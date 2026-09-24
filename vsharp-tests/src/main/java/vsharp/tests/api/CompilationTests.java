package vsharp.tests.api;

import java.util.List;
import java.util.Set;
import vsharp.compiler.api.Compilation;
import vsharp.compiler.api.CompilationResult;
import vsharp.compiler.api.Phase;
import vsharp.compiler.api.UnitAnalysis;
import vsharp.compiler.diagnostics.Diagnostic;
import vsharp.compiler.source.SourceFile;
import vsharp.tests.TestSources;
import vsharp.testkit.Assert;
import vsharp.testkit.TestRegistry;
import vsharp.testkit.TestSuite;

/// Driver coverage for [Compilation]: phase order, gating, unit shapes and determinism.
///
/// The facade owns no analysis, so these cases assert sequencing rather than semantics:
/// which phase ran, what a failed run leaves behind, and that two runs agree exactly.
public final class CompilationTests implements TestSuite {

    @Override
    public String suiteName() {
        return "api.compilation";
    }

    @Override
    public void register(TestRegistry registry) {
        registerPhases(registry);
        registerShapes(registry);
        registerDeterminism(registry);
    }

    // ---- Phase sequencing ---------------------------------------------------------

    private static void registerPhases(TestRegistry registry) {
        registry.test("a clean source runs the whole front end", () -> {
            CompilationResult result = compile("""
                    static class C {
                        static int Add(int a, int b) {
                            return a + b;
                        }
                    }
                    """);
            Assert.equalList(List.of(), codes(result), "clean diagnostics");
            Assert.equal(Phase.FLOW_ANALYSIS, result.reached(), "reached phase");
            Assert.isTrue(result.succeeded(), "clean run succeeds");
        });

        registry.test("a syntax error stops after parsing", () -> {
            CompilationResult result = compile("static class C { static void F(");
            Assert.equal(Phase.PARSE, result.reached(), "parse stop");
            Assert.isTrue(result.hasErrors(), "parse errors reported");
            Assert.isFalse(result.succeeded(), "failed run");
        });

        registry.test("a declaration error stops before expression binding", () -> {
            CompilationResult result = compile("""
                    static class C {
                        static void F() {
                        }
                        static void F() {
                        }
                    }
                    """);
            Assert.equalList(List.of("VS0111"), codes(result), "duplicate function only");
            Assert.equal(Phase.DECLARATION_BINDING, result.reached(), "declaration stop");
        });

        registry.test("an expression error stops before flow analysis", () -> {
            // `missing` is undefined, so `x` is never definitely assigned either; only the
            // binding diagnostic may appear, because flow analysis must not run.
            CompilationResult result = compile("""
                    static class C {
                        static void F() {
                            int x = missing;
                            int y = x;
                        }
                    }
                    """);
            Assert.equalList(List.of("VS0103"), codes(result), "binding stop diagnostics");
            Assert.equal(Phase.EXPRESSION_BINDING, result.reached(), "binding stop");
        });

        registry.test("ref and typed out arguments require identity conversions", () -> {
            CompilationResult result = compile("""
                    static class C {
                        static void RefLong(ref long value) { }
                        static void OutLong(out long value) { value = 0; }
                        static void F() {
                            int value = 1;
                            RefLong(ref value);
                            OutLong(out value);
                        }
                    }
                    """);
            Assert.equalList(List.of("VS1503", "VS1503"), codes(result),
                    "ref/out identity diagnostics");
            Assert.equal(Phase.EXPRESSION_BINDING, result.reached(), "binding stop");
        });

        registry.test("out var overloads remain ambiguous until a candidate is selected", () -> {
            CompilationResult result = compile("""
                    static class C {
                        static void Pick(out int value) { value = 0; }
                        static void Pick(out long value) { value = 0; }
                        static void F() {
                            Pick(out var value);
                        }
                    }
                    """);
            Assert.equalList(List.of("VS0121"), codes(result),
                    "out var ambiguity diagnostic");
            Assert.equal(Phase.EXPRESSION_BINDING, result.reached(), "binding stop");
        });

        registry.test("an out-of-range decimal literal is diagnosed at binding", () -> {
            CompilationResult result = compile("""
                    static class C {
                        static decimal F() {
                            return 9e28m;
                        }
                    }
                    """);
            Assert.equalList(List.of("VS0594"), codes(result), "decimal literal diagnostic");
            Assert.equal(Phase.EXPRESSION_BINDING, result.reached(), "binding stop");
        });

        registry.test("iterator statements are rejected before lowering and emission", () -> {
            CompilationResult result = Compilation.of(List.of(TestSources.styled("Iterator.vs", """
                    static class C {
                        static object Values(bool stop) {
                            if (stop) {
                                yield break;
                            }
                            yield return 1;
                        }
                    }
                    """))).emit();
            Assert.equalList(List.of("VS20001", "VS20001"), codes(result),
                    "iterator diagnostics");
            Assert.equal(Phase.EXPRESSION_BINDING, result.reached(),
                    "iterator syntax never reaches IR lowering");
            Assert.isTrue(result.units().getFirst() instanceof UnitAnalysis.Bound,
                    "binding result retained");
        });

        registry.test("unsupported type-operator operands stop before lowering", () -> {
            CompilationResult result = Compilation.of(List.of(TestSources.styled("TypeOps.vs", """
                    struct S { int value; }
                    static class C {
                        static int A() => sizeof(nint);
                        static int B() => sizeof(string);
                        static int C1() => sizeof(S);
                        static object Erased<T>() => typeof(T);
                    }
                    """))).emit();
            Assert.equalList(List.of("VS0233", "VS0233", "VS0233", "VS20002"),
                    codes(result), "type operator diagnostics");
            Assert.equal(Phase.EXPRESSION_BINDING, result.reached(),
                    "unsupported operands never reach lowering");
        });

        registry.test("unbound generic names are confined to typeof", () -> {
            CompilationResult result = Compilation.of(List.of(TestSources.styled("Unbound.vs", """
                    struct Box<T> { }
                    static class C {
                        static Box<> Invalid() => default(Box<>);
                    }
                    """))).emit();
            // Both unbound uses are reported, the return type and the `default` operand.
            // The .NET 10 oracle compiles the same two-occurrence program and reports CS7003
            // twice, so one diagnostic was an artifact of the operand's type never being
            // resolved at all before the design routed expression type positions through the
            // declaration binder.
            Assert.equalList(List.of("VS7003", "VS7003"), codes(result),
                    "unbound generic diagnostics");
            Assert.equal(Phase.DECLARATION_BINDING, result.reached(),
                    "unbound storage types never reach expression binding");
        });

        registry.test("nameof rejects expressions without names before lowering", () -> {
            CompilationResult result = Compilation.of(List.of(TestSources.styled("NameOf.vs", """
                    static class C {
                        static string Name() => nameof(1 + 2);
                    }
                    """))).emit();
            Assert.equalList(List.of("VS8081"), codes(result), "nameof diagnostic");
            Assert.equal(Phase.EXPRESSION_BINDING, result.reached(),
                    "nameless nameof never reaches lowering");
        });

        registry.test("a capturing local function reaches code generation", () -> {
            CompilationResult capturing = Compilation.of(List.of(TestSources.styled("Capture.vs", """
                    static class C {
                        static int F() {
                            int seed = 1;
                            int G() => seed + 1;
                            return G();
                        }
                    }
                    """))).emit();
            Assert.equalList(List.of(), codes(capturing),
                    "capturing local function diagnostics");
            Assert.equal(Phase.CODE_GENERATION, capturing.reached(),
                    "capture cells reach bytecode generation");

            CompilationResult parameterised = Compilation.of(List.of(TestSources.styled("Capture.vs", """
                    static class C {
                        static int F() {
                            int seed = 1;
                            int G(int value) => value + 1;
                            return G(seed);
                        }
                    }
                    """))).emit();
            Assert.equalList(List.of(), codes(parameterised),
                    "explicit parameter passing still compiles");
        });

        registry.test("a static local function cannot capture state", () -> {
            CompilationResult result = Compilation.of(List.of(TestSources.styled("StaticCapture.vs", """
                    struct C {
                        int field;
                        int F() {
                            int local = 1;
                            static int Local() => local;
                            static int Receiver() => field;
                            return Local() + Receiver();
                        }
                    }
                    """))).emit();
            Assert.equalList(List.of("VS8421", "VS8422"), codes(result),
                    "static local capture diagnostics");
            Assert.equal(Phase.FLOW_ANALYSIS, result.reached(),
                    "invalid static captures stop before bytecode generation");
        });

        registry.test("a lambda is refused and a generic Java type never crashes the compiler", () -> {
            // Both of these used to end the process rather than the compilation: the lambda
            // recursed between the two lowerers until the stack overflowed, and the type
            // argument tripped an arity assertion inside `TypeSymbol.Constructed`. Java
            // signature mapping now makes the latter valid and keeps this as the regression
            // that ordinary generic source reaches code generation rather than VS29999.
            CompilationResult lambda = Compilation.of(List.of(TestSources.styled("Lambda.vs", """
                    static class C {
                        static void F() {
                            var f = (int x) => x;
                        }
                    }
                    """))).emit();
            Assert.equalList(List.of("VS20001"), codes(lambda),
                    "a lambda needs a delegate type, which the subset excludes");
            Assert.equal(Phase.EXPRESSION_BINDING, lambda.reached(),
                    "the lambda never reaches lowering");

            CompilationResult generic = Compilation.of(List.of(TestSources.styled("Generic.vs", """
                    using java.util;
                    static class C {
                        static int F() {
                            var values = new ArrayList<string>();
                            return values.Size();
                        }
                    }
                    """))).emit();
            Assert.equalList(List.of(), codes(generic),
                    "a module-path generic type retains its declared arity");
            Assert.equal(Phase.CODE_GENERATION, generic.reached(),
                    "the constructed Java type emits rather than crashing");
        });

        registry.test("an interpolation format clause is validated at compile time", () -> {
            // The clause is always literal text, so a specifier this build can never honour
            // fails the build rather than the run.
            CompilationResult culture = Compilation.of(List.of(TestSources.styled("Culture.vs", """
                    static class C {
                        static string F() {
                            int n = 42;
                            return $"{n:N2}";
                        }
                    }
                    """))).emit();
            Assert.equalList(List.of("VS20002"), codes(culture),
                    "N needs a culture model this build does not have");
            Assert.contains(culture.diagnostics().getFirst().message(), "culture model",
                    "the diagnostic names the reason");
            Assert.contains(culture.diagnostics().getFirst().message(), "'N2'",
                    "the diagnostic quotes the specifier");

            CompilationResult custom = Compilation.of(List.of(TestSources.styled("Custom.vs", """
                    static class C {
                        static string F() {
                            int n = 42;
                            return $"{n:0.00}";
                        }
                    }
                    """))).emit();
            Assert.equalList(List.of("VS20002"), codes(custom),
                    "a custom format string is a separate surface");
            Assert.contains(custom.diagnostics().getFirst().message(), "custom format string",
                    "the diagnostic distinguishes custom from invalid");

            CompilationResult unknown = Compilation.of(List.of(TestSources.styled("Unknown.vs", """
                    static class C {
                        static string F() {
                            int n = 42;
                            return $"{n:Q}";
                        }
                    }
                    """))).emit();
            Assert.equalList(List.of("VS20002"), codes(unknown), "Q is not a specifier at all");

            // An enum's specifier grammar is a different one - `X` pads to the underlying
            // width and `F` means flags - so it is refused rather than silently mapped.
            CompilationResult enumHole = Compilation.of(List.of(TestSources.styled("Enum.vs", """
                    enum Status { Draft = 0, Live = 7 }
                    static class C {
                        static string F() {
                            Status s = Status.Live;
                            return $"{s:D}";
                        }
                    }
                    """))).emit();
            Assert.equalList(List.of("VS20002"), codes(enumHole),
                    "enum format specifiers use a grammar this build does not implement");

            // Not IFormattable in .NET, so C# ignores the clause and so does V#.
            CompilationResult ignored = Compilation.of(List.of(TestSources.styled("Ignored.vs", """
                    static class C {
                        static string F() {
                            string s = "abc";
                            bool b = true;
                            char c = 'x';
                            return $"{s:F2}{b:F2}{c:F2}";
                        }
                    }
                    """))).emit();
            Assert.equalList(List.of(), codes(ignored),
                    "a clause on a non-formattable type is ignored, exactly as C# ignores it");

            // Supported specifiers must still compile cleanly.
            CompilationResult accepted = Compilation.of(List.of(TestSources.styled("Ok.vs", """
                    static class C {
                        static string F() {
                            int n = 42;
                            double d = 2.5;
                            return $"{n,-8:D4}{n:X}{d:F2}";
                        }
                    }
                    """))).emit();
            Assert.equalList(List.of(), codes(accepted),
                    "D, X and F with alignment all bind and emit");
        });

        registry.test("only types with a runtime carrier can be constructed", () -> {
            CompilationResult carrierless = Compilation.of(List.of(TestSources.styled("New.vs", """
                    using System;
                    static class C {
                        static object Run() => new Object();
                    }
                    """))).emit();
            Assert.equalList(List.of("VS20001"), codes(carrierless),
                    "a corelib name with no runtime class is not constructible");

            CompilationResult wrongArguments = Compilation.of(List.of(TestSources.styled("New.vs", """
                    using System;
                    static class C {
                        static void Run() {
                            throw new InvalidOperationException(1, 2, 3);
                        }
                    }
                    """))).emit();
            // A constructor is named by its type, so C# reports CS1729 here rather than
            // CS1501's member-name shape; verified against .NET 10.
            Assert.equalList(List.of("VS1729"), codes(wrongArguments),
                    "the carrier's own constructors are what overload resolution sees");
        });

        registry.test("patterns are checked against the type they test", () -> {
            // `(1)` is a parenthesised *constant* pattern, so this arm could never match; it
            // used to compile into dead code instead of being reported.
            CompilationResult parenthesised = Compilation.of(List.of(TestSources.styled("Const.vs", """
                    record struct Point(int X, int Y);
                    static class C {
                        static string Run(Point p) => p switch { (1) => "one", _ => "other" };
                    }
                    """))).emit();
            Assert.equalList(List.of("VS8121"), codes(parenthesised),
                    "a constant that cannot equal the tested value is reported");

            // Before this the backend reported VS29999 for it: a user program must never be
            // able to produce an internal compiler error.
            CompilationResult reference = Compilation.of(List.of(TestSources.styled("Const.vs", """
                    static class C {
                        static bool Run(string s) => s is 5;
                    }
                    """))).emit();
            Assert.equalList(List.of("VS8121"), codes(reference),
                    "a constant of an unrelated type is reported, not emitted");
            Assert.equal(Phase.EXPRESSION_BINDING, reference.reached(),
                    "the pattern never reaches the backend");

            CompilationResult relational = Compilation.of(List.of(TestSources.styled("Rel.vs", """
                    static class C {
                        static bool Run(string s) => s is > 5;
                    }
                    """))).emit();
            Assert.equalList(List.of("VS8781"), codes(relational),
                    "C# defines no relational operators for string");

            CompilationResult nullable = Compilation.of(List.of(TestSources.styled("Rel.vs", """
                    static class C {
                        static bool Run(int? n) => n is > 5;
                    }
                    """))).emit();
            Assert.equalList(List.of(), codes(nullable),
                    "a nullable operand is supported in relational patterns");

            CompilationResult decimals = Compilation.of(List.of(TestSources.styled("Rel.vs", """
                    static class C {
                        static bool Run(decimal d) => d is > 5m;
                    }
                    """))).emit();
            Assert.equalList(List.of(), codes(decimals),
                    "a decimal operand is supported in relational patterns");

            CompilationResult unrelatedType = Compilation.of(List.of(TestSources.styled("Type.vs", """
                    static class C {
                        static bool Run(int x) => x is long v;
                    }
                    """))).emit();
            Assert.equalList(List.of("VS8121"), codes(unrelatedType),
                    "a value-typed operand admits only its own type as a type pattern");

            CompilationResult boxingType = Compilation.of(List.of(TestSources.styled("Type.vs", """
                    static class C {
                        static bool Run(int x) => x is object o;
                    }
                    """))).emit();
            Assert.equalList(List.of("VS20002"), codes(boxingType),
                    "boxing a value operand in a type pattern is a stated build limit");

            // The compatible directions both stay legal: the constant converts to the tested
            // type, or the tested type converts to the constant's (`object o is 5` unboxes).
            CompilationResult compatible = Compilation.of(List.of(TestSources.styled("Ok.vs", """
                    static class C {
                        static bool Widening(long v) => v is 1;
                        static bool Boxed(object o) => o is 5;
                        static bool Text(string s) => s is "a" or null;
                        static bool Money(decimal d) => d is 6m;
                        static bool Lifted(int? n) => n is 7;
                        static int Same(int x) => x is int n and > 5 ? n : -1;
                    }
                    """))).emit();
            Assert.equalList(List.of(), codes(compatible),
                    "compatible constant patterns stay legal");
        });

        registry.test("record struct misuse is diagnosed, never silently accepted", () -> {
            CompilationResult unknown = Compilation.of(List.of(TestSources.styled("With.vs", """
                    record struct Point(int X, int Y);
                    static class C {
                        static Point Run(Point p) => p with { Z = 1 };
                    }
                    """))).emit();
            Assert.equalList(List.of("VS0117"), codes(unknown),
                    "'with' on a name that is not a component");

            CompilationResult duplicate = Compilation.of(List.of(TestSources.styled("With.vs", """
                    record struct Point(int X, int Y);
                    static class C {
                        static Point Run(Point p) => p with { X = 1, X = 2 };
                    }
                    """))).emit();
            Assert.equalList(List.of("VS0102"), codes(duplicate),
                    "a component may be given a new value only once");

            CompilationResult notARecord = Compilation.of(List.of(TestSources.styled("With.vs", """
                    static class C {
                        static int Run(int value) => value with { X = 1 };
                    }
                    """))).emit();
            Assert.equalList(List.of("VS20001"), codes(notARecord),
                    "'with' needs a positional record struct receiver");

            CompilationResult primary = Compilation.of(List.of(TestSources.styled("Plain.vs", """
                    struct Plain(int X);
                    """))).emit();
            Assert.equalList(List.of("VS20001"), codes(primary),
                    "a non-record struct primary constructor captures, which needs the object model");

            CompilationResult arity = Compilation.of(List.of(TestSources.styled("Arity.vs", """
                    record struct Point(int X, int Y);
                    static class C {
                        static string Run(Point p) => p switch { (1, 2, 3) => "three", _ => "other" };
                    }
                    """))).emit();
            Assert.equalList(List.of("VS8502"), codes(arity),
                    "a positional pattern must match the component count");

            CompilationResult arguments = Compilation.of(List.of(TestSources.styled("Args.vs", """
                    record struct Point(int X, int Y);
                    static class C {
                        static Point Run() => new Point(1);
                    }
                    """))).emit();
            Assert.equalList(List.of("VS1729"), codes(arguments),
                    "the synthesized constructor takes exactly the components");

            CompilationResult targets = Compilation.of(List.of(TestSources.styled("Targets.vs", """
                    record struct Point(int X, int Y);
                    static class C {
                        static void Run(Point p) {
                            var (a, b, c) = p;
                        }
                    }
                    """))).emit();
            Assert.equalList(List.of("VS0029"), codes(targets),
                    "deconstruction target count must match the component count");
        });

        registry.test("a deconstructing foreach is checked against the element type", () -> {
            CompilationResult arity = Compilation.of(List.of(TestSources.styled("ForeachArity.vs", """
                    static class C {
                        static void Run((string, int)[] pairs) {
                            foreach (var (a, b, c) in pairs) {
                            }
                        }
                    }
                    """))).emit();
            Assert.equalList(List.of("VS8502"), codes(arity),
                    "a deconstructing foreach must match the element's component count");

            // The element decides whether taking it apart is available at all. `string` offers
            // no positional components, and V# has no instance `Deconstruct` to fall back on
            // (the omitted object model), so the shape is refused rather than mis-matched.
            CompilationResult shape = Compilation.of(List.of(TestSources.styled("ForeachShape.vs", """
                    static class C {
                        static void Run(string[] words) {
                            foreach (var (a, b) in words) {
                            }
                        }
                    }
                    """))).emit();
            Assert.equalList(List.of("VS8129"), codes(shape),
                    "an element with no positional components cannot be deconstructed");

            // Each component is still checked against the type its target declares. C# calls
            // this a declaration and reports CS0030; V# reaches the component through the
            // positional pattern walk, so it reports the pattern form (VS8121) at the same
            // position and stops the same compilation. The divergence is in the wording only
            // and is documented in the feature matrix.
            CompilationResult conversion = Compilation.of(List.of(TestSources.styled("ForeachConv.vs", """
                    static class C {
                        static void Run((string, int)[] pairs) {
                            foreach ((string name, string age) in pairs) {
                            }
                        }
                    }
                    """))).emit();
            Assert.equalList(List.of("VS8121"), codes(conversion),
                    "a component must be compatible with its declared target type");
        });

        registry.test("a primitive array cannot carry a type-parameter array", () -> {
            // `T[]` is `object[]` in the descriptor, and `[I` is not assignable to it.
            // The refusal is the erasure difference itself, not an unfinished feature, so it
            // has its own code and names both the argument and the erasure.
            CompilationResult result = Compilation.of(List.of(TestSources.styled("Generic.vs", """
                    static class C {
                        static T First<T>(T[] values) => values[0];
                        static int Run() {
                            int[] values = { 1 };
                            return First(values);
                        }
                    }
                    """))).emit();
            Assert.equalList(List.of("VS20019"), codes(result),
                    "type-parameter-dependent array diagnostic");
            Assert.isTrue(result.diagnostics().getFirst().message().contains("'int[]'"),
                    "the diagnostic names the argument that cannot be carried");
            Assert.isTrue(result.diagnostics().getFirst().message().contains("'object[]'"),
                    "the diagnostic names the erasure it would have to become");
            Assert.equal(Phase.FLOW_ANALYSIS, result.reached(),
                    "emission gate runs after the clean front end");
            Assert.isTrue(result.units().getFirst() instanceof UnitAnalysis.Analysed,
                    "analysed unit is retained");
        });

        registry.test("a reference array is carried by a type-parameter array", () -> {
            // The same declaration with a reference argument is emitted, which is what the design
            // opened. Kept beside the refusal so the pair states the whole rule.
            CompilationResult result = Compilation.of(List.of(TestSources.styled("GenericOk.vs", """
                    static class C {
                        static T First<T>(T[] values) => values[0];
                        static string Run() {
                            string[] values = { "a" };
                            return First(values);
                        }
                    }
                    """))).emit();
            Assert.equalList(List.of(), codes(result), "a reference array needs no gate");
            Assert.isTrue(result.succeeded(), "the call is emitted");
        });

        registry.test("flow errors are reported once earlier phases are clean", () -> {
            CompilationResult result = compile("""
                    static class C {
                        static void F() {
                            int x;
                            int y = x;
                        }
                    }
                    """);
            Assert.equalList(List.of("VS0165"), codes(result), "flow diagnostics");
            Assert.equal(Phase.FLOW_ANALYSIS, result.reached(), "flow reached");
            Assert.isFalse(result.succeeded(), "flow error fails the run");
        });

        registry.test("a warning never stops the run", () -> {
            CompilationResult result = compile("""
                    static class C {
                        static void F() {
                            return;
                            int x = 1;
                        }
                    }
                    """);
            Assert.equalList(List.of("VS0162"), codes(result), "warning diagnostics");
            Assert.isFalse(result.hasErrors(), "warning is not an error");
            Assert.isTrue(result.succeeded(), "warning run succeeds");
        });

        registry.test("phases are declared in execution order", () -> {
            Assert.equalList(
                    List.of(Phase.PARSE, Phase.DECLARATION_BINDING, Phase.EXPRESSION_BINDING,
                            Phase.FLOW_ANALYSIS, Phase.CODE_GENERATION),
                    List.of(Phase.values()), "phase order");
            Assert.equal("flow analysis", Phase.FLOW_ANALYSIS.displayName(), "display name");
            Assert.equal("code generation", Phase.CODE_GENERATION.displayName(), "code generation display name");
        });
    }

