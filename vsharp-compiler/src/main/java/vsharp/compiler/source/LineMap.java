package vsharp.compiler.source;

import java.util.Arrays;

/// Maps character offsets to one-based line/column positions.
///
/// Line breaks follow the C# specification rather than the platform: carriage return,
/// line feed, the CR LF pair (counted once), next line (U+0085), line separator (U+2028)
/// and paragraph separator (U+2029) all start a new line. Java's own notion of a line
/// terminator is narrower, so relying on it would misreport positions in files that use
/// the Unicode separators.
public final class LineMap {

    /// U+0085 NEXT LINE.
    private static final char NEXT_LINE = 0x0085;

    /// U+2028 LINE SEPARATOR.
    private static final char LINE_SEPARATOR = 0x2028;

    /// U+2029 PARAGRAPH SEPARATOR.
    private static final char PARAGRAPH_SEPARATOR = 0x2029;

    /// Offset of the first character of each line; always starts with `0`.
    private final int[] lineStarts;

    private final int textLength;

    private LineMap(int[] lineStarts, int textLength) {
        this.lineStarts = lineStarts;
        this.textLength = textLength;
    }

    /// Builds a line map for `text`.
    public static LineMap of(CharSequence text) {
        int[] starts = new int[Math.max(16, text.length() / 24 + 2)];
        int count = 0;
        starts[count++] = 0;
        int length = text.length();
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            if (!isLineTerminator(c)) {
                continue;
            }
            // A CR LF pair is one terminator, so consume the LF with it.
            if (c == '\r' && i + 1 < length && text.charAt(i + 1) == '\n') {
                i++;
            }
            if (count == starts.length) {
                starts = Arrays.copyOf(starts, starts.length * 2);
            }
            starts[count++] = i + 1;
        }
        return new LineMap(Arrays.copyOf(starts, count), length);
    }

    /// Whether `c` terminates a line under the C# lexical grammar.
    public static boolean isLineTerminator(char c) {
        return c == '\r'
                || c == '\n'
                || c == NEXT_LINE
                || c == LINE_SEPARATOR
                || c == PARAGRAPH_SEPARATOR;
    }

    /// Number of lines; a file always has at least one.
    public int lineCount() {
        return lineStarts.length;
    }

    /// Total length of the mapped text.
    public int textLength() {
        return textLength;
    }

    /// Converts a character offset to a one-based position.
    ///
    /// @throws IndexOutOfBoundsException if `offset` lies outside `[0, textLength]`
    public LinePosition positionOf(int offset) {
        if (offset < 0 || offset > textLength) {
            throw new IndexOutOfBoundsException(
                    "Offset " + offset + " is outside the source text of length " + textLength);
        }
        int index = Arrays.binarySearch(lineStarts, offset);
        int line = index >= 0 ? index : -index - 2;
        return new LinePosition(line + 1, offset - lineStarts[line] + 1);
    }

    /// Start offset of a one-based line number.
    public int lineStart(int line) {
        if (line < 1 || line > lineStarts.length) {
            throw new IndexOutOfBoundsException(
                    "Line " + line + " is outside the source text with " + lineStarts.length + " lines");
        }
        return lineStarts[line - 1];
    }

    /// End offset of a one-based line, excluding its terminator.
    public int lineEnd(int line, CharSequence text) {
        int start = lineStart(line);
        int limit = line < lineStarts.length ? lineStarts[line] : textLength;
        int end = limit;
        while (end > start && isLineTerminator(text.charAt(end - 1))) {
            end--;
        }
        return end;
    }
}
