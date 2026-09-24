package vsharp.tests.ir;

import java.util.List;
import vsharp.compiler.api.Compilation;
import vsharp.compiler.api.UnitAnalysis;
import vsharp.compiler.ir.IrFunction;
import vsharp.compiler.ir.IrInterpolationPart;
import vsharp.compiler.ir.IrStatement;
import vsharp.compiler.ir.IrUnit;
import vsharp.compiler.ir.UnitLowerer;
import vsharp.testkit.Assert;
import vsharp.testkit.TestRegistry;
import vsharp.testkit.TestSuite;

/// Unit-level IR coverage: callable selection, ordering and implicit-return facts.
public final class UnitLoweringTests implements TestSuite {

    @Override
    public String suiteName() {
        return "ir.units";
    }

    @Override
    public void register(TestRegistry registry) {
        registry.test("unit lowering preserves callable declaration order", this::preservesOrder);
        registry.test("reachable void end point becomes an implicit-return fact",
                this::recordsImplicitReturn);
        registry.test("expression-bodied value callable becomes a return", this::lowersExpressionBody);
        registry.test("local function becomes a separate callable without statement code",
                this::lowersLocalFunction);
        registry.test("interpolation preserves literal text and hole structure",
                this::lowersInterpolation);
        registry.test("array creation preserves dimensions and initializer values",
                this::lowersArrayCreation);
        registry.test("type-directed deferred expressions keep explicit IR forms",
                this::lowersTypeDirectedExpressions);
        registry.test("range and switch expressions receive concrete result IR",
                this::lowersRangeAndSwitchExpressions);
        registry.test("type operators and is patterns keep typed operands",
                this::lowersTypeOperationsAndPatterns);
        registry.test("array-targeted collection expression lowers elements and spreads",
                this::lowersArrayTargetedCollection);
        registry.test("array length lowers as a dedicated verifier operation",
                this::lowersArrayLength);
        registry.test("nested callable captures retain resolved lexical symbols",
                this::recordsNestedCallableCaptures);
        registry.test("unit lowering preserves declared fields", this::preservesDeclaredFields);
    }

    private void preservesOrder() {
        IrUnit unit = lower("""
                static class C {
                    static void First() { }
                    static int Second() => 2;
                }
                """);
        Assert.equalList(List.of("First", "Second"), unit.functions().stream()
                .map(function -> function.symbol().name()).toList(), "function order");
    }

    private void preservesDeclaredFields() {
        IrUnit unit = lower("""
                public struct Storage {
                    public static int Counter;
                    public int Value;
                    static int Touch() => 1;
                }
                """);
        Assert.equalList(List.of("Counter", "Value"), unit.fields().stream()
                .map(field -> field.name()).toList(), "field order");
    }

    private void recordsImplicitReturn() {
        IrFunction.Implemented function = implemented(lower("""
                static class C {
                    static void F() { int x = 1; }
                }
                """).functions().get(0));
        Assert.isTrue(function.appendImplicitReturn(), "void fallthrough requires emitted return");
    }

    private void lowersExpressionBody() {
        IrFunction.Implemented function = implemented(lower("""
                static class C {
                    static int F() => 40 + 2;
                }
                """).functions().get(0));
        Assert.isFalse(function.appendImplicitReturn(), "value body cannot fall through");
        Assert.equal(1L, function.body().statements().size(), "one return statement");
        Assert.isTrue(function.body().statements().get(0) instanceof IrStatement.Return,
                "expression body becomes return");
    }

    private void lowersLocalFunction() {
        IrUnit unit = lower("""
                static class C {
                    static int Outer() {
                        int Local() => 42;
                        return Local();
                    }
                }
                """);
        Assert.equalList(List.of("Outer", "Local"), unit.functions().stream()
                .map(function -> function.symbol().name()).toList(), "outer then local callable");
        IrFunction.Implemented outer = implemented(unit.functions().get(0));
        Assert.isTrue(outer.body().statements().get(0) instanceof IrStatement.Empty,
                "local declaration emits no in-place instruction");
    }

