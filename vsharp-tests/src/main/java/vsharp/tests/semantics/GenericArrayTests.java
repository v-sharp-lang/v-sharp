package vsharp.tests.semantics;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.TypeCheckInstruction;
import java.util.List;
import vsharp.testkit.Assert;
import vsharp.testkit.TestRegistry;
import vsharp.testkit.TestSuite;
import vsharp.tests.EmittedProgram;

/// Type-parameter-dependent array parameters and results.
///
/// `T[]` is `[Ljava/lang/Object;` in the descriptor, so the whole feature rests on one JVM
/// property: array covariance lets any reference array stand in for an `object[]`, and the
/// caller narrows the result back with `checkcast`. These cases pin both halves - what that
/// admits, and the two shapes erasure cannot represent, which are refused by name rather than
/// approximated.
public final class GenericArrayTests implements TestSuite {

    @Override
    public String suiteName() {
        return "semantic.generic-arrays";
    }

    @Override
    public void register(TestRegistry registry) {
        registry.test("a reference array is passed to a type-parameter array",
                this::referenceArrayArgument);
        registry.test("a type-parameter array result is narrowed back",
                this::arrayResultIsNarrowed);
        registry.test("the callee writes through to the caller's array",
                this::writesThroughToCaller);
        registry.test("a jagged array is passed to a jagged type-parameter array",
                this::jaggedArgument);
        registry.test("a jagged array infers the element type of a single-rank parameter",
                this::jaggedInfersArrayElement);
        registry.test("an array deeper than the parameter is still an object array",
                this::deeperPrimitiveArrayIsReference);
        registry.test("a generic extension method extends every reference array",
                this::genericArrayExtension);
        registry.test("the emitted descriptor is the erased object array",
                this::emittedDescriptorIsErased);
        registry.test("a generic params parameter expands loose arguments",
                this::genericParamsExpands);
        registry.test("a ready-made array takes the normal form of a generic params parameter",
                this::genericParamsNormalForm);
        registry.test("a generic params parameter follows fixed parameters",
                this::genericParamsAfterFixed);

        registry.test("a primitive array is refused by name", this::primitiveArrayRefused);
        registry.test("creating an array of a type parameter is refused",
                this::arrayCreationRefused);
        registry.test("a collection expression of a type parameter is refused",
                this::collectionOfTypeParameterRefused);
    }

    // ---- accepted ------------------------------------------------------------------

    private void referenceArrayArgument() {
        String source = """
            static class Seq {
                public static T Second<T>(T[] items) { return items[1]; }
                public static int Count<T>(T[] items) { return items.Length; }
            }
            static class RefArray {
                public static string Run() {
                    string[] names = { "a", "b", "c" };
                    return Seq.Second(names) + Seq.Count(names);
                }
            }
            """;
        EmittedProgram.assertResult("RefArray", source, "RefArray", "Run", "b3");
    }

    private void arrayResultIsNarrowed() {
        // The declaration returns the erased `object[]`; the call site must narrow it before
        // the result can be indexed as a `string[]`. Indexing the result is what proves the
        // `checkcast` is there and correct - a missing one is a verifier error, a wrong one a
        // ClassCastException.
        String source = """
            static class Seq {
                public static T[] Echo<T>(T[] items) { return items; }
            }
            static class Narrowed {
                public static string Run() {
                    string[] names = { "a", "b", "c" };
                    string[] echoed = Seq.Echo(names);
                    return echoed[2] + echoed.Length;
                }
            }
            """;
        EmittedProgram.assertResult("Narrowed", source, "Narrowed", "Run", "c3");
    }

    private void writesThroughToCaller() {
        // The array is passed, never copied: a boxed `Integer[]` copy would have compiled and
        // silently lost this. C# has the same guarantee for the same reason - an array is a
        // reference - so the observable behaviour matches.
        String source = """
            static class Seq {
                public static void Fill<T>(T[] items, T value) {
                    for (int i = 0; i < items.Length; i++) { items[i] = value; }
                }
            }
            static class WriteThrough {
                public static string Run() {
                    string[] target = { "x", "y" };
                    Seq.Fill(target, "z");
                    return target[0] + target[1];
                }
            }
            """;
        EmittedProgram.assertResult("WriteThrough", source, "WriteThrough", "Run", "zz");
    }

    private void jaggedArgument() {
        String source = """
            static class Seq {
                public static int Cells<T>(T[][] rows) {
                    int total = 0;
                    for (int i = 0; i < rows.Length; i++) { total = total + rows[i].Length; }
                    return total;
                }
            }
            static class Jagged {
                public static int Run() {
                    string[] first = { "a", "b", "c" };
                    string[] second = { "d" };
                    string[][] rows = { first, second };
                    return Seq.Cells(rows);
                }
            }
            """;
        EmittedProgram.assertResult("Jagged", source, "Jagged", "Run", 4);
    }

    private void jaggedInfersArrayElement() {
        // C# §12.6.3.10 matches one indexing step at a time, so `T[]` against `string[][]`
        // infers `T = string[]`. Comparing whole rank chains refused this, which is what the
        // jagged receiver of a generic extension method ran into first.
        String source = """
            static class Seq {
                public static T First<T>(T[] items) { return items[0]; }
            }
            static class JaggedInference {
                public static int Run() {
                    string[] first = { "a", "b", "c" };
                    string[] second = { "d" };
                    string[][] rows = { first, second };
                    return Seq.First(rows).Length;
                }
            }
            """;
        EmittedProgram.assertResult("JaggedInference", source, "JaggedInference", "Run", 3);
    }

