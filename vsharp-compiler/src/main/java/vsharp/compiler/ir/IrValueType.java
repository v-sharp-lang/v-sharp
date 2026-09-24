package vsharp.compiler.ir;

import java.util.Objects;
import vsharp.compiler.semantics.types.JvmTypeKind;
import vsharp.compiler.semantics.types.TypeSymbol;

/// A source type together with the exact verifier carrier the backend must use.
///
/// Keeping both pieces prevents lowering from accidentally treating source-level signedness,
/// tuples, or decimal values as though their shared JVM carrier gave them identical semantics.
/// `Error` and `Inferred` are deliberately rejected: IR is only created after successful
/// front-end analysis.
public record IrValueType(TypeSymbol sourceType, JvmTypeKind carrier) {

    public IrValueType {
        Objects.requireNonNull(sourceType, "sourceType");
        Objects.requireNonNull(carrier, "carrier");
        if (sourceType == TypeSymbol.Error.INSTANCE || sourceType == TypeSymbol.Inferred.INSTANCE) {
            throw new IllegalArgumentException("IR cannot carry " + sourceType.displayName());
        }
        if (sourceType.jvmTypeKind() != carrier) {
            throw new IllegalArgumentException("source type/carrier mismatch: "
                    + sourceType.displayName() + " / " + carrier);
        }
    }

    public static IrValueType of(TypeSymbol sourceType) {
        Objects.requireNonNull(sourceType, "sourceType");
        return new IrValueType(sourceType, sourceType.jvmTypeKind());
    }

    public boolean isVoid() {
        return carrier == JvmTypeKind.VOID;
    }
}
