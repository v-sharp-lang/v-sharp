package vsharp.runtime;

import java.math.BigDecimal;
import java.math.RoundingMode;

/// C#-faithful `System.Math` members for the overloads the curated core library admits.
///
/// The signed integral forms of `Abs` are the ones whose JVM counterpart cannot be reused
/// as-is: Java's `Math.abs(int.MinValue)` and `Math.abs(long.MinValue)` silently return
/// the negative input, while C# throws `System.OverflowException`. The JDK's
/// `Math.absExact` throws `ArithmeticException` on exactly that input, which is the JVM
/// exception `System.OverflowException` maps to, so the int/long/nint forms delegate
/// directly. The sbyte/short forms are carried in the JVM's signed byte/short and have
/// the same negative-minimum hazard with no `absExact` counterpart, so they test before
/// negating. Floating and decimal forms cannot overflow: C# `Math.Abs(float.NaN)` and
/// `Math.Abs(double.NaN)` return NaN exactly like Java's `Math.abs`, and a valid
/// `decimal` value always has a valid same-scale absolute value.
///
/// `Max`/`Min` have no overflow hazard. The signed and floating forms reuse the JDK
/// `Math.max`/`Math.min` whose NaN and signed-zero behaviour match the .NET 10 oracle.
/// The unsigned forms must compare unsigned bits, because their carriers are signed. The
/// decimal forms compare with `VsDecimal.compare` and replicate the oracle's operand
/// choice when values are equal (`Max` returns the first operand, `Min` the second).
/// `Clamp` validates `min > max` before inspecting the value, then returns `min`, `max`,
/// or the original value object in that order. Returning an operand rather than rebuilding
/// it preserves floating signed-zero/NaN bits and decimal scale.
public final class VsMath {

    private VsMath() {
        throw new AssertionError("No instances");
    }

    // ---- Abs ---------------------------------------------------------------------

    public static byte absSByte(byte value) {
        if (value == Byte.MIN_VALUE) {
            throw new ArithmeticException("Math.Abs overflow: sbyte.MinValue");
        }
        return value < 0 ? (byte) -value : value;
    }

    public static short absShort(short value) {
        if (value == Short.MIN_VALUE) {
            throw new ArithmeticException("Math.Abs overflow: short.MinValue");
        }
        return value < 0 ? (short) -value : value;
    }

    public static int absInt(int value) {
        return Math.absExact(value);
    }

    public static long absLong(long value) {
        return Math.absExact(value);
    }

    /// `nint` is 64-bit in V# by declaration (R5), so it shares the long carrier and the
    /// long overflow rule.
    public static long absNInt(long value) {
        return Math.absExact(value);
    }

    public static float absFloat(float value) {
        return Math.abs(value);
    }

    public static double absDouble(double value) {
        return Math.abs(value);
    }

    /// `decimal` values reaching this helper have already been validated by the compiler
    /// against `VsDecimal`'s coefficient/scale envelope, so the plain same-scale negate is
    /// the exact C# `decimal.Abs` operation.
    public static BigDecimal absDecimal(BigDecimal value) {
        return value.signum() < 0 ? value.negate() : value;
    }

    // ---- Max ---------------------------------------------------------------------

    public static byte maxSByte(byte a, byte b) {
        return (byte) Math.max(a, b);
    }

    public static short maxShort(short a, short b) {
        return (short) Math.max(a, b);
    }

    public static int maxInt(int a, int b) {
        return Math.max(a, b);
    }

    public static long maxLong(long a, long b) {
        return Math.max(a, b);
    }

    public static long maxNInt(long a, long b) {
        return Math.max(a, b);
    }

    public static byte maxByte(byte a, byte b) {
        return (byte) Math.max(Byte.toUnsignedInt(a), Byte.toUnsignedInt(b));
    }

    public static short maxUShort(short a, short b) {
        return (short) Math.max(Short.toUnsignedInt(a), Short.toUnsignedInt(b));
    }

