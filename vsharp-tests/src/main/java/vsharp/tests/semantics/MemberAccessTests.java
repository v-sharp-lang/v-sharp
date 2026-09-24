package vsharp.tests.semantics;

import java.util.List;
import vsharp.compiler.api.Compilation;
import vsharp.compiler.api.CompilationResult;
import vsharp.compiler.diagnostics.Diagnostic;
import vsharp.compiler.diagnostics.DiagnosticBag;
import vsharp.compiler.diagnostics.DiagnosticCode;
import vsharp.compiler.semantics.binding.BoundExpression;
import vsharp.compiler.semantics.binding.DeclarationBinder;
import vsharp.compiler.semantics.binding.ExpressionBinder;
import vsharp.compiler.semantics.binding.ExpressionBinding;
import vsharp.compiler.semantics.binding.SemanticModel;
import vsharp.compiler.source.SourceFile;
import vsharp.compiler.syntax.AuxiliarySyntax;
import vsharp.compiler.syntax.Parser;
import vsharp.tests.TestSources;
import vsharp.testkit.Assert;
import vsharp.testkit.TestRegistry;
import vsharp.testkit.TestSuite;

public final class MemberAccessTests implements TestSuite {

    @Override
    public String suiteName() {
        return "semantic.memberaccess";
    }

    @Override
    public void register(TestRegistry registry) {
        registry.test("array length property member access", this::arrayLengthAccess);
        registry.test("array element access indexing", this::arrayElementAccess);
        registry.test("string length and element access", this::stringAccess);
        registry.test("null-conditional access has an evaluate-once bound node",
                this::nullConditionalAccess);
        registry.test("conditional access receiver boundaries are diagnosed",
                this::nullConditionalReceiverDiagnostics);
        registry.test("indexing non-array expression error", this::indexingNonArrayError);
        registry.test("wrong index count for array error", this::wrongIndexCountError);
        registry.test("member not found diagnostic error", this::memberNotFoundError);
        registry.test("instance field access", this::instanceFieldAccess);
        registry.test("keyword type limits bind as constants", this::builtinLimitsBind);
        registry.test("static corelib methods bind through a keyword type",
                this::builtinStaticMethodsBind);
        registry.test("empty String params calls preserve C# 13 ambiguity",
                this::emptyStringParamsCallsAreAmbiguous);
        registry.test("uncurated keyword type members are rejected",
                this::uncuratedBuiltinMembersRejected);
        registry.test("universal members are absent from ambiguous carriers",
                this::universalMembersAbsentFromAmbiguousCarriers);
        registry.test("instance field initializers are refused",
                this::instanceFieldInitializerRefused);
        registry.test("unlowerable constructs are diagnosed, not ICEd",
                this::unlowerableConstructsDiagnosed);
    }

    private void arrayLengthAccess() {
        String source = """
            static class P {
                static void M() {
                    int[] arr = new int[5];
                    int len = arr.Length;
                }
            }
            """;
        SourceFile file = TestSources.styled("test.vs", source);
        DiagnosticBag diagnostics = new DiagnosticBag();
        AuxiliarySyntax.CompilationUnit unit = Parser.parse(file, diagnostics);
        Assert.isFalse(diagnostics.hasErrors(), "syntax should parse without errors");

        SemanticModel model = DeclarationBinder.bind(java.util.List.of(file), java.util.List.of(unit), diagnostics);
        ExpressionBinding binding = ExpressionBinder.bind(file, unit, model, diagnostics);
        Assert.isFalse(diagnostics.hasErrors(), "array.Length binding should succeed");
    }

    private void arrayElementAccess() {
        String source = """
            static class P {
                static void M() {
                    int[] arr = new int[5];
                    int val = arr[0];
                }
            }
            """;
        SourceFile file = TestSources.styled("test.vs", source);
        DiagnosticBag diagnostics = new DiagnosticBag();
        AuxiliarySyntax.CompilationUnit unit = Parser.parse(file, diagnostics);
        SemanticModel model = DeclarationBinder.bind(java.util.List.of(file), java.util.List.of(unit), diagnostics);
        ExpressionBinding binding = ExpressionBinder.bind(file, unit, model, diagnostics);
        Assert.isFalse(diagnostics.hasErrors(), "arr[0] element access binding should succeed");

        boolean hasElementAccess = binding.expressions().stream()
                .anyMatch(BoundExpression.ElementAccess.class::isInstance);
        Assert.isTrue(hasElementAccess, "BoundExpression.ElementAccess node should be emitted");
    }

