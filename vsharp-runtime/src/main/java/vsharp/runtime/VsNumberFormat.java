package vsharp.runtime;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/// The curated standard numeric format specifiers `D`, `X` and `F`.
///
/// V# has no current-culture model, so these follow the rule's deterministic invariant contract:
/// the decimal point is `.`, the negative sign is `-`, and no group separator, currency
/// symbol or percent sign is ever produced. [VsFormat] remains the authority for default
/// rendering, which a null or empty specifier delegates to unchanged.
///
/// Three measured behaviours drive the implementation and none of them is guessable:
///
///   - **The precision is a minimum, never a width.** `(-42).ToString("X4")` is `FFFFFFD6`,
///     eight digits, because the carrier's two's-complement value already needs them.
///   - **`double`/`float` round half to even; `decimal` rounds half away from zero.** The
///     .NET 10 oracle gives `2.5.ToString("F0")` as `2` but `2.5m.ToString("F0")` as `3`,
///     and `0.125.ToString("F2")` as `0.12` against `0.125m.ToString("F2")` as `0.13`.
///     Binary values round on their *exact* value, so `2.345` prints `2.35` while `2.355`
///     prints `2.35`: the stored doubles straddle the decimal literals in opposite
///     directions.
///   - **Sign survives a zero magnitude for binary types but not for `decimal`.** Both
///     `(-0.0)` and `(-0.001)` print `-0.00`, while `(-0.004m)` prints `0.00`.
///
/// Everything outside `D`/`X`/`F` is refused rather than approximated. `G`, `N`, `C`, `E`,
/// `P` and `R` are valid in C# but select group separators, currency or exponential layout
/// that a culture-free build cannot render faithfully, and custom format strings such as
/// `"D2X"` are a separate composite surface. An unrecognised single letter reproduces
/// .NET's own `FormatException` text.
public final class VsNumberFormat {

    /// .NET's message for a format string whose first character is not a known specifier.
    private static final String INVALID = "Format specifier was invalid.";

    /// .NET's documented upper bound for a precision specifier.
    private static final int MAX_PRECISION = 999999999;

    private VsNumberFormat() {
        throw new AssertionError("No instances");
    }

    /// A relative clause naming why `spec` cannot be honoured, or `null` when it can.
    ///
    /// The clause is written to follow the specifier text in a sentence that ends
    /// "is not supported by this V# build", which is the shape of the compiler's VS20002
    /// diagnostic. The compiler calls this so an unsupported clause in an interpolated
    /// string - whose format text is always a literal - fails the build instead of the run.
    /// Which specifiers are supported therefore lives in exactly one place: a compile-time
    /// check that disagreed with the runtime engine would be worse than no check at all.
    ///
    /// A `null` result does not promise the call will succeed. `D` and `X` remain
    /// integral-only, and a floating receiver still raises at run time exactly as C# does,
    /// because refusing that at compile time would reject programs C# accepts.
    public static String unsupportedReason(String spec) {
        Specifier specifier = Specifier.parse(spec);
        if (specifier == null) {
            return null;
        }
        if (specifier.custom()) {
            return "which is a custom format string rather than one of the standard "
                    + "D, X and F specifiers,";
        }
        return switch (specifier.letter()) {
            case 'D', 'd', 'X', 'x', 'F', 'f' -> null;
            case 'G', 'g', 'N', 'n', 'C', 'c', 'E', 'e', 'P', 'p', 'R', 'r', 'B', 'b' ->
                "which needs a culture model this build does not have, leaving only "
                        + "D, X and F,";
            default -> "which is not a recognised standard numeric format specifier,";
        };
    }

    // ------------------------------------------------------------- integral

    public static String formatSByte(byte value, String spec) {
        return integral(spec, value, value & 0xFFL, 2, true);
    }

    public static String formatByte(byte value, String spec) {
        return integral(spec, value & 0xFFL, value & 0xFFL, 2, false);
    }

    public static String formatShort(short value, String spec) {
        return integral(spec, value, value & 0xFFFFL, 4, true);
    }

