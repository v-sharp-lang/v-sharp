package vsharp.tests.stdlib;

import java.util.List;

import vsharp.compiler.api.Compilation;
import vsharp.compiler.api.CompilationResult;
import vsharp.compiler.source.SourceFile;
import vsharp.tests.TestSources;
import vsharp.testkit.Assert;
import vsharp.testkit.TestRegistry;
import vsharp.testkit.TestSuite;

public final class StandardLibraryTests implements TestSuite {

    @Override
    public String suiteName() {
        return "stdlib.corelib";
    }

    @Override
    public void register(TestRegistry registry) {
        registry.test("console writeline binds successfully", this::consoleWriteLineBinds);
    }

    private void consoleWriteLineBinds() {
        String source = """
            using System;
            struct App {
                static void Main() {
                    Console.WriteLine("Hello Corelib!");
                    Console.WriteLine(42);
                }
            }
            """;

        SourceFile file = TestSources.styled("App.vs", source);
        Compilation compilation = Compilation.of(List.of(file));
        CompilationResult result = compilation.emit();

        // The binding should succeed because corelib.vs is automatically injected

        if (result.hasErrors()) {
            for (vsharp.compiler.diagnostics.Diagnostic d : result.errors()) {
                System.out.println(d);
            }
        }
        Assert.isFalse(result.hasErrors(), "Console should bind successfully to corelib.vs through using System");

    }
}
