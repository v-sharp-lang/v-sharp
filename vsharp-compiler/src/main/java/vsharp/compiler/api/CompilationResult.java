package vsharp.compiler.api;

import java.util.List;
import java.util.Objects;
import vsharp.compiler.diagnostics.Diagnostic;

/// The outcome of one [Compilation] run.
///
/// `diagnostics` is already in [Diagnostic#DISPLAY_ORDER] across every file and phase, so
/// two runs over identical input print byte-identical output. `reached` is the last phase
/// that executed; `units` holds one entry per source file in the order given to the
/// compilation, all of the shape produced by `reached`.
public record CompilationResult(Phase reached, List<Diagnostic> diagnostics,
        List<UnitAnalysis> units) {

    public CompilationResult {
        Objects.requireNonNull(reached, "reached");
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
        units = List.copyOf(Objects.requireNonNull(units, "units"));
    }

    /// Whether any diagnostic is an error; warnings alone still leave a run successful.
    public boolean hasErrors() {
        return diagnostics.stream().anyMatch(Diagnostic::isError);
    }

    /// The error subset, in display order.
    public List<Diagnostic> errors() {
        return diagnostics.stream().filter(Diagnostic::isError).toList();
    }

    /// Whether the whole front end ran without an error.
    public boolean succeeded() {
        return reached.compareTo(Phase.FLOW_ANALYSIS) >= 0 && !hasErrors();
    }
}
