package vsharp.compiler.ir;

import java.util.List;
import java.util.Objects;
import vsharp.compiler.semantics.symbols.FieldSymbol;
import vsharp.compiler.semantics.symbols.FunctionSymbol;
import vsharp.compiler.semantics.symbols.LocalSymbol;

/// A typed pattern used by switch and foreach lowering, without syntax-tree dependencies.
public sealed interface IrPattern permits IrPattern.Discard, IrPattern.Constant, IrPattern.Type,
        IrPattern.Var, IrPattern.Relational, IrPattern.Binary, IrPattern.Not,
        IrPattern.Recursive, IrPattern.ListPattern, IrPattern.Slice {

    record Discard() implements IrPattern {}

    record Constant(IrExpression expression) implements IrPattern {
        public Constant { Objects.requireNonNull(expression, "expression"); }
    }

    record Type(IrValueType type, LocalSymbol variable) implements IrPattern {
        public Type { Objects.requireNonNull(type, "type"); }
    }

    /// `var x`: always matches and binds. The bound type is the operand's, which the binder
    /// inferred rather than the source spelling it, so lowering carries it here instead of
    /// leaving the backend to read an `Inferred` placeholder off the symbol.
    record Var(IrValueType type, LocalSymbol variable) implements IrPattern {
        public Var {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(variable, "variable");
        }
    }

    record Relational(IrBinaryOperator operator, IrExpression value) implements IrPattern {
        public Relational {
            Objects.requireNonNull(operator, "operator");
            Objects.requireNonNull(value, "value");
        }
    }

    record Binary(IrPattern left, Kind operator, IrPattern right) implements IrPattern {
        public enum Kind { AND, OR }

        public Binary {
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(operator, "operator");
            Objects.requireNonNull(right, "right");
        }
    }

    record Not(IrPattern pattern) implements IrPattern {
        public Not { Objects.requireNonNull(pattern, "pattern"); }
    }

    /// `expr is T (p1, p2) { Name: p3 } name`: an optional type test, then one subpattern
    /// per component of the tested value.
    ///
    /// Positional and property syntax differ only in how the component's value is reached,
    /// which binding has already resolved into [Component]; by the time lowering runs there
    /// is one uniform list. A `type` of `null` is the typeless form (`expr is { X: 1 }`),
    /// which C# still requires to be non-`null` before any component is read.
    record Recursive(IrValueType type, List<Component> components, Binding designation)
            implements IrPattern {
        public Recursive {
            components = List.copyOf(components);
        }
    }

    /// A pattern designation and the type it binds. `var x` and a recursive or list
    /// pattern's trailing name are all inferred from the value that reaches them, so the
    /// symbol alone carries only a placeholder type.
    record Binding(IrValueType type, LocalSymbol symbol) {
        public Binding {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(symbol, "symbol");
        }
    }

    /// How one subpattern of a [Recursive] pattern reaches its value from the tested value,
    /// and the type that value has.
    record Component(Access access, int index, FieldSymbol field, FunctionSymbol accessor,
            IrValueType type, IrPattern pattern) {
        /// The JVM operation that produces the component's value.
        public enum Access {
            /// `ValueTuple.ItemN` on a `vsharp.runtime.VsTupleN`, positional or by name.
            TUPLE_ITEM,
            /// An instance field of a declared struct or record struct.
            FIELD,
            /// `Length` on an array.
            ARRAY_LENGTH,
            /// `Length` on a `string`.
            STRING_LENGTH,
            /// A no-argument Java accessor - the curated `Map.Entry` deconstruction.
            ACCESSOR
        }

        public Component {
            Objects.requireNonNull(access, "access");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(pattern, "pattern");
            if (access == Access.TUPLE_ITEM && index < 0) {
                throw new IllegalArgumentException("tuple component needs an index");
            }
            if (access == Access.FIELD && field == null) {
                throw new IllegalArgumentException("field component needs a field");
            }
            if (access == Access.ACCESSOR && accessor == null) {
                throw new IllegalArgumentException("accessor component needs a method");
            }
        }
    }

    /// `xs is [1, .. var rest, var last]`: a length test plus one subpattern per element.
    ///
    /// `collectionType` and `elementType` are what binding resolved the tested value and a
    /// non-slice subpattern to; a [Slice] element tests the collection type, because a slice
    /// of an array is an array and of a `string` a `string`.
    record ListPattern(IrValueType collectionType, IrValueType elementType,
            List<IrPattern> elements, Binding designation) implements IrPattern {
        public ListPattern {
            Objects.requireNonNull(collectionType, "collectionType");
            Objects.requireNonNull(elementType, "elementType");
            elements = List.copyOf(elements);
        }
    }

    /// `..` or `.. pattern` inside a list pattern. It only ever appears as a direct element
    /// of a [ListPattern], which is what supplies the type it tests.
    record Slice(IrPattern pattern) implements IrPattern {
        public Slice { Objects.requireNonNull(pattern, "pattern"); }
    }
}
