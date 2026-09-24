package vsharp.compiler.ir;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import vsharp.compiler.semantics.symbols.FieldSymbol;
import vsharp.compiler.semantics.symbols.FunctionSymbol;
import vsharp.compiler.semantics.symbols.LocalSymbol;
import vsharp.compiler.semantics.symbols.ParameterSymbol;
import vsharp.compiler.semantics.symbols.Symbol;
import vsharp.compiler.syntax.SyntaxKind;

/// Finds the lexical values a callable obtains from an enclosing callable.
///
/// The walk is over lowered IR, so it cannot mistake an identifier spelling for a declaration
/// and it includes assignment targets as well as reads. Each local function is emitted as its
/// own callable and therefore owns its own direct capture list.
public final class CaptureCollector {

    private final FunctionSymbol function;
    private final Map<Symbol, IrValueType> captures = new LinkedHashMap<>();
    private boolean implicitReceiver;

    private CaptureCollector(FunctionSymbol function) {
        this.function = function;
    }

    static List<IrCapture> collect(FunctionSymbol function, IrStatement.Block body) {
        CaptureCollector collector = new CaptureCollector(function);
        collector.statement(body);
        List<IrCapture> result = new ArrayList<>();
        collector.captures.forEach((symbol, type) -> result.add(new IrCapture(symbol, type)));
        return List.copyOf(result);
    }

    /// Whether the callable reads/writes an instance member without an explicit receiver.
    /// A non-static local function inherits its enclosing receiver in the backend; a static
    /// one is diagnosed as CS8422's V# equivalent before emission.
    public static boolean usesImplicitReceiver(FunctionSymbol function,
            IrStatement.Block body) {
        CaptureCollector collector = new CaptureCollector(function);
        collector.statement(body);
        return collector.implicitReceiver;
    }

    /// Resolves receiver requirements through the local-function call graph. A helper that
    /// merely calls another helper using `this` still needs the receiver; this closure is
    /// also what lets the emission gate reject an explicitly static function before slot 0
    /// can be referenced as though it held `this`.
    public static Set<FunctionSymbol> resolveImplicitReceivers(List<IrFunction> functions) {
        Set<FunctionSymbol> receivers = new LinkedHashSet<>();
        Map<FunctionSymbol, Set<FunctionSymbol>> calls = new LinkedHashMap<>();
        for (IrFunction function : functions) {
            if (!(function instanceof IrFunction.Implemented implemented)) {
                continue;
            }
            if (usesImplicitReceiver(implemented.symbol(), implemented.body())) {
                receivers.add(implemented.symbol());
            }
            Set<FunctionSymbol> called = new LinkedHashSet<>();
            findCalls(implemented.body(), called);
            calls.put(implemented.symbol(), called);
        }

        boolean changed = true;
        while (changed) {
            changed = false;
            for (Map.Entry<FunctionSymbol, Set<FunctionSymbol>> entry : calls.entrySet()) {
                if (!receivers.contains(entry.getKey())
                        && entry.getValue().stream().anyMatch(receivers::contains)) {
                    receivers.add(entry.getKey());
                    changed = true;
                }
            }
        }
        return Set.copyOf(receivers);
    }

