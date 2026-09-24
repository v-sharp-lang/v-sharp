package vsharp.compiler.semantics.conversions;

import java.util.Objects;
import vsharp.compiler.semantics.binding.BoundExpression;
import vsharp.compiler.semantics.binding.JavaInterop;
import vsharp.compiler.semantics.constants.ConstantEvaluator;
import vsharp.compiler.semantics.constants.ConstantValue;
import vsharp.compiler.semantics.symbols.NamedTypeSymbol;
import vsharp.compiler.semantics.types.BuiltinType;
import vsharp.compiler.semantics.types.TypeSymbol;

/// C# 13 type conversion classification engine.
public final class Conversions {

    private Conversions() {}

    /// Classify conversion from a bound expression to a target type, considering expression-based
    /// rules (such as implicit constant integer conversions).
    public static Conversion classify(BoundExpression expr, TypeSymbol targetType) {
        return classify(expr, targetType, LambdaTargets.NONE);
    }

    /// Classifies an expression-based conversion where a lambda may appear.
    ///
    /// A lambda has no type of its own, so it is answered by `lambdaTargets` before any
    /// type-to-type rule runs; every other expression form is unaffected.
    public static Conversion classify(BoundExpression expr, TypeSymbol targetType,
            LambdaTargets lambdaTargets) {
        Objects.requireNonNull(expr, "expr");
        Objects.requireNonNull(targetType, "targetType");
        Objects.requireNonNull(lambdaTargets, "lambdaTargets");
        if (isUnboundLambda(expr)) {
            return lambdaTargets.convertible(expr, targetType)
                    ? Conversion.IMPLICIT_LAMBDA
                    : Conversion.NONE;
        }

        TypeSymbol sourceType = expr.type();
        if (sourceType instanceof TypeSymbol.Error || targetType instanceof TypeSymbol.Error) {
            return Conversion.NONE;
        }

        Conversion direct = classify(sourceType, targetType);
        if (direct.isImplicit()) {
            return direct;
        }

        TypeSymbol effectiveTarget = targetType;
        if (effectiveTarget instanceof TypeSymbol.Nullable nullable) {
            effectiveTarget = nullable.element();
        }

        // Check implicit constant expression conversions (C# §10.2.3). The rule is about a
        // constant *expression*, not a literal token: `sbyte b = -128;` is a unary minus over
        // a literal, and `int.MinValue` is a folded constant, so the value has to come from
        // the evaluator rather than from the shape of the node.
        ConstantValue constant = constantOf(expr);
        if (effectiveTarget instanceof BuiltinType b) {
            if (sourceType == BuiltinType.INT && constant instanceof ConstantValue.Int folded) {
                int i = folded.value();
                boolean matches = switch (b) {
                    case SBYTE -> i >= Byte.MIN_VALUE && i <= Byte.MAX_VALUE;
                    case BYTE -> i >= 0 && i <= 255;
                    case SHORT -> i >= Short.MIN_VALUE && i <= Short.MAX_VALUE;
                    case USHORT -> i >= 0 && i <= 65535;
                    case UINT -> i >= 0;
                    case ULONG -> i >= 0;
                    case NINT -> true;
                    case NUINT -> i >= 0;
                    default -> false;
                };
                if (matches) {
                    return targetType instanceof TypeSymbol.Nullable ? Conversion.IMPLICIT_NULLABLE : Conversion.IMPLICIT_CONSTANT;
                }
            } else if (sourceType == BuiltinType.LONG
                    && constant instanceof ConstantValue.Long folded) {
                if (b == BuiltinType.ULONG && folded.value() >= 0) {
                    return targetType instanceof TypeSymbol.Nullable ? Conversion.IMPLICIT_NULLABLE : Conversion.IMPLICIT_CONSTANT;
                }
            }
        } else if (effectiveTarget instanceof NamedTypeSymbol named && named.declaredKind() == NamedTypeSymbol.DeclaredKind.ENUM) {
            // Constant zero converts implicitly to any enum type
            if (constant instanceof ConstantValue.Int folded && folded.value() == 0) {
                return targetType instanceof TypeSymbol.Nullable ? Conversion.IMPLICIT_NULLABLE : Conversion.IMPLICIT_CONSTANT;
            }
        }

        return direct;
    }

