<p align="center">
  <img src="docs/assets/use-vsharp.png" alt="V#" width="560">
</p>

<h3 align="center">C# syntax. JVM bytecode. No object model. No dependencies.</h3>

<p align="center">
  <code>0.0.93-A</code> · language surface <code>4</code> · Java 25 · <b>zero</b> third-party libraries
</p>

---

**V# is the non-object-oriented subset of C# 13, compiled to standard JVM class files.**

You write C# — the literals, the patterns, the interpolation, the tuples, `async`/`await` — and you
get ordinary bytecode that any Java program can call, any JVM can run, and `javap` can read. There
is no runtime bridge, no shim layer, and no reimplementation of the JDK: `java.util.ArrayList` *is*
the list you use.

The compiler itself is written in pure Java 25 against the JDK only. No ASM, no ANTLR, no Jackson,
no JUnit. Bytecode is emitted with `java.lang.classfile`, the parser is hand-written, and the test
harness is in this repository. The dependency list is empty, and it is meant to stay that way.

```
V# source → lexer → parser → AST → semantic analysis → typed IR → lowering → JVM bytecode
```

---

## Sixty seconds

**Requirements:** a JDK 25 and Gradle 9.x. That is the whole list.

```bash
gradle installTools          # builds the compiler and prints the PATH line for your shell
export PATH="$PWD/vsharp-cli/build/install/vsharp/bin:$PATH"
vsharp --version             # V# 0.0.93-A (surface 4)
```

Write `Hello.vs`:

```csharp
using System;

static int Fib(int n)
{
    return n < 2 ? n : Fib(n - 1) + Fib(n - 2);
}

Console.WriteLine($"fib(10) = {Fib(10)}");
```

Compile and run it:

```bash
vsharp --out out Hello.vs
java -cp out:vsharp-runtime/build/libs/vsharp-runtime-0.0.93-A.jar Hello
# fib(10) = 55
```

Top-level statements become a `public static void main(String[])` on a class named after the file.
The output is a normal `.class` — nothing about running it is V#-specific.

---

## The tour

Every snippet below was compiled and executed by this compiler. The comment is the real output.

### Strings that format like C#

```csharp
using System;

static (string Name, int Score) Best()
{
    return ("ada", 96);
}

var (name, score) = Best();
Console.WriteLine($"{name,-8}|{score:D4}|{score:X}|{score / 7.0:F2}");
// ada     |0096|60|13.71
```

Alignment, `D`/`X`/`F` specifiers, tuples with element names, and deconstruction — all of it C#
semantics, not `String.format` with a different face. Numeric rendering is culture-free and
verified digit-for-digit against .NET 10.

### Patterns everywhere

```csharp
static string Describe(object value)
{
    return value switch
    {
        int n when n < 0        => "negative",
        int n                   => $"int {n}",
        string { Length: > 3 } s => $"long string {s}",
        string s                => $"short {s}",
        null                    => "nothing",
        _                       => "something else",
    };
}
// int 42|negative|short hi|long string hello|nothing
```

Type patterns, property patterns, `when` guards, `case null`, and exhaustive switch expressions.

### Values that behave like values

```csharp
record struct Point(int X, int Y);

Point a = new Point(2, 3);
Point b = a with { Y = 9 };
string near = a is Point { X: > 1, Y: < 5 } ? "near" : "far";
Console.WriteLine($"{a}|{b}|{near}|{a == new Point(2, 3)}");
// Point { X = 2, Y = 3 }|Point { X = 2, Y = 9 }|near|True
```

`record struct` gives you the constructor, `ToString`, structural equality and `with` — no class,
no inheritance, no virtual dispatch.

### Slices, ranges and list patterns

```csharp
int[] numbers = { 3, 1, 4, 1, 5, 9, 2, 6 };
int[] middle = numbers[2..5];

string shape = numbers switch
{
    [var first, .., var last] => $"{first}..{last}",
    [] => "empty",
};

Console.WriteLine($"{shape}|{numbers[^1]}|{middle.Length}|{middle[0]}");
// 3..6|6|3|4
```

### Generics and extension methods

