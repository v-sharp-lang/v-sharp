package vsharp.compiler.semantics.binding;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassSignature;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodSignature;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Signature;
import java.lang.classfile.attribute.SignatureAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.AccessFlag;
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Enumeration;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import vsharp.compiler.semantics.symbols.FieldSymbol;
import vsharp.compiler.semantics.symbols.FunctionSymbol;
import vsharp.compiler.semantics.symbols.NamedTypeSymbol;
import vsharp.compiler.semantics.symbols.ParameterSymbol;
import vsharp.compiler.semantics.symbols.SourceLocation;
import vsharp.compiler.semantics.symbols.Symbol;
import vsharp.compiler.semantics.symbols.TypeParameterSymbol;
import vsharp.compiler.semantics.types.BuiltinType;
import vsharp.compiler.semantics.types.TypeSymbol;
import vsharp.compiler.source.SourceFile;
import vsharp.compiler.source.SourceSpan;
import vsharp.compiler.syntax.SyntaxKind;

/// Resolves Java types from the running JVM's module path so V# source can name them
/// directly, without the compiler hand-declaring them in `corelib.vs`.
///
/// Resolution reads class files through [ClassFile] rather than reflection, so a type is
/// described exactly as the JVM records it and no user class is ever initialised by the
/// compiler.
///
/// One instance belongs to one binding run and is confined to that run's thread. Its caches
/// are memoisation of a pure function of the module path, so a fresh instance always
/// produces identical symbols; they are not shared global state.
///
/// Discovery is deliberately partial and its limits are exact: public, non-synthetic,
/// non-bridge methods, public non-synthetic fields, and public non-synthetic constructors
/// of a resolvable class or interface are exposed, together with the same categories of
/// member inherited from the superclass and interface closure. Concrete generic signatures
/// and type-variable substitutions are preserved, and a wildcard is carried as a real
/// [TypeSymbol.Wildcard] with its declared bound and variance. Annotations are not
/// mapped. A descriptor that mentions an unresolvable class is still exposed, with that
/// position typed as `object`.
///
/// An inherited member is named after the type that inherits it, not the class that declares
/// it. That is what javac emits, and it is the only choice that is always correct here: JVM
/// resolution searches superclasses, so the reference still resolves to the declaring member,
/// while the referenced class stays one the V# source could name and is therefore accessible.
/// Naming the declaring class instead would emit references to package-private bases such as
/// `java.lang.AbstractStringBuilder` and fail with `IllegalAccessError` at first execution.
public final class JavaInterop {

    private static final SourceLocation JAVA_MODULE_LOCATION = new SourceLocation(
            SourceFile.of("<java-module-path>", ""), new SourceSpan(0, 0));

    private final Map<String, NamedTypeSymbol> resolvedTypes = new HashMap<>();
    private final Set<String> unresolvableTypes = new HashSet<>();
    private final Map<NamedTypeSymbol, List<Symbol>> resolvedMembers = new HashMap<>();
    private final Map<NamedTypeSymbol, List<FunctionSymbol>> resolvedConstructors = new HashMap<>();
    private final Map<NamedTypeSymbol, FunctionalKey> functionalMethods = new HashMap<>();
    private final Map<NamedTypeSymbol, Boolean> throwableTypes = new HashMap<>();
    private final Map<String, Set<String>> supertypeClosures = new HashMap<>();
    private final Map<NamedTypeSymbol, List<TypeParameterSymbol>> resolvedTypeParameters =
            new HashMap<>();
    private final Map<FunctionSymbol, FunctionSymbol> erasedDeclarations =
            new IdentityHashMap<>();

    private final Set<String> classpathNamespaces;
    private final ClassLoader classLoader;

    /// Identifies this resolver's classpath for [ClassFileCache], or empty when the classpath
    /// cannot be proven unchanged and must therefore be read afresh every time.
    private final Optional<String> classFileSignature;

    /// Creates a resolver with empty caches, confined to one binding run, reading only the
    /// module path. Every V# program can see the JDK without declaring anything.
    public JavaInterop() {
        this(List.of());
    }

    /// Creates a resolver that also reads `classpath`. The entries are loaded through a
    /// loader of this compilation's own, and class files are read from that loader, so
    /// descriptor resolution and namespace legality agree by construction. The parent is the
    /// compiler's own loader because the V# runtime library travels with the compiler and is
    /// part of the language: every corelib carrier - `vsharp.runtime.VsFormatException` and
    /// its siblings - must resolve for a program that declared nothing.
    public JavaInterop(List<Path> classpath) {
        Objects.requireNonNull(classpath, "classpath");
        List<Path> entries = List.copyOf(classpath);
        entries.forEach(entry -> Objects.requireNonNull(entry, "classpath entry"));
        this.classFileSignature = ClassFileCache.signatureOf(entries);
        // The package set is derived from the same immutable artifacts as the class files, so
        // it is cached on the same signature: scanning 57 jars costs ~80 ms, paid once per
        // edit before this.
        this.classpathNamespaces = ClassFileCache.namespaces(
                classFileSignature, () -> classpathNamespaces(entries));
        // One loader per classpath, reused while its artifacts are unchanged and closed when
        // they are not. Building a fresh loader per compilation and never closing it left the
        // jar pinned in the JDK's process-wide cache, so a rebuilt dependency stayed invisible
        // to a long-lived server for good.
        this.classLoader = ClassFileCache.loaderFor(classFileSignature, entries, SYSTEM_LOADER);

    }

    /// Resolves the type named `qualifiedName`, or `null` when the module path has no such
    /// type. A miss is not a diagnostic: callers try several candidate owners per name.
    public NamedTypeSymbol resolveType(String qualifiedName) {
        Objects.requireNonNull(qualifiedName, "qualifiedName");
        NamedTypeSymbol cached = resolvedTypes.get(qualifiedName);
        if (cached != null) {
            return cached;
        }
        if (unresolvableTypes.contains(qualifiedName)) {
            return null;
        }
        // A nested type is written `Map.Entry` in V# exactly as in Java, but its class
        // file is `Map$Entry`. Only the binary name identifies the class, so the symbol carries
        // that one and the written spelling becomes an alias for it: descriptors reached the
        // same class through `$` long before source could name it, and both must arrive at one
        // symbol or a member call would type-check against a different one.
        DiscoveredClass discovered = discoverClass(qualifiedName);
        if (discovered == null) {
            unresolvableTypes.add(qualifiedName);
            return null;
        }
        String binaryName = discovered.binaryName();
        NamedTypeSymbol nested = resolvedTypes.get(binaryName);
        if (nested != null) {
            resolvedTypes.put(qualifiedName, nested);
            return nested;
        }
        ClassModel classModel = discovered.model();
        boolean isInterface = classModel.flags().has(AccessFlag.INTERFACE);
        List<Signature.TypeParam> genericParameters = classTypeParameters(classModel);
        NamedTypeSymbol typeSymbol = new NamedTypeSymbol(
                simpleNameOf(binaryName),
                binaryName,
                JAVA_MODULE_LOCATION,
                isInterface ? NamedTypeSymbol.DeclaredKind.INTERFACE
                        : NamedTypeSymbol.DeclaredKind.CLASS,
                genericParameters.size(),
                supertypeClosure(binaryName),
                keywordSubtypes(binaryName));
        // Publish before any member work so a self-referential descriptor cannot recurse.
        resolvedTypes.put(binaryName, typeSymbol);
        resolvedTypes.put(qualifiedName, typeSymbol);
        List<TypeParameterSymbol> typeParameters = new ArrayList<>(genericParameters.size());
        for (int ordinal = 0; ordinal < genericParameters.size(); ordinal++) {
            String name = genericParameters.get(ordinal).identifier();
            typeParameters.add(new TypeParameterSymbol(name, binaryName + "." + name,
                    JAVA_MODULE_LOCATION, ordinal,
                    boundErasure(genericParameters.get(ordinal))));
        }
        resolvedTypeParameters.put(typeSymbol, List.copyOf(typeParameters));
        return typeSymbol;
    }



    /// The one interface `foreach` enumerates through: every JDK collection reaches it.
    private static final String ITERABLE = "java.lang.Iterable";

    /// Every package name the module path publishes, together with every enclosing name that
    /// implies: `java.util.stream` also records `java` and `java.util`, because C#
    /// namespaces are hierarchical and `using java.util;` must stay legal even though no class
    /// is declared directly in it. Read once - the boot layer cannot grow during a run.
    private static final Set<String> MODULE_PATH_NAMESPACES = modulePathNamespaces();

    /// The loader every Java class is read through: the compiler's own.
    ///
    /// Naming a package is not enough - the class must also load, and a system module the
    /// compiler's boot layer never resolved cannot be read at all. A child `ModuleLayer` is no
    /// escape: the JVM prohibits both a user-defined loader and the platform loader from
    /// defining a `java.*` package, measured. The resolution therefore has to
    /// exist in the *launch*, which is why every process that runs this compiler is started with
    /// `--add-modules ALL-SYSTEM`; [#unresolvedSystemModule] turns the remaining case - an
    /// embedder that did not - into an explicit diagnostic instead of "type not found".
    private static final ClassLoader SYSTEM_LOADER = JavaInterop.class.getClassLoader();

