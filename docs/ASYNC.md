# async and await in V#

V# implements `async`/`await` as **syntactic sugar over a bounded blocking join**, not as C#'s
state machine. The shape a program relies on is preserved — work starts at the call, the caller
joins it at the `await`, a result or an exception comes back — while the mechanism is a virtual
thread and a `java.util.concurrent.Future`.

```csharp
using System;

static class Work
{
    public static async Task<int> Double(int value)
    {
        return value * 2;
    }

    public static async Task Announce(string text)
    {
        Console.WriteLine("announcing " + text);
    }
}

Task<int> first = Work.Double(21);     // starts here
Task<int> second = Work.Double(50);    // runs alongside the first
Console.WriteLine(await first + await second);   // joins here
await Work.Announce("done");
```

## `Task` is `java.util.concurrent.Future`

There is no V#-owned task type. `Task` **is** `java.util.concurrent.Future` and `Task<T>` is
`Future<T>`, the same way `System.Type` is `java.lang.Class`. Three consequences, all intended:

- A future returned by *any* JDK or third-party API is awaitable with no adapter:

  ```csharp
  using java.util.concurrent;

  ExecutorService pool = Executors.NewVirtualThreadPerTaskExecutor();
  Task<string> work = pool.Submit(() => "from the jdk");
  Console.WriteLine(await work);
  ```

- A task's own members work, in V#'s PascalCase spelling: `task.IsDone()`, `task.Cancel(true)`.
- A `Task` crossing into Java is an ordinary `Future`, so Java callers need nothing generated.

## The 60-second execution limit

`await` waits at most **60 seconds**. This is an architectural constant, not a default: there is
no overload, attribute, property, configuration key or environment variable that changes it, and
a test asserts the value so that adding one fails the build.

On expiry the task is cancelled and `VsAsyncTimeoutException` is thrown. A bounded wait is what
makes a blocking `await` safe to compile into every program — a task that hangs surfaces at a
known instant instead of holding its caller forever.

## Failure

The body's exception is what you catch, unwrapped from `ExecutionException`:

```csharp
try
{
    int value = await Failing();
}
catch (InvalidOperationException failure)   // the body's own type
{
    Console.WriteLine(failure.GetMessage());
}
```

A checked exception — only reachable from a Java call inside the body — arrives wrapped in
`VsAsyncException` with the original as its cause, because V# has no checked-exception surface.

## What is emitted

An `async` declaration becomes two methods, which `javap` shows plainly:

```
public static java.util.concurrent.Future Double(int);   // declared name and signature
public static int Double$async(int);                     // the written body
```

The wrapper's whole implementation is one `invokedynamic` binding the body to a `Callable`
(or `Runnable` when the body produces nothing) plus one call to `vsharp.runtime.VsAsync.run`.
`await` is a call to `VsAsync.await` followed by the cast or unboxing the erasure removed. One
task is one virtual thread, so a program may have as many outstanding tasks as it has work.

Every stage before lowering — parsing aside — is unaware that `async` exists: the body is bound,
flow-analysed and lowered as an ordinary method, because that is what it is.

## `await` works in any body

Unlike C#, `await` is **not** confined to `async` methods. C# confines it because its state
machine rewrites the enclosing method; V# performs no rewrite — `await` is a call — so an
ordinary method can start tasks and join them:

```csharp
public static bool FanIn()          // not async
{
    Task<int> a = Unit(300);
    Task<int> b = Unit(300);
    return await a + await b == 600;
}
```

This is what makes the 60-second limit reachable everywhere. Before it, a synchronous caller had
to join with the JDK's own `Future.Get()`, which is **unbounded**: measured against a task that
hangs, `Get()` returned after 90 s where `await` fails at 60.

**Divergence:** a local variable can no longer be named `await`. That is the price of the bounded
join being the only idiom, and it is the one C# compatibility V# gives up here.

## `async void`

`async void` is the declaration that says nobody will join. It produces no task at all:

```csharp
public static async void Warm()     // emitted as `void Warm()`, no future
{
    RebuildCache();
}
```

Because nothing can observe the result, an escaping exception is **logged** through
`System.Logger` naming the callable, rather than stored in a future nobody reads:

```
GRAVE: async void Warm failed and nothing was waiting for it
```

This diverges from C#, which rethrows on the captured synchronization context and usually ends
the process. V# has no such context, and killing a process for a detached background task would
be worse than reporting it.

## Restrictions

Each is diagnosed rather than silently mis-compiled:

| Written | Diagnostic |
| --- | --- |
| `async` returning something other than `Task`/`Task<T>` | `VS20014` |
| `await` applied to a non-task | `VS20015` |
| `async` on a lambda or a local function | `VS20016` |
| `ref`, `out` or `in` parameter on an `async` callable | `VS20017` |

`ref`/`out`/`in` stay refused, as in C#, and V#'s lowering makes the reason concrete: a
by-reference parameter is a shared cell, the body writes it on another thread, and the caller
already holds the wrapper's result — an `out` would be read before it was written. Return a tuple
instead:

```csharp
static async Task<(bool, int)> TryParse(string text) { ... }
var (ok, value) = await TryParse(text);
```

There is no cancellation token and no `ConfigureAwait`, neither of which has meaning for a
bounded blocking join.

`async` lambdas and local functions are refused for a concrete reason: both are emitted through
machinery that passes captured outer values as shared cells, and the body/wrapper split would
need a second convention to forward those cells. A declared method has no capture convention.
Move the body to one.

## Joining with `Get()` is refused

`await` is the only supported way to join a task. The JDK's own blocking reads are a compile
error, because they wait outside the 60-second limit:

```
error VS20018: 'Get' on a task waits outside the 60 second execution limit;
use 'await' instead, or pass --allow-unbounded-joins to accept unbounded waits in this build
```

This covers `Get`, `Join` and `GetNow` on anything that is a `Future` — including a
`CompletableFuture` from a library. It is about *tasks*, not about a method name:
`list.Get(0)` and `map.Get(k)` are untouched.

It is not hypothetical. This bypass reached production in a server whose TLS certificate
loading joined with `Get()`; measured against a hanging task it waited the full **90 s** where
`await` fails at 60, so a certificate on an unresponsive filesystem would have wedged startup
forever.

`--allow-unbounded-joins` downgrades every occurrence to a warning. It never silences them: the
decision then shows up both in the build command and at each call site.

## An un-awaited task swallows its failure

A task nobody joins reports nothing — the exception is stored in the future and never read:

```csharp
Task<int> ignored = Boom();   // throws inside; nothing is printed, nothing fails
```

This is `ExecutorService.submit` semantics, and matches C#'s unobserved task exceptions. If a
task's failure matters, join it. Starting work you never join is a decision, not an accident the
compiler can detect for you.

## What this is not

This does not make a single call faster, and it is not cooperative scheduling. Awaiting
immediately after calling is exactly a synchronous call with extra steps:

```csharp
int value = await Work.Double(21);    // no concurrency: started and joined at once
```

The benefit is in starting several tasks before joining any of them, and in blocking on a
virtual thread rather than a platform one.
