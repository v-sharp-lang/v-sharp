package vsharp.tests.semantics;

import java.util.List;
import vsharp.compiler.diagnostics.DiagnosticBag;
import vsharp.compiler.semantics.binding.BoundExpression;
import vsharp.compiler.semantics.binding.DeclarationBinder;
import vsharp.compiler.semantics.binding.ExpressionBinder;
import vsharp.compiler.semantics.binding.ExpressionBinding;
import vsharp.compiler.semantics.binding.SemanticModel;
import vsharp.compiler.semantics.overloads.OverloadResolver;
import vsharp.compiler.semantics.overloads.OverloadResult;
import vsharp.compiler.semantics.symbols.FunctionSymbol;
import vsharp.compiler.semantics.symbols.LocalSymbol;
import vsharp.compiler.semantics.symbols.ParameterSymbol;
import vsharp.compiler.semantics.symbols.SourceLocation;
import vsharp.compiler.semantics.types.BuiltinType;
import vsharp.compiler.semantics.types.TypeSymbol;
import vsharp.compiler.source.SourceFile;
import vsharp.compiler.source.SourceSpan;
import vsharp.compiler.syntax.AuxiliarySyntax;
import vsharp.compiler.syntax.ExpressionSyntax;
import vsharp.compiler.syntax.LiteralType;
import vsharp.compiler.syntax.Parser;
import vsharp.compiler.syntax.SyntaxKind;
import vsharp.compiler.syntax.SyntaxToken;
import vsharp.testkit.Assert;
import vsharp.testkit.TestRegistry;
import vsharp.testkit.TestSuite;

public final class OverloadTests implements TestSuite {

    private final SourceLocation dummyLoc = new SourceLocation(
            SourceFile.of("test.vs", ""), new SourceSpan(0, 1));

    @Override
    public String suiteName() {
        return "semantic.overloads";
    }

    @Override
    public void register(TestRegistry registry) {
        registry.test("exact match vs widening overload", this::exactMatchVsWidening);
        registry.test("multiple argument betterness ranking", this::multipleArgumentBetterness);
        registry.test("named argument overload matching", this::namedArgumentMatching);
        registry.test("params array normal vs expanded ranking", this::paramsArrayRanking);
        registry.test("a single params element expands at equal arity", this::paramsSingleElementExpansion);
        registry.test("ref out parameter matching and failures", this::refOutParameterMatching);
        registry.test("ambiguous call detection", this::ambiguousCallDetection);
        registry.test("argument count mismatch failure", this::argumentCountMismatch);
        registry.test("optional parameters make a candidate applicable",
                this::optionalParameterApplicability);
        registry.test("a candidate needing no defaults wins an otherwise tied overload",
                this::nonDefaultedCandidateWinsTie);
        registry.test("generic constructed type inference", this::genericConstructedTypeInference);
    }

    private FunctionSymbol createFunction(String name, TypeSymbol returnType, ParameterSymbol... parameters) {
        return new FunctionSymbol(
                name, name, dummyLoc, returnType, List.of(), List.of(parameters),
                List.of(), false, false);
    }

    private ParameterSymbol createParam(String name, TypeSymbol type, int ordinal, SyntaxKind... modifiers) {
        return new ParameterSymbol(name, name, dummyLoc, type, ordinal, List.of(modifiers), null);
    }

    private ParameterSymbol createOptionalIntParam(String name, int ordinal, int value) {
        SyntaxToken token = SyntaxToken.literal(SyntaxKind.INTEGER_LITERAL,
                new SourceSpan(0, 1), Integer.toString(value), LiteralType.INT, value, false);
        ExpressionSyntax defaultValue = new ExpressionSyntax.Literal(
                new SourceSpan(0, 1), token);
        return new ParameterSymbol(name, name, dummyLoc, BuiltinType.INT, ordinal, List.of(),
                defaultValue);
    }

    private BoundExpression createLiteral(TypeSymbol type, Object val) {
        return new BoundExpression.Literal(new SourceSpan(0, 1), type, val);
    }

    private AuxiliarySyntax.Argument createArg(SyntaxKind kind, String text, LiteralType litType, Object val, String name, SyntaxKind modifier) {
        SyntaxToken token = SyntaxToken.literal(kind, new SourceSpan(0, 1), text, litType, val, false);
        return new AuxiliarySyntax.Argument(new SourceSpan(0, 1), name, modifier,
                new vsharp.compiler.syntax.ExpressionSyntax.Literal(new SourceSpan(0, 1), token));
    }

