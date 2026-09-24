plugins {
    application
}

description = "V# command-line compiler driver"

dependencies {
    implementation(project(":vsharp-compiler"))
    // Runtime only: nothing compiles against the preflight, which reaches the driver by
    // reflection precisely so that it can be loaded by a JVM that cannot load the driver.
    runtimeOnly(project(":vsharp-boot"))
}

application {
    // `mainModule` is deliberately unset, so the launcher starts from the class path rather
    // than the module path. Resolving a module graph reads every `module-info.class` in it,
    // and all of them are class file version 69: an older JVM fails there, before any V# code
    // runs, and reports a class file version number instead of an instruction. On the class
    // path the same JVM reaches the Java 11 preflight instead.
    //
    // `mainClass` stays the driver because Gradle writes it into this module's descriptor,
    // where a class from another module would be invalid. The start script's own entry point
    // is overridden below; `gradle run` and `java -jar` keep using the driver directly.
    mainClass.set("vsharp.cli.Main")
    applicationName = "vsharp"

    // The compiler resolves user type names against the JDK it runs on. As a named module its
    // own graph would resolve only what it requires - `java.base` and little else - so
    // `java.net.http`, `java.sql` and every other system module would be invisible to a V#
    // program. A module layer cannot repair that at run time: the JVM refuses to let any
    // loader but the boot loader define a `java.*` package. The launch is therefore where the
    // default root set is resolved at launch: exactly what an ordinary classpath Java program
    // sees, which keeps incubator modules out rather than warning on every compile.
    applicationDefaultJvmArgs = listOf("--add-modules", "ALL-DEFAULT")
}

tasks.processResources {
    // The version is expanded into a resource, so it is an *input* to this task. Without
    // declaring it, the template alone decides staleness: changing the project version left
    // the task up to date and shipped a jar whose manifest said one version and whose
    // `--version` output said the previous one. Found exactly that way, when 0.1.0 became
    // 0.0.93-A and only the manifest moved.
    inputs.property("vsharpVersion", project.version.toString())
    filesMatching("vsharp/cli/version.txt") {
        expand("version" to project.version.toString())
    }
}

tasks.named<Jar>("jar") {
    manifest {
        attributes("Main-Class" to "vsharp.cli.Main")
    }
}

// The installed launcher starts the preflight; everything else about the script is Gradle's.
tasks.named<CreateStartScripts>("startScripts") {
    mainClass.set("vsharp.boot.CompilerBoot")
}
