package vsharp.compiler.ir;

import java.util.List;
import java.util.Objects;
import vsharp.compiler.semantics.binding.BoundExpression;
import vsharp.compiler.semantics.binding.SemanticModel;
import vsharp.compiler.semantics.constants.ConstantEvaluator;
import vsharp.compiler.semantics.constants.ConstantValue;
import vsharp.compiler.semantics.symbols.Symbol;
import vsharp.compiler.syntax.SyntaxKind;

/// Lowers a successfully bound expression into explicit IR operations.
///
/// This is intentionally independent of statement lowering. It makes conversion nodes and
/// compound-assignment read/modify/write structure visible now; the next increment gives the
/// expressions control-flow context, local slots and labels.
public final class ExpressionLowerer {

    /// Lowers a bound node whose form is only representable from its syntax.
    ///
    /// Several constructs (interpolated strings, array creation, `default`, `as`, switch
    /// expressions, ...) bind to [BoundExpression.Deferred] and are lowered by
    /// [SyntaxExpressionLowerer] from the syntax tree instead. A deferred node can appear
    /// anywhere an expression can - including as a call argument - so the bound-driven walk
    /// needs a way back into the syntax-driven one.
    @FunctionalInterface
    public interface DeferredLowerer {
        /// Returns the IR for `deferred`, or `null` when its syntax cannot be recovered.
        IrExpression lowerDeferred(BoundExpression.Deferred deferred);
    }

    private final DeferredLowerer deferredLowerer;

    /// The model that decided every `record struct`'s positional shape, or `null` for
    /// the model-free entry point below. Only deconstruction consults it: taking a record
    /// apart reads its component fields, and which fields those are - and in which order - is
    /// a binding decision, not something the IR can re-derive from a type.
    private final SemanticModel model;

    private ExpressionLowerer(DeferredLowerer deferredLowerer, SemanticModel model) {
        this.deferredLowerer = deferredLowerer;
        this.model = model;
    }

    /// Lowers a bound expression that is known to contain no deferred nodes. A deferred node
    /// reached through this entry point throws, because there is no syntax to fall back to.
    public static IrExpression lower(BoundExpression expression) {
        Objects.requireNonNull(expression, "expression");
        return new ExpressionLowerer(null, null).lowerUnchecked(expression);
    }

    /// Lowers a bound expression, handing any deferred node back to `deferredLowerer`.
    static IrExpression lower(BoundExpression expression, DeferredLowerer deferredLowerer,
            SemanticModel model) {
        Objects.requireNonNull(expression, "expression");
        Objects.requireNonNull(deferredLowerer, "deferredLowerer");
        Objects.requireNonNull(model, "model");
        return new ExpressionLowerer(deferredLowerer, model).lowerUnchecked(expression);
    }

    /// The component fields a deconstruction of `type` reads, empty when the value is a tuple
    /// (or when no model is available, which is only the model-free entry point above).
    private List<vsharp.compiler.semantics.symbols.FieldSymbol> positionalComponents(
            vsharp.compiler.semantics.types.TypeSymbol type) {
        if (model == null || !(type instanceof vsharp.compiler.semantics.symbols.NamedTypeSymbol named)) {
            return List.of();
        }
        return model.positionalLayout(named)
                .map(vsharp.compiler.semantics.binding.PositionalLayout::components)
                .orElseGet(List::of);
    }

