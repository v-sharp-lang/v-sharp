package vsharp.compiler.syntax;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import vsharp.compiler.diagnostics.DiagnosticBag;
import vsharp.compiler.diagnostics.DiagnosticCode;
import vsharp.compiler.source.LineMap;
import vsharp.compiler.source.SourceFile;
import vsharp.compiler.source.SourceSpan;

/// The V# lexer: C# 13 lexical grammar over a flat character buffer.
///
/// The lexer never throws on malformed input. Every failure is reported to the
/// [DiagnosticBag] and the scan continues from a position guaranteed to advance, so a
/// single bad character cannot stop the compiler from reporting the rest of the file.
///
/// Two decisions are worth stating because they look like omissions:
///
///  - `>>` is never produced. Consecutive `>` characters close nested type argument lists
///    far more often than they shift, so the parser merges them where it wants an
///    operator. See [SyntaxFacts#mergesIntoShift].
///  - Trivia is discarded. Only [SyntaxToken#atStartOfLine] survives, which is all the
///    parser and the directive handling need.
public final class Lexer {

    /// C# permits at most four hexadecimal digits in a `\x` escape.
    private static final int MAX_HEX_ESCAPE_DIGITS = 4;

    private static final BigInteger INT_MAX = BigInteger.valueOf(Integer.MAX_VALUE);
    private static final BigInteger UINT_MAX = BigInteger.valueOf(0xFFFF_FFFFL);
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);
    private static final BigInteger ULONG_MAX = new BigInteger("18446744073709551615");

    private final SourceFile file;
    private final String text;
    private final DiagnosticBag diagnostics;
    private final int limit;

    private int position;
    private boolean atStartOfLine = true;

    private Lexer(SourceFile file, DiagnosticBag diagnostics) {
        this(file, file.text(), diagnostics, 0, file.length(), true);
    }

    private Lexer(SourceFile file, String scanningText, DiagnosticBag diagnostics, int start,
            int limit, boolean startsLine) {
        this.file = Objects.requireNonNull(file, "file");
        this.text = Objects.requireNonNull(scanningText, "scanningText");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        if (text.length() != file.length()) {
            throw new IllegalArgumentException("scanning text must preserve source length");
        }
        if (start < 0 || limit < start || limit > file.length()) {
            throw new IllegalArgumentException("invalid lexical range [" + start + ".."
                    + limit + ") for source of length " + text.length());
        }
        this.position = start;
        this.limit = limit;
        this.atStartOfLine = startsLine;
    }

    /// Lexes an entire file.
    ///
    /// The returned list always ends with an [SyntaxKind#END_OF_FILE] token, so the parser
    /// never has to bounds-check its lookahead.
    ///
    /// @param file        the source to lex
    /// @param diagnostics the bag receiving every lexical error
    /// @return the tokens, in source order
    public static List<SyntaxToken> tokenize(SourceFile file, DiagnosticBag diagnostics) {
        Lexer lexer = new Lexer(file, diagnostics);
        return lexer.tokenizeRange();
    }

    /// Lexes a same-length masked view while diagnostics continue to reference `file`.
    /// Used by preprocessing: inactive code and directive lines are spaces in the view,
    /// preserving every source offset and line terminator from the original file.
    static List<SyntaxToken> tokenizeMasked(SourceFile file, String maskedText,
            DiagnosticBag diagnostics) {
        Lexer lexer = new Lexer(file, maskedText, diagnostics, 0, file.length(), true);
        return lexer.tokenizeRange();
    }

    /// Lexes one source fragment while retaining absolute source offsets.
    ///
    /// Interpolated-string holes are already delimited by the outer lexical pass. The
    /// parser re-enters the lexer through this method so diagnostics inside a hole point
    /// into the original file instead of into an artificial substring.
    public static List<SyntaxToken> tokenizeFragment(SourceFile file, SourceSpan fragment,
            DiagnosticBag diagnostics) {
        Objects.requireNonNull(fragment, "fragment");
        Lexer lexer = new Lexer(file, file.text(), diagnostics, fragment.start(), fragment.end(),
                false);
        return lexer.tokenizeRange();
    }

    private List<SyntaxToken> tokenizeRange() {
        List<SyntaxToken> tokens = new ArrayList<>();
        while (true) {
            skipTrivia();
            boolean startsLine = atStartOfLine;
            atStartOfLine = false;
            int start = position;
            if (atEnd()) {
                tokens.add(SyntaxToken.of(SyntaxKind.END_OF_FILE, SourceSpan.at(start), "",
                        startsLine));
                return List.copyOf(tokens);
            }
            tokens.add(scanToken(start, startsLine));
        }
    }

    // ---- Character access --------------------------------------------------------------

    private boolean atEnd() {
        return position >= limit;
    }

    private char current() {
        return position < limit ? text.charAt(position) : '\0';
    }

    private char peek(int offset) {
        int index = position + offset;
        return index < limit ? text.charAt(index) : '\0';
    }

    private char advance() {
        return text.charAt(position++);
    }

    private boolean match(char expected) {
        if (current() == expected && !atEnd()) {
            position++;
            return true;
        }
        return false;
    }

    private SourceSpan spanFrom(int start) {
        return SourceSpan.between(start, position);
    }

    private String textFrom(int start) {
        return text.substring(start, position);
    }

    private void report(DiagnosticCode code, int start, Object... arguments) {
        diagnostics.report(code, file, spanFrom(start), arguments);
    }

    private void reportAt(DiagnosticCode code, SourceSpan span, Object... arguments) {
        diagnostics.report(code, file, span, arguments);
    }

    // ---- Trivia ------------------------------------------------------------------------

    /// Consumes whitespace, line terminators and comments, tracking line starts.
    private void skipTrivia() {
        while (!atEnd()) {
            char c = current();
            if (LineMap.isLineTerminator(c)) {
                position++;
                if (c == '\r' && current() == '\n') {
                    position++;
                }
                atStartOfLine = true;
            } else if (SyntaxFacts.isWhitespace(c)) {
                position++;
            } else if (c == '/' && peek(1) == '/') {
                while (!atEnd() && !LineMap.isLineTerminator(current())) {
                    position++;
                }
            } else if (c == '/' && peek(1) == '*') {
                skipDelimitedComment();
            } else {
                return;
            }
        }
    }

    private void skipDelimitedComment() {
        int start = position;
        position += 2;
        while (!atEnd()) {
            if (current() == '*' && peek(1) == '/') {
                position += 2;
                return;
            }
            if (LineMap.isLineTerminator(current())) {
                atStartOfLine = true;
            }
            position++;
        }
        // The comment swallowed the rest of the file; point the caret at its opening.
        reportAt(DiagnosticCode.UNTERMINATED_COMMENT, SourceSpan.between(start, start + 2));
    }

    // ---- Dispatch ----------------------------------------------------------------------

    private SyntaxToken scanToken(int start, boolean startsLine) {
        char c = current();
        if (c == '@' && (peek(1) == '"' || (peek(1) == '$' && peek(2) == '"'))) {
            return scanVerbatimForms(start, startsLine);
        }
        if (c == '@' || c == '\\' || SyntaxFacts.isIdentifierStart(c)) {
            return scanIdentifierOrKeyword(start, startsLine);
        }
        if (isDigit(c) || (c == '.' && isDigit(peek(1)))) {
            return scanNumber(start, startsLine);
        }
        if (c == '\'') {
            return scanCharacterLiteral(start, startsLine);
        }
        if (c == '"') {
            return scanStringLiteral(start, startsLine, false);
        }
        if (c == '$') {
            return scanInterpolatedForms(start, startsLine);
        }
        return scanPunctuator(start, startsLine);
    }

    private SyntaxToken scanVerbatimForms(int start, boolean startsLine) {
        position++; // '@'
        if (current() == '$') {
            position++;
            return scanInterpolatedString(start, startsLine, true);
        }
        return scanVerbatimString(start, startsLine);
    }

    private SyntaxToken scanInterpolatedForms(int start, boolean startsLine) {
        int dollars = 0;
        while (current() == '$') {
            dollars++;
            position++;
        }
        if (current() == '@') {
            position++;
            return scanInterpolatedString(start, startsLine, true);
        }
        if (current() == '"' && peek(1) == '"' && peek(2) == '"') {
            return scanInterpolatedRawString(start, startsLine, dollars);
        }
        if (current() != '"') {
            report(DiagnosticCode.UNEXPECTED_CHARACTER, start, "$");
            return SyntaxToken.of(SyntaxKind.BAD_TOKEN, spanFrom(start), textFrom(start),
                    startsLine);
        }
        if (dollars > 1) {
            report(DiagnosticCode.MALFORMED_INTERPOLATION, start,
                    "multiple '$' are only allowed on raw string literals");
        }
        return scanInterpolatedString(start, startsLine, false);
    }

    // ---- Identifiers -------------------------------------------------------------------

    private SyntaxToken scanIdentifierOrKeyword(int start, boolean startsLine) {
        boolean verbatim = current() == '@';
        if (verbatim) {
            position++;
        }
        StringBuilder name = new StringBuilder();
        boolean first = true;
        while (!atEnd()) {
            char c = current();
            if (c == '\\' && (peek(1) == 'u' || peek(1) == 'U')) {
                int escapeStart = position;
                int codePoint = scanUnicodeEscapeValue();
                boolean acceptable = first
                        ? SyntaxFacts.isIdentifierStart(codePoint)
                        : SyntaxFacts.isIdentifierPart(codePoint);
                if (!acceptable) {
                    reportAt(DiagnosticCode.INVALID_IDENTIFIER_ESCAPE,
                            SourceSpan.between(escapeStart, position),
                            text.substring(escapeStart, position));
                } else {
                    name.appendCodePoint(codePoint);
                }
                first = false;
                continue;
            }
            int codePoint = text.codePointAt(position);
            boolean acceptable = first
                    ? SyntaxFacts.isIdentifierStart(codePoint)
                    : SyntaxFacts.isIdentifierPart(codePoint);
            if (!acceptable) {
                break;
            }
            name.appendCodePoint(codePoint);
            position += Character.charCount(codePoint);
            first = false;
        }
        if (name.isEmpty()) {
            // A lone `@` or a `\` that began no escape: consume one character so the scan
            // is guaranteed to progress.
            position = Math.max(position + 1, start + 1);
            report(DiagnosticCode.UNEXPECTED_CHARACTER, start, text.charAt(start));
            return SyntaxToken.of(SyntaxKind.BAD_TOKEN, spanFrom(start), textFrom(start),
                    startsLine);
        }
        String spelling = name.toString();
        SourceSpan span = spanFrom(start);
        if (!verbatim) {
            // `@if` is the identifier `if`; only a bare keyword is reserved.
            var keyword = SyntaxKind.keyword(spelling);
            if (keyword.isPresent()) {
                SyntaxKind kind = keyword.get();
                return switch (kind) {
                    case TRUE -> SyntaxToken.literal(kind, span, spelling, LiteralType.BOOL,
                            Boolean.TRUE, startsLine);
                    case FALSE -> SyntaxToken.literal(kind, span, spelling, LiteralType.BOOL,
                            Boolean.FALSE, startsLine);
                    case NULL -> SyntaxToken.literal(kind, span, spelling, LiteralType.NULL,
                            null, startsLine);
                    default -> SyntaxToken.of(kind, span, spelling, startsLine);
                };
            }
        }
        return SyntaxToken.literal(SyntaxKind.IDENTIFIER, span, spelling, LiteralType.NONE,
                null, startsLine);
    }

    /// Decodes a backslash-u escape (four hex digits) or backslash-U escape (eight)
    /// starting at the backslash, returning the code point.
    ///
    /// Returns a value that fails every identifier and character test when the escape is
    /// malformed, after reporting it, so callers need no second error path.
    private int scanUnicodeEscapeValue() {
        int start = position;
        position++; // backslash
        boolean longForm = current() == 'U';
        position++; // u or U
        int digits = longForm ? 8 : 4;
        int value = 0;
        int seen = 0;
        while (seen < digits && isHexDigit(current())) {
            value = (value << 4) + hexValue(current());
            position++;
            seen++;
        }
        if (seen != digits || !Character.isValidCodePoint(value)) {
            reportAt(DiagnosticCode.UNRECOGNIZED_ESCAPE, SourceSpan.between(start, position));
            return -1;
        }
        return value;
    }

    // ---- Numbers -----------------------------------------------------------------------

    private SyntaxToken scanNumber(int start, boolean startsLine) {
        if (current() == '0' && (peek(1) == 'x' || peek(1) == 'X')) {
            return scanIntegerWithRadix(start, startsLine, 16);
        }
        if (current() == '0' && (peek(1) == 'b' || peek(1) == 'B')) {
            return scanIntegerWithRadix(start, startsLine, 2);
        }
        return scanDecimalNumber(start, startsLine);
    }

    private SyntaxToken scanIntegerWithRadix(int start, boolean startsLine, int radix) {
        position += 2; // 0x / 0b
        StringBuilder digits = new StringBuilder();
        boolean lastWasSeparator = false;
        while (!atEnd()) {
            char c = current();
            if (c == '_') {
                lastWasSeparator = true;
                position++;
            } else if (radix == 16 ? isHexDigit(c) : (c == '0' || c == '1')) {
                digits.append(c);
                lastWasSeparator = false;
                position++;
            } else {
                break;
            }
        }
        if (digits.isEmpty() || lastWasSeparator) {
            scanLiteralSuffix(); // consumed so the next token starts cleanly
            report(DiagnosticCode.INVALID_NUMERIC_LITERAL, start, textFrom(start));
            return SyntaxToken.literal(SyntaxKind.INTEGER_LITERAL, spanFrom(start),
                    textFrom(start), LiteralType.INT, Integer.valueOf(0), startsLine);
        }
        BigInteger value = new BigInteger(digits.toString(), radix);
        String suffix = scanLiteralSuffix();
        return integerToken(start, startsLine, value, suffix, realTypeFor(suffix, start));
    }

    private SyntaxToken scanDecimalNumber(int start, boolean startsLine) {
        StringBuilder core = new StringBuilder();
        boolean real = false;
        scanDigitRun(core, start);
        // A `.` joins the literal only when a digit follows: `1.ToString()` and `1..2` must
        // both keep the dot as its own token.
        if (current() == '.' && isDigit(peek(1))) {
            real = true;
            core.append('.');
            position++;
            scanDigitRun(core, start);
        }
        if (current() == 'e' || current() == 'E') {
            real = true;
            core.append('e');
            position++;
            if (current() == '+' || current() == '-') {
                core.append(advance());
            }
            int before = core.length();
            scanDigitRun(core, start);
            if (core.length() == before) {
                report(DiagnosticCode.INVALID_NUMERIC_LITERAL, start, textFrom(start));
                core.append('0');
            }
        }
        String suffix = scanLiteralSuffix();
        String digits = core.toString();
        LiteralType realType = realTypeFor(suffix, start);
        if (real || realType != null) {
            return realToken(start, startsLine, digits, realType == null
                    ? LiteralType.DOUBLE
                    : realType);
        }
        return integerToken(start, startsLine, new BigInteger(digits), suffix, realType);
    }

    private void scanDigitRun(StringBuilder out, int literalStart) {
        boolean lastWasSeparator = false;
        boolean any = false;
        while (!atEnd()) {
            char c = current();
            if (c == '_') {
                lastWasSeparator = true;
                position++;
            } else if (isDigit(c)) {
                out.append(c);
                any = true;
                lastWasSeparator = false;
                position++;
            } else {
                break;
            }
        }
        if (any && lastWasSeparator) {
            report(DiagnosticCode.INVALID_NUMERIC_LITERAL, literalStart, textFrom(literalStart));
        }
    }

    /// Consumes the trailing letters of a numeric literal, which may be a type suffix.
    private String scanLiteralSuffix() {
        int start = position;
        while (!atEnd() && SyntaxFacts.isIdentifierPart(current())) {
            position++;
        }
        return text.substring(start, position);
    }

    /// Maps a suffix to a floating-point type, or `null` when the suffix is not one.
    private LiteralType realTypeFor(String suffix, int start) {
        return switch (suffix) {
            case "" -> null;
            case "f", "F" -> LiteralType.FLOAT;
            case "d", "D" -> LiteralType.DOUBLE;
            case "m", "M" -> LiteralType.DECIMAL;
            case "u", "U", "l", "L", "ul", "uL", "Ul", "UL", "lu", "lU", "Lu", "LU" -> null;
            default -> {
                report(DiagnosticCode.INVALID_NUMERIC_SUFFIX, start, suffix);
                yield null;
            }
        };
    }

    private SyntaxToken realToken(int start, boolean startsLine, String digits,
            LiteralType type) {
        String literal = textFrom(start);
        Object value = switch (type) {
            case FLOAT -> Float.valueOf(Float.parseFloat(digits));
            case DECIMAL -> new BigDecimal(digits);
            default -> Double.valueOf(Double.parseDouble(digits));
        };
        return SyntaxToken.literal(SyntaxKind.REAL_LITERAL, spanFrom(start), literal, type,
                value, startsLine);
    }

    private SyntaxToken integerToken(int start, boolean startsLine, BigInteger value,
            String suffix, LiteralType realType) {
        if (realType != null) {
            // `1f`, `2m`: an integral spelling with a floating-point suffix.
            return realToken(start, startsLine, value.toString(), realType);
        }
        boolean unsignedSuffix = suffix.toLowerCase(java.util.Locale.ROOT).contains("u");
        boolean longSuffix = suffix.toLowerCase(java.util.Locale.ROOT).contains("l");
        LiteralType type = classifyInteger(value, unsignedSuffix, longSuffix, start);
        Object boxed = switch (type) {
            case INT, UINT -> Integer.valueOf(value.intValue());
            default -> Long.valueOf(value.longValue());
        };
        return SyntaxToken.literal(SyntaxKind.INTEGER_LITERAL, spanFrom(start), textFrom(start),
                type, boxed, startsLine);
    }

    /// Applies C#'s literal typing ladder.
    ///
    /// Unsuffixed literals take the first of `int`, `uint`, `long`, `ulong` that fits; a
    /// `U` suffix removes the signed candidates and an `L` suffix removes the 32-bit ones.
    /// Note that `-2147483648` is a unary minus applied to a literal that does not fit
    /// `int`; the parser folds that pair, so the ladder here is deliberately sign-blind.
    private LiteralType classifyInteger(BigInteger value, boolean unsigned, boolean isLong,
            int start) {
        if (value.compareTo(ULONG_MAX) > 0) {
            report(DiagnosticCode.NUMERIC_LITERAL_OVERFLOW, start, "ulong");
            return LiteralType.ULONG;
        }
        if (!unsigned && !isLong) {
            if (value.compareTo(INT_MAX) <= 0) {
                return LiteralType.INT;
            }
            if (value.compareTo(UINT_MAX) <= 0) {
                return LiteralType.UINT;
            }
            return value.compareTo(LONG_MAX) <= 0 ? LiteralType.LONG : LiteralType.ULONG;
        }
        if (unsigned && !isLong) {
            return value.compareTo(UINT_MAX) <= 0 ? LiteralType.UINT : LiteralType.ULONG;
        }
        if (!unsigned) {
            return value.compareTo(LONG_MAX) <= 0 ? LiteralType.LONG : LiteralType.ULONG;
        }
        return LiteralType.ULONG;
    }

    // ---- Character and string literals -------------------------------------------------

    private SyntaxToken scanCharacterLiteral(int start, boolean startsLine) {
        position++; // opening quote
        StringBuilder content = new StringBuilder();
        boolean closed = false;
        boolean badEscape = false;
        while (!atEnd()) {
            char c = current();
            if (c == '\'') {
                position++;
                closed = true;
                break;
            }
            if (LineMap.isLineTerminator(c)) {
                break;
            }
            if (c == '\\') {
                badEscape |= !decodeEscape(content);
            } else {
                content.append(advance());
            }
        }
        char value = '\0';
        if (!closed) {
            report(DiagnosticCode.NEWLINE_IN_LITERAL, start);
        } else if (badEscape) {
            value = content.isEmpty() ? '\0' : content.charAt(0);
        } else if (content.isEmpty()) {
            report(DiagnosticCode.EMPTY_CHARACTER_LITERAL, start);
        } else if (content.length() > 1) {
            // A `\U0001F600` escape yields a surrogate pair, which C# also rejects here.
            report(DiagnosticCode.TOO_MANY_CHARACTERS, start);
            value = content.charAt(0);
        } else {
            value = content.charAt(0);
        }
        return SyntaxToken.literal(SyntaxKind.CHARACTER_LITERAL, spanFrom(start),
                textFrom(start), LiteralType.CHAR, Character.valueOf(value), startsLine);
    }

    private SyntaxToken scanStringLiteral(int start, boolean startsLine, boolean rawOnly) {
        if (current() == '"' && peek(1) == '"' && peek(2) == '"') {
            return scanRawString(start, startsLine);
        }
        if (rawOnly) {
            throw new IllegalStateException("Raw string expected at " + start);
        }
        position++; // opening quote
        StringBuilder content = new StringBuilder();
        boolean closed = false;
        while (!atEnd()) {
            char c = current();
            if (c == '"') {
                position++;
                closed = true;
                break;
            }
            if (LineMap.isLineTerminator(c)) {
                break;
            }
            if (c == '\\') {
                decodeEscape(content);
            } else {
                content.append(advance());
            }
        }
        if (!closed) {
            report(atEnd() ? DiagnosticCode.UNTERMINATED_STRING
                    : DiagnosticCode.NEWLINE_IN_LITERAL, start);
        }
        return finishString(start, startsLine, content.toString());
    }

    private SyntaxToken scanVerbatimString(int start, boolean startsLine) {
        position++; // opening quote, the '@' is already consumed
        StringBuilder content = new StringBuilder();
        boolean closed = false;
        while (!atEnd()) {
            char c = current();
            if (c == '"') {
                if (peek(1) == '"') {
                    content.append('"');
                    position += 2;
                    continue;
                }
                position++;
                closed = true;
                break;
            }
            // Verbatim strings span lines and treat backslashes literally.
            content.append(advance());
        }
        if (!closed) {
            report(DiagnosticCode.UNTERMINATED_STRING, start);
        }
        return finishString(start, startsLine, content.toString());
    }

    /// Applies an optional `u8` suffix and builds the token.
    private SyntaxToken finishString(int start, boolean startsLine, String content) {
        if (current() == 'u' && peek(1) == '8'
                && !SyntaxFacts.isIdentifierPart(peek(2))) {
            position += 2;
            return SyntaxToken.literal(SyntaxKind.UTF8_STRING_LITERAL, spanFrom(start),
                    textFrom(start), LiteralType.UTF8,
                    content.getBytes(StandardCharsets.UTF_8), startsLine);
        }
        return SyntaxToken.literal(SyntaxKind.STRING_LITERAL, spanFrom(start), textFrom(start),
                LiteralType.STRING, content, startsLine);
    }

    // ---- Raw string literals -----------------------------------------------------------

    private SyntaxToken scanRawString(int start, boolean startsLine) {
        int quoteCount = 0;
        while (current() == '"') {
            quoteCount++;
            position++;
        }
        int contentStart = position;
        int contentEnd = -1;
        int closingRun = 0;
        while (!atEnd()) {
            if (current() != '"') {
                position++;
                continue;
            }
            int runStart = position;
            int run = 0;
            while (current() == '"') {
                run++;
                position++;
            }
            if (run >= quoteCount) {
                contentEnd = runStart;
                closingRun = run;
                break;
            }
        }
        if (contentEnd < 0) {
            report(DiagnosticCode.UNTERMINATED_RAW_STRING, start);
            return finishString(start, startsLine, text.substring(contentStart, position));
        }
        if (closingRun > quoteCount) {
            report(DiagnosticCode.RAW_STRING_EXTRA_QUOTES, start);
        }
        String raw = text.substring(contentStart, contentEnd);
        return finishString(start, startsLine, normalizeRawContent(raw, start, contentEnd));
    }

    /// Applies the C# 11 raw-string rules: single-line verbatim content, or multi-line
    /// content whose common indentation is defined by the closing delimiter's line.
    private String normalizeRawContent(String raw, int literalStart, int contentEnd) {
        int firstBreak = indexOfLineTerminator(raw, 0);
        if (firstBreak < 0) {
            return raw; // single-line form: content is taken exactly as written
        }
        if (!raw.substring(0, firstBreak).isBlank()) {
            reportAt(DiagnosticCode.RAW_STRING_DELIMITER_LINE,
                    SourceSpan.at(literalStart), "Opening");
        }
        List<String> lines = splitLines(raw);
        // The first line holds only the whitespace after the opening delimiter, and the
        // last holds the indentation of the closing delimiter; neither is content.
        String indentation = lines.removeLast();
        lines.removeFirst();
        if (!indentation.isBlank()) {
            reportAt(DiagnosticCode.RAW_STRING_DELIMITER_LINE, SourceSpan.at(contentEnd),
                    "Closing");
            indentation = "";
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (i > 0) {
                out.append('\n');
            }
            if (line.startsWith(indentation)) {
                out.append(line, indentation.length(), line.length());
            } else if (line.isBlank()) {
                out.append(line.strip());
            } else {
                reportAt(DiagnosticCode.RAW_STRING_INDENTATION, SourceSpan.at(literalStart));
                out.append(line.stripLeading());
            }
        }
        return out.toString();
    }

    private static int indexOfLineTerminator(String value, int from) {
        for (int i = from; i < value.length(); i++) {
            if (LineMap.isLineTerminator(value.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    /// Splits on every C# line terminator, keeping empty lines, without keeping terminators.
    private static List<String> splitLines(String value) {
        List<String> lines = new ArrayList<>();
        int lineStart = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!LineMap.isLineTerminator(c)) {
                continue;
            }
            lines.add(value.substring(lineStart, i));
            if (c == '\r' && i + 1 < value.length() && value.charAt(i + 1) == '\n') {
                i++;
            }
            lineStart = i + 1;
        }
        lines.add(value.substring(lineStart));
        return lines;
    }

    // ---- Interpolated strings ----------------------------------------------------------

    private SyntaxToken scanInterpolatedString(int start, boolean startsLine,
            boolean verbatim) {
        position++; // opening quote
        List<InterpolationPart> parts = new ArrayList<>();
        StringBuilder run = new StringBuilder();
        boolean closed = false;
        while (!atEnd()) {
            char c = current();
            if (c == '"') {
                if (verbatim && peek(1) == '"') {
                    run.append('"');
                    position += 2;
                    continue;
                }
                position++;
                closed = true;
                break;
            }
            if (!verbatim && LineMap.isLineTerminator(c)) {
                break;
            }
            if (c == '{') {
                if (peek(1) == '{') {
                    run.append('{');
                    position += 2;
                    continue;
                }
                if (!run.isEmpty()) {
                    parts.add(new InterpolationPart.Text(run.toString()));
                    run.setLength(0);
                }
                parts.add(scanInterpolationHole());
                continue;
            }
            if (c == '}') {
                if (peek(1) == '}') {
                    run.append('}');
                    position += 2;
                    continue;
                }
                reportAt(DiagnosticCode.UNESCAPED_INTERPOLATION_BRACE,
                        SourceSpan.between(position, position + 1), "}");
                position++;
                continue;
            }
            if (c == '\\' && !verbatim) {
                decodeEscape(run);
                continue;
            }
            run.append(advance());
        }
        if (!closed) {
            report(atEnd() ? DiagnosticCode.UNTERMINATED_STRING
                    : DiagnosticCode.NEWLINE_IN_LITERAL, start);
        }
        if (!run.isEmpty()) {
            parts.add(new InterpolationPart.Text(run.toString()));
        }
        return SyntaxToken.literal(SyntaxKind.INTERPOLATED_STRING, spanFrom(start),
                textFrom(start), LiteralType.INTERPOLATED, List.copyOf(parts), startsLine);
    }

    /// Scans `{expression[,alignment][:format]}`, leaving the expression unparsed.
    private InterpolationPart.Hole scanInterpolationHole() {
        int braceStart = position;
        position++; // '{'
        int expressionStart = position;
        int expressionEnd = scanHoleExpression();
        SourceSpan expression = SourceSpan.between(expressionStart, expressionEnd);
        if (expression.isEmpty()) {
            reportAt(DiagnosticCode.MALFORMED_INTERPOLATION,
                    SourceSpan.between(braceStart, Math.max(position, braceStart + 1)),
                    "the hole has no expression");
        }
        SourceSpan alignment = null;
        if (current() == ',') {
            position++;
            int alignmentStart = position;
            alignment = SourceSpan.between(alignmentStart, scanHoleExpression());
        }
        String format = null;
        if (current() == ':') {
            position++;
            int formatStart = position;
            while (!atEnd() && current() != '}' && !LineMap.isLineTerminator(current())) {
                position++;
            }
            format = text.substring(formatStart, position);
        }
        if (current() == '}') {
            position++;
        } else {
            reportAt(DiagnosticCode.MALFORMED_INTERPOLATION,
                    SourceSpan.between(braceStart, position), "the hole is not closed");
        }
        return new InterpolationPart.Hole(expression, alignment, format);
    }

    /// Scans an interpolated raw string. Its `$` count is also the number of consecutive
    /// braces which opens or closes a hole; shorter brace runs are literal content.
    private SyntaxToken scanInterpolatedRawString(int start, boolean startsLine, int dollars) {
        int quoteCount = 0;
        while (current() == '"') {
            quoteCount++;
            position++;
        }
        int contentStart = position;
        int contentEnd = -1;
        int closingRun = 0;
        while (!atEnd()) {
            if (current() != '"') {
                position++;
                continue;
            }
            int runStart = position;
            int run = 0;
            while (current() == '"') {
                run++;
                position++;
            }
            if (run >= quoteCount) {
                contentEnd = runStart;
                closingRun = run;
                break;
            }
        }
        if (contentEnd < 0) {
            contentEnd = position;
            report(DiagnosticCode.UNTERMINATED_RAW_STRING, start);
        } else if (closingRun > quoteCount) {
            report(DiagnosticCode.RAW_STRING_EXTRA_QUOTES, start);
        }
        RawContent content = normalizeRawContentWithOffsets(contentStart, contentEnd, start);
        List<InterpolationPart> parts = splitRawInterpolation(content, dollars, start);
        return SyntaxToken.literal(SyntaxKind.INTERPOLATED_STRING, spanFrom(start),
                textFrom(start), LiteralType.INTERPOLATED, parts, startsLine);
    }

    /// Applies multiline raw indentation while retaining one original source offset for
    /// each resulting UTF-16 code unit. Normalised newlines map to their original line
    /// terminator, which keeps an interpolation expression's span absolute and contiguous.
    private RawContent normalizeRawContentWithOffsets(int contentStart, int contentEnd,
            int literalStart) {
        List<RawLine> lines = rawLines(contentStart, contentEnd);
        StringBuilder value = new StringBuilder();
        List<Integer> offsets = new ArrayList<>();
        if (lines.size() == 1) {
            appendMapped(value, offsets, contentStart, contentEnd);
            return new RawContent(value.toString(), toIntArray(offsets), contentEnd);
        }

        RawLine opening = lines.getFirst();
        if (!text.substring(opening.start(), opening.end()).isBlank()) {
            reportAt(DiagnosticCode.RAW_STRING_DELIMITER_LINE,
                    SourceSpan.at(literalStart), "Opening");
        }
        RawLine closing = lines.getLast();
        String indentation = text.substring(closing.start(), closing.end());
        if (!indentation.isBlank()) {
            reportAt(DiagnosticCode.RAW_STRING_DELIMITER_LINE,
                    SourceSpan.at(contentEnd), "Closing");
            indentation = "";
        }

        for (int i = 1; i < lines.size() - 1; i++) {
            RawLine line = lines.get(i);
            String lineText = text.substring(line.start(), line.end());
            int retainedStart;
            if (lineText.startsWith(indentation)) {
                retainedStart = line.start() + indentation.length();
            } else if (lineText.isBlank()) {
                retainedStart = line.end();
            } else {
                reportAt(DiagnosticCode.RAW_STRING_INDENTATION,
                        SourceSpan.at(line.start()));
                retainedStart = line.start();
                while (retainedStart < line.end()
                        && Character.isWhitespace(text.charAt(retainedStart))) {
                    retainedStart++;
                }
            }
            appendMapped(value, offsets, retainedStart, line.end());
            if (i < lines.size() - 2) {
                value.append('\n');
                offsets.add(line.end());
            }
        }
        return new RawContent(value.toString(), toIntArray(offsets), closing.start());
    }

    private List<RawLine> rawLines(int start, int end) {
        List<RawLine> lines = new ArrayList<>();
        int lineStart = start;
        int cursor = start;
        while (cursor < end) {
            if (!LineMap.isLineTerminator(text.charAt(cursor))) {
                cursor++;
                continue;
            }
            int terminatorEnd = cursor + 1;
            if (text.charAt(cursor) == '\r' && terminatorEnd < end
                    && text.charAt(terminatorEnd) == '\n') {
                terminatorEnd++;
            }
            lines.add(new RawLine(lineStart, cursor, terminatorEnd));
            lineStart = terminatorEnd;
            cursor = terminatorEnd;
        }
        lines.add(new RawLine(lineStart, end, end));
        return List.copyOf(lines);
    }

    private void appendMapped(StringBuilder value, List<Integer> offsets, int start, int end) {
        for (int i = start; i < end; i++) {
            value.append(text.charAt(i));
            offsets.add(i);
        }
    }

    private static int[] toIntArray(List<Integer> offsets) {
        int[] result = new int[offsets.size()];
        for (int i = 0; i < offsets.size(); i++) {
            result[i] = offsets.get(i);
        }
        return result;
    }

    private List<InterpolationPart> splitRawInterpolation(RawContent content, int dollars,
            int literalStart) {
        List<InterpolationPart> parts = new ArrayList<>();
        StringBuilder run = new StringBuilder();
        int cursor = 0;
        while (cursor < content.value().length()) {
            char c = content.value().charAt(cursor);
            if (c == '{') {
                int braces = countRun(content.value(), cursor, '{');
                if (braces >= dollars) {
                    int literalBraces = braces - dollars;
                    run.repeat('{', literalBraces);
                    cursor += literalBraces;
                    if (!run.isEmpty()) {
                        parts.add(new InterpolationPart.Text(run.toString()));
                        run.setLength(0);
                    }
                    RawHole hole = scanRawHole(content, cursor, dollars, literalStart);
                    parts.add(hole.part());
                    cursor = hole.next();
                    continue;
                }
                run.repeat('{', braces);
                cursor += braces;
                continue;
            }
            if (c == '}') {
                int braces = countRun(content.value(), cursor, '}');
                if (braces >= dollars) {
                    reportAt(DiagnosticCode.UNESCAPED_INTERPOLATION_BRACE,
                            SourceSpan.between(content.sourceOffset(cursor),
                                    content.sourceOffset(cursor + braces)), "}");
                }
                run.repeat('}', braces);
                cursor += braces;
                continue;
            }
            run.append(c);
            cursor++;
        }
        if (!run.isEmpty()) {
            parts.add(new InterpolationPart.Text(run.toString()));
        }
        return List.copyOf(parts);
    }

    private RawHole scanRawHole(RawContent content, int opening, int dollars,
            int literalStart) {
        int cursor = opening + dollars;
        int expressionStart = cursor;
        cursor = scanRawHoleComponent(content.value(), cursor, dollars, true);
        int expressionEnd = cursor;
        SourceSpan expression = SourceSpan.between(content.sourceOffset(expressionStart),
                content.sourceOffset(expressionEnd));
        if (expression.isEmpty()) {
            reportAt(DiagnosticCode.MALFORMED_INTERPOLATION,
                    SourceSpan.at(content.sourceOffset(opening)),
                    "the hole has no expression");
        }
        SourceSpan alignment = null;
        if (charAt(content.value(), cursor) == ',') {
            cursor++;
            int alignmentStart = cursor;
            cursor = scanRawHoleComponent(content.value(), cursor, dollars, false);
            alignment = SourceSpan.between(content.sourceOffset(alignmentStart),
                    content.sourceOffset(cursor));
        }
        String format = null;
        if (charAt(content.value(), cursor) == ':') {
            cursor++;
            int formatStart = cursor;
            while (cursor < content.value().length()
                    && !hasRun(content.value(), cursor, '}', dollars)) {
                cursor++;
            }
            format = content.value().substring(formatStart, cursor);
        }
        if (hasRun(content.value(), cursor, '}', dollars)) {
            cursor += dollars;
        } else {
            int diagnosticStart = Math.min(content.sourceOffset(opening), literalStart);
            reportAt(DiagnosticCode.MALFORMED_INTERPOLATION,
                    SourceSpan.between(diagnosticStart, content.sourceOffset(cursor)),
                    "the hole is not closed");
            cursor = Math.max(cursor, opening + dollars);
        }
        return new RawHole(new InterpolationPart.Hole(expression, alignment, format), cursor);
    }

    private int scanRawHoleComponent(String value, int from, int dollars,
            boolean commaTerminates) {
        int cursor = from;
        int depth = 0;
        while (cursor < value.length()) {
            char c = value.charAt(cursor);
            if (c == '"' || c == '\'') {
                cursor = skipLogicalLiteral(value, cursor, c);
                continue;
            }
            if (c == '(' || c == '[' || c == '{') {
                depth++;
                cursor++;
                continue;
            }
            if (c == ')' || c == ']') {
                if (depth > 0) {
                    depth--;
                }
                cursor++;
                continue;
            }
            if (c == '}') {
                if (depth == 0 && hasRun(value, cursor, '}', dollars)) {
                    return cursor;
                }
                if (depth > 0) {
                    depth--;
                }
                cursor++;
                continue;
            }
            if (depth == 0 && ((commaTerminates && c == ',') || c == ':')) {
                return cursor;
            }
            cursor++;
        }
        return cursor;
    }

    private static int skipLogicalLiteral(String value, int from, char quote) {
        int cursor = from + 1;
        while (cursor < value.length()) {
            char c = value.charAt(cursor++);
            if (c == '\\' && cursor < value.length()) {
                cursor++;
            } else if (c == quote || LineMap.isLineTerminator(c)) {
                return cursor;
            }
        }
        return cursor;
    }

    private static int countRun(String value, int from, char expected) {
        int cursor = from;
        while (cursor < value.length() && value.charAt(cursor) == expected) {
            cursor++;
        }
        return cursor - from;
    }

    private static boolean hasRun(String value, int from, char expected, int count) {
        return countRun(value, from, expected) >= count;
    }

    private static char charAt(String value, int at) {
        return at < value.length() ? value.charAt(at) : '\0';
    }

    private record RawLine(int start, int end, int terminatorEnd) { }

    private record RawContent(String value, int[] offsets, int sourceEnd) {

        private int sourceOffset(int logicalOffset) {
            return logicalOffset < offsets.length ? offsets[logicalOffset] : sourceEnd;
        }
    }

    private record RawHole(InterpolationPart.Hole part, int next) { }

    /// Advances to the end of a hole's expression, returning that offset.
    ///
    /// Nesting is tracked so that `{f(a, b)}` and `{d[",": 1]}` are not cut at their inner
    /// punctuation. A bare `:` ends the expression, which is why C# requires parentheses
    /// around a conditional expression in a hole; `::` is left alone.
    private int scanHoleExpression() {
        int depth = 0;
        while (!atEnd()) {
            char c = current();
            if (LineMap.isLineTerminator(c) && depth == 0) {
                return position;
            }
            if (c == '(' || c == '[' || c == '{') {
                depth++;
                position++;
                continue;
            }
            if (c == ')' || c == ']') {
                if (depth == 0) {
                    return position;
                }
                depth--;
                position++;
                continue;
            }
            if (c == '}') {
                if (depth == 0) {
                    return position;
                }
                depth--;
                position++;
                continue;
            }
            if (depth == 0 && c == ',') {
                return position;
            }
            if (depth == 0 && c == ':') {
                if (peek(1) == ':') {
                    position += 2;
                    continue;
                }
                return position;
            }
            if (c == '"' || c == '\'') {
                skipNestedLiteral(c);
                continue;
            }
            position++;
        }
        return position;
    }

    /// Skips a string or character literal nested inside an interpolation hole.
    private void skipNestedLiteral(char quote) {
        position++;
        while (!atEnd()) {
            char c = current();
            if (c == '\\') {
                position += position + 1 < limit ? 2 : 1;
                continue;
            }
            position++;
            if (c == quote) {
                return;
            }
            if (LineMap.isLineTerminator(c)) {
                return;
            }
        }
    }

    // ---- Escapes -----------------------------------------------------------------------

    /// Decodes one escape sequence at the current backslash, appending the result.
    ///
    /// @return whether the escape was well formed; a caller that would otherwise report a
    ///         consequence of the failure, such as an empty character literal, stays quiet
    private boolean decodeEscape(StringBuilder out) {
        int start = position;
        if (position + 1 >= limit) {
            position++;
            reportAt(DiagnosticCode.UNRECOGNIZED_ESCAPE, SourceSpan.between(start, position));
            return false;
        }
        char kind = text.charAt(position + 1);
        boolean wellFormed = true;
        switch (kind) {
            case '\'', '"', '\\' -> {
                out.append(kind);
                position += 2;
            }
            case '0' -> {
                out.append('\0');
                position += 2;
            }
            case 'a' -> {
                out.append((char) 0x0007);
                position += 2;
            }
            case 'b' -> {
                out.append('\b');
                position += 2;
            }
            case 'f' -> {
                out.append('\f');
                position += 2;
            }
            case 'n' -> {
                out.append('\n');
                position += 2;
            }
            case 'r' -> {
                out.append('\r');
                position += 2;
            }
            case 't' -> {
                out.append('\t');
                position += 2;
            }
            case 'v' -> {
                out.append((char) 0x000B);
                position += 2;
            }
            case 'u', 'U' -> {
                int codePoint = scanUnicodeEscapeValue();
                if (codePoint >= 0) {
                    out.appendCodePoint(codePoint);
                } else {
                    wellFormed = false;
                }
            }
            case 'x' -> {
                position += 2;
                int value = 0;
                int digits = 0;
                while (digits < MAX_HEX_ESCAPE_DIGITS && isHexDigit(current())) {
                    value = (value << 4) + hexValue(current());
                    position++;
                    digits++;
                }
                if (digits == 0) {
                    reportAt(DiagnosticCode.UNRECOGNIZED_ESCAPE,
                            SourceSpan.between(start, position));
                    wellFormed = false;
                } else {
                    out.append((char) value);
                }
            }
            default -> {
                position += 2;
                reportAt(DiagnosticCode.UNRECOGNIZED_ESCAPE, SourceSpan.between(start, position));
                wellFormed = false;
            }
        }
        return wellFormed;
    }

    // ---- Punctuators -------------------------------------------------------------------

    private SyntaxToken scanPunctuator(int start, boolean startsLine) {
        char c = advance();
        SyntaxKind kind = switch (c) {
            case '{' -> SyntaxKind.OPEN_BRACE;
            case '}' -> SyntaxKind.CLOSE_BRACE;
            case '[' -> SyntaxKind.OPEN_BRACKET;
            case ']' -> SyntaxKind.CLOSE_BRACKET;
            case '(' -> SyntaxKind.OPEN_PAREN;
            case ')' -> SyntaxKind.CLOSE_PAREN;
            case ',' -> SyntaxKind.COMMA;
            case ';' -> SyntaxKind.SEMICOLON;
            case '~' -> SyntaxKind.TILDE;
            case '#' -> SyntaxKind.HASH;
            case '.' -> match('.') ? SyntaxKind.DOT_DOT : SyntaxKind.DOT;
            case ':' -> match(':') ? SyntaxKind.COLON_COLON : SyntaxKind.COLON;
            case '+' -> match('+') ? SyntaxKind.PLUS_PLUS
                    : match('=') ? SyntaxKind.PLUS_EQUALS : SyntaxKind.PLUS;
            case '-' -> match('-') ? SyntaxKind.MINUS_MINUS
                    : match('=') ? SyntaxKind.MINUS_EQUALS
                    : match('>') ? SyntaxKind.MINUS_GREATER_THAN : SyntaxKind.MINUS;
            case '*' -> match('=') ? SyntaxKind.ASTERISK_EQUALS : SyntaxKind.ASTERISK;
            case '/' -> match('=') ? SyntaxKind.SLASH_EQUALS : SyntaxKind.SLASH;
            case '%' -> match('=') ? SyntaxKind.PERCENT_EQUALS : SyntaxKind.PERCENT;
            case '^' -> match('=') ? SyntaxKind.CARET_EQUALS : SyntaxKind.CARET;
            case '!' -> match('=') ? SyntaxKind.EXCLAMATION_EQUALS : SyntaxKind.EXCLAMATION;
            case '=' -> match('=') ? SyntaxKind.EQUALS_EQUALS
                    : match('>') ? SyntaxKind.EQUALS_GREATER_THAN : SyntaxKind.EQUALS;
            case '&' -> match('&') ? SyntaxKind.AMPERSAND_AMPERSAND
                    : match('=') ? SyntaxKind.AMPERSAND_EQUALS : SyntaxKind.AMPERSAND;
            case '|' -> match('|') ? SyntaxKind.BAR_BAR
                    : match('=') ? SyntaxKind.BAR_EQUALS : SyntaxKind.BAR;
            case '>' -> match('=') ? SyntaxKind.GREATER_THAN_EQUALS : SyntaxKind.GREATER_THAN;
            case '<' -> scanLessThan();
            case '?' -> scanQuestion();
            default -> null;
        };
        if (kind == null) {
            report(DiagnosticCode.UNEXPECTED_CHARACTER, start, String.valueOf(c));
            return SyntaxToken.of(SyntaxKind.BAD_TOKEN, spanFrom(start), textFrom(start),
                    startsLine);
        }
        return SyntaxToken.of(kind, spanFrom(start), textFrom(start), startsLine);
    }

    private SyntaxKind scanLessThan() {
        if (match('=')) {
            return SyntaxKind.LESS_THAN_EQUALS;
        }
        if (match('<')) {
            return match('=') ? SyntaxKind.LESS_THAN_LESS_THAN_EQUALS
                    : SyntaxKind.LESS_THAN_LESS_THAN;
        }
        return SyntaxKind.LESS_THAN;
    }

    private SyntaxKind scanQuestion() {
        if (current() == '?') {
            position++;
            return match('=') ? SyntaxKind.QUESTION_QUESTION_EQUALS
                    : SyntaxKind.QUESTION_QUESTION;
        }
        // `?.` only forms when a member name can follow: `x ? .5 : 0` must still work.
        if (current() == '.' && !isDigit(peek(1))) {
            position++;
            return SyntaxKind.QUESTION_DOT;
        }
        return SyntaxKind.QUESTION;
    }

    // ---- Character classification ------------------------------------------------------

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isHexDigit(char c) {
        return isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static int hexValue(char c) {
        if (isDigit(c)) {
            return c - '0';
        }
        return (Character.toLowerCase(c) - 'a') + 10;
    }
}
