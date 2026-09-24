package vsharp.compiler.semantics.overloads;

import java.util.List;
import java.util.Objects;
import vsharp.compiler.semantics.binding.BoundExpression;
import vsharp.compiler.semantics.conversions.Conversion;
import vsharp.compiler.semantics.symbols.FunctionSymbol;
import vsharp.compiler.semantics.symbols.ParameterSymbol;

/// A function candidate evaluated during overload resolution.
public record Candidate(
        FunctionSymbol function,
        FunctionSymbol declaration,
        List<BoundExpression> boundArguments,
        List<Conversion> argumentConversions,
        List<Integer> argumentParameterOrdinals,
        List<ParameterSymbol> omittedParameters,
        boolean expandedParams) {

    public Candidate {
        Objects.requireNonNull(function, "function");
        Objects.requireNonNull(declaration, "declaration");
        boundArguments = List.copyOf(boundArguments);
        argumentConversions = List.copyOf(argumentConversions);
        argumentParameterOrdinals = List.copyOf(argumentParameterOrdinals);
        omittedParameters = List.copyOf(omittedParameters);
        if (boundArguments.size() != argumentConversions.size()
                || boundArguments.size() != argumentParameterOrdinals.size()) {
            throw new IllegalArgumentException(
                    "arguments, conversions and parameter ordinals must have equal sizes");
        }
    }
}
