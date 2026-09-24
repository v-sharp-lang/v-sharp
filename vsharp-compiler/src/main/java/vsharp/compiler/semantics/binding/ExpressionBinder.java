package vsharp.compiler.semantics.binding;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import vsharp.compiler.diagnostics.DiagnosticBag;
import vsharp.compiler.diagnostics.Diagnostic;
import vsharp.compiler.diagnostics.DiagnosticCode;
import vsharp.compiler.diagnostics.Severity;
import vsharp.runtime.VsAsync;
import vsharp.compiler.semantics.constants.ConstantEvaluator;
import vsharp.compiler.semantics.constants.ConstantValue;
import vsharp.compiler.semantics.conversions.Conversion;
import vsharp.compiler.semantics.conversions.ConversionKind;
import vsharp.compiler.semantics.conversions.Conversions;
import vsharp.compiler.semantics.conversions.LambdaTargets;
import vsharp.compiler.semantics.overloads.Candidate;
import vsharp.compiler.semantics.overloads.GenericInference;
import vsharp.compiler.semantics.overloads.OverloadResolver;
import vsharp.compiler.semantics.overloads.OverloadResult;
import vsharp.compiler.semantics.symbols.ContainerSymbol;
import vsharp.compiler.semantics.symbols.EnumMemberSymbol;
import vsharp.compiler.semantics.symbols.FieldSymbol;
import vsharp.compiler.semantics.symbols.FunctionSymbol;
import vsharp.compiler.semantics.symbols.LocalSymbol;
import vsharp.compiler.semantics.symbols.NamedTypeSymbol;
import vsharp.compiler.semantics.symbols.NamespaceSymbol;
import vsharp.compiler.semantics.symbols.ParameterSymbol;
import vsharp.compiler.semantics.symbols.Scope;
import vsharp.compiler.semantics.symbols.Symbol;
import vsharp.compiler.semantics.symbols.SymbolKind;
import vsharp.compiler.semantics.symbols.TypeParameterSymbol;
import vsharp.compiler.semantics.types.BuiltinType;
import vsharp.compiler.semantics.types.TaskTypes;
import vsharp.compiler.semantics.types.CorelibCarriers;
import vsharp.compiler.semantics.types.JvmTypeKind;
import vsharp.compiler.semantics.types.TypeSymbol;
import vsharp.compiler.source.SourceFile;
import vsharp.compiler.source.SourceSpan;
import vsharp.compiler.syntax.AuxiliarySyntax;
import vsharp.compiler.syntax.DeclarationSyntax;
import vsharp.compiler.syntax.ExpressionSyntax;
import vsharp.compiler.syntax.LiteralType;
import vsharp.runtime.VsDecimal;
import vsharp.compiler.syntax.PatternSyntax;
import vsharp.compiler.syntax.StatementSyntax;
import vsharp.compiler.syntax.SyntaxKind;
import vsharp.compiler.syntax.SyntaxNode;
import vsharp.compiler.syntax.TypeSyntax;

/// Resolves value names and the predefined core expression operators.
///
/// Invocation betterness, user-defined operators, member access, full conversions and
/// constant folding are deliberately represented as deferred expressions. Children whose
/// lexical scopes are already represented are still bound; lambda and switch-arm bodies
/// wait for their own declaration-space milestone rather than borrowing the wrong scope.
public final class ExpressionBinder {

    /// The JDK's key/value pair, the one Java type V# deconstructs positionally.
    private static final String MAP_ENTRY = "java.util.Map$Entry";

    /// The `System` type each builtin keyword names, for the member lookup only.
    ///
    /// A builtin type is not a declaration, so `i.ToString()` has nowhere to look until the
    /// receiver type is exchanged for the corelib struct carrying the curated member set.
    /// The map is the whole admission list: a keyword absent here has no instance members at
    /// all, and one present here has exactly the members `corelib.vs` declares - never more.
    private static final Map<BuiltinType, String> CORELIB_TYPE_NAMES = corelibTypeNames();

    /// The `MinValue`/`MaxValue` pair of every builtin type that has one, in its own carrier.
    ///
    /// These are compile-time constants in C# (`§7.3`), so they are bound as literals rather
    /// than as corelib fields: a field would emit a read of a class corelib never generates,
    /// and would lose the folding every constant context depends on. `uint`/`ulong`/`nuint`
    /// hold the bit pattern, matching how the lexer stores an unsigned literal.
    private static final Map<BuiltinType, Object> MIN_VALUES = minValues();
    private static final Map<BuiltinType, Object> MAX_VALUES = maxValues();

    /// The remaining `float`/`double` constants, keyed by member name.
    ///
    /// C# declares `NaN`, `PositiveInfinity`, `NegativeInfinity` and `Epsilon` as `const` on
    /// both floating types, so they fold and bind exactly like the pair above. `Epsilon` is
    /// the smallest positive value, which is the one Java spells `MIN_VALUE` - the reason
    /// `MinValue` above cannot be taken from the JDK constant of the same name.
    private static final Map<String, Map<BuiltinType, Object>> FLOATING_CONSTANTS =
            floatingConstants();

    private static Map<BuiltinType, String> corelibTypeNames() {
        Map<BuiltinType, String> names = new EnumMap<>(BuiltinType.class);
        names.put(BuiltinType.SBYTE, "SByte");
        names.put(BuiltinType.BYTE, "Byte");
        names.put(BuiltinType.SHORT, "Int16");
        names.put(BuiltinType.USHORT, "UInt16");
        names.put(BuiltinType.INT, "Int32");
        names.put(BuiltinType.UINT, "UInt32");
        names.put(BuiltinType.LONG, "Int64");
        names.put(BuiltinType.ULONG, "UInt64");
        names.put(BuiltinType.NINT, "IntPtr");
        names.put(BuiltinType.NUINT, "UIntPtr");
        names.put(BuiltinType.DECIMAL, "Decimal");
        names.put(BuiltinType.FLOAT, "Single");
        names.put(BuiltinType.DOUBLE, "Double");
        names.put(BuiltinType.BOOL, "Boolean");
        names.put(BuiltinType.CHAR, "Char");
        names.put(BuiltinType.STRING, "String");
        names.put(BuiltinType.OBJECT, "Object");
        return Map.copyOf(names);
    }

    private static Map<BuiltinType, Object> minValues() {
        Map<BuiltinType, Object> values = new EnumMap<>(BuiltinType.class);
        values.put(BuiltinType.SBYTE, (int) Byte.MIN_VALUE);
        values.put(BuiltinType.BYTE, 0);
        values.put(BuiltinType.SHORT, (int) Short.MIN_VALUE);
        values.put(BuiltinType.USHORT, 0);
        values.put(BuiltinType.INT, Integer.MIN_VALUE);
        values.put(BuiltinType.UINT, 0);
        values.put(BuiltinType.LONG, Long.MIN_VALUE);
        values.put(BuiltinType.ULONG, 0L);
        values.put(BuiltinType.NINT, Long.MIN_VALUE);
        values.put(BuiltinType.NUINT, 0L);
        values.put(BuiltinType.CHAR, Character.MIN_VALUE);
        // C#'s `float.MinValue` is the most negative finite value, not Java's smallest
        // positive one; the two names do not mean the same thing.
        values.put(BuiltinType.FLOAT, -Float.MAX_VALUE);
        values.put(BuiltinType.DOUBLE, -Double.MAX_VALUE);
        return Map.copyOf(values);
    }

    private static Map<BuiltinType, Object> maxValues() {
        Map<BuiltinType, Object> values = new EnumMap<>(BuiltinType.class);
        values.put(BuiltinType.SBYTE, (int) Byte.MAX_VALUE);
        values.put(BuiltinType.BYTE, 255);
        values.put(BuiltinType.SHORT, (int) Short.MAX_VALUE);
        values.put(BuiltinType.USHORT, 65535);
        values.put(BuiltinType.INT, Integer.MAX_VALUE);
        values.put(BuiltinType.UINT, -1);
        values.put(BuiltinType.LONG, Long.MAX_VALUE);
        values.put(BuiltinType.ULONG, -1L);
        values.put(BuiltinType.NINT, Long.MAX_VALUE);
        values.put(BuiltinType.NUINT, -1L);
        values.put(BuiltinType.CHAR, Character.MAX_VALUE);
        values.put(BuiltinType.FLOAT, Float.MAX_VALUE);
        values.put(BuiltinType.DOUBLE, Double.MAX_VALUE);
        return Map.copyOf(values);
    }

    private static Map<String, Map<BuiltinType, Object>> floatingConstants() {
        Map<String, Map<BuiltinType, Object>> constants = new LinkedHashMap<>();
        constants.put("NaN", floatingPair(Float.NaN, Double.NaN));
        constants.put("PositiveInfinity",
                floatingPair(Float.POSITIVE_INFINITY, Double.POSITIVE_INFINITY));
        constants.put("NegativeInfinity",
                floatingPair(Float.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY));
        constants.put("Epsilon", floatingPair(Float.MIN_VALUE, Double.MIN_VALUE));
        return Map.copyOf(constants);
    }

    private static Map<BuiltinType, Object> floatingPair(float single, double both) {
        Map<BuiltinType, Object> values = new EnumMap<>(BuiltinType.class);
        values.put(BuiltinType.FLOAT, single);
        values.put(BuiltinType.DOUBLE, both);
        return Map.copyOf(values);
    }

    private ExpressionBinder() {
        throw new AssertionError("No instances");
    }

    /// Binds every expression reachable from `unit` using declaration scopes from `model`.
    public static ExpressionBinding bind(SourceFile file, AuxiliarySyntax.CompilationUnit unit,
            SemanticModel model, DiagnosticBag diagnostics) {
        return bind(file, unit, model, diagnostics, false);
    }

