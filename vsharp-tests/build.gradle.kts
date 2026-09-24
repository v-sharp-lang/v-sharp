// Executable test suite. There is no `test` source set and no test framework: cases are
// ServiceLoader-provided TestSuite implementations executed by the V# testkit runner.
description = "V# test suite"

dependencies {
    implementation(project(":vsharp-testkit"))
    implementation(project(":vsharp-boot"))
    implementation(project(":vsharp-compiler"))
    implementation(project(":vsharp-cli"))
    implementation(project(":vsharp-runtime"))
}

val runTests = tasks.register<JavaExec>("runTests") {
    group = "verification"
    description = "Runs the complete V# test suite."
    mainModule.set("vsharp.tests")
    mainClass.set("vsharp.tests.TestMain")
    classpath = sourceSets.main.get().runtimeClasspath
    // The suite compiles V# through the same facade the CLI does, so it needs the same view of
    // the installed JDK.
    jvmArgs("--add-modules", "ALL-DEFAULT")
    // Filters may be passed through: ./gradlew :vsharp-tests:runTests --args="lexer"
}

tasks.named("check") { dependsOn(runTests) }
