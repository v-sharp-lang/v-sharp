package vsharp.compiler.semantics.constants;

import java.math.BigDecimal;
import java.util.Objects;
import vsharp.compiler.diagnostics.DiagnosticCode;
import vsharp.compiler.semantics.binding.BoundExpression;
import vsharp.compiler.semantics.symbols.EnumMemberSymbol;
import vsharp.compiler.semantics.symbols.FieldSymbol;
import vsharp.compiler.semantics.symbols.NamedTypeSymbol;
import vsharp.compiler.semantics.types.BuiltinType;
import vsharp.compiler.semantics.types.TypeSymbol;
import vsharp.compiler.syntax.SyntaxKind;
import vsharp.runtime.VsDecimal;
import vsharp.runtime.VsFormat;

/// Constant expression evaluator for V# (C# §12.23).
public final class ConstantEvaluator {

    private ConstantEvaluator() {}

    public sealed interface Result permits Result.Value, Result.Failure {
        record Value(ConstantValue value) implements Result {
            public Value {
                Objects.requireNonNull(value, "value");
            }
        }

        record Failure(DiagnosticCode code) implements Result {
            public Failure {
                Objects.requireNonNull(code, "code");
            }
        }
    }

    /// Try to evaluate a bound expression as a compile-time constant value.
    public static Result evaluate(BoundExpression expr, boolean isChecked) {
        Objects.requireNonNull(expr, "expr");

        if (expr instanceof BoundExpression.Literal lit) {
            return evaluateLiteral(lit);
        }
        if (expr instanceof BoundExpression.Parenthesized p) {
            return evaluate(p.expression(), isChecked);
        }
        if (expr instanceof BoundExpression.Unary u) {
            return evaluateUnary(u, isChecked);
        }
        if (expr instanceof BoundExpression.Binary b) {
            return evaluateBinary(b, isChecked);
        }
        if (expr instanceof BoundExpression.Conditional c) {
            return evaluateConditional(c, isChecked);
        }
        if (expr instanceof BoundExpression.Conversion conversion) {
            return evaluateConversion(conversion, isChecked);
        }
        if (expr instanceof BoundExpression.Value value) {
            return evaluateValue(value);
        }
        if (expr instanceof BoundExpression.Deferred deferred) {
            return evaluateDeferred(deferred, isChecked);
        }

        return new Result.Failure(DiagnosticCode.INTERNAL_ERROR);
    }

    /// Folds a read of a *named* constant, which C# §12.23 lists as a constant expression in
    /// its own right: a simple-name or member-access that references a constant is one.
    ///
    /// An enum member *is* its value - there is no run-time entity to read - and a
    /// `const` field carries the value the constant pass folded for it. Before that
    /// pass existed, a read of a constant was not foldable here at all, which is why the
    /// initializer of a `const` had to be folded by a second, weaker folder over syntax.
    ///
    /// Anything else named by a value - a local, a parameter, an ordinary field - has no
    /// compile-time value and answers `INTERNAL_ERROR`, the evaluator's "not a constant".
    private static Result evaluateValue(BoundExpression.Value value) {
        if (value.symbol() instanceof EnumMemberSymbol member) {
            return new Result.Value(new ConstantValue.EnumVal(member.type(), member.value()));
        }
        if (value.symbol() instanceof FieldSymbol field && field.constantValue() != null) {
            return new Result.Value(field.constantValue());
        }
        return new Result.Failure(DiagnosticCode.INTERNAL_ERROR);
    }

    /// Folds `checked(e)` and `unchecked(e)`, which are operators over an expression
    /// (C# §12.8.20) rather than statements: each selects the overflow rule its operand folds
    /// under, overriding whatever the surrounding context asked for. `unchecked(int.MaxValue
    /// + 1)` is a constant with the wrapped value, and `checked` of the same is the overflow
    /// the enclosing fold would already have reported.
    private static Result evaluateDeferred(BoundExpression.Deferred deferred, boolean isChecked) {
        boolean checkedForm = "checked".equals(deferred.form());
        if ((checkedForm || "unchecked".equals(deferred.form()))
                && deferred.children().size() == 1) {
            return evaluate(deferred.children().getFirst(), checkedForm);
        }
        return new Result.Failure(DiagnosticCode.INTERNAL_ERROR);
    }

    /// Folds a numeric conversion of a constant operand.
    ///
    /// Binary promotion inserts one of these before the operator ever sees its operands, so
    /// `long.MaxValue + 1` arrives as a `long` added to a converted `int`. Without this the
    /// fold answered `INTERNAL_ERROR` and the overflow went unreported, which is the one shape
    /// the design left open.
    ///
    /// The table [#convert] applies is exact in both directions, so a *written* cast folds too
    /// (`const int Truncated = (int)3.9;`) while one whose value the target cannot hold keeps
    /// answering `INTERNAL_ERROR`. That is sound only because of the gate below: the
    /// fold happens under a checked evaluation, where a conversion that loses its value is
    /// already an error at the cast itself.
    private static Result evaluateConversion(BoundExpression.Conversion conversion,
            boolean isChecked) {
        // Folded only for a *checked* evaluation, which is the overflow rule's own context
        //. Every other caller - flow analysis, the interpolation clause reader, the
        // pattern-constant reader - evaluates unchecked and saw `INTERNAL_ERROR` for a
        // conversion before this existed; they keep seeing it, so no lowering shape and no
        // reachability answer moves. A `(int)1` that folded here would erase the explicit
        // `Convert` the IR deliberately preserves.
        if (!isChecked) {
            return new Result.Failure(DiagnosticCode.INTERNAL_ERROR);
        }
        Result operand = evaluate(conversion.operand(), isChecked);
        if (!(operand instanceof Result.Value value)) {
            return operand;
        }
        TypeSymbol source = conversion.operand().type();
        TypeSymbol target = conversion.type();
        if (source == target) {
            return operand;
        }
        ConstantValue converted = convert(value.value(), source, target);
        return converted == null
                ? new Result.Failure(DiagnosticCode.INTERNAL_ERROR)
                : new Result.Value(converted);
    }

