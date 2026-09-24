package vsharp.tests.runtime;

import vsharp.runtime.VsBoolean;
import vsharp.runtime.VsFormatException;
import vsharp.testkit.Assert;
import vsharp.testkit.TestRegistry;
import vsharp.testkit.TestSuite;

/// Exhaustive .NET-10-oracle-derived coverage for the Boolean string parser.
public final class BooleanTests implements TestSuite {

    @Override
    public String suiteName() {
        return "runtime.boolean";
    }

    @Override
    public void register(TestRegistry registry) {
        registry.test("tokens accept every ASCII case combination", () -> {
            for (int mask = 0; mask < 16; mask++) {
                String token = caseVariant("true", mask);
                Assert.isTrue(VsBoolean.parse(token), "true token " + token);
            }
            for (int mask = 0; mask < 32; mask++) {
                String token = caseVariant("false", mask);
                Assert.isFalse(VsBoolean.parse(token), "false token " + token);
            }
        });

        registry.test("trim set is exactly C# whitespace plus NUL", () -> {
            for (int code = 0; code <= 0xFFFF; code++) {
                char character = (char) code;
                boolean[] result = {false};
                boolean success = VsBoolean.tryParse(
                        character + "True" + character, result);
                boolean expected = character == 0 || isCSharpWhiteSpace(character);
                Assert.equal(expected, success, "trim U+%04X".formatted(code));
                Assert.equal(expected, result[0], "trim value U+%04X".formatted(code));
            }
            Assert.isTrue(VsBoolean.parse(" \0\tTrue\r\n\0 "),
                    "mixed trim characters");
        });

        registry.test("malformed input has the exact Boolean exception contract", () -> {
            String[] malformed = {"", " ", "T", "F", "1", "yes", "+True",
                    "Tr ue", "truefalse", "\0", "Tr\0ue", "True\0x",
                    "\uFF34rue", "fal\u017Fe", "\u001CTrue\u001C"};
            for (String text : malformed) {
                VsFormatException failure = Assert.throwsException(VsFormatException.class,
                        () -> VsBoolean.parse(text), "format " + text);
                Assert.equal("String '" + text + "' was not recognized as a valid Boolean.",
                        failure.getMessage(), "format message");
            }
            IllegalArgumentException nullFailure = Assert.throwsException(
                    IllegalArgumentException.class,
                    () -> VsBoolean.parse(null), "null is ArgumentException");
            Assert.equal("Value cannot be null. (Parameter 'value')",
                    nullFailure.getMessage(), "null parameter and message");
        });

        registry.test("try parse assigns false for every failure", () -> {
            boolean[] result = {true};
            Assert.isFalse(VsBoolean.tryParse(null, result), "null fails");
            Assert.isFalse(result[0], "null assigns false");
            result[0] = true;
            Assert.isFalse(VsBoolean.tryParse("bad", result), "malformed fails");
            Assert.isFalse(result[0], "malformed assigns false");
            Assert.isTrue(VsBoolean.tryParse(" False ", result), "false token succeeds");
            Assert.isFalse(result[0], "false token value");
            Assert.isTrue(VsBoolean.tryParse(" TRUE ", result), "true token succeeds");
            Assert.isTrue(result[0], "true token value");
        });
    }

    private static String caseVariant(String lower, int mask) {
        StringBuilder result = new StringBuilder(lower.length());
        for (int index = 0; index < lower.length(); index++) {
            char character = lower.charAt(index);
            result.append((mask & (1 << index)) == 0
                    ? character : Character.toUpperCase(character));
        }
        return result.toString();
    }

    private static boolean isCSharpWhiteSpace(char value) {
        return (value >= 0x0009 && value <= 0x000D)
                || value == 0x0020
                || value == 0x0085
                || value == 0x00A0
                || value == 0x1680
                || (value >= 0x2000 && value <= 0x200A)
                || value == 0x2028
                || value == 0x2029
                || value == 0x202F
                || value == 0x205F
                || value == 0x3000;
    }
}
