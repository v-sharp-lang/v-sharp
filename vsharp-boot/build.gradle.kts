description = "V# launch preflight: the one component an older JVM can still load"

// Deliberately compiled far below the rest of the toolchain. Every other module is class
// file version 69 and a JDK 24 or older refuses to load it, which is the single failure a
// user on a stale or unexpected JDK actually meets first. A guard that cannot itself be
// loaded cannot report anything, so this module targets Java 11 and does its work through
// reflection rather than a compile-time reference to the compiler.
tasks.withType<JavaCompile>().configureEach {
    options.release.set(11)
}
