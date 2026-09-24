package vsharp.compiler.ir;

import java.util.Objects;
import java.util.function.Function;
import vsharp.compiler.semantics.binding.ExpressionBinding;
import vsharp.compiler.semantics.binding.ListPatternShape;
import vsharp.compiler.semantics.binding.PatternComponent;
import vsharp.compiler.semantics.binding.SemanticModel;
import vsharp.compiler.semantics.symbols.LocalSymbol;
import vsharp.compiler.semantics.symbols.Symbol;
import vsharp.compiler.syntax.ExpressionSyntax;
import vsharp.compiler.syntax.PatternSyntax;

/// Lowers pattern syntax to [IrPattern], shared by statement and expression lowering.
///
/// Both entry points reach patterns - `switch` labels through statements, `is` and switch
/// expressions through expressions - and both need the identical translation, so the two
/// copies that used to exist could drift. They now differ only in how they lower a pattern's
/// embedded expressions, which is the constructor's function.
final class PatternLowerer {

    private final ExpressionBinding expressions;
    private final SemanticModel model;
    private final Function<ExpressionSyntax, IrExpression> lowerExpression;

    PatternLowerer(ExpressionBinding expressions, SemanticModel model,
            Function<ExpressionSyntax, IrExpression> lowerExpression) {
        this.expressions = Objects.requireNonNull(expressions, "expressions");
        this.model = Objects.requireNonNull(model, "model");
        this.lowerExpression = Objects.requireNonNull(lowerExpression, "lowerExpression");
    }

    IrPattern lower(PatternSyntax pattern) {
        return switch (pattern) {
            case PatternSyntax.Missing ignored -> throw new IllegalArgumentException(
                    "missing pattern reached IR lowering");
            case PatternSyntax.Discard ignored -> new IrPattern.Discard();
            case PatternSyntax.Constant constant -> model.patternType(constant)
                    .map(type -> (IrPattern) new IrPattern.Type(IrValueType.of(type), null))
                    .orElseGet(() -> new IrPattern.Constant(
                            lowerExpression.apply(constant.expression())));
            case PatternSyntax.Type typed -> new IrPattern.Type(
                    IrValueType.of(resolvedType(typed.type())),
                    typed.name() == null ? null : local(typed));
            case PatternSyntax.Var variable -> {
                LocalSymbol symbol = local(variable);
                yield new IrPattern.Var(IrValueType.of(boundType(symbol)), symbol);
            }
            case PatternSyntax.Relational relational -> new IrPattern.Relational(
                    IrOperators.binary(relational.operator()),
                    lowerExpression.apply(relational.value()));
            case PatternSyntax.Binary binary -> new IrPattern.Binary(lower(binary.left()),
                    switch (binary.operator()) {
                        case AND -> IrPattern.Binary.Kind.AND;
                        case OR -> IrPattern.Binary.Kind.OR;
                    }, lower(binary.right()));
            case PatternSyntax.Not not -> new IrPattern.Not(lower(not.pattern()));
            case PatternSyntax.Parenthesized parenthesized -> lower(parenthesized.pattern());
            case PatternSyntax.Recursive recursive -> new IrPattern.Recursive(
                    recursive.type() == null ? null
                            : IrValueType.of(resolvedType(recursive.type())),
                    expressions.componentsOf(recursive).stream().map(this::lowerComponent)
                            .toList(),
                    recursive.designation() == null ? null : binding(recursive));
            case PatternSyntax.ListPattern list -> {
                ListPatternShape shape = expressions.shapeOf(list).orElseThrow(
                        () -> new IllegalArgumentException("list pattern was not resolved"));
                yield new IrPattern.ListPattern(IrValueType.of(shape.collectionType()),
                        IrValueType.of(shape.elementType()),
                        list.elements().stream().map(this::lower).toList(),
                        list.designation() == null ? null : binding(list));
            }
            case PatternSyntax.Slice slice -> new IrPattern.Slice(slice.pattern() == null
                    ? new IrPattern.Discard() : lower(slice.pattern()));
        };
    }

    private IrPattern.Component lowerComponent(PatternComponent component) {
        IrPattern.Component.Access access = switch (component.kind()) {
            case TUPLE_ITEM -> IrPattern.Component.Access.TUPLE_ITEM;
            case FIELD -> IrPattern.Component.Access.FIELD;
            case ARRAY_LENGTH -> IrPattern.Component.Access.ARRAY_LENGTH;
            case STRING_LENGTH -> IrPattern.Component.Access.STRING_LENGTH;
            case ACCESSOR -> IrPattern.Component.Access.ACCESSOR;
        };
        return new IrPattern.Component(access, component.index(), component.field(),
                component.accessor(), IrValueType.of(component.type()),
                lower(component.pattern()));
    }

    private vsharp.compiler.semantics.types.TypeSymbol resolvedType(
            vsharp.compiler.syntax.TypeSyntax syntax) {
        return model.typeOf(syntax).orElseThrow(
                () -> new IllegalArgumentException("pattern type was not resolved"));
    }

    /// The type a `var` designation binds. The declaration binder could only mark it
    /// inferred, so the value comes from the binder's inference; a pattern that
    /// reaches lowering has been bound, so the type is present.
    private vsharp.compiler.semantics.types.TypeSymbol boundType(LocalSymbol local) {
        return expressions.effectiveType(local).orElseThrow(
                () -> new IllegalArgumentException("pattern variable has no type: " + local));
    }

    private IrPattern.Binding binding(PatternSyntax pattern) {
        LocalSymbol symbol = local(pattern);
        return new IrPattern.Binding(IrValueType.of(boundType(symbol)), symbol);
    }

    private LocalSymbol local(PatternSyntax pattern) {
        Symbol symbol = model.declaredSymbol(pattern).orElseThrow(
                () -> new IllegalArgumentException("pattern variable has no symbol: " + pattern));
        if (symbol instanceof LocalSymbol local) {
            return local;
        }
        throw new IllegalArgumentException("pattern declaration is not a local: " + symbol);
    }
}