    public static int maxUInt(int a, int b) {
        return Integer.compareUnsigned(a, b) >= 0 ? a : b;
    }

    public static long maxULong(long a, long b) {
        return Long.compareUnsigned(a, b) >= 0 ? a : b;
    }

    public static long maxNUInt(long a, long b) {
        return Long.compareUnsigned(a, b) >= 0 ? a : b;
    }

    public static float maxFloat(float a, float b) {
        return Math.max(a, b);
    }

    public static double maxDouble(double a, double b) {
        return Math.max(a, b);
    }

    public static BigDecimal maxDecimal(BigDecimal a, BigDecimal b) {
        return VsDecimal.compare(a, b) >= 0 ? a : b;
    }

    // ---- Min ---------------------------------------------------------------------

    public static byte minSByte(byte a, byte b) {
        return (byte) Math.min(a, b);
    }

    public static short minShort(short a, short b) {
        return (short) Math.min(a, b);
    }

    public static int minInt(int a, int b) {
        return Math.min(a, b);
    }

    public static long minLong(long a, long b) {
        return Math.min(a, b);
    }

    public static long minNInt(long a, long b) {
        return Math.min(a, b);
    }

    public static byte minByte(byte a, byte b) {
        return (byte) Math.min(Byte.toUnsignedInt(a), Byte.toUnsignedInt(b));
    }

    public static short minUShort(short a, short b) {
        return (short) Math.min(Short.toUnsignedInt(a), Short.toUnsignedInt(b));
    }

    public static int minUInt(int a, int b) {
        return Integer.compareUnsigned(a, b) <= 0 ? a : b;
    }

    public static long minULong(long a, long b) {
        return Long.compareUnsigned(a, b) <= 0 ? a : b;
    }

    public static long minNUInt(long a, long b) {
        return Long.compareUnsigned(a, b) <= 0 ? a : b;
    }

    public static float minFloat(float a, float b) {
        return Math.min(a, b);
    }

    public static double minDouble(double a, double b) {
        return Math.min(a, b);
    }

    public static BigDecimal minDecimal(BigDecimal a, BigDecimal b) {
        return VsDecimal.compare(a, b) < 0 ? a : b;
    }

    // ---- Clamp -------------------------------------------------------------------

    public static byte clampSByte(byte value, byte min, byte max) {
        requireValidClampRange(min > max);
        return value < min ? min : value > max ? max : value;
    }

    public static byte clampByte(byte value, byte min, byte max) {
        int unsignedValue = Byte.toUnsignedInt(value);
        int unsignedMin = Byte.toUnsignedInt(min);
        int unsignedMax = Byte.toUnsignedInt(max);
        requireValidClampRange(unsignedMin > unsignedMax);
        return unsignedValue < unsignedMin ? min : unsignedValue > unsignedMax ? max : value;
    }

    public static short clampShort(short value, short min, short max) {
        requireValidClampRange(min > max);
        return value < min ? min : value > max ? max : value;
    }

    public static short clampUShort(short value, short min, short max) {
        int unsignedValue = Short.toUnsignedInt(value);
        int unsignedMin = Short.toUnsignedInt(min);
        int unsignedMax = Short.toUnsignedInt(max);
        requireValidClampRange(unsignedMin > unsignedMax);
        return unsignedValue < unsignedMin ? min : unsignedValue > unsignedMax ? max : value;
    }

    public static int clampInt(int value, int min, int max) {
        requireValidClampRange(min > max);
        return value < min ? min : value > max ? max : value;
    }

    public static int clampUInt(int value, int min, int max) {
        requireValidClampRange(Integer.compareUnsigned(min, max) > 0);
        return Integer.compareUnsigned(value, min) < 0 ? min
                : Integer.compareUnsigned(value, max) > 0 ? max : value;
    }

    public static long clampLong(long value, long min, long max) {
        requireValidClampRange(min > max);
        return value < min ? min : value > max ? max : value;
    }

