package vsharp.compiler.semantics.binding;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import vsharp.compiler.diagnostics.DiagnosticBag;
import vsharp.compiler.diagnostics.DiagnosticCode;
import vsharp.compiler.semantics.constants.ConstantSlot;
import vsharp.compiler.semantics.symbols.ContainerSymbol;
import vsharp.compiler.semantics.symbols.EnumMemberSymbol;
import vsharp.compiler.semantics.symbols.FieldSymbol;
import vsharp.compiler.semantics.symbols.FunctionSymbol;
import vsharp.compiler.semantics.symbols.LocalSymbol;
import vsharp.compiler.semantics.symbols.NamedTypeSymbol;
import vsharp.compiler.semantics.symbols.NamespaceSymbol;
import vsharp.compiler.semantics.symbols.ParameterSymbol;
import vsharp.compiler.semantics.symbols.Scope;
import vsharp.compiler.semantics.symbols.SourceLocation;
import vsharp.compiler.semantics.symbols.Symbol;
import vsharp.compiler.semantics.symbols.SymbolKind;
import vsharp.compiler.semantics.symbols.TypeParameterSymbol;
import vsharp.compiler.semantics.types.BuiltinType;
import vsharp.compiler.semantics.types.JvmTypeKind;
import vsharp.compiler.semantics.types.TaskTypes;
import vsharp.compiler.semantics.types.TypeSymbol;
import vsharp.compiler.source.SourceFile;
import vsharp.compiler.source.SourceSpan;
import vsharp.compiler.syntax.AuxiliarySyntax;
import vsharp.compiler.syntax.DeclarationSyntax;
import vsharp.compiler.syntax.ExpressionSyntax;
import vsharp.compiler.syntax.PatternSyntax;
import vsharp.compiler.syntax.StatementSyntax;
import vsharp.compiler.syntax.SyntaxKind;
import vsharp.compiler.syntax.SyntaxNode;
import vsharp.compiler.syntax.TypeSyntax;

/// Builds declaration symbols and lexical scopes without binding expressions.
///
/// The binder deliberately performs header collection before member binding. A type can
/// therefore refer to another type declared later in the same source file, while symbol
/// and scope iteration remains deterministic.
public final class DeclarationBinder {

    /// The corelib declarations that are C# *classes*, written as `struct` only because V#
    /// has no `class` keyword to spell them with. See [CorelibCarriers].
    private static final Set<String> CORELIB_REFERENCE_TYPES =
            vsharp.compiler.semantics.types.CorelibCarriers.referenceTypeNames();

    private DeclarationBinder() {
        throw new AssertionError("No instances");
    }

    public static SemanticModel bind(List<SourceFile> files,
            List<AuxiliarySyntax.CompilationUnit> units, DiagnosticBag diagnostics) {
        return bind(files, units, diagnostics, List.of());
    }

    /// Binds with `classpath` declared: Java types are resolved from the module path and
    /// from those entries, and from nothing else.
    public static SemanticModel bind(List<SourceFile> files,
            List<AuxiliarySyntax.CompilationUnit> units, DiagnosticBag diagnostics,
            List<java.nio.file.Path> classpath) {
        Objects.requireNonNull(classpath, "classpath");
        Objects.requireNonNull(files, "files");
        Objects.requireNonNull(units, "units");
        Objects.requireNonNull(diagnostics, "diagnostics");
        if (files.size() != units.size()) {
            throw new IllegalArgumentException("files and units must be equal in size");
        }
        HeaderBinder binder = new HeaderBinder(diagnostics);
        for (int i = 0; i < files.size(); i++) {
            binder.collect(files.get(i), units.get(i));
        }
        return new Binder(binder.bind(), units, diagnostics, new JavaInterop(classpath)).bind();
    }

    private static final class HeaderBinder {
        private final DiagnosticBag diagnostics;
        private final NamespaceNode root;
        private final List<Symbol> symbols = new ArrayList<>();
        private final IdentityHashMap<SyntaxNode, Symbol> declaredSymbols = new IdentityHashMap<>();
        private final IdentityHashMap<DeclarationSyntax, Symbol> headers = new IdentityHashMap<>();
        private final LinkedHashMap<TypeKey, Symbol> declaredTypeHeaders = new LinkedHashMap<>();
        private final LinkedHashMap<String, List<Symbol>> headersByOwner = new LinkedHashMap<>();
        private final IdentityHashMap<SyntaxNode, SourceFile> syntaxFiles = new IdentityHashMap<>();

        HeaderBinder(DiagnosticBag diagnostics) {
            this.diagnostics = diagnostics;
            vsharp.compiler.source.SourceFile dummy = vsharp.compiler.source.SourceFile.of("<global>", "");
            vsharp.compiler.semantics.symbols.SourceLocation loc = new vsharp.compiler.semantics.symbols.SourceLocation(dummy, new vsharp.compiler.source.SourceSpan(0,0));
            this.root = new NamespaceNode("", "", loc);
        }

        void collect(SourceFile file, AuxiliarySyntax.CompilationUnit unit) {
            syntaxFiles.put(unit, file);
            for (DeclarationSyntax declaration : unit.declarations()) {
                syntaxFiles.put(declaration, file);
            }
            root.declarations.addAll(unit.declarations().stream()
                    .filter(declaration -> !(declaration instanceof DeclarationSyntax.Namespace))
                    .toList());
            root.statements.addAll(unit.statements());
            for (StatementSyntax stmt : unit.statements()) {
                syntaxFiles.put(stmt, file);
            }
            collectNamespaces(root, unit.declarations(), file);
        }

        GlobalHeaders bind() {
            createNamespaceSymbols(root, true);
            registerNamespaceHeaders(root);
            return new GlobalHeaders(root, declaredTypeHeaders, headersByOwner, headers,
                    symbols, declaredSymbols, syntaxFiles);
        }

        private void collectNamespaces(NamespaceNode owner, List<DeclarationSyntax> declarations, SourceFile file) {
            for (DeclarationSyntax declaration : declarations) {
                if (!(declaration instanceof DeclarationSyntax.Namespace namespace)) {
                    continue;
                }
                NamespaceNode target = owner;
                for (TypeSyntax.Segment segment : namespace.name().segments()) {
                    String fullName = qualify(target.fullName, segment.identifier());
                    target = target.children.computeIfAbsent(segment.identifier(),
                            ignored -> new NamespaceNode(segment.identifier(), fullName,
                                    new SourceLocation(file, segment.span())));
                }
                target.parts.add(namespace);
                syntaxFiles.put(namespace, file);
                for (DeclarationSyntax member : namespace.declarations()) {
                    syntaxFiles.put(member, file);
                    if (!(member instanceof DeclarationSyntax.Namespace)) {
                        target.declarations.add(member);
                    }
                }
                target.statements.addAll(namespace.statements());
                for (StatementSyntax stmt : namespace.statements()) {
                    syntaxFiles.put(stmt, file);
                }
                collectNamespaces(target, namespace.declarations(), file);
            }
        }

        private void createNamespaceSymbols(NamespaceNode node, boolean global) {
            node.symbol = new NamespaceSymbol(global ? "<global>" : node.name,
                    node.fullName, node.location);
            symbols.add(node.symbol);
            for (DeclarationSyntax.Namespace part : node.parts) {
                declaredSymbols.put(part, node.symbol);
            }
            node.children.values().forEach(child -> createNamespaceSymbols(child, false));
        }

        private void registerNamespaceHeaders(NamespaceNode node) {
            for (DeclarationSyntax declaration : node.declarations) {
                registerHeader(node.fullName, declaration, node);
            }
            node.children.values().forEach(this::registerNamespaceHeaders);
        }

        private void registerHeader(String ownerName, DeclarationSyntax declaration, NamespaceNode namespaceOwner) {
            SourceFile file = syntaxFiles.get(declaration);
            switch (declaration) {
                case DeclarationSyntax.StaticContainer container -> {
                    ContainerSymbol symbol = new ContainerSymbol(container.name(),
                            qualify(ownerName, container.name()), new SourceLocation(file, container.span()),
                            container.typeParameters().size(), container.modifiers());
                    registerTypeLikeHeader(ownerName, container.name(), symbol.arity(), symbol,
                            declaration, namespaceOwner, file);
                    for (DeclarationSyntax member : container.members()) {
                        syntaxFiles.put(member, file);
                        registerHeader(symbol.qualifiedName(), member, null);
                    }
                }
                case DeclarationSyntax.Struct structure -> {
                    String structName = qualify(ownerName, structure.name());
                    NamedTypeSymbol.DeclaredKind kind = structure.record()
                            ? NamedTypeSymbol.DeclaredKind.RECORD_STRUCT
                            : NamedTypeSymbol.DeclaredKind.STRUCT;
                    // Corelib spells every declaration `struct` because V# has no `class`
                    // keyword, but a handful of them *are* C# classes and are carried as JVM
                    // references. Their kind has to say so, or `e != null` is rejected as a
                    // null comparison against a value type and no `catch` body can test its
                    // exception.
                    if (CORELIB_REFERENCE_TYPES.contains(structName)) {
                        kind = NamedTypeSymbol.DeclaredKind.CLASS;
                    }
                    NamedTypeSymbol symbol = new NamedTypeSymbol(structure.name(),
                            structName, new SourceLocation(file, structure.span()), kind,
                            structure.typeParameters().size());
                    registerTypeLikeHeader(ownerName, structure.name(), symbol.arity(), symbol,
                            declaration, namespaceOwner, file);
                    for (DeclarationSyntax member : structure.members()) {
                        syntaxFiles.put(member, file);
                        registerHeader(symbol.qualifiedName(), member, null);
                    }
                }
                case DeclarationSyntax.Enum enumeration -> {
                    NamedTypeSymbol symbol = new NamedTypeSymbol(enumeration.name(),
                            qualify(ownerName, enumeration.name()), new SourceLocation(file, enumeration.span()),
                            NamedTypeSymbol.DeclaredKind.ENUM, 0);
                    registerTypeLikeHeader(ownerName, enumeration.name(), 0, symbol, declaration,
                            namespaceOwner, file);
                }
                default -> {}
            }
        }

        private void registerTypeLikeHeader(String ownerName, String name, int arity,
                Symbol proposed, DeclarationSyntax declaration, NamespaceNode namespaceOwner, SourceFile file) {
            TypeKey key = new TypeKey(ownerName, name, arity);
            Symbol existing = declaredTypeHeaders.get(key);
            if (existing != null) {
                DiagnosticCode code = namespaceOwner == null
                        ? DiagnosticCode.DUPLICATE_MEMBER
                        : DiagnosticCode.DUPLICATE_NAMESPACE_MEMBER;
                diagnostics.report(code, file, declaration.span(), ownerDisplay(ownerName), name);
                headers.put(declaration, existing);
                return;
            }
            if (namespaceOwner != null && namespaceOwner.children.containsKey(name)) {
                diagnostics.report(DiagnosticCode.DUPLICATE_NAMESPACE_MEMBER, file,
                        declaration.span(), ownerDisplay(ownerName), name);
            }
            declaredTypeHeaders.put(key, proposed);
            headers.put(declaration, proposed);
            headersByOwner.computeIfAbsent(ownerName, ignored -> new ArrayList<>()).add(proposed);
            symbols.add(proposed);
            declaredSymbols.put(declaration, proposed);
        }
        
        private static String qualify(String parent, String child) {
            return parent.isEmpty() ? child : parent + "." + child;
        }

        private static String ownerDisplay(String owner) {
            return owner.isEmpty() ? "<global namespace>" : owner;
        }
    }

    private static final class Binder {

        /// Source-facing name of every lambda, kept unspellable so it cannot collide.
        static final String LAMBDA_NAME = LambdaNames.LAMBDA_NAME;

        /// Name of the synthesized `record struct` constructor. It is the JVM's own
        /// constructor name, which no V# identifier can spell, so it never collides with a
        /// declared member and can never be reached by name resolution.
        private static final String CONSTRUCTOR_NAME = "<init>";

        private SourceFile file;
        private final AuxiliarySyntax.CompilationUnit unit;
        private final List<AuxiliarySyntax.CompilationUnit> units;
        private final DiagnosticBag diagnostics;
        private final List<Symbol> allSymbols = new ArrayList<>();
        /// Qualified names of every declared `static class`, used to tell a top level container
        /// from a nested one when an extension declaration is validated.
        private final Set<String> containerNames = new java.util.LinkedHashSet<>();
        private final IdentityHashMap<SyntaxNode, Symbol> declaredSymbols =
                new IdentityHashMap<>();
        private final IdentityHashMap<SyntaxNode, Scope> syntaxScopes = new IdentityHashMap<>();
        private final IdentityHashMap<Symbol, Scope> symbolScopes = new IdentityHashMap<>();
        private final IdentityHashMap<TypeSyntax, TypeSymbol> resolvedTypes =
                new IdentityHashMap<>();
        /// Bare-name patterns that name resolution proved to be type patterns.
        private final IdentityHashMap<PatternSyntax, TypeSymbol> patternTypes =
                new IdentityHashMap<>();
        private final IdentityHashMap<DeclarationSyntax, Symbol> headers =
                new IdentityHashMap<>();
        private final IdentityHashMap<FunctionSymbol, CallableContext> callableContexts =
                new IdentityHashMap<>();
        private final IdentityHashMap<FunctionSymbol, DeclarationSyntax> callableDeclarations =
                new IdentityHashMap<>();
        private final LinkedHashMap<TypeKey, Symbol> declaredTypeHeaders = new LinkedHashMap<>();
        private final LinkedHashMap<String, List<Symbol>> headersByOwner = new LinkedHashMap<>();
        private final Set<Symbol> boundOwners = Collections.newSetFromMap(new IdentityHashMap<>());
        /// The component/constructor shape of every `record struct` this binder materialised.
        /// Keyed by the type symbol, which is shared across files because type headers are
        /// global, so a record struct declared in one file is positionally usable in all.
        private final IdentityHashMap<TypeSymbol, PositionalLayout> positionalLayouts =
                new IdentityHashMap<>();
        private final NamespaceNode root;
        private int localOrdinal;
        private int lambdaOrdinal;
        private FunctionSymbol topLevelFunction;

        private final IdentityHashMap<SyntaxNode, SourceFile> syntaxFiles;
        private final JavaInterop javaInterop;

        /// The namespaces each file imported with a plain `using`, in source order. Type
        /// resolution consults them for the file it is currently binding, which is what makes
        /// `using System;` reach `Exception` in a `catch` clause the same way it reaches
        /// `Console` in an expression.
        private final IdentityHashMap<SourceFile, List<String>> importsByFile =
                new IdentityHashMap<>();
        /// `global using` namespaces, which C# applies to every file of the compilation.
        private final List<String> globalImports = new ArrayList<>();
        private final IdentityHashMap<SourceFile, Map<String, String>> aliasesByFile =
                new IdentityHashMap<>();
        private final Map<String, String> globalAliases = new LinkedHashMap<>();
        private final List<SemanticModel.ScopedUsing> scopedUsings = new ArrayList<>();

        Binder(GlobalHeaders globalHeaders, List<AuxiliarySyntax.CompilationUnit> units,
                DiagnosticBag diagnostics, JavaInterop javaInterop) {
            this.javaInterop = javaInterop;
            this.units = units;
            this.file = null;
            this.unit = null;
            this.diagnostics = diagnostics;
            this.root = globalHeaders.root();
            this.declaredTypeHeaders.putAll(globalHeaders.declaredTypeHeaders());
            this.headersByOwner.putAll(globalHeaders.headersByOwner());
            this.headers.putAll(globalHeaders.headers());
            this.allSymbols.addAll(globalHeaders.symbols());
            for (Symbol symbol : globalHeaders.symbols()) {
                if (symbol instanceof ContainerSymbol container) {
                    this.containerNames.add(container.qualifiedName());
                }
            }
            this.syntaxFiles = new IdentityHashMap<>(globalHeaders.syntaxFiles());
            
            // To ensure local declaredSymbols in the file are visible in the semantic model, we prepopulate with global ones
            this.declaredSymbols.putAll(globalHeaders.declaredSymbols());
            collectImports();
        }

