package vsharp.cli;

import java.io.PrintWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import vsharp.compiler.api.Compilation;
import vsharp.compiler.api.CompilationResult;
import vsharp.compiler.api.Phase;
import vsharp.compiler.api.SurfaceVersion;
import vsharp.compiler.api.UnitAnalysis;
import vsharp.compiler.diagnostics.Diagnostic;
import vsharp.compiler.diagnostics.DiagnosticCode;
import vsharp.compiler.diagnostics.DiagnosticFormatter;
import vsharp.compiler.nativeimage.EntryPoints;
import vsharp.compiler.nativeimage.GraalDetection;
import vsharp.compiler.nativeimage.GraalToolchain;
import vsharp.compiler.nativeimage.NativeImageBuilder;
import vsharp.compiler.nativeimage.NativeImageOutcome;
import vsharp.compiler.nativeimage.NativeImageRequest;
import vsharp.compiler.source.SourceFile;
import vsharp.compiler.source.SourceSpan;
import vsharp.compiler.syntax.SyntaxFacts;

/// Dependency-free V# command-line driver.
///
/// The driver reads sources, then hands them to [Compilation], which sequences parsing,
/// declaration binding, expression binding, flow analysis and bytecode generation, returning
/// one ordered diagnostic list. The CLI itself decides nothing about phase order; it only maps
/// the outcome to a stable exit code and writes the generated `.class` artifacts.
public final class Cli {

    /// Source errors were found.
    public static final int SOURCE_ERROR = 1;

    /// The invocation or an input boundary was invalid.
    public static final int USAGE_ERROR = 2;

    private static final String USAGE = """
            Usage: vsharp [options] <source.vs>...

            Compiles V# source files to JVM .class artifacts.

            Options:
              -DNAME, --define NAME, --define=NAME
                                      Define a preprocessing symbol (repeatable)
              --diagnostics compact|detailed
                                      Select diagnostic rendering (default: detailed)
              -cp PATH, --classpath PATH, --classpath=PATH
                                      Resolve Java types from these entries as well as the
                                      module path; separator-joined, repeatable
              --out DIR, --out=DIR   Write generated .class files to DIR (default: .)
              --jar FILE, --jar=FILE Write generated classes to one deterministic JAR
              --allow-unbounded-joins
                                      Accept joining a task with the JDK's own Get()/Join()
                                      instead of 'await'. Such a join waits outside the 60
                                      second execution limit, so it is an error by default;
                                      this downgrades it to a warning at every call site
                                      rather than silencing it
              --help                  Show this help
              --version               Show the V# driver version
              --                      End option processing

            Environment:
              GRAAL_HOME              A GraalVM installation. When it is set and usable, a
                                      successful compilation with exactly one entry point is
                                      followed by a native executable, written beside the
                                      classes and named after that entry point. Unset, it
                                      changes nothing. A native image that cannot be built is
                                      reported and never fails the compilation.

            Exit codes:
              0  success       1  source errors       2  bad invocation
            """;

    private static final String VERSION = loadVersion();

    private Cli() {
        throw new AssertionError("No instances");
    }

    /// Runs one invocation without terminating the VM, with no environment.
    ///
    /// Public for Gradle/embedding integration and dependency-free tests. [Main] is the
    /// process boundary which applies the returned exit code. An embedder that calls this
    /// overload gets compilation only: the environment-triggered native image stage needs an
    /// environment, and inheriting an ambient one would make an embedded compile depend on
    /// whichever variables its host happened to export.
    ///
    /// @param arguments the command line
    /// @param standardOut where normal output goes
    /// @param standardError where diagnostics and failures go
    /// @return the exit code
    public static int run(String[] arguments, PrintWriter standardOut, PrintWriter standardError) {
        return run(arguments, standardOut, standardError, Map.of());
    }

