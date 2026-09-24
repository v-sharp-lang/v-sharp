package vsharp.tests.runtime;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import vsharp.runtime.VsChar;
import vsharp.testkit.Assert;
import vsharp.testkit.TestRegistry;
import vsharp.testkit.TestSuite;

/// Full-BMP .NET-10-oracle-derived coverage for the culture-independent `System.Char`
/// operations.
public final class CharTests implements TestSuite {

    /// SHA-256 of `code|letter-or-digit|upper-invariant|lower-invariant` for all 65,536
    /// UTF-16 code units, one upper-case-hex record per line, produced by .NET 10.0.11.
    private static final String ORACLE_DIGEST =
            "6dae506e9f67855e376551a9b18b382479450e901115c87e69f739474f132cd4";

    @Override
    public String suiteName() {
        return "runtime.char";
    }

    @Override
    public void register(TestRegistry registry) {
        registry.test("invariant operations replay the full .NET 10 BMP corpus", () ->
                Assert.equal(ORACLE_DIGEST, digest(replayCorpus()), "BMP corpus digest"));

        registry.test("classification and invariant casing pin category and Turkish-I edges", () -> {
            Assert.isTrue(VsChar.isLetterOrDigit('A'), "ASCII letter");
            Assert.isTrue(VsChar.isLetterOrDigit('7'), "ASCII digit");
            Assert.isTrue(VsChar.isLetterOrDigit((char) 0x0665), "Arabic-Indic digit");
            Assert.isTrue(VsChar.isLetterOrDigit((char) 0x4E2D), "CJK letter");
            Assert.isFalse(VsChar.isLetterOrDigit((char) 0x00B2), "numeric but not a digit");
            Assert.isFalse(VsChar.isLetterOrDigit((char) 0x0301), "combining mark");
            Assert.isFalse(VsChar.isLetterOrDigit((char) 0xD800), "lone surrogate");

            Assert.equal('A', VsChar.toUpperInvariant('a'), "ASCII upper");
            Assert.equal('a', VsChar.toLowerInvariant('A'), "ASCII lower");
            Assert.equal((char) 0x03A3, VsChar.toUpperInvariant((char) 0x03C2),
                    "final sigma uppercases to capital sigma");
            Assert.equal((char) 0x03C3, VsChar.toLowerInvariant((char) 0x03A3),
                    "capital sigma lowercases to ordinary sigma");
            Assert.equal((char) 0x0131, VsChar.toUpperInvariant((char) 0x0131),
                    "dotless i stays unchanged under .NET invariant char casing");
            Assert.equal((char) 0x0130, VsChar.toLowerInvariant((char) 0x0130),
                    "dotted I stays unchanged under .NET invariant char casing");
        });
    }

    private static String replayCorpus() {
        StringBuilder records = new StringBuilder(1_300_000);
        for (int code = Character.MIN_VALUE; code <= Character.MAX_VALUE; code++) {
            char value = (char) code;
            records.append("%04X|%d|%04X|%04X".formatted(
                    code,
                    VsChar.isLetterOrDigit(value) ? 1 : 0,
                    (int) VsChar.toUpperInvariant(value),
                    (int) VsChar.toLowerInvariant(value)))
                    .append('\n');
        }
        return records.toString();
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
