package vsharp.compiler.diagnostics;

import java.util.Comparator;
import java.util.Objects;
import vsharp.compiler.source.LinePosition;
import vsharp.compiler.source.SourceFile;
import vsharp.compiler.source.SourceSpan;

/// A single compiler message bound to a location in a source file.
///
/// @param code     the stable diagnostic code
/// @param severity effective severity, which may differ from the code's default when the
///                 driver escalates warnings
/// @param message  the already-formatted, locale-independent message text
/// @param file     the file the message refers to
/// @param span     the exact region the message refers to
public record Diagnostic(
        DiagnosticCode code,
        Severity severity,
        String message,
        SourceFile file,
        SourceSpan span) {

    /// Orders diagnostics the way a user reads a file: by file name, then position, then
    /// code. This total order is what makes compiler output reproducible.
    public static final Comparator<Diagnostic> DISPLAY_ORDER =
            Comparator.comparing((Diagnostic d) -> d.file().name())
                    .thenComparingInt(d -> d.span().start())
                    .thenComparingInt(d -> d.span().length())
                    .thenComparingInt(d -> d.code().number());

    public Diagnostic {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(span, "span");
    }

    /// The same diagnostic at a different severity.
    ///
    /// Used where a build option decides how loudly a condition is reported without changing
    /// what is reported: the code, message and span are the condition, and only the severity
    /// is the policy.
    ///
    /// @param replacement the severity to carry instead
    /// @return this diagnostic at `replacement`
    public Diagnostic withSeverity(Severity replacement) {
        Objects.requireNonNull(replacement, "replacement");
        return replacement == severity ? this
                : new Diagnostic(code, replacement, message, file, span);
    }

    /// Creates a diagnostic using the code's default severity.
    public static Diagnostic of(
            DiagnosticCode code, SourceFile file, SourceSpan span, Object... arguments) {
        return new Diagnostic(code, code.defaultSeverity(), code.format(arguments), file, span);
    }

    /// Whether this diagnostic prevents successful compilation.
    public boolean isError() {
        return severity == Severity.ERROR;
    }

    /// Start position in human coordinates.
    public LinePosition position() {
        return file.positionOf(span.start());
    }

    /// Renders the single-line MSBuild-style form, for example
    /// `Program.vs(3,15): error VS1002: ; expected`.
    @Override
    public String toString() {
        LinePosition position = position();
        return "%s(%d,%d): %s %s: %s".formatted(
                file.name(), position.line(), position.column(), severity.label(), code.id(), message);
    }
}
