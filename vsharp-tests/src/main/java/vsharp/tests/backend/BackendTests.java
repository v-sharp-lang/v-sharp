package vsharp.tests.backend;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import vsharp.compiler.api.Compilation;
import vsharp.compiler.api.CompilationResult;
import vsharp.compiler.api.UnitAnalysis;
import vsharp.compiler.source.SourceFile;
import vsharp.tests.TestSources;
import vsharp.testkit.Assert;
import vsharp.testkit.TestRegistry;
import vsharp.testkit.TestSuite;

public final class BackendTests implements TestSuite {

    @Override
    public String suiteName() {
        return "backend.generation";
    }



    @Override
    public void register(TestRegistry registry) {
        registry.test("emit simple method", this::emitSimpleMethod);
        registry.test("emit factorial while", this::emitFactorialWhile);
        registry.test("emit branch and math", this::emitBranchAndMath);
        registry.test("emit arrays", this::emitArrays);
        registry.test("emit jagged arrays", this::emitJaggedArrays);
        registry.test("emit jagged arrays of jagged arrays", this::emitJaggedArrayOfJaggedArrays);
        registry.test("emit jagged arrays of references", this::emitJaggedReferenceArrays);
        registry.test("emit rectangular arrays", this::emitRectangularArrays);
        registry.test("emit foreach over rectangular arrays", this::emitRectangularForeach);
        registry.test("emit foreach that deconstructs its element", this::emitDeconstructingForeach);
        registry.test("emit a call that writes its own type arguments",
                this::emitWrittenTypeArgumentCall);
        registry.test("emit foreach that deconstructs a record struct element",
                this::emitDeconstructingForeachOverRecordStruct);
        registry.test("emit foreach that deconstructs into nested and discarded targets",
                this::emitDeconstructingForeachNested);
        registry.test("emit expanded params calls", this::emitExpandedParamsCall);
        registry.test("emit a local function of top-level statements",
                this::emitTopLevelLocalFunction);
        registry.test("emit a launchable main for static Main()", this::emitEntryPointBridge);
        registry.test("emit a launchable main for static Main(string[])",
                this::emitEntryPointBridgeForMainWithArgs);
        registry.test("emit no launcher for a non-entry-point Main",
                this::emitNoBridgeForNonEntryPointMain);
        registry.test("emit ToString on builtin value types", this::emitBuiltinToString);
        registry.test("emit builtin MinValue/MaxValue constants", this::emitBuiltinLimits);
        registry.test("fold builtin limits in arithmetic", this::emitBuiltinLimitArithmetic);
        registry.test("emit decimal arithmetic, conversions, default and display",
                this::emitDecimalArithmeticAndDisplay);
        registry.test("declared decimal fields and calls use the BigDecimal carrier",
                this::emitDecimalCarrierDescriptors);
        registry.test("emit nullable decimal lifting, coalescing and object boxing",
                this::emitNullableDecimalCarrier);
        registry.test("emit explicit object unboxing conversions",
                this::emitExplicitObjectUnboxing);
        registry.test("emit builtin ToString for byte, ushort, nuint and decimal",
                this::emitBuiltinToStringUnsignedSmallAndDecimal);
        registry.test("emit System.Int32 Parse and TryParse through the out-cell ABI",
                this::emitSystemInt32Parsing);
        registry.test("emit System.Double Parse and TryParse with exact edge behavior",
                this::emitSystemDoubleParsing);
        registry.test("emit System.Boolean Parse and TryParse with exact trim behavior",
                this::emitSystemBooleanParsing);
        registry.test("emit System.String.Split over separator arrays and the fallback",
                this::emitSystemStringSplit);
        registry.test("emit the ordinal System.String shaping family",
                this::emitSystemStringShaping);
        registry.test("emit the curated System.Array statics",
                this::emitSystemArrayStatics);
        registry.test("emit ToString with the invariant D/X/F specifiers",
                this::emitNumberFormatSpecifiers);
        registry.test("emit interpolation format clauses through the same engine",
                this::emitInterpolationFormatClauses);
        registry.test("emit System.Math.Abs over the curated overload family",
                this::emitSystemMathAbs);
        registry.test("emit System.Math.Max/Min over the full overload set",
                this::emitSystemMathMaxMin);
        registry.test("emit System.Math.Clamp over the full overload set",
                this::emitSystemMathClamp);
        registry.test("emit System.Math.Round over both carriers and every midpoint mode",
                this::emitSystemMathRound);
        registry.test("emit the float and double NaN, infinity and epsilon constants",
                this::emitFloatingConstants);
        registry.test("emit System.String.IsNullOrEmpty and IsNullOrWhiteSpace",
                this::emitStringNullOrWhiteSpaceChecks);
        registry.test("emit System.Char culture-independent operation family",
                this::emitSystemCharPredicates);
        registry.test("emit the bounded System.Convert radix pair",
                this::emitSystemConvertRadix);
        registry.test("emit System.Math.Sign over the full signed/floating/decimal overload set",
                this::emitSystemMathSign);
        registry.test("emit System.Math.Floor, Ceiling and Truncate with C# rounding",
                this::emitSystemMathRounding);
        registry.test("emit System.Math.Sqrt, Pow and Log with C# edge semantics",
                this::emitSystemMathTranscendentals);
        registry.test("emit the bounded System.Math Sin, Cos and Tan trio",
                this::emitSystemMathDirectTrigonometry);
        registry.test("emit System.String.Trim, TrimStart and TrimEnd with C# whitespace",
                this::emitStringTrimFamily);
        registry.test("emit the ordinal System.String search and replace family",
                this::emitStringSearchFamily);
        registry.test("emit System.String invariant casing over Unicode edge cases",
                this::emitStringInvariantCasing);
        registry.test("emit System.String Join and Concat string params family",
                this::emitStringJoinConcat);
        registry.test("emit null-conditional access on nullable value receivers",
                this::emitNullableValueConditionalAccess);
        registry.test("compare a caught exception against null",
                this::emitExceptionNullComparison);
        registry.test("nested tuple conversions convert every element at every depth",
                this::emitNestedTupleConversions);
        registry.test("corelib exceptions are thrown, caught and raised by the JVM alike",
                this::emitCorelibExceptions);
        registry.test("switch dispatch over every carrier, pattern and guard",
                this::emitGeneralSwitchDispatch);
        registry.test("record struct positional members construct, read, copy and deconstruct",
                this::emitRecordStructPositionalMembers);
        registry.test("record struct components and constructor are ordinary Java members",
                this::emitRecordStructJavaShape);
        registry.test("run static field initializers", this::emitStaticFieldInitializers);
        registry.test("array initializer shorthand", this::emitArrayInitializerShorthand);
        registry.test("emit instance methods", this::emitInstanceMethods);
        registry.test("emit multiple classes in one file", this::emitMultipleClassesInOneFile);
        registry.test("emit declared fields even when no method references them",
                this::emitDeclaredFieldsWithoutReferences);
        registry.test("declared field descriptor matches the descriptor its accesses use",
                this::emitFieldOfDeclaredTypeUsesOneDescriptor);
        registry.test("unsigned builtins are declared with their primitive carrier",
                this::emitUnsignedBuiltinsUseTheirPrimitiveCarrier);
        registry.test("top-level statements emit a launchable main(String[])",
                this::emitTopLevelStatementsAsMain);
        registry.test("a deferred expression lowers in call-argument position",
                this::emitDeferredExpressionAsCallArgument);
        registry.test("emit factorial do-while", this::emitFactorialDoWhile);
        registry.test("emit do-while runs body once before testing the condition", this::emitDoWhileRunsOnce);
        registry.test("emit unary negate", this::emitUnaryNegate);
        registry.test("emit long arithmetic and unary operations", this::emitLongArithmeticAndUnaryOperations);
        registry.test("emit float arithmetic and unary operations", this::emitFloatArithmeticAndUnaryOperations);
        registry.test("emit small integral arithmetic after numeric promotion",
                this::emitSmallIntegralArithmeticAfterNumericPromotion);
        registry.test("emit mixed width arithmetic through numeric promotion",
                this::emitMixedWidthArithmetic);
        registry.test("emit unsigned small types widened to their C# value",
                this::emitUnsignedSmallWidening);
        registry.test("emit narrowing casts that truncate", this::emitNarrowingCastTruncates);
        registry.test("emit floating comparisons with C# NaN ordering", this::emitFloatingComparisons);
        registry.test("emit constructs as the last statement of a value method",
                this::emitConstructAsLastStatementOfValueMethod);
        registry.test("emit unary logical not", this::emitUnaryLogicalNot);
        registry.test("emit unary bitwise not", this::emitUnaryBitwiseNot);
        registry.test("emit switch with fallthrough case labels and default", this::emitSwitchWithSharedCasesAndDefault);
        registry.test("emit switch goto case and goto default", this::emitSwitchGoto);
        registry.test("emit implicit numeric widening conversion in a local declaration", this::emitImplicitWideningConversion);
        registry.test("emit implicit numeric widening conversion in a return statement", this::emitImplicitReturnConversion);
        registry.test("emit explicit numeric narrowing cast", this::emitExplicitNarrowingCast);
        registry.test("emit bounded string members", this::emitStringMembers);
        registry.test("emit null-conditional access once across the whole chain",
                this::emitNullConditionalAccess);
        registry.test("emit unsigned arithmetic, ordering, conversion and rendering",
                this::emitUnsignedOperations);
        registry.test("emit boxed and nullable unsigned values without losing signedness",
                this::emitBoxedUnsignedValues);
        registry.test("emit explicit numeric widening cast", this::emitExplicitWideningCast);
        registry.test("emit conditional expression both branches", this::emitConditionalExpression);
        registry.test("emit nested conditional expression", this::emitNestedConditionalExpression);
        registry.test("emit try-catch catching a genuine runtime exception", this::emitTryCatchCatchesRealException);
        registry.test("emit try-catch normal completion skips the handler", this::emitTryCatchNormalCompletion);
        registry.test("emit positional and property patterns", this::emitRecursivePatterns);
        registry.test("emit list and slice patterns", this::emitListPatterns);
        registry.test("emit interpolation hole alignment", this::emitInterpolationAlignment);
        registry.test("emit conditional and switch arms converted to their common type",
                this::emitConvertedResultArms);
        registry.test("emit a lambda nested in a lambda that captures through it",
                this::emitTransitiveLambdaCapture);
        registry.test("emit constants folded from constant expressions, as static finals",
                this::emitFoldedConstantExpressions);
        registry.test("emit a tuple whose elements need a reference conversion",
                this::emitConvertedTupleElements);
        registry.test("emit string.Format, interpolation's run-time twin",
                this::emitStringFormat);
        registry.test("emit the negated-minimum integer literals",
                this::emitNegatedMinimumLiterals);
        registry.test("emit UTF-8 string literals as byte arrays",
                this::emitUtf8StringLiteral);
        registry.test("emit user-defined implicit and explicit conversions",
                this::emitUserDefinedConversions);
        registry.test("emit marker attributes as JVM annotations", this::emitMarkerAttributes);
        registry.test("emit sizeof and typeof with C# type identity", this::emitTypeOperations);
        registry.test("emit tuple deconstruction declarations and tuple conversions",
                this::emitTupleDeconstructionAndConversion);
        registry.test("emit a bare catch as a JVM catch-all handler", this::emitBareCatch);
        registry.test("emit a bare throw; as a rethrow of the handled exception",
                this::emitRethrow);
        registry.test("emit Java construction through a public constructor",
                this::emitJavaConstructorAndThrow);
        registry.test("emit a public static Java field read", this::emitJavaStaticFieldRead);
        registry.test("emit a qualified static Java method call", this::emitJavaStaticMethodCall);
        registry.test("emit Java types and statics reached through an imported package",
                this::emitImportedJavaPackage);
        registry.test("emit Java reference conversions and interface dispatch",
                this::emitJavaReferenceConversions);
        registry.test("emit members of a corelib exception's JVM carrier",
                this::emitCorelibCarrierMembers);
        registry.test("emit C# property reads as JavaBeans accessors",
                this::emitJavaBeanProperties);
        registry.test("emit a deconstructing foreach over a Java map's entry set",
                this::emitMapEntryDeconstructingForeach);
        registry.test("emit the grouping and mapping Collectors over a stream",
                this::emitGroupingCollectors);
        registry.test("emit the Collectors idioms through carried wildcards",
                this::emitCollectorsThroughWildcards);
        registry.test("emit statement-bodied lambdas over every body shape",
                this::emitStatementBodiedLambdas);
        registry.test("emit a lambda typed from its call's target",
                this::emitTargetTypedLambdaArgument);
        registry.test("emit a lambda typed from the target of the call it is nested in",
                this::emitTargetTypedLambdaInNestedCall);
        registry.test("emit a call whose type argument only its target supplies",
                this::emitContextOnlyInference);
        registry.test("emit calls into bounded Java generic methods",
                this::emitBoundedJavaGenerics);
        registry.test("emit a Java downcast that fails at run time",
                this::emitFailingJavaDowncast);
        registry.test("emit object.ToString over boxed and Java receivers",
                this::emitObjectToString);
        registry.test("emit Equals and GetHashCode over every declaring carrier",
                this::emitUniversalMembers);
        registry.test("emit a universal member on a null receiver",
                this::emitUniversalMembersOnNull);
        registry.test("emit GetType over V# carriers and Java receivers",
                this::emitGetType);
        registry.test("emit typeof Java types as JVM Class literals",
                this::emitJavaTypeofClassLiterals);
        registry.test("emit nested Java types named as Java source writes them",
                this::emitNestedJavaTypes);
        registry.test("emit Java varargs calls in expanded and normal form",
                this::emitJavaVarargs);
        registry.test("emit a params constructor in empty, expanded and array form",
                this::emitVarargsConstructor);
        registry.test("emit types named through using aliases", this::emitUsingAliases);
        registry.test("emit Func and Action as JDK functional interfaces",
                this::emitDelegateTypes);
        registry.test("emit unqualified members brought in by using static",
                this::emitStaticImports);
        registry.test("emit a type argument inferred through the target's parameterization",
                this::emitNestedTargetParameterInference);
        registry.test("emit typeof string as the real java.lang.String class",
                this::emitStringClassLiteral);
        registry.test("emit an unchecked cast from a raw Java generic",
                this::emitUncheckedGenericCast);
        registry.test("emit record struct value equality, hashing and rendering",
                this::emitRecordStructValueMembers);
        registry.test("emit raw Java generics erased to their variable bounds",
                this::emitRawGenericBoundErasure);
        registry.test("emit an unbounded wildcard projected to its declared bound",
                this::emitBoundedWildcardProjection);
        registry.test("emit generic Java varargs inferred from loose arguments",
                this::emitJavaGenericVarargs);
        registry.test("emit string passed and cast across the Java hierarchy",
                this::emitKeywordHierarchy);
        registry.test("emit calls to inherited interface members and chained results",
                this::emitInterfaceInheritedMembers);
        registry.test("emit constructed Java generics with class and method substitution",
                this::emitJavaGenericSignatures);
        registry.test("emit Java generics closed by a V# method type parameter",
                this::emitJavaGenericsClosedByVsharpTypeParameter);
        registry.test("emit a qualified static Java call selected by overload",
                this::emitJavaStaticOverloadSelection);
        registry.test("emit a public Java instance field write and read",
                this::emitJavaInstanceFieldWriteAndRead);
        registry.test("emit inherited Java members through a concrete subclass",
                this::emitInheritedJavaMembers);
        registry.test("emit erased Java generic members and descriptor overloads",
                this::emitErasedJavaGenericOverloads);
        registry.test("emit foreach over Java collections, iterables and string",
                this::emitForeachOverJavaIterables);
        registry.test("emit a discarded erased generic result without unboxing it",
                this::emitDiscardedErasedResult);
        registry.test("emit nameof a parameter", this::emitNameOfParameter);
        registry.test("emit nameof a local variable", this::emitNameOfLocal);
        registry.test("emit nameof qualified types and members", this::emitQualifiedNameOf);
        registry.test("emit a 2-element tuple", this::emitTwoElementTuple);
        registry.test("emit a 3-element tuple", this::emitThreeElementTuple);
        registry.test("emit goto forward jump over code", this::emitGotoForwardJump);
        registry.test("emit goto backward jump as a loop", this::emitGotoBackwardJump);
        registry.test("emit checked statement block", this::emitCheckedStatement);
        registry.test("emit checked expression", this::emitCheckedExpression);
        registry.test("emit checked addition that overflows and is caught",
                this::emitCheckedAdditionOverflow);
        registry.test("emit unchecked addition that wraps", this::emitUncheckedAdditionWraps);
        registry.test("emit checked enum arithmetic that overflows",
                this::emitCheckedEnumArithmeticOverflow);
        registry.test("emit ref and out arguments through one-element cells",
                this::emitRefOutLocalCells);
        registry.test("emit ref and out arguments for static and instance fields",
                this::emitRefOutFieldCells);
        registry.test("emit ref and out arguments for instance fields and array elements",
                this::emitRefOutInstanceAndElementCells);
        registry.test("emit in parameters as readonly by-value (documented divergence)",
                this::emitInParametersByValue);
        registry.test("emit checked narrowing conversion that overflows",
                this::emitCheckedNarrowingConversionOverflow);
        registry.test("emit checked negation that overflows", this::emitCheckedNegationOverflow);
        registry.test("emit checked unsigned conversion that overflows",
                this::emitCheckedUnsignedConversionOverflow);
        registry.test("emit interpolated string with int and string holes", this::emitInterpolatedString);
        registry.test("emit interpolated string with bool hole uses C# formatting", this::emitInterpolatedBool);
        registry.test("emit is-type pattern binds the variable on match", this::emitIsTypePatternMatch);
        registry.test("emit is-type pattern falls through without binding on mismatch", this::emitIsTypePatternMismatch);
        registry.test("emit is-null constant pattern", this::emitIsNullConstantPattern);
        registry.test("emit is-constant pattern against a non-null value", this::emitIsConstantPattern);
        registry.test("emit is-constant pattern over a value operand", this::emitValueConstantPattern);
        registry.test("emit relational patterns", this::emitRelationalPatterns);
        registry.test("emit negated patterns", this::emitNegatedPatterns);
        registry.test("emit and/or patterns, including nested", this::emitConjunctivePatterns);
        registry.test("emit a pattern binding that only some paths assign",
                this::emitShortCircuitedPatternBinding);
        registry.test("emit a bare named type pattern the binder reclassified",
                this::emitBareNamedTypePattern);
        registry.test("emit a type pattern against a Java module-path class",
                this::emitJavaTypePattern);
        registry.test("emit an and-pattern over the type its left operand narrowed to",
                this::emitNarrowedConjunctivePattern);
        registry.test("emit a narrowing and-pattern skipped by a short-circuited or",
                this::emitNarrowingUnderShortCircuit);
        registry.test("emit null assigned to a named reference type", this::emitNullToNamedType);
        registry.test("emit bitwise and shift operators", this::emitBitwiseAndShiftOperators);
        registry.test("emit an unsigned right shift for unsigned operands",
                this::emitUnsignedShiftRight);
        registry.test("emit bitwise operators over bool", this::emitBooleanBitwiseOperators);
        registry.test("emit && and || without evaluating the decided operand",
                this::emitShortCircuitOperators);
        registry.test("emit ?? yielding the left operand unless it is null",
                this::emitCoalesceOperator);
        registry.test("emit string concatenation with C# null and value rendering",
                this::emitStringConcatenation);
        registry.test("emit prefix and postfix increment in value position",
                this::emitIncrementValuePosition);
        registry.test("emit increment over a for loop, a static field and an array element",
                this::emitIncrementPlaces);
        registry.test("emit increment on a double, where an arithmetic undo would be wrong",
                this::emitDoubleIncrement);
        registry.test("emit enum members as their underlying values", this::emitEnumValues);
        registry.test("emit enum arithmetic and relational operators", this::emitEnumArithmetic);
        registry.test("emit a switch over an enum governing expression", this::emitEnumSwitch);
        registry.test("emit enum name rendering for display strings", this::emitEnumNameRendering);
        registry.test("emit reads of folded constant fields", this::emitConstantFieldReads);
        registry.test("emit a switch over a string, including case null", this::emitStringSwitch);
        registry.test("emit a switch over a char", this::emitCharSwitch);
        registry.test("emit calls to local functions", this::emitLocalFunctions);
        registry.test("local-function captures share mutable cells across calls",
                this::emitMutableLocalFunctionCaptures);
        registry.test("an expression-bodied local function call keeps its caller reachable",
                this::emitExpressionBodiedLocalFunctionCall);
        registry.test("nested and recursive local functions forward captures transitively",
                this::emitTransitiveLocalFunctionCaptures);
        registry.test("foreach, pattern and catch locals can be captured",
                this::emitScopedLocalFunctionCaptures);
        registry.test("emit generic local functions through erased descriptors",
                this::emitGenericLocalFunctions);
        registry.test("emit optional defaults and named arguments in source order",
                this::emitOptionalArguments);
        registry.test("emit nullable int state, members, coalescing, lifting and round trips",
                this::emitNullableIntVertical);
        registry.test("emit nullable casts and lifted int and bool operators",
                this::emitNullableOperatorClosure);
        registry.test("emit nullable primitive carriers and widening matrix",
                this::emitNullablePrimitiveMatrix);
        registry.test("emit 'as' expression succeeds on a matching reference type", this::emitAsMatch);
        registry.test("emit 'as' expression yields null on a mismatched reference type", this::emitAsMismatch);
        registry.test("emit range construction for all four open/closed forms", this::emitRangeConstructsAllForms);
        registry.test("emit range dispatches to the matching VsRange/VsIndex factory per form",
                this::emitRangeReferencesCorrectFactories);
        registry.test("emit range index from end uses VsIndex.fromEnd", this::emitRangeIndexFromEnd);
        registry.test("emit boxing conversion for value types", this::emitBoxingConversion);
        registry.test("emit finally runs after normal completion, in order", this::emitFinallyRunsOnNormalCompletion);
        registry.test("emit finally runs before a return from inside try", this::emitFinallyRunsBeforeReturnFromTry);
        registry.test("emit finally runs before a return from inside catch", this::emitFinallyRunsBeforeReturnFromCatch);
        registry.test("emit finally runs before an uncaught exception propagates",
                this::emitFinallyRunsBeforeUncaughtExceptionPropagates);
        registry.test("emit finally runs when break escapes", this::emitBreakEscapingFinally);
        registry.test("emit finally runs when goto escapes", this::emitGotoEscapingFinally);
        registry.test("emit finally skipped if break stays inside", this::emitBreakStaysInsideFinally);
        registry.test("emit lock body runs and returns normally", this::emitLockRunsNormally);
        registry.test("emit lock releases the monitor before a return from inside it", this::emitLockReturnsFromInside);
        registry.test("emit lock pairs one monitorenter with every monitorexit exit path",
                this::emitLockMonitorInstructionsArePaired);
        registry.test("emit array creation with a flat initializer", this::emitArrayCreationInitializer);
        registry.test("emit a target-typed collection expression", this::emitCollectionExpression);
        registry.test("emit a target-typed collection expression with spreads", this::emitCollectionExpressionWithSpreads);
        registry.test("emit switch expression over is-pattern arms", this::emitSwitchExpressionArms);
        registry.test("emit int switch expression over constant and discard arms",
                this::emitIntSwitchExpressionConstantAndDiscardArms);
        registry.test("emit using statement releases resource", this::emitUsingReleasesResource);
        registry.test("emit range and index array slicing", this::emitIndexAndSlice);
        registry.test("emit covariant array widening and guarded narrowing",
                this::emitArrayCovariance);
        registry.test("emit a lambda as a Java functional-interface instance",
                this::emitFunctionalInterfaceLambda);
        registry.test("emit lambdas whose generic positions need boxing",
                this::emitBoxedGenericLambdas);
        registry.test("emit a capturing lambda that shares the enclosing variable",
                this::emitCapturingLambda);
        registry.test("emit a stream pipeline whose element type comes from a lambda body",
                this::emitLambdaInferredPipeline);
        registry.test("emit string equality as C#'s value comparison",
                this::emitStringValueEquality);
    }

    /// C# compares `string` by value and `object` by reference, decided by the *static*
    /// type. The JVM's `if_acmpeq` is reference equality always, so a computed string tested
    /// against a literal silently answered `false`. Every expected value here was taken from
    /// a C# 10 oracle, including the `(object)` case that must stay reference equality.
    private void emitStringValueEquality() {
        String source = """
            public struct Equality {
                public static int TestStringEquality() {
                    string head = "AD";
                    string computed = head + "A!";
                    int score = 0;
                    if (computed == "ADA!") {
                        score = score + 1;
                    }
                    if (computed != "ADA!") {
                        score = score + 10;
                    }
                    string nil = null;
                    if (nil == null) {
                        score = score + 100;
                    }
                    if (nil == "x") {
                        score = score + 1000;
                    }
                    object boxed = computed;
                    if (boxed == (object)"ADA!") {
                        score = score + 10000;
                    }
                    return score;
                }
            }
            """;
        assertOutput("Equality", source, "TestStringEquality", 101);
    }

    /// each `Map` changes the stream's element type, and only the lambda body says what
    /// to. Executing the pipeline is what proves the inferred type reached the bytecode: the
    /// `int` elements must box through `Integer` and come back out as `int`.
    private void emitLambdaInferredPipeline() {
        String source = """
            using java.util;
            public struct Pipeline {
                public static int TestInferredElements() {
                    List<string> names = new ArrayList<string>();
                    names.Add("ada");
                    names.Add("grace");
                    List<int> lengths = names.Stream().Map(n => n.Length).ToList();
                    int score = lengths.Get(1);
                    List<string> loud = names.Stream()
                            .Map(n => n.ToUpperInvariant()).Map(n => n + "!").ToList();
                    if (loud.Get(0) == "ADA!") {
                        score = score + 100;
                    }
                    return score;
                }
            }
            """;
        assertOutput("Pipeline", source, "TestInferredElements", 105);
    }

    /// C# captures a variable, not its value. The shared one-element cell V# already
    /// uses for local-function captures is what gives a lambda the same semantics, so a write
    /// made *after* the lambda is created is visible to it, and a write made *by* it is
    /// visible outside. Java cannot express either - it would reject both variables as not
    /// effectively final - so the expected values were taken from a C# 10 oracle: 42, then
    /// 1, 2 and a final outer value of 2.
    private void emitCapturingLambda() {
        String source = """
            using java.util.function;
            public struct Captures {
                public static int TestSharedCapture() {
                    int counter = 0;
                    Supplier<int> read = () => counter;
                    counter = 42;
                    int score = read.Get();
                    int bump = 0;
                    Supplier<int> increment = () => bump = bump + 1;
                    increment.Get();
                    increment.Get();
                    return score + bump;
                }
            }
            """;
        assertOutput("Captures", source, "TestSharedCapture", 44);
    }

    /// `LambdaMetafactory` rejects a primitive standing where the erasure has a
    /// reference, and it does so at *link* time - a `BootstrapMethodError` on first call that
    /// no compile-time check catches. So each shape is executed: `BiFunction<int,int,int>` has
    /// three erased positions and must box all of them, while `IntBinaryOperator` declares
    /// real primitives and must box none. Passing a lambda straight to a wildcard-declared
    /// JDK parameter is executed in the same method.
    private void emitBoxedGenericLambdas() {
        String source = """
            using java.util;
            using java.util.function;
            public struct Boxing {
                public static int TestGenericCarriers() {
                    BiFunction<int, int, int> add = (x, y) => x + y;
                    int score = add.Apply(2, 3);
                    IntBinaryOperator times = (x, y) => x * y;
                    score = score + times.ApplyAsInt(3, 4);
                    List<string> words = new ArrayList<string>();
                    words.Add("delta");
                    words.Add("al");
                    words.RemoveIf(w => w.Length < 3);
                    score = score + words.Size();
                    Collections.Sort(words, (a, b) => a.Length - b.Length);
                    return score;
                }
            }
            """;
        assertOutput("Boxing", source, "TestGenericCarriers", 18);
    }

    /// the lambda body is emitted as an ordinary static method and the value is created
    /// by `invokedynamic` through `LambdaMetafactory`, exactly as javac lowers one. Executing
    /// it is the only proof that matters: the instance must be a real JDK `Comparator` that
    /// `java.util.Collections` will call back into.
    private void emitFunctionalInterfaceLambda() {
        String source = """
            using java.util;
            using java.util.function;
            public struct Lambdas {
                public static int TestFunctionalInterfaces() {
                    Predicate<string> longer = s => s.Length > 3;
                    int score = longer.Test("abcd") ? 1 : 0;
                    Function<string, int> size = s => s.Length;
                    score = score + size.Apply("abcde");
                    Comparator<string> byLength = (a, b) => a.Length - b.Length;
                    List<string> names = new ArrayList<string>();
                    names.Add("pear");
                    names.Add("fig");
                    Collections.Sort(names, byLength);
                    if (names.Get(0) == "fig") {
                        score = score + 100;
                    }
                    return score;
                }
            }
            """;
        assertOutput("Lambdas", source, "TestFunctionalInterfaces", 106);
    }

    private void emitArrayCovariance() {
        String source = """
            using System;
            public struct Covariance {
                public static int TestCovariantArrays() {
                    string[] names = new string[] { "alpha", "beta" };
                    object[] widened = names;
                    int score = widened.Length;
                    string[] back = (string[])widened;
                    if (back[0] == "alpha") {
                        score = score + 10;
                    }
                    object[] fresh = new object[1];
                    try {
                        string[] bad = (string[])fresh;
                        score = score + bad.Length;
                    } catch (InvalidCastException e) {
                        score = score + 100;
                    }
                    return score;
                }
            }
            """;
        assertOutput("Covariance", source, "TestCovariantArrays", 112);
    }

    private void emitTwoElementTuple() {
        String source = """
            public struct Tuples {
                public static (int, int) MakePair(int a, int b) {
                    return (a, b);
                }
            }
            """;
        // VsTupleN's canonical constructor is a generic record erased to Object parameters,
        // so this also exercises boxing each element before construction.
        assertOutput("Tuples", source, "MakePair", new vsharp.runtime.VsTuple2<>(3, 4), 3, 4);
    }

    private void emitDeclaredFieldsWithoutReferences() {
        String source = """
            public struct Storage {
                public static int Counter;
                public int Value;
            }
            """;
        Class<?> type = compileAndLoad("Storage", source);
        try {
            java.lang.reflect.Field counter = type.getField("Counter");
            Assert.equal(int.class, counter.getType(), "static field type");
            Assert.isTrue(Modifier.isStatic(counter.getModifiers()), "Counter is static");
            java.lang.reflect.Field value = type.getField("Value");
            Assert.equal(int.class, value.getType(), "instance field type");
            Assert.isFalse(Modifier.isStatic(value.getModifiers()), "Value is instance");
        } catch (NoSuchFieldException failure) {
            throw new AssertionError("declared field is missing from emitted class", failure);
        }
    }

    /// Constructs that bind to `BoundExpression.Deferred` are lowered from syntax, not from
    /// their bound form. Statement lowering enters through the syntax path, so
    /// `string s = $"...";` always worked - but an invocation delegates to the bound-driven
    /// walk, which met the deferred argument and refused with an internal compiler error.
    /// `Console.WriteLine($"...")` is the canonical first program, so this was reachable by
    /// the most obvious source anyone could write.
    private void emitDeferredExpressionAsCallArgument() {
        String source = """
            public struct Fmt {
                public static string Wrap(string text) {
                    return text;
                }
                public static string Go(int n) {
                    return Fmt.Wrap($"v {n}");
                }
            }
            """;
        assertOutput("Fmt", source, "Go", "v 5", 5);
    }

    /// The binder names the synthesised top-level function `<top-level>` so user code cannot
    /// refer to it, but `<` and `>` are reserved in JVM method names. Emitting that name
    /// verbatim produced a class the JVM refuses to load at all - `ClassFormatError: Illegal
    /// method name` - which no backend test caught because they all compile struct members.
    /// Loading the class here is the real assertion; `defineClass` is what used to fail.
    private void emitTopLevelStatementsAsMain() {
        String source = """
            int x = 1;
            int y = x + 1;
            """;
        Class<?> program = compileAndLoad("Hello", source);
        try {
            java.lang.reflect.Method main = program.getMethod("main", String[].class);
            Assert.equal(void.class, main.getReturnType(), "main returns void");
            Assert.isTrue(Modifier.isStatic(main.getModifiers()), "main is static");
            Assert.isTrue(Modifier.isPublic(main.getModifiers()), "main is public");
            // Executing it proves the body was emitted under the renamed entry point, not
            // merely that a signature exists.
            main.invoke(null, (Object) new String[0]);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("top-level statements did not produce a runnable main",
                    failure);
        }
    }

    /// The unsigned and native-integer builtins have no descriptor of their own: they ride
    /// the signed carrier of the same width. Every one of them must therefore be *declared*
    /// with that primitive descriptor, because the loads, stores and returns emitted for
    /// them are already the primitive instructions.
    private void emitUnsignedBuiltinsUseTheirPrimitiveCarrier() {
        String source = """
            public struct Sizes {
                public static uint Total;
                public static sbyte Small;
                public static uint Twice(uint value) {
                    return value + value;
                }
            }
            """;
        Class<?> sizes = compileAndLoad("Sizes", source);
        try {
            Assert.equal(int.class, sizes.getField("Total").getType(), "uint field carrier is int");
            Assert.equal(byte.class, sizes.getField("Small").getType(), "sbyte field carrier is byte");
            java.lang.reflect.Method twice = sizes.getMethod("Twice", int.class);
            Assert.equal(int.class, twice.getReturnType(), "uint return carrier is int");
            Assert.equal(84, (int) (Integer) twice.invoke(null, 42), "uint arithmetic executes");
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("unsigned-carried members are missing or mistyped", failure);
        }
    }

    /// A field whose type is a declared type, not a builtin. The declaration site and every
    /// access site must agree on the descriptor: the JVM resolves a field by name *and*
    /// descriptor, so disagreement is a `NoSuchFieldError` at first execution, and a wrong
    /// declared descriptor is also visible to any Java caller through reflection.
    private void emitFieldOfDeclaredTypeUsesOneDescriptor() {
        String source = """
            public struct Point {
                public int X;
            }
            public struct Board {
                public static Point Origin;
                public static Point Read() {
                    return Board.Origin;
                }
            }
            """;
        Class<?> board = compileAndLoad("Board", source);
        try {
            java.lang.reflect.Field origin = board.getField("Origin");
            Assert.equal("Point", origin.getType().getName(),
                    "declared field type is the declared V# type, not a widened carrier");
            Assert.equal("Point", board.getMethod("Read").getReturnType().getName(),
                    "reader returns the declared V# type");
            // Executing the read is what proves the two descriptors agree: the getstatic is
            // resolved against the field the emitter declared.
            Assert.isTrue(board.getMethod("Read").invoke(null) == null,
                    "unassigned reference-carried field reads as null");
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("field or reader is missing from the emitted class", failure);
        }
    }

    private void emitThreeElementTuple() {
        // A different arity than the 2-element case above, proving the VsTupleN selection is
        // driven by the element count rather than hardcoded to VsTuple2.
        String source = """
            public struct Tuples {
                public static (int, int, int) MakeTriple(int a, int b, int c) {
                    return (a, b, c);
                }
            }
            """;
        assertOutput("Tuples", source, "MakeTriple", new vsharp.runtime.VsTuple3<>(1, 2, 3), 1, 2, 3);
    }

    private void emitGotoForwardJump() {
        // `goto` jumps forward past an assignment, so the method returns the original value
        // rather than the overwritten one. Exercises a forward reference where `goto` is
        // emitted before the label it targets has been seen.
        String source = """
            public struct Jump {
                public static int SkipAssign(int n) {
                    int result = n;
                    goto done;
                    result = 999;
                    done:
                    return result;
                }
            }
            """;
        assertOutput("Jump", source, "SkipAssign", 7, 7);
    }

    private void emitGotoBackwardJump() {
        // Uses `goto` as a manual loop: jumps backward to `top:` until the counter is zero.
        // Exercises a backward reference where the label has already been bound before the
        // `goto` is emitted.
        String source = """
            public struct Jump {
                public static int CountDown(int n) {
                    int sum = 0;
                    top:
                    if (n > 0) {
                        sum = sum + n;
                        n = n - 1;
                        goto top;
                    }
                    return sum;
                }
            }
            """;
        // CountDown(5) = 5+4+3+2+1 = 15
        assertOutput("Jump", source, "CountDown", 15, 5);
    }

    private void emitCheckedStatement() {
        // `checked { }` is a lexical context, not a no-op: the arithmetic inside it traps
        // on overflow. This case never overflows, so the trapping form must still compute
        // exactly what the wrapping one does.
        String source = """
            public struct Overflow {
                public static int AddChecked(int a, int b) {
                    int result;
                    checked {
                        result = a + b;
                    }
                    return result;
                }
            }
            """;
        assertOutput("Overflow", source, "AddChecked", 15, 7, 8);
    }

    private void emitCheckedExpression() {
        // `checked(expr)` carries the same context through a single expression; a product
        // that fits is returned unchanged by the trapping opcode path.
        String source = """
            public struct Overflow {
                public static int MulChecked(int a, int b) {
                    return checked(a * b);
                }
            }
            """;
        assertOutput("Overflow", source, "MulChecked", 42, 6, 7);
    }

    private void emitCheckedAdditionOverflow() {
        // The whole chain in one case: `checked` addition compiles to `Math.addExact`,
        // whose `ArithmeticException` is what `System.OverflowException` names in V#
        // (CorelibCarriers), so the source-level `catch` actually catches it.
        String source = """
            using System;
            public struct CheckedAdd {
                public static int Add(int a, int b) {
                    int result = 0;
                    try {
                        checked {
                            result = a + b;
                        }
                    } catch (OverflowException) {
                        result = -1;
                    }
                    return result;
                }
            }
            """;
        assertOutput("CheckedAdd", source, "Add", -1, Integer.MAX_VALUE, 1);
    }

    private void emitUncheckedAdditionWraps() {
        // The default context is unchecked, and `unchecked` inside `checked` restores it:
        // the same overflow silently wraps to `int.MinValue` instead of throwing.
        String source = """
            public struct UncheckedAdd {
                public static int Wrap(int a, int b) {
                    int result;
                    checked {
                        unchecked {
                            result = a + b;
                        }
                    }
                    return result;
                }
            }
            """;
        assertOutput("UncheckedAdd", source, "Wrap", Integer.MIN_VALUE, Integer.MAX_VALUE, 1);
    }

    /// C# performs enum arithmetic on the enum's underlying type (§19.6.3), so a `checked`
    /// context must trap `E + U` and `E - E` exactly like `int` arithmetic would; the enum's
    /// `int` carrier is the only fact the emitter needs once it normalises the operand type.
    private void emitCheckedEnumArithmeticOverflow() {
        String source = """
            using System;
            public enum Limit { Min = -2147483648, Max = 2147483647 }
            public struct CheckedEnum {
                public static int AddOverflow() {
                    int result;
                    try {
                        checked {
                            Limit sum = Limit.Max + 1;
                            result = (int)sum;
                        }
                    } catch (OverflowException) {
                        result = -1;
                    }
                    return result;
                }
                public static int SubtractOverflow() {
                    int result;
                    try {
                        checked {
                            result = Limit.Min - Limit.Max;
                        }
                    } catch (OverflowException) {
                        result = -1;
                    }
                    return result;
                }
                public static int AddWraps() {
                    int result;
                    checked {
                        unchecked {
                            Limit sum = Limit.Max + 1;
                            result = (int)sum;
                        }
                    }
                    return result;
                }
            }
            """;
        assertOutput("CheckedEnum", source, "AddOverflow", -1);
        assertOutput("CheckedEnum", source, "SubtractOverflow", -1);
        assertOutput("CheckedEnum", source, "AddWraps", Integer.MIN_VALUE);
    }

