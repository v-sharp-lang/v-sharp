package vsharp.tests.runtime;

import vsharp.runtime.VsMath;
import vsharp.testkit.Assert;
import vsharp.testkit.TestRegistry;
import vsharp.testkit.TestSuite;

/// Direct coverage for the bounded trigonometric compatibility contract.
public final class MathTests implements TestSuite {

    @Override
    public String suiteName() {
        return "runtime.math";
    }

    @Override
    public void register(TestRegistry registry) {
        registry.test("Sin Cos and Tan preserve every measured IEEE edge bit", () -> {
            raw(0x0000000000000000L, VsMath.sin(0.0d), "Sin(+0)");
            raw(0x8000000000000000L, VsMath.sin(-0.0d), "Sin(-0)");
            raw(0x3FF0000000000000L, VsMath.cos(0.0d), "Cos(+0)");
            raw(0x3FF0000000000000L, VsMath.cos(-0.0d), "Cos(-0)");
            raw(0x0000000000000000L, VsMath.tan(0.0d), "Tan(+0)");
            raw(0x8000000000000000L, VsMath.tan(-0.0d), "Tan(-0)");

            raw(0xFFF8000000000000L, VsMath.sin(Double.POSITIVE_INFINITY), "Sin(+inf)");
            raw(0xFFF8000000000000L, VsMath.cos(Double.NEGATIVE_INFINITY), "Cos(-inf)");
            raw(0xFFF8000000000000L, VsMath.tan(Double.POSITIVE_INFINITY), "Tan(+inf)");

            double positiveSignallingNaN = Double.longBitsToDouble(0x7FF0000000000001L);
            double negativeSignallingNaN = Double.longBitsToDouble(0xFFF0000000000001L);
            raw(0x7FF8000000000001L, VsMath.sin(positiveSignallingNaN), "Sin(+sNaN)");
            raw(0xFFF8000000000001L, VsMath.cos(negativeSignallingNaN), "Cos(-sNaN)");
            raw(0x7FF8000000000001L, VsMath.tan(positiveSignallingNaN), "Tan(+sNaN)");
        });

        registry.test("known .NET finite deltas stay within the one ULP policy", () -> {
            withinOneUlp(0x3FD915EA5232C69EL,
                    VsMath.sin(Double.longBitsToDouble(0xD5DD3B6C00A3DBB6L)), "Sin sample");
            withinOneUlp(0xBCA72CECE675D1FCL,
                    VsMath.cos(Double.longBitsToDouble(0x3FF921FB54442D19L)), "Cos sample");
            withinOneUlp(0x3FDEBF81A928A483L,
                    VsMath.tan(Double.longBitsToDouble(0xC9AE6881305AFE66L)), "Tan sample");
        });
    }

    private static void raw(long expectedBits, double actual, String what) {
        Assert.equal(expectedBits, Double.doubleToRawLongBits(actual), what + " raw bits");
    }

    private static void withinOneUlp(long expectedBits, double actual, String what) {
        long actualBits = Double.doubleToRawLongBits(actual);
        long distance = Math.abs(expectedBits - actualBits);
        Assert.isTrue(distance <= 1L,
                what + ": expected raw bits within one ULP of %016X but was %016X"
                        .formatted(expectedBits, actualBits));
    }
}
