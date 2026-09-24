package vsharp.tests.syntax;

import java.util.List;
import vsharp.compiler.api.Compilation;
import vsharp.compiler.api.CompilationResult;
import vsharp.compiler.api.Phase;
import vsharp.compiler.diagnostics.Diagnostic;
import vsharp.compiler.source.SourceFile;
import vsharp.testkit.Assert;
import vsharp.testkit.TestRegistry;
import vsharp.testkit.TestSuite;

/// Compiler-level coverage for V#'s mandatory Allman and brace-indentation contract.
///
/// These inputs deliberately bypass `TestSources`: the exact whitespace is the behaviour under
/// test. Parser-only suites stay layout-neutral, while every real compilation enters this gate.
public final class SourceStyleTests implements TestSuite {

    @Override
    public String suiteName() {
        return "syntax.style";
    }

    @Override
    public void register(TestRegistry registry) {
        registry.test("canonical Allman source reaches semantic analysis",
                SourceStyleTests::canonicalAllmanSource);
        registry.test("opening braces occupy their own new line",
                SourceStyleTests::openingBracesOwnLine);
        registry.test("closing braces begin their line",
                SourceStyleTests::closingBracesBeginLine);
        registry.test("every brace uses four-space lexical indentation",
                SourceStyleTests::braceIndentation);
        registry.test("tabs cannot disguise brace indentation",
                SourceStyleTests::tabsAreRefused);
        registry.test("inactive code comments and literals do not contain syntax braces",
                SourceStyleTests::onlyActiveSyntaxIsChecked);
        registry.test("expression and pattern braces may be written on one line",
                SourceStyleTests::inlineExpressionBraces);
        registry.test("a spread expression brace is a block again",
                SourceStyleTests::spreadExpressionBraces);
        registry.test("an inline pair does not exempt the blocks written inside it",
                SourceStyleTests::inlinePairKeepsInnerBlocks);
    }

    private static void canonicalAllmanSource() {
        CompilationResult result = analyze("""
                static class C
                {
                    static int Sum()
                    {
                        int[] values = new int[]
                        {
                            1,
                            2
                        };
                        if (values.Length == 2)
                        {
                            return values[0] + values[1];
                        }
                        return 0;
                    }
                }
                """);
        Assert.equalList(List.of(), codes(result), "canonical diagnostics");
        Assert.equal(Phase.FLOW_ANALYSIS, result.reached(), "style permits later phases");
    }

    private static void openingBracesOwnLine() {
        CompilationResult result = analyze("""
                static class C {
                    static void F()
                    { return;
                    }
                }
                """);
        Assert.equalList(List.of("VS20007", "VS20007"), codes(result),
                "one positioned diagnostic per misplaced opening brace");
        Assert.equal(Phase.PARSE, result.reached(), "style gates binding");
    }

    private static void closingBracesBeginLine() {
        CompilationResult result = analyze("""
                static class C
                {
                    static void F()
                    {
                        return; }
                }
                """);
        Assert.equalList(List.of("VS20008"), codes(result),
                "inline closing brace diagnostic");
    }

    private static void braceIndentation() {
        CompilationResult result = analyze("""
                static class C
                 {
                    static void F()
                   {
                   }
                }
                """);
        Assert.equalList(List.of("VS20009", "VS20009", "VS20009"), codes(result),
                "top-level and nested brace indentation");
    }

    private static void tabsAreRefused() {
        CompilationResult result = analyze("static class C\n{\n    static void F()\n\t{\n\t}\n}\n");
        Assert.equalList(List.of("VS20009", "VS20009"), codes(result),
                "tabs never equal structural spaces");
    }

    private static void onlyActiveSyntaxIsChecked() {
        CompilationResult result = analyze("""
                #if NEVER
                static class Hidden { static void Bad() { } }
                #endif
                static class C
                {
                    static string Text()
                    {
                        // A comment containing { and } is trivia.
                        return "{literal}";
                    }
                }
                """);
        Assert.equalList(List.of(), codes(result), "non-syntax braces are ignored");
    }

    /// The three constructs C# writes as a brace inside an expression or a pattern stay
    /// writable in their canonical spelling: an array initializer, a property subpattern and a
    /// `with` initializer, each complete on one line.
    private static void inlineExpressionBraces() {
        CompilationResult result = analyze("""
                static class C
                {
                    record struct Order(string Customer, int Quantity);

                    static int[] Values()
                    {
                        return new int[] { 1, 2, 3 };
                    }

                    static string Classify(Order o)
                    {
                        return o switch
                        {
                            Order { Quantity: > 100 } => "bulk",
                            _ => "plain"
                        };
                    }

                    static Order Bump(Order o)
                    {
                        return o with { Quantity = 5 };
                    }
                }
                """);
        Assert.equalList(List.of(), codes(result), "inline expression braces are admissible");
        Assert.equal(Phase.FLOW_ANALYSIS, result.reached(), "style permits later phases");
    }

    /// Spread across lines the same initializer is an ordinary block and obeys Allman, so the
    /// exemption cannot become a licence for free-form layout.
    private static void spreadExpressionBraces() {
        CompilationResult result = analyze("""
                static class C
                {
                    static int[] F()
                    {
                        return new int[] {
                            1,
                            2 };
                    }
                }
                """);
        Assert.equalList(List.of("VS20007", "VS20008"), codes(result),
                "a multi-line initializer keeps both brace rules");
    }

    /// Both members of an exempt pair are skipped, so lexical depth is unchanged and blocks
    /// written after or inside one are still measured against their real enclosing block.
    private static void inlinePairKeepsInnerBlocks() {
        CompilationResult result = analyze("""
                static class C
                {
                    static int F()
                    {
                        int[] xs = new int[] { 1, 2 };
                        if (xs.Length == 2)
                         {
                         }
                        return 0;
                    }
                }
                """);
        Assert.equalList(List.of("VS20009", "VS20009"), codes(result),
                "depth accounting survives the exempt pair");
    }

    private static CompilationResult analyze(String text) {
        SourceFile file = SourceFile.of("Style.vs", text);
        return Compilation.of(List.of(file)).analyze();
    }

    private static List<String> codes(CompilationResult result) {
        return result.diagnostics().stream()
                .map(Diagnostic::code)
                .map(Object::toString)
                .toList();
    }
}
