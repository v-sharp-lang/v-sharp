package vsharp.lsp.server;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import vsharp.compiler.semantics.binding.SemanticModel;
import vsharp.compiler.semantics.symbols.Symbol;
import vsharp.compiler.semantics.symbols.SymbolKind;
import vsharp.compiler.source.SourceFile;
import vsharp.lsp.json.Json;

/// Builds the outline for `textDocument/documentSymbol`.
///
/// Nesting is derived from qualified names rather than from source spans, because a
/// symbol's recorded location is its declaring name, not its extent - span containment
/// would put nothing inside anything. `Focus.Program.Main` is a child of `Focus.Program`
/// by construction, which is exactly the tree the outline should show.
///
/// Locals, parameters and type parameters are omitted. They are real symbols, but an
/// outline listing every loop variable is unusable, and editors expect declaration-level
/// structure here.
final class DocumentSymbols {

    /// LSP `SymbolKind`. Transmitted as bare integers by the protocol.
    private static final Map<SymbolKind, Integer> KINDS = Map.of(
            SymbolKind.NAMESPACE, 3,
            SymbolKind.STATIC_CONTAINER, 5,
            SymbolKind.NAMED_TYPE, 23,
            SymbolKind.FUNCTION, 12,
            SymbolKind.FIELD, 8,
            SymbolKind.ENUM_MEMBER, 22);

    private DocumentSymbols() {
        throw new AssertionError("No instances");
    }

    static List<Json> of(SemanticModel model, SourceFile file) {
        List<Symbol> declared = model.symbols().stream()
                .filter(s -> s.location().file().name().equals(file.name()))
                .filter(s -> KINDS.containsKey(s.kind()))
                .filter(s -> !s.name().isEmpty() && !s.name().startsWith("<"))
                .sorted(Comparator.comparingInt(s -> s.location().span().start()))
                .toList();

        // Children are collected against the owner's qualified name; roots are the
        // symbols whose owner is not itself declared in this file.
        Map<String, List<Symbol>> childrenByOwner = new LinkedHashMap<>();
        List<Symbol> roots = new ArrayList<>();
        for (Symbol symbol : declared) {
            String owner = owner(symbol.qualifiedName());
            boolean ownerDeclaredHere = declared.stream()
                    .anyMatch(other -> other != symbol && other.qualifiedName().equals(owner));
            if (ownerDeclaredHere) {
                childrenByOwner.computeIfAbsent(owner, key -> new ArrayList<>()).add(symbol);
            } else {
                roots.add(symbol);
            }
        }
        return roots.stream().map(root -> node(root, file, childrenByOwner)).toList();
    }

    private static Json node(Symbol symbol, SourceFile file,
            Map<String, List<Symbol>> childrenByOwner) {
        List<Json> children = childrenByOwner
                .getOrDefault(symbol.qualifiedName(), List.of()).stream()
                .map(child -> node(child, file, childrenByOwner))
                .toList();
        // `range` should span the whole declaration and `selectionRange` its name. Only
        // the name span is recorded on a symbol, so both are the name: editors accept
        // this - selectionRange must be contained in range, and identical satisfies it -
        // and the outline still navigates to the right line.
        Json range = Positions.range(file, symbol.location().span());
        return Json.object()
                .put("name", symbol.name())
                .put("detail", Signatures.of(symbol))
                .put("kind", KINDS.getOrDefault(symbol.kind(), 13))
                .put("range", range)
                .put("selectionRange", range)
                .put("children", children)
                .build();
    }

    private static String owner(String qualifiedName) {
        int lastDot = qualifiedName.lastIndexOf('.');
        return lastDot < 0 ? "" : qualifiedName.substring(0, lastDot);
    }
}
