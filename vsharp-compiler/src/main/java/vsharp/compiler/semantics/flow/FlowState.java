package vsharp.compiler.semantics.flow;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import vsharp.compiler.semantics.symbols.Symbol;

/// The flow facts that hold at one point in a function body.
///
/// C# §9.4 tracks two independent properties: whether control can reach the point at all,
/// and which variables are definitely assigned when it does. Both live here so that every
/// statement transition is a pure function from one state to the next, which keeps the
/// analysis re-runnable — the `goto` fixpoint replays the same walk with different label
/// entry states and must not observe residue from the previous pass.
///
/// Instances are immutable; `assigned` iterates in insertion order so that any diagnostic
/// derived from it is deterministic.
public record FlowState(boolean reachable, Set<Symbol> assigned) {

    private static final FlowState REACHABLE_EMPTY =
            new FlowState(true, Collections.unmodifiableSet(new LinkedHashSet<>()));

    public FlowState {
        Objects.requireNonNull(assigned, "assigned");
        assigned = Collections.unmodifiableSet(new LinkedHashSet<>(assigned));
    }

    /// The state at the start of a function body: reachable, nothing assigned yet.
    public static FlowState start() {
        return REACHABLE_EMPTY;
    }

    /// Whether `symbol` is definitely assigned here.
    public boolean isAssigned(Symbol symbol) {
        return assigned.contains(Objects.requireNonNull(symbol, "symbol"));
    }

    /// The same state with `symbol` definitely assigned.
    public FlowState assign(Symbol symbol) {
        Objects.requireNonNull(symbol, "symbol");
        if (assigned.contains(symbol)) {
            return this;
        }
        Set<Symbol> next = new LinkedHashSet<>(assigned);
        next.add(symbol);
        return new FlowState(reachable, next);
    }

    /// The same state with every symbol in `symbols` definitely assigned.
    public FlowState assignAll(Collection<? extends Symbol> symbols) {
        Objects.requireNonNull(symbols, "symbols");
        if (symbols.isEmpty() || assigned.containsAll(symbols)) {
            return this;
        }
        Set<Symbol> next = new LinkedHashSet<>(assigned);
        next.addAll(symbols);
        return new FlowState(reachable, next);
    }

    /// The same assignments, but control can no longer arrive here.
    public FlowState unreachable() {
        return reachable ? new FlowState(false, assigned) : this;
    }

    /// The same assignments, reachable again — used where a label or case re-enters.
    public FlowState reachableAgain() {
        return reachable ? this : new FlowState(true, assigned);
    }

    /// Joins two control-flow paths.
    ///
    /// An unreachable path contributes nothing: C# treats `if (c) x = 1; else return;`
    /// as leaving `x` assigned, because the `else` never reaches the join.
    public static FlowState merge(FlowState left, FlowState right) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        if (!left.reachable) {
            return right;
        }
        if (!right.reachable) {
            return left;
        }
        Set<Symbol> shared = new LinkedHashSet<>();
        for (Symbol symbol : left.assigned) {
            if (right.assigned.contains(symbol)) {
                shared.add(symbol);
            }
        }
        return new FlowState(true, shared);
    }

    /// Joins any number of paths; an empty list is unreachable with nothing assigned.
    public static FlowState merge(List<FlowState> states) {
        Objects.requireNonNull(states, "states");
        FlowState result = null;
        for (FlowState state : states) {
            result = result == null ? state : merge(result, state);
        }
        return result == null ? REACHABLE_EMPTY.unreachable() : result;
    }

    /// Whether both states agree on reachability and on the assigned set.
    public boolean sameAs(FlowState other) {
        Objects.requireNonNull(other, "other");
        return reachable == other.reachable && assigned.equals(other.assigned);
    }
}
