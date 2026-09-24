package vsharp.compiler.semantics.types;

import vsharp.compiler.semantics.symbols.NamedTypeSymbol;

/// What `Task` means, in one place.
///
/// V# has no `Task` type of its own: `Task` is `java.util.concurrent.Future` and `Task<T>` is
/// `Future<T>`. Every stage that must recognise a task asks here rather than repeating
/// the qualified name, so the identity is a single fact rather than a convention.
public final class TaskTypes {

    /// The JDK type `Task` spells.
    public static final String TASK_QUALIFIED_NAME = "java.util.concurrent.Future";

    private TaskTypes() {
        throw new AssertionError("No instances");
    }

    /// Whether a named type is the task definition.
    ///
    /// @param definition the type to test
    /// @return whether it is `java.util.concurrent.Future`
    public static boolean isTaskDefinition(NamedTypeSymbol definition) {
        return definition != null && TASK_QUALIFIED_NAME.equals(definition.qualifiedName());
    }

    /// Whether a type is `Task` or `Task<T>`, which is what an `async` callable must return
    /// and what `await` accepts.
    ///
    /// @param type the type to test
    /// @return whether the type is a task
    public static boolean isTask(TypeSymbol type) {
        return switch (type) {
            case TypeSymbol.Constructed constructed -> isTaskDefinition(constructed.definition());
            case NamedTypeSymbol named -> isTaskDefinition(named);
            default -> false;
        };
    }

    /// The value an awaited task produces.
    ///
    /// @param argument the task's type argument
    /// @return the result type; `java.lang.Void` reads as `void`, so an `async Task` body that
    ///     produces nothing awaits as nothing rather than as a null of an unspeakable type
    public static TypeSymbol resultOf(TypeSymbol argument) {
        if (argument instanceof NamedTypeSymbol named
                && "java.lang.Void".equals(named.qualifiedName())) {
            return BuiltinType.VOID;
        }
        return argument;
    }

    /// The result type an `async` callable's body produces, given its declared task type.
    ///
    /// @param declared the declared `Task` or `Task<T>`
    /// @return `void` for a bare `Task`, otherwise the task's argument
    public static TypeSymbol bodyResultOf(TypeSymbol declared) {
        if (declared instanceof TypeSymbol.Constructed constructed
                && !constructed.arguments().isEmpty()) {
            return resultOf(constructed.arguments().getFirst());
        }
        return BuiltinType.VOID;
    }

    /// Whether an `async` declaration is detached - `async void`, which no caller can join.
    ///
    /// @param declared the declared return type
    /// @return whether the callable hands back nothing at all
    public static boolean isDetached(TypeSymbol declared) {
        return declared == BuiltinType.VOID;
    }
}
