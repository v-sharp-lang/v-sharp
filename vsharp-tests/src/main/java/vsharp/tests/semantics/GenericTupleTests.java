package vsharp.tests.semantics;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import vsharp.testkit.Assert;
import vsharp.testkit.TestRegistry;
import vsharp.testkit.TestSuite;
import vsharp.tests.EmittedProgram;

/// Tuples as generic carriers.
///
/// A tuple's descriptor does not mention its elements: every V# tuple reaches a parameter or
/// result position as `Ljava/lang/Object;`, and each element access re-establishes the concrete
/// type with the `checkcast` to `VsTupleN` it already emitted. `(T, T)` and `(int, int)` are
/// therefore the same JVM signature, which is what makes this a carrier decision rather than a
/// representation.
public final class GenericTupleTests implements TestSuite {

    @Override
    public String suiteName() {
        return "semantic.generic-tuples";
    }

    @Override
    public void register(TestRegistry registry) {
        registry.test("a tuple result is built from type parameters", this::tupleResult);
        registry.test("a tuple parameter infers its elements", this::tupleParameterInference);
        registry.test("a tuple reorders two type parameters", this::tupleReorders);
        registry.test("a tuple element of a value type round-trips", this::valueElements);
        registry.test("a ref tuple parameter writes back", this::refTupleParameter);
        registry.test("the emitted tuple descriptor names no element", this::descriptorIsOpaque);
    }

    private void tupleResult() {
        String source = """
            static class Seq {
                public static (T, T) Pair<T>(T left, T right) { return (left, right); }
            }
            static class TupleResult {
                public static string Run() {
                    (string, string) names = Seq.Pair("a", "b");
                    return names.Item1 + names.Item2;
                }
            }
            """;
        EmittedProgram.assertResult("TupleResult", source, "TupleResult", "Run", "ab");
    }

    private void tupleParameterInference() {
        // Inference had no tuple case at all, so `(T, T)` never unified with `(int, int)` and
        // the call was refused as an argument conversion failure before the carrier was ever
        // consulted. Matching runs element by element when the arities agree.
        String source = """
            static class Seq {
                public static T FirstOf<T>((T, T) pair) { return pair.Item1; }
            }
            static class TupleParam {
                public static int Run() {
                    (int, int) numbers = (4, 5);
                    return Seq.FirstOf(numbers);
                }
            }
            """;
        EmittedProgram.assertResult("TupleParam", source, "TupleParam", "Run", 4);
    }

    private void tupleReorders() {
        String source = """
            static class Seq {
                public static (R, T) Swap<T, R>((T, R) pair) { return (pair.Item2, pair.Item1); }
            }
            static class Reorder {
                public static string Run() {
                    (string, int) mixed = ("x", 7);
                    (int, string) swapped = Seq.Swap(mixed);
                    return swapped.Item1 + "," + swapped.Item2;
                }
            }
            """;
        EmittedProgram.assertResult("Reorder", source, "Reorder", "Run", "7,x");
    }

    private void valueElements() {
        // The elements inside `VsTupleN` are erased to `object`, so a value type is boxed on
        // construction and unboxed on access. Summing them proves both halves.
        String source = """
            static class Seq {
                public static (T, T) Pair<T>(T left, T right) { return (left, right); }
            }
            static class ValueElements {
                public static int Run() {
                    (int, int) numbers = Seq.Pair(1, 2);
                    return numbers.Item1 * 10 + numbers.Item2;
                }
            }
            """;
        EmittedProgram.assertResult("ValueElements", source, "ValueElements", "Run", 12);
    }

    private void refTupleParameter() {
        // Both carriers at once: a tuple inside the erased `ref` cell.
        String source = """
            static class Seq {
                public static void Widen<T>(ref (T, T) pair, T value) { pair = (value, value); }
            }
            static class RefTuple {
                public static string Run() {
                    (string, string) names = ("a", "b");
                    Seq.Widen(ref names, "z");
                    (int, int) numbers = (1, 2);
                    Seq.Widen(ref numbers, 3);
                    return names.Item1 + names.Item2 + numbers.Item1 + numbers.Item2;
                }
            }
            """;
        EmittedProgram.assertResult("RefTuple", source, "RefTuple", "Run", "zz33");
    }

    private void descriptorIsOpaque() {
        String source = """
            static class Seq {
                public static (T, T) Pair<T>(T left, T right) { return (left, right); }
            }
            static class Opaque {
                public static string Run() {
                    (string, string) names = Seq.Pair("a", "b");
                    return names.Item1;
                }
            }
            """;
        EmittedProgram program = EmittedProgram.of("Opaque", source);
        ClassModel holder = ClassFile.of().parse(program.bytes("Seq"));
        String descriptor = holder.methods().stream()
                .filter(method -> method.methodName().stringValue().equals("Pair"))
                .findFirst()
                .orElseThrow()
                .methodType()
                .stringValue();
        Assert.equal("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", descriptor,
                "a tuple result carries no element type into the descriptor");
    }
}
