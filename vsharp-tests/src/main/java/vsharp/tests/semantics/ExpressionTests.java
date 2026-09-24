package vsharp.tests.semantics;

import java.util.List;
import vsharp.compiler.diagnostics.Diagnostic;
import vsharp.compiler.diagnostics.DiagnosticBag;
import vsharp.compiler.semantics.binding.BoundExpression;
import vsharp.compiler.semantics.binding.DeclarationBinder;
import vsharp.compiler.semantics.binding.ExpressionBinder;
import vsharp.compiler.semantics.binding.ExpressionBinding;
import vsharp.compiler.semantics.binding.SemanticModel;
import vsharp.compiler.semantics.symbols.FieldSymbol;
import vsharp.compiler.semantics.symbols.LocalSymbol;
import vsharp.compiler.semantics.symbols.ParameterSymbol;
import vsharp.compiler.semantics.types.BuiltinType;
import vsharp.compiler.semantics.types.TypeSymbol;
import vsharp.compiler.source.SourceFile;
import vsharp.compiler.syntax.AuxiliarySyntax;
import vsharp.compiler.syntax.DeclarationSyntax;
import vsharp.compiler.syntax.ExpressionSyntax;
import vsharp.compiler.syntax.Parser;
import vsharp.compiler.syntax.PatternSyntax;
import vsharp.compiler.syntax.StatementSyntax;
import vsharp.testkit.Assert;
import vsharp.testkit.TestRegistry;
import vsharp.testkit.TestSuite;

/// Value-name and core predefined-operator binding coverage.
public final class ExpressionTests implements TestSuite {

    @Override
    public String suiteName() {
        return "semantic.expressions";
    }

    @Override
    public void register(TestRegistry registry) {
        registerLiteralsAndNames(registry);
        registerOperators(registry);
        registerDiagnostics(registry);
    }

