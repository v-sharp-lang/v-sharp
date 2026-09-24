package vsharp.runtime;

import java.util.Objects;

/// C#-faithful `System.Int32` parsing under V#'s deterministic invariant-culture policy.
///
/// The admitted source members are `Parse(string)` and `TryParse(string, out int)`. Their
/// grammar is the invariant form of .NET's default `NumberStyles.Integer`: ASCII decimal
/// digits, one optional ASCII sign, and leading/trailing U+0009..U+000D or U+0020. .NET also
/// accepts one or more trailing NUL terminators, a compatibility detail deliberately retained
/// here. The .NET one-string members normally consult the current culture; V# has no culture
/// model, so the design fixes them to invariant signs and leaves culture/provider/style, Span and
/// UTF-8 overloads outside this runtime surface.
public final class VsInt32 {

    private static final String OVERFLOW_MESSAGE =
            "Value was either too large or too small for an Int32.";

    private VsInt32() {
        throw new AssertionError("No instances");
    }

    /// Parses one invariant decimal integer, preserving C#'s three failure categories.
    public static int parse(String text) {
        if (text == null) {
            // C# raises ArgumentNullException; the curated exception set represents its
            // System.ArgumentException base with IllegalArgumentException.
            throw new IllegalArgumentException("Value cannot be null. (Parameter 's')");
        }
        ParseResult result = scan(text);
        return switch (result.status()) {
            case SUCCESS -> result.value();
            case FORMAT -> throw new VsFormatException(
                    "The input string '" + text + "' was not in a correct format.");
            case OVERFLOW -> throw new ArithmeticException(OVERFLOW_MESSAGE);
        };
    }

    /// Attempts the same parse without using exceptions as control flow. `result[0]` is set
    /// to zero for every failure, matching C#'s `out` contract.
    public static boolean tryParse(String text, int[] result) {
        Objects.requireNonNull(result, "result");
        if (result.length == 0) {
            throw new IllegalArgumentException("result cell must contain one element");
        }
        result[0] = 0;
        if (text == null) {
            return false;
        }
        ParseResult parsed = scan(text);
        if (parsed.status() != ParseStatus.SUCCESS) {
            return false;
        }
        result[0] = parsed.value();
        return true;
    }

    /// Scans syntax to completion even after detecting range overflow. This ordering matters:
    /// .NET reports FormatException for `2147483648x`, but OverflowException for
    /// `2147483648 `, so a malformed suffix outranks an already-overflowing magnitude.
    private static ParseResult scan(String text) {
        int length = text.length();
        int index = 0;
        while (index < length && isNumberWhiteSpace(text.charAt(index))) {
            index++;
        }

        boolean negative = false;
        if (index < length && (text.charAt(index) == '+' || text.charAt(index) == '-')) {
            negative = text.charAt(index) == '-';
            index++;
        }

        int digitsStart = index;
        int limit = negative ? Integer.MIN_VALUE : -Integer.MAX_VALUE;
        int multiplyLimit = limit / 10;
        int value = 0;
        boolean overflow = false;
        while (index < length) {
            char character = text.charAt(index);
            if (character < '0' || character > '9') {
                break;
            }
            int digit = character - '0';
            if (!overflow) {
                if (value < multiplyLimit) {
                    overflow = true;
                } else {
                    int multiplied = value * 10;
                    if (multiplied < limit + digit) {
                        overflow = true;
                    } else {
                        value = multiplied - digit;
                    }
                }
            }
            index++;
        }
        if (index == digitsStart) {
            return ParseResult.format();
        }

        while (index < length && isNumberWhiteSpace(text.charAt(index))) {
            index++;
        }
        // CoreCLR accepts trailing NUL terminators after the number or its trailing white
        // space, but not before digits and not when followed by any non-NUL character.
        while (index < length && text.charAt(index) == '\0') {
            index++;
        }
        if (index != length) {
            return ParseResult.format();
        }
        if (overflow) {
            return ParseResult.overflow();
        }
        return ParseResult.success(negative ? value : -value);
    }

    private static boolean isNumberWhiteSpace(char character) {
        return (character >= 0x0009 && character <= 0x000D) || character == 0x0020;
    }

    private enum ParseStatus {
        SUCCESS,
        FORMAT,
        OVERFLOW
    }

    private record ParseResult(ParseStatus status, int value) {
        private static ParseResult success(int value) {
            return new ParseResult(ParseStatus.SUCCESS, value);
        }

        private static ParseResult format() {
            return new ParseResult(ParseStatus.FORMAT, 0);
        }

        private static ParseResult overflow() {
            return new ParseResult(ParseStatus.OVERFLOW, 0);
        }
    }
}
