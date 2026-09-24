package vsharp.runtime;

import java.util.Objects;

/// C#-faithful `System.Double` parsing under V#'s deterministic invariant-culture policy.
///
/// The admitted source members are `Parse(string)` and `TryParse(string, out double)`. The
/// scanner accepts the invariant form of .NET's default `NumberStyles.Float | AllowThousands`
/// grammar and rejects Java-only hexadecimal and type-suffix forms before delegating decimal
/// conversion to [Double#parseDouble]. Culture/provider/style, Span and UTF-8 overloads remain
/// outside the curated surface.
public final class VsDouble {

    /// .NET 10's canonical result for every accepted spelling of NaN, including `+NaN` and
    /// `-NaN`. Java's `Double.NaN` has the opposite sign bit.
    private static final long DOTNET_NAN_BITS = 0xFFF8000000000000L;

    private VsDouble() {
        throw new AssertionError("No instances");
    }

    /// Parses one invariant floating-point value. Finite overflow and underflow are successful
    /// results in modern .NET: the JDK conversion already returns signed infinity/zero for them.
    public static double parse(String text) {
        if (text == null) {
            // the design represents ArgumentNullException by its ArgumentException base carrier.
            throw new IllegalArgumentException("Value cannot be null. (Parameter 's')");
        }
        ParseResult result = scan(text);
        if (!result.success()) {
            throw new VsFormatException(
                    "The input string '" + text + "' was not in a correct format.");
        }
        return result.value();
    }

    /// Attempts the same parse without exceptions as control flow. `result[0]` is positive
    /// zero on every failure, while a successful negative underflow remains negative zero.
    public static boolean tryParse(String text, double[] result) {
        Objects.requireNonNull(result, "result");
        if (result.length == 0) {
            throw new IllegalArgumentException("result cell must contain one element");
        }
        result[0] = 0.0d;
        if (text == null) {
            return false;
        }
        ParseResult parsed = scan(text);
        if (!parsed.success()) {
            return false;
        }
        result[0] = parsed.value();
        return true;
    }

    private static ParseResult scan(String text) {
        int length = text.length();
        int start = 0;
        while (start < length && isNumberWhiteSpace(text.charAt(start))) {
            start++;
        }

        int index = start;
        boolean negative = false;
        if (index < length && (text.charAt(index) == '+' || text.charAt(index) == '-')) {
            negative = text.charAt(index) == '-';
            index++;
        }

        // Special symbols permit outer number whitespace and an optional sign, but .NET's
        // special-symbol path does not share the numeric parser's trailing-NUL compatibility.
        int specialEnd = length;
        while (specialEnd > index && isNumberWhiteSpace(text.charAt(specialEnd - 1))) {
            specialEnd--;
        }
        if (matchesAsciiIgnoreCase(text, index, specialEnd, "nan")) {
            return ParseResult.success(Double.longBitsToDouble(DOTNET_NAN_BITS));
        }
        if (matchesAsciiIgnoreCase(text, index, specialEnd, "infinity")) {
            return ParseResult.success(
                    negative ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY);
        }

        boolean hasIntegerDigit = false;
        boolean hasDigit = false;
        boolean hasGroupSeparator = false;
        while (index < length) {
            char character = text.charAt(index);
            if (isAsciiDigit(character)) {
                hasIntegerDigit = true;
                hasDigit = true;
                index++;
            } else if (character == ',' && hasIntegerDigit) {
                // NumberStyles.AllowThousands does not validate group widths: `1,,2` and
                // `12,34` are both accepted, but a separator cannot precede the first digit.
                hasGroupSeparator = true;
                index++;
            } else {
                break;
            }
        }
        if (index < length && text.charAt(index) == '.') {
            index++;
            while (index < length && isAsciiDigit(text.charAt(index))) {
                hasDigit = true;
                index++;
            }
        }
        if (!hasDigit) {
            return ParseResult.failure();
        }
        if (index < length && (text.charAt(index) == 'e' || text.charAt(index) == 'E')) {
            index++;
            if (index < length && (text.charAt(index) == '+' || text.charAt(index) == '-')) {
                index++;
            }
            int exponentStart = index;
            while (index < length && isAsciiDigit(text.charAt(index))) {
                index++;
            }
            if (index == exponentStart) {
                return ParseResult.failure();
            }
        }

        int numberEnd = index;
        while (index < length && isNumberWhiteSpace(text.charAt(index))) {
            index++;
        }
        while (index < length && text.charAt(index) == 0) {
            index++;
        }
        if (index != length) {
            return ParseResult.failure();
        }

        String normalized = text.substring(start, numberEnd);
        if (hasGroupSeparator) {
            normalized = normalized.replace(",", "");
        }
        try {
            return ParseResult.success(Double.parseDouble(normalized));
        } catch (NumberFormatException ignored) {
            // The explicit scanner owns the public grammar. This is a defensive boundary for
            // any JDK conversion limit, and still surfaces as C#'s FormatException category.
            return ParseResult.failure();
        }
    }

    /// ASCII-only by design. Java's Unicode case folding would admit spellings that .NET's
    /// invariant `NaN` and `Infinity` symbols do not contain.
    private static boolean matchesAsciiIgnoreCase(
            String text, int start, int end, String expected) {
        if (end - start != expected.length()) {
            return false;
        }
        for (int index = 0; index < expected.length(); index++) {
            char actual = text.charAt(start + index);
            if (actual >= 'A' && actual <= 'Z') {
                actual = (char) (actual + ('a' - 'A'));
            }
            if (actual != expected.charAt(index)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAsciiDigit(char character) {
        return character >= '0' && character <= '9';
    }

    private static boolean isNumberWhiteSpace(char character) {
        return (character >= 0x0009 && character <= 0x000D) || character == 0x0020;
    }

    private record ParseResult(boolean success, double value) {
        private static ParseResult success(double value) {
            return new ParseResult(true, value);
        }

        private static ParseResult failure() {
            return new ParseResult(false, 0.0d);
        }
    }
}
