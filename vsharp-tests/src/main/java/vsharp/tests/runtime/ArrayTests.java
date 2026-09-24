package vsharp.tests.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import vsharp.runtime.VsArray;
import vsharp.testkit.Assert;
import vsharp.testkit.TestRegistry;
import vsharp.testkit.TestSuite;

/// .NET-10-oracle-derived coverage for the curated `System.Array` statics.
///
/// [#ORACLE_DIGEST] is the SHA-256 of a 212-record run of `Sort`, `Reverse` and `IndexOf`
/// over every admitted element type, executed against the installed .NET 10 SDK. Elements
/// are rendered as their JVM *carrier* bits in hexadecimal on both sides, so the comparison
/// involves no formatting model and cannot silently agree through a shared bug. The named
/// cases below pin the three behaviours that are not shared with `java.util.Arrays`.
public final class ArrayTests implements TestSuite {

    /// SHA-256 of the records produced by the .NET 10 oracle program.
    private static final String ORACLE_DIGEST =
            "aaac57990016657c7b1ae9d0f8b7dd560b197369d838263ac941204f12c1a6c1";

    private static final char HI = (char) 0xD83D;
    private static final char LO = (char) 0xDE00;
    /// .NET's `float.NaN`/`double.NaN` are the *negative* quiet NaN, while the JDK's
    /// `Float.NaN`/`Double.NaN` are the positive one. The corpus feeds .NET's bits so both
    /// sides receive identical input; the named cases below deliberately mix both payloads
    /// to prove the helpers are payload-independent.
    private static final float NET_NAN_F = Float.intBitsToFloat(0xFFC00000);
    private static final double NET_NAN = Double.longBitsToDouble(0xFFF8000000000000L);

    @Override
    public String suiteName() {
        return "runtime.array";
    }

