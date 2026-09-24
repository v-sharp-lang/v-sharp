package vsharp.compiler.syntax;

/// The C# type a literal token denotes, decided lexically.
///
/// C# fixes a literal's type at the lexical level — `1` is `int`, `1u` is `uint`,
/// `4000000000` is `uint` because it does not fit `int` — so the decision belongs here
/// rather than in the binder. The binder still applies constant conversions on top.
public enum LiteralType {

    /// No literal value: the token is not a literal.
    NONE,

    /// `int`. Token value is an `Integer`.
    INT,

    /// `uint`. Token value is an `Integer` holding the raw 32 bits.
    UINT,

    /// `long`. Token value is a `Long`.
    LONG,

    /// `ulong`. Token value is a `Long` holding the raw 64 bits.
    ULONG,

    /// `float`. Token value is a `Float`.
    FLOAT,

    /// `double`. Token value is a `Double`.
    DOUBLE,

    /// `decimal`. Token value is a `java.math.BigDecimal`.
    DECIMAL,

    /// `char`. Token value is a `Character`.
    CHAR,

    /// `string`. Token value is a `String`.
    STRING,

    /// `ReadOnlySpan<byte>` in C#; a `byte[]` here. Token value is a `byte[]`.
    UTF8,

    /// `bool`. Token value is a `Boolean`.
    BOOL,

    /// The `null` literal. Token value is `null`.
    NULL,

    /// An interpolated string. Token value is a `List<InterpolationPart>`.
    INTERPOLATED;

    /// Whether this is one of the integral types.
    public boolean isIntegral() {
        return this == INT || this == UINT || this == LONG || this == ULONG;
    }

    /// Whether this type is unsigned, which decides which arithmetic helpers apply.
    public boolean isUnsigned() {
        return this == UINT || this == ULONG;
    }
}