    private void exactMatchVsWidening() {
        FunctionSymbol fInt = createFunction("M", BuiltinType.VOID, createParam("x", BuiltinType.INT, 0));
        FunctionSymbol fDouble = createFunction("M", BuiltinType.VOID, createParam("x", BuiltinType.DOUBLE, 0));

        BoundExpression argInt = createLiteral(BuiltinType.INT, 42);
        AuxiliarySyntax.Argument argSyn = createArg(SyntaxKind.INTEGER_LITERAL, "42", LiteralType.INT, 42, null, null);

        OverloadResult result = OverloadResolver.resolve(
                List.of(fInt, fDouble), List.of(argInt), List.of(argSyn));

        Assert.isTrue(result instanceof OverloadResult.Success, "resolution should succeed");
        OverloadResult.Success success = (OverloadResult.Success) result;
        Assert.equal("M", success.candidate().function().name(), "function name");
        Assert.equal(BuiltinType.INT, success.candidate().function().parameters().get(0).type(), "best match is M(int)");
    }

    private void multipleArgumentBetterness() {
        FunctionSymbol f1 = createFunction("M", BuiltinType.VOID,
                createParam("x", BuiltinType.INT, 0), createParam("y", BuiltinType.DOUBLE, 1));
        FunctionSymbol f2 = createFunction("M", BuiltinType.VOID,
                createParam("x", BuiltinType.DOUBLE, 0), createParam("y", BuiltinType.INT, 1));

        BoundExpression arg1 = createLiteral(BuiltinType.INT, 1);
        BoundExpression arg2 = createLiteral(BuiltinType.INT, 2);

        AuxiliarySyntax.Argument syn1 = createArg(SyntaxKind.INTEGER_LITERAL, "1", LiteralType.INT, 1, null, null);
        AuxiliarySyntax.Argument syn2 = createArg(SyntaxKind.INTEGER_LITERAL, "2", LiteralType.INT, 2, null, null);

        OverloadResult result = OverloadResolver.resolve(
                List.of(f1, f2), List.of(arg1, arg2), List.of(syn1, syn2));

        Assert.isTrue(result instanceof OverloadResult.Ambiguous, "M(int, double) vs M(double, int) with (int, int) should be ambiguous");
    }

    private void namedArgumentMatching() {
        FunctionSymbol f = createFunction("M", BuiltinType.VOID,
                createParam("x", BuiltinType.INT, 0), createParam("y", BuiltinType.STRING, 1));

        BoundExpression argY = createLiteral(BuiltinType.STRING, "hello");
        BoundExpression argX = createLiteral(BuiltinType.INT, 10);

        AuxiliarySyntax.Argument synY = createArg(SyntaxKind.STRING_LITERAL, "\"hello\"", LiteralType.STRING, "hello", "y", null);
        AuxiliarySyntax.Argument synX = createArg(SyntaxKind.INTEGER_LITERAL, "10", LiteralType.INT, 10, "x", null);

        OverloadResult result = OverloadResolver.resolve(
                List.of(f), List.of(argY, argX), List.of(synY, synX));

        Assert.isTrue(result instanceof OverloadResult.Success, "named arguments y: 'hello', x: 10 should resolve M(int x, string y)");
    }

    private void paramsArrayRanking() {
        TypeSymbol intArray = new TypeSymbol.Array(BuiltinType.INT, List.of(1));
        FunctionSymbol fNormal = createFunction("M", BuiltinType.VOID, createParam("arr", intArray, 0, SyntaxKind.PARAMS));

        BoundExpression argArr = createLiteral(intArray, new int[]{1, 2});
        AuxiliarySyntax.Argument synArr = createArg(SyntaxKind.INTEGER_LITERAL, "0", LiteralType.INT, 0, null, null);

        OverloadResult result = OverloadResolver.resolve(
                List.of(fNormal), List.of(argArr), List.of(synArr));

        Assert.isTrue(result instanceof OverloadResult.Success, "normal array argument should resolve params parameter");
        OverloadResult.Success success = (OverloadResult.Success) result;
        Assert.isFalse(success.candidate().expandedParams(), "should choose normal params form over expanded");
    }