    private void statement(IrStatement statement) {
        switch (statement) {
            case IrStatement.Block block -> block.statements().forEach(this::statement);
            case IrStatement.Expression expression -> expression(expression.expression());
            case IrStatement.Locals locals -> locals.locals().forEach(local -> {
                if (local.initializer() != null) expression(local.initializer());
            });
            case IrStatement.If conditional -> {
                expression(conditional.condition());
                statement(conditional.whenTrue());
                if (conditional.whenFalse() != null) statement(conditional.whenFalse());
            }
            case IrStatement.While loop -> { expression(loop.condition()); statement(loop.body()); }
            case IrStatement.DoWhile loop -> { statement(loop.body()); expression(loop.condition()); }
            case IrStatement.For loop -> {
                loop.initializers().forEach(this::statement);
                if (loop.condition() != null) expression(loop.condition());
                loop.iterators().forEach(this::expression);
                statement(loop.body());
            }
            case IrStatement.Return returned -> { if (returned.expression() != null) expression(returned.expression()); }
            case IrStatement.Throw thrown -> { if (thrown.expression() != null) expression(thrown.expression()); }
            case IrStatement.Labeled labeled -> statement(labeled.statement());
            case IrStatement.Try attempt -> {
                statement(attempt.body());
                attempt.catches().forEach(clause -> {
                    if (clause.filter() != null) expression(clause.filter());
                    statement(clause.body());
                });
                if (attempt.finallyBody() != null) statement(attempt.finallyBody());
            }
            case IrStatement.Using using -> {
                statement(using.resource());
                if (using.body() != null) statement(using.body());
            }
            case IrStatement.Lock locked -> { expression(locked.expression()); statement(locked.body()); }
            case IrStatement.Checked checked -> statement(checked.body());
            case IrStatement.Foreach loop -> { expression(loop.collection()); statement(loop.body()); }
            case IrStatement.Switch selection -> {
                expression(selection.governing());
                selection.sections().forEach(section -> {
                    section.labels().forEach(label -> {
                        if (label.pattern() != null) pattern(label.pattern());
                        if (label.guard() != null) expression(label.guard());
                    });
                    section.statements().forEach(this::statement);
                });
            }
            case IrStatement.SwitchGoto jumped -> { if (jumped.value() != null) expression(jumped.value()); }
            case IrStatement.Empty ignored -> { }
            case IrStatement.Break ignored -> { }
            case IrStatement.Continue ignored -> { }
            case IrStatement.Goto ignored -> { }
        }
    }

    private void expression(IrExpression expression) {
        switch (expression) {
            case IrExpression.Await await -> expression(await.operand());
            // A functional value names a separately emitted callable and carries no operand
            // of its own, so it contributes no *direct* capture here: whatever the lambda
            // body reads is collected when that body is walked as its own callable. The
            // creating callable still has to forward those cells, which is an edge
            // [#resolveTransitive] adds rather than a read this walk can see.
            case IrExpression.FunctionalValue ignored -> { }
            case IrExpression.Load load -> {
                capture(load.symbol(), load.type());
                if (load.symbol() instanceof FieldSymbol field
                        && !field.modifiers().contains(SyntaxKind.STATIC)) {
                    implicitReceiver = true;
                }
            }
            case IrExpression.FieldLoad fieldLoad -> expression(fieldLoad.receiver());
            case IrExpression.FieldStore fieldStore -> { expression(fieldStore.receiver()); expression(fieldStore.value()); }
            case IrExpression.Store store -> {
                capture(store.target(), store.type());
                if (store.target() instanceof FieldSymbol field
                        && !field.modifiers().contains(SyntaxKind.STATIC)) {
                    implicitReceiver = true;
                }
                expression(store.value());
            }
            case IrExpression.Unary unary -> expression(unary.operand());
            // The place is both read and written, and it is spelled as its own load, so
            // walking it captures the variable exactly as a read or a write would.
            case IrExpression.Mutate mutate -> expression(mutate.place());
            case IrExpression.Binary binary -> { expression(binary.left()); expression(binary.right()); }
            case IrExpression.Convert convert -> expression(convert.operand());
            case IrExpression.Conditional conditional -> {
                expression(conditional.condition());
                expression(conditional.whenTrue());
                expression(conditional.whenFalse());
            }
            case IrExpression.Call call -> {
                if (call.receiver() != null) expression(call.receiver());
                call.arguments().forEach(this::expression);
                FunctionSymbol declaration = call.declaration();
                if (call.receiver() == null && !declaration.localFunction()
                        && !declaration.modifiers().contains(SyntaxKind.STATIC)) {
                    implicitReceiver = true;
                }
            }
            case IrExpression.ObjectCreation creation -> creation.arguments()
                    .forEach(this::expression);
            case IrExpression.RecordWith with -> {
                expression(with.receiver());
                with.replacements().values().forEach(this::expression);
            }
            case IrExpression.ElementLoad element -> {
                expression(element.receiver());
                element.indices().forEach(this::expression);
            }
            case IrExpression.ElementStore elementStore -> {
                expression(elementStore.receiver());
                elementStore.indices().forEach(this::expression);
                expression(elementStore.value());
            }
            case IrExpression.TupleElementLoad element -> expression(element.receiver());
            case IrExpression.ArrayLength length -> expression(length.receiver());
            case IrExpression.StringLength length -> expression(length.receiver());
            case IrExpression.StringElementLoad element -> {
                expression(element.receiver());
                expression(element.index());
            }
            case IrExpression.IndexElementLoad element -> {
                expression(element.receiver());
                expression(element.index());
            }
            case IrExpression.IndexElementStore store -> {
                expression(store.receiver());
                expression(store.index());
                expression(store.value());
            }
            case IrExpression.SliceElementLoad slice -> {
                expression(slice.receiver());
                expression(slice.range());
            }
            case IrExpression.ConditionalReceiver ignored -> { }
            case IrExpression.NullConditional conditional -> {
                expression(conditional.receiver());
                expression(conditional.access());
            }
            case IrExpression.NullableHasValue hasValue -> expression(hasValue.receiver());
            case IrExpression.NullableValue value -> expression(value.receiver());
            case IrExpression.Tuple tuple -> tuple.elements().forEach(this::expression);
            case IrExpression.TupleDeconstructionStore store -> {
                store.targets().forEach(this::expression);
                expression(store.value());
            }
            case IrExpression.Interpolated text -> text.parts().forEach(part -> {
                if (part instanceof IrInterpolationPart.Hole hole) {
                    expression(hole.expression());
                    if (hole.alignment() != null) expression(hole.alignment());
                }
            });
            case IrExpression.ArrayCreation array -> {
                array.dimensions().forEach(this::expression);
                array.initializer().forEach(this::arrayElement);
            }
            case IrExpression.As as -> expression(as.operand());
            case IrExpression.NameOf ignored -> {
                // Compile-time name only; the semantic operand is never evaluated or captured.
            }
            case IrExpression.Checked checked -> expression(checked.operand());
            case IrExpression.Range range -> {
                if (range.start() != null) expression(range.start());
                if (range.end() != null) expression(range.end());
            }
            case IrExpression.Switch switched -> {
                expression(switched.governing());
                switched.arms().forEach(arm -> {
                    pattern(arm.pattern());
                    if (arm.guard() != null) expression(arm.guard());
                    expression(arm.expression());
                });
            }
            case IrExpression.IsPattern is -> { expression(is.expression()); pattern(is.pattern()); }
            case IrExpression.Collection collection -> collection.elements()
                    .forEach(element -> expression(element.expression()));
            case IrExpression.Constant ignored -> { }
            case IrExpression.DefaultValue ignored -> { }
            case IrExpression.TypeOperation ignored -> { }

        }
    }

