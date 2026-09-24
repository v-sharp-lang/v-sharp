# V# CLI output and runtime notes

The installed command is `vsharp` from the `vsharp-cli` Gradle application.

```bash
gradle -q :vsharp-cli:installDist
vsharp-cli/build/install/vsharp/bin/vsharp --out build/classes-vs src/Program.vs
```

The driver reads UTF-8 `.vs` sources, runs the compiler through bytecode generation,
prints diagnostics, and writes JVM artifacts only when there are no error diagnostics.
Warnings are printed to standard output and do not block artifact emission.

## Operating systems

The only host requirement is a JDK 25 for that host. The compiler, the CLI, the language
server and the runtime are pure Java 25 with no native code, no third-party dependency and
no shell out of the compiler itself, so there is nothing to port per platform. Every
path-shaped and process-shaped decision is read from the running JVM rather than assumed:

| Decision | Source | Linux · macOS · FreeBSD · OpenBSD | Windows 11 |
| --- | --- | --- | --- |
| `--classpath` and `--out` spelling | `java.io.File.pathSeparator`, `java.nio.file.Path` | `:`, `/` | `;`, `\` |
| Installed launcher | `installDist` writes both | `bin/vsharp` (`#!/bin/sh`) | `bin\vsharp.bat` |
| Launcher used by `compileVSharp` | `os.name` | `bin/vsharp` | `bin\vsharp.bat` |
| `java` binary in the workflow gate | `os.name` | `bin/java` | `bin\java.exe` |
| `GRAAL_HOME` launcher | file name search | `bin/native-image` | `bin\native-image.cmd` |
| Native image file name | observed after the build | `Program` | `Program.exe` |
| Native image linking | musl loader search | `--static --libc=musl` on musl, nothing on glibc | not applicable |
| Executable bit on written files | `supportedFileAttributeViews` | POSIX permissions | not applicable |

### Installing

A JDK 25 is the only prerequisite — **Gradle itself is not**, because the repository carries
the wrapper:

```bash
# Linux (glibc or musl), FreeBSD, OpenBSD
./gradlew installTools
```

```text
:: Windows 11 (cmd.exe or PowerShell)
gradlew.bat installTools
```

`installTools` installs both the compiler and the language server and then prints the `PATH`
line for the shell it is running under — `export PATH=...` on POSIX, `set PATH=...` on
Windows — followed by the launcher name to try. Nothing about it is host-specific except the
sentence it prints. `./gradlew :vsharp-cli:distZip` (or `distTar`) produces the same layout
as a relocatable archive, which is the practical install on a machine that does not build the
compiler: unpack it and put its `bin` directory on `PATH`.

The launchers are Gradle's own POSIX `sh` and Windows `cmd` start scripts. On macOS, FreeBSD and
OpenBSD the `sh` script is the portable one and needs no `bash`.

### The JDK requirement, and what happens without it

Every module except one is class file version 69, so a JVM older than 25 cannot load the
compiler at all. The exception is `vsharp-boot`, compiled for Java 11: the installed
launchers start it from the class path, it checks the running JVM, and it either hands over
to the driver or explains. An old or unexpected JDK therefore produces this instead of
`UnsupportedClassVersionError`:

```text
vsharp: V# requires Java 25 or later; this launcher is running Java 21
  the JVM in use is /opt/jdk21
  set JAVA_HOME to a Java 25 installation, or put its bin directory first on PATH
  install one with: apt install openjdk-25-jdk, dnf install java-25-openjdk, apk add openjdk25, or unpack any JDK 25 build
```

The install hint is chosen from `os.name`: `winget install Microsoft.OpenJDK.25` on Windows,
`pkg install openjdk25` on FreeBSD, `pkg_add jdk` on OpenBSD, `brew install openjdk@25` on
macOS, distribution packages on Linux.
Exit code is 2, the same code every other misinvocation uses.

Two host facts worth stating plainly rather than discovering:

- **OpenBSD** ports have historically shipped LTS JDKs up to 21. If `pkg_add jdk` gives you
  21, V# will refuse to start with the message above; a JDK 25 build for the host is required
  and nothing in this repository can substitute for it.
- **Native executables** (`GRAAL_HOME`) exist only where GraalVM does — Linux and Windows.
  FreeBSD and OpenBSD run V# on the JVM and simply never enter that stage, which is a no-op
  and not an error. On musl the image is linked `--static --libc=musl`, because a dynamically
  linked image does not start there.

### What is verified, and where

