package vsharp.tests.semantics;

import java.util.List;
import vsharp.compiler.semantics.binding.BoundExpression;
import vsharp.compiler.semantics.conversions.Conversion;
import vsharp.compiler.semantics.conversions.ConversionKind;
import vsharp.compiler.semantics.conversions.Conversions;
import vsharp.compiler.semantics.symbols.NamedTypeSymbol;
import vsharp.compiler.semantics.types.BuiltinType;
import vsharp.compiler.semantics.types.TypeSymbol;
import vsharp.compiler.source.SourceSpan;
import vsharp.compiler.syntax.SyntaxKind;
import vsharp.testkit.Assert;
import vsharp.testkit.TestRegistry;
import vsharp.testkit.TestSuite;

public final class ConversionTests implements TestSuite {

    @Override
    public String suiteName() {
        return "semantic.conversions";
    }

    @Override
    public void register(TestRegistry registry) {
        registry.test("identity conversion", this::identityConversion);
        registry.test("implicit numeric ladder", this::implicitNumericLadder);
        registry.test("implicit constant conversion", this::implicitConstantConversion);
        registry.test("constant out of range conversion", this::constantOutOfRangeConversion);
        registry.test("folded constant expression conversion", this::foldedConstantConversion);
        registry.test("explicit numeric conversion", this::explicitNumericConversion);
        registry.test("nullable conversions", this::nullableConversions);
        registry.test("tuple conversions", this::tupleConversions);
        registry.test("null conversion", this::nullConversion);
        registry.test("enum conversions", this::enumConversions);
        registry.test("array covariance conversions", this::arrayCovarianceConversions);
    }

    private void arrayCovarianceConversions() {
        TypeSymbol stringArray = new TypeSymbol.Array(BuiltinType.STRING, List.of(1));
        TypeSymbol objectArray = new TypeSymbol.Array(BuiltinType.OBJECT, List.of(1));
        TypeSymbol intArray = new TypeSymbol.Array(BuiltinType.INT, List.of(1));
        TypeSymbol stringJagged = new TypeSymbol.Array(stringArray, List.of(1));
        TypeSymbol objectJagged = new TypeSymbol.Array(objectArray, List.of(1));
        TypeSymbol stringRank2 = new TypeSymbol.Array(BuiltinType.STRING, List.of(2));

        Assert.equal(ConversionKind.IMPLICIT_REFERENCE,
                Conversions.classify(stringArray, objectArray).kind(),
                "string[] -> object[] widens covariantly");

        Assert.equal(ConversionKind.EXPLICIT_REFERENCE,
                Conversions.classify(objectArray, stringArray).kind(),
                "object[] -> string[] narrows explicitly");

        Assert.equal(ConversionKind.IMPLICIT_REFERENCE,
                Conversions.classify(stringJagged, objectJagged).kind(),
                "string[][] -> object[][] widens through the element array");

        Assert.equal(ConversionKind.NONE,
                Conversions.classify(intArray, objectArray).kind(),
                "int[] -> object[] is refused: a primitive element is not a reference");

        Assert.equal(ConversionKind.NONE,
                Conversions.classify(stringArray, objectJagged).kind(),
                "string[] -> object[][] is refused: element is not a reference conversion");

        Assert.equal(ConversionKind.NONE,
                Conversions.classify(stringRank2, objectArray).kind(),
                "string[,] -> object[] is refused: ranks must be identical");

        Assert.equal(ConversionKind.IMPLICIT_REFERENCE,
                Conversions.classify(stringArray, BuiltinType.OBJECT).kind(),
                "string[] -> object stays an ordinary reference conversion");
    }

    private void identityConversion() {
        Conversion c1 = Conversions.classify(BuiltinType.INT, BuiltinType.INT);
        Assert.equal(ConversionKind.IDENTITY, c1.kind(), "int -> int identity");
        Assert.isTrue(c1.isImplicit(), "identity must be implicit");

        Conversion c2 = Conversions.classify(BuiltinType.STRING, BuiltinType.STRING);
        Assert.equal(ConversionKind.IDENTITY, c2.kind(), "string -> string identity");
    }

