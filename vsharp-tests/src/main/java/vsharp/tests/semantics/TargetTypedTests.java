package vsharp.tests.semantics;

import vsharp.testkit.Assert;
import vsharp.testkit.TestRegistry;
import vsharp.testkit.TestSuite;
import vsharp.tests.EmittedProgram;

/// Target-typed conditional and switch expressions.
///
/// When the arms share no common type of their own, the type the result is being converted to
/// supplies one, provided every arm converts to it. That is C# 9's rule, and without it
/// `keep ? value : null` - the shape a nullable is most naturally produced by - was refused
/// outright while C# compiled it.
public final class TargetTypedTests implements TestSuite {

    @Override
    public String suiteName() {
        return "semantic.target-typed";
    }

    @Override
    public void register(TestRegistry registry) {
        registry.test("a return type types a conditional with a null arm",
                this::conditionalFromReturnType);
        registry.test("a local's declared type types a conditional", this::conditionalFromLocal);
        registry.test("a return type types a switch expression with a null arm",
                this::switchFromReturnType);
        registry.test("a reference target keeps working", this::referenceTarget);
        registry.test("a common type still wins where one exists", this::commonTypeStillWins);
        registry.test("arms that convert to nothing are still refused", this::noCommonTypeRefused);
    }

    private void conditionalFromReturnType() {
        String source = """
            static class Seq {
                public static int? OrNull(int value, bool keep) {
                    return keep ? value : null;
                }
            }
            static class FromReturn {
                public static string Run() {
                    return Seq.OrNull(3, true).Value + "|" + Seq.OrNull(3, false).HasValue;
                }
            }
            """;
        EmittedProgram.assertResult("FromReturn", source, "FromReturn", "Run", "3|False");
    }

    private void conditionalFromLocal() {
        // A declaration initializer is a target-typed context too, so the same conditional
        // means the same thing there as in a `return`.
        String source = """
            static class FromLocal {
                public static int Run() {
                    int? kept = true ? 1 : null;
                    int? absent = false ? 1 : null;
                    return kept.Value * 10 + (absent.HasValue ? 1 : 0);
                }
            }
            """;
        EmittedProgram.assertResult("FromLocal", source, "FromLocal", "Run", 10);
    }

    private void switchFromReturnType() {
        String source = """
            static class Seq {
                public static int? Classify(int value) {
                    return value switch {
                        0 => null,
                        _ => value
                    };
                }
            }
            static class FromSwitch {
                public static string Run() {
                    return Seq.Classify(0).HasValue + "|" + Seq.Classify(7).Value;
                }
            }
            """;
        EmittedProgram.assertResult("FromSwitch", source, "FromSwitch", "Run", "False|7");
    }

    private void referenceTarget() {
        // This shape already worked, because `null` converts to a reference type under the
        // ordinary common-type rule. It is pinned so the new path cannot quietly take it over
        // and change what it means.
        String source = """
            static class Seq {
                public static string OrNull(string value, bool keep) {
                    return keep ? value : null;
                }
            }
            static class Reference {
                public static string Run() {
                    return Seq.OrNull("x", true) + "|" + (Seq.OrNull("x", false) == null);
                }
            }
            """;
        EmittedProgram.assertResult("Reference", source, "Reference", "Run", "x|True");
    }

    private void commonTypeStillWins() {
        // The target is consulted only when the arms have no common type. Here they do - `int`
        // widens to `long` - and that rule keeps deciding, which is what stops a target from
        // silently changing an expression that was already well typed.
        String source = """
            static class Seq {
                public static long Widen(int value, bool keep) {
                    return keep ? value : 0;
                }
            }
            static class CommonType {
                public static long Run() { return Seq.Widen(5, true); }
            }
            """;
        EmittedProgram.assertResult("CommonType", source, "CommonType", "Run", 5L);
    }

    private void noCommonTypeRefused() {
        // A target that cannot take both arms is still an error: target typing supplies a type,
        // it does not excuse one.
        String source = """
            static class Seq {
                public static int Bad(bool keep) { return keep ? 1 : "text"; }
            }
            """;
        Assert.isTrue(EmittedProgram.refusalCodes(source).contains("VS0173"),
                "arms that do not convert to the target are refused");
    }
}
