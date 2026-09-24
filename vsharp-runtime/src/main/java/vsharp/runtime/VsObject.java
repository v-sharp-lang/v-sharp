package vsharp.runtime;

import java.math.BigDecimal;
import java.math.BigInteger;

/// The universal members `Equals(object)` and `GetHashCode()`.
///
/// A JVM carrier answers both questions already, but four of them answer differently from the
/// C# type they carry, so neither member can be lowered to `java.lang.Object`'s method alone:
///
///   - `Double`/`Float` compare raw bits, so Java reports `0.0 != -0.0` and `NaN == NaN`, while
///     C# `Equals` is `==` widened by an explicit NaN case - it reports both as equal;
///   - `Boolean` hashes to 1231/1237 and `Character` hashes to its code unit, where C# uses
///     0/1 and `value | value << 16`;
///   - `BigDecimal` equality is scale-sensitive, so Java separates `1.0m` from `1.00m` that C#
///     compares numerically, and its hash follows the representation rather than the value;
///   - a NaN or a negative zero must normalise before hashing, as .NET does explicitly.
///
/// Every value here is pinned against the .NET 10 oracle in `runtime.object` tests. `string`
/// is the one carrier whose own methods are exact for `Equals`; its hash is Java's, because
/// .NET randomises string hashing per process and guarantees only that equal values hash
/// equally - a guarantee `String.hashCode` also keeps.
///
/// A null receiver reaches an ordinary `NullPointerException`, which is the JVM class carrying
/// `System.NullReferenceException`, matching the C# throw.
public final class VsObject {

    /// .NET's `Decimal` hash normalises the value, then folds the 96-bit coefficient with the
    /// sign/scale word: `flags ^ hi ^ mid ^ lo`.
    private static final int DECIMAL_SIGN = 0x80000000;
    private static final int DECIMAL_SCALE_SHIFT = 16;
    private static final long DOUBLE_SPECIAL = 0x7FF0000000000000L;
    private static final long DOUBLE_MAGNITUDE = 0x7FFFFFFFFFFFFFFFL;
    private static final int FLOAT_SPECIAL = 0x7F800000;
    private static final int FLOAT_MAGNITUDE = 0x7FFFFFFF;
    private static final int WORD_BITS = 32;

    private VsObject() {
        throw new AssertionError("No instances");
    }

    /// `object.Equals(object)`, dispatching on the receiver's runtime carrier.
    ///
    /// C# runs the receiver's own override, so a boxed `double`, `float` or `decimal` must use
    /// its C# rule rather than `java.lang.Object`'s. Every other carrier - including a Java
    /// object reached through interop - is exact under `equals` already.
    public static boolean equals(Object receiver, Object other) {
        if (receiver instanceof Double value) {
            return equals(value.doubleValue(), other);
        }
        if (receiver instanceof Float value) {
            return equals(value.floatValue(), other);
        }
        if (receiver instanceof BigDecimal value) {
            return equals(value, other);
        }
        return receiver.equals(other);
    }

    /// `int.Equals(object)`: true only for a boxed `int` of the same value.
    public static boolean equals(int receiver, Object other) {
        return other instanceof Integer value && value.intValue() == receiver;
    }

    /// `long.Equals(object)`.
    public static boolean equals(long receiver, Object other) {
        return other instanceof Long value && value.longValue() == receiver;
    }

    /// `bool.Equals(object)`.
    public static boolean equals(boolean receiver, Object other) {
        return other instanceof Boolean value && value.booleanValue() == receiver;
    }

    /// `char.Equals(object)`.
    public static boolean equals(char receiver, Object other) {
        return other instanceof Character value && value.charValue() == receiver;
    }

    /// `double.Equals(object)`: `==` plus the explicit NaN case, so `0.0` equals `-0.0` and
    /// `NaN` equals `NaN` - neither of which `Double.equals` reports.
    public static boolean equals(double receiver, Object other) {
        if (!(other instanceof Double value)) {
            return false;
        }
        double operand = value.doubleValue();
        return receiver == operand || (Double.isNaN(receiver) && Double.isNaN(operand));
    }

    /// `float.Equals(object)`, on the same rule as `double`.
    public static boolean equals(float receiver, Object other) {
        if (!(other instanceof Float value)) {
            return false;
        }
        float operand = value.floatValue();
        return receiver == operand || (Float.isNaN(receiver) && Float.isNaN(operand));
    }

    /// `decimal.Equals(object)`: numeric equality, so trailing zeros do not separate values.
    public static boolean equals(BigDecimal receiver, Object other) {
        return other instanceof BigDecimal value && receiver.compareTo(value) == 0;
    }

