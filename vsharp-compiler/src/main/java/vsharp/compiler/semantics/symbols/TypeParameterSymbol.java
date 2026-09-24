package vsharp.compiler.semantics.symbols;

import java.util.Objects;
import vsharp.compiler.semantics.types.BuiltinType;
import vsharp.compiler.semantics.types.JvmTypeKind;
import vsharp.compiler.semantics.types.TypeSymbol;

/// A generic type or function parameter.
///
/// `bound` is the *erasure* of the declared bound: the single JVM carrier every position
/// holding this parameter takes. A V# declaration always bounds by `object`, and so
/// does an unbounded Java declaration; `<E extends Enum<E>>` carries `java.lang.Enum`, which
/// is what `java.lang.Enum.valueOf`'s descriptor returns. Recording it on the symbol is what
/// lets a bare type-parameter carrier stay on the emission path at all - erasure, descriptor
/// building and constraint checking all read this one value, so no stage can invent `Object`
/// for a position the JVM declares narrower. Only the raw bound is kept (never a constructed
/// one), because an F-bounded `E extends Enum<E>` would otherwise be cyclic.
/// `constraintType` is the named type a `where` clause demands the argument convert to, or
/// `null`. It is kept beside `bound` rather than inside it: `bound` is the erasure every
/// position holding this parameter uses, and a V# declaration always erases to `object`
/// however it is constrained, so storing a constraint there would change emitted
/// descriptors.
///
/// `valueKind` is the `where` clause's value-kind constraint, which the erased `bound` cannot
/// express: `struct` and `class` both erase to `Object` and are nonetheless different
/// contracts, so a program using one has to be checked against it rather than against the
/// carrier.
public record TypeParameterSymbol(String name, String qualifiedName, SourceLocation location,
        int ordinal, TypeSymbol bound, TypeParameterSymbol.ValueKind valueKind,
        TypeSymbol constraintType)
        implements Symbol, TypeSymbol {

    /// The value-kind a `where` clause demands of a type argument.
    public enum ValueKind {
        /// No `struct` or `class` constraint: any type argument is admissible.
        ANY,
        /// `where T : struct` - a non-nullable value type (CS0453).
        VALUE,
        /// `where T : class` - a reference type (CS0452).
        REFERENCE
    }

    public TypeParameterSymbol {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(qualifiedName, "qualifiedName");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(bound, "bound");
        Objects.requireNonNull(valueKind, "valueKind");
        if (ordinal < 0) {
            throw new IllegalArgumentException("ordinal must not be negative");
        }
    }

    /// A parameter with a declared bound and no value-kind constraint.
    public TypeParameterSymbol(String name, String qualifiedName, SourceLocation location,
            int ordinal, TypeSymbol bound) {
        this(name, qualifiedName, location, ordinal, bound, ValueKind.ANY, null);
    }

    /// A parameter bounded by `object`: every V# declaration, and every Java declaration
    /// whose bound erases to `Object`.
    public TypeParameterSymbol(String name, String qualifiedName, SourceLocation location,
            int ordinal) {
        this(name, qualifiedName, location, ordinal, BuiltinType.OBJECT, ValueKind.ANY, null);
    }

    /// Whether `argument` satisfies this parameter's value-kind constraint.
    ///
    /// A nullable value type is deliberately *not* a `struct` argument, exactly as in C#:
    /// `Nullable<T>` is a value type but `where T : struct` means non-nullable, which is what
    /// makes `T?` meaningful inside such a declaration in the first place.
    public boolean admits(TypeSymbol argument) {
        Objects.requireNonNull(argument, "argument");
        return switch (valueKind) {
            case ANY -> true;
            case VALUE -> argument.isValueType() && !(argument instanceof TypeSymbol.Nullable);
            case REFERENCE -> !argument.isValueType();
        };
    }

    /// The `where` clause spelling this parameter demands, for diagnostics.
    public String constraintSpelling() {
        return switch (valueKind) {
            case ANY -> constraintType == null ? "" : constraintType.displayName();
            case VALUE -> "struct";
            case REFERENCE -> "class";
        };
    }

    @Override
    public SymbolKind kind() {
        return SymbolKind.TYPE_PARAMETER;
    }

    @Override
    public String displayName() {
        return name;
    }

    @Override
    public JvmTypeKind jvmTypeKind() {
        return JvmTypeKind.REFERENCE;
    }

    @Override
    public boolean isValueType() {
        return false;
    }
}
