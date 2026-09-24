package vsharp.compiler.semantics.binding;

import java.io.IOException;
import java.lang.classfile.ClassModel;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/// A process-wide cache of class files read from the module path and the declared classpath.
///
/// Every cache inside [JavaInterop] belongs to one binding run, which is right for the symbols
/// it derives - they carry that run's types - but wrong for the *bytes* underneath them. A
/// compiler invocation reads each class file once and exits, so nothing was lost; a language
/// server analyses on every edit and re-read them all every time. Measured over a real application
/// application: a warm analysis of its 45 sources takes 1325 ms against its 57 jars and 450 ms
/// without them, and of that ~875 ms difference only ~94 ms is the jar scan at construction.
/// The rest is this: reading a class out of a jar costs ~0.18 ms and the application touches
/// thousands of them, while *parsing* the bytes costs ~0.01 ms - so the cost is I/O, and I/O
/// over immutable inputs is exactly what a cache is for.
///
/// ## Why this is safe to share
///
/// A class file is immutable for as long as the artifact holding it is. The cache is therefore
/// keyed by a *signature* of the classpath - each entry's path, size and modification time - so
/// a jar rebuilt underneath a running server yields a different signature and its old entries
/// are simply never consulted again. Results are identical with or without the cache; only the
/// time taken differs, which keeps compilation deterministic.
///
/// ## Why a directory entry disables it
///
/// A signature can only be cheap if it is a fixed amount of work per entry. That holds for a
/// jar, whose size and timestamp change whenever its contents do, and fails for a directory,
/// whose timestamp does not change when a class file nested inside it is rewritten - precisely
/// what happens when a project compiles into a directory on its own classpath. Rather than
/// hash a tree on every construction, a classpath containing any directory is marked
/// uncacheable and reads go straight through. Correctness first; the common dependency shape
/// is a jar, and that is the shape that is slow.
final class ClassFileCache {

    private static final System.Logger LOG = System.getLogger(ClassFileCache.class.getName());

    /// Marks a name that is known *not* to resolve, so a repeated miss costs a map lookup
    /// rather than a walk of every classpath entry.
    private static final Object ABSENT = new Object();

    /// Entries retained. Each holds one parsed class file, so this bounds memory rather than
    /// time: a run that touches more classes than this evicts the least recently used and
    /// re-reads it if asked again. Large enough to hold everything a realistic application
    /// resolves - a real application touches a few thousand - and small enough to bound a server
    /// that has been open for days across many projects.
    private static final int MAX_ENTRIES = 8192;

    /// Signature component for a classpath that must not be cached.
    /// The NUL is written as an escape rather than as a literal control character: a raw
    /// NUL makes this file binary to every ordinary text tool - grep skips it and diffs
    /// refuse it - while the value it denotes is identical.
    private static final String UNCACHEABLE = "\0uncacheable";

