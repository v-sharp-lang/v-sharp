package vsharp.compiler.semantics.overloads;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import vsharp.compiler.semantics.binding.BoundExpression;
import vsharp.compiler.semantics.conversions.Conversions;
import vsharp.compiler.semantics.conversions.LambdaTargets;
import vsharp.compiler.semantics.symbols.FunctionSymbol;
import vsharp.compiler.semantics.symbols.ParameterSymbol;
import vsharp.compiler.semantics.symbols.TypeParameterSymbol;
import vsharp.compiler.semantics.types.BuiltinType;
import vsharp.compiler.semantics.types.TypeSymbol;

/// Implements generic method type inference for method invocations.
public final class GenericInference {

    private GenericInference() {
        throw new AssertionError("No instances");
    }

    /// Infers type arguments for a generic function given call arguments.
    /// Returns a specialized FunctionSymbol if type inference succeeds, or null if it fails.
    public static FunctionSymbol infer(FunctionSymbol function, List<BoundExpression> arguments) {
        return infer(function, arguments, false, LambdaTargets.NONE);
    }

    /// Infers with the lambda seam, so an explicitly typed lambda can close a type parameter
    /// that appears only in the target's parameter position.
    public static FunctionSymbol infer(FunctionSymbol function, List<BoundExpression> arguments,
            LambdaTargets lambdas) {
        return infer(function, arguments, false, lambdas);
    }

    /// Infers a generic expanded-params call from the loose argument element types. The fixed
    /// parameters retain ordinary matching; every argument at the trailing params ordinal is
    /// matched against the array element. Overload resolution invokes this only for Java
    /// module-path declarations, leaving V#'s inference policy unchanged.
    public static FunctionSymbol inferExpandedParams(FunctionSymbol function,
            List<BoundExpression> arguments) {
        return inferExpandedParams(function, arguments, LambdaTargets.NONE);
    }

    /// The expanded-params form with the lambda seam.
    public static FunctionSymbol inferExpandedParams(FunctionSymbol function,
            List<BoundExpression> arguments, LambdaTargets lambdas) {
        return infer(function, arguments, true, lambdas);
    }

    /// The constraint an inferred call would violate, or `null` when it violates none.
    ///
    /// Shares [#inferMap] with inference itself, so what is reported is exactly what was
    /// rejected; the resolver asks only on the failure path, where the cost does not matter.
    public static ConstraintFailure constraintFailure(FunctionSymbol function,
            List<BoundExpression> arguments, boolean expandedParams, LambdaTargets lambdas) {
        if (function.typeParameters().isEmpty()) {
            return null;
        }
        Map<TypeParameterSymbol, TypeSymbol> inferred =
                inferMap(function, arguments, expandedParams, lambdas);
        return inferred == null ? null : violatedConstraint(inferred);
    }

    private static FunctionSymbol infer(FunctionSymbol function,
            List<BoundExpression> arguments, boolean expandedParams, LambdaTargets lambdas) {
        if (function.typeParameters().isEmpty()) {
            return function;
        }
        Map<TypeParameterSymbol, TypeSymbol> inferred =
                inferMap(function, arguments, expandedParams, lambdas);
        if (inferred == null) {
            return null;
        }

        // A declared bound is a constraint, not decoration. Now that a bounded method
        // keeps its signature, inference can reach type arguments the declaration forbids -
        // `Enum.ValueOf` with a `T` that is no enum - and the call would emit against the
        // bound's erased descriptor and fault at run time with a `ClassCastException` the
        // compiler had promised away. Refusing the candidate here turns that into ordinary
        // overload failure, which is the deterministic diagnostic the acceptance gate demands.
        if (!satisfiesBounds(inferred)) {
            return null;
        }
        return specialize(function, inferred);
    }

