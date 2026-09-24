package vsharp.compiler.semantics.symbols;

import java.util.Objects;
import vsharp.compiler.semantics.types.TypeSymbol;

/// A local variable, including `for`, `foreach`, catch and using locals.
public record LocalSymbol(String name, String qualifiedName, SourceLocation location,
        TypeSymbol type, boolean constant, boolean using) implements Symbol {
    public LocalSymbol {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(qualifiedName, "qualifiedName");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(type, "type");
    }

    @Override
    public SymbolKind kind() {
        return SymbolKind.LOCAL;
    }
}
