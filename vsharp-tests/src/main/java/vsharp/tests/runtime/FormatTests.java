package vsharp.tests.runtime;

import vsharp.runtime.VsFormat;
import vsharp.runtime.VsUnsigned;
import vsharp.testkit.Assert;
import vsharp.testkit.TestRegistry;
import vsharp.testkit.TestSuite;

/// C#-faithful default value formatting.
///
/// These expectations come from C#/.NET Core semantics, not from Java's `toString`. Each
/// case names the divergence it pins so a regression reads as a semantic change rather
/// than a formatting nit.
public final class FormatTests implements TestSuite {

    @Override
    public String suiteName() {
        return "runtime.format";
    }

    @Override
    public void register(TestRegistry registry) {
        registry.test("bool renders capitalised", () -> {
            Assert.equal("True", VsFormat.toDisplayString(true), "true");
            Assert.equal("False", VsFormat.toDisplayString(false), "false");
        });

        registry.test("null renders as empty string", () ->
                Assert.equal("", VsFormat.toDisplayString((Object) null), "null object"));

        registry.test("boxed bool routes through the bool rule", () ->
                Assert.equal("True", VsFormat.toDisplayString((Object) Boolean.TRUE), "boxed true"));

        registry.test("boxed unsigned values retain unsigned rendering", () -> {
            Assert.equal("200", VsFormat.toDisplayString(VsUnsigned.boxByte((byte) -56)),
                    "boxed byte 200");
            Assert.equal("40000",
                    VsFormat.toDisplayString(VsUnsigned.boxUShort((short) -25536)),
                    "boxed ushort 40000");
            Assert.equal("4294967295", VsFormat.toDisplayString(VsUnsigned.box(-1)),
                    "boxed uint max");
            Assert.equal("18446744073709551615",
                    VsFormat.toDisplayString(VsUnsigned.box(-1L)), "boxed ulong max");
            Assert.equal(-1, VsUnsigned.unboxInt(VsUnsigned.box(-1)),
                    "uint box round trip");
            Assert.equal(-1L, VsUnsigned.unboxLong(VsUnsigned.box(-1L)),
                    "ulong box round trip");
            Assert.equal((byte) -56, VsUnsigned.unboxByte(VsUnsigned.boxByte((byte) -56)),
                    "byte box round trip");
            Assert.equal((short) -25536,
                    VsUnsigned.unboxUShort(VsUnsigned.boxUShort((short) -25536)),
                    "ushort box round trip");
        });

        registry.test("char renders as itself", () -> {
            Assert.equal("a", VsFormat.toDisplayString('a'), "letter");
            Assert.equal("0", VsFormat.toDisplayString('0'), "digit character, not its code");
        });

        registry.test("integral types never widen to floating point", () -> {
            // 16777217 is the first int that a float cannot represent; an accidental
            // widening overload would print 1.6777216E7.
            Assert.equal("16777217", VsFormat.toDisplayString(16777217), "int");
            Assert.equal("9223372036854775807", VsFormat.toDisplayString(Long.MAX_VALUE), "long");
            Assert.equal("-2147483648", VsFormat.toDisplayString(Integer.MIN_VALUE), "int min");
        });

        registry.test("double drops the trailing .0 Java adds", () -> {
            Assert.equal("1", VsFormat.toDisplayString(1.0d), "1.0");
            Assert.equal("100", VsFormat.toDisplayString(100.0d), "100.0");
            Assert.equal("-3", VsFormat.toDisplayString(-3.0d), "-3.0");
        });

        registry.test("double keeps shortest round-trip digits", () -> {
            Assert.equal("0.30000000000000004", VsFormat.toDisplayString(0.1d + 0.2d), "0.1+0.2");
            Assert.equal("1.5", VsFormat.toDisplayString(1.5d), "1.5");
            Assert.equal("0.0001", VsFormat.toDisplayString(0.0001d), "1e-4 stays fixed-point");
        });

        // Every expectation below was read off the local .NET 10 SDK (`dotnet --version`
        // 10.0.400) rather than from the "G" documentation: the default `ToString()` of
        // .NET Core 3.0+ is shortest-round-trip, and its layout switches to exponential at
        // the *round-trip* precision - 17 for double, 9 for float - not at the G default
        // precision of 15/7 these thresholds used to carry (R2, the design).
        registry.test("double switches to exponential outside the round-trip window", () -> {
            Assert.equal("10000000000000000", VsFormat.toDisplayString(1e16d),
                    "the last plain exponent");
            Assert.equal("1E+17", VsFormat.toDisplayString(1e17d), "the first exponential one");
            Assert.equal("1000000000000000", VsFormat.toDisplayString(1e15d), "1e15 stays plain");
            Assert.equal("100000000000000", VsFormat.toDisplayString(1e14d), "1e14 stays plain");
            Assert.equal("1E+21", VsFormat.toDisplayString(1e21d), "1e21");
            Assert.equal("0.0001", VsFormat.toDisplayString(1e-4d), "the last plain negative");
            Assert.equal("1E-05", VsFormat.toDisplayString(1e-5d), "1e-5");
            Assert.equal("-1.5E+21", VsFormat.toDisplayString(-1.5e21d), "negative exponential");
            Assert.equal("1608655749757169.8", VsFormat.toDisplayString(1608655749757169.8d),
                    "a 17-digit value below the window keeps its fraction");
            Assert.equal("5E-324", VsFormat.toDisplayString(Double.MIN_VALUE),
                    "Java prints 4.9E-324; .NET prints the shorter literal that round-trips");
        });

        registry.test("double non-finite and signed zero", () -> {
            Assert.equal("NaN", VsFormat.toDisplayString(Double.NaN), "NaN");
            Assert.equal("Infinity", VsFormat.toDisplayString(Double.POSITIVE_INFINITY), "+inf");
            Assert.equal("-Infinity", VsFormat.toDisplayString(Double.NEGATIVE_INFINITY), "-inf");
            Assert.equal("0", VsFormat.toDisplayString(0.0d), "positive zero");
            Assert.equal("-0", VsFormat.toDisplayString(-0.0d), "negative zero is preserved");
        });

        registry.test("float uses the single-precision round-trip window", () -> {
            Assert.equal("1", VsFormat.toDisplayString(1.0f), "1.0f");
            Assert.equal("1000000", VsFormat.toDisplayString(1e6f), "1e6f stays fixed-point");
            Assert.equal("100000000", VsFormat.toDisplayString(1e8f), "the last plain exponent");
            Assert.equal("1E+09", VsFormat.toDisplayString(1e9f), "the first exponential one");
            Assert.equal("16777216", VsFormat.toDisplayString(16777216f), "2^24 prints plain");
            Assert.equal("0.0001", VsFormat.toDisplayString(1e-4f), "the last plain negative");
            Assert.equal("1E-05", VsFormat.toDisplayString(1e-5f), "1e-5f");
            Assert.equal("0.1", VsFormat.toDisplayString(0.1f), "0.1f is not 0.10000000149011612");
            Assert.equal("1E-45", VsFormat.toDisplayString(Float.MIN_VALUE),
                    "Java prints 1.4E-45; .NET prints the shorter literal that round-trips");
        });

        registry.test("string renders itself and null renders empty", () -> {
            Assert.equal("abc", VsFormat.toDisplayString("abc"), "string");
            Assert.equal("", VsFormat.toDisplayString((String) null), "null string");
        });
    }
}
