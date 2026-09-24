package vsharp.lsp.server;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import vsharp.compiler.semantics.binding.JavaInterop;
import vsharp.compiler.semantics.binding.SemanticModel;
import vsharp.compiler.semantics.symbols.FieldSymbol;
import vsharp.compiler.semantics.symbols.FunctionSymbol;
import vsharp.compiler.semantics.symbols.NamedTypeSymbol;
import vsharp.compiler.semantics.symbols.ParameterSymbol;
import vsharp.compiler.semantics.symbols.Symbol;
import vsharp.compiler.syntax.SyntaxKind;

/// Renders a class-file type as a readable V# declaration, for go-to-definition.
///
/// A type on the module path has no source to open, so "go to definition" answered nothing at
/// all - on a jar dependency, on the JDK, on everything a real program spends its day calling.
/// Answering nothing is defensible and useless. What a user wants there is what the type
/// offers and how to spell it, which is exactly what can be derived from the class file.
///
/// The output is deliberately a *signature view*, not decompilation: declarations with no
/// bodies, in the spelling V# source must use (`Size`, not `size`), so that reading it tells
/// the user what to type. It is generated on demand and served through a `vsharp-jar:` URI
/// that the extension resolves, which is what lets an editor open a document that exists
/// nowhere on disk.
final class JavaSources {

    /// The URI scheme carrying these documents. Anything but `file:` keeps the editor from
    /// treating them as editable, which they must not be.
    static final String SCHEME = "vsharp-jar";

    /// A rendered type: its text, the line its declaration was written on, and the line each
    /// member was written on.
    record Rendered(String text, int typeLine, Map<String, Integer> memberLines) {}

    private JavaSources() {
        throw new AssertionError("No instances");
    }

    /// The URI of the signature view for `qualifiedName`.
    ///
    /// The `.vs` suffix is what makes the editor colour it with the V# grammar; the qualified
    /// name is the whole path, so the request that serves the content needs no other state.
    static String uriFor(String qualifiedName) {
        return SCHEME + ":/" + qualifiedName + ".vs";
    }

    /// The qualified type name carried by such a URI, or empty when it is not one of ours.
    static Optional<String> typeFromUri(String uri) {
        String prefix = SCHEME + ":/";
        if (!uri.startsWith(prefix) || !uri.endsWith(".vs")) {
            return Optional.empty();
        }
        return Optional.of(uri.substring(prefix.length(), uri.length() - ".vs".length()));
    }

    /// Renders `type` and every member it publishes.
    static Rendered render(NamedTypeSymbol type, SemanticModel model) {
        JavaInterop interop = model.javaInterop();
        List<String> lines = new ArrayList<>();
        Map<String, Integer> memberLines = new LinkedHashMap<>();

        lines.add("// Signature view generated from the class file. Read-only.");
        lines.add("// Members are shown in the spelling V# source must use.");
        lines.add("");

        String qualified = type.qualifiedName();
        int lastDot = qualified.lastIndexOf('.');
        String namespace = lastDot < 0 ? "" : qualified.substring(0, lastDot);
        String simple = lastDot < 0 ? qualified : qualified.substring(lastDot + 1);
        if (!namespace.isEmpty()) {
            lines.add("namespace " + namespace);
            lines.add("{");
        }
        String indent = namespace.isEmpty() ? "    " : "        ";
        String typeIndent = namespace.isEmpty() ? "" : "    ";
        int typeLine = lines.size();
        lines.add(typeIndent + keywordOf(type) + " " + simple);
        lines.add(typeIndent + "{");

        List<Symbol> members = interop.getMembers(type);
        List<String> fields = new ArrayList<>();
        List<String> methods = new ArrayList<>();
        Map<String, String> fieldOwner = new LinkedHashMap<>();
        Map<String, String> methodOwner = new LinkedHashMap<>();
        for (Symbol member : members) {
            String spelling = Completions.spellingOf(member);
            if (spelling.isEmpty() || spelling.startsWith("<")) {
                continue;
            }
            if (member instanceof FieldSymbol field) {
                String text = indent + modifiers(field.modifiers()) + field.type().displayName()
                        + " " + spelling + ";";
                fields.add(text);
                fieldOwner.putIfAbsent(spelling, text);
            } else if (member instanceof FunctionSymbol function) {
                String text = indent + modifiers(function.modifiers())
                        + function.returnType().displayName() + " " + spelling
                        + "(" + parameters(function) + ");";
                methods.add(text);
                methodOwner.putIfAbsent(spelling, text);
            }
        }

        for (String field : fields) {
            lines.add(field);
        }
        if (!fields.isEmpty() && !methods.isEmpty()) {
            lines.add("");
        }
        for (String method : methods) {
            lines.add(method);
        }
        lines.add(typeIndent + "}");
        if (!namespace.isEmpty()) {
            lines.add("}");
        }
        lines.add("");

        // Record where each member's *first* declaration landed, which is where a jump should
        // arrive. Overloads share a name, and the first is as good an anchor as any.
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            for (Map.Entry<String, String> entry : fieldOwner.entrySet()) {
                if (line.equals(entry.getValue())) {
                    memberLines.putIfAbsent(entry.getKey(), index);
                }
            }
            for (Map.Entry<String, String> entry : methodOwner.entrySet()) {
                if (line.equals(entry.getValue())) {
                    memberLines.putIfAbsent(entry.getKey(), index);
                }
            }
        }
        return new Rendered(String.join("\n", lines), typeLine, memberLines);
    }

    private static String keywordOf(NamedTypeSymbol type) {
        return switch (type.declaredKind()) {
            case INTERFACE -> "interface";
            case ENUM -> "enum";
            case RECORD_STRUCT -> "record struct";
            case STRUCT -> "struct";
            case CLASS -> "class";
        };
    }

    private static String parameters(FunctionSymbol function) {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < function.parameters().size(); index++) {
            if (index > 0) {
                text.append(", ");
            }
            ParameterSymbol parameter = function.parameters().get(index);
            text.append(parameter.type().displayName()).append(' ').append(parameter.name());
        }
        return text.toString();
    }

    /// The modifiers worth showing, in canonical order.
    private static String modifiers(List<SyntaxKind> modifiers) {
        StringBuilder text = new StringBuilder();
        for (SyntaxKind kind : List.of(SyntaxKind.PUBLIC, SyntaxKind.STATIC, SyntaxKind.READONLY)) {
            if (modifiers.contains(kind)) {
                text.append(kind.text().orElse("")).append(' ');
            }
        }
        return text.toString();
    }
}
