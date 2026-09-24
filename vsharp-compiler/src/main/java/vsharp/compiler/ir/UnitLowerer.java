package vsharp.compiler.ir;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import vsharp.compiler.api.UnitAnalysis;
import vsharp.compiler.semantics.binding.BoundExpression;
import vsharp.compiler.semantics.symbols.EnumMemberSymbol;
import vsharp.compiler.semantics.symbols.FieldSymbol;
import vsharp.compiler.semantics.symbols.FunctionSymbol;
import vsharp.compiler.semantics.symbols.NamedTypeSymbol;
import vsharp.compiler.semantics.symbols.SourceLocation;
import vsharp.compiler.semantics.symbols.Symbol;
import vsharp.compiler.semantics.types.BuiltinType;
import vsharp.compiler.semantics.types.TaskTypes;
import vsharp.compiler.source.SourceSpan;
import vsharp.compiler.syntax.AuxiliarySyntax;
import vsharp.compiler.syntax.DeclarationSyntax;
import vsharp.compiler.syntax.ExpressionSyntax;
import vsharp.compiler.syntax.StatementSyntax;
import vsharp.compiler.syntax.SyntaxKind;

/// Lowers every currently supported callable in one fully analysed source unit.
///
/// This deliberately accepts only [UnitAnalysis.Analysed]: IR cannot be made from a tree that
/// has not passed declaration, expression and flow analysis. The facade will invoke this only
/// once every valid statement and expression form has a lowering rule.
public final class UnitLowerer {

    /// The unspeakable name of the synthetic callable carrying a holder's static field
    /// initializers. The backend emits it as `<clinit>`; no source can declare it.
    public static final String STATIC_INITIALIZER_NAME = "<static-init>";

    private final UnitAnalysis.Analysed analysis;
    private final List<IrFunction> functions = new ArrayList<>();
    private final List<FieldSymbol> fields = new ArrayList<>();
    /// Positional `record struct` shapes declared in this file, in declaration order.
    private final List<IrRecordStruct> records = new ArrayList<>();
    private final List<IrEnum> enums = new ArrayList<>();

    /// Static field initializers per declaring holder, in source order.
    private final Map<String, List<IrStatement>> staticInitializers = new LinkedHashMap<>();

    private UnitLowerer(UnitAnalysis.Analysed analysis) {
        this.analysis = Objects.requireNonNull(analysis, "analysis");
    }

    public static IrUnit lower(UnitAnalysis.Analysed analysis) {
        UnitLowerer lowerer = new UnitLowerer(analysis);
        lowerer.lowerUnit();
        return new IrUnit(analysis.file(), lowerer.functions, lowerer.fields, lowerer.records, lowerer.enums);
    }

    private void lowerUnit() {
        for (DeclarationSyntax declaration : analysis.syntax().declarations()) {
            lowerDeclaration(declaration);
        }
        lowerLambdaBodies();
        addStaticInitializers();
        if (!analysis.syntax().statements().isEmpty()) {
            FunctionSymbol function = analysis.model().topLevelFunction().orElseThrow(
                    () -> new IllegalArgumentException("top-level statements have no function"));
            StatementSyntax.Block body = new StatementSyntax.Block(analysis.syntax().span(),
                    analysis.syntax().statements());
            functions.add(implemented(function, body, List.of()));
            collectLocalFunctions(body.statements());
        }
    }

    private void lowerDeclaration(DeclarationSyntax declaration) {
        switch (declaration) {
            case DeclarationSyntax.Namespace namespace -> namespace.declarations()
                    .forEach(this::lowerDeclaration);
            case DeclarationSyntax.StaticContainer container -> container.members()
                    .forEach(this::lowerDeclaration);
            case DeclarationSyntax.Struct structure -> {
                lowerRecordStruct(structure);
                structure.members().forEach(this::lowerDeclaration);
            }
            case DeclarationSyntax.Method method -> lowerCallable(declaration, method.body(),
                    method.expressionBody());
            case DeclarationSyntax.Operator operator -> lowerCallable(declaration, operator.body(),
                    operator.expressionBody());
            case DeclarationSyntax.ConversionOperator conversion -> lowerCallable(declaration,
                    conversion.body(), conversion.expressionBody());
            case DeclarationSyntax.Enum enumeration -> {
                lowerEnum(enumeration);
            }
            case DeclarationSyntax.Field field -> lowerField(field);
            case DeclarationSyntax.Unsupported ignored -> throw new IllegalArgumentException(
                    "unsupported declaration reached IR lowering: " + declaration.span());
        }
    }

