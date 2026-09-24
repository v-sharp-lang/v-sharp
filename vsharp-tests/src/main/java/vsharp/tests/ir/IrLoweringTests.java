package vsharp.tests.ir;

import java.util.List;
import vsharp.compiler.ir.ExpressionLowerer;
import vsharp.compiler.ir.IrBinaryOperator;
import vsharp.compiler.ir.IrExpression;
import vsharp.compiler.ir.IrValueType;
import vsharp.compiler.semantics.binding.BoundExpression;
import vsharp.compiler.semantics.conversions.Conversion;
import vsharp.compiler.semantics.symbols.LocalSymbol;
import vsharp.compiler.semantics.symbols.SourceLocation;
import vsharp.compiler.semantics.types.BuiltinType;
import vsharp.compiler.semantics.types.TypeSymbol;
import vsharp.compiler.source.SourceFile;
import vsharp.compiler.source.SourceSpan;
import vsharp.compiler.syntax.SyntaxKind;
import vsharp.testkit.Assert;
import vsharp.testkit.TestRegistry;
import vsharp.testkit.TestSuite;

/// Structural coverage for the IR boundary before statement lowering is introduced.
public final class IrLoweringTests implements TestSuite {

    private static final SourceFile FILE = SourceFile.of("Ir.vs", "x");
    private static final SourceSpan SPAN = new SourceSpan(0, 1);
    private static final LocalSymbol LOCAL = new LocalSymbol("x", "C.F.x",
            new SourceLocation(FILE, SPAN), BuiltinType.INT, false, false);

    @Override
    public String suiteName() {
        return "ir.lowering";
    }

    @Override
    public void register(TestRegistry registry) {
        registry.test("compile-time arithmetic becomes one typed constant", this::foldsConstant);
        registry.test("conversions remain explicit after lowering", this::keepsConversion);
        registry.test("compound assignment becomes read modify write", this::desugarsCompoundAssignment);
        registry.test("conditional and tuple preserve typed child order", this::preservesCompositeOrder);
        registry.test("unresolved semantic forms cannot enter IR", this::rejectsDeferredForms);
        registry.test("IR values reject unbound semantic types", this::rejectsUnboundTypes);
    }

    private void foldsConstant() {
        BoundExpression expression = new BoundExpression.Binary(SPAN, BuiltinType.INT,
                new BoundExpression.Literal(SPAN, BuiltinType.INT, 40), SyntaxKind.PLUS,
                new BoundExpression.Literal(SPAN, BuiltinType.INT, 2));

        IrExpression result = ExpressionLowerer.lower(expression);
        Assert.equal(new IrExpression.Constant(IrValueType.of(BuiltinType.INT), 42), result,
                "folded arithmetic");
    }

    private void keepsConversion() {
        BoundExpression source = new BoundExpression.Value(SPAN, BuiltinType.INT, LOCAL);
        BoundExpression expression = new BoundExpression.Conversion(SPAN, source,
                BuiltinType.LONG, Conversion.IMPLICIT_NUMERIC);

        IrExpression result = ExpressionLowerer.lower(expression);
        Assert.equal(new IrExpression.Convert(IrValueType.of(BuiltinType.LONG),
                Conversion.IMPLICIT_NUMERIC,
                new IrExpression.Load(IrValueType.of(BuiltinType.INT), LOCAL)), result,
                "numeric conversion is not erased");
    }

    private void desugarsCompoundAssignment() {
        BoundExpression target = new BoundExpression.Value(SPAN, BuiltinType.INT, LOCAL);
        BoundExpression expression = new BoundExpression.Assignment(SPAN, BuiltinType.INT,
                target, SyntaxKind.PLUS_EQUALS,
                new BoundExpression.Literal(SPAN, BuiltinType.INT, 3));

        IrExpression result = ExpressionLowerer.lower(expression);
        IrExpression.Load load = new IrExpression.Load(IrValueType.of(BuiltinType.INT), LOCAL);
        Assert.equal(new IrExpression.Store(IrValueType.of(BuiltinType.INT), LOCAL,
                new IrExpression.Binary(IrValueType.of(BuiltinType.INT), load,
                        IrBinaryOperator.ADD,
                        new IrExpression.Constant(IrValueType.of(BuiltinType.INT), 3))), result,
                "x += 3 desugars to x = x + 3");
    }

    private void preservesCompositeOrder() {
        TypeSymbol.Tuple tupleType = new TypeSymbol.Tuple(List.of(
                new TypeSymbol.TupleElement(BuiltinType.INT, "left"),
                new TypeSymbol.TupleElement(BuiltinType.INT, "right")));
        BoundExpression tuple = new BoundExpression.Tuple(SPAN, tupleType, List.of(
                new BoundExpression.Literal(SPAN, BuiltinType.INT, 1),
                new BoundExpression.Literal(SPAN, BuiltinType.INT, 2)));
        BoundExpression expression = new BoundExpression.Conditional(SPAN, tupleType,
                new BoundExpression.Value(SPAN, BuiltinType.BOOL,
                        new LocalSymbol("enabled", "C.F.enabled", new SourceLocation(FILE, SPAN),
                                BuiltinType.BOOL, false, false)),
                tuple, tuple);

        IrExpression result = ExpressionLowerer.lower(expression);
        if (!(result instanceof IrExpression.Conditional conditional)) {
            throw Assert.fail(() -> "expected conditional IR but got " + result);
        }
        Assert.isTrue(conditional.whenTrue() instanceof IrExpression.Tuple, "tuple true branch");
        IrExpression.Tuple lowered = (IrExpression.Tuple) conditional.whenTrue();
        Assert.equalList(List.of(1, 2), lowered.elements().stream()
                .map(element -> ((IrExpression.Constant) element).value()).toList(),
                "tuple element order");
    }

    private void rejectsDeferredForms() {
        IllegalArgumentException failure = Assert.throwsException(IllegalArgumentException.class,
                () -> ExpressionLowerer.lower(new BoundExpression.Deferred(SPAN, BuiltinType.INT,
                        "array creation", List.of())), "deferred forms are not executable IR");
        Assert.contains(failure.getMessage(), "deferred array creation", "reason names boundary");
    }

    private void rejectsUnboundTypes() {
        Assert.throwsException(IllegalArgumentException.class,
                () -> IrValueType.of(TypeSymbol.Inferred.INSTANCE), "var cannot enter IR");
        Assert.throwsException(IllegalArgumentException.class,
                () -> IrValueType.of(TypeSymbol.Error.INSTANCE), "error cannot enter IR");
    }
}
