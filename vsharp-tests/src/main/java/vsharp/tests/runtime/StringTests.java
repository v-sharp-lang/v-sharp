package vsharp.tests.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.function.Supplier;

import vsharp.runtime.VsString;
import vsharp.testkit.Assert;
import vsharp.testkit.TestRegistry;
import vsharp.testkit.TestSuite;

/// .NET-10-oracle-derived coverage for the ordinal `System.String` members: the separator
/// array `Split` and the shaping family `PadLeft`/`PadRight`/`ToCharArray`/`Insert`/
/// `Remove`.
///
/// Each corpus below is the exact cross-product that was executed against the installed
/// .NET 10 SDK, and [#ORACLE_DIGEST]/[#SHAPE_DIGEST] are the SHA-256 values of those runs.
/// The replays reproduce the record text from [VsString] and compare digests, so any
/// divergence in result, part count, thrown-or-not decision or message text fails as a
/// single check, while the individual cases name the behaviours that matter.
public final class StringTests implements TestSuite {

    /// SHA-256 of the `SPLIT`/`RECORDS` records produced by the .NET 10 oracle program.
    private static final String ORACLE_DIGEST =
            "0a6ae425f0b9990cf21b9b3f93e969eb12374dbab2f58135eefcbf89d20fb581";

    /// SHA-256 of the canonicalised shaping records produced by the .NET 10 oracle program.
    private static final String SHAPE_DIGEST =
            "3ba4a7a36c2cfc19cdb77e29c5c8c30aba2e119ab070fd1d5c2334fcb1fcb84a";

    /// SHA-256 of simple upper/lower invariant results for all 1,112,064 Unicode scalar
    /// values, rendered as UTF-16 code units by the .NET 10 oracle.
    private static final String INVARIANT_CASE_DIGEST =
            "facf78c8b5332c8266645b9182a5cae085668f63635c2c860af23747fd1888c5";

    private static final char SP = (char) 0x0020;
    private static final char TAB = (char) 0x0009;
    private static final char LF = (char) 0x000A;
    private static final char CR = (char) 0x000D;
    private static final char NUL = (char) 0x0000;
    private static final char NBSP = (char) 0x00A0;
    private static final char NEL = (char) 0x0085;
    private static final char IDSP = (char) 0x3000;
    private static final char FIGSP = (char) 0x2007;
    private static final char NNBSP = (char) 0x202F;
    private static final char FILESEP = (char) 0x001C;
    private static final char UNITSEP = (char) 0x001F;
    private static final char HI = (char) 0xD83D;
    private static final char LO = (char) 0xDE00;

    @Override
    public String suiteName() {
        return "runtime.string";
    }

