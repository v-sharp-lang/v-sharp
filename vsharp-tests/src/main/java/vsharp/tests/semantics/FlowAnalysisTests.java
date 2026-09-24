package vsharp.tests.semantics;

import java.util.List;
import vsharp.compiler.diagnostics.Diagnostic;
import vsharp.compiler.diagnostics.DiagnosticBag;
import vsharp.compiler.semantics.binding.DeclarationBinder;
import vsharp.compiler.semantics.binding.ExpressionBinder;
import vsharp.compiler.semantics.binding.ExpressionBinding;
import vsharp.compiler.semantics.binding.SemanticModel;
import vsharp.compiler.semantics.flow.FlowAnalysis;
import vsharp.compiler.semantics.flow.FlowResult;
import vsharp.compiler.semantics.symbols.FunctionSymbol;
import vsharp.compiler.semantics.symbols.Symbol;
import vsharp.compiler.source.SourceFile;
import vsharp.compiler.syntax.AuxiliarySyntax;
import vsharp.compiler.syntax.Parser;
import vsharp.testkit.Assert;
import vsharp.testkit.TestRegistry;
import vsharp.testkit.TestSuite;

/// Definite-assignment and reachability coverage for [FlowAnalysis].
///
/// Every case binds a complete source file, asserts that binding itself is clean, and then
/// inspects only the diagnostics the flow pass adds — so a regression in an earlier stage
/// fails loudly instead of masquerading as a flow result.
public final class FlowAnalysisTests implements TestSuite {

    @Override
    public String suiteName() {
        return "semantic.flow";
    }

    @Override
    public void register(TestRegistry registry) {
        registerDefiniteAssignment(registry);
        registerConditions(registry);
        registerLoops(registry);
        registerJumps(registry);
        registerReturns(registry);
        registerNestedFunctions(registry);
        registerResults(registry);
    }

    // ---- Definite assignment -------------------------------------------------------

    private static void registerDefiniteAssignment(TestRegistry registry) {
        registry.test("reading a local before any assignment is rejected", () -> {
            Assert.equalList(List.of("VS0165"), flow("""
                static class C {
                    static void F() {
                        int x;
                        int y = x;
                    }
                }
                """), "unassigned local diagnostics");
        });

        registry.test("a local assigned on every path may be read", () -> {
            Assert.equalList(List.of(), flow("""
                static class C {
                    static void F(bool c) {
                        int x;
                        if (c) {
                            x = 1;
                        } else {
                            x = 2;
                        }
                        int y = x;
                    }
                }
                """), "fully assigned local diagnostics");
        });

        registry.test("a local assigned on only one path may not be read", () -> {
            Assert.equalList(List.of("VS0165"), flow("""
                static class C {
                    static void F(bool c) {
                        int x;
                        if (c) {
                            x = 1;
                        }
                        int y = x;
                    }
                }
                """), "partially assigned local diagnostics");
        });

        registry.test("a branch that leaves the function contributes nothing to the join", () -> {
            Assert.equalList(List.of(), flow("""
                static class C {
                    static void F(bool c) {
                        int x;
                        if (c) {
                            x = 1;
                        } else {
                            return;
                        }
                        int y = x;
                    }
                }
                """), "unreachable-join diagnostics");
        });

        registry.test("an assignment cannot use the variable it is assigning", () -> {
            Assert.equalList(List.of("VS0165"), flow("""
                static class C {
                    static void F() {
                        int x;
                        x = x + 1;
                    }
                }
                """), "self-referential assignment diagnostics");
        });

        registry.test("a compound assignment reads its target first", () -> {
            Assert.equalList(List.of("VS0165"), flow("""
                static class C {
                    static void F() {
                        int x;
                        x += 1;
                    }
                }
                """), "compound assignment diagnostics");
        });

        registry.test("an out argument assigns its variable", () -> {
            Assert.equalList(List.of(), flow("""
                static class C {
                    static bool Set(out int v) {
                        v = 1;
                        return true;
                    }

                    static void F() {
                        int x;
                        Set(out x);
                        int y = x;
                    }
                }
                """), "out-argument assignment diagnostics");
        });

        registry.test("a ref argument still requires prior assignment", () -> {
            Assert.equalList(List.of("VS0165"), flow("""
                static class C {
                    static void Bump(ref int v) {
                        v = v + 1;
                    }

                    static void F() {
                        int x;
                        Bump(ref x);
                    }
                }
                """), "ref-argument diagnostics");
        });

        registry.test("nameof does not read its operand", () -> {
            Assert.equalList(List.of(), flow("""
                static class C {
                    static void F() {
                        int x;
                        string name = nameof(x);
                    }
                }
                """), "nameof diagnostics");
        });

        registry.test("a finally block propagates its assignments", () -> {
            Assert.equalList(List.of(), flow("""
                static class C {
                    static void F() {
                        int x;
                        try {
                            x = 1;
                        } finally {
                            x = 2;
                        }
                        int y = x;
                    }
                }
                """), "finally propagation diagnostics");
        });

        registry.test("a try body assignment does not survive its catch", () -> {
            Assert.equalList(List.of("VS0165"), flow("""
                using java.lang;
                static class C {
                    static void F() {
                        int x;
                        try {
                            x = 1;
                        } catch (Exception e) {
                        }
                        int y = x;
                    }
                }
                """), "try/catch join diagnostics");
        });
    }

