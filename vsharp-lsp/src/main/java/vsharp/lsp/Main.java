package vsharp.lsp;

import java.io.IOException;
import vsharp.lsp.server.Server;

/// Entry point for the V# language server.
///
/// The server speaks LSP over stdin and stdout, which is how every editor launches one.
/// Nothing else may write to `System.out` in this process - a stray `println` would be
/// framed as a protocol message and desynchronise the client permanently - so diagnostics
/// about the server itself go through `System.Logger`, which defaults to `System.err`.
public final class Main {

    private Main() {
        throw new AssertionError("No instances");
    }

    /// Runs the server until the client disconnects.
    ///
    /// Exit code 0 on a clean shutdown, 1 when the transport failed, matching the
    /// convention the CLI already uses so supervisors can treat both the same way.
    public static void main(String[] args) {
        try (Server server = new Server(System.in, System.out)) {
            server.run();
        } catch (IOException e) {
            System.Logger log = System.getLogger(Main.class.getName());
            log.log(System.Logger.Level.ERROR, "Language server transport failed", e);
            System.exit(1);
        }
    }
}
