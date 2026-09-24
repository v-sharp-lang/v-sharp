plugins {
    application
}

description = "V# language server: diagnostics, completion, hover and symbols over LSP"

dependencies {
    implementation(project(":vsharp-compiler"))
    runtimeOnly(project(":vsharp-boot"))
}

application {
    // Same class path launch and same preflight as the CLI, for the same reason: a
    // server that cannot start must say why. The bundled editor launch is unaffected - an
    // extension runs the module path directly with a JVM it selected itself.
    mainClass.set("vsharp.lsp.Main")
    applicationName = "vsharp-lsp"

    // Same reasoning as the CLI: the server type-checks user code against the JDK it
    // runs on, so it must see the ordinary classpath root module set rather than the closure
    // of `requires vsharp.compiler`. Without this every `java.net.http` reference an editor
    // buffer makes would resolve in `vsharp` but not in `vsharp-lsp`, and the editor would
    // contradict the compiler.
    applicationDefaultJvmArgs = listOf("--add-modules", "ALL-DEFAULT")
}

tasks.named<Jar>("jar") {
    manifest {
        attributes("Main-Class" to "vsharp.lsp.Main")
    }
}

tasks.named<CreateStartScripts>("startScripts") {
    mainClass.set("vsharp.boot.ServerBoot")
}
