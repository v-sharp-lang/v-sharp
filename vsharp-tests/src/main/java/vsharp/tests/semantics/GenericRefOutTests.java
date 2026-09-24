package vsharp.tests.semantics;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import vsharp.testkit.Assert;
import vsharp.testkit.TestRegistry;
import vsharp.testkit.TestSuite;
import vsharp.tests.EmittedProgram;

/// Generic `ref`/`out` parameters.
///
/// A `ref T` is the declaration's erasure wrapped in the one-element cell `ref` already used:
/// `[Ljava/lang/Object;`, whatever the call specialized `T` to. The value is adapted into the
/// cell and back out of it, which is the same boundary a by-value `T` crosses, so a value-type
/// argument travels boxed and is unboxed on copy-back.
///
/// Every case here *loads* the emitted class. That is deliberate: the first version of this
/// work compiled clean and produced a `VerifyError`, which only class loading reveals.
public final class GenericRefOutTests implements TestSuite {

    @Override
    public String suiteName() {
        return "semantic.generic-refs";
    }

    @Override
    public void register(TestRegistry registry) {
        registry.test("a generic ref parameter swaps reference values", this::refReferenceType);
        registry.test("a generic ref parameter swaps value-type values", this::refValueType);
        registry.test("a generic out parameter assigns the caller's variable", this::outParameter);
        registry.test("a generic out parameter accepts an out declaration", this::outDeclaration);
        registry.test("a generic ref parameter writes back to a field", this::refField);
        registry.test("a reference cell is forwarded to a generic ref parameter",
                this::forwardedReferenceCell);
        registry.test("a primitive cell is copied through a generic ref parameter",
                this::copiedPrimitiveCell);
        registry.test("a by-value argument of a generic ref call is adapted too",
                this::byValueArgumentIsAdapted);
        registry.test("the emitted cell is the erased object array", this::emittedCellIsErased);
        registry.test("a ref parameter carries a nullable of a type parameter",
                this::refNullableParameter);
    }

    private void refReferenceType() {
        String source = """
            static class Seq {
                public static void Swap<T>(ref T left, ref T right) {
                    T temp = left;
                    left = right;
                    right = temp;
                }
            }
            static class RefRef {
                public static string Run() {
                    string first = "a";
                    string second = "b";
                    Seq.Swap(ref first, ref second);
                    return first + second;
                }
            }
            """;
        EmittedProgram.assertResult("RefRef", source, "RefRef", "Run", "ba");
    }

    private void refValueType() {
        // `T = int` is the case a wrong cell type shows up in: the cell is `object[]`, so the
        // value is boxed on the way in and unboxed on the way back. A cell typed `[I` would
        // have matched the local and failed the verifier at the call.
        String source = """
            static class Seq {
                public static void Swap<T>(ref T left, ref T right) {
                    T temp = left;
                    left = right;
                    right = temp;
                }
            }
            static class RefValue {
                public static int Run() {
                    int one = 1;
                    int two = 2;
                    Seq.Swap(ref one, ref two);
                    return one * 10 + two;
                }
            }
            """;
        EmittedProgram.assertResult("RefValue", source, "RefValue", "Run", 21);
    }

    private void outParameter() {
        String source = """
            static class Seq {
                public static bool TryFirst<T>(T[] items, out T value) {
                    if (items.Length == 0) {
                        value = default;
                        return false;
                    }
                    value = items[0];
                    return true;
                }
            }
            static class OutParam {
                public static string Run() {
                    string[] names = { "x", "y" };
                    string found;
                    bool ok = Seq.TryFirst(names, out found);
                    return ok + ":" + found;
                }
            }
            """;
        EmittedProgram.assertResult("OutParam", source, "OutParam", "Run", "True:x");
    }

    private void outDeclaration() {
        String source = """
            static class Seq {
                public static bool TryFirst<T>(T[] items, out T value) {
                    if (items.Length == 0) {
                        value = default;
                        return false;
                    }
                    value = items[0];
                    return true;
                }
            }
            static class OutDecl {
                public static string Run() {
                    string[] empty = { };
                    bool none = Seq.TryFirst(empty, out string missing);
                    return none + ":" + (missing == null);
                }
            }
            """;
        EmittedProgram.assertResult("OutDecl", source, "OutDecl", "Run", "False:True");
    }

