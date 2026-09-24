# V# editor support — language server and VS Code / VSCodium extension

Two deliverables, one source of truth. The **language server** (`vsharp-lsp`) runs the same
front end as the `vsharp` CLI, so the editor and the command line can never disagree about a
diagnostic. The **extension** (`vsharp-vscode`) adds syntax highlighting and wires the server
into VS Code and VSCodium.

Both obey the project's absolute dependency rule. The server is pure Java 25 on JDK APIs only —
the JSON-RPC codec and the LSP framing are written in this repository rather than taken from a
library. The extension is plain JavaScript against the VS Code API with **no npm dependencies**:
there is no `vscode-languageclient`, no bundler and no `vsce`. The `.vsix` is produced by a
Gradle `Zip` task, because a `.vsix` is an OPC zip and nothing more.

## 1. What the server implements

| Capability | Method | Notes |
| --- | --- | --- |
| Diagnostics | `textDocument/publishDiagnostics` | Push-based. Every compiler diagnostic, with its `VS….` code and C#-exact message. |
| Completion | `textDocument/completion` | Keywords, project symbols, and members of the value or type before a `.`. |
| Signature help | `textDocument/signatureHelp` | Overloads of the call being written, owner-qualified, with passing modifiers, real defaults and the active parameter highlighted. An extension method called through a receiver shows its *reduced* signature - the `this` parameter is already supplied - and shows it in full when called as an ordinary static. Triggers on `(` and `,`. |
| Hover | `textDocument/hover` | The declaration rendered as C# source, e.g. `const int App.Program.Limit = 3`. Parameter modifiers (`this`, `ref`, `out`, `in`, `params`) and enforced `where` constraints are shown, because a declaration missing them is not the one the author wrote. |
| Go to definition | `textDocument/definition` | Project files, opened or not; jar and JDK types open a generated signature view. |
| Find references | `textDocument/references` | Whole-word lexical search across the project's sources. |
| Document symbols | `textDocument/documentSymbol` | Hierarchical outline, nested by qualified name. |
| Lifecycle | `initialize`, `initialized`, `shutdown`, `exit` | Full-document sync only (`TextDocumentSyncKind.Full`). |

Completion never hand-writes a keyword list. Reserved words are enumerated from `SyntaxKind`
and contextual words from `SyntaxFacts.contextualKeywords()` — the same tables the lexer
consults — so a word added to the language is offered by construction. The current partition is
**77 reserved + 44 contextual = 121 keywords**, and the grammar generator fails the build if any
of them is left uncoloured or coloured twice.

Everything else in the list comes from the `SemanticModel` of the last analysis, which includes
the compiler-shipped corelib. That is why `Console.` offers `WriteLine`, `Write` and `ReadLine`
rather than a curated guess.

**After a dot the answer is members or nothing.** The unqualified list — namespaces, types,
keywords — is never a legal continuation of `x.`, so it is never offered there. Returning it when
the receiver could not be read produced 222 irrelevant suggestions for `bytes[0].`; an empty list
says "this receiver is not understood", which is true.

**Every item is offered in the spelling V# requires.** A member discovered on the module path is
converted to its PascalCase form (`size` becomes `Size`), and a Java member is never offered
*unqualified* at all, because V# has no unqualified `toString` — those are reached through a
receiver. Before that filter, 907 of 1851 items in one real file were names the compiler would
have rejected. Names in your own source keep the case you wrote: a camelCase local, parameter or
private field is ordinary C# style and is offered exactly as declared.

### Analysis model

The server compiles **the whole project**, not just the open buffers. A V# program's types live
across files, so analysing one open file alone reports every sibling type as missing: opening
`PetController.vs` by itself produced 19 spurious `VS0246` errors naming `Pet`, `Outcome` and
`Species`, on a file the CLI builds cleanly.

Sources are discovered from the project's **`src` tree**, found by walking up from the open file
to the nearest directory holding `vsharp.classpath`, `.git` or a Gradle build script. Open buffers
override what is on disk, so unsaved edits are what gets analysed. The scan deliberately stops at
`src` rather than the project root: one real project keeps 72 standalone probe files outside `src`, each a
conflicting one-file experiment, and compiling those together with the application broke binding —
silently, because their diagnostics belonged to no open buffer and were dropped, leaving no errors
*and* no symbols. A file outside `src` is analysed alone, together with whatever else is open.

Only the front end runs (`Compilation.analyze`, never `emit`), so no bytecode is generated for a
buffer that changes on the next keystroke.

### Why answers survive a syntax error

`Compilation` gates its phases globally: one error anywhere returns at `Phase.PARSE`, and a parsed
unit carries no semantic model. An editor asks for completion at exactly the moment the buffer does
not parse — typing `obj.` *creates* the syntax error that destroys the model which would have said
what `obj` is. Binding semantic answers to the current run therefore means answering nothing
precisely when it matters, which is what a real session showed: every request made while a file
carried one diagnostic returned no symbols and a completion list of exactly 121 items, the keyword
table alone.

