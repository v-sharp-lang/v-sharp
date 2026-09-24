package vsharp.runtime;

/// The overflow checks a `checked` context needs and the JVM does not provide.
///
/// C# `checked` arithmetic throws `System.OverflowException` where the JVM opcodes wrap
/// silently. The signed 32/64-bit cases are exactly `Math.addExact` and friends, so the
/// backend calls `java.lang.Math` directly and never reaches this class for them. What is
/// left has no JDK counterpart:
///
///   - the unsigned operators, because V# keeps `uint`/`ulong`/`nuint` in the *signed*
///     `int`/`long` carriers (see [VsUnsigned]), so `Math.addExact` would test the wrong
///     boundary - `0xFFFFFFFFu + 1u` overflows while the signed carriers hold `-1 + 1`;
///   - the range test an explicit conversion needs, which is a comparison against the
///     target's C# range rather than against the carrier's.
///
/// Every failure throws `java.lang.ArithmeticException`, which is the class V# maps
/// `System.OverflowException` to, so a V# `catch (OverflowException)` and a Java
/// `catch (ArithmeticException)` both see it.
public final class VsChecked {

    /// .NET's own message, so a V# stack trace reads like the C# one it mirrors.
    private static final String OVERFLOW = "Arithmetic operation resulted in an overflow.";

    private VsChecked() {
        throw new AssertionError("No instances");
    }

    /// `uint + uint`. The exact sum of two 32-bit unsigned values always fits in a `long`,
    /// so the test is whether anything landed above bit 31.
    public static int addUInt(int left, int right) {
        long result = Integer.toUnsignedLong(left) + Integer.toUnsignedLong(right);
        if ((result >>> Integer.SIZE) != 0L) {
            throw overflow();
        }
        return (int) result;
    }

    /// `uint - uint`. Unsigned subtraction overflows exactly when it would go below zero.
    public static int subtractUInt(int left, int right) {
        if (Integer.compareUnsigned(left, right) < 0) {
            throw overflow();
        }
        return left - right;
    }

    /// `uint * uint`. The exact product of two 32-bit unsigned values fits in a `long`.
    public static int multiplyUInt(int left, int right) {
        long result = Integer.toUnsignedLong(left) * Integer.toUnsignedLong(right);
        if ((result >>> Integer.SIZE) != 0L) {
            throw overflow();
        }
        return (int) result;
    }

    /// `ulong + ulong`. An unsigned sum that wrapped is unsigned-less than either operand.
    public static long addULong(long left, long right) {
        long result = left + right;
        if (Long.compareUnsigned(result, left) < 0) {
            throw overflow();
        }
        return result;
    }

    /// `ulong - ulong`.
    public static long subtractULong(long left, long right) {
        if (Long.compareUnsigned(left, right) < 0) {
            throw overflow();
        }
        return left - right;
    }

    /// `ulong * ulong`. The upper 64 bits of the unsigned product are non-zero exactly when
    /// the value does not fit.
    public static long multiplyULong(long left, long right) {
        if (Math.unsignedMultiplyHigh(left, right) != 0L) {
            throw overflow();
        }
        return left * right;
    }

    /// The range test for a conversion whose source is a signed integral type, already
    /// widened to `long` by the caller. Bounds are the target's C# range.
    public static long range(long value, long min, long max) {
        if (value < min || value > max) {
            throw overflow();
        }
        return value;
    }

    /// The range test for a conversion whose source is `ulong`/`nuint`, whose carrier is a
    /// bit pattern rather than a signed value. Only an upper bound can be violated.
    public static long unsignedRange(long carrier, long maxInclusive) {
        if (Long.compareUnsigned(carrier, maxInclusive) > 0) {
            throw overflow();
        }
        return carrier;
    }

    /// The range test for `float`/`double` to a signed integral target. C# truncates toward
    /// zero and then asks whether the *truncated* value is representable, so `(int)2.9e0` is
    /// legal in a `checked` context while NaN and both infinities are not.
    ///
    /// The upper bound is tested exclusively against `max + 1` because `(double) long`
    /// rounds: `Long.MAX_VALUE` becomes `2^63`, which is one ulp too permissive, while
    /// `2^63` as an exclusive bound is exact for every target in the table.
    public static long fromDouble(double value, long min, long max) {
        double truncated = truncate(value);
        if (!(truncated >= (double) min) || !(truncated < (double) max + 1.0d)) {
            throw overflow();
        }
        return (long) truncated;
    }

    /// The same test for a `ulong`/`nuint` target, whose upper bound has no `long` spelling.
    public static long toUnsignedFromDouble(double value) {
        double truncated = truncate(value);
        if (!(truncated >= 0.0d) || !(truncated < 0x1p64d)) {
            throw overflow();
        }
        return VsUnsigned.fromDouble(truncated);
    }

    /// C#'s conversion rounding: toward zero, leaving NaN and the infinities alone so the
    /// range test rejects them.
    private static double truncate(double value) {
        return value < 0.0d ? Math.ceil(value) : Math.floor(value);
    }

    private static ArithmeticException overflow() {
        return new ArithmeticException(OVERFLOW);
    }
}
