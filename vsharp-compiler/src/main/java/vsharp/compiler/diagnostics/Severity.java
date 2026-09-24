package vsharp.compiler.diagnostics;

/// How seriously the compiler treats a diagnostic.
///
/// Only [#ERROR] suppresses artifact generation; warnings and information are advisory
/// unless the caller opts into warnings-as-errors.
public enum Severity {

    /// Advisory detail that never indicates a defect.
    INFO("info"),

    /// Suspicious but compilable code.
    WARNING("warning"),

    /// A defect that prevents successful compilation.
    ERROR("error");

    private final String label;

    Severity(String label) {
        this.label = label;
    }

    /// Lower-case label used in rendered diagnostics, matching MSBuild conventions.
    public String label() {
        return label;
    }
}