Semantic features therefore read the **last analysis that bound**. Diagnostics still come from the
current run, because those must describe the text on screen. A file that has never parsed since the
server started has no such state, and completion there degrades to keywords.

Analysis runs on a virtual thread per edit, and a burst is coalesced: opening a project sends one
`didOpen` per file, and analysing the whole open set once per notification is quadratic — 35 files
meant 35 full compilations. A task that is already superseded does no work at all. Results are
stored in revision order, so two overlapping runs finishing out of order cannot pin the editor to
the older one.

A request that needs semantic state waits for the analysis of the edit it follows. The deadline is
two-tier: **10 seconds** when no analysis has ever landed, because degrading there means answering
from nothing, and a real project takes several seconds to analyse cold against its jars; **2
seconds** once a usable result exists, because degrading to the previous analysis is mild — the
symbols are real, just one edit old. Both were once far longer, which made an interactive request
appear to hang; three seconds cold was then too short and returned an empty outline on first open.
The client also honours cancellation, so abandoning a completion tells the server to stop.

JDK types need no configuration: the server resolves them against the JDK it runs on, exactly as
the CLI does, so `using java.util;` and `new ArrayList()` resolve in the editor because
they resolve in the compiler.

### Project dependencies — `vsharp.classpath`

A real project's *dependencies* do need configuring, and getting this wrong is not a missing
feature but an actively harmful one: with an empty classpath the server reported **330 spurious
`VS0246` errors** across a real application, on files the CLI builds cleanly. An editor that
invents errors is worse than one with no diagnostics at all.

The server reads a **`vsharp.classpath`** file from the nearest enclosing directory of each open
source. One entry per line, or several joined by the platform path separator; blank lines and
`#` comments ignored; relative entries resolve against the file's own directory; entries that no
longer exist are skipped with a debug note rather than failing the analysis.

It is a file rather than an editor setting because the build already knows the answer, and a
hand-maintained setting drifts from it the first time a dependency changes. Generate it from the
same configuration the CLI is given:

```kotlin
val vsharpClasspathFile by tasks.registering {
    group = "ide"
    val target = layout.projectDirectory.file("vsharp.classpath")
    val entries = appRuntime          // the configuration passed to `--classpath`
    inputs.files(entries)
    outputs.file(target)
    doLast {
        target.asFile.writeText(
            entries.files.sortedBy { it.absolutePath }
                .joinToString("\n", postfix = "\n") { it.absolutePath },
            Charsets.UTF_8)
    }
}
```

A consuming project carries this task. Run `gradle vsharpClasspathFile`
once, and again whenever dependencies change.

A client may instead pass `initializationOptions.classpath` as an array of absolute paths, which
takes precedence; the descriptor is the normal route.

### Go to definition into a jar or the JDK

There is no source to open for a class file, so one is generated: a **signature view** of the
declaring type — declarations without bodies, in the spelling V# source must use — served through
a `vsharp-jar:` URI that the extension resolves. It is read-only, because nothing could be written
back into a class file. Jumping to a type lands on its declaration; jumping to a member lands on
that member's line.

```
// Signature view generated from the class file. Read-only.
// Members are shown in the spelling V# source must use.

namespace java.lang
{
    class Integer
    {
        public static int ParseInt(string p0, int p1);
```

Definition also reaches project files you have **not** opened, which previously returned nothing:
the file is on disk and has a URI, so it is offered. Only corelib, which ships inside the
compiler, genuinely has none.

### Errors in files you have not opened

Diagnostics are published for **every** file the analysis touched, not only the open ones, and
cleared when a file goes clean. This matters more than it sounds: `Compilation` gates its phases
globally, so one unresolved type anywhere halts the run before type checking — and if that error
sits in a file nobody has open, every open file looks clean while nothing is being checked.

That is exactly what a missing `vsharp.classpath` produces: eight unresolved-type errors in one
file disabled type checking across a whole project, and the symptoms reported were "type errors are
not caught" and "PascalCase is not enforced" — the member-spelling rule is an expression-binding
check that never got to run. If diagnostics look suspiciously absent, generate the classpath first.

### Measured agreement

With the descriptor in place the server and the CLI agree exactly on the two real applications:
across all **45 application sources** the server reports the same two intentional warnings the CLI
does — `VS1030` from a `#warning` directive and `VS0162` for deliberately unreachable code, at
identical positions — and nothing else; across the **9 sources of one project**, nothing at all.

### Known limitations

These are stated rather than hidden:

* **Hover and go-to-definition match by name**, not by reference resolution — the compiler does
  not yet record reference-to-symbol edges. A shadowed name can resolve to the outer
  declaration. Invisible for a name declared once, which is the overwhelmingly common case.
* **Go to definition returns nothing for corelib** and for files the editor has not opened:
  neither has an addressable URI.
* **Member completion resolves the qualifier by name**, not by resolving an arbitrary
  expression. A local, parameter or field works, including `var` (its inferred type is read from
  the binding), and so does an indexed one: `bytes[0].` peels one array rank per step and `s[0].`
  gives `char`. A type or namespace works. A receiver ending in a **call** — `Foo().` — does not,
  because that needs the call bound; the answer there is an empty list, never a guess.