    /// Binds every expression reachable from `unit`, accepting unbounded task joins.
    ///
    /// `allowUnboundedJoins` only changes the severity of [DiagnosticCode#UNBOUNDED_TASK_JOIN];
    /// the call site is reported either way, so a build that opts in still sees every place it
    /// opted in at.
    public static ExpressionBinding bind(SourceFile file, AuxiliarySyntax.CompilationUnit unit,
            SemanticModel model, DiagnosticBag diagnostics, boolean allowUnboundedJoins) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(diagnostics, "diagnostics");
        return new Binder(file, unit, model, diagnostics, allowUnboundedJoins).bind();
    }

    /// Binds one `const` field initializer in `scope`, for [ConstantFieldBinder].
    ///
    /// Only the expression is bound: an initializer declares nothing, and the unit it lives in
    /// is walked by the ordinary pass afterwards, which binds this same expression again and
    /// is the pass that reports what is wrong with it. `diagnostics` is therefore the constant
    /// pass's scratch bag - reporting from here too would say everything twice.
    ///
    /// `namespaceName` is the namespace body the field is declared in, because a `using`
    /// written inside a namespace body is in scope only there and the walk that found
    /// the field is what knows which body that is.
    static BoundExpression bindConstantInitializer(SourceFile file,
            AuxiliarySyntax.CompilationUnit unit, SemanticModel model, Scope scope,
            String namespaceName, ExpressionSyntax initializer, DiagnosticBag diagnostics) {
        Binder binder = new Binder(file, unit, model, diagnostics);
        binder.currentNamespace = namespaceName;
        return binder.bindExpression(initializer, scope);
    }

    private static final class Binder {

        private final SourceFile file;
        private final AuxiliarySyntax.CompilationUnit unit;
        private final SemanticModel model;
        private final DiagnosticBag diagnostics;
        private final List<ConstantPatternCheck> pendingConstantChecks = new ArrayList<>();
        private final IdentityHashMap<ExpressionSyntax, BoundExpression> expressions =
                new IdentityHashMap<>();
        private final List<BoundExpression> bindingOrder = new ArrayList<>();
        /// The collection and element types each list pattern tests.
        private final IdentityHashMap<PatternSyntax.ListPattern, ListPatternShape> listShapes =
                new IdentityHashMap<>();
        /// The components each recursive pattern tests, resolved once here.
        private final IdentityHashMap<PatternSyntax.Recursive, List<PatternComponent>>
                patternComponents = new IdentityHashMap<>();
        private final IdentityHashMap<LocalSymbol, TypeSymbol> inferredLocalTypes =
                new IdentityHashMap<>();
        /// The syntax each still-untyped lambda was deferred from. A lambda has no type
        /// of its own, so it is bound to a node only once a target type names its parameters.
        private final IdentityHashMap<BoundExpression, ExpressionSyntax.Lambda> lambdaSyntax =
                new IdentityHashMap<>();
        /// Calls whose result still carries a type parameter no argument mentioned.
        /// Only a target type can close one, and the conversion that does removes the entry;
        /// whatever remains at the end of the unit was never targeted and is CS0411. Keyed by
        /// identity because the open expression is exactly the instance a conversion wraps.
        private final IdentityHashMap<BoundExpression, ExpressionSyntax> openResults =
                new IdentityHashMap<>();
        /// The function each open result came from, for the diagnostic's name.
        private final IdentityHashMap<BoundExpression, String> openResultNames =
                new IdentityHashMap<>();
        /// Lambdas whose target parameter type was still open when their call was bound, in
        /// binding order. `names.Sort(Comparator.ComparingInt(n => n.Length))` leaves
        /// one: the inner call is `Comparator<T>` with `T` open, so `ToIntFunction<T>` cannot
        /// yet type `n`. The outer conversion is what names `T`, and flushing the pending entry
        /// there binds the body against the closed type.
        private final List<PendingLambda> pendingLambdas = new ArrayList<>();
        /// The half-open range of [#pendingLambdas] each bound argument produced, so a flush
        /// touches only the lambdas that came from that argument's own subtree.
        private final IdentityHashMap<BoundExpression, int[]> pendingRanges =
                new IdentityHashMap<>();
        /// The lambda parameters a target type gave concrete types. Scopes were built before
        /// any target was known, so lookup still finds the declaration-time symbol; every use
        /// is retyped through this map, exactly as an inferred `var` local is.
        private final IdentityHashMap<Symbol, ParameterSymbol> lambdaParameters =
                new IdentityHashMap<>();
        /// One bound node per lambda syntax, so a lambda reached twice (an argument re-examined
        /// after overload resolution) is bound - and diagnosed - exactly once.
        private final IdentityHashMap<ExpressionSyntax.Lambda, BoundExpression> lambdaValues =
                new IdentityHashMap<>();
        /// The values the lambda currently being bound may use without capturing: its own
        /// parameters. Anything else lexical makes it a closure.
        private Set<Symbol> lambdaOwnValues;
        private boolean lambdaCaptures;
        /// Nesting depth of `catch` clause *bodies* currently being bound, which is what
        /// decides whether a bare `throw;` has an exception to rethrow (C# §13.10.6, CS0156).
        /// A `finally` body and the guarded `try` body are deliberately not counted: neither
        /// is handling an exception, and a `throw;` in either is an error in C# too.
        private int catchBodyDepth;

        private final boolean allowUnboundedJoins;

        Binder(SourceFile file, AuxiliarySyntax.CompilationUnit unit, SemanticModel model,
                DiagnosticBag diagnostics) {
            this(file, unit, model, diagnostics, false);
        }

        Binder(SourceFile file, AuxiliarySyntax.CompilationUnit unit, SemanticModel model,
                DiagnosticBag diagnostics, boolean allowUnboundedJoins) {
            this.file = file;
            this.unit = unit;
            this.model = model;
            this.diagnostics = diagnostics;
            this.allowUnboundedJoins = allowUnboundedJoins;
        }

        /// Records that the lambda being bound reads a lexical value it does not own.
        ///
        /// `LambdaMetafactory` takes captured values as the *leading* parameters of the
        /// implementation method, while V# appends a local function's captures after its
        /// declared ones. Rather than emit one convention and call it with the other,
        /// a capturing lambda is refused until that ordering is made explicit.
        private void noteLambdaCapture(Symbol value) {
            if (lambdaOwnValues != null
                    && (value instanceof LocalSymbol || value instanceof ParameterSymbol)
                    && !lambdaOwnValues.contains(value)) {
                lambdaCaptures = true;
            }
        }

        /// A lambda parameter's declaration-time symbol, replaced by the one a target type
        /// gave a concrete type. Every other symbol is returned unchanged.
        private Symbol retypedLambdaParameter(Symbol symbol) {
            ParameterSymbol typed = lambdaParameters.get(symbol);
            return typed == null ? symbol : typed;
        }

        ExpressionBinding bind() {
            Scope global = model.scopeFor(unit).orElse(model.globalScope());
            unit.declarations().forEach(declaration -> bindDeclaration(declaration, global));
            if (!unit.statements().isEmpty()) {
                Scope topLevel = model.topLevelFunction().flatMap(model::scopeOf)
                        .orElse(global);
                FunctionSymbol function = model.topLevelFunction().orElse(null);
                unit.statements().forEach(statement -> bindStatement(statement, topLevel,
                        function));
            }
            reportUntargetedLambdas();
            reportUntargetedOpenResults();
            return new ExpressionBinding(expressions, bindingOrder, inferredLocalTypes,
                    patternComponents, listShapes);
        }

        /// The namespace body currently being walked, for the `using` directives written in it.
        private String currentNamespace = "";

        private static String joinNamespace(String enclosing, String written) {
            if (written.isEmpty()) {
                return enclosing;
            }
            return enclosing.isEmpty() ? written : enclosing + "." + written;
        }

        private void bindDeclaration(DeclarationSyntax declaration, Scope enclosing) {
            switch (declaration) {
                case DeclarationSyntax.Namespace namespace -> {
                    Scope scope = model.scopeFor(namespace).orElse(enclosing);
                    // A `using` written in this body is in scope for everything inside it
                    //, so the body's name travels with the walk and is what the
                    // scoped lookups are answered against.
                    String enclosingNamespace = currentNamespace;
                    currentNamespace = joinNamespace(enclosingNamespace,
                            namespace.name().segments().stream()
                                    .map(vsharp.compiler.syntax.TypeSyntax.Segment::identifier)
                                    .reduce((left, right) -> left + "." + right)
                                    .orElse(""));
                    namespace.declarations().forEach(member -> bindDeclaration(member, scope));
                    FunctionSymbol function = synthesizedFunction(scope);
                    Scope statementScope = function == null
                            ? scope : model.scopeOf(function).orElse(scope);
                    namespace.statements().forEach(statement -> bindStatement(statement,
                            statementScope, function));
                    currentNamespace = enclosingNamespace;
                }
                case DeclarationSyntax.StaticContainer container -> {
                    Scope scope = model.scopeFor(container).orElse(enclosing);
                    container.members().forEach(member -> bindDeclaration(member, scope));
                }
                case DeclarationSyntax.Struct structure -> {
                    Scope scope = model.scopeFor(structure).orElse(enclosing);
                    for (AuxiliarySyntax.Parameter parameter : structure.primaryParameters()) {
                        if (parameter.defaultValue() != null) {
                            bindExpression(parameter.defaultValue(), scope);
                        }
                    }
                    structure.members().forEach(member -> bindDeclaration(member, scope));
                }
                case DeclarationSyntax.Enum enumeration -> {
                    Scope scope = model.scopeFor(enumeration).orElse(enclosing);
                    for (AuxiliarySyntax.EnumMember member : enumeration.members()) {
                        if (member.value() != null) {
                            bindExpression(member.value(), scope);
                        }
                    }
                }
                case DeclarationSyntax.Field field -> bindField(field, enclosing);
                case DeclarationSyntax.Method method -> bindCallable(method, enclosing);
                case DeclarationSyntax.Operator operator -> bindCallable(operator, enclosing);
                case DeclarationSyntax.ConversionOperator conversion ->
                        bindCallable(conversion, enclosing);
                case DeclarationSyntax.Unsupported ignored -> {
                    // Syntax diagnostics already bounded this excluded declaration.
                }
            }
        }

        private void bindField(DeclarationSyntax.Field field, Scope scope) {
            for (AuxiliarySyntax.VariableDeclarator variable : field.variables()) {
                if (variable.initializer() == null) {
                    continue;
                }
                BoundExpression initializer = bindExpression(variable.initializer(), scope);
                Symbol declared = model.declaredSymbol(variable).orElse(null);
                if (declared instanceof FieldSymbol symbol) {
                    requireImplicitConversion(initializer, symbol.type(), variable.initializer());
                }
            }
        }

        private void bindCallable(DeclarationSyntax declaration, Scope enclosing) {
            Symbol declared = model.declaredSymbol(declaration).orElse(null);
            if (!(declared instanceof FunctionSymbol function)) {
                return;
            }
            Scope scope = model.scopeOf(function).orElse(enclosing);
            switch (declaration) {
                case DeclarationSyntax.Method method -> {
                    bindParameterDefaults(method.parameters(), function, scope);
                    if (method.expressionBody() != null) {
                        BoundExpression body = bindExpression(method.expressionBody(), scope,
                                bodyReturnType(function));
                        requireReturnConversion(body, function, method.expressionBody());
                    }
                    if (method.body() != null) {
                        bindBlock(method.body(), scope, function);
                    }
                }
                case DeclarationSyntax.Operator operator -> {
                    bindParameterDefaults(operator.parameters(), function, scope);
                    if (operator.expressionBody() != null) {
                        BoundExpression body = bindExpression(operator.expressionBody(), scope,
                                function.returnType());
                        requireReturnConversion(body, function, operator.expressionBody());
                    }
                    if (operator.body() != null) {
                        bindBlock(operator.body(), scope, function);
                    }
                }
                case DeclarationSyntax.ConversionOperator conversion -> {
                    bindParameterDefaults(List.of(conversion.parameter()), function, scope);
                    if (conversion.expressionBody() != null) {
                        BoundExpression body = bindExpression(conversion.expressionBody(), scope,
                                function.returnType());
                        requireReturnConversion(body, function, conversion.expressionBody());
                    }
                    if (conversion.body() != null) {
                        bindBlock(conversion.body(), scope, function);
                    }
                }
                case DeclarationSyntax.Namespace ignored -> {
                }
                case DeclarationSyntax.StaticContainer ignored -> {
                }
                case DeclarationSyntax.Enum ignored -> {
                }
                case DeclarationSyntax.Struct ignored -> {
                }
                case DeclarationSyntax.Field ignored -> {
                }
                case DeclarationSyntax.Unsupported ignored -> {
                }
            }
        }

        private void bindParameterDefaults(List<AuxiliarySyntax.Parameter> parameters,
                FunctionSymbol function, Scope scope) {
            for (int index = 0; index < parameters.size(); index++) {
                AuxiliarySyntax.Parameter parameter = parameters.get(index);
                if (parameter.defaultValue() != null) {
                    ParameterSymbol symbol = function.parameters().get(index);
                    BoundExpression value = bindExpression(parameter.defaultValue(), scope,
                            symbol.type());
                    boolean converts = requireImplicitConversion(value, symbol.type(),
                            parameter.defaultValue());
                    if (converts) {
                        DiagnosticCode failure = parameterDefaultFailure(value);
                        if (failure == DiagnosticCode.DEFAULT_PARAMETER_VALUE_MUST_BE_CONSTANT) {
                            diagnostics.report(failure, file, parameter.defaultValue().span(),
                                    parameter.name());
                        } else if (failure != null) {
                            diagnostics.report(failure, file, parameter.defaultValue().span());
                        }
                    }
                }
            }
        }

        /// Returns the diagnostic that makes `expression` invalid as an optional default.
        /// `INTERNAL_ERROR` from ConstantEvaluator means only that the evaluator does not yet
        /// fold that otherwise constant shape (for example a mixed-width converted operand),
        /// not that the source is invalid; only semantic failures such as divide-by-zero or
        /// checked overflow are reported here.
        private static DiagnosticCode parameterDefaultFailure(BoundExpression expression) {
            if (!isValidParameterDefault(expression)) {
                return DiagnosticCode.DEFAULT_PARAMETER_VALUE_MUST_BE_CONSTANT;
            }
            if (expression instanceof BoundExpression.Value
                    || expression instanceof BoundExpression.Deferred deferred
                            && (deferred.form().equals("default")
                                    || deferred.form().equals("nameof"))) {
                return null;
            }

            boolean checked = true;
            BoundExpression evaluated = expression;
            while (evaluated instanceof BoundExpression.Conversion conversion) {
                evaluated = conversion.operand();
            }
            if (evaluated instanceof BoundExpression.Deferred deferred
                    && (deferred.form().equals("checked")
                            || deferred.form().equals("unchecked"))) {
                checked = deferred.form().equals("checked");
                if (deferred.children().size() != 1) {
                    return null;
                }
                evaluated = deferred.children().getFirst();
            }
            ConstantEvaluator.Result result = ConstantEvaluator.evaluate(evaluated, checked);
            if (result instanceof ConstantEvaluator.Result.Failure failure
                    && failure.code() != DiagnosticCode.INTERNAL_ERROR
                    // Constant overflow and division by a constant zero are both reported
                    // wherever the expression is written, so reporting either
                    // again here would say it twice about one operator.
                    && failure.code() != DiagnosticCode.COMPILE_TIME_OVERFLOW
                    && failure.code() != DiagnosticCode.DIVISION_BY_ZERO) {
                return failure.code();
            }
            return null;
        }

        /// C# optional defaults are compile-time expressions. The value itself is still
        /// retained as a bound expression because each call site must embed it; this test
        /// answers only whether the bound shape is one of the constant-expression shapes.
        private static boolean isValidParameterDefault(BoundExpression expression) {
            return switch (expression) {
                case BoundExpression.Literal ignored -> true;
                case BoundExpression.Parenthesized parenthesized ->
                    isValidParameterDefault(parenthesized.expression());
                case BoundExpression.Unary unary -> isValidParameterDefault(unary.operand());
                case BoundExpression.Binary binary -> isValidParameterDefault(binary.left())
                        && isValidParameterDefault(binary.right());
                case BoundExpression.Conditional conditional ->
                    isValidParameterDefault(conditional.condition())
                            && isValidParameterDefault(conditional.whenTrue())
                            && isValidParameterDefault(conditional.whenFalse());
                case BoundExpression.Conversion conversion ->
                    isValidParameterDefault(conversion.operand());
                case BoundExpression.Value value -> value.symbol() instanceof EnumMemberSymbol ||
                    (value.symbol() instanceof FieldSymbol field && field.isConstant());
                case BoundExpression.Deferred deferred -> switch (deferred.form()) {
                    case "default", "nameof" -> true;
                    case "checked", "unchecked" -> deferred.children().stream()
                            .allMatch(Binder::isValidParameterDefault);
                    default -> false;
                };
                default -> false;
            };
        }

        private void bindBlock(StatementSyntax.Block block, Scope enclosing,
                FunctionSymbol function) {
            Scope scope = model.scopeFor(block).orElse(enclosing);
            block.statements().forEach(statement -> bindStatement(statement, scope, function));
        }

        private void bindStatement(StatementSyntax statement, Scope enclosing,
                FunctionSymbol function) {
            Scope scope = model.scopeFor(statement).orElse(enclosing);
            switch (statement) {
                case StatementSyntax.Block block -> bindBlock(block, scope, function);
                case StatementSyntax.Empty ignored -> {
                }
                case StatementSyntax.Expression expression -> bindExpression(
                        expression.expression(), scope);
                case StatementSyntax.LocalDeclaration local -> bindLocal(local, scope);
                case StatementSyntax.If conditional -> {
                    requireBoolean(bindExpression(conditional.condition(), scope),
                            conditional.condition());
                    bindStatement(conditional.whenTrue(), scope, function);
                    if (conditional.whenFalse() != null) {
                        bindStatement(conditional.whenFalse(), scope, function);
                    }
                }
                case StatementSyntax.While loop -> {
                    requireBoolean(bindExpression(loop.condition(), scope), loop.condition());
                    bindStatement(loop.body(), scope, function);
                }
                case StatementSyntax.Do loop -> {
                    bindStatement(loop.body(), scope, function);
                    requireBoolean(bindExpression(loop.condition(), scope), loop.condition());
                }
                case StatementSyntax.For loop -> {
                    loop.initializers().forEach(initializer -> bindStatement(initializer, scope,
                            function));
                    if (loop.condition() != null) {
                        requireBoolean(bindExpression(loop.condition(), scope), loop.condition());
                    }
                    loop.iterators().forEach(iterator -> bindExpression(iterator, scope));
                    bindStatement(loop.body(), scope, function);
                }
                case StatementSyntax.Foreach loop -> {
                    BoundExpression collection = bindExpression(loop.collection(), scope);
                    TypeSymbol element = foreachElementType(collection, loop);
                    if (isDeconstructingDesignation(loop.variable()) && element != null) {
                        // `foreach (var (a, b) in pairs)` destructures the element instead of
                        // testing it, but the component walk is the same one `is (a, b)` uses,
                        // so the locals are typed per component rather than from the element.
                        bindTestPattern(loop.variable(), scope, element);
                    } else {
                        inferForeachLocals(loop.variable(), element);
                        bindPattern(loop.variable(), scope);
                        checkForeachElementConversion(loop, element);
                    }
                    bindStatement(loop.body(), scope, function);
                }
                case StatementSyntax.Switch selection -> {
                    BoundExpression governing = bindExpression(selection.expression(), scope);
                    for (AuxiliarySyntax.SwitchSection section : selection.sections()) {
                        Scope sectionScope = model.scopeFor(section).orElse(scope);
                        for (AuxiliarySyntax.SwitchLabel label : section.labels()) {
                            if (label.pattern() != null) {
                                bindTestPattern(label.pattern(), sectionScope,
                                        governing.type());
                            }
                            if (label.guard() != null) {
                                requireBoolean(bindExpression(label.guard(), sectionScope),
                                        label.guard());
                            }
                        }
                        section.statements().forEach(child -> bindStatement(child, sectionScope,
                                function));
                    }
                }
                case StatementSyntax.Break ignored -> {
                }
                case StatementSyntax.Continue ignored -> {
                }
                case StatementSyntax.Return returned -> {
                    if (returned.expression() != null) {
                        BoundExpression value = function == null ? bindExpression(returned.expression(), scope)
                                : bindExpression(returned.expression(), scope, bodyReturnType(function));
                        if (function != null) {
                            requireReturnConversion(value, function, returned.expression());
                        }
                    }
                }
                case StatementSyntax.Throw thrown -> {
                    if (thrown.expression() != null) {
                        requireThrowable(bindExpression(thrown.expression(), scope),
                                thrown.expression());
                    } else if (catchBodyDepth == 0) {
                        // A rethrow needs the exception an enclosing `catch` is handling
                        //. Outside one there is none, exactly as in C#.
                        diagnostics.report(DiagnosticCode.RETHROW_OUTSIDE_CATCH, file,
                                thrown.span());
                    }
                }
                case StatementSyntax.Goto jump -> {
                    if (jump.value() != null) {
                        bindExpression(jump.value(), scope);
                    }
                }
                case StatementSyntax.Labeled labeled -> bindStatement(labeled.statement(), scope,
                        function);
                case StatementSyntax.Try attempt -> {
                    bindBlock(attempt.body(), scope, function);
                    for (AuxiliarySyntax.CatchClause clause : attempt.catches()) {
                        Scope catchScope = model.scopeFor(clause).orElse(scope);
                        if (clause.type() != null) {
                            TypeSymbol catchType = model.typeOf(clause.type())
                                    .orElse(TypeSymbol.Error.INSTANCE);
                            if (!isThrowable(catchType) && catchType != TypeSymbol.Error.INSTANCE) {
                                diagnostics.report(
                                        DiagnosticCode.TYPE_CAUGHT_OR_THROWN_MUST_DERIVE_FROM_EXCEPTION,
                                        file, clause.type().span());
                            }
                        }
                        if (clause.filter() != null) {
                            requireBoolean(bindExpression(clause.filter(), catchScope),
                                    clause.filter());
                        }
                        catchBodyDepth++;
                        try {
                            bindBlock(clause.body(), catchScope, function);
                        } finally {
                            catchBodyDepth--;
                        }
                    }
                    if (attempt.finallyBody() != null) {
                        bindBlock(attempt.finallyBody(), scope, function);
                    }
                }
                case StatementSyntax.Using using -> {
                    TypeSymbol autoCloseable = model.javaInterop().resolveType("java.lang.AutoCloseable");
                    if (using.resource() instanceof StatementSyntax.LocalDeclaration local) {
                        bindLocal(local, scope);
                        if (autoCloseable != null) {
                            for (AuxiliarySyntax.VariableDeclarator variable : local.variables()) {
                                Symbol symbol = model.declaredSymbol(variable).orElse(null);
                                if (symbol instanceof LocalSymbol ls) {
                                    TypeSymbol type = ls.type() == TypeSymbol.Inferred.INSTANCE
                                            ? inferredLocalTypes.get(ls)
                                            : ls.type();
                                    if (type != null && type != TypeSymbol.Error.INSTANCE) {
                                        if (!vsharp.compiler.semantics.conversions.Conversions.classify(type, autoCloseable).isImplicit()) {
                                            diagnostics.report(DiagnosticCode.TYPE_USED_IN_USING_STATEMENT_MUST_BE_IMPLICITLY_CONVERTIBLE_TO_AUTOCLOSEABLE,
                                                    file, variable.span());
                                        }
                                    }
                                }
                            }
                        }
                    } else if (using.resource() instanceof ExpressionSyntax expression) {
                        BoundExpression boundExpr = bindExpression(expression, scope);
                        if (autoCloseable != null && boundExpr.type() != TypeSymbol.Error.INSTANCE) {
                            if (!vsharp.compiler.semantics.conversions.Conversions.classify(boundExpr.type(), autoCloseable).isImplicit()) {
                                diagnostics.report(DiagnosticCode.TYPE_USED_IN_USING_STATEMENT_MUST_BE_IMPLICITLY_CONVERTIBLE_TO_AUTOCLOSEABLE,
                                        file, expression.span());
                            }
                        }
                    }
                    if (using.body() != null) {
                        bindStatement(using.body(), scope, function);
                    }
                }
                case StatementSyntax.Lock lock -> {
                    BoundExpression lockExpr = bindExpression(lock.expression(), scope);
                    TypeSymbol type = lockExpr.type();
                    if (!(type instanceof TypeSymbol.Error) && type.isValueType()) {
                        diagnostics.report(DiagnosticCode.NON_REFERENCE_TYPE_IN_LOCK, file,
                                lock.expression().span(), type.displayName());
                    }
                    bindStatement(lock.body(), scope, function);
                }
                case StatementSyntax.Checked checked -> bindBlock(checked.body(), scope, function);
                case StatementSyntax.Yield yielded -> {
                    diagnostics.report(DiagnosticCode.OBJECT_MODEL_UNSUPPORTED, file,
                            yielded.span(), yielded.isBreak() ? "yield break" : "yield return");
                    if (yielded.expression() != null) {
                        bindExpression(yielded.expression(), scope);
                    }
                }
                case StatementSyntax.LocalFunction local -> {
                    // A local function declared inside a `catch` body is still a separate
                    // method: it does not handle the enclosing exception, so `throw;` inside
                    // it is CS0156 in C# and must be here too.
                    int enclosingCatchDepth = catchBodyDepth;
                    catchBodyDepth = 0;
                    try {
                        bindCallable(local.declaration(), scope);
                    } finally {
                        catchBodyDepth = enclosingCatchDepth;
                    }
                }
            }
        }

        private void bindLocal(StatementSyntax.LocalDeclaration declaration, Scope scope) {
            for (AuxiliarySyntax.VariableDeclarator variable : declaration.variables()) {
                List<LocalSymbol> locals = declaredLocals(variable);
                if (variable.initializer() == null) {
                    for (LocalSymbol local : locals) {
                        if (local.type() == TypeSymbol.Inferred.INSTANCE) {
                            diagnostics.report(DiagnosticCode.INFERRED_LOCAL_REQUIRES_INITIALIZER,
                                    file, variable.span());
                        }
                    }
                    continue;
                }
                LocalSymbol soleLocal = locals.size() == 1 ? locals.getFirst() : null;
                BoundExpression initializer = soleLocal == null
                        || soleLocal.type() == TypeSymbol.Inferred.INSTANCE
                        ? bindExpression(variable.initializer(), scope)
                        : bindExpression(variable.initializer(), scope, soleLocal.type());
                TypeSymbol initType = initializer.type();
                if (locals.size() > 1) {
                    List<TypeSymbol> deconstructed = deconstructionElementTypes(initType, locals.size());
                    if (deconstructed != null) {
                        for (int index = 0; index < locals.size(); index++) {
                            LocalSymbol local = locals.get(index);
                            if (local.type() == TypeSymbol.Inferred.INSTANCE) {
                                TypeSymbol elemType = deconstructed.get(index);
                                if (elemType == null || elemType == TypeSymbol.Null.INSTANCE) {
                                    diagnostics.report(DiagnosticCode.CANNOT_INFER_LOCAL_TYPE, file, variable.initializer().span(), "<null>");
                                    inferredLocalTypes.put(local, TypeSymbol.Error.INSTANCE);
                                } else if (elemType == BuiltinType.VOID) {
                                    diagnostics.report(DiagnosticCode.CANNOT_INFER_LOCAL_TYPE, file, variable.initializer().span(), "void");
                                    inferredLocalTypes.put(local, TypeSymbol.Error.INSTANCE);
                                } else {
                                    inferredLocalTypes.put(local, elemType);
                                }
                            } else {
                                // Explicitly typed local in deconstruction, e.g. (int a, var b) = expr;
                                // We don't have a BoundExpression for the element to check implicit conversion yet.
                                // C# handles deconstruction by creating a temporary, but for type inference we just need the type.
                                // The actual assignment conversion will be checked during assignment lowering if we lower it to assignments,
                                // but for now we just infer the types.
                            }
                        }
                    } else {
                        diagnostics.report(DiagnosticCode.CANNOT_IMPLICITLY_CONVERT, file, variable.initializer().span(), displayType(initializer), "tuple");
                    }
                    continue;
                }

                if (initializer instanceof BoundExpression.Tuple tuple
                        && locals.size() == tuple.elements().size()
                        && locals.stream().allMatch(local ->
                                local.type() == TypeSymbol.Inferred.INSTANCE)) {
                    for (int index = 0; index < locals.size(); index++) {
                        inferLocal(locals.get(index), tuple.elements().get(index),
                                variable.initializer(), scope);
                    }
                    continue;
                }
                for (LocalSymbol local : locals) {
                    if (local.type() == TypeSymbol.Inferred.INSTANCE) {
                        inferLocal(local, initializer, variable.initializer(), scope);
                    } else {
                        requireImplicitConversion(initializer, local.type(),
                                variable.initializer());
                    }
                }
                // A `const` *local* is deliberately not held to CS0133 here. C# requires its
                // initializer to be a constant expression, and the field path enforces exactly
                // that, but the evaluator cannot fold a read of a const *local*:
                // [LocalSymbol] carries no folded value, only the `constant` flag, so
                // `const int Half = Max / 2;` over a preceding `const int Max` answers "not a
                // constant" and a gate here would reject valid C#. Rejecting correct programs
                // is worse than accepting a few incorrect ones, so the check waits until a
                // local's folded value is carried on its symbol.
            }
        }

        /// The element types a deconstruction of `type` into `arity` targets produces, or
        /// `null` when the type does not deconstruct into that shape.
        ///
        /// V# sources them from tuple elements and from the positional components of a
        /// `record struct`; a user-written `Deconstruct` method is an instance method,
        /// which the omitted object model does not provide.
        private List<TypeSymbol> deconstructionElementTypes(TypeSymbol type, int arity) {
            if (type instanceof TypeSymbol.Tuple tuple && tuple.elements().size() == arity) {
                List<TypeSymbol> elements = new ArrayList<>(arity);
                for (TypeSymbol.TupleElement element : tuple.elements()) {
                    elements.add(element.type());
                }
                return elements;
            }
            if (type instanceof NamedTypeSymbol named) {
                Optional<PositionalLayout> layout = model.positionalLayout(named);
                if (layout.isPresent() && layout.get().components().size() == arity) {
                    List<TypeSymbol> elements = new ArrayList<>(arity);
                    for (FieldSymbol component : layout.get().components()) {
                        elements.add(component.type());
                    }
                    return elements;
                }
            }
            return null;
        }

        /// The type one iteration step yields, or `null` when the collection is not
        /// enumerable and CS1579 has been reported. An already-erroneous collection
        /// yields `null` silently: its own diagnostic is the useful one.
        private TypeSymbol foreachElementType(BoundExpression collection,
                StatementSyntax.Foreach loop) {
            TypeSymbol collectionType = collection.type();
            if (collectionType == TypeSymbol.Error.INSTANCE
                    || collectionType == TypeSymbol.Inferred.INSTANCE
                    || collectionType == TypeSymbol.Null.INSTANCE) {
                return null;
            }
            TypeSymbol element = model.javaInterop().foreachElementType(collectionType)
                    .orElse(null);
            if (element == null) {
                diagnostics.report(DiagnosticCode.FOREACH_NOT_ENUMERABLE, file,
                        loop.collection().span(), displayType(collection));
            }
            return element;
        }

        /// True for the two spellings that take a `foreach` element apart - `var (a, b)` and
        /// the tuple-typed `(int a, string b)` - which the parser normalises into the same
        /// positional recursive pattern carrying no type test of its own.
        private static boolean isDeconstructingDesignation(PatternSyntax pattern) {
            return pattern instanceof PatternSyntax.Recursive recursive
                    && recursive.type() == null
                    && !recursive.positional().isEmpty();
        }

        /// `var` in a `foreach` takes the element type, exactly as C# infers it.
        private void inferForeachLocals(PatternSyntax pattern, TypeSymbol element) {
            if (element == null) {
                return;
            }
            List<LocalSymbol> locals = new ArrayList<>();
            collectPatternLocals(pattern, locals);
            for (LocalSymbol local : locals) {
                if (local.type() == TypeSymbol.Inferred.INSTANCE) {
                    inferredLocalTypes.put(local, element);
                }
            }
        }

        /// C# lets a `foreach` variable be written with any type the element *explicitly*
        /// converts to, and performs that conversion per step (CS0030 when none exists). The
        /// check is skipped for a deconstructing pattern, whose locals take tuple components
        /// rather than the element itself.
        private void checkForeachElementConversion(StatementSyntax.Foreach loop,
                TypeSymbol element) {
            if (element == null || element == TypeSymbol.Error.INSTANCE) {
                return;
            }
            List<LocalSymbol> locals = new ArrayList<>();
            collectPatternLocals(loop.variable(), locals);
            if (locals.size() != 1) {
                return;
            }
            LocalSymbol local = locals.getFirst();
            TypeSymbol declared = inferredLocalTypes.getOrDefault(local, local.type());
            if (declared == TypeSymbol.Inferred.INSTANCE || declared == TypeSymbol.Error.INSTANCE) {
                return;
            }
            if (!Conversions.classify(element, declared).exists()) {
                diagnostics.report(DiagnosticCode.CANNOT_EXPLICITLY_CONVERT, file,
                        loop.variable().span(), element.displayName(), declared.displayName());
            }
        }

        private List<LocalSymbol> declaredLocals(AuxiliarySyntax.VariableDeclarator variable) {
            Symbol direct = model.declaredSymbol(variable).orElse(null);
            if (direct instanceof LocalSymbol local) {
                return List.of(local);
            }
            if (variable.designation() == null) {
                return List.of();
            }
            List<LocalSymbol> result = new ArrayList<>();
            collectPatternLocals(variable.designation(), result);
            return List.copyOf(result);
        }

        private void collectPatternLocals(PatternSyntax pattern, List<LocalSymbol> result) {
            Symbol symbol = model.declaredSymbol(pattern).orElse(null);
            if (symbol instanceof LocalSymbol local) {
                result.add(local);
            }
            switch (pattern) {
                case PatternSyntax.Recursive recursive -> {
                    recursive.positional().forEach(child -> collectPatternLocals(child, result));
                    recursive.properties().forEach(property -> collectPatternLocals(
                            property.pattern(), result));
                }
                case PatternSyntax.ListPattern list -> list.elements()
                        .forEach(child -> collectPatternLocals(child, result));
                case PatternSyntax.Slice slice -> {
                    if (slice.pattern() != null) {
                        collectPatternLocals(slice.pattern(), result);
                    }
                }
                case PatternSyntax.Binary binary -> {
                    collectPatternLocals(binary.left(), result);
                    collectPatternLocals(binary.right(), result);
                }
                case PatternSyntax.Not not -> collectPatternLocals(not.pattern(), result);
                case PatternSyntax.Parenthesized parenthesized -> collectPatternLocals(
                        parenthesized.pattern(), result);
                case PatternSyntax.Missing ignored -> {
                }
                case PatternSyntax.Discard ignored -> {
                }
                case PatternSyntax.Constant ignored -> {
                }
                case PatternSyntax.Type ignored -> {
                }
                case PatternSyntax.Var ignored -> {
                }
                case PatternSyntax.Relational ignored -> {
                }
            }
        }

        private void inferLocal(LocalSymbol local, BoundExpression initializer,
                ExpressionSyntax syntax, Scope scope) {
            if (initializer instanceof BoundExpression.Error) {
                return;
            }
            if (initializer instanceof BoundExpression.FunctionGroup
                    || initializer instanceof BoundExpression.DeclarationGroup
                    || initializer.type() == TypeSymbol.Null.INSTANCE) {
                diagnostics.report(DiagnosticCode.CANNOT_INFER_LOCAL_TYPE, file,
                        syntax.span(), displayType(initializer));
                return;
            }
            if (initializer.type() == TypeSymbol.Error.INSTANCE) {
                return;
            }
            // `var` is not a target, so it cannot close an open type argument. The sweep
            // reports the call itself; recording the open type here would put a `T` no
            // declaration owns into the local and cascade at every later use.
            if (hasUnresolvedTypeParameters(initializer.type(), scope)) {
                inferredLocalTypes.put(local, TypeSymbol.Error.INSTANCE);
                return;
            }
            inferredLocalTypes.put(local, initializer.type());
        }

        private BoundExpression bindExpression(ExpressionSyntax syntax, Scope scope) {
            BoundExpression cached = expressions.get(syntax);
            if (cached != null) {
                return cached;
            }
            BoundExpression result = switch (syntax) {
                case ExpressionSyntax.Missing missing -> new BoundExpression.Error(missing.span());
                case ExpressionSyntax.Unsupported unsupported ->
                        new BoundExpression.Error(unsupported.span());
                case ExpressionSyntax.Literal literal -> bindLiteral(literal);
                // The parser only builds this node before a `.`, and `bindMemberAccess`
                // consumes it there. Reaching binding as a value means the member access it
                // introduced was not one: `int.` alone is the invalid term C# names.
                case ExpressionSyntax.PredefinedType predefined -> {
                    diagnostics.report(DiagnosticCode.EXPRESSION_EXPECTED, file, predefined.span(),
                            predefined.keyword().display());
                    yield new BoundExpression.Error(predefined.span());
                }
                case ExpressionSyntax.Identifier identifier -> bindIdentifier(identifier, scope);
                case ExpressionSyntax.Declaration declaration ->
                        bindDeclarationExpression(declaration, scope);
                case ExpressionSyntax.Parenthesized parenthesized -> {
                    BoundExpression expression = bindExpression(parenthesized.expression(), scope);
                    yield new BoundExpression.Parenthesized(parenthesized.span(), expression.type(),
                            expression);
                }
                case ExpressionSyntax.Tuple tuple -> bindTuple(tuple, scope);
                case ExpressionSyntax.Unary unary -> bindUnary(unary, scope);
                case ExpressionSyntax.Await await -> bindAwait(await, scope);
                case ExpressionSyntax.Binary binary -> bindBinary(binary, scope);
                case ExpressionSyntax.Assignment assignment -> bindAssignment(assignment, scope);
                case ExpressionSyntax.Conditional conditional -> bindConditional(conditional,
                        scope);
                case ExpressionSyntax.MemberAccess member -> bindMemberAccess(member, scope);
                case ExpressionSyntax.ElementAccess element -> bindElementAccess(element, scope);
                case ExpressionSyntax.Invocation invocation -> bindInvocation(invocation, scope);
                case ExpressionSyntax.Postfix postfix -> bindPostfix(postfix, scope);
                case ExpressionSyntax.Cast cast -> bindCast(cast, scope);
                case ExpressionSyntax.Lambda lambda -> bindLambda(lambda, scope);
                case ExpressionSyntax.Switch switched -> bindDeferredSwitch(switched, scope);
                case ExpressionSyntax.Throw thrown -> bindThrowExpression(thrown, scope);
                case ExpressionSyntax.Default defaultExpression -> new BoundExpression.Deferred(
                        defaultExpression.span(), defaultExpression.type() == null
                                ? TypeSymbol.Error.INSTANCE
                                : resolveType(defaultExpression.type(), scope),
                        "default", List.of());
                case ExpressionSyntax.TypeOperator typeOperator ->
                        bindTypeOperation(typeOperator, scope);
                case ExpressionSyntax.NameOf nameOf -> bindNameOf(nameOf, scope);
                case ExpressionSyntax.Checked checked -> {
                    // `unchecked` is the only way a C# program may overflow a constant
                    // expression without a diagnostic, so the region is tracked while
                    // its operands bind rather than inspected afterwards.
                    if (!checked.checked()) {
                        uncheckedDepth++;
                    }
                    BoundExpression expression;
                    try {
                        expression = bindExpression(checked.expression(), scope);
                    } finally {
                        if (!checked.checked()) {
                            uncheckedDepth--;
                        }
                    }
                    yield new BoundExpression.Deferred(checked.span(), expression.type(),
                            checked.checked() ? "checked" : "unchecked", List.of(expression));
                }
                case ExpressionSyntax.IsPattern isPattern -> {
                    BoundExpression expression = bindExpression(isPattern.expression(), scope);
                    bindTestPattern(isPattern.pattern(), scope, expression.type());
                    yield new BoundExpression.Deferred(isPattern.span(), BuiltinType.BOOL,
                            "is pattern", List.of(expression));
                }
                case ExpressionSyntax.As as -> new BoundExpression.Deferred(as.span(),
                        resolveType(as.type(), scope), "as",
                        List.of(bindExpression(as.expression(), scope)));
                case ExpressionSyntax.Range range -> bindRange(range, scope);
                case ExpressionSyntax.Collection collection -> bindCollection(collection, scope, null);
                case ExpressionSyntax.Interpolated interpolated -> bindInterpolated(interpolated,
                        scope);
                case ExpressionSyntax.ArrayCreation array -> bindArrayCreation(array, scope);
                case ExpressionSyntax.ObjectCreation creation -> bindObjectCreation(creation,
                        scope);
                case ExpressionSyntax.ArrayInitializer initializer ->
                        new BoundExpression.Deferred(initializer.span(),
                                TypeSymbol.Error.INSTANCE, "array initializer",
                                initializer.elements().stream()
                                        .map(element -> bindExpression(element, scope)).toList());
                case ExpressionSyntax.With with -> bindWith(with, scope);
            };
            expressions.put(syntax, result);
            bindingOrder.add(result);
            return result;
        }

        private BoundExpression bindExpression(ExpressionSyntax syntax, Scope scope,
                TypeSymbol targetType) {
            if (syntax instanceof ExpressionSyntax.Default defaultExpression
                    && defaultExpression.type() == null) {
                BoundExpression result = new BoundExpression.Deferred(defaultExpression.span(),
                        targetType, "default", List.of());
                expressions.put(defaultExpression, result);
                bindingOrder.add(result);
                return result;
            }
            // A call is the one expression whose *arguments* may need the target: a lambda
            // argument typed by a parameter no other argument mentions can only be closed
            // from here. Binding runs once and caches under the same identity every
            // later lookup uses; the conversion wrapper is applied exactly as it is for any
            // other expression.
            if (syntax instanceof ExpressionSyntax.Invocation invocation
                    && expressions.get(invocation) == null) {
                BoundExpression bound = bindInvocation(invocation, scope, targetType);
                expressions.put(invocation, bound);
                bindingOrder.add(bound);
                return applyTargetConversion(bound, targetType, syntax);
            }
            // A conditional and a switch expression are target-typed in C# 9: when their arms
            // share no common type, the type the result is being converted to supplies one
            //. Both cache exactly as every other node does, so a later lookup for the
            // same syntax finds the typed form rather than re-binding it without the target.
            if (syntax instanceof ExpressionSyntax.Conditional conditional
                    && expressions.get(conditional) == null) {
                BoundExpression result = bindConditional(conditional, scope, targetType);
                expressions.put(conditional, result);
                bindingOrder.add(result);
                return applyTargetConversion(result, targetType, syntax);
            }
            if (syntax instanceof ExpressionSyntax.Switch switched
                    && expressions.get(switched) == null) {
                BoundExpression result = bindDeferredSwitch(switched, scope, targetType);
                expressions.put(switched, result);
                bindingOrder.add(result);
                return applyTargetConversion(result, targetType, syntax);
            }
            if (syntax instanceof ExpressionSyntax.Collection collection) {
                BoundExpression cached = expressions.get(collection);
                if (cached != null) {
                    return cached;
                }
                BoundExpression result = bindCollection(collection, scope, targetType);
                expressions.put(collection, result);
                bindingOrder.add(result);
                return result;
            }
            return applyTargetConversion(bindExpression(syntax, scope), targetType, syntax);
        }

        /// Wraps `bound` in an explicit `BoundExpression.Conversion` when it needs one to
        /// reach `targetType`, so IR lowering sees a real `IrExpression.Convert` instead of
        /// the conversion silently vanishing: a bound expression's own `.type()` stays
        /// whatever it was bound as, so without this wrapping the caller's separate
        /// `requireImplicitConversion`/`requireReturnConversion` diagnostic check validates
        /// that a conversion exists while nothing downstream ever records which one - every
        /// numeric-widening local declaration or return (`double d = someInt;`,
        /// `double F() => someInt;`) would reach the backend as if no conversion were needed
        /// at all, producing wrong bytecode (a raw `int` bit pattern stored as a `double`)
        /// rather than a diagnosable gap. Recovery/error-typed expressions, already-matching
        /// types, and conversions that do not exist all pass through unchanged; an invalid
        /// conversion is left for the caller's own diagnostic to report, not silently
        /// "resolved" here.
        ///
        /// `bindExpression(syntax, scope)` already cached the unwrapped `bound` under
        /// `syntax`'s identity (`expressions`/`bindingOrder`) before this method sees it -
        /// every later lookup of this exact syntax node (`ExpressionBinding.expressionFor`,
        /// which is how IR lowering finds it) goes through that identity cache, not through
        /// this method's return value. The wrapped result must overwrite that cache entry or
        /// the wrapping only reaches this method's immediate caller and never the lowerer.
        private BoundExpression applyTargetConversion(BoundExpression bound, TypeSymbol targetType,
                ExpressionSyntax syntax) {
            // A lambda is typed by its target rather than converted to it: it carries
            // no type of its own, so the error-type shortcut below would drop it silently.
            if (Conversions.isUnboundLambda(bound)) {
                BoundExpression lambda = materializeLambda(bound, targetType);
                if (lambda != bound) {
                    expressions.put(syntax, lambda);
                }
                return lambda;
            }
            if (isRecovery(bound) || bound.type() == TypeSymbol.Error.INSTANCE
                    || bound.type().equals(targetType)) {
                return bound;
            }
            Conversion conversion = Conversions.classify(bound, targetType);
            // Only a genuinely implicit conversion is wrapped here: these two binding
            // contexts (local declarations, returns) both require an implicit conversion,
            // exactly what requireImplicitConversion/requireReturnConversion re-check
            // immediately afterward. A conversion that merely `exists()` (e.g. EXPLICIT_
            // NUMERIC for an out-of-range constant narrowing) must be left unwrapped so that
            // re-check still sees the original, unconverted type and reports its diagnostic -
            // wrapping it here would retype the cached expression to the target type first,
            // silently defeating that diagnostic rather than merely recording a legal
            // conversion for the backend.
            if (!conversion.isImplicit()) {
                return bound;
            }
            openResults.remove(bound);
            BoundExpression wrapped = new BoundExpression.Conversion(syntax.span(), bound, targetType, conversion);
            expressions.put(syntax, wrapped);
            bindingOrder.add(wrapped);
            return wrapped;
        }

        private BoundExpression bindCollection(ExpressionSyntax.Collection collection, Scope scope,
                TypeSymbol targetType) {
            if (!(targetType instanceof TypeSymbol.Array array)) {
                diagnostics.report(DiagnosticCode.CANNOT_INFER_LOCAL_TYPE, file, collection.span());
                return new BoundExpression.Error(collection.span());
            }
            List<BoundExpression.Collection.Element> elements = new ArrayList<>();
            for (AuxiliarySyntax.CollectionElement element : collection.elements()) {
                BoundExpression value = bindExpression(element.expression(), scope);
                TypeSymbol required = element.spread() ? array : array.elementType();
                requireImplicitConversion(value, required, element.expression());
                elements.add(new BoundExpression.Collection.Element(element.spread(), value));
            }
            return new BoundExpression.Collection(collection.span(), array, elements);
        }

        private BoundExpression bindLiteral(ExpressionSyntax.Literal literal) {
            LiteralType literalType = literal.token().literalType();
            TypeSymbol type = switch (literalType) {
                case INT -> BuiltinType.INT;
                case UINT -> BuiltinType.UINT;
                case LONG -> BuiltinType.LONG;
                case ULONG -> BuiltinType.ULONG;
                case FLOAT -> BuiltinType.FLOAT;
                case DOUBLE -> BuiltinType.DOUBLE;
                case DECIMAL -> BuiltinType.DECIMAL;
                case CHAR -> BuiltinType.CHAR;
                case STRING, INTERPOLATED -> BuiltinType.STRING;
                case UTF8 -> new TypeSymbol.Array(BuiltinType.BYTE, List.of(1));
                case BOOL -> BuiltinType.BOOL;
                case NULL -> TypeSymbol.Null.INSTANCE;
                case NONE -> TypeSymbol.Error.INSTANCE;
            };
            if (type == TypeSymbol.Error.INSTANCE) {
                return new BoundExpression.Error(literal.span());
            }
            Object value = literal.token().value();
            if (type == BuiltinType.DECIMAL && value instanceof BigDecimal decimal) {
                try {
                    value = VsDecimal.fromLiteral(decimal);
                } catch (ArithmeticException ex) {
                    diagnostics.report(DiagnosticCode.DECIMAL_LITERAL_OVERFLOW, file,
                            literal.span());
                    value = VsDecimal.zero();
                }
            }
            return new BoundExpression.Literal(literal.span(), type, value);
        }

        private List<Symbol> lookupInScope(Scope scope, String name) {
            List<Symbol> found = scope.lookup(name);
            if (!found.isEmpty()) {
                return found;
            }
            Scope fileScope = model.scopeFor(unit).orElse(model.globalScope());
            if (scope != fileScope) {
                return fileScope.lookup(name);
            }
            return found;
        }

        private BoundExpression bindIdentifier(ExpressionSyntax.Identifier identifier,
                Scope scope) {
            List<Symbol> declarations = lookupInScope(scope, identifier.name());
            if (declarations.isEmpty()) {
                diagnostics.report(DiagnosticCode.NAME_NOT_FOUND, file, identifier.span(),
                        identifier.name());
                return new BoundExpression.Error(identifier.span());
            }
            Symbol value = declarations.stream().filter(Binder::isValue).findFirst()
                    .map(this::retypedLambdaParameter).orElse(null);
            if (value != null) {
                noteLambdaCapture(value);
                if (value instanceof LocalSymbol local
                        && identifier.span().start() < local.location().span().end()) {
                    diagnostics.report(DiagnosticCode.VARIABLE_BEFORE_DECLARATION, file,
                            identifier.span(), local.name());
                    return new BoundExpression.Error(identifier.span());
                }
                return new BoundExpression.Value(identifier.span(), valueType(value), value);
            }
            List<FunctionSymbol> functions = declarations.stream()
                    .filter(FunctionSymbol.class::isInstance)
                    .map(FunctionSymbol.class::cast)
                    .toList();
            if (!functions.isEmpty()) {
                return new BoundExpression.FunctionGroup(identifier.span(), identifier.name(), functions, null);
            }
            List<Symbol> typeOrNamespace = declarations.stream()
                    .filter(Binder::isTypeOrNamespace)
                    .toList();
            if (!typeOrNamespace.isEmpty()) {
                return new BoundExpression.DeclarationGroup(identifier.span(), identifier.name(),
                        typeOrNamespace);
            }
            diagnostics.report(DiagnosticCode.NAME_NOT_FOUND, file, identifier.span(),
                    identifier.name());
            return new BoundExpression.Error(identifier.span());
        }

        private TypeSymbol valueType(Symbol symbol) {
            return switch (symbol) {
                case LocalSymbol local -> inferredLocalTypes.getOrDefault(local,
                        local.type() == TypeSymbol.Inferred.INSTANCE
                                ? TypeSymbol.Error.INSTANCE : local.type());
                case ParameterSymbol parameter -> parameter.type() == TypeSymbol.Inferred.INSTANCE
                        ? TypeSymbol.Error.INSTANCE : parameter.type();
                case FieldSymbol field -> field.type();
                case EnumMemberSymbol member -> member.type();
                case NamespaceSymbol ignored -> TypeSymbol.Error.INSTANCE;
                case ContainerSymbol ignored -> TypeSymbol.Error.INSTANCE;
                case NamedTypeSymbol ignored -> TypeSymbol.Error.INSTANCE;
                case FunctionSymbol ignored -> TypeSymbol.Error.INSTANCE;
                case TypeParameterSymbol ignored -> TypeSymbol.Error.INSTANCE;
            };
        }

        private BoundExpression bindTuple(ExpressionSyntax.Tuple tuple, Scope scope) {
            List<BoundExpression> elements = tuple.elements().stream()
                    .map(element -> bindExpression(element, scope)).toList();
            TypeSymbol.Tuple type = new TypeSymbol.Tuple(elements.stream()
                    .map(element -> new TypeSymbol.TupleElement(element.type(), null)).toList());
            return new BoundExpression.Tuple(tuple.span(), type, elements);
        }

        /// Binds `await task` to the task's result type.
        ///
        /// A task is `java.util.concurrent.Future`, which is what `Task` spells, so the result
        /// type is simply the type argument. A raw `Task` - a bare `Future` reached through
        /// Java interop - yields `object`, exactly as reading any raw generic does.
        /// Refuses a task joined with the JDK's own blocking read instead of `await`.
        ///
        /// The execution limit is what makes a blocking `await` safe to compile into every
        /// program, and `Get()` steps around it: the no-argument form waits for ever and the
        /// timed form waits for whatever the caller picked. Both are reported at every call
        /// site. This was not a hypothetical - it reached production code in a server whose
        /// certificate loading would have hung indefinitely on an unresponsive filesystem.
        ///
        /// `Join` and `GetNow` are included because `CompletableFuture` is a task too, and a
        /// rule that only covered the spelling people happened to use first would be a rule
        /// about spelling.
        private void reportUnboundedJoin(BoundExpression receiver, String name, SourceSpan span) {
            if (receiver == null || !isBlockingJoinName(name) || !isTaskType(receiver.type())) {
                return;
            }
            // The member group carries the JVM spelling, because the PascalCase mapping has
            // already been applied by the time a call is resolved. The message has to name what
            // the user actually wrote, so it maps back.
            Diagnostic diagnostic = Diagnostic.of(DiagnosticCode.UNBOUNDED_TASK_JOIN, file, span,
                    JavaInterop.vsharpMemberName(name), VsAsync.LIMIT.toSeconds());
            diagnostics.add(allowUnboundedJoins
                    ? diagnostic.withSeverity(Severity.WARNING)
                    : diagnostic);
        }

        /// The JVM spellings, not the V# ones: a resolved member group has already been
        /// through the PascalCase-to-camelCase mapping, so `Get()` arrives here as `get`.
        private static boolean isBlockingJoinName(String name) {
            return "get".equals(name) || "join".equals(name) || "getNow".equals(name);
        }

        /// Whether a type is a task: `Task`/`Task<T>` itself, or any Java type that implements
        /// `java.util.concurrent.Future`, which is what makes the rule cover a
        /// `CompletableFuture` handed over by a library.
        private static boolean isTaskType(TypeSymbol type) {
            if (TaskTypes.isTask(type)) {
                return true;
            }
            NamedTypeSymbol definition = switch (type) {
                case TypeSymbol.Constructed constructed -> constructed.definition();
                case NamedTypeSymbol named -> named;
                default -> null;
            };
            return definition != null
                    && definition.javaSupertypes().contains(TaskTypes.TASK_QUALIFIED_NAME);
        }

        private BoundExpression bindAwait(ExpressionSyntax.Await await, Scope scope) {
            BoundExpression operand = bindExpression(await.operand(), scope);
            TypeSymbol result = awaitedResult(operand.type());
            if (result == null) {
                if (!isRecovery(operand)) {
                    diagnostics.report(DiagnosticCode.AWAIT_REQUIRES_TASK, file, await.span(),
                            displayType(operand));
                }
                return new BoundExpression.Error(await.span());
            }
            return new BoundExpression.Await(await.span(), result, operand);
        }

        /// The value an awaited type produces, or `null` when the type is not a task.
        private static TypeSymbol awaitedResult(TypeSymbol type) {
            if (type instanceof TypeSymbol.Constructed constructed
                    && TaskTypes.isTaskDefinition(constructed.definition())) {
                return constructed.arguments().isEmpty()
                        ? BuiltinType.OBJECT
                        : TaskTypes.resultOf(constructed.arguments().getFirst());
            }
            if (type instanceof NamedTypeSymbol named && TaskTypes.isTaskDefinition(named)) {
                return BuiltinType.OBJECT;
            }
            return null;
        }

        private BoundExpression bindUnary(ExpressionSyntax.Unary unary, Scope scope) {
            BoundExpression operand = bindExpression(unary.operand(), scope);
            TypeSymbol result = CoreOperators.unaryResult(unary.operator(), operand.type());
            if (result == TypeSymbol.Error.INSTANCE) {
                if (!isRecovery(operand)) {
                    diagnostics.report(DiagnosticCode.UNARY_OPERATOR_NOT_APPLICABLE, file,
                            unary.span(), unary.operator().display(), displayType(operand));
                }
                return new BoundExpression.Error(unary.span());
            }
            if (unary.operator() == SyntaxKind.PLUS_PLUS
                    || unary.operator() == SyntaxKind.MINUS_MINUS) {
                if (!isWritable(operand)) {
                    diagnostics.report(DiagnosticCode.ASSIGNMENT_TARGET_REQUIRED, file,
                            unary.span());
                    return new BoundExpression.Error(unary.span());
                }
                return new BoundExpression.Mutation(unary.span(), result, unary.operator(),
                        operand, false);
            }
            BoundExpression bound = new BoundExpression.Unary(unary.span(), result,
                    unary.operator(), operand);
            reportConstantFailure(bound, unary.span());
            return bound;
        }

        private BoundExpression bindCast(ExpressionSyntax.Cast cast, Scope scope) {
            BoundExpression operand = bindExpression(cast.expression(), scope);
            TypeSymbol target = resolveType(cast.type(), scope);
            if (isRecovery(operand) || target == TypeSymbol.Error.INSTANCE) {
                return new BoundExpression.Error(cast.span());
            }
            Conversion conversion = Conversions.classify(operand, target);
            if (!conversion.exists()) {
                BoundExpression converted = userConversion(operand, target, cast.span(), false);
                if (converted != null) {
                    return converted;
                }
                diagnostics.report(DiagnosticCode.CANNOT_EXPLICITLY_CONVERT, file, cast.span(),
                        displayType(operand.type()), displayType(target));
                return new BoundExpression.Error(cast.span());
            }
            return new BoundExpression.Conversion(cast.span(), operand, target, conversion);
        }

        private BoundExpression bindPostfix(ExpressionSyntax.Postfix postfix, Scope scope) {
            BoundExpression operand = bindExpression(postfix.operand(), scope);
            TypeSymbol result = CoreOperators.unaryResult(postfix.operator(), operand.type());
            if (result == TypeSymbol.Error.INSTANCE) {
                if (!isRecovery(operand)) {
                    diagnostics.report(DiagnosticCode.UNARY_OPERATOR_NOT_APPLICABLE, file,
                            postfix.span(), postfix.operator().display(), displayType(operand));
                }
                return new BoundExpression.Error(postfix.span());
            }
            if (!isWritable(operand)) {
                diagnostics.report(DiagnosticCode.ASSIGNMENT_TARGET_REQUIRED, file,
                        postfix.span());
                return new BoundExpression.Error(postfix.span());
            }
            return new BoundExpression.Mutation(postfix.span(), result, postfix.operator(),
                    operand, true);
        }

        private BoundExpression bindInvocation(ExpressionSyntax.Invocation invocation,
                Scope scope) {
            return bindInvocation(invocation, scope, null);
        }

        /// `callTarget` is the type this call's result is being converted to, when the binder
        /// already knows it. It is the only source for a type parameter no argument mentions
        ///, and is `null` everywhere a call stands in a position with no declared type.
        private BoundExpression bindInvocation(ExpressionSyntax.Invocation invocation,
                Scope scope, TypeSymbol callTarget) {
            BoundExpression boundTarget = bindExpression(invocation.target(), scope);
            List<BoundExpression> boundArgs = bindArguments(invocation.arguments(), scope);

            if (boundArgs.stream().anyMatch(Binder::isRecovery)) {
                return new BoundExpression.Error(invocation.span());
            }

            if (boundTarget instanceof BoundExpression.NullConditional conditional) {
                return bindConditionalInvocation(conditional, invocation, boundArgs, scope);
            }

            if (boundTarget instanceof BoundExpression.Error || isRecovery(boundTarget)) {
                return new BoundExpression.Error(invocation.span());
            }

            if (boundTarget instanceof BoundExpression.FunctionGroup group) {
                return bindFunctionInvocation(group, invocation, boundArgs, scope, callTarget);
            }

            // A value whose type is a functional interface is invocable: C# writes `f(x)` for a
            // delegate, and `Func`/`Action` *are* those interfaces here. The same rule
            // lets any Java SAM value be called directly, which is what a V# program holding a
            // `Comparator` or a `Runnable` would otherwise have to spell as `.Compare`/`.Run`.
            FunctionSymbol functional = model.javaInterop()
                    .functionalMethodOf(boundTarget.type()).orElse(null);
            if (functional != null && functional.parameters().size() == boundArgs.size()) {
                BoundExpression.FunctionGroup group = new BoundExpression.FunctionGroup(
                        invocation.span(), functional.name(), List.of(functional), boundTarget);
                return bindFunctionInvocation(group, invocation, boundArgs, scope, callTarget);
            }

            // A target that is not a function group cannot be called. Returning a deferred
            // node here reported nothing and left an Error-typed node whose only syntax is
            // this same invocation, so lowering resolved it back to itself and recursed until
            // the stack ended - `s.Length()` on a property crashed the compiler instead of
            // diagnosing it. Recovery is only sound once the diagnostic exists.
            diagnostics.report(DiagnosticCode.NON_INVOCABLE_MEMBER, file, invocation.span(),
                    invokedMemberName(invocation, boundTarget));
            return new BoundExpression.Error(invocation.span());
        }

        /// The source spelling of what was invoked, for the non-invocable diagnostic. The
        /// syntax is preferred over the bound node because it is what the user wrote and what
        /// the rule's PascalCase rule governs; a non-member target falls back to its type.
        private String invokedMemberName(ExpressionSyntax.Invocation invocation,
                BoundExpression boundTarget) {
            return switch (invocation.target()) {
                case ExpressionSyntax.MemberAccess member -> member.name();
                case ExpressionSyntax.Identifier identifier -> identifier.name();
                default -> displayType(boundTarget);
            };
        }

        private BoundExpression bindConditionalInvocation(
                BoundExpression.NullConditional conditional,
                ExpressionSyntax.Invocation invocation, List<BoundExpression> boundArgs,
                Scope scope) {
            BoundExpression access = conditional.access();
            BoundExpression invoked;
            if (access instanceof BoundExpression.NullConditional nested) {
                invoked = bindConditionalInvocation(nested, invocation, boundArgs, scope);
            } else if (access instanceof BoundExpression.FunctionGroup group) {
                invoked = bindFunctionInvocation(group, invocation, boundArgs, scope, null);
            } else {
                return new BoundExpression.Deferred(invocation.span(), TypeSymbol.Error.INSTANCE,
                        "invocation", prepend(access, boundArgs));
            }
            if (invoked instanceof BoundExpression.Error) {
                return invoked;
            }
            return finishConditionalAccess(
                    invocation.span(), conditional.receiver(), invoked);
        }

        private BoundExpression bindFunctionInvocation(BoundExpression.FunctionGroup group,
                ExpressionSyntax.Invocation invocation, List<BoundExpression> boundArgs,
                Scope scope, TypeSymbol callTarget) {
            List<TypeSymbol> writtenTypeArguments = writtenTypeArguments(invocation, scope);
            if (writtenTypeArguments == null || !applicableToWrittenArity(
                    group, writtenTypeArguments, invocation)) {
                return new BoundExpression.Error(invocation.span());
            }
            // C# §12.8.10.3 rewrites `receiver.Method(args)` as `Class.Method(receiver, args)`
            // *before* overload resolution runs, so the receiver is an ordinary first argument
            // from here on: one argument list, one resolution, one betterness ranking. Only the
            // written syntax differs, which is why the rewrite happens at the seam and nothing
            // downstream - inference, `out var` typing, the emitter - needs an extension case.
            List<BoundExpression> callArgs = group.extensionForm()
                    ? prepend(group.receiver(), boundArgs)
                    : boundArgs;
            List<AuxiliarySyntax.Argument> callSyntax = group.extensionForm()
                    ? prepend(receiverArgument(invocation), invocation.arguments())
                    : invocation.arguments();
            boolean extensionForm = group.extensionForm();
            BoundExpression callReceiver = extensionForm ? null : group.receiver();
            OverloadResult result = OverloadResolver.resolve(
                    group.candidates(), callArgs, callSyntax, lambdaTargets,
                    writtenTypeArguments);
            if (!(result instanceof OverloadResult.Success) && !extensionForm
                    && group.receiver() != null) {
                // C# searches extension methods when no instance member *applied*, not only
                // when none was named, so a member that exists but refuses these arguments
                // still gets the second search. The retry only replaces the first result when
                // it succeeds: a failing extension set must not take the instance member's
                // diagnostic away from the call the author actually wrote.
                List<FunctionSymbol> extensions = extensionCandidates(
                        scope, group.name(), group.receiver().type());
                List<BoundExpression> extensionArgs = prepend(group.receiver(), boundArgs);
                List<AuxiliarySyntax.Argument> extensionSyntax =
                        prepend(receiverArgument(invocation), invocation.arguments());
                OverloadResult retried = extensions.isEmpty() ? null : OverloadResolver.resolve(
                        extensions, extensionArgs, extensionSyntax, lambdaTargets,
                        writtenTypeArguments);
                if (retried instanceof OverloadResult.Success) {
                    group = new BoundExpression.FunctionGroup(group.span(), group.name(),
                            extensions, group.receiver(), true);
                    callArgs = extensionArgs;
                    callSyntax = extensionSyntax;
                    callReceiver = null;
                    extensionForm = true;
                    result = retried;
                }
            }
            if (result instanceof OverloadResult.Success success) {
                Candidate candidate = success.candidate();
                FunctionSymbol function = candidate.function();
                if (isEmptyStringParamsAmbiguity(candidate)) {
                    String name = function.qualifiedName();
                    diagnostics.report(DiagnosticCode.AMBIGUOUS_CALL, file, invocation.span(),
                            name + "(params ReadOnlySpan<object>)",
                            name + "(params ReadOnlySpan<string>)");
                    return new BoundExpression.Error(invocation.span());
                }
                List<BoundExpression> typedArgs = inferOutDeclarationTypes(candidate,
                        callSyntax, callArgs);
                CallArguments arguments = callArguments(candidate, typedArgs, scope,
                        invocation.span(), callTarget);
                // A lambda body that failed to bind leaves the type parameter it was going to
                // supply open, and a result type still mentioning `R` would name a type
                // parameter the user never wrote. The body's own diagnostic is the real one,
                // so the call becomes recovery instead of cascading.
                if (arguments.expressions().stream().anyMatch(Binder::isRecovery)) {
                    return new BoundExpression.Error(invocation.span());
                }
                // A type parameter only a lambda could supply stayed open through inference;
                // the bound body has now said what it is, so the result type is finished here
                // rather than left as `Stream<R>`.
                TypeSymbol resultType = closeAgainstCallTarget(function,
                        closeLambdaInferences(function.returnType(), arguments.expressions()),
                        callTarget);
                FunctionSymbol declaration = model.javaInterop()
                        .erasedDeclaration(candidate.declaration());
                reportUnboundedJoin(group.receiver(), group.name(), invocation.span());
                BoundExpression.Call call = new BoundExpression.Call(
                        invocation.span(), resultType, callReceiver, function,
                        declaration,
                        arguments.expressions(), arguments.parameterOrdinals());
                if (hasUnresolvedTypeParameters(resultType, scope)) {
                    openResults.put(call, invocation);
                    openResultNames.put(call, group.name());
                }
                return call;
            }
            reportOverloadFailure(result, group.name(), invocation.span(),
                    callSyntax, callArgs.size(), false, extensionForm ? 1 : 0);
            // A failed call still consumed its arguments: an open result among them has had
            // its real diagnostic reported here, precisely, against the target that refused
            // it. Dropping the entries keeps the CS0411 sweep from repeating the same fault
            // in vaguer words.
            boundArgs.forEach(openResults::remove);
            return new BoundExpression.Error(invocation.span());
        }

        /// The extension methods named `name` that C# §12.8.10.3 offers for a receiver of
        /// `receiverType`, in the order the enclosing scopes declare and import them: the
        /// innermost scope first, then what each of its `using` directives brought in, then
        /// the file's own scope. Nearness never decides a winner here - betterness does, in
        /// [OverloadResolver] - so the order only has to be deterministic.
        ///
        /// A `using static` directive imports the members themselves rather than the holder,
        /// which is why a function is accepted directly as well as through a container.
        ///
        /// Only the receiver decides candidacy, exactly as the specification says: the `this`
        /// parameter must accept it by an identity, reference or boxing conversion. A generic
        /// method's receiver is left to inference, and if it then refuses, resolution reports
        /// the mismatch against the receiver argument rather than hiding the method.
        private List<FunctionSymbol> extensionCandidates(
                Scope scope, String name, TypeSymbol receiverType) {
            if (receiverType == null || receiverType == TypeSymbol.Error.INSTANCE
                    || receiverType == TypeSymbol.Null.INSTANCE) {
                return List.of();
            }
            Map<String, FunctionSymbol> found = new LinkedHashMap<>();
            Scope fileScope = model.scopeFor(unit).orElse(model.globalScope());
            for (Scope start : List.of(scope, fileScope)) {
                for (Scope current = start; current != null;
                        current = current.parent().orElse(null)) {
                    collectExtensions(current, name, receiverType, found);
                    for (Scope imported : current.importedScopes()) {
                        collectExtensions(imported, name, receiverType, found);
                    }
                }
            }
            return List.copyOf(found.values());
        }

        private void collectExtensions(Scope scope, String name, TypeSymbol receiverType,
                Map<String, FunctionSymbol> found) {
            for (Symbol symbol : scope.symbols()) {
                switch (symbol) {
                    case ContainerSymbol container -> {
                        Optional<Scope> members = model.scopeOf(container);
                        if (members.isPresent()) {
                            for (Symbol member : members.get().lookupLocal(name)) {
                                acceptExtension(member, receiverType, found);
                            }
                        }
                    }
                    case FunctionSymbol function -> {
                        if (function.name().equals(name)) {
                            acceptExtension(function, receiverType, found);
                        }
                    }
                    default -> {
                    }
                }
            }
        }

        private void acceptExtension(Symbol symbol, TypeSymbol receiverType,
                Map<String, FunctionSymbol> found) {
            if (!(symbol instanceof FunctionSymbol function) || !function.isExtension()) {
                return;
            }
            TypeSymbol extended = function.extendedType().orElseThrow();
            boolean accepts = !function.typeParameters().isEmpty()
                    || switch (Conversions.classify(receiverType, extended).kind()) {
                        case IDENTITY, IMPLICIT_REFERENCE, BOXING -> true;
                        default -> false;
                    };
            if (accepts) {
                found.putIfAbsent(function.qualifiedName() + "#" + function.signature(),
                        function);
            }
        }

        private static <T> List<T> prepend(T head, List<T> tail) {
            List<T> all = new ArrayList<>(tail.size() + 1);
            all.add(head);
            all.addAll(tail);
            return List.copyOf(all);
        }

        /// The argument syntax standing for the receiver of an extension call in receiver form.
        ///
        /// Resolution reads argument syntax for named labels, `out var` declarations and lambda
        /// targets, none of which a receiver can be, so the node only has to carry the right
        /// span and the expression the author wrote before the dot. A conditional access binds
        /// its receiver once into a placeholder, and the written syntax is still the correct
        /// span for it.
        private static AuxiliarySyntax.Argument receiverArgument(
                ExpressionSyntax.Invocation invocation) {
            ExpressionSyntax written = invocation.target() instanceof ExpressionSyntax.MemberAccess
                    member ? member.receiver() : invocation.target();
            return new AuxiliarySyntax.Argument(written.span(), null, null, written);
        }

        /// Applies the selected out-parameter type to an `out var` declaration. The typeless
        /// argument participates in overload resolution first; only a unique winner may fix
        /// the local's type. Both the syntax-identity cache and the local inference table are
        /// updated because lowering reads the former for the call argument and later name
        /// binding reads the latter for uses of the declared local.
        private List<BoundExpression> inferOutDeclarationTypes(Candidate candidate,
                List<AuxiliarySyntax.Argument> argumentSyntax,
                List<BoundExpression> boundArgs) {
            List<BoundExpression> typed = null;
            for (int index = 0; index < boundArgs.size(); index++) {
                BoundExpression argument = boundArgs.get(index);
                if (argument.type() != TypeSymbol.Inferred.INSTANCE
                        || !(argument instanceof BoundExpression.Value value)
                        || !(value.symbol() instanceof LocalSymbol local)
                        || !(argumentSyntax.get(index).expression()
                                instanceof ExpressionSyntax.Declaration declaration)) {
                    continue;
                }
                int ordinal = candidate.argumentParameterOrdinals().get(index);
                ParameterSymbol parameter = candidate.function().parameters().get(ordinal);
                if (!parameter.modifiers().contains(SyntaxKind.OUT)) {
                    continue;
                }
                TypeSymbol inferred = parameter.type();
                if (inferred == TypeSymbol.Error.INSTANCE
                        || inferred == TypeSymbol.Inferred.INSTANCE) {
                    continue;
                }
                BoundExpression resolved = new BoundExpression.Value(argument.span(), inferred,
                        local);
                if (typed == null) {
                    typed = new ArrayList<>(boundArgs);
                }
                typed.set(index, resolved);
                inferredLocalTypes.put(local, inferred);
                expressions.put(declaration, resolved);
                bindingOrder.add(resolved);
            }
            return typed == null ? boundArgs : List.copyOf(typed);
        }

        /// .NET 10's C# 13 String surface has both object and string `params ReadOnlySpan`
        /// overloads. A call with no value arguments is therefore ambiguous even though V#
        /// represents the supported string-only subset with one equivalent params array.
        private static boolean isEmptyStringParamsAmbiguity(Candidate candidate) {
            if (!candidate.expandedParams()) {
                return false;
            }
            String name = candidate.function().qualifiedName();
            if (!name.equals("System.String.Concat") && !name.equals("System.String.Join")) {
                return false;
            }
            int paramsOrdinal = candidate.function().parameters().size() - 1;
            return candidate.argumentParameterOrdinals().stream()
                    .noneMatch(ordinal -> ordinal == paramsOrdinal);
        }

        /// Binds `receiver with { X = 1 }`: a copy of a positional `record struct` in which the
        /// named components take new values. C# defines `with` over the copy constructor
        /// and property setters of a record; V# has neither, so the expression is defined
        /// directly over the positional components - the only members a `record struct` has -
        /// and every other receiver type is refused rather than half-supported.
        private BoundExpression bindWith(ExpressionSyntax.With with, Scope scope) {
            BoundExpression receiver = bindExpression(with.receiver(), scope);
            List<BoundExpression> children = new ArrayList<>();
            children.add(receiver);
            Optional<PositionalLayout> layout = receiver.type() instanceof NamedTypeSymbol named
                    ? model.positionalLayout(named)
                    : Optional.empty();
            if (layout.isEmpty() && !isRecovery(receiver)) {
                diagnostics.report(DiagnosticCode.OBJECT_MODEL_UNSUPPORTED, file,
                        with.span(), "'with' on a type that is not a positional record struct");
                return new BoundExpression.Error(with.span());
            }
            Set<String> assigned = new LinkedHashSet<>();
            for (AuxiliarySyntax.VariableDeclarator initializer : with.initializers()) {
                if (initializer.initializer() == null || initializer.name() == null) {
                    continue;
                }
                Optional<FieldSymbol> component = layout.isEmpty()
                        ? Optional.empty()
                        : layout.get().component(initializer.name());
                // The component type is the assignment target, so it is the binding target too:
                // `p with { X = someByte }` has to carry the widening the constructor call needs.
                BoundExpression value = component
                        .map(field -> bindExpression(initializer.initializer(), scope,
                                field.type()))
                        .orElseGet(() -> bindExpression(initializer.initializer(), scope));
                children.add(value);
                if (layout.isEmpty()) {
                    continue;
                }
                if (component.isEmpty()) {
                    diagnostics.report(DiagnosticCode.MEMBER_NOT_FOUND, file,
                            initializer.span(), displayType(receiver.type()),
                            initializer.name());
                    continue;
                }
                if (!assigned.add(initializer.name())) {
                    diagnostics.report(DiagnosticCode.DUPLICATE_MEMBER, file,
                            initializer.span(), displayType(receiver.type()),
                            initializer.name());
                    continue;
                }
                requireImplicitConversion(value, component.get().type(),
                        initializer.initializer());
            }
            return new BoundExpression.Deferred(with.span(), receiver.type(), "with", children);
        }

        private BoundExpression bindObjectCreation(ExpressionSyntax.ObjectCreation creation,
                Scope scope) {
            TypeSymbol type = resolveType(creation.type(), scope);
            List<BoundExpression> boundArgs = bindArguments(creation.arguments(), scope);
            if (type == TypeSymbol.Error.INSTANCE) {
                return new BoundExpression.Error(creation.span());
            }
            NamedTypeSymbol named;
            if (type instanceof NamedTypeSymbol direct) {
                named = direct;
            } else if (type instanceof TypeSymbol.Constructed constructed) {
                named = constructed.definition();
            } else {
                diagnostics.report(DiagnosticCode.OBJECT_MODEL_UNSUPPORTED, file,
                        creation.span(), "object creation");
                return new BoundExpression.Error(creation.span());
            }
            // A positional `record struct` is the one V# type a `new` expression can build: its
            // constructor is synthesized from the declaration rather than declared, so there is
            // exactly one candidate and overload resolution still supplies named arguments,
            // optional defaults, argument conversions and the arity diagnostics.
            Optional<PositionalLayout> layout = model.positionalLayout(named);
            // A corelib exception is a V#-declared name whose values are instances of a real
            // JVM class, so `new Exception("boom")` selects one of that class's own
            // constructors while the expression keeps its C#-facing type. Without this a
            // program could catch the corelib exceptions but never throw one.
            NamedTypeSymbol carrier = corelibCarrierType(named);
            if (layout.isEmpty() && carrier == null && !model.javaInterop().isJavaType(named)) {
                diagnostics.report(DiagnosticCode.OBJECT_MODEL_UNSUPPORTED, file,
                        creation.span(), "object creation");
                return new BoundExpression.Error(creation.span());
            }

            List<FunctionSymbol> constructors = layout
                    .map(shape -> List.of(shape.constructor()))
                    .orElseGet(() -> model.javaInterop().getConstructors(
                            carrier != null ? carrier : named));
            OverloadResult result = OverloadResolver.resolve(constructors, boundArgs,
                    creation.arguments());
            if (result instanceof OverloadResult.Success success) {
                Candidate candidate = success.candidate();
                // A constructor reaches the JVM through its descriptor alone: `ObjectCreation`
                // carries no ordinal list the way `Call` does, so the same `params` packing and
                // omitted-default completion every call gets must be applied here and then laid
                // out in parameter order. Binding the arguments without it produced a
                // `NEW`/`DUP`/`INVOKESPECIAL` whose descriptor declared the array the stack
                // never held - a structurally invalid method the verifier rejected.
                CallArguments completed = callArguments(candidate, boundArgs, scope,
                        creation.span(), null);
                return new BoundExpression.ObjectCreation(creation.span(), type,
                        candidate.function(), inParameterOrder(completed));
            }
            reportOverloadFailure(result, named.displayName(), creation.span(),
                    creation.arguments(), boundArgs.size(), true);
            return new BoundExpression.Error(creation.span());
        }

        /// The module-path class carrying a corelib type, or `null` when the type is not a
        /// corelib name with a runtime class. The carrier answers constructor lookup; the
        /// corelib symbol stays the type of the expression, so diagnostics and `catch` keep
        /// naming what the source wrote.
        private NamedTypeSymbol corelibCarrierType(NamedTypeSymbol named) {
            if (model.javaInterop().isJavaType(named)) {
                return null;
            }
            return vsharp.compiler.semantics.types.CorelibCarriers
                    .javaClassName(named.qualifiedName())
                    .map(javaClass -> model.javaInterop().resolveType(javaClass))
                    .orElse(null);
        }

        private BoundExpression bindThrowExpression(ExpressionSyntax.Throw thrown, Scope scope) {
            BoundExpression operand = bindExpression(thrown.expression(), scope);
            requireThrowable(operand, thrown.expression());
            return new BoundExpression.Deferred(thrown.span(), TypeSymbol.Error.INSTANCE,
                    "throw", List.of(operand));
        }

        private void requireThrowable(BoundExpression expression, ExpressionSyntax syntax) {
            if (!isRecovery(expression) && expression.type() != TypeSymbol.Error.INSTANCE
                    && !isThrowable(expression.type())) {
                diagnostics.report(DiagnosticCode.TYPE_CAUGHT_OR_THROWN_MUST_DERIVE_FROM_EXCEPTION,
                        file, syntax.span());
            }
        }

        private boolean isThrowable(TypeSymbol type) {
            if (type == TypeSymbol.Null.INSTANCE) {
                return true;
            }
            if (!(type instanceof NamedTypeSymbol named)) {
                return false;
            }
            return vsharp.compiler.semantics.types.CorelibCarriers
                            .hasRuntimeClass(named.qualifiedName())
                    || model.javaInterop().isThrowable(named);
        }

        private List<BoundExpression> convertedArguments(Candidate candidate,
                List<BoundExpression> boundArgs, TypeSymbol callTarget) {
            FunctionSymbol function = candidate.function();
            List<BoundExpression> converted = new ArrayList<>(boundArgs.size());
            for (int i = 0; i < boundArgs.size(); i++) {
                BoundExpression argument = boundArgs.get(i);
                Conversion conversion = candidate.argumentConversions().get(i);
                if (conversion.kind() == ConversionKind.IDENTITY) {
                    converted.add(argument);
                    continue;
                }
                int ordinal = candidate.argumentParameterOrdinals().get(i);
                TypeSymbol targetType = function.parameters().get(ordinal).type();
                if (candidate.expandedParams()
                        && ordinal == function.parameters().size() - 1
                        && targetType instanceof TypeSymbol.Array paramsArray) {
                    targetType = paramsArray.elementType();
                }
                // The selected candidate is what gives an argument lambda its type, so
                // the lambda is bound here rather than wrapped: there is no source value to
                // convert, only a body that could not be bound until this target was known.
                if (conversion.kind() == ConversionKind.IMPLICIT_LAMBDA) {
                    TypeSymbol lambdaTarget = closeParameterAgainstCallTarget(function, targetType,
                            callTarget);
                    BoundExpression materialized = materializeLambda(argument, lambdaTarget);
                    if (materialized == argument
                            && GenericInference.hasOpenTypeParameters(lambdaTarget)) {
                        // Nothing here can type it; the call's own conversion still might.
                        pendingLambdas.add(new PendingLambda(argument, lambdaTarget));
                    }
                    converted.add(materialized);
                    continue;
                }
                argument = flushPendingLambdas(argument, targetType);
                openResults.remove(argument);
                converted.add(new BoundExpression.Conversion(argument.span(), argument,
                        targetType, conversion));
            }
            return List.copyOf(converted);
        }

        /// Binds any lambda left pending inside `argument` now that `targetType` names the
        /// type arguments its own call could not.
        ///
        /// This is the outer half of that rule. There, a declaration's type reached the call before
        /// its arguments were converted; here the call is itself an argument, so its type
        /// arrives one step later - at the conversion to the parameter it is passed to. The
        /// range recorded for this argument is what keeps the flush to lambdas from its own
        /// subtree, so two sibling calls sharing a generic method cannot close each other.
        private BoundExpression flushPendingLambdas(BoundExpression argument,
                TypeSymbol targetType) {
            int[] range = pendingRanges.get(argument);
            if (range == null || !GenericInference.hasOpenTypeParameters(argument.type())) {
                return argument;
            }
            Map<TypeParameterSymbol, TypeSymbol> closure =
                    GenericInference.inferFromTarget(argument.type(), targetType);
            if (closure == null) {
                return argument;
            }
            Map<BoundExpression, BoundExpression> materialized = new IdentityHashMap<>();
            for (int index = range[0]; index < range[1]; index++) {
                PendingLambda pending = pendingLambdas.get(index);
                BoundExpression bound = materializeLambda(pending.deferred(),
                        GenericInference.substituteType(pending.openTarget(), closure));
                if (bound != pending.deferred()) {
                    materialized.put(pending.deferred(), bound);
                }
            }
            return closeCall(argument, closure, materialized);
        }

        /// The same call with the closure applied to its result and its signature.
        ///
        /// Retyping rather than leaving the open node is what upholds the rule's rule that an open
        /// type is never stored: the emission guard walks every bound expression, so a call
        /// still reading `Comparator<T>` would be refused as an unsupported generic carrier
        /// even though its target had already named `T`. The node is replaced in the binding
        /// order in place, never appended, because that walk also emits one method per lambda
        /// and a duplicate would emit twice.
        private BoundExpression closeCall(BoundExpression argument,
                Map<TypeParameterSymbol, TypeSymbol> closure,
                Map<BoundExpression, BoundExpression> materialized) {
            if (!(argument instanceof BoundExpression.Call call)) {
                return argument;
            }
            FunctionSymbol function = call.function();
            List<ParameterSymbol> parameters = function.parameters().stream()
                    .map(parameter -> new ParameterSymbol(parameter.name(),
                            parameter.qualifiedName(), parameter.location(),
                            GenericInference.substituteType(parameter.type(), closure),
                            parameter.ordinal(), parameter.modifiers(), parameter.defaultValue()))
                    .toList();
            FunctionSymbol closedFunction = new FunctionSymbol(function.name(),
                    function.qualifiedName(), function.location(),
                    GenericInference.substituteType(function.returnType(), closure),
                    function.typeParameters(), parameters, function.modifiers(),
                    function.localFunction(), function.synthesized(), function.interfaceOwner());
            BoundExpression.Call closed = new BoundExpression.Call(call.span(),
                    GenericInference.substituteType(call.type(), closure), call.receiver(),
                    closedFunction, call.declaration(),
                    materializedArguments(call.arguments(), materialized),
                    call.argumentParameterOrdinals());
            int position = indexOfIdentity(bindingOrder, call);
            if (position >= 0) {
                bindingOrder.set(position, closed);
            }
            for (Map.Entry<ExpressionSyntax, BoundExpression> entry : expressions.entrySet()) {
                if (entry.getValue() == call) {
                    entry.setValue(closed);
                    break;
                }
            }
            openResults.remove(call);
            return closed;
        }

        /// Puts each freshly bound lambda in the place its deferred node still holds.
        ///
        /// The lambda stayed unbound in the argument list because nothing had typed it when the
        /// call was bound. Closing only the signature is not enough: the emission gate
        /// reads the bound argument carriers, so a call whose signature named `T` while its
        /// argument still carried the untyped node is refused as an unsupported generic carrier.
        /// Only direct arguments are replaced; a lambda deferred deeper inside another open call
        /// leaves that call open and is reported by the gate rather than emitted.
        private static List<BoundExpression> materializedArguments(List<BoundExpression> arguments,
                Map<BoundExpression, BoundExpression> materialized) {
            if (materialized.isEmpty()) {
                return arguments;
            }
            List<BoundExpression> replaced = new ArrayList<>(arguments.size());
            for (BoundExpression argument : arguments) {
                replaced.add(materialized.getOrDefault(argument, argument));
            }
            return List.copyOf(replaced);
        }

        private static int indexOfIdentity(List<BoundExpression> list, BoundExpression target) {
            for (int index = 0; index < list.size(); index++) {
                if (list.get(index) == target) {
                    return index;
                }
            }
            return -1;
        }

        /// Supplies a lambda parameter type that no argument can close, from the type the call
        /// itself is being converted to.
        ///
        /// `Comparator.ComparingInt(n =&gt; n.Length())` declares
        /// `&lt;T&gt; Comparator&lt;T&gt; comparingInt(ToIntFunction&lt;? super T&gt;)`. Nothing in the argument
        /// list mentions `T` - the only argument is the lambda whose parameter `T` types - so
        /// inference leaves it open and the lambda has no type to bind its body against.
        /// The declaration being initialised says `Comparator<string>`, and matching the result
        /// type against it binds `T` under the same bound check every other type argument gets
        ///. Returns the parameter unchanged whenever there is no target, nothing is
        /// open, or the target does not close it; the lambda then fails on its own terms.
        private TypeSymbol closeParameterAgainstCallTarget(FunctionSymbol function,
                TypeSymbol parameterType, TypeSymbol callTarget) {
            if (callTarget == null
                    || !vsharp.compiler.semantics.overloads.GenericInference
                            .hasOpenTypeParameters(parameterType)) {
                return parameterType;
            }
            Map<TypeParameterSymbol, TypeSymbol> inferred = vsharp.compiler.semantics.overloads
                    .GenericInference.inferFromTarget(function.returnType(), callTarget);
            if (inferred == null) {
                return parameterType;
            }
            return vsharp.compiler.semantics.overloads.GenericInference
                    .substituteType(parameterType, inferred);
        }

        /// Closes a result type the lambda could not close, against the type the call is being
        /// converted to.
        ///
        /// [#closeParameterAgainstCallTarget] already reads the target to type a lambda's
        /// *parameter*, and doing so hides the result: once `Function&lt;T, R&gt;` has become
        /// `Function&lt;T, int&gt;`, the lambda's declared return is no longer the open `R`, so
        /// nothing is left for [#closeLambdaInferences] to learn from and the result stayed open
        ///. The expression then carried a type parameter into contexts that had been
        /// type-checked as `int`, and the emitted call returned the erasure unnarrowed - a class
        /// the JVM refuses to load. The same inference the parameter used answers for the result.
        private TypeSymbol closeAgainstCallTarget(FunctionSymbol function, TypeSymbol resultType,
                TypeSymbol callTarget) {
            if (callTarget == null || !vsharp.compiler.semantics.overloads.GenericInference
                    .hasOpenTypeParameters(resultType)) {
                return resultType;
            }
            Map<TypeParameterSymbol, TypeSymbol> inferred = vsharp.compiler.semantics.overloads
                    .GenericInference.inferFromTarget(function.returnType(), callTarget);
            return inferred == null ? resultType : vsharp.compiler.semantics.overloads
                    .GenericInference.substituteType(resultType, inferred);
        }

        /// The explicit arguments remain in textual order so side effects retain C#'s
        /// left-to-right evaluation rule. Omitted declaration defaults are appended and the
        /// parallel ordinal list tells lowering which JVM parameter each value supplies.
        private CallArguments callArguments(Candidate candidate,
                List<BoundExpression> boundArgs, Scope callScope, SourceSpan callSpan,
                TypeSymbol callTarget) {
            List<BoundExpression> arguments = new ArrayList<>(
                    convertedArguments(candidate, boundArgs, callTarget));
            List<Integer> parameterOrdinals = new ArrayList<>(
                    candidate.argumentParameterOrdinals());
            if (candidate.expandedParams()) {
                CallArguments expanded = collapseParamsArray(candidate, arguments,
                        parameterOrdinals, callSpan);
                arguments = new ArrayList<>(expanded.expressions());
                parameterOrdinals = new ArrayList<>(expanded.parameterOrdinals());
            }
            Scope declarationScope = model.scopeOf(candidate.declaration()).orElse(callScope);
            for (ParameterSymbol parameter : candidate.omittedParameters()) {
                ExpressionSyntax defaultValue = parameter.defaultValue();
                if (defaultValue == null) {
                    throw new IllegalStateException(
                            "an omitted parameter has no declaration default: "
                                    + parameter.qualifiedName());
                }
                arguments.add(bindExpression(defaultValue, declarationScope, parameter.type()));
                parameterOrdinals.add(parameter.ordinal());
            }
            return new CallArguments(arguments, parameterOrdinals);
        }

        /// Lays completed arguments out in declaration order, for the one call shape that
        /// cannot carry ordinals to lowering.
        ///
        /// `Call` keeps the written order and lets the backend store each value into the slot
        /// its ordinal names, which is how C#'s left-to-right evaluation survives named
        /// arguments. A constructor has no such channel, so the order is settled here; the
        /// sort is stable, leaving positional arguments exactly as written.
        private static List<BoundExpression> inParameterOrder(CallArguments arguments) {
            List<Integer> ordinals = arguments.parameterOrdinals();
            List<BoundExpression> expressions = arguments.expressions();
            List<Integer> positions = new ArrayList<>();
            for (int index = 0; index < expressions.size(); index++) {
                positions.add(index);
            }
            positions.sort(Comparator.comparingInt(ordinals::get));
            List<BoundExpression> ordered = new ArrayList<>(expressions.size());
            for (int position : positions) {
                ordered.add(expressions.get(position));
            }
            return List.copyOf(ordered);
        }

        /// Turns the arguments matched to a `params` parameter into the single array C#
        /// passes (§12.6.2.4).
        ///
        /// Overload resolution already converted each of them to the *element* type, so all
        /// that remains is the array itself - built as the collection form the backend
        /// already emits, which is why nothing downstream needs a `params` case.
        private CallArguments collapseParamsArray(Candidate candidate,
                List<BoundExpression> arguments, List<Integer> parameterOrdinals,
                SourceSpan callSpan) {
            int last = candidate.function().parameters().size() - 1;
            if (last < 0 || !(candidate.function().parameters().get(last).type()
                    instanceof TypeSymbol.Array arrayType)) {
                return new CallArguments(arguments, parameterOrdinals);
            }
            TypeSymbol.Array packedArrayType = arrayType;
            if (JavaInterop.isModulePathSymbol(candidate.declaration())) {
                FunctionSymbol erased = model.javaInterop()
                        .erasedDeclaration(candidate.declaration());
                if (erased.parameters().get(last).type() instanceof TypeSymbol.Array erasedArray) {
                    packedArrayType = erasedArray;
                }
            }
            List<BoundExpression.Collection.Element> elements = new ArrayList<>();
            List<BoundExpression> fixedArguments = new ArrayList<>();
            List<Integer> fixedOrdinals = new ArrayList<>();
            for (int index = 0; index < arguments.size(); index++) {
                if (parameterOrdinals.get(index) == last) {
                    BoundExpression element = arguments.get(index);
                    Conversion conversion = Conversions.classify(element,
                            packedArrayType.elementType());
                    if (!conversion.isImplicit()) {
                        throw new IllegalStateException(
                                "expanded Java params element cannot reach erased array carrier: "
                                        + element.type().displayName() + " -> "
                                        + packedArrayType.elementType().displayName());
                    }
                    if (!conversion.isIdentity()) {
                        element = new BoundExpression.Conversion(element.span(), element,
                                packedArrayType.elementType(), conversion);
                    }
                    elements.add(new BoundExpression.Collection.Element(false,
                            element));
                } else {
                    fixedArguments.add(arguments.get(index));
                    fixedOrdinals.add(parameterOrdinals.get(index));
                }
            }
            SourceSpan span = elements.isEmpty()
                    ? callSpan : elements.getFirst().expression().span();
            fixedArguments.add(new BoundExpression.Collection(span, packedArrayType, elements));
            fixedOrdinals.add(last);
            return new CallArguments(fixedArguments, fixedOrdinals);
        }

        private record CallArguments(List<BoundExpression> expressions,
                List<Integer> parameterOrdinals) {
            private CallArguments {
                expressions = List.copyOf(expressions);
                parameterOrdinals = List.copyOf(parameterOrdinals);
            }
        }

        private void reportOverloadFailure(OverloadResult result, String name, SourceSpan span,
                List<AuxiliarySyntax.Argument> argumentSyntax, int argumentCount) {
            reportOverloadFailure(result, name, span, argumentSyntax, argumentCount, false, 0);
        }

        private void reportOverloadFailure(OverloadResult result, String name, SourceSpan span,
                List<AuxiliarySyntax.Argument> argumentSyntax, int argumentCount,
                boolean constructor) {
            reportOverloadFailure(result, name, span, argumentSyntax, argumentCount, constructor, 0);
        }

        /// Reports why no candidate applied.
        ///
        /// Every reason but one is shared between a call and a `new` expression, because an
        /// argument that will not convert is the same failure whichever the author wrote. The
        /// arity reason is not: C# names the *type* whose constructors were searched and
        /// reports CS1729, because a constructor has no member name to put in CS1501's place
        ///.
        ///
        /// `synthetic` is the number of leading arguments the resolution list carries that the
        /// program did not write - one for an extension call in receiver form, zero
        /// otherwise. Spans keep indexing the resolution list, because that is the list the
        /// failure indexes, while every argument *number* in a message is reduced back to the
        /// written form: `text.Repeat("x")` names argument 1, not argument 2, exactly as C#
        /// does for the same program. A failure on the receiver itself has no written ordinal
        /// at all, so it reports CS1929 against the receiver instead.
        private void reportOverloadFailure(OverloadResult result, String name, SourceSpan span,
                List<AuxiliarySyntax.Argument> argumentSyntax, int argumentCount,
                boolean constructor, int synthetic) {
            if (result instanceof OverloadResult.Ambiguous ambiguous) {
                diagnostics.report(DiagnosticCode.AMBIGUOUS_CALL, file, span,
                        ambiguous.candidates().get(0).signature(),
                        ambiguous.candidates().get(1).signature());
                return;
            }
            if (!(result instanceof OverloadResult.Failure failure)) {
                throw new IllegalStateException("unhandled overload result: " + result);
            }
            int index = failure.argumentIndex();
            if (synthetic > 0 && index > 0 && index <= synthetic
                    && failure.reason()
                            != OverloadResult.FailureReason.NO_OVERLOAD_TAKES_N_ARGUMENTS) {
                diagnostics.report(DiagnosticCode.EXTENSION_RECEIVER_MISMATCH, file,
                        argumentSyntax.get(index - 1).span(), failure.actualType(), name,
                        failure.expectedType());
                return;
            }
            switch (failure.reason()) {
                case NO_OVERLOAD_TAKES_N_ARGUMENTS ->
                    diagnostics.report(constructor
                                    ? DiagnosticCode.NO_CONSTRUCTOR_TAKES_N_ARGUMENTS
                                    : DiagnosticCode.NO_OVERLOAD_TAKES_N_ARGUMENTS, file,
                            span, name, argumentCount - synthetic);
                case CANNOT_CONVERT_ARGUMENT ->
                    diagnostics.report(DiagnosticCode.CANNOT_CONVERT_ARGUMENT, file,
                            argumentSyntax.get(index - 1).span(),
                            index - synthetic, failure.actualType(), failure.expectedType());
                case REF_OUT_MODIFIER_MISSING ->
                    diagnostics.report(DiagnosticCode.ARGUMENT_MUST_BE_PASSED_WITH_REF_OUT, file,
                            argumentSyntax.get(index - 1).span(),
                            index - synthetic, failure.modifier());
                case REF_OUT_MODIFIER_UNEXPECTED ->
                    diagnostics.report(DiagnosticCode.ARGUMENT_SHOULD_NOT_BE_PASSED_WITH_REF_OUT,
                            file, argumentSyntax.get(index - 1).span(),
                            index - synthetic, failure.modifier());
                case NOT_VARIABLE ->
                    diagnostics.report(DiagnosticCode.REF_OUT_ARGUMENT_MUST_BE_VARIABLE, file,
                            argumentSyntax.get(index - 1).span());
                // The failure carries the offending type, the constraint's spelling and the
                // type parameter's name, so the message names the program rather than the
                // resolution.
                case CONSTRAINT_VIOLATION -> {
                    switch (failure.expectedType()) {
                        case "struct" -> diagnostics.report(
                                DiagnosticCode.CONSTRAINT_REQUIRES_VALUE_TYPE, file, span,
                                failure.actualType(), failure.modifier(), name);
                        case "class" -> diagnostics.report(
                                DiagnosticCode.CONSTRAINT_REQUIRES_REFERENCE_TYPE, file, span,
                                failure.actualType(), failure.modifier(), name);
                        // A named type the argument does not convert to: C# names both
                        // ends of the missing conversion, which is what tells the author
                        // whether to change the argument or the clause.
                        default -> diagnostics.report(
                                DiagnosticCode.CONSTRAINT_REQUIRES_CONVERSION, file, span,
                                failure.actualType(), failure.modifier(), name,
                                failure.actualType(), failure.expectedType());
                    }
                }
            }
        }

        private BoundExpression bindMemberAccess(ExpressionSyntax.MemberAccess member, Scope scope) {
            if (member.receiver() instanceof ExpressionSyntax.PredefinedType predefined) {
                return bindPredefinedTypeMember(member, predefined);
            }
            // `nint`/`nuint` are contextual keywords and therefore remain Identifier nodes
            // after parsing. Treat them as predefined type receivers only when no value or
            // declaration in the current scope owns that spelling; contextual keywords must
            // remain legal identifiers and obey normal shadowing.
            if (member.receiver() instanceof ExpressionSyntax.Identifier identifier
                    && identifier.typeArguments().isEmpty()
                    && lookupInScope(scope, identifier.name()).isEmpty()
                    && (identifier.name().equals("nint")
                            || identifier.name().equals("nuint"))) {
                SyntaxKind keyword = identifier.name().equals("nint")
                        ? SyntaxKind.NINT : SyntaxKind.NUINT;
                return bindPredefinedTypeMember(member,
                        new ExpressionSyntax.PredefinedType(identifier.span(), keyword));
            }
            if (!member.nullConditional()) {
                BoundExpression javaStaticMember = bindQualifiedJavaStaticMember(member, scope);
                if (javaStaticMember != null) {
                    return javaStaticMember;
                }
            }
            BoundExpression boundReceiver = bindExpression(member.receiver(), scope);
            if (boundReceiver instanceof BoundExpression.Error || isRecovery(boundReceiver)) {
                return new BoundExpression.Error(member.span());
            }
            if (reportQualifiedTypeReceiver(member, boundReceiver, scope)) {
                return new BoundExpression.Error(member.span());
            }

            if (member.nullConditional()) {
                return mapConditionalTail(boundReceiver, member.span(), receiver ->
                        bindConditionalMember(member, scope, receiver));
            }
            if (boundReceiver instanceof BoundExpression.NullConditional conditional) {
                return mapConditionalTail(conditional, member.span(), receiver ->
                        bindMemberAccessCore(member, scope, receiver));
            }
            return bindMemberAccessCore(member, scope, boundReceiver);
        }

        /// Refuses a V# type reached through a written namespace path, such as
        /// `System.Console.WriteLine`.
        ///
        /// The Java form of this is caught where a package-qualified receiver is resolved; this
        /// is the V# form, where the receiver binds to a declaration group instead. Two
        /// conditions have to hold together, and the second is what keeps nested static classes
        /// working: the receiver must be *dotted*, and its leftmost segment must denote a
        /// namespace rather than a type. `Outer.Inner.Member` inside one file is untouched,
        /// because `Outer` is a type.
        private boolean reportQualifiedTypeReceiver(ExpressionSyntax.MemberAccess member,
                BoundExpression boundReceiver, Scope scope) {
            if (!(member.receiver() instanceof ExpressionSyntax.MemberAccess dotted)
                    || !(boundReceiver instanceof BoundExpression.DeclarationGroup group)
                    || group.candidates().stream().noneMatch(NamedTypeSymbol.class::isInstance)) {
                return false;
            }
            ExpressionSyntax leftmost = dotted;
            while (leftmost instanceof ExpressionSyntax.MemberAccess next) {
                leftmost = next.receiver();
            }
            if (!(leftmost instanceof ExpressionSyntax.Identifier root)) {
                return false;
            }
            List<Symbol> rootSymbols = lookupInScope(scope, root.name());
            if (rootSymbols.isEmpty()
                    || rootSymbols.stream().anyMatch(NamedTypeSymbol.class::isInstance)) {
                return false;
            }
            NamedTypeSymbol named = group.candidates().stream()
                    .filter(NamedTypeSymbol.class::isInstance)
                    .map(NamedTypeSymbol.class::cast)
                    .findFirst().orElseThrow();
            String qualified = named.qualifiedName().replace('$', '.');
            int lastDot = qualified.lastIndexOf('.');
            diagnostics.report(DiagnosticCode.QUALIFIED_TYPE_NAME, file, dotted.span(),
                    qualified.substring(lastDot + 1), qualified.substring(0, lastDot),
                    qualified.substring(lastDot + 1), qualified);
            return true;
        }

        private BoundExpression bindConditionalMember(ExpressionSyntax.MemberAccess member,
                Scope scope, BoundExpression receiver) {
            if (!supportsConditionalReceiver(receiver, member.span())) {
                return new BoundExpression.Error(member.span());
            }
            BoundExpression placeholder = new BoundExpression.ConditionalReceiver(
                    member.receiver().span(), conditionalReceiverValueType(receiver),
                    receiver.type());
            BoundExpression access = bindMemberAccessCore(member, scope, placeholder);
            return finishConditionalAccess(member.span(), receiver, access);
        }

        private BoundExpression bindMemberAccessCore(ExpressionSyntax.MemberAccess member,
                Scope scope, BoundExpression boundReceiver) {

            String memberName = member.name();

            if (boundReceiver instanceof BoundExpression.DeclarationGroup declarationGroup) {
                List<Symbol> membersFound = new ArrayList<>();
                for (Symbol containerSymbol : declarationGroup.candidates()) {
                    Optional<Scope> containerScope = model.scopeOf(containerSymbol);
                    if (containerScope.isPresent()) {
                        membersFound.addAll(containerScope.get().lookupLocal(memberName));
                    }
                }

                if (membersFound.isEmpty()) {
                    diagnostics.report(DiagnosticCode.MEMBER_NOT_FOUND, file, member.span(),
                            declarationGroup.name(), memberName);
                    return new BoundExpression.Error(member.span());
                }

                List<FunctionSymbol> functions = membersFound.stream()
                        .filter(FunctionSymbol.class::isInstance)
                        .map(FunctionSymbol.class::cast)
                        .toList();

                if (!functions.isEmpty()) {
                    return new BoundExpression.FunctionGroup(member.span(), memberName, functions, boundReceiver);
                }

                Symbol first = membersFound.get(0);
                if (first.kind() == SymbolKind.FIELD || first.kind() == SymbolKind.ENUM_MEMBER) {
                    TypeSymbol memberType = first instanceof FieldSymbol field
                            ? field.type()
                            : ((EnumMemberSymbol) first).type();
                    return new BoundExpression.Value(member.span(), memberType, first);
                }

                return new BoundExpression.DeclarationGroup(member.span(), memberName, membersFound);
            }

            TypeSymbol receiverType = boundReceiver.type();

            if (receiverType instanceof TypeSymbol.Tuple tuple) {
                int index = tupleElementIndex(tuple, memberName);
                if (index >= 0) {
                    return new BoundExpression.TupleElement(member.span(),
                            tuple.elements().get(index).type(), boundReceiver, index);
                }
            }

            if (receiverType instanceof TypeSymbol.Array) {
                if (memberName.equals("Length")) {
                    return new BoundExpression.ArrayLength(member.span(), boundReceiver);
                }
            }

            if (receiverType == BuiltinType.STRING) {
                if (memberName.equals("Length")) {
                    return new BoundExpression.StringLength(member.span(), boundReceiver);
                }
            }

            if (!member.nullConditional() && receiverType instanceof TypeSymbol.Nullable nullable) {
                if (memberName.equals("HasValue")) {
                    return new BoundExpression.NullableHasValue(member.span(), boundReceiver);
                }
                if (memberName.equals("Value")) {
                    return new BoundExpression.NullableValue(member.span(), nullable.element(),
                            boundReceiver);
                }
            }

            // A wildcard-typed receiver is looked through to its bound, which is Java's capture
            // conversion as far as member lookup can observe it: `WebClient.get()` is
            // typed `RequestHeadersUriSpec<?>`, and every chained call reads members of the
            // bound that `?` stands for, never of the wildcard itself.
            if (receiverType instanceof TypeSymbol.Wildcard wildcardReceiver) {
                receiverType = wildcardReceiver.bound();
            }

            // The corelib struct answers the lookup, but the keyword is what the source wrote:
            // a diagnostic naming `System.Int32` where the program says `int` describes a type
            // the author never mentioned.
            TypeSymbol writtenType = receiverType;
            NamedTypeSymbol corelibReceiver = corelibTypeFor(receiverType);
            if (corelibReceiver != null) {
                receiverType = corelibReceiver;
            }

            NamedTypeSymbol declaredType = switch (receiverType) {
                case NamedTypeSymbol named -> named;
                case TypeSymbol.Constructed constructed -> constructed.definition();
                default -> null;
            };
            NamedTypeSymbol namedType = javaCarrierSurface(declaredType, memberName);
            if (namedType != null) {
                // A Java type reached only through a descriptor - the return of
                // `a.Stream()`, never written in the program - has no declaration scope,
                // because scopes are built where a type is *named*. Its members are still
                // known, so the resolver answers directly and method chaining works.
                boolean javaType = model.javaInterop().isJavaType(namedType);
                Optional<Scope> typeScope = model.scopeOf(namedType);
                if (typeScope.isPresent() || javaType) {
                    java.util.function.Function<String, List<Symbol>> lookup =
                            typeScope.<java.util.function.Function<String, List<Symbol>>>map(
                                    scoped -> scoped::lookupLocal)
                            .orElseGet(() -> name -> model.javaInterop().getMembers(namedType)
                                    .stream().filter(symbol -> symbol.name().equals(name))
                                    .toList());
                    String resolvedName = memberName;
                    boolean propertyAccessor = false;
                    List<Symbol> membersFound = lookup.apply(resolvedName);
                    if (javaType) {
                        // V# spells every member in PascalCase; the JDK spells its own in
                        // camelCase. The written name is authoritative and only its
                        // absence reaches the translation, so a Java member that already
                        // starts uppercase keeps answering under its own spelling.
                        if (membersFound.isEmpty()) {
                            String jvmName = JavaInterop.jvmMemberName(resolvedName);
                            if (jvmName != null) {
                                List<Symbol> translated = lookup.apply(jvmName);
                                if (!translated.isEmpty()) {
                                    resolvedName = jvmName;
                                    membersFound = translated;
                                }
                            }
                        }
                        if (membersFound.isEmpty() && "GetType".equals(resolvedName)) {
                            // The one member whose C# and JDK names differ by more than case:
                            // `GetType()` is `getClass()`, and V#'s `System.Type` is the
                            // `java.lang.Class` it returns. Every V# type answers
                            // `GetType()` - the nine carriers through `corelib.vs`, a Java
                            // receiver here - so a C# program never has to know which world
                            // its receiver came from. A Java class that declares its own
                            // `getType()` has already answered above and never arrives.
                            List<Symbol> inherited = lookup.apply("getClass");
                            if (!inherited.isEmpty()) {
                                resolvedName = "getClass";
                                membersFound = inherited;
                            }
                        }
                        if (membersFound.isEmpty()) {
                            // A C# property read reaches the JDK as its accessor:
                            // `error.Message` is `getMessage()`, `entry.Key` is `getKey()`.
                            // The rule is the JavaBeans convention the whole JDK already
                            // follows, not a per-member table, and it fires only after the
                            // written name and its camelCase translation both found nothing -
                            // so a Java class that really declares `Message` or `message`
                            // keeps answering first, and no existing binding changes.
                            List<Symbol> accessors = lookup.apply("get" + memberName);
                            if (!accessors.isEmpty()) {
                                resolvedName = "get" + memberName;
                                membersFound = accessors;
                                propertyAccessor = true;
                            }
                        }
                        if (!membersFound.isEmpty() && resolvedName.equals(memberName)
                                && reportJavaCasing(member.span(), memberName,
                                name -> lookup.apply(name).isEmpty())) {
                            return new BoundExpression.Error(member.span());
                        }
                    }
                    if (!membersFound.isEmpty()) {
                        if (javaType) {
                            membersFound = model.javaInterop()
                                    .specializeMembers(receiverType, membersFound);
                        }
                        List<FunctionSymbol> functions = membersFound.stream()
                                .filter(FunctionSymbol.class::isInstance)
                                .map(FunctionSymbol.class::cast)
                                .toList();
                        if (propertyAccessor) {
                            // A property read is complete where it is written, so the access
                            // becomes the call itself and never a method group: nothing later
                            // will supply an argument list for it. Only a genuine accessor
                            // shape qualifies - no parameters, a result - and an overloaded
                            // `getX` is not one, so it falls through to the same
                            // "no definition" diagnostic the name had without this rule.
                            List<FunctionSymbol> accessors = functions.stream()
                                    .filter(function -> function.parameters().isEmpty()
                                            && function.returnType() != BuiltinType.VOID)
                                    .toList();
                            if (accessors.size() == 1) {
                                FunctionSymbol accessor = accessors.get(0);
                                return new BoundExpression.Call(member.span(),
                                        accessor.returnType(), boundReceiver, accessor,
                                        model.javaInterop().erasedDeclaration(accessor),
                                        List.of(), List.of());
                            }
                        } else if (!functions.isEmpty()) {
                            return new BoundExpression.FunctionGroup(member.span(), resolvedName, functions, boundReceiver);
                        }
                        Symbol first = membersFound.get(0);
                        if (first instanceof FieldSymbol field) {
                            return new BoundExpression.MemberAccess(member.span(), field.type(), boundReceiver, field);
                        }
                    }
                }
            }

            if (receiverType == TypeSymbol.Error.INSTANCE) {
                return new BoundExpression.Error(member.span());
            }

            // The name is not a member of the receiver, so C# §12.8.10.3's second search runs
            // before the failure is reported: an extension method in scope answers here, and
            // the group remembers that it was reached in receiver form so the call site can
            // pass the receiver as the first argument.
            // The *written* type decides candidacy, not the corelib carrier that answered the
            // member lookup: `this int` extends what the program spells `int`, and classifying
            // against `System.Int32` would refuse the identity conversion the author sees.
            List<FunctionSymbol> extensions =
                    extensionCandidates(scope, memberName, writtenType);
            if (!extensions.isEmpty()) {
                return new BoundExpression.FunctionGroup(member.span(), memberName, extensions,
                        boundReceiver, true);
            }

            diagnostics.report(DiagnosticCode.MEMBER_NOT_FOUND, file, member.span(),
                    displayType(writtenType), memberName);
            return new BoundExpression.Error(member.span());
        }

        /// Binds a static member written through a keyword type, including `int.MaxValue`
        /// and a curated corelib function such as `char.IsDigit`.
        ///
        /// C# defines these as constants (§7.3), and V# binds them as literals for the same
        /// reason: a field read would need a corelib class the backend never emits, and would
        /// not fold in the constant contexts (`const`, `case`, array sizes) that reach them.
        /// Functions are resolved only when their declaration is static. Instance members
        /// remain reachable through a value receiver, never through the type spelling.
        private BoundExpression bindPredefinedTypeMember(ExpressionSyntax.MemberAccess member,
                ExpressionSyntax.PredefinedType predefined) {
            BuiltinType builtin = BuiltinType.fromKeyword(predefined.keyword()).orElse(null);
            Map<BuiltinType, Object> constants = switch (member.name()) {
                case "MinValue" -> MIN_VALUES;
                case "MaxValue" -> MAX_VALUES;
                default -> FLOATING_CONSTANTS.get(member.name());
            };
            Object value = builtin == null || constants == null ? null : constants.get(builtin);
            if (value != null) {
                return new BoundExpression.Literal(member.span(), builtin, value);
            }

            NamedTypeSymbol corelibType = builtin == null ? null : corelibTypeFor(builtin);
            if (corelibType != null) {
                List<FunctionSymbol> functions = model.scopeOf(corelibType).stream()
                        .flatMap(typeScope -> typeScope.lookupLocal(member.name()).stream())
                        .filter(FunctionSymbol.class::isInstance)
                        .map(FunctionSymbol.class::cast)
                        .filter(function -> function.modifiers().contains(SyntaxKind.STATIC))
                        .toList();
                if (!functions.isEmpty()) {
                    BoundExpression typeReceiver = new BoundExpression.DeclarationGroup(
                            predefined.span(), builtin.displayName(), List.of(corelibType));
                    return new BoundExpression.FunctionGroup(member.span(), member.name(),
                            functions, typeReceiver);
                }
            }

            diagnostics.report(DiagnosticCode.MEMBER_NOT_FOUND, file, member.span(),
                    builtin == null ? predefined.keyword().display() : builtin.displayName(),
                    member.name());
            return new BoundExpression.Error(member.span());
        }

        /// The type whose member surface answers a lookup: the declared type itself, or the
        /// JVM class carrying it when that class is real and the declaration is silent.
        ///
        /// A corelib type listed in [CorelibCarriers] *is* a JVM class - a caught
        /// `System.InvalidOperationException` is a `java.lang.IllegalStateException` on the
        /// stack - so every member that class defines is already present on the value. Reading
        /// them through the ordinary Java path gives `GetMessage()`, `GetCause()`,
        /// `GetStackTrace()` and `PrintStackTrace()` on the whole exception family under the
        /// same PascalCase translation every other Java receiver uses, instead of one
        /// curated declaration and one runtime target per member.
        ///
        /// The substitution is confined to types that have a carrier class for a reason that
        /// is not convenience: `string` and the numeric keywords deliberately have none,
        /// so a name C# defines - `Split`, `IndexOf`, `Replace`, `Format` - can never be
        /// answered here by a Java method that spells itself the same and means something
        /// else. The curated declaration is consulted first, so where V# does own the name it
        /// keeps it. What the carrier surface does expose is its JVM spelling: `ToString()`
        /// on an exception renders the Java class name and no stack frames, the same carrier
        /// visibility `GetType()` already documents.
        private NamedTypeSymbol javaCarrierSurface(NamedTypeSymbol declaredType, String memberName) {
            if (declaredType == null
                    || !CorelibCarriers.hasRuntimeClass(declaredType.qualifiedName())) {
                return declaredType;
            }
            boolean declared = model.scopeOf(declaredType)
                    .map(scope -> !scope.lookupLocal(memberName).isEmpty())
                    .orElse(false);
            if (declared) {
                return declaredType;
            }
            String carrier = CorelibCarriers.javaClassName(declaredType.qualifiedName())
                    .orElseThrow();
            NamedTypeSymbol carrierType = model.javaInterop().resolveType(carrier);
            return carrierType == null ? declaredType : carrierType;
        }

        /// The corelib declaration standing in for a builtin type during member lookup, or
        /// `null` when the type declares no members.
        ///
        /// C# has no separate namespace for keyword types - `int` *is* `System.Int32` - but
        /// V# keeps builtins as `BuiltinType`, which owns no scope. Exchanging the receiver
        /// here, and only here, keeps that representation while letting the curated corelib
        /// member set answer `i.ToString()`. Conversions, carriers and overload resolution
        /// continue to see the builtin, so nothing else observes the substitution.
        private NamedTypeSymbol corelibTypeFor(TypeSymbol type) {
            if (!(type instanceof BuiltinType builtin)) {
                return null;
            }
            String name = CORELIB_TYPE_NAMES.get(builtin);
            if (name == null) {
                return null;
            }
            List<Symbol> systemSyms = model.globalScope().lookup("System");
            if (systemSyms.isEmpty() || !(systemSyms.get(0) instanceof NamespaceSymbol systemNs)) {
                return null;
            }
            Optional<Scope> systemScope = model.scopeOf(systemNs);
            if (systemScope.isEmpty()) {
                return null;
            }
            List<Symbol> found = systemScope.get().lookupLocal(name);
            return found.isEmpty() || !(found.get(0) instanceof NamedTypeSymbol named)
                    ? null
                    : named;
        }

        /// Resolves a static member written through a Java type expression - fully qualified as
        /// `java.lang.Math.Abs(-5)` and `java.lang.Integer.MAX_VALUE`, or as the simple name an
        /// import makes available, `Arrays.ToString(...)` under `using java.util;` - which no
        /// scope can bind because neither `java` nor an imported Java type is a declared name.
        /// The normal declaration scope remains authoritative whenever the first segment exists,
        /// so a V# declaration named `java` or `Arrays` cannot be shadowed by the module path.
        ///
        /// Static functions are returned as a group over a type receiver, the same shape a
        /// curated corelib static uses, so overload resolution and lowering need no Java-only
        /// arm. Fields bind to their value directly.
        private BoundExpression bindQualifiedJavaStaticMember(
                ExpressionSyntax.MemberAccess member, Scope scope) {
            if (member.nullConditional()) {
                return null;
            }
            String ownerName = qualifiedExpressionName(member.receiver());
            if (ownerName == null || !lookupInScope(scope, firstSegment(ownerName)).isEmpty()) {
                return null;
            }
            JavaOwner resolution = resolveJavaOwner(ownerName, member.receiver().span());
            if (resolution.ambiguous()) {
                return new BoundExpression.Error(member.span());
            }
            NamedTypeSymbol owner = resolution.type();
            if (owner == null) {
                return reportUnknownTypeInKnownNamespace(member, ownerName);
            }
            // A receiver written as a package path is the expression-position form of the same
            // thing [DeclarationBinder#reportQualifiedTypeName] refuses, and it uses the same
            // exact test: the receiver is qualified when what the source wrote *is* the
            // type's own qualified name. `java.lang.Integer` spells it and is refused, while
            // `HttpResponse.BodyHandlers` does not - that reaches a nested type through an
            // imported simple name, which is the shape this rule exists to encourage.
            String qualified = owner.qualifiedName().replace('$', '.');
            if (ownerName.equals(qualified)) {
                int lastDot = qualified.lastIndexOf('.');
                diagnostics.report(DiagnosticCode.QUALIFIED_TYPE_NAME, file,
                        member.receiver().span(), qualified.substring(lastDot + 1),
                        qualified.substring(0, lastDot), qualified.substring(lastDot + 1),
                        qualified);
                return new BoundExpression.Error(member.span());
            }
            List<Symbol> members = model.javaInterop().getMembers(owner);
            BoundExpression bound = bindJavaStaticMember(member, owner, members, member.name());
            if (bound != null) {
                if (reportJavaCasing(member.span(), member.name(),
                        name -> members.stream().noneMatch(symbol -> symbol.name().equals(name)))) {
                    return new BoundExpression.Error(member.span());
                }
                return bound;
            }
            String jvmName = JavaInterop.jvmMemberName(member.name());
            BoundExpression translated = jvmName == null ? null
                    : bindJavaStaticMember(member, owner, members, jvmName);
            if (translated != null) {
                return translated;
            }
            // The receiver named a Java type, so the failure is a missing member of that type,
            // not a missing name. Falling through would let ordinary name resolution report
            // `VS0103` against the first segment - `java` in `java.lang.Integer.MaxValue` - which
            // names something the author never asked about.
            diagnostics.report(DiagnosticCode.MEMBER_NOT_FOUND, file, member.span(),
                    owner.qualifiedName(), member.name());
            return new BoundExpression.Error(member.span());
        }

        /// Reports CS0234 when a qualified receiver's *namespace* resolves but the type segment
        /// after it does not.
        ///
        /// `java.lang.Nope.Thing` used to fall through to ordinary name resolution and report
        /// VS0103 against `java` - a segment the author never asked about, and one that exists.
        /// This is the same correction the design made for a missing *member* of a resolved type, one
        /// segment earlier: when `java.lang` is a package the image really has, the failure is
        /// `Nope`, and saying so names both the segment and where it was looked for.
        ///
        /// Only a Java package counts, because that is the namespace kind a receiver expression
        /// can carry here; anything else still falls through, so a V# name keeps the ordinary
        /// diagnostic and no shape loses one.
        private BoundExpression reportUnknownTypeInKnownNamespace(
                ExpressionSyntax.MemberAccess member, String ownerName) {
            int lastDot = ownerName.lastIndexOf('.');
            if (lastDot <= 0) {
                return null;
            }
            String namespaceName = ownerName.substring(0, lastDot);
            if (!model.javaInterop().isKnownNamespace(namespaceName)) {
                return null;
            }
            String missing = ownerName.substring(lastDot + 1);
            diagnostics.report(DiagnosticCode.TYPE_NOT_IN_NAMESPACE, file,
                    member.receiver().span(), missing, namespaceName);
            return new BoundExpression.Error(member.span());
        }

        /// The outcome of naming a Java type for a static-member receiver: the type when
        /// exactly one answered, and whether an ambiguity was already reported so the caller
        /// stops instead of letting ordinary name resolution add a second, misleading
        /// diagnostic for the same span.
        private record JavaOwner(NamedTypeSymbol type, boolean ambiguous) {
            static final JavaOwner NONE = new JavaOwner(null, false);
            static final JavaOwner AMBIGUOUS = new JavaOwner(null, true);
        }

        /// The Java type a static-member receiver names: the spelling as written, or - when the
        /// module path has no such type - the one an imported package supplies.
        ///
        /// Imports form one unordered set, so two packages answering the same simple name is an
        /// ambiguity rather than a race won by `using` order; the written spelling is tried
        /// first and is never ambiguous, because it names exactly one class.
        private JavaOwner resolveJavaOwner(String ownerName, SourceSpan span) {
            // The same substitution declaration binding applies: `Req.NewBuilder(...)` is
            // an expression, so the alias has to be honoured on this path too, or a name would
            // denote a type in a declaration and nothing in a call.
            String aliased = model.scopedAliasTarget(file, currentNamespace, ownerName);
            if (aliased == null) {
                aliased = model.aliasTarget(file, ownerName);
            }
            if (aliased != null) {
                ownerName = aliased;
            }
            NamedTypeSymbol written = model.javaInterop().resolveType(ownerName);
            if (written != null) {
                return new JavaOwner(written, false);
            }
            NamedTypeSymbol found = null;
            List<String> visible = new ArrayList<>(model.scopedImports(file, currentNamespace));
            for (String imported : model.importedNamespaces(file)) {
                if (!visible.contains(imported)) {
                    visible.add(imported);
                }
            }
            for (String imported : visible) {
                NamedTypeSymbol candidate =
                        model.javaInterop().resolveType(imported + "." + ownerName);
                if (candidate == null || candidate == found) {
                    continue;
                }
                if (found != null) {
                    diagnostics.report(DiagnosticCode.AMBIGUOUS_REFERENCE, file, span,
                            ownerName, found.qualifiedName(), candidate.qualifiedName());
                    return JavaOwner.AMBIGUOUS;
                }
                found = candidate;
            }
            return found == null ? JavaOwner.NONE : new JavaOwner(found, false);
        }

        /// Refuses a JDK member written in the JVM's own camelCase, so PascalCase is the one
        /// spelling V# source ever uses. Reports and answers `true` when the access is
        /// rejected.
        ///
        /// The rule is suspended for the single shape it cannot serve: a type declaring both
        /// `x` and `X`, where the PascalCase spelling is already taken by a different member
        /// and the lowercase one has no other name. `pascalUnclaimed` decides that per site.
        private boolean reportJavaCasing(SourceSpan span, String writtenName,
                java.util.function.Predicate<String> pascalUnclaimed) {
            String pascalName = JavaInterop.vsharpMemberName(writtenName);
            if (pascalName == null || !pascalUnclaimed.test(pascalName)) {
                return false;
            }
            diagnostics.report(DiagnosticCode.JAVA_MEMBER_CASING, file, span,
                    writtenName, pascalName);
            return true;
        }

        private BoundExpression bindJavaStaticMember(ExpressionSyntax.MemberAccess member,
                NamedTypeSymbol owner, List<Symbol> members, String name) {
            List<FunctionSymbol> functions = members.stream()
                    .filter(FunctionSymbol.class::isInstance)
                    .map(FunctionSymbol.class::cast)
                    .filter(function -> function.name().equals(name))
                    .filter(function -> function.modifiers().contains(SyntaxKind.STATIC))
                    .toList();
            if (!functions.isEmpty()) {
                BoundExpression typeReceiver = new BoundExpression.DeclarationGroup(
                        member.receiver().span(), owner.qualifiedName(), List.of(owner));
                return new BoundExpression.FunctionGroup(member.span(), name, functions,
                        typeReceiver);
            }
            return members.stream()
                    .filter(FieldSymbol.class::isInstance)
                    .map(FieldSymbol.class::cast)
                    .filter(field -> field.name().equals(name))
                    .filter(field -> field.modifiers().contains(SyntaxKind.STATIC))
                    .findFirst()
                    .<BoundExpression>map(field -> new BoundExpression.Value(
                            member.span(), field.type(), field))
                    .orElse(null);
        }

        private static String qualifiedExpressionName(ExpressionSyntax expression) {
            return switch (expression) {
                case ExpressionSyntax.Identifier identifier
                        when identifier.typeArguments().isEmpty() -> identifier.name();
                case ExpressionSyntax.MemberAccess member
                        when !member.nullConditional() && member.typeArguments().isEmpty() -> {
                    String receiver = qualifiedExpressionName(member.receiver());
                    yield receiver == null ? null : receiver + "." + member.name();
                }
                default -> null;
            };
        }

        private static String firstSegment(String qualifiedName) {
            int separator = qualifiedName.indexOf('.');
            return separator < 0 ? qualifiedName : qualifiedName.substring(0, separator);
        }

        private BoundExpression bindElementAccess(ExpressionSyntax.ElementAccess element, Scope scope) {
            BoundExpression boundReceiver = bindExpression(element.receiver(), scope);
            List<BoundExpression> boundArgs = bindArguments(element.arguments(), scope);

            if (boundReceiver instanceof BoundExpression.Error || isRecovery(boundReceiver)
                    || boundArgs.stream().anyMatch(Binder::isRecovery)) {
                return new BoundExpression.Error(element.span());
            }

            if (element.nullConditional()) {
                return mapConditionalTail(boundReceiver, element.span(), receiver -> {
                    if (!supportsConditionalReceiver(receiver, element.span())) {
                        return new BoundExpression.Error(element.span());
                    }
                    BoundExpression placeholder = new BoundExpression.ConditionalReceiver(
                            element.receiver().span(), conditionalReceiverValueType(receiver),
                            receiver.type());
                    BoundExpression access = bindElementAccessCore(
                            element, scope, placeholder, boundArgs);
                    return finishConditionalAccess(element.span(), receiver, access);
                });
            }
            if (boundReceiver instanceof BoundExpression.NullConditional conditional) {
                return mapConditionalTail(conditional, element.span(), receiver ->
                        bindElementAccessCore(element, scope, receiver, boundArgs));
            }
            return bindElementAccessCore(element, scope, boundReceiver, boundArgs);
        }

        private BoundExpression bindElementAccessCore(ExpressionSyntax.ElementAccess element,
                Scope scope, BoundExpression boundReceiver, List<BoundExpression> boundArgs) {

            TypeSymbol receiverType = boundReceiver.type();

            if (receiverType == BuiltinType.STRING) {
                if (boundArgs.size() != 1) {
                    diagnostics.report(DiagnosticCode.WRONG_INDEX_COUNT, file, element.span(), 1);
                    return new BoundExpression.Error(element.span());
                }
                BoundExpression index = convertIndex(boundArgs.getFirst());
                if (index == null) {
                    return new BoundExpression.Error(element.span());
                }
                if (index.type() == TypeSymbol.Index.INSTANCE) {
                    return new BoundExpression.IndexAccess(element.span(), BuiltinType.CHAR, boundReceiver, index);
                } else if (index.type() == TypeSymbol.Range.INSTANCE) {
                    return new BoundExpression.SliceAccess(element.span(), BuiltinType.STRING, boundReceiver, index);
                }
                return new BoundExpression.StringElementAccess(
                        element.span(), boundReceiver, index);
            }

            if (receiverType instanceof TypeSymbol.Array arrayType) {
                int expectedRank = arrayType.ranks().isEmpty() ? 1 : arrayType.ranks().getFirst();
                if (boundArgs.size() != expectedRank) {
                    diagnostics.report(DiagnosticCode.WRONG_INDEX_COUNT, file, element.span(),
                            expectedRank);
                    return new BoundExpression.Error(element.span());
                }

                if (expectedRank == 1 && boundArgs.size() == 1) {
                    BoundExpression index = convertIndex(boundArgs.getFirst());
                    if (index != null) {
                        if (index.type() == TypeSymbol.Index.INSTANCE) {
                            return new BoundExpression.IndexAccess(element.span(), arrayType.elementType(), boundReceiver, index);
                        } else if (index.type() == TypeSymbol.Range.INSTANCE) {
                            return new BoundExpression.SliceAccess(element.span(), arrayType, boundReceiver, index);
                        }
                    }
                }

                List<BoundExpression> convertedArgs = new ArrayList<>(boundArgs.size());
                for (BoundExpression arg : boundArgs) {
                    BoundExpression index = convertIndex(arg);
                    if (index == null) {
                        return new BoundExpression.Error(element.span());
                    }
                    if (index.type() == TypeSymbol.Index.INSTANCE || index.type() == TypeSymbol.Range.INSTANCE) {
                        diagnostics.report(DiagnosticCode.CANNOT_IMPLICITLY_CONVERT, file, index.span(), displayType(index.type()), displayType(BuiltinType.INT));
                        return new BoundExpression.Error(element.span());
                    }
                    convertedArgs.add(index);
                }

                return new BoundExpression.ElementAccess(
                        element.span(), arrayType.elementType(), boundReceiver, convertedArgs);
            }
            

            // User-defined indexer support
            NamedTypeSymbol namedType = null;
            if (receiverType instanceof NamedTypeSymbol nts) {
                namedType = nts;
            } else if (receiverType instanceof TypeSymbol.Constructed constructed) {
                namedType = constructed.definition();
            }
            
            if (namedType != null) {
                Scope typeScope = model.scopeOf(namedType).orElse(null);
                if (typeScope != null) {
                    List<Symbol> found = typeScope.lookupLocal("this[]");
                    List<FunctionSymbol> candidates = found.stream()
                            .filter(FunctionSymbol.class::isInstance)
                            .map(FunctionSymbol.class::cast)
                            .toList();
                            
                    if (!candidates.isEmpty()) {
                        List<AuxiliarySyntax.Argument> argumentSyntax = element.arguments();
                        vsharp.compiler.semantics.overloads.OverloadResult result = vsharp.compiler.semantics.overloads.OverloadResolver.resolve(candidates, boundArgs, argumentSyntax);
                        if (result instanceof vsharp.compiler.semantics.overloads.OverloadResult.Success success) {
                            Candidate candidate = success.candidate();
                            FunctionSymbol method = candidate.function();
                            CallArguments arguments = callArguments(candidate, boundArgs, scope,
                                    element.span(), null);
                            return new BoundExpression.Call(element.span(), method.returnType(),
                                    boundReceiver, method, candidate.declaration(),
                                    arguments.expressions(),
                                    arguments.parameterOrdinals());
                        }
                    }
                }
            }

            if (receiverType == TypeSymbol.Error.INSTANCE) {
                return new BoundExpression.Error(element.span());
            }

            diagnostics.report(DiagnosticCode.INDEXING_NON_ARRAY, file, element.span(),
                    displayType(receiverType));
            return new BoundExpression.Error(element.span());
        }

        private BoundExpression mapConditionalTail(BoundExpression expression,
                SourceSpan resultSpan,
                Function<BoundExpression, BoundExpression> mapper) {
            if (!(expression instanceof BoundExpression.NullConditional conditional)) {
                return mapper.apply(expression);
            }
            BoundExpression access = conditional.access();
            BoundExpression mapped = access instanceof BoundExpression.NullConditional
                    ? mapConditionalTail(access, resultSpan, mapper)
                    : mapper.apply(access);
            if (mapped instanceof BoundExpression.Error) {
                return mapped;
            }
            return finishConditionalAccess(
                    resultSpan, conditional.receiver(), mapped);
        }

        private BoundExpression finishConditionalAccess(SourceSpan span,
                BoundExpression receiver, BoundExpression access) {
            if (containsFunctionGroup(access)) {
                return new BoundExpression.NullConditional(
                        span, TypeSymbol.Error.INSTANCE, receiver, access);
            }
            if (access instanceof BoundExpression.Error || isRecovery(access)) {
                return new BoundExpression.Error(span);
            }

            TypeSymbol resultType = access.type();
            if (resultType != BuiltinType.VOID && resultType.isValueType()
                    && !(resultType instanceof TypeSymbol.Nullable)) {
                TypeSymbol.Nullable liftedType = new TypeSymbol.Nullable(resultType);
                Conversion conversion = Conversions.classify(access.type(), liftedType);
                if (!conversion.isImplicit()) {
                    throw new IllegalStateException("Conditional access could not lift "
                            + displayType(access.type()) + " to " + displayType(liftedType));
                }
                access = new BoundExpression.Conversion(
                        access.span(), access, liftedType, conversion);
                resultType = liftedType;
            }
            return new BoundExpression.NullConditional(span, resultType, receiver, access);
        }

        private static boolean containsFunctionGroup(BoundExpression expression) {
            if (expression instanceof BoundExpression.FunctionGroup) {
                return true;
            }
            return expression instanceof BoundExpression.NullConditional conditional
                    && containsFunctionGroup(conditional.access());
        }

        private boolean supportsConditionalReceiver(BoundExpression receiver, SourceSpan span) {
            TypeSymbol type = receiver.type();
            if (type instanceof TypeSymbol.Nullable) {
                return true;
            }
            if (type.isValueType()) {
                diagnostics.report(DiagnosticCode.UNARY_OPERATOR_NOT_APPLICABLE, file, span,
                        "?", displayType(type));
                return false;
            }
            return true;
        }

        /// The type a [BoundExpression.ConditionalReceiver] presents to the access subtree:
        /// a nullable receiver exposes its underlying value type (C# resolves `x?.M()` against
        /// `T`, not `T?`), while any other receiver exposes its own type.
        private static TypeSymbol conditionalReceiverValueType(BoundExpression receiver) {
            return receiver.type() instanceof TypeSymbol.Nullable nullable
                    ? nullable.element() : receiver.type();
        }

        private BoundExpression convertIndex(BoundExpression argument) {
            if (argument.type() == TypeSymbol.Index.INSTANCE || argument.type() == TypeSymbol.Range.INSTANCE) {
                return argument;
            }
            Conversion conversion = Conversions.classify(argument.type(), BuiltinType.INT);
            if (!conversion.isImplicit()) {
                diagnostics.report(DiagnosticCode.CANNOT_IMPLICITLY_CONVERT, file,
                        argument.span(), displayType(argument.type()), displayType(BuiltinType.INT));
                return null;
            }
            if (conversion.kind() == ConversionKind.IDENTITY) {
                return argument;
            }
            return new BoundExpression.Conversion(
                    argument.span(), argument, BuiltinType.INT, conversion);
        }

        private static String displayType(TypeSymbol type) {
            if (type == null) {
                return "<error>";
            }
            return type.displayName();
        }

        private BoundExpression bindBinary(ExpressionSyntax.Binary binary, Scope scope) {
            BoundExpression left = bindExpression(binary.left(), scope);
            BoundExpression right = bindExpression(binary.right(), scope);
            if (!isShift(binary.operator())) {
                // A shift is excluded because its count is an `int` in every predefined
                // overload; the other operators take their two operands in one type.
                right = unsignedConstantOperand(right, left.type());
                left = unsignedConstantOperand(left, right.type());
                if (binary.operator() == SyntaxKind.EQUALS_EQUALS
                        || binary.operator() == SyntaxKind.EXCLAMATION_EQUALS) {
                    right = enumConstantOperand(right, left.type());
                    left = enumConstantOperand(left, right.type());
                }
            }
            BoundExpression valueEquality = recordValueEquality(binary, left, right);
            if (valueEquality != null) {
                return valueEquality;
            }
            TypeSymbol result = CoreOperators.binaryResult(binary.operator(), left.type(),
                    right.type());
            if (result == TypeSymbol.Error.INSTANCE) {
                if (!isRecovery(left) && !isRecovery(right)) {
                    reportInvalidBinary(binary.operator(), binary, left, right);
                }
                return new BoundExpression.Error(binary.span());
            }
            TypeSymbol operandType = CoreOperators.binaryOperandType(binary.operator(),
                    left.type(), right.type());
            BoundExpression bound = new BoundExpression.Binary(binary.span(), result,
                    promoteOperand(left, operandType), binary.operator(),
                    promoteOperand(right, operandType));
            reportConstantFailure(bound, binary.span());
            return bound;
        }

        /// How many `unchecked` regions enclose the expression being bound.
        private int uncheckedDepth;

        /// Reports CS0220 when a constant expression overflows, and CS0020 when it divides by a
        /// constant zero (the design, completed by that rule).
        ///
        /// C# evaluates a constant expression in a *checked* context by default (§12.23), so
        /// `int.MaxValue + 1` is a compile-time error wherever it is written, not only inside
        /// `checked(...)`; `unchecked(...)` is the one way to ask for the wrapped value. V#
        /// folded every one of these silently, which is a wrong answer rather than a missing
        /// feature: the program kept a value C# refuses to produce.
        ///
        /// The evaluator already had the whole rule - exact arithmetic under `isChecked`, and
        /// `ArithmeticException` mapped to `COMPILE_TIME_OVERFLOW` - and only the callers were
        /// wrong, so this reports through it rather than repeating the arithmetic. A shape the
        /// evaluator cannot fold yet answers `INTERNAL_ERROR` and is passed over in silence,
        /// exactly as the optional-default check already treats it: not every unfoldable
        /// expression is an invalid one.
        ///
        /// Two codes come out of it, and they differ in exactly one way. Overflow is a
        /// *checked-context* error, so `unchecked(int.MaxValue + 1)` is the wrapped value and
        /// no diagnostic. Division by a constant zero is not: C# §12.23 makes `1 / 0` CS0020
        /// wherever it is written, because there is no value for `unchecked` to produce.
        /// Unary operands reach this too - `-int.MinValue` overflows, and routing only binary
        /// operators through the gate left it folding to itself in silence.
        private void reportConstantFailure(BoundExpression bound, SourceSpan span) {
            ConstantEvaluator.Result result = ConstantEvaluator.evaluate(bound, true);
            if (!(result instanceof ConstantEvaluator.Result.Failure failure)) {
                return;
            }
            if (failure.code() == DiagnosticCode.DIVISION_BY_ZERO) {
                diagnostics.report(DiagnosticCode.DIVISION_BY_ZERO, file, span);
                return;
            }
            if (failure.code() == DiagnosticCode.COMPILE_TIME_OVERFLOW && uncheckedDepth == 0) {
                diagnostics.report(DiagnosticCode.COMPILE_TIME_OVERFLOW, file, span);
            }
        }

        /// Rewrites `==`/`!=` between two values of one `record struct` into the componentwise
        /// answer C# defines.
        ///
        /// The operands are *values*, so reference comparison is not a weaker answer, it is the
        /// wrong one: two `Point(1, 2)` are equal in C# and were quietly `false` here, because
        /// the emitted class is an ordinary JVM class and `if_acmpeq` compares its identity.
        /// Only a matching pair rewrites; a record struct compared with `null` or with anything
        /// else falls through to the ordinary operator rules and their diagnostics.
        private BoundExpression recordValueEquality(ExpressionSyntax.Binary binary,
                BoundExpression left, BoundExpression right) {
            if (binary.operator() != SyntaxKind.EQUALS_EQUALS
                    && binary.operator() != SyntaxKind.EXCLAMATION_EQUALS) {
                return null;
            }
            if (!(left.type() instanceof NamedTypeSymbol named) || !named.equals(right.type())
                    || model.positionalLayout(named).isEmpty()) {
                return null;
            }
            FunctionSymbol equals = model.scopeOf(named)
                    .map(scope -> scope.lookupLocal("Equals"))
                    .orElse(List.of()).stream()
                    .filter(FunctionSymbol.class::isInstance)
                    .map(FunctionSymbol.class::cast)
                    .filter(function -> function.parameters().size() == 1)
                    .findFirst()
                    .orElse(null);
            if (equals == null) {
                return null;
            }
            BoundExpression call = new BoundExpression.Call(binary.span(), BuiltinType.BOOL,
                    left, equals, equals, List.of(right), List.of(0));
            return binary.operator() == SyntaxKind.EQUALS_EQUALS
                    ? call
                    : new BoundExpression.Unary(binary.span(), BuiltinType.BOOL,
                            SyntaxKind.EXCLAMATION, call);
        }

        /// Applies the constant conversion where an unsigned operand meets a constant of a
        /// signed type. This is the case C# resolves through the predefined operator set and
        /// the type-based promotion table alone cannot: `v + 1` on a `uint` is `uint` in C#,
        /// through operator +(uint,uint), while promoting the pair by type would widen both
        /// to `long` - a different result type, and a `return v + 1;` that stops compiling.
        private static BoundExpression unsignedConstantOperand(BoundExpression operand,
                TypeSymbol other) {
            if (!(other instanceof BuiltinType builtin)) {
                return operand;
            }
            return switch (builtin) {
                case UINT, ULONG, NUINT -> constantOperand(operand, other);
                default -> operand;
            };
        }

        /// `e == 0` and `0 == e` are valid C# through the implicit constant-zero-to-enum
        /// conversion (§19.6.3), while `e == 1` is not. The type-based equality rule cannot
        /// see the constant, so this retypes a constant zero to the enum operand before
        /// `binaryResult` runs, exactly like the unsigned constant rule above.
        private static BoundExpression enumConstantOperand(BoundExpression operand,
                TypeSymbol other) {
            if (!(other instanceof NamedTypeSymbol named)
                    || named.declaredKind() != NamedTypeSymbol.DeclaredKind.ENUM) {
                return operand;
            }
            return constantOperand(operand, other);
        }

        private static boolean isShift(SyntaxKind operator) {
            return operator == SyntaxKind.LESS_THAN_LESS_THAN
                    || operator == SyntaxKind.GREATER_THAN_GREATER_THAN
                    || operator == SyntaxKind.GREATER_THAN_GREATER_THAN_GREATER_THAN;
        }

        /// Retypes a constant operand to the other operand's type when C# would (§12.4.5:
        /// the predefined operator set is searched after each operand's implicit conversions,
        /// and a constant expression converts implicitly to any type whose range holds it).
        /// Without this step `ul / 3` finds no operator at all, because binary numeric
        /// promotion refuses to mix `ulong` with the *signed* `int` the literal carries.
        /// Only a constant is ever retyped, and only where the operator already failed, so
        /// no successful operator changes shape here.
        private static BoundExpression constantOperand(BoundExpression operand,
                TypeSymbol target) {
            if (target == null || operand.type() == target) {
                return operand;
            }
            Conversion conversion = Conversions.classify(operand, target);
            if (conversion.kind() != ConversionKind.IMPLICIT_CONSTANT) {
                return operand;
            }
            return new BoundExpression.Conversion(operand.span(), operand, target, conversion);
        }

        /// Converts one operand of a binary operator to the operator's common operand type.
        /// The conversion is always implicit - `binaryOperandType` derives the target from
        /// both operand types precisely so that each one reaches it - so a non-implicit
        /// result is a defect in the promotion table, not a user error, and must not be
        /// silently dropped into wrong arithmetic.
        private static BoundExpression promoteOperand(BoundExpression operand,
                TypeSymbol operandType) {
            if (operandType == null || operand.type() == operandType) {
                return operand;
            }
            Conversion conversion = Conversions.classify(operand.type(), operandType);
            if (conversion.kind() == ConversionKind.IDENTITY) {
                return operand;
            }
            if (!conversion.isImplicit()) {
                throw new IllegalStateException("Binary numeric promotion produced a "
                        + "non-implicit conversion from " + displayType(operand.type()) + " to "
                        + displayType(operandType));
            }
            return new BoundExpression.Conversion(operand.span(), operand, operandType,
                    conversion);
        }

        private BoundExpression bindAssignment(ExpressionSyntax.Assignment assignment,
                Scope scope) {
            BoundExpression target = bindExpression(assignment.target(), scope);
            // A simple assignment is a target-typed context, exactly as a declaration with an
            // initializer is: `value = default` means `default(T)` for the target's `T`, and
            // without the target the expression has no type at all. Binding the value blind
            // left `default` as an error-typed node that reached lowering and stopped the
            // compiler with an internal error on valid C#. A *compound* assignment is
            // not target-typed - C# computes the binary result first - so it keeps the plain
            // binding it had.
            BoundExpression value = assignment.operator() == SyntaxKind.EQUALS
                    && !isRecovery(target) && target.type() != TypeSymbol.Error.INSTANCE
                    ? bindExpression(assignment.value(), scope, target.type())
                    : bindExpression(assignment.value(), scope);
            if (!isRecovery(target) && !isWritable(target)) {
                diagnostics.report(DiagnosticCode.ASSIGNMENT_TARGET_REQUIRED, file,
                        assignment.target().span());
                return new BoundExpression.Assignment(assignment.span(), TypeSymbol.Error.INSTANCE,
                        target, assignment.operator(), value);
            }
            if (assignment.operator() == SyntaxKind.EQUALS
                    && target instanceof BoundExpression.Tuple tupleTarget
                    && !(value.type() instanceof TypeSymbol.Tuple)) {
                // `(a, b) = p;` where `p` is a positional `record struct`: the value is not
                // convertible to the target tuple as a whole - it is taken apart, and each
                // component must convert to the target at its position. A tuple value
                // keeps the ordinary whole-value conversion rule below.
                List<TypeSymbol> deconstructed = deconstructionElementTypes(value.type(),
                        tupleTarget.elements().size());
                if (deconstructed != null) {
                    boolean converts = true;
                    for (int index = 0; index < deconstructed.size(); index++) {
                        TypeSymbol elementTarget = tupleTarget.elements().get(index).type();
                        if (!Conversions.classify(deconstructed.get(index), elementTarget)
                                .isImplicit()) {
                            diagnostics.report(DiagnosticCode.CANNOT_IMPLICITLY_CONVERT, file,
                                    assignment.value().span(),
                                    deconstructed.get(index).displayName(),
                                    elementTarget.displayName());
                            converts = false;
                        }
                    }
                    return new BoundExpression.Assignment(assignment.span(),
                            converts ? target.type() : TypeSymbol.Error.INSTANCE, target,
                            assignment.operator(), value);
                }
            }
            if (assignment.operator() == SyntaxKind.EQUALS) {
                if (!requireImplicitConversion(value, target.type(), assignment.value())) {
                    return new BoundExpression.Assignment(assignment.span(),
                            TypeSymbol.Error.INSTANCE, target, assignment.operator(), value);
                }
                return new BoundExpression.Assignment(assignment.span(), target.type(), target,
                        assignment.operator(), value);
            }
            SyntaxKind binaryOperator = compoundOperator(assignment.operator());
            TypeSymbol result = binaryOperator == null ? TypeSymbol.Error.INSTANCE
                    : CoreOperators.binaryResult(binaryOperator, target.type(), value.type());
            if (result == TypeSymbol.Error.INSTANCE && binaryOperator != null
                    && !isShift(binaryOperator)) {
                BoundExpression retyped = constantOperand(value, target.type());
                if (retyped != value) {
                    value = retyped;
                    result = CoreOperators.binaryResult(binaryOperator, target.type(),
                            value.type());
                }
            }
            if (result == TypeSymbol.Error.INSTANCE) {
                if (!isRecovery(target) && !isRecovery(value)) {
                    reportInvalidBinary(binaryOperator == null ? assignment.operator()
                            : binaryOperator, assignment, target, value);
                }
                return new BoundExpression.Assignment(assignment.span(), TypeSymbol.Error.INSTANCE,
                        target, assignment.operator(), value);
            }
            return new BoundExpression.Assignment(assignment.span(), target.type(), target,
                    assignment.operator(), value);
        }

        private BoundExpression bindConditional(ExpressionSyntax.Conditional conditional,
                Scope scope) {
            return bindConditional(conditional, scope, null);
        }

        private BoundExpression bindConditional(ExpressionSyntax.Conditional conditional,
                Scope scope, TypeSymbol targetType) {
            BoundExpression condition = bindExpression(conditional.condition(), scope);
            requireBoolean(condition, conditional.condition());
            BoundExpression whenTrue = bindExpression(conditional.whenTrue(), scope);
            BoundExpression whenFalse = bindExpression(conditional.whenFalse(), scope);
            TypeSymbol type = CoreOperators.bestCommonType(whenTrue.type(), whenFalse.type());
            if (type == TypeSymbol.Error.INSTANCE
                    && targetTypes(targetType, whenTrue, whenFalse)) {
                type = targetType;
            }
            if (type == TypeSymbol.Error.INSTANCE && !isRecovery(whenTrue)
                    && !isRecovery(whenFalse)) {
                diagnostics.report(DiagnosticCode.CONDITIONAL_TYPE_UNDETERMINED, file,
                        conditional.span(), displayType(whenTrue), displayType(whenFalse));
            }
            // Both arms are converted to the conditional's own type, per C# §12.18.
            // The best common type is not automatically the type of either arm: in
            // `count == 0 ? 0 : total / count` the arms are `int` and `long`, so the `int`
            // one needs a widening conversion. Leaving it out produced a branch that pushed
            // one stack slot where the other pushed two, which the class-file writer rejected
            // as a stack-size mismatch - a compiler defect, not a source error.
            return new BoundExpression.Conditional(conditional.span(), type, condition,
                    convertArm(whenTrue, type), convertArm(whenFalse, type));
        }

        /// Converts one arm of a conditional or switch expression to the result type the two
        /// arms agreed on.
        ///
        /// Unlike binary numeric promotion this cannot assume the conversion exists: the best
        /// common type of two *reference* arms is whichever one the other converts to, and a
        /// recovery arm has no type at all. An arm that does not convert implicitly is left
        /// alone, because the diagnostic for that was already reported where the common type
        /// could not be determined, and wrapping it would only hide the recovery.
        private static BoundExpression convertArm(BoundExpression arm, TypeSymbol type) {
            if (type == TypeSymbol.Error.INSTANCE || arm.type() == type
                    || arm.type() == TypeSymbol.Error.INSTANCE) {
                return arm;
            }
            Conversion conversion = Conversions.classify(arm.type(), type);
            if (!conversion.isImplicit() || conversion.kind() == ConversionKind.IDENTITY) {
                return arm;
            }
            return new BoundExpression.Conversion(arm.span(), arm, type, conversion);
        }

        private BoundExpression bindDeferredSwitch(ExpressionSyntax.Switch switched,
                Scope scope) {
            return bindDeferredSwitch(switched, scope, null);
        }

        /// Whether `target` can type an expression whose arms share no common type.
        ///
        /// C# 9 target-types a conditional and a switch expression: when the arms have no
        /// common type of their own, the type the result is being converted to supplies one,
        /// provided every arm converts to it. That is the whole rule, and it is what makes
        /// `keep ? value : null` mean `default(int?)` on one side and a lifted `value` on the
        /// other - the shape a nullable is most naturally produced by, and one V# refused
        /// outright while C# compiled it. A recovery arm is ignored rather than
        /// counted as a failure, so a broken arm reports its own diagnostic instead of this
        /// one.
        private boolean targetTypes(TypeSymbol target, BoundExpression... arms) {
            if (target == null || target == TypeSymbol.Error.INSTANCE
                    || target == TypeSymbol.Inferred.INSTANCE || arms.length == 0) {
                return false;
            }
            for (BoundExpression arm : arms) {
                if (isRecovery(arm)) {
                    return false;
                }
                if (!Conversions.classify(arm, target).isImplicit()) {
                    return false;
                }
            }
            return true;
        }

        private BoundExpression bindDeferredSwitch(ExpressionSyntax.Switch switched,
                Scope scope, TypeSymbol targetType) {
            List<BoundExpression> children = new ArrayList<>();
            BoundExpression governing = bindExpression(switched.governing(), scope);
            children.add(governing);
            // Which children are arm *values* rather than the governing expression or a
            // guard, and the syntax each came from, so both the deferred node and the
            // binding can carry the conversion once the result type is known.
            List<Integer> valuePositions = new ArrayList<>();
            List<ExpressionSyntax> valueSyntax = new ArrayList<>();
            TypeSymbol result = null;
            for (AuxiliarySyntax.SwitchExpressionArm arm : switched.arms()) {
                Scope armScope = model.scopeFor(arm).orElse(scope);
                bindTestPattern(arm.pattern(), armScope, children.get(0).type());
                if (arm.guard() != null) {
                    BoundExpression guard = bindExpression(arm.guard(), armScope);
                    requireBoolean(guard, arm.guard());
                    children.add(guard);
                }
                BoundExpression value = bindExpression(arm.expression(), armScope);
                valuePositions.add(children.size());
                valueSyntax.add(arm.expression());
                children.add(value);
                result = result == null ? value.type()
                        : CoreOperators.bestCommonType(result, value.type());
            }
            if (result == null) {
                result = TypeSymbol.Error.INSTANCE;
            }
            if (result == TypeSymbol.Error.INSTANCE && targetTypes(targetType,
                    valuePositions.stream().map(children::get).toArray(BoundExpression[]::new))) {
                result = targetType;
            }
            if (result == TypeSymbol.Error.INSTANCE) {
                diagnostics.report(DiagnosticCode.CONDITIONAL_TYPE_UNDETERMINED, file,
                        switched.span(), "switch arms", "switch arms");
            }
            // Every arm produces the switch's own type, exactly as a conditional's two arms
            // do: `0 => 0, 1 => big` over a `long` needs the `int` arm widened, or the arms
            // leave different stack heights at the join.
            //
            // The binding is rewritten as well as the deferred node, because a switch
            // expression is lowered from its *syntax* - the lowerer walks the arms and asks
            // for each arm expression's bound form, so a conversion recorded only in the
            // deferred node's children would never be emitted.
            for (int index = 0; index < valuePositions.size(); index++) {
                int position = valuePositions.get(index);
                BoundExpression converted = convertArm(children.get(position), result);
                children.set(position, converted);
                expressions.put(valueSyntax.get(index), converted);
            }
            return new BoundExpression.Deferred(switched.span(), result, "switch expression",
                    children);
        }

        private BoundExpression bindRange(ExpressionSyntax.Range range, Scope scope) {
            List<BoundExpression> children = new ArrayList<>();
            if (range.start() != null) {
                children.add(bindExpression(range.start(), scope));
            }
            if (range.end() != null) {
                children.add(bindExpression(range.end(), scope));
            }
            return new BoundExpression.Deferred(range.span(), TypeSymbol.Range.INSTANCE, "range",
                    children);
        }

        private BoundExpression bindInterpolated(ExpressionSyntax.Interpolated interpolated,
                Scope scope) {
            List<BoundExpression> children = new ArrayList<>();
            for (AuxiliarySyntax.InterpolationElement element : interpolated.elements()) {
                if (element instanceof AuxiliarySyntax.InterpolationElement.Hole hole) {
                    children.add(bindExpression(hole.expression(), scope));
                    if (hole.alignment() != null) {
                        BoundExpression alignment = bindExpression(hole.alignment(), scope,
                                BuiltinType.INT);
                        children.add(alignment);
                        requireImplicitConversion(alignment, BuiltinType.INT, hole.alignment());
                        // C# requires a constant alignment (CS0150), and so does V#: the
                        // padding is pure width arithmetic, not a formatting policy.
                        if (ConstantEvaluator.evaluate(alignment, false)
                                instanceof ConstantEvaluator.Result.Failure) {
                            diagnostics.report(DiagnosticCode.CONSTANT_VALUE_EXPECTED, file,
                                    hole.alignment().span());
                        }
                    }
                    if (hole.format() != null) {
                        checkInterpolationFormat(hole, children.getFirst());
                    }
                }
            }
            return new BoundExpression.Deferred(interpolated.span(), BuiltinType.STRING,
                    "interpolated string", children);
        }

        /// Validates an interpolation hole's format clause against the the design engine.
        ///
        /// The clause is literal text, so everything that can never work is refused here
        /// rather than at run time. Three outcomes are measured against the .NET 10 oracle:
        /// a numeric hole is validated by [vsharp.runtime.VsNumberFormat#unsupportedReason],
        /// a `string`, `bool` or `char` hole *ignores* the clause exactly as C# does because
        /// those types are not `IFormattable`, and everything else is refused. Enums are
        /// refused deliberately: their specifier grammar is a different one - `X` pads to the
        /// underlying width and `F` means flags, so `$"{status:F2}"` throws in C# - and V#
        /// does not implement it.
        private void checkInterpolationFormat(AuxiliarySyntax.InterpolationElement.Hole hole,
                BoundExpression value) {
            TypeSymbol type = value.type();
            if (type == TypeSymbol.Error.INSTANCE) {
                return;
            }
            if (type == BuiltinType.STRING || type == BuiltinType.BOOL || type == BuiltinType.CHAR) {
                // Not IFormattable in .NET, so the clause is silently ignored by C# too.
                return;
            }
            if (isFormattableNumeric(type)) {
                String reason = vsharp.runtime.VsNumberFormat.unsupportedReason(hole.format());
                if (reason != null) {
                    diagnostics.report(DiagnosticCode.NOT_YET_IMPLEMENTED, file, hole.span(),
                            "The interpolation format specifier '" + hole.format() + "', "
                            + reason);
                }
                return;
            }
            diagnostics.report(DiagnosticCode.NOT_YET_IMPLEMENTED, file, hole.span(),
                    "An interpolation format specifier on a value of type '"
                    + type.displayName() + "'");
        }

        /// The types whose `ToString(string)` this build implements.
        private static boolean isFormattableNumeric(TypeSymbol type) {
            return type == BuiltinType.SBYTE || type == BuiltinType.BYTE
                    || type == BuiltinType.SHORT || type == BuiltinType.USHORT
                    || type == BuiltinType.INT || type == BuiltinType.UINT
                    || type == BuiltinType.LONG || type == BuiltinType.ULONG
                    || type == BuiltinType.FLOAT || type == BuiltinType.DOUBLE
                    || type == BuiltinType.DECIMAL;
        }

        private BoundExpression bindArrayCreation(ExpressionSyntax.ArrayCreation array,
                Scope scope) {
            TypeSymbol element = resolveType(array.elementType(), scope);
            List<BoundExpression> children = new ArrayList<>();
            array.dimensions().forEach(dimension -> children.add(bindExpression(dimension, scope)));
            array.initializer().forEach(value -> bindArrayInitializerElement(
                    value, scope, element, children));
            int rank = Math.max(1, array.dimensions().size());
            TypeSymbol type = element == TypeSymbol.Error.INSTANCE ? TypeSymbol.Error.INSTANCE
                    : new TypeSymbol.Array(element, List.of(rank));
            return new BoundExpression.Deferred(array.span(), type,
                    BoundExpression.ARRAY_CREATION_FORM, children);
        }

        private void bindArrayInitializerElement(ExpressionSyntax syntax, Scope scope,
                TypeSymbol elementType, List<BoundExpression> children) {
            if (syntax instanceof ExpressionSyntax.ArrayInitializer nested) {
                nested.elements().forEach(element -> bindArrayInitializerElement(
                        element, scope, elementType, children));
                return;
            }
            BoundExpression value = bindExpression(syntax, scope, elementType);
            requireImplicitConversion(value, elementType, syntax);
            children.add(value);
        }

        private List<BoundExpression> bindArguments(List<AuxiliarySyntax.Argument> arguments,
                Scope scope) {
            List<BoundExpression> bound = new ArrayList<>(arguments.size());
            for (AuxiliarySyntax.Argument argument : arguments) {
                int start = pendingLambdas.size();
                BoundExpression expression = bindExpression(argument.expression(), scope);
                if (pendingLambdas.size() > start) {
                    pendingRanges.put(expression, new int[] {start, pendingLambdas.size()});
                }
                bound.add(expression);
            }
            return List.copyOf(bound);
        }

        /// A lambda waiting for a type argument its own call could not supply.
        private record PendingLambda(BoundExpression deferred, TypeSymbol openTarget) {
        }

        private static List<BoundExpression> prepend(BoundExpression first,
                List<BoundExpression> remainder) {
            List<BoundExpression> result = new ArrayList<>(remainder.size() + 1);
            result.add(first);
            result.addAll(remainder);
            return List.copyOf(result);
        }

        /// Defers a lambda until a target type gives it one.
        ///
        /// A lambda has no type of its own: its parameter types, its return type and even
        /// whether it is legal all come from the type it is converted to. Binding its body
        /// here would have to invent types for parameters that no target has named yet, and
        /// an argument lambda must survive overload resolution before its target is known.
        /// So nothing is bound now; [#materializeLambda] binds the body exactly once, against
        /// the target that won. A lambda that never reaches one is reported by [#reportUntargetedLambdas],
        /// so an unbound node can never reach lowering silently.
        private BoundExpression bindLambda(ExpressionSyntax.Lambda lambda, Scope scope) {
            BoundExpression bound = lambdaValues.get(lambda);
            if (bound != null) {
                return bound;
            }
            BoundExpression deferred = new BoundExpression.Deferred(lambda.span(),
                    TypeSymbol.Error.INSTANCE, Conversions.LAMBDA_FORM, List.of());
            lambdaSyntax.put(deferred, lambda);
            return deferred;
        }

        /// Everything overload resolution and inference may ask about an as-yet-untyped
        /// lambda: whether it converts, and the parameter types it wrote.
        ///
        /// One object rather than a method reference, because the seam now has three
        /// questions and a method reference would silently answer only the first.
        private final LambdaTargets lambdaTargets = new LambdaTargets() {
            @Override
            public boolean convertible(BoundExpression lambda, TypeSymbol target) {
                return lambdaConvertible(lambda, target);
            }

            @Override
            public List<TypeSymbol> writtenParameters(BoundExpression lambda) {
                return writtenLambdaParameters(lambda);
            }

            @Override
            public List<TypeSymbol> functionalParameters(TypeSymbol target) {
                return functionalParameterTypes(target);
            }

            @Override
            public boolean returnsVoid(TypeSymbol target) {
                return model.javaInterop().functionalMethodOf(target)
                        .map(method -> method.returnType() == BuiltinType.VOID)
                        .orElse(false);
            }
        };

        /// The types an explicitly typed lambda wrote for its parameters, or `null` when any
        /// of them was left to inference. A written type is a constraint inference can use
        /// before the body exists.
        private List<TypeSymbol> writtenLambdaParameters(BoundExpression expression) {
            ExpressionSyntax.Lambda lambda = lambdaSyntax.get(expression);
            if (lambda == null || lambda.parameters().isEmpty()) {
                return null;
            }
            Scope body = model.scopeFor(lambda).orElse(model.globalScope());
            List<TypeSymbol> written = new ArrayList<>(lambda.parameters().size());
            for (AuxiliarySyntax.Parameter parameter : lambda.parameters()) {
                if (parameter.type() == null) {
                    return null;
                }
                written.add(resolveType(parameter.type(), body));
            }
            return List.copyOf(written);
        }

        /// The parameter types of `target`'s single abstract method as `target` parameterises
        /// them - the same shape [#lambdaTargetMethod] matches a lambda against.
        private List<TypeSymbol> functionalParameterTypes(TypeSymbol target) {
            return model.javaInterop().functionalMethodOf(target)
                    .map(method -> method.parameters().stream()
                            .map(ParameterSymbol::type)
                            .toList())
                    .orElse(null);
        }

        /// The type arguments a call *wrote*, resolved, or `null` when one of them is itself
        /// an error.
        ///
        /// `Optional.Empty&lt;string&gt;()` has no argument to infer from, so the written arguments
        /// are the only thing that can close the method. They reach overload resolution rather
        /// than being substituted here, so each candidate keeps the generic *declaration* its
        /// call must emit against - a specialised signature at the call site would name a
        /// method the JVM does not have.
        private List<TypeSymbol> writtenTypeArguments(ExpressionSyntax.Invocation invocation,
                Scope scope) {
            List<TypeSyntax> written = switch (invocation.target()) {
                case ExpressionSyntax.Identifier identifier -> identifier.typeArguments();
                case ExpressionSyntax.MemberAccess member -> member.typeArguments();
                default -> List.of();
            };
            if (written.isEmpty()) {
                return List.of();
            }
            List<TypeSymbol> arguments = written.stream()
                    .map(type -> resolveType(type, scope))
                    .toList();
            return arguments.stream().anyMatch(type -> type == TypeSymbol.Error.INSTANCE)
                    ? null : arguments;
        }

        /// Reports CS0305 when no candidate declares as many type parameters as the call wrote,
        /// so a wrong count names the arity instead of falling through to "no overload".
        private boolean applicableToWrittenArity(BoundExpression.FunctionGroup group,
                List<TypeSymbol> writtenTypeArguments, ExpressionSyntax.Invocation invocation) {
            if (writtenTypeArguments.isEmpty()) {
                return true;
            }
            if (group.candidates().stream().anyMatch(candidate ->
                    candidate.typeParameters().size() == writtenTypeArguments.size())) {
                return true;
            }
            diagnostics.report(DiagnosticCode.WRONG_TYPE_ARGUMENT_COUNT, file,
                    invocation.target().span(), group.name(),
                    group.candidates().getFirst().typeParameters().size());
            return false;
        }

        /// Whether an untyped lambda can become a value of `target`: the conversion every
        /// applicability test asks about, answered without binding the body.
        private boolean lambdaConvertible(BoundExpression expression, TypeSymbol target) {
            // Overload resolution asks only whether the shape fits. A parameter still carrying
            // an open type parameter is not yet unrepresentable - the call's own target can
            // close it before the body is bound - so exactness is required at
            // materialisation, where the type must be final, and not here.
            return lambdaTargetMethod(expression, target, false) != null;
        }

        /// The abstract method `target` would give a lambda, or `null` when the conversion
        /// does not exist.
        ///
        /// The parameter shape and the *statement-expression* rule are decided here. C# also
        /// weighs the body's exact return type, which cannot be known before the body is bound
        /// and the body cannot be bound before a target is chosen; a body whose type does not
        /// fit is reported against the chosen target instead of silently removing the candidate
        /// (documented divergence).
        private FunctionSymbol lambdaTargetMethod(BoundExpression expression, TypeSymbol target,
                boolean requireExact) {
            ExpressionSyntax.Lambda lambda = lambdaSyntax.get(expression);
            if (lambda == null) {
                return null;
            }
            FunctionSymbol method = model.javaInterop().functionalMethodOf(target).orElse(null);
            if (method == null || method.parameters().size() != lambda.parameters().size()) {
                return null;
            }
            // A void-returning target accepts an expression body only when that expression is
            // a *statement* expression - C# §10.7 and JLS 15.27.2 agree, and the rule is purely
            // syntactic, so it is decidable here. Without it `pool.Submit(() =&gt; "done")`
            // is ambiguous between `Callable<T>` and `Runnable`, which is how the JDK's own
            // overloads are meant to be separated.
            if (method.returnType() == BuiltinType.VOID
                    && lambda.body() instanceof ExpressionSyntax body
                    && !isStatementExpression(body)) {
                return null;
            }
            Scope body = model.scopeFor(lambda).orElse(model.globalScope());
            for (int index = 0; index < lambda.parameters().size(); index++) {
                AuxiliarySyntax.Parameter parameter = lambda.parameters().get(index);
                // A `ref`/`out`/`params` lambda parameter has no functional-interface shape to
                // land on, so it is not convertible rather than approximated.
                if (!parameter.modifiers().isEmpty() || parameter.defaultValue() != null) {
                    return null;
                }
                TypeSymbol expected = method.parameters().get(index).type();
                if (parameter.type() != null
                        && !resolveType(parameter.type(), body).equals(expected)) {
                    return null;
                }
                // An erased or otherwise unrepresentable parameter cannot be given to a real
                // JVM method, so the conversion is refused instead of emitted wrongly.
                if (expected == TypeSymbol.Error.INSTANCE) {
                    return null;
                }
                if (requireExact && expected instanceof TypeParameterSymbol) {
                    return null;
                }
            }
            return method;
        }

        /// Whether an expression may stand alone as a statement, which is what a void-returning
        /// functional interface requires of an expression-bodied lambda. C# names exactly these
        /// forms (§12.7): invocation, object creation, assignment including its compound forms,
        /// and pre/post increment and decrement. A conditional whose arms are themselves
        /// statement expressions is not one - C# does not admit it either.
        private static boolean isStatementExpression(ExpressionSyntax expression) {
            return switch (expression) {
                case ExpressionSyntax.Invocation ignored -> true;
                case ExpressionSyntax.ObjectCreation ignored -> true;
                case ExpressionSyntax.Assignment ignored -> true;
                case ExpressionSyntax.Postfix postfix ->
                        postfix.operator() == SyntaxKind.PLUS_PLUS
                                || postfix.operator() == SyntaxKind.MINUS_MINUS;
                case ExpressionSyntax.Unary unary ->
                        unary.operator() == SyntaxKind.PLUS_PLUS
                                || unary.operator() == SyntaxKind.MINUS_MINUS;
                default -> false;
            };
        }

        /// Binds a deferred lambda against the target type that finally typed it, once.
        private BoundExpression materializeLambda(BoundExpression deferred, TypeSymbol targetType) {
            ExpressionSyntax.Lambda lambda = lambdaSyntax.get(deferred);
            FunctionSymbol method = lambdaTargetMethod(deferred, targetType, true);
            if (lambda == null || method == null) {
                return deferred;
            }
            BoundExpression existing = lambdaValues.get(lambda);
            if (existing != null) {
                return existing;
            }
            BoundExpression result = bindLambdaBody(lambda, targetType, method);
            lambdaValues.put(lambda, result);
            expressions.put(lambda, result);
            bindingOrder.add(result);
            return result;
        }

        /// Binds the body of a lambda whose target type is now known.
        ///
        /// The declaration binder created the lambda's callable and its scope before any
        /// target existed, so its parameters carry [TypeSymbol.Inferred]. They are retyped
        /// here into fresh symbols that the body's own uses resolve to through
        /// `lambdaParameters`, which is how an inferred `var` local already works: the
        /// declaration-time symbol keeps its identity in the scope, and every bound use
        /// carries the concrete type the backend needs to allocate a slot.
        private BoundExpression bindLambdaBody(ExpressionSyntax.Lambda lambda,
                TypeSymbol targetType, FunctionSymbol method) {
            FunctionSymbol declared = model.declaredSymbol(lambda)
                    .filter(FunctionSymbol.class::isInstance)
                    .map(FunctionSymbol.class::cast)
                    .orElse(null);
            if (declared == null) {
                return new BoundExpression.Error(lambda.span());
            }
            // The two C# body shapes. A statement body is bound by the ordinary
            // statement walk under the lambda's own callable, so `return` conversions, flow
            // analysis and lowering all reach it exactly as they reach a local function.
            ExpressionSyntax expressionBody = lambda.body() instanceof ExpressionSyntax written
                    ? written : null;
            StatementSyntax.Block blockBody = lambda.body() instanceof StatementSyntax.Block block
                    ? block : null;
            if (expressionBody == null && blockBody == null) {
                return new BoundExpression.Error(lambda.span());
            }
            // A lambda body is emitted as a static method of the class hosting its enclosing
            // callable, under the same `Enclosing$name` convention local functions use.
            // The name is the one [LambdaNames#bodyQualifiedName] already gave the declaration,
            // so this callable keeps it and everything the body declared stays under it.
            // Non-capturing means the method is genuinely static, so it is marked so here
            // rather than inferred from an enclosing-callable lookup that does not apply.
            String qualifiedName = declared.qualifiedName();
            // The parameters are retyped in place, keeping their qualified names: capture
            // analysis decides what a callable owns by qualified-name prefix, and the prefix
            // is unchanged.
            List<ParameterSymbol> parameters = new ArrayList<>(declared.parameters().size());
            for (int index = 0; index < declared.parameters().size(); index++) {
                ParameterSymbol original = declared.parameters().get(index);
                ParameterSymbol typed = new ParameterSymbol(original.name(),
                        qualifiedName + "." + original.name(), original.location(),
                        method.parameters().get(index).type(), original.ordinal(),
                        original.modifiers(), original.defaultValue());
                parameters.add(typed);
                lambdaParameters.put(original, typed);
            }
            FunctionSymbol function = new FunctionSymbol(declared.name(), qualifiedName,
                    declared.location(), method.returnType(), List.of(), List.copyOf(parameters),
                    List.of(SyntaxKind.STATIC), true, true, false);
            Scope body = model.scopeFor(lambda).orElse(model.globalScope());
            // An open return type is one the call is waiting on rather than one the lambda
            // must satisfy: `Stream.Map`'s `R` is whatever the body produces. The body
            // is bound with no target in that case, and its own type becomes both the
            // callable's return type and the type argument the call is finished with.
            // Open means *contains* an open parameter, not "is" one. `Map` declares `R`
            // and `FlatMap` declares `Mono<? extends R>`; both are types no conversion can check
            // until the body has said what it produces, so both must bind the body without a
            // target and let the body's own type close the call.
            boolean openReturn = hasUnresolvedTypeParameters(method.returnType(), body);
            Set<Symbol> enclosingOwn = lambdaOwnValues;
            lambdaOwnValues = Set.copyOf(parameters);
            try {
                if (blockBody != null) {
                    return bindLambdaBlock(lambda, targetType, method, function, body, blockBody,
                            openReturn);
                }
                BoundExpression bound = method.returnType() == BuiltinType.VOID || openReturn
                        ? bindExpression(expressionBody, body)
                        : bindExpression(expressionBody, body, method.returnType());
                if (method.returnType() != BuiltinType.VOID && !openReturn) {
                    requireImplicitConversion(bound, method.returnType(), expressionBody);
                }
                if (!openReturn) {
                    return new BoundExpression.Lambda(lambda.span(), targetType, function,
                            method, expressionBody, bound);
                }
                if (isRecovery(bound)) {
                    return new BoundExpression.Error(lambda.span());
                }
                // A body that produces nothing cannot close a type parameter: the call would
                // emit an implementation returning `void` behind a SAM the JVM expects to
                // return a reference, which `LambdaMetafactory` rejects at *run* time
                //. C# reports the same conversion failure at compile time.
                if (bound.type() == BuiltinType.VOID) {
                    diagnostics.report(DiagnosticCode.CANNOT_IMPLICITLY_CONVERT, file,
                            expressionBody.span(), "void",
                            displayType(method.returnType()));
                    return new BoundExpression.Error(lambda.span());
                }
                // The interface method deliberately keeps its open type parameter: it is the
                // key the call site closes its result type with, and the erased descriptor it
                // carries is already the reference the JVM expects. Only the implementation's
                // return type becomes the concrete one the body produced.
                return new BoundExpression.Lambda(lambda.span(), targetType,
                        withReturnType(function, bound.type()), method, expressionBody, bound);
            } finally {
                lambdaOwnValues = enclosingOwn;
            }
        }

        /// Binds a statement-bodied lambda's block under its own callable.
        ///
        /// The block is walked by the ordinary statement binder with `function` as the owning
        /// callable, so `return` conversions, local declarations, patterns and nested
        /// callables are handled by exactly one implementation rather than a lambda-specific
        /// copy. Whether every path returns is not decided here: [FlowAnalysis] owns end-point
        /// reachability and reports it for this callable like any other.
        ///
        /// An open return type has no target a `return` could be checked against, so the walk
        /// runs with no owning callable and the lambda's result type is the one its `return`
        /// expressions produced - the statement-body counterpart of an expression body closing
        /// the call.
        private BoundExpression bindLambdaBlock(ExpressionSyntax.Lambda lambda,
                TypeSymbol targetType, FunctionSymbol method, FunctionSymbol function,
                Scope body, StatementSyntax.Block blockBody, boolean openReturn) {
            bindBlock(blockBody, body, openReturn ? null : function);
            if (!openReturn) {
                return new BoundExpression.Lambda(lambda.span(), targetType, function, method,
                        blockBody);
            }
            List<ExpressionSyntax> returned = new ArrayList<>();
            collectReturnedExpressions(blockBody, returned);
            TypeSymbol produced = null;
            for (ExpressionSyntax expression : returned) {
                BoundExpression bound = expressions.get(expression);
                if (bound == null || isRecovery(bound) || bound.type() == BuiltinType.VOID) {
                    continue;
                }
                if (produced == null) {
                    produced = bound.type();
                } else if (!produced.equals(bound.type())) {
                    requireImplicitConversion(bound, produced, expression);
                }
            }
            if (produced == null) {
                // Same failure an expression body producing `void` has: `LambdaMetafactory`
                // would reject the implementation at run time, so it is a compile error here
                //.
                diagnostics.report(DiagnosticCode.CANNOT_IMPLICITLY_CONVERT, file,
                        lambda.span(), "void", displayType(method.returnType()));
                return new BoundExpression.Error(lambda.span());
            }
            return new BoundExpression.Lambda(lambda.span(), targetType,
                    withReturnType(function, produced), method, blockBody);
        }

        /// The expressions every `return` in a body hands back, excluding nested callables.
        ///
        /// A nested lambda or local function returns to itself, so its `return` statements say
        /// nothing about this body's result type.
        private static void collectReturnedExpressions(StatementSyntax statement,
                List<ExpressionSyntax> into) {
            switch (statement) {
                case StatementSyntax.Return returned -> {
                    if (returned.expression() != null) {
                        into.add(returned.expression());
                    }
                }
                case StatementSyntax.LocalFunction ignored -> {
                }
                case StatementSyntax.Block block -> block.statements()
                        .forEach(child -> collectReturnedExpressions(child, into));
                case StatementSyntax.If conditional -> {
                    collectReturnedExpressions(conditional.whenTrue(), into);
                    if (conditional.whenFalse() != null) {
                        collectReturnedExpressions(conditional.whenFalse(), into);
                    }
                }
                case StatementSyntax.While loop -> collectReturnedExpressions(loop.body(), into);
                case StatementSyntax.Do loop -> collectReturnedExpressions(loop.body(), into);
                case StatementSyntax.For loop -> collectReturnedExpressions(loop.body(), into);
                case StatementSyntax.Foreach loop -> collectReturnedExpressions(loop.body(), into);
                case StatementSyntax.Switch switched -> switched.sections().forEach(
                        section -> section.statements()
                                .forEach(child -> collectReturnedExpressions(child, into)));
                case StatementSyntax.Try attempt -> {
                    collectReturnedExpressions(attempt.body(), into);
                    attempt.catches().forEach(
                            clause -> collectReturnedExpressions(clause.body(), into));
                    if (attempt.finallyBody() != null) {
                        collectReturnedExpressions(attempt.finallyBody(), into);
                    }
                }
                case StatementSyntax.Using using -> collectReturnedExpressions(using.body(), into);
                case StatementSyntax.Lock locked -> collectReturnedExpressions(locked.body(), into);
                case StatementSyntax.Checked checked ->
                        collectReturnedExpressions(checked.body(), into);
                case StatementSyntax.Labeled labeled ->
                        collectReturnedExpressions(labeled.statement(), into);
                default -> {
                }
            }
        }

        /// Substitutes the type arguments a lambda body supplied into a call's result type.
        ///
        /// Each materialised lambda records the pairing implicitly: its interface method's
        /// *declared* return is the open type parameter, and the method it ended up with
        /// carries the type the body produced. Nothing else can close these, because no
        /// ordinary argument mentions them - that is exactly why inference left them open.
        private TypeSymbol closeLambdaInferences(TypeSymbol returnType,
                List<BoundExpression> arguments) {
            Map<TypeParameterSymbol, TypeSymbol> inferred = new LinkedHashMap<>();
            for (BoundExpression argument : arguments) {
                if (!(argument instanceof BoundExpression.Lambda lambda)) {
                    continue;
                }
                TypeSymbol declared = lambda.interfaceMethod().returnType();
                TypeSymbol produced = lambda.function().returnType();
                if (declared instanceof TypeParameterSymbol open) {
                    inferred.putIfAbsent(open, produced);
                    continue;
                }
                // The parameter a lambda supplies is not always the whole return type: `Map`
                // declares `R`, but `FlatMap` declares `Mono<? extends R>`, so `R` has to be
                // read *through* the target's own parameterization. Matching the declared
                // shape against what the body produced is the same structural step the design already
                // performs against a conversion target, and it is wildcard-aware, so
                // `Mono<? extends R>` against `Mono<ServerResponse>` binds `R` once.
                Map<TypeParameterSymbol, TypeSymbol> nested =
                        GenericInference.inferFromTarget(declared, produced);
                if (nested != null) {
                    nested.forEach(inferred::putIfAbsent);
                }
            }
            return inferred.isEmpty() ? returnType
                    : GenericInference.substituteType(returnType, inferred);
        }

        /// The same callable with a different return type, used to close an open functional
        /// return once the lambda body has said what it produces.
        private static FunctionSymbol withReturnType(FunctionSymbol function, TypeSymbol type) {
            return new FunctionSymbol(function.name(), function.qualifiedName(),
                    function.location(), type, function.typeParameters(), function.parameters(),
                    function.modifiers(), function.localFunction(), function.synthesized(),
                    function.interfaceOwner());
        }

        /// Reports every lambda that no target type ever claimed.
        ///
        /// Running as a sweep is deliberate: a lambda is refused where it fails to convert,
        /// but a lambda in a position that never asks for a conversion would otherwise leave
        /// an untyped node in the tree for lowering to trip over. This guarantees the file
        /// carries a diagnostic and therefore never reaches emission.
        /// Reports every call left holding a type parameter nothing ever supplied. Closure
        /// happens in `Conversions`, so an entry surviving here means the result reached a
        /// position with no target: `var c = Comparator.NaturalOrder();`. Letting it through
        /// would put an open `T` into a local's type and fault at run time.
        private void reportUntargetedOpenResults() {
            for (Map.Entry<BoundExpression, ExpressionSyntax> entry : openResults.entrySet()) {
                diagnostics.report(DiagnosticCode.CANNOT_INFER_TYPE_ARGUMENTS, file,
                        entry.getValue().span(), openResultNames.get(entry.getKey()));
            }
        }

        /// Whether `type` contains a type parameter that the V# source at `scope` did not
        /// declare. Java generic inference deliberately leaves such a parameter in a result
        /// until an argument, lambda body or conversion target supplies it. A V#
        /// callable's own `T` is different: it is already a complete source type whose JVM
        /// representation is its declared erasure, and Java inference may use it directly.
        private boolean hasUnresolvedTypeParameters(TypeSymbol type, Scope scope) {
            return switch (type) {
                case TypeParameterSymbol parameter -> !lookupInScope(scope, parameter.name())
                        .contains(parameter);
                case TypeSymbol.Array array -> hasUnresolvedTypeParameters(array.element(), scope);
                case TypeSymbol.Nullable nullable ->
                    hasUnresolvedTypeParameters(nullable.element(), scope);
                case TypeSymbol.Wildcard wildcard ->
                    hasUnresolvedTypeParameters(wildcard.bound(), scope);
                case TypeSymbol.Constructed constructed -> constructed.arguments().stream()
                        .anyMatch(argument -> hasUnresolvedTypeParameters(argument, scope));
                default -> false;
            };
        }

        private void reportUntargetedLambdas() {
            for (ExpressionSyntax.Lambda lambda : lambdaSyntax.values()) {
                if (!lambdaValues.containsKey(lambda)) {
                    diagnostics.report(DiagnosticCode.OBJECT_MODEL_UNSUPPORTED, file,
                            lambda.span(), "a lambda expression, which needs a delegate type");
                }
            }
        }

        /// Binds an `out int x` style declaration expression to the local the declaration
        /// binder already introduced into the enclosing declaration space.
        private BoundExpression bindDeclarationExpression(ExpressionSyntax.Declaration declaration,
                Scope scope) {
            TypeSymbol type = resolveType(declaration.type(), scope);
            if (model.declaredSymbol(declaration.designation())
                    .orElse(null) instanceof LocalSymbol local) {
                TypeSymbol declared = local.type() == TypeSymbol.Inferred.INSTANCE
                        ? type : local.type();
                return new BoundExpression.Value(declaration.span(), declared, local);
            }
            return new BoundExpression.Deferred(declaration.span(), type, "declaration",
                    List.of());
        }

        /// Binds a pattern in a position that *tests* a value - an `is` expression, a `switch`
        /// label, a switch-expression arm - and reports the forms this build cannot compile.
        ///
        /// Separate from `bindPattern` because `foreach` uses the same syntax to *destructure*
        /// a known value rather than to test one: `foreach ((int a, int b) in pairs)` is a
        /// recursive pattern that works, while `o is Point(1, 2)` is one that does not. Saying
        /// so here is what keeps an unimplemented pattern a diagnostic instead of an internal
        /// error later in lowering.
        private void bindTestPattern(PatternSyntax pattern, Scope scope, TypeSymbol operandType) {
            resolveTestPattern(pattern, operandType);
            bindPattern(pattern, scope);
            // A constant pattern is checked against the type it tests, but its constant is an
            // expression that only exists once `bindPattern` ran. Resolution records the pair
            // and the check is drained here, so the type-aware walk is written once.
            for (ConstantPatternCheck check : pendingConstantChecks) {
                checkConstantPattern(check.constant(), check.operandType());
            }
            pendingConstantChecks.clear();
        }

        /// A constant pattern and the type it tests, held until its constant is bound.
        private record ConstantPatternCheck(PatternSyntax.Constant constant,
                TypeSymbol operandType) {
        }

        /// Walks a testing pattern against the static type of the value it tests, resolving
        /// every recursive pattern's components and every `var` designation's inferred
        /// type, and reporting the forms this build still cannot compile.
        ///
        /// The operand type is threaded through because a subpattern tests a *component's*
        /// value rather than the outer one, and because `and` narrows: in
        /// `o is Point p and { X: 1 }` the right conjunct's components come from `Point`.
        private void resolveTestPattern(PatternSyntax pattern, TypeSymbol operandType) {
            switch (pattern) {
                case PatternSyntax.Recursive recursive ->
                        resolveRecursivePattern(recursive, operandType);
                case PatternSyntax.ListPattern list -> resolveListPattern(list, operandType);
                case PatternSyntax.Slice slice -> diagnostics.report(
                        DiagnosticCode.SLICE_PATTERN_MISPLACED, file, slice.span());
                case PatternSyntax.Binary binary -> {
                    resolveTestPattern(binary.left(), operandType);
                    resolveTestPattern(binary.right(),
                            binary.operator() == PatternSyntax.Binary.Kind.AND
                                    ? narrowedType(binary.left(), operandType)
                                    : operandType);
                }
                case PatternSyntax.Not not -> resolveTestPattern(not.pattern(), operandType);
                case PatternSyntax.Parenthesized parenthesized ->
                        resolveTestPattern(parenthesized.pattern(), operandType);
                case PatternSyntax.Var variable -> inferPatternLocal(variable, operandType);
                case PatternSyntax.Constant constant -> pendingConstantChecks.add(
                        new ConstantPatternCheck(constant, operandType));
                case PatternSyntax.Relational relational ->
                        checkRelationalPattern(relational, operandType);
                case PatternSyntax.Missing ignored -> { }
                case PatternSyntax.Discard ignored -> { }
                case PatternSyntax.Type typed -> checkTypePattern(typed, operandType);
            }
        }

        /// Checks a type pattern against a value-typed operand. A reference-carried operand
        /// (including `T?` and `object`) is a real run-time test and is left to
        /// `requireTestableType`; a value-typed one has no test to make, so C# admits only the
        /// identity shape (`int x is int n`, always true) and rejects an unrelated type with
        /// CS8121. Boxing a value operand to a reference target is legal C# that this backend
        /// does not emit, and it is reported as the build limit it is rather than reaching the
        /// emitter.
        private void checkTypePattern(PatternSyntax.Type typed, TypeSymbol operandType) {
            TypeSymbol tested = model.typeOf(typed.type()).orElse(TypeSymbol.Error.INSTANCE);
            if (tested == TypeSymbol.Error.INSTANCE || operandType == TypeSymbol.Error.INSTANCE
                    || operandType == BuiltinType.DYNAMIC
                    || operandType.jvmTypeKind() == JvmTypeKind.REFERENCE) {
                return;
            }
            if (tested == operandType) {
                return;
            }
            if (tested.jvmTypeKind() == JvmTypeKind.REFERENCE
                    && Conversions.classify(operandType, tested).exists()) {
                diagnostics.report(DiagnosticCode.NOT_YET_IMPLEMENTED, file, typed.span(),
                        "A type pattern that boxes a value-typed operand");
                return;
            }
            diagnostics.report(DiagnosticCode.PATTERN_CANNOT_HANDLE_TYPE, file, typed.span(),
                    operandType.displayName(), tested.displayName());
        }

        /// Rejects a constant pattern whose constant can never equal a value of the tested
        /// type (C# CS8121). Without this the arm is silently dead - `p is (1)` over a record
        /// struct is a *parenthesised* constant pattern, not a positional one - and for a
        /// reference-carried operand it used to reach the backend as an internal error, which
        /// a user program must never be able to cause.
        ///
        /// Compatibility is symmetric on purpose: the constant converts to the tested type
        /// (`long v is 1`), or the tested type converts to the constant's (`object o is 5`,
        /// which C# answers by unboxing). `null` is left alone: nullability is a separate
        /// rule, already reported where it applies.
        private void checkConstantPattern(PatternSyntax.Constant constant,
                TypeSymbol operandType) {
            if (model.patternType(constant).isPresent()) {
                return;
            }
            BoundExpression bound = expressions.get(constant.expression());
            if (bound == null || isRecovery(bound) || operandType == TypeSymbol.Error.INSTANCE) {
                return;
            }
            TypeSymbol constantType = bound.type();
            if (constantType == TypeSymbol.Error.INSTANCE
                    || constantType == TypeSymbol.Null.INSTANCE
                    || operandType == BuiltinType.DYNAMIC) {
                return;
            }
            if (Conversions.classify(constantType, operandType).exists()
                    || Conversions.classify(operandType, constantType).exists()) {
                return;
            }
            diagnostics.report(DiagnosticCode.PATTERN_CANNOT_HANDLE_TYPE, file, constant.span(),
                    operandType.displayName(), constantType.displayName());
        }

        /// Rejects a relational pattern over a type it cannot order. C# defines relational
        /// patterns for the numeric types, `char` and enums (CS8781 otherwise); V# additionally
        /// refuses `decimal` and nullable operands, whose carriers are references the backend
        /// compares with no opcode - saying so is the alternative to an internal error.
        private void checkRelationalPattern(PatternSyntax.Relational relational,
                TypeSymbol operandType) {
            TypeSymbol testedType = operandType instanceof TypeSymbol.Nullable nullable
                    ? nullable.element() : operandType;
            if (testedType == TypeSymbol.Error.INSTANCE) {
                return;
            }
            if (testedType instanceof NamedTypeSymbol named
                    && named.declaredKind() == NamedTypeSymbol.DeclaredKind.ENUM) {
                return;
            }
            if (testedType instanceof BuiltinType builtin) {
                switch (builtin) {
                    case SBYTE, BYTE, SHORT, USHORT, INT, UINT, LONG, ULONG, NINT, NUINT,
                            CHAR, FLOAT, DOUBLE, DECIMAL -> {
                        return;
                    }
                    default -> { }
                }
            }
            diagnostics.report(DiagnosticCode.RELATIONAL_PATTERN_UNSUPPORTED_TYPE, file,
                    relational.span(), operandType.displayName());
        }

        /// The type a value has after `pattern` matched it, which is the input type of a
        /// following `and` conjunct.
        private TypeSymbol narrowedType(PatternSyntax pattern, TypeSymbol operandType) {
            return switch (pattern) {
                case PatternSyntax.Type typed -> model.typeOf(typed.type()).orElse(operandType);
                case PatternSyntax.Recursive recursive when recursive.type() != null ->
                        model.typeOf(recursive.type()).orElse(operandType);
                case PatternSyntax.Parenthesized parenthesized ->
                        narrowedType(parenthesized.pattern(), operandType);
                case PatternSyntax.Binary binary
                        when binary.operator() == PatternSyntax.Binary.Kind.AND ->
                        narrowedType(binary.right(), narrowedType(binary.left(), operandType));
                default -> operandType;
            };
        }

        /// Resolves `is T (p, q) { Name: r } name` into a decided component list.
        ///
        /// V# reaches positional components through tuple elements only. Every other C#
        /// source of them is a `Deconstruct` instance method, which the omitted object model
        /// does not provide, so those are refused with a stable diagnostic rather than
        /// silently mis-matched. Property patterns reach a declared struct's fields, which is
        /// the same test written by name.
        private void resolveRecursivePattern(PatternSyntax.Recursive recursive,
                TypeSymbol operandType) {
            TypeSymbol tested = recursive.type() == null
                    ? operandType
                    : model.typeOf(recursive.type()).orElse(TypeSymbol.Error.INSTANCE);
            inferPatternLocal(recursive, tested);
            List<PatternComponent> components = new ArrayList<>();
            resolvePositionalComponents(recursive, tested, components);
            for (AuxiliarySyntax.PropertySubpattern property : recursive.properties()) {
                PatternComponent component = resolvePropertyComponent(property, tested);
                if (component != null) {
                    components.add(component);
                }
            }
            patternComponents.put(recursive, List.copyOf(components));
            for (PatternComponent component : components) {
                resolveTestPattern(component.pattern(), component.type());
            }
        }

        private void resolvePositionalComponents(PatternSyntax.Recursive recursive,
                TypeSymbol tested, List<PatternComponent> components) {
            List<PatternSyntax> positional = recursive.positional();
            if (positional.isEmpty() || tested == TypeSymbol.Error.INSTANCE) {
                return;
            }
            if (tested instanceof TypeSymbol.Tuple tuple) {
                if (tuple.elements().size() != positional.size()) {
                    diagnostics.report(DiagnosticCode.POSITIONAL_PATTERN_ARITY, file,
                            recursive.span(), tuple.displayName(), tuple.elements().size(),
                            positional.size());
                    return;
                }
                for (int index = 0; index < positional.size(); index++) {
                    components.add(PatternComponent.tupleItem(index,
                            tuple.elements().get(index).type(), positional.get(index)));
                }
                return;
            }
            // A positional `record struct` is the other source of positional components: its
            // declaration fixes their order, so `p is (1, var y)` reads the component fields
            // at those positions exactly as C#'s generated `Deconstruct` would.
            if (tested instanceof NamedTypeSymbol named) {
                Optional<PositionalLayout> layout = model.positionalLayout(named);
                if (layout.isPresent()) {
                    List<FieldSymbol> shape = layout.get().components();
                    if (shape.size() != positional.size()) {
                        diagnostics.report(DiagnosticCode.POSITIONAL_PATTERN_ARITY, file,
                                recursive.span(), named.displayName(), shape.size(),
                                positional.size());
                        return;
                    }
                    for (int index = 0; index < positional.size(); index++) {
                        components.add(PatternComponent.field(shape.get(index),
                                positional.get(index)));
                    }
                    return;
                }
            }
            if (addMapEntryComponents(recursive, tested, positional, components)) {
                return;
            }
            diagnostics.report(DiagnosticCode.POSITIONAL_PATTERN_REQUIRES_TUPLE, file,
                    recursive.span(), tested.displayName());
        }

        /// The one Java type V# deconstructs positionally: `java.util.Map.Entry` into its key
        /// and value.
        ///
        /// C# writes `foreach (var (key, value) in dictionary)` because `KeyValuePair`
        /// deconstructs, and the JDK's carrier for the same idea publishes the pair through two
        /// accessors instead of a `Deconstruct`. Curating exactly this one type keeps the C#
        /// spelling available over the JDK's dictionaries without inventing a general rule that
        /// would make every two-getter class silently destructurable.
        private boolean addMapEntryComponents(PatternSyntax.Recursive recursive,
                TypeSymbol tested, List<PatternSyntax> positional,
                List<PatternComponent> components) {
            NamedTypeSymbol definition = tested instanceof TypeSymbol.Constructed constructed
                    ? constructed.definition()
                    : tested instanceof NamedTypeSymbol named ? named : null;
            if (definition == null || !MAP_ENTRY.equals(definition.qualifiedName())) {
                return false;
            }
            if (positional.size() != 2) {
                diagnostics.report(DiagnosticCode.POSITIONAL_PATTERN_ARITY, file,
                        recursive.span(), definition.displayName(), 2, positional.size());
                return true;
            }
            List<TypeSymbol> arguments = tested instanceof TypeSymbol.Constructed constructed
                    ? constructed.arguments()
                    : List.of(BuiltinType.OBJECT, BuiltinType.OBJECT);
            List<Symbol> members = model.javaInterop().getMembers(definition);
            FunctionSymbol key = entryAccessor(members, "getKey");
            FunctionSymbol value = entryAccessor(members, "getValue");
            if (key == null || value == null) {
                return false;
            }
            components.add(PatternComponent.accessor(key,
                    componentArgument(arguments, 0), positional.get(0)));
            components.add(PatternComponent.accessor(value,
                    componentArgument(arguments, 1), positional.get(1)));
            return true;
        }

        private static TypeSymbol componentArgument(List<TypeSymbol> arguments, int index) {
            if (index >= arguments.size()) {
                return BuiltinType.OBJECT;
            }
            TypeSymbol argument = arguments.get(index);
            return argument instanceof TypeSymbol.Wildcard wildcard ? wildcard.bound() : argument;
        }

        private static FunctionSymbol entryAccessor(List<Symbol> members, String name) {
            for (Symbol member : members) {
                if (member instanceof FunctionSymbol function
                        && function.name().equals(name)
                        && function.parameters().isEmpty()) {
                    return function;
                }
            }
            return null;
        }

        /// Resolves `xs is [1, .. var rest, var last]`.
        ///
        /// C# matches a list pattern against anything countable and indexable. V# has no
        /// user-defined indexers in this subset, so the surface is exactly the two the
        /// language already indexes and lengths: a vector array and a `string`, whose
        /// element is `char`. A slice may appear once, and its subpattern tests the
        /// *collection* type because a slice of an array is an array and of a string a
        /// string, matching C#.
        private void resolveListPattern(PatternSyntax.ListPattern list, TypeSymbol operandType) {
            TypeSymbol element = listElementType(operandType);
            if (element == null) {
                if (operandType != TypeSymbol.Error.INSTANCE) {
                    diagnostics.report(DiagnosticCode.LIST_PATTERN_UNSUPPORTED_TARGET, file,
                            list.span(), operandType.displayName());
                }
                return;
            }
            inferPatternLocal(list, operandType);
            boolean sliceSeen = false;
            for (PatternSyntax child : list.elements()) {
                if (child instanceof PatternSyntax.Slice slice) {
                    if (sliceSeen) {
                        diagnostics.report(DiagnosticCode.SLICE_PATTERN_MISPLACED, file,
                                slice.span());
                    }
                    sliceSeen = true;
                    if (slice.pattern() != null) {
                        resolveTestPattern(slice.pattern(), operandType);
                    }
                } else {
                    resolveTestPattern(child, element);
                }
            }
            listShapes.put(list, new ListPatternShape(operandType, element));
        }

        /// What a non-slice subpattern of a list pattern tests, or `null` when the value has
        /// no length-and-index surface V# can use.
        private static TypeSymbol listElementType(TypeSymbol collection) {
            if (collection == BuiltinType.STRING) {
                return BuiltinType.CHAR;
            }
            if (collection instanceof TypeSymbol.Array array && array.jvmDepth() == 1) {
                return array.elementType();
            }
            return null;
        }

        /// One `{ Name: pattern }` entry. C# reads a property; V# has no properties, so the
        /// readable members are a tuple's elements, an array's or string's `Length`, and a
        /// declared struct's instance fields - exactly what member access already binds.
        private PatternComponent resolvePropertyComponent(
                AuxiliarySyntax.PropertySubpattern property, TypeSymbol tested) {
            String name = property.name();
            if (tested == TypeSymbol.Error.INSTANCE) {
                return null;
            }
            if (tested instanceof TypeSymbol.Tuple tuple) {
                int index = tupleElementIndex(tuple, name);
                if (index >= 0) {
                    return PatternComponent.tupleItem(index, tuple.elements().get(index).type(),
                            property.pattern());
                }
            } else if (tested instanceof TypeSymbol.Array && "Length".equals(name)) {
                return PatternComponent.length(PatternComponent.Kind.ARRAY_LENGTH,
                        BuiltinType.INT, property.pattern());
            } else if (tested == BuiltinType.STRING && "Length".equals(name)) {
                return PatternComponent.length(PatternComponent.Kind.STRING_LENGTH,
                        BuiltinType.INT, property.pattern());
            } else if (tested instanceof NamedTypeSymbol named) {
                FieldSymbol field = instanceField(named, name);
                if (field != null) {
                    return PatternComponent.field(field, property.pattern());
                }
            }
            diagnostics.report(DiagnosticCode.MEMBER_NOT_FOUND, file, property.span(),
                    tested.displayName(), name);
            return null;
        }

        private static int tupleElementIndex(TypeSymbol.Tuple tuple, String name) {
            List<TypeSymbol.TupleElement> elements = tuple.elements();
            for (int index = 0; index < elements.size(); index++) {
                if (name.equals(elements.get(index).name())) {
                    return index;
                }
            }
            if (name.startsWith("Item")) {
                try {
                    int position = Integer.parseInt(name.substring(4));
                    if (position >= 1 && position <= elements.size()) {
                        return position - 1;
                    }
                } catch (NumberFormatException ignored) {
                    return -1;
                }
            }
            return -1;
        }

        /// The declared instance fields of a type, in declaration order - which for a record
        /// struct is its positional order.
        private List<FieldSymbol> instanceFields(NamedTypeSymbol type) {
            return model.scopeOf(type).map(Scope::symbols).orElse(List.of()).stream()
                    .filter(FieldSymbol.class::isInstance)
                    .map(FieldSymbol.class::cast)
                    .filter(field -> !field.modifiers().contains(SyntaxKind.STATIC))
                    .toList();
        }

        private FieldSymbol instanceField(NamedTypeSymbol type, String name) {
            return instanceFields(type).stream()
                    .filter(field -> field.name().equals(name))
                    .findFirst()
                    .orElse(null);
        }

        /// Records the type a pattern designation binds, when the declaration binder could
        /// only mark it inferred. `var x` and a recursive pattern's trailing name both take
        /// the type of the value reaching them, which is known only here.
        private void inferPatternLocal(PatternSyntax pattern, TypeSymbol type) {
            if (type == TypeSymbol.Error.INSTANCE || type == TypeSymbol.Inferred.INSTANCE) {
                return;
            }
            if (model.declaredSymbol(pattern).orElse(null) instanceof LocalSymbol local
                    && local.type() == TypeSymbol.Inferred.INSTANCE) {
                inferredLocalTypes.put(local, type);
            }
        }

        private void bindPattern(PatternSyntax pattern, Scope scope) {
            bindPattern(pattern, scope, false);
        }

        /// Binds the expressions inside a pattern and enforces C# 13 §12.3: a designation
        /// may not appear under `not` or `or`. Such a variable is never definitely assigned
        /// where the pattern matches - `not` matches exactly when its subpattern did not
        /// bind, and `or` short-circuits over one arm - so reading it could only observe a
        /// default. `and` keeps the flag it was given, because both arms must match.
        private void bindPattern(PatternSyntax pattern, Scope scope, boolean underNotOrOr) {
            switch (pattern) {
                case PatternSyntax.Constant constant -> bindConstantPattern(constant, scope);
                case PatternSyntax.Relational relational -> bindExpression(relational.value(),
                        scope);
                case PatternSyntax.Binary binary -> {
                    boolean disjunctive = underNotOrOr
                            || binary.operator() == PatternSyntax.Binary.Kind.OR;
                    bindPattern(binary.left(), scope, disjunctive);
                    bindPattern(binary.right(), scope, disjunctive);
                }
                case PatternSyntax.Not not -> bindPattern(not.pattern(), scope, true);
                case PatternSyntax.Parenthesized parenthesized -> bindPattern(
                        parenthesized.pattern(), scope, underNotOrOr);
                case PatternSyntax.Recursive recursive -> {
                    rejectDesignation(recursive.designation(), recursive.span(), underNotOrOr);
                    recursive.positional().forEach(child -> bindPattern(child, scope,
                            underNotOrOr));
                    recursive.properties().forEach(property -> bindPattern(property.pattern(),
                            scope, underNotOrOr));
                }
                case PatternSyntax.ListPattern list -> {
                    rejectDesignation(list.designation(), list.span(), underNotOrOr);
                    list.elements().forEach(child -> bindPattern(child, scope, underNotOrOr));
                }
                case PatternSyntax.Slice slice -> {
                    if (slice.pattern() != null) {
                        bindPattern(slice.pattern(), scope, underNotOrOr);
                    }
                }
                case PatternSyntax.Missing ignored -> {
                }
                case PatternSyntax.Discard ignored -> {
                }
                case PatternSyntax.Type typed -> {
                    rejectDesignation(typed.name(), typed.span(), underNotOrOr);
                    model.typeOf(typed.type())
                            .ifPresent(type -> requireTestableType(type, typed.span()));
                }
                case PatternSyntax.Var variable -> rejectDesignation(variable.name(),
                        variable.span(), underNotOrOr);
            }
        }

        private void rejectDesignation(String designation, SourceSpan span,
                boolean underNotOrOr) {
            if (underNotOrOr && designation != null) {
                diagnostics.report(DiagnosticCode.DESIGNATION_UNDER_NOT_OR_OR, file, span);
            }
        }

        /// A pattern the parser produced as a constant pattern. Declaration binding has
        /// already decided whether its bare name was really a type, so this either
        /// validates that type or binds a genuine constant expression.
        ///
        /// The `DeclarationGroup` case is the leftover: a name that resolves to a namespace or
        /// a static container is neither a type nor a value, and a constant pattern applies no
        /// conversion, so nothing downstream would report it - it used to reach lowering and
        /// fail as an internal error.
        private void bindConstantPattern(PatternSyntax.Constant constant, Scope scope) {
            TypeSymbol tested = model.patternType(constant).orElse(null);
            if (tested != null) {
                requireTestableType(tested, constant.span());
                return;
            }
            if (bindExpression(constant.expression(), scope)
                    instanceof BoundExpression.DeclarationGroup group) {
                diagnostics.report(DiagnosticCode.TYPE_PATTERN_UNSUPPORTED_TARGET, file,
                        constant.span(), group.name(),
                        group.candidates().stream().anyMatch(NamespaceSymbol.class::isInstance)
                                ? "it names a namespace, not a type"
                                : "it does not name a type");
            }
        }

        /// Rejects a pattern that asks the JVM a question it cannot answer. A type test
        /// is a runtime test, so it needs a type with a distinct runtime carrier; the types
        /// refused here are exactly those the backend has no `instanceof` target for, and
        /// diagnosing them keeps that backend limit a stable user-facing error instead of an
        /// internal compiler error.
        private void requireTestableType(TypeSymbol type, SourceSpan span) {
            String reason = untestableReason(type);
            if (reason != null) {
                diagnostics.report(DiagnosticCode.TYPE_PATTERN_UNSUPPORTED_TARGET, file, span,
                        type.displayName(), reason);
            }
        }

        /// Why `type` cannot be tested at run time, or `null` when it can.
        private static String untestableReason(TypeSymbol type) {
            return switch (type) {
                case BuiltinType builtin -> switch (builtin) {
                    case INT, LONG, FLOAT, DOUBLE, BOOL, CHAR, STRING, OBJECT -> null;
                    // These still share their standard JVM box with another built-in.
                    case SBYTE, SHORT, NINT ->
                        "V# carries it in the same JVM type as another built-in, so a value "
                                + "of it cannot be recognised at run time";
                    // the design gives these a distinct private object-boundary box. Admitting them
                    // as type patterns needs a dedicated runtime predicate/unbox path rather
                    // than exposing that private implementation class to generated code.
                    case BYTE, USHORT, UINT, ULONG, NUINT ->
                        "its private unsigned box is not exposed as a type-pattern target";
                    case DECIMAL -> "V# gives it no runtime class of its own";
                    case DYNAMIC, VOID -> "it is not a testable type";
                };
                case NamedTypeSymbol named -> untestableNamedReason(named);
                // Already reported where the type failed to resolve; do not report twice.
                case TypeSymbol.Error ignored -> null;
                case TypeSymbol.Array ignored -> "V# does not test array types yet";
                case TypeSymbol.Nullable ignored ->
                    "a nullable value type is never the run-time type of a value";
                case TypeSymbol.Tuple ignored -> "V# tuples have no run-time class";
                case TypeSymbol.Constructed ignored ->
                    "a constructed generic type is erased on the JVM";
                case TypeParameterSymbol ignored -> "a type parameter is erased on the JVM";
                case TypeSymbol.Wildcard ignored ->
                    "a wildcard is a Java type argument, not the run-time type of a value";
                // No value has one of these as its run-time type: they are either storage
                // classifications or types no expression can be an instance of.
                case TypeSymbol.Ref ignored -> "it is not a testable type";
                case TypeSymbol.Null ignored -> "it is not a testable type";
                case TypeSymbol.Inferred ignored -> "it is not a testable type";
                case TypeSymbol.Range ignored -> "it is not a testable type";
                case TypeSymbol.Index ignored -> "it is not a testable type";
                case TypeSymbol.Function ignored -> "it is not a testable type";
            };
        }

        /// Named types carry their own class, except the corelib placeholders, which
        /// declare a name for the type system and no runtime class at all - the sole
        /// exceptions being the corelib exception types, which the backend aliases to real
        /// JVM classes ([CorelibCarriers]). Enum members are constants on their holder, so an enum has
        /// no distinct carrier either.
        private static String untestableNamedReason(NamedTypeSymbol named) {
            if (named.declaredKind() == NamedTypeSymbol.DeclaredKind.ENUM) {
                return "V# enums have no run-time class of their own";
            }
            if (SourceFile.CORELIB_NAME.equals(named.location().file().name())
                    && !vsharp.compiler.semantics.types.CorelibCarriers
                            .hasRuntimeClass(named.qualifiedName())) {
                return "it is a compiler-declared placeholder with no run-time class";
            }
            return null;
        }

        private TypeSymbol resolveType(TypeSyntax syntax, Scope scope) {
            TypeSymbol known = model.typeOf(syntax).orElse(null);
            if (known != null) {
                return known;
            }
            return switch (syntax) {
                case TypeSyntax.Predefined predefined -> BuiltinType
                        .fromKeyword(predefined.keyword()).map(TypeSymbol.class::cast)
                        .orElse(TypeSymbol.Error.INSTANCE);
                case TypeSyntax.Name name -> resolveNamedType(name, scope);
                case TypeSyntax.Array array -> new TypeSymbol.Array(resolveType(array.element(),
                        scope), array.ranks());
                case TypeSyntax.Tuple tuple -> new TypeSymbol.Tuple(tuple.elements().stream()
                        .map(element -> new TypeSymbol.TupleElement(resolveType(element.type(),
                                scope), element.name())).toList());
                case TypeSyntax.Nullable nullable -> new TypeSymbol.Nullable(resolveType(
                        nullable.element(), scope));
                case TypeSyntax.Ref ref -> new TypeSymbol.Ref(resolveType(ref.element(), scope),
                        ref.readOnly());
                case TypeSyntax.Omitted ignored -> TypeSymbol.Error.INSTANCE;
                case TypeSyntax.Missing ignored -> TypeSymbol.Error.INSTANCE;
            };
        }

        private TypeSymbol resolveNamedType(TypeSyntax.Name name, Scope scope) {
            if (name.segments().size() != 1) {
                diagnostics.report(DiagnosticCode.TYPE_OR_NAMESPACE_NOT_FOUND, file, name.span(),
                        name.text());
                return TypeSymbol.Error.INSTANCE;
            }
            TypeSyntax.Segment segment = name.segments().getFirst();
            List<Symbol> declarations = lookupInScope(scope, segment.identifier());
            for (Symbol declaration : declarations) {
                if (declaration instanceof TypeParameterSymbol parameter
                        && segment.typeArguments().isEmpty()) {
                    return parameter;
                }
                if (declaration instanceof NamedTypeSymbol named) {
                    if (segment.typeArguments().size() == named.arity()) {
                        if (named.arity() == 0) {
                            return named;
                        }
                        List<TypeSymbol> arguments = segment.typeArguments().stream()
                                .map(argument -> resolveType(argument, scope)).toList();
                        return new TypeSymbol.Constructed(named, arguments);
                    }
                    diagnostics.report(DiagnosticCode.WRONG_TYPE_ARGUMENT_COUNT, file,
                            name.span(), name.text(), named.arity());
                    return TypeSymbol.Error.INSTANCE;
                }
            }
            diagnostics.report(DiagnosticCode.TYPE_OR_NAMESPACE_NOT_FOUND, file, name.span(),
                    name.text());
            return TypeSymbol.Error.INSTANCE;
        }

        private boolean requireImplicitConversion(BoundExpression source, TypeSymbol target,
                ExpressionSyntax syntax) {
            if (isRecovery(source)) {
                return false;
            }
            if (source.type() == TypeSymbol.Error.INSTANCE) {
                if (source instanceof BoundExpression.FunctionGroup
                        || source instanceof BoundExpression.DeclarationGroup) {
                    diagnostics.report(DiagnosticCode.CANNOT_IMPLICITLY_CONVERT, file,
                            syntax.span(), displayType(source), target.displayName());
                }
                return false;
            }
            Conversion conversion = Conversions.classify(source, target);
            if (conversion.isImplicit()) {
                return true;
            }
            BoundExpression converted = userConversion(source, target, syntax.span(), true);
            if (converted != null) {
                replaceCachedExpression(syntax, source, converted);
                return true;
            }
            // C# reports the out-of-range *value*, not the type pair, whenever the source is a
            // constant expression the target could otherwise have accepted - and `-1` is such
            // an expression just as much as `1` is.
            Object constant = foldedValue(source);
            if (constant != null && isConstantConversionTarget(source.type(), target)) {
                diagnostics.report(DiagnosticCode.CONSTANT_VALUE_CANNOT_CONVERT, file,
                        syntax.span(), constant, target.displayName());
            } else {
                diagnostics.report(DiagnosticCode.CANNOT_IMPLICITLY_CONVERT, file, syntax.span(),
                        displayType(source), target.displayName());
            }
            return false;
        }

        private BoundExpression userConversion(BoundExpression source, TypeSymbol target,
                SourceSpan span, boolean implicitOnly) {
            FunctionSymbol operator = conversionOperator(source.type(), target, implicitOnly);
            if (operator == null) {
                return null;
            }
            return new BoundExpression.Call(span, operator.returnType(), null, operator, operator,
                    List.of(source), List.of(0));
        }

        private FunctionSymbol conversionOperator(TypeSymbol source, TypeSymbol target,
                boolean implicitOnly) {
            if (source instanceof NamedTypeSymbol sourceType) {
                FunctionSymbol operator = conversionOperator(sourceType, source, target,
                        implicitOnly);
                if (operator != null) {
                    return operator;
                }
            }
            if (target instanceof NamedTypeSymbol targetType) {
                return conversionOperator(targetType, source, target, implicitOnly);
            }
            return null;
        }

        private FunctionSymbol conversionOperator(NamedTypeSymbol owner, TypeSymbol source,
                TypeSymbol target, boolean implicitOnly) {
            return model.scopeOf(owner).stream()
                    .flatMap(scope -> scope.symbols().stream())
                    .filter(FunctionSymbol.class::isInstance)
                    .map(FunctionSymbol.class::cast)
                    .filter(function -> function.typeParameters().isEmpty()
                            && function.parameters().size() == 1
                            && function.parameters().getFirst().type().equals(source)
                            && function.returnType().equals(target)
                            && function.modifiers().contains(SyntaxKind.PUBLIC)
                            && function.modifiers().contains(SyntaxKind.STATIC)
                            && function.name().startsWith(implicitOnly
                                    ? "implicit operator " : "explicit operator "))
                    .findFirst()
                    .orElse(null);
        }

        private void replaceCachedExpression(ExpressionSyntax syntax,
                BoundExpression original, BoundExpression replacement) {
            if (expressions.get(syntax) == original) {
                expressions.put(syntax, replacement);
            }
            int position = indexOfIdentity(bindingOrder, original);
            if (position >= 0) {
                bindingOrder.set(position, replacement);
            }
            openResults.remove(original);
        }

        /// The integral value of a constant expression, for the diagnostic that prints it, or
        /// `null` when the expression is not a constant of a shape this rule concerns.
        private static Object foldedValue(BoundExpression source) {
            return switch (ConstantEvaluator.evaluate(source, false)) {
                case ConstantEvaluator.Result.Value value -> switch (value.value()) {
                    case ConstantValue.Int folded -> folded.value();
                    case ConstantValue.Long folded -> folded.value();
                    default -> null;
                };
                case ConstantEvaluator.Result.Failure ignored -> null;
            };
        }

        private static boolean isConstantConversionTarget(TypeSymbol sourceType, TypeSymbol targetType) {
            TypeSymbol effectiveTarget = targetType instanceof TypeSymbol.Nullable n ? n.element() : targetType;
            if (effectiveTarget instanceof BuiltinType b) {
                if (sourceType == BuiltinType.INT) {
                    return switch (b) {
                        case SBYTE, BYTE, SHORT, USHORT, UINT, ULONG, NINT, NUINT -> true;
                        default -> false;
                    };
                }
                if (sourceType == BuiltinType.LONG) {
                    return b == BuiltinType.ULONG;
                }
            }
            return effectiveTarget instanceof NamedTypeSymbol n && n.declaredKind() == NamedTypeSymbol.DeclaredKind.ENUM;
        }

        private void requireReturnConversion(BoundExpression source, FunctionSymbol function,
                ExpressionSyntax syntax) {
            TypeSymbol expected = bodyReturnType(function);
            // A lambda return type stays `Inferred` until a target type is known, so the
            // conversion cannot be checked yet.
            if (expected != BuiltinType.VOID && expected != TypeSymbol.Inferred.INSTANCE) {
                requireImplicitConversion(source, expected, syntax);
            }
        }

        /// The type a `return` inside this callable's body must produce.
        ///
        /// For an `async` callable that is the *result* of the declared task, not the task
        ///: `async Task<int> Sum()` is written with `return 42;`, exactly as in C#,
        /// because the task is manufactured by the wrapper the compiler emits rather than by
        /// the body. For every other callable it is the declared return type unchanged.
        private static TypeSymbol bodyReturnType(FunctionSymbol function) {
            return function.isAsync()
                    ? TaskTypes.bodyResultOf(function.returnType())
                    : function.returnType();
        }

        private void requireBoolean(BoundExpression expression, ExpressionSyntax syntax) {
            requireImplicitConversion(expression, BuiltinType.BOOL, syntax);
        }

        private void reportInvalidBinary(SyntaxKind operator, SyntaxNode syntax,
                BoundExpression left, BoundExpression right) {
            diagnostics.report(DiagnosticCode.BINARY_OPERATOR_NOT_APPLICABLE, file,
                    syntax.span(), operator.display(), displayType(left), displayType(right));
        }

        private static boolean isValue(Symbol symbol) {
            return symbol instanceof LocalSymbol || symbol instanceof ParameterSymbol
                    || symbol instanceof FieldSymbol || symbol instanceof EnumMemberSymbol;
        }

        private static boolean isTypeOrNamespace(Symbol symbol) {
            return symbol instanceof NamespaceSymbol || symbol instanceof ContainerSymbol
                    || symbol instanceof NamedTypeSymbol || symbol instanceof TypeParameterSymbol;
        }

        private static boolean isRecovery(BoundExpression expression) {
            // A function group, a type/namespace group and an untyped lambda all carry
            // the error type without being errors: each is a form that only a surrounding
            // context - an invocation, a member access, a target type - can finish typing.
            return expression instanceof BoundExpression.Error
                    || expression.type() == TypeSymbol.Error.INSTANCE
                            && !(expression instanceof BoundExpression.FunctionGroup)
                            && !(expression instanceof BoundExpression.DeclarationGroup)
                            && !Conversions.isUnboundLambda(expression);
        }

        private static boolean isWritable(BoundExpression expression) {
            if (expression instanceof BoundExpression.ElementAccess || expression instanceof BoundExpression.IndexAccess) {
                return true;
            }
            if (expression instanceof BoundExpression.MemberAccess member) {
                if (member.member() instanceof FieldSymbol field) {
                    return !field.isConstant() && !field.modifiers().contains(SyntaxKind.READONLY);
                }
                return false;
            }
            if (expression instanceof BoundExpression.Tuple tuple) {
                return tuple.elements().stream().allMatch(Binder::isWritable);
            }
            if (!(expression instanceof BoundExpression.Value value)) {
                return false;
            }
            return switch (value.symbol()) {
                case LocalSymbol local -> !local.constant();
                case ParameterSymbol parameter -> !parameter.modifiers().contains(SyntaxKind.IN);
                case FieldSymbol field -> !field.isConstant()
                        && !field.modifiers().contains(SyntaxKind.READONLY);
                case EnumMemberSymbol ignored -> false;
                case NamespaceSymbol ignored -> false;
                case ContainerSymbol ignored -> false;
                case NamedTypeSymbol ignored -> false;
                case FunctionSymbol ignored -> false;
                case TypeParameterSymbol ignored -> false;
            };
        }

        /// Binds the type operand and enforces the part of C#'s `sizeof` rule that is legal in
        /// a safe context. V# excludes unsafe code, so user structs, enums, native integers
        /// and reference types receive CS0233's equivalent diagnostic here instead of
        /// reaching the backend. `typeof(T)` is compile-time type identity; an erased method
        /// type parameter is the one form V# cannot preserve because its run-time argument is
        /// unavailable.
        private BoundExpression bindTypeOperation(ExpressionSyntax.TypeOperator operation,
                Scope scope) {
            TypeSymbol operandType = resolveType(operation.type(), scope);
            if (operandType == TypeSymbol.Error.INSTANCE) {
                return new BoundExpression.TypeOperation(operation.span(),
                        operation.operator() == SyntaxKind.SIZEOF
                                ? BuiltinType.INT : BuiltinType.OBJECT,
                        operation.operator(), operandType);
            }
            if (operation.operator() == SyntaxKind.SIZEOF && !hasPredefinedSize(operandType)) {
                diagnostics.report(DiagnosticCode.SIZEOF_REQUIRES_UNSAFE_CONTEXT, file,
                        operation.type().span(), operandType.displayName());
            } else if (operation.operator() == SyntaxKind.TYPEOF
                    && containsTypeParameter(operandType)) {
                diagnostics.report(DiagnosticCode.NOT_YET_IMPLEMENTED, file,
                        operation.type().span(), "typeof over an erased type parameter");
            }
            TypeSymbol resultType = BuiltinType.INT;
            if (operation.operator() == SyntaxKind.TYPEOF) {
                if (JavaInterop.hasClassLiteral(operandType)) {
                    NamedTypeSymbol classType = Objects.requireNonNull(
                            model.javaInterop().resolveType("java.lang.Class"),
                            "JDK 25 must publish java.lang.Class");
                    TypeSymbol classArgument = operandType instanceof NamedTypeSymbol named
                            && named.qualifiedName().equals("System.String")
                                    ? BuiltinType.STRING : operandType;
                    resultType = new TypeSymbol.Constructed(classType, List.of(classArgument));
                } else {
                    resultType = BuiltinType.OBJECT;
                }
            }
            return new BoundExpression.TypeOperation(operation.span(), resultType,
                    operation.operator(), operandType);
        }

        /// `nameof` validates name binding but never evaluates its operand. Keep the bound
        /// child for semantic validation/constant classification; lowering derives the final
        /// identifier directly from syntax, so a namespace/type declaration group is never
        /// mistaken for an executable value.
        private BoundExpression bindNameOf(ExpressionSyntax.NameOf nameOf, Scope scope) {
            BoundExpression operand = bindExpression(nameOf.expression(), scope);
            boolean hasName = nameOf.expression() instanceof ExpressionSyntax.Identifier
                    || nameOf.expression() instanceof ExpressionSyntax.MemberAccess member
                            && !member.nullConditional();
            if (!hasName && !(operand instanceof BoundExpression.Error)) {
                diagnostics.report(DiagnosticCode.EXPRESSION_HAS_NO_NAME, file,
                        nameOf.expression().span());
            }
            return new BoundExpression.Deferred(nameOf.span(), BuiltinType.STRING, "nameof",
                    List.of(operand));
        }

        private static boolean hasPredefinedSize(TypeSymbol type) {
            return type instanceof BuiltinType builtin && switch (builtin) {
                case SBYTE, BYTE, SHORT, USHORT, INT, UINT, LONG, ULONG,
                        FLOAT, DOUBLE, DECIMAL, BOOL, CHAR -> true;
                case NINT, NUINT, STRING, OBJECT, DYNAMIC, VOID -> false;
            };
        }

        private static boolean containsTypeParameter(TypeSymbol type) {
            return switch (type) {
                case TypeParameterSymbol ignored -> true;
                case TypeSymbol.Array array -> containsTypeParameter(array.element());
                case TypeSymbol.Tuple tuple -> tuple.elements().stream()
                        .anyMatch(element -> containsTypeParameter(element.type()));
                case TypeSymbol.Nullable nullable -> containsTypeParameter(nullable.element());
                case TypeSymbol.Ref ref -> containsTypeParameter(ref.element());
                case TypeSymbol.Constructed constructed -> constructed.arguments().stream()
                        .anyMatch(Binder::containsTypeParameter);
                case TypeSymbol.Function function -> containsTypeParameter(function.returns())
                        || function.parameters().stream().anyMatch(Binder::containsTypeParameter);
                default -> false;
            };
        }

        private static String displayType(BoundExpression expression) {
            return switch (expression) {
                case BoundExpression.FunctionGroup ignored -> "method group";
                case BoundExpression.DeclarationGroup ignored -> "type or namespace";
                default -> expression.type().displayName();
            };
        }

        private static SyntaxKind compoundOperator(SyntaxKind assignment) {
            return switch (assignment) {
                case PLUS_EQUALS -> SyntaxKind.PLUS;
                case MINUS_EQUALS -> SyntaxKind.MINUS;
                case ASTERISK_EQUALS -> SyntaxKind.ASTERISK;
                case SLASH_EQUALS -> SyntaxKind.SLASH;
                case PERCENT_EQUALS -> SyntaxKind.PERCENT;
                case AMPERSAND_EQUALS -> SyntaxKind.AMPERSAND;
                case BAR_EQUALS -> SyntaxKind.BAR;
                case CARET_EQUALS -> SyntaxKind.CARET;
                case QUESTION_QUESTION_EQUALS -> SyntaxKind.QUESTION_QUESTION;
                case LESS_THAN_LESS_THAN_EQUALS -> SyntaxKind.LESS_THAN_LESS_THAN;
                case GREATER_THAN_GREATER_THAN_EQUALS -> SyntaxKind.GREATER_THAN_GREATER_THAN;
                case GREATER_THAN_GREATER_THAN_GREATER_THAN_EQUALS ->
                        SyntaxKind.GREATER_THAN_GREATER_THAN_GREATER_THAN;
                default -> null;
            };
        }

        private static FunctionSymbol synthesizedFunction(Scope scope) {
            return scope.lookupLocal("<top-level>").stream()
                    .filter(FunctionSymbol.class::isInstance)
                    .map(FunctionSymbol.class::cast)
                    .filter(FunctionSymbol::synthesized)
                    .findFirst()
                    .orElse(null);
        }
    }

}