    /// The value of `expr` when it is a constant expression, or `null` when it is not.
    ///
    /// Evaluation is unchecked: the range test below *is* the overflow rule for this
    /// conversion, so folding must not reject a value before it is compared.
    private static ConstantValue constantOf(BoundExpression expr) {
        return ConstantEvaluator.evaluate(expr, false)
                instanceof ConstantEvaluator.Result.Value value ? value.value() : null;
    }

    /// The [BoundExpression.Deferred] form a lambda carries until a target type types it
    ///. Binding cannot give a lambda a type, so it stays deferred - and deferred
    /// deliberately, not erroneous, because a lambda in an argument list must survive
    /// overload resolution to find out what it is.
    public static final String LAMBDA_FORM = "lambda";

    /// Whether an expression is a lambda still waiting for its target type.
    public static boolean isUnboundLambda(BoundExpression expr) {
        return expr instanceof BoundExpression.Deferred deferred
                && LAMBDA_FORM.equals(deferred.form());
    }

    /// Classify conversion purely between source and target types.
    public static Conversion classify(TypeSymbol sourceType, TypeSymbol targetType) {
        Objects.requireNonNull(sourceType, "sourceType");
        Objects.requireNonNull(targetType, "targetType");

        if (sourceType instanceof TypeSymbol.Error || targetType instanceof TypeSymbol.Error) {
            return Conversion.NONE;
        }

        if (sourceType.equals(targetType)) {
            return Conversion.IDENTITY;
        }

        // Null literal conversion
        if (sourceType == TypeSymbol.Null.INSTANCE) {
            if (isReferenceType(targetType) || targetType instanceof TypeSymbol.Nullable) {
                return Conversion.IMPLICIT_NULL;
            }
            return Conversion.NONE;
        }

        // Builtin numeric / char conversions
        if (sourceType instanceof BuiltinType src && targetType instanceof BuiltinType tgt) {
            if (isImplicitNumeric(src, tgt)) {
                return Conversion.IMPLICIT_NUMERIC;
            }
            if (isExplicitNumeric(src, tgt)) {
                return Conversion.EXPLICIT_NUMERIC;
            }
        }

        // Enum conversions
        if (sourceType instanceof NamedTypeSymbol srcNamed && srcNamed.declaredKind() == NamedTypeSymbol.DeclaredKind.ENUM) {
            if (targetType instanceof BuiltinType tgtB && isNumericOrChar(tgtB)) {
                return Conversion.EXPLICIT_ENUM;
            }
            if (targetType instanceof NamedTypeSymbol tgtNamed && tgtNamed.declaredKind() == NamedTypeSymbol.DeclaredKind.ENUM) {
                return Conversion.EXPLICIT_ENUM;
            }
        } else if (targetType instanceof NamedTypeSymbol tgtNamed && tgtNamed.declaredKind() == NamedTypeSymbol.DeclaredKind.ENUM) {
            if (sourceType instanceof BuiltinType srcB && isNumericOrChar(srcB)) {
                return Conversion.EXPLICIT_ENUM;
            }
        }

        // Boxing a nullable value is an identity at runtime under no value is null and
        // a present value is already its boxed underlying value. Classify it before the
        // general nullable-source rule, which otherwise mistakes `T? -> object` for an
        // explicit nullable conversion derived from `T -> object`.
        if (sourceType instanceof TypeSymbol.Nullable && targetType == BuiltinType.OBJECT) {
            return Conversion.BOXING;
        }

        // Nullable conversions
        if (targetType instanceof TypeSymbol.Nullable targetNullable) {
            TypeSymbol targetUnderlying = targetNullable.element();
            if (sourceType instanceof TypeSymbol.Nullable sourceNullable) {
                TypeSymbol sourceUnderlying = sourceNullable.element();
                Conversion underlyingConv = classify(sourceUnderlying, targetUnderlying);
                if (underlyingConv.isImplicit()) {
                    return Conversion.IMPLICIT_NULLABLE;
                }
                if (underlyingConv.isExplicit()) {
                    return Conversion.EXPLICIT_NULLABLE;
                }
            } else {
                Conversion underlyingConv = classify(sourceType, targetUnderlying);
                if (underlyingConv.isImplicit()) {
                    return Conversion.IMPLICIT_NULLABLE;
                }
                if (underlyingConv.isExplicit()) {
                    return Conversion.EXPLICIT_NULLABLE;
                }
            }
        } else if (sourceType instanceof TypeSymbol.Nullable sourceNullable) {
            Conversion underlyingConv = classify(sourceNullable.element(), targetType);
            if (underlyingConv.exists()) {
                return Conversion.EXPLICIT_NULLABLE;
            }
        }

        // Tuple conversions
        if (sourceType instanceof TypeSymbol.Tuple srcTuple
                && targetType instanceof TypeSymbol.Tuple tgtTuple) {
            if (srcTuple.elements().size() == tgtTuple.elements().size()) {
                boolean allImplicit = true;
                boolean allExist = true;

                for (int i = 0; i < srcTuple.elements().size(); i++) {
                    TypeSymbol sElem = srcTuple.elements().get(i).type();
                    TypeSymbol tElem = tgtTuple.elements().get(i).type();
                    Conversion c = classify(sElem, tElem);
                    if (!c.exists()) {
                        allExist = false;
                        break;
                    }
                    if (!c.isImplicit()) {
                        allImplicit = false;
                    }
                }

                if (allExist) {
                    return allImplicit ? Conversion.IMPLICIT_TUPLE : Conversion.EXPLICIT_TUPLE;
                }
            }
        }

        // Boxing conversions
        if (sourceType.isValueType() && targetType == BuiltinType.OBJECT) {
            return Conversion.BOXING;
        }

        // Explicit unboxing conversions (C# §10.3.7): `object` to any non-nullable value
        // type. Nullable targets were handled above, so the underlying conversion they
        // derive from lands here.
        if (sourceType == BuiltinType.OBJECT && targetType.isValueType()) {
            return Conversion.UNBOXING;
        }

        // Reference conversions
        if (isReferenceType(sourceType) && targetType == BuiltinType.OBJECT) {
            return Conversion.IMPLICIT_REFERENCE;
        }
        if (sourceType == BuiltinType.OBJECT && isReferenceType(targetType)) {
            return Conversion.EXPLICIT_REFERENCE;
        }

        // Java reference conversions. The JVM hierarchy each module-path type carries
        // decides both directions: to a supertype or implemented interface implicitly, exactly
        // as the JVM assigns without an instruction, and back down explicitly, which the
        // backend guards with `checkcast`. V# declarations carry an empty closure, so this
        // never widens the object-model-free subset's own conversions.
        // A keyword type reaching the same hierarchy from the side that carries it:
        // `string` is `java.lang.String`, so it widens to every interface that class
        // implements, and comes back down through an explicit cast.
        if (sourceType instanceof BuiltinType keyword
                && targetType instanceof NamedTypeSymbol javaTarget
                && javaTarget.keywordSubtypes().contains(keyword)) {
            // A keyword *value* type reaches the hierarchy through its boxed carrier, so the
            // conversion is a boxing one and the backend must wrap it. C# classifies
            // `IComparable c = 42;` the same way, and the emitter already knows how to emit
            // it. A reference keyword - `string` - is assignable as it stands.
            return keyword.isValueType() ? Conversion.BOXING : Conversion.IMPLICIT_REFERENCE;
        }
        if (targetType instanceof BuiltinType keywordTarget
                && sourceType instanceof NamedTypeSymbol javaSource
                && javaSource.keywordSubtypes().contains(keywordTarget)) {
            return keywordTarget.isValueType()
                    ? Conversion.UNBOXING
                    : Conversion.EXPLICIT_REFERENCE;
        }

        if (sourceType instanceof NamedTypeSymbol source
                && targetType instanceof NamedTypeSymbol target) {
            if (source.javaSupertypes().contains(target.qualifiedName())) {
                return Conversion.IMPLICIT_REFERENCE;
            }
            if (target.javaSupertypes().contains(source.qualifiedName())) {
                return Conversion.EXPLICIT_REFERENCE;
            }
        }

        NamedTypeSymbol source = namedDefinition(sourceType);
        NamedTypeSymbol target = namedDefinition(targetType);
        if (source != null && target != null
                && JavaInterop.isModulePathSymbol(source)
                && JavaInterop.isModulePathSymbol(target)
                && compatibleJavaArguments(sourceType, targetType)) {
            if (source.equals(target)) {
                // A constructed Java value converts to the raw spelling of the same class.
                // This is the erasure-compatible path existing Java interop source used before
                // signatures were preserved; raw-to-constructed remains refused below.
                return Conversion.IMPLICIT_REFERENCE;
            }
            if (source.javaSupertypes().contains(target.qualifiedName())) {
                return Conversion.IMPLICIT_REFERENCE;
            }
            if (target.javaSupertypes().contains(source.qualifiedName())) {
                return Conversion.EXPLICIT_REFERENCE;
            }
        }

        // Java's unchecked conversion (JLS 5.1.9): a *raw* value converts to a parameterized
        // form of the same class, which Java permits with a warning. V# admits it only as an
        // explicit cast, never implicitly, because the cast is exactly the assertion the
        // warning stands for and nothing at run time can check it - both spellings erase to the
        // one class. Without it, any raw result a JDK signature produces is unusable: Spring's
        // `ServerResponse.Ok().Body(publisher, Class)` hands back a raw `Mono`, and a handler
        // must return `Mono<ServerResponse>`.
        if (source != null && target != null && source.equals(target)
                && JavaInterop.isModulePathSymbol(source)
                && sourceType instanceof NamedTypeSymbol
                && targetType instanceof TypeSymbol.Constructed) {
            return Conversion.EXPLICIT_REFERENCE;
        }

        // A result whose type argument only its target could supply is closed here.
        // `Comparator.NaturalOrder()` is `Comparator<T>` with `T` open until this conversion
        // names it; closing is a real inference step, checked against the declared bound, so
        // it accepts exactly the programs javac accepts. The conversion is reported as a
        // reference conversion rather than an identity so the binder's wrapper records the
        // *closed* type - nothing downstream should ever read the open one. It emits no
        // instruction: erasure makes both spellings the same JVM carrier.
        if (vsharp.compiler.semantics.overloads.GenericInference
                .hasOpenTypeParameters(sourceType)) {
            TypeSymbol closedSource = vsharp.compiler.semantics.overloads.GenericInference
                    .closeAgainstTarget(sourceType, targetType);
            TypeSymbol closedTarget = vsharp.compiler.semantics.overloads.GenericInference
                    .closeTargetToo(sourceType, targetType);
            // Both sides carry the one substitution, and the comparison that follows is the
            // ordinary one - it cannot re-enter this branch, because the closed source has no
            // open parameter left.
            if (closedSource != null && closedTarget != null
                    && classify(closedSource, closedTarget).isImplicit()) {
                return Conversion.IMPLICIT_REFERENCE;
            }
        }

        // Array covariance (C# §10.2.12 implicit, §10.3.5 explicit). `S[]` converts to `T[]`
        // when the ranks are identical, both element types are reference types, and a
        // *reference* conversion relates the elements. The JVM already assigns covariant
        // reference arrays without an instruction and already stores an `ArrayStoreException`
        // guard on every `aastore`, so C#'s rule and its run-time check are the platform's
        // own rule - the implicit direction emits nothing and the explicit one is one
        // `checkcast` against the target array descriptor. The reference-element guard is
        // what keeps `int[]` out: a primitive array is not assignable to `object[]` in either
        // language, and admitting it would emit unverifiable bytecode rather than a diagnostic.
        if (sourceType instanceof TypeSymbol.Array sourceArray
                && targetType instanceof TypeSymbol.Array targetArray
                && sourceArray.ranks().equals(targetArray.ranks())
                && isReferenceType(sourceArray.element())
                && isReferenceType(targetArray.element())) {
            ConversionKind elementKind = classify(sourceArray.element(), targetArray.element()).kind();
            if (elementKind == ConversionKind.IMPLICIT_REFERENCE) {
                return Conversion.IMPLICIT_REFERENCE;
            }
            if (elementKind == ConversionKind.EXPLICIT_REFERENCE) {
                return Conversion.EXPLICIT_REFERENCE;
            }
        }

        return Conversion.NONE;
    }

