package vsharp.compiler.ir;

import java.util.List;
import java.util.Objects;
import vsharp.compiler.semantics.symbols.FieldSymbol;
import vsharp.compiler.semantics.symbols.FunctionSymbol;

/// The constructor the backend must synthesize for a positional `record struct`.
///
/// The declaration has no constructor body to lower - the constructor is defined structurally
/// as "store parameter `i` into component `i`" - so it reaches the backend as this shape
/// rather than as an [IrFunction] with statements.
public record IrRecordStruct(String owner, List<FieldSymbol> components,
        FunctionSymbol constructor) {

    public IrRecordStruct {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(constructor, "constructor");
        components = List.copyOf(components);
        if (components.size() != constructor.parameters().size()) {
            throw new IllegalArgumentException("record struct arity mismatch: " + owner);
        }
    }
}
