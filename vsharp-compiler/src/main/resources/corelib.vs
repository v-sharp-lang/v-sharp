namespace System
{

    // The declarations a keyword type resolves to during member lookup. Only members whose
    // C# contract maps exactly onto a JVM operation are declared: `ToString()` renders
    // through the same runtime formatter `Console.WriteLine` uses, so an unsigned value
    // reads as unsigned. `CompareTo` is deliberately absent - see docs/FEATURE-MATRIX.md (D65).
    //
    // `Equals(object)`, `GetHashCode()` and `GetType()` are declared on the types whose boxed
    // JVM carrier is exactly the C# type: `object`, `string`, `bool`, `char`, `int`, `long`,
    // `float`, `double` and `decimal` (D138, D140). `sbyte`/`short` box as `Integer` and the
    // unsigned family shares its carrier with a signed type, so `Equals`'s type test would
    // answer `true` where C# answers `false` and `GetType()` would name the signed sibling;
    // the three members stay absent there until the carrier can say which C# type it holds.
    //
    // `System.Type` is `java.lang.Class` on this platform (D140), so the whole JDK reflection
    // surface is reachable from a `GetType()` result and a `Class` crossing back from Java is
    // the same V# type. The cost is that a `Type` names its JVM carrier - `int.GetType()`
    // reads `class java.lang.Integer`, not C#'s `System.Int32`.
    public struct Object
    {
        public extern string ToString();
        public extern bool Equals(object obj);
        public extern int GetHashCode();
        public extern Type GetType();
    }
    public struct String
    {
        public extern bool Equals(object obj);
        public extern int GetHashCode();
        public extern Type GetType();
        public extern string Substring(int startIndex);
        public extern string Substring(int startIndex, int length);
        public extern string ToString();
        public extern string Trim();
        public extern string TrimStart();
        public extern string TrimEnd();
        public extern string ToUpperInvariant();
        public extern string ToLowerInvariant();
        // The ordinal search and replace family (D109). C#'s `IndexOf(string)`,
        // `StartsWith(string)`, `EndsWith(string)`, `ToUpper` and `ToLower` compare with the
        // current culture and stay absent: V# has no culture model to be faithful to. The
        // invariant casing pair above and the ordinal members below are exact (D128/D109).
        public extern bool Contains(string value);
        public extern bool Contains(char value);
        public extern int IndexOf(char value);
        public extern int LastIndexOf(char value);
        public extern bool StartsWith(char value);
        public extern bool EndsWith(char value);
        public extern string Replace(char oldChar, char newChar);
        public extern string Replace(string oldValue, string newValue);
        // The separator-array split (D119). The `string`-separator, `StringSplitOptions` and
        // count-limited overloads stay absent: they need an options enum and a distinct
        // multi-character search, neither of which this increment admits.
        public extern string[] Split(params char[] separator);
        // Ordinal string shaping (D121). Every member below is a pure code-unit operation with
        // no culture, format or comparison model; `ToCharArray(int,int)` and the `Substring`-like
        // shapes already covered by D59 stay out.
        public extern string PadLeft(int totalWidth);
        public extern string PadLeft(int totalWidth, char paddingChar);
        public extern string PadRight(int totalWidth);
        public extern string PadRight(int totalWidth, char paddingChar);
        public extern char[] ToCharArray();
        public extern string Insert(int startIndex, string value);
        public extern string Remove(int startIndex);
        public extern string Remove(int startIndex, int count);
        public static extern bool IsNullOrEmpty(string value);
        public static extern bool IsNullOrWhiteSpace(string value);
        // C# 13 exposes params ReadOnlySpan overloads as well as array overloads. V# uses
        // the equivalent string-array params shape and reproduces the zero-value ambiguity
        // in binding; see D111.
        public static extern string Concat(params string[] values);
        public static extern string Join(string separator, params string[] value);
        // Composite formatting, the run-time twin of interpolation (D190). The same grammar
        // and the same renderers, parsed at run time because the format text is a value here
        // rather than a literal. The `IFormatProvider` overloads stay absent under D59: they
        // exist to select a culture, and V# has none.
        public static extern string Format(string format, params object[] args);
    }
    // `ToString(string)` admits only the standard `D`, `X` and `F` specifiers under D116's
    // invariant contract (D124). `G`, `N`, `C`, `E`, `P` and `R` need a culture model and are
    // refused at runtime, as are custom format strings; the provider overloads stay absent.
    public struct SByte
    {
        public extern string ToString();
        public extern string ToString(string format);
    }
    public struct Byte
    {
        public extern string ToString();
        public extern string ToString(string format);
    }
    public struct Int16
    {
        public extern string ToString();
        public extern string ToString(string format);
    }
    public struct UInt16
    {
        public extern string ToString();
        public extern string ToString(string format);
    }
    public struct Int32
    {
        public extern string ToString();
        public extern bool Equals(object obj);
        public extern int GetHashCode();
        public extern Type GetType();
        public extern string ToString(string format);
        // The invariant string forms only (D116). Style/provider, Span and UTF-8 overloads
        // depend on library surfaces V# deliberately does not expose.
        public static extern int Parse(string s);
        public static extern bool TryParse(string s, out int result);
    }
    public struct UInt32
    {
        public extern string ToString();
        public extern string ToString(string format);
    }
    public struct Int64
    {
        public extern string ToString();
        public extern string ToString(string format);
        public extern bool Equals(object obj);
        public extern int GetHashCode();
        public extern Type GetType();
    }
    public struct UInt64
    {
        public extern string ToString();
        public extern string ToString(string format);
    }
    public struct IntPtr
    {
        public extern string ToString();
    }
    public struct UIntPtr
    {
        public extern string ToString();
    }
    public struct Decimal
    {
        public extern string ToString();
        public extern string ToString(string format);
        public extern bool Equals(object obj);
        public extern int GetHashCode();
        public extern Type GetType();
    }
    public struct Single
    {
        public extern string ToString();
        public extern string ToString(string format);
        public extern bool Equals(object obj);
        public extern int GetHashCode();
        public extern Type GetType();
    }
    public struct Double
    {
        public extern string ToString();
        public extern bool Equals(object obj);
        public extern int GetHashCode();
        public extern Type GetType();
        public extern string ToString(string format);
        // Deterministic invariant-culture string forms only (D117). The provider/style,
        // Span and UTF-8 overloads remain outside the curated library surface.
        public static extern double Parse(string s);
        public static extern bool TryParse(string s, out double result);
    }
    public struct Boolean
    {
        public extern string ToString();
        public extern bool Equals(object obj);
        public extern int GetHashCode();
        public extern Type GetType();
        // The complete culture-independent Boolean string family (D118).
        public static extern bool Parse(string value);
        public static extern bool TryParse(string value, out bool result);
    }
    public struct Char
    {
        public extern string ToString();
        public extern bool Equals(object obj);
        public extern int GetHashCode();
        public extern Type GetType();
        public static extern bool IsDigit(char value);
        public static extern bool IsLetter(char value);
        public static extern bool IsLetterOrDigit(char value);
        public static extern bool IsWhiteSpace(char value);
        public static extern char ToUpperInvariant(char value);
        public static extern char ToLowerInvariant(char value);
        // ToUpper/ToLower use the current culture in C# and remain absent under D59; the
        // explicitly invariant pair above is exact over the full BMP (D126).
    }
    public struct Exception
    {
    }
    // Thrown by `checked` arithmetic and `checked` conversions (D21). Carried by
    // `java.lang.ArithmeticException`, which is also what the JVM throws for `x / 0`.
    public struct OverflowException
    {
    }
    // JVM integer division already raises the same ArithmeticException carrier used for
    // OverflowException. The shared-carrier consequence is documented in D126.
    public struct DivideByZeroException
    {
    }
    // The exceptions the JVM itself raises for the operations C# names differently, so an
    // ordinary `catch (IndexOutOfRangeException)` catches the array access that raised it
    // (D98). Each maps to a distinct JVM class, and `Exception` remains the one that catches
    // them all, matching C#'s root.
    public struct IndexOutOfRangeException
    {
    }
    public struct NullReferenceException
    {
    }
    public struct InvalidCastException
    {
    }
    public struct InvalidOperationException
    {
    }
    public struct ArgumentException
    {
    }
    // Kept distinct from ArgumentException: those types are siblings in .NET, so Java's
    // ArgumentException-derived NumberFormatException is not a faithful carrier (D116).
    public struct FormatException
    {
    }

    // The two culture-independent radix forms. Other Convert
    // overloads remain outside this closed increment rather than growing a conversion facade.
    public struct Convert
    {
        public static extern string ToString(int value, int toBase);
        public static extern int ToInt32(string value, int fromBase);
    }

    // The curated System.Array statics (D123). Every admitted member is a total, ordinal
    // operation over one primitive element type. `Sort(string[])` is absent because .NET
    // orders strings with the current culture (D59), while `IndexOf(string[], string)` is
    // present because it is ordinal - both were measured against the .NET 10 oracle.
    // `decimal[]` is absent because .NET's unstable sort and the JDK's stable one can order
    // equal-comparing decimals of different scale differently. `bool[]`, `object[]`,
    // `nint`/`nuint`, the index/length-limited overloads, BinarySearch, Copy, Clear and
    // Resize are all out of this increment.
    public struct Array
    {
        public static extern void Sort(sbyte[] array);
        public static extern void Sort(byte[] array);
        public static extern void Sort(short[] array);
        public static extern void Sort(ushort[] array);
        public static extern void Sort(int[] array);
        public static extern void Sort(uint[] array);
        public static extern void Sort(long[] array);
        public static extern void Sort(ulong[] array);
        public static extern void Sort(char[] array);
        public static extern void Sort(float[] array);
        public static extern void Sort(double[] array);

        public static extern void Reverse(sbyte[] array);
        public static extern void Reverse(byte[] array);
        public static extern void Reverse(short[] array);
        public static extern void Reverse(ushort[] array);
        public static extern void Reverse(int[] array);
        public static extern void Reverse(uint[] array);
        public static extern void Reverse(long[] array);
        public static extern void Reverse(ulong[] array);
        public static extern void Reverse(char[] array);
        public static extern void Reverse(float[] array);
        public static extern void Reverse(double[] array);
        public static extern void Reverse(string[] array);

        public static extern int IndexOf(sbyte[] array, sbyte value);
        public static extern int IndexOf(byte[] array, byte value);
        public static extern int IndexOf(short[] array, short value);
        public static extern int IndexOf(ushort[] array, ushort value);
        public static extern int IndexOf(int[] array, int value);
        public static extern int IndexOf(uint[] array, uint value);
        public static extern int IndexOf(long[] array, long value);
        public static extern int IndexOf(ulong[] array, ulong value);
        public static extern int IndexOf(char[] array, char value);
        public static extern int IndexOf(float[] array, float value);
        public static extern int IndexOf(double[] array, double value);
        public static extern int IndexOf(string[] array, string value);
    }

    public struct Math
    {
        public static extern sbyte Abs(sbyte value);
        public static extern short Abs(short value);
        public static extern int Abs(int value);
        public static extern long Abs(long value);
        public static extern nint Abs(nint value);
        public static extern float Abs(float value);
        public static extern double Abs(double value);
        public static extern decimal Abs(decimal value);

        public static extern sbyte Max(sbyte value1, sbyte value2);
        public static extern sbyte Min(sbyte value1, sbyte value2);
        public static extern byte Max(byte value1, byte value2);
        public static extern byte Min(byte value1, byte value2);
        public static extern short Max(short value1, short value2);
        public static extern short Min(short value1, short value2);
        public static extern ushort Max(ushort value1, ushort value2);
        public static extern ushort Min(ushort value1, ushort value2);
        public static extern int Max(int value1, int value2);
        public static extern int Min(int value1, int value2);
        public static extern uint Max(uint value1, uint value2);
        public static extern uint Min(uint value1, uint value2);
        public static extern long Max(long value1, long value2);
        public static extern long Min(long value1, long value2);
        public static extern ulong Max(ulong value1, ulong value2);
        public static extern ulong Min(ulong value1, ulong value2);
        public static extern nint Max(nint value1, nint value2);
        public static extern nint Min(nint value1, nint value2);
        public static extern nuint Max(nuint value1, nuint value2);
        public static extern nuint Min(nuint value1, nuint value2);
        public static extern float Max(float value1, float value2);
        public static extern float Min(float value1, float value2);
        public static extern double Max(double value1, double value2);
        public static extern double Min(double value1, double value2);
        public static extern decimal Max(decimal value1, decimal value2);
        public static extern decimal Min(decimal value1, decimal value2);

        public static extern sbyte Clamp(sbyte value, sbyte min, sbyte max);
        public static extern byte Clamp(byte value, byte min, byte max);
        public static extern short Clamp(short value, short min, short max);
        public static extern ushort Clamp(ushort value, ushort min, ushort max);
        public static extern int Clamp(int value, int min, int max);
        public static extern uint Clamp(uint value, uint min, uint max);
        public static extern long Clamp(long value, long min, long max);
        public static extern ulong Clamp(ulong value, ulong min, ulong max);
        public static extern nint Clamp(nint value, nint min, nint max);
        public static extern nuint Clamp(nuint value, nuint min, nuint max);
        public static extern float Clamp(float value, float min, float max);
        public static extern double Clamp(double value, double min, double max);
        public static extern decimal Clamp(decimal value, decimal min, decimal max);

        public static extern int Sign(sbyte value);
        public static extern int Sign(short value);
        public static extern int Sign(int value);
        public static extern int Sign(long value);
        public static extern int Sign(nint value);
        public static extern int Sign(float value);
        public static extern int Sign(double value);
        public static extern int Sign(decimal value);

        public static extern double Floor(double value);
        public static extern double Ceiling(double value);
        public static extern double Truncate(double value);
        public static extern double Sqrt(double d);
        public static extern double Pow(double x, double y);
        public static extern double Log(double d);
        public static extern double Sin(double a);
        public static extern double Cos(double d);
        public static extern double Tan(double a);

        // The rounding family (D114). `MidpointRounding` is the only enum the curated
        // library declares; it is `int`-carried like every V# enum, so the runtime
        // targets below take the mode as a plain `int`.
        public static extern double Round(double value);
        public static extern double Round(double value, int digits);
        public static extern double Round(double value, MidpointRounding mode);
        public static extern double Round(double value, int digits, MidpointRounding mode);
        public static extern decimal Round(decimal d);
        public static extern decimal Round(decimal d, int decimals);
        public static extern decimal Round(decimal d, MidpointRounding mode);
        public static extern decimal Round(decimal d, int decimals, MidpointRounding mode);
    }

    /// The midpoint policies `System.Math.Round` accepts. The values are C#'s own and are
    /// the wire format the runtime helpers switch over.
    public enum MidpointRounding
    {
        ToEven = 0,
        AwayFromZero = 1,
        ToZero = 2,
        ToNegativeInfinity = 3,
        ToPositiveInfinity = 4
    }

    public struct Console
    {
        public static extern void WriteLine();
        public static extern void WriteLine(string value);
        public static extern void WriteLine(object value);
        public static extern void WriteLine(bool value);
        public static extern void WriteLine(char value);
        public static extern void WriteLine(int value);
        public static extern void WriteLine(uint value);
        public static extern void WriteLine(long value);
        public static extern void WriteLine(ulong value);
        public static extern void WriteLine(float value);
        public static extern void WriteLine(double value);

        public static extern void Write(string value);
        public static extern void Write(object value);
        public static extern void Write(bool value);
        public static extern void Write(char value);
        public static extern void Write(int value);
        public static extern void Write(uint value);
        public static extern void Write(long value);
        public static extern void Write(ulong value);
        public static extern void Write(float value);
        public static extern void Write(double value);

        public static extern string ReadLine();
    }
}
