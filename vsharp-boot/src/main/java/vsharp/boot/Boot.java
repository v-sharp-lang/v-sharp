package vsharp.boot;

import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/// The first class a V# launcher runs, and the only one an older JVM can load.
///
/// Everything else in this toolchain is compiled for Java 25 and is class file version 69, so
/// a JVM older than that refuses it at class loading - before any V# code runs, and therefore
/// before anything can explain what is wrong. On a host where the installed JDK is not the one
/// the launcher found, that surfaces as `UnsupportedClassVersionError` naming a class file
/// version number, which says nothing about what to install or which JVM was actually used.
///
/// This class is compiled for Java 11 and reaches the real entry point by reflection, so it
/// loads on any JVM a user is likely to have, checks the version itself, and either hands over
/// or explains. It is the whole of V#'s platform preflight: the toolchain is pure Java with no
/// native code, so the JDK is the only host requirement there is to check.
public final class Boot {

    /// The feature version every other module is compiled for.
    public static final int REQUIRED_FEATURE = 25;

    private static final int USAGE_EXIT = 2;

    private Boot() {
        throw new AssertionError("No instances");
    }

    /// Runs `entryPoint` when this JVM is new enough, and explains when it is not.
    ///
    /// @param entryPoint the binary name of the class whose `main` is the real entry point
    /// @param arguments the command line, passed through untouched
    public static void run(String entryPoint, String[] arguments) {
        int feature = runningFeature();
        if (feature < REQUIRED_FEATURE) {
            System.err.print(message(feature, System.getProperty("java.home"),
                    System.getProperty("os.name")));
            System.err.flush();
            System.exit(USAGE_EXIT);
            return;
        }
        invoke(entryPoint, arguments);
    }

    /// The feature version of the running JVM.
    ///
    /// `Runtime.version()` exists from Java 9 and is the exact answer; the property is the
    /// fallback for anything older, where the spelling is `1.8` and the feature is 8.
    ///
    /// @return the feature version, or 0 when neither source can be read
    public static int runningFeature() {
        try {
            return Runtime.version().feature();
        } catch (NoSuchMethodError | RuntimeException ignored) {
            return featureOf(System.getProperty("java.specification.version"));
        }
    }

    /// Parses a `java.specification.version` value.
    ///
    /// @param value the property value, which may be `null`, `21`, or the legacy `1.8`
    /// @return the feature version, or 0 when the value cannot be read
    public static int featureOf(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        String text = value.startsWith("1.") ? value.substring(2) : value;
        int end = 0;
        while (end < text.length() && Character.isDigit(text.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return 0;
        }
        try {
            return Integer.parseInt(text.substring(0, end));
        } catch (NumberFormatException failure) {
            return 0;
        }
    }

    /// The diagnostic an unusable JVM produces.
    ///
    /// It names three things a user needs and none of which the class loader's own error
    /// carries: the version that is running, *where* it came from - the usual cause is a
    /// `JAVA_HOME` pointing at a different JDK than the one on `PATH` - and the command that
    /// installs a usable one on this host.
    ///
    /// @param feature the running feature version, or 0 when it could not be read
    /// @param javaHome the running JVM's home, which may be `null`
    /// @param osName the `os.name` value, which may be `null`
    /// @return the complete message, newline terminated
    public static String message(int feature, String javaHome, String osName) {
        StringBuilder text = new StringBuilder();
        text.append("vsharp: V# requires Java ").append(REQUIRED_FEATURE)
                .append(" or later; this launcher is running Java ")
                .append(feature <= 0 ? "of an unknown version" : Integer.toString(feature))
                .append('\n');
        if (javaHome != null && !javaHome.isEmpty()) {
            text.append("  the JVM in use is ").append(javaHome).append('\n');
            text.append("  set JAVA_HOME to a Java ").append(REQUIRED_FEATURE)
                    .append(" installation, or put its bin directory first on PATH\n");
        }
        text.append("  install one with: ").append(installHint(osName)).append('\n');
        return text.toString();
    }

    /// The install command for a host, or the general instruction when it is not one of the
    /// four the toolchain is documented for.
    ///
    /// @param osName the `os.name` value, which may be `null`
    /// @return a single-line hint
    public static String installHint(String osName) {
        String name = osName == null ? "" : osName.toLowerCase(java.util.Locale.ROOT);
        if (name.startsWith("windows")) {
            return "winget install Microsoft.OpenJDK.25"
                    + "  (or unpack any JDK 25 and set JAVA_HOME)";
        }
        if (name.contains("freebsd")) {
            return "pkg install openjdk25";
        }
        if (name.contains("openbsd")) {
            return "pkg_add jdk  (choose a 25 flavour; OpenBSD ports may still ship 21, in "
                    + "which case use a JDK 25 build for the host)";
        }
        if (name.contains("linux")) {
            return "apt install openjdk-25-jdk, dnf install java-25-openjdk, "
                    + "apk add openjdk25, or unpack any JDK 25 build";
        }
        if (name.contains("mac") || name.contains("darwin")) {
            return "brew install openjdk@25"
                    + "  (or unpack any JDK 25 and set JAVA_HOME)";
        }
        return "install a JDK " + REQUIRED_FEATURE + " for this host and set JAVA_HOME";
    }

    private static void invoke(String entryPoint, String[] arguments) {
        try {
            Method main = Class.forName(entryPoint).getMethod("main", String[].class);
            main.invoke(null, (Object) arguments);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new IllegalStateException("V# entry point failed: " + entryPoint, cause);
        } catch (ReflectiveOperationException failure) {
            PrintStream error = System.err;
            error.println("vsharp: the V# entry point " + entryPoint
                    + " could not be started: " + failure);
            error.println("  this installation is incomplete; reinstall the distribution");
            error.flush();
            System.exit(USAGE_EXIT);
        }
    }
}
