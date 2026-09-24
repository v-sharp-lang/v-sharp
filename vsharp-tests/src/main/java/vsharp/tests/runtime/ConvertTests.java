package vsharp.tests.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Supplier;

import vsharp.runtime.VsConvert;
import vsharp.runtime.VsFormatException;
import vsharp.testkit.Assert;
import vsharp.testkit.TestRegistry;
import vsharp.testkit.TestSuite;

/// .NET-10-oracle-derived coverage for the bounded invariant radix conversion pair.
public final class ConvertTests implements TestSuite {

    private static final String ORACLE_DIGEST =
            "5efe86691c0524ce8ab075fd1bea536421d855bc2fa8fafa89899ca7635e0247";

    private static final int[] BASES = {-1, 0, 2, 3, 8, 10, 16, 36};
    private static final int[] VALUES = {
        Integer.MIN_VALUE, Integer.MIN_VALUE + 1, -65536, -32769, -32768, -257, -256, -255,
        -17, -16, -15, -9, -8, -7, -2, -1, 0, 1, 2, 7, 8, 9, 10, 15, 16, 17,
        127, 128, 255, 256, 257, 32767, 32768, 65535, 65536,
        Integer.MAX_VALUE - 1, Integer.MAX_VALUE
    };
    private static final String[] TEXTS = {
        null, "", " ", " 1", "1 ", "\t1", "+", "-", "+0", "-0", "+1", "-1",
        "0", "00", "0001", "7", "8", "9", "10", "15", "16", "17", "2147483647",
        "2147483648", "-2147483648", "-2147483649", "4294967295", "4294967296",
        "7fffffff", "80000000", "ffffffff", "FFFFFFFF", "100000000", "0x0", "0x1",
        "0X7fffffff", "0xffffffff", "+0x1", "+0XFFFFFFFF", "0x", "x1", "0x+1",
        "0x-1", "0x 1", "++1", "+-1", "08", "018", "00000000ffffffff",
        "0000000100000000", "deadbeef", "DEADBEEF", "beef", "101010",
        "11111111111111111111111111111111", "100000000000000000000000000000000",
        "37777777777", "40000000000", "17777777777", "-80000000", "+7fffffff",
        "_1", "1_0", "1.0", "1,0", "g", "G", "z", "\u0661", "\uFF11",
        String.valueOf((char) 0), "1" + (char) 0
    };

    @Override
    public String suiteName() {
        return "runtime.convert";
    }

    @Override
    public void register(TestRegistry registry) {
        registry.test("radix conversion replays the .NET 10 corpus byte for byte", () ->
                Assert.equal(ORACLE_DIGEST, digest(replayCorpus()), "Convert corpus digest"));

        registry.test("non-decimal negative values round trip as UInt32 bit patterns", () -> {
            Assert.equal("ffffffff", VsConvert.toString(-1, 16), "negative hexadecimal");
            Assert.equal("37777777777", VsConvert.toString(-1, 8), "negative octal");
            Assert.equal("11111111111111111111111111111111", VsConvert.toString(-1, 2),
                    "negative binary");
            Assert.equal(-1, VsConvert.toInt32("ffffffff", 16), "hexadecimal bits");
            Assert.equal(Integer.MIN_VALUE, VsConvert.toInt32("80000000", 16),
                    "sign bit is a value bit outside base 10");
            Assert.equal(-1, VsConvert.toInt32("0XFFFFFFFF", 16), "optional hex prefix");
        });

        registry.test("null, signs and failures retain Convert's distinct contracts", () -> {
            Assert.equal(0, VsConvert.toInt32(null, 16), "null converts to zero");
            Assert.equal(31, VsConvert.toInt32("+0x1f", 16), "plus precedes the prefix");
            Assert.equal(Integer.MIN_VALUE, VsConvert.toInt32("-2147483648", 10),
                    "decimal minimum");
            Assert.equal("Invalid Base.", Assert.throwsException(IllegalArgumentException.class,
                    () -> VsConvert.toInt32(null, 3), "base validation precedes null").getMessage(),
                    "invalid-base message");
            Assert.equal("String cannot contain a minus sign if the base is not 10.",
                    Assert.throwsException(IllegalArgumentException.class,
                            () -> VsConvert.toInt32("-1", 16), "non-decimal minus").getMessage(),
                    "minus message");
            Assert.equal("Could not find any recognizable digits.",
                    Assert.throwsException(VsFormatException.class,
                            () -> VsConvert.toInt32("x", 16), "no digits").getMessage(),
                    "no-digits message");
            Assert.equal("Additional non-parsable characters are at the end of the string.",
                    Assert.throwsException(VsFormatException.class,
                            () -> VsConvert.toInt32("1x", 16), "trailing text").getMessage(),
                    "trailing message");
            Assert.equal("Value was either too large or too small for a UInt32.",
                    Assert.throwsException(ArithmeticException.class,
                            () -> VsConvert.toInt32("100000000", 16), "unsigned overflow").getMessage(),
                    "non-decimal overflow message");
        });
    }

    private static String replayCorpus() {
        StringBuilder records = new StringBuilder(650_000);
        for (int value : VALUES) {
            for (int radix : BASES) {
                appendToString(records, value, radix);
            }
        }
        for (String text : TEXTS) {
            for (int radix : BASES) {
                appendToInt32(records, text, radix);
            }
        }

        int state = 0xC0FFEE42;
        int[] validBases = {2, 8, 10, 16};
        for (int i = 0; i < 512; i++) {
            state = state * 1664525 + 1013904223;
            int value = state;
            for (int radix : validBases) {
                appendToString(records, value, radix);
                appendToInt32(records, VsConvert.toString(value, radix), radix);
            }
        }
        return records.toString();
    }

    private static void appendToString(StringBuilder records, int value, int radix) {
        records.append("S|").append(value).append('|').append(radix).append('|')
                .append(run(() -> VsConvert.toString(value, radix))).append('\n');
    }

    private static void appendToInt32(StringBuilder records, String value, int radix) {
        records.append("P|").append(escape(value)).append('|').append(radix).append('|')
                .append(run(() -> Integer.toString(VsConvert.toInt32(value, radix)))).append('\n');
    }

    private static String run(Supplier<String> body) {
        try {
            return "OK|" + escape(body.get());
        } catch (VsFormatException failure) {
            return "EX|FMT|" + escape(failure.getMessage());
        } catch (ArithmeticException failure) {
            return "EX|OVF|" + escape(failure.getMessage());
        } catch (IllegalArgumentException failure) {
            return "EX|ARG|" + escape(failure.getMessage());
        }
    }

    private static String escape(String value) {
        if (value == null) {
            return "<null>";
        }
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character >= ' ' && character <= '~' && character != '\\' && character != '|') {
                result.append(character);
            } else {
                result.append("\\u").append("%04X".formatted((int) character));
            }
        }
        return result.toString();
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