    private static void registerLiteralsAndNames(TestRegistry registry) {
        registry.test("literal tokens map to their semantic types", () -> {
            Bound bound = clean("""
                    static class C {
                        static int I() => 1;
                        static uint U() => 4000000000;
                        static long L() => 1L;
                        static double D() => 1.0;
                        static decimal M() => 1m;
                        static char Ch() => 'x';
                        static string S() => "x";
                        static bool B() => true;
                        static string N() => null;
                    }
                    """);
            Assert.equal(BuiltinType.INT, typeOfBody(bound, "I"), "int literal");
            Assert.equal(BuiltinType.UINT, typeOfBody(bound, "U"), "uint literal");
            Assert.equal(BuiltinType.LONG, typeOfBody(bound, "L"), "long literal");
            Assert.equal(BuiltinType.DOUBLE, typeOfBody(bound, "D"), "double literal");
            Assert.equal(BuiltinType.DECIMAL, typeOfBody(bound, "M"), "decimal literal");
            Assert.equal(BuiltinType.CHAR, typeOfBody(bound, "Ch"), "char literal");
            Assert.equal(BuiltinType.STRING, typeOfBody(bound, "S"), "string literal");
            Assert.equal(BuiltinType.BOOL, typeOfBody(bound, "B"), "bool literal");
            // Unlike every sibling above, `null`'s own type (TypeSymbol.Null.INSTANCE) does
            // not equal N()'s declared return type (string), so this return position now
            // wraps it in an explicit implicit-null-conversion node - the type recorded at
            // the expression-body syntax is the converted (string) type, not the bare
            // literal's own type, exactly like every other real conversion the IR needs to
            // see. (Before this was wired up, the conversion was silently dropped: bytecode
            // would have loaded a bare `null` and returned it unconverted from a method
            // whose descriptor still said `Ljava/lang/String;` - happens to verify for a
            // reference type, but was never actually applying the conversion the binder had
            // already validated as legal.)
            Assert.equal(BuiltinType.STRING, typeOfBody(bound, "N"), "null literal converted to return type");
        });

        registry.test("utf8 literals retain byte array type", () -> {
            Bound bound = clean("static class C { static byte[] Data() => \"ok\"u8; }");
            Assert.equal(new TypeSymbol.Array(BuiltinType.BYTE, List.of(1)),
                    typeOfBody(bound, "Data"), "utf8 type");
        });

        registry.test("parameters fields and inferred locals resolve through nested scopes",
                () -> {
                    Bound bound = clean("""
                            static class C {
                                static int field = 4;
                                static int F(int parameter) {
                                    var local = parameter + field;
                                    return local;
                                }
                            }
                            """);
                    DeclarationSyntax.Method method = method(bound.unit(), "F");
                    StatementSyntax.LocalDeclaration declaration =
                            (StatementSyntax.LocalDeclaration) method.body().statements().get(0);
                    ExpressionSyntax.Binary initializer = (ExpressionSyntax.Binary)
                            declaration.variables().getFirst().initializer();
                    BoundExpression.Value parameter = (BoundExpression.Value)
                            expression(bound, initializer.left());
                    BoundExpression.Value field = (BoundExpression.Value)
                            expression(bound, initializer.right());
                    Assert.isTrue(parameter.symbol() instanceof ParameterSymbol,
                            "parameter symbol");
                    Assert.isTrue(field.symbol() instanceof FieldSymbol, "field symbol");
                    LocalSymbol local = (LocalSymbol) bound.declarations()
                            .declaredSymbol(declaration.variables().getFirst()).orElseThrow();
                    Assert.equal(BuiltinType.INT,
                            bound.expressions().inferredType(local).orElseThrow(), "inferred type");
                    StatementSyntax.Return returned =
                            (StatementSyntax.Return) method.body().statements().get(1);
                    BoundExpression.Value reference = (BoundExpression.Value)
                            expression(bound, returned.expression());
                    Assert.isTrue(reference.symbol() == local, "canonical local identity");
                    Assert.equal(BuiltinType.INT, reference.type(), "effective local type");
                });

        registry.test("function identifiers bind deterministic overload groups", () -> {
            Bound bound = clean("""
                    static class C {
                        static int Twice(int value) => value + value;
                        static long Twice(long value) => value + value;
                        static int Use() => Twice(2) + 1;
                    }
                    """);
            ExpressionSyntax.Binary use = (ExpressionSyntax.Binary)
                    method(bound.unit(), "Use").expressionBody();
            ExpressionSyntax.Invocation invocation = (ExpressionSyntax.Invocation) use.left();
            BoundExpression.FunctionGroup group = (BoundExpression.FunctionGroup)
                    expression(bound, invocation.target());
            Assert.equal("Twice", group.name(), "group name");
            Assert.equal(2L, group.candidates().size(), "overload count");
        });

        registry.test("tuple deconstruction and array foreach infer component types", () -> {
            Bound bound = clean("""
                    static class C {
                        static int F() {
                            var (number, text) = (1, "x");
                            foreach (var item in new int[] { 2 }) number += item;
                            return number;
                        }
                    }
                    """);
            DeclarationSyntax.Method method = method(bound.unit(), "F");
            StatementSyntax.LocalDeclaration deconstruction =
                    (StatementSyntax.LocalDeclaration) method.body().statements().get(0);
            PatternSyntax.Recursive designation = (PatternSyntax.Recursive)
                    deconstruction.variables().getFirst().designation();
            LocalSymbol number = (LocalSymbol) bound.declarations()
                    .declaredSymbol(designation.positional().get(0)).orElseThrow();
            LocalSymbol text = (LocalSymbol) bound.declarations()
                    .declaredSymbol(designation.positional().get(1)).orElseThrow();
            StatementSyntax.Foreach loop =
                    (StatementSyntax.Foreach) method.body().statements().get(1);
            LocalSymbol item = (LocalSymbol) bound.declarations()
                    .declaredSymbol(loop.variable()).orElseThrow();
            Assert.equal(BuiltinType.INT,
                    bound.expressions().inferredType(number).orElseThrow(), "tuple number");
            Assert.equal(BuiltinType.STRING,
                    bound.expressions().inferredType(text).orElseThrow(), "tuple text");
            Assert.equal(BuiltinType.INT,
                    bound.expressions().inferredType(item).orElseThrow(), "foreach item");
        });

        registry.test("parentheses and tuples retain typed semantic shape", () -> {
            Bound bound = clean("""
                    static class C {
                        static int P() => (1);
                        static (int, string) T() => (1, "x");
                    }
                    """);
            BoundExpression parenthesized = expression(bound,
                    method(bound.unit(), "P").expressionBody());
            Assert.isTrue(parenthesized instanceof BoundExpression.Parenthesized,
                    "parenthesized node");
            Assert.equal(BuiltinType.INT, parenthesized.type(), "parenthesized type");
            BoundExpression tuple = expression(bound, method(bound.unit(), "T").expressionBody());
            Assert.isTrue(tuple instanceof BoundExpression.Tuple, "tuple node");
            Assert.equal("(int, string)", tuple.type().displayName(), "tuple type");
        });
    }