    private void refField() {
        String source = """
            static class Seq {
                public static void Bump<T>(ref T value, T replacement) { value = replacement; }
            }
            static class RefField {
                static string stored = "before";
                public static string Run() {
                    Seq.Bump(ref stored, "after");
                    return stored;
                }
            }
            """;
        EmittedProgram.assertResult("RefField", source, "RefField", "Run", "after");
    }

    private void forwardedReferenceCell() {
        // The caller's own cell is a `[Ljava/lang/String;`, which *is* an
        // `[Ljava/lang/Object;` by array covariance, so it is handed over unchanged and the
        // two names alias exactly as C# requires.
        String source = """
            static class Seq {
                public static void Bump<T>(ref T value, T replacement) { value = replacement; }
                public static void Outer(ref string text) { Seq.Bump(ref text, "forwarded"); }
            }
            static class Forwarded {
                public static string Run() {
                    string text = "before";
                    Seq.Outer(ref text);
                    return text;
                }
            }
            """;
        EmittedProgram.assertResult("Forwarded", source, "Forwarded", "Run", "forwarded");
    }

    private void copiedPrimitiveCell() {
        // An `[I` cell cannot be forwarded where `[Ljava/lang/Object;` is required, so the
        // value travels through a cell of the required type and is written back afterwards.
        String source = """
            static class Seq {
                public static void Bump<T>(ref T value, T replacement) { value = replacement; }
                public static void Outer(ref int number) { Seq.Bump(ref number, 99); }
            }
            static class Copied {
                public static int Run() {
                    int number = 1;
                    Seq.Outer(ref number);
                    return number;
                }
            }
            """;
        EmittedProgram.assertResult("Copied", source, "Copied", "Run", 99);
    }

    private void byValueArgumentIsAdapted() {
        // The `T replacement` beside the `ref T` is an ordinary erased parameter and has to be
        // boxed like any other. Pushing the raw `int` verified as "integer is not assignable to
        // java/lang/Object" - a class that cannot load, not a wrong value.
        String source = """
            static class Seq {
                public static void Bump<T>(ref T value, T replacement) { value = replacement; }
            }
            static class ByValue {
                public static int Run() {
                    int number = 1;
                    Seq.Bump(ref number, 42);
                    return number;
                }
            }
            """;
        EmittedProgram.assertResult("ByValue", source, "ByValue", "Run", 42);
    }

    private void emittedCellIsErased() {
        String source = """
            static class Seq {
                public static void Bump<T>(ref T value, T replacement) { value = replacement; }
            }
            static class Cell {
                public static int Run() {
                    int number = 1;
                    Seq.Bump(ref number, 42);
                    return number;
                }
            }
            """;
        EmittedProgram program = EmittedProgram.of("Cell", source);
        ClassModel holder = ClassFile.of().parse(program.bytes("Seq"));
        String descriptor = holder.methods().stream()
                .filter(method -> method.methodName().stringValue().equals("Bump"))
                .findFirst()
                .orElseThrow()
                .methodType()
                .stringValue();
        Assert.equal("([Ljava/lang/Object;Ljava/lang/Object;)V", descriptor,
                "a ref T is a cell over the erasure, and the by-value T is the erasure itself");
    }

    private void refNullableParameter() {
        // Two erased carriers at once: a `Nullable` inside the `ref` cell. The
        // `where T : struct` that makes `T?` meaningful is enforced, so the combination
        // is checked rather than assumed.
        String source = """
            static class Seq {
                public static void Clear<T>(ref T? slot) where T : struct { slot = null; }
            }
            static class RefNullable {
                public static bool Run() {
                    int? maybe = 3;
                    Seq.Clear(ref maybe);
                    return maybe.HasValue;
                }
            }
            """;
        EmittedProgram.assertResult("RefNullable", source, "RefNullable", "Run", false);
    }

}
