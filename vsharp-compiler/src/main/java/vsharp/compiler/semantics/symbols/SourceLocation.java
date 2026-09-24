package vsharp.compiler.semantics.symbols;

import java.util.Objects;
import vsharp.compiler.source.SourceFile;
import vsharp.compiler.source.SourceSpan;

/// The source file and span which introduced a symbol.
public record SourceLocation(SourceFile file, SourceSpan span) {
    public SourceLocation {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(span, "span");
    }
}
