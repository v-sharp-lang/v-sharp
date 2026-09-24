package vsharp.lsp.server;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Optional;

/// Conversions between LSP document URIs and local filesystem paths.
///
/// Editors address buffers by URI; the compiler names sources by display string and reads
/// them from [Path]. Keeping the translation in one place means a diagnostic's file name
/// and the URI it is published against can never drift apart.
final class Uris {

    private Uris() {
        throw new AssertionError("No instances");
    }

    /// The local path behind a `file:` URI, or empty for any other scheme.
    ///
    /// Untitled and in-memory buffers legitimately have no path; they are still analysable
    /// because their text arrives in the `didOpen` payload, so an absent path is a normal
    /// outcome rather than an error.
    static Optional<Path> toPath(String uri) {
        try {
            URI parsed = new URI(uri);
            if (!"file".equalsIgnoreCase(parsed.getScheme())) {
                return Optional.empty();
            }
            return Optional.of(Path.of(parsed));
        } catch (URISyntaxException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /// The `file:` URI for a local path.
    static String fromPath(Path path) {
        return path.toUri().toString();
    }

    /// The display name the compiler should use for a document.
    ///
    /// A real path renders as the path, so compiler output matches what the CLI would
    /// print for the same file; anything else falls back to the URI itself, which is at
    /// least unique and recognisable in the editor.
    static String displayName(String uri) {
        return toPath(uri).map(Path::toString).orElse(uri);
    }
}