    /// The value `constant` takes in `target`, or `null` when that is not a conversion this
    /// evaluator folds.
    ///
    /// This is the compiler's single answer to "what is this constant, viewed as that type?",
    /// used both for a conversion written inside a constant expression and for giving a
    /// `const` declaration the type it was declared with (C# §15.4). Having one answer is the
    /// point: a second, weaker one is what made `const long Ticks = 5;` and `(int)Color.Red`
    /// disagree about what a constant is, depending on where it was written.
    ///
    /// Every numeric entry is exact. A narrowing conversion is folded only when the value is
    /// representable in the target, so a cast whose operand does not fit answers `null` and
    /// the fold reports "not a constant" rather than inventing a wrapped value - which is
    /// sound, because the conversion is folded only under a *checked* evaluation, and a
    /// checked conversion that loses the value is already an error where it is written.
    /// Conversion from a floating or `decimal` constant truncates toward zero, exactly as the
    /// run-time conversion does.
    ///
    /// `char` is here because it shares the `int` carrier in every arithmetic context: binary
    /// numeric promotion inserts exactly this conversion before an operator sees a `char`
    /// operand, so without it `const int Next = 'a' + 1;` has no folded form. An enum constant
    /// is here for the same reason a member *is* its value: C# §10.3.3 makes the
    /// conversion to and from the underlying type explicit in both directions, and
    /// `(int)Color.Red` is how a `const int` names an enum member.
    ///
    /// The signedness of the *source* decides how an `int`-carried value widens: `uint` keeps
    /// its bit pattern in a signed `int`, so `(long)4294967295u` is `4294967295`, not `-1`.
    public static ConstantValue convert(ConstantValue constant, TypeSymbol source,
            TypeSymbol target) {
        Objects.requireNonNull(constant, "constant");
        Objects.requireNonNull(target, "target");
        if (constant instanceof ConstantValue.EnumVal enumValue) {
            if (isEnum(target)) {
                return constant;
            }
            return enumValue.underlyingValue() instanceof Integer underlying
                    ? fromWhole(underlying, target)
                    : null;
        }
        if (isEnum(target)) {
            return constant instanceof ConstantValue.Int i
                    ? new ConstantValue.EnumVal(target, i.value())
                    : null;
        }
        if (constant instanceof ConstantValue.Boolean || constant instanceof ConstantValue.Null
                || constant instanceof ConstantValue.StringVal
                || constant instanceof ConstantValue.ByteArray) {
            // These have exactly one type each, so the only conversion of one is the identity
            // its caller already applied.
            return source == target ? constant : null;
        }
        if (constant instanceof ConstantValue.Double d) {
            return target == BuiltinType.FLOAT
                    ? new ConstantValue.Float((float) d.value())
                    : fromFloating(d.value(), target);
        }
        if (constant instanceof ConstantValue.Float f) {
            return target == BuiltinType.DOUBLE
                    ? new ConstantValue.Double(f.value())
                    : fromFloating(f.value(), target);
        }
        if (constant instanceof ConstantValue.Decimal dec) {
            return fromDecimal(dec.value(), target);
        }
        long whole;
        if (constant instanceof ConstantValue.Int i) {
            whole = isUnsigned(source) ? Integer.toUnsignedLong(i.value()) : i.value();
        } else if (constant instanceof ConstantValue.Long l) {
            whole = l.value();
        } else if (constant instanceof ConstantValue.Char c) {
            whole = c.value();
        } else {
            return null;
        }
        return fromWhole(whole, target);
    }

    /// The constant a whole number takes in `target`, or `null` when it does not fit.
    private static ConstantValue fromWhole(long value, TypeSymbol target) {
        if (isIntCarried(target)) {
            return fits(value, target) ? new ConstantValue.Int((int) value) : null;
        }
        if (isLongCarried(target)) {
            return new ConstantValue.Long(value);
        }
        if (target == BuiltinType.CHAR) {
            return value >= Character.MIN_VALUE && value <= Character.MAX_VALUE
                    ? new ConstantValue.Char((char) value)
                    : null;
        }
        if (target == BuiltinType.DOUBLE) {
            return new ConstantValue.Double(value);
        }
        if (target == BuiltinType.FLOAT) {
            return new ConstantValue.Float(value);
        }
        return target == BuiltinType.DECIMAL
                ? new ConstantValue.Decimal(BigDecimal.valueOf(value))
                : null;
    }

