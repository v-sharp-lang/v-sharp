package vsharp.compiler.ir;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import vsharp.compiler.semantics.symbols.FieldSymbol;
import vsharp.compiler.source.SourceFile;

/// All lowerable callables originating from one source file, in source declaration order.
public record IrUnit(SourceFile file, List<IrFunction> functions, List<FieldSymbol> fields,
        List<IrRecordStruct> records, List<IrEnum> enums) {
    public IrUnit(SourceFile file, List<IrFunction> functions) {
        this(file, functions, List.of(), List.of(), List.of());
    }

    public IrUnit(SourceFile file, List<IrFunction> functions, List<FieldSymbol> fields) {
        this(file, functions, fields, List.of(), List.of());
    }

    public IrUnit {
        Objects.requireNonNull(file, "file");
        functions = List.copyOf(functions);
        fields = List.copyOf(fields);
        records = List.copyOf(records);
        enums = List.copyOf(enums);
        Set<String> names = new HashSet<>();
        for (IrFunction function : functions) {
            Objects.requireNonNull(function, "function");
            if (!names.add(function.symbol().qualifiedName() + "|" + function.symbol().signature())) {
                throw new IllegalArgumentException("duplicate IR function: "
                        + function.symbol().qualifiedName() + "|" + function.symbol().signature());
            }
        }
        Set<String> fieldNames = new HashSet<>();
        for (FieldSymbol field : fields) {
            Objects.requireNonNull(field, "field");
            if (!fieldNames.add(field.qualifiedName())) {
                throw new IllegalArgumentException("duplicate IR field: " + field.qualifiedName());
            }
        }
    }
}
