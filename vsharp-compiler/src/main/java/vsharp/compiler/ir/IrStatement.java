package vsharp.compiler.ir;

import java.util.List;
import java.util.Objects;
import vsharp.compiler.source.SourceSpan;

/// A control-flow statement with expressions already lowered to [IrExpression].
///
/// Labels are still source-level names in this first lowering layer. The verifier-oriented
/// backend will assign concrete branch labels after functions and blocks are assembled.
public sealed interface IrStatement permits IrStatement.Block, IrStatement.Empty,
        IrStatement.Expression, IrStatement.Locals, IrStatement.If, IrStatement.While,
        IrStatement.DoWhile, IrStatement.For, IrStatement.Break, IrStatement.Continue,
        IrStatement.Return, IrStatement.Throw, IrStatement.Goto, IrStatement.Labeled,
        IrStatement.Try, IrStatement.Using, IrStatement.Lock, IrStatement.Checked,
        IrStatement.Foreach, IrStatement.Switch, IrStatement.SwitchGoto {

    SourceSpan span();

    record Block(SourceSpan span, List<IrStatement> statements) implements IrStatement {
        public Block {
            Objects.requireNonNull(span, "span");
            statements = List.copyOf(statements);
        }
    }

    record Empty(SourceSpan span) implements IrStatement {
        public Empty { Objects.requireNonNull(span, "span"); }
    }

    record Expression(SourceSpan span, IrExpression expression) implements IrStatement {
        public Expression {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(expression, "expression");
        }
    }

    record Locals(SourceSpan span, List<IrLocal> locals) implements IrStatement {
        public Locals {
            Objects.requireNonNull(span, "span");
            locals = List.copyOf(locals);
            if (locals.isEmpty()) {
                throw new IllegalArgumentException("local declaration needs a local");
            }
        }
    }

    record If(SourceSpan span, IrExpression condition, IrStatement whenTrue,
            IrStatement whenFalse) implements IrStatement {
        public If {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(condition, "condition");
            Objects.requireNonNull(whenTrue, "whenTrue");
        }
    }

    record While(SourceSpan span, IrExpression condition, IrStatement body) implements IrStatement {
        public While {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(condition, "condition");
            Objects.requireNonNull(body, "body");
        }
    }

    record DoWhile(SourceSpan span, IrStatement body, IrExpression condition) implements IrStatement {
        public DoWhile {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(body, "body");
            Objects.requireNonNull(condition, "condition");
        }
    }

    record For(SourceSpan span, List<IrStatement> initializers, IrExpression condition,
            List<IrExpression> iterators, IrStatement body) implements IrStatement {
        public For {
            Objects.requireNonNull(span, "span");
            initializers = List.copyOf(initializers);
            iterators = List.copyOf(iterators);
            Objects.requireNonNull(body, "body");
        }
    }

    record Break(SourceSpan span) implements IrStatement {
        public Break { Objects.requireNonNull(span, "span"); }
    }

    record Continue(SourceSpan span) implements IrStatement {
        public Continue { Objects.requireNonNull(span, "span"); }
    }

    record Return(SourceSpan span, IrExpression expression) implements IrStatement {
        public Return { Objects.requireNonNull(span, "span"); }
    }

    record Throw(SourceSpan span, IrExpression expression) implements IrStatement {
        public Throw { Objects.requireNonNull(span, "span"); }
    }

    record Goto(SourceSpan span, String label) implements IrStatement {
        public Goto {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(label, "label");
        }
    }

    record Labeled(SourceSpan span, String label, IrStatement statement) implements IrStatement {
        public Labeled {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(statement, "statement");
        }
    }

    /// Structured exception handling; emission later creates the exception-table entries.
    record Try(SourceSpan span, Block body, List<IrCatch> catches, Block finallyBody)
            implements IrStatement {
        public Try {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(body, "body");
            catches = List.copyOf(catches);
            if (catches.isEmpty() && finallyBody == null) {
                throw new IllegalArgumentException("try needs a catch or finally body");
            }
        }
    }

    /// A resource declaration/expression and optional nested lifetime body.
    record Using(SourceSpan span, IrStatement resource, IrStatement body) implements IrStatement {
        public Using {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(resource, "resource");
            if (!(resource instanceof Locals || resource instanceof Expression)) {
                throw new IllegalArgumentException("using resource must declare or evaluate");
            }
        }
    }

    record Lock(SourceSpan span, IrExpression expression, IrStatement body) implements IrStatement {
        public Lock {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(expression, "expression");
            Objects.requireNonNull(body, "body");
        }
    }

    /// An arithmetic-overflow context retained for the bytecode arithmetic rule selection.
    record Checked(SourceSpan span, boolean checked, Block body) implements IrStatement {
        public Checked {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(body, "body");
        }
    }

    /// `elementType` is what one iteration step yields before any conversion to the variable's
    /// own type: an array's indexed element, `char` for a `string`, or the `Iterable` argument
    /// a Java collection publishes. The backend needs it to know which carrier the
    /// value arrives in, which the variable type alone does not say for `foreach (long x in
    /// List<int>)`.
    record Foreach(SourceSpan span, List<IrIterationVariable> variables,
            IrExpression collection, IrValueType elementType, IrStatement body)
            implements IrStatement {
        public Foreach {
            Objects.requireNonNull(span, "span");
            variables = List.copyOf(variables);
            if (variables.isEmpty()) {
                throw new IllegalArgumentException("foreach needs an iteration variable");
            }
            Objects.requireNonNull(collection, "collection");
            Objects.requireNonNull(elementType, "elementType");
            Objects.requireNonNull(body, "body");
        }
    }

    record Switch(SourceSpan span, IrExpression governing, List<IrSwitchSection> sections)
            implements IrStatement {
        public Switch {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(governing, "governing");
            sections = List.copyOf(sections);
        }
    }

    record SwitchGoto(SourceSpan span, SwitchGotoKind kind, IrExpression value)
            implements IrStatement {
        public enum SwitchGotoKind { CASE, DEFAULT }

        public SwitchGoto {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(kind, "kind");
            if (kind == SwitchGotoKind.CASE && value == null) {
                throw new IllegalArgumentException("goto case needs a value");
            }
            if (kind == SwitchGotoKind.DEFAULT && value != null) {
                throw new IllegalArgumentException("goto default has no value");
            }
        }
    }

}
