package vsharp.compiler.backend;

import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.util.LinkedHashMap;
import java.util.Map;

import vsharp.compiler.semantics.symbols.FunctionSymbol;
import vsharp.compiler.semantics.symbols.ParameterSymbol;

/// The single table binding every `extern` member declared by `corelib.vs` to the JVM
/// method that implements it.
///
/// A corelib member has no body, so the backend cannot emit a call to it the way it emits a
/// call to user code: something has to say which JVM method carries `System.Console.WriteLine`.
/// Keeping that in one table rather than in a chain of name comparisons is what makes a newly
/// declared corelib member a *loud* failure ([CodeEmitter] throws when the lookup misses)
/// instead of a silent call to a class that was never emitted.
///
/// Members are keyed by V# signature - qualified name plus parameter type spelling - not by
/// JVM descriptor, because `uint` and `int` share the `int` carrier and must reach different
/// runtime methods (`WriteLineUnsigned` versus `WriteLine`) while looking identical to the JVM.
final class RuntimeLibrary {

    /// How the argument stack must be adjusted before the call, for the rare member whose
    /// JVM counterpart takes a different shape than its C# declaration.
    enum ArgumentShape {
        /// The V# arguments are already exactly the JVM parameters.
        DIRECT,
        /// C# `Substring(start, length)` versus Java `substring(start, end)`: the second
        /// argument becomes `start + length` on the stack.
        STRING_START_LENGTH
    }

    /// How the call reaches its JVM method. A V# instance member does not have to become a JVM
    /// instance method: a builtin value type has no class of its own, so `i.ToString()` puts the
    /// receiver on the stack and then calls a static formatter with it.
    enum Invocation {
        /// A static V# member: nothing but the declared arguments are on the stack.
        STATIC,
        /// A JVM instance method invoked on the V# receiver.
        VIRTUAL,
        /// A static JVM method taking the V# receiver as its first argument.
        RECEIVER_STATIC;

        /// Whether a receiver must have been emitted before the arguments.
        boolean needsReceiver() {
            return this != STATIC;
        }
    }

    /// A resolved runtime target: the owner, the JVM method name, its descriptor, how the call
    /// is made, and any argument reshaping.
    record Target(ClassDesc owner, String name, MethodTypeDesc desc, Invocation invocation,
            ArgumentShape shape) {

        Target(ClassDesc owner, String name, MethodTypeDesc desc, boolean instanceCall) {
            this(owner, name, desc, instanceCall ? Invocation.VIRTUAL : Invocation.STATIC,
                    ArgumentShape.DIRECT);
        }

        Target(ClassDesc owner, String name, MethodTypeDesc desc, boolean instanceCall,
                ArgumentShape shape) {
            this(owner, name, desc, instanceCall ? Invocation.VIRTUAL : Invocation.STATIC, shape);
        }
    }

    private static final ClassDesc CD_CONSOLE = ClassDesc.of("vsharp.runtime.stdlib.Console");
    private static final ClassDesc CD_VSFORMAT = ClassDesc.of("vsharp.runtime.VsFormat");
    private static final ClassDesc CD_VSINT32 = ClassDesc.of("vsharp.runtime.VsInt32");
    private static final ClassDesc CD_VSDOUBLE = ClassDesc.of("vsharp.runtime.VsDouble");
    private static final ClassDesc CD_VSBOOLEAN = ClassDesc.of("vsharp.runtime.VsBoolean");
    private static final ClassDesc CD_VS_DECIMAL = ClassDesc.of("vsharp.runtime.VsDecimal");
    private static final ClassDesc CD_VSCHAR = ClassDesc.of("vsharp.runtime.VsChar");
    private static final ClassDesc CD_VSCONVERT = ClassDesc.of("vsharp.runtime.VsConvert");
    private static final ClassDesc CD_VSMATH = ClassDesc.of("vsharp.runtime.VsMath");
    private static final ClassDesc CD_VSSTRING = ClassDesc.of("vsharp.runtime.VsString");
    private static final ClassDesc CD_VSARRAY = ClassDesc.of("vsharp.runtime.VsArray");
    private static final ClassDesc CD_VSNUMFORMAT = ClassDesc.of("vsharp.runtime.VsNumberFormat");
    private static final ClassDesc CD_VSOBJECT = ClassDesc.of("vsharp.runtime.VsObject");
    private static final ClassDesc CD_BIG_DECIMAL = ClassDesc.of("java.math.BigDecimal");
    private static final Map<String, Target> TARGETS = buildTargets();

    private RuntimeLibrary() {
        throw new AssertionError("No instances");
    }

    /// The runtime method implementing `symbol`, or `null` when the member is unmapped.
    static Target lookup(FunctionSymbol symbol) {
        return TARGETS.get(signature(symbol));
    }

    /// The lookup key, and the text used in the diagnostic when a member is unmapped.
    static String signature(FunctionSymbol symbol) {
        StringBuilder key = new StringBuilder(symbol.qualifiedName()).append('(');
        boolean first = true;
        for (ParameterSymbol parameter : symbol.parameters()) {
            if (!first) {
                key.append(',');
            }
            key.append(parameter.type().displayName());
            first = false;
        }
        return key.append(')').toString();
    }

