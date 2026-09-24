package vsharp.tests.semantics;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import vsharp.compiler.api.Compilation;
import vsharp.compiler.api.CompilationResult;
import vsharp.compiler.semantics.binding.JavaInterop;
import vsharp.compiler.semantics.symbols.FieldSymbol;
import vsharp.compiler.semantics.symbols.FunctionSymbol;
import vsharp.compiler.semantics.symbols.NamedTypeSymbol;
import vsharp.compiler.semantics.symbols.Symbol;
import vsharp.compiler.semantics.symbols.TypeParameterSymbol;
import vsharp.compiler.semantics.types.BuiltinType;
import vsharp.compiler.semantics.types.TypeSymbol;
import vsharp.compiler.source.SourceFile;
import vsharp.compiler.syntax.SyntaxKind;
import vsharp.tests.TestSources;
import vsharp.testkit.Assert;
import vsharp.testkit.TestRegistry;
import vsharp.testkit.TestSuite;

/// Java module-path type discovery: end-to-end name resolution plus the exact descriptor
/// mapping every later phase depends on.
public final class InteropTests implements TestSuite {

    @Override
    public String suiteName() {
        return "semantic.interop";
    }

    @Override
    public void register(TestRegistry registry) {
        registry.test("a rebuilt jar is seen, not served from the class-file cache",
                this::rebuiltJarIsNotStale);
        registry.test("resolve java.lang.AutoCloseable dynamically",
                this::resolveJavaLangAutoCloseable);
        registry.test("every module of the installed JDK is nameable, resolved or not",
                this::wholeJdkIsReachable);
        registry.test("class and interface kinds are distinguished", this::declaredKinds);
        registry.test("absent types resolve to nothing, repeatedly", this::absentTypes);
        registry.test("malformed type names never throw", this::malformedNames);
        registry.test("primitive descriptors map by signedness", this::primitiveDescriptors);
        registry.test("array descriptors map to jagged array types", this::arrayDescriptors);
        registry.test("initialisers and non-public members are hidden", this::hiddenMembers);
        registry.test("bridge methods are not exposed as overloads", this::bridgeMethods);
        registry.test("public fields retain static and readonly metadata", this::publicFields);
        registry.test("public constructors retain descriptor parameter types",
                this::publicConstructors);
        registry.test("construction remains unavailable for V# declarations",
                this::vsharpConstructionRemainsExcluded);
        registry.test("throw and catch types must map to java.lang.Throwable",
                this::throwableTypesRequired);
        registry.test("discovery is deterministic across instances", this::determinism);
        registry.test("superclass members are inherited and named after the inheriting type",
                this::inheritedMembers);
        registry.test("overrides and hidden fields are collected once, derived-before-base",
                this::inheritanceDeduplication);
        registry.test("interfaces inherit java.lang.Object members",
                this::interfacesInheritObjectMembers);
        registry.test("superinterface members are inherited, statics are not",
                this::superinterfaceMembersAreInherited);
        registry.test("member name translation is a bounded first-character rule",
                this::memberNameTranslation);
        registry.test("the JDK's own camelCase spelling is refused", this::javaCasingIsMandatory);
        registry.test("translation does not reach V# declarations or unmapped names",
                this::casingRuleIsScopedToJava);
        registry.test("an imported Java package supplies simple type and static-owner names",
                this::importedJavaPackages);
        registry.test("a name two imported packages supply is ambiguous, not order-dependent",
                this::ambiguousImportsAreRefused);
        registry.test("a missing member of a resolved Java type is a member diagnostic",
                this::missingJavaMemberIsAMemberDiagnostic);
        registry.test("an unknown type in a known Java package is a namespace diagnostic",
                this::missingJavaTypeIsANamespaceDiagnostic);
        registry.test("a constructor arity failure names the type, not a member",
                this::constructorArityDiagnostic);
        registry.test("overloads that erase to one JVM method are refused",
                this::erasedSignatureClash);
        registry.test("a fully qualified type name is refused in source",
                this::qualifiedTypeNamesAreRefused);
        registry.test("a discovered type carries its transitive supertype closure",
                this::supertypeClosure);
        registry.test("Java reference conversions follow the JVM hierarchy",
                this::javaReferenceConversions);
        registry.test("types written inside expressions reach the Java module path",
                this::javaTypesInExpressionPositions);
        registry.test("java.lang.Object and java.lang.String are the keyword types",
                this::canonicalTypesAreAliases);
        registry.test("ACC_VARARGS marks the trailing parameter as params",
                this::varargsBecomeParams);
        registry.test("string reaches the interfaces java.lang.String implements",
                this::keywordTypesReachTheirHierarchy);
        registry.test("Java generic signatures preserve exact source types",
                this::genericSignaturesPreserveTypes);
        registry.test("foreach element types come from the Java hierarchy",
                this::foreachElementTypes);
        registry.test("a non-enumerable foreach collection is a positioned diagnostic",
                this::foreachNotEnumerable);
        registry.test("an import naming no package or namespace is refused",
                this::unknownImportsAreRefused);
        registry.test("a raw Java generic needs a cast to become parameterized",
                this::rawToParameterizedNeedsACast);
        registry.test("a using inside a namespace body is scoped to it",
                this::namespaceScopedImports);
        registry.test("typeof a Java type is the interoperable java.lang.Class",
                this::javaTypeofIsClass);
        registry.test("a lambda converts to a Java functional interface",
                this::lambdaConvertsToFunctionalInterface);
        registry.test("a lambda whose body does not fit the abstract method is refused",
                this::lambdaReturnTypeIsChecked);
        registry.test("the lambda forms this build cannot emit are named diagnostics",
                this::refusedLambdaFormsAreDiagnosed);
        registry.test("a wildcard functional parameter projects to its bound",
                this::wildcardFunctionalParametersProject);
        registry.test("a type argument only the target supplies is closed and bound-checked",
                this::contextOnlyInferenceIsClosedAgainstItsTarget);
        registry.test("a wildcard is variant and a plain type argument is not",
                this::wildcardVarianceDoesNotLeakIntoPlainGenerics);
        registry.test("a lambda parameter is typed from the call's own target",
                this::lambdaParameterIsTypedFromTheCallTarget);
        registry.test("invoking a non-method member is diagnosed, never a crash",
                this::invokingANonInvocableMemberIsDiagnosed);
        registry.test("an open type argument no target closes is CS0411",
                this::openTypeArgumentWithoutATargetIsDiagnosed);
        registry.test("a type parameter only a lambda can supply is inferred from its body",
                this::lambdaSuppliesTypeArgument);
        registry.test("an explicitly typed lambda closes a static generic method's parameter",
                this::writtenLambdaParametersCloseInference);
        registry.test("a value-producing lambda separates Callable from Runnable",
                this::valueProducingLambdaPrefersAValueTarget);
        registry.test("a boxed carrier is the keyword type in a type argument",
                this::boxedCarriersAreKeywordTypeArguments);
        registry.test("a functional interface keeps its wildcards outside a lambda",
                this::functionalInterfacesStayVariant);
        registry.test("a call may write the type arguments nothing else can supply",
                this::writtenTypeArgumentsCloseACall);
    }

    /// `List<int>` and `List<java.lang.Integer>` are one type. V# boxes every value it
    /// puts in a generic position and the argument erases away, so the JVM sees the same
    /// `List` either way - exactly as C# gives `int` and `System.Int32` one meaning. Before
    /// this, `IntStream.Boxed().ToList()` was a `List<java.lang.Integer>` assignable to
    /// nothing a V# program can spell, and every primitive stream ended in VS0029.
    private void boxedCarriersAreKeywordTypeArguments() {
        CompilationResult result = Compilation.of(List.of(TestSources.styled("Boxed.vs", """
                using java.lang;
                using java.util;
                using java.util.stream;
                static class C {
                    static List<int> Run() {
                        return IntStream.Of(1, 2, 3).Map((int n) => n * 2).Boxed().ToList();
                    }
                    static List<Integer> Spelled(List<int> values) {
                        return values;
                    }
                    static Map<string, long> Counts(Map<string, Long> counts) {
                        return counts;
                    }
                }
                """))).analyze();
        Assert.equalList(List.of(), result.diagnostics().stream()
                .map(diagnostic -> diagnostic.code().toString()).toList(),
                "both spellings of a boxed type argument denote one type");
    }

    /// JLS 9.9's non-wildcard parameterization exists to type a *lambda's* parameters, so
    /// it is applied where a lambda is typed and not baked into the type. Projecting it into
    /// the type silently made every functional interface invariant - and `Iterable` is one, so
    /// `String.Join(sep, List<string>)` and `Files.Write(path, List<string>)` were refused for
    /// a target `Iterable<? extends CharSequence>` that had become `Iterable<CharSequence>`.
    /// The lambda side must keep working, which is the second half of the test.
    private void functionalInterfacesStayVariant() {
        CompilationResult result = Compilation.of(List.of(TestSources.styled("Variance.vs", """
                using java.lang;
                using java.nio.file;
                using java.util;
                static class C {
                    static string Join(List<string> words) {
                        return String.Join("-", words);
                    }
                    static void Save(Path file, List<string> lines) {
                        Files.Write(file, lines);
                    }
                    static void Sort(List<string> words) {
                        Collections.Sort(words, (a, b) => a.Length - b.Length);
                    }
                }
                """))).analyze();
        Assert.equalList(List.of(), result.diagnostics().stream()
                .map(diagnostic -> diagnostic.code().toString()).toList(),
                "a wildcard-declared Iterable admits a covariant list, and a lambda still types");
    }

    /// `Optional.Empty<string>()` has no argument to infer from, so the type arguments
    /// the call writes are the only thing that can close it. They were parsed and ignored,
    /// which both made such a call unwritable - the Java static path refused to resolve a
    /// receiver whose member carried them - and let a wrong one bind to something else.
    private void writtenTypeArgumentsCloseACall() {
        CompilationResult closed = Compilation.of(List.of(TestSources.styled("Written.vs", """
                using java.util;
                static class C {
                    static bool Run() {
                        Optional<string> empty = Optional.Empty<string>();
                        List<string> none = Collections.EmptyList<string>();
                        return empty.IsPresent() || none.Size() > 0;
                    }
                }
                """))).analyze();
        Assert.equalList(List.of(), closed.diagnostics().stream()
                .map(diagnostic -> diagnostic.code().toString()).toList(),
                "a written type argument closes a call no argument could");

        CompilationResult wrongArity = Compilation.of(List.of(TestSources.styled("Arity.vs", """
                using java.util;
                static class C {
                    static void Run() {
                        Optional.Empty<string, int>();
                    }
                }
                """))).analyze();
        Assert.equalList(List.of("VS0305"), wrongArity.diagnostics().stream()
                .map(diagnostic -> diagnostic.code().toString()).toList(),
                "a wrong type-argument count names the arity");

        // The written argument is applied, not ignored: the call's own argument must now
        // convert to it, which is the failure a silently inferred `T` used to hide.
        CompilationResult applied = Compilation.of(List.of(TestSources.styled("Applied.vs", """
                static class C {
                    static T Echo<T>(T value) => value;
                    static string Run() => Echo<string>(3);
                }
                """))).analyze();
        Assert.equalList(List.of("VS1503"), applied.diagnostics().stream()
                .map(diagnostic -> diagnostic.code().toString()).toList(),
                "a written type argument types the parameter it names");
    }