    /// The system module publishing `packageName` when the boot layer did not resolve it, or
    /// `null` when the package is either resolved or not a JDK package at all. A name that
    /// exists in the installed image but cannot be loaded is an environment error, and saying
    /// so is the difference between a five-second fix and a hunt for a missing type.
    public static String unresolvedSystemModule(String packageName) {
        Objects.requireNonNull(packageName, "packageName");
        for (Module module : ModuleLayer.boot().modules()) {
            if (module.getPackages().contains(packageName)) {
                return null;
            }
        }
        for (ModuleReference reference : ModuleFinder.ofSystem().findAll()) {
            if (reference.descriptor().packages().contains(packageName)) {
                return reference.descriptor().name();
            }
        }
        return null;
    }


    /// Every package the *installed* JDK publishes, which is not the same set as the compiler's
    /// own boot layer.
    ///
    /// V# is compiled as a named module, so its boot layer resolves only what the compiler
    /// itself requires - `java.base` and little else. Deriving the visible JDK from that layer
    /// made `java.net.http`, `java.sql`, `java.xml` and every other system module that the
    /// compiler happens not to use invisible to user programs, reported as CS0246 for a type
    /// that plainly exists. The installed image is the authority instead, read once through
    /// [ModuleFinder#ofSystem], so what a V# program may name is decided by the JDK it runs on
    /// rather than by the compiler's own dependency graph.
    private static Set<String> modulePathNamespaces() {
        Set<String> namespaces = new HashSet<>();
        for (ModuleReference reference : ModuleFinder.ofSystem().findAll()) {
            for (String packageName : reference.descriptor().packages()) {
                String enclosing = packageName;
                while (namespaces.add(enclosing)) {
                    int lastDot = enclosing.lastIndexOf('.');
                    if (lastDot < 0) {
                        break;
                    }
                    enclosing = enclosing.substring(0, lastDot);
                }
            }
        }
        return Set.copyOf(namespaces);
    }

    /// Whether `name` is a package the module path publishes, or encloses one. A `using`
    /// answering `false` here and naming no declared V# namespace is the C# CS0246 case: the
    /// import can never contribute a type, so it is a source error rather than a silent no-op.
    public static boolean isModulePathNamespace(String name) {
        Objects.requireNonNull(name, "name");
        return MODULE_PATH_NAMESPACES.contains(name);
    }

    /// Whether `name` is a package this compilation can read a class from - the boot layer plus
    /// every package the declared classpath publishes. The boot layer answers for the JDK;
    /// a jar contributes only when the user named it, so an undeclared dependency still reports
    /// CS0246 rather than binding against whatever happened to sit on the compiler's own path.
    public boolean isKnownNamespace(String name) {
        Objects.requireNonNull(name, "name");
        return MODULE_PATH_NAMESPACES.contains(name) || classpathNamespaces.contains(name);
    }

    /// Collects the packages the declared classpath publishes, with every enclosing name, so
    /// `using org.springframework.boot;` resolves exactly as a module-path package does. A
    /// directory entry is walked; a jar is read through its entries. An unreadable entry is a
    /// user-facing error at the point it was declared, never a silent empty namespace set.
    private static Set<String> classpathNamespaces(List<Path> classpath) {
        Set<String> namespaces = new HashSet<>();
        for (Path entry : classpath) {
            for (String packageName : packagesOf(entry)) {
                String enclosing = packageName;
                while (namespaces.add(enclosing)) {
                    int lastDot = enclosing.lastIndexOf('.');
                    if (lastDot < 0) {
                        break;
                    }
                    enclosing = enclosing.substring(0, lastDot);
                }
            }
        }
        return Set.copyOf(namespaces);
    }

