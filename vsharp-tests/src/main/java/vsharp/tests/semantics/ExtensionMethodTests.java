package vsharp.tests.semantics;

import vsharp.testkit.Assert;
import vsharp.testkit.TestRegistry;
import vsharp.testkit.TestSuite;
import vsharp.tests.EmittedProgram;

/// Extension methods: declaration shape, the second member lookup C# §12.8.10.3
/// performs, and the static call every one of them lowers to.
///
/// The emitted form is the point of the feature here: `text.WordCount()` and
/// `Ext.WordCount(text)` are the same `invokestatic`, so a V# extension method is an ordinary
/// static method to the JVM and to any Java caller, and no runtime support is involved.
public final class ExtensionMethodTests implements TestSuite {

    @Override
    public String suiteName() {
        return "semantic.extensions";
    }

    @Override
    public void register(TestRegistry registry) {
        registry.test("a receiver-form call reaches the extension method",
                this::receiverFormCall);
        registry.test("an extension method takes further arguments",
                this::extensionWithArguments);
        registry.test("a value type receiver is passed by value",
                this::valueTypeReceiver);
        registry.test("a receiver reaches an extension of object by boxing",
                this::boxingReceiver);
        registry.test("a generic extension infers its type argument from the receiver",
                this::genericReceiverInference);
        registry.test("the same method is still callable in static form",
                this::staticFormStillWorks);
        registry.test("an imported namespace supplies extension methods",
                this::namespaceImportSuppliesExtensions);
        registry.test("a using static directive supplies extension methods",
                this::usingStaticSuppliesExtensions);
        registry.test("an instance member wins over an extension of the same name",
                this::instanceMemberWins);
        registry.test("an extension answers when no instance overload applies",
                this::extensionAnswersWhenNoOverloadApplies);
        registry.test("a receiver-form call emits the holder's static method",
                this::receiverFormEmitsStaticCall);
        registry.test("a null receiver still reaches the extension method",
                this::nullReceiverReachesExtension);
        registry.test("a Java type can be extended", this::javaReceiverExtension);
        registry.test("a Java member found by its V# spelling wins over an extension",
                this::javaMemberWinsOverExtension);

        registry.test("this on a later parameter is refused", this::thisNotFirstParameter);
        registry.test("a non-static extension declaration is refused", this::extensionMustBeStatic);
        registry.test("an extension outside a static class is refused",
                this::extensionMustBeInStaticClass);
        registry.test("an extension in a generic static class is refused",
                this::extensionMustBeInNonGenericClass);
        registry.test("an extension in a nested static class is refused",
                this::extensionMustBeTopLevel);
        registry.test("ref, out and params cannot be combined with this",
                this::passingModifiersWithThis);
        registry.test("an extension that is not in scope is not found",
                this::extensionOutOfScopeIsNotFound);
        registry.test("a receiver needing a numeric conversion is not an extension receiver",
                this::numericConversionIsNotAReceiver);
    }

    // ---- positive ------------------------------------------------------------------

    private void receiverFormCall() {
        String source = """
            static class Ext {
                public static int Twice(this int value) { return value * 2; }
            }
            static class Receiver {
                public static int Run(int seed) { return seed.Twice(); }
            }
            """;
        EmittedProgram.assertResult("Receiver", source, "Receiver", "Run", 42, 21);
    }

    private void extensionWithArguments() {
        String source = """
            static class Ext {
                public static string Repeat(this string text, int times) {
                    string result = "";
                    for (int i = 0; i < times; i++) { result = result + text; }
                    return result;
                }
            }
            static class Repeats {
                public static string Run() { return "ab".Repeat(3); }
            }
            """;
        EmittedProgram.assertResult("Repeats", source, "Repeats", "Run", "ababab");
    }

    private void valueTypeReceiver() {
        // C# passes the receiver of a value-type extension by value, so mutating the
        // parameter cannot be observed by the caller. The receiver is an ordinary argument
        // here, which is exactly why that holds without a rule of its own.
        String source = """
            static class Ext {
                public static int Consume(this int value) { value = value + 1; return value; }
            }
            static class ByValue {
                public static int Run() {
                    int seed = 1;
                    int produced = seed.Consume();
                    return produced * 10 + seed;
                }
            }
            """;
        EmittedProgram.assertResult("ByValue", source, "ByValue", "Run", 21);
    }

    private void boxingReceiver() {
        String source = """
            static class Ext {
                public static string Describe(this object value) { return "obj:" + value; }
            }
            static class Boxed {
                public static string Run() { int value = 7; return value.Describe(); }
            }
            """;
        EmittedProgram.assertResult("Boxed", source, "Boxed", "Run", "obj:7");
    }

    private void genericReceiverInference() {
        String source = """
            static class Ext {
                public static string Label<T>(this T value) { return "<" + value + ">"; }
            }
            static class Generic {
                public static string Run() { int value = 3; return value.Label(); }
            }
            """;
        EmittedProgram.assertResult("Generic", source, "Generic", "Run", "<3>");
    }

