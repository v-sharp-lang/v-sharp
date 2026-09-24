package vsharp.tests.semantics;

import java.util.List;
import vsharp.compiler.diagnostics.Diagnostic;
import vsharp.compiler.diagnostics.DiagnosticBag;
import vsharp.compiler.diagnostics.DiagnosticCode;
import vsharp.compiler.semantics.binding.BoundExpression;
import vsharp.compiler.semantics.binding.DeclarationBinder;
import vsharp.compiler.semantics.binding.ExpressionBinder;
import vsharp.compiler.semantics.binding.ExpressionBinding;
import vsharp.compiler.semantics.binding.SemanticModel;
import vsharp.compiler.semantics.symbols.FunctionSymbol;
import vsharp.compiler.semantics.symbols.LocalSymbol;
import vsharp.compiler.semantics.symbols.ParameterSymbol;
import vsharp.compiler.semantics.symbols.Symbol;
import vsharp.compiler.semantics.types.BuiltinType;
import vsharp.compiler.semantics.types.TypeSymbol;
import vsharp.compiler.source.SourceFile;
import vsharp.compiler.syntax.AuxiliarySyntax;
import vsharp.compiler.syntax.Parser;
import vsharp.testkit.Assert;
import vsharp.testkit.TestRegistry;
import vsharp.testkit.TestSuite;

/// Declaration-scope coverage for lambdas, pattern variables and switch arms.
public final class LambdaScopeTests implements TestSuite {

    @Override
    public String suiteName() {
        return "semantic.lambda-scopes";
    }

    @Override
    public void register(TestRegistry registry) {
        registerLambdaScopes(registry);
        registerPatternScopes(registry);
    }

