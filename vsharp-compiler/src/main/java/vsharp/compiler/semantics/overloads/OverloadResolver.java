package vsharp.compiler.semantics.overloads;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import vsharp.compiler.semantics.binding.BoundExpression;
import vsharp.compiler.semantics.binding.JavaInterop;
import vsharp.compiler.semantics.conversions.Conversion;
import vsharp.compiler.semantics.conversions.Conversions;
import vsharp.compiler.semantics.conversions.LambdaTargets;
import vsharp.compiler.semantics.symbols.FieldSymbol;
import vsharp.compiler.semantics.symbols.FunctionSymbol;
import vsharp.compiler.semantics.symbols.ParameterSymbol;
import vsharp.compiler.semantics.symbols.TypeParameterSymbol;
import vsharp.compiler.semantics.types.BuiltinType;
import vsharp.compiler.semantics.types.TypeSymbol;
import vsharp.compiler.syntax.AuxiliarySyntax;
import vsharp.compiler.syntax.ExpressionSyntax;
import vsharp.compiler.syntax.SyntaxKind;

/// Engine for function overload resolution and betterness ranking (C# §12.6.4).
public final class OverloadResolver {

    private OverloadResolver() {
    }

    /// Resolves the best candidate function among the given overload group candidates.
    public static OverloadResult resolve(
            List<FunctionSymbol> candidates,
            List<BoundExpression> boundArguments,
            List<AuxiliarySyntax.Argument> argumentSyntaxes) {
        return resolve(candidates, boundArguments, argumentSyntaxes, LambdaTargets.NONE);
    }

    /// Resolves the best candidate where an argument may still be an untyped lambda.
    ///
    /// A lambda is applicable to every parameter it could become, so a call that passes one
    /// can stay ambiguous between overloads that a typed argument would have separated. That
    /// is C#'s own behaviour: the anonymous-function conversion contributes no betterness
    /// beyond existing, and a genuinely ambiguous call is reported rather than guessed.
    public static OverloadResult resolve(
            List<FunctionSymbol> candidates,
            List<BoundExpression> boundArguments,
            List<AuxiliarySyntax.Argument> argumentSyntaxes,
            LambdaTargets lambdaTargets) {
        return resolve(candidates, boundArguments, argumentSyntaxes, lambdaTargets, List.of());
    }

    /// Resolves with the type arguments the call wrote. They replace inference for every
    /// candidate of matching arity, and the *declaration* each candidate remembers stays the
    /// generic one, so the call still emits against its erased JVM descriptor.
    public static OverloadResult resolve(
            List<FunctionSymbol> candidates,
            List<BoundExpression> boundArguments,
            List<AuxiliarySyntax.Argument> argumentSyntaxes,
            LambdaTargets lambdaTargets,
            List<TypeSymbol> writtenTypeArguments) {

        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(boundArguments, "boundArguments");
        Objects.requireNonNull(argumentSyntaxes, "argumentSyntaxes");
        Objects.requireNonNull(lambdaTargets, "lambdaTargets");
        Objects.requireNonNull(writtenTypeArguments, "writtenTypeArguments");

        List<Candidate> applicable = new ArrayList<>();
        OverloadResult.Failure bestFailure = null;

        for (FunctionSymbol function : candidates) {
            CandidateMatchResult match = matchCandidate(function, boundArguments,
                    argumentSyntaxes, lambdaTargets, writtenTypeArguments);
            if (match.candidate() != null) {
                applicable.add(match.candidate());
            } else if (match.failure() != null && bestFailure == null) {
                bestFailure = match.failure();
            }
        }

        if (applicable.isEmpty()) {
            return bestFailure != null
                    ? bestFailure
                    : new OverloadResult.Failure(
                            OverloadResult.FailureReason.NO_OVERLOAD_TAKES_N_ARGUMENTS,
                            0, "", "", "");
        }

        if (applicable.size() == 1) {
            return new OverloadResult.Success(applicable.getFirst());
        }

        // Filter for the best candidate(s) using betterness ranking
        List<Candidate> bestCandidates = new ArrayList<>();
        for (Candidate c1 : applicable) {
            boolean isBetterOrEqualThanAll = true;
            for (Candidate c2 : applicable) {
                if (c1 != c2 && isBetterCandidate(c2, c1, lambdaTargets)) {
                    isBetterOrEqualThanAll = false;
                    break;
                }
            }
            if (isBetterOrEqualThanAll) {
                bestCandidates.add(c1);
            }
        }

        if (bestCandidates.size() == 1) {
            return new OverloadResult.Success(bestCandidates.getFirst());
        }

        List<FunctionSymbol> ambiguous = bestCandidates.stream().map(Candidate::function).toList();
        return new OverloadResult.Ambiguous(ambiguous);
    }

