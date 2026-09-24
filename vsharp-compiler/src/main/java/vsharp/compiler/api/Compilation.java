package vsharp.compiler.api;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import vsharp.compiler.diagnostics.Diagnostic;
import vsharp.compiler.diagnostics.DiagnosticBag;
import vsharp.compiler.diagnostics.DiagnosticCode;
import vsharp.compiler.semantics.binding.BoundExpression;
import vsharp.compiler.semantics.binding.ConstantFieldBinder;
import vsharp.compiler.semantics.binding.DeclarationBinder;
import vsharp.compiler.semantics.binding.ExpressionBinder;
import vsharp.compiler.semantics.binding.ExpressionBinding;
import vsharp.compiler.semantics.binding.JavaInterop;
import vsharp.compiler.semantics.binding.SemanticModel;
import vsharp.compiler.semantics.conversions.Conversions;
import vsharp.compiler.semantics.flow.FlowAnalysis;
import vsharp.compiler.semantics.flow.FlowResult;
import vsharp.compiler.semantics.symbols.FunctionSymbol;
import vsharp.compiler.semantics.symbols.ParameterSymbol;
import vsharp.compiler.semantics.symbols.TypeParameterSymbol;
import vsharp.compiler.semantics.types.JvmTypeKind;
import vsharp.compiler.semantics.types.TypeSymbol;
import vsharp.compiler.source.SourceFile;
import vsharp.compiler.syntax.AuxiliarySyntax;
import vsharp.compiler.syntax.Parser;
import vsharp.compiler.syntax.SourceStyleValidator;
import vsharp.compiler.syntax.SyntaxKind;

/// The front-end driver: an immutable set of sources plus the phases that run over them.
///
/// The facade exists so that drivers - the CLI, Gradle, tests and later the backend - all
/// sequence the compiler identically. Nothing here re-implements a phase; each phase keeps
/// its own public entry point and stays independently testable.
///
/// Phases run one at a time across *all* files, and a phase runs only when no error has
/// been reported yet. Gating is deliberate: a declaration binder fed a syntactically broken
/// tree produces cascades that hide the real fault, and C# users expect the first genuine
/// error to lead. Warnings never stop a run.
///
/// Every phase shares one [DiagnosticBag], so the result is ordered by file and position
/// rather than by the order the phases happened to discover problems.
///
/// Each file is bound into its own [SemanticModel]; cross-file symbol merging beyond
/// namespace lookup is a later milestone.
public final class Compilation {

    private final List<SourceFile> sources;
    private final Set<String> symbols;
    private final List<Path> classpath;
    private final boolean allowUnboundedJoins;

    private Compilation(List<SourceFile> sources, Set<String> symbols, List<Path> classpath,
            boolean allowUnboundedJoins) {
        this.sources = sources;
        this.symbols = symbols;
        this.classpath = classpath;
        this.allowUnboundedJoins = allowUnboundedJoins;
    }

    /// The same compilation, accepting unbounded task joins.
    ///
    /// Joining a task with the JDK's own `Get()` steps outside the execution limit, so it is an
    /// error by default. This opt-in downgrades it to a warning; it never silences it, because
    /// the value of the rule is that every such call site stays visible.
    ///
    /// @return a compilation whose unbounded joins are warnings rather than errors
    public Compilation allowingUnboundedJoins() {
        return new Compilation(sources, symbols, classpath, true);
    }

    /// A compilation of `sources` with no preprocessing symbols defined.
    public static Compilation of(List<SourceFile> sources) {
        return of(sources, Set.of());
    }

    /// A compilation of `sources` with the given preprocessing symbols.
    ///
    /// Symbols are held in a sorted set so that directive evaluation, and any diagnostic
    /// derived from it, cannot vary with hash iteration order between runs.
    public static Compilation of(List<SourceFile> sources, Set<String> symbols) {
        return of(sources, symbols, List.of());
    }

