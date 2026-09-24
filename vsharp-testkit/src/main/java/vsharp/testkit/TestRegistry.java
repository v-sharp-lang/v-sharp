package vsharp.testkit;

/// Collects the cases of a single [TestSuite].
public interface TestRegistry {

    /// A test body, allowed to throw so that tests need no checked-exception boilerplate.
    @FunctionalInterface
    interface Body {
        void run() throws Exception;
    }

    /// Registers one case. Names must be unique within a suite.
    void test(String name, Body body);
}
