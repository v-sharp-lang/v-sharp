package vsharp.tests.runtime;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Supplier;

import vsharp.runtime.VsFormatException;
import vsharp.runtime.VsNumberFormat;
import vsharp.testkit.Assert;
import vsharp.testkit.TestRegistry;
import vsharp.testkit.TestSuite;

/// .NET-10-oracle-derived coverage for the invariant `D`/`X`/`F` specifiers.
///
/// [#ORACLE_DIGEST] is the SHA-256 of an 825-record run over every admitted numeric type
/// crossed with the admitted specifiers, executed under `InvariantCulture`. Only the
/// exception *type* column is canonicalised away, because V# raises `VsFormatException`
/// where .NET raises `FormatException`; the message text is compared verbatim, so the exact
/// set of refused combinations is pinned as tightly as the accepted output.
public final class NumberFormatTests implements TestSuite {

    /// SHA-256 of the canonicalised admitted-specifier records from the .NET 10 oracle.
    private static final String ORACLE_DIGEST =
            "a0d4260580233b13042063f89aa280b344489f1e2efe46b664677c0dd9045a78";

    private static final String INVALID = "Format specifier was invalid.";

    /// The specifiers this build admits, in the oracle's order.
    private static final String[] FORMATS = {
        null, "", "D", "D5", "d3", "D0", "X", "X4", "x", "x8", "X0", "F", "F0", "F2", "f4",
    };

    @Override
    public String suiteName() {
        return "runtime.numberformat";
    }

