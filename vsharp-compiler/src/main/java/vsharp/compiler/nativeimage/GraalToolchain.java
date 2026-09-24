package vsharp.compiler.nativeimage;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/// A validated GraalVM installation, addressed only through the paths a driver actually uses.
///
/// The compiler learns nothing about how a particular machine makes GraalVM runnable: it
/// validates whatever `GRAAL_HOME` names and invokes that home's own `bin/native-image`. A host
/// whose stock launcher does not run (a distribution without a generic-Linux dynamic loader, for
/// instance) is expected to export a home whose launcher does, which keeps every such workaround
/// in the environment where it belongs.
public record GraalToolchain(Path home, Path nativeImage) {

    /// The environment variable whose presence is the sole trigger for native image generation.
    public static final String HOME_VARIABLE = "GRAAL_HOME";

    private static final String LAUNCHER_NAME = "native-image";
    private static final String WINDOWS_LAUNCHER_NAME = "native-image.cmd";

    public GraalToolchain {
        Objects.requireNonNull(home, "home");
        Objects.requireNonNull(nativeImage, "nativeImage");
    }

    /// Looks for an installation in the given environment.
    ///
    /// @param environment the process environment, passed explicitly so that callers and tests
    ///     decide what the compiler sees rather than inheriting an ambient value
    /// @return what the `GRAAL_HOME` entry of that environment names
    public static GraalDetection detect(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        return validate(environment.get(HOME_VARIABLE));
    }

    /// Validates one candidate installation directory.
    ///
    /// @param home the directory to validate, which may be `null` or blank
    /// @return [GraalDetection.Absent] when nothing was named, [GraalDetection.Invalid] naming
    ///     the failed check, or [GraalDetection.Present] with the usable toolchain
    public static GraalDetection validate(String home) {
        if (home == null) {
            return new GraalDetection.Absent(HOME_VARIABLE + " is not set");
        }
        if (home.isBlank()) {
            return new GraalDetection.Absent(HOME_VARIABLE + " is empty");
        }
        Path directory;
        try {
            directory = Path.of(expandUserHome(home)).toAbsolutePath().normalize();
        } catch (InvalidPathException failure) {
            return new GraalDetection.Invalid(home, "not a valid path: " + failure.getMessage());
        }
        if (!Files.isDirectory(directory)) {
            return new GraalDetection.Invalid(home, "not a directory: " + directory);
        }
        if (!Files.isDirectory(directory.resolve("lib").resolve("svm"))) {
            return new GraalDetection.Invalid(home,
                    "not a GraalVM installation with native image support: no lib/svm directory in "
                            + directory);
        }
        Path launcher = directory.resolve("bin").resolve(LAUNCHER_NAME);
        if (!Files.isRegularFile(launcher)) {
            Path windows = directory.resolve("bin").resolve(WINDOWS_LAUNCHER_NAME);
            if (!Files.isRegularFile(windows)) {
                return new GraalDetection.Invalid(home, "no native image launcher at " + launcher);
            }
            launcher = windows;
        }
        if (!Files.isExecutable(launcher)) {
            return new GraalDetection.Invalid(home, "native image launcher is not executable: " + launcher);
        }
        return new GraalDetection.Present(new GraalToolchain(directory, launcher));
    }

    /// Expands a leading `~` against `user.home`, because an exported variable is a string and
    /// not a shell word: a home written as `~/graalvm` inside quotes reaches this process with
    /// the tilde intact, and resolving it relative to the working directory - which is what
    /// [Path] would otherwise do - can only produce a directory that does not exist.
    ///
    /// @param home the value as exported
    /// @return the value with a leading `~` element replaced, or the value itself
    private static String expandUserHome(String home) {
        String userHome = System.getProperty("user.home");
        if (userHome == null || userHome.isBlank()) {
            return home;
        }
        if (home.equals("~")) {
            return userHome;
        }
        if (home.startsWith("~/") || home.startsWith("~" + File.separator)) {
            return userHome + home.substring(1);
        }
        return home;
    }
}