    /// Matches the arguments against the parameters and returns the inferred arguments, or
    /// `null` when the shapes do not match or a required parameter stayed unknown. No
    /// constraint is applied here, which is what lets the caller decide whether an unmet one is
    /// an inapplicable candidate or a diagnosable program.
    private static Map<TypeParameterSymbol, TypeSymbol> inferMap(FunctionSymbol function,
            List<BoundExpression> arguments, boolean expandedParams, LambdaTargets lambdas) {
        Map<TypeParameterSymbol, TypeSymbol> inferred = new HashMap<>();
        List<ParameterSymbol> parameters = function.parameters();

        int fixedCount = expandedParams && !parameters.isEmpty()
                ? parameters.size() - 1 : parameters.size();
        int count = expandedParams ? arguments.size()
                : Math.min(arguments.size(), parameters.size());
        for (int i = 0; i < count; i++) {
            TypeSymbol paramType;
            if (i < fixedCount) {
                paramType = parameters.get(i).type();
            } else if (parameters.getLast().type() instanceof TypeSymbol.Array paramsArray) {
                paramType = paramsArray.elementType();
            } else {
                return null;
            }
            BoundExpression argument = arguments.get(i);
            if (Conversions.isUnboundLambda(argument)) {
                matchWrittenLambda(paramType, argument, function.typeParameters(), inferred,
                        lambdas);
                continue;
            }
            matchTypes(paramType, argument.type(), function.typeParameters(), inferred);
        }

        // Verify all type parameters were inferred. One is allowed to remain open: a
        // parameter that *only* a lambda could supply, because a lambda has no type until a
        // target types it and its target is the very parameter being inferred.
        // `Stream.map(Function<? super T, ? extends R>)` is the shape - `T` comes from the
        // receiver, but nothing except the lambda's own body can say what `R` is. Leaving it
        // as its type parameter lets binding fill it in once the body is bound; requiring it
        // here would send the call to the raw fallback, where every lambda parameter reads
        // `object`. A parameter that any ordinary argument mentions is still required, so
        // this never weakens inference for a call that could have been inferred.
        for (TypeParameterSymbol tp : function.typeParameters()) {
            if (!inferred.containsKey(tp) && !onlyLambdaCanSupply(tp, parameters, arguments,
                    fixedCount) && mentionedByAnyParameter(tp, parameters)) {
                return null;
            }
        }

        return inferred;
    }

    /// Applies inferred arguments to a declaration, producing the specialized symbol.
    private static FunctionSymbol specialize(FunctionSymbol function,
            Map<TypeParameterSymbol, TypeSymbol> inferred) {
        List<ParameterSymbol> parameters = function.parameters();
        List<ParameterSymbol> substitutedParams = parameters.stream()
                .map(p -> new ParameterSymbol(
                        p.name(),
                        p.qualifiedName(),
                        p.location(),
                        substitute(p.type(), inferred),
                        p.ordinal(),
                        p.modifiers(),
                        p.defaultValue()))
                .toList();

        TypeSymbol substitutedReturn = substitute(function.returnType(), inferred);

        return new FunctionSymbol(
                function.name(),
                function.qualifiedName(),
                function.location(),
                substitutedReturn,
                List.of(),
                substitutedParams,
                function.modifiers(),
                function.localFunction(),
                function.synthesized(),
                function.interfaceOwner());
    }