    // ---- Result shape -------------------------------------------------------------

    private static void registerShapes(TestRegistry registry) {
        registry.test("a completed run exposes one analysed unit per source", () -> {
            CompilationResult result = Compilation.of(List.of(
                    TestSources.styled("A.vs", "static class A { static int F() { return 1; } }"),
                    TestSources.styled("B.vs", "static class B { static int G() { return 2; } }")))
                    .analyze();
            Assert.equal(2L, result.units().size(), "unit count");
            Assert.equalList(List.of("A.vs", "B.vs"),
                    result.units().stream().map(unit -> unit.file().name()).toList(),
                    "units follow source order");
            for (UnitAnalysis unit : result.units()) {
                Assert.isTrue(unit instanceof UnitAnalysis.Analysed, "analysed shape");
            }
        });

        registry.test("a stopped run exposes the shape of the last phase", () -> {
            Assert.isTrue(compile("static class C { static void F(").units().get(0)
                    instanceof UnitAnalysis.Parsed, "parsed shape");
            Assert.isTrue(compile("""
                    static class C {
                        static void F() {
                        }
                        static void F() {
                        }
                    }
                    """).units().get(0) instanceof UnitAnalysis.Declared, "declared shape");
            Assert.isTrue(compile("static class C { static void F() { int x = missing; } }")
                    .units().get(0) instanceof UnitAnalysis.Bound, "bound shape");
        });

        registry.test("an analysed unit carries every earlier phase result", () -> {
            UnitAnalysis unit = compile("static class C { static int F() { return 1; } }")
                    .units().get(0);
            if (!(unit instanceof UnitAnalysis.Analysed analysed)) {
                throw Assert.fail(() -> "expected an analysed unit but got " + unit);
            }
            Assert.notNull(analysed.syntax(), "syntax");
            Assert.notNull(analysed.model().topLevelFunction(), "model");
            Assert.notNull(analysed.expressions(), "expressions");
            Assert.equal(1L, analysed.flow().analysedFunctions().size(), "analysed functions");
        });

        registry.test("an emitted unit carries IR and bytecode", () -> {
            CompilationResult result = Compilation.of(List.of(TestSources.styled("EmitApi.vs",
                    "static class EmitApi { static int F() { return 1; } }"))).emit();
            Assert.equal(Phase.CODE_GENERATION, result.reached(), "emit reached");
            Assert.isTrue(result.succeeded(), "emitted run succeeds");
            UnitAnalysis unit = result.units().get(0);
            if (!(unit instanceof UnitAnalysis.Emitted emitted)) {
                throw Assert.fail(() -> "expected an emitted unit but got " + unit);
            }
            Assert.notNull(emitted.ir(), "ir");
            Assert.isTrue(!emitted.classes().isEmpty(), "bytecode is present");
        });

        registry.test("a file mixing a type with top-level statements emits both holders", () -> {
            // The holder set is derived from every member, not only the ones a declared type
            // owns: dropping the file's own synthetic holder would leave the top-level `Main`
            // on the floor and the program unloadable.
            CompilationResult result = Compilation.of(List.of(TestSources.styled("Mixed.vs", """
                    using System;
                    public struct Pt { public int X; }
                    Console.WriteLine(1);
                    """))).emit();
            Assert.isTrue(result.succeeded(), "mixed run succeeds");
            UnitAnalysis unit = result.units().get(0);
            if (!(unit instanceof UnitAnalysis.Emitted emitted)) {
                throw Assert.fail(() -> "expected an emitted unit but got " + unit);
            }
            Assert.equalList(List.of("Mixed", "Pt"), List.copyOf(emitted.classes().keySet()),
                    "declared type and file holder are both emitted");
        });

        registry.test("emit accepts static-field ref and out arguments", () -> {
            CompilationResult result = Compilation.of(List.of(TestSources.styled("RefOutEmit.vs", """
                    static class C {
                        static int a = 1;
                        static int b = 2;
                        static void Swap(ref int x, ref int y) {
                            int t = x;
                            x = y;
                            y = t;
                        }
                        static void Next(out int v, int amount) {
                            v = amount;
                        }
                        static int F() {
                            Swap(ref a, ref b);
                            Next(out a, 7);
                            return a * 10 + b;
                        }
                    }
                    """))).emit();
            Assert.equal(Phase.CODE_GENERATION, result.reached(),
                    "static-field ref/out reaches code generation");
            Assert.isTrue(result.succeeded(), "static-field ref/out run succeeds");
            Assert.isTrue(result.units().get(0) instanceof UnitAnalysis.Emitted,
                    "unit is emitted");
        });

        registry.test("an empty compilation succeeds with no units", () -> {
            CompilationResult result = Compilation.of(List.of()).analyze();
            Assert.isTrue(result.succeeded(), "empty run succeeds");
            Assert.equal(0L, result.units().size(), "no units");
        });
    }

