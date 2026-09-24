package vsharp.compiler.ir;

import java.util.Objects;
import vsharp.compiler.semantics.symbols.LocalSymbol;

/// A foreach variable after `var` inference and pattern declaration binding.
public record IrIterationVariable(LocalSymbol symbol, IrValueType type) {
    public IrIterationVariable {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(type, "type");
    }
}
