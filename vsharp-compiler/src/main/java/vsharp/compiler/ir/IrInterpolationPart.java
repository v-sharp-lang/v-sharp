package vsharp.compiler.ir;

import java.util.Objects;

/// A literal run or formatted hole in an interpolated string expression.
public sealed interface IrInterpolationPart permits IrInterpolationPart.Text, IrInterpolationPart.Hole {

    record Text(String value) implements IrInterpolationPart {
        public Text { Objects.requireNonNull(value, "value"); }
    }

    record Hole(IrExpression expression, IrExpression alignment, String format)
            implements IrInterpolationPart {
        public Hole { Objects.requireNonNull(expression, "expression"); }
    }
}
