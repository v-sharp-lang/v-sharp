package vsharp.compiler.nativeimage;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/// The C library a Linux host provides, which decides how a native image must be linked.
///
/// Nothing else in the toolchain depends on the host's libc: the compiler is pure Java and its
/// output is bytecode. Native image generation is the one stage that produces a host binary,
/// and there GraalVM needs to be told: a musl host - Alpine and the container images built on
/// it - links a *static* image against musl, and the dynamic default produces a binary that
/// does not start. A glibc host needs nothing said.
///
/// The question is answered by looking for musl's own dynamic loader rather than by parsing a
/// distribution name, because the loader is what actually differs and its name is fixed.
public enum HostLibc {

    /// The ordinary Linux case, and every non-Linux host: no linking options are added.
    DEFAULT,

    /// A Linux host whose loader is musl, where an image must be statically linked.
    MUSL;

    private static final String MUSL_LOADER_PREFIX = "ld-musl-";

    /// The library directories a loader is published in, in search order.
    private static final List<String> LOADER_DIRECTORIES = List.of("lib", "usr/lib", "lib64");

    /// Detects the libc of the host this process runs on.
    ///
    /// @return the detected libc, or [#DEFAULT] when the host is not Linux or the loader
    ///         cannot be read
    public static HostLibc detect() {
        return detect(System.getProperty("os.name", ""), Path.of("/"));
    }

    /// Detects a libc against an explicit host description, so the decision is testable
    /// without being on the host it describes.
    ///
    /// @param osName the `os.name` value to interpret
    /// @param root the filesystem root to search for a loader
    /// @return the detected libc
    public static HostLibc detect(String osName, Path root) {
        Objects.requireNonNull(osName, "osName");
        Objects.requireNonNull(root, "root");
        if (!osName.toLowerCase(Locale.ROOT).contains("linux")) {
            return DEFAULT;
        }
        for (String directory : LOADER_DIRECTORIES) {
            if (hasMuslLoader(root.resolve(directory))) {
                return MUSL;
            }
        }
        return DEFAULT;
    }

    /// The launcher options this libc requires.
    ///
    /// @return the options, in launcher order, or an empty list when none are needed
    public List<String> imageOptions() {
        return this == MUSL ? List.of("--static", "--libc=musl") : List.of();
    }

    private static boolean hasMuslLoader(Path directory) {
        if (!Files.isDirectory(directory)) {
            return false;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                Path name = entry.getFileName();
                if (name != null && name.toString().startsWith(MUSL_LOADER_PREFIX)) {
                    return true;
                }
            }
            return false;
        } catch (IOException | RuntimeException unreadable) {
            // An unreadable library directory is not evidence of musl, and a native image
            // build that would fail is a better outcome than a static link forced by a guess.
            return false;
        }
    }
}
