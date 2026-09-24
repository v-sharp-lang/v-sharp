package vsharp.runtime;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/// C#-faithful default value formatting.
///
/// The JVM and the CLR disagree on several default `ToString()` results, and V# must not
/// silently adopt Java's behaviour. This class is the single place where those differences
/// are reconciled; every documented divergence is covered by tests.
///
/// Reconciled cases:
///   - `bool` renders as `True`/`False`, not `true`/`false`.
///   - `null` renders as the empty string (matching `Console.WriteLine((object)null)`).
///   - `double`/`float` use .NET Core shortest-round-trip formatting: `1d` renders as
///     `1` (not Java's `1.0`), and exponential form uses `E+21`/`E-07` style with a
///     sign and at least two exponent digits.
///   - Non-finite values render as `NaN`, `Infinity` and `-Infinity` (Java agrees, but the
///     mapping is pinned here so it cannot drift).
public final class VsFormat {

    /// The decimal exponent at which a `double` switches to exponential notation, measured
    /// against the local .NET 10 SDK rather than derived from the "G" documentation (R2,
    /// the design): `1e16` prints as `10000000000000000` and `1e17` as `1E+17`. The default
    /// `ToString()` of .NET Core 3.0+ is shortest-round-trip, not `G15`, and its layout
    /// threshold is the round-trip precision, not the `G` default precision.
    private static final int DOUBLE_PRECISION = 17;

    /// The same bound for `float`, also measured: `1e8` prints plain and `1e9` as `1E+09`.
    private static final int FLOAT_PRECISION = 9;

    /// Exponents at or below this bound always use exponential notation.
    private static final int NEGATIVE_EXPONENT_LIMIT = -5;

    private VsFormat() {
        throw new AssertionError("No instances");
    }

    /// Formats an arbitrary value using C# default formatting rules.
    /// `object.ToString()`. C# guarantees the member on every reference, and reaching it
    /// through a null one is `NullReferenceException` - not the `"null"` that Java's
    /// `String.valueOf` would answer - so the receiver is checked before anything is
    /// formatted. A non-null value is displayed by the same C#-faithful rules string
    /// concatenation already uses, so a boxed `bool` reads `True` and a Java object reads
    /// whatever its own `toString` returns.
    public static String objectToString(Object value) {
        if (value == null) {
            throw new NullPointerException("Object reference not set to an instance of an object.");
        }
        return toDisplayString(value);
    }

    public static String toDisplayString(Object value) {
        return switch (value) {
            case null -> "";
            case Boolean b -> toDisplayString(b.booleanValue());
            case Double d -> toDisplayString(d.doubleValue());
            case Float f -> toDisplayString(f.floatValue());
            case BigDecimal d -> VsDecimal.toDisplayString(d);
            default -> value.toString();
        };
    }

    /// Applies an interpolated string's alignment clause to an already-rendered hole.
    ///
    /// C# pads with spaces to a *minimum* width of `|alignment|`: a positive alignment is
    /// right-aligned (padding on the left) and a negative one left-aligned. A value already
    /// at least that wide is never truncated, which is why this widens and never shortens.
    ///
    /// @param value the rendered hole
    /// @param alignment the alignment clause; `0` pads nothing
    /// @return `value`, padded to the requested width
    public static String align(String value, int alignment) {
        int width = Math.abs(alignment);
        int padding = width - value.length();
        if (padding <= 0) {
            return value;
        }
        String spaces = " ".repeat(padding);
        return alignment < 0 ? value + spaces : spaces + value;
    }

    /// Formats a `bool` as C# does.
    public static String toDisplayString(boolean value) {
        return value ? "True" : "False";
    }

    /// Formats a `char` as C# does (the character itself, never a numeric code).
    public static String toDisplayString(char value) {
        return String.valueOf(value);
    }

    // The integral and string overloads below are not conveniences: without them an `int`
    // argument would widen to the `float` overload and be rendered through floating-point
    // formatting, corrupting values above 2^24. Exact overloads keep integral values exact.

    /// Formats an `int`.
    public static String toDisplayString(int value) {
        return Integer.toString(value);
    }

    /// Formats a `long`.
    public static String toDisplayString(long value) {
        return Long.toString(value);
    }

    /// Formats a `uint`, whose JVM carrier is the signed `int` holding its bit pattern.
    ///
    /// V# keeps unsignedness in the type rather than in the carrier, so rendering is the
    /// point where the two spellings of the same 32 bits diverge: `4294967295u` and `-1`
    /// share a carrier and must print differently.
    public static String toDisplayStringUnsigned(int value) {
        return Integer.toUnsignedString(value);
    }

    /// Formats a `ulong` carried in the signed `long` holding its bit pattern.
    public static String toDisplayStringUnsigned(long value) {
        return Long.toUnsignedString(value);
    }