    @Override
    public void register(TestRegistry registry) {
        registry.test("array statics replay the .NET 10 corpus byte for byte", () ->
                Assert.equal(ORACLE_DIGEST, digest(replayCorpus()), "array corpus digest"));

        registry.test("unsigned element types sort by their unsigned value", () -> {
            byte[] bytes = {(byte) 200, 5, (byte) 255, 0, (byte) 128};
            VsArray.sortByte(bytes);
            Assert.equal("00,05,80,C8,FF", hexBytes(bytes), "byte sorts unsigned");
            byte[] signed = {(byte) 200, 5, (byte) 255, 0, (byte) 128};
            VsArray.sortSByte(signed);
            Assert.equal("80,C8,FF,00,05", hexBytes(signed), "sbyte sorts signed over the same carrier");

            int[] unsignedInts = {-1, 1, Integer.MIN_VALUE, 0};
            VsArray.sortUInt(unsignedInts);
            Assert.equal("00000000,00000001,80000000,FFFFFFFF", hexInts(unsignedInts), "uint sorts unsigned");
            int[] signedInts = {-1, 1, Integer.MIN_VALUE, 0};
            VsArray.sortInt(signedInts);
            Assert.equal("80000000,FFFFFFFF,00000000,00000001", hexInts(signedInts), "int sorts signed");

            long[] unsignedLongs = {-1L, 1L, Long.MIN_VALUE, 0L};
            VsArray.sortULong(unsignedLongs);
            Assert.equal(0L, unsignedLongs[0], "ulong smallest");
            Assert.equal(-1L, unsignedLongs[3], "ulong largest is all ones");

            short[] unsignedShorts = {-1, 1, Short.MIN_VALUE, 0};
            VsArray.sortUShort(unsignedShorts);
            Assert.equal(0, unsignedShorts[0], "ushort smallest");
            Assert.equal(-1, unsignedShorts[3], "ushort largest is all ones");
        });

        registry.test("floating sorts place NaN first, unlike the JDK's total order", () -> {
            // The JDK sorts NaN last; C# sorts it first. Signed zero agrees in both.
            double[] doubles = {1.0, Double.NaN, -0.0, 0.0, Double.NEGATIVE_INFINITY,
                Double.POSITIVE_INFINITY, -1.0};
            VsArray.sortDouble(doubles);
            Assert.isTrue(Double.isNaN(doubles[0]), "NaN sorts first");
            Assert.equal(Double.NEGATIVE_INFINITY, doubles[1], "then negative infinity");
            Assert.equal(-1.0, doubles[2], "then the negative value");
            Assert.equal(Long.MIN_VALUE, Double.doubleToRawLongBits(doubles[3]), "then negative zero");
            Assert.equal(0L, Double.doubleToRawLongBits(doubles[4]), "then positive zero");
            Assert.equal(1.0, doubles[5], "then the positive value");
            Assert.equal(Double.POSITIVE_INFINITY, doubles[6], "then positive infinity");

            float[] floats = {1f, Float.NaN, -0f, 0f, Float.NEGATIVE_INFINITY};
            VsArray.sortFloat(floats);
            Assert.isTrue(Float.isNaN(floats[0]), "float NaN sorts first");
            Assert.equal(Float.NEGATIVE_INFINITY, floats[1], "float negative infinity follows");

            // Every-element and no-element NaN cases must not rotate anything away.
            double[] allNaN = {Double.NaN, NET_NAN};
            VsArray.sortDouble(allNaN);
            Assert.isTrue(Double.isNaN(allNaN[0]) && Double.isNaN(allNaN[1]), "all NaN stays all NaN");
            double[] noNaN = {2.0, 1.0};
            VsArray.sortDouble(noNaN);
            Assert.equal(1.0, noNaN[0], "an array without NaN is untouched by the rotation");
            double[] empty = {};
            VsArray.sortDouble(empty);
            Assert.equal(0, empty.length, "empty stays empty");
        });

        registry.test("IndexOf uses Equals, so NaN matches NaN and -0.0 matches 0.0", () -> {
            Assert.equal(1, VsArray.indexOfDouble(new double[] {1.0, Double.NaN}, Double.NaN),
                    "NaN is found, which `==` could never do");
            Assert.equal(1, VsArray.indexOfDouble(new double[] {1.0, NET_NAN}, Double.NaN),
                    "any NaN payload matches any other");
            Assert.equal(0, VsArray.indexOfDouble(new double[] {-0.0}, 0.0),
                    "positive zero finds negative zero, which raw-bit equality would miss");
            Assert.equal(0, VsArray.indexOfDouble(new double[] {0.0}, -0.0),
                    "and the other way round");
            Assert.equal(1, VsArray.indexOfFloat(new float[] {1f, NET_NAN_F}, Float.NaN),
                    "float NaN matches");
            Assert.equal(0, VsArray.indexOfFloat(new float[] {-0f}, 0f), "float signed zero matches");
            Assert.equal(0, VsArray.indexOfInt(new int[] {7, 7}, 7), "the first occurrence wins");
            Assert.equal(-1, VsArray.indexOfInt(new int[] {1}, 9), "an absent value is -1");
            Assert.equal(-1, VsArray.indexOfInt(new int[0], 0), "an empty array is -1");
        });

        registry.test("IndexOf over strings is ordinal and null-tolerant", () -> {
            // Proved against the oracle with a culture-equal but ordinally different pair:
            // U+00C5 must not be found in an array holding U+0041 U+030A.
            String precomposed = String.valueOf((char) 0x00C5);
            String decomposed = "" + (char) 0x0041 + (char) 0x030A;
            Assert.equal(-1, VsArray.indexOfString(new String[] {decomposed}, precomposed),
                    "a culture-sensitive search would have found this");
            Assert.equal(0, VsArray.indexOfString(new String[] {null, "a"}, null), "null is found");
            Assert.equal(1, VsArray.indexOfString(new String[] {"A", "a"}, "a"), "case is significant");
            Assert.equal(-1, VsArray.indexOfString(new String[] {"a"}, null), "null is absent when unheld");
        });

        registry.test("Reverse is in place over every length", () -> {
            int[] four = {1, 2, 3, 4};
            VsArray.reverseInt(four);
            Assert.equal("00000004,00000003,00000002,00000001", hexInts(four), "even length");
            int[] three = {1, 2, 3};
            VsArray.reverseInt(three);
            Assert.equal("00000003,00000002,00000001", hexInts(three), "odd length keeps the middle");
            int[] one = {1};
            VsArray.reverseInt(one);
            Assert.equal("00000001", hexInts(one), "single element");
            int[] none = {};
            VsArray.reverseInt(none);
            Assert.equal(0, none.length, "empty");
            String[] words = {"a", null, "b"};
            VsArray.reverseString(words);
            Assert.equal("b", words[0], "strings reverse");
            Assert.equal(null, words[1], "including nulls");
        });

        registry.test("every member rejects a null array the way .NET does", () -> {
            String expected = "Value cannot be null. (Parameter 'array')";
            Assert.equal(expected, Assert.throwsException(IllegalArgumentException.class,
                    () -> VsArray.sortInt(null), "null sort").getMessage(), "sort message");
            Assert.equal(expected, Assert.throwsException(IllegalArgumentException.class,
                    () -> VsArray.reverseInt(null), "null reverse").getMessage(), "reverse message");
            Assert.equal(expected, Assert.throwsException(IllegalArgumentException.class,
                    () -> VsArray.indexOfInt(null, 1), "null indexOf").getMessage(), "indexOf message");
            Assert.equal(expected, Assert.throwsException(IllegalArgumentException.class,
                    () -> VsArray.indexOfString(null, "a"), "null string indexOf").getMessage(),
                    "string indexOf message");
            Assert.equal(expected, Assert.throwsException(IllegalArgumentException.class,
                    () -> VsArray.sortDouble(null), "null double sort").getMessage(), "double sort message");
        });
    }