    public static String formatUShort(short value, String spec) {
        return integral(spec, value & 0xFFFFL, value & 0xFFFFL, 4, false);
    }

    public static String formatInt(int value, String spec) {
        return integral(spec, value, value & 0xFFFFFFFFL, 8, true);
    }

    public static String formatUInt(int value, String spec) {
        return integral(spec, value & 0xFFFFFFFFL, value & 0xFFFFFFFFL, 8, false);
    }

    public static String formatLong(long value, String spec) {
        return integral(spec, value, value, 16, true);
    }

    public static String formatULong(long value, String spec) {
        return integral(spec, value, value, 16, false);
    }

    /// @param signedValue the value as C# sees it, used by `D` and `F`
    /// @param carrierBits the same bits read unsigned, used by `X`
    /// @param hexDigits the carrier's width in hexadecimal digits
    /// @param signed whether `signedValue` may be negative
    private static String integral(String spec, long signedValue, long carrierBits,
            int hexDigits, boolean signed) {
        Specifier specifier = Specifier.parse(spec);
        if (specifier == null) {
            return defaultIntegral(signedValue, carrierBits, signed);
        }
        if (specifier.custom()) {
            throw refuse(specifier);
        }
        String digits = signed ? Long.toString(signedValue) : Long.toUnsignedString(carrierBits);
        return switch (specifier.letter()) {
            case 'D', 'd' -> padDecimal(digits, specifier.precision(0));
            case 'X', 'x' -> hexadecimal(carrierBits, hexDigits, specifier);
            case 'F', 'f' -> fixedIntegral(digits, specifier.precision(2));
            default -> throw refuse(specifier);
        };
    }

    private static String defaultIntegral(long signedValue, long carrierBits, boolean signed) {
        return signed ? Long.toString(signedValue) : Long.toUnsignedString(carrierBits);
    }

    /// `X` renders the carrier's two's-complement bits, so the width is the carrier's and the
    /// precision only ever adds leading zeros.
    private static String hexadecimal(long carrierBits, int hexDigits, Specifier specifier) {
        String hex = Long.toHexString(carrierBits);
        if (hexDigits < 16 && hex.length() > hexDigits) {
            hex = hex.substring(hex.length() - hexDigits);
        }
        hex = pad(hex, specifier.precision(0));
        return specifier.letter() == 'X' ? hex.toUpperCase(Locale.ROOT) : hex;
    }

    private static String padDecimal(String digits, int precision) {
        if (digits.startsWith("-")) {
            return "-" + pad(digits.substring(1), precision);
        }
        return pad(digits, precision);
    }

    /// `F` over an integral value never rounds; it only appends a fraction of zeros.
    private static String fixedIntegral(String digits, int precision) {
        if (precision == 0) {
            return digits;
        }
        return digits + "." + "0".repeat(precision);
    }

    // ------------------------------------------------------------- floating

