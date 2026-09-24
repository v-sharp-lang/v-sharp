package vsharp.tests.semantics;

import java.math.BigDecimal;
import java.util.List;
import vsharp.compiler.api.Compilation;
import vsharp.compiler.api.CompilationResult;
import vsharp.compiler.diagnostics.Diagnostic;
import vsharp.compiler.diagnostics.DiagnosticCode;
import vsharp.compiler.semantics.binding.BoundExpression;
import vsharp.compiler.semantics.constants.ConstantEvaluator;
import vsharp.compiler.semantics.constants.ConstantValue;
import vsharp.compiler.semantics.types.BuiltinType;
import vsharp.compiler.source.SourceSpan;
import vsharp.compiler.syntax.SyntaxKind;
import vsharp.tests.TestSources;
import vsharp.testkit.Assert;
import vsharp.testkit.TestRegistry;
import vsharp.testkit.TestSuite;

public final class ConstantTests implements TestSuite {

    private static final SourceSpan SPAN = new SourceSpan(0, 1);

    @Override
    public String suiteName() {
        return "semantic.constants";
    }

    @Override
    public void register(TestRegistry registry) {
        registry.test("literal evaluation", this::literalEvaluation);
        registry.test("unary operations", this::unaryOperations);
        registry.test("binary arithmetic", this::binaryArithmetic);
        registry.test("checked overflow", this::checkedOverflow);
        registry.test("unchecked overflow", this::uncheckedOverflow);
        registry.test("division by zero", this::divisionByZero);
        registry.test("string concatenation", this::stringConcatenation);
        registry.test("relational and logical", this::relationalAndLogical);
        registry.test("conditional evaluation", this::conditionalEvaluation);
        registerFieldConstants(registry);
    }

    // ---- Field constants ----------------------------------------------------------

    /// A `const` *field* is folded by the declaration-level constant pass, which is a
    /// separate entry into this same evaluator: the field's initializer is bound and folded
    /// before any other expression binds, because a later reader needs the answer. These cases
    /// assert that a field const admits exactly what a local const admits, that the failures
    /// are the real diagnostics rather than "cannot fold", and that the cycle a field can form
    /// - and a local cannot - terminates.
    private static void registerFieldConstants(TestRegistry registry) {
        registry.test("a field const folds the whole operator set", () -> {
            Assert.equalList(List.of(), codes(compile("""
                    static class C {
                        const int Max = int.MaxValue;
                        const int Base = 5;
                        const int Doubled = Base * 2;
                        const long Wide = Doubled;
                        const int Wrapped = unchecked(int.MaxValue + 1);
                        const string Tag = "v" + "1";
                        const double Half = 1.0 / 2;
                        const int FromChar = 'A' + 1;
                        const bool Cmp = Base < Doubled;
                    }
                    """)), "every folded form a local const accepts");
        });

        registry.test("a field const reads another file's field const", () -> {
            CompilationResult result = Compilation.of(List.of(
                    TestSources.styled("Lib.vs", """
                            namespace N {
                                static class Lib {
                                    public const int Base = 7;
                                }
                            }
                            """),
                    TestSources.styled("Use.vs", """
                            namespace N {
                                static class Use {
                                    const int Derived = Lib.Base * 3;
                                    static int F() {
                                        return Derived;
                                    }
                                }
                            }
                            """))).analyze();
            Assert.equalList(List.of(), codes(result), "cross-file constant resolution");
        });

        registry.test("a field const divided by constant zero reports VS0020", () -> {
            Assert.equalList(List.of("VS0020"), codes(compile("""
                    static class C {
                        const int D = 1 / 0;
                    }
                    """)), "the real division diagnostic, not a fold stub");
        });

        registry.test("a field const overflowing in checked mode reports VS0220", () -> {
            Assert.equalList(List.of("VS0220"), codes(compile("""
                    static class C {
                        const int Over = int.MaxValue + 1;
                    }
                    """)), "the real overflow diagnostic, not a fold stub");
        });

        registry.test("a circular field const terminates with VS0110", () -> {
            CompilationResult result = compile("""
                    static class C {
                        const int A = B;
                        const int B = A;
                    }
                    """);
            Assert.equalList(List.of("VS0110"), codes(result),
                    "a cycle is a diagnostic, not a stack overflow");
            Assert.contains(result.diagnostics().getFirst().message(), "circular",
                    "the diagnostic names the cycle");
        });

        registry.test("a field const reading itself terminates with VS0110", () -> {
            Assert.equalList(List.of("VS0110"), codes(compile("""
                    static class C {
                        const int Self = Self + 1;
                    }
                    """)), "the shortest cycle is still a cycle");
        });

        registry.test("a non-constant field initializer reports VS0133", () -> {
            CompilationResult result = compile("""
                    static class C {
                        static int Get() {
                            return 1;
                        }
                        const int X = Get();
                    }
                    """);
            Assert.equalList(List.of("VS0133"), codes(result),
                    "a call is a user error, not a limit of this build");
            Assert.contains(result.diagnostics().getFirst().message(), "must be constant",
                    "the diagnostic blames the expression, not the compiler");
            Assert.contains(result.diagnostics().getFirst().message(), "C.X",
                    "the diagnostic names the constant");
        });

        registry.test("a const local reading another const local still compiles", () -> {
            // Pins the reason CS0133 is not yet enforced on locals: the evaluator cannot fold
            // a read of a const local, so a constness gate on this path would reject this
            // perfectly valid program. If a local ever carries its folded value, this case
            // must keep passing while a non-constant local initializer starts failing.
            Assert.equalList(List.of(), codes(compile("""
                    static class C {
                        static int F() {
                            const int Max = int.MaxValue;
                            const int Half = Max / 2;
                            return Half;
                        }
                    }
                    """)), "a const local may read a preceding const local");
        });

        registry.test("a concatenation with an enum operand is not folded", () -> {
            // C# admits only string and null constant operands in a constant of string type
            // (§12.23), and the run-time concatenation prints the member *name*. Folding the
            // pair would print the underlying number instead, so the fold must refuse - which
            // is what keeps `const string S = "x" + Status.Draft;` an error rather than a
            // silently wrong constant.
            Assert.equalList(List.of("VS0133"), codes(compile("""
                    enum Status {
                        Draft
                    }
                    static class C {
                        const string S = "x" + Status.Draft;
                    }
                    """)), "an enum operand leaves the concatenation non-constant");
        });
    }

