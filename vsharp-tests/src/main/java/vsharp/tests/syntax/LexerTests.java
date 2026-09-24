package vsharp.tests.syntax;

import java.math.BigDecimal;
import java.util.List;
import vsharp.compiler.diagnostics.Diagnostic;
import vsharp.compiler.diagnostics.DiagnosticBag;
import vsharp.compiler.source.SourceFile;
import vsharp.compiler.syntax.InterpolationPart;
import vsharp.compiler.syntax.Lexer;
import vsharp.compiler.syntax.LiteralType;
import vsharp.compiler.syntax.SyntaxFacts;
import vsharp.compiler.syntax.SyntaxKind;
import vsharp.compiler.syntax.SyntaxToken;
import vsharp.testkit.Assert;
import vsharp.testkit.TestRegistry;
import vsharp.testkit.TestSuite;

/// The C# 13 lexical grammar.
///
/// Every case states the C# behaviour it pins, because the lexer's job is fidelity: a
/// difference from C# here is a bug even when the result is defensible in isolation.
public final class LexerTests implements TestSuite {

    @Override
    public String suiteName() {
        return "syntax.lexer";
    }

    @Override
    public void register(TestRegistry registry) {
        registerNames(registry);
        registerNumbers(registry);
        registerCharactersAndStrings(registry);
        registerRawStrings(registry);
        registerInterpolation(registry);
        registerOperatorsAndTrivia(registry);
        registerRecovery(registry);
    }

    // ---- Helpers -----------------------------------------------------------------------

    private static SourceFile source(String text) {
        return SourceFile.of("Test.vs", text);
    }

    /// Lexes and asserts that nothing was reported.
    private static List<SyntaxToken> lex(String text) {
        DiagnosticBag bag = new DiagnosticBag();
        List<SyntaxToken> tokens = Lexer.tokenize(source(text), bag);
        Assert.isTrue(bag.isEmpty(), "unexpected diagnostics for <" + text + ">: " + bag.all());
        return tokens;
    }

    /// Lexes and returns the diagnostic codes, in display order.
    private static List<String> errors(String text) {
        DiagnosticBag bag = new DiagnosticBag();
        Lexer.tokenize(source(text), bag);
        return bag.all().stream().map(Diagnostic::code).map(Object::toString).toList();
    }

    private static List<SyntaxKind> kinds(String text) {
        return lex(text).stream().map(SyntaxToken::kind).toList();
    }

    /// The single significant token of `text`, ignoring the end-of-file token.
    private static SyntaxToken single(String text) {
        List<SyntaxToken> tokens = lex(text);
        Assert.equal(2L, (long) tokens.size(), "one token plus end of file in <" + text + ">");
        return tokens.getFirst();
    }

    // ---- Identifiers and keywords ------------------------------------------------------

    private static void registerNames(TestRegistry registry) {
        registry.test("keywords are recognised and identifiers are not", () -> {
            Assert.equalList(
                    List.of(SyntaxKind.INT, SyntaxKind.IDENTIFIER, SyntaxKind.EQUALS,
                            SyntaxKind.INTEGER_LITERAL, SyntaxKind.SEMICOLON,
                            SyntaxKind.END_OF_FILE),
                    kinds("int x = 1;"), "declaration");
        });

        registry.test("contextual keywords stay identifiers", () -> {
            // A C# program may declare a variable named `var`, `record` or `when`.
            for (String word : List.of("var", "record", "when", "value", "async", "await")) {
                SyntaxToken token = single(word);
                Assert.equal(SyntaxKind.IDENTIFIER, token.kind(), word + " is an identifier");
                Assert.isTrue(SyntaxFacts.isContextualKeyword(word),
                        word + " is known to the parser as contextual");
            }
        });

        registry.test("a verbatim identifier drops its at sign and never reserves", () -> {
            SyntaxToken token = single("@if");
            Assert.equal(SyntaxKind.IDENTIFIER, token.kind(), "kind");
            Assert.equal("if", token.text(), "text excludes the at sign");
            Assert.equal(3L, (long) token.span().length(), "span includes the at sign");
        });

        registry.test("identifiers accept Unicode escapes and Unicode letters", () -> {
            Assert.equal("abc", single("\\u0061bc").text(), "escaped first character");
            Assert.equal("café", single("café").text(), "letters outside ASCII");
            Assert.equal("_x", single("_x").text(), "leading underscore");
        });

        registry.test("keyword literals carry their value", () -> {
            Assert.equal(Boolean.TRUE, single("true").valueAs(Boolean.class), "true");
            Assert.equal(Boolean.FALSE, single("false").valueAs(Boolean.class), "false");
            Assert.equal(LiteralType.NULL, single("null").literalType(), "null literal type");
        });
    }