    private record CandidateMatchResult(Candidate candidate, OverloadResult.Failure failure) {
    }

    /// A candidate is applicable in its *normal* form or, failing that, in its
    /// *expanded* form (C# 12.6.4.2). Both forms must be attempted: an argument
    /// count equal to the parameter count does not decide between them, because a
    /// single trailing element (`Concat("a")`, `Split(',')`) is expanded while a
    /// ready-made array (`Concat(parts)`) is not. Normal form wins when both apply.
    /// When neither applies the expanded diagnostic is reported, so the user sees
    /// the element type C# names rather than the array type.
    private static CandidateMatchResult matchCandidate(
            FunctionSymbol function,
            List<BoundExpression> boundArguments,
            List<AuxiliarySyntax.Argument> argumentSyntaxes,
            LambdaTargets lambdaTargets,
            List<TypeSymbol> writtenTypeArguments) {

        CandidateMatchResult normal = matchCandidateForm(function, boundArguments, argumentSyntaxes,
                false, lambdaTargets, writtenTypeArguments);
        if (normal.candidate() != null || !takesParams(function)) {
            return normal;
        }
        CandidateMatchResult expanded = matchCandidateForm(function, boundArguments,
                argumentSyntaxes, true, lambdaTargets, writtenTypeArguments);
        if (expanded.candidate() != null || expanded.failure() != null) {
            return expanded;
        }
        return normal;
    }

    /// Whether the declared (uninferred) function ends in a `params` parameter.
    private static boolean takesParams(FunctionSymbol function) {
        List<ParameterSymbol> parameters = function.parameters();
        return !parameters.isEmpty() && parameters.getLast().modifiers().contains(SyntaxKind.PARAMS);
    }

