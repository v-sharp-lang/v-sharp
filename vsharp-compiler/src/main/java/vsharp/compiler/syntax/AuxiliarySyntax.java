package vsharp.compiler.syntax;

import java.util.List;
import java.util.Objects;
import vsharp.compiler.source.SourceSpan;

/// Immutable syntax carriers which support declarations, expressions and statements.
///
/// These nodes are deliberately kept out of the five principal grammar categories. A
/// parameter, argument or switch arm is meaningful only as a child of another node, but it
/// still owns a source span and participates in the closed syntax-tree algebra.
public sealed interface AuxiliarySyntax extends SyntaxNode
        permits AuxiliarySyntax.Argument, AuxiliarySyntax.Attribute,
                AuxiliarySyntax.AttributeList, AuxiliarySyntax.CatchClause,
                AuxiliarySyntax.CollectionElement, AuxiliarySyntax.CompilationUnit,
                AuxiliarySyntax.ConstraintClause, AuxiliarySyntax.Directive,
                AuxiliarySyntax.EnumMember, AuxiliarySyntax.InterpolationElement,
                AuxiliarySyntax.Parameter, AuxiliarySyntax.PropertySubpattern,
                AuxiliarySyntax.SwitchExpressionArm, AuxiliarySyntax.SwitchLabel,
                AuxiliarySyntax.SwitchSection, AuxiliarySyntax.TypeParameter,
                AuxiliarySyntax.UsingDirective, AuxiliarySyntax.VariableDeclarator,
                TypeSyntax.Segment, TypeSyntax.TupleElement {

    /// A complete source file.
    ///
    /// The four syntax lists retain source order within their categories. Cross-category
    /// ordering is recoverable from spans and is checked by the binder where C# imposes an
    /// order.
    ///
    /// `inlineBraces` carries the start offsets of the opening braces the grammar writes
    /// inside an expression or a pattern - array initializers, property subpatterns and
    /// `with` initializers. A brace's grammatical role is known only to the parser, and
    /// source-layout validation is a token pass that would otherwise have to guess it.
    record CompilationUnit(
            SourceSpan span,
            List<Directive> directives,
            List<UsingDirective> usings,
            List<DeclarationSyntax> declarations,
            List<StatementSyntax> statements,
            List<Integer> inlineBraces) implements AuxiliarySyntax {

        public CompilationUnit {
            Objects.requireNonNull(span, "span");
            directives = List.copyOf(directives);
            usings = List.copyOf(usings);
            declarations = List.copyOf(declarations);
            statements = List.copyOf(statements);
            inlineBraces = List.copyOf(inlineBraces);
        }
    }

    /// `global using static A.B;`, `using A.B;`, or `using Alias = A.B;`.
    record UsingDirective(SourceSpan span, boolean global, boolean isStatic, String alias,
            TypeSyntax.Name target) implements AuxiliarySyntax {

        public UsingDirective {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(target, "target");
        }
    }

    /// One preprocessing directive. The argument is preserved verbatim, without its
    /// leading/trailing horizontal whitespace, for the preprocessing stage.
    record Directive(SourceSpan span, Kind kind, String argument) implements AuxiliarySyntax {

        public enum Kind {
            DEFINE, UNDEF, IF, ELIF, ELSE, ENDIF, REGION, ENDREGION, ERROR, WARNING,
            LINE, NULLABLE, PRAGMA, UNKNOWN
        }

        public Directive {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(argument, "argument");
        }
    }

    /// `[A]` or `[return: A(1, Name = value)]`.
    record AttributeList(SourceSpan span, String target, List<Attribute> attributes)
            implements AuxiliarySyntax {

        public AttributeList {
            Objects.requireNonNull(span, "span");
            attributes = List.copyOf(attributes);
            if (attributes.isEmpty()) {
                throw new IllegalArgumentException("an attribute list cannot be empty");
            }
        }
    }

    /// One attribute within an attribute list.
    record Attribute(SourceSpan span, TypeSyntax.Name name, List<Argument> arguments)
            implements AuxiliarySyntax {

        public Attribute {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(name, "name");
            arguments = List.copyOf(arguments);
        }
    }

    /// A generic parameter declaration.
    record TypeParameter(SourceSpan span, String name, List<AttributeList> attributes)
            implements AuxiliarySyntax {

        public TypeParameter {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(name, "name");
            attributes = List.copyOf(attributes);
        }
    }

    /// `where T : struct, IFoo, new()`.
    ///
    /// Constraints are stored as their syntax nodes. Keyword constraints use [TypeSyntax.Name]
    /// and `new()` uses the spelling `new()`; the binder gives each spelling its semantics.
    record ConstraintClause(SourceSpan span, String parameter, List<TypeSyntax> constraints,
            boolean constructorConstraint, boolean allowsRefStruct) implements AuxiliarySyntax {

        public ConstraintClause {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(parameter, "parameter");
            constraints = List.copyOf(constraints);
        }
    }

    /// One callable parameter.
    ///
    /// @param modifiers `scoped`, `ref`, `out`, `in`, `params`, and/or `this`
    /// @param type absent only for an implicitly typed lambda parameter
    /// @param defaultValue optional default argument expression
    record Parameter(SourceSpan span, List<AttributeList> attributes, List<SyntaxKind> modifiers,
            TypeSyntax type, String name, ExpressionSyntax defaultValue)
            implements AuxiliarySyntax {

        public Parameter {
            Objects.requireNonNull(span, "span");
            attributes = List.copyOf(attributes);
            modifiers = List.copyOf(modifiers);
            Objects.requireNonNull(name, "name");
        }
    }

    /// One invocation, element-access or attribute argument.
    ///
    /// @param name the named-argument label, or `null`
    /// @param modifier `ref`, `out` or `in`, or `null`
    record Argument(SourceSpan span, String name, SyntaxKind modifier, ExpressionSyntax expression)
            implements AuxiliarySyntax {

        public Argument {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(expression, "expression");
        }
    }

    /// A local, field or `for` declaration entry.
    record VariableDeclarator(SourceSpan span, String name, PatternSyntax designation,
            ExpressionSyntax initializer)
            implements AuxiliarySyntax {

        public VariableDeclarator {
            Objects.requireNonNull(span, "span");
            if ((name == null) == (designation == null)) {
                throw new IllegalArgumentException(
                        "a variable needs exactly one of name and designation");
            }
        }

        /// A non-deconstruction variable.
        public static VariableDeclarator named(SourceSpan span, String name,
                ExpressionSyntax initializer) {
            return new VariableDeclarator(span, Objects.requireNonNull(name, "name"), null,
                    initializer);
        }
    }

    /// An enum constant with an optional explicit value.
    record EnumMember(SourceSpan span, List<AttributeList> attributes, String name,
            ExpressionSyntax value) implements AuxiliarySyntax {

        public EnumMember {
            Objects.requireNonNull(span, "span");
            attributes = List.copyOf(attributes);
            Objects.requireNonNull(name, "name");
        }
    }

    /// An element of a C# 12 collection expression. `spread` distinguishes `..items`.
    record CollectionElement(SourceSpan span, boolean spread, ExpressionSyntax expression)
            implements AuxiliarySyntax {

        public CollectionElement {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(expression, "expression");
        }
    }

    /// One parsed component of an interpolated string.
    sealed interface InterpolationElement extends AuxiliarySyntax
            permits InterpolationElement.Text, InterpolationElement.Hole {

        /// Decoded literal text.
        record Text(SourceSpan span, String value) implements InterpolationElement {

            public Text {
                Objects.requireNonNull(span, "span");
                Objects.requireNonNull(value, "value");
            }
        }

        /// A parsed interpolation hole.
        record Hole(SourceSpan span, ExpressionSyntax expression, ExpressionSyntax alignment,
                String format) implements InterpolationElement {

            public Hole {
                Objects.requireNonNull(span, "span");
                Objects.requireNonNull(expression, "expression");
            }
        }
    }

    /// One arm of a switch expression.
    record SwitchExpressionArm(SourceSpan span, PatternSyntax pattern, ExpressionSyntax guard,
            ExpressionSyntax expression) implements AuxiliarySyntax {

        public SwitchExpressionArm {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(pattern, "pattern");
            Objects.requireNonNull(expression, "expression");
        }
    }

    /// A `case` or `default` label in a switch statement.
    ///
    /// `pattern` is absent for `default`; `guard` is optional.
    record SwitchLabel(SourceSpan span, PatternSyntax pattern, ExpressionSyntax guard)
            implements AuxiliarySyntax {

        public SwitchLabel {
            Objects.requireNonNull(span, "span");
        }
    }

    /// Consecutive switch labels and the statements they govern.
    record SwitchSection(SourceSpan span, List<SwitchLabel> labels,
            List<StatementSyntax> statements) implements AuxiliarySyntax {

        public SwitchSection {
            Objects.requireNonNull(span, "span");
            labels = List.copyOf(labels);
            statements = List.copyOf(statements);
            if (labels.isEmpty()) {
                throw new IllegalArgumentException("a switch section needs a label");
            }
        }
    }

    /// One catch clause, including an optional exception filter.
    ///
    /// @param nameSpan the span of the exception variable, or `null` when the clause declares
    ///     none; the clause span reaches past the body and so cannot locate the declaration
    record CatchClause(SourceSpan span, TypeSyntax type, String name, SourceSpan nameSpan,
            ExpressionSyntax filter, StatementSyntax.Block body) implements AuxiliarySyntax {

        public CatchClause {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(body, "body");
            if ((name == null) != (nameSpan == null)) {
                throw new IllegalArgumentException("a catch name needs its own span");
            }
        }
    }

    /// A named property component of a recursive pattern.
    record PropertySubpattern(SourceSpan span, String name, PatternSyntax pattern)
            implements AuxiliarySyntax {

        public PropertySubpattern {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(pattern, "pattern");
        }
    }
}
