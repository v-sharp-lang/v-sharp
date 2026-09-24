package vsharp.compiler.semantics.symbols;

import java.util.List;
import java.util.Objects;
import vsharp.compiler.syntax.SyntaxKind;

/// A static class used as a namespace-like owner for functions and constants.
public record ContainerSymbol(String name, String qualifiedName, SourceLocation location,
        int arity, List<SyntaxKind> modifiers) implements Symbol {
    public ContainerSymbol {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(qualifiedName, "qualifiedName");
        Objects.requireNonNull(location, "location");
        if (arity < 0) {
            throw new IllegalArgumentException("arity must not be negative");
        }
        modifiers = List.copyOf(modifiers);
    }

    @Override
    public SymbolKind kind() {
        return SymbolKind.STATIC_CONTAINER;
    }
}
