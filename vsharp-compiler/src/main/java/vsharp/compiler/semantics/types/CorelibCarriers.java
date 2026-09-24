package vsharp.compiler.semantics.types;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/// The corelib declarations that are backed by a real JVM class, and which class that is.
///
/// Most of `corelib.vs` declares names the type system needs and the backend never emits: a
/// V# `int` is the JVM `int`, and `System.Int32` has no runtime carrier at all. The
/// exceptions are the exception types, which must be real classes because `throw` and
/// `catch` are JVM instructions over `java.lang.Throwable`.
///
/// Keeping the mapping here rather than in a name comparison inside each stage is what stops
/// the binder and the backend from disagreeing: the binder decides that a type is a
/// reference type, is throwable and is testable from exactly the table the emitter then
/// writes a descriptor from.
///
/// `System.OverflowException` and `System.DivideByZeroException` map to
/// `java.lang.ArithmeticException` - the class the JVM itself throws for `x / 0`
/// and the one `Math.addExact` throws on overflow - so the checked helpers need no exception
/// class of V#'s own. Their shared-carrier catch overlap is a documented JVM divergence.
/// `System.FormatException` is the one deliberate
/// runtime-owned carrier: NumberFormatException would put it below ArgumentException on the
/// JVM, contradicting the sibling relationship that C# programs can observe.
public final class CorelibCarriers {

    private static final Map<String, String> JAVA_CLASSES = Map.of(
            "System.Exception", "java.lang.Exception",
            "System.OverflowException", "java.lang.ArithmeticException",
            "System.DivideByZeroException", "java.lang.ArithmeticException",
            "System.IndexOutOfRangeException", "java.lang.ArrayIndexOutOfBoundsException",
            "System.NullReferenceException", "java.lang.NullPointerException",
            "System.InvalidCastException", "java.lang.ClassCastException",
            "System.InvalidOperationException", "java.lang.IllegalStateException",
            "System.ArgumentException", "java.lang.IllegalArgumentException",
            "System.FormatException", "vsharp.runtime.VsFormatException");

    private CorelibCarriers() {
        throw new AssertionError("No instances");
    }

    /// The corelib names that are C# *classes*, written as `struct` only because V# has no
    /// `class` keyword to spell them with.
    ///
    /// Reference-ness is observable: it decides whether `x == null` is a legal comparison,
    /// whether `null` is assignable, and how a `catch` variable behaves. `System.String` is
    /// absent because the keyword `string` already carries that decision.
    public static Set<String> referenceTypeNames() {
        Set<String> names = new java.util.LinkedHashSet<>(JAVA_CLASSES.keySet());
        names.add("System.Object");
        return Set.copyOf(names);
    }

    /// The binary name of the JVM class carrying a corelib type, if it has one.
    public static Optional<String> javaClassName(String qualifiedName) {
        return Optional.ofNullable(JAVA_CLASSES.get(qualifiedName));
    }

    /// Whether a corelib type is emitted as a real class, and so can be thrown, caught and
    /// tested.
    public static boolean hasRuntimeClass(String qualifiedName) {
        return JAVA_CLASSES.containsKey(qualifiedName);
    }
}
