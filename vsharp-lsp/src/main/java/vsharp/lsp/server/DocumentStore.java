package vsharp.lsp.server;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import vsharp.compiler.source.SourceFile;

/// The set of documents the editor currently has open, and their latest text.
///
/// The map is concurrent because the reader loop mutates it while analysis tasks on
/// virtual threads read it. Each entry is an immutable snapshot carrying the client's
/// version number, so a task that started against version 7 can notice that version 8 has
/// since arrived and drop its stale result instead of publishing diagnostics for text the
/// user has already edited past.
final class DocumentStore {

    /// One immutable snapshot of an open buffer.
    record Document(String uri, int version, String text) {

        Document {
            Objects.requireNonNull(uri, "uri");
            Objects.requireNonNull(text, "text");
        }

        /// The compiler input for this snapshot.
        ///
        /// Built on demand rather than cached: it is a line index over text the editor may
        /// replace on the next keystroke, and building it is linear in a file that was
        /// just transmitted in full anyway.
        SourceFile source() {
            return SourceFile.of(Uris.displayName(uri), text);
        }
    }

    private final ConcurrentMap<String, Document> documents = new ConcurrentHashMap<>();

    /// Records a newly opened or fully replaced buffer.
    void put(String uri, int version, String text) {
        documents.put(uri, new Document(uri, version, text));
    }

    /// Drops a closed buffer.
    void remove(String uri) {
        documents.remove(uri);
    }

    /// The current snapshot of `uri`, if it is open.
    Optional<Document> get(String uri) {
        return Optional.ofNullable(documents.get(uri));
    }

    /// Whether `uri` is still open at exactly `version`.
    ///
    /// The staleness check every analysis task makes before publishing.
    boolean isCurrent(String uri, int version) {
        Document current = documents.get(uri);
        return current != null && current.version() == version;
    }

    /// Every open document, in URI order so that iteration is deterministic.
    Collection<Document> all() {
        return documents.values().stream()
                .sorted(java.util.Comparator.comparing(Document::uri))
                .toList();
    }

    /// The open URIs, in order.
    List<String> uris() {
        return all().stream().map(Document::uri).toList();
    }
}
