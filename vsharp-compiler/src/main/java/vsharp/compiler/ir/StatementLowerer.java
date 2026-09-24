package vsharp.compiler.ir;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import vsharp.compiler.semantics.binding.BoundExpression;
import vsharp.compiler.semantics.binding.ExpressionBinding;
import vsharp.compiler.semantics.binding.PatternComponent;
import vsharp.compiler.semantics.binding.PositionalLayout;
import vsharp.compiler.semantics.binding.SemanticModel;
import vsharp.compiler.semantics.flow.FlowResult;
import vsharp.compiler.semantics.symbols.FieldSymbol;
import vsharp.compiler.semantics.symbols.FunctionSymbol;
import vsharp.compiler.semantics.symbols.LocalSymbol;
import vsharp.compiler.semantics.symbols.SourceLocation;
import vsharp.compiler.semantics.symbols.Symbol;
import vsharp.compiler.source.SourceFile;
import vsharp.compiler.syntax.AuxiliarySyntax;
import vsharp.compiler.syntax.ExpressionSyntax;
import vsharp.compiler.syntax.PatternSyntax;
import vsharp.compiler.syntax.StatementSyntax;

/// Lowers the core statement subset after flow analysis.
///
/// The lowering intentionally filters statements FlowAnalysis proved unreachable. This keeps
/// dead source from becoming verifier-hostile bytecode later, while preserving source spans on
/// all emitted statements for debugging metadata. Exception, switch, foreach and resource
/// lowering remain separate rules because each needs dedicated JVM control-flow machinery.
public final class StatementLowerer {

    private final SemanticModel model;
    private final ExpressionBinding expressions;
    private final FlowResult flow;
    /// The file being lowered, so synthetic locals carry a real source location.
    private final SourceFile file;

    private StatementLowerer(SourceFile file, SemanticModel model, ExpressionBinding expressions,
            FlowResult flow) {
        this.file = Objects.requireNonNull(file, "file");
        this.model = Objects.requireNonNull(model, "model");
        this.expressions = Objects.requireNonNull(expressions, "expressions");
        this.flow = Objects.requireNonNull(flow, "flow");
    }

    public static IrStatement lower(StatementSyntax statement, SourceFile file,
            SemanticModel model, ExpressionBinding expressions, FlowResult flow) {
        return new StatementLowerer(file, model, expressions, flow).lowerRequired(statement);
    }

    private IrStatement lowerRequired(StatementSyntax statement) {
        return lowerOptional(Objects.requireNonNull(statement, "statement"))
                .orElseThrow(() -> new IllegalArgumentException("root statement is unreachable"));
    }

    private Optional<IrStatement> lowerOptional(StatementSyntax statement) {
        if (!(statement instanceof StatementSyntax.Block) && !flow.isReachable(statement)) {
            return Optional.empty();
        }
        return Optional.of(switch (statement) {
            case StatementSyntax.Block block -> new IrStatement.Block(block.span(),
                    lowerStatements(block.statements()));
            case StatementSyntax.Empty empty -> new IrStatement.Empty(empty.span());
            case StatementSyntax.Expression expression -> new IrStatement.Expression(expression.span(),
                    lowerExpression(expression.expression()));
            case StatementSyntax.LocalDeclaration declaration ->
                    lowerLocalDeclaration(declaration);
            case StatementSyntax.If conditional -> new IrStatement.If(conditional.span(),
                    lowerExpression(conditional.condition()), lowerNested(conditional.whenTrue()),
                    conditional.whenFalse() == null ? null : lowerNested(conditional.whenFalse()));
            case StatementSyntax.While loop -> new IrStatement.While(loop.span(),
                    lowerExpression(loop.condition()), lowerNested(loop.body()));
            case StatementSyntax.Do loop -> new IrStatement.DoWhile(loop.span(),
                    lowerNested(loop.body()), lowerExpression(loop.condition()));
            case StatementSyntax.For loop -> new IrStatement.For(loop.span(),
                    lowerStatements(loop.initializers()), loop.condition() == null ? null
                            : lowerExpression(loop.condition()),
                    loop.iterators().stream().map(this::lowerExpression).toList(),
                    lowerNested(loop.body()));
            case StatementSyntax.Break broken -> new IrStatement.Break(broken.span());
            case StatementSyntax.Continue continued -> new IrStatement.Continue(continued.span());
            case StatementSyntax.Return returned -> new IrStatement.Return(returned.span(),
                    returned.expression() == null ? null : lowerExpression(returned.expression()));
            case StatementSyntax.Throw thrown -> new IrStatement.Throw(thrown.span(),
                    thrown.expression() == null ? null : lowerExpression(thrown.expression()));
            case StatementSyntax.Goto jumped -> lowerGoto(jumped);
            case StatementSyntax.Labeled labeled -> new IrStatement.Labeled(labeled.span(),
                    labeled.label(), lowerNested(labeled.statement()));
            case StatementSyntax.Foreach loop -> lowerForeach(loop);
            case StatementSyntax.Switch selection -> lowerSwitch(selection);
            case StatementSyntax.Try attempt -> lowerTry(attempt);
            case StatementSyntax.Using using -> lowerUsing(using);
            case StatementSyntax.Lock locked -> new IrStatement.Lock(locked.span(),
                    lowerExpression(locked.expression()), lowerNested(locked.body()));
            case StatementSyntax.Checked checked -> new IrStatement.Checked(checked.span(),
                    checked.checked(), lowerBlock(checked.body()));
            case StatementSyntax.Yield yielded -> throw new IllegalStateException(
                    "excluded yield statement reached IR lowering at " + yielded.span());
            // Local functions are compiled as separate IrFunction entries by UnitLowerer;
            // their declaration has no run-time instruction at this source position.
            case StatementSyntax.LocalFunction local -> new IrStatement.Empty(local.span());
        });
    }