    private static final Map<String, Object> ENTRIES =
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Object> eldest) {
                    return size() > MAX_ENTRIES;
                }
            };

    /// The loader serving one classpath, remembered with the signature it was built for.
    private record Loaders(String signature, URLClassLoader loader) {}

    /// One loader per *set of classpath paths*, so that a rebuilt jar can be detected and the
    /// loader holding the old one closed.
    ///
    /// Closing matters for correctness, not tidiness. An unclosed [URLClassLoader] leaves its
    /// jar open in the JDK's process-wide jar cache, and every later loader over the same URL
    /// is handed that same stale handle - so a dependency rebuilt underneath a running server
    /// stays invisible for the life of the process, no matter how many loaders are created.
    /// Measured directly: a second loader over a rewritten jar still reads the first version's
    /// bytes unless the first loader was closed.
    private static final Map<String, Loaders> LOADERS = new java.util.HashMap<>();

    /// One package set per classpath signature. Unbounded in principle and tiny in practice:
    /// a server sees one signature per project, and a new one only when a dependency changes.
    private static final Map<String, Set<String>> NAMESPACES = new ConcurrentHashMap<>();

    private ClassFileCache() {
        throw new AssertionError("No instances");
    }

    /// A key identifying `classpath` for cache purposes, or empty when it must not be cached.
    ///
    /// The empty classpath is cacheable and yields a stable signature: those reads come from
    /// the JDK image, which cannot change while the process runs.
    static Optional<String> signatureOf(List<Path> classpath) {
        StringBuilder signature = new StringBuilder("jdk");
        for (Path entry : classpath) {
            try {
                if (!Files.isRegularFile(entry)) {
                    return Optional.empty();
                }
                // Full instant, not milliseconds: a jar rewritten within the same millisecond
                // to the same length would otherwise carry an identical signature and be
                // served from the stale cache. Nanosecond resolution makes that collision
                // require two writes in the same nanosecond, which no build produces.
                signature.append('|').append(entry.toAbsolutePath())
                        .append(':').append(Files.size(entry))
                        .append(':').append(Files.getLastModifiedTime(entry).toInstant());
            } catch (IOException | SecurityException e) {
                // An entry that cannot be described cannot be proven unchanged.
                return Optional.empty();
            }
        }
        return Optional.of(signature.toString());
    }

    /// The class file for `qualifiedName`, reading it through `loader` on a miss.
    ///
    /// `signature` empty means the classpath is uncacheable, in which case `loader` runs every
    /// time. A `null` from `loader` is remembered as an absence, because failing to resolve a
    /// name is as repeatable as resolving one.
    static ClassModel get(Optional<String> signature, String qualifiedName,
            Supplier<ClassModel> loader) {
        if (signature.isEmpty()) {
            return loader.get();
        }
        String key = signature.get() + '\0' + qualifiedName;
        synchronized (ENTRIES) {
            Object cached = ENTRIES.get(key);
            if (cached == ABSENT) {
                return null;
            }
            if (cached != null) {
                return (ClassModel) cached;
            }
        }
        // Loaded outside the lock: reading a class file is I/O, and holding the monitor across
        // it would serialise every concurrent analysis behind the slowest read. Two threads
        // may load the same class once each, which wastes a read and never produces a wrong
        // answer, because the bytes they read are the same bytes.
        ClassModel model = loader.get();
        synchronized (ENTRIES) {
            ENTRIES.put(key, model == null ? ABSENT : model);
        }
        return model;
    }

    /// The package set published by a classpath, computed by `scan` on a miss.
    ///
    /// Held separately from the class files because there is exactly one per signature, and it
    /// must not be evicted by the class-file traffic that shares the cache: recomputing it
    /// means reopening every jar.
    static Set<String> namespaces(Optional<String> signature, Supplier<Set<String>> scan) {
        if (signature.isEmpty()) {
            return scan.get();
        }
        synchronized (NAMESPACES) {
            Set<String> cached = NAMESPACES.get(signature.get());
            if (cached != null) {
                return cached;
            }
        }
        Set<String> scanned = Set.copyOf(scan.get());
        synchronized (NAMESPACES) {
            NAMESPACES.put(signature.get(), scanned);
        }
        return scanned;
    }

    /// The loader for `entries`, reused while their signature holds.
    ///
    /// When the signature has moved on, the previous loader is closed before a new one is
    /// built: that is what evicts the stale jar handle from the JDK's cache and lets the
    /// rebuilt artifact be read. An uncacheable classpath gets a fresh loader every time,
    /// which is correct and merely slower.
    static ClassLoader loaderFor(Optional<String> signature, List<Path> entries,
            ClassLoader parent) {
        if (entries.isEmpty()) {
            return parent;
        }
        URL[] urls = urlsOf(entries);
        if (signature.isEmpty()) {
            return new URLClassLoader(urls, parent);
        }
        String key = entries.toString();
        synchronized (LOADERS) {
            Loaders existing = LOADERS.get(key);
            if (existing != null && existing.signature().equals(signature.get())) {
                return existing.loader();
            }
            if (existing != null) {
                try {
                    existing.loader().close();
                } catch (IOException e) {
                    // A loader that will not close still has to be replaced; the worst case
                    // is the leaked handle we were trying to avoid.
                    LOG.log(System.Logger.Level.WARNING,
                            "Could not close the superseded class loader", e);
                }
            }
            URLClassLoader created = new URLClassLoader(urls, parent);
            LOADERS.put(key, new Loaders(signature.get(), created));
            return created;
        }
    }

    private static URL[] urlsOf(List<Path> entries) {
        URL[] urls = new URL[entries.size()];
        for (int index = 0; index < entries.size(); index++) {
            try {
                urls[index] = entries.get(index).toUri().toURL();
            } catch (MalformedURLException e) {
                throw new IllegalStateException("Classpath entry is not a readable location: "
                        + entries.get(index), e);
            }
        }
        return urls;
    }

    /// Drops every entry. Exists for tests, which must be able to measure a cold read.
    static void clear() {
        synchronized (ENTRIES) {
            ENTRIES.clear();
        }
        NAMESPACES.clear();
        synchronized (LOADERS) {
            for (Loaders loaders : LOADERS.values()) {
                try {
                    loaders.loader().close();
                } catch (IOException ignored) {
                    // Closing on the way out is best-effort.
                }
            }
            LOADERS.clear();
        }
    }

    /// The number of retained entries, for tests.
    static int size() {
        synchronized (ENTRIES) {
            return ENTRIES.size();
        }
    }
}
