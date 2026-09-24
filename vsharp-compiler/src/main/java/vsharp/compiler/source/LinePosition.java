package vsharp.compiler.source;

/// A one-based line/column pair, the form used in user-facing diagnostics.
///
/// Columns count UTF-16 code units from the start of the line, matching how C# compilers
/// and virtually all editors report positions.
///
/// @param line   one-based line number
/// @param column one-based column number
public record LinePosition(int line, int column) implements Comparable<LinePosition> {

    public LinePosition {
        if (line < 1) {
            throw new IllegalArgumentException("line must be positive but was " + line);
        }
        if (column < 1) {
            throw new IllegalArgumentException("column must be positive but was " + column);
        }
    }

    @Override
    public int compareTo(LinePosition other) {
        int byLine = Integer.compare(line, other.line);
        return byLine != 0 ? byLine : Integer.compare(column, other.column);
    }

    @Override
    public String toString() {
        return line + "," + column;
    }
}