    public static long clampULong(long value, long min, long max) {
        requireValidClampRange(Long.compareUnsigned(min, max) > 0);
        return Long.compareUnsigned(value, min) < 0 ? min
                : Long.compareUnsigned(value, max) > 0 ? max : value;
    }

    public static long clampNInt(long value, long min, long max) {
        return clampLong(value, min, max);
    }

    public static long clampNUInt(long value, long min, long max) {
        return clampULong(value, min, max);
    }

    public static float clampFloat(float value, float min, float max) {
        requireValidClampRange(min > max);
        return value < min ? min : value > max ? max : value;
    }

    public static double clampDouble(double value, double min, double max) {
        requireValidClampRange(min > max);
        return value < min ? min : value > max ? max : value;
    }

    public static BigDecimal clampDecimal(BigDecimal value, BigDecimal min, BigDecimal max) {
        requireValidClampRange(VsDecimal.compare(min, max) > 0);
        return VsDecimal.compare(value, min) < 0 ? min
                : VsDecimal.compare(value, max) > 0 ? max : value;
    }

    private static void requireValidClampRange(boolean invalid) {
        if (invalid) {
            throw new IllegalArgumentException("min must be less than or equal to max");
        }
    }

    // ---- Sign --------------------------------------------------------------------

    public static int signSByte(byte value) {
        return Integer.signum(value);
    }

    public static int signShort(short value) {
        return Integer.signum(value);
    }

    public static int signInt(int value) {
        return Integer.signum(value);
    }

    public static int signLong(long value) {
        return Long.signum(value);
    }

    /// `nint` is 64-bit in V# (R5), so it shares the long carrier and sign rule.
    public static int signNInt(long value) {
        return Long.signum(value);
    }

    /// C# `Math.Sign(float.NaN)` throws `ArithmeticException`; Java's `Math.signum`
    /// returns NaN, so the float/double forms test for NaN before delegating.
    public static int signFloat(float value) {
        if (Float.isNaN(value)) {
            throw new ArithmeticException("Math.Sign(float.NaN)");
        }
        return (int) Math.signum(value);
    }

    public static int signDouble(double value) {
        if (Double.isNaN(value)) {
            throw new ArithmeticException("Math.Sign(double.NaN)");
        }
        return (int) Math.signum(value);
    }

    public static int signDecimal(BigDecimal value) {
        return value.signum();
    }

    // ---- Floor / Ceiling / Truncate ----------------------------------------------

    public static double floor(double value) {
        return Math.floor(value);
    }

    public static double ceiling(double value) {
        return Math.ceil(value);
    }

    /// C# truncates toward zero. Java has no direct `truncate`, so the direction selects
    /// `ceil` for negative values and `floor` otherwise; this also preserves the .NET
    /// signed-zero and NaN behaviour measured against the oracle.
    public static double truncate(double value) {
        return value < 0.0d ? Math.ceil(value) : Math.floor(value);
    }

    // ---- Transcendentals ---------------------------------------------------------

    public static double sqrt(double value) {
        return Math.sqrt(value);
    }

    /// Java and .NET disagree on six cells in the measured NaN/infinity edge matrix:
    /// .NET defines positive one raised to any exponent as one, and negative one raised
    /// to either infinity as one. Java returns NaN in those cases. Guard precisely those
    /// rules. The measured runtimes also differ only in the sign bit when a negative NaN
    /// base is raised to +/-1, so clear the NaN result's non-semantic sign bit only in
    /// those two cells to reproduce the .NET raw result as well.
    public static double pow(double value, double power) {
        if (value == 1.0d || (value == -1.0d && Double.isInfinite(power))) {
            return 1.0d;
        }
        double result = Math.pow(value, power);
        if (Double.isNaN(result) && Math.abs(power) == 1.0d) {
            long positiveNaNBits = Double.doubleToRawLongBits(result) & Long.MAX_VALUE;
            return Double.longBitsToDouble(positiveNaNBits);
        }
        return result;
    }

    public static double log(double value) {
        return Math.log(value);
    }