    private static Map<String, Target> buildTargets() {
        Map<String, Target> targets = new LinkedHashMap<>();

        // System.Console. The unsigned overloads exist because a `uint`/`ulong` argument is
        // indistinguishable from `int`/`long` once it is on the operand stack: the choice of
        // rendering has to be made here, while the V# type is still known.
        console(targets, "()", "", MethodTypeDesc.of(ConstantDescs.CD_void));
        console(targets, "(string)", "", writeDesc(ConstantDescs.CD_String));
        console(targets, "(object)", "", writeDesc(ConstantDescs.CD_Object));
        console(targets, "(bool)", "", writeDesc(ConstantDescs.CD_boolean));
        console(targets, "(char)", "", writeDesc(ConstantDescs.CD_char));
        console(targets, "(int)", "", writeDesc(ConstantDescs.CD_int));
        console(targets, "(uint)", "Unsigned", writeDesc(ConstantDescs.CD_int));
        console(targets, "(long)", "", writeDesc(ConstantDescs.CD_long));
        console(targets, "(ulong)", "Unsigned", writeDesc(ConstantDescs.CD_long));
        console(targets, "(float)", "", writeDesc(ConstantDescs.CD_float));
        console(targets, "(double)", "", writeDesc(ConstantDescs.CD_double));
        targets.put("System.Console.ReadLine()", new Target(CD_CONSOLE, "ReadLine",
                MethodTypeDesc.of(ConstantDescs.CD_String), false));

        // System.String instance members mapped onto java.lang.String. `Length` and
        // indexing are not here: they are IR nodes, not calls.
        targets.put("System.String.Substring(int)", new Target(ConstantDescs.CD_String, "substring",
                MethodTypeDesc.of(ConstantDescs.CD_String, ConstantDescs.CD_int), true));
        targets.put("System.String.Substring(int,int)", new Target(ConstantDescs.CD_String,
                "substring",
                MethodTypeDesc.of(ConstantDescs.CD_String, ConstantDescs.CD_int,
                        ConstantDescs.CD_int),
                true, ArgumentShape.STRING_START_LENGTH));

        // System.String static null/whitespace checks. `IsNullOrWhiteSpace` uses the
        // C# `char.IsWhiteSpace` BMP set, not Java's `String.isBlank()` (see VsString).
        stringCheck(targets, "IsNullOrEmpty", "isNullOrEmpty");
        stringCheck(targets, "IsNullOrWhiteSpace", "isNullOrWhiteSpace");

        // `Trim`/`TrimStart`/`TrimEnd` also use the exact C# whitespace set; Java's
        // `String.trim()` only strips ASCII <= ' ', so it would leave NBSP and NEL intact.
        stringTrim(targets, "Trim", "trim");
        stringTrim(targets, "TrimStart", "trimStart");
        stringTrim(targets, "TrimEnd", "trimEnd");
        stringInstance(targets, "ToUpperInvariant()", "toUpperInvariant",
                ConstantDescs.CD_String);
        stringInstance(targets, "ToLowerInvariant()", "toLowerInvariant",
                ConstantDescs.CD_String);

        // The ordinal search/replace family. Java's operations are ordinal and already
        // produce .NET's values; the helpers exist for C#'s argument contract (null argument,
        // empty `oldValue`, null `newValue` deleting) which Java's own methods do not share.
        stringInstance(targets, "Contains(string)", "contains", ConstantDescs.CD_boolean,
                ConstantDescs.CD_String);
        stringInstance(targets, "Contains(char)", "containsChar", ConstantDescs.CD_boolean,
                ConstantDescs.CD_char);
        stringInstance(targets, "IndexOf(char)", "indexOfChar", ConstantDescs.CD_int,
                ConstantDescs.CD_char);
        stringInstance(targets, "LastIndexOf(char)", "lastIndexOfChar", ConstantDescs.CD_int,
                ConstantDescs.CD_char);
        stringInstance(targets, "StartsWith(char)", "startsWithChar", ConstantDescs.CD_boolean,
                ConstantDescs.CD_char);
        stringInstance(targets, "EndsWith(char)", "endsWithChar", ConstantDescs.CD_boolean,
                ConstantDescs.CD_char);
        stringInstance(targets, "Replace(char,char)", "replaceChar", ConstantDescs.CD_String,
                ConstantDescs.CD_char, ConstantDescs.CD_char);
        stringInstance(targets, "Replace(string,string)", "replace", ConstantDescs.CD_String,
                ConstantDescs.CD_String, ConstantDescs.CD_String);

        // The separator-array split. The params array reaches the helper as a JVM
        // `char[]`, whether the call passed one normally or the binder expanded arguments,
        // and the result is an ordinary `String[]` the caller indexes and iterates.
        stringInstance(targets, "Split(char[])", "split",
                ConstantDescs.CD_String.arrayType(), ConstantDescs.CD_char.arrayType());

        // Ordinal string shaping. Padding, code-unit extraction, insertion and removal
        // are pure UTF-16 operations, so each maps to one receiver-static `VsString` call.
        stringInstance(targets, "PadLeft(int)", "padLeft", ConstantDescs.CD_String, ConstantDescs.CD_int);
        stringInstance(targets, "PadLeft(int,char)", "padLeft", ConstantDescs.CD_String,
                ConstantDescs.CD_int, ConstantDescs.CD_char);
        stringInstance(targets, "PadRight(int)", "padRight", ConstantDescs.CD_String, ConstantDescs.CD_int);
        stringInstance(targets, "PadRight(int,char)", "padRight", ConstantDescs.CD_String,
                ConstantDescs.CD_int, ConstantDescs.CD_char);
        stringInstance(targets, "ToCharArray()", "toCharArray", ConstantDescs.CD_char.arrayType());
        stringInstance(targets, "Insert(int,string)", "insert", ConstantDescs.CD_String,
                ConstantDescs.CD_int, ConstantDescs.CD_String);
        stringInstance(targets, "Remove(int)", "remove", ConstantDescs.CD_String, ConstantDescs.CD_int);
        stringInstance(targets, "Remove(int,int)", "remove", ConstantDescs.CD_String,
                ConstantDescs.CD_int, ConstantDescs.CD_int);

        // The curated standard numeric format specifiers. Each type reaches the
        // helper matching its carrier *and* signedness, because `uint` and `int` share the
        // `int` carrier but render differently under `D`.
        numberFormat(targets, "SByte", "formatSByte", ConstantDescs.CD_byte);
        numberFormat(targets, "Byte", "formatByte", ConstantDescs.CD_byte);
        numberFormat(targets, "Int16", "formatShort", ConstantDescs.CD_short);
        numberFormat(targets, "UInt16", "formatUShort", ConstantDescs.CD_short);
        numberFormat(targets, "Int32", "formatInt", ConstantDescs.CD_int);
        numberFormat(targets, "UInt32", "formatUInt", ConstantDescs.CD_int);
        numberFormat(targets, "Int64", "formatLong", ConstantDescs.CD_long);
        numberFormat(targets, "UInt64", "formatULong", ConstantDescs.CD_long);
        numberFormat(targets, "Single", "formatFloat", ConstantDescs.CD_float);
        numberFormat(targets, "Double", "formatDouble", ConstantDescs.CD_double);
        numberFormat(targets, "Decimal", "formatDecimal", CD_BIG_DECIMAL);

        // The curated System.Array statics. Unsigned element types share their signed
        // counterpart's JVM carrier, so `Sort` needs a distinct helper per V# element type
        // while `Reverse` and `IndexOf` - which move or compare bits - share one per carrier.
        arraySort(targets, "sbyte", "sortSByte", ConstantDescs.CD_byte);
        arraySort(targets, "byte", "sortByte", ConstantDescs.CD_byte);
        arraySort(targets, "short", "sortShort", ConstantDescs.CD_short);
        arraySort(targets, "ushort", "sortUShort", ConstantDescs.CD_short);
        arraySort(targets, "int", "sortInt", ConstantDescs.CD_int);
        arraySort(targets, "uint", "sortUInt", ConstantDescs.CD_int);
        arraySort(targets, "long", "sortLong", ConstantDescs.CD_long);
        arraySort(targets, "ulong", "sortULong", ConstantDescs.CD_long);
        arraySort(targets, "char", "sortChar", ConstantDescs.CD_char);
        arraySort(targets, "float", "sortFloat", ConstantDescs.CD_float);
        arraySort(targets, "double", "sortDouble", ConstantDescs.CD_double);

        arrayReverse(targets, "sbyte", "reverseByte", ConstantDescs.CD_byte);
        arrayReverse(targets, "byte", "reverseByte", ConstantDescs.CD_byte);
        arrayReverse(targets, "short", "reverseShort", ConstantDescs.CD_short);
        arrayReverse(targets, "ushort", "reverseShort", ConstantDescs.CD_short);
        arrayReverse(targets, "int", "reverseInt", ConstantDescs.CD_int);
        arrayReverse(targets, "uint", "reverseInt", ConstantDescs.CD_int);
        arrayReverse(targets, "long", "reverseLong", ConstantDescs.CD_long);
        arrayReverse(targets, "ulong", "reverseLong", ConstantDescs.CD_long);
        arrayReverse(targets, "char", "reverseChar", ConstantDescs.CD_char);
        arrayReverse(targets, "float", "reverseFloat", ConstantDescs.CD_float);
        arrayReverse(targets, "double", "reverseDouble", ConstantDescs.CD_double);
        arrayReverse(targets, "string", "reverseString", ConstantDescs.CD_String);

        arrayIndexOf(targets, "sbyte", "indexOfByte", ConstantDescs.CD_byte);
        arrayIndexOf(targets, "byte", "indexOfByte", ConstantDescs.CD_byte);
        arrayIndexOf(targets, "short", "indexOfShort", ConstantDescs.CD_short);
        arrayIndexOf(targets, "ushort", "indexOfShort", ConstantDescs.CD_short);
        arrayIndexOf(targets, "int", "indexOfInt", ConstantDescs.CD_int);
        arrayIndexOf(targets, "uint", "indexOfInt", ConstantDescs.CD_int);
        arrayIndexOf(targets, "long", "indexOfLong", ConstantDescs.CD_long);
        arrayIndexOf(targets, "ulong", "indexOfLong", ConstantDescs.CD_long);
        arrayIndexOf(targets, "char", "indexOfChar", ConstantDescs.CD_char);
        arrayIndexOf(targets, "float", "indexOfFloat", ConstantDescs.CD_float);
        arrayIndexOf(targets, "double", "indexOfDouble", ConstantDescs.CD_double);
        arrayIndexOf(targets, "string", "indexOfString", ConstantDescs.CD_String);

        // Culture-independent string-array composition. The params array is a JVM
        // String[] whether the call supplied it normally or the binder expanded arguments.
        targets.put("System.String.Concat(string[])",
                new Target(CD_VSSTRING, "concat",
                        MethodTypeDesc.of(ConstantDescs.CD_String,
                                ConstantDescs.CD_String.arrayType()),
                        Invocation.STATIC, ArgumentShape.DIRECT));
        targets.put("System.String.Join(string,string[])",
                new Target(CD_VSSTRING, "join",
                        MethodTypeDesc.of(ConstantDescs.CD_String, ConstantDescs.CD_String,
                                ConstantDescs.CD_String.arrayType()),
                        Invocation.STATIC, ArgumentShape.DIRECT));
        // Composite formatting. The params array is an `Object[]`, so each value
        // argument is boxed by the ordinary params expansion before the call.
        targets.put("System.String.Format(string,object[])",
                new Target(CD_VSSTRING, "format",
                        MethodTypeDesc.of(ConstantDescs.CD_String, ConstantDescs.CD_String,
                                ConstantDescs.CD_Object.arrayType()),
                        Invocation.STATIC, ArgumentShape.DIRECT));

        // `value.ToString()`. The receiver is already on the stack, so a static
        // formatter consumes it as its first argument: the same call the compiler emits for
        // `Console.Write`, which is why an unsigned type renders unsigned here too. `string`
        // is the one receiver whose own JVM method is already exact.
        toString(targets, "SByte", "", ConstantDescs.CD_int);
        // `byte`/`ushort` share their signed JVM carriers with `sbyte`/`short`, so their
        // formatter must recover the C# unsigned value before rendering it.
        toString(targets, "Byte", "Byte", ConstantDescs.CD_byte);
        toString(targets, "Int16", "", ConstantDescs.CD_int);
        toString(targets, "UInt16", "UShort", ConstantDescs.CD_short);
        toString(targets, "Int32", "", ConstantDescs.CD_int);
        toString(targets, "UInt32", "Unsigned", ConstantDescs.CD_int);
        toString(targets, "Int64", "", ConstantDescs.CD_long);
        toString(targets, "UInt64", "Unsigned", ConstantDescs.CD_long);
        toString(targets, "IntPtr", "", ConstantDescs.CD_long);
        toString(targets, "UIntPtr", "Unsigned", ConstantDescs.CD_long);
        toString(targets, "Single", "", ConstantDescs.CD_float);
        toString(targets, "Double", "", ConstantDescs.CD_double);
        toString(targets, "Boolean", "", ConstantDescs.CD_boolean);
        toString(targets, "Char", "", ConstantDescs.CD_char);
        targets.put("System.Object.ToString()",
                new Target(CD_VSFORMAT, "objectToString",
                        MethodTypeDesc.of(ConstantDescs.CD_String, ConstantDescs.CD_Object),
                        Invocation.RECEIVER_STATIC, ArgumentShape.DIRECT));
        targets.put("System.String.ToString()", new Target(ConstantDescs.CD_String, "toString",
                MethodTypeDesc.of(ConstantDescs.CD_String), true));
        targets.put("System.Decimal.ToString()",
                new Target(CD_VS_DECIMAL, "toDisplayString",
                        MethodTypeDesc.of(ConstantDescs.CD_String, CD_BIG_DECIMAL),
                        Invocation.RECEIVER_STATIC, ArgumentShape.DIRECT));

        // `value.Equals(object)` and `value.GetHashCode()`. Four carriers answer these
        // differently from the C# type they carry - `double`/`float` over NaN and negative
        // zero, `bool`/`char` over the hash constants, and `decimal` over trailing zeros - so
        // both members route through `VsObject` rather than the JVM method, on every type
        // alike. `object` and `string` dispatch there on their runtime carrier.
        universal(targets, "Object", ConstantDescs.CD_Object);
        universal(targets, "String", ConstantDescs.CD_Object);
        universal(targets, "Int32", ConstantDescs.CD_int);
        universal(targets, "Int64", ConstantDescs.CD_long);
        universal(targets, "Boolean", ConstantDescs.CD_boolean);
        universal(targets, "Char", ConstantDescs.CD_char);
        universal(targets, "Single", ConstantDescs.CD_float);
        universal(targets, "Double", ConstantDescs.CD_double);
        universal(targets, "Decimal", CD_BIG_DECIMAL);

        // The invariant string-only Int32 parsing family. The out parameter uses the
        // compiler's existing one-element cell ABI, so the runtime descriptor takes int[].
        targets.put("System.Int32.Parse(string)",
                new Target(CD_VSINT32, "parse",
                        MethodTypeDesc.of(ConstantDescs.CD_int, ConstantDescs.CD_String),
                        Invocation.STATIC, ArgumentShape.DIRECT));
        targets.put("System.Int32.TryParse(string,int)",
                new Target(CD_VSINT32, "tryParse",
                        MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_String,
                                ConstantDescs.CD_int.arrayType()),
                        Invocation.STATIC, ArgumentShape.DIRECT));

