package vsharp.compiler.semantics.binding;

import java.util.EnumSet;
import java.util.Set;
import vsharp.compiler.semantics.symbols.NamedTypeSymbol;
import vsharp.compiler.semantics.symbols.TypeParameterSymbol;
import vsharp.compiler.semantics.types.BuiltinType;
import vsharp.compiler.semantics.types.TypeSymbol;
import vsharp.compiler.syntax.SyntaxKind;

/// Pure type rules used by the first expression-binding stage.
final class CoreOperators {

    private static final Set<BuiltinType> NUMERIC = EnumSet.of(
            BuiltinType.SBYTE, BuiltinType.BYTE, BuiltinType.SHORT, BuiltinType.USHORT,
            BuiltinType.INT, BuiltinType.UINT, BuiltinType.LONG, BuiltinType.ULONG,
            BuiltinType.NINT, BuiltinType.NUINT, BuiltinType.FLOAT, BuiltinType.DOUBLE,
            BuiltinType.DECIMAL, BuiltinType.CHAR);

    private static final Set<BuiltinType> INTEGRAL = EnumSet.of(
            BuiltinType.SBYTE, BuiltinType.BYTE, BuiltinType.SHORT, BuiltinType.USHORT,
            BuiltinType.INT, BuiltinType.UINT, BuiltinType.LONG, BuiltinType.ULONG,
            BuiltinType.NINT, BuiltinType.NUINT, BuiltinType.CHAR);

    private CoreOperators() {
        throw new AssertionError("No instances");
    }

    static TypeSymbol unaryResult(SyntaxKind operator, TypeSymbol operand) {
        if (operand == TypeSymbol.Error.INSTANCE || operand == TypeSymbol.Inferred.INSTANCE) {
            return TypeSymbol.Error.INSTANCE;
        }
        if (operand instanceof TypeSymbol.Nullable nullable) {
            TypeSymbol result = unaryResult(operator, nullable.element());
            return result == TypeSymbol.Error.INSTANCE
                    ? result : new TypeSymbol.Nullable(result);
        }
        return switch (operator) {
            case PLUS -> unaryNumericPromotion(operand);
            case MINUS -> unaryNegation(operand);
            case EXCLAMATION -> operand == BuiltinType.BOOL
                    ? BuiltinType.BOOL : TypeSymbol.Error.INSTANCE;
            case TILDE -> isEnum(operand) ? operand : unaryIntegralPromotion(operand);
            case CARET -> {
                TypeSymbol promoted = unaryIntegralPromotion(operand);
                yield promoted == BuiltinType.INT ? TypeSymbol.Index.INSTANCE : TypeSymbol.Error.INSTANCE;
            }
            case PLUS_PLUS, MINUS_MINUS -> isNumeric(operand)
                    || operand instanceof NamedTypeSymbol named
                            && named.declaredKind() == NamedTypeSymbol.DeclaredKind.ENUM
                    ? operand : TypeSymbol.Error.INSTANCE;
            default -> TypeSymbol.Error.INSTANCE;
        };
    }

