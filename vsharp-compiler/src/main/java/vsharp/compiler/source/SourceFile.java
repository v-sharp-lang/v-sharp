package vsharp.compiler.source;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/// An immutable V# compilation input: a display name plus its full text.
///
/// Text is always decoded as UTF-8 — never with the platform default charset — and a
/// leading byte-order mark is removed so that offset `0` is the first real character and
/// spans stay aligned with what the user sees in an editor.
public final class SourceFile {

    /// The name carried by the compiler-shipped corelib source. It lives here because
    /// the driver that loads corelib and the binder that recognises its placeholder
    /// declarations must agree on exactly one spelling.
    public static final String CORELIB_NAME = "corelib.vs";

    /// U+FEFF BYTE ORDER MARK, permitted at the start of a C# source file.
    private static final char BYTE_ORDER_MARK = 0xFEFF;

    private final String name;

    private final String text;

    private final LineMap lineMap;

    private SourceFile(String name, String text) {
        this.name = Objects.requireNonNull(name, "name");
        this.text = Objects.requireNonNull(text, "text");
        this.lineMap = LineMap.of(text);
    }

    /// Creates a source file from text held in memory, typically a test input.
    public static SourceFile of(String name, String text) {
        return new SourceFile(name, stripByteOrderMark(Objects.requireNonNull(text, "text")));
    }

    /// Reads a source file from disk as UTF-8.
    ///
    /// @throws UncheckedIOException if the file cannot be read
    public static SourceFile read(Path path) {
        Objects.requireNonNull(path, "path");
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            return new SourceFile(path.toString(), stripByteOrderMark(text));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read source file " + path, e);
        }
    }

    private static String stripByteOrderMark(String text) {
        return !text.isEmpty() && text.charAt(0) == BYTE_ORDER_MARK ? text.substring(1) : text;
    }

    /// Display name used in diagnostics.
    public String name() {
        return name;
    }

    /// Full decoded text.
    public String text() {
        return text;
    }

    /// Length in UTF-16 code units.
    public int length() {
        return text.length();
    }

    /// Line index for this file.
    public LineMap lineMap() {
        return lineMap;
    }

    /// Text covered by `span`.
    public String textOf(SourceSpan span) {
        return text.substring(span.start(), span.end());
    }

    /// One-based position of `offset`.
    public LinePosition positionOf(int offset) {
        return lineMap.positionOf(offset);
    }

    /// Text of a one-based line, excluding its terminator. Used to render the source
    /// excerpt that accompanies a diagnostic.
    public String lineText(int line) {
        return text.substring(lineMap.lineStart(line), lineMap.lineEnd(line, text));
    }

    @Override
    public String toString() {
        return name;
    }
}