    /// Applies type arguments the source *wrote* to a generic function.
    ///
    /// C# lets a call name its type arguments (`Optional.Empty&lt;string&gt;()`), and for a method
    /// whose parameters mention none of them that is the only way to close it. The arguments
    /// are positional, so the count must match; a caller checks that before substituting.
    public static FunctionSymbol withTypeArguments(FunctionSymbol function,
            List<TypeSymbol> arguments) {
        Objects.requireNonNull(function, "function");
        Objects.requireNonNull(arguments, "arguments");
        if (function.typeParameters().size() != arguments.size()) {
            throw new IllegalArgumentException("type argument count must match the declaration");
        }
        Map<TypeParameterSymbol, TypeSymbol> bound = new HashMap<>();
        for (int index = 0; index < arguments.size(); index++) {
            bound.put(function.typeParameters().get(index), arguments.get(index));
        }
        if (!satisfiesBounds(bound)) {
            return null;
        }
        List<ParameterSymbol> parameters = function.parameters().stream()
                .map(parameter -> new ParameterSymbol(parameter.name(), parameter.qualifiedName(),
                        parameter.location(), substitute(parameter.type(), bound),
                        parameter.ordinal(), parameter.modifiers(), parameter.defaultValue()))
                .toList();
        return new FunctionSymbol(function.name(), function.qualifiedName(), function.location(),
                substitute(function.returnType(), bound), List.of(), parameters,
                function.modifiers(), function.localFunction(), function.synthesized(),
                function.interfaceOwner());
    }

    /// Whether every inferred type argument satisfies its parameter's declared bound.
    ///
    /// The recorded bound is the erasure of the leftmost declared one, and the question it
    /// asks - is this type assignable to that carrier - is exactly what the conversion engine
    /// already answers from the supertype closure each Java symbol carries, so no
    /// resolver and no separate subtyping model is needed. An unbounded parameter bounds by
    /// `object` and admits everything, which is every V# declaration's case; a value type is
    /// admitted because the argument reaching a generic position is boxed and V# does not yet
    /// model the boxed carrier's own hierarchy - a deliberate admission, never a rejection of
    /// a program javac accepts.
    /// The first `where` constraint the inferred arguments violate, or `null`.
    ///
    /// Kept separate from [#satisfiesBounds] because the two failures deserve different
    /// answers: an erased bound the argument misses makes the candidate inapplicable and the
    /// call falls through to ordinary overload failure, while a value-kind constraint is a
    /// statement about the program that C# names precisely (CS0453/CS0452) and V# should name
    /// too.
    public static ConstraintFailure violatedConstraint(
            Map<TypeParameterSymbol, TypeSymbol> inferred) {
        for (Map.Entry<TypeParameterSymbol, TypeSymbol> entry : inferred.entrySet()) {
            if (!entry.getKey().admits(entry.getValue())) {
                return new ConstraintFailure(entry.getKey(), entry.getValue());
            }
            if (!satisfiesConstraintType(entry.getKey(), entry.getValue())) {
                return new ConstraintFailure(entry.getKey(), entry.getValue());
            }
        }
        return null;
    }

    /// Whether a type argument meets the *named* type its parameter's `where` clause demands
    ///.
    ///
    /// The JVM hierarchy answers this for a reference argument: `javaSupertypes` carries the
    /// transitive closure including interfaces, and a keyword type reaches it from the
    /// side that carries it, so `string` satisfies `java.lang.Comparable` and `object`
    /// does not - which is CS0311 and CS0311 alone.
    ///
    /// Two argument shapes are admitted without being checked, because V# cannot decide them
    /// and refusing them would reject programs C# accepts. A *value type* has no boxed-interface
    /// surface here - `int` implements `Comparable` on the JVM through `Integer`, which V#'s
    /// conversion model does not reach - and a *type parameter* would need its own constraints
    /// compared, which is C#'s CS0314 and needs the same closure this one is missing. Both are
    /// recorded rather than silently assumed correct.
    private static boolean satisfiesConstraintType(TypeParameterSymbol parameter,
            TypeSymbol argument) {
        TypeSymbol required = parameter.constraintType();
        if (required == null || argument instanceof TypeParameterSymbol) {
            return true;
        }
        if (switch (Conversions.classify(argument, required).kind()) {
            case IDENTITY, IMPLICIT_REFERENCE, BOXING -> true;
            default -> false;
        }) {
            return true;
        }
        // The conversion says no, which is only an answer when the argument's hierarchy is one
        // V# can read. A keyword type without a modelled boxed carrier - the unsigned
        // family - is unanswerable, and admitting it is the only choice that does not refuse a
        // program C# accepts. A V# declared value type implements nothing on the JVM, so "no"
        // is a real answer for it.
        return argument instanceof BuiltinType keyword
                && !vsharp.compiler.semantics.binding.JavaInterop.hasKeywordCarrier(keyword);
    }