    /// `ExecutorService.Submit` declares `Callable<T>` beside `Runnable`, and the JDK
    /// expects the lambda itself to separate them. Two rules do it, both of which C# and the
    /// JLS state identically. A void-returning target takes an expression body only when that
    /// expression is a *statement* expression, which is syntactic; and when both still apply,
    /// the value-returning target is the better one. Without them `Submit(() => "done")` was
    /// ambiguous - the plainest use of a virtual-thread executor was unwritable.
    ///
    /// The third case is the one that must not be silent: a body producing nothing cannot
    /// close `T`, and emitting it produced a `BootstrapMethodError` at launch rather than a
    /// diagnostic.
    private void valueProducingLambdaPrefersAValueTarget() {
        CompilationResult chosen = Compilation.of(List.of(TestSources.styled("Submit.vs", """
                using java.util;
                using java.util.concurrent;
                static class C {
                    static string Run(ExecutorService pool) {
                        Future<string> answer = pool.Submit(() => "done");
                        return await answer;
                    }
                    static void Fire(ExecutorService pool, List<string> log) {
                        pool.Execute(() => log.Add("x"));
                    }
                }
                """))).analyze();
        Assert.equalList(List.of(), chosen.diagnostics().stream()
                .map(diagnostic -> diagnostic.code().toString()).toList(),
                "a value body chooses Callable and a statement body still reaches Runnable");

        CompilationResult voidBody = Compilation.of(List.of(TestSources.styled("Void.vs", """
                using System;
                using java.util.concurrent;
                static class C {
                    static void Run(ExecutorService pool) {
                        pool.Submit(() => Console.WriteLine("x"));
                    }
                }
                """))).analyze();
        Assert.equalList(List.of("VS0029"), voidBody.diagnostics().stream()
                .map(diagnostic -> diagnostic.code().toString()).toList(),
                "a body that produces nothing cannot close the target's type parameter");
    }

    /// `Collectors.GroupingBy` has no receiver, and `T` appears nowhere but the
    /// classifier's own parameter, so nothing but the lambda can say what it is. Before this,
    /// every `Collectors` aggregation reported VS1503 against `Function&lt;T, K&gt;` - stream
    /// grouping, counting and `ToMap` were unwritable. An explicitly typed lambda is read as an
    /// inference source: the written type closes `T`, and the bound body then closes `K`
    /// through that rule, which is what makes the collector's own result type finite.
    private void writtenLambdaParametersCloseInference() {
        CompilationResult grouping = Compilation.of(List.of(TestSources.styled("Group.vs", """
                using java.util;
                using java.util.stream;
                static class C {
                    static Map<int, List<string>> Run(List<string> words) {
                        return words.Stream().Collect(Collectors.GroupingBy((string w) => w.Length));
                    }
                    static Map<string, int> Pairs(List<string> words) {
                        return words.Stream().Collect(
                                Collectors.ToMap((string w) => w, (string w) => w.Length));
                    }
                }
                """))).analyze();
        Assert.equalList(List.of(), grouping.diagnostics().stream()
                .map(diagnostic -> diagnostic.code().toString()).toList(),
                "a written lambda parameter closes a static generic method's type parameter");

        // The written type is a constraint like any other, so a wrong one must still fail -
        // the seam may not become a way of forcing a candidate that does not apply.
        CompilationResult wrong = Compilation.of(List.of(TestSources.styled("Wrong.vs", """
                using java.util;
                using java.util.stream;
                static class C {
                    static Map<int, List<string>> Run(List<string> words) {
                        return words.Stream().Collect(Collectors.GroupingBy((int w) => w));
                    }
                }
                """))).analyze();
        Assert.isTrue(wrong.diagnostics().stream().anyMatch(diagnostic -> diagnostic.isError()),
                "a written parameter type that the stream cannot supply is still refused");
    }

    /// the compiler is a named module, so its own graph resolves `java.base` and little
    /// else. Deriving the visible JDK from that graph made `java.net.http`, `java.sql` and
    /// `java.xml` report CS0246 for types that plainly exist - the single largest interop hole
    /// measured. The launch now resolves the default root set, and the
    /// installed image (not the compiler's dependencies) decides what a program may name.
    ///
    /// A package that exists in the image but is *not* resolved is an environment fault, not a
    /// spelling one, and says so with its own code; a package that exists nowhere keeps CS0246.
    private void wholeJdkIsReachable() {
        CompilationResult beyondJavaBase = Compilation.of(List.of(TestSources.styled("Jdk.vs", """
                using java.net;
                using java.net.http;
                using java.sql;
                static class C {
                    static string Run() {
                        HttpRequest request = HttpRequest.NewBuilder()
                                .Uri(URI.Create("http://localhost/")).Build();
                        Timestamp stamp = Timestamp.ValueOf("2026-09-14 10:00:00");
                        return request.Method() + stamp.GetTime();
                    }
                }
                """))).analyze();
        Assert.equalList(List.of(), beyondJavaBase.diagnostics().stream()
                .map(diagnostic -> diagnostic.code().toString()).toList(),
                "modules outside java.base resolve like java.base does");

        // An incubator module is the one kind the default root set deliberately leaves out.
        // Which one exists is a property of the installed image, so the expectation is read
        // from the image rather than hardcoded: present but unresolved is VS20010, absent
        // altogether is the ordinary CS0246.
        String incubating = JavaInterop.unresolvedSystemModule("jdk.incubator.vector");
        CompilationResult unresolved = Compilation.of(List.of(TestSources.styled("Inc.vs", """
                using jdk.incubator.vector;
                static class C {
                    static int Run() => 1;
                }
                """))).analyze();
        Assert.equalList(List.of(incubating == null ? "VS0246" : "VS20010"),
                unresolved.diagnostics().stream()
                        .map(diagnostic -> diagnostic.code().toString()).toList(),
                "an unresolved system module is reported as the environment fault it is");

        CompilationResult absent = Compilation.of(List.of(TestSources.styled("Gone.vs", """
                using com.nosuch.pkg;
                static class C {
                    static int Run() => 1;
                }
                """))).analyze();
        Assert.equalList(List.of("VS0246"), absent.diagnostics().stream()
                .map(diagnostic -> diagnostic.code().toString()).toList(),
                "a package no image publishes stays CS0246");
    }

    /// `Stream.Map`'s `R` appears nowhere an ordinary argument could reach it - only the
    /// lambda body can say what it is. Inference leaves it open and the bound body closes it,
    /// so the call's result is `List<int>` here rather than the raw fallback's `List<object>`.
    /// A body that fails to bind must report only its own diagnostic: a result type still
    /// mentioning `R` would name a type parameter the user never wrote.
    private void lambdaSuppliesTypeArgument() {
        CompilationResult inferred = Compilation.of(List.of(TestSources.styled("Map.vs", """
                using java.util;
                List<string> names = new ArrayList<string>();
                List<int> lengths = names.Stream().Map(n => n.Length).ToList();
                """))).analyze();
        Assert.equalList(List.of(), inferred.diagnostics().stream()
                .map(diagnostic -> diagnostic.code().toString()).toList(),
                "a lambda body supplies the open type argument");

        CompilationResult broken = Compilation.of(List.of(TestSources.styled("Bad.vs", """
                using java.util;
                List<string> names = new ArrayList<string>();
                List<int> lengths = names.Stream().Map(n => n.Nope()).ToList();
                """))).analyze();
        Assert.equalList(List.of("VS0117"), broken.diagnostics().stream()
                .map(diagnostic -> diagnostic.code().toString()).toList(),
                "a failed lambda body does not cascade an open type parameter");
    }

    /// almost every JDK functional parameter is wildcard-declared, so without JLS 9.9's
    /// non-wildcard parameterization a lambda handed straight to `Collections.Sort` or
    /// `Stream.Filter` would type its parameters `object` and refuse every member call. The
    /// projection is deliberately confined to functional interfaces: `Collection<? extends E>`
    /// keeps its erasure fallback, because projecting an invariant argument there would narrow
    /// assignments Java accepts.
    private void wildcardFunctionalParametersProject() {
        CompilationResult result = Compilation.of(List.of(TestSources.styled("Wild.vs", """
                using java.util;
                List<string> names = new ArrayList<string>();
                Collections.Sort(names, (a, b) => a.Length - b.Length);
                names.RemoveIf(n => n.Length < 2);
                """))).analyze();
        Assert.equalList(List.of(), result.diagnostics().stream()
                .map(diagnostic -> diagnostic.code().toString()).toList(),
                "wildcard functional parameters accept a lambda directly");
    }

    /// a lambda has no type of its own, so a Java functional interface is what gives it
    /// one. The abstract method's substituted signature - not its erasure - types the
    /// parameters, which is why `s.Length` binds against `string` rather than `object`.
    private void lambdaConvertsToFunctionalInterface() {
        CompilationResult result = Compilation.of(List.of(TestSources.styled("Lambda.vs", """
                using java.lang;
                using java.util.function;
                using System;
                Predicate<string> longer = s => s.Length > 3;
                Supplier<string> greet = () => "hi";
                Runnable task = () => Console.WriteLine("ran");
                """))).analyze();
        Assert.equalList(List.of(), result.diagnostics().stream()
                .map(diagnostic -> diagnostic.code().toString()).toList(),
                "lambda to functional interface binds clean");
    }

    /// `Comparator.NaturalOrder()` mentions `T` in no parameter, so only the position it is
    /// converted to can say what `T` is. Closing it there is a real inference step and
    /// is checked against the declared bound, so it accepts what javac accepts and refuses
    /// what javac refuses - `Comparator<Random>` would have faulted inside `TimSort` with a
    /// `ClassCastException` the compiler had promised away.
    private void contextOnlyInferenceIsClosedAgainstItsTarget() {
        CompilationResult closed = Compilation.of(List.of(TestSources.styled("Closed.vs", """
                using java.util;

                static class Closed
                {
                    static void Main()
                    {
                        ArrayList<string> names = new ArrayList<string>();
                        names.Sort(Comparator.NaturalOrder());
                    }
                }
                """))).analyze();
        Assert.equalList(List.of(), closed.diagnostics().stream()
                .map(diagnostic -> diagnostic.code().toString()).toList(),
                "a target closes a type argument no argument mentions");

        CompilationResult violated = Compilation.of(List.of(TestSources.styled("Bound.vs", """
                using java.util;

                static class Bound
                {
                    static void Main()
                    {
                        ArrayList<Random> items = new ArrayList<Random>();
                        items.Sort(Comparator.NaturalOrder());
                    }
                }
                """))).analyze();
        Assert.equalList(List.of("VS1503"), violated.diagnostics().stream()
                .map(diagnostic -> diagnostic.code().toString()).toList(),
                "closing against a target that violates the bound is refused at compile time");
    }

