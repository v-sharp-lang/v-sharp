package vsharp.runtime;

import java.util.Objects;

/// C#-faithful `System.Boolean.Parse(string)` and `TryParse(string, out bool)`.
///
/// Boolean parsing is culture-independent. Only ASCII-case variants of `True` and `False`
/// are tokens; both ends ignore NUL plus the exact C# `Char.IsWhiteSpace` BMP set.
public final class VsBoolean {

    private static final int INVALID = -1;
    private static final int FALSE = 0;
    private static final int TRUE = 1;

    private VsBoolean() {
        throw new AssertionError("No instances");
    }

    public static boolean parse(String text) {
        if (text == null) {
            // Boolean.Parse names this parameter `value`, unlike the numeric `s` overloads.
            throw new IllegalArgumentException("Value cannot be null. (Parameter 'value')");
        }
        int parsed = parseValue(text);
        if (parsed == INVALID) {
            throw new VsFormatException(
                    "String '" + text + "' was not recognized as a valid Boolean.");
        }
        return parsed == TRUE;
    }

    public static boolean tryParse(String text, boolean[] result) {
        Objects.requireNonNull(result, "result");
        if (result.length == 0) {
            throw new IllegalArgumentException("result cell must contain one element");
        }
        result[0] = false;
        if (text == null) {
            return false;
        }
        int parsed = parseValue(text);
        if (parsed == INVALID) {
            return false;
        }
        result[0] = parsed == TRUE;
        return true;
    }

    /// Returns a three-state integer so the valid value `false` remains distinct from failure
    /// without allocating a result object on this tiny parsing path.
    private static int parseValue(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && isTrimCharacter(text.charAt(start))) {
            start++;
        }
        while (end > start && isTrimCharacter(text.charAt(end - 1))) {
            end--;
        }
        if (matchesAsciiIgnoreCase(text, start, end, "true")) {
            return TRUE;
        }
        if (matchesAsciiIgnoreCase(text, start, end, "false")) {
            return FALSE;
        }
        return INVALID;
    }

    private static boolean matchesAsciiIgnoreCase(
            String text, int start, int end, String expected) {
        if (end - start != expected.length()) {
            return false;
        }
        for (int index = 0; index < expected.length(); index++) {
            char actual = text.charAt(start + index);
            if (actual >= 'A' && actual <= 'Z') {
                actual = (char) (actual + ('a' - 'A'));
            }
            if (actual != expected.charAt(index)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isTrimCharacter(char value) {
        return value == 0 || VsChar.isWhiteSpace(value);
    }
}
