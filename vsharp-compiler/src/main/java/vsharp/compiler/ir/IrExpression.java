package vsharp.compiler.ir;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import vsharp.compiler.semantics.conversions.Conversion;
import vsharp.compiler.semantics.symbols.FieldSymbol;
import vsharp.compiler.semantics.symbols.FunctionSymbol;
import vsharp.compiler.semantics.symbols.Symbol;

/// A typed expression ready for control-flow and JVM lowering.
///
/// Every node has an [IrValueType], including stores and calls. Source spans and parser token
/// kinds are intentionally absent: diagnostics were settled before this stage, while the
/// backend needs only typed operations and stable symbol identities.
public sealed interface IrExpression permits IrExpression.Constant, IrExpression.Load, IrExpression.FieldLoad,
        IrExpression.Unary, IrExpression.Binary, IrExpression.Mutate, IrExpression.Convert, IrExpression.Store, IrExpression.FieldStore, IrExpression.ElementStore, IrExpression.TupleDeconstructionStore,
        IrExpression.Conditional, IrExpression.Call, IrExpression.ObjectCreation,
        IrExpression.RecordWith,
        IrExpression.ElementLoad, IrExpression.IndexElementLoad, IrExpression.IndexElementStore, IrExpression.SliceElementLoad,
        IrExpression.ArrayLength, IrExpression.StringLength, IrExpression.StringElementLoad,
        IrExpression.TupleElementLoad,
        IrExpression.ConditionalReceiver, IrExpression.NullConditional,
        IrExpression.NullableHasValue, IrExpression.NullableValue,
        IrExpression.Tuple, IrExpression.Interpolated, IrExpression.ArrayCreation,
        IrExpression.DefaultValue, IrExpression.As, IrExpression.NameOf,
        IrExpression.Checked, IrExpression.Range, IrExpression.Switch,
        IrExpression.TypeOperation, IrExpression.IsPattern, IrExpression.Collection,
        IrExpression.FunctionalValue, IrExpression.Await {

    IrValueType type();

    /// A lambda converted to a Java functional interface.
    ///
    /// `implementation` is the static callable carrying the body, emitted as its own method in
    /// the same class. `interfaceMethod` is the interface's abstract method with the target's
    /// own type arguments substituted, and `erasedInterfaceMethod` is the descriptor the JVM
    /// actually declares; the two differ exactly where generics were erased, which is the
    /// difference `LambdaMetafactory` exists to bridge.
    record FunctionalValue(IrValueType type, FunctionSymbol implementation,
            FunctionSymbol interfaceMethod, FunctionSymbol erasedInterfaceMethod)
            implements IrExpression {
        public FunctionalValue {
            java.util.Objects.requireNonNull(type, "type");
            java.util.Objects.requireNonNull(implementation, "implementation");
            java.util.Objects.requireNonNull(interfaceMethod, "interfaceMethod");
            java.util.Objects.requireNonNull(erasedInterfaceMethod, "erasedInterfaceMethod");
        }
    }

    /// `await task`: the blocking read of a task, with the awaited value's own type.
    ///
    /// The runtime call it lowers to erases to `Object`, so the backend follows it with the
    /// cast or unboxing that `type` requires - the same step any erased generic read takes.
    record Await(IrValueType type, IrExpression operand) implements IrExpression {
        public Await {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(operand, "operand");
        }
    }

    record Constant(IrValueType type, Object value) implements IrExpression {
        public Constant {
            Objects.requireNonNull(type, "type");
        }
    }

    record Load(IrValueType type, Symbol symbol) implements IrExpression {
        public Load {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(symbol, "symbol");
        }
    }

    record FieldLoad(IrValueType type, IrExpression receiver, Symbol field) implements IrExpression {
        public FieldLoad {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(receiver, "receiver");
            Objects.requireNonNull(field, "field");
        }
    }

    record Unary(IrValueType type, IrUnaryOperator operator, IrExpression operand)
            implements IrExpression {
        public Unary {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(operator, "operator");
            Objects.requireNonNull(operand, "operand");
        }
    }

    record Binary(IrValueType type, IrExpression left, IrBinaryOperator operator,
            IrExpression right) implements IrExpression {
        public Binary {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(operator, "operator");
            Objects.requireNonNull(right, "right");
        }
    }

    /// A conversion is explicit even when it is a verifier no-op, preserving C# semantics
    /// for later boxing, numeric narrowing and nullable lowering.
    record Convert(IrValueType type, Conversion conversion, IrExpression operand)
            implements IrExpression {
        public Convert {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(conversion, "conversion");
            Objects.requireNonNull(operand, "operand");
            if (!conversion.exists()) {
                throw new IllegalArgumentException("IR conversion must exist");
            }
        }
    }

    /// Stores evaluate to their assigned value, matching C# assignment-expression semantics.
    /// `place++` / `++place`: reads `place`, applies `step` by one, stores the result back,
    /// and yields the value from after the update (`++x`) or from before it (`x++`).
    ///
    /// The place is spelled as the load that reads it - a [Load], [FieldLoad] or
    /// [ElementLoad] - so no separate notion of an assignable location is needed: the backend
    /// turns each load into its matching store.
    record Mutate(IrValueType type, IrExpression place, IrBinaryOperator step,
            boolean yieldUpdated) implements IrExpression {
        public Mutate {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(place, "place");
            Objects.requireNonNull(step, "step");
        }
    }

    record Store(IrValueType type, Symbol target, IrExpression value) implements IrExpression {
        public Store {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(value, "value");
        }
    }

    record FieldStore(IrValueType type, IrExpression receiver, Symbol target, IrExpression value) implements IrExpression {
        public FieldStore {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(receiver, "receiver");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(value, "value");
        }
    }

    record Conditional(IrValueType type, IrExpression condition, IrExpression whenTrue,
            IrExpression whenFalse) implements IrExpression {
        public Conditional {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(condition, "condition");
            Objects.requireNonNull(whenTrue, "whenTrue");
            Objects.requireNonNull(whenFalse, "whenFalse");
        }
    }

    record Call(IrValueType type, IrExpression receiver, FunctionSymbol function,
            FunctionSymbol declaration,
            List<IrExpression> arguments, List<Integer> argumentParameterOrdinals)
            implements IrExpression {
        public Call {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(function, "function");
            Objects.requireNonNull(declaration, "declaration");
            arguments = List.copyOf(arguments);
            argumentParameterOrdinals = List.copyOf(argumentParameterOrdinals);
            if (arguments.size() != argumentParameterOrdinals.size()) {
                throw new IllegalArgumentException(
                        "arguments and parameter ordinals must have equal sizes");
            }
        }
    }

    /// A selected Java constructor invocation. Its result type identifies the allocated class.
    record ObjectCreation(IrValueType type, FunctionSymbol constructor,
            List<IrExpression> arguments) implements IrExpression {
        public ObjectCreation {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(constructor, "constructor");
            arguments = List.copyOf(arguments);
        }
    }

    /// `receiver with { X = v }`: a fresh positional `record struct` whose components come from
    /// `receiver` except the ones `replacements` names, keyed by component name. The
    /// receiver is evaluated exactly once even though every unreplaced component reads it.
    record RecordWith(IrValueType type, IrExpression receiver, FunctionSymbol constructor,
            List<FieldSymbol> components, Map<String, IrExpression> replacements)
            implements IrExpression {
        public RecordWith {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(receiver, "receiver");
            Objects.requireNonNull(constructor, "constructor");
            components = List.copyOf(components);
            replacements = Map.copyOf(replacements);
            if (components.size() != constructor.parameters().size()) {
                throw new IllegalArgumentException(
                        "record struct component/parameter count mismatch");
            }
        }
    }

    record ElementLoad(IrValueType type, IrExpression receiver, List<IrExpression> indices)
            implements IrExpression {
        public ElementLoad {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(receiver, "receiver");
            indices = List.copyOf(indices);
        }
    }

    record ElementStore(IrValueType type, IrExpression receiver, List<IrExpression> indices, IrExpression value)
            implements IrExpression {
        public ElementStore {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(receiver, "receiver");
            indices = List.copyOf(indices);
            Objects.requireNonNull(value, "value");
        }
    }

    /// Takes a value apart into `targets`. The value is either a tuple, whose elements are
    /// read through `VsTupleN.itemN` (`components` empty), or a positional `record struct`,
    /// whose components are read from the instance fields listed in `components`, in
    /// declaration order. Keeping one node for both keeps the target walk - locals,
    /// fields, array elements, nested tuples - written once.
    record TupleDeconstructionStore(IrValueType type, List<IrExpression> targets,
            List<FieldSymbol> components, IrExpression value) implements IrExpression {
        public TupleDeconstructionStore {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(targets, "targets");
            Objects.requireNonNull(value, "value");
            targets = List.copyOf(targets);
            components = List.copyOf(components);
            if (!components.isEmpty() && components.size() != targets.size()) {
                throw new IllegalArgumentException(
                        "deconstruction component/target count mismatch: " + components.size()
                                + " vs " + targets.size());
            }
        }

        public TupleDeconstructionStore(IrValueType type, List<IrExpression> targets,
                IrExpression value) {
            this(type, targets, List.of(), value);
        }
    }

    record IndexElementLoad(IrValueType type, IrExpression receiver, IrExpression index)
            implements IrExpression {
        public IndexElementLoad {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(receiver, "receiver");
            Objects.requireNonNull(index, "index");
        }
    }

    record IndexElementStore(IrValueType type, IrExpression receiver, IrExpression index, IrExpression value)
            implements IrExpression {
        public IndexElementStore {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(receiver, "receiver");
            Objects.requireNonNull(index, "index");
            Objects.requireNonNull(value, "value");
        }
    }

    record SliceElementLoad(IrValueType type, IrExpression receiver, IrExpression range)
            implements IrExpression {
        public SliceElementLoad {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(receiver, "receiver");
            Objects.requireNonNull(range, "range");
        }
    }

    /// One element of a value tuple, by resolved position.
    record TupleElementLoad(IrValueType type, IrExpression receiver, int index)
            implements IrExpression {
        public TupleElementLoad {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(receiver, "receiver");
            if (index < 0) {
                throw new IllegalArgumentException("tuple element index must not be negative");
            }
        }
    }

    record ArrayLength(IrValueType type, IrExpression receiver) implements IrExpression {
        public ArrayLength {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(receiver, "receiver");
        }
    }

    record StringLength(IrValueType type, IrExpression receiver) implements IrExpression {
        public StringLength {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(receiver, "receiver");
        }
    }

    record StringElementLoad(IrValueType type, IrExpression receiver, IrExpression index)
            implements IrExpression {
        public StringElementLoad {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(receiver, "receiver");
            Objects.requireNonNull(index, "index");
        }
    }

    /// Loads the receiver slot owned by the nearest enclosing [NullConditional].
    /// `carrierType` is the spilled slot's type; `type` is what the access subtree sees
    /// (the underlying value type when the carrier is nullable).
    record ConditionalReceiver(IrValueType type, IrValueType carrierType)
            implements IrExpression {
        public ConditionalReceiver {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(carrierType, "carrierType");
        }
    }

    /// The receiver is evaluated once; `access` executes only on the non-null path and
    /// reads the saved value through [ConditionalReceiver].
    record NullConditional(IrValueType type, IrExpression receiver, IrExpression access)
            implements IrExpression {
        public NullConditional {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(receiver, "receiver");
            Objects.requireNonNull(access, "access");
        }
    }

    record NullableHasValue(IrValueType type, IrExpression receiver) implements IrExpression {
        public NullableHasValue {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(receiver, "receiver");
        }
    }

    record NullableValue(IrValueType type, IrExpression receiver) implements IrExpression {
        public NullableValue {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(receiver, "receiver");
        }
    }

    record Tuple(IrValueType type, List<IrExpression> elements) implements IrExpression {
        public Tuple {
            Objects.requireNonNull(type, "type");
            elements = List.copyOf(elements);
            if (elements.size() < 2) {
                throw new IllegalArgumentException("tuple needs at least two elements");
            }
        }
    }

    /// Formatted string construction stays structured until the runtime call sequence is chosen.
    record Interpolated(IrValueType type, List<IrInterpolationPart> parts) implements IrExpression {
        public Interpolated {
            Objects.requireNonNull(type, "type");
            parts = List.copyOf(parts);
        }
    }

    /// Array construction retains dimensions separately from nested initializer shape.
    record ArrayCreation(IrValueType type, List<IrExpression> dimensions,
            List<IrArrayElement> initializer) implements IrExpression {
        public ArrayCreation {
            Objects.requireNonNull(type, "type");
            dimensions = List.copyOf(dimensions);
            initializer = List.copyOf(initializer);
        }
    }

    record DefaultValue(IrValueType type) implements IrExpression {
        public DefaultValue { Objects.requireNonNull(type, "type"); }
    }

    record As(IrValueType type, IrExpression operand) implements IrExpression {
        public As {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(operand, "operand");
        }
    }

    /// `nameof` is its compile-time final identifier; its operand is never executable IR.
    record NameOf(IrValueType type, String name) implements IrExpression {
        public NameOf {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(name, "name");
        }
    }

    record Checked(IrValueType type, boolean checked, IrExpression operand)
            implements IrExpression {
        public Checked {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(operand, "operand");
        }
    }

    /// C# `start..end`, lowered later to `vsharp.runtime.VsRange` construction.
    record Range(IrValueType type, IrExpression start, IrExpression end) implements IrExpression {
        public Range {
            Objects.requireNonNull(type, "type");
        }
    }

    /// Ordered pattern matching whose result type was inferred during expression binding.
    record Switch(IrValueType type, IrExpression governing, List<IrSwitchExpressionArm> arms)
            implements IrExpression {
        public Switch {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(governing, "governing");
            arms = List.copyOf(arms);
            if (arms.isEmpty()) {
                throw new IllegalArgumentException("switch expression needs an arm");
            }
        }
    }

    record TypeOperation(IrValueType type, IrTypeOperatorKind operator, IrValueType operandType)
            implements IrExpression {
        public TypeOperation {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(operator, "operator");
            Objects.requireNonNull(operandType, "operandType");
        }
    }

    record IsPattern(IrValueType type, IrExpression expression, IrPattern pattern)
            implements IrExpression {
        public IsPattern {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(expression, "expression");
            Objects.requireNonNull(pattern, "pattern");
        }
    }

    record Collection(IrValueType type, List<IrCollectionElement> elements)
            implements IrExpression {
        public Collection {
            Objects.requireNonNull(type, "type");
            elements = List.copyOf(elements);
        }
    }
}