    /// The two halves of that rule, which must hold together or the model is wrong in one direction.
    ///
    /// `Collection<? extends E>` is variant because Java wrote a wildcard there, so adding a
    /// `ArrayList<string>` to a `ArrayList<object>` is accepted exactly as javac accepts it -
    /// projecting the wildcard away would have refused it. A plain type argument stays
    /// invariant, so assigning `ArrayList<string>` to `ArrayList<object>` is still refused -
    /// admitting it would be heap pollution javac rejects. Variance appears only where the
    /// class file put it.
    private void wildcardVarianceDoesNotLeakIntoPlainGenerics() {
        CompilationResult covariant = Compilation.of(List.of(TestSources.styled("Covariant.vs", """
                using java.util;

                static class Covariant
                {
                    static void Main()
                    {
                        ArrayList<string> src = new ArrayList<string>();
                        ArrayList<object> dst = new ArrayList<object>();
                        dst.AddAll(src);
                    }
                }
                """))).analyze();
        Assert.equalList(List.of(), covariant.diagnostics().stream()
                .map(diagnostic -> diagnostic.code().toString()).toList(),
                "a wildcard parameter accepts the subtype argument Java accepts");

        CompilationResult invariant = Compilation.of(List.of(TestSources.styled("Invariant.vs", """
                using java.util;

                static class Invariant
                {
                    static void Main()
                    {
                        ArrayList<string> src = new ArrayList<string>();
                        ArrayList<object> bad = src;
                    }
                }
                """))).analyze();
        Assert.equalList(List.of("VS0029"), invariant.diagnostics().stream()
                .map(diagnostic -> diagnostic.code().toString()).toList(),
                "a plain type argument stays invariant, as it is in Java");
    }

    /// `Comparator.ComparingInt` declares `<T> Comparator<T> comparingInt(ToIntFunction<? super
    /// T>)`: the only argument is the lambda whose parameter `T` types, so no argument can
    /// supply `T` and the body has nothing to bind against. The declaration being initialised
    /// is the only source, and threading it into the call is what types `n` as `string`.
    /// Overload resolution must therefore accept a parameter still carrying an open `T` - it is
    /// closable, not unrepresentable - while materialisation still demands an exact type.
    private void lambdaParameterIsTypedFromTheCallTarget() {
        CompilationResult typed = Compilation.of(List.of(TestSources.styled("Typed.vs", """
                using System;
                using java.util;

                static class Typed
                {
                    static void Main()
                    {
                        Comparator<string> byLength = Comparator.ComparingInt(n => n.Length);
                        Console.WriteLine(byLength.Compare("bbb", "a"));
                    }
                }
                """))).analyze();
        Assert.equalList(List.of(), typed.diagnostics().stream()
                .map(diagnostic -> diagnostic.code().toString()).toList(),
                "the call's target types a lambda parameter no argument could");
    }

    /// Invoking a property used to produce an Error-typed deferred node that reported nothing
    /// and whose only syntax was the invocation itself, so lowering resolved it back to itself
    /// and recursed until the stack ended: `s.Length()` crashed the compiler with
    /// `StackOverflowError` rather than diagnosing malformed input. Recovery is sound
    /// only once the diagnostic exists.
    private void invokingANonInvocableMemberIsDiagnosed() {
        CompilationResult invoked = Compilation.of(List.of(TestSources.styled("Invoked.vs", """
                using System;
                static class Invoked
                {
                    static void Main()
                    {
                        string s = "abc";
                        int k = s.Length();
                        Console.WriteLine(k);
                    }
                }
                """))).analyze();
        Assert.equalList(List.of("VS1955"), invoked.diagnostics().stream()
                .map(diagnostic -> diagnostic.code().toString()).toList(),
                "a non-invocable member is one diagnostic, not a compiler crash");
    }

    /// An open result that reaches no target at all is the usage CS0411 names. `var` cannot
    /// close it, and admitting it would put a type parameter no declaration owns into the
    /// local's type; the local is typed as an error so the fault is reported exactly once.
    private void openTypeArgumentWithoutATargetIsDiagnosed() {
        CompilationResult open = Compilation.of(List.of(TestSources.styled("Open.vs", """
                using System;
                using java.util;

                static class Open
                {
                    static void Main()
                    {
                        var comparator = Comparator.NaturalOrder();
                        Console.WriteLine(comparator.Compare("a", "b"));
                    }
                }
                """))).analyze();
        Assert.equalList(List.of("VS0411"), open.diagnostics().stream()
                .map(diagnostic -> diagnostic.code().toString()).toList(),
                "an untargeted open type argument is reported once, without cascading");
    }

    /// The body is bound against the target that won, so a body that cannot reach the
    /// abstract method's return type is an ordinary conversion diagnostic rather than a
    /// silently dropped candidate.
    private void lambdaReturnTypeIsChecked() {
        CompilationResult result = Compilation.of(List.of(TestSources.styled("Bad.vs", """
                using java.util;
                Comparator<string> bad = (a, b) => "text";
                """))).analyze();
        Assert.equalList(List.of("VS0029"), result.diagnostics().stream()
                .map(diagnostic -> diagnostic.code().toString()).toList(),
                "lambda body return conversion is checked");
    }

    /// Every form this build does not emit is refused by name, never accepted and
    /// mis-emitted: a statement body (it would skip the flow analysis declared callables get)
    /// and a lambda no target type ever claimed. Captures were refused by that rule and are
    /// supported since that rule, so a capturing lambda must now bind clean.
    private void refusedLambdaFormsAreDiagnosed() {
        CompilationResult captures = Compilation.of(List.of(TestSources.styled("Capture.vs", """
                using java.util.function;
                int factor = 3;
                Supplier<int> scaled = () => factor;
                """))).analyze();
        Assert.equalList(List.of(), captures.diagnostics().stream()
                .map(diagnostic -> diagnostic.code().toString()).toList(),
                "a capturing lambda binds clean");

        CompilationResult untargeted = Compilation.of(List.of(TestSources.styled("None.vs", """
                var f = (int x) => x + 1;
                """))).analyze();
        Assert.equal(true, untargeted.diagnostics().stream()
                .anyMatch(diagnostic -> "VS20001".equals(diagnostic.code().toString())),
                "a lambda with no target type is refused");
    }

    /// the design keeps V# source identities in `VsType`, but a module-path operand has an exact
    /// JVM class literal and must be assignable to the `System.Type`/`java.lang.Class` alias.
    private void javaTypeofIsClass() {
        CompilationResult result = Compilation.of(List.of(TestSources.styled("Typeof.vs", """
                using System;
                using java.util;
                static class C
                {
                    static void F()
                    {
                        Type raw = typeof(ArrayList);
                        Type generic = typeof(ArrayList<string>);
                        Type nested = typeof(Map.Entry<string, int>);
                        Type array = typeof(ArrayList[]);
                    }
                }
                """))).analyze();
        Assert.equalList(List.of(), result.diagnostics().stream()
                .map(diagnostic -> diagnostic.code().toString()).toList(),
                "all Java class-literal shapes bind as System.Type");
    }

    /// The implicit half of a raw Java generic converts to a parameterized form only
    /// through an explicit cast. Java's unchecked conversion is a *warning* precisely because
    /// nothing checks it, so admitting it silently would let a wrong element type through with
    /// no cast in the source to point at. The explicit direction is executed by a backend test;
    /// this is the half that must keep failing.
    private void rawToParameterizedNeedsACast() {
        record Case(String body, String expected, String reason) { }
        List<Case> cases = List.of(
                new Case("ArrayList raw = new ArrayList(); ArrayList<string> t = raw;",
                        "VS0029", "raw to parameterized is never implicit"),
                new Case("ArrayList raw = new ArrayList(); ArrayList<string> t = (ArrayList<string>)raw;",
                        "", "an explicit cast is the assertion Java's warning stands for"),
                new Case("ArrayList<string> t = new ArrayList<string>(); ArrayList raw = t;",
                        "", "parameterized to raw stays implicit, as it erases"));
        for (Case testCase : cases) {
            List<String> codes = Compilation.of(List.of(TestSources.styled("Raw.vs",
                    "using java.util;\nstatic class C { static void R() { "
                            + testCase.body() + " } }\n")))
                    .analyze().diagnostics().stream()
                    .map(d -> d.code().toString()).distinct().toList();
            Assert.equalList(testCase.expected().isEmpty() ? List.of()
                    : List.of(testCase.expected()), codes, testCase.reason());
        }
    }

    /// A `using` written inside a namespace body applies within that body, and nowhere else
    ///. Both halves are asserted together because only the pair is the C# rule: the
    /// directive must reach the namespace that wrote it - including a nested one, and including
    /// an alias - and must not reach a sibling, which is exactly what folding these into the
    /// file would do. The directives parsed all along and were discarded at binding, so the
    /// application had to hoist every one of them above its namespace.
    private void namespaceScopedImports() {
        record Case(String source, String expected, String reason) { }
        String inScope = """
                namespace Owning {
                    using java.util;
                    static class Uses { static int R() { return new ArrayList().Size(); } }
                }
                """;
        String nested = """
                namespace Owning {
                    using java.util;
                    namespace Inner {
                        static class Uses { static int R() { return new ArrayList().Size(); } }
                    }
                }
                """;
        String aliased = """
                using java.util;
                namespace Owning {
                    using Names = java.util.ArrayList;
                    static class Uses { static int R() { return new Names().Size(); } }
                }
                """;
        String sibling = """
                namespace Owning {
                    using java.util;
                    static class Uses { static int R() { return new ArrayList().Size(); } }
                }
                namespace Other {
                    static class Leaks { static int R() { return new ArrayList().Size(); } }
                }
                """;
        // A V#-declared name resolves through the imported *scope* chain rather than through
        // Java owner resolution, so it is the half that stayed broken until the design split header
        // binding per part. Both its positive and its isolation case belong here.
        String vsharpName = """
                namespace Owning.Model {
                    static class Shape { static string Name() => "circle"; }
                }
                namespace Owning.Web {
                    using Owning.Model;
                    static class Uses { static string R() { return Shape.Name(); } }
                }
                """;
        String vsharpSibling = """
                namespace Owning.Model {
                    static class Shape { static string Name() => "circle"; }
                }
                namespace Owning.Web {
                    using Owning.Model;
                    static class Uses { static string R() { return Shape.Name(); } }
                }
                namespace Owning.Other {
                    static class Leaks { static string R() { return Shape.Name(); } }
                }
                """;
        List<Case> cases = List.of(
                new Case(inScope, "", "the namespace that wrote the directive"),
                new Case(nested, "", "a namespace nested inside it"),
                new Case(aliased, "", "an alias written in the body"),
                new Case(vsharpName, "", "a V#-declared name through the imported scope chain"),
                new Case(sibling, "VS0246", "a sibling namespace must not see it"),
                new Case(vsharpSibling, "VS0103",
                        "a sibling must not see a V# name either"));
        for (Case testCase : cases) {
            List<String> codes = Compilation.of(List.of(
                    TestSources.styled("Scoped.vs", testCase.source())))
                    .analyze().diagnostics().stream()
                    .map(d -> d.code().toString()).distinct().toList();
            Assert.equalList(testCase.expected().isEmpty() ? List.of()
                    : List.of(testCase.expected()), codes, testCase.reason());
        }
    }

