package vsharp.compiler.semantics.flow;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import vsharp.compiler.semantics.symbols.FunctionSymbol;
import vsharp.compiler.syntax.StatementSyntax;

/// What the flow pass learned about a compilation unit.
///
/// Diagnostics are reported into the caller's bag as the walk proceeds; this record keeps
/// the facts later stages need. Lowering must not emit code for unreachable statements —
/// the JVM verifier rejects some shapes that a naive translation of dead code produces —
/// and the backend needs to know whether a value-returning function can fall off its end.
///
/// Statement identity is used deliberately: two `break;` statements are equal as records
/// but are distinct program points.
public final class FlowResult {

    private final Set<StatementSyntax> unreachable;

    private final List<StatementSyntax> unreachableOrder;

    private final Map<FunctionSymbol, Boolean> endReachable;

    FlowResult(List<StatementSyntax> unreachableOrder,
            Map<FunctionSymbol, Boolean> endReachable) {
        this.unreachableOrder = List.copyOf(unreachableOrder);
        Set<StatementSyntax> identity =
                Collections.newSetFromMap(new IdentityHashMap<>());
        identity.addAll(this.unreachableOrder);
        this.unreachable = Collections.unmodifiableSet(identity);
        this.endReachable = Map.copyOf(endReachable);
    }

    /// Whether control can reach `statement`.
    public boolean isReachable(StatementSyntax statement) {
        return !unreachable.contains(Objects.requireNonNull(statement, "statement"));
    }

    /// Every statement control cannot reach, in source order of discovery.
    public List<StatementSyntax> unreachableStatements() {
        return unreachableOrder;
    }

    /// Whether the end point of `function`'s body is reachable, when the body was analysed.
    ///
    /// A `true` answer for a value-returning function means the program is ill-formed and a
    /// diagnostic was already reported; for a `void` function it means lowering must append
    /// an implicit `return`.
    public boolean endReachable(FunctionSymbol function) {
        Boolean value = endReachable.get(Objects.requireNonNull(function, "function"));
        return value != null && value;
    }

    /// The functions whose bodies this pass walked.
    public Set<FunctionSymbol> analysedFunctions() {
        return endReachable.keySet();
    }
}
