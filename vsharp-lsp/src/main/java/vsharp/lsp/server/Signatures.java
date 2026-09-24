package vsharp.lsp.server;

import java.util.stream.Collectors;
import vsharp.compiler.semantics.constants.ConstantValue;
import vsharp.compiler.semantics.symbols.ContainerSymbol;
import vsharp.compiler.semantics.symbols.EnumMemberSymbol;
import vsharp.compiler.semantics.symbols.FieldSymbol;
import vsharp.compiler.semantics.symbols.FunctionSymbol;
import vsharp.compiler.semantics.symbols.LocalSymbol;
import vsharp.compiler.semantics.symbols.NamedTypeSymbol;
import vsharp.compiler.semantics.symbols.NamespaceSymbol;
import vsharp.compiler.semantics.symbols.ParameterSymbol;
import vsharp.compiler.semantics.symbols.Symbol;
import vsharp.compiler.semantics.symbols.TypeParameterSymbol;
import vsharp.compiler.syntax.SyntaxKind;

/// Renders a bound symbol as the C# declaration a user would have written.
///
/// Hover is only useful when it shows source-shaped text: a V# author reading
/// `static int Add(int a, int b)` recognises their own declaration, whereas the JVM
/// descriptor would tell them about the backend instead of their program. The renderer is
/// an exhaustive pattern switch over the sealed [Symbol] hierarchy, so a new symbol kind
/// is a compile error here rather than a silently generic hover.
final class Signatures {

    /// Modifiers worth repeating back to the user, in canonical C# order. The full
    /// modifier list would include tokens the binder synthesises, which would show the
    /// user words they did not write.
    private static final java.util.List<SyntaxKind> SHOWN_MODIFIERS = java.util.List.of(
            SyntaxKind.PUBLIC, SyntaxKind.INTERNAL, SyntaxKind.PRIVATE,
            SyntaxKind.STATIC, SyntaxKind.CONST, SyntaxKind.READONLY, SyntaxKind.EXTERN);

    private Signatures() {
        throw new AssertionError("No instances");
    }

    static String of(Symbol symbol) {
        return switch (symbol) {
            case NamespaceSymbol namespace -> "namespace " + namespace.qualifiedName();
            case ContainerSymbol container ->
                    modifiers(container.modifiers()) + "class " + container.qualifiedName();
            case NamedTypeSymbol type -> keywordOf(type) + " " + type.qualifiedName();
            case FunctionSymbol function -> function(function);
            case TypeParameterSymbol parameter -> "type parameter " + parameter.name()
                    + (parameter.constraintSpelling().isEmpty() ? ""
                            : " : " + parameter.constraintSpelling());
            case ParameterSymbol parameter ->
                    parameter.type().displayName() + " " + parameter.name();
            case FieldSymbol field -> field(field);
            case LocalSymbol local -> local(local);
            case EnumMemberSymbol member ->
                    member.qualifiedName() + " = " + member.value();
        };
    }

    private static String keywordOf(NamedTypeSymbol type) {
        return switch (type.declaredKind()) {
            case ENUM -> "enum";
            case STRUCT -> "struct";
            case RECORD_STRUCT -> "record struct";
            // Classes and interfaces only reach V# through Java resolution; naming them
            // by their Java keyword is accurate and tells the user this is interop.
            case CLASS -> "class";
            case INTERFACE -> "interface";
        };
    }

    /// Parameter-passing modifiers, in the order C# writes them. Only these five appear: they
    /// are the ones an author writes on a parameter, and dropping them made hover render a
    /// declaration the user did not write - an `out T value` read as by-value, and an extension
    /// method indistinguishable from a plain static one.
    private static final java.util.List<SyntaxKind> PARAMETER_MODIFIERS = java.util.List.of(
            SyntaxKind.THIS, SyntaxKind.REF, SyntaxKind.OUT, SyntaxKind.IN, SyntaxKind.PARAMS);

    private static String function(FunctionSymbol function) {
        String typeParameters = function.typeParameters().isEmpty() ? ""
                : function.typeParameters().stream()
                        .map(TypeParameterSymbol::name)
                        .collect(Collectors.joining(", ", "<", ">"));
        String parameters = function.parameters().stream()
                .map(Signatures::parameter)
                .collect(Collectors.joining(", "));
        return modifiers(function.modifiers()) + function.returnType().displayName() + " "
                + function.name() + typeParameters + "(" + parameters + ")"
                + constraints(function);
    }

    private static String parameter(ParameterSymbol parameter) {
        StringBuilder text = new StringBuilder();
        for (SyntaxKind modifier : PARAMETER_MODIFIERS) {
            if (parameter.modifiers().contains(modifier)) {
                text.append(modifier.text().orElse("")).append(' ');
            }
        }
        return text.append(parameter.type().displayName()).append(' ')
                .append(parameter.name()).toString();
    }

    /// The `where` clauses the declaration carries, which are enforced and therefore
    /// part of what a caller has to satisfy.
    private static String constraints(FunctionSymbol function) {
        StringBuilder text = new StringBuilder();
        for (TypeParameterSymbol parameter : function.typeParameters()) {
            String constraint = parameter.constraintSpelling();
            if (!constraint.isEmpty()) {
                text.append(" where ").append(parameter.name()).append(" : ").append(constraint);
            }
        }
        return text.toString();
    }

    private static String field(FieldSymbol field) {
        String value = field.constantValue() == null ? ""
                : " = " + constant(field.constantValue());
        return modifiers(field.modifiers()) + field.type().displayName() + " "
                + field.qualifiedName() + value;
    }

    /// Renders a folded constant the way it would be written in source.
    ///
    /// [ConstantValue] variants are records, so their `toString` is the derived
    /// `Int[value=3]` - correct for a debugger and wrong for a hover, which is showing the
    /// user their own declaration. Strings and chars are re-quoted for the same reason: an
    /// unquoted `= abc` reads as a name rather than a value.
    private static String constant(ConstantValue value) {
        return switch (value) {
            case ConstantValue.Null ignored -> "null";
            case ConstantValue.Boolean b -> java.lang.Boolean.toString(b.value());
            case ConstantValue.Int i -> Integer.toString(i.value());
            case ConstantValue.Long l -> l.value() + "L";
            case ConstantValue.Float f -> f.value() + "f";
            case ConstantValue.Double d -> java.lang.Double.toString(d.value());
            case ConstantValue.Decimal d -> d.value() + "m";
            case ConstantValue.Char c -> "'" + c.value() + "'";
            case ConstantValue.StringVal s -> "\"" + s.value() + "\"";
            case ConstantValue.EnumVal e ->
                    e.enumType().displayName() + "(" + e.underlyingValue() + ")";
            // A UTF-8 literal's bytes are not source text; its length is the useful fact.
            case ConstantValue.ByteArray b -> "byte[" + b.value().length + "]";
        };
    }

    private static String local(LocalSymbol local) {
        String prefix = local.constant() ? "const " : "";
        return prefix + local.type().displayName() + " " + local.name();
    }

    private static String modifiers(java.util.List<SyntaxKind> modifiers) {
        String rendered = SHOWN_MODIFIERS.stream()
                .filter(modifiers::contains)
                .map(kind -> kind.text().orElse(kind.display()))
                .collect(Collectors.joining(" "));
        return rendered.isEmpty() ? "" : rendered + " ";
    }
}
