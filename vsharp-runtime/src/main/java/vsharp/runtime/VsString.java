package vsharp.runtime;

/// C#-faithful `System.String` static members for the curated core library.
///
/// `IsNullOrWhiteSpace` cannot be Java's `String.isBlank()`: a full BMP sweep against the
/// local .NET 10 oracle showed that Java's whitespace classification differs from C#'s.
/// Java includes `\u001C`..`\u001F` while C# does not, and C# includes `\u0085`, `\u00A0`,
/// `\u2007` and `\u202F` while Java does not. The exact predicate lives in [VsChar], so
/// `char.IsWhiteSpace`, the string checks and trimming cannot drift apart.
public final class VsString {

    private VsString() {
        throw new AssertionError("No instances");
    }

    public static boolean isNullOrEmpty(String value) {
        return value == null || value.isEmpty();
    }

    public static boolean isNullOrWhiteSpace(String value) {
        if (value == null || value.isEmpty()) {
            return true;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!VsChar.isWhiteSpace(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    public static String trim(String value) {
        int first = 0;
        int last = value.length() - 1;
        while (first <= last && VsChar.isWhiteSpace(value.charAt(first))) {
            first++;
        }
        while (last >= first && VsChar.isWhiteSpace(value.charAt(last))) {
            last--;
        }
        return value.substring(first, last + 1);
    }

    public static String trimStart(String value) {
        int first = 0;
        int last = value.length();
        while (first < last && VsChar.isWhiteSpace(value.charAt(first))) {
            first++;
        }
        return value.substring(first);
    }

    public static String trimEnd(String value) {
        int last = value.length() - 1;
        while (last >= 0 && VsChar.isWhiteSpace(value.charAt(last))) {
            last--;
        }
        return value.substring(0, last + 1);
    }

    /// .NET invariant string casing is simple, context-free code-point casing: it does not
    /// expand sharp-s/ligatures and it does not select final sigma. A complete Unicode-scalar
    /// comparison against .NET 10 proves that the JDK's simple Character mapping plus the two
    /// Turkish-I corrections owned by [VsChar] is exact.
    public static String toUpperInvariant(String value) {
        return mapInvariantCase(value, true);
    }

    public static String toLowerInvariant(String value) {
        return mapInvariantCase(value, false);
    }

    private static String mapInvariantCase(String value, boolean upper) {
        requireReceiver(value);
        StringBuilder changed = null;
        for (int index = 0; index < value.length();) {
            int codePoint = value.codePointAt(index);
            int mapped = upper
                    ? VsChar.toUpperInvariantCodePoint(codePoint)
                    : VsChar.toLowerInvariantCodePoint(codePoint);
            if (changed == null && mapped != codePoint) {
                changed = new StringBuilder(value.length());
                changed.append(value, 0, index);
            }
            if (changed != null) {
                changed.appendCodePoint(mapped);
            }
            index += Character.charCount(codePoint);
        }
        return changed == null ? value : changed.toString();
    }

    /// The ordinal search family. Java's `indexOf`/`lastIndexOf`/`replace` are already
    /// ordinal and produce .NET's values for every non-null argument, so the only work these
    /// helpers do is C#'s argument contract: the receiver is checked before the arguments,
    /// a null argument is `ArgumentNullException` and an empty `oldValue` is `ArgumentException`
    /// - both of which V# raises as `System.ArgumentException` (see docs/FEATURE-MATRIX.md).
    public static boolean contains(String value, String search) {
        requireReceiver(value);
        requireArgument(search, "value");
        return value.indexOf(search) >= 0;
    }

    public static boolean containsChar(String value, char search) {
        requireReceiver(value);
        return value.indexOf(search) >= 0;
    }

    public static int indexOfChar(String value, char search) {
        requireReceiver(value);
        return value.indexOf(search);
    }

    public static int lastIndexOfChar(String value, char search) {
        requireReceiver(value);
        return value.lastIndexOf(search);
    }

    public static boolean startsWithChar(String value, char search) {
        requireReceiver(value);
        return !value.isEmpty() && value.charAt(0) == search;
    }

    public static boolean endsWithChar(String value, char search) {
        requireReceiver(value);
        return !value.isEmpty() && value.charAt(value.length() - 1) == search;
    }

    public static String replaceChar(String value, char oldChar, char newChar) {
        requireReceiver(value);
        return value.replace(oldChar, newChar);
    }

    /// A null `newValue` deletes every occurrence in .NET, and an empty `oldValue` is rejected
    /// rather than inserted between every character the way Java's `replace` would.
    public static String replace(String value, String oldValue, String newValue) {
        requireReceiver(value);
        requireArgument(oldValue, "oldValue");
        if (oldValue.isEmpty()) {
            throw new IllegalArgumentException("String cannot be of zero length. (Parameter 'oldValue')");
        }
        return value.replace(oldValue, newValue == null ? "" : newValue);
    }

    /// `String.Split(params char[])`. Java's `String.split` is unusable here: it is
    /// regular-expression based and it strips trailing empty entries. C#'s separator-array
    /// form is a plain UTF-16 code-unit scan that keeps every empty entry, so a receiver
    /// with `n` separator occurrences always yields `n + 1` parts and an empty receiver
    /// yields one empty part. A null or empty separator array is C#'s documented fallback
    /// to the whitespace set, which is [VsChar#isWhiteSpace] rather than Java's wider one.
    /// Matching is by code unit and never by code point: a lone high surrogate separator
    /// splits inside a surrogate pair, which the .NET 10 oracle confirms.
    public static String[] split(String value, char[] separators) {
        requireReceiver(value);
        boolean whitespace = separators == null || separators.length == 0;
        int parts = 1;
        for (int i = 0; i < value.length(); i++) {
            if (isSeparator(value.charAt(i), separators, whitespace)) {
                parts++;
            }
        }
        String[] result = new String[parts];
        int index = 0;
        int start = 0;
        for (int i = 0; i < value.length(); i++) {
            if (isSeparator(value.charAt(i), separators, whitespace)) {
                result[index++] = value.substring(start, i);
                start = i + 1;
            }
        }
        result[index] = value.substring(start);
        return result;
    }

    private static boolean isSeparator(char candidate, char[] separators, boolean whitespace) {
        if (whitespace) {
            return VsChar.isWhiteSpace(candidate);
        }
        for (char separator : separators) {
            if (separator == candidate) {
                return true;
            }
        }
        return false;
    }

    /// C# treats null elements as empty strings and rejects only a null values array.
    public static String concat(String[] values) {
        requireArgument(values, "values");
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (value != null) {
                result.append(value);
            }
        }
        return result.toString();
    }

    /// C# treats a null separator and null elements as empty strings. Separators still occupy
    /// the positions around a null element, so `["a", null, "b"]` joins as `"a,,b"`.
    public static String join(String separator, String[] values) {
        requireArgument(values, "value");
        String actualSeparator = separator == null ? "" : separator;
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i != 0) {
                result.append(actualSeparator);
            }
            if (values[i] != null) {
                result.append(values[i]);
            }
        }
        return result.toString();
    }