    private void deeperPrimitiveArrayIsReference() {
        // `int[][]` is refused nowhere: its component is `[I`, a reference, so the value *is*
        // an `object[]`. Only an argument of the declaration's own depth has to present a
        // reference leaf, which is exactly the JVM's rule and not a rank comparison.
        String source = """
            static class Seq {
                public static int Rows<T>(T[] items) { return items.Length; }
            }
            static class DeepPrimitive {
                public static int Run() {
                    int[] first = { 1, 2, 3 };
                    int[] second = { 4 };
                    int[][] grid = { first, second };
                    return Seq.Rows(grid);
                }
            }
            """;
        EmittedProgram.assertResult("DeepPrimitive", source, "DeepPrimitive", "Run", 2);
    }

    private void genericArrayExtension() {
        String source = """
            static class Seq {
                public static T First<T>(this T[] items) { return items[0]; }
            }
            static class ArrayExtension {
                public static string Run() {
                    string[] names = { "a", "b" };
                    string[][] rows = { names };
                    return names.First() + rows.First().Length;
                }
            }
            """;
        EmittedProgram.assertResult("ArrayExtension", source, "ArrayExtension", "Run", "a2");
    }

    private void emittedDescriptorIsErased() {
        String source = """
            static class Seq {
                public static T[] Echo<T>(T[] items) { return items; }
            }
            static class Descriptor {
                public static int Run() {
                    string[] names = { "a" };
                    return Seq.Echo(names).Length;
                }
            }
            """;
        EmittedProgram program = EmittedProgram.of("Descriptor", source);
        ClassModel holder = ClassFile.of().parse(program.bytes("Seq"));
        MethodModel echo = holder.methods().stream()
                .filter(method -> method.methodName().stringValue().equals("Echo"))
                .findFirst()
                .orElseThrow();
        Assert.equal("([Ljava/lang/Object;)[Ljava/lang/Object;",
                echo.methodType().stringValue(),
                "a type-parameter array is erased to an object array in both positions");

        ClassModel caller = ClassFile.of().parse(program.bytes("Descriptor"));
        List<String> casts = caller.methods().stream()
                .filter(method -> method.methodName().stringValue().equals("Run"))
                .findFirst()
                .orElseThrow()
                .code()
                .orElseThrow()
                .elementStream()
                .filter(TypeCheckInstruction.class::isInstance)
                .map(TypeCheckInstruction.class::cast)
                .map(instruction -> instruction.type().asInternalName())
                .toList();
        Assert.equalList(List.of("[Ljava/lang/String;"), casts,
                "the caller narrows the erased result back to its own array type");
    }

    private void genericParamsExpands() {
        // Inference has to see the *element* type while the candidate is considered in
        // expanded form; matching three `string` arguments against `T[]` itself infers
        // nothing, which used to fail the call with "no overload takes 3 arguments".
        // The zero-argument case writes its type argument, exactly as C# requires when no
        // argument can supply one.
        String source = """
            static class Seq {
                public static int Count<T>(params T[] items) { return items.Length; }
            }
            static class Expanded {
                public static string Run() {
                    return Seq.Count("a", "b", "c") + "|" + Seq.Count("a") + "|"
                            + Seq.Count<string>();
                }
            }
            """;
        EmittedProgram.assertResult("Expanded", source, "Expanded", "Run", "3|1|0");
    }

    private void genericParamsNormalForm() {
        // A ready-made array is passed, not wrapped in another one: normal form wins when
        // both apply, which is the rule that keeps `Count(names)` meaning two and not one.
        String source = """
            static class Seq {
                public static int Count<T>(params T[] items) { return items.Length; }
            }
            static class NormalForm {
                public static int Run() {
                    string[] ready = { "x", "y" };
                    return Seq.Count(ready);
                }
            }
            """;
        EmittedProgram.assertResult("NormalForm", source, "NormalForm", "Run", 2);
    }

    private void genericParamsAfterFixed() {
        String source = """
            static class Seq {
                public static string Join<T>(string separator, params T[] items) {
                    string result = "";
                    for (int i = 0; i < items.Length; i++) {
                        if (i > 0) { result = result + separator; }
                        result = result + items[i];
                    }
                    return result;
                }
            }
            static class Fixed {
                public static string Run() {
                    string[] ready = { "x", "y" };
                    return Seq.Join("-", "a", "b") + Seq.Join(",", ready);
                }
            }
            """;
        EmittedProgram.assertResult("Fixed", source, "Fixed", "Run", "a-bx,y");
    }

    // ---- refused -------------------------------------------------------------------

    private void primitiveArrayRefused() {
        String source = """
            static class Seq {
                public static T First<T>(T[] items) { return items[0]; }
            }
            static class Primitive {
                public static int Run() {
                    int[] values = { 1, 2 };
                    return Seq.First(values);
                }
            }
            """;
        List<String> codes = EmittedProgram.refusalCodes(source);
        Assert.isTrue(codes.contains("VS20019"),
                "a primitive array cannot carry a type-parameter array");
    }

    private void arrayCreationRefused() {
        // Without this the JVM allocates the erasure and the caller's narrowing cast fails at
        // run time on a value the program built correctly - a wrong answer, not a gap.
        String source = """
            static class Seq {
                public static T[] Copy<T>(T[] items) {
                    T[] result = new T[items.Length];
                    for (int i = 0; i < items.Length; i++) { result[i] = items[i]; }
                    return result;
                }
            }
            """;
        Assert.isTrue(EmittedProgram.refusalCodes(source).contains("VS20020"),
                "an array of a type parameter has no run-time element type");
    }

    private void collectionOfTypeParameterRefused() {
        String source = """
            static class Seq {
                public static T[] Pair<T>(T first, T second) {
                    T[] values = [first, second];
                    return values;
                }
            }
            """;
        Assert.isTrue(EmittedProgram.refusalCodes(source).contains("VS20020"),
                "a collection expression targeting a type-parameter array is the same allocation");
    }

}
