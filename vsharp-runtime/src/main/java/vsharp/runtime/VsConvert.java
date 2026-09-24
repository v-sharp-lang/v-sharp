package vsharp.runtime;

/// C#-faithful radix conversion for the two culture-independent `System.Convert` members
/// admitted by the curated core library.
public final class VsConvert {

    private static final String INVALID_BASE = "Invalid Base.";
    private static final String MINUS_NON_DECIMAL =
            "String cannot contain a minus sign if the base is not 10.";
    private static final String NO_DIGITS = "Could not find any recognizable digits.";
    private static final String TRAILING =
            "Additional non-parsable characters are at the end of the string.";
    private static final String INT_OVERFLOW =
            "Value was either too large or too small for an Int32.";
    private static final String UINT_OVERFLOW =
            "Value was either too large or too small for a UInt32.";
    private static final String EMPTY = "Specified argument was out of the range of valid values. "
            + "(Parameter 'Index was out of range. Must be non-negative and less than the size "
            + "of the collection.')";

    private VsConvert() {
        throw new AssertionError("No instances");
    }

    /// Renders an `int` in base 2, 8, 10 or 16. Non-decimal negative values use their
    /// 32-bit two's-complement representation, exactly like .NET.
    public static String toString(int value, int toBase) {
        requireBase(toBase);
        return toBase == 10
                ? Integer.toString(value)
                : Integer.toUnsignedString(value, toBase);
    }

    /// Parses an `int` from base 2, 8, 10 or 16 using `System.Convert`'s deliberately strict
    /// grammar: no whitespace, an optional plus in every base, a minus only in base 10, and
    /// an optional `0x` prefix only in base 16. Non-decimal input is a UInt32 bit pattern.
    public static int toInt32(String value, int fromBase) {
        requireBase(fromBase);
        if (value == null) {
            return 0;
        }
        if (value.isEmpty()) {
            throw new IllegalArgumentException(EMPTY);
        }

        int index = 0;
        boolean negative = false;
        char first = value.charAt(0);
        if (first == '-') {
            if (fromBase != 10) {
                throw new IllegalArgumentException(MINUS_NON_DECIMAL);
            }
            negative = true;
            index++;
        } else if (first == '+') {
            index++;
        }

        if (fromBase == 16 && index + 1 < value.length()
                && value.charAt(index) == '0'
                && (value.charAt(index + 1) == 'x' || value.charAt(index + 1) == 'X')) {
            index += 2;
        }

        long limit = fromBase == 10
                ? (negative ? 2_147_483_648L : Integer.MAX_VALUE)
                : 0xFFFF_FFFFL;
        long magnitude = 0;
        int digits = 0;
        boolean overflow = false;
        for (; index < value.length(); index++) {
            int digit = digit(value.charAt(index));
            if (digit < 0 || digit >= fromBase) {
                throw new VsFormatException(digits == 0 ? NO_DIGITS : TRAILING);
            }
            digits++;
            if (!overflow) {
                if (magnitude > (limit - digit) / fromBase) {
                    overflow = true;
                } else {
                    magnitude = magnitude * fromBase + digit;
                }
            }
        }

        if (digits == 0) {
            throw new VsFormatException(NO_DIGITS);
        }
        if (overflow) {
            throw new ArithmeticException(fromBase == 10 ? INT_OVERFLOW : UINT_OVERFLOW);
        }
        return negative ? (int) -magnitude : (int) magnitude;
    }

    private static void requireBase(int radix) {
        if (radix != 2 && radix != 8 && radix != 10 && radix != 16) {
            throw new IllegalArgumentException(INVALID_BASE);
        }
    }

    private static int digit(char value) {
        if (value >= '0' && value <= '9') {
            return value - '0';
        }
        if (value >= 'a' && value <= 'f') {
            return value - 'a' + 10;
        }
        if (value >= 'A' && value <= 'F') {
            return value - 'A' + 10;
        }
        return -1;
    }
}