    /// A `using` that names nothing is a source error, not a silent no-op. Before this,
    /// `using java.util.strem;` compiled clean and the typo surfaced later as a missing type at
    /// every use site. A package the module path publishes, an enclosing name of one, a `global`
    /// import and a declared V# namespace all stay legal - only a name nothing can supply fails.
    private void unknownImportsAreRefused() {
        record Case(String directive, String expected, String reason) { }
        List<Case> cases = List.of(
                new Case("using java.util;", "", "a package that exists"),
                new Case("using java;", "", "an enclosing name of a package"),
                new Case("using java.util.concurrent.atomic;", "", "a deeply nested package"),
                new Case("global using java.util;", "", "a global import of a package"),
                new Case("using System;", "", "a declared V# namespace"),
                new Case("using java.util.strem;", "VS0246", "a mistyped package"),
                new Case("using java.nosuchpackage;", "VS0246", "an invented package"),
                new Case("using NoSuch.Namespace;", "VS0246", "an invented namespace"));
        for (Case testCase : cases) {
            List<String> codes = Compilation.of(List.of(TestSources.styled("Using.vs",
                    testCase.directive() + "\nstatic class C { static void R() { } }\n")))
                    .analyze().diagnostics().stream().map(d -> d.code().toString()).toList();
            Assert.equalList(testCase.expected().isEmpty() ? List.of()
                    : List.of(testCase.expected()), codes, testCase.reason());
        }
    }

    /// The three carriers a `foreach` can walk: an array's indexed element, `string`'s
    /// `char`, and the `T` a Java type's `java.lang.Iterable` supertype publishes as viewed
    /// through the receiver. A raw or unbound element erases to `object`, which is the honest
    /// source type - never a dangling type parameter no expression could ever hold.
    private void foreachElementTypes() {
        JavaInterop interop = new JavaInterop();
        Assert.equal(BuiltinType.CHAR, interop.foreachElementType(BuiltinType.STRING).orElse(null),
                "string yields char");
        Assert.equal(BuiltinType.OBJECT,
                interop.foreachElementType(interop.resolveType("java.util.ArrayList")).orElse(null),
                "a raw ArrayList yields object");
        Assert.isTrue(interop.foreachElementType(
                        interop.resolveType("java.lang.StringBuilder")).isEmpty(),
                "a non-Iterable Java type yields no element type");
        Assert.isTrue(interop.isIterable(interop.resolveType("java.util.Set")),
                "an interface reaches Iterable through its superinterfaces");
        Assert.isFalse(interop.isIterable(interop.resolveType("java.util.HashMap")),
                "Map is not Iterable, exactly as Java has it");
    }

    /// C# reports CS1579 for a `foreach` over a value that publishes no enumeration, and V#
    /// must report it rather than reaching a backend that assumed every collection is an
    /// array. `java.util.HashMap` is the realistic case: `foreach` over it needs `KeySet()`.
    private void foreachNotEnumerable() {
        CompilationResult result = Compilation.of(List.of(TestSources.styled("Each.vs", """
                using java.util;
                static class C {
                    static void Walk() {
                        HashMap map = new HashMap();
                        foreach (var entry in map) { }
                    }
                }
                """))).analyze();
        Assert.equalList(List.of("VS1579"), result.diagnostics().stream()
                .map(diagnostic -> diagnostic.code().toString()).toList(),
                "non-enumerable foreach diagnostic");
    }

    /// JDK generic metadata supplies class arity, method inference and inherited
    /// substitutions. Wildcard-bearing declarations fall back as a whole to their erased
    /// descriptor, so partially mapped signatures can never invent a type parameter that no
    /// argument can infer.
    private void genericSignaturesPreserveTypes() {
        JavaInterop interop = new JavaInterop();
        NamedTypeSymbol arrayList = interop.resolveType("java.util.ArrayList");
        Assert.equal(1, arrayList.arity(), "ArrayList declares E");
        Assert.equalList(List.of("E"), interop.getTypeParameters(arrayList).stream()
                .map(TypeParameterSymbol::name).toList(), "class type parameter order");

        FunctionSymbol rawGet = interop.getMembers(arrayList).stream()
                .filter(FunctionSymbol.class::isInstance).map(FunctionSymbol.class::cast)
                .filter(function -> function.name().equals("get"))
                .filter(function -> function.parameters().size() == 1)
                .findFirst().orElseThrow();
        Assert.isTrue(rawGet.returnType() instanceof TypeParameterSymbol,
                "the declaration retains its erased type variable");
        TypeSymbol.Constructed strings = new TypeSymbol.Constructed(arrayList,
                List.of(BuiltinType.STRING));
        FunctionSymbol stringGet = interop.specializeMembers(strings, List.of(rawGet)).stream()
                .map(FunctionSymbol.class::cast).findFirst().orElseThrow();
        Assert.equal(BuiltinType.STRING, stringGet.returnType(),
                "a constructed receiver specializes the result");
        FunctionSymbol erasedGet = interop.erasedDeclaration(stringGet);
        Assert.equal(BuiltinType.OBJECT, erasedGet.returnType(),
                "the JVM declaration retains get's Object result descriptor");
        Assert.equal(rawGet.qualifiedName(), erasedGet.qualifiedName(),
                "the source specialization and JVM declaration name the same member");

        NamedTypeSymbol list = interop.resolveType("java.util.List");
        FunctionSymbol of = interop.getMembers(list).stream()
                .filter(FunctionSymbol.class::isInstance).map(FunctionSymbol.class::cast)
                .filter(function -> function.name().equals("of"))
                .filter(function -> function.parameters().size() == 1)
                .filter(function -> function.parameters().getFirst().modifiers()
                        .contains(SyntaxKind.PARAMS))
                .findFirst().orElseThrow();
        Assert.equal(1, of.typeParameters().size(), "List.of declares an inferable E");
        Assert.isTrue(of.returnType() instanceof TypeSymbol.Constructed,
                "its return retains List<E>, not raw List");

        FunctionSymbol max = interop.getMembers(interop.resolveType("java.util.Collections"))
                .stream().filter(FunctionSymbol.class::isInstance).map(FunctionSymbol.class::cast)
                .filter(function -> function.name().equals("max"))
                .filter(function -> function.parameters().size() == 1)
                .findFirst().orElseThrow();
        Assert.equal(1, max.typeParameters().size(),
                "a wildcard-bearing method keeps its signature");
        Assert.equal(max.typeParameters().getFirst(), max.returnType(),
                "its result is the declared type parameter, not the erased Object");
        TypeSymbol collection = max.parameters().getFirst().type();
        Assert.isTrue(collection instanceof TypeSymbol.Constructed,
                "the parameter keeps Collection<...> rather than erasing to raw Collection");
        TypeSymbol element = ((TypeSymbol.Constructed) collection).arguments().getFirst();
        Assert.isTrue(element instanceof TypeSymbol.Wildcard wildcard
                        && !wildcard.superBound()
                        && wildcard.bound().equals(max.typeParameters().getFirst()),
                "the wildcard is carried as `? extends T`, which is what Java wrote");

        FunctionSymbol valueOf = interop.getMembers(interop.resolveType("java.lang.Enum"))
                .stream().filter(FunctionSymbol.class::isInstance).map(FunctionSymbol.class::cast)
                .filter(function -> function.name().equals("valueOf"))
                .filter(function -> function.parameters().size() == 2)
                .findFirst().orElseThrow();
        Assert.equal(1, valueOf.typeParameters().size(),
                "a bounded method keeps its declared type parameter");
        Assert.equal("java.lang.Enum",
                ((NamedTypeSymbol) valueOf.typeParameters().getFirst().bound()).qualifiedName(),
                "the recorded bound is the erasure the JVM declares, not `object`");
        Assert.equal(valueOf.typeParameters().getFirst(), valueOf.returnType(),
                "the result stays the parameter; its carrier comes from the bound at emission");

        CompilationResult valid = Compilation.of(List.of(TestSources.styled("Generic.vs", """
                using java.util;
                static class Generic {
                    static string Read() {
                        ArrayList<string> values = new ArrayList<string>();
                        values.Add("typed");
                        List<string> widened = values;
                        Optional<string> optional = Optional.Of(values.Get(0));
                        return widened.Get(0) + optional.OrElse("missing");
                    }
                }
                """))).analyze();
        Assert.isFalse(valid.hasErrors(),
                "constructed classes, class substitution and method inference bind together");

        Assert.equalList(List.of("VS1503"), Compilation.of(List.of(TestSources.styled("Wrong.vs", """
                using java.util;
                static class Wrong {
                    static void Add() {
                        ArrayList<string> values = new ArrayList<string>();
                        values.Add(1);
                    }
                }
                """))).analyze().diagnostics().stream().map(d -> d.code().toString()).toList(),
                "the specialized parameter rejects a value of the wrong type");
    }

    /// `string` *is* `java.lang.String`, so it widens to every interface that class implements
    /// and comes back down through an explicit cast. The fact is recorded on the
    /// interface, the only side of the relation that has a symbol, which is what keeps the
    /// conversion engine a pure function of its arguments. An interface `java.lang.String`
    /// does not implement is refused in both directions.
    private void keywordTypesReachTheirHierarchy() {
        JavaInterop interop = new JavaInterop();
        Assert.isTrue(interop.resolveType("java.lang.CharSequence").keywordSubtypes()
                .contains(BuiltinType.STRING), "string is assignable to CharSequence");
        Assert.isTrue(interop.resolveType("java.io.Serializable").keywordSubtypes()
                .contains(BuiltinType.STRING), "and to every other interface String implements");
        Assert.isFalse(interop.resolveType("java.util.List").keywordSubtypes()
                .contains(BuiltinType.STRING), "but not to an unrelated interface");
        Assert.equal(Set.of(), interop.resolveType("java.util.ArrayList").keywordSubtypes(),
                "a class no keyword type is assignable to records none");

        record Case(String body, String expected, String reason) { }
        List<Case> cases = List.of(
                new Case("CharSequence c = \"x\";", "",
                        "string widens to an implemented interface implicitly"),
                new Case("Serializable z = \"x\";", "",
                        "and to any other one"),
                new Case("CharSequence c = \"x\"; string s = (string)c;", "",
                        "the reverse is an explicit cast"),
                new Case("CharSequence c = \"x\"; string s = c;", "VS0029",
                        "the reverse is not implicit"),
                new Case("string s = \"x\"; List l = s;", "VS0029",
                        "an unimplemented interface has no implicit conversion"),
                new Case("string s = \"x\"; List l = (List)s;", "VS0030",
                        "and no explicit one either"));
        for (Case testCase : cases) {
            List<String> codes = Compilation.of(List.of(TestSources.styled("Keyword.vs",
                    "using java.io;\nusing java.lang;\nusing java.util;\n"
                            + "static class C { static void R() { " + testCase.body() + " } }\n")))
                    .analyze().diagnostics().stream().map(d -> d.code().toString()).toList();
            Assert.equalList(testCase.expected().isEmpty() ? List.of()
                    : List.of(testCase.expected()), codes, testCase.reason());
        }
    }