    // ---- Numeric literals --------------------------------------------------------------

    private static void registerNumbers(TestRegistry registry) {
        registry.test("the unsuffixed literal ladder is int, uint, long, ulong", () -> {
            Assert.equal(LiteralType.INT, single("2147483647").literalType(), "int max");
            Assert.equal(LiteralType.UINT, single("2147483648").literalType(),
                    "past int max becomes uint, which is why -2147483648 needs folding");
            Assert.equal(LiteralType.LONG, single("4294967296").literalType(), "past uint max");
            Assert.equal(LiteralType.ULONG, single("9223372036854775808").literalType(),
                    "past long max");
        });

        registry.test("suffixes restrict the ladder", () -> {
            Assert.equal(LiteralType.UINT, single("1u").literalType(), "u");
            Assert.equal(LiteralType.LONG, single("1L").literalType(), "L");
            Assert.equal(LiteralType.ULONG, single("1UL").literalType(), "UL");
            Assert.equal(LiteralType.ULONG, single("1lu").literalType(), "lu");
            Assert.equal(LiteralType.ULONG, single("5000000000u").literalType(),
                    "u still promotes to ulong when the value does not fit");
        });

        registry.test("integer values are decoded in every radix", () -> {
            Assert.equal(Integer.valueOf(255), single("0xFF").valueAs(Integer.class), "hex");
            Assert.equal(Integer.valueOf(170), single("0b1010_1010").valueAs(Integer.class),
                    "binary with separators");
            Assert.equal(Integer.valueOf(1_000_000), single("1_000_000").valueAs(Integer.class),
                    "decimal with separators");
            Assert.equal(Long.valueOf(-1L), single("0xFFFFFFFFFFFFFFFF").valueAs(Long.class),
                    "ulong max is stored as raw bits");
        });

        registry.test("real literals take their type from the suffix", () -> {
            Assert.equal(LiteralType.DOUBLE, single("1.5").literalType(), "default is double");
            Assert.equal(Double.valueOf(1.5d), single("1.5").valueAs(Double.class), "value");
            Assert.equal(Float.valueOf(1.5f), single("1.5f").valueAs(Float.class), "float");
            Assert.equal(LiteralType.DOUBLE, single("1d").literalType(), "integral with d");
            Assert.equal(new BigDecimal("1"), single("1m").valueAs(BigDecimal.class), "decimal");
            Assert.equal(Double.valueOf(1000d), single("1e3").valueAs(Double.class), "exponent");
            Assert.equal(Double.valueOf(0.5d), single(".5").valueAs(Double.class), "leading dot");
            Assert.equal(Double.valueOf(0.0015d), single("1.5e-3").valueAs(Double.class),
                    "signed exponent");
        });

        registry.test("a dot only joins a number when a digit follows", () -> {
            // `1..2` is a range and `1.ToString()` is a member access; neither is a real.
            Assert.equalList(
                    List.of(SyntaxKind.INTEGER_LITERAL, SyntaxKind.DOT_DOT,
                            SyntaxKind.INTEGER_LITERAL, SyntaxKind.END_OF_FILE),
                    kinds("1..2"), "range");
            Assert.equalList(
                    List.of(SyntaxKind.INTEGER_LITERAL, SyntaxKind.DOT, SyntaxKind.IDENTIFIER,
                            SyntaxKind.END_OF_FILE),
                    kinds("1.ToString"), "member access");
        });

        registry.test("malformed numbers are reported, not thrown", () -> {
            Assert.equalList(List.of("VS1021"), errors("99999999999999999999999"), "overflow");
            Assert.equalList(List.of("VS1085"), errors("1z"), "unknown suffix");
            Assert.equalList(List.of("VS1013"), errors("1_"), "trailing separator");
            Assert.equalList(List.of("VS1013"), errors("0x"), "no hex digits");
            Assert.equalList(List.of("VS1013"), errors("1e"), "no exponent digits");
        });
    }

    // ---- Characters and strings --------------------------------------------------------