    private void staticFormStillWorks() {
        String source = """
            static class Ext {
                public static int Twice(this int value) { return value * 2; }
            }
            static class StaticForm {
                public static int Run() { return Ext.Twice(4); }
            }
            """;
        EmittedProgram.assertResult("StaticForm", source, "StaticForm", "Run", 8);
    }

    private void namespaceImportSuppliesExtensions() {
        String source = """
            using Helpers;
            namespace Helpers {
                static class Ext {
                    public static int Twice(this int value) { return value * 2; }
                }
            }
            static class Imported {
                public static int Run() { int value = 5; return value.Twice(); }
            }
            """;
        EmittedProgram.assertResult("Imported", source, "Imported", "Run", 10);
    }

    private void usingStaticSuppliesExtensions() {
        String source = """
            using static Helpers.Ext;
            namespace Helpers {
                static class Ext {
                    public static int Twice(this int value) { return value * 2; }
                }
            }
            static class StaticImported {
                public static int Run() { int value = 6; return value.Twice(); }
            }
            """;
        EmittedProgram.assertResult("StaticImported", source, "StaticImported", "Run", 12);
    }

    private void instanceMemberWins() {
        // C# only searches extension methods when the receiver has no applicable member of
        // that name, so an extension can never take a call away from the receiver's own
        // member. `string.Length` is a member, and the extension named `Length` is dead.
        String source = """
            static class Ext {
                public static int Length(this string text) { return -1; }
            }
            static class Priority {
                public static int Run() { return "abcd".Length; }
            }
            """;
        EmittedProgram.assertResult("Priority", source, "Priority", "Run", 4);
    }

    private void extensionAnswersWhenNoOverloadApplies() {
        // `Substring` exists on the receiver, but not with an empty argument list, so the
        // second search still runs - C# reaches extension methods when no instance member
        // *applied*, not only when none was named.
        String source = """
            static class Ext {
                public static string Substring(this string text) { return text.Substring(1); }
            }
            static class NoOverload {
                public static string Run() { return "abc".Substring(); }
            }
            """;
        EmittedProgram.assertResult("NoOverload", source, "NoOverload", "Run", "bc");
    }

    private void nullReceiverReachesExtension() {
        // An extension call is a static call, so a null receiver is a null argument and not
        // a dereference. C# behaves the same way, and a program relying on it must keep
        // working here.
        String source = """
            static class Ext {
                public static bool IsMissing(this string text) { return text == null; }
            }
            static class NullReceiver {
                public static bool Run() { string text = null; return text.IsMissing(); }
            }
            """;
        EmittedProgram.assertResult("NullReceiver", source, "NullReceiver", "Run", true);
    }

    private void javaReceiverExtension() {
        // The receiver is a constructed Java type read from the module path. Nothing about the
        // feature is V#-only: a JDK class is extended exactly as a V# one is.
        String source = """
            using java.util;
            static class Ext {
                public static string JoinWith(this ArrayList<string> items, string separator) {
                    string result = "";
                    for (int i = 0; i < items.Size(); i++) {
                        if (i > 0) { result = result + separator; }
                        result = result + items.Get(i);
                    }
                    return result;
                }
            }
            static class JavaReceiver {
                public static string Run() {
                    ArrayList<string> items = new ArrayList<string>();
                    items.Add("a");
                    items.Add("b");
                    return items.JoinWith("-");
                }
            }
            """;
        EmittedProgram.assertResult("JavaReceiver", source, "JavaReceiver", "Run", "a-b");
    }

    private void javaMemberWinsOverExtension() {
        // `Size()` is `java.util.ArrayList.size()` under the rule's PascalCase mapping, so it is a
        // member of the receiver and the extension of the same name never applies. The two
        // rules have to compose in this order or an extension could silently take over a JDK
        // call; the extension stays reachable in static form, which is what C# guarantees.
        String source = """
            using java.util;
            static class Ext {
                public static int Size(this ArrayList<string> items) { return -1; }
            }
            static class Shadowing {
                public static int Run() {
                    ArrayList<string> items = new ArrayList<string>();
                    items.Add("a");
                    return items.Size() * 100 + Ext.Size(items);
                }
            }
            """;
        EmittedProgram.assertResult("Shadowing", source, "Shadowing", "Run", 99);
    }