    /// `ref`/`out` parameters are passed as one-element array cells at V# boundaries. The
    /// callee reads and writes `cell[0]`, and the caller copies the current value in for
    /// `ref` (or leaves the cell default for `out`), passes the cell and copies it back.
    private void emitRefOutLocalCells() {
        String source = """
            public struct RefOut {
                static void Swap(ref int a, ref int b) {
                    int t = a;
                    a = b;
                    b = t;
                }
                static void Next(out int value, int amount) {
                    value = amount;
                }
                static void Bump(ref int value) {
                    value = value + 1;
                }
                public static int Run() {
                    int x = 1;
                    int y = 2;
                    Swap(ref x, ref y);
                    int z;
                    Next(out z, 9);
                    Bump(ref x);
                    return x * 100 + y * 10 + z;
                }
            }
            """;
        // Swap: x=2, y=1. Next: z=9. Bump: x=3. Result: 3*100 + 1*10 + 9 = 319.
        assertOutput("RefOut", source, "Run", 319);
    }

    /// Static-field places follow the same cell protocol: `getstatic` copies in for `ref`,
    /// and `putstatic` copies back after the call. The overload binder still restricts
    /// instance fields and array elements to `VS1510`, so static fields are the reachable
    /// field place this case executes.
    private void emitRefOutFieldCells() {
        String source = """
            public struct FieldRefOut {
                static int a = 1;
                static int b = 2;
                static void Swap(ref int x, ref int y) {
                    int t = x;
                    x = y;
                    y = t;
                }
                static void Next(out int v, int amount) {
                    v = amount;
                }
                public static int StaticRun() {
                    Swap(ref a, ref b);
                    Next(out a, 7);
                    return a * 10 + b;
                }
            }
            """;
        // Swap: a=2, b=1. Next: a=7. Result: 7*10 + 1 = 71.
        assertOutput("FieldRefOut", source, "StaticRun", 71);
    }

    /// Instance fields copy in/out through a spilled receiver (`getfield`/`putfield`), and
    /// single-index array elements through a spilled receiver and index (`Xaload`/`Xastore`).
    private void emitRefOutInstanceAndElementCells() {
        String source = """
            using java.awt;
            public struct PlaceRefOut {
                static void Swap(ref int a, ref int b) {
                    int t = a;
                    a = b;
                    b = t;
                }
                static void Next(out int v, int amount) {
                    v = amount;
                }
                public static int InstanceRun() {
                    Point p = new Point();
                    p.X = 3;
                    p.Y = 4;
                    Swap(ref p.X, ref p.Y);
                    return p.X * 10 + p.Y;
                }
                public static int ElementRun() {
                    int[] values = new int[] { 1, 2 };
                    Swap(ref values[0], ref values[1]);
                    Next(out values[0], 7);
                    return values[0] * 10 + values[1];
                }
                public static int RectRun() {
                    int[,] cells = new int[2, 2];
                    cells[0, 1] = 3;
                    cells[1, 0] = 4;
                    Swap(ref cells[0, 1], ref cells[1, 0]);
                    Next(out cells[1, 1], 7);
                    return cells[0, 1] * 100 + cells[1, 0] * 10 + cells[1, 1];
                }
            }
            """;
        // InstanceRun: point becomes (4,3) => 43. ElementRun: values become {7,1} => 71.
        // RectRun: cells[0,1]=4, cells[1,0]=3, cells[1,1]=7 => 437.
        assertOutput("PlaceRefOut", source, "InstanceRun", 43);
        assertOutput("PlaceRefOut", source, "ElementRun", 71);
        assertOutput("PlaceRefOut", source, "RectRun", 437);
    }

    /// `in` parameters are readonly and are currently passed by value. The basic
    /// cases match C#; the aliasing case is the documented divergence: C# would return 99
    /// because `in x` and `ref x` name the same storage, while V# copies `x` for the `in`
    /// parameter and therefore still reads 5 after the `ref` write.
    private void emitInParametersByValue() {
        String source = """
            public struct InProbe {
                static int Read(in int value) { return value; }
                static int Observe(in int a, ref int b) {
                    b = 99;
                    return a;
                }
                public static int Basic() {
                    int x = 5;
                    return Read(in x);
                }
                public static int Expression() {
                    return Read(in (2 + 3));
                }
                public static int Aliased() {
                    int x = 5;
                    return Observe(in x, ref x);
                }
            }
            """;
        assertOutput("InProbe", source, "Basic", 5);
        assertOutput("InProbe", source, "Expression", 5);
        assertOutput("InProbe", source, "Aliased", 5);
    }

    private void emitCheckedNarrowingConversionOverflow() {
        // Conversions trap too, not only arithmetic (C# 11.7.18). 300 is outside `byte`,
        // so the checked cast throws where the unchecked one masks to 44.
        String source = """
            using System;
            public struct CheckedCast {
                public static int Narrow(int n) {
                    int result;
                    try {
                        byte b = checked((byte)n);
                        result = b;
                    } catch (OverflowException) {
                        result = -1;
                    }
                    return result;
                }
                public static int NarrowUnchecked(int n) {
                    byte b = (byte)n;
                    return b;
                }
            }
            """;
        assertOutput("CheckedCast", source, "Narrow", -1, 300);
        assertOutput("CheckedCast", source, "NarrowUnchecked", 44, 300);
    }

    private void emitCheckedNegationOverflow() {
        // `-int.MinValue` is the one negation with no result, and the only unary operator
        // that can overflow. `Math.negateExact` is what makes it observable.
        String source = """
            using System;
            public struct CheckedNeg {
                public static int Negate(int n) {
                    int result;
                    try {
                        result = checked(-n);
                    } catch (OverflowException) {
                        result = -1;
                    }
                    return result;
                }
            }
            """;
        assertOutput("CheckedNeg", source, "Negate", -1, Integer.MIN_VALUE);
        assertOutput("CheckedNeg", source, "Negate", -5, 5);
    }

    private void emitCheckedUnsignedConversionOverflow() {
        // The unsigned targets have no JDK `*Exact` counterpart, so these route through
        // `vsharp.runtime.VsChecked`: -1 is not a `uint`, while 7 converts unchanged.
        String source = """
            using System;
            public struct CheckedUnsigned {
                public static int ToUInt(int n) {
                    int result;
                    try {
                        uint u = checked((uint)n);
                        result = (int)u;
                    } catch (OverflowException) {
                        result = -1;
                    }
                    return result;
                }
            }
            """;
        assertOutput("CheckedUnsigned", source, "ToUInt", -1, -1);
        assertOutput("CheckedUnsigned", source, "ToUInt", 7, 7);
    }

    private void emitInterpolatedString() {
        // Exercises the StringBuilder-based interpolation with text, int and string holes.
        String source = """
            public struct Greet {
                public static string Hello(string name, int age) {
                    return $"Hello, {name}! You are {age} years old.";
                }
            }
            """;
        assertOutput("Greet", source, "Hello", "Hello, Alice! You are 30 years old.", "Alice", 30);
    }

    private void emitInterpolatedBool() {
        // Exercises C#-faithful bool formatting via VsFormat: `true` renders as `True`.
        String source = """
            public struct Fmt {
                public static string ShowBool(bool flag) {
                    return $"flag is {flag}";
                }
            }
            """;
        assertOutput("Fmt", source, "ShowBool", "flag is True", true);
        assertOutput("Fmt", source, "ShowBool", "flag is False", false);
    }

    private void emitIsTypePatternMatch() {
        // `is int n` on a reference-typed (`object`) parameter: instanceof + checkcast +
        // unbox, binding `n` only along the matched branch.
        String source = """
            public struct Patterns {
                public static int Classify(object o) {
                    if (o is int n) {
                        return n;
                    }
                    if (o is string s) {
                        return -2;
                    }
                    return -1;
                }
            }
            """;
        assertOutput("Patterns", source, "Classify", 42, (Object) 42);
        assertOutput("Patterns", source, "Classify", -2, (Object) "hello");
    }

    private void emitIsTypePatternMismatch() {
        // A boxed Double matches neither `is int` nor `is string`, so the tested reference
        // must be correctly discarded (not left on the stack) on every failed instanceof.
        String source = """
            public struct Patterns {
                public static int Classify(object o) {
                    if (o is int n) {
                        return n;
                    }
                    if (o is string s) {
                        return -2;
                    }
                    return -1;
                }
            }
            """;
        assertOutput("Patterns", source, "Classify", -1, (Object) 3.5);
    }

    private void emitIsNullConstantPattern() {
        String source = """
            public struct NullCheck {
                public static bool IsNull(object o) {
                    return o is null;
                }
            }
            """;
        assertOutput("NullCheck", source, "IsNull", true, (Object) null);
        assertOutput("NullCheck", source, "IsNull", false, (Object) "x");
    }

    private void emitIsConstantPattern() {
        String source = """
            public struct Matcher {
                public static bool IsFive(object o) {
                    return o is 5;
                }
            }
            """;
        assertOutput("Matcher", source, "IsFive", true, (Object) 5);
        assertOutput("Matcher", source, "IsFive", false, (Object) 6);
    }

    /// A constant pattern over a *value* operand: no boxing, a direct comparison on the
    /// value's own carrier, and - unlike every pattern test before this increment - an
    /// operand that is not reference-typed at all.
    private void emitValueConstantPattern() {
        String source = """
            public struct Matcher {
                public static bool IsFive(int n) {
                    return n is 5;
                }
                public static bool IsFiveLong(long n) {
                    return n is 5;
                }
            }
            """;
        assertOutput("Matcher", source, "IsFive", true, 5);
        assertOutput("Matcher", source, "IsFive", false, 6);
        // The literal is an `int`; the comparison is `lcmp`. Reaching it without promoting
        // the constant would fail verification, so this is the the design contract inside patterns.
        assertOutput("Matcher", source, "IsFiveLong", true, 5L);
        assertOutput("Matcher", source, "IsFiveLong", false, 6L);
    }

    private void emitRelationalPatterns() {
        String source = """
            public struct Matcher {
                public static bool Positive(int n) {
                    return n is > 0;
                }
                public static bool AtLeast(long n) {
                    return n is >= 1000;
                }
                public static bool Under(double d) {
                    return d is < 2.5;
                }
            }
            """;
        assertOutput("Matcher", source, "Positive", true, 1);
        assertOutput("Matcher", source, "Positive", false, 0);
        assertOutput("Matcher", source, "Positive", false, -1);
        assertOutput("Matcher", source, "AtLeast", true, 1000L);
        assertOutput("Matcher", source, "AtLeast", false, 999L);
        assertOutput("Matcher", source, "Under", true, 2.0d);
        assertOutput("Matcher", source, "Under", false, 2.5d);
        // NaN is unordered: every relational pattern against it is false, exactly as a
        // written comparison is.
        assertOutput("Matcher", source, "Under", false, Double.NaN);
    }

    private void emitNegatedPatterns() {
        String source = """
            public struct Matcher {
                public static bool NotZero(int n) {
                    return n is not 0;
                }
                public static bool NotNull(object o) {
                    return o is not null;
                }
            }
            """;
        assertOutput("Matcher", source, "NotZero", true, 1);
        assertOutput("Matcher", source, "NotZero", false, 0);
        assertOutput("Matcher", source, "NotNull", true, (Object) "x");
        assertOutput("Matcher", source, "NotNull", false, (Object) null);
    }

    private void emitConjunctivePatterns() {
        // `and`/`or` re-test the same operand, which is the whole reason the operand is
        // parked in a slot; nested combinators prove the tree composes past one level.
        String source = """
            public struct Matcher {
                public static string Classify(int n) {
                    if (n is > 0 and < 10) {
                        return "small";
                    }
                    if (n is < 0 or > 100) {
                        return "outside";
                    }
                    if (n is 0 or 10 or 20) {
                        return "round";
                    }
                    return "other";
                }
                public static bool Teens(int n) {
                    return n is >= 13 and <= 19 and not 15;
                }
            }
            """;
        assertOutput("Matcher", source, "Classify", "small", 5);
        assertOutput("Matcher", source, "Classify", "outside", -1);
        assertOutput("Matcher", source, "Classify", "outside", 101);
        assertOutput("Matcher", source, "Classify", "round", 0);
        assertOutput("Matcher", source, "Classify", "round", 20);
        assertOutput("Matcher", source, "Classify", "other", 11);
        assertOutput("Matcher", source, "Teens", true, 13);
        assertOutput("Matcher", source, "Teens", true, 19);
        assertOutput("Matcher", source, "Teens", false, 15);
        assertOutput("Matcher", source, "Teens", false, 20);
    }

    /// A binding that only some paths reach. C# forbids a designation under `or`/`not`
    /// (VS8780), so the only way a pattern variable can be unassigned at a join is for its
    /// own pattern to have failed - `n` is stored on the matching edge only, and the slot
    /// still merges with the edges that skipped the store. Priming every binding with a
    /// typed default before the tree runs is what keeps the slot's verifier type consistent
    /// across those merges. The `or` arm also covers a bare keyword type pattern, which is
    /// only a pattern (never a constant expression) because `double` is a type keyword.
    private void emitShortCircuitedPatternBinding() {
        String source = """
            public struct Matcher {
                public static int Width(object o) {
                    if (o is int n) {
                        return n;
                    }
                    if (o is string or double) {
                        return 0;
                    }
                    return -1;
                }
            }
            """;
        assertOutput("Matcher", source, "Width", 7, (Object) 7);
        assertOutput("Matcher", source, "Width", 0, (Object) "unbound");
        assertOutput("Matcher", source, "Width", 0, (Object) 1.5);
        assertOutput("Matcher", source, "Width", -1, (Object) true);
    }

    /// `o is Holder` with no designation. The parser cannot tell this from a constant
    /// pattern - `o is Red` is one - so it produces a constant pattern either way and name
    /// resolution reclassifies it. `Holder` is a V# declaration, so the test targets
    /// the class this compilation emits for it; no V# declaration is instantiable, so the
    /// answer is always false, which is exactly what C# says a type test of a type with no
    /// instances returns.
    private void emitBareNamedTypePattern() {
        String source = """
            public struct Holder {
                public static int Kind(object o) {
                    if (o is Holder) {
                        return 1;
                    }
                    if (o is string) {
                        return 2;
                    }
                    return 0;
                }
            }
            """;
        assertOutput("Holder", source, "Kind", 2, (Object) "text");
        assertOutput("Holder", source, "Kind", 0, (Object) 7);
        assertOutput("Holder", source, "Kind", 0, (Object) null);
    }

    /// A type pattern over a module-path class, in both spellings: with a designation (which
    /// the parser resolves as a type) and bare (which only name resolution can). Both must
    /// reach the same test, or the two would disagree about the same program.
    private void emitJavaTypePattern() {
        String source = """
            using java.lang;
            public struct JavaKind {
                public static int Kind(object o) {
                    if (o is Integer boxed) {
                        return 1;
                    }
                    if (o is Exception) {
                        return 2;
                    }
                    return 0;
                }
            }
            """;
        assertOutput("JavaKind", source, "Kind", 1, (Object) 5);
        assertOutput("JavaKind", source, "Kind", 2, new java.io.IOException("x"));
        assertOutput("JavaKind", source, "Kind", 0, (Object) "text");
    }

    /// C# narrows the input type across `and`: the right operand tests the type the left
    /// narrowed to, so `o is int n and > 0` compares an `int` even though the tree started
    /// from an `object`. Emitted against the original operand it would either compare a
    /// reference or fail to verify. The undesignated form narrows too, through a slot the
    /// pattern never names.
    private void emitNarrowedConjunctivePattern() {
        String source = """
            public struct Ranges {
                public static int Classify(object o) {
                    if (o is int n and > 100) {
                        return n;
                    }
                    if (o is int and > 0 and < 10) {
                        return 1;
                    }
                    if (o is long l and >= 1000L) {
                        return 2;
                    }
                    return -1;
                }
            }
            """;
        assertOutput("Ranges", source, "Classify", 400, (Object) 400);
        assertOutput("Ranges", source, "Classify", 1, (Object) 5);
        assertOutput("Ranges", source, "Classify", -1, (Object) 50);
        assertOutput("Ranges", source, "Classify", 2, (Object) 5000L);
        assertOutput("Ranges", source, "Classify", -1, (Object) "text");
    }

    /// The narrowing slot is written only on the path that matched, so like every pattern
    /// binding it must be primed before the tree runs: here the `or` short-circuits over the
    /// whole conjunction whenever the operand is a string, leaving the slot untouched on
    /// that edge into the join.
    private void emitNarrowingUnderShortCircuit() {
        String source = """
            public struct Guarded {
                public static bool Matches(object o) {
                    return o is string or (int and > 0);
                }
            }
            """;
        assertOutput("Guarded", source, "Matches", true, (Object) "text");
        assertOutput("Guarded", source, "Matches", true, (Object) 3);
        assertOutput("Guarded", source, "Matches", false, (Object) (-3));
        assertOutput("Guarded", source, "Matches", false, (Object) 2.5);
    }

    /// `null` needs no instruction to become a value of a reference type - it already is
    /// one - but the conversion is still classified, and the emitter used to reject every
    /// classification it had no opcode for.
    private void emitNullToNamedType() {
        String source = """
            using java.lang;
            public struct Nulls {
                public static bool Empty() {
                    Integer boxed = null;
                    string text = null;
                    object any = null;
                    if (boxed is not null) {
                        return false;
                    }
                    if (text is not null) {
                        return false;
                    }
                    return any is null;
                }
            }
            """;
        assertOutput("Nulls", source, "Empty", true);
    }

    private void emitBitwiseAndShiftOperators() {
        String source = """
            public struct Bits {
                public static int And(int a, int b) { return a & b; }
                public static int Or(int a, int b) { return a | b; }
                public static int Xor(int a, int b) { return a ^ b; }
                public static int Left(int a, int n) { return a << n; }
                public static int Right(int a, int n) { return a >> n; }
                public static long LongAnd(long a, long b) { return a & b; }
                public static long LongLeft(long a, int n) { return a << n; }
                public static long LongRight(long a, int n) { return a >> n; }
            }
            """;
        assertOutput("Bits", source, "And", 8, 12, 10);
        assertOutput("Bits", source, "Or", 14, 12, 10);
        assertOutput("Bits", source, "Xor", 6, 12, 10);
        assertOutput("Bits", source, "Left", 48, 12, 2);
        assertOutput("Bits", source, "Right", -3, -12, 2);
        // C# masks a shift count to the operand's width, and so does the JVM: 32 shifts by 0.
        assertOutput("Bits", source, "Left", 12, 12, 32);
        assertOutput("Bits", source, "LongAnd", 8L, 12L, 10L);
        assertOutput("Bits", source, "LongLeft", 48L, 12L, 2);
        assertOutput("Bits", source, "LongRight", -3L, -12L, 2);
        assertOutput("Bits", source, "LongLeft", 12L, 12L, 64);
    }

    /// `>>` is arithmetic over a signed type and logical over an unsigned one, and V# carries
    /// both in the same JVM primitive - so the operand's C# type is the only thing that can
    /// choose the opcode. `>>>` is logical whatever the operand.
    private void emitUnsignedShiftRight() {
        String source = """
            public struct Shifts {
                public static uint Unsigned(uint a, int n) { return a >> n; }
                public static int Signed(int a, int n) { return a >> n; }
                public static int Logical(int a, int n) { return a >>> n; }
            }
            """;
        assertOutput("Shifts", source, "Unsigned", 0x3FFFFFFF, 0xFFFFFFFF, 2);
        assertOutput("Shifts", source, "Signed", -1, -1, 2);
        assertOutput("Shifts", source, "Logical", 0x3FFFFFFF, -1, 2);
    }

    private void emitBooleanBitwiseOperators() {
        String source = """
            public struct Flags {
                public static bool And(bool x, bool y) { return x & y; }
                public static bool Or(bool x, bool y) { return x | y; }
                public static bool Xor(bool x, bool y) { return x ^ y; }
            }
            """;
        assertOutput("Flags", source, "And", false, true, false);
        assertOutput("Flags", source, "And", true, true, true);
        assertOutput("Flags", source, "Or", true, true, false);
        assertOutput("Flags", source, "Xor", true, true, false);
        assertOutput("Flags", source, "Xor", false, true, true);
    }

    /// The point of `&&`/`||` is the operand that is *not* evaluated, which only a side
    /// effect can observe: `Count` runs once per evaluation of the right operand, so the
    /// counter distinguishes a real short circuit from a correct-looking `iand`.
    private void emitShortCircuitOperators() {
        String source = """
            public struct Logic {
                static int calls;
                static bool Count(bool value) {
                    calls = calls + 1;
                    return value;
                }
                public static int AndSkips(bool left) {
                    calls = 0;
                    bool ignored = left && Count(true);
                    return calls;
                }
                public static int OrSkips(bool left) {
                    calls = 0;
                    bool ignored = left || Count(true);
                    return calls;
                }
                public static bool AndValue(bool x, bool y) { return x && y; }
                public static bool OrValue(bool x, bool y) { return x || y; }
            }
            """;
        assertOutput("Logic", source, "AndSkips", 0, false);
        assertOutput("Logic", source, "AndSkips", 1, true);
        assertOutput("Logic", source, "OrSkips", 0, true);
        assertOutput("Logic", source, "OrSkips", 1, false);
        assertOutput("Logic", source, "AndValue", false, true, false);
        assertOutput("Logic", source, "AndValue", true, true, true);
        assertOutput("Logic", source, "OrValue", true, false, true);
        assertOutput("Logic", source, "OrValue", false, false, false);
    }

    private void emitCoalesceOperator() {
        String source = """
            public struct Fallback {
                static int calls;
                static string Right() {
                    calls = calls + 1;
                    return "fallback";
                }
                public static string Pick(string left) {
                    return left ?? Right();
                }
                public static int Evaluations(string left) {
                    calls = 0;
                    string ignored = left ?? Right();
                    return calls;
                }
            }
            """;
        assertOutput("Fallback", source, "Pick", "value", (Object) "value");
        assertOutput("Fallback", source, "Pick", "fallback", (Object) null);
        assertOutput("Fallback", source, "Evaluations", 0, (Object) "value");
        assertOutput("Fallback", source, "Evaluations", 1, (Object) null);
    }

    /// C# renders a null string as empty inside a concatenation and a `bool` as `True`, so
    /// this is not `String.valueOf` - it is the same conversion interpolation uses.
    private void emitStringConcatenation() {
        String source = """
            public struct Join {
                public static string Two(string a, string b) { return a + b; }
                public static string WithInt(string a, int n) { return a + n; }
                public static string WithBool(string a, bool b) { return a + b; }
                public static string Chain(int n) { return "n=" + n + "!"; }
            }
            """;
        assertOutput("Join", source, "Two", "ab", "a", "b");
        assertOutput("Join", source, "Two", "a", "a", null);
        assertOutput("Join", source, "Two", "b", null, "b");
        assertOutput("Join", source, "WithInt", "n42", "n", 42);
        assertOutput("Join", source, "WithBool", "f:True", "f:", true);
        assertOutput("Join", source, "Chain", "n=7!", 7);
    }

    /// The two spellings differ in value, not only in position: `a++` yields the value from
    /// before the update and `++a` the value after it, while both leave the same variable.
    private void emitIncrementValuePosition() {
        String source = """
            public struct Steps {
                public static int PostIncrement(int a) { return a++; }
                public static int PreIncrement(int a) { return ++a; }
                public static int PostDecrement(int a) { return a--; }
                public static int PreDecrement(int a) { return --a; }
                public static int AfterPost(int a) { int ignored = a++; return a; }
                public static int AfterPre(int a) { int ignored = ++a; return a; }
                public static int Statement(int a) { a++; a++; return a; }
            }
            """;
        assertOutput("Steps", source, "PostIncrement", 5, 5);
        assertOutput("Steps", source, "PreIncrement", 6, 5);
        assertOutput("Steps", source, "PostDecrement", 5, 5);
        assertOutput("Steps", source, "PreDecrement", 4, 5);
        assertOutput("Steps", source, "AfterPost", 6, 5);
        assertOutput("Steps", source, "AfterPre", 6, 5);
        assertOutput("Steps", source, "Statement", 7, 5);
    }

    /// Every place a `++` can be applied to, including the one that made the loop idiom
    /// impossible. The array case must evaluate its receiver and index once, not twice.
    private void emitIncrementPlaces() {
        String source = """
            public struct Places {
                static int counter;
                static int[] target;
                static int Index() { counter = counter + 1; return 0; }
                public static int Loop(int n) {
                    int total = 0;
                    for (int i = 0; i < n; i++) {
                        total = total + i;
                    }
                    return total;
                }
                public static int Field() {
                    counter = 5;
                    counter++;
                    return counter;
                }
                public static int Element(int[] xs) {
                    xs[1]++;
                    return xs[1];
                }
                public static int ElementValue(int[] xs) {
                    return xs[1]++;
                }
                public static int IndexEvaluations(int[] xs) {
                    counter = 0;
                    xs[Index()]++;
                    return counter;
                }
            }
            """;
        assertOutput("Places", source, "Loop", 10, 5);
        assertOutput("Places", source, "Loop", 0, 0);
        assertOutput("Places", source, "Field", 6);
        assertOutput("Places", source, "Element", 21, (Object) new int[] {10, 20});
        assertOutput("Places", source, "ElementValue", 20, (Object) new int[] {10, 20});
        assertOutput("Places", source, "IndexEvaluations", 1, (Object) new int[] {10, 20});
    }

    /// `2^53` is the largest double where adding one still changes the value: at `2^53` the
    /// step is lost, so a postfix implemented as `(d + 1) - 1` would return one less than the
    /// value it was given, and the variable would end up wrong as well.
    private void emitDoubleIncrement() {
        String source = """
            public struct Doubles {
                public static double PostValue(double d) { return d++; }
                public static double AfterPost(double d) { double ignored = d++; return d; }
                public static double PreValue(double d) { return ++d; }
                public static long LongPost(long value) { return value++; }
                public static long LongAfter(long value) { value++; return value; }
            }
            """;
        double unstepped = 9007199254740992.0;
        assertOutput("Doubles", source, "PostValue", 5.0, 5.0);
        assertOutput("Doubles", source, "PreValue", 6.0, 5.0);
        assertOutput("Doubles", source, "AfterPost", 6.0, 5.0);
        assertOutput("Doubles", source, "PostValue", unstepped, unstepped);
        assertOutput("Doubles", source, "AfterPost", unstepped, unstepped);
        assertOutput("Doubles", source, "LongPost", 5L, 5L);
        assertOutput("Doubles", source, "LongAfter", 6L, 5L);
    }

    /// A V# enum has no run-time class: a member is the `int` it names, everywhere. The
    /// values follow C#'s counting rule, which an explicit initialiser resets.
    private void emitEnumValues() {
        String source = """
            public enum Level { Low, Medium, High }
            public enum Code { Ok = 200, Created, Missing = 404 }
            public struct Enums {
                static Level current;
                public static int LowValue() { return (int) Level.Low; }
                public static int HighValue() { return (int) Level.High; }
                public static int Counted() { return (int) Code.Created; }
                public static int Reset() { return (int) Code.Missing; }
                public static bool Same(Level a, Level b) { return a == b; }
                public static int RoundTrip(Level level) { current = level; return (int) current; }
            }
            """;
        assertOutput("Enums", source, "LowValue", 0);
        assertOutput("Enums", source, "HighValue", 2);
        assertOutput("Enums", source, "Counted", 201);
        assertOutput("Enums", source, "Reset", 404);
        assertOutput("Enums", source, "Same", true, 1, 1);
        assertOutput("Enums", source, "Same", false, 0, 1);
        assertOutput("Enums", source, "RoundTrip", 2, 2);
    }

    /// C# §19.6.3 enum operators: `E + U`/`U + E`/`E - U`/`E - E`, the bitwise forms and
    /// the relational family all lower to the enum's `int` carrier; only the binder change
    /// is new here - the backend was already able to emit an `int`-carried binary op.
    private void emitEnumArithmetic() {
        String source = """
            public enum Flag { None = 0, A = 1, B = 2, C = 4 }
            public struct EnumMath {
                public static int PlusLeft(Flag f) { return (int)(f + 2); }
                public static int PlusRight(Flag f) { return (int)(2 + f); }
                public static int MinusInt(Flag f) { return (int)(f - 1); }
                public static int MinusEnum(Flag a, Flag b) { return a - b; }
                public static int Or(Flag a, Flag b) { return (int)(a | b); }
                public static int And(Flag a, Flag b) { return (int)(a & b); }
                public static int Xor(Flag a, Flag b) { return (int)(a ^ b); }
                public static int Not(Flag a) { return (int)(~a); }
                public static int PostInc(Flag a) { a++; return (int)a; }
                public static int PreDec(Flag a) { --a; return (int)a; }
                public static bool Less(Flag a, Flag b) { return a < b; }
                public static bool Greater(Flag a, Flag b) { return a > b; }
                public static bool LessEq(Flag a, Flag b) { return a <= b; }
                public static bool GreaterEq(Flag a, Flag b) { return a >= b; }
                public static bool Eq(Flag a, Flag b) { return a == b; }
                public static bool Neq(Flag a, Flag b) { return a != b; }
            }
            """;
        assertOutput("EnumMath", source, "PlusLeft", 3, 1);
        assertOutput("EnumMath", source, "PlusRight", 3, 1);
        assertOutput("EnumMath", source, "MinusInt", 0, 1);
        assertOutput("EnumMath", source, "MinusEnum", 1, 2, 1);
        assertOutput("EnumMath", source, "Or", 3, 1, 2);
        assertOutput("EnumMath", source, "And", 0, 1, 2);
        assertOutput("EnumMath", source, "Xor", 3, 1, 2);
        assertOutput("EnumMath", source, "Not", -2, 1);
        assertOutput("EnumMath", source, "PostInc", 2, 1);
        assertOutput("EnumMath", source, "PreDec", 0, 1);
        assertOutput("EnumMath", source, "Less", true, 1, 2);
        assertOutput("EnumMath", source, "Greater", false, 1, 2);
        assertOutput("EnumMath", source, "LessEq", true, 1, 1);
        assertOutput("EnumMath", source, "GreaterEq", false, 1, 2);
        assertOutput("EnumMath", source, "Eq", true, 2, 2);
        assertOutput("EnumMath", source, "Neq", true, 1, 2);
    }

    private void emitEnumSwitch() {
        String source = """
            public enum Level { Low, Medium, High }
            public struct Grades {
                public static int Score(Level level) {
                    switch (level) {
                        case Level.Low:
                            return 1;
                        case Level.High:
                            return 3;
                        default:
                            return 0;
                    }
                }
            }
            """;
        assertOutput("Grades", source, "Score", 1, 0);
        assertOutput("Grades", source, "Score", 0, 1);
        assertOutput("Grades", source, "Score", 3, 2);
    }

    /// A `const` field has no run-time storage: every read is replaced by the folded value, so
    /// these methods must answer without loading a field at all. The chain is what makes it a
    /// backend case rather than a semantic one - `Doubled` can only be emitted if the constant
    /// pass folded `Base` first.
    private void emitConstantFieldReads() {
        String source = """
            public enum Color { Red, Green = 4 }
            public struct Consts {
                public const int Base = 5;
                public const int Doubled = Base * 2;
                public const long Wide = Doubled;
                public const int Wrapped = unchecked(int.MaxValue + 1);
                public const int FromEnum = (int) Color.Green;
                public const string Tag = "v" + "1";
                public const char Ch = 'A';
                public const int FromChar = Ch + 1;
                public const double Half = 1.0 / 2;

                public static int ReadDoubled() { return Doubled; }
                public static long ReadWide() { return Wide; }
                public static int ReadWrapped() { return Wrapped; }
                public static int ReadFromEnum() { return FromEnum; }
                public static string ReadTag() { return Tag; }
                public static int ReadFromChar() { return FromChar; }
                public static double ReadHalf() { return Half; }
            }
            """;
        assertOutput("Consts", source, "ReadDoubled", 10);
        assertOutput("Consts", source, "ReadWide", 10L);
        assertOutput("Consts", source, "ReadWrapped", -2147483648);
        assertOutput("Consts", source, "ReadFromEnum", 4);
        assertOutput("Consts", source, "ReadTag", "v1");
        assertOutput("Consts", source, "ReadFromChar", 66);
        assertOutput("Consts", source, "ReadHalf", 0.5);
    }

    private void emitEnumNameRendering() {
        String source = """
            public enum Status { Draft, Active, Archived = 99 }
            public struct Formatter {
                public static string FormatDraft() { return "State: " + Status.Draft; }
                public static string FormatActive() { return "State: " + Status.Active; }
                public static string FormatArchived() { return "State: " + Status.Archived; }
                public static string FormatUnknown(Status s) { return "State: " + s; }
                public static string FormatInterpolation() { return $"State: {Status.Active}"; }
            }
            """;
        assertOutput("Formatter", source, "FormatDraft", "State: Draft");
        assertOutput("Formatter", source, "FormatActive", "State: Active");
        assertOutput("Formatter", source, "FormatArchived", "State: Archived");
        assertOutput("Formatter", source, "FormatUnknown", "State: 42", 42);
        assertOutput("Formatter", source, "FormatInterpolation", "State: Active");
    }

    /// A string switch compares with `String.equals` in source order. `case null:` is tested
    /// first, which is both C#'s answer for a null governing value and what keeps every
    /// `equals` receiver non-null.
    private void emitStringSwitch() {
        String source = """
            public struct Router {
                public static int Route(string path) {
                    switch (path) {
                        case null:
                            return -1;
                        case "get":
                            return 1;
                        case "put":
                            goto case "post";
                        case "post":
                            return 2;
                        default:
                            return 0;
                    }
                }
            }
            """;
        assertOutput("Router", source, "Route", 1, (Object) "get");
        assertOutput("Router", source, "Route", 2, (Object) "post");
        assertOutput("Router", source, "Route", 2, (Object) "put");
        assertOutput("Router", source, "Route", -1, (Object) null);
        assertOutput("Router", source, "Route", 0, (Object) "other");
        // Equality, not identity: a string built at run time takes the same branch.
        assertOutput("Router", source, "Route", 1, (Object) new String("get"));
    }

    private void emitCharSwitch() {
        String source = """
            public struct Letters {
                public static int Rank(char c) {
                    switch (c) {
                        case 'a':
                            return 1;
                        case 'z':
                            return 26;
                        default:
                            return 0;
                    }
                }
            }
            """;
        assertOutput("Letters", source, "Rank", 1, 'a');
        assertOutput("Letters", source, "Rank", 26, 'z');
        assertOutput("Letters", source, "Rank", 0, 'm');
    }

    /// A local function is emitted as a static method of the enclosing holder and has no
    /// receiver, though C# gives it no `static` modifier to say so - which is what made a
    /// call mistake its first argument for one.
    private void emitLocalFunctions() {
        String source = """
            public struct Helpers {
                static int total;
                public static int Twice(int n) {
                    int Double(int v) { return v * 2; }
                    return Double(n);
                }
                public static int Factorial(int n) {
                    int Fact(int v) { return v <= 1 ? 1 : v * Fact(v - 1); }
                    return Fact(n);
                }
                public static int Accumulate(int n) {
                    total = 0;
                    void Add(int v) { total = total + v; }
                    Add(n);
                    Add(n);
                    return total;
                }
            }
            """;
        assertOutput("Helpers", source, "Twice", 14, 7);
        assertOutput("Helpers", source, "Factorial", 120, 5);
        assertOutput("Helpers", source, "Factorial", 1, 0);
        assertOutput("Helpers", source, "Accumulate", 6, 3);
    }

    /// Captured variables are shared storage, not value snapshots. This pins writes through
    /// ordinary calls, wide/reference carriers, a throwing call, and the alias where one
    /// variable is simultaneously an explicit ref argument and an implicit capture.
    private void emitMutableLocalFunctionCaptures() {
        String source = """
            using System;
            public struct CaptureCells {
                int state;

                public static string Basic(int seed) {
                    int value = seed;
                    long wide = 10L;
                    string text = "a";
                    int Read() => value;
                    int Original() => seed;
                    void Change(int amount) {
                        value = value + amount;
                        wide = wide + 2L;
                        text = text + "b";
                        seed++;
                    }
                    Change(2);
                    return Read() + "|" + value + "|" + wide + "|" + text
                        + "|" + Original();
                }

                public static int Alias() {
                    int value = 1;
                    void Change(ref int other) {
                        value++;
                        other++;
                    }
                    Change(ref value);
                    return value;
                }

                public static int Exceptional() {
                    int value = 1;
                    void Change() {
                        value = 9;
                        throw new InvalidOperationException();
                    }
                    try {
                        Change();
                    } catch (InvalidOperationException) {
                    }
                    return value;
                }

                public int Instance(int seed) {
                    state = seed;
                    int Change(int amount) {
                        state = state + amount;
                        return state;
                    }
                    return Change(2);
                }
            }
            """;
        assertOutput("CaptureCells", source, "Basic", "5|5|12|ab|4", 3);
        assertOutput("CaptureCells", source, "Alias", 3);
        assertOutput("CaptureCells", source, "Exceptional", 9);
        Class<?> type = compileAndLoad("CaptureCells", source);
        try {
            Object instance = type.getConstructor().newInstance();
            Assert.equal(7, type.getMethod("Instance", int.class).invoke(instance, 5),
                    "a local function inherits its enclosing instance receiver");
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("instance receiver capture should execute", failure);
        }
    }

    /// Regression for the FlowAnalysis defect where an expression-bodied local function
    /// contributed no normal-return state, so a call in an `if` condition made both branches
    /// - and everything after the `if` - unreachable and the emitted method fell into the
    /// `aconst_null`/`athrow` tail.
    private void emitExpressionBodiedLocalFunctionCall() {
        String source = """
            public struct ExpressionBodyFlow {
                public static int Run() {
                    int value = 5;
                    int Read() => value;
                    void Bump() { value = 7; }
                    Bump();
                    if (Read() == 7) return Read();
                    return -1;
                }
            }
            """;
        assertOutput("ExpressionBodyFlow", source, "Run", 7);
    }

    /// Middle does not mention `value` directly; it needs the cell only because it calls
    /// Leaf. Sum calls itself recursively and every frame must forward the same cell.
    private void emitTransitiveLocalFunctionCaptures() {
        String source = """
            public struct CaptureGraph {
                public static int Nested(int start) {
                    int value = start;
                    int Middle(int amount) {
                        int Leaf(int delta) {
                            value = value + delta;
                            return value;
                        }
                        return Leaf(amount);
                    }
                    return Middle(4) * 10 + value;
                }

                public static int Recursive() {
                    int total = 0;
                    int Sum(int n) {
                        if (n == 0) return total;
                        total = total + n;
                        return Sum(n - 1);
                    }
                    int result = Sum(3);
                    return result * 10 + total;
                }

                public static string Generic() {
                    int calls = 0;
                    T Identity<T>(T value) {
                        calls++;
                        return value;
                    }
                    return Identity(4) + "|" + Identity("x") + "|" + calls;
                }
            }
            """;
        assertOutput("CaptureGraph", source, "Nested", 77, 3);
        assertOutput("CaptureGraph", source, "Recursive", 66);
        assertOutput("CaptureGraph", source, "Generic", "4|x|2");
    }

