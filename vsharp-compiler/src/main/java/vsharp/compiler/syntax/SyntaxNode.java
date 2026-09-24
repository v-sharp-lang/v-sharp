package vsharp.compiler.syntax;

import vsharp.compiler.source.SourceSpan;

/// A node of the V# syntax tree.
///
/// The tree is a closed algebra: five categories, each a sealed interface whose cases are
/// records declared in the same file. Consumers switch exhaustively and the compiler proves
/// that a new node kind cannot be forgotten by a later stage.
///
/// Nodes are immutable and carry only syntax. No binding, type or constant information is
/// attached here; that lives on the typed IR, so the same tree can be re-analysed and so a
/// syntax test cannot accidentally depend on semantic state.
public sealed interface SyntaxNode
        permits AuxiliarySyntax, DeclarationSyntax, ExpressionSyntax, PatternSyntax,
                StatementSyntax, TypeSyntax {

    /// The source region this node covers, including every token of its children.
    SourceSpan span();
}