    private void arrayElement(IrArrayElement element) {
        switch (element) {
            case IrArrayElement.Value value -> expression(value.expression());
            case IrArrayElement.Nested nested -> nested.elements().forEach(this::arrayElement);
        }
    }

    private void pattern(IrPattern pattern) {
        switch (pattern) {
            case IrPattern.Constant constant -> expression(constant.expression());
            case IrPattern.Relational relational -> expression(relational.value());
            case IrPattern.Binary binary -> { pattern(binary.left()); pattern(binary.right()); }
            case IrPattern.Not not -> pattern(not.pattern());
            case IrPattern.Recursive recursive -> recursive.components()
                    .forEach(component -> pattern(component.pattern()));
            case IrPattern.ListPattern list -> list.elements().forEach(this::pattern);
            case IrPattern.Slice slice -> pattern(slice.pattern());
            case IrPattern.Discard ignored -> { }
            case IrPattern.Type ignored -> { }
            case IrPattern.Var ignored -> { }
        }
    }

    /// A lowering-owned local is named `<...>`, which no source identifier can spell, and it
    /// is always declared in the same callable that reads it. Ownership for those cannot be
    /// decided from the qualified name the way a user local's can, so the spelling is the
    /// rule: a synthetic local is never a capture.
    private void capture(Symbol symbol, IrValueType type) {
        if (symbol.name().startsWith("<")) {
            return;
        }
        if ((symbol instanceof LocalSymbol || symbol instanceof ParameterSymbol)
                && !declaredWithin(function, symbol)) {
            captures.putIfAbsent(symbol, type);
        }
    }