    /// Runs one invocation without terminating the VM against an explicit environment.
    ///
    /// When that environment's `GRAAL_HOME` names a usable GraalVM installation, a successful
    /// compilation that produced exactly one entry point is followed by native image
    /// generation. When it does not, this is exactly the three-argument overload.
    ///
    /// @param arguments the command line
    /// @param standardOut where normal output goes
    /// @param standardError where diagnostics and failures go
    /// @param environment the environment whose `GRAAL_HOME` is read
    /// @return the exit code
    public static int run(String[] arguments, PrintWriter standardOut, PrintWriter standardError,
            Map<String, String> environment) {
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(standardOut, "standardOut");
        Objects.requireNonNull(standardError, "standardError");
        Objects.requireNonNull(environment, "environment");
        CommandLine commandLine = parseCommandLine(arguments);
        int exitCode = switch (commandLine) {
            case CommandLine.Help ignored -> {
                standardOut.print(USAGE);
                yield 0;
            }
            case CommandLine.Version ignored -> {
                // The surface level is part of the identity a build needs: release versions
                // cannot say whether one compiler is older than another, because every jar
                // here reports one release version. A build records this number in its `vsharp.classpath`
                // descriptor so the language server can refuse to analyse when it is behind.
                standardOut.println("V# " + implementationVersion()
                        + " (surface " + SurfaceVersion.CURRENT + ")");
                yield 0;
            }
            case CommandLine.Invalid invalid -> {
                standardError.println("vsharp: " + invalid.message());
                standardError.println("Try 'vsharp --help' for usage.");
                yield USAGE_ERROR;
            }
            case CommandLine.Check check ->
                    checkSources(check, standardOut, standardError, environment);
        };
        standardOut.flush();
        standardError.flush();
        return exitCode;
    }

    private static int checkSources(CommandLine.Check command, PrintWriter standardOut,
            PrintWriter standardError, Map<String, String> environment) {
        boolean boundaryErrors = false;
        List<SourceFile> files = new ArrayList<>(command.sources().size());
        for (Path path : command.sources()) {
            try {
                files.add(SourceFile.read(path));
            } catch (UncheckedIOException failure) {
                SourceFile unavailable = SourceFile.of(path.toString(), "");
                Diagnostic diagnostic = Diagnostic.of(DiagnosticCode.SOURCE_UNREADABLE,
                        unavailable, SourceSpan.at(0), boundaryMessage(failure));
                standardError.println(DiagnosticFormatter.compact().format(diagnostic));
                boundaryErrors = true;
            }
        }

        // An unreadable input is an invocation failure, not a source error: analysing the
        // readable remainder would report a partial, misleading result.
        if (boundaryErrors) {
            return USAGE_ERROR;
        }

        CompilationResult result;
        try {
            Compilation compilation = Compilation.of(files, command.symbols(),
                    command.classpath());
            if (command.allowUnboundedJoins()) {
                compilation = compilation.allowingUnboundedJoins();
            }
            result = compilation.emit();
        } catch (RuntimeException failure) {
            SourceFile location = files.isEmpty()
                    ? SourceFile.of("<compiler>", "")
                    : files.getFirst();
            Diagnostic diagnostic = Diagnostic.of(DiagnosticCode.INTERNAL_ERROR,
                    location, SourceSpan.at(0), internalMessage(failure));
            standardError.println(command.formatter().format(diagnostic));
            return SOURCE_ERROR;
        }
        if (!result.diagnostics().isEmpty()) {
            PrintWriter destination = result.hasErrors() ? standardError : standardOut;
            destination.println(command.formatter().formatAll(result.diagnostics()));
        }
        if (result.hasErrors()) {
            return SOURCE_ERROR;
        }
        return writeArtifacts(result, command.output(), command.classpath(), command.formatter(),
                standardOut, standardError, environment);
    }

    private static String boundaryMessage(UncheckedIOException failure) {
        Throwable cause = failure.getCause();
        String message = cause == null ? failure.getMessage() : cause.getMessage();
        return Objects.requireNonNullElse(message, "unknown I/O error");
    }