    /// The direct trigonometric trio intentionally uses [Math], not [StrictMath]. The
    /// A .NET 10/JDK 25 comparison covers 32,794 edge, arbitrary-bit and
    /// bounded finite inputs. Every result category and every signed-zero/NaN edge bit
    /// agrees; the 235 finite differences are all exactly one ULP. StrictMath is less
    /// compatible on the same corpus (2,497 one-ULP differences).
    public static double sin(double value) {
        return Math.sin(value);
    }

    public static double cos(double value) {
        return Math.cos(value);
    }

    public static double tan(double value) {
        return Math.tan(value);
    }

    // ---- Round -------------------------------------------------------------------

    /// The largest `digits` a `double` rounding accepts; `decimals` for the decimal forms is
    /// bounded by [VsDecimal#MAX_SCALE] instead. Both are C#'s own limits, and both reject
    /// a negative count: the .NET check is unsigned, so `-1` and `int.MinValue` fail the
    /// same way as `16`.
    private static final int MAX_ROUNDING_DIGITS = 15;

    /// Above this magnitude a `double` has no fractional part left to round, so the digit
    /// forms return the value untouched - measurably without even validating the mode.
    private static final double DOUBLE_ROUND_LIMIT = 1e16d;

    /// The scaling factors the digit forms multiply by. Rounding is defined as "scale, round
    /// to integer, unscale", so the result carries the representation error of both
    /// operations exactly as .NET's does; computing `Math.pow(10, digits)` instead would not
    /// reproduce it for every digit count.
    private static final double[] ROUND_POWER_10 = {
        1e0d, 1e1d, 1e2d, 1e3d, 1e4d, 1e5d, 1e6d, 1e7d,
        1e8d, 1e9d, 1e10d, 1e11d, 1e12d, 1e13d, 1e14d, 1e15d
    };

    /// `Math.Round(double)` - to even, the mode the JVM's `rint` already implements.
    public static double round(double value) {
        return Math.rint(value);
    }

    /// `Math.Round(double, MidpointRounding)`. The mode is validated for every value,
    /// including NaN and the infinities, which is the one place this form differs
    /// observably from passing `0` digits to [#roundDigitsMode].
    public static double roundMode(double value, int mode) {
        return applyRounding(value, mode);
    }

    /// `Math.Round(double, int)` - the digit form at the default to-even mode.
    public static double roundDigits(double value, int digits) {
        return roundDigitsMode(value, digits, MIDPOINT_TO_EVEN);
    }

    /// `Math.Round(double, int, MidpointRounding)`.
    ///
    /// `digits` is validated first and unconditionally; the mode is only validated when the
    /// value actually reaches the rounding step, so an invalid mode over a value at or above
    /// [#DOUBLE_ROUND_LIMIT] (or NaN) returns the value rather than throwing. That ordering
    /// is measured against the .NET 10 oracle, not assumed.
    public static double roundDigitsMode(double value, int digits, int mode) {
        if (Integer.compareUnsigned(digits, MAX_ROUNDING_DIGITS) > 0) {
            throw invalidRoundingDigits();
        }
        if (Math.abs(value) < DOUBLE_ROUND_LIMIT) {
            double power10 = ROUND_POWER_10[digits];
            return applyRounding(value * power10, mode) / power10;
        }
        return value;
    }

    /// `Math.Round(decimal)`.
    public static BigDecimal roundDecimal(BigDecimal value) {
        return roundDecimalDigitsMode(value, 0, MIDPOINT_TO_EVEN);
    }

    /// `Math.Round(decimal, MidpointRounding)`.
    public static BigDecimal roundDecimalMode(BigDecimal value, int mode) {
        return roundDecimalDigitsMode(value, 0, mode);
    }

    /// `Math.Round(decimal, int)`.
    public static BigDecimal roundDecimalDigits(BigDecimal value, int decimals) {
        return roundDecimalDigitsMode(value, decimals, MIDPOINT_TO_EVEN);
    }

