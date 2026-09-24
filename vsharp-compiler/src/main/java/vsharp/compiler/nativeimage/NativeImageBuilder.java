package vsharp.compiler.nativeimage;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/// Runs a GraalVM native image build for an already emitted program.
///
/// The launcher is a separate process on purpose: it is a different JVM with its own module
/// graph and its own flags, and the compiler holds no GraalVM code on its own class or module
/// path, which keeps the dependency rule intact - the toolchain is an installed program that
/// happens to be present, exactly like a C compiler.
public final class NativeImageBuilder {

    /// How many trailing launcher lines a failure carries, which is enough to hold a GraalVM
    /// error block without turning a build log into a transcript.
    private static final int RETAINED_LINES = 40;

    private static final String NO_FALLBACK = "--no-fallback";

    /// What a Windows launcher appends to the name passed to `-o`, because a program that is
    /// not named `.exe` is not executable there.
    private static final String WINDOWS_IMAGE_SUFFIX = ".exe";

    private final GraalToolchain toolchain;

    private final HostLibc libc;

    /// Creates a builder for one installation on this host.
    ///
    /// @param toolchain the validated installation to invoke
    public NativeImageBuilder(GraalToolchain toolchain) {
        this(toolchain, HostLibc.detect());
    }

    /// Creates a builder for one installation on a stated host.
    ///
    /// @param toolchain the validated installation to invoke
    /// @param libc the host's C library, which decides how the image is linked
    public NativeImageBuilder(GraalToolchain toolchain, HostLibc libc) {
        this.toolchain = Objects.requireNonNull(toolchain, "toolchain");
        this.libc = Objects.requireNonNull(libc, "libc");
    }

    /// Builds the exact command line a request produces.
    ///
    /// @param request the build to describe
    /// @return the command, launcher first
    public List<String> command(NativeImageRequest request) {
        Objects.requireNonNull(request, "request");
        List<String> command = new ArrayList<>();
        command.add(toolchain.nativeImage().toString());
        // A fallback image is a launcher that still needs a JVM installed, which would silently
        // defeat the only reason to build one, so it is refused rather than accepted quietly.
        command.add(NO_FALLBACK);
        // Before the request's own options, because they are the host's terms rather than
        // this build's: a musl host cannot produce a dynamically linked image at all.
        command.addAll(libc.imageOptions());
        command.addAll(request.options());
        command.add("-classpath");
        command.add(request.classpath().stream()
                .map(Path::toString)
                .collect(Collectors.joining(File.pathSeparator)));
        command.add("-o");
        command.add(request.image().toString());
        command.add(request.mainClass());
        return List.copyOf(command);
    }

    /// Runs one native image build, streaming the launcher's output as it arrives.
    ///
    /// @param request what to build
    /// @param log receives every line the launcher writes, so a long build is visible while it
    ///     runs rather than only after it ends
    /// @return what the launcher did
    public NativeImageOutcome build(NativeImageRequest request, Consumer<String> log) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(log, "log");
        List<String> command = command(request);
        Path parent = request.image().toAbsolutePath().getParent();
        List<Path> spellings = imageSpellings(request.image());
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            // The previous executable is removed before the launcher starts, not after it
            // succeeds. A build that fails must not leave a runnable file behind: a stale
            // binary that still starts is the one failure mode a user cannot see. Every
            // spelling is cleared, so a Windows `.exe` cannot survive a build that stops
            // producing one.
            for (Path spelling : spellings) {
                Files.deleteIfExists(spelling);
            }
        } catch (IOException failure) {
            return new NativeImageOutcome.Failed(-1,
                    "cannot prepare " + request.image() + ": " + message(failure), List.of());
        }
        Instant started = Instant.now();
        Deque<String> retained = new ArrayDeque<>();
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException failure) {
            return new NativeImageOutcome.Failed(-1,
                    "cannot run " + toolchain.nativeImage() + ": " + message(failure), List.of());
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.accept(line);
                retained.addLast(line);
                if (retained.size() > RETAINED_LINES) {
                    retained.removeFirst();
                }
            }
            int exitCode = process.waitFor();
            Duration duration = Duration.between(started, Instant.now());
            if (exitCode != 0) {
                return new NativeImageOutcome.Failed(exitCode,
                        "native image generation failed with exit code " + exitCode,
                        List.copyOf(new ArrayList<>(retained)));
            }
            Optional<Path> produced = spellings.stream().filter(Files::isRegularFile).findFirst();
            if (produced.isEmpty()) {
                return new NativeImageOutcome.Failed(0,
                        "native image generation reported success but produced no file at "
                                + describe(spellings),
                        List.copyOf(new ArrayList<>(retained)));
            }
            return new NativeImageOutcome.Succeeded(produced.get(), duration);
        } catch (IOException failure) {
            process.destroy();
            return new NativeImageOutcome.Failed(-1,
                    "cannot read the native image launcher output: " + message(failure),
                    List.copyOf(new ArrayList<>(retained)));
        } catch (InterruptedException interruption) {
            process.destroy();
            Thread.currentThread().interrupt();
            return new NativeImageOutcome.Failed(-1, "native image generation was interrupted",
                    List.copyOf(new ArrayList<>(retained)));
        }
    }

    /// Every file name the launcher may write for one request, most exact first.
    ///
    /// A Windows `native-image` appends `.exe` to the `-o` name, so the file a build produces
    /// is not always the file the request named, and a check against the requested spelling
    /// alone would call a successful Windows build a failure. The answer is observed rather
    /// than predicted: both spellings are cleared before the launcher runs, so whichever one
    /// exists afterwards is the executable this build produced. That keeps the rule out of an
    /// `os.name` test, which is what makes it testable on a host that is not Windows.
    ///
    /// @param image the requested executable
    /// @return the requested name, then its `.exe` form when the request does not already
    ///         carry one
    private static List<Path> imageSpellings(Path image) {
        Path name = image.getFileName();
        if (name == null || name.toString().endsWith(WINDOWS_IMAGE_SUFFIX)) {
            return List.of(image);
        }
        return List.of(image, image.resolveSibling(name + WINDOWS_IMAGE_SUFFIX));
    }

    private static String describe(List<Path> spellings) {
        List<String> names = new ArrayList<>();
        for (Path spelling : spellings) {
            names.add(spelling.toString());
        }
        return String.join(" or ", names);
    }

    private static String message(Exception failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName()
                : message;
    }
}
