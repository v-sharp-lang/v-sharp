package vsharp.compiler.ir;

import java.util.List;
import java.util.Objects;
import vsharp.compiler.semantics.symbols.EnumMemberSymbol;
import vsharp.compiler.semantics.symbols.NamedTypeSymbol;

public record IrEnum(NamedTypeSymbol symbol, List<EnumMemberSymbol> members) {
    public IrEnum {
        Objects.requireNonNull(symbol, "symbol");
        members = List.copyOf(members);
    }
}
