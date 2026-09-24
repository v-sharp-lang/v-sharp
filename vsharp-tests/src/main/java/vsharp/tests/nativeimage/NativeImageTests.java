package vsharp.tests.nativeimage;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import vsharp.cli.Cli;
import vsharp.compiler.nativeimage.EntryPoints;
import vsharp.compiler.nativeimage.GraalDetection;
import vsharp.compiler.nativeimage.GraalToolchain;
import vsharp.compiler.nativeimage.HostLibc;
import vsharp.compiler.nativeimage.NativeImageBuilder;
import vsharp.compiler.nativeimage.NativeImageRequest;
import vsharp.testkit.Assert;
import vsharp.testkit.TestRegistry;
import vsharp.testkit.TestSuite;

/// The `GRAAL_HOME` trigger: detection, entry point discovery, and the command the driver runs.
///
/// A real GraalVM build takes minutes and exists on no continuous integration machine by
/// default, so the launcher under test is a recording script. That keeps the subject of these
/// cases exactly what the compiler owns - whether a stage runs, what it is asked to build, and
/// what a user is told - and leaves image generation itself to the machine that has a toolchain.
public final class NativeImageTests implements TestSuite {

    private static final String SOURCE = """
            using System;
            Console.WriteLine("native");
            """;

    @Override
    public String suiteName() {
        return "nativeimage";
    }

