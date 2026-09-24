package vsharp.compiler.api;

import java.util.Objects;

/// The monotonic level of the language surface this compiler implements.
///
/// Release versions cannot answer "is this compiler older than that one": every jar in this
/// project reports the same release version, and the jars are built with timestamps stripped so
/// reproducible. Something that increases when the accepted syntax changes is needed instead,
/// and this is it.
///
/// The number exists because an editor and a build can run different compilers. A language
/// server bundled with an extension is a copy, and a copy four days old reported thirteen
/// syntax errors in a file the CLI compiled cleanly - a parser without the `await` operator
/// reads `await Work()` as a declaration and asks for a semicolon. Nothing
/// detected it, because both halves called themselves by the same release version.
///
/// Bump [#CURRENT] whenever the compiler starts accepting source that an earlier build would
/// reject. Do not bump it for a fix that only changes what is *rejected*, or for anything
/// invisible to source: the number answers one question, whether source written for a project
/// can be parsed by a given compiler.
public final class SurfaceVersion {

    /// Level 1: the surface before `async`/`await`.
    ///
    /// A compiler that predates this class reports no level at all, which reads as older than
    /// every level and is exactly right.
    public static final int INITIAL = 1;

    /// Level 2: `async`, `await` in any body, `Task`/`Task<T>`, `async void`, and the refusal
    /// of unbounded task joins.
    public static final int ASYNC = 2;

    /// Level 3: extension methods, and a type parameter inside an array, `params`, `ref`/`out`,
    /// a tuple or a nullable.
    ///
    /// Every one of these is source an earlier build rejects rather than misreads, which is the
    /// condition this number exists for. A level-2 compiler answers `text.WordCount()` with
    /// "'string' does not contain a definition for 'WordCount'" and refuses
    /// `Second&lt;T&gt;(T[] items)` with `VS20002`; it also stops
    /// with an internal error on `value = default` as an assignment. The *rejections*
    /// added in the same period - unmet `where` constraints and `==` over an unconstrained
    /// type parameter - deliberately do not contribute: this number answers whether a
    /// project's source can be parsed and bound by a given compiler, not whether that compiler
    /// is strict enough.
    public static final int EXTENSIONS = 3;

    /// Level 4: target-typed conditionals and switch expressions, and the conversion of
    /// a keyword value type to an interface its boxed carrier implements.
    ///
    /// Both admit source a level-3 build refuses outright rather than misreads, which is the
    /// only condition that moves this number. `return keep ? value : null;` against a declared
    /// `int?` return reports `VS0173` on a level-3 compiler - the arms share no common type of
    /// their own, and nothing supplied one - and `Comparable c = 42;` fails its conversion
    /// there for the same kind of reason: the boxed carrier's interfaces were not modelled.
    ///
    /// Recorded late. Level 3 was published and these two landed at
    /// and 250, so builds between those points reported a level lower than the
    /// source they accept. That direction is the safe one - a project descriptor written by
    /// such a build asks for less than its compiler gives - but it is still a stale number,
    /// and this corrects it.
    public static final int TARGET_TYPING = 4;

    /// The level this build implements.
    public static final int CURRENT = TARGET_TYPING;

    /// The directive a build writes into `vsharp.classpath` to record the level its compiler
    /// implements.
    ///
    /// It is written as a comment because every reader of that file already skips `#` lines,
    /// so an older language server ignores it instead of failing on it, and a newer one can
    /// read a descriptor written by an older build and simply find nothing.
    public static final String DIRECTIVE = "#!surface ";

    private SurfaceVersion() {
        throw new AssertionError("No instances");
    }

    /// Reads the level a descriptor line records.
    ///
    /// @param line one line of a `vsharp.classpath` descriptor
    /// @return the level the line records, or empty when it is not a surface directive or
    ///     carries something that is not a number
    public static java.util.OptionalInt parse(String line) {
        Objects.requireNonNull(line, "line");
        String text = line.strip();
        if (!text.startsWith(DIRECTIVE)) {
            return java.util.OptionalInt.empty();
        }
        try {
            return java.util.OptionalInt.of(
                    Integer.parseInt(text.substring(DIRECTIVE.length()).strip()));
        } catch (NumberFormatException malformed) {
            return java.util.OptionalInt.empty();
        }
    }

    /// The directive line a build should write for this compiler.
    ///
    /// @return the complete line, without a terminator
    public static String directive() {
        return DIRECTIVE + CURRENT;
    }
}
