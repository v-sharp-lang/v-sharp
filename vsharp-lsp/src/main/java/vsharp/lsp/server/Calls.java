package vsharp.lsp.server;

import java.util.Optional;
import vsharp.compiler.syntax.SyntaxFacts;

/// Finds the call whose argument list encloses the cursor.
///
/// Signature help is asked for while the call is still being written, so there is no bound tree
/// to consult and usually no matching `)` either: `Console.WriteLine(` is the normal input, not
/// the exception. The scan therefore works on text, backwards from the cursor, looking for the
/// nearest `(` that nothing has closed.
///
/// It is not a parser and does not pretend to be. It tracks three things a naive bracket count
/// gets wrong - nested parentheses, brackets, and text inside string or character literals -
/// because each of them otherwise produces a confident answer about the wrong call. Comments
/// are deliberately not handled: recognising them needs a forward scan from the line start and
/// the payoff is a case nobody hits, since nobody asks for signature help inside a comment.
final class Calls {

    /// The call being written at the cursor: the text naming the callee, and which argument
    /// the cursor sits in, counting from zero.
    record CallSite(String callee, int activeParameter) {}

    /// How far back to scan. A call's argument list is rarely more than a few hundred
    /// characters, and the bound keeps a pathological file from turning every keystroke into a
    /// walk to the top of the buffer.
    private static final int MAX_SCAN = 8192;

    private Calls() {
        throw new AssertionError("No instances");
    }

    /// The enclosing call at `offset`, or empty when the cursor is not inside an argument list.
    static Optional<CallSite> enclosing(String text, int offset) {
        int cursor = Math.max(0, Math.min(offset, text.length()));
        int limit = Math.max(0, cursor - MAX_SCAN);
        int depth = 0;
        int commas = 0;
        for (int index = cursor - 1; index >= limit; index--) {
            char c = text.charAt(index);
            if (c == '"' || c == '\'') {
                index = openingQuote(text, index, limit);
                continue;
            }
            if (c == ')' || c == ']') {
                depth++;
                continue;
            }
            if (c == '[') {
                // An unclosed `[` means the cursor is in an index or collection expression,
                // not an argument list; the enclosing call, if any, is not what is being typed.
                if (depth == 0) {
                    return Optional.empty();
                }
                depth--;
                continue;
            }
            if (c == '(') {
                if (depth > 0) {
                    depth--;
                    continue;
                }
                String callee = calleeBefore(text, index);
                return callee.isEmpty()
                        ? Optional.empty()
                        : Optional.of(new CallSite(callee, commas));
            }
            if (c == ',' && depth == 0) {
                commas++;
                continue;
            }
            if (c == ';' || c == '{' || c == '}') {
                // A statement boundary: there is no call in progress.
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /// Given the index of a closing quote, the index of its opening one.
    ///
    /// Scanning backwards means the quote met first is the *closing* one. A quote preceded by
    /// an odd number of backslashes is escaped and does not open the literal, which is what
    /// keeps `"a\"b"` from being read as two literals.
    private static int openingQuote(String text, int closing, int limit) {
        char quote = text.charAt(closing);
        for (int index = closing - 1; index >= limit; index--) {
            if (text.charAt(index) != quote) {
                continue;
            }
            int backslashes = 0;
            for (int back = index - 1; back >= limit && text.charAt(back) == '\\'; back--) {
                backslashes++;
            }
            if (backslashes % 2 == 0) {
                return index;
            }
        }
        return limit;
    }

    /// The dotted name immediately before `parenthesis`, such as `Console.WriteLine`.
    ///
    /// Whitespace between the name and the parenthesis is allowed, because `Foo (x)` is legal
    /// and someone mid-edit produces it constantly.
    private static String calleeBefore(String text, int parenthesis) {
        int end = parenthesis;
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        int start = end;
        while (start > 0) {
            char c = text.charAt(start - 1);
            if (SyntaxFacts.isIdentifierPart(c) || c == '.') {
                start--;
                continue;
            }
            break;
        }
        String callee = text.substring(start, end).trim();
        // A trailing dot means the member is not written yet; there is nothing to describe.
        return callee.endsWith(".") || callee.startsWith(".") ? "" : callee;
    }
}