* **Find references is a lexical search.** Whole-word matches only, so `Limit` does not match
  `LimitValue`, but two unrelated declarations sharing a name cannot be told apart, and a mention
  inside a comment is reported. The same missing reference-to-symbol edges are the cause.
* **Full document sync only.** Incremental sync is not advertised, so it is not implemented.
* **Signature help picks the overload by argument count**, not by argument types: an exact arity
  match wins, then the narrowest that can still take another argument, then the widest. Labels are
  written the way the declaration is — `string Program.Greet(string name, string greeting = "hello",
  int times = 1)`, `bool Program.TryParse(string text, out int value)`, `int Program.Sum(params
  int[] numbers)` — because that is what tells you what you may type. A function declared in the
  current file outranks one of the same name in corelib. Parameters of a class-file method are
  named `p0`, `p1` unless the jar was compiled with `-parameters`, because the names are genuinely
  not in the artifact.

## 2. Building

```sh
gradle --offline -q :vsharp-lsp:installDist     # the server, as a start script + jars
gradle --offline -q :vsharp-vscode:vsix         # the extension, as an installable .vsix
```

The `.vsix` lands in `vsharp-vscode/build/distributions/vsharp-0.0.93-A.vsix` and bundles the
server jars under `extension/server/lib`.

The grammar is generated, never edited by hand:

```sh
gradle --offline -q :vsharp-vscode:generateGrammar
```

It is written to `vsharp-vscode/build/generated/syntaxes/vsharp.tmLanguage.json`. The generator
validates its own output before writing — JSON brace balance, and every `match`/`begin`/`end`
compiled as a regular expression — because a malformed grammar does not crash an editor, it
loads and silently colours nothing.

## 3. Installing

VSCodium:

```sh
codium --install-extension vsharp-vscode/build/distributions/vsharp-0.0.93-A.vsix --force
```

VS Code:

```sh
code --install-extension vsharp-vscode/build/distributions/vsharp-0.0.93-A.vsix --force
```

Both are supported and neither is preferred; the extension uses no marketplace-only API.

The extension launches the bundled server with `java --module-path <ext>/server/lib
--add-modules ALL-DEFAULT --module vsharp.lsp/vsharp.lsp.Main`. It runs `java` directly rather
than the generated start script on purpose: zip entries carry no executable bit, so a bundled
launcher would arrive unrunnable.

**Java 25 or later is required at run time**, the same requirement the compiler has.

The launch is platform-neutral for the same reason: `java` is located through
`vsharp.server.java`, then `JAVA_HOME` (`bin\java.exe` on Windows, `bin/java` elsewhere), then
`PATH`. Only when an extension carries no bundled server does it fall back to an installed
launcher on `PATH`, and it names `vsharp-lsp.bat` on Windows and `vsharp-lsp` elsewhere,
because Node resolves neither spelling for the other platform.

## 4. Settings

| Setting | Default | Meaning |
| --- | --- | --- |
| `vsharp.server.path` | `""` | Absolute path to a `vsharp-lsp` launcher. Overrides the bundled server. |
| `vsharp.server.java` | `""` | `java` executable for the bundled server. Falls back to `JAVA_HOME`, then `PATH`. |
| `vsharp.trace.server` | `false` | Log every LSP message to the **V# Language Server** output channel. |

Commands: **V#: Restart Language Server**, **V#: Show Language Server Output**.

## 5. Syntax highlighting

`syntaxes/vsharp.tmLanguage.json` (scope `source.vsharp`, file type `.vs`) covers comments
including `///` documentation, all three string forms — regular, verbatim `@""` and raw `"""` —
with interpolation holes highlighted as code, character literals and escapes, hex/binary/decimal
numeric literals with digit separators and suffixes, preprocessor directives, the four keyword
categories, type and attribute declarations, invocations, operators and punctuation.

TextMate is a regular-expression colouriser, not a parser. It is deliberately the shallow half
of the story: anything structural — what a name binds to, whether it exists — comes from the
language server.

`language-configuration.json` sets the bracket, comment and folding behaviour, and its
indentation rules follow the compiler's mandatory **Allman** placement: indent increases after a
line that is only `{`, rather than after a trailing brace. A `///` line continues onto the next.

## 6. The status bar

The extension contributes a status bar item showing the server's state — spinning while starting,
a tick when ready, an error icon when it failed — and clicking it opens the log. It exists because
of a real report where the extension appeared completely inert and there was no way to tell a
server that had failed to start from one that was merely thinking, or from an editor that had
never activated the extension at all: three causes, one indistinguishable symptom.

**Reinstalling the `.vsix` does not affect an already-running window.** Run
**Developer: Reload Window** (or restart the editor) after an upgrade, or the old extension host
keeps serving.

## 7. Verifying an installation

Open any `.vs` file and confirm keywords colour. Then check the server is live: introduce an
undefined name and a diagnostic with a `VS….` code should appear, and removing it should clear
the diagnostic. If nothing happens, run **V#: Show Language Server Output**; a missing or too-old
`java` is the usual cause and is reported there.