    /// A compilation that may also resolve Java types from `classpath`.
    ///
    /// The entries are kept in the order written, because a classpath is a search order and a
    /// duplicated type must resolve to the same class the JVM will load at run time. Nothing
    /// outside these entries and the module path can contribute a type: the compiler's own
    /// launch classpath is deliberately not a source, so a build that compiles is a build whose
    /// dependencies were declared.
    public static Compilation of(List<SourceFile> sources, Set<String> symbols,
            List<Path> classpath) {
        Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(symbols, "symbols");
        Objects.requireNonNull(classpath, "classpath");
        List<SourceFile> files = List.copyOf(sources);
        files.forEach(file -> Objects.requireNonNull(file, "source file"));
        symbols.forEach(symbol -> Objects.requireNonNull(symbol, "symbol"));
        List<Path> entries = List.copyOf(classpath);
        entries.forEach(entry -> Objects.requireNonNull(entry, "classpath entry"));
        return new Compilation(files, Collections.unmodifiableSet(new TreeSet<>(symbols)),
                entries, false);
    }

    /// The classpath entries Java resolution may read, in search order.
    public List<Path> classpath() {
        return classpath;
    }

    /// The sources, in the order phases will visit them.
    public List<SourceFile> sources() {
        return sources;
    }

    /// The defined preprocessing symbols, in sorted order.
    public Set<String> symbols() {
        return symbols;
    }

    /// The corelib resource name, resolved once per classloader.
    private static final String CORELIB_RESOURCE = "/corelib.vs";
    private static final String CORELIB_FILE_NAME = SourceFile.CORELIB_NAME;

    /// Loads the compiler-shipped declarations (`System.Console` and friends) that every
    /// compilation binds against so qualified names such as `System.Console.WriteLine`
    /// resolve without a user-supplied reference mechanism.
    ///
    /// This file is compiler-controlled, not user input: it participates in parsing and
    /// declaration binding so its symbols merge into the shared global scope, but it is
    /// deliberately never exposed through [CompilationResult#units()] - a caller's result
    /// shape must reflect only the sources it supplied, exactly like a referenced assembly
    /// contributes symbols without becoming a compilation's own syntax tree.
    private static SourceFile loadCorelib() {
        try (InputStream is = Compilation.class.getResourceAsStream(CORELIB_RESOURCE)) {
            if (is == null) {
                throw new IllegalStateException(
                        "corelib.vs resource is missing from the compiler classpath; this is a packaging defect");
            }
            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            return SourceFile.of(CORELIB_FILE_NAME, content);
        } catch (IOException e) {
            throw new IllegalStateException("corelib.vs resource could not be read", e);
        }
    }

    /// Fails fast if the compiler-shipped corelib itself produced a diagnostic. Corelib is
    /// not user input, so any diagnostic against it is a compiler defect that must never be
    /// silently folded into a user's result - it is asserted here rather than left to surface
    /// as a baffling error against a file the user never wrote.
    private static void requireCorelibClean(List<Diagnostic> diagnostics, String stage) {
        for (Diagnostic diagnostic : diagnostics) {
            if (diagnostic.file() != null && CORELIB_FILE_NAME.equals(diagnostic.file().name())) {
                throw new IllegalStateException(
                        "compiler defect: corelib.vs reported a diagnostic during " + stage + ": " + diagnostic);
            }
        }
    }

