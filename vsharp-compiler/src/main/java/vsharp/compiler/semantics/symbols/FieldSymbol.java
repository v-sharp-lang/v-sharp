package vsharp.compiler.semantics.symbols;

import java.util.List;
import java.util.Objects;
import vsharp.compiler.semantics.constants.ConstantSlot;
import vsharp.compiler.semantics.constants.ConstantValue;
import vsharp.compiler.semantics.types.TypeSymbol;
import vsharp.compiler.syntax.SyntaxKind;

/// A field or constant owned by a V# container/value type or a discovered Java class.
///
/// The constant value is held in a [ConstantSlot] rather than directly, because a `const`
/// field is declared before its initializer can be bound and is folded afterwards.
/// Every other field's slot is resolved at construction through [#of].
public record FieldSymbol(String name, String qualifiedName, SourceLocation location,
        TypeSymbol type, ConstantSlot constant, List<SyntaxKind> modifiers) implements Symbol {
    public FieldSymbol {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(qualifiedName, "qualifiedName");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(constant, "constant");
        modifiers = List.copyOf(modifiers);
    }

    /// A field whose constant value is already settled: `constantValue` is the folded value,
    /// or `null` for a field that is not a constant.
    public static FieldSymbol of(String name, String qualifiedName, SourceLocation location,
            TypeSymbol type, ConstantValue constantValue, List<SyntaxKind> modifiers) {
        return new FieldSymbol(name, qualifiedName, location, type,
                ConstantSlot.resolved(constantValue), modifiers);
    }

    /// The folded value of this constant, or `null` when it has none.
    public ConstantValue constantValue() {
        return constant.value();
    }

    public boolean isConstant() {
        return constant.value() != null;
    }

    @Override
    public SymbolKind kind() {
        return SymbolKind.FIELD;
    }
}