    private static CompilationResult compile(String text) {
        return Compilation.of(List.of(TestSources.styled("Const.vs", text))).analyze();
    }

    private static List<String> codes(CompilationResult result) {
        return result.diagnostics().stream().map(Diagnostic::code).map(Object::toString).toList();
    }

    private void literalEvaluation() {
        BoundExpression intLit = new BoundExpression.Literal(SPAN, BuiltinType.INT, 42);
        ConstantEvaluator.Result r1 = ConstantEvaluator.evaluate(intLit, false);
        Assert.isTrue(r1 instanceof ConstantEvaluator.Result.Value, "int literal must evaluate to value");
        Assert.equal(new ConstantValue.Int(42), ((ConstantEvaluator.Result.Value) r1).value(), "int literal 42");

        BoundExpression strLit = new BoundExpression.Literal(SPAN, BuiltinType.STRING, "vsharp");
        ConstantEvaluator.Result r2 = ConstantEvaluator.evaluate(strLit, false);
        Assert.equal(new ConstantValue.StringVal("vsharp"), ((ConstantEvaluator.Result.Value) r2).value(), "string literal vsharp");
    }

    private void unaryOperations() {
        BoundExpression intLit = new BoundExpression.Literal(SPAN, BuiltinType.INT, 10);
        BoundExpression neg = new BoundExpression.Unary(SPAN, BuiltinType.INT, SyntaxKind.MINUS, intLit);
        ConstantEvaluator.Result r1 = ConstantEvaluator.evaluate(neg, false);
        Assert.equal(new ConstantValue.Int(-10), ((ConstantEvaluator.Result.Value) r1).value(), "negate 10");

        BoundExpression not = new BoundExpression.Unary(SPAN, BuiltinType.BOOL, SyntaxKind.EXCLAMATION,
                new BoundExpression.Literal(SPAN, BuiltinType.BOOL, false));
        ConstantEvaluator.Result r2 = ConstantEvaluator.evaluate(not, false);
        Assert.equal(new ConstantValue.Boolean(true), ((ConstantEvaluator.Result.Value) r2).value(), "not false");
    }

    private void binaryArithmetic() {
        BoundExpression left = new BoundExpression.Literal(SPAN, BuiltinType.INT, 15);
        BoundExpression right = new BoundExpression.Literal(SPAN, BuiltinType.INT, 27);
        BoundExpression add = new BoundExpression.Binary(SPAN, BuiltinType.INT, left, SyntaxKind.PLUS, right);

        ConstantEvaluator.Result res = ConstantEvaluator.evaluate(add, false);
        Assert.equal(new ConstantValue.Int(42), ((ConstantEvaluator.Result.Value) res).value(), "15 + 27");
    }

