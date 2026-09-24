package vsharp.compiler.semantics.symbols;

import java.util.Objects;

/// A namespace declaration; the empty qualified name is the global namespace.
public record NamespaceSymbol(String name, String qualifiedName, SourceLocation location)
        implements Symbol {
    public NamespaceSymbol {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(qualifiedName, "qualifiedName");
        Objects.requireNonNull(location, "location");
    }

    @Override
    public SymbolKind kind() {
        return SymbolKind.NAMESPACE;
    }
}