    private static CandidateMatchResult matchCandidateForm(
            FunctionSymbol function,
            List<BoundExpression> boundArguments,
            List<AuxiliarySyntax.Argument> argumentSyntaxes,
            boolean forceExpanded,
            LambdaTargets lambdaTargets,
            List<TypeSymbol> writtenTypeArguments) {

        FunctionSymbol declaration = function;
        if (!writtenTypeArguments.isEmpty()) {
            // The call named its type arguments, so there is nothing to infer: a candidate of a
            // different arity is not this call's method, and one whose bounds the arguments
            // violate is not applicable.
            if (function.typeParameters().size() != writtenTypeArguments.size()) {
                return new CandidateMatchResult(null, null);
            }
            FunctionSymbol written = GenericInference.withTypeArguments(function,
                    writtenTypeArguments);
            if (written == null) {
                Map<TypeParameterSymbol, TypeSymbol> bound = new java.util.LinkedHashMap<>();
                for (int index = 0; index < writtenTypeArguments.size(); index++) {
                    bound.put(function.typeParameters().get(index),
                            writtenTypeArguments.get(index));
                }
                GenericInference.ConstraintFailure violated =
                        GenericInference.violatedConstraint(bound);
                return violated == null
                        ? new CandidateMatchResult(null, null)
                        : new CandidateMatchResult(null, new OverloadResult.Failure(
                                OverloadResult.FailureReason.CONSTRAINT_VIOLATION, 0,
                                violated.parameter().constraintSpelling(),
                                violated.argument().displayName(),
                                violated.parameter().name()));
            }
            function = written;
        } else if (!function.typeParameters().isEmpty()) {
            // Inference must see the *element* type when the call is being considered in
            // expanded form, whoever declared the method: `Count("a", "b")` supplies `string`
            // three times to a `params T[]`, and matching those against the array itself
            // infers nothing, which failed the candidate with "no overload takes 3 arguments".
            // This was scoped to module-path declarations when the Java path needed it;
            // the restriction was never a policy, and C# §12.6.4 considers both forms of every
            // candidate alike.
            boolean inferExpandedParams = takesParams(function)
                    && argumentSyntaxes.stream().allMatch(argument -> argument.name() == null)
                    && (forceExpanded || boundArguments.size() > function.parameters().size());
            FunctionSymbol inferred = inferExpandedParams
                    ? GenericInference.inferExpandedParams(function, boundArguments, lambdaTargets)
                    : GenericInference.infer(function, boundArguments, lambdaTargets);
            if (inferred == null) {
                if (!JavaInterop.isModulePathSymbol(function)) {
                    // A `where` constraint the arguments do not meet is a statement about the
                    // program, not an inapplicable candidate, so it is reported by name rather
                    // than folded into "no overload takes N arguments".
                    GenericInference.ConstraintFailure violated = GenericInference
                            .constraintFailure(function, boundArguments, inferExpandedParams,
                                    lambdaTargets);
                    return violated == null
                            ? new CandidateMatchResult(null, null)
                            : new CandidateMatchResult(null, new OverloadResult.Failure(
                                    OverloadResult.FailureReason.CONSTRAINT_VIOLATION, 0,
                                    violated.parameter().constraintSpelling(),
                                    violated.argument().displayName(),
                                    violated.parameter().name()));
                }
                // Java raw arguments erase generic declarations when inference has no type
                // information to recover. Preserve that existing interop path without applying
                // it to V# generic functions, whose inference rules remain unchanged.
                inferred = GenericInference.erase(function);
            }
            function = inferred;
        }

        List<ParameterSymbol> parameters = function.parameters();
        int argCount = boundArguments.size();
        int paramCount = parameters.size();

        boolean hasParams = !parameters.isEmpty()
                && parameters.getLast().modifiers().contains(SyntaxKind.PARAMS);
        if (!forceExpanded && hasParams
                && javaParamsNormalNeedsExpansion(declaration, function)) {
            // `Arrays.asList(int[])` cannot pass `int[]` as the declaration's erased
            // `Object[]`. It is instead the expanded one-element call with T = int[], which
            // packs that array into Object[]. Reference arrays remain legal normal-form calls.
            return new CandidateMatchResult(null, null);
        }

        // Check positional + named argument mapping
        int[] argToParam = new int[argCount];
        Arrays.fill(argToParam, -1);

        boolean[] paramFilled = new boolean[paramCount];

        int positionalCount = 0;
        while (positionalCount < argCount && argumentSyntaxes.get(positionalCount).name() == null) {
            positionalCount++;
        }

        // Match positional arguments
        for (int i = 0; i < positionalCount; i++) {
            if (i < paramCount) {
                argToParam[i] = i;
                paramFilled[i] = true;
            } else if (!hasParams) {
                return new CandidateMatchResult(null, new OverloadResult.Failure(
                        OverloadResult.FailureReason.NO_OVERLOAD_TAKES_N_ARGUMENTS,
                        i, "", "", ""));
            }
        }

        // Match named arguments
        for (int i = positionalCount; i < argCount; i++) {
            AuxiliarySyntax.Argument argSyntax = argumentSyntaxes.get(i);
            if (argSyntax.name() != null) {
                int foundParam = -1;
                for (int p = 0; p < paramCount; p++) {
                    if (parameters.get(p).name().equals(argSyntax.name())) {
                        foundParam = p;
                        break;
                    }
                }
                if (foundParam == -1 || paramFilled[foundParam]) {
                    return new CandidateMatchResult(null, new OverloadResult.Failure(
                            OverloadResult.FailureReason.NO_OVERLOAD_TAKES_N_ARGUMENTS,
                            i, "", "", ""));
                }
                argToParam[i] = foundParam;
                paramFilled[foundParam] = true;
            } else {
                // Positional argument after named argument is invalid in C# 7.2+ unless order matches
                if (i < paramCount && !paramFilled[i]) {
                    argToParam[i] = i;
                    paramFilled[i] = true;
                } else if (!hasParams) {
                    return new CandidateMatchResult(null, new OverloadResult.Failure(
                            OverloadResult.FailureReason.NO_OVERLOAD_TAKES_N_ARGUMENTS,
                            i, "", "", ""));
                }
            }
        }

        // Check params expansion vs normal form
        boolean isExpanded = false;
        if (hasParams) {
            int lastParamIdx = paramCount - 1;
            // The params parameter may only be expanded over positional arguments;
            // supplying it by name selects the normal form and nothing else.
            boolean expandable = forceExpanded && positionalCount == argCount && argCount > lastParamIdx;
            if (expandable || !paramFilled[lastParamIdx] || (positionalCount > paramCount)) {
                isExpanded = true;
                for (int i = lastParamIdx; i < argCount; i++) {
                    if (argToParam[i] == -1) {
                        argToParam[i] = lastParamIdx;
                    }
                }
                paramFilled[lastParamIdx] = true;
            }
        }

        // Every unassigned fixed parameter must be optional. Keep the omitted parameters on
        // the candidate: the call site substitutes their declaration defaults after overload
        // resolution, just as C# passes default arguments implicitly (§12.6.2.3).
        List<ParameterSymbol> omittedParameters = new ArrayList<>();
        for (int p = 0; p < paramCount; p++) {
            if (!paramFilled[p]) {
                // If it's params, empty array is valid for expanded params
                if (p == paramCount - 1 && hasParams) {
                    isExpanded = true;
                    paramFilled[p] = true;
                } else if (parameters.get(p).defaultValue() != null) {
                    omittedParameters.add(parameters.get(p));
                } else {
                    return new CandidateMatchResult(null, new OverloadResult.Failure(
                            OverloadResult.FailureReason.NO_OVERLOAD_TAKES_N_ARGUMENTS,
                            p, "", "", ""));
                }
            }
        }

        // Verify conversions and argument modifiers
        List<Conversion> conversions = new ArrayList<>(argCount);

        for (int i = 0; i < argCount; i++) {
            BoundExpression argExpr = boundArguments.get(i);
            AuxiliarySyntax.Argument argSyntax = argumentSyntaxes.get(i);
            int paramIdx = argToParam[i];
            if (paramIdx < 0 || paramIdx >= paramCount) {
                return new CandidateMatchResult(null, new OverloadResult.Failure(
                        OverloadResult.FailureReason.NO_OVERLOAD_TAKES_N_ARGUMENTS,
                        i, "", "", ""));
            }

            ParameterSymbol param = parameters.get(paramIdx);
            TypeSymbol targetType = param.type();

            if (isExpanded && paramIdx == paramCount - 1 && targetType instanceof TypeSymbol.Array arr) {
                targetType = arr.elementType();
            }

            SyntaxKind paramMod = getParamModifier(param);
            SyntaxKind argMod = argSyntax.modifier();
            boolean inferredOutDeclaration = paramMod == SyntaxKind.OUT
                    && argMod == SyntaxKind.OUT
                    && argExpr.type() == TypeSymbol.Inferred.INSTANCE
                    && argSyntax.expression() instanceof ExpressionSyntax.Declaration;

            // Ref/Out/In modifier matching
            if (paramMod == SyntaxKind.REF) {
                if (argMod != SyntaxKind.REF) {
                    return new CandidateMatchResult(null, new OverloadResult.Failure(
                            OverloadResult.FailureReason.REF_OUT_MODIFIER_MISSING,
                            i + 1, targetType.displayName(), argExpr.type().displayName(), "ref"));
                }
                if (!isWritableVariable(argExpr)) {
                    return new CandidateMatchResult(null, new OverloadResult.Failure(
                            OverloadResult.FailureReason.NOT_VARIABLE,
                            i + 1, targetType.displayName(), argExpr.type().displayName(), "ref"));
                }
            } else if (paramMod == SyntaxKind.OUT) {
                if (argMod != SyntaxKind.OUT) {
                    return new CandidateMatchResult(null, new OverloadResult.Failure(
                            OverloadResult.FailureReason.REF_OUT_MODIFIER_MISSING,
                            i + 1, targetType.displayName(), argExpr.type().displayName(), "out"));
                }
                if (!isWritableVariable(argExpr) && !(argExpr instanceof BoundExpression.Deferred d && d.form().equals("declaration"))) {
                    return new CandidateMatchResult(null, new OverloadResult.Failure(
                            OverloadResult.FailureReason.NOT_VARIABLE,
                            i + 1, targetType.displayName(), argExpr.type().displayName(), "out"));
                }
            } else if (paramMod == SyntaxKind.IN) {
                if (argMod != null && argMod != SyntaxKind.IN) {
                    return new CandidateMatchResult(null, new OverloadResult.Failure(
                            OverloadResult.FailureReason.REF_OUT_MODIFIER_UNEXPECTED,
                            i + 1, targetType.displayName(), argExpr.type().displayName(),
                            argMod.display()));
                }
            } else { // No parameter modifier
                if (argMod != null) {
                    return new CandidateMatchResult(null, new OverloadResult.Failure(
                            OverloadResult.FailureReason.REF_OUT_MODIFIER_UNEXPECTED,
                            i + 1, targetType.displayName(), argExpr.type().displayName(),
                            argMod.display()));
                }
            }

            Conversion conv = inferredOutDeclaration
                    ? Conversion.IDENTITY
                    : Conversions.classify(argExpr, targetType, lambdaTargets);
            // `ref` and explicitly typed `out` arguments are aliases, not values passed
            // through a conversion: their type must be identical to the parameter type.
            // `out var` is the one typeless form; every candidate may infer its own target
            // here, and the selected candidate fixes the local's effective type later.
            boolean conversionAccepted = inferredOutDeclaration
                    || ((paramMod == SyntaxKind.REF || paramMod == SyntaxKind.OUT)
                            ? conv.isIdentity() : conv.isImplicit());
            if (!conversionAccepted) {
                return new CandidateMatchResult(null, new OverloadResult.Failure(
                        OverloadResult.FailureReason.CANNOT_CONVERT_ARGUMENT,
                        i + 1, targetType.displayName(), argExpr.type().displayName(), ""));
            }
            conversions.add(conv);
        }

        List<Integer> argumentParameterOrdinals = Arrays.stream(argToParam).boxed().toList();
        Candidate candidate = new Candidate(function, declaration, boundArguments, conversions,
                argumentParameterOrdinals, omittedParameters, isExpanded);
        return new CandidateMatchResult(candidate, null);
    }

