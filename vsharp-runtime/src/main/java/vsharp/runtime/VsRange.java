package vsharp.runtime;

import java.util.Objects;

/// Runtime representation of the C# `System.Range` value type: a start-inclusive,
/// end-exclusive pair of [VsIndex] values.
///
/// @param start inclusive lower bound
/// @param end   exclusive upper bound
public record VsRange(VsIndex start, VsIndex end) {

    /// The `..` range covering an entire sequence.
    public static final VsRange ALL = new VsRange(VsIndex.START, VsIndex.END);

    public VsRange {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
    }

    /// Lowering target for `start..`.
    public static VsRange startAt(VsIndex start) {
        return new VsRange(start, VsIndex.END);
    }

    /// Lowering target for `..end`.
    public static VsRange endAt(VsIndex end) {
        return new VsRange(VsIndex.START, end);
    }

    /// Resolves this range against a concrete length, validating bounds the way
    /// `System.Range.GetOffsetAndLength(int)` does.
    ///
    /// @return the resolved `[offset, length]` pair
    /// @throws IndexOutOfBoundsException if the resolved range does not fit `length`
    public int[] getOffsetAndLength(int length) {
        int startOffset = start.getOffset(length);
        int endOffset = end.getOffset(length);
        if (startOffset < 0 || endOffset > length || startOffset > endOffset) {
            throw new IndexOutOfBoundsException(
                    "Range " + this + " is out of bounds for length " + length);
        }
        return new int[] {startOffset, endOffset - startOffset};
    }

    @Override
    public String toString() {
        return start + ".." + end;
    }
}
