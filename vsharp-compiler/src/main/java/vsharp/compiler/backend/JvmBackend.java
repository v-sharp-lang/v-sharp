package vsharp.compiler.backend;

import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Objects.requireNonNullElse;

import vsharp.compiler.ir.CaptureCollector;
import vsharp.compiler.ir.IrCapture;
import vsharp.compiler.ir.IrEnum;
import vsharp.compiler.ir.IrFunction;
import vsharp.compiler.ir.IrRecordStruct;
import vsharp.compiler.ir.IrUnit;
import vsharp.compiler.semantics.symbols.FieldSymbol;
import vsharp.compiler.semantics.symbols.FunctionSymbol;
import vsharp.compiler.syntax.SyntaxKind;

public final class JvmBackend {

    private JvmBackend() {
        throw new AssertionError("No instances");
    }


    public static java.util.Map<String, byte[]> emit(IrUnit unit) {
        ClassFile cf = ClassFile.of();
        Map<FunctionSymbol, List<IrCapture>> captureMap =
                CaptureCollector.resolveTransitive(unit.functions());

        // A member whose qualified name carries no owner segment belongs to the file's
        // synthetic holder, so it names that holder exactly as a declared type names its
        // own. Deriving the set from *every* member - rather than only the owned ones and
        // an is-anything-left fallback - is what keeps a file that mixes top-level
        // statements with a type declaration emitting both classes instead of dropping
        // the program on the floor.
        String fileHolder = sourceFileClassName(unit.file().name());
        Set<String> holders = new LinkedHashSet<>();
        for (IrFunction function : unit.functions()) {
            // Local functions carry the `Enclosing$Local` name their declaring class owns
            // and synthesized ones (top-level `main`, `<clinit>`) carry their holder's, so
            // the same owner rule the emission loop below applies places all of them; a
            // callable that names no holder here is a callable no class would declare.
            holders.add(requireNonNullElse(
                    holderName(function.symbol().qualifiedName()), fileHolder));
        }
        for (FieldSymbol field : unit.fields()) {
            holders.add(requireNonNullElse(holderName(field.qualifiedName()), fileHolder));
        }
        for (IrEnum enumeration : unit.enums()) {
            holders.add(requireNonNullElse(holderName(enumeration.symbol().qualifiedName()), fileHolder));
        }

        if (holders.isEmpty()) {
            holders.add(fileHolder);
        }

        java.util.Map<String, byte[]> classes = new java.util.LinkedHashMap<>();

        for (String holder : holders) {
            ClassDesc classDesc = ClassDesc.of(holder);

            byte[] bytecode = cf.build(classDesc, classBuilder -> {
                // `unit.fields()` is the whole field population: every FieldSymbol in the
                // compilation is created from a declarator that UnitLowerer records here, so
                // walking bodies for referenced fields could only rediscover these same
                // symbols (or ones owned by another file's holder, which this loop skips).
                for (FieldSymbol field : unit.fields()) {
                    String fieldHolder = holderName(field.qualifiedName());
                    if (fieldHolder == null) {
                        fieldHolder = sourceFileClassName(unit.file().name());
                    }
                    if (!holder.equals(fieldHolder)) {
                        continue;
                    }

                    int flags = ClassFile.ACC_PUBLIC;
                    // A `const` is static whether or not it was written `static`: C# §15.4
                    // makes constants implicitly static, and the reads are folded to their
                    // value, so an instance field here is both wrong and unreachable. It was
                    // also unsound - an unfolded read emitted `getfield` against whatever
                    // happened to be on the stack, which the verifier rejected.
                    if (field.modifiers().contains(SyntaxKind.STATIC) || field.isConstant()) {
                        flags |= ClassFile.ACC_STATIC;
                    }
                    if (field.isConstant()) {
                        flags |= ClassFile.ACC_FINAL;
                    }
                    // The descriptor comes from the same mapper the accesses use; declaring
                    // a field with any other carrier makes every access unresolvable.
                    classBuilder.withField(field.name(), CodeEmitter.toClassDesc(field.type()), flags);
                }

                // A no-argument constructor, so instance members are reachable from Java and
                // from V# code that constructs the holder.
                classBuilder.withMethodBody(ConstantDescs.INIT_NAME,
                        MethodTypeDesc.of(ConstantDescs.CD_void), ClassFile.ACC_PUBLIC, codeBuilder -> {
                    codeBuilder.aload(0);
                    codeBuilder.invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME,
                            MethodTypeDesc.of(ConstantDescs.CD_void));
                    codeBuilder.return_();
                });

                // A positional `record struct` also gets the constructor its declaration
                // implies, whose parameters initialise the component fields.
                for (IrRecordStruct record : unit.records()) {
                    if (holder.equals(record.owner())) {
                        CodeEmitter.emitRecordConstructor(classDesc, classBuilder, record);
                        CodeEmitter.emitRecordValueMembers(classDesc, classBuilder, record);
                    }
                }

                // A name table for each enum declared by this holder.
                for (IrEnum enumeration : unit.enums()) {
                    String enumHolder = holderName(enumeration.symbol().qualifiedName());
                    if (enumHolder == null) {
                        enumHolder = sourceFileClassName(unit.file().name());
                    }
                    if (holder.equals(enumHolder)) {
                        CodeEmitter.emitEnumNameTable(classBuilder, enumeration);
                    }
                }

                // A `Main` this holder declares, or `null`. Collected while emitting so the
                // launcher can be added afterwards: it calls a method that must exist.
                FunctionSymbol entryPoint = null;
                boolean hasTopLevelMain = false;
                for (IrFunction function : unit.functions()) {
                    if (function instanceof IrFunction.Implemented impl) {
                        // Local and synthesized functions are named after their declaring
                        // context, so the qualified name alone places every callable; a
                        // name with no owner segment belongs to the file's synthetic holder.
                        String owner = holderName(function.symbol().qualifiedName());
                        if (owner == null) {
                            owner = sourceFileClassName(unit.file().name());
                        }
                        if (holder.equals(owner)) {
                            CodeEmitter.emit(classDesc, classBuilder, impl, captureMap);
                            if (CodeEmitter.isEntryPoint(impl.symbol())) {
                                entryPoint = impl.symbol();
                            }
                            hasTopLevelMain |= CodeEmitter.isTopLevelMain(impl.symbol());
                        }
                    } else if (function instanceof IrFunction.AsyncEntry entry) {
                        String owner = holderName(entry.symbol().qualifiedName());
                        if (owner == null) {
                            owner = sourceFileClassName(unit.file().name());
                        }
                        if (holder.equals(owner)) {
                            CodeEmitter.emitAsyncEntry(classDesc, classBuilder, entry);
                        }
                    }
                }

                // Top-level statements already *are* `main`; a second one would make the
                // class unloadable, and C# would have rejected the pair as two entry points.
                if (entryPoint != null && !hasTopLevelMain) {
                    CodeEmitter.emitEntryPointBridge(classDesc, classBuilder, entryPoint, captureMap);
                }
            });
            classes.put(holder, bytecode);
        }

        return classes;
    }

    static String holderName(String qualifiedName) {
        int dot = qualifiedName.lastIndexOf('.');
        if (dot <= 0) {
            return null;
        }
        return qualifiedName.substring(0, dot);
    }

    static String sourceFileClassName(String name) {
        String fileName = fileBaseName(name);
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            fileName = fileName.substring(0, dot);
        }
        return isJavaIdentifier(fileName) ? fileName : "Program";
    }

    private static String fileBaseName(String name) {
        try {
            Path fileName = Path.of(name).getFileName();
            return fileName == null ? name : fileName.toString();
        } catch (InvalidPathException ignored) {
            return name;
        }
    }

    private static boolean isJavaIdentifier(String name) {
        if (name.isEmpty() || !Character.isJavaIdentifierStart(name.charAt(0))) {
            return false;
        }
        for (int index = 1; index < name.length(); index++) {
            if (!Character.isJavaIdentifierPart(name.charAt(index))) {
                return false;
            }
        }
        return true;
    }

}
