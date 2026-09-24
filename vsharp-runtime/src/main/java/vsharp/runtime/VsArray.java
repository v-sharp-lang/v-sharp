package vsharp.runtime;

import java.util.Arrays;
import java.util.Objects;

/// C#-faithful `System.Array` statics for the curated core library.
///
/// None of these members is a thin forward to `java.util.Arrays`; three measured
/// differences force real work:
///
///   - **Unsigned element types share a signed JVM carrier.** `byte`, `ushort`, `uint` and
///     `ulong` are carried as JVM `byte`, `short`, `int` and `long`, so sorting them with
///     the signed comparison would order `200` before `5`. Each unsigned sort flips the
///     sign bit, sorts, and flips it back, which is an exact order-isomorphism between the
///     unsigned and signed orderings.
///   - **NaN sorts first in C# and last in Java.** .NET orders `NaN, -Infinity, ..., -0, +0,
///     ..., +Infinity`; the JDK's total order puts NaN last. The floating sorts therefore
///     rotate the trailing NaN block to the front. Signed zero agrees in both (`-0 < +0`).
///   - **`IndexOf` uses `Equals`, not `==`.** NaN is found by NaN, and `-0.0` is found by
///     `0.0`. That is neither Java's `==` (which fails for NaN) nor `Double.equals`/
///     `doubleToLongBits` (which would wrongly separate `-0.0` from `0.0`), so the
///     predicate is spelled out.
///
/// `Sort` over `string[]` is deliberately absent: .NET orders strings with the current
/// culture, which V# has no model for. `IndexOf` over `string[]` *is* present because
/// it is ordinal - proved against the .NET 10 oracle with a culture-equal but ordinally
/// different pair, which it reports as absent.
public final class VsArray {

    private VsArray() {
        throw new AssertionError("No instances");
    }

    // ---------------------------------------------------------------- sorting

    public static void sortSByte(byte[] array) {
        requireArray(array);
        Arrays.sort(array);
    }

    /// `byte` is unsigned in C# but carried as a signed JVM `byte`.
    public static void sortByte(byte[] array) {
        requireArray(array);
        for (int i = 0; i < array.length; i++) {
            array[i] ^= Byte.MIN_VALUE;
        }
        Arrays.sort(array);
        for (int i = 0; i < array.length; i++) {
            array[i] ^= Byte.MIN_VALUE;
        }
    }

    public static void sortShort(short[] array) {
        requireArray(array);
        Arrays.sort(array);
    }

    public static void sortUShort(short[] array) {
        requireArray(array);
        for (int i = 0; i < array.length; i++) {
            array[i] ^= Short.MIN_VALUE;
        }
        Arrays.sort(array);
        for (int i = 0; i < array.length; i++) {
            array[i] ^= Short.MIN_VALUE;
        }
    }

    public static void sortInt(int[] array) {
        requireArray(array);
        Arrays.sort(array);
    }

    public static void sortUInt(int[] array) {
        requireArray(array);
        for (int i = 0; i < array.length; i++) {
            array[i] ^= Integer.MIN_VALUE;
        }
        Arrays.sort(array);
        for (int i = 0; i < array.length; i++) {
            array[i] ^= Integer.MIN_VALUE;
        }
    }

    public static void sortLong(long[] array) {
        requireArray(array);
        Arrays.sort(array);
    }

    public static void sortULong(long[] array) {
        requireArray(array);
        for (int i = 0; i < array.length; i++) {
            array[i] ^= Long.MIN_VALUE;
        }
        Arrays.sort(array);
        for (int i = 0; i < array.length; i++) {
            array[i] ^= Long.MIN_VALUE;
        }
    }

    /// `char` is unsigned in both languages, so the JDK ordering is already C#'s.
    public static void sortChar(char[] array) {
        requireArray(array);
        Arrays.sort(array);
    }

    public static void sortFloat(float[] array) {
        requireArray(array);
        Arrays.sort(array);
        int nan = 0;
        while (nan < array.length && Float.isNaN(array[array.length - 1 - nan])) {
            nan++;
        }
        if (nan > 0 && nan < array.length) {
            float[] tail = Arrays.copyOfRange(array, array.length - nan, array.length);
            System.arraycopy(array, 0, array, nan, array.length - nan);
            System.arraycopy(tail, 0, array, 0, nan);
        }
    }

    public static void sortDouble(double[] array) {
        requireArray(array);
        Arrays.sort(array);
        int nan = 0;
        while (nan < array.length && Double.isNaN(array[array.length - 1 - nan])) {
            nan++;
        }
        if (nan > 0 && nan < array.length) {
            double[] tail = Arrays.copyOfRange(array, array.length - nan, array.length);
            System.arraycopy(array, 0, array, nan, array.length - nan);
            System.arraycopy(tail, 0, array, 0, nan);
        }
    }

