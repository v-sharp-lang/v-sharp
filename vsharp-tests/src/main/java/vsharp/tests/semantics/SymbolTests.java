package vsharp.tests.semantics;

import java.util.List;
import vsharp.compiler.diagnostics.Diagnostic;
import vsharp.compiler.diagnostics.DiagnosticBag;
import vsharp.compiler.semantics.binding.DeclarationBinder;
import vsharp.compiler.semantics.binding.SemanticModel;
import vsharp.compiler.semantics.symbols.FieldSymbol;
import vsharp.compiler.semantics.symbols.FunctionSymbol;
import vsharp.compiler.semantics.symbols.LocalSymbol;
import vsharp.compiler.semantics.symbols.NamespaceSymbol;
import vsharp.compiler.semantics.symbols.Scope;
import vsharp.compiler.semantics.symbols.SourceLocation;
import vsharp.compiler.semantics.symbols.Symbol;
import vsharp.compiler.semantics.types.BuiltinType;
import vsharp.compiler.semantics.types.JvmTypeKind;
import vsharp.compiler.semantics.types.TypeSymbol;
import vsharp.compiler.source.SourceFile;
import vsharp.compiler.source.SourceSpan;
import vsharp.compiler.syntax.AuxiliarySyntax;
import vsharp.compiler.syntax.DeclarationSyntax;
import vsharp.compiler.syntax.Parser;
import vsharp.compiler.syntax.StatementSyntax;
import vsharp.testkit.Assert;
import vsharp.testkit.TestRegistry;
import vsharp.testkit.TestSuite;

/// Declaration-binding coverage for semantic types, symbols and lexical scopes.
public final class SymbolTests implements TestSuite {

    @Override
    public String suiteName() {
        return "semantic.symbols";
    }

    @Override
    public void register(TestRegistry registry) {
        registerTypes(registry);
        registerScopes(registry);
        registerDeclarations(registry);
        registerDiagnostics(registry);
    }

    private static void registerTypes(TestRegistry registry) {
        registry.test("builtins retain source identity and verifier carriers", () -> {
            Assert.equal(BuiltinType.INT, BuiltinType.fromKeyword(
                    vsharp.compiler.syntax.SyntaxKind.INT).orElseThrow(), "int lookup");
            Assert.equal(JvmTypeKind.INT, BuiltinType.UINT.jvmTypeKind(), "uint carrier");
            Assert.equal(JvmTypeKind.LONG, BuiltinType.NINT.jvmTypeKind(), "nint carrier");
            Assert.isTrue(BuiltinType.DECIMAL.isValueType(), "decimal value semantics");
            Assert.isFalse(BuiltinType.STRING.isValueType(), "string reference semantics");
            Assert.isTrue(BuiltinType.fromKeyword(vsharp.compiler.syntax.SyntaxKind.VAR).isEmpty(),
                    "var is not a built-in semantic type");
        });

        registry.test("composite type display names are deterministic", () -> {
            TypeSymbol array = new TypeSymbol.Array(BuiltinType.INT, List.of(2, 1));
            TypeSymbol tuple = new TypeSymbol.Tuple(List.of(
                    new TypeSymbol.TupleElement(array, "items"),
                    new TypeSymbol.TupleElement(BuiltinType.STRING, null)));
            Assert.equal("int[,][]", array.displayName(), "array display");
            Assert.equal("(int[,][] items, string)", tuple.displayName(), "tuple display");
            Assert.equal("(int[,][] items, string)?",
                    new TypeSymbol.Nullable(tuple).displayName(), "nullable display");
        });
    }