        /// Records the `using` namespaces per file before any declaration is bound, because type
        /// positions are resolved during declaration binding - earlier than the imported scopes
        /// `bind()` builds for expression lookup.
        ///
        /// `using static` is not a namespace import - it brings members into scope - and is left
        /// out here rather than silently treated as one. `using X = Y;` is an alias: it is not a
        /// namespace import either, but its spelling is recorded so that name resolution can
        /// substitute it.
        private void collectImports() {
            for (AuxiliarySyntax.CompilationUnit unit : units) {
                SourceFile owning = syntaxFiles.get(unit);
                List<String> imported = new ArrayList<>();
                Map<String, String> aliases = new LinkedHashMap<>();
                for (AuxiliarySyntax.UsingDirective using : unit.usings()) {
                    if (using.isStatic()) {
                        continue;
                    }
                    if (using.alias() != null) {
                        String target = using.target().segments().stream()
                                .map(TypeSyntax.Segment::identifier)
                                .reduce((left, right) -> left + "." + right)
                                .orElse("");
                        if (!target.isEmpty()) {
                            if (using.global()) {
                                globalAliases.putIfAbsent(using.alias(), target);
                            } else {
                                aliases.putIfAbsent(using.alias(), target);
                            }
                        }
                        continue;
                    }
                    String namespaceName = using.target().segments().stream()
                            .map(TypeSyntax.Segment::identifier)
                            .reduce((left, right) -> left + "." + right)
                            .orElse("");
                    if (namespaceName.isEmpty()) {
                        continue;
                    }
                    if (using.global()) {
                        if (!globalImports.contains(namespaceName)) {
                            globalImports.add(namespaceName);
                        }
                    } else if (!imported.contains(namespaceName)) {
                        imported.add(namespaceName);
                    }
                }
                if (owning != null && !imported.isEmpty()) {
                    importsByFile.put(owning, List.copyOf(imported));
                }
                if (owning != null && !aliases.isEmpty()) {
                    aliasesByFile.put(owning, Map.copyOf(aliases));
                }
                for (DeclarationSyntax declaration : unit.declarations()) {
                    collectScopedImports(owning, declaration, "");
                }
            }
        }

        /// Records the `using` directives written inside a namespace body, with the namespace
        /// they apply within. C# scopes them there, so they are kept with their owner
        /// rather than folded into the file: two namespaces in one file must not share imports.
        private void collectScopedImports(SourceFile owning, DeclarationSyntax declaration,
                String enclosing) {
            if (!(declaration instanceof DeclarationSyntax.Namespace namespace)) {
                return;
            }
            String written = namespace.name().segments().stream()
                    .map(TypeSyntax.Segment::identifier)
                    .reduce((left, right) -> left + "." + right)
                    .orElse("");
            String fullName = joinQualified(enclosing, written);
            for (AuxiliarySyntax.UsingDirective using : namespace.usings()) {
                if (using.isStatic()) {
                    continue;
                }
                String target = using.target().segments().stream()
                        .map(TypeSyntax.Segment::identifier)
                        .reduce((left, right) -> left + "." + right)
                        .orElse("");
                if (!target.isEmpty()) {
                    scopedUsings.add(new SemanticModel.ScopedUsing(owning, fullName,
                            using.alias(), target));
                }
            }
            for (DeclarationSyntax nested : namespace.declarations()) {
                collectScopedImports(owning, nested, fullName);
            }
        }

        /// The namespace imports written inside the namespace body owning `ownerName`.
        private List<String> scopedImports(String ownerName) {
            List<String> found = new ArrayList<>();
            for (SemanticModel.ScopedUsing using : scopedUsings) {
                if (using.alias() == null && appliesHere(using, ownerName)
                        && !found.contains(using.target())) {
                    found.add(using.target());
                }
            }
            return found;
        }

        /// The alias written inside the namespace body owning `ownerName`, or `null`.
        private String scopedAliasFor(String ownerName, String alias) {
            for (SemanticModel.ScopedUsing using : scopedUsings) {
                if (alias.equals(using.alias()) && appliesHere(using, ownerName)) {
                    return using.target();
                }
            }
            return null;
        }

        private boolean appliesHere(SemanticModel.ScopedUsing using, String ownerName) {
            if (using.file() != null && file != null
                    && !using.file().name().equals(file.name())) {
                return false;
            }
            return ownerName != null && (ownerName.equals(using.namespaceName())
                    || ownerName.startsWith(using.namespaceName() + "."));
        }

        /// The target `using X = Y;` bound `alias` to for the file being bound, or `null`.
        private String aliasTargetFor(String alias) {
            Map<String, String> local = file == null ? null : aliasesByFile.get(file);
            String found = local == null ? null : local.get(alias);
            return found != null ? found : globalAliases.get(alias);
        }

        /// The namespaces imported at the file currently being bound, file-local ones first.
        private List<String> importedNamespaces() {
            List<String> local = file == null ? null : importsByFile.get(file);
            if (local == null) {
                return globalImports;
            }
            if (globalImports.isEmpty()) {
                return local;
            }
            List<String> all = new ArrayList<>(local);
            for (String global : globalImports) {
                if (!all.contains(global)) {
                    all.add(global);
                }
            }
            return all;
        }

        /// Reports a `using` that names neither a declared V# namespace nor a package the module
        /// path publishes. C# rejects such an import with CS0246, and V# must too: an
        /// unchecked import is a silent no-op, so a mistyped JDK package (`java.util.strem`)
        /// used to surface far away as a missing type instead of at the line that is wrong.
        ///
        /// `using static` and `using X = Y;` name a type rather than a namespace and are checked
        /// where they are resolved, so they are left alone here.
        private void reportUnknownImport(AuxiliarySyntax.CompilationUnit compilationUnit,
                AuxiliarySyntax.UsingDirective using) {
            if (using.alias() != null) {
                return;
            }
            String namespaceName = using.target().segments().stream()
                    .map(TypeSyntax.Segment::identifier)
                    .reduce((left, right) -> left + "." + right)
                    .orElse("");
            if (namespaceName.isEmpty()) {
                return;
            }
            String unresolved = JavaInterop.unresolvedSystemModule(namespaceName);
            if (unresolved == null && javaInterop.isKnownNamespace(namespaceName)) {
                return;
            }
            SourceFile owning = syntaxFiles.get(compilationUnit);
            if (owning == null) {
                return;
            }
            if (unresolved != null) {
                // The package exists in the installed image but no class in it can be loaded,
                // which is an environment fault rather than a source one.
                diagnostics.report(DiagnosticCode.SYSTEM_MODULE_NOT_RESOLVED, owning,
                        using.target().span(), namespaceName, unresolved);
                return;
            }
            diagnostics.report(DiagnosticCode.TYPE_OR_NAMESPACE_NOT_FOUND, owning,
                    using.target().span(), namespaceName);
        }

        SemanticModel bind() {
            Scope globalScope = bindNamespace(root, null);
            
            for (AuxiliarySyntax.CompilationUnit u : units) {
                if (u.usings().isEmpty()) {
                    syntaxScopes.put(u, globalScope);
                } else {
                    List<Scope> importedScopes = new java.util.ArrayList<>();
                    for (AuxiliarySyntax.UsingDirective using : u.usings()) {
                        if (using.isStatic()) {
                            Scope members = staticMemberScope(using.target(), globalScope);
                            if (members == null) {
                                reportUnknownImport(u, using);
                            } else {
                                importedScopes.add(members);
                            }
                            continue;
                        }
                        Scope currentScope = globalScope;
                        NamespaceSymbol targetNs = null;
                        for (vsharp.compiler.syntax.TypeSyntax.Segment segment : using.target().segments()) {
                            java.util.List<Symbol> found = currentScope.lookup(segment.identifier());
                            if (!found.isEmpty() && found.get(0) instanceof NamespaceSymbol ns) {
                                targetNs = ns;
                                currentScope = symbolScopes.get(ns);
                            } else {
                                targetNs = null;
                                break;
                            }
                        }
                        if (targetNs != null) {
                            Scope s = symbolScopes.get(targetNs);
                            importedScopes.add(s);
                        } else {
                            reportUnknownImport(u, using);
                        }
                    }
                    if (!importedScopes.isEmpty()) {
                        syntaxScopes.put(u, Scope.withImports(root.symbol, globalScope, java.util.List.of(), importedScopes));
                    } else {
                        syntaxScopes.put(u, globalScope);
                    }
                }
            }
            
            return new SemanticModel(root.symbol, globalScope, allSymbols, declaredSymbols,
                    syntaxScopes, symbolScopes, resolvedTypes, patternTypes, positionalLayouts,
                    topLevelFunction, javaInterop, importsByFile, globalImports,
                    aliasesByFile, globalAliases, scopedUsings);
        }

        /// The JDK functional interface a `Func`/`Action` spelling denotes, or `null`.
        ///
        /// The generic interfaces are used, never the primitive-specialised ones: C# delegate
        /// semantics are generic, `IntUnaryOperator` is an optimisation rather than a meaning,
        /// and selecting by primitiveness would make one source spelling denote two types. An
        /// arity the JDK has no counterpart for is refused rather than synthesized, because a
        /// fabricated interface would interoperate with nothing.
        private TypeSymbol delegateInterface(String simpleName, TypeSyntax.Name name,
                String ownerName, Map<String, TypeParameterSymbol> typeParameters,
                TypeSyntax.Segment finalSegment) {
            boolean action = simpleName.equals("Action");
            if (!action && !simpleName.equals("Func")) {
                return null;
            }
            if (name.segments().size() > 1) {
                String written = name.segments().stream().limit(name.segments().size() - 1L)
                        .map(TypeSyntax.Segment::identifier)
                        .reduce((left, right) -> left + "." + right).orElse("");
                if (!written.equals("System")) {
                    return null;
                }
            }
            int arity = finalSegment.typeArguments().size();
            String target = action
                    ? switch (arity) {
                        case 0 -> "java.lang.Runnable";
                        case 1 -> "java.util.function.Consumer";
                        case 2 -> "java.util.function.BiConsumer";
                        default -> null;
                    }
                    : switch (arity) {
                        case 1 -> "java.util.function.Supplier";
                        case 2 -> "java.util.function.Function";
                        case 3 -> "java.util.function.BiFunction";
                        default -> null;
                    };
            if (target == null) {
                diagnostics.report(DiagnosticCode.NOT_YET_IMPLEMENTED, file, name.span(),
                        simpleName + " with " + arity + " type arguments has no JDK counterpart");
                return TypeSymbol.Error.INSTANCE;
            }
            NamedTypeSymbol definition = javaInterop.resolveType(target);
            if (definition == null) {
                return null;
            }
            if (arity == 0) {
                return definition;
            }
            List<TypeSymbol> arguments = finalSegment.typeArguments().stream()
                    .map(argument -> resolveType(argument, ownerName, typeParameters, false, false))
                    .toList();
            return new TypeSymbol.Constructed(definition, arguments);
        }

        /// The scope a `using static T;` contributes: the static members of `T`.
        ///
        /// C# brings those members into scope for *unqualified* use, so the directive behaves
        /// like a namespace import whose contents happen to be members rather than types - which
        /// is why it is expressed as one more imported scope rather than a mechanism of its own.
        /// A V#-declared type answers with its declaration scope; a Java type has no scope,
        /// because scopes are built where a type is *named*, so one is built here from the static
        /// members the class file publishes. Returns `null` when the name denotes no type, and
        /// the caller reports that as the unresolved import it is.
        private Scope staticMemberScope(TypeSyntax.Name target, Scope globalScope) {
            List<TypeSyntax.Segment> segments = target.segments();
            if (segments.isEmpty()) {
                return null;
            }
            Scope current = globalScope;
            for (int index = 0; index < segments.size() - 1 && current != null; index++) {
                List<Symbol> found = current.lookup(segments.get(index).identifier());
                current = !found.isEmpty() && found.get(0) instanceof NamespaceSymbol namespace
                        ? symbolScopes.get(namespace)
                        : null;
            }
            String simpleName = segments.get(segments.size() - 1).identifier();
            if (current != null) {
                List<Symbol> found = current.lookup(simpleName);
                // A `static class` is a [ContainerSymbol], not a [NamedTypeSymbol] - V# has no
                // object model, so a holder of statics is not a type. `using static` accepts
                // either, because what it imports is a member scope rather than a type.
                if (!found.isEmpty() && !(found.get(0) instanceof NamespaceSymbol)) {
                    Symbol declared = found.get(0);
                    Scope declaredScope = symbolScopes.get(declared);
                    if (declaredScope != null) {
                        return declaredScope;
                    }
                    // `symbolScopes` is keyed by symbol identity, and the symbol a name lookup
                    // answers with is not always the instance whose scope was registered, so the
                    // owner is matched by qualified name instead. That also covers the case where
                    // the declaring namespace is bound after the importing one, since any scope
                    // already registered for that owner is found whatever the order was - which
                    // one real project needed, importing `FruitApi.Util.JsonText` from `FruitApi.Web`.
                    String qualified = declared.qualifiedName();
                    for (Map.Entry<Symbol, Scope> entry : symbolScopes.entrySet()) {
                        if (entry.getKey().qualifiedName().equals(qualified)
                                && !entry.getValue().symbols().isEmpty()) {
                            return entry.getValue();
                        }
                    }
                }
            }
            String qualifiedName = segments.stream()
                    .map(TypeSyntax.Segment::identifier)
                    .reduce((left, right) -> left + "." + right)
                    .orElse("");
            NamedTypeSymbol javaType = javaInterop.resolveType(qualifiedName);
            if (javaType == null) {
                return null;
            }
            // Registered under the *V# spelling*: the JDK declares `sqrt`, source writes `Sqrt`,
            // and an unqualified name has no receiver for the member-access translation to run
            // on. The qualified name is left alone, so emission still targets `sqrt` -
            // the backend takes a method's emitted name from that, never from its V# name.
            List<Symbol> statics = new ArrayList<>();
            for (Symbol member : javaInterop.getMembers(javaType)) {
                if (!isStaticMember(member)) {
                    continue;
                }
                if (member instanceof FunctionSymbol function) {
                    String written = JavaInterop.vsharpMemberName(function.name());
                    statics.add(written == null || written.equals(function.name())
                            ? function
                            : new FunctionSymbol(written, function.qualifiedName(),
                                    function.location(), function.returnType(),
                                    function.typeParameters(), function.parameters(),
                                    function.modifiers(), function.localFunction(),
                                    function.synthesized(), function.interfaceOwner()));
                } else {
                    statics.add(member);
                }
            }
            return statics.isEmpty() ? null : Scope.nested(javaType, globalScope, statics);
        }

        private static boolean isStaticMember(Symbol symbol) {
            if (symbol instanceof FunctionSymbol function) {
                return function.modifiers().contains(SyntaxKind.STATIC);
            }
            return symbol instanceof FieldSymbol field
                    && field.modifiers().contains(SyntaxKind.STATIC);
        }

        /// The top-level function synthesized for a namespace's loose statements, kept from the
        /// scope-creation pass so the body pass can bind against it.
        private final IdentityHashMap<NamespaceNode, FunctionSymbol> namespaceEntries =
                new IdentityHashMap<>();

        /// Binds a namespace in two passes.
        ///
        /// Every namespace scope is created first, then every body is bound. One pass cannot
        /// work once a `using` inside a namespace body may name a sibling: the imported scope
        /// has to exist when the importing body binds, and sibling order is arbitrary. The
        /// split is also what makes per-part header binding possible, which is the whole point
        /// - a namespace's declarations are merged across every file that declares it, while a
        /// `using` written in one of those files belongs to that file alone.
        private Scope bindNamespace(NamespaceNode node, Scope parent) {
            Scope scope = createNamespaceScopes(node, parent);
            bindNamespaceBodies(node);
            return scope;
        }

        private Scope createNamespaceScopes(NamespaceNode node, Scope parent) {
            List<Symbol> declarations = new ArrayList<>();
            declarations.addAll(node.children.values().stream().map(child -> child.symbol).toList());
            declarations.addAll(headersByOwner.getOrDefault(node.fullName, List.of()));

            FunctionSymbol entry = null;
            if (!node.statements.isEmpty()) {
                entry = createTopLevelFunction(node);
                declarations.add(entry);
                namespaceEntries.put(node, entry);
                if (node == root) {
                    topLevelFunction = entry;
                }
            }

            Scope scope = parent == null
                    ? Scope.root(node.symbol, declarations)
                    : Scope.nested(node.symbol, parent, declarations);
            symbolScopes.put(node.symbol, scope);
            for (DeclarationSyntax.Namespace part : node.parts) {
                syntaxScopes.put(part, scope);
            }
            if (node == root) {
                syntaxScopes.put(unit, scope);
            }


            for (NamespaceNode child : node.children.values()) {
                createNamespaceScopes(child, scope);
            }
            return scope;
        }

