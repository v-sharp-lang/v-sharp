package vsharp.compiler.ir;

/// Unary operations after syntax has been removed from the lowering contract.
public enum IrUnaryOperator {
    IDENTITY,
    NEGATE,
    LOGICAL_NOT,
    BITWISE_NOT,
    INDEX_FROM_END
}
