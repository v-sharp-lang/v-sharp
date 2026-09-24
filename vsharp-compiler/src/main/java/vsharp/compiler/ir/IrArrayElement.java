package vsharp.compiler.ir;

import java.util.List;
import java.util.Objects;

/// One scalar or nested array-initializer element.
public sealed interface IrArrayElement permits IrArrayElement.Value, IrArrayElement.Nested {

    record Value(IrExpression expression) implements IrArrayElement {
        public Value { Objects.requireNonNull(expression, "expression"); }
    }

    record Nested(List<IrArrayElement> elements) implements IrArrayElement {
        public Nested { elements = List.copyOf(elements); }
    }
}