    /// These locals do not come from an ordinary local-declaration slot: foreach, pattern
    /// and catch introduce them in their own lowering paths. Each must still use the same
    /// capture-cell contract as a plain local.
    private void emitScopedLocalFunctionCaptures() {
        String source = """
            using System;
            public struct ScopedCaptures {
                public static int ForeachLocal() {
                    int total = 0;
                    foreach (int item in new int[] { 2, 3 }) {
                        void Add() { total = total + item; }
                        Add();
                    }
                    return total;
                }

                public static int PatternLocal(object input) {
                    if (input is int number) {
                        int Increment() { return ++number; }
                        return Increment() + number;
                    }
                    return -1;
                }

                public static int CatchLocal() {
                    try {
                        throw new InvalidOperationException();
                    } catch (Exception error) {
                        int Seen() => error == null ? 0 : 1;
                        return Seen();
                    }
                }
            }
            """;
        assertOutput("ScopedCaptures", source, "ForeachLocal", 5);
        assertOutput("ScopedCaptures", source, "PatternLocal", 12,
                Integer.valueOf(5));
        assertOutput("ScopedCaptures", source, "CatchLocal", 1);
    }

    /// Generic declarations have one erased JVM body. The selected call still carries its
    /// inferred source types, so value arguments/results box and unbox at that boundary while
    /// reference arguments/results cast back from Object. Calling the specialized descriptor
    /// directly used to compile cleanly and then fail with NoSuchMethodError.
    private void emitGenericLocalFunctions() {
        String source = """
            public struct GenericCalls {
                public static string Run() {
                    T Identity<T>(T value) { return value; }
                    int number = Identity(41) + 1;
                    string word = Identity("generic");
                    uint unsigned = Identity(uint.MaxValue);
                    return number + "|" + word + "|" + unsigned;
                }
            }
            """;
        assertOutput("GenericCalls", source, "Run", "42|generic|4294967295");

        Class<?> program = compileAndLoad("GenericCalls", source);
        try {
            Method erased = program.getMethod("Run$Identity", Object.class);
            Assert.equal(Object.class, erased.getReturnType(),
                    "generic local declaration keeps one erased descriptor");
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("generic local function was not emitted erased", failure);
        }
    }

    /// Optional defaults are call-site arguments, and named arguments are evaluated in the
    /// order written even though the JVM operand stack must ultimately use parameter order.
    private void emitOptionalArguments() {
        String source = """
            public struct OptionalArguments {
                public static int BeforeDeclaration() { return Add(39); }
                static int Add(int x, int y = 1 + 2) { return x + y; }

                static int Arrange(int a, int b = 2, int c = 3) {
                    return a * 100 + b * 10 + c;
                }

                public static int NamedOmission() { return Arrange(c: 9, a: 4); }

                public static int NamedEvaluationOrder() {
                    int value = 0;
                    return Arrange(c: value++, a: value++);
                }

                static int Pick(int x) { return 1; }
                static int Pick(int x, int y = 0) { return 2; }
                public static int PreferExactArity() { return Pick(5); }

                static long Widen(int x, long y = 2) { return x + y; }
                public static long ConvertedDefault() { return Widen(40); }

                static int Defaulted(int x = default) { return x; }
                public static int TargetTypedDefault() { return Defaulted(); }

                public static int LocalDefault() {
                    int LocalAdd(int x, int y = 2) { return x + y; }
                    return LocalAdd(40);
                }
            }
            """;
        assertOutput("OptionalArguments", source, "BeforeDeclaration", 42);
        assertOutput("OptionalArguments", source, "NamedOmission", 429);
        assertOutput("OptionalArguments", source, "NamedEvaluationOrder", 120);
        assertOutput("OptionalArguments", source, "PreferExactArity", 1);
        assertOutput("OptionalArguments", source, "ConvertedDefault", 42L);
        assertOutput("OptionalArguments", source, "TargetTypedDefault", 0);
        assertOutput("OptionalArguments", source, "LocalDefault", 42);
    }

    /// the rule's first vertical proof deliberately stays on `int?`, but crosses every boundary
    /// the carrier decision affects: locals, both states, intrinsic members, a guarded Value
    /// failure, coalescing, lifted arithmetic, arrays, and static field/parameter/return IO.
    private void emitNullableIntVertical() {
        String source = """
            using System;
            public struct NullableInt {
                public static int? Stored;

                public static int? Echo(int? value) { return value; }
                public static object Box(int? value) { return value; }

                public static int State(int? value) {
                    if (!value.HasValue) { return -1; }
                    return value.Value;
                }

                public static int Coalesce(int? value) { return value ?? 5; }

                public static int Lift(int? left, int? right) {
                    int? sum = left + right;
                    return sum ?? -1;
                }

                public static int RoundTrip(int seed, bool clear) {
                    int? local = seed;
                    if (clear) { local = null; }
                    Stored = Echo(local);
                    return Stored ?? 9;
                }

                public static int ArrayRoundTrip(int? value) {
                    int?[] values = new int?[] { value, null, 3 };
                    return (values[0] ?? 0) + (values[1] ?? 4) + (values[2] ?? 0);
                }

                public static int?[] ArrayEcho(int?[] values) { return values; }

                public static int MissingValueThrows() {
                    int? value = null;
                    try { return value.Value; }
                    catch (Exception error) { return 77; }
                }
            }
            """;
        assertOutput("NullableInt", source, "Echo", 12, (Object) 12);
        assertOutput("NullableInt", source, "Echo", null, (Object) null);
        assertOutput("NullableInt", source, "Box", 12, (Object) 12);
        assertOutput("NullableInt", source, "Box", null, (Object) null);
        assertOutput("NullableInt", source, "State", 12, (Object) 12);
        assertOutput("NullableInt", source, "State", -1, (Object) null);
        assertOutput("NullableInt", source, "Coalesce", 12, (Object) 12);
        assertOutput("NullableInt", source, "Coalesce", 5, (Object) null);
        assertOutput("NullableInt", source, "Lift", 9, (Object) 4, (Object) 5);
        assertOutput("NullableInt", source, "Lift", -1, (Object) 4, (Object) null);
        assertOutput("NullableInt", source, "RoundTrip", 42, 42, false);
        assertOutput("NullableInt", source, "RoundTrip", 9, 42, true);
        assertOutput("NullableInt", source, "ArrayRoundTrip", 13, (Object) 6);
        assertOutput("NullableInt", source, "ArrayRoundTrip", 7, (Object) null);
        assertOutput("NullableInt", source, "MissingValueThrows", 77);
        try {
            Class<?> emitted = compileAndLoad("NullableInt", source);
            Assert.equal(Object.class, emitted.getField("Stored").getType(),
                    "nullable field descriptor");
            Method echo = emitted.getMethod("Echo", Object.class);
            Assert.equal(Object.class, echo.getParameterTypes()[0],
                    "nullable parameter descriptor");
            Assert.equal(Object.class, echo.getReturnType(), "nullable return descriptor");
            Method arrayEcho = emitted.getMethod("ArrayEcho", Object[].class);
            Assert.equal(Object[].class, arrayEcho.getParameterTypes()[0],
                    "nullable array parameter descriptor");
            Assert.equal(Object[].class, arrayEcho.getReturnType(),
                    "nullable array return descriptor");
        } catch (ReflectiveOperationException error) {
            throw new RuntimeException(error);
        }
    }

    private void emitNullableOperatorClosure() {
        String source = """
            using System;
            public struct NullableOperators {
                static int Calls;
                static int? Mutable;

                static int? Next(bool empty, int value) {
                    Calls = Calls + 1;
                    if (empty) { return null; }
                    return value;
                }

                public static bool Equal(int? left, int? right) { return left == right; }
                public static bool NotEqual(int? left, int? right) { return left != right; }
                public static bool Less(int? left, int? right) { return left < right; }
                public static bool AtLeast(int? left, int? right) { return left >= right; }

                public static int Negate(int? value) { return (-value) ?? 99; }
                public static int Identity(int? value) { return (+value) ?? 99; }
                public static int Complement(int? value) { return (~value) ?? 99; }
                public static int And(int? left, int? right) { return (left & right) ?? -1; }
                public static int Or(int? left, int? right) { return (left | right) ?? -1; }
                public static int Xor(int? left, int? right) { return (left ^ right) ?? -1; }
                public static int ShiftLeft(int? value, int? count) {
                    return (value << count) ?? -1;
                }
                public static int ShiftRight(int? value, int count) {
                    return (value >> count) ?? -1;
                }

                public static int PostIncrement(int? value) {
                    int? before = value++;
                    return (before ?? -10) * 100 + (value ?? -10);
                }
                public static int PreDecrement(int? value) { return (--value) ?? -10; }
                public static int FieldIncrement(int? value) {
                    Mutable = value;
                    return (++Mutable) ?? -10;
                }
                public static int ArrayIncrement(int? value) {
                    int?[] values = new int?[] { value };
                    int? before = values[0]++;
                    return (before ?? -10) * 100 + (values[0] ?? -10);
                }

                public static int BoolNot(bool? value) {
                    bool? result = !value;
                    if (!result.HasValue) { return -1; }
                    return result.Value ? 1 : 0;
                }

                public static int BoolAnd(bool? left, bool? right) {
                    bool? result = left & right;
                    if (!result.HasValue) { return -1; }
                    return result.Value ? 1 : 0;
                }

                public static int BoolOr(bool? left, bool? right) {
                    bool? result = left | right;
                    if (!result.HasValue) { return -1; }
                    return result.Value ? 1 : 0;
                }

                public static int BoolXor(bool? left, bool? right) {
                    bool? result = left ^ right;
                    if (!result.HasValue) { return -1; }
                    return result.Value ? 1 : 0;
                }

                public static int CastValue(int? value) { return (int)value; }
                public static int CastMissing() {
                    int? value = null;
                    try { return (int)value; }
                    catch (Exception error) { return 77; }
                }
                public static int CastNarrow(long? value) { return ((int?)value) ?? -1; }
                public static string CastReference(object value) { return (string)value; }

                public static int Effects() {
                    Calls = 0;
                    bool equal = Next(true, 0) == Next(false, 1);
                    return Calls * 10 + (equal ? 1 : 0);
                }
            }
            """;

        assertOutput("NullableOperators", source, "Equal", true, (Object) 1000, (Object) 1000);
        assertOutput("NullableOperators", source, "Equal", true, (Object) null, (Object) null);
        assertOutput("NullableOperators", source, "Equal", false, (Object) 1, (Object) null);
        assertOutput("NullableOperators", source, "NotEqual", true, (Object) 1, (Object) null);
        assertOutput("NullableOperators", source, "Less", true, (Object) 1, (Object) 2);
        assertOutput("NullableOperators", source, "Less", false, (Object) null, (Object) 2);
        assertOutput("NullableOperators", source, "AtLeast", true, (Object) 2, (Object) 2);

        assertOutput("NullableOperators", source, "Negate", -5, (Object) 5);
        assertOutput("NullableOperators", source, "Negate", 99, (Object) null);
        assertOutput("NullableOperators", source, "Identity", 5, (Object) 5);
        assertOutput("NullableOperators", source, "Complement", -6, (Object) 5);
        assertOutput("NullableOperators", source, "And", 8, (Object) 12, (Object) 10);
        assertOutput("NullableOperators", source, "Or", 14, (Object) 12, (Object) 10);
        assertOutput("NullableOperators", source, "Xor", 6, (Object) 12, (Object) 10);
        assertOutput("NullableOperators", source, "And", -1, (Object) null, (Object) 10);
        assertOutput("NullableOperators", source, "ShiftLeft", 48, (Object) 12, (Object) 2);
        assertOutput("NullableOperators", source, "ShiftLeft", -1, (Object) 12, (Object) null);
        assertOutput("NullableOperators", source, "ShiftRight", -3, (Object) (-12), 2);
        assertOutput("NullableOperators", source, "PostIncrement", 405, (Object) 4);
        assertOutput("NullableOperators", source, "PostIncrement", -1010, (Object) null);
        assertOutput("NullableOperators", source, "PreDecrement", 3, (Object) 4);
        assertOutput("NullableOperators", source, "PreDecrement", -10, (Object) null);
        assertOutput("NullableOperators", source, "FieldIncrement", 5, (Object) 4);
        assertOutput("NullableOperators", source, "FieldIncrement", -10, (Object) null);
        assertOutput("NullableOperators", source, "ArrayIncrement", 405, (Object) 4);
        assertOutput("NullableOperators", source, "ArrayIncrement", -1010, (Object) null);

        assertOutput("NullableOperators", source, "BoolNot", 0, (Object) true);
        assertOutput("NullableOperators", source, "BoolNot", -1, (Object) null);
        assertOutput("NullableOperators", source, "BoolAnd", 0, (Object) false, (Object) null);
        assertOutput("NullableOperators", source, "BoolAnd", -1, (Object) true, (Object) null);
        assertOutput("NullableOperators", source, "BoolOr", 1, (Object) true, (Object) null);
        assertOutput("NullableOperators", source, "BoolOr", -1, (Object) false, (Object) null);
        assertOutput("NullableOperators", source, "BoolXor", 1, (Object) true, (Object) false);
        assertOutput("NullableOperators", source, "BoolXor", -1, (Object) true, (Object) null);

        assertOutput("NullableOperators", source, "CastValue", 42, (Object) 42);
        assertOutput("NullableOperators", source, "CastMissing", 77);
        assertOutput("NullableOperators", source, "CastNarrow", 42, (Object) 42L);
        assertOutput("NullableOperators", source, "CastNarrow", -1, (Object) null);
        assertOutput("NullableOperators", source, "CastReference", "text", (Object) "text");
        assertOutput("NullableOperators", source, "Effects", 20);
    }

    private void emitNullablePrimitiveMatrix() {
        String source = """
            public enum NullableColor { Red = 3 }

            public struct NullableCarriers {
                public static int SByteValue(sbyte? value) { return value.Value; }
                public static int ByteValue(byte? value) { return value.Value; }
                public static int ShortValue(short? value) { return value.Value; }
                public static int UShortValue(ushort? value) { return value.Value; }
                public static int IntValue(int? value) { return value.Value; }
                public static long UIntValue(uint? value) { return value.Value; }
                public static long LongValue(long? value) { return value.Value; }
                public static ulong ULongValue(ulong? value) { return value.Value; }
                public static nint NIntValue(nint? value) { return value.Value; }
                public static nuint NUIntValue(nuint? value) { return value.Value; }
                public static int CharValue(char? value) { return value.Value; }
                public static float FloatValue(float? value) { return value.Value; }
                public static double DoubleValue(double? value) { return value.Value; }
                public static bool BoolValue(bool? value) { return value.Value; }
                public static int EnumValue(NullableColor? value) { return (int)value.Value; }

                public static long? WidenInt(int? value) { return value; }
                public static double? WidenFloat(float? value) { return value; }
                public static long AddLong(long? left, long? right) {
                    return (left + right) ?? -1L;
                }
                public static double AddDouble(double? left, double? right) {
                    return (left + right) ?? -1.0;
                }
            }
            """;

        assertOutput("NullableCarriers", source, "SByteValue", -5, (Object) (byte) -5);
        assertOutput("NullableCarriers", source, "ByteValue", 200, (Object) (byte) -56);
        assertOutput("NullableCarriers", source, "ShortValue", -1234, (Object) (short) -1234);
        assertOutput("NullableCarriers", source, "UShortValue", 40000,
                (Object) (short) -25536);
        assertOutput("NullableCarriers", source, "IntValue", 42, (Object) 42);
        assertOutput("NullableCarriers", source, "UIntValue", 42L, (Object) 42);
        assertOutput("NullableCarriers", source, "LongValue", 42L, (Object) 42L);
        assertOutput("NullableCarriers", source, "ULongValue", 42L, (Object) 42L);
        assertOutput("NullableCarriers", source, "NIntValue", 42L, (Object) 42L);
        assertOutput("NullableCarriers", source, "NUIntValue", 42L, (Object) 42L);
        assertOutput("NullableCarriers", source, "CharValue", 65, (Object) 'A');
        assertOutput("NullableCarriers", source, "FloatValue", 1.5f, (Object) 1.5f);
        assertOutput("NullableCarriers", source, "DoubleValue", 2.5, (Object) 2.5);
        assertOutput("NullableCarriers", source, "BoolValue", true, (Object) true);
        assertOutput("NullableCarriers", source, "EnumValue", 3, (Object) 3);
        assertOutput("NullableCarriers", source, "WidenInt", 42L, (Object) 42);
        assertOutput("NullableCarriers", source, "WidenInt", null, (Object) null);
        assertOutput("NullableCarriers", source, "WidenFloat", 1.5, (Object) 1.5f);
        assertOutput("NullableCarriers", source, "AddLong", 9L, (Object) 4L, (Object) 5L);
        assertOutput("NullableCarriers", source, "AddLong", -1L, (Object) 4L, (Object) null);
        assertOutput("NullableCarriers", source, "AddDouble", 4.0, (Object) 1.5,
                (Object) 2.5);
    }

    private void emitAsMatch() {
        // `o as string` succeeds: instanceof true, checkcast, the same string comes back.
        String source = """
            public struct AsTest {
                public static string TryString(object o) {
                    return o as string;
                }
            }
            """;
        assertOutput("AsTest", source, "TryString", "hello", (Object) "hello");
    }

    private void emitAsMismatch() {
        // `o as string` fails against a boxed Integer: yields null rather than throwing.
        String source = """
            public struct AsTest {
                public static string TryString(object o) {
                    return o as string;
                }
            }
            """;
        assertOutput("AsTest", source, "TryString", null, (Object) 5);
    }

    private static final String RANGE_ALL_FORMS_SOURCE = """
        public struct Ranges {
            public static int ConstructAllForms(int a, int b) {
                var full = a..b;
                var openStart = ..b;
                var openEnd = a..;
                var all = ..;
                return a + b;
            }
        }
        """;

    private void emitRangeConstructsAllForms() {
        // A VsRange value has no spellable source-level type in this subset yet (it is
        // TypeSymbol.Range, a compiler-internal value with no implicit conversion to
        // `object` - Conversions.isReferenceType does not cover it, matching real C#'s
        // distinct boxing-conversion rule, which this subset does not implement in
        // general), so the constructed ranges cannot cross the reflection boundary as a
        // return value. This is an execution smoke test instead: all four forms
        // (`a..b`, `..b`, `a..`, `..`) construct without a verifier or link error, and
        // the trailing `a + b` proves the surrounding stack/locals were left undisturbed
        // by whichever construction sequence ran immediately before it.
        assertOutput("Ranges", RANGE_ALL_FORMS_SOURCE, "ConstructAllForms", 7, 3, 4);
    }

    private void emitRangeReferencesCorrectFactories() {
        // Structural check (CLAUDE.md: "inspect and test emitted bytecode structurally"):
        // confirms each of the four forms dispatches to ITS OWN matching VsRange/VsIndex
        // factory, not merely some factory with a compatible descriptor - the execution
        // test above cannot distinguish e.g. a same-descriptor startAt/endAt mix-up.
        SourceFile file = TestSources.styled("Ranges.vs", RANGE_ALL_FORMS_SOURCE);
        Compilation compilation = Compilation.of(java.util.List.of(file));
        CompilationResult result = compilation.emit();
        Assert.isFalse(result.hasErrors(), "compilation should succeed");

        java.util.Map<String, byte[]> classes = new java.util.LinkedHashMap<>();
        for (UnitAnalysis unit : result.units()) {
            if (unit instanceof UnitAnalysis.Emitted emitted && emitted.file().name().equals("Ranges.vs")) {
                classes.putAll(emitted.classes());
            }
        }
        Assert.isTrue(!classes.isEmpty(), "bytecode should be generated");

        java.lang.classfile.ClassModel model = java.lang.classfile.ClassFile.of().parse(classes.values().iterator().next());
        java.util.Set<String> memberRefs = new java.util.HashSet<>();
        for (var entry : model.constantPool()) {
            if (entry instanceof java.lang.classfile.constantpool.MemberRefEntry member
                    && member.owner().asInternalName().startsWith("vsharp/runtime/Vs")) {
                memberRefs.add(member.owner().asInternalName() + "." + member.name().stringValue());
            }
        }
        Assert.isTrue(memberRefs.contains("vsharp/runtime/VsRange.ALL"), "references VsRange.ALL for '..'");
        Assert.isTrue(memberRefs.contains("vsharp/runtime/VsRange.startAt"), "references VsRange.startAt for 'a..'");
        Assert.isTrue(memberRefs.contains("vsharp/runtime/VsRange.endAt"), "references VsRange.endAt for '..b'");
        Assert.isTrue(memberRefs.contains("vsharp/runtime/VsRange.<init>"), "references VsRange.<init> for 'a..b'");
        Assert.isTrue(memberRefs.contains("vsharp/runtime/VsIndex.fromStart"),
                "references VsIndex.fromStart for every int endpoint");
    }

    private void emitRangeIndexFromEnd() {
        String source = """
            public struct Ranges {
                public static int FromEnd(int a) {
                    var range = ^a..;
                    var range2 = ..^a;
                    return a;
                }
            }
            """;
        // The return simply proves execution didn't throw and stack wasn't messed up
        assertOutput("Ranges", source, "FromEnd", 5, 5);
        
        // Structure check to ensure fromEnd is actually used for the ^ operator
        SourceFile file = TestSources.styled("Ranges.vs", source);
        Compilation compilation = Compilation.of(java.util.List.of(file));
        CompilationResult result = compilation.emit();
        java.util.Map<String, byte[]> classes = new java.util.LinkedHashMap<>();
        for (UnitAnalysis unit : result.units()) {
            if (unit instanceof UnitAnalysis.Emitted emitted && emitted.file().name().equals("Ranges.vs")) {
                classes.putAll(emitted.classes());
            }
        }
        java.lang.classfile.ClassModel model = java.lang.classfile.ClassFile.of().parse(classes.values().iterator().next());
        java.util.Set<String> memberRefs = new java.util.HashSet<>();
        for (var entry : model.constantPool()) {
            if (entry instanceof java.lang.classfile.constantpool.MemberRefEntry member
                    && member.owner().asInternalName().startsWith("vsharp/runtime/Vs")) {
                memberRefs.add(member.owner().asInternalName() + "." + member.name().stringValue());
            }
        }
        Assert.isTrue(memberRefs.contains("vsharp/runtime/VsIndex.fromEnd"),
                "references VsIndex.fromEnd for ^ endpoints");
    }

    private void emitBoxingConversion() {
        String source = """
            public struct BoxTest {
                public static int RunBox(int n) {
                    var range = n..n;
                    object boxedRange = range;

                    object boxedInt = n;
                    if (boxedInt is int val) {
                        return val;
                    }
                    return -1;
                }
            }
            """;
        assertOutput("BoxTest", source, "RunBox", 5, 5);
    }

    private void emitJavaConstructorAndThrow() {
        String source = """
            using java.lang;
            public struct JavaThrow {
                public static int Run() {
                    try {
                        throw new IllegalArgumentException("boom");
                    } catch (IllegalArgumentException error) {
                        return 42;
                    }
                }
            }
            """;
        assertOutput("JavaThrow", source, "Run", 42);
    }

    private void emitJavaStaticFieldRead() {
        String source = """
            using java.lang;
            public struct JavaStaticField {
                public static int Run() {
                    return Integer.MAX_VALUE;
                }
            }
            """;
        assertOutput("JavaStaticField", source, "Run", Integer.MAX_VALUE);
    }

    /// Widening to an interface costs no instruction, a downcast is guarded by `checkcast`,
    /// and a call through an interface-typed receiver must use `invokeinterface`. The
    /// last one is why this executes: binding and descriptors were already correct when
    /// `invokevirtual` on `java.util.List` failed with `IncompatibleClassChangeError` at the
    /// first call. `Collections.Sort` is the ordinary JDK call the whole conversion exists for.
    /// A corelib exception is its JVM carrier everywhere, not only in the `catch` clause that
    /// tests it. The call here is what proves it: `invokevirtual` takes its owner from
    /// the receiver's static type, so a `System.Exception` local that descriptor-ised to
    /// `System/Exception` produced a constant-pool reference to a class that is never emitted
    /// and never loads - a class file that compiles, verifies structurally, and dies at the
    /// first call. The members reached are the carrier's own, under the same PascalCase
    /// translation every Java receiver uses: `ToString` renders the JVM class name,
    /// which is the divergence from .NET's message-only rendering that the carrier choice
    /// already documents.
    private void emitCorelibCarrierMembers() {
        String source = """
            using System;
            public struct CarrierMembers {
                public static string Run() {
                    try {
                        throw new InvalidOperationException("boom");
                    } catch (Exception error) {
                        return error.Message + "|" + error.ToString();
                    }
                }
            }
            """;
        assertOutput("CarrierMembers", source,
                "Run", "boom|java.lang.IllegalStateException: boom");
    }

    /// A C# property read binds the JavaBeans accessor the JDK declares for it, so
    /// `Message`, `Key` and `Value` are written as C# writes them rather than as `GetKey()`.
    /// The rule fires only after the written name and its camelCase translation both find
    /// nothing: `entry.GetKey()` below still resolves directly, and both spellings must reach
    /// the same method for the bound access to be a completed call rather than a method group
    /// waiting for an argument list that never comes.
    private void emitJavaBeanProperties() {
        String source = """
            using java.util;
            public struct BeanProperties {
                public static string Run() {
                    TreeMap<string, int> map = new TreeMap<string, int>();
                    map.Put("a", 1);
                    string joined = "";
                    foreach (Map.Entry<string, int> entry in map.EntrySet()) {
                        joined = joined + entry.Key + "=" + entry.Value
                            + "/" + entry.GetKey();
                    }
                    return joined;
                }
            }
            """;
        assertOutput("BeanProperties", source, "Run", "a=1/a");
    }

    /// `foreach (var (key, value) in map.EntrySet())` is the shape every C# program writes over
    /// a dictionary, and the JDK's carrier for that idea publishes its pair through two
    /// accessors instead of a `Deconstruct`. The curated interop deconstruction reads
    /// each component by calling the accessor the entry declares, so the loop binds per
    /// component with the component's own type rather than an erased `Object`: `value + 1`
    /// below is `int` arithmetic, not a boxing error.
    private void emitMapEntryDeconstructingForeach() {
        String source = """
            using java.util;
            public struct EntryDeconstruction {
                public static string Run() {
                    TreeMap<string, int> ages = new TreeMap<string, int>();
                    ages.Put("ana", 31);
                    ages.Put("bob", 42);
                    string joined = "";
                    int total = 0;
                    foreach (var (name, age) in ages.EntrySet()) {
                        joined = joined + name + ":" + (age + 1) + ";";
                        total = total + age + name.Length;
                    }
                    return joined + total;
                }
            }
            """;
        assertOutput("EntryDeconstruction", source, "Run", "ana:32;bob:43;79");
    }

    /// A lambda may have a statement block for a body, which is the shape an Allman
    /// program reaches for as soon as the body needs more than one expression.
    ///
    /// Four body shapes have to work, and they fail differently: a *closed* target
    /// (`Comparator<string>`) checks each `return` against the interface's declared result,
    /// an *open* one (`Stream.Map`'s `R`) is closed *by* what the returns produced, a `void`
    /// target mutates a captured local through its cell, and a body may declare a local
    /// function of its own. The last is why the emitted method name is chosen at declaration
    /// time: `Shout` is qualified under the lambda, so the class it lands in and the cells it
    /// forwards are decided by one spelling of the lambda's name rather than two.
    private void emitStatementBodiedLambdas() {
        String source = """
            using java.util;
            using java.util.stream;
            public struct StatementLambdas {
                public static string Run() {
                    ArrayList<string> words = new ArrayList<string>();
                    words.Add("delta");
                    words.Add("al");
                    words.Add("charlie");
                    words.Sort((a, b) =>
                    {
                        int left = a.Length;
                        int right = b.Length;
                        return left - right;
                    });
                    List<int> lengths = words.Stream().Map(w =>
                    {
                        int n = w.Length;
                        return n + 1;
                    }).Collect(Collectors.ToList());
                    int total = 0;
                    words.ForEach(w =>
                    {
                        if (w.Length > 3)
                        {
                            total = total + w.Length;
                        }
                    });
                    List<string> shouts = words.Stream().Map(w =>
                    {
                        string Shout(string s)
                        {
                            return s.ToUpperInvariant();
                        }
                        return Shout(w);
                    }).Collect(Collectors.ToList());
                    return words.Get(0) + "|" + lengths.Get(0) + "|" + total
                        + "|" + shouts.Get(2);
                }
            }
            """;
        assertOutput("StatementLambdas", source, "Run", "al|3|12|CHARLIE");
    }

    /// The grouping Collectors are a static generic method whose type parameters appear only in
    /// its functional parameter, so nothing but the lambda can close them. Each call
    /// below infers a different shape - a `char` key over `List<string>` values, a `string` key
    /// over `int` values, and a downstream `Counting()` whose `Long` carrier reaches a written
    /// `long` - and every one of them has to execute, not merely bind.
    private void emitGroupingCollectors() {
        String source = """
            using java.util;
            using java.util.stream;
            public struct GroupingCollectors {
                public static string Run() {
                    ArrayList<string> words = new ArrayList<string>();
                    words.Add("apple");
                    words.Add("avocado");
                    words.Add("banana");
                    Map<char, List<string>> byFirst =
                        words.Stream().Collect(Collectors.GroupingBy(w => w[0]));
                    Map<string, int> lengths =
                        words.Stream().Collect(Collectors.ToMap(w => w, w => w.Length));
                    Map<char, long> counts = words.Stream()
                        .Collect(Collectors.GroupingBy(w => w[0], Collectors.Counting()));
                    return byFirst.Get('a').Size() + "|" + lengths.Get("banana")
                        + "|" + counts.Get('b');
                }
            }
            """;
        assertOutput("GroupingCollectors", source, "Run", "2|6|1");
    }

    private void emitJavaReferenceConversions() {
        String source = """
            using java.lang;
            using java.util;
            public struct JavaConversions {
                public static string Run() {
                    ArrayList values = new ArrayList();
                    values.Add("delta");
                    values.Add("alpha");
                    Object widened = values;
                    List asInterface = (List)widened;
                    Collections.Sort(values);
                    return asInterface.Size() + "|" + values.Get(0) + "|" + widened.ToString();
                }
            }
            """;
        assertOutput("JavaConversions", source, "Run", "2|alpha|[alpha, delta]");
    }

    /// A Java type parameter's bound is the carrier every position typed by it takes, and the
    /// descriptor the call site must name. `EnumSet.of` is `(Ljava/lang/Enum;
    /// Ljava/lang/Enum;)Ljava/util/EnumSet;` and `Enum.compareTo` takes `Ljava/lang/Enum;`,
    /// not `Ljava/lang/Object;` - naming `Object` produces `NoSuchMethodError` at the first
    /// call, which is why this executes rather than only compiles. Inference still produces
    /// the source-visible `EnumSet<DayOfWeek>`, so `Contains` and the element type stay exact
    /// while the emitted signature stays erased.
    private void emitBoundedJavaGenerics() {
        String source = """
            using java.util;
            using java.time;
            public struct BoundedGenerics {
                public static string Run() {
                    EnumSet<DayOfWeek> days = EnumSet.Of(DayOfWeek.MONDAY, DayOfWeek.FRIDAY);
                    DayOfWeek monday = DayOfWeek.Of(1);
                    int ordered = monday.CompareTo(DayOfWeek.Of(5));
                    return days.Size() + "|" + days.Contains(DayOfWeek.MONDAY) + "|" + ordered;
                }
            }
            """;
        assertOutput("BoundedGenerics", source, "Run", "2|True|-4");
    }

    /// `Stream.collect` is `<R, A> R collect(Collector<? super T, A, R>)` and `Collectors.toList`
    /// is `<T> Collector<T, ?, List<T>>`: every interesting position is a wildcard, and erasing
    /// them made `Collect` return `Object`. Carrying them lets `R` be inferred as
    /// `List<string>` while the emitted call stays erased, so this executes rather than only
    /// compiling. `Joining` is the same shape with a concrete argument type.
    private void emitCollectorsThroughWildcards() {
        String source = """
                using java.util;
                using java.util.stream;

                public struct CollectorsIdioms
                {
                    public static string Run()
                    {
                        ArrayList<string> names = new ArrayList<string>();
                        names.Add("alpha");
                        names.Add("b");
                        List<string> kept = names.Stream().Filter(s => s.Length > 1)
                                .Collect(Collectors.ToList());
                        string joined = names.Stream().Collect(Collectors.Joining("-"));
                        return kept.Size() + "|" + kept.Get(0) + "|" + joined;
                    }
                }
                """;
        assertOutput("CollectorsIdioms", source, "Run", "1|alpha|alpha-b");
    }

    /// The declaration's type is the only thing that can type `n`, and it has to do so before
    /// the body binds. This executes rather than only compiling because the lambda is
    /// emitted through `LambdaMetafactory` against `ToIntFunction`: if `T` reached emission
    /// open, the indy descriptor would name a type the JVM cannot link.
    private void emitTargetTypedLambdaArgument() {
        String source = """
                using java.util;

                public struct TargetTypedLambda
                {
                    public static string Run()
                    {
                        ArrayList<string> names = new ArrayList<string>();
                        names.Add("bbb");
                        names.Add("a");
                        names.Add("cc");
                        Comparator<string> byLength = Comparator.ComparingInt(n => n.Length);
                        names.Sort(byLength);
                        return names.Get(0) + "|" + names.Get(1) + "|" + names.Get(2);
                    }
                }
                """;
        assertOutput("TargetTypedLambda", source, "Run", "a|cc|bbb");
    }

    /// The same lambda one step deeper: the call is an argument, so its own type arrives only
    /// at the conversion to `Sort`'s parameter, after its arguments were already bound.
    /// This executes because the flush has to do two things, and the first alone is invisible
    /// to a compile-only test: close the call's signature, and put the lambda it finally bound
    /// in the argument list. Leaving the deferred node there kept an open `ToIntFunction` as
    /// the argument carrier and the emission gate refused the call as an unsupported generic.
    private void emitTargetTypedLambdaInNestedCall() {
        String source = """
                using java.util;

                public struct NestedTargetTypedLambda
                {
                    public static string Run()
                    {
                        ArrayList<string> names = new ArrayList<string>();
                        names.Add("bbb");
                        names.Add("a");
                        names.Add("cc");
                        names.Sort(Comparator.ComparingInt(n => n.Length));
                        return names.Get(0) + "|" + names.Get(1) + "|" + names.Get(2);
                    }
                }
                """;
        assertOutput("NestedTargetTypedLambda", source, "Run", "a|cc|bbb");
    }

    /// `Comparator.NaturalOrder()` names `T` in no parameter: the target closes it.
    /// What is emitted is the erased `Comparator` either way, so this test exists to prove
    /// the closure reaches the backend as a real call and sorts - the shape that previously
    /// fell to the raw fallback and failed to convert at all.
    private void emitContextOnlyInference() {
        String source = """
                using java.util;

                public struct ContextOnly
                {
                    public static string Run()
                    {
                        ArrayList<string> names = new ArrayList<string>();
                        names.Add("pear");
                        names.Add("apple");
                        names.Sort(Comparator.NaturalOrder());
                        TreeSet<string> ordered = new TreeSet<string>(Comparator.NaturalOrder());
                        ordered.Add("b");
                        ordered.Add("a");
                        return names.Get(0) + "|" + names.Get(1) + "|" + ordered.First();
                    }
                }
                """;
        assertOutput("ContextOnly", source, "Run", "apple|pear|a");
    }

    /// `object.ToString()` is what a JDK method returning `Object` is usually asked for, so
    /// the keyword type must carry it. Display follows the same C#-faithful rules as
    /// concatenation - a boxed `bool` reads `True`, not Java's `true` - and a null receiver is
    /// `NullReferenceException`. The .NET 10 oracle produces `42`, `True` and the throw for the
    /// same program.
    private void emitObjectToString() {
        String source = """
            using java.lang;
            using System;
            public struct ObjectToString {
                public static string Run() {
                    object number = 42;
                    object flag = true;
                    object java = new StringBuilder();
                    object missing = null;
                    string thrown = "no throw";
                    try {
                        missing.ToString();
                    } catch (NullReferenceException e) {
                        thrown = "NRE";
                    }
                    return number.ToString() + "|" + flag.ToString() + "|"
                        + java.ToString().Length + "|" + thrown;
                }
            }
            """;
        assertOutput("ObjectToString", source, "Run", "42|True|0|NRE");
    }

    /// `Equals(object)` and `GetHashCode()` over every carrier that declares them. The
    /// expected string is the byte-for-byte output of the same program under the .NET 10
    /// oracle, and it pins precisely the places where the JVM carrier answers differently:
    /// `bool` hashes 1/0 rather than 1231/1237, `char` hashes `value | value << 16`, `NaN`
    /// equals itself while `==` does not, `-0.0` hashes as `0.0`, and `1.00m` equals and hashes
    /// with `1m` although `BigDecimal.equals` separates them. The cross-type cases are the
    /// other half of the contract: a boxed `int` is not equal to a boxed `long` or `double` of
    /// the same value, and a `bool` is not equal to `1`.
    private void emitUniversalMembers() {
        String source = """
            public struct UniversalMembers {
                public static string Run() {
                    object n = 7;
                    object l = 7L;
                    object d = 1.5;
                    object f = 1.5f;
                    object b = true;
                    object c = 'A';
                    object m = 1.00m;
                    object s = "ab";
                    double nan = double.NaN;
                    double inf = double.PositiveInfinity;
                    double ninf = double.NegativeInfinity;
                    double zero = 0.0;
                    double negzero = -0.0;
                    float fnan = float.NaN;
                    decimal one = 1m;
                    string hashes = n.GetHashCode() + "|" + l.GetHashCode() + "|"
                        + b.GetHashCode() + "|" + false.GetHashCode() + "|" + c.GetHashCode()
                        + "|" + d.GetHashCode() + "|" + f.GetHashCode() + "|" + m.GetHashCode()
                        + "|" + one.GetHashCode() + "|" + nan.GetHashCode() + "|"
                        + inf.GetHashCode() + "|" + ninf.GetHashCode() + "|"
                        + zero.GetHashCode() + "|" + negzero.GetHashCode() + "|"
                        + fnan.GetHashCode() + "|" + (-2.5m).GetHashCode() + "|"
                        + 0m.GetHashCode() + "|" + long.MinValue.GetHashCode();
                    string equal = n.Equals(7) + "|" + n.Equals(7L) + "|" + n.Equals(l) + "|"
                        + d.Equals(1.5) + "|" + d.Equals(n) + "|" + m.Equals(one) + "|"
                        + m.Equals(1) + "|" + nan.Equals(nan) + "|" + (nan == nan) + "|"
                        + zero.Equals(negzero) + "|" + fnan.Equals(fnan) + "|"
                        + s.Equals("ab") + "|" + s.Equals(null) + "|" + s.Equals('a') + "|"
                        + b.Equals(true) + "|" + b.Equals(1) + "|" + c.Equals('A');
                    bool sameHash = "ab".GetHashCode() == ("a" + "b").GetHashCode();
                    return hashes + "#" + equal + "#" + sameHash;
                }
            }
            """;
        assertOutput("UniversalMembers", source,
                "Run",
                "7|7|1|0|4259905|1073217536|1069547520|1|1|2146435072|2146435072|-1048576|0|0|"
                        + "2139095040|-2147418087|0|-2147483648"
                        + "#True|False|False|True|False|True|False|True|False|True|True|True|"
                        + "False|False|True|False|True#True");
    }