    private void stringAccess() {
        String source = """
            static class P {
                static char M(string value) {
                    int length = value.Length;
                    return value[length - 1];
                }
            }
            """;
        SourceFile file = TestSources.styled("test.vs", source);
        DiagnosticBag diagnostics = new DiagnosticBag();
        AuxiliarySyntax.CompilationUnit unit = Parser.parse(file, diagnostics);
        SemanticModel model = DeclarationBinder.bind(
                java.util.List.of(file), java.util.List.of(unit), diagnostics);
        ExpressionBinding binding = ExpressionBinder.bind(file, unit, model, diagnostics);

        Assert.isFalse(diagnostics.hasErrors(), "built-in string accesses should bind");
        Assert.isTrue(binding.expressions().stream()
                        .anyMatch(BoundExpression.StringLength.class::isInstance),
                "StringLength node should be emitted");
        Assert.isTrue(binding.expressions().stream()
                        .anyMatch(BoundExpression.StringElementAccess.class::isInstance),
                "StringElementAccess node should be emitted");
    }

    private void nullConditionalAccess() {
        String source = """
            static class P {
                static int? Length(string value) => value?.Length;
                static int? Element(int[] values) => values?[0];
            }
            """;
        SourceFile file = TestSources.styled("test.vs", source);
        DiagnosticBag diagnostics = new DiagnosticBag();
        AuxiliarySyntax.CompilationUnit unit = Parser.parse(file, diagnostics);
        SemanticModel model = DeclarationBinder.bind(
                java.util.List.of(file), java.util.List.of(unit), diagnostics);
        ExpressionBinding binding = ExpressionBinder.bind(file, unit, model, diagnostics);

        Assert.isFalse(diagnostics.hasErrors(), "conditional access should bind cleanly");
        long conditionals = binding.expressions().stream()
                .filter(BoundExpression.NullConditional.class::isInstance)
                .count();
        Assert.equal(2L, conditionals,
                "member and element forms should retain their guarded receiver");
    }

    private void nullConditionalReceiverDiagnostics() {
        String source = """
            static class P {
                static void Invalid(int value, int? optional) {
                    value?.ToString();
                    optional?.ToString();
                }
            }
            """;
        // The nullable branch needs `System.Int32.ToString` from corelib, which the facade
        // merges automatically; the direct binder used by the other cases does not.
        CompilationResult result = Compilation.of(List.of(
                TestSources.styled("test.vs", source))).analyze();
        List<String> codes = result.diagnostics().stream()
                .map(Diagnostic::code).map(Object::toString).toList();
        Assert.equalList(List.of("VS0023"), codes,
                "non-nullable values are invalid; nullable-value member dispatch now binds");
    }

    private void indexingNonArrayError() {
        String source = """
            static class P {
                static void M() {
                    int x = 42;
                    int y = x[0];
                }
            }
            """;
        SourceFile file = TestSources.styled("test.vs", source);
        DiagnosticBag diagnostics = new DiagnosticBag();
        AuxiliarySyntax.CompilationUnit unit = Parser.parse(file, diagnostics);
        SemanticModel model = DeclarationBinder.bind(java.util.List.of(file), java.util.List.of(unit), diagnostics);
        ExpressionBinder.bind(file, unit, model, diagnostics);

        Assert.isTrue(diagnostics.hasErrors(), "indexing non-array should produce diagnostic error");
        boolean hasCode = diagnostics.all().stream()
                .anyMatch(d -> d.code() == DiagnosticCode.INDEXING_NON_ARRAY);
        Assert.isTrue(hasCode, "VS0021 INDEXING_NON_ARRAY diagnostic should be present");
    }

    private void wrongIndexCountError() {
        String source = """
            static class P {
                static void M() {
                    int[] arr = new int[5];
                    int y = arr[0, 1];
                }
            }
            """;
        SourceFile file = TestSources.styled("test.vs", source);
        DiagnosticBag diagnostics = new DiagnosticBag();
        AuxiliarySyntax.CompilationUnit unit = Parser.parse(file, diagnostics);
        SemanticModel model = DeclarationBinder.bind(java.util.List.of(file), java.util.List.of(unit), diagnostics);
        ExpressionBinder.bind(file, unit, model, diagnostics);

        Assert.isTrue(diagnostics.hasErrors(), "wrong index count should produce diagnostic error");
        boolean hasCode = diagnostics.all().stream()
                .anyMatch(d -> d.code() == DiagnosticCode.WRONG_INDEX_COUNT);
        Assert.isTrue(hasCode, "VS0022 WRONG_INDEX_COUNT diagnostic should be present");
    }

    private void memberNotFoundError() {
        String source = """
            static class P {
                static void M() {
                    int x = 42;
                    int y = x.NonExistent;
                }
            }
            """;
        SourceFile file = TestSources.styled("test.vs", source);
        DiagnosticBag diagnostics = new DiagnosticBag();
        AuxiliarySyntax.CompilationUnit unit = Parser.parse(file, diagnostics);
        SemanticModel model = DeclarationBinder.bind(java.util.List.of(file), java.util.List.of(unit), diagnostics);
        ExpressionBinder.bind(file, unit, model, diagnostics);

        Assert.isTrue(diagnostics.hasErrors(), "non-existent member access should produce diagnostic error");
        boolean hasCode = diagnostics.all().stream()
                .anyMatch(d -> d.code() == DiagnosticCode.MEMBER_NOT_FOUND);
        Assert.isTrue(hasCode, "VS0117 MEMBER_NOT_FOUND diagnostic should be present");
    }

