package vsharp.compiler.ir;

/// Type-directed operations whose operand is a type, not a run-time expression.
public enum IrTypeOperatorKind {
    SIZEOF,
    TYPEOF
}
