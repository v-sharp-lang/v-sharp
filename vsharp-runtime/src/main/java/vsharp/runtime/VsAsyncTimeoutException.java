package vsharp.runtime;

/// Thrown when an `await` does not complete within [VsAsync#LIMIT], or when the awaited task
/// was cancelled or the waiting thread interrupted.
///
/// It is unchecked because V# has no checked-exception surface, and distinct from the failure
/// of the body itself: this says the task did not finish, not that it finished badly.
public final class VsAsyncTimeoutException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /// Creates the exception.
    ///
    /// @param message what was exceeded or interrupted
    /// @param cause the concurrency exception that reported it
    public VsAsyncTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
