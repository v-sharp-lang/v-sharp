package vsharp.tests.ir;

import java.util.List;
import vsharp.compiler.api.Compilation;
import vsharp.compiler.api.UnitAnalysis;
import vsharp.compiler.diagnostics.DiagnosticBag;
import vsharp.compiler.ir.IrExpression;
import vsharp.compiler.ir.IrStatement;
import vsharp.compiler.ir.StatementLowerer;
import vsharp.compiler.semantics.flow.FlowAnalysis;
import vsharp.compiler.semantics.types.BuiltinType;
import vsharp.compiler.semantics.flow.FlowResult;
import vsharp.compiler.syntax.DeclarationSyntax;
import vsharp.compiler.syntax.StatementSyntax;
import vsharp.testkit.Assert;
import vsharp.testkit.TestRegistry;
import vsharp.testkit.TestSuite;

/// Ensures statement lowering consumes the front end's actual analysis products.
public final class StatementLoweringTests implements TestSuite {

    @Override
    public String suiteName() {
        return "ir.statements";
    }

    @Override
    public void register(TestRegistry registry) {
        registry.test("flow-unreachable statements are absent from lowered blocks",
                this::elidesUnreachableStatements);
        registry.test("core control flow keeps source statement order", this::preservesControlFlow);
        registry.test("exception, resource and checked wrappers stay explicit",
                this::preservesStructuredWrappers);
        registry.test("foreach and switch lower their typed control-flow structure",
                this::lowersIterationAndSelection);
        registry.test("complex patterns lower successfully in expressions", this::lowersComplexPatternsInExpressions);
    }

    private void elidesUnreachableStatements() {
        UnitAnalysis.Analysed analysed = analysed("""
                static class C {
                    static void F() {
                        int x = 1;
                        x += 2;
                        return;
                        int omitted = 3;
                    }
                }
                """);
        StatementSyntax.Block body = method(analysed).body();
        IrStatement.Block lowered = (IrStatement.Block) StatementLowerer.lower(body,
                analysed.file(), analysed.model(), analysed.expressions(), analysed.flow());

        Assert.equal(3L, lowered.statements().size(), "reachable statement count");
        Assert.isTrue(lowered.statements().get(0) instanceof IrStatement.Locals,
                "local declaration retained");
        Assert.isTrue(lowered.statements().get(1) instanceof IrStatement.Expression,
                "compound assignment retained");
        Assert.isTrue(lowered.statements().get(2) instanceof IrStatement.Return,
                "return retained");
        IrStatement.Expression expression = (IrStatement.Expression) lowered.statements().get(1);
        Assert.isTrue(expression.expression() instanceof IrExpression.Store,
                "statement lowering uses expression desugaring");
    }

    private void preservesControlFlow() {
        UnitAnalysis.Analysed analysed = analysed("""
                static class C {
                    static void F(bool enabled) {
                        int x = 1;
                        if (enabled) {
                            x = 2;
                        } else {
                            x = 3;
                        }
                        while (enabled) {
                            break;
                        }
                    }
                }
                """);
        IrStatement.Block lowered = (IrStatement.Block) StatementLowerer.lower(method(analysed).body(),
                analysed.file(), analysed.model(), analysed.expressions(), analysed.flow());
        Assert.equalList(List.of(IrStatement.Locals.class, IrStatement.If.class, IrStatement.While.class),
                lowered.statements().stream().map(Object::getClass).toList(),
                "top-level control-flow order");
    }

    private void preservesStructuredWrappers() {
        UnitAnalysis.Analysed analysed = analysed("""
                using java.lang;
                static class C {
                    static void F(object gate) {
                        checked { int x = 1; }
                        lock (gate) { }
                        try { } catch (Exception e) { } finally { }
                    }
                }
                """);
        IrStatement.Block lowered = (IrStatement.Block) StatementLowerer.lower(method(analysed).body(),
                analysed.file(), analysed.model(), analysed.expressions(), analysed.flow());
        Assert.equalList(List.of(IrStatement.Checked.class, IrStatement.Lock.class,
                        IrStatement.Try.class),
                lowered.statements().stream().map(Object::getClass).toList(),
                "structured wrappers remain visible");
        IrStatement.Try attempt = (IrStatement.Try) lowered.statements().get(2);
        Assert.equal(1L, attempt.catches().size(), "catch retained");
        Assert.notNull(attempt.finallyBody(), "finally retained");
    }