    /// The constant a floating value takes in an integral `target`, truncating toward zero.
    /// A NaN or an infinity has no integral value at all, and neither has one outside the
    /// range of the target, so both answer `null` rather than the run-time conversion's
    /// unspecified result.
    private static ConstantValue fromFloating(double value, TypeSymbol target) {
        if (!Double.isFinite(value)) {
            return null;
        }
        double truncated = value < 0 ? Math.ceil(value) : Math.floor(value);
        if (target == BuiltinType.DECIMAL) {
            return new ConstantValue.Decimal(BigDecimal.valueOf(value));
        }
        return truncated < Long.MIN_VALUE || truncated > Long.MAX_VALUE
                ? null
                : fromWhole((long) truncated, target);
    }

    /// The constant a `decimal` takes in `target`. The scale is dropped by truncation toward
    /// zero for an integral target and kept exactly for a `decimal` one, so the fold never
    /// routes a decimal through a binary floating carrier it cannot represent.
    private static ConstantValue fromDecimal(BigDecimal value, TypeSymbol target) {
        if (target == BuiltinType.DECIMAL) {
            return new ConstantValue.Decimal(value);
        }
        if (target == BuiltinType.DOUBLE) {
            return new ConstantValue.Double(value.doubleValue());
        }
        if (target == BuiltinType.FLOAT) {
            return new ConstantValue.Float(value.floatValue());
        }
        java.math.BigInteger whole = value.toBigInteger();
        return whole.bitLength() > 63 ? null : fromWhole(whole.longValue(), target);
    }

    /// Whether `value` is exactly representable in an `int`-carried builtin type.
    private static boolean fits(long value, TypeSymbol target) {
        return switch (target) {
            case BuiltinType.SBYTE -> value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE;
            case BuiltinType.BYTE -> value >= 0 && value <= 255;
            case BuiltinType.SHORT -> value >= Short.MIN_VALUE && value <= Short.MAX_VALUE;
            case BuiltinType.USHORT -> value >= 0 && value <= 65535;
            case BuiltinType.INT -> value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE;
            case BuiltinType.UINT -> value >= 0 && value <= 0xFFFFFFFFL;
            case null, default -> false;
        };
    }

    /// Whether a type is an enum, whose constants are its underlying integral values.
    private static boolean isEnum(TypeSymbol type) {
        return type instanceof NamedTypeSymbol named
                && named.declaredKind() == NamedTypeSymbol.DeclaredKind.ENUM;
    }

    private static Result evaluateLiteral(BoundExpression.Literal lit) {
        TypeSymbol type = lit.type();
        Object val = lit.value();

        if (type == TypeSymbol.Null.INSTANCE || val == null) {
            return new Result.Value(new ConstantValue.Null());
        }
        if (type == BuiltinType.BOOL && val instanceof Boolean b) {
            return new Result.Value(new ConstantValue.Boolean(b));
        }
        // `uint`/`ulong` share the signed carriers: the lexer already stored the bit pattern
        // (`4294967295u` arrives as `-1`), and unsignedness stays in the type, so folding and
        // rendering consult the operand type rather than the constant's shape.
        // The types below `int` have no literal syntax of their own in C#, but they do have
        // constants (`byte.MaxValue`), and every one of them is carried in the `int` shape.
        if (isIntCarried(type) && val instanceof Integer i) {
            return new Result.Value(new ConstantValue.Int(i));
        }
        if (isLongCarried(type) && val instanceof Long l) {
            return new Result.Value(new ConstantValue.Long(l));
        }
        if (type == BuiltinType.FLOAT && val instanceof Float f) {
            return new Result.Value(new ConstantValue.Float(f));
        }
        if (type == BuiltinType.DOUBLE && val instanceof Double d) {
            return new Result.Value(new ConstantValue.Double(d));
        }
        if (type == BuiltinType.DECIMAL && val instanceof BigDecimal bd) {
            try {
                return new Result.Value(new ConstantValue.Decimal(VsDecimal.fromLiteral(bd)));
            } catch (ArithmeticException ex) {
                return new Result.Failure(DiagnosticCode.DECIMAL_LITERAL_OVERFLOW);
            }
        }
        if (type == BuiltinType.CHAR && val instanceof Character c) {
            return new Result.Value(new ConstantValue.Char(c));
        }
        if (type == BuiltinType.STRING && val instanceof String s) {
            return new Result.Value(new ConstantValue.StringVal(s));
        }
        if (type instanceof TypeSymbol.Array array
                && array.elementType() == BuiltinType.BYTE
                && val instanceof byte[] bytes) {
            return new Result.Value(new ConstantValue.ByteArray(bytes));
        }

        return new Result.Failure(DiagnosticCode.INTERNAL_ERROR);
    }

    /// The builtin types whose constants are held in an `int`, signed or not.
    private static boolean isIntCarried(TypeSymbol type) {
        return type == BuiltinType.SBYTE || type == BuiltinType.BYTE || type == BuiltinType.SHORT
                || type == BuiltinType.USHORT || type == BuiltinType.INT
                || type == BuiltinType.UINT;
    }

    /// The builtin types whose constants are held in a `long`. `nint`/`nuint` are 64-bit in
    /// V# by declaration (R5), so their constants share the `long` shape.
    private static boolean isLongCarried(TypeSymbol type) {
        return type == BuiltinType.LONG || type == BuiltinType.ULONG || type == BuiltinType.NINT
                || type == BuiltinType.NUINT;
    }