    /// The package names a single classpath entry carries. Class files are the only evidence a
    /// package exists: a jar's directory entries are optional and a source tree's empty folders
    /// publish nothing, so both shapes are read from `.class` paths alone.
    private static Set<String> packagesOf(Path entry) {
        Set<String> packages = new HashSet<>();
        if (Files.isDirectory(entry)) {
            try (Stream<Path> walk = Files.walk(entry)) {
                walk.filter(path -> path.getFileName().toString().endsWith(".class"))
                        .map(path -> entry.relativize(path).getParent())
                        .filter(parent -> parent != null)
                        .map(parent -> parent.toString().replace(parent.getFileSystem().getSeparator(), "."))
                        .forEach(packages::add);
            } catch (IOException e) {
                throw new IllegalStateException("Unreadable classpath directory: " + entry, e);
            }
            return packages;
        }
        try (JarFile jar = new JarFile(entry.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (!name.endsWith(".class")) {
                    continue;
                }
                int lastSlash = name.lastIndexOf('/');
                if (lastSlash > 0) {
                    packages.add(name.substring(0, lastSlash).replace('/', '.'));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unreadable classpath entry: " + entry, e);
        }
        return packages;
    }

    /// The JVM class each keyword type is. `object` needs no entry: the conversion engine
    /// already converts every reference to and from it, and a written `java.lang.Object` is
    /// resolved to the keyword itself.
    private static final Map<BuiltinType, String> KEYWORD_CLASSES = Map.ofEntries(
            Map.entry(BuiltinType.STRING, "java.lang.String"),
            // The boxed carrier of each keyword *value* type, so the same closure answers
            // `int` against `java.lang.Comparable` that already answers `string` against
            // `java.lang.CharSequence`. C# admits the corresponding conversion as a
            // boxing conversion, and so does V#: the kind is decided in `Conversions`, not
            // here, because this table only says which class carries the keyword.
            //
            // The unsigned keywords are deliberately absent. `uint` and its siblings box
            // through `VsUnsigned` into the same `Integer`/`Long` carriers as their signed
            // counterparts, so the closure would claim `uint` is `Comparable` and the
            // comparison it reached would be the *signed* one - a wrong answer rather than a
            // missing feature. `decimal`, `nint` and `nuint` are absent for the same reason:
            // their carrier's interfaces do not mean what the keyword means.
            Map.entry(BuiltinType.SBYTE, "java.lang.Byte"),
            Map.entry(BuiltinType.SHORT, "java.lang.Short"),
            Map.entry(BuiltinType.INT, "java.lang.Integer"),
            Map.entry(BuiltinType.LONG, "java.lang.Long"),
            Map.entry(BuiltinType.FLOAT, "java.lang.Float"),
            Map.entry(BuiltinType.DOUBLE, "java.lang.Double"),
            Map.entry(BuiltinType.BOOL, "java.lang.Boolean"),
            Map.entry(BuiltinType.CHAR, "java.lang.Character"));

    /// The keyword types assignable to `qualifiedName`, which is how a conversion from a
    /// keyword type to a Java interface is answered without a resolver. `string` is
    /// assignable to `java.lang.CharSequence` for exactly the reason `java.util.ArrayList` is
    /// assignable to `java.util.List`: the class file says so.
    /// Whether a keyword type has a boxed carrier whose hierarchy V# can read.
    ///
    /// The unsigned keywords and the carriers whose interfaces do not mean what the keyword
    /// means are absent from the table, so a question about them is *unanswerable* rather than
    /// answered "no" - a distinction a constraint check has to respect or it refuses programs
    /// C# accepts.
    public static boolean hasKeywordCarrier(BuiltinType keyword) {
        return KEYWORD_CLASSES.containsKey(keyword);
    }

    private Set<BuiltinType> keywordSubtypes(String qualifiedName) {
        Set<BuiltinType> subtypes = EnumSet.noneOf(BuiltinType.class);
        for (Map.Entry<BuiltinType, String> keyword : KEYWORD_CLASSES.entrySet()) {
            if (keyword.getValue().equals(qualifiedName)
                    || supertypeClosure(keyword.getValue()).contains(qualifiedName)) {
                subtypes.add(keyword.getKey());
            }
        }
        return subtypes;
    }

    /// Every type `qualifiedName` is assignable to, by qualified name: its superclasses, the
    /// interfaces it and they implement, and the superinterfaces of those, transitively
    ///. `java.lang.Object` is included for every class, and for every interface too,
    /// because a JVM interface reference is assignable to `Object`.
    ///
    /// The closure is read from class files rather than from resolved symbols, so it neither
    /// recurses into member loading nor forces a symbol to exist for a supertype the program
    /// never names. It is memoised per name and shared by every type that inherits it, which
    /// is what keeps the walk proportional to the hierarchy a compilation actually mentions.
    private Set<String> supertypeClosure(String qualifiedName) {
        Set<String> cached = supertypeClosures.get(qualifiedName);
        if (cached != null) {
            return cached;
        }
        Set<String> closure = new LinkedHashSet<>();
        Deque<String> pending = new ArrayDeque<>();
        pending.add(qualifiedName);
        Set<String> seen = new HashSet<>();
        seen.add(qualifiedName);
        while (!pending.isEmpty()) {
            ClassModel model = readClassFile(pending.removeFirst());
            if (model == null) {
                continue;
            }
            List<String> direct = new ArrayList<>();
            model.superclass().map(entry -> entry.asInternalName().replace('/', '.'))
                    .ifPresent(direct::add);
            for (var entry : model.interfaces()) {
                direct.add(entry.asInternalName().replace('/', '.'));
            }
            for (String supertype : direct) {
                closure.add(supertype);
                if (seen.add(supertype)) {
                    pending.add(supertype);
                }
            }
        }
        // An interface's class file names no superclass, but every reference to one is still
        // assignable to `Object`.
        closure.add("java.lang.Object");
        closure.remove(qualifiedName);
        Set<String> result = Set.copyOf(closure);
        supertypeClosures.put(qualifiedName, result);
        return result;
    }

    /// The members of a previously resolved type, in class-file declaration order. Members
    /// are loaded on first request so that merely naming a type does not transitively load
    /// the closure of every type its signatures mention.
    public List<Symbol> getMembers(NamedTypeSymbol typeSymbol) {
        Objects.requireNonNull(typeSymbol, "typeSymbol");
        List<Symbol> cached = resolvedMembers.get(typeSymbol);
        if (cached != null) {
            return cached;
        }
        ClassModel classModel = readClassFile(typeSymbol.qualifiedName());
        List<Symbol> members = classModel == null ? List.of() : loadMembers(typeSymbol, classModel);
        resolvedMembers.put(typeSymbol, members);
        return members;
    }

    /// The declared type parameters read from a Java class signature, in ordinal order.
    /// Each carries its bound's erasure, so a member position typed by the parameter
    /// names the carrier the JVM declares rather than an assumed `Object`.
    public List<TypeParameterSymbol> getTypeParameters(NamedTypeSymbol typeSymbol) {
        Objects.requireNonNull(typeSymbol, "typeSymbol");
        return resolvedTypeParameters.getOrDefault(typeSymbol, List.of());
    }

    /// Applies a constructed receiver's class arguments to members while retaining a link to
    /// each erased JVM declaration. A raw generic receiver substitutes `object`, preserving the
    /// pre-signature behavior for existing V# source; a constructed receiver substitutes its
    /// exact arguments. Method-owned type parameters remain for ordinary inference.
    public List<Symbol> specializeMembers(TypeSymbol receiverType, List<Symbol> members) {
        Objects.requireNonNull(receiverType, "receiverType");
        Objects.requireNonNull(members, "members");
        NamedTypeSymbol definition;
        List<TypeSymbol> arguments;
        if (receiverType instanceof TypeSymbol.Constructed constructed) {
            definition = constructed.definition();
            arguments = constructed.arguments();
        } else if (receiverType instanceof NamedTypeSymbol named && named.arity() > 0) {
            // A raw Java type erases each variable to its *bound*, which is what the class file
            // already declares and what a lambda over its method must match: the SAM of
            // `ApplicationContextInitializer<C extends ConfigurableApplicationContext>` takes a
            // `ConfigurableApplicationContext`, and substituting `object` produced an
            // implementation method the LambdaMetafactory rejected at the first call with
            // `Type mismatch for dynamic parameter 0`. An unbounded variable bounds by
            // `object`, so the common case is unchanged.
            definition = named;
            arguments = getTypeParameters(named).stream()
                    .map(TypeParameterSymbol::bound)
                    .map(TypeSymbol.class::cast)
                    .toList();
        } else {
            return members;
        }
        List<TypeParameterSymbol> parameters = getTypeParameters(definition);
        if (parameters.size() != arguments.size()) {
            return members;
        }
        Map<TypeParameterSymbol, TypeSymbol> substitutions = new HashMap<>();
        for (int index = 0; index < parameters.size(); index++) {
            substitutions.put(parameters.get(index), arguments.get(index));
        }

        List<Symbol> specialized = new ArrayList<>(members.size());
        for (Symbol member : members) {
            if (!(member instanceof FunctionSymbol function)) {
                // Generic public fields retain their erased descriptor for now. Specializing
                // their semantic type without carrying the erased declaration separately would
                // make getfield/putfield reference a field descriptor the JVM does not declare.
                specialized.add(member);
                continue;
            }
            TypeSymbol returnType = substitute(function.returnType(), substitutions);
            List<ParameterSymbol> functionParameters = function.parameters().stream()
                    .map(parameter -> new ParameterSymbol(parameter.name(),
                            parameter.qualifiedName(), parameter.location(),
                            substitute(parameter.type(), substitutions), parameter.ordinal(),
                            parameter.modifiers(), parameter.defaultValue()))
                    .toList();
            if (returnType.equals(function.returnType())
                    && functionParameters.equals(function.parameters())) {
                specialized.add(function);
                continue;
            }
            FunctionSymbol specialization = new FunctionSymbol(function.name(),
                    function.qualifiedName(), function.location(), returnType,
                    function.typeParameters(), functionParameters, function.modifiers(),
                    function.localFunction(), function.synthesized(), function.interfaceOwner());
            erasedDeclarations.put(specialization, erasedDeclaration(function));
            specialized.add(specialization);
        }
        return List.copyOf(specialized);
    }

    /// The element type one `foreach` step over `collection` produces, or empty when the type
    /// is not enumerable and the binder must report CS1579 instead of guessing an element.
    ///
    /// Three carriers are enumerable, and every one of them is a shape V# already has:
    /// an array yields its indexed element (`int[][]` yields `int[]`, `int[,]` its leaf, which
    /// is exactly `Array.elementType`); `string` yields `char`, matching C#'s indexer-based
    /// enumeration without needing `CharSequence`; and any Java type reaching
    /// `java.lang.Iterable` yields that interface's `T` *as viewed through the receiver*, so
    /// `ArrayList<string>` yields `string` and a raw `ArrayList` yields `object`.
    ///
    /// C#'s pattern-based `GetEnumerator` lookup is deliberately not implemented: V# declares
    /// no user types with members, so the only enumerables that can exist are these three, and
    /// `Iterable` is what the JDK actually publishes.
    public Optional<TypeSymbol> foreachElementType(TypeSymbol collection) {
        Objects.requireNonNull(collection, "collection");
        if (collection instanceof TypeSymbol.Array array) {
            return Optional.of(array.elementType());
        }
        if (collection == BuiltinType.STRING) {
            return Optional.of(BuiltinType.CHAR);
        }
        NamedTypeSymbol definition;
        List<TypeSymbol> arguments;
        if (collection instanceof TypeSymbol.Constructed constructed) {
            definition = constructed.definition();
            arguments = constructed.arguments();
        } else if (collection instanceof NamedTypeSymbol named) {
            definition = named;
            arguments = java.util.Collections.nCopies(named.arity(), BuiltinType.OBJECT);
        } else {
            return Optional.empty();
        }
        if (!isIterable(definition)) {
            return Optional.empty();
        }
        Map<String, TypeSymbol> iterableEnvironment =
                genericSupertypeEnvironments(definition).get(ITERABLE);
        TypeSymbol element = iterableEnvironment == null || iterableEnvironment.isEmpty()
                ? BuiltinType.OBJECT
                : iterableEnvironment.values().iterator().next();
        List<TypeParameterSymbol> parameters = getTypeParameters(definition);
        if (parameters.size() != arguments.size()) {
            return Optional.of(BuiltinType.OBJECT);
        }
        Map<TypeParameterSymbol, TypeSymbol> substitutions = new HashMap<>();
        for (int index = 0; index < parameters.size(); index++) {
            substitutions.put(parameters.get(index), arguments.get(index));
        }
        TypeSymbol substituted = substitute(element, substitutions);
        // An unsubstituted variable is a raw or unbound use: its erasure is what the iterator
        // actually returns, so `object` is the honest source type rather than a dangling `T`.
        return Optional.of(substituted instanceof TypeParameterSymbol
                ? BuiltinType.OBJECT : substituted);
    }

    /// Whether a Java type is enumerable by `foreach`: `java.lang.Iterable` itself, or any
    /// type whose supertype closure reaches it.
    public boolean isIterable(NamedTypeSymbol typeSymbol) {
        Objects.requireNonNull(typeSymbol, "typeSymbol");
        if (!isJavaType(typeSymbol)) {
            return false;
        }
        return ITERABLE.equals(typeSymbol.qualifiedName())
                || supertypeClosure(typeSymbol.qualifiedName()).contains(ITERABLE);
    }

    /// The single abstract method of a Java functional interface, substituted for the
    /// arguments of `typeSymbol`, or empty when the type is not one.
    ///
    /// This is JLS 9.8 read from class files rather than from `@FunctionalInterface`, which is
    /// documentation and not a requirement: `Comparator` carries it and `Iterable` does not,
    /// yet both are decided by the same rule. A method is abstract for this purpose when no
    /// interface in the closure supplies a default for it and it does not redeclare a public
    /// instance method of `java.lang.Object` - `Comparator` redeclares `equals(Object)` for
    /// exactly that reason, so ignoring the exclusion would make it a two-method interface and
    /// refuse every comparator lambda a V# program writes.
    public Optional<FunctionSymbol> functionalMethod(NamedTypeSymbol typeSymbol) {
        Objects.requireNonNull(typeSymbol, "typeSymbol");
        if (typeSymbol.declaredKind() != NamedTypeSymbol.DeclaredKind.INTERFACE
                || !isJavaType(typeSymbol)) {
            return Optional.empty();
        }
        return functionalKey(typeSymbol).resolve(this, typeSymbol);
    }

    /// Whether a resolved Java interface has exactly one abstract method.
    ///
    /// Deliberately cheaper than [#functionalMethod]: it reads class files only and never
    /// loads members, so signature mapping can consult it while it is itself in the middle of
    /// loading the members of some other type.
    private boolean isFunctionalInterface(NamedTypeSymbol typeSymbol) {
        return typeSymbol.declaredKind() == NamedTypeSymbol.DeclaredKind.INTERFACE
                && isJavaType(typeSymbol)
                && functionalKey(typeSymbol).arity() >= 0;
    }

    private FunctionalKey functionalKey(NamedTypeSymbol typeSymbol) {
        FunctionalKey cached = functionalMethods.get(typeSymbol);
        if (cached != null) {
            return cached;
        }
        Set<String> abstractKeys = new LinkedHashSet<>();
        Set<String> implemented = new HashSet<>();
        List<String> hierarchy = new ArrayList<>();
        hierarchy.add(typeSymbol.qualifiedName());
        hierarchy.addAll(supertypeClosure(typeSymbol.qualifiedName()));
        for (String name : hierarchy) {
            ClassModel model = readClassFile(name);
            if (model == null || !model.flags().has(AccessFlag.INTERFACE)) {
                continue;
            }
            for (MethodModel method : model.methods()) {
                String methodName = method.methodName().stringValue();
                if (method.flags().has(AccessFlag.STATIC)
                        || method.flags().has(AccessFlag.PRIVATE)
                        || methodName.startsWith("<")) {
                    continue;
                }
                int arity = method.methodTypeSymbol().parameterCount();
                String key = methodName + "/" + arity;
                if (method.flags().has(AccessFlag.ABSTRACT)) {
                    if (!isObjectPublicMethod(methodName, arity)) {
                        abstractKeys.add(key);
                    }
                } else {
                    implemented.add(key);
                }
            }
        }
        abstractKeys.removeAll(implemented);
        if (abstractKeys.size() != 1) {
            functionalMethods.put(typeSymbol, FunctionalKey.NONE);
            return FunctionalKey.NONE;
        }
        String key = abstractKeys.iterator().next();
        String jvmName = key.substring(0, key.lastIndexOf('/'));
        int arity = Integer.parseInt(key.substring(key.lastIndexOf('/') + 1));
        FunctionalKey resolved = new FunctionalKey(jvmName, arity);
        functionalMethods.put(typeSymbol, resolved);
        return resolved;
    }

    /// The single abstract method of `type`, with the constructed receiver's own arguments
    /// substituted into it, or empty when `type` is not a Java functional interface.
    ///
    /// A lambda is converted against the *instantiated* signature - `Comparator<string>.compare`
    /// takes two `string`s, not two `object`s - while the erased declaration stays reachable
    /// through [#erasedDeclaration] for the JVM descriptor the call site must name.
    public Optional<FunctionSymbol> functionalMethodOf(TypeSymbol type) {
        Objects.requireNonNull(type, "type");
        NamedTypeSymbol definition = switch (type) {
            case TypeSymbol.Constructed constructed -> constructed.definition();
            case NamedTypeSymbol named -> named;
            default -> null;
        };
        if (definition == null) {
            return Optional.empty();
        }
        // JLS 9.9: a lambda's parameters are typed from the target's *non-wildcard*
        // parameterization, so the projection happens here, where a lambda is being typed,
        // rather than in the type itself. `Comparator<? super string>` gives a lambda
        // two `string` parameters; the type it was read from stays variant for everyone else.
        return functionalMethod(definition)
                .map(method -> (FunctionSymbol) specializeMembers(
                        nonWildcardParameterization(type), List.of(method)).getFirst());
    }

    /// JLS 9.9's non-wildcard parameterization: every wildcard argument replaced by its bound
    ///. `Comparator<? super T>` becomes `Comparator<T>`, which is the parameterization a
    /// lambda's parameters are typed from; a type carrying no wildcard is returned unchanged.
    private static TypeSymbol nonWildcardParameterization(TypeSymbol type) {
        if (!(type instanceof TypeSymbol.Constructed constructed)) {
            return type;
        }
        List<TypeSymbol> projected = new ArrayList<>(constructed.arguments().size());
        boolean changed = false;
        for (TypeSymbol argument : constructed.arguments()) {
            if (argument instanceof TypeSymbol.Wildcard wildcard) {
                projected.add(wildcard.bound());
                changed = true;
                continue;
            }
            projected.add(argument);
        }
        return changed ? new TypeSymbol.Constructed(constructed.definition(), projected) : type;
    }

    /// The name and arity of a functional interface's abstract method, memoised per type so
    /// that the class-file walk runs once. The member itself is looked up on demand because
    /// it carries the substituted signature the binder needs.
    private record FunctionalKey(String jvmName, int arity) {

        private static final FunctionalKey NONE = new FunctionalKey("", -1);

        private Optional<FunctionSymbol> resolve(JavaInterop interop, NamedTypeSymbol owner) {
            if (arity < 0) {
                return Optional.empty();
            }
            // Members discovered from a class file keep their JVM spelling; the PascalCase
            // translation happens at the source-name side, not here.
            for (Symbol member : interop.getMembers(owner)) {
                if (member instanceof FunctionSymbol function
                        && function.name().equals(jvmName)
                        && function.parameters().size() == arity
                        && !function.modifiers().contains(SyntaxKind.STATIC)) {
                    return Optional.of(function);
                }
            }
            return Optional.empty();
        }
    }

    /// Whether a name and arity redeclare a public, non-final instance method of
    /// `java.lang.Object`. Only these three can appear as abstract interface methods, and JLS
    /// 9.8 discounts every one of them when counting a functional interface's methods.
    private static boolean isObjectPublicMethod(String name, int arity) {
        return switch (name) {
            case "equals" -> arity == 1;
            case "hashCode", "toString" -> arity == 0;
            default -> false;
        };
    }

    /// The descriptor-exact JVM declaration behind a signature-mapped function, or the input
    /// itself when it did not come from Java discovery. The backend builds the invocation from
    /// this declaration and uses the specialization only for source typing.
    public FunctionSymbol erasedDeclaration(FunctionSymbol function) {
        Objects.requireNonNull(function, "function");
        return erasedDeclarations.getOrDefault(function, function);
    }

    /// Whether a symbol came from the Java module path rather than V# source/corelib.
    /// Overload resolution uses this only to preserve Java's legal raw invocation fallback
    /// when a generic signature cannot infer through an erased raw argument.
    public static boolean isModulePathSymbol(Symbol symbol) {
        Objects.requireNonNull(symbol, "symbol");
        return symbol.location() == JAVA_MODULE_LOCATION;
    }

    /// Whether a source type denotes a JVM class discovered from the Java module path.
    ///
    /// Constructed Java types erase to their definition, and an array of a Java type still has
    /// a descriptor-exact JVM class literal. Keeping this query here gives binding and emission
    /// one authority for the the design `typeof` boundary without classifying V# declarations by name.
    /// Whether `typeof` over `type` names a real JVM class rather than a V# type identity.
    ///
    /// `VsType` is deliberate, not legacy: a JVM class cannot tell every V# type apart. `int` and
    /// `uint` share the `int` carrier, `sbyte` and `byte` share `byte`, `long` and `ulong` share
    /// `long`, and `object` shares `java.lang.Object` with `dynamic` - so a class literal would
    /// equate types C# keeps distinct, which `CarrierDistinctions` pins on purpose.
    ///
    /// `string` is the one keyword with no such ambiguity: it *is* `java.lang.String` and
    /// nothing else is carried by it, so naming the real class costs no identity. Both spellings
    /// qualify, because `typeof(string)` and `typeof(System.String)` are one type in C# and must
    /// remain one token here - `AliasIdentity` pins that. What it buys is every JDK API that
    /// takes a `Class`: `BodyToMono(typeof(string))` over a Spring request body is unwritable
    /// otherwise.
    public static boolean hasClassLiteral(TypeSymbol type) {
        return isModulePathType(type)
                || type == BuiltinType.STRING
                || type instanceof NamedTypeSymbol named
                        && named.qualifiedName().equals("System.String");
    }

    public static boolean isModulePathType(TypeSymbol type) {
        Objects.requireNonNull(type, "type");
        return switch (type) {
            case NamedTypeSymbol named -> isModulePathSymbol(named);
            case TypeSymbol.Constructed constructed -> isModulePathSymbol(
                    constructed.definition());
            case TypeSymbol.Array array -> isModulePathType(array.element());
            default -> false;
        };
    }

    /// Whether `typeSymbol` is the canonical symbol this resolver created for a Java class.
    /// Identity matters: a V# declaration can use the same qualified spelling but must never
    /// become constructible merely because a module-path class has that name.
    public boolean isJavaType(NamedTypeSymbol typeSymbol) {
        Objects.requireNonNull(typeSymbol, "typeSymbol");
        return resolvedTypes.get(typeSymbol.qualifiedName()) == typeSymbol;
    }

    /// The JDK spelling a V# member name denotes, or `null` when the V# spelling is already
    /// the JDK one and no translation exists.
    ///
    /// V# writes every member in PascalCase, including members it borrows from Java, whose
    /// convention is camelCase. The two conventions differ in exactly one character, so the
    /// translation is the first character's case and nothing else: `Append` names `append`
    /// and `ToURI` names `toURI`, while an acronym's remaining letters are never touched.
    /// Members the JDK does not spell in camelCase have no translated form and are matched by
    /// their own spelling, which is why callers try the written name first and this fallback
    /// second. Constant-style names are excluded outright: a name of two or more characters
    /// holding no lowercase letter - `MAX_VALUE`, `PI` - is not a PascalCase member, and
    /// folding its first character would only manufacture a spelling nothing declares. A
    /// single character carries no such evidence, so `X` still names `java.awt.Point.x`.
    public static String jvmMemberName(String vsharpName) {
        Objects.requireNonNull(vsharpName, "vsharpName");
        if (vsharpName.isEmpty() || !Character.isUpperCase(vsharpName.charAt(0))) {
            return null;
        }
        if (vsharpName.length() > 1 && vsharpName.chars().noneMatch(Character::isLowerCase)) {
            return null;
        }
        return Character.toLowerCase(vsharpName.charAt(0)) + vsharpName.substring(1);
    }

    /// The V# spelling of a JDK member name: the inverse of [#jvmMemberName], used to name
    /// the required spelling in a diagnostic when source wrote the JDK's own camelCase.
    public static String vsharpMemberName(String jvmName) {
        Objects.requireNonNull(jvmName, "jvmName");
        if (jvmName.isEmpty() || !Character.isLowerCase(jvmName.charAt(0))) {
            return null;
        }
        return Character.toUpperCase(jvmName.charAt(0)) + jvmName.substring(1);
    }

    /// Public constructors in class-file declaration order. Constructors stay separate from
    /// ordinary members because they cannot be selected by member access; object-creation
    /// binding is their only consumer.
    public List<FunctionSymbol> getConstructors(NamedTypeSymbol typeSymbol) {
        Objects.requireNonNull(typeSymbol, "typeSymbol");
        if (!isJavaType(typeSymbol)) {
            return List.of();
        }
        List<FunctionSymbol> cached = resolvedConstructors.get(typeSymbol);
        if (cached != null) {
            return cached;
        }
        ClassModel classModel = readClassFile(typeSymbol.qualifiedName());
        List<FunctionSymbol> constructors = classModel == null
                ? List.of() : loadConstructors(typeSymbol, classModel);
        resolvedConstructors.put(typeSymbol, constructors);
        return constructors;
    }

    /// Whether the discovered Java type is `java.lang.Throwable` or extends it. This narrow
    /// hierarchy query protects `athrow` and exception-table catch types without exposing
    /// inherited members or otherwise widening the interop surface.
    public boolean isThrowable(NamedTypeSymbol typeSymbol) {
        Objects.requireNonNull(typeSymbol, "typeSymbol");
        if (!isJavaType(typeSymbol)) {
            return false;
        }
        return throwableTypes.computeIfAbsent(typeSymbol,
                type -> hasSuperclass(type.qualifiedName(), "java.lang.Throwable",
                        new HashSet<>()));
    }

    private boolean hasSuperclass(String typeName, String expected,
            Set<String> visited) {
        if (typeName.equals(expected)) {
            return true;
        }
        if (!visited.add(typeName)) {
            throw new IllegalStateException("Cyclic Java superclass chain at " + typeName);
        }
        ClassModel classModel = readClassFile(typeName);
        return classModel != null && classModel.superclass()
                .map(entry -> hasSuperclass(entry.asInternalName().replace('/', '.'), expected,
                        visited))
                .orElse(false);
    }

    /// Reads the class file backing `qualifiedName` from the system class loader, which
    /// exposes both the runtime image and the compilation class path.
    ///
    /// A name that resolves to no resource, or to bytes that are not a valid class file, is
    /// reported as absent. Nothing else is caught: an error reading a resource that does
    /// exist is a real environment failure and must not be silently turned into "no such
    /// type", which would surface later as a misleading name-resolution diagnostic.
    /// A class file found on the module path together with the binary name that found it.
    private record DiscoveredClass(String binaryName, ClassModel model) {

        DiscoveredClass {
            Objects.requireNonNull(binaryName, "binaryName");
            Objects.requireNonNull(model, "model");
        }
    }

    /// Finds the class a written type name denotes, or `null` when the module path has none.
    ///
    /// V# writes a nested type the way Java source does - `Map.Entry` - while the class file
    /// is `Map$Entry`. Package and enclosing-type separators are the same character in
    /// source and different characters in the binary name, and nothing in the name says which
    /// is which, so the splits are tried right to left: the written name first, then one
    /// enclosing level at a time. Right to left is the order that matters, because a package
    /// and a type may share a spelling and the deeper nesting is the more specific answer.
    private DiscoveredClass discoverClass(String qualifiedName) {
        ClassModel model = readClassFile(qualifiedName);
        if (model != null) {
            return new DiscoveredClass(qualifiedName, model);
        }
        for (int dot = qualifiedName.lastIndexOf('.'); dot > 0;
                dot = qualifiedName.lastIndexOf('.', dot - 1)) {
            String candidate = qualifiedName.substring(0, dot) + "$"
                    + qualifiedName.substring(dot + 1).replace('.', '$');
            ClassModel nested = readClassFile(candidate);
            if (nested != null) {
                return new DiscoveredClass(candidate, nested);
            }
        }
        return null;
    }

    private ClassModel readClassFile(String qualifiedName) {
        if (qualifiedName.isEmpty() || qualifiedName.startsWith(".")
                || qualifiedName.endsWith(".") || qualifiedName.indexOf('/') >= 0) {
            return null;
        }
        return ClassFileCache.get(classFileSignature, qualifiedName,
                () -> loadClassFile(qualifiedName));
    }

    /// Reads and parses one class file, with no caching.
    private ClassModel loadClassFile(String qualifiedName) {
        String resource = qualifiedName.replace('.', '/') + ".class";
        try (InputStream in = classLoader.getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            return ClassFile.of().parse(in.readAllBytes());
        } catch (IOException e) {
            throw new IllegalStateException("Unreadable class file on the module path: "
                    + qualifiedName, e);
        } catch (IllegalArgumentException e) {
            // Not a class file, or a version this JDK cannot parse: treat as undiscoverable.
            return null;
        }
    }

    /// Collects every member reachable through a reference of `typeSymbol`, derived before
    /// base and class-chain members before the interface closure.
    ///
    /// Deduplication mirrors the JVM's own rules rather than approximating them: a field
    /// hides any same-named superclass field whatever its descriptor, and a method overrides
    /// or hides any superclass method with the same name and parameter descriptors. Return
    /// type is excluded from the method key on purpose, because a covariant override and the
    /// base declaration it overrides differ only there and must not both be exposed as
    /// overloads of one another.
    ///
    /// A superclass that cannot be read stops the walk instead of failing the compilation:
    /// the members already collected stay valid, which is the same partial-discovery
    /// contract [#resolveType] applies to a missing type.
    ///
    /// The superclass chain comes first and contributes both instance and static members,
    /// because a class inherits both. The interface closure follows and contributes instance
    /// members only: a static interface method is not inherited (JLS 8.4.8), while a default
    /// or abstract one is invocable through any reference that has it. An interface finally
    /// picks up `java.lang.Object`'s public methods, which JLS 9.2 makes members of every
    /// interface type and which the JVM resolves for an `invokeinterface` (JVMS 5.4.3.4).
    ///
    /// Each inherited member is still named after the type that inherits it, so the emitted
    /// reference is one the source could name: javac emits `java/util/ArrayList.stream` for a
    /// method `ArrayList` gains from `Collection`, and `java/util/Set.toString` for one `Set`
    /// gains from `Object`.
    private List<Symbol> loadMembers(NamedTypeSymbol typeSymbol, ClassModel classModel) {
        List<Symbol> members = new ArrayList<>();
        Set<String> hiddenFields = new HashSet<>();
        Set<String> overriddenMethods = new HashSet<>();
        Set<String> semanticMethods = new HashSet<>();
        Set<String> visited = new HashSet<>();
        String owner = typeSymbol.qualifiedName();
        Map<String, Map<String, TypeSymbol>> typeEnvironments =
                genericSupertypeEnvironments(typeSymbol);
        boolean isInterface = typeSymbol.declaredKind() == NamedTypeSymbol.DeclaredKind.INTERFACE;
        visited.add(owner);
        ClassModel current = classModel;
        String currentName = owner;
        while (current != null) {
            loadDeclaredMembers(owner, current, members, hiddenFields, overriddenMethods,
                    semanticMethods, isInterface, true,
                    typeEnvironments.getOrDefault(currentName, Map.of()));
            String superclass = isInterface ? null : current.superclass()
                    .map(entry -> entry.asInternalName().replace('/', '.'))
                    .orElse(null);
            if (superclass == null) {
                break;
            }
            if (!visited.add(superclass)) {
                throw new IllegalStateException("Cyclic Java superclass chain at " + superclass);
            }
            currentName = superclass;
            current = readClassFile(superclass);
        }

        for (String supertype : supertypeClosure(owner)) {
            if (!visited.add(supertype)) {
                continue;
            }
            ClassModel model = readClassFile(supertype);
            if (model == null || !model.flags().has(AccessFlag.INTERFACE)) {
                continue;
            }
            loadDeclaredMembers(owner, model, members, hiddenFields, overriddenMethods,
                    semanticMethods, isInterface, false,
                    typeEnvironments.getOrDefault(supertype, Map.of()));
        }
        if (isInterface) {
            ClassModel object = readClassFile("java.lang.Object");
            if (object != null) {
                loadDeclaredMembers(owner, object, members, hiddenFields, overriddenMethods,
                        semanticMethods, true, false, Map.of());
            }
        }
        return List.copyOf(members);
    }

    private void loadDeclaredMembers(String owner, ClassModel classModel, List<Symbol> members,
            Set<String> hiddenFields, Set<String> overriddenMethods,
            Set<String> semanticMethods, boolean interfaceOwner,
            boolean includeStaticsAndFields, Map<String, TypeSymbol> classEnvironment) {
        for (FieldModel fieldModel : includeStaticsAndFields ? classModel.fields()
                : List.<FieldModel>of()) {
            if (!fieldModel.flags().has(AccessFlag.PUBLIC)
                    || fieldModel.flags().has(AccessFlag.SYNTHETIC)) {
                continue;
            }
            String fieldName = fieldModel.fieldName().stringValue();
            if (!hiddenFields.add(fieldName)) {
                continue;
            }
            List<SyntaxKind> modifiers = new ArrayList<>();
            modifiers.add(SyntaxKind.PUBLIC);
            if (fieldModel.flags().has(AccessFlag.STATIC)) {
                modifiers.add(SyntaxKind.STATIC);
            }
            if (fieldModel.flags().has(AccessFlag.FINAL)) {
                modifiers.add(SyntaxKind.READONLY);
            }
            members.add(FieldSymbol.of(
                    fieldName,
                    owner + "." + fieldName,
                    JAVA_MODULE_LOCATION,
                    mapType(fieldModel.fieldTypeSymbol()),
                    null,
                    modifiers));
        }
        for (MethodModel methodModel : classModel.methods()) {
            String methodName = methodModel.methodName().stringValue();
            if (methodName.equals("<init>") || methodName.equals("<clinit>")) {
                continue;
            }
            if (!methodModel.flags().has(AccessFlag.PUBLIC)
                    || methodModel.flags().has(AccessFlag.SYNTHETIC)
                    || methodModel.flags().has(AccessFlag.BRIDGE)) {
                continue;
            }
            MethodTypeDesc descriptor = methodModel.methodTypeSymbol();
            if (!overriddenMethods.add(methodKey(methodName, descriptor))) {
                continue;
            }
            boolean isStatic = methodModel.flags().has(AccessFlag.STATIC);
            // A static interface method is not inherited, so it belongs only to the type that
            // declares it; an instance method is reachable through any reference that has it.
            if (isStatic && !includeStaticsAndFields) {
                continue;
            }
            String qualifiedName = owner + "." + methodName;
            MethodTypes methodTypes = methodTypes(qualifiedName, methodModel, descriptor,
                    classEnvironment);
            List<ParameterSymbol> parameters = parameters(qualifiedName,
                    methodTypes.parameterTypes(),
                    methodModel.flags().has(AccessFlag.VARARGS));
            List<SyntaxKind> modifiers = isStatic
                    ? List.of(SyntaxKind.PUBLIC, SyntaxKind.STATIC)
                    : List.of(SyntaxKind.PUBLIC);
            FunctionSymbol function = new FunctionSymbol(
                    methodName,
                    qualifiedName,
                    JAVA_MODULE_LOCATION,
                    methodTypes.returnType(),
                    methodTypes.typeParameters(),
                    parameters,
                    modifiers,
                    false,
                    false,
                    interfaceOwner);
            FunctionSymbol erased = new FunctionSymbol(
                    methodName,
                    qualifiedName,
                    JAVA_MODULE_LOCATION,
                    mapType(descriptor.returnType()),
                    List.of(),
                    parameters(qualifiedName, descriptor,
                            methodModel.flags().has(AccessFlag.VARARGS)),
                    modifiers,
                    false,
                    false,
                    interfaceOwner);
            erasedDeclarations.put(function, erased);
            // Generic substitution can make two distinct JVM descriptors one V# signature:
            // `String.compareTo(String)` and inherited `Comparable<T>.compareTo(T)` are both
            // `(string)` once `T` is known. Derived-before-base order keeps the direct member.
            if (semanticMethods.add(function.signature())) {
                members.add(function);
            }
        }
    }

    /// The type-variable environment of every superclass and interface as viewed through
    /// `owner`. This is the substitution Java itself applies to inherited members: for
    /// `List<E> extends Collection<E>`, Collection's `E` is List's `E`; for
    /// `String implements Comparable<String>`, Comparable's `T` is the builtin `string`.
    private Map<String, Map<String, TypeSymbol>> genericSupertypeEnvironments(
            NamedTypeSymbol owner) {
        Map<String, Map<String, TypeSymbol>> environments = new LinkedHashMap<>();
        Map<String, TypeSymbol> ownerEnvironment = new LinkedHashMap<>();
        for (TypeParameterSymbol parameter : getTypeParameters(owner)) {
            ownerEnvironment.put(parameter.name(), parameter);
        }
        environments.put(owner.qualifiedName(), Map.copyOf(ownerEnvironment));

        Deque<String> pending = new ArrayDeque<>();
        pending.add(owner.qualifiedName());
        while (!pending.isEmpty()) {
            String currentName = pending.removeFirst();
            ClassModel current = readClassFile(currentName);
            if (current == null) {
                continue;
            }
            Map<String, TypeSymbol> currentEnvironment = environments.get(currentName);
            for (Signature.ClassTypeSig supertype : directSupertypeSignatures(current)) {
                String supertypeName = className(supertype.classDesc());
                if (environments.containsKey(supertypeName)) {
                    continue;
                }
                Map<String, TypeSymbol> environment = new LinkedHashMap<>();
                ClassModel supertypeModel = readClassFile(supertypeName);
                List<Signature.TypeParam> parameters = supertypeModel == null
                        ? List.of() : classTypeParameters(supertypeModel);
                List<Signature.TypeArg> arguments = supertype.typeArgs();
                for (int index = 0; index < parameters.size(); index++) {
                    TypeSymbol argument = index < arguments.size()
                            ? mapTypeArgument(arguments.get(index), currentEnvironment,
                                    boundErasure(parameters.get(index)))
                            : null;
                    environment.put(parameters.get(index).identifier(),
                            argument == null ? BuiltinType.OBJECT : argument);
                }
                environments.put(supertypeName, Map.copyOf(environment));
                pending.add(supertypeName);
            }
        }
        return Map.copyOf(environments);
    }

    private static List<Signature.ClassTypeSig> directSupertypeSignatures(ClassModel classModel) {
        SignatureAttribute attribute = classModel.findAttribute(Attributes.signature())
                .orElse(null);
        if (attribute != null) {
            ClassSignature signature = attribute.asClassSignature();
            List<Signature.ClassTypeSig> supertypes = new ArrayList<>();
            supertypes.add(signature.superclassSignature());
            supertypes.addAll(signature.superinterfaceSignatures());
            return List.copyOf(supertypes);
        }
        List<Signature.ClassTypeSig> supertypes = new ArrayList<>();
        classModel.superclass().map(entry -> entry.asInternalName().replace('/', '.'))
                .map(ClassDesc::of).map(Signature.ClassTypeSig::of)
                .ifPresent(supertypes::add);
        for (var entry : classModel.interfaces()) {
            supertypes.add(Signature.ClassTypeSig.of(
                    ClassDesc.of(entry.asInternalName().replace('/', '.'))));
        }
        return List.copyOf(supertypes);
    }

    private static List<Signature.TypeParam> classTypeParameters(ClassModel classModel) {
        return classModel.findAttribute(Attributes.signature())
                .map(SignatureAttribute::asClassSignature)
                .map(ClassSignature::typeParameters)
                .orElse(List.of());
    }

    private MethodTypes methodTypes(String qualifiedName, MethodModel methodModel,
            MethodTypeDesc descriptor, Map<String, TypeSymbol> classEnvironment) {
        MethodTypes erased = new MethodTypes(List.of(), mapType(descriptor.returnType()),
                descriptor.parameterList().stream().map(this::mapType).toList());
        SignatureAttribute attribute = methodModel.findAttribute(Attributes.signature())
                .orElse(null);
        if (attribute == null) {
            return erased;
        }
        MethodSignature signature = attribute.asMethodSignature();
        List<TypeParameterSymbol> typeParameters = new ArrayList<>();
        Map<String, TypeSymbol> environment = new LinkedHashMap<>(classEnvironment);
        for (int ordinal = 0; ordinal < signature.typeParameters().size(); ordinal++) {
            String name = signature.typeParameters().get(ordinal).identifier();
            TypeParameterSymbol parameter = new TypeParameterSymbol(name,
                    qualifiedName + "." + name, JAVA_MODULE_LOCATION, ordinal,
                    boundErasure(signature.typeParameters().get(ordinal)));
            typeParameters.add(parameter);
            environment.put(name, parameter);
        }
        if (signature.arguments().size() != descriptor.parameterCount()) {
            return erased;
        }
        List<TypeSymbol> parameters = new ArrayList<>(descriptor.parameterCount());
        for (int index = 0; index < descriptor.parameterCount(); index++) {
            TypeSymbol mapped = mapSignature(signature.arguments().get(index), environment);
            if (mapped == null) {
                return erased;
            }
            parameters.add(mapped);
        }
        TypeSymbol returnType = mapSignature(signature.result(), environment);
        if (returnType == null) {
            return erased;
        }
        return new MethodTypes(List.copyOf(typeParameters), returnType,
                List.copyOf(parameters));
    }

    private record MethodTypes(List<TypeParameterSymbol> typeParameters, TypeSymbol returnType,
            List<TypeSymbol> parameterTypes) {
    }

    /// The JVM carrier one declared type parameter erases to (JLS 4.6, the design).
    ///
    /// Erasure is the *leftmost* bound - the class bound when one is written, otherwise the
    /// first interface bound - and `Object` when nothing is declared. That is precisely the
    /// descriptor javac emits for every position typed by the parameter, which is why the
    /// value is read from the signature rather than assumed: `<T extends Comparable<? super
    /// T>>` erases to `Comparable`, `<E extends Enum<E>>` to `Enum`, and `Enum.valueOf`'s
    /// return descriptor is `Ljava/lang/Enum;` accordingly. Only the bound's raw class is
    /// taken; its own type arguments are erased away exactly as the JVM erases them, and an
    /// F-bound would otherwise be cyclic. A bound that is itself a type variable contributes
    /// `object`: reading it would need the other parameter's erasure, and the conservative
    /// answer is never unsound because the constraint check simply admits more.
    private TypeSymbol boundErasure(Signature.TypeParam parameter) {
        Signature.RefTypeSig bound = parameter.classBound()
                .orElseGet(() -> parameter.interfaceBounds().isEmpty()
                        ? null : parameter.interfaceBounds().getFirst());
        if (bound instanceof Signature.ClassTypeSig classType) {
            return mapType(classType.classDesc());
        }
        return BuiltinType.OBJECT;
    }

    /// The JVM override/hiding identity of a method: its name and parameter descriptors,
    /// deliberately without the return type.
    private static String methodKey(String methodName, MethodTypeDesc descriptor) {
        StringBuilder key = new StringBuilder(methodName).append('(');
        for (int i = 0; i < descriptor.parameterCount(); i++) {
            key.append(descriptor.parameterType(i).descriptorString());
        }
        return key.append(')').toString();
    }

    private List<FunctionSymbol> loadConstructors(NamedTypeSymbol typeSymbol,
            ClassModel classModel) {
        List<FunctionSymbol> constructors = new ArrayList<>();
        String qualifiedName = typeSymbol.qualifiedName() + ".<init>";
        for (MethodModel methodModel : classModel.methods()) {
            if (!methodModel.methodName().equalsString("<init>")
                    || !methodModel.flags().has(AccessFlag.PUBLIC)
                    || methodModel.flags().has(AccessFlag.SYNTHETIC)) {
                continue;
            }
            MethodTypeDesc descriptor = methodModel.methodTypeSymbol();
            constructors.add(new FunctionSymbol(
                    "<init>",
                    qualifiedName,
                    JAVA_MODULE_LOCATION,
                    typeSymbol,
                    List.of(),
                    parameters(qualifiedName, descriptor,
                            methodModel.flags().has(AccessFlag.VARARGS)),
                    List.of(SyntaxKind.PUBLIC),
                    false,
                    false));
        }
        return List.copyOf(constructors);
    }

    /// A Java varargs method is a method whose trailing parameter is an array plus the
    /// `ACC_VARARGS` flag, which is exactly what C# `params` means, so the discovered
    /// parameter is marked `params` and the existing expansion applies unchanged.
    ///
    /// Normal form is still attempted first by overload resolution, so passing an actual
    /// array to `String.Join(CharSequence, CharSequence[])` continues to bind without being
    /// wrapped in a second array - the same rule Java and C# both follow.
    private List<ParameterSymbol> parameters(String qualifiedName, MethodTypeDesc descriptor,
            boolean varargs) {
        return parameters(qualifiedName,
                descriptor.parameterList().stream().map(this::mapType).toList(), varargs);
    }

    private static List<ParameterSymbol> parameters(String qualifiedName,
            List<TypeSymbol> parameterTypes, boolean varargs) {
        List<ParameterSymbol> parameters = new ArrayList<>();
        for (int i = 0; i < parameterTypes.size(); i++) {
            String parameterName = "p" + i;
            boolean trailing = varargs && i == parameterTypes.size() - 1;
            parameters.add(new ParameterSymbol(
                    parameterName,
                    qualifiedName + "." + parameterName,
                    JAVA_MODULE_LOCATION,
                    parameterTypes.get(i),
                    i,
                    trailing ? List.of(SyntaxKind.PARAMS) : List.of(),
                    null));
        }
        return List.copyOf(parameters);
    }

    /// Maps the exact subset of the Java signature algebra V# can express today. Wildcards
    /// deliberately return `null`, making that one position fall back to its erased descriptor
    /// rather than pretending `? extends` or `? super` is an invariant type argument - except
    /// on a functional interface, where JLS 9.9 defines exactly what the wildcards mean.
    private TypeSymbol mapSignature(Signature signature, Map<String, TypeSymbol> environment) {
        return switch (signature) {
            case Signature.BaseTypeSig base ->
                    mapType(ClassDesc.ofDescriptor(String.valueOf(base.baseType())));
            case Signature.TypeVarSig variable -> environment.get(variable.identifier());
            case Signature.ArrayTypeSig array -> {
                TypeSymbol component = mapSignature(array.componentSignature(), environment);
                yield component == null ? null : new TypeSymbol.Array(component, List.of(1));
            }
            case Signature.ClassTypeSig classType -> {
                TypeSymbol raw = mapType(classType.classDesc());
                if (classType.typeArgs().isEmpty()) {
                    yield raw;
                }
                if (!(raw instanceof NamedTypeSymbol definition)
                        || definition.arity() != classType.typeArgs().size()) {
                    yield null;
                }
                // Wildcards are kept here, for every interface alike. JLS 9.9's
                // non-wildcard parameterization exists to type a *lambda's* parameters, so it
                // is applied where a lambda is typed - [#functionalMethodOf] - and not baked
                // into the type. Projecting at this point silently made every functional
                // interface invariant, and `Iterable` is one: `String.Join(sep, List<string>)`
                // was refused because `Iterable<? extends CharSequence>` had become
                // `Iterable<CharSequence>`, which no `List<string>` converts to (the design revised).
                List<TypeParameterSymbol> declaredBounds = getTypeParameters(definition);
                List<TypeSymbol> arguments = new ArrayList<>(classType.typeArgs().size());
                boolean complete = true;
                for (int index = 0; index < classType.typeArgs().size(); index++) {
                    Signature.TypeArg argument = classType.typeArgs().get(index);
                    TypeSymbol declaredBound = declaredBounds.size() == classType.typeArgs().size()
                            ? declaredBounds.get(index).bound()
                            : BuiltinType.OBJECT;
                    TypeSymbol mapped = mapTypeArgument(argument, environment, declaredBound);
                    if (mapped == null) {
                        complete = false;
                        break;
                    }
                    arguments.add(mapped);
                }
                yield complete ? new TypeSymbol.Constructed(definition, arguments) : null;
            }
        };
    }

    /// One type argument, wildcards included.
    ///
    /// A wildcard becomes a [TypeSymbol.Wildcard] carrying its written bound rather than
    /// erasing its whole enclosing signature, which is what let `Collector<? super T, A, R>`
    /// cost `Stream.Collect` its result type. An unbounded `?` is `? extends object`, which
    /// is what the JLS says it means.
    /// The V# keyword type a boxed carrier denotes in a *type-argument* position, or
    /// the argument unchanged.
    ///
    /// `List<int>` and `List<Integer>` are one type: V# already boxes every value it puts in a
    /// generic position and erases the argument away, so the JVM sees the same `List` either
    /// way. Reading `Stream.Boxed().ToList()` as `List<java.lang.Integer>` therefore denoted a
    /// type V# source cannot spell for a list it can build, and `IntStream` aggregation could
    /// not be assigned anywhere. The mapping is confined to type arguments on purpose: a
    /// `java.lang.Integer` in a parameter, field or return position keeps its reference
    /// identity, because there it can be `null` and `int` cannot.
    private static TypeSymbol keywordForBoxedArgument(TypeSymbol argument) {
        if (!(argument instanceof NamedTypeSymbol named)) {
            return argument;
        }
        return switch (named.qualifiedName()) {
            case "java.lang.Integer" -> BuiltinType.INT;
            case "java.lang.Long" -> BuiltinType.LONG;
            case "java.lang.Short" -> BuiltinType.SHORT;
            case "java.lang.Byte" -> BuiltinType.SBYTE;
            case "java.lang.Character" -> BuiltinType.CHAR;
            case "java.lang.Boolean" -> BuiltinType.BOOL;
            case "java.lang.Float" -> BuiltinType.FLOAT;
            case "java.lang.Double" -> BuiltinType.DOUBLE;
            default -> argument;
        };
    }

    private TypeSymbol mapTypeArgument(Signature.TypeArg argument,
            Map<String, TypeSymbol> environment, TypeSymbol declaredBound) {
        if (!(argument instanceof Signature.TypeArg.Bounded bounded)) {
            // `?` is `? extends B` for the `B` the parameter declares, not `? extends Object`
            //. `WebClient.get()` returns `RequestHeadersUriSpec<?>` whose parameter is
            // bounded by `RequestHeadersSpec<S>`; dropping that bound made every chained call
            // on the result a member lookup against `object`.
            return new TypeSymbol.Wildcard(declaredBound, false);
        }
        TypeSymbol bound = keywordForBoxedArgument(
                mapSignature(bounded.boundType(), environment));
        if (bound == null) {
            return null;
        }
        return switch (bounded.wildcardIndicator()) {
            case NONE -> bound;
            case EXTENDS -> new TypeSymbol.Wildcard(bound, false);
            case SUPER -> new TypeSymbol.Wildcard(bound, true);
        };
    }

    /// JLS 9.9's non-wildcard parameterization of one type argument.
    ///
    /// Both bounded forms contribute their bound - `? super string` and `? extends string`
    /// alike give `string`, which is exactly the type a lambda parameter takes in each case -
    /// and an unbounded `?` gives the *declared* bound of the parameter it stands for, which
    /// is what JLS 9.9 means and what an unbounded parameter's implicit `Object` bound already
    /// gave for every unbounded declaration.
    ///
    /// Reading the declared bound is not a refinement of a conservative rule, it is the
    /// difference between compiling and not: `RouterFunction<T extends ServerResponse>` is a
    /// functional interface, so `toHttpHandler(RouterFunction<?>)` projects here, and
    /// projecting to `object` made the parameter `RouterFunction<object>` - a type no
    /// `RouterFunction<ServerResponse>` converts to, rejecting a call Java accepts.
    private TypeSymbol projectTypeArgument(Signature.TypeArg argument,
            Map<String, TypeSymbol> environment, TypeSymbol declaredBound) {
        if (argument instanceof Signature.TypeArg.Bounded bounded) {
            return keywordForBoxedArgument(mapSignature(bounded.boundType(), environment));
        }
        return declaredBound;
    }

    private static TypeSymbol substitute(TypeSymbol type,
            Map<TypeParameterSymbol, TypeSymbol> substitutions) {
        if (type instanceof TypeParameterSymbol parameter) {
            return substitutions.getOrDefault(parameter, parameter);
        }
        if (type instanceof TypeSymbol.Array array) {
            TypeSymbol element = substitute(array.element(), substitutions);
            return element.equals(array.element()) ? array : new TypeSymbol.Array(element, array.ranks());
        }
        if (type instanceof TypeSymbol.Constructed constructed) {
            List<TypeSymbol> arguments = constructed.arguments().stream()
                    .map(argument -> substitute(argument, substitutions)).toList();
            return arguments.equals(constructed.arguments()) ? constructed
                    : new TypeSymbol.Constructed(constructed.definition(), arguments);
        }
        if (type instanceof TypeSymbol.Wildcard wildcard) {
            TypeSymbol bound = substitute(wildcard.bound(), substitutions);
            return bound.equals(wildcard.bound()) ? wildcard
                    : new TypeSymbol.Wildcard(bound, wildcard.superBound());
        }
        if (type instanceof TypeSymbol.Nullable nullable) {
            TypeSymbol element = substitute(nullable.element(), substitutions);
            return element.equals(nullable.element()) ? nullable : new TypeSymbol.Nullable(element);
        }
        if (type instanceof TypeSymbol.Ref ref) {
            TypeSymbol element = substitute(ref.element(), substitutions);
            return element.equals(ref.element()) ? ref : new TypeSymbol.Ref(element, ref.readOnly());
        }
        return type;
    }

    /// Maps a JVM type to the V# type with the same runtime representation.
    ///
    /// The primitive mapping follows signedness, not spelling: JVM `byte` is signed, so it
    /// is C# `sbyte`, and JVM `char` is an unsigned 16-bit code unit, so it is C# `char`.
    /// `java.lang.String` and `java.lang.Object` map onto the built-in `string` and
    /// `object`, which is what the rest of the type system already assumes.
    private TypeSymbol mapType(ClassDesc descriptor) {
        if (descriptor.isPrimitive()) {
            return switch (descriptor.descriptorString()) {
                case "V" -> BuiltinType.VOID;
                case "Z" -> BuiltinType.BOOL;
                case "B" -> BuiltinType.SBYTE;
                case "S" -> BuiltinType.SHORT;
                case "C" -> BuiltinType.CHAR;
                case "I" -> BuiltinType.INT;
                case "J" -> BuiltinType.LONG;
                case "F" -> BuiltinType.FLOAT;
                case "D" -> BuiltinType.DOUBLE;
                default -> throw new IllegalStateException(
                        "Unknown JVM primitive descriptor: " + descriptor.descriptorString());
            };
        }
        if (descriptor.isArray()) {
            int rank = 0;
            ClassDesc element = descriptor;
            while (element.isArray()) {
                element = element.componentType();
                rank++;
            }
            // A Java `T[][]` is a jagged array of arrays, which V# spells `T[][]`: one rank-1
            // dimension per level, never a single multi-dimensional rank.
            List<Integer> ranks = new ArrayList<>(rank);
            for (int i = 0; i < rank; i++) {
                ranks.add(1);
            }
            return new TypeSymbol.Array(mapType(element), List.copyOf(ranks));
        }
        String className = descriptor.packageName().isEmpty()
                ? descriptor.displayName()
                : descriptor.packageName() + "." + descriptor.displayName();
        return switch (className) {
            case "java.lang.String" -> BuiltinType.STRING;
            case "java.lang.Object" -> BuiltinType.OBJECT;
            default -> {
                NamedTypeSymbol resolved = resolveType(className);
                yield resolved != null ? resolved : BuiltinType.OBJECT;
            }
        };
    }

    private static String className(ClassDesc descriptor) {
        return descriptor.packageName().isEmpty()
                ? descriptor.displayName()
                : descriptor.packageName() + "." + descriptor.displayName();
    }

    private static String simpleNameOf(String qualifiedName) {
        // `$` separates a nested type from its enclosing one and outranks `.`, so
        // `java.util.Map$Entry` is named `Entry` - what the source wrote after the owner.
        int lastSeparator = Math.max(qualifiedName.lastIndexOf('.'), qualifiedName.lastIndexOf('$'));
        return lastSeparator < 0 ? qualifiedName : qualifiedName.substring(lastSeparator + 1);
    }
}
