package vsharp.lsp.server;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SequencedMap;
import vsharp.compiler.api.Compilation;
import vsharp.compiler.api.CompilationResult;
import vsharp.compiler.api.UnitAnalysis;
import vsharp.compiler.diagnostics.Diagnostic;
import vsharp.compiler.semantics.binding.SemanticModel;
import vsharp.compiler.source.SourceFile;

/// One front-end run over the open documents, indexed for editor queries.
///
/// The editor compiles the whole *project* rather than one file at a time, because V#
/// namespaces span files and a single-file view invents resolution errors the CLI does not
/// report - measured at 19 of them on one application controller. [ProjectSources] finds
/// the source set; open buffers override what is on disk. Only the front end runs -
/// [Compilation#analyze], never `emit` - so no bytecode is generated for a buffer that
/// changes on the next keystroke.
record Analysis(CompilationResult result, SequencedMap<String, SourceFile> sourcesByUri,
        Map<String, List<Diagnostic>> diagnosticsByUri, Optional<SemanticModel> model) {

    Analysis {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(sourcesByUri, "sourcesByUri");
        Objects.requireNonNull(diagnosticsByUri, "diagnosticsByUri");
        Objects.requireNonNull(model, "model");
    }

    /// Runs the front end over `documents`, resolving Java types against `classpath` as well
    /// as the JDK the server runs on.
    ///
    /// JDK types need no classpath - the compiler resolves them against the JDK it runs on,
    /// exactly as the CLI does - but a real project's *dependencies* do, and without
    /// them the editor reports errors on files the CLI builds cleanly.
    static Analysis of(java.util.Collection<DocumentStore.Document> documents,
            java.util.List<java.nio.file.Path> classpath) {
        return of(documents, classpath, false);
    }

    /// The same analysis, accepting unbounded task joins.
    ///
    /// `Get`/`Join`/`GetNow` on a task is a compile error, because such a join waits outside
    /// the execution limit `await` is held to. A build may accept it with
    /// `--allow-unbounded-joins`, and when it does the editor has to agree: analysing without
    /// the option would paint every such call site red while the build is green, which is the
    /// exact editor-versus-compiler divergence the design and that rule were about.
    static Analysis of(java.util.Collection<DocumentStore.Document> documents,
            java.util.List<java.nio.file.Path> classpath, boolean allowUnboundedJoins) {
        // The whole project, not just the open buffers: a sibling type the editor has not
        // opened is still part of the program, and compiling without it reports it missing
        //.
        List<SourceFile> sources = ProjectSources.discover(documents);

        // Index the *exact* instances handed to the compiler. [SourceFile] has no value
        // equality, and [SemanticModel] keys per-file imports by object identity, so handing a
        // later query an equal-looking copy makes the model answer "this file imports nothing"
        // - which silently stopped every simple Java name from resolving.
        Map<String, SourceFile> compiledByName = new LinkedHashMap<>();
        for (SourceFile source : sources) {
            compiledByName.put(source.name(), source);
        }
        SequencedMap<String, SourceFile> byUri = new LinkedHashMap<>();
        Map<String, String> uriByName = new LinkedHashMap<>();
        for (DocumentStore.Document document : documents) {
            String name = Uris.displayName(document.uri());
            SourceFile file = compiledByName.getOrDefault(name, document.source());
            byUri.put(document.uri(), file);
            uriByName.put(file.name(), document.uri());
        }

        Compilation compilation = Compilation.of(sources, java.util.Set.of(), classpath);
        if (allowUnboundedJoins) {
            compilation = compilation.allowingUnboundedJoins();
        }
        CompilationResult result = compilation.analyze();

        Map<String, List<Diagnostic>> byDocument = new LinkedHashMap<>();
        for (String uri : byUri.keySet()) {
            byDocument.put(uri, new ArrayList<>());
        }
        for (Diagnostic diagnostic : result.diagnostics()) {
            // Corelib is compiler-shipped source and has no path, so its diagnostics - there
            // should be none - are dropped. Every other file is reported, *including* one the
            // editor has not opened. Dropping those was actively harmful: a single unresolved
            // type in an unopened file halts the pipeline before type checking, so the whole
            // project silently stops being validated and nothing on screen says why. Measured
            // on a real project, eight such errors in one unopened file disabled type checking
            // everywhere while every open file showed a clean bill of health.
            String uri = uriByName.get(diagnostic.file().name());
            if (uri == null) {
                uri = pathUriOf(diagnostic.file().name());
            }
            if (uri != null) {
                byDocument.computeIfAbsent(uri, key -> new ArrayList<>()).add(diagnostic);
            }
        }

        return new Analysis(result, byUri, byDocument, firstModel(result));
    }

    /// The `file:` URI of a source that is not an open buffer, or `null` when the name is not
    /// a path at all - which is how compiler-shipped corelib is recognised.
    private static String pathUriOf(String name) {
        try {
            java.nio.file.Path path = java.nio.file.Path.of(name);
            return path.isAbsolute() ? path.toUri().toString() : null;
        } catch (java.nio.file.InvalidPathException e) {
            return null;
        }
    }

    /// The semantic model of the run.
    ///
    /// Every unit of a result shares one model instance - binding is global, not per-file -
    /// so the first unit that reached at least the declared shape answers for all of them.
    /// A run that stopped at parsing has no model at all, which is why this is optional and
    /// why completion degrades to keywords when a buffer is badly broken.
    private static Optional<SemanticModel> firstModel(CompilationResult result) {
        for (UnitAnalysis unit : result.units()) {
            SemanticModel model = switch (unit) {
                case UnitAnalysis.Parsed ignored -> null;
                case UnitAnalysis.Declared declared -> declared.model();
                case UnitAnalysis.Bound bound -> bound.model();
                case UnitAnalysis.Analysed analysed -> analysed.model();
                case UnitAnalysis.Emitted emitted -> emitted.model();
            };
            if (model != null) {
                return Optional.of(model);
            }
        }
        return Optional.empty();
    }

    /// The parsed source for `uri`, if it took part in this run.
    Optional<SourceFile> sourceOf(String uri) {
        return Optional.ofNullable(sourcesByUri.get(uri));
    }

    /// The bound expressions of `uri`, when binding got that far.
    ///
    /// This is what knows the type of a `var` local: inference happens during expression
    /// binding and is recorded here, not written back onto the symbol, so a completion that
    /// reads only [SemanticModel] sees `var` as unresolved and offers nothing.
    Optional<vsharp.compiler.semantics.binding.ExpressionBinding> bindingOf(String uri) {
        return unitOf(uri).map(unit -> switch (unit) {
            case UnitAnalysis.Bound bound -> bound.expressions();
            case UnitAnalysis.Analysed analysed -> analysed.expressions();
            case UnitAnalysis.Emitted emitted -> emitted.expressions();
            case UnitAnalysis.Parsed ignored -> null;
            case UnitAnalysis.Declared ignored -> null;
        }).filter(java.util.Objects::nonNull);
    }

    /// The unit analysis for `uri`, if it took part in this run.
    Optional<UnitAnalysis> unitOf(String uri) {
        return sourceOf(uri).flatMap(file -> result.units().stream()
                .filter(unit -> unit.file().name().equals(file.name()))
                .findFirst());
    }
}