    @Override
    public void register(TestRegistry registry) {
        registry.test("specifiers replay the .NET 10 corpus byte for byte", () ->
                Assert.equal(ORACLE_DIGEST, digest(replayCorpus()), "format corpus digest"));

        registry.test("the precision is a minimum, never a width", () -> {
            // Two's complement already needs eight digits, so X4 cannot narrow it.
            Assert.equal("FFFFFFD6", VsNumberFormat.formatInt(-42, "X4"), "int X4");
            Assert.equal("2A", VsNumberFormat.formatInt(42, "X"), "int X");
            Assert.equal("00FF", VsNumberFormat.formatSByte((byte) -1, "X4"), "sbyte X4 pads");
            Assert.equal("FF", VsNumberFormat.formatSByte((byte) -1, "X"), "sbyte X is carrier-wide");
            Assert.equal("FFFF", VsNumberFormat.formatShort((short) -1, "X"), "short X");
            Assert.equal("FFFFFFFFFFFFFFFF", VsNumberFormat.formatLong(-1L, "X"), "long X");
            Assert.equal("ffffffd6", VsNumberFormat.formatInt(-42, "x"), "lowercase x");
            Assert.equal("-00042", VsNumberFormat.formatInt(-42, "D5"), "D pads after the sign");
            Assert.equal("-2147483648", VsNumberFormat.formatInt(Integer.MIN_VALUE, "D"),
                    "the most negative int has no positive magnitude");
        });

        registry.test("unsigned types render their unsigned value", () -> {
            Assert.equal("255", VsNumberFormat.formatByte((byte) -1, "D"), "byte D is unsigned");
            Assert.equal("-1", VsNumberFormat.formatSByte((byte) -1, "D"), "sbyte D is signed");
            Assert.equal("4294967295", VsNumberFormat.formatUInt(-1, "D"), "uint D");
            Assert.equal("18446744073709551615", VsNumberFormat.formatULong(-1L, "D"), "ulong D");
            Assert.equal("65535", VsNumberFormat.formatUShort((short) -1, "D"), "ushort D");
        });

        registry.test("binary types round half to even but decimal rounds away from zero", () -> {
            // The single most surprising measured difference in this family.
            Assert.equal("2", VsNumberFormat.formatDouble(2.5, "F0"), "double 2.5 -> 2");
            Assert.equal("4", VsNumberFormat.formatDouble(3.5, "F0"), "double 3.5 -> 4");
            Assert.equal("0.12", VsNumberFormat.formatDouble(0.125, "F2"), "double 0.125 -> 0.12");
            Assert.equal("-2", VsNumberFormat.formatDouble(-2.5, "F0"), "double -2.5 -> -2");
            Assert.equal("2", VsNumberFormat.formatFloat(2.5f, "F0"), "float follows double");

            Assert.equal("3", VsNumberFormat.formatDecimal(new BigDecimal("2.5"), "F0"),
                    "decimal 2.5 -> 3");
            Assert.equal("0.13", VsNumberFormat.formatDecimal(new BigDecimal("0.125"), "F2"),
                    "decimal 0.125 -> 0.13");
            Assert.equal("-3", VsNumberFormat.formatDecimal(new BigDecimal("-2.5"), "F0"),
                    "decimal -2.5 -> -3");
        });

        registry.test("binary rounding uses the stored value, not the literal", () -> {
            // 2.345 stores just above and 2.355 just below, so both land on 2.35.
            Assert.equal("2.35", VsNumberFormat.formatDouble(2.345, "F2"), "2.345 rounds up");
            Assert.equal("2.35", VsNumberFormat.formatDouble(2.355, "F2"), "2.355 rounds down");
            Assert.equal("123456.79", VsNumberFormat.formatDouble(123456.789, "F2"), "F2 of a big value");
            Assert.equal("1000000000000000000000.00", VsNumberFormat.formatDouble(1e21, "F2"),
                    "F never switches to exponential notation");
        });

        registry.test("sign survives a zero magnitude for binary types only", () -> {
            Assert.equal("-0.00", VsNumberFormat.formatDouble(-0.0, "F2"), "negative zero keeps its sign");
            Assert.equal("-0.00", VsNumberFormat.formatDouble(-0.001, "F2"), "so does a rounded-away value");
            Assert.equal("0.00", VsNumberFormat.formatDecimal(new BigDecimal("-0.004"), "F2"),
                    "decimal drops the sign instead");
            Assert.equal("0", VsNumberFormat.formatDouble(0.0, "F0"), "positive zero is unsigned");
        });

        registry.test("NaN and the infinities short-circuit the whole format string", () -> {
            // Measured: .NET returns the symbol even for specifiers it would otherwise
            // reject outright, so the format string is never parsed for these values.
            for (String spec : new String[] {null, "", "F2", "D", "X4", "N2", "C", "Q", "D2X"}) {
                Assert.equal("NaN", VsNumberFormat.formatDouble(Double.NaN, spec), "NaN " + spec);
                Assert.equal("Infinity",
                        VsNumberFormat.formatDouble(Double.POSITIVE_INFINITY, spec), "inf " + spec);
                Assert.equal("-Infinity",
                        VsNumberFormat.formatDouble(Double.NEGATIVE_INFINITY, spec), "-inf " + spec);
                Assert.equal("NaN", VsNumberFormat.formatFloat(Float.NaN, spec), "float NaN " + spec);
            }
        });

        registry.test("a null or empty specifier keeps the default rendering", () -> {
            Assert.equal("42", VsNumberFormat.formatInt(42, null), "null spec");
            Assert.equal("42", VsNumberFormat.formatInt(42, ""), "empty spec");
            Assert.equal("2.345", VsNumberFormat.formatDouble(2.345, null), "double default");
            Assert.equal("-0", VsNumberFormat.formatDouble(-0.0, ""), "default keeps negative zero");
        });

        registry.test("D and X are integral only, for finite values", () -> {
            for (String spec : new String[] {"D", "D5", "X", "x8"}) {
                Assert.equal(INVALID, refusal(() -> VsNumberFormat.formatDouble(1.0, spec)),
                        "double " + spec);
                Assert.equal(INVALID, refusal(() -> VsNumberFormat.formatDecimal(BigDecimal.ONE, spec)),
                        "decimal " + spec);
            }
        });

        registry.test("unknown specifiers reproduce .NET's own message", () -> {
            Assert.equal(INVALID, refusal(() -> VsNumberFormat.formatInt(1, "Q")), "Q");
            Assert.equal(INVALID, refusal(() -> VsNumberFormat.formatInt(1, "z")), "z");
        });

        registry.test("culture-dependent specifiers are refused, not approximated", () -> {
            // C# accepts every one of these; a build with no culture model must not guess.
            for (String spec : new String[] {"G", "N2", "C", "E2", "P1", "R", "n", "c"}) {
                String message = refusal(() -> VsNumberFormat.formatInt(1, spec));
                Assert.contains(message, "not supported by this V# build", "refusal for " + spec);
                Assert.contains(message, "culture model", "reason for " + spec);
            }
        });

        registry.test("custom format strings are refused as a separate surface", () -> {
            // .NET renders "D2X" as a custom format string rather than rejecting it, which is
            // exactly why it cannot be silently treated as an invalid standard specifier.
            for (String spec : new String[] {"D2X", "0.00", "#,##0", "yyyy", "D1000000000"}) {
                Assert.contains(refusal(() -> VsNumberFormat.formatInt(1, spec)),
                        "Custom numeric format strings are not supported", "refusal for " + spec);
            }
        });
    }

