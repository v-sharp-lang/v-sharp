package vsharp.compiler.syntax;

import java.util.List;
import java.util.Objects;
import vsharp.compiler.source.SourceSpan;

/// A namespace, static grouping, value type, field or callable declaration.
public sealed interface DeclarationSyntax extends SyntaxNode {

    record Namespace(SourceSpan span, TypeSyntax.Name name, boolean fileScoped,
            List<AuxiliarySyntax.UsingDirective> usings, List<DeclarationSyntax> declarations,
            List<StatementSyntax> statements) implements DeclarationSyntax {
        public Namespace {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(name, "name");
            usings = List.copyOf(usings);
            declarations = List.copyOf(declarations);
            statements = List.copyOf(statements);
        }
    }

    /// A static C# class used only as a namespace-like function/constant container.
    record StaticContainer(SourceSpan span, List<AuxiliarySyntax.AttributeList> attributes,
            List<SyntaxKind> modifiers, String name,
            List<AuxiliarySyntax.TypeParameter> typeParameters,
            List<AuxiliarySyntax.ConstraintClause> constraints,
            List<DeclarationSyntax> members) implements DeclarationSyntax {
        public StaticContainer {
            Objects.requireNonNull(span, "span");
            attributes = List.copyOf(attributes);
            modifiers = List.copyOf(modifiers);
            Objects.requireNonNull(name, "name");
            typeParameters = List.copyOf(typeParameters);
            constraints = List.copyOf(constraints);
            members = List.copyOf(members);
        }
    }

    record Enum(SourceSpan span, List<AuxiliarySyntax.AttributeList> attributes,
            List<SyntaxKind> modifiers, String name, TypeSyntax underlyingType,
            List<AuxiliarySyntax.EnumMember> members) implements DeclarationSyntax {
        public Enum {
            Objects.requireNonNull(span, "span");
            attributes = List.copyOf(attributes);
            modifiers = List.copyOf(modifiers);
            Objects.requireNonNull(name, "name");
            members = List.copyOf(members);
        }
    }

    record Struct(SourceSpan span, List<AuxiliarySyntax.AttributeList> attributes,
            List<SyntaxKind> modifiers, boolean record, String name,
            List<AuxiliarySyntax.TypeParameter> typeParameters,
            List<AuxiliarySyntax.Parameter> primaryParameters,
            List<AuxiliarySyntax.ConstraintClause> constraints,
            List<DeclarationSyntax> members) implements DeclarationSyntax {
        public Struct {
            Objects.requireNonNull(span, "span");
            attributes = List.copyOf(attributes);
            modifiers = List.copyOf(modifiers);
            Objects.requireNonNull(name, "name");
            typeParameters = List.copyOf(typeParameters);
            primaryParameters = List.copyOf(primaryParameters);
            constraints = List.copyOf(constraints);
            members = List.copyOf(members);
        }
    }

    record Field(SourceSpan span, List<AuxiliarySyntax.AttributeList> attributes,
            List<SyntaxKind> modifiers, TypeSyntax type,
            List<AuxiliarySyntax.VariableDeclarator> variables) implements DeclarationSyntax {
        public Field {
            Objects.requireNonNull(span, "span");
            attributes = List.copyOf(attributes);
            modifiers = List.copyOf(modifiers);
            Objects.requireNonNull(type, "type");
            variables = List.copyOf(variables);
        }
    }

    /// A method or local function. Exactly one of `body` and `expressionBody` is normally
    /// present; both may be absent after recovery or for an extern declaration.
    record Method(SourceSpan span, List<AuxiliarySyntax.AttributeList> attributes,
            List<SyntaxKind> modifiers, TypeSyntax returnType, String name,
            List<AuxiliarySyntax.TypeParameter> typeParameters,
            List<AuxiliarySyntax.Parameter> parameters,
            List<AuxiliarySyntax.ConstraintClause> constraints, StatementSyntax.Block body,
            ExpressionSyntax expressionBody) implements DeclarationSyntax {
        public Method {
            Objects.requireNonNull(span, "span");
            attributes = List.copyOf(attributes);
            modifiers = List.copyOf(modifiers);
            Objects.requireNonNull(returnType, "returnType");
            Objects.requireNonNull(name, "name");
            typeParameters = List.copyOf(typeParameters);
            parameters = List.copyOf(parameters);
            constraints = List.copyOf(constraints);
        }
    }

    record Operator(SourceSpan span, List<AuxiliarySyntax.AttributeList> attributes,
            List<SyntaxKind> modifiers, TypeSyntax returnType, SyntaxKind operator,
            List<AuxiliarySyntax.Parameter> parameters, StatementSyntax.Block body,
            ExpressionSyntax expressionBody) implements DeclarationSyntax {
        public Operator {
            Objects.requireNonNull(span, "span");
            attributes = List.copyOf(attributes);
            modifiers = List.copyOf(modifiers);
            Objects.requireNonNull(returnType, "returnType");
            Objects.requireNonNull(operator, "operator");
            parameters = List.copyOf(parameters);
        }
    }

    record ConversionOperator(SourceSpan span, List<AuxiliarySyntax.AttributeList> attributes,
            List<SyntaxKind> modifiers, boolean implicit, TypeSyntax targetType,
            AuxiliarySyntax.Parameter parameter, StatementSyntax.Block body,
            ExpressionSyntax expressionBody) implements DeclarationSyntax {
        public ConversionOperator {
            Objects.requireNonNull(span, "span");
            attributes = List.copyOf(attributes);
            modifiers = List.copyOf(modifiers);
            Objects.requireNonNull(targetType, "targetType");
            Objects.requireNonNull(parameter, "parameter");
        }
    }

    /// A recognised but excluded declaration. Keeping its extent in the tree lets later
    /// declarations be parsed and diagnostics remain useful after an object-model feature.
    record Unsupported(SourceSpan span, SyntaxKind introducer, String name)
            implements DeclarationSyntax {
        public Unsupported {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(introducer, "introducer");
        }
    }
}
