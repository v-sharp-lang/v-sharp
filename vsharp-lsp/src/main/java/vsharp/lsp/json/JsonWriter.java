package vsharp.lsp.json;

import java.util.Map;

/// Renders a [Json] tree as compact JSON.
///
/// Output is deterministic: objects emit members in insertion order, and integral numbers
/// lose their fractional part, so the same tree always produces the same bytes. That is
/// what lets the protocol tests compare payloads literally.
final class JsonWriter {

    private JsonWriter() {
        throw new AssertionError("No instances");
    }

    static String write(Json value) {
        StringBuilder out = new StringBuilder();
        append(out, value);
        return out.toString();
    }

    private static void append(StringBuilder out, Json value) {
        switch (value) {
            case Json.Null ignored -> out.append("null");
            case Json.Bool b -> out.append(b.value() ? "true" : "false");
            case Json.Num n -> appendNumber(out, n.value());
            case Json.Str s -> appendString(out, s.value());
            case Json.Arr a -> {
                out.append('[');
                boolean first = true;
                for (Json item : a.items()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    append(out, item);
                }
                out.append(']');
            }
            case Json.Obj o -> {
                out.append('{');
                boolean first = true;
                for (Map.Entry<String, Json> member : o.members().entrySet()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    appendString(out, member.getKey());
                    out.append(':');
                    append(out, member.getValue());
                }
                out.append('}');
            }
        }
    }

    /// Writes a number without a spurious `.0`.
    ///
    /// Every number LSP exchanges - request ids, line and character offsets, enum codes -
    /// is conceptually an integer, and clients that compare request ids textually would
    /// not match `1.0` against the `1` they sent.
    private static void appendNumber(StringBuilder out, double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value)
                && Math.abs(value) < 9.007199254740992E15) {
            out.append((long) value);
            return;
        }
        out.append(value);
    }

    private static void appendString(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append("\\u").append(String.format("%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