```csharp
static class Text
{
    public static bool IsAnyOf(this string value, params string[] options)
    {
        foreach (string option in options)
        {
            if (value == option)
            {
                return true;
            }
        }
        return false;
    }

    public static T Second<T>(this T[] items)
    {
        return items[1];
    }
}

string[] words = { "compile", "run", "verify" };
Console.WriteLine($"{"run".IsAnyOf("walk", "run")}|{words.Second()}|{Text.IsAnyOf("no", "yes")}");
// True|run|False
```

Extension methods are a compile-time rewrite to a static call, exactly as C# defines them — the
receiver form and the static form are the same method. Type parameters work through arrays,
`params`, `ref`/`out`, tuples and nullables, and `where` constraints are enforced.

### async / await

```csharp
using System;

static class Work
{
    public static async Task<int> Fetch(int id)
    {
        return id * 2;
    }

    public static async Task<string> Run()
    {
        int first = await Fetch(21);
        int second = await Fetch(50);
        return $"{first}|{second}";
    }
}

Console.WriteLine(await Work.Run());
// 42|100
```

`Task<T>` is `java.util.concurrent.Future<T>` — not a lookalike. Work runs on a virtual thread and
`await` is the only supported join, with a bounded wait; the JDK's own blocking `Get()`/`Join()`
are compile errors, because they wait outside that bound.

### Exceptions

```csharp
static string Parse(string text)
{
    try
    {
        checked
        {
            int value = int.Parse(text);
            return $"ok {value * 2}";
        }
    }
    catch (OverflowException)
    {
        return "overflow";
    }
    catch (FormatException)
    {
        return "not a number";
    }
    finally
    {
        Console.Write("[done]");
    }
}

Console.WriteLine($"{Parse("21")}|{Parse("x")}");
// [done][done]ok 42|not a number
```

`checked` arithmetic, C#'s exception types, and `try`/`catch`/`finally` over real JVM exception
tables. JDK exceptions can be caught and thrown directly.

---

## Direct JDK integration

This is the part that makes V# a JVM language rather than a language that targets the JVM.

**Use the JDK from V#** — import a package, call it in V# spelling:

```csharp
using System;
using java.lang;
using java.util;
using java.time;

List<string> names = new ArrayList<string>();
names.Add("grace");
names.Add("ada");
Collections.Sort(names);

Map<string, int> lengths = new HashMap<string, int>();
foreach (string name in names)
{
    lengths.Put(name, name.Length);
}

Duration window = Duration.OfMinutes(90);
Console.WriteLine($"{names.Get(0)}|{lengths.Get("grace")}|{window.ToHours()}h|{Integer.MAX_VALUE}");
// ada|5|1h|2147483647
```

Generics cross the boundary, JDK types are resolved against the JDK the compiler runs on, and the
whole default module image is nameable — `java.sql`, `java.net.http`, `java.util.logging` — with no
configuration.

**Use V# from Java** — it is just bytecode:

```csharp
namespace Demo;

public static class Greet
{
    public static string Hello(string name)
    {
        return $"hello, {name}";
    }
}
```

```java
import Demo.Greet;

public final class FromJava {
    public static void main(String[] args) {
        System.out.println(Greet.Hello("java"));   // hello, java
    }
}
```

```console
$ javap -cp out Demo.Greet
public class Demo.Greet {
  public Demo.Greet();
  public static java.lang.String Hello(java.lang.String);
}
```

---

## Three rules that will surprise you

V# is stricter than C# in exactly three places, and each one is a compiler error rather than a
convention. All three exist so that one program has one spelling.

**1. Allman braces are grammar.** Every block brace stands alone on its line, indented four spaces
per enclosing brace pair.

```csharp
static int Add(int a, int b) {      // error VS20007: Opening brace must appear on its
    return a + b;                   //                own new line (Allman style)
}
```

Array initializers, property subpatterns and `with` initializers may be written fully inline
(`{ 1, 2, 3 }`) or fully Allman — mixing the two reports `VS20008` as well. Tabs are not
indentation.

**2. JVM members are written in PascalCase.** The compiler owns the translation, and it touches the
first character only.

```csharp
file.toURI();   // error VS20006: Java member 'toURI' must be written as 'ToURI'
file.ToURI();   // correct
```

