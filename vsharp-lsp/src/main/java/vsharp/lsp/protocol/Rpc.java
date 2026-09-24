package vsharp.lsp.protocol;

import java.util.Objects;
import java.util.Optional;
import vsharp.lsp.json.Json;

/// JSON-RPC 2.0 message shapes, as far as LSP uses them.
///
/// Classifying an incoming payload once - into a request that owes a response, or a
/// notification that does not - lets the dispatcher pattern switch over a closed set
/// instead of re-inspecting members at every branch, and makes "forgot to answer a
/// request" a shape error rather than a silent hang in the editor.
public sealed interface Rpc {

    /// Standard JSON-RPC and LSP error codes.
    ///
    /// Only the codes this server can actually produce are listed; inventing the rest
    /// would be documentation of behaviour that does not exist.
    final class ErrorCodes {

        /// The payload was not well-formed JSON.
        public static final int PARSE_ERROR = -32700;

        /// The payload was JSON but not a valid request object.
        public static final int INVALID_REQUEST = -32600;

        /// No handler is registered for the method.
        public static final int METHOD_NOT_FOUND = -32601;

        /// A handler threw. The message carries the exception's description.
        public static final int INTERNAL_ERROR = -32603;

        /// A request arrived after `shutdown`.
        public static final int INVALID_STATE = -32002;

        private ErrorCodes() {
            throw new AssertionError("No instances");
        }
    }

    /// The method name being invoked.
    String method();

    /// The `params` member, which LSP always sends as an object for the methods handled here.
    Json.Obj params();

    /// A call that owes exactly one response carrying `id`.
    record Request(Json id, String method, Json.Obj params) implements Rpc {

        public Request {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(params, "params");
        }
    }

    /// A one-way message; answering it is a protocol violation.
    record Notification(String method, Json.Obj params) implements Rpc {

        public Notification {
            Objects.requireNonNull(method, "method");
            Objects.requireNonNull(params, "params");
        }
    }

    /// Classifies a decoded payload.
    ///
    /// Returns empty for anything that is not a call the server should act on - a response
    /// to a server-initiated request, or a batch array - so the dispatcher never has to
    /// guess what an unrecognised shape meant.
    static Optional<Rpc> classify(Json message) {
        if (!(message instanceof Json.Obj obj)) {
            return Optional.empty();
        }
        Optional<String> method = obj.string("method");
        if (method.isEmpty()) {
            return Optional.empty();
        }
        Json.Obj params = obj.object("params").orElseGet(Json.Obj::empty);
        Optional<Json> id = obj.get("id");
        return Optional.of(id.<Rpc>map(value -> new Request(value, method.get(), params))
                .orElseGet(() -> new Notification(method.get(), params)));
    }

    /// Builds a successful response. `result` may be JSON `null`, which LSP requires for
    /// `shutdown` and for requests with no data to return.
    static Json.Obj result(Json id, Json result) {
        return Json.object()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("result", result == null ? Json.Null.INSTANCE : result)
                .build();
    }

    /// Builds an error response.
    static Json.Obj error(Json id, int code, String message) {
        return Json.object()
                .put("jsonrpc", "2.0")
                .put("id", id == null ? Json.Null.INSTANCE : id)
                .put("error", Json.object().put("code", code).put("message", message).build())
                .build();
    }

    /// Builds a notification the server sends to the client.
    static Json.Obj notification(String method, Json.Obj params) {
        return Json.object()
                .put("jsonrpc", "2.0")
                .put("method", method)
                .put("params", params)
                .build();
    }
}