    /// Materialises a positional `record struct` for the backend: its components are ordinary
    /// instance fields of the holder, and its constructor is synthesized structurally.
    private void lowerRecordStruct(DeclarationSyntax.Struct structure) {
        analysis.model().declaredSymbol(structure)
                .filter(NamedTypeSymbol.class::isInstance)
                .map(NamedTypeSymbol.class::cast)
                .flatMap(named -> analysis.model().positionalLayout(named)
                        .map(layout -> new IrRecordStruct(named.qualifiedName(),
                                layout.components(), layout.constructor())))
                .ifPresent(record -> {
                    fields.addAll(record.components());
                    records.add(record);
                });
    }

    private void lowerEnum(DeclarationSyntax.Enum enumeration) {
        Symbol owner = analysis.model().declaredSymbol(enumeration).orElse(null);
        if (owner instanceof NamedTypeSymbol named) {
            List<EnumMemberSymbol> members = new ArrayList<>();
            for (AuxiliarySyntax.EnumMember memberSyntax : enumeration.members()) {
                Symbol memberSymbol = analysis.model().declaredSymbol(memberSyntax).orElse(null);
                if (memberSymbol instanceof EnumMemberSymbol enumMember) {
                    members.add(enumMember);
                }
            }
            enums.add(new IrEnum(named, members));
        }
    }

    private void lowerField(DeclarationSyntax.Field field) {
        for (AuxiliarySyntax.VariableDeclarator variable : field.variables()) {
            FieldSymbol symbol = analysis.model().declaredSymbol(variable)
                    .filter(FieldSymbol.class::isInstance)
                    .map(FieldSymbol.class::cast)
                    .orElse(null);
            if (symbol == null) {
                continue;
            }
            fields.add(symbol);
            if (variable.initializer() == null
                    || !symbol.modifiers().contains(SyntaxKind.STATIC)) {
                continue;
            }
            // A static field initializer is a statement that has to run before anything reads
            // the field. C# gives the class an implicit static constructor for exactly that,
            // and the JVM's `<clinit>` is the same mechanism, so the initializers of one
            // holder are collected here in declaration order and become its body.
            IrExpression value = SyntaxExpressionLowerer.lower(variable.initializer(),
                    analysis.expressions(), analysis.model());
            staticInitializers.computeIfAbsent(holderOf(symbol), holder -> new ArrayList<>())
                    .add(new IrStatement.Expression(variable.initializer().span(),
                            new IrExpression.Store(IrValueType.of(symbol.type()), symbol, value)));
        }
    }

    /// The qualified name of the type declaring `field`, which is also the class its
    /// initializers must run in.
    private static String holderOf(FieldSymbol field) {
        String qualifiedName = field.qualifiedName();
        int dot = qualifiedName.lastIndexOf('.');
        return dot <= 0 ? "" : qualifiedName.substring(0, dot);
    }

    /// Builds one synthetic callable per holder carrying its static field initializers.
    ///
    /// The name is unspeakable so no source can collide with it, and the backend recognises it
    /// to emit `<clinit>`; giving it an ordinary `FunctionSymbol` means slot allocation,
    /// emission and holder placement all work exactly as they do for a written method.
    /// Suffix of the callable an `async` body is emitted as.
    private static final String ASYNC_BODY_SUFFIX = "$async";

    private void addStaticInitializers() {
        for (Map.Entry<String, List<IrStatement>> entry : staticInitializers.entrySet()) {
            String holder = entry.getKey();
            List<IrStatement> statements = entry.getValue();
            SourceSpan span = statements.getFirst().span();
            FunctionSymbol symbol = new FunctionSymbol(STATIC_INITIALIZER_NAME,
                    holder.isEmpty() ? STATIC_INITIALIZER_NAME
                            : holder + "." + STATIC_INITIALIZER_NAME,
                    new SourceLocation(analysis.file(), span), BuiltinType.VOID, List.of(),
                    List.of(), List.of(SyntaxKind.STATIC), false, true);
            functions.add(new IrFunction.Implemented(symbol,
                    new IrStatement.Block(span, statements), true, List.of()));
        }
    }