    // ---- Conditions ----------------------------------------------------------------

    private static void registerConditions(TestRegistry registry) {
        registry.test("&& carries the left operand's true state into the right", () -> {
            Assert.equalList(List.of(), flow("""
                static class C {
                    static bool Set(out int v) {
                        v = 1;
                        return true;
                    }

                    static void F(bool c) {
                        int x;
                        if (Set(out x) && x > 0) {
                            int y = x;
                        }
                    }
                }
                """), "&& propagation diagnostics");
        });

        registry.test("|| leaves the right operand's assignments conditional", () -> {
            Assert.equalList(List.of("VS0165"), flow("""
                static class C {
                    static bool Set(out int v) {
                        v = 1;
                        return true;
                    }

                    static void F(bool c) {
                        int x;
                        if (c || Set(out x)) {
                            int y = x;
                        }
                    }
                }
                """), "|| propagation diagnostics");
        });

        registry.test("! swaps the true and false states", () -> {
            Assert.equalList(List.of(), flow("""
                static class C {
                    static bool Set(out int v) {
                        v = 1;
                        return true;
                    }

                    static void F() {
                        int x;
                        if (!Set(out x)) {
                            int y = x;
                        }
                    }
                }
                """), "negation diagnostics");
        });

        registry.test("a conditional expression splits its branches", () -> {
            Assert.equalList(List.of("VS0165"), flow("""
                static class C {
                    static bool Set(out int v) {
                        v = 1;
                        return true;
                    }

                    static void F(bool c) {
                        int x;
                        int y = c ? 0 : (Set(out x) ? x : 0);
                        int z = x;
                    }
                }
                """), "conditional expression diagnostics");
        });

        registry.test("a switch statement with a default merges all sections", () -> {
            Assert.equalList(List.of(), flow("""
                static class C {
                    static void F(int v) {
                        int x;
                        switch (v) {
                            case 1:
                                x = 1;
                                break;
                            default:
                                x = 2;
                                break;
                        }
                        int y = x;
                    }
                }
                """), "switch join diagnostics");
        });

        registry.test("a switch statement without a default may fall past every case", () -> {
            Assert.equalList(List.of("VS0165"), flow("""
                static class C {
                    static void F(int v) {
                        int x;
                        switch (v) {
                            case 1:
                                x = 1;
                                break;
                        }
                        int y = x;
                    }
                }
                """), "switch fall-past diagnostics");
        });

        registry.test("a switch section may not fall through to the next", () -> {
            Assert.equalList(List.of("VS0163"), flow("""
                static class C {
                    static void F(int v) {
                        int x = 0;
                        switch (v) {
                            case 1:
                                x = 1;
                            case 2:
                                x = 2;
                                break;
                        }
                    }
                }
                """), "fall-through diagnostics");
        });
    }

    // ---- Loops ---------------------------------------------------------------------