    static TypeSymbol binaryResult(SyntaxKind operator, TypeSymbol left, TypeSymbol right) {
        if (left == TypeSymbol.Error.INSTANCE || right == TypeSymbol.Error.INSTANCE
                || left == TypeSymbol.Inferred.INSTANCE || right == TypeSymbol.Inferred.INSTANCE) {
            return TypeSymbol.Error.INSTANCE;
        }
        if ((operator == SyntaxKind.EQUALS_EQUALS
                || operator == SyntaxKind.EXCLAMATION_EQUALS)
                && equalityApplicable(left, right)) {
            return BuiltinType.BOOL;
        }
        if (operator != SyntaxKind.QUESTION_QUESTION
                && (left instanceof TypeSymbol.Nullable
                        || right instanceof TypeSymbol.Nullable)) {
            return liftedBinaryResult(operator, left, right);
        }
        return switch (operator) {
            case PLUS -> binaryPlus(left, right);
            case MINUS -> binaryMinus(left, right);
            case ASTERISK, SLASH, PERCENT -> numericPromotion(left, right);
            case LESS_THAN, LESS_THAN_EQUALS, GREATER_THAN, GREATER_THAN_EQUALS ->
                    binaryRelational(left, right);
            case EQUALS_EQUALS, EXCLAMATION_EQUALS -> equalityApplicable(left, right)
                    ? BuiltinType.BOOL : TypeSymbol.Error.INSTANCE;
            case AMPERSAND, BAR, CARET -> bitwiseResult(left, right);
            case AMPERSAND_AMPERSAND, BAR_BAR -> left == BuiltinType.BOOL
                    && right == BuiltinType.BOOL
                    ? BuiltinType.BOOL : TypeSymbol.Error.INSTANCE;
            case LESS_THAN_LESS_THAN, GREATER_THAN_GREATER_THAN,
                    GREATER_THAN_GREATER_THAN_GREATER_THAN -> shiftResult(left, right);
            case QUESTION_QUESTION -> coalesceResult(left, right);
            default -> TypeSymbol.Error.INSTANCE;
        };
    }

    /// The single type both operands of `operator` are converted to before it is applied, or
    /// `null` when the operands keep their own types. C# §12.4.7 promotes both operands of an
    /// arithmetic, relational, numeric-equality or integral-bitwise operator to one common
    /// type; the JVM has no mixed-width arithmetic instruction, so this is not a formality -
    /// without it the backend emits `LADD` over a `long` and an `int` and the class fails
    /// verification.
    ///
    /// `null` is returned where promotion must not happen: string concatenation keeps each
    /// operand's own type for formatting, `bool` and reference equality have nothing to
    /// promote, `&&`/`||` are already `bool`, and a shift deliberately keeps a promoted left
    /// operand and an `int` count, which is the shape the JVM shift opcodes already take.
    /// Nullable-lifted forms are also excluded: their operands are lifted, not converted.
    static TypeSymbol binaryOperandType(SyntaxKind operator, TypeSymbol left, TypeSymbol right) {
        if (left instanceof TypeSymbol.Nullable || right instanceof TypeSymbol.Nullable
                || !isNumeric(left) || !isNumeric(right)) {
            return null;
        }
        TypeSymbol promoted = switch (operator) {
            case PLUS, MINUS, ASTERISK, SLASH, PERCENT,
                    LESS_THAN, LESS_THAN_EQUALS, GREATER_THAN, GREATER_THAN_EQUALS,
                    EQUALS_EQUALS, EXCLAMATION_EQUALS -> numericPromotion(left, right);
            case AMPERSAND, BAR, CARET -> bitwiseResult(left, right);
            default -> TypeSymbol.Error.INSTANCE;
        };
        return promoted == TypeSymbol.Error.INSTANCE || !isNumeric(promoted) ? null : promoted;
    }

    static TypeSymbol bestCommonType(TypeSymbol left, TypeSymbol right) {
        if (same(left, right)) {
            return left;
        }
        if (left == TypeSymbol.Null.INSTANCE && acceptsNull(right)) {
            return right;
        }
        if (right == TypeSymbol.Null.INSTANCE && acceptsNull(left)) {
            return left;
        }
        TypeSymbol numeric = numericPromotion(left, right);
        if (numeric != TypeSymbol.Error.INSTANCE) {
            return numeric;
        }
        if (implicitConversion(left, right)) {
            return right;
        }
        return implicitConversion(right, left) ? left : TypeSymbol.Error.INSTANCE;
    }

    static boolean implicitConversion(TypeSymbol source, TypeSymbol target) {
        if (source == TypeSymbol.Error.INSTANCE || target == TypeSymbol.Error.INSTANCE
                || source == TypeSymbol.Inferred.INSTANCE || target == TypeSymbol.Inferred.INSTANCE) {
            return true;
        }
        return vsharp.compiler.semantics.conversions.Conversions.classify(source, target).isImplicit();
    }