    public static String formatFloat(float value, String spec) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            return VsFormat.toDisplayString(value);
        }
        Specifier specifier = Specifier.parse(spec);
        if (specifier == null) {
            return VsFormat.toDisplayString(value);
        }
        if (specifier.custom()) {
            throw refuse(specifier);
        }
        // Widening to double is exact, so the value rounded is still the float's own.
        return binary(specifier, value, Float.floatToRawIntBits(value) < 0);
    }

    /// NaN and the infinities short-circuit the format string entirely: the .NET 10 oracle
    /// returns the symbol even for `D`, `X`, an unknown letter such as `Q` and a custom format
    /// string, so the specifier is never parsed for them.
    public static String formatDouble(double value, String spec) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return VsFormat.toDisplayString(value);
        }
        Specifier specifier = Specifier.parse(spec);
        if (specifier == null) {
            return VsFormat.toDisplayString(value);
        }
        if (specifier.custom()) {
            throw refuse(specifier);
        }
        return binary(specifier, value, Double.doubleToRawLongBits(value) < 0);
    }

    private static String binary(Specifier specifier, double value, boolean negative) {
        return switch (specifier.letter()) {
            case 'F', 'f' -> {
                // The sign is carried separately so that a magnitude rounding to zero still
                // prints `-0.00`, which is what .NET does for binary floating point.
                BigDecimal magnitude = new BigDecimal(Math.abs(value))
                        .setScale(specifier.precision(2), RoundingMode.HALF_EVEN);
                yield (negative ? "-" : "") + magnitude.toPlainString();
            }
            case 'D', 'd', 'X', 'x' -> throw integralOnly();
            default -> throw refuse(specifier);
        };
    }

    // -------------------------------------------------------------- decimal

    public static String formatDecimal(BigDecimal value, String spec) {
        Specifier specifier = Specifier.parse(spec);
        if (specifier == null) {
            return VsDecimal.toDisplayString(value);
        }
        if (specifier.custom()) {
            throw refuse(specifier);
        }
        return switch (specifier.letter()) {
            // Unlike the binary types, `decimal` rounds half away from zero and lets a zero
            // magnitude drop its sign, both measured against the .NET 10 oracle.
            case 'F', 'f' -> value.setScale(specifier.precision(2), RoundingMode.HALF_UP)
                    .toPlainString();
            case 'D', 'd', 'X', 'x' -> throw integralOnly();
            default -> throw refuse(specifier);
        };
    }

    // ------------------------------------------------------------- refusals

    /// `D` and `X` are integral-only in C#; a floating receiver makes them invalid outright.
    private static VsFormatException integralOnly() {
        return new VsFormatException(INVALID);
    }

    /// A specifier C# accepts but this build cannot render faithfully without a culture.
    private static VsFormatException refuse(Specifier specifier) {
        if (specifier.custom()) {
            return new VsFormatException(
                    "Custom numeric format strings are not supported by this V# build; "
                    + "only the standard D, X and F specifiers are available.");
        }
        return switch (specifier.letter()) {
            case 'G', 'g', 'N', 'n', 'C', 'c', 'E', 'e', 'P', 'p', 'R', 'r', 'B', 'b' ->
                new VsFormatException("The '" + specifier.letter()
                        + "' numeric format specifier is not supported by this V# build, "
                        + "because it needs a culture model; only D, X and F are available.");
            default -> new VsFormatException(INVALID);
        };
    }

    /// A parsed standard numeric format string: one letter and an optional precision.
    ///
    /// Anything else is a *custom* format string. .NET renders those; V# refuses them, so the
    /// distinction is kept explicitly rather than collapsed into "invalid".
    private record Specifier(char letter, int precision, boolean custom) {

        /// @return the parsed specifier, or `null` when the default rendering applies
        static Specifier parse(String spec) {
            if (spec == null || spec.isEmpty()) {
                return null;
            }
            char letter = spec.charAt(0);
            if (!isAsciiLetter(letter) || !allDigits(spec, 1)) {
                return new Specifier(letter, -1, true);
            }
            if (spec.length() == 1) {
                return new Specifier(letter, -1, false);
            }
            long precision = 0;
            for (int i = 1; i < spec.length(); i++) {
                precision = precision * 10 + (spec.charAt(i) - '0');
                if (precision > MAX_PRECISION) {
                    return new Specifier(letter, -1, true);
                }
            }
            return new Specifier(letter, (int) precision, false);
        }

        /// The requested precision, or `fallback` when the specifier carried none.
        int precision(int fallback) {
            return precision < 0 ? fallback : precision;
        }

        private static boolean isAsciiLetter(char c) {
            return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
        }

        private static boolean allDigits(String spec, int from) {
            for (int i = from; i < spec.length(); i++) {
                if (spec.charAt(i) < '0' || spec.charAt(i) > '9') {
                    return false;
                }
            }
            return true;
        }
    }

    private static String pad(String digits, int precision) {
        return digits.length() >= precision ? digits
                : "0".repeat(precision - digits.length()) + digits;
    }
}