    private void checkedOverflow() {
        BoundExpression max = new BoundExpression.Literal(SPAN, BuiltinType.INT, Integer.MAX_VALUE);
        BoundExpression one = new BoundExpression.Literal(SPAN, BuiltinType.INT, 1);
        BoundExpression add = new BoundExpression.Binary(SPAN, BuiltinType.INT, max, SyntaxKind.PLUS, one);

        ConstantEvaluator.Result res = ConstantEvaluator.evaluate(add, true);
        Assert.isTrue(res instanceof ConstantEvaluator.Result.Failure, "checked overflow must fail");
        Assert.equal(DiagnosticCode.COMPILE_TIME_OVERFLOW, ((ConstantEvaluator.Result.Failure) res).code(), "overflow diagnostic");
    }

    private void uncheckedOverflow() {
        BoundExpression max = new BoundExpression.Literal(SPAN, BuiltinType.INT, Integer.MAX_VALUE);
        BoundExpression one = new BoundExpression.Literal(SPAN, BuiltinType.INT, 1);
        BoundExpression add = new BoundExpression.Binary(SPAN, BuiltinType.INT, max, SyntaxKind.PLUS, one);

        ConstantEvaluator.Result res = ConstantEvaluator.evaluate(add, false);
        Assert.isTrue(res instanceof ConstantEvaluator.Result.Value, "unchecked overflow must wrap");
        Assert.equal(new ConstantValue.Int(Integer.MIN_VALUE), ((ConstantEvaluator.Result.Value) res).value(), "wrap overflow");
    }

    private void divisionByZero() {
        BoundExpression ten = new BoundExpression.Literal(SPAN, BuiltinType.INT, 10);
        BoundExpression zero = new BoundExpression.Literal(SPAN, BuiltinType.INT, 0);
        BoundExpression div = new BoundExpression.Binary(SPAN, BuiltinType.INT, ten, SyntaxKind.SLASH, zero);

        ConstantEvaluator.Result res = ConstantEvaluator.evaluate(div, false);
        Assert.isTrue(res instanceof ConstantEvaluator.Result.Failure, "divide by zero must fail");
        Assert.equal(DiagnosticCode.DIVISION_BY_ZERO, ((ConstantEvaluator.Result.Failure) res).code(), "divide by zero diagnostic");
    }

    private void stringConcatenation() {
        BoundExpression s1 = new BoundExpression.Literal(SPAN, BuiltinType.STRING, "Hello, ");
        BoundExpression s2 = new BoundExpression.Literal(SPAN, BuiltinType.STRING, "world!");
        BoundExpression concat = new BoundExpression.Binary(SPAN, BuiltinType.STRING, s1, SyntaxKind.PLUS, s2);

        ConstantEvaluator.Result r1 = ConstantEvaluator.evaluate(concat, false);
        Assert.equal(new ConstantValue.StringVal("Hello, world!"), ((ConstantEvaluator.Result.Value) r1).value(), "string concat");
    }

    private void relationalAndLogical() {
        BoundExpression five = new BoundExpression.Literal(SPAN, BuiltinType.INT, 5);
        BoundExpression ten = new BoundExpression.Literal(SPAN, BuiltinType.INT, 10);
        BoundExpression lt = new BoundExpression.Binary(SPAN, BuiltinType.BOOL, five, SyntaxKind.LESS_THAN, ten);

        ConstantEvaluator.Result r1 = ConstantEvaluator.evaluate(lt, false);
        Assert.equal(new ConstantValue.Boolean(true), ((ConstantEvaluator.Result.Value) r1).value(), "5 < 10");
    }

    private void conditionalEvaluation() {
        BoundExpression cond = new BoundExpression.Literal(SPAN, BuiltinType.BOOL, true);
        BoundExpression whenTrue = new BoundExpression.Literal(SPAN, BuiltinType.INT, 100);
        BoundExpression whenFalse = new BoundExpression.Literal(SPAN, BuiltinType.INT, 200);
        BoundExpression ternary = new BoundExpression.Conditional(SPAN, BuiltinType.INT, cond, whenTrue, whenFalse);

        ConstantEvaluator.Result res = ConstantEvaluator.evaluate(ternary, false);
        Assert.equal(new ConstantValue.Int(100), ((ConstantEvaluator.Result.Value) res).value(), "ternary true");
    }
}