    private List<IrStatement> lowerStatements(List<StatementSyntax> statements) {
        List<IrStatement> lowered = new ArrayList<>(statements.size());
        for (StatementSyntax statement : statements) {
            lowerOptional(statement).ifPresent(lowered::add);
        }
        return lowered;
    }

    private IrStatement lowerNested(StatementSyntax statement) {
        return lowerOptional(statement).orElseGet(() -> new IrStatement.Empty(statement.span()));
    }

    private IrStatement.Try lowerTry(StatementSyntax.Try attempt) {
        List<IrCatch> catches = attempt.catches().stream().map(this::lowerCatch).toList();
        return new IrStatement.Try(attempt.span(), lowerBlock(attempt.body()), catches,
                attempt.finallyBody() == null ? null : lowerBlock(attempt.finallyBody()));
    }

    private IrCatch lowerCatch(AuxiliarySyntax.CatchClause clause) {
        IrValueType type = clause.type() == null ? null : IrValueType.of(model.typeOf(clause.type())
                .orElseThrow(() -> new IllegalArgumentException("catch type was not resolved")));
        LocalSymbol variable = null;
        if (clause.name() != null) {
            Symbol declared = model.declaredSymbol(clause).orElseThrow(
                    () -> new IllegalArgumentException("catch variable has no symbol"));
            if (!(declared instanceof LocalSymbol local)) {
                throw new IllegalArgumentException("catch declaration is not a local: " + declared);
            }
            variable = local;
        }
        return new IrCatch(type, variable, clause.filter() == null ? null
                : lowerExpression(clause.filter()), lowerBlock(clause.body()));
    }

    private IrStatement.Using lowerUsing(StatementSyntax.Using using) {
        IrStatement resource = switch (using.resource()) {
            case StatementSyntax.LocalDeclaration declaration -> lowerLocals(declaration);
            case ExpressionSyntax expression -> new IrStatement.Expression(expression.span(),
                    lowerExpression(expression));
            default -> throw new IllegalStateException("invalid using resource");
        };
        return new IrStatement.Using(using.span(), resource,
                using.body() == null ? null : lowerNested(using.body()));
    }

    private IrStatement.Block lowerBlock(StatementSyntax.Block block) {
        IrStatement lowered = lowerRequired(block);
        if (lowered instanceof IrStatement.Block result) {
            return result;
        }
        throw new IllegalStateException("block lowering changed statement shape");
    }

