package vsharp.compiler.ir;

import java.util.Objects;

/// One scalar or spread input to a target-typed collection expression.
public record IrCollectionElement(boolean spread, IrExpression expression) {
    public IrCollectionElement { Objects.requireNonNull(expression, "expression"); }
}