    public static boolean isImplicitNumeric(BuiltinType src, BuiltinType tgt) {
        return switch (src) {
            case SBYTE -> tgt == BuiltinType.SHORT || tgt == BuiltinType.INT || tgt == BuiltinType.LONG
                    || tgt == BuiltinType.FLOAT || tgt == BuiltinType.DOUBLE || tgt == BuiltinType.DECIMAL
                    || tgt == BuiltinType.NINT;
            case BYTE -> tgt == BuiltinType.SHORT || tgt == BuiltinType.USHORT || tgt == BuiltinType.INT
                    || tgt == BuiltinType.UINT || tgt == BuiltinType.LONG || tgt == BuiltinType.ULONG
                    || tgt == BuiltinType.FLOAT || tgt == BuiltinType.DOUBLE || tgt == BuiltinType.DECIMAL
                    || tgt == BuiltinType.NINT || tgt == BuiltinType.NUINT;
            case SHORT -> tgt == BuiltinType.INT || tgt == BuiltinType.LONG || tgt == BuiltinType.FLOAT
                    || tgt == BuiltinType.DOUBLE || tgt == BuiltinType.DECIMAL || tgt == BuiltinType.NINT;
            case USHORT -> tgt == BuiltinType.INT || tgt == BuiltinType.UINT || tgt == BuiltinType.LONG
                    || tgt == BuiltinType.ULONG || tgt == BuiltinType.FLOAT || tgt == BuiltinType.DOUBLE
                    || tgt == BuiltinType.DECIMAL || tgt == BuiltinType.NINT || tgt == BuiltinType.NUINT;
            case INT -> tgt == BuiltinType.LONG || tgt == BuiltinType.FLOAT || tgt == BuiltinType.DOUBLE
                    || tgt == BuiltinType.DECIMAL || tgt == BuiltinType.NINT;
            case UINT -> tgt == BuiltinType.LONG || tgt == BuiltinType.ULONG || tgt == BuiltinType.FLOAT
                    || tgt == BuiltinType.DOUBLE || tgt == BuiltinType.DECIMAL || tgt == BuiltinType.NUINT;
            case LONG, ULONG -> tgt == BuiltinType.FLOAT || tgt == BuiltinType.DOUBLE || tgt == BuiltinType.DECIMAL;
            case CHAR -> tgt == BuiltinType.USHORT || tgt == BuiltinType.INT || tgt == BuiltinType.UINT
                    || tgt == BuiltinType.LONG || tgt == BuiltinType.ULONG || tgt == BuiltinType.FLOAT
                    || tgt == BuiltinType.DOUBLE || tgt == BuiltinType.DECIMAL || tgt == BuiltinType.NINT
                    || tgt == BuiltinType.NUINT;
            case FLOAT -> tgt == BuiltinType.DOUBLE;
            case NINT -> tgt == BuiltinType.LONG || tgt == BuiltinType.FLOAT || tgt == BuiltinType.DOUBLE
                    || tgt == BuiltinType.DECIMAL;
            case NUINT -> tgt == BuiltinType.ULONG || tgt == BuiltinType.FLOAT || tgt == BuiltinType.DOUBLE
                    || tgt == BuiltinType.DECIMAL;
            default -> false;
        };
    }