    private static Result evaluateUnary(BoundExpression.Unary u, boolean isChecked) {
        Result sub = evaluate(u.operand(), isChecked);
        if (sub instanceof Result.Failure f) {
            return f;
        }

        ConstantValue val = ((Result.Value) sub).value();
        SyntaxKind op = u.operator();

        try {
            if (op == SyntaxKind.PLUS) {
                return new Result.Value(val);
            }
            if (op == SyntaxKind.MINUS) {
                // C# negates in the operator's *result* type (§12.9.3), and `-uint` is a
                // `long`. A `uint` constant is carried in the 32 bits of `ConstantValue.Int`
                // as an unsigned magnitude, so negating it as an `int` reports an overflow
                // C# does not have: `-2147483648` is exactly that shape, because the literal
                // `2147483648` is a `uint`. Widen first, then negate, and the result cannot
                // overflow a `long`.
                if (val instanceof ConstantValue.Int i && isUnsigned(u.operand().type())) {
                    return new Result.Value(
                            new ConstantValue.Long(-Integer.toUnsignedLong(i.value())));
                }
                if (val instanceof ConstantValue.Int i) {
                    int r = isChecked ? Math.negateExact(i.value()) : -i.value();
                    return new Result.Value(new ConstantValue.Int(r));
                }
                if (val instanceof ConstantValue.Long l) {
                    long r = isChecked ? Math.negateExact(l.value()) : -l.value();
                    return new Result.Value(new ConstantValue.Long(r));
                }
                if (val instanceof ConstantValue.Float f) {
                    return new Result.Value(new ConstantValue.Float(-f.value()));
                }
                if (val instanceof ConstantValue.Double d) {
                    return new Result.Value(new ConstantValue.Double(-d.value()));
                }
                if (val instanceof ConstantValue.Decimal dec) {
                    return new Result.Value(new ConstantValue.Decimal(dec.value().negate()));
                }
            }
            if (op == SyntaxKind.TILDE) {
                if (val instanceof ConstantValue.Int i) {
                    return new Result.Value(new ConstantValue.Int(~i.value()));
                }
                if (val instanceof ConstantValue.Long l) {
                    return new Result.Value(new ConstantValue.Long(~l.value()));
                }
            }
            if (op == SyntaxKind.EXCLAMATION) {
                if (val instanceof ConstantValue.Boolean b) {
                    return new Result.Value(new ConstantValue.Boolean(!b.value()));
                }
            }
        } catch (ArithmeticException ex) {
            return new Result.Failure(DiagnosticCode.COMPILE_TIME_OVERFLOW);
        }

        return new Result.Failure(DiagnosticCode.INTERNAL_ERROR);
    }

    private static Result evaluateBinary(BoundExpression.Binary b, boolean isChecked) {
        Result leftRes = evaluate(b.left(), isChecked);
        if (leftRes instanceof Result.Failure f) {
            return f;
        }
        Result rightRes = evaluate(b.right(), isChecked);
        if (rightRes instanceof Result.Failure f) {
            return f;
        }

        ConstantValue left = ((Result.Value) leftRes).value();
        ConstantValue right = ((Result.Value) rightRes).value();
        SyntaxKind op = b.operator();

        // String concatenation
        if (op == SyntaxKind.PLUS && (left instanceof ConstantValue.StringVal || right instanceof ConstantValue.StringVal)) {
            // An enum operand is refused rather than rendered. `"S: " + Status.Draft` prints the
            // member *name*, not its number, and the name is not reachable from a folded
            // value: [ConstantValue.EnumVal] carries the enum type and the underlying number,
            // while the name lives in the member table - and no name exists at all for a value
            // outside the members. Folding here would silently print `0` for `Draft`. C# agrees
            // that nothing is lost: a constant expression of type string admits only string and
            // null constant operands (§12.23), so this concatenation was never a constant. The
            // ordinary run-time path renders it correctly.
            if (left instanceof ConstantValue.EnumVal || right instanceof ConstantValue.EnumVal) {
                return new Result.Failure(DiagnosticCode.INTERNAL_ERROR);
            }
            String lStr = stringify(left, b.left().type());
            String rStr = stringify(right, b.right().type());
            return new Result.Value(new ConstantValue.StringVal(lStr + rStr));
        }

        // The left operand carries the signedness of the operation: binary numeric promotion
        // has already given both operands the same type, and for a shift it is the shifted
        // value - not the count - that decides whether `>>` is arithmetic or logical.
        boolean unsigned = isUnsigned(b.left().type());

        try {
            if (left instanceof ConstantValue.Int l && right instanceof ConstantValue.Int r) {
                return unsigned
                        ? evaluateUIntBinary(l.value(), r.value(), op, isChecked)
                        : evaluateIntBinary(l.value(), r.value(), op, isChecked);
            }
            if (left instanceof ConstantValue.Long l && right instanceof ConstantValue.Long r) {
                return unsigned
                        ? evaluateULongBinary(l.value(), r.value(), op, isChecked)
                        : evaluateLongBinary(l.value(), r.value(), op, isChecked);
            }
            if (left instanceof ConstantValue.Float l && right instanceof ConstantValue.Float r) {
                return evaluateFloatBinary(l.value(), r.value(), op);
            }
            if (left instanceof ConstantValue.Double l && right instanceof ConstantValue.Double r) {
                return evaluateDoubleBinary(l.value(), r.value(), op);
            }
            if (left instanceof ConstantValue.Decimal l && right instanceof ConstantValue.Decimal r) {
                return evaluateDecimalBinary(l.value(), r.value(), op);
            }
            if (left instanceof ConstantValue.Boolean l && right instanceof ConstantValue.Boolean r) {
                return evaluateBooleanBinary(l.value(), r.value(), op);
            }
        } catch (ArithmeticException ex) {
            return new Result.Failure(DiagnosticCode.COMPILE_TIME_OVERFLOW);
        }

        return new Result.Failure(DiagnosticCode.INTERNAL_ERROR);
    }