    /// Runs the front end and reports how far it got.
    ///
    /// The call is pure with respect to the compilation: running it twice yields equal
    /// diagnostics, so callers may analyse the same instance repeatedly.
    public CompilationResult analyze() {
        DiagnosticBag diagnostics = new DiagnosticBag();

        SourceFile corelib = loadCorelib();
        List<SourceFile> allSources = new ArrayList<>(sources.size() + 1);
        allSources.add(corelib);
        allSources.addAll(sources);

        List<AuxiliarySyntax.CompilationUnit> allTrees = new ArrayList<>(allSources.size());
        for (SourceFile file : allSources) {
            allTrees.add(Parser.parse(file, symbols, diagnostics));
        }
        requireCorelibClean(diagnostics.all(), "parsing");
        List<AuxiliarySyntax.CompilationUnit> trees = allTrees.subList(1, allTrees.size());
        if (diagnostics.hasErrors()) {
            return result(Phase.PARSE, diagnostics, parsed(sources, trees));
        }

        // Layout is a language-level source-quality invariant, not a CLI preference. Run it
        // through the shared facade after syntax succeeds so API, CLI, Gradle and backend users
        // all receive the same strict Allman/indentation diagnostics without parser cascades.
        for (int index = 0; index < allSources.size(); index++) {
            SourceStyleValidator.validate(
                    allSources.get(index), symbols, allTrees.get(index).inlineBraces(), diagnostics);
        }
        requireCorelibClean(diagnostics.all(), "source style validation");
        if (diagnostics.hasErrors()) {
            return result(Phase.PARSE, diagnostics, parsed(sources, trees));
        }

        SemanticModel globalModel = DeclarationBinder.bind(allSources, allTrees, diagnostics, classpath);
        requireCorelibClean(diagnostics.all(), "declaration binding");
        List<SemanticModel> models = new ArrayList<>(sources.size());
        for (int index = 0; index < sources.size(); index++) {
            models.add(globalModel);
        }
        if (diagnostics.hasErrors()) {
            return result(Phase.DECLARATION_BINDING, diagnostics, declared(sources, trees, models));
        }

        // A `const` field's value is a folded *bound* expression, so it can only be produced
        // once every declaration exists: `const int Doubled = Base * 2;` needs `Base` in the
        // declaration space before its initializer can be bound at all. This pass sits
        // exactly there - after the space is complete, before anything reads a constant - and
        // is what lets a `const` field support what a `const` local already supported. Its
        // failures belong to the declaration phase, because an unfoldable or circular constant
        // is a broken declaration rather than a broken use.
        ConstantFieldBinder.resolve(allSources, allTrees, globalModel, diagnostics);
        requireCorelibClean(diagnostics.all(), "constant binding");
        if (diagnostics.hasErrors()) {
            return result(Phase.DECLARATION_BINDING, diagnostics, declared(sources, trees, models));
        }

        List<ExpressionBinding> bindings = new ArrayList<>(sources.size());
        for (int index = 0; index < sources.size(); index++) {
            bindings.add(ExpressionBinder.bind(sources.get(index), trees.get(index),
                    models.get(index), diagnostics, allowUnboundedJoins));
        }
        if (diagnostics.hasErrors()) {
            return result(Phase.EXPRESSION_BINDING, diagnostics, bound(sources, trees, models, bindings));
        }

        List<UnitAnalysis> units = new ArrayList<>(sources.size());
        for (int index = 0; index < sources.size(); index++) {
            FlowResult flow = FlowAnalysis.analyze(sources.get(index), trees.get(index),
                    models.get(index), bindings.get(index), diagnostics);
            units.add(new UnitAnalysis.Analysed(sources.get(index), trees.get(index),
                    models.get(index), bindings.get(index), flow));
        }
        return result(Phase.FLOW_ANALYSIS, diagnostics, units);
    }

    public CompilationResult emit() {
        CompilationResult analyzed = analyze();
        if (analyzed.hasErrors() || analyzed.reached() != Phase.FLOW_ANALYSIS) {
            return analyzed;
        }

        DiagnosticBag emissionDiagnostics = unsupportedGenericDiagnostics(analyzed);
        if (emissionDiagnostics.hasErrors()) {
            return new CompilationResult(Phase.FLOW_ANALYSIS,
                    emissionDiagnostics.all(), analyzed.units());
        }

        List<UnitAnalysis> emittedUnits = new java.util.ArrayList<>();
        List<vsharp.compiler.ir.IrUnit> lowered = new ArrayList<>(analyzed.units().size());
        DiagnosticBag loweredDiagnostics = new DiagnosticBag();
        loweredDiagnostics.addAll(analyzed.diagnostics());
        for (UnitAnalysis unit : analyzed.units()) {
            if (!(unit instanceof UnitAnalysis.Analysed analysed)) {
                throw new IllegalStateException("Expected Analysed unit");
            }
            vsharp.compiler.ir.IrUnit ir = vsharp.compiler.ir.UnitLowerer.lower(analysed);
            lowered.add(ir);
            reportInvalidStaticLocalCaptures(ir, analysed, loweredDiagnostics);
        }
        if (loweredDiagnostics.hasErrors()) {
            return new CompilationResult(Phase.FLOW_ANALYSIS,
                    loweredDiagnostics.all(), analyzed.units());
        }

        int unitIndex = 0;
        for (UnitAnalysis unit : analyzed.units()) {
            if (unit instanceof UnitAnalysis.Analysed analysed) {
                vsharp.compiler.ir.IrUnit ir = lowered.get(unitIndex++);
                java.util.Map<String, byte[]> classes = vsharp.compiler.backend.JvmBackend.emit(ir);
                emittedUnits.add(new UnitAnalysis.Emitted(
                        analysed.file(), analysed.syntax(), analysed.model(),
                        analysed.expressions(), analysed.flow(), ir, classes));
            } else {
                throw new IllegalStateException("Expected Analysed unit");
            }
        }
        
        return new CompilationResult(Phase.CODE_GENERATION, analyzed.diagnostics(), emittedUnits);
    }