    private static void registerOperators(TestRegistry registry) {
        registry.test("numeric promotion comparison and string concatenation bind", () -> {
            Bound bound = clean("""
                    static class C {
                        static long Mixed() => 1u + 2;
                        static string Text() => "value=" + 1;
                        static bool Compare() => 1 < 2.0;
                        static int Complement() => ~(byte)1;
                        static bool Logic(bool a, bool b) => a && !b;
                        static string Choose(string value) => value ?? "fallback";
                    }
                    """);
            Assert.equal(BuiltinType.LONG, typeOfBody(bound, "Mixed"), "uint plus int");
            Assert.equal(BuiltinType.STRING, typeOfBody(bound, "Text"), "concatenation");
            Assert.equal(BuiltinType.BOOL, typeOfBody(bound, "Compare"), "comparison");
            Assert.equal(BuiltinType.INT, typeOfBody(bound, "Complement"), "unary promotion");
            Assert.equal(BuiltinType.BOOL, typeOfBody(bound, "Logic"), "Boolean logic");
            Assert.equal(BuiltinType.STRING, typeOfBody(bound, "Choose"), "coalescing");
        });

        registry.test("simple and compound assignment retain target type", () -> {
            Bound bound = clean("""
                    static class C {
                        static long F() {
                            long value = 1;
                            value += 2;
                            value = 3;
                            return value;
                        }
                    }
                    """);
            DeclarationSyntax.Method method = method(bound.unit(), "F");
            StatementSyntax.Expression compound =
                    (StatementSyntax.Expression) method.body().statements().get(1);
            StatementSyntax.Expression simple =
                    (StatementSyntax.Expression) method.body().statements().get(2);
            Assert.equal(BuiltinType.LONG, expression(bound, compound.expression()).type(),
                    "compound result");
            Assert.equal(BuiltinType.LONG, expression(bound, simple.expression()).type(),
                    "simple result");
        });

        registry.test("literal constants convert to in-range narrow and enum targets", () -> {
            clean("""
                    enum State { None, Ready }
                    static class C {
                        static void F() {
                            byte small = 255;
                            ushort wide = 65535;
                            uint unsigned = 1;
                            ulong veryWide = 1L;
                            byte? optional = 1;
                            State zero = 0;
                        }
                    }
                    """);
        });

        registry.test("conditional expressions select a common numeric type", () -> {
            Bound bound = clean(
                    "static class C { static long F(bool pick) => pick ? 1 : 2L; }");
            Assert.equal(BuiltinType.LONG, typeOfBody(bound, "F"), "conditional type");
        });

        registry.test("nullable and enum operators use their predefined lifted forms", () -> {
            Bound bound = clean("""
                    enum Flags { First, Second }
                    static class C {
                        static int? Add(int? value) => value + 1;
                        static bool Compare(int? left, long? right) => left < right;
                        static bool? Both(bool? left, bool? right) => left & right;
                        static Flags Flip(Flags value) => ~value;
                        static Flags Merge(Flags left, Flags right) => left | right;
                    }
                    """);
            Assert.equal(new TypeSymbol.Nullable(BuiltinType.INT), typeOfBody(bound, "Add"),
                    "lifted arithmetic");
            Assert.equal(BuiltinType.BOOL, typeOfBody(bound, "Compare"),
                    "lifted relational result");
            Assert.equal(new TypeSymbol.Nullable(BuiltinType.BOOL), typeOfBody(bound, "Both"),
                    "nullable Boolean logical result");
            Assert.equal("Flags", typeOfBody(bound, "Flip").displayName(),
                    "enum complement");
            Assert.equal("Flags", typeOfBody(bound, "Merge").displayName(),
                    "enum logical result");
        });

        registry.test("enum arithmetic and relational operators use the predefined forms", () -> {
            Bound bound = clean("""
                    enum Flags { None, A = 1, B = 2 }
                    static class C {
                        static Flags PlusLeft(Flags value) => value + 1;
                        static Flags PlusRight(Flags value) => 1 + value;
                        static Flags MinusInt(Flags value) => value - 1;
                        static int MinusEnum(Flags left, Flags right) => left - right;
                        static bool Less(Flags left, Flags right) => left < right;
                        static bool GreaterEqual(Flags left, Flags right) => left >= right;
                        static bool ZeroEq(Flags value) => value == 0;
                        static bool ZeroNeq(Flags value) => 0 != value;
                    }
                    """);
            Assert.equal("Flags", typeOfBody(bound, "PlusLeft").displayName(),
                    "enum plus int");
            Assert.equal("Flags", typeOfBody(bound, "PlusRight").displayName(),
                    "int plus enum");
            Assert.equal("Flags", typeOfBody(bound, "MinusInt").displayName(),
                    "enum minus int");
            Assert.equal(BuiltinType.INT, typeOfBody(bound, "MinusEnum"),
                    "enum minus enum");
            Assert.equal(BuiltinType.BOOL, typeOfBody(bound, "Less"), "enum less than");
            Assert.equal(BuiltinType.BOOL, typeOfBody(bound, "GreaterEqual"),
                    "enum greater or equal");
            Assert.equal(BuiltinType.BOOL, typeOfBody(bound, "ZeroEq"),
                    "enum equals constant zero");
            Assert.equal(BuiltinType.BOOL, typeOfBody(bound, "ZeroNeq"),
                    "constant zero equals enum");
        });
    }

