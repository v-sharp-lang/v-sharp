package vsharp.runtime;

/// The unsigned conversions the JVM has no opcode for.
///
/// V# stores `uint`/`ulong`/`nuint` in the signed `int`/`long` carriers, keeping
/// unsignedness in the type rather than in the representation. Most unsigned operations
/// still reduce to an opcode or to an existing `Integer`/`Long` static: `divideUnsigned`,
/// `remainderUnsigned`, `compareUnsigned`, `toUnsignedLong`. The two directions between an
/// unsigned 64-bit value and a floating-point value have no such counterpart, because
/// `l2d`/`d2l` read the carrier as signed and `d2l` additionally saturates, so `2^64 - 1`
/// would render as `-1` and any `double` at or above `2^63` would clamp to `long`'s
/// maximum instead of wrapping into the unsigned range C# specifies for an `unchecked`
/// conversion. Those four cases are implemented here and called from generated code.
///
/// The same loss of source identity occurs when a signed-carrier unsigned value crosses an
/// `object` or nullable boundary. Private immutable boxes retain that identity without
/// changing primitive descriptors; the public box/unbox methods are the generated-code ABI.
public final class VsUnsigned {

    private record UInt8Box(byte bits) {
        @Override
        public String toString() {
            return Integer.toString(Byte.toUnsignedInt(bits));
        }
    }

    private record UInt16Box(short bits) {
        @Override
        public String toString() {
            return Integer.toString(Short.toUnsignedInt(bits));
        }
    }

    /// The object-boundary identity of a `uint`. Keeping this carrier private prevents it
    /// from becoming a second public numeric API; generated code enters and leaves through
    /// the static methods below.
    private record UInt32Box(int bits) {
        @Override
        public String toString() {
            return Integer.toUnsignedString(bits);
        }
    }

    /// The shared object-boundary identity of `ulong` and 64-bit `nuint`.
    private record UInt64Box(long bits) {
        @Override
        public String toString() {
            return Long.toUnsignedString(bits);
        }
    }

    private VsUnsigned() {
        throw new AssertionError("No instances");
    }

    /// Boxes a C# `byte`, whose JVM carrier is the signed `byte` bit pattern.
    public static Object boxByte(byte value) {
        return new UInt8Box(value);
    }

    /// Boxes a `ushort`, whose JVM carrier is the signed `short` bit pattern.
    public static Object boxUShort(short value) {
        return new UInt16Box(value);
    }

    /// Boxes a `uint` without losing its unsigned source identity.
    public static Object box(int value) {
        return new UInt32Box(value);
    }

    /// Boxes a `ulong` or 64-bit `nuint` without losing its unsigned source identity.
    public static Object box(long value) {
        return new UInt64Box(value);
    }

    /// Unboxes a present `byte?` carrier, accepting the Java-boundary `Byte` spelling.
    public static byte unboxByte(Object value) {
        return switch (value) {
            case UInt8Box box -> box.bits();
            case Byte small -> small.byteValue();
            default -> throw new ClassCastException("Not a boxed V# byte: "
                    + value.getClass().getName());
        };
    }

    /// Unboxes a present `ushort?` carrier, accepting the Java-boundary `Short` spelling.
    public static short unboxUShort(Object value) {
        return switch (value) {
            case UInt16Box box -> box.bits();
            case Short medium -> medium.shortValue();
            default -> throw new ClassCastException("Not a boxed V# ushort: "
                    + value.getClass().getName());
        };
    }

    /// Unboxes a present `uint?` carrier.
    ///
    /// `Integer` remains accepted as the Java-call boundary spelling used before the design; V#
    /// generated values use [UInt32Box], which retains unsigned formatting through object.
    public static int unboxInt(Object value) {
        return switch (value) {
            case UInt32Box box -> box.bits();
            case Integer integer -> integer.intValue();
            default -> throw new ClassCastException("Not a boxed V# uint: "
                    + value.getClass().getName());
        };
    }

    /// Unboxes a present `ulong?` or `nuint?` carrier, accepting the former Java-boundary
    /// `Long` spelling for compatibility.
    public static long unboxLong(Object value) {
        return switch (value) {
            case UInt64Box box -> box.bits();
            case Long wide -> wide.longValue();
            default -> throw new ClassCastException("Not a boxed V# unsigned long: "
                    + value.getClass().getName());
        };
    }

    /// Converts a `ulong` held in a signed `long` carrier to `double`.
    ///
    /// A non-negative carrier is already the value. Otherwise the exact value is
    /// `carrier + 2^64`, computed by halving with an odd-bit correction so that no
    /// precision is lost before the single rounding the result is allowed to have.
    public static double toDouble(long unsignedValue) {
        if (unsignedValue >= 0L) {
            return unsignedValue;
        }
        return ((double) ((unsignedValue >>> 1) | (unsignedValue & 1L))) * 2.0;
    }

    /// Converts a `ulong` held in a signed `long` carrier to `float`.
    public static float toFloat(long unsignedValue) {
        return (float) toDouble(unsignedValue);
    }

    /// Converts a `double` to the `ulong` C# produces in an `unchecked` context.
    ///
    /// Values below `2^63` use the ordinary `d2l` path; the rest are shifted down by
    /// `2^63`, converted, and the sign bit restored, so `18446744073709551615.0` reaches
    /// the all-ones carrier instead of `d2l`'s saturated `Long.MAX_VALUE`.
    public static long fromDouble(double value) {
        if (value < 0x1p63) {
            return (long) value;
        }
        long shifted = (long) (value - 0x1p63);
        return shifted | Long.MIN_VALUE;
    }

    /// Converts a `float` to the `ulong` C# produces in an `unchecked` context.
    public static long fromFloat(float value) {
        return fromDouble(value);
    }
}
