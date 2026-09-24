package vsharp.runtime;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Objects;

/// Exact C# `decimal` policy over the JDK's arbitrary-precision decimal primitives.
///
/// A CLR decimal is a sign, a 96-bit unsigned coefficient, and a scale from 0 through 28.
/// `BigDecimal` is the JVM carrier, but every value entering or produced by generated code
/// passes through this class so arbitrary precision never leaks into V# semantics. Scale
/// reduction considers the complete discarded remainder and rounds midpoint ties to even.
public final class VsDecimal {

    public static final int MAX_SCALE = 28;

    private static final BigInteger MAX_COEFFICIENT = BigInteger.ONE.shiftLeft(96)
            .subtract(BigInteger.ONE);
    private static final BigInteger TEN = BigInteger.TEN;
    private static final MathContext SINGLE_INPUT = new MathContext(7, RoundingMode.HALF_EVEN);
    private static final MathContext DOUBLE_INPUT = new MathContext(15, RoundingMode.HALF_EVEN);
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private VsDecimal() {
        throw new AssertionError("No instances");
    }

    /// Converts the exact token spelling (without its `m` suffix) to a representable value.
    public static BigDecimal fromLiteral(String value) {
        return fromLiteral(new BigDecimal(Objects.requireNonNull(value, "value")));
    }

    /// Compiler-side overload retaining the token's already parsed exact value and scale.
    public static BigDecimal fromLiteral(BigDecimal value) {
        return fit(Objects.requireNonNull(value, "value"));
    }

    /// The value of `default(decimal)`.
    public static BigDecimal zero() {
        return ZERO;
    }

    public static BigDecimal negate(BigDecimal value) {
        return checked(value).negate();
    }

    public static BigDecimal add(BigDecimal left, BigDecimal right) {
        return fit(checked(left).add(checked(right)));
    }

    public static BigDecimal subtract(BigDecimal left, BigDecimal right) {
        return fit(checked(left).subtract(checked(right)));
    }

    public static BigDecimal multiply(BigDecimal left, BigDecimal right) {
        return fit(checked(left).multiply(checked(right)));
    }

    /// Divides by growing the coefficient only while both the 96-bit and scale bounds allow.
    /// The last discarded remainder is rounded to even, and a quotient that was ever
    /// inexact is unscaled in the same way as `System.Decimal`.
    public static BigDecimal divide(BigDecimal left, BigDecimal right) {
        BigDecimal dividend = checked(left);
        BigDecimal divisor = checked(right);
        if (divisor.signum() == 0) {
            throw new ArithmeticException("Division by zero");
        }

        boolean negative = dividend.signum() != 0
                && dividend.signum() != divisor.signum();
        BigInteger numerator = dividend.unscaledValue().abs();
        BigInteger denominator = divisor.unscaledValue().abs();
        int naturalScale = dividend.scale() - divisor.scale();
        Division initial = divisionAtScale(numerator, denominator,
                dividend.scale(), divisor.scale(), naturalScale);
        boolean unscale = initial.remainder().signum() != 0;

        int scale = Math.max(0, naturalScale);
        Division current = divisionAtScale(numerator, denominator,
                dividend.scale(), divisor.scale(), scale);
        if (current.quotient().compareTo(MAX_COEFFICIENT) > 0) {
            throw overflow();
        }

        while (current.remainder().signum() != 0 && scale < MAX_SCALE) {
            Division next = divisionAtScale(numerator, denominator,
                    dividend.scale(), divisor.scale(), scale + 1);
            if (next.quotient().compareTo(MAX_COEFFICIENT) > 0) {
                break;
            }
            scale++;
            current = next;
        }

        BigInteger coefficient = current.quotient();
        if (current.remainder().signum() != 0) {
            coefficient = roundToEven(coefficient, current.remainder(), current.divisor());
            while (coefficient.compareTo(MAX_COEFFICIENT) > 0) {
                if (scale == 0) {
                    throw overflow();
                }
                scale--;
                current = divisionAtScale(numerator, denominator,
                        dividend.scale(), divisor.scale(), scale);
                coefficient = roundToEven(current.quotient(), current.remainder(),
                        current.divisor());
            }
        }

        BigDecimal result = signed(coefficient, scale, negative);
        return unscale ? unscale(result) : result;
    }