    @Override
    public void register(TestRegistry registry) {
        registry.test("split replays the .NET 10 corpus byte for byte", () ->
                Assert.equal(ORACLE_DIGEST, digest(replayCorpus()), "split corpus digest"));

        registry.test("shaping replays the .NET 10 corpus byte for byte", () ->
                Assert.equal(SHAPE_DIGEST, digest(replayShapeCorpus()), "shaping corpus digest"));

        registry.test("invariant casing replays every Unicode scalar value", () ->
                Assert.equal(INVARIANT_CASE_DIGEST, digest(replayInvariantCaseCorpus()),
                        "invariant casing scalar digest"));

        registry.test("invariant casing is simple, context-free and UTF-16 safe", () -> {
            Assert.equal("ABC XYZ", VsString.toUpperInvariant("abc xyz"), "ASCII upper");
            Assert.equal("abc xyz", VsString.toLowerInvariant("ABC XYZ"), "ASCII lower");
            Assert.equal("I\u0130\u0131I", VsString.toUpperInvariant("I\u0130\u0131i"),
                    "Turkish-I upper edge");
            Assert.equal("i\u0130\u0131i", VsString.toLowerInvariant("I\u0130\u0131i"),
                    "Turkish-I lower edge");
            Assert.equal("\u00DF\uFB00\u0149\u0587",
                    VsString.toUpperInvariant("\u00DF\uFB00\u0149\u0587"),
                    "invariant casing does not expand sharp-s or ligatures");
            Assert.equal("\u03BF\u03C3", VsString.toLowerInvariant("\u039F\u03A3"),
                    "invariant casing does not select final sigma");
            Assert.equal("\uD801\uDC00", VsString.toUpperInvariant("\uD801\uDC28"),
                    "supplementary Deseret mapping");
            Assert.equal("x" + HI + "y", VsString.toLowerInvariant("X" + HI + "Y"),
                    "lone surrogate is preserved");
            Assert.throwsException(NullPointerException.class,
                    () -> VsString.toUpperInvariant(null), "null receiver");
        });

        registry.test("padding never truncates and copies the pad unit verbatim", () -> {
            String receiver = "ab";
            Assert.isTrue(receiver == VsString.padLeft(receiver, 2), "width at length returns the receiver");
            Assert.isTrue(receiver == VsString.padRight(receiver, 0), "width below length returns the receiver");
            Assert.equal("   ab", VsString.padLeft(receiver, 5), "default pad is a space");
            Assert.equal("ab...", VsString.padRight(receiver, 5, '.'), "explicit pad character");
            Assert.equal("ab" + NUL + NUL, VsString.padRight(receiver, 4, NUL), "NUL pads like any unit");
            Assert.equal(HI + "" + HI + "ab", VsString.padLeft(receiver, 4, HI),
                    "a lone surrogate pads like any unit");
            Assert.equal("", VsString.padLeft("", 0), "empty stays empty");
        });

        registry.test("padding rejects a negative width the way .NET does", () -> {
            IllegalArgumentException failure = Assert.throwsException(IllegalArgumentException.class,
                    () -> VsString.padLeft("ab", -1), "negative width");
            Assert.equal("totalWidth ('-1') must be a non-negative value. (Parameter 'totalWidth')"
                    + "\nActual value was -1.", failure.getMessage(), "totalWidth message");
            Assert.throwsException(IllegalArgumentException.class,
                    () -> VsString.padRight("ab", -1, '.'), "negative width with pad character");
        });

        registry.test("ToCharArray copies code units, not code points", () -> {
            Assert.equal(0, VsString.toCharArray("").length, "empty array");
            char[] pair = VsString.toCharArray(HI + "" + LO);
            Assert.equal(2, pair.length, "surrogate pair splits into two units");
            Assert.equal(HI, pair[0], "high surrogate");
            Assert.equal(LO, pair[1], "low surrogate");
            char[] first = VsString.toCharArray("ab");
            first[0] = 'z';
            Assert.equal('a', VsString.toCharArray("ab")[0], "each call returns a fresh copy");
        });

        registry.test("Insert validates its argument before its index", () -> {
            Assert.equal("Xa", VsString.insert("a", 0, "X"), "insert at the start");
            Assert.equal("aXY", VsString.insert("a", 1, "XY"), "insert at the end");
            Assert.equal("a" + HI + LO, VsString.insert("a", 1, HI + "" + LO), "insert a surrogate pair");
            IllegalArgumentException nullFirst = Assert.throwsException(IllegalArgumentException.class,
                    () -> VsString.insert("ab", -1, null), "null argument with an invalid index");
            Assert.equal("Value cannot be null. (Parameter 'value')", nullFirst.getMessage(),
                    "the argument check runs first");
            IllegalArgumentException unsigned = Assert.throwsException(IllegalArgumentException.class,
                    () -> VsString.insert("ab", -1, "X"), "negative index");
            Assert.equal("startIndex ('4294967295') must be less than or equal to '2'. (Parameter 'startIndex')"
                    + "\nActual value was 4294967295.", unsigned.getMessage(),
                    "the index test is unsigned");
        });

        registry.test("Remove keeps .NET's two distinct diagnostic shapes", () -> {
            Assert.equal("a", VsString.remove("ab", 1), "one-argument remove truncates");
            Assert.equal("ab", VsString.remove("ab", 2), "removing at the length keeps everything");
            Assert.equal("aa", VsString.remove("ababa", 1, 3), "two-argument remove cuts a window");
            Assert.equal("StartIndex cannot be less than zero. (Parameter 'startIndex')",
                    Assert.throwsException(IllegalArgumentException.class,
                            () -> VsString.remove("ab", -1), "negative start").getMessage(),
                    "legacy negative message");
            Assert.equal("startIndex cannot be larger than length of string. (Parameter 'startIndex')",
                    Assert.throwsException(IllegalArgumentException.class,
                            () -> VsString.remove("ab", 3), "start past the end").getMessage(),
                    "legacy overflow message");
            Assert.equal("count ('0') must be less than or equal to '-1'. (Parameter 'count')"
                    + "\nActual value was 0.",
                    Assert.throwsException(IllegalArgumentException.class,
                            () -> VsString.remove("ab", 3, 0), "start past the end with a count").getMessage(),
                    "the two-argument form bounds count, never the start index");
        });

        registry.test("shaping has C#'s receiver contract", () -> {
            Assert.throwsException(NullPointerException.class,
                    () -> VsString.padLeft(null, 1), "null receiver for PadLeft");
            Assert.throwsException(NullPointerException.class,
                    () -> VsString.toCharArray(null), "null receiver for ToCharArray");
            Assert.throwsException(NullPointerException.class,
                    () -> VsString.insert(null, 0, null), "null receiver precedes the argument check");
            Assert.throwsException(NullPointerException.class,
                    () -> VsString.remove(null, 0, 0), "null receiver for Remove");
        });

        registry.test("split keeps every empty entry", () -> {
            parts("empty receiver", VsString.split("", comma()), "");
            parts("lone separator", VsString.split(",", comma()), "", "");
            parts("consecutive separators", VsString.split("a,,b", comma()), "a", "", "b");
            parts("leading and trailing separators",
                    VsString.split(",a,b,", comma()), "", "a", "b", "");
        });

        registry.test("a null or empty separator falls back to C# whitespace", () -> {
            parts("null separator",
                    VsString.split("  a  b  ", null), "", "", "a", "", "b", "", "");
            parts("empty separator",
                    VsString.split("  a  b  ", new char[0]), "", "", "a", "", "b", "", "");
            parts("control whitespace",
                    VsString.split(TAB + "a" + LF + "b" + CR + "c", null), "", "a", "b", "c");
        });

        registry.test("the whitespace fallback is C#'s set and not Java's", () -> {
            // C# treats NBSP, NEL, FIGSP and NNBSP as whitespace; Java's `isWhitespace` does
            // not. Java treats the file and unit separators as whitespace; C# does not.
            parts("C#-only set",
                    VsString.split(NEL + "a" + FIGSP + "b" + NNBSP + "c", null),
                    "", "a", "b", "c");
            parts("no-break spaces",
                    VsString.split("" + NBSP + "a" + NBSP + IDSP + "b", null),
                    "", "a", "", "b");
            parts("Java-only set excluded",
                    VsString.split(FILESEP + "a" + UNITSEP + "b", null),
                    FILESEP + "a" + UNITSEP + "b");
            parts("NUL is not whitespace",
                    VsString.split("a" + NUL + "b", null), "a" + NUL + "b");
        });

        registry.test("split matches by code unit and not by code point", () -> {
            // A lone high surrogate separator splits inside a surrogate pair, exactly as the
            // .NET 10 oracle records: the scan never composes code points.
            parts("lone high surrogate",
                    VsString.split("a" + HI + LO + "b", new char[] {HI}), "a", LO + "b");
            parts("pair survives a real split",
                    VsString.split(HI + "" + LO + ",X", comma()), "" + HI + LO, "X");
        });

        registry.test("split has C#'s receiver contract", () -> {
            NullPointerException failure = Assert.throwsException(NullPointerException.class,
                    () -> VsString.split(null, comma()), "null receiver");
            Assert.equal("Object reference not set to an instance of an object.",
                    failure.getMessage(), "null receiver message");
        });
    }

