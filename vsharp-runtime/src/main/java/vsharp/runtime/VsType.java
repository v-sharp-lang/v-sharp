package vsharp.runtime;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/// Runtime identity token for a source-level V# type.
///
/// JVM class literals cannot represent all C# type identities faithfully: `int` and `uint`
/// both use the JVM `int` carrier, nullable values erase to `Object`, and tuple element names
/// erase entirely. `typeof(T)` therefore loads one interned token keyed by T's canonical C#
/// identity. Interning is required because the current `object` equality operator is reference
/// equality, matching `System.Type`'s identity semantics without giving V# a user object model.
public final class VsType {

    private static final ConcurrentMap<String, VsType> INTERNED = new ConcurrentHashMap<>();

    private final String displayName;

    private VsType(String displayName) {
        this.displayName = displayName;
    }

    /// Returns the process-wide identity token for a canonical source type name.
    public static VsType of(String displayName) {
        Objects.requireNonNull(displayName, "displayName");
        if (displayName.isEmpty()) {
            throw new IllegalArgumentException("type display name must not be empty");
        }
        return INTERNED.computeIfAbsent(displayName, VsType::new);
    }

    /// Returns the canonical C#-facing spelling used to create this token.
    public String displayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