    /// A Java varargs method carries `ACC_VARARGS` and a trailing array parameter, which is
    /// exactly C# `params`, so the discovered parameter is marked and the existing expansion
    /// applies. A method whose trailing parameter is merely an array is not varargs and must
    /// keep binding in normal form only.
    private void varargsBecomeParams() {
        JavaInterop interop = new JavaInterop();
        NamedTypeSymbol list = interop.resolveType("java.util.List");
        FunctionSymbol of = interop.getMembers(list).stream()
                .filter(FunctionSymbol.class::isInstance).map(FunctionSymbol.class::cast)
                .filter(function -> function.name().equals("of"))
                .filter(function -> function.parameters().size() == 1)
                .filter(function -> function.parameters().getFirst().type()
                        instanceof TypeSymbol.Array)
                .findFirst().orElseThrow();
        Assert.isTrue(of.parameters().getLast().modifiers().contains(SyntaxKind.PARAMS),
                "the trailing array parameter of a varargs method is params");
        Assert.isTrue(of.interfaceOwner(), "a member of an interface records its owner kind");

        NamedTypeSymbol arrays = interop.resolveType("java.util.Arrays");
        FunctionSymbol toStringOfInts = interop.getMembers(arrays).stream()
                .filter(FunctionSymbol.class::isInstance).map(FunctionSymbol.class::cast)
                .filter(function -> function.name().equals("toString"))
                .filter(function -> function.parameters().size() == 1)
                .findFirst().orElseThrow();
        Assert.isFalse(toStringOfInts.parameters().getLast().modifiers()
                .contains(SyntaxKind.PARAMS),
                "an ordinary array parameter is not params");
        Assert.isFalse(toStringOfInts.interfaceOwner(), "a member of a class is not interface-owned");

        Assert.equalList(List.of("VS1501"), Compilation.of(List.of(TestSources.styled("Loose.vs", """
                using java.util;
                static class C {
                    static string R() { return Arrays.ToString(1, 2); }
                }
                """))).analyze().diagnostics().stream().map(d -> d.code().toString()).toList(),
                "loose arguments do not expand into a non-varargs array parameter");
    }

    /// One JVM class is one V# type. Reading a descriptor already mapped `Ljava/lang/Object;`
    /// to `object` and `Ljava/lang/String;` to `string`, so a written spelling must agree:
    /// while they were separate module-path symbols, the same class was two V# types that did
    /// not convert to each other, and every `Object`-returning JDK method walked into it
    ///. Construction follows the keyword's documented object-model exclusion.
    private void canonicalTypesAreAliases() {
        Assert.isFalse(Compilation.of(List.of(TestSources.styled("Alias.vs", """
                using java.lang;
                using java.util;
                static class C {
                    static string R() {
                        ArrayList values = new ArrayList();
                        values.Add("only");
                        Object largest = Collections.Max(values);
                        string written = String.ValueOf(largest);
                        object keyword = largest;
                        String round = written;
                        return round + keyword.ToString();
                    }
                }
                """))).analyze().hasErrors(),
                "the keyword and qualified spellings are one type in both directions");

        Assert.equalList(List.of("VS20001"), Compilation.of(List.of(TestSources.styled("New.vs", """
                using java.lang;
                static class C {
                    static void R() { Object o = new Object(); }
                }
                """))).analyze().diagnostics().stream().map(d -> d.code().toString()).toList(),
                "constructing it reports the same object-model exclusion as `new object()`");
    }

    /// The closure a module-path type carries is what the conversion engine answers with, so
    /// it must hold the whole hierarchy: superclasses, directly implemented interfaces, the
    /// superinterfaces of those, and `java.lang.Object` - including for an interface, whose
    /// class file names no superclass at all. A V# declaration carries none.
    private void supertypeClosure() {
        JavaInterop interop = new JavaInterop();
        Set<String> arrayList = interop.resolveType("java.util.ArrayList").javaSupertypes();
        Assert.isTrue(arrayList.contains("java.util.AbstractList"), "direct superclass");
        Assert.isTrue(arrayList.contains("java.util.AbstractCollection"), "transitive superclass");
        Assert.isTrue(arrayList.contains("java.lang.Object"), "the root is always present");
        Assert.isTrue(arrayList.contains("java.util.List"), "directly implemented interface");
        Assert.isTrue(arrayList.contains("java.util.Collection"), "superinterface of an interface");
        Assert.isTrue(arrayList.contains("java.lang.Iterable"), "transitive superinterface");
        Assert.isFalse(arrayList.contains("java.util.ArrayList"), "a type is not its own supertype");
        Assert.isFalse(arrayList.contains("java.util.LinkedList"), "an unrelated sibling is absent");

        Assert.isTrue(interop.resolveType("java.util.List").javaSupertypes()
                .contains("java.lang.Object"),
                "an interface reference is still assignable to Object");
        Assert.equal(Set.of(), interop.resolveType("java.lang.Object").javaSupertypes(),
                "the root has no supertype");

        Assert.equal(Set.of(), new NamedTypeSymbol("S", "S",
                interop.resolveType("java.lang.Object").location(),
                NamedTypeSymbol.DeclaredKind.STRUCT, 0).javaSupertypes(),
                "a V# declaration carries no JVM hierarchy");
    }

    /// Widening to a supertype or implemented interface is implicit, exactly as the JVM
    /// assigns; the reverse needs an explicit cast; and unrelated types convert in neither
    /// direction. The subset's own declarations are untouched, because their closure is empty.
    private void javaReferenceConversions() {
        String header = """
                using java.io;
                using java.lang;
                using java.util;
                static class C {
                """;
        record Case(String body, String expected, String reason) { }
        List<Case> cases = List.of(
                new Case("ArrayList a = new ArrayList(); AbstractList b = a;", "",
                        "subclass to superclass is implicit"),
                new Case("ArrayList a = new ArrayList(); List l = a;", "",
                        "class to implemented interface is implicit"),
                new Case("ArrayList a = new ArrayList(); object o = a;", "",
                        "anything to java.lang.Object is implicit"),
                new Case("List l = null; ArrayList a = (ArrayList)l;", "",
                        "interface to class is an explicit downcast"),
                new Case("List l = null; ArrayList a = l;", "VS0029",
                        "interface to class is not implicit"),
                new Case("ArrayList a = new ArrayList(); StringBuilder b = a;", "VS0029",
                        "unrelated types have no implicit conversion"),
                new Case("ArrayList a = new ArrayList();"
                        + " StringBuilder b = (StringBuilder)a;", "VS0030",
                        "unrelated types have no explicit conversion either"));
        for (Case testCase : cases) {
            List<String> codes = Compilation.of(List.of(TestSources.styled("Conv.vs",
                    header + "    static void R() { " + testCase.body() + " }\n}\n")))
                    .analyze().diagnostics().stream().map(d -> d.code().toString()).toList();
            Assert.equalList(testCase.expected().isEmpty() ? List.of()
                    : List.of(testCase.expected()), codes, testCase.reason());
        }
    }

    /// A cast, `as`, `default(T)` and an array creation are type positions too. Expression
    /// binding resolves single names in the local scope by design, so before the design every one of
    /// them reported `VS0246` for a Java type - even a fully qualified one - which made a
    /// reference conversion impossible to spell explicitly.
    private void javaTypesInExpressionPositions() {
        record Case(String body, String reason) { }
        List<Case> cases = List.of(
                new Case("object o = null; var x = (StringBuilder)o;", "a cast"),
                new Case("object o = null; var x = o as StringBuilder;", "an as"),
                new Case("var x = default(StringBuilder);", "a default operand"),
                new Case("var a = new StringBuilder[2];", "an array element type"));
        for (Case testCase : cases) {
            Assert.isFalse(Compilation.of(List.of(TestSources.styled("Pos.vs",
                    "using java.lang;\nstatic class C { static void R() { "
                            + testCase.body() + " } }\n")))
                    .analyze().hasErrors(), testCase.reason() + " reaches the Java module path");
        }

        List<String> codes = Compilation.of(List.of(TestSources.styled("Absent.vs",
                "static class C { static void R() { object o = null; var x = (NoSuchT)o; } }\n")))
                .analyze().diagnostics().stream().map(d -> d.code().toString()).toList();
        Assert.equalList(List.of("VS0246"), codes,
                "an unresolvable type is still reported exactly once");
    }

    /// `using java.util;` makes a Java package's types available by simple name, in type
    /// positions and as the receiver of a static member. A V# declaration of the same
    /// spelling still wins, because the enclosing chain is searched before any import.
    private void importedJavaPackages() {
        Assert.isFalse(Compilation.of(List.of(TestSources.styled("Types.vs", """
                using java.util;
                static class C {
                    static int Run() {
                        ArrayList values = new ArrayList();
                        values.Add("x");
                        return values.Size();
                    }
                }
                """))).analyze().hasErrors(), "an imported package supplies a simple type name");

        Assert.isFalse(Compilation.of(List.of(TestSources.styled("Static.vs", """
                using java.util;
                static class C {
                    static string Run() {
                        return Arrays.ToString(new int[] { 1, 2 });
                    }
                }
                """))).analyze().hasErrors(), "an imported package supplies a static owner name");

        Assert.isFalse(Compilation.of(List.of(TestSources.styled("Shadow.vs", """
                using java.util;
                namespace N {
                    public struct ArrayList { public int V; }
                    static class C {
                        static int Run() {
                            ArrayList declared = default;
                            return declared.V;
                        }
                    }
                }
                """))).analyze().hasErrors(), "a V# declaration wins over an imported Java type");
    }

    /// Two imported packages answering one simple name is an ambiguity. Resolving it by
    /// `using` order would make the meaning of the program depend on the order of its imports,
    /// which is measurably what happened before `java.util.List` and `java.awt.List` each
    /// won by being imported first. The fully qualified spelling stays unambiguous.
    private void ambiguousImportsAreRefused() {
        String imports = """
                using java.util;
                using java.awt;
                """;
        Assert.equalList(List.of("VS0104"), Compilation.of(List.of(TestSources.styled("Type.vs",
                imports + """
                static class C {
                    static bool Run() {
                        List l = null;
                        return l == null;
                    }
                }
                """))).analyze().diagnostics().stream().map(d -> d.code().toString()).toList(),
                "an ambiguous type position reports once, without a cascade");

        Assert.equalList(List.of("VS0104"), Compilation.of(List.of(TestSources.styled("Owner.vs",
                imports + """
                static class C {
                    static bool Run() {
                        return List.Of(1) != null;
                    }
                }
                """))).analyze().diagnostics().stream().map(d -> d.code().toString()).toList(),
                "an ambiguous static owner reports once, without a cascade");

        // A qualified spelling is no longer writable in source, so an alias is what
        // settles a collision between two imported packages - and it resolves through the very
        // same qualified lookup, which is what this asserts.
        Assert.isFalse(Compilation.of(List.of(TestSources.styled("Aliased.vs", imports + """
                using Rows = java.util.List;
                static class C {
                    static bool Run() {
                        Rows l = null;
                        return l == null;
                    }
                }
                """))).analyze().hasErrors(), "an alias names exactly one type");
    }

    /// Once a receiver names a Java type, a member it does not declare is a missing *member*.
    /// Reporting `VS0103` against the first segment - `java` - named something the author never
    /// asked about; constants such as `MAX_VALUE` reach this path because they are outside the
    /// the design casing translation.
    private void missingJavaMemberIsAMemberDiagnostic() {
        List<String> codes = Compilation.of(List.of(TestSources.styled("Missing.vs", """
                using java.lang;
                static class C {
                    static int Run() {
                        return Integer.MaxValue;
                    }
                }
                """))).analyze().diagnostics().stream().map(d -> d.code().toString()).toList();
        Assert.equalList(List.of("VS0117"), codes,
                "a missing member of a resolved Java type reports VS0117 only");
    }

