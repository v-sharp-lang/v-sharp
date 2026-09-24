package vsharp.cli;

import java.lang.module.ResolvedModule;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/// Finds the V# runtime library that emitted classes link against.
///
/// Compiled output calls `vsharp.runtime` helpers for formatting, indexing, checked arithmetic
/// and the console surface, so anything that loads those classes - a JVM launch or a native
/// image build - needs the library on its path. The driver is already running on it, so the
/// honest answer is wherever this process resolved it from, rather than a guessed install layout.
final class RuntimeLocation {

    private static final String MODULE_NAME = "vsharp.runtime";
    private static final String JAR_PREFIX = "vsharp-runtime";

    private RuntimeLocation() {
        throw new AssertionError("No instances");
    }

    /// @return the runtime library path, or empty when the driver runs from exploded classes
    ///         that no single file names
    static Optional<Path> runtimeLibrary() {
        return fromModulePath().or(RuntimeLocation::fromClassPath);
    }

    private static Optional<Path> fromModulePath() {
        return ModuleLayer.boot().configuration().findModule(MODULE_NAME)
                .map(ResolvedModule::reference)
                .flatMap(reference -> reference.location())
                .flatMap(RuntimeLocation::toPath);
    }

    private static Optional<Path> fromClassPath() {
        String classPath = System.getProperty("java.class.path", "");
        List<Path> candidates = new ArrayList<>();
        for (String entry : classPath.split(java.io.File.pathSeparator, -1)) {
            if (entry.isEmpty()) {
                continue;
            }
            Path path = Path.of(entry);
            Path name = path.getFileName();
            if (name != null && name.toString().startsWith(JAR_PREFIX)) {
                candidates.add(path.toAbsolutePath().normalize());
            }
        }
        return candidates.isEmpty() ? Optional.empty() : Optional.of(candidates.getFirst());
    }

    private static Optional<Path> toPath(URI location) {
        if (!"file".equals(location.getScheme())) {
            return Optional.empty();
        }
        try {
            return Optional.of(Path.of(location).toAbsolutePath().normalize());
        } catch (IllegalArgumentException failure) {
            return Optional.empty();
        }
    }
}
