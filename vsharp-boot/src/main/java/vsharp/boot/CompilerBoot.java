package vsharp.boot;

/// The `vsharp` command's process entry point.
public final class CompilerBoot {

    private CompilerBoot() {
        throw new AssertionError("No instances");
    }

    /// Checks the JVM, then starts the compiler driver.
    ///
    /// @param args the command line
    public static void main(String[] args) {
        Boot.run("vsharp.cli.Main", args);
    }
}