    /// C# permits captures only in a non-static local function (CS8421/CS8422). The exact
    /// capture and implicit-receiver facts are lowering results, so this check belongs at the
    /// same post-lowering gate that formerly protected the unimplemented capture backend.
    private static void reportInvalidStaticLocalCaptures(vsharp.compiler.ir.IrUnit ir,
            UnitAnalysis.Analysed analysed, DiagnosticBag diagnostics) {
        java.util.Map<FunctionSymbol, List<vsharp.compiler.ir.IrCapture>> captures =
                vsharp.compiler.ir.CaptureCollector.resolveTransitive(ir.functions());
        Set<FunctionSymbol> receiverUsers =
                vsharp.compiler.ir.CaptureCollector.resolveImplicitReceivers(ir.functions());
        for (vsharp.compiler.ir.IrFunction function : ir.functions()) {
            if (!(function instanceof vsharp.compiler.ir.IrFunction.Implemented implemented)
                    || !implemented.symbol().localFunction()
                    || !implemented.symbol().modifiers().contains(SyntaxKind.STATIC)
                    // A lambda body is emitted static because it needs no receiver, not
                    // because the source said `static`. C# lets a lambda capture, and that rule
                    // gives it the same shared cells a non-static local function uses, so
                    // CS8421's rule does not apply to one.
                    || vsharp.compiler.semantics.binding.LambdaNames.isLambdaBody(
                            implemented.symbol())) {
                continue;
            }
            for (vsharp.compiler.ir.IrCapture capture : captures.getOrDefault(
                    implemented.symbol(), List.of())) {
                diagnostics.report(
                        DiagnosticCode.STATIC_LOCAL_FUNCTION_CANNOT_CAPTURE_VARIABLE,
                        analysed.file(), implemented.symbol().location().span(),
                        capture.symbol().name());
            }
            if (receiverUsers.contains(implemented.symbol())) {
                diagnostics.report(
                        DiagnosticCode.STATIC_LOCAL_FUNCTION_CANNOT_CAPTURE_RECEIVER,
                        analysed.file(), implemented.symbol().location().span());
            }
        }
    }

    /// The JVM-erased backend can adapt a direct `T` parameter/result with a box/unbox at the
    /// call boundary. A constructed generic such as `List<T>` is also stable: every
    /// specialization has the definition's one JVM descriptor, because its arguments erase.
    /// Type-parameter-dependent arrays and ref/out/params storage can change representation and
    /// must not reach lowering as a specialized descriptor that has no emitted declaration.
    private static DiagnosticBag unsupportedGenericDiagnostics(CompilationResult analyzed) {
        DiagnosticBag diagnostics = new DiagnosticBag();
        diagnostics.addAll(analyzed.diagnostics());
        for (UnitAnalysis unit : analyzed.units()) {
            if (!(unit instanceof UnitAnalysis.Analysed bound)) {
                continue;
            }
            for (BoundExpression expression : bound.expressions().expressions()) {
                if (expression instanceof BoundExpression.Call call) {
                    switch (genericEmission(call)) {
                        case GenericEmission.Supported ignored -> {
                        }
                        case GenericEmission.UnsupportedForm form ->
                                diagnostics.report(DiagnosticCode.NOT_YET_IMPLEMENTED,
                                        bound.file(), call.span(), form.description());
                        case GenericEmission.PrimitiveArray primitive ->
                                diagnostics.report(DiagnosticCode.GENERIC_PRIMITIVE_ARRAY_CARRIER,
                                        bound.file(), call.span(), primitive.written(),
                                        primitive.declared(), primitive.erasure());
                    }
                    continue;
                }
                // `new T[n]` is the one array whose element type is unknown at run time. The
                // JVM allocates the erasure - an `object[]` - and the caller's narrowing
                // `checkcast` to `string[]` then fails on a value the program built correctly,
                // which is a wrong answer rather than a missing feature.
                if (allocatedArray(expression) instanceof TypeSymbol.Array array
                        && containsTypeParameter(array)) {
                    diagnostics.report(DiagnosticCode.GENERIC_ARRAY_CREATION, bound.file(),
                            expression.span(), array.element().displayName());
                }
            }
        }
        return diagnostics;
    }