    public static boolean isExplicitNumeric(BuiltinType src, BuiltinType tgt) {
        if (!isNumericOrChar(src) || !isNumericOrChar(tgt)) {
            return false;
        }
        return src != tgt && !isImplicitNumeric(src, tgt);
    }

    private static boolean isNumericOrChar(BuiltinType b) {
        return switch (b) {
            case SBYTE, BYTE, SHORT, USHORT, INT, UINT, LONG, ULONG, CHAR, FLOAT, DOUBLE, DECIMAL, NINT, NUINT -> true;
            default -> false;
        };
    }

    private static boolean isReferenceType(TypeSymbol type) {
        if (type == BuiltinType.STRING || type == BuiltinType.OBJECT) {
            return true;
        }
        if (type instanceof NamedTypeSymbol named) {
            return named.declaredKind() == NamedTypeSymbol.DeclaredKind.CLASS 
                || named.declaredKind() == NamedTypeSymbol.DeclaredKind.INTERFACE;
        }
        return type instanceof TypeSymbol.Array || type instanceof TypeSymbol.Tuple
                || type instanceof TypeSymbol.Constructed;
    }

    private static NamedTypeSymbol namedDefinition(TypeSymbol type) {
        if (type instanceof NamedTypeSymbol named) {
            return named;
        }
        return type instanceof TypeSymbol.Constructed constructed
                ? constructed.definition() : null;
    }