        /// Binds each part against its own imports, then the namespace's loose statements, then
        /// every child.
        private void bindNamespaceBodies(NamespaceNode node) {
            Scope scope = symbolScopes.get(node.symbol);
            if (node.parts.isEmpty()) {
                // The root has no syntax part of its own; its declarations come from the units.
                bindHeaderOwners(node.declarations, scope, Map.of());
            } else {
                for (DeclarationSyntax.Namespace part : node.parts) {
                    Scope partScope = scopeWithPartImports(part, node, scope);
                    if (partScope != scope) {
                        syntaxScopes.put(part, partScope);
                    }
                    bindHeaderOwners(part.declarations().stream()
                            .filter(member -> !(member instanceof DeclarationSyntax.Namespace))
                            .toList(), partScope, Map.of());
                }
            }
            FunctionSymbol entry = namespaceEntries.get(node);
            if (entry != null) {
                bindStatementList(entry, node.statements, scope, null);
            }
            for (NamespaceNode child : node.children.values()) {
                bindNamespaceBodies(child);
            }
        }

        /// The scope a namespace *part* binds against: its namespace's scope, with the scopes of
        /// the namespaces its own `using` directives name layered in front. A part with no
        /// directives binds against the shared scope unchanged, so nothing is allocated or
        /// observable for the ordinary case.
        private Scope scopeWithPartImports(DeclarationSyntax.Namespace part, NamespaceNode node,
                Scope scope) {
            if (part.usings().isEmpty()) {
                return scope;
            }
            Scope globalScope = symbolScopes.get(root.symbol);
            List<Scope> imported = new ArrayList<>();
            for (AuxiliarySyntax.UsingDirective using : part.usings()) {
                if (using.alias() != null) {
                    continue;
                }
                if (using.isStatic()) {
                    Scope members = staticMemberScope(using.target(), globalScope);
                    if (members != null) {
                        imported.add(members);
                    }
                    continue;
                }
                Scope current = globalScope;
                NamespaceSymbol target = null;
                for (TypeSyntax.Segment segment : using.target().segments()) {
                    List<Symbol> found = current == null
                            ? List.of() : current.lookup(segment.identifier());
                    if (!found.isEmpty() && found.get(0) instanceof NamespaceSymbol namespace) {
                        target = namespace;
                        current = symbolScopes.get(namespace);
                    } else {
                        target = null;
                        break;
                    }
                }
                Scope targetScope = target == null ? null : symbolScopes.get(target);
                if (targetScope != null && !imported.contains(targetScope)) {
                    imported.add(targetScope);
                }
            }
            return imported.isEmpty() ? scope
                    : Scope.withImports(node.symbol, scope, List.of(), imported);
        }

        private FunctionSymbol createTopLevelFunction(NamespaceNode owner) {
            String name = "<top-level>";
            FunctionSymbol function = new FunctionSymbol(name, qualify(owner.fullName, name),
                    owner.location, BuiltinType.VOID, List.of(), List.of(),
                    List.of(SyntaxKind.STATIC), false, true);
            allSymbols.add(function);
            callableContexts.put(function, new CallableContext(owner.fullName, Map.of()));
            return function;
        }

        private void bindHeaderOwners(List<DeclarationSyntax> declarations, Scope parent,
                Map<String, TypeParameterSymbol> inheritedTypeParameters) {
            for (DeclarationSyntax declaration : declarations) {
                Symbol owner = headers.get(declaration);
                if (owner != null && boundOwners.add(owner)) {
                    SourceFile previous = this.file;
                    this.file = syntaxFiles.get(declaration);
                    bindOwner(declaration, owner, parent, inheritedTypeParameters);
                    this.file = previous;
                }
            }
        }

        private void bindOwner(DeclarationSyntax declaration, Symbol owner, Scope parent,
                Map<String, TypeParameterSymbol> inheritedTypeParameters) {
            List<AuxiliarySyntax.TypeParameter> parameterSyntax = typeParametersOf(declaration);
            TypeParameterSet ownTypeParameters = createTypeParameters(owner.qualifiedName(),
                    parameterSyntax, constraintsOf(declaration), inheritedTypeParameters);
            allSymbols.addAll(ownTypeParameters.declared());
            MemberAccumulator members = new MemberAccumulator();
            ownTypeParameters.accepted().forEach(members::addUnchecked);
            headersByOwner.getOrDefault(owner.qualifiedName(), List.of())
                    .forEach(members::addUnchecked);

            Map<String, TypeParameterSymbol> typeContext = ownTypeParameters.context();
            List<CallableDeclaration> callables = new ArrayList<>();

            switch (declaration) {
                case DeclarationSyntax.StaticContainer container -> bindMembers(owner,
                        container.members(), typeContext, members, callables);
                case DeclarationSyntax.Struct structure -> {
                    bindPrimaryParameters(owner, structure, typeContext, members);
                    bindMembers(owner, structure.members(), typeContext, members, callables);
                }
                case DeclarationSyntax.Enum enumeration -> bindEnumMembers(owner, enumeration,
                        typeContext, members);
                case DeclarationSyntax.Namespace ignored -> throw unexpectedOwner(declaration);
                case DeclarationSyntax.Field ignored -> throw unexpectedOwner(declaration);
                case DeclarationSyntax.Method ignored -> throw unexpectedOwner(declaration);
                case DeclarationSyntax.Operator ignored -> throw unexpectedOwner(declaration);
                case DeclarationSyntax.ConversionOperator ignored ->
                        throw unexpectedOwner(declaration);
                case DeclarationSyntax.Unsupported ignored -> throw unexpectedOwner(declaration);
            }

            Scope scope = Scope.nested(owner, parent, members.symbols());
            symbolScopes.put(owner, scope);
            syntaxScopes.put(declaration, scope);

            List<DeclarationSyntax> nested = membersOf(declaration);
            bindHeaderOwners(nested, scope, typeContext);
            for (CallableDeclaration callable : callables) {
                bindCallable(callable.symbol(), callable.declaration(), scope);
            }
        }

        private static IllegalStateException unexpectedOwner(DeclarationSyntax declaration) {
            return new IllegalStateException(
                    "declaration cannot own a scope: " + declaration.getClass().getSimpleName());
        }

        /// Materialises the positional members of a `record struct`.
        ///
        /// C# turns each primary constructor parameter of a positional record struct into a
        /// public member of the same name plus a constructor parameter (§15.3). V# carries the
        /// member as a public instance field, so `p.X` is an ordinary field access and the
        /// synthesized constructor is the only writer. A primary constructor on a plain
        /// `struct` means something different in C# 12 - the parameters are *captured* by the
        /// members that mention them, which needs the constructor machinery of the object
        /// model - so it is refused instead of being silently ignored.
        private void bindPrimaryParameters(Symbol owner, DeclarationSyntax.Struct structure,
                Map<String, TypeParameterSymbol> typeContext, MemberAccumulator members) {
            List<AuxiliarySyntax.Parameter> parameters = structure.primaryParameters();
            if (parameters.isEmpty()) {
                return;
            }
            boolean positional = structure.record() && owner instanceof NamedTypeSymbol named
                    && named.declaredKind() == NamedTypeSymbol.DeclaredKind.RECORD_STRUCT;
            if (!positional) {
                diagnostics.report(DiagnosticCode.OBJECT_MODEL_UNSUPPORTED, file,
                        parameters.getFirst().span(), "a non-record struct primary constructor");
            }
            List<FieldSymbol> components = new ArrayList<>();
            List<ParameterSymbol> constructorParameters = new ArrayList<>();
            String constructorName = qualify(owner.qualifiedName(), CONSTRUCTOR_NAME);
            boolean sawOptional = false;
            for (int index = 0; index < parameters.size(); index++) {
                AuxiliarySyntax.Parameter parameter = parameters.get(index);
                sawOptional = validateParameterDefault(parameter, sawOptional);
                TypeSymbol type = parameter.type() == null
                        ? TypeSymbol.Error.INSTANCE
                        : resolveType(parameter.type(), owner.qualifiedName(), typeContext,
                                false, false);
                if (!positional) {
                    continue;
                }
                FieldSymbol component = FieldSymbol.of(parameter.name(),
                        qualify(owner.qualifiedName(), parameter.name()),
                        location(parameter.span()), type, null, List.of());
                acceptNonFunction(owner, component, parameter.span(), members);
                allSymbols.add(component);
                declaredSymbols.put(parameter, component);
                components.add(component);
                constructorParameters.add(new ParameterSymbol(parameter.name(),
                        qualify(constructorName, parameter.name()), location(parameter.span()),
                        type, index, List.of(), parameter.defaultValue()));
            }
            if (!positional) {
                return;
            }
            NamedTypeSymbol named = (NamedTypeSymbol) owner;
            FunctionSymbol constructor = new FunctionSymbol(CONSTRUCTOR_NAME, constructorName,
                    location(structure.span()), named, List.of(), constructorParameters,
                    List.of(SyntaxKind.PUBLIC), false, true);
            positionalLayouts.put(named, new PositionalLayout(named, components, constructor));
            synthesizeRecordValueMembers(named, members);
        }

        /// Declares the value members C# synthesizes for every `record struct`.
        ///
        /// A record struct *is* a value: `==`, `Equals`, `GetHashCode` and `ToString` are
        /// generated from its components, and a program that writes `a == b` is entitled to the
        /// componentwise answer. Without these the emitted class carried public fields and
        /// nothing else, so `==` fell to reference comparison and quietly answered `false` for
        /// two equal values - a wrong answer rather than a diagnostic, which is the worst shape
        /// a defect can take.
        ///
        /// Each symbol is named as C# spells it and *qualified* as the JVM spells it, so member
        /// lookup answers `Equals` while emission targets `equals`. That is what keeps the
        /// generated class a well-behaved JVM citizen: `HashMap`, `List.contains` and
        /// `System.out.println` all reach the same three methods from ordinary Java.
        private void synthesizeRecordValueMembers(NamedTypeSymbol named,
                MemberAccumulator members) {
            SourceLocation location = named.location();
            members.addUnchecked(new FunctionSymbol("Equals",
                    qualify(named.qualifiedName(), "equals"), location, BuiltinType.BOOL,
                    List.of(),
                    List.of(new ParameterSymbol("obj", qualify(named.qualifiedName(), "equals.obj"),
                            location, BuiltinType.OBJECT, 0, List.of(), null)),
                    List.of(SyntaxKind.PUBLIC), false, false));
            members.addUnchecked(new FunctionSymbol("GetHashCode",
                    qualify(named.qualifiedName(), "hashCode"), location, BuiltinType.INT,
                    List.of(), List.of(), List.of(SyntaxKind.PUBLIC), false, false));
            members.addUnchecked(new FunctionSymbol("ToString",
                    qualify(named.qualifiedName(), "toString"), location, BuiltinType.STRING,
                    List.of(), List.of(), List.of(SyntaxKind.PUBLIC), false, false));
        }

        private void bindEnumMembers(Symbol owner, DeclarationSyntax.Enum enumeration,
                Map<String, TypeParameterSymbol> typeContext, MemberAccumulator members) {
            if (enumeration.underlyingType() != null) {
                TypeSymbol underlying = resolveType(enumeration.underlyingType(),
                        owner.qualifiedName(), typeContext, false, false);
                if (underlying != BuiltinType.INT && underlying != TypeSymbol.Error.INSTANCE) {
                    // V# carries every enum as an `int`. Saying so is better than
                    // accepting the declaration and storing values in the wrong width.
                    diagnostics.report(DiagnosticCode.NOT_YET_IMPLEMENTED, file,
                            enumeration.underlyingType().span(),
                            "An enum underlying type other than 'int'");
                }
            }
            TypeSymbol enumType = (NamedTypeSymbol) owner;
            // C# §19.4: members count up from zero, and an explicit initialiser both takes
            // effect and resets the count for the members after it.
            int nextValue = 0;
            for (AuxiliarySyntax.EnumMember member : enumeration.members()) {
                if (member.value() != null) {
                    Integer explicitValue = enumMemberValue(member.value());
                    if (explicitValue == null) {
                        diagnostics.report(DiagnosticCode.NOT_YET_IMPLEMENTED, file,
                                member.value().span(),
                                "An enum member initialised by anything but an integer literal");
                    } else {
                        nextValue = explicitValue;
                    }
                }
                EnumMemberSymbol symbol = new EnumMemberSymbol(member.name(),
                        qualify(owner.qualifiedName(), member.name()), location(member.span()),
                        enumType, nextValue);
                nextValue++;
                allSymbols.add(symbol);
                declaredSymbols.put(member, symbol);
                acceptNonFunction(owner, symbol, member.span(), members);
            }
        }

        /// The value of an explicit enum member initialiser, or `null` when it is not one this
        /// build evaluates. Only integer literals, optionally negated, are folded here:
        /// declaration binding runs before expression binding, so a computed initialiser such
        /// as `C = A | B` has no bound form to evaluate yet, and guessing one would be worse
        /// than saying so.
        private static Integer enumMemberValue(ExpressionSyntax expression) {
            if (expression instanceof ExpressionSyntax.Unary unary
                    && unary.operator() == SyntaxKind.MINUS) {
                Integer magnitude = enumMemberValue(unary.operand());
                return magnitude == null ? null : -magnitude;
            }
            if (expression instanceof ExpressionSyntax.Literal literal
                    && literal.token().value() instanceof Number number
                    && !(number instanceof Double) && !(number instanceof Float)) {
                long value = number.longValue();
                return value < Integer.MIN_VALUE || value > Integer.MAX_VALUE
                        ? null : (int) value;
            }
            return null;
        }

        private void bindMembers(Symbol owner, List<DeclarationSyntax> declarations,
                Map<String, TypeParameterSymbol> typeContext, MemberAccumulator members,
                List<CallableDeclaration> callables) {
            for (DeclarationSyntax declaration : declarations) {
                switch (declaration) {
                    case DeclarationSyntax.Field field -> bindField(owner, field, typeContext,
                            members);
                    case DeclarationSyntax.Method method -> {
                        resolveAttributes(method.attributes(), owner.qualifiedName(), typeContext);
                        FunctionSymbol symbol = createFunction(owner, method.name(), method.span(),
                                method.returnType(), method.typeParameters(), method.parameters(),
                                method.modifiers(), false, method, typeContext);
                        requireTaskReturnType(symbol, method.returnType().span());
                        acceptFunction(owner, symbol, method.span(), members);
                        callables.add(new CallableDeclaration(symbol, method));
                    }
                    case DeclarationSyntax.Operator operator -> {
                        resolveAttributes(operator.attributes(), owner.qualifiedName(), typeContext);
                        String name = "operator " + operator.operator().display();
                        FunctionSymbol symbol = createFunction(owner, name, operator.span(),
                                operator.returnType(), List.of(), operator.parameters(),
                                operator.modifiers(), false, operator, typeContext);
                        acceptFunction(owner, symbol, operator.span(), members);
                        callables.add(new CallableDeclaration(symbol, operator));
                    }
                    case DeclarationSyntax.ConversionOperator conversion -> {
                        resolveAttributes(conversion.attributes(), owner.qualifiedName(),
                                typeContext);
                        String prefix = conversion.implicit() ? "implicit" : "explicit";
                        String name = prefix + " operator " + typeSpelling(conversion.targetType());
                        FunctionSymbol symbol = createFunction(owner, name, conversion.span(),
                                conversion.targetType(), List.of(), List.of(conversion.parameter()),
                                conversion.modifiers(), false, conversion, typeContext);
                        acceptFunction(owner, symbol, conversion.span(), members);
                        callables.add(new CallableDeclaration(symbol, conversion));
                    }
                    case DeclarationSyntax.StaticContainer ignored -> {
                        // Its predeclared header was inserted before ordinary members.
                    }
                    case DeclarationSyntax.Struct ignored -> {
                        // Its predeclared header was inserted before ordinary members.
                    }
                    case DeclarationSyntax.Enum ignored -> {
                        // Its predeclared header was inserted before ordinary members.
                    }
                    case DeclarationSyntax.Namespace namespace -> diagnostics.report(
                            DiagnosticCode.DUPLICATE_MEMBER, file, namespace.span(),
                            owner.qualifiedName(), namespace.name().text());
                    case DeclarationSyntax.Unsupported ignored -> {
                        // Already diagnosed by syntax parsing.
                    }
                }
            }
        }