    /// `Math.Round(decimal, int, MidpointRounding)`. Unlike the `double` form, the mode is
    /// validated for every value because a decimal always has a scale to reduce.
    public static BigDecimal roundDecimalDigitsMode(BigDecimal value, int decimals, int mode) {
        if (Integer.compareUnsigned(decimals, VsDecimal.MAX_SCALE) > 0) {
            throw invalidRoundingDecimals();
        }
        return VsDecimal.roundToScale(value, decimals, roundingModeOf(mode));
    }

    /// The `System.MidpointRounding` members, by their C# values.
    private static final int MIDPOINT_TO_EVEN = 0;
    private static final int MIDPOINT_AWAY_FROM_ZERO = 1;
    private static final int MIDPOINT_TO_ZERO = 2;
    private static final int MIDPOINT_TO_NEGATIVE_INFINITY = 3;
    private static final int MIDPOINT_TO_POSITIVE_INFINITY = 4;

    /// Rounds an already scaled `double` to an integral value under one midpoint mode.
    private static double applyRounding(double value, int mode) {
        return switch (mode) {
            case MIDPOINT_TO_EVEN -> Math.rint(value);
            case MIDPOINT_AWAY_FROM_ZERO -> roundAwayFromZero(value);
            case MIDPOINT_TO_ZERO -> truncate(value);
            case MIDPOINT_TO_NEGATIVE_INFINITY -> Math.floor(value);
            case MIDPOINT_TO_POSITIVE_INFINITY -> Math.ceil(value);
            default -> throw invalidMidpointRounding(mode);
        };
    }

    /// The one mode with no JDK counterpart: split off the integral part and step away from
    /// zero when the discarded fraction reaches a half. Working from the fraction rather than
    /// adding `0.5` keeps the signed zeros, the infinities and NaN intact.
    private static double roundAwayFromZero(double value) {
        double integral = truncate(value);
        double fraction = value - integral;
        return Math.abs(fraction) >= 0.5d ? integral + Math.signum(fraction) : integral;
    }

    /// The [RoundingMode] implementing a `System.MidpointRounding` member over a decimal.
    /// `HALF_UP` is the JDK's name for rounding a tie away from zero; `DOWN`, `FLOOR` and
    /// `CEILING` never see a tie because they discard the whole remainder.
    private static RoundingMode roundingModeOf(int mode) {
        return switch (mode) {
            case MIDPOINT_TO_EVEN -> RoundingMode.HALF_EVEN;
            case MIDPOINT_AWAY_FROM_ZERO -> RoundingMode.HALF_UP;
            case MIDPOINT_TO_ZERO -> RoundingMode.DOWN;
            case MIDPOINT_TO_NEGATIVE_INFINITY -> RoundingMode.FLOOR;
            case MIDPOINT_TO_POSITIVE_INFINITY -> RoundingMode.CEILING;
            default -> throw invalidMidpointRounding(mode);
        };
    }

    /// C# raises `ArgumentOutOfRangeException`, a type V# cannot spell as distinct from its
    /// base: the corelib exception set has one `System.ArgumentException`, carried by
    /// `java.lang.IllegalArgumentException`.
    /// The two digit-count messages are .NET 10's own text, measured from the runtime rather
    /// than paraphrased: the `double` and `decimal` forms word the same condition differently.
    private static IllegalArgumentException invalidRoundingDigits() {
        return new IllegalArgumentException(
                "Rounding digits must be between 0 and " + MAX_ROUNDING_DIGITS
                        + ", inclusive. (Parameter 'digits')");
    }

    private static IllegalArgumentException invalidRoundingDecimals() {
        return new IllegalArgumentException(
                "Decimal can only round to between 0 and " + VsDecimal.MAX_SCALE
                        + " digits of precision. (Parameter 'decimals')");
    }

    private static IllegalArgumentException invalidMidpointRounding(int mode) {
        return new IllegalArgumentException(
                "The value '" + mode + "' is not valid for this usage of the type MidpointRounding."
                        + " (Parameter 'mode')");
    }
}
