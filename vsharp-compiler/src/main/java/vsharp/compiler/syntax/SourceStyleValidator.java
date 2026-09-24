package vsharp.compiler.syntax;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import vsharp.compiler.diagnostics.DiagnosticBag;
import vsharp.compiler.diagnostics.DiagnosticCode;
import vsharp.compiler.source.SourceFile;

/// Enforces the source-layout invariants that are part of the V# language contract.
///
/// V# uses Allman braces for every *block* brace - declarations, namespaces, statement blocks,
/// `switch` bodies and switch expressions. Every such opening brace occupies its own line, both
/// braces use four literal spaces per lexically enclosing brace pair, and a closing brace begins
/// its line. This intentionally rejects tabs and visually aligned but structurally inconsistent
/// indentation: one source has one spelling, independent of editor settings.
///
/// Three C# constructs write a brace *inside an expression or a pattern* - array initializers
/// (`{ 1, 2 }`), property subpatterns (`Order { Quantity: > 100 }`) and `with` initializers.
/// Forcing Allman on those would make the constructs unwritable in their canonical C# spelling,
/// so a pair the parser identifies as one of them may be written entirely on one line. Spread
/// across lines it is a block again and obeys the ordinary rule, which keeps exactly two
/// admissible spellings - fully inline, or fully Allman - rather than free-form layout.
///
/// Validation consumes the preprocessor's same-length masked view. Braces in inactive branches,
/// comments and literals are therefore not mistaken for active syntax, while diagnostics retain
/// offsets into the original source.
public final class SourceStyleValidator {

    private static final int INDENT_WIDTH = 4;

    private SourceStyleValidator() {
        throw new AssertionError("No instances");
    }

    /// Validates one syntactically clean source file.
    ///
    /// The compilation facade invokes this after parsing succeeds, so malformed syntax keeps its
    /// primary parser diagnostic instead of accumulating layout cascades. The scratch bag prevents
    /// preprocessing warnings from being reported a second time during this token-only pass.
    ///
    /// `inlineBraces` holds the start offsets of expression- and pattern-level opening braces as
    /// the parser recorded them; see [AuxiliarySyntax.CompilationUnit].
    public static void validate(SourceFile file, Set<String> symbols, List<Integer> inlineBraces,
            DiagnosticBag diagnostics) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(symbols, "symbols");
        Objects.requireNonNull(inlineBraces, "inlineBraces");
        Objects.requireNonNull(diagnostics, "diagnostics");

        DiagnosticBag scratch = new DiagnosticBag();
        Preprocessor.Result preprocessed = Preprocessor.process(file, symbols, scratch);
        List<SyntaxToken> tokens = Lexer.tokenizeMasked(
                file, preprocessed.maskedText(), scratch);
        Set<Integer> exempt = exemptTokens(file, tokens, Set.copyOf(inlineBraces));

        int depth = 0;
        for (int position = 0; position < tokens.size(); position++) {
            if (exempt.contains(position)) {
                continue;
            }
            SyntaxToken token = tokens.get(position);
            if (token.is(SyntaxKind.OPEN_BRACE)) {
                validateOpeningBrace(file, token, depth, diagnostics);
                depth++;
            } else if (token.is(SyntaxKind.CLOSE_BRACE)) {
                depth = Math.max(0, depth - 1);
                validateClosingBrace(file, token, depth, diagnostics);
            }
        }
    }

    /// Token positions of the brace pairs that are exempt because the parser classified the
    /// opening brace as expression- or pattern-level *and* the pair occupies a single line.
    ///
    /// Both members of an exempt pair are skipped, so nesting depth is unaffected and a block
    /// written inside one - a statement lambda in an array initializer, say - keeps being
    /// measured against its enclosing block. Matching runs over a syntactically clean file, so
    /// the braces are balanced; a stray closing brace is ignored rather than assumed away.
    private static Set<Integer> exemptTokens(SourceFile file, List<SyntaxToken> tokens,
            Set<Integer> inlineBraces) {
        if (inlineBraces.isEmpty()) {
            return Set.of();
        }
        Set<Integer> exempt = new HashSet<>();
        Deque<Integer> open = new ArrayDeque<>();
        for (int position = 0; position < tokens.size(); position++) {
            SyntaxToken token = tokens.get(position);
            if (token.is(SyntaxKind.OPEN_BRACE)) {
                open.push(position);
            } else if (token.is(SyntaxKind.CLOSE_BRACE) && !open.isEmpty()) {
                int opening = open.pop();
                SyntaxToken brace = tokens.get(opening);
                if (inlineBraces.contains(brace.span().start())
                        && file.positionOf(brace.span().start()).line()
                                == file.positionOf(token.span().start()).line()) {
                    exempt.add(opening);
                    exempt.add(position);
                }
            }
        }
        return Set.copyOf(exempt);
    }

    private static void validateOpeningBrace(SourceFile file, SyntaxToken brace, int depth,
            DiagnosticBag diagnostics) {
        int line = file.positionOf(brace.span().start()).line();
        int lineStart = file.lineMap().lineStart(line);
        int lineEnd = file.lineMap().lineEnd(line, file.text());
        String prefix = file.text().substring(lineStart, brace.span().start());
        boolean prefixIsWhitespace = prefix.isBlank();
        boolean suffixIsEmpty = file.text().substring(brace.span().end(), lineEnd).isBlank();

        if (!prefixIsWhitespace || !suffixIsEmpty) {
            diagnostics.report(DiagnosticCode.OPENING_BRACE_PLACEMENT,
                    file, brace.span());
        }
        if (prefixIsWhitespace) {
            validateIndentation(file, brace, lineStart, depth, diagnostics);
        }
    }

    private static void validateClosingBrace(SourceFile file, SyntaxToken brace, int depth,
            DiagnosticBag diagnostics) {
        int line = file.positionOf(brace.span().start()).line();
        int lineStart = file.lineMap().lineStart(line);
        String prefix = file.text().substring(lineStart, brace.span().start());
        if (!prefix.isBlank()) {
            diagnostics.report(DiagnosticCode.CLOSING_BRACE_PLACEMENT,
                    file, brace.span());
            return;
        }
        validateIndentation(file, brace, lineStart, depth, diagnostics);
    }

    private static void validateIndentation(SourceFile file, SyntaxToken brace, int lineStart,
            int depth, DiagnosticBag diagnostics) {
        int expected = depth * INDENT_WIDTH;
        int actual = brace.span().start() - lineStart;
        if (actual != expected
                || !containsOnlySpaces(file.text(), lineStart, brace.span().start())) {
            diagnostics.report(DiagnosticCode.BRACE_INDENTATION,
                    file, brace.span(), expected);
        }
    }

    private static boolean containsOnlySpaces(String text, int start, int end) {
        for (int index = start; index < end; index++) {
            if (text.charAt(index) != ' ') {
                return false;
            }
        }
        return true;
    }
}
