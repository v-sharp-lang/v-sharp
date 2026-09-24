package vsharp.compiler.ir;

import java.util.Objects;
import vsharp.compiler.semantics.symbols.LocalSymbol;
import vsharp.compiler.semantics.symbols.ParameterSymbol;
import vsharp.compiler.semantics.symbols.Symbol;

/// One outer lexical value used by a nested callable.
///
/// Captures retain their original symbol identity and exact lowered type. JVM lowering decides
/// whether a value can be copied or needs a shared mutable cell; this IR boundary deliberately
/// records neither a synthetic field nor a closure-class layout.
public record IrCapture(Symbol symbol, IrValueType type) {
    public IrCapture {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(type, "type");
        if (!(symbol instanceof LocalSymbol) && !(symbol instanceof ParameterSymbol)) {
            throw new IllegalArgumentException("only lexical values can be captured: " + symbol);
        }
    }
}
