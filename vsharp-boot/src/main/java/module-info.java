/// The V# launch preflight: the only module compiled below Java 25, so that a JVM which
/// cannot load the toolchain can still load something that explains why.
module vsharp.boot {
    exports vsharp.boot;
}
