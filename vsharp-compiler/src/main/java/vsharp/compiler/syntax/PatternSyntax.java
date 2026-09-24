package vsharp.compiler.syntax;

import java.util.List;
import java.util.Objects;
import vsharp.compiler.source.SourceSpan;

/// A C# pattern. Exhaustiveness and type compatibility belong to semantic analysis.
public sealed interface PatternSyntax extends SyntaxNode {

    record Missing(SourceSpan span) implements PatternSyntax {
        public Missing { Objects.requireNonNull(span, "span"); }
    }

    record Discard(SourceSpan span) implements PatternSyntax {
        public Discard { Objects.requireNonNull(span, "span"); }
    }

    record Constant(SourceSpan span, ExpressionSyntax expression) implements PatternSyntax {
        public Constant {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(expression, "expression");
        }
    }

    /// A declaration/type pattern. `name` is absent for a pure type test.
    record Type(SourceSpan span, TypeSyntax type, String name) implements PatternSyntax {
        public Type {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(type, "type");
        }
    }

    record Var(SourceSpan span, String name) implements PatternSyntax {
        public Var {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(name, "name");
        }
    }

    record Relational(SourceSpan span, SyntaxKind operator, ExpressionSyntax value)
            implements PatternSyntax {
        public Relational {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(operator, "operator");
            Objects.requireNonNull(value, "value");
        }
    }

    record Binary(SourceSpan span, PatternSyntax left, Kind operator, PatternSyntax right)
            implements PatternSyntax {
        public enum Kind { AND, OR }

        public Binary {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(operator, "operator");
            Objects.requireNonNull(right, "right");
        }
    }

    record Not(SourceSpan span, PatternSyntax pattern) implements PatternSyntax {
        public Not {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(pattern, "pattern");
        }
    }

    record Parenthesized(SourceSpan span, PatternSyntax pattern) implements PatternSyntax {
        public Parenthesized {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(pattern, "pattern");
        }
    }

    /// Positional and/or property recursive pattern, optionally introduced by a type.
    record Recursive(SourceSpan span, TypeSyntax type, List<PatternSyntax> positional,
            List<AuxiliarySyntax.PropertySubpattern> properties, String designation)
            implements PatternSyntax {
        public Recursive {
            Objects.requireNonNull(span, "span");
            positional = List.copyOf(positional);
            properties = List.copyOf(properties);
        }
    }

    /// A list pattern. A [Slice] may occur in `elements` at most once; semantic validation
    /// reports duplicates rather than making malformed syntax unrepresentable.
    record ListPattern(SourceSpan span, List<PatternSyntax> elements, String designation)
            implements PatternSyntax {
        public ListPattern {
            Objects.requireNonNull(span, "span");
            elements = List.copyOf(elements);
        }
    }

    record Slice(SourceSpan span, PatternSyntax pattern) implements PatternSyntax {
        public Slice { Objects.requireNonNull(span, "span"); }
    }
}