    /// Whether a type keeps its values unsigned while sharing a signed JVM carrier.
    ///
    /// `byte`/`ushort` are absent because binary numeric promotion widens them to `int`
    /// before any operator sees them (C# §12.4.7), so they never reach folding as themselves.
    private static boolean isUnsigned(TypeSymbol type) {
        return type == BuiltinType.UINT || type == BuiltinType.ULONG || type == BuiltinType.NUINT;
    }

    /// Folds a `uint` operator. The carrier is the signed `int` holding the bit pattern, so
    /// every ordering, division and right shift must be the unsigned form; `4294967295u / 2`
    /// is `2147483647`, not the `0` that a signed division of `-1` would produce.
    private static Result evaluateUIntBinary(int l, int r, SyntaxKind op, boolean isChecked) {
        long ul = Integer.toUnsignedLong(l);
        long ur = Integer.toUnsignedLong(r);
        switch (op) {
            case PLUS -> {
                return checkedUInt(ul + ur, isChecked);
            }
            case MINUS -> {
                return checkedUInt(ul - ur, isChecked);
            }
            case ASTERISK -> {
                return checkedUInt(ul * ur, isChecked);
            }
            case SLASH -> {
                if (r == 0) {
                    return new Result.Failure(DiagnosticCode.DIVISION_BY_ZERO);
                }
                return new Result.Value(new ConstantValue.Int(Integer.divideUnsigned(l, r)));
            }
            case PERCENT -> {
                if (r == 0) {
                    return new Result.Failure(DiagnosticCode.DIVISION_BY_ZERO);
                }
                return new Result.Value(new ConstantValue.Int(Integer.remainderUnsigned(l, r)));
            }
            case AMPERSAND -> {
                return new Result.Value(new ConstantValue.Int(l & r));
            }
            case BAR -> {
                return new Result.Value(new ConstantValue.Int(l | r));
            }
            case CARET -> {
                return new Result.Value(new ConstantValue.Int(l ^ r));
            }
            case LESS_THAN_LESS_THAN -> {
                return new Result.Value(new ConstantValue.Int(l << (r & 31)));
            }
            // `>>` on a `uint` is a logical shift in C#, so it agrees with `>>>`.
            case GREATER_THAN_GREATER_THAN, GREATER_THAN_GREATER_THAN_GREATER_THAN -> {
                return new Result.Value(new ConstantValue.Int(l >>> (r & 31)));
            }
            case EQUALS_EQUALS -> {
                return new Result.Value(new ConstantValue.Boolean(l == r));
            }
            case EXCLAMATION_EQUALS -> {
                return new Result.Value(new ConstantValue.Boolean(l != r));
            }
            case LESS_THAN -> {
                return new Result.Value(new ConstantValue.Boolean(ul < ur));
            }
            case LESS_THAN_EQUALS -> {
                return new Result.Value(new ConstantValue.Boolean(ul <= ur));
            }
            case GREATER_THAN -> {
                return new Result.Value(new ConstantValue.Boolean(ul > ur));
            }
            case GREATER_THAN_EQUALS -> {
                return new Result.Value(new ConstantValue.Boolean(ul >= ur));
            }
            default -> {}
        }
        return new Result.Failure(DiagnosticCode.INTERNAL_ERROR);
    }

    /// Narrows a widened `uint` result, reporting overflow only in a `checked` context.
    private static Result checkedUInt(long wide, boolean isChecked) {
        if (isChecked && (wide < 0L || wide > 0xFFFF_FFFFL)) {
            return new Result.Failure(DiagnosticCode.COMPILE_TIME_OVERFLOW);
        }
        return new Result.Value(new ConstantValue.Int((int) wide));
    }