    /// `object.GetType()`. V#'s `System.Type` is `java.lang.Class`, so the answer is the
    /// receiver's real JVM carrier and stays usable with every reflective JDK API. A `string`
    /// receiver arrives here too, since its carrier is `java.lang.String`.
    public static Class<?> type(Object receiver) {
        return receiver.getClass();
    }

    /// `int.GetType()`. Value receivers report the *boxed* carrier the same value reports once
    /// it is assigned to an `object`, never `int.class`: one C# type, one `Type`.
    public static Class<?> type(int receiver) {
        return Integer.class;
    }

    /// `long.GetType()`, on the same rule as `int`.
    public static Class<?> type(long receiver) {
        return Long.class;
    }

    /// `bool.GetType()`, on the same rule as `int`.
    public static Class<?> type(boolean receiver) {
        return Boolean.class;
    }

    /// `char.GetType()`, on the same rule as `int`.
    public static Class<?> type(char receiver) {
        return Character.class;
    }

    /// `float.GetType()`, on the same rule as `int`.
    public static Class<?> type(float receiver) {
        return Float.class;
    }

    /// `double.GetType()`, on the same rule as `int`.
    public static Class<?> type(double receiver) {
        return Double.class;
    }

    /// `decimal.GetType()`. `BigDecimal` is `System.Decimal`'s carrier, and the receiver's
    /// own class is returned rather than a constant because a subclass would be a real carrier.
    public static Class<?> type(BigDecimal receiver) {
        return receiver.getClass();
    }

    /// `object.GetHashCode()`, dispatching on the receiver's runtime carrier.
    public static int hash(Object receiver) {
        if (receiver instanceof Double value) {
            return hash(value.doubleValue());
        }
        if (receiver instanceof Float value) {
            return hash(value.floatValue());
        }
        if (receiver instanceof Boolean value) {
            return hash(value.booleanValue());
        }
        if (receiver instanceof Character value) {
            return hash(value.charValue());
        }
        if (receiver instanceof BigDecimal value) {
            return hash(value);
        }
        return receiver.hashCode();
    }

    /// `int.GetHashCode()`: the value itself, which is also `Integer.hashCode`.
    public static int hash(int receiver) {
        return receiver;
    }

    /// `long.GetHashCode()`: the two halves folded, which is also `Long.hashCode`.
    public static int hash(long receiver) {
        return (int) (receiver ^ (receiver >>> WORD_BITS));
    }

    /// `bool.GetHashCode()`: 1 or 0, not Java's 1231/1237.
    public static int hash(boolean receiver) {
        return receiver ? 1 : 0;
    }

    /// `char.GetHashCode()`: the code unit in both halves, not Java's bare code unit.
    public static int hash(char receiver) {
        return receiver | (receiver << 16);
    }

    /// `double.GetHashCode()`: the folded bits, after collapsing every NaN and both zeros the
    /// way .NET does - Java keeps `-0.0` and a canonical NaN distinct instead.
    public static int hash(double receiver) {
        long bits = Double.doubleToRawLongBits(receiver);
        if (((bits - 1) & DOUBLE_MAGNITUDE) >= DOUBLE_SPECIAL) {
            bits &= DOUBLE_SPECIAL;
        }
        return (int) bits ^ (int) (bits >> WORD_BITS);
    }

    /// `float.GetHashCode()`, on the same rule as `double`.
    public static int hash(float receiver) {
        int bits = Float.floatToRawIntBits(receiver);
        if (((bits - 1) & FLOAT_MAGNITUDE) >= FLOAT_SPECIAL) {
            bits &= FLOAT_SPECIAL;
        }
        return bits;
    }

    /// `decimal.GetHashCode()`: .NET removes trailing zeros, then folds the sign/scale word
    /// with the three coefficient words. Equal values therefore hash equally even when their
    /// scales differ, and the result matches .NET exactly rather than only its contract.
    public static int hash(BigDecimal receiver) {
        BigDecimal value = VsDecimal.unbox(receiver);
        if (value.unscaledValue().signum() == 0) {
            return 0;
        }
        BigDecimal stripped = value.stripTrailingZeros();
        if (stripped.scale() < 0) {
            stripped = stripped.setScale(0);
        }
        BigInteger coefficient = stripped.unscaledValue().abs();
        int flags = stripped.scale() << DECIMAL_SCALE_SHIFT;
        if (value.signum() < 0) {
            flags |= DECIMAL_SIGN;
        }
        int low = coefficient.intValue();
        int mid = coefficient.shiftRight(WORD_BITS).intValue();
        int high = coefficient.shiftRight(2 * WORD_BITS).intValue();
        return flags ^ high ^ mid ^ low;
    }
}