    private void resolveAttributes(List<AuxiliarySyntax.AttributeList> attributes,
            String ownerName, Map<String, TypeParameterSymbol> typeContext) {
        for (AuxiliarySyntax.AttributeList list : attributes) {
            if (list.target() != null) {
                diagnostics.report(DiagnosticCode.NOT_YET_IMPLEMENTED, file, list.span(),
                        "attribute targets");
            }
            for (AuxiliarySyntax.Attribute attribute : list.attributes()) {
                if (!attribute.arguments().isEmpty()) {
                    diagnostics.report(DiagnosticCode.NOT_YET_IMPLEMENTED, file,
                            attribute.span(), "attribute arguments");
                }
                TypeSymbol resolved = resolveType(attribute.name(), ownerName, typeContext,
                        false, false);
                if (!(resolved instanceof NamedTypeSymbol named)
                        || !named.javaSupertypes().contains("java.lang.annotation.Annotation")) {
                    diagnostics.report(DiagnosticCode.NOT_YET_IMPLEMENTED, file,
                            attribute.span(), "non-runtime annotations");
                }
            }
        }
    }

        private void bindField(Symbol owner, DeclarationSyntax.Field field,
                Map<String, TypeParameterSymbol> typeContext, MemberAccumulator members) {
            TypeSymbol type = resolveType(field.type(), owner.qualifiedName(), typeContext,
                    false, false);
            boolean constant = field.modifiers().contains(SyntaxKind.CONST);
            boolean instanceField = !field.modifiers().contains(SyntaxKind.STATIC) && !constant;
            for (AuxiliarySyntax.VariableDeclarator variable : field.variables()) {
                if (variable.name() == null) {
                    continue;
                }
                // Every V# named type is a struct, and C# only accepts an instance field
                // initializer on a struct that also declares an explicit parameterless
                // constructor (§15.4.5) - which is object-model surface V# omits. Saying so is
                // the alternative to accepting the initializer and never running it.
                if (instanceField && variable.initializer() != null) {
                    diagnostics.report(DiagnosticCode.OBJECT_MODEL_UNSUPPORTED, file,
                            variable.initializer().span(),
                            "an instance field initializer, which needs a constructor");
                }
                // A `const` initializer is a *constant expression* (C# §15.4), and a constant
                // expression is folded from a bound tree - which does not exist yet, because
                // `const int Doubled = Base * 2;` cannot resolve `Base` until every
                // declaration in the compilation has been created. So the value is left
                // unresolved here and folded by [ConstantFieldBinder] once it can be bound
                // for real. Declaration binding used to fold it itself, with a
                // literal-only folder over syntax that answered "cannot fold" for
                // `int.MaxValue`, for a read of another constant, and for `1 / 0`.
                //
                // A constant whose value cannot be folded has no run-time entity to read.
                // Accepting one silently emitted an ordinary field that nothing ever
                // assigned, so every read answered zero - or, worse, `getfield` against
                // whatever the expression stack happened to hold, which the verifier
                // rejected at class load. The constant pass reports that instead.
                ConstantSlot slot = constant && variable.initializer() != null
                        ? ConstantSlot.unresolved()
                        : ConstantSlot.resolved(null);
                FieldSymbol symbol = new FieldSymbol(variable.name(),
                        qualify(owner.qualifiedName(), variable.name()), location(variable.span()),
                        type, slot, field.modifiers());
                allSymbols.add(symbol);
                declaredSymbols.put(variable, symbol);
                acceptNonFunction(owner, symbol, variable.span(), members);
            }
        }

        private FunctionSymbol createFunction(Symbol owner, String name, SourceSpan span,
                TypeSyntax returnSyntax, List<AuxiliarySyntax.TypeParameter> typeParameters,
                List<AuxiliarySyntax.Parameter> parameters, List<SyntaxKind> modifiers,
                boolean local, DeclarationSyntax declaration,
                Map<String, TypeParameterSymbol> inheritedTypeParameters) {
            // A local function is emitted as a static method of the class that hosts its
            // enclosing callable, so it is qualified into that class rather than under the
            // callable: `Helpers.Twice.Double` would name a class `Helpers.Twice` that nothing
            // emits, while `Helpers.Twice$Double` names the method `Twice$Double` of `Helpers`
            // and stays unique per enclosing callable, to any nesting depth.
            String qualifiedName = local
                    ? owner.qualifiedName() + "$" + name
                    : qualify(owner.qualifiedName(), name);
            TypeParameterSet own = createTypeParameters(qualifiedName, typeParameters,
                    constraintsOf(declaration), inheritedTypeParameters);
            TypeSymbol returnType = resolveType(returnSyntax, owner.qualifiedName(), own.context(),
                    true, false);
            List<ParameterSymbol> parameterSymbols = new ArrayList<>();
            Map<String, ParameterSymbol> names = new LinkedHashMap<>();
            boolean sawOptional = false;
            for (int index = 0; index < parameters.size(); index++) {
                AuxiliarySyntax.Parameter parameter = parameters.get(index);
                sawOptional = validateParameterDefault(parameter, sawOptional);
                TypeSymbol type = parameter.type() == null
                        ? TypeSymbol.Inferred.INSTANCE
                        : resolveType(parameter.type(), owner.qualifiedName(), own.context(),
                                false, false);
                ParameterSymbol symbol = new ParameterSymbol(parameter.name(),
                        qualify(qualifiedName, parameter.name()), location(parameter.span()), type,
                        index, parameter.modifiers(), parameter.defaultValue());
                parameterSymbols.add(symbol);
                declaredSymbols.put(parameter, symbol);
                if (names.putIfAbsent(symbol.name(), symbol) != null) {
                    diagnostics.report(DiagnosticCode.DUPLICATE_PARAMETER, file,
                            parameter.span(), parameter.name());
                }
            }
            validateExtensionShape(owner, name, parameters, modifiers, local,
                    inheritedTypeParameters);
            FunctionSymbol function = new FunctionSymbol(name, qualifiedName, location(span),
                    returnType, own.declared(), parameterSymbols, modifiers, local, false);
            allSymbols.add(function);
            allSymbols.addAll(own.declared());
            allSymbols.addAll(parameterSymbols);
            declaredSymbols.put(declaration, function);
            callableContexts.put(function,
                    new CallableContext(owner.qualifiedName(), own.context()));
            callableDeclarations.put(function, declaration);
            return function;
        }

        /// Enforces the declaration-shape rules of C# §12.8.10.3 for `this` parameters.
        ///
        /// The `this` modifier is the whole of what makes a method an extension, so every rule
        /// about where one may be written is checked here, once, before the [FunctionSymbol]
        /// exists. A declaration that fails any of these still becomes an ordinary static
        /// method - [FunctionSymbol#isExtension] keeps answering from the written shape - so a
        /// rejected extension is still callable in static form and one error is reported per
        /// broken rule rather than a cascade at every call site.
        private void validateExtensionShape(Symbol owner, String name,
                List<AuxiliarySyntax.Parameter> parameters, List<SyntaxKind> modifiers,
                boolean local, Map<String, TypeParameterSymbol> inheritedTypeParameters) {
            for (int index = 1; index < parameters.size(); index++) {
                AuxiliarySyntax.Parameter parameter = parameters.get(index);
                if (parameter.modifiers().contains(SyntaxKind.THIS)) {
                    diagnostics.report(DiagnosticCode.EXTENSION_THIS_NOT_FIRST_PARAMETER, file,
                            parameter.span(), name);
                }
            }
            if (parameters.isEmpty()) {
                return;
            }
            AuxiliarySyntax.Parameter receiver = parameters.getFirst();
            if (!receiver.modifiers().contains(SyntaxKind.THIS)) {
                return;
            }
            if (receiver.modifiers().contains(SyntaxKind.REF)) {
                diagnostics.report(DiagnosticCode.EXTENSION_REF_WITH_THIS, file, receiver.span());
            }
            if (receiver.modifiers().contains(SyntaxKind.OUT)) {
                diagnostics.report(DiagnosticCode.EXTENSION_OUT_WITH_THIS, file, receiver.span());
            }
            if (receiver.modifiers().contains(SyntaxKind.PARAMS)) {
                diagnostics.report(DiagnosticCode.EXTENSION_PARAMS_WITH_THIS, file,
                        receiver.span(), name);
            }
            if (local || !modifiers.contains(SyntaxKind.STATIC)) {
                diagnostics.report(DiagnosticCode.EXTENSION_METHOD_MUST_BE_STATIC, file,
                        receiver.span(), name);
            }
            // A V# `static class` is a [ContainerSymbol]; anything else owning the method - a
            // namespace holder, a `struct`, a callable - is not the static class C# requires.
            // A generic container is refused for the same reason C# does (CS1106): the
            // receiver's type would depend on arguments no call site can supply.
            if (local || !(owner instanceof ContainerSymbol) || !inheritedTypeParameters.isEmpty()) {
                diagnostics.report(DiagnosticCode.EXTENSION_METHOD_MUST_BE_IN_STATIC_CLASS, file,
                        receiver.span(), name);
            } else if (nestedContainer(owner.qualifiedName())) {
                diagnostics.report(DiagnosticCode.EXTENSION_METHOD_MUST_BE_TOP_LEVEL, file,
                        receiver.span(), name);
            }
        }

        /// Whether a container's own owner is another container rather than a namespace.
        private boolean nestedContainer(String qualifiedName) {
            int lastDot = qualifiedName.lastIndexOf('.');
            return lastDot > 0 && containerNames.contains(qualifiedName.substring(0, lastDot));
        }

        private void acceptFunction(Symbol owner, FunctionSymbol function, SourceSpan span,
                MemberAccumulator members) {
            List<Symbol> existing = members.group(function.name());
            boolean duplicate = existing.stream().anyMatch(symbol ->
                    !(symbol instanceof FunctionSymbol candidate)
                            || candidate.signature().equals(function.signature()));
            if (duplicate) {
                DiagnosticCode code = existing.stream().allMatch(FunctionSymbol.class::isInstance)
                        ? DiagnosticCode.DUPLICATE_FUNCTION
                        : DiagnosticCode.DUPLICATE_MEMBER;
                diagnostics.report(code, file, span, owner.qualifiedName(), function.name());
                return;
            }
            // Two overloads C# tells apart can still be one method on the JVM, because a V#
            // enum is a named `int` and every enum erases to the same descriptor.
            // `Name(Species)` beside `Name(Stage)` is legal C# and one `Name(I)` here, and
            // emitting both produced a class file the JVM refused to load with
            // `ClassFormatError: duplicate method`. A source-level diagnostic is the only
            // sound answer: the alternative is a program that compiles and cannot start.
            //
            // Only *emitted* methods can clash. An `extern` member declares a mapping to a
            // runtime target rather than a method this compiler writes, which is why corelib
            // may declare `Math.Round(double, MidpointRounding)` beside `Round(double, int)`:
            // neither reaches a class file.
            if (!function.modifiers().contains(SyntaxKind.EXTERN)) {
                String erased = erasedSignature(function);
                for (Symbol symbol : existing) {
                    if (symbol instanceof FunctionSymbol other
                            && !other.modifiers().contains(SyntaxKind.EXTERN)
                            && erasedSignature(other).equals(erased)) {
                        diagnostics.report(DiagnosticCode.ERASED_SIGNATURE_CLASH, file, span,
                                owner.qualifiedName(), function.name(), erased);
                        return;
                    }
                }
            }
            members.addUnchecked(function);
        }

        /// A function's parameter list as the JVM will see it, for the one erasure V# performs
        /// that C# does not: an enum is a named `int`, so every enum parameter erases to
        /// the same thing. Nothing else is erased here - generics are checked by the
        /// ordinary signature comparison above, which runs first.
        private static String erasedSignature(FunctionSymbol function) {
            StringBuilder erased = new StringBuilder(function.name());
            erased.append('(');
            for (int index = 0; index < function.parameters().size(); index++) {
                if (index > 0) {
                    erased.append(',');
                }
                TypeSymbol type = function.parameters().get(index).type();
                erased.append(type instanceof NamedTypeSymbol named
                        && named.declaredKind() == NamedTypeSymbol.DeclaredKind.ENUM
                        ? "int" : type.displayName());
            }
            return erased.append(')').toString();
        }

        private void acceptNonFunction(Symbol owner, Symbol symbol, SourceSpan span,
                MemberAccumulator members) {
            if (!members.group(symbol.name()).isEmpty()) {
                diagnostics.report(DiagnosticCode.DUPLICATE_MEMBER, file, span,
                        owner.qualifiedName(), symbol.name());
                return;
            }
            members.addUnchecked(symbol);
        }

        private void bindCallable(FunctionSymbol function, DeclarationSyntax declaration,
                Scope parent) {
            SourceFile previous = this.file;
            this.file = syntaxFiles.get(declaration);
            if (this.file == null) {
                // Every declaration reaching this point was registered by the header collector,
                // so a missing owner is compiler-internal corruption, not a source error.
                throw new IllegalStateException(
                        "compiler defect: no source file is registered for callable declaration "
                                + declaration.getClass().getSimpleName());
            }
            try {
            StatementSyntax.Block body = switch (declaration) {
                case DeclarationSyntax.Method method -> method.body();
                case DeclarationSyntax.Operator operator -> operator.body();
                case DeclarationSyntax.ConversionOperator conversion -> conversion.body();
                case DeclarationSyntax.Namespace ignored -> null;
                case DeclarationSyntax.StaticContainer ignored -> null;
                case DeclarationSyntax.Enum ignored -> null;
                case DeclarationSyntax.Struct ignored -> null;
                case DeclarationSyntax.Field ignored -> null;
                case DeclarationSyntax.Unsupported ignored -> null;
            };
            if (body != null) {
                bindStatementList(function, body.statements(), parent, body);
                return;
            }
            ExpressionSyntax expressionBody = expressionBodyOf(declaration);
            Scope scope = createCallableScope(function, List.of(), parent, expressionBody);
            syntaxScopes.put(declaration, scope);
            bindExpressionScopes(function, expressionBody, scope);
            } finally {
                this.file = previous;
            }
        }

        /// The expression body of a callable declaration, or `null` when it has none.
        private static ExpressionSyntax expressionBodyOf(DeclarationSyntax declaration) {
            return switch (declaration) {
                case DeclarationSyntax.Method method -> method.expressionBody();
                case DeclarationSyntax.Operator operator -> operator.expressionBody();
                case DeclarationSyntax.ConversionOperator conversion ->
                        conversion.expressionBody();
                case DeclarationSyntax.Namespace ignored -> null;
                case DeclarationSyntax.StaticContainer ignored -> null;
                case DeclarationSyntax.Enum ignored -> null;
                case DeclarationSyntax.Struct ignored -> null;
                case DeclarationSyntax.Field ignored -> null;
                case DeclarationSyntax.Unsupported ignored -> null;
            };
        }

        private void bindStatementList(FunctionSymbol function, List<StatementSyntax> statements,
                Scope parent, SyntaxNode scopeSyntax) {
            Scope scope = createCallableScope(function, statements, parent, null);
            if (scopeSyntax != null) {
                syntaxScopes.put(scopeSyntax, scope);
            }
            for (StatementSyntax statement : statements) {
                // Top-level statements are bound straight from `bindNamespace`, which never
                // passes through `bindCallable`, so `this.file` is still null here for them.
                // Any nested declaration - a `foreach` variable, a pattern designation - then
                // builds a `SourceLocation` with a null file and crashes the compiler. Track
                // the owning file per statement exactly as `collectDirectLocals` does.
                SourceFile previous = this.file;
                SourceFile owning = syntaxFiles.get(statement);
                if (owning != null) {
                    this.file = owning;
                }
                try {
                    bindStatement(statement, function, scope);
                } finally {
                    this.file = previous;
                }
            }
        }

