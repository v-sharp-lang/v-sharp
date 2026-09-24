package vsharp.runtime;

/// Carries a checked exception out of an awaited task.
///
/// A V# body cannot declare a checked exception, so this exists for the one case that can
/// still produce one: an `async` body that called a Java method which threw it. The original
/// is the cause and is never discarded.
public final class VsAsyncException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /// Creates the carrier.
    ///
    /// @param message the original exception's message
    /// @param cause the exception the awaited body threw
    public VsAsyncException(String message, Throwable cause) {
        super(message, cause);
    }
}