    @Override
    public void register(TestRegistry registry) {
        registry.test("an unset or empty GRAAL_HOME is absent, not a failure", () -> {
            GraalDetection unset = GraalToolchain.detect(Map.of());
            Assert.isTrue(unset instanceof GraalDetection.Absent, "unset detection");
            GraalDetection empty = GraalToolchain.detect(
                    Map.of(GraalToolchain.HOME_VARIABLE, "   "));
            Assert.isTrue(empty instanceof GraalDetection.Absent, "empty detection");
        });

        registry.test("help documents the trigger and every exit code", () -> {
            Invocation help = invoke(Map.of(), "--help");
            Assert.equal(0L, help.exitCode(), "help exit");
            Assert.contains(help.standardOut(), "GRAAL_HOME", "the trigger is discoverable");
            Assert.contains(help.standardOut(), "Unset, it", "the no-op case is stated");
            Assert.contains(help.standardOut(), "never fails the compilation",
                    "the failure policy is stated");
            Assert.isFalse(help.standardOut().contains("3  native"), "there is no native exit code");
        });

        registry.test("a named home that is not an installation is invalid", () -> {
            Path directory = temporaryDirectory();
            try {
                Path missing = directory.resolve("nowhere");
                GraalDetection absentDirectory = GraalToolchain.validate(missing.toString());
                Assert.isTrue(absentDirectory instanceof GraalDetection.Invalid,
                        "missing directory detection");
                Assert.contains(((GraalDetection.Invalid) absentDirectory).reason(),
                        "not a directory", "missing directory reason");

                GraalDetection withoutSvm = GraalToolchain.validate(directory.toString());
                Assert.isTrue(withoutSvm instanceof GraalDetection.Invalid, "no lib/svm detection");
                Assert.contains(((GraalDetection.Invalid) withoutSvm).reason(), "lib/svm",
                        "no lib/svm reason");

                Files.createDirectories(directory.resolve("lib").resolve("svm"));
                GraalDetection withoutLauncher = GraalToolchain.validate(directory.toString());
                Assert.isTrue(withoutLauncher instanceof GraalDetection.Invalid,
                        "no launcher detection");
                Assert.contains(((GraalDetection.Invalid) withoutLauncher).reason(),
                        "native image launcher", "no launcher reason");
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            } finally {
                deleteTree(directory);
            }
        });

        registry.test("a Windows installation is accepted by its .cmd launcher", () -> {
            // A GraalVM for Windows names its launcher `native-image.cmd`, and nothing else
            // about the installation differs. The search is written by name rather than by
            // `os.name`, so the shape a Windows host would present is testable here.
            Path directory = temporaryDirectory();
            try {
                Files.createDirectories(directory.resolve("lib").resolve("svm"));
                Path launcher = directory.resolve("bin").resolve("native-image.cmd");
                Files.createDirectories(launcher.getParent());
                Files.writeString(launcher, "@echo off\r\n", StandardCharsets.UTF_8);
                makeExecutable(launcher);

                GraalDetection detection = GraalToolchain.validate(directory.toString());
                Assert.isTrue(detection instanceof GraalDetection.Present,
                        "cmd launcher detection");
                GraalToolchain toolchain = ((GraalDetection.Present) detection).toolchain();
                Assert.equal(launcher, toolchain.nativeImage(), "the .cmd launcher is selected");
                Assert.contains(new NativeImageBuilder(toolchain)
                                .command(NativeImageRequest.of(
                                        List.of(directory), "Entry", directory.resolve("Entry")))
                                .getFirst(),
                        "native-image.cmd", "the command runs the .cmd launcher");
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            } finally {
                deleteTree(directory);
            }
        });

        registry.test("a musl host links a static image and a glibc host does not", () -> {
            // Alpine and the container images built on it cannot run a dynamically linked
            // image at all, so the two options are the difference between an executable and
            // a file that does not start. The host is identified by musl's own loader, whose
            // name is fixed, rather than by a distribution name - which is what lets the musl
            // decision be exercised on this glibc machine.
            Path directory = temporaryDirectory();
            try {
                Path musl = directory.resolve("alpine");
                Files.createDirectories(musl.resolve("lib"));
                Files.writeString(musl.resolve("lib").resolve("ld-musl-x86_64.so.1"), "",
                        StandardCharsets.UTF_8);
                Assert.equal(HostLibc.MUSL, HostLibc.detect("Linux", musl), "musl loader found");

                Path glibc = directory.resolve("debian");
                Files.createDirectories(glibc.resolve("lib"));
                Files.writeString(glibc.resolve("lib").resolve("ld-linux-x86-64.so.2"), "",
                        StandardCharsets.UTF_8);
                Assert.equal(HostLibc.DEFAULT, HostLibc.detect("Linux", glibc),
                        "a glibc loader is not musl");
                Assert.equal(HostLibc.DEFAULT, HostLibc.detect("FreeBSD", musl),
                        "the question is only asked of Linux");
                Assert.equal(HostLibc.DEFAULT, HostLibc.detect("Windows 11", musl),
                        "Windows links its own way");
                Assert.equalList(List.of("--static", "--libc=musl"),
                        HostLibc.MUSL.imageOptions(), "musl link options");
                Assert.equalList(List.of(), HostLibc.DEFAULT.imageOptions(),
                        "a default host adds nothing");

                Files.createDirectories(directory.resolve("lib").resolve("svm"));
                Path launcher = directory.resolve("bin").resolve("native-image");
                Files.createDirectories(launcher.getParent());
                Files.writeString(launcher, "#!/bin/sh\n", StandardCharsets.UTF_8);
                makeExecutable(launcher);
                GraalToolchain toolchain = new GraalToolchain(directory, launcher);
                NativeImageRequest request = NativeImageRequest.of(
                        List.of(directory), "Entry", directory.resolve("Entry"));

                List<String> onMusl = new NativeImageBuilder(toolchain, HostLibc.MUSL)
                        .command(request);
                Assert.isTrue(onMusl.contains("--static"), "a musl command links statically");
                Assert.isTrue(onMusl.contains("--libc=musl"), "a musl command names its libc");
                Assert.isTrue(onMusl.indexOf("--static") < onMusl.indexOf("-classpath"),
                        "host options precede the build's own arguments");
                List<String> onGlibc = new NativeImageBuilder(toolchain, HostLibc.DEFAULT)
                        .command(request);
                Assert.isFalse(onGlibc.contains("--libc=musl"),
                        "a glibc command is unchanged");
                Assert.equal(HostLibc.DEFAULT, HostLibc.detect(),
                        "this machine is a glibc host");
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            } finally {
                deleteTree(directory);
            }
        });

        registry.test("a home written with a leading tilde resolves against the user home", () -> {
            GraalDetection detection = GraalToolchain.validate("~/vsharp-absent-graal-home");
            Assert.isTrue(detection instanceof GraalDetection.Invalid, "tilde detection");
            String reason = ((GraalDetection.Invalid) detection).reason();
            Assert.contains(reason, System.getProperty("user.home"), "tilde expansion");
            Assert.isFalse(reason.contains("/~/"), "no literal tilde element");
        });

        registry.test("a request needs a classpath and an entry point", () -> {
            Path image = Path.of("image");
            Assert.throwsException(IllegalArgumentException.class,
                    () -> NativeImageRequest.of(List.of(), "Hello", image), "empty classpath");
            Assert.throwsException(IllegalArgumentException.class,
                    () -> NativeImageRequest.of(List.of(Path.of("out")), "  ", image),
                    "blank main class");
        });

        registry.test("entry points are read from emitted bytecode in a stable order", () -> {
            Path directory = temporaryDirectory();
            try {
                Path source = write(directory, "Entry.vs", SOURCE);
                Path output = directory.resolve("out");
                Invocation compile = invoke(Map.of(), "--out", output.toString(),
                        source.toString());
                Assert.equal(0L, compile.exitCode(), "compile exit");
                List<String> names = EntryPoints.mainClasses(classFiles(output));
                Assert.equalList(List.of("Entry"), names, "entry points");
                Assert.equalList(names, EntryPoints.mainClasses(classFiles(output)),
                        "repeated reads agree");
            } finally {
                deleteTree(directory);
            }
        });

        if (File.separatorChar != '/') {
            return;
        }

        registry.test("an exported GRAAL_HOME runs the launcher with the emitted program", () -> {
            Path directory = temporaryDirectory();
            try {
                Path home = recordingToolchain(directory);
                Path source = write(directory, "Entry.vs", SOURCE);
                Path output = directory.resolve("out");
                Invocation invocation = invoke(Map.of(GraalToolchain.HOME_VARIABLE, home.toString()),
                        "--out", output.toString(), source.toString());

                Assert.equal(0L, invocation.exitCode(), "native exit");
                Assert.isTrue(Files.exists(output.resolve("Entry.class")),
                        "class artifacts are still written");
                Assert.contains(invocation.standardOut(), "generating a native image for Entry",
                        "announcement");
                Assert.contains(invocation.standardOut(), "native image", "result line");
                Assert.equal("", invocation.standardError(), "no diagnostics");

                List<String> command = Files.readAllLines(directory.resolve("arguments.txt"),
                        StandardCharsets.UTF_8);
                Assert.isTrue(command.contains("--no-fallback"), "fallback images are refused");
                Assert.isTrue(command.contains("Entry"), "main class is named explicitly");
                int classpath = command.indexOf("-classpath");
                Assert.isTrue(classpath >= 0, "classpath is passed");
                String entries = command.get(classpath + 1);
                Assert.contains(entries, output.toString(), "emitted output leads the classpath");
                Assert.contains(entries, "vsharp-runtime", "runtime library is on the classpath");
                Assert.isTrue(Files.isExecutable(output.resolve("Entry")),
                        "the image lands beside the classes");
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            } finally {
                deleteTree(directory);
            }
        });

        registry.test("jar output images the jar itself, beside it", () -> {
            Path directory = temporaryDirectory();
            try {
                Path home = recordingToolchain(directory);
                Path source = write(directory, "Entry.vs", SOURCE);
                Path jar = directory.resolve("app.jar");
                Invocation invocation = invoke(Map.of(GraalToolchain.HOME_VARIABLE, home.toString()),
                        "--jar", jar.toString(), source.toString());

                Assert.equal(0L, invocation.exitCode(), "jar native exit");
                Assert.isTrue(Files.exists(jar), "the jar is still written");

                List<String> command = Files.readAllLines(directory.resolve("arguments.txt"),
                        StandardCharsets.UTF_8);
                String entries = command.get(command.indexOf("-classpath") + 1);
                // A V# jar carries no Main-Class attribute, so the jar is a classpath entry and
                // the entry point is named separately - never `-jar`.
                Assert.contains(entries, jar.toString(), "the jar leads the classpath");
                Assert.isFalse(command.contains("-jar"), "the jar is not the launcher's -jar");
                Assert.isTrue(command.contains("Entry"), "entry point is named");
                Assert.isTrue(Files.isExecutable(directory.resolve("Entry")),
                        "the image lands beside the jar");
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            } finally {
                deleteTree(directory);
            }
        });

        registry.test("a failed build leaves no runnable image from a previous one", () -> {
            Path directory = temporaryDirectory();
            try {
                Path source = write(directory, "Entry.vs", SOURCE);
                Path output = directory.resolve("out");

                Path working = recordingToolchain(directory);
                Assert.equal(0L, invoke(Map.of(GraalToolchain.HOME_VARIABLE, working.toString()),
                        "--out", output.toString(), source.toString()).exitCode(), "first build");
                Path image = output.resolve("Entry");
                Assert.isTrue(Files.isExecutable(image), "first build produced an image");

                deleteTree(working);
                Path broken = failingToolchain(directory);
                Invocation second = invoke(Map.of(GraalToolchain.HOME_VARIABLE, broken.toString()),
                        "--out", output.toString(), source.toString());

                Assert.equal(0L, second.exitCode(), "a failed image is not a failed compile");
                Assert.isFalse(Files.exists(image),
                        "the stale executable is gone rather than silently runnable");
            } finally {
                deleteTree(directory);
            }
        });

        // Windows is the reason this case exists. Its `native-image` writes `Entry.exe` for a
        // `-o Entry` request, and a check against the requested spelling alone would call that
        // successful build a failure and leave the executable unreported. The rule is not an
        // `os.name` test - it is what the launcher actually produced - so the behaviour a
        // Windows host would exercise is exercised here, on a development machine, by a launcher that
        // appends the suffix.
        registry.test("an image written with a .exe suffix is found, reported and replaced", () -> {
            Path directory = temporaryDirectory();
            try {
                Path source = write(directory, "Entry.vs", SOURCE);
                Path output = directory.resolve("out");

                Path working = suffixingToolchain(directory);
                Invocation first = invoke(Map.of(GraalToolchain.HOME_VARIABLE, working.toString()),
                        "--out", output.toString(), source.toString());
                Path image = output.resolve("Entry.exe");

                Assert.equal(0L, first.exitCode(), "suffixed build exit");
                Assert.isTrue(Files.isExecutable(image), "the .exe image is produced");
                Assert.isFalse(Files.exists(output.resolve("Entry")),
                        "no file carries the requested spelling");
                Assert.contains(first.standardOut(), "Entry.exe generated",
                        "the produced name is reported, not the requested one");
                Assert.equal("", first.standardError(), "a suffixed image is not a failure");

                deleteTree(working);
                Path broken = failingToolchain(directory);
                Invocation second = invoke(Map.of(GraalToolchain.HOME_VARIABLE, broken.toString()),
                        "--out", output.toString(), source.toString());

                Assert.equal(0L, second.exitCode(), "a failed image is not a failed compile");
                Assert.isFalse(Files.exists(image),
                        "the stale .exe is gone rather than silently runnable");
            } finally {
                deleteTree(directory);
            }
        });

        registry.test("a program with no entry point is compiled and not imaged", () -> {
            Path directory = temporaryDirectory();
            try {
                Path home = recordingToolchain(directory);
                Path source = write(directory, "Library.vs", """
                        static class Library
                        {
                            static int Twice(int v)
                            {
                                return v + v;
                            }
                        }
                        """);
                Path output = directory.resolve("out");
                Invocation invocation = invoke(Map.of(GraalToolchain.HOME_VARIABLE, home.toString()),
                        "--out", output.toString(), source.toString());

                Assert.equal(0L, invocation.exitCode(), "library exit");
                Assert.isTrue(Files.exists(output.resolve("Library.class")), "class artifact");
                Assert.contains(invocation.standardOut(), "no entry point", "explanation");
                Assert.equal("", invocation.standardError(), "a library is not an error");
                Assert.isFalse(Files.exists(directory.resolve("arguments.txt")),
                        "the launcher never ran");
            } finally {
                deleteTree(directory);
            }
        });

        registry.test("an invalid GRAAL_HOME reports and leaves compilation untouched", () -> {
            Path directory = temporaryDirectory();
            try {
                Path source = write(directory, "Entry.vs", SOURCE);
                Path output = directory.resolve("out");
                Path missing = directory.resolve("nowhere");
                Invocation invocation = invoke(
                        Map.of(GraalToolchain.HOME_VARIABLE, missing.toString()),
                        "--out", output.toString(), source.toString());

                Assert.equal(0L, invocation.exitCode(), "invalid home exit");
                Assert.isTrue(Files.exists(output.resolve("Entry.class")), "class artifact");
                Assert.contains(invocation.standardError(), GraalToolchain.HOME_VARIABLE,
                        "the variable is named");
                Assert.contains(invocation.standardError(), "no native image was generated",
                        "the consequence is stated");
            } finally {
                deleteTree(directory);
            }
        });

        registry.test("a failing launcher reports without failing the compilation", () -> {
            Path directory = temporaryDirectory();
            try {
                Path home = failingToolchain(directory);
                Path source = write(directory, "Entry.vs", SOURCE);
                Path output = directory.resolve("out");
                Invocation invocation = invoke(Map.of(GraalToolchain.HOME_VARIABLE, home.toString()),
                        "--out", output.toString(), source.toString());

                // The decisive guarantee: exporting GRAAL_HOME cannot turn a green build red.
                Assert.equal(0L, invocation.exitCode(), "native failure keeps the compile exit");
                Assert.isTrue(Files.exists(output.resolve("Entry.class")),
                        "compilation still produced its artifacts");
                Assert.contains(invocation.standardError(), "exit code 9", "the launcher's code");
                Assert.contains(invocation.standardError(), "classes are unaffected",
                        "the consequence is stated");
                Assert.contains(invocation.standardOut(), "no toolchain here",
                        "the launcher's own output is shown");
            } finally {
                deleteTree(directory);
            }
        });

        registry.test("a source error stops before the native stage", () -> {
            Path directory = temporaryDirectory();
            try {
                Path home = recordingToolchain(directory);
                Path source = write(directory, "Broken.vs", "int value = ;");
                Path output = directory.resolve("out");
                Invocation invocation = invoke(Map.of(GraalToolchain.HOME_VARIABLE, home.toString()),
                        "--out", output.toString(), source.toString());

                Assert.equal(1L, invocation.exitCode(), "source error exit");
                Assert.isFalse(Files.exists(directory.resolve("arguments.txt")),
                        "the launcher never ran");
            } finally {
                deleteTree(directory);
            }
        });
    }

