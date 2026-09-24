package vsharp.compiler.syntax;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/// Every token kind the V# lexer can produce.
///
/// Reserved keywords get their own kind because the grammar can never see them as names.
/// Contextual keywords deliberately do not: C# lexes `var`, `record` or `when` as ordinary
/// identifiers and lets the parser decide, and a program using `var` as a variable name
/// must keep working. [SyntaxFacts] holds the contextual set for the parser to consult.
public enum SyntaxKind {

    // ---- Markers -------------------------------------------------------------------

    /// A token the lexer could not classify; already reported as a diagnostic.
    BAD_TOKEN(null, Category.MARKER),

    /// Synthesised by error recovery to stand in for a token the parser required.
    MISSING_TOKEN(null, Category.MARKER),

    /// End of the source file. Always the last token of a lex.
    END_OF_FILE(null, Category.MARKER),

    // ---- Names and literals --------------------------------------------------------

    IDENTIFIER(null, Category.IDENTIFIER),
    INTEGER_LITERAL(null, Category.LITERAL),
    REAL_LITERAL(null, Category.LITERAL),
    CHARACTER_LITERAL(null, Category.LITERAL),
    STRING_LITERAL(null, Category.LITERAL),
    UTF8_STRING_LITERAL(null, Category.LITERAL),
    INTERPOLATED_STRING(null, Category.LITERAL),

    // ---- Parser-only contextual kinds ----------------------------------------------
    // The lexer emits IDENTIFIER for these spellings. The parser uses the distinct kinds
    // in syntax nodes where the grammar makes the contextual meaning unambiguous.

    VAR("var", Category.IDENTIFIER), DYNAMIC("dynamic", Category.IDENTIFIER),
    NINT("nint", Category.IDENTIFIER), NUINT("nuint", Category.IDENTIFIER),
    PARTIAL("partial", Category.IDENTIFIER), FILE("file", Category.IDENTIFIER),
    SCOPED("scoped", Category.IDENTIFIER), ASYNC("async", Category.IDENTIFIER),
    AWAIT("await", Category.IDENTIFIER),

    // ---- Reserved keywords ---------------------------------------------------------

    ABSTRACT("abstract"), AS("as"), BASE("base"), BOOL("bool"), BREAK("break"),
    BYTE("byte"), CASE("case"), CATCH("catch"), CHAR("char"), CHECKED("checked"),
    CLASS("class"), CONST("const"), CONTINUE("continue"), DECIMAL("decimal"),
    DEFAULT("default"), DELEGATE("delegate"), DO("do"), DOUBLE("double"), ELSE("else"),
    ENUM("enum"), EVENT("event"), EXPLICIT("explicit"), EXTERN("extern"), FALSE("false"),
    FINALLY("finally"), FIXED("fixed"), FLOAT("float"), FOR("for"), FOREACH("foreach"),
    GOTO("goto"), IF("if"), IMPLICIT("implicit"), IN("in"), INT("int"),
    INTERFACE("interface"), INTERNAL("internal"), IS("is"), LOCK("lock"), LONG("long"),
    NAMESPACE("namespace"), NEW("new"), NULL("null"), OBJECT("object"),
    OPERATOR("operator"), OUT("out"), OVERRIDE("override"), PARAMS("params"),
    PRIVATE("private"), PROTECTED("protected"), PUBLIC("public"), READONLY("readonly"),
    REF("ref"), RETURN("return"), SBYTE("sbyte"), SEALED("sealed"), SHORT("short"),
    SIZEOF("sizeof"), STACKALLOC("stackalloc"), STATIC("static"), STRING("string"),
    STRUCT("struct"), SWITCH("switch"), THIS("this"), THROW("throw"), TRUE("true"),
    TRY("try"), TYPEOF("typeof"), UINT("uint"), ULONG("ulong"), UNCHECKED("unchecked"),
    UNSAFE("unsafe"), USHORT("ushort"), USING("using"), VIRTUAL("virtual"), VOID("void"),
    VOLATILE("volatile"), WHILE("while"),

    // ---- Punctuators and operators -------------------------------------------------
    // Ordering inside each length group is irrelevant; the lexer matches longest-first
    // by explicit lookahead, not by scanning this table.

