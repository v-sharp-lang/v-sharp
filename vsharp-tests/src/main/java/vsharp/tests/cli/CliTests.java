package vsharp.tests.cli;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import vsharp.cli.Cli;
import vsharp.cli.Main;
import vsharp.compiler.nativeimage.GraalToolchain;
import vsharp.tests.TestSources;
import vsharp.testkit.Assert;
import vsharp.testkit.TestRegistry;
import vsharp.testkit.TestSuite;

/// Command-line parsing, boundary failures, diagnostics and the actual JPMS process entry.
public final class CliTests implements TestSuite {

    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(15);

    @Override
    public String suiteName() {
        return "cli";
    }

    @Override
    public void register(TestRegistry registry) {
        registry.test("help and version are successful terminal commands", () -> {
            Invocation help = invoke("--help");
            Assert.equal(0L, help.exitCode(), "help exit");
            Assert.contains(help.standardOut(), "Usage: vsharp", "help text");
            Assert.contains(help.standardOut(), "Compiles V# source files to JVM .class artifacts",
                    "honest stage description");
            Invocation version = invoke("--version");
            Assert.equal(0L, version.exitCode(), "version exit");
            Assert.contains(version.standardOut(), "V# ", "version text");
        });

        registry.test("invalid command lines return usage exit code two", () -> {
            for (List<String> arguments : List.of(
                    List.<String>of(), List.of("--unknown"), List.of("--define"),
                    List.of("--out"), List.of("--out="), List.of("--jar"), List.of("--jar="),
                    List.of("--out", "classes", "--jar", "app.jar", "input.vs"),
                    List.of("--jar", "app.jar", "--out", "classes", "input.vs"),
                    List.of("-Dnot-valid!", "input.vs"),
                    List.of("--diagnostics", "json", "input.vs"))) {
                Invocation invocation = invoke(arguments.toArray(String[]::new));
                Assert.equal(2L, invocation.exitCode(), "invalid invocation " + arguments);
                Assert.contains(invocation.standardError(), "Try 'vsharp --help'",
                        "usage hint");
            }
        });

        registry.test("valid source returns zero and writes a class artifact", () -> {
            Path directory = temporaryDirectory();
            Path source = temporarySource(directory, "Program.vs",
                    "static class Program { static int Add(int a, int b)"
                    + " => a + b; }");
            try {
                Path output = directory.resolve("out");
                Invocation invocation = invoke("--out", output.toString(), source.toString());
                Assert.equal(0L, invocation.exitCode(), "valid source exit");
                Assert.equal("", invocation.standardOut(), "valid stdout");
                Assert.equal("", invocation.standardError(), "valid stderr");
                Assert.isTrue(Files.exists(output.resolve("Program.class")),
                        "class artifact is written");
            } finally {
                deleteTree(directory);
            }
        });

        registry.test("cross-file namespaced calls emit linkable class names", () -> {
            Path directory = temporaryDirectory();
            Path helper = temporarySource(directory, "Helper.vs", """
                    namespace Test {
                        public static class Helper {
                            public static int Compute() => 42;
                        }
                    }
                    """);
            Path program = temporarySource(directory, "Program.vs", """
                    namespace Test {
                        public static class Program {
                            public static int MainValue() {
                                return Helper.Compute();
                            }
                        }
                    }
                    """);
            try {
                Path output = directory.resolve("out");
                Invocation invocation = invoke("--out", output.toString(),
                        helper.toString(), program.toString());
                Assert.equal(0L, invocation.exitCode(), "cross-file compile exit");
                Assert.equal("", invocation.standardError(), "cross-file stderr");
                Assert.isTrue(Files.exists(output.resolve("Test/Helper.class")),
                        "helper class keeps namespace");
                Assert.isTrue(Files.exists(output.resolve("Test/Program.class")),
                        "program class keeps namespace");
                Assert.equal(42, invokeStatic(output, "Test.Program", "MainValue"),
                        "cross-file call links and executes");
            } finally {
                deleteTree(directory);
            }
        });

        registry.test("jar output writes sorted deterministic class entries", () -> {
            Path directory = temporaryDirectory();
            Path first = temporarySource(directory, "B.vs",
                    "static class B { static int Value() => 2; }");
            Path second = temporarySource(directory, "A.vs",
                    "static class A { static int Value() => 1; }");
            Path firstJar = directory.resolve("first.jar");
            Path secondJar = directory.resolve("second.jar");
            try {
                Invocation firstRun = invoke("--jar", firstJar.toString(),
                        first.toString(), second.toString());
                Assert.equal(0L, firstRun.exitCode(), "first jar exit");
                Assert.equal("", firstRun.standardError(), "first jar stderr");
                Invocation secondRun = invoke("--jar=" + secondJar,
                        first.toString(), second.toString());
                Assert.equal(0L, secondRun.exitCode(), "second jar exit");
                Assert.equal("", secondRun.standardError(), "second jar stderr");
                Assert.equalList(List.of("A.class", "B.class"), jarEntries(firstJar),
                        "jar entries are sorted by class name");
                Assert.isTrue(Arrays.equals(Files.readAllBytes(firstJar),
                        Files.readAllBytes(secondJar)), "jar output is byte-identical");
            } catch (java.io.IOException failure) {
                throw new AssertionError("cannot inspect CLI jar output", failure);
            } finally {
                deleteTree(directory);
            }
        });

        registry.test("source errors return one with selectable diagnostic detail", () -> {
            Path source = temporarySource("int value = 1");
            try {
                Invocation detailed = invoke(source.toString());
                Assert.equal(1L, detailed.exitCode(), "syntax error exit");
                Assert.contains(detailed.standardError(), "VS1002", "diagnostic code");
                Assert.contains(detailed.standardError(), "^", "detailed caret");
                Invocation compact = invoke("--diagnostics", "compact", source.toString());
                Assert.equal(1L, compact.exitCode(), "compact syntax error exit");
                Assert.equal(1L, compact.standardError().lines().count(),
                        "compact is one line");
            } finally {
                delete(source);
            }
        });

        registry.test("define options select preprocessing branches", () -> {
            Path directory = temporaryDirectory();
            Path source = temporarySource(directory, "Feature.vs", """
                    #if FEATURE
                    int selected = 1;
                    #else
                    "unterminated
                    #endif
                    """);
            try {
                Path output = directory.resolve("out");
                for (String option : List.of("-DFEATURE", "--define=FEATURE")) {
                    Invocation invocation = invoke(option, "--out", output.toString(),
                            source.toString());
                    Assert.equal(0L, invocation.exitCode(), option + " selects valid branch");
                }
                Invocation separated = invoke("--define", "FEATURE", "--out", output.toString(),
                        source.toString());
                Assert.equal(0L, separated.exitCode(), "separate define form");
                Invocation absent = invoke(source.toString());
                Assert.equal(1L, absent.exitCode(), "missing symbol selects invalid branch");
            } finally {
                deleteTree(directory);
            }
        });

        registry.test("semantic errors reach the driver exit code", () -> {
            Path source = temporarySource("""
                    static class C {
                        static void F() {
                            int x;
                            int y = x;
                        }
                    }
                    """);
            try {
                Invocation invocation = invoke(source.toString());
                Assert.equal(1L, invocation.exitCode(), "flow error exit");
                Assert.contains(invocation.standardError(), "VS0165", "flow diagnostic code");
                Assert.equal("", invocation.standardOut(), "flow error stdout");
            } finally {
                delete(source);
            }
        });

        registry.test("source style errors block artifact emission", () -> {
            Path directory = temporaryDirectory();
            Path source = temporaryRawSource(directory, "Style.vs",
                    "static class Style { static int One() => 1; }");
            try {
                Path output = directory.resolve("out");
                Invocation invocation = invoke("--out", output.toString(), source.toString());
                Assert.equal(1L, invocation.exitCode(), "style error exit");
                Assert.contains(invocation.standardError(), "VS20007",
                        "Allman placement diagnostic");
                Assert.isFalse(Files.exists(output.resolve("Style.class")),
                        "style errors produce no artifact");
            } finally {
                deleteTree(directory);
            }
        });

        registry.test("warnings print on standard out and keep the zero exit", () -> {
            Path directory = temporaryDirectory();
            Path source = temporarySource(directory, "Warnings.vs", """
                    static class Warnings {
                        static void F() {
                            return;
                            int x = 1;
                        }
                    }
                    """);
            try {
                Path output = directory.resolve("out");
                Invocation invocation = invoke("--out", output.toString(), source.toString());
                Assert.equal(0L, invocation.exitCode(), "warning exit");
                Assert.contains(invocation.standardOut(), "VS0162", "warning code");
                Assert.equal("", invocation.standardError(), "warning stderr");
                Assert.isTrue(Files.exists(output.resolve("Warnings.class")),
                        "warning-only source still emits");
            } finally {
                deleteTree(directory);
            }
        });

        registry.test("output write failure reports VS29902 and boundary exit two", () -> {
            Path directory = temporaryDirectory();
            Path source = temporarySource(directory, "Output.vs",
                    "static class Output { static int One() => 1; }");
            Path outputFile = directory.resolve("not-a-directory");
            try {
                Files.writeString(outputFile, "", StandardCharsets.UTF_8);
                Invocation invocation = invoke("--out", outputFile.toString(), source.toString());
                Assert.equal(2L, invocation.exitCode(), "unwritable output exit");
                Assert.contains(invocation.standardError(), "VS29902", "output diagnostic");
            } catch (java.io.IOException failure) {
                throw new AssertionError("cannot prepare output failure test", failure);
            } finally {
                deleteTree(directory);
            }
        });

        registry.test("several sources report in one ordered pass", () -> {
            Path first = temporarySource("static class A { static void F(out int v) { } }");
            Path second = temporarySource("static class B { static void G() { int x; int y = x; } }");
            try {
                Invocation invocation = invoke("--diagnostics", "compact",
                        second.toString(), first.toString());
                Assert.equal(1L, invocation.exitCode(), "multi-source exit");
                List<String> lines = invocation.standardError().lines().toList();
                Assert.equal(2L, lines.size(), "one line per diagnostic");
                // Temporary names are random, so the expectation is the file order the
                // formatter promises rather than the order the arguments were given in.
                List<String> byName = Stream.of(first.toString(), second.toString())
                        .sorted()
                        .toList();
                Assert.contains(lines.get(0), byName.get(0), "lower file name leads");
                Assert.contains(lines.get(1), byName.get(1), "higher file name follows");
            } finally {
                delete(first);
                delete(second);
            }
        });

        registry.test("unreadable input reports VS29901 and boundary exit two", () -> {
            Path absent = Path.of(System.getProperty("java.io.tmpdir"),
                    "vsharp-absent-" + Long.toUnsignedString(System.nanoTime()) + ".vs");
            Invocation invocation = invoke(absent.toString());
            Assert.equal(2L, invocation.exitCode(), "unreadable source exit");
            Assert.contains(invocation.standardError(), "VS29901", "boundary diagnostic");
            Assert.contains(invocation.standardError(), absent.toString(), "source path");
        });

        // Diagnostics quote source text, and a V# identifier may be any Unicode identifier,
        // so the encoding the driver writes in decides whether a diagnostic arrives readable.
        // Redirected output must stay UTF-8 on every host - that is what a build, a pipe and
        // this suite capture - while a terminal gets what it declared, which is the Windows
        // console code page and is the whole reason the rule is not a constant.
        registry.test("redirected output is UTF-8 and a terminal gets its own encoding", () -> {
            Assert.equal(StandardCharsets.UTF_8,
                    Main.outputCharset(false, StandardCharsets.US_ASCII),
                    "a redirected stream ignores the declared encoding");
            Assert.equal(StandardCharsets.UTF_8, Main.outputCharset(false, null),
                    "no console is UTF-8");
            Assert.equal(StandardCharsets.UTF_8, Main.outputCharset(true, null),
                    "a terminal that declares nothing is UTF-8");
            Assert.equal(StandardCharsets.US_ASCII,
                    Main.outputCharset(true, StandardCharsets.US_ASCII),
                    "a terminal's own encoding is honoured");
            Assert.equal(StandardCharsets.UTF_8,
                    Main.outputCharset(true, StandardCharsets.UTF_8),
                    "a UTF-8 terminal is unchanged");
        });

        registry.test("module entry point works in a fresh JVM process", () -> {
            Path directory = temporaryDirectory();
            Path source = temporarySource(directory, "ProcessEntry.vs", "int value = 1;");
            try {
                String modulePath = System.getProperty("jdk.module.path");
                Assert.notNull(modulePath, "test JVM module path");
                Path output = directory.resolve("out");
                // `bin/java` everywhere but Windows, where the launcher is `bin/java.exe`.
                Path java = Path.of(System.getProperty("java.home"), "bin",
                        System.getProperty("os.name", "").startsWith("Windows")
                                ? "java.exe" : "java");
                ProcessBuilder builder = new ProcessBuilder(java.toString(),
                        "--module-path", modulePath, "-m", "vsharp.cli/vsharp.cli.Main",
                        "--out", output.toString(), source.toString());
                // Main reads the real environment, so the machine running the suite must not
                // decide whether this compilation also generates a native image.
                builder.environment().remove(GraalToolchain.HOME_VARIABLE);
                Process process = builder.start();
                boolean completed = process.waitFor(PROCESS_TIMEOUT.toMillis(),
                        TimeUnit.MILLISECONDS);
                if (!completed) {
                    process.destroyForcibly();
                }
                Assert.isTrue(completed, "CLI process completes within timeout");
                String standardOut = new String(process.getInputStream().readAllBytes(),
                        StandardCharsets.UTF_8);
                String standardError = new String(process.getErrorStream().readAllBytes(),
                        StandardCharsets.UTF_8);
                Assert.equal(0L, process.exitValue(), "process exit");
                Assert.equal("", standardOut, "process stdout");
                Assert.equal("", standardError, "process stderr");
                Assert.isTrue(Files.exists(output.resolve("ProcessEntry.class")),
                        "process writes class artifact");
            } finally {
                deleteTree(directory);
            }
        });
    }