    private static String internalMessage(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    private static int writeArtifacts(CompilationResult result, OutputTarget output,
            List<Path> classpath, DiagnosticFormatter formatter, PrintWriter standardOut,
            PrintWriter standardError, Map<String, String> environment) {
        if (result.reached() != Phase.CODE_GENERATION) {
            return 0;
        }
        List<Artifact> artifacts;
        try {
            artifacts = artifacts(result);
            switch (output) {
                case OutputTarget.Directory directory -> writeClassDirectory(directory.path(), artifacts);
                case OutputTarget.Jar jar -> writeJar(jar.path(), artifacts);
            }
        } catch (IOException | RuntimeException failure) {
            SourceFile file = SourceFile.of(output.path().toString(), "");
            Diagnostic diagnostic = Diagnostic.of(DiagnosticCode.OUTPUT_UNWRITABLE,
                    file, SourceSpan.at(0), boundaryMessage(failure));
            standardError.println(formatter.format(diagnostic));
            return USAGE_ERROR;
        }
        return generateNativeImage(artifacts, output, classpath, standardOut, standardError,
                environment);
    }

    /// Produces a native executable when, and only when, the environment asked for one.
    ///
    /// Every path that does not end in a started launcher returns zero: the classes are already
    /// written and a compilation that used to succeed must still succeed. An exported but
    /// unusable `GRAAL_HOME`, a program with no entry point and a program with several are all
    /// reported, because an instruction that silently did nothing is worse than a slow build.
    private static int generateNativeImage(List<Artifact> artifacts, OutputTarget output,
            List<Path> classpath, PrintWriter standardOut, PrintWriter standardError,
            Map<String, String> environment) {
        GraalToolchain toolchain;
        switch (GraalToolchain.detect(environment)) {
            case GraalDetection.Absent ignored -> {
                return 0;
            }
            case GraalDetection.Invalid invalid -> {
                standardError.println("vsharp: " + GraalToolchain.HOME_VARIABLE + " is set to '"
                        + invalid.home() + "' but " + invalid.reason()
                        + "; no native image was generated");
                return 0;
            }
            case GraalDetection.Present present -> toolchain = present.toolchain();
        }

        List<byte[]> bytecode = new ArrayList<>(artifacts.size());
        for (Artifact artifact : artifacts) {
            bytecode.add(artifact.bytecode());
        }
        List<String> entryPoints = EntryPoints.mainClasses(bytecode);
        if (entryPoints.isEmpty()) {
            standardOut.println("vsharp: no entry point was compiled; no native image was generated");
            return 0;
        }
        if (entryPoints.size() > 1) {
            // Explanations go to standard output and misconfiguration goes to standard error.
            // A test source set with one entry point per suite is a normal, correct
            // compilation, and it must not write to the error stream on every build.
            standardOut.println("vsharp: " + entryPoints.size()
                    + " entry points were compiled (" + String.join(", ", entryPoints)
                    + "); no native image was generated");
            return 0;
        }

        Optional<Path> runtimeLibrary = RuntimeLocation.runtimeLibrary();
        if (runtimeLibrary.isEmpty()) {
            standardError.println("vsharp: the V# runtime library could not be located from this"
                    + " driver, and emitted classes link against it; no native image was generated");
            return 0;
        }

        String mainClass = entryPoints.getFirst();
        Path image = imagePath(output, mainClass);
        // Emitted output first, then the runtime it calls, then whatever Java the program used:
        // the same search order the JVM launch documented in docs/CLI.md already uses.
        List<Path> imageClasspath = new ArrayList<>();
        imageClasspath.add(output.path());
        imageClasspath.add(runtimeLibrary.get());
        imageClasspath.addAll(classpath);
        NativeImageRequest request = NativeImageRequest.of(imageClasspath, mainClass, image);
        NativeImageBuilder builder = new NativeImageBuilder(toolchain);
        standardOut.println("vsharp: generating a native image for " + mainClass + " with "
                + toolchain.nativeImage());
        standardOut.flush();
        NativeImageOutcome outcome = builder.build(request, line -> {
            standardOut.println(line);
            standardOut.flush();
        });
        return switch (outcome) {
            case NativeImageOutcome.Succeeded succeeded -> {
                standardOut.println("vsharp: native image " + succeeded.image() + " generated in "
                        + seconds(succeeded.duration()) + "s");
                yield 0;
            }
            case NativeImageOutcome.Skipped skipped -> {
                standardOut.println("vsharp: " + skipped.reason());
                yield 0;
            }
            case NativeImageOutcome.Failed failed -> {
                // A failed image never fails the compilation. The classes are written and
                // correct; the executable was an extra artifact the environment asked for, and
                // a program that cannot be imaged - one needing AOT metadata V# does not emit,
                // for instance - must not turn a green build red merely because a variable is
                // exported. The failure is stated instead, twice over: the launcher's own
                // output has already streamed, and this names the consequence.
                standardError.println("vsharp: " + failed.message()
                        + "; the compiled classes are unaffected");
                yield 0;
            }
        };
    }

    /// Places the executable beside the classes it was built from, named after its entry point.
    private static Path imagePath(OutputTarget output, String mainClass) {
        int lastDot = mainClass.lastIndexOf('.');
        String simpleName = lastDot < 0 ? mainClass : mainClass.substring(lastDot + 1);
        Path directory = switch (output) {
            case OutputTarget.Directory target -> target.path();
            case OutputTarget.Jar target -> {
                Path parent = target.path().toAbsolutePath().getParent();
                yield parent == null ? Path.of(".") : parent;
            }
        };
        return directory.resolve(simpleName);
    }

    private static String seconds(Duration duration) {
        return String.valueOf(duration.toSeconds());
    }

    private static List<Artifact> artifacts(CompilationResult result) throws IOException {
        List<Artifact> artifacts = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (UnitAnalysis unit : result.units()) {
            if (unit instanceof UnitAnalysis.Emitted emitted) {
                for (byte[] bytecode : emitted.classes().values()) {
                    String name = classFileName(bytecode);
                    if (!names.add(name)) {
                        throw new IOException("duplicate output artifact " + name);
                    }
                    artifacts.add(new Artifact(name, bytecode));
                }
            }
        }
        artifacts.sort(Comparator.comparing(Artifact::name));
        return artifacts;
    }

    private static void writeClassDirectory(Path outputDirectory, List<Artifact> artifacts)
            throws IOException {
        Files.createDirectories(outputDirectory);
        for (Artifact artifact : artifacts) {
            Path path = outputDirectory.resolve(artifact.name());
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(path, artifact.bytecode());
        }
    }

    private static void writeJar(Path jarFile, List<Artifact> artifacts) throws IOException {
        Path parent = jarFile.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(jarFile))) {
            for (Artifact artifact : artifacts) {
                JarEntry entry = new JarEntry(artifact.name());
                entry.setTime(0);
                jar.putNextEntry(entry);
                jar.write(artifact.bytecode());
                jar.closeEntry();
            }
        }
    }

    private static String classFileName(byte[] bytecode) {
        ClassModel model = ClassFile.of().parse(bytecode);
        return model.thisClass().asInternalName() + ".class";
    }

    private static String boundaryMessage(Exception failure) {
        Throwable cause = failure.getCause();
        String message = cause == null ? failure.getMessage() : cause.getMessage();
        return Objects.requireNonNullElse(message, "unknown I/O error");
    }

    /// Splits one `--classpath` value on the platform separator, the spelling every JVM tool
    /// already uses, and keeps the written order because a classpath is a search order. The
    /// option repeats and accumulates, so a long dependency set can be passed in parts.
    private static List<Path> classpathEntries(String value) {
        List<Path> entries = new ArrayList<>();
        for (String entry : value.split(java.io.File.pathSeparator, -1)) {
            if (!entry.isEmpty()) {
                entries.add(Path.of(entry));
            }
        }
        return entries;
    }

    private static CommandLine parseCommandLine(String[] arguments) {
        List<Path> sources = new ArrayList<>();
        Set<String> symbols = new TreeSet<>();
        List<Path> classpath = new ArrayList<>();
        DiagnosticFormatter formatter = DiagnosticFormatter.detailed();
        Path outputDirectory = Path.of(".");
        Path jarFile = null;
        boolean explicitOutputDirectory = false;
        boolean allowUnboundedJoins = false;
        boolean options = true;
        for (int i = 0; i < arguments.length; i++) {
            String argument = Objects.requireNonNull(arguments[i], "argument");
            if (options && argument.equals("--")) {
                options = false;
            } else if (options && argument.equals("--help")) {
                return new CommandLine.Help();
            } else if (options && argument.equals("--version")) {
                return new CommandLine.Version();
            } else if (options && argument.equals("--allow-unbounded-joins")) {
                allowUnboundedJoins = true;
            } else if (options && argument.equals("--diagnostics")) {
                if (i + 1 == arguments.length) {
                    return new CommandLine.Invalid("--diagnostics requires compact or detailed");
                }
                String style = arguments[++i];
                if (style.equals("compact")) {
                    formatter = DiagnosticFormatter.compact();
                } else if (style.equals("detailed")) {
                    formatter = DiagnosticFormatter.detailed();
                } else {
                    return new CommandLine.Invalid(
                            "unknown diagnostic style '" + style + "'");
                }
            } else if (options && argument.equals("--out")) {
                if (i + 1 == arguments.length) {
                    return new CommandLine.Invalid("--out requires a directory");
                }
                if (jarFile != null) {
                    return new CommandLine.Invalid("--out cannot be combined with --jar");
                }
                outputDirectory = Path.of(arguments[++i]);
                explicitOutputDirectory = true;
            } else if (options && argument.startsWith("--out=")) {
                String directory = argument.substring("--out=".length());
                if (directory.isEmpty()) {
                    return new CommandLine.Invalid("--out requires a directory");
                }
                if (jarFile != null) {
                    return new CommandLine.Invalid("--out cannot be combined with --jar");
                }
                outputDirectory = Path.of(directory);
                explicitOutputDirectory = true;
            } else if (options && (argument.equals("--classpath") || argument.equals("-cp"))) {
                if (i + 1 == arguments.length) {
                    return new CommandLine.Invalid("--classpath requires a path");
                }
                classpath.addAll(classpathEntries(arguments[++i]));
            } else if (options && argument.startsWith("--classpath=")) {
                String value = argument.substring("--classpath=".length());
                if (value.isEmpty()) {
                    return new CommandLine.Invalid("--classpath requires a path");
                }
                classpath.addAll(classpathEntries(value));
            } else if (options && argument.equals("--jar")) {
                if (i + 1 == arguments.length) {
                    return new CommandLine.Invalid("--jar requires a file");
                }
                if (explicitOutputDirectory) {
                    return new CommandLine.Invalid("--jar cannot be combined with --out");
                }
                jarFile = Path.of(arguments[++i]);
            } else if (options && argument.startsWith("--jar=")) {
                String file = argument.substring("--jar=".length());
                if (file.isEmpty()) {
                    return new CommandLine.Invalid("--jar requires a file");
                }
                if (explicitOutputDirectory) {
                    return new CommandLine.Invalid("--jar cannot be combined with --out");
                }
                jarFile = Path.of(file);
            } else if (options && argument.equals("--define")) {
                if (i + 1 == arguments.length) {
                    return new CommandLine.Invalid("--define requires a symbol");
                }
                String symbol = arguments[++i];
                if (!validSymbol(symbol)) {
                    return new CommandLine.Invalid("invalid preprocessing symbol '" + symbol + "'");
                }
                symbols.add(symbol);
            } else if (options && argument.startsWith("--define=")) {
                String symbol = argument.substring("--define=".length());
                if (!validSymbol(symbol)) {
                    return new CommandLine.Invalid("invalid preprocessing symbol '" + symbol + "'");
                }
                symbols.add(symbol);
            } else if (options && argument.startsWith("-D")) {
                String symbol = argument.substring(2);
                if (!validSymbol(symbol)) {
                    return new CommandLine.Invalid("invalid preprocessing symbol '" + symbol + "'");
                }
                symbols.add(symbol);
            } else if (options && argument.startsWith("-")) {
                return new CommandLine.Invalid("unknown option '" + argument + "'");
            } else {
                sources.add(Path.of(argument));
            }
        }
        if (sources.isEmpty()) {
            return new CommandLine.Invalid("no source files were provided");
        }
        OutputTarget output = jarFile == null
                ? new OutputTarget.Directory(outputDirectory)
                : new OutputTarget.Jar(jarFile);
        return new CommandLine.Check(sources, symbols, classpath, formatter, output,
                allowUnboundedJoins);
    }

    private static boolean validSymbol(String value) {
        if (value.isEmpty()) {
            return false;
        }
        int first = value.codePointAt(0);
        if (!SyntaxFacts.isIdentifierStart(first)) {
            return false;
        }
        for (int i = Character.charCount(first); i < value.length();) {
            int codePoint = value.codePointAt(i);
            if (!SyntaxFacts.isIdentifierPart(codePoint)) {
                return false;
            }
            i += Character.charCount(codePoint);
        }
        return true;
    }

    private static String implementationVersion() {
        return VERSION;
    }

    private static String loadVersion() {
        try (InputStream input = Cli.class.getResourceAsStream("version.txt")) {
            if (input == null) {
                return "development";
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException failure) {
            return "development";
        }
    }

    private sealed interface CommandLine {

        record Check(List<Path> sources, Set<String> symbols, List<Path> classpath,
                DiagnosticFormatter formatter, OutputTarget output, boolean allowUnboundedJoins)
                implements CommandLine {
            public Check {
                sources = List.copyOf(sources);
                symbols = Collections.unmodifiableSet(new TreeSet<>(symbols));
                // Search order, so a classpath keeps the order written rather than being sorted.
                classpath = List.copyOf(classpath);
                Objects.requireNonNull(formatter, "formatter");
                Objects.requireNonNull(output, "output");
            }
        }

        record Invalid(String message) implements CommandLine {
            public Invalid { Objects.requireNonNull(message, "message"); }
        }

        record Help() implements CommandLine { }

        record Version() implements CommandLine { }
    }

    private sealed interface OutputTarget {

        Path path();

        record Directory(Path path) implements OutputTarget {
            public Directory { Objects.requireNonNull(path, "path"); }
        }

        record Jar(Path path) implements OutputTarget {
            public Jar { Objects.requireNonNull(path, "path"); }
        }
    }

    private record Artifact(String name, byte[] bytecode) {
        Artifact {
            Objects.requireNonNull(name, "name");
            bytecode = Objects.requireNonNull(bytecode, "bytecode").clone();
        }

        @Override
        public byte[] bytecode() {
            return bytecode.clone();
        }
    }
}
