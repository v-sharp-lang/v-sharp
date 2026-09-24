package vsharp.compiler.semantics.binding;

import java.util.Objects;
import vsharp.compiler.semantics.types.TypeSymbol;

/// The two types a list pattern tests against: the collection it matches and the element a
/// non-slice subpattern sees.
///
/// Resolved during binding, for the same reason a recursive pattern's components are: the
/// element type of a `string` is `char` and of an `int[]` is `int`, and re-deriving that in
/// lowering would be a second copy of a rule that must not drift.
public record ListPatternShape(TypeSymbol collectionType, TypeSymbol elementType) {
    public ListPatternShape {
        Objects.requireNonNull(collectionType, "collectionType");
        Objects.requireNonNull(elementType, "elementType");
    }
}
