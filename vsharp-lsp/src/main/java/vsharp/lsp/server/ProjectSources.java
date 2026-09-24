package vsharp.lsp.server;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.Set;
import java.util.stream.Stream;
import vsharp.compiler.source.SourceFile;

/// The set of sources the server compiles: the whole project, not just the open buffers.
///
/// Compiling only what the editor has open is the mistake this class exists to correct. A V#
/// program's types live across files, so opening one file of a real application and compiling
/// it alone reports every *sibling* type as missing. Measured on a real application: opening
/// `PetController.vs` by itself produced 19 `VS0246` errors naming `Pet`, `Outcome`,
/// `Species`, `PetPatch` and `PetRelation` - all of them declared in files the editor had not
/// opened, none of them a real error, on a file the CLI builds cleanly. A user seeing
/// that concludes the extension is broken, and is right to.
///
/// So the project is discovered from disk and the open buffers are layered on top: an open
/// file contributes its *unsaved* text, every other `.vs` file under the project's `src` tree
/// contributes what is on disk. That is the source set the build compiles. See
/// [#scanRootFor] for why the scan stops at `src` rather than the project root.
final class ProjectSources {

    private static final System.Logger LOG = System.getLogger(ProjectSources.class.getName());

    /// Directories never searched for sources. Build outputs contain copies of sources in
    /// some layouts, and a duplicate declaration would be reported as a real error.
    private static final Set<String> IGNORED = Set.of(
            "build", "out", "target", "bin", ".git", ".gradle", "node_modules", ".idea",
            ".vscode", ".metals", "scratch");

    /// Most sources read from one project. A tree larger than this is not a V# project the
    /// editor should be compiling in its entirety, and the bound keeps a mistaken root - a
    /// home directory, say - from stalling the server.
    private static final int MAX_SOURCES = 2000;

    /// How far up to look for a project marker before giving up and using the file's own
    /// directory.
    private static final int MAX_DEPTH = 24;

    private ProjectSources() {
        throw new AssertionError("No instances");
    }

    /// The compiler inputs for `open`, in deterministic order.
    ///
    /// Open documents come first and in URI order, so the source list - and therefore
    /// diagnostic ordering - does not depend on how the filesystem enumerated the rest.
    static List<SourceFile> discover(Collection<DocumentStore.Document> open) {
        SequencedMap<String, SourceFile> byName = new LinkedHashMap<>();
        for (DocumentStore.Document document : open) {
            SourceFile file = document.source();
            byName.put(file.name(), file);
        }

        for (Path root : scanRootsOf(open)) {
            for (Path path : sourcesUnder(root)) {
                String name = path.toString();
                if (byName.containsKey(name)) {
                    // The editor's buffer wins: it may hold edits that are not saved yet, and
                    // analysing the saved text instead would report errors the user has
                    // already fixed on screen.
                    continue;
                }
                try {
                    byName.put(name, SourceFile.read(path));
                } catch (UncheckedIOException e) {
                    LOG.log(System.Logger.Level.DEBUG, "Skipping unreadable source " + path, e);
                }
                if (byName.size() >= MAX_SOURCES) {
                    LOG.log(System.Logger.Level.WARNING,
                            "Stopped discovering sources at {0} files under {1}",
                            MAX_SOURCES, root);
                    return List.copyOf(byName.values());
                }
            }
        }
        return List.copyOf(byName.values());
    }

    /// The directories to scan for the open documents, without duplicates.
    private static Set<Path> scanRootsOf(Collection<DocumentStore.Document> open) {
        Set<Path> roots = new LinkedHashSet<>();
        for (DocumentStore.Document document : open) {
            Optional<Path> path = Uris.toPath(document.uri()).map(Path::toAbsolutePath);
            if (path.isEmpty()) {
                continue;
            }
            Optional<Path> project = rootOf(path.get().getParent());
            if (project.isEmpty()) {
                continue;
            }
            scanRootFor(project.get(), path.get()).ifPresent(roots::add);
        }
        return roots;
    }

    /// The directory holding a project's *sources*, which is not the project root.
    ///
    /// Scanning the root outright was too greedy and produced the very failure this class
    /// exists to remove. One real application keeps 81 `.vs` files under its root, of which
    /// 72 are standalone probes - deliberately conflicting one-file experiments, several
    /// declaring the same entry point. Compiling those together with the application broke
    /// binding, and because none of them was open in the editor their diagnostics were
    /// dropped: the user got no errors *and* no symbols, which is indistinguishable from a
    /// dead extension.
    ///
    /// The rule is therefore conventional rather than exhaustive: a document under the
    /// project's `src` tree is compiled with that tree, which is the set the build compiles.
    /// A document outside it - a scratch file, a probe - is compiled alone, together with
    /// whatever else the editor happens to have open. Analysing too little is a visible,
    /// local error the user can act on; analysing an unrelated tree is a silent one.
    private static Optional<Path> scanRootFor(Path project, Path document) {
        Path sources = project.resolve("src");
        return Files.isDirectory(sources) && document.startsWith(sources)
                ? Optional.of(sources)
                : Optional.empty();
    }

    /// The project root above `directory`.
    ///
    /// `vsharp.classpath` is the strongest marker, because it is written by the build for
    /// exactly this project; `.git` and `settings.gradle.kts` are the usual fallbacks. With no
    /// marker at all the file's own directory is used, which keeps a stray file outside any
    /// project analysable without dragging in a whole home directory.
    private static Optional<Path> rootOf(Path directory) {
        Path current = directory;
        for (int depth = 0; current != null && depth < MAX_DEPTH; depth++) {
            if (Files.isRegularFile(current.resolve(Classpaths.FILE_NAME))
                    || Files.isDirectory(current.resolve(".git"))
                    || Files.isRegularFile(current.resolve("settings.gradle.kts"))
                    || Files.isRegularFile(current.resolve("build.gradle.kts"))) {
                return Optional.of(current);
            }
            current = current.getParent();
        }
        return Optional.ofNullable(directory);
    }

    /// Every `.vs` file under `root`, in sorted order, skipping build and tooling directories.
    private static List<Path> sourcesUnder(Path root) {
        List<Path> found = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(path -> path.toString().endsWith(".vs"))
                    .filter(Files::isRegularFile)
                    .filter(path -> !isIgnored(root, path))
                    .sorted()
                    .forEach(found::add);
        } catch (IOException | UncheckedIOException e) {
            LOG.log(System.Logger.Level.WARNING, "Could not scan " + root + " for sources", e);
        }
        return found;
    }

    private static boolean isIgnored(Path root, Path path) {
        Path relative = root.relativize(path);
        for (Path segment : relative) {
            if (IGNORED.contains(segment.toString())) {
                return true;
            }
        }
        return false;
    }
}