    private void implicitNumericLadder() {
        Conversion c1 = Conversions.classify(BuiltinType.SBYTE, BuiltinType.SHORT);
        Assert.equal(ConversionKind.IMPLICIT_NUMERIC, c1.kind(), "sbyte -> short");

        Conversion c2 = Conversions.classify(BuiltinType.INT, BuiltinType.LONG);
        Assert.equal(ConversionKind.IMPLICIT_NUMERIC, c2.kind(), "int -> long");

        Conversion c3 = Conversions.classify(BuiltinType.CHAR, BuiltinType.INT);
        Assert.equal(ConversionKind.IMPLICIT_NUMERIC, c3.kind(), "char -> int");

        Conversion c4 = Conversions.classify(BuiltinType.FLOAT, BuiltinType.DOUBLE);
        Assert.equal(ConversionKind.IMPLICIT_NUMERIC, c4.kind(), "float -> double");
    }

    private void implicitConstantConversion() {
        BoundExpression zero = new BoundExpression.Literal(new SourceSpan(0, 1), BuiltinType.INT, 0);
        Conversion c1 = Conversions.classify(zero, BuiltinType.BYTE);
        Assert.equal(ConversionKind.IMPLICIT_CONSTANT, c1.kind(), "0 -> byte");
        Assert.isTrue(c1.isImplicit(), "0 -> byte must be implicit constant");

        BoundExpression val255 = new BoundExpression.Literal(new SourceSpan(0, 3), BuiltinType.INT, 255);
        Conversion c2 = Conversions.classify(val255, BuiltinType.BYTE);
        Assert.equal(ConversionKind.IMPLICIT_CONSTANT, c2.kind(), "255 -> byte");

        BoundExpression minus1 = new BoundExpression.Literal(new SourceSpan(0, 2), BuiltinType.INT, -1);
        Conversion c3 = Conversions.classify(minus1, BuiltinType.SBYTE);
        Assert.equal(ConversionKind.IMPLICIT_CONSTANT, c3.kind(), "-1 -> sbyte");

        BoundExpression longVal = new BoundExpression.Literal(new SourceSpan(0, 4), BuiltinType.LONG, 100L);
        Conversion c4 = Conversions.classify(longVal, BuiltinType.ULONG);
        Assert.equal(ConversionKind.IMPLICIT_CONSTANT, c4.kind(), "100L -> ulong");
    }

    /// The rule is about a constant *expression*, not a literal token (C# §10.2.11): the one
    /// spelling of `sbyte.MinValue` a program writes by hand is a unary minus over a literal,
    /// and `1 + 1` is a constant too.
    private void foldedConstantConversion() {
        BoundExpression minus128 = new BoundExpression.Unary(new SourceSpan(0, 4),
                BuiltinType.INT, SyntaxKind.MINUS,
                new BoundExpression.Literal(new SourceSpan(1, 4), BuiltinType.INT, 128));
        Assert.equal(ConversionKind.IMPLICIT_CONSTANT,
                Conversions.classify(minus128, BuiltinType.SBYTE).kind(), "-128 -> sbyte");
        Assert.isFalse(Conversions.classify(minus128, BuiltinType.BYTE).isImplicit(),
                "-128 stays out of range for byte");

        BoundExpression sum = new BoundExpression.Binary(new SourceSpan(0, 5), BuiltinType.INT,
                new BoundExpression.Literal(new SourceSpan(0, 1), BuiltinType.INT, 1),
                SyntaxKind.PLUS,
                new BoundExpression.Literal(new SourceSpan(4, 5), BuiltinType.INT, 1));
        Assert.equal(ConversionKind.IMPLICIT_CONSTANT,
                Conversions.classify(sum, BuiltinType.BYTE).kind(), "1 + 1 -> byte");
    }

    private void constantOutOfRangeConversion() {
        BoundExpression val256 = new BoundExpression.Literal(new SourceSpan(0, 3), BuiltinType.INT, 256);
        Conversion c1 = Conversions.classify(val256, BuiltinType.BYTE);
        Assert.equal(ConversionKind.EXPLICIT_NUMERIC, c1.kind(), "256 -> byte out of range");

        BoundExpression minus1 = new BoundExpression.Literal(new SourceSpan(0, 2), BuiltinType.INT, -1);
        Conversion c2 = Conversions.classify(minus1, BuiltinType.BYTE);
        Assert.equal(ConversionKind.EXPLICIT_NUMERIC, c2.kind(), "-1 -> byte out of range");
    }

