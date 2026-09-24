package vsharp.compiler.ir;

import java.util.List;

/// Consecutive labels and their lowered statements, preserving source order.
public record IrSwitchSection(List<IrSwitchLabel> labels, List<IrStatement> statements) {
    public IrSwitchSection {
        labels = List.copyOf(labels);
        statements = List.copyOf(statements);
        if (labels.isEmpty()) {
            throw new IllegalArgumentException("switch section needs a label");
        }
    }
}
