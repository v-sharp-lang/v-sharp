package vsharp.compiler.semantics.constants;

import java.math.BigDecimal;
import java.util.Objects;
import vsharp.compiler.semantics.types.TypeSymbol;

/// Immutable compile-time constant expression result.
public sealed interface ConstantValue permits ConstantValue.Null, ConstantValue.Boolean,
        ConstantValue.Int, ConstantValue.Long, ConstantValue.Float, ConstantValue.Double,
        ConstantValue.Decimal, ConstantValue.Char, ConstantValue.StringVal,
        ConstantValue.EnumVal, ConstantValue.ByteArray {

    record Null() implements ConstantValue {}

    record Boolean(boolean value) implements ConstantValue {}

    record Int(int value) implements ConstantValue {}

    record Long(long value) implements ConstantValue {}

    record Float(float value) implements ConstantValue {}

    record Double(double value) implements ConstantValue {}

    record Decimal(BigDecimal value) implements ConstantValue {
        public Decimal {
            Objects.requireNonNull(value, "value");
        }
    }

    record Char(char value) implements ConstantValue {}

    record StringVal(String value) implements ConstantValue {
        public StringVal {
            Objects.requireNonNull(value, "value");
        }
    }

    record EnumVal(TypeSymbol enumType, Object underlyingValue) implements ConstantValue {
        public EnumVal {
            Objects.requireNonNull(enumType, "enumType");
            Objects.requireNonNull(underlyingValue, "underlyingValue");
        }
    }

    record ByteArray(byte[] value) implements ConstantValue {
        public ByteArray {
            Objects.requireNonNull(value, "value");
        }
    }
}
