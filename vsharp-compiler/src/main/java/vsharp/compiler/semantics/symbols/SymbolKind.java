package vsharp.compiler.semantics.symbols;

/// Declaration categories represented by the symbol model.
public enum SymbolKind {
    NAMESPACE,
    STATIC_CONTAINER,
    NAMED_TYPE,
    FUNCTION,
    TYPE_PARAMETER,
    PARAMETER,
    FIELD,
    LOCAL,
    ENUM_MEMBER
}
