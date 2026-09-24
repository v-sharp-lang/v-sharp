package vsharp.compiler.ir;

import java.util.Objects;
import vsharp.compiler.semantics.symbols.LocalSymbol;

/// One typed catch handler before JVM exception-table construction.
///
/// `type` and `variable` are both absent only for a bare `catch`. A typed catch may omit its
/// variable, as C# permits.
public record IrCatch(IrValueType type, LocalSymbol variable, IrExpression filter,
        IrStatement.Block body) {
    public IrCatch {
        Objects.requireNonNull(body, "body");
        if (variable != null && type == null) {
            throw new IllegalArgumentException("catch variable needs a catch type");
        }
    }
}