    OPEN_BRACE("{", Category.PUNCTUATOR), CLOSE_BRACE("}", Category.PUNCTUATOR),
    OPEN_BRACKET("[", Category.PUNCTUATOR), CLOSE_BRACKET("]", Category.PUNCTUATOR),
    OPEN_PAREN("(", Category.PUNCTUATOR), CLOSE_PAREN(")", Category.PUNCTUATOR),
    DOT(".", Category.PUNCTUATOR), DOT_DOT("..", Category.PUNCTUATOR),
    COMMA(",", Category.PUNCTUATOR), COLON(":", Category.PUNCTUATOR),
    COLON_COLON("::", Category.PUNCTUATOR), SEMICOLON(";", Category.PUNCTUATOR),
    PLUS("+", Category.PUNCTUATOR), MINUS("-", Category.PUNCTUATOR),
    ASTERISK("*", Category.PUNCTUATOR), SLASH("/", Category.PUNCTUATOR),
    PERCENT("%", Category.PUNCTUATOR), AMPERSAND("&", Category.PUNCTUATOR),
    BAR("|", Category.PUNCTUATOR), CARET("^", Category.PUNCTUATOR),
    EXCLAMATION("!", Category.PUNCTUATOR), TILDE("~", Category.PUNCTUATOR),
    EQUALS("=", Category.PUNCTUATOR), LESS_THAN("<", Category.PUNCTUATOR),
    GREATER_THAN(">", Category.PUNCTUATOR), QUESTION("?", Category.PUNCTUATOR),
    QUESTION_QUESTION("??", Category.PUNCTUATOR), QUESTION_DOT("?.", Category.PUNCTUATOR),
    PLUS_PLUS("++", Category.PUNCTUATOR), MINUS_MINUS("--", Category.PUNCTUATOR),
    AMPERSAND_AMPERSAND("&&", Category.PUNCTUATOR), BAR_BAR("||", Category.PUNCTUATOR),
    MINUS_GREATER_THAN("->", Category.PUNCTUATOR),
    EQUALS_EQUALS("==", Category.PUNCTUATOR), EXCLAMATION_EQUALS("!=", Category.PUNCTUATOR),
    LESS_THAN_EQUALS("<=", Category.PUNCTUATOR),
    GREATER_THAN_EQUALS(">=", Category.PUNCTUATOR),
    EQUALS_GREATER_THAN("=>", Category.PUNCTUATOR),
    PLUS_EQUALS("+=", Category.PUNCTUATOR), MINUS_EQUALS("-=", Category.PUNCTUATOR),
    ASTERISK_EQUALS("*=", Category.PUNCTUATOR), SLASH_EQUALS("/=", Category.PUNCTUATOR),
    PERCENT_EQUALS("%=", Category.PUNCTUATOR), AMPERSAND_EQUALS("&=", Category.PUNCTUATOR),
    BAR_EQUALS("|=", Category.PUNCTUATOR), CARET_EQUALS("^=", Category.PUNCTUATOR),
    QUESTION_QUESTION_EQUALS("??=", Category.PUNCTUATOR),
    LESS_THAN_LESS_THAN("<<", Category.PUNCTUATOR),
    LESS_THAN_LESS_THAN_EQUALS("<<=", Category.PUNCTUATOR),
    // Parser-only composite kinds. The lexer still emits each `>` separately so nested
    // generic argument lists remain unambiguous; Parser synthesises these operator kinds.
    GREATER_THAN_GREATER_THAN(">>", Category.PUNCTUATOR),
    GREATER_THAN_GREATER_THAN_EQUALS(">>=", Category.PUNCTUATOR),
    GREATER_THAN_GREATER_THAN_GREATER_THAN(">>>", Category.PUNCTUATOR),
    GREATER_THAN_GREATER_THAN_GREATER_THAN_EQUALS(">>>=", Category.PUNCTUATOR),
    HASH("#", Category.PUNCTUATOR);

    // `>>`, `>>>` and their compound forms are deliberately never emitted by the lexer:
    // C# lexes consecutive `>` characters separately so that `List<List<int>>` closes two
    // type arguments. The parser uses the composite kinds above in syntax nodes only.

    /// Broad classification, used by diagnostics and by the parser's token predicates.
    public enum Category {
        /// Not a real token: end of file, or a lexer/parser error placeholder.
        MARKER,
        /// A name.
        IDENTIFIER,
        /// A literal value.
        LITERAL,
        /// A reserved word that can never be a name.
        KEYWORD,
        /// An operator or punctuator.
        PUNCTUATOR
    }

    private static final Map<String, SyntaxKind> KEYWORDS = Stream.of(values())
            .filter(kind -> kind.category == Category.KEYWORD)
            .collect(Collectors.toUnmodifiableMap(kind -> kind.text, kind -> kind));

    private final String text;
    private final Category category;

    SyntaxKind(String text, Category category) {
        this.text = text;
        this.category = category;
    }

    SyntaxKind(String text) {
        this(text, Category.KEYWORD);
    }

    /// The fixed spelling of this kind, for keywords and punctuators.
    ///
    /// Empty for kinds whose text varies, such as identifiers and literals.
    public Optional<String> text() {
        return Optional.ofNullable(text);
    }

    /// The fixed spelling, or the enum name when the kind has no fixed spelling.
    ///
    /// Used in diagnostic messages, where `';' expected` must never read as `SEMICOLON`.
    public String display() {
        return text != null ? text : name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    }

    /// Broad classification of this kind.
    public Category category() {
        return category;
    }

    /// Whether this kind is a reserved keyword.
    public boolean isKeyword() {
        return category == Category.KEYWORD;
    }

    /// The reserved keyword spelled `text`, if there is one.
    public static Optional<SyntaxKind> keyword(String text) {
        return Optional.ofNullable(KEYWORDS.get(text));
    }
}
