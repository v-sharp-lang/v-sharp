package vsharp.compiler.semantics.binding;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import vsharp.compiler.semantics.symbols.FunctionSymbol;
import vsharp.compiler.semantics.symbols.NamespaceSymbol;
import vsharp.compiler.semantics.symbols.Scope;
import vsharp.compiler.semantics.symbols.Symbol;
import vsharp.compiler.semantics.types.TypeSymbol;
import vsharp.compiler.source.SourceFile;
import vsharp.compiler.syntax.SyntaxNode;
import vsharp.compiler.syntax.PatternSyntax;
import vsharp.compiler.syntax.TypeSyntax;

/// Immutable result of declaration binding for one source file.
///
/// Syntax associations use identity rather than record equality: two recovery nodes may
/// legitimately have equal contents while still being different declarations.
public final class SemanticModel {

    private final NamespaceSymbol globalNamespace;
    private final Scope globalScope;
    private final List<Symbol> symbols;
    private final IdentityHashMap<SyntaxNode, Symbol> declaredSymbols;
    private final IdentityHashMap<SyntaxNode, Scope> syntaxScopes;
    private final IdentityHashMap<Symbol, Scope> symbolScopes;
    private final IdentityHashMap<TypeSyntax, TypeSymbol> resolvedTypes;
    private final IdentityHashMap<PatternSyntax, TypeSymbol> patternTypes;
    private final IdentityHashMap<TypeSymbol, PositionalLayout> positionalLayouts;
    private final FunctionSymbol topLevelFunction;
    private final JavaInterop javaInterop;
    private final Map<SourceFile, List<String>> importsByFile;
    private final List<String> globalImports;
    private final Map<SourceFile, Map<String, String>> aliasesByFile;
    private final Map<String, String> globalAliases;
    private final List<ScopedUsing> scopedUsings;

    SemanticModel(NamespaceSymbol globalNamespace, Scope globalScope, List<Symbol> symbols,
            Map<SyntaxNode, Symbol> declaredSymbols, Map<SyntaxNode, Scope> syntaxScopes,
            Map<Symbol, Scope> symbolScopes, Map<TypeSyntax, TypeSymbol> resolvedTypes,
            Map<PatternSyntax, TypeSymbol> patternTypes,
            Map<TypeSymbol, PositionalLayout> positionalLayouts,
            FunctionSymbol topLevelFunction, JavaInterop javaInterop,
            Map<SourceFile, List<String>> importsByFile, List<String> globalImports,
            Map<SourceFile, Map<String, String>> aliasesByFile,
            Map<String, String> globalAliases, List<ScopedUsing> scopedUsings) {
        this.javaInterop = Objects.requireNonNull(javaInterop, "javaInterop");
        this.importsByFile = Map.copyOf(importsByFile);
        this.globalImports = List.copyOf(globalImports);
        this.aliasesByFile = Map.copyOf(aliasesByFile);
        this.globalAliases = Map.copyOf(globalAliases);
        this.scopedUsings = List.copyOf(scopedUsings);
        this.globalNamespace = Objects.requireNonNull(globalNamespace, "globalNamespace");
        this.globalScope = Objects.requireNonNull(globalScope, "globalScope");
        this.symbols = List.copyOf(symbols);
        this.declaredSymbols = identityCopy(declaredSymbols);
        this.syntaxScopes = identityCopy(syntaxScopes);
        this.symbolScopes = identityCopy(symbolScopes);
        this.resolvedTypes = identityCopy(resolvedTypes);
        this.patternTypes = identityCopy(patternTypes);
        this.positionalLayouts = identityCopy(positionalLayouts);
        this.topLevelFunction = topLevelFunction;
    }

    private static <K, V> IdentityHashMap<K, V> identityCopy(Map<K, V> source) {
        IdentityHashMap<K, V> result = new IdentityHashMap<>();
        result.putAll(source);
        return result;
    }

    public NamespaceSymbol globalNamespace() {
        return globalNamespace;
    }

    public Scope globalScope() {
        return globalScope;
    }

    /// The Java module-path resolver used while binding this model's declarations. Later
    /// phases reuse it so a Java type observed during declaration binding and the same type
    /// observed during expression binding are one symbol.
    public JavaInterop javaInterop() {
        return javaInterop;
    }

    /// A `using` written inside a namespace body, with the namespace it applies within.
    ///
    /// C# scopes such a directive to that body, so it is carried with its owner rather than
    /// folded into the file: two namespaces in one file must not see each other's imports.
    /// `alias` is null for a plain namespace import, and `target` is then the imported namespace.
    public record ScopedUsing(SourceFile file, String namespaceName, String alias, String target) {
        public ScopedUsing {
            Objects.requireNonNull(namespaceName, "namespaceName");
            Objects.requireNonNull(target, "target");
        }

