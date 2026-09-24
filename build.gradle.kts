// V# — root build.
// ABSOLUTE DEPENDENCY RULE: no third-party implementation/runtime dependency is declared
// anywhere in this build. There is deliberately no `repositories { }` block in any project:
// a dependency on an external artifact cannot even be resolved.

plugins {
    base
}

val vsharpVersion: String by extra("0.0.93-A")

subprojects {
    apply(plugin = "java-library")

    group = "dev.vsharp"
    version = vsharpVersion

    extensions.configure<JavaPluginExtension> {
        modularity.inferModulePath.set(true)
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release.set(25)
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(
            listOf("-Xlint:all,-serial,-requires-automatic", "-Werror"),
        )
    }

    tasks.withType<Jar>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        manifest {
            attributes(
                "Implementation-Title" to project.name,
                "Implementation-Version" to vsharpVersion,
            )
        }
    }

    tasks.withType<Javadoc>().configureEach {
        options.encoding = "UTF-8"
    }
}

// ---------------------------------------------------------------------------------------
// Gradle integration check.
//
// Applies the same script plugin a consumer would (`gradle/vsharp.gradle.kts`) to the
// sample under `samples/hello`, then runs the emitted class and asserts its output. This is
// the acceptance gate's "Gradle workflow works" and "CLI works from produced artifact",
// verified by the build rather than asserted in prose.
// ---------------------------------------------------------------------------------------

val sampleClassesDir = "build/samples/hello/classes"

extra["vsharpSourceDir"] = "samples/hello/src"
extra["vsharpOutputDir"] = sampleClassesDir
apply(from = "gradle/vsharp.gradle.kts")

val verifyGradleWorkflow by tasks.registering(Exec::class) {
    group = "verification"
    description = "Compiles the V# sample through the Gradle integration and runs the result."

    val runtimeJar = project(":vsharp-runtime").tasks.named<Jar>("jar")
    val classesDir = layout.projectDirectory.dir(sampleClassesDir)
    // The launcher inside a JDK is `bin/java` everywhere except Windows, where it is
    // `bin/java.exe`; naming the wrong one makes this gate unrunnable rather than failing.
    val javaBinary =
        if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
    val javaExecutable = File(File(System.getProperty("java.home"), "bin"), javaBinary)
    val record = layout.buildDirectory.file("samples/hello/output.txt")

    dependsOn("compileVSharp", runtimeJar)
    inputs.dir(classesDir).withPropertyName("sampleClasses")
    inputs.files(runtimeJar).withPropertyName("vsharpRuntime")
    outputs.file(record).withPropertyName("sampleOutput")

    val captured = java.io.ByteArrayOutputStream()
    standardOutput = captured
    isIgnoreExitValue = true

    doFirst {
        // Exactly what docs/CLI.md tells a user to do: generated classes plus the V# runtime
        // on the class path, launched by binary name.
        val classpath = listOf(
            classesDir.asFile.absolutePath,
            runtimeJar.get().archiveFile.get().asFile.absolutePath,
        ).joinToString(File.pathSeparator)
        commandLine(javaExecutable.absolutePath, "-cp", classpath, "Hello")
    }

    doLast {
        val text = captured.toString(Charsets.UTF_8).trim()
        val exitValue = executionResult.get().exitValue
        check(exitValue == 0) {
            "V# sample exited with $exitValue; output was \"$text\""
        }
        val expected = "hello from V# 42"
        check(text == expected) {
            "V# Gradle workflow produced \"$text\", expected \"$expected\""
        }

        val target = record.get().asFile
        target.parentFile.mkdirs()
        target.writeText(text + "\n", Charsets.UTF_8)
        logger.lifecycle("V# Gradle workflow verified: $text")
    }
}

tasks.named("check") { dependsOn(verifyGradleWorkflow) }

// ---------------------------------------------------------------------------------------
// One install command for every supported host.
//
// The tools are ordinary Gradle application distributions, so `installDist` already writes
// both a POSIX `sh` launcher and a Windows `.bat` launcher on every platform. What a user
// still has to work out is which one their shell wants and what to put on PATH, and that is
// the only part that differs between Linux, FreeBSD, OpenBSD and Windows. This task answers
// it from the JVM that is running rather than from documentation that can drift.
// ---------------------------------------------------------------------------------------
val installTools by tasks.registering {
    group = "distribution"
    description = "Installs the vsharp and vsharp-lsp launchers and prints how to reach them."

    dependsOn(":vsharp-cli:installDist", ":vsharp-lsp:installDist")

    val windows = System.getProperty("os.name").startsWith("Windows")
    val binDirectories = listOf("vsharp-cli", "vsharp-lsp").map { module ->
        val application = if (module == "vsharp-cli") "vsharp" else "vsharp-lsp"
        rootProject.layout.projectDirectory
            .dir("$module/build/install/$application/bin").asFile
    }

    doLast {
        val launcher = if (windows) "vsharp.bat" else "vsharp"
        logger.lifecycle("V# tools installed. Launchers:")
        binDirectories.forEach { directory ->
            logger.lifecycle("  $directory")
        }
        logger.lifecycle(
            if (windows) {
                "Add them to PATH for this session:\n" + binDirectories.joinToString("\n") {
                    "  set PATH=$it;%PATH%"
                }
            } else {
                "Add them to PATH for this session:\n" +
                    "  export PATH=\"" + binDirectories.joinToString(":") + ":\$PATH\""
            },
        )
        logger.lifecycle("Then: $launcher --version")
    }
}
