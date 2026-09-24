package vsharp.lsp.json;

/// A malformed JSON payload.
///
/// Unchecked and contextual: a client that sends broken JSON is a protocol error the
/// server reports and recovers from, not a condition any caller can meaningfully handle
/// by catching a checked exception at every parse site.
public final class JsonException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /// Creates the exception with a message describing the offending position.
    public JsonException(String message) {
        super(message);
    }
}
