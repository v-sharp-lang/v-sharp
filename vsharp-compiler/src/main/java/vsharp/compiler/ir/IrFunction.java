package vsharp.compiler.ir;

import java.util.Objects;
import vsharp.compiler.semantics.symbols.FunctionSymbol;

/// One callable in a lowered compilation unit.
public sealed interface IrFunction
        permits IrFunction.Implemented, IrFunction.External, IrFunction.AsyncEntry {

    FunctionSymbol symbol();

    /// A source callable with a body. `appendImplicitReturn` is true only for a void callable
    /// whose end point FlowAnalysis found reachable; bytecode emission appends the return after
    /// allocating labels and local slots.
    record Implemented(FunctionSymbol symbol, IrStatement.Block body, boolean appendImplicitReturn,
            java.util.List<IrCapture> captures, java.util.List<IrAnnotation> annotations)
            implements IrFunction {
        public Implemented(FunctionSymbol symbol, IrStatement.Block body,
                boolean appendImplicitReturn, java.util.List<IrCapture> captures) {
            this(symbol, body, appendImplicitReturn, captures, java.util.List.of());
        }

        public Implemented {
            Objects.requireNonNull(symbol, "symbol");
            Objects.requireNonNull(body, "body");
            captures = java.util.List.copyOf(captures);
            annotations = java.util.List.copyOf(annotations);
        }
    }

    record IrAnnotation(String className) {
        public IrAnnotation {
            Objects.requireNonNull(className, "className");
        }
    }

    /// The caller-facing half of an `async` callable.
    ///
    /// An `async` declaration becomes two methods. `body` is an ordinary private callable
    /// holding the written statements and returning the task's *result* type; this entry keeps
    /// the declared name, parameters and `Task` return type, and its whole implementation is
    /// to start `body` on a task and hand that task back. Splitting at the IR boundary is what
    /// keeps every earlier stage - binding, flow analysis, statement lowering - unaware that
    /// `async` exists: they see a normal callable, because that is what the body is.
    ///
    /// @param symbol the declared callable, returning `Task` or `Task<T>`
    /// @param body the synthesized callable holding the written body
    record AsyncEntry(FunctionSymbol symbol, FunctionSymbol body) implements IrFunction {
        public AsyncEntry {
            Objects.requireNonNull(symbol, "symbol");
            Objects.requireNonNull(body, "body");
        }
    }

    /// An extern declaration has a signature but no emitted body in this compilation.
    record External(FunctionSymbol symbol) implements IrFunction {
        public External { Objects.requireNonNull(symbol, "symbol"); }
    }
}