The platform-dependent decisions above are covered by the `nativeimage` and `boot` suites on
an ordinary Linux machine, because each is decided by something observable rather than by the
host it describes: the Windows-shaped `native-image.cmd` installation and the `.exe` image
name are found by file name, the musl decision is a loader search against an injected root,
and the preflight is a pure function over a version string. The refusal path was additionally
executed for real against a JDK 21 installed beside the JDK 25.

The test suite itself is host-neutral: its fake `native-image` launcher is a Java class, and
the only per-platform part is the two-line script that starts it — `#!/bin/sh` or a `.cmd`.

What is *not* claimed: no suite run has happened on FreeBSD, OpenBSD, Windows or Alpine. Four
decisions come down to a single `os.name` branch each — the start script name, the `java`
binary name, the fake launcher's script form, and the install hint — and those are exercised
here only with the Linux value.

## JDK visibility

A V# program may name any type of the JDK it is compiled against. The compiler resolves those
names against its *own* running image, and because the compiler is a named module its module
graph would otherwise resolve `java.base` and little else — leaving `java.net.http`, `java.sql`
and `java.xml` unreachable. The launcher therefore starts the compiler with
`--add-modules ALL-DEFAULT`, which is exactly the module set an ordinary classpath Java program
sees; incubator modules stay out, as they do for `javac`. This is baked into the generated start
scripts, so nothing is required of a user of the CLI or of the Gradle integration.

An embedder calling `Compilation` directly from its own JVM must pass the same flag. If it does
not, a `using` naming a package that exists in the image but was not resolved reports `VS20010`,
which names the module to add, instead of the misleading "type or namespace not found".

Source layout is part of the V# language contract. Every syntax opening brace uses Allman
placement (an otherwise empty new line), and opening and closing braces use exactly four
literal spaces per enclosing brace pair. A closing brace begins its line. Violations report
`VS20007`-`VS20009` and stop before declaration binding; tabs never substitute for structural
spaces. The rule covers every block brace, while braces inside inactive preprocessor branches,
comments and literals are not syntax and are ignored.

Array initializers, property subpatterns and `with` initializers write a brace inside an
expression or a pattern. Such a pair may be written on a single line - `new int[] { 1, 2 }`,
`Order { Quantity: > 100 }`, `o with { Quantity = 5 }` - or fully in Allman form; spread over
several lines without Allman placement it reports the same diagnostics as any other block.

## Output modes

Directory output is the default:

```bash
vsharp Program.vs
vsharp --out build/classes-vs Program.vs
vsharp --out=build/classes-vs Program.vs
```

Generated `.class` files are written under the output directory using the emitted JVM
binary name. A `Program` holder inside `namespace Demo` therefore produces
`Demo/Program.class`.

JAR output is explicit and mutually exclusive with `--out`:

```bash
vsharp --jar build/program.jar Program.vs
vsharp --jar=build/program.jar Program.vs
```

The JAR contains only V#-generated program classes. Entries are sorted and timestamped
deterministically so identical inputs produce byte-identical JAR files. The compiler does
not currently add a manifest, choose a main class, or bundle `vsharp.runtime`.

## Running generated artifacts

Generated code is standard JVM bytecode. Programs that use runtime helpers such as
`System.Console`, tuples, ranges, slices, or C#-faithful formatting must run with
`vsharp.runtime` available.

A file of top-level statements becomes `public static void main(String[])` on a holder named
after the file, so the emitted class is directly launchable:

```bash
vsharp --out build/classes-vs Hello.vs
java -cp build/classes-vs:vsharp-runtime/build/libs/vsharp-runtime-0.0.93-A.jar Hello
```

When running loose classes from a Gradle build, include both the generated output directory
and the runtime module/JAR on the class path or module path used by the host application.
The CLI's `--jar` output is likewise a program-class JAR, not a standalone executable or
fat JAR.

## Gradle integration

`gradle/vsharp.gradle.kts` is a dependency-free script plugin that compiles `.vs` sources as
part of a Gradle build. It uses no `gradleApi()`, no `java-gradle-plugin` and no published
plugin, so applying it adds nothing to resolve:

```kotlin
extra["vsharpSourceDir"] = "src/main/vsharp"           // default
extra["vsharpOutputDir"] = "build/classes/vsharp/main" // default
extra["vsharpDefines"] = "DEBUG,TRACE"                 // optional
apply(from = "gradle/vsharp.gradle.kts")
```

It registers `compileVSharp` and makes `assemble` depend on it, so `gradle build` compiles V#
sources alongside everything else. Sources are passed in sorted order so diagnostics do not
depend on filesystem order, and the task declares its inputs and outputs, so it is
incremental and cacheable.