    private void instanceFieldAccess() {
        String source = """
            static class P {
                struct Point {
                    public int X;
                }
                static void M() {
                    Point p = default;
                    int x = p.X;
                }
            }
            """;
        SourceFile file = TestSources.styled("test.vs", source);
        DiagnosticBag diagnostics = new DiagnosticBag();
        AuxiliarySyntax.CompilationUnit unit = Parser.parse(file, diagnostics);
        SemanticModel model = DeclarationBinder.bind(java.util.List.of(file), java.util.List.of(unit), diagnostics);
        ExpressionBinding binding = ExpressionBinder.bind(file, unit, model, diagnostics);
        Assert.isFalse(diagnostics.hasErrors(), "instance field access binding should succeed");

        boolean hasMemberAccess = binding.expressions().stream()
                .anyMatch(BoundExpression.MemberAccess.class::isInstance);
        Assert.isTrue(hasMemberAccess, "BoundExpression.MemberAccess node should be emitted");
    }

    /// `int.MaxValue` is the one shape in which a keyword type may appear as an expression.
    private void builtinLimitsBind() {
        String source = """
            static class P {
                static void M() {
                    int high = int.MaxValue;
                    long low = long.MinValue;
                    char last = char.MaxValue;
                }
            }
            """;
        SourceFile file = TestSources.styled("test.vs", source);
        DiagnosticBag diagnostics = new DiagnosticBag();
        AuxiliarySyntax.CompilationUnit unit = Parser.parse(file, diagnostics);
        SemanticModel model = DeclarationBinder.bind(List.of(file), List.of(unit), diagnostics);
        ExpressionBinder.bind(file, unit, model, diagnostics);
        Assert.isFalse(diagnostics.hasErrors(), "MinValue/MaxValue should bind");
    }

    /// A keyword type is the same C# type as its System declaration. Static functions on the
    /// curated declaration must therefore bind through `char`, not only `System.Char`.
    private void builtinStaticMethodsBind() {
        String source = """
            static class P {
                static bool M(char value) {
                    return char.IsDigit(value) || char.IsLetter(value)
                        || char.IsLetterOrDigit(value) || char.IsWhiteSpace(value)
                        || char.ToUpperInvariant(value) == char.ToLowerInvariant(value);
                }
            }
            """;
        CompilationResult result = Compilation.of(
                List.of(TestSources.styled("test.vs", source))).analyze();
        Assert.isFalse(result.hasErrors(),
                "static curated members should bind through the keyword type");
    }

    /// C# 13's string/object params-span overloads make the no-value calls ambiguous. The
    /// curated string[] representation must not accidentally accept a program C# rejects.
    private void emptyStringParamsCallsAreAmbiguous() {
        CompilationResult result = Compilation.of(List.of(TestSources.styled("test.vs", """
            static class P {
                static void M() {
                    string a = string.Concat();
                    string b = string.Join(",");
                }
            }
            """))).analyze();
        List<String> codes = result.diagnostics().stream()
                .map(Diagnostic::code).map(Object::toString).toList();
        Assert.equalList(List.of("VS0121", "VS0121"), codes,
                "both empty params calls are ambiguous");
    }

    /// `Equals(object)` and `GetHashCode()` reach only the carriers that can answer them
    /// exactly. `byte`/`ushort` box as their signed JVM carrier and `uint`/`ulong` box
    /// as the signed type of the same width, so an `Equals` type test would report `true`
    /// where C# reports `false` and a hash could not tell the two C# types apart; both members
    /// stay unresolvable there rather than answering wrongly. `GetType()` is refused for the
    /// same reason on every value: the carrier cannot name the C# type it holds.
    private void universalMembersAbsentFromAmbiguousCarriers() {
        String source = """
            static class P {
                static void M() {
                    uint u = 1;
                    byte b = 2;
                    short s = 3;
                    object o = null;
                    int first = u.GetHashCode();
                    bool second = b.Equals(2);
                    int third = s.GetHashCode();
                    o.GetType();
                }
            }
            """;
        SourceFile file = TestSources.styled("test.vs", source);
        DiagnosticBag diagnostics = new DiagnosticBag();
        AuxiliarySyntax.CompilationUnit unit = Parser.parse(file, diagnostics);
        SemanticModel model = DeclarationBinder.bind(List.of(file), List.of(unit), diagnostics);
        ExpressionBinder.bind(file, unit, model, diagnostics);

        List<String> messages = diagnostics.all().stream()
                .filter(d -> d.code() == DiagnosticCode.MEMBER_NOT_FOUND)
                .map(d -> d.message())
                .toList();
        Assert.equal(4, messages.size(), "every ambiguous carrier is rejected");
        Assert.isTrue(messages.get(0).contains("'uint'"), "diagnostic names uint");
        Assert.isTrue(messages.get(1).contains("'byte'"), "diagnostic names byte");
        Assert.isTrue(messages.get(2).contains("'short'"), "diagnostic names short");
        Assert.isTrue(messages.get(3).contains("'GetType'"), "GetType stays absent");
    }