    private static void registerDiagnostics(TestRegistry registry) {
        registry.test("invalid explicit casts report VS0030 before lowering", () -> {
            Bound bound = bind("static class C { static int F(string value) => (int)value; }");
            Assert.equalList(List.of("VS0030"), codes(bound), "explicit cast diagnostic");
        });

        registry.test("a constant expression overflows at compile time", () -> {
            record Case(String body, List<String> expected, String reason) { }
            List<Case> cases = List.of(
                    new Case("const int a = int.MaxValue + 1;", List.of("VS0220"),
                            "a const initializer is a constant context"),
                    new Case("int b = int.MaxValue + 1;", List.of("VS0220"),
                            "a constant expression is checked wherever it is written"),
                    new Case("int d = checked(int.MaxValue + 1);", List.of("VS0220"),
                            "an explicit checked region"),
                    new Case("int h = int.MinValue - 1;", List.of("VS0220"), "subtraction"),
                    new Case("int i = 1000000 * 1000000;", List.of("VS0220"), "multiplication"),
                    new Case("int c = unchecked(int.MaxValue + 1);", List.of(),
                            "unchecked is the one way to ask for the wrapped value"),
                    new Case("int j = int.MaxValue - 1;", List.of(), "arithmetic that fits"),
                    new Case("int k = 2 * 3 + 4;", List.of(), "folding that does not overflow"),
                    // A promoted operand arrives wrapped in a conversion, which the evaluator
                    // folds only for a checked evaluation.
                    new Case("const long g = long.MaxValue + 1;", List.of("VS0220"),
                            "a promoted constant operand is checked too"),
                    new Case("long o = unchecked(long.MaxValue + 1);", List.of(),
                            "unchecked still wraps across a promotion"),
                    new Case("long q = int.MaxValue + 1L;", List.of(),
                            "widening that fits is not an overflow"),
                    new Case("long p = long.MaxValue - 1;", List.of(), "long arithmetic that fits"),
                    // Unary operands reach the gate too, and divide-by-constant-zero is the one
                    // constant failure `unchecked` does not excuse - there is no value for it to
                    // produce, so C# reports CS0020 wherever it is written.
                    new Case("const int r = -int.MinValue;", List.of("VS0220"),
                            "negation overflows in a constant context"),
                    new Case("int s = -int.MinValue;", List.of("VS0220"),
                            "negation is checked wherever it is written"),
                    new Case("int t = unchecked(-int.MinValue);", List.of(),
                            "unchecked wraps a negation too"),
                    new Case("int u = -5;", List.of(), "negation that fits"),
                    new Case("const int v = 1 / 0;", List.of("VS0020"),
                            "division by a constant zero"),
                    new Case("int w = unchecked(1 / 0);", List.of("VS0020"),
                            "unchecked does not excuse a division by constant zero"),
                    new Case("int x = 5 % 0;", List.of("VS0020"), "remainder by a constant zero"),
                    new Case("int y = 10 / 2;", List.of(), "division that has a value"));
            for (Case testCase : cases) {
                Bound bound = bind("static class C { static void R() { " + testCase.body() + " } }");
                Assert.equalList(testCase.expected(), codes(bound), testCase.reason());
            }
        });

        registry.test("optional parameter declaration rules are diagnosed", () -> {
            Bound bound = bind("""
                    static class C {
                        static int Next() => 1;
                        static int NonConstant(int x = Next()) => x;
                        static int DivideByZero(int x = 1 / 0) => x;
                        static int Overflow(int x = checked(2147483647 + 1)) => x;
                        static int BadOrder(int x = 1, int required) => x + required;
                        static int ByReference(ref int x = 1) => x;
                    }
                    """);
            Assert.equalList(List.of("VS1736", "VS0020", "VS0220", "VS1737", "VS1741"),
                    codes(bound),
                    "optional parameter declaration diagnostics");
        });

        registry.test("undefined names and invalid operators report without cascades", () -> {
            Bound bound = bind("""
                    static class C {
                        static void F() {
                            missing;
                            -"text";
                            true + 1;
                            if (1) {}
                        }
                    }
                    """);
            Assert.equalList(List.of("VS0103", "VS0023", "VS0019", "VS0029"), codes(bound),
                    "core expression diagnostics");
        });

        registry.test("invalid assignment conversion and targets are distinct", () -> {
            Bound bound = bind("""
                    static class C {
                        static void F() {
                            int value = 0;
                            value = 1L;
                            1 = 2;
                            const int fixedValue = 0;
                            fixedValue = 1;
                        }
                    }
                    """);
            Assert.equalList(List.of("VS0029", "VS0131", "VS0131"), codes(bound),
                    "assignment diagnostics");
        });

        registry.test("incompatible numeric families report once per operator", () -> {
            // `decimal` mixes with no floating-point type, and a *variable* of a signed type
            // mixes with no unsigned one: neither pair has a common type. A signed constant
            // is the documented exception, covered by the next test.
            Bound bound = bind("""
                    static class C {
                        static void F(long signed, ulong unsigned) {
                            var decimalAndDouble = 1m + 1.0;
                            var unsignedAndSigned = unsigned + signed;
                        }
                    }
                    """);
            Assert.equalList(List.of("VS0019", "VS0019"), codes(bound),
                    "numeric incompatibility diagnostics");
        });

        registry.test("invalid enum arithmetic reports VS0019 without cascades", () -> {
            Bound bound = bind("""
                    enum Flags { A, B }
                    static class C {
                        static void F(Flags value, int number) {
                            Flags both = value + value;
                            Flags wrong = number - value;
                            Flags mixed = value + 2.0;
                            bool ordered = value < number;
                            bool one = value == 1;
                        }
                    }
                    """);
            Assert.equalList(List.of("VS0019", "VS0019", "VS0019", "VS0019", "VS0019"),
                    codes(bound), "enum arithmetic diagnostics");
        });

        registry.test("a constant operand converts to the other operand's unsigned type", () -> {
            // C# 12.4.5 searches the predefined operators after each operand's implicit
            // conversions, so `unsigned - 1` selects operator -(ulong,ulong) through the
            // constant conversion of `1`. Only a constant does: `signed` above cannot.
            Bound bound = bind("""
                    static class C {
                        static void F(ulong unsigned, uint small) {
                            var subtracted = unsigned - 1;
                            var compared = small < 7;
                            var negative = unsigned + -1;
                        }
                    }
                    """);
            Assert.equalList(List.of("VS0019"), codes(bound),
                    "constant operand diagnostics");
        });

        registry.test("list patterns bind over arrays and strings", () -> {
            Bound bound = clean("""
                    static class C {
                        static void F(int[] xs, string s) {
                            bool a = xs is [];
                            bool b = xs is [1, 2];
                            bool c = xs is [var first, .., var last] && first < last;
                            bool d = xs is [1, .. var rest] && rest is [2];
                            bool e = s is ['a', ..];
                            bool f = xs is not [1, 2];
                        }
                    }
                    """);
            Assert.isTrue(bound.diagnostics().isEmpty(), "list patterns bind");
        });

        registry.test("a list pattern reports its own resolution failures", () -> {
            Bound bound = bind("""
                    static class C {
                        static void F(object o, int[] xs) {
                            bool a = o is [1, 2];
                            bool b = xs is [.., 1, ..];
                        }
                    }
                    """);
            Assert.equalList(List.of("VS8985", "VS8980"), codes(bound),
                    "uncountable target and duplicate-slice diagnostics");
        });

        registry.test("recursive patterns bind against the tested value's components", () -> {
            Bound bound = clean("""
                    public struct Point { public int X; public int Y; }
                    static class C {
                        static void F(object o, int[] xs, (int, string) pair, Point p) {
                            bool a = o is string { Length: 3 };
                            bool b = xs is { Length: > 0 };
                            bool c = pair is (1, var text) && text is not null;
                            bool d = p is Point { X: 1, Y: var y } && y > 0;
                            bool e = pair is { Item2: "x" };
                            bool f = o is Point { X: 1 } narrowed && narrowed.Y == 2;
                        }
                    }
                    """);
            Assert.isTrue(bound.diagnostics().isEmpty(),
                    "recursive patterns over tuples, structs, arrays and strings bind");
        });

        registry.test("a recursive pattern reports its own resolution failures", () -> {
            Bound bound = bind("""
                    public struct Plain { public int X; }
                    static class C {
                        static void F((int, int) pair, Plain plain) {
                            bool a = pair is (1, 2, 3);
                            bool b = plain is Plain(1);
                            bool c = pair is { Missing: 1 };
                        }
                    }
                    """);
            Assert.equalList(List.of("VS8502", "VS8129", "VS0117"), codes(bound),
                    "arity, no-deconstruction and unknown-member diagnostics");
        });

        registry.test("foreach still destructures with the same pattern syntax", () -> {
            // `foreach` uses a recursive pattern to take apart a value it already has, which
            // is implemented; only the testing positions are restricted.
            Bound bound = clean("""
                    static class C {
                        static void F((int, int)[] pairs) {
                            foreach (var (a, b) in pairs) {
                            }
                        }
                    }
                    """);
            Assert.isTrue(bound.diagnostics().isEmpty(), "foreach deconstruction stays legal");
        });

        registry.test("pattern designations are rejected under not and or", () -> {
            // C# 13 s12.3: a designation under `not` or `or` can never be definitely
            // assigned where the pattern matches, so declaring one is an error even though
            // the grammar admits it. `and` keeps the binding: both arms must have matched.
            Bound bound = bind("""
                    static class C {
                        static void F(object o) {
                            bool negated = o is not int badNot;
                            bool disjunctive = o is string or int badOr;
                            bool parenthesized = o is not (int badNested);
                            bool conjunctive = o is int good and > 0;
                        }
                    }
                    """);
            Assert.equalList(List.of("VS8780", "VS8780", "VS8780"), codes(bound),
                    "designation-under-not-or-or diagnostics");
        });

        registry.test("out of range literal conversion has its own stable diagnostic", () -> {
            Bound bound = bind("static class C { static void F() { byte value = 256; } }");
            Assert.equalList(List.of("VS0031"), codes(bound), "constant range diagnostic");
        });

        registry.test("var inference rejects missing null and method-group initializers", () -> {
            Bound bound = bind("""
                    static class C {
                        static int F() {
                            var missing;
                            var nullValue = null;
                            var group = F;
                            var self = self;
                            return 0;
                        }
                    }
                    """);
            Assert.equalList(List.of("VS0818", "VS0815", "VS0815", "VS0841"), codes(bound),
                    "var diagnostics");
        });

        registry.test("locals used before declaration report the dedicated diagnostic", () -> {
            Bound bound = bind("""
                    static class C {
                        static int F() {
                            return later;
                            int later = 1;
                        }
                    }
                    """);
            Assert.equalList(List.of("VS0841"), codes(bound), "declaration-order diagnostic");
        });

        registry.test("conditional branches without a common type report once", () -> {
            Bound bound = bind("""
                    static class C {
                        static void F() { var value = true ? "text" : 1; }
                    }
                    """);
            Assert.equalList(List.of("VS0173"), codes(bound), "conditional diagnostic");
        });

        registry.test("lock statement requires a reference type", () -> {
            Bound bound = bind("""
                    static class C {
                        static void F(int value) {
                            lock (value) {}
                            lock ("string") {}
                            lock (null) {}
                        }
                    }
                    """);
            Assert.equalList(List.of("VS0185"), codes(bound), "lock target diagnostic");
        });

        registry.test("a simple assignment target-types its value", () -> {
            // `value = default` has no type of its own; the target supplies it, exactly as a
            // declaration's initializer does. Binding the value blind left an error-typed node
            // that reached lowering and stopped the compiler with an internal error on valid
            // C#. A compound assignment is not target-typed and keeps its own rule.
            Bound bound = bind("""
                    static class C {
                        static void F() {
                            string text = "a";
                            text = default;
                            int count = 1;
                            count = default;
                            count += 2;
                        }
                    }
                    """);
            Assert.equalList(List.of(), codes(bound), "an assignment of default binds cleanly");
        });
    }