Configuration goes through `extra` properties rather than a typed extension because a type
declared inside a script plugin is not visible to the build script that applies it.

The task sets `JAVA_HOME` for the launcher from `org.gradle.java.home`, falling back to the
Gradle JVM. The compiler is a JDK 25 module, and Gradle's launcher JVM is often older — on
a development machine it is 21 — which would otherwise fail with
`Unsupported major.minor version 69.0` before the compiler starts.

This build applies the plugin to itself: `samples/hello` is compiled by `compileVSharp` and
run by `verifyGradleWorkflow`, which asserts the program's actual output and is wired into
`check`. `gradle build` therefore proves the whole path — compiler, CLI, emitted artifact and
runtime — rather than asserting it in prose.

## Unbounded task joins

`await` is the only supported way to join a task. Joining with the JDK's `Get()`, `Join()` or
`GetNow()` is an error (`VS20018`), because those wait outside the 60-second execution limit.
`--allow-unbounded-joins` downgrades each occurrence to a warning; it never silences them.

## Native executables

A native binary is produced when, and only when, the environment names a usable GraalVM:

```bash
export GRAAL_HOME=/path/to/graalvm
vsharp --out build/classes-vs Hello.vs
build/classes-vs/Hello
```

`--jar` works the same way: the JAR is the image classpath and the executable lands beside it,
named after the entry point rather than after the JAR, because a V# JAR carries no
`Main-Class` attribute and the entry point is always named explicitly.

`GRAAL_HOME` is the entire interface. There is no flag, no configuration file and no task to
invoke: exporting the variable is the green light, and unsetting it restores the previous
behaviour exactly. The stage runs after compilation has already succeeded and written its
`.class` files, so it can only add an artifact — a program that compiled before compiles
identically now, and every existing exit code keeps its meaning.

What the compiler does with the variable:

- **Unset, empty, or naming a directory that is not a GraalVM installation with
  `lib/svm` and an executable `bin/native-image`** — the first two are silent and change
  nothing. A named but unusable home is reported on standard error, because an exported
  variable is an instruction rather than a preference, and it still leaves the compilation
  successful. A leading `~` is expanded, since a home exported in quotes reaches the process
  with the tilde intact.
- **Usable** — the emitted classes are scanned for entry points, which are read from the
  bytecode rather than the syntax tree, so a `main` reaches the image whether it came from a
  declared method or from a file of top-level statements. Exactly one entry point starts a
  build; none is announced and skipped; several are reported, because the compiler will not
  guess which program a user meant. A test source set that compiles one entry point per suite
  therefore explains itself and builds nothing, which is correct rather than a failure.

The image is built from the emitted output plus `vsharp.runtime`, in the same search order the
JVM launch documented above uses, and lands beside the classes it was built from, named after
the entry point's simple name. Any previous executable at that path is removed before the
launcher starts, so a failed rebuild can never leave a stale binary that still runs. Images are
built with `--no-fallback`: a fallback image is a launcher that still needs an installed JVM,
which defeats the only reason to build one.

**A failed native build never fails the compilation.** It is reported on standard error, the
launcher's own output has already been streamed, and the exit code stays exactly what the
compilation earned. This is deliberate and was settled by measurement: a Spring Boot
application compiles cleanly to classes but cannot be imaged, because native image generation
needs AOT metadata that V# does not emit. Exporting a variable must not turn a green
production build red, so the executable is an artifact that may be missing — never a gate.

Standard error carries misconfiguration (an unusable home, a failed build); standard output
carries the compiler explaining what it did (no entry point, several entry points, progress,
and the finished image).

The Gradle integration follows the same rule. `compileVSharp` inherits the environment and
declares `GRAAL_HOME` as an input, so exporting it re-runs a compilation that was otherwise up
to date — without that, a toggled variable would silently produce no executable.

## Current limits

These limits are intentional current build facts, not hidden behavior:

- Built-in `string` exposes `Length`, integer indexing, both `Substring` overloads, the
  parameterless trim family, invariant `ToUpperInvariant`/`ToLowerInvariant`, the ordinal
  search/replace family, static null/whitespace checks, and string-array `Join`/`Concat`.
  Invariant string casing is simple and context-free and matches .NET 10 over every Unicode
  scalar; current-culture `ToUpper`/`ToLower` stay unavailable. Built-in `char` exposes the culture-independent
  `IsDigit`, `IsLetter`, `IsLetterOrDigit` and `IsWhiteSpace` predicates plus
  `ToUpperInvariant`/`ToLowerInvariant`; the invariant casing pair is full-BMP oracle matched
  with explicit handling for U+0130/U+0131. Other members remain absent rather than inheriting Java methods
  whose culture or edge-case semantics differ; in particular, default string and character
  casing remain unavailable because C# uses the current culture.