    /// A type argument that does not meet its parameter's value-kind constraint.
    public record ConstraintFailure(TypeParameterSymbol parameter, TypeSymbol argument) {
        public ConstraintFailure {
            Objects.requireNonNull(parameter, "parameter");
            Objects.requireNonNull(argument, "argument");
        }
    }

    private static boolean satisfiesBounds(Map<TypeParameterSymbol, TypeSymbol> inferred) {
        if (violatedConstraint(inferred) != null) {
            return false;
        }
        for (Map.Entry<TypeParameterSymbol, TypeSymbol> entry : inferred.entrySet()) {
            TypeSymbol bound = entry.getKey().bound();
            if (bound == BuiltinType.OBJECT) {
                continue;
            }
            TypeSymbol argument = entry.getValue();
            if (argument instanceof TypeSymbol.Constructed constructed) {
                argument = constructed.definition();
            }
            if (argument.equals(bound) || argument.isValueType()) {
                continue;
            }
            if (!Conversions.classify(argument, bound).isImplicit()) {
                return false;
            }
        }
        return true;
    }

    /// Whether any parameter position mentions `tp`, so an argument could have supplied it.
    ///
    /// When none does, only the call's target can: `Comparator.NaturalOrder()` and
    /// `Collections.EmptyList()` take no arguments at all, and their type argument is
    /// readable only from what the result is being converted to. Such a parameter stays
    /// open for [#closeAgainstTarget] exactly as a lambda-only one stays open for the body
    ///; the alternative is the raw fallback, which is what made `Comparator<string>`
    /// unreachable from V# source.
    private static boolean mentionedByAnyParameter(TypeParameterSymbol tp,
            List<ParameterSymbol> parameters) {
        for (ParameterSymbol parameter : parameters) {
            if (mentions(parameter.type(), tp)) {
                return true;
            }
        }
        return false;
    }

    /// Whether every parameter mentioning `tp` was given an as-yet-untyped lambda.
    private static boolean onlyLambdaCanSupply(TypeParameterSymbol tp,
            List<ParameterSymbol> parameters, List<BoundExpression> arguments, int fixedCount) {
        boolean carriedByLambda = false;
        for (int index = 0; index < parameters.size() && index < fixedCount; index++) {
            if (!mentions(parameters.get(index).type(), tp)) {
                continue;
            }
            if (index >= arguments.size()
                    || !Conversions.isUnboundLambda(arguments.get(index))) {
                return false;
            }
            carriedByLambda = true;
        }
        return carriedByLambda;
    }

    /// Reads an explicitly typed lambda as an inference source.
    ///
    /// The lambda itself has no type yet, but the types it *wrote* are as good a constraint as
    /// any ordinary argument's: matching them against the target's abstract-method parameters
    /// closes `T` in `Collectors.GroupingBy(Function&lt;? super T, ? extends K&gt;)`, which no
    /// receiver and no other argument could. A lambda that wrote nothing contributes nothing
    /// and stays open for the body to close, exactly as the design leaves it.
    private static void matchWrittenLambda(TypeSymbol paramType, BoundExpression lambda,
            List<TypeParameterSymbol> typeParams, Map<TypeParameterSymbol, TypeSymbol> inferred,
            LambdaTargets lambdas) {
        List<TypeSymbol> written = lambdas.writtenParameters(lambda);
        if (written == null || written.isEmpty()) {
            return;
        }
        List<TypeSymbol> expected = lambdas.functionalParameters(paramType);
        if (expected == null || expected.size() != written.size()) {
            return;
        }
        for (int index = 0; index < written.size(); index++) {
            matchTypes(expected.get(index), written.get(index), typeParams, inferred);
        }
    }

