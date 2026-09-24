package vsharp.runtime;

/// Runtime representation of the C# `System.Index` value type.
///
/// A `VsIndex` is either an offset from the start of a collection (`i`) or an offset
/// from its end (`^i`). Offsets are non-negative; `^0` denotes the position one past
/// the last element, exactly as in C#.
///
/// @param value  non-negative offset
/// @param fromEnd whether [#value] counts backwards from the end
public record VsIndex(int value, boolean fromEnd) {

    /// Start of a sequence, i.e. `0`.
    public static final VsIndex START = new VsIndex(0, false);

    /// End of a sequence, i.e. `^0`.
    public static final VsIndex END = new VsIndex(0, true);

    public VsIndex {
        if (value < 0) {
            throw new IndexOutOfBoundsException("Index value must be non-negative but was " + value);
        }
    }

    /// Creates an index counting from the start.
    public static VsIndex fromStart(int value) {
        return new VsIndex(value, false);
    }

    /// Creates an index counting from the end, the lowering target of the `^` operator.
    public static VsIndex fromEnd(int value) {
        return new VsIndex(value, true);
    }

    /// Resolves this index against a concrete length.
    ///
    /// Mirrors `System.Index.GetOffset(int)`: no bounds validation is performed here,
    /// because the subsequent element access performs it and must report the failure.
    public int getOffset(int length) {
        return fromEnd ? length - value : value;
    }

    @Override
    public String toString() {
        return fromEnd ? "^" + value : Integer.toString(value);
    }
}
