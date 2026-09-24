package vsharp.compiler.semantics.binding;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import vsharp.compiler.semantics.symbols.Symbol;
import vsharp.compiler.syntax.DeclarationSyntax;
import vsharp.compiler.syntax.SyntaxNode;
import vsharp.compiler.source.SourceFile;
import vsharp.compiler.semantics.binding.DeclarationBinder.NamespaceNode;
import vsharp.compiler.semantics.binding.DeclarationBinder.TypeKey;

/// Global catalogue of namespace and type headers across all compilation units.
public final class GlobalHeaders {

    private final NamespaceNode root;
    private final Map<TypeKey, Symbol> declaredTypeHeaders;
    private final Map<String, List<Symbol>> headersByOwner;
    private final Map<DeclarationSyntax, Symbol> headers;
    private final List<Symbol> symbols;
    private final Map<SyntaxNode, Symbol> declaredSymbols;
    private final Map<SyntaxNode, SourceFile> syntaxFiles;

    GlobalHeaders(NamespaceNode root, Map<TypeKey, Symbol> declaredTypeHeaders,
            Map<String, List<Symbol>> headersByOwner, Map<DeclarationSyntax, Symbol> headers,
            List<Symbol> symbols, Map<SyntaxNode, Symbol> declaredSymbols,
            Map<SyntaxNode, SourceFile> syntaxFiles) {
        this.root = root;
        this.declaredTypeHeaders = Map.copyOf(declaredTypeHeaders);
        this.headersByOwner = Map.copyOf(headersByOwner);
        this.headers = identityCopy(headers);
        this.symbols = List.copyOf(symbols);
        this.declaredSymbols = identityCopy(declaredSymbols);
        this.syntaxFiles = identityCopy(syntaxFiles);
    }

    private static <K, V> IdentityHashMap<K, V> identityCopy(Map<K, V> source) {
        IdentityHashMap<K, V> result = new IdentityHashMap<>();
        result.putAll(source);
        return result;
    }

    NamespaceNode root() {
        return root;
    }

    Map<TypeKey, Symbol> declaredTypeHeaders() {
        return declaredTypeHeaders;
    }

    Map<String, List<Symbol>> headersByOwner() {
        return headersByOwner;
    }

    Map<DeclarationSyntax, Symbol> headers() {
        return headers;
    }

    List<Symbol> symbols() {
        return symbols;
    }

    Map<SyntaxNode, Symbol> declaredSymbols() {
        return declaredSymbols;
    }
    
    Map<SyntaxNode, SourceFile> syntaxFiles() {
        return syntaxFiles;
    }
}