    /// This bounded interop slice preserves invariant concrete arguments. A conversion to a
    /// raw target is safe under erasure; two constructed types must carry the same arguments.
    /// Raw-to-constructed unchecked conversion and wildcard variance stay outside the model.
    private static boolean compatibleJavaArguments(TypeSymbol source, TypeSymbol target) {
        if (!(target instanceof TypeSymbol.Constructed targetConstructed)) {
            return true;
        }
        if (!(source instanceof TypeSymbol.Constructed sourceConstructed)
                || sourceConstructed.arguments().size() != targetConstructed.arguments().size()) {
            return false;
        }
        for (int index = 0; index < targetConstructed.arguments().size(); index++) {
            if (!contains(targetConstructed.arguments().get(index),
                    sourceConstructed.arguments().get(index))) {
                return false;
            }
        }
        return true;
    }

    /// JLS 4.5.1 containment: whether a type argument written on the target admits the one the
    /// source carries.
    ///
    /// Without a wildcard this is invariance, exactly as before - `List<string>` is not a
    /// `List<object>` in Java and is not one here. A wildcard is the one place Java itself is
    /// variant, so `Collection<? extends object>` admits `ArrayList<string>`'s `string` and
    /// `Comparator<? super string>` admits `object`. Reading the wildcard is what keeps both
    /// answers Java's: erasing it lost the result type of every `Collectors` idiom, and
    /// projecting it away would have refused the covariant call.
    private static boolean contains(TypeSymbol targetArgument, TypeSymbol sourceArgument) {
        if (targetArgument.equals(sourceArgument)) {
            return true;
        }
        // Java captures a wildcard the source carries into a fresh type the target's own
        // parameter is then inferred as: `Collector<T,?,List<T>>` reaches
        // `Collector<? super T, A, R>` with `A` standing for the capture of `?`. V# models
        // the capture by its bound, which is what the descriptor carries anyway.
        if (sourceArgument instanceof TypeSymbol.Wildcard capture
                && !(targetArgument instanceof TypeSymbol.Wildcard)) {
            return capture.bound().equals(targetArgument);
        }
        if (!(targetArgument instanceof TypeSymbol.Wildcard wildcard)) {
            return false;
        }
        TypeSymbol source = sourceArgument instanceof TypeSymbol.Wildcard sourceWildcard
                ? sourceWildcard.bound()
                : sourceArgument;
        if (sourceArgument instanceof TypeSymbol.Wildcard sourceWildcard
                && sourceWildcard.superBound() != wildcard.superBound()) {
            return false;
        }
        if (wildcard.bound() == BuiltinType.OBJECT && !wildcard.superBound()) {
            return true;
        }
        return wildcard.superBound()
                ? classify(wildcard.bound(), source).isImplicit()
                : classify(source, wildcard.bound()).isImplicit();
    }
}
