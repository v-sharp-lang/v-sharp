package vsharp.testkit;

/// Signals a failed assertion. Distinct from ordinary exceptions so the runner can report
/// assertion failures separately from unexpected errors in the code under test.
public final class AssertionFailure extends RuntimeException {

    /// Creates a failure with the given message.
    public AssertionFailure(String message) {
        super(message);
    }

    /// Creates a failure with the given message and cause.
    public AssertionFailure(String message, Throwable cause) {
        super(message, cause);
    }
}
