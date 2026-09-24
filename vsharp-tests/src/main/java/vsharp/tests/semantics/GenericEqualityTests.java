package vsharp.tests.semantics;

import vsharp.testkit.Assert;
import vsharp.testkit.TestRegistry;
import vsharp.testkit.TestSuite;
import vsharp.tests.EmittedProgram;

/// `==` and `!=` over a type parameter.
///
/// Erasure leaves one implementation to choose and the two candidates disagree: identity for a
/// reference type, value comparison for a value type. C# refuses the operator unless the
/// parameter is known to be a reference type, and so does V# - after a release in which it did
/// not, and silently emitted the identity comparison.
///
/// The shape in `unconstrainedEqualityIsRefused` is the one that shipped a regression into a
/// real server: a generic array search compared method names by reference, so a name parsed off
/// the wire never matched the configured one and every request was answered 405.
public final class GenericEqualityTests implements TestSuite {

    @Override
    public String suiteName() {
        return "semantic.generic-equality";
    }

    @Override
    public void register(TestRegistry registry) {
        registry.test("== between unconstrained type parameters is refused",
                this::unconstrainedEqualityIsRefused);
        registry.test("!= between unconstrained type parameters is refused",
                this::unconstrainedInequalityIsRefused);
        registry.test("== between value-constrained type parameters is refused",
                this::structConstrainedEqualityIsRefused);
        registry.test("== between reference-constrained type parameters is allowed",
                this::classConstrainedEqualityIsAllowed);
        registry.test("a comparison against null stays allowed", this::nullComparisonIsAllowed);
        registry.test("value equality through Objects.Equals is the alternative",
                this::objectsEqualsIsTheAlternative);
    }

    private void unconstrainedEqualityIsRefused() {
        String source = """
            static class Seq {
                public static bool Contains<T>(T[] values, T candidate) {
                    for (int i = 0; i < values.Length; i++) {
                        if (values[i] == candidate) { return true; }
                    }
                    return false;
                }
            }
            """;
        Assert.isTrue(EmittedProgram.refusalCodes(source).contains("VS0019"),
                "an unconstrained type parameter has no ==");
    }

    private void unconstrainedInequalityIsRefused() {
        String source = """
            static class Seq {
                public static bool Differs<T>(T left, T right) { return left != right; }
            }
            """;
        Assert.isTrue(EmittedProgram.refusalCodes(source).contains("VS0019"),
                "an unconstrained type parameter has no !=");
    }

    private void structConstrainedEqualityIsRefused() {
        // A value-type parameter is refused for the same reason C# refuses it: the operator
        // each instantiation would need is not the one erasure can emit.
        String source = """
            static class Seq {
                public static bool Same<T>(T left, T right) where T : struct {
                    return left == right;
                }
            }
            """;
        Assert.isTrue(EmittedProgram.refusalCodes(source).contains("VS0019"),
                "a struct-constrained type parameter has no ==");
    }

    private void classConstrainedEqualityIsAllowed() {
        // Known to be a reference type, so identity is the right and only meaning - and it is
        // genuinely identity, which is what C# guarantees here too.
        String source = """
            using java.lang;
            static class Seq {
                public static bool Same<T>(T left, T right) where T : class {
                    return left == right;
                }
            }
            static class RefEquality {
                public static string Run() {
                    string literal = "GET";
                    string copy = new StringBuilder().Append("GE").Append("T").ToString();
                    return Seq.Same(literal, literal) + "|" + Seq.Same(literal, copy);
                }
            }
            """;
        EmittedProgram.assertResult("RefEquality", source, "RefEquality", "Run", "True|False");
    }

    private void nullComparisonIsAllowed() {
        // `value == null` asks about absence, which erasure answers the same way for every
        // instantiation, so C# allows it on an unconstrained parameter and so does V#.
        String source = """
            static class Seq {
                public static bool Missing<T>(T value) { return value == null; }
            }
            static class NullCheck {
                public static string Run() {
                    string present = "x";
                    string absent = null;
                    return Seq.Missing(present) + "|" + Seq.Missing(absent);
                }
            }
            """;
        EmittedProgram.assertResult("NullCheck", source, "NullCheck", "Run", "False|True");
    }

    private void objectsEqualsIsTheAlternative() {
        // What the refusal points an author towards: value equality that does not depend on
        // what the type parameter turns out to be.
        String source = """
            using java.lang;
            using java.util;
            static class Seq {
                public static bool Same<T>(T left, T right) { return Objects.Equals(left, right); }
            }
            static class ValueEquality {
                public static string Run() {
                    string copy = new StringBuilder().Append("GE").Append("T").ToString();
                    return Seq.Same("GET", copy) + "|" + Seq.Same("GET", "HEAD");
                }
            }
            """;
        EmittedProgram.assertResult("ValueEquality", source, "ValueEquality", "Run", "True|False");
    }
}
