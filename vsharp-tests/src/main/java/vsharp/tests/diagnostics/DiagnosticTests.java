package vsharp.tests.diagnostics;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import vsharp.compiler.diagnostics.Diagnostic;
import vsharp.compiler.diagnostics.DiagnosticBag;
import vsharp.compiler.diagnostics.DiagnosticCode;
import vsharp.compiler.diagnostics.DiagnosticFormatter;
import vsharp.compiler.diagnostics.Severity;
import vsharp.compiler.source.SourceFile;
import vsharp.compiler.source.SourceSpan;
import vsharp.testkit.Assert;
import vsharp.testkit.TestRegistry;
import vsharp.testkit.TestSuite;

/// Diagnostic codes, ordering and rendering.
///
/// Determinism is the point of most of these: CI compares compiler output textually, so
/// identical input must produce byte-identical diagnostics regardless of the order stages
/// happened to report them or the machine's locale.
public final class DiagnosticTests implements TestSuite {

    private static final SourceFile FILE = SourceFile.of("Program.vs", "int x = 1\nint y = 2;\n");

    @Override
    public String suiteName() {
        return "diagnostics";
    }

    @Override
    public void register(TestRegistry registry) {
        registry.test("codes are unique", () -> {
            Set<Integer> numbers = java.util.Arrays.stream(DiagnosticCode.values())
                    .map(DiagnosticCode::number).collect(Collectors.toSet());
            Assert.equal((long) DiagnosticCode.values().length, (long) numbers.size(),
                    "every diagnostic code has its own number");
        });

        registry.test("code identifiers mirror the C# form", () -> {
            Assert.equal("VS1002", DiagnosticCode.SEMICOLON_EXPECTED.id(),
                    "missing semicolon matches Roslyn's CS1002");
            Assert.equal("VS20001", DiagnosticCode.OBJECT_MODEL_UNSUPPORTED.id(),
                    "V#-specific restrictions live above the CS range");
            Assert.equal("VS1002", DiagnosticCode.SEMICOLON_EXPECTED.toString(),
                    "rendered form matches Roslyn casing");
        });

        registry.test("message formatting ignores the machine locale", () -> {
            Locale previous = Locale.getDefault();
            try {
                Locale.setDefault(Locale.forLanguageTag("tr-TR"));
                Assert.equal("Identifier expected",
                        DiagnosticCode.IDENTIFIER_EXPECTED.format(), "argument-free message");
                Assert.equal("VS1002", DiagnosticCode.SEMICOLON_EXPECTED.id(),
                        "identifiers are not affected by Turkish casing");
            } finally {
                Locale.setDefault(previous);
            }
        });

        registry.test("diagnostic renders in MSBuild single-line form", () -> {
            Diagnostic diagnostic = Diagnostic.of(DiagnosticCode.SEMICOLON_EXPECTED, FILE,
                    SourceSpan.at(9));
            Assert.equal("Program.vs(1,10): error VS1002: ; expected", diagnostic.toString(),
                    "headline");
            Assert.isTrue(diagnostic.isError(), "severity");
        });

        registry.test("detailed rendering underlines the offending span", () -> {
            Diagnostic diagnostic = Diagnostic.of(DiagnosticCode.UNEXPECTED_CHARACTER, FILE,
                    SourceSpan.between(4, 5), "x");
            String rendered = DiagnosticFormatter.detailed().format(diagnostic);
            List<String> lines = rendered.lines().toList();
            Assert.equal(3L, (long) lines.size(), "headline, excerpt and caret");
            Assert.equal("1 | int x = 1", lines.get(1), "excerpt");
            Assert.equal("  |     ^", lines.get(2), "caret under column 5");
        });

        registry.test("an empty span still gets one caret", () -> {
            Diagnostic diagnostic = Diagnostic.of(DiagnosticCode.SEMICOLON_EXPECTED, FILE,
                    SourceSpan.at(9));
            List<String> lines = DiagnosticFormatter.detailed().format(diagnostic).lines().toList();
            Assert.equal("  |          ^", lines.get(2), "caret at the missing token");
        });

        registry.test("bag reports in display order regardless of insertion order", () -> {
            DiagnosticBag bag = new DiagnosticBag();
            bag.report(DiagnosticCode.SEMICOLON_EXPECTED, FILE, SourceSpan.at(20));
            bag.report(DiagnosticCode.IDENTIFIER_EXPECTED, FILE, SourceSpan.at(4));
            bag.report(DiagnosticCode.STATEMENT_EXPECTED, FILE, SourceSpan.at(4));
            List<Integer> starts = bag.all().stream().map(d -> d.span().start()).toList();
            Assert.equalList(List.of(4, 4, 20), starts, "sorted by position");
            Assert.equal("VS1001", bag.all().get(0).code().toString(),
                    "ties broken by code, not by insertion");
            Assert.equal(3L, (long) bag.errorCount(), "error count");
            Assert.isTrue(bag.hasErrors(), "hasErrors");
        });

        registry.test("only errors block artifact generation", () -> {
            Assert.equal(Severity.ERROR, DiagnosticCode.SEMICOLON_EXPECTED.defaultSeverity(),
                    "syntax errors are errors");
            Assert.equal("warning", Severity.WARNING.label(), "MSBuild label");
            Assert.equal("info", Severity.INFO.label(), "MSBuild label");
            Diagnostic warning = new Diagnostic(DiagnosticCode.SEMICOLON_EXPECTED,
                    Severity.WARNING, "downgraded", FILE, SourceSpan.at(0));
            Assert.isFalse(warning.isError(), "a downgraded diagnostic is not an error");
            DiagnosticBag bag = new DiagnosticBag();
            bag.add(warning);
            Assert.isFalse(bag.hasErrors(), "warnings alone leave the bag error-free");
            Assert.isTrue(bag.errors().isEmpty(), "errors view excludes warnings");
        });

        registry.test("bag merges another bag without losing order", () -> {
            DiagnosticBag first = new DiagnosticBag();
            first.report(DiagnosticCode.SEMICOLON_EXPECTED, FILE, SourceSpan.at(9));
            DiagnosticBag second = new DiagnosticBag();
            second.report(DiagnosticCode.IDENTIFIER_EXPECTED, FILE, SourceSpan.at(0));
            second.addAll(first.all());
            Assert.equal(2L, (long) second.all().size(), "merged size");
            Assert.equal(0L, (long) second.all().get(0).span().start(), "still sorted");
        });
    }
}