    private static boolean javaParamsNormalNeedsExpansion(FunctionSymbol declaration,
            FunctionSymbol specialization) {
        if (!JavaInterop.isModulePathSymbol(declaration)
                || declaration.parameters().isEmpty()
                || specialization.parameters().isEmpty()) {
            return false;
        }
        TypeSymbol declared = declaration.parameters().getLast().type();
        TypeSymbol specialized = specialization.parameters().getLast().type();
        return declared instanceof TypeSymbol.Array declaredArray
                && declaredArray.elementType() instanceof TypeParameterSymbol
                && specialized instanceof TypeSymbol.Array specializedArray
                && specializedArray.elementType().jvmTypeKind()
                        != vsharp.compiler.semantics.types.JvmTypeKind.REFERENCE;
    }

    private static SyntaxKind getParamModifier(ParameterSymbol param) {
        for (SyntaxKind mod : param.modifiers()) {
            if (mod == SyntaxKind.REF || mod == SyntaxKind.OUT || mod == SyntaxKind.IN) {
                return mod;
            }
        }
        return null;
    }

    private static boolean isWritableVariable(BoundExpression expr) {
        if (expr instanceof BoundExpression.Value val
                && (val.symbol().kind() == vsharp.compiler.semantics.symbols.SymbolKind.LOCAL
                        || val.symbol().kind() == vsharp.compiler.semantics.symbols.SymbolKind.PARAMETER
                        || val.symbol().kind() == vsharp.compiler.semantics.symbols.SymbolKind.FIELD)) {
            return true;
        }
        if (expr instanceof BoundExpression.MemberAccess member
                && member.member() instanceof FieldSymbol field) {
            return !field.isConstant() && !field.modifiers().contains(SyntaxKind.READONLY);
        }
        // Array elements are writable places at any index count; the backend descends the
        // JVM rank chain for rectangular elements.
        return expr instanceof BoundExpression.ElementAccess;
    }

