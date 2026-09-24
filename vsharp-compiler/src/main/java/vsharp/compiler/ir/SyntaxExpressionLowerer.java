package vsharp.compiler.ir;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import vsharp.compiler.semantics.binding.BoundExpression;
import vsharp.compiler.semantics.binding.ExpressionBinding;
import vsharp.compiler.semantics.binding.PositionalLayout;
import vsharp.compiler.semantics.binding.SemanticModel;
import vsharp.compiler.semantics.symbols.NamedTypeSymbol;
import vsharp.compiler.semantics.symbols.LocalSymbol;
import vsharp.compiler.semantics.symbols.Symbol;
import vsharp.compiler.syntax.AuxiliarySyntax;
import vsharp.compiler.syntax.ExpressionSyntax;
import vsharp.compiler.syntax.PatternSyntax;

/// Syntax-aware lowering for forms whose bound node deliberately omits source payload.
///
/// Most expressions lower through [ExpressionLowerer]. Interpolation needs literal text and
/// format clauses, while array initializers need nested brace shape; both are syntax details
/// correctly absent from the general bound-expression algebra.
final class SyntaxExpressionLowerer {

    private final ExpressionBinding expressions;
    private final SemanticModel model;

    private SyntaxExpressionLowerer(ExpressionBinding expressions, SemanticModel model) {
        this.expressions = Objects.requireNonNull(expressions, "expressions");
        this.model = Objects.requireNonNull(model, "model");
    }

    static IrExpression lower(ExpressionSyntax syntax, ExpressionBinding expressions,
            SemanticModel model) {
        return new SyntaxExpressionLowerer(expressions, model).lowerExpression(syntax);
    }

    private IrExpression lowerExpression(ExpressionSyntax syntax) {
        return switch (syntax) {
            case ExpressionSyntax.Interpolated interpolated -> lowerInterpolated(interpolated);
            case ExpressionSyntax.ArrayCreation array -> lowerArrayCreation(array);
            case ExpressionSyntax.ArrayInitializer ignored -> throw new IllegalArgumentException(
                    "array initializer is valid only inside array creation");
            case ExpressionSyntax.Cast cast -> ExpressionLowerer.lower(bound(cast),
                    this::lowerDeferred, model);
            case ExpressionSyntax.Default value -> new IrExpression.DefaultValue(IrValueType.of(
                    bound(value).type()));
            case ExpressionSyntax.As as -> new IrExpression.As(IrValueType.of(bound(as).type()),
                    lowerExpression(as.expression()));
            case ExpressionSyntax.NameOf nameOf -> new IrExpression.NameOf(IrValueType.of(
                    bound(nameOf).type()), nameOfValue(nameOf.expression()));
            case ExpressionSyntax.Checked checked -> new IrExpression.Checked(IrValueType.of(
                    bound(checked).type()), checked.checked(), lowerExpression(checked.expression()));
            case ExpressionSyntax.Range range -> new IrExpression.Range(IrValueType.of(
                    bound(range).type()), range.start() == null ? null : lowerExpression(range.start()),
                    range.end() == null ? null : lowerExpression(range.end()));
            case ExpressionSyntax.Switch switched -> lowerSwitch(switched);
            // A lambda that reached a functional-interface target bound to a value node and
            // lowers through the ordinary bound walk. One that did not was refused in
            // binding, and a compilation with errors never reaches lowering, so a lambda whose
            // bound form is still the deferred placeholder is compiler-internal corruption.
            case ExpressionSyntax.Lambda lambda -> {
                if (!(bound(lambda) instanceof BoundExpression.Lambda)) {
                    throw new IllegalStateException(
                            "an untargeted lambda reached lowering at " + lambda.span());
                }
                yield ExpressionLowerer.lower(bound(lambda), this::lowerDeferred, model);
            }
            case ExpressionSyntax.With with -> lowerWith(with);
            case ExpressionSyntax.IsPattern isPattern -> new IrExpression.IsPattern(IrValueType.of(
                    bound(isPattern).type()), lowerExpression(isPattern.expression()),
                    lowerPattern(isPattern.pattern()));
            // Everything else is fully represented by its bound form. That walk hands any
            // deferred node it meets - a nested interpolated string, `default`, `as`, ... -
            // back here, so a deferred expression is lowerable anywhere one can appear, not
            // only where a statement happens to lower it from syntax.
            default -> ExpressionLowerer.lower(bound(syntax), this::lowerDeferred, model);
        };
    }

