package vsharp.runtime;

/// Runtime carrier for a C# value tuple of arity 8 (`System.ValueTuple`).
///
/// Element names in V# source are compile-time only, exactly as in C#, so they leave no
/// trace here. Structural equality and hashing come from the record contract, matching
/// `ValueTuple`'s value semantics.
///
/// @param item1 element 1 of the tuple
/// @param item2 element 2 of the tuple
/// @param item3 element 3 of the tuple
/// @param item4 element 4 of the tuple
/// @param item5 element 5 of the tuple
/// @param item6 element 6 of the tuple
/// @param item7 element 7 of the tuple
/// @param item8 element 8 of the tuple
public record VsTuple8<T1, T2, T3, T4, T5, T6, T7, T8>(T1 item1, T2 item2, T3 item3, T4 item4, T5 item5, T6 item6, T7 item7, T8 item8) {

    @Override
    public String toString() {
        return "(" + VsFormat.toDisplayString(item1)
                + ", " + VsFormat.toDisplayString(item2)
                + ", " + VsFormat.toDisplayString(item3)
                + ", " + VsFormat.toDisplayString(item4)
                + ", " + VsFormat.toDisplayString(item5)
                + ", " + VsFormat.toDisplayString(item6)
                + ", " + VsFormat.toDisplayString(item7)
                + ", " + VsFormat.toDisplayString(item8) + ")";
    }
}