    /// Rebuilds the oracle's record text from [VsNumberFormat].
    private static String replayCorpus() {
        Records records = new Records();
        byte[] sbytes = {0, 1, -1, Byte.MIN_VALUE, Byte.MAX_VALUE, 42};
        for (byte value : sbytes) {
            records.rows("sbyte", Long.toString(value), spec -> VsNumberFormat.formatSByte(value, spec));
        }
        for (int value : new int[] {0, 255, 42}) {
            byte carrier = (byte) value;
            records.rows("byte", Integer.toString(value),
                    spec -> VsNumberFormat.formatByte(carrier, spec));
        }
        for (short value : new short[] {0, -1, Short.MIN_VALUE, Short.MAX_VALUE}) {
            records.rows("short", Short.toString(value),
                    spec -> VsNumberFormat.formatShort(value, spec));
        }
        for (int value : new int[] {0, 65535}) {
            short carrier = (short) value;
            records.rows("ushort", Integer.toString(value),
                    spec -> VsNumberFormat.formatUShort(carrier, spec));
        }
        for (int value : new int[] {0, 1, -1, -42, 42, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            records.rows("int", Integer.toString(value),
                    spec -> VsNumberFormat.formatInt(value, spec));
        }
        for (long value : new long[] {0L, 4294967295L}) {
            int carrier = (int) value;
            records.rows("uint", Long.toString(value),
                    spec -> VsNumberFormat.formatUInt(carrier, spec));
        }
        for (long value : new long[] {0L, -1L, Long.MIN_VALUE, Long.MAX_VALUE}) {
            records.rows("long", Long.toString(value),
                    spec -> VsNumberFormat.formatLong(value, spec));
        }
        for (long carrier : new long[] {0L, -1L}) {
            records.rows("ulong", Long.toUnsignedString(carrier),
                    spec -> VsNumberFormat.formatULong(carrier, spec));
        }
        // .NET's `double.NaN` is the *negative* quiet NaN; feeding the JDK constant here
        // would compare two different inputs (the same trap as).
        double netNaN = Double.longBitsToDouble(0xFFF8000000000000L);
        double[] doubles = {0.0, -0.0, 1.0, -1.5, 2.345, 2.355, 0.125, 1.0 / 3.0,
            netNaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 1e21, 123456.789};
        for (double value : doubles) {
            records.rows("double", Long.toString(Double.doubleToRawLongBits(value)),
                    spec -> VsNumberFormat.formatDouble(value, spec));
        }
        float netNaNf = Float.intBitsToFloat(0xFFC00000);
        float[] floats = {0f, 1.5f, 2.345f, netNaNf, Float.POSITIVE_INFINITY};
        for (float value : floats) {
            records.rows("float", Integer.toString(Float.floatToRawIntBits(value)),
                    spec -> VsNumberFormat.formatFloat(value, spec));
        }
        String[] decimals = {"0", "1.0", "-1.5", "2.345", "2.355", "19.99", "59.97"};
        for (String literal : decimals) {
            BigDecimal value = new BigDecimal(literal);
            records.rows("decimal", literal.equals("0") ? "0" : literal,
                    spec -> VsNumberFormat.formatDecimal(value, spec));
        }
        return records.finish();
    }

    /// Accumulates one `F|type|value|spec|outcome` row per admitted specifier.
    private static final class Records {
        private final StringBuilder text = new StringBuilder();
        private int count;

        void rows(String type, String value, java.util.function.Function<String, String> render) {
            for (String format : FORMATS) {
                String spec = format == null ? "<null>" : (format.isEmpty() ? "<empty>" : format);
                String outcome;
                try {
                    outcome = "OK|" + escape(render.apply(format));
                } catch (RuntimeException failure) {
                    outcome = "EX|" + escape(failure.getMessage());
                }
                text.append("F|").append(type).append('|').append(value).append('|')
                        .append(spec).append('|').append(outcome).append('\n');
                count++;
            }
        }

        String finish() {
            return text.append("RECORDS|").append(count).append('\n').toString();
        }
    }

    /// The message of the [VsFormatException] `body` must raise.
    private static String refusal(Supplier<String> body) {
        return Assert.throwsException(VsFormatException.class, body::get, "expected a refusal")
                .getMessage();
    }

    /// The oracle's escape: printable ASCII survives except the delimiters the format uses.
    private static String escape(String value) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character >= ' ' && character <= '~' && character != '|' && character != '\\') {
                text.append(character);
            } else {
                text.append("\\u").append("%04X".formatted((int) character));
            }
        }
        return text.toString();
    }

    private static String digest(String records) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(records.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException cause) {
            throw new IllegalStateException("SHA-256 is required by the platform", cause);
        }
    }
}