    public static BigDecimal remainder(BigDecimal left, BigDecimal right) {
        BigDecimal dividend = checked(left);
        BigDecimal divisor = checked(right);
        if (divisor.signum() == 0) {
            throw new ArithmeticException("Division by zero");
        }
        if (dividend.signum() == 0) {
            return dividend;
        }
        BigDecimal result = fit(dividend.remainder(divisor));
        // System.Decimal preserves the aligned operand scale for an exact non-zero-dividend
        // remainder. BigDecimal instead collapses some such zeros to the dividend's scale.
        return result.signum() == 0
                ? result.setScale(Math.max(dividend.scale(), divisor.scale())) : result;
    }

    public static int compare(BigDecimal left, BigDecimal right) {
        return checked(left).compareTo(checked(right));
    }

    /// Reduces a value's stored scale under one rounding direction, the operation behind
    /// `System.Math.Round(decimal, ...)`.
    ///
    /// A decimal keeps its scale, so rounding is a scale *reduction*, never a normalisation:
    /// a value already at or below the requested scale is returned untouched (`1.00m` rounded
    /// to three decimals stays `1.00m`, scale 2), and a value above it lands on exactly the
    /// requested scale (`0.999...m` rounded to three becomes `1.000m`, scale 3). The caller
    /// has already validated `scale`; the result re-enters the envelope check because
    /// rounding away from zero can grow the coefficient.
    public static BigDecimal roundToScale(BigDecimal value, int scale, RoundingMode mode) {
        BigDecimal decimal = checked(value);
        Objects.requireNonNull(mode, "mode");
        if (scale < 0 || scale > MAX_SCALE) {
            throw new IllegalArgumentException("Rounding scale out of range: " + scale);
        }
        return decimal.scale() <= scale ? decimal : fit(decimal.setScale(scale, mode));
    }

    /// C#'s invariant default decimal layout is fixed-point and retains stored scale.
    public static String toDisplayString(BigDecimal value) {
        return checked(value).toPlainString();
    }

    /// Creates a fresh object-boundary value while retaining coefficient and scale.
    public static Object box(BigDecimal value) {
        BigDecimal decimal = checked(value);
        return new BigDecimal(decimal.unscaledValue(), decimal.scale());
    }

    /// Accepts the public Java-boundary carrier used by emitted decimal descriptors.
    public static BigDecimal unbox(Object value) {
        if (value instanceof BigDecimal decimal) {
            return checked(decimal);
        }
        throw new ClassCastException("Not a boxed V# decimal: "
                + Objects.requireNonNull(value, "value").getClass().getName());
    }

    public static BigDecimal fromInt(int value) {
        return BigDecimal.valueOf(value);
    }

    public static BigDecimal fromUInt(int value) {
        return BigDecimal.valueOf(Integer.toUnsignedLong(value));
    }

    public static BigDecimal fromLong(long value) {
        return BigDecimal.valueOf(value);
    }

    public static BigDecimal fromULong(long value) {
        return new BigDecimal(new BigInteger(Long.toUnsignedString(value)));
    }

    public static BigDecimal fromFloat(float value) {
        if (!Float.isFinite(value)) {
            throw overflow();
        }
        BigDecimal converted = new BigDecimal(Float.toString(value)).round(SINGLE_INPUT);
        return canonicalBinaryInput(converted);
    }

    public static BigDecimal fromDouble(double value) {
        if (!Double.isFinite(value)) {
            throw overflow();
        }
        BigDecimal converted = BigDecimal.valueOf(value).round(DOUBLE_INPUT);
        return canonicalBinaryInput(converted);
    }

    public static byte toSByte(BigDecimal value) {
        return checkedInteger(value, BigInteger.valueOf(Byte.MIN_VALUE),
                BigInteger.valueOf(Byte.MAX_VALUE)).byteValue();
    }

    public static byte toByte(BigDecimal value) {
        return checkedInteger(value, BigInteger.ZERO, BigInteger.valueOf(255)).byteValue();
    }

    public static short toShort(BigDecimal value) {
        return checkedInteger(value, BigInteger.valueOf(Short.MIN_VALUE),
                BigInteger.valueOf(Short.MAX_VALUE)).shortValue();
    }

    public static short toUShort(BigDecimal value) {
        return checkedInteger(value, BigInteger.ZERO, BigInteger.valueOf(65535)).shortValue();
    }

    public static char toChar(BigDecimal value) {
        return (char) checkedInteger(value, BigInteger.ZERO,
                BigInteger.valueOf(Character.MAX_VALUE)).intValue();
    }

