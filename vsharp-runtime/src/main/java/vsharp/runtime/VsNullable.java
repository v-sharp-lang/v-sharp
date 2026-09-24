package vsharp.runtime;

/// Runtime checks shared by compiler-generated nullable-value access.
///
/// A nullable value has no wrapper of its own: `null` denotes no value and a present
/// value is its boxed underlying value. This helper centralises the one operation that cannot
/// be a plain JVM opcode, `Nullable<T>.Value`'s required failure on the no-value state.
public final class VsNullable {

    private VsNullable() {
        throw new AssertionError("No instances");
    }

    public static Object requireValue(Object value) {
        if (value == null) {
            throw new IllegalStateException("Nullable object must have a value.");
        }
        return value;
    }
}