    static boolean acceptsNull(TypeSymbol type) {
        if (type == BuiltinType.VOID) {
            return false;
        }
        if (!type.isValueType()) {
            return true;
        }
        if (type instanceof TypeSymbol.Nullable || type == TypeSymbol.Null.INSTANCE) {
            return true;
        }
        return false;
    }

    private static TypeSymbol unaryNumericPromotion(TypeSymbol operand) {
        if (!(operand instanceof BuiltinType builtin) || !NUMERIC.contains(builtin)) {
            return TypeSymbol.Error.INSTANCE;
        }
        return isSmallIntegral(builtin) ? BuiltinType.INT : builtin;
    }

    private static TypeSymbol unaryNegation(TypeSymbol operand) {
        TypeSymbol promoted = unaryNumericPromotion(operand);
        if (promoted == BuiltinType.UINT) {
            return BuiltinType.LONG;
        }
        return promoted == BuiltinType.ULONG || promoted == BuiltinType.NUINT
                ? TypeSymbol.Error.INSTANCE : promoted;
    }

    private static TypeSymbol unaryIntegralPromotion(TypeSymbol operand) {
        if (!(operand instanceof BuiltinType builtin) || !INTEGRAL.contains(builtin)) {
            return TypeSymbol.Error.INSTANCE;
        }
        return isSmallIntegral(builtin) ? BuiltinType.INT : builtin;
    }

    private static TypeSymbol numericPromotion(TypeSymbol left, TypeSymbol right) {
        if (!(left instanceof BuiltinType first) || !(right instanceof BuiltinType second)
                || !NUMERIC.contains(first) || !NUMERIC.contains(second)) {
            return TypeSymbol.Error.INSTANCE;
        }
        if (first == BuiltinType.DECIMAL || second == BuiltinType.DECIMAL) {
            return first == BuiltinType.FLOAT || first == BuiltinType.DOUBLE
                    || second == BuiltinType.FLOAT || second == BuiltinType.DOUBLE
                    ? TypeSymbol.Error.INSTANCE : BuiltinType.DECIMAL;
        }
        if (first == BuiltinType.DOUBLE || second == BuiltinType.DOUBLE) {
            return BuiltinType.DOUBLE;
        }
        if (first == BuiltinType.FLOAT || second == BuiltinType.FLOAT) {
            return BuiltinType.FLOAT;
        }
        if (first == BuiltinType.ULONG || second == BuiltinType.ULONG) {
            BuiltinType other = first == BuiltinType.ULONG ? second : first;
            return isSignedIntegral(other) ? TypeSymbol.Error.INSTANCE : BuiltinType.ULONG;
        }
        if (first == BuiltinType.NUINT || second == BuiltinType.NUINT) {
            BuiltinType other = first == BuiltinType.NUINT ? second : first;
            if (other == BuiltinType.ULONG) {
                return BuiltinType.ULONG;
            }
            return isSignedIntegral(other) ? TypeSymbol.Error.INSTANCE : BuiltinType.NUINT;
        }
        if (first == BuiltinType.LONG || second == BuiltinType.LONG) {
            return BuiltinType.LONG;
        }
        if (first == BuiltinType.NINT || second == BuiltinType.NINT) {
            BuiltinType other = first == BuiltinType.NINT ? second : first;
            return other == BuiltinType.UINT ? BuiltinType.LONG : BuiltinType.NINT;
        }
        if (first == BuiltinType.UINT || second == BuiltinType.UINT) {
            BuiltinType other = first == BuiltinType.UINT ? second : first;
            return oneOf(other, BuiltinType.SBYTE, BuiltinType.SHORT, BuiltinType.INT)
                    ? BuiltinType.LONG : BuiltinType.UINT;
        }
        return BuiltinType.INT;
    }

