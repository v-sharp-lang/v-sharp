package vsharp.compiler.nativeimage;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/// What a native image build did.
///
/// A failure is a value rather than an exception because native image generation is an optional
/// stage layered on a compilation that already succeeded: the driver decides what a failed image
/// means for its exit code, and never loses the classes it already wrote.
public sealed interface NativeImageOutcome {

    /// The executable was produced.
    ///
    /// @param image the executable that now exists
    /// @param duration how long the build took
    record Succeeded(Path image, Duration duration) implements NativeImageOutcome {

        public Succeeded {
            Objects.requireNonNull(image, "image");
            Objects.requireNonNull(duration, "duration");
        }
    }

    /// The launcher ran and refused, or could not be run at all.
    ///
    /// @param exitCode the launcher's exit code, or `-1` when it never started
    /// @param message the reason, already trimmed to what a user needs
    /// @param output the last lines the launcher wrote, oldest first
    record Failed(int exitCode, String message, List<String> output) implements NativeImageOutcome {

        public Failed {
            Objects.requireNonNull(message, "message");
            output = List.copyOf(Objects.requireNonNull(output, "output"));
        }
    }

    /// The build was not attempted, and the compilation's own result stands unchanged.
    ///
    /// @param reason why no image was requested, phrased for a build log
    record Skipped(String reason) implements NativeImageOutcome {

        public Skipped {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