    /// The array type an expression allocates, or `null` when it allocates none. Both forms
    /// that reach `anewarray` are covered: `new T[n]`/`new T[] { ... }`, which binding leaves
    /// deferred to its syntax, and a collection expression whose target type is an array.
    private static TypeSymbol allocatedArray(BoundExpression expression) {
        return switch (expression) {
            case BoundExpression.Deferred deferred
                    when BoundExpression.ARRAY_CREATION_FORM.equals(deferred.form()) ->
                    deferred.type();
            case BoundExpression.Collection collection -> collection.type();
            default -> null;
        };
    }

    /// Whether a call can be emitted against its erased declaration, and why not when it
    /// cannot. The reasons are distinct because their remedies are: a gated *form* is a
    /// missing feature, while a primitive array standing in for an erased `object[]` is a
    /// program the JVM's type system refuses and no later build will accept.
    private sealed interface GenericEmission {
        record Supported() implements GenericEmission {}

        record UnsupportedForm(String description) implements GenericEmission {}

        record PrimitiveArray(String written, String declared, String erasure)
                implements GenericEmission {}
    }

    private static final GenericEmission SUPPORTED = new GenericEmission.Supported();

    private static final GenericEmission GATED_FORM = new GenericEmission.UnsupportedForm(
            "generic calls with ref/out or params storage, or a type parameter nested in "
                    + "another carrier");

    private static GenericEmission genericEmission(BoundExpression.Call call) {
        FunctionSymbol declaration = call.declaration();
        FunctionSymbol specialization = call.function();
        if (JavaInterop.isModulePathSymbol(declaration)) {
            for (int index = 0; index < declaration.parameters().size(); index++) {
                ParameterSymbol parameter = declaration.parameters().get(index);
                if (parameter.modifiers().contains(SyntaxKind.REF)
                        || parameter.modifiers().contains(SyntaxKind.OUT)
                        || !compatibleJavaParameterCarrier(parameter.type(),
                                javaArgumentCarrier(call, specialization, index))) {
                    return GATED_FORM;
                }
            }
            return compatibleJavaReturnCarrier(declaration.returnType(),
                    specialization.returnType()) ? SUPPORTED : GATED_FORM;
        }
        if (declaration.typeParameters().isEmpty()) {
            return SUPPORTED;
        }
        for (int index = 0; index < declaration.parameters().size(); index++) {
            ParameterSymbol parameter = declaration.parameters().get(index);
            // `ref T`/`out T` is the declaration's erasure wrapped in a cell -
            // `[Ljava/lang/Object;` - and the call site adapts the value into and out of it,
            // which is the same boundary a by-value `T` already crossed. The cell is
            // decided by the declaration, so the carrier rule below still governs what may be
            // inside it: `ref (T, T)` stays gated because a tuple has no erasure rule yet.
            //
            // `params T[]` is not a storage form of its own: by the time a call is bound, an
            // expanded one has already collapsed its loose arguments into the array that is
            // actually passed, so both forms present the same array to the same rule
            // the design applies to every other one. Refusing the modifier rather than the carrier
            // left `Count(names)` gated while the identical `T[]` declaration compiled.
            GenericEmission carrier = arrayCarrier(parameter.type(),
                    javaArgumentCarrier(call, specialization, index));
            if (!(carrier instanceof GenericEmission.Supported)) {
                return carrier;
            }
        }
        return arrayCarrier(declaration.returnType(), specialization.returnType());
    }