    /// One argument against one `params` parameter is the case where argument count
    /// alone cannot pick a form: an array takes the normal form, a single element
    /// takes the expanded one. C# 12.6.4.2 requires both to be attempted, so
    /// `Concat("a")` and `Split(',')` must resolve rather than report VS1503.
    private void paramsSingleElementExpansion() {
        TypeSymbol intArray = new TypeSymbol.Array(BuiltinType.INT, List.of(1));
        FunctionSymbol f = createFunction("M", BuiltinType.VOID, createParam("arr", intArray, 0, SyntaxKind.PARAMS));

        BoundExpression element = createLiteral(BuiltinType.INT, 1);
        AuxiliarySyntax.Argument syn = createArg(SyntaxKind.INTEGER_LITERAL, "1", LiteralType.INT, 0, null, null);

        OverloadResult result = OverloadResolver.resolve(List.of(f), List.of(element), List.of(syn));

        Assert.isTrue(result instanceof OverloadResult.Success,
                "a lone element argument should expand into the params array");
        Assert.isTrue(((OverloadResult.Success) result).candidate().expandedParams(),
                "the single element must select the expanded form");

        // An element that converts to neither the array nor its element type reports
        // the expanded failure, naming the element type the way C# does.
        BoundExpression wrong = createLiteral(BuiltinType.STRING, "no");
        AuxiliarySyntax.Argument synWrong = createArg(SyntaxKind.STRING_LITERAL, "\"no\"", LiteralType.STRING, 0, null, null);

        OverloadResult failed = OverloadResolver.resolve(List.of(f), List.of(wrong), List.of(synWrong));

        Assert.isTrue(failed instanceof OverloadResult.Failure, "string argument matches neither form");
        Assert.equal(OverloadResult.FailureReason.CANNOT_CONVERT_ARGUMENT,
                ((OverloadResult.Failure) failed).reason(), "CANNOT_CONVERT_ARGUMENT expected");
        Assert.equal("int", ((OverloadResult.Failure) failed).expectedType(),
                "the expanded form names the element type");
    }

    private void refOutParameterMatching() {
        FunctionSymbol fRef = createFunction("M", BuiltinType.VOID, createParam("x", BuiltinType.INT, 0, SyntaxKind.REF));

        LocalSymbol localX = new LocalSymbol("x", "x", dummyLoc, BuiltinType.INT, false, false);
        BoundExpression valX = new BoundExpression.Value(new SourceSpan(0, 1), BuiltinType.INT, localX);

        AuxiliarySyntax.Argument synRef = createArg(SyntaxKind.INTEGER_LITERAL, "0", LiteralType.INT, 0, null, SyntaxKind.REF);

        OverloadResult result = OverloadResolver.resolve(
                List.of(fRef), List.of(valX), List.of(synRef));

        Assert.isTrue(result instanceof OverloadResult.Success, "ref variable argument should match ref int parameter");

        AuxiliarySyntax.Argument synNoRef = createArg(SyntaxKind.INTEGER_LITERAL, "0", LiteralType.INT, 0, null, null);

        OverloadResult failResult = OverloadResolver.resolve(
                List.of(fRef), List.of(valX), List.of(synNoRef));

        Assert.isTrue(failResult instanceof OverloadResult.Failure, "missing ref modifier should fail");
        OverloadResult.Failure failure = (OverloadResult.Failure) failResult;
        Assert.equal(OverloadResult.FailureReason.REF_OUT_MODIFIER_MISSING, failure.reason(), "REF_OUT_MODIFIER_MISSING expected");
    }

    private void ambiguousCallDetection() {
        FunctionSymbol f1 = createFunction("M", BuiltinType.VOID, createParam("x", BuiltinType.LONG, 0));
        FunctionSymbol f2 = createFunction("M", BuiltinType.VOID, createParam("x", BuiltinType.DOUBLE, 0));

        BoundExpression argInt = createLiteral(BuiltinType.INT, 10);
        AuxiliarySyntax.Argument synInt = createArg(SyntaxKind.INTEGER_LITERAL, "10", LiteralType.INT, 10, null, null);

        OverloadResult result = OverloadResolver.resolve(
                List.of(f1, f2), List.of(argInt), List.of(synInt));

        Assert.isTrue(result instanceof OverloadResult.Success, "int -> long is better than int -> double");
        OverloadResult.Success success = (OverloadResult.Success) result;
        Assert.equal(BuiltinType.LONG, success.candidate().function().parameters().get(0).type(), "int -> long is better target than int -> double");
    }