        private Scope createCallableScope(FunctionSymbol function,
                List<StatementSyntax> statements, Scope parent, ExpressionSyntax expressionBody) {
            MemberAccumulator declarations = new MemberAccumulator();
            function.typeParameters().forEach(parameter -> addFirstByName(declarations, parameter));
            function.parameters().forEach(parameter -> addFirstByName(declarations, parameter));
            collectDirectLocals(function, statements, parent, declarations);
            collectExpressionLocals(function, expressionBody, parent, declarations);
            Scope scope = Scope.nested(function, parent, declarations.symbols());
            symbolScopes.put(function, scope);
            DeclarationSyntax syntax = callableDeclarations.get(function);
            if (syntax != null) {
                syntaxScopes.put(syntax, scope);
            }
            return scope;
        }

        private static void addFirstByName(MemberAccumulator declarations, Symbol symbol) {
            if (declarations.group(symbol.name()).isEmpty()) {
                declarations.addUnchecked(symbol);
            }
        }

        private void collectDirectLocals(FunctionSymbol owner, List<StatementSyntax> statements,
                Scope parent, MemberAccumulator declarations) {
            CallableContext context = callableContexts.get(owner);
            for (StatementSyntax statement : statements) {
                SourceFile previous = this.file;
                if (syntaxFiles.containsKey(statement)) this.file = syntaxFiles.get(statement);
                switch (statement) {
                    case StatementSyntax.LocalDeclaration local -> bindLocalDeclaration(owner,
                            local, context, parent, declarations);
                    case StatementSyntax.LocalFunction local -> {
                        DeclarationSyntax.Method method = local.declaration();
                        if (this.file != null) syntaxFiles.put(method, this.file);
                        FunctionSymbol symbol = createFunction(owner, method.name(), method.span(),
                                method.returnType(), method.typeParameters(), method.parameters(),
                                method.modifiers(), true, method, context.typeParameters());
                        declaredSymbols.put(local, symbol);
                        if (localNameExists(symbol.name(), parent, declarations)) {
                            diagnostics.report(DiagnosticCode.DUPLICATE_LOCAL, file,
                                    local.span(), symbol.name());
                        } else {
                            declarations.addUnchecked(symbol);
                        }
                    }
                    default -> {
                        // Only declarations directly in this statement list belong here.
                    }
                }
                collectStatementExpressionLocals(owner, statement, parent, declarations);
                this.file = previous;
            }
        }

        /// Collects the pattern variables a statement contributes to its enclosing block.
        ///
        /// C# scopes an `out` variable or `is` pattern variable to the nearest enclosing
        /// declaration space. Statements that own a scope of their own — every loop, `if`,
        /// `switch`, `lock`, `using` and `try` — instead collect those names into that scope,
        /// so only the statements without a scope of their own are handled here.
        private void collectStatementExpressionLocals(FunctionSymbol owner,
                StatementSyntax statement, Scope parent, MemberAccumulator declarations) {
            switch (statement) {
                case StatementSyntax.Expression expression -> collectExpressionLocals(owner,
                        expression.expression(), parent, declarations);
                case StatementSyntax.LocalDeclaration local -> local.variables().forEach(
                        variable -> collectExpressionLocals(owner, variable.initializer(), parent,
                                declarations));
                case StatementSyntax.Return returned -> collectExpressionLocals(owner,
                        returned.expression(), parent, declarations);
                case StatementSyntax.Throw thrown -> collectExpressionLocals(owner,
                        thrown.expression(), parent, declarations);
                case StatementSyntax.Yield yielded -> collectExpressionLocals(owner,
                        yielded.expression(), parent, declarations);
                case StatementSyntax.Goto jump -> collectExpressionLocals(owner, jump.value(),
                        parent, declarations);
                case StatementSyntax.Labeled labeled -> {
                    // A labelled declaration is not legal C#; recursing would declare twice.
                    if (!(labeled.statement() instanceof StatementSyntax.LocalDeclaration)
                            && !(labeled.statement() instanceof StatementSyntax.LocalFunction)) {
                        collectStatementExpressionLocals(owner, labeled.statement(), parent,
                                declarations);
                    }
                }
                default -> {
                    // Every other statement scopes its own pattern variables.
                }
            }
        }

        /// Collects the names an expression declares into the scope being built.
        ///
        /// The walk stops at lambda bodies and switch expression arms because those own a
        /// nested scope which cannot exist until this one does; [#bindExpressionScopes]
        /// creates them afterwards.
        private void collectExpressionLocals(FunctionSymbol owner, ExpressionSyntax expression,
                Scope parent, MemberAccumulator declarations) {
            switch (expression) {
                case null -> {
                }
                case ExpressionSyntax.Declaration declaration -> {
                    CallableContext context = callableContexts.get(owner);
                    TypeSymbol type = resolveType(declaration.type(), context.typeOwner(),
                            context.typeParameters(), false, true);
                    declarePatternLocals(owner, declaration.designation(), type, false, false,
                            parent, declarations);
                }
                case ExpressionSyntax.IsPattern isPattern -> {
                    collectExpressionLocals(owner, isPattern.expression(), parent, declarations);
                    declarePatternLocals(owner, isPattern.pattern(), TypeSymbol.Inferred.INSTANCE,
                            false, false, parent, declarations);
                }
                default -> childExpressions(expression).forEach(child ->
                        collectExpressionLocals(owner, child, parent, declarations));
            }
        }

        /// Creates the scopes owned by the lambdas and switch arms inside an expression.
        private void bindExpressionScopes(FunctionSymbol owner, ExpressionSyntax expression,
                Scope scope) {
            switch (expression) {
                case null -> {
                }
                case ExpressionSyntax.Lambda lambda -> bindLambda(owner, lambda, scope);
                case ExpressionSyntax.Switch switched -> {
                    bindExpressionScopes(owner, switched.governing(), scope);
                    for (AuxiliarySyntax.SwitchExpressionArm arm : switched.arms()) {
                        MemberAccumulator declarations = new MemberAccumulator();
                        declarePatternLocals(owner, arm.pattern(), TypeSymbol.Inferred.INSTANCE,
                                false, false, scope, declarations);
                        collectExpressionLocals(owner, arm.guard(), scope, declarations);
                        Scope armScope = declarations.symbols().isEmpty()
                                ? scope
                                : Scope.nested(owner, scope, declarations.symbols());
                        syntaxScopes.put(arm, armScope);
                        bindExpressionScopes(owner, arm.guard(), armScope);
                        bindExpressionScopes(owner, arm.expression(), armScope);
                    }
                }
                case ExpressionSyntax.ObjectCreation creation -> {
                    CallableContext context = callableContexts.get(owner);
                    resolveType(creation.type(), context.typeOwner(), context.typeParameters(),
                            false, false);
                    childExpressions(creation).forEach(child ->
                            bindExpressionScopes(owner, child, scope));
                }
                case ExpressionSyntax.Identifier identifier -> identifier.typeArguments()
                        .forEach(type -> resolveWrittenType(owner, type));
                case ExpressionSyntax.MemberAccess member -> {
                    member.typeArguments().forEach(type -> resolveWrittenType(owner, type));
                    bindExpressionScopes(owner, member.receiver(), scope);
                }
                case ExpressionSyntax.TypeOperator operation -> {
                    // Unlike an operand expression, the type inside `sizeof`/`typeof` is not
                    // visited by childExpressions. Resolve it here through the declaration
                    // binder's complete namespace/import/Java path so expression binding does
                    // not fall back to its deliberately local single-name lookup.
                    CallableContext context = callableContexts.get(owner);
                    resolveType(operation.type(), context.typeOwner(), context.typeParameters(),
                            operation.operator() == SyntaxKind.TYPEOF, false,
                            operation.operator() == SyntaxKind.TYPEOF);
                }
                // Every remaining type written inside an expression, for the same reason
                //: a cast, `as`, `default(T)` and an array creation's element type are
                // type positions the local single-name lookup cannot answer, so a Java type -
                // qualified or imported - was reported as `VS0246` in exactly the places a
                // program needs to spell a reference conversion.
                case ExpressionSyntax.Cast cast -> {
                    resolveWrittenType(owner, cast.type());
                    childExpressions(cast).forEach(child ->
                            bindExpressionScopes(owner, child, scope));
                }
                case ExpressionSyntax.As asExpression -> {
                    resolveWrittenType(owner, asExpression.type());
                    childExpressions(asExpression).forEach(child ->
                            bindExpressionScopes(owner, child, scope));
                }
                case ExpressionSyntax.Default defaultExpression ->
                        resolveWrittenType(owner, defaultExpression.type());
                case ExpressionSyntax.ArrayCreation array -> {
                    resolveWrittenType(owner, array.elementType());
                    childExpressions(array).forEach(child ->
                            bindExpressionScopes(owner, child, scope));
                }
                default -> childExpressions(expression).forEach(child ->
                        bindExpressionScopes(owner, child, scope));
            }
        }

        /// Resolves a type written inside an expression through the declaration binder's full
        /// namespace, import and Java module-path lookup, recording it so expression binding
        /// reads the answer instead of re-deriving it locally.
        ///
        /// An unresolvable spelling is reported here and recorded as the error type, which is
        /// what keeps it a single diagnostic: expression binding reads the recorded answer and
        /// does not report the same name again.
        private void resolveWrittenType(Symbol owner, TypeSyntax type) {
            CallableContext context = callableContexts.get(owner);
            // A bare `default` writes no type at all - its type is the target's - so there is
            // nothing to resolve, and neither has an expression outside any callable.
            if (type == null || context == null) {
                return;
            }
            resolveType(type, context.typeOwner(), context.typeParameters(), false, false);
        }

        /// The immediate sub-expressions of an expression, in evaluation order.
        ///
        /// Lambda bodies and switch arms are excluded: both introduce a scope, so both are
        /// walked explicitly by the callers that know which scope applies.
        private static List<ExpressionSyntax> childExpressions(ExpressionSyntax expression) {
            return switch (expression) {
                case ExpressionSyntax.Parenthesized parenthesized ->
                        present(parenthesized.expression());
                case ExpressionSyntax.Tuple tuple -> present(tuple.elements());
                case ExpressionSyntax.Unary unary -> present(unary.operand());
                case ExpressionSyntax.Await await -> present(await.operand());
                case ExpressionSyntax.Binary binary -> present(binary.left(), binary.right());
                case ExpressionSyntax.Assignment assignment ->
                        present(assignment.target(), assignment.value());
                case ExpressionSyntax.Conditional conditional -> present(conditional.condition(),
                        conditional.whenTrue(), conditional.whenFalse());
                case ExpressionSyntax.MemberAccess member -> present(member.receiver());
                case ExpressionSyntax.ElementAccess element -> concat(element.receiver(),
                        argumentExpressions(element.arguments()));
                case ExpressionSyntax.Invocation invocation -> concat(invocation.target(),
                        argumentExpressions(invocation.arguments()));
                case ExpressionSyntax.Postfix postfix -> present(postfix.operand());
                case ExpressionSyntax.Cast cast -> present(cast.expression());
                case ExpressionSyntax.Switch switched -> present(switched.governing());
                case ExpressionSyntax.Throw thrown -> present(thrown.expression());
                case ExpressionSyntax.NameOf nameOf -> present(nameOf.expression());
                case ExpressionSyntax.Checked checked -> present(checked.expression());
                case ExpressionSyntax.IsPattern isPattern -> present(isPattern.expression());
                case ExpressionSyntax.As as -> present(as.expression());
                case ExpressionSyntax.Range range -> present(range.start(), range.end());
                case ExpressionSyntax.Collection collection -> present(collection.elements()
                        .stream().map(AuxiliarySyntax.CollectionElement::expression).toList());
                case ExpressionSyntax.Interpolated interpolated -> present(interpolated.elements()
                        .stream()
                        .<ExpressionSyntax>mapMulti((element, sink) -> {
                            if (element instanceof AuxiliarySyntax.InterpolationElement.Hole hole) {
                                sink.accept(hole.expression());
                                sink.accept(hole.alignment());
                            }
                        })
                        .toList());
                case ExpressionSyntax.ArrayCreation array -> present(Stream.concat(
                        array.dimensions().stream(), array.initializer().stream()).toList());
                case ExpressionSyntax.ObjectCreation creation ->
                        argumentExpressions(creation.arguments());
                case ExpressionSyntax.ArrayInitializer initializer ->
                        present(initializer.elements());
                case ExpressionSyntax.With with -> concat(with.receiver(), present(
                        with.initializers().stream()
                                .map(AuxiliarySyntax.VariableDeclarator::initializer).toList()));
                case ExpressionSyntax.Declaration ignored -> List.of();
                case ExpressionSyntax.Lambda ignored -> List.of();
                case ExpressionSyntax.Identifier ignored -> List.of();
                case ExpressionSyntax.PredefinedType ignored -> List.of();
                case ExpressionSyntax.Literal ignored -> List.of();
                case ExpressionSyntax.Default ignored -> List.of();
                case ExpressionSyntax.TypeOperator ignored -> List.of();
                case ExpressionSyntax.Missing ignored -> List.of();
                case ExpressionSyntax.Unsupported ignored -> List.of();
            };
        }

        private static List<ExpressionSyntax> argumentExpressions(
                List<AuxiliarySyntax.Argument> arguments) {
            return present(arguments.stream().map(AuxiliarySyntax.Argument::expression).toList());
        }

        private static List<ExpressionSyntax> present(ExpressionSyntax... expressions) {
            return present(Arrays.asList(expressions));
        }

        private static List<ExpressionSyntax> present(List<ExpressionSyntax> expressions) {
            return expressions.stream().filter(Objects::nonNull).toList();
        }

        private static List<ExpressionSyntax> concat(ExpressionSyntax first,
                List<ExpressionSyntax> rest) {
            List<ExpressionSyntax> all = new ArrayList<>();
            if (first != null) {
                all.add(first);
            }
            all.addAll(rest);
            return List.copyOf(all);
        }

        /// Binds a lambda as a synthesized local function owning its parameter scope.
        ///
        /// Parameter and return types stay [TypeSymbol.Inferred] until a target type is known;
        /// only the lexical structure is decided here.
        private void bindLambda(FunctionSymbol owner, ExpressionSyntax.Lambda lambda,
                Scope parent) {
            if (this.file != null) syntaxFiles.put(lambda, this.file);
            CallableContext context = callableContexts.get(owner);
            // The emitted method name is chosen here, not renamed later, so the locals and
            // nested local functions this body declares are qualified under it.
            String qualifiedName =
                    LambdaNames.bodyQualifiedName(owner.qualifiedName(), lambdaOrdinal++);
            List<ParameterSymbol> parameters = new ArrayList<>();
            Map<String, ParameterSymbol> names = new LinkedHashMap<>();
            boolean sawOptional = false;
            for (int index = 0; index < lambda.parameters().size(); index++) {
                AuxiliarySyntax.Parameter parameter = lambda.parameters().get(index);
                sawOptional = validateParameterDefault(parameter, sawOptional);
                TypeSymbol type = parameter.type() == null
                        ? TypeSymbol.Inferred.INSTANCE
                        : resolveType(parameter.type(), context.typeOwner(),
                                context.typeParameters(), false, false);
                ParameterSymbol symbol = new ParameterSymbol(parameter.name(),
                        qualify(qualifiedName, parameter.name()), location(parameter.span()),
                        type, index, parameter.modifiers(), parameter.defaultValue());
                declaredSymbols.put(parameter, symbol);
                if (names.putIfAbsent(symbol.name(), symbol) != null) {
                    diagnostics.report(DiagnosticCode.DUPLICATE_PARAMETER, file,
                            parameter.span(), parameter.name());
                    continue;
                }
                if (localNameExists(symbol.name(), parent, new MemberAccumulator())) {
                    diagnostics.report(DiagnosticCode.DUPLICATE_LOCAL, file, parameter.span(),
                            symbol.name());
                }
                parameters.add(symbol);
            }
            TypeSymbol returnType = lambda.returnType() == null
                    ? TypeSymbol.Inferred.INSTANCE
                    : resolveType(lambda.returnType(), context.typeOwner(),
                            context.typeParameters(), true, false);
            FunctionSymbol function = new FunctionSymbol(LAMBDA_NAME, qualifiedName,
                    location(lambda.span()), returnType, List.of(), parameters,
                    lambda.isStatic() ? List.of(SyntaxKind.STATIC) : List.of(), true, true);
            allSymbols.add(function);
            allSymbols.addAll(parameters);
            declaredSymbols.put(lambda, function);
            callableContexts.put(function,
                    new CallableContext(context.typeOwner(), context.typeParameters()));
            switch (lambda.body()) {
                case StatementSyntax.Block block -> {
                    bindStatementList(function, block.statements(), parent, block);
                    syntaxScopes.put(lambda, symbolScopes.get(function));
                }
                case ExpressionSyntax body -> {
                    Scope scope = createCallableScope(function, List.of(), parent, body);
                    syntaxScopes.put(lambda, scope);
                    bindExpressionScopes(function, body, scope);
                }
                default -> throw new IllegalStateException(
                        "a lambda body is an expression or a block");
            }
        }