    /// A null receiver reaches the JVM class carrying `System.NullReferenceException`,
    /// which is what C# throws for the same program; the members are not null-tolerant helpers.
    private void emitUniversalMembersOnNull() {
        String source = """
            using System;
            public struct UniversalNull {
                public static string Run() {
                    object missing = null;
                    string thrown = "no throw";
                    try {
                        missing.GetHashCode();
                    } catch (NullReferenceException e) {
                        thrown = "NRE";
                    }
                    return thrown;
                }
            }
            """;
        assertOutput("UniversalNull", source, "Run", "NRE");
    }

    /// `object.GetType()` over both worlds, the last universal member. It is the one
    /// member whose C# and JVM names differ by more than case, so a V# carrier routes through
    /// `VsObject.type` while a Java receiver takes `getClass` directly - the program cannot
    /// tell which, which is the point. `System.Type` *is* `java.lang.Class`, so the result
    /// carries the whole reflective surface (`GetName`, `GetSimpleName`, both camelCase-
    /// translated) and compares by identity. The divergence pinned here is the *name*: .NET
    /// reports `System.Int32`, the JVM reports `java.lang.Integer`, because the boxed carrier
    /// is what every other V# member already answers about.
    private void emitGetType() {
        String source = """
            using System;
            using java.util;
            public struct GetTypeMembers {
                public static string Run() {
                    object o = 42;
                    string s = "abc";
                    bool b = true;
                    char c = 'x';
                    double d = 1.5;
                    float f = 2.5f;
                    long l = 9L;
                    decimal m = 1.0m;
                    ArrayList<string> list = new ArrayList<string>();
                    string names = o.GetType().GetName() + "|" + s.GetType().GetName() + "|"
                        + b.GetType().GetName() + "|" + c.GetType().GetName() + "|"
                        + d.GetType().GetName() + "|" + f.GetType().GetName() + "|"
                        + l.GetType().GetName() + "|" + m.GetType().GetName() + "|"
                        + list.GetType().GetName();
                    Type boxed = o.GetType();
                    bool same = boxed == 43.GetType();
                    bool distinct = boxed == l.GetType();
                    object missing = null;
                    string thrown = "no throw";
                    try {
                        missing.GetType();
                    } catch (NullReferenceException e) {
                        thrown = "NRE";
                    }
                    return names + "#" + boxed.GetSimpleName() + "|" + same + "|" + distinct
                        + "|" + thrown;
                }
            }
            """;
        assertOutput("GetTypeMembers", source, "Run",
                "java.lang.Integer|java.lang.String|java.lang.Boolean|java.lang.Character|"
                        + "java.lang.Double|java.lang.Float|java.lang.Long|java.math.BigDecimal|"
                        + "java.util.ArrayList#Integer|True|False|NRE");
    }

    /// `typeof` at the Java boundary is a typed JVM class literal, not the rule's private
    /// V# source-identity token. Generic arguments erase, nested binary spelling survives,
    /// and an array literal carries the JVM's exact array class descriptor. Its semantic
    /// `Class<T>` argument also specializes generic JDK members such as `Class.cast`; keeping
    /// the result raw would make the literal unusable at precisely the interop boundary it
    /// exists to open.
    private void emitJavaTypeofClassLiterals() {
        String source = """
            using System;
            using java.lang;
            using java.util;
            public struct JavaTypeof {
                public static string Run() {
                    Type raw = typeof(ArrayList);
                    Type generic = typeof(ArrayList<string>);
                    Type nested = typeof(Map.Entry<string, int>);
                    Type array = typeof(ArrayList[]);
                    int boxed = typeof(Integer).Cast(7);
                    return raw.GetName() + "|" + (raw == generic) + "|"
                        + nested.GetName() + "|" + array.GetName() + "|" + boxed;
                }
            }
            """;
        assertOutput("JavaTypeof", source, "Run",
                "java.util.ArrayList|True|java.util.Map$Entry|[Ljava.util.ArrayList;|7");
    }

    /// A nested Java type is written the way Java source writes it, and the enclosing
    /// separator is the same `.` a package uses, so resolution finds which is which by trying
    /// the splits right to left. Every shape a program reaches one through is here: the simple
    /// name under a `using`, the fully qualified name, construction of a nested class, and the
    /// nested interface a nested class implements. The written name and the `$` binary name a
    /// descriptor already carried must reach one symbol - `pair` is assigned to `other` across
    /// exactly that boundary.
    private void emitNestedJavaTypes() {
        String source = """
            using java.util;
            public struct NestedJavaTypes {
                public static string Run() {
                    AbstractMap.SimpleEntry<string, int> pair =
                        new AbstractMap.SimpleEntry<string, int>("a", 1);
                    Map.Entry<string, int> other = pair;
                    TreeMap<string, int> sorted = new TreeMap<string, int>();
                    sorted.Put("b", 2);
                    sorted.Put("a", 1);
                    string walked = "";
                    foreach (Map.Entry<string, int> e in sorted.EntrySet()) {
                        walked = walked + e.GetKey() + "=" + e.GetValue() + ";";
                    }
                    return other.GetKey() + "|" + other.GetValue() + "|"
                        + pair.GetType().GetName() + "|" + walked;
                }
            }
            """;
        assertOutput("NestedJavaTypes", source, "Run",
                "a|1|java.util.AbstractMap$SimpleEntry|a=1;b=2;");
    }

    /// A Java varargs method is C# `params`, so the existing expansion applies: loose
    /// arguments are packed into the trailing array, and an actual array still binds in normal
    /// form rather than being wrapped again. `java.util.List.of` is also a *static interface*
    /// method, which must be referenced through an `InterfaceMethodref`; this executes because
    /// a `Methodref` for it verified and then failed at the call.
    private void emitJavaVarargs() {
        String source = """
            using java.lang;
            using java.util;
            public struct JavaVarargs {
                public static string Run() {
                    List expanded = List.Of("a", "b", "c");
                    object[] items = new object[] { "p", "q" };
                    List normal = List.Of(items);
                    string formatted = String.Format("%s-%s", "x", "y");
                    return expanded.Size() + "|" + normal.Size() + "|" + formatted;
                }
            }
            """;
        assertOutput("JavaVarargs", source, "Run", "3|2|x-y");
    }

    /// JLS 9.9's non-wildcard parameterization reads the *declared bound* of the parameter an
    /// unbounded `?` stands for. `Comparator<T>` is unbounded, so its projection was
    /// always `object` and nothing revealed the gap; a functional interface with a bounded
    /// parameter - `EnumSet`'s element, `Comparable<T extends Comparable>` shapes, and Spring's
    /// `RouterFunction<T extends ServerResponse>` - projected to `object` instead, turning a
    /// parameter Java writes as `Foo<?>` into `Foo<object>`, which nothing converts to. This
    /// uses `Enum.CompareTo` through a raw-bounded functional receiver to keep the proof inside
    /// the JDK, and executes because the descriptor is what the verifier checks.
    private void emitBoundedWildcardProjection() {
        String source = """
            using java.time;
            using java.util;
            public struct BoundedProjection {
                public static string Run() {
                    List<DayOfWeek> days = new ArrayList<DayOfWeek>();
                    days.Add(DayOfWeek.FRIDAY);
                    days.Add(DayOfWeek.MONDAY);
                    Collections.Sort(days);
                    Collections.Reverse(days);
                    return days.Get(0).ToString() + "|" + days.Size();
                }
            }
            """;
        assertOutput("BoundedProjection", source, "Run", "FRIDAY|2");
    }

    /// `using X = Y;` substitutes a spelling before resolution. The directive already
    /// parsed - the syntax tree carried `alias` - and binding dropped it, so the name reported
    /// VS0246. Aliases are how C# answers a collision, and a collision is not hypothetical here:
    /// one real project met `java.net.http.HttpRequest` against `org.springframework.http.HttpRequest`
    /// in one file, and a probe proves the alias separates them. Only `java.base` types appear
    /// here, because a test JVM does not resolve every JDK module the CLI does. Both positions
    /// are covered, as they resolve on different paths: a declared type, a constructed one, a
    /// static call whose receiver is the alias, and `typeof`.
    private void emitUsingAliases() {
        String source = """
            using java.time;
            using java.util;
            using Clock = java.util.Date;
            using Instants = java.time.Instant;
            using Names = java.util.ArrayList;
            public struct Aliases {
                public static string Run() {
                    Clock legacy = new Clock(0);
                    Instants epoch = Instants.OfEpochMilli(0);
                    Names names = new Names();
                    names.Add("a");
                    return legacy.GetTime() + "|" + epoch.ToString() + "|" + names.Size()
                        + "|" + typeof(Clock).GetSimpleName();
                }
            }
            """;
        assertOutput("Aliases", source, "Run", "0|1970-01-01T00:00:00Z|1|Date");
    }

    /// `Func` and `Action` are the JDK's generic functional interfaces under C#'s names.
    ///
    /// Mapping them where a written type name is resolved is what makes a delegate-typed local,
    /// parameter and return work at once, and what lets a V# lambda reach one through the
    /// ordinary functional-interface conversion rather than a delegate model V# does not have.
    /// The generic interfaces are used, never the primitive-specialised ones: C# delegate
    /// semantics are generic, and selecting `IntUnaryOperator` by primitiveness would make one
    /// source spelling denote two types. Type arguments stay semantic - `Func&lt;int,int&gt;`
    /// types its lambda's parameter `int` - and erase at emission like every other generic.
    /// Invocation is the C# spelling `f(x)`, which lowers to the interface's single abstract
    /// method, so a Java SAM value held in a local is callable the same way.
    private void emitDelegateTypes() {
        String source = """
            using System;
            using java.util;
            public struct Delegates {
                public static string Run() {
                    Func<string, int> length = text => text.Length;
                    Func<int> answer = () => 42;
                    Func<int, int, int> add = (left, right) => left + right;
                    Action<string> collect = text => Sink.Add(text);
                    Action plain = () => Sink.Add("ran");
                    collect("hi");
                    plain();
                    Comparator<string> byLength = Comparator.ComparingInt(entry => entry.Length);
                    return length("fig") + "|" + answer() + "|" + add(2, 3)
                        + "|" + byLength("aa", "b") + "|" + Sink.Seen;
                }
            }
            public struct Sink {
                public static string Seen = "";
                public static void Add(string value) { Seen = Seen + value + ";"; }
            }
            """;
        assertOutput("Delegates", source, "Run", "3|42|5|1|hi;ran;");
    }

    /// `using static T;` brings `T`'s static members into scope for unqualified use.
    ///
    /// It is expressed as one more imported scope rather than a mechanism of its own, which is
    /// what makes it work for a JDK class and a V# holder alike. Two things had to be got right.
    /// A JDK member is declared `sqrt` while the source writes `Sqrt`, and an unqualified name
    /// has no receiver for the rule's member-access translation to run on - so the members are
    /// registered under their V# spelling while their qualified name, which is what the backend
    /// emits from, stays `java.lang.Math.sqrt`. And a V# `static class` is a `ContainerSymbol`,
    /// not a type: V# has no object model, so a holder of statics is not a `NamedTypeSymbol`,
    /// and a check for one silently found nothing.
    private void emitStaticImports() {
        String source = """
            using java.lang;
            using static java.lang.Math;
            using static Lib.Text;
            namespace Lib {
                public static class Text {
                    public static string Shout(string value) => value.ToUpperInvariant();
                }
            }
            public struct StaticImports {
                public static string Run() {
                    return Sqrt(16.0) + "|" + Max(3, 7) + "|" + Abs(-5) + "|" + Shout("hi");
                }
            }
            """;
        assertOutput("StaticImports", source, "Run", "4|7|5|HI");
    }

    /// A type argument a lambda supplies through the target's own parameterization.
    ///
    /// `Optional.Map` declares `R` and inferred already; `Optional.FlatMap` declares
    /// `Optional<? extends R>`, so `R` has to be read *through* that shape from what the body
    /// produced - and until it was, the call reported VS0411 with the body's own result refused
    /// as unconvertible to `Optional<? extends R>`. Three things had to hold together: a lambda
    /// whose declared return merely *contains* an open parameter must bind its body without a
    /// target, the produced type must be matched structurally against the declared one, and a
    /// lambda nested inside another must get a legal JVM method name - `Owner.Fn$lambda$0$lambda$1`
    /// rather than a name still carrying `&lt;lambda&gt;#0`. This executes because only execution
    /// proves the emitted names link.
    private void emitNestedTargetParameterInference() {
        String source = """
            using java.util;
            using java.util.stream;
            public struct NestedInference {
                public static string Run() {
                    Optional<string> present = Optional.Of("fig");
                    Optional<int> mapped = present.Map(text => text.Length);
                    Optional<int> flattened = present.FlatMap(text => Optional.Of(text.Length + 1));
                    ArrayList<string> names = new ArrayList<string>();
                    names.Add("pear");
                    names.Add("fig");
                    // A lambda nested inside a lambda, which is what broke the emitted name.
                    List<int> lengths = names.Stream()
                        .Map(name => Optional.Of(name).Map(inner => inner.Length).Get())
                        .Collect(Collectors.ToList());
                    return mapped.Get() + "|" + flattened.Get() + "|" + lengths.Get(0)
                        + "|" + lengths.Get(1);
                }
            }
            """;
        assertOutput("NestedInference", source, "Run", "3|4|4|3");
    }

    /// `typeof(string)` names the real `java.lang.String` class, which is what makes every
    /// JDK API taking a `Class` reachable - Spring's `BodyToMono(typeof(string))` is unwritable
    /// otherwise. Only `string` qualifies: `int`/`uint`, `sbyte`/`byte`, `long`/`ulong` and
    /// `object`/`dynamic` share JVM carriers, so a class literal would equate types C# keeps
    /// distinct, which is why `VsType` exists and why `CarrierDistinctions` still holds. Both
    /// spellings must reach one token, and the descriptor comes from a mapping that is
    /// deliberately *not* `CorelibCarriers` membership: that table also decides the rule's carrier
    /// member surface, and joining it would hand `string` every `java.lang.String` method.
    private void emitStringClassLiteral() {
        String source = """
            using java.lang;
            using System;
            public struct StringToken {
                public static string Run() {
                    Class token = typeof(string);
                    bool aliased = typeof(string) == typeof(String);
                    bool distinct = typeof(int) != typeof(uint) && typeof(sbyte) != typeof(byte);
                    return token.GetName() + "|" + aliased + "|" + distinct
                        + "|" + "abc".Split('b').Length;
                }
            }
            """;
        assertOutput("StringToken", source, "Run", "java.lang.String|True|True|2");
    }

    /// Java's unchecked conversion (JLS 5.1.9), admitted as an explicit cast only.
    ///
    /// A JDK signature that hands back a raw type made its result unusable: Spring's
    /// `ServerResponse.Ok().Body(publisher, Class)` returns a raw `Mono` where a handler must
    /// return `Mono<ServerResponse>`. The cast is exactly the assertion Java's warning stands
    /// for, and nothing at run time can check it because both spellings erase to one class -
    /// which is why the implicit direction stays refused and is asserted separately below.
    private void emitUncheckedGenericCast() {
        String source = """
            using java.util;
            public struct Unchecked {
                public static string Run() {
                    ArrayList raw = new ArrayList();
                    raw.Add("x");
                    ArrayList<string> typed = (ArrayList<string>)raw;
                    List<string> asInterface = (List<string>)(List)typed;
                    return typed.Size() + "|" + typed.Get(0) + "|" + asInterface.Get(0).Length;
                }
            }
            """;
        assertOutput("Unchecked", source, "Run", "1|x|1");
    }

    /// A `record struct` is a value, so C# synthesizes `==`, `Equals`, `GetHashCode` and
    /// `ToString` from its components. Before this the emitted class carried public
    /// fields and nothing else: `==` fell to `if_acmpeq` and answered `false` for two equal
    /// values - a wrong answer, not a diagnostic, which is why this executes and asserts the
    /// rendered text rather than checking that it compiles. The members are emitted under their
    /// JVM names, so `HashMap` and `println` reach the same three from ordinary Java, and
    /// components render through the language's own formatter: the `bool` reads `True`, not
    /// Java's `true`.
    private void emitRecordStructValueMembers() {
        String source = """
            public record struct Point(int X, int Y);
            public record struct Flagged(string Name, bool On);
            public struct RecordValues {
                public static string Run() {
                    Point a = new Point(1, 2);
                    Point b = new Point(1, 2);
                    Point c = new Point(1, 9);
                    Flagged tag = new Flagged("x", true);
                    return (a == b) + "|" + (a != b) + "|" + (a == c)
                        + "|" + a.Equals(b) + "|" + (a.GetHashCode() == b.GetHashCode())
                        + "|" + a.ToString() + "|" + tag.ToString();
                }
            }
            """;
        assertOutput("RecordValues", source, "Run",
                "True|False|False|True|True|Point { X = 1, Y = 2 }|Flagged { Name = x, On = True }");
    }

    /// A *raw* Java generic erases each variable to its bound, not to `object`.
    /// `EnumMap<K extends Enum<K>, V>` declares `put(Ljava/lang/Enum;Ljava/lang/Object;)`, so a
    /// raw receiver substituting `object` for `K` named a method that does not exist and failed
    /// at the first call. This executes rather than only compiling for exactly that reason. The
    /// same rule is what lets a lambda implement a bounded functional interface: the
    /// implementation method must carry the bound, or `LambdaMetafactory` rejects it as a type
    /// mismatch on the dynamic parameter.
    private void emitRawGenericBoundErasure() {
        String source = """
            using java.time;
            using java.util;
            public struct RawBounds {
                public static string Run() {
                    EnumMap map = new EnumMap(typeof(DayOfWeek));
                    map.Put(DayOfWeek.FRIDAY, "friday");
                    return map.Get(DayOfWeek.FRIDAY) + "|" + map.Size();
                }
            }
            """;
        assertOutput("RawBounds", source, "Run", "friday|1");
    }

    /// A `params` *constructor* reaches the JVM through its descriptor alone, with no ordinal
    /// list to carry the expansion. `bindObjectCreation` converted its arguments but
    /// never ran the packing and default completion `callArguments` gives every ordinary call,
    /// so `new ProcessBuilder()` emitted `NEW`/`DUP`/`INVOKESPECIAL ([Ljava/lang/String;)V`
    /// over an empty stack: a structurally invalid method, reported as an internal compiler
    /// error rather than compiled. Both the empty and the expanded form are checked here,
    /// because only the expanded one proves the array is filled rather than merely allocated.
    private void emitVarargsConstructor() {
        String source = """
            using java.lang;
            public struct VarargsConstructor {
                public static string Run() {
                    ProcessBuilder empty = new ProcessBuilder();
                    ProcessBuilder expanded = new ProcessBuilder("ls", "-l");
                    string[] listed = new string[] { "a", "b", "c" };
                    ProcessBuilder array = new ProcessBuilder(listed);
                    return empty.Command().Size() + "|" + expanded.Command().Size()
                        + "|" + expanded.Command().Get(1) + "|" + array.Command().Size();
                }
            }
            """;
        assertOutput("VarargsConstructor", source, "Run", "0|2|-l|3");
    }

    /// A *generic* varargs method infers its element from the loose arguments, which
    /// the rule's signature mapping could not do: inference ran before expansion, matched the
    /// declared `T[]` against each scalar, failed, and took the raw fallback, so
    /// `Arrays.AsList(1, 2)` reported VS0029 against `List<int>`. The inferred element must
    /// survive per-element boxing into the erased `Object[]` and unbox back through `Get`,
    /// while an actual array still binds in normal form instead of being wrapped again.
    private void emitJavaGenericVarargs() {
        String source = """
            using java.util;
            public struct JavaGenericVarargs {
                public static string Run() {
                    List<int> numbers = Arrays.AsList(1, 2);
                    int sum = numbers.Get(0) + numbers.Get(1);
                    List<string> words = Arrays.AsList("a", "b");
                    string[] actual = new string[] { "p", "q", "r" };
                    List<string> normal = Arrays.AsList(actual);
                    return sum + "|" + numbers.Size() + "|" + words.Get(1) + "|"
                        + normal.Size() + "|" + normal.Get(2);
                }
            }
            """;
        assertOutput("JavaGenericVarargs", source, "Run", "3|2|b|3|r");
    }

    /// `String.Join` is the call that measured this gap: the design fixed its arity and it still
    /// failed, because its first parameter is `CharSequence` and `string` had no conversion to
    /// it. Widening emits nothing, since the value is already a `java.lang.String`; the
    /// downcast back is `checkcast`-guarded and throws for a value that is not one.
    private void emitKeywordHierarchy() {
        String source = """
            using java.lang;
            public struct KeywordHierarchy {
                public static string Run() {
                    string joined = String.Join("-", "a", "b");
                    CharSequence sequence = "text";
                    string back = (string)sequence;
                    Object other = new StringBuilder("q");
                    CharSequence widened = (CharSequence)other;
                    string thrown = "no throw";
                    try {
                        string wrong = (string)widened;
                    } catch (ClassCastException e) {
                        thrown = "CCE";
                    }
                    return joined + "|" + sequence.Length() + "|" + back + "|"
                        + widened.Length() + "|" + thrown;
                }
            }
            """;
        assertOutput("KeywordHierarchy", source, "Run", "a-b|4|text|1|CCE");
    }

    /// Members an interface gains from `java.lang.Object` and from its superinterfaces, and a
    /// call chained onto a type the program never names. The chain is the second half:
    /// declaration scopes are built where a type is *named*, so `a.Stream()` returned a type
    /// with no scope and `.Count()` could not be found on it. Both `compareTo` overloads are
    /// exercised to show the inherited erased one does not shadow the specific one.
    private void emitInterfaceInheritedMembers() {
        String source = """
            using java.lang;
            using java.math;
            using java.util;
            public struct InterfaceMembers {
                public static string Run() {
                    ArrayList values = new ArrayList();
                    values.Add("m");
                    values.Add("z");
                    Set keys = new TreeMap().KeySet();
                    BigDecimal two = BigDecimal.ValueOf(2L);
                    BigDecimal three = BigDecimal.ValueOf(3L);
                    Comparable erased = two;
                    return keys.ToString() + "|" + values.Stream().Count()
                        + "|" + two.CompareTo(three) + "|" + erased.CompareTo(three);
                }
            }
            """;
        assertOutput("InterfaceMembers", source, "Run", "[]|2|-1|-1");
    }

    /// The four program shapes that justified signature mapping, executed across every
    /// boundary the erased descriptor hides: constructed object creation, class-parameter
    /// `Add`/`Get`, `ArrayList<string>` to `List<string>`, generic static varargs inference,
    /// inherited `Stream<E>`, and `Optional<T>` flowing through a chained return.
    private void emitJavaGenericSignatures() {
        String source = """
            using java.util;
            public struct JavaGenerics {
                public static string Run() {
                    ArrayList<string> mutable = new ArrayList<string>();
                    mutable.Add("alpha");
                    mutable.Add("beta");
                    List<string> widened = mutable;
                    List<string> made = List.Of("gamma", "delta");
                    Optional<string> optional = Optional.Of(widened.Get(1));
                    string streamed = made.Stream().FindFirst().OrElse("missing");
                    ArrayList<int> numbers = new ArrayList<int>();
                    numbers.Add(7);
                    Optional<int> number = Optional.Of(numbers.Get(0));
                    return mutable.Get(0) + "|" + widened.Get(1) + "|"
                        + optional.OrElse("missing") + "|" + streamed + "|"
                        + number.OrElse(0);
                }
            }
            """;
        assertOutput("JavaGenerics", source, "Run", "alpha|beta|beta|gamma|7");
    }

    /// A V# method type parameter is a usable Java type argument, not an unresolved Java
    /// inference variable. The distinction matters in three places: an argument can infer a
    /// Java static method's `T`, a written Java method argument can name the V# `T`, and a
    /// constructed receiver can return that `T` into a `var` local. All three erase to Object,
    /// with the existing generic call boundary responsible for boxing and unboxing values.
    private void emitJavaGenericsClosedByVsharpTypeParameter() {
        String source = """
            using java.lang;
            using java.util;

            public static class GenericBridge
            {
                static List<T> One<T>(T value)
                {
                    return List.Of(value);
                }

                static Optional<T> Empty<T>()
                {
                    return Optional.Empty<T>();
                }

                static Optional<int> EmptyBoxed()
                {
                    return Optional.Empty<Integer>();
                }

                static T Head<T>(List<T> values)
                {
                    var first = values.Get(0);
                    return first;
                }

                public static string Run()
                {
                    List<string> words = One("bridge");
                    List<int> numbers = One(41);
                    Optional<string> empty = Empty<string>();
                    return Head(words) + "|" + (Head(numbers) + 1) + "|" + empty.IsEmpty()
                        + "|" + EmptyBoxed().IsEmpty();
                }
            }
            """;
        assertOutput("GenericBridge", source, "Run", "bridge|42|True|True");
    }

    /// The downcast is a real `checkcast`, not a static assumption: casting to a type the value
    /// is not throws exactly as the JVM specifies.
    private void emitFailingJavaDowncast() {
        String source = """
            using java.lang;
            using java.util;
            public struct JavaBadCast {
                public static string Run() {
                    Object o = new StringBuilder();
                    try {
                        List l = (List)o;
                        return "no throw " + l.Size();
                    } catch (ClassCastException e) {
                        return "threw";
                    }
                }
            }
            """;
        assertOutput("JavaBadCast", source, "Run", "threw");
    }

    /// `using java.util;` reaching a type, its instance members and a static owner by simple
    /// name. The emitted references must still be the fully qualified JVM classes, so
    /// this executes rather than only binding.
    private void emitImportedJavaPackage() {
        String source = """
            using java.util;
            public struct ImportedJava {
                public static string Run() {
                    ArrayList values = new ArrayList();
                    values.Add("head");
                    values.Add("tail");
                    return Arrays.ToString(new int[] { 1, 2 }) + "|" + values.Get(0)
                        + "|" + values.Size();
                }
            }
            """;
        assertOutput("ImportedJava", source, "Run", "[1, 2]|head|2");
    }

    /// A static JDK method reached through its fully qualified type, in PascalCase.
    /// `java` is not a declared name, so the whole receiver is a type expression no scope can
    /// answer, and the emitted call is an ordinary `invokestatic` on the owning Java class.
    private void emitJavaStaticMethodCall() {
        String source = """
            using java.lang;
            public struct JavaStaticCall {
                public static string Run() {
                    int abs = Math.Abs(-5);
                    int parsed = Integer.ParseInt("42");
                    long max = Math.Max(3L, 9L);
                    string valued = String.ValueOf(7);
                    return abs + "|" + parsed + "|" + max + "|" + valued;
                }
            }
            """;
        assertOutput("JavaStaticCall", source, "Run", "5|42|9|7");
    }

    /// Overload resolution over a translated name: `Abs` selects among four JDK `abs`
    /// descriptors by argument type, exactly as it does for a V# declaration.
    private void emitJavaStaticOverloadSelection() {
        String source = """
            using java.lang;
            public struct JavaStaticOverloads {
                public static string Run() {
                    return Math.Abs(-5) + "|" + Math.Abs(-5L)
                        + "|" + Math.Abs(-5.5);
                }
            }
            """;
        assertOutput("JavaStaticOverloads", source, "Run", "5|5|5.5");
    }

    private void emitJavaInstanceFieldWriteAndRead() {
        String source = """
            using java.awt;
            public struct JavaInstanceField {
                public static int Run() {
                    Point point = new Point(0, 35);
                    point.X = 7;
                    return point.X + point.Y;
                }
            }
            """;
        assertOutput("JavaInstanceField", source, "Run", 42);
    }

    /// Neither `setTimeInMillis`, `getTimeInMillis` nor `YEAR` is declared by
    /// `GregorianCalendar`; all three are inherited from `java.util.Calendar`. Emission owns
    /// every reference by the type the source named, and the JVM resolves each one up the
    /// superclass chain - the whole point of naming inherited members after the inheritor.
    private void emitInheritedJavaMembers() {
        String source = """
            using java.util;
            public struct JavaInherited {
                public static long Run() {
                    GregorianCalendar calendar = new GregorianCalendar();
                    calendar.SetTimeInMillis(86400000);
                    return calendar.GetTimeInMillis() + GregorianCalendar.YEAR;
                }
            }
            """;
        assertOutput("JavaInherited", source, "Run", 86400001L);
    }

    /// Java's `ArrayList<E>` methods carry `E` only in their generic Signature attribute;
    /// the JVM descriptors expose `Object`. V# deliberately maps those erased descriptors,
    /// but must still preserve real overload distinctions such as `remove(int): Object`
    /// versus `remove(Object): boolean` and select the matching invocation descriptor.
    private void emitErasedJavaGenericOverloads() {
        String source = """
            using java.util;
            public struct JavaGenerics {
                public static string Run() {
                    ArrayList values = new ArrayList();
                    bool added = values.Add("tail");
                    values.Add(0, "head");
                    string first = (string)values.Get(0);
                    string removed = (string)values.Remove(0);
                    bool removedTail = values.Remove("tail");
                    return added + "|" + first + "|" + removed + "|"
                        + removedTail + "|" + values.Size();
                }
            }
            """;
        assertOutput("JavaGenerics", source, "Run", "True|head|head|True|0");
    }

    /// A call that writes its type arguments still emits against the *erased* declaration
    ///: the JVM has one `Echo(Object)Object`, so a specialised signature at the call
    /// site would name a method that does not exist - which is exactly what an earlier
    /// substitute-before-resolution attempt produced, a clean compile and a
    /// `NoSuchMethodError` at launch. The value is boxed in and cast out, as erasure requires.
    private void emitWrittenTypeArgumentCall() {
        String source = """
            public struct WrittenArgs {
                private static T Echo<T>(T value) { return value; }
                public static string Run() {
                    int number = Echo<int>(3);
                    string text = Echo<string>("x");
                    return text + number;
                }
            }
            """;
        assertOutput("WrittenArgs", source, "Run", "x3");
    }

    /// A `foreach` whose variable takes the element *apart*. Both C# spellings are
    /// covered: `var (a, b)` and the fully typed `(string a, int b)`, which the parser
    /// normalises into the same positional pattern. Before this existed the pattern reached
    /// lowering as a value *test* and the backend refused it with an internal error, so no
    /// program could iterate a tuple sequence by component. The loop keeps exactly one
    /// iteration variable - a synthetic carrier - and the deconstruction is ordinary body
    /// code, which is why `break` and the loop protocol stay untouched.
    private void emitDeconstructingForeach() {
        String source = """
            public struct DeconForeach {
                public static string Run() {
                    (string, int)[] pairs = new (string, int)[2];
                    pairs[0] = ("ana", 31);
                    pairs[1] = ("bob", 24);
                    string names = "";
                    int total = 0;
                    foreach (var (name, age) in pairs) {
                        names += name;
                        total += age;
                    }
                    foreach ((string other, int years) in pairs) {
                        names += "|" + other;
                        total += years;
                    }
                    return names + "#" + total;
                }
            }
            """;
        assertOutput("DeconForeach", source, "Run", "anabob|ana|bob#110");
    }

    /// The second source of positional components is a `record struct`: its declaration
    /// fixes the order, so the element is read through its component *fields* rather than
    /// through tuple items. The same walk must therefore choose `getfield` here and `item1`
    /// above, which is exactly the divergence that a single test over tuples would hide.
    private void emitDeconstructingForeachOverRecordStruct() {
        String source = """
            public record struct Reading(string Sensor, int Value);
            public struct DeconRecordForeach {
                public static string Run() {
                    Reading[] readings = new Reading[2];
                    readings[0] = new Reading("t", 4);
                    readings[1] = new Reading("h", 6);
                    string report = "";
                    foreach (var (sensor, value) in readings) {
                        report += sensor + value;
                    }
                    return report;
                }
            }
            """;
        assertOutput("DeconRecordForeach", source, "Run", "t4h6");
    }

    /// Nesting and discards in the same designation. A nested tuple re-enters the
    /// component walk one level down, and a `_` component is still read and converted - it
    /// simply lands in a slot nobody names, so the enclosing element is consumed exactly
    /// once per step whether or not every component is wanted.
    private void emitDeconstructingForeachNested() {
        String source = """
            public struct DeconNested {
                public static string Run() {
                    (string, (int, int))[] rows = new (string, (int, int))[2];
                    rows[0] = ("a", (1, 2));
                    rows[1] = ("b", (3, 4));
                    string shown = "";
                    foreach (var (label, (low, high)) in rows) {
                        shown += label + low + high;
                    }
                    foreach (var (_, (_, top)) in rows) {
                        shown += "/" + top;
                    }
                    return shown;
                }
            }
            """;
        assertOutput("DeconNested", source, "Run", "a12b34/2/4");
    }

    /// `foreach` over a Java collection. Before this existed the backend assumed every
    /// enumerable was an array and emitted `arraylength` on a `java.util.List`, which the
    /// verifier rejects - the first ordinary JDK program written against V# failed to launch.
    /// Three carriers are covered together because each takes a different path: a class-typed
    /// receiver, an interface-typed one, and a `string` (indexed, never iterated, exactly as
    /// C# defines it). The erased element arrives as `Object` and is narrowed per step, so a
    /// `HashMap.KeySet()` walk and an explicitly typed `int` variable over a boxed `Integer`
    /// source both prove the unboxing arm. Arrays keep their own loop, unchanged.
    private void emitForeachOverJavaIterables() {
        String source = """
            using java.util;
            public struct JavaForeach {
                public static string Run() {
                    ArrayList words = new ArrayList();
                    words.Add("a");
                    words.Add("b");
                    string joined = "";
                    foreach (string word in words) { joined += word; }
                    List asInterface = words;
                    foreach (var word in asInterface) { joined += "|" + word; }
                    foreach (char c in "hi") { joined += "/" + c; }
                    ArrayList numbers = new ArrayList();
                    numbers.Add(7);
                    int total = 0;
                    foreach (int n in numbers) { total += n; }
                    int[] direct = new int[] { 1, 2 };
                    foreach (int n in direct) { total += n; }
                    return joined + "#" + total;
                }
            }
            """;
        assertOutput("JavaForeach", source, "Run", "ab|a|b/h/i#10");
    }

    /// A statement-level call whose erased generic result is discarded. `Map.Put`
    /// returns the *previous* mapping, which is genuinely `null` on a first insert, so
    /// narrowing a result nobody reads threw `NullPointerException` in the same first
    /// ordinary program. The value is popped as the erased reference the JVM produced;
    /// a `Get` that is read still unboxes, and a discarded `long` still pops two slots.
    private void emitDiscardedErasedResult() {
        String source = """
            using java.util;
            public struct DiscardedResult {
                private static long Wide() { return 9L; }
                public static string Run() {
                    HashMap counts = new HashMap();
                    counts.Put("k", 1);
                    counts.Put("k", 2);
                    int read = (int)counts.Get("k");
                    Wide();
                    return read + "|" + counts.Size();
                }
            }
            """;
        assertOutput("DiscardedResult", source, "Run", "2|1");
    }

