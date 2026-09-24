package vsharp.compiler.semantics.symbols;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/// An immutable lexical scope with deterministic declaration order.
///
/// The parent link points outward only. Child scopes are indexed by [SemanticModel], which
/// avoids cycles between immutable symbols and scopes.
public final class Scope {

    private final Symbol owner;
    private final Scope parent;
    private final List<Symbol> symbols;
    private final List<Scope> importedScopes;
    private final Map<String, List<Symbol>> declarations;

    private Scope(Symbol owner, Scope parent, List<? extends Symbol> symbols, List<Scope> importedScopes) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.parent = parent;
        this.symbols = List.copyOf(symbols);
        this.importedScopes = List.copyOf(importedScopes);

        Map<String, List<Symbol>> mutable = new LinkedHashMap<>();
        for (Symbol symbol : symbols) {
            Objects.requireNonNull(symbol, "symbol");
            mutable.computeIfAbsent(symbol.name(), ignored -> new ArrayList<>()).add(symbol);
        }
        Map<String, List<Symbol>> frozen = new LinkedHashMap<>();
        mutable.forEach((name, group) -> frozen.put(name, List.copyOf(group)));
        this.declarations = Collections.unmodifiableMap(frozen);
    }

    /// Creates the sole scope without a parent.
    public static Scope root(Symbol owner, List<? extends Symbol> symbols) {
        return new Scope(owner, null, symbols, List.of());
    }

    /// Creates a lexical child of `parent`.
    public static Scope nested(Symbol owner, Scope parent, List<? extends Symbol> symbols) {
        return new Scope(owner, Objects.requireNonNull(parent, "parent"), symbols, List.of());
    }

    /// Creates a lexical scope with imported scopes.
    public static Scope withImports(Symbol owner, Scope parent, List<? extends Symbol> symbols, List<Scope> importedScopes) {
        return new Scope(owner, parent, symbols, importedScopes);
    }

    public Symbol owner() {
        return owner;
    }

    public Optional<Scope> parent() {
        return Optional.ofNullable(parent);
    }

    public List<Scope> importedScopes() {
        return importedScopes;
    }

    /// Declarations in source order, including every overload in overload order.
    public List<Symbol> symbols() {
        return symbols;
    }

    /// Names declared directly in this scope, in first-declaration order.
    public List<String> declaredNames() {
        return List.copyOf(declarations.keySet());
    }

    /// All declarations with `name` in this scope, or an empty list.
    public List<Symbol> lookupLocal(String name) {
        Objects.requireNonNull(name, "name");
        return declarations.getOrDefault(name, List.of());
    }

    /// The nearest lexical declaration group with `name`, or an empty list.
    public List<Symbol> lookup(String name) {
        Objects.requireNonNull(name, "name");
        for (Scope current = this; current != null; current = current.parent) {
            List<Symbol> found = current.declarations.get(name);
            if (found != null) {
                return found;
            }
            for (Scope imported : current.importedScopes) {
                List<Symbol> importedFound = imported.lookupLocal(name);
                if (!importedFound.isEmpty()) {
                    return importedFound;
                }
            }
        }
        return List.of();
    }
}