    private static Invocation invoke(String... arguments) {
        StringWriter output = new StringWriter();
        StringWriter error = new StringWriter();
        int exitCode = Cli.run(arguments, new PrintWriter(output), new PrintWriter(error));
        return new Invocation(exitCode, output.toString(), error.toString());
    }

    private static Path temporarySource(String text) {
        try {
            Path file = Files.createTempFile("vsharp-cli-", ".vs");
            Files.writeString(file, TestSources.allman(text), StandardCharsets.UTF_8);
            return file;
        } catch (java.io.IOException failure) {
            throw new AssertionError("cannot create CLI test input", failure);
        }
    }

    private static Path temporarySource(Path directory, String fileName, String text) {
        try {
            Path file = directory.resolve(fileName);
            Files.writeString(file, TestSources.allman(text), StandardCharsets.UTF_8);
            return file;
        } catch (java.io.IOException failure) {
            throw new AssertionError("cannot create CLI test input", failure);
        }
    }

    private static Path temporaryRawSource(Path directory, String fileName, String text) {
        try {
            Path file = directory.resolve(fileName);
            Files.writeString(file, text, StandardCharsets.UTF_8);
            return file;
        } catch (java.io.IOException failure) {
            throw new AssertionError("cannot create raw CLI test input", failure);
        }
    }

