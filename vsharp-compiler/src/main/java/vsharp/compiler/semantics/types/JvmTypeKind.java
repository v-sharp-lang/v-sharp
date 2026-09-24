package vsharp.compiler.semantics.types;

/// The verifier-level carrier used by a semantic type.
///
/// This is intentionally coarser than [TypeSymbol]: signed and unsigned integers share a
/// JVM carrier even though their conversions and operators remain different in V#.
public enum JvmTypeKind {
    BOOLEAN,
    BYTE,
    SHORT,
    CHAR,
    INT,
    LONG,
    FLOAT,
    DOUBLE,
    REFERENCE,
    VOID
}
