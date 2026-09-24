package vsharp.compiler.syntax;

import java.util.List;
import java.util.Objects;
import vsharp.compiler.source.SourceSpan;

/// An expression as written in source, before binding or constant folding.
public sealed interface ExpressionSyntax extends SyntaxNode {

    record Missing(SourceSpan span) implements ExpressionSyntax {
        public Missing { Objects.requireNonNull(span, "span"); }
    }

    /// Recognised syntax which is intentionally outside the V# language boundary.
    record Unsupported(SourceSpan span, SyntaxKind introducer, String description)
            implements ExpressionSyntax {
        public Unsupported {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(introducer, "introducer");
            Objects.requireNonNull(description, "description");
        }
    }

    record Literal(SourceSpan span, SyntaxToken token) implements ExpressionSyntax {
        public Literal {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(token, "token");
        }
    }

    record Identifier(SourceSpan span, String name, List<TypeSyntax> typeArguments)
            implements ExpressionSyntax {
        public Identifier {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(name, "name");
            typeArguments = List.copyOf(typeArguments);
        }
    }

    /// A predefined type used where an expression is expected, as in `int.MaxValue`.
    ///
    /// C# allows a keyword type to appear only as the receiver of a member access (§12.8.7),
    /// never as a value, so the parser produces this node only when a `.` follows. Keeping it
    /// distinct from [Identifier] is what lets the binder resolve `int` to the corelib type
    /// without the keyword ever entering ordinary name lookup.
    record PredefinedType(SourceSpan span, SyntaxKind keyword) implements ExpressionSyntax {
        public PredefinedType {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(keyword, "keyword");
        }
    }

    /// A declaration expression such as the `var value` in `M(out var value)`.
    record Declaration(SourceSpan span, TypeSyntax type, PatternSyntax designation)
            implements ExpressionSyntax {
        public Declaration {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(designation, "designation");
        }
    }

    record Parenthesized(SourceSpan span, ExpressionSyntax expression)
            implements ExpressionSyntax {
        public Parenthesized {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(expression, "expression");
        }
    }

    record Tuple(SourceSpan span, List<ExpressionSyntax> elements) implements ExpressionSyntax {
        public Tuple {
            Objects.requireNonNull(span, "span");
            elements = List.copyOf(elements);
            if (elements.size() < 2) {
                throw new IllegalArgumentException("a tuple expression needs two elements");
            }
        }
    }

    record Unary(SourceSpan span, SyntaxKind operator, ExpressionSyntax operand)
            implements ExpressionSyntax {
        public Unary {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(operator, "operator");
            Objects.requireNonNull(operand, "operand");
        }
    }

    /// `await operand`: the completion of a task, read as a value.
    ///
    /// A separate node rather than a [Unary] operator kind, because it is not one: no operand
    /// type resolves it through the operator tables, it is legal only where the grammar put an
    /// async context, and it lowers to a call rather than to an instruction.
    record Await(SourceSpan span, ExpressionSyntax operand) implements ExpressionSyntax {
        public Await {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(operand, "operand");
        }
    }

    record Binary(SourceSpan span, ExpressionSyntax left, SyntaxKind operator,
            ExpressionSyntax right) implements ExpressionSyntax {
        public Binary {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(operator, "operator");
            Objects.requireNonNull(right, "right");
        }
    }

    record Assignment(SourceSpan span, ExpressionSyntax target, SyntaxKind operator,
            ExpressionSyntax value) implements ExpressionSyntax {
        public Assignment {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(operator, "operator");
            Objects.requireNonNull(value, "value");
        }
    }

    record Conditional(SourceSpan span, ExpressionSyntax condition, ExpressionSyntax whenTrue,
            ExpressionSyntax whenFalse) implements ExpressionSyntax {
        public Conditional {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(condition, "condition");
            Objects.requireNonNull(whenTrue, "whenTrue");
            Objects.requireNonNull(whenFalse, "whenFalse");
        }
    }

    record MemberAccess(SourceSpan span, ExpressionSyntax receiver, String name,
            List<TypeSyntax> typeArguments, boolean nullConditional)
            implements ExpressionSyntax {
        public MemberAccess {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(receiver, "receiver");
            Objects.requireNonNull(name, "name");
            typeArguments = List.copyOf(typeArguments);
        }
    }

    record ElementAccess(SourceSpan span, ExpressionSyntax receiver,
            List<AuxiliarySyntax.Argument> arguments, boolean nullConditional)
            implements ExpressionSyntax {
        public ElementAccess {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(receiver, "receiver");
            arguments = List.copyOf(arguments);
        }
    }

    record Invocation(SourceSpan span, ExpressionSyntax target,
            List<AuxiliarySyntax.Argument> arguments) implements ExpressionSyntax {
        public Invocation {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(target, "target");
            arguments = List.copyOf(arguments);
        }
    }

