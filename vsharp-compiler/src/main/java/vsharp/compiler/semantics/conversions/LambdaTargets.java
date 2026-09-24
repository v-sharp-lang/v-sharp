package vsharp.compiler.semantics.conversions;

import java.util.List;
import vsharp.compiler.semantics.binding.BoundExpression;
import vsharp.compiler.semantics.types.TypeSymbol;

/// Decides whether an as-yet-untyped lambda can become a value of a target type.
///
/// The anonymous-function conversion (C# §10.7) is the one conversion that cannot be answered
/// from the source and target types alone: it depends on the lambda's parameter count, on
/// whether the target declares exactly one abstract method, and on that method's signature.
/// Answering it needs the binder's syntax and the Java module path, so [Conversions] takes
/// this decision as a collaborator instead of reaching for either itself.
@FunctionalInterface
public interface LambdaTargets {

    /// Refuses every lambda. Used by classification call sites that are comparing types
    /// rather than converting an argument, where no lambda can appear.
    LambdaTargets NONE = (lambda, target) -> false;

    /// Whether `lambda` - a deferred node of form `lambda` - converts to `target`.
    boolean convertible(BoundExpression lambda, TypeSymbol target);

    /// The parameter types `lambda` *wrote*, or `null` when it wrote none.
    ///
    /// An explicitly typed lambda is the one untyped argument that can still say something
    /// before it is bound, and for a static generic method it is often the only thing that
    /// can: in `Collectors.GroupingBy((string w) =&gt; w.Length)` there is no receiver, and `T`
    /// appears nowhere but the classifier's own parameter. Inference reads the written types
    /// through this seam rather than reaching for the binder's syntax tables.
    default List<TypeSymbol> writtenParameters(BoundExpression lambda) {
        return null;
    }

    /// The parameter types of `target`'s single abstract method as that target parameterises
    /// them, or `null` when `target` is no functional interface.
    default List<TypeSymbol> functionalParameters(TypeSymbol target) {
        return null;
    }

    /// Whether `target`'s single abstract method returns nothing. Overload resolution
    /// uses it to prefer a value-returning target for a lambda that produces a value, which is
    /// how `ExecutorService.Submit`'s `Callable`/`Runnable` pair is meant to separate.
    default boolean returnsVoid(TypeSymbol target) {
        return false;
    }
}
