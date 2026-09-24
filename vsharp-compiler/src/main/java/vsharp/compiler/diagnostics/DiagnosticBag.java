package vsharp.compiler.diagnostics;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import vsharp.compiler.source.SourceFile;
import vsharp.compiler.source.SourceSpan;

/// Collects diagnostics produced during one compilation.
///
/// The bag is intentionally not thread-safe: each compilation stage owns one, and results
/// are merged explicitly. That keeps ordering deterministic and avoids the global mutable
/// diagnostic state that makes many compilers hard to test.
///
/// Reading the contents always yields [Diagnostic#DISPLAY_ORDER], never insertion order,
/// so a stage may report out of order without affecting output.
public final class DiagnosticBag {

    private final List<Diagnostic> diagnostics = new ArrayList<>();

    private int errorCount;

    /// Creates an empty bag.
    public DiagnosticBag() {
        // Nothing to initialise.
    }

    /// Reports a diagnostic using its code's default severity.
    public void report(DiagnosticCode code, SourceFile file, SourceSpan span, Object... arguments) {
        add(Diagnostic.of(code, file, span, arguments));
    }

    /// Adds an already-built diagnostic.
    public void add(Diagnostic diagnostic) {
        Objects.requireNonNull(diagnostic, "diagnostic");
        diagnostics.add(diagnostic);
        if (diagnostic.isError()) {
            errorCount++;
        }
    }

    /// Adds every diagnostic from another source.
    public void addAll(Collection<Diagnostic> other) {
        other.forEach(this::add);
    }

    /// Whether any error has been reported.
    public boolean hasErrors() {
        return errorCount > 0;
    }

    /// Number of errors reported.
    public int errorCount() {
        return errorCount;
    }

    /// Whether nothing at all has been reported.
    public boolean isEmpty() {
        return diagnostics.isEmpty();
    }

    /// All diagnostics, in display order.
    public List<Diagnostic> all() {
        return diagnostics.stream().sorted(Diagnostic.DISPLAY_ORDER).toList();
    }

    /// Only the errors, in display order.
    public List<Diagnostic> errors() {
        return diagnostics.stream()
                .filter(Diagnostic::isError)
                .sorted(Diagnostic.DISPLAY_ORDER)
                .toList();
    }

    @Override
    public String toString() {
        return "DiagnosticBag[" + diagnostics.size() + " diagnostics, " + errorCount + " errors]";
    }
}
