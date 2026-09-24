package vsharp.compiler.syntax;

import java.util.List;
import java.util.Set;

/// Lexical predicates shared by the lexer and the parser.
public final class SyntaxFacts {

    /// Words the grammar treats as keywords only in specific positions.
    ///
    /// They are lexed as [SyntaxKind#IDENTIFIER] so that a program declaring a variable
    /// named `value`, `record` or `from` keeps compiling, exactly as C# guarantees. The
    /// parser consults this set at the positions where the grammar makes them special.
    private static final Set<String> CONTEXTUAL_KEYWORDS = Set.of(
            "add", "alias", "allows", "and", "args", "ascending", "async", "await", "by",
            "descending", "dynamic", "equals", "file", "from", "get", "global", "group",
            "init", "into", "join", "let", "managed", "nameof", "nint", "not", "notnull",
            "nuint", "on", "or", "orderby", "partial", "record", "remove", "required",
            "scoped", "select", "set", "unmanaged", "value", "var", "when", "where",
            "with", "yield");

    /// U+000B VERTICAL TAB, whitespace in C# but not in Java's Character#isWhitespace
    /// contract for our purposes; spelled out so no literal control character appears
    /// in this source file.
    private static final char VERTICAL_TAB = 0x000B;

    /// U+000C FORM FEED.
    private static final char FORM_FEED = 0x000C;

    private SyntaxFacts() {
        throw new AssertionError("No instances");
    }

    /// Whether `text` is a contextual keyword in some position.
    public static boolean isContextualKeyword(String text) {
        return CONTEXTUAL_KEYWORDS.contains(text);
    }

    /// Every contextual keyword, sorted, as an immutable list.
    ///
    /// Exposed so that tooling - completion in the language server, the editor grammar
    /// generator - can enumerate the set instead of restating it. A second copy of this
    /// list somewhere else would silently rot the first time the grammar grows a word.
    public static List<String> contextualKeywords() {
        return CONTEXTUAL_KEYWORDS.stream().sorted().toList();
    }

    /// Whether `c` can start a C# identifier.
    ///
    /// The C# rule is `letter_character | '_'`, where a letter character is any of the
    /// Unicode categories Lu, Ll, Lt, Lm, Lo and Nl. Java's own identifier predicates
    /// admit `$` and currency symbols, so they cannot be reused.
    public static boolean isIdentifierStart(int c) {
        if (c == '_') {
            return true;
        }
        return switch (Character.getType(c)) {
            case Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER,
                    Character.TITLECASE_LETTER, Character.MODIFIER_LETTER,
                    Character.OTHER_LETTER, Character.LETTER_NUMBER -> true;
            default -> false;
        };
    }

    /// Whether `c` can continue a C# identifier.
    ///
    /// Adds the combining marks (Mn, Mc), decimal digits (Nd), connectors (Pc) and format
    /// characters (Cf) to the starting set.
    public static boolean isIdentifierPart(int c) {
        if (isIdentifierStart(c)) {
            return true;
        }
        return switch (Character.getType(c)) {
            case Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK,
                    Character.DECIMAL_DIGIT_NUMBER, Character.CONNECTOR_PUNCTUATION,
                    Character.FORMAT -> true;
            default -> false;
        };
    }

    /// Whether `c` is whitespace under the C# lexical grammar.
    ///
    /// Line terminators are excluded: they are handled separately because they end
    /// single-line comments and delimit preprocessing directives.
    public static boolean isWhitespace(char c) {
        return c == ' ' || c == '\t' || c == VERTICAL_TAB || c == FORM_FEED
                || (c > 127 && Character.getType(c) == Character.SPACE_SEPARATOR);
    }

    /// Whether two adjacent `>` tokens may be merged into a shift operator.
    ///
    /// The lexer never produces `>>`, because `List<List<int>>` must close two type
    /// argument lists. The parser merges the tokens only where it wants an operator, and
    /// only when they are physically adjacent, so `a > > b` stays a syntax error.
    public static boolean mergesIntoShift(SyntaxToken left, SyntaxToken right) {
        return left.is(SyntaxKind.GREATER_THAN)
                && right.is(SyntaxKind.GREATER_THAN)
                && left.span().end() == right.span().start();
    }
}