    /// Compares one split result against its expected parts in declaration order.
    private static void parts(String what, String[] actual, String... expected) {
        Assert.equalList(Arrays.asList(expected), Arrays.asList(actual), what);
    }

    /// Rebuilds the oracle program's record text from [VsString#split].
    private static String replayCorpus() {
        String[] receivers = {
            "",
            "" + SP,
            "a",
            "abc",
            "a b c",
            "a,b,c",
            ",a,b,",
            ",,",
            "a,,b",
            ",",
            "" + SP + SP + "a" + SP + SP + "b" + SP + SP,
            "" + TAB + "a" + LF + "b" + CR + "c",
            "a b",
            "a  b",
            " a ",
            "a b ",
            " a b",
            "" + NUL,
            "a" + NUL + "b",
            "a" + NUL + NUL + "b",
            "" + NBSP + "a" + NBSP,
            "" + IDSP + "a" + IDSP + "b",
            "" + NEL + "a" + FIGSP + "b" + NNBSP + "c",
            "" + FILESEP + "a" + UNITSEP + "b",
            "" + HI + LO + ",X",
            "a" + HI + LO + "b",
            "" + SP + SP + SP,
            "aXbXXc",
            "a.b.c",
            "key=val",
            "a, b",
        };
        char[][] separators = {
            null,
            {},
            {','},
            {SP},
            {',', ';'},
            {NUL},
            {'X'},
            {'.'},
            {'='},
            {'a'},
            {HI},
            {'b', 'c'},
            {SP, ','},
            {NBSP},
            {TAB, LF},
        };
        StringBuilder records = new StringBuilder();
        int count = 0;
        for (String receiver : receivers) {
            for (char[] separator : separators) {
                String[] parts = VsString.split(receiver, separator);
                records.append("SPLIT|").append(escape(receiver)).append('|')
                        .append(describe(separator)).append('|')
                        .append(parts.length).append('|');
                for (int i = 0; i < parts.length; i++) {
                    if (i != 0) {
                        records.append(';');
                    }
                    records.append(escape(parts[i]));
                }
                records.append('\n');
                count++;
            }
        }
        return records.append("RECORDS|").append(count).append('\n').toString();
    }