    /// Folds a `ulong` operator over the signed `long` carrier. There is no wider type to
    /// widen into, so overflow is detected from the wrapped result itself.
    private static Result evaluateULongBinary(long l, long r, SyntaxKind op, boolean isChecked) {
        switch (op) {
            case PLUS -> {
                long sum = l + r;
                if (isChecked && java.lang.Long.compareUnsigned(sum, l) < 0) {
                    return new Result.Failure(DiagnosticCode.COMPILE_TIME_OVERFLOW);
                }
                return new Result.Value(new ConstantValue.Long(sum));
            }
            case MINUS -> {
                if (isChecked && java.lang.Long.compareUnsigned(l, r) < 0) {
                    return new Result.Failure(DiagnosticCode.COMPILE_TIME_OVERFLOW);
                }
                return new Result.Value(new ConstantValue.Long(l - r));
            }
            case ASTERISK -> {
                if (isChecked && Math.unsignedMultiplyHigh(l, r) != 0L) {
                    return new Result.Failure(DiagnosticCode.COMPILE_TIME_OVERFLOW);
                }
                return new Result.Value(new ConstantValue.Long(l * r));
            }
            case SLASH -> {
                if (r == 0L) {
                    return new Result.Failure(DiagnosticCode.DIVISION_BY_ZERO);
                }
                return new Result.Value(
                        new ConstantValue.Long(java.lang.Long.divideUnsigned(l, r)));
            }
            case PERCENT -> {
                if (r == 0L) {
                    return new Result.Failure(DiagnosticCode.DIVISION_BY_ZERO);
                }
                return new Result.Value(
                        new ConstantValue.Long(java.lang.Long.remainderUnsigned(l, r)));
            }
            case AMPERSAND -> {
                return new Result.Value(new ConstantValue.Long(l & r));
            }
            case BAR -> {
                return new Result.Value(new ConstantValue.Long(l | r));
            }
            case CARET -> {
                return new Result.Value(new ConstantValue.Long(l ^ r));
            }
            case LESS_THAN_LESS_THAN -> {
                return new Result.Value(new ConstantValue.Long(l << (r & 63)));
            }
            case GREATER_THAN_GREATER_THAN, GREATER_THAN_GREATER_THAN_GREATER_THAN -> {
                return new Result.Value(new ConstantValue.Long(l >>> (r & 63)));
            }
            case EQUALS_EQUALS -> {
                return new Result.Value(new ConstantValue.Boolean(l == r));
            }
            case EXCLAMATION_EQUALS -> {
                return new Result.Value(new ConstantValue.Boolean(l != r));
            }
            case LESS_THAN -> {
                return new Result.Value(
                        new ConstantValue.Boolean(java.lang.Long.compareUnsigned(l, r) < 0));
            }
            case LESS_THAN_EQUALS -> {
                return new Result.Value(
                        new ConstantValue.Boolean(java.lang.Long.compareUnsigned(l, r) <= 0));
            }
            case GREATER_THAN -> {
                return new Result.Value(
                        new ConstantValue.Boolean(java.lang.Long.compareUnsigned(l, r) > 0));
            }
            case GREATER_THAN_EQUALS -> {
                return new Result.Value(
                        new ConstantValue.Boolean(java.lang.Long.compareUnsigned(l, r) >= 0));
            }
            default -> {}
        }
        return new Result.Failure(DiagnosticCode.INTERNAL_ERROR);
    }

    private static Result evaluateIntBinary(int l, int r, SyntaxKind op, boolean isChecked) {
        switch (op) {
            case PLUS -> {
                int res = isChecked ? Math.addExact(l, r) : (l + r);
                return new Result.Value(new ConstantValue.Int(res));
            }
            case MINUS -> {
                int res = isChecked ? Math.subtractExact(l, r) : (l - r);
                return new Result.Value(new ConstantValue.Int(res));
            }
            case ASTERISK -> {
                int res = isChecked ? Math.multiplyExact(l, r) : (l * r);
                return new Result.Value(new ConstantValue.Int(res));
            }
            case SLASH -> {
                if (r == 0) return new Result.Failure(DiagnosticCode.DIVISION_BY_ZERO);
                if (isChecked && l == Integer.MIN_VALUE && r == -1) {
                    return new Result.Failure(DiagnosticCode.COMPILE_TIME_OVERFLOW);
                }
                return new Result.Value(new ConstantValue.Int(l / r));
            }
            case PERCENT -> {
                if (r == 0) return new Result.Failure(DiagnosticCode.DIVISION_BY_ZERO);
                return new Result.Value(new ConstantValue.Int(l % r));
            }
            case AMPERSAND -> { return new Result.Value(new ConstantValue.Int(l & r)); }
            case BAR -> { return new Result.Value(new ConstantValue.Int(l | r)); }
            case CARET -> { return new Result.Value(new ConstantValue.Int(l ^ r)); }
            case LESS_THAN_LESS_THAN -> { return new Result.Value(new ConstantValue.Int(l << (r & 31))); }
            case GREATER_THAN_GREATER_THAN -> { return new Result.Value(new ConstantValue.Int(l >> (r & 31))); }
            case GREATER_THAN_GREATER_THAN_GREATER_THAN -> { return new Result.Value(new ConstantValue.Int(l >>> (r & 31))); }
            case EQUALS_EQUALS -> { return new Result.Value(new ConstantValue.Boolean(l == r)); }
            case EXCLAMATION_EQUALS -> { return new Result.Value(new ConstantValue.Boolean(l != r)); }
            case LESS_THAN -> { return new Result.Value(new ConstantValue.Boolean(l < r)); }
            case LESS_THAN_EQUALS -> { return new Result.Value(new ConstantValue.Boolean(l <= r)); }
            case GREATER_THAN -> { return new Result.Value(new ConstantValue.Boolean(l > r)); }
            case GREATER_THAN_EQUALS -> { return new Result.Value(new ConstantValue.Boolean(l >= r)); }
            default -> {}
        }
        return new Result.Failure(DiagnosticCode.INTERNAL_ERROR);
    }

