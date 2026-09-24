package vsharp.compiler.syntax;

import java.util.Objects;
import java.util.Optional;
import vsharp.compiler.source.SourceSpan;

/// A piece of an interpolated string.
///
/// The lexer splits `$"a{b,3:F2}c"` into its literal runs and its holes but does not parse
/// the holes: a hole is arbitrary C# expression syntax, and re-entering the parser from
/// the lexer would invert the pipeline. Each hole therefore carries the exact source span
/// of its expression, so the parser can lex that region with correctly offset positions.
public sealed interface InterpolationPart {

    /// Literal text between holes, with `{{`/`}}` already reduced to `{`/`}`.
    ///
    /// @param value the decoded text
    record Text(String value) implements InterpolationPart {

        /// Validates the text.
        public Text {
            Objects.requireNonNull(value, "value");
        }
    }

    /// A `{expression[,alignment][:format]}` hole.
    ///
    /// @param expression the span of the expression, excluding braces and clauses
    /// @param alignment  the span of the alignment expression after `,`, if present
    /// @param format     the literal format string after `:`, if present
    record Hole(SourceSpan expression, SourceSpan alignment, String format)
            implements InterpolationPart {

        /// Validates the hole.
        public Hole {
            Objects.requireNonNull(expression, "expression");
        }

        /// The alignment expression's span, if the hole has one.
        public Optional<SourceSpan> alignmentSpan() {
            return Optional.ofNullable(alignment);
        }

        /// The format clause, if the hole has one.
        public Optional<String> formatClause() {
            return Optional.ofNullable(format);
        }
    }
}