    private static TypeSymbol bitwiseResult(TypeSymbol left, TypeSymbol right) {
        if (left == BuiltinType.BOOL && right == BuiltinType.BOOL) {
            return BuiltinType.BOOL;
        }
        if (left.equals(right) && isEnum(left)) {
            return left;
        }
        TypeSymbol result = numericPromotion(left, right);
        return isIntegral(result) ? result : TypeSymbol.Error.INSTANCE;
    }

    /// C# §19.6.3 defines `E + U` and `U + E` over an enum and its underlying type, while
    /// `E + E` remains invalid; the promoted arithmetic path below deliberately does not
    /// accept an enum, so these are the only enum-plus forms that bind.
    private static TypeSymbol binaryPlus(TypeSymbol left, TypeSymbol right) {
        if (stringConcatenation(left, right)) {
            return BuiltinType.STRING;
        }
        if (isEnum(left) && right == BuiltinType.INT) {
            return left;
        }
        if (isEnum(right) && left == BuiltinType.INT) {
            return right;
        }
        return numericPromotion(left, right);
    }

    /// `E - U` returns the enum; `E - E` returns the underlying type (`int` in V#, the design).
    /// `U - E` has no C# predefined operator and stays invalid.
    private static TypeSymbol binaryMinus(TypeSymbol left, TypeSymbol right) {
        if (isEnum(left) && right == BuiltinType.INT) {
            return left;
        }
        if (isEnum(left) && left.equals(right)) {
            return BuiltinType.INT;
        }
        return numericPromotion(left, right);
    }

    /// Relational operators over the same enum type compare the underlying values (C#
    /// §19.6.3); the operands keep their own `int` carrier, so no promotion is needed and
    /// the backend emits `if_icmp*` directly.
    private static TypeSymbol binaryRelational(TypeSymbol left, TypeSymbol right) {
        if (isEnum(left) && left.equals(right)) {
            return BuiltinType.BOOL;
        }
        return numericPromotion(left, right) == TypeSymbol.Error.INSTANCE
                ? TypeSymbol.Error.INSTANCE : BuiltinType.BOOL;
    }

    private static TypeSymbol liftedBinaryResult(SyntaxKind operator, TypeSymbol left,
            TypeSymbol right) {
        if (operator == SyntaxKind.AMPERSAND_AMPERSAND || operator == SyntaxKind.BAR_BAR) {
            return TypeSymbol.Error.INSTANCE;
        }
        TypeSymbol unwrappedLeft = unwrapNullable(left, right);
        TypeSymbol unwrappedRight = unwrapNullable(right, left);
        TypeSymbol result = binaryResult(operator, unwrappedLeft, unwrappedRight);
        if (result == TypeSymbol.Error.INSTANCE) {
            return result;
        }
        if (operator == SyntaxKind.EQUALS_EQUALS || operator == SyntaxKind.EXCLAMATION_EQUALS
                || operator == SyntaxKind.LESS_THAN || operator == SyntaxKind.LESS_THAN_EQUALS
                || operator == SyntaxKind.GREATER_THAN
                || operator == SyntaxKind.GREATER_THAN_EQUALS) {
            return BuiltinType.BOOL;
        }
        return result.isValueType() ? new TypeSymbol.Nullable(result) : result;
    }

    private static TypeSymbol unwrapNullable(TypeSymbol type, TypeSymbol other) {
        if (type instanceof TypeSymbol.Nullable nullable) {
            return nullable.element();
        }
        return type == TypeSymbol.Null.INSTANCE && other instanceof TypeSymbol.Nullable nullable
                ? nullable.element() : type;
    }

    private static TypeSymbol shiftResult(TypeSymbol left, TypeSymbol right) {
        if (!isIntegral(left) || !isIntegral(right)) {
            return TypeSymbol.Error.INSTANCE;
        }
        return unaryIntegralPromotion(left);
    }