    private static void registerCharactersAndStrings(TestRegistry registry) {
        registry.test("character escapes follow C#", () -> {
            Assert.equal(Character.valueOf('a'), single("'a'").valueAs(Character.class), "plain");
            Assert.equal(Character.valueOf('\n'), single("'\\n'").valueAs(Character.class), "n");
            Assert.equal(Character.valueOf((char) 0x0007),
                    single("'\\a'").valueAs(Character.class), "alert has no Java escape");
            Assert.equal(Character.valueOf('A'), single("'\\u0041'").valueAs(Character.class),
                    "four-digit Unicode escape");
            Assert.equal(Character.valueOf('A'), single("'\\x41'").valueAs(Character.class),
                    "variable-length hex escape");
            Assert.equal(Character.valueOf('\0'), single("'\\0'").valueAs(Character.class),
                    "null character");
        });

        registry.test("character literal errors", () -> {
            Assert.equalList(List.of("VS1011"), errors("''"), "empty");
            Assert.equalList(List.of("VS1012"), errors("'ab'"), "too many");
            Assert.equalList(List.of("VS1009"), errors("'\\q'"), "unknown escape");
            // The quote that closed nothing opens a second literal, which then runs to the
            // end of the file: C# cascades here too, and suppressing it would hide the
            // second, genuinely unterminated literal.
            Assert.equalList(List.of("VS1010", "VS1010"), errors("'a\nb'"),
                    "newline in constant, then the trailing quote opens an unclosed literal");
        });

        registry.test("regular strings decode escapes", () -> {
            Assert.equal("a\tb", single("\"a\\tb\"").valueAs(String.class), "tab");
            Assert.equal("", single("\"\"").valueAs(String.class), "empty string");
            Assert.equal("q\"q", single("\"q\\\"q\"").valueAs(String.class), "escaped quote");
        });

        registry.test("verbatim strings take backslashes literally and span lines", () -> {
            Assert.equal("a\\tb", single("@\"a\\tb\"").valueAs(String.class),
                    "no escape processing");
            Assert.equal("a\"b", single("@\"a\"\"b\"").valueAs(String.class),
                    "a doubled quote is one quote");
            Assert.equal("a\nb", single("@\"a\nb\"").valueAs(String.class), "newlines allowed");
        });

        registry.test("a u8 suffix produces UTF-8 bytes", () -> {
            SyntaxToken token = single("\"hé\"u8");
            Assert.equal(SyntaxKind.UTF8_STRING_LITERAL, token.kind(), "kind");
            byte[] bytes = token.valueAs(byte[].class);
            Assert.equal(3L, (long) bytes.length, "e-acute is two bytes in UTF-8");
        });

        registry.test("string literal errors", () -> {
            Assert.equalList(List.of("VS1010", "VS1039"), errors("\"a\nb\""),
                    "newline in constant, then the trailing quote runs to end of file");
            Assert.equalList(List.of("VS1039"), errors("\"abc"), "unterminated at end of file");
            Assert.equalList(List.of("VS1039"), errors("@\"abc"), "unterminated verbatim");
        });
    }

    // ---- Raw strings -------------------------------------------------------------------

    private static void registerRawStrings(TestRegistry registry) {
        registry.test("a single-line raw string is taken exactly as written", () -> {
            Assert.equal("a\\tb\"c", single("\"\"\"a\\tb\"c\"\"\"").valueAs(String.class),
                    "no escapes, and shorter quote runs are content");
        });

        registry.test("a multi-line raw string strips the closing indentation", () -> {
            String literal = "\"\"\"\n  hello\n  world\n  \"\"\"";
            Assert.equal("hello\nworld", single(literal).valueAs(String.class), "content");
        });

        registry.test("a multi-line raw string keeps relative indentation", () -> {
            String literal = "\"\"\"\n  a\n    b\n  \"\"\"";
            Assert.equal("a\n  b", single(literal).valueAs(String.class), "deeper line keeps two");
        });

        registry.test("longer delimiters let content hold quote runs", () -> {
            Assert.equal("a\"\"\"b",
                    single("\"\"\"\"a\"\"\"b\"\"\"\"").valueAs(String.class), "four quotes");
        });

        registry.test("raw string errors", () -> {
            Assert.equalList(List.of("VS8997"), errors("\"\"\"abc"), "never closed");
            Assert.equalList(List.of("VS8998"),
                    errors("\"\"\"\n  a\nb\n  \"\"\""), "line indented less than the closing");
        });
    }

    // ---- Interpolation -----------------------------------------------------------------