        /// Whether this directive is in scope for a declaration owned by `ownerName`, which is
        /// true within the namespace itself and within anything nested inside it.
        boolean appliesTo(SourceFile queried, String ownerName) {
            if (file != null && queried != null && !file.name().equals(queried.name())) {
                return false;
            }
            return ownerName.equals(namespaceName) || ownerName.startsWith(namespaceName + ".");
        }
    }

    /// The namespaces imported by a `using` inside the namespace body owning `ownerName`.
    public List<String> scopedImports(SourceFile file, String ownerName) {
        if (scopedUsings.isEmpty() || ownerName == null) {
            return List.of();
        }
        List<String> found = new java.util.ArrayList<>();
        for (ScopedUsing using : scopedUsings) {
            if (using.alias() == null && using.appliesTo(file, ownerName)
                    && !found.contains(using.target())) {
                found.add(using.target());
            }
        }
        return List.copyOf(found);
    }

    /// The alias `alias` was given by a `using` inside the namespace body owning `ownerName`.
    public String scopedAliasTarget(SourceFile file, String ownerName, String alias) {
        if (ownerName == null) {
            return null;
        }
        for (ScopedUsing using : scopedUsings) {
            if (alias.equals(using.alias()) && using.appliesTo(file, ownerName)) {
                return using.target();
            }
        }
        return null;
    }

    /// The name `using X = Y;` bound `alias` to in `file`, or `null` when nothing did.
    ///
    /// A file-local alias wins over a `global using` one, matching the rule every other import
    /// follows. The answer is the *written* target, unresolved: an alias is a spelling, so it is
    /// substituted before resolution and then resolved by the ordinary rules, which is what lets
    /// one alias name a V# type, a Java type or a namespace without three mechanisms.
    public String aliasTarget(SourceFile file, String alias) {
        Objects.requireNonNull(alias, "alias");
        Map<String, String> local = file == null ? null : aliasesByFile.get(file);
        String found = local == null ? null : local.get(alias);
        return found != null ? found : globalAliases.get(alias);
    }

    /// The namespaces `file` imported with a plain `using`, file-local ones first, followed by
    /// the compilation's `global using` namespaces. Expression binding needs the same list
    /// declaration binding used, because an imported Java package is what makes a simple name
    /// such as `Arrays` denote a type at all.
    public List<String> importedNamespaces(SourceFile file) {
        List<String> local = file == null ? null : importsByFile.get(file);
        if (local == null) {
            return globalImports;
        }
        if (globalImports.isEmpty()) {
            return local;
        }
        List<String> all = new java.util.ArrayList<>(local);
        for (String global : globalImports) {
            if (!all.contains(global)) {
                all.add(global);
            }
        }
        return List.copyOf(all);
    }

    /// Every successfully materialised declaration, in deterministic binding order.
    public List<Symbol> symbols() {
        return symbols;
    }

    public Optional<Symbol> declaredSymbol(SyntaxNode declaration) {
        return Optional.ofNullable(declaredSymbols.get(Objects.requireNonNull(
                declaration, "declaration")));
    }

    public Optional<Scope> scopeFor(SyntaxNode syntax) {
        return Optional.ofNullable(syntaxScopes.get(Objects.requireNonNull(syntax, "syntax")));
    }

    public Optional<Scope> scopeOf(Symbol symbol) {
        return Optional.ofNullable(symbolScopes.get(Objects.requireNonNull(symbol, "symbol")));
    }

    public Optional<TypeSymbol> typeOf(TypeSyntax syntax) {
        return Optional.ofNullable(resolvedTypes.get(Objects.requireNonNull(syntax, "syntax")));
    }

    /// The type a bare-name pattern was resolved to test, when it named one.
    ///
    /// `o is Red` is a constant pattern when `Red` names a constant and a type pattern when it
    /// names a type, and the grammar cannot tell them apart, so the parser always produces a
    /// [PatternSyntax.Constant] and name resolution decides. The answer is recorded here
    /// because deciding it needs the same type lookup - including `using` imports and the Java
    /// module path - that every other type position uses.
    public Optional<TypeSymbol> patternType(PatternSyntax pattern) {
        return Optional.ofNullable(patternTypes.get(Objects.requireNonNull(pattern, "pattern")));
    }

    /// The positional shape of `type` when it is a `record struct` with components.
    ///
    /// Object creation, `with`, deconstruction and positional patterns all need the same
    /// answer - which fields, in which order, and the constructor that fills them - so the
    /// decision is made once during declaration binding and recorded here rather than being
    /// re-derived from syntax by each consumer.
    public Optional<PositionalLayout> positionalLayout(TypeSymbol type) {
        return Optional.ofNullable(positionalLayouts.get(Objects.requireNonNull(type, "type")));
    }

    public Optional<FunctionSymbol> topLevelFunction() {
        return Optional.ofNullable(topLevelFunction);
    }
}