    /// A local declaration, which is either the ordinary `T name = value;` form or a
    /// deconstruction declaration (`var (x, y) = pair;`, `(int x, string y) = pair;`).
    ///
    /// A deconstruction declarator has no symbol of its own - its names live on the
    /// designation pattern - so it lowers to the declarations of those locals followed by
    /// the same `TupleDeconstructionStore` a deconstruction *assignment* produces. Emitting
    /// it as a statement pair rather than teaching `IrLocal` about groups keeps one shape
    /// for "a local exists" and one for "a tuple is taken apart".
    private IrStatement lowerLocalDeclaration(StatementSyntax.LocalDeclaration declaration) {
        if (declaration.variables().stream().noneMatch(v -> v.designation() != null)) {
            return lowerLocals(declaration);
        }
        List<IrLocal> locals = new ArrayList<>();
        List<IrStatement> statements = new ArrayList<>();
        for (AuxiliarySyntax.VariableDeclarator variable : declaration.variables()) {
            if (variable.designation() == null) {
                locals.add(lowerLocal(variable));
                continue;
            }
            List<LocalSymbol> targets = patternLocals(variable.designation());
            targets.forEach(local -> locals.add(new IrLocal(local, null)));
            if (variable.initializer() == null) {
                continue;
            }
            IrExpression value = lowerExpression(variable.initializer());
            List<IrExpression> loads = targets.stream()
                    .map(local -> (IrExpression) new IrExpression.Load(
                            IrValueType.of(expressions.effectiveType(local).orElseThrow(
                                    () -> new IllegalArgumentException(
                                            "deconstruction target has no type: " + local.name()))),
                            local))
                    .toList();
            // A positional `record struct` initializer is taken apart through its component
            // fields rather than through tuple items; tuples keep an empty list.
            List<FieldSymbol> components = model.positionalLayout(value.type().sourceType())
                    .map(PositionalLayout::components)
                    .orElseGet(List::of);
            statements.add(new IrStatement.Expression(variable.span(),
                    new IrExpression.TupleDeconstructionStore(value.type(), loads, components, value)));
        }
        List<IrStatement> all = new ArrayList<>();
        all.add(new IrStatement.Locals(declaration.span(), locals));
        all.addAll(statements);
        return new IrStatement.Block(declaration.span(), all);
    }

    private IrStatement.Locals lowerLocals(StatementSyntax.LocalDeclaration declaration) {
        List<IrLocal> locals = new ArrayList<>(declaration.variables().size());
        for (AuxiliarySyntax.VariableDeclarator variable : declaration.variables()) {
            locals.add(lowerLocal(variable));
        }
        return new IrStatement.Locals(declaration.span(), locals);
    }

    private IrLocal lowerLocal(AuxiliarySyntax.VariableDeclarator variable) {
        Symbol symbol = model.declaredSymbol(variable).orElseThrow(
                () -> new IllegalArgumentException("local declaration has no symbol: " + variable));
        if (!(symbol instanceof LocalSymbol local)) {
            throw new IllegalArgumentException("declaration does not denote a local: " + symbol);
        }
        return new IrLocal(local, variable.initializer() == null ? null
                : lowerExpression(variable.initializer()));
    }

    private IrStatement lowerGoto(StatementSyntax.Goto jumped) {
        return switch (jumped.kind()) {
            case LABEL -> new IrStatement.Goto(jumped.span(), jumped.label());
            case CASE -> new IrStatement.SwitchGoto(jumped.span(),
                    IrStatement.SwitchGoto.SwitchGotoKind.CASE, lowerExpression(jumped.value()));
            case DEFAULT -> new IrStatement.SwitchGoto(jumped.span(),
                    IrStatement.SwitchGoto.SwitchGotoKind.DEFAULT, null);
        };
    }

    private IrStatement.Foreach lowerForeach(StatementSyntax.Foreach loop) {
        IrExpression collection = lowerExpression(loop.collection());
        // Binding already reported CS1579 for a collection with no element type, so reaching
        // lowering without one is a compiler defect, not a user error.
        IrValueType element = IrValueType.of(model.javaInterop()
                .foreachElementType(collection.type().sourceType())
                .orElseThrow(() -> new IllegalArgumentException(
                        "foreach collection is not enumerable: "
                                + collection.type().sourceType().displayName())));
        if (loop.variable() instanceof PatternSyntax.Recursive recursive
                && recursive.type() == null && recursive.positional().size() > 1) {
            return lowerDeconstructingForeach(loop, recursive, collection, element);
        }
        return new IrStatement.Foreach(loop.span(), iterationVariables(loop.variable()),
                collection, element, lowerNested(loop.body()));
    }

