package vsharp.cli;

import java.io.Console;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/// Process entry point for the `vsharp` command.
public final class Main {

    private Main() {
        throw new AssertionError("No instances");
    }

    /// Executes the command and terminates with its stable exit code.
    public static void main(String[] args) {
        Charset charset = outputCharset();
        System.exit(Cli.run(args, writer(System.out, charset), writer(System.err, charset),
                System.getenv()));
    }

    /// The encoding diagnostics are written in.
    ///
    /// Redirected output - a file, a pipe, a Gradle build, a test harness - is always UTF-8,
    /// so a captured diagnostic is byte-identical on every host and a V# identifier outside
    /// ASCII survives the capture.
    ///
    /// An interactive terminal gets the encoding the terminal itself declared. This exists
    /// for Windows, whose console is a code page rather than a byte-transparent stream: UTF-8
    /// bytes written to a console running the legacy code page are rendered as mojibake, and a
    /// diagnostic that cannot be read is a diagnostic that was not delivered. On a host whose
    /// terminal declares UTF-8 - the ordinary Linux, FreeBSD and OpenBSD case - this chooses
    /// UTF-8 and changes nothing.
    ///
    /// @return the console's charset when this process is attached to a terminal, UTF-8
    ///         otherwise
    private static Charset outputCharset() {
        Console console = System.console();
        boolean terminal = console != null && console.isTerminal();
        return outputCharset(terminal, terminal ? console.charset() : null);
    }

    /// The rule itself, separated from the console so it can be asserted directly.
    ///
    /// @param terminal whether this process writes to a terminal rather than to a file or pipe
    /// @param declared the charset that terminal declared, which may be `null`
    /// @return the encoding to write diagnostics in
    public static Charset outputCharset(boolean terminal, Charset declared) {
        if (!terminal || declared == null) {
            return StandardCharsets.UTF_8;
        }
        return declared;
    }

    private static PrintWriter writer(OutputStream stream, Charset charset) {
        return new PrintWriter(new OutputStreamWriter(stream, charset), true);
    }
}