    /// Whether one declared position of a generic V# callable survives erasure.
    ///
    /// A bare `T` is adapted by box/unbox at the boundary and a constructed application erases
    /// to its definition's single descriptor; both were already emitted. A
    /// type-parameter-dependent array is the position this rule adds: `T[]` is
    /// `[Ljava/lang/Object;` in the descriptor, and the question is only whether the argument's
    /// own descriptor is assignable to it, which is JVM array covariance and nothing more.
    ///
    /// The test is therefore on JVM array *depth*, not on C# rank specifiers. An argument
    /// deeper than the declaration passes at every level, because the component it presents at
    /// that depth is itself an array and every array is an `object`: `string[][]` and `int[][]`
    /// are both `object[]`. An argument of equal depth passes only when its leaf is a
    /// reference, which is where a primitive array is refused: `[I` is not an
    /// `[Ljava/lang/Object;` and the JVM defines no widening to it. Boxing into an
    /// `Integer[]` copy would compile and then silently lose the caller's aliasing, so
    /// `Second(new int[] { 1 })` is refused by name instead.
    private static GenericEmission arrayCarrier(TypeSymbol declared, TypeSymbol specialized) {
        if (!containsTypeParameter(declared) || hasStableGenericCarrier(declared)) {
            return SUPPORTED;
        }
        if (!(declared instanceof TypeSymbol.Array declaredArray)) {
            return GATED_FORM;
        }
        if (declared.equals(specialized)) {
            return SUPPORTED;
        }
        if (specialized instanceof TypeSymbol.Array specializedArray) {
            int declaredDepth = declaredArray.jvmDepth();
            int specializedDepth = specializedArray.jvmDepth();
            if (specializedDepth > declaredDepth
                    || (specializedDepth == declaredDepth
                            && specializedArray.element().jvmTypeKind()
                                    == JvmTypeKind.REFERENCE)) {
                return SUPPORTED;
            }
        }
        return new GenericEmission.PrimitiveArray(specialized.displayName(),
                declared.displayName(), erasureOf(declaredArray));
    }

    /// The array a type-parameter-dependent one becomes in the descriptor: each type parameter
    /// leaf is its bound, which is `object` for every V# declaration.
    private static String erasureOf(TypeSymbol.Array array) {
        TypeSymbol element = array.element() instanceof TypeParameterSymbol parameter
                ? parameter.bound()
                : array.element();
        return new TypeSymbol.Array(element, array.ranks()).displayName();
    }

    /// A direct parameter is adapted at the call boundary; a constructed application's type
    /// arguments disappear from the descriptor altogether; a type-parameter-dependent array is
    /// decided against the call's own argument by [#arrayCarrier]. Other containing forms stay
    /// gated until their representation has an equally explicit rule.
    ///
    /// A tuple is stable for the plainest possible reason: its descriptor does not
    /// mention its elements at all. Every V# tuple reaches a descriptor position as
    /// `Ljava/lang/Object;` and is re-established by the `checkcast` to `VsTupleN` that each
    /// element access already emits, so `(T, T)` and `(int, int)` are the same JVM signature and
    /// there is nothing to approximate.
    /// A `Nullable` is stable for the same reason a tuple is: `int?` already reaches
    /// every descriptor position as `Ljava/lang/Object;` - a boxed value or `null` - so `T?`
    /// and `int?` are one JVM signature and `HasValue`/`Value` read the carrier they always
    /// read. The `where T : struct` that makes `T?` meaningful is enforced since that rule, which is
    /// what lets this be admitted rather than guessed at.
    private static boolean hasStableGenericCarrier(TypeSymbol type) {
        return type instanceof TypeParameterSymbol || type instanceof TypeSymbol.Constructed
                || type instanceof TypeSymbol.Tuple || type instanceof TypeSymbol.Nullable;
    }

    /// Expanded params binding has already collapsed loose values into the actual array passed
    /// to the JVM. Inspect that bound argument rather than the source-specialized parameter: for
    /// `Arrays.AsList(1, 2)` the former is the required `Object[]`, while the latter remains
    /// `int[]` as the source-level `T[]` substitution.
    private static TypeSymbol javaArgumentCarrier(BoundExpression.Call call,
            FunctionSymbol specialization, int parameterOrdinal) {
        for (int index = 0; index < call.arguments().size(); index++) {
            if (call.argumentParameterOrdinals().get(index) == parameterOrdinal) {
                return call.arguments().get(index).type();
            }
        }
        return specialization.parameters().get(parameterOrdinal).type();
    }