    /// `string.Format` - C# composite formatting, evaluated at run time.
    ///
    /// This is the run-time twin of interpolation, which the compiler lowers hole by hole
    /// because its format text is a literal. Here the text is a value, so the same grammar is
    /// parsed here: `{index[,alignment][:format]}`, with `{{` and `}}` escaping a literal
    /// brace. Both paths render through [VsFormat] and [VsNumberFormat], so a `bool` reads
    /// `True` and `{0:F2}` rounds identically whichever spelling produced it.
    ///
    /// C#'s divergences are kept: a `null` argument renders as the empty string rather than
    /// `"null"`, alignment widens and never truncates, and a format specifier on a value that
    /// is not formattable is ignored rather than refused - C# calls `IFormattable.ToString`
    /// only when the value implements it, and falls back to `ToString()` otherwise.
    ///
    /// @param format the composite format string
    /// @param args the arguments its holes index into
    /// @return the formatted result
    /// @throws IllegalArgumentException when `format` or `args` is null
    /// @throws VsFormatException when the text is malformed or a hole indexes past `args`
    public static String format(String format, Object[] args) {
        requireArgument(format, "format");
        requireArgument(args, "args");
        StringBuilder result = new StringBuilder(format.length() + 16);
        int index = 0;
        while (index < format.length()) {
            char c = format.charAt(index);
            if (c == '}') {
                // A closing brace is only legal doubled; a lone one is C#'s "not in a correct
                // format", because it could only end a hole that never opened.
                if (index + 1 < format.length() && format.charAt(index + 1) == '}') {
                    result.append('}');
                    index += 2;
                    continue;
                }
                throw malformedFormat();
            }
            if (c != '{') {
                result.append(c);
                index++;
                continue;
            }
            if (index + 1 < format.length() && format.charAt(index + 1) == '{') {
                result.append('{');
                index += 2;
                continue;
            }
            int close = format.indexOf('}', index + 1);
            if (close < 0) {
                throw malformedFormat();
            }
            result.append(renderHole(format.substring(index + 1, close), args));
            index = close + 1;
        }
        return result.toString();
    }