    private void argumentCountMismatch() {
        FunctionSymbol f = createFunction("M", BuiltinType.VOID,
                createParam("x", BuiltinType.INT, 0), createParam("y", BuiltinType.INT, 1));

        BoundExpression arg1 = createLiteral(BuiltinType.INT, 1);
        AuxiliarySyntax.Argument syn1 = createArg(SyntaxKind.INTEGER_LITERAL, "1", LiteralType.INT, 1, null, null);

        OverloadResult result = OverloadResolver.resolve(
                List.of(f), List.of(arg1), List.of(syn1));

        Assert.isTrue(result instanceof OverloadResult.Failure, "1 argument for M(int, int) should fail");
        OverloadResult.Failure failure = (OverloadResult.Failure) result;
        Assert.equal(OverloadResult.FailureReason.NO_OVERLOAD_TAKES_N_ARGUMENTS, failure.reason(), "NO_OVERLOAD_TAKES_N_ARGUMENTS expected");
    }

    private void optionalParameterApplicability() {
        ParameterSymbol optional = createOptionalIntParam("y", 1, 2);
        FunctionSymbol function = createFunction("M", BuiltinType.INT,
                createParam("x", BuiltinType.INT, 0), optional);
        BoundExpression argument = createLiteral(BuiltinType.INT, 40);
        AuxiliarySyntax.Argument syntax = createArg(SyntaxKind.INTEGER_LITERAL, "40",
                LiteralType.INT, 40, null, null);

        OverloadResult result = OverloadResolver.resolve(
                List.of(function), List.of(argument), List.of(syntax));

        Assert.isTrue(result instanceof OverloadResult.Success,
                "an omitted optional parameter should not make the candidate inapplicable");
        OverloadResult.Success success = (OverloadResult.Success) result;
        Assert.equal(List.of(optional), success.candidate().omittedParameters(),
                "the selected candidate retains the declaration default to substitute");
        Assert.equal(List.of(0), success.candidate().argumentParameterOrdinals(),
                "the supplied argument maps to the first parameter");
    }

    private void nonDefaultedCandidateWinsTie() {
        FunctionSymbol exact = createFunction("M", BuiltinType.INT,
                createParam("x", BuiltinType.INT, 0));
        FunctionSymbol defaulted = createFunction("M", BuiltinType.INT,
                createParam("x", BuiltinType.INT, 0),
                createOptionalIntParam("y", 1, 0));
        BoundExpression argument = createLiteral(BuiltinType.INT, 1);
        AuxiliarySyntax.Argument syntax = createArg(SyntaxKind.INTEGER_LITERAL, "1",
                LiteralType.INT, 1, null, null);

        OverloadResult result = OverloadResolver.resolve(
                List.of(defaulted, exact), List.of(argument), List.of(syntax));

        Assert.isTrue(result instanceof OverloadResult.Success,
                "the otherwise tied overload should be resolved");
        OverloadResult.Success success = (OverloadResult.Success) result;
        Assert.equal(1, success.candidate().function().parameters().size(),
                "the overload requiring no substituted default is better");
    }

    private void genericConstructedTypeInference() {
        String source = """
            static class P {
                struct List<T> {}
                static T GetFirst<T>(List<T> list) => default(T);
                static void M() {
                    List<int> intList = default(List<int>);
                    int first = GetFirst(intList);
                }
            }
            """;
        SourceFile file = SourceFile.of("test.vs", source);
        DiagnosticBag diagnostics = new DiagnosticBag();
        AuxiliarySyntax.CompilationUnit unit = Parser.parse(file, diagnostics);
        SemanticModel model = DeclarationBinder.bind(java.util.List.of(file), java.util.List.of(unit), diagnostics);
        ExpressionBinding binding = ExpressionBinder.bind(file, unit, model, diagnostics);
        
        for (var d : diagnostics.all()) { System.out.println("DIAG: " + d); }
        Assert.isFalse(diagnostics.hasErrors(), "generic method with constructed parameter should bind correctly without explicit type arguments");
    }
}