    /// `foreach (var (a, b) in pairs)` iterates over *one* element carrier and takes it apart
    /// at the top of the body, which is exactly the shape `var (a, b) = element;` lowers to.
    /// Keeping a single iteration variable leaves the loop protocol itself untouched: the
    /// backend stores one element per step and the deconstruction is ordinary body code.
    private IrStatement.Foreach lowerDeconstructingForeach(StatementSyntax.Foreach loop,
            PatternSyntax.Recursive recursive, IrExpression collection, IrValueType element) {
        LocalSymbol carrier = new LocalSymbol(
                "<element>" + loop.span().start(), "<element>" + loop.span().start(),
                new SourceLocation(file, loop.variable().span()),
                element.sourceType(), false, false);
        List<IrLocal> declared = new ArrayList<>();
        List<IrStatement> body = new ArrayList<>();
        List<PatternComponent> resolved = expressions.componentsOf(recursive);
        if (resolved.stream().anyMatch(
                component -> component.kind() == PatternComponent.Kind.ACCESSOR)) {
            // A curated interop deconstruction reads each component by calling a method
            //, so the components are stored one by one instead of through the tuple
            // walk, which only knows fields and tuple items.
            List<IrStatement> reads = accessorComponentStores(recursive, resolved, carrier,
                    element, declared);
            body.add(new IrStatement.Locals(loop.variable().span(), declared));
            body.addAll(reads);
            body.add(lowerNested(loop.body()));
            return new IrStatement.Foreach(loop.span(),
                    List.of(new IrIterationVariable(carrier, element)), collection, element,
                    new IrStatement.Block(loop.span(), body));
        }
        List<IrExpression> targets = deconstructionTargets(recursive, declared);
        // A positional `record struct` element is taken apart through its component fields
        // rather than through tuple items; tuples keep an empty list.
        List<FieldSymbol> components = model.positionalLayout(element.sourceType())
                .map(PositionalLayout::components)
                .orElseGet(List::of);
        IrStatement store = new IrStatement.Expression(loop.variable().span(),
                new IrExpression.TupleDeconstructionStore(element, targets, components,
                        new IrExpression.Load(element, carrier)));
        body.add(new IrStatement.Locals(loop.variable().span(), declared));
        body.add(store);
        body.add(lowerNested(loop.body()));
        return new IrStatement.Foreach(loop.span(),
                List.of(new IrIterationVariable(carrier, element)), collection, element,
                new IrStatement.Block(loop.span(), body));
    }

    /// One assignment per component for a deconstruction that reads through Java accessors
    ///: `var (key, value) in map.EntrySet()` becomes `key = entry.GetKey();` and
    /// `value = entry.GetValue();`, which are ordinary calls the backend already emits.
    private List<IrStatement> accessorComponentStores(PatternSyntax.Recursive recursive,
            List<PatternComponent> components, LocalSymbol carrier, IrValueType element,
            List<IrLocal> declared) {
        List<IrStatement> stores = new ArrayList<>(components.size());
        for (int index = 0; index < components.size(); index++) {
            PatternComponent component = components.get(index);
            PatternSyntax child = recursive.positional().get(index);
            IrValueType componentType = IrValueType.of(component.type());
            LocalSymbol target = child instanceof PatternSyntax.Discard discard
                    ? new LocalSymbol("<discard>" + discard.span().start(),
                            "<discard>" + discard.span().start(),
                            new SourceLocation(file, discard.span()), component.type(),
                            false, false)
                    : patternLocal(child);
            declared.add(new IrLocal(target, null));
            // The call's *function* carries the component type the parameterization
            // publishes, while its *declaration* stays the erased Java method - which is what
            // makes the backend narrow the erased reference instead of storing an `Object`
            // into an `int` slot.
            FunctionSymbol accessor = component.accessor();
            FunctionSymbol specialized = new FunctionSymbol(accessor.name(),
                    accessor.qualifiedName(), accessor.location(), component.type(),
                    List.of(), accessor.parameters(), accessor.modifiers(),
                    accessor.localFunction(), accessor.synthesized(), accessor.interfaceOwner());
            IrExpression read = new IrExpression.Call(componentType,
                    new IrExpression.Load(element, carrier), specialized,
                    model.javaInterop().erasedDeclaration(accessor), List.of(), List.of());
            stores.add(new IrStatement.Expression(child.span(),
                    new IrExpression.Store(componentType, target, read)));
        }
        return stores;
    }

    /// Builds one store target per positional component, declaring every local the
    /// designation introduces. A nested designation becomes a nested tuple target, which the
    /// backend re-enters with the same walk.
    private List<IrExpression> deconstructionTargets(PatternSyntax.Recursive recursive,
            List<IrLocal> declared) {
        List<PatternComponent> components = expressions.componentsOf(recursive);
        List<PatternSyntax> positional = recursive.positional();
        List<IrExpression> targets = new ArrayList<>(positional.size());
        for (int index = 0; index < positional.size(); index++) {
            PatternSyntax child = positional.get(index);
            IrValueType componentType = IrValueType.of(components.get(index).type());
            targets.add(switch (child) {
                case PatternSyntax.Recursive nested -> new IrExpression.Tuple(componentType,
                        deconstructionTargets(nested, declared));
                // A discarded component is still read and converted, so it needs a slot to
                // land in; naming it by position keeps the symbol identity unique.
                case PatternSyntax.Discard discard -> declare(new LocalSymbol(
                        "<discard>" + discard.span().start(), "<discard>" + discard.span().start(),
                        new SourceLocation(file, discard.span()),
                        components.get(index).type(), false, false), componentType, declared);
                default -> {
                    LocalSymbol local = patternLocal(child);
                    yield declare(local, IrValueType.of(expressions.effectiveType(local).orElseThrow(
                            () -> new IllegalArgumentException(
                                    "deconstruction target has no type: " + local.name()))),
                            declared);
                }
            });
        }
        return targets;
    }

