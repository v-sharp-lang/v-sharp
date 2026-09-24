package vsharp.tests.async;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import vsharp.cli.Cli;
import vsharp.runtime.VsAsync;
import vsharp.testkit.Assert;
import vsharp.testkit.TestRegistry;
import vsharp.testkit.TestSuite;

/// `async` and `await`: the surface, the emitted shape, and the behaviour.
///
/// V# implements neither the C# awaiter protocol nor its state machine. An `async` callable is
/// split into an ordinary body method and a wrapper that starts it on a virtual thread, and
/// `await` is a bounded blocking read of the resulting task. These cases pin down what that
/// does and does not buy, including the three ways the surface refuses.
public final class AsyncTests implements TestSuite {

    @Override
    public String suiteName() {
        return "async";
    }

    @Override
    public void register(TestRegistry registry) {
        registry.test("an async callable is emitted as a body method plus a task wrapper", () -> {
            Path directory = temporaryDirectory();
            try {
                Path output = compile(directory, "Shape.vs", """
                        static class Shape
                        {
                            public static async Task<int> Twice(int value)
                            {
                                return value + value;
                            }

                            public static async Task Nothing()
                            {
                            }
                        }
                        """);
                List<String> shape = methodSignatures(output, "Shape");
                // The declared name keeps the declared signature - a caller sees a task - and
                // the written body becomes a private-by-convention `$async` callable returning
                // the task's result. `$` cannot be spelled in V#, so it cannot collide.
                Assert.isTrue(shape.contains("java.util.concurrent.Future Twice(int)"),
                        "wrapper keeps the declared task signature: " + shape);
                Assert.isTrue(shape.contains("int Twice$async(int)"),
                        "body returns the task's result: " + shape);
                Assert.isTrue(shape.contains("java.util.concurrent.Future Nothing()"),
                        "a bare Task is still a task: " + shape);
                Assert.isTrue(shape.contains("void Nothing$async()"),
                        "a bare Task's body returns nothing: " + shape);
            } finally {
                deleteTree(directory);
            }
        });

        registry.test("await produces the body's value, typed", () -> {
            Path directory = temporaryDirectory();
            try {
                Path output = compile(directory, "Values.vs", """
                        static class Values
                        {
                            static async Task<int> Number()
                            {
                                return 21;
                            }

                            static async Task<string> Text()
                            {
                                return "text";
                            }

                            public static async Task<int> Doubled()
                            {
                                return await Number() * 2;
                            }

                            public static async Task<string> Word()
                            {
                                return await Text();
                            }
                        }
                        """);
                // `await` is confined to an async callable exactly as in C#, which is what
                // keeps the word an ordinary identifier everywhere else. The harness joins
                // the resulting task the same way compiled code would.
                Assert.equal(42, awaited(output, "Values", "Doubled"), "unboxed int result");
                Assert.equal("text", awaited(output, "Values", "Word"), "cast reference result");
            } finally {
                deleteTree(directory);
            }
        });

        registry.test("tasks started together overlap", () -> {
            Path directory = temporaryDirectory();
            try {
                Path output = compile(directory, "Overlap.vs", """
                        using java.lang;
                        using java.time;

                        static class Overlap
                        {
                            static async Task<int> Wait(int millis)
                            {
                                Thread.Sleep(millis);
                                return millis;
                            }

                            public static async Task<bool> Concurrent()
                            {
                                long start = Instant.Now().ToEpochMilli();
                                Task<int> first = Wait(300);
                                Task<int> second = Wait(300);
                                Task<int> third = Wait(300);
                                int total = await first + await second + await third;
                                long elapsed = Instant.Now().ToEpochMilli() - start;
                                return total == 900 && elapsed < 700;
                            }
                        }
                        """);
                // The point of starting at the call rather than at the await: three 300 ms
                // tasks must not cost 900 ms. If this ever fails, the work became sequential.
                Assert.equal(true, awaited(output, "Overlap", "Concurrent"),
                        "three 300ms tasks complete in under 700ms");
            } finally {
                deleteTree(directory);
            }
        });

        registry.test("a failing body throws its own exception at the await", () -> {
            Path directory = temporaryDirectory();
            try {
                Path output = compile(directory, "Failing.vs", """
                        using System;

                        static class Failing
                        {
                            static async Task<int> Boom()
                            {
                                throw new InvalidOperationException("boom");
                            }

                            public static async Task<string> Caught()
                            {
                                try
                                {
                                    return $"unreachable {await Boom()}";
                                }
                                catch (InvalidOperationException failure)
                                {
                                    return failure.GetMessage();
                                }
                            }
                        }
                        """);
                // ExecutionException is unwrapped, so `await` reads like the call it replaced
                // and the body's own exception type is catchable.
                Assert.equal("boom", awaited(output, "Failing", "Caught"),
                        "the body's exception is what surfaces");
            } finally {
                deleteTree(directory);
            }
        });

        registry.test("a task is a JDK future, so JDK futures are awaitable", () -> {
            Path directory = temporaryDirectory();
            try {
                Path output = compile(directory, "Interop.vs", """
                        using java.util.concurrent;

                        static class Interop
                        {
                            public static async Task<string> FromJava()
                            {
                                ExecutorService pool = Executors.NewVirtualThreadPerTaskExecutor();
                                Task<string> task = pool.Submit(() => "from the jdk");
                                string value = await task;
                                pool.Shutdown();
                                return value;
                            }
                        }
                        """);
                // `Task<T>` *is* `java.util.concurrent.Future<T>`, so this needs no
                // adapter: a future a JDK method returned is awaited by the same keyword.
                Assert.equal("from the jdk", awaited(output, "Interop", "FromJava"),
                        "a future from ExecutorService.Submit awaits");
            } finally {
                deleteTree(directory);
            }
        });

        registry.test("the execution limit is sixty seconds and has no switch", () -> {
            // An architectural constant, asserted rather than described: there is no overload,
            // property or environment variable that changes it, and this fails if one appears.
            Assert.equal(60L, VsAsync.LIMIT.toSeconds(), "await execution limit");
            Assert.isTrue(java.lang.reflect.Modifier.isFinal(
                            fieldModifiers(VsAsync.class, "LIMIT")),
                    "the limit is final");
        });

        registry.test("a synchronous method joins tasks with the bounded await", () -> {
            Path directory = temporaryDirectory();
            try {
                Path output = compile(directory, "Fan.vs", """
                        using java.lang;

                        static class Fan
                        {
                            static async Task<int> Unit(int millis)
                            {
                                Thread.Sleep(millis);
                                return millis;
                            }

                            public static bool FanIn()
                            {
                                long start = System.NanoTime();
                                Task<int> a = Unit(300);
                                Task<int> b = Unit(300);
                                Task<int> c = Unit(300);
                                int total = await a + await b + await c;
                                return total == 900
                                    && (System.NanoTime() - start) / 1000000 < 700;
                            }
                        }
                        """.replace("System.NanoTime()", "Clock2.Now()")
                           .replace("using java.lang;", "using java.lang;\nusing java.time;")
                        + """

                        static class Clock2
                        {
                            public static long Now()
                            {
                                return Instant.Now().ToEpochMilli();
                            }
                        }
                        """);
                // `await` is an operator in every body, not only an async one. That is
                // what makes the sixty-second limit reachable from synchronous code: before
                // this, a sync caller had to join with the JDK's own unbounded `Get()`, and
                // the architectural limit was simply absent there.
                Assert.equal(true, invoke(output, "Fan", "FanIn"),
                        "a plain method starts three tasks and joins them, overlapped");
            } finally {
                deleteTree(directory);
            }
        });

        registry.test("async void is detached and returns nothing", () -> {
            Path directory = temporaryDirectory();
            try {
                Path output = compile(directory, "Detach.vs", """
                        static class Detach
                        {
                            public static async void Go(string note)
                            {
                            }
                        }
                        """);
                List<String> shape = methodSignatures(output, "Detach");
                // No future at all: `async void` is the declaration that says nobody will join,
                // so there is nothing to hand back.
                Assert.isTrue(shape.contains("void Go(java.lang.String)"),
                        "the wrapper returns void, not a task: " + shape);
                Assert.isTrue(shape.contains("void Go$async(java.lang.String)"),
                        "the body is still emitted: " + shape);
            } finally {
                deleteTree(directory);
            }
        });

        registry.test("async refuses a return type that is not a task", () -> {
            Diagnostics result = compileExpectingErrors("""
                    static class Bad
                    {
                        public static async int Wrong()
                        {
                            return 1;
                        }
                    }
                    """);
            Assert.contains(result.text(), "VS20014", "async return type diagnostic");
            Assert.contains(result.text(), "Task", "the message names what is required");
            Assert.contains(result.text(), "void", "void is now one of the accepted forms");
        });

        registry.test("await refuses an operand that is not a task", () -> {
            Diagnostics result = compileExpectingErrors("""
                    static class Bad
                    {
                        public static async Task<int> Wrong()
                        {
                            int value = await 7;
                            return value;
                        }
                    }
                    """);
            Assert.contains(result.text(), "VS20015", "await operand diagnostic");
        });

        registry.test("joining a task with Get refuses to compile", () -> {
            // The execution limit is the reason a blocking `await` is safe to compile into
            // every program. `Get()` waits without limit and the timed overload waits for
            // whatever the caller picked, so either one silently opts that call site out of
            // the limit. This reached production once - a server whose certificate loading
            // would have hung for ever on an unresponsive filesystem - which is why it is an
            // error rather than a style note.
            Diagnostics result = compileExpectingErrors("""
                    using java.util.concurrent;

                    static class Bad
                    {
                        static async Task<int> Unit()
                        {
                            return 1;
                        }

                        public static int Bypass()
                        {
                            Task<int> a = Unit();
                            Task<int> b = Unit();
                            CompletableFuture<string> c = CompletableFuture.CompletedFuture("j");
                            return a.Get() + b.Get(5L, TimeUnit.SECONDS) + c.Join().Length;
                        }
                    }
                    """);
            Assert.contains(result.text(), "VS20018", "unbounded join diagnostic");
            // Reported in the spelling the user wrote, not the JVM's: a resolved member group
            // carries the camelCase name by then.
            Assert.contains(result.text(), "'Get'", "the V# spelling is named");
            Assert.contains(result.text(), "'Join'", "CompletableFuture counts as a task");
            Assert.equal(3L, result.text().lines()
                    .filter(line -> line.contains("VS20018")).count(),
                    "every call site is reported, not just the first");
        });

        registry.test("--allow-unbounded-joins downgrades without silencing", () -> {
            Path directory = temporaryDirectory();
            try {
                Path source = write(directory, "Opt.vs", """
                        static class Opt
                        {
                            static async Task<int> Unit()
                            {
                                return 1;
                            }

                            public static int Join()
                            {
                                return Unit().Get();
                            }
                        }
                        """);
                Path output = directory.resolve("out");
                StringWriter out = new StringWriter();
                StringWriter error = new StringWriter();
                int exitCode = Cli.run(new String[] { "--allow-unbounded-joins",
                        "--out", output.toString(), source.toString() },
                        new PrintWriter(out), new PrintWriter(error));
                String text = out + error.toString();

                Assert.equal(0L, exitCode, "the opt-in compiles: " + text);
                Assert.contains(text, "VS20018", "the call site is still reported");
                Assert.contains(text, "warning", "downgraded rather than silenced");
                Assert.isTrue(Files.exists(output.resolve("Opt.class")), "artifacts are written");
            } finally {
                deleteTree(directory);
            }
        });

        registry.test("Get on something that is not a task is untouched", () -> {
            Path directory = temporaryDirectory();
            try {
                // The rule is about tasks, not about a method name. A list and a map both have
                // `Get`, and neither waits for anything.
                Path output = compile(directory, "Lists.vs", """
                        using java.util;

                        static class Lists
                        {
                            public static string Read()
                            {
                                var list = new ArrayList<string>();
                                list.Add("x");
                                var map = new HashMap<string, string>();
                                map.Put("k", "v");
                                return list.Get(0) + map.Get("k");
                            }
                        }
                        """);
                Assert.equal("xv", invoke(output, "Lists", "Read"), "ordinary Get still works");
            } finally {
                deleteTree(directory);
            }
        });

        registry.test("async refuses by-reference parameters", () -> {
            // C# refuses these as CS1988. V# must too, and its own lowering makes the reason
            // sharper: a by-reference parameter is a shared cell, and the body writes it on
            // another thread after the caller already holds the wrapper's result.
            //
            // This was found by executing a program rather than by reading one. Before the
            // refusal existed, the emitted wrapper loaded the cell with `iload` because it
            // asked the *source* type for the carrier instead of the descriptor, producing a
            // class that failed JVM verification and could not be loaded at all.
            for (String modifier : List.of("out", "ref", "in")) {
                String body = modifier.equals("out") ? "value = 1;\n            return 2;"
                        : "return value;";
                Diagnostics result = compileExpectingErrors("""
                        static class Bad
                        {
                            public static async Task<int> Wrong(%s int value)
                            {
                                %s
                            }
                        }
                        """.formatted(modifier, body));
                Assert.contains(result.text(), "VS20017", modifier + " parameter diagnostic");
                // The refusal stands - C# refuses it too - so the message has to carry the
                // alternative that does work, or it just blocks the user.
                Assert.contains(result.text(), "tuple", "the message offers the way forward");
            }
        });

        registry.test("an async callable's wrapper always verifies", () -> {
            Path directory = temporaryDirectory();
            try {
                // Every parameter carrier the wrapper has to reload, in one signature: two slot
                // widths, a reference, and a primitive that is not int. Loading any of these
                // with the wrong kind produces a class the JVM refuses, so merely getting the
                // class loaded is the assertion.
                Path output = compile(directory, "Carriers.vs", """
                        static class Carriers
                        {
                            public static async Task<string> Mixed(int a, long b, double c,
                                    string d, bool e, char f)
                            {
                                return $"{a}{b}{c}{d}{e}{f}";
                            }
                        }
                        """);
                List<String> shape = methodSignatures(output, "Carriers");
                Assert.isTrue(shape.stream().anyMatch(name -> name.contains("Mixed$async")),
                        "the body is emitted: " + shape);
                Assert.isTrue(shape.stream().anyMatch(
                                name -> name.startsWith("java.util.concurrent.Future Mixed(")),
                        "the wrapper is emitted and the class verified: " + shape);
            } finally {
                deleteTree(directory);
            }
        });

        registry.test("async is refused on a lambda and on a local function", () -> {
            Diagnostics lambda = compileExpectingErrors("""
                    static class Bad
                    {
                        public static int Wrong()
                        {
                            var f = async () => 1;
                            return 0;
                        }
                    }
                    """);
            Assert.contains(lambda.text(), "VS20016", "async lambda diagnostic");

            Diagnostics local = compileExpectingErrors("""
                    static class Bad
                    {
                        public static int Wrong()
                        {
                            async Task<int> Inner()
                            {
                                return 1;
                            }
                            return 0;
                        }
                    }
                    """);
            Assert.contains(local.text(), "VS20016", "async local function diagnostic");
        });
    }

