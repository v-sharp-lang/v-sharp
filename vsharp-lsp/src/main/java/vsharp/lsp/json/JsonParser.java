package vsharp.lsp.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;

/// A recursive-descent JSON reader (RFC 8259).
///
/// Hand-rolled because the absolute dependency rule forbids Jackson and Gson, and because
/// the protocol needs exactly one thing from a parser: turn a complete in-memory payload
/// into a [Json] tree, refusing anything malformed with a position. There is no streaming
/// mode, since LSP framing already delivers whole messages.
///
/// Nesting is bounded so that a hostile or corrupt payload reports a diagnosable error
/// instead of exhausting the stack, which on a long-lived editor server would take the
/// whole session down.
final class JsonParser {

    /// Deepest array/object nesting accepted. LSP payloads nest perhaps six levels; this
    /// leaves three orders of magnitude of headroom while still bounding recursion.
    private static final int MAX_DEPTH = 512;

    private final String text;

    private int position;

    private JsonParser(String text) {
        this.text = text;
    }

    static Json parse(String text) {
        if (text == null) {
            throw new JsonException("JSON payload is null");
        }
        JsonParser parser = new JsonParser(text);
        parser.skipWhitespace();
        Json value = parser.readValue(0);
        parser.skipWhitespace();
        if (parser.position != text.length()) {
            throw parser.error("trailing content after JSON value");
        }
        return value;
    }

    private Json readValue(int depth) {
        if (depth > MAX_DEPTH) {
            throw error("JSON nested deeper than " + MAX_DEPTH + " levels");
        }
        char c = peek();
        return switch (c) {
            case '{' -> readObject(depth);
            case '[' -> readArray(depth);
            case '"' -> new Json.Str(readString());
            case 't' -> readLiteral("true", Json.Bool.TRUE);
            case 'f' -> readLiteral("false", Json.Bool.FALSE);
            case 'n' -> readLiteral("null", Json.Null.INSTANCE);
            default -> readNumber();
        };
    }

    private Json readObject(int depth) {
        expect('{');
        SequencedMap<String, Json> members = new LinkedHashMap<>();
        skipWhitespace();
        if (peek() == '}') {
            position++;
            return new Json.Obj(members);
        }
        while (true) {
            skipWhitespace();
            String key = readString();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            members.put(key, readValue(depth + 1));
            skipWhitespace();
            char c = next();
            if (c == '}') {
                return new Json.Obj(members);
            }
            if (c != ',') {
                throw error("expected ',' or '}' in object");
            }
        }
    }

    private Json readArray(int depth) {
        expect('[');
        List<Json> items = new ArrayList<>();
        skipWhitespace();
        if (peek() == ']') {
            position++;
            return new Json.Arr(items);
        }
        while (true) {
            skipWhitespace();
            items.add(readValue(depth + 1));
            skipWhitespace();
            char c = next();
            if (c == ']') {
                return new Json.Arr(items);
            }
            if (c != ',') {
                throw error("expected ',' or ']' in array");
            }
        }
    }

    private String readString() {
        expect('"');
        StringBuilder out = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') {
                return out.toString();
            }
            if (c != '\\') {
                if (c < 0x20) {
                    throw error("unescaped control character in string");
                }
                out.append(c);
                continue;
            }
            char escape = next();
            switch (escape) {
                case '"' -> out.append('"');
                case '\\' -> out.append('\\');
                case '/' -> out.append('/');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> out.append(readHexEscape());
                default -> throw error("unknown escape '\\" + escape + "'");
            }
        }
    }

    /// Reads the four hex digits of a `\\u` escape.
    ///
    /// The result is appended as a single `char`: a surrogate pair arrives as two separate
    /// escapes and therefore as two chars, which is exactly the UTF-16 representation Java
    /// strings already use, so no pairing logic is needed here.
    private char readHexEscape() {
        if (position + 4 > text.length()) {
            throw error("truncated \\u escape");
        }
        int value = 0;
        for (int i = 0; i < 4; i++) {
            int digit = Character.digit(text.charAt(position + i), 16);
            if (digit < 0) {
                throw error("non-hex digit in \\u escape");
            }
            value = value * 16 + digit;
        }
        position += 4;
        return (char) value;
    }

    private Json readNumber() {
        int start = position;
        if (peek() == '-') {
            position++;
        }
        readDigits();
        if (position < text.length() && text.charAt(position) == '.') {
            position++;
            readDigits();
        }
        if (position < text.length()
                && (text.charAt(position) == 'e' || text.charAt(position) == 'E')) {
            position++;
            if (position < text.length()
                    && (text.charAt(position) == '+' || text.charAt(position) == '-')) {
                position++;
            }
            readDigits();
        }
        String literal = text.substring(start, position);
        try {
            return new Json.Num(Double.parseDouble(literal));
        } catch (NumberFormatException e) {
            throw error("malformed number '" + literal + "'");
        }
    }

    private void readDigits() {
        int start = position;
        while (position < text.length() && text.charAt(position) >= '0'
                && text.charAt(position) <= '9') {
            position++;
        }
        if (position == start) {
            throw error("expected a digit");
        }
    }

    private Json readLiteral(String literal, Json value) {
        if (!text.startsWith(literal, position)) {
            throw error("expected '" + literal + "'");
        }
        position += literal.length();
        return value;
    }

    private void skipWhitespace() {
        while (position < text.length()) {
            char c = text.charAt(position);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                return;
            }
            position++;
        }
    }

    private char peek() {
        if (position >= text.length()) {
            throw error("unexpected end of JSON input");
        }
        return text.charAt(position);
    }

    private char next() {
        char c = peek();
        position++;
        return c;
    }

    private void expect(char expected) {
        char c = next();
        if (c != expected) {
            throw error("expected '" + expected + "' but found '" + c + "'");
        }
    }

    private JsonException error(String message) {
        return new JsonException("Malformed JSON at offset " + position + ": " + message);
    }
}
