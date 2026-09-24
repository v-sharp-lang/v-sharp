/// Minimal, dependency-free test harness for the V# project.
///
/// External test frameworks are forbidden by the project's dependency rule, so suites are
/// plain [vsharp.testkit.TestSuite] services discovered with `ServiceLoader` and executed
/// by [vsharp.testkit.TestRunner] in a deterministic order.
module vsharp.testkit {
    exports vsharp.testkit;

    uses vsharp.testkit.TestSuite;
}
