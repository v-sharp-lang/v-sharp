package vsharp.boot;

/// The `vsharp-lsp` command's process entry point.
public final class ServerBoot {

    private ServerBoot() {
        throw new AssertionError("No instances");
    }

    /// Checks the JVM, then starts the language server.
    ///
    /// @param args the command line
    public static void main(String[] args) {
        Boot.run("vsharp.lsp.Main", args);
    }
}