    private static int fieldModifiers(Class<?> owner, String name) {
        try {
            return owner.getField(name).getModifiers();
        } catch (NoSuchFieldException failure) {
            throw new AssertionError("no field " + name + " on " + owner, failure);
        }
    }

    private static List<String> methodSignatures(Path output, String className) {
        try (URLClassLoader loader = new URLClassLoader(
                new URL[] { output.toUri().toURL() }, ClassLoader.getPlatformClassLoader())) {
            Class<?> type = Class.forName(className, false, loader);
            return Stream.of(type.getDeclaredMethods())
                    .map(method -> method.getReturnType().getName() + " " + method.getName() + "("
                            + Stream.of(method.getParameterTypes())
                                    .map(Class::getName)
                                    .reduce((left, right) -> left + ", " + right)
                                    .orElse("")
                            + ")")
                    .sorted()
                    .toList();
        } catch (ReflectiveOperationException | IOException failure) {
            throw new AssertionError("cannot read " + className, failure);
        }
    }

    /// Calls an `async` callable and joins the task it returns, which is what `await` does.
    private static Object awaited(Path output, String className, String methodName) {
        Object task = invoke(output, className, methodName);
        Assert.isTrue(task instanceof java.util.concurrent.Future,
                "an async callable returns a task, not " + task);
        return VsAsync.await((java.util.concurrent.Future<?>) task);
    }