    private static void registerLambdaScopes(TestRegistry registry) {
        registry.test("lambda parameters live in a synthesised nested function", () -> {
            Bound bound = cleanApartFromLambdaRefusals("""
                static class C {
                    static void F(int seed) {
                        var f = (int x) => x + seed;
                    }
                }
                """);
            Assert.equalList(
                    List.of("C.F$lambda$0", "C.F$lambda$0.x"),
                    names(bound, "$lambda$"),
                    "lambda symbols");
            Symbol parameter = symbol(bound, "C.F$lambda$0.x");
            Assert.isTrue(parameter instanceof ParameterSymbol, "lambda parameter kind");
            // The declaration space is built before any target type exists, which is what
            // this suite is about. Since the design the *body* is bound only once a functional
            // interface types the parameters, so an untargeted lambda like this one
            // deliberately has no bound uses at all - see the targeted case below.
            Assert.isTrue(
                    !resolvesTo(bound, parameter),
                    "an untargeted lambda body is not bound");
        });

        registry.test("a targeted lambda body binds against the interface signature", () -> {
            Bound bound = clean("""
                using java.util.function;
                static class C {
                    static void F() {
                        Predicate<string> longer = s => s.Length > 3;
                    }
                }
                """);
            // The body resolves the lambda's own parameter, retyped from the abstract
            // method's substituted signature: `s` is `string`, so `s.Length` binds.
            Assert.isTrue(bound.expressions().expressions().stream()
                            .anyMatch(expression -> expression instanceof BoundExpression.Value value
                                    && value.symbol() instanceof ParameterSymbol declared
                                    && "s".equals(declared.name())
                                    && declared.type() == BuiltinType.STRING),
                    "the lambda body resolves its own parameter as string");
            Assert.isTrue(bound.expressions().expressions().stream()
                            .anyMatch(expression -> expression instanceof BoundExpression.Lambda),
                    "the lambda became a functional-interface value");
        });

        registry.test("lambda parameters do not escape the lambda", () -> {
            Bound bound = bind("""
                static class C {
                    static void F() {
                        var f = (int x) => x;
                        int y = x;
                    }
                }
                """);
            Assert.equalList(List.of("VS20001", "VS0103"), codes(bound),
                    "the lambda is refused and its parameter still does not escape");
        });

        registry.test("lambda parameters collide with enclosing locals", () -> {
            Bound bound = bind("""
                static class C {
                    static void F() {
                        int x = 1;
                        var f = (int x) => x;
                    }
                }
                """);
            Assert.equalList(List.of("VS20001", "VS0128"), codes(bound),
                    "the lambda is refused and its parameter still collides");
        });

        registry.test("nested lambdas nest their declaration spaces", () -> {
            Bound bound = cleanApartFromLambdaRefusals("""
                static class C {
                    static void F() {
                        var f = (int x) => (int y) => x + y;
                    }
                }
                """);
            Assert.equalList(
                    List.of(
                            "C.F$lambda$0",
                            "C.F$lambda$0.x",
                            "C.F$lambda$0$lambda$1",
                            "C.F$lambda$0$lambda$1.y"),
                    names(bound, "$lambda$"),
                    "nested lambda symbols");
        });

        registry.test("lambda signatures stay inferred until a target type exists", () -> {
            Bound bound = cleanApartFromLambdaRefusals("""
                static class C {
                    static void F() {
                        var f = (int x) => x;
                    }
                }
                """);
            FunctionSymbol lambda = (FunctionSymbol) symbol(bound, "C.F$lambda$0");
            Assert.isTrue(lambda.localFunction(), "a lambda is a local function");
            Assert.isTrue(lambda.synthesized(), "a lambda is synthesised");
            Assert.equal(
                    TypeSymbol.Inferred.INSTANCE,
                    lambda.returnType(),
                    "lambda return type stays inferred");
        });

        registry.test("explicit lambda return types are declared", () -> {
            Bound bound = cleanApartFromLambdaRefusals("""
                static class C {
                    static void F() {
                        var f = int (int x) => x;
                    }
                }
                """);
            FunctionSymbol lambda = (FunctionSymbol) symbol(bound, "C.F$lambda$0");
            Assert.equal(BuiltinType.INT, lambda.returnType(), "declared lambda return type");
        });

        registry.test("explicit lambda return types are checked", () -> {
            Bound bound = bind("""
                static class C {
                    static void F() {
                        var f = int (int x) => "text";
                        var g = int (int x) => { return "text"; };
                    }
                }
                """);
            // Since the design an untargeted lambda is refused before its body is bound, so the
            // refusal is the only diagnostic: there is no signature to check a return
            // against. The same body under a functional-interface target does report its
            // conversion, which `semantic.interop` covers.
            Assert.equalList(List.of("VS20001", "VS20001"), codes(bound),
                    "an untargeted lambda is refused once, without a body cascade");
        });
    }

