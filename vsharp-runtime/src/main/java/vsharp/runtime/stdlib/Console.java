package vsharp.runtime.stdlib;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import vsharp.runtime.VsFormat;

/// The V# console surface, mirroring the subset of `System.Console` that a non-OOP
/// program needs.
///
/// Streams are pinned to UTF-8 rather than inheriting the platform default encoding, so
/// program output is byte-identical across the clusters V# targets. Values are rendered
/// through [VsFormat] so that `Console.WriteLine(true)` prints `True`, as in C#.
public final class Console {

    private static final PrintStream OUT = new PrintStream(System.out, true, StandardCharsets.UTF_8);

    private static final BufferedReader IN =
            new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

    private Console() {
        throw new AssertionError("No instances");
    }

    /// Writes the line terminator only.
    public static void WriteLine() {
        OUT.println();
    }

    /// Reads one line from standard input, or `null` at end of input.
    public static String ReadLine() {
        try {
            return IN.readLine();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read from standard input", e);
        }
    }

    /// Writes a `boolean` value followed by the line terminator.
    public static void WriteLine(boolean value) {
        OUT.println(VsFormat.toDisplayString(value));
    }

    /// Writes a `boolean` value without a line terminator.
    public static void Write(boolean value) {
        OUT.print(VsFormat.toDisplayString(value));
    }

    /// Writes a `char` value followed by the line terminator.
    public static void WriteLine(char value) {
        OUT.println(VsFormat.toDisplayString(value));
    }

    /// Writes a `char` value without a line terminator.
    public static void Write(char value) {
        OUT.print(VsFormat.toDisplayString(value));
    }

    /// Writes a `int` value followed by the line terminator.
    public static void WriteLine(int value) {
        OUT.println(VsFormat.toDisplayString(value));
    }

    /// Writes a `int` value without a line terminator.
    public static void Write(int value) {
        OUT.print(VsFormat.toDisplayString(value));
    }

    /// Writes a `uint` value followed by the line terminator.
    ///
    /// A `uint` and an `int` share the JVM `int` carrier, so the two cannot be told apart
    /// by descriptor. The unsigned members carry the distinction in their *name*, and the
    /// compiler selects them from the V# parameter type rather than from the carrier.
    public static void WriteLineUnsigned(int value) {
        OUT.println(VsFormat.toDisplayStringUnsigned(value));
    }

    /// Writes a `uint` value without a line terminator.
    public static void WriteUnsigned(int value) {
        OUT.print(VsFormat.toDisplayStringUnsigned(value));
    }

    /// Writes a `ulong` value followed by the line terminator.
    public static void WriteLineUnsigned(long value) {
        OUT.println(VsFormat.toDisplayStringUnsigned(value));
    }

    /// Writes a `ulong` value without a line terminator.
    public static void WriteUnsigned(long value) {
        OUT.print(VsFormat.toDisplayStringUnsigned(value));
    }

    /// Writes a `long` value followed by the line terminator.
    public static void WriteLine(long value) {
        OUT.println(VsFormat.toDisplayString(value));
    }

    /// Writes a `long` value without a line terminator.
    public static void Write(long value) {
        OUT.print(VsFormat.toDisplayString(value));
    }

    /// Writes a `float` value followed by the line terminator.
    public static void WriteLine(float value) {
        OUT.println(VsFormat.toDisplayString(value));
    }

    /// Writes a `float` value without a line terminator.
    public static void Write(float value) {
        OUT.print(VsFormat.toDisplayString(value));
    }

    /// Writes a `double` value followed by the line terminator.
    public static void WriteLine(double value) {
        OUT.println(VsFormat.toDisplayString(value));
    }

    /// Writes a `double` value without a line terminator.
    public static void Write(double value) {
        OUT.print(VsFormat.toDisplayString(value));
    }

    /// Writes a `String` value followed by the line terminator.
    public static void WriteLine(String value) {
        OUT.println(VsFormat.toDisplayString(value));
    }

    /// Writes a `String` value without a line terminator.
    public static void Write(String value) {
        OUT.print(VsFormat.toDisplayString(value));
    }

    /// Writes a `Object` value followed by the line terminator.
    public static void WriteLine(Object value) {
        OUT.println(VsFormat.toDisplayString(value));
    }

    /// Writes a `Object` value without a line terminator.
    public static void Write(Object value) {
        OUT.print(VsFormat.toDisplayString(value));
    }
}