    /// Determines if candidate c1 is strictly better than candidate c2 according to C# §12.6.4.2.
    public static boolean isBetterCandidate(Candidate c1, Candidate c2) {
        return isBetterCandidate(c1, c2, LambdaTargets.NONE);
    }

    /// The same ranking with the lambda seam, which adds C# §12.6.4.4's rule for an argument
    /// that is an anonymous function.
    public static boolean isBetterCandidate(Candidate c1, Candidate c2,
            LambdaTargets lambdaTargets) {
        int argCount = Math.min(c1.boundArguments().size(), c2.boundArguments().size());

        boolean c1BetterInAtLeastOne = false;
        boolean c2BetterInAtLeastOne = false;

        for (int i = 0; i < argCount; i++) {
            BoundExpression arg = c1.boundArguments().get(i);
            TypeSymbol t1 = c1.function().parameters().get(
                    c1.argumentParameterOrdinals().get(i)).type();
            TypeSymbol t2 = c2.function().parameters().get(
                    c2.argumentParameterOrdinals().get(i)).type();
            Conversion conv1 = c1.argumentConversions().get(i);
            Conversion conv2 = c2.argumentConversions().get(i);

            int comp = compareConversions(arg.type(), t1, conv1, t2, conv2);
            if (comp > 0) {
                c1BetterInAtLeastOne = true;
            } else if (comp < 0) {
                c2BetterInAtLeastOne = true;
            }
        }

        if (c1BetterInAtLeastOne && !c2BetterInAtLeastOne) {
            return true;
        }
        if (c2BetterInAtLeastOne && !c1BetterInAtLeastOne) {
            return false;
        }

        // After inference, `of(T)` and `of(T[])` can both become `of(object[])` for an array
        // argument. Java selects the structurally more specific declaration (`T[]`); looking
        // only at the substituted parameter types makes them spuriously ambiguous.
        int declarationSpecificity = compareGenericDeclarationSpecificity(c1, c2);
        if (declarationSpecificity != 0) {
            return declarationSpecificity > 0;
        }

        // If conversions are equivalent, non-expanded params form is better than expanded form
        if (!c1.expandedParams() && c2.expandedParams()) {
            return true;
        }

        // C# §12.6.4.4: for an argument that is an anonymous function producing a value, a
        // target whose method returns something is better than one returning nothing. The JDK
        // relies on it - `ExecutorService.Submit` declares `Callable<T>` beside `Runnable` and
        // expects `() => value` to choose the first.
        int lambdaReturns = compareLambdaTargetReturns(c1, c2, lambdaTargets);
        if (lambdaReturns != 0) {
            return lambdaReturns > 0;
        }

        // C# §12.6.4.3: when conversions otherwise tie, a member for which every parameter
        // has a supplied argument beats one that needs at least one optional default.
        if (c1.omittedParameters().isEmpty() && !c2.omittedParameters().isEmpty()) {
            return true;
        }

        return false;
    }

