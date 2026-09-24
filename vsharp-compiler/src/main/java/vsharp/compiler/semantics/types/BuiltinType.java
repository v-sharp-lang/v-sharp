package vsharp.compiler.semantics.types;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import vsharp.compiler.syntax.SyntaxKind;

/// The immutable set of C# built-in types recognised by V#.
///
/// `dynamic` is retained as a distinct semantic spelling even though the declaration
/// binder diagnoses it as outside the non-object-oriented language subset.
public enum BuiltinType implements TypeSymbol {
    SBYTE("sbyte", SyntaxKind.SBYTE, JvmTypeKind.BYTE, true),
    BYTE("byte", SyntaxKind.BYTE, JvmTypeKind.BYTE, true),
    SHORT("short", SyntaxKind.SHORT, JvmTypeKind.SHORT, true),
    USHORT("ushort", SyntaxKind.USHORT, JvmTypeKind.SHORT, true),
    INT("int", SyntaxKind.INT, JvmTypeKind.INT, true),
    UINT("uint", SyntaxKind.UINT, JvmTypeKind.INT, true),
    LONG("long", SyntaxKind.LONG, JvmTypeKind.LONG, true),
    ULONG("ulong", SyntaxKind.ULONG, JvmTypeKind.LONG, true),
    NINT("nint", SyntaxKind.NINT, JvmTypeKind.LONG, true),
    NUINT("nuint", SyntaxKind.NUINT, JvmTypeKind.LONG, true),
    FLOAT("float", SyntaxKind.FLOAT, JvmTypeKind.FLOAT, true),
    DOUBLE("double", SyntaxKind.DOUBLE, JvmTypeKind.DOUBLE, true),
    DECIMAL("decimal", SyntaxKind.DECIMAL, JvmTypeKind.REFERENCE, true),
    BOOL("bool", SyntaxKind.BOOL, JvmTypeKind.BOOLEAN, true),
    CHAR("char", SyntaxKind.CHAR, JvmTypeKind.CHAR, true),
    STRING("string", SyntaxKind.STRING, JvmTypeKind.REFERENCE, false),
    OBJECT("object", SyntaxKind.OBJECT, JvmTypeKind.REFERENCE, false),
    DYNAMIC("dynamic", SyntaxKind.DYNAMIC, JvmTypeKind.REFERENCE, false),
    VOID("void", SyntaxKind.VOID, JvmTypeKind.VOID, false);

    private static final Map<SyntaxKind, BuiltinType> BY_KEYWORD = createKeywordMap();

    private final String displayName;
    private final SyntaxKind keyword;
    private final JvmTypeKind jvmTypeKind;
    private final boolean valueType;

    BuiltinType(String displayName, SyntaxKind keyword, JvmTypeKind jvmTypeKind,
            boolean valueType) {
        this.displayName = displayName;
        this.keyword = keyword;
        this.jvmTypeKind = jvmTypeKind;
        this.valueType = valueType;
    }

    private static Map<SyntaxKind, BuiltinType> createKeywordMap() {
        Map<SyntaxKind, BuiltinType> result = new EnumMap<>(SyntaxKind.class);
        for (BuiltinType type : values()) {
            result.put(type.keyword, type);
        }
        return Map.copyOf(result);
    }

    /// Resolves a predefined syntax keyword, excluding `var`.
    public static Optional<BuiltinType> fromKeyword(SyntaxKind keyword) {
        return Optional.ofNullable(BY_KEYWORD.get(keyword));
    }

    public SyntaxKind keyword() {
        return keyword;
    }

    @Override
    public String displayName() {
        return displayName;
    }

    @Override
    public JvmTypeKind jvmTypeKind() {
        return jvmTypeKind;
    }

    @Override
    public boolean isValueType() {
        return valueType;
    }
}