    private static void registerScopes(TestRegistry registry) {
        registry.test("scopes preserve order group overloads and search parents", () -> {
            SourceFile file = SourceFile.of("Scope.vs", "");
            SourceLocation location = new SourceLocation(file, SourceSpan.at(0));
            NamespaceSymbol global = new NamespaceSymbol("<global>", "", location);
            NamespaceSymbol nested = new NamespaceSymbol("N", "N", location);
            FieldSymbol outer = FieldSymbol.of("outer", "outer", location, BuiltinType.INT, null,
                    List.of());
            Scope root = Scope.root(global, List.of(outer));
            Scope child = Scope.nested(nested, root, List.of());
            Assert.equalList(List.of("outer"), root.declaredNames(), "declaration order");
            Assert.equal(outer, child.lookup("outer").getFirst(), "parent lookup");
            Assert.isTrue(child.lookupLocal("outer").isEmpty(), "local lookup boundary");
            Assert.equal(root, child.parent().orElseThrow(), "parent identity");
            Assert.throwsException(UnsupportedOperationException.class,
                    () -> root.symbols().add(outer), "immutable declarations");
        });
    }

    private static void registerDeclarations(TestRegistry registry) {
        registry.test("partial namespace declarations merge into one scope", () -> {
            Bound bound = clean("namespace N { struct A {} } namespace N { struct B {} }");
            AuxiliarySyntax.CompilationUnit unit = bound.unit();
            DeclarationSyntax.Namespace first =
                    (DeclarationSyntax.Namespace) unit.declarations().get(0);
            DeclarationSyntax.Namespace second =
                    (DeclarationSyntax.Namespace) unit.declarations().get(1);
            Symbol firstSymbol = bound.model().declaredSymbol(first).orElseThrow();
            Symbol secondSymbol = bound.model().declaredSymbol(second).orElseThrow();
            Assert.isTrue(firstSymbol == secondSymbol, "canonical namespace identity");
            Scope scope = bound.model().scopeOf(firstSymbol).orElseThrow();
            Assert.equalList(List.of("A", "B"), scope.declaredNames(), "merged members");
        });

        registry.test("container methods parameters fields locals and local functions bind", () -> {
            Bound bound = clean("""
                    namespace N {
                        static class Box<T> {
                            const int Answer = 42;
                            static T Pick(T left, T right) {
                                int count = 0;
                                int Twice(int value) => value + value;
                                { string text = "ok"; }
                                return left;
                            }
                        }
                    }
                    """);
            FunctionSymbol pick = function(bound.model(), "Pick");
            Scope functionScope = bound.model().scopeOf(pick).orElseThrow();
            Assert.equalList(List.of("left", "right", "count", "Twice"),
                    functionScope.declaredNames(), "function declarations");
            Assert.equal("T", pick.returnType().displayName(), "generic return type");
            Assert.equal("T", pick.parameters().getFirst().type().displayName(),
                    "generic parameter type");
            FunctionSymbol twice = function(bound.model(), "Twice");
            Assert.isTrue(twice.localFunction(), "local function marker");
            Assert.equal(JvmTypeKind.INT, twice.returnType().jvmTypeKind(),
                    "local function return type");
            Assert.equal(1L, bound.model().symbols().stream()
                    .filter(FieldSymbol.class::isInstance).count(), "field count");
            Assert.equal(2L, bound.model().symbols().stream()
                    .filter(LocalSymbol.class::isInstance).count(), "nested local count");
        });

        registry.test("later declared value types resolve in callable signatures", () -> {
            Bound bound = clean("""
                    static class Factory { static Later Echo(Later value) => value; }
                    struct Later {}
                    """);
            FunctionSymbol echo = function(bound.model(), "Echo");
            Assert.equal("Later", echo.returnType().displayName(), "forward return type");
            Assert.isTrue(echo.returnType() == echo.parameters().getFirst().type(),
                    "canonical forward type");
        });

        registry.test("overloads form one deterministic declaration group", () -> {
            Bound bound = bind("""
                    static class C {
                        static int F(int value) => value;
                        static string F(string value) => value;
                        static int F(int other) => other;
                    }
                    """);
            DeclarationSyntax.StaticContainer container =
                    (DeclarationSyntax.StaticContainer) bound.unit().declarations().getFirst();
            Scope scope = bound.model().scopeFor(container).orElseThrow();
            Assert.equal(2L, scope.lookupLocal("F").size(), "accepted overload count");
            Assert.equalList(List.of("VS0111"), codes(bound), "duplicate signature diagnostic");
        });

        registry.test("top level statements own a synthesized function scope", () -> {
            Bound bound = clean("int value = 1; int Twice(int x) => x + x; value++;");
            FunctionSymbol entry = bound.model().topLevelFunction().orElseThrow();
            Scope scope = bound.model().scopeOf(entry).orElseThrow();
            Assert.isTrue(entry.synthesized(), "synthesized marker");
            Assert.equalList(List.of("value", "Twice"), scope.declaredNames(),
                    "top-level declarations");
            Assert.isTrue(function(bound.model(), "Twice").localFunction(),
                    "top-level local function");
        });

        registry.test("resolved composite types are retained by syntax identity", () -> {
            Bound bound = clean("static class C { static (int x, string y)?[,][] F() => default; }");
            DeclarationSyntax.StaticContainer container =
                    (DeclarationSyntax.StaticContainer) bound.unit().declarations().getFirst();
            DeclarationSyntax.Method method =
                    (DeclarationSyntax.Method) container.members().getFirst();
            TypeSymbol type = bound.model().typeOf(method.returnType()).orElseThrow();
            Assert.equal("(int x, string y)?[,][]", type.displayName(), "resolved return type");
        });
    }

