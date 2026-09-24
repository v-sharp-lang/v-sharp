package vsharp.compiler.semantics.binding;

/// The one spelling that marks a callable as a lambda body, shared by the phases that must
/// agree on it.
///
/// A lambda body is a synthesized callable whose *source* name no program can write, while
/// its qualified name carries the `Enclosing$lambda$n` method name the backend emits. Binding
/// creates it, lowering collects its captures, and the backend orders those captures ahead of
/// the declared parameters because `LambdaMetafactory` requires exactly that. Three phases
/// therefore need the same predicate, and a private constant in one of them would leave the
/// other two matching on a string literal.
public final class LambdaNames {

    /// The `name()` every lambda body's [vsharp.compiler.semantics.symbols.FunctionSymbol]
    /// carries. `<` and `>` keep it unwritable in source and unreachable by name resolution.
    public static final String LAMBDA_NAME = "<lambda>";

    private LambdaNames() {
    }

    /// Whether a callable is a lambda body rather than a declared or local function.
    public static boolean isLambdaBody(
            vsharp.compiler.semantics.symbols.FunctionSymbol symbol) {
        return symbol.synthesized() && LAMBDA_NAME.equals(symbol.name());
    }

    /// The qualified name a lambda body is declared - and emitted - under.
    ///
    /// The obvious `Owner.Fn.<lambda>#0` would name a class `Owner.Fn` that nothing emits,
    /// and `<lambda>` is not a legal JVM method name. `Owner.Fn$lambda$0` is both, and it is
    /// chosen at *declaration* time rather than renamed later, so every local and nested
    /// local function the body declares is qualified under the final name from the start:
    /// capture analysis and the backend, which both decide ownership and class placement by
    /// qualified-name prefix, then never see two spellings of the same callable.
    ///
    /// Nesting composes by construction - a lambda inside a lambda reaches
    /// `Owner.Fn$lambda$0$lambda$1` - because the owner's own name is already final. `$`
    /// cannot appear in a V# identifier, so no source name can collide with the result.
    public static String bodyQualifiedName(String ownerQualifiedName, int ordinal) {
        return ownerQualifiedName + "$" + "lambda" + "$" + ordinal;
    }
}