    private static boolean mentions(TypeSymbol type, TypeParameterSymbol tp) {
        if (type.equals(tp)) {
            return true;
        }
        if (type instanceof TypeSymbol.Array array) {
            return mentions(array.element(), tp);
        }
        if (type instanceof TypeSymbol.Constructed constructed) {
            return constructed.arguments().stream().anyMatch(argument -> mentions(argument, tp));
        }
        if (type instanceof TypeSymbol.Nullable nullable) {
            return mentions(nullable.element(), tp);
        }
        if (type instanceof TypeSymbol.Wildcard wildcard) {
            return mentions(wildcard.bound(), tp);
        }
        return false;
    }

    /// Closes a result type that kept type parameters only its target could supply.
    ///
    /// Returns `source` with those parameters bound to what `target` has in the same
    /// positions, or `null` when the shapes do not line up or a binding would violate the
    /// declared bound. The bound check is not optional: `Comparator.NaturalOrder()` declares
    /// `T extends Comparable<? super T>`, so closing it against `Comparator<Random>` names a
    /// type argument javac rejects, and admitting it would compile to a `ClassCastException`
    /// inside `TimSort` - the exact failure the design exists to prevent, re-entering through the
    /// one position inference does not reach.
    public static TypeSymbol closeAgainstTarget(TypeSymbol source, TypeSymbol target) {
        Map<TypeParameterSymbol, TypeSymbol> inferred = inferFromTarget(source, target);
        if (inferred == null) {
            return null;
        }
        return substitute(source, inferred);
    }

    /// The target with the same closure applied.
    ///
    /// A target can mention the source's own open parameter: inferring `Stream.Collect`'s `R`
    /// from `Collectors.ToList()` gives `R = List<T>` for the *argument's* still-open `T`, so
    /// the parameter reads `Collector<? super string, object, List<T>>`. Closing one side only
    /// would compare a closed type against an open one and always fail; both are closed by the
    /// one substitution and then compared by the ordinary rules.
    public static TypeSymbol closeTargetToo(TypeSymbol source, TypeSymbol target) {
        Map<TypeParameterSymbol, TypeSymbol> inferred = inferFromTarget(source, target);
        return inferred == null ? null : substitute(target, inferred);
    }