    private static void registerDiagnostics(TestRegistry registry) {
        registry.test("duplicate namespace type and member diagnostics are stable", () -> {
            Bound bound = bind("""
                    struct Value {}
                    struct Value {}
                    struct Holder { int field; int field; }
                    """);
            Assert.equalList(List.of("VS0101", "VS0102"), codes(bound), "duplicate codes");
        });

        registry.test("duplicate parameters type parameters and locals are diagnosed", () -> {
            Bound bound = bind("""
                    static class C<T, T> {
                        static void F(int item, int item) {
                            int item = 0;
                            int other = 1;
                            { int other = 2; }
                        }
                    }
                    """);
            Assert.equalList(List.of("VS0692", "VS0100", "VS0128", "VS0128"), codes(bound),
                    "lexically ordered duplicate codes");
        });

        registry.test("unknown and wrong arity types recover with error symbols", () -> {
            Bound bound = bind("""
                    struct Box<T> {}
                    static class C { Missing first; Box<int, string> second; }
                    """);
            Assert.equalList(List.of("VS0246", "VS0305"), codes(bound), "name diagnostics");
            List<FieldSymbol> fields = bound.model().symbols().stream()
                    .filter(FieldSymbol.class::isInstance).map(FieldSymbol.class::cast).toList();
            Assert.isTrue(fields.stream().allMatch(field -> field.type() == TypeSymbol.Error.INSTANCE),
                    "error type recovery");
        });

        registry.test("var void and dynamic declaration restrictions are explicit", () -> {
            Bound bound = bind("""
                    static class C {
                        var field;
                        dynamic dispatch;
                        static void F(void parameter) { var local = 1; }
                    }
                    """);
            Assert.equalList(List.of("VS0825", "VS20001", "VS1547"), codes(bound),
                    "restricted type diagnostics");
        });
    }

    private static FunctionSymbol function(SemanticModel model, String name) {
        return model.symbols().stream()
                .filter(FunctionSymbol.class::isInstance)
                .map(FunctionSymbol.class::cast)
                .filter(function -> function.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing function " + name));
    }

    private static Bound clean(String text) {
        Bound bound = bind(text);
        Assert.isTrue(bound.diagnostics().isEmpty(),
                "unexpected diagnostics: " + bound.diagnostics().all());
        return bound;
    }

    private static Bound bind(String text) {
        SourceFile file = SourceFile.of("Semantic.vs", text);
        DiagnosticBag diagnostics = new DiagnosticBag();
        AuxiliarySyntax.CompilationUnit unit = Parser.parse(file, diagnostics);
        SemanticModel model = DeclarationBinder.bind(java.util.List.of(file), java.util.List.of(unit), diagnostics);
        return new Bound(unit, model, diagnostics);
    }

    private static List<String> codes(Bound bound) {
        return bound.diagnostics().all().stream().map(Diagnostic::code)
                .map(Object::toString).toList();
    }

    private record Bound(AuxiliarySyntax.CompilationUnit unit, SemanticModel model,
            DiagnosticBag diagnostics) {}
}