- Built-in `int` exposes invariant `Parse(string)` and `TryParse(string, out int)`. The latter
  closes the normal `Console.ReadLine()` input loop through the compiler's existing out-cell
  ABI. Unlike .NET's current-culture one-string calls, V# fixes signs to the invariant forms
  because it has no culture model. Culture/provider/style, Span and UTF-8 overloads remain
  unavailable.
- Built-in `double` exposes the corresponding invariant `Parse(string)` and
  `TryParse(string, out double)` pair. Decimal/exponent/group syntax, `NaN`/`Infinity`, signed
  overflow/underflow and raw IEEE-754 results follow the measured .NET 10 behavior; broader
  overloads and the other numeric parsing types remain unavailable.
- Built-in `bool` exposes culture-independent `Parse(string)` and
  `TryParse(string, out bool)`: ASCII-case `True`/`False`, surrounded by C# whitespace or
  NUL, with false assigned on failure. This closes the deliberately bounded parsing set.
- `System.Convert` exposes the probe-selected radix pair `ToString(int, base)` and
  `ToInt32(string, base)`. Bases 2/8/10/16, non-decimal UInt32 bit patterns, strict ASCII
  grammar, null-to-zero, and argument/format/overflow outcomes match the .NET 10 corpus;
  the wider Convert facade remains outside the curated surface.
- `System.Math` includes the workload-selected `Sin(double)`, `Cos(double)` and
  `Tan(double)` trio. A 32,794-record .NET 10/JDK 25 comparison proves identical result
  categories and IEEE edge bits; all finite differences are at most one ULP. Inverse trig,
  `Atan2`, `Exp`, `Log10` and two-argument `Log` remain outside this bounded increment.
- Null-conditional `?.` / `?[]` evaluates its receiver once and short-circuits the whole
  chain. Nullable value receivers are supported: `int? x; x?.ToString()` resolves the
  member against `int`, skips it for the no-value state, and lifts the result as required.
- Generic methods and local functions execute when each inferred type parameter is used
  directly as a parameter or result, or as an argument of a constructed generic such as
  `List<T>`, or as an array of either; the erased declaration uses `Object` where needed and
  the call site boxes/unboxes/checkcasts as needed. A `T[]` parameter or result accepts any
  reference array, including a `params T[]` in either its expanded or its normal form; a
  primitive array reports `VS20019` and creating `new T[n]` reports `VS20020`, because neither
  survives erasure. A generic `ref`/`out` parameter is carried by a cell over the erasure, so
  it works for reference and value type arguments alike. A tuple is a carrier as well, because
  its descriptor never mentions its elements. Type parameters nested in any remaining carrier -
  `T?` - report `VS20002` before bytecode generation.
- Managed `sizeof` uses C#'s predefined safe-type sizes. `typeof` returns an interned V#
  source-type token rather than a JVM `Class`, so aliases compare equal while signed and
  unsigned types sharing a JVM carrier remain distinct.
- `yield return` / `yield break` are outside the non-object-oriented subset and report
  `VS20001`: C# iterator contracts require interface implementation and virtual dispatch.
- Java interop discovery exposes public fields and methods declared by resolvable classes or
  inherited through their superclass/interface closure, plus public constructors. Class and
  method `Signature` attributes preserve exact invariant type arguments and type-variable
  substitution, so `ArrayList<string>`, `List.Of(...)`, `Optional<string>` and typed Stream
  chains retain their source types while calls still use erased JVM descriptors. A
  wildcard-bearing method falls back wholly to its erased descriptor, as does a method whose
  unprojected bound changes a direct erased carrier. Generic public fields, constructor
  signatures, bounds and annotations remain unprojected. Raw Java type use stays available for
  compatibility. Direct primitive arguments/results are boxed/unboxed through the erased
  `Object` position. Generic expanded varargs infer from their loose arguments, so
  `Arrays.AsList(1, 2)` is `List<int>` and boxes per element into the erased array, while an
  actual array argument still binds in normal form.
- `--jar` output is a program-class JAR: no manifest, no main class, no bundled
  `vsharp.runtime`.
- The complete list of constructs the backend refuses is in
  [`BYTECODE.md`](BYTECODE.md#forms-that-refuse-to-emit).
