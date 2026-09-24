package vsharp.tests.semantics;

import vsharp.testkit.Assert;
import vsharp.testkit.TestRegistry;
import vsharp.testkit.TestSuite;
import vsharp.tests.EmittedProgram;

/// `where` constraints on type parameters.
///
/// The clauses were parsed and then discarded, so `Describe<T>(T) where T : struct` accepted a
/// `string` and ran - a program C# refuses with CS0453. V# now enforces the two constraints the
/// JVM can be held to and reports them under C#'s own numbers.
public final class GenericConstraintTests implements TestSuite {

    @Override
    public String suiteName() {
        return "semantic.generic-constraints";
    }

    @Override
    public void register(TestRegistry registry) {
        registry.test("a struct constraint admits a value type", this::structAdmitsValue);
        registry.test("a struct constraint refuses a reference type", this::structRefusesReference);
        registry.test("a struct constraint refuses a nullable value type",
                this::structRefusesNullable);
        registry.test("a class constraint admits a reference type", this::classAdmitsReference);
        registry.test("a class constraint refuses a value type", this::classRefusesValue);
        registry.test("a written type argument is checked against the constraint",
                this::writtenTypeArgumentChecked);
        registry.test("an unconstrained parameter still admits everything",
                this::unconstrainedAdmitsEverything);
        registry.test("unmanaged is read as a value-type constraint", this::unmanagedIsValue);
        registry.test("a constrained overload loses to an applicable one",
                this::constraintPicksTheOtherOverload);
        registry.test("a new() constraint is refused", this::constructorConstraintRefused);
        registry.test("a value type is not a valid constraint", this::valueTypeConstraintRefused);
        registry.test("an interface constraint is still accepted",
                this::interfaceConstraintAccepted);
        registry.test("a reference type that does not implement the interface is refused",
                this::interfaceConstraintRefusesReference);
        registry.test("a value type reaches the interface through its boxed carrier",
                this::interfaceConstraintAdmitsValueType);
        registry.test("a value type that implements nothing is refused",
                this::interfaceConstraintRefusesValueTypeThatDoesNot);
        registry.test("an unanswerable keyword type is admitted",
                this::interfaceConstraintAdmitsUnanswerableKeyword);
    }

    private void interfaceConstraintRefusesReference() {
        // The case the rule exists for, and the one the JVM hierarchy can answer: a
        // reference argument that does not implement the interface. C# reports CS0311 for
        // the equivalent program.
        String source = """
            using java.lang;
            using java.util;
            static class Seq {
                public static string Need<T>(T value) where T : Comparable { return "c:" + value; }
            }
            static class Refused {
                public static string Run() { return Seq.Need(new ArrayList<string>()); }
            }
            """;
        Assert.isTrue(EmittedProgram.refusalCodes(source).contains("VS0311"),
                "a reference type must convert to the constraint");
    }

    private void interfaceConstraintAdmitsValueType() {
        // `int` reaches `Comparable` through its boxed carrier `Integer`, so the argument is
        // now *checked* rather than admitted blindly, and it passes - exactly as C# admits
        // `IComparable` for `int`.
        String source = """
            using java.lang;
            static class Seq {
                public static string Need<T>(T value) where T : Comparable { return "c:" + value; }
            }
            static class Admitted {
                public static string Run() { return Seq.Need(42) + Seq.Need("text") + Seq.Need(true); }
            }
            """;
        EmittedProgram.assertResult("Admitted", source, "Admitted", "Run",
                "c:42c:textc:True");
    }

    private void interfaceConstraintRefusesValueTypeThatDoesNot() {
        // A V# declared value type implements nothing on the JVM, so "no" is a real
        // answer for it. This was admitted unchecked until the boxed carriers were
        // modelled.
        String source = """
            using java.lang;
            record struct Point(int X, int Y);
            static class Seq {
                public static string Need<T>(T value) where T : Comparable { return "c:" + value; }
            }
            static class RefusedStruct {
                public static string Run() { return Seq.Need(new Point(1, 2)); }
            }
            """;
        Assert.isTrue(EmittedProgram.refusalCodes(source).contains("VS0311"),
                "a value type that implements nothing cannot satisfy an interface constraint");
    }

    private void interfaceConstraintAdmitsUnanswerableKeyword() {
        // `uint` boxes through `VsUnsigned` into the carrier whose interfaces answer the
        // *signed* question, so it is deliberately not modelled and the argument is
        // admitted. Unanswerable is not "no": refusing would reject a program C# accepts.
        String source = """
            using java.lang;
            static class Seq {
                public static string Need<T>(T value) where T : Comparable { return "c:" + value; }
            }
            static class Unanswerable {
                public static string Run() {
                    uint unsigned = 5;
                    return Seq.Need(unsigned);
                }
            }
            """;
        EmittedProgram.assertResult("Unanswerable", source, "Unanswerable", "Run", "c:5");
    }

    private void constructorConstraintRefused() {
        // `new()` exists in C# to permit `new T()`, which V# refuses as the object model it
        // omits. The constraint would be a promise no operation here can consume, so it is
        // refused for the same reason and with the same diagnostic.
        String source = """
            static class Seq {
                public static string Describe<T>(T value) where T : new() { return "n:" + value; }
            }
            """;
        Assert.isTrue(EmittedProgram.refusalCodes(source).contains("VS20001"),
                "a new() constraint names an operation V# does not have");
    }