    // -------------------------------------------------------------- reversing

    public static void reverseByte(byte[] array) {
        requireArray(array);
        for (int low = 0, high = array.length - 1; low < high; low++, high--) {
            byte swap = array[low];
            array[low] = array[high];
            array[high] = swap;
        }
    }

    public static void reverseShort(short[] array) {
        requireArray(array);
        for (int low = 0, high = array.length - 1; low < high; low++, high--) {
            short swap = array[low];
            array[low] = array[high];
            array[high] = swap;
        }
    }

    public static void reverseInt(int[] array) {
        requireArray(array);
        for (int low = 0, high = array.length - 1; low < high; low++, high--) {
            int swap = array[low];
            array[low] = array[high];
            array[high] = swap;
        }
    }

    public static void reverseLong(long[] array) {
        requireArray(array);
        for (int low = 0, high = array.length - 1; low < high; low++, high--) {
            long swap = array[low];
            array[low] = array[high];
            array[high] = swap;
        }
    }

    public static void reverseChar(char[] array) {
        requireArray(array);
        for (int low = 0, high = array.length - 1; low < high; low++, high--) {
            char swap = array[low];
            array[low] = array[high];
            array[high] = swap;
        }
    }

    public static void reverseFloat(float[] array) {
        requireArray(array);
        for (int low = 0, high = array.length - 1; low < high; low++, high--) {
            float swap = array[low];
            array[low] = array[high];
            array[high] = swap;
        }
    }

    public static void reverseDouble(double[] array) {
        requireArray(array);
        for (int low = 0, high = array.length - 1; low < high; low++, high--) {
            double swap = array[low];
            array[low] = array[high];
            array[high] = swap;
        }
    }

    public static void reverseString(String[] array) {
        requireArray(array);
        for (int low = 0, high = array.length - 1; low < high; low++, high--) {
            String swap = array[low];
            array[low] = array[high];
            array[high] = swap;
        }
    }

    // -------------------------------------------------------------- searching

    /// Integral searches compare bits, so one helper serves both the signed and the
    /// unsigned element type sharing a carrier.
    public static int indexOfByte(byte[] array, byte value) {
        requireArray(array);
        for (int i = 0; i < array.length; i++) {
            if (array[i] == value) {
                return i;
            }
        }
        return -1;
    }

    public static int indexOfShort(short[] array, short value) {
        requireArray(array);
        for (int i = 0; i < array.length; i++) {
            if (array[i] == value) {
                return i;
            }
        }
        return -1;
    }

    public static int indexOfInt(int[] array, int value) {
        requireArray(array);
        for (int i = 0; i < array.length; i++) {
            if (array[i] == value) {
                return i;
            }
        }
        return -1;
    }

    public static int indexOfLong(long[] array, long value) {
        requireArray(array);
        for (int i = 0; i < array.length; i++) {
            if (array[i] == value) {
                return i;
            }
        }
        return -1;
    }

    public static int indexOfChar(char[] array, char value) {
        requireArray(array);
        for (int i = 0; i < array.length; i++) {
            if (array[i] == value) {
                return i;
            }
        }
        return -1;
    }

    /// `Equals` semantics: NaN matches NaN, and `-0.0` matches `0.0`.
    public static int indexOfFloat(float[] array, float value) {
        requireArray(array);
        for (int i = 0; i < array.length; i++) {
            if (array[i] == value || (Float.isNaN(array[i]) && Float.isNaN(value))) {
                return i;
            }
        }
        return -1;
    }

    public static int indexOfDouble(double[] array, double value) {
        requireArray(array);
        for (int i = 0; i < array.length; i++) {
            if (array[i] == value || (Double.isNaN(array[i]) && Double.isNaN(value))) {
                return i;
            }
        }
        return -1;
    }

    /// Ordinal, null-tolerant string search, matching `EqualityComparer<String>.Default`.
    public static int indexOfString(String[] array, String value) {
        requireArray(array);
        for (int i = 0; i < array.length; i++) {
            if (Objects.equals(array[i], value)) {
                return i;
            }
        }
        return -1;
    }

    /// C# names this parameter `array` in every member above; V#'s corelib has no
    /// `ArgumentNullException` carrier, so it arrives as `System.ArgumentException`
    /// with .NET's message text.
    private static void requireArray(Object array) {
        if (array == null) {
            throw new IllegalArgumentException("Value cannot be null. (Parameter 'array')");
        }
    }
}
