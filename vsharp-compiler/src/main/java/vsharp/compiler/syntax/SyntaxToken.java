package vsharp.compiler.syntax;

import java.util.Objects;
import java.util.Optional;
import vsharp.compiler.source.SourceSpan;

/// One lexed token.
///
/// Trivia is not attached. V# has no formatter or round-tripping requirement, so the lexer
/// discards whitespace and comments once it has recorded what the rest of the compiler
/// actually needs from them: [#atStartOfLine], which preprocessing directives and some
/// diagnostics depend on.
///
/// @param kind          the token's classification
/// @param span          the exact source region, including quotes and suffixes
/// @param text          the raw source text of the token
/// @param literalType   the C# type of the literal value, or [LiteralType#NONE]
/// @param value         the decoded literal value, whose class is fixed by `literalType`;
///                      `null` for non-literals and for the `null` literal
/// @param atStartOfLine whether only whitespace preceded this token on its line
public record SyntaxToken(
        SyntaxKind kind,
        SourceSpan span,
        String text,
        LiteralType literalType,
        Object value,
        boolean atStartOfLine) {

    /// Validates the invariants every token shares.
    public SyntaxToken {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(literalType, "literalType");
    }

    /// Creates a token that carries no literal value.
    public static SyntaxToken of(SyntaxKind kind, SourceSpan span, String text,
            boolean atStartOfLine) {
        return new SyntaxToken(kind, span, text, LiteralType.NONE, null, atStartOfLine);
    }

    /// Creates a literal token.
    public static SyntaxToken literal(SyntaxKind kind, SourceSpan span, String text,
            LiteralType literalType, Object value, boolean atStartOfLine) {
        return new SyntaxToken(kind, span, text, literalType, value, atStartOfLine);
    }

    /// Creates the zero-width token the parser inserts when a required token is absent.
    ///
    /// The span is empty and sits exactly where the token should have been, so the caret
    /// in a diagnostic points at the gap rather than at the next token.
    public static SyntaxToken missing(SyntaxKind expected, int position) {
        return new SyntaxToken(SyntaxKind.MISSING_TOKEN, SourceSpan.at(position),
                expected.display(), LiteralType.NONE, null, false);
    }

    /// Whether this token has the given kind.
    public boolean is(SyntaxKind other) {
        return kind == other;
    }

    /// Whether this token was synthesised by error recovery rather than lexed.
    public boolean isMissing() {
        return kind == SyntaxKind.MISSING_TOKEN;
    }

    /// The decoded literal value, if this token is a literal with a value.
    ///
    /// Empty for non-literals and for `null`, which the [#literalType] distinguishes.
    public Optional<Object> literalValue() {
        return Optional.ofNullable(value);
    }

    /// The literal value, cast to the caller's expected class.
    ///
    /// @throws ClassCastException if the value is not of that class, which is a compiler
    ///         defect rather than a user error: [LiteralType] fixes the class
    public <T> T valueAs(Class<T> type) {
        return type.cast(value);
    }

    @Override
    public String toString() {
        return kind + "@" + span + " '" + text + "'";
    }
}