    private IrExpression declare(LocalSymbol local, IrValueType type,
            List<IrLocal> declared) {
        declared.add(new IrLocal(local, null));
        return new IrExpression.Load(type, local);
    }

    private List<IrIterationVariable> iterationVariables(PatternSyntax pattern) {
        List<LocalSymbol> locals = patternLocals(pattern);
        return locals.stream().map(local -> new IrIterationVariable(local,
                IrValueType.of(expressions.effectiveType(local).orElseThrow(
                        () -> new IllegalArgumentException("foreach local has no effective type: "
                                + local.name()))))).toList();
    }

    private IrStatement.Switch lowerSwitch(StatementSyntax.Switch selection) {
        List<IrSwitchSection> sections = selection.sections().stream().map(section ->
                new IrSwitchSection(section.labels().stream().map(this::lowerSwitchLabel).toList(),
                        lowerStatements(section.statements()))).toList();
        return new IrStatement.Switch(selection.span(), lowerExpression(selection.expression()), sections);
    }

    private IrSwitchLabel lowerSwitchLabel(AuxiliarySyntax.SwitchLabel label) {
        return new IrSwitchLabel(label.pattern() == null ? null : lowerPattern(label.pattern()),
                label.guard() == null ? null : lowerExpression(label.guard()));
    }

    private IrPattern lowerPattern(PatternSyntax pattern) {
        return new PatternLowerer(expressions, model, this::lowerExpression).lower(pattern);
    }

    private List<LocalSymbol> patternLocals(PatternSyntax pattern) {
        List<LocalSymbol> locals = new ArrayList<>();
        collectPatternLocals(pattern, locals);
        return locals;
    }

    private void collectPatternLocals(PatternSyntax pattern, List<LocalSymbol> locals) {
        switch (pattern) {
            case PatternSyntax.Var variable -> locals.add(patternLocal(variable));
            case PatternSyntax.Type typed -> {
                if (typed.name() != null) locals.add(patternLocal(typed));
            }
            case PatternSyntax.Recursive recursive -> {
                if (recursive.designation() != null) locals.add(patternLocal(recursive));
                recursive.positional().forEach(child -> collectPatternLocals(child, locals));
                recursive.properties().forEach(property -> collectPatternLocals(property.pattern(), locals));
            }
            case PatternSyntax.ListPattern list -> {
                if (list.designation() != null) locals.add(patternLocal(list));
                list.elements().forEach(child -> collectPatternLocals(child, locals));
            }
            case PatternSyntax.Slice slice -> {
                if (slice.pattern() != null) collectPatternLocals(slice.pattern(), locals);
            }
            case PatternSyntax.Binary binary -> {
                collectPatternLocals(binary.left(), locals);
                collectPatternLocals(binary.right(), locals);
            }
            case PatternSyntax.Not not -> collectPatternLocals(not.pattern(), locals);
            case PatternSyntax.Parenthesized parenthesized -> collectPatternLocals(parenthesized.pattern(),
                    locals);
            case PatternSyntax.Missing ignored -> { }
            case PatternSyntax.Discard ignored -> { }
            case PatternSyntax.Constant ignored -> { }
            case PatternSyntax.Relational ignored -> { }
        }
    }

    private LocalSymbol patternLocal(PatternSyntax pattern) {
        Symbol declared = model.declaredSymbol(pattern).orElseThrow(
                () -> new IllegalArgumentException("pattern variable has no symbol: " + pattern));
        if (declared instanceof LocalSymbol local) {
            return local;
        }
        throw new IllegalArgumentException("pattern declaration is not a local: " + declared);
    }

    private IrExpression lowerExpression(ExpressionSyntax syntax) {
        return SyntaxExpressionLowerer.lower(syntax, expressions, model);
    }

    private static IllegalArgumentException unsupported(StatementSyntax statement, String form) {
        return new IllegalArgumentException("cannot lower " + form + " statement at "
                + statement.span());
    }
}
