package vsharp.compiler.ir;

/// One switch label. A null pattern denotes `default`; a guard remains an explicit condition.
public record IrSwitchLabel(IrPattern pattern, IrExpression guard) {}
