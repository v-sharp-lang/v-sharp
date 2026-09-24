package vsharp.lsp.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import vsharp.compiler.diagnostics.Diagnostic;
import vsharp.compiler.api.SurfaceVersion;
import vsharp.compiler.diagnostics.Severity;
import vsharp.compiler.semantics.binding.JavaInterop;
import vsharp.compiler.semantics.binding.SemanticModel;
import vsharp.compiler.semantics.symbols.NamedTypeSymbol;
import vsharp.compiler.semantics.symbols.Symbol;
import vsharp.compiler.source.SourceFile;
import vsharp.compiler.source.SourceSpan;
import vsharp.lsp.json.Json;
import vsharp.lsp.protocol.MessageStream;
import vsharp.lsp.protocol.Rpc;

/// The language server: one read loop, one document store, one analysis per edit.
///
/// The loop is single-threaded on purpose - LSP requires that notifications are processed
/// in order, and document state is only mutated here. Analysis is the expensive part and
/// runs on a virtual thread per edit; results are published only if the document version
/// they were computed from is still current, so a burst of keystrokes collapses to the
/// diagnostics of the last one instead of flickering through stale sets.
///
/// Requests that need semantic state wait for the analysis of the edit they follow. An
/// editor sends `didChange` and a completion request back to back, so answering from the
/// previous run would return symbols for text the user has already replaced - and on the
/// very first request after `didOpen` there is no previous run at all, which showed up as
/// an empty completion list. The wait is bounded: past the deadline the server answers
/// with whatever it has rather than making the editor hang.
public final class Server implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(Server.class.getName());

    /// How long a semantic request waits once a usable result already exists.
    ///
    /// Short, because degrading to the *previous* analysis is a mild answer: the symbols are
    /// real, just one edit old. Freezing the editor would be worse.
    private static final Duration WARM_DEADLINE = Duration.ofSeconds(2);

    /// How long the *first* request waits, when no analysis has ever landed.
    ///
    /// Longer than the warm budget, because degrading with nothing analysed means answering
    /// from nothing at all. Bounded well below a human's patience all the same: an editor that
    /// stops responding is reported as broken, and a keyword-only completion list that arrives
    /// is worth more than a perfect one that does not. The 30 seconds this was first
    /// set to is longer than any interactive request should ever take; three seconds, tried
    /// next, was shorter than a real project's first analysis and returned an empty outline.
    /// Ten covers the measured cold analysis of both reference applications with margin.
    private static final Duration COLD_DEADLINE = Duration.ofSeconds(10);

    private final MessageStream stream;

    private final DocumentStore documents = new DocumentStore();

    private final ExecutorService analysisPool = Executors.newVirtualThreadPerTaskExecutor();

    /// Analysis state, all guarded by [#analysisLock].
    ///
    /// `requested` counts edits, `completed` counts the runs that have landed. They are
    /// equal exactly when [#latest] describes the current buffers; a request that finds
    /// them unequal has an edit in flight and waits for it.
    private final Object analysisLock = new Object();

    private long requested;

    private long completed;

    private Analysis latest;

    /// The revision [#latest] was produced from, so an out-of-order completion cannot
    /// overwrite a newer result with an older one.
    private long published;

    /// The most recent analysis that actually produced a semantic model.
    ///
    /// [Compilation] gates its phases globally: a single error anywhere - including the
    /// half-typed line the user is looking at - stops the run at parsing, and a parsed unit
    /// carries no model. An editor asks for completion at precisely that moment, so binding
    /// semantic answers to the *current* run means answering nothing exactly when it matters:
    /// typing `obj.` creates the syntax error that destroys the model that would have told the
    /// editor what `obj` is. Measured in a user's session, every request while a file had one
    /// diagnostic returned zero symbols and a keyword-only completion list.
    ///
    /// So semantic features read the newest model that exists, which is the last parseable
    /// state of the program. Diagnostics still come from the current run, because those must
    /// describe the text on screen.
    private Analysis lastGood;

    /// URIs that currently carry published diagnostics, so markers on a file the editor never
    /// opened can be cleared once its errors are gone.
    private final java.util.Set<String> publishedUris = new java.util.LinkedHashSet<>();

    /// Classpath entries supplied by the client in `initializationOptions`, which take
    /// precedence over any discovered `vsharp.classpath` descriptor.
    private volatile List<java.nio.file.Path> configuredClasspath = List.of();

    /// Whether the client's build accepts unbounded task joins, from
    /// `initializationOptions.allowUnboundedJoins`. Defaults to false, which is what every
    /// build that does not pass `--allow-unbounded-joins` gets from the compiler.
    private volatile boolean allowUnboundedJoins;

    private final AtomicBoolean shutdownRequested = new AtomicBoolean();

    private boolean exit;

    public Server(InputStream in, OutputStream out) {
        this.stream = new MessageStream(Objects.requireNonNull(in, "in"),
                Objects.requireNonNull(out, "out"));
    }

    /// Runs until the client closes the stream or sends `exit`.
    ///
    /// @throws IOException if the underlying stream fails
    public void run() throws IOException {
        while (!exit) {
            Optional<Json> message = stream.read();
            if (message.isEmpty()) {
                break;
            }
            Optional<Rpc> rpc = Rpc.classify(message.get());
            if (rpc.isEmpty()) {
                continue;
            }
            dispatch(rpc.get());
        }
    }

    private void dispatch(Rpc rpc) throws IOException {
        switch (rpc) {
            case Rpc.Request request -> handleRequest(request);
            case Rpc.Notification notification -> handleNotification(notification);
        }
    }

    private void handleRequest(Rpc.Request request) throws IOException {
        if (shutdownRequested.get() && !"shutdown".equals(request.method())) {
            stream.write(Rpc.error(request.id(), Rpc.ErrorCodes.INVALID_STATE,
                    "Server is shutting down"));
            return;
        }
        try {
            Optional<Json> result = answer(request);
            if (result.isPresent()) {
                stream.write(Rpc.result(request.id(), result.get()));
            } else {
                stream.write(Rpc.error(request.id(), Rpc.ErrorCodes.METHOD_NOT_FOUND,
                        "Unhandled method: " + request.method()));
            }
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "Handler failed for " + request.method(), e);
            stream.write(Rpc.error(request.id(), Rpc.ErrorCodes.INTERNAL_ERROR,
                    request.method() + " failed: " + e));
        }
    }

    private Optional<Json> answer(Rpc.Request request) {
        return switch (request.method()) {
            case "initialize" -> {
                configuredClasspath = readClasspath(request.params());
                allowUnboundedJoins = request.params().object("initializationOptions")
                        .flatMap(options -> options.bool("allowUnboundedJoins"))
                        .orElse(false);
                yield Optional.of(Capabilities.initializeResult());
            }
            case "shutdown" -> {
                shutdownRequested.set(true);
                yield Optional.of(Json.Null.INSTANCE);
            }
            case "textDocument/completion" -> Optional.of(completion(request.params()));
            case "textDocument/signatureHelp" -> Optional.of(signatureHelp(request.params()));
            case "textDocument/hover" -> Optional.of(hover(request.params()));
            case "textDocument/definition" -> Optional.of(definition(request.params()));
            case "textDocument/references" -> Optional.of(references(request.params()));
            case "vsharp/javaSource" -> Optional.of(javaSource(request.params()));
            case "textDocument/documentSymbol" -> Optional.of(documentSymbols(request.params()));
            default -> Optional.empty();
        };
    }

    /// Reads `initializationOptions.classpath`, an array of absolute paths.
    ///
    /// This is the escape hatch for a project with no `vsharp.classpath` descriptor; the
    /// descriptor is the normal route, because the build already knows the answer and can
    /// write it, while a client-side setting drifts the first time a dependency changes.
    /// A path that does not exist is dropped rather than failing initialization - the editor
    /// should still start, and the compiler would ignore it anyway.
    private static List<java.nio.file.Path> readClasspath(Json.Obj params) {
        List<java.nio.file.Path> entries = new ArrayList<>();
        for (Json entry : params.object("initializationOptions")
                .map(options -> options.array("classpath"))
                .orElseGet(List::of)) {
            if (!(entry instanceof Json.Str text) || text.value().isBlank()) {
                continue;
            }
            try {
                java.nio.file.Path path = java.nio.file.Path.of(text.value());
                if (java.nio.file.Files.exists(path)) {
                    entries.add(path);
                }
            } catch (java.nio.file.InvalidPathException e) {
                LOG.log(System.Logger.Level.WARNING, "Bad configured classpath entry: "
                        + text.value(), e);
            }
        }
        return List.copyOf(entries);
    }

    private void handleNotification(Rpc.Notification notification) throws IOException {
        switch (notification.method()) {
            case "initialized" -> {
            }
            case "exit" -> exit = true;
            case "textDocument/didOpen" -> didOpen(notification.params());
            case "textDocument/didChange" -> didChange(notification.params());
            case "textDocument/didClose" -> didClose(notification.params());
            default -> LOG.log(System.Logger.Level.TRACE, "Ignored notification {0}",
                    notification.method());
        }
    }

    private void didOpen(Json.Obj params) {
        Json.Obj document = params.object("textDocument").orElse(Json.Obj.empty());
        String uri = document.string("uri").orElse(null);
        if (uri == null) {
            return;
        }
        int version = document.integer("version").orElse(0);
        documents.put(uri, version, document.string("text").orElse(""));
        schedule(uri, version);
    }

    /// Applies a change notification.
    ///
    /// Only full-document sync is advertised in [Capabilities], so the last content change
    /// carries the whole buffer. Incremental sync would need range editing here; refusing
    /// to advertise it is what keeps that complexity out of the server entirely.
    private void didChange(Json.Obj params) {
        Json.Obj document = params.object("textDocument").orElse(Json.Obj.empty());
        String uri = document.string("uri").orElse(null);
        if (uri == null) {
            return;
        }
        int version = document.integer("version").orElse(0);
        List<Json> changes = params.array("contentChanges");
        if (changes.isEmpty()) {
            return;
        }
        Json last = changes.get(changes.size() - 1);
        if (!(last instanceof Json.Obj change)) {
            return;
        }
        documents.put(uri, version, change.string("text").orElse(""));
        schedule(uri, version);
    }

    private void didClose(Json.Obj params) {
        Json.Obj document = params.object("textDocument").orElse(Json.Obj.empty());
        document.string("uri").ifPresent(uri -> {
            documents.remove(uri);
            publish(uri, List.of());
        });
    }

    /// Analyses the open set and publishes, unless the edit is already superseded.
    ///
    /// A superseded run still counts as completed: the edit that superseded it has its own
    /// run in flight, and leaving the counter behind would strand every waiting request
    /// until its deadline.
    private void schedule(String uri, int version) {
        long revision;
        synchronized (analysisLock) {
            revision = ++requested;
        }
        analysisPool.execute(() -> {
            // Coalesce a burst. Opening a project sends one `didOpen` per file, and analysing
            // the whole open set once per notification is quadratic: 35 files meant 35 full
            // compilations, which blew the request deadline and left the editor answering from
            // stale state. A task that is already superseded does no work at all; the task for
            // the newest revision does it once.
            synchronized (analysisLock) {
                if (revision < requested) {
                    completed = Math.max(completed, revision);
                    analysisLock.notifyAll();
                    return;
                }
            }
            // A server older than the compiler the project builds with is a different parser,
            // and analysing anyway produces syntax errors on source the build accepts - thirteen
            // of them, in the report that led to this check. Refusing is the
            // only honest answer, and it has to say why, because the alternative is an editor
            // full of errors that do not exist.
            java.util.OptionalInt required = Classpaths.requiredSurface(documents.all());
            if (required.isPresent() && required.getAsInt() > SurfaceVersion.CURRENT) {
                reportSurfaceSkew(required.getAsInt());
                return;
            }
            Analysis analysis = null;
            try {
                analysis = Analysis.of(documents.all(),
                        Classpaths.forDocuments(documents.all(), configuredClasspath),
                        allowUnboundedJoins);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "Analysis failed for " + uri, e);
            }
            boolean usable = analysis != null && documents.isCurrent(uri, version);
            synchronized (analysisLock) {
                // Two runs can overlap when an edit lands mid-analysis, and they can finish out
                // of order. Revision order, not completion order, decides which result stands:
                // without this an older run could overwrite a newer one and pin the editor to
                // diagnostics for text the user has already replaced.
                if (usable && revision > published) {
                    latest = analysis;
                    published = revision;
                    if (analysis.model().isPresent()) {
                        lastGood = analysis;
                    }
                } else {
                    usable = false;
                }
                completed = Math.max(completed, revision);
                analysisLock.notifyAll();
            }
            if (!usable) {
                return;
            }
            // Every file the analysis touched, not just the open ones, plus anything
            // published before that is now clean so its markers disappear.
            java.util.Set<String> targets = new java.util.LinkedHashSet<>(documents.uris());
            targets.addAll(analysis.diagnosticsByUri().keySet());
            synchronized (analysisLock) {
                targets.addAll(publishedUris);
                publishedUris.clear();
                analysis.diagnosticsByUri().forEach((each, list) -> {
                    if (!list.isEmpty()) {
                        publishedUris.add(each);
                    }
                });
            }
            for (String target : targets) {
                publish(target, analysis.diagnosticsByUri().getOrDefault(target, List.of()));
            }
        });
    }

    /// The analysis to answer a semantic request from, waiting for an in-flight edit.
    ///
    /// Returns empty only when nothing has ever analysed successfully or the deadline
    /// expired, in which case callers degrade rather than fail.
    private Optional<Analysis> analysis() {
        synchronized (analysisLock) {
            Duration budget = latest == null ? COLD_DEADLINE : WARM_DEADLINE;
            long deadline = System.nanoTime() + budget.toNanos();
            while (completed < requested) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    LOG.log(System.Logger.Level.WARNING,
                            "Analysis deadline expired; answering from stale state");
                    break;
                }
                try {
                    analysisLock.wait(Duration.ofNanos(remaining).toMillis() + 1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return Optional.ofNullable(latest);
        }
    }

    private Optional<SemanticModel> model() {
        return semanticAnalysis().flatMap(Analysis::model);
    }

    /// The analysis semantic features should answer from.
    ///
    /// The current run when it bound, otherwise the last one that did. See [#lastGood].
    private Optional<Analysis> semanticAnalysis() {
        Optional<Analysis> current = analysis();
        if (current.isPresent() && current.get().model().isPresent()) {
            return current;
        }
        synchronized (analysisLock) {
            return Optional.ofNullable(lastGood);
        }
    }

    /// Replaces every published marker with one message naming the version skew.
    ///
    /// One diagnostic per open file, at the first character, so it is visible wherever the user
    /// is looking without pretending to know which construct the older parser would have choked
    /// on. Analysis does not run, so nothing else is reported and no stale markers survive.
    private void reportSurfaceSkew(int required) {
        String message = "V# language server is older than this project's compiler: the project"
                + " requires language surface " + required + " and this server implements "
                + SurfaceVersion.CURRENT + ". Analysis is disabled because an older parser"
                + " reports errors on source the build accepts. Rebuild and reinstall the V#"
                + " extension, or point vsharp.server.path at a current server.";
        LOG.log(System.Logger.Level.ERROR, message);
        java.util.Set<String> targets;
        synchronized (analysisLock) {
            targets = new java.util.LinkedHashSet<>(documents.uris());
            targets.addAll(publishedUris);
            publishedUris.clear();
            publishedUris.addAll(documents.uris());
        }
        Json marker = Json.object()
                .put("range", Json.object()
                        .put("start", Json.object().put("line", 0).put("character", 0).build())
                        .put("end", Json.object().put("line", 0).put("character", 0).build())
                        .build())
                .put("severity", 1)
                .put("source", "vsharp")
                .put("message", message)
                .build();
        for (String target : targets) {
            List<Json> items = documents.uris().contains(target) ? List.of(marker) : List.of();
            try {
                stream.write(Rpc.notification("textDocument/publishDiagnostics", Json.object()
                        .put("uri", target)
                        .put("diagnostics", items)
                        .build()));
            } catch (IOException e) {
                LOG.log(System.Logger.Level.ERROR, "Could not publish the version skew", e);
            }
        }
    }

    private void publish(String uri, List<Diagnostic> diagnostics) {
        Optional<SourceFile> file = documents.get(uri).map(DocumentStore.Document::source);
        List<Json> items = new ArrayList<>(diagnostics.size());
        for (Diagnostic diagnostic : diagnostics) {
            SourceFile source = file.orElse(diagnostic.file());
            items.add(Json.object()
                    .put("range", Positions.range(source, diagnostic.span()))
                    .put("severity", severityOf(diagnostic.severity()))
                    .put("code", diagnostic.code().id())
                    .put("source", "vsharp")
                    .put("message", diagnostic.message())
                    .build());
        }
        Json.Obj params = Json.object()
                .put("uri", uri)
                .put("diagnostics", items)
                .build();
        try {
            stream.write(Rpc.notification("textDocument/publishDiagnostics", params));
        } catch (IOException e) {
            LOG.log(System.Logger.Level.ERROR, "Could not publish diagnostics", e);
        }
    }

    private static int severityOf(Severity severity) {
        return switch (severity) {
            case ERROR -> 1;
            case WARNING -> 2;
            case INFO -> 3;
        };
    }

    private Json completion(Json.Obj params) {
        Optional<SourceFile> file = fileOf(params);
        if (file.isEmpty()) {
            return Json.Arr.of(Completions.keywords());
        }
        int offset = Positions.offsetOf(file.get(), positionOf(params));
        Optional<SemanticModel> model = model();
        Optional<vsharp.compiler.semantics.binding.ExpressionBinding> binding =
                params.object("textDocument").flatMap(d -> d.string("uri"))
                        .flatMap(uri -> semanticAnalysis().flatMap(a -> a.bindingOf(uri)));
        // The analysis's own SourceFile, not a fresh one built from the buffer: the model
        // keys per-file imports by object identity, so a freshly constructed equal-looking
        // instance silently answers "no imports" and a simple Java name never resolves.
        Optional<SourceFile> modelFile = params.object("textDocument")
                .flatMap(d -> d.string("uri"))
                .flatMap(uri -> semanticAnalysis().flatMap(a -> a.sourceOf(uri)));
        return Json.Arr.of(Completions.at(file.get(), offset, model, binding, modelFile));
    }

    /// The parameter list of the call being written at the cursor.
    private Json signatureHelp(Json.Obj params) {
        Optional<SourceFile> file = fileOf(params);
        if (file.isEmpty()) {
            return Json.Null.INSTANCE;
        }
        String uri = params.object("textDocument").flatMap(d -> d.string("uri")).orElse(null);
        int offset = Positions.offsetOf(file.get(), positionOf(params));
        Optional<vsharp.compiler.semantics.binding.ExpressionBinding> binding = uri == null
                ? Optional.empty()
                : semanticAnalysis().flatMap(a -> a.bindingOf(uri));
        SourceFile modelFile = uri == null ? file.get()
                : semanticAnalysis().flatMap(a -> a.sourceOf(uri)).orElse(file.get());
        return SignatureHelp.at(file.get(), offset, model(), binding, modelFile);
    }

    /// Hover text for the identifier under the cursor.
    ///
    /// The identifier is read from the buffer and resolved by name against the bound
    /// symbol table, preferring a symbol declared in the same file. This is a name match,
    /// not a reference resolution - the compiler does not yet record reference-to-symbol
    /// edges - so shadowed names can hover to the outer declaration. The limitation is
    /// stated rather than hidden, and it is invisible for the overwhelmingly common case
    /// of a name that is declared once.
    private Json hover(Json.Obj params) {
        Optional<SourceFile> file = fileOf(params);
        if (file.isEmpty()) {
            return Json.Null.INSTANCE;
        }
        SourceFile source = file.get();
        int offset = Positions.offsetOf(source, positionOf(params));
        Optional<String> word = Words.at(source.text(), offset);
        if (word.isEmpty()) {
            return Json.Null.INSTANCE;
        }
        Optional<Symbol> symbol = resolve(word.get(), source);
        if (symbol.isEmpty()) {
            return Json.Null.INSTANCE;
        }
        String markdown = "```csharp\n" + Signatures.of(symbol.get()) + "\n```";
        return Json.object()
                .put("contents", Json.object()
                        .put("kind", "markdown")
                        .put("value", markdown)
                        .build())
                .put("range", Positions.range(source, Words.spanAt(source.text(), offset)))
                .build();
    }

    private Json definition(Json.Obj params) {
        Optional<SourceFile> file = fileOf(params);
        if (file.isEmpty()) {
            return Json.Null.INSTANCE;
        }
        SourceFile source = file.get();
        int offset = Positions.offsetOf(source, positionOf(params));
        Optional<String> word = Words.at(source.text(), offset);
        Optional<Symbol> symbol = word.flatMap(name -> resolve(name, source));
        if (symbol.isEmpty()) {
            // A JDK or dependency name never enters the symbol table until something binds it,
            // so a declared-symbol lookup cannot find `Integer` or `Files` at all. Resolve it
            // the way completion does instead.
            return word.map(name -> javaNameDefinition(name, source, offset, params))
                    .orElse(Json.Null.INSTANCE);
        }
        if (JavaInterop.isModulePathSymbol(symbol.get())) {
            return javaDefinition(symbol.get());
        }
        SourceFile declaring = symbol.get().location().file();
        Optional<String> uri = documents.all().stream()
                .filter(d -> d.source().name().equals(declaring.name()))
                .map(DocumentStore.Document::uri)
                .findFirst()
                // A project file the editor has not opened is still on disk and still has a
                // URI; only corelib, which ships inside the compiler, genuinely has none.
                .or(() -> pathUriOf(declaring.name()));
        if (uri.isEmpty()) {
            return Json.Null.INSTANCE;
        }
        return Json.object()
                .put("uri", uri.get())
                .put("range", Positions.range(declaring, symbol.get().location().span()))
                .build();
    }

    /// The `file:` URI of a source name that is a real path.
    private static Optional<String> pathUriOf(String name) {
        try {
            java.nio.file.Path path = java.nio.file.Path.of(name);
            return path.isAbsolute() && java.nio.file.Files.exists(path)
                    ? Optional.of(path.toUri().toString())
                    : Optional.empty();
        } catch (java.nio.file.InvalidPathException e) {
            return Optional.empty();
        }
    }

    /// Go-to-definition for a name the symbol table does not carry: a module-path type, or a
    /// member reached through one.
    ///
    /// The receiver is read and resolved exactly as completion reads it, so whatever the
    /// completion list offered is what this can open.
    private Json javaNameDefinition(String word, SourceFile source, int offset, Json.Obj params) {
        Optional<SemanticModel> model = model();
        if (model.isEmpty()) {
            return Json.Null.INSTANCE;
        }
        SemanticModel bound = model.get();
        String uri = params.object("textDocument").flatMap(d -> d.string("uri")).orElse(null);
        SourceFile modelFile = uri == null ? source
                : semanticAnalysis().flatMap(a -> a.sourceOf(uri)).orElse(source);

        int wordStart = Words.spanAt(source.text(), offset).start();
        Optional<Completions.Receiver> receiver =
                Completions.receiverBefore(source.text(), wordStart);
        if (receiver.isPresent()) {
            NamedTypeSymbol owner = ownerTypeOf(receiver.get(), bound, modelFile, source);
            if (owner == null) {
                return Json.Null.INSTANCE;
            }
            JavaSources.Rendered rendered = JavaSources.render(owner, bound);
            Integer line = rendered.memberLines().get(word);
            return line == null
                    ? Json.Null.INSTANCE
                    : locationIn(owner.qualifiedName(), line);
        }
        return Completions.javaTypeNamed(word, bound, modelFile)
                .map(type -> locationIn(type.qualifiedName(),
                        JavaSources.render(type, bound).typeLine()))
                .orElse(Json.Null.INSTANCE);
    }

    /// The class-file type a receiver denotes, if it is one.
    private NamedTypeSymbol ownerTypeOf(Completions.Receiver receiver, SemanticModel model,
            SourceFile modelFile, SourceFile source) {
        Optional<Symbol> named = Completions.typeOrNamespaceNamed(receiver.name(), model);
        if (named.isPresent() && named.get() instanceof NamedTypeSymbol type
                && model.javaInterop().isJavaType(type)) {
            return type;
        }
        Optional<vsharp.compiler.semantics.types.TypeSymbol> value =
                Completions.valueNamed(receiver.name(), model, Optional.empty(), source);
        if (value.isPresent()) {
            NamedTypeSymbol surface = Completions.memberSurfaceOf(value.get(), model);
            if (surface != null && model.javaInterop().isJavaType(surface)) {
                return surface;
            }
        }
        return Completions.javaTypeNamed(receiver.name(), model, modelFile).orElse(null);
    }

    private static Json locationIn(String qualifiedName, int line) {
        Json.Obj point = Json.object().put("line", line).put("character", 0).build();
        return Json.object()
                .put("uri", JavaSources.uriFor(qualifiedName))
                .put("range", Json.object().put("start", point).put("end", point).build())
                .build();
    }

    /// Go-to-definition for a symbol that came from a class file.
    ///
    /// There is no source to open, so one is generated: a signature view of the declaring
    /// type, served through the `vsharp-jar:` scheme and addressed at the member's line
    ///.
    private Json javaDefinition(Symbol symbol) {
        Optional<SemanticModel> model = model();
        if (model.isEmpty()) {
            return Json.Null.INSTANCE;
        }
        String qualified = symbol.qualifiedName();
        boolean isType = symbol.kind() == vsharp.compiler.semantics.symbols.SymbolKind.NAMED_TYPE;
        String typeName = isType ? qualified : ownerOf(qualified);
        if (typeName.isEmpty()) {
            return Json.Null.INSTANCE;
        }
        NamedTypeSymbol type = model.get().javaInterop().resolveType(typeName);
        if (type == null) {
            return Json.Null.INSTANCE;
        }
        JavaSources.Rendered rendered = JavaSources.render(type, model.get());
        int line = isType
                ? rendered.typeLine()
                : rendered.memberLines()
                        .getOrDefault(Completions.spellingOf(symbol), rendered.typeLine());
        Json.Obj point = Json.object().put("line", line).put("character", 0).build();
        return Json.object()
                .put("uri", JavaSources.uriFor(typeName))
                .put("range", Json.object().put("start", point).put("end", point).build())
                .build();
    }

    private static String ownerOf(String qualifiedName) {
        int lastDot = qualifiedName.lastIndexOf('.');
        return lastDot < 0 ? "" : qualifiedName.substring(0, lastDot);
    }

    /// Serves the text of a `vsharp-jar:` document to the extension.
    private Json javaSource(Json.Obj params) {
        Optional<SemanticModel> model = model();
        Optional<String> typeName = params.string("uri").flatMap(JavaSources::typeFromUri);
        if (model.isEmpty() || typeName.isEmpty()) {
            return Json.Null.INSTANCE;
        }
        NamedTypeSymbol type = model.get().javaInterop().resolveType(typeName.get());
        if (type == null) {
            return Json.Null.INSTANCE;
        }
        return Json.object()
                .put("text", JavaSources.render(type, model.get()).text())
                .build();
    }

    /// Every occurrence of the identifier under the cursor, across the open documents.
    ///
    /// This is a *lexical* search, and says so rather than pretending otherwise: the compiler
    /// records no reference-to-symbol edges, so two unrelated declarations sharing a name
    /// cannot be told apart. It is scoped to whole-word matches outside comments and strings,
    /// which is what makes it useful rather than noisy, and it only ever reports positions
    /// that really do spell the name. Going to a declaration and finding its uses is the
    /// second thing anybody tries in an editor; answering nothing at all was worse than
    /// answering this.
    private Json references(Json.Obj params) {
        Optional<SourceFile> file = fileOf(params);
        if (file.isEmpty()) {
            return Json.Arr.empty();
        }
        int offset = Positions.offsetOf(file.get(), positionOf(params));
        Optional<String> word = Words.at(file.get().text(), offset);
        if (word.isEmpty()) {
            return Json.Arr.empty();
        }
        boolean includeDeclaration = params.object("context")
                .flatMap(context -> context.get("includeDeclaration"))
                .map(value -> value instanceof Json.Bool b && b.value())
                .orElse(true);
        List<Json> locations = new ArrayList<>();
        for (DocumentStore.Document document : documents.all()) {
            SourceFile source = document.source();
            for (SourceSpan span : Words.occurrences(source.text(), word.get())) {
                boolean declaration = declaresAt(source, span, word.get());
                if (declaration && !includeDeclaration) {
                    continue;
                }
                locations.add(Json.object()
                        .put("uri", document.uri())
                        .put("range", Positions.range(source, span))
                        .build());
            }
        }
        return Json.Arr.of(locations);
    }

    /// Whether a symbol of this name is declared at `span` in `source`.
    private boolean declaresAt(SourceFile source, SourceSpan span, String name) {
        return model().map(m -> m.symbols().stream()
                .filter(symbol -> symbol.name().equals(name))
                .anyMatch(symbol -> symbol.location().file().name().equals(source.name())
                        && symbol.location().span().start() == span.start()))
                .orElse(false);
    }

    private Json documentSymbols(Json.Obj params) {
        Optional<SourceFile> file = fileOf(params);
        Optional<SemanticModel> model = model();
        if (file.isEmpty() || model.isEmpty()) {
            return Json.Arr.empty();
        }
        return Json.Arr.of(DocumentSymbols.of(model.get(), file.get()));
    }

    private Optional<Symbol> resolve(String name, SourceFile file) {
        Optional<SemanticModel> model = model();
        if (model.isEmpty()) {
            return Optional.empty();
        }
        // Matched on the spelling the *source* uses: a class-file member is declared
        // `parseInt` and written `ParseInt`, so matching the raw name would never find the
        // symbol the user is pointing at.
        List<Symbol> matches = model.get().symbols().stream()
                .filter(s -> s.name().equals(name) || Completions.spellingOf(s).equals(name))
                .toList();
        return matches.stream()
                .filter(s -> s.location().file().name().equals(file.name()))
                .findFirst()
                .or(() -> matches.stream().findFirst());
    }

    private Optional<SourceFile> fileOf(Json.Obj params) {
        String uri = params.object("textDocument")
                .flatMap(d -> d.string("uri"))
                .orElse(null);
        if (uri == null) {
            return Optional.empty();
        }
        return documents.get(uri)
                .map(DocumentStore.Document::source)
                .or(() -> analysis().flatMap(a -> a.sourceOf(uri)));
    }

    private static Json.Obj positionOf(Json.Obj params) {
        return params.object("position").orElse(Json.Obj.empty());
    }

    @Override
    public void close() throws IOException {
        analysisPool.close();
        stream.close();
    }

    /// The document URIs currently published against, for tests.
    Map<String, List<Diagnostic>> lastDiagnostics() {
        return analysis().map(Analysis::diagnosticsByUri).orElse(Map.of());
    }
}