    /// Renders one `index[,alignment][:format]` hole against the argument list.
    private static String renderHole(String hole, Object[] args) {
        int comma = hole.indexOf(',');
        int colon = hole.indexOf(':');
        // A comma inside the format specifier - `{0:F2}` has none, but a custom one could -
        // is part of the specifier, not an alignment clause.
        if (colon >= 0 && comma > colon) {
            comma = -1;
        }
        int indexEnd = comma >= 0 ? comma : colon >= 0 ? colon : hole.length();
        int argument = parseNonNegative(hole.substring(0, indexEnd));
        if (argument >= args.length) {
            throw new VsFormatException("Index (zero based) must be greater than or equal to "
                    + "zero and less than the size of the argument list.");
        }
        int alignment = 0;
        if (comma >= 0) {
            int alignmentEnd = colon >= 0 ? colon : hole.length();
            alignment = parseAlignment(hole.substring(comma + 1, alignmentEnd));
        }
        String specifier = colon >= 0 ? hole.substring(colon + 1) : null;
        return VsFormat.align(render(args[argument], specifier), alignment);
    }

    /// Applies a hole's format specifier to one argument.
    ///
    /// The numeric carriers route to [VsNumberFormat] so `D`, `X` and `F` mean exactly what
    /// they mean in an interpolated string and in `ToString(string)`. Every other value takes
    /// C#'s non-`IFormattable` path: the specifier is ignored and the value renders by its
    /// default display. A `null` specifier is the no-specifier case and never reaches the
    /// numeric formatter, because `{0}` must not start rejecting specifier-less values.
    private static String render(Object value, String specifier) {
        if (specifier == null || specifier.isEmpty()) {
            return VsFormat.toDisplayString(value);
        }
        return switch (value) {
            case Integer i -> VsNumberFormat.formatInt(i.intValue(), specifier);
            case Long l -> VsNumberFormat.formatLong(l.longValue(), specifier);
            case Short s -> VsNumberFormat.formatShort(s.shortValue(), specifier);
            case Byte b -> VsNumberFormat.formatSByte(b.byteValue(), specifier);
            case Double d -> VsNumberFormat.formatDouble(d.doubleValue(), specifier);
            case Float f -> VsNumberFormat.formatFloat(f.floatValue(), specifier);
            case java.math.BigDecimal d -> VsNumberFormat.formatDecimal(d, specifier);
            default -> VsFormat.toDisplayString(value);
        };
    }