    private static void registerLoops(TestRegistry registry) {
        registry.test("a while body may never run", () -> {
            Assert.equalList(List.of("VS0165"), flow("""
                static class C {
                    static void F(bool c) {
                        int x;
                        while (c) {
                            x = 1;
                        }
                        int y = x;
                    }
                }
                """), "while join diagnostics");
        });

        registry.test("a do body always runs", () -> {
            Assert.equalList(List.of(), flow("""
                static class C {
                    static void F(bool c) {
                        int x;
                        do {
                            x = 1;
                        } while (c);
                        int y = x;
                    }
                }
                """), "do-while join diagnostics");
        });

        registry.test("an assignment in one iteration does not satisfy the next", () -> {
            Assert.equalList(List.of("VS0165"), flow("""
                static class C {
                    static void F(bool c) {
                        int x;
                        while (c) {
                            int y = x;
                            x = 1;
                        }
                    }
                }
                """), "loop back-edge diagnostics");
        });

        registry.test("while (true) never falls out of the loop", () -> {
            Assert.equalList(List.of(), flow("""
                static class C {
                    static int F() {
                        while (true) {
                            return 1;
                        }
                    }
                }
                """), "constant loop condition diagnostics");
        });

        registry.test("a for loop without a condition never falls out", () -> {
            Assert.equalList(List.of("VS0162"), flow("""
                static class C {
                    static void F() {
                        for (;;) {
                        }
                        int x = 1;
                    }
                }
                """), "infinite for diagnostics");
        });

        registry.test("a break makes the loop exit reachable", () -> {
            Assert.equalList(List.of(), flow("""
                static class C {
                    static void F(bool c) {
                        int x;
                        while (true) {
                            x = 1;
                            break;
                        }
                        int y = x;
                    }
                }
                """), "break exit diagnostics");
        });

        registry.test("a foreach body may never run", () -> {
            Assert.equalList(List.of("VS0165"), flow("""
                static class C {
                    static void F(int[] values) {
                        int x;
                        foreach (int v in values) {
                            x = v;
                        }
                        int y = x;
                    }
                }
                """), "foreach join diagnostics");
        });
    }

    // ---- Jumps ---------------------------------------------------------------------

    private static void registerJumps(TestRegistry registry) {
        registry.test("break outside a loop or switch is rejected", () -> {
            Assert.equalList(List.of("VS0139"), flow("""
                static class C {
                    static void F() {
                        break;
                    }
                }
                """), "stray break diagnostics");
        });

        registry.test("continue outside a loop is rejected", () -> {
            Assert.equalList(List.of("VS0139"), flow("""
                static class C {
                    static void F(int v) {
                        switch (v) {
                            case 1:
                                continue;
                        }
                    }
                }
                """), "stray continue diagnostics");
        });

        registry.test("goto to an unknown label is rejected", () -> {
            Assert.equalList(List.of("VS0159"), flow("""
                static class C {
                    static void F() {
                        goto missing;
                    }
                }
                """), "missing label diagnostics");
        });

        registry.test("an unreferenced label is reported", () -> {
            Assert.equalList(List.of("VS0164"), flow("""
                static class C {
                    static void F() {
                        skip: ;
                    }
                }
                """), "unused label diagnostics");
        });

        registry.test("a label merges the states of every goto that targets it", () -> {
            Assert.equalList(List.of("VS0165"), flow("""
                static class C {
                    static void F(bool c) {
                        int x;
                        if (c) {
                            goto skip;
                        }
                        x = 1;
                        skip:
                        int y = x;
                    }
                }
                """), "goto join diagnostics");
        });

        registry.test("every goto path may assign the variable", () -> {
            Assert.equalList(List.of(), flow("""
                static class C {
                    static void F(bool c) {
                        int x;
                        if (c) {
                            x = 2;
                            goto skip;
                        }
                        x = 1;
                        skip:
                        int y = x;
                    }
                }
                """), "converged goto diagnostics");
        });
    }

    // ---- Returns and reachability --------------------------------------------------