    /// Formats a `byte` whose signed JVM carrier is the only spelling available to a
    /// generated call, preserving C#'s unsigned value (`200`, never `-56`).
    public static String toDisplayStringByte(byte value) {
        return Integer.toString(Byte.toUnsignedInt(value));
    }

    /// Formats a `ushort` whose signed JVM carrier is the only spelling available to a
    /// generated call, preserving C#'s unsigned value (`40000`, never `-25536`).
    public static String toDisplayStringUShort(short value) {
        return Integer.toString(Short.toUnsignedInt(value));
    }

    /// Formats a `string`, rendering `null` as the empty string as C# does.
    public static String toDisplayString(String value) {
        return value == null ? "" : value;
    }

    /// Formats a `double` using .NET's shortest round-trip default format.
    public static String toDisplayString(double value) {
        if (Double.isNaN(value)) {
            return "NaN";
        }
        if (Double.isInfinite(value)) {
            return value > 0 ? "Infinity" : "-Infinity";
        }
        if (value == 0.0d) {
            // Preserves negative zero, as .NET Core 3.0+ does.
            return Double.doubleToRawLongBits(value) == 0L ? "0" : "-0";
        }
        return format(shortest(Double.toString(value), value), DOUBLE_PRECISION);
    }

    /// Formats a `float` using .NET's shortest round-trip default format.
    public static String toDisplayString(float value) {
        if (Float.isNaN(value)) {
            return "NaN";
        }
        if (Float.isInfinite(value)) {
            return value > 0 ? "Infinity" : "-Infinity";
        }
        if (value == 0.0f) {
            return Float.floatToRawIntBits(value) == 0 ? "0" : "-0";
        }
        return format(shortest(Float.toString(value), value), FLOAT_PRECISION);
    }

    /// Drops the one digit Java keeps but .NET does not.
    ///
    /// Both runtimes print the shortest decimal that round-trips, but Java's specification
    /// requires *at least two* significant digits, so `Double.MIN_VALUE` renders as
    /// `4.9E-324` where .NET renders `5E-324` and `Float.MIN_VALUE` as `1.4E-45` where .NET
    /// renders `1E-45`. Whenever the two-digit literal has a one-digit neighbour that reads
    /// back as the very same value, that neighbour is the shortest round-trip and the one
    /// .NET prints (R2, the design).
    private static String shortest(String javaLiteral, double value) {
        BigDecimal exact = new BigDecimal(javaLiteral);
        if (exact.precision() != 2) {
            return javaLiteral;
        }
        BigDecimal rounded = exact.round(new MathContext(1, RoundingMode.HALF_EVEN));
        return rounded.doubleValue() == value ? rounded.toString() : javaLiteral;
    }

    /// The `float` counterpart: the round-trip is checked at `float` width, so a literal is
    /// only shortened when the shorter one reads back as the same `float`.
    private static String shortest(String javaLiteral, float value) {
        BigDecimal exact = new BigDecimal(javaLiteral);
        if (exact.precision() != 2) {
            return javaLiteral;
        }
        BigDecimal rounded = exact.round(new MathContext(1, RoundingMode.HALF_EVEN));
        return rounded.floatValue() == value ? rounded.toString() : javaLiteral;
    }

    /// Re-renders a Java shortest-round-trip literal in .NET's default layout.
    ///
    /// Java always emits a fractional digit and an unsigned exponent (`1.0E21`); .NET emits
    /// the shortest plain form when the exponent is small (`1`) and `E+21` otherwise.
    private static String format(String javaLiteral, int precision) {
        BigDecimal exact = new BigDecimal(javaLiteral);
        BigDecimal magnitude = exact.abs();
        // `precision - scale - 1` is the base-10 exponent of the leading digit.
        int decimalExponent = magnitude.precision() - magnitude.scale() - 1;
        // The .NET "G" rule: fixed-point while the exponent stays above -5 and below the
        // format's precision specifier, exponential everywhere else.
        if (decimalExponent >= precision || decimalExponent <= NEGATIVE_EXPONENT_LIMIT) {
            return scientific(exact, decimalExponent);
        }
        return exact.stripTrailingZeros().toPlainString();
    }

    /// Renders `value` as `d.dddE+xx`, the .NET exponential default.
    private static String scientific(BigDecimal value, int decimalExponent) {
        BigDecimal mantissa = value.movePointLeft(decimalExponent)
                .round(new MathContext(value.precision(), RoundingMode.HALF_EVEN))
                .stripTrailingZeros();
        StringBuilder out = new StringBuilder(mantissa.toPlainString());
        out.append('E').append(decimalExponent < 0 ? '-' : '+');
        String digits = Integer.toString(Math.abs(decimalExponent));
        if (digits.length() < 2) {
            out.append('0');
        }
        return out.append(digits).toString();
    }
}