    private static void registerInterpolation(TestRegistry registry) {
        registry.test("an interpolated string splits into text and holes", () -> {
            String literal = "$\"a{b}c\"";
            SyntaxToken token = single(literal);
            Assert.equal(SyntaxKind.INTERPOLATED_STRING, token.kind(), "kind");
            List<?> parts = token.valueAs(List.class);
            Assert.equal(3L, (long) parts.size(), "text, hole, text");
            Assert.equal("a", ((InterpolationPart.Text) parts.get(0)).value(), "leading text");
            Assert.equal("c", ((InterpolationPart.Text) parts.get(2)).value(), "trailing text");
            InterpolationPart.Hole hole = (InterpolationPart.Hole) parts.get(1);
            Assert.equal("b", source(literal).textOf(hole.expression()),
                    "the hole's span points at the unparsed expression");
        });

        registry.test("a hole carries alignment and format clauses", () -> {
            String literal = "$\"{x,3:F2}\"";
            List<?> parts = single(literal).valueAs(List.class);
            InterpolationPart.Hole hole = (InterpolationPart.Hole) parts.getFirst();
            Assert.equal("x", source(literal).textOf(hole.expression()), "expression");
            Assert.equal("3", source(literal).textOf(hole.alignmentSpan().orElseThrow()),
                    "alignment");
            Assert.equal("F2", hole.formatClause().orElseThrow(), "format");
        });

        registry.test("holes tolerate nested punctuation", () -> {
            String literal = "$\"{f(a, b)}\"";
            List<?> parts = single(literal).valueAs(List.class);
            InterpolationPart.Hole hole = (InterpolationPart.Hole) parts.getFirst();
            Assert.equal("f(a, b)", source(literal).textOf(hole.expression()),
                    "a comma inside parentheses is not an alignment clause");
        });

        registry.test("doubled braces are literal text", () -> {
            List<?> parts = single("$\"{{x}}\"").valueAs(List.class);
            Assert.equal(1L, (long) parts.size(), "no holes");
            Assert.equal("{x}", ((InterpolationPart.Text) parts.getFirst()).value(), "text");
        });

        registry.test("verbatim interpolated strings accept both orders", () -> {
            for (String literal : List.of("$@\"a{b}\"", "@$\"a{b}\"")) {
                List<?> parts = single(literal).valueAs(List.class);
                Assert.equal(2L, (long) parts.size(), literal + " splits into text and hole");
            }
        });

        registry.test("interpolation errors", () -> {
            Assert.equalList(List.of("VS8990"), errors("$\"{}\""), "empty hole");
            Assert.equalList(List.of("VS8991"), errors("$\"a}b\""), "unescaped close brace");
            Assert.equalList(List.of("VS8990"), errors("$\"\"\"a{b\"\"\""),
                    "unclosed raw interpolation hole");
        });

        registry.test("interpolated raw strings use their dollar count as brace width", () -> {
            List<?> singleDollar = single("$\"\"\"a{value}c\"\"\"").valueAs(List.class);
            Assert.equal(3L, singleDollar.size(), "text hole text");
            Assert.equal("a", ((InterpolationPart.Text) singleDollar.get(0)).value(),
                    "leading text");
            Assert.equal("value", source("$\"\"\"a{value}c\"\"\"").textOf(
                    ((InterpolationPart.Hole) singleDollar.get(1)).expression()),
                    "single-dollar hole");

            String json = "$$\"\"\"{\"value\": {{item}}}\"\"\"";
            List<?> doubleDollar = single(json).valueAs(List.class);
            Assert.equal(3L, doubleDollar.size(), "literal braces around one hole");
            Assert.equal("{\"value\": ",
                    ((InterpolationPart.Text) doubleDollar.get(0)).value(), "JSON prefix");
            Assert.equal("item", source(json).textOf(
                    ((InterpolationPart.Hole) doubleDollar.get(1)).expression()),
                    "double-dollar hole");
            Assert.equal("}", ((InterpolationPart.Text) doubleDollar.get(2)).value(),
                    "JSON suffix");
        });

        registry.test("multiline raw interpolation strips indentation before splitting", () -> {
            String literal = "$\"\"\"\n    begin {value}\n    end\n    \"\"\"";
            List<?> parts = single(literal).valueAs(List.class);
            Assert.equal(3L, parts.size(), "multiline parts");
            Assert.equal("begin ", ((InterpolationPart.Text) parts.get(0)).value(),
                    "first line indentation");
            Assert.equal("\nend", ((InterpolationPart.Text) parts.get(2)).value(),
                    "remaining normalised content");
        });
    }

    // ---- Operators and trivia ----------------------------------------------------------