    record Postfix(SourceSpan span, ExpressionSyntax operand, SyntaxKind operator)
            implements ExpressionSyntax {
        public Postfix {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(operand, "operand");
            Objects.requireNonNull(operator, "operator");
        }
    }

    record Cast(SourceSpan span, TypeSyntax type, ExpressionSyntax expression)
            implements ExpressionSyntax {
        public Cast {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(expression, "expression");
        }
    }

    /// @param returnType the explicit return type, or `null` for the usual inferred form
    record Lambda(SourceSpan span, boolean isStatic, TypeSyntax returnType,
            List<AuxiliarySyntax.Parameter> parameters,
            SyntaxNode body) implements ExpressionSyntax {
        public Lambda {
            Objects.requireNonNull(span, "span");
            parameters = List.copyOf(parameters);
            Objects.requireNonNull(body, "body");
            if (!(body instanceof ExpressionSyntax || body instanceof StatementSyntax.Block)) {
                throw new IllegalArgumentException("a lambda body is an expression or block");
            }
        }
    }

    record Switch(SourceSpan span, ExpressionSyntax governing,
            List<AuxiliarySyntax.SwitchExpressionArm> arms) implements ExpressionSyntax {
        public Switch {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(governing, "governing");
            arms = List.copyOf(arms);
        }
    }

    record Throw(SourceSpan span, ExpressionSyntax expression) implements ExpressionSyntax {
        public Throw {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(expression, "expression");
        }
    }

    record Default(SourceSpan span, TypeSyntax type) implements ExpressionSyntax {
        public Default { Objects.requireNonNull(span, "span"); }
    }

    record TypeOperator(SourceSpan span, SyntaxKind operator, TypeSyntax type)
            implements ExpressionSyntax {
        public TypeOperator {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(operator, "operator");
            Objects.requireNonNull(type, "type");
        }
    }

    record NameOf(SourceSpan span, ExpressionSyntax expression) implements ExpressionSyntax {
        public NameOf {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(expression, "expression");
        }
    }

    record Checked(SourceSpan span, boolean checked, ExpressionSyntax expression)
            implements ExpressionSyntax {
        public Checked {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(expression, "expression");
        }
    }

    record IsPattern(SourceSpan span, ExpressionSyntax expression, PatternSyntax pattern)
            implements ExpressionSyntax {
        public IsPattern {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(expression, "expression");
            Objects.requireNonNull(pattern, "pattern");
        }
    }

    record As(SourceSpan span, ExpressionSyntax expression, TypeSyntax type)
            implements ExpressionSyntax {
        public As {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(expression, "expression");
            Objects.requireNonNull(type, "type");
        }
    }

    record Range(SourceSpan span, ExpressionSyntax start, ExpressionSyntax end)
            implements ExpressionSyntax {
        public Range { Objects.requireNonNull(span, "span"); }
    }

    record Collection(SourceSpan span, List<AuxiliarySyntax.CollectionElement> elements)
            implements ExpressionSyntax {
        public Collection {
            Objects.requireNonNull(span, "span");
            elements = List.copyOf(elements);
        }
    }

    record Interpolated(SourceSpan span, List<AuxiliarySyntax.InterpolationElement> elements)
            implements ExpressionSyntax {
        public Interpolated {
            Objects.requireNonNull(span, "span");
            elements = List.copyOf(elements);
        }
    }

    record ArrayCreation(SourceSpan span, TypeSyntax elementType,
            List<ExpressionSyntax> dimensions, List<ExpressionSyntax> initializer)
            implements ExpressionSyntax {
        public ArrayCreation {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(elementType, "elementType");
            dimensions = List.copyOf(dimensions);
            initializer = List.copyOf(initializer);
        }
    }

    /// Construction at the Java interop boundary. Semantic binding rejects this node unless
    /// `type` resolves to a module-path Java class with an applicable public constructor.
    record ObjectCreation(SourceSpan span, TypeSyntax type,
            List<AuxiliarySyntax.Argument> arguments) implements ExpressionSyntax {
        public ObjectCreation {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(type, "type");
            arguments = List.copyOf(arguments);
        }
    }

    /// A nested `{ ... }` inside an array initializer.
    record ArrayInitializer(SourceSpan span, List<ExpressionSyntax> elements)
            implements ExpressionSyntax {
        public ArrayInitializer {
            Objects.requireNonNull(span, "span");
            elements = List.copyOf(elements);
        }
    }

    record With(SourceSpan span, ExpressionSyntax receiver,
            List<AuxiliarySyntax.VariableDeclarator> initializers) implements ExpressionSyntax {
        public With {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(receiver, "receiver");
            initializers = List.copyOf(initializers);
        }
    }
}
