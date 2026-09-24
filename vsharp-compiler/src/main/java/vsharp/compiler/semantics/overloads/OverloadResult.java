package vsharp.compiler.semantics.overloads;

import java.util.List;
import java.util.Objects;
import vsharp.compiler.semantics.symbols.FunctionSymbol;

/// Outcome of attempting overload resolution on a set of function candidates.
public sealed interface OverloadResult
        permits OverloadResult.Success, OverloadResult.Ambiguous, OverloadResult.Failure {

    record Success(Candidate candidate) implements OverloadResult {
        public Success {
            Objects.requireNonNull(candidate, "candidate");
        }
    }

    record Ambiguous(List<FunctionSymbol> candidates) implements OverloadResult {
        public Ambiguous {
            candidates = List.copyOf(candidates);
        }
    }

    enum FailureReason {
        NO_OVERLOAD_TAKES_N_ARGUMENTS,
        CANNOT_CONVERT_ARGUMENT,
        REF_OUT_MODIFIER_MISSING,
        REF_OUT_MODIFIER_UNEXPECTED,
        NOT_VARIABLE,
        /// A type argument does not meet its parameter's `where` constraint. The
        /// failure carries the offending type in `actualType`, the constraint's spelling in
        /// `expectedType`, and the type parameter's name in `modifier`.
        CONSTRAINT_VIOLATION
    }

    record Failure(FailureReason reason, int argumentIndex, String expectedType, String actualType, String modifier)
            implements OverloadResult {
    }
}
