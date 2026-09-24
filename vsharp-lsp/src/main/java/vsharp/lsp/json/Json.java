package vsharp.lsp.json;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SequencedMap;

/// An immutable JSON value.
///
/// The language server protocol is JSON-RPC, and the absolute dependency rule forbids
/// Jackson and Gson alike, so the wire format is modelled here as an algebraic type: a
/// sealed interface over records, consumed by pattern switch rather than by casting.
/// Objects preserve insertion order ([SequencedMap]) so that two runs over identical
/// input produce byte-identical payloads, which is what makes the protocol tests
/// reproducible.
///
/// Parsing lives in [JsonParser] and rendering in [JsonWriter]; this type only carries
/// values and the small accessor vocabulary the server dispatch needs.
public sealed interface Json {

    /// JSON `null`. A singleton because the value carries nothing.
    record Null() implements Json {

        /// The only instance.
        public static final Null INSTANCE = new Null();
    }

    /// JSON `true` / `false`.
    record Bool(boolean value) implements Json {

        /// Shared `true`.
        public static final Bool TRUE = new Bool(true);

        /// Shared `false`.
        public static final Bool FALSE = new Bool(false);
    }

    /// A JSON number.
    ///
    /// JSON has one numeric type, so the value is carried as a `double` and rendered back
    /// without a fractional part when it is integral. That keeps request identifiers -
    /// which LSP clients send as integers and compare by value - round-tripping as the
    /// integers they were sent as.
    record Num(double value) implements Json {

        /// Wraps an `int`, the only numeric width the protocol actually uses.
        public static Num of(int value) {
            return new Num(value);
        }
    }

    /// A JSON string.
    record Str(String value) implements Json {

        public Str {
            Objects.requireNonNull(value, "value");
        }
    }

    /// A JSON array.
    record Arr(List<Json> items) implements Json {

        public Arr {
            items = List.copyOf(Objects.requireNonNull(items, "items"));
        }

        /// An array of the given elements.
        public static Arr of(List<Json> items) {
            return new Arr(items);
        }

        /// The empty array.
        public static Arr empty() {
            return new Arr(List.of());
        }
    }

    /// A JSON object with deterministic member order.
    record Obj(SequencedMap<String, Json> members) implements Json {

        public Obj {
            Objects.requireNonNull(members, "members");
            members = java.util.Collections.unmodifiableSequencedMap(
                    new LinkedHashMap<>(members));
        }

        /// Starts a builder for an object literal.
        public static Builder builder() {
            return new Builder();
        }

        /// The empty object.
        public static Obj empty() {
            return new Obj(new LinkedHashMap<>());
        }

        /// The member named `key`, if present and not JSON `null`.
        public Optional<Json> get(String key) {
            Json value = members.get(key);
            return value == null || value instanceof Null ? Optional.empty() : Optional.of(value);
        }

        /// The member named `key` when it is a string.
        public Optional<String> string(String key) {
            return get(key).flatMap(v -> v instanceof Str s ? Optional.of(s.value())
                    : Optional.empty());
        }

        /// The member named `key` when it is a number, truncated to `int`.
        public Optional<Integer> integer(String key) {
            return get(key).flatMap(v -> v instanceof Num n ? Optional.of((int) n.value())
                    : Optional.empty());
        }

        /// The member named `key` when it is a boolean.
        public Optional<Boolean> bool(String key) {
            return get(key).flatMap(v -> v instanceof Bool b ? Optional.of(b.value())
                    : Optional.empty());
        }

        /// The member named `key` when it is an object.
        public Optional<Obj> object(String key) {
            return get(key).flatMap(v -> v instanceof Obj o ? Optional.of(o) : Optional.empty());
        }

        /// The member named `key` when it is an array; an empty list when it is absent.
        public List<Json> array(String key) {
            return get(key).map(v -> v instanceof Arr a ? a.items() : List.<Json>of())
                    .orElseGet(List::of);
        }

        /// Accumulates object members in insertion order.
        ///
        /// `put` overloads skip `null` arguments rather than writing JSON `null`, because
        /// LSP treats an absent member and an explicit null differently in several
        /// requests and the server should never send the second by accident.
        public static final class Builder {

            private final SequencedMap<String, Json> members = new LinkedHashMap<>();

            private Builder() {
            }

            /// Adds a member.
            public Builder put(String key, Json value) {
                if (value != null) {
                    members.put(key, value);
                }
                return this;
            }

            /// Adds a string member, skipped when `value` is `null`.
            public Builder put(String key, String value) {
                return value == null ? this : put(key, new Str(value));
            }

            /// Adds a numeric member.
            public Builder put(String key, int value) {
                return put(key, Num.of(value));
            }

            /// Adds a boolean member.
            public Builder put(String key, boolean value) {
                return put(key, value ? Bool.TRUE : Bool.FALSE);
            }

            /// Adds an array member.
            public Builder put(String key, List<Json> items) {
                return items == null ? this : put(key, Arr.of(items));
            }

            /// Builds the object.
            public Obj build() {
                return new Obj(members);
            }
        }
    }

    /// Renders this value as compact JSON.
    default String text() {
        return JsonWriter.write(this);
    }

    /// Parses `text` as a single JSON value.
    ///
    /// @throws JsonException if `text` is not one well-formed JSON value
    static Json parse(String text) {
        return JsonParser.parse(text);
    }

    /// Wraps a string, or yields JSON `null` for a `null` argument.
    static Json of(String value) {
        return value == null ? Null.INSTANCE : new Str(value);
    }

    /// Builds an object from alternating key/value pairs.
    static Obj.Builder object() {
        return Obj.builder();
    }

    /// Wraps a list of values.
    static Arr array(List<Json> items) {
        return Arr.of(items);
    }

    /// Reads this value as a `Map` view when it is an object, otherwise an empty map.
    default Map<String, Json> asObject() {
        return this instanceof Obj o ? o.members() : Map.of();
    }
}
