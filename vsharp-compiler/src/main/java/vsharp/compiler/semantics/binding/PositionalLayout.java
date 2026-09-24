package vsharp.compiler.semantics.binding;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import vsharp.compiler.semantics.symbols.FieldSymbol;
import vsharp.compiler.semantics.symbols.FunctionSymbol;
import vsharp.compiler.semantics.symbols.NamedTypeSymbol;

/// The positional shape of a `record struct`: its components in declaration order and the
/// constructor that fills them.
///
/// C# gives a positional record struct a primary constructor plus one member per parameter
/// (§15.3). V# carries those members as public instance fields - the record's mutable
/// auto-properties have no property machinery behind them here, and a field is the same
/// storage with the same name, so `p.X` reads and writes the component C# names.
/// The constructor is synthesized rather than declared: no source declares it, it has no
/// body to lower, and the backend emits it structurally from this layout.
public record PositionalLayout(NamedTypeSymbol type, List<FieldSymbol> components,
        FunctionSymbol constructor) {

    public PositionalLayout {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(constructor, "constructor");
        components = List.copyOf(components);
        if (components.size() != constructor.parameters().size()) {
            throw new IllegalArgumentException(
                    "record struct component/parameter count mismatch: " + type.qualifiedName());
        }
    }

    /// The component named `name`, when the record struct declares one.
    public Optional<FieldSymbol> component(String name) {
        Objects.requireNonNull(name, "name");
        return components.stream().filter(field -> field.name().equals(name)).findFirst();
    }

    /// The position of `name` among the components, or `-1`.
    public int indexOf(String name) {
        for (int index = 0; index < components.size(); index++) {
            if (components.get(index).name().equals(name)) {
                return index;
            }
        }
        return -1;
    }
}
