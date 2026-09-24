// V# Gradle integration.
//
// A dependency-free script plugin: apply it from any Gradle build to compile `.vs` sources
// with the V# compiler and put the emitted `.class` files where a JVM can run them.
//
//     extra["vsharpSourceDir"] = "src/main/vsharp"          // optional, this is the default
//     extra["vsharpOutputDir"] = "build/classes/vsharp/main" // optional, this is the default
//     extra["vsharpDefines"] = "DEBUG,TRACE"                 // optional
//     apply(from = "gradle/vsharp.gradle.kts")
//
// It registers `compileVSharp` and makes `assemble` depend on it, so `gradle build` compiles
// V# sources the way it compiles any other source set.
//
// Configuration goes through `extra` properties rather than a typed extension on purpose:
// a type declared inside a script plugin is not visible to the build script that applies it,
// so a typed extension would only be configurable through untyped reflection anyway.
//
// ABSOLUTE DEPENDENCY RULE: this deliberately does not use `gradleApi()`,
// `java-gradle-plugin`, or any published plugin. It drives the installed `vsharp` launcher
// through Gradle's own `Exec` task, so applying it adds nothing to resolve - which is what
// lets it work in this build, where no `repositories { }` block exists anywhere.

fun vsharpProperty(name: String, default: String): String =
    (project.findProperty(name) as String?)?.takeIf { it.isNotBlank() } ?: default

val vsharpSourceDir = vsharpProperty("vsharpSourceDir", "src/main/vsharp")
val vsharpOutputDir = vsharpProperty("vsharpOutputDir", "build/classes/vsharp/main")
val vsharpDefines = vsharpProperty("vsharpDefines", "")

// `:vsharp-cli:installDist` produces the launcher. Depending on the task rather than on a
// hardcoded path keeps the wiring correct whenever the compiler is rebuilt.
//
// `installDist` writes both start scripts on every host: a POSIX `sh` one and a Windows
// `.bat` one. Existence therefore cannot choose between them - the operating system must.
// Handing `Exec` the `sh` script on Windows is not a portable fallback, it is a failure: the
// file has no interpreter there, and the task dies with "%1 is not a valid Win32
// application" rather than compiling anything.
val vsharpLauncherName =
    if (System.getProperty("os.name").startsWith("Windows")) "vsharp.bat" else "vsharp"
val vsharpLauncher = rootProject.file("vsharp-cli/build/install/vsharp/bin/$vsharpLauncherName")

// The compiler is a JDK 25 module and its jars are class file version 69, so the launcher
// must not inherit an older JAVA_HOME. Gradle's own launcher JVM is frequently older than
// the JDK a project builds with - on a development machine it is 21 - and the start script would then
// fail with "Unsupported major.minor version 69.0" before reaching main.
val vsharpJavaHome = vsharpProperty("org.gradle.java.home", System.getProperty("java.home"))

// The native image stage is triggered by `GRAAL_HOME` alone, and an `Exec` task inherits the
// environment, so a build that exports it already reaches the compiler. What Gradle must be
// told is that the variable is an input: without this, a build that first ran without a
// GraalVM would be up to date afterwards, and exporting the variable would silently produce
// no executable. The value is read through a provider so the up-to-date check sees the
// environment of the build rather than the environment of the daemon that started it.
val vsharpGraalHome = providers.environmentVariable("GRAAL_HOME").orElse("")

tasks.register<Exec>("compileVSharp") {
    group = "build"
    description = "Compiles V# sources to JVM class files."

    dependsOn(":vsharp-cli:installDist")

    val sourceTree = fileTree(vsharpSourceDir) { include("**/*.vs") }
    val outputDirectory = layout.projectDirectory.dir(vsharpOutputDir)

    inputs.files(sourceTree).withPropertyName("vsharpSources").skipWhenEmpty()
    inputs.file(vsharpLauncher).withPropertyName("vsharpLauncher")
    inputs.property("graalHome", vsharpGraalHome)
    // The executable, when one is produced, lands beside the classes it was built from,
    // so the single output directory already carries it.
    outputs.dir(outputDirectory).withPropertyName("vsharpOutputDir")

    environment("JAVA_HOME", vsharpJavaHome)

    doFirst {
        val target = outputDirectory.asFile
        target.mkdirs()

        // Sorted so the command line - and therefore any diagnostic ordering that depends on
        // input order - is identical across machines and filesystems.
        val sources = sourceTree.files.sortedBy { it.absolutePath }
        require(sources.isNotEmpty()) { "No .vs sources found under $vsharpSourceDir" }

        val command = mutableListOf(vsharpLauncher.absolutePath, "--out", target.absolutePath)
        vsharpDefines.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { command += listOf("--define", it) }
        sources.forEach { command += it.absolutePath }
        commandLine(command)
    }
}

tasks.matching { it.name == "assemble" }.configureEach { dependsOn("compileVSharp") }