    /// `receiver with { X = v }` reads as the positional constructor call the copy really is
    ///: each component either keeps the receiver's value or takes the named replacement.
    /// The shape lives in the declaration's [PositionalLayout], which the IR cannot re-derive.
    private IrExpression lowerWith(ExpressionSyntax.With with) {
        vsharp.compiler.semantics.types.TypeSymbol type = bound(with).type();
        PositionalLayout layout = type instanceof NamedTypeSymbol named
                ? model.positionalLayout(named).orElseThrow(
                        () -> new IllegalStateException("'with' target has no positional layout"))
                : null;
        if (layout == null) {
            throw new IllegalStateException("'with' target is not a record struct");
        }
        Map<String, IrExpression> replacements = new LinkedHashMap<>();
        for (AuxiliarySyntax.VariableDeclarator initializer : with.initializers()) {
            if (initializer.name() == null || initializer.initializer() == null) {
                continue;
            }
            replacements.putIfAbsent(initializer.name(),
                    lowerExpression(initializer.initializer()));
        }
        return new IrExpression.RecordWith(IrValueType.of(type), lowerExpression(with.receiver()),
                layout.constructor(), layout.components(), replacements);
    }

    /// Expression binding has already rejected nameless shapes with VS8081. Syntax is
    /// authoritative here because a qualified type/member can bind to a declaration group,
    /// which intentionally has no executable lowering.
    private static String nameOfValue(ExpressionSyntax expression) {
        return switch (expression) {
            case ExpressionSyntax.Identifier identifier -> identifier.name();
            case ExpressionSyntax.MemberAccess member -> member.name();
            default -> throw new IllegalStateException(
                    "nameof operand was not validated: "
                            + expression.getClass().getSimpleName());
        };
    }

    /// Recovers a deferred node's syntax and lowers it through the syntax-driven path.
    /// Returns `null` when the syntax is not recoverable, leaving the caller to report the
    /// node as unsupported rather than substituting something wrong.
    private IrExpression lowerDeferred(BoundExpression.Deferred deferred) {
        return expressions.syntaxFor(deferred).map(this::lowerExpression).orElse(null);
    }

    private IrExpression.Interpolated lowerInterpolated(ExpressionSyntax.Interpolated interpolated) {
        List<IrInterpolationPart> parts = interpolated.elements().stream().<IrInterpolationPart>map(element -> switch (element) {
            case AuxiliarySyntax.InterpolationElement.Text text -> new IrInterpolationPart.Text(text.value());
            case AuxiliarySyntax.InterpolationElement.Hole hole -> new IrInterpolationPart.Hole(
                    lowerExpression(hole.expression()), hole.alignment() == null ? null
                            : lowerExpression(hole.alignment()), hole.format());
        }).toList();
        return new IrExpression.Interpolated(IrValueType.of(bound(interpolated).type()), parts);
    }

    private IrExpression.ArrayCreation lowerArrayCreation(ExpressionSyntax.ArrayCreation array) {
        return new IrExpression.ArrayCreation(IrValueType.of(bound(array).type()),
                array.dimensions().stream().map(this::lowerExpression).toList(),
                array.initializer().stream().map(this::lowerArrayElement).toList());
    }

    private IrArrayElement lowerArrayElement(ExpressionSyntax syntax) {
        if (syntax instanceof ExpressionSyntax.ArrayInitializer initializer) {
            return new IrArrayElement.Nested(initializer.elements().stream()
                    .map(this::lowerArrayElement).toList());
        }
        return new IrArrayElement.Value(lowerExpression(syntax));
    }

    private IrExpression.Switch lowerSwitch(ExpressionSyntax.Switch switched) {
        return new IrExpression.Switch(IrValueType.of(bound(switched).type()),
                lowerExpression(switched.governing()), switched.arms().stream().map(arm ->
                        new IrSwitchExpressionArm(lowerPattern(arm.pattern()), arm.guard() == null
                                ? null : lowerExpression(arm.guard()), lowerExpression(arm.expression())))
                        .toList());
    }

    private IrPattern lowerPattern(PatternSyntax pattern) {
        return new PatternLowerer(expressions, model, this::lowerExpression).lower(pattern);
    }

    private LocalSymbol local(PatternSyntax pattern) {
        Symbol symbol = model.declaredSymbol(pattern).orElseThrow(
                () -> new IllegalArgumentException("pattern variable has no symbol"));
        if (symbol instanceof LocalSymbol local) return local;
        throw new IllegalArgumentException("pattern symbol is not a local: " + symbol);
    }

    private BoundExpression bound(ExpressionSyntax syntax) {
        return expressions.expressionFor(syntax).orElseThrow(
                () -> new IllegalArgumentException("expression was not bound: " + syntax));
    }

}
