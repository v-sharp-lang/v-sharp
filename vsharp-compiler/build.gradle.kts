// The V# compiler. JDK-only: the single dependency is the V# runtime module, whose types
// the backend needs in order to derive verified class descriptors for generated code.
description = "V# compiler: lexer, parser, semantic analysis, typed IR, JVM backend"

dependencies {
    implementation(project(":vsharp-runtime"))
}
