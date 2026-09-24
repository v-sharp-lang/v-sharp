package vsharp.compiler.ir;

import java.util.Objects;

/// One ordered switch-expression arm after pattern and result binding.
public record IrSwitchExpressionArm(IrPattern pattern, IrExpression guard,
        IrExpression expression) {
    public IrSwitchExpressionArm {
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(expression, "expression");
    }
}
