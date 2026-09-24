package vsharp.lsp.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import vsharp.compiler.source.SourceSpan;
import vsharp.compiler.syntax.SyntaxFacts;

/// Identifier extraction straight from buffer text.
///
/// Hover and go-to-definition need the word under a cursor before any tree exists - the
/// buffer may not parse at all while the user is mid-edit. Scanning text with the
/// compiler's own [SyntaxFacts] identifier predicates keeps the word boundaries identical
/// to the lexer's, so the server never selects a span the compiler would tokenise
/// differently.
final class Words {

    private Words() {
        throw new AssertionError("No instances");
    }

    /// The identifier containing or immediately preceding `offset`, if there is one.
    static Optional<String> at(String text, int offset) {
        SourceSpan span = spanAt(text, offset);
        return span.isEmpty() ? Optional.empty() : Optional.of(text.substring(span.start(), span.end()));
    }

    /// Every whole-word occurrence of `word` in `text`, in position order.
    ///
    /// Whole-word means the characters on both sides are not identifier parts, so searching
    /// for `Limit` does not match `LimitValue` or `MaxLimit`. Comments and string literals are
    /// not excluded: doing that properly needs the lexer's state, and the compiler's token
    /// stream is not retained for a buffer that may not parse. A name mentioned in a comment
    /// is a real mention, which is the honest reading of a lexical search.
    static List<SourceSpan> occurrences(String text, String word) {
        List<SourceSpan> spans = new ArrayList<>();
        if (word.isEmpty()) {
            return spans;
        }
        int from = 0;
        while (true) {
            int at = text.indexOf(word, from);
            if (at < 0) {
                return spans;
            }
            int after = at + word.length();
            boolean boundedLeft = at == 0
                    || !SyntaxFacts.isIdentifierPart(text.charAt(at - 1));
            boolean boundedRight = after >= text.length()
                    || !SyntaxFacts.isIdentifierPart(text.charAt(after));
            if (boundedLeft && boundedRight) {
                spans.add(SourceSpan.between(at, after));
            }
            from = at + 1;
        }
    }

    /// The span of that identifier; empty at `offset` when the cursor is not on a word.
    ///
    /// A cursor sitting immediately after a word counts as being on it, which is where a
    /// caret lands when the user finishes typing a name and hovers without moving.
    static SourceSpan spanAt(String text, int offset) {
        int clamped = Math.max(0, Math.min(offset, text.length()));
        int start = clamped;
        while (start > 0 && SyntaxFacts.isIdentifierPart(text.charAt(start - 1))) {
            start--;
        }
        int end = clamped;
        while (end < text.length() && SyntaxFacts.isIdentifierPart(text.charAt(end))) {
            end++;
        }
        if (start == end) {
            return SourceSpan.at(clamped);
        }
        if (!SyntaxFacts.isIdentifierStart(text.charAt(start))) {
            // A numeric literal, not a name: `1x` must never hover as an identifier.
            return SourceSpan.at(clamped);
        }
        return SourceSpan.between(start, end);
    }
}
