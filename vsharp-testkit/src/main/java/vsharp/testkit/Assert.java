package vsharp.testkit;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/// Assertion helpers for [TestSuite] bodies.
///
/// Failure messages always render both expected and actual values, because a compiler test
/// that fails with only "assertion failed" costs more time than it saves.
public final class Assert {

    private Assert() {
        throw new AssertionError("No instances");
    }

    /// Asserts that `condition` holds.
    public static void isTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionFailure(message);
        }
    }

    /// Asserts that `condition` does not hold.
    public static void isFalse(boolean condition, String message) {
        isTrue(!condition, message);
    }

    /// Asserts reference-or-value equality using [Objects#equals].
    public static void equal(Object expected, Object actual, String what) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionFailure(
                    what + System.lineSeparator()
                            + "  expected: " + render(expected) + System.lineSeparator()
                            + "  actual:   " + render(actual));
        }
    }

    /// Asserts equality of `long` values, avoiding boxing surprises in numeric tests.
    public static void equal(long expected, long actual, String what) {
        if (expected != actual) {
            throw new AssertionFailure(what + ": expected " + expected + " but was " + actual);
        }
    }

    /// Asserts that two lists are equal element-wise, reporting the first difference.
    public static void equalList(List<?> expected, List<?> actual, String what) {
        int limit = Math.min(expected.size(), actual.size());
        for (int i = 0; i < limit; i++) {
            if (!Objects.equals(expected.get(i), actual.get(i))) {
                throw new AssertionFailure(
                        what + ": first difference at index " + i + System.lineSeparator()
                                + "  expected: " + render(expected.get(i)) + System.lineSeparator()
                                + "  actual:   " + render(actual.get(i)) + System.lineSeparator()
                                + "  expected list: " + expected + System.lineSeparator()
                                + "  actual list:   " + actual);
            }
        }
        if (expected.size() != actual.size()) {
            throw new AssertionFailure(
                    what + ": expected " + expected.size() + " elements but got " + actual.size()
                            + System.lineSeparator() + "  expected list: " + expected
                            + System.lineSeparator() + "  actual list:   " + actual);
        }
    }

    /// Asserts that `actual` is non-null.
    public static void notNull(Object actual, String what) {
        if (actual == null) {
            throw new AssertionFailure(what + ": expected a non-null value");
        }
    }

    /// Asserts that `actual` contains `needle`, quoting both on failure.
    public static void contains(String actual, String needle, String what) {
        notNull(actual, what);
        if (!actual.contains(needle)) {
            throw new AssertionFailure(
                    what + ": expected to contain " + render(needle) + System.lineSeparator()
                            + "  actual: " + render(actual));
        }
    }

    /// Asserts that `body` throws `expected`, returning the exception for further checks.
    public static <T extends Throwable> T throwsException(
            Class<T> expected, TestRegistry.Body body, String what) {
        try {
            body.run();
        } catch (Throwable actual) {
            if (expected.isInstance(actual)) {
                return expected.cast(actual);
            }
            throw new AssertionFailure(
                    what + ": expected " + expected.getSimpleName() + " but got "
                            + actual.getClass().getName(), actual);
        }
        throw new AssertionFailure(
                what + ": expected " + expected.getSimpleName() + " but nothing was thrown");
    }

    /// Fails unconditionally with a lazily built message.
    public static AssertionFailure fail(Supplier<String> message) {
        throw new AssertionFailure(message.get());
    }

    /// Renders a value unambiguously: strings are quoted, arrays are expanded.
    private static String render(Object value) {
        return switch (value) {
            case null -> "null";
            case String s -> "\"" + s.replace("\n", "\\n").replace("\t", "\\t") + "\"";
            case Object[] a -> Arrays.deepToString(a);
            case int[] a -> Arrays.toString(a);
            case long[] a -> Arrays.toString(a);
            case char[] a -> Arrays.toString(a);
            case double[] a -> Arrays.toString(a);
            default -> value.toString();
        };
    }
}