    /// One segment earlier than the rule's correction: when the *namespace* resolves but the type
    /// after it does not, the failure is that type, not the leading identifier. Checked
    /// against .NET 10, which reports CS0234 at the same column with the same wording.
    private void missingJavaTypeIsANamespaceDiagnostic() {
        List<String> codes = Compilation.of(List.of(TestSources.styled("MissingType.vs", """
                static class C {
                    static string Run() {
                        return java.lang.Nope.Thing;
                    }
                }
                """))).analyze().diagnostics().stream().map(d -> d.code().toString()).toList();
        Assert.equalList(List.of("VS0234"), codes,
                "an unknown type in a known package reports VS0234 only");

        // A package that does not exist at all is still the whole-name failure, so the two
        // diagnostics stay distinguishable rather than one swallowing the other.
        List<String> unknownPackage = Compilation.of(List.of(TestSources.styled("NoPkg.vs", """
                using java.nosuchpackage;
                static class C {
                    static int Run() {
                        return 1;
                    }
                }
                """))).analyze().diagnostics().stream().map(d -> d.code().toString()).toList();
        Assert.equalList(List.of("VS0246"), unknownPackage,
                "an unknown package reports VS0246 only");
    }

    /// A constructor is named by its type, so an argument count no constructor declares is
    /// CS1729 rather than CS1501's member-name shape. Verified against .NET 10.
    private void constructorArityDiagnostic() {
        List<String> codes = Compilation.of(List.of(TestSources.styled("Ctor.vs", """
                using java.util;
                static class C {
                    static int Run() {
                        ArrayList<string> bad = new ArrayList<string>("nope", 1, 2);
                        return bad.Size();
                    }
                }
                """))).analyze().diagnostics().stream().map(d -> d.code().toString()).toList();
        Assert.equalList(List.of("VS1729"), codes,
                "a constructor arity failure reports VS1729 only");
    }

    /// Two overloads C# tells apart can still be one method on the JVM, because a V# enum is a
    /// named `int` and every enum erases to the same descriptor.
    ///
    /// This was found by a real application: three `Name` overloads over three enums compiled
    /// without complaint and produced a class file the JVM refused to load with
    /// `ClassFormatError: duplicate method`. A program that compiles and cannot start is the
    /// worst failure mode available, so the clash is reported at the second declaration.
    ///
    /// The negatives matter as much: an overload on a *non*-enum type is unaffected, and
    /// corelib's `extern` members - which declare a mapping to a runtime target rather than a
    /// method this compiler emits - may still overload on an enum, as `Math.Round` does.
    private void erasedSignatureClash() {
        List<String> codes = Compilation.of(List.of(TestSources.styled("Clash.vs", """
                public enum A { X }
                public enum B { Y }
                static class C {
                    public static string Name(A a) { return "a"; }
                    public static string Name(B b) { return "b"; }
                }
                """))).analyze().diagnostics().stream().map(d -> d.code().toString()).toList();
        Assert.equalList(List.of("VS20011"), codes, "two enum overloads clash");

        Assert.isFalse(Compilation.of(List.of(TestSources.styled("NoClash.vs", """
                public enum A { X }
                static class C {
                    public static string Name(A a) { return "a"; }
                    public static string Name(string s) { return "s"; }
                    public static string Name(A a, int extra) { return "a2"; }
                }
                """))).analyze().hasErrors(),
                "overloads that stay distinct after erasure are accepted");

        // `Math.Round(double, MidpointRounding)` sits beside `Round(double, int)` in corelib;
        // if the rule reached `extern` members, nothing would compile at all.
        Assert.isFalse(Compilation.of(List.of(TestSources.styled("Corelib.vs", """
                using System;
                static class C {
                    public static double Run() { return Math.Round(1.5, 2); }
                }
                """))).analyze().hasErrors(), "corelib's own enum overloads still bind");
    }

    /// A type is named by its simple name and imported, never spelled out.
    ///
    /// A qualified spelling repeats a package path at every use, hides which dependencies a
    /// file has, and cannot be redirected without editing every line that mentions it. The rule
    /// is enforced in all three positions a qualified name can appear: a type, a static-member
    /// receiver on the Java module path, and a namespace-qualified V# type.
    ///
    /// The negatives are what make the rule safe rather than blunt, and each is checked below:
    /// `using` directives, alias targets and `using static` targets are *where* a qualified
    /// name belongs; a nested type reached through an imported simple name is not a qualified
    /// name; and a name that does not resolve at all still gets its own diagnostic.
    private void qualifiedTypeNamesAreRefused() {
        record Case(String source, String expected, String reason) { }
        List<Case> cases = List.of(
                new Case("""
                        static class C {
                            static void R() { java.lang.StringBuilder b = null; }
                        }
                        """, "VS20012", "a qualified type position"),
                new Case("""
                        using System;
                        static class C {
                            static void R() { Console.WriteLine(java.lang.Integer.MAX_VALUE); }
                        }
                        """, "VS20012", "a qualified Java static receiver"),
                new Case("""
                        static class C {
                            static void R() { System.Console.WriteLine("x"); }
                        }
                        """, "VS20012", "a qualified V# static receiver"),
                new Case("""
                        static class C {
                            static java.util.List<string> R() { return null; }
                        }
                        """, "VS20012", "a qualified return type"),
                new Case("""
                        using java.lang;
                        static class C {
                            static void R() { StringBuilder b = null; }
                        }
                        """, "", "an imported simple name"),
                new Case("""
                        using java.util;
                        static class C {
                            static void R() { Map.Entry<string, int> e = null; }
                        }
                        """, "", "a nested type through an imported simple name"),
                new Case("""
                        using Builder = java.lang.StringBuilder;
                        static class C {
                            static void R() { Builder b = null; }
                        }
                        """, "", "an alias target is where a qualified name belongs"),
                new Case("""
                        using static java.lang.Math;
                        static class C {
                            static int R() { return Abs(-1); }
                        }
                        """, "", "a using static target likewise"),
                new Case("""
                        using java.util;
                        using java.util.concurrent;
                        static class C {
                            static void R() { ArrayList a = null; }
                        }
                        """, "", "several imports at once"));
        for (Case testCase : cases) {
            List<String> codes = Compilation.of(List.of(TestSources.styled("Qual.vs",
                    testCase.source()))).analyze().diagnostics().stream()
                    .map(diagnostic -> diagnostic.code().toString()).toList();
            Assert.equalList(testCase.expected().isEmpty() ? List.of()
                    : List.of(testCase.expected()), codes, testCase.reason());
        }
    }

    /// The translation is exactly one character wide, in both directions, and declines to
    /// answer for spellings that are already correct or that carry no letter case at all.
    private void memberNameTranslation() {
        Assert.equal("abs", JavaInterop.jvmMemberName("Abs"), "PascalCase folds to camelCase");
        Assert.equal("toURI", JavaInterop.jvmMemberName("ToURI"),
                "an acronym's remaining letters are untouched");
        Assert.equal("compareTo", JavaInterop.jvmMemberName("CompareTo"), "interior case is kept");
        Assert.isTrue(JavaInterop.jvmMemberName("abs") == null,
                "an already-camelCase name has no translation");
        Assert.isTrue(JavaInterop.jvmMemberName("MAX_VALUE") == null,
                "a constant-style name is not a PascalCase member");
        Assert.isTrue(JavaInterop.jvmMemberName("PI") == null, "a short constant is not folded");
        Assert.equal("xIndex", JavaInterop.jvmMemberName("XIndex"),
                "a name holding a lowercase letter is a member, not a constant");
        Assert.equal("x", JavaInterop.jvmMemberName("X"),
                "a single character carries no constant evidence");
        Assert.isTrue(JavaInterop.jvmMemberName("") == null, "an empty name translates to nothing");

        Assert.equal("Abs", JavaInterop.vsharpMemberName("abs"), "camelCase lifts to PascalCase");
        Assert.equal("ToURI", JavaInterop.vsharpMemberName("toURI"), "the inverse is exact");
        Assert.isTrue(JavaInterop.vsharpMemberName("Abs") == null,
                "an already-PascalCase name has no inverse");
    }

    /// V# spells every member in PascalCase, including borrowed ones, so the JDK's own
    /// spelling is a diagnostic rather than a second accepted name (the design/VS20006).
    private void javaCasingIsMandatory() {
        CompilationResult instanceCall = Compilation.of(List.of(TestSources.styled("Instance.vs", """
                using java.lang;
                static class C {
                    static void Set() {
                        StringBuilder b = new StringBuilder();
                        b.append("x");
                    }
                }
                """))).analyze();
        Assert.equalList(List.of("VS20006"),
                instanceCall.diagnostics().stream().map(d -> d.code().toString()).toList(),
                "an instance member written in camelCase is refused");

        CompilationResult staticCall = Compilation.of(List.of(TestSources.styled("Static.vs", """
                using java.lang;
                static class C {
                    static int Set() {
                        return Math.abs(-5);
                    }
                }
                """))).analyze();
        Assert.equalList(List.of("VS20006"),
                staticCall.diagnostics().stream().map(d -> d.code().toString()).toList(),
                "a static member written in camelCase is refused");

        CompilationResult field = Compilation.of(List.of(TestSources.styled("Field.vs", """
                using java.awt;
                static class C {
                    static int Set() {
                        Point p = new Point(1, 2);
                        return p.x;
                    }
                }
                """))).analyze();
        Assert.equalList(List.of("VS20006"),
                field.diagnostics().stream().map(d -> d.code().toString()).toList(),
                "a field written in the JVM's own spelling is refused");
    }

    /// The rule is a Java-boundary rule. A V# declaration owns its spelling outright, and a
    /// name the JDK does not declare under either casing stays an ordinary missing member.
    private void casingRuleIsScopedToJava() {
        CompilationResult vsharpMember = Compilation.of(List.of(TestSources.styled("Own.vs", """
                struct Holder {
                    public int Value;
                }
                static class C {
                    static int Read(Holder h) {
                        return h.Value;
                    }
                }
                """))).analyze();
        Assert.equalList(List.of(), vsharpMember.diagnostics().stream()
                .map(d -> d.code().toString()).toList(), "V# members are unaffected");

        CompilationResult absent = Compilation.of(List.of(TestSources.styled("Absent.vs", """
                using java.lang;
                static class C {
                    static void Set() {
                        StringBuilder b = new StringBuilder();
                        b.NoSuchMember();
                    }
                }
                """))).analyze();
        Assert.equalList(List.of("VS0117"),
                absent.diagnostics().stream().map(d -> d.code().toString()).toList(),
                "a name absent under both spellings is a missing member, not a casing error");
    }