    /// Compares two candidates by the return of the functional target each gives a lambda
    /// argument: value-returning beats void, and a candidate is better only when no lambda
    /// argument prefers the other.
    private static int compareLambdaTargetReturns(Candidate first, Candidate second,
            LambdaTargets lambdaTargets) {
        boolean firstBetter = false;
        boolean secondBetter = false;
        int count = Math.min(first.boundArguments().size(), second.boundArguments().size());
        for (int index = 0; index < count; index++) {
            if (!Conversions.isUnboundLambda(first.boundArguments().get(index))) {
                continue;
            }
            TypeSymbol firstTarget = first.function().parameters().get(
                    first.argumentParameterOrdinals().get(index)).type();
            TypeSymbol secondTarget = second.function().parameters().get(
                    second.argumentParameterOrdinals().get(index)).type();
            boolean firstVoid = lambdaTargets.returnsVoid(firstTarget);
            boolean secondVoid = lambdaTargets.returnsVoid(secondTarget);
            if (firstVoid != secondVoid) {
                firstBetter |= !firstVoid;
                secondBetter |= !secondVoid;
            }
        }
        if (firstBetter == secondBetter) {
            return 0;
        }
        return firstBetter ? 1 : -1;
    }

    private static int compareGenericDeclarationSpecificity(Candidate first, Candidate second) {
        if (!JavaInterop.isModulePathSymbol(first.declaration())
                || !JavaInterop.isModulePathSymbol(second.declaration())) {
            return 0;
        }
        boolean firstBetter = false;
        boolean secondBetter = false;
        int count = Math.min(first.boundArguments().size(), second.boundArguments().size());
        for (int index = 0; index < count; index++) {
            TypeSymbol firstDeclared = first.declaration().parameters().get(
                    first.argumentParameterOrdinals().get(index)).type();
            TypeSymbol secondDeclared = second.declaration().parameters().get(
                    second.argumentParameterOrdinals().get(index)).type();
            boolean firstDirect = firstDeclared instanceof TypeParameterSymbol;
            boolean secondDirect = secondDeclared instanceof TypeParameterSymbol;
            if (firstDirect != secondDirect) {
                firstBetter |= !firstDirect;
                secondBetter |= !secondDirect;
            }
        }
        if (firstBetter == secondBetter) {
            return 0;
        }
        return firstBetter ? 1 : -1;
    }

