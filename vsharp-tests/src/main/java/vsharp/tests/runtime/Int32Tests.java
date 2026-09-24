package vsharp.tests.runtime;

import vsharp.runtime.VsFormatException;
import vsharp.runtime.VsInt32;
import vsharp.testkit.Assert;
import vsharp.testkit.TestRegistry;
import vsharp.testkit.TestSuite;

/// .NET-10-oracle-derived coverage for the curated `System.Int32` parsing surface.
public final class Int32Tests implements TestSuite {

    @Override
    public String suiteName() {
        return "runtime.int32";
    }

    @Override
    public void register(TestRegistry registry) {
        registry.test("parse accepts the invariant integer grammar and boundaries", () -> {
            parsed(0, "0");
            parsed(0, "-0");
            parsed(0, "+0");
            parsed(42, "42");
            parsed(42, " 42 ");
            parsed(-42, "\t-42\r\n");
            parsed(Integer.MAX_VALUE, "2147483647");
            parsed(Integer.MIN_VALUE, "-2147483648");
            parsed(Integer.MIN_VALUE,
                    "-0000000000000000000000000000000000000000000000000002147483648");
            parsed(42, "42" + (char) 0);
            parsed(42, "42 " + (char) 0 + (char) 0);
        });

        registry.test("number whitespace is exactly the measured ASCII set", () -> {
            for (int code = 0; code <= 0x20; code++) {
                String text = Character.toString((char) code) + "42" + (char) code;
                int[] result = {99};
                boolean expected = (code >= 0x09 && code <= 0x0D) || code == 0x20;
                Assert.equal(expected, VsInt32.tryParse(text, result),
                        "whitespace U+%04X success".formatted(code));
                Assert.equal(expected ? 42 : 0, result[0],
                        "whitespace U+%04X result".formatted(code));
            }
        });

        registry.test("format and overflow remain distinct failure categories", () -> {
            String[] malformed = {"", " ", "00+1", "1 2", "1_0", "0x10", "12.0",
                    "1e2", "++1", "--1", "+ 1", "- 1", "１２", "١٢", "−1",
                    "\u00A042\u00A0", "\u008542\u0085", "\u200042\u2000",
                    "2147483648x", "42" + (char) 0 + "x"};
            for (String text : malformed) {
                VsFormatException failure = Assert.throwsException(VsFormatException.class,
                        () -> VsInt32.parse(text), "format " + text);
                Throwable caught = failure;
                Assert.isFalse(caught instanceof IllegalArgumentException,
                        "FormatException must not derive from ArgumentException");
                Assert.equal("The input string '" + text + "' was not in a correct format.",
                        failure.getMessage(), "format message");
            }

            String[] overflowing = {"2147483648", "-2147483649", "+2147483648",
                    "9999999999999999999999999999999999999",
                    "9999999999999999999999999999999999999 " + (char) 0};
            for (String text : overflowing) {
                ArithmeticException failure = Assert.throwsException(ArithmeticException.class,
                        () -> VsInt32.parse(text), "overflow " + text);
                Assert.equal("Value was either too large or too small for an Int32.",
                        failure.getMessage(), "overflow message");
            }
            IllegalArgumentException nullFailure = Assert.throwsException(
                    IllegalArgumentException.class,
                    () -> VsInt32.parse(null), "null is ArgumentException");
            Assert.equal("Value cannot be null. (Parameter 's')", nullFailure.getMessage(),
                    "null message");
        });

        registry.test("try parse never throws for text failure and always assigns out", () -> {
            int[] result = {123456789};
            Assert.isFalse(VsInt32.tryParse(null, result), "null fails");
            Assert.equal(0, result[0], "null assigns zero");
            Assert.isFalse(VsInt32.tryParse("bad", result), "format fails");
            Assert.equal(0, result[0], "format assigns zero");
            Assert.isFalse(VsInt32.tryParse("2147483648", result), "overflow fails");
            Assert.equal(0, result[0], "overflow assigns zero");
            Assert.isTrue(VsInt32.tryParse("-42", result), "valid succeeds");
            Assert.equal(-42, result[0], "success assigns value");
        });
    }

    private static void parsed(int expected, String text) {
        Assert.equal(expected, VsInt32.parse(text), "parse " + text);
    }
}
