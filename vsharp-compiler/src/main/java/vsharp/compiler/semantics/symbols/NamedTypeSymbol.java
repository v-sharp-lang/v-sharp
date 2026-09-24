package vsharp.compiler.semantics.symbols;

import java.util.Objects;
import java.util.Set;
import vsharp.compiler.semantics.types.BuiltinType;
import vsharp.compiler.semantics.types.JvmTypeKind;
import vsharp.compiler.semantics.types.TypeSymbol;

/// A declared enum, struct or record struct, or a type discovered on the Java module path.
///
/// `javaSupertypes` holds the transitive closure of the superclasses and interfaces a
/// module-path type is assignable to, by qualified name, and is empty for every V#
/// declaration. Carrying it on the symbol is what lets the conversion engine answer a
/// Java reference conversion without a resolver: the closure is a fact about the class file,
/// fixed the moment the type is discovered, so it belongs to the symbol just as its arity
/// does.
/// `keywordSubtypes` is that closure's dual, and exists because a keyword type has no symbol
/// to carry a hierarchy on: `string` *is* `java.lang.String`, which implements
/// `java.lang.CharSequence`, but `string` is a [BuiltinType] and the conversion engine is a
/// pure function of its arguments with no resolver to ask. Recording on `CharSequence` that
/// `string` is assignable to it answers the same question from the side that does have a
/// symbol, and leaves `Conversions` unchanged in kind.
public record NamedTypeSymbol(String name, String qualifiedName, SourceLocation location,
        DeclaredKind declaredKind, int arity, Set<String> javaSupertypes,
        Set<BuiltinType> keywordSubtypes) implements Symbol, TypeSymbol {

    /// A type with no supertype closure: every V# declaration, which has no JVM hierarchy V#
    /// can observe, and any caller that does not describe one.
    public NamedTypeSymbol(String name, String qualifiedName, SourceLocation location,
            DeclaredKind declaredKind, int arity) {
        this(name, qualifiedName, location, declaredKind, arity, Set.of(), Set.of());
    }

    public NamedTypeSymbol(String name, String qualifiedName, SourceLocation location,
            DeclaredKind declaredKind, int arity, Set<String> javaSupertypes) {
        this(name, qualifiedName, location, declaredKind, arity, javaSupertypes, Set.of());
    }

    public enum DeclaredKind {
        ENUM,
        STRUCT,
        RECORD_STRUCT,
        CLASS,
        INTERFACE
    }

    public NamedTypeSymbol {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(qualifiedName, "qualifiedName");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(declaredKind, "declaredKind");
        if (arity < 0) {
            throw new IllegalArgumentException("arity must not be negative");
        }
        javaSupertypes = Set.copyOf(javaSupertypes);
        keywordSubtypes = Set.copyOf(keywordSubtypes);
    }

    @Override
    public SymbolKind kind() {
        return SymbolKind.NAMED_TYPE;
    }

    @Override
    public String displayName() {
        return qualifiedName;
    }

    /// An enum has no run-time class: C# defines it as its underlying type with named
    /// constants, and V# carries it as the `int` that underlying type defaults to. Every other
    /// named type is a reference to its own class.
    @Override
    public JvmTypeKind jvmTypeKind() {
        return declaredKind == DeclaredKind.ENUM ? JvmTypeKind.INT : JvmTypeKind.REFERENCE;
    }

    @Override
    public boolean isValueType() {
        return declaredKind == DeclaredKind.STRUCT 
            || declaredKind == DeclaredKind.RECORD_STRUCT 
            || declaredKind == DeclaredKind.ENUM;
    }
}