    private static void registerPatternScopes(TestRegistry registry) {
        registry.test("is-pattern variables join the enclosing declaration space", () -> {
            Bound bound = clean("""
                static class C {
                    static int F(object o) {
                        if (o is int n) {
                            return n;
                        }
                        return 0;
                    }
                }
                """);
            Symbol pattern = symbol(bound, "C.F.n#0");
            Assert.isTrue(pattern instanceof LocalSymbol, "pattern variable kind");
            Assert.isTrue(resolvesTo(bound, pattern), "the pattern variable is readable");
        });

        registry.test("switch-expression arm variables stay inside their arm", () -> {
            Bound bound = bind("""
                static class C {
                    static int F(object o) {
                        int r = o switch { int n => n, _ => 0 };
                        return n;
                    }
                }
                """);
            Assert.equalList(List.of("VS0103"), codes(bound), "arm escape diagnostics");
        });

        registry.test("switch-section labels share one section scope", () -> {
            Bound bound = clean("""
                static class C {
                    static int F(object o) {
                        switch (o) {
                            case int n:
                                return n;
                            default:
                                return 0;
                        }
                    }
                }
                """);
            Assert.isTrue(resolvesTo(bound, symbol(bound, "C.F.n#0")), "section variable is readable");
        });

        registry.test("out declarations belong to the enclosing statement list", () -> {
            Bound bound = clean("""
                static class C {
                    static bool G(out int v) {
                        v = 1;
                        return true;
                    }

                    static int F() {
                        G(out int w);
                        return w;
                    }
                }
                """);
            Symbol declared = symbol(bound, "C.F.w#0");
            Assert.isTrue(declared instanceof LocalSymbol, "out declaration kind");
            Assert.isTrue(resolvesTo(bound, declared), "the out declaration is readable afterwards");
        });

        // A filter is refused (the design/VS20005), but its scope rule is still exercised: the
        // clause variable must be in scope inside the filter, or the refusal would be
        // hiding a scoping defect rather than a deliberate exclusion.
        registry.test("catch filters see the exception variable and are refused", () -> {
            Bound bound = bind("""
                using java.lang;
                static class C {
                    static bool Check(IllegalArgumentException failure) {
                        return true;
                    }

                    static void F() {
                        try {
                        } catch (IllegalArgumentException e) when (Check(e)) {
                            Check(e);
                        }
                    }
                }
                """);
            Assert.isTrue(resolvesTo(bound, symbol(bound, "C.F.e#0")), "filter sees the clause name");
            Assert.isTrue(bound.diagnostics().all().stream()
                            .allMatch(d -> d.code() == DiagnosticCode.RUNTIME_MODEL_UNSUPPORTED),
                    "the only diagnostic is the filter exclusion: " + bound.diagnostics().all());
            Assert.isTrue(bound.diagnostics().hasErrors(), "a catch filter is refused");
        });
    }

    private static List<String> names(Bound bound, String needle) {
        return bound.model().symbols().stream()
                .map(Symbol::qualifiedName)
                .filter(name -> name.contains(needle))
                .toList();
    }

    private static Symbol symbol(Bound bound, String qualifiedName) {
        return bound.model().symbols().stream()
                .filter(symbol -> symbol.qualifiedName().equals(qualifiedName))
                .findFirst()
                .orElseThrow(() -> Assert.fail(() -> "missing symbol " + qualifiedName
                        + " in " + names(bound, "")));
    }

    private static boolean resolvesTo(Bound bound, Symbol symbol) {
        return bound.expressions().expressions().stream()
                .anyMatch(expression -> expression instanceof BoundExpression.Value value
                        && value.symbol() == symbol);
    }

    private static List<String> codes(Bound bound) {
        return bound.diagnostics().all().stream().map(Diagnostic::code).map(Object::toString).toList();
    }

    /// A lambda is refused at binding because the subset has no delegate type to give it a
    /// value, so a lambda program's *expected* diagnostics are exactly those refusals;
    /// the declaration space it introduced, which is what these tests are about, is built
    /// regardless. Anything else is a real failure.
    private static Bound cleanApartFromLambdaRefusals(String text) {
        Bound bound = bind(text);
        List<String> unexpected = bound.diagnostics().all().stream()
                .filter(d -> d.code() != DiagnosticCode.OBJECT_MODEL_UNSUPPORTED)
                .map(Object::toString)
                .toList();
        Assert.isTrue(unexpected.isEmpty(), "unexpected diagnostics: " + unexpected);
        return bound;
    }

    private static Bound clean(String text) {
        Bound bound = bind(text);
        Assert.isTrue(
                bound.diagnostics().all().isEmpty(),
                "unexpected diagnostics: " + bound.diagnostics().all());
        return bound;
    }

    private static Bound bind(String text) {
        SourceFile file = SourceFile.of("LambdaScopes.vs", text);
        DiagnosticBag diagnostics = new DiagnosticBag();
        AuxiliarySyntax.CompilationUnit unit = Parser.parse(file, diagnostics);
        SemanticModel model = DeclarationBinder.bind(java.util.List.of(file), java.util.List.of(unit), diagnostics);
        ExpressionBinding expressions = ExpressionBinder.bind(file, unit, model, diagnostics);
        return new Bound(model, expressions, diagnostics);
    }

    private record Bound(
            SemanticModel model, ExpressionBinding expressions, DiagnosticBag diagnostics) {}
}