        /// Enforces the declaration-shape rules that decide whether a parameter can be
        /// omitted. `params` is itself omittable in expanded form, so it is not a required
        /// fixed parameter following an optional one.
        private boolean validateParameterDefault(AuxiliarySyntax.Parameter parameter,
                boolean sawOptional) {
            if (parameter.defaultValue() != null) {
                if (parameter.modifiers().contains(SyntaxKind.REF)
                        || parameter.modifiers().contains(SyntaxKind.OUT)) {
                    diagnostics.report(DiagnosticCode.REF_OUT_PARAMETER_CANNOT_HAVE_DEFAULT,
                            file, parameter.span());
                }
                return true;
            }
            if (sawOptional && !parameter.modifiers().contains(SyntaxKind.PARAMS)) {
                diagnostics.report(DiagnosticCode.OPTIONAL_PARAMETER_ORDER, file,
                        parameter.span());
            }
            return sawOptional;
        }

        private void bindLocalDeclaration(FunctionSymbol owner,
                StatementSyntax.LocalDeclaration declaration, CallableContext context,
                Scope parent, MemberAccumulator declarations) {
            TypeSymbol type = resolveType(declaration.type(), context.typeOwner(),
                    context.typeParameters(), false, true);
            for (AuxiliarySyntax.VariableDeclarator variable : declaration.variables()) {
                if (variable.name() != null) {
                    declareLocal(owner, variable.name(), variable.span(), variable, type,
                            declaration.constant(), declaration.using(), parent, declarations);
                } else {
                    declarePatternLocals(owner, variable.designation(), type,
                            declaration.constant(), declaration.using(), parent, declarations);
                }
            }
        }

        private void declareLocal(FunctionSymbol owner, String name, SourceSpan span,
                SyntaxNode syntax, TypeSymbol type, boolean constant, boolean using,
                Scope parent, MemberAccumulator declarations) {

            String qualifiedName = owner.qualifiedName() + "." + name + "#" + localOrdinal++;
            LocalSymbol symbol = new LocalSymbol(name, qualifiedName, location(span), type,
                    constant, using);
            allSymbols.add(symbol);
            declaredSymbols.put(syntax, symbol);
            if (localNameExists(name, parent, declarations)) {
                diagnostics.report(DiagnosticCode.DUPLICATE_LOCAL, file, span, name);
            } else {
                declarations.addUnchecked(symbol);
            }
        }

        private boolean localNameExists(String name, Scope parent,
                MemberAccumulator declarations) {
            if (!declarations.group(name).isEmpty()) {
                return true;
            }
            return parent.lookup(name).stream().anyMatch(symbol ->
                    symbol.kind() == SymbolKind.LOCAL || symbol.kind() == SymbolKind.PARAMETER
                            || symbol instanceof FunctionSymbol function
                                    && function.localFunction());
        }

        private void bindStatement(StatementSyntax statement, FunctionSymbol function,
                Scope scope) {
            switch (statement) {
                case StatementSyntax.Block block -> bindNestedBlock(block, function, scope,
                        List.of());
                case StatementSyntax.If conditional -> {
                    Scope inner = createConditionScope(function, conditional, scope,
                            conditional.condition());
                    bindExpressionScopes(function, conditional.condition(), inner);
                    bindEmbedded(conditional.whenTrue(), function, inner);
                    if (conditional.whenFalse() != null) {
                        bindEmbedded(conditional.whenFalse(), function, inner);
                    }
                }
                case StatementSyntax.While loop -> {
                    Scope inner = createConditionScope(function, loop, scope, loop.condition());
                    bindExpressionScopes(function, loop.condition(), inner);
                    bindEmbedded(loop.body(), function, inner);
                }
                case StatementSyntax.Do loop -> {
                    Scope inner = createConditionScope(function, loop, scope, loop.condition());
                    bindEmbedded(loop.body(), function, inner);
                    bindExpressionScopes(function, loop.condition(), inner);
                }
                case StatementSyntax.For loop -> bindFor(loop, function, scope);
                case StatementSyntax.Foreach loop -> bindForeach(loop, function, scope);
                case StatementSyntax.Switch selection -> bindSwitch(selection, function, scope);
                case StatementSyntax.Labeled labeled -> bindEmbedded(labeled.statement(), function,
                        scope);
                case StatementSyntax.Try attempt -> bindTry(attempt, function, scope);
                case StatementSyntax.Using using -> bindUsing(using, function, scope);
                case StatementSyntax.Lock lock -> {
                    Scope inner = createConditionScope(function, lock, scope, lock.expression());
                    bindExpressionScopes(function, lock.expression(), inner);
                    bindEmbedded(lock.body(), function, inner);
                }
                case StatementSyntax.Checked checked -> bindNestedBlock(checked.body(), function,
                        scope, List.of());
                case StatementSyntax.LocalFunction local -> {
                    Symbol symbol = declaredSymbols.get(local);
                    if (symbol instanceof FunctionSymbol localFunction) {
                        bindCallable(localFunction, local.declaration(), scope);
                    }
                }
                case StatementSyntax.Expression expression -> bindExpressionScopes(function,
                        expression.expression(), scope);
                case StatementSyntax.LocalDeclaration local -> local.variables().forEach(
                        variable -> bindExpressionScopes(function, variable.initializer(), scope));
                case StatementSyntax.Return returned -> bindExpressionScopes(function,
                        returned.expression(), scope);
                case StatementSyntax.Throw thrown -> bindExpressionScopes(function,
                        thrown.expression(), scope);
                case StatementSyntax.Goto jump -> bindExpressionScopes(function, jump.value(),
                        scope);
                case StatementSyntax.Yield yielded -> bindExpressionScopes(function,
                        yielded.expression(), scope);
                case StatementSyntax.Empty ignored -> {
                }
                case StatementSyntax.Break ignored -> {
                }
                case StatementSyntax.Continue ignored -> {
                }
            }
        }

        /// Scopes the names a controlling expression declares to the statement that owns it.
        ///
        /// The scope is only materialised when the expression actually declares something, so
        /// scope chains stay as short as the source demands.
        private Scope createConditionScope(FunctionSymbol function, StatementSyntax statement,
                Scope parent, ExpressionSyntax controlling) {
            MemberAccumulator declarations = new MemberAccumulator();
            collectExpressionLocals(function, controlling, parent, declarations);
            if (declarations.symbols().isEmpty()) {
                return parent;
            }
            Scope scope = Scope.nested(function, parent, declarations.symbols());
            syntaxScopes.put(statement, scope);
            return scope;
        }

        private void bindEmbedded(StatementSyntax statement, FunctionSymbol function,
                Scope parent) {
            if (statement instanceof StatementSyntax.Block block) {
                bindNestedBlock(block, function, parent, List.of());
                return;
            }
            if (statement instanceof StatementSyntax.LocalDeclaration
                    || statement instanceof StatementSyntax.LocalFunction) {
                Scope scope = createRegionScope(function, List.of(statement), parent, statement,
                        List.of());
                bindStatement(statement, function, scope);
                return;
            }
            bindStatement(statement, function, parent);
        }

        private void bindNestedBlock(StatementSyntax.Block block, FunctionSymbol function,
                Scope parent, List<Symbol> baseSymbols) {
            Scope scope = createRegionScope(function, block.statements(), parent, block,
                    baseSymbols);
            for (StatementSyntax statement : block.statements()) {
                bindStatement(statement, function, scope);
            }
        }

        private Scope createRegionScope(FunctionSymbol function, List<StatementSyntax> statements,
                Scope parent, SyntaxNode syntax, List<Symbol> baseSymbols) {
            return createRegionScope(function, statements, parent, syntax, baseSymbols, List.of());
        }

        private Scope createRegionScope(FunctionSymbol function, List<StatementSyntax> statements,
                Scope parent, SyntaxNode syntax, List<Symbol> baseSymbols,
                List<ExpressionSyntax> trailing) {
            MemberAccumulator declarations = new MemberAccumulator();
            baseSymbols.forEach(declarations::addUnchecked);
            collectDirectLocals(function, statements, parent, declarations);
            for (ExpressionSyntax expression : trailing) {
                collectExpressionLocals(function, expression, parent, declarations);
            }
            Scope scope = Scope.nested(function, parent, declarations.symbols());
            syntaxScopes.put(syntax, scope);
            return scope;
        }

        private void bindFor(StatementSyntax.For loop, FunctionSymbol function, Scope parent) {
            Scope scope = createRegionScope(function, loop.initializers(), parent, loop, List.of(),
                    concat(loop.condition(), loop.iterators()));
            for (StatementSyntax initializer : loop.initializers()) {
                bindStatement(initializer, function, scope);
            }
            bindExpressionScopes(function, loop.condition(), scope);
            loop.iterators().forEach(iterator -> bindExpressionScopes(function, iterator, scope));
            bindEmbedded(loop.body(), function, scope);
        }

        private void bindForeach(StatementSyntax.Foreach loop, FunctionSymbol function,
                Scope parent) {
            CallableContext context = callableContexts.get(function);
            TypeSymbol type = resolveType(loop.type(), context.typeOwner(),
                    context.typeParameters(), false, true);
            MemberAccumulator declarations = new MemberAccumulator();
            declarePatternLocals(function, loop.variable(), type, false, false, parent,
                    declarations);
            collectExpressionLocals(function, loop.collection(), parent, declarations);
            Scope scope = Scope.nested(function, parent, declarations.symbols());
            syntaxScopes.put(loop, scope);
            bindExpressionScopes(function, loop.collection(), scope);
            bindEmbedded(loop.body(), function, scope);
        }

        private void bindSwitch(StatementSyntax.Switch selection, FunctionSymbol function,
                Scope parent) {
            Scope governing = createConditionScope(function, selection, parent,
                    selection.expression());
            bindExpressionScopes(function, selection.expression(), governing);
            for (AuxiliarySyntax.SwitchSection section : selection.sections()) {
                Scope scope = createRegionScope(function, section.statements(), governing, section,
                        declareSectionLabels(function, section, governing));
                for (AuxiliarySyntax.SwitchLabel label : section.labels()) {
                    bindExpressionScopes(function, label.guard(), scope);
                }
                for (StatementSyntax statement : section.statements()) {
                    bindStatement(statement, function, scope);
                }
            }
        }

        /// Declares the pattern variables of a switch section, which C# scopes to the section.
        private List<Symbol> declareSectionLabels(FunctionSymbol function,
                AuxiliarySyntax.SwitchSection section, Scope parent) {
            MemberAccumulator declarations = new MemberAccumulator();
            for (AuxiliarySyntax.SwitchLabel label : section.labels()) {
                if (label.pattern() != null) {
                    declarePatternLocals(function, label.pattern(), TypeSymbol.Inferred.INSTANCE,
                            false, false, parent, declarations);
                }
                collectExpressionLocals(function, label.guard(), parent, declarations);
            }
            return declarations.symbols();
        }

        private void bindTry(StatementSyntax.Try attempt, FunctionSymbol function, Scope parent) {
            bindNestedBlock(attempt.body(), function, parent, List.of());
            CallableContext context = callableContexts.get(function);
            for (AuxiliarySyntax.CatchClause clause : attempt.catches()) {
                MemberAccumulator declarations = new MemberAccumulator();
                if (clause.type() != null) {
                    TypeSymbol type = resolveType(clause.type(), context.typeOwner(),
                            context.typeParameters(), false, false);
                    if (clause.name() != null) {
                        LocalSymbol symbol = new LocalSymbol(clause.name(),
                                function.qualifiedName() + "." + clause.name() + "#"
                                        + localOrdinal++,
                                location(clause.nameSpan()), type, false, false);
                        allSymbols.add(symbol);
                        declaredSymbols.put(clause, symbol);
                        declarations.addUnchecked(symbol);
                    }
                }
                // A bare `catch` needs no type resolution: the backend emits it as the JVM's
                // typeless catch-all handler entry, which is exactly C#'s "catches every
                // exception" semantics on this platform.
                if (clause.filter() != null) {
                    // C# evaluates a filter during the first pass of a two-pass search, before
                    // any inner `finally` runs; the JVM unwinds in one pass, so a filter can
                    // only be approximated by catching, testing and rethrowing - which runs
                    // those `finally` blocks first. That reordering is observable, so the
                    // construct is refused rather than silently given different semantics.
                    diagnostics.report(DiagnosticCode.RUNTIME_MODEL_UNSUPPORTED, file,
                            clause.filter().span(), "catch filter ('when')",
                            "C# evaluates it before inner finally blocks run, which the JVM's "
                                    + "single-pass exception handling cannot reproduce");
                }
                collectExpressionLocals(function, clause.filter(), parent, declarations);
                bindNestedBlock(clause.body(), function, parent, declarations.symbols());
                Scope scope = syntaxScopes.get(clause.body());
                syntaxScopes.put(clause, scope);
                bindExpressionScopes(function, clause.filter(), scope);
            }
            if (attempt.finallyBody() != null) {
                bindNestedBlock(attempt.finallyBody(), function, parent, List.of());
            }
        }

        private void bindUsing(StatementSyntax.Using using, FunctionSymbol function,
                Scope parent) {
            if (using.resource() instanceof StatementSyntax.LocalDeclaration declaration) {
                Scope scope = createRegionScope(function, List.of(declaration), parent, using,
                        List.of());
                bindStatement(declaration, function, scope);
                if (using.body() != null) {
                    bindEmbedded(using.body(), function, scope);
                }
                return;
            }
            Scope scope = parent;
            if (using.resource() instanceof ExpressionSyntax resource) {
                scope = createConditionScope(function, using, parent, resource);
                bindExpressionScopes(function, resource, scope);
            }
            if (using.body() != null) {
                bindEmbedded(using.body(), function, scope);
            }
        }

        private void declarePatternLocals(FunctionSymbol owner, PatternSyntax pattern,
                TypeSymbol type, boolean constant, boolean using, Scope parent,
                MemberAccumulator declarations) {
            switch (pattern) {
                case PatternSyntax.Var variable -> declareLocal(owner, variable.name(), variable.span(),
                        variable, type, constant, using, parent, declarations);
                case PatternSyntax.Type typed -> {
                    TypeSymbol declaredType = resolveType(typed.type(),
                            callableContexts.get(owner).typeOwner(),
                            callableContexts.get(owner).typeParameters(), false, false);
                    if (typed.name() != null) {
                        declareLocal(owner, typed.name(), typed.span(), typed, declaredType,
                                constant, using, parent, declarations);
                    }
                }
                case PatternSyntax.Recursive recursive -> {
                    if (recursive.type() != null) {
                        // `o is Point (1, 2)` names a type the expression binder narrows to
                        // and resolves components against, so it has to be resolved here with
                        // every other pattern type.
                        resolveType(recursive.type(), callableContexts.get(owner).typeOwner(),
                                callableContexts.get(owner).typeParameters(), false, false);
                    }
                    if (recursive.designation() != null) {
                        declareLocal(owner, recursive.designation(), recursive.span(), recursive,
                                type, constant, using, parent, declarations);
                    }
                    recursive.positional().forEach(child -> declarePatternLocals(owner, child,
                            type, constant, using, parent, declarations));
                    recursive.properties().forEach(property -> declarePatternLocals(owner,
                            property.pattern(), type, constant, using, parent, declarations));
                }
                case PatternSyntax.ListPattern list -> {
                    if (list.designation() != null) {
                        declareLocal(owner, list.designation(), list.span(), list, type, constant,
                                using, parent, declarations);
                    }
                    list.elements().forEach(child -> declarePatternLocals(owner, child, type,
                            constant, using, parent, declarations));
                }
                case PatternSyntax.Slice slice -> {
                    if (slice.pattern() != null) {
                        declarePatternLocals(owner, slice.pattern(), type, constant, using, parent,
                                declarations);
                    }
                }
                case PatternSyntax.Binary binary -> {
                    declarePatternLocals(owner, binary.left(), type, constant, using, parent,
                            declarations);
                    declarePatternLocals(owner, binary.right(), type, constant, using, parent,
                            declarations);
                }
                case PatternSyntax.Not not -> declarePatternLocals(owner, not.pattern(), type,
                        constant, using, parent, declarations);
                case PatternSyntax.Parenthesized parenthesized -> declarePatternLocals(owner,
                        parenthesized.pattern(), type, constant, using, parent, declarations);
                case PatternSyntax.Missing ignored -> {
                }
                case PatternSyntax.Discard ignored -> {
                }
                case PatternSyntax.Constant constantPattern ->
                    resolveBareNamePattern(owner, constantPattern);
                case PatternSyntax.Relational ignored -> {
                }
            }
        }