    /// Rebuilds the oracle program's record text from [VsArray].
    private static String replayCorpus() {
        Records records = new Records();

        byte[][] sbytes = {{}, {0}, {-128, 127, 0, -1}, {5, 5, -5}};
        byte[] sbyteNeedles = {0, -1, 127, 9};
        for (byte[] input : sbytes) {
            records.sorted("sbyte", hexBytes(input), copyAndSortSByte(input));
            records.reversed("sbyte", hexBytes(input), copyAndReverseByte(input));
            for (byte needle : sbyteNeedles) {
                records.searched("sbyte", hexBytes(input), hexBytes(new byte[] {needle}),
                        VsArray.indexOfByte(input, needle));
            }
        }

        byte[][] bytes = {{}, {(byte) 200, 5, (byte) 255, 0, (byte) 128}, {1, 1}};
        byte[] byteNeedles = {0, (byte) 255, (byte) 200, 9};
        for (byte[] input : bytes) {
            records.sorted("byte", hexBytes(input), copyAndSortByte(input));
            records.reversed("byte", hexBytes(input), copyAndReverseByte(input));
            for (byte needle : byteNeedles) {
                records.searched("byte", hexBytes(input), hexBytes(new byte[] {needle}),
                        VsArray.indexOfByte(input, needle));
            }
        }

        short[][] shorts = {{}, {-32768, 32767, 0, -1}, {3, 3}};
        short[] shortNeedles = {0, -1, 32767, 9};
        for (short[] input : shorts) {
            records.sorted("short", hexShorts(input), copyAndSortShort(input, true));
            records.reversed("short", hexShorts(input), copyAndReverseShort(input));
            for (short needle : shortNeedles) {
                records.searched("short", hexShorts(input), hexShorts(new short[] {needle}),
                        VsArray.indexOfShort(input, needle));
            }
        }

        short[][] ushorts = {{}, {-1, 1, Short.MIN_VALUE, 0}};
        short[] ushortNeedles = {0, -1, Short.MIN_VALUE, 9};
        for (short[] input : ushorts) {
            records.sorted("ushort", hexShorts(input), copyAndSortShort(input, false));
            records.reversed("ushort", hexShorts(input), copyAndReverseShort(input));
            for (short needle : ushortNeedles) {
                records.searched("ushort", hexShorts(input), hexShorts(new short[] {needle}),
                        VsArray.indexOfShort(input, needle));
            }
        }

        int[][] ints = {{}, {5, -3, 0, Integer.MIN_VALUE, Integer.MAX_VALUE}, {7, 7}};
        int[] intNeedles = {0, 7, Integer.MIN_VALUE, 9};
        for (int[] input : ints) {
            records.sorted("int", hexInts(input), copyAndSortInt(input, true));
            records.reversed("int", hexInts(input), copyAndReverseInt(input));
            for (int needle : intNeedles) {
                records.searched("int", hexInts(input), hexInts(new int[] {needle}),
                        VsArray.indexOfInt(input, needle));
            }
        }

        int[][] uints = {{}, {-1, 1, Integer.MIN_VALUE, 0}};
        int[] uintNeedles = {0, -1, Integer.MIN_VALUE, 9};
        for (int[] input : uints) {
            records.sorted("uint", hexInts(input), copyAndSortInt(input, false));
            records.reversed("uint", hexInts(input), copyAndReverseInt(input));
            for (int needle : uintNeedles) {
                records.searched("uint", hexInts(input), hexInts(new int[] {needle}),
                        VsArray.indexOfInt(input, needle));
            }
        }

        long[][] longs = {{}, {Long.MIN_VALUE, Long.MAX_VALUE, 0, -1}};
        long[] longNeedles = {0, -1, Long.MAX_VALUE, 9};
        for (long[] input : longs) {
            records.sorted("long", hexLongs(input), copyAndSortLong(input, true));
            records.reversed("long", hexLongs(input), copyAndReverseLong(input));
            for (long needle : longNeedles) {
                records.searched("long", hexLongs(input), hexLongs(new long[] {needle}),
                        VsArray.indexOfLong(input, needle));
            }
        }

        long[][] ulongs = {{}, {-1L, 1L, Long.MIN_VALUE, 0L}};
        long[] ulongNeedles = {0L, -1L, Long.MIN_VALUE, 9L};
        for (long[] input : ulongs) {
            records.sorted("ulong", hexLongs(input), copyAndSortLong(input, false));
            records.reversed("ulong", hexLongs(input), copyAndReverseLong(input));
            for (long needle : ulongNeedles) {
                records.searched("ulong", hexLongs(input), hexLongs(new long[] {needle}),
                        VsArray.indexOfLong(input, needle));
            }
        }

        char[][] chars = {{}, {'b', 'A', (char) 0xFFFF, ' '}, {HI, LO}};
        char[] charNeedles = {'A', (char) 0xFFFF, 'z'};
        for (char[] input : chars) {
            records.sorted("char", hexChars(input), copyAndSortChar(input));
            records.reversed("char", hexChars(input), copyAndReverseChar(input));
            for (char needle : charNeedles) {
                records.searched("char", hexChars(input), hexChars(new char[] {needle}),
                        VsArray.indexOfChar(input, needle));
            }
        }

        float[][] floats = {{}, {1f, NET_NAN_F, -0f, 0f, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY},
            {NET_NAN_F, NET_NAN_F}, {-0f}};
        float[] floatNeedles = {NET_NAN_F, 0f, -0f, 1f, 2f};
        for (float[] input : floats) {
            records.sorted("float", hexFloats(input), copyAndSortFloat(input));
            records.reversed("float", hexFloats(input), copyAndReverseFloat(input));
            for (float needle : floatNeedles) {
                records.searched("float", hexFloats(input), hexFloats(new float[] {needle}),
                        VsArray.indexOfFloat(input, needle));
            }
        }

        double[][] doubles = {{}, {1.0, NET_NAN, -0.0, 0.0, Double.NEGATIVE_INFINITY,
            Double.POSITIVE_INFINITY, -1.0}, {NET_NAN, NET_NAN}, {-0.0}};
        double[] doubleNeedles = {NET_NAN, 0.0, -0.0, 1.0, 2.0};
        for (double[] input : doubles) {
            records.sorted("double", hexDoubles(input), copyAndSortDouble(input));
            records.reversed("double", hexDoubles(input), copyAndReverseDouble(input));
            for (double needle : doubleNeedles) {
                records.searched("double", hexDoubles(input), hexDoubles(new double[] {needle}),
                        VsArray.indexOfDouble(input, needle));
            }
        }

        String[][] strings = {{}, {"b", "A", null, "a"}, {"", "a"}};
        String[] stringNeedles = {"a", null, "", "zz"};
        for (String[] input : strings) {
            records.reversed("string", hexStrings(input), copyAndReverseString(input));
            for (String needle : stringNeedles) {
                records.searched("string", hexStrings(input), hexStrings(new String[] {needle}),
                        VsArray.indexOfString(input, needle));
            }
        }

        return records.finish();
    }

