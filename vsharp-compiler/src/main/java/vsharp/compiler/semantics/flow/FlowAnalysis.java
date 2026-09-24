package vsharp.compiler.semantics.flow;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import vsharp.compiler.diagnostics.DiagnosticBag;
import vsharp.compiler.diagnostics.DiagnosticCode;
import vsharp.compiler.semantics.binding.BoundExpression;
import vsharp.compiler.semantics.binding.ExpressionBinding;
import vsharp.compiler.semantics.binding.SemanticModel;
import vsharp.compiler.semantics.constants.ConstantEvaluator;
import vsharp.compiler.semantics.constants.ConstantValue;
import vsharp.compiler.semantics.symbols.FunctionSymbol;
import vsharp.compiler.semantics.symbols.ParameterSymbol;
import vsharp.compiler.semantics.symbols.Scope;
import vsharp.compiler.semantics.symbols.Symbol;
import vsharp.compiler.semantics.types.BuiltinType;
import vsharp.compiler.semantics.types.TaskTypes;
import vsharp.compiler.semantics.types.TypeSymbol;
import vsharp.compiler.source.SourceFile;
import vsharp.compiler.source.SourceSpan;
import vsharp.compiler.syntax.AuxiliarySyntax;
import vsharp.compiler.syntax.DeclarationSyntax;
import vsharp.compiler.syntax.ExpressionSyntax;
import vsharp.compiler.syntax.PatternSyntax;
import vsharp.compiler.syntax.StatementSyntax;
import vsharp.compiler.syntax.SyntaxKind;

/// Definite-assignment and reachability analysis over a bound compilation unit.
///
/// The pass walks statement syntax rather than a bound statement tree — V# binds
/// expressions, not statements — and consults [ExpressionBinding] whenever it needs to know
/// which symbol an identifier denotes or whether a condition is a compile-time constant.
///
/// It implements C# §9.4: a variable is *definitely assigned* at a point when every path
/// reaching that point assigned it, joins intersect, and boolean operators propagate
/// separate "when true" and "when false" states so that `if (o is int n)` makes `n`
/// available in the true branch only. Loops follow the C# rule rather than a dataflow
/// fixpoint: a loop body is entered with the state before the loop, so an assignment in one
/// iteration never satisfies a read in the next.
///
/// Two deliberate deviations from Roslyn are:
///
///  1. `goto` label states converge by bounded iteration ([#GOTO_PASSES] silent passes
///     before the reporting pass) instead of an unbounded fixpoint. Convergence is
///     monotone — each pass can only shrink an assigned set — so the bound only risks
///     missing a diagnostic in pathological label graphs, never inventing one.
///  2. Label scope is the whole function body rather than the declaring block, so a `goto`
///     that C# would reject for jumping into a block is accepted here.
///
/// Local function bodies receive a second, call-site analysis for outer locals: reads see
/// the caller's definite-assignment state and assignments made on every normal exit flow
/// back to the caller. Recursive calls already on the analysis stack reuse their current
/// entry state, which keeps the walk bounded. The declaration-site pass still owns the
/// callable's internal diagnostics and reachability result, so a call does not duplicate
/// errors about locals declared inside the local function itself.
public final class FlowAnalysis {

    /// Silent iterations used to converge `goto` label entry states.
    private static final int GOTO_PASSES = 4;

    private FlowAnalysis() {
        throw new AssertionError("No instances");
    }

