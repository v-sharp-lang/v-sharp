/// The V# compiler.
///
/// Exports are deliberately limited to the usable front-end contracts: source and
/// diagnostics, immutable syntax, and declaration semantics. Future IR, lowering and
/// backend implementation packages remain encapsulated so each public surface is an
/// explicit compatibility decision.
module vsharp.compiler {
    requires vsharp.runtime;

    exports vsharp.compiler.api;
    exports vsharp.compiler.diagnostics;
    exports vsharp.compiler.ir;
    exports vsharp.compiler.nativeimage;
    exports vsharp.compiler.semantics.binding;
    exports vsharp.compiler.semantics.constants;
    exports vsharp.compiler.semantics.conversions;
    exports vsharp.compiler.semantics.flow;
    exports vsharp.compiler.semantics.overloads;
    exports vsharp.compiler.semantics.symbols;
    exports vsharp.compiler.semantics.types;
    exports vsharp.compiler.source;
    exports vsharp.compiler.syntax;
}