    /// Whether `signed` is the better conversion target of the two by C# §12.6.4.6's
    /// signed/unsigned table. The rule is one-directional and exhaustive: a signed type
    /// wins over every unsigned type at least as wide as itself, and no other pairing is
    /// decided here.
    private static boolean signedPreferred(TypeSymbol signed, TypeSymbol unsigned) {
        if (!(signed instanceof BuiltinType s) || !(unsigned instanceof BuiltinType u)) {
            return false;
        }
        return switch (s) {
            case SBYTE -> u == BuiltinType.BYTE || u == BuiltinType.USHORT
                    || u == BuiltinType.UINT || u == BuiltinType.ULONG || u == BuiltinType.NUINT;
            case SHORT -> u == BuiltinType.USHORT || u == BuiltinType.UINT
                    || u == BuiltinType.ULONG || u == BuiltinType.NUINT;
            case INT -> u == BuiltinType.UINT || u == BuiltinType.ULONG || u == BuiltinType.NUINT;
            case LONG -> u == BuiltinType.ULONG || u == BuiltinType.NUINT;
            case NINT -> u == BuiltinType.UINT || u == BuiltinType.ULONG || u == BuiltinType.NUINT;
            default -> false;
        };
    }

    /// Compares two target types T1 and T2 for argument type A.
    /// Returns >0 if T1 is a better conversion target than T2, <0 if T2 is better, 0 if equal.
    private static int compareConversions(
            TypeSymbol argType,
            TypeSymbol t1,
            Conversion conv1,
            TypeSymbol t2,
            Conversion conv2) {

        if (t1.equals(t2)) {
            return 0;
        }

        // An `out var` declaration has no argument type from which one candidate can be a
        // better conversion than another. C# reports M(out int)/M(out long) as ambiguous;
        // considering the implicit int-to-long conversion here would incorrectly pick int.
        if (argType == TypeSymbol.Inferred.INSTANCE) {
            return 0;
        }

        // Exact match is best
        if (argType.equals(t1) && !argType.equals(t2)) {
            return 1;
        }
        if (argType.equals(t2) && !argType.equals(t1)) {
            return -1;
        }

        // Better conversion target (C# §12.6.4.3):
        // An implicit conversion from T1 to T2 means T1 is more specific (better) than T2.
        Conversion c12 = Conversions.classify(t1, t2);
        Conversion c21 = Conversions.classify(t2, t1);

        if (c12.isImplicit() && !c21.isImplicit()) {
            return 1;
        }
        if (c21.isImplicit() && !c12.isImplicit()) {
            return -1;
        }

        // C# §12.6.4.6 closes the signed/unsigned tie that no conversion decides: neither
        // `int` nor `uint` converts implicitly to the other, so without this table a
        // `ushort` argument would find `F(int)` and `F(uint)` equally good and the call
        // would be reported ambiguous where C# picks the signed one.
        if (signedPreferred(t1, t2)) {
            return 1;
        }
        if (signedPreferred(t2, t1)) {
            return -1;
        }

        return 0;
    }
}