        /// Decides whether a constant pattern spelled as a bare name is really a type pattern
        ///: `o is Red` tests a constant, `o is Point` tests a type, and only name
        /// resolution can say which. It is answered here rather than during expression binding
        /// because this is where type lookup lives - including `using` imports and Java
        /// module-path discovery - so `o is Exception` resolves exactly like the `o is
        /// Exception e` that already worked.
        ///
        /// A name that resolves to no type is left alone: expression binding then treats it as
        /// the constant it looks like, and reports an unresolved name if it is neither.
        private void resolveBareNamePattern(FunctionSymbol owner,
                PatternSyntax.Constant pattern) {
            TypeSyntax.Name name = asTypeName(pattern.expression());
            if (name == null) {
                return;
            }
            CallableContext context = callableContexts.get(owner);
            TypeSymbol resolved = lookupNamedType(name,
                    context == null ? "" : context.typeOwner(),
                    context == null ? Map.of() : context.typeParameters());
            if (resolved != null) {
                patternTypes.put(pattern, resolved);
            }
        }

        /// Re-reads a dotted chain of plain identifiers as the type name it may be, or returns
        /// `null` for any expression that cannot spell a type (a literal, a call, an
        /// identifier carrying type arguments the pattern grammar never produces).
        private static TypeSyntax.Name asTypeName(ExpressionSyntax expression) {
            List<TypeSyntax.Segment> segments = new ArrayList<>();
            ExpressionSyntax current = expression;
            while (current instanceof ExpressionSyntax.MemberAccess access) {
                if (!access.typeArguments().isEmpty() || access.nullConditional()) {
                    return null;
                }
                segments.addFirst(TypeSyntax.Segment.of(access.span(), access.name()));
                current = access.receiver();
            }
            if (!(current instanceof ExpressionSyntax.Identifier identifier)
                    || !identifier.typeArguments().isEmpty()) {
                return null;
            }
            segments.addFirst(TypeSyntax.Segment.of(identifier.span(), identifier.name()));
            return new TypeSyntax.Name(expression.span(), segments, false);
        }

        private TypeParameterSet createTypeParameters(String ownerName,
                List<AuxiliarySyntax.TypeParameter> syntax,
                Map<String, TypeParameterSymbol> inherited) {
            return createTypeParameters(ownerName, syntax, List.of(), inherited);
        }

        private TypeParameterSet createTypeParameters(String ownerName,
                List<AuxiliarySyntax.TypeParameter> syntax,
                List<AuxiliarySyntax.ConstraintClause> constraints,
                Map<String, TypeParameterSymbol> inherited) {
            validateConstraints(constraints, ownerName);
            List<TypeParameterSymbol> declared = new ArrayList<>();
            List<TypeParameterSymbol> accepted = new ArrayList<>();
            Map<String, TypeParameterSymbol> context = new LinkedHashMap<>(inherited);
            Set<String> ownNames = new java.util.LinkedHashSet<>();
            for (int index = 0; index < syntax.size(); index++) {
                AuxiliarySyntax.TypeParameter parameter = syntax.get(index);
                TypeParameterSymbol symbol = new TypeParameterSymbol(parameter.name(),
                        qualify(ownerName, parameter.name()), location(parameter.span()), index,
                        BuiltinType.OBJECT, valueKindOf(parameter.name(), constraints),
                        constraintTypeOf(parameter.name(), constraints, ownerName));
                declared.add(symbol);
                declaredSymbols.put(parameter, symbol);
                if (!ownNames.add(parameter.name())) {
                    diagnostics.report(DiagnosticCode.DUPLICATE_TYPE_PARAMETER, file,
                            parameter.span(), parameter.name());
                } else {
                    accepted.add(symbol);
                    context.put(parameter.name(), symbol);
                }
            }
            return new TypeParameterSet(List.copyOf(declared), List.copyOf(accepted),
                    Collections.unmodifiableMap(context));
        }

        private TypeSymbol resolveType(TypeSyntax syntax, String ownerName,
                Map<String, TypeParameterSymbol> typeParameters, boolean allowVoid,
                boolean allowVar) {
            return resolveType(syntax, ownerName, typeParameters, allowVoid, allowVar, false);
        }

        /// The final flag is deliberately supplied only by `typeof`: C# permits an unbound
        /// generic name such as `Box<>` there, while the same syntax is not a usable storage
        /// type anywhere else.
        private TypeSymbol resolveType(TypeSyntax syntax, String ownerName,
                Map<String, TypeParameterSymbol> typeParameters, boolean allowVoid,
                boolean allowVar, boolean allowUnboundGeneric) {
            TypeSymbol resolved = switch (syntax) {
                case TypeSyntax.Predefined predefined -> resolvePredefined(predefined, allowVoid,
                        allowVar);
                case TypeSyntax.Name name -> resolveNamedType(name, ownerName, typeParameters,
                        allowUnboundGeneric);
                case TypeSyntax.Array array -> new TypeSymbol.Array(resolveType(array.element(),
                        ownerName, typeParameters, false, false), array.ranks());
                case TypeSyntax.Tuple tuple -> new TypeSymbol.Tuple(tuple.elements().stream()
                        .map(element -> new TypeSymbol.TupleElement(resolveType(element.type(),
                                ownerName, typeParameters, false, false), element.name()))
                        .toList());
                case TypeSyntax.Nullable nullable -> new TypeSymbol.Nullable(resolveType(
                        nullable.element(), ownerName, typeParameters, false, false));
                case TypeSyntax.Ref ref -> new TypeSymbol.Ref(resolveType(ref.element(), ownerName,
                        typeParameters, false, false), ref.readOnly());
                case TypeSyntax.Omitted ignored -> TypeSymbol.Error.INSTANCE;
                case TypeSyntax.Missing ignored -> TypeSymbol.Error.INSTANCE;
            };
            resolvedTypes.put(syntax, resolved);
            return resolved;
        }

        private TypeSymbol resolvePredefined(TypeSyntax.Predefined predefined,
                boolean allowVoid, boolean allowVar) {
            if (predefined.keyword() == SyntaxKind.VAR) {
                if (!allowVar) {
                    diagnostics.report(DiagnosticCode.INVALID_VAR_CONTEXT, file,
                            predefined.span());
                    return TypeSymbol.Error.INSTANCE;
                }
                return TypeSymbol.Inferred.INSTANCE;
            }
            BuiltinType builtin = BuiltinType.fromKeyword(predefined.keyword()).orElse(null);
            if (builtin == null) {
                return TypeSymbol.Error.INSTANCE;
            }
            if (builtin == BuiltinType.VOID && !allowVoid) {
                diagnostics.report(DiagnosticCode.VOID_NOT_ALLOWED, file, predefined.span());
                return TypeSymbol.Error.INSTANCE;
            }
            if (builtin == BuiltinType.DYNAMIC) {
                diagnostics.report(DiagnosticCode.OBJECT_MODEL_UNSUPPORTED, file,
                        predefined.span(), "dynamic");
            }
            return builtin;
        }

        private TypeSymbol resolveNamedType(TypeSyntax.Name name, String ownerName,
                Map<String, TypeParameterSymbol> typeParameters) {
            return resolveNamedType(name, ownerName, typeParameters, false);
        }

        private TypeSymbol resolveNamedType(TypeSyntax.Name name, String ownerName,
                Map<String, TypeParameterSymbol> typeParameters, boolean allowUnboundGeneric) {
            TypeSymbol resolved = lookupNamedType(name, ownerName, typeParameters,
                    allowUnboundGeneric);
            if (resolved != null) {
                reportQualifiedTypeName(name, resolved);
                return resolved;
            }
            diagnostics.report(DiagnosticCode.TYPE_OR_NAMESPACE_NOT_FOUND, file, name.span(),
                    name.text());
            return TypeSymbol.Error.INSTANCE;
        }

        /// Resolves a name to a type, or returns `null` when no type carries that name.
        ///
        /// Separated from `resolveNamedType` so a caller that is only *asking whether* a name
        /// is a type - the bare-name pattern probe - gets an answer instead of a
        /// diagnostic, while every position that requires a type still reports one.
        private TypeSymbol lookupNamedType(TypeSyntax.Name name, String ownerName,
                Map<String, TypeParameterSymbol> typeParameters) {
            return lookupNamedType(name, ownerName, typeParameters, false);
        }

        /// Refuses a type named by its own fully qualified name.
        ///
        /// The test is exact and has no false positives: a name is qualified when what the
        /// source wrote *is* the resolved type's qualified name. `java.util.Map` spells it and
        /// is refused; `Map.Entry` does not - it reaches a nested type through an imported
        /// simple name, which is the shape C# itself encourages - and neither does a simple
        /// name, an alias, or a generic argument list, because only the segments are compared.
        ///
        /// Nested Java types carry `$` in their qualified name, so the comparison normalises
        /// it: `java.util.Map.Entry` written out is refused just as `java.util.Map` is.
        private void reportQualifiedTypeName(TypeSyntax.Name name, TypeSymbol resolved) {
            if (name.segments().size() < 2) {
                return;
            }
            String written = name.segments().stream()
                    .map(TypeSyntax.Segment::identifier)
                    .reduce((left, right) -> left + "." + right)
                    .orElse("");
            TypeSymbol subject = resolved instanceof TypeSymbol.Constructed constructed
                    ? constructed.definition() : resolved;
            if (!(subject instanceof NamedTypeSymbol named)) {
                return;
            }
            String qualified = named.qualifiedName().replace('$', '.');
            if (!written.equals(qualified)) {
                return;
            }
            int lastDot = qualified.lastIndexOf('.');
            String namespaceName = qualified.substring(0, lastDot);
            String simpleName = qualified.substring(lastDot + 1);
            diagnostics.report(DiagnosticCode.QUALIFIED_TYPE_NAME, file, name.span(),
                    simpleName, namespaceName, simpleName, qualified);
        }

        private TypeSymbol lookupNamedType(TypeSyntax.Name name, String ownerName,
                Map<String, TypeParameterSymbol> typeParameters, boolean allowUnboundGeneric) {
            TypeSyntax.Segment finalSegment = name.segments().getLast();
            if (name.segments().size() == 1 && !finalSegment.isGeneric()) {
                TypeParameterSymbol parameter = typeParameters.get(finalSegment.identifier());
                if (parameter != null) {
                    return parameter;
                }
            }

            String aliasedName = finalSegment.identifier();
            // `Func`/`Action` are the JDK's generic functional interfaces under C#'s names
            //. Mapping them here, where a written type name is resolved, is what makes a
            // delegate-typed local, parameter and return work everywhere at once - and what
            // makes a V# lambda assigned to one convert through the ordinary functional-interface
            // path rather than a delegate model V# does not have.
            TypeSymbol delegateType = delegateInterface(aliasedName, name, ownerName,
                    typeParameters, finalSegment);
            if (delegateType != null) {
                return delegateType;
            }
            boolean hasOmittedArguments = finalSegment.typeArguments().stream()
                    .anyMatch(TypeSyntax.Omitted.class::isInstance);
            boolean isUnboundGeneric = hasOmittedArguments
                    && finalSegment.typeArguments().stream()
                            .allMatch(TypeSyntax.Omitted.class::isInstance);
            if (hasOmittedArguments && (!allowUnboundGeneric || !isUnboundGeneric)) {
                diagnostics.report(DiagnosticCode.UNBOUND_GENERIC_NAME, file, name.span());
                return TypeSymbol.Error.INSTANCE;
            }
            List<TypeSymbol> arguments = isUnboundGeneric
                    ? List.of()
                    : finalSegment.typeArguments().stream()
                            .map(argument -> resolveType(argument, ownerName, typeParameters,
                                    false, false))
                            .toList();
            int requestedArity = finalSegment.typeArguments().size();
            String pathOwner = name.segments().stream()
                    .limit(name.segments().size() - 1L)
                    .map(TypeSyntax.Segment::identifier)
                    .reduce((left, right) -> left + "." + right)
                    .orElse("");
            // `using X = Y;` substitutes a spelling before resolution, so the alias target
            // is then found by the ordinary rules and may name a V# type, a Java type or a
            // namespace-qualified one without three mechanisms. `global::` skips aliases exactly
            // as it skips imports, and a written qualification is never an alias use.
            String aliasedPath = null;
            if (!name.global() && name.segments().size() == 1) {
                String target = scopedAliasFor(ownerName, name.segments().getFirst().identifier());
                if (target == null) {
                    target = aliasTargetFor(name.segments().getFirst().identifier());
                }
                if (target != null) {
                    int lastDot = target.lastIndexOf('.');
                    aliasedPath = lastDot < 0 ? "" : target.substring(0, lastDot);
                    aliasedName = lastDot < 0 ? target : target.substring(lastDot + 1);
                }
            }
            List<String> candidates = new ArrayList<>();
            if (aliasedPath != null) {
                candidates.add(aliasedPath);
            }
            // Where the imported candidates begin. Everything before this index came from the
            // enclosing chain and is searched in order, first match winning; everything from it
            // on is one unordered import set in which two answers are an ambiguity.
            int importedFrom;
            if (aliasedPath != null) {
                importedFrom = candidates.size();
            } else if (name.global()) {
                candidates.add(pathOwner);
                importedFrom = candidates.size();
            } else {
                for (String prefix = ownerName; ; prefix = parentName(prefix)) {
                    candidates.add(joinQualified(prefix, pathOwner));
                    if (prefix.isEmpty()) {
                        break;
                    }
                }
                importedFrom = candidates.size();
                // C# searches the enclosing namespaces before the imported ones (§7.8), so the
                // `using` namespaces are appended after the whole enclosing chain: a type
                // declared next to the reference always wins over an imported name of the same
                // spelling. `global::` skips imports entirely, which is its purpose.
                List<String> visible = new ArrayList<>(scopedImports(ownerName));
                for (String imported : importedNamespaces()) {
                    if (!visible.contains(imported)) {
                        visible.add(imported);
                    }
                }
                for (String imported : visible) {
                    String candidate = joinQualified(imported, pathOwner);
                    if (!candidates.contains(candidate)) {
                        candidates.add(candidate);
                    }
                }
            }

            for (int index = 0; index < importedFrom; index++) {
                Symbol found = declaredTypeHeaders.get(new TypeKey(candidates.get(index),
                        aliasedName, requestedArity));
                if (found instanceof NamedTypeSymbol type) {
                    return isUnboundGeneric || arguments.isEmpty()
                            ? type : new TypeSymbol.Constructed(type, arguments);
                }
            }
            NamedTypeSymbol importedType = null;
            for (int index = importedFrom; index < candidates.size(); index++) {
                Symbol found = declaredTypeHeaders.get(new TypeKey(candidates.get(index),
                        aliasedName, requestedArity));
                if (!(found instanceof NamedTypeSymbol type) || type == importedType) {
                    continue;
                }
                if (importedType != null) {
                    diagnostics.report(DiagnosticCode.AMBIGUOUS_REFERENCE, file, name.span(),
                            aliasedName, importedType.qualifiedName(),
                            type.qualifiedName());
                    return TypeSymbol.Error.INSTANCE;
                }
                importedType = type;
            }
            if (importedType != null) {
                return isUnboundGeneric || arguments.isEmpty()
                        ? importedType : new TypeSymbol.Constructed(importedType, arguments);
            }
            for (String candidateOwner : candidates) {
                for (Map.Entry<TypeKey, Symbol> entry : declaredTypeHeaders.entrySet()) {
                    TypeKey key = entry.getKey();
                    if (entry.getValue() instanceof NamedTypeSymbol named
                            && key.owner().equals(candidateOwner)
                            && key.name().equals(aliasedName)) {
                        if (requestedArity == 0 && javaInterop.isJavaType(named)) {
                            return named;
                        }
                        diagnostics.report(DiagnosticCode.WRONG_TYPE_ARGUMENT_COUNT, file,
                                name.span(), name.text(), named.arity());
                        return TypeSymbol.Error.INSTANCE;
                    }
                }
            }
            
            // Fallback to Java module-path resolution. A declared V# type always wins over a
            // module-path one - every declared candidate above has already been tried - so the
            // two worlds are never ambiguous against each other, only within themselves.
            NamedTypeSymbol javaType = null;
            String javaOwner = null;
            for (int index = 0; index < candidates.size(); index++) {
                String candidateOwner = candidates.get(index);
                String fullCandidate = candidateOwner.isEmpty() ? aliasedName : candidateOwner + "." + aliasedName;
                NamedTypeSymbol resolved = javaInterop.resolveType(javaSpelling(fullCandidate));
                if (resolved == null || resolved == javaType) {
                    continue;
                }
                if (index < importedFrom) {
                    javaType = resolved;
                    javaOwner = candidateOwner;
                    break;
                }
                if (javaType != null) {
                    diagnostics.report(DiagnosticCode.AMBIGUOUS_REFERENCE, file, name.span(),
                            aliasedName, javaType.qualifiedName(),
                            resolved.qualifiedName());
                    return TypeSymbol.Error.INSTANCE;
                }
                javaType = resolved;
                javaOwner = candidateOwner;
            }
            if (javaType != null) {
                // `object` *is* `java.lang.Object` and `string` *is* `java.lang.String`, exactly
                // as C#'s keywords are its `System` types. Reading a descriptor already maps
                // both to the builtin, so a written spelling must agree: leaving them as
                // separate module-path symbols made one JVM class two V# types that did not
                // convert to each other, which every `Object`-returning JDK method walked into
                //. `new java.lang.Object()` consequently reports the same VS20001 as
                // `new object()`, which is the documented object-model exclusion, not a new one.
                TypeSymbol alias = switch (javaType.qualifiedName()) {
                    case "java.lang.Object" -> BuiltinType.OBJECT;
                    case "java.lang.String" -> BuiltinType.STRING;
                    default -> null;
                };
                if (alias != null) {
                    return alias;
                }
                TypeKey key = new TypeKey(javaOwner, aliasedName, javaType.arity());
                declaredTypeHeaders.put(key, javaType);
                if (javaType.arity() > 0) {
                    // Java permits raw use of a generic class. Keep an arity-zero lookup alias
                    // for existing V# source while the symbol itself retains its real arity.
                    declaredTypeHeaders.put(new TypeKey(javaOwner,
                            aliasedName, 0), javaType);
                }
                allSymbols.add(javaType);

                List<Symbol> javaMembers = javaInterop.getMembers(javaType);
                allSymbols.addAll(javaMembers);
                Scope typeScope = Scope.root(javaType, javaMembers);
                symbolScopes.put(javaType, typeScope);

                if (isUnboundGeneric || arguments.isEmpty()) {
                    return javaType;
                }
                if (arguments.size() != javaType.arity()) {
                    diagnostics.report(DiagnosticCode.WRONG_TYPE_ARGUMENT_COUNT, file,
                            name.span(), name.text(), javaType.arity());
                    return TypeSymbol.Error.INSTANCE;
                }
                return new TypeSymbol.Constructed(javaType, arguments);
            }
            return null;
        }

