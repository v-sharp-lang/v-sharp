package vsharp.lsp.server;

import java.util.List;
import vsharp.lsp.json.Json;

/// The server's `initialize` response.
///
/// Only capabilities that are actually implemented are advertised. An editor trusts this
/// document literally: advertising `renameProvider` would make the rename command appear
/// and then fail, which is worse for the user than the command being absent.
final class Capabilities {

    /// Characters after which the editor should request completion without being asked.
    /// `.` opens member lists; the others are where a fresh word starts in C# syntax and
    /// are what make the keyword list appear at the beginning of a statement.
    private static final List<String> TRIGGERS = List.of(".", " ", "(", "[", ",", ":", "<");

    private Capabilities() {
        throw new AssertionError("No instances");
    }

    static Json.Obj initializeResult() {
        return Json.object()
                .put("capabilities", Json.object()
                        // 1 = full sync. The client resends the whole buffer on every
                        // edit; see Server#didChange for why incremental is not offered.
                        .put("textDocumentSync", 1)
                        .put("completionProvider", Json.object()
                                .put("triggerCharacters",
                                        TRIGGERS.stream().map(Json::of).toList())
                                .put("resolveProvider", false)
                                .build())
                        .put("signatureHelpProvider", Json.object()
                                .put("triggerCharacters", List.of(Json.of("("), Json.of(",")))
                                .put("retriggerCharacters", List.of(Json.of(",")))
                                .build())
                        .put("hoverProvider", true)
                        .put("definitionProvider", true)
                        .put("referencesProvider", true)
                        .put("documentSymbolProvider", true)
                        .build())
                .put("serverInfo", Json.object()
                        .put("name", "vsharp-lsp")
                        .put("version", version())
                        .build())
                .build();
    }

    /// The server version, taken from the jar manifest when packaged and reported as
    /// `dev` when running from a class directory, so the value is never invented.
    private static String version() {
        Package self = Capabilities.class.getPackage();
        String implementation = self == null ? null : self.getImplementationVersion();
        return implementation == null ? "dev" : implementation;
    }
}