    private void receiverFormEmitsStaticCall() {
        String source = """
            static class Ext {
                public static int Twice(this int value) { return value * 2; }
            }
            static class Emitted {
                public static int Run() { int value = 2; return value.Twice(); }
            }
            """;
        EmittedProgram program = EmittedProgram.of("Emitted", source);
        byte[] holder = program.bytes("Ext");
        Assert.isTrue(holder != null, "the holder class is emitted");
        java.lang.classfile.ClassModel model = java.lang.classfile.ClassFile.of().parse(holder);
        java.lang.classfile.MethodModel method = model.methods().stream()
                .filter(candidate -> candidate.methodName().stringValue().equals("Twice"))
                .findFirst()
                .orElseThrow();
        Assert.isTrue(method.flags().flags().contains(
                        java.lang.reflect.AccessFlag.STATIC),
                "an extension method is emitted static");
        Assert.equal("(I)I", method.methodType().stringValue(),
                "the receiver is the first parameter of the emitted descriptor");

        java.lang.classfile.ClassModel caller =
                java.lang.classfile.ClassFile.of().parse(program.bytes("Emitted"));
        String text = caller.methods().stream()
                .filter(candidate -> candidate.methodName().stringValue().equals("Run"))
                .findFirst()
                .orElseThrow()
                .code()
                .orElseThrow()
                .elementStream()
                .filter(java.lang.classfile.instruction.InvokeInstruction.class::isInstance)
                .map(java.lang.classfile.instruction.InvokeInstruction.class::cast)
                .map(invoke -> invoke.opcode() + " " + invoke.owner().asInternalName()
                        + "." + invoke.name().stringValue())
                .findFirst()
                .orElse("<none>");
        Assert.equal("INVOKESTATIC Ext.Twice", text,
                "a receiver-form call is the holder's static method");
    }

    // ---- negative ------------------------------------------------------------------

    private void thisNotFirstParameter() {
        String source = """
            static class Ext {
                public static int Add(int first, this int second) { return first + second; }
            }
            """;
        Assert.isTrue(EmittedProgram.refusalCodes(source).contains("VS1100"),
                "this on a later parameter reports VS1100");
    }

    private void extensionMustBeStatic() {
        String source = """
            static class Ext {
                public int Twice(this int value) { return value * 2; }
            }
            """;
        Assert.isTrue(EmittedProgram.refusalCodes(source).contains("VS1105"),
                "a non-static extension reports VS1105");
    }

    private void extensionMustBeInStaticClass() {
        String source = """
            public struct Holder {
                public static int Twice(this int value) { return value * 2; }
            }
            """;
        Assert.isTrue(EmittedProgram.refusalCodes(source).contains("VS1106"),
                "an extension declared in a struct reports VS1106");
    }

    private void extensionMustBeInNonGenericClass() {
        String source = """
            static class Ext<T> {
                public static int Twice(this int value) { return value * 2; }
            }
            """;
        Assert.isTrue(EmittedProgram.refusalCodes(source).contains("VS1106"),
                "an extension in a generic static class reports VS1106");
    }

    private void extensionMustBeTopLevel() {
        String source = """
            static class Outer {
                static class Inner {
                    public static int Twice(this int value) { return value * 2; }
                }
            }
            """;
        Assert.isTrue(EmittedProgram.refusalCodes(source).contains("VS1109"),
                "an extension in a nested static class reports VS1109");
    }

    private void passingModifiersWithThis() {
        String refSource = """
            static class Ext {
                public static int Twice(this ref int value) { return value * 2; }
            }
            """;
        Assert.isTrue(EmittedProgram.refusalCodes(refSource).contains("VS1101"), "ref with this reports VS1101");

        String outSource = """
            static class Ext {
                public static int Twice(this out int value) { value = 1; return 2; }
            }
            """;
        Assert.isTrue(EmittedProgram.refusalCodes(outSource).contains("VS1102"), "out with this reports VS1102");

        String paramsSource = """
            static class Ext {
                public static int Count(this params int[] values) { return values.Length; }
            }
            """;
        Assert.isTrue(EmittedProgram.refusalCodes(paramsSource).contains("VS1104"),
                "params with this reports VS1104");
    }

    private void extensionOutOfScopeIsNotFound() {
        // The holder is in a namespace this file never imports, so the name is simply not a
        // member of the receiver and the ordinary member diagnostic stands.
        String source = """
            namespace Helpers {
                static class Ext {
                    public static int Twice(this int value) { return value * 2; }
                }
            }
            static class Unimported {
                public static int Run() { int value = 5; return value.Twice(); }
            }
            """;
        Assert.isTrue(EmittedProgram.refusalCodes(source).contains("VS0117"),
                "an unimported extension is not a member of the receiver");
    }

    private void numericConversionIsNotAReceiver() {
        // C# admits only an identity, reference or boxing conversion from the receiver to the
        // `this` parameter, so a widening `int` to `long` does not make the method reachable.
        String source = """
            static class Ext {
                public static long Twice(this long value) { return value * 2; }
            }
            static class Widening {
                public static long Run() { int value = 5; return value.Twice(); }
            }
            """;
        Assert.isTrue(EmittedProgram.refusalCodes(source).contains("VS0117"),
                "a widening receiver does not select an extension method");
    }

}