    private static void registerReturns(TestRegistry registry) {
        registry.test("a value-returning function must return on every path", () -> {
            Assert.equalList(List.of("VS0161"), flow("""
                static class C {
                    static int F(bool c) {
                        if (c) {
                            return 1;
                        }
                    }
                }
                """), "missing return diagnostics");
        });

        registry.test("a throw satisfies a value-returning function", () -> {
            Assert.equalList(List.of(), flow("""
                using java.lang;
                static class C {
                    static int F(bool c, IllegalArgumentException error) {
                        if (c) {
                            return 1;
                        }
                        throw error;
                    }
                }
                """), "throwing function diagnostics");
        });

        registry.test("a bare return in a value-returning function is rejected", () -> {
            Assert.equalList(List.of("VS0126"), flow("""
                static class C {
                    static int F() {
                        return;
                    }
                }
                """), "valueless return diagnostics");
        });

        registry.test("returning a value from a void function is rejected", () -> {
            Assert.equalList(List.of("VS0127"), flow("""
                static class C {
                    static void F() {
                        return 1;
                    }
                }
                """), "void return diagnostics");
        });

        // A statement-bodied lambda is a callable like any other: the analysis reaches
        // it through an *expression*, and the three diagnostics below are the reason that
        // matters. Without the walk each one would either emit invalid bytecode or move a
        // compile-time error to run time.
        registry.test("a value-returning lambda must return on every path", () -> {
            Assert.equalList(List.of("VS0161"), flow("""
                using java.util;
                static class C {
                    static void F(ArrayList<string> words) {
                        words.Sort((a, b) =>
                        {
                            if (a.Length > b.Length)
                            {
                                return 1;
                            }
                        });
                    }
                }
                """), "missing lambda return diagnostics");
        });

        registry.test("returning a value from a void lambda is rejected", () -> {
            Assert.equalList(List.of("VS0127"), flow("""
                using java.util;
                static class C {
                    static void F(ArrayList<string> words) {
                        words.ForEach(w =>
                        {
                            return w.Length;
                        });
                    }
                }
                """), "void lambda return diagnostics");
        });

        registry.test("a lambda body's own locals are definitely assigned", () -> {
            Assert.equalList(List.of("VS0165"), flow("""
                using java.util;
                static class C {
                    static void F(ArrayList<string> words) {
                        words.ForEach(w =>
                        {
                            int n;
                            int m = n;
                        });
                    }
                }
                """), "unassigned lambda local diagnostics");
        });

        registry.test("statements after a return are unreachable", () -> {
            Assert.equalList(List.of("VS0162"), flow("""
                static class C {
                    static void F() {
                        return;
                        int x = 1;
                    }
                }
                """), "unreachable statement diagnostics");
        });

        registry.test("an unreachable region is reported once", () -> {
            Assert.equalList(List.of("VS0162"), flow("""
                static class C {
                    static void F() {
                        return;
                        int x = 1;
                        int y = 2;
                        int z = 3;
                    }
                }
                """), "unreachable region diagnostics");
        });

        registry.test("an out parameter must be assigned before control leaves", () -> {
            Assert.equalList(List.of("VS0177"), flow("""
                static class C {
                    static void F(out int v) {
                    }
                }
                """), "unassigned out parameter diagnostics");
        });

        registry.test("an out parameter must be assigned on every return path", () -> {
            Assert.equalList(List.of("VS0177"), flow("""
                static class C {
                    static void F(bool c, out int v) {
                        if (c) {
                            return;
                        }
                        v = 1;
                    }
                }
                """), "conditional out parameter diagnostics");
        });

        registry.test("an assigned out parameter is accepted", () -> {
            Assert.equalList(List.of(), flow("""
                static class C {
                    static void F(bool c, out int v) {
                        if (c) {
                            v = 0;
                            return;
                        }
                        v = 1;
                    }
                }
                """), "assigned out parameter diagnostics");
        });

        registry.test("reading an out parameter before assigning it is rejected", () -> {
            Assert.equalList(List.of("VS0269"), flow("""
                static class C {
                    static void F(out int v) {
                        int y = v;
                        v = 1;
                    }
                }
                """), "unassigned out parameter read diagnostics");
        });
    }

    // ---- Lambdas and local functions ------------------------------------------------