    /// Rebuilds the shaping oracle's record text from the [VsString] shaping members.
    ///
    /// The oracle's exception columns are canonicalised to the message alone, because V#'s
    /// corelib maps both `ArgumentOutOfRangeException` and `ArgumentNullException` onto
    /// `System.ArgumentException`; the shipped message text, and the exact set of
    /// cases that throw at all, are reproduced verbatim.
    private static String replayShapeCorpus() {
        String[] receivers = {
            "",
            "a",
            "ab",
            "ababa",
            "" + SP + 'X' + SP,
            "" + NBSP + "a",
            "" + HI + LO,
            "a" + NUL + "b",
        };
        int[] widths = {-1, 0, 1, 2, 5, 10};
        char[] pads = {SP, '.', NUL, HI};
        String[] inserted = {null, "", "X", "XY", "" + HI + LO};

        StringBuilder records = new StringBuilder();
        int count = 0;
        for (String receiver : receivers) {
            records.append("CHARS|").append(escape(receiver)).append('|')
                    .append(run(() -> {
                        char[] units = VsString.toCharArray(receiver);
                        StringBuilder text = new StringBuilder().append(units.length).append(':');
                        for (int i = 0; i < units.length; i++) {
                            if (i != 0) {
                                text.append(',');
                            }
                            text.append("%04X".formatted((int) units[i]));
                        }
                        return text.toString();
                    })).append('\n');
            count++;

            for (int width : widths) {
                records.append("PADL|").append(escape(receiver)).append('|').append(width).append('|')
                        .append(run(() -> VsString.padLeft(receiver, width))).append('\n');
                records.append("PADR|").append(escape(receiver)).append('|').append(width).append('|')
                        .append(run(() -> VsString.padRight(receiver, width))).append('\n');
                count += 2;
                for (char pad : pads) {
                    records.append("PADLC|").append(escape(receiver)).append('|').append(width).append('|')
                            .append(escape(String.valueOf(pad))).append('|')
                            .append(run(() -> VsString.padLeft(receiver, width, pad))).append('\n');
                    records.append("PADRC|").append(escape(receiver)).append('|').append(width).append('|')
                            .append(escape(String.valueOf(pad))).append('|')
                            .append(run(() -> VsString.padRight(receiver, width, pad))).append('\n');
                    count += 2;
                }
            }

            for (int index = -1; index <= receiver.length() + 1; index++) {
                int at = index;
                for (String value : inserted) {
                    records.append("INS|").append(escape(receiver)).append('|').append(at).append('|')
                            .append(escape(value)).append('|')
                            .append(run(() -> VsString.insert(receiver, at, value))).append('\n');
                    count++;
                }
                records.append("REM1|").append(escape(receiver)).append('|').append(at).append('|')
                        .append(run(() -> VsString.remove(receiver, at))).append('\n');
                count++;
                for (int howMany = -1; howMany <= receiver.length() + 1; howMany++) {
                    int removed = howMany;
                    records.append("REM2|").append(escape(receiver)).append('|').append(at).append('|')
                            .append(removed).append('|')
                            .append(run(() -> VsString.remove(receiver, at, removed))).append('\n');
                    count++;
                }
            }
        }
        return records.append("RECORDS|").append(count).append('\n').toString();
    }