    /// Accumulates the oracle's three record shapes and its trailing count.
    private static final class Records {
        private final StringBuilder text = new StringBuilder();
        private int count;

        void sorted(String tag, String input, String output) {
            text.append("SORT|").append(tag).append('|').append(input).append('|').append(output).append('\n');
            count++;
        }

        void reversed(String tag, String input, String output) {
            text.append("REV|").append(tag).append('|').append(input).append('|').append(output).append('\n');
            count++;
        }

        void searched(String tag, String input, String needle, int result) {
            text.append("IDX|").append(tag).append('|').append(input).append('|').append(needle)
                    .append('|').append(result).append('\n');
            count++;
        }

        String finish() {
            return text.append("RECORDS|").append(count).append('\n').toString();
        }
    }

    private static String copyAndSortSByte(byte[] input) {
        byte[] copy = input.clone();
        VsArray.sortSByte(copy);
        return hexBytes(copy);
    }

    private static String copyAndSortByte(byte[] input) {
        byte[] copy = input.clone();
        VsArray.sortByte(copy);
        return hexBytes(copy);
    }

    private static String copyAndReverseByte(byte[] input) {
        byte[] copy = input.clone();
        VsArray.reverseByte(copy);
        return hexBytes(copy);
    }

    private static String copyAndSortShort(short[] input, boolean signed) {
        short[] copy = input.clone();
        if (signed) {
            VsArray.sortShort(copy);
        } else {
            VsArray.sortUShort(copy);
        }
        return hexShorts(copy);
    }

