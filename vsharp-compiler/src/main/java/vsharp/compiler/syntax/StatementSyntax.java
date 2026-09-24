package vsharp.compiler.syntax;

import java.util.List;
import java.util.Objects;
import vsharp.compiler.source.SourceSpan;

/// A statement, including top-level statements and local-function declarations.
public sealed interface StatementSyntax extends SyntaxNode {

    record Block(SourceSpan span, List<StatementSyntax> statements) implements StatementSyntax {
        public Block {
            Objects.requireNonNull(span, "span");
            statements = List.copyOf(statements);
        }
    }

    record Empty(SourceSpan span) implements StatementSyntax {
        public Empty { Objects.requireNonNull(span, "span"); }
    }

    record Expression(SourceSpan span, ExpressionSyntax expression) implements StatementSyntax {
        public Expression {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(expression, "expression");
        }
    }

    record LocalDeclaration(SourceSpan span, boolean constant, boolean using,
            TypeSyntax type, List<AuxiliarySyntax.VariableDeclarator> variables)
            implements StatementSyntax {
        public LocalDeclaration {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(type, "type");
            variables = List.copyOf(variables);
        }
    }

    record If(SourceSpan span, ExpressionSyntax condition, StatementSyntax whenTrue,
            StatementSyntax whenFalse) implements StatementSyntax {
        public If {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(condition, "condition");
            Objects.requireNonNull(whenTrue, "whenTrue");
        }
    }

    record While(SourceSpan span, ExpressionSyntax condition, StatementSyntax body)
            implements StatementSyntax {
        public While {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(condition, "condition");
            Objects.requireNonNull(body, "body");
        }
    }

    record Do(SourceSpan span, StatementSyntax body, ExpressionSyntax condition)
            implements StatementSyntax {
        public Do {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(body, "body");
            Objects.requireNonNull(condition, "condition");
        }
    }

    record For(SourceSpan span, List<StatementSyntax> initializers, ExpressionSyntax condition,
            List<ExpressionSyntax> iterators, StatementSyntax body) implements StatementSyntax {
        public For {
            Objects.requireNonNull(span, "span");
            initializers = List.copyOf(initializers);
            iterators = List.copyOf(iterators);
            Objects.requireNonNull(body, "body");
        }
    }

    record Foreach(SourceSpan span, TypeSyntax type, PatternSyntax variable,
            ExpressionSyntax collection, StatementSyntax body) implements StatementSyntax {
        public Foreach {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(variable, "variable");
            Objects.requireNonNull(collection, "collection");
            Objects.requireNonNull(body, "body");
        }
    }

    record Switch(SourceSpan span, ExpressionSyntax expression,
            List<AuxiliarySyntax.SwitchSection> sections) implements StatementSyntax {
        public Switch {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(expression, "expression");
            sections = List.copyOf(sections);
        }
    }

    record Break(SourceSpan span) implements StatementSyntax {
        public Break { Objects.requireNonNull(span, "span"); }
    }

    record Continue(SourceSpan span) implements StatementSyntax {
        public Continue { Objects.requireNonNull(span, "span"); }
    }

    record Return(SourceSpan span, ExpressionSyntax expression) implements StatementSyntax {
        public Return { Objects.requireNonNull(span, "span"); }
    }

    record Throw(SourceSpan span, ExpressionSyntax expression) implements StatementSyntax {
        public Throw { Objects.requireNonNull(span, "span"); }
    }

    record Goto(SourceSpan span, Kind kind, String label, ExpressionSyntax value)
            implements StatementSyntax {
        public enum Kind { LABEL, CASE, DEFAULT }

        public Goto {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(kind, "kind");
        }
    }

    record Labeled(SourceSpan span, String label, StatementSyntax statement)
            implements StatementSyntax {
        public Labeled {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(statement, "statement");
        }
    }

    record Try(SourceSpan span, Block body, List<AuxiliarySyntax.CatchClause> catches,
            Block finallyBody) implements StatementSyntax {
        public Try {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(body, "body");
            catches = List.copyOf(catches);
        }
    }

    /// A using statement. `resource` is a local declaration or an expression. `body` is
    /// absent for a using declaration, whose lifetime extends to the containing scope.
    record Using(SourceSpan span, SyntaxNode resource, StatementSyntax body)
            implements StatementSyntax {
        public Using {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(resource, "resource");
            if (!(resource instanceof LocalDeclaration || resource instanceof ExpressionSyntax)) {
                throw new IllegalArgumentException("using resource must declare or evaluate");
            }
        }
    }

    record Lock(SourceSpan span, ExpressionSyntax expression, StatementSyntax body)
            implements StatementSyntax {
        public Lock {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(expression, "expression");
            Objects.requireNonNull(body, "body");
        }
    }

    record Checked(SourceSpan span, boolean checked, Block body) implements StatementSyntax {
        public Checked {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(body, "body");
        }
    }

    record Yield(SourceSpan span, boolean isBreak, ExpressionSyntax expression)
            implements StatementSyntax {
        public Yield { Objects.requireNonNull(span, "span"); }
    }

    record LocalFunction(SourceSpan span, DeclarationSyntax.Method declaration)
            implements StatementSyntax {
        public LocalFunction {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(declaration, "declaration");
        }
    }
}
