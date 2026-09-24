package vsharp.compiler.semantics.constants;

import java.util.Objects;

/// The constant value a field carries, written exactly once.
///
/// A `const` field's value is a *folded bound expression* (C# §12.23), but the field's symbol
/// has to exist before any expression can be bound: `const int Doubled = Base * 2;` cannot
/// resolve `Base` until `Base` is already in the declaration space. So the symbol is created
/// first, with an unresolved slot, and the value arrives from `ConstantFieldBinder` once every
/// declaration exists and the initializer can be bound and folded for real.
///
/// Before this slot existed, declaration binding folded `const` initializers with a private
/// literal-only folder over *syntax*, because the real evaluator needs a bound expression that
/// did not exist yet. That second folder answered `null` for `int.MaxValue`, for a read of
/// another constant, and for division by a constant zero, and every one of them was reported
/// as "a constant initializer this build cannot fold" instead of its own diagnostic. The slot
/// is what removes the need for a second folder at all.
///
/// Everything else about a symbol stays immutable. This is the one deferred cell, it admits
/// exactly one write, and that write happens before expression binding runs - so every
/// consumer downstream of the constant pass observes a frozen value. A second write is a
/// compiler defect rather than a source error, so it throws rather than silently winning.
///
/// The slot is written by a single pass on one thread, which is why it needs no memory
/// barrier: the compilation's phases are sequenced by [vsharp.compiler.api.Compilation], and
/// no phase reads a slot the constant pass has not already finished with.
public final class ConstantSlot {

    private ConstantValue value;
    private boolean resolved;

    private ConstantSlot(ConstantValue value, boolean resolved) {
        this.value = value;
        this.resolved = resolved;
    }

    /// A slot whose answer is already known. `value` is `null` for a field that is not a
    /// constant at all, which is every ordinary field and every discovered Java field.
    public static ConstantSlot resolved(ConstantValue value) {
        return new ConstantSlot(value, true);
    }

    /// A slot for a `const` whose initializer has not been folded yet.
    public static ConstantSlot unresolved() {
        return new ConstantSlot(null, false);
    }

    /// The folded value, or `null` when the field has none - either because it is not a
    /// constant, because its initializer could not be folded (always reported where it is
    /// written), or because nothing has folded it yet.
    public ConstantValue value() {
        return value;
    }

    /// Whether the fold has already been attempted, successfully or not. A resolved slot is
    /// never revisited, which is also what stops a cyclic constant from being folded twice.
    public boolean isResolved() {
        return resolved;
    }

    /// Records the fold's answer, `null` when it produced no value.
    public void resolve(ConstantValue folded) {
        if (resolved) {
            throw new IllegalStateException("constant value already resolved");
        }
        this.value = folded;
        this.resolved = true;
    }

    /// Equal when the values are, which keeps [vsharp.compiler.semantics.symbols.FieldSymbol]
    /// equality meaning exactly what it meant when the value was a plain record component.
    @Override
    public boolean equals(Object other) {
        return other instanceof ConstantSlot slot && Objects.equals(value, slot.value);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(value);
    }

    @Override
    public String toString() {
        return resolved ? String.valueOf(value) : "<unresolved>";
    }
}
