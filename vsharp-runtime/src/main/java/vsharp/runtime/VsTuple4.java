package vsharp.runtime;

/// Runtime carrier for a C# value tuple of arity 4 (`System.ValueTuple`).
///
/// Element names in V# source are compile-time only, exactly as in C#, so they leave no
/// trace here. Structural equality and hashing come from the record contract, matching
/// `ValueTuple`'s value semantics.
///
/// @param item1 element 1 of the tuple
/// @param item2 element 2 of the tuple
/// @param item3 element 3 of the tuple
/// @param item4 element 4 of the tuple
public record VsTuple4<T1, T2, T3, T4>(T1 item1, T2 item2, T3 item3, T4 item4) {

    @Override
    public String toString() {
        return "(" + VsFormat.toDisplayString(item1)
                + ", " + VsFormat.toDisplayString(item2)
                + ", " + VsFormat.toDisplayString(item3)
                + ", " + VsFormat.toDisplayString(item4) + ")";
    }
}