    private static TypeSymbol typeOfBody(Bound bound, String name) {
        return expression(bound, method(bound.unit(), name).expressionBody()).type();
    }

    private static BoundExpression expression(Bound bound, ExpressionSyntax syntax) {
        return bound.expressions().expressionFor(syntax)
                .orElseThrow(() -> new AssertionError("expression was not bound: " + syntax));
    }

    private static DeclarationSyntax.Method method(AuxiliarySyntax.CompilationUnit unit,
            String name) {
        DeclarationSyntax.StaticContainer container = unit.declarations().stream()
                .filter(DeclarationSyntax.StaticContainer.class::isInstance)
                .map(DeclarationSyntax.StaticContainer.class::cast)
                .findFirst().orElseThrow();
        return container.members().stream()
                .filter(DeclarationSyntax.Method.class::isInstance)
                .map(DeclarationSyntax.Method.class::cast)
                .filter(method -> method.name().equals(name))
                .findFirst().orElseThrow();
    }

    private static Bound clean(String text) {
        Bound bound = bind(text);
        Assert.isTrue(bound.diagnostics().isEmpty(),
                "unexpected diagnostics: " + bound.diagnostics().all());
        return bound;
    }

    private static Bound bind(String text) {
        SourceFile file = SourceFile.of("Expressions.vs", text);
        DiagnosticBag diagnostics = new DiagnosticBag();
        AuxiliarySyntax.CompilationUnit unit = Parser.parse(file, diagnostics);
        SemanticModel declarations = DeclarationBinder.bind(java.util.List.of(file), java.util.List.of(unit), diagnostics);
        ExpressionBinding expressions = ExpressionBinder.bind(file, unit, declarations,
                diagnostics);
        return new Bound(unit, declarations, expressions, diagnostics);
    }

    private static List<String> codes(Bound bound) {
        return bound.diagnostics().all().stream().map(Diagnostic::code)
                .map(Object::toString).toList();
    }

    private record Bound(AuxiliarySyntax.CompilationUnit unit, SemanticModel declarations,
            ExpressionBinding expressions, DiagnosticBag diagnostics) {}
}