        /// The JDK class a `System` name spells. The reverse of the `java.lang.Object` alias
        /// below: `System.Type` *is* `java.lang.Class`, so a `GetType()` result carries
        /// the entire reflection surface and a `Class` crossing back from a JDK method is the
        /// same V# type rather than a second one. A V#-owned `Type` would have had to redeclare
        /// that surface member by member and would reintroduce exactly the two-types-one-class
        /// split the design removed. The name is only consulted after every declared candidate has
        /// failed, so a user-declared `Type` still wins.
        private static String javaSpelling(String qualifiedName) {
            return switch (qualifiedName) {
                case "System.Type" -> "java.lang.Class";
                // `Task` *is* `java.util.concurrent.Future`, by the same reasoning and
                // through the same door as `System.Type`. V# does not implement the C# awaiter
                // protocol; it starts an async body on a virtual thread and hands back the
                // future that submission produced, so the type a caller receives is the JDK's.
                // Declaring a V#-owned `Task` instead would have created the two-types-one-class
                // split the design removed, and would have made every JDK method that already returns
                // a `Future` un-awaitable.
                case "Task", "System.Threading.Tasks.Task" -> "java.util.concurrent.Future";
                default -> qualifiedName;
            };
        }

        /// Refuses an `async` callable whose return type is not a task.
        ///
        /// V# manufactures the task rather than transforming the body into a state machine, so
        /// the declared type is literally what the wrapper returns: there is no `async void`
        /// and no custom awaitable builder to accept anything else. The check is skipped for an
        /// already-erroneous type so one mistake reports once.
        private void requireTaskReturnType(FunctionSymbol symbol, SourceSpan span) {
            if (!symbol.isAsync() || symbol.returnType() == TypeSymbol.Error.INSTANCE) {
                return;
            }
            // `void` joins `Task` and `Task<T>`: it is the declaration that says nobody will
            // wait, which V# honours by starting the body detached and logging a failure no
            // caller can observe.
            if (symbol.returnType() != BuiltinType.VOID
                    && !TaskTypes.isTask(symbol.returnType())) {
                diagnostics.report(DiagnosticCode.ASYNC_RETURN_TYPE, file, span,
                        symbol.returnType().displayName());
            }
            // A by-reference parameter is carried as a shared cell. The async body reads and
            // writes that cell on another thread while the caller already holds the result of
            // the wrapper, so an `out` would be read before it is written and a `ref` would be
            // a plain data race. Refusing is the only correct answer, and it is also C#'s.
            for (ParameterSymbol parameter : symbol.parameters()) {
                for (SyntaxKind modifier : parameter.modifiers()) {
                    if (modifier == SyntaxKind.REF || modifier == SyntaxKind.OUT
                            || modifier == SyntaxKind.IN) {
                        diagnostics.report(DiagnosticCode.ASYNC_BY_REFERENCE_PARAMETER, file,
                                parameter.location().span(), modifier.display());
                    }
                }
            }
        }

        private static List<AuxiliarySyntax.TypeParameter> typeParametersOf(
                DeclarationSyntax declaration) {
            return switch (declaration) {
                case DeclarationSyntax.StaticContainer container -> container.typeParameters();
                case DeclarationSyntax.Struct structure -> structure.typeParameters();
                case DeclarationSyntax.Namespace ignored -> List.of();
                case DeclarationSyntax.Enum ignored -> List.of();
                case DeclarationSyntax.Field ignored -> List.of();
                case DeclarationSyntax.Method ignored -> List.of();
                case DeclarationSyntax.Operator ignored -> List.of();
                case DeclarationSyntax.ConversionOperator ignored -> List.of();
                case DeclarationSyntax.Unsupported ignored -> List.of();
            };
        }

        private static List<AuxiliarySyntax.ConstraintClause> constraintsOf(
                DeclarationSyntax declaration) {
            return switch (declaration) {
                case DeclarationSyntax.StaticContainer container -> container.constraints();
                case DeclarationSyntax.Struct structure -> structure.constraints();
                case DeclarationSyntax.Method method -> method.constraints();
                case DeclarationSyntax.Namespace ignored -> List.of();
                case DeclarationSyntax.Enum ignored -> List.of();
                case DeclarationSyntax.Field ignored -> List.of();
                case DeclarationSyntax.Operator ignored -> List.of();
                case DeclarationSyntax.ConversionOperator ignored -> List.of();
                case DeclarationSyntax.Unsupported ignored -> List.of();
            };
        }

        /// Refuses the `where` clauses that cannot mean anything here.
        ///
        /// `new()` is refused because its entire purpose in C# is to permit `new T()`, and
        /// `new T()` is refused by V# as the object model it omits - the constraint would be a
        /// promise with no operation able to consume it, and accepting it silently is the same
        /// shape the design was written to end. A *named* type is refused when it is a `struct` or
        /// `record struct`: C# itself reports CS0701 for that, because a sealed value type can
        /// only ever be satisfied by itself. An interface constraint stays accepted and is
        /// recorded as not yet enforced rather than refused, because it is a valid C# clause
        /// whose enforcement is a separate piece of work.
        private void validateConstraints(List<AuxiliarySyntax.ConstraintClause> constraints,
                String ownerName) {
            for (AuxiliarySyntax.ConstraintClause clause : constraints) {
                if (clause.constructorConstraint()) {
                    diagnostics.report(DiagnosticCode.OBJECT_MODEL_UNSUPPORTED, file,
                            clause.span(), "a new() type constraint");
                }
                for (TypeSyntax constraint : clause.constraints()) {
                    TypeSyntax written = constraint instanceof TypeSyntax.Nullable nullable
                            ? nullable.element()
                            : constraint;
                    if (!(written instanceof TypeSyntax.Name named)
                            || KEYWORD_CONSTRAINTS.contains(lastSegment(named))) {
                        continue;
                    }
                    TypeSymbol resolved = resolveType(written, ownerName, Map.of(), false, false);
                    if (resolved instanceof NamedTypeSymbol type
                            && (type.declaredKind() == NamedTypeSymbol.DeclaredKind.STRUCT
                                    || type.declaredKind()
                                            == NamedTypeSymbol.DeclaredKind.RECORD_STRUCT)) {
                        diagnostics.report(DiagnosticCode.INVALID_CONSTRAINT_TYPE, file,
                                written.span(), type.name());
                    }
                }
            }
        }

        /// The named type a `where` clause demands the argument convert to, or `null`.
        ///
        /// Only a type that survived [#validateConstraints] reaches this - a `struct` or
        /// `record struct` was already refused - so what remains is an interface or a class,
        /// both of which C# admits and both of which the JVM hierarchy can answer for.
        private TypeSymbol constraintTypeOf(String name,
                List<AuxiliarySyntax.ConstraintClause> constraints, String ownerName) {
            for (AuxiliarySyntax.ConstraintClause clause : constraints) {
                if (!clause.parameter().equals(name)) {
                    continue;
                }
                for (TypeSyntax constraint : clause.constraints()) {
                    if (!(constraint instanceof TypeSyntax.Name named)
                            || KEYWORD_CONSTRAINTS.contains(lastSegment(named))) {
                        continue;
                    }
                    TypeSymbol resolved = resolveType(named, ownerName, Map.of(), false, false);
                    if (resolved instanceof NamedTypeSymbol type
                            && type.declaredKind() != NamedTypeSymbol.DeclaredKind.STRUCT
                            && type.declaredKind() != NamedTypeSymbol.DeclaredKind.RECORD_STRUCT) {
                        return resolved;
                    }
                }
            }
            return null;
        }

        /// The constraint spellings that are keywords rather than type names.
        private static final Set<String> KEYWORD_CONSTRAINTS =
                Set.of("struct", "class", "notnull", "unmanaged");

        private static String lastSegment(TypeSyntax.Name name) {
            return name.segments().isEmpty() ? ""
                    : name.segments().getLast().identifier();
        }

        /// The value-kind a `where` clause demands of one type parameter.
        ///
        /// Only the two kinds the JVM can be held to are read here. `struct` and `unmanaged`
        /// both require a non-nullable value type - `unmanaged` is strictly narrower, and
        /// narrowing it further needs a notion of managed-ness V# does not have - while `class`
        /// and `class?` require a reference type. `notnull` is accepted and carries no
        /// value-kind, because V# has no nullable-reference analysis to enforce it against, and
        /// a named type or interface constraint is not enforced yet; both are recorded as open
        /// rather than pretended.
        private static TypeParameterSymbol.ValueKind valueKindOf(String name,
                List<AuxiliarySyntax.ConstraintClause> constraints) {
            for (AuxiliarySyntax.ConstraintClause clause : constraints) {
                if (!clause.parameter().equals(name)) {
                    continue;
                }
                for (TypeSyntax constraint : clause.constraints()) {
                    TypeSyntax written = constraint instanceof TypeSyntax.Nullable nullable
                            ? nullable.element()
                            : constraint;
                    String spelling = written instanceof TypeSyntax.Name named
                            && named.segments().size() == 1
                            ? named.segments().getFirst().identifier()
                            : "";
                    switch (spelling) {
                        case "struct", "unmanaged" -> {
                            return TypeParameterSymbol.ValueKind.VALUE;
                        }
                        case "class" -> {
                            return TypeParameterSymbol.ValueKind.REFERENCE;
                        }
                        default -> {
                        }
                    }
                }
            }
            return TypeParameterSymbol.ValueKind.ANY;
        }

        private static List<DeclarationSyntax> membersOf(DeclarationSyntax declaration) {
            return switch (declaration) {
                case DeclarationSyntax.StaticContainer container -> container.members();
                case DeclarationSyntax.Struct structure -> structure.members();
                case DeclarationSyntax.Namespace ignored -> List.of();
                case DeclarationSyntax.Enum ignored -> List.of();
                case DeclarationSyntax.Field ignored -> List.of();
                case DeclarationSyntax.Method ignored -> List.of();
                case DeclarationSyntax.Operator ignored -> List.of();
                case DeclarationSyntax.ConversionOperator ignored -> List.of();
                case DeclarationSyntax.Unsupported ignored -> List.of();
            };
        }

        private String typeSpelling(TypeSyntax syntax) {
            return switch (syntax) {
                case TypeSyntax.Predefined predefined -> predefined.keyword().display();
                case TypeSyntax.Name name -> name.text();
                case TypeSyntax.Array array -> typeSpelling(array.element()) + "[]";
                case TypeSyntax.Tuple ignored -> "tuple";
                case TypeSyntax.Nullable nullable -> typeSpelling(nullable.element()) + "?";
                case TypeSyntax.Ref ref -> "ref " + typeSpelling(ref.element());
                case TypeSyntax.Omitted ignored -> "<omitted>";
                case TypeSyntax.Missing ignored -> "<missing>";
            };
        }

        private SourceLocation location(SourceSpan span) {
            return new SourceLocation(this.file, span);
        }

        private SourceLocation location(SyntaxNode node, SourceSpan span) {
            SourceFile file = this.file;
            return new SourceLocation(file != null ? file : vsharp.compiler.source.SourceFile.of("<unknown>", ""), span);
        }


        


        private static String ownerDisplay(String ownerName) {
            return ownerName.isEmpty() ? "<global namespace>" : ownerName;
        }

        private static String qualify(String owner, String name) {
            return owner.isEmpty() ? name : owner + "." + name;
        }

        private static String joinQualified(String left, String right) {
            if (left.isEmpty()) {
                return right;
            }
            return right.isEmpty() ? left : left + "." + right;
        }

        private static String parentName(String name) {
            int separator = name.lastIndexOf('.');
            return separator < 0 ? "" : name.substring(0, separator);
        }
    }

    record TypeKey(String owner, String name, int arity) {}

    private record TypeParameterSet(List<TypeParameterSymbol> declared,
            List<TypeParameterSymbol> accepted, Map<String, TypeParameterSymbol> context) {}

    private record CallableContext(String typeOwner,
            Map<String, TypeParameterSymbol> typeParameters) {}

    private record CallableDeclaration(FunctionSymbol symbol,
            DeclarationSyntax declaration) {}

    private static final class MemberAccumulator {
        private final List<Symbol> symbols = new ArrayList<>();
        private final Map<String, List<Symbol>> groups = new LinkedHashMap<>();

        void addUnchecked(Symbol symbol) {
            symbols.add(symbol);
            groups.computeIfAbsent(symbol.name(), ignored -> new ArrayList<>()).add(symbol);
        }

        List<Symbol> group(String name) {
            return groups.getOrDefault(name, List.of());
        }

        List<Symbol> symbols() {
            return List.copyOf(symbols);
        }
    }

    static final class NamespaceNode {
        private final String name;
        private final String fullName;
        private final SourceLocation location;
        private final LinkedHashMap<String, NamespaceNode> children = new LinkedHashMap<>();
        private final List<DeclarationSyntax.Namespace> parts = new ArrayList<>();
        private final List<DeclarationSyntax> declarations = new ArrayList<>();
        private final List<StatementSyntax> statements = new ArrayList<>();
        private NamespaceSymbol symbol;

        NamespaceNode(String name, String fullName, SourceLocation location) {
            this.name = name;
            this.fullName = fullName;
            this.location = location;
        }
    }
}
