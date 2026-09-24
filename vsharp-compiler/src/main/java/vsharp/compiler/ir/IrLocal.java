package vsharp.compiler.ir;

import java.util.Objects;
import vsharp.compiler.semantics.symbols.LocalSymbol;

/// A local declaration and its optional initializer in IR.
public record IrLocal(LocalSymbol symbol, IrExpression initializer) {
    public IrLocal {
        Objects.requireNonNull(symbol, "symbol");
    }
}