    private void valueTypeConstraintRefused() {
        // C# reports CS0701 for this: a sealed value type could only ever be satisfied by
        // itself. V# had accepted it silently.
        String source = """
            record struct Point(int X, int Y);
            static class Seq {
                public static string Named<T>(T value) where T : Point { return "p:" + value; }
            }
            """;
        Assert.isTrue(EmittedProgram.refusalCodes(source).contains("VS0701"),
                "a struct is not a valid constraint");
    }

    private void interfaceConstraintAccepted() {
        // A Java interface *is* a valid C# constraint, so it stays accepted. It is not yet
        // enforced, which is recorded as open work rather than turned into a refusal.
        String source = """
            using java.lang;
            static class Seq {
                public static string Java<T>(T value) where T : Comparable { return "c:" + value; }
            }
            static class Accepted {
                public static string Run() { return Seq.Java(42); }
            }
            """;
        EmittedProgram.assertResult("Accepted", source, "Accepted", "Run", "c:42");
    }

    private void structAdmitsValue() {
        String source = """
            static class Seq {
                public static string Describe<T>(T value) where T : struct { return "v:" + value; }
            }
            static class StructOk {
                public static string Run() { return Seq.Describe(1) + Seq.Describe(true); }
            }
            """;
        EmittedProgram.assertResult("StructOk", source, "StructOk", "Run", "v:1v:True");
    }

    private void structRefusesReference() {
        String source = """
            static class Seq {
                public static string Describe<T>(T value) where T : struct { return "v:" + value; }
            }
            static class StructBad {
                public static string Run() { return Seq.Describe("text"); }
            }
            """;
        Assert.isTrue(EmittedProgram.refusalCodes(source).contains("VS0453"),
                "a reference type cannot satisfy 'struct'");
    }

    private void structRefusesNullable() {
        // `where T : struct` means *non-nullable* value type, which is exactly what makes `T?`
        // meaningful inside such a declaration. C# reports the same CS0453 here.
        String source = """
            static class Seq {
                public static string Describe<T>(T value) where T : struct { return "v:" + value; }
            }
            static class NullableBad {
                public static string Run() {
                    int? maybe = 3;
                    return Seq.Describe(maybe);
                }
            }
            """;
        Assert.isTrue(EmittedProgram.refusalCodes(source).contains("VS0453"),
                "a nullable value type cannot satisfy 'struct'");
    }

    private void classAdmitsReference() {
        String source = """
            static class Seq {
                public static string Named<T>(T value) where T : class { return "r:" + value; }
            }
            static class ClassOk {
                public static string Run() { return Seq.Named("text"); }
            }
            """;
        EmittedProgram.assertResult("ClassOk", source, "ClassOk", "Run", "r:text");
    }

    private void classRefusesValue() {
        String source = """
            static class Seq {
                public static string Named<T>(T value) where T : class { return "r:" + value; }
            }
            static class ClassBad {
                public static string Run() { return Seq.Named(2); }
            }
            """;
        Assert.isTrue(EmittedProgram.refusalCodes(source).contains("VS0452"),
                "a value type cannot satisfy 'class'");
    }

    private void writtenTypeArgumentChecked() {
        // Inference is not the only way in: a written type argument replaces it entirely, so
        // the constraint has to be checked on that path too.
        String source = """
            static class Seq {
                public static string Describe<T>(T value) where T : struct { return "v:" + value; }
            }
            static class WrittenBad {
                public static string Run() { return Seq.Describe<string>("x"); }
            }
            """;
        Assert.isTrue(EmittedProgram.refusalCodes(source).contains("VS0453"),
                "a written type argument is checked against the constraint");
    }

    private void unconstrainedAdmitsEverything() {
        String source = """
            static class Seq {
                public static string Free<T>(T value) { return "a:" + value; }
            }
            static class Unconstrained {
                public static string Run() { return Seq.Free(1) + Seq.Free("s"); }
            }
            """;
        EmittedProgram.assertResult("Unconstrained", source, "Unconstrained", "Run", "a:1a:s");
    }

    private void unmanagedIsValue() {
        // `unmanaged` is strictly narrower than `struct`, and narrowing it further needs a
        // notion of managed-ness V# does not have; reading it as `struct` refuses what C#
        // refuses and admits a little more, which is documented rather than silent.
        String source = """
            static class Seq {
                public static string Raw<T>(T value) where T : unmanaged { return "u:" + value; }
            }
            static class Unmanaged {
                public static string Run() { return Seq.Raw(5); }
            }
            """;
        EmittedProgram.assertResult("Unmanaged", source, "Unmanaged", "Run", "u:5");

        String refused = """
            static class Seq {
                public static string Raw<T>(T value) where T : unmanaged { return "u:" + value; }
            }
            static class UnmanagedBad {
                public static string Run() { return Seq.Raw("s"); }
            }
            """;
        Assert.isTrue(EmittedProgram.refusalCodes(refused).contains("VS0453"),
                "a reference type cannot satisfy 'unmanaged'");
    }

    private void constraintPicksTheOtherOverload() {
        // A constraint makes a candidate inapplicable rather than the call an error, so an
        // overload that does apply still wins - which is why the violation is reported only
        // when nothing else answered.
        String source = """
            static class Seq {
                public static string Pick<T>(T value) where T : struct { return "v:" + value; }
                public static string Pick(string value) { return "s:" + value; }
            }
            static class Overloaded {
                public static string Run() { return Seq.Pick(1) + Seq.Pick("x"); }
            }
            """;
        EmittedProgram.assertResult("Overloaded", source, "Overloaded", "Run", "v:1s:x");
    }
}
