package vsharp.compiler.semantics.types;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import vsharp.compiler.semantics.symbols.NamedTypeSymbol;
import vsharp.compiler.semantics.symbols.TypeParameterSymbol;

/// A resolved V# type.
///
/// The hierarchy contains no syntax nodes and is safe to share between later semantic and
/// lowering stages. Error and inferred types are explicit values so recovery never relies
/// on `null`.
public sealed interface TypeSymbol
        permits BuiltinType, NamedTypeSymbol, TypeParameterSymbol, TypeSymbol.Array,
                TypeSymbol.Tuple, TypeSymbol.Nullable, TypeSymbol.Ref, TypeSymbol.Wildcard,
                TypeSymbol.Constructed, TypeSymbol.Null, TypeSymbol.Inferred,
                TypeSymbol.Error, TypeSymbol.Range, TypeSymbol.Index, TypeSymbol.Function {

    /// Stable source-facing spelling used in diagnostics and signatures.
    String displayName();

    /// Verifier-level carrier planned for JVM lowering.
    JvmTypeKind jvmTypeKind();

    /// Whether the type has C# value semantics.
    boolean isValueType();

    /// A Java wildcard type argument: `? extends Number`, `? super String`, or bare `?`
    ///.
    ///
    /// Only a Java signature produces one - V# source cannot spell a wildcard - so the node
    /// appears exclusively inside the arguments of a [Constructed] type read from a class
    /// file, and no parser, declaration binder or V# conversion rule ever meets it. That
    /// containment is what makes the addition small: the type model gains variance exactly
    /// where the JVM already had it, and nowhere else.
    ///
    /// Carrying the wildcard is strictly more faithful than the erasure it replaces. A
    /// signature holding one used to fall back whole to its descriptor, which made
    /// `Stream.collect(Collector<? super T, A, R>)` return `Object` and cost every
    /// `Collectors` idiom its type; and projecting it away instead would have made
    /// `Collection<? extends E>` invariant, refusing `AddAll(listOfString)` on a
    /// `Collection<object>` - a program Java accepts. The wildcard says what Java says, so
    /// both work for the same reason.
    ///
    /// `bound` is the written bound, or `object` for a bare `?`; erasure is the bound, which
    /// is what the JVM descriptor already uses.
    record Wildcard(TypeSymbol bound, boolean superBound) implements TypeSymbol {
        public Wildcard {
            Objects.requireNonNull(bound, "bound");
        }

        @Override
        public String displayName() {
            return (superBound ? "? super " : "? extends ") + bound.displayName();
        }

        @Override
        public JvmTypeKind jvmTypeKind() {
            return JvmTypeKind.REFERENCE;
        }

        @Override
        public boolean isValueType() {
            return false;
        }
    }

    /// A rectangular or jagged array layer.
    ///
    /// Each rank corresponds to one source rank specifier, outermost first.
    ///
    /// The representation is canonical: `element` is never itself an `Array`, because an
    /// array of arrays is the same type as one carrying both rank specifiers. `int[][]`
    /// written as a declared type reaches the binder as `Array(int, [1, 1])` while
    /// `new int[2][]` builds `Array(Array(int, [1]), [1])`; without flattening those two
    /// spellings of one type compare unequal and every conversion is rejected. The compact
    /// constructor therefore absorbs a nested element, appending its ranks after this
    /// layer's - outermost first, matching C# source order.
    record Array(TypeSymbol element, List<Integer> ranks) implements TypeSymbol {
        public Array {
            Objects.requireNonNull(element, "element");
            ranks = List.copyOf(ranks);
            if (ranks.isEmpty() || ranks.stream().anyMatch(rank -> rank < 1)) {
                throw new IllegalArgumentException("array ranks must be positive and non-empty");
            }
            if (element instanceof Array nested) {
                List<Integer> merged = new ArrayList<>(ranks);
                merged.addAll(nested.ranks());
                ranks = List.copyOf(merged);
                element = nested.element();
            }
        }

        /// The type of `a[i, ...]` for one indexing step: this array with its outermost rank
        /// specifier removed, which is the element type only when a single specifier is left.
        /// `int[][]`'s indexed element is `int[]`, not `int`.
        public TypeSymbol elementType() {
            return ranks.size() == 1 ? element : new Array(element, ranks.subList(1, ranks.size()));
        }

        /// The total number of JVM array levels, which is the sum of every rank because a
        /// rectangular `int[,]` is carried as `[[I`.
        public int jvmDepth() {
            int depth = 0;
            for (int rank : ranks) {
                depth += rank;
            }
            return depth;
        }

        @Override
        public String displayName() {
            StringBuilder out = new StringBuilder(element.displayName());
            for (int rank : ranks) {
                out.append('[').append(",".repeat(rank - 1)).append(']');
            }
            return out.toString();
        }

        @Override
        public JvmTypeKind jvmTypeKind() {
            return JvmTypeKind.REFERENCE;
        }

        @Override
        public boolean isValueType() {
            return false;
        }
    }

    /// One tuple component, retaining its optional source name.
    record TupleElement(TypeSymbol type, String name) {
        public TupleElement {
            Objects.requireNonNull(type, "type");
        }

        String displayName() {
            return name == null ? type.displayName() : type.displayName() + " " + name;
        }
    }

    /// A tuple value type.
    record Tuple(List<TupleElement> elements) implements TypeSymbol {
        public Tuple {
            elements = List.copyOf(elements);
            if (elements.size() < 2) {
                throw new IllegalArgumentException("a tuple type needs at least two elements");
            }
        }

        @Override
        public String displayName() {
            return elements.stream().map(TupleElement::displayName)
                    .collect(Collectors.joining(", ", "(", ")"));
        }

        @Override
        public JvmTypeKind jvmTypeKind() {
            return JvmTypeKind.REFERENCE;
        }

        @Override
        public boolean isValueType() {
            return true;
        }
    }

    /// A nullable value or annotated nullable reference type.
    record Nullable(TypeSymbol element) implements TypeSymbol {
        public Nullable {
            Objects.requireNonNull(element, "element");
        }

        @Override
        public String displayName() {
            return element.displayName() + "?";
        }

        @Override
        public JvmTypeKind jvmTypeKind() {
            return JvmTypeKind.REFERENCE;
        }

        @Override
        public boolean isValueType() {
            return element.isValueType();
        }
    }

    /// A managed by-reference type.
    record Ref(TypeSymbol element, boolean readOnly) implements TypeSymbol {
        public Ref {
            Objects.requireNonNull(element, "element");
        }

        @Override
        public String displayName() {
            return readOnly ? "ref readonly " + element.displayName()
                    : "ref " + element.displayName();
        }

        @Override
        public JvmTypeKind jvmTypeKind() {
            return JvmTypeKind.REFERENCE;
        }

        @Override
        public boolean isValueType() {
            return false;
        }
    }

    /// An application of a generic declared value type.
    record Constructed(NamedTypeSymbol definition, List<TypeSymbol> arguments)
            implements TypeSymbol {

        /// The boxed carriers that *are* a V# keyword type when they stand as a type argument
        ///. `List<int>` and `List<java.lang.Integer>` denote one type - V# boxes every
        /// value it puts in a generic position and the argument erases away, so the JVM sees
        /// the same `List` either way - exactly as C# gives `int` and `System.Int32` one
        /// meaning. Normalising here rather than at each construction site is what makes the
        /// two spellings equal wherever a type argument is compared, displayed or inferred.
        private static final Map<String, TypeSymbol> BOXED_ARGUMENTS = Map.of(
                "java.lang.Integer", BuiltinType.INT,
                "java.lang.Long", BuiltinType.LONG,
                "java.lang.Short", BuiltinType.SHORT,
                "java.lang.Byte", BuiltinType.SBYTE,
                "java.lang.Character", BuiltinType.CHAR,
                "java.lang.Boolean", BuiltinType.BOOL,
                "java.lang.Float", BuiltinType.FLOAT,
                "java.lang.Double", BuiltinType.DOUBLE);

        /// A boxed carrier in a type-argument position, normalised to its keyword type. The
        /// rule is confined to type arguments: a `java.lang.Integer` parameter, field or
        /// return keeps its reference identity, because there it can be `null`.
        private static TypeSymbol normalize(TypeSymbol argument) {
            if (argument instanceof NamedTypeSymbol named) {
                return BOXED_ARGUMENTS.getOrDefault(named.qualifiedName(), argument);
            }
            if (argument instanceof Wildcard wildcard) {
                TypeSymbol bound = normalize(wildcard.bound());
                return bound == wildcard.bound() ? wildcard
                        : new Wildcard(bound, wildcard.superBound());
            }
            return argument;
        }

        public Constructed {
            Objects.requireNonNull(definition, "definition");
            arguments = arguments.stream().map(Constructed::normalize).toList();
            if (arguments.size() != definition.arity()) {
                throw new IllegalArgumentException(
                        "expected " + definition.arity() + " type arguments but got "
                                + arguments.size());
            }
        }

        @Override
        public String displayName() {
            return arguments.stream().map(TypeSymbol::displayName)
                    .collect(Collectors.joining(", ", definition.qualifiedName() + "<", ">"));
        }

        @Override
        public JvmTypeKind jvmTypeKind() {
            return definition.jvmTypeKind();
        }

        @Override
        public boolean isValueType() {
            return definition.isValueType();
        }
    }

    /// The typeless `null` literal before a target conversion is selected.
    enum Null implements TypeSymbol {
        INSTANCE;

        @Override
        public String displayName() {
            return "<null>";
        }

        @Override
        public JvmTypeKind jvmTypeKind() {
            return JvmTypeKind.REFERENCE;
        }

        @Override
        public boolean isValueType() {
            return false;
        }
    }

    /// Placeholder for a `var` local until expression binding infers its type.
    enum Inferred implements TypeSymbol {
        INSTANCE;

        @Override
        public String displayName() {
            return "var";
        }

        @Override
        public JvmTypeKind jvmTypeKind() {
            return JvmTypeKind.REFERENCE;
        }

        @Override
        public boolean isValueType() {
            return false;
        }
    }

    /// Recovery type for syntax which could not resolve to a semantic type.
    enum Error implements TypeSymbol {
        INSTANCE;

        @Override
        public String displayName() {
            return "<error>";
        }

        @Override
        public JvmTypeKind jvmTypeKind() {
            return JvmTypeKind.REFERENCE;
        }

        @Override
        public boolean isValueType() {
            return false;
        }
    }

    /// The compiler-known C# `System.Range` value produced by the `..` operator.
    ///
    /// It maps to `vsharp.runtime.VsRange` in the backend. It is a semantic type rather than a
    /// source keyword because C# spells the operator without requiring a direct type reference.
    enum Range implements TypeSymbol {
        INSTANCE;

        @Override
        public String displayName() {
            return "System.Range";
        }

        @Override
        public JvmTypeKind jvmTypeKind() {
            return JvmTypeKind.REFERENCE;
        }

        @Override
        public boolean isValueType() {
            return true;
        }
    }

    /// The compiler-known C# `System.Index` value produced by the `^` prefix operator.
    ///
    /// It maps to `vsharp.runtime.VsIndex` in the backend.
    enum Index implements TypeSymbol {
        INSTANCE;

        @Override
        public String displayName() {
            return "System.Index";
        }

        @Override
        public JvmTypeKind jvmTypeKind() {
            return JvmTypeKind.REFERENCE;
        }

        @Override
        public boolean isValueType() {
            return true;
        }
    }

    /// An internal built-in function type used for naturally typed lambdas.
    record Function(List<TypeSymbol> parameters, TypeSymbol returns) implements TypeSymbol {
        public Function {
            parameters = List.copyOf(parameters);
            Objects.requireNonNull(returns, "returns");
            if (parameters.stream().anyMatch(type -> type == Inferred.INSTANCE
                    || type == Error.INSTANCE) || returns == Inferred.INSTANCE
                    || returns == Error.INSTANCE) {
                throw new IllegalArgumentException("function type needs resolved signature");
            }
        }

        @Override
        public String displayName() {
            return parameters.stream().map(TypeSymbol::displayName)
                    .collect(Collectors.joining(", ", "func(", ") -> " + returns.displayName()));
        }

        @Override
        public JvmTypeKind jvmTypeKind() { return JvmTypeKind.REFERENCE; }

        @Override
        public boolean isValueType() { return false; }
    }
}
