package vsharp.tests.runtime;

import vsharp.runtime.VsDouble;
import vsharp.runtime.VsFormatException;
import vsharp.testkit.Assert;
import vsharp.testkit.TestRegistry;
import vsharp.testkit.TestSuite;

/// .NET-10-oracle-derived coverage for the curated `System.Double` parsing surface.
public final class DoubleTests implements TestSuite {

    @Override
    public String suiteName() {
        return "runtime.double";
    }

    @Override
    public void register(TestRegistry registry) {
        registry.test("parse accepts invariant decimal exponent and grouping grammar", () -> {
            parsed(0x3FF4000000000000L, "1.25");
            parsed(0x3FE0000000000000L, ".5");
            parsed(0xBFE0000000000000L, "-.5");
            parsed(0x4059000000000000L, "1.e2");
            parsed(0x40934A0000000000L, "1,234.5");
            parsed(0x4093480000000000L, "12,34");
            parsed(0x4028000000000000L, "1,,2");
            parsed(0x3FF3333333333333L, "1,,.2");
            parsed(0x3FF0000000000000L, "1,,," + (char) 0 + (char) 0);
        });

        registry.test("number whitespace is exactly the measured ASCII set", () -> {
            for (int code = 0; code <= 0x20; code++) {
                String text = Character.toString((char) code) + "1.5" + (char) code;
                double[] result = {99.0d};
                boolean expected = (code >= 0x09 && code <= 0x0D) || code == 0x20;
                Assert.equal(expected, VsDouble.tryParse(text, result),
                        "whitespace U+%04X success".formatted(code));
                Assert.equal(expected ? 0x3FF8000000000000L : 0L,
                        Double.doubleToRawLongBits(result[0]),
                        "whitespace U+%04X result".formatted(code));
            }
        });

        registry.test("special overflow and underflow results preserve measured bits", () -> {
            parsed(0xFFF8000000000000L, "NaN");
            parsed(0xFFF8000000000000L, "+nAn");
            parsed(0xFFF8000000000000L, " -NAN ");
            parsed(0x7FF0000000000000L, "iNfInItY");
            parsed(0xFFF0000000000000L, " -Infinity ");
            parsed(0x7FEFFFFFFFFFFFFFL, "1.7976931348623158E+308");
            parsed(0x7FF0000000000000L, "1.7976931348623159E+308");
            parsed(0xFFF0000000000000L, "-1e309");
            parsed(0x0000000000000001L, "2.4703282292062328E-324");
            parsed(0x0000000000000000L, "2.4703282292062327E-324");
            parsed(0x8000000000000000L, "-1e-4000");
            parsed(0x4340000000000000L, "9007199254740993");
        });

        registry.test("format failures reject Java-only and misplaced syntax", () -> {
            String[] malformed = {"", " ", ".", "+", "1e", "1e+", "1_0",
                    "0x1.0p0", "1f", ",1", ".1,", "1.2,3", "1e1,0",
                    "NaNx", "Infinityx", "NaN" + (char) 0, "∞", "１２.３",
                    "\u00A01\u00A0", "1" + (char) 0 + "x",
                    "9".repeat(10_000) + "x"};
            for (String text : malformed) {
                VsFormatException failure = Assert.throwsException(VsFormatException.class,
                        () -> VsDouble.parse(text), "format " + text);
                Assert.equal("The input string '" + text + "' was not in a correct format.",
                        failure.getMessage(), "format message");
            }
            IllegalArgumentException nullFailure = Assert.throwsException(
                    IllegalArgumentException.class,
                    () -> VsDouble.parse(null), "null is ArgumentException");
            Assert.equal("Value cannot be null. (Parameter 's')", nullFailure.getMessage(),
                    "null message");
        });

        registry.test("try parse assigns positive zero on failure and exact bits on success", () -> {
            double[] result = {Double.longBitsToDouble(0x7FF8000000000042L)};
            Assert.isFalse(VsDouble.tryParse(null, result), "null fails");
            Assert.equal(0L, Double.doubleToRawLongBits(result[0]), "null assigns +0");
            Assert.isFalse(VsDouble.tryParse("bad", result), "format fails");
            Assert.equal(0L, Double.doubleToRawLongBits(result[0]), "format assigns +0");
            Assert.isTrue(VsDouble.tryParse("-0", result), "negative zero succeeds");
            Assert.equal(0x8000000000000000L, Double.doubleToRawLongBits(result[0]),
                    "negative zero bits");
            Assert.isTrue(VsDouble.tryParse("1e309", result), "overflow succeeds");
            Assert.equal(0x7FF0000000000000L, Double.doubleToRawLongBits(result[0]),
                    "overflow infinity bits");
        });
    }

    private static void parsed(long expectedBits, String text) {
        Assert.equal(expectedBits, Double.doubleToRawLongBits(VsDouble.parse(text)),
                "parse " + text);
    }
}
