package vsharp.testkit;

/// A named group of test cases, contributed as a `ServiceLoader` service.
///
/// Implementations register their cases eagerly in [#register(TestRegistry)]; the runner
/// then executes them in registration order, which keeps output stable across runs.
@FunctionalInterface
public interface TestSuite {

    /// Registers every case in this suite.
    void register(TestRegistry registry);

    /// The suite's display name. Defaults to the implementing class's simple name.
    default String suiteName() {
        return getClass().getSimpleName();
    }
}
