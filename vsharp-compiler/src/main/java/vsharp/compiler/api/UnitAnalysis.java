package vsharp.compiler.api;

import java.util.Objects;
import vsharp.compiler.semantics.binding.ExpressionBinding;
import vsharp.compiler.semantics.binding.SemanticModel;
import vsharp.compiler.semantics.flow.FlowResult;
import vsharp.compiler.source.SourceFile;
import vsharp.compiler.syntax.AuxiliarySyntax;

/// Everything the compiler knows about one source file after a [Compilation] run.
///
/// The shape records how far the run got: a phase that never executed has no
/// half-populated placeholder, so consumers pattern-match instead of null-checking.
/// Every unit of one result has the same shape, because phases are gated globally.
public sealed interface UnitAnalysis {

    /// The file this analysis describes.
    SourceFile file();

    /// The parsed compilation unit; present in every shape because parsing always runs.
    AuxiliarySyntax.CompilationUnit syntax();

    /// Only parsing ran, or parsing reported errors.
    record Parsed(SourceFile file, AuxiliarySyntax.CompilationUnit syntax)
            implements UnitAnalysis {

        public Parsed {
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(syntax, "syntax");
        }
    }

    /// Declarations and scopes exist; expressions were not bound.
    record Declared(SourceFile file, AuxiliarySyntax.CompilationUnit syntax, SemanticModel model)
            implements UnitAnalysis {

        public Declared {
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(syntax, "syntax");
            Objects.requireNonNull(model, "model");
        }
    }

    /// Expressions are bound; flow analysis did not run.
    record Bound(SourceFile file, AuxiliarySyntax.CompilationUnit syntax, SemanticModel model,
            ExpressionBinding expressions) implements UnitAnalysis {

        public Bound {
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(syntax, "syntax");
            Objects.requireNonNull(model, "model");
            Objects.requireNonNull(expressions, "expressions");
        }
    }


    /// The complete front end ran: this is what lowering will consume.
    record Analysed(SourceFile file, AuxiliarySyntax.CompilationUnit syntax, SemanticModel model,
            ExpressionBinding expressions, FlowResult flow) implements UnitAnalysis {

        public Analysed {
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(syntax, "syntax");
            Objects.requireNonNull(model, "model");
            Objects.requireNonNull(expressions, "expressions");
            Objects.requireNonNull(flow, "flow");
        }
    }

    /// The backend ran, generating IR and JVM bytecode.
    record Emitted(SourceFile file, AuxiliarySyntax.CompilationUnit syntax, SemanticModel model,
            ExpressionBinding expressions, FlowResult flow, vsharp.compiler.ir.IrUnit ir, java.util.Map<String, byte[]> classes) implements UnitAnalysis {

        public Emitted {
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(syntax, "syntax");
            Objects.requireNonNull(model, "model");
            Objects.requireNonNull(expressions, "expressions");
            Objects.requireNonNull(flow, "flow");
            Objects.requireNonNull(ir, "ir");
            Objects.requireNonNull(classes, "classes");
        }
    }
}

