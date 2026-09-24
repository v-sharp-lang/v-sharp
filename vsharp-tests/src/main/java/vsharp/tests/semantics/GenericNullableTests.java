package vsharp.tests.semantics;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import vsharp.testkit.Assert;
import vsharp.testkit.TestRegistry;
import vsharp.testkit.TestSuite;
import vsharp.tests.EmittedProgram;

/// `T?` as a generic carrier.
///
/// A nullable is already opaque in every descriptor - `int?` reaches a parameter or result
/// position as `Ljava/lang/Object;`, a boxed value or `null` - so `T?` and `int?` are one JVM
/// signature and `HasValue`/`Value` read the carrier they always read. What makes this
/// admissible rather than a guess is that `where T : struct` is enforced: without it,
/// `OrNull<string>` would reach the same code by the same rule.
public final class GenericNullableTests implements TestSuite {

    @Override
    public String suiteName() {
        return "semantic.generic-nullables";
    }

    @Override
    public void register(TestRegistry registry) {
        registry.test("a nullable result carries a value", this::nullableResultWithValue);
        registry.test("a nullable result carries absence", this::nullableResultEmpty);
        registry.test("a nullable parameter is read back", this::nullableParameter);
        registry.test("a bool element round-trips through the carrier", this::boolElement);
        registry.test("the constraint still guards the carrier", this::constraintGuardsCarrier);
        registry.test("the emitted nullable descriptor names no element",
                this::descriptorIsOpaque);
    }

    private void nullableResultWithValue() {
        String source = """
            static class Seq {
                public static T? OrNull<T>(T value, bool keep) where T : struct {
                    if (keep) { return value; }
                    return null;
                }
            }
            static class WithValue {
                public static string Run() {
                    int? kept = Seq.OrNull(7, true);
                    return kept.HasValue + ":" + kept.Value;
                }
            }
            """;
        EmittedProgram.assertResult("WithValue", source, "WithValue", "Run", "True:7");
    }

    private void nullableResultEmpty() {
        String source = """
            static class Seq {
                public static T? OrNull<T>(T value, bool keep) where T : struct {
                    if (keep) { return value; }
                    return null;
                }
            }
            static class Empty {
                public static bool Run() { return Seq.OrNull(7, false).HasValue; }
            }
            """;
        EmittedProgram.assertResult("Empty", source, "Empty", "Run", false);
    }

    private void nullableParameter() {
        String source = """
            static class Seq {
                public static T OrDefault<T>(T? maybe, T fallback) where T : struct {
                    if (maybe.HasValue) { return maybe.Value; }
                    return fallback;
                }
            }
            static class Parameter {
                public static int Run() {
                    int? present = 7;
                    int? absent = null;
                    return Seq.OrDefault(present, -1) * 100 + Seq.OrDefault(absent, -1);
                }
            }
            """;
        EmittedProgram.assertResult("Parameter", source, "Parameter", "Run", 699);
    }

    private void boolElement() {
        String source = """
            static class Seq {
                public static T? OrNull<T>(T value, bool keep) where T : struct {
                    if (keep) { return value; }
                    return null;
                }
                public static T OrDefault<T>(T? maybe, T fallback) where T : struct {
                    if (maybe.HasValue) { return maybe.Value; }
                    return fallback;
                }
            }
            static class BoolElement {
                public static bool Run() {
                    bool? flag = Seq.OrNull(true, true);
                    return Seq.OrDefault(flag, false);
                }
            }
            """;
        EmittedProgram.assertResult("BoolElement", source, "BoolElement", "Run", true);
    }

    private void constraintGuardsCarrier() {
        // `T?` over a reference type is not a C# program, and the carrier does not make it one.
        String source = """
            static class Seq {
                public static T? OrNull<T>(T value, bool keep) where T : struct {
                    if (keep) { return value; }
                    return null;
                }
            }
            static class Guarded {
                public static bool Run() { return Seq.OrNull("x", true).HasValue; }
            }
            """;
        Assert.isTrue(EmittedProgram.refusalCodes(source).contains("VS0453"),
                "the struct constraint still refuses a reference type argument");
    }

    private void descriptorIsOpaque() {
        String source = """
            static class Seq {
                public static T? OrNull<T>(T value, bool keep) where T : struct {
                    if (keep) { return value; }
                    return null;
                }
            }
            static class Opaque {
                public static bool Run() { return Seq.OrNull(1, true).HasValue; }
            }
            """;
        EmittedProgram program = EmittedProgram.of("Opaque", source);
        ClassModel holder = ClassFile.of().parse(program.bytes("Seq"));
        String descriptor = holder.methods().stream()
                .filter(method -> method.methodName().stringValue().equals("OrNull"))
                .findFirst()
                .orElseThrow()
                .methodType()
                .stringValue();
        Assert.equal("(Ljava/lang/Object;Z)Ljava/lang/Object;", descriptor,
                "a nullable result carries no element type into the descriptor");
    }
}