    /// Resolves the values each callable must receive after accounting for calls between
    /// local functions. If G calls H and H reads a variable owned by F, G must accept and
    /// forward F's cell even when G never reads that variable itself.
    public static Map<FunctionSymbol, List<IrCapture>> resolveTransitive(List<IrFunction> functions) {
        Map<FunctionSymbol, Set<Symbol>> captures = new LinkedHashMap<>();
        Map<FunctionSymbol, Map<Symbol, IrValueType>> captureTypes = new LinkedHashMap<>();
        Map<FunctionSymbol, Set<FunctionSymbol>> calls = new LinkedHashMap<>();

        for (IrFunction function : functions) {
            if (function instanceof IrFunction.Implemented implemented) {
                Set<Symbol> directCaptures = new LinkedHashSet<>();
                Map<Symbol, IrValueType> types = new LinkedHashMap<>();
                for (IrCapture capture : implemented.captures()) {
                    directCaptures.add(capture.symbol());
                    types.put(capture.symbol(), capture.type());
                }
                captures.put(function.symbol(), directCaptures);
                captureTypes.put(function.symbol(), types);

                Set<FunctionSymbol> called = new LinkedHashSet<>();
                findCalls(implemented.body(), called);
                calls.put(function.symbol(), called);
            }
        }

        boolean changed = true;
        while (changed) {
            changed = false;
            for (Map.Entry<FunctionSymbol, Set<FunctionSymbol>> entry : calls.entrySet()) {
                FunctionSymbol caller = entry.getKey();
                Set<Symbol> callerCaptures = captures.get(caller);
                Map<Symbol, IrValueType> callerTypes = captureTypes.get(caller);
                for (FunctionSymbol callee : entry.getValue()) {
                    Set<Symbol> calleeCaptures = captures.get(callee);
                    if (calleeCaptures == null) {
                        continue;
                    }
                    for (Symbol capture : calleeCaptures) {
                        if (!declaredWithin(caller, capture)
                                && callerCaptures.add(capture)) {
                            callerTypes.put(capture,
                                    captureTypes.get(callee).get(capture));
                            changed = true;
                        }
                    }
                }
            }
        }

        Map<FunctionSymbol, List<IrCapture>> result = new LinkedHashMap<>();
        for (FunctionSymbol function : captures.keySet()) {
            List<IrCapture> list = new ArrayList<>();
            for (Symbol capture : captures.get(function)) {
                list.add(new IrCapture(capture,
                        captureTypes.get(function).get(capture)));
            }
            result.put(function, List.copyOf(list));
        }
        return Map.copyOf(result);
    }

    /// Whether a callable owns a variable, decided by qualified-name prefix. A lambda body
    /// has one spelling to match against, because its emitted name is the name its
    /// declaration was bound with.
    private static boolean declaredWithin(FunctionSymbol function, Symbol symbol) {
        return symbol.qualifiedName().startsWith(function.qualifiedName() + ".");
    }

