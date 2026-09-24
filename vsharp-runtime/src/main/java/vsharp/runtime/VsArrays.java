package vsharp.runtime;

import java.lang.reflect.Array;
import java.util.Objects;

/// Array operations whose C# meaning differs from the single JVM instruction that looks like
/// them.
///
/// A C# rectangular array (`int[,]`) is carried as a JVM array of arrays, so `arraylength`
/// answers the *first* dimension while C# `Length` is the total element count. The difference
/// is invisible for a vector and wrong for everything else, which is why the compiler emits a
/// call here for any rank above one instead of the instruction.
public final class VsArrays {

    private VsArrays() {
        throw new AssertionError("No instances");
    }

    /// The total number of elements of a rectangular array of `rank` dimensions, which is what
    /// C# `Array.Length` returns.
    ///
    /// The array is rectangular by construction - the compiler only emits this for a type
    /// declared `T[,...]`, and such an array is only ever created with one shape - so the
    /// first element of each level describes the whole level.
    public static int length(Object array, int rank) {
        Objects.requireNonNull(array, "array");
        if (rank < 1) {
            throw new IllegalArgumentException("rank must be positive but was " + rank);
        }
        int total = 1;
        Object level = array;
        for (int dimension = 0; dimension < rank; dimension++) {
            int size = Array.getLength(level);
            total *= size;
            if (dimension + 1 < rank) {
                if (size == 0) {
                    return 0;
                }
                level = Array.get(level, 0);
            }
        }
        return total;
    }

    /// The length of one dimension, which is C#'s `Array.GetLength(int)`.
    public static int getLength(Object array, int dimension) {
        Objects.requireNonNull(array, "array");
        if (dimension < 0) {
            throw new IndexOutOfBoundsException("dimension must not be negative: " + dimension);
        }
        Object level = array;
        for (int index = 0; index < dimension; index++) {
            level = Array.get(level, 0);
        }
        return Array.getLength(level);
    }
}
