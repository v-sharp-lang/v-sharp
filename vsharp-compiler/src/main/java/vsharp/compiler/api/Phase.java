package vsharp.compiler.api;

/// The ordered compiler phases a [Compilation] can reach.
///
/// A run stops after the first phase that reports an error, so the phase recorded in a
/// [CompilationResult] is exactly the last one that executed. Phases are declared in
/// execution order and `compareTo` may be used to test whether a phase was reached.
public enum Phase {

    /// Preprocessing, lexing and parsing of every source file.
    PARSE("parse"),

    /// Declaration symbols and lexical scopes for every parsed unit.
    DECLARATION_BINDING("declaration binding"),

    /// Value names, operators and the bound expression algebra.
    EXPRESSION_BINDING("expression binding"),

    /// Definite assignment and reachability over the bound tree.
    FLOW_ANALYSIS("flow analysis"),

    /// JVM bytecode generation.
    CODE_GENERATION("code generation");

    private final String displayName;

    Phase(String displayName) {
        this.displayName = displayName;
    }

    /// Lower-case human-readable name used in driver messages.
    public String displayName() {
        return displayName;
    }
}