    /// Class files are cached across compilations, keyed by a signature of the classpath, so
    /// that a language server does not re-read every dependency on every edit. The
    /// whole correctness of that rests on one property: a jar rewritten underneath a *running*
    /// process must be seen, not served from the cache. An editor keeps one process alive for
    /// days while the build rewrites jars under it, so a stale read would report errors about
    /// code the user has already fixed - and would do it only in the editor, never in the CLI,
    /// which is the hardest kind of defect to believe.
    ///
    /// The jar is written twice with a different member each time, and both members must
    /// resolve while they exist. `java.lang.classfile` synthesises the classes, so the test
    /// needs no compiler but this one.
    private void rebuiltJarIsNotStale() {
        Path directory = null;
        try {
            directory = Files.createTempDirectory("vsharp-cache-test");
            Path jar = directory.resolve("library.jar");

            writeJar(jar, "probe/Thing", "First");
            Assert.equalList(List.of(), codesOf(compileAgainst(jar, "First")),
                    "the member present in the first jar resolves");
            Assert.isTrue(!codesOf(compileAgainst(jar, "Second")).isEmpty(),
                    "a member absent from the first jar does not resolve");

            // The rewrite the cache has to notice.
            writeJar(jar, "probe/Thing", "Second");
            Assert.equalList(List.of(), codesOf(compileAgainst(jar, "Second")),
                    "the member added by the rebuilt jar resolves, so nothing was stale");
            Assert.isTrue(!codesOf(compileAgainst(jar, "First")).isEmpty(),
                    "the member the rebuild removed stops resolving");
        } catch (IOException e) {
            throw new IllegalStateException("Could not stage the jar for the cache test", e);
        } finally {
            deleteTree(directory);
        }
    }

    /// Compiles a program that calls `probe.Thing.<member>()` against `jar`.
    private static CompilationResult compileAgainst(Path jar, String member) {
        return Compilation.of(
                List.of(TestSources.styled("Use.vs", """
                        using probe;
                        static class Use {
                            static void F() {
                                Thing.%s();
                            }
                        }
                        """.formatted(member))),
                Set.of(),
                List.of(jar)).analyze();
    }

    private static List<String> codesOf(CompilationResult result) {
        return result.diagnostics().stream()
                .map(diagnostic -> diagnostic.code().toString())
                .toList();
    }