    private void lowersInterpolation() {
        IrFunction.Implemented function = implemented(lower("""
                static class C {
                    static string F(int value) => $"value={value}";
                }
                """).functions().get(0));
        IrStatement.Return returned = (IrStatement.Return) function.body().statements().get(0);
        if (!(returned.expression() instanceof vsharp.compiler.ir.IrExpression.Interpolated text)) {
            throw Assert.fail(() -> "expected interpolated IR but got " + returned.expression());
        }
        Assert.equal(2L, text.parts().size(), "text and hole parts");
        Assert.equal(new IrInterpolationPart.Text("value="), text.parts().get(0), "literal text");
        IrInterpolationPart.Hole hole = (IrInterpolationPart.Hole) text.parts().get(1);
        Assert.notNull(hole.expression(), "hole expression");
        // Alignment and format specifiers are refused at binding, so a lowered hole
        // never carries them; `semantic.memberaccess` covers the refusal.
        Assert.isTrue(hole.format() == null, "no format clause reaches IR");
        Assert.isTrue(hole.alignment() == null, "no alignment reaches IR");
    }

    private void lowersArrayCreation() {
        IrFunction.Implemented function = implemented(lower("""
                static class C {
                    static int[] F() => new int[2] { 1, 2 };
                }
                """).functions().get(0));
        IrStatement.Return returned = (IrStatement.Return) function.body().statements().get(0);
        if (!(returned.expression() instanceof vsharp.compiler.ir.IrExpression.ArrayCreation array)) {
            throw Assert.fail(() -> "expected array IR but got " + returned.expression());
        }
        Assert.equal(1L, array.dimensions().size(), "one dimension");
        Assert.equal(2L, array.initializer().size(), "two initializer values");
    }

    private void lowersTypeDirectedExpressions() {
        IrUnit unit = lower("""
                static class C {
                    static int Cast() => (int)1;
                    static int Default() => default(int);
                    static object As(object value) => value as object;
                    static string Name(int value) => nameof(value);
                    static int Checked() => checked(1 + 2);
                }
                """);
        Assert.equalList(List.of("Cast", "Default", "As", "Name", "Checked"),
                unit.functions().stream().map(function -> function.symbol().name()).toList(),
                "type-directed functions");
        Assert.equalList(List.of(vsharp.compiler.ir.IrExpression.Convert.class,
                        vsharp.compiler.ir.IrExpression.DefaultValue.class,
                        vsharp.compiler.ir.IrExpression.As.class,
                        vsharp.compiler.ir.IrExpression.NameOf.class,
                        vsharp.compiler.ir.IrExpression.Checked.class),
                unit.functions().stream().map(UnitLoweringTests::returnExpression)
                        .map(Object::getClass).toList(), "explicit type-directed forms");
    }

    private void lowersRangeAndSwitchExpressions() {
        IrUnit rangeUnit = lower("""
                static class C {
                    static void Range() { var value = 1..2; }
                }
                """);
        IrStatement.Locals locals = (IrStatement.Locals) implemented(rangeUnit.functions().get(0))
                .body().statements().get(0);
        Assert.isTrue(locals.locals().get(0).initializer()
                instanceof vsharp.compiler.ir.IrExpression.Range, "range initializer form");

        IrUnit switchUnit = lower("""
                static class C {
                    static int Choose(int value) => value switch { 1 => 10, _ => 20 };
                }
                """);
        vsharp.compiler.ir.IrExpression expression = returnExpression(switchUnit.functions().get(0));
        if (!(expression instanceof vsharp.compiler.ir.IrExpression.Switch switched)) {
            throw Assert.fail(() -> "expected switch expression IR but got " + expression);
        }
        Assert.equal(2L, switched.arms().size(), "switch arm count");
        Assert.isTrue(switched.arms().get(0).pattern() instanceof vsharp.compiler.ir.IrPattern.Constant,
                "constant first arm");
        Assert.isTrue(switched.arms().get(1).pattern() instanceof vsharp.compiler.ir.IrPattern.Discard,
                "discard second arm");
    }