    private static Path temporaryDirectory() {
        try {
            return Files.createTempDirectory("vsharp-cli-");
        } catch (java.io.IOException failure) {
            throw new AssertionError("cannot create CLI test directory", failure);
        }
    }

    private static void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (java.io.IOException failure) {
            throw new AssertionError("cannot delete CLI test input " + file, failure);
        }
    }

    private static void deleteTree(Path directory) {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (java.io.IOException failure) {
            throw new AssertionError("cannot delete CLI test directory " + directory, failure);
        }
    }

    private static List<String> jarEntries(Path jar) {
        try (JarFile file = new JarFile(jar.toFile())) {
            return file.stream().map(entry -> entry.getName()).toList();
        } catch (java.io.IOException failure) {
            throw new AssertionError("cannot read jar " + jar, failure);
        }
    }

    private static Object invokeStatic(Path classes, String className, String methodName) {
        try (URLClassLoader loader = new URLClassLoader(
                new URL[] { classes.toUri().toURL() }, ClassLoader.getPlatformClassLoader())) {
            Class<?> type = Class.forName(className, true, loader);
            Method method = type.getMethod(methodName);
            return method.invoke(null);
        } catch (ReflectiveOperationException | java.io.IOException failure) {
            throw new AssertionError("cannot invoke " + className + "." + methodName, failure);
        }
    }

    private record Invocation(int exitCode, String standardOut, String standardError) { }
}
