import java.nio.charset.StandardCharsets

// The V# editor extension for VS Code and VSCodium.
//
// The extension is JavaScript and JSON; the only Java here is the generator that derives the
// TextMate grammar from the compiler's token tables, so the highlighter cannot drift from
// the lexer. Packaging is a plain Zip task: a .vsix is an OPC zip, and building it with
// Gradle keeps the dependency rule intact - no npm, no `vsce`, no toolchain outside the JDK.

plugins {
    application
}

description = "V# language support for VS Code and VSCodium"

dependencies {
    implementation(project(":vsharp-compiler"))
}

application {
    mainModule.set("vsharp.vscode")
    mainClass.set("vsharp.vscode.GrammarGenerator")
}

val extensionSource = layout.projectDirectory.dir("extension")
val staged = layout.buildDirectory.dir("vsix")
val grammarFile = layout.buildDirectory.file("generated/syntaxes/vsharp.tmLanguage.json")

/// The publisher and version the manifest and package.json must agree on. A mismatch makes
/// an installed extension that the marketplace tooling refuses to update.
val extensionPublisher = "dev-vsharp"
val extensionId = "vsharp"
val extensionVersion = "0.0.93-A"

val generateGrammar by tasks.registering(JavaExec::class) {
    group = "build"
    description = "Generates the TextMate grammar from the compiler's keyword tables."
    classpath = sourceSets["main"].runtimeClasspath
    mainModule.set("vsharp.vscode")
    mainClass.set("vsharp.vscode.GrammarGenerator")
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(grammarFile.get().asFile.absolutePath)
    })
    outputs.file(grammarFile)
}

/// The language server, packaged as module-path jars.
///
/// `installDist` also produces start scripts; they are deliberately not copied. Zip entries
/// carry no executable bit, so a bundled script would arrive unrunnable. The extension
/// launches `java --module-path server/lib` itself instead.
///
/// `Sync` rather than `Copy`, because this directory *is* a module path and a module path
/// may not hold two versions of one module. A `Copy` leaves whatever was staged before, so
/// renaming the jars from 0.1.0 to 0.0.93-A shipped both sets inside one `.vsix`, and the
/// server died at boot layer initialisation with "Two versions of module vsharp.boot found" -
/// reported from a real editor, exit code 1, before a single line of V# was read. Sync
/// deletes what the source no longer contains, which is the only correct semantics here.
///
/// The preflight jar is excluded on purpose: it is a class-path guard for the installed start
/// scripts, the extension launches the module path directly, and an unused module here
/// is exactly the dead weight that caused the collision.
val stageServer by tasks.registering(Sync::class) {
    dependsOn(":vsharp-lsp:installDist")
    from(project(":vsharp-lsp").layout.buildDirectory.dir("install/vsharp-lsp/lib"))
    into(staged.map { it.dir("extension/server/lib") })
    include("*.jar")
    exclude("vsharp-boot-*.jar")
}

val stageExtension by tasks.registering(Copy::class) {
    dependsOn(generateGrammar, stageServer)
    into(staged.map { it.dir("extension") })
    from(extensionSource) {
        exclude("syntaxes/**")
    }
    from(grammarFile) {
        into("syntaxes")
    }
    doLast {
        val manifest = staged.get().file("extension.vsixmanifest").asFile
        manifest.parentFile.mkdirs()
        manifest.writeText(vsixManifest(), StandardCharsets.UTF_8)
        val contentTypes = staged.get().file("[Content_Types].xml").asFile
        contentTypes.writeText(contentTypes(), StandardCharsets.UTF_8)
    }
}