    /// Analyses every function body in `unit`, reporting flow diagnostics into `diagnostics`.
    public static FlowResult analyze(SourceFile file, AuxiliarySyntax.CompilationUnit unit,
            SemanticModel model, ExpressionBinding expressions, DiagnosticBag diagnostics) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(expressions, "expressions");
        Objects.requireNonNull(diagnostics, "diagnostics");
        return new Walker(file, model, expressions, diagnostics).walk(unit);
    }

    /// The two states a condition produces, per C# §9.4.4.
    private record Cond(FlowState whenTrue, FlowState whenFalse) {

        static Cond both(FlowState state) {
            return new Cond(state, state);
        }

        Cond swapped() {
            return new Cond(whenFalse, whenTrue);
        }
    }

    /// Where a `break` or `continue` lands.
    private static final class JumpTarget {

        private final boolean loop;

        private final List<FlowState> breaks = new ArrayList<>();

        private final List<FlowState> continues = new ArrayList<>();

        JumpTarget(boolean loop) {
            this.loop = loop;
        }
    }

    /// The labels a body declares and whether it contains any `goto`.
    private record LabelScan(Map<String, SourceSpan> labels, boolean hasGoto) {}

    private record PassResult(Map<String, FlowState> labels, FlowState normalExit) {}

    /// Per-body analysis state; one instance per pass over one function body.
    private static final class Frame {

        private final FunctionSymbol function;

        private final TypeSymbol returnType;

        private final SourceSpan span;

        private final boolean report;

        private final Map<String, FlowState> incoming;

        private final Map<String, SourceSpan> declaredLabels;

        private final Set<Symbol> tracked = new LinkedHashSet<>();

        private final List<ParameterSymbol> outParameters = new ArrayList<>();

        private final Set<Symbol> reportedOutParameters = new LinkedHashSet<>();

        private final List<FlowState> returns = new ArrayList<>();

        /// Non-null only for the extra local-function pass performed at an invocation.
        private final SourceSpan callSite;

        private final Set<Symbol> callSiteTracked;

        private final Set<Symbol> reportedCallSiteSymbols = new LinkedHashSet<>();

        private final Map<String, FlowState> labelStates = new LinkedHashMap<>();

        private final Set<String> usedLabels = new LinkedHashSet<>();

        Frame(FunctionSymbol function, TypeSymbol returnType, SourceSpan span, boolean report,
                Map<String, FlowState> incoming, Map<String, SourceSpan> declaredLabels,
                SourceSpan callSite, Set<Symbol> callSiteTracked) {
            this.function = function;
            this.returnType = returnType;
            this.span = span;
            this.report = report;
            this.incoming = incoming;
            this.declaredLabels = declaredLabels;
            this.callSite = callSite;
            this.callSiteTracked = Set.copyOf(callSiteTracked);
        }
    }

    private static final class Walker {

        private final SourceFile file;

        private final SemanticModel model;

        private final ExpressionBinding expressions;

        private final DiagnosticBag diagnostics;

        private final List<StatementSyntax> unreachable = new ArrayList<>();

        private final Map<FunctionSymbol, Boolean> endReachable = new LinkedHashMap<>();

        private final Map<FunctionSymbol, DeclarationSyntax.Method> localFunctions =
                new LinkedHashMap<>();

        private final Deque<FunctionSymbol> localCallStack = new ArrayDeque<>();

        private Frame frame;

        private Deque<JumpTarget> targets = new ArrayDeque<>();

        private boolean regionReported;

        Walker(SourceFile file, SemanticModel model, ExpressionBinding expressions,
                DiagnosticBag diagnostics) {
            this.file = file;
            this.model = model;
            this.expressions = expressions;
            this.diagnostics = diagnostics;
        }

        // ---- Traversal of declarations ---------------------------------------------

        FlowResult walk(AuxiliarySyntax.CompilationUnit unit) {
            Scope global = model.globalScope();
            unit.declarations().forEach(declaration -> declaration(declaration, global));
            if (!unit.statements().isEmpty()) {
                FunctionSymbol function = model.topLevelFunction().orElse(null);
                analyzeStatements(function, unit.statements(),
                        function == null ? unit.span() : function.location().span());
            }
            return new FlowResult(unreachable, endReachable);
        }

        private void declaration(DeclarationSyntax declaration, Scope enclosing) {
            switch (declaration) {
                case DeclarationSyntax.Namespace namespace -> {
                    Scope scope = model.scopeFor(namespace).orElse(enclosing);
                    namespace.declarations().forEach(member -> declaration(member, scope));
                    if (!namespace.statements().isEmpty()) {
                        FunctionSymbol function = synthesizedFunction(scope);
                        analyzeStatements(function, namespace.statements(),
                                function == null
                                        ? namespace.span() : function.location().span());
                    }
                }
                case DeclarationSyntax.StaticContainer container -> {
                    Scope scope = model.scopeFor(container).orElse(enclosing);
                    container.members().forEach(member -> declaration(member, scope));
                }
                case DeclarationSyntax.Struct structure -> {
                    Scope scope = model.scopeFor(structure).orElse(enclosing);
                    for (AuxiliarySyntax.Parameter parameter : structure.primaryParameters()) {
                        analyzeDetachedExpression(parameter.defaultValue());
                    }
                    structure.members().forEach(member -> declaration(member, scope));
                }
                case DeclarationSyntax.Enum enumeration -> {
                    for (AuxiliarySyntax.EnumMember member : enumeration.members()) {
                        analyzeDetachedExpression(member.value());
                    }
                }
                case DeclarationSyntax.Field field -> {
                    for (AuxiliarySyntax.VariableDeclarator variable : field.variables()) {
                        analyzeDetachedExpression(variable.initializer());
                    }
                }
                case DeclarationSyntax.Method method -> {
                    method.parameters()
                            .forEach(parameter -> analyzeDetachedExpression(
                                    parameter.defaultValue()));
                    analyzeCallable(declaration, method.body(), method.expressionBody());
                }
                case DeclarationSyntax.Operator operator ->
                        analyzeCallable(declaration, operator.body(), operator.expressionBody());
                case DeclarationSyntax.ConversionOperator conversion -> analyzeCallable(
                        declaration, conversion.body(), conversion.expressionBody());
                case DeclarationSyntax.Unsupported ignored -> {
                    // Excluded declarations were already diagnosed by the parser.
                }
            }
        }

        private static FunctionSymbol synthesizedFunction(Scope scope) {
            return scope.lookupLocal("<top-level>").stream()
                    .filter(FunctionSymbol.class::isInstance)
                    .map(FunctionSymbol.class::cast)
                    .filter(FunctionSymbol::synthesized)
                    .findFirst()
                    .orElse(null);
        }

        private void analyzeCallable(DeclarationSyntax declaration, StatementSyntax.Block body,
                ExpressionSyntax expressionBody) {
            Symbol declared = model.declaredSymbol(declaration).orElse(null);
            if (!(declared instanceof FunctionSymbol function)
                    || (body == null && expressionBody == null)) {
                return;
            }
            analyzeBody(function, bodyReturnType(function), function.location().span(),
                    body == null ? List.of() : body.statements(), expressionBody,
                    FlowState.start(), Set.of());
        }

        /// The type a `return` in this callable's body must produce.
        ///
        /// An async body returns the task's *result*, not the task: `async Task F()` is
        /// written with no `return` at all, and asking for the declared type here reported
        /// "not all code paths return a value" on every correct async body.
        private static TypeSymbol bodyReturnType(FunctionSymbol function) {
            if (function == null) {
                return BuiltinType.VOID;
            }
            return function.isAsync()
                    ? TaskTypes.bodyResultOf(function.returnType())
                    : function.returnType();
        }

        private void analyzeStatements(FunctionSymbol function, List<StatementSyntax> statements,
                SourceSpan span) {
            TypeSymbol returnType = bodyReturnType(function);
            analyzeBody(function, returnType, span, statements, null, FlowState.start(), Set.of());
        }

        /// Analyses an expression that belongs to no function body, so that lambdas inside
        /// field initialisers, enum member values and parameter defaults are still checked.
        private void analyzeDetachedExpression(ExpressionSyntax expression) {
            if (expression == null) {
                return;
            }
            analyzeBody(null, BuiltinType.VOID, expression.span(), List.of(), expression,
                    FlowState.start(), Set.of());
        }

        // ---- Body analysis ---------------------------------------------------------

        private FlowState analyzeBody(FunctionSymbol function, TypeSymbol returnType,
                SourceSpan span,
                List<StatementSyntax> statements, ExpressionSyntax expressionBody,
                FlowState initial, Set<Symbol> inheritedTracked) {
            return analyzeBody(function, returnType, span, statements, expressionBody,
                    initial, inheritedTracked, null);
        }

        private FlowState analyzeBody(FunctionSymbol function, TypeSymbol returnType,
                SourceSpan span,
                List<StatementSyntax> statements, ExpressionSyntax expressionBody,
                FlowState initial, Set<Symbol> inheritedTracked, SourceSpan callSite) {
            Frame outerFrame = frame;
            Deque<JumpTarget> outerTargets = targets;
            boolean outerRegion = regionReported;

            Map<String, SourceSpan> labels = new LinkedHashMap<>();
            boolean hasGoto = scanLabels(statements, labels);
            LabelScan scan = new LabelScan(labels, hasGoto);

            Map<String, FlowState> incoming = Map.of();
            if (scan.hasGoto()) {
                for (int pass = 0; pass < GOTO_PASSES; pass++) {
                    PassResult produced = runPass(function, returnType, span,
                            statements, expressionBody, initial, inheritedTracked, incoming,
                            scan, false, null);
                    if (produced.labels().equals(incoming)) {
                        break;
                    }
                    incoming = produced.labels();
                }
            }
            PassResult result = runPass(function, returnType, span, statements,
                    expressionBody, initial, inheritedTracked, incoming, scan,
                    callSite == null, callSite);

            frame = outerFrame;
            targets = outerTargets;
            regionReported = outerRegion;
            return result.normalExit();
        }

        private PassResult runPass(FunctionSymbol function, TypeSymbol returnType,
                SourceSpan span, List<StatementSyntax> statements,
                ExpressionSyntax expressionBody, FlowState initial,
                Set<Symbol> inheritedTracked, Map<String, FlowState> incoming, LabelScan scan,
                boolean report, SourceSpan callSite) {
            Frame current = new Frame(function, returnType, span, report, incoming,
                    scan.labels(), callSite, inheritedTracked);
            current.tracked.addAll(inheritedTracked);
            frame = current;
            targets = new ArrayDeque<>();
            regionReported = false;

            FlowState state = initial;
            if (function != null) {
                for (ParameterSymbol parameter : function.parameters()) {
                    if (parameter.modifiers().contains(SyntaxKind.OUT)) {
                        current.outParameters.add(parameter);
                        current.tracked.add(parameter);
                    } else {
                        state = state.assign(parameter);
                    }
                }
            }

            FlowState end;
            if (expressionBody != null) {
                FlowState value = expression(expressionBody, state);
                checkOutParameters(value, span);
                // An expression-bodied callable returns through the expression, not through
                // a `return` statement, so its value state must reach `current.returns` for
                // call-site analysis exactly like the `returnStatement` path does. Without
                // this, the callable's normal exit is `merge(empty)` (unreachable), and any
                // caller that uses such a call in a condition marks both branches - and
                // everything after the `if` - unreachable, which lowering then elides.
                current.returns.add(value);
                end = value.unreachable();
            } else {
                end = walkStatements(statements, state);
                if (end.reachable()) {
                    checkOutParameters(end, span);
                    if (requiresValue()) {
                        report(DiagnosticCode.NOT_ALL_PATHS_RETURN, span,
                                function == null ? "<top-level>" : function.name());
                    }
                }
            }

            if (report) {
                if (function != null) {
                    endReachable.put(function, end.reachable());
                }
                for (Map.Entry<String, SourceSpan> label : scan.labels().entrySet()) {
                    if (!current.usedLabels.contains(label.getKey())) {
                        report(DiagnosticCode.LABEL_NEVER_REFERENCED, label.getValue());
                    }
                }
            }
            List<FlowState> normalExits = new ArrayList<>(current.returns);
            if (end.reachable()) {
                normalExits.add(end);
            }
            return new PassResult(current.labelStates, FlowState.merge(normalExits));
        }

        private boolean requiresValue() {
            TypeSymbol returnType = frame.returnType;
            return frame.function != null
                    && returnType != BuiltinType.VOID
                    && returnType != TypeSymbol.Inferred.INSTANCE
                    && returnType != TypeSymbol.Error.INSTANCE;
        }

        private void checkOutParameters(FlowState state, SourceSpan span) {
            for (ParameterSymbol parameter : frame.outParameters) {
                if (state.isAssigned(parameter)
                        || !frame.reportedOutParameters.add(parameter)) {
                    continue;
                }
                report(DiagnosticCode.OUT_PARAMETER_NOT_ASSIGNED, span, parameter.name());
            }
        }

        // ---- Statements ------------------------------------------------------------

        private FlowState walkStatements(List<StatementSyntax> statements, FlowState state) {
            // Local functions are in scope throughout their declaration space, including
            // before their textual declaration, so register the complete nested set before
            // examining any invocation in this list.
            statements.forEach(this::registerLocalFunctions);
            for (StatementSyntax statement : statements) {
                state = visitStatement(statement, state);
            }
            return state;
        }

        private void registerLocalFunctions(StatementSyntax statement) {
            switch (statement) {
                case StatementSyntax.LocalFunction local -> {
                    Symbol declared = model.declaredSymbol(local).orElse(null);
                    if (declared instanceof FunctionSymbol function) {
                        localFunctions.put(function, local.declaration());
                    }
                    DeclarationSyntax.Method method = local.declaration();
                    if (method.body() != null) {
                        method.body().statements().forEach(this::registerLocalFunctions);
                    }
                }
                case StatementSyntax.Block block ->
                        block.statements().forEach(this::registerLocalFunctions);
                case StatementSyntax.If conditional -> {
                    registerLocalFunctions(conditional.whenTrue());
                    if (conditional.whenFalse() != null) {
                        registerLocalFunctions(conditional.whenFalse());
                    }
                }
                case StatementSyntax.While loop -> registerLocalFunctions(loop.body());
                case StatementSyntax.Do loop -> registerLocalFunctions(loop.body());
                case StatementSyntax.For loop -> {
                    loop.initializers().forEach(this::registerLocalFunctions);
                    registerLocalFunctions(loop.body());
                }
                case StatementSyntax.Foreach loop -> registerLocalFunctions(loop.body());
                case StatementSyntax.Switch selection -> selection.sections().forEach(section ->
                        section.statements().forEach(this::registerLocalFunctions));
                case StatementSyntax.Try attempt -> {
                    attempt.body().statements().forEach(this::registerLocalFunctions);
                    attempt.catches().forEach(clause -> clause.body().statements()
                            .forEach(this::registerLocalFunctions));
                    if (attempt.finallyBody() != null) {
                        attempt.finallyBody().statements()
                                .forEach(this::registerLocalFunctions);
                    }
                }
                case StatementSyntax.Using using -> {
                    if (using.body() != null) {
                        registerLocalFunctions(using.body());
                    }
                }
                case StatementSyntax.Lock locked -> registerLocalFunctions(locked.body());
                case StatementSyntax.Checked checked -> checked.body().statements()
                        .forEach(this::registerLocalFunctions);
                case StatementSyntax.Labeled labeled ->
                        registerLocalFunctions(labeled.statement());
                case StatementSyntax.Empty ignored -> { }
                case StatementSyntax.Expression ignored -> { }
                case StatementSyntax.LocalDeclaration ignored -> { }
                case StatementSyntax.Break ignored -> { }
                case StatementSyntax.Continue ignored -> { }
                case StatementSyntax.Return ignored -> { }
                case StatementSyntax.Throw ignored -> { }
                case StatementSyntax.Goto ignored -> { }
                case StatementSyntax.Yield ignored -> { }
            }
        }

        /// Visits one statement, first reporting it if control cannot reach it.
        ///
        /// A local function declaration is never unreachable: it is a declaration that other
        /// statements may call, not a step in the enclosing control flow.
        private FlowState visitStatement(StatementSyntax statement, FlowState state) {
            if (statement instanceof StatementSyntax.LocalFunction) {
                return statement(statement, state);
            }
            FlowState entry = labelEntry(statement, state);
            if (entry.reachable()) {
                regionReported = false;
            } else {
                markUnreachable(statement);
                if (!regionReported) {
                    regionReported = true;
                    report(DiagnosticCode.UNREACHABLE_CODE, statement.span());
                }
            }
            return statement(statement, entry);
        }

        private FlowState labelEntry(StatementSyntax statement, FlowState state) {
            if (!(statement instanceof StatementSyntax.Labeled labeled)) {
                return state;
            }
            FlowState incoming = frame.incoming.get(labeled.label());
            return incoming == null ? state : FlowState.merge(state, incoming);
        }

        private void markUnreachable(StatementSyntax statement) {
            if (frame.report) {
                unreachable.add(statement);
            }
        }

        private FlowState statement(StatementSyntax statement, FlowState state) {
            return switch (statement) {
                case StatementSyntax.Block block -> walkStatements(block.statements(), state);
                case StatementSyntax.Empty ignored -> state;
                case StatementSyntax.Expression expression ->
                        expression(expression.expression(), state);
                case StatementSyntax.LocalDeclaration local -> localDeclaration(local, state);
                case StatementSyntax.If conditional -> ifStatement(conditional, state);
                case StatementSyntax.While loop -> whileStatement(loop, state);
                case StatementSyntax.Do loop -> doStatement(loop, state);
                case StatementSyntax.For loop -> forStatement(loop, state);
                case StatementSyntax.Foreach loop -> foreachStatement(loop, state);
                case StatementSyntax.Switch selection -> switchStatement(selection, state);
                case StatementSyntax.Break ignored -> breakStatement(statement, state);
                case StatementSyntax.Continue ignored -> continueStatement(statement, state);
                case StatementSyntax.Return returned -> returnStatement(returned, state);
                case StatementSyntax.Throw thrown ->
                        visitOptional(thrown.expression(), state).unreachable();
                case StatementSyntax.Goto jump -> gotoStatement(jump, state);
                case StatementSyntax.Labeled labeled ->
                        visitStatement(labeled.statement(), labelEntry(labeled, state));
                case StatementSyntax.Try attempt -> tryStatement(attempt, state);
                case StatementSyntax.Using using -> usingStatement(using, state);
                case StatementSyntax.Lock lock -> visitStatement(lock.body(),
                        expression(lock.expression(), state));
                case StatementSyntax.Checked checked ->
                        walkStatements(checked.body().statements(), state);
                case StatementSyntax.Yield yielded -> {
                    FlowState next = visitOptional(yielded.expression(), state);
                    yield yielded.isBreak() ? next.unreachable() : next;
                }
                case StatementSyntax.LocalFunction local -> {
                    localFunction(local);
                    yield state;
                }
            };
        }

        private FlowState localDeclaration(StatementSyntax.LocalDeclaration local,
                FlowState state) {
            for (AuxiliarySyntax.VariableDeclarator variable : local.variables()) {
                if (variable.initializer() != null) {
                    state = expression(variable.initializer(), state);
                }
                if (variable.designation() != null) {
                    state = state.assignAll(patternSymbols(variable.designation()));
                    continue;
                }
                Symbol symbol = model.declaredSymbol(variable).orElse(null);
                if (symbol == null) {
                    continue;
                }
                if (variable.initializer() != null || local.constant() || local.using()) {
                    state = state.assign(symbol);
                } else {
                    frame.tracked.add(symbol);
                }
            }
            return state;
        }

        private FlowState ifStatement(StatementSyntax.If conditional, FlowState state) {
            Cond cond = condition(conditional.condition(), state);
            FlowState whenTrue = visitStatement(conditional.whenTrue(), cond.whenTrue());
            FlowState whenFalse = conditional.whenFalse() == null
                    ? cond.whenFalse()
                    : visitStatement(conditional.whenFalse(), cond.whenFalse());
            return FlowState.merge(whenTrue, whenFalse);
        }

        private FlowState whileStatement(StatementSyntax.While loop, FlowState state) {
            Cond cond = condition(loop.condition(), state);
            JumpTarget target = push(true);
            visitStatement(loop.body(), cond.whenTrue());
            pop();
            List<FlowState> exits = new ArrayList<>(target.breaks);
            exits.add(cond.whenFalse());
            return FlowState.merge(exits);
        }

        private FlowState doStatement(StatementSyntax.Do loop, FlowState state) {
            JumpTarget target = push(true);
            FlowState bodyEnd = visitStatement(loop.body(), state);
            pop();
            List<FlowState> beforeCondition = new ArrayList<>(target.continues);
            beforeCondition.add(bodyEnd);
            Cond cond = condition(loop.condition(), FlowState.merge(beforeCondition));
            List<FlowState> exits = new ArrayList<>(target.breaks);
            exits.add(cond.whenFalse());
            return FlowState.merge(exits);
        }

        private FlowState forStatement(StatementSyntax.For loop, FlowState state) {
            for (StatementSyntax initializer : loop.initializers()) {
                state = visitStatement(initializer, state);
            }
            Cond cond = loop.condition() == null
                    ? new Cond(state, state.unreachable())
                    : condition(loop.condition(), state);
            JumpTarget target = push(true);
            FlowState bodyEnd = visitStatement(loop.body(), cond.whenTrue());
            pop();
            List<FlowState> beforeIterators = new ArrayList<>(target.continues);
            beforeIterators.add(bodyEnd);
            FlowState iteration = FlowState.merge(beforeIterators);
            for (ExpressionSyntax iterator : loop.iterators()) {
                iteration = expression(iterator, iteration);
            }
            List<FlowState> exits = new ArrayList<>(target.breaks);
            exits.add(cond.whenFalse());
            return FlowState.merge(exits);
        }

        private FlowState foreachStatement(StatementSyntax.Foreach loop, FlowState state) {
            FlowState afterCollection = expression(loop.collection(), state);
            FlowState bodyEntry = afterCollection.assignAll(patternSymbols(loop.variable()));
            JumpTarget target = push(true);
            visitStatement(loop.body(), bodyEntry);
            pop();
            List<FlowState> exits = new ArrayList<>(target.breaks);
            exits.add(afterCollection);
            return FlowState.merge(exits);
        }

        private FlowState switchStatement(StatementSyntax.Switch selection, FlowState state) {
            FlowState governing = expression(selection.expression(), state);
            JumpTarget target = push(false);
            boolean hasDefault = false;
            for (AuxiliarySyntax.SwitchSection section : selection.sections()) {
                FlowState entry = governing;
                for (AuxiliarySyntax.SwitchLabel label : section.labels()) {
                    if (label.pattern() == null) {
                        hasDefault = true;
                    } else {
                        entry = patternReads(label.pattern(), entry);
                        entry = entry.assignAll(patternSymbols(label.pattern()));
                    }
                    if (label.guard() != null) {
                        entry = condition(label.guard(), entry).whenTrue();
                    }
                }
                regionReported = false;
                FlowState end = walkStatements(section.statements(), entry);
                if (end.reachable() && !section.statements().isEmpty()) {
                    report(DiagnosticCode.SWITCH_FALL_THROUGH, section.span());
                }
            }
            pop();
            List<FlowState> exits = new ArrayList<>(target.breaks);
            if (!hasDefault) {
                exits.add(governing);
            }
            return FlowState.merge(exits);
        }

        private FlowState breakStatement(StatementSyntax statement, FlowState state) {
            JumpTarget target = targets.peek();
            if (target == null) {
                report(DiagnosticCode.NO_ENCLOSING_LOOP, statement.span());
            } else {
                target.breaks.add(state);
            }
            return state.unreachable();
        }

        private FlowState continueStatement(StatementSyntax statement, FlowState state) {
            JumpTarget target = null;
            for (JumpTarget candidate : targets) {
                if (candidate.loop) {
                    target = candidate;
                    break;
                }
            }
            if (target == null) {
                report(DiagnosticCode.NO_ENCLOSING_LOOP, statement.span());
            } else {
                target.continues.add(state);
            }
            return state.unreachable();
        }

        private FlowState returnStatement(StatementSyntax.Return returned, FlowState state) {
            if (returned.expression() != null) {
                state = expression(returned.expression(), state);
                if (frame.function != null && frame.returnType == BuiltinType.VOID) {
                    report(DiagnosticCode.RETURN_VALUE_IN_VOID_FUNCTION, returned.span(),
                            frame.function.name());
                }
            } else if (requiresValue()) {
                report(DiagnosticCode.RETURN_VALUE_REQUIRED, returned.span(),
                        frame.returnType.displayName());
            }
            checkOutParameters(state, returned.span());
            frame.returns.add(state);
            return state.unreachable();
        }

        private FlowState gotoStatement(StatementSyntax.Goto jump, FlowState state) {
            state = visitOptional(jump.value(), state);
            if (jump.kind() == StatementSyntax.Goto.Kind.LABEL && jump.label() != null) {
                frame.usedLabels.add(jump.label());
                if (!frame.declaredLabels.containsKey(jump.label())) {
                    report(DiagnosticCode.LABEL_NOT_FOUND, jump.span(), jump.label());
                } else {
                    FlowState existing = frame.labelStates.get(jump.label());
                    frame.labelStates.put(jump.label(),
                            existing == null ? state : FlowState.merge(existing, state));
                }
            }
            return state.unreachable();
        }

        private FlowState tryStatement(StatementSyntax.Try attempt, FlowState state) {
            FlowState entry = state;
            List<FlowState> normal = new ArrayList<>();
            normal.add(walkStatements(attempt.body().statements(), entry));
            for (AuxiliarySyntax.CatchClause clause : attempt.catches()) {
                FlowState catchEntry = entry;
                Symbol declared = model.declaredSymbol(clause).orElse(null);
                if (declared != null) {
                    catchEntry = catchEntry.assign(declared);
                }
                if (clause.filter() != null) {
                    catchEntry = condition(clause.filter(), catchEntry).whenTrue();
                }
                regionReported = false;
                normal.add(walkStatements(clause.body().statements(), catchEntry));
            }
            FlowState result = FlowState.merge(normal);
            if (attempt.finallyBody() != null) {
                regionReported = false;
                FlowState finallyEnd = walkStatements(attempt.finallyBody().statements(), entry);
                Set<Symbol> gained = new LinkedHashSet<>(finallyEnd.assigned());
                gained.removeAll(entry.assigned());
                result = result.assignAll(gained);
                if (!finallyEnd.reachable()) {
                    result = result.unreachable();
                }
            }
            return result;
        }

        private FlowState usingStatement(StatementSyntax.Using using, FlowState state) {
            switch (using.resource()) {
                case StatementSyntax.LocalDeclaration declaration ->
                        state = localDeclaration(declaration, state);
                case ExpressionSyntax resource -> state = expression(resource, state);
                default -> {
                    // A malformed resource was already diagnosed by the parser.
                }
            }
            return using.body() == null ? state : visitStatement(using.body(), state);
        }

        private void localFunction(StatementSyntax.LocalFunction local) {
            if (!frame.report) {
                return;
            }
            Symbol declared = model.declaredSymbol(local).orElse(null);
            DeclarationSyntax.Method method = local.declaration();
            if (!(declared instanceof FunctionSymbol function)
                    || (method.body() == null && method.expressionBody() == null)) {
                return;
            }
            analyzeBody(function, function.returnType(), function.location().span(),
                    method.body() == null ? List.of() : method.body().statements(),
                    method.expressionBody(), FlowState.start(), Set.of());
        }

        // ---- Conditions ------------------------------------------------------------

        private Cond condition(ExpressionSyntax syntax, FlowState state) {
            Cond special = switch (syntax) {
                case ExpressionSyntax.Parenthesized parenthesized ->
                        condition(parenthesized.expression(), state);
                case ExpressionSyntax.Unary unary ->
                        unary.operator() == SyntaxKind.EXCLAMATION
                                ? condition(unary.operand(), state).swapped() : null;
                case ExpressionSyntax.Binary binary -> logicalCondition(binary, state);
                case ExpressionSyntax.IsPattern isPattern -> {
                    FlowState after = patternReads(isPattern.pattern(),
                            expression(isPattern.expression(), state));
                    yield new Cond(after.assignAll(patternSymbols(isPattern.pattern())), after);
                }
                default -> null;
            };
            if (special != null) {
                return special;
            }
            FlowState after = expression(syntax, state);
            java.lang.Boolean constant = constantCondition(syntax);
            if (constant == null) {
                return Cond.both(after);
            }
            return constant
                    ? new Cond(after, after.unreachable())
                    : new Cond(after.unreachable(), after);
        }

        private Cond logicalCondition(ExpressionSyntax.Binary binary, FlowState state) {
            if (binary.operator() == SyntaxKind.AMPERSAND_AMPERSAND) {
                Cond left = condition(binary.left(), state);
                Cond right = condition(binary.right(), left.whenTrue());
                return new Cond(right.whenTrue(),
                        FlowState.merge(left.whenFalse(), right.whenFalse()));
            }
            if (binary.operator() == SyntaxKind.BAR_BAR) {
                Cond left = condition(binary.left(), state);
                Cond right = condition(binary.right(), left.whenFalse());
                return new Cond(FlowState.merge(left.whenTrue(), right.whenTrue()),
                        right.whenFalse());
            }
            return null;
        }

        private java.lang.Boolean constantCondition(ExpressionSyntax syntax) {
            BoundExpression bound = expressions.expressionFor(syntax).orElse(null);
            if (bound == null) {
                return null;
            }
            return ConstantEvaluator.evaluate(bound, false)
                    instanceof ConstantEvaluator.Result.Value value
                    && value.value() instanceof ConstantValue.Boolean truth
                    ? truth.value() : null;
        }

        // ---- Expressions -----------------------------------------------------------

        private FlowState visitOptional(ExpressionSyntax syntax, FlowState state) {
            return syntax == null ? state : expression(syntax, state);
        }

        private FlowState expression(ExpressionSyntax syntax, FlowState state) {
            return switch (syntax) {
                case ExpressionSyntax.Identifier identifier -> {
                    checkRead(identifier, state);
                    yield state;
                }
                case ExpressionSyntax.Assignment assignment -> assignment(assignment, state);
                case ExpressionSyntax.Invocation invocation -> localFunctionInvocation(
                        invocation, arguments(invocation.arguments(),
                                expression(invocation.target(), state)));
                case ExpressionSyntax.ElementAccess element -> arguments(element.arguments(),
                        expression(element.receiver(), state));
                case ExpressionSyntax.MemberAccess member ->
                        expression(member.receiver(), state);
                case ExpressionSyntax.Parenthesized parenthesized ->
                        expression(parenthesized.expression(), state);
                // `await` reads its operand and nothing else: it starts no work, branches
                // nowhere and always completes or throws, so definite assignment flows
                // straight through it.
                case ExpressionSyntax.Await await -> expression(await.operand(), state);
                case ExpressionSyntax.Binary binary -> binary(binary, state);
                case ExpressionSyntax.Unary unary -> mutating(unary.operator())
                        ? assignTo(unary.operand(), expression(unary.operand(), state))
                        : expression(unary.operand(), state);
                case ExpressionSyntax.Postfix postfix ->
                        assignTo(postfix.operand(), expression(postfix.operand(), state));
                case ExpressionSyntax.Conditional conditional -> {
                    Cond cond = condition(conditional.condition(), state);
                    yield FlowState.merge(expression(conditional.whenTrue(), cond.whenTrue()),
                            expression(conditional.whenFalse(), cond.whenFalse()));
                }
                case ExpressionSyntax.Tuple tuple -> {
                    for (ExpressionSyntax element : tuple.elements()) {
                        state = expression(element, state);
                    }
                    yield state;
                }
                case ExpressionSyntax.IsPattern isPattern -> patternReads(isPattern.pattern(),
                        expression(isPattern.expression(), state));
                case ExpressionSyntax.Switch switched -> switchExpression(switched, state);
                case ExpressionSyntax.Lambda lambda -> {
                    lambda(lambda, state);
                    yield state;
                }
                case ExpressionSyntax.Cast cast -> expression(cast.expression(), state);
                case ExpressionSyntax.Checked checked -> expression(checked.expression(), state);
                case ExpressionSyntax.As as -> expression(as.expression(), state);
                case ExpressionSyntax.Throw thrown ->
                        expression(thrown.expression(), state).unreachable();
                case ExpressionSyntax.Range range ->
                        visitOptional(range.end(), visitOptional(range.start(), state));
                case ExpressionSyntax.Collection collection -> {
                    for (AuxiliarySyntax.CollectionElement element : collection.elements()) {
                        state = expression(element.expression(), state);
                    }
                    yield state;
                }
                case ExpressionSyntax.Interpolated interpolated -> {
                    for (AuxiliarySyntax.InterpolationElement element
                            : interpolated.elements()) {
                        if (element instanceof AuxiliarySyntax.InterpolationElement.Hole hole) {
                            state = visitOptional(hole.alignment(),
                                    expression(hole.expression(), state));
                        }
                    }
                    yield state;
                }
                case ExpressionSyntax.ArrayCreation array -> {
                    for (ExpressionSyntax dimension : array.dimensions()) {
                        state = expression(dimension, state);
                    }
                    for (ExpressionSyntax element : array.initializer()) {
                        state = expression(element, state);
                    }
                    yield state;
                }
                case ExpressionSyntax.ObjectCreation creation ->
                        arguments(creation.arguments(), state);
                case ExpressionSyntax.ArrayInitializer initializer -> {
                    for (ExpressionSyntax element : initializer.elements()) {
                        state = expression(element, state);
                    }
                    yield state;
                }
                case ExpressionSyntax.With with -> {
                    state = expression(with.receiver(), state);
                    for (AuxiliarySyntax.VariableDeclarator initializer : with.initializers()) {
                        state = visitOptional(initializer.initializer(), state);
                    }
                    yield state;
                }
                // `nameof` never reads its operand, and the remaining forms hold no
                // expression that could read or assign a variable.
                case ExpressionSyntax.NameOf ignored -> state;
                case ExpressionSyntax.Declaration ignored -> state;
                case ExpressionSyntax.PredefinedType ignored -> state;
                case ExpressionSyntax.Literal ignored -> state;
                case ExpressionSyntax.Default ignored -> state;
                case ExpressionSyntax.TypeOperator ignored -> state;
                case ExpressionSyntax.Missing ignored -> state;
                case ExpressionSyntax.Unsupported ignored -> state;
            };
        }

        private static boolean mutating(SyntaxKind operator) {
            return operator == SyntaxKind.PLUS_PLUS || operator == SyntaxKind.MINUS_MINUS;
        }

        private FlowState binary(ExpressionSyntax.Binary binary, FlowState state) {
            Cond logical = logicalCondition(binary, state);
            if (logical != null) {
                return FlowState.merge(logical.whenTrue(), logical.whenFalse());
            }
            FlowState afterLeft = expression(binary.left(), state);
            if (binary.operator() == SyntaxKind.QUESTION_QUESTION) {
                return FlowState.merge(afterLeft, expression(binary.right(), afterLeft));
            }
            return expression(binary.right(), afterLeft);
        }

        private FlowState switchExpression(ExpressionSyntax.Switch switched, FlowState state) {
            FlowState governing = expression(switched.governing(), state);
            List<FlowState> results = new ArrayList<>();
            for (AuxiliarySyntax.SwitchExpressionArm arm : switched.arms()) {
                FlowState armState = patternReads(arm.pattern(), governing)
                        .assignAll(patternSymbols(arm.pattern()));
                if (arm.guard() != null) {
                    armState = condition(arm.guard(), armState).whenTrue();
                }
                results.add(expression(arm.expression(), armState));
            }
            return results.isEmpty() ? governing : FlowState.merge(results);
        }

        private FlowState assignment(ExpressionSyntax.Assignment assignment, FlowState state) {
            boolean simple = assignment.operator() == SyntaxKind.EQUALS;
            List<Symbol> written = new ArrayList<>();
            state = target(assignment.target(), simple, state, written);
            state = expression(assignment.value(), state);
            return state.assignAll(written);
        }

        /// Walks an assignment target, collecting the variables it writes.
        ///
        /// A compound assignment reads its target first, so only a simple `=` suppresses the
        /// definite-assignment check on the target itself.
        private FlowState target(ExpressionSyntax syntax, boolean simple, FlowState state,
                List<Symbol> written) {
            switch (syntax) {
                case ExpressionSyntax.Tuple tuple -> {
                    for (ExpressionSyntax element : tuple.elements()) {
                        state = target(element, simple, state, written);
                    }
                }
                case ExpressionSyntax.Declaration declaration ->
                        written.addAll(patternSymbols(declaration.designation()));
                case ExpressionSyntax.Identifier identifier -> {
                    Symbol symbol = boundSymbol(identifier);
                    if (symbol != null) {
                        if (!simple) {
                            checkRead(identifier, state);
                        }
                        written.add(symbol);
                    }
                }
                default -> state = expression(syntax, state);
            }
            return state;
        }

        /// Marks a `++`/`--` operand as written after it has been read.
        private FlowState assignTo(ExpressionSyntax syntax, FlowState state) {
            Symbol symbol = syntax instanceof ExpressionSyntax.Identifier identifier
                    ? boundSymbol(identifier) : null;
            return symbol == null ? state : state.assign(symbol);
        }

        private FlowState arguments(List<AuxiliarySyntax.Argument> arguments, FlowState state) {
            List<Symbol> written = new ArrayList<>();
            for (AuxiliarySyntax.Argument argument : arguments) {
                if (argument.modifier() == SyntaxKind.OUT) {
                    state = target(argument.expression(), true, state, written);
                } else {
                    state = expression(argument.expression(), state);
                }
            }
            return state.assignAll(written);
        }

        private FlowState localFunctionInvocation(ExpressionSyntax.Invocation invocation,
                FlowState state) {
            BoundExpression bound = expressions.expressionFor(invocation).orElse(null);
            if (!(bound instanceof BoundExpression.Call call)
                    || !call.declaration().localFunction()) {
                return state;
            }
            FunctionSymbol function = call.declaration();
            DeclarationSyntax.Method method = localFunctions.get(function);
            if (method == null || localCallStack.contains(function)) {
                // The declaration-site pass still validates the recursive callable's own
                // body. Re-entering it here would recurse forever; the current symbolic
                // state is the conservative transfer for that recursive edge.
                return state;
            }

            Set<Symbol> callerTracked = new LinkedHashSet<>(frame.tracked);
            localCallStack.push(function);
            FlowState exit;
            try {
                exit = analyzeBody(function, function.returnType(), function.location().span(),
                        method.body() == null ? List.of() : method.body().statements(),
                        method.expressionBody(), state, callerTracked, invocation.span());
            } finally {
                localCallStack.pop();
            }

            if (!exit.reachable()) {
                return state.unreachable();
            }
            List<Symbol> assignedByCall = new ArrayList<>();
            for (Symbol symbol : callerTracked) {
                if (exit.isAssigned(symbol)) {
                    assignedByCall.add(symbol);
                }
            }
            return state.assignAll(assignedByCall);
        }

        private void lambda(ExpressionSyntax.Lambda lambda, FlowState state) {
            if (!frame.report) {
                return;
            }
            Symbol declared = model.declaredSymbol(lambda).orElse(null);
            if (!(declared instanceof FunctionSymbol function)) {
                return;
            }
            // The binder replaced the declaration-time callable with one carrying the target's
            // concrete signature, and that is the symbol lowering asks about end-point
            // reachability, so results are recorded against it rather than the
            // placeholder the declaration walk made.
            function = expressions.expressionFor(lambda).orElse(null)
                    instanceof BoundExpression.Lambda bound ? bound.function() : function;
            List<StatementSyntax> statements = lambda.body() instanceof StatementSyntax.Block block
                    ? block.statements() : List.of();
            ExpressionSyntax expressionBody = lambda.body() instanceof ExpressionSyntax body
                    ? body : null;
            analyzeBody(function, function.returnType(), lambda.span(), statements,
                    expressionBody, state, frame.tracked);
        }

        // ---- Symbols and patterns --------------------------------------------------

        private Symbol boundSymbol(ExpressionSyntax syntax) {
            return expressions.expressionFor(syntax).orElse(null)
                    instanceof BoundExpression.Value value ? value.symbol() : null;
        }

        private void checkRead(ExpressionSyntax.Identifier identifier, FlowState state) {
            Symbol symbol = boundSymbol(identifier);
            if (frame.callSite != null) {
                if (symbol != null && frame.callSiteTracked.contains(symbol)
                        && !state.isAssigned(symbol)
                        && frame.reportedCallSiteSymbols.add(symbol)) {
                    DiagnosticCode code = symbol instanceof ParameterSymbol
                            ? DiagnosticCode.UNASSIGNED_OUT_PARAMETER_USE
                            : DiagnosticCode.UNASSIGNED_LOCAL_USE;
                    diagnostics.report(code, file, frame.callSite, symbol.name());
                }
                return;
            }
            if (symbol == null || !frame.tracked.contains(symbol) || state.isAssigned(symbol)) {
                return;
            }
            DiagnosticCode code = symbol instanceof ParameterSymbol
                    ? DiagnosticCode.UNASSIGNED_OUT_PARAMETER_USE
                    : DiagnosticCode.UNASSIGNED_LOCAL_USE;
            report(code, identifier.span(), symbol.name());
        }

        /// The locals a pattern declares, including nested designations.
        private List<Symbol> patternSymbols(PatternSyntax pattern) {
            List<Symbol> result = new ArrayList<>();
            collectPatternSymbols(pattern, result);
            return result;
        }

        private void collectPatternSymbols(PatternSyntax pattern, List<Symbol> result) {
            if (pattern == null) {
                return;
            }
            model.declaredSymbol(pattern).ifPresent(result::add);
            switch (pattern) {
                case PatternSyntax.Recursive recursive -> {
                    recursive.positional()
                            .forEach(child -> collectPatternSymbols(child, result));
                    recursive.properties().forEach(property ->
                            collectPatternSymbols(property.pattern(), result));
                }
                case PatternSyntax.ListPattern list ->
                        list.elements().forEach(child -> collectPatternSymbols(child, result));
                case PatternSyntax.Slice slice -> collectPatternSymbols(slice.pattern(), result);
                case PatternSyntax.Binary binary -> {
                    collectPatternSymbols(binary.left(), result);
                    collectPatternSymbols(binary.right(), result);
                }
                case PatternSyntax.Not not -> collectPatternSymbols(not.pattern(), result);
                case PatternSyntax.Parenthesized parenthesized ->
                        collectPatternSymbols(parenthesized.pattern(), result);
                case PatternSyntax.Var ignored -> {
                }
                case PatternSyntax.Type ignored -> {
                }
                case PatternSyntax.Constant ignored -> {
                }
                case PatternSyntax.Relational ignored -> {
                }
                case PatternSyntax.Discard ignored -> {
                }
                case PatternSyntax.Missing ignored -> {
                }
            }
        }

        /// Walks the expressions a pattern evaluates, such as `case > limit`.
        private FlowState patternReads(PatternSyntax pattern, FlowState state) {
            if (pattern == null) {
                return state;
            }
            return switch (pattern) {
                case PatternSyntax.Constant constant ->
                        expression(constant.expression(), state);
                case PatternSyntax.Relational relational ->
                        expression(relational.value(), state);
                case PatternSyntax.Recursive recursive -> {
                    for (PatternSyntax child : recursive.positional()) {
                        state = patternReads(child, state);
                    }
                    for (AuxiliarySyntax.PropertySubpattern property : recursive.properties()) {
                        state = patternReads(property.pattern(), state);
                    }
                    yield state;
                }
                case PatternSyntax.ListPattern list -> {
                    for (PatternSyntax child : list.elements()) {
                        state = patternReads(child, state);
                    }
                    yield state;
                }
                case PatternSyntax.Slice slice -> patternReads(slice.pattern(), state);
                case PatternSyntax.Binary binary ->
                        patternReads(binary.right(), patternReads(binary.left(), state));
                case PatternSyntax.Not not -> patternReads(not.pattern(), state);
                case PatternSyntax.Parenthesized parenthesized ->
                        patternReads(parenthesized.pattern(), state);
                case PatternSyntax.Var ignored -> state;
                case PatternSyntax.Type ignored -> state;
                case PatternSyntax.Discard ignored -> state;
                case PatternSyntax.Missing ignored -> state;
            };
        }

        // ---- Labels ----------------------------------------------------------------

        /// Records every label the body declares; answers whether it contains any `goto`.
        ///
        /// Nested local functions are skipped: their labels belong to their own bodies.
        private boolean scanLabels(List<StatementSyntax> statements,
                Map<String, SourceSpan> labels) {
            boolean hasGoto = false;
            for (StatementSyntax statement : statements) {
                hasGoto |= scanLabels(statement, labels);
            }
            return hasGoto;
        }

        private boolean scanLabels(StatementSyntax statement, Map<String, SourceSpan> labels) {
            return switch (statement) {
                case StatementSyntax.Labeled labeled -> {
                    labels.putIfAbsent(labeled.label(), labeled.span());
                    yield scanLabels(labeled.statement(), labels);
                }
                case StatementSyntax.Goto ignored -> true;
                case StatementSyntax.Block block -> scanLabels(block.statements(), labels);
                case StatementSyntax.If conditional -> scanLabels(conditional.whenTrue(), labels)
                        | (conditional.whenFalse() != null
                                && scanLabels(conditional.whenFalse(), labels));
                case StatementSyntax.While loop -> scanLabels(loop.body(), labels);
                case StatementSyntax.Do loop -> scanLabels(loop.body(), labels);
                case StatementSyntax.For loop ->
                        scanLabels(loop.initializers(), labels) | scanLabels(loop.body(), labels);
                case StatementSyntax.Foreach loop -> scanLabels(loop.body(), labels);
                case StatementSyntax.Using using -> using.body() != null
                        && scanLabels(using.body(), labels);
                case StatementSyntax.Lock lock -> scanLabels(lock.body(), labels);
                case StatementSyntax.Checked checked ->
                        scanLabels(checked.body().statements(), labels);
                case StatementSyntax.Switch selection -> {
                    boolean hasGoto = false;
                    for (AuxiliarySyntax.SwitchSection section : selection.sections()) {
                        hasGoto |= scanLabels(section.statements(), labels);
                    }
                    yield hasGoto;
                }
                case StatementSyntax.Try attempt -> {
                    boolean hasGoto = scanLabels(attempt.body().statements(), labels);
                    for (AuxiliarySyntax.CatchClause clause : attempt.catches()) {
                        hasGoto |= scanLabels(clause.body().statements(), labels);
                    }
                    if (attempt.finallyBody() != null) {
                        hasGoto |= scanLabels(attempt.finallyBody().statements(), labels);
                    }
                    yield hasGoto;
                }
                case StatementSyntax.Empty ignored -> false;
                case StatementSyntax.Expression ignored -> false;
                case StatementSyntax.LocalDeclaration ignored -> false;
                case StatementSyntax.Break ignored -> false;
                case StatementSyntax.Continue ignored -> false;
                case StatementSyntax.Return ignored -> false;
                case StatementSyntax.Throw ignored -> false;
                case StatementSyntax.Yield ignored -> false;
                case StatementSyntax.LocalFunction ignored -> false;
            };
        }

        // ---- Plumbing --------------------------------------------------------------

        private JumpTarget push(boolean loop) {
            JumpTarget target = new JumpTarget(loop);
            targets.push(target);
            return target;
        }

        private void pop() {
            targets.pop();
        }

        private void report(DiagnosticCode code, SourceSpan span, Object... arguments) {
            if (frame.report) {
                diagnostics.report(code, file, span, arguments);
            }
        }
    }
}