    private static void registerNestedFunctions(TestRegistry registry) {
        // Local functions are the capturing callables of the subset: a lambda needs a delegate
        // type to become a value, so binding refuses one and capture-time definite
        // assignment is observed here instead.
        registry.test("a local function checks captured locals at each call", () -> {
            Assert.equalList(List.of("VS0165"), flow("""
                static class C {
                    static void F() {
                        int x;
                        int G() => x;
                        G();
                    }
                }
                """), "captured local diagnostics");
        });

        registry.test("a captured local that is assigned first is accepted", () -> {
            Assert.equalList(List.of(), flow("""
                static class C {
                    static void F() {
                        int x = 1;
                        int G() => x;
                        G();
                    }
                }
                """), "captured assigned local diagnostics");
        });

        registry.test("a local function's definite assignments flow back to its caller", () -> {
            Assert.equalList(List.of(), flow("""
                static class C {
                    static int F() {
                        int x;
                        Assign();
                        void Assign() { x = 1; }
                        return x;
                    }
                }
                """), "captured assignment summary");

            Assert.equalList(List.of("VS0165"), flow("""
                static class C {
                    static int F(bool condition) {
                        int x;
                        void MaybeAssign() {
                            if (condition) x = 1;
                        }
                        MaybeAssign();
                        return x;
                    }
                }
                """), "only assignments on every normal exit flow back");
        });

        registry.test("a local function body is analysed on its own", () -> {
            Assert.equalList(List.of("VS0165"), flow("""
                static class C {
                    static void F() {
                        void G() {
                            int a;
                            int b = a;
                        }
                        G();
                    }
                }
                """), "local function diagnostics");
        });

        registry.test("a local function declaration is never unreachable", () -> {
            Assert.equalList(List.of(), flow("""
                static class C {
                    static int F() {
                        return G();

                        int G() {
                            return 1;
                        }
                    }
                }
                """), "local function reachability diagnostics");
        });

        registry.test("an expression-bodied local function call keeps the caller reachable", () -> {
            Assert.equalList(List.of(), flow("""
                static class C {
                    static int F() {
                        int x = 5;
                        int G() => x;
                        if (G() == 5) return -1;
                        return G();
                    }
                }
                """), "expression-bodied local function call flow");
        });
    }

    // ---- Result surface -------------------------------------------------------------

    private static void registerResults(TestRegistry registry) {
        registry.test("the result records unreachable statements in source order", () -> {
            Analysis analysis = analyze("""
                static class C {
                    static void F() {
                        return;
                        int x = 1;
                        int y = 2;
                    }
                }
                """);
            Assert.equal(2, analysis.result().unreachableStatements().size(),
                    "unreachable statement count");
            Assert.isFalse(
                    analysis.result().isReachable(
                            analysis.result().unreachableStatements().get(0)),
                    "recorded statement is unreachable");
        });

        registry.test("the result records whether a body's end point is reachable", () -> {
            Analysis analysis = analyze("""
                static class C {
                    static void Falls() {
                    }

                    static int Never() {
                        return 1;
                    }
                }
                """);
            Assert.isTrue(analysis.result().endReachable(function(analysis, "C.Falls")),
                    "void body end is reachable");
            Assert.isFalse(analysis.result().endReachable(function(analysis, "C.Never")),
                    "returning body end is unreachable");
            Assert.equal(2, analysis.result().analysedFunctions().size(), "analysed functions");
        });

        registry.test("top-level statements are analysed too", () -> {
            Assert.equalList(List.of("VS0165"), flow("""
                int x;
                int y = x;
                """), "top-level diagnostics");
        });
    }

    // ---- Harness --------------------------------------------------------------------

    private static List<String> flow(String text) {
        return analyze(text).codes();
    }

    private static Analysis analyze(String text) {
        SourceFile file = SourceFile.of("Flow.vs", text);
        DiagnosticBag binding = new DiagnosticBag();
        AuxiliarySyntax.CompilationUnit unit = Parser.parse(file, binding);
        SemanticModel model = DeclarationBinder.bind(java.util.List.of(file), java.util.List.of(unit), binding);
        ExpressionBinding expressions = ExpressionBinder.bind(file, unit, model, binding);
        Assert.isTrue(binding.all().isEmpty(), "unexpected binding diagnostics: " + binding.all());
        DiagnosticBag flow = new DiagnosticBag();
        FlowResult result = FlowAnalysis.analyze(file, unit, model, expressions, flow);
        return new Analysis(model, result, flow);
    }

    private static FunctionSymbol function(Analysis analysis, String qualifiedName) {
        for (Symbol symbol : analysis.model().symbols()) {
            if (symbol instanceof FunctionSymbol function
                    && function.qualifiedName().equals(qualifiedName)) {
                return function;
            }
        }
        throw Assert.fail(() -> "no function named " + qualifiedName);
    }

    private record Analysis(SemanticModel model, FlowResult result, DiagnosticBag diagnostics) {

        List<String> codes() {
            return diagnostics.all().stream().map(Diagnostic::code).map(Object::toString).toList();
        }
    }
}
