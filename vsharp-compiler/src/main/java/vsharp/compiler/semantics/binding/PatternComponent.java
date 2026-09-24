package vsharp.compiler.semantics.binding;

import java.util.Objects;
import vsharp.compiler.semantics.symbols.FieldSymbol;
import vsharp.compiler.semantics.symbols.FunctionSymbol;
import vsharp.compiler.semantics.types.TypeSymbol;
import vsharp.compiler.syntax.PatternSyntax;

/// One resolved component of a recursive pattern: how a subpattern's value is reached from
/// the value the enclosing pattern tests, and the static type of that value.
///
/// Positional (`is (1, var y)`) and property (`is { X: 1 }`) syntax both resolve to this
/// form, so member lookup happens exactly once, in binding, where its failures are ordinary
/// user diagnostics. Lowering and code generation then consume a decided access instead of
/// re-resolving names against a type they would have to re-derive.
public record PatternComponent(Kind kind, int index, FieldSymbol field,
        FunctionSymbol accessor, TypeSymbol type, PatternSyntax pattern) {

    /// The operation that produces the component's value.
    public enum Kind {
        /// An element of a value tuple, by position or by element name.
        TUPLE_ITEM,
        /// An instance field of a declared struct or record struct.
        FIELD,
        /// The length of an array.
        ARRAY_LENGTH,
        /// The length of a `string`.
        STRING_LENGTH,
        /// A no-argument Java accessor, for the one interop deconstruction V# curates:
        /// `java.util.Map.Entry` into `(GetKey(), GetValue())`.
        ACCESSOR
    }

    public PatternComponent {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(pattern, "pattern");
        if (kind == Kind.TUPLE_ITEM && index < 0) {
            throw new IllegalArgumentException("a tuple component needs an element index");
        }
        if (kind == Kind.FIELD && field == null) {
            throw new IllegalArgumentException("a field component needs a field symbol");
        }
        if (kind == Kind.ACCESSOR && accessor == null) {
            throw new IllegalArgumentException("an accessor component needs a method symbol");
        }
    }

    static PatternComponent tupleItem(int index, TypeSymbol type, PatternSyntax pattern) {
        return new PatternComponent(Kind.TUPLE_ITEM, index, null, null, type, pattern);
    }

    static PatternComponent field(FieldSymbol field, PatternSyntax pattern) {
        return new PatternComponent(Kind.FIELD, -1, field, null, field.type(), pattern);
    }

    static PatternComponent length(Kind kind, TypeSymbol type, PatternSyntax pattern) {
        return new PatternComponent(kind, -1, null, null, type, pattern);
    }

    /// A component read by calling a no-argument Java method. The *type* is the one the
    /// receiver's parameterization publishes, not the erased return the declaration carries.
    static PatternComponent accessor(FunctionSymbol accessor, TypeSymbol type,
            PatternSyntax pattern) {
        return new PatternComponent(Kind.ACCESSOR, -1, null, accessor, type, pattern);
    }
}