    /// Builds an installation whose launcher records its arguments and creates the image.
    private static Path recordingToolchain(Path directory) {
        return toolchain(directory, FakeLauncher.RECORD);
    }

    /// Builds an installation whose launcher appends `.exe`, the way a Windows one does.
    private static Path suffixingToolchain(Path directory) {
        return toolchain(directory, FakeLauncher.SUFFIX);
    }

    /// Builds an installation whose launcher refuses, the way a broken toolchain does.
    private static Path failingToolchain(Path directory) {
        return toolchain(directory, FakeLauncher.FAIL);
    }

    /// Writes a GraalVM-shaped installation whose launcher starts [FakeLauncher].
    ///
    /// The launcher is the one part of these cases that has to be a real program the host can
    /// execute, so it is the one part that differs per platform: a `#!/bin/sh` file named
    /// `native-image`, or a `native-image.cmd` batch file, which is what `GraalToolchain`
    /// looks for on Windows. Both are two lines and both delegate immediately, so the
    /// behaviour under test stays in Java.
    private static Path toolchain(Path directory, String mode) {
        try {
            Path home = directory.resolve("graal");
            Files.createDirectories(home.resolve("lib").resolve("svm"));
            boolean windows = System.getProperty("os.name", "").startsWith("Windows");
            Path launcher = home.resolve("bin")
                    .resolve(windows ? "native-image.cmd" : "native-image");
            Files.createDirectories(launcher.getParent());
            String java = Path.of(System.getProperty("java.home"), "bin",
                    windows ? "java.exe" : "java").toString();
            String modulePath = System.getProperty("jdk.module.path");
            String record = directory.resolve("arguments.txt").toString();
            String entry = "vsharp.tests/vsharp.tests.nativeimage.FakeLauncher";
            Files.writeString(launcher, windows
                    ? "@echo off\r\n\"" + java + "\" --module-path \"" + modulePath
                            + "\" -m " + entry + " " + mode + " \"" + record + "\" %*\r\n"
                    : "#!/bin/sh\nexec \"" + java + "\" --module-path \"" + modulePath
                            + "\" -m " + entry + " " + mode + " \"" + record + "\" \"$@\"\n",
                    StandardCharsets.UTF_8);
            makeExecutable(launcher);
            return home;
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /// Marks a written launcher executable where the file system carries that bit.
    ///
    /// Windows has no POSIX permission view and decides executability by extension, so the
    /// call is not merely unnecessary there - it throws. Asking the file system what it
    /// supports keeps these cases runnable on every host the compiler targets.
    private static void makeExecutable(Path launcher) throws IOException {
        if (launcher.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(launcher,
                    Set.copyOf(PosixFilePermissions.fromString("rwxr-xr-x")));
        }
    }

    private static List<byte[]> classFiles(Path output) {
        try (Stream<Path> files = Files.walk(output)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".class"))
                    .sorted()
                    .map(NativeImageTests::read)
                    .toList();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static byte[] read(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static Invocation invoke(Map<String, String> environment, String... arguments) {
        StringWriter output = new StringWriter();
        StringWriter error = new StringWriter();
        int exitCode = Cli.run(arguments, new PrintWriter(output), new PrintWriter(error),
                environment);
        return new Invocation(exitCode, output.toString(), error.toString());
    }

    private static Path write(Path directory, String name, String text) {
        try {
            Path source = directory.resolve(name);
            Files.writeString(source, text + System.lineSeparator(), StandardCharsets.UTF_8);
            return source;
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static Path temporaryDirectory() {
        try {
            return Files.createTempDirectory("vsharp-native-");
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

    private record Invocation(int exitCode, String standardOut, String standardError) {
    }
}