    /// Whether a source-specialized Java parameter can be adapted to the descriptor-exact JVM
    /// declaration. Ordinary conversions and direct boxing are supported. Reference arrays are
    /// covariant, but a primitive array can never stand in for an erased `Object[]`.
    private static boolean compatibleJavaParameterCarrier(TypeSymbol declared,
            TypeSymbol specialized) {
        if (declared.equals(specialized)) {
            return true;
        }
        if (declared instanceof TypeSymbol.Array declaredArray
                && specialized instanceof TypeSymbol.Array specializedArray
                && declaredArray.ranks().equals(specializedArray.ranks())) {
            return declaredArray.element().jvmTypeKind() == JvmTypeKind.REFERENCE
                    && specializedArray.element().jvmTypeKind() == JvmTypeKind.REFERENCE;
        }
        return Conversions.classify(specialized, declared).isImplicit();
    }

    /// A Java generic result may be narrowed from its descriptor by `checkcast`, or unboxed
    /// from a direct reference carrier. Array specialization still requires reference leaves;
    /// an erased `Object[]` cannot truthfully become an `int[]`.
    private static boolean compatibleJavaReturnCarrier(TypeSymbol declared,
            TypeSymbol specialized) {
        if (declared.equals(specialized)) {
            return true;
        }
        if (declared instanceof TypeSymbol.Array declaredArray
                && specialized instanceof TypeSymbol.Array specializedArray
                && declaredArray.ranks().equals(specializedArray.ranks())) {
            return declaredArray.element().jvmTypeKind() == JvmTypeKind.REFERENCE
                    && specializedArray.element().jvmTypeKind() == JvmTypeKind.REFERENCE;
        }
        return declared.jvmTypeKind() == JvmTypeKind.REFERENCE;
    }

    private static boolean containsTypeParameter(TypeSymbol type) {
        return switch (type) {
            case TypeParameterSymbol ignored -> true;
            case TypeSymbol.Array array -> containsTypeParameter(array.element());
            case TypeSymbol.Tuple tuple -> tuple.elements().stream()
                    .anyMatch(element -> containsTypeParameter(element.type()));
            case TypeSymbol.Nullable nullable -> containsTypeParameter(nullable.element());
            case TypeSymbol.Ref ref -> containsTypeParameter(ref.element());
            case TypeSymbol.Constructed constructed -> constructed.arguments().stream()
                    .anyMatch(Compilation::containsTypeParameter);
            case TypeSymbol.Function function -> containsTypeParameter(function.returns())
                    || function.parameters().stream().anyMatch(Compilation::containsTypeParameter);
            default -> false;
        };
    }

    private CompilationResult result(Phase reached, DiagnosticBag diagnostics,


            List<UnitAnalysis> units) {
        return new CompilationResult(reached, diagnostics.all(), units);
    }

    private List<UnitAnalysis> parsed(List<SourceFile> allSources, List<AuxiliarySyntax.CompilationUnit> trees) {
        List<UnitAnalysis> units = new ArrayList<>(allSources.size());
        for (int index = 0; index < allSources.size(); index++) {
            units.add(new UnitAnalysis.Parsed(allSources.get(index), trees.get(index)));
        }
        return units;
    }

    private List<UnitAnalysis> declared(List<SourceFile> allSources, List<AuxiliarySyntax.CompilationUnit> trees,
            List<SemanticModel> models) {
        List<UnitAnalysis> units = new ArrayList<>(models.size());
        for (int index = 0; index < models.size(); index++) {
            units.add(new UnitAnalysis.Declared(
                    allSources.get(index), trees.get(index), models.get(index)));
        }
        return units;
    }

    private List<UnitAnalysis> bound(List<SourceFile> allSources, List<AuxiliarySyntax.CompilationUnit> trees,
            List<SemanticModel> models, List<ExpressionBinding> bindings) {
        List<UnitAnalysis> units = new ArrayList<>(bindings.size());
        for (int index = 0; index < bindings.size(); index++) {
            units.add(new UnitAnalysis.Bound(allSources.get(index), trees.get(index),
                    models.get(index), bindings.get(index)));
        }
        return units;
    }

    @Override
    public String toString() {
        return "Compilation[" + sources.size() + " source(s)]";
    }
}
