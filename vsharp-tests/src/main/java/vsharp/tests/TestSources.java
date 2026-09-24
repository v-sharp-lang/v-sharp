package vsharp.tests;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import vsharp.compiler.diagnostics.DiagnosticBag;
import vsharp.compiler.source.LineMap;
import vsharp.compiler.source.SourceFile;
import vsharp.compiler.syntax.Lexer;
import vsharp.compiler.syntax.SyntaxKind;
import vsharp.compiler.syntax.SyntaxToken;

/// Builds canonical V# source used by non-style tests.
///
/// The historical suite deliberately used compact C# snippets. Source layout is now a language
/// invariant, so execution and semantic tests pass their inputs through this syntax-aware brace
/// normalizer. It sees braces through the production lexer, which means braces inside comments,
/// strings and interpolation text are never rewritten. Dedicated style tests always bypass this
/// helper and feed the compiler the exact source under examination.
public final class TestSources {

    private static final int INDENT_WIDTH = 4;

    private TestSources() {
        throw new AssertionError("No instances");
    }

    /// Creates an in-memory source after canonicalizing brace layout.
    public static SourceFile styled(String name, String text) {
        return SourceFile.of(name, allman(text));
    }

    /// Moves syntax braces to their canonical line and indentation without touching literals.
    public static String allman(String text) {
        SourceFile source = SourceFile.of("style-input.vs", text);
        List<SyntaxToken> tokens = Lexer.tokenize(source, new DiagnosticBag());
        Map<Integer, SyntaxKind> braces = new HashMap<>();
        for (SyntaxToken token : tokens) {
            if (token.is(SyntaxKind.OPEN_BRACE) || token.is(SyntaxKind.CLOSE_BRACE)) {
                braces.put(token.span().start(), token.kind());
            }
        }

        StringBuilder result = new StringBuilder(text.length() + braces.size() * 8);
        int depth = 0;
        boolean discardWhitespaceAfterOpening = false;
        for (int index = 0; index < text.length(); index++) {
            SyntaxKind brace = braces.get(index);
            if (brace == SyntaxKind.OPEN_BRACE) {
                ensureLineStart(result, depth);
                result.append('{').append('\n');
                depth++;
                result.append(" ".repeat(depth * INDENT_WIDTH));
                discardWhitespaceAfterOpening = true;
                continue;
            }
            if (brace == SyntaxKind.CLOSE_BRACE) {
                depth = Math.max(0, depth - 1);
                ensureLineStart(result, depth);
                result.append('}');
                discardWhitespaceAfterOpening = false;
                continue;
            }

            char character = text.charAt(index);
            if (discardWhitespaceAfterOpening && isLayoutWhitespace(character)) {
                continue;
            }
            discardWhitespaceAfterOpening = false;
            result.append(character);
        }
        return result.toString();
    }

    private static void ensureLineStart(StringBuilder text, int depth) {
        int lineStart = text.length();
        while (lineStart > 0 && !LineMap.isLineTerminator(text.charAt(lineStart - 1))) {
            lineStart--;
        }
        int contentEnd = text.length();
        while (contentEnd > lineStart && isHorizontalWhitespace(text.charAt(contentEnd - 1))) {
            contentEnd--;
        }
        if (contentEnd > lineStart) {
            text.setLength(contentEnd);
            text.append('\n');
        } else {
            text.setLength(lineStart);
        }
        text.append(" ".repeat(depth * INDENT_WIDTH));
    }

    private static boolean isLayoutWhitespace(char character) {
        return isHorizontalWhitespace(character) || LineMap.isLineTerminator(character);
    }

    private static boolean isHorizontalWhitespace(char character) {
        return !LineMap.isLineTerminator(character) && Character.isWhitespace(character);
    }
}
