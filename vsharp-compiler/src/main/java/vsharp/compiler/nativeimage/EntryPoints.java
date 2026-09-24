package vsharp.compiler.nativeimage;

import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodModel;
import java.lang.reflect.AccessFlag;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/// Finds the entry points of an emitted program by reading the emitted bytecode.
///
/// The bytecode is the authority rather than the syntax tree: an image is started by whatever
/// the JVM would start, so an entry point is exactly a `public static void main(String[])` in a
/// produced class, whether it came from a declared method or from a top-level statement holder.
public final class EntryPoints {

    private static final String MAIN_NAME = "main";
    private static final String MAIN_DESCRIPTOR = "([Ljava/lang/String;)V";

    private EntryPoints() {
        throw new AssertionError("No instances");
    }

    /// Lists the entry points of a set of emitted classes.
    ///
    /// @param bytecode the emitted class files
    /// @return the binary names of every class declaring a JVM entry point, in a deterministic
    ///     order, so that an ambiguous program is reported identically on every machine
    public static List<String> mainClasses(Collection<byte[]> bytecode) {
        Objects.requireNonNull(bytecode, "bytecode");
        ClassFile reader = ClassFile.of();
        TreeSet<String> names = new TreeSet<>();
        for (byte[] classFile : bytecode) {
            ClassModel model = reader.parse(classFile);
            if (declaresMain(model)) {
                names.add(model.thisClass().asInternalName().replace('/', '.'));
            }
        }
        return List.copyOf(new ArrayList<>(names));
    }

    private static boolean declaresMain(ClassModel model) {
        for (MethodModel method : model.methods()) {
            if (method.methodName().equalsString(MAIN_NAME)
                    && method.methodType().equalsString(MAIN_DESCRIPTOR)
                    && method.flags().has(AccessFlag.PUBLIC)
                    && method.flags().has(AccessFlag.STATIC)) {
                return true;
            }
        }
        return false;
    }
}
