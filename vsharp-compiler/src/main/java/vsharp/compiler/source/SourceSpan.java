package vsharp.compiler.source;

/// A half-open character range `[start, start + length)` within a single source file.
///
/// Spans are stored as offsets rather than line/column pairs because the lexer and parser
/// work on a flat character buffer; conversion to human coordinates happens once, at
/// diagnostic-rendering time, through [LineMap].
///
/// @param start  zero-based inclusive start offset
/// @param length non-negative character count
public record SourceSpan(int start, int length) {

    public SourceSpan {
        if (start < 0) {
            throw new IllegalArgumentException("start must be non-negative but was " + start);
        }
        if (length < 0) {
            throw new IllegalArgumentException("length must be non-negative but was " + length);
        }
    }

    /// Creates a span from inclusive start and exclusive end offsets.
    public static SourceSpan between(int start, int endExclusive) {
        return new SourceSpan(start, endExclusive - start);
    }

    /// Creates an empty span at `position`, used for diagnostics that point between
    /// characters, such as a missing token.
    public static SourceSpan at(int position) {
        return new SourceSpan(position, 0);
    }

    /// Exclusive end offset.
    public int end() {
        return start + length;
    }

    /// Whether this span covers no characters.
    public boolean isEmpty() {
        return length == 0;
    }

    /// The smallest span covering both this span and `other`.
    public SourceSpan union(SourceSpan other) {
        int unionStart = Math.min(start, other.start);
        return SourceSpan.between(unionStart, Math.max(end(), other.end()));
    }

    /// Whether `offset` falls within this span.
    public boolean contains(int offset) {
        return offset >= start && offset < end();
    }

    @Override
    public String toString() {
        return "[" + start + ".." + end() + ")";
    }
}
