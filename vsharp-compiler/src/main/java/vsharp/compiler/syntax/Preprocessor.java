package vsharp.compiler.syntax;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import vsharp.compiler.diagnostics.DiagnosticBag;
import vsharp.compiler.diagnostics.DiagnosticCode;
import vsharp.compiler.source.LineMap;
import vsharp.compiler.source.SourceFile;
import vsharp.compiler.source.SourceSpan;

/// C# conditional-compilation pass.
///
/// The result is a same-length view of the source. Directive lines and inactive branches
/// become spaces while line terminators are untouched, so the ordinary lexer sees only
/// active C# and every token/diagnostic still has its original absolute offset. Directives
/// themselves are retained as syntax records for tooling and later `#line`/nullable policy.
public final class Preprocessor {

    private Preprocessor() {
        throw new AssertionError("No instances");
    }

    /// Processes a file with an initial symbol set.
    public static Result process(SourceFile file, Set<String> initialSymbols,
            DiagnosticBag diagnostics) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(initialSymbols, "initialSymbols");
        Objects.requireNonNull(diagnostics, "diagnostics");
        Processor processor = new Processor(file, initialSymbols, diagnostics);
        return processor.process();
    }

    /// Immutable output of preprocessing.
    public record Result(String maskedText, List<AuxiliarySyntax.Directive> directives,
            Set<String> symbols) {

        public Result {
            Objects.requireNonNull(maskedText, "maskedText");
            directives = List.copyOf(directives);
            symbols = Collections.unmodifiableSet(new TreeSet<>(symbols));
        }
    }

    private static final class Processor {

        private final SourceFile file;
        private final String text;
        private final char[] masked;
        private final Set<String> symbols;
        private final DiagnosticBag diagnostics;
        private final List<AuxiliarySyntax.Directive> directives = new ArrayList<>();
        private final Deque<ConditionalFrame> conditionals = new ArrayDeque<>();

        private Processor(SourceFile file, Set<String> initialSymbols,
                DiagnosticBag diagnostics) {
            this.file = file;
            this.text = file.text();
            this.masked = text.toCharArray();
            this.symbols = new HashSet<>(initialSymbols);
            this.diagnostics = diagnostics;
        }

        private Result process() {
            int lineStart = 0;
            while (lineStart < text.length()) {
                int lineEnd = lineStart;
                while (lineEnd < text.length()
                        && !LineMap.isLineTerminator(text.charAt(lineEnd))) {
                    lineEnd++;
                }
                processLine(lineStart, lineEnd);
                if (lineEnd == text.length()) {
                    lineStart = lineEnd;
                } else if (text.charAt(lineEnd) == '\r' && lineEnd + 1 < text.length()
                        && text.charAt(lineEnd + 1) == '\n') {
                    lineStart = lineEnd + 2;
                } else {
                    lineStart = lineEnd + 1;
                }
            }
            // The empty final line needs no processing, but an empty file is valid.
            if (!conditionals.isEmpty()) {
                diagnostics.report(DiagnosticCode.ENDIF_EXPECTED, file,
                        SourceSpan.at(file.length()));
            }
            return new Result(new String(masked), directives, symbols);
        }

        private void processLine(int lineStart, int lineEnd) {
            int first = lineStart;
            while (first < lineEnd && SyntaxFacts.isWhitespace(text.charAt(first))) {
                first++;
            }
            if (first < lineEnd && text.charAt(first) == '#') {
                processDirective(first, lineEnd);
                mask(lineStart, lineEnd);
            } else if (!isActive()) {
                mask(lineStart, lineEnd);
            }
        }

        private void processDirective(int hash, int lineEnd) {
            int cursor = hash + 1;
            while (cursor < lineEnd && SyntaxFacts.isWhitespace(text.charAt(cursor))) {
                cursor++;
            }
            int keywordStart = cursor;
            while (cursor < lineEnd && SyntaxFacts.isIdentifierPart(text.charAt(cursor))) {
                cursor++;
            }
            String keyword = text.substring(keywordStart, cursor).toLowerCase(Locale.ROOT);
            int argumentStart = cursor;
            while (argumentStart < lineEnd && SyntaxFacts.isWhitespace(text.charAt(argumentStart))) {
                argumentStart++;
            }
            String argument = text.substring(argumentStart, lineEnd).stripTrailing();
            AuxiliarySyntax.Directive.Kind kind = directiveKind(keyword);
            SourceSpan directiveSpan = SourceSpan.between(hash, lineEnd);
            directives.add(new AuxiliarySyntax.Directive(directiveSpan, kind, argument));
            if (kind == AuxiliarySyntax.Directive.Kind.UNKNOWN) {
                diagnostics.report(DiagnosticCode.INVALID_PREPROCESSOR_DIRECTIVE, file,
                        SourceSpan.between(keywordStart, Math.max(keywordStart, cursor)));
                return;
            }
            switch (kind) {
                case IF -> enterConditional(argument, argumentStart, lineEnd);
                case ELIF -> continueConditional(argument, argumentStart, lineEnd);
                case ELSE -> elseConditional(directiveSpan);
                case ENDIF -> endConditional(directiveSpan);
                case DEFINE -> {
                    if (isActive() && validSymbol(argument)) {
                        symbols.add(argument);
                    }
                }
                case UNDEF -> {
                    if (isActive() && validSymbol(argument)) {
                        symbols.remove(argument);
                    }
                }
                case ERROR -> {
                    if (isActive()) {
                        diagnostics.report(DiagnosticCode.PREPROCESSOR_ERROR, file, directiveSpan,
                                argument);
                    }
                }
                case WARNING -> {
                    if (isActive()) {
                        diagnostics.report(DiagnosticCode.PREPROCESSOR_WARNING, file,
                                directiveSpan, argument);
                    }
                }
                case REGION, ENDREGION, LINE, NULLABLE, PRAGMA -> {
                    // Retained for their owning later stage. They do not alter activity.
                }
            }
        }

        private void enterConditional(String argument, int argumentStart, int lineEnd) {
            boolean parentActive = isActive();
            boolean condition = evaluate(argument, argumentStart, lineEnd);
            boolean currentActive = parentActive && condition;
            conditionals.push(new ConditionalFrame(parentActive, currentActive, currentActive,
                    false));
        }

        private void continueConditional(String argument, int argumentStart, int lineEnd) {
            if (conditionals.isEmpty()) {
                unexpected(SourceSpan.between(Math.max(0, argumentStart - 5), lineEnd));
                return;
            }
            ConditionalFrame prior = conditionals.pop();
            if (prior.elseSeen()) {
                unexpected(SourceSpan.between(Math.max(0, argumentStart - 5), lineEnd));
                conditionals.push(prior);
                return;
            }
            boolean condition = evaluate(argument, argumentStart, lineEnd);
            boolean active = prior.parentActive() && !prior.branchTaken() && condition;
            conditionals.push(new ConditionalFrame(prior.parentActive(),
                    prior.branchTaken() || active, active, false));
        }

        private void elseConditional(SourceSpan span) {
            if (conditionals.isEmpty()) {
                unexpected(span);
                return;
            }
            ConditionalFrame prior = conditionals.pop();
            if (prior.elseSeen()) {
                unexpected(span);
                conditionals.push(prior);
                return;
            }
            boolean active = prior.parentActive() && !prior.branchTaken();
            conditionals.push(new ConditionalFrame(prior.parentActive(), true, active, true));
        }

        private void endConditional(SourceSpan span) {
            if (conditionals.isEmpty()) {
                unexpected(span);
            } else {
                conditionals.pop();
            }
        }

        private void unexpected(SourceSpan span) {
            diagnostics.report(DiagnosticCode.UNEXPECTED_PREPROCESSOR_DIRECTIVE, file, span);
        }

        private boolean evaluate(String argument, int argumentStart, int lineEnd) {
            ConditionParser parser = new ConditionParser(argument, symbols);
            boolean result = parser.parse();
            if (!parser.valid()) {
                diagnostics.report(DiagnosticCode.INVALID_PREPROCESSOR_EXPRESSION, file,
                        SourceSpan.between(argumentStart, lineEnd));
            }
            return result;
        }

        private boolean isActive() {
            return conditionals.isEmpty() || conditionals.peek().currentActive();
        }

        private void mask(int start, int end) {
            for (int i = start; i < end; i++) {
                masked[i] = ' ';
            }
        }

        private static boolean validSymbol(String value) {
            if (value.isEmpty() || !SyntaxFacts.isIdentifierStart(value.codePointAt(0))) {
                return false;
            }
            for (int i = Character.charCount(value.codePointAt(0)); i < value.length();) {
                int codePoint = value.codePointAt(i);
                if (!SyntaxFacts.isIdentifierPart(codePoint)) {
                    return false;
                }
                i += Character.charCount(codePoint);
            }
            return true;
        }

        private static AuxiliarySyntax.Directive.Kind directiveKind(String keyword) {
            return switch (keyword) {
                case "define" -> AuxiliarySyntax.Directive.Kind.DEFINE;
                case "undef" -> AuxiliarySyntax.Directive.Kind.UNDEF;
                case "if" -> AuxiliarySyntax.Directive.Kind.IF;
                case "elif" -> AuxiliarySyntax.Directive.Kind.ELIF;
                case "else" -> AuxiliarySyntax.Directive.Kind.ELSE;
                case "endif" -> AuxiliarySyntax.Directive.Kind.ENDIF;
                case "region" -> AuxiliarySyntax.Directive.Kind.REGION;
                case "endregion" -> AuxiliarySyntax.Directive.Kind.ENDREGION;
                case "error" -> AuxiliarySyntax.Directive.Kind.ERROR;
                case "warning" -> AuxiliarySyntax.Directive.Kind.WARNING;
                case "line" -> AuxiliarySyntax.Directive.Kind.LINE;
                case "nullable" -> AuxiliarySyntax.Directive.Kind.NULLABLE;
                case "pragma" -> AuxiliarySyntax.Directive.Kind.PRAGMA;
                default -> AuxiliarySyntax.Directive.Kind.UNKNOWN;
            };
        }
    }

    private record ConditionalFrame(boolean parentActive, boolean branchTaken,
            boolean currentActive, boolean elseSeen) { }

    /// Tiny recursive-descent parser for the preprocessor's deliberately restricted
    /// Boolean language: identifiers, true/false, !, &&, ||, ==, != and parentheses.
    private static final class ConditionParser {

        private final String text;
        private final Set<String> symbols;
        private int position;
        private boolean valid = true;

        private ConditionParser(String text, Set<String> symbols) {
            this.text = text;
            this.symbols = symbols;
        }

        private boolean parse() {
            boolean value = parseOr();
            skipWhitespace();
            if (position != text.length()) {
                valid = false;
            }
            return value;
        }

        private boolean parseOr() {
            boolean value = parseAnd();
            while (match("||")) {
                boolean right = parseAnd();
                value = value || right;
            }
            return value;
        }

        private boolean parseAnd() {
            boolean value = parseEquality();
            while (match("&&")) {
                boolean right = parseEquality();
                value = value && right;
            }
            return value;
        }

        private boolean parseEquality() {
            boolean value = parseUnary();
            while (true) {
                if (match("==")) {
                    boolean right = parseUnary();
                    value = value == right;
                } else if (match("!=")) {
                    boolean right = parseUnary();
                    value = value != right;
                } else {
                    return value;
                }
            }
        }

        private boolean parseUnary() {
            if (match("!")) {
                return !parseUnary();
            }
            if (match("(")) {
                boolean value = parseOr();
                if (!match(")")) {
                    valid = false;
                }
                return value;
            }
            String identifier = takeIdentifier();
            return switch (identifier) {
                case "true" -> true;
                case "false" -> false;
                case "" -> {
                    valid = false;
                    if (position < text.length()) {
                        position++;
                    }
                    yield false;
                }
                default -> symbols.contains(identifier);
            };
        }

        private String takeIdentifier() {
            skipWhitespace();
            if (position >= text.length()
                    || !SyntaxFacts.isIdentifierStart(text.codePointAt(position))) {
                return "";
            }
            int start = position;
            int codePoint = text.codePointAt(position);
            position += Character.charCount(codePoint);
            while (position < text.length()) {
                codePoint = text.codePointAt(position);
                if (!SyntaxFacts.isIdentifierPart(codePoint)) {
                    break;
                }
                position += Character.charCount(codePoint);
            }
            return text.substring(start, position);
        }

        private boolean match(String expected) {
            skipWhitespace();
            if (!text.startsWith(expected, position)) {
                return false;
            }
            position += expected.length();
            return true;
        }

        private void skipWhitespace() {
            while (position < text.length()
                    && SyntaxFacts.isWhitespace(text.charAt(position))) {
                position++;
            }
        }

        private boolean valid() {
            return valid;
        }
    }
}