    public static int toInt(BigDecimal value) {
        return checkedInteger(value, BigInteger.valueOf(Integer.MIN_VALUE),
                BigInteger.valueOf(Integer.MAX_VALUE)).intValue();
    }

    public static int toUInt(BigDecimal value) {
        return checkedInteger(value, BigInteger.ZERO,
                BigInteger.ONE.shiftLeft(32).subtract(BigInteger.ONE)).intValue();
    }

    public static long toLong(BigDecimal value) {
        return checkedInteger(value, BigInteger.valueOf(Long.MIN_VALUE),
                BigInteger.valueOf(Long.MAX_VALUE)).longValue();
    }

    public static long toULong(BigDecimal value) {
        return checkedInteger(value, BigInteger.ZERO,
                BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)).longValue();
    }

    public static float toFloat(BigDecimal value) {
        return checked(value).floatValue();
    }

    public static double toDouble(BigDecimal value) {
        return checked(value).doubleValue();
    }

    private static BigDecimal canonicalBinaryInput(BigDecimal value) {
        BigDecimal fitted = fit(value);
        return fitted.signum() == 0 ? ZERO : unscale(fitted);
    }

    private static BigInteger checkedInteger(BigDecimal value, BigInteger min, BigInteger max) {
        BigInteger integer = checked(value).setScale(0, RoundingMode.DOWN).toBigIntegerExact();
        if (integer.compareTo(min) < 0 || integer.compareTo(max) > 0) {
            throw overflow();
        }
        return integer;
    }

    /// Fits an exact result into the CLR coefficient/scale envelope in one rounding step.
    private static BigDecimal fit(BigDecimal value) {
        if (value.signum() != 0 && value.precision() - value.scale() > 29) {
            throw overflow();
        }
        if (value.signum() != 0 && value.precision() - value.scale() < -29) {
            return ZERO.setScale(MAX_SCALE);
        }
        BigDecimal exact = value.scale() < 0 ? value.setScale(0) : value;
        for (int scale = Math.min(exact.scale(), MAX_SCALE); scale >= 0; scale--) {
            BigDecimal candidate = exact.setScale(scale, RoundingMode.HALF_EVEN);
            if (candidate.unscaledValue().abs().compareTo(MAX_COEFFICIENT) <= 0) {
                return candidate;
            }
        }
        throw overflow();
    }

    private static BigDecimal checked(BigDecimal value) {
        BigDecimal decimal = Objects.requireNonNull(value, "value");
        if (decimal.scale() < 0 || decimal.scale() > MAX_SCALE
                || decimal.unscaledValue().abs().compareTo(MAX_COEFFICIENT) > 0) {
            return fit(decimal);
        }
        return decimal;
    }

    private static Division divisionAtScale(BigInteger numerator, BigInteger denominator,
            int numeratorScale, int denominatorScale, int resultScale) {
        int shift = denominatorScale - numeratorScale + resultScale;
        BigInteger scaledNumerator = numerator;
        BigInteger scaledDenominator = denominator;
        if (shift >= 0) {
            scaledNumerator = scaledNumerator.multiply(TEN.pow(shift));
        } else {
            scaledDenominator = scaledDenominator.multiply(TEN.pow(-shift));
        }
        BigInteger[] quotient = scaledNumerator.divideAndRemainder(scaledDenominator);
        return new Division(quotient[0], quotient[1], scaledDenominator);
    }

    private static BigInteger roundToEven(BigInteger quotient, BigInteger remainder,
            BigInteger divisor) {
        int midpoint = remainder.shiftLeft(1).compareTo(divisor);
        return midpoint > 0 || midpoint == 0 && quotient.testBit(0)
                ? quotient.add(BigInteger.ONE) : quotient;
    }

    private static BigDecimal signed(BigInteger coefficient, int scale, boolean negative) {
        return new BigDecimal(negative ? coefficient.negate() : coefficient, scale);
    }

    private static BigDecimal unscale(BigDecimal value) {
        BigInteger coefficient = value.unscaledValue();
        int scale = value.scale();
        while (scale > 0) {
            BigInteger[] divided = coefficient.divideAndRemainder(TEN);
            if (divided[1].signum() != 0) {
                break;
            }
            coefficient = divided[0];
            scale--;
        }
        return new BigDecimal(coefficient, scale);
    }

    private static ArithmeticException overflow() {
        return new ArithmeticException("Decimal overflow");
    }

    private record Division(BigInteger quotient, BigInteger remainder, BigInteger divisor) {}
}