`Integer.MAX_VALUE` keeps its own spelling — a name with no lowercase letter is not PascalCase, so
it is matched verbatim. `Integer.MaxValue` is an error.

**3. Types are imported, never qualified.**

```csharp
java.util.ArrayList<int> x = new java.util.ArrayList<int>();
// error VS20012: Name 'ArrayList' by its simple name and import it with 'using java.util;'
```

A qualified name belongs in a `using` directive, an alias target, or a `using static` target —
nowhere else. Nested names like `Map.Entry<string, int>` are not qualified names and stay legal.

---

## What is deliberately not here

V# is the subset of C# that does **not** need the object model. These are excluded by design, each
for a stated semantic reason, and none of them is a "not yet":

| Excluded | Why |
| --- | --- |
| Classes with instance state, inheritance, `virtual`/`override`, interfaces you declare | The feature *is* the object model |
| User-declared `delegate` types, multicast, events | A delegate is a reference type with instance state and an invocation list |
| LINQ query expressions | Built on delegates and extension-method chains over an object model |
| Iterators (`yield return`) | Compiles to a generated state-machine class |
| Anonymous types, object initializers on classes | No user classes to initialize |
| `unsafe`, pointers, `stackalloc`, `fixed`, `ref struct`, `Span<T>` | No unmanaged memory model on the JVM |

What you get instead: values, enums, `struct`, `record struct`, tuples, static containers, free
functions, lambdas, and the whole JDK. `Func` and `Action` *are* available — they denote JDK
functional interfaces (`Supplier`/`Function`/`BiFunction`, `Runnable`/`Consumer`/`BiConsumer`) by
arity, so `Func<int, int> twice = x => x * 2;` compiles and `twice(21)` lowers to the interface's
single abstract method.

The full classification — every C# 13 construct, its status, its JVM lowering and its tests — is in
[`docs/FEATURE-MATRIX.md`](docs/FEATURE-MATRIX.md).

---

## The toolchain

| Tool | What it is |
| --- | --- |
| `vsharp` | The compiler CLI: `.vs` → `.class` or a deterministic JAR, with stable exit codes and C#-exact diagnostics |
| `vsharp-lsp` | A language server: diagnostics, completion, signature help, hover, go-to-definition, symbols |
| `vsharp-vscode` | VS Code / VSCodium extension, packaged as a `.vsix` with the server bundled |
| `gradle/vsharp.gradle.kts` | A dependency-free Gradle script plugin: `apply(from = ...)` and `gradle build` compiles your `.vs` sources |
| Native images | Export `GRAAL_HOME` and a single-entry-point compilation also produces a native executable |

```bash
vsharp --out classes  src/*.vs          # directory output
vsharp --jar app.jar  src/*.vs          # one deterministic JAR
vsharp --classpath libs/dep.jar src/*.vs
vsharp --diagnostics compact src/*.vs   # one line per diagnostic, for CI
```

Two compilations of the same sources produce byte-identical artifacts.

---

## Platforms

The compiler, the CLI, the language server and the runtime are pure Java 25. There is no native
code, no JNI, no shell-out and nothing to port: **anywhere a JDK 25 runs, V# runs**, on any
architecture that JDK supports. Every path- and process-shaped decision is read from the running
JVM rather than assumed.

| Host | Compiler · CLI · LSP | Gradle plugin | Native image (`GRAAL_HOME`) | Get a JDK 25 |
| --- | :--: | :--: | --- | --- |
| **Linux** (glibc) | ✅ | ✅ | ✅ | `apt install openjdk-25-jdk` · `dnf install java-25-openjdk` |
| **Linux** (musl / Alpine) | ✅ | ✅ | ✅ linked `--static --libc=musl` | `apk add openjdk25` |
| **Windows 11** | ✅ | ✅ | ✅ produces `Program.exe` | `winget install Microsoft.OpenJDK.25` |
| **macOS** | ✅ | ✅ | ✅ | `brew install openjdk@25` |
| **FreeBSD** | ✅ | ✅ | ⚪ no GraalVM for the host — the stage is skipped, not failed | `pkg install openjdk25` |
| **OpenBSD** | ⚠️ needs a JDK 25 build for the host | ⚠️ same | ⚪ no GraalVM for the host | `pkg_add jdk` — ports have shipped up to 21 |