    private static void findCalls(IrStatement statement, Set<FunctionSymbol> called) {
        switch (statement) {
            case IrStatement.Block block -> block.statements().forEach(s -> findCalls(s, called));
            case IrStatement.Expression expression -> findCalls(expression.expression(), called);
            case IrStatement.Locals locals -> locals.locals().forEach(local -> {
                if (local.initializer() != null) findCalls(local.initializer(), called);
            });
            case IrStatement.If conditional -> {
                findCalls(conditional.condition(), called);
                findCalls(conditional.whenTrue(), called);
                if (conditional.whenFalse() != null) findCalls(conditional.whenFalse(), called);
            }
            case IrStatement.While loop -> { findCalls(loop.condition(), called); findCalls(loop.body(), called); }
            case IrStatement.DoWhile loop -> { findCalls(loop.body(), called); findCalls(loop.condition(), called); }
            case IrStatement.For loop -> {
                loop.initializers().forEach(s -> findCalls(s, called));
                if (loop.condition() != null) findCalls(loop.condition(), called);
                loop.iterators().forEach(e -> findCalls(e, called));
                findCalls(loop.body(), called);
            }
            case IrStatement.Return returned -> { if (returned.expression() != null) findCalls(returned.expression(), called); }
            case IrStatement.Throw thrown -> { if (thrown.expression() != null) findCalls(thrown.expression(), called); }
            case IrStatement.Labeled labeled -> findCalls(labeled.statement(), called);
            case IrStatement.Try attempt -> {
                findCalls(attempt.body(), called);
                attempt.catches().forEach(clause -> {
                    if (clause.filter() != null) findCalls(clause.filter(), called);
                    findCalls(clause.body(), called);
                });
                if (attempt.finallyBody() != null) findCalls(attempt.finallyBody(), called);
            }
            case IrStatement.Using using -> {
                findCalls(using.resource(), called);
                if (using.body() != null) findCalls(using.body(), called);
            }
            case IrStatement.Lock locked -> { findCalls(locked.expression(), called); findCalls(locked.body(), called); }
            case IrStatement.Checked checked -> findCalls(checked.body(), called);
            case IrStatement.Foreach loop -> { findCalls(loop.collection(), called); findCalls(loop.body(), called); }
            case IrStatement.Switch selection -> {
                findCalls(selection.governing(), called);
                selection.sections().forEach(section -> {
                    section.labels().forEach(label -> {
                        if (label.pattern() != null) findCalls(label.pattern(), called);
                        if (label.guard() != null) findCalls(label.guard(), called);
                    });
                    section.statements().forEach(s -> findCalls(s, called));
                });
            }
            case IrStatement.SwitchGoto jumped -> { if (jumped.value() != null) findCalls(jumped.value(), called); }
            case IrStatement.Empty ignored -> { }
            case IrStatement.Break ignored -> { }
            case IrStatement.Continue ignored -> { }
            case IrStatement.Goto ignored -> { }
        }
    }

