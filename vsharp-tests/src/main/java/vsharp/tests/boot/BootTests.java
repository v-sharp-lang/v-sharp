package vsharp.tests.boot;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import vsharp.boot.Boot;
import vsharp.testkit.Assert;
import vsharp.testkit.TestRegistry;
import vsharp.testkit.TestSuite;

/// The launch preflight: the guard that turns an unusable JVM into an instruction.
///
/// Every other module is class file version 69, so a JVM older than 25 refuses the toolchain
/// at class loading and reports a version number. These cases pin the three things that make
/// the guard worth having: that it demands the version the toolchain is actually built for,
/// that it reads a JVM version correctly including the legacy spelling, and that what it
/// prints names the running JVM and a way to fix it.
public final class BootTests implements TestSuite {

    @Override
    public String suiteName() {
        return "boot";
    }

    @Override
    public void register(TestRegistry registry) {
        registry.test("the required version is the one the toolchain is compiled for", () -> {
            // Read from a real compiled class rather than from a constant, so that raising
            // the toolchain's release without raising the guard is a failing test rather
            // than a preflight that waves through a JVM which cannot load the driver.
            int major = majorVersionOf(vsharp.cli.Main.class);
            Assert.equal(Boot.REQUIRED_FEATURE, major - 44,
                    "the guard matches the driver's class file version");
            Assert.isTrue(Boot.runningFeature() >= Boot.REQUIRED_FEATURE,
                    "this JVM satisfies the guard it is running under");
        });

        registry.test("a JVM version is read in both spellings", () -> {
            Assert.equal(25, Boot.featureOf("25"), "a plain feature version");
            Assert.equal(21, Boot.featureOf("21"), "an older feature version");
            Assert.equal(25, Boot.featureOf("25.0.3"), "a full version string");
            Assert.equal(8, Boot.featureOf("1.8"), "the legacy spelling");
            Assert.equal(8, Boot.featureOf("1.8.0_452"), "a legacy update string");
            Assert.equal(0, Boot.featureOf(null), "an absent property");
            Assert.equal(0, Boot.featureOf(""), "an empty property");
            Assert.equal(0, Boot.featureOf("unknown"), "an unreadable property");
        });

        registry.test("the refusal names the version, the JVM and a way to fix it", () -> {
            String message = Boot.message(21, "/opt/jdk21", "Linux");
            Assert.contains(message, "requires Java 25", "the requirement");
            Assert.contains(message, "running Java 21", "what is actually running");
            Assert.contains(message, "/opt/jdk21", "which JVM, because JAVA_HOME is the cause");
            Assert.contains(message, "JAVA_HOME", "the usual remedy");
            Assert.contains(message, "apk add openjdk25", "an install command");

            Assert.contains(Boot.message(0, null, "Linux"), "unknown version",
                    "an unreadable version is still reported");
            Assert.isFalse(Boot.message(0, null, "Linux").contains("the JVM in use"),
                    "nothing is invented when the home is absent");
        });

        registry.test("each supported host is told how to install a JDK", () -> {
            Assert.contains(Boot.installHint("Windows 11"), "winget", "Windows");
            Assert.contains(Boot.installHint("FreeBSD"), "pkg install openjdk25", "FreeBSD");
            Assert.contains(Boot.installHint("OpenBSD"), "pkg_add", "OpenBSD");
            Assert.contains(Boot.installHint("Linux"), "apk add openjdk25", "Alpine and musl");
            Assert.contains(Boot.installHint("Linux"), "apt install", "Debian and Ubuntu");
            Assert.contains(Boot.installHint("Mac OS X"), "brew install openjdk@25", "macOS");
            Assert.contains(Boot.installHint("Darwin"), "brew install openjdk@25",
                    "macOS under its kernel name");
            Assert.contains(Boot.installHint("SunOS"), "set JAVA_HOME",
                    "an unlisted host still gets an instruction");
            Assert.contains(Boot.installHint(null), "set JAVA_HOME", "an absent os.name");
        });
    }

    private static int majorVersionOf(Class<?> type) {
        String resource = type.getSimpleName() + ".class";
        try (InputStream bytes = type.getResourceAsStream(resource)) {
            Assert.notNull(bytes, "class file of " + type.getName());
            byte[] header = bytes.readNBytes(8);
            Assert.equal(8, header.length, "class file header length");
            return ((header[6] & 0xFF) << 8) | (header[7] & 0xFF);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
