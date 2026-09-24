package vsharp.testkit;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;

/// Executes every discovered [TestSuite] and reports the outcome.
///
/// Suites run sorted by name and cases run in registration order, so identical inputs
/// produce byte-identical output — a hard requirement for CI diffing. Exit code `0` means
/// all cases passed, `1` means at least one failed, `2` means no case matched the filter.
public final class TestRunner {

    private static final PrintStream OUT = new PrintStream(System.out, true, StandardCharsets.UTF_8);

    private TestRunner() {
        throw new AssertionError("No instances");
    }

    /// Runs the suites.
    ///
    /// @param args optional case-insensitive substring filters matched against
    ///             `suiteName.caseName`; with no arguments every case runs
    public static void main(String[] args) {
        Report report = run(List.of(args));
        report.print(OUT);
        System.exit(report.exitCode());
    }

    /// Runs all discovered suites, applying the given filters.
    public static Report run(List<String> filters) {
        List<Case> cases = collect(filters);
        List<Failure> failures = new ArrayList<>();
        Instant started = Instant.now();
        for (Case testCase : cases) {
            try {
                testCase.body().run();
            } catch (AssertionFailure e) {
                failures.add(new Failure(testCase.displayName(), e, false));
            } catch (Throwable e) {
                failures.add(new Failure(testCase.displayName(), e, true));
            }
        }
        return new Report(cases.size(), List.copyOf(failures), Duration.between(started, Instant.now()));
    }

    /// Discovers suites and flattens them into a deterministic list of cases.
    private static List<Case> collect(List<String> filters) {
        List<TestSuite> suites = new ArrayList<>();
        ServiceLoader.load(TestSuite.class).forEach(suites::add);
        suites.sort(Comparator.comparing(TestSuite::suiteName));

        List<Case> cases = new ArrayList<>();
        for (TestSuite suite : suites) {
            String suiteName = suite.suiteName();
            Set<String> seen = new HashSet<>();
            List<Case> suiteCases = new ArrayList<>();
            suite.register((name, body) -> {
                if (!seen.add(name)) {
                    throw new IllegalStateException(
                            "Duplicate test name '" + name + "' in suite " + suiteName);
                }
                suiteCases.add(new Case(suiteName + "." + name, body));
            });
            for (Case testCase : suiteCases) {
                if (matches(testCase.displayName(), filters)) {
                    cases.add(testCase);
                }
            }
        }
        return List.copyOf(cases);
    }

    private static boolean matches(String displayName, List<String> filters) {
        if (filters.isEmpty()) {
            return true;
        }
        String lower = displayName.toLowerCase(Locale.ROOT);
        return filters.stream().anyMatch(f -> lower.contains(f.toLowerCase(Locale.ROOT)));
    }

    /// One executable case, already qualified with its suite name.
    private record Case(String displayName, TestRegistry.Body body) {}

    /// A failed case. `unexpected` distinguishes a thrown error from a failed assertion.
    public record Failure(String displayName, Throwable error, boolean unexpected) {}

    /// Aggregate outcome of a run.
    ///
    /// @param total    number of cases executed
    /// @param failures the failures, in execution order
    /// @param elapsed  wall-clock duration of the run
    public record Report(int total, List<Failure> failures, Duration elapsed) {

        public Report {
            failures = List.copyOf(failures);
        }

        /// Number of cases that passed.
        public int passed() {
            return total - failures.size();
        }

        /// Process exit code for this outcome.
        public int exitCode() {
            if (total == 0) {
                return 2;
            }
            return failures.isEmpty() ? 0 : 1;
        }

        /// Writes a human- and CI-readable summary.
        public void print(PrintStream out) {
            for (Failure failure : failures) {
                out.println((failure.unexpected() ? "ERROR " : "FAIL  ") + failure.displayName());
                Throwable error = failure.error();
                out.println("      " + error.getClass().getSimpleName() + ": "
                        + Optional.ofNullable(error.getMessage()).orElse("(no message)")
                                .replace(System.lineSeparator(), System.lineSeparator() + "      "));
                if (failure.unexpected()) {
                    for (StackTraceElement frame : trimmed(error)) {
                        out.println("        at " + frame);
                    }
                }
            }
            if (total == 0) {
                out.println("No tests matched the filter.");
                return;
            }
            out.println("%d tests, %d passed, %d failed in %d ms"
                    .formatted(total, passed(), failures.size(), elapsed.toMillis()));
        }

        /// Keeps the frames above the harness itself, which are the ones that matter.
        private static List<StackTraceElement> trimmed(Throwable error) {
            StackTraceElement[] frames = error.getStackTrace();
            List<StackTraceElement> kept = new ArrayList<>();
            for (StackTraceElement frame : frames) {
                if (frame.getClassName().startsWith("vsharp.testkit.TestRunner")) {
                    break;
                }
                kept.add(frame);
                if (kept.size() == 12) {
                    break;
                }
            }
            return List.copyOf(kept);
        }
    }
}