✅ supported ⚪ not available on the host, and never an error ⚠️ blocked only by JDK availability

**What actually differs per host**, and where each answer comes from:

| Decision | Source | POSIX hosts | Windows |
| --- | --- | --- | --- |
| Classpath separator, path spelling | `File.pathSeparator`, `Path` | `:` and `/` | `;` and `\` |
| Installed launcher | `installDist` writes both | `bin/vsharp` (`#!/bin/sh`) | `bin\vsharp.bat` |
| `java` binary | `os.name` | `bin/java` | `bin\java.exe` |
| GraalVM launcher | file-name search | `bin/native-image` | `bin\native-image.cmd` |
| Native image file name | observed after the build | `Program` | `Program.exe` |
| Native image linking | musl loader search | `--static --libc=musl` on musl | n/a |
| Executable bit | `supportedFileAttributeViews` | POSIX permissions | n/a |

The POSIX launcher is Gradle's own `#!/bin/sh` start script — it needs no `bash`, which is what
makes FreeBSD and OpenBSD ordinary rather than special.

**Honest status:** the suite is executed end-to-end on Linux. The other hosts are supported by
construction rather than by assumption — each decision above is either computed from the running
JVM or observed from the filesystem, and each is covered by the test suite, including the
Windows-shaped `native-image.cmd` installation, the `.exe` image name and the musl loader search.
Four single-line `os.name` branches and one `%*` in a batch file are the only code a first run on
another host would exercise for the first time.

**If your JVM is too old**, you get an instruction rather than a stack trace. A JVM older than 25
cannot load the compiler at all, so the launchers start a Java 11 preflight that names the version
it found, the JVM it came from — usually a `JAVA_HOME` pointing somewhere unexpected — and the
install command for *your* host, then exits 2:

```console
$ vsharp --version
vsharp: V# requires Java 25 or later; this launcher is running Java 21
  the JVM in use is /opt/jdk21
  set JAVA_HOME to a Java 25 installation, or put its bin directory first on PATH
  install one with: apt install openjdk-25-jdk, dnf install java-25-openjdk, apk add openjdk25, or unpack any JDK 25 build
```

---

## Repository layout

```
vsharp-boot/        launch preflight (the only module built for Java 11)
vsharp-runtime/     runtime support emitted code links against: formatting, ranges, tuples, console
vsharp-compiler/    source model, diagnostics, lexer, parser, binder, typed IR, lowering, backend
vsharp-cli/         the vsharp command
vsharp-lsp/         the language server
vsharp-vscode/      editor extension and its generated TextMate grammar
vsharp-testkit/     dependency-free test harness (no JUnit)
vsharp-tests/       the executable suite: 865 cases
samples/hello/      the sample the build itself compiles and runs as an acceptance gate
docs/               language, bytecode, CLI, editor and grammar documentation
```

```bash
gradle build                      # compiles everything, runs the suite and the sample gate
gradle :vsharp-tests:runTests     # the suite alone
gradle :vsharp-tests:runTests --args="lexer"   # one area
```

`gradle build` is the acceptance gate: it compiles the compiler, runs all 865 cases, then compiles
`samples/hello` with the produced CLI, runs the emitted class on a real JVM, and asserts its output.

---

## Documentation

| Document | Contents |
| --- | --- |
| [`docs/FEATURE-MATRIX.md`](docs/FEATURE-MATRIX.md) | Every C# 13 construct: status, semantics, JVM lowering, tests, and the reason for each exclusion |
| [`docs/GRAMMAR.md`](docs/GRAMMAR.md) | The grammar, precedence, associativity and error-recovery rules |
| [`docs/BYTECODE.md`](docs/BYTECODE.md) | Type mappings, descriptors, and the emitted shape of every construct |
| [`docs/CLI.md`](docs/CLI.md) | Options, exit codes, output modes, JDK visibility, platforms |
| [`docs/EDITORS.md`](docs/EDITORS.md) | The language server and the extension |
| [`docs/ASYNC.md`](docs/ASYNC.md) | How `async`/`await` maps onto virtual threads and `Future` |

---

## License

BSD 3-Clause. See [`LICENSE`](LICENSE).
