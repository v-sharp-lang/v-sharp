package vsharp.tests;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import vsharp.compiler.api.Compilation;
import vsharp.compiler.api.CompilationResult;
import vsharp.compiler.api.UnitAnalysis;
import vsharp.compiler.source.SourceFile;
import vsharp.testkit.Assert;

/// A compiled V# program whose classes have been *loaded*, and therefore verified.
///
/// Loading is the point. A backend defect can leave a compilation reporting nothing and still
/// produce a class file the JVM refuses to link: two such defects reached the repository behind
/// suites that only inspected diagnostics and bytecode structure. Running a method
/// forces the whole chain - descriptors, stack shapes, adaptation at every erasure boundary -
/// to be right, and a `VerifyError` surfaces as an ordinary test failure.
///
/// Every backend test should go through this rather than assert its own view of correctness.
public final class EmittedProgram {

    private final Map<String, byte[]> classes;
    private final ClassLoader loader;

    private EmittedProgram(Map<String, byte[]> classes) {
        this.classes = Map.copyOf(classes);
        Map<String, byte[]> defined = this.classes;
        this.loader = new ClassLoader(EmittedProgram.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = defined.get(name);
                if (bytes == null) {
                    return super.findClass(name);
                }
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
    }

    /// Compiles `source` as `fileName.vs` and asserts that it succeeded.
    ///
    /// The source is laid out by [TestSources#styled], so a case may be written compactly and
    /// still satisfy the mandatory brace style.
    public static EmittedProgram of(String fileName, String source) {
        SourceFile file = TestSources.styled(fileName + ".vs", source);
        CompilationResult result = Compilation.of(List.of(file)).emit();
        if (result.hasErrors()) {
            result.diagnostics().forEach(diagnostic -> System.out.println(diagnostic.message()));
        }
        Assert.isFalse(result.hasErrors(), "compilation should succeed");
        Map<String, byte[]> classes = new LinkedHashMap<>();
        for (UnitAnalysis unit : result.units()) {
            if (unit instanceof UnitAnalysis.Emitted emitted) {
                classes.putAll(emitted.classes());
            }
        }
        Assert.isTrue(!classes.isEmpty(), "bytecode should be generated");
        return new EmittedProgram(classes);
    }

    /// The diagnostic codes a compilation of `source` reports, asserting that it failed.
    public static List<String> refusalCodes(String source) {
        SourceFile file = TestSources.styled("test.vs", source);
        CompilationResult result = Compilation.of(List.of(file)).emit();
        Assert.isTrue(result.hasErrors(), "the program should be refused");
        return result.diagnostics().stream().map(diagnostic -> diagnostic.code().id()).toList();
    }

    /// The emitted bytes of one class, for structural assertions about the descriptor or code.
    public byte[] bytes(String className) {
        byte[] bytes = classes.get(className);
        Assert.isTrue(bytes != null, "class should be emitted: " + className);
        return bytes;
    }

    /// Loads `className`, which verifies it, and returns it.
    public Class<?> load(String className) {
        try {
            return loader.loadClass(className);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    /// Invokes a static method and returns its result, unwrapping the reflective cause so a
    /// failure inside the program reads as itself.
    public Object invoke(String className, String methodName, Object... arguments) {
        Class<?> type = load(className);
        Method found = null;
        for (Method candidate : type.getMethods()) {
            if (candidate.getName().equals(methodName)) {
                found = candidate;
            }
        }
        Assert.isTrue(found != null, "method should be found: " + className + '.' + methodName);
        try {
            return found.invoke(null, arguments);
        } catch (InvocationTargetException e) {
            throw e.getCause() instanceof RuntimeException runtime
                    ? runtime
                    : new IllegalStateException(e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /// Compiles, loads and runs in one step, asserting the result.
    public static void assertResult(String fileName, String source, String className,
            String methodName, Object expected, Object... arguments) {
        Assert.equal(expected, of(fileName, source).invoke(className, methodName, arguments),
                "execution should return the expected result");
    }
}