    /// Compiles `source` (declaring a struct named `className`) via the full front end and
    /// `Compilation.emit()`, asserts it succeeds, and loads the emitted `.class` bytes into
    /// a fresh `ClassLoader`. Shared by the `finally` tests below, which need more than a
    /// single-return-value comparison (`assertOutput`) - some need static-field state
    /// inspected after the call, others need to observe a propagated exception.
    private Class<?> compileAndLoad(String className, String source) {
        SourceFile file = TestSources.styled(className + ".vs", source);
        Compilation compilation = Compilation.of(java.util.List.of(file));
        CompilationResult result = compilation.emit();
        if (result.hasErrors()) {
            for (var d : result.diagnostics()) {
                System.out.println(d.message());
            }
        }
        Assert.isFalse(result.hasErrors(), "compilation should succeed");

        java.util.Map<String, byte[]> classes = new java.util.LinkedHashMap<>();
        for (UnitAnalysis unit : result.units()) {
            if (unit instanceof UnitAnalysis.Emitted emitted && emitted.file().name().equals(className + ".vs")) {
                classes.putAll(emitted.classes());
            }
        }
        Assert.isTrue(!classes.isEmpty(), "bytecode should be generated");

        java.util.Map<String, byte[]> finalClasses = classes;
        ClassLoader loader = new ClassLoader() {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (finalClasses.containsKey(name)) {
                    byte[] classBytes = finalClasses.get(name); return defineClass(name, classBytes, 0, classBytes.length);
                }
                return super.findClass(name);
            }
        };
        try {
            return loader.loadClass(className);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    private void emitFinallyRunsOnNormalCompletion() {
        // Order-sensitive by construction: (start+1)*10 only matches if the try body's
        // increment truly ran BEFORE the finally body's multiply. Either step missing, or
        // running in the wrong order, produces a different number (e.g. 11 if finally ran
        // first, 2 if finally never ran, 10 if the try body never ran).
        String source = """
            public struct FinallyOrder {
                public static int RunNormal(int start) {
                    int x = start;
                    try {
                        x = x + 1;
                    } finally {
                        x = x * 10;
                    }
                    return x;
                }
            }
            """;
        assertOutput("FinallyOrder", source, "RunNormal", 20, 1);
    }

    private void emitFinallyRunsBeforeReturnFromTry() {
        // The finally clause cannot affect an already-computed return value (C# fixes the
        // return expression's value before running finally), so proving finally actually
        // ran needs an external side channel: a static field, checked after the call.
        String source = """
            public struct FinallyReturn {
                public static int Ran;

                public static int RunReturn(int x) {
                    try {
                        return x * 1000;
                    } finally {
                        Ran = 1;
                    }
                }
            }
            """;
        try {
            Class<?> clazz = compileAndLoad("FinallyReturn", source);
            Method method = clazz.getMethod("RunReturn", int.class);
            Object result = method.invoke(null, 7);
            Assert.equal(7000, result, "the try's own return value is unaffected by finally");
            Assert.equal(1, clazz.getField("Ran").get(null), "finally ran before the method actually returned");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void emitFinallyRunsBeforeReturnFromCatch() {
        String source = """
            using System;
            public struct FinallyCatch {
                public static int Ran;

                public static int RunCatchAndFinally(int divisor) {
                    try {
                        return 100 / divisor;
                    } catch (Exception e) {
                        return -1;
                    } finally {
                        Ran = Ran + 1;
                    }
                }
            }
            """;
        try {
            Class<?> clazz = compileAndLoad("FinallyCatch", source);
            Method method = clazz.getMethod("RunCatchAndFinally", int.class);

            Object caughtResult = method.invoke(null, 0);
            Assert.equal(-1, caughtResult, "the catch's own return value survives finally");
            Assert.equal(1, clazz.getField("Ran").get(null), "finally ran once after the catch body returned");

            Object normalResult = method.invoke(null, 5);
            Assert.equal(20, normalResult, "normal completion still divides correctly");
            Assert.equal(2, clazz.getField("Ran").get(null), "finally ran again after the try body returned");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void emitFinallyRunsBeforeUncaughtExceptionPropagates() {
        String source = """
            public struct FinallyThrows {
                public static int Ran;

                public static int RunThrows(int divisor) {
                    try {
                        return 100 / divisor;
                    } finally {
                        Ran = 1;
                    }
                }
            }
            """;
        try {
            Class<?> clazz = compileAndLoad("FinallyThrows", source);
            Method method = clazz.getMethod("RunThrows", int.class);
            boolean threw = false;
            try {
                method.invoke(null, 0);
            } catch (java.lang.reflect.InvocationTargetException e) {
                threw = true;
                Assert.isTrue(e.getCause() instanceof ArithmeticException,
                        "the real ArithmeticException propagates, not something else");
            }
            Assert.isTrue(threw, "the exception should have propagated past the method");
            Assert.equal(1, clazz.getField("Ran").get(null), "finally ran before the exception left the method");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void emitBreakEscapingFinally() {
        String source = """
            public struct FinallyBreak {
                public static int Ran;

                public static int RunBreak() {
                    int i = 0;
                    while (i < 5) {
                        try {
                            if (i == 2) {
                                break;
                            }
                        } finally {
                            Ran = Ran + 1;
                        }
                        i = i + 1;
                    }
                    return i;
                }
            }
            """;
        try {
            Class<?> clazz = compileAndLoad("FinallyBreak", source);
            Method method = clazz.getMethod("RunBreak");
            Object result = method.invoke(null);
            Assert.equal(2, result, "loop stops at 2");
            Assert.equal(3, clazz.getField("Ran").get(null), "finally runs 3 times (0, 1, and escaping at 2)");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void emitGotoEscapingFinally() {
        String source = """
            public struct FinallyGoto {
                public static int Ran;

                public static int RunGoto() {
                    try {
                        goto done;
                    } finally {
                        Ran = Ran + 1;
                    }
                done:
                    return 42;
                }
            }
            """;
        try {
            Class<?> clazz = compileAndLoad("FinallyGoto", source);
            Method method = clazz.getMethod("RunGoto");
            Object result = method.invoke(null);
            Assert.equal(42, result, "goto targets done correctly");
            Assert.equal(1, clazz.getField("Ran").get(null), "finally ran when goto escaped");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void emitBreakStaysInsideFinally() {
        String source = """
            public struct FinallyInnerBreak {
                public static int Ran;

                public static int RunInnerBreak() {
                    try {
                        while (true) {
                            break;
                        }
                        return 10;
                    } finally {
                        Ran = Ran + 1;
                    }
                }
            }
            """;
        try {
            Class<?> clazz = compileAndLoad("FinallyInnerBreak", source);
            Method method = clazz.getMethod("RunInnerBreak");
            Object result = method.invoke(null);
            Assert.equal(10, result, "break stays inside try and method returns 10");
            Assert.equal(1, clazz.getField("Ran").get(null), "finally runs only once on normal return, break didn't trigger it");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


    private static final String LOCK_SOURCE = """
        public struct LockTest {
            public static int RunLocked(object obj, int x) {
                int result = 0;
                lock (obj) {
                    if (x > 0) {
                        return x * 2;
                    }
                    result = x + 1;
                }
                return result;
            }
        }
        """;

    private void emitLockRunsNormally() {
        // A single-thread test cannot observe a genuinely leaked/double-released monitor
        // (the same thread may always re-enter its own monitor regardless), so this is a
        // functional smoke test that monitorenter/the body/monitorexit compose into valid,
        // correctly-executing bytecode; emitLockMonitorInstructionsArePaired below is the
        // structural check ("inspect bytecode structurally", CLAUDE.md) that the actual
        // monitorenter/monitorexit pairing is what the design/the rule's shared emitProtectedRegion
        // machinery is supposed to produce. This path (x <= 0) falls through the lock body
        // normally, reaching the normal-completion cleanup copy rather than the return one.
        assertOutput("LockTest", LOCK_SOURCE, "RunLocked", -2, "any-object", -3);
    }

    private void emitLockReturnsFromInside() {
        // x > 0 returns from lexically inside the lock body, exercising emitReturn's
        // active-cleanup duplication (shared with try/finally) rather than the
        // normal-completion path exercised above.
        assertOutput("LockTest", LOCK_SOURCE, "RunLocked", 10, "any-object", 5);
    }

    private void emitLockMonitorInstructionsArePaired() {
        SourceFile file = TestSources.styled("LockTest.vs", LOCK_SOURCE);
        Compilation compilation = Compilation.of(java.util.List.of(file));
        CompilationResult result = compilation.emit();
        Assert.isFalse(result.hasErrors(), "compilation should succeed");

        java.util.Map<String, byte[]> classes = new java.util.LinkedHashMap<>();
        for (UnitAnalysis unit : result.units()) {
            if (unit instanceof UnitAnalysis.Emitted emitted && emitted.file().name().equals("LockTest.vs")) {
                classes.putAll(emitted.classes());
            }
        }
        Assert.isTrue(!classes.isEmpty(), "bytecode should be generated");

        java.lang.classfile.ClassModel model = java.lang.classfile.ClassFile.of().parse(classes.values().iterator().next());
        int enters = 0;
        int exits = 0;
        for (var method : model.methods()) {
            if (!method.methodName().stringValue().equals("RunLocked")) continue;
            var code = method.code().orElseThrow();
            for (var element : code) {
                if (element instanceof java.lang.classfile.instruction.MonitorInstruction monitor) {
                    if (monitor.opcode() == java.lang.classfile.Opcode.MONITORENTER) enters++;
                    if (monitor.opcode() == java.lang.classfile.Opcode.MONITOREXIT) exits++;
                }
            }
        }
        Assert.equal(1, enters, "exactly one monitorenter, when the lock is acquired");
        // LOCK_SOURCE's body has a genuinely reachable normal-completion fallthrough (the
        // `x <= 0` path) as well as a genuinely reachable `return` inside the lock (the
        // `x > 0` path) and a real exceptionCatchAll entry - so, unlike a lock body that
        // always returns unconditionally (where java.lang.classfile's own dead-code
        // patching would neutralise the unreachable normal-completion copy down to a NOP
        // stub, discovered empirically while writing this test), all three copies of
        // monitorexit that emitProtectedRegion emits survive as real instructions here.
        Assert.equal(3, exits, "monitorexit duplicated at every exit path: return, normal fallthrough, and rethrow");
    }

    private static final String USING_SOURCE = """
        using java.lang;
        public struct UsingTest {
            public static int RunUsing(AutoCloseable resource, int x) {
                int result = 0;
                using (resource) {
                    if (x > 0) {
                        return x * 2;
                    }
                    result = x + 1;
                }
                return result;
            }
        }
        """;

    private void emitUsingReleasesResource() {
        assertOutput("UsingTest", USING_SOURCE, "RunUsing", -2, null, -3);
        assertOutput("UsingTest", USING_SOURCE, "RunUsing", 10, null, 5);

        SourceFile file = TestSources.styled("UsingTest.vs", USING_SOURCE);
        Compilation compilation = Compilation.of(java.util.List.of(file));
        CompilationResult result = compilation.emit();
        Assert.isFalse(result.hasErrors(), "compilation should succeed");

        java.util.Map<String, byte[]> classes = new java.util.LinkedHashMap<>();
        for (UnitAnalysis unit : result.units()) {
            if (unit instanceof UnitAnalysis.Emitted emitted && emitted.file().name().equals("UsingTest.vs")) {
                classes.putAll(emitted.classes());
            }
        }
        Assert.isTrue(!classes.isEmpty(), "bytecode should be generated");

        java.lang.classfile.ClassModel model = java.lang.classfile.ClassFile.of().parse(classes.values().iterator().next());
        int closeCalls = 0;
        for (var method : model.methods()) {
            if (!method.methodName().stringValue().equals("RunUsing")) continue;
            var code = method.code().orElseThrow();
            for (var element : code) {
                if (element instanceof java.lang.classfile.instruction.InvokeInstruction invoke) {
                    if (invoke.name().stringValue().equals("close") && invoke.opcode() == java.lang.classfile.Opcode.INVOKEINTERFACE) {
                        closeCalls++;
                    }
                }
            }
        }
        Assert.equal(3, closeCalls, "close() duplicated at every exit path: return, normal fallthrough, and rethrow");
    }

    private void emitNameOfParameter() {
        String source = """
            public struct Names {
                public static string OfParameter(int value) {
                    return nameof(value);
                }
            }
            """;
        assertOutput("Names", source, "OfParameter", "value", 0);
    }

    private void emitNameOfLocal() {
        String source = """
            public struct Names {
                public static string OfLocal() {
                    int count = 0;
                    return nameof(count);
                }
            }
            """;
        assertOutput("Names", source, "OfLocal", "count");
    }

    private void emitQualifiedNameOf() {
        String source = """
            using System;
            public struct QualifiedNameOf {
                public static string Type() => nameof(Int32);
                public static string Member() => nameof(Console.WriteLine);
            }
            """;
        assertOutput("QualifiedNameOf", source, "Type", "Int32");
        assertOutput("QualifiedNameOf", source, "Member", "WriteLine");
    }

    private void emitTryCatchCatchesRealException() {
        // V# has no `new` for reference types, so there is no way to construct a thrown
        // value from source. Integer division by zero is
        // a genuine JVM-level throw that needs none: `idiv` itself raises a real
        // java.lang.ArithmeticException, which this test relies on to prove the emitted
        // exception-table entry actually catches something, not merely that it verifies.
        String source = """
            using System;
            public struct Calc {
                public static int SafeDivide(int a, int b) {
                    try {
                        return a / b;
                    } catch (Exception e) {
                        return -1;
                    }
                }
            }
            """;
        assertOutput("Calc", source, "SafeDivide", 5, 10, 2);
        assertOutput("Calc", source, "SafeDivide", -1, 10, 0);
    }

    private void emitTryCatchNormalCompletion() {
        // Checks convergence after try/catch: a local assigned in both the try body and
        // every catch clause is used afterward on both paths, exercising the `goto endLabel`
        // from both the try body's normal completion and the catch handler.
        String source = """
            using System;
            public struct Calc {
                public static int Describe(int a, int b) {
                    int result;
                    try {
                        result = a / b;
                    } catch (Exception e) {
                        result = -1;
                    }
                    return result * 10;
                }
            }
            """;
        assertOutput("Calc", source, "Describe", 50, 20, 4);
        assertOutput("Calc", source, "Describe", -10, 20, 0);
    }

    /// A tuple element is converted to the element type the tuple declares.
    ///
    /// Returning `(ArrayList<string>, int)` where the tuple says `(List<string>, int)` is an
    /// ordinary widening reference conversion - a no-op on the JVM, because the value already
    /// *is* an instance of the target - and the emitter refused it as unsupported, which
    /// reached the user as an internal compiler error. Boxing and a narrowing cast take the
    /// same path and are checked here too.
    private void emitConvertedTupleElements() {
        String source = """
            using java.util;
            public struct Tuples {
                public static int Widened() {
                    ArrayList<string> made = new ArrayList<string>();
                    made.Add("x");
                    (List<string>, int) pair = (made, 1);
                    var (list, n) = pair;
                    return list.Size() + n;
                }
                public static string Boxed() {
                    (object, object) pair = (1, "two");
                    var (left, right) = pair;
                    return left + "/" + right;
                }
                public static int Narrowed() {
                    List<string> made = new ArrayList<string>();
                    (object, int) pair = (made, 2);
                    var (value, n) = pair;
                    ArrayList<string> back = (ArrayList<string>)value;
                    return back.Size() + n;
                }
            }
            """;
        assertOutput("Tuples", source, "Widened", 2);
        assertOutput("Tuples", source, "Boxed", "1/two");
        assertOutput("Tuples", source, "Narrowed", 2);
    }

    /// A `const` initializer may be any constant *expression*, not only a literal (C# §15.4,
    /// the design), and a constant is implicitly static.
    ///
    /// `const int MaxAgeMonths = 40 * 12;` used to fold to nothing, which made the field not a
    /// constant at all: it reached the backend as an ordinary *instance* field that nothing
    /// ever assigned, and a read of it emitted `getfield` against whatever the expression stack
    /// happened to hold. Inside a switch expression that was a `Pet`, and the JVM rejected the
    /// class at load with a `VerifyError` - a wrong answer at best, unloadable bytecode at
    /// worst. The values below were checked against .NET 10.
    private void emitFoldedConstantExpressions() {
        String source = """
            public struct Consts {
                const int Months = 40 * 12;
                const int Big = 5_000_000;
                const int Mask = (1 << 8) - 1;
                const long Wide = 1000L * 1000L;
                const int Nested = (2 + 3) * (4 - 1);
                public static int MonthsOf() { return Months; }
                public static int BigOf() { return Big; }
                public static int MaskOf() { return Mask; }
                public static long WideOf() { return Wide; }
                public static int NestedOf() { return Nested; }
                // A read from inside a switch expression is the shape that produced invalid
                // bytecode, because a pattern temporary was on the stack at the time.
                public static string Guarded(int age) {
                    return age switch { _ when age > Months => "old", _ => "fine" };
                }
            }
            """;
        assertOutput("Consts", source, "MonthsOf", 480);
        assertOutput("Consts", source, "BigOf", 5000000);
        assertOutput("Consts", source, "MaskOf", 255);
        assertOutput("Consts", source, "WideOf", 1000000L);
        assertOutput("Consts", source, "NestedOf", 15);
        assertOutput("Consts", source, "Guarded", "old", 600);
        assertOutput("Consts", source, "Guarded", "fine", 12);
    }

    /// A callable that *creates* a lambda must hold every cell that lambda reads, including
    /// the ones it reads only because a lambda nested inside it does.
    ///
    /// `LambdaMetafactory` is handed the captured cells at the creation site, so the edge in
    /// the transitive-capture graph is the creation, not a call. Treating a functional value
    /// as carrying no edge left the outer lambda's method without a cell for the parameter the
    /// inner one read, which the backend could only report as an internal error.
    ///
    /// Three nesting levels, a captured *local* as well as a parameter, and a write from the
    /// innermost lambda that has to be visible outside it - the cell is shared, not copied.
    private void emitTransitiveLambdaCapture() {
        String source = """
            using java.util.function;
            public struct Nested {
                static string Apply(Function<string, string> f, string v) { return f.Apply(v); }
                public static string Two(string tag) {
                    Supplier<string> outer = () => Apply(inner => tag + inner, "x");
                    return outer.Get();
                }
                public static string Three(string tag) {
                    string local = "-";
                    Supplier<string> outer = () => Apply(
                        a => Apply(b => tag + local + a + b, "2"), "1");
                    return outer.Get();
                }
                public static int Written(int seed) {
                    int total = seed;
                    Supplier<string> outer = () => Apply(inner =>
                    {
                        total = total + 5;
                        return inner;
                    }, "x");
                    outer.Get();
                    return total;
                }
            }
            """;
        assertOutput("Nested", source, "Two", "tx", (Object) "t");
        assertOutput("Nested", source, "Three", "t-12", (Object) "t");
        assertOutput("Nested", source, "Written", 15, 10);
    }

    /// Both arms of a conditional, and every arm of a switch expression, are converted to the
    /// type the arms agreed on (C# §12.18, the design).
    ///
    /// The best common type is not automatically the type of any one arm: `count == 0 ? 0 :
    /// total / count` mixes an `int` constant with a `long`. Leaving the widening out produced
    /// a branch that pushed one stack slot where the other pushed two - invalid bytecode the
    /// class-file writer rejected, which reached the user as an internal compiler error rather
    /// than a wrong answer. The switch expression needed the conversion recorded in the
    /// *binding* as well, because it is lowered from its syntax.
    ///
    /// All four shapes were compared against .NET 10.
    private void emitConvertedResultArms() {
        String source = """
            public struct Arms {
                public static long Average(long total, int count) {
                    return count == 0 ? 0 : total / count;
                }
                public static long Band(int n, long big) {
                    return n switch { 0 => 0, 1 => big, _ => big * 2 };
                }
                public static double Mixed(int a, double b) { return a > 0 ? a : b; }
                public static string Ref(bool c, string s) { return c ? s : null; }
            }
            """;
        assertOutput("Arms", source, "Average", 2L, 10L, 4);
        assertOutput("Arms", source, "Average", 0L, 10L, 0);
        assertOutput("Arms", source, "Band", 0L, 0, 7L);
        assertOutput("Arms", source, "Band", 7L, 1, 7L);
        assertOutput("Arms", source, "Band", 14L, 2, 7L);
        assertOutput("Arms", source, "Mixed", 3.0d, 3, 2.5d);
        assertOutput("Arms", source, "Mixed", 2.5d, -1, 2.5d);
        // A reference arm needs no conversion and must not acquire one.
        assertOutput("Arms", source, "Ref", "x", true, "x");
        assertOutput("Arms", source, "Ref", null, false, "x");
    }

    /// `string.Format` is interpolation with the format text arriving as a value, so
    /// the two must agree on everything: reordering and repetition of holes, alignment in both
    /// directions, the `D`/`X`/`F` specifiers, alignment *and* specifier together, brace
    /// escaping, `null` rendering as the empty string, and C#'s `True` for a `bool`.
    ///
    /// Every expectation below was taken from .NET 10 under invariant globalization, and the
    /// whole set was compared byte for byte rather than written by hand.
    private void emitStringFormat() {
        String source = """
            public struct Fmt {
                public static string Order() { return string.Format("{1}{0}", "a", "b"); }
                public static string Repeat() { return string.Format("{0}{0}{0}", "x"); }
                public static string Right() { return string.Format("[{0,6}]", 42); }
                public static string Left() { return string.Format("[{0,-6}]", 42); }
                public static string Wide() { return string.Format("[{0,2}]", "abcdef"); }
                public static string Specs() { return string.Format("{0:F2}|{1:D}", 3.14159, 255); }
                public static string Hex() { return string.Format("{0:X}|{1:F0}", 255, 2.5); }
                public static string Both() { return string.Format("{0,8:F2}|", 3.14159); }
                public static string BothLeft() { return string.Format("{0,-8:F2}|", 3.14159); }
                public static string Braces() { return string.Format("{{literal}} {0}", "v"); }
                public static string None() { return string.Format("no holes"); }
                public static string Bool() { return string.Format("{0}", true); }
                // A `null` *element* renders as the empty string. A bare `null` would be the
                // params array itself, which .NET rejects with the same message V# does, so
                // the case that exercises the element needs a second argument to force the
                // expansion - exactly as it does in C#.
                public static string Null() { return string.Format("[{0}]", null, 0); }
                public static string Mixed() { return string.Format("{0}|{1}", 'c', 1.5); }
            }
            """;
        assertOutput("Fmt", source, "Order", "ba");
        assertOutput("Fmt", source, "Repeat", "xxx");
        assertOutput("Fmt", source, "Right", "[    42]");
        assertOutput("Fmt", source, "Left", "[42    ]");
        assertOutput("Fmt", source, "Wide", "[abcdef]");
        assertOutput("Fmt", source, "Specs", "3.14|255");
        assertOutput("Fmt", source, "Hex", "FF|2");
        assertOutput("Fmt", source, "Both", "    3.14|");
        assertOutput("Fmt", source, "BothLeft", "3.14    |");
        assertOutput("Fmt", source, "Braces", "{literal} v");
        assertOutput("Fmt", source, "None", "no holes");
        assertOutput("Fmt", source, "Bool", "True");
        assertOutput("Fmt", source, "Null", "[]");
        assertOutput("Fmt", source, "Mixed", "c|1.5");
    }

    /// C# §6.4.5.3's one exception to the sign-blind literal ladder: a decimal literal
    /// of magnitude 2^31 or 2^63 with no suffix, standing immediately after a unary minus, is
    /// part of the literal. Without it a program cannot write `int.MinValue`'s value.
    ///
    /// The three negatives matter as much as the positives: the same digits on their own stay
    /// `uint` and `ulong`, a suffixed or hexadecimal spelling is outside the rule, and an
    /// ordinary negation is untouched.
    private void emitNegatedMinimumLiterals() {
        String source = """
            public struct Lit {
                public static int Min() { int a = -2147483648; return a; }
                public static bool IsMin() { return -2147483648 == int.MinValue; }
                public static long LongMin() { long b = -9223372036854775808; return b; }
                public static bool IsLongMin() { return -9223372036854775808 == long.MinValue; }
                public static uint StillUnsigned() { uint c = 2147483648; return c; }
                public static long Suffixed() { long e = -2147483648L; return e; }
                public static long Hexadecimal() { long f = -0x7FFFFFFF; return f; }
                public static int Ordinary() { return -2147483647; }
                public static int InExpression() { return -2147483648 + 1; }
            }
            """;
        assertOutput("Lit", source, "Min", Integer.MIN_VALUE);
        assertOutput("Lit", source, "IsMin", true);
        assertOutput("Lit", source, "LongMin", Long.MIN_VALUE);
        assertOutput("Lit", source, "IsLongMin", true);
        // `uint` shares the JVM's signed carrier, so the raw 32 bits are what comes back:
        // 2147483648 unsigned is Integer.MIN_VALUE signed, which is the point - the literal
        // was never negated, only reinterpreted.
        assertOutput("Lit", source, "StillUnsigned", Integer.MIN_VALUE);
        assertOutput("Lit", source, "Suffixed", (long) Integer.MIN_VALUE);
        assertOutput("Lit", source, "Hexadecimal", -2147483647L);
        assertOutput("Lit", source, "Ordinary", -2147483647);
        assertOutput("Lit", source, "InExpression", -2147483647);
    }

    /// UTF-8 literals are `byte[]` constants, and `byte` keeps C#'s unsigned interpretation.
    private void emitUtf8StringLiteral() {
        String source = """
            public struct Utf8 {
                public static string Run() {
                    byte[] data = "é"u8;
                    return data.Length + "|" + data[0] + "|" + data[1];
                }
            }
            """;
        assertOutput("Utf8", source, "Run", "2|195|169");
    }

    private void emitUserDefinedConversions() {
        String source = """
            public struct Conversions {
                public int Value;

                public static implicit operator int(Conversions value) {
                    return value.Value;
                }

                public static explicit operator Conversions(int value) {
                    Conversions result = default(Conversions);
                    result.Value = value * 2;
                    return result;
                }

                public static int Run() {
                    Conversions value = (Conversions)21;
                    int result = value;
                    return result;
                }
            }
            """;
        assertOutput("Conversions", source, "Run", 42);
    }

    private void emitMarkerAttributes() {
        String source = """
            using java.lang;

            public struct Annotated {
                [Deprecated]
                public static int Value() {
                    return 7;
                }
            }
            """;
        Class<?> program = compileAndLoad("Annotated", source);
        try {
            Method value = program.getMethod("Value");
            Assert.isTrue(value.isAnnotationPresent(Deprecated.class),
                    "marker attributes should reach Java reflection");
            Assert.equal(7, value.invoke(null), "the annotated method should execute");
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("the annotated method could not be invoked", failure);
        }
    }

    /// Interpolation alignment: space padding to a minimum width, right-aligned for a
    /// positive clause and left-aligned for a negative one, never truncating.
    private void emitInterpolationAlignment() {
        String source = """
            public struct Align {
                public static string Right(int v) { return $"[{v,5}]"; }
                public static string Left(int v) { return $"[{v,-5}]"; }
                public static string Exact(string s) { return $"[{s,3}]"; }
                public static string TooWide(string s) { return $"[{s,2}]"; }
                public static string Zero(int v) { return $"[{v,0}]"; }
                public static string Constant(int v) { return $"[{v,2 + 3}]"; }
                public static string Several(int a, string b) { return $"{a,3}|{b,-3}|"; }
            }
            """;
        assertOutput("Align", source, "Right", "[   42]", 42);
        assertOutput("Align", source, "Left", "[42   ]", 42);
        assertOutput("Align", source, "Exact", "[abc]", (Object) "abc");
        // C# pads to a *minimum* width, so a wider value is never truncated.
        assertOutput("Align", source, "TooWide", "[abcd]", (Object) "abcd");
        assertOutput("Align", source, "Zero", "[7]", 7);
        assertOutput("Align", source, "Constant", "[    7]", 7);
        assertOutput("Align", source, "Several", "  1|xy |", 1, "xy");
    }

    /// List and slice patterns over the two collections V# lengths and indexes: a
    /// vector array and a `string`. Covers the exact-length form, a slice's `>=` form,
    /// suffix elements indexed from the end, a bound slice, and nesting.
    private void emitListPatterns() {
        String source = """
            public struct Lists {
                public static int Exact(int[] xs) {
                    if (xs is []) { return 0; }
                    if (xs is [1, 2]) { return 12; }
                    if (xs is [var only]) { return only; }
                    return -1;
                }

                public static int Slice(int[] xs) {
                    if (xs is [1, .., 9]) { return 19; }
                    if (xs is [var first, ..]) { return first; }
                    return -1;
                }

                public static int Bound(int[] xs) {
                    if (xs is [_, .. var rest]) { return rest.Length; }
                    return -1;
                }

                public static int Nested(int[] xs) {
                    if (xs is [1, .. var rest] && rest is [2, 3]) { return 123; }
                    return -1;
                }

                public static int Text(string s) {
                    if (s is ['a', ..]) { return 1; }
                    if (s is [.., 'z']) { return 2; }
                    if (s is [var only]) { return only; }
                    return -1;
                }

                public static string Tail(string s) {
                    if (s is [_, .. var rest]) { return rest; }
                    return "";
                }

                public static bool RejectsNull(int[] xs) {
                    return xs is [..];
                }
            }
            """;
        assertOutput("Lists", source, "Exact", 0, (Object) new int[0]);
        assertOutput("Lists", source, "Exact", 12, (Object) new int[] {1, 2});
        assertOutput("Lists", source, "Exact", 7, (Object) new int[] {7});
        assertOutput("Lists", source, "Exact", -1, (Object) new int[] {1, 2, 3});
        assertOutput("Lists", source, "Slice", 19, (Object) new int[] {1, 5, 9});
        assertOutput("Lists", source, "Slice", 19, (Object) new int[] {1, 9});
        assertOutput("Lists", source, "Slice", 4, (Object) new int[] {4, 5});
        assertOutput("Lists", source, "Slice", -1, (Object) new int[0]);
        assertOutput("Lists", source, "Bound", 2, (Object) new int[] {1, 2, 3});
        assertOutput("Lists", source, "Bound", 0, (Object) new int[] {1});
        assertOutput("Lists", source, "Nested", 123, (Object) new int[] {1, 2, 3});
        assertOutput("Lists", source, "Nested", -1, (Object) new int[] {1, 2});
        assertOutput("Lists", source, "Text", 1, (Object) "abc");
        assertOutput("Lists", source, "Text", 2, (Object) "xyz");
        assertOutput("Lists", source, "Text", (int) 'q', (Object) "q");
        assertOutput("Lists", source, "Tail", "bc", (Object) "abc");
        assertOutput("Lists", source, "Tail", "", (Object) "a");
        assertOutput("Lists", source, "RejectsNull", false, (Object) null);
        assertOutput("Lists", source, "RejectsNull", true, (Object) new int[0]);
    }

    /// Deconstruction *declarations* and tuple conversions. Both used to reach
    /// `VS29999`: a declaration's targets have no slots when the store runs, and a tuple
    /// conversion had no emission at all - which is what an ordinary named-element tuple
    /// declaration produces, since `(int, string)` to `(int Count, string Word)` is a
    /// conversion in the type system even though it renames nothing at run time.
    private void emitTupleDeconstructionAndConversion() {
        String source = """
            public struct Decon {
                public static int Typed(int n, string text) {
                    (int, string) pair = (n, text);
                    (int value, string word) = pair;
                    return value * 10 + word.Length;
                }

                public static int Inferred(int n, string text) {
                    (int, string) pair = (n, text);
                    var (value, word) = pair;
                    return value * 10 + word.Length;
                }

                public static int NamedElements(int n, string text) {
                    (int Count, string Word) pair = (n, text);
                    return pair.Count * 10 + pair.Word.Length;
                }

                public static long WidenedElements(int a, int b) {
                    (long, long) widened = (a, b);
                    (long first, long second) = widened;
                    return first * 1000 + second;
                }

                public static int Nested(int a, int b, string text) {
                    ((int, int), string) value = ((a, b), text);
                    ((int x, int y), string word) = value;
                    return x * 100 + y * 10 + word.Length;
                }

                public static string NestedConversions(int a, int b, int c, string text) {
                    ((long, double), (long, string)) converted = ((a, b), (c, text));
                    ((long x, double y), (long z, string word)) = converted;
                    return x + "|" + y + "|" + z + "|" + word;
                }
            }
            """;
        assertOutput("Decon", source, "Typed", 43, 4, "xyz");
        assertOutput("Decon", source, "Inferred", 43, 4, "xyz");
        assertOutput("Decon", source, "NamedElements", 43, 4, "xyz");
        assertOutput("Decon", source, "WidenedElements", 2003L, 2, 3);
        assertOutput("Decon", source, "Nested", 123, 1, 2, "xyz");
        assertOutput("Decon", source, "NestedConversions", "1|2|3|x", 1, 2, 3, "x");
    }

    /// `sizeof` has a fixed C# table rather than following JVM slot widths (`decimal` is a
    /// reference carrier here but is 16 bytes in C#). `typeof` uses interned source identity,
    /// so keyword/corelib aliases compare equal while signed/unsigned types that share a JVM
    /// primitive carrier remain distinct.
    private void emitTypeOperations() {
        String source = """
            using System;

            public struct OpenBox<T> { }

            public struct TypeOperations {
                public static int Sizes() {
                    return sizeof(sbyte) + sizeof(byte) + sizeof(short) + sizeof(ushort)
                        + sizeof(int) + sizeof(uint) + sizeof(long) + sizeof(ulong)
                        + sizeof(char) + sizeof(float) + sizeof(double) + sizeof(decimal)
                        + sizeof(bool);
                }

                public static bool AliasIdentity() {
                    return typeof(int) == typeof(Int32)
                        && typeof(int) == typeof(Int32)
                        && typeof(string) == typeof(String);
                }

                public static bool CarrierDistinctions() {
                    return typeof(int) != typeof(uint)
                        && typeof(sbyte) != typeof(byte)
                        && typeof(long) != typeof(ulong)
                        && typeof(int[]) != typeof(uint[]);
                }

                public static bool TupleNamesErase() {
                    return typeof((int Left, string Right)) == typeof((int, string));
                }

                public static bool OpenGenericIdentity() {
                    return typeof(OpenBox<>) != typeof(OpenBox<int>);
                }

                public static object IntToken() { return typeof(int); }
                public static object VoidToken() { return typeof(void); }
            }
            """;
        assertOutput("TypeOperations", source, "Sizes", 61);
        assertOutput("TypeOperations", source, "AliasIdentity", true);
        assertOutput("TypeOperations", source, "CarrierDistinctions", true);
        assertOutput("TypeOperations", source, "TupleNamesErase", true);
        assertOutput("TypeOperations", source, "OpenGenericIdentity", true);
        assertOutput("TypeOperations", source, "IntToken",
                vsharp.runtime.VsType.of("System.Int32"));
        assertOutput("TypeOperations", source, "VoidToken",
                vsharp.runtime.VsType.of("System.Void"));
    }

    /// Recursive patterns: a positional pattern over a value tuple, a property
    /// pattern over a tuple element name, an array's or string's `Length`, and a declared
    /// struct's fields - each nesting arbitrary subpatterns and short-circuiting.
    private void emitRecursivePatterns() {
        String source = """
            public struct Point { public int X; public int Y; }

            public struct Recursive {
                // A tuple *parameter* is carried as `Object`, so the component access needs
                // its own `checkcast`; without one this shape failed JVM verification while
                // the local-variable shape below verified fine.
                public static int ThroughParameter(int n, string text) {
                    return FromTuple((n, text));
                }

                private static int FromTuple((int, string) pair) {
                    if (pair is (1, "one")) { return 1; }
                    if (pair is (var value, var word) && word is not null) {
                        return value * 10 + word.Length;
                    }
                    return -1;
                }

                public static int Tuple(int n, string text) {
                    (int, string) pair = (n, text);
                    if (pair is (1, "one")) { return 1; }
                    if (pair is (var value, var word) && word is not null) {
                        return value * 10 + word.Length;
                    }
                    return -1;
                }

                public static int ByItemName(int n, string text) {
                    (int, string) pair = (n, text);
                    if (pair is { Item1: > 5, Item2: var word }) { return word.Length; }
                    if (pair is { Item1: var first }) { return first; }
                    return -1;
                }

                public static int StringLength(object o) {
                    if (o is string { Length: 3 }) { return 3; }
                    if (o is string { Length: > 3 } s) { return s.Length; }
                    if (o is string { Length: var n }) { return -n; }
                    return -100;
                }

                public static int ArrayLength(int[] xs) {
                    if (xs is { Length: 0 }) { return 0; }
                    if (xs is { Length: var n } && n > 2) { return n; }
                    return -1;
                }

                public static int Fields(int x, int y) {
                    Point p = default(Point);
                    p.X = x;
                    p.Y = y;
                    object boxed = p;
                    if (boxed is Point { X: 1, Y: var got }) { return 100 + got; }
                    if (boxed is Point { X: var a, Y: var b }) { return a * 10 + b; }
                    return -1;
                }

                public static int Selected(int n, string text) {
                    (int, string) pair = (n, text);
                    return pair switch {
                        (0, _) => 0,
                        (var value, null) => value,
                        (var value, var word) => value * 100 + word.Length,
                    };
                }

                public static bool RejectsNull(string s) {
                    return s is { Length: 1 };
                }
            }
            """;
        assertOutput("Recursive", source, "ThroughParameter", 1, 1, "one");
        assertOutput("Recursive", source, "ThroughParameter", 23, 2, "abc");
        assertOutput("Recursive", source, "ThroughParameter", -1, 2, null);
        assertOutput("Recursive", source, "Tuple", 1, 1, "one");
        assertOutput("Recursive", source, "Tuple", 23, 2, "abc");
        assertOutput("Recursive", source, "Tuple", -1, 2, null);
        assertOutput("Recursive", source, "ByItemName", 4, 6, "four");
        assertOutput("Recursive", source, "ByItemName", 2, 2, "four");
        assertOutput("Recursive", source, "StringLength", 3, (Object) "abc");
        assertOutput("Recursive", source, "StringLength", 5, (Object) "abcde");
        assertOutput("Recursive", source, "StringLength", -1, (Object) "a");
        assertOutput("Recursive", source, "StringLength", -100, (Object) 7);
        assertOutput("Recursive", source, "ArrayLength", 0, (Object) new int[0]);
        assertOutput("Recursive", source, "ArrayLength", 3, (Object) new int[] {1, 2, 3});
        assertOutput("Recursive", source, "ArrayLength", -1, (Object) new int[] {1, 2});
        assertOutput("Recursive", source, "Fields", 105, 1, 5);
        assertOutput("Recursive", source, "Fields", 34, 3, 4);
        assertOutput("Recursive", source, "Selected", 0, 0, "x");
        assertOutput("Recursive", source, "Selected", 7, 7, null);
        assertOutput("Recursive", source, "Selected", 703, 7, "abc");
        // A typeless property pattern rejects null before reading any component, as C# does.
        assertOutput("Recursive", source, "RejectsNull", false, (Object) null);
        assertOutput("Recursive", source, "RejectsNull", false, (Object) "ab");
        assertOutput("Recursive", source, "RejectsNull", true, (Object) "a");
    }

    /// A bare `catch` is the JVM's typeless catch-all handler entry. It catches what a
    /// typed clause would, it participates in first-match ordering with typed clauses, and it
    /// composes with `finally`.
    private void emitBareCatch() {
        String source = """
            using java.lang;
            public struct Bare {
                public static int Catches(int a, int b) {
                    try { return a / b; }
                    catch { return -1; }
                }

                public static int OrderedAfterTyped(int b) {
                    try { return 100 / b; }
                    catch (IllegalArgumentException) { return 1; }
                    catch { return 2; }
                }

                public static int WithFinally(int b) {
                    int trace = 0;
                    try { trace = 100 / b; }
                    catch { trace = 5; }
                    finally { trace = trace + 1000; }
                    return trace;
                }

                public static int NoHandlerRuns(int b) {
                    try { return 100 / b; }
                    catch { return -1; }
                }
            }
            """;
        assertOutput("Bare", source, "Catches", 5, 20, 4);
        assertOutput("Bare", source, "Catches", -1, 20, 0);
        // The typed clause does not match the ArithmeticException that `100 / 0` raises, so
        // the later catch-all takes it - first-match order, not catch-all-wins.
        assertOutput("Bare", source, "OrderedAfterTyped", 2, 0);
        assertOutput("Bare", source, "OrderedAfterTyped", 25, 4);
        assertOutput("Bare", source, "WithFinally", 1005, 0);
        assertOutput("Bare", source, "WithFinally", 1025, 4);
        assertOutput("Bare", source, "NoHandlerRuns", 50, 2);
    }

    /// `throw;` reloads the innermost enclosing handler's caught reference and rethrows it,
    /// preserving exception identity. Verified from a typed clause, from a bare clause,
    /// through an intervening `finally`, and across nesting where the inner handler's rethrow
    /// must reach the outer one.
    private void emitRethrow() {
        String source = """
            using java.lang;
            using System;
            public struct Rethrow {
                public static int Trace;

                public static int Outward(int b) {
                    Trace = 0;
                    try {
                        try { return 100 / b; }
                        catch (Exception) { Trace = Trace + 1; throw; }
                    } catch (Exception) {
                        Trace = Trace + 20;
                        return -1;
                    }
                }

                public static int FromBareClause(int b) {
                    Trace = 0;
                    try {
                        try { return 100 / b; }
                        catch { Trace = Trace + 2; throw; }
                    } catch { return -2; }
                }

                public static int RunsFinallyOnTheWayOut(int b) {
                    Trace = 0;
                    try {
                        try { return 100 / b; }
                        catch (Exception) { throw; }
                        finally { Trace = Trace + 7; }
                    } catch (Exception) { return -3; }
                }

                public static bool PreservesIdentity() {
                    Exception original = new Exception("marker");
                    try {
                        try { throw original; }
                        catch (Exception) { throw; }
                    } catch (Exception caught) {
                        return caught == original;
                    }
                }
            }
            """;
        assertOutput("Rethrow", source, "Outward", -1, 0);
        assertOutput("Rethrow", source, "Outward", 50, 2);
        assertOutput("Rethrow", source, "FromBareClause", -2, 0);
        assertOutput("Rethrow", source, "FromBareClause", 25, 4);
        assertOutput("Rethrow", source, "RunsFinallyOnTheWayOut", -3, 0);
        assertOutput("Rethrow", source, "PreservesIdentity", true);

        try {
            Class<?> emitted = compileAndLoad("Rethrow", source);
            emitted.getMethod("Outward", int.class).invoke(null, 0);
            Assert.equal(21, emitted.getField("Trace").get(null),
                    "both handlers ran: inner rethrow reached the outer clause");
            emitted.getMethod("RunsFinallyOnTheWayOut", int.class).invoke(null, 0);
            Assert.equal(7, emitted.getField("Trace").get(null),
                    "the finally ran while the rethrown exception unwound");
        } catch (ReflectiveOperationException error) {
            throw new RuntimeException(error);
        }
    }

    private void emitConditionalExpression() {
        String source = """
            public struct Ternary {
                public static int Max(int a, int b) {
                    return a > b ? a : b;
                }
            }
            """;
        assertOutput("Ternary", source, "Max", 5, 5, 3);
        assertOutput("Ternary", source, "Max", 7, 2, 7);
    }

    private void emitNestedConditionalExpression() {
        // A conditional nested in the `whenFalse` branch of another - checks that the inner
        // conditional's own labels don't collide with the outer's.
        String source = """
            public struct Ternary {
                public static int Sign(int n) {
                    return n > 0 ? 1 : n < 0 ? -1 : 0;
                }
            }
            """;
        assertOutput("Ternary", source, "Sign", 1, 5);
        assertOutput("Ternary", source, "Sign", -1, -5);
        assertOutput("Ternary", source, "Sign", 0, 0);
    }

    private void emitStringMembers() {
        String source = """
            public struct Strings {
                static int effects;

                public static int Length(string s) { return s.Length; }
                public static char Element(string s, int index) { return s[index]; }
                public static string Substring(string s, int start) { return s.Substring(start); }
                public static string SubstringLen(string s, int start, int len) { return s.Substring(start, len); }

                static string Receiver() { effects = effects * 10 + 1; return "ABCDE"; }
                static int Index() { effects = effects * 10 + 2; return 1; }

                public static int ElementEffects() {
                    effects = 0;
                    char value = Receiver()[Index()];
                    return effects * 100 + (int)value;
                }

                public static int SubstringEffects() {
                    effects = 0;
                    string value = Receiver().Substring(Index(), 2);
                    return effects * 100 + value.Length;
                }
            }
            """;

        assertOutput("Strings", source, "Length", 11, (Object) "Hello World");
        assertOutput("Strings", source, "Length", 4, (Object) "A😀B");
        assertOutput("Strings", source, "Element", 'W', (Object) "Hello World", 6);
        assertOutput("Strings", source, "Element", '\uD83D', (Object) "A😀B", 1);
        assertOutput("Strings", source, "Element", '\uDE00', (Object) "A😀B", 2);
        assertOutput("Strings", source, "Substring", "World", (Object) "Hello World", 6);
        assertOutput("Strings", source, "SubstringLen", "ell", (Object) "Hello World", 1, 3);
        assertOutput("Strings", source, "SubstringLen", "\uD83D", (Object) "A😀B", 1, 1);
        assertOutput("Strings", source, "SubstringLen", "\uDE00", (Object) "A😀B", 2, 1);
        assertOutput("Strings", source, "SubstringLen", "😀", (Object) "A😀B", 1, 2);
        assertOutput("Strings", source, "ElementEffects", 1266);
        assertOutput("Strings", source, "SubstringEffects", 1202);
    }

    /// `uint`/`ulong` share the JVM's signed carriers, so every operation whose result depends
    /// on how the bit pattern is *read* has to be selected by the V# type. Each case here is
    /// one the plain signed opcode gets wrong: `4294967295u / 3` is `1431655765` and not `0`,
    /// `4294967295u > 1` is true where `if_icmpgt` says false, and rendering the same carrier
    /// must produce `4294967295` and not `-1`.
    private void emitUnsignedOperations() {
        String source = """
            public struct Unsigned {
                public static uint Divide(uint a, uint b) { return a / b; }
                public static uint Remainder(uint a, uint b) { return a % b; }
                public static bool Greater(uint a, uint b) { return a > b; }
                public static bool Equal(uint a, uint b) { return a == b; }
                public static uint Shift(uint a, int n) { return a >> n; }
                public static uint Wrap(uint a) { return a + 1; }

                public static ulong LongDivide(ulong a, ulong b) { return a / b; }
                public static ulong LongRemainder(ulong a, ulong b) { return a % b; }
                public static bool LongGreater(ulong a, ulong b) { return a > b; }

                public static long Widen(uint a) { return a; }
                public static double ToDouble(uint a) { return a; }
                public static double LongToDouble(ulong a) { return a; }
                public static float ToFloat(uint a) { return a; }
                public static int Narrow(uint a) { return (int)a; }
                public static uint FromDouble(double d) { return (uint)d; }
                public static ulong LongFromDouble(double d) { return (ulong)d; }

                public static string Render(uint a) { return "" + a; }
                public static string LongRender(ulong a) { return "" + a; }
                public static string Interpolate(uint a, ulong b) { return $"{a}/{b}"; }
            }
            """;

        int maxUint = -1;                      // 4294967295u in the signed int carrier
        long maxUlong = -1L;                   // 18446744073709551615ul in the signed long carrier

        assertOutput("Unsigned", source, "Divide", 1431655765, maxUint, 3);
        assertOutput("Unsigned", source, "Remainder", 5, maxUint, 10);
        assertOutput("Unsigned", source, "Greater", true, maxUint, 1);
        assertOutput("Unsigned", source, "Greater", false, 1, maxUint);
        assertOutput("Unsigned", source, "Equal", true, maxUint, maxUint);
        assertOutput("Unsigned", source, "Shift", 268435455, maxUint, 4);
        assertOutput("Unsigned", source, "Wrap", 0, maxUint);

        assertOutput("Unsigned", source, "LongDivide", 6148914691236517205L, maxUlong, 3L);
        assertOutput("Unsigned", source, "LongRemainder", 5L, maxUlong, 10L);
        assertOutput("Unsigned", source, "LongGreater", true, maxUlong, 1L);

        assertOutput("Unsigned", source, "Widen", 4294967295L, maxUint);
        assertOutput("Unsigned", source, "ToDouble", 4294967295.0d, maxUint);
        assertOutput("Unsigned", source, "LongToDouble", 1.8446744073709552E19d, maxUlong);
        assertOutput("Unsigned", source, "ToFloat", 4.2949673E9f, maxUint);
        assertOutput("Unsigned", source, "Narrow", -1, maxUint);
        assertOutput("Unsigned", source, "FromDouble", maxUint, 4294967295.0d);
        assertOutput("Unsigned", source, "LongFromDouble", maxUlong, 1.8446744073709552E19d);

        assertOutput("Unsigned", source, "Render", "4294967295", maxUint);
        assertOutput("Unsigned", source, "LongRender", "18446744073709551615", maxUlong);
        assertOutput("Unsigned", source, "Interpolate", "4294967295/18446744073709551615",
                maxUint, maxUlong);
    }

    /// Primitive unsigned values keep the JVM's `int`/`long` descriptors. Only an
    /// object/nullable boundary needs a distinct box so `-1`'s bits remain distinguishable
    /// from signed `-1`; nullable unboxing and lifted arithmetic must then recover the same
    /// primitive carrier rather than leaking the box into numeric code.
    private void emitBoxedUnsignedValues() {
        String source = """
            public struct BoxedUnsigned {
                public static string ContextualShadow() {
                    long nuint = 7L;
                    return nuint.ToString();
                }

                public static string Render() {
                    byte small = 200;
                    ushort medium = 40000;
                    uint u = uint.MaxValue;
                    ulong ul = ulong.MaxValue;
                    nuint nu = nuint.MaxValue;
                    byte? nullableSmall = small;
                    ushort? nullableMedium = medium;
                    uint? nullableU = u;
                    ulong? nullableUl = ul;
                    nuint? nullableNu = nu;
                    uint? advanced = nullableU + 1u;
                    uint? empty = null;

                    object boxedSmall = small;
                    object boxedMedium = medium;
                    object boxedU = u;
                    object boxedUl = ul;
                    object boxedNu = nu;
                    object boxedNullableSmall = nullableSmall;
                    object boxedNullableMedium = nullableMedium;
                    object boxedNullableU = nullableU;
                    object boxedNullableUl = nullableUl;
                    object boxedNullableNu = nullableNu;

                    return boxedSmall + "|" + boxedMedium + "|" + boxedU + "|"
                        + boxedUl + "|" + boxedNu + "|" + boxedNullableSmall + "|"
                        + boxedNullableMedium + "|" + boxedNullableU + "|" + boxedNullableUl + "|"
                        + boxedNullableNu + "|" + advanced + "|" + empty;
                }
            }
            """;
        assertOutput("BoxedUnsigned", source, "Render",
                "200|40000|4294967295|18446744073709551615|18446744073709551615|"
                        + "200|40000|4294967295|18446744073709551615|"
                        + "18446744073709551615|0|");
        assertOutput("BoxedUnsigned", source, "ContextualShadow", "7");
    }

    private void emitNullConditionalAccess() {
        String source = """
            using System;
            public struct ConditionalAccess {
                static string current;
                static int effects;

                static string Receiver() {
                    effects = effects * 10 + 1;
                    return current;
                }

                static int Argument() {
                    effects = effects * 10 + 2;
                    return 1;
                }

                public static int? Length(string value) { return value?.Length; }
                public static string Tail(string value) { return value?.Substring(1); }
                public static int? First(int[] values) { return values?[0]; }
                public static char? Character(string value) { return value?[1]; }

                public static int Chain(string value) {
                    return value?.Substring(1).Length ?? -1;
                }

                public static string DoubleConditional(string value) {
                    return value?.Substring(1)?.Substring(1);
                }

                public static int Effects(string value) {
                    current = value;
                    effects = 0;
                    string result = Receiver()?.Substring(Argument());
                    return effects * 100 + (result == null ? 0 : result.Length);
                }

                public static int IndexEffects(int[] values) {
                    effects = 0;
                    int result = values?[Argument()] ?? -1;
                    return effects * 100 + result;
                }

                public static int Parenthesized(string value) {
                    try {
                        return (value?.Substring(1)).Length;
                    } catch (Exception error) {
                        return 77;
                    }
                }
            }
            """;

        assertOutput("ConditionalAccess", source, "Length", 4, (Object) "text");
        assertOutput("ConditionalAccess", source, "Length", null, new Object[] { null });
        assertOutput("ConditionalAccess", source, "Tail", "ext", (Object) "text");
        assertOutput("ConditionalAccess", source, "Tail", null, new Object[] { null });
        assertOutput("ConditionalAccess", source, "First", 7,
                (Object) new int[] { 7, 8 });
        assertOutput("ConditionalAccess", source, "First", null, new Object[] { null });
        assertOutput("ConditionalAccess", source, "Character", 'e', (Object) "text");
        assertOutput("ConditionalAccess", source, "Character", null,
                new Object[] { null });
        assertOutput("ConditionalAccess", source, "Chain", 3, (Object) "text");
        assertOutput("ConditionalAccess", source, "Chain", -1, new Object[] { null });
        assertOutput("ConditionalAccess", source, "DoubleConditional", "xt",
                (Object) "text");
        assertOutput("ConditionalAccess", source, "DoubleConditional", null,
                new Object[] { null });
        assertOutput("ConditionalAccess", source, "Effects", 1203, (Object) "text");
        assertOutput("ConditionalAccess", source, "Effects", 100, new Object[] { null });
        assertOutput("ConditionalAccess", source, "IndexEffects", 208,
                (Object) new int[] { 7, 8 });
        assertOutput("ConditionalAccess", source, "IndexEffects", -1,
                new Object[] { null });
        assertOutput("ConditionalAccess", source, "Parenthesized", 3, (Object) "text");
        assertOutput("ConditionalAccess", source, "Parenthesized", 77,
                new Object[] { null });
    }

    private void emitExplicitNarrowingCast() {
        // `(int) d` truncates toward zero (C# §10.3.2), matching the JVM's `d2i` exactly.
        // Explicit syntax still lowers through the classified Conversion node, so nullable
        // and reference casts cannot diverge from the conversion engine.
        String source = """
            public struct Cast {
                public static int DoubleToInt(double d) {
                    return (int) d;
                }
            }
            """;
        assertOutput("Cast", source, "DoubleToInt", 3, 3.9);
        assertOutput("Cast", source, "DoubleToInt", -3, -3.9);
    }

    private void emitExplicitWideningCast() {
        // A cast can also widen (`(double) n`), even though it would already be implicit
        // without the cast - C# still allows and parses it as an explicit Cast node.
        String source = """
            public struct Cast {
                public static double IntToDouble(int n) {
                    return (double) n;
                }
            }
            """;
        assertOutput("Cast", source, "IntToDouble", 6.0, 6);
    }

    private void emitImplicitWideningConversion() {
        // `double d = n;` is an implicit int-to-double conversion (C# §10.2.1); the JVM has
        // no such implicit promotion, so the backend must insert a real `i2d`.
        String source = """
            public struct Convert {
                public static double IntToDouble(int n) {
                    double d = n;
                    return d + 0.5;
                }
            }
            """;
        assertOutput("Convert", source, "IntToDouble", 4.5, 4);
        assertOutput("Convert", source, "IntToDouble", -1.5, -2);
    }

    private void emitImplicitReturnConversion() {
        // Exercises the *other* call site the same binder fix touches: `return n;` from a
        // `double`-returning method needs the identical implicit int-to-double conversion as
        // the local-declaration case above, but through `requireReturnConversion` rather than
        // `bindLocal`.
        String source = """
            public struct Convert {
                public static double ReturnIntAsDouble(int n) {
                    return n;
                }
            }
            """;
        assertOutput("Convert", source, "ReturnIntAsDouble", 4.0, 4);
        assertOutput("Convert", source, "ReturnIntAsDouble", -7.0, -7);
    }

    private void emitFactorialDoWhile() {
        String source = """
            public struct Math {
                public static int Factorial(int n) {
                    int result = 1;
                    do {
                        result = result * n;
                        n = n - 1;
                    } while (n > 1);
                    return result;
                }
            }
            """;
        assertOutput("Math", source, "Factorial", 120, 5);
    }

    private void emitDoWhileRunsOnce() {
        // The loop condition is false from the start; a `do/while` must still run the body
        // exactly once, unlike `while`, which would skip it entirely (C# §13.9.5).
        String source = """
            public struct Math {
                public static int RunOnce(int n) {
                    int count = 0;
                    do {
                        count = count + 1;
                    } while (n > 0);
                    return count;
                }
            }
            """;
        assertOutput("Math", source, "RunOnce", 1, 0);
    }

    private void emitUnaryNegate() {
        String source = """
            public struct Math {
                public static int Negate(int n) {
                    return -n;
                }
            }
            """;
        assertOutput("Math", source, "Negate", -5, 5);
        assertOutput("Math", source, "Negate", 7, -7);
    }

    private void emitLongArithmeticAndUnaryOperations() {
        String source = """
            public struct WideMath {
                public static long Mix(long a, long b) {
                    return -(a + b) * 2L + (a / b) - (a % b);
                }

                public static long Complement(long value) {
                    return ~value;
                }
            }
            """;
        assertOutput("WideMath", source, "Mix", -24L, 10L, 3L);
        assertOutput("WideMath", source, "Complement", -6L, 5L);
    }

    private void emitFloatArithmeticAndUnaryOperations() {
        String source = """
            public struct FloatMath {
                public static float Mix(float a, float b) {
                    return -(a + b) * 2.0f + (a / b) - (a % b);
                }
            }
            """;
        assertOutput("FloatMath", source, "Mix", -9.5f, 3.0f, 2.0f);
    }

    private void emitSmallIntegralArithmeticAfterNumericPromotion() {
        String source = """
            public struct SmallMath {
                public static int Mix(byte value, short delta, char marker) {
                    return (value + delta) * marker + ~value;
                }
            }
            """;
        assertOutput("SmallMath", source, "Mix", 452, (byte) 2, (short) 5, 'A');
    }

    /// The JVM has no mixed-width arithmetic instruction: `long + int` must reach `ladd` with
    /// two longs on the stack or the class does not verify. This is the execution half of the
    /// binder's binary numeric promotion.
    private void emitMixedWidthArithmetic() {
        String source = """
            public struct Widths {
                public static long Add(long left, int right) {
                    return left + right;
                }
                public static double Scale(int count, double factor) {
                    return count * factor;
                }
                public static bool Below(int small, long large) {
                    return small < large;
                }
            }
            """;
        assertOutput("Widths", source, "Add", 86400001L, 86400000L, 1);
        assertOutput("Widths", source, "Scale", 7.5d, 3, 2.5d);
        assertOutput("Widths", source, "Below", true, 1, 2L);
        assertOutput("Widths", source, "Below", false, 3, 2L);
    }

    /// Every ordered comparison against NaN is false in C#, and `!=` is true - which on the JVM
    /// is a choice between the `g` and `l` flavours of the float compare opcodes at each site.
    private void emitFloatingComparisons() {
        String source = """
            public struct Compare {
                public static bool Less(double left, double right) {
                    return left < right;
                }
                public static bool AtLeast(double left, double right) {
                    return left >= right;
                }
                public static bool Same(double left, double right) {
                    return left == right;
                }
                public static bool Differs(double left, double right) {
                    return left != right;
                }
            }
            """;
        assertOutput("Compare", source, "Less", true, 1.0d, 2.0d);
        assertOutput("Compare", source, "Less", false, 2.0d, 1.0d);
        assertOutput("Compare", source, "AtLeast", true, 2.0d, 2.0d);
        assertOutput("Compare", source, "Same", true, 2.0d, 2.0d);

        double nan = Double.NaN;
        assertOutput("Compare", source, "Less", false, nan, 1.0d);
        assertOutput("Compare", source, "Less", false, 1.0d, nan);
        assertOutput("Compare", source, "AtLeast", false, nan, 1.0d);
        assertOutput("Compare", source, "AtLeast", false, 1.0d, nan);
        assertOutput("Compare", source, "Same", false, nan, nan);
        assertOutput("Compare", source, "Differs", true, nan, nan);
    }

    /// Risk R8: every construct that binds a shared end/break label binds it after the
    /// last instruction it emits. When the construct is the final statement of a method
    /// whose value is always returned from inside it, no instruction follows, so the
    /// label denotes one byte past the code array - a position `goto`/`ifeq` cannot
    /// legally name. `Try` hit this and was fixed with a trailing `nop`. `If`,
    /// `While`, `DoWhile` and `Switch` share the shape and had never been exercised in
    /// it, because every existing test happens to have a trailing statement. Each method
    /// here is deliberately written with no statement after the construct, which C#
    /// permits only because the construct's own end point is unreachable.
    private void emitConstructAsLastStatementOfValueMethod() {
        String source = """
            public struct LastStatement {
                public static int IfElse(int n) {
                    if (n > 0) {
                        return 1;
                    } else {
                        return -1;
                    }
                }
                public static int WhileTrue(int n) {
                    while (true) {
                        n = n + 1;
                        if (n > 3) {
                            return n;
                        }
                    }
                }
                public static int DoWhileTrue(int n) {
                    do {
                        n = n + 1;
                        if (n > 3) {
                            return n;
                        }
                    } while (true);
                }
                public static int SwitchArms(int n) {
                    switch (n) {
                        case 1:
                            return 10;
                        case 2:
                            return 20;
                        default:
                            return 0;
                    }
                }
                public static int NestedIfInLoop(int n) {
                    while (true) {
                        if (n > 0) {
                            return n;
                        } else {
                            return -n;
                        }
                    }
                }
            }
            """;
        assertOutput("LastStatement", source, "IfElse", 1, 5);
        assertOutput("LastStatement", source, "IfElse", -1, -5);
        assertOutput("LastStatement", source, "WhileTrue", 4, 0);
        assertOutput("LastStatement", source, "WhileTrue", 8, 7);
        assertOutput("LastStatement", source, "DoWhileTrue", 4, 0);
        assertOutput("LastStatement", source, "DoWhileTrue", 8, 7);
        assertOutput("LastStatement", source, "SwitchArms", 10, 1);
        assertOutput("LastStatement", source, "SwitchArms", 20, 2);
        assertOutput("LastStatement", source, "SwitchArms", 0, 9);
        assertOutput("LastStatement", source, "NestedIfInLoop", 6, 6);
        assertOutput("LastStatement", source, "NestedIfInLoop", 6, -6);
    }

    /// V# `byte` and `ushort` are unsigned but ride in the JVM's signed `byte`/`short`
    /// carriers, so every widening has to undo the sign extension the JVM applies. Passing
    /// the C# values 200 and 40000 in their carrier encodings (-56, -25536) proves the
    /// compiler reads back the C# value and not the carrier's.
    private void emitUnsignedSmallWidening() {
        String source = """
            public struct Unsigned {
                public static int Widen(byte small, ushort medium) {
                    return small + medium;
                }
                public static long Promote(byte small) {
                    return small;
                }
            }
            """;
        assertOutput("Unsigned", source, "Widen", 40200, (byte) -56, (short) -25536);
        assertOutput("Unsigned", source, "Promote", 200L, (byte) -56);
    }

    /// An `unchecked` narrowing cast truncates in C#; on the JVM that is `i2b`/`i2s`/`i2c`
    /// applied after the value reaches the int carrier.
    private void emitNarrowingCastTruncates() {
        String source = """
            public struct Narrow {
                public static sbyte ToSByte(int value) {
                    return (sbyte)value;
                }
                public static char ToChar(int value) {
                    return (char)value;
                }
                public static short FromLong(long value) {
                    return (short)value;
                }
            }
            """;
        assertOutput("Narrow", source, "ToSByte", (byte) -56, 200);
        assertOutput("Narrow", source, "ToChar", 'A', 65601);
        assertOutput("Narrow", source, "FromLong", (short) -25536, 40000L);
    }

    private void emitUnaryLogicalNot() {
        String source = """
            public struct Logic {
                public static bool Not(bool value) {
                    return !value;
                }
            }
            """;
        assertOutput("Logic", source, "Not", false, true);
        assertOutput("Logic", source, "Not", true, false);
    }

    private void emitUnaryBitwiseNot() {
        String source = """
            public struct Math {
                public static int Complement(int n) {
                    return ~n;
                }
            }
            """;
        assertOutput("Math", source, "Complement", -1, 0);
        assertOutput("Math", source, "Complement", -6, 5);
    }

    private void emitSwitchWithSharedCasesAndDefault() {
        // Three constant labels share one section, a fourth has its own, and default covers
        // everything else - exercises the case-value-to-section dispatch map directly.
        String source = """
            public struct Classifier {
                public static int Classify(int n) {
                    switch (n) {
                        case 1:
                        case 2:
                        case 3:
                            return 100;
                        case 4:
                            return 200;
                        default:
                            return 999;
                    }
                }
            }
            """;
        assertOutput("Classifier", source, "Classify", 100, 1);
        assertOutput("Classifier", source, "Classify", 100, 2);
        assertOutput("Classifier", source, "Classify", 100, 3);
        assertOutput("Classifier", source, "Classify", 200, 4);
        assertOutput("Classifier", source, "Classify", 999, 5);
    }

    private void emitSwitchGoto() {
        // Exercises both `goto case` forms distinctly enough that a wrong jump target would
        // change the result: a forward `goto case` from case 1, a direct match on case 2 and
        // case 3, and a `goto case` from `default` landing on case 2.
        String source = """
            public struct Router {
                public static int Route(int n) {
                    switch (n) {
                        case 1:
                            goto case 3;
                        case 2:
                            return 20;
                        case 3:
                            return 30;
                        default:
                            goto case 2;
                    }
                }
            }
            """;
        assertOutput("Router", source, "Route", 30, 1);
        assertOutput("Router", source, "Route", 20, 2);
        assertOutput("Router", source, "Route", 30, 3);
        assertOutput("Router", source, "Route", 20, 99);
    }


    private void emitSimpleMethod() {
        String source = """
            public struct TestClass {
                public static int ReturnsFive() {
                    return 5;
                }
            }
            """;
        assertOutput("TestClass", source, "ReturnsFive", 5);
    }

    private void emitFactorialWhile() {
        String source = """
            public struct Math {
                public static int Factorial(int n) {
                    int result = 1;
                    while (n > 1) {
                        result = result * n;
                        n = n - 1;
                    }
                    return result;
                }
            }
            """;
        assertOutput("Math", source, "Factorial", 120, 5);
    }

    private void emitBranchAndMath() {
        String source = """
            public struct Math {
                public static int Abs(int n) {
                    if (n < 0) {
                        return 0 - n;
                    }
                    return n;
                }
            }
            """;
        assertOutput("Math", source, "Abs", 5, -5);
    }

    private void assertOutput(String className, String source, String methodName, Object expectedResult, Object... args) {
        SourceFile file = TestSources.styled(className + ".vs", source);
        Compilation compilation = Compilation.of(java.util.List.of(file));

        CompilationResult result = compilation.emit();
        if (result.hasErrors()) {
            for (var d : result.diagnostics()) {
                System.out.println(d.message());
            }
        }
        Assert.isFalse(result.hasErrors(), "compilation should succeed");


        java.util.Map<String, byte[]> classes = new java.util.LinkedHashMap<>();
        for (UnitAnalysis unit : result.units()) {
            if (unit instanceof UnitAnalysis.Emitted emitted && emitted.file().name().equals(className + ".vs")) {
                classes.putAll(emitted.classes());
            }
        }

        Assert.isTrue(!classes.isEmpty(), "bytecode should be generated");

        try {
            java.util.Map<String, byte[]> finalClasses = classes;
            ClassLoader loader = new ClassLoader() {
                @Override
                protected Class<?> findClass(String name) throws ClassNotFoundException {
                    if (finalClasses.containsKey(name)) {
                        byte[] classBytes = finalClasses.get(name); return defineClass(name, classBytes, 0, classBytes.length);
                    }
                    return super.findClass(name);
                }
            };

            Class<?> clazz = loader.loadClass(className);
            Method[] methods = clazz.getMethods();
            Method method = null;
            for (Method m : methods) {
                if (m.getName().equals(methodName)) method = m;
            }
            Assert.isTrue(method != null, "method should be found");

            Assert.equal(expectedResult, method.invoke(null, args), "execution should return expected result");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void emitArrayCreationInitializer() {
        // Before this was fixed, ArrayCreation's own `initializer` field was silently
        // ignored by CodeEmitter - the array was allocated at the right length but every
        // element stayed at its JVM default (0), the values from `{ 10, 20, 30 }` never
        // stored. foreach-summing proves each initializer value actually landed.
        String source = """
            public struct ArrayInit {
                public static int SumInit() {
                    int[] arr = new int[] { 10, 20, 30 };
                    int sum = 0;
                    foreach (int item in arr) {
                        sum = sum + item;
                    }
                    return sum;
                }
            }
            """;
        assertOutput("ArrayInit", source, "SumInit", 60);
    }

    private void emitCollectionExpression() {
        String source = """
            public struct CollectionInit {
                public static int SumCollection() {
                    int[] arr = [1, 2, 3, 4];
                    int sum = 0;
                    foreach (int item in arr) {
                        sum = sum + item;
                    }
                    return sum;
                }
            }
            """;
        assertOutput("CollectionInit", source, "SumCollection", 10);
    }

    private void emitCollectionExpressionWithSpreads() {
        String source = """
            public struct CollectionSpreads {
                public static int SumSpreads() {
                    int[] arr1 = [2, 3];
                    int[] arr2 = [6];
                    int[] arr = [1, ..arr1, 4, 5, ..arr2, 7];
                    int sum = 0;
                    foreach (int item in arr) {
                        sum = sum + item;
                    }
                    return sum;
                }
            }
            """;
        assertOutput("CollectionSpreads", source, "SumSpreads", 28);
    }

    private void emitSwitchExpressionArms() {
        // Exercises Type (int n), Type (string s), Constant (null) and Discard (_) arms
        // in one switch expression, reusing emitPatternTest exactly as `is` does.
        String source = """
            public struct SwitchExprTest {
                public static int Classify(object o) {
                    return o switch {
                        int n => n * 10,
                        string s => -1,
                        null => -2,
                        _ => -3,
                    };
                }
            }
            """;
        assertOutput("SwitchExprTest", source, "Classify", 420, (Object) 42);
        assertOutput("SwitchExprTest", source, "Classify", -1, (Object) "hello");
        assertOutput("SwitchExprTest", source, "Classify", -2, (Object) null);
        assertOutput("SwitchExprTest", source, "Classify", -3, (Object) 3.5);
    }

    private void emitIntSwitchExpressionConstantAndDiscardArms() {
        String source = """
            public struct IntSwitchExpr {
                public static int Classify(int value) {
                    return value switch {
                        1 => 10,
                        2 => 20,
                        _ => -1,
                    };
                }
            }
            """;
        assertOutput("IntSwitchExpr", source, "Classify", 10, 1);
        assertOutput("IntSwitchExpr", source, "Classify", 20, 2);
        assertOutput("IntSwitchExpr", source, "Classify", -1, 5);
    }

    private void emitArrays() {
        String source = """
            public struct TestClass {
                public static int SumArray(int size) {
                    int[] arr = new int[size];
                    for (int i = 0; i < arr.Length; i = i + 1) {
                        arr[i] = i * 2;
                    }

                    int sum = 0;
                    foreach (int item in arr) {
                        sum = sum + item;
                    }
                    return sum;
                }
            }
            """;

        SourceFile file = TestSources.styled("TestClass.vs", source);
        Compilation compilation = Compilation.of(java.util.List.of(file));

        CompilationResult result = compilation.emit();
        if (result.hasErrors()) {
            for (var d : result.diagnostics()) {
                System.out.println(d.message());
            }
        }
        Assert.isFalse(result.hasErrors(), "compilation should succeed");


        java.util.Map<String, byte[]> classes = new java.util.LinkedHashMap<>();
        for (UnitAnalysis unit : result.units()) {
            if (unit.file().name().equals("TestClass.vs") && unit instanceof UnitAnalysis.Emitted emitted) {
                classes.putAll(emitted.classes());
            }
        }

        Assert.isTrue(!classes.isEmpty(), "bytecode should be generated");

        try {
            java.util.Map<String, byte[]> finalClasses = classes;
            ClassLoader loader = new ClassLoader() {
                @Override
                protected Class<?> findClass(String name) throws ClassNotFoundException {
                    if (finalClasses.containsKey(name)) {
                        byte[] classBytes = finalClasses.get(name); return defineClass(name, classBytes, 0, classBytes.length);
                    }
                    return super.findClass(name);
                }
            };

            Class<?> clazz = loader.loadClass("TestClass");
            Method method = clazz.getMethod("SumArray", int.class);

            // For size 5: arr = {0, 2, 4, 6, 8}, sum = 20
            Assert.equal(20, method.invoke(null, 5), "SumArray(5) should return 20");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


    /// `int[][]` is one type with one spelling: the binder used to build
    /// `Array(Array(int, [1]), [1])` for the declared form and `Array(int, [1, 1])` for
    /// `new int[2][]`, so assigning one to the other was rejected as a conversion between
    /// different types. The representation is canonical now, and this walks a jagged array
    /// through creation, element assignment, reading, `Length` and `foreach`.
    private void emitJaggedArrays() {
        String source = """
            public struct TestClass {
                public static int Sum(int rows) {
                    int[][] grid = new int[rows][];
                    for (int i = 0; i < grid.Length; i = i + 1) {
                        grid[i] = new int[2];
                        grid[i][0] = i;
                        grid[i][1] = i * 10;
                    }

                    int total = 0;
                    foreach (int[] row in grid) {
                        foreach (int value in row) {
                            total = total + value;
                        }
                    }
                    return total;
                }
            }
            """;
        assertOutput("TestClass", source, "Sum", 33, 3);
    }

    /// The element type of a jagged array is the array one indexing step removes, so a
    /// three-level `int[][][]` yields `int[][]` and only the innermost step yields `int`.
    private void emitJaggedArrayOfJaggedArrays() {
        String source = """
            public struct TestClass {
                public static int Deep(int value) {
                    int[][][] cube = new int[1][][];
                    cube[0] = new int[1][];
                    cube[0][0] = new int[1];
                    cube[0][0][0] = value;
                    return cube[0][0][0] + cube.Length + cube[0].Length;
                }
            }
            """;
        assertOutput("TestClass", source, "Deep", 9, 7);
    }

    /// A jagged array of a reference element type carries `[[Ljava/lang/String;`, so the
    /// element load has to produce a `String[]` reference rather than a flattened element.
    private void emitJaggedReferenceArrays() {
        String source = """
            public struct TestClass {
                public static string Pick(string value) {
                    string[][] names = new string[2][];
                    names[1] = new string[1];
                    names[1][0] = value;
                    return names[1][0];
                }
            }
            """;
        assertOutput("TestClass", source, "Pick", "ok", "ok");
    }

    /// A local function declared among top-level statements is owned by the synthesised
    /// `<top-level>` callable, whose name is neither a legal JVM method name nor a class
    /// name: emitting it verbatim produced `main` calling into a class nobody wrote. It
    /// belongs to the file holder, as `main$Local`.
    /// A rectangular `int[,]` is two dimensions of one array, not two arrays. The
    /// backend used to allocate only the first dimension and index with only the first
    /// subscript, so `m[0, 0]` and `m[0, 5]` were the same element and `Length` under-reported
    /// - a program that compiled, verified and computed the wrong answer.
    private void emitRectangularArrays() {
        String source = """
            public struct TestClass {
                public static int Grid(int seed) {
                    int[,] cells = new int[2, 3];
                    cells[0, 0] = seed;
                    cells[1, 2] = seed * 2;
                    return cells[0, 0] + cells[1, 2] + cells.Length + cells[1, 0];
                }
            }
            """;
        // 5 + 10 + 6 (2 * 3 elements) + 0 (never written, and not aliased onto a written one).
        assertOutput("TestClass", source, "Grid", 21, 5);
    }

    /// `foreach` over a rectangular array iterates every element in row-major order. The
    /// emitter builds one loop per JVM array level instead of the single `arraylength`/load
    /// pair a vector needs, which previously produced unverifiable bytecode (`iaload` on a
    /// `[[I`) for any rank above one.
    private void emitRectangularForeach() {
        String source = """
            public struct RectForeach {
                public static int Sum2D(int seed) {
                    int[,] cells = new int[2, 3];
                    cells[0, 0] = seed;
                    cells[0, 2] = seed * 2;
                    cells[1, 1] = seed * 3;
                    int total = 0;
                    foreach (int v in cells) {
                        total = total + v;
                    }
                    return total + cells.Length;
                }
                public static int Sum3D(int seed) {
                    int[,,] cube = new int[2, 2, 2];
                    cube[0, 0, 0] = seed;
                    cube[1, 1, 1] = seed + 1;
                    int total = 0;
                    foreach (int v in cube) {
                        total = total + v;
                    }
                    return total + cube.Length;
                }
            }
            """;
        // 2D: (5 + 10 + 15) + 6 elements.
        assertOutput("RectForeach", source, "Sum2D", 36, 5);
        // 3D: (5 + 6) + 8 elements.
        assertOutput("RectForeach", source, "Sum3D", 19, 5);
    }

    /// A `params` call in expanded form passes one array (C# §12.6.2.4). Overload resolution
    /// accepted these calls already; the backend refused them, so the only way to call such a
    /// method was to build the array by hand. Binding now builds it, in the collection form
    /// the emitter already knows.
    private void emitExpandedParamsCall() {
        String source = """
            public struct TestClass {
                static int Sum(params int[] values) {
                    int total = 0;
                    foreach (int value in values) {
                        total = total + value;
                    }
                    return total;
                }

                static int Offset(int start, params int[] values) {
                    return start + Sum(values);
                }

                public static int All(int seed) {
                    return Sum(1, 2, 3) + Sum() + Sum(new int[] { 4, 5 }) + Offset(seed, 6, 7);
                }
            }
            """;
        // 6 + 0 + 9 + (10 + 13).
        assertOutput("TestClass", source, "All", 38, 10);
    }

    private void emitTopLevelLocalFunction() {
        String source = """
            int Twice(int value) {
                return value * 2;
            }

            int doubled = Twice(21);
            """;

        Class<?> program = compileAndLoad("TopLevelLocals", source);
        try {
            Method local = program.getMethod("main$Twice", int.class);
            Assert.equal(42, local.invoke(null, 21), "main$Twice(21)");

            // Running `main` is the assertion that the call site targets the holder the
            // method was emitted into: a wrong owner fails to resolve at execution.
            program.getMethod("main", String[].class).invoke(null, (Object) new String[0]);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("top-level local function is not callable", failure);
        }
    }

    /// C# launches `static Main`; the JVM launches `main`. Without the bridge, a program
    /// written the way every C# program is written produces a class the `java` launcher
    /// refuses to run, so the whole `--jar`/`--out` output path is unusable from a shell.
    private void emitEntryPointBridge() {
        String source = """
            static class Program {
                public static int Seen;

                static void Main() {
                    Seen = 7;
                }
            }
            """;

        Class<?> program = compileAndLoad("Program", source);
        try {
            Method main = program.getMethod("main", String[].class);
            Assert.equal(void.class, main.getReturnType(), "bridge returns void");
            Assert.isTrue(Modifier.isStatic(main.getModifiers()), "bridge is static");
            Assert.isTrue(Modifier.isPublic(main.getModifiers()), "bridge is public");

            main.invoke(null, (Object) new String[0]);
            Assert.equal(7, program.getField("Seen").get(null), "Main ran through the bridge");
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("no launchable main was emitted for Main()", failure);
        }
    }

    /// The `string[]`-taking, `int`-returning form. It is never invoked here: its lowering
    /// ends in `System.exit`, which would take this test JVM with it. The shape - forwarding
    /// argument, and both methods present - is what the launcher needs.
    private void emitEntryPointBridgeForMainWithArgs() {
        String source = """
            static class Args {
                static int Main(string[] args) {
                    return args.Length;
                }
            }
            """;

        Class<?> program = compileAndLoad("Args", source);
        try {
            Method main = program.getMethod("main", String[].class);
            Assert.equal(void.class, main.getReturnType(), "bridge returns void");
            Method declared = program.getMethod("Main", String[].class);
            Assert.equal(int.class, declared.getReturnType(), "Main keeps its int result");
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("no launchable main was emitted for Main(string[])", failure);
        }
    }

    /// A `Main` that does not match an entry-point signature is an ordinary method: adding a
    /// launcher for it would call it with arguments it cannot accept.
    private void emitNoBridgeForNonEntryPointMain() {
        String source = """
            static class Other {
                static string Main(int value) {
                    return "no";
                }
            }
            """;

        Class<?> program = compileAndLoad("Other", source);
        for (Method method : program.getDeclaredMethods()) {
            Assert.isFalse("main".equals(method.getName()), "no bridge for string Main(int)");
        }
    }

    /// `value.ToString()` on a keyword type. The receiver is a primitive with no class
    /// of its own, so the call becomes a static formatter taking it - the same one
    /// `Console.Write` uses, which is why `uint` reads unsigned and `bool` reads `True`.
    private void emitBuiltinToString() {
        String source = """
            public struct TestClass {
                public static string Render(int value) {
                    uint big = 4294967295;
                    bool flag = true;
                    char letter = 'x';
                    double half = 1.5;
                    long wide = 9223372036854775807;
                    return value.ToString() + "|" + big.ToString() + "|" + flag.ToString()
                            + "|" + letter.ToString() + "|" + half.ToString()
                            + "|" + wide.ToString();
                }
            }
            """;
        assertOutput("TestClass", source, "Render", "42|4294967295|True|x|1.5|9223372036854775807",
                42);
    }

    /// `int.MinValue` and friends are constants, so they fold: the emitted method loads one
    /// value and returns, with no reference to a corelib class the backend never generates.
    private void emitBuiltinLimits() {
        String source = """
            public struct TestClass {
                public static string Limits(int ignored) {
                    return int.MaxValue.ToString() + "|" + sbyte.MinValue.ToString()
                            + "|" + uint.MaxValue.ToString() + "|" + ushort.MaxValue.ToString()
                            + "|" + ulong.MaxValue.ToString();
                }
            }
            """;
        assertOutput("TestClass", source,
                "Limits", "2147483647|-128|4294967295|65535|18446744073709551615", 0);
    }

    /// A constant is a constant everywhere: the pair folds in ordinary arithmetic too, which
    /// is what makes `int.MaxValue - 1` usable as an array size or a `case` label.
    /// The vertical `decimal` proof: literals keep their scale, arithmetic and comparisons
    /// run through `VsDecimal` (the same policy constant folding uses), explicit numeric
    /// conversions work in both directions, `default(decimal)` is numeric zero rather than
    /// the JVM `null`, and concatenation/interpolation render fixed-point text.
    private void emitDecimalArithmeticAndDisplay() {
        String source = """
            public struct Dec {
                public static string Run() {
                    decimal a = 1.50m;
                    decimal b = 0.25m;
                    return (a + b) + "|" + (a - b) + "|" + (a * b) + "|" + (a / b)
                        + "|" + (a % b) + "|" + (a < b) + "|" + (a >= b) + "|" + (-a)
                        + "|" + $"x={a}" + "|" + default(decimal)
                        + "|" + (decimal)3 + "|" + (int)3.99m;
                }
                public static decimal Folded() {
                    return 1.50m + 0.25m;
                }
                public static string Tiny() {
                    return "v=" + 1e-28m;
                }
            }
            """;
        assertOutput("Dec", source, "Run",
                "1.75|1.25|0.3750|6|0.00|False|True|-1.50|x=1.50|0|3|3");
        assertOutput("Dec", source, "Folded", new java.math.BigDecimal("1.75"));
        assertOutput("Dec", source, "Tiny", "v=0.0000000000000000000000000001");
    }

    /// A decimal field, parameter and return value must all use the same `BigDecimal`
    /// descriptor: a field declared as `Object` while accesses treat it as `BigDecimal`
    /// would pass the verifier and fail later with `NoSuchFieldError` - the descriptor-mismatch
    /// defect family. Reflection over the loaded class is the direct assertion.
    private void emitDecimalCarrierDescriptors() {
        String source = """
            public struct DecCarrier {
                public static decimal Value;
                public static decimal Identity(decimal x) {
                    DecCarrier.Value = x;
                    return DecCarrier.Value;
                }
            }
            """;
        try {
            Class<?> clazz = compileAndLoad("DecCarrier", source);
            Assert.equal(java.math.BigDecimal.class, clazz.getField("Value").getType(),
                    "decimal field is declared as BigDecimal");
            Method method = clazz.getMethod("Identity", java.math.BigDecimal.class);
            Assert.equal(java.math.BigDecimal.class, method.getReturnType(),
                    "decimal return is declared as BigDecimal");
            java.math.BigDecimal result = (java.math.BigDecimal) method.invoke(null,
                    new java.math.BigDecimal("12.34"));
            Assert.equal(new java.math.BigDecimal("12.34"), result,
                    "decimal value round-trips through the BigDecimal carrier");
            Assert.equal(new java.math.BigDecimal("12.34"),
                    clazz.getField("Value").get(null),
                    "the decimal field holds the written BigDecimal value");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /// Decimal rides the same null-or-boxed `Object` nullable carrier as the other value
    /// types, so lifted operators, `??` and `HasValue`/`Value` must work with a
    /// `BigDecimal` underlying value; boxing `decimal` to `object` is `VsDecimal.box`.
    private void emitNullableDecimalCarrier() {
        String source = """
            public struct DecNull {
                public static decimal? Lift(decimal? a, decimal? b) {
                    return a + b;
                }
                public static decimal Coalesce(decimal? a) {
                    return a ?? 3.5m;
                }
                public static bool Has(decimal? a) {
                    return a.HasValue;
                }
                public static object Box(decimal a) {
                    object o = a;
                    return o;
                }
            }
            """;
        assertOutput("DecNull", source, "Lift", new java.math.BigDecimal("2.50"),
                new java.math.BigDecimal("1.25"), new java.math.BigDecimal("1.25"));
        assertOutput("DecNull", source, "Lift", null,
                null, new java.math.BigDecimal("1.25"));
        assertOutput("DecNull", source, "Coalesce", new java.math.BigDecimal("3.5"),
                (Object) null);
        assertOutput("DecNull", source, "Has", true, new java.math.BigDecimal("1.25"));
        assertOutput("DecNull", source, "Has", false, (Object) null);
        assertOutput("DecNull", source, "Box", new java.math.BigDecimal("2.50"),
                new java.math.BigDecimal("2.50"));
    }

    /// `(T)o` for every value-type carrier the backend can unbox: standard wrappers,
    /// the private `VsUnsigned` boxes, `VsDecimal`, and a nullable target that turns a null
    /// `object` into the no-value state instead of throwing.
    private void emitExplicitObjectUnboxing() {
        String source = """
            public struct Unbox {
                public static int AsInt(object o) { return (int)o; }
                public static string AsUInt(object o) { return ((uint)o).ToString(); }
                public static int AsByte(object o) { return (byte)o + 0; }
                public static int AsUShort(object o) { return (ushort)o + 0; }
                public static string AsULong(object o) { return ((ulong)o).ToString(); }
                public static string AsNUInt(object o) { return "" + (nuint)o; }
                public static string AsDecimal(object o) { return "" + (decimal)o; }
                public static double AsDouble(object o) { return (double)o; }
                public static bool AsBool(object o) { return (bool)o; }
                public static int? AsNullable(object o) { return (int?)o; }
            }
            """;
        assertOutput("Unbox", source, "AsInt", 42, 42);
        assertOutput("Unbox", source, "AsUInt", "4294967295",
                vsharp.runtime.VsUnsigned.box(-1));
        assertOutput("Unbox", source, "AsByte", 200,
                vsharp.runtime.VsUnsigned.boxByte((byte) 200));
        assertOutput("Unbox", source, "AsUShort", 40000,
                vsharp.runtime.VsUnsigned.boxUShort((short) 40000));
        assertOutput("Unbox", source, "AsULong", "18446744073709551615",
                vsharp.runtime.VsUnsigned.box(-1L));
        assertOutput("Unbox", source, "AsNUInt", "18446744073709551615",
                vsharp.runtime.VsUnsigned.box(-1L));
        assertOutput("Unbox", source, "AsDecimal", "2.50",
                vsharp.runtime.VsDecimal.box(new java.math.BigDecimal("2.50")));
        assertOutput("Unbox", source, "AsDouble", 1.5, 1.5);
        assertOutput("Unbox", source, "AsBool", true, true);
        assertOutput("Unbox", source, "AsNullable", 7, 7);
        assertOutput("Unbox", source, "AsNullable", null, (Object) null);
    }

    /// Checkpoint 100's unboxing probe exposed four gaps in the the design `ToString()` claim:
    /// `byte`/`ushort` rendered their signed carrier and `nuint`/`decimal` had no member at
    /// all. The four now render their C# value through the same formatter family as `int`.
    private void emitBuiltinToStringUnsignedSmallAndDecimal() {
        String source = """
            public struct ToStringFix {
                public static string Render() {
                    byte b = 200;
                    ushort u = 40000;
                    nuint n = nuint.MaxValue;
                    decimal d = 2.50m;
                    return b.ToString() + "|" + u.ToString() + "|" + n.ToString()
                            + "|" + d.ToString();
                }
            }
            """;
        assertOutput("ToStringFix", source, "Render", "200|40000|18446744073709551615|2.50");
    }

    /// `System.Math.Abs` is the first curated non-object-model BCL member family beyond the
    /// console/formatting surface. The signed integral minima must throw `OverflowException`
    /// (Java's `Math.abs` silently wraps), and the float/double/decimal forms must match C#.
    private void emitSystemMathAbs() {
        String source = """
            using System;
            public struct MathAbs {
                public static string Render() {
                    sbyte sb = -100;
                    short sh = -30000;
                    int i = -42;
                    long l = -42L;
                    nint n = -7;
                    float f = -1.5f;
                    double d = -2.5;
                    decimal m = -3.75m;
                    return Math.Abs(sb) + "|" + Math.Abs(sh) + "|"
                            + Math.Abs(i) + "|" + Math.Abs(l) + "|"
                            + Math.Abs(n) + "|" + Math.Abs(f) + "|"
                            + Math.Abs(d) + "|" + Math.Abs(m);
                }

                public static int Throws() {
                    int min = int.MinValue;
                    long lmin = long.MinValue;
                    sbyte sbmin = sbyte.MinValue;
                    short shmin = short.MinValue;
                    nint nmin = nint.MinValue;
                    int count = 0;
                    try { Math.Abs(min); } catch (OverflowException) { count++; }
                    try { Math.Abs(lmin); } catch (OverflowException) { count++; }
                    try { Math.Abs(sbmin); } catch (OverflowException) { count++; }
                    try { Math.Abs(shmin); } catch (OverflowException) { count++; }
                    try { Math.Abs(nmin); } catch (OverflowException) { count++; }
                    return count;
                }
            }
            """;
        assertOutput("MathAbs", source, "Render", "100|30000|42|42|7|1.5|2.5|3.75");
        assertOutput("MathAbs", source, "Throws", 5);
    }

    /// The invariant string-only `System.Int32.Parse`/`TryParse` family. The source
    /// executes both keyword and corelib aliases, all three failure categories, the exact
    /// FormatException/ArgumentException sibling behavior, and `out` copy-back to local,
    /// static-field and array-element places through the existing cell ABI.
    private void emitSystemInt32Parsing() {
        String source = """
            using System;

            public struct IntParsing {
                static int Stored = 9;
                static int IndexCalls = 0;

                static int NextIndex() {
                    IndexCalls++;
                    return 1;
                }

                public static string Values() {
                    return int.Parse("0")
                        + "|" + Int32.Parse("-2147483648")
                        + "|" + Int32.Parse(" 2147483647 ")
                        + "|" + int.Parse("42\\0");
                }

                public static string Try(string text) {
                    int value = 123456789;
                    bool success = int.TryParse(text, out value);
                    return success + "|" + value;
                }

                public static string TryDeclared(string text) {
                    bool success = int.TryParse(text, out int value);
                    return success + "|" + value;
                }

                public static string TryInferred(string text) {
                    bool success = int.TryParse(text, out var value);
                    return success + "|" + value;
                }

                public static int OutPlaces() {
                    int[] values = new int[] { 4, 5 };
                    bool field = Int32.TryParse("7", out Stored);
                    bool element = Int32.TryParse("8", out values[NextIndex()]);
                    bool failure = Int32.TryParse("bad", out values[0]);
                    return Stored * 1000 + values[0] * 100 + values[1] * 10 + IndexCalls;
                }

                public static int Exceptions() {
                    int count = 0;
                    try { int.Parse("bad"); }
                    catch (ArgumentException) { count = count + 100; }
                    catch (FormatException) { count = count + 1; }
                    try { int.Parse("2147483648"); }
                    catch (OverflowException) { count = count + 2; }
                    try { int.Parse(null); }
                    catch (ArgumentException) { count = count + 4; }
                    try { int.Parse("still bad"); }
                    catch (Exception) { count = count + 8; }
                    try { throw new FormatException("made"); }
                    catch (FormatException) { count = count + 16; }
                    return count;
                }
            }
            """;
        assertOutput("IntParsing", source, "Values", "0|-2147483648|2147483647|42");
        assertOutput("IntParsing", source, "Try", "True|-42", "-42");
        assertOutput("IntParsing", source, "Try", "False|0", "bad");
        assertOutput("IntParsing", source, "Try", "False|0", "2147483648");
        assertOutput("IntParsing", source, "Try", "False|0", (Object) null);
        assertOutput("IntParsing", source, "TryDeclared", "True|42", "42");
        assertOutput("IntParsing", source, "TryDeclared", "False|0", "bad");
        assertOutput("IntParsing", source, "TryInferred", "True|42", "42");
        assertOutput("IntParsing", source, "TryInferred", "False|0", "bad");
        assertOutput("IntParsing", source, "OutPlaces", 7081);
        assertOutput("IntParsing", source, "Exceptions", 31);
    }

    /// The invariant string-only `System.Double.Parse`/`TryParse` family, including
    /// permissive pre-decimal grouping, special values, modern overflow/underflow behavior,
    /// exception hierarchy and all representative out-cell place shapes.
    private void emitSystemDoubleParsing() {
        String source = """
            using System;

            public struct DoubleParsing {
                static double Stored = 9.0;
                static int IndexCalls = 0;

                static int NextIndex() {
                    IndexCalls++;
                    return 1;
                }

                public static string Values() {
                    return double.Parse("1,234.5")
                        + "|" + Double.Parse("1e-2")
                        + "|" + Double.Parse(".5");
                }

                public static string Special() {
                    return double.Parse("nAn")
                        + "|" + double.Parse("1e309")
                        + "|" + double.Parse("-Infinity");
                }

                public static bool NegativeUnderflow() {
                    double value = double.Parse("-1e-4000");
                    return 1.0 / value == double.NegativeInfinity;
                }

                public static string Try(string text) {
                    double value = 123.0;
                    bool success = double.TryParse(text, out value);
                    return success + "|" + value;
                }

                public static string TryDeclared(string text) {
                    bool success = double.TryParse(text, out double value);
                    return success + "|" + value;
                }

                public static string TryInferred(string text) {
                    bool success = double.TryParse(text, out var value);
                    return success + "|" + value;
                }

                public static double OutPlaces() {
                    double[] values = new double[] { 4.0, 5.0 };
                    bool field = Double.TryParse("7.5", out Stored);
                    bool element = Double.TryParse("8.5", out values[NextIndex()]);
                    bool failure = Double.TryParse("bad", out values[0]);
                    return Stored + values[0] + values[1] + IndexCalls;
                }

                public static int Exceptions() {
                    int count = 0;
                    try { double.Parse("bad"); }
                    catch (ArgumentException) { count = count + 100; }
                    catch (FormatException) { count = count + 1; }
                    try { double.Parse(null); }
                    catch (ArgumentException) { count = count + 2; }
                    try { double.Parse("still bad"); }
                    catch (Exception) { count = count + 4; }
                    return count;
                }
            }
            """;
        assertOutput("DoubleParsing", source, "Values", "1234.5|0.01|0.5");
        assertOutput("DoubleParsing", source, "Special", "NaN|Infinity|-Infinity");
        assertOutput("DoubleParsing", source, "NegativeUnderflow", true);
        assertOutput("DoubleParsing", source, "Try", "True|-42.5", "-42.5");
        assertOutput("DoubleParsing", source, "Try", "False|0", "bad");
        assertOutput("DoubleParsing", source, "Try", "True|Infinity", "1e309");
        assertOutput("DoubleParsing", source, "TryDeclared", "True|42.5", "42.5");
        assertOutput("DoubleParsing", source, "TryDeclared", "False|0", "bad");
        assertOutput("DoubleParsing", source, "TryInferred", "True|42.5", "42.5");
        assertOutput("DoubleParsing", source, "TryInferred", "False|0", "bad");
        assertOutput("DoubleParsing", source, "OutPlaces", 17.0d);
        assertOutput("DoubleParsing", source, "Exceptions", 7);
    }

    /// The complete culture-independent Boolean string parsing family, including
    /// both declaration forms and representative out-cell places.
    private void emitSystemBooleanParsing() {
        String source = """
            using System;

            public struct BooleanParsing {
                static bool Stored = false;
                static int IndexCalls = 0;

                static int NextIndex() { IndexCalls++; return 1; }

                public static string Values() {
                    return bool.Parse("TrUe") + "|"
                        + Boolean.Parse(" false ") + "|"
                        + Boolean.Parse("\0\tTRUE\u00A0");
                }

                public static string Try(string text) {
                    bool value = true;
                    bool success = bool.TryParse(text, out value);
                    return success + "|" + value;
                }

                public static string TryDeclared(string text) {
                    bool success = bool.TryParse(text, out bool value);
                    return success + "|" + value;
                }

                public static string TryInferred(string text) {
                    bool success = bool.TryParse(text, out var value);
                    return success + "|" + value;
                }

                public static int OutPlaces() {
                    bool[] values = new bool[] { true, false };
                    bool field = Boolean.TryParse("true", out Stored);
                    bool element = Boolean.TryParse("TRUE", out values[NextIndex()]);
                    bool failure = Boolean.TryParse("bad", out values[0]);
                    return (Stored ? 1000 : 0) + (values[0] ? 100 : 0)
                        + (values[1] ? 10 : 0) + IndexCalls;
                }

                public static int Exceptions() {
                    int count = 0;
                    try { bool.Parse("bad"); }
                    catch (ArgumentException) { count = count + 100; }
                    catch (FormatException) { count = count + 1; }
                    try { bool.Parse(null); }
                    catch (ArgumentException) { count = count + 2; }
                    try { bool.Parse("still bad"); }
                    catch (Exception) { count = count + 4; }
                    return count;
                }
            }
            """;
        assertOutput("BooleanParsing", source, "Values", "True|False|True");
        assertOutput("BooleanParsing", source, "Try", "True|True", "true");
        assertOutput("BooleanParsing", source, "Try", "True|False", "FALSE");
        assertOutput("BooleanParsing", source, "Try", "False|False", "bad");
        assertOutput("BooleanParsing", source, "TryDeclared", "True|True", "True");
        assertOutput("BooleanParsing", source, "TryDeclared", "False|False", "bad");
        assertOutput("BooleanParsing", source, "TryInferred", "True|False", "False");
        assertOutput("BooleanParsing", source, "TryInferred", "False|False", "bad");
        assertOutput("BooleanParsing", source, "OutPlaces", 1011);
        assertOutput("BooleanParsing", source, "Exceptions", 7);
    }

    /// The separator-array `System.String.Split` family: both the expanded `params`
    /// call shape and an explicit `char[]` argument, the whitespace fallback, and the
    /// `string[]` result flowing through indexing, `Length` and `foreach`.
    private void emitSystemStringSplit() {
        String source = """
            using System;

            public struct Splitting {
                public static string Expanded() {
                    string[] parts = "a,b;c".Split(',', ';');
                    return parts.Length + "|" + parts[0] + parts[1] + parts[2];
                }

                public static string Explicit() {
                    char[] separators = new char[] { '=' };
                    string[] parts = "key=value".Split(separators);
                    return parts[0] + "|" + parts[1];
                }

                public static string Empties() {
                    string[] parts = ",a,,b,".Split(',');
                    string result = parts.Length.ToString();
                    foreach (string part in parts) { result = result + "[" + part + "]"; }
                    return result;
                }

                public static string Fallback() {
                    string[] parts = "  a\\u00A0b ".Split();
                    string result = parts.Length.ToString();
                    foreach (string part in parts) { result = result + "[" + part + "]"; }
                    return result;
                }

                public static string NoSeparator() {
                    string[] parts = "abc".Split('x');
                    return parts.Length + "|" + parts[0];
                }

                public static int Failure() {
                    string missing = null;
                    try { missing.Split(','); }
                    catch (NullReferenceException) { return 1; }
                    return 0;
                }
            }
            """;
        assertOutput("Splitting", source, "Expanded", "3|abc");
        assertOutput("Splitting", source, "Explicit", "key|value");
        assertOutput("Splitting", source, "Empties", "5[][a][][b][]");
        assertOutput("Splitting", source, "Fallback", "5[][][a][b][]");
        assertOutput("Splitting", source, "NoSeparator", "1|abc");
        assertOutput("Splitting", source, "Failure", 1);
    }

    /// The ordinal shaping family: both padding overloads in both directions, the
    /// `char[]` result of `ToCharArray` flowing through `Length`, indexing and `foreach`,
    /// and the two distinct argument-failure shapes `Insert` and `Remove` produce.
    private void emitSystemStringShaping() {
        String source = """
            using System;

            public struct Shaping {
                public static string Padded() {
                    return "ab".PadLeft(5) + "|" + "ab".PadRight(5, '.') + "|"
                        + "ab".PadLeft(1) + "|" + "".PadRight(3, 'x');
                }

                public static string Units() {
                    char[] units = "hi!".ToCharArray();
                    string result = units.Length.ToString();
                    foreach (char unit in units) { result = result + "[" + unit + "]"; }
                    return result + units[0];
                }

                public static string Edited() {
                    return "ac".Insert(1, "b") + "|" + "abc".Remove(1) + "|" + "ababa".Remove(1, 3);
                }

                public static int Failures() {
                    int score = 0;
                    try { "ab".PadLeft(-1); } catch (ArgumentException) { score = score + 1; }
                    try { "ab".Insert(0, null); } catch (ArgumentException) { score = score + 10; }
                    try { "ab".Insert(-1, "x"); } catch (ArgumentException) { score = score + 100; }
                    try { "ab".Remove(-1); } catch (ArgumentException) { score = score + 1000; }
                    try { "ab".Remove(3, 0); } catch (ArgumentException) { score = score + 10000; }
                    return score;
                }

                public static int NullReceiver() {
                    string missing = null;
                    try { missing.PadLeft(2); }
                    catch (NullReferenceException) { return 1; }
                    return 0;
                }
            }
            """;
        assertOutput("Shaping", source, "Padded", "   ab|ab...|ab|xxx");
        assertOutput("Shaping", source, "Units", "3[h][i][!]h");
        assertOutput("Shaping", source, "Edited", "abc|a|aa");
        assertOutput("Shaping", source, "Failures", 11111);
        assertOutput("Shaping", source, "NullReceiver", 1);
    }

    /// The curated `System.Array` statics. The critical selections are the unsigned
    /// element types, which share their signed counterpart's JVM carrier and must still sort
    /// by unsigned value, and the floating sort, which must place NaN first.
    private void emitSystemArrayStatics() {
        String source = """
            using System;

            public struct Arrays {
                public static string Ints() {
                    int[] data = { 5, -3, 0 };
                    Array.Sort(data);
                    string sorted = data[0] + "," + data[1] + "," + data[2];
                    Array.Reverse(data);
                    return sorted + "|" + data[0] + "," + data[1] + "," + data[2]
                        + "|" + Array.IndexOf(data, 0) + "," + Array.IndexOf(data, 9);
                }

                public static string Unsigned() {
                    byte[] unsigned = { 200, 5, 255 };
                    Array.Sort(unsigned);
                    sbyte[] signed = { -56, 5, -1 };
                    Array.Sort(signed);
                    return unsigned[0] + "," + unsigned[2] + "|" + signed[0] + "," + signed[2];
                }

                public static string Floats() {
                    double[] data = { 1.0, 0.0 / 0.0, -1.0 };
                    Array.Sort(data);
                    return data[0] + "," + data[1] + "," + data[2]
                        + "|" + Array.IndexOf(data, 0.0 / 0.0);
                }

                public static string Words() {
                    string[] words = { "b", "a" };
                    Array.Reverse(words);
                    return words[0] + words[1] + "|" + Array.IndexOf(words, "b");
                }

                public static string Chars() {
                    // Deliberately not a palindrome: a no-op Reverse must fail this.
                    char[] letters = "stressed".ToCharArray();
                    Array.Reverse(letters);
                    string result = "";
                    foreach (char c in letters) { result = result + c; }
                    return result;
                }

                public static int NullArray() {
                    int[] missing = null;
                    try { Array.Sort(missing); }
                    catch (ArgumentException) { return 1; }
                    return 0;
                }
            }
            """;
        assertOutput("Arrays", source, "Ints", "-3,0,5|5,0,-3|1,-1");
        // 200 and 255 stay unsigned; the same carrier bits read as -56 and -1 for sbyte.
        assertOutput("Arrays", source, "Unsigned", "5,255|-56,5");
        assertOutput("Arrays", source, "Floats", "NaN,-1,1|0");
        assertOutput("Arrays", source, "Words", "ab|1");
        assertOutput("Arrays", source, "Chars", "desserts");
        assertOutput("Arrays", source, "NullArray", 1);
    }

    /// `ToString(string)` with the admitted invariant specifiers, including the
    /// unsigned rendering that shares a carrier with its signed sibling, and the refusals.
    private void emitNumberFormatSpecifiers() {
        String source = """
            using System;

            public struct Formatting {
                public static string Integers() {
                    int id = 42;
                    return id.ToString("D5") + "|" + (-42).ToString("D3") + "|"
                        + 48879.ToString("X") + "|" + 48879.ToString("x8");
                }

                public static string Unsigned() {
                    byte small = 255;
                    uint wide = 4294967295;
                    return small.ToString("D") + "|" + wide.ToString("D") + "|" + wide.ToString("X");
                }

                public static string Fixed() {
                    double ratio = 2.345;
                    decimal money = 19.99m;
                    return ratio.ToString("F2") + "|" + money.ToString("F1") + "|"
                        + (2.5).ToString("F0") + "|" + (2.5m).ToString("F0");
                }

                public static string Specials() {
                    double nan = 0.0 / 0.0;
                    return nan.ToString("F2") + "|" + (1.0 / 0.0).ToString("D");
                }

                public static int Refusals() {
                    int score = 0;
                    try { (1.0).ToString("D"); } catch (FormatException) { score = score + 1; }
                    try { 1.ToString("Q"); } catch (FormatException) { score = score + 10; }
                    try { 1.ToString("N2"); } catch (FormatException) { score = score + 100; }
                    try { 1.ToString("0.00"); } catch (FormatException) { score = score + 1000; }
                    return score;
                }
            }
            """;
        assertOutput("Formatting", source, "Integers", "00042|-042|BEEF|0000beef");
        assertOutput("Formatting", source, "Unsigned", "255|4294967295|FFFFFFFF");
        // 2.5 is an exact tie: binary rounds to even, decimal rounds away from zero.
        assertOutput("Formatting", source, "Fixed", "2.35|20.0|2|3");
        assertOutput("Formatting", source, "Specials", "NaN|Infinity");
        assertOutput("Formatting", source, "Refusals", 1111);
    }

    /// Interpolation format clauses, which reuse the rule's engine. The clause is applied
    /// before the alignment clause, and a clause on a type C# does not treat as
    /// `IFormattable` is ignored rather than refused, exactly as C# ignores it.
    private void emitInterpolationFormatClauses() {
        String source = """
            using System;

            public struct Interp {
                public static string Numbers() {
                    int n = 42;
                    double d = 2.345;
                    decimal m = 19.99m;
                    return $"{n:D5}|{n:X}|{d:F2}|{m:F1}";
                }

                public static string WithAlignment() {
                    double d = 2.345;
                    int n = 42;
                    // The format runs first, then the alignment pads the rendered text.
                    return $"[{d,10:F2}][{n,-8:D4}]";
                }

                public static string NotFormattable() {
                    string s = "abc";
                    bool b = true;
                    char c = 'x';
                    // C# ignores the clause for these, because none is IFormattable.
                    return $"{s:F2}|{b:F2}|{c:F2}";
                }

                public static string Unsigned() {
                    uint u = 4294967295;
                    return $"{u:D}|{u:X}";
                }

                public static string Specials() {
                    double nan = 0.0 / 0.0;
                    return $"{nan:F2}|{(1.0 / 0.0):F2}";
                }
            }
            """;
        assertOutput("Interp", source, "Numbers", "00042|2A|2.35|20.0");
        assertOutput("Interp", source, "WithAlignment", "[      2.35][0042    ]");
        assertOutput("Interp", source, "NotFormattable", "abc|True|x");
        assertOutput("Interp", source, "Unsigned", "4294967295|FFFFFFFF");
        assertOutput("Interp", source, "Specials", "NaN|Infinity");
    }

    /// `System.Math.Max`/`Min` over the complete C# overload set, so the result keeps C#'s
    /// type rather than widening to a carrier. Unsigned forms compare unsigned bits; the
    /// decimal equal-value operands keep the .NET operand choice; signed zero and NaN match
    /// the .NET 10 oracle.
    private void emitSystemMathMaxMin() {
        String source = """
            using System;
            public struct MaxMin {
                public static string Render() {
                    sbyte sb1 = -5; sbyte sb2 = 3;
                    byte b1 = 200; byte b2 = 30;
                    short sh1 = -30000; short sh2 = 7;
                    ushort us1 = 40000; ushort us2 = 7;
                    int i1 = -42; int i2 = 5;
                    uint ui1 = uint.MaxValue; uint ui2 = 5u;
                    long l1 = -42L; long l2 = 5L;
                    ulong ul1 = ulong.MaxValue; ulong ul2 = 5UL;
                    nint n1 = -7; nint n2 = 3;
                    nuint nu1 = nuint.MaxValue; nuint nu2 = 3;
                    float f1 = 1.5f; float f2 = 2.5f;
                    double d1 = 1.5; double d2 = 2.5;
                    decimal m1 = 1.0m; decimal m2 = 1.00m;
                    double z1 = 0.0; double z2 = -0.0;
                    float zf1 = 0.0f; float zf2 = -0.0f;
                    double nan1 = 0.0 / 0.0;
                    return Math.Max(sb1, sb2) + "|" + Math.Min(sb1, sb2) + "|"
                        + Math.Max(b1, b2) + "|" + Math.Min(b1, b2) + "|"
                        + Math.Max(sh1, sh2) + "|" + Math.Min(sh1, sh2) + "|"
                        + Math.Max(us1, us2) + "|" + Math.Min(us1, us2) + "|"
                        + Math.Max(i1, i2) + "|" + Math.Min(i1, i2) + "|"
                        + Math.Max(ui1, ui2) + "|" + Math.Min(ui1, ui2) + "|"
                        + Math.Max(l1, l2) + "|" + Math.Min(l1, l2) + "|"
                        + Math.Max(ul1, ul2) + "|" + Math.Min(ul1, ul2) + "|"
                        + Math.Max(n1, n2) + "|" + Math.Min(n1, n2) + "|"
                        + Math.Max(nu1, nu2) + "|" + Math.Min(nu1, nu2) + "|"
                        + Math.Max(f1, f2) + "|" + Math.Min(f1, f2) + "|"
                        + Math.Max(d1, d2) + "|" + Math.Min(d1, d2) + "|"
                        + Math.Max(m1, m2) + "|" + Math.Min(m1, m2) + "|"
                        + Math.Max(z1, z2) + "|" + Math.Min(z1, z2) + "|"
                        + Math.Max(zf1, zf2) + "|" + Math.Min(zf1, zf2) + "|"
                        + Math.Max(nan1, 1.0) + "|" + Math.Min(nan1, 1.0);
                }
            }
            """;
        assertOutput("MaxMin", source, "Render",
                "3|-5|200|30|7|-30000|40000|7|5|-42|4294967295|5|5|-42|"
                        + "18446744073709551615|5|3|-7|18446744073709551615|3|"
                        + "2.5|1.5|2.5|1.5|1.0|1.00|0|-0|0|-0|NaN|NaN");
    }

    /// The .NET 10 oracle exposes 13 Clamp overloads. Every overload returns an input
    /// The eight `System.Math.Round` overloads, over both carriers and every midpoint mode.
    ///
    /// The `double` forms round to even by default and validate `digits` before `mode`; the
    /// `decimal` forms keep the value's stored scale, so the rendered text carries it. The
    /// expected string is the .NET 10 output byte-for-byte, and the wider 4,025-record
    /// oracle behind it is.
    private void emitSystemMathRound() {
        String source = """
            using System;

            public struct MathRound {
                public static string Render() {
                    return Math.Round(2.5)
                        + "|" + Math.Round(3.5)
                        + "|" + Math.Round(-2.5)
                        + "|" + Math.Round(0.5)
                        + "|" + Math.Round(2.5, MidpointRounding.AwayFromZero)
                        + "|" + Math.Round(2.5, MidpointRounding.ToZero)
                        + "|" + Math.Round(2.5, MidpointRounding.ToNegativeInfinity)
                        + "|" + Math.Round(2.5, MidpointRounding.ToPositiveInfinity)
                        + "|" + Math.Round(-2.5, MidpointRounding.ToZero)
                        + "|" + Math.Round(-2.5, MidpointRounding.ToNegativeInfinity)
                        + "|" + Math.Round(3.14159265358979, 3)
                        + "|" + Math.Round(3.14159265358979, 0)
                        + "|" + Math.Round(1.005, 2)
                        + "|" + Math.Round(2.675, 2, MidpointRounding.AwayFromZero)
                        + "|" + Math.Round(-1.5, 0, MidpointRounding.AwayFromZero)
                        + "|" + Math.Round(1e16, 15)
                        + "|" + Math.Round(double.NaN)
                        + "|" + Math.Round(double.PositiveInfinity, 3)
                        + "|" + Math.Round(2.5m)
                        + "|" + Math.Round(3.5m)
                        + "|" + Math.Round(-2.5m, MidpointRounding.AwayFromZero)
                        + "|" + Math.Round(1.005m, 2)
                        + "|" + Math.Round(2.675m, 2, MidpointRounding.AwayFromZero)
                        + "|" + Math.Round(1.00m, 3)
                        + "|" + Math.Round(0.999999999999999999999999999m, 3)
                        + "|" + Math.Round(-0.5m, MidpointRounding.ToNegativeInfinity)
                        + "|" + Math.Round(1234.5678m, 3, MidpointRounding.ToZero)
                        + "|" + Math.Round(79228162514264337593543950335m, 28);
                }

                public static int Throws() {
                    int count = 0;
                    try { Math.Round(2.5, -1); } catch (ArgumentException) { count++; }
                    try { Math.Round(2.5, 16); } catch (ArgumentException) { count++; }
                    try { Math.Round(2.5, (MidpointRounding)7); } catch (ArgumentException) { count++; }
                    try { Math.Round(2.5, 99, (MidpointRounding)99); } catch (ArgumentException) { count++; }
                    try { Math.Round(double.NaN, -1); } catch (ArgumentException) { count++; }
                    try { Math.Round(2.5m, 29); } catch (ArgumentException) { count++; }
                    try { Math.Round(2.5m, (MidpointRounding)5); } catch (ArgumentException) { count++; }
                    return count;
                }
            }
            """;
        assertOutput("MathRound", source, "Render",
                "2|4|-2|0|3|2|2|3|-2|-3|3.142|3|1|2.68|-2|10000000000000000|NaN|Infinity|"
                        + "2|4|-3|1.00|2.68|1.00|1.000|-1|1234.567|"
                        + "79228162514264337593543950335");
        assertOutput("MathRound", source, "Throws", 7);
    }

    /// The `float`/`double` constants beyond `MinValue`/`MaxValue`.
    ///
    /// C# declares all four as `const`, so each must fold in a constant context as well as
    /// bind in an ordinary expression, and `Epsilon` must be the smallest positive value -
    /// the meaning Java gives `MIN_VALUE` and C# does not.
    private void emitFloatingConstants() {
        String source = """
            public struct Floats {
                public static string Render() {
                    const double far = double.PositiveInfinity;
                    return double.NaN
                        + "|" + double.PositiveInfinity
                        + "|" + double.NegativeInfinity
                        + "|" + double.Epsilon
                        + "|" + float.NaN
                        + "|" + float.PositiveInfinity
                        + "|" + float.NegativeInfinity
                        + "|" + float.Epsilon
                        + "|" + (double.NaN == double.NaN)
                        + "|" + (1.0 / 0.0 == double.PositiveInfinity)
                        + "|" + far;
                }
            }
            """;
        assertOutput("Floats", source, "Render",
                "NaN|Infinity|-Infinity|5E-324|NaN|Infinity|-Infinity|1E-45|"
                        + "False|True|Infinity");
    }

    /// The .NET 10 oracle exposes 13 Clamp overloads. Every overload returns an input
    /// operand unchanged, which preserves unsigned identity, floating NaN/signed-zero bits,
    /// and decimal scale; range validation happens first and throws ArgumentException.
    private void emitSystemMathClamp() {
        String source = """
            using System;

            public struct MathClamp {
                public static string Render() {
                    sbyte sb = Math.Clamp((sbyte)-9, (sbyte)-4, (sbyte)7);
                    byte b = Math.Clamp((byte)250, (byte)200, byte.MaxValue);
                    short sh = Math.Clamp((short)30000, (short)-20000, (short)20000);
                    ushort us = Math.Clamp((ushort)5, (ushort)40000, ushort.MaxValue);
                    int i = Math.Clamp(3, -10, 20);
                    uint ui = Math.Clamp(5u, 3000000000u, uint.MaxValue);
                    long l = Math.Clamp(long.MaxValue, -10L, 20L);
                    ulong ul = Math.Clamp(18000000000000000000UL, 15000000000000000000UL, ulong.MaxValue);
                    nint n = Math.Clamp(nint.MinValue, (nint)(-10), (nint)20);
                    nuint nu = Math.Clamp(nuint.MaxValue, (nuint)5, (nuint)18000000000000000000UL);
                    float fzero = Math.Clamp(-0.0f, 0.0f, 0.0f);
                    float fbound = Math.Clamp(1.0f, 0.0f, -0.0f);
                    float fnan = 0.0f / 0.0f;
                    double dzero = Math.Clamp(-0.0, 0.0, 0.0);
                    double dbound = Math.Clamp(1.0, 0.0, -0.0);
                    double dnan = 0.0 / 0.0;
                    return sb + "|" + b + "|" + sh + "|" + us + "|" + i + "|" + ui + "|"
                        + l + "|" + ul + "|" + n + "|" + nu + "|"
                        + fzero + "|" + fbound + "|" + Math.Clamp(fnan, -1.0f, 1.0f) + "|"
                        + Math.Clamp(2.0f, fnan, 1.0f) + "|" + Math.Clamp(2.0f, -1.0f, fnan) + "|"
                        + dzero + "|" + dbound + "|" + Math.Clamp(dnan, -1.0, 1.0) + "|"
                        + Math.Clamp(2.0, dnan, 1.0) + "|" + Math.Clamp(2.0, -1.0, dnan) + "|"
                        + Math.Clamp(0.1m, 1.00m, 3.000m) + "|"
                        + Math.Clamp(2.0000m, 1.00m, 3.000m) + "|"
                        + Math.Clamp(4m, 1.00m, 3.000m) + "|"
                        + Math.Clamp(1.0000m, 1.00m, 3.000m);
                }

                public static int Throws() {
                    float fnan = 0.0f / 0.0f;
                    double dnan = 0.0 / 0.0;
                    int count = 0;
                    try { Math.Clamp((sbyte)0, (sbyte)7, (sbyte)-4); } catch (ArgumentException) { count++; }
                    try { Math.Clamp((byte)0, (byte)250, (byte)200); } catch (ArgumentException) { count++; }
                    try { Math.Clamp((short)0, (short)20000, (short)-20000); } catch (ArgumentException) { count++; }
                    try { Math.Clamp((ushort)0, (ushort)60000, (ushort)40000); } catch (ArgumentException) { count++; }
                    try { Math.Clamp(0, 20, -10); } catch (ArgumentException) { count++; }
                    try { Math.Clamp(0u, 4000000000u, 3000000000u); } catch (ArgumentException) { count++; }
                    try { Math.Clamp(0L, 20L, -10L); } catch (ArgumentException) { count++; }
                    try { Math.Clamp(0UL, 18000000000000000000UL, 15000000000000000000UL); } catch (ArgumentException) { count++; }
                    try { Math.Clamp((nint)0, (nint)20, (nint)(-10)); } catch (ArgumentException) { count++; }
                    try { Math.Clamp((nuint)0, (nuint)18000000000000000000UL, (nuint)15000000000000000000UL); } catch (ArgumentException) { count++; }
                    try { Math.Clamp(fnan, 2.0f, 1.0f); } catch (ArgumentException) { count++; }
                    try { Math.Clamp(dnan, 2.0, 1.0); } catch (ArgumentException) { count++; }
                    try { Math.Clamp(0m, 2.0m, 1.00m); } catch (ArgumentException) { count++; }
                    return count;
                }
            }
            """;
        assertOutput("MathClamp", source, "Render",
                "-4|250|20000|40000|3|3000000000|20|18000000000000000000|-10|"
                        + "18000000000000000000|-0|-0|NaN|1|2|-0|-0|NaN|1|2|"
                        + "1.00|2.0000|3.000|1.0000");
        assertOutput("MathClamp", source, "Throws", 13);
    }

    /// `System.String.IsNullOrWhiteSpace` must use C#'s whitespace set, not Java's:
    /// the four divergence code points (`\u001C`, `\u0085`, `\u00A0`, `\u2007`, `\u202F`)
    /// are pinned in both directions against the .NET 10 oracle.
    private void emitStringNullOrWhiteSpaceChecks() {
        String source = """
            using System;
            public struct StringChecks {
                public static string Render() {
                    string n = null;
                    string e = "";
                    string s = "x";
                    string nbsp = "" + (char)0x00A0;
                    string fs = "" + (char)0x001C;
                    string figure = "" + (char)0x2007;
                    string narrow = "" + (char)0x202F;
                    return String.IsNullOrEmpty(n) + "|"
                        + String.IsNullOrEmpty(e) + "|"
                        + String.IsNullOrEmpty(s) + "|"
                        + String.IsNullOrWhiteSpace(n) + "|"
                        + String.IsNullOrWhiteSpace(e) + "|"
                        + String.IsNullOrWhiteSpace(" \\t\\r\\n") + "|"
                        + String.IsNullOrWhiteSpace(nbsp) + "|"
                        + String.IsNullOrWhiteSpace(fs) + "|"
                        + String.IsNullOrWhiteSpace(figure) + "|"
                        + String.IsNullOrWhiteSpace(narrow) + "|"
                        + String.IsNullOrWhiteSpace(s);
                }
            }
            """;
        assertOutput("StringChecks", source, "Render",
                "True|True|False|True|True|True|True|False|True|True|False");
    }

    /// The .NET 10/JDK 25 oracle sweep compared all 65,536 UTF-16 code units. The new
    /// IsLetterOrDigit member is identical; invariant casing needs exactly the U+0130/U+0131
    /// guards in VsChar. Counts cover the whole BMP and the value string pins those edges.
    private void emitSystemCharPredicates() {
        String source = """
            using System;

            public struct CharPredicates {
                public static string Render() {
                    return char.IsDigit('7') + "|" + char.IsDigit((char)0x0665) + "|"
                        + char.IsDigit((char)0x00B2) + "|" + Char.IsLetter('A') + "|"
                        + Char.IsLetter((char)0x03A9) + "|" + Char.IsLetter((char)0x4E2D) + "|"
                        + Char.IsLetter((char)0x0301) + "|" + Char.IsLetter((char)0xD800) + "|"
                        + Char.IsWhiteSpace(' ') + "|"
                        + Char.IsWhiteSpace((char)0x0085) + "|"
                        + Char.IsWhiteSpace((char)0x00A0) + "|"
                        + Char.IsWhiteSpace((char)0x2007) + "|"
                        + Char.IsWhiteSpace((char)0x202F) + "|"
                        + Char.IsWhiteSpace((char)0x001C) + "|"
                        + Char.IsWhiteSpace('x') + "|"
                        + char.IsLetterOrDigit('A') + "|" + char.IsLetterOrDigit('7') + "|"
                        + char.IsLetterOrDigit((char)0x0301) + "|"
                        + (int)char.ToUpperInvariant('a') + "|"
                        + (int)Char.ToLowerInvariant('A') + "|"
                        + (int)Char.ToUpperInvariant((char)0x0131) + "|"
                        + (int)Char.ToLowerInvariant((char)0x0130);
                }

                public static string BmpCounts() {
                    int digits = 0;
                    int letters = 0;
                    int whitespace = 0;
                    int letterOrDigit = 0;
                    int upperChanges = 0;
                    int lowerChanges = 0;
                    for (int i = 0; i <= 65535; i++) {
                        char value = (char)i;
                        if (char.IsDigit(value)) digits++;
                        if (char.IsLetter(value)) letters++;
                        if (char.IsWhiteSpace(value)) whitespace++;
                        if (char.IsLetterOrDigit(value)) letterOrDigit++;
                        if (char.ToUpperInvariant(value) != value) upperChanges++;
                        if (char.ToLowerInvariant(value) != value) lowerChanges++;
                    }
                    return digits + "|" + letters + "|" + whitespace + "|" + letterOrDigit
                        + "|" + upperChanges + "|" + lowerChanges;
                }
            }
            """;
        assertOutput("CharPredicates", source, "Render",
                "True|True|False|True|True|True|False|False|True|True|True|True|True|False|False"
                    + "|True|True|False|65|97|305|304");
        assertOutput("CharPredicates", source, "BmpCounts", "370|48973|25|49343|1194|1177");
    }

    /// String invariant casing is simple code-point mapping, not Java's expanding/contextual
    /// Locale.ROOT string operation. The emitted path must preserve supplementary and lone
    /// surrogate UTF-16 while applying the two Turkish-I corrections.
    private void emitStringInvariantCasing() {
        String source = """
            using System;
            public struct InvariantCase {
                public static string Map(string value) {
                    return value.ToUpperInvariant() + "|" + value.ToLowerInvariant();
                }
                public static string Null(string value) {
                    try {
                        return value.ToUpperInvariant();
                    } catch (NullReferenceException) {
                        return "null receiver";
                    }
                }
            }
            """;
        assertOutput("InvariantCase", source, "Map", "ABC|abc", "AbC");
        assertOutput("InvariantCase", source, "Map",
                "I\u0130\u0131I|i\u0130\u0131i", "I\u0130\u0131i");
        assertOutput("InvariantCase", source, "Map", "\u00DF\uFB00|\u00DF\uFB00",
                "\u00DF\uFB00");
        assertOutput("InvariantCase", source, "Map", "\u039F\u03A3|\u03BF\u03C3",
                "\u039F\u03A3");
        assertOutput("InvariantCase", source, "Map", "\uD801\uDC00|\uD801\uDC28",
                "\uD801\uDC28");
        assertOutput("InvariantCase", source, "Map", "X\uD800Y|x\uD800y", "x\uD800Y");
        assertOutput("InvariantCase", source, "Null", "null receiver", (Object) null);
    }

    /// The selected Convert pair is strict, invariant and static: emitted calls must preserve
    /// UInt32 bit-pattern parsing outside base 10 and expose all three failure categories.
    private void emitSystemConvertRadix() {
        String source = """
            using System;

            public struct Radix {
                public static string Render(int value) {
                    return Convert.ToString(value, 16) + "|" + Convert.ToString(value, 2);
                }
                public static int Parse(string value, int radix) {
                    return Convert.ToInt32(value, radix);
                }
                public static string Classify(string value, int radix) {
                    try {
                        return "ok " + Convert.ToInt32(value, radix);
                    } catch (FormatException) {
                        return "format";
                    } catch (OverflowException) {
                        return "overflow";
                    } catch (ArgumentException) {
                        return "argument";
                    }
                }
            }
            """;
        assertOutput("Radix", source, "Render", "beef|1011111011101111", 48879);
        assertOutput("Radix", source, "Render",
                "ffffffff|11111111111111111111111111111111", -1);
        assertOutput("Radix", source, "Parse", 48879, "beef", 16);
        assertOutput("Radix", source, "Parse", -1, "0xffffffff", 16);
        assertOutput("Radix", source, "Parse", 0, null, 2);
        assertOutput("Radix", source, "Classify", "argument", "1", 3);
        assertOutput("Radix", source, "Classify", "argument", "-1", 16);
        assertOutput("Radix", source, "Classify", "argument", "", 16);
        assertOutput("Radix", source, "Classify", "format", "x", 16);
        assertOutput("Radix", source, "Classify", "overflow", "100000000", 16);
    }

    /// `System.Math.Sign` returns `int` for every overload. The float/double forms must
    /// throw for NaN (C# `ArithmeticException`), unlike Java's `Math.signum`, which returns
    /// NaN. The .NET 10 oracle confirms the value sequence and both NaN throws.
    private void emitSystemMathSign() {
        String source = """
            using System;
            public struct MathSign {
                public static string Render() {
                    nint nneg = -5;
                    nint npos = 5;
                    return Math.Sign((sbyte)-5) + "|" + Math.Sign((sbyte)5) + "|"
                        + Math.Sign((sbyte)0) + "|" + Math.Sign((short)-5) + "|"
                        + Math.Sign((short)5) + "|" + Math.Sign(-5) + "|"
                        + Math.Sign(5) + "|" + Math.Sign(0) + "|"
                        + Math.Sign(-5L) + "|" + Math.Sign(5L) + "|"
                        + Math.Sign(nneg) + "|" + Math.Sign(npos) + "|"
                        + Math.Sign(-2.5f) + "|" + Math.Sign(2.5f) + "|"
                        + Math.Sign(0.0f) + "|" + Math.Sign(-2.5) + "|"
                        + Math.Sign(2.5) + "|" + Math.Sign(0.0) + "|"
                        + Math.Sign(-2.5m) + "|" + Math.Sign(2.5m) + "|"
                        + Math.Sign(0.0m);
                }

                public static int Throws() {
                    float zero = 0.0f;
                    float fnan = zero / zero;
                    double dzero = 0.0;
                    double dnan = dzero / dzero;
                    int count = 0;
                    try { Math.Sign(fnan); } catch (Exception) { count++; }
                    try { Math.Sign(dnan); } catch (Exception) { count++; }
                    return count;
                }
            }
            """;
        assertOutput("MathSign", source, "Render",
                "-1|1|0|-1|1|-1|1|0|-1|1|-1|1|-1|1|0|-1|1|0|-1|1|0");
        assertOutput("MathSign", source, "Throws", 2);
    }

    /// `Floor`/`Ceiling`/`Truncate` match the .NET 10 oracle, including signed zero, NaN
    /// and infinities. `Truncate` is the one member Java has no direct opcode for, so
    /// `VsMath.truncate` selects `ceil` for negative values and `floor` otherwise.
    private void emitSystemMathRounding() {
        String source = """
            using System;
            public struct MathRounding {
                public static string Render() {
                    double negZero = -0.0;
                    double zero = 0.0;
                    double nan = zero / zero;
                    double posInf = 1.0 / zero;
                    double negInf = -1.0 / zero;
                    return Math.Floor(2.7) + "|" + Math.Ceiling(2.7) + "|" + Math.Truncate(2.7) + "|"
                        + Math.Floor(-2.7) + "|" + Math.Ceiling(-2.7) + "|" + Math.Truncate(-2.7) + "|"
                        + Math.Floor(2.3) + "|" + Math.Ceiling(2.3) + "|" + Math.Truncate(2.3) + "|"
                        + Math.Floor(-2.3) + "|" + Math.Ceiling(-2.3) + "|" + Math.Truncate(-2.3) + "|"
                        + Math.Floor(0.0) + "|" + Math.Ceiling(0.0) + "|" + Math.Truncate(0.0) + "|"
                        + Math.Floor(negZero) + "|" + Math.Ceiling(negZero) + "|" + Math.Truncate(negZero) + "|"
                        + Math.Floor(nan) + "|" + Math.Ceiling(nan) + "|" + Math.Truncate(nan) + "|"
                        + Math.Floor(posInf) + "|" + Math.Ceiling(posInf) + "|" + Math.Truncate(posInf) + "|"
                        + Math.Floor(negInf) + "|" + Math.Ceiling(negInf) + "|" + Math.Truncate(negInf) + "|"
                        + Math.Floor(5.0) + "|" + Math.Ceiling(5.0) + "|" + Math.Truncate(5.0) + "|"
                        + Math.Floor(-5.0) + "|" + Math.Ceiling(-5.0) + "|" + Math.Truncate(-5.0);
                }
            }
            """;
        assertOutput("MathRounding", source, "Render",
                "2|3|2|-3|-2|-2|2|3|2|-3|-2|-2|0|0|0|-0|-0|-0|NaN|NaN|NaN|"
                        + "Infinity|Infinity|Infinity|-Infinity|-Infinity|-Infinity|5|5|5|-5|-5|-5");
    }

    /// The matched .NET 10/JDK 25 oracle compared 12,687 records. The delegates agree on
    /// every Sqrt/Log result category and bit. Pow needs the VsMath
    /// guards pinned below for positive one with NaN/infinity and negative one with
    /// infinity; negative one with NaN must remain NaN and Pow normalizes Java's two
    /// negative-NaN sign-bit results to the positive .NET representation.
    private void emitSystemMathTranscendentals() {
        String source = """
            using System;

            public struct MathTranscendentals {
                public static string Render() {
                    double zero = 0.0;
                    double negZero = -0.0;
                    double nan = zero / zero;
                    double posInf = 1.0 / zero;
                    double negInf = -1.0 / zero;
                    return Math.Sqrt(4.0) + "|" + Math.Sqrt(-4.0) + "|"
                        + Math.Sqrt(negZero) + "|" + Math.Sqrt(posInf) + "|"
                        + Math.Log(1.0) + "|" + Math.Log(zero) + "|"
                        + Math.Log(-1.0) + "|" + Math.Log(posInf) + "|"
                        + Math.Pow(2.0, 10.0) + "|" + Math.Pow(-2.0, 3.0) + "|"
                        + Math.Pow(-2.0, 2.0) + "|" + Math.Pow(-2.0, 0.5) + "|"
                        + Math.Pow(negZero, -3.0) + "|" + Math.Pow(negZero, 3.0) + "|"
                        + Math.Pow(1.0, nan) + "|" + Math.Pow(1.0, posInf) + "|"
                        + Math.Pow(1.0, negInf) + "|" + Math.Pow(-1.0, posInf) + "|"
                        + Math.Pow(-1.0, negInf) + "|" + Math.Pow(-1.0, nan);
                }
            }
            """;
        assertOutput("MathTranscendentals", source, "Render",
                "2|NaN|-0|Infinity|0|-Infinity|NaN|Infinity|1024|-8|4|NaN|"
                        + "-Infinity|-0|1|1|1|1|1|NaN");
    }

    /// Direct trigonometry has exact .NET result categories and edge bits. Ordinary finite
    /// results follow the rule's measured one-ULP policy, so the emitted path rounds only the
    /// display samples while still executing each runtime mapping.
    private void emitSystemMathDirectTrigonometry() {
        String source = """
            using System;

            public struct MathDirectTrig {
                public static string Render() {
                    double zero = 0.0;
                    double negZero = -0.0;
                    double posInf = 1.0 / zero;
                    return Math.Sin(zero) + "|" + Math.Sin(negZero) + "|"
                        + Math.Cos(zero) + "|" + Math.Tan(negZero) + "|"
                        + Math.Sin(posInf) + "|" + Math.Cos(posInf) + "|" + Math.Tan(posInf) + "|"
                        + Math.Round(Math.Sin(0.5), 12) + "|"
                        + Math.Round(Math.Cos(0.5), 12) + "|"
                        + Math.Round(Math.Tan(0.5), 12);
                }
            }
            """;
        assertOutput("MathDirectTrig", source, "Render",
                "0|-0|1|-0|NaN|NaN|NaN|0.479425538604|0.87758256189|0.546302489844");
    }

    /// `Trim`/`TrimStart`/`TrimEnd` must use the same C# whitespace set as the design. The four
    /// C#-only whitespace chars are trimmed, and the Java-only `\u001C`/`\u001F` are not.
    private void emitStringTrimFamily() {
        String source = """
            public struct StringTrim {
                public static string Render() {
                    string a = "  x  ";
                    string b = "" + (char)0x00A0 + "x" + (char)0x202F + " ";
                    string c = "" + (char)0x0085 + "x" + (char)0x0085;
                    string d = "" + (char)0x2007 + "x" + (char)0x2007;
                    string e = "" + (char)0x001C + "x";
                    string f = "x" + (char)0x001F;
                    string g = "" + (char)0x00A0 + (char)0x202F;
                    return a.Trim() + "|" + a.TrimStart() + "|" + a.TrimEnd() + "|"
                        + b.Trim() + "|" + b.TrimStart() + "|" + b.TrimEnd() + "|"
                        + c.Trim() + "|" + c.TrimStart() + "|" + c.TrimEnd() + "|"
                        + d.Trim() + "|" + d.TrimStart() + "|" + d.TrimEnd() + "|"
                        + e.Trim() + "|" + e.TrimStart() + "|" + e.TrimEnd() + "|"
                        + f.Trim() + "|" + f.TrimStart() + "|" + f.TrimEnd() + "|"
                        + g.Trim() + "|" + g.TrimStart() + "|" + g.TrimEnd();
                }
            }
            """;
        String fs = "" + (char) 0x001C;
        String us = "" + (char) 0x001F;
        String expected = "x|x  |  x|"
                + "x|x" + (char) 0x202F + " |" + (char) 0x00A0 + "x|"
                + "x|x" + (char) 0x0085 + "|" + (char) 0x0085 + "x|"
                + "x|x" + (char) 0x2007 + "|" + (char) 0x2007 + "x|"
                + fs + "x|" + fs + "x|" + fs + "x|"
                + "x" + us + "|x" + us + "|x" + us + "|"
                + "||";
        assertOutput("StringTrim", source, "Render", expected);
    }

    /// The ordinal `System.String` search and replace family. Every value matches the
    /// .NET 10 oracle: an empty needle is contained, a missing one is `-1`/`False`, `Replace`
    /// scans left-to-right without overlap, a null `newValue` deletes, and the comparison is
    /// ordinal, so a zero-width joiner is never folded away.
    private void emitStringSearchFamily() {
        String source = """
            using System;
            public struct StringSearch {
                public static string Render() {
                    string s = "abcb";
                    string e = "";
                    string zwj = "a" + (char)0x200D + "bc";
                    string n = null;
                    return s.Contains("b") + "|" + s.Contains("") + "|" + s.Contains("z") + "|"
                        + s.Contains('b') + "|" + s.Contains('z') + "|" + e.Contains("") + "|"
                        + s.IndexOf('b') + "|" + s.IndexOf('z') + "|" + e.IndexOf('a') + "|"
                        + s.LastIndexOf('b') + "|" + s.LastIndexOf('z') + "|" + e.LastIndexOf('a') + "|"
                        + s.StartsWith('a') + "|" + s.StartsWith('b') + "|" + e.StartsWith('a') + "|"
                        + s.EndsWith('b') + "|" + s.EndsWith('a') + "|" + e.EndsWith('a') + "|"
                        + s.Replace('b', 'x') + "|" + s.Replace('b', 'b') + "|" + s.Replace('z', 'x') + "|"
                        + s.Replace("b", "xy") + "|" + s.Replace("b", "") + "|" + s.Replace("b", n) + "|"
                        + s.Replace("q", "x") + "|" + "aaa".Replace("aa", "b") + "|" + e.Replace("a", "b") + "|"
                        + "abc".Contains((char)0x200D) + "|" + zwj.IndexOf((char)0x200D);
                }

                public static int Throws() {
                    string s = "abcb";
                    string n = null;
                    int count = 0;
                    try { bool ignored = s.Contains(n); } catch (ArgumentException) { count++; }
                    try { string ignored = s.Replace(n, "x"); } catch (ArgumentException) { count++; }
                    try { string ignored = s.Replace("", "x"); } catch (ArgumentException) { count++; }
                    try { bool ignored = n.Contains("a"); } catch (NullReferenceException) { count++; }
                    try { int ignored = n.IndexOf('a'); } catch (NullReferenceException) { count++; }
                    try { string ignored = n.Replace('a', 'b'); } catch (NullReferenceException) { count++; }
                    return count;
                }
            }
            """;
        String expected = "True|True|False|True|False|True|"
                + "1|-1|-1|"
                + "3|-1|-1|"
                + "True|False|False|"
                + "True|False|False|"
                + "axcx|abcb|abcb|"
                + "axycxy|ac|ac|"
                + "abcb|ba||"
                + "False|1";
        assertOutput("StringSearch", source, "Render", expected);
        assertOutput("StringSearch", source, "Throws", 6);
    }

    /// Join/Concat preserve left-to-right argument evaluation, treat null strings as empty,
    /// and reject only a null array. Every output and exception matches the .NET 10 oracle.
    private void emitStringJoinConcat() {
        String source = """
            using System;
            public struct StringCompose {
                static string Mark(ref string order, string marker, string value) {
                    order += marker;
                    return value;
                }

                public static string Render() {
                    string n = null;
                    string[] mixed = new string[] { "a", n, "b" };
                    string[] empty = new string[] { };
                    return "[" + string.Concat("a", "b") + "]|"
                        + "[" + string.Concat("a", n, "b") + "]|"
                        + "[" + string.Concat(mixed) + "]|"
                        + "[" + string.Concat(empty) + "]|"
                        + "[" + string.Join(",", "a", "b") + "]|"
                        + "[" + string.Join(",", mixed) + "]|"
                        + "[" + string.Join(n, mixed) + "]|"
                        + "[" + string.Join(",", empty) + "]";
                }

                public static string EvaluationOrder() {
                    string order = "";
                    string concatenated = string.Concat(Mark(ref order, "1", "a"),
                        Mark(ref order, "2", "b"), Mark(ref order, "3", "c"));
                    string concatOrder = order;
                    order = "";
                    string joined = string.Join(Mark(ref order, "1", ","),
                        Mark(ref order, "2", "a"), Mark(ref order, "3", "b"));
                    return concatOrder + "|" + concatenated + "|" + order + "|" + joined;
                }

                public static int Throws() {
                    string[] values = null;
                    int count = 0;
                    try { string ignored = string.Concat(values); }
                    catch (ArgumentException) { count++; }
                    try { string ignored = string.Join(",", values); }
                    catch (ArgumentException) { count++; }
                    try { string ignored = string.Concat(null); }
                    catch (ArgumentException) { count++; }
                    try { string ignored = string.Join(",", null); }
                    catch (ArgumentException) { count++; }
                    return count;
                }
            }
            """;
        assertOutput("StringCompose", source, "Render",
                "[ab]|[ab]|[ab]|[]|[a,b]|[a,,b]|[ab]|[]");
        assertOutput("StringCompose", source, "EvaluationOrder", "123|abc|123|a,b");
        assertOutput("StringCompose", source, "Throws", 4);
    }

    /// `x?.M()` on a nullable value receiver resolves `M` against the underlying `T` and
    /// short-circuits on the null state; the result lifts to `T?` when `M` returns a value
    /// type, and a nested chain (`x?.ToString()?.Length`) addresses the first non-null link.
    private void emitNullableValueConditionalAccess() {
        String source = """
            public struct NullCond {
                public static string Render(int? x) {
                    return x?.ToString() ?? "null";
                }
                public static int Length(int? x) {
                    return x?.ToString()?.Length ?? -1;
                }
            }
            """;
        assertOutput("NullCond", source, "Render", "42", 42);
        assertOutput("NullCond", source, "Render", "null", (Object) null);
        assertOutput("NullCond", source, "Length", 2, 42);
        assertOutput("NullCond", source, "Length", -1, (Object) null);
    }

    /// A positional `record struct`: its parameters are public instance fields, its
    /// declaration implies a constructor, `with` copies through that constructor, and both
    /// deconstruction forms and positional patterns read the components in declaration order.
    private void emitRecordStructPositionalMembers() {
        String source = """
            public record struct Point(int X, long Y);
            public struct RecordUse {
                public static int Calls;
                private static Point Next() {
                    Calls = Calls + 1;
                    return new Point(Calls, 10L);
                }
                public static string Read() {
                    Point p = new Point(3, 4L);
                    return p.X + "|" + p.Y;
                }
                public static string Copy() {
                    Point p = new Point(3, 4L);
                    Point q = p with { X = 9 };
                    return q.X + "|" + q.Y + "|" + p.X;
                }
                public static string CopyEvaluatesReceiverOnce() {
                    Calls = 0;
                    Point p = Next() with { Y = 0L };
                    return p.X + "|" + Calls;
                }
                public static string Deconstruct() {
                    Point p = new Point(3, 4L);
                    (int a, long b) = p;
                    var (c, d) = p;
                    int e;
                    long f;
                    (e, f) = p;
                    return a + "|" + b + "|" + c + "|" + d + "|" + e + "|" + f;
                }
                public static string Match(int x, long y) {
                    Point p = new Point(x, y);
                    return p switch {
                        (0, 0L) => "origin",
                        (var only, 0L) => "axis " + only,
                        { X: 1, Y: var rest } => "one " + rest,
                        _ => "other"
                    };
                }
            }
            """;
        assertOutput("RecordUse", source, "Read", "3|4");
        assertOutput("RecordUse", source, "Copy", "9|4|3");
        // `with` on a call receiver must advance the counter once, which a re-evaluated
        // receiver (one `getfield` per copied component) would not.
        assertOutput("RecordUse", source, "CopyEvaluatesReceiverOnce", "1|1");
        assertOutput("RecordUse", source, "Deconstruct", "3|4|3|4|3|4");
        assertOutput("RecordUse", source, "Match", "origin", 0, 0L);
        assertOutput("RecordUse", source, "Match", "axis 5", 5, 0L);
        assertOutput("RecordUse", source, "Match", "one 7", 1, 7L);
        assertOutput("RecordUse", source, "Match", "other", 4, 4L);
    }

    /// The emitted record struct has to be an ordinary JVM class for Java callers: public
    /// fields at the component descriptors and one constructor taking the components in
    /// declaration order, next to the parameterless constructor every holder declares.
    private void emitRecordStructJavaShape() {
        String source = """
            public record struct Pair(string Name, int Count);
            public struct PairUse {
                public static int Read(Pair p) => p.Count;
            }
            """;
        Class<?> pair = compileAndLoad("Pair", source);
        try {
            Assert.equal(String.class, pair.getField("Name").getType(),
                    "component field keeps its declared descriptor");
            Assert.equal(int.class, pair.getField("Count").getType(),
                    "component field keeps its declared descriptor");
            Object value = pair.getConstructor(String.class, int.class)
                    .newInstance("a", 7);
            Assert.equal("a", pair.getField("Name").get(value),
                    "the constructor stores its first argument in the first component");
            Assert.equal(7, pair.getField("Count").get(value),
                    "the constructor stores its second argument in the second component");
            Assert.isTrue(pair.getConstructor() != null,
                    "the parameterless constructor stays available to Java callers");
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("record struct shape is not Java-callable", failure);
        }
    }

    /// Switch dispatch used to be two narrow matchers: a switch *expression* only over a
    /// reference or JVM-int carrier with constant arms, and a switch *statement* only over an
    /// int or string with constant labels. Everything else - a `long` governing value, a
    /// relational arm, a `when` guard, a type pattern - reported VS29999 even though `is`
    /// compiled the very same pattern. Both forms now go through the one pattern compiler
    ///, so this test walks the shapes that used to be internal errors.
    private void emitGeneralSwitchDispatch() {
        String source = """
            public enum Color { Red, Green, Blue }
            public struct Dispatch {
                public static string Wide(long v) => v switch {
                    1L => "one",
                    > 2L and < 10L => "mid",
                    _ => "other"
                };
                public static string Fractional(double d) => d switch {
                    1.5 => "exact",
                    > 2.0 => "big",
                    _ => "other"
                };
                public static string Grade(int score) {
                    switch (score) {
                        case < 0: return "invalid";
                        case >= 90: return "A";
                        case int n when n >= 70: return "C" + n;
                        case 0: return "zero";
                        default: return "F";
                    }
                }
                public static string Shape(object o) {
                    switch (o) {
                        case null: return "null";
                        case int n when n > 100: return "big";
                        case int n: return "int " + n;
                        case string s: return "string " + s;
                        default: return "other";
                    }
                }
                public static string Warm(Color c) {
                    switch (c) {
                        case Color.Red:
                        case Color.Blue: return "warm";
                        case Color.Green: return "cool";
                        default: return "?";
                    }
                }
                public static string Fall(int x) {
                    string acc = "";
                    switch (x) {
                        case > 5: acc = acc + "big;"; goto default;
                        case 1: acc = acc + "one;"; break;
                        default: acc = acc + "end;"; break;
                    }
                    return acc;
                }
                public static int Bind(int x) => x is int n and > 5 ? n : -1;
            }
            """;
        assertOutput("Dispatch", source, "Wide", "one", 1L);
        assertOutput("Dispatch", source, "Wide", "mid", 5L);
        assertOutput("Dispatch", source, "Wide", "other", 50L);
        assertOutput("Dispatch", source, "Fractional", "exact", 1.5d);
        assertOutput("Dispatch", source, "Fractional", "big", 9.0d);
        assertOutput("Dispatch", source, "Grade", "invalid", -1);
        assertOutput("Dispatch", source, "Grade", "A", 95);
        assertOutput("Dispatch", source, "Grade", "C75", 75);
        assertOutput("Dispatch", source, "Grade", "zero", 0);
        assertOutput("Dispatch", source, "Grade", "F", 50);
        assertOutput("Dispatch", source, "Shape", "null", (Object) null);
        assertOutput("Dispatch", source, "Shape", "big", 500);
        assertOutput("Dispatch", source, "Shape", "int 5", 5);
        assertOutput("Dispatch", source, "Shape", "string hi", "hi");
        assertOutput("Dispatch", source, "Shape", "other", 1.5d);
        // A `goto default` out of a pattern label still reaches the default section, and the
        // constant sections keep dispatching by value.
        assertOutput("Dispatch", source, "Fall", "big;end;", 9);
        assertOutput("Dispatch", source, "Fall", "one;", 1);
        assertOutput("Dispatch", source, "Bind", 7, 7);
        assertOutput("Dispatch", source, "Bind", -1, 2);
    }

    /// A corelib exception is a V# name whose values are instances of a JVM class, so
    /// `new Exception("boom")` selects a constructor of that class. Before this, corelib
    /// exceptions could be caught but never constructed - `throw new Exception(...)`, the most
    /// ordinary line in C# error handling, reported VS20001 - and the only exceptions a
    /// program could name were `Exception` and `OverflowException`.
    private void emitCorelibExceptions() {
        String source = """
            using System;
            public struct Throwing {
                public static string Thrown() {
                    try {
                        throw new InvalidOperationException("no");
                    } catch (InvalidOperationException) {
                        return "invalid operation";
                    }
                }
                public static string Root() {
                    try {
                        throw new ArgumentException("bad");
                    } catch (Exception) {
                        return "root catches every corelib exception";
                    }
                }
                public static string Indexed(int index) {
                    int[] values = new int[1];
                    try {
                        values[index] = 1;
                        return "stored";
                    } catch (IndexOutOfRangeException) {
                        return "out of range";
                    }
                }
                public static string Dereferenced(string text) {
                    try {
                        return "length " + text.Length;
                    } catch (NullReferenceException) {
                        return "null reference";
                    }
                }
                public static string Cast(object value) {
                    try {
                        int number = (int) value;
                        return "int " + number;
                    } catch (InvalidCastException) {
                        return "invalid cast";
                    }
                }
                public static string Divide(int divisor) {
                    try {
                        return "value " + (12 / divisor);
                    } catch (DivideByZeroException) {
                        return "divide by zero";
                    }
                }
            }
            """;
        assertOutput("Throwing", source, "Thrown", "invalid operation");
        assertOutput("Throwing", source, "Root", "root catches every corelib exception");
        // The JVM raises these itself, so catching them by their C# names proves the carrier
        // mapping is the same one the runtime uses.
        assertOutput("Throwing", source, "Indexed", "stored", 0);
        assertOutput("Throwing", source, "Indexed", "out of range", 3);
        assertOutput("Throwing", source, "Dereferenced", "length 2", "hi");
        assertOutput("Throwing", source, "Dereferenced", "null reference", (Object) null);
        assertOutput("Throwing", source, "Cast", "int 7", 7);
        assertOutput("Throwing", source, "Cast", "invalid cast", "text");
        assertOutput("Throwing", source, "Divide", "value 4", 3);
        assertOutput("Throwing", source, "Divide", "divide by zero", 0);
    }

    /// Tuple conversion rebuilds the tuple element by element and recurses through nested
    /// tuples. Only the one-level widening case was executed; these pin
    /// the deeper shapes that share the same path: two levels of widening, a nested rename
    /// (element names are compile-time only, so the conversion must emit nothing for them),
    /// mixed integral and floating promotions at depth, and two sibling nested tuples each
    /// converting differently.
    private void emitNestedTupleConversions() {
        String source = """
            public struct Nested {
                public static string Widened() {
                    ((int, int), int) source = ((1, 2), 3);
                    ((long, long), long) target = source;
                    return target.Item1.Item1 + "|" + target.Item1.Item2 + "|" + target.Item2;
                }
                public static string Renamed() {
                    (int a, (int b, int c)) source = (1, (2, 3));
                    (int x, (int y, int z)) target = source;
                    return target.x + "|" + target.Item2.y + "|" + target.Item2.z;
                }
                public static string Promoted() {
                    (int, (int, float)) source = (1, (2, 3.5f));
                    (long, (long, double)) target = source;
                    return target.Item1 + "|" + target.Item2.Item1 + "|" + target.Item2.Item2;
                }
                public static string Siblings() {
                    ((int, int), (int, int)) source = ((1, 2), (3, 4));
                    ((long, long), (double, double)) target = source;
                    return target.Item1.Item2 + "|" + target.Item2.Item1;
                }
            }
            """;
        assertOutput("Nested", source, "Widened", "1|2|3");
        assertOutput("Nested", source, "Renamed", "1|2|3");
        assertOutput("Nested", source, "Promoted", "1|2|3.5");
        assertOutput("Nested", source, "Siblings", "2|3");
    }

    private void emitBuiltinLimitArithmetic() {
        String source = """
            public struct TestClass {
                public static int Span(int value) {
                    return int.MaxValue - int.MaxValue + value;
                }
            }
            """;
        assertOutput("TestClass", source, "Span", 5, 5);
    }

    /// A corelib type that is a C# *class* must compare against `null`. Before this,
    /// `System.Exception` was a `struct` in the type model - the only spelling corelib had -
    /// so `e != null` was rejected as a null test on a value type and no `catch` body could
    /// check its own exception.
    private void emitExceptionNullComparison() {
        String source = """
            using System;
            public struct TestClass {
                public static string Caught(int divisor) {
                    try {
                        int result = 10 / divisor;
                        return "no";
                    } catch (Exception failure) {
                        return failure != null ? "yes" : "null";
                    }
                }
            }
            """;
        assertOutput("TestClass", source, "Caught", "yes", 0);
    }

    /// A static field initializer has to *run*. The field was declared and the value
    /// silently discarded before this: `static int Count = 41;` read back as 0, which is worse
    /// than a diagnostic because the program compiles and computes the wrong answer.
    private void emitStaticFieldInitializers() {
        String source = """
            public struct TestClass {
                static int First = 2;
                static int Second = First * 3;
                static string Label = "v" + "#";

                public static string Read(int ignored) {
                    return First.ToString() + "|" + Second.ToString() + "|" + Label;
                }
            }
            """;
        assertOutput("TestClass", source, "Read", "2|6|v#", 0);
    }

    /// `int[] flat = { 4, 5, 6 };` is `new int[] { 4, 5, 6 }` (C# §12.8.16.5); the parser
    /// desugars it so binding, lowering and emission see one array-creation shape.
    private void emitArrayInitializerShorthand() {
        String source = """
            public struct TestClass {
                static int[] Shared = { 7, 8 };

                public static int Total(int extra) {
                    int[] flat = { 4, 5, 6 };
                    string[] names = { "a", "b" };
                    int[] empty = { };
                    int sum = empty.Length + names.Length;
                    foreach (int value in flat) {
                        sum = sum + value;
                    }
                    return sum + Shared[0] + Shared[1] + extra;
                }
            }
            """;
        assertOutput("TestClass", source, "Total", 33, 1);
    }

    private void emitInstanceMethods() {
        String source = """
            public struct TestClass {
                public int Value;

                public void Increment() {
                    Value = Value + 1;
                }

                public int GetValue() {
                    return Value;
                }

                public static int RunCounter() {
                    TestClass c = default(TestClass);
                    c.Value = 10;
                    c.Increment();
                    c.Increment();
                    return c.GetValue();
                }
            }
            """;

        SourceFile file = TestSources.styled("TestClass.vs", source);
        Compilation compilation = Compilation.of(java.util.List.of(file));

        CompilationResult result = compilation.emit();
        if (result.hasErrors()) {
            for (var d : result.diagnostics()) {
                System.out.println(d.message());
            }
        }
        Assert.isFalse(result.hasErrors(), "compilation should succeed");


        java.util.Map<String, byte[]> classes = new java.util.LinkedHashMap<>();
        for (UnitAnalysis unit : result.units()) {
            if (unit instanceof UnitAnalysis.Emitted emitted) {
                if (emitted.file().name().equals("TestClass.vs")) {
                    classes.putAll(emitted.classes());
                }
            }
        }

        Assert.isTrue(!classes.isEmpty(), "TestClass bytecode should be generated");

        try {
            java.util.Map<String, byte[]> finalClasses = classes;
            ClassLoader loader = new ClassLoader() {
                @Override
                protected Class<?> findClass(String name) throws ClassNotFoundException {
                    if (finalClasses.containsKey(name)) {
                        byte[] classBytes = finalClasses.get(name); return defineClass(name, classBytes, 0, classBytes.length);
                    }
                    return super.findClass(name);
                }
            };

            Class<?> clazz = loader.loadClass("TestClass");
            Method method = clazz.getMethod("RunCounter");

            Assert.equal(12, method.invoke(null), "RunCounter should return 12");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void emitMultipleClassesInOneFile() {
        String source = """
            namespace A {
                public struct B {
                    public int Val;
                    public int GetVal() { return Val; }
                }

                public struct C {
                    public static int Sum(B b) {
                        return b.GetVal() + 10;
                    }
                }
            }
            """;

        SourceFile file = TestSources.styled("MultiClass.vs", source);
        Compilation compilation = Compilation.of(java.util.List.of(file));

        CompilationResult result = compilation.emit();
        if (result.hasErrors()) {
            for (var d : result.diagnostics()) {
                System.out.println(d.message());
            }
        }
        Assert.isFalse(result.hasErrors(), "compilation should succeed");

        java.util.Map<String, byte[]> classes = new java.util.LinkedHashMap<>();
        for (UnitAnalysis unit : result.units()) {
            if (unit instanceof UnitAnalysis.Emitted emitted && emitted.file().name().equals("MultiClass.vs")) {
                classes.putAll(emitted.classes());
            }
        }

        Assert.isTrue(classes.containsKey("A.B"), "A.B should be generated");
        Assert.isTrue(classes.containsKey("A.C"), "A.C should be generated");

        try {
            java.util.Map<String, byte[]> finalClasses = classes;
            ClassLoader loader = new ClassLoader() {
                @Override
                protected Class<?> findClass(String name) throws ClassNotFoundException {
                    if (finalClasses.containsKey(name)) {
                        byte[] classBytes = finalClasses.get(name);
                        return defineClass(name, classBytes, 0, classBytes.length);
                    }
                    return super.findClass(name);
                }
            };

            Class<?> clazzB = loader.loadClass("A.B");
            Object bInstance = clazzB.getDeclaredConstructor().newInstance();
            clazzB.getField("Val").set(bInstance, 42);

            Class<?> clazzC = loader.loadClass("A.C");
            Method method = clazzC.getMethod("Sum", clazzB);

            Assert.equal(52, method.invoke(null, bInstance), "A.C.Sum should return 52");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void emitIndexAndSlice() {
        String source = """
            public struct Arrays {
                public static int TestSliceAndIndex() {
                    int[] arr = new int[] { 10, 20, 30, 40, 50 };
                    arr[^1] = 99;
                    int[] sub = arr[1..^1];
                    int sum = 0;
                    foreach (int x in sub) {
                        sum = sum + x;
                    }
                    return sum + arr[^1];
                }
            }
            """;
        assertOutput("Arrays", source, "TestSliceAndIndex", 189);
    }
}