    private static void registerOperatorsAndTrivia(TestRegistry registry) {
        registry.test("operators are lexed longest-match", () -> {
            Assert.equalList(
                    List.of(SyntaxKind.QUESTION_QUESTION_EQUALS, SyntaxKind.EQUALS_GREATER_THAN,
                            SyntaxKind.LESS_THAN_LESS_THAN_EQUALS, SyntaxKind.DOT_DOT,
                            SyntaxKind.COLON_COLON, SyntaxKind.MINUS_GREATER_THAN,
                            SyntaxKind.END_OF_FILE),
                    kinds("??= => <<= .. :: ->"), "three- and two-character operators");
        });

        registry.test("consecutive greater-than signs stay separate tokens", () -> {
            // `List<List<int>>` must close two type argument lists, so the lexer never
            // forms `>>`; the parser merges the pair only where it wants a shift.
            List<SyntaxToken> tokens = lex("a >> b");
            Assert.equal(SyntaxKind.GREATER_THAN, tokens.get(1).kind(), "first");
            Assert.equal(SyntaxKind.GREATER_THAN, tokens.get(2).kind(), "second");
            Assert.isTrue(SyntaxFacts.mergesIntoShift(tokens.get(1), tokens.get(2)),
                    "adjacent pair merges");
            List<SyntaxToken> spaced = lex("a > > b");
            Assert.isFalse(SyntaxFacts.mergesIntoShift(spaced.get(1), spaced.get(2)),
                    "a separated pair is not a shift operator");
        });

        registry.test("question mark disambiguates against a leading-dot real", () -> {
            Assert.equalList(
                    List.of(SyntaxKind.IDENTIFIER, SyntaxKind.QUESTION_DOT,
                            SyntaxKind.IDENTIFIER, SyntaxKind.END_OF_FILE),
                    kinds("a?.b"), "null-conditional access");
            Assert.equalList(
                    List.of(SyntaxKind.IDENTIFIER, SyntaxKind.QUESTION,
                            SyntaxKind.REAL_LITERAL, SyntaxKind.COLON,
                            SyntaxKind.REAL_LITERAL, SyntaxKind.END_OF_FILE),
                    kinds("a?.5:.5"), "conditional over leading-dot reals");
        });

        registry.test("comments and whitespace are trivia", () -> {
            Assert.equalList(
                    List.of(SyntaxKind.IDENTIFIER, SyntaxKind.IDENTIFIER,
                            SyntaxKind.END_OF_FILE),
                    kinds("a // comment\n/* block\n comment */ b"), "both comment forms");
        });

        registry.test("start-of-line is tracked for directives", () -> {
            List<SyntaxToken> tokens = lex("a\n  b c");
            Assert.isTrue(tokens.get(0).atStartOfLine(), "first token on line one");
            Assert.isTrue(tokens.get(1).atStartOfLine(), "indentation still starts the line");
            Assert.isFalse(tokens.get(2).atStartOfLine(), "third token follows another token");
        });

        registry.test("spans cover the exact source text", () -> {
            List<SyntaxToken> tokens = lex("  abc  ");
            Assert.equal(2L, (long) tokens.getFirst().span().start(), "start after whitespace");
            Assert.equal(5L, (long) tokens.getFirst().span().end(), "end before whitespace");
            Assert.equal(7L, (long) tokens.get(1).span().start(), "end of file sits at the end");
        });
    }

    // ---- Recovery and determinism ------------------------------------------------------

    private static void registerRecovery(TestRegistry registry) {
        registry.test("an unexpected character is reported and skipped", () -> {
            DiagnosticBag bag = new DiagnosticBag();
            List<SyntaxToken> tokens = Lexer.tokenize(source("a § b"), bag);
            Assert.equal("VS1056", bag.all().getFirst().code().toString(), "code");
            Assert.equalList(
                    List.of(SyntaxKind.IDENTIFIER, SyntaxKind.BAD_TOKEN, SyntaxKind.IDENTIFIER,
                            SyntaxKind.END_OF_FILE),
                    tokens.stream().map(SyntaxToken::kind).toList(),
                    "lexing continues past the bad character");
        });

        registry.test("an unterminated block comment is reported once", () -> {
            Assert.equalList(List.of("VS1035"), errors("a /* b"), "one diagnostic");
        });

        registry.test("lexing is deterministic", () -> {
            String text = "int x = 1; $\"{x,3:F2}\" // note\n";
            Assert.equal(lex(text).toString(), lex(text).toString(),
                    "identical input yields identical tokens");
        });

        registry.test("every token of a realistic file is accounted for", () -> {
            String program = """
                    using System;

                    static int Add(int a, int b) => a + b;

                    var total = Add(1, 2);
                    Console.WriteLine($"total = {total}");
                    """;
            List<SyntaxToken> tokens = lex(program);
            Assert.equal(SyntaxKind.END_OF_FILE, tokens.getLast().kind(), "terminated");
            int covered = tokens.stream().mapToInt(token -> token.span().length()).sum();
            Assert.isTrue(covered > 0 && covered < program.length(),
                    "tokens cover the source minus trivia");
        });
    }
}