    private static void findCalls(IrExpression expression, Set<FunctionSymbol> called) {
        switch (expression) {
            case IrExpression.Await await -> findCalls(await.operand(), called);
            // Creating a lambda is an edge in this graph exactly as calling one is.
            // `LambdaMetafactory` is handed the captured cells at the *creation* site, so a
            // callable that creates a lambda must already hold every cell that lambda reads -
            // including the ones it only reads because a lambda nested inside it does. A
            // lambda nested in a lambda, capturing a parameter of the method around both, is
            // the shape that proves it.
            case IrExpression.FunctionalValue functional ->
                    called.add(functional.implementation());
            case IrExpression.Call call -> {
                if (call.receiver() != null) findCalls(call.receiver(), called);
                call.arguments().forEach(e -> findCalls(e, called));
                if (call.declaration() != null) {
                    called.add(call.declaration());
                } else if (call.function() != null) {
                    called.add(call.function());
                }
            }
            case IrExpression.FieldLoad fieldLoad -> findCalls(fieldLoad.receiver(), called);
            case IrExpression.FieldStore fieldStore -> { findCalls(fieldStore.receiver(), called); findCalls(fieldStore.value(), called); }
            case IrExpression.Store store -> findCalls(store.value(), called);
            case IrExpression.Unary unary -> findCalls(unary.operand(), called);
            case IrExpression.Mutate mutate -> findCalls(mutate.place(), called);
            case IrExpression.Binary binary -> { findCalls(binary.left(), called); findCalls(binary.right(), called); }
            case IrExpression.Convert convert -> findCalls(convert.operand(), called);
            case IrExpression.Conditional conditional -> {
                findCalls(conditional.condition(), called);
                findCalls(conditional.whenTrue(), called);
                findCalls(conditional.whenFalse(), called);
            }
            case IrExpression.ObjectCreation creation -> creation.arguments().forEach(e -> findCalls(e, called));
            case IrExpression.RecordWith with -> {
                findCalls(with.receiver(), called);
                with.replacements().values().forEach(e -> findCalls(e, called));
            }
            case IrExpression.ElementLoad element -> {
                findCalls(element.receiver(), called);
                element.indices().forEach(e -> findCalls(e, called));
            }
            case IrExpression.ElementStore elementStore -> {
                findCalls(elementStore.receiver(), called);
                elementStore.indices().forEach(e -> findCalls(e, called));
                findCalls(elementStore.value(), called);
            }
            case IrExpression.TupleElementLoad element -> findCalls(element.receiver(), called);
            case IrExpression.ArrayLength length -> findCalls(length.receiver(), called);
            case IrExpression.StringLength length -> findCalls(length.receiver(), called);
            case IrExpression.StringElementLoad element -> { findCalls(element.receiver(), called); findCalls(element.index(), called); }
            case IrExpression.IndexElementLoad element -> { findCalls(element.receiver(), called); findCalls(element.index(), called); }
            case IrExpression.IndexElementStore store -> { findCalls(store.receiver(), called); findCalls(store.index(), called); findCalls(store.value(), called); }
            case IrExpression.SliceElementLoad slice -> { findCalls(slice.receiver(), called); findCalls(slice.range(), called); }
            case IrExpression.NullConditional conditional -> { findCalls(conditional.receiver(), called); findCalls(conditional.access(), called); }
            case IrExpression.NullableHasValue hasValue -> findCalls(hasValue.receiver(), called);
            case IrExpression.NullableValue value -> findCalls(value.receiver(), called);
            case IrExpression.Tuple tuple -> tuple.elements().forEach(e -> findCalls(e, called));
            case IrExpression.TupleDeconstructionStore store -> { store.targets().forEach(e -> findCalls(e, called)); findCalls(store.value(), called); }
            case IrExpression.Interpolated text -> text.parts().forEach(part -> {
                if (part instanceof IrInterpolationPart.Hole hole) {
                    findCalls(hole.expression(), called);
                    if (hole.alignment() != null) findCalls(hole.alignment(), called);
                }
            });
            case IrExpression.ArrayCreation array -> {
                array.dimensions().forEach(e -> findCalls(e, called));
                array.initializer().forEach(element -> findCalls(element, called));
            }
            case IrExpression.As as -> findCalls(as.operand(), called);
            case IrExpression.Checked checked -> findCalls(checked.operand(), called);
            case IrExpression.Range range -> {
                if (range.start() != null) findCalls(range.start(), called);
                if (range.end() != null) findCalls(range.end(), called);
            }
            case IrExpression.Switch switched -> {
                findCalls(switched.governing(), called);
                switched.arms().forEach(arm -> {
                    findCalls(arm.pattern(), called);
                    if (arm.guard() != null) findCalls(arm.guard(), called);
                    findCalls(arm.expression(), called);
                });
            }
            case IrExpression.IsPattern is -> { findCalls(is.expression(), called); findCalls(is.pattern(), called); }
            case IrExpression.Collection collection -> collection.elements().forEach(e -> findCalls(e.expression(), called));
            case IrExpression.Load ignored -> { }
            case IrExpression.ConditionalReceiver ignored -> { }
            case IrExpression.NameOf ignored -> { }
            case IrExpression.Constant ignored -> { }
            case IrExpression.DefaultValue ignored -> { }
            case IrExpression.TypeOperation ignored -> { }
        }
    }

    private static void findCalls(IrPattern pattern, Set<FunctionSymbol> called) {
        switch (pattern) {
            case IrPattern.Constant constant -> findCalls(constant.expression(), called);
            case IrPattern.Relational relational -> findCalls(relational.value(), called);
            case IrPattern.Binary binary -> { findCalls(binary.left(), called); findCalls(binary.right(), called); }
            case IrPattern.Not not -> findCalls(not.pattern(), called);
            case IrPattern.Recursive recursive -> recursive.components().forEach(component -> findCalls(component.pattern(), called));
            case IrPattern.ListPattern list -> list.elements().forEach(e -> findCalls(e, called));
            case IrPattern.Slice slice -> findCalls(slice.pattern(), called);
            case IrPattern.Discard ignored -> { }
            case IrPattern.Type ignored -> { }
            case IrPattern.Var ignored -> { }
        }
    }

    private static void findCalls(IrArrayElement element, Set<FunctionSymbol> called) {
        switch (element) {
            case IrArrayElement.Value value -> findCalls(value.expression(), called);
            case IrArrayElement.Nested nested -> nested.elements().forEach(e -> findCalls(e, called));
        }
    }
}