    private IrExpression lowerUnchecked(BoundExpression expression) {
        ConstantEvaluator.Result constant = ConstantEvaluator.evaluate(expression, false);
        if (constant instanceof ConstantEvaluator.Result.Value value) {
            return new IrExpression.Constant(IrValueType.of(expression.type()),
                    constantValue(value.value()));
        }

        return switch (expression) {
            // An enum member is its value: there is no run-time entity to read, so it lowers
            // to the constant it names rather than to a load of something that does not exist
            //. This is also what lets a `case Color.Red:` label reach the backend as the
            // int constant a switch dispatches on.
            case BoundExpression.Value value when value.symbol()
                    instanceof vsharp.compiler.semantics.symbols.EnumMemberSymbol member ->
                    new IrExpression.Constant(IrValueType.of(value.type()), member.value());
            case BoundExpression.Value value when value.symbol()
                    instanceof vsharp.compiler.semantics.symbols.FieldSymbol field && field.isConstant() ->
                    new IrExpression.Constant(IrValueType.of(value.type()), constantValue(field.constantValue()));
            case BoundExpression.Value value -> new IrExpression.Load(IrValueType.of(value.type()),
                    value.symbol());
            case BoundExpression.Parenthesized parenthesized -> lowerUnchecked(
                    parenthesized.expression());
            case BoundExpression.Await await -> new IrExpression.Await(
                    IrValueType.of(await.type()), lowerUnchecked(await.operand()));
            case BoundExpression.Tuple tuple -> new IrExpression.Tuple(IrValueType.of(tuple.type()),
                    tuple.elements().stream().map(this::lowerUnchecked).toList());
            case BoundExpression.Unary unary -> new IrExpression.Unary(IrValueType.of(unary.type()),
                    IrOperators.unary(unary.operator()), lowerUnchecked(unary.operand()));
            case BoundExpression.Binary binary -> new IrExpression.Binary(IrValueType.of(binary.type()),
                    lowerUnchecked(binary.left()), IrOperators.binary(binary.operator()),
                    lowerUnchecked(binary.right()));
            case BoundExpression.Mutation mutation -> lowerMutation(mutation);
            case BoundExpression.Assignment assignment -> lowerAssignment(assignment);
            case BoundExpression.Conditional conditional -> new IrExpression.Conditional(
                    IrValueType.of(conditional.type()), lowerUnchecked(conditional.condition()),
                    lowerUnchecked(conditional.whenTrue()), lowerUnchecked(conditional.whenFalse()));
            case BoundExpression.Conversion conversion -> new IrExpression.Convert(
                    IrValueType.of(conversion.type()), conversion.conversion(),
                    lowerUnchecked(conversion.operand()));

            case BoundExpression.Call call -> {
                IrExpression receiver = call.receiver() != null
                        && !call.function().modifiers().contains(
                                vsharp.compiler.syntax.SyntaxKind.STATIC)
                        ? lowerUnchecked(call.receiver()) : null;
                yield new IrExpression.Call(IrValueType.of(call.type()), receiver,
                        call.function(), call.declaration(),
                        call.arguments().stream().map(this::lowerUnchecked).toList(),
                        call.argumentParameterOrdinals());
            }
            case BoundExpression.ObjectCreation creation -> new IrExpression.ObjectCreation(
                    IrValueType.of(creation.type()), creation.constructor(),
                    creation.arguments().stream().map(this::lowerUnchecked).toList());
            case BoundExpression.MemberAccess member -> {
                if (member.member() instanceof vsharp.compiler.semantics.symbols.FieldSymbol field) {
                    yield new IrExpression.FieldLoad(IrValueType.of(field.type()), lowerUnchecked(member.receiver()), field);
                }
                yield null;
            }
            case BoundExpression.ElementAccess access -> new IrExpression.ElementLoad(
                    IrValueType.of(access.type()), lowerUnchecked(access.receiver()),
                    access.arguments().stream().map(this::lowerUnchecked).toList());
            case BoundExpression.IndexAccess access -> new IrExpression.IndexElementLoad(
                    IrValueType.of(access.type()), lowerUnchecked(access.receiver()),
                    lowerUnchecked(access.index()));
            case BoundExpression.SliceAccess access -> new IrExpression.SliceElementLoad(
                    IrValueType.of(access.type()), lowerUnchecked(access.receiver()),
                    lowerUnchecked(access.range()));
            case BoundExpression.TupleElement element -> new IrExpression.TupleElementLoad(
                    IrValueType.of(element.type()), lowerUnchecked(element.receiver()),
                    element.index());
            case BoundExpression.ArrayLength length -> new IrExpression.ArrayLength(
                    IrValueType.of(length.type()), lowerUnchecked(length.receiver()));
            case BoundExpression.StringLength length -> new IrExpression.StringLength(
                    IrValueType.of(length.type()), lowerUnchecked(length.receiver()));
            case BoundExpression.StringElementAccess access -> new IrExpression.StringElementLoad(
                    IrValueType.of(access.type()), lowerUnchecked(access.receiver()),
                    lowerUnchecked(access.index()));
            case BoundExpression.ConditionalReceiver receiver ->
                    new IrExpression.ConditionalReceiver(IrValueType.of(receiver.type()),
                            IrValueType.of(receiver.carrierType()));
            case BoundExpression.NullConditional conditional ->
                    new IrExpression.NullConditional(IrValueType.of(conditional.type()),
                            lowerUnchecked(conditional.receiver()),
                            lowerUnchecked(conditional.access()));
            case BoundExpression.NullableHasValue hasValue -> new IrExpression.NullableHasValue(
                    IrValueType.of(hasValue.type()), lowerUnchecked(hasValue.receiver()));
            case BoundExpression.NullableValue value -> new IrExpression.NullableValue(
                    IrValueType.of(value.type()), lowerUnchecked(value.receiver()));
            case BoundExpression.Collection collection -> new IrExpression.Collection(
                    IrValueType.of(collection.type()), collection.elements().stream().map(element ->
                            new IrCollectionElement(element.spread(), lowerUnchecked(element.expression())))
                            .toList());
            // A lambda became a functional-interface value in binding. The body is
            // emitted as its own static method; here only the call site that creates the
            // instance is lowered, which is why it carries no operand.
            case BoundExpression.Lambda lambda -> new IrExpression.FunctionalValue(
                    IrValueType.of(lambda.type()), lambda.function(), lambda.interfaceMethod(),
                    model == null ? lambda.interfaceMethod()
                            : model.javaInterop().erasedDeclaration(lambda.interfaceMethod()));
            case BoundExpression.TypeOperation operation -> new IrExpression.TypeOperation(
                    IrValueType.of(operation.type()), switch (operation.operator()) {
                        case SIZEOF -> IrTypeOperatorKind.SIZEOF;
                        case TYPEOF -> IrTypeOperatorKind.TYPEOF;
                        default -> throw unsupported(operation, "invalid type operator");
                    }, IrValueType.of(operation.operandType()));
            case BoundExpression.Error ignored -> throw unsupported(expression, "error recovery node");
            case BoundExpression.FunctionGroup ignored -> throw unsupported(expression, "uninvoked function group");
            case BoundExpression.DeclarationGroup ignored -> throw unsupported(expression,
                    "unresolved declaration group");
            case BoundExpression.Deferred deferred -> {
                IrExpression lowered = deferredLowerer == null ? null
                        : deferredLowerer.lowerDeferred(deferred);
                if (lowered == null) {
                    throw unsupported(expression, "deferred " + deferred.form());
                }
                yield lowered;
            }
            case BoundExpression.Literal ignored -> throw unsupported(expression, "invalid literal");
        };
    }


