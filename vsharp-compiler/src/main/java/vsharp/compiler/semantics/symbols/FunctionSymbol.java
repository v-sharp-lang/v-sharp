package vsharp.compiler.semantics.symbols;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import vsharp.compiler.semantics.types.TypeSymbol;
import vsharp.compiler.syntax.SyntaxKind;

/// A method, local function, operator or synthesized top-level entry function.
///
/// `interfaceOwner` marks a member discovered on a Java interface. The JVM encodes the two
/// kinds of owner in different constant-pool entries, so it decides an opcode rather than a
/// detail: a static interface method invoked through a `Methodref` is rejected at run time,
/// exactly as an interface instance method invoked with `invokevirtual` is.
public record FunctionSymbol(String name, String qualifiedName, SourceLocation location,
        TypeSymbol returnType, List<TypeParameterSymbol> typeParameters,
        List<ParameterSymbol> parameters, List<SyntaxKind> modifiers,
        boolean localFunction, boolean synthesized, boolean interfaceOwner) implements Symbol {

    /// A function whose owner is not a Java interface: every V# declaration, and any member
    /// discovered on a Java class.
    public FunctionSymbol(String name, String qualifiedName, SourceLocation location,
            TypeSymbol returnType, List<TypeParameterSymbol> typeParameters,
            List<ParameterSymbol> parameters, List<SyntaxKind> modifiers,
            boolean localFunction, boolean synthesized) {
        this(name, qualifiedName, location, returnType, typeParameters, parameters, modifiers,
                localFunction, synthesized, false);
    }

    public FunctionSymbol {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(qualifiedName, "qualifiedName");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(returnType, "returnType");
        typeParameters = List.copyOf(typeParameters);
        parameters = List.copyOf(parameters);
        modifiers = List.copyOf(modifiers);
    }

    @Override
    public SymbolKind kind() {
        return SymbolKind.FUNCTION;
    }

    /// Whether this callable was declared `async`.
    ///
    /// An async callable's [#returnType] is the `Task` its *caller* receives, while its body
    /// produces that task's result type. Every stage that reads one of the two must know
    /// which it wants, so the distinction is asked for explicitly rather than inferred.
    ///
    /// @return whether the `async` modifier was written
    public boolean isAsync() {
        return modifiers.contains(SyntaxKind.ASYNC);
    }

    /// Whether this callable is a C# extension method: a `static` method whose first
    /// parameter carries the `this` modifier (§12.8.10.3).
    ///
    /// The shape is the whole definition. An extension method is an ordinary static method
    /// in every other respect - it is emitted, invoked and interoperated with as one - and
    /// the modifier only changes which *call syntax* may reach it, so nothing past binding
    /// needs to know that a call was written in receiver form.
    ///
    /// @return whether the first parameter was declared `this`
    public boolean isExtension() {
        return !parameters.isEmpty()
                && parameters.getFirst().modifiers().contains(SyntaxKind.THIS)
                && modifiers.contains(SyntaxKind.STATIC);
    }

    /// The receiver type an extension method extends.
    ///
    /// @return the first parameter's type, or empty when this is not an extension method
    public Optional<TypeSymbol> extendedType() {
        return isExtension() ? Optional.of(parameters.getFirst().type()) : Optional.empty();
    }

    /// Stable overload identity excluding the containing declaration.
    public String signature() {
        String parameterTypes = parameters.stream().map(FunctionSymbol::parameterSignature)
                .collect(Collectors.joining(","));
        return name + "`" + typeParameters.size() + "(" + parameterTypes + ")";
    }

    private static String parameterSignature(ParameterSymbol parameter) {
        String passing = parameter.modifiers().stream()
                .filter(kind -> kind == SyntaxKind.REF || kind == SyntaxKind.OUT
                        || kind == SyntaxKind.IN)
                .map(SyntaxKind::display)
                .findFirst()
                .map(value -> value + " ")
                .orElse("");
        return passing + parameter.type().displayName();
    }
}
