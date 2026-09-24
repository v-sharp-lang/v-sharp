package vsharp.compiler.syntax;

import java.util.List;
import java.util.Objects;
import vsharp.compiler.source.SourceSpan;

/// A type as written in source.
///
/// Types are syntax, not semantics: `var` is a [Predefined] keyword here and only the binder
/// decides what it means, and `A.B` is a [Name] with two segments whether `A` turns out to be
/// a namespace or a static container.
public sealed interface TypeSyntax extends SyntaxNode {

    /// One `Identifier` or `Identifier<T, U>` step of a qualified name.
    ///
    /// @param typeArguments empty for a non-generic segment; a list of [Omitted] entries for
    ///        the unbound form `List<>` used by `typeof`
    record Segment(SourceSpan span, String identifier, List<TypeSyntax> typeArguments)
            implements AuxiliarySyntax {

        public Segment {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(identifier, "identifier");
            typeArguments = List.copyOf(typeArguments);
        }

        /// A segment with no type argument list.
        public static Segment of(SourceSpan span, String identifier) {
            return new Segment(span, identifier, List.of());
        }

        /// Whether this segment carries a type argument list, even an unbound one.
        public boolean isGeneric() {
            return !typeArguments.isEmpty();
        }
    }

    /// One element of a tuple type, such as the `int x` of `(int x, string y)`.
    ///
    /// @param name the element name, or `null` when the element is unnamed
    record TupleElement(SourceSpan span, TypeSyntax type, String name) implements AuxiliarySyntax {

        public TupleElement {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(type, "type");
        }
    }

    /// A built-in type keyword: `int`, `string`, `object`, `void`, `var`, `dynamic`.
    ///
    /// `var` and `dynamic` are contextual keywords the lexer hands over as identifiers; the
    /// parser folds them into this node so the binder sees one shape for all built-in names.
    record Predefined(SourceSpan span, SyntaxKind keyword) implements TypeSyntax {

        public Predefined {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(keyword, "keyword");
        }
    }

    /// A possibly qualified, possibly generic name: `Console`, `System.Collections.Generic`,
    /// `List<int>`, `global::System.String`.
    ///
    /// @param global whether the name was written with the `global::` alias qualifier
    record Name(SourceSpan span, List<Segment> segments, boolean global) implements TypeSyntax {

        public Name {
            Objects.requireNonNull(span, "span");
            segments = List.copyOf(segments);
            if (segments.isEmpty()) {
                throw new IllegalArgumentException("a name needs at least one segment");
            }
        }

        /// A single unqualified, non-generic name.
        public static Name of(SourceSpan span, String identifier) {
            return new Name(span, List.of(Segment.of(span, identifier)), false);
        }

        /// The dotted spelling, without type arguments. Used by diagnostics.
        public String text() {
            StringBuilder out = new StringBuilder(global ? "global::" : "");
            for (int i = 0; i < segments.size(); i++) {
                if (i > 0) {
                    out.append('.');
                }
                out.append(segments.get(i).identifier());
            }
            return out.toString();
        }
    }

    /// `T?`.
    ///
    /// For a value type this is `Nullable<T>`; for a reference type it is an annotation that
    /// the JVM erases. The distinction is the binder's, which is why one node covers both.
    record Nullable(SourceSpan span, TypeSyntax element) implements TypeSyntax {

        public Nullable {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(element, "element");
        }
    }

    /// `T[]`, `T[,]`, `T[][]`.
    ///
    /// @param ranks the dimension count of each specifier, outermost first: `int[,][]` is an
    ///        element type of `int` with ranks `[2, 1]`, matching how C# writes it
    record Array(SourceSpan span, TypeSyntax element, List<Integer> ranks) implements TypeSyntax {

        public Array {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(element, "element");
            ranks = List.copyOf(ranks);
            if (ranks.isEmpty()) {
                throw new IllegalArgumentException("an array type needs a rank specifier");
            }
            for (int rank : ranks) {
                if (rank < 1) {
                    throw new IllegalArgumentException("rank must be positive but was " + rank);
                }
            }
        }
    }

    /// `(int, string)` or `(int x, string y)`.
    record Tuple(SourceSpan span, List<TupleElement> elements) implements TypeSyntax {

        public Tuple {
            Objects.requireNonNull(span, "span");
            elements = List.copyOf(elements);
            if (elements.size() < 2) {
                throw new IllegalArgumentException("a tuple type needs at least two elements");
            }
        }
    }

    /// `ref T` or `ref readonly T`, valid on locals and return types.
    record Ref(SourceSpan span, TypeSyntax element, boolean readOnly) implements TypeSyntax {

        public Ref {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(element, "element");
        }
    }

    /// The missing argument of an unbound generic name, as in `typeof(List<>)`.
    record Omitted(SourceSpan span) implements TypeSyntax {

        public Omitted {
            Objects.requireNonNull(span, "span");
        }
    }

    /// A type the parser could not read. Already reported; keeps the tree shaped so that
    /// later syntax is still analysed.
    record Missing(SourceSpan span) implements TypeSyntax {

        public Missing {
            Objects.requireNonNull(span, "span");
        }
    }
}