    /// `x++` / `++x`. The place is carried as the load that reads it, so the backend can turn
    /// it into the matching store; the operator becomes the step, and the position becomes the
    /// one bit that says which value the expression yields.
    private IrExpression lowerMutation(BoundExpression.Mutation mutation) {
        IrBinaryOperator step = mutation.operator() == vsharp.compiler.syntax.SyntaxKind.PLUS_PLUS
                ? IrBinaryOperator.ADD
                : IrBinaryOperator.SUBTRACT;
        return new IrExpression.Mutate(IrValueType.of(mutation.type()),
                lowerUnchecked(mutation.target()), step, !mutation.postfix());
    }

    private IrExpression lowerAssignment(BoundExpression.Assignment assignment) {
        IrValueType type = IrValueType.of(assignment.type());
        IrExpression value = lowerUnchecked(assignment.value());
        
        if (assignment.target() instanceof BoundExpression.ElementAccess elementAccess) {
            IrExpression receiver = lowerUnchecked(elementAccess.receiver());
            java.util.List<IrExpression> indices = elementAccess.arguments().stream().map(this::lowerUnchecked).toList();
            if (assignment.operator() == vsharp.compiler.syntax.SyntaxKind.EQUALS) {
                return new IrExpression.ElementStore(type, receiver, indices, value);
            }
            IrExpression prior = new IrExpression.ElementLoad(type, receiver, indices);
            IrExpression updated = new IrExpression.Binary(type, prior,
                    IrOperators.compoundAssignment(assignment.operator()), value);
            return new IrExpression.ElementStore(type, receiver, indices, updated);
        } else if (assignment.target() instanceof BoundExpression.IndexAccess indexAccess) {
            IrExpression receiver = lowerUnchecked(indexAccess.receiver());
            IrExpression index = lowerUnchecked(indexAccess.index());
            if (assignment.operator() == vsharp.compiler.syntax.SyntaxKind.EQUALS) {
                return new IrExpression.IndexElementStore(type, receiver, index, value);
            }
            IrExpression prior = new IrExpression.IndexElementLoad(type, receiver, index);
            IrExpression updated = new IrExpression.Binary(type, prior,
                    IrOperators.compoundAssignment(assignment.operator()), value);
            return new IrExpression.IndexElementStore(type, receiver, index, updated);
        } else if (assignment.target() instanceof BoundExpression.MemberAccess memberAccess && memberAccess.member() instanceof vsharp.compiler.semantics.symbols.FieldSymbol field) {
            IrExpression receiver = lowerUnchecked(memberAccess.receiver());
            if (assignment.operator() == vsharp.compiler.syntax.SyntaxKind.EQUALS) {
                return new IrExpression.FieldStore(type, receiver, field, value);
            }
            IrExpression prior = new IrExpression.FieldLoad(type, receiver, field);
            IrExpression updated = new IrExpression.Binary(type, prior,
                    IrOperators.compoundAssignment(assignment.operator()), value);
            return new IrExpression.FieldStore(type, receiver, field, updated);
        }

        if (assignment.target() instanceof BoundExpression.Tuple tupleTarget) {
            java.util.List<IrExpression> targets = tupleTarget.elements().stream().map(this::lowerUnchecked).toList();
            // `(a, b) = p;` where `p` is a positional `record struct` reads the components
            // instead of tuple items; an empty component list keeps the tuple shape.
            return new IrExpression.TupleDeconstructionStore(type, targets,
                    positionalComponents(assignment.value().type()), value);
        }

        Symbol target = assignmentTarget(assignment.target());
        if (assignment.operator() == vsharp.compiler.syntax.SyntaxKind.EQUALS) {
            return new IrExpression.Store(type, target, value);
        }
        IrExpression prior = new IrExpression.Load(type, target);
        IrExpression updated = new IrExpression.Binary(type, prior,
                IrOperators.compoundAssignment(assignment.operator()), value);
        return new IrExpression.Store(type, target, updated);
    }

    private static Symbol assignmentTarget(BoundExpression target) {
        if (target instanceof BoundExpression.Value value) {
            return value.symbol();
        }
        throw unsupported(target, "non-variable assignment target");
    }

    private static Object constantValue(ConstantValue value) {
        return switch (value) {
            case ConstantValue.Null ignored -> null;
            case ConstantValue.Boolean booleanValue -> booleanValue.value();
            case ConstantValue.Int intValue -> intValue.value();
            case ConstantValue.Long longValue -> longValue.value();
            case ConstantValue.Float floatValue -> floatValue.value();
            case ConstantValue.Double doubleValue -> doubleValue.value();
            case ConstantValue.Decimal decimal -> decimal.value();
            case ConstantValue.Char character -> character.value();
            case ConstantValue.StringVal string -> string.value();
            case ConstantValue.EnumVal enumValue -> enumValue.underlyingValue();
            case ConstantValue.ByteArray bytes -> bytes.value();
        };
    }

    private static IllegalArgumentException unsupported(BoundExpression expression, String reason) {
        return new IllegalArgumentException("cannot lower " + reason + " at " + expression.span());
    }
}