        // The invariant string-only Double parsing family. Modern .NET treats finite
        // overflow/underflow as successful infinity/zero results; VsDouble preserves the bits.
        targets.put("System.Double.Parse(string)",
                new Target(CD_VSDOUBLE, "parse",
                        MethodTypeDesc.of(ConstantDescs.CD_double, ConstantDescs.CD_String),
                        Invocation.STATIC, ArgumentShape.DIRECT));
        targets.put("System.Double.TryParse(string,double)",
                new Target(CD_VSDOUBLE, "tryParse",
                        MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_String,
                                ConstantDescs.CD_double.arrayType()),
                        Invocation.STATIC, ArgumentShape.DIRECT));

        // Boolean's complete culture-independent string parsing family.
        targets.put("System.Boolean.Parse(string)",
                new Target(CD_VSBOOLEAN, "parse",
                        MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_String),
                        Invocation.STATIC, ArgumentShape.DIRECT));
        targets.put("System.Boolean.TryParse(string,bool)",
                new Target(CD_VSBOOLEAN, "tryParse",
                        MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_String,
                                ConstantDescs.CD_boolean.arrayType()),
                        Invocation.STATIC, ArgumentShape.DIRECT));

        // The culture-independent System.Char operations. Classification matches
        // JDK 25 over the full BMP (apart from the already-explicit whitespace set); invariant
        // casing needs only the two Turkish-I guards owned by VsChar.
        charPredicate(targets, "IsDigit", "isDigit");
        charPredicate(targets, "IsLetter", "isLetter");
        charPredicate(targets, "IsLetterOrDigit", "isLetterOrDigit");
        charPredicate(targets, "IsWhiteSpace", "isWhiteSpace");
        charTransform(targets, "ToUpperInvariant", "toUpperInvariant");
        charTransform(targets, "ToLowerInvariant", "toLowerInvariant");

        // The bounded, culture-independent radix conversion pair.
        targets.put("System.Convert.ToString(int,int)",
                new Target(CD_VSCONVERT, "toString",
                        MethodTypeDesc.of(ConstantDescs.CD_String,
                                ConstantDescs.CD_int, ConstantDescs.CD_int),
                        Invocation.STATIC, ArgumentShape.DIRECT));
        targets.put("System.Convert.ToInt32(string,int)",
                new Target(CD_VSCONVERT, "toInt32",
                        MethodTypeDesc.of(ConstantDescs.CD_int,
                                ConstantDescs.CD_String, ConstantDescs.CD_int),
                        Invocation.STATIC, ArgumentShape.DIRECT));

        // System.Math.Abs. The signed integral overloads use VsMath rather than the
        // JDK `Math.abs` because C# throws OverflowException for the signed minimum while
        // Java silently wraps. The decimal overload uses the same decimal carrier every
        // other decimal operation uses.
        math(targets, "(sbyte)", "absSByte", ConstantDescs.CD_byte);
        math(targets, "(short)", "absShort", ConstantDescs.CD_short);
        math(targets, "(int)", "absInt", ConstantDescs.CD_int);
        math(targets, "(long)", "absLong", ConstantDescs.CD_long);
        math(targets, "(nint)", "absNInt", ConstantDescs.CD_long);
        math(targets, "(float)", "absFloat", ConstantDescs.CD_float);
        math(targets, "(double)", "absDouble", ConstantDescs.CD_double);
        targets.put("System.Math.Abs(decimal)",
                new Target(CD_VSMATH, "absDecimal",
                        MethodTypeDesc.of(CD_BIG_DECIMAL, CD_BIG_DECIMAL),
                        Invocation.STATIC, ArgumentShape.DIRECT));

        // System.Math.Max/Min. The full C# overload set is declared so the result type is
        // C#'s, not a widened carrier; unsigned forms compare unsigned bits.
        maxMin(targets, "sbyte", "maxSByte", "minSByte", ConstantDescs.CD_byte);
        maxMin(targets, "byte", "maxByte", "minByte", ConstantDescs.CD_byte);
        maxMin(targets, "short", "maxShort", "minShort", ConstantDescs.CD_short);
        maxMin(targets, "ushort", "maxUShort", "minUShort", ConstantDescs.CD_short);
        maxMin(targets, "int", "maxInt", "minInt", ConstantDescs.CD_int);
        maxMin(targets, "uint", "maxUInt", "minUInt", ConstantDescs.CD_int);
        maxMin(targets, "long", "maxLong", "minLong", ConstantDescs.CD_long);
        maxMin(targets, "ulong", "maxULong", "minULong", ConstantDescs.CD_long);
        maxMin(targets, "nint", "maxNInt", "minNInt", ConstantDescs.CD_long);
        maxMin(targets, "nuint", "maxNUInt", "minNUInt", ConstantDescs.CD_long);
        maxMin(targets, "float", "maxFloat", "minFloat", ConstantDescs.CD_float);
        maxMin(targets, "double", "maxDouble", "minDouble", ConstantDescs.CD_double);
        maxMin(targets, "decimal", "maxDecimal", "minDecimal", CD_BIG_DECIMAL);

        // System.Math.Clamp. The complete C# overload set preserves source result
        // types; unsigned and decimal forms need carrier-aware comparisons in VsMath.
        clamp(targets, "sbyte", "clampSByte", ConstantDescs.CD_byte);
        clamp(targets, "byte", "clampByte", ConstantDescs.CD_byte);
        clamp(targets, "short", "clampShort", ConstantDescs.CD_short);
        clamp(targets, "ushort", "clampUShort", ConstantDescs.CD_short);
        clamp(targets, "int", "clampInt", ConstantDescs.CD_int);
        clamp(targets, "uint", "clampUInt", ConstantDescs.CD_int);
        clamp(targets, "long", "clampLong", ConstantDescs.CD_long);
        clamp(targets, "ulong", "clampULong", ConstantDescs.CD_long);
        clamp(targets, "nint", "clampNInt", ConstantDescs.CD_long);
        clamp(targets, "nuint", "clampNUInt", ConstantDescs.CD_long);
        clamp(targets, "float", "clampFloat", ConstantDescs.CD_float);
        clamp(targets, "double", "clampDouble", ConstantDescs.CD_double);
        clamp(targets, "decimal", "clampDecimal", CD_BIG_DECIMAL);

        // System.Math.Sign returns `int` for every overload, including float/double/decimal.
        sign(targets, "sbyte", "signSByte", ConstantDescs.CD_byte);
        sign(targets, "short", "signShort", ConstantDescs.CD_short);
        sign(targets, "int", "signInt", ConstantDescs.CD_int);
        sign(targets, "long", "signLong", ConstantDescs.CD_long);
        sign(targets, "nint", "signNInt", ConstantDescs.CD_long);
        sign(targets, "float", "signFloat", ConstantDescs.CD_float);
        sign(targets, "double", "signDouble", ConstantDescs.CD_double);
        sign(targets, "decimal", "signDecimal", CD_BIG_DECIMAL);

        // System.Math.Floor/Ceiling/Truncate are double -> double and map to VsMath so
        // Truncate's toward-zero rule lives beside Floor/Ceiling in one authority.
        mathDouble(targets, "Floor", "floor");
        mathDouble(targets, "Ceiling", "ceiling");
        mathDouble(targets, "Truncate", "truncate");

        // System.Math.Sqrt/Pow/Log. VsMath.pow repairs the six .NET/JDK
        // category differences around +/-1 with NaN/infinite exponents and normalizes
        // the otherwise-different NaN sign bit.
        mathDouble(targets, "Sqrt", "sqrt");
        mathDoubleBinary(targets, "Pow", "pow");
        mathDouble(targets, "Log", "log");

        // The workload-selected direct trigonometric trio. A 32,794-record
        // .NET/JDK comparison bounds every finite difference to one ULP and proves
        // exact categories plus exact signed-zero and NaN edge bits.
        mathDouble(targets, "Sin", "sin");
        mathDouble(targets, "Cos", "cos");
        mathDouble(targets, "Tan", "tan");

        // System.Math.Round. `MidpointRounding` is a corelib enum, so V# carries it
        // as an `int` and the runtime helpers take the mode as one: the mode parameter is
        // spelled `System.MidpointRounding` in the V# signature key but `int` in the
        // descriptor. The decimal forms take the CLR envelope carrier.
        round(targets, "(double)", "round", ConstantDescs.CD_double, ConstantDescs.CD_double);
        round(targets, "(double,int)", "roundDigits", ConstantDescs.CD_double,
                ConstantDescs.CD_double, ConstantDescs.CD_int);
        round(targets, "(double,System.MidpointRounding)", "roundMode", ConstantDescs.CD_double,
                ConstantDescs.CD_double, ConstantDescs.CD_int);
        round(targets, "(double,int,System.MidpointRounding)", "roundDigitsMode",
                ConstantDescs.CD_double, ConstantDescs.CD_double,
                ConstantDescs.CD_int, ConstantDescs.CD_int);
        round(targets, "(decimal)", "roundDecimal", CD_BIG_DECIMAL, CD_BIG_DECIMAL);
        round(targets, "(decimal,int)", "roundDecimalDigits", CD_BIG_DECIMAL,
                CD_BIG_DECIMAL, ConstantDescs.CD_int);
        round(targets, "(decimal,System.MidpointRounding)", "roundDecimalMode", CD_BIG_DECIMAL,
                CD_BIG_DECIMAL, ConstantDescs.CD_int);
        round(targets, "(decimal,int,System.MidpointRounding)", "roundDecimalDigitsMode",
                CD_BIG_DECIMAL, CD_BIG_DECIMAL, ConstantDescs.CD_int, ConstantDescs.CD_int);

        return Map.copyOf(targets);
    }

    /// Registers the `Write`/`WriteLine` pair for one parameter spelling; both differ only in
    /// the line terminator, so declaring them together keeps the two families in step.
    private static void console(Map<String, Target> targets, String parameters, String suffix,
            MethodTypeDesc desc) {
        targets.put("System.Console.WriteLine" + parameters,
                new Target(CD_CONSOLE, "WriteLine" + suffix, desc, false));
        if (!parameters.equals("()")) {
            targets.put("System.Console.Write" + parameters,
                    new Target(CD_CONSOLE, "Write" + suffix, desc, false));
        }
    }

    /// Registers a builtin type's `ToString()` as a static `VsFormat` call taking the receiver.
    /// `suffix` selects the unsigned rendering for the types whose carrier cannot say so itself.
    private static void toString(Map<String, Target> targets, String type, String suffix,
            ClassDesc carrier) {
        targets.put("System." + type + ".ToString()",
                new Target(CD_VSFORMAT, "toDisplayString" + suffix,
                        MethodTypeDesc.of(ConstantDescs.CD_String, carrier),
                        Invocation.RECEIVER_STATIC, ArgumentShape.DIRECT));
    }

    /// Registers one type's `Equals(object)`/`GetHashCode()`/`GetType()` trio as static
    /// `VsObject` calls taking the receiver. All three are declared together in `corelib.vs`,
    /// so registering them together keeps a type from gaining one without the others.
    ///
    /// `GetType()` returns `java.lang.Class`, which *is* V#'s `System.Type`. A value
    /// receiver reports its boxed carrier - `int` answers `Integer.class`, never `int.class` -
    /// so `int x = 5; object o = x;` cannot make the same value report two different types.
    private static void universal(Map<String, Target> targets, String type, ClassDesc carrier) {
        targets.put("System." + type + ".GetType()",
                new Target(CD_VSOBJECT, "type",
                        MethodTypeDesc.of(ConstantDescs.CD_Class, carrier),
                        Invocation.RECEIVER_STATIC, ArgumentShape.DIRECT));
        targets.put("System." + type + ".Equals(object)",
                new Target(CD_VSOBJECT, "equals",
                        MethodTypeDesc.of(ConstantDescs.CD_boolean, carrier,
                                ConstantDescs.CD_Object),
                        Invocation.RECEIVER_STATIC, ArgumentShape.DIRECT));
        targets.put("System." + type + ".GetHashCode()",
                new Target(CD_VSOBJECT, "hash",
                        MethodTypeDesc.of(ConstantDescs.CD_int, carrier),
                        Invocation.RECEIVER_STATIC, ArgumentShape.DIRECT));
    }

    /// Registers one `System.Math.Abs` overload as a static `VsMath` call.
    private static void math(Map<String, Target> targets, String parameters, String method,
            ClassDesc carrier) {
        targets.put("System.Math.Abs" + parameters,
                new Target(CD_VSMATH, method,
                        MethodTypeDesc.of(carrier, carrier),
                        Invocation.STATIC, ArgumentShape.DIRECT));
    }

    /// Registers the `System.Math.Max`/`Min` pair for one parameter spelling.
    private static void maxMin(Map<String, Target> targets, String type, String maxMethod,
            String minMethod, ClassDesc carrier) {
        MethodTypeDesc desc = MethodTypeDesc.of(carrier, carrier, carrier);
        targets.put("System.Math.Max(" + type + "," + type + ")",
                new Target(CD_VSMATH, maxMethod, desc, Invocation.STATIC, ArgumentShape.DIRECT));
        targets.put("System.Math.Min(" + type + "," + type + ")",
                new Target(CD_VSMATH, minMethod, desc, Invocation.STATIC, ArgumentShape.DIRECT));
    }

    /// Registers one `System.Math.Clamp` overload as a static three-argument `VsMath` call.
    private static void clamp(Map<String, Target> targets, String type, String method,
            ClassDesc carrier) {
        targets.put("System.Math.Clamp(" + type + "," + type + "," + type + ")",
                new Target(CD_VSMATH, method,
                        MethodTypeDesc.of(carrier, carrier, carrier, carrier),
                        Invocation.STATIC, ArgumentShape.DIRECT));
    }

    /// Registers one `System.Math.Sign` overload as a static `VsMath` call returning `int`.
    private static void sign(Map<String, Target> targets, String type, String method,
            ClassDesc carrier) {
        targets.put("System.Math.Sign(" + type + ")",
                new Target(CD_VSMATH, method,
                        MethodTypeDesc.of(ConstantDescs.CD_int, carrier),
                        Invocation.STATIC, ArgumentShape.DIRECT));
    }

    /// Registers one double-to-double `System.Math` member as a static `VsMath` call.
    private static void mathDouble(Map<String, Target> targets, String member, String method) {
        targets.put("System.Math." + member + "(double)",
                new Target(CD_VSMATH, method,
                        MethodTypeDesc.of(ConstantDescs.CD_double, ConstantDescs.CD_double),
                        Invocation.STATIC, ArgumentShape.DIRECT));
    }

    /// Registers one double-pair-to-double `System.Math` member as a static `VsMath` call.
    private static void mathDoubleBinary(Map<String, Target> targets, String member, String method) {
        targets.put("System.Math." + member + "(double,double)",
                new Target(CD_VSMATH, method,
                        MethodTypeDesc.of(ConstantDescs.CD_double,
                                ConstantDescs.CD_double, ConstantDescs.CD_double),
                        Invocation.STATIC, ArgumentShape.DIRECT));
    }

    /// Registers one `System.Math.Round` overload as a static `VsMath` call.
    private static void round(Map<String, Target> targets, String parameters, String method,
            ClassDesc returns, ClassDesc... carriers) {
        targets.put("System.Math.Round" + parameters,
                new Target(CD_VSMATH, method, MethodTypeDesc.of(returns, carriers),
                        Invocation.STATIC, ArgumentShape.DIRECT));
    }

    /// Registers one static `System.Char` predicate as a `VsChar` call.
    private static void charPredicate(Map<String, Target> targets, String member, String method) {
        targets.put("System.Char." + member + "(char)",
                new Target(CD_VSCHAR, method,
                        MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_char),
                        Invocation.STATIC, ArgumentShape.DIRECT));
    }

    /// Registers one invariant static `System.Char` transform as a `VsChar` call.
    private static void charTransform(Map<String, Target> targets, String member, String method) {
        targets.put("System.Char." + member + "(char)",
                new Target(CD_VSCHAR, method,
                        MethodTypeDesc.of(ConstantDescs.CD_char, ConstantDescs.CD_char),
                        Invocation.STATIC, ArgumentShape.DIRECT));
    }

    /// Registers one static `System.String` check as a `VsString` call.
    private static void stringCheck(Map<String, Target> targets, String member, String method) {
        targets.put("System.String." + member + "(string)",
                new Target(CD_VSSTRING, method,
                        MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_String),
                        Invocation.STATIC, ArgumentShape.DIRECT));
    }

    /// Registers one instance `System.String` trim member as a `VsString` call taking the
    /// receiver as its first argument.
    private static void stringTrim(Map<String, Target> targets, String member, String method) {
        targets.put("System.String." + member + "()",
                new Target(CD_VSSTRING, method,
                        MethodTypeDesc.of(ConstantDescs.CD_String, ConstantDescs.CD_String),
                        Invocation.RECEIVER_STATIC, ArgumentShape.DIRECT));
    }

    /// Registers one instance `System.String` member as a `VsString` call whose first
    /// argument is the receiver already on the stack. `signature` is the V# spelling
    /// (`Contains(char)`), `parameters` the JVM descriptors that follow the receiver.
    private static void stringInstance(Map<String, Target> targets, String signature,
            String method, ClassDesc returns, ClassDesc... parameters) {
        ClassDesc[] descriptors = new ClassDesc[parameters.length + 1];
        descriptors[0] = ConstantDescs.CD_String;
        System.arraycopy(parameters, 0, descriptors, 1, parameters.length);
        targets.put("System.String." + signature,
                new Target(CD_VSSTRING, method, MethodTypeDesc.of(returns, descriptors),
                        Invocation.RECEIVER_STATIC, ArgumentShape.DIRECT));
    }

    /// Registers a builtin type's `ToString(string)` as a static `VsNumberFormat` call
    /// taking the receiver followed by the format string.
    private static void numberFormat(Map<String, Target> targets, String type, String method,
            ClassDesc carrier) {
        targets.put("System." + type + ".ToString(string)",
                new Target(CD_VSNUMFORMAT, method,
                        MethodTypeDesc.of(ConstantDescs.CD_String, carrier, ConstantDescs.CD_String),
                        Invocation.RECEIVER_STATIC, ArgumentShape.DIRECT));
    }

    /// Registers `System.Array.Sort(T[])` for the V# element type `spelling`, whose JVM
    /// carrier is `carrier`.
    private static void arraySort(Map<String, Target> targets, String spelling, String method,
            ClassDesc carrier) {
        targets.put("System.Array.Sort(" + spelling + "[])",
                new Target(CD_VSARRAY, method,
                        MethodTypeDesc.of(ConstantDescs.CD_void, carrier.arrayType()),
                        Invocation.STATIC, ArgumentShape.DIRECT));
    }

    /// Registers `System.Array.Reverse(T[])`.
    private static void arrayReverse(Map<String, Target> targets, String spelling, String method,
            ClassDesc carrier) {
        targets.put("System.Array.Reverse(" + spelling + "[])",
                new Target(CD_VSARRAY, method,
                        MethodTypeDesc.of(ConstantDescs.CD_void, carrier.arrayType()),
                        Invocation.STATIC, ArgumentShape.DIRECT));
    }

    /// Registers `System.Array.IndexOf(T[], T)`.
    private static void arrayIndexOf(Map<String, Target> targets, String spelling, String method,
            ClassDesc carrier) {
        targets.put("System.Array.IndexOf(" + spelling + "[]," + spelling + ")",
                new Target(CD_VSARRAY, method,
                        MethodTypeDesc.of(ConstantDescs.CD_int, carrier.arrayType(), carrier),
                        Invocation.STATIC, ArgumentShape.DIRECT));
    }

    private static MethodTypeDesc writeDesc(ClassDesc parameter) {
        return MethodTypeDesc.of(ConstantDescs.CD_void, parameter);
    }
}