    /// Rebuilds the .NET oracle's one-record-per-Unicode-scalar invariant-casing stream.
    private static String replayInvariantCaseCorpus() {
        StringBuilder records = new StringBuilder(32_000_000);
        for (int codePoint = Character.MIN_CODE_POINT;
                codePoint <= Character.MAX_CODE_POINT; codePoint++) {
            if (codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE) {
                continue;
            }
            String source = Character.toString(codePoint);
            records.append("S|");
            appendHex(records, codePoint, 6);
            records.append('|');
            appendUnits(records, VsString.toUpperInvariant(source));
            records.append('|');
            appendUnits(records, VsString.toLowerInvariant(source));
            records.append('\n');
        }
        return records.toString();
    }

    private static void appendUnits(StringBuilder target, String value) {
        for (int index = 0; index < value.length(); index++) {
            if (index != 0) {
                target.append(',');
            }
            appendHex(target, value.charAt(index), 4);
        }
    }

    private static void appendHex(StringBuilder target, int value, int width) {
        int shift = (width - 1) * 4;
        for (; shift >= 0; shift -= 4) {
            int digit = value >>> shift & 0xF;
            target.append((char) (digit < 10 ? '0' + digit : 'A' + digit - 10));
        }
    }

    /// One corpus outcome: the escaped result, or the escaped message of whatever was thrown.
    private static String run(Supplier<String> body) {
        try {
            return "OK|" + escape(body.get());
        } catch (RuntimeException failure) {
            return "EX|" + escape(failure.getMessage());
        }
    }

    /// The oracle's separator column: `<null>`, or the code units in upper-case hexadecimal.
    private static String describe(char[] separator) {
        if (separator == null) {
            return "<null>";
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < separator.length; i++) {
            if (i != 0) {
                text.append(',');
            }
            text.append("%04X".formatted((int) separator[i]));
        }
        return text.toString();
    }

    /// The oracle's escape: printable ASCII survives except the delimiters the format uses.
    private static String escape(String value) {
        if (value == null) {
            return "<null>";
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character >= ' ' && character <= '~'
                    && character != '\\' && character != '|' && character != ';') {
                text.append(character);
            } else {
                text.append("\\u").append("%04X".formatted((int) character));
            }
        }
        return text.toString();
    }

    private static char[] comma() {
        return new char[] {','};
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