    private void lowersTypeOperationsAndPatterns() {
        IrUnit unit = lower("""
                static class C {
                    static int Size() => sizeof(int);
                    static object Type() => typeof(int);
                    static bool Matches(int value) => value is 1;
                }
                """);
        Assert.equalList(List.of(vsharp.compiler.ir.IrExpression.TypeOperation.class,
                        vsharp.compiler.ir.IrExpression.TypeOperation.class,
                        vsharp.compiler.ir.IrExpression.IsPattern.class),
                unit.functions().stream().map(UnitLoweringTests::returnExpression)
                        .map(Object::getClass).toList(), "type and pattern forms");
        vsharp.compiler.ir.IrExpression.TypeOperation sizeof =
                (vsharp.compiler.ir.IrExpression.TypeOperation) returnExpression(unit.functions().get(0));
        Assert.equal(vsharp.compiler.ir.IrTypeOperatorKind.SIZEOF, sizeof.operator(),
                "sizeof operator");
        Assert.equal("int", sizeof.operandType().sourceType().displayName(), "sizeof operand type");
    }

    private void lowersArrayTargetedCollection() {
        IrUnit unit = lower("""
                static class C {
                    static int[] Make(int[] tail) => [1, ..tail, 3];
                }
                """);
        vsharp.compiler.ir.IrExpression expression = returnExpression(unit.functions().get(0));
        if (!(expression instanceof vsharp.compiler.ir.IrExpression.Collection collection)) {
            throw Assert.fail(() -> "expected collection IR but got " + expression);
        }
        Assert.equal(3L, collection.elements().size(), "collection elements");
        Assert.isTrue(collection.elements().get(1).spread(), "middle element spreads");
    }

    private void lowersArrayLength() {
        IrUnit unit = lower("""
                static class C {
                    static int Count(int[] values) => values.Length;
                }
                """);
        vsharp.compiler.ir.IrExpression expression = returnExpression(unit.functions().get(0));
        if (!(expression instanceof vsharp.compiler.ir.IrExpression.ArrayLength length)) {
            throw Assert.fail(() -> "expected array-length IR but got " + expression);
        }
        Assert.equal("int", length.type().sourceType().displayName(), "array length result type");
        Assert.isTrue(length.receiver() instanceof vsharp.compiler.ir.IrExpression.Load,
                "array receiver remains a resolved value load");
    }

    /// Local functions are the capturing callables of the subset: a lambda needs a delegate
    /// type to become a value, which is object-model surface V# omits, so binding refuses one
    /// and only a local function reaches lowering with captures.
    private void recordsNestedCallableCaptures() {
        IrUnit unit = lower("""
                static class C {
                    static void F(int seed) {
                        int offset = 2;
                        int Add(int value) => value + seed + offset;
                    }
                }
                """);
        IrFunction.Implemented lambda = implemented(unit.functions().get(1));
        Assert.equalList(List.of("C.F.seed", "C.F.offset#0"), lambda.captures().stream()
                .map(capture -> capture.symbol().qualifiedName()).toList(),
                "capture order and symbols");
        Assert.equalList(List.of("int", "int"), lambda.captures().stream()
                .map(capture -> capture.type().sourceType().displayName()).toList(),
                "capture types");
    }

    private static IrUnit lower(String text) {
        UnitAnalysis unit = Compilation.of(List.of(vsharp.tests.TestSources.styled("Ir.vs", text)))
                .analyze().units().get(0);
        if (unit instanceof UnitAnalysis.Analysed analysed) {
            return UnitLowerer.lower(analysed);
        }
        throw Assert.fail(() -> "expected analysed unit but got " + unit);
    }

    private static IrFunction.Implemented implemented(IrFunction function) {
        if (function instanceof IrFunction.Implemented implemented) {
            return implemented;
        }
        throw Assert.fail(() -> "expected implemented function but got " + function);
    }

    private static vsharp.compiler.ir.IrExpression returnExpression(IrFunction function) {
        IrStatement.Return returned = (IrStatement.Return) implemented(function).body().statements()
                .get(0);
        return returned.expression();
    }
}