    private static TypeSymbol coalesceResult(TypeSymbol left, TypeSymbol right) {
        if (left == TypeSymbol.Null.INSTANCE) {
            return right;
        }
        if (left instanceof TypeSymbol.Nullable nullable) {
            if (implicitConversion(right, nullable.element())) {
                return nullable.element();
            }
            return implicitConversion(right, left) ? left : TypeSymbol.Error.INSTANCE;
        }
        if (acceptsNull(left)) {
            return implicitConversion(right, left) ? left : bestCommonType(left, right);
        }
        return TypeSymbol.Error.INSTANCE;
    }

    private static boolean equalityApplicable(TypeSymbol left, TypeSymbol right) {
        if (!typeParameterOperandAllowed(left, right)
                || !typeParameterOperandAllowed(right, left)) {
            return false;
        }
        if (same(left, right)) {
            return left != BuiltinType.VOID;
        }
        if (left == TypeSymbol.Null.INSTANCE) {
            return acceptsNull(right);
        }
        if (right == TypeSymbol.Null.INSTANCE) {
            return acceptsNull(left);
        }
        return numericPromotion(left, right) != TypeSymbol.Error.INSTANCE
                || implicitConversion(left, right) || implicitConversion(right, left);
    }

    /// Whether one operand of `==`/`!=` may be a type parameter.
    ///
    /// C# refuses the operator unless the parameter is *known* to be a reference type, because
    /// erasure leaves only one implementation to choose and the two candidates disagree:
    /// `==` on a reference type compares identity, while on a value type it would have to
    /// compare values. V# had accepted it and silently emitted the identity comparison, so a
    /// generic `Contains&lt;T&gt;` over strings answered by reference - two equal method names
    /// parsed from different buffers were "different" - with nothing reported at any stage.
    ///
    /// The null literal is the one admissible companion, exactly as in C#: `value == null` asks
    /// a question about absence that erasure answers the same way for every instantiation.
    private static boolean typeParameterOperandAllowed(TypeSymbol operand, TypeSymbol other) {
        if (!(operand instanceof TypeParameterSymbol parameter)) {
            return true;
        }
        return other == TypeSymbol.Null.INSTANCE
                || parameter.valueKind() == TypeParameterSymbol.ValueKind.REFERENCE;
    }

    private static boolean stringConcatenation(TypeSymbol left, TypeSymbol right) {
        return left == BuiltinType.STRING && right != BuiltinType.VOID
                || right == BuiltinType.STRING && left != BuiltinType.VOID;
    }

    private static boolean isNumeric(TypeSymbol type) {
        return type instanceof BuiltinType builtin && NUMERIC.contains(builtin);
    }

    private static boolean isEnum(TypeSymbol type) {
        return type instanceof NamedTypeSymbol named
                && named.declaredKind() == NamedTypeSymbol.DeclaredKind.ENUM;
    }

    private static boolean isIntegral(TypeSymbol type) {
        return type instanceof BuiltinType builtin && INTEGRAL.contains(builtin);
    }

    private static boolean isSmallIntegral(BuiltinType type) {
        return oneOf(type, BuiltinType.SBYTE, BuiltinType.BYTE, BuiltinType.SHORT,
                BuiltinType.USHORT, BuiltinType.CHAR);
    }

    private static boolean isSignedIntegral(BuiltinType type) {
        return oneOf(type, BuiltinType.SBYTE, BuiltinType.SHORT, BuiltinType.INT,
                BuiltinType.LONG, BuiltinType.NINT);
    }

    private static boolean same(TypeSymbol left, TypeSymbol right) {
        return left.equals(right);
    }

    private static boolean oneOf(BuiltinType value, BuiltinType... candidates) {
        for (BuiltinType candidate : candidates) {
            if (value == candidate) {
                return true;
            }
        }
        return false;
    }
}