    private void lowersIterationAndSelection() {
        UnitAnalysis.Analysed analysed = analysed("""
                static class C {
                    static void F(int[] values, int value) {
                        foreach (int item in values) { break; }
                        switch (value) {
                            case 1: break;
                            default: break;
                        }
                    }
                }
                """);
        IrStatement.Block lowered = (IrStatement.Block) StatementLowerer.lower(method(analysed).body(),
                analysed.file(), analysed.model(), analysed.expressions(), analysed.flow());
        Assert.equalList(List.of(IrStatement.Foreach.class, IrStatement.Switch.class),
                lowered.statements().stream().map(Object::getClass).toList(),
                "iteration then selection");
        IrStatement.Foreach foreach = (IrStatement.Foreach) lowered.statements().get(0);
        Assert.equalList(List.of("item"), foreach.variables().stream()
                .map(variable -> variable.symbol().name()).toList(), "iteration variable");
        IrStatement.Switch selection = (IrStatement.Switch) lowered.statements().get(1);
        Assert.equal(2L, selection.sections().size(), "switch sections");
        Assert.notNull(selection.sections().get(0).labels().get(0).pattern(),
                "case has a pattern");
        Assert.equal(null, selection.sections().get(1).labels().get(0).pattern(),
                "default has no pattern");
    }

    /// The IR path for a list pattern exists and is exercised here, but binding now rejects
    /// the pattern in a testing position (`VS20002`), because the backend cannot emit it and a
    /// user must get a diagnostic rather than an internal error. So this analyses a unit that
    /// deliberately carries that diagnostic: the lowering it pins is what a future backend
    /// increment will consume, and it must not rot in the meantime.
    private void lowersComplexPatternsInExpressions() {
        UnitAnalysis.Analysed analysed = analysed("""
                static class C {
                    static void F(int[] xs) {
                        bool result = xs is [1, 2, ..];
                    }
                }
                """);
        IrStatement.Block lowered = (IrStatement.Block) StatementLowerer.lower(
                method(analysed).body(), analysed.file(), analysed.model(), analysed.expressions(),
                analysed.flow());
        IrStatement.Locals locals = (IrStatement.Locals) lowered.statements().get(0);
        IrExpression.IsPattern isPattern =
                (IrExpression.IsPattern) locals.locals().get(0).initializer();
        if (!(isPattern.pattern() instanceof vsharp.compiler.ir.IrPattern.ListPattern list)) {
            throw Assert.fail(() -> "expected a list pattern but got " + isPattern.pattern());
        }
        Assert.equal(3L, list.elements().size(), "two elements and a slice");
        Assert.equal(BuiltinType.INT, list.elementType().sourceType(), "element type resolved");
    }

    /// The bound form of a unit that carries diagnostics, for the forms binding reports as
    /// unimplemented while their lowering is already written.
    private static UnitAnalysis.Bound bound(String text) {
        UnitAnalysis unit = Compilation.of(List.of(vsharp.tests.TestSources.styled("Ir.vs", text)))
                .analyze().units().get(0);
        if (unit instanceof UnitAnalysis.Bound boundUnit) {
            return boundUnit;
        }
        throw Assert.fail(() -> "expected a bound unit but got " + unit);
    }

    private static UnitAnalysis.Analysed analysed(String text) {
        UnitAnalysis unit = Compilation.of(List.of(vsharp.tests.TestSources.styled("Ir.vs", text)))
                .analyze().units().get(0);
        if (unit instanceof UnitAnalysis.Analysed analysed) {
            return analysed;
        }
        throw Assert.fail(() -> "expected clean analysed unit but got " + unit);
    }

    private static DeclarationSyntax.Method method(UnitAnalysis.Analysed analysed) {
        DeclarationSyntax.StaticContainer container = (DeclarationSyntax.StaticContainer) analysed
                .syntax().declarations().get(0);
        return (DeclarationSyntax.Method) container.members().get(0);
    }
}