    /// The type arguments a conversion target supplies for the parameters left open in `source`,
    /// or `null` when the shapes do not line up, a parameter stays unbound, or a binding would
    /// violate its declared bound.
    ///
    /// This is the one inference step whose information flows from the target inward, so it is
    /// also the only one that can type a lambda whose parameter type mentions a type parameter
    /// no argument supplies: `Comparator.ComparingInt(n =&gt; n.Length())` types `n` only because
    /// the declaration it initialises says `Comparator<string>`.
    public static Map<TypeParameterSymbol, TypeSymbol> inferFromTarget(TypeSymbol source,
            TypeSymbol target) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        List<TypeParameterSymbol> open = new ArrayList<>();
        collectOpen(source, open);
        if (open.isEmpty()) {
            return null;
        }
        Map<TypeParameterSymbol, TypeSymbol> inferred = new HashMap<>();
        matchTypes(source, target, open, inferred);
        for (TypeParameterSymbol parameter : open) {
            if (!inferred.containsKey(parameter)) {
                return null;
            }
        }
        return satisfiesBounds(inferred) ? inferred : null;
    }

    /// Whether the type carries a type parameter no target has yet closed.
    public static boolean hasOpenTypeParameters(TypeSymbol type) {
        List<TypeParameterSymbol> open = new ArrayList<>();
        collectOpen(type, open);
        return !open.isEmpty();
    }

    private static void collectOpen(TypeSymbol type, List<TypeParameterSymbol> into) {
        switch (type) {
            case TypeParameterSymbol parameter -> {
                if (!into.contains(parameter)) {
                    into.add(parameter);
                }
            }
            case TypeSymbol.Array array -> collectOpen(array.element(), into);
            case TypeSymbol.Nullable nullable -> collectOpen(nullable.element(), into);
            case TypeSymbol.Wildcard wildcard -> collectOpen(wildcard.bound(), into);
            case TypeSymbol.Constructed constructed -> {
                for (TypeSymbol argument : constructed.arguments()) {
                    collectOpen(argument, into);
                }
            }
            case TypeSymbol.Tuple tuple -> {
                for (TypeSymbol.TupleElement element : tuple.elements()) {
                    collectOpen(element.type(), into);
                }
            }
            default -> {
            }
        }
    }

    /// Substitutes inferred type arguments into a type. Binding uses this to finish a call
    /// whose result type depended on a lambda body.
    public static TypeSymbol substituteType(TypeSymbol type,
            Map<TypeParameterSymbol, TypeSymbol> map) {
        return substitute(type, map);
    }

    /// The raw Java view of a generic declaration. Java permits invocation through raw
    /// arguments when inference has no type information to recover; V# source already relied
    /// on that behavior before signature preservation. V# declarations never use this path.
    public static FunctionSymbol erase(FunctionSymbol function) {
        Map<TypeParameterSymbol, TypeSymbol> erased = new HashMap<>();
        for (TypeParameterSymbol parameter : function.typeParameters()) {
            erased.put(parameter, parameter.bound());
        }
        List<ParameterSymbol> parameters = function.parameters().stream()
                .map(parameter -> new ParameterSymbol(parameter.name(), parameter.qualifiedName(),
                        parameter.location(), eraseType(parameter.type(), erased),
                        parameter.ordinal(), parameter.modifiers(), parameter.defaultValue()))
                .toList();
        return new FunctionSymbol(function.name(), function.qualifiedName(), function.location(),
                eraseType(function.returnType(), erased), List.of(), parameters,
                function.modifiers(), function.localFunction(), function.synthesized(),
                function.interfaceOwner());
    }

    private static TypeSymbol eraseType(TypeSymbol type,
            Map<TypeParameterSymbol, TypeSymbol> erased) {
        if (type instanceof TypeParameterSymbol parameter) {
            return erased.getOrDefault(parameter, parameter.bound());
        }
        if (type instanceof TypeSymbol.Array array) {
            return new TypeSymbol.Array(eraseType(array.element(), erased), array.ranks());
        }
        if (type instanceof TypeSymbol.Wildcard wildcard) {
            return eraseType(wildcard.bound(), erased);
        }
        if (type instanceof TypeSymbol.Constructed constructed) {
            return constructed.definition();
        }
        if (type instanceof TypeSymbol.Tuple tuple) {
            return new TypeSymbol.Tuple(tuple.elements().stream()
                    .map(element -> new TypeSymbol.TupleElement(
                            eraseType(element.type(), erased), element.name()))
                    .toList());
        }
        return type;
    }

    private static void matchTypes(
            TypeSymbol paramType,
            TypeSymbol argType,
            List<TypeParameterSymbol> typeParams,
            Map<TypeParameterSymbol, TypeSymbol> inferred) {

        // A wildcard is a bound with variance; inference reads the bound, which is the type
        // the argument actually supplies. `Collection<? extends T>` binds `T` from a
        // `Collection<string>` exactly as `Collection<T>` would.
        if (paramType instanceof TypeSymbol.Wildcard paramWildcard) {
            matchTypes(paramWildcard.bound(), argType, typeParams, inferred);
            return;
        }
        if (argType instanceof TypeSymbol.Wildcard argWildcard) {
            matchTypes(paramType, argWildcard.bound(), typeParams, inferred);
            return;
        }
        if (paramType instanceof TypeParameterSymbol tp && typeParams.contains(tp)) {
            inferred.putIfAbsent(tp, argType);
        } else if (paramType instanceof TypeSymbol.Array arrP && argType instanceof TypeSymbol.Array arrA) {
            // C# §12.6.3.10 infers from one array to another when their *outermost* rank
            // matches, and then continues with one indexing step removed from each. Comparing
            // the whole rank chain instead refused `T[]` against `string[][]`, where C# infers
            // `T = string[]`: a jagged array is an array of arrays, so only the first
            // specifier belongs to this step.
            if (arrP.ranks().getFirst().equals(arrA.ranks().getFirst())) {
                matchTypes(arrP.elementType(), arrA.elementType(), typeParams, inferred);
            }
        } else if (paramType instanceof TypeSymbol.Constructed consP && argType instanceof TypeSymbol.Constructed consA) {
            if (consP.definition().equals(consA.definition()) && consP.arguments().size() == consA.arguments().size()) {
                for (int i = 0; i < consP.arguments().size(); i++) {
                    matchTypes(consP.arguments().get(i), consA.arguments().get(i), typeParams, inferred);
                }
            }
        } else if (paramType instanceof TypeSymbol.Tuple tupleP
                && argType instanceof TypeSymbol.Tuple tupleA) {
            // A tuple infers element by element when the arities agree, which is the same
            // structural step a constructed application takes. Element *names* are compile-time
            // only in C# and never participate.
            if (tupleP.elements().size() == tupleA.elements().size()) {
                for (int i = 0; i < tupleP.elements().size(); i++) {
                    matchTypes(tupleP.elements().get(i).type(), tupleA.elements().get(i).type(),
                            typeParams, inferred);
                }
            }
        } else if (paramType instanceof TypeSymbol.Nullable nullableP
                && argType instanceof TypeSymbol.Nullable nullableA) {
            matchTypes(nullableP.element(), nullableA.element(), typeParams, inferred);
        }
    }

    private static TypeSymbol substitute(TypeSymbol type, Map<TypeParameterSymbol, TypeSymbol> map) {
        if (type instanceof TypeParameterSymbol tp && map.containsKey(tp)) {
            return map.get(tp);
        } else if (type instanceof TypeSymbol.Array arr) {
            TypeSymbol subElement = substitute(arr.element(), map);
            return subElement == arr.element() ? arr : new TypeSymbol.Array(subElement, arr.ranks());
        } else if (type instanceof TypeSymbol.Wildcard wildcard) {
            TypeSymbol bound = substitute(wildcard.bound(), map);
            return bound == wildcard.bound() ? wildcard
                    : new TypeSymbol.Wildcard(bound, wildcard.superBound());
        } else if (type instanceof TypeSymbol.Constructed cons) {
            boolean changed = false;
            java.util.List<TypeSymbol> newArgs = new java.util.ArrayList<>();
            for (TypeSymbol arg : cons.arguments()) {
                TypeSymbol subArg = substitute(arg, map);
                if (subArg != arg) {
                    changed = true;
                }
                newArgs.add(subArg);
            }
            if (changed) {
                return new TypeSymbol.Constructed(cons.definition(), newArgs);
            }
            return cons;
        } else if (type instanceof TypeSymbol.Tuple tuple) {
            boolean changed = false;
            java.util.List<TypeSymbol.TupleElement> elements = new java.util.ArrayList<>();
            for (TypeSymbol.TupleElement element : tuple.elements()) {
                TypeSymbol substituted = substitute(element.type(), map);
                if (substituted != element.type()) {
                    changed = true;
                }
                elements.add(new TypeSymbol.TupleElement(substituted, element.name()));
            }
            return changed ? new TypeSymbol.Tuple(elements) : tuple;
        } else if (type instanceof TypeSymbol.Nullable nullable) {
            TypeSymbol element = substitute(nullable.element(), map);
            return element == nullable.element() ? nullable : new TypeSymbol.Nullable(element);
        }
        return type;
    }
}
