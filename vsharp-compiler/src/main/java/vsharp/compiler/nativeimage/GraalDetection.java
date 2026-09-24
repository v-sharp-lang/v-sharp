package vsharp.compiler.nativeimage;

import java.util.Objects;

/// The result of looking for a GraalVM installation in the process environment.
///
/// The three cases are deliberately distinct because they demand different behaviour from a
/// driver: [Absent] must leave every previous behaviour untouched, [Invalid] must be reported
/// (an exported variable is an instruction, and silently ignoring it would hide a typo) and
/// only [Present] enables native image generation.
public sealed interface GraalDetection {

    /// No installation was named, so nothing about the build changes.
    ///
    /// @param reason why the variable did not name an installation, for logging only
    record Absent(String reason) implements GraalDetection {

        public Absent {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /// An installation was named but cannot be used, and the exact failed check is carried.
    ///
    /// @param home the value that was named
    /// @param reason the check that failed, phrased for a user who must fix the variable
    record Invalid(String home, String reason) implements GraalDetection {

        public Invalid {
            Objects.requireNonNull(home, "home");
            Objects.requireNonNull(reason, "reason");
        }
    }

    /// A usable installation was named.
    ///
    /// @param toolchain the validated installation
    record Present(GraalToolchain toolchain) implements GraalDetection {

        public Present {
            Objects.requireNonNull(toolchain, "toolchain");
        }
    }
}