    private static Result evaluateLongBinary(long l, long r, SyntaxKind op, boolean isChecked) {
        switch (op) {
            case PLUS -> {
                long res = isChecked ? Math.addExact(l, r) : (l + r);
                return new Result.Value(new ConstantValue.Long(res));
            }
            case MINUS -> {
                long res = isChecked ? Math.subtractExact(l, r) : (l - r);
                return new Result.Value(new ConstantValue.Long(res));
            }
            case ASTERISK -> {
                long res = isChecked ? Math.multiplyExact(l, r) : (l * r);
                return new Result.Value(new ConstantValue.Long(res));
            }
            case SLASH -> {
                if (r == 0) return new Result.Failure(DiagnosticCode.DIVISION_BY_ZERO);
                if (isChecked && l == Long.MIN_VALUE && r == -1) {
                    return new Result.Failure(DiagnosticCode.COMPILE_TIME_OVERFLOW);
                }
                return new Result.Value(new ConstantValue.Long(l / r));
            }
            case PERCENT -> {
                if (r == 0) return new Result.Failure(DiagnosticCode.DIVISION_BY_ZERO);
                return new Result.Value(new ConstantValue.Long(l % r));
            }
            case AMPERSAND -> { return new Result.Value(new ConstantValue.Long(l & r)); }
            case BAR -> { return new Result.Value(new ConstantValue.Long(l | r)); }
            case CARET -> { return new Result.Value(new ConstantValue.Long(l ^ r)); }
            case LESS_THAN_LESS_THAN -> { return new Result.Value(new ConstantValue.Long(l << (r & 63))); }
            case GREATER_THAN_GREATER_THAN -> { return new Result.Value(new ConstantValue.Long(l >> (r & 63))); }
            case GREATER_THAN_GREATER_THAN_GREATER_THAN -> { return new Result.Value(new ConstantValue.Long(l >>> (r & 63))); }
            case EQUALS_EQUALS -> { return new Result.Value(new ConstantValue.Boolean(l == r)); }
            case EXCLAMATION_EQUALS -> { return new Result.Value(new ConstantValue.Boolean(l != r)); }
            case LESS_THAN -> { return new Result.Value(new ConstantValue.Boolean(l < r)); }
            case LESS_THAN_EQUALS -> { return new Result.Value(new ConstantValue.Boolean(l <= r)); }
            case GREATER_THAN -> { return new Result.Value(new ConstantValue.Boolean(l > r)); }
            case GREATER_THAN_EQUALS -> { return new Result.Value(new ConstantValue.Boolean(l >= r)); }
            default -> {}
        }
        return new Result.Failure(DiagnosticCode.INTERNAL_ERROR);
    }

    private static Result evaluateFloatBinary(float l, float r, SyntaxKind op) {
        switch (op) {
            case PLUS -> { return new Result.Value(new ConstantValue.Float(l + r)); }
            case MINUS -> { return new Result.Value(new ConstantValue.Float(l - r)); }
            case ASTERISK -> { return new Result.Value(new ConstantValue.Float(l * r)); }
            case SLASH -> { return new Result.Value(new ConstantValue.Float(l / r)); }
            case PERCENT -> { return new Result.Value(new ConstantValue.Float(l % r)); }
            case EQUALS_EQUALS -> { return new Result.Value(new ConstantValue.Boolean(l == r)); }
            case EXCLAMATION_EQUALS -> { return new Result.Value(new ConstantValue.Boolean(l != r)); }
            case LESS_THAN -> { return new Result.Value(new ConstantValue.Boolean(l < r)); }
            case LESS_THAN_EQUALS -> { return new Result.Value(new ConstantValue.Boolean(l <= r)); }
            case GREATER_THAN -> { return new Result.Value(new ConstantValue.Boolean(l > r)); }
            case GREATER_THAN_EQUALS -> { return new Result.Value(new ConstantValue.Boolean(l >= r)); }
            default -> {}
        }
        return new Result.Failure(DiagnosticCode.INTERNAL_ERROR);
    }

    private static Result evaluateDoubleBinary(double l, double r, SyntaxKind op) {
        switch (op) {
            case PLUS -> { return new Result.Value(new ConstantValue.Double(l + r)); }
            case MINUS -> { return new Result.Value(new ConstantValue.Double(l - r)); }
            case ASTERISK -> { return new Result.Value(new ConstantValue.Double(l * r)); }
            case SLASH -> { return new Result.Value(new ConstantValue.Double(l / r)); }
            case PERCENT -> { return new Result.Value(new ConstantValue.Double(l % r)); }
            case EQUALS_EQUALS -> { return new Result.Value(new ConstantValue.Boolean(l == r)); }
            case EXCLAMATION_EQUALS -> { return new Result.Value(new ConstantValue.Boolean(l != r)); }
            case LESS_THAN -> { return new Result.Value(new ConstantValue.Boolean(l < r)); }
            case LESS_THAN_EQUALS -> { return new Result.Value(new ConstantValue.Boolean(l <= r)); }
            case GREATER_THAN -> { return new Result.Value(new ConstantValue.Boolean(l > r)); }
            case GREATER_THAN_EQUALS -> { return new Result.Value(new ConstantValue.Boolean(l >= r)); }
            default -> {}
        }
        return new Result.Failure(DiagnosticCode.INTERNAL_ERROR);
    }

