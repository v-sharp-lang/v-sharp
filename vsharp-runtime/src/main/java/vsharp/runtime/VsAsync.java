package vsharp.runtime;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/// The execution surface behind `async` and `await`.
///
/// V# does not implement the C# state machine. An `async` callable is compiled into an
/// ordinary method holding the body plus a wrapper that submits it here and returns the
/// [Future] the submission produced; `await` is the blocking read of such a future. The
/// language keeps its shape - concurrent work starts at the call and is joined at the
/// `await` - without the object model the C# awaiter protocol requires.
///
/// One task is one virtual thread. That is what makes the blocking model cheap enough to be
/// the whole implementation: a parked virtual thread costs a heap object rather than an OS
/// thread, so a program may have as many outstanding tasks as it has work.
public final class VsAsync {

    /// The execution limit every awaited task is held to.
    ///
    /// This is an architectural constant, not a default: there is no parameter, overload,
    /// configuration key or environment variable that changes it, and none will be added.
    /// A bounded wait is what makes a blocking `await` safe to compile into every program -
    /// a task that hangs surfaces as a failure at a known instant instead of holding its
    /// caller forever.
    public static final Duration LIMIT = Duration.ofSeconds(60);

    private static final ExecutorService TASKS =
            Executors.newVirtualThreadPerTaskExecutor();

    private static final System.Logger LOG = System.getLogger("vsharp.runtime.VsAsync");

    private VsAsync() {
        throw new AssertionError("No instances");
    }

    /// Starts a value-returning `async` body.
    ///
    /// @param <T> the body's result type
    /// @param body the compiled body of the `async` callable
    /// @return the running task
    public static <T> Future<T> run(Callable<T> body) {
        Objects.requireNonNull(body, "body");
        return TASKS.submit(body);
    }

    /// Starts an `async` body that produces no value.
    ///
    /// @param body the compiled body of the `async` callable
    /// @return the running task, whose result is always `null`
    public static Future<Void> runVoid(Runnable body) {
        Objects.requireNonNull(body, "body");
        return TASKS.submit(body, null);
    }

    /// Starts an `async void` body, which nobody can join.
    ///
    /// `async void` says the caller will not wait, so nothing will ever read the failure out of
    /// a future. Losing it silently is the one outcome that cannot be debugged, so the failure
    /// is logged here instead. This is a deliberate divergence from C#, which rethrows on the
    /// captured synchronization context and typically ends the process: V# has no such context,
    /// and taking a process down for a detached background task would be worse than reporting
    /// it.
    ///
    /// @param body the compiled body of the `async void` callable
    /// @param name the callable's source name, so the log names what failed
    public static void runDetached(Runnable body, String name) {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(name, "name");
        TASKS.submit(() -> {
            try {
                body.run();
            } catch (RuntimeException | Error failure) {
                LOG.log(System.Logger.Level.ERROR,
                        "async void {0} failed and nothing was waiting for it", name, failure);
            }
        });
    }

    /// Completes an already-known value as a task, for an `async` body that returned without
    /// starting any work.
    ///
    /// @param <T> the value type
    /// @param value the value
    /// @return a task that is already done
    public static <T> Future<T> completed(T value) {
        return java.util.concurrent.CompletableFuture.completedFuture(value);
    }

    /// Waits for a task and produces its result, which is what `await` compiles to.
    ///
    /// The wait is bounded by [#LIMIT]. On expiry the task is interrupted and the wait fails:
    /// leaving it running would hand back a thread that still holds whatever the task holds,
    /// and reporting success would be a lie.
    ///
    /// Failure is reported as the body threw it. [ExecutionException] is unwrapped, because a
    /// caller that wrote `await Work()` is reading code that looks like a call and must be
    /// able to catch what a call throws; only a checked exception - which no V# body can
    /// declare - is wrapped, and then in an unchecked carrier rather than swallowed.
    ///
    /// @param <T> the task's result type
    /// @param task the task to wait for
    /// @return the task's result
    public static <T> T await(Future<T> task) {
        Objects.requireNonNull(task, "task");
        try {
            return task.get(LIMIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException expiry) {
            task.cancel(true);
            throw new VsAsyncTimeoutException(
                    "await exceeded the " + LIMIT.toSeconds() + " second execution limit", expiry);
        } catch (CancellationException cancelled) {
            throw new VsAsyncTimeoutException("the awaited task was cancelled", cancelled);
        } catch (InterruptedException interruption) {
            // The waiting thread was interrupted, not the task. Cancel what is now unobserved
            // and restore the flag so an enclosing cancellation still works.
            task.cancel(true);
            Thread.currentThread().interrupt();
            throw new VsAsyncTimeoutException("the thread awaiting a task was interrupted",
                    interruption);
        } catch (ExecutionException failure) {
            throw rethrow(failure.getCause());
        }
    }

    /// Waits for a task that produces no value.
    ///
    /// @param task the task to wait for
    public static void awaitVoid(Future<?> task) {
        await(task);
    }

    private static RuntimeException rethrow(Throwable cause) {
        if (cause instanceof RuntimeException unchecked) {
            return unchecked;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return new VsAsyncException(cause == null ? "the awaited task failed" : cause.getMessage(),
                cause);
    }
}
