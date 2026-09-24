package vsharp.compiler.diagnostics;

import java.util.List;
import vsharp.compiler.source.LinePosition;
import vsharp.compiler.source.SourceFile;

/// Renders diagnostics for humans and for tooling.
///
/// Two forms are supported. The compact form is a single MSBuild-style line that IDEs,
/// Gradle and CI log scrapers already understand. The detailed form adds the offending
/// source line and a caret run underneath it, which is what makes a compiler pleasant to
/// use at a terminal.
public final class DiagnosticFormatter {

    /// Tab width used when aligning the caret under the source excerpt.
    private static final int TAB_WIDTH = 4;

    /// Longest excerpt rendered; beyond this the line is elided around the span.
    private static final int MAX_EXCERPT_WIDTH = 160;

    private final boolean detailed;

    private DiagnosticFormatter(boolean detailed) {
        this.detailed = detailed;
    }

    /// A formatter emitting only the single-line form.
    public static DiagnosticFormatter compact() {
        return new DiagnosticFormatter(false);
    }

    /// A formatter that also emits the source excerpt and caret.
    public static DiagnosticFormatter detailed() {
        return new DiagnosticFormatter(true);
    }

    /// Renders one diagnostic, without a trailing newline.
    public String format(Diagnostic diagnostic) {
        String headline = diagnostic.toString();
        if (!detailed) {
            return headline;
        }
        return headline + System.lineSeparator() + excerpt(diagnostic);
    }

    /// Renders a list of diagnostics, one per entry, separated by newlines.
    public String formatAll(List<Diagnostic> diagnostics) {
        return diagnostics.stream().map(this::format).reduce(
                new StringBuilder(),
                (buffer, line) -> buffer.isEmpty() ? buffer.append(line)
                        : buffer.append(System.lineSeparator()).append(line),
                StringBuilder::append).toString();
    }

    /// Builds the two-line source excerpt: the offending line, then the caret run.
    private String excerpt(Diagnostic diagnostic) {
        SourceFile file = diagnostic.file();
        LinePosition position = diagnostic.position();
        String line = expandTabs(file.lineText(position.line()));
        int caretStart = expandedColumn(file.lineText(position.line()), position.column() - 1);
        // An empty span (a missing token) still deserves a visible marker.
        int caretLength = Math.max(1, Math.min(diagnostic.span().length(), line.length() - caretStart));

        String rendered = line;
        int offset = 0;
        if (line.length() > MAX_EXCERPT_WIDTH) {
            offset = Math.max(0, caretStart - MAX_EXCERPT_WIDTH / 2);
            int end = Math.min(line.length(), offset + MAX_EXCERPT_WIDTH);
            rendered = (offset > 0 ? "..." : "") + line.substring(offset, end)
                    + (end < line.length() ? "..." : "");
            offset -= offset > 0 ? 3 : 0;
        }

        String gutter = " ".repeat(Integer.toString(position.line()).length());
        return "%s | %s%s%s | %s%s".formatted(
                position.line(), rendered, System.lineSeparator(),
                gutter, " ".repeat(Math.max(0, caretStart - offset)), "^".repeat(caretLength));
    }

    /// Replaces tabs with spaces so the caret lines up in a terminal.
    private static String expandTabs(String line) {
        if (line.indexOf('\t') < 0) {
            return line;
        }
        StringBuilder out = new StringBuilder(line.length() + 8);
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\t') {
                out.append(" ".repeat(TAB_WIDTH - out.length() % TAB_WIDTH));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /// Maps a raw column index onto the tab-expanded line.
    private static int expandedColumn(String line, int rawColumn) {
        int expanded = 0;
        int limit = Math.min(rawColumn, line.length());
        for (int i = 0; i < limit; i++) {
            expanded += line.charAt(i) == '\t' ? TAB_WIDTH - expanded % TAB_WIDTH : 1;
        }
        return expanded;
    }
}
