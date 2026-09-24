package vsharp.tests.semantics;

import java.util.List;
import vsharp.compiler.diagnostics.DiagnosticBag;
import vsharp.compiler.semantics.binding.DeclarationBinder;
import vsharp.compiler.semantics.binding.ExpressionBinder;
import vsharp.compiler.semantics.binding.SemanticModel;
import vsharp.compiler.semantics.symbols.FieldSymbol;
import vsharp.compiler.semantics.symbols.NamespaceSymbol;
import vsharp.compiler.semantics.symbols.Scope;
import vsharp.compiler.semantics.symbols.SourceLocation;
import vsharp.compiler.semantics.symbols.Symbol;
import vsharp.compiler.semantics.types.BuiltinType;
import vsharp.compiler.source.SourceFile;
import vsharp.compiler.source.SourceSpan;
import vsharp.compiler.syntax.AuxiliarySyntax;
import vsharp.compiler.syntax.Parser;
import vsharp.testkit.Assert;
import vsharp.testkit.TestRegistry;
import vsharp.testkit.TestSuite;

public final class CrossFileCompilationTests implements TestSuite {

    @Override
    public String suiteName() {
        return "semantic.crossfile";
    }

    @Override
    public void register(TestRegistry registry) {
        registry.test("cross-file namespace symbol merging", this::crossFileNamespaceMerging);
        registry.test("cross-file method invocation", this::crossFileMethodInvocation);
        registry.test("imported scope hierarchy in Scope", this::importedScopeHierarchy);
        registry.test("using directive scope resolution", this::usingDirectiveScopeResolution);
    }

    private void crossFileNamespaceMerging() {
        String src1 = """
            namespace Company.App {
                public struct Model {
                    public int Id;
                }
            }
            """;
        String src2 = """
            namespace Company.App {
                public struct Controller {
                    public int Mode;
                    public Model m; // Cross-file field type resolution
                }
            }
            """;

        SourceFile file1 = SourceFile.of("Model.vs", src1);
        SourceFile file2 = SourceFile.of("Controller.vs", src2);

        DiagnosticBag diagnostics = new DiagnosticBag();
        AuxiliarySyntax.CompilationUnit u1 = Parser.parse(file1, diagnostics);
        AuxiliarySyntax.CompilationUnit u2 = Parser.parse(file2, diagnostics);

        Assert.isFalse(diagnostics.hasErrors(), "both source files should parse cleanly");

        List<SourceFile> sources = List.of(file1, file2);
        List<AuxiliarySyntax.CompilationUnit> trees = List.of(u1, u2);
        SemanticModel globalModel = DeclarationBinder.bind(sources, trees, diagnostics);
        SemanticModel model1 = globalModel;
        SemanticModel model2 = globalModel;

        Assert.isFalse(diagnostics.hasErrors(), "single-file declaration binding clean");

        ExpressionBinder.bind(file1, u1, model1, diagnostics);
        ExpressionBinder.bind(file2, u2, model2, diagnostics);
        Assert.isFalse(diagnostics.hasErrors(), "cross-file compilation expression binding clean");
    }

    private void crossFileMethodInvocation() {
        String src1 = """
            namespace Test {
                public static class Helper {
                    public static int Compute() => 42;
                }
            }
            """;
        String src2 = """
            namespace Test {
                public static class Program {
                    public static void Main() {
                        int x = Helper.Compute();
                    }
                }
            }
            """;

        SourceFile file1 = SourceFile.of("Helper.vs", src1);
        SourceFile file2 = SourceFile.of("Program.vs", src2);
        List<SourceFile> sources = List.of(file1, file2);

        DiagnosticBag diagnostics = new DiagnosticBag();
        AuxiliarySyntax.CompilationUnit u1 = Parser.parse(file1, diagnostics);
        AuxiliarySyntax.CompilationUnit u2 = Parser.parse(file2, diagnostics);
        List<AuxiliarySyntax.CompilationUnit> trees = List.of(u1, u2);

        SemanticModel globalModel = DeclarationBinder.bind(sources, trees, diagnostics);
        SemanticModel model1 = globalModel;
        SemanticModel model2 = globalModel;

        Assert.isFalse(diagnostics.hasErrors(), "declarations clean");

        ExpressionBinder.bind(file1, u1, model1, diagnostics);
        ExpressionBinder.bind(file2, u2, model2, diagnostics);

        Assert.isFalse(diagnostics.hasErrors(), "cross-file method invocation clean");
    }

    private void importedScopeHierarchy() {
        SourceFile sf = SourceFile.of("dummy.vs", "");
        SourceLocation loc = new SourceLocation(sf, new SourceSpan(0, 0));

        Scope parent = Scope.root(
                new NamespaceSymbol("Global", "Global", loc),
                List.of());

        Scope imported = Scope.root(
                new NamespaceSymbol("Imported", "Imported", loc),
                List.of(FieldSymbol.of(
                        "importedVar", "importedVar", loc,
                        BuiltinType.INT, null, List.of())));

        Scope child = Scope.withImports(
                new NamespaceSymbol("Child", "Child", loc),
                parent,
                List.of(),
                List.of(imported));

        List<Symbol> found = child.lookup("importedVar");
        Assert.isFalse(found.isEmpty(), "importedVar should be resolved via imported scope in Scope.lookup");
        Assert.equal("importedVar", found.get(0).name(), "found imported symbol name");
    }
    
    private void usingDirectiveScopeResolution() {
        String src1 = """
            namespace Utility.Math {
                public static class Calculator {
                    public static int Add(int a, int b) => a + b;
                }
            }
            """;
        String src2 = """
            using Utility.Math;
            
            namespace Application {
                public static class Program {
                    public static void Main() {
                        int result = Calculator.Add(5, 10);
                    }
                }
            }
            """;

        SourceFile file1 = SourceFile.of("Math.vs", src1);
        SourceFile file2 = SourceFile.of("App.vs", src2);
        java.util.List<SourceFile> sources = java.util.List.of(file1, file2);

        DiagnosticBag diagnostics = new DiagnosticBag();
        AuxiliarySyntax.CompilationUnit u1 = Parser.parse(file1, diagnostics);
        AuxiliarySyntax.CompilationUnit u2 = Parser.parse(file2, diagnostics);
        java.util.List<AuxiliarySyntax.CompilationUnit> trees = java.util.List.of(u1, u2);

        SemanticModel globalModel = DeclarationBinder.bind(sources, trees, diagnostics);
        Assert.isFalse(diagnostics.hasErrors(), "declarations clean for using directives");

        ExpressionBinder.bind(file1, u1, globalModel, diagnostics);
        ExpressionBinder.bind(file2, u2, globalModel, diagnostics);

        Assert.isFalse(diagnostics.hasErrors(), "using directive cross-file method invocation clean");
    }
}
