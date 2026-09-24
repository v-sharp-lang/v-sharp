/// The V# language server.
///
/// Speaks LSP over stdio against the same front end the CLI runs, so the editor and the
/// command line can never disagree about a diagnostic. Only the entry point is exported;
/// the JSON, protocol and server packages are implementation detail.
module vsharp.lsp {
    requires vsharp.compiler;

    exports vsharp.lsp;
}