    private void lowerCallable(DeclarationSyntax declaration, StatementSyntax.Block body,
            ExpressionSyntax expressionBody) {
        Symbol declared = analysis.model().declaredSymbol(declaration).orElseThrow(
                () -> new IllegalArgumentException("callable has no symbol: " + declaration));
        if (!(declared instanceof FunctionSymbol function)) {
            throw new IllegalArgumentException("callable does not denote a function: " + declared);
        }
        if (body == null && expressionBody == null) {
            functions.add(new IrFunction.External(function));
            return;
        }
        if (function.isAsync()) {
            // Two callables from one declaration: the written body under a synthesized
            // name returning the task's result, and the declared entry that starts it.
            FunctionSymbol bodySymbol = asyncBodySymbol(function);
            functions.add(body != null
                    ? implemented(bodySymbol, function, body, annotations(declaration))
                    : expressionBodied(bodySymbol, expressionBody, annotations(declaration)));
            functions.add(new IrFunction.AsyncEntry(function, bodySymbol));
            if (body != null) {
                collectLocalFunctions(body.statements());
            }
            return;
        }
        if (body != null) {
            functions.add(implemented(function, body, annotations(declaration)));
            collectLocalFunctions(body.statements());
            return;
        }
        functions.add(expressionBodied(function, expressionBody, annotations(declaration)));
    }

    /// The private callable an `async` declaration's body becomes.
    ///
    /// It keeps the declaration's own parameter symbols, so every load inside the body still
    /// resolves to the same slot, and returns the task's result type, which is what the body's
    /// `return` statements were bound against. The `$` in the name is legal in a JVM method
    /// name and unspeakable in V#, so it cannot collide with a declared overload.
    private static FunctionSymbol asyncBodySymbol(FunctionSymbol function) {
        List<SyntaxKind> modifiers = new ArrayList<>(function.modifiers());
        modifiers.remove(SyntaxKind.ASYNC);
        return new FunctionSymbol(function.name() + ASYNC_BODY_SUFFIX,
                function.qualifiedName() + ASYNC_BODY_SUFFIX, function.location(),
                TaskTypes.bodyResultOf(function.returnType()), function.typeParameters(),
                function.parameters(), List.copyOf(modifiers), function.localFunction(), true);
    }

    /// Lowers a body whose flow was analysed under a different symbol.
    ///
    /// An `async` body is emitted as a synthesized callable, but flow analysis ran over the
    /// declaration, so the reachability question has to be asked with the symbol that was
    /// analysed. Getting this wrong is silent: the emitted method simply loses its implicit
    /// `return` and ends in the unreachable-tail `athrow` instead.
    private IrFunction.Implemented implemented(FunctionSymbol emitted, FunctionSymbol analysed,
            StatementSyntax.Block body, List<IrFunction.IrAnnotation> annotations) {
        IrStatement lowered = StatementLowerer.lower(body, analysis.file(), analysis.model(),
                analysis.expressions(), analysis.flow());
        if (!(lowered instanceof IrStatement.Block block)) {
            throw new IllegalStateException("block lowering changed statement shape");
        }
        // Both queries are asked with the *analysed* symbol. Reachability was recorded under
        // it, and so is ownership of every parameter and local: the capture collector decides
        // ownership by qualified-name prefix, so asking with the synthesized `$async` name
        // made a body's own parameters look like captured outer values and gave the emitted
        // method a second, cell-shaped copy of each one.
        return new IrFunction.Implemented(emitted, block,
                emitted.returnType() == BuiltinType.VOID && analysis.flow().endReachable(analysed),
                CaptureCollector.collect(analysed, block), annotations);
    }

    private IrFunction.Implemented implemented(FunctionSymbol function, StatementSyntax.Block body,
            List<IrFunction.IrAnnotation> annotations) {
        IrStatement lowered = StatementLowerer.lower(body, analysis.file(), analysis.model(),
                analysis.expressions(), analysis.flow());
        if (!(lowered instanceof IrStatement.Block block)) {
            throw new IllegalStateException("block lowering changed statement shape");
        }
        return new IrFunction.Implemented(function, block,
                function.returnType() == BuiltinType.VOID && analysis.flow().endReachable(function),
                CaptureCollector.collect(function, block), annotations);
    }

    private IrFunction.Implemented expressionBodied(FunctionSymbol function,
            ExpressionSyntax expressionBody, List<IrFunction.IrAnnotation> annotations) {
        IrExpression lowered = SyntaxExpressionLowerer.lower(expressionBody, analysis.expressions(),
                analysis.model());
        List<IrStatement> statements = function.returnType() == BuiltinType.VOID
                ? List.of(new IrStatement.Expression(expressionBody.span(), lowered),
                        new IrStatement.Return(expressionBody.span(), null))
                : List.of(new IrStatement.Return(expressionBody.span(), lowered));
        IrStatement.Block body = new IrStatement.Block(expressionBody.span(), statements);
        return new IrFunction.Implemented(function, body, false,
                CaptureCollector.collect(function, body), annotations);
    }