    // ---- Determinism --------------------------------------------------------------

    private static void registerDeterminism(TestRegistry registry) {
        registry.test("diagnostics of several files are ordered by file and position", () -> {
            Compilation compilation = Compilation.of(List.of(
                    TestSources.styled("B.vs", """
                            static class B {
                                static void F() {
                                    int x;
                                    int y = x;
                                }
                            }
                            """),
                    TestSources.styled("A.vs", """
                            static class A {
                                static void F(out int v) {
                                }
                            }
                            """)));
            List<Diagnostic> diagnostics = compilation.analyze().diagnostics();
            Assert.equalList(List.of("A.vs", "B.vs"),
                    diagnostics.stream().map(diagnostic -> diagnostic.file().name()).toList(),
                    "file ordering ignores source order");
            Assert.equalList(List.of("VS0177", "VS0165"),
                    diagnostics.stream().map(Diagnostic::code).map(Object::toString).toList(),
                    "codes follow file ordering");
        });

        registry.test("analysing the same compilation twice yields the same diagnostics", () -> {
            Compilation compilation = Compilation.of(List.of(TestSources.styled("Repeat.vs", """
                    static class C {
                        static int F(bool c) {
                            int x;
                            if (c) {
                                x = 1;
                            }
                            int y = x;
                            return y;
                        }
                    }
                    """)));
            List<String> first = codes(compilation.analyze());
            List<String> second = codes(compilation.analyze());
            Assert.equalList(List.of("VS0165"), first, "first run");
            Assert.equalList(first, second, "runs agree");
        });

        registry.test("preprocessing symbols are held in a stable sorted set", () -> {
            Compilation compilation = Compilation.of(
                    List.of(TestSources.styled("Symbols.vs", "")), Set.of("ZED", "ALPHA", "MID"));
            Assert.equalList(List.of("ALPHA", "MID", "ZED"),
                    List.copyOf(compilation.symbols()), "sorted symbols");
        });

        registry.test("defined symbols select the compiled branch", () -> {
            String text = """
                    #if FEATURE
                    static class C {
                        static void F() {
                            int x;
                            int y = x;
                        }
                    }
                    #endif
                    """;
            Assert.equalList(List.of("VS0165"),
                    codes(Compilation.of(List.of(TestSources.styled("Branch.vs", text)),
                            Set.of("FEATURE")).analyze()),
                    "active branch is analysed");
            Assert.equalList(List.of(),
                    codes(Compilation.of(List.of(TestSources.styled("Branch.vs", text))).analyze()),
                    "inactive branch is masked");
        });
    }

    // ---- Harness ------------------------------------------------------------------

    private static CompilationResult compile(String text) {
        return Compilation.of(List.of(TestSources.styled("Api.vs", text))).analyze();
    }

    private static List<String> codes(CompilationResult result) {
        return result.diagnostics().stream().map(Diagnostic::code).map(Object::toString).toList();
    }
}
