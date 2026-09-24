package vsharp.compiler.semantics.symbols;

import java.util.Objects;
import vsharp.compiler.semantics.types.TypeSymbol;

/// A named constant declared by an enum.
///
/// The `value` is the member's constant in the enum's underlying type, assigned by C#'s rule:
/// members count up from zero, and an explicit initialiser resets the count. It is carried on
/// the symbol because an enum has no run-time identity of its own - a member *is* its value
/// everywhere it is read, compared or switched on.
public record EnumMemberSymbol(String name, String qualifiedName, SourceLocation location,
        TypeSymbol type, int value) implements Symbol {
    public EnumMemberSymbol {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(qualifiedName, "qualifiedName");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(type, "type");
    }

    @Override
    public SymbolKind kind() {
        return SymbolKind.ENUM_MEMBER;
    }
}