    private static String copyAndReverseShort(short[] input) {
        short[] copy = input.clone();
        VsArray.reverseShort(copy);
        return hexShorts(copy);
    }

    private static String copyAndSortInt(int[] input, boolean signed) {
        int[] copy = input.clone();
        if (signed) {
            VsArray.sortInt(copy);
        } else {
            VsArray.sortUInt(copy);
        }
        return hexInts(copy);
    }

    private static String copyAndReverseInt(int[] input) {
        int[] copy = input.clone();
        VsArray.reverseInt(copy);
        return hexInts(copy);
    }

    private static String copyAndSortLong(long[] input, boolean signed) {
        long[] copy = input.clone();
        if (signed) {
            VsArray.sortLong(copy);
        } else {
            VsArray.sortULong(copy);
        }
        return hexLongs(copy);
    }

    private static String copyAndReverseLong(long[] input) {
        long[] copy = input.clone();
        VsArray.reverseLong(copy);
        return hexLongs(copy);
    }

    private static String copyAndSortChar(char[] input) {
        char[] copy = input.clone();
        VsArray.sortChar(copy);
        return hexChars(copy);
    }

    private static String copyAndReverseChar(char[] input) {
        char[] copy = input.clone();
        VsArray.reverseChar(copy);
        return hexChars(copy);
    }

    private static String copyAndSortFloat(float[] input) {
        float[] copy = input.clone();
        VsArray.sortFloat(copy);
        return hexFloats(copy);
    }

    private static String copyAndReverseFloat(float[] input) {
        float[] copy = input.clone();
        VsArray.reverseFloat(copy);
        return hexFloats(copy);
    }

    private static String copyAndSortDouble(double[] input) {
        double[] copy = input.clone();
        VsArray.sortDouble(copy);
        return hexDoubles(copy);
    }

    private static String copyAndReverseDouble(double[] input) {
        double[] copy = input.clone();
        VsArray.reverseDouble(copy);
        return hexDoubles(copy);
    }

    private static String copyAndReverseString(String[] input) {
        String[] copy = input.clone();
        VsArray.reverseString(copy);
        return hexStrings(copy);
    }

    private static String hexBytes(byte[] values) {
        if (values.length == 0) {
            return "<empty>";
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i != 0) {
                text.append(',');
            }
            text.append("%02X".formatted(values[i] & 0xFF));
        }
        return text.toString();
    }

    private static String hexShorts(short[] values) {
        if (values.length == 0) {
            return "<empty>";
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i != 0) {
                text.append(',');
            }
            text.append("%04X".formatted(values[i] & 0xFFFF));
        }
        return text.toString();
    }

    private static String hexInts(int[] values) {
        if (values.length == 0) {
            return "<empty>";
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i != 0) {
                text.append(',');
            }
            text.append("%08X".formatted(values[i]));
        }
        return text.toString();
    }

    private static String hexLongs(long[] values) {
        if (values.length == 0) {
            return "<empty>";
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i != 0) {
                text.append(',');
            }
            text.append("%016X".formatted(values[i]));
        }
        return text.toString();
    }

    private static String hexChars(char[] values) {
        if (values.length == 0) {
            return "<empty>";
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i != 0) {
                text.append(',');
            }
            text.append("%04X".formatted((int) values[i]));
        }
        return text.toString();
    }

    private static String hexFloats(float[] values) {
        if (values.length == 0) {
            return "<empty>";
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i != 0) {
                text.append(',');
            }
            text.append("%08X".formatted(Float.floatToRawIntBits(values[i])));
        }
        return text.toString();
    }

    private static String hexDoubles(double[] values) {
        if (values.length == 0) {
            return "<empty>";
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i != 0) {
                text.append(',');
            }
            text.append("%016X".formatted(Double.doubleToRawLongBits(values[i])));
        }
        return text.toString();
    }

    private static String hexStrings(String[] values) {
        if (values.length == 0) {
            return "<empty>";
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i != 0) {
                text.append(',');
            }
            text.append(hexString(values[i]));
        }
        return text.toString();
    }

    private static String hexString(String value) {
        if (value == null) {
            return "<null>";
        }
        if (value.isEmpty()) {
            return "<empty>";
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            text.append("%04X".formatted((int) value.charAt(i)));
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