    private void explicitNumericConversion() {
        Conversion c1 = Conversions.classify(BuiltinType.INT, BuiltinType.SHORT);
        Assert.equal(ConversionKind.EXPLICIT_NUMERIC, c1.kind(), "int -> short explicit");

        Conversion c2 = Conversions.classify(BuiltinType.DOUBLE, BuiltinType.FLOAT);
        Assert.equal(ConversionKind.EXPLICIT_NUMERIC, c2.kind(), "double -> float explicit");

        Conversion c3 = Conversions.classify(BuiltinType.LONG, BuiltinType.INT);
        Assert.equal(ConversionKind.EXPLICIT_NUMERIC, c3.kind(), "long -> int explicit");
    }

    private void nullableConversions() {
        TypeSymbol intNullable = new TypeSymbol.Nullable(BuiltinType.INT);
        Conversion c1 = Conversions.classify(BuiltinType.INT, intNullable);
        Assert.equal(ConversionKind.IMPLICIT_NULLABLE, c1.kind(), "int -> int?");

        TypeSymbol longNullable = new TypeSymbol.Nullable(BuiltinType.LONG);
        Conversion c2 = Conversions.classify(intNullable, longNullable);
        Assert.equal(ConversionKind.IMPLICIT_NULLABLE, c2.kind(), "int? -> long?");

        Conversion c3 = Conversions.classify(intNullable, BuiltinType.INT);
        Assert.equal(ConversionKind.EXPLICIT_NULLABLE, c3.kind(), "int? -> int explicit");

        Conversion c4 = Conversions.classify(intNullable, BuiltinType.OBJECT);
        Assert.equal(ConversionKind.BOXING, c4.kind(), "int? -> object boxes by carrier identity");
    }

    private void tupleConversions() {
        TypeSymbol.Tuple src = new TypeSymbol.Tuple(List.of(
                new TypeSymbol.TupleElement(BuiltinType.INT, null),
                new TypeSymbol.TupleElement(BuiltinType.STRING, null)
        ));
        TypeSymbol.Tuple tgt = new TypeSymbol.Tuple(List.of(
                new TypeSymbol.TupleElement(BuiltinType.LONG, null),
                new TypeSymbol.TupleElement(BuiltinType.OBJECT, null)
        ));
        Conversion c1 = Conversions.classify(src, tgt);
        Assert.equal(ConversionKind.IMPLICIT_TUPLE, c1.kind(), "tuple implicit");

        TypeSymbol.Tuple explicitTgt = new TypeSymbol.Tuple(List.of(
                new TypeSymbol.TupleElement(BuiltinType.SHORT, null),
                new TypeSymbol.TupleElement(BuiltinType.OBJECT, null)
        ));
        Conversion c2 = Conversions.classify(src, explicitTgt);
        Assert.equal(ConversionKind.EXPLICIT_TUPLE, c2.kind(), "tuple explicit");
    }

    private void nullConversion() {
        Conversion c1 = Conversions.classify(TypeSymbol.Null.INSTANCE, BuiltinType.STRING);
        Assert.equal(ConversionKind.IMPLICIT_NULL, c1.kind(), "null -> string");

        TypeSymbol intNullable = new TypeSymbol.Nullable(BuiltinType.INT);
        Conversion c2 = Conversions.classify(TypeSymbol.Null.INSTANCE, intNullable);
        Assert.equal(ConversionKind.IMPLICIT_NULL, c2.kind(), "null -> int?");

        Conversion c3 = Conversions.classify(TypeSymbol.Null.INSTANCE, BuiltinType.INT);
        Assert.equal(ConversionKind.NONE, c3.kind(), "null -> int none");
    }

    private void enumConversions() {
        NamedTypeSymbol enumType = new NamedTypeSymbol("Color", "Color",
                new vsharp.compiler.semantics.symbols.SourceLocation(
                        vsharp.compiler.source.SourceFile.of("test.vs", ""),
                        new SourceSpan(0, 1)),
                NamedTypeSymbol.DeclaredKind.ENUM, 0);
        Conversion c1 = Conversions.classify(BuiltinType.INT, enumType);
        Assert.equal(ConversionKind.EXPLICIT_ENUM, c1.kind(), "int -> enum explicit");

        Conversion c2 = Conversions.classify(enumType, BuiltinType.INT);
        Assert.equal(ConversionKind.EXPLICIT_ENUM, c2.kind(), "enum -> int explicit");

        BoundExpression zero = new BoundExpression.Literal(new SourceSpan(0, 1), BuiltinType.INT, 0);
        Conversion c3 = Conversions.classify(zero, enumType);
        Assert.equal(ConversionKind.IMPLICIT_CONSTANT, c3.kind(), "0 -> enum implicit constant");
    }
}