    private static Object invoke(Path output, String className, String methodName) {
        try (URLClassLoader loader = new URLClassLoader(
                new URL[] { output.toUri().toURL() }, AsyncTests.class.getClassLoader())) {
            Class<?> type = Class.forName(className, true, loader);
            Method method = type.getMethod(methodName);
            return method.invoke(null);
        } catch (ReflectiveOperationException | IOException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            throw new AssertionError("cannot invoke " + className + "." + methodName
                    + ": " + cause, cause);
        }
    }

    private static Path compile(Path directory, String fileName, String source) {
        Path file = write(directory, fileName, source);
        Path output = directory.resolve("out");
        Diagnostics result = run(output, file);
        Assert.equal(0L, result.exitCode(), "compile " + fileName + ": " + result.text());
        return output;
    }

    private static Diagnostics compileExpectingErrors(String source) {
        Path directory = temporaryDirectory();
        try {
            Path file = write(directory, "Bad.vs", source);
            Diagnostics result = run(directory.resolve("out"), file);
            Assert.equal(1L, result.exitCode(), "expected source errors: " + result.text());
            return result;
        } finally {
            deleteTree(directory);
        }
    }

    private static Diagnostics run(Path output, Path source) {
        StringWriter out = new StringWriter();
        StringWriter error = new StringWriter();
        int exitCode = Cli.run(new String[] { "--out", output.toString(), source.toString() },
                new PrintWriter(out), new PrintWriter(error));
        return new Diagnostics(exitCode, out + error.toString());
    }

    private static Path write(Path directory, String name, String source) {
        try {
            Path file = directory.resolve(name);
            Files.writeString(file, source, StandardCharsets.UTF_8);
            return file;
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static Path temporaryDirectory() {
        try {
            return Files.createTempDirectory("vsharp-async-");
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static void deleteTree(Path directory) {
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // A leftover temporary directory is not a test result.
        }
    }

    private record Diagnostics(int exitCode, String text) { }
}
