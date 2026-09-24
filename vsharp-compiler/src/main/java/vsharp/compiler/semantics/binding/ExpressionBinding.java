package vsharp.compiler.semantics.binding;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
import vsharp.compiler.semantics.types.TypeSymbol;
import vsharp.compiler.syntax.ExpressionSyntax;
import vsharp.compiler.syntax.PatternSyntax;

/// Immutable result of binding every reachable expression in a compilation unit.
public final class ExpressionBinding {

    private final IdentityHashMap<ExpressionSyntax, BoundExpression> expressions;
    private final List<BoundExpression> bindingOrder;
    private final IdentityHashMap<LocalSymbol, TypeSymbol> inferredLocalTypes;
    private final IdentityHashMap<BoundExpression, ExpressionSyntax> syntaxByExpression;
    private final IdentityHashMap<PatternSyntax.Recursive, List<PatternComponent>>
            patternComponents;
    private final IdentityHashMap<PatternSyntax.ListPattern, ListPatternShape> listShapes;

    ExpressionBinding(Map<ExpressionSyntax, BoundExpression> expressions,
            List<BoundExpression> bindingOrder,
            Map<LocalSymbol, TypeSymbol> inferredLocalTypes,
            Map<PatternSyntax.Recursive, List<PatternComponent>> patternComponents,
            Map<PatternSyntax.ListPattern, ListPatternShape> listShapes) {
        this.expressions = identityCopy(expressions);
        this.bindingOrder = List.copyOf(bindingOrder);
        this.inferredLocalTypes = identityCopy(inferredLocalTypes);
        this.patternComponents = identityCopy(patternComponents);
        this.listShapes = identityCopy(listShapes);
        this.syntaxByExpression = new IdentityHashMap<>();
        for (Map.Entry<ExpressionSyntax, BoundExpression> entry : this.expressions.entrySet()) {
            this.syntaxByExpression.put(entry.getValue(), entry.getKey());
        }
    }

    private static <K, V> IdentityHashMap<K, V> identityCopy(Map<K, V> source) {
        IdentityHashMap<K, V> result = new IdentityHashMap<>();
        result.putAll(source);
        return result;
    }

    /// Every expression in deterministic post-order.
    public List<BoundExpression> expressions() {
        return bindingOrder;
    }

    /// The syntax a bound expression came from, when it is recoverable.
    ///
    /// Lowering needs this to re-enter the syntax-driven path for
    /// [BoundExpression.Deferred] nodes, whose form is only representable from syntax.
    /// Binding associates syntax and bound nodes by identity, so this inverse is exact for
    /// every node the binder recorded.
    public Optional<ExpressionSyntax> syntaxFor(BoundExpression expression) {
        return Optional.ofNullable(syntaxByExpression.get(
                Objects.requireNonNull(expression, "expression")));
    }

    public Optional<BoundExpression> expressionFor(ExpressionSyntax syntax) {
        return Optional.ofNullable(expressions.get(Objects.requireNonNull(syntax, "syntax")));
    }

    public Optional<TypeSymbol> typeOf(ExpressionSyntax syntax) {
        return expressionFor(syntax).map(BoundExpression::type);
    }

    /// The components a recursive pattern tests, resolved during binding. Empty for
    /// a pattern that failed to resolve, whose diagnostic already stopped the compilation.
    public List<PatternComponent> componentsOf(PatternSyntax.Recursive pattern) {
        return patternComponents.getOrDefault(Objects.requireNonNull(pattern, "pattern"),
                List.of());
    }

    /// The collection and element types a list pattern tests, when it resolved.
    public Optional<ListPatternShape> shapeOf(PatternSyntax.ListPattern pattern) {
        return Optional.ofNullable(listShapes.get(Objects.requireNonNull(pattern, "pattern")));
    }

    public Optional<TypeSymbol> inferredType(LocalSymbol local) {
        return Optional.ofNullable(inferredLocalTypes.get(Objects.requireNonNull(local, "local")));
    }

    /// The value type known after local inference, if this symbol denotes a value.
    public Optional<TypeSymbol> effectiveType(Symbol symbol) {
        Objects.requireNonNull(symbol, "symbol");
        return switch (symbol) {
            case LocalSymbol local -> Optional.ofNullable(inferredLocalTypes.getOrDefault(
                    local, local.type() == TypeSymbol.Inferred.INSTANCE ? null : local.type()));
            case ParameterSymbol parameter -> Optional.of(parameter.type());
            case FieldSymbol field -> Optional.of(field.type());
            case EnumMemberSymbol member -> Optional.of(member.type());
            case NamespaceSymbol ignored -> Optional.empty();
            case ContainerSymbol ignored -> Optional.empty();
            case NamedTypeSymbol ignored -> Optional.empty();
            case FunctionSymbol ignored -> Optional.empty();
            case TypeParameterSymbol ignored -> Optional.empty();
        };
    }
}