    private List<IrFunction.IrAnnotation> annotations(DeclarationSyntax declaration) {
        List<AuxiliarySyntax.AttributeList> lists = switch (declaration) {
            case DeclarationSyntax.Method method -> method.attributes();
            case DeclarationSyntax.Operator operator -> operator.attributes();
            case DeclarationSyntax.ConversionOperator conversion -> conversion.attributes();
            default -> List.of();
        };
        List<IrFunction.IrAnnotation> result = new ArrayList<>();
        for (AuxiliarySyntax.AttributeList list : lists) {
            for (AuxiliarySyntax.Attribute attribute : list.attributes()) {
                analysis.model().typeOf(attribute.name())
                        .filter(NamedTypeSymbol.class::isInstance)
                        .map(NamedTypeSymbol.class::cast)
                        .ifPresent(type -> result.add(
                                new IrFunction.IrAnnotation(type.qualifiedName())));
            }
        }
        return List.copyOf(result);
    }

    /// Emits one static callable per lambda that reached a functional-interface target
    ///.
    ///
    /// A lambda is an expression, so it is not reachable from the declaration walk above; the
    /// binder's deterministic expression order is what fixes the emission order here. The body
    /// goes through the ordinary expression-bodied or block-bodied callable path, which is what
    /// makes a lambda body an ordinary method rather than a second kind of body.
    ///
    /// A statement body also carries the local functions declared inside it, collected
    /// here because the declaration walk never reaches a lambda.
    private void lowerLambdaBodies() {
        for (BoundExpression expression : analysis.expressions().expressions()) {
            if (expression instanceof BoundExpression.Lambda lambda) {
                if (lambda.blockBody() != null) {
                    functions.add(implemented(lambda.function(), lambda.blockBody(), List.of()));
                    collectLocalFunctions(lambda.blockBody().statements());
                } else {
                    functions.add(expressionBodied(lambda.function(), lambda.bodySyntax(),
                            List.of()));
                }
            }
        }
    }

    private void collectLocalFunctions(List<StatementSyntax> statements) {
        for (StatementSyntax statement : statements) {
            collectLocalFunctions(statement);
        }
    }

    private void collectLocalFunctions(StatementSyntax statement) {
        switch (statement) {
            case StatementSyntax.LocalFunction local -> lowerCallable(local.declaration(),
                    local.declaration().body(), local.declaration().expressionBody());
            case StatementSyntax.Block block -> collectLocalFunctions(block.statements());
            case StatementSyntax.If conditional -> {
                collectLocalFunctions(conditional.whenTrue());
                if (conditional.whenFalse() != null) collectLocalFunctions(conditional.whenFalse());
            }
            case StatementSyntax.While loop -> collectLocalFunctions(loop.body());
            case StatementSyntax.Do loop -> collectLocalFunctions(loop.body());
            case StatementSyntax.For loop -> {
                collectLocalFunctions(loop.initializers());
                collectLocalFunctions(loop.body());
            }
            case StatementSyntax.Foreach loop -> collectLocalFunctions(loop.body());
            case StatementSyntax.Switch selection -> selection.sections().forEach(section ->
                    collectLocalFunctions(section.statements()));
            case StatementSyntax.Try attempt -> {
                collectLocalFunctions(attempt.body().statements());
                attempt.catches().forEach(clause -> collectLocalFunctions(clause.body().statements()));
                if (attempt.finallyBody() != null) collectLocalFunctions(
                        attempt.finallyBody().statements());
            }
            case StatementSyntax.Using using -> {
                if (using.resource() instanceof StatementSyntax.LocalDeclaration) {
                    // A using declaration cannot contain a local-function declaration.
                }
                if (using.body() != null) collectLocalFunctions(using.body());
            }
            case StatementSyntax.Lock locked -> collectLocalFunctions(locked.body());
            case StatementSyntax.Checked checked -> collectLocalFunctions(checked.body().statements());
            case StatementSyntax.Empty ignored -> { }
            case StatementSyntax.Expression ignored -> { }
            case StatementSyntax.LocalDeclaration ignored -> { }
            case StatementSyntax.Break ignored -> { }
            case StatementSyntax.Continue ignored -> { }
            case StatementSyntax.Return ignored -> { }
            case StatementSyntax.Throw ignored -> { }
            case StatementSyntax.Goto ignored -> { }
            case StatementSyntax.Labeled labeled -> collectLocalFunctions(labeled.statement());
            case StatementSyntax.Yield ignored -> { }
        }
    }


}
