package vsharp.compiler.semantics.symbols;

import java.util.List;
import java.util.Objects;
import vsharp.compiler.semantics.types.TypeSymbol;
import vsharp.compiler.syntax.ExpressionSyntax;
import vsharp.compiler.syntax.SyntaxKind;

/// A callable parameter in ordinal order.
public record ParameterSymbol(String name, String qualifiedName, SourceLocation location,
        TypeSymbol type, int ordinal, List<SyntaxKind> modifiers,
        ExpressionSyntax defaultValue) implements Symbol {
    public ParameterSymbol {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(qualifiedName, "qualifiedName");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(type, "type");
        if (ordinal < 0) {
            throw new IllegalArgumentException("ordinal must not be negative");
        }
        modifiers = List.copyOf(modifiers);
    }

    @Override
    public SymbolKind kind() {
        return SymbolKind.PARAMETER;
    }
}
