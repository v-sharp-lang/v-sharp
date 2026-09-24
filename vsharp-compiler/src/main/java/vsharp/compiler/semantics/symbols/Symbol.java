package vsharp.compiler.semantics.symbols;

/// An immutable declaration identity produced by semantic binding.
public sealed interface Symbol
        permits NamespaceSymbol, ContainerSymbol, NamedTypeSymbol, FunctionSymbol,
                TypeParameterSymbol, ParameterSymbol, FieldSymbol, LocalSymbol,
                EnumMemberSymbol {

    String name();

    String qualifiedName();

    SourceLocation location();

    SymbolKind kind();
}
