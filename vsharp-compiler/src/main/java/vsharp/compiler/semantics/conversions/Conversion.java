package vsharp.compiler.semantics.conversions;

import java.util.Objects;

/// Immutable representation of a C# 13 type conversion classification.
public record Conversion(ConversionKind kind) {
    public static final Conversion NONE = new Conversion(ConversionKind.NONE);
    public static final Conversion IDENTITY = new Conversion(ConversionKind.IDENTITY);
    public static final Conversion IMPLICIT_NUMERIC = new Conversion(ConversionKind.IMPLICIT_NUMERIC);
    public static final Conversion IMPLICIT_CONSTANT = new Conversion(ConversionKind.IMPLICIT_CONSTANT);
    public static final Conversion IMPLICIT_NULL = new Conversion(ConversionKind.IMPLICIT_NULL);
    public static final Conversion IMPLICIT_REFERENCE = new Conversion(ConversionKind.IMPLICIT_REFERENCE);
    public static final Conversion IMPLICIT_NULLABLE = new Conversion(ConversionKind.IMPLICIT_NULLABLE);
    public static final Conversion IMPLICIT_TUPLE = new Conversion(ConversionKind.IMPLICIT_TUPLE);
    public static final Conversion IMPLICIT_LAMBDA = new Conversion(ConversionKind.IMPLICIT_LAMBDA);
    public static final Conversion EXPLICIT_NUMERIC = new Conversion(ConversionKind.EXPLICIT_NUMERIC);
    public static final Conversion EXPLICIT_ENUM = new Conversion(ConversionKind.EXPLICIT_ENUM);
    public static final Conversion EXPLICIT_REFERENCE = new Conversion(ConversionKind.EXPLICIT_REFERENCE);
    public static final Conversion EXPLICIT_NULLABLE = new Conversion(ConversionKind.EXPLICIT_NULLABLE);
    public static final Conversion EXPLICIT_TUPLE = new Conversion(ConversionKind.EXPLICIT_TUPLE);
    public static final Conversion BOXING = new Conversion(ConversionKind.BOXING);
    public static final Conversion UNBOXING = new Conversion(ConversionKind.UNBOXING);

    public Conversion {
        Objects.requireNonNull(kind, "kind");
    }

    public boolean exists() {
        return kind != ConversionKind.NONE;
    }

    public boolean isImplicit() {
        return kind == ConversionKind.IDENTITY
                || kind == ConversionKind.IMPLICIT_NUMERIC
                || kind == ConversionKind.IMPLICIT_CONSTANT
                || kind == ConversionKind.IMPLICIT_NULL
                || kind == ConversionKind.IMPLICIT_REFERENCE
                || kind == ConversionKind.IMPLICIT_NULLABLE
                || kind == ConversionKind.IMPLICIT_TUPLE
                || kind == ConversionKind.IMPLICIT_LAMBDA
                || kind == ConversionKind.BOXING;
    }

    public boolean isExplicit() {
        return kind == ConversionKind.EXPLICIT_NUMERIC
                || kind == ConversionKind.EXPLICIT_ENUM
                || kind == ConversionKind.EXPLICIT_REFERENCE
                || kind == ConversionKind.EXPLICIT_NULLABLE
                || kind == ConversionKind.EXPLICIT_TUPLE
                || kind == ConversionKind.UNBOXING;
    }

    public boolean isIdentity() {
        return kind == ConversionKind.IDENTITY;
    }
}