    /// The curated surface is a closed list. `Parse` and the default Char casing
    /// members are culture-sensitive in C#, and `bool` has no limits at all, so all must
    /// stay unresolvable rather than approximated,
    /// and the diagnostic must name the keyword the program wrote - not the corelib struct
    /// the lookup goes through.
    private void uncuratedBuiltinMembersRejected() {
        String source = """
            static class P {
                static void M() {
                    int parsed = int.Parse("1");
                    bool flag = bool.MaxValue;
                    char upper = char.ToUpper('i');
                    char lower = char.ToLower('I');
                }
            }
            """;
        SourceFile file = TestSources.styled("test.vs", source);
        DiagnosticBag diagnostics = new DiagnosticBag();
        AuxiliarySyntax.CompilationUnit unit = Parser.parse(file, diagnostics);
        SemanticModel model = DeclarationBinder.bind(List.of(file), List.of(unit), diagnostics);
        ExpressionBinder.bind(file, unit, model, diagnostics);

        List<String> messages = diagnostics.all().stream()
                .filter(d -> d.code() == DiagnosticCode.MEMBER_NOT_FOUND)
                .map(d -> d.message())
                .toList();
        Assert.equal(4, messages.size(), "all uncurated members are rejected");
        Assert.isTrue(messages.get(0).contains("'int'"), "diagnostic names the keyword type");
        Assert.isTrue(messages.get(1).contains("'bool'"), "diagnostic names the keyword type");
        Assert.isTrue(messages.get(2).contains("'char'"), "upper diagnostic names char");
        Assert.isTrue(messages.get(3).contains("'char'"), "lower diagnostic names char");
    }

    /// Every V# named type is a struct, and C# accepts an instance field initializer on a
    /// struct only when it also declares an explicit parameterless constructor. V# has no
    /// constructors, so the initializer is refused rather than accepted and never run.
    private void instanceFieldInitializerRefused() {
        String source = """
            static class P {
                struct Point {
                    public int X = 5;
                }
                static int Counter = 3;
            }
            """;
        SourceFile file = TestSources.styled("test.vs", source);
        DiagnosticBag diagnostics = new DiagnosticBag();
        AuxiliarySyntax.CompilationUnit unit = Parser.parse(file, diagnostics);
        DeclarationBinder.bind(List.of(file), List.of(unit), diagnostics);

        List<DiagnosticCode> codes = diagnostics.all().stream().map(d -> d.code()).toList();
        Assert.equalList(List.of(DiagnosticCode.OBJECT_MODEL_UNSUPPORTED), codes,
                "only the instance initializer is refused; the static one is accepted");
    }

    /// No construct a user can write may reach the emitter's internal errors. Each of
    /// these produced `VS29999 Internal compiler error` before; each is now a positioned
    /// `VS20002` naming what is missing.
    private void unlowerableConstructsDiagnosed() {
        // `{v:X}` used to stand in for "unlowerable" here; the design implements it, so the case
        // now asserts both halves: a supported specifier binds silently, and one this build
        // cannot honour is still a positioned VS20002 rather than an internal error.
        Assert.equal(0L, notYetImplemented("""
                static class P {
                    static void M(int v) {
                        string b = $"{v,6:X}";
                    }
                }
                """), "an invariant specifier with an alignment clause binds");

        Assert.equal(1L, notYetImplemented("""
                static class P {
                    static void M(int v) {
                        string b = $"{v:N2}";
                    }
                }
                """), "a culture-dependent specifier is refused with a diagnostic");
    }

    /// The number of `VS20002` diagnostics `source` produces through the front end.
    private static long notYetImplemented(String source) {
        SourceFile file = TestSources.styled("test.vs", source);
        DiagnosticBag diagnostics = new DiagnosticBag();
        AuxiliarySyntax.CompilationUnit unit = Parser.parse(file, diagnostics);
        SemanticModel model = DeclarationBinder.bind(List.of(file), List.of(unit), diagnostics);
        ExpressionBinder.bind(file, unit, model, diagnostics);
        return diagnostics.all().stream()
                .filter(d -> d.code() == DiagnosticCode.NOT_YET_IMPLEMENTED)
                .count();
    }
}
