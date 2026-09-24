/// The V# test suite.
///
/// Every case is a [vsharp.testkit.TestSuite] service; the module's only entry point runs
/// them. Suites are listed explicitly in `provides` so that adding a suite without wiring
/// it in is a compile-time visible omission rather than a silently skipped test.
module vsharp.tests {
    requires vsharp.compiler;
    requires vsharp.boot;
    requires vsharp.cli;
    requires vsharp.runtime;
    requires vsharp.testkit;

    provides vsharp.testkit.TestSuite with
            vsharp.tests.api.CompilationTests,
            vsharp.tests.async.AsyncTests,
            vsharp.tests.boot.BootTests,
            vsharp.tests.cli.CliTests,
            vsharp.tests.diagnostics.DiagnosticTests,
            vsharp.tests.ir.IrLoweringTests,
            vsharp.tests.ir.StatementLoweringTests,
            vsharp.tests.ir.UnitLoweringTests,
            vsharp.tests.runtime.ArrayTests,
            vsharp.tests.runtime.BooleanTests,
            vsharp.tests.runtime.CharTests,
            vsharp.tests.runtime.ConvertTests,
            vsharp.tests.runtime.DecimalTests,
            vsharp.tests.runtime.DoubleTests,
            vsharp.tests.runtime.FormatTests,
            vsharp.tests.runtime.Int32Tests,
            vsharp.tests.runtime.MathTests,
            vsharp.tests.runtime.NumberFormatTests,
            vsharp.tests.runtime.StringTests,
            vsharp.tests.runtime.IndexRangeTests,
            vsharp.tests.semantics.ConstantTests,
            vsharp.tests.semantics.ConversionTests,
            vsharp.tests.semantics.CrossFileCompilationTests,
            vsharp.tests.semantics.InteropTests,
            vsharp.tests.semantics.ExpressionTests,
            vsharp.tests.semantics.ExtensionMethodTests,
            vsharp.tests.semantics.FlowAnalysisTests,
            vsharp.tests.semantics.GenericArrayTests,
            vsharp.tests.semantics.GenericConstraintTests,
            vsharp.tests.semantics.GenericEqualityTests,
            vsharp.tests.semantics.GenericNullableTests,
            vsharp.tests.semantics.GenericRefOutTests,
            vsharp.tests.semantics.GenericResultTests,
            vsharp.tests.semantics.GenericTupleTests,
            vsharp.tests.semantics.LambdaScopeTests,
            vsharp.tests.semantics.MemberAccessTests,
            vsharp.tests.semantics.OverloadTests,
            vsharp.tests.semantics.SymbolTests,
            vsharp.tests.semantics.TargetTypedTests,
            vsharp.tests.source.SourceModelTests,
            vsharp.tests.syntax.LexerTests,
            vsharp.tests.syntax.ParserTests,
            vsharp.tests.syntax.SourceStyleTests,
            vsharp.tests.backend.BackendTests,
            vsharp.tests.nativeimage.NativeImageTests,
            vsharp.tests.stdlib.StandardLibraryTests;
}