    private static int parseNonNegative(String text) {
        if (text.isEmpty()) {
            throw malformedFormat();
        }
        int value = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                throw malformedFormat();
            }
            // .NET caps a hole index at 1000000; beyond that the text is malformed rather
            // than merely out of range, and the cap also keeps this from overflowing.
            value = value * 10 + (c - '0');
            if (value > 1000000) {
                throw malformedFormat();
            }
        }
        return value;
    }

    private static int parseAlignment(String text) {
        if (text.isEmpty()) {
            throw malformedFormat();
        }
        boolean negative = text.charAt(0) == '-';
        int magnitude = parseNonNegative(negative ? text.substring(1) : text);
        return negative ? -magnitude : magnitude;
    }

    private static VsFormatException malformedFormat() {
        return new VsFormatException("Input string was not in a correct format.");
    }

    /// Ordinal string shaping. `PadLeft`/`PadRight` never truncate: a width at or below
    /// the current length returns the receiver unchanged. The padding character is copied as a
    /// bare code unit, so NUL and lone surrogates pad exactly like any other character.
    public static String padLeft(String value, int totalWidth) {
        return padLeft(value, totalWidth, ' ');
    }

    public static String padLeft(String value, int totalWidth, char paddingChar) {
        requireReceiver(value);
        requireNonNegative(totalWidth, "totalWidth");
        if (totalWidth <= value.length()) {
            return value;
        }
        StringBuilder result = new StringBuilder(totalWidth);
        for (int index = value.length(); index < totalWidth; index++) {
            result.append(paddingChar);
        }
        return result.append(value).toString();
    }

    public static String padRight(String value, int totalWidth) {
        return padRight(value, totalWidth, ' ');
    }

    public static String padRight(String value, int totalWidth, char paddingChar) {
        requireReceiver(value);
        requireNonNegative(totalWidth, "totalWidth");
        if (totalWidth <= value.length()) {
            return value;
        }
        StringBuilder result = new StringBuilder(totalWidth).append(value);
        for (int index = value.length(); index < totalWidth; index++) {
            result.append(paddingChar);
        }
        return result.toString();
    }

    /// A fresh UTF-16 code-unit copy per call; surrogate pairs are split into their two units.
    public static char[] toCharArray(String value) {
        requireReceiver(value);
        return value.toCharArray();
    }

    /// `Insert` validates its argument before its index, and its index test is unsigned, so a
    /// negative start index is reported as the 32-bit unsigned value .NET compares.
    public static String insert(String value, int startIndex, String inserted) {
        requireReceiver(value);
        requireArgument(inserted, "value");
        if (Integer.compareUnsigned(startIndex, value.length()) > 0) {
            throw notAtMost("startIndex", Integer.toUnsignedString(startIndex), Integer.toString(value.length()));
        }
        return new StringBuilder(value).insert(startIndex, inserted).toString();
    }

    /// The one-argument `Remove` keeps .NET's legacy message pair, which carries no actual value.
    public static String remove(String value, int startIndex) {
        requireReceiver(value);
        if (startIndex < 0) {
            throw new IllegalArgumentException("StartIndex cannot be less than zero. (Parameter 'startIndex')");
        }
        if (startIndex > value.length()) {
            throw new IllegalArgumentException(
                    "startIndex cannot be larger than length of string. (Parameter 'startIndex')");
        }
        return value.substring(0, startIndex);
    }

    /// The two-argument `Remove` never tests the start index against the length directly: it
    /// bounds `count` by `Length - startIndex`, so an oversized start index fails on `count`
    /// against a negative bound.
    public static String remove(String value, int startIndex, int count) {
        requireReceiver(value);
        requireNonNegative(startIndex, "startIndex");
        requireNonNegative(count, "count");
        int available = value.length() - startIndex;
        if (count > available) {
            throw notAtMost("count", Integer.toString(count), Integer.toString(available));
        }
        return value.substring(0, startIndex) + value.substring(startIndex + count);
    }

    /// .NET's argument range messages append the actual value on its own line. V#'s corelib has
    /// no `ArgumentOutOfRangeException` carrier, so both arrive as `System.ArgumentException`
    /// carrying the shipped text verbatim.
    private static void requireNonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " ('" + value + "') must be a non-negative value. (Parameter '"
                    + name + "')\nActual value was " + value + ".");
        }
    }

    private static IllegalArgumentException notAtMost(String name, String value, String bound) {
        return new IllegalArgumentException(name + " ('" + value + "') must be less than or equal to '" + bound
                + "'. (Parameter '" + name + "')\nActual value was " + value + ".");
    }

    /// An instance member reached through a null reference is `NullReferenceException`, and it
    /// is observed before any argument is validated.
    private static void requireReceiver(String value) {
        if (value == null) {
            throw new NullPointerException("Object reference not set to an instance of an object.");
        }
    }

    private static void requireArgument(Object argument, String name) {
        if (argument == null) {
            throw new IllegalArgumentException("Value cannot be null. (Parameter '" + name + "')");
        }
    }

}
