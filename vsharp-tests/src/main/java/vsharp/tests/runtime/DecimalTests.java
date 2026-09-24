package vsharp.tests.runtime;

import java.math.BigDecimal;
import vsharp.runtime.VsDecimal;
import vsharp.testkit.Assert;
import vsharp.testkit.TestRegistry;
import vsharp.testkit.TestSuite;

/// Oracle-derived coverage for the CLR decimal envelope implemented by [VsDecimal].
public final class DecimalTests implements TestSuite {

    @Override
    public String suiteName() {
        return "runtime.decimal";
    }

    @Override
    public void register(TestRegistry registry) {
        registry.test("literal scale rounding and default match decimal bits", () -> {
            text("1.2300", literal("1.2300"), "apparent scale");
            text("0.0000000000000000000000000000",
                    literal("0.00000000000000000000000000005"), "half-even down");
            text("0.0000000000000000000000000002",
                    literal("0.00000000000000000000000000015"), "half-even up");
            text("0.1234567890123456789012345678",
                    literal("0.12345678901234567890123456785"), "29th digit rounding");
            text("79228162514264337593543950335",
                    literal("79228162514264337593543950335.0"), "maximum drops scale");
            text("0", VsDecimal.zero(), "default decimal");
        });

        registry.test("arithmetic retains CLR scale and half-even reduction", () -> {
            text("4.60", VsDecimal.add(literal("1.20"), literal("3.4")), "add");
            text("-2.20", VsDecimal.subtract(literal("1.20"), literal("3.4")),
                    "subtract");
            text("4.080", VsDecimal.multiply(literal("1.20"), literal("3.4")),
                    "multiply");
            text("79228162514264337593543950335",
                    VsDecimal.add(max(), literal("0.4")), "fit by rounding down");
            text("0.0000000000000000000000000000",
                    VsDecimal.multiply(literal("0.0000000000000000000000000001"),
                            literal("0.1")), "underflow retains scale 28");
        });

        registry.test("division grows coefficient within 96 bits", () -> {
            text("0.3333333333333333333333333333",
                    VsDecimal.divide(literal("1"), literal("3")), "one third");
            text("0.6666666666666666666666666667",
                    VsDecimal.divide(literal("2"), literal("3")), "two thirds rounds up");
            text("0.50", VsDecimal.divide(literal("1.00"), literal("2")),
                    "left scale retained");
            text("0.5", VsDecimal.divide(literal("1"), literal("2.00")),
                    "inexact path unscaled");
            text("100", VsDecimal.divide(literal("1"), literal("0.01")),
                    "negative natural scale");
        });

        registry.test("overflow and remainder match always-checked decimal", () -> {
            Assert.throwsException(ArithmeticException.class,
                    () -> VsDecimal.add(max(), literal("0.5")), "midpoint carry overflow");
            Assert.throwsException(ArithmeticException.class,
                    () -> VsDecimal.add(max(), literal("1")), "maximum plus one");
            Assert.throwsException(ArithmeticException.class,
                    () -> VsDecimal.divide(literal("1"), VsDecimal.zero()), "divide by zero");
            text("0.10", VsDecimal.remainder(literal("1.00"), literal("0.3")),
                    "remainder scale");
            text("0.0000000000000000000000000000",
                    VsDecimal.remainder(literal("12345678901234567890123456.78"),
                            literal("0.0000000000000000000000000001")),
                    "exact zero uses aligned scale");
        });

        registry.test("numeric conversions use CLR precision and ranges", () -> {
            text("4294967295", VsDecimal.fromUInt(-1), "uint maximum");
            text("18446744073709551615", VsDecimal.fromULong(-1L), "ulong maximum");
            text("1.23456789012346", VsDecimal.fromDouble(1.2345678901234567d),
                    "double 15 digits");
            text("1.234568", VsDecimal.fromFloat(1.23456789f), "float 7 digits");
            text("0", VsDecimal.fromDouble(1e-29d), "binary underflow scale zero");
            Assert.equal(1, VsDecimal.toInt(literal("1.9")), "positive truncation");
            Assert.equal(-1, VsDecimal.toInt(literal("-1.9")), "negative truncation");
            Assert.equal(-1, VsDecimal.toUInt(literal("4294967295")), "uint raw bits");
            Assert.equal(-1L, VsDecimal.toULong(literal("18446744073709551615")),
                    "ulong raw bits");
            Assert.throwsException(ArithmeticException.class,
                    () -> VsDecimal.toInt(max()), "integral conversion overflow");
        });

        registry.test("boxing copies while nullable unboxing accepts BigDecimal", () -> {
            BigDecimal value = literal("1.2300");
            Object first = VsDecimal.box(value);
            Object second = VsDecimal.box(value);
            Assert.isFalse(first == second, "separate decimal boxes");
            text("1.2300", VsDecimal.unbox(first), "box round trip");
            Assert.throwsException(ClassCastException.class,
                    () -> VsDecimal.unbox(Integer.valueOf(1)), "wrong Java carrier");
        });
    }

    private static BigDecimal literal(String value) {
        return VsDecimal.fromLiteral(value);
    }

    private static BigDecimal max() {
        return literal("79228162514264337593543950335");
    }

    private static void text(String expected, BigDecimal actual, String what) {
        Assert.equal(expected, VsDecimal.toDisplayString(actual), what);
    }
}
