// Sample V# program used by the Gradle integration check.
// Top-level statements become `public static void main(String[])` on a holder named after
// the file, so the emitted class is directly launchable with `java -cp <out> Hello`.

using System;

int first = 2;
int second = 40;
Console.WriteLine($"hello from V# {first + second}");