/// Refuses to package a module path that cannot be resolved.
///
/// The JVM's own check is the last one: two versions of a module are a hard failure at boot
/// layer initialisation, with no partial success and nothing the extension can do about it.
/// That check runs on a user's machine, so this one runs on the build's - the same rule, one
/// step earlier, phrased as the module name it would collide on.
val verifyServerModulePath by tasks.registering {
    group = "verification"
    description = "Checks the bundled server's module path holds one jar per module."

    // The whole staged tree, not just the copy step: `stageExtension` writes into the
    // directory that contains this one, so the final state is what must be checked.
    dependsOn(stageServer, stageExtension)
    val libraryDirectory = staged.map { it.dir("extension/server/lib") }
    inputs.dir(libraryDirectory).withPropertyName("serverModulePath")

    doLast {
        val jars = libraryDirectory.get().asFile.listFiles { file -> file.name.endsWith(".jar") }
            ?: emptyArray()
        check(jars.isNotEmpty()) { "The bundled server has no jars to run." }
        // `vsharp-compiler-0.0.93-A.jar` and `vsharp-compiler-0.1.0.jar` are one module under
        // two names, which is precisely what the resolver rejects. The version is everything
        // from the first `-` that starts a digit, so a prerelease tag cannot hide a duplicate:
        // stripping one dash-separated segment would have left `vsharp-compiler-0.0.93` and
        // `vsharp-compiler-0.1.0` looking like different modules.
        val versioned = Regex("^(.*?)-\\d.*\\.jar$")
        val byModule = jars.groupBy {
            versioned.find(it.name)?.groupValues?.get(1) ?: it.name.removeSuffix(".jar")
        }
        val duplicated = byModule.filterValues { it.size > 1 }
        check(duplicated.isEmpty()) {
            "The bundled server module path holds more than one version of a module, which the " +
                "JVM refuses at startup: " +
                duplicated.entries.joinToString("; ") { (module, files) ->
                    "$module -> " + files.map { it.name }.sorted().joinToString(", ")
                }
        }
        logger.lifecycle("Bundled server module path verified: ${jars.size} modules, no duplicates")
    }
}

val vsix by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Packages the extension as an installable .vsix."
    dependsOn(stageExtension, verifyServerModulePath)
    from(staged)
    archiveFileName.set("$extensionId-$extensionVersion.vsix")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.named("assemble") {
    dependsOn(vsix)
}

/// The OPC manifest a VS Code-compatible gallery and the CLI installer both read.
fun vsixManifest(): String = """
    <?xml version="1.0" encoding="utf-8"?>
    <PackageManifest Version="2.0.0" xmlns="http://schemas.microsoft.com/developer/vsx-schema/2011">
      <Metadata>
        <Identity Language="en-US" Id="$extensionId" Version="$extensionVersion" Publisher="$extensionPublisher" />
        <DisplayName>V#</DisplayName>
        <Description xml:space="preserve">V# language support: syntax highlighting, diagnostics, completion, hover, go-to-definition and document symbols.</Description>
        <Tags>vsharp,v#,csharp,jvm,language server</Tags>
        <Categories>Programming Languages,Linters</Categories>
        <GalleryFlags>Public</GalleryFlags>
      </Metadata>
      <Installation>
        <InstallationTarget Id="Microsoft.VisualStudio.Code" Version="[1.75.0,)" />
      </Installation>
      <Dependencies />
      <Assets>
        <Asset Type="Microsoft.VisualStudio.Code.Manifest" Path="extension/package.json" Addressable="true" />
      </Assets>
    </PackageManifest>
""".trimIndent() + "\n"

/// OPC requires a content-type for every extension present in the package.
fun contentTypes(): String = """
    <?xml version="1.0" encoding="utf-8"?>
    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
      <Default Extension="json" ContentType="application/json" />
      <Default Extension="js" ContentType="application/javascript" />
      <Default Extension="jar" ContentType="application/java-archive" />
      <Default Extension="md" ContentType="text/markdown" />
      <Default Extension="txt" ContentType="text/plain" />
      <Default Extension="vsixmanifest" ContentType="text/xml" />
    </Types>
""".trimIndent() + "\n"
