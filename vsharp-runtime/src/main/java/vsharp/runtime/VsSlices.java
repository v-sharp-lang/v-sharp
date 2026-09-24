package vsharp.runtime;

import java.util.Arrays;
import java.util.Objects;

/// Element and slice access for the C# index/range operators.
///
/// `a[^1]` lowers to an [VsIndex#getOffset(int)] call followed by an ordinary array load,
/// but `a[1..^1]` lowers to one of the `slice` overloads below. Consistent with C#, slicing
/// an array copies; slicing a `string` returns a substring.
public final class VsSlices {

    private VsSlices() {
        throw new AssertionError("No instances");
    }

    /// Resolves an index against a length and validates it, so that an out-of-range
    /// `^0` or oversized offset fails with the same shape of error as a direct access.
    public static int offset(VsIndex index, int length) {
        int resolved = Objects.requireNonNull(index, "index").getOffset(length);
        if (resolved < 0 || resolved >= length) {
            throw new IndexOutOfBoundsException(
                    "Index " + index + " is out of bounds for length " + length);
        }
        return resolved;
    }

    /// Slices a `string`, matching `string`'s range indexer.
    public static String slice(String source, VsRange range) {
        int[] offsetAndLength = range.getOffsetAndLength(Objects.requireNonNull(source, "source").length());
        return source.substring(offsetAndLength[0], offsetAndLength[0] + offsetAndLength[1]);
    }

    /// Slices a reference array, preserving the runtime component type.
    public static <T> T[] slice(T[] source, VsRange range) {
        int[] offsetAndLength = range.getOffsetAndLength(Objects.requireNonNull(source, "source").length);
        return Arrays.copyOfRange(source, offsetAndLength[0], offsetAndLength[0] + offsetAndLength[1]);
    }

    /// Slices a `boolean[]`, copying the selected elements.
    public static boolean[] slice(boolean[] source, VsRange range) {
        int[] offsetAndLength = range.getOffsetAndLength(Objects.requireNonNull(source, "source").length);
        return Arrays.copyOfRange(source, offsetAndLength[0], offsetAndLength[0] + offsetAndLength[1]);
    }

    /// Slices a `byte[]`, copying the selected elements.
    public static byte[] slice(byte[] source, VsRange range) {
        int[] offsetAndLength = range.getOffsetAndLength(Objects.requireNonNull(source, "source").length);
        return Arrays.copyOfRange(source, offsetAndLength[0], offsetAndLength[0] + offsetAndLength[1]);
    }

    /// Slices a `short[]`, copying the selected elements.
    public static short[] slice(short[] source, VsRange range) {
        int[] offsetAndLength = range.getOffsetAndLength(Objects.requireNonNull(source, "source").length);
        return Arrays.copyOfRange(source, offsetAndLength[0], offsetAndLength[0] + offsetAndLength[1]);
    }

    /// Slices a `char[]`, copying the selected elements.
    public static char[] slice(char[] source, VsRange range) {
        int[] offsetAndLength = range.getOffsetAndLength(Objects.requireNonNull(source, "source").length);
        return Arrays.copyOfRange(source, offsetAndLength[0], offsetAndLength[0] + offsetAndLength[1]);
    }

    /// Slices a `int[]`, copying the selected elements.
    public static int[] slice(int[] source, VsRange range) {
        int[] offsetAndLength = range.getOffsetAndLength(Objects.requireNonNull(source, "source").length);
        return Arrays.copyOfRange(source, offsetAndLength[0], offsetAndLength[0] + offsetAndLength[1]);
    }

    /// Slices a `long[]`, copying the selected elements.
    public static long[] slice(long[] source, VsRange range) {
        int[] offsetAndLength = range.getOffsetAndLength(Objects.requireNonNull(source, "source").length);
        return Arrays.copyOfRange(source, offsetAndLength[0], offsetAndLength[0] + offsetAndLength[1]);
    }

    /// Slices a `float[]`, copying the selected elements.
    public static float[] slice(float[] source, VsRange range) {
        int[] offsetAndLength = range.getOffsetAndLength(Objects.requireNonNull(source, "source").length);
        return Arrays.copyOfRange(source, offsetAndLength[0], offsetAndLength[0] + offsetAndLength[1]);
    }

    /// Slices a `double[]`, copying the selected elements.
    public static double[] slice(double[] source, VsRange range) {
        int[] offsetAndLength = range.getOffsetAndLength(Objects.requireNonNull(source, "source").length);
        return Arrays.copyOfRange(source, offsetAndLength[0], offsetAndLength[0] + offsetAndLength[1]);
    }
}
