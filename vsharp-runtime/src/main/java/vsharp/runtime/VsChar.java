package vsharp.runtime;

/// C#-faithful, culture-independent `System.Char` operations for the curated core library.
///
/// A full BMP comparison against the local .NET 10 oracle established that
/// `Character.isDigit(char)`, `Character.isLetter(char)` and `Character.isLetterOrDigit(char)`
/// classify all 65,536 UTF-16 code units exactly like their C# counterparts on JDK 25.
/// `IsWhiteSpace` cannot delegate to
/// `Character.isWhitespace`: C# includes NEL and three no-break spaces that Java excludes,
/// while Java includes four information separators that C# excludes. Its exact 25-character
/// BMP set therefore remains explicit here and is shared by the string whitespace helpers.
///
/// Java's simple invariant casing matches .NET 10 at every BMP code unit except the two
/// Turkish-I characters. C# invariant char casing leaves U+0130 and U+0131 unchanged, whereas
/// `Character` maps them to ASCII `i` and `I`; the guards below are therefore semantic, not a
/// locale choice. String casing is not used because it expands some single code units.
public final class VsChar {

    private VsChar() {
        throw new AssertionError("No instances");
    }

    public static boolean isDigit(char value) {
        return Character.isDigit(value);
    }

    public static boolean isLetter(char value) {
        return Character.isLetter(value);
    }

    public static boolean isLetterOrDigit(char value) {
        return Character.isLetterOrDigit(value);
    }

    /// The exact C# `char.IsWhiteSpace` set for the BMP, measured against .NET 10.
    public static boolean isWhiteSpace(char value) {
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

    public static char toUpperInvariant(char value) {
        return (char) toUpperInvariantCodePoint(value);
    }

    public static char toLowerInvariant(char value) {
        return (char) toLowerInvariantCodePoint(value);
    }

    /// Package-level code-point forms shared with invariant string casing, so the two
    /// Turkish-I corrections have one owner and supplementary mappings stay intact.
    static int toUpperInvariantCodePoint(int value) {
        return value == 0x0131 ? value : Character.toUpperCase(value);
    }

    static int toLowerInvariantCodePoint(int value) {
        return value == 0x0130 ? value : Character.toLowerCase(value);
    }
}
