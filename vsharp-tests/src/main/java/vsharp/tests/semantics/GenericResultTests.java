package vsharp.tests.semantics;

import vsharp.testkit.Assert;
import vsharp.testkit.TestRegistry;
import vsharp.testkit.TestSuite;
import vsharp.tests.EmittedProgram;

/// The result boundary of a generic call.
///
/// A generic declaration returns its erasure, so every call whose result is used has to narrow
/// it back - unbox for a value type, `checkcast` for a reference one. Two paths skipped that
/// narrowing and produced class files the JVM refuses to link, from compilations that reported
/// nothing: a result whose type only a lambda could supply, and any call that also had a
/// `ref`/`out` parameter.
///
/// Every case runs the program, because that is the only assertion that would have failed on
/// the broken versions.
public final class GenericResultTests implements TestSuite {

    @Override
    public String suiteName() {
        return "semantic.generic-results";
    }

    @Override
    public void register(TestRegistry registry) {
        registry.test("a result only a lambda supplies is unboxed", this::lambdaSuppliedValue);
        registry.test("a result only a lambda supplies is narrowed",
                this::lambdaSuppliedReference);
        registry.test("a ref call narrows its own result", this::refCallResult);
        registry.test("a ref call narrows a reference result", this::refCallReferenceResult);
        registry.test("a discarded erased result is popped, not narrowed",
                this::discardedResultIsPopped);
        registry.test("a targeted result closes against the target", this::targetedResultCloses);
        registry.test("a result nothing can infer is still refused", this::unresolvableResult);
    }

    private void targetedResultCloses() {
        // The C#-portable form of the same shape, and the one an oracle can check: `Func<T, R>`
        // is a functional interface here, so this program compiles under both compilers
        // and prints the same four lines. A `return` supplies a target type, which closes the
        // lambda's *parameter* before the lambda is bound - and used to leave the result open,
        // because closing it had only ever happened as a side effect of reading the lambda.
        String source = """
            static class Seq {
                public static R Map<T, R>(T value, Func<T, R> mapper) { return mapper(value); }
            }
            static class Targeted {
                public static int Direct() { return Seq.Map(3, v => v * 2); }
                public static string Run() {
                    int doubled = Seq.Map(21, v => v * 2);
                    return Direct() + "|" + Seq.Map("x", v => v + "!") + "|" + doubled
                            + "|" + Seq.Map(5, v => v > 3);
                }
            }
            """;
        EmittedProgram.assertResult("Targeted", source, "Targeted", "Run", "6|x!|42|True");
    }

    private void unresolvableResult() {
        // Closing against the target must not become a way to invent a type argument nothing
        // supplies: a result parameter no argument and no target mentions is still CS0411.
        String source = """
            static class Seq {
                public static R Make<T, R>(T value) { return default; }
            }
            static class Unresolvable {
                public static void Run() { var made = Seq.Make(3); }
            }
            """;
        Assert.isTrue(EmittedProgram.refusalCodes(source).contains("VS0411"),
                "an uninferable type argument is reported, not guessed");
    }

    private void lambdaSuppliedValue() {
        // `R` is supplied by nothing except the lambda's own body, so it is still open on the
        // selected symbol when the call node is built and is closed afterwards. The closed type
        // is what chose `int` for the surrounding context, and what the narrowing must follow.
        String source = """
            using java.util.function;
            static class Seq {
                public static R Map<T, R>(T value, Function<T, R> mapper) {
                    return mapper.Apply(value);
                }
            }
            static class LambdaValue {
                public static int Run() { return Seq.Map(3, v => v * 2); }
            }
            """;
        EmittedProgram.assertResult("LambdaValue", source, "LambdaValue", "Run", 6);
    }

    private void lambdaSuppliedReference() {
        String source = """
            using java.util.function;
            static class Seq {
                public static R Map<T, R>(T value, Function<T, R> mapper) {
                    return mapper.Apply(value);
                }
            }
            static class LambdaReference {
                public static string Run() { return Seq.Map("x", v => v + "!").Length + "y"; }
            }
            """;
        EmittedProgram.assertResult("LambdaReference", source, "LambdaReference", "Run", "2y");
    }

    private void refCallResult() {
        // The ref/out call path returns early from the ordinary one, and never adapted the
        // result at all: an erased `object` was left where an `int` was required.
        String source = """
            static class Seq {
                public static T Pick<T>(ref T value, T replacement) {
                    T previous = value;
                    value = replacement;
                    return previous;
                }
            }
            static class RefResult {
                public static int Run() {
                    int number = 5;
                    int previous = Seq.Pick(ref number, 9);
                    return previous * 10 + number;
                }
            }
            """;
        EmittedProgram.assertResult("RefResult", source, "RefResult", "Run", 59);
    }

    private void refCallReferenceResult() {
        String source = """
            static class Seq {
                public static T Pick<T>(ref T value, T replacement) {
                    T previous = value;
                    value = replacement;
                    return previous;
                }
            }
            static class RefReference {
                public static string Run() {
                    string text = "a";
                    string old = Seq.Pick(ref text, "b");
                    return old + text;
                }
            }
            """;
        EmittedProgram.assertResult("RefReference", source, "RefReference", "Run", "ab");
    }

    private void discardedResultIsPopped() {
        // The narrowing is skipped deliberately when the result is discarded, so that an
        // absent erased value is not unboxed into a NullPointerException the program never
        // asked for. Keeping a case here means the the design fix cannot quietly undo that rule.
        String source = """
            using java.util.function;
            static class Seq {
                public static R Map<T, R>(T value, Function<T, R> mapper) {
                    return mapper.Apply(value);
                }
            }
            static class Discarded {
                static int calls;
                public static int Run() {
                    Seq.Map(3, v => v * 2);
                    calls = calls + 1;
                    return calls;
                }
            }
            """;
        Assert.equal(1, EmittedProgram.of("Discarded", source).invoke("Discarded", "Run"),
                "a discarded erased result does not disturb the statement after it");
    }
}
