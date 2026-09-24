package vsharp.compiler.semantics.binding;

import java.util.List;
import java.util.Objects;
import vsharp.compiler.semantics.symbols.FunctionSymbol;
import vsharp.compiler.semantics.symbols.Symbol;
import vsharp.compiler.semantics.symbols.SymbolKind;
import vsharp.compiler.semantics.types.TypeSymbol;
import vsharp.compiler.source.SourceSpan;
import vsharp.compiler.syntax.ExpressionSyntax;
import vsharp.compiler.syntax.StatementSyntax;
import vsharp.compiler.syntax.SyntaxKind;

import vsharp.compiler.semantics.conversions.Conversion;

/// An immutable expression after value-name and core operator binding.
///
/// This is semantic structure, not lowering IR: it preserves source operators and operand
/// types while later conversion and constant-evaluation stages remain free to insert their
/// own explicit nodes.
public sealed interface BoundExpression
        permits BoundExpression.MemberAccess, BoundExpression.NullableHasValue,
                BoundExpression.NullableValue, BoundExpression.Error, BoundExpression.Literal, BoundExpression.Value,
                BoundExpression.FunctionGroup, BoundExpression.DeclarationGroup,
                BoundExpression.Parenthesized, BoundExpression.Tuple,
                BoundExpression.Unary, BoundExpression.Mutation, BoundExpression.Binary,
                BoundExpression.Assignment, BoundExpression.Conditional,
                BoundExpression.Conversion, BoundExpression.Call, BoundExpression.ObjectCreation,
                BoundExpression.ElementAccess, BoundExpression.ArrayLength,
                BoundExpression.TupleElement,
                BoundExpression.StringLength, BoundExpression.StringElementAccess,
                BoundExpression.ConditionalReceiver, BoundExpression.NullConditional,
                BoundExpression.Collection, BoundExpression.TypeOperation,
                BoundExpression.IndexAccess, BoundExpression.SliceAccess,
                BoundExpression.Lambda, BoundExpression.Await,
                BoundExpression.Deferred {

    /// The [Deferred#form] of `new T[n]` and `new T[] { ... }`.
    ///
    /// Named here because two stages must agree on it: binding writes it, and the emission
    /// gate reads it to refuse allocating an array whose element type is erased.
    String ARRAY_CREATION_FORM = "array creation";

    SourceSpan span();

    TypeSymbol type();

    /// Recovery node for malformed, invalid, or not-yet-bound expression forms.
    record Error(SourceSpan span) implements BoundExpression {
        public Error {
            Objects.requireNonNull(span, "span");
        }

        @Override
        public TypeSymbol type() {
            return TypeSymbol.Error.INSTANCE;
        }
    }

    /// A decoded source literal. `value` is null only for the null literal.
    record Literal(SourceSpan span, TypeSymbol type, Object value) implements BoundExpression {
        public Literal {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(type, "type");
        }
    }

    /// A value-bearing local, parameter, field, or enum member.
    record Value(SourceSpan span, TypeSymbol type, Symbol symbol) implements BoundExpression {
        public Value {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(symbol, "symbol");
            if (symbol.kind() != SymbolKind.LOCAL && symbol.kind() != SymbolKind.PARAMETER
                    && symbol.kind() != SymbolKind.FIELD
                    && symbol.kind() != SymbolKind.ENUM_MEMBER) {
                throw new IllegalArgumentException("symbol does not denote a value: " + symbol);
            }
        }
    }

    /// An unresolved-overload function group. Invocation binding consumes this node later.
    record MemberAccess(SourceSpan span, TypeSymbol type, BoundExpression receiver, Symbol member) implements BoundExpression {
    }

    /// The two intrinsic members every nullable value type exposes. They are semantic nodes,
    /// rather than synthetic fields, because neither has storage in the chosen JVM carrier:
    /// both are observations of the nullable reference itself.
    record NullableHasValue(SourceSpan span, BoundExpression receiver) implements BoundExpression {
        public NullableHasValue {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(receiver, "receiver");
            if (!(receiver.type() instanceof TypeSymbol.Nullable)) {
                throw new IllegalArgumentException("HasValue receiver must be nullable");
            }
        }

        @Override
        public TypeSymbol type() {
            return vsharp.compiler.semantics.types.BuiltinType.BOOL;
        }
    }

    record NullableValue(SourceSpan span, TypeSymbol type, BoundExpression receiver)
            implements BoundExpression {
        public NullableValue {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(receiver, "receiver");
            if (!(receiver.type() instanceof TypeSymbol.Nullable nullable)
                    || !nullable.element().equals(type)) {
                throw new IllegalArgumentException("Value type must be the nullable element type");
            }
        }
    }

    /// A resolved set of same-named callables awaiting an argument list.
    ///
    /// `extensionForm` marks the group C# §12.8.10.3 reached through a receiver that does not
    /// declare the member: the candidates are extension methods, `receiver` is the expression
    /// written before the dot, and the invocation is the static call with that receiver
    /// prepended to the argument list. The flag is the whole difference between the two call
    /// forms - a group produced by naming the static class instead keeps `false` and passes
    /// its arguments unchanged, which is exactly how C# distinguishes them.
    record FunctionGroup(SourceSpan span, String name, List<FunctionSymbol> candidates,
            BoundExpression receiver, boolean extensionForm)
            implements BoundExpression {
        public FunctionGroup {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(name, "name");
            candidates = List.copyOf(candidates);
            if (candidates.isEmpty()) {
                throw new IllegalArgumentException("a function group needs candidates");
            }
            if (extensionForm && receiver == null) {
                throw new IllegalArgumentException("an extension group needs a receiver");
            }
        }

        /// An ordinary group: the receiver, when present, is the instance a call binds against.
        public FunctionGroup(SourceSpan span, String name, List<FunctionSymbol> candidates,
                BoundExpression receiver) {
            this(span, name, candidates, receiver, false);
        }

        @Override
        public TypeSymbol type() {
            return TypeSymbol.Error.INSTANCE;
        }
    }

    /// A namespace, static container, or named type awaiting member access binding.
    record DeclarationGroup(SourceSpan span, String name, List<Symbol> candidates)
            implements BoundExpression {
        public DeclarationGroup {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(name, "name");
            candidates = List.copyOf(candidates);
            if (candidates.isEmpty()) {
                throw new IllegalArgumentException("a declaration group needs candidates");
            }
        }

        @Override
        public TypeSymbol type() {
            return TypeSymbol.Error.INSTANCE;
        }
    }

    record Parenthesized(SourceSpan span, TypeSymbol type, BoundExpression expression)
            implements BoundExpression {
        public Parenthesized {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(expression, "expression");
        }
    }

    record Tuple(SourceSpan span, TypeSymbol.Tuple type, List<BoundExpression> elements)
            implements BoundExpression {
        public Tuple {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(type, "type");
            elements = List.copyOf(elements);
            if (elements.size() < 2 || elements.size() != type.elements().size()) {
                throw new IllegalArgumentException("tuple element/type arity mismatch");
            }
        }
    }

    record Unary(SourceSpan span, TypeSymbol type, SyntaxKind operator,
            BoundExpression operand) implements BoundExpression {
        public Unary {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(operator, "operator");
            Objects.requireNonNull(operand, "operand");
        }
    }

    /// `await task`, whose type is the task's result type.
    ///
    /// The operand is always a `Task`/`Task<T>`, which is `java.util.concurrent.Future`, and
    /// the node carries the result type the binder read out of it so no later stage has to
    /// re-derive it from an erased signature.
    record Await(SourceSpan span, TypeSymbol type, BoundExpression operand)
            implements BoundExpression {
        public Await {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(operand, "operand");
        }
    }

    record Binary(SourceSpan span, TypeSymbol type, BoundExpression left,
            SyntaxKind operator, BoundExpression right) implements BoundExpression {
        public Binary {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(operator, "operator");
            Objects.requireNonNull(right, "right");
        }
    }

    /// `x++`, `x--`, `++x` or `--x`.
    ///
    /// Distinct from [Unary] because the two spellings of the same operator differ in value,
    /// not only in position: a postfix form yields the value from before the update. Binding
    /// them both to a unary node would lose the one fact lowering needs.
    record Mutation(SourceSpan span, TypeSymbol type, SyntaxKind operator,
            BoundExpression target, boolean postfix) implements BoundExpression {
        public Mutation {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(operator, "operator");
            Objects.requireNonNull(target, "target");
        }
    }

    record Assignment(SourceSpan span, TypeSymbol type, BoundExpression target,
            SyntaxKind operator, BoundExpression value) implements BoundExpression {
        public Assignment {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(operator, "operator");
            Objects.requireNonNull(value, "value");
        }
    }

    record Conditional(SourceSpan span, TypeSymbol type, BoundExpression condition,
            BoundExpression whenTrue, BoundExpression whenFalse) implements BoundExpression {
        public Conditional {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(condition, "condition");
            Objects.requireNonNull(whenTrue, "whenTrue");
            Objects.requireNonNull(whenFalse, "whenFalse");
        }
    }

    /// An explicit or implicit conversion applied to an expression.
    record Conversion(SourceSpan span, BoundExpression operand, TypeSymbol type,
            vsharp.compiler.semantics.conversions.Conversion conversion) implements BoundExpression {
        public Conversion {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(operand, "operand");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(conversion, "conversion");
        }
    }

    /// A resolved function invocation.
    record Call(SourceSpan span, TypeSymbol type, BoundExpression receiver, FunctionSymbol function,
            FunctionSymbol declaration,
            List<BoundExpression> arguments, List<Integer> argumentParameterOrdinals)
            implements BoundExpression {
        public Call {
            Objects.requireNonNull(span, "span");
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

    /// A Java object creation with its public constructor overload already selected.
    record ObjectCreation(SourceSpan span, TypeSymbol type, FunctionSymbol constructor,
            List<BoundExpression> arguments) implements BoundExpression {
        public ObjectCreation {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(constructor, "constructor");
            arguments = List.copyOf(arguments);
        }
    }

    /// An array element access expression.
    record ElementAccess(SourceSpan span, TypeSymbol type, BoundExpression receiver,
            List<BoundExpression> arguments) implements BoundExpression {
        public ElementAccess {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(receiver, "receiver");
            arguments = List.copyOf(arguments);
        }
    }

    /// An element access using System.Index.
    record IndexAccess(SourceSpan span, TypeSymbol type, BoundExpression receiver,
            BoundExpression index) implements BoundExpression {
        public IndexAccess {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(receiver, "receiver");
            Objects.requireNonNull(index, "index");
        }
    }

    /// A slice access using System.Range.
    record SliceAccess(SourceSpan span, TypeSymbol type, BoundExpression receiver,
            BoundExpression range) implements BoundExpression {
        public SliceAccess {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(receiver, "receiver");
            Objects.requireNonNull(range, "range");
        }
    }

    /// The built-in one-dimensional or multi-dimensional array length property.
    /// `pair.Item1` or `pair.Name`: one element of a value tuple, resolved to its position.
    ///
    /// An explicit node rather than a deferred one because element names are a type-system
    /// fact with no run-time trace, so nothing downstream could re-derive the position from
    /// the syntax - and a deferred node here made lowering hand the same member-access
    /// syntax back to itself until the stack ran out.
    record TupleElement(SourceSpan span, TypeSymbol type, BoundExpression receiver, int index)
            implements BoundExpression {
        public TupleElement {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(receiver, "receiver");
            if (index < 0) {
                throw new IllegalArgumentException("tuple element index must not be negative");
            }
        }
    }

    record ArrayLength(SourceSpan span, BoundExpression receiver) implements BoundExpression {
        public ArrayLength {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(receiver, "receiver");
        }

        @Override
        public TypeSymbol type() {
            return vsharp.compiler.semantics.types.BuiltinType.INT;
        }
    }

    /// The built-in string length property.
    record StringLength(SourceSpan span, BoundExpression receiver) implements BoundExpression {
        public StringLength {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(receiver, "receiver");
        }

        @Override
        public TypeSymbol type() {
            return vsharp.compiler.semantics.types.BuiltinType.INT;
        }
    }

    /// The built-in UTF-16 string indexer.
    record StringElementAccess(SourceSpan span, BoundExpression receiver,
            BoundExpression index) implements BoundExpression {
        public StringElementAccess {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(receiver, "receiver");
            Objects.requireNonNull(index, "index");
        }

        @Override
        public TypeSymbol type() {
            return vsharp.compiler.semantics.types.BuiltinType.CHAR;
        }
    }

    /// A use of the receiver slot owned by the nearest enclosing null-conditional node.
    /// It is a semantic placeholder, never a second evaluation of the source receiver.
    /// The saved value is carried by `carrierType` (the receiver's original type) and is
    /// made available to the access subtree as `type` (the underlying type for a nullable
    /// receiver, the same type otherwise).
    record ConditionalReceiver(SourceSpan span, TypeSymbol type, TypeSymbol carrierType)
            implements BoundExpression {
        public ConditionalReceiver {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(carrierType, "carrierType");
        }
    }

    /// Evaluates `receiver` once and evaluates `access` only when it is non-null.
    /// `access` refers back to the saved value through [ConditionalReceiver].
    record NullConditional(SourceSpan span, TypeSymbol type, BoundExpression receiver,
            BoundExpression access) implements BoundExpression {
        public NullConditional {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(receiver, "receiver");
            Objects.requireNonNull(access, "access");
        }
    }

    /// A collection expression after a target type has selected its element representation.
    record Collection(SourceSpan span, TypeSymbol type, List<Element> elements)
            implements BoundExpression {
        public record Element(boolean spread, BoundExpression expression) {
            public Element { Objects.requireNonNull(expression, "expression"); }
        }

        public Collection {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(type, "type");
            elements = List.copyOf(elements);
        }
    }

    /// A lambda that has reached a target type and become a Java functional-interface value
    ///.
    ///
    /// `type` is the interface the lambda was converted to and `interfaceMethod` is that
    /// interface's single abstract method in its *erased* form, because the JVM call site
    /// names the erased descriptor while `function` carries the substituted one the body was
    /// bound against. `bodySyntax` is retained so lowering re-enters the ordinary
    /// expression-bodied callable path rather than inventing a second body shape.
    ///
    /// A statement-bodied lambda carries `blockBody` instead: its statements were bound
    /// by the ordinary statement walk under the lambda's own callable, so lowering re-enters
    /// the ordinary *block*-bodied path. Exactly one of the two body shapes is present.
    record Lambda(SourceSpan span, TypeSymbol type, FunctionSymbol function,
            FunctionSymbol interfaceMethod, ExpressionSyntax bodySyntax,
            BoundExpression body, StatementSyntax.Block blockBody) implements BoundExpression {
        public Lambda {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(function, "function");
            Objects.requireNonNull(interfaceMethod, "interfaceMethod");
            if (blockBody == null) {
                Objects.requireNonNull(bodySyntax, "bodySyntax");
                Objects.requireNonNull(body, "body");
            } else if (bodySyntax != null || body != null) {
                throw new IllegalArgumentException("a lambda has one body shape, not both");
            }
        }

        /// An expression-bodied lambda, the shape a conversion target can type directly.
        Lambda(SourceSpan span, TypeSymbol type, FunctionSymbol function,
                FunctionSymbol interfaceMethod, ExpressionSyntax bodySyntax,
                BoundExpression body) {
            this(span, type, function, interfaceMethod, bodySyntax, body, null);
        }

        /// A statement-bodied lambda, whose result type the `return` statements produced.
        Lambda(SourceSpan span, TypeSymbol type, FunctionSymbol function,
                FunctionSymbol interfaceMethod, StatementSyntax.Block blockBody) {
            this(span, type, function, interfaceMethod, null, null, blockBody);
        }
    }

    /// A `sizeof(T)` or `typeof(T)` operation with its type operand already resolved.
    record TypeOperation(SourceSpan span, TypeSymbol type, SyntaxKind operator,
            TypeSymbol operandType) implements BoundExpression {
        public TypeOperation {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(operator, "operator");
            Objects.requireNonNull(operandType, "operandType");
        }
    }

    /// A recognised expression whose dedicated semantic rules belong to a later stage.
    ///
    /// Bound children and any independently known result type are retained, so deferring
    /// overload, member, pattern, or target typing never prevents diagnostics below it.
    record Deferred(SourceSpan span, TypeSymbol type, String form,
            List<BoundExpression> children) implements BoundExpression {
        public Deferred {
            Objects.requireNonNull(span, "span");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(form, "form");
            children = List.copyOf(children);
        }
    }
}
