package vsharp.tests;

import vsharp.testkit.TestRunner;

/// Entry point of the V# test suite.
///
/// Delegates to the testkit runner. It exists so the runner can be launched as a module
/// main class from the module that actually provides the suites, which keeps the service
/// providers inside the resolved module graph.
public final class TestMain {

    private TestMain() {
        throw new AssertionError("No instances");
    }

    /// Runs every suite, optionally filtered by the given case-insensitive substrings.
    public static void main(String[] args) {
        TestRunner.main(args);
    }
}
