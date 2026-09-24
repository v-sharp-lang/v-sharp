package vsharp.runtime;

/// JVM carrier for the curated core library's `System.FormatException`.
///
/// Java's superficially similar [NumberFormatException] cannot carry this type: it extends
/// [IllegalArgumentException], while .NET's `FormatException` and `ArgumentException` are
/// siblings. A distinct runtime exception preserves the observable catch hierarchy while
/// remaining unchecked at the Java boundary, as every CLR exception is at a C# call site.
public final class VsFormatException extends RuntimeException {

    public VsFormatException() {
        super();
    }

    public VsFormatException(String message) {
        super(message);
    }
}