    private static Result evaluateDecimalBinary(BigDecimal l, BigDecimal r, SyntaxKind op) {
        switch (op) {
            case PLUS -> { return new Result.Value(new ConstantValue.Decimal(VsDecimal.add(l, r))); }
            case MINUS -> { return new Result.Value(new ConstantValue.Decimal(VsDecimal.subtract(l, r))); }
            case ASTERISK -> { return new Result.Value(new ConstantValue.Decimal(VsDecimal.multiply(l, r))); }
            case SLASH -> {
                if (r.signum() == 0) return new Result.Failure(DiagnosticCode.DIVISION_BY_ZERO);
                return new Result.Value(new ConstantValue.Decimal(VsDecimal.divide(l, r)));
            }
            case PERCENT -> {
                if (r.signum() == 0) return new Result.Failure(DiagnosticCode.DIVISION_BY_ZERO);
                return new Result.Value(new ConstantValue.Decimal(VsDecimal.remainder(l, r)));
            }
            case EQUALS_EQUALS -> { return new Result.Value(new ConstantValue.Boolean(VsDecimal.compare(l, r) == 0)); }
            case EXCLAMATION_EQUALS -> { return new Result.Value(new ConstantValue.Boolean(VsDecimal.compare(l, r) != 0)); }
            case LESS_THAN -> { return new Result.Value(new ConstantValue.Boolean(VsDecimal.compare(l, r) < 0)); }
            case LESS_THAN_EQUALS -> { return new Result.Value(new ConstantValue.Boolean(VsDecimal.compare(l, r) <= 0)); }
            case GREATER_THAN -> { return new Result.Value(new ConstantValue.Boolean(VsDecimal.compare(l, r) > 0)); }
            case GREATER_THAN_EQUALS -> { return new Result.Value(new ConstantValue.Boolean(VsDecimal.compare(l, r) >= 0)); }
            default -> {}
        }
        return new Result.Failure(DiagnosticCode.INTERNAL_ERROR);
    }

    /// Folds a `bool` operator.
    ///
    /// `&&` and `||` are here with their non-short-circuiting siblings because C# §12.23 lists
    /// both forms as constant expressions, and over two constants they *are* the same
    /// function: both operands were already folded before this is reached, and a fold that
    /// fails on either side is the same failure whichever operator joined them. The eager
    /// order also matches where the operator's own diagnostics are reported - a division by a
    /// constant zero is reported at the division, whether or not a `false &&` precedes it.
    private static Result evaluateBooleanBinary(boolean l, boolean r, SyntaxKind op) {
        switch (op) {
            case AMPERSAND, AMPERSAND_AMPERSAND -> {
                return new Result.Value(new ConstantValue.Boolean(l & r));
            }
            case BAR, BAR_BAR -> { return new Result.Value(new ConstantValue.Boolean(l | r)); }
            case CARET -> { return new Result.Value(new ConstantValue.Boolean(l ^ r)); }
            case EQUALS_EQUALS -> { return new Result.Value(new ConstantValue.Boolean(l == r)); }
            case EXCLAMATION_EQUALS -> { return new Result.Value(new ConstantValue.Boolean(l != r)); }
            default -> {}
        }
        return new Result.Failure(DiagnosticCode.INTERNAL_ERROR);
    }

    private static Result evaluateConditional(BoundExpression.Conditional c, boolean isChecked) {
        Result condRes = evaluate(c.condition(), isChecked);
        if (condRes instanceof Result.Failure f) {
            return f;
        }

        ConstantValue condVal = ((Result.Value) condRes).value();
        if (condVal instanceof ConstantValue.Boolean b) {
            return evaluate(b.value() ? c.whenTrue() : c.whenFalse(), isChecked);
        }

        return new Result.Failure(DiagnosticCode.INTERNAL_ERROR);
    }

    /// Renders a folded operand of a string concatenation.
    ///
    /// Folding a concatenation at compile time must produce exactly the text the same
    /// concatenation would produce at run time, so this defers to [VsFormat] - the single
    /// place where C# rendering is defined - instead of to Java's `String.valueOf`,
    /// which disagrees on `bool` (`true` vs `True`), `float` and `double`. The unsigned types
    /// are the one case the value alone cannot answer: `4294967295u` and `-1` share a carrier.
    private static String stringify(ConstantValue v, TypeSymbol type) {
        return switch (v) {
            case ConstantValue.Null n -> "";
            case ConstantValue.Boolean b -> VsFormat.toDisplayString(b.value());
            case ConstantValue.Int i -> type == BuiltinType.UINT
                    ? VsFormat.toDisplayStringUnsigned(i.value())
                    : VsFormat.toDisplayString(i.value());
            case ConstantValue.Long l -> type == BuiltinType.ULONG || type == BuiltinType.NUINT
                    ? VsFormat.toDisplayStringUnsigned(l.value())
                    : VsFormat.toDisplayString(l.value());
            case ConstantValue.Float f -> VsFormat.toDisplayString(f.value());
            case ConstantValue.Double d -> VsFormat.toDisplayString(d.value());
            case ConstantValue.Decimal dec -> VsDecimal.toDisplayString(dec.value());
            case ConstantValue.Char c -> VsFormat.toDisplayString(c.value());
            case ConstantValue.StringVal s -> s.value();
            case ConstantValue.EnumVal ignored -> throw new IllegalArgumentException(
                    "an enum operand renders its member name, which a folded value cannot reach");
            case ConstantValue.ByteArray ignored -> throw new IllegalArgumentException(
                    "a UTF-8 literal byte array is not a string operand");
        };
    }
}