    /// Writes a jar holding one class with one public static no-argument `void` method.
    private static void writeJar(Path jar, String internalName, String member)
            throws IOException {
        ClassDesc owner = ClassDesc.ofInternalName(internalName);
        byte[] bytes = ClassFile.of().build(owner, builder -> builder
                .withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL | ClassFile.ACC_SUPER)
                .withMethod(member, MethodTypeDesc.of(ConstantDescs.CD_void),
                        ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC,
                        method -> method.withCode(CodeBuilder::return_)));
        // Deleted first: writing in place can preserve the previous length, and a jar whose
        // size and timestamp both repeat is exactly the collision the signature must avoid.
        Files.deleteIfExists(jar);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry(internalName + ".class"));
            out.write(bytes);
            out.closeEntry();
        }
    }

    private static void deleteTree(Path directory) {
        if (directory == null) {
            return;
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // A leftover temporary file is not a test failure.
                }
            });
        } catch (IOException ignored) {
            // Nothing to clean.
        }
    }

    private void resolveJavaLangAutoCloseable() {
        String source = """
            using java.lang;
            namespace InteropTest {
                public static class App {
                    public static void Main() {
                        AutoCloseable resource = null;
                        if (resource != null) {
                            resource.Close();
                        }
                    }
                }
            }
            """;

        SourceFile file = TestSources.styled("App.vs", source);
        CompilationResult result = Compilation.of(List.of(file)).analyze();

        Assert.isFalse(result.hasErrors(),
                "should resolve java.lang.AutoCloseable and its close method without errors: "
                        + result.diagnostics());
    }

    private void declaredKinds() {
        JavaInterop interop = new JavaInterop();
        NamedTypeSymbol closeable = interop.resolveType("java.lang.AutoCloseable");
        Assert.notNull(closeable, "java.lang.AutoCloseable resolves");
        Assert.equal(NamedTypeSymbol.DeclaredKind.INTERFACE, closeable.declaredKind(),
                "AutoCloseable is an interface");
        Assert.equal("AutoCloseable", closeable.name(), "simple name");
        Assert.equal("java.lang.AutoCloseable", closeable.qualifiedName(), "qualified name");

        NamedTypeSymbol string = interop.resolveType("java.lang.String");
        Assert.notNull(string, "java.lang.String resolves");
        Assert.equal(NamedTypeSymbol.DeclaredKind.CLASS, string.declaredKind(),
                "String is a class");
        Assert.isFalse(string.isValueType(), "a discovered Java class is a reference type");
    }

    private void absentTypes() {
        JavaInterop interop = new JavaInterop();
        // The second call is served by the negative cache and must agree with the first.
        Assert.isTrue(interop.resolveType("java.lang.NoSuchTypeHere") == null,
                "absent type resolves to nothing");
        Assert.isTrue(interop.resolveType("java.lang.NoSuchTypeHere") == null,
                "absent type stays absent when cached");
    }

    private void malformedNames() {
        JavaInterop interop = new JavaInterop();
        for (String name : List.of("", ".", "java.lang.", ".String", "java/lang/String")) {
            Assert.isTrue(interop.resolveType(name) == null,
                    "malformed name resolves to nothing: '" + name + "'");
        }
    }

    private void primitiveDescriptors() {
        JavaInterop interop = new JavaInterop();
        NamedTypeSymbol math = interop.resolveType("java.lang.Math");
        Assert.notNull(math, "java.lang.Math resolves");
        List<Symbol> members = interop.getMembers(math);

        // Math.abs is overloaded on exactly int, long, float and double. A descriptor decoder
        // that widened `J` to nint or dropped `F` to object would collapse these.
        List<TypeSymbol> absParameters = new ArrayList<>();
        for (Symbol member : members) {
            if (member instanceof FunctionSymbol function && function.name().equals("abs")
                    && function.parameters().size() == 1) {
                Assert.equal(function.parameters().get(0).type(), function.returnType(),
                        "Math.abs returns its argument type");
                absParameters.add(function.parameters().get(0).type());
            }
        }
        Assert.equalList(List.of(BuiltinType.INT, BuiltinType.LONG, BuiltinType.FLOAT,
                BuiltinType.DOUBLE), absParameters, "Math.abs overload parameter types");

        NamedTypeSymbol string = interop.resolveType("java.lang.String");
        Assert.equal(BuiltinType.CHAR, returnTypeOf(interop, string, "charAt", 1),
                "String.charAt returns char, not object");
        Assert.equal(BuiltinType.INT, returnTypeOf(interop, string, "length"),
                "String.length returns int");
        Assert.equal(BuiltinType.BOOL, returnTypeOf(interop, string, "isEmpty"),
                "String.isEmpty returns bool");
        Assert.equal(BuiltinType.STRING, returnTypeOf(interop, string, "trim"),
                "java.lang.String maps onto the built-in string");
        Assert.equal(BuiltinType.VOID, returnTypeOf(interop,
                interop.resolveType("java.lang.AutoCloseable"), "close"),
                "AutoCloseable.close returns void");
    }

    private void arrayDescriptors() {
        JavaInterop interop = new JavaInterop();
        NamedTypeSymbol string = interop.resolveType("java.lang.String");
        // JVM `byte` is signed, so `byte[]` is a V# `sbyte[]`, and a Java array of arrays is
        // a jagged array: one rank-1 dimension per level.
        TypeSymbol bytes = returnTypeOf(interop, string, "getBytes");
        Assert.isTrue(bytes instanceof TypeSymbol.Array, "String.getBytes returns an array");
        TypeSymbol.Array array = (TypeSymbol.Array) bytes;
        Assert.equal(BuiltinType.SBYTE, array.element(), "JVM byte is C# sbyte");
        Assert.equalList(List.of(1), array.ranks(), "one rank-1 dimension");

        NamedTypeSymbol arrays = interop.resolveType("java.util.Arrays");
        Assert.notNull(arrays, "java.util.Arrays resolves");
        boolean sawJagged = false;
        for (Symbol member : interop.getMembers(arrays)) {
            if (member instanceof FunctionSymbol function && function.name().equals("deepToString")
                    && function.parameters().get(0).type() instanceof TypeSymbol.Array outer) {
                Assert.equal(BuiltinType.OBJECT, outer.element(), "object[] element type");
                Assert.equalList(List.of(1), outer.ranks(), "object[] is rank 1");
                sawJagged = true;
            }
        }
        Assert.isTrue(sawJagged, "Arrays.deepToString(object[]) is discovered");
    }

    private void hiddenMembers() {
        JavaInterop interop = new JavaInterop();
        NamedTypeSymbol string = interop.resolveType("java.lang.String");
        for (Symbol member : interop.getMembers(string)) {
            Assert.isFalse(member.name().startsWith("<"),
                    "initialisers are not exposed as members: " + member.name());
            List<SyntaxKind> modifiers = member instanceof FunctionSymbol function
                    ? function.modifiers() : ((FieldSymbol) member).modifiers();
            Assert.isTrue(modifiers.contains(SyntaxKind.PUBLIC),
                    "only public members are exposed: " + member.name());
        }
        // Statics carry the modifier that call sites use for opcode selection. Everything
        // java.lang.Math declares is static, so its instance members are exactly the ones it
        // inherits from java.lang.Object - no more, and none missing.
        NamedTypeSymbol math = interop.resolveType("java.lang.Math");
        List<String> mathInstanceMembers = instanceMemberNames(interop.getMembers(math));
        List<String> objectInstanceMembers =
                instanceMemberNames(interop.getMembers(interop.resolveType("java.lang.Object")));
        Assert.equalList(objectInstanceMembers, mathInstanceMembers,
                "Math's only instance members are the ones inherited from Object");
    }

    private static List<String> instanceMemberNames(List<Symbol> members) {
        return members.stream()
                .filter(member -> !(member instanceof FunctionSymbol function
                        ? function.modifiers() : ((FieldSymbol) member).modifiers())
                        .contains(SyntaxKind.STATIC))
                .map(Symbol::name)
                .sorted()
                .distinct()
                .toList();
    }

    private void bridgeMethods() {
        JavaInterop interop = new JavaInterop();
        NamedTypeSymbol string = interop.resolveType("java.lang.String");
        // String implements Comparable<String>, so the class file also carries a synthetic
        // bridge `compareTo(Object)`. Exposing it would create a bogus overload that only
        // differs from the real method by an erased parameter type.
        List<TypeSymbol> compareToParameters = new ArrayList<>();
        for (Symbol member : interop.getMembers(string)) {
            if (member instanceof FunctionSymbol function
                    && function.name().equals("compareTo")) {
                compareToParameters.add(function.parameters().get(0).type());
            }
        }
        // The synthetic bridge on `String` itself is still filtered. Generic substitution also
        // maps inherited `Comparable<T>.compareTo(T)` to `compareTo(string)`, where it duplicates
        // String's direct member and is removed by the derived-before-base semantic key. A
        // `Comparable<string>` receiver still gets the inherited declaration from its own type.
        Assert.equalList(List.of(BuiltinType.STRING), compareToParameters,
                "the direct member once, never a bridge or substituted duplicate");
    }

    private void publicFields() {
        JavaInterop interop = new JavaInterop();
        NamedTypeSymbol integer = interop.resolveType("java.lang.Integer");
        FieldSymbol maxValue = fieldOf(interop, integer, "MAX_VALUE");
        Assert.equal(BuiltinType.INT, maxValue.type(), "Integer.MAX_VALUE descriptor");
        Assert.isTrue(maxValue.modifiers().contains(SyntaxKind.PUBLIC), "MAX_VALUE is public");
        Assert.isTrue(maxValue.modifiers().contains(SyntaxKind.STATIC), "MAX_VALUE is static");
        Assert.isTrue(maxValue.modifiers().contains(SyntaxKind.READONLY), "MAX_VALUE is final");
        Assert.isFalse(maxValue.isConstant(), "Java constants are not folded as V# const values");

        NamedTypeSymbol point = interop.resolveType("java.awt.Point");
        FieldSymbol x = fieldOf(interop, point, "x");
        Assert.equal(BuiltinType.INT, x.type(), "Point.x descriptor");
        Assert.isFalse(x.modifiers().contains(SyntaxKind.STATIC), "Point.x is an instance field");
        Assert.isFalse(x.modifiers().contains(SyntaxKind.READONLY), "Point.x is mutable");

        CompilationResult assignment = Compilation.of(List.of(TestSources.styled("FinalField.vs", """
                using java.lang;
                static class C {
                    static void Set() {
                        Integer.MAX_VALUE = 0;
                    }
                }
                """))).analyze();
        Assert.equalList(List.of("VS0131"), assignment.diagnostics().stream()
                .map(diagnostic -> diagnostic.code().toString()).toList(),
                "a final Java field is not a writable assignment target");

        CompilationResult shadowed = Compilation.of(List.of(TestSources.styled("Shadow.vs", """
                using java.lang;
                namespace java.lang {
                    static class Integer {
                        public static int MAX_VALUE;
                    }
                }
                static class C {
                    static void Set() {
                        Integer.MAX_VALUE = 0;
                    }
                }
                """))).analyze();
        Assert.equalList(List.of(), shadowed.diagnostics(),
                "a V# first segment shadows the Java static-field bridge");
    }

    private void publicConstructors() {
        JavaInterop interop = new JavaInterop();
        NamedTypeSymbol exception = interop.resolveType("java.lang.IllegalArgumentException");
        Assert.notNull(exception, "IllegalArgumentException resolves");
        List<FunctionSymbol> constructors = interop.getConstructors(exception);
        Assert.isTrue(!constructors.isEmpty(), "public constructors are discovered");

        boolean sawNoArguments = false;
        boolean sawStringArgument = false;
        for (FunctionSymbol constructor : constructors) {
            Assert.equal("<init>", constructor.name(), "constructor JVM name");
            Assert.equal(exception, constructor.returnType(), "construction result type");
            Assert.isTrue(constructor.modifiers().contains(SyntaxKind.PUBLIC),
                    "only public constructors are exposed");
            if (constructor.parameters().isEmpty()) {
                sawNoArguments = true;
            } else if (constructor.parameters().size() == 1
                    && constructor.parameters().getFirst().type() == BuiltinType.STRING) {
                sawStringArgument = true;
            }
        }
        Assert.isTrue(sawNoArguments, "zero-argument constructor retained");
        Assert.isTrue(sawStringArgument, "String constructor descriptor maps to V# string");
        Assert.equalList(constructors, interop.getConstructors(exception),
                "constructor cache preserves order and identity");
    }

    private void vsharpConstructionRemainsExcluded() {
        CompilationResult result = Compilation.of(List.of(TestSources.styled("Construct.vs", """
                struct Value { }
                static class C {
                    static Value Make() {
                        return new Value();
                    }
                }
                """))).analyze();
        Assert.equalList(List.of("VS20001"), result.diagnostics().stream()
                .map(diagnostic -> diagnostic.code().toString()).toList(),
                "V# construction diagnostic");
    }

    private void throwableTypesRequired() {
        CompilationResult result = Compilation.of(List.of(TestSources.styled("Throws.vs", """
                static class C {
                    static void ThrowNumber() {
                        throw 1;
                    }
                    static void CatchString() {
                        try { } catch (string value) { }
                    }
                }
                """))).analyze();
        Assert.equalList(List.of("VS0155", "VS0155"), result.diagnostics().stream()
                .map(diagnostic -> diagnostic.code().toString()).toList(),
                "throw/catch type diagnostics");

        JavaInterop interop = new JavaInterop();
        Assert.isTrue(interop.isThrowable(
                        interop.resolveType("java.lang.IllegalArgumentException")),
                "IllegalArgumentException derives from Throwable");
        Assert.isFalse(interop.isThrowable(interop.resolveType("java.lang.String")),
                "String does not derive from Throwable");
    }

    /// `java.lang.StringBuilder` declares `append` but inherits `length` from the
    /// package-private `java.lang.AbstractStringBuilder`. Both must be exposed, and the
    /// inherited one must be named after StringBuilder: a reference to the package-private
    /// declaring class would compile and then fail with `IllegalAccessError`.
    private void inheritedMembers() {
        JavaInterop interop = new JavaInterop();
        NamedTypeSymbol builder = interop.resolveType("java.lang.StringBuilder");
        List<String> names = interop.getMembers(builder).stream()
                .filter(member -> member.name().equals("length"))
                .map(Symbol::qualifiedName)
                .distinct()
                .toList();
        Assert.equalList(List.of("java.lang.StringBuilder.length"), names,
                "the inherited length() is owned by the type that inherits it");
    }

    /// A member declared by both a class and its superclass is one member, not two: an
    /// override is keyed by name and parameter descriptors, a hidden field by name. The
    /// derived declaration is the one kept, because it is the one reached first.
    private void inheritanceDeduplication() {
        JavaInterop interop = new JavaInterop();
        // String declares toString() and also inherits Object's - one member must survive.
        long stringToString = interop.getMembers(interop.resolveType("java.lang.String")).stream()
                .filter(member -> member.name().equals("toString"))
                .count();
        Assert.equal(1L, stringToString, "an override is collected once");

        // GregorianCalendar overrides Calendar's clone() and inherits Calendar's YEAR field.
        NamedTypeSymbol calendar = interop.resolveType("java.util.GregorianCalendar");
        List<Symbol> members = interop.getMembers(calendar);
        Assert.equal(1L, members.stream().filter(m -> m.name().equals("clone")).count(),
                "an override of a public superclass method is collected once");
        Assert.equalList(List.of("java.util.GregorianCalendar.YEAR"),
                members.stream().filter(m -> m.name().equals("YEAR"))
                        .map(Symbol::qualifiedName).toList(),
                "an inherited static field is exposed once, owned by the inheriting type");
    }

    /// An interface's class-file superclass is `java.lang.Object`, but an interface-owned
    /// reference needs `invokeinterface`, so the walk must not start for interfaces.
    /// An interface type's members include `java.lang.Object`'s public methods. JLS 9.2 makes
    /// them members of every interface, the JVM resolves them for an `invokeinterface`
    /// (JVMS 5.4.3.4), and javac emits exactly `invokeinterface java/util/Set.toString` for
    /// one - so refusing them made an interface-typed value unable to answer `ToString()`,
    /// which a realistic program hit immediately. Order stays derived before base.
    private void interfacesInheritObjectMembers() {
        JavaInterop interop = new JavaInterop();
        List<String> names = interop.getMembers(interop.resolveType("java.lang.AutoCloseable"))
                .stream().map(Symbol::name).toList();
        Assert.equal("close", names.getFirst(), "the interface's own member comes first");
        Assert.isTrue(names.contains("toString"), "Object's methods are members of an interface");
        Assert.isTrue(names.contains("hashCode"), "including hashCode");
        Assert.isTrue(names.contains("getClass"),
                "including the final ones, which are still invocable through an interface");
        Assert.isFalse(names.contains("<init>"), "but not its constructor");
    }

    /// A member gained from a superinterface is reachable too, and is named after the type
    /// that inherits it, exactly as javac names `java/util/ArrayList.stream` for a method
    /// `ArrayList` gains from `Collection`. Static interface methods are excluded, because
    /// JLS 8.4.8 does not inherit them.
    private void superinterfaceMembersAreInherited() {
        JavaInterop interop = new JavaInterop();
        FunctionSymbol stream = interop.getMembers(interop.resolveType("java.util.Set")).stream()
                .filter(FunctionSymbol.class::isInstance).map(FunctionSymbol.class::cast)
                .filter(function -> function.name().equals("stream"))
                .findFirst().orElseThrow();
        Assert.equal("java.util.Set.stream", stream.qualifiedName(),
                "an inherited member is named after the inheriting type");

        Assert.isTrue(interop.getMembers(interop.resolveType("java.util.ArrayList")).stream()
                .map(Symbol::name).anyMatch(name -> name.equals("stream")),
                "a class gains a default method from an implemented interface");

        Assert.isTrue(interop.getMembers(interop.resolveType("java.util.List")).stream()
                .map(Symbol::name).anyMatch(name -> name.equals("copyOf")),
                "a static declared on the interface itself is still a member");
        Assert.isFalse(interop.getMembers(interop.resolveType("java.util.Set")).stream()
                .map(Symbol::name).anyMatch(name -> name.equals("emptyList")),
                "a static is not inherited from anywhere");
    }

    private void determinism() {
        List<Symbol> first = membersOf(new JavaInterop(), "java.lang.Math");
        List<Symbol> second = membersOf(new JavaInterop(), "java.lang.Math");
        Assert.equalList(first, second,
                "two fresh resolvers produce identical members in identical order");
        Assert.isTrue(!first.isEmpty(), "java.lang.Math exposes members");

        JavaInterop firstInterop = new JavaInterop();
        JavaInterop secondInterop = new JavaInterop();
        Assert.equalList(firstInterop.getConstructors(
                        firstInterop.resolveType("java.lang.IllegalArgumentException")),
                secondInterop.getConstructors(
                        secondInterop.resolveType("java.lang.IllegalArgumentException")),
                "two fresh resolvers produce identical constructor order");
    }

    private static List<Symbol> membersOf(JavaInterop interop, String qualifiedName) {
        return interop.getMembers(interop.resolveType(qualifiedName));
    }

    private static FieldSymbol fieldOf(JavaInterop interop, NamedTypeSymbol owner, String name) {
        Assert.notNull(owner, "field owner resolves");
        for (Symbol member : interop.getMembers(owner)) {
            if (member instanceof FieldSymbol field && field.name().equals(name)) {
                return field;
            }
        }
        throw new AssertionError("no field named " + name + " on " + owner.qualifiedName());
    }

    /// The return type of the unique zero-argument overload named `methodName`, which keeps
    /// these assertions independent of class-file member order.
    private static TypeSymbol returnTypeOf(JavaInterop interop, NamedTypeSymbol owner,
            String methodName) {
        return returnTypeOf(interop, owner, methodName, 0);
    }

    private static TypeSymbol returnTypeOf(JavaInterop interop, NamedTypeSymbol owner,
            String methodName, int arity) {
        for (Symbol member : interop.getMembers(owner)) {
            if (member instanceof FunctionSymbol function && function.name().equals(methodName)
                    && function.parameters().size() == arity) {
                return function.returnType();
            }
        }
        throw new AssertionError("no method named " + methodName + " on " + owner.qualifiedName());
    }
}
