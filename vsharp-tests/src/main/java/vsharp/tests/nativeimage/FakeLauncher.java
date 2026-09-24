package vsharp.tests.nativeimage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/// A stand-in for GraalVM's `native-image`, written in Java rather than in a shell.
///
/// These cases need a launcher that is a real, runnable program: the compiler starts it with
/// `ProcessBuilder`, streams its output and reads its exit code, and none of that can be
/// faked at a seam. A shell script is the obvious way to write one and is what this used to
/// be - but a `#!/bin/sh` script is unrunnable on Windows, which made the whole native image
/// suite POSIX-only. Writing the same argument parsing twice, once in `sh` and once in
/// `cmd`, would mean shipping batch code that no machine here can execute.
///
/// So the behaviour lives here, in Java, tested by every run of the suite on any host, and
/// the installed launcher is a two-line script whose only job is to start this class. That
/// leaves exactly one line per platform that differs.
public final class FakeLauncher {

    /// Records its arguments, writes the requested image, and succeeds.
    public static final String RECORD = "record";

    /// Behaves like a Windows launcher: writes the image with an `.exe` suffix.
    public static final String SUFFIX = "suffix";

    /// Behaves like a broken installation: explains and exits non-zero.
    public static final String FAIL = "fail";

    /// The exit code the failing mode reports, chosen to be recognisable in an assertion.
    public static final int FAILURE_EXIT = 9;

    private FakeLauncher() {
        throw new AssertionError("No instances");
    }

    /// Runs one fake build.
    ///
    /// @param args the mode, the file to record launcher arguments in, then the arguments
    ///     the compiler passed to the launcher
    /// @throws IOException when the recording or the image cannot be written
    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            throw new IllegalArgumentException("usage: FakeLauncher <mode> <record> [args...]");
        }
        String mode = args[0];
        Path record = Path.of(args[1]);
        List<String> launcherArguments = new ArrayList<>(
                List.of(args).subList(2, args.length));

        if (FAIL.equals(mode)) {
            System.out.println("no toolchain here");
            System.out.flush();
            System.exit(FAILURE_EXIT);
            return;
        }

        List<String> lines = new ArrayList<>(launcherArguments);
        lines.add("");
        Files.writeString(record, String.join("\n", lines), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        Path image = imageOf(launcherArguments);
        if (image == null) {
            throw new IllegalArgumentException("no -o argument in " + launcherArguments);
        }
        Path produced = SUFFIX.equals(mode)
                ? image.resolveSibling(image.getFileName() + ".exe")
                : image;
        Path parent = produced.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(produced, "#!/bin/sh\n", StandardCharsets.UTF_8);
        if (produced.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            produced.toFile().setExecutable(true, false);
        }
        System.out.println(SUFFIX.equals(mode)
                ? "fake windows native image build" : "fake native image build");
        System.out.flush();
    }

    private static Path imageOf(List<String> arguments) {
        for (int index = 0; index + 1 < arguments.size(); index++) {
            if ("-o".equals(arguments.get(index))) {
                return Path.of(arguments.get(index + 1));
            }
        }
        return null;
    }
}
