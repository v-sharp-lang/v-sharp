package vsharp.compiler.backend;

import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Label;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.constant.DynamicCallSiteDesc;
import java.lang.constant.MethodHandleDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import vsharp.compiler.ir.*;
import vsharp.compiler.semantics.binding.JavaInterop;
import vsharp.compiler.semantics.binding.LambdaNames;
import vsharp.compiler.semantics.conversions.Conversion;
import vsharp.compiler.semantics.conversions.ConversionKind;
import vsharp.compiler.semantics.conversions.Conversions;
import vsharp.compiler.semantics.symbols.*;
import vsharp.compiler.semantics.types.*;

public final class CodeEmitter {

    private static final ClassDesc CD_OBJECTS = ClassDesc.of("java.util.Objects");
    private static final ClassDesc CD_VSFORMAT = ClassDesc.of("vsharp.runtime.VsFormat");
    private static final ClassDesc CD_VS_NULLABLE = ClassDesc.of("vsharp.runtime.VsNullable");
    private static final ClassDesc CD_VS_UNSIGNED = ClassDesc.of("vsharp.runtime.VsUnsigned");
    private static final ClassDesc CD_VS_ASYNC = ClassDesc.of("vsharp.runtime.VsAsync");
    private static final ClassDesc CD_CALLABLE = ClassDesc.of("java.util.concurrent.Callable");
    private static final ClassDesc CD_RUNNABLE = ClassDesc.of("java.lang.Runnable");
    private static final ClassDesc CD_FUTURE = ClassDesc.of("java.util.concurrent.Future");
    private static final ClassDesc CD_VS_CHECKED = ClassDesc.of("vsharp.runtime.VsChecked");
    private static final ClassDesc CD_VS_DECIMAL = ClassDesc.of("vsharp.runtime.VsDecimal");
    private static final ClassDesc CD_VS_TYPE = ClassDesc.of("vsharp.runtime.VsType");
    private static final ClassDesc CD_BIG_DECIMAL = ClassDesc.of("java.math.BigDecimal");
    private static final ClassDesc CD_MATH = ClassDesc.of("java.lang.Math");
    private static final ClassDesc CD_ITERABLE = ClassDesc.of("java.lang.Iterable");
    private static final ClassDesc CD_ITERATOR = ClassDesc.of("java.util.Iterator");

    private final ClassDesc ownerClass;
    private final CodeBuilder codeBuilder;
    /// The one call whose erased generic result the statement being emitted discards, held
    /// by identity so the narrowing is suppressed for exactly that invocation and no other.
    private IrExpression.Call discardedCall;
    private final Map<Symbol, Integer> localSlots = new HashMap<>();
    /// Slots holding the narrowed value of an undesignated type pattern, keyed by identity
    /// because two `int` patterns in one tree are equal records but are distinct tests.
    private final Map<IrPattern, Integer> patternNarrowSlots = new IdentityHashMap<>();
    /// Slots holding one recursive-pattern component's extracted value, keyed by identity
    /// for the same reason the narrow slots are: two components can be equal records.
    private final Map<IrPattern.Component, Integer> patternComponentSlots =
            new IdentityHashMap<>();
    /// Slots holding one list-pattern element's (or slice's) value, keyed by identity.
    private final Map<IrPattern, Integer> patternElementSlots = new IdentityHashMap<>();
    private record JumpTarget(Label label, int depth) {}
    private final Deque<JumpTarget> breakTargets = new ArrayDeque<>();
    private final Deque<JumpTarget> continueTargets = new ArrayDeque<>();
    private final Deque<SwitchDispatch> switchGotoTargets = new ArrayDeque<>();
    private record ConditionalReceiverSlot(int index, java.lang.classfile.TypeKind kind) {}
    private final Deque<ConditionalReceiverSlot> conditionalReceiverSlots = new ArrayDeque<>();
    private final Map<String, Label> gotoLabels = new HashMap<>();
    private final Map<String, Integer> gotoLabelDepths = new HashMap<>();
    /// Local slots holding the exception caught by each lexically enclosing `catch` body, so a
    /// bare `throw;` can reload the innermost one. Pushed per handler body, not per `try`:
    /// a `finally` body and the guarded `try` body are outside any handler and never push, which
    /// is exactly where C# forbids a bare `throw;` (CS0156) and where the binder refuses it.
    private final Deque<Integer> handledExceptionSlots = new ArrayDeque<>();

    /// Whether the code being emitted is lexically inside `checked`, in which case integer
    /// arithmetic and narrowing conversions must trap on overflow instead of wrapping.
    ///
    /// C# §11.7.18 makes this a purely lexical property of the operator's position, so a
    /// plain field saved and restored around each `checked`/`unchecked` region tracks it
    /// exactly. The default is `false`: C# is unchecked outside a `checked` region unless the
    /// operands are constants, and constant overflow is already a binding-time error.
    private boolean checkedContext;

    /// Cleanup actions currently protecting the code being emitted, innermost first
    /// (pushed/popped exactly around the region they protect - never around their own
    /// re-emission at each exit point, since a cleanup is not "inside" its own
    /// protection). Each entry emits whatever bytecode releases that one resource: a real
    /// `finally` body's statements, or (for `lock`) a synthetic `aload`+`monitorexit` -
    /// both share the exact same multi-exit duplication shape (`emitProtectedRegion`), so
    /// this deque does not care which.
    ///
    /// Every abrupt-exit statement form consults it and duplicates exactly the cleanups it
    /// escapes, innermost first: `return` runs all of them, while `break`, `continue`,
    /// `goto` and `goto case` run only the difference between this depth and the depth
    /// recorded for their target ([JumpTarget], [SwitchDispatch], `gotoLabelDepths`), since
    /// a jump that stays inside a protected region must not release it.
    private final Deque<Runnable> activeCleanups = new ArrayDeque<>();

    /// The `goto case`/`goto default` targets of the innermost switch currently being
    /// emitted, keyed by the same int-constant values used for the initial dispatch.
    /// Case labels of the enclosing switch, keyed by their constant value - an `Integer` for
    /// an `int`, `char` or enum governing expression, a `String` for a string one - so that
    /// `goto case` can find the same label the dispatch uses.
    private record SwitchDispatch(Map<Object, Label> caseTargets, Label defaultTarget, int depth) {}

    private final Map<FunctionSymbol, List<IrCapture>> captureMap;
    /// Every lexical value captured by at least one local function in this compilation unit.
    ///
    /// Captured variables use a shared one-element array cell in their declaring callable and
    /// in every local function that receives them. Local functions are direct, non-escaping
    /// calls in V#'s subset, so this gives every nested/recursive call the same storage and
    /// preserves writes even when a call exits by throwing. The identity map matches the
    /// symbol-identity contract of the semantic model and IR.
    private final Map<Symbol, IrValueType> capturedValues = new IdentityHashMap<>();

    private CodeEmitter(ClassDesc ownerClass, CodeBuilder codeBuilder,
            Map<FunctionSymbol, List<IrCapture>> captureMap) {
        this.ownerClass = ownerClass;
        this.codeBuilder = codeBuilder;
        this.captureMap = Map.copyOf(captureMap);
        for (List<IrCapture> captures : captureMap.values()) {
            for (IrCapture capture : captures) {
                IrValueType previous = capturedValues.putIfAbsent(
                        capture.symbol(), capture.type());
                if (previous != null && !previous.equals(capture.type())) {
                    throw new IllegalArgumentException(
                            "inconsistent capture types for " + capture.symbol().qualifiedName());
                }
            }
        }
    }

    /// The source-level identity the binder gives the synthesised top-level function. It is
    /// deliberately unspeakable so user code cannot name it - and it is also an illegal JVM
    /// method name (`<` and `>` are reserved for `<init>`/`<clinit>`), so it must never
    /// reach a class file: a class carrying it fails to load with `ClassFormatError`.
    private static final String TOP_LEVEL_SOURCE_NAME = "<top-level>";

    /// The JVM entry point a file of top-level statements becomes (Q1).
    private static final MethodTypeDesc MAIN_DESCRIPTOR =
            MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String.arrayType());

    public static void emit(ClassDesc ownerClass, ClassBuilder classBuilder,
            IrFunction.Implemented impl,
            Map<FunctionSymbol, List<IrCapture>> captureMap) {
        FunctionSymbol symbol = impl.symbol();
        boolean topLevel = symbol.synthesized() && TOP_LEVEL_SOURCE_NAME.equals(symbol.name());

        // The holder's static field initializers become its class initializer: the JVM runs
        // `<clinit>` on first use, which is exactly when C# runs an implicit static
        // constructor. It takes no arguments, returns void and is never called by name,
        // so it is emitted static and non-public.
        boolean classInit = symbol.synthesized()
                && UnitLowerer.STATIC_INITIALIZER_NAME.equals(symbol.name());

        // Top-level statements become a standard `public static void main(String[])`, so the
        // artifact the CLI produces is directly launchable with `java -cp ... <Holder>`.
        String name = topLevel ? "main"
                : classInit ? ConstantDescs.CLASS_INIT_NAME : emittedMethodName(symbol);
        MethodTypeDesc mtd = topLevel ? MAIN_DESCRIPTOR : buildMethodTypeDesc(symbol, captureMap);

        int flags = classInit ? 0 : ClassFile.ACC_PUBLIC;
        if (topLevel || classInit || isStaticMethod(symbol, captureMap)) {
            flags |= ClassFile.ACC_STATIC;
        }

        classBuilder.withMethod(name, mtd, flags, methodBuilder -> {
            methodBuilder.withCode(codeBuilder -> {
                CodeEmitter emitter = new CodeEmitter(ownerClass, codeBuilder, captureMap);
                emitter.allocateParameters(symbol);
                emitter.findGotoLabelDepths(impl.body(), 0);
                emitter.emitStatement(impl.body());
                if (impl.appendImplicitReturn()) {
                    codeBuilder.return_();
                } else {
                    // Nothing follows the body: the method's value is always returned from
                    // inside it, so flow analysis demanded no trailing statement. Every
                    // construct that binds a shared end/break label binds it after its last
                    // instruction, so such a label denotes one byte past the code array - a
                    // position no `goto`/`ifeq` may name, and the class is rejected with
                    // "branch target out of bytecode range" before it ever runs (R8/the design).
                    //
                    // The filler cannot be a `nop`: `while (true)` still emits a real
                    // conditional branch to its end label, and the verifier - which does not
                    // constant-fold the condition C# proved always true - would then see
                    // control fall off the end of the code. `athrow` terminates control flow
                    // for the verifier while remaining valid whatever the branch left on the
                    // stack, and `null` is assignable to `Throwable`. C#'s reachability rules
                    // are what guarantee these two instructions are never executed; if one
                    // ever were, an immediate `NullPointerException` is the correct outcome,
                    // since the method has no value to return.
                    codeBuilder.aconst_null();
                    codeBuilder.athrow();
                }
            });
            if (!impl.annotations().isEmpty()) {
                methodBuilder.with(RuntimeVisibleAnnotationsAttribute.of(
                        impl.annotations().stream()
                                .map(annotation -> java.lang.classfile.Annotation.of(
                                        ClassDesc.of(annotation.className())))
                                .toArray(java.lang.classfile.Annotation[]::new)));
            }
        });
    }

    /// Emits the caller-facing half of an `async` declaration.
    ///
    /// The whole method is: push the arguments, bind them to the body callable through
    /// `LambdaMetafactory`, submit, return the task. Nothing about the body is re-examined
    /// here - it was emitted as an ordinary method - so an async declaration costs exactly one
    /// extra method and one `invokedynamic`, and the concurrency lives entirely in the runtime.
    ///
    /// The functional interface is chosen by the body's own return type: a body that produces
    /// a value is a [java.util.concurrent.Callable], one that produces none is a [Runnable].
    /// `LambdaMetafactory` adapts a primitive result to the interface's erased `Object` on its
    /// own, which is why a body returning `int` needs no boxing here.
    ///
    /// @param ownerClass the holder being built
    /// @param classBuilder the holder's builder
    /// @param entry the declared callable and the body it starts
    public static void emitAsyncEntry(ClassDesc ownerClass, ClassBuilder classBuilder,
            IrFunction.AsyncEntry entry) {
        FunctionSymbol symbol = entry.symbol();
        FunctionSymbol body = entry.body();
        MethodTypeDesc entryDescriptor = buildMethodTypeDesc(symbol, Map.of());
        MethodTypeDesc bodyDescriptor = buildMethodTypeDesc(body, Map.of());
        // `async void` hands back nothing, so there is no future and no way to join: the body
        // is started detached and its failure is logged by the runtime rather than stored in a
        // future nobody will read.
        boolean detached = symbol.returnType() == BuiltinType.VOID;
        boolean producesValue = !detached && body.returnType() != BuiltinType.VOID;
        ClassDesc functionalInterface = producesValue ? CD_CALLABLE : CD_RUNNABLE;
        String samName = producesValue ? "call" : "run";
        MethodTypeDesc samDescriptor = producesValue
                ? MethodTypeDesc.of(ConstantDescs.CD_Object)
                : MethodTypeDesc.of(ConstantDescs.CD_void);
        MethodTypeDesc instantiated = producesValue
                ? MethodTypeDesc.of(boxedCarrier(body.returnType()))
                : MethodTypeDesc.of(ConstantDescs.CD_void);

        classBuilder.withMethod(emittedMethodName(symbol), entryDescriptor,
                ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, methodBuilder ->
                methodBuilder.withCode(codeBuilder -> {
            // Arguments are captured by value at the call, which is what makes the task
            // observe the arguments the caller passed rather than whatever a shared cell
            // happens to hold when the virtual thread finally runs.
            // The carrier comes from the descriptor this method is being emitted with, never
            // from the source type. A parameter whose carrier differs from its written type -
            // a by-reference parameter, which is a one-element array cell - made the two
            // disagree, and the result was an `iload` against an `int[]` local: a class that
            // fails verification and cannot even be loaded. The descriptor is the only
            // authority on what is actually in the slot.
            int slot = 0;
            for (ClassDesc parameter : entryDescriptor.parameterList()) {
                java.lang.classfile.TypeKind kind = java.lang.classfile.TypeKind.from(parameter);
                codeBuilder.loadLocal(kind, slot);
                slot += kind.slotSize();
            }
            codeBuilder.invokedynamic(DynamicCallSiteDesc.of(
                    LAMBDA_METAFACTORY, samName,
                    MethodTypeDesc.of(functionalInterface, entryDescriptor.parameterArray()),
                    samDescriptor,
                    MethodHandleDesc.ofMethod(DirectMethodHandleDesc.Kind.STATIC, ownerClass,
                            emittedMethodName(body), bodyDescriptor),
                    instantiated));
            if (detached) {
                codeBuilder.ldc(symbol.name());
                codeBuilder.invokestatic(CD_VS_ASYNC, "runDetached",
                        MethodTypeDesc.of(ConstantDescs.CD_void, CD_RUNNABLE,
                                ConstantDescs.CD_String));
                codeBuilder.return_();
                return;
            }
            codeBuilder.invokestatic(CD_VS_ASYNC, producesValue ? "run" : "runVoid",
                    MethodTypeDesc.of(CD_FUTURE, functionalInterface));
            codeBuilder.areturn();
        }));
    }

    /// The reference carrier a value takes when it stands in an erased generic position, which
    /// is what `LambdaMetafactory` must be told the implementation really produces.
    private static ClassDesc boxedCarrier(TypeSymbol type) {
        ClassDesc exact = toClassDesc(type);
        return exact.isPrimitive() && type instanceof BuiltinType builtin
                ? boxedClassDesc(builtin)
                : exact;
    }

    /// Whether `symbol` is a C# entry point: a static `Main` returning `void` or `int` and    /// Whether `symbol` is a C# entry point: a static `Main` returning `void` or `int` and
    /// taking either nothing or `string[]` (C# §7.1). C# also allows only one per assembly;
    /// V# emits a class per holder instead of one assembly, so each qualifying `Main` gets
    /// its own launcher and the user picks a class to run, exactly as in Java.
    static boolean isTopLevelMain(FunctionSymbol symbol) {
        return symbol.synthesized() && TOP_LEVEL_SOURCE_NAME.equals(symbol.name());
    }

    static boolean isEntryPoint(FunctionSymbol symbol) {
        if (!"Main".equals(symbol.name()) || symbol.localFunction() || symbol.synthesized()
                || !symbol.modifiers().contains(
                        vsharp.compiler.syntax.SyntaxKind.STATIC)) {
            return false;
        }
        if (symbol.returnType() != BuiltinType.VOID && symbol.returnType() != BuiltinType.INT) {
            return false;
        }
        return switch (symbol.parameters().size()) {
            case 0 -> true;
            case 1 -> symbol.parameters().getFirst().type() instanceof TypeSymbol.Array array
                    && array.jvmDepth() == 1 && array.element() == BuiltinType.STRING;
            default -> false;
        };
    }

    /// Emits the `public static void main(String[])` the JVM launches, forwarding to a C#
    /// `Main`. The bridge exists because C# names its entry point `Main` while the JVM
    /// launcher only recognises `main`, and because `int Main()` reports its value as the
    /// process exit code - which on the JVM is `System.exit`, not a return value.
    static void emitEntryPointBridge(ClassDesc ownerClass, ClassBuilder classBuilder,
            FunctionSymbol symbol, Map<FunctionSymbol, List<IrCapture>> captureMap) {
        classBuilder.withMethodBody("main", MAIN_DESCRIPTOR,
                ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, codeBuilder -> {
            if (!symbol.parameters().isEmpty()) {
                codeBuilder.aload(0);
            }
            codeBuilder.invokestatic(ownerClass, emittedMethodName(symbol),
                    buildMethodTypeDesc(symbol, captureMap));
            if (symbol.returnType() == BuiltinType.INT) {
                codeBuilder.invokestatic(ClassDesc.of("java.lang.System"), "exit",
                        MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_int));
            }
            codeBuilder.return_();
        });
    }

    /// Emits the constructor of a positional `record struct`. C# synthesizes it from the
    /// declaration - it has no body to lower - so it is built structurally here: each parameter,
    /// in declaration order, is stored into the component field of the same name.
    static void emitRecordConstructor(ClassDesc ownerClass, ClassBuilder classBuilder,
            IrRecordStruct record) {
        List<ClassDesc> parameterDescs = record.constructor().parameters().stream()
                .map(parameter -> toClassDesc(parameter.type()))
                .toList();
        classBuilder.withMethodBody(ConstantDescs.INIT_NAME,
                MethodTypeDesc.of(ConstantDescs.CD_void, parameterDescs.toArray(ClassDesc[]::new)),
                ClassFile.ACC_PUBLIC, codeBuilder -> {
            codeBuilder.aload(0);
            codeBuilder.invokespecial(ConstantDescs.CD_Object, ConstantDescs.INIT_NAME,
                    MethodTypeDesc.of(ConstantDescs.CD_void));
            int slot = 1;
            for (int index = 0; index < record.components().size(); index++) {
                FieldSymbol component = record.components().get(index);
                java.lang.classfile.TypeKind kind = toTypeKind(
                        record.constructor().parameters().get(index).type());
                codeBuilder.aload(0);
                switch (kind) {
                    case INT, BOOLEAN, BYTE, SHORT, CHAR -> codeBuilder.iload(slot);
                    case LONG -> codeBuilder.lload(slot);
                    case FLOAT -> codeBuilder.fload(slot);
                    case DOUBLE -> codeBuilder.dload(slot);
                    case REFERENCE -> codeBuilder.aload(slot);
                    case VOID -> throw new IllegalStateException(
                            "record struct component cannot be void: " + component.qualifiedName());
                }
                slot += kind == java.lang.classfile.TypeKind.LONG
                        || kind == java.lang.classfile.TypeKind.DOUBLE ? 2 : 1;
                codeBuilder.putfield(ownerClass, component.name(), toClassDesc(component.type()));
            }
            codeBuilder.return_();
        });
    }

    /// Emits the three value members C# synthesizes for a `record struct`, under their
    /// JVM names so ordinary Java reaches them too.
    ///
    /// `equals` compares the exact class and then every component; `hashCode` folds the same
    /// components with the 31 multiplier the JDK itself uses; `toString` renders
    /// `Point { X = 1, Y = 2 }`, C#'s shape, with each component rendered through the same
    /// [VsFormat] the rest of the language formats with - so a `bool` component reads `True`
    /// here exactly as it does from `Console.WriteLine`, rather than Java's `true`.
    static void emitRecordValueMembers(ClassDesc ownerClass, ClassBuilder classBuilder,
            IrRecordStruct record) {
        List<FieldSymbol> components = record.components();
        classBuilder.withMethodBody("equals",
                MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_Object),
                ClassFile.ACC_PUBLIC, codeBuilder -> {
            java.lang.classfile.Label notEqual = codeBuilder.newLabel();
            // Same reference is the cheap answer, and the only one valid for a null component
            // graph that would otherwise recurse.
            java.lang.classfile.Label compare = codeBuilder.newLabel();
            codeBuilder.aload(0);
            codeBuilder.aload(1);
            codeBuilder.if_acmpne(compare);
            codeBuilder.iconst_1();
            codeBuilder.ireturn();
            codeBuilder.labelBinding(compare);
            codeBuilder.aload(1);
            codeBuilder.instanceOf(ownerClass);
            codeBuilder.ifeq(notEqual);
            codeBuilder.aload(1);
            codeBuilder.checkcast(ownerClass);
            codeBuilder.astore(2);
            for (FieldSymbol component : components) {
                ClassDesc descriptor = toClassDesc(component.type());
                java.lang.classfile.TypeKind kind = toTypeKind(component.type());
                codeBuilder.aload(0);
                codeBuilder.getfield(ownerClass, component.name(), descriptor);
                codeBuilder.aload(2);
                codeBuilder.getfield(ownerClass, component.name(), descriptor);
                switch (kind) {
                    case INT, BOOLEAN, BYTE, SHORT, CHAR -> codeBuilder.if_icmpne(notEqual);
                    case LONG -> {
                        codeBuilder.lcmp();
                        codeBuilder.ifne(notEqual);
                    }
                    case FLOAT -> {
                        codeBuilder.fcmpl();
                        codeBuilder.ifne(notEqual);
                    }
                    case DOUBLE -> {
                        codeBuilder.dcmpl();
                        codeBuilder.ifne(notEqual);
                    }
                    // A reference component delegates to `Objects.equals`, which is null-safe
                    // and dispatches to the component's own equality - including a nested
                    // record struct's synthesized one.
                    case REFERENCE -> {
                        codeBuilder.invokestatic(CD_OBJECTS, "equals", MethodTypeDesc.of(
                                ConstantDescs.CD_boolean, ConstantDescs.CD_Object,
                                ConstantDescs.CD_Object));
                        codeBuilder.ifeq(notEqual);
                    }
                    case VOID -> throw new IllegalStateException(
                            "record struct component cannot be void: " + component.name());
                }
            }
            codeBuilder.iconst_1();
            codeBuilder.ireturn();
            codeBuilder.labelBinding(notEqual);
            codeBuilder.iconst_0();
            codeBuilder.ireturn();
        });

        classBuilder.withMethodBody("hashCode", MethodTypeDesc.of(ConstantDescs.CD_int),
                ClassFile.ACC_PUBLIC, codeBuilder -> {
            codeBuilder.iconst_0();
            for (FieldSymbol component : components) {
                ClassDesc descriptor = toClassDesc(component.type());
                codeBuilder.bipush(31);
                codeBuilder.imul();
                codeBuilder.aload(0);
                codeBuilder.getfield(ownerClass, component.name(), descriptor);
                hashComponent(codeBuilder, toTypeKind(component.type()), descriptor);
                codeBuilder.iadd();
            }
            codeBuilder.ireturn();
        });

        classBuilder.withMethodBody("toString", MethodTypeDesc.of(ConstantDescs.CD_String),
                ClassFile.ACC_PUBLIC, codeBuilder -> {
            ClassDesc builder = ClassDesc.of("java.lang.StringBuilder");
            codeBuilder.new_(builder);
            codeBuilder.dup();
            codeBuilder.invokespecial(builder, ConstantDescs.INIT_NAME,
                    MethodTypeDesc.of(ConstantDescs.CD_void));
            // The constructor's owner segment is the declared type's simple name, which is what
            // C# prints before the brace.
            String displayName = record.constructor().qualifiedName();
            int lastDot = displayName.lastIndexOf('.');
            displayName = lastDot < 0 ? displayName : displayName.substring(0, lastDot);
            int ownerDot = displayName.lastIndexOf('.');
            displayName = ownerDot < 0 ? displayName : displayName.substring(ownerDot + 1);
            appendLiteral(codeBuilder, builder, displayName + " { ");
            for (int index = 0; index < components.size(); index++) {
                FieldSymbol component = components.get(index);
                appendLiteral(codeBuilder, builder,
                        (index == 0 ? "" : ", ") + component.name() + " = ");
                codeBuilder.aload(0);
                codeBuilder.getfield(ownerClass, component.name(), toClassDesc(component.type()));
                displayComponent(codeBuilder, toTypeKind(component.type()),
                        toClassDesc(component.type()));
                codeBuilder.invokevirtual(builder, "append",
                        MethodTypeDesc.of(builder, ConstantDescs.CD_String));
            }
            appendLiteral(codeBuilder, builder, " }");
            codeBuilder.invokevirtual(builder, "toString",
                    MethodTypeDesc.of(ConstantDescs.CD_String));
            codeBuilder.areturn();
        });
    }

    private static void appendLiteral(CodeBuilder codeBuilder, ClassDesc builder, String text) {
        codeBuilder.ldc(text);
        codeBuilder.invokevirtual(builder, "append",
                MethodTypeDesc.of(builder, ConstantDescs.CD_String));
    }

    /// Reduces one component on the stack to its `int` hash, through the wrapper static the JDK
    /// declares for the carrier so that `long` and `double` fold their whole width.
    private static void hashComponent(CodeBuilder codeBuilder, java.lang.classfile.TypeKind kind,
            ClassDesc descriptor) {
        switch (kind) {
            case BOOLEAN -> codeBuilder.invokestatic(ConstantDescs.CD_Boolean, "hashCode",
                    MethodTypeDesc.of(ConstantDescs.CD_int, ConstantDescs.CD_boolean));
            case CHAR -> codeBuilder.invokestatic(ConstantDescs.CD_Character, "hashCode",
                    MethodTypeDesc.of(ConstantDescs.CD_int, ConstantDescs.CD_char));
            case LONG -> codeBuilder.invokestatic(ConstantDescs.CD_Long, "hashCode",
                    MethodTypeDesc.of(ConstantDescs.CD_int, ConstantDescs.CD_long));
            case FLOAT -> codeBuilder.invokestatic(ConstantDescs.CD_Float, "hashCode",
                    MethodTypeDesc.of(ConstantDescs.CD_int, ConstantDescs.CD_float));
            case DOUBLE -> codeBuilder.invokestatic(ConstantDescs.CD_Double, "hashCode",
                    MethodTypeDesc.of(ConstantDescs.CD_int, ConstantDescs.CD_double));
            case REFERENCE -> codeBuilder.invokestatic(CD_OBJECTS, "hashCode",
                    MethodTypeDesc.of(ConstantDescs.CD_int, ConstantDescs.CD_Object));
            // `int`, `byte` and `short` are already their own hash.
            default -> { }
        }
    }

    /// Renders one component on the stack through the language's own formatter, so the text a
    /// record struct produces matches the text every other V# rendering of that value produces.
    private static void displayComponent(CodeBuilder codeBuilder,
            java.lang.classfile.TypeKind kind, ClassDesc descriptor) {
        ClassDesc argument = switch (kind) {
            case BOOLEAN -> ConstantDescs.CD_boolean;
            case CHAR -> ConstantDescs.CD_char;
            case BYTE, SHORT, INT -> ConstantDescs.CD_int;
            case LONG -> ConstantDescs.CD_long;
            case FLOAT -> ConstantDescs.CD_float;
            case DOUBLE -> ConstantDescs.CD_double;
            default -> null;
        };
        if (argument == null) {
            codeBuilder.invokestatic(CD_VSFORMAT, "objectToString",
                    MethodTypeDesc.of(ConstantDescs.CD_String, ConstantDescs.CD_Object));
            return;
        }
        if (kind == java.lang.classfile.TypeKind.BYTE
                || kind == java.lang.classfile.TypeKind.SHORT) {
            // Both widen to `int` on the stack already; the `int` renderer is exact for them.
            codeBuilder.invokestatic(CD_VSFORMAT, "toDisplayString",
                    MethodTypeDesc.of(ConstantDescs.CD_String, ConstantDescs.CD_int));
            return;
        }
        codeBuilder.invokestatic(CD_VSFORMAT, "toDisplayString",
                MethodTypeDesc.of(ConstantDescs.CD_String, argument));
    }

    public static void emitEnumNameTable(ClassBuilder classBuilder, vsharp.compiler.ir.IrEnum enumeration) {
        String name = "$" + enumeration.symbol().qualifiedName().replace('.', '$') + "_name";
        MethodTypeDesc mtd = MethodTypeDesc.of(ConstantDescs.CD_String, ConstantDescs.CD_int);
        classBuilder.withMethodBody(name, mtd, ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC, codeBuilder -> {
            java.lang.classfile.Label endLabel = codeBuilder.newLabel();
            for (vsharp.compiler.semantics.symbols.EnumMemberSymbol member : enumeration.members()) {
                java.lang.classfile.Label nextLabel = codeBuilder.newLabel();
                codeBuilder.iload(0);
                codeBuilder.ldc(member.value());
                codeBuilder.if_icmpne(nextLabel);
                codeBuilder.ldc(member.name());
                codeBuilder.areturn();
                codeBuilder.labelBinding(nextLabel);
            }
            // fallback to Integer.toString(value)
            codeBuilder.iload(0);
            codeBuilder.invokestatic(ClassDesc.of("java.lang.Integer"), "toString", 
                    MethodTypeDesc.of(ConstantDescs.CD_String, ConstantDescs.CD_int));
            codeBuilder.areturn();
            codeBuilder.labelBinding(endLabel);
        });
    }

    private void findGotoLabelDepths(IrStatement statement, int currentDepth) {
        if (statement == null) {
            return;
        }
        switch (statement) {
            case IrStatement.Block block -> block.statements().forEach(s -> findGotoLabelDepths(s, currentDepth));
            case IrStatement.If ifStmt -> {
                findGotoLabelDepths(ifStmt.whenTrue(), currentDepth);
                findGotoLabelDepths(ifStmt.whenFalse(), currentDepth);
            }
            case IrStatement.For forStmt -> {
                forStmt.initializers().forEach(s -> findGotoLabelDepths(s, currentDepth));
                findGotoLabelDepths(forStmt.body(), currentDepth);
            }
            case IrStatement.Foreach foreachStmt -> findGotoLabelDepths(foreachStmt.body(), currentDepth);
            case IrStatement.While whileStmt -> findGotoLabelDepths(whileStmt.body(), currentDepth);
            case IrStatement.DoWhile doWhileStmt -> findGotoLabelDepths(doWhileStmt.body(), currentDepth);
            case IrStatement.Try tryStmt -> {
                int protectedDepth = tryStmt.finallyBody() != null ? currentDepth + 1 : currentDepth;
                findGotoLabelDepths(tryStmt.body(), protectedDepth);
                tryStmt.catches().forEach(c -> findGotoLabelDepths(c.body(), protectedDepth));
                findGotoLabelDepths(tryStmt.finallyBody(), currentDepth);
            }
            case IrStatement.Lock lockStmt -> findGotoLabelDepths(lockStmt.body(), currentDepth + 1);
            case IrStatement.Checked checkedStmt -> findGotoLabelDepths(checkedStmt.body(), currentDepth);
            case IrStatement.Labeled labeled -> {
                gotoLabelDepths.put(labeled.label(), currentDepth);
                findGotoLabelDepths(labeled.statement(), currentDepth);
            }
            case IrStatement.Switch switchStmt -> switchStmt.sections().forEach(s -> s.statements().forEach(stmt -> findGotoLabelDepths(stmt, currentDepth)));
            case IrStatement.Using usingStmt -> findGotoLabelDepths(usingStmt.body(), currentDepth + 1);
            default -> {}
        }
    }
    
    private void allocateParameters(FunctionSymbol symbol) {
        int i = 0;
        List<IrCapture> leading = LambdaNames.isLambdaBody(symbol)
                ? captureMap.getOrDefault(symbol, List.of()) : List.of();
        for (IrCapture capture : leading) {
            // A lambda body receives its captures ahead of the interface method's own
            // parameters; each is already the shared cell, so no copy is made.
            localSlots.put(capture.symbol(), codeBuilder.parameterSlot(i));
            i++;
        }
        for (ParameterSymbol param : symbol.parameters()) {
            // parameterSlot indexes the descriptor's parameters; for an instance method it
            // accounts for the receiver's physical slot 0 itself.
            int parameterSlot = codeBuilder.parameterSlot(i);
            if (capturedValues.containsKey(param) && !isRefOrOutParameter(param)) {
                // The public/source descriptor still receives the parameter by value. Lift
                // it once on entry so this method and all nested functions share one cell.
                int cellSlot = allocateSymbolSlot(param, param.type());
                codeBuilder.aload(cellSlot);
                codeBuilder.ldc(0);
                loadLocal(toTypeKind(param.type()), parameterSlot);
                emitArrayStore(toTypeKind(param.type()));
            } else {
                // A ref/out parameter already arrives as a cell; an uncaptured value stays
                // in its ordinary parameter slot.
                localSlots.put(param, parameterSlot);
            }
            i++;
        }
        List<IrCapture> captures = captureMap.get(symbol);
        if (captures == null || !leading.isEmpty()) {
            return;
        }
        for (IrCapture capture : captures) {
            // Every implicit capture parameter is itself the shared cell. No copy is made
            // here: nested and recursive local functions must observe the same mutations.
            int slot = codeBuilder.parameterSlot(i);
            localSlots.put(capture.symbol(), slot);
            i++;
        }
    }

    /// The resolved element type of a captured variable. `var` locals retain an inferred
    /// placeholder on their declaration symbol, while their lowered uses carry the concrete
    /// type, so the capture plan is authoritative whenever one exists.
    private TypeSymbol capturedType(Symbol symbol, TypeSymbol fallback) {
        IrValueType captured = capturedValues.get(symbol);
        return captured == null ? fallback : captured.sourceType();
    }

    private boolean isCellBacked(Symbol symbol) {
        return isRefOrOutParameter(symbol) || capturedValues.containsKey(symbol);
    }

    /// Allocates storage for one source variable. A captured variable is initialized to a
    /// valid one-element cell immediately, even when C# definite assignment says its value
    /// cannot yet be read; this keeps every verifier path typed and lets later stores target
    /// the cell without a separate allocation state.
    private int allocateSymbolSlot(Symbol symbol, TypeSymbol type) {
        Integer existing = localSlots.get(symbol);
        if (existing != null) {
            return existing;
        }
        if (!isCellBacked(symbol)) {
            int slot = codeBuilder.allocateLocal(toTypeKind(type));
            localSlots.put(symbol, slot);
            return slot;
        }
        int slot = codeBuilder.allocateLocal(java.lang.classfile.TypeKind.REFERENCE);
        emitNewArrayCell(type);
        codeBuilder.astore(slot);
        localSlots.put(symbol, slot);
        return slot;
    }

    private void loadSymbolValue(Symbol symbol, TypeSymbol type) {
        int slot = localSlots.get(symbol);
        if (isCellBacked(symbol)) {
            emitCellLoad(type, slot);
        } else {
            loadLocal(toTypeKind(type), slot);
        }
    }

    /// Stores the value currently on top of the operand stack into a source variable.
    /// `leaveValue` preserves assignment-expression semantics; declarations, pattern binds
    /// and iteration-variable writes pass false.
    private void storeSymbolValue(Symbol symbol, TypeSymbol type, boolean leaveValue) {
        java.lang.classfile.TypeKind kind = toTypeKind(type);
        int slot = localSlots.get(symbol);
        if (!isCellBacked(symbol)) {
            if (leaveValue) {
                emitDup(kind);
            }
            storeLocal(kind, slot);
            return;
        }

        int valueSlot = codeBuilder.allocateLocal(kind);
        storeLocal(kind, valueSlot);
        codeBuilder.aload(slot);
        codeBuilder.ldc(0);
        loadLocal(kind, valueSlot);
        emitArrayStore(kind);
        if (leaveValue) {
            loadLocal(kind, valueSlot);
        }
    }

    static MethodTypeDesc buildMethodTypeDesc(FunctionSymbol symbol, Map<FunctionSymbol, List<IrCapture>> captureMap) {
        ClassDesc returnDesc = toClassDesc(symbol.returnType());
        List<ClassDesc> declared = new ArrayList<>();
        for (ParameterSymbol param : symbol.parameters()) {
            ClassDesc descriptor = toClassDesc(param.type());
            // A `ref`/`out` parameter is passed as a one-element array cell at V# boundaries
            // (the feature matrix's planned model), so its JVM parameter is the array.
            if (isRefOrOutParameter(param)) {
                descriptor = descriptor.arrayType();
            }
            declared.add(descriptor);
        }
        List<ClassDesc> captured = new ArrayList<>();
        List<IrCapture> captures = captureMap.get(symbol);
        if (captures != null) {
            for (IrCapture capture : captures) {
                // Captures are shared variables, not snapshots. A one-element array is the
                // same cell representation already used by ref/out.
                captured.add(cellElementClassDesc(
                        capture.type().sourceType()).arrayType());
            }
        }
        // A lambda body puts its captures *first*: `LambdaMetafactory` prepends the call
        // site's captured arguments to the interface method's own, so any other order links
        // to a descriptor the JVM will not accept. Every other callable keeps the
        // trailing order its direct callers already push.
        List<ClassDesc> paramDescs = new ArrayList<>();
        if (LambdaNames.isLambdaBody(symbol)) {
            paramDescs.addAll(captured);
            paramDescs.addAll(declared);
        } else {
            paramDescs.addAll(declared);
            paramDescs.addAll(captured);
        }
        return MethodTypeDesc.of(returnDesc, paramDescs.toArray(new ClassDesc[0]));
    }

    private static ClassDesc cellElementClassDesc(TypeSymbol type) {
        if (type instanceof NamedTypeSymbol named) {
            String carrier = CorelibCarriers.javaClassName(named.qualifiedName()).orElse(null);
            if (carrier != null) {
                return ClassDesc.of(carrier);
            }
        }
        return toClassDesc(type);
    }

    /// The single JVM carrier descriptor for a V# type, shared with [JvmBackend] so a field
    /// is *declared* with exactly the descriptor its accesses reference: the JVM resolves a
    /// field by name and descriptor together, so two mappings mean `NoSuchFieldError`.
    ///
    /// Types with no dedicated carrier yet (arrays, tuples, nullable, ranges) intentionally
    /// fall through to `Object`; that is a lossy but self-consistent carrier, and it is what
    /// every other stage already assumes.
    ///
    /// A builtin's descriptor is derived from the same [JvmTypeKind] that drives the load,
    /// store and return *instructions* ([#toTypeKind]), never from its V# spelling. The two
    /// cannot then disagree by construction: naming the builtins individually is how `uint`,
    /// `sbyte` and the native integers once ended up declared as `Object` while their bodies
    /// used `iload`, which the verifier rejects.
    static ClassDesc toClassDesc(TypeSymbol type) {
        if (type instanceof BuiltinType builtin) {
            return switch (builtin.jvmTypeKind()) {
                case BOOLEAN -> ConstantDescs.CD_boolean;
                case BYTE -> ConstantDescs.CD_byte;
                case SHORT -> ConstantDescs.CD_short;
                case CHAR -> ConstantDescs.CD_char;
                case INT -> ConstantDescs.CD_int;
                case LONG -> ConstantDescs.CD_long;
                case FLOAT -> ConstantDescs.CD_float;
                case DOUBLE -> ConstantDescs.CD_double;
                case VOID -> ConstantDescs.CD_void;
                // `string` and `decimal` are the builtins with reference carriers of their
                // own; `object` and `dynamic` remain carried as `Object`.
                case REFERENCE -> switch (builtin) {
                    case STRING -> ConstantDescs.CD_String;
                    case DECIMAL -> CD_BIG_DECIMAL;
                    default -> ConstantDescs.CD_Object;
                };
            };
        } else if (type instanceof NamedTypeSymbol namedType) {
            // An enum is carried as its underlying `int`, so it is `I` in a descriptor,
            // never a class reference: no class is emitted for an enum declaration.
            if (namedType.declaredKind() == NamedTypeSymbol.DeclaredKind.ENUM) {
                return ConstantDescs.CD_int;
            }
            // `System.String` is the keyword `string` written long-hand, so it takes the
            // keyword's descriptor. This is deliberately *not* a `CorelibCarriers` entry: that
            // table decides the rule's carrier member surface as well as descriptors, and enrolling
            // `System.String` in it would hand `string` every `java.lang.String` method, so
            // `Split`, `IndexOf` and `Replace` would silently stop meaning what C# says they
            // mean. A descriptor is all that is shared here, and nothing else.
            if (namedType.qualifiedName().equals("System.String")) {
                return ConstantDescs.CD_String;
            }
            // A corelib name with a carrier class *is* that class in every descriptor
            // position, not only in the two that asked for it explicitly (`catch`, cell
            // elements): a `System.Exception` local holds a `java.lang.Exception`, and a call
            // on it must name that owner. Spelling `System/Exception` here stayed internally
            // consistent - declaration and use agreed - while naming a class that is never
            // emitted and never loads, so the mismatch only surfaced at run time.
            return CorelibCarriers.javaClassName(namedType.qualifiedName())
                    .map(ClassDesc::of)
                    .orElseGet(() -> ClassDesc.of(namedType.qualifiedName()));
        } else if (type instanceof TypeSymbol.Constructed constructed) {
            // Java and V# generic applications are erased to their definition's JVM class.
            // Type arguments remain semantic and never manufacture a parameterized descriptor.
            return toClassDesc(constructed.definition());
        } else if (type instanceof TypeSymbol.Array array) {
            // `int[]` is `[I` and `int[][]` is `[[I`; a multi-dimensional `int[,]` is also a
            // JVM array of arrays, so each declared rank contributes that many levels.
            ClassDesc descriptor = toClassDesc(array.element());
            for (int level = 0; level < array.jvmDepth(); level++) {
                descriptor = descriptor.arrayType();
            }
            return descriptor;
        } else if (type instanceof TypeSymbol.Wildcard wildcard) {
            // A wildcard erases to its bound, which is the descriptor the JVM already uses
            // for the position it came from.
            return toClassDesc(wildcard.bound());
        } else if (type instanceof TypeParameterSymbol parameter) {
            // A position left standing as a bare type parameter is carried by its bound's
            // erasure, which is the descriptor the declaration itself uses. Every V#
            // parameter and every unbounded Java one bounds by `object` and lands on the
            // fallback below unchanged; a bounded one - `Enum.valueOf`'s `T extends Enum<T>` -
            // must reach `Ljava/lang/Enum;` or the call site names a method that does not
            // exist.
            return toClassDesc(parameter.bound());
        }
        return ConstantDescs.CD_Object;
    }

    private void emitStatement(IrStatement statement) {
        switch (statement) {
            case IrStatement.Block block -> {
                for (IrStatement stmt : block.statements()) {
                    emitStatement(stmt);
                }
            }
            case IrStatement.Expression exprStmt -> {
                IrExpression expression = exprStmt.expression();
                // A discarded erased generic result must not be narrowed first: `Map.Put`
                // returns the *previous* value, genuinely absent on a first insert, and
                // unboxing a null the program never reads would throw where C# and Java do
                // not. The erased reference is popped in its place.
                discardedCall = erasedResultCall(expression);
                emitExpression(expression);
                boolean discardedErased = discardedCall != null;
                discardedCall = null;
                if (discardedErased) {
                    codeBuilder.pop();
                } else if (!expression.type().isVoid()) {
                    // A discarded value must be popped by its slot width: `long` and `double`
                    // occupy two, and `pop` on one of them is a verifier error, not a leak.
                    emitPop(toTypeKind(expression.type().sourceType()));
                }
            }
            case IrStatement.Locals locals -> {
                for (IrLocal local : locals.locals()) {
                    TypeSymbol storageType = capturedType(local.symbol(),
                            local.initializer() == null
                                    ? local.symbol().type()
                                    : local.initializer().type().sourceType());
                    allocateSymbolSlot(local.symbol(), storageType);
                    if (local.initializer() != null) {
                        emitExpression(local.initializer());
                        storeSymbolValue(local.symbol(), storageType, false);
                    }
                }
            }
            case IrStatement.Return ret -> emitReturn(ret);
            case IrStatement.If ifStmt -> {
                emitExpression(ifStmt.condition());
                Label falseLabel = codeBuilder.newLabel();
                Label endLabel = codeBuilder.newLabel();
                
                codeBuilder.ifeq(falseLabel);
                emitStatement(ifStmt.whenTrue());
                if (ifStmt.whenFalse() != null) {
                    codeBuilder.goto_(endLabel);
                    codeBuilder.labelBinding(falseLabel);
                    emitStatement(ifStmt.whenFalse());
                    codeBuilder.labelBinding(endLabel);
                } else {
                    codeBuilder.labelBinding(falseLabel);
                }
            }
            case IrStatement.For forStmt -> {
                for (IrStatement init : forStmt.initializers()) {
                    emitStatement(init);
                }
                
                Label startLabel = codeBuilder.newLabel();
                Label continueLabel = codeBuilder.newLabel();
                Label endLabel = codeBuilder.newLabel();
                forStmt.initializers().forEach(this::emitStatement);
                codeBuilder.labelBinding(startLabel);
                if (forStmt.condition() != null) {
                    emitExpression(forStmt.condition());
                    codeBuilder.ifeq(endLabel);
                }
                breakTargets.push(new JumpTarget(endLabel, activeCleanups.size()));
                continueTargets.push(new JumpTarget(continueLabel, activeCleanups.size()));
                try {
                    emitStatement(forStmt.body());
                } finally {
                    breakTargets.pop();
                    continueTargets.pop();
                }
                codeBuilder.labelBinding(continueLabel);
                for (IrExpression updater : forStmt.iterators()) {
                    emitExpression(updater);
                    if (!updater.type().isVoid()) {
                        codeBuilder.pop();
                    }
                }
                codeBuilder.goto_(startLabel);
                codeBuilder.labelBinding(endLabel);
            }
            case IrStatement.Foreach foreachStmt -> emitForeach(foreachStmt);
            case IrStatement.While whileStmt -> {
                Label startLabel = codeBuilder.newLabel();
                Label endLabel = codeBuilder.newLabel();

                continueTargets.push(new JumpTarget(startLabel, activeCleanups.size()));
                breakTargets.push(new JumpTarget(endLabel, activeCleanups.size()));

                codeBuilder.labelBinding(startLabel);
                emitExpression(whileStmt.condition());
                codeBuilder.ifeq(endLabel);
                emitStatement(whileStmt.body());
                codeBuilder.goto_(startLabel);
                codeBuilder.labelBinding(endLabel);

                continueTargets.pop();
                breakTargets.pop();
            }
            case IrStatement.DoWhile doWhileStmt -> {
                Label bodyLabel = codeBuilder.newLabel();
                Label conditionLabel = codeBuilder.newLabel();
                Label endLabel = codeBuilder.newLabel();

                continueTargets.push(new JumpTarget(conditionLabel, activeCleanups.size()));
                breakTargets.push(new JumpTarget(endLabel, activeCleanups.size()));

                codeBuilder.labelBinding(bodyLabel);
                emitStatement(doWhileStmt.body());
                codeBuilder.labelBinding(conditionLabel);
                emitExpression(doWhileStmt.condition());
                codeBuilder.ifne(bodyLabel);
                codeBuilder.labelBinding(endLabel);

                continueTargets.pop();
                breakTargets.pop();
            }
            case IrStatement.Break _ -> {
                JumpTarget target = breakTargets.peek();
                emitCleanups(activeCleanups.size() - target.depth());
                codeBuilder.goto_(target.label());
            }
            case IrStatement.Continue _ -> {
                JumpTarget target = continueTargets.peek();
                emitCleanups(activeCleanups.size() - target.depth());
                codeBuilder.goto_(target.label());
            }
            case IrStatement.Throw throwStmt -> {
                if (throwStmt.expression() == null) {
                    // `throw;` reloads the innermost enclosing handler's caught reference and
                    // rethrows it. The binder guarantees a handler is in scope.
                    codeBuilder.aload(handledExceptionSlots.peek());
                } else {
                    emitExpression(throwStmt.expression());
                }
                codeBuilder.athrow();
            }
            case IrStatement.Try tryStmt -> emitTry(tryStmt);
            case IrStatement.Switch switchStmt -> emitSwitch(switchStmt);
            case IrStatement.SwitchGoto switchGoto -> {
                SwitchDispatch dispatch = switchGotoTargets.peek();
                emitCleanups(activeCleanups.size() - dispatch.depth());
                Label target = switch (switchGoto.kind()) {
                    case CASE -> dispatch.caseTargets().get(requireCaseConstant(switchGoto.value(),
                            "'goto case'"));
                    case DEFAULT -> dispatch.defaultTarget();
                };
                if (target == null) {
                    throw new UnsupportedOperationException(
                            "'goto case'/'goto default' has no matching label in the enclosing switch");
                }
                codeBuilder.goto_(target);
            }
            case IrStatement.Labeled labeled -> {
                codeBuilder.labelBinding(getOrCreateGotoLabel(labeled.label()));
                emitStatement(labeled.statement());
            }
            case IrStatement.Goto gotoStmt -> {
                int targetDepth = gotoLabelDepths.get(gotoStmt.label());
                emitCleanups(activeCleanups.size() - targetDepth);
                codeBuilder.goto_(getOrCreateGotoLabel(gotoStmt.label()));
            }
            case IrStatement.Checked checked -> {
                // The JVM has no hardware checked/unchecked distinction, so the block emits
                // its body under the context flag the arithmetic and conversion emitters read.
                boolean priorContext = checkedContext;
                checkedContext = checked.checked();
                emitStatement(checked.body());
                checkedContext = priorContext;
            }
            case IrStatement.Empty empty -> {
                // Do nothing
            }
            case IrStatement.Lock lockStmt -> emitLock(lockStmt);
            case IrStatement.Using usingStmt -> emitUsing(usingStmt);
            default -> throw new UnsupportedOperationException(
                    "CodeEmitter does not yet emit statement form: " + statement.getClass().getSimpleName());
        }
    }

    /// Emits a C-style `switch` over an int-typed governing expression and int-constant or
    /// `default`/discard labels only. Every other switch form (string/type/relational/
    /// recursive/list/slice patterns, `when` guards) is unimplemented and throws rather than
    /// emitting a wrong or partial dispatch - real C# pattern-matching switches need a
    /// general pattern compiler, which is a separate, larger backend increment.
    ///
    /// Sections never fall through to one another in C#: FlowAnalysis already requires each
    /// section to end in `break`/`return`/`throw`/`goto case`/`goto default`, so this method
    /// only has to dispatch into the matching section and emit its statements in order.
    /// `break` inside the switch reuses the same `breakTargets` stack loops use, which is
    /// correct because C# `break` always means the innermost enclosing loop or switch.
    /// Emits `try`/`catch` directly and delegates any form with `finally` to the shared
    /// protected-region path. Every catch clause must have an explicit type and no `when`
    /// filter; a bare `catch {}` or guarded catch throws rather than being approximated.
    /// Expression binding has already proved every explicit catch type derives from
    /// `Throwable`; discovered Java types use their own descriptor and the corelib exception
    /// names alias their JVM carriers ([#runtimeClassDesc]).
    private void emitTry(IrStatement.Try tryStmt) {
        if (tryStmt.finallyBody() != null) {
            emitTryFinally(tryStmt);
            return;
        }

        Label tryStart = codeBuilder.newLabel();
        Label tryEnd = codeBuilder.newLabel();
        Label endLabel = codeBuilder.newLabel();

        codeBuilder.labelBinding(tryStart);
        emitStatement(tryStmt.body());
        codeBuilder.labelBinding(tryEnd);
        codeBuilder.goto_(endLabel);

        for (IrCatch catchClause : tryStmt.catches()) {
            emitCatchHandler(catchClause, tryStart, tryEnd, endLabel);
        }

        // A `goto endLabel` is emitted unconditionally above regardless of whether the try
        // body or a catch body already returned, so `endLabel` can be bound at the very end
        // of the method. That is the shared R8 shape, and `emit` closes it once for every
        // construct by ending an unreachable-tail method with a `nop`.
        codeBuilder.labelBinding(endLabel);
    }

    /// Emits one `catch` clause as a JVM exception handler guarding `[regionStart, regionEnd)`
    /// and falling through to `continueLabel` when the handler body completes normally.
    ///
    /// A typed clause becomes a typed handler entry. A bare `catch` becomes the JVM's typeless
    /// catch-all entry, whose handler class is `java.lang.Throwable`: C#'s bare `catch`
    /// means "every exception", and on this platform every thrown value is a `Throwable`. The
    /// one divergence - a bare `catch` here also sees `java.lang.Error`, which has no CLR
    /// counterpart - is recorded in the feature matrix rather than papered over with an
    /// `instanceof Exception` test that would swallow-and-rethrow with different unwinding.
    ///
    /// The caught reference is always stored into a local, even when the clause names no
    /// variable, because a bare `throw;` anywhere in the handler body needs it back
    /// ([#handledExceptionSlots]). The JVM begins every handler with exactly that one value
    /// on the stack, so the store also leaves the stack empty for the body.
    private void emitCatchHandler(IrCatch catchClause, Label regionStart, Label regionEnd,
            Label continueLabel) {
        if (catchClause.filter() != null) {
            throw new UnsupportedOperationException("CodeEmitter does not yet emit catch filters ('when')");
        }

        Label handlerStart = codeBuilder.newLabel();
        codeBuilder.labelBinding(handlerStart);
        if (catchClause.type() == null) {
            codeBuilder.exceptionCatchAll(regionStart, regionEnd, handlerStart);
        } else {
            codeBuilder.exceptionCatch(regionStart, regionEnd, handlerStart,
                    runtimeClassDesc(catchClause.type().sourceType()));
        }

        int slot = codeBuilder.allocateLocal(java.lang.classfile.TypeKind.REFERENCE);
        codeBuilder.astore(slot);
        if (catchClause.variable() != null) {
            LocalSymbol variable = catchClause.variable();
            TypeSymbol type = capturedType(variable, variable.type());
            if (isCellBacked(variable)) {
                allocateSymbolSlot(variable, type);
                codeBuilder.aload(slot);
                storeSymbolValue(variable, type, false);
            } else {
                localSlots.put(variable, slot);
            }
        }

        handledExceptionSlots.push(slot);
        try {
            emitStatement(catchClause.body());
        } finally {
            handledExceptionSlots.pop();
        }
        codeBuilder.goto_(continueLabel);
    }

    /// `try { body } [catch...] finally { cleanup }` delegates to `emitProtectedRegion`
    /// with its real `finally` body as the cleanup and its declared catch clauses.
    private void emitTryFinally(IrStatement.Try tryStmt) {
        emitProtectedRegion(tryStmt.body(), tryStmt.catches(), () -> emitStatement(tryStmt.finallyBody()));
    }

    /// `lock (expr) { body }`: acquires the JVM's built-in object monitor with
    /// `monitorenter` and delegates to `emitProtectedRegion` (no catch clauses of its own)
    /// with `monitorexit` as the cleanup - real C#'s `lock` is exactly `Monitor.Enter`/
    /// `Monitor.Exit` around a `try`/`finally`, and the JVM already has these as dedicated
    /// opcodes rather than library calls, so no corelib/interop support is needed here.
    /// Scoped to `REFERENCE`-kind lock expressions: `monitorenter`/`monitorexit` operate
    /// on an object reference, exactly as real C# already requires the lock expression to
    /// be a reference type (a diagnostic this compiler does not yet enforce at the
    /// semantic layer - see pending priority 11 - so the backend itself must refuse
    /// rather than emit `monitorenter` against a primitive value).
    private void emitLock(IrStatement.Lock lockStmt) {
        java.lang.classfile.TypeKind kind = toTypeKind(lockStmt.expression().type().sourceType());
        if (kind != java.lang.classfile.TypeKind.REFERENCE) {
            throw new UnsupportedOperationException(
                    "CodeEmitter only emits 'lock' over a reference-typed expression currently, not " + kind);
        }

        emitExpression(lockStmt.expression());
        int lockSlot = codeBuilder.allocateLocal(java.lang.classfile.TypeKind.REFERENCE);
        codeBuilder.dup();
        codeBuilder.astore(lockSlot);
        codeBuilder.monitorenter();

        emitProtectedRegion(lockStmt.body(), List.of(), () -> {
            codeBuilder.aload(lockSlot);
            codeBuilder.monitorexit();
        });
    }

    private void emitUsing(IrStatement.Using usingStmt) {
        List<Runnable> resourceLoads = new ArrayList<>();
        if (usingStmt.resource() instanceof IrStatement.Locals localsStmt) {
            emitStatement(localsStmt);
            for (IrLocal local : localsStmt.locals()) {
                LocalSymbol symbol = local.symbol();
                TypeSymbol type = capturedType(symbol, symbol.type());
                resourceLoads.add(() -> loadSymbolValue(symbol, type));
            }
        } else if (usingStmt.resource() instanceof IrStatement.Expression exprStmt) {
            emitExpression(exprStmt.expression());
            int slot = codeBuilder.allocateLocal(java.lang.classfile.TypeKind.REFERENCE);
            codeBuilder.astore(slot);
            resourceLoads.add(() -> codeBuilder.aload(slot));
        } else {
            throw new IllegalStateException("invalid using resource");
        }

        emitProtectedRegion(usingStmt.body() == null ? new IrStatement.Empty(usingStmt.span()) : usingStmt.body(), List.of(), () -> {
            for (int i = resourceLoads.size() - 1; i >= 0; i--) {
                Runnable load = resourceLoads.get(i);
                Label skipClose = codeBuilder.newLabel();
                load.run();
                codeBuilder.ifnull(skipClose);
                load.run();
                codeBuilder.invokeinterface(
                        java.lang.constant.ClassDesc.of("java.lang.AutoCloseable"),
                        "close",
                        java.lang.constant.MethodTypeDesc.ofDescriptor("()V")
                );
                codeBuilder.labelBinding(skipClose);
            }
        });
    }

    /// Emits `body` (plus, for `try`, its `catches`) protected by `cleanup`, guaranteeing
    /// `cleanup` runs exactly once on every way execution can leave the region - the JVM's
    /// only available mechanism for this, since bytecode has no `jsr`/`ret` (removed) and
    /// no other "run this once regardless of exit reason" primitive is duplicating
    /// `cleanup`'s bytecode at each such path:
    ///
    ///  1. Normal completion (`body`, or a catch body, runs to its end without throwing or
    ///     returning): all such paths converge on `normalPath`, `cleanup` runs once,
    ///     execution falls through to whatever follows the whole construct.
    ///  2. An exception escapes `body` OR a catch body: a catch-all (`exceptionCatchAll`)
    ///     handler spanning the *entire* protected region - `body` plus every catch body,
    ///     since a catch body throwing must still trigger cleanup - saves the exception,
    ///     runs `cleanup`, then rethrows it.
    ///  3. A `return` lexically inside `body` or a catch body: `emitReturn` consults
    ///     `activeCleanups` (pushed here around exactly the protected region, popped
    ///     before `cleanup` is emitted for itself - a cleanup is never "inside" its own
    ///     protection) and runs every active cleanup, innermost first, before the real
    ///     `return_`, storing the return value in a fresh temp local first so the
    ///     duplicated cleanups' own bytecode cannot disturb it.
    ///
    ///  4. A `break`, `continue`, `goto` or `goto case` inside `body` or a catch body whose
    ///     target lies outside the region: duplication is scoped to *only* the regions the
    ///     jump actually escapes. Each break/continue target records `activeCleanups.size()`
    ///     at the point it was established ([JumpTarget]), each `goto` label records its
    ///     depth in a pre-pass (`findGotoLabelDepths`) because a label can be jumped to
    ///     before it is emitted, and the jump runs exactly the difference. A jump that stays
    ///     inside the region - a loop wholly contained in a `try` - therefore duplicates
    ///     nothing, which is the whole point of tracking depth rather than a plain flag.
    private void emitProtectedRegion(IrStatement body, List<IrCatch> catches, Runnable cleanup) {
        Label bodyStart = codeBuilder.newLabel();
        Label bodyEnd = codeBuilder.newLabel();
        Label protectedEnd = codeBuilder.newLabel();
        Label normalPath = codeBuilder.newLabel();
        Label endLabel = codeBuilder.newLabel();

        activeCleanups.push(cleanup);

        codeBuilder.labelBinding(bodyStart);
        emitStatement(body);
        codeBuilder.labelBinding(bodyEnd);
        codeBuilder.goto_(normalPath);

        for (IrCatch catchClause : catches) {
            emitCatchHandler(catchClause, bodyStart, bodyEnd, normalPath);
        }

        codeBuilder.labelBinding(protectedEnd);
        activeCleanups.pop();

        Label catchAllStart = codeBuilder.newLabel();
        codeBuilder.exceptionCatchAll(bodyStart, protectedEnd, catchAllStart);

        codeBuilder.labelBinding(normalPath);
        cleanup.run();
        codeBuilder.goto_(endLabel);

        codeBuilder.labelBinding(catchAllStart);
        int excSlot = codeBuilder.allocateLocal(java.lang.classfile.TypeKind.REFERENCE);
        codeBuilder.astore(excSlot);
        cleanup.run();
        codeBuilder.aload(excSlot);
        codeBuilder.athrow();

        codeBuilder.labelBinding(endLabel);
    }

    private void emitCleanups(int count) {
        if (count <= 0) return;
        int i = 0;
        for (Runnable cleanup : activeCleanups) {
            if (i >= count) break;
            cleanup.run();
            i++;
        }
    }

    /// `return`, running every currently active cleanup (innermost first) before the real
    /// `return_` - see `emitProtectedRegion`'s class comment. A non-`void` return value is
    /// stashed in a fresh temp local first so the cleanups' own bytecode (arbitrary
    /// statements, including further branches) cannot disturb a value sitting on the
    /// operand stack across them.
    private void emitReturn(IrStatement.Return ret) {
        if (activeCleanups.isEmpty()) {
            if (ret.expression() != null) {
                emitExpression(ret.expression());
                codeBuilder.return_(toTypeKind(ret.expression().type().sourceType()));
            } else {
                codeBuilder.return_();
            }
            return;
        }
        if (ret.expression() != null) {
            java.lang.classfile.TypeKind kind = toTypeKind(ret.expression().type().sourceType());
            emitExpression(ret.expression());
            int tempSlot = codeBuilder.allocateLocal(kind);
            storeLocal(kind, tempSlot);
            for (Runnable cleanup : activeCleanups) {
                cleanup.run();
            }
            loadLocal(kind, tempSlot);
            codeBuilder.return_(kind);
        } else {
            for (Runnable cleanup : activeCleanups) {
                cleanup.run();
            }
            codeBuilder.return_();
        }
    }


    /// Lazily creates or retrieves a JVM `Label` for a source-level `goto` label name.
    /// Labels may be referenced by `goto` before they are defined by `Labeled`, so the
    /// map always creates the label on first access regardless of order.
    private Label getOrCreateGotoLabel(String name) {
        return gotoLabels.computeIfAbsent(name, _ -> codeBuilder.newLabel());
    }

    /// The JVM class a named type denotes at runtime: the class a `catch` clause tests, and
    /// the `instanceof`/`checkcast` target of a type pattern.
    ///
    /// The corelib exception names (`corelib.vs`, the design) map to their JVM carriers through
    /// [CorelibCarriers] - the same table the binder decides throwability from - mirroring
    /// the `System.Console.WriteLine` special case in the `Call` expression. Every other
    /// named type is its own class: types discovered from the module path keep their
    /// qualified Java name, and V#-declared types name the holder this compilation emits for
    /// them (`toClassDesc`, the design). The remaining corelib placeholders (`System.Object` and
    /// friends) declare no runtime carrier at all and the binder refuses them in both
    /// positions, so they never arrive here.
    private static ClassDesc runtimeClassDesc(TypeSymbol type) {
        if (type instanceof TypeSymbol.Constructed constructed) {
            return runtimeClassDesc(constructed.definition());
        }
        if (type instanceof NamedTypeSymbol named) {
            String javaClass = CorelibCarriers.javaClassName(named.qualifiedName()).orElse(null);
            if (javaClass != null) {
                return ClassDesc.of(javaClass);
            }
            return toClassDesc(named);
        }
        throw new UnsupportedOperationException(
                "CodeEmitter cannot map named type: " + type.displayName());
    }

    /// Constructs a `vsharp.runtime.VsTupleN` (arity 2-8, the closed set that exists) via
    /// `new`/`dup`/`invokespecial &lt;init&gt;` - the same construction shape `DefaultValue`
    /// already proves works. `VsTupleN` is a generic record, so its canonical constructor is
    /// erased to `N` `Object` parameters regardless of each element's real type: every
    /// element must be boxed first (`emitBox`) before the constructor call, unlike every
    /// other expression form this backend emits so far, which stays unboxed throughout.
    private void emitTuple(IrExpression.Tuple tuple) {
        int arity = tuple.elements().size();
        if (arity < 2 || arity > 8) {
            throw new UnsupportedOperationException(
                    "CodeEmitter only emits tuples of arity 2-8 (vsharp.runtime.VsTuple2..VsTuple8), got: " + arity);
        }
        ClassDesc tupleClass = ClassDesc.of("vsharp.runtime.VsTuple" + arity);
        codeBuilder.new_(tupleClass);
        codeBuilder.dup();
        List<ClassDesc> paramDescs = new ArrayList<>(arity);
        for (IrExpression element : tuple.elements()) {
            emitExpression(element);
            emitBox(element.type().sourceType());
            paramDescs.add(ConstantDescs.CD_Object);
        }
        codeBuilder.invokespecial(tupleClass, "<init>",
                MethodTypeDesc.of(ConstantDescs.CD_void, paramDescs));
    }

    private void emitTupleDeconstructionStore(IrExpression.TupleDeconstructionStore store) {
        emitExpression(store.value());
        int tupleSlot = codeBuilder.allocateLocal(java.lang.classfile.TypeKind.REFERENCE);
        codeBuilder.dup();
        codeBuilder.astore(tupleSlot);
        emitDeconstructionTargets(store.targets(), store.components(), tupleSlot);
    }

    /// Stores each element of the value parked in `tupleSlot` into the matching target.
    /// Separate from its caller so a nested tuple target can re-enter it one level down.
    ///
    /// When `components` is empty the parked value is a tuple and each element is read
    /// through `VsTupleN.itemN`; otherwise it is a positional `record struct` and each
    /// element is the component field at that position, read with `getfield` at its
    /// declared descriptor - so no boxing is involved on that path.
    private void emitDeconstructionTargets(List<IrExpression> targets,
            List<vsharp.compiler.semantics.symbols.FieldSymbol> components, int tupleSlot) {
        int arity = targets.size();
        ClassDesc tupleClass = ClassDesc.of("vsharp.runtime.VsTuple" + arity);

        for (int position = 0; position < arity; position++) {
            int itemIndex = position + 1;
            IrExpression target = targets.get(position);
            java.lang.classfile.TypeKind kind = toTypeKind(target.type().sourceType());

            int componentIndex = position;
            Runnable produceValue = components.isEmpty() ? null : () -> {
                vsharp.compiler.semantics.symbols.FieldSymbol component = components.get(componentIndex);
                codeBuilder.aload(tupleSlot);
                codeBuilder.checkcast(declaringClassOf(component));
                codeBuilder.getfield(declaringClassOf(component), component.name(),
                        toClassDesc(component.type()));
            };
            Runnable readTupleItem = () -> {
                codeBuilder.aload(tupleSlot);
                // The slot's verified type is whatever produced the tuple, which for a
                // parameter or a nested element is `Object`, so the accessor's receiver is
                // cast before the call.
                codeBuilder.checkcast(tupleClass);
                codeBuilder.invokevirtual(tupleClass, "item" + itemIndex, MethodTypeDesc.of(ConstantDescs.CD_Object));
                if (kind != java.lang.classfile.TypeKind.REFERENCE) {
                    emitUnboxNullableElement(target.type().sourceType());
                } else {
                    // `toClassDesc`, not `runtimeClassDesc`: a deconstruction target can be
                    // any carrier, including `string` and a nested tuple, neither of which
                    // has a declared runtime class of its own.
                    codeBuilder.checkcast(toClassDesc(target.type().sourceType()));
                }
            };
            if (produceValue == null) {
                produceValue = readTupleItem;
            }

            switch (target) {
                case IrExpression.Load load
                        when load.symbol() instanceof LocalSymbol local -> {
                    // A deconstruction *declaration* - `(int x, string y) = pair;` - reaches
                    // its targets before any `IrStatement.Locals` gave them slots, so the
                    // slot is allocated here on first use, exactly as a pattern variable's
                    // is. The kind comes from the target's own IR type rather than the
                    // symbol, whose declared type is the `var` placeholder in `var (x, y)`.
                    TypeSymbol targetType = capturedType(local,
                            target.type().sourceType());
                    allocateSymbolSlot(local, targetType);
                    produceValue.run();
                    storeSymbolValue(local, targetType, false);
                }
                case IrExpression.Load load when load.symbol() instanceof vsharp.compiler.semantics.symbols.FieldSymbol field
                        && field.modifiers().contains(vsharp.compiler.syntax.SyntaxKind.STATIC) -> {
                    produceValue.run();
                    ClassDesc container = declaringClassOf(field);
                    ClassDesc descriptor = toClassDesc(field.type());
                    codeBuilder.putstatic(container, field.name(), descriptor);
                }
                case IrExpression.FieldLoad fieldLoad -> {
                    emitExpression(fieldLoad.receiver());
                    produceValue.run();
                    vsharp.compiler.semantics.symbols.FieldSymbol field = (vsharp.compiler.semantics.symbols.FieldSymbol) fieldLoad.field();
                    ClassDesc container = declaringClassOf(field);
                    ClassDesc descriptor = toClassDesc(field.type());
                    codeBuilder.putfield(container, field.name(), descriptor);
                }
                case IrExpression.ElementLoad elementLoad -> {
                    emitExpression(elementLoad.receiver());
                    for (IrExpression index : elementLoad.indices()) {
                        emitExpression(index);
                    }
                    produceValue.run();
                    emitArrayStore(kind);
                }
                case IrExpression.Tuple nested -> {
                    // `((int x, int y), string word) = value;`: the element is itself a
                    // tuple, so it is parked and taken apart by the same walk one level down.
                    produceValue.run();
                    int nestedSlot = codeBuilder.allocateLocal(
                            java.lang.classfile.TypeKind.REFERENCE);
                    codeBuilder.astore(nestedSlot);
                    emitDeconstructionTargets(nested.elements(), List.of(), nestedSlot);
                }
                default -> throw new UnsupportedOperationException(
                        "CodeEmitter cannot deconstruct into target: " + target.getClass().getSimpleName());
            }
        }
    }

    /// Boxes a value while its V# type is still available. Unsignedness is not present in a
    /// JVM `TypeKind`, so `byte`/`ushort`/`uint`/`ulong`/`nuint` use distinct runtime object
    /// carriers at object and nullable boundaries; every other type uses the standard
    /// wrapper.
    private void emitBox(TypeSymbol type) {
        if (type == BuiltinType.BYTE) {
            codeBuilder.invokestatic(CD_VS_UNSIGNED, "boxByte",
                    MethodTypeDesc.of(ConstantDescs.CD_Object, ConstantDescs.CD_byte));
            return;
        }
        if (type == BuiltinType.USHORT) {
            codeBuilder.invokestatic(CD_VS_UNSIGNED, "boxUShort",
                    MethodTypeDesc.of(ConstantDescs.CD_Object, ConstantDescs.CD_short));
            return;
        }
        if (type == BuiltinType.UINT) {
            codeBuilder.invokestatic(CD_VS_UNSIGNED, "box",
                    MethodTypeDesc.of(ConstantDescs.CD_Object, ConstantDescs.CD_int));
            return;
        }
        if (type == BuiltinType.ULONG || type == BuiltinType.NUINT) {
            codeBuilder.invokestatic(CD_VS_UNSIGNED, "box",
                    MethodTypeDesc.of(ConstantDescs.CD_Object, ConstantDescs.CD_long));
            return;
        }
        if (type == BuiltinType.DECIMAL) {
            codeBuilder.invokestatic(CD_VS_DECIMAL, "box",
                    MethodTypeDesc.of(ConstantDescs.CD_Object, CD_BIG_DECIMAL));
            return;
        }
        emitBox(toTypeKind(type));
    }

    /// Boxes a value whose source type has no carrier ambiguity. `REFERENCE` values (e.g.
    /// `string`) are already objects and need no boxing.
    private void emitBox(java.lang.classfile.TypeKind kind) {
        switch (kind) {
            case INT -> codeBuilder.invokestatic(ConstantDescs.CD_Integer, "valueOf",
                    MethodTypeDesc.of(ConstantDescs.CD_Integer, ConstantDescs.CD_int));
            case BYTE -> codeBuilder.invokestatic(ConstantDescs.CD_Byte, "valueOf",
                    MethodTypeDesc.of(ConstantDescs.CD_Byte, ConstantDescs.CD_byte));
            case SHORT -> codeBuilder.invokestatic(ConstantDescs.CD_Short, "valueOf",
                    MethodTypeDesc.of(ConstantDescs.CD_Short, ConstantDescs.CD_short));
            case CHAR -> codeBuilder.invokestatic(ConstantDescs.CD_Character, "valueOf",
                    MethodTypeDesc.of(ConstantDescs.CD_Character, ConstantDescs.CD_char));
            case LONG -> codeBuilder.invokestatic(ConstantDescs.CD_Long, "valueOf",
                    MethodTypeDesc.of(ConstantDescs.CD_Long, ConstantDescs.CD_long));
            case FLOAT -> codeBuilder.invokestatic(ConstantDescs.CD_Float, "valueOf",
                    MethodTypeDesc.of(ConstantDescs.CD_Float, ConstantDescs.CD_float));
            case DOUBLE -> codeBuilder.invokestatic(ConstantDescs.CD_Double, "valueOf",
                    MethodTypeDesc.of(ConstantDescs.CD_Double, ConstantDescs.CD_double));
            case BOOLEAN -> codeBuilder.invokestatic(ConstantDescs.CD_Boolean, "valueOf",
                    MethodTypeDesc.of(ConstantDescs.CD_Boolean, ConstantDescs.CD_boolean));
            case REFERENCE -> {
                // Already a reference type; nothing to box.
            }
            default -> throw new UnsupportedOperationException("CodeEmitter cannot box type kind: " + kind);
        }
    }

    /// Unboxes the wrapper reference on top of the stack (`wrapperDesc`, e.g. `Integer`),
    /// the inverse of `emitBox`; needed only by `is` type-pattern matching against a
    /// boxed primitive today.
    private void emitUnbox(java.lang.classfile.TypeKind kind, ClassDesc wrapperDesc) {
        switch (kind) {
            case INT -> codeBuilder.invokevirtual(wrapperDesc, "intValue", MethodTypeDesc.of(ConstantDescs.CD_int));
            case BYTE -> codeBuilder.invokevirtual(wrapperDesc, "byteValue", MethodTypeDesc.of(ConstantDescs.CD_byte));
            case SHORT -> codeBuilder.invokevirtual(wrapperDesc, "shortValue", MethodTypeDesc.of(ConstantDescs.CD_short));
            case CHAR -> codeBuilder.invokevirtual(wrapperDesc, "charValue", MethodTypeDesc.of(ConstantDescs.CD_char));
            case LONG -> codeBuilder.invokevirtual(wrapperDesc, "longValue", MethodTypeDesc.of(ConstantDescs.CD_long));
            case FLOAT -> codeBuilder.invokevirtual(wrapperDesc, "floatValue", MethodTypeDesc.of(ConstantDescs.CD_float));
            case DOUBLE -> codeBuilder.invokevirtual(wrapperDesc, "doubleValue", MethodTypeDesc.of(ConstantDescs.CD_double));
            case BOOLEAN -> codeBuilder.invokevirtual(wrapperDesc, "booleanValue", MethodTypeDesc.of(ConstantDescs.CD_boolean));
            default -> throw new UnsupportedOperationException("CodeEmitter cannot unbox type kind: " + kind);
        }
    }

    /// Emits an explicit unboxing conversion from `object` to a non-nullable value type.
    /// The standard wrappers handle the types whose JVM carrier has an unambiguous public
    /// box; `byte`/`ushort`/`uint`/`ulong`/`nuint` use the private `VsUnsigned` boxes,
    /// `decimal` uses `VsDecimal.unbox`, enums unbox their `int` carrier, and declared
    /// structs are their own reference carrier, so a `checkcast` is the whole conversion.
    private void emitUnboxObject(TypeSymbol to) {
        if (to instanceof BuiltinType builtin) {
            switch (builtin) {
                case BYTE -> codeBuilder.invokestatic(CD_VS_UNSIGNED, "unboxByte",
                        MethodTypeDesc.of(ConstantDescs.CD_byte, ConstantDescs.CD_Object));
                case USHORT -> codeBuilder.invokestatic(CD_VS_UNSIGNED, "unboxUShort",
                        MethodTypeDesc.of(ConstantDescs.CD_short, ConstantDescs.CD_Object));
                case UINT -> codeBuilder.invokestatic(CD_VS_UNSIGNED, "unboxInt",
                        MethodTypeDesc.of(ConstantDescs.CD_int, ConstantDescs.CD_Object));
                case ULONG, NUINT -> codeBuilder.invokestatic(CD_VS_UNSIGNED, "unboxLong",
                        MethodTypeDesc.of(ConstantDescs.CD_long, ConstantDescs.CD_Object));
                case DECIMAL -> codeBuilder.invokestatic(CD_VS_DECIMAL, "unbox",
                        MethodTypeDesc.of(CD_BIG_DECIMAL, ConstantDescs.CD_Object));
                case SBYTE, SHORT, INT, LONG, NINT, FLOAT, DOUBLE, BOOL, CHAR -> {
                    ClassDesc boxed = boxedClassDesc(builtin);
                    codeBuilder.checkcast(boxed);
                    emitUnbox(toTypeKind(builtin), boxed);
                }
                default -> throw new UnsupportedOperationException(
                        "CodeEmitter cannot unbox object to: " + builtin);
            }
            return;
        }
        if (to instanceof NamedTypeSymbol named) {
            if (named.declaredKind() == NamedTypeSymbol.DeclaredKind.ENUM) {
                codeBuilder.checkcast(ConstantDescs.CD_Integer);
                codeBuilder.invokevirtual(ConstantDescs.CD_Integer, "intValue",
                        MethodTypeDesc.of(ConstantDescs.CD_int));
                return;
            }
            if (named.isValueType()) {
                codeBuilder.checkcast(toClassDesc(named));
                return;
            }
        }
        throw new UnsupportedOperationException(
                "CodeEmitter cannot unbox object to: " + to.displayName());
    }

    private static ClassDesc boxedClassDesc(TypeSymbol type) {
        return switch (toTypeKind(type)) {
            case INT -> ConstantDescs.CD_Integer;
            case BYTE -> ConstantDescs.CD_Byte;
            case SHORT -> ConstantDescs.CD_Short;
            case CHAR -> ConstantDescs.CD_Character;
            case LONG -> ConstantDescs.CD_Long;
            case FLOAT -> ConstantDescs.CD_Float;
            case DOUBLE -> ConstantDescs.CD_Double;
            case BOOLEAN -> ConstantDescs.CD_Boolean;
            case REFERENCE -> toClassDesc(type);
            default -> throw new UnsupportedOperationException(
                    "CodeEmitter cannot box nullable element: " + type.displayName());
        };
    }

    /// Consumes a known-present boxed nullable carrier and pushes its underlying value.
    private void emitUnboxNullableElement(TypeSymbol element) {
        if (element == BuiltinType.BYTE) {
            codeBuilder.invokestatic(CD_VS_UNSIGNED, "unboxByte",
                    MethodTypeDesc.of(ConstantDescs.CD_byte, ConstantDescs.CD_Object));
            return;
        }
        if (element == BuiltinType.USHORT) {
            codeBuilder.invokestatic(CD_VS_UNSIGNED, "unboxUShort",
                    MethodTypeDesc.of(ConstantDescs.CD_short, ConstantDescs.CD_Object));
            return;
        }
        if (element == BuiltinType.UINT) {
            codeBuilder.invokestatic(CD_VS_UNSIGNED, "unboxInt",
                    MethodTypeDesc.of(ConstantDescs.CD_int, ConstantDescs.CD_Object));
            return;
        }
        if (element == BuiltinType.ULONG || element == BuiltinType.NUINT) {
            codeBuilder.invokestatic(CD_VS_UNSIGNED, "unboxLong",
                    MethodTypeDesc.of(ConstantDescs.CD_long, ConstantDescs.CD_Object));
            return;
        }
        ClassDesc boxed = boxedClassDesc(element);
        codeBuilder.checkcast(boxed);
        if (toTypeKind(element) != java.lang.classfile.TypeKind.REFERENCE) {
            emitUnbox(toTypeKind(element), boxed);
        }
    }

    private void emitRequireNullableValue(TypeSymbol element) {
        codeBuilder.invokestatic(CD_VS_NULLABLE, "requireValue",
                MethodTypeDesc.of(ConstantDescs.CD_Object, ConstantDescs.CD_Object));
        emitUnboxNullableElement(element);
    }

    private void emitNullableHasValue(IrExpression.NullableHasValue hasValue) {
        Label noValue = codeBuilder.newLabel();
        Label end = codeBuilder.newLabel();
        emitExpression(hasValue.receiver());
        codeBuilder.ifnull(noValue);
        codeBuilder.iconst_1();
        codeBuilder.goto_(end);
        codeBuilder.labelBinding(noValue);
        codeBuilder.iconst_0();
        codeBuilder.labelBinding(end);
    }

    private void emitNullableValue(IrExpression.NullableValue value) {
        emitExpression(value.receiver());
        emitRequireNullableValue(value.type().sourceType());
    }

    private void emitNullConditional(IrExpression.NullConditional conditional) {
        java.lang.classfile.TypeKind receiverKind = toTypeKind(
                conditional.receiver().type().sourceType());
        if (receiverKind != java.lang.classfile.TypeKind.REFERENCE) {
            throw new UnsupportedOperationException(
                    "Null-conditional receiver must have a reference carrier");
        }

        int receiverSlot = codeBuilder.allocateLocal(receiverKind);
        emitExpression(conditional.receiver());
        storeLocal(receiverKind, receiverSlot);

        Label present = codeBuilder.newLabel();
        Label end = codeBuilder.newLabel();
        loadLocal(receiverKind, receiverSlot);
        codeBuilder.ifnonnull(present);
        emitNullConditionalDefault(conditional.type().sourceType());
        codeBuilder.goto_(end);

        codeBuilder.labelBinding(present);
        conditionalReceiverSlots.push(
                new ConditionalReceiverSlot(receiverSlot, receiverKind));
        try {
            emitExpression(conditional.access());
        } finally {
            conditionalReceiverSlots.pop();
        }
        codeBuilder.labelBinding(end);
    }

    private void emitConditionalReceiver(IrExpression.ConditionalReceiver receiver) {
        ConditionalReceiverSlot slot = conditionalReceiverSlots.peek();
        if (slot == null) {
            throw new IllegalStateException(
                    "Conditional receiver used outside null-conditional access");
        }
        TypeSymbol carrierType = receiver.carrierType().sourceType();
        java.lang.classfile.TypeKind carrierKind = toTypeKind(carrierType);
        if (carrierKind != slot.kind()) {
            throw new IllegalStateException("Conditional receiver carrier mismatch");
        }
        loadLocal(slot.kind(), slot.index());
        if (carrierType instanceof TypeSymbol.Nullable nullable) {
            emitUnboxNullableElement(nullable.element());
        }
    }

    private void emitNullConditionalDefault(TypeSymbol type) {
        java.lang.classfile.TypeKind kind = toTypeKind(type);
        if (kind == java.lang.classfile.TypeKind.VOID) {
            return;
        }
        if (kind == java.lang.classfile.TypeKind.REFERENCE) {
            codeBuilder.aconst_null();
            return;
        }
        throw new IllegalStateException(
                "Null-conditional value result was not lifted: " + type.displayName());
    }

    /// Discards the value on top of the stack, respecting its JVM slot width (`pop2` for
    /// the category-2 `long`/`double` kinds, `pop` otherwise).
    private void emitPop(java.lang.classfile.TypeKind kind) {
        if (kind == java.lang.classfile.TypeKind.LONG || kind == java.lang.classfile.TypeKind.DOUBLE) {
            codeBuilder.pop2();
        } else {
            codeBuilder.pop();
        }
    }

    /// Allocates (once) a JVM local slot for a pattern-introduced variable that owns no
    /// enclosing `IrStatement.Locals` declaration - e.g. `is int n` - exactly like a
    /// `catch` clause variable's slot is allocated lazily on first use.
    private int allocatePatternLocal(LocalSymbol variable) {
        return allocatePatternLocal(variable, capturedType(variable, variable.type()));
    }

    private int allocatePatternLocal(LocalSymbol variable, TypeSymbol type) {
        return allocateSymbolSlot(variable, capturedType(variable, type));
    }

    /// The `instanceof`/`checkcast` target for an `is` type-pattern's tested type.
    ///
    /// A type test asks a runtime question, so it is answerable only for types V# gives a
    /// distinct JVM carrier: the built-ins whose standard boxed wrapper class is fixed and
    /// public, plus `string`/`object` (which need no boxing), and named types, whose carrier
    /// is their own emitted or module-path class
    /// (`runtimeClassDesc`, the design/). Named types became testable once interop
    /// construction landed (the design/) and something in the subset could produce an
    /// instance to test.
    ///
    /// Every remaining spelling is refused by the binder before lowering (VS20004, the design):
    /// some share a standard box with another built-in, the rule's unsigned boxes are deliberately
    /// private and have no type-pattern ABI, and `decimal` type tests remain excluded while
    /// its public boundary predicate is still unset. The throw is therefore a compiler-defect
    /// guard, not a user-reachable path.
    private static ClassDesc classDescForInstanceOf(TypeSymbol type) {
        if (type instanceof BuiltinType builtin) {
            return switch (builtin) {
                case INT -> ConstantDescs.CD_Integer;
                case LONG -> ConstantDescs.CD_Long;
                case FLOAT -> ConstantDescs.CD_Float;
                case DOUBLE -> ConstantDescs.CD_Double;
                case BOOL -> ConstantDescs.CD_Boolean;
                case CHAR -> ConstantDescs.CD_Character;
                case STRING -> ConstantDescs.CD_String;
                case OBJECT -> ConstantDescs.CD_Object;
                default -> throw new UnsupportedOperationException(
                        "CodeEmitter does not emit an 'is' type-pattern test against: " + builtin);
            };
        }
        if (type instanceof NamedTypeSymbol named) {
            return runtimeClassDesc(named);
        }
        throw new UnsupportedOperationException(
                "CodeEmitter emits 'is' type-pattern tests against built-in and named types only, not: "
                        + type);
    }

    /// Emits `expr is pattern`, for an operand of any type kind.
    ///
    /// The operand is evaluated exactly once - C# guarantees that even though a pattern
    /// may test it many times - and parked in a temporary slot, which is what makes a
    /// pattern *tree* possible at all: `and`, `or` and `not` all re-test the same value,
    /// and a stack-based test could only ever consume it once. Every binding the pattern
    /// can introduce is pre-initialised before the tree runs, so no branch of the tree
    /// leaves a slot untyped at a join (see `initialisePatternBindings`).
    private void emitIsPattern(IrExpression.IsPattern isPattern) {
        TypeSymbol operandType = isPattern.expression().type().sourceType();
        java.lang.classfile.TypeKind operandKind = toTypeKind(operandType);
        emitExpression(isPattern.expression());
        int slot = codeBuilder.allocateLocal(operandKind);
        storeLocal(operandKind, slot);
        initialisePatternBindings(isPattern.pattern());
        emitPatternTest(isPattern.pattern(), operandType, slot);
    }

    /// Stores a type-appropriate default into every variable the pattern can bind, before
    /// any test runs.
    ///
    /// A pattern tree short-circuits: `a or b` never evaluates `b` once `a` matched, so a
    /// binding declared inside `b` is assigned on one path into the join and untouched on
    /// the other. C#'s definite-assignment rules (already enforced by `FlowAnalysis`) stop
    /// such a binding from being *read*, but the JVM verifier is stricter than that: it
    /// merges a slot's static type across every edge, and a slot that is `int` on one edge
    /// and never-assigned on another merges to `top`, which poisons every later read of
    /// that slot - including reads on the path that did assign it. Priming the slots up
    /// front gives every edge the same type. The stores are dead under C# semantics.
    private void initialisePatternBindings(IrPattern pattern) {
        initialisePatternBindings(pattern, false);
    }

    /// `narrowingRead` is true when a conjunct that follows `pattern` will test the value
    /// `pattern` narrowed to, so an undesignated type pattern needs a slot of its own to
    /// publish that value through. It is a slot the tree writes, so it is primed here for
    /// exactly the reason every binding is.
    private void initialisePatternBindings(IrPattern pattern, boolean narrowingRead) {
        switch (pattern) {
            case IrPattern.Discard ignored -> { }
            case IrPattern.Constant ignored -> { }
            case IrPattern.Relational ignored -> { }
            case IrPattern.Var var -> initialiseBinding(var.variable(),
                    var.type().sourceType());
            case IrPattern.Type type -> {
                if (type.variable() != null) {
                    initialiseBinding(type.variable(), type.type().sourceType());
                    if (narrowingRead && isCellBacked(type.variable())) {
                        initialiseNarrowSlot(type);
                    }
                } else if (narrowingRead) {
                    initialiseNarrowSlot(type);
                }
            }
            case IrPattern.Not not -> initialisePatternBindings(not.pattern());
            case IrPattern.Binary binary -> {
                // Only `and` narrows: its left operand's narrowed value is the input its
                // right operand tests, and the pair's own narrowed value is the right's.
                // `or` widens back to the common type, so neither side publishes one.
                boolean conjunction = binary.operator() == IrPattern.Binary.Kind.AND;
                initialisePatternBindings(binary.left(), conjunction);
                initialisePatternBindings(binary.right(), conjunction && narrowingRead);
            }
            case IrPattern.Recursive recursive -> {
                // Every component's value is parked in a slot of its own before its
                // subpattern runs, and the recursive test can fail before reaching a later
                // component, so those slots need the same priming a binding does.
                for (IrPattern.Component component : recursive.components()) {
                    initialiseComponentSlot(component);
                    initialisePatternBindings(component.pattern());
                }
                if (recursive.type() != null && recursive.designation() == null) {
                    initialiseNarrowSlot(recursive, recursive.type());
                }
                if (recursive.designation() != null) {
                    initialiseBinding(recursive.designation().symbol(),
                            recursive.designation().type().sourceType());
                    if (recursive.type() != null
                            && isCellBacked(recursive.designation().symbol())) {
                        initialiseNarrowSlot(recursive, recursive.type());
                    }
                }
            }
            case IrPattern.ListPattern list -> {
                // Every element's value is parked in a slot of its own before its subpattern
                // runs, and the test can fail before reaching a later element, so those slots
                // need the same priming a binding does. A slice tests the collection type.
                for (IrPattern element : list.elements()) {
                    if (element instanceof IrPattern.Slice slice) {
                        if (!(slice.pattern() instanceof IrPattern.Discard)) {
                            initialiseElementSlot(element, list.collectionType());
                        }
                        initialisePatternBindings(slice.pattern());
                    } else {
                        initialiseElementSlot(element, list.elementType());
                        initialisePatternBindings(element);
                    }
                }
                if (list.designation() != null) {
                    initialiseBinding(list.designation().symbol(),
                            list.designation().type().sourceType());
                }
            }
            case IrPattern.Slice slice -> initialisePatternBindings(slice.pattern());
        }
    }

    private void initialiseBinding(LocalSymbol variable) {
        initialiseBinding(variable, variable.type());
    }

    /// The bound type is passed separately because a `var` designation's symbol only carries
    /// the `Inferred` placeholder; the pattern carries the type the binder inferred.
    private void initialiseBinding(LocalSymbol variable, TypeSymbol boundType) {
        java.lang.classfile.TypeKind kind = toTypeKind(boundType);
        allocatePatternLocal(variable, boundType);
        emitPrimitiveDefault(kind);
        storeSymbolValue(variable, boundType, false);
    }

    private void initialiseNarrowSlot(IrPattern.Type type) {
        initialiseNarrowSlot(type, type.type());
    }

    private void initialiseNarrowSlot(IrPattern pattern, IrValueType type) {
        java.lang.classfile.TypeKind kind = toTypeKind(type.sourceType());
        int slot = patternNarrowSlots.computeIfAbsent(pattern,
                _ -> codeBuilder.allocateLocal(kind));
        emitPrimitiveDefault(kind);
        storeLocal(kind, slot);
    }

    private void initialiseElementSlot(IrPattern element, IrValueType type) {
        java.lang.classfile.TypeKind kind = toTypeKind(type.sourceType());
        int slot = patternElementSlots.computeIfAbsent(element,
                _ -> codeBuilder.allocateLocal(kind));
        emitPrimitiveDefault(kind);
        storeLocal(kind, slot);
    }

    private void initialiseComponentSlot(IrPattern.Component component) {
        java.lang.classfile.TypeKind kind = toTypeKind(component.type().sourceType());
        int slot = patternComponentSlots.computeIfAbsent(component,
                _ -> codeBuilder.allocateLocal(kind));
        emitPrimitiveDefault(kind);
        storeLocal(kind, slot);
    }

    /// Tests the value held in `slot` (of static type `operandType`) against `pattern`,
    /// leaving exactly one `boolean` on the stack and consuming nothing.
    ///
    /// This is the pattern compiler: a test-and-branch tree, where each node reads the
    /// operand slot as often as it needs and every node has the same stack contract, so
    /// nodes compose to arbitrary depth. `Discard` and `Var` always match; `Constant` is
    /// an equality test; `Relational` is a comparison against a constant; `Type` is an
    /// `instanceof` with optional unboxing; `Not` inverts; `Binary` is a short-circuiting
    /// `and`/`or`. `Recursive` extracts each component into a slot of its own and hangs a
    /// subtree off it. `ListPattern`/`Slice` still throw per they need length
    /// checks and slicing rather than more branching, which is a separate increment.
    private PatternOperand emitPatternTest(IrPattern pattern, TypeSymbol operandType, int slot) {
        java.lang.classfile.TypeKind operandKind = toTypeKind(operandType);
        PatternOperand unchanged = new PatternOperand(operandType, slot);
        switch (pattern) {
            case IrPattern.Discard ignored -> codeBuilder.iconst_1();
            case IrPattern.Var var -> {
                emitPatternBindingStore(var.variable(), var.type().sourceType(),
                        operandType, slot);
                codeBuilder.iconst_1();
            }
            case IrPattern.Constant constant -> emitConstantPatternTest(constant, operandType, slot);
            case IrPattern.Type type -> {
                if (operandKind != java.lang.classfile.TypeKind.REFERENCE) {
                    // A value-typed operand has no run-time test to make: binding admitted
                    // only the identity shape here (`int x is int n`, always true in C#), so
                    // the pattern binds and answers true, and narrowing leaves the operand
                    // exactly where it was.
                    if (type.variable() != null) {
                        emitPatternBindingStore(type.variable(), type.type().sourceType(),
                                operandType, slot);
                    }
                    codeBuilder.iconst_1();
                    return unchanged;
                }
                loadLocal(operandKind, slot);
                return emitTypePatternTest(type, unchanged);
            }
            case IrPattern.Relational relational -> emitRelationalPatternTest(relational, operandType, slot);
            case IrPattern.Not not -> {
                emitPatternTest(not.pattern(), operandType, slot);
                codeBuilder.ldc(1);
                codeBuilder.ixor();
            }
            case IrPattern.Binary binary -> {
                return emitBinaryPatternTest(binary, operandType, slot);
            }
            case IrPattern.Recursive recursive -> {
                return emitRecursivePatternTest(recursive, operandType, slot);
            }
            case IrPattern.ListPattern list -> emitListPatternTest(list, slot);
            case IrPattern.Slice ignored -> throw new UnsupportedOperationException(
                    "a slice pattern is only emitted as an element of a list pattern");
        }
        return unchanged;
    }

    /// The value a conjunct tests, as a static type plus the local slot holding it.
    ///
    /// C# narrows across `and`: the input type of the right operand is the *narrowed* type
    /// of the left, so in `o is int n and > 0` the comparison is against an `int` and not
    /// against the `object` the tree started from. Threading the operand through the tree
    /// rather than recomputing it keeps the narrowing decision in the one place that also
    /// emits the store which makes it true.
    private record PatternOperand(TypeSymbol type, int slot) { }

    /// Binds a pattern variable to the operand held in `slot`. The kinds normally agree;
    /// they differ only where C# widens the binding to a reference (`x is var o` with `o`
    /// typed `object`), which needs a box on the way in.
    private void emitPatternBindingStore(LocalSymbol variable, TypeSymbol bindingType,
            TypeSymbol operandType, int slot) {
        java.lang.classfile.TypeKind operandKind = toTypeKind(operandType);
        java.lang.classfile.TypeKind bindingKind = toTypeKind(bindingType);
        allocatePatternLocal(variable, bindingType);
        loadLocal(operandKind, slot);
        if (bindingKind != operandKind) {
            if (bindingKind != java.lang.classfile.TypeKind.REFERENCE) {
                throw new UnsupportedOperationException(
                        "CodeEmitter cannot bind a " + operandKind + " operand to a " + bindingKind + " pattern variable");
            }
            emitBox(operandType);
        }
        storeSymbolValue(variable, bindingType, false);
    }

    /// `expr is <constant>`, including `is null`.
    ///
    /// A reference operand defers to `java.util.Objects.equals(Object, Object)`, which is
    /// simultaneously correct for reference equality against `null` (`Objects.equals(x,
    /// null)` is `true` only when `x` is itself `null`) and value equality for boxed
    /// primitives/strings. A value operand compares directly against the constant on its
    /// own carrier instead: boxing both sides to reach `Objects.equals` would still be
    /// correct, but it would allocate on a path where the JVM has a single comparison
    /// instruction, and `is null` cannot arise there at all.
    private void emitConstantPatternTest(IrPattern.Constant constant, TypeSymbol operandType, int slot) {
        java.lang.classfile.TypeKind operandKind = toTypeKind(operandType);
        loadLocal(operandKind, slot);
        if (operandKind == java.lang.classfile.TypeKind.REFERENCE) {
            emitExpression(constant.expression());
            TypeSymbol constantType = constant.expression().type().sourceType();
            java.lang.classfile.TypeKind constantKind = toTypeKind(constantType);
            if (constantKind != java.lang.classfile.TypeKind.REFERENCE) {
                emitBox(constantType);
            }
            ClassDesc objectsDesc = ClassDesc.of("java.util.Objects");
            codeBuilder.invokestatic(objectsDesc, "equals",
                    MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_Object, ConstantDescs.CD_Object));
            return;
        }
        emitPatternComparand(constant.expression(), operandType);
        emitBinaryOp(IrBinaryOperator.EQUAL, operandType);
    }

    /// `expr is > 0` and the other three relational forms: a comparison between the
    /// operand and a constant, emitted through exactly the binary-operator path a written
    /// `expr > 0` would take, so pattern and expression comparisons cannot diverge on
    /// operand promotion or on NaN ordering.
    private void emitRelationalPatternTest(IrPattern.Relational relational, TypeSymbol operandType, int slot) {
        java.lang.classfile.Label endLabel = null;
        if (operandType instanceof TypeSymbol.Nullable nullable) {
            endLabel = codeBuilder.newLabel();
            loadLocal(java.lang.classfile.TypeKind.REFERENCE, slot);
            codeBuilder.ifnull(endLabel);
            loadLocal(java.lang.classfile.TypeKind.REFERENCE, slot);
            emitUnboxNullableElement(nullable.element());
            operandType = nullable.element();
        } else {
            java.lang.classfile.TypeKind operandKind = toTypeKind(operandType);
            if (operandKind == java.lang.classfile.TypeKind.REFERENCE && operandType != BuiltinType.DECIMAL) {
                throw new UnsupportedOperationException(
                        "CodeEmitter cannot emit a relational pattern against a reference-typed operand");
            }
            loadLocal(operandKind, slot);
        }
        emitPatternComparand(relational.value(), operandType);
        emitBinaryOp(relational.operator(), operandType);
        if (endLabel != null) {
            java.lang.classfile.Label successLabel = codeBuilder.newLabel();
            codeBuilder.goto_(successLabel);
            codeBuilder.labelBinding(endLabel);
            codeBuilder.iconst_0();
            codeBuilder.labelBinding(successLabel);
        }
    }

    /// Emits a pattern's constant operand already converted to the tested value's type.
    ///
    /// The comparison that follows is a single-width JVM opcode, so `long n is > 0` must
    /// not reach `lcmp` with an `int` on the stack. The binder types a pattern's constant
    /// by its own literal rather than by the governing value, so unlike a written binary
    /// operator (whose operands the binder now promotes, the design) the conversion has to happen
    /// here.
    private void emitPatternComparand(IrExpression comparand, TypeSymbol operandType) {
        emitExpression(comparand);
        emitUnderlyingConversion(comparand.type().sourceType(), operandType);
    }

    /// `and`/`or` over two sub-patterns, short-circuiting exactly as C# specifies: the
    /// right side is not tested once the left has decided the result. Any binding the
    /// skipped side declares keeps the default `initialisePatternBindings` gave it.
    private PatternOperand emitBinaryPatternTest(IrPattern.Binary binary, TypeSymbol operandType, int slot) {
        Label shortCircuitLabel = codeBuilder.newLabel();
        Label endLabel = codeBuilder.newLabel();
        boolean isAnd = binary.operator() == IrPattern.Binary.Kind.AND;

        PatternOperand afterLeft = emitPatternTest(binary.left(), operandType, slot);
        if (isAnd) {
            codeBuilder.ifeq(shortCircuitLabel);
        } else {
            codeBuilder.ifne(shortCircuitLabel);
        }
        // Reaching the right operand of `and` means the left matched, so the right tests
        // the value the left narrowed to; `or` reached its right because the left did not
        // match, and nothing was narrowed.
        PatternOperand rightInput = isAnd ? afterLeft : new PatternOperand(operandType, slot);
        PatternOperand afterRight = emitPatternTest(binary.right(), rightInput.type(), rightInput.slot());
        codeBuilder.goto_(endLabel);

        codeBuilder.labelBinding(shortCircuitLabel);
        if (isAnd) {
            codeBuilder.iconst_0();
        } else {
            codeBuilder.iconst_1();
        }
        codeBuilder.labelBinding(endLabel);
        return isAnd ? afterRight : new PatternOperand(operandType, slot);
    }

    /// `expr is T (p1, p2) { Name: p3 } name`: an optional type test, then one subtree per
    /// component of the tested value.
    ///
    /// Every operand a recursive pattern can reach - a value tuple, a declared struct, an
    /// array, a string - is carried as a reference, so this path is reference-only. A typed
    /// pattern runs `instanceof` (which is already false for `null`) and narrows into the
    /// designation's slot or the primed narrow slot; a typeless `is { X: 1 }` still rejects
    /// `null` first, because C# reads no component out of a null value.
    ///
    /// Each component is then extracted into a slot of its own and handed to the same
    /// `emitPatternTest` every other node uses, so components nest to any depth. The chain
    /// short-circuits to `failLabel` on the first component that does not match, which is
    /// what makes a later component's extraction unreachable rather than merely unused -
    /// C# never reads `Y` once `X` failed.
    private PatternOperand emitRecursivePatternTest(IrPattern.Recursive recursive,
            TypeSymbol operandType, int slot) {
        java.lang.classfile.TypeKind operandKind = toTypeKind(operandType);
        if (operandKind != java.lang.classfile.TypeKind.REFERENCE) {
            throw new UnsupportedOperationException(
                    "CodeEmitter only emits recursive patterns over reference-typed operands, not "
                            + operandKind);
        }

        Label failLabel = codeBuilder.newLabel();
        Label endLabel = codeBuilder.newLabel();

        TypeSymbol valueType = operandType;
        int valueSlot = slot;

        codeBuilder.aload(slot);
        if (recursive.type() == null) {
            codeBuilder.ifnull(failLabel);
            if (recursive.designation() != null) {
                emitPatternBindingStore(recursive.designation().symbol(),
                        recursive.designation().type().sourceType(), operandType, slot);
            }
        } else {
            TypeSymbol testedType = recursive.type().sourceType();
            ClassDesc testDesc = classDescForInstanceOf(testedType);
            codeBuilder.instanceOf(testDesc);
            codeBuilder.ifeq(failLabel);

            java.lang.classfile.TypeKind testedKind = toTypeKind(testedType);
            valueType = testedType;
            LocalSymbol designation = recursive.designation() == null
                    ? null : recursive.designation().symbol();
            valueSlot = designation == null || isCellBacked(designation)
                    ? patternNarrowSlots.get(recursive)
                    : allocatePatternLocal(designation, testedType);
            codeBuilder.aload(slot);
            codeBuilder.checkcast(testDesc);
            if (testedKind != java.lang.classfile.TypeKind.REFERENCE) {
                emitUnbox(testedKind, testDesc);
            }
            if (designation != null && isCellBacked(designation)) {
                storeLocal(testedKind, valueSlot);
                loadLocal(testedKind, valueSlot);
                storeSymbolValue(designation, testedType, false);
            } else {
                storeLocal(testedKind, valueSlot);
            }
        }

        for (IrPattern.Component component : recursive.components()) {
            TypeSymbol componentType = component.type().sourceType();
            emitComponentLoad(component, valueType, valueSlot);
            storeLocal(toTypeKind(componentType), patternComponentSlots.get(component));
            emitPatternTest(component.pattern(), componentType,
                    patternComponentSlots.get(component));
            codeBuilder.ifeq(failLabel);
        }

        codeBuilder.iconst_1();
        codeBuilder.goto_(endLabel);
        codeBuilder.labelBinding(failLabel);
        codeBuilder.iconst_0();
        codeBuilder.labelBinding(endLabel);
        return new PatternOperand(valueType, valueSlot);
    }

    /// `xs is [1, .. var rest, var last]`: a null test, a length test, then one subtree per
    /// element.
    ///
    /// C# indexes the prefix from the start and the suffix from the end, so a pattern with a
    /// slice needs `Length >= prefix + suffix` while one without needs `Length == count`.
    /// The suffix indices are computed as `length - k` rather than folded into constants,
    /// which is what makes `[first, .., last]` match every collection of two or more.
    ///
    /// A slice subpattern is matched against the actual slice, produced through the same
    /// `VsSlices` call a written `xs[prefix..^suffix]` emits, so the two cannot disagree on
    /// bounds or on the empty-slice case. A bare `..` binds nothing and produces nothing.
    private void emitListPatternTest(IrPattern.ListPattern list, int slot) {
        TypeSymbol collectionType = list.collectionType().sourceType();
        TypeSymbol elementType = list.elementType().sourceType();
        java.lang.classfile.TypeKind elementKind = toTypeKind(elementType);

        List<IrPattern> elements = list.elements();
        int sliceIndex = -1;
        for (int index = 0; index < elements.size(); index++) {
            if (elements.get(index) instanceof IrPattern.Slice) {
                sliceIndex = index;
                break;
            }
        }
        int prefix = sliceIndex < 0 ? elements.size() : sliceIndex;
        int suffix = sliceIndex < 0 ? 0 : elements.size() - sliceIndex - 1;

        Label failLabel = codeBuilder.newLabel();
        Label endLabel = codeBuilder.newLabel();

        codeBuilder.aload(slot);
        codeBuilder.ifnull(failLabel);

        int lengthSlot = codeBuilder.allocateLocal(java.lang.classfile.TypeKind.INT);
        emitCollectionLength(collectionType, slot);
        codeBuilder.istore(lengthSlot);
        codeBuilder.iload(lengthSlot);
        codeBuilder.ldc(prefix + suffix);
        if (sliceIndex < 0) {
            codeBuilder.if_icmpne(failLabel);
        } else {
            codeBuilder.if_icmplt(failLabel);
        }

        for (int index = 0; index < prefix; index++) {
            IrPattern element = elements.get(index);
            int elementSlot = patternElementSlots.get(element);
            emitCollectionElementAt(collectionType, slot, index);
            storeLocal(elementKind, elementSlot);
            emitPatternTest(element, elementType, elementSlot);
            codeBuilder.ifeq(failLabel);
        }

        for (int index = 0; index < suffix; index++) {
            IrPattern element = elements.get(sliceIndex + 1 + index);
            int elementSlot = patternElementSlots.get(element);
            codeBuilder.aload(slot);
            codeBuilder.checkcast(toClassDesc(collectionType));
            codeBuilder.iload(lengthSlot);
            codeBuilder.ldc(suffix - index);
            codeBuilder.isub();
            emitCollectionElementLoad(collectionType);
            storeLocal(elementKind, elementSlot);
            emitPatternTest(element, elementType, elementSlot);
            codeBuilder.ifeq(failLabel);
        }

        if (sliceIndex >= 0
                && !(((IrPattern.Slice) elements.get(sliceIndex)).pattern()
                        instanceof IrPattern.Discard)) {
            IrPattern.Slice slice = (IrPattern.Slice) elements.get(sliceIndex);
            int sliceSlot = patternElementSlots.get(slice);
            emitSliceValue(collectionType, slot, prefix, suffix);
            codeBuilder.astore(sliceSlot);
            emitPatternTest(slice.pattern(), collectionType, sliceSlot);
            codeBuilder.ifeq(failLabel);
        }

        if (list.designation() != null) {
            emitPatternBindingStore(list.designation().symbol(),
                    list.designation().type().sourceType(), collectionType, slot);
        }

        codeBuilder.iconst_1();
        codeBuilder.goto_(endLabel);
        codeBuilder.labelBinding(failLabel);
        codeBuilder.iconst_0();
        codeBuilder.labelBinding(endLabel);
    }

    /// Pushes the length of the array or string held in `slot`.
    private void emitCollectionLength(TypeSymbol collectionType, int slot) {
        codeBuilder.aload(slot);
        codeBuilder.checkcast(toClassDesc(collectionType));
        if (collectionType == BuiltinType.STRING) {
            codeBuilder.invokevirtual(ConstantDescs.CD_String, "length",
                    MethodTypeDesc.of(ConstantDescs.CD_int));
        } else {
            codeBuilder.arraylength();
        }
    }

    /// Pushes the element at a constant index of the array or string held in `slot`.
    private void emitCollectionElementAt(TypeSymbol collectionType, int slot, int index) {
        codeBuilder.aload(slot);
        codeBuilder.checkcast(toClassDesc(collectionType));
        codeBuilder.ldc(index);
        emitCollectionElementLoad(collectionType);
    }

    /// Consumes a receiver and an `int` index and pushes the element.
    private void emitCollectionElementLoad(TypeSymbol collectionType) {
        if (collectionType == BuiltinType.STRING) {
            codeBuilder.invokevirtual(ConstantDescs.CD_String, "charAt",
                    MethodTypeDesc.of(ConstantDescs.CD_char, ConstantDescs.CD_int));
        } else {
            emitArrayLoad(toTypeKind(((TypeSymbol.Array) collectionType).elementType()));
        }
    }

    /// Pushes `collection[prefix..^suffix]`, the value a slice subpattern tests.
    private void emitSliceValue(TypeSymbol collectionType, int slot, int prefix, int suffix) {
        ClassDesc indexDesc = ClassDesc.of("vsharp.runtime.VsIndex");
        ClassDesc rangeDesc = ClassDesc.of("vsharp.runtime.VsRange");
        ClassDesc slicesDesc = ClassDesc.of("vsharp.runtime.VsSlices");

        codeBuilder.aload(slot);
        codeBuilder.checkcast(toClassDesc(collectionType));
        codeBuilder.new_(rangeDesc);
        codeBuilder.dup();
        codeBuilder.ldc(prefix);
        codeBuilder.invokestatic(indexDesc, "fromStart",
                MethodTypeDesc.of(indexDesc, ConstantDescs.CD_int));
        codeBuilder.ldc(suffix);
        codeBuilder.invokestatic(indexDesc, "fromEnd",
                MethodTypeDesc.of(indexDesc, ConstantDescs.CD_int));
        codeBuilder.invokespecial(rangeDesc, "<init>",
                MethodTypeDesc.of(ConstantDescs.CD_void, indexDesc, indexDesc));

        if (collectionType == BuiltinType.STRING) {
            codeBuilder.invokestatic(slicesDesc, "slice",
                    MethodTypeDesc.of(ConstantDescs.CD_String, ConstantDescs.CD_String, rangeDesc));
            return;
        }
        ClassDesc arrayDesc = toClassDesc(collectionType);
        TypeSymbol element = ((TypeSymbol.Array) collectionType).elementType();
        if (toTypeKind(element) == java.lang.classfile.TypeKind.REFERENCE) {
            ClassDesc objectArray = ClassDesc.ofDescriptor("[Ljava/lang/Object;");
            codeBuilder.invokestatic(slicesDesc, "slice",
                    MethodTypeDesc.of(objectArray, objectArray, rangeDesc));
            codeBuilder.checkcast(arrayDesc);
        } else {
            codeBuilder.invokestatic(slicesDesc, "slice",
                    MethodTypeDesc.of(arrayDesc, arrayDesc, rangeDesc));
        }
    }

    /// Pushes one component's value, read from the tested value held in `valueSlot`.
    /// Binding decided the access, so this is a direct translation with no name lookup:
    /// tuple elements go through the erased `VsTupleN.itemN()` accessor and unbox exactly
    /// as tuple deconstruction does, fields through `getfield`, and the two `Length` forms
    /// through the same instructions a written `.Length` would emit.
    ///
    /// Each access is preceded by a `checkcast` to the receiver class it needs. The V# type
    /// of the slot is not the JVM type the verifier tracks for it: a tuple is carried as
    /// `Object` in parameters and fields, and a narrowed operand slot can hold anything the
    /// enclosing tree stored. The cast is what makes the receiver assignable; it is
    /// redundant only when the slot already happens to be typed, and a redundant
    /// `checkcast` costs an instruction rather than a verification failure.
    private void emitComponentLoad(IrPattern.Component component, TypeSymbol valueType,
            int valueSlot) {
        TypeSymbol componentType = component.type().sourceType();
        codeBuilder.aload(valueSlot);
        switch (component.access()) {
            case TUPLE_ITEM -> {
                if (!(valueType instanceof TypeSymbol.Tuple tuple)) {
                    throw new UnsupportedOperationException(
                            "a tuple component was resolved against a non-tuple operand: " + valueType);
                }
                ClassDesc tupleClass = ClassDesc.of("vsharp.runtime.VsTuple" + tuple.elements().size());
                codeBuilder.checkcast(tupleClass);
                codeBuilder.invokevirtual(tupleClass, "item" + (component.index() + 1),
                        MethodTypeDesc.of(ConstantDescs.CD_Object));
                if (toTypeKind(componentType) != java.lang.classfile.TypeKind.REFERENCE) {
                    emitUnboxNullableElement(componentType);
                } else {
                    // `toClassDesc`, not `runtimeClassDesc`: a component's type can be any
                    // carrier, including `string` and a nested tuple, which have no declared
                    // runtime class of their own.
                    codeBuilder.checkcast(toClassDesc(componentType));
                }
            }
            case FIELD -> {
                ClassDesc owner = declaringClassOf(component.field());
                codeBuilder.checkcast(owner);
                codeBuilder.getfield(owner, component.field().name(),
                        toClassDesc(component.field().type()));
            }
            case ARRAY_LENGTH -> {
                codeBuilder.checkcast(toClassDesc(valueType));
                int rank = valueType instanceof TypeSymbol.Array array
                        ? array.ranks().getFirst() : 1;
                if (rank > 1) {
                    codeBuilder.ldc(rank);
                    codeBuilder.invokestatic(ClassDesc.of("vsharp.runtime.VsArrays"), "length",
                            MethodTypeDesc.of(ConstantDescs.CD_int, ConstantDescs.CD_Object,
                                    ConstantDescs.CD_int));
                } else {
                    codeBuilder.arraylength();
                }
            }
            case STRING_LENGTH -> {
                codeBuilder.checkcast(ConstantDescs.CD_String);
                codeBuilder.invokevirtual(ConstantDescs.CD_String, "length",
                        MethodTypeDesc.of(ConstantDescs.CD_int));
            }
            case ACCESSOR -> {
                // The curated `Map.Entry` deconstruction. The accessor is a generic
                // Java method, so it is invoked through its erased descriptor and the erased
                // reference is narrowed exactly as a tuple item is.
                emitAccessorComponent(component, valueType, componentType);
            }
        }
    }

    /// Reads a component by calling a no-argument Java accessor on the tested value.
    /// The receiver is cast to the accessor's own owner first, because the slot's verified
    /// type is whatever produced the value - `Object` for an erased element.
    private void emitAccessorComponent(IrPattern.Component component, TypeSymbol valueType,
            TypeSymbol componentType) {
        if (valueType instanceof TypeSymbol.Constructed || valueType instanceof NamedTypeSymbol) {
            codeBuilder.checkcast(toClassDesc(valueType));
            emitInstanceInvoke(valueType, component.accessor(), Map.of());
        } else {
            // An erased element arrives as `Object`; the accessor's own owner is then the only
            // receiver type available, and it is exactly the one the descriptor names.
            ClassDesc owner = callableContainer(component.accessor().qualifiedName());
            codeBuilder.checkcast(owner);
            codeBuilder.invokeinterface(owner, component.accessor().name(),
                    MethodTypeDesc.of(ConstantDescs.CD_Object));
        }
        if (toTypeKind(componentType) != java.lang.classfile.TypeKind.REFERENCE) {
            emitUnboxNullableElement(componentType);
            return;
        }
        codeBuilder.checkcast(toClassDesc(componentType));
    }

    /// `expr is T` / `expr is T name`: `instanceof` against `T`'s boxed/reference class,
    /// then on a match, `checkcast`s and (for a boxable built-in) unboxes before storing
    /// into the designated variable, if any; on a mismatch, discards the tested reference
    /// without ever executing `checkcast`/unboxing against the wrong runtime type. Both
    /// branches converge on a single `boolean` result.
    ///
    /// When the pattern binds a variable, the not-matched branch also stores a
    /// type-appropriate default (`0`/`false`/`null`) into that variable's slot before the
    /// join. This is not a semantic requirement (C#'s own definite-assignment analysis,
    /// already enforced earlier by `FlowAnalysis`, is what actually keeps an unmatched
    /// binding from ever being *read*) - it exists purely because the JVM verifier tracks
    /// only a local slot's static *type* across every edge into a merge point, not which
    /// branch actually ran. Without it, the matched path leaves the slot typed (e.g.)
    /// `int` while the not-matched path leaves it untouched (`top`, i.e. never assigned
    /// any type), so any instruction that could be reached from both - even this pattern
    /// test's own internal `endLabel`, well before any consuming `if`/`while` - fails
    /// verification with "Bad local variable type" the moment code after it reads the
    /// slot. Giving both paths the same type at the join is the standard fix; the
    /// resulting spurious zero-store is dead as far as C# semantics are concerned.
    private PatternOperand emitTypePatternTest(IrPattern.Type type, PatternOperand operand) {
        TypeSymbol targetType = type.type().sourceType();
        ClassDesc testDesc = classDescForInstanceOf(targetType);
        java.lang.classfile.TypeKind targetKind = toTypeKind(targetType);
        // The tested value is kept whenever anything downstream reads it: a designation
        // names it, or a following conjunct tests it (in which case the priming walk
        // reserved an unnamed slot). Otherwise it is popped and nothing narrows.
        LocalSymbol variable = type.variable();
        Integer bindingSlot = variable == null ? null
                : Integer.valueOf(allocatePatternLocal(variable, targetType));
        Integer narrowedSlot = variable != null && isCellBacked(variable)
                ? patternNarrowSlots.get(type) : bindingSlot;
        if (variable == null) {
            narrowedSlot = patternNarrowSlots.get(type);
        }

        Label notMatchedLabel = codeBuilder.newLabel();
        Label endLabel = codeBuilder.newLabel();

        codeBuilder.dup();
        codeBuilder.instanceOf(testDesc);
        codeBuilder.ifeq(notMatchedLabel);

        codeBuilder.checkcast(testDesc);
        if (targetKind != java.lang.classfile.TypeKind.REFERENCE) {
            emitUnbox(targetKind, testDesc);
        }
        if (variable != null) {
            if (narrowedSlot != null && !narrowedSlot.equals(bindingSlot)) {
                storeLocal(targetKind, narrowedSlot);
                loadLocal(targetKind, narrowedSlot);
            }
            storeSymbolValue(variable, targetType, false);
        } else if (narrowedSlot != null) {
            storeLocal(targetKind, narrowedSlot);
        } else {
            emitPop(targetKind);
        }
        codeBuilder.iconst_1();
        codeBuilder.goto_(endLabel);

        codeBuilder.labelBinding(notMatchedLabel);
        codeBuilder.pop();
        if (narrowedSlot != null) {
            emitPrimitiveDefault(targetKind);
            storeLocal(targetKind, narrowedSlot);
        }
        if (variable != null) {
            emitPrimitiveDefault(targetKind);
            storeSymbolValue(variable, targetType, false);
        }
        codeBuilder.iconst_0();

        codeBuilder.labelBinding(endLabel);
        return narrowedSlot == null ? operand : new PatternOperand(targetType, narrowedSlot);
    }

    /// A type-appropriate zero/`false`/`null` value for `kind`, pushed onto the stack.
    /// Shares the primitive half of `IrExpression.DefaultValue`'s logic; unlike that case,
    /// a struct-construction fallback is never needed here because
    /// `classDescForInstanceOf` only ever selects a boxable built-in or a plain reference
    /// type for `kind` to begin with.
    private void emitPrimitiveDefault(java.lang.classfile.TypeKind kind) {
        switch (kind) {
            case INT, BOOLEAN, BYTE, SHORT, CHAR -> codeBuilder.ldc(0);
            case DOUBLE -> codeBuilder.ldc(0.0);
            case FLOAT -> codeBuilder.ldc(0.0f);
            case LONG -> codeBuilder.ldc(0L);
            case REFERENCE -> codeBuilder.aconst_null();
            default -> throw new UnsupportedOperationException(
                    "CodeEmitter cannot produce a default value for type kind: " + kind);
        }
    }

    /// `governing switch { pattern [when guard] => expression, ... }`: evaluates
    /// `governing` once into a temp local (each arm needs its own fresh copy to test,
    /// since pattern tests consume the value from the stack), then tries each arm in
    /// source order and on a match with a true-or-absent guard emits the arm's result and
    /// jumps past the rest. `REFERENCE`-kind governing values run through the same
    /// `emitPatternTest` used by `is` expressions. JVM-int-carrier governing values
    /// (`int`, plus byte/short/char descriptors loaded as ints) use a primitive path for
    /// constant, discard and var arms only, avoiding boxing and deliberately not
    /// generalising type/nested pattern matching to primitives.
    /// A switch expression is a chain of arms, each testing the governing value parked in a
    /// slot of its own carrier and short-circuiting to the next arm.
    ///
    /// Every arm goes through the general pattern compiler (`emitPatternTest`), which is the
    /// same one `is` uses, so an arm may be any pattern the language admits and the governing
    /// value may have any carrier - `long`, `double` and relational arms included. The
    /// primitive path used to be a separate, smaller matcher that knew only constants, which
    /// is what made `v switch { > 5 =&gt; ... }` an internal error while `v is &gt; 5` worked.
    private void emitSwitchExpression(IrExpression.Switch switchExpr) {
        TypeSymbol governingType = switchExpr.governing().type().sourceType();
        java.lang.classfile.TypeKind governingKind = toTypeKind(governingType);
        emitExpression(switchExpr.governing());
        int governingSlot = codeBuilder.allocateLocal(governingKind);
        storeLocal(governingKind, governingSlot);

        Label endLabel = codeBuilder.newLabel();
        for (IrSwitchExpressionArm arm : switchExpr.arms()) {
            Label nextArmLabel = codeBuilder.newLabel();
            initialisePatternBindings(arm.pattern());
            emitPatternTest(arm.pattern(), governingType, governingSlot);
            codeBuilder.ifeq(nextArmLabel);
            if (arm.guard() != null) {
                emitExpression(arm.guard());
                codeBuilder.ifeq(nextArmLabel);
            }
            emitExpression(arm.expression());
            codeBuilder.goto_(endLabel);
            codeBuilder.labelBinding(nextArmLabel);
        }

        emitNonExhaustiveSwitchExpressionThrow();
        codeBuilder.labelBinding(endLabel);
    }

    private void emitNonExhaustiveSwitchExpressionThrow() {
        ClassDesc exceptionDesc = ClassDesc.of("java.lang.RuntimeException");
        codeBuilder.new_(exceptionDesc);
        codeBuilder.dup();
        codeBuilder.ldc("Non-exhaustive switch expression matched no arm");
        codeBuilder.invokespecial(exceptionDesc, "<init>",
                MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_String));
        codeBuilder.athrow();
    }

    /// Tests a JVM-int-carrier switch governing value on top of the stack against one
    /// primitive-compatible pattern, leaving a boolean int. This is intentionally smaller
    /// than the reference `emitPatternTest`: type patterns need `instanceof`, and
    /// relational/binary/recursive/list/slice patterns still need a real pattern compiler.
    /// `expr as T`: `instanceof` against `T`, yielding the `checkcast` value on a match or
    /// `null` on a mismatch - no local-slot binding, so unlike `emitTypePatternTest` this
    /// needs no the design-style default-store fix: both branches simply push a `REFERENCE` value
    /// onto the *operand stack* before converging, which the verifier accepts by taking
    /// the least common supertype (trivially satisfied here since `null` is compatible
    /// with any reference type). Scoped to `REFERENCE`-kind operands and target types
    /// (built-ins with a fixed boxed/reference class, via `classDescForInstanceOf`) -
    /// `as` against a value-type target has no null-representable JVM carrier without a
    /// chosen `Nullable<T>` runtime representation, which this subset does not need yet.
    private void emitAs(IrExpression.As asExpr) {
        java.lang.classfile.TypeKind operandKind = toTypeKind(asExpr.operand().type().sourceType());
        if (operandKind != java.lang.classfile.TypeKind.REFERENCE) {
            throw new UnsupportedOperationException(
                    "CodeEmitter only emits 'as' over reference-typed operands currently, not " + operandKind);
        }
        TypeSymbol targetType = asExpr.type().sourceType();
        java.lang.classfile.TypeKind targetKind = toTypeKind(targetType);
        if (targetKind != java.lang.classfile.TypeKind.REFERENCE) {
            throw new UnsupportedOperationException(
                    "CodeEmitter only emits 'as' against reference target types currently, not " + targetType);
        }
        ClassDesc testDesc = classDescForInstanceOf(targetType);

        emitExpression(asExpr.operand());
        codeBuilder.dup();
        codeBuilder.instanceOf(testDesc);
        Label matchedLabel = codeBuilder.newLabel();
        Label endLabel = codeBuilder.newLabel();
        codeBuilder.ifne(matchedLabel);

        codeBuilder.pop();
        codeBuilder.aconst_null();
        codeBuilder.goto_(endLabel);

        codeBuilder.labelBinding(matchedLabel);
        codeBuilder.checkcast(testDesc);

        codeBuilder.labelBinding(endLabel);
    }

    /// `start..end` (and its open forms `start..`, `..end`, `..`), lowered to
    /// `vsharp.runtime.VsRange` construction exactly as its own IR doc comment promises.
    /// `IrExpression.Range` allows either endpoint to be absent (only `type` is
    /// non-null), so all four C# forms are handled by dispatching to `VsRange`'s matching
    /// factory: `ALL` for `..`, `startAt`/`endAt` for the one-sided forms, and the full
    /// two-argument constructor otherwise - mirroring `VsRange`'s own Java API one-to-one
    /// rather than always going through the general constructor.
    private void emitRange(IrExpression.Range range) {
        ClassDesc vsRangeDesc = ClassDesc.of("vsharp.runtime.VsRange");
        ClassDesc vsIndexDesc = ClassDesc.of("vsharp.runtime.VsIndex");

        if (range.start() == null && range.end() == null) {
            codeBuilder.getstatic(vsRangeDesc, "ALL", vsRangeDesc);
        } else if (range.end() == null) {
            emitRangeIndex(range.start(), vsIndexDesc);
            codeBuilder.invokestatic(vsRangeDesc, "startAt", MethodTypeDesc.of(vsRangeDesc, vsIndexDesc));
        } else if (range.start() == null) {
            emitRangeIndex(range.end(), vsIndexDesc);
            codeBuilder.invokestatic(vsRangeDesc, "endAt", MethodTypeDesc.of(vsRangeDesc, vsIndexDesc));
        } else {
            codeBuilder.new_(vsRangeDesc);
            codeBuilder.dup();
            emitRangeIndex(range.start(), vsIndexDesc);
            emitRangeIndex(range.end(), vsIndexDesc);
            codeBuilder.invokespecial(vsRangeDesc, "<init>",
                    MethodTypeDesc.of(ConstantDescs.CD_void, vsIndexDesc, vsIndexDesc));
        }
    }

    /// Wraps a plain `int` range endpoint as a start-relative `VsIndex` via
    /// `VsIndex.fromStart(int)`. Scoped to `int`-typed endpoints: C#'s `^`
    /// (index-from-end) prefix operator has no case in `CoreOperators.unaryResult`
    /// (only its binary-XOR `CARET` meaning is handled), so no program in this subset
    /// can currently bind a from-end range endpoint in the first place - a semantic-layer
    /// gap upstream of the backend, not something to approximate here by guessing intent.
    private void emitRangeIndex(IrExpression endpoint, ClassDesc vsIndexDesc) {
        vsharp.compiler.semantics.types.TypeSymbol sourceType = endpoint.type().sourceType();
        java.lang.classfile.TypeKind kind = toTypeKind(sourceType);
        
        emitExpression(endpoint);
        
        if (sourceType == vsharp.compiler.semantics.types.TypeSymbol.Index.INSTANCE) {
            // Already a System.Index value on the stack.
            return;
        }

        if (kind != java.lang.classfile.TypeKind.INT) {
            throw new UnsupportedOperationException(
                    "CodeEmitter only emits int-typed or System.Index range endpoints currently, not " + sourceType.displayName());
        }
        codeBuilder.invokestatic(vsIndexDesc, "fromStart",
                MethodTypeDesc.of(vsIndexDesc, ConstantDescs.CD_int));
    }

    /// Emits a C# interpolated string as a `StringBuilder` concatenation.
    ///
    /// Each `Text` part is appended as a literal string. Each `Hole` is evaluated, and
    /// if its type is already `string` or `REFERENCE`, it is appended directly via
    /// `StringBuilder.append(String)`; otherwise it is boxed and passed through
    /// `VsFormat.toDisplayString(Object)` to get C#-faithful formatting before appending.
    /// Alignment and format specifiers are not yet implemented and throw per that rule.
    private void emitInterpolated(IrExpression.Interpolated interpolated) {
        ClassDesc sbDesc = ClassDesc.of("java.lang.StringBuilder");
        codeBuilder.new_(sbDesc);
        codeBuilder.dup();
        codeBuilder.invokespecial(sbDesc, "<init>", MethodTypeDesc.of(ConstantDescs.CD_void));

        for (IrInterpolationPart part : interpolated.parts()) {
            switch (part) {
                case IrInterpolationPart.Text text -> {
                    codeBuilder.ldc(text.value());
                    codeBuilder.invokevirtual(sbDesc, "append",
                            MethodTypeDesc.of(sbDesc, ConstantDescs.CD_String));
                }
                case IrInterpolationPart.Hole hole -> {
                    // A hole renders exactly as a concatenation operand does, including the
                    // unsigned types whose signedness boxing would destroy. A format clause
                    // routes it through the same the design engine `ToString(string)` uses; the
                    // binder has already refused every clause this build cannot honour, and
                    // a clause on a non-formattable type is ignored exactly as C# ignores it.
                    if (hole.format() != null && emitFormattedHole(hole)) {
                        // The formatted string is on the stack.
                    } else {
                        emitDisplayString(hole.expression());
                    }
                    if (hole.alignment() != null) {
                        // Alignment is space padding to a minimum width, applied to the
                        // rendered hole - not a formatting policy - so it is one call on the
                        // string the hole already produced.
                        emitExpression(hole.alignment());
                        codeBuilder.invokestatic(ClassDesc.of("vsharp.runtime.VsFormat"), "align",
                                MethodTypeDesc.of(ConstantDescs.CD_String,
                                        ConstantDescs.CD_String, ConstantDescs.CD_int));
                    }
                    codeBuilder.invokevirtual(sbDesc, "append",
                            MethodTypeDesc.of(sbDesc, ConstantDescs.CD_String));
                }
            }
        }
        codeBuilder.invokevirtual(sbDesc, "toString",
                MethodTypeDesc.of(ConstantDescs.CD_String));
    }

    /// Emits a hole whose format clause this build honours, leaving the rendered string on
    /// the stack. Returns `false` when the hole's type is not one the the design engine formats, in
    /// which case C# ignores the clause and the caller renders normally.
    private boolean emitFormattedHole(IrInterpolationPart.Hole hole) {
        TypeSymbol type = hole.expression().type().sourceType();
        String method = numberFormatMethod(type);
        if (method == null) {
            return false;
        }
        emitExpression(hole.expression());
        codeBuilder.ldc(hole.format());
        codeBuilder.invokestatic(ClassDesc.of("vsharp.runtime.VsNumberFormat"), method,
                MethodTypeDesc.of(ConstantDescs.CD_String, toClassDesc(type),
                        ConstantDescs.CD_String));
        return true;
    }

    /// The `VsNumberFormat` entry point for `type`, selected by carrier *and* signedness
    /// because `uint` and `int` share the `int` carrier but render differently under `D`.
    private static String numberFormatMethod(TypeSymbol type) {
        if (!(type instanceof BuiltinType builtin)) {
            return null;
        }
        return switch (builtin) {
            case SBYTE -> "formatSByte";
            case BYTE -> "formatByte";
            case SHORT -> "formatShort";
            case USHORT -> "formatUShort";
            case INT -> "formatInt";
            case UINT -> "formatUInt";
            case LONG -> "formatLong";
            case ULONG -> "formatULong";
            case FLOAT -> "formatFloat";
            case DOUBLE -> "formatDouble";
            case DECIMAL -> "formatDecimal";
            default -> null;
        };
    }

    /// A `switch` statement, dispatched by comparing the governing value against each case
    /// constant in source order.
    ///
    /// Two governing shapes are supported, which is what C# needs for the value types V#
    /// carries: the integral ones (`int`, `char` and enums, all of which reach here as an
    /// `int`) compare with `if_icmpeq`, and `string` compares with `String.equals`. The string
    /// form is deliberately a chain rather than a `hashCode` `lookupswitch`: the chain cannot
    /// mis-dispatch on a hash collision, and switches in real source have few cases. A null
    /// governing string matches only `case null:` - `equals` would answer `false` for every
    /// other label anyway, but testing null first is what keeps the receiver non-null.
    private void emitSwitch(IrStatement.Switch switchStmt) {
        TypeSymbol governingType = switchStmt.governing().type().sourceType();
        java.lang.classfile.TypeKind governingKind = toTypeKind(governingType);
        boolean stringSwitch = governingType == BuiltinType.STRING;
        // The dense path below dispatches by constant value, which is what `goto case` needs
        // and what a C# switch over an int or a string almost always is. Any other governing
        // carrier, any pattern label that is not a constant, and any `when` guard need the
        // general pattern compiler instead - the same one `is` and switch expressions use
        //. Deciding once, before anything is emitted, keeps the two shapes separate.
        boolean constantDispatch = (intSwitchCarrier(governingKind) || stringSwitch)
                && switchStmt.sections().stream().flatMap(section -> section.labels().stream())
                        .allMatch(CodeEmitter::isConstantDispatchLabel);
        if (!constantDispatch) {
            emitPatternSwitch(switchStmt, governingType, governingKind);
            return;
        }

        // Evaluated once into a local so repeated case comparisons cannot re-run a
        // side-effecting governing expression.
        int governingSlot = codeBuilder.allocateLocal(governingKind);
        emitExpression(switchStmt.governing());
        storeLocal(governingKind, governingSlot);

        Label endLabel = codeBuilder.newLabel();
        List<Label> sectionLabels = new ArrayList<>(switchStmt.sections().size());
        Map<Object, Label> caseTargets = new LinkedHashMap<>();
        Label nullTarget = null;
        Label defaultTarget = null;

        // First pass: validate every label before emitting anything, so an unsupported
        // label fails atomically instead of leaving a half-emitted switch in the method.
        for (IrSwitchSection section : switchStmt.sections()) {
            Label sectionLabel = codeBuilder.newLabel();
            sectionLabels.add(sectionLabel);
            for (IrSwitchLabel label : section.labels()) {
                if (label.guard() != null) {
                    throw new UnsupportedOperationException(
                            "CodeEmitter does not yet emit guarded switch labels ('when')");
                }
                if (label.pattern() == null) {
                    // A null pattern is `default:` (IrSwitchLabel's own contract); the
                    // discard pattern `case _:` is a distinct C# pattern-matching form and
                    // arrives as IrPattern.Discard, not as a null pattern.
                    if (defaultTarget != null) {
                        throw new IllegalStateException(
                                "compiler defect: more than one default label reached the backend for one switch");
                    }
                    defaultTarget = sectionLabel;
                } else {
                    switch (label.pattern()) {
                        case IrPattern.Discard discard -> {
                            if (defaultTarget != null) {
                                throw new IllegalStateException(
                                        "compiler defect: more than one default label reached the backend for one switch");
                            }
                            defaultTarget = sectionLabel;
                        }
                        case IrPattern.Constant constant -> {
                            Object value = requireCaseConstant(constant.expression(),
                                    "switch case label");
                            if (value == NULL_CASE) {
                                nullTarget = sectionLabel;
                            } else {
                                caseTargets.put(value, sectionLabel);
                            }
                        }
                        default -> throw new UnsupportedOperationException(
                                "CodeEmitter does not yet emit switch label pattern: "
                                        + label.pattern().getClass().getSimpleName());
                    }
                }
            }
        }

        switchGotoTargets.push(new SwitchDispatch(caseTargets, defaultTarget, activeCleanups.size()));
        breakTargets.push(new JumpTarget(endLabel, activeCleanups.size()));

        Label fallback = defaultTarget != null ? defaultTarget : endLabel;
        if (stringSwitch) {
            codeBuilder.aload(governingSlot);
            codeBuilder.ifnull(nullTarget != null ? nullTarget : fallback);
            for (Map.Entry<Object, Label> entry : caseTargets.entrySet()) {
                codeBuilder.aload(governingSlot);
                codeBuilder.ldc((String) entry.getKey());
                codeBuilder.invokevirtual(ConstantDescs.CD_String, "equals",
                        MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_Object));
                codeBuilder.ifne(entry.getValue());
            }
        } else {
            if (nullTarget != null) {
                throw new UnsupportedOperationException(
                        "CodeEmitter cannot emit 'case null:' for a non-reference governing expression");
            }
            for (Map.Entry<Object, Label> entry : caseTargets.entrySet()) {
                codeBuilder.iload(governingSlot);
                codeBuilder.ldc((Integer) entry.getKey());
                codeBuilder.if_icmpeq(entry.getValue());
            }
        }
        codeBuilder.goto_(fallback);

        for (int i = 0; i < switchStmt.sections().size(); i++) {
            codeBuilder.labelBinding(sectionLabels.get(i));
            for (IrStatement sectionStatement : switchStmt.sections().get(i).statements()) {
                emitStatement(sectionStatement);
            }
        }

        codeBuilder.labelBinding(endLabel);
        breakTargets.pop();
        switchGotoTargets.pop();
    }

    /// True when `label` can be dispatched from a constant table: `default:`, `case _:`, or a
    /// case whose pattern is a folded constant, and with no `when` guard.
    private static boolean isConstantDispatchLabel(IrSwitchLabel label) {
        if (label.guard() != null) {
            return false;
        }
        return switch (label.pattern()) {
            case null -> true;
            case IrPattern.Discard ignored -> true;
            case IrPattern.Constant constant -> caseConstant(constant.expression()) != null;
            default -> false;
        };
    }

    /// A `switch` statement whose labels are patterns, guarded, or over a carrier no constant
    /// table can key: the governing value is parked in a slot of its own kind and each label
    /// is tested in source order by the general pattern compiler, jumping to its section on a
    /// match. Section bodies, fall-through, `break` and `goto case` are unchanged - only
    /// the dispatch differs - so `case > 5:`, `case int n when n &gt; 5:` and a `switch (o)` over
    /// a reference now compile instead of reporting an internal error.
    private void emitPatternSwitch(IrStatement.Switch switchStmt, TypeSymbol governingType,
            java.lang.classfile.TypeKind governingKind) {
        int governingSlot = codeBuilder.allocateLocal(governingKind);
        emitExpression(switchStmt.governing());
        storeLocal(governingKind, governingSlot);

        Label endLabel = codeBuilder.newLabel();
        List<Label> sectionLabels = new ArrayList<>(switchStmt.sections().size());
        Map<Object, Label> caseTargets = new LinkedHashMap<>();
        Label defaultTarget = null;
        for (IrSwitchSection section : switchStmt.sections()) {
            Label sectionLabel = codeBuilder.newLabel();
            sectionLabels.add(sectionLabel);
            for (IrSwitchLabel label : section.labels()) {
                boolean unguardedDefault = label.guard() == null
                        && (label.pattern() == null || label.pattern() instanceof IrPattern.Discard);
                if (unguardedDefault) {
                    if (defaultTarget != null) {
                        throw new IllegalStateException(
                                "compiler defect: more than one default label reached the backend for one switch");
                    }
                    defaultTarget = sectionLabel;
                    continue;
                }
                if (label.pattern() instanceof IrPattern.Constant constant) {
                    Object value = caseConstant(constant.expression());
                    if (value != null && value != NULL_CASE && label.guard() == null) {
                        // Still keyed for `goto case`, which only ever names a constant label.
                        caseTargets.putIfAbsent(value, sectionLabel);
                    }
                }
                Label nextLabel = codeBuilder.newLabel();
                initialisePatternBindings(label.pattern());
                emitPatternTest(label.pattern(), governingType, governingSlot);
                codeBuilder.ifeq(nextLabel);
                if (label.guard() != null) {
                    emitExpression(label.guard());
                    codeBuilder.ifeq(nextLabel);
                }
                codeBuilder.goto_(sectionLabel);
                codeBuilder.labelBinding(nextLabel);
            }
        }

        switchGotoTargets.push(new SwitchDispatch(caseTargets, defaultTarget, activeCleanups.size()));
        breakTargets.push(new JumpTarget(endLabel, activeCleanups.size()));
        codeBuilder.goto_(defaultTarget != null ? defaultTarget : endLabel);

        for (int i = 0; i < switchStmt.sections().size(); i++) {
            codeBuilder.labelBinding(sectionLabels.get(i));
            for (IrStatement sectionStatement : switchStmt.sections().get(i).statements()) {
                emitStatement(sectionStatement);
            }
        }

        codeBuilder.labelBinding(endLabel);
        breakTargets.pop();
        switchGotoTargets.pop();
    }

    /// Stands for `case null:`, which has no constant value to key a dispatch table by.
    private static final Object NULL_CASE = new Object();

    /// The already-folded compile-time constant a case label or `goto case` dispatches on:
    /// an `Integer` (covering `int`, `char` and enum members), a `String`, or [NULL_CASE].
    /// Anything else fails loudly rather than mis-dispatching.
    private static Object requireCaseConstant(IrExpression expression, String context) {
        Object value = caseConstant(expression);
        if (value != null) {
            return value;
        }
        throw new UnsupportedOperationException(
                "CodeEmitter only emits int, char, enum, string and null " + context
                        + "s currently, got: " + expression);
    }

    /// The same value as [requireCaseConstant], or `null` when `expression` is not one - which
    /// is how the dispatch shape is chosen before any instruction has been emitted.
    private static Object caseConstant(IrExpression expression) {
        if (expression instanceof IrExpression.Constant constant) {
            Object value = constant.value();
            if (value instanceof Integer || value instanceof String) {
                return value;
            }
            if (value instanceof Character character) {
                return (int) character.charValue();
            }
            if (value == null) {
                return NULL_CASE;
            }
        }
        return null;
    }

    private void emitForeach(IrStatement.Foreach foreachStmt) {
        TypeSymbol collectionType = foreachStmt.collection().type().sourceType();
        if (collectionType instanceof TypeSymbol.Array array
                && array.ranks().get(0) > 1) {
            emitRectangularForeach(foreachStmt, array);
            return;
        }
        if (collectionType == BuiltinType.STRING) {
            emitStringForeach(foreachStmt);
            return;
        }
        if (!(collectionType instanceof TypeSymbol.Array)) {
            emitIterableForeach(foreachStmt);
            return;
        }

        IrExpression collection = foreachStmt.collection();
        java.lang.classfile.TypeKind collectionKind = toTypeKind(collection.type().sourceType());
        int arrSlot = codeBuilder.allocateLocal(collectionKind);
        emitExpression(collection);
        storeLocal(collectionKind, arrSlot);

        int indexSlot = codeBuilder.allocateLocal(java.lang.classfile.TypeKind.INT);
        codeBuilder.ldc(0);
        codeBuilder.istore(indexSlot);

        Label conditionLabel = codeBuilder.newLabel();
        Label endLabel = codeBuilder.newLabel();
        Label continueLabel = codeBuilder.newLabel();

        breakTargets.push(new JumpTarget(endLabel, activeCleanups.size()));
        continueTargets.push(new JumpTarget(continueLabel, activeCleanups.size()));

        codeBuilder.labelBinding(conditionLabel);
        codeBuilder.iload(indexSlot);
        loadLocal(collectionKind, arrSlot);
        codeBuilder.arraylength();
        codeBuilder.if_icmpge(endLabel);

        IrIterationVariable iterationVariable = foreachStmt.variables().get(0);
        TypeSymbol elementType = foreachStmt.elementType().sourceType();
        TypeSymbol variableType = iterationVariable.type().sourceType();
        allocateSymbolSlot(iterationVariable.symbol(), variableType);

        loadLocal(collectionKind, arrSlot);
        codeBuilder.iload(indexSlot);
        emitArrayLoad(toTypeKind(elementType));
        emitIterationElement(elementType, variableType);
        storeSymbolValue(iterationVariable.symbol(), variableType, false);

        emitStatement(foreachStmt.body());

        codeBuilder.labelBinding(continueLabel);
        codeBuilder.iinc(indexSlot, 1);
        codeBuilder.goto_(conditionLabel);

        codeBuilder.labelBinding(endLabel);
        continueTargets.pop();
        breakTargets.pop();
    }

    /// A `string` is indexed, not iterated: C# defines `foreach (char c in s)` over
    /// `s[0..s.Length]`, and `String.charAt` is exactly that indexer on the JVM. Using the
    /// `CharSequence` iterator instead would iterate code *points* and box every one.
    private void emitStringForeach(IrStatement.Foreach foreachStmt) {
        IrIterationVariable iterationVariable = foreachStmt.variables().get(0);
        TypeSymbol variableType = iterationVariable.type().sourceType();

        int stringSlot = codeBuilder.allocateLocal(java.lang.classfile.TypeKind.REFERENCE);
        emitExpression(foreachStmt.collection());
        storeLocal(java.lang.classfile.TypeKind.REFERENCE, stringSlot);

        int indexSlot = codeBuilder.allocateLocal(java.lang.classfile.TypeKind.INT);
        codeBuilder.ldc(0);
        codeBuilder.istore(indexSlot);

        Label conditionLabel = codeBuilder.newLabel();
        Label endLabel = codeBuilder.newLabel();
        Label continueLabel = codeBuilder.newLabel();

        breakTargets.push(new JumpTarget(endLabel, activeCleanups.size()));
        continueTargets.push(new JumpTarget(continueLabel, activeCleanups.size()));

        codeBuilder.labelBinding(conditionLabel);
        codeBuilder.iload(indexSlot);
        loadLocal(java.lang.classfile.TypeKind.REFERENCE, stringSlot);
        codeBuilder.invokevirtual(ConstantDescs.CD_String, "length",
                MethodTypeDesc.of(ConstantDescs.CD_int));
        codeBuilder.if_icmpge(endLabel);

        allocateSymbolSlot(iterationVariable.symbol(), variableType);
        loadLocal(java.lang.classfile.TypeKind.REFERENCE, stringSlot);
        codeBuilder.iload(indexSlot);
        codeBuilder.invokevirtual(ConstantDescs.CD_String, "charAt",
                MethodTypeDesc.of(ConstantDescs.CD_char, ConstantDescs.CD_int));
        emitIterationElement(BuiltinType.CHAR, variableType);
        storeSymbolValue(iterationVariable.symbol(), variableType, false);

        emitStatement(foreachStmt.body());

        codeBuilder.labelBinding(continueLabel);
        codeBuilder.iinc(indexSlot, 1);
        codeBuilder.goto_(conditionLabel);

        codeBuilder.labelBinding(endLabel);
        continueTargets.pop();
        breakTargets.pop();
    }

    /// Every remaining enumerable is a `java.lang.Iterable`, so the loop is the
    /// ordinary `hasNext`/`next` shape javac emits, with the erased `Object` `next` returns
    /// narrowed to the element type the receiver's signature published.
    ///
    /// The iterator lives in a local rather than on the stack because `break`, `continue`
    /// and `return` unwind through cleanups that would not preserve it.
    private void emitIterableForeach(IrStatement.Foreach foreachStmt) {
        IrIterationVariable iterationVariable = foreachStmt.variables().get(0);
        TypeSymbol elementType = foreachStmt.elementType().sourceType();
        TypeSymbol variableType = iterationVariable.type().sourceType();

        int iteratorSlot = codeBuilder.allocateLocal(java.lang.classfile.TypeKind.REFERENCE);
        emitExpression(foreachStmt.collection());
        codeBuilder.invokeinterface(CD_ITERABLE, "iterator", MethodTypeDesc.of(CD_ITERATOR));
        storeLocal(java.lang.classfile.TypeKind.REFERENCE, iteratorSlot);

        Label conditionLabel = codeBuilder.newLabel();
        Label endLabel = codeBuilder.newLabel();
        Label continueLabel = codeBuilder.newLabel();

        breakTargets.push(new JumpTarget(endLabel, activeCleanups.size()));
        continueTargets.push(new JumpTarget(continueLabel, activeCleanups.size()));

        codeBuilder.labelBinding(conditionLabel);
        loadLocal(java.lang.classfile.TypeKind.REFERENCE, iteratorSlot);
        codeBuilder.invokeinterface(CD_ITERATOR, "hasNext",
                MethodTypeDesc.of(ConstantDescs.CD_boolean));
        codeBuilder.ifeq(endLabel);

        allocateSymbolSlot(iterationVariable.symbol(), variableType);
        loadLocal(java.lang.classfile.TypeKind.REFERENCE, iteratorSlot);
        codeBuilder.invokeinterface(CD_ITERATOR, "next",
                MethodTypeDesc.of(ConstantDescs.CD_Object));
        emitErasedElement(elementType);
        emitIterationElement(elementType, variableType);
        storeSymbolValue(iterationVariable.symbol(), variableType, false);

        emitStatement(foreachStmt.body());

        codeBuilder.labelBinding(continueLabel);
        codeBuilder.goto_(conditionLabel);

        codeBuilder.labelBinding(endLabel);
        continueTargets.pop();
        breakTargets.pop();
    }

    /// Narrows the erased `Object` an iterator yields to the element type the receiver's
    /// generic signature published. A raw or unbounded receiver publishes `object` itself,
    /// which needs no instruction; every other element is a `checkcast`, or an unboxing
    /// call when the V# element type is a value type whose JVM carrier is a wrapper.
    private void emitErasedElement(TypeSymbol elementType) {
        if (elementType == BuiltinType.OBJECT) {
            return;
        }
        if (toTypeKind(elementType) == java.lang.classfile.TypeKind.REFERENCE) {
            codeBuilder.checkcast(toClassDesc(elementType));
            return;
        }
        emitUnboxObject(elementType);
    }

    /// Converts the element one iteration step produced into the iteration variable's own
    /// type. `var` and an explicitly matching type make this a representation identity;
    /// C# additionally allows any explicit conversion here (CS0030), which the binder has
    /// already proved exists, so an ordinary conversion emission covers the rest.
    private void emitIterationElement(TypeSymbol elementType, TypeSymbol variableType) {
        if (elementType == variableType || elementType.equals(variableType)) {
            return;
        }
        emitNumericConversion(Conversions.classify(elementType, variableType),
                elementType, variableType);
    }

    /// A rectangular outermost rank (`int[,]`, `int[,,]`) is carried as nested JVM arrays,
    /// so `foreach` needs one loop per JVM array level. The innermost loop loads the leaf
    /// element and runs the body; `continue` targets that innermost increment and `break`
    /// exits past every level. A jagged outer rank (`int[][]`) stays on the vector path
    /// above, where each iteration legitimately yields an array.
    private void emitRectangularForeach(IrStatement.Foreach foreachStmt,
            TypeSymbol.Array arrayType) {
        int depth = arrayType.jvmDepth();
        int[] arraySlots = new int[depth];
        int[] indexSlots = new int[depth];

        Label endLabel = codeBuilder.newLabel();
        Label continueLabel = codeBuilder.newLabel();

        emitExpression(foreachStmt.collection());
        arraySlots[0] = codeBuilder.allocateLocal(java.lang.classfile.TypeKind.REFERENCE);
        storeLocal(java.lang.classfile.TypeKind.REFERENCE, arraySlots[0]);

        breakTargets.push(new JumpTarget(endLabel, activeCleanups.size()));
        continueTargets.push(new JumpTarget(continueLabel, activeCleanups.size()));

        emitRectangularForeachLevel(foreachStmt, arrayType, depth, 0, arraySlots, indexSlots,
                continueLabel);

        codeBuilder.labelBinding(endLabel);
        continueTargets.pop();
        breakTargets.pop();
    }

    private void emitRectangularForeachLevel(IrStatement.Foreach foreachStmt,
            TypeSymbol.Array arrayType, int depth, int level, int[] arraySlots, int[] indexSlots,
            Label continueLabel) {
        indexSlots[level] = codeBuilder.allocateLocal(java.lang.classfile.TypeKind.INT);
        codeBuilder.ldc(0);
        codeBuilder.istore(indexSlots[level]);

        Label startLabel = codeBuilder.newLabel();
        Label endLabel = codeBuilder.newLabel();

        codeBuilder.labelBinding(startLabel);
        codeBuilder.iload(indexSlots[level]);
        loadLocal(java.lang.classfile.TypeKind.REFERENCE, arraySlots[level]);
        codeBuilder.arraylength();
        codeBuilder.if_icmpge(endLabel);

        if (level == depth - 1) {
            IrIterationVariable iterationVariable = foreachStmt.variables().get(0);
            TypeSymbol elementType = foreachStmt.elementType().sourceType();
            TypeSymbol variableType = iterationVariable.type().sourceType();
            allocateSymbolSlot(iterationVariable.symbol(), variableType);

            loadLocal(java.lang.classfile.TypeKind.REFERENCE, arraySlots[level]);
            codeBuilder.iload(indexSlots[level]);
            emitArrayLoad(toTypeKind(elementType));
            emitIterationElement(elementType, variableType);
            storeSymbolValue(iterationVariable.symbol(), variableType, false);

            emitStatement(foreachStmt.body());
        } else {
            arraySlots[level + 1] = codeBuilder.allocateLocal(
                    java.lang.classfile.TypeKind.REFERENCE);
            loadLocal(java.lang.classfile.TypeKind.REFERENCE, arraySlots[level]);
            codeBuilder.iload(indexSlots[level]);
            codeBuilder.aaload();
            storeLocal(java.lang.classfile.TypeKind.REFERENCE, arraySlots[level + 1]);

            emitRectangularForeachLevel(foreachStmt, arrayType, depth, level + 1, arraySlots,
                    indexSlots, continueLabel);
        }

        if (level == depth - 1) {
            codeBuilder.labelBinding(continueLabel);
        }
        codeBuilder.iinc(indexSlots[level], 1);
        codeBuilder.goto_(startLabel);
        codeBuilder.labelBinding(endLabel);
    }

    private void emitExpression(IrExpression expression) {
        switch (expression) {
            case IrExpression.Constant constant -> {
                Object val = constant.value();
                if (val instanceof Integer i) {
                    codeBuilder.ldc(i);
                } else if (val instanceof Long l) {
                    codeBuilder.ldc(l);
                } else if (val instanceof Float f) {
                    codeBuilder.ldc(f);
                } else if (val instanceof String s) {
                    codeBuilder.ldc(s);
                } else if (val instanceof Double d) {
                    codeBuilder.ldc(d);
                } else if (val instanceof java.math.BigDecimal bd) {
                    // The JVM `ldc` cannot carry a `BigDecimal`, so the value is emitted as
                    // its exact plain spelling and reconstructed through the same runtime
                    // policy that validates literals - a folded constant therefore
                    // cannot differ from the same literal evaluated at run time.
                    codeBuilder.ldc(bd.toPlainString());
                    codeBuilder.invokestatic(CD_VS_DECIMAL, "fromLiteral",
                            MethodTypeDesc.of(CD_BIG_DECIMAL, ConstantDescs.CD_String));
                } else if (val instanceof Boolean b) {
                    codeBuilder.ldc(b ? 1 : 0);
                } else if (val instanceof Character ch) {
                    codeBuilder.ldc((int) ch.charValue());
                } else if (val == null) {
                    codeBuilder.aconst_null();
                } else if (val instanceof byte[] bytes) {
                    codeBuilder.ldc(bytes.length);
                    emitNewArray(java.lang.classfile.TypeKind.BYTE, BuiltinType.BYTE);
                    for (int index = 0; index < bytes.length; index++) {
                        codeBuilder.dup();
                        codeBuilder.ldc(index);
                        codeBuilder.bipush(bytes[index]);
                        codeBuilder.bastore();
                    }
                } else {
                    throw new UnsupportedOperationException(
                            "CodeEmitter does not yet emit a constant of runtime type: " + val.getClass());
                }
            }
            case IrExpression.ArrayCreation arr -> {
                // `new T[N]` supplies an explicit dimension; `new T[] { ... }` (or
                // `new T[] { }`) infers the length from the initializer instead and
                // lowers with an empty `dimensions()` - there is no dimension expression
                // to evaluate in that case, only the initializer's own element count.
                TypeSymbol.Array createdType =
                        (TypeSymbol.Array) arr.type().sourceType();
                // `new int[2, 3]` is one C# array with two dimensions and two JVM array
                // levels, so every dimension is evaluated and `multianewarray` allocates them
                // together. Emitting only the first produced a vector that indexing then
                // silently aliased into.
                if (arr.dimensions().size() > 1) {
                    arr.dimensions().forEach(this::emitExpression);
                    codeBuilder.multianewarray(toClassDesc(createdType), arr.dimensions().size());
                    break;
                }
                if (arr.dimensions().isEmpty()) {
                    codeBuilder.ldc(arr.initializer().size());
                } else {
                    emitExpression(arr.dimensions().get(0));
                }
                TypeSymbol elementType = createdType.elementType();
                java.lang.classfile.TypeKind elemKind = toTypeKind(elementType);
                emitNewArray(elemKind, elementType);
                if (!arr.initializer().isEmpty()) {
                    List<IrExpression> values = new ArrayList<>(arr.initializer().size());
                    for (IrArrayElement element : arr.initializer()) {
                        if (!(element instanceof IrArrayElement.Value value)) {
                            throw new UnsupportedOperationException(
                                    "CodeEmitter does not yet emit nested (multi-dimensional) array initializers");
                        }
                        values.add(value.expression());
                    }
                    emitArrayValueElements(values, elemKind);
                }
            }
            case IrExpression.TupleElementLoad element -> {
                TypeSymbol tupleType = element.receiver().type().sourceType();
                if (!(tupleType instanceof TypeSymbol.Tuple tuple)) {
                    throw new UnsupportedOperationException(
                            "CodeEmitter cannot read a tuple element from: " + tupleType);
                }
                ClassDesc tupleClass = ClassDesc.of("vsharp.runtime.VsTuple"
                        + tuple.elements().size());
                emitExpression(element.receiver());
                // The static carrier of a tuple is `Object`, so the accessor's receiver has
                // to be cast before the call, exactly as a pattern component's is.
                codeBuilder.checkcast(tupleClass);
                codeBuilder.invokevirtual(tupleClass, "item" + (element.index() + 1),
                        MethodTypeDesc.of(ConstantDescs.CD_Object));
                TypeSymbol elementType = element.type().sourceType();
                if (toTypeKind(elementType) != java.lang.classfile.TypeKind.REFERENCE) {
                    emitUnboxNullableElement(elementType);
                } else {
                    codeBuilder.checkcast(toClassDesc(elementType));
                }
            }
            case IrExpression.ArrayLength len -> {
                emitExpression(len.receiver());
                // C# `Length` is the element count of *this* array. For a vector - including a
                // jagged `int[][]`, which is a vector of arrays - that is `arraylength`. For a
                // rectangular array it is the product of that specifier's dimensions, which
                // `arraylength` would under-report as the outermost one alone. The rank
                // that matters is the outermost specifier, never the total JVM depth.
                int rank = len.receiver().type().sourceType()
                        instanceof TypeSymbol.Array array ? array.ranks().getFirst() : 1;
                if (rank > 1) {
                    codeBuilder.ldc(rank);
                    codeBuilder.invokestatic(ClassDesc.of("vsharp.runtime.VsArrays"), "length",
                            MethodTypeDesc.of(ConstantDescs.CD_int, ConstantDescs.CD_Object,
                                    ConstantDescs.CD_int));
                } else {
                    codeBuilder.arraylength();
                }
            }
            case IrExpression.StringLength len -> {
                emitExpression(len.receiver());
                codeBuilder.invokevirtual(ConstantDescs.CD_String, "length",
                        MethodTypeDesc.of(ConstantDescs.CD_int));
            }
            case IrExpression.StringElementLoad element -> {
                emitExpression(element.receiver());
                emitExpression(element.index());
                codeBuilder.invokevirtual(ConstantDescs.CD_String, "charAt",
                        MethodTypeDesc.of(ConstantDescs.CD_char, ConstantDescs.CD_int));
            }
            case IrExpression.ConditionalReceiver receiver -> emitConditionalReceiver(receiver);
            case IrExpression.NullConditional conditional -> emitNullConditional(conditional);
            case IrExpression.NullableHasValue hasValue -> emitNullableHasValue(hasValue);
            case IrExpression.NullableValue value -> emitNullableValue(value);
            case IrExpression.IndexElementLoad iload -> {
                emitExpression(iload.receiver());
                if (iload.receiver().type().sourceType() == BuiltinType.STRING) {
                    codeBuilder.dup();
                    codeBuilder.invokevirtual(ConstantDescs.CD_String, "length", MethodTypeDesc.of(ConstantDescs.CD_int));
                    emitExpression(iload.index());
                    codeBuilder.swap();
                    codeBuilder.invokestatic(ClassDesc.of("vsharp.runtime.VsSlices"), "offset", MethodTypeDesc.of(ConstantDescs.CD_int, ClassDesc.of("vsharp.runtime.VsIndex"), ConstantDescs.CD_int));
                    codeBuilder.invokevirtual(ConstantDescs.CD_String, "charAt", MethodTypeDesc.of(ConstantDescs.CD_char, ConstantDescs.CD_int));
                } else {
                    codeBuilder.dup();
                    codeBuilder.arraylength();
                    emitExpression(iload.index());
                    codeBuilder.swap();
                    codeBuilder.invokestatic(ClassDesc.of("vsharp.runtime.VsSlices"), "offset", MethodTypeDesc.of(ConstantDescs.CD_int, ClassDesc.of("vsharp.runtime.VsIndex"), ConstantDescs.CD_int));
                    emitArrayLoad(toTypeKind(iload.type().sourceType()));
                }
            }
            case IrExpression.IndexElementStore istore -> {
                emitExpression(istore.receiver());
                codeBuilder.dup();
                codeBuilder.arraylength();
                emitExpression(istore.index());
                codeBuilder.swap();
                codeBuilder.invokestatic(ClassDesc.of("vsharp.runtime.VsSlices"), "offset", MethodTypeDesc.of(ConstantDescs.CD_int, ClassDesc.of("vsharp.runtime.VsIndex"), ConstantDescs.CD_int));
                emitExpression(istore.value());
                java.lang.classfile.TypeKind typeKind = toTypeKind(istore.type().sourceType());
                if (typeKind == java.lang.classfile.TypeKind.LONG || typeKind == java.lang.classfile.TypeKind.DOUBLE) {
                    codeBuilder.dup2_x2();
                } else {
                    codeBuilder.dup_x2();
                }
                emitArrayStore(typeKind);
            }
            case IrExpression.SliceElementLoad sload -> {
                emitExpression(sload.receiver());
                emitExpression(sload.range());
                if (sload.receiver().type().sourceType() == BuiltinType.STRING) {
                    codeBuilder.invokestatic(ClassDesc.of("vsharp.runtime.VsSlices"), "slice", MethodTypeDesc.of(ConstantDescs.CD_String, ConstantDescs.CD_String, ClassDesc.of("vsharp.runtime.VsRange")));
                } else {
                    java.lang.classfile.TypeKind typeKind = toTypeKind(((TypeSymbol.Array)sload.receiver().type().sourceType()).elementType());
                    ClassDesc arrayDesc = toClassDesc(sload.receiver().type().sourceType());
                    if (typeKind == java.lang.classfile.TypeKind.REFERENCE) {
                        codeBuilder.invokestatic(ClassDesc.of("vsharp.runtime.VsSlices"), "slice", MethodTypeDesc.of(ClassDesc.ofDescriptor("[Ljava/lang/Object;"), ClassDesc.ofDescriptor("[Ljava/lang/Object;"), ClassDesc.of("vsharp.runtime.VsRange")));
                        codeBuilder.checkcast(arrayDesc);
                    } else {
                        codeBuilder.invokestatic(ClassDesc.of("vsharp.runtime.VsSlices"), "slice", MethodTypeDesc.of(arrayDesc, arrayDesc, ClassDesc.of("vsharp.runtime.VsRange")));
                    }
                }
            }
            case IrExpression.ElementLoad eload -> {
                emitExpression(eload.receiver());
                emitLeadingIndices(eload.indices());
                emitExpression(eload.indices().getLast());
                emitArrayLoad(toTypeKind(eload.type().sourceType()));
            }
            case IrExpression.ElementStore estore -> {
                emitExpression(estore.receiver());
                emitLeadingIndices(estore.indices());
                emitExpression(estore.indices().getLast());
                emitExpression(estore.value());
                java.lang.classfile.TypeKind typeKind = toTypeKind(estore.type().sourceType());
                
                if (typeKind == java.lang.classfile.TypeKind.LONG || typeKind == java.lang.classfile.TypeKind.DOUBLE) {
                    codeBuilder.dup2_x2();
                } else {
                    codeBuilder.dup_x2();
                }
                emitArrayStore(typeKind);
            }
            case IrExpression.FieldLoad fload -> {
                if (fload.receiver() != null) {
                    emitExpression(fload.receiver());
                    ClassDesc container = toClassDesc(fload.receiver().type().sourceType());
                    codeBuilder.getfield(container, fload.field().name(), toClassDesc(((FieldSymbol)fload.field()).type()));
                } else {
                    String qName = fload.field().qualifiedName();
                    String containerName = qName.substring(0, qName.lastIndexOf('.'));
                    codeBuilder.getstatic(ClassDesc.of(containerName), fload.field().name(), toClassDesc(((FieldSymbol)fload.field()).type()));
                }
            }
            case IrExpression.FieldStore fstore -> {
                if (fstore.receiver() != null) {
                    emitExpression(fstore.receiver());
                    emitExpression(fstore.value());
                    java.lang.classfile.TypeKind typeKind = toTypeKind(fstore.type().sourceType());
                    if (typeKind == java.lang.classfile.TypeKind.LONG || typeKind == java.lang.classfile.TypeKind.DOUBLE) {
                        codeBuilder.dup2_x1();
                    } else {
                        codeBuilder.dup_x1();
                    }
                    ClassDesc container = toClassDesc(fstore.receiver().type().sourceType());
                    codeBuilder.putfield(container, fstore.target().name(), toClassDesc(((FieldSymbol)fstore.target()).type()));
                } else {
                    emitExpression(fstore.value());
                    java.lang.classfile.TypeKind typeKind = toTypeKind(fstore.type().sourceType());
                    if (typeKind == java.lang.classfile.TypeKind.LONG || typeKind == java.lang.classfile.TypeKind.DOUBLE) {
                        codeBuilder.dup2();
                    } else {
                        codeBuilder.dup();
                    }
                    String qName = fstore.target().qualifiedName();
                    String containerName = qName.substring(0, qName.lastIndexOf('.'));
                    codeBuilder.putstatic(ClassDesc.of(containerName), fstore.target().name(), toClassDesc(((FieldSymbol)fstore.target()).type()));
                }
            }
            case IrExpression.Call call -> emitCall(call);
            case IrExpression.Load load -> {
                if (localSlots.containsKey(load.symbol())) {
                    loadSymbolValue(load.symbol(), load.type().sourceType());
                } else if (load.symbol() instanceof FieldSymbol field) {
                    if (field.modifiers().contains(vsharp.compiler.syntax.SyntaxKind.STATIC)) {
                        String qName = field.qualifiedName();
                        String containerName = qName.substring(0, qName.lastIndexOf('.'));
                        codeBuilder.getstatic(ClassDesc.of(containerName), field.name(), toClassDesc(field.type()));
                    } else {
                        // Implicit this
                        codeBuilder.aload(0);
                        String qName = field.qualifiedName();
                        String containerName = qName.substring(0, qName.lastIndexOf('.'));
                        ClassDesc container = ClassDesc.of(containerName);
                        codeBuilder.getfield(container, field.name(), toClassDesc(field.type()));
                    }
                }
            }
            case IrExpression.Store store -> {
                if (localSlots.containsKey(store.target())) {
                    emitExpression(store.value());
                    storeSymbolValue(store.target(), store.type().sourceType(), true);
                } else if (store.target() instanceof FieldSymbol field) {
                    if (field.modifiers().contains(vsharp.compiler.syntax.SyntaxKind.STATIC)) {
                        emitExpression(store.value());
                        java.lang.classfile.TypeKind typeKind = toTypeKind(store.type().sourceType());
                        if (typeKind == java.lang.classfile.TypeKind.LONG || typeKind == java.lang.classfile.TypeKind.DOUBLE) {
                            codeBuilder.dup2();
                        } else {
                            codeBuilder.dup();
                        }
                        String qName = field.qualifiedName();
                        String containerName = qName.substring(0, qName.lastIndexOf('.'));
                        codeBuilder.putstatic(ClassDesc.of(containerName), field.name(), toClassDesc(field.type()));
                    } else {
                        // Implicit this
                        codeBuilder.aload(0);
                        emitExpression(store.value());
                        java.lang.classfile.TypeKind typeKind = toTypeKind(store.type().sourceType());
                        if (typeKind == java.lang.classfile.TypeKind.LONG || typeKind == java.lang.classfile.TypeKind.DOUBLE) {
                            codeBuilder.dup2_x1();
                        } else {
                            codeBuilder.dup_x1();
                        }
                        String qName = field.qualifiedName();
                        String containerName = qName.substring(0, qName.lastIndexOf('.'));
                        ClassDesc container = ClassDesc.of(containerName);
                        codeBuilder.putfield(container, field.name(), toClassDesc(field.type()));
                    }
                }
            }
            case IrExpression.Mutate mutate -> emitMutate(mutate);
            case IrExpression.Binary binary -> emitBinary(binary);
            case IrExpression.Unary unary -> {
                if (unary.operand().type().sourceType() instanceof TypeSymbol.Nullable
                        && unary.type().sourceType() instanceof TypeSymbol.Nullable) {
                    emitLiftedUnary(unary);
                } else {
                    emitExpression(unary.operand());
                    emitUnaryOp(unary.operator(), unary.operand().type().sourceType());
                }
            }
            case IrExpression.Convert convert -> {
                emitExpression(convert.operand());
                emitNumericConversion(convert.conversion(),
                        convert.operand().type().sourceType(), convert.type().sourceType());
            }
            case IrExpression.NameOf nameOf -> codeBuilder.ldc(nameOf.name());
            case IrExpression.TypeOperation typeOperation -> emitTypeOperation(typeOperation);
            case IrExpression.FunctionalValue functional -> emitFunctionalValue(functional);
            case IrExpression.Await await -> emitAwait(await);
            case IrExpression.Tuple tuple -> emitTuple(tuple);
            case IrExpression.TupleDeconstructionStore store -> emitTupleDeconstructionStore(store);
            case IrExpression.ObjectCreation creation -> emitObjectCreation(creation);
            case IrExpression.RecordWith with -> emitRecordWith(with);
            case IrExpression.Conditional conditional -> {
                // Same branch/label shape as the `If` statement; the binder already gives
                // `whenTrue`/`whenFalse` a common type, so no per-branch type
                // reconciliation is needed here - whichever side runs leaves the one value
                // the surrounding expression context expects.
                emitExpression(conditional.condition());
                Label falseLabel = codeBuilder.newLabel();
                Label endLabel = codeBuilder.newLabel();
                codeBuilder.ifeq(falseLabel);
                emitExpression(conditional.whenTrue());
                codeBuilder.goto_(endLabel);
                codeBuilder.labelBinding(falseLabel);
                emitExpression(conditional.whenFalse());
                codeBuilder.labelBinding(endLabel);
            }
            case IrExpression.DefaultValue def -> {
                java.lang.classfile.TypeKind typeKind = toTypeKind(def.type().sourceType());
                if (typeKind == java.lang.classfile.TypeKind.INT || typeKind == java.lang.classfile.TypeKind.BOOLEAN || typeKind == java.lang.classfile.TypeKind.BYTE || typeKind == java.lang.classfile.TypeKind.SHORT || typeKind == java.lang.classfile.TypeKind.CHAR) {
                    codeBuilder.ldc(0);
                } else if (typeKind == java.lang.classfile.TypeKind.DOUBLE) {
                    codeBuilder.ldc(0.0);
                } else if (typeKind == java.lang.classfile.TypeKind.FLOAT) {
                    codeBuilder.ldc(0.0f);
                } else if (typeKind == java.lang.classfile.TypeKind.LONG) {
                    codeBuilder.ldc(0L);
                } else if (def.type().sourceType() == BuiltinType.DECIMAL) {
                    // C# `default(decimal)` is the scale-0 zero, not the JVM default
                    // `null` of the `BigDecimal` carrier.
                    codeBuilder.invokestatic(CD_VS_DECIMAL, "zero",
                            MethodTypeDesc.of(CD_BIG_DECIMAL));
                } else {
                    if (def.type().sourceType().isValueType() && def.type().sourceType() instanceof NamedTypeSymbol namedType) {
                        ClassDesc desc = toClassDesc(namedType);
                        codeBuilder.new_(desc);
                        codeBuilder.dup();
                        codeBuilder.invokespecial(desc, "<init>", java.lang.constant.MethodTypeDesc.of(java.lang.constant.ConstantDescs.CD_void));
                    } else {
                        codeBuilder.aconst_null();
                    }
                }
            }
            case IrExpression.IsPattern isPattern -> emitIsPattern(isPattern);
            case IrExpression.As asExpr -> emitAs(asExpr);
            case IrExpression.Range range -> emitRange(range);
            case IrExpression.Collection collection -> emitCollection(collection);
            case IrExpression.Switch switchExpr -> emitSwitchExpression(switchExpr);
            case IrExpression.Checked checked -> {
                // `checked(expr)` / `unchecked(expr)` set the context their operand is
                // emitted under; the value they leave on the stack is the operand's own.
                boolean priorContext = checkedContext;
                checkedContext = checked.checked();
                emitExpression(checked.operand());
                checkedContext = priorContext;
            }
            case IrExpression.Interpolated interpolated -> emitInterpolated(interpolated);
            default -> throw new UnsupportedOperationException(
                    "CodeEmitter does not yet emit expression form: " + expression.getClass().getSimpleName());
        }
    }

    /// The bootstrap every lambda call site names: `LambdaMetafactory.metafactory`.
    /// Its three leading parameters - lookup, name, invoked type - are supplied by the JVM,
    /// so only the SAM descriptor, the implementation handle and the instantiated descriptor
    /// are written into the constant pool.
    private static final DirectMethodHandleDesc LAMBDA_METAFACTORY =
            ConstantDescs.ofCallsiteBootstrap(
                    ClassDesc.of("java.lang.invoke.LambdaMetafactory"), "metafactory",
                    ConstantDescs.CD_CallSite, ConstantDescs.CD_MethodType,
                    ConstantDescs.CD_MethodHandle, ConstantDescs.CD_MethodType);

    /// Creates a Java functional-interface instance from a lambda.
    ///
    /// This is javac's own lowering, and deliberately so: the body is an ordinary static
    /// method and `invokedynamic` asks `LambdaMetafactory` to spin the implementing class at
    /// first execution. Emitting a hand-written class here would mean generating an
    /// object-model artefact - a named type with an instance method overriding an interface -
    /// which is exactly what V# does not have. The erased SAM descriptor and the instantiated
    /// one are both passed because they differ wherever generics were erased, and the
    /// difference is what the metafactory bridges.
    ///
    /// The call site takes no arguments: a capturing lambda is refused during binding, so the
    /// resulting instance is stateless and the JVM is free to reuse one.
    /// Emits `await task`: the task, the bounded blocking read, then the conversion
    /// the erased result needs.
    ///
    /// `VsAsync.await` is declared over a type variable, so it returns `Object` at the JVM
    /// level whatever the source type says. The cast or unboxing that follows is the same step
    /// any erased generic read takes, and it is what makes `int total = await Sum();` produce
    /// an `int` on the stack rather than an `Integer`.
    private void emitAwait(IrExpression.Await await) {
        emitExpression(await.operand());
        codeBuilder.invokestatic(CD_VS_ASYNC, "await",
                MethodTypeDesc.of(ConstantDescs.CD_Object, CD_FUTURE));
        TypeSymbol result = await.type().sourceType();
        if (result == BuiltinType.VOID) {
            // `await` on a bare `Task` is a statement, not a value. The result is always null
            // and nothing may read it, so it is dropped here rather than left on the stack for
            // a statement context that would have to know to pop it.
            codeBuilder.pop();
            return;
        }
        if (result == BuiltinType.OBJECT) {
            return;
        }
        // A value type is unboxed; a reference type is cast. The distinction is the carrier,
        // not the source kind: `string` and a declared struct are both references here and
        // both need the checkcast the erasure removed, while `int` needs its box opened.
        ClassDesc carrier = toClassDesc(result);
        if (carrier.isPrimitive()) {
            emitUnboxObject(result);
            return;
        }
        codeBuilder.checkcast(carrier);
    }

    private void emitFunctionalValue(IrExpression.FunctionalValue functional) {
        FunctionSymbol implementation = functional.implementation();
        DirectMethodHandleDesc implementationHandle = MethodHandleDesc.ofMethod(
                DirectMethodHandleDesc.Kind.STATIC, ownerClass,
                emittedMethodName(implementation),
                buildMethodTypeDesc(implementation, captureMap));
        // Each captured variable is pushed as the shared cell the enclosing method already
        // holds, so the lambda observes later mutations exactly as C# requires. The
        // call site's descriptor takes them and returns the interface.
        List<IrCapture> captures = captureMap.getOrDefault(implementation, List.of());
        List<ClassDesc> capturedDescs = new ArrayList<>(captures.size());
        for (IrCapture capture : captures) {
            Integer slot = localSlots.get(capture.symbol());
            if (slot == null) {
                throw new IllegalStateException("No cell for captured variable "
                        + capture.symbol().qualifiedName() + " while creating a lambda");
            }
            codeBuilder.aload(slot);
            capturedDescs.add(cellElementClassDesc(capture.type().sourceType()).arrayType());
        }
        codeBuilder.invokedynamic(DynamicCallSiteDesc.of(
                LAMBDA_METAFACTORY,
                functional.interfaceMethod().name(),
                MethodTypeDesc.of(toClassDesc(functional.type().sourceType()),
                        capturedDescs.toArray(new ClassDesc[0])),
                signatureOf(functional.erasedInterfaceMethod(), null),
                implementationHandle,
                signatureOf(functional.interfaceMethod(),
                        functional.erasedInterfaceMethod())));
    }

    /// The JVM descriptor of an interface method, ignoring any capture convention: an
    /// interface method never has one.
    ///
    /// When `erased` is given, the result is the *instantiated* descriptor and every position
    /// the erasure left as a reference is boxed. That is the difference between a genuine
    /// primitive and an erased type variable: `Predicate<string>.test` is
    /// `(String)boolean` - its `boolean` is declared, not erased - while
    /// `BiConsumer<string,int>.accept` is `(String, Integer)void`, because a type argument
    /// always erases to a reference. `LambdaMetafactory` requires the instantiated descriptor
    /// to specialize the erased one and rejects a primitive standing in for a type variable
    /// with a `BootstrapMethodError` at the first call - a failure no compile-time check
    /// catches, which is why it took an executed program to find. Adapting a boxed
    /// parameter back to the implementation's primitive is the metafactory's own job.
    private static MethodTypeDesc signatureOf(FunctionSymbol method, FunctionSymbol erased) {
        List<ClassDesc> parameters = new ArrayList<>();
        for (int index = 0; index < method.parameters().size(); index++) {
            TypeSymbol declared = method.parameters().get(index).type();
            parameters.add(erased == null || index >= erased.parameters().size()
                    ? toClassDesc(declared)
                    : genericCarrier(declared, erased.parameters().get(index).type()));
        }
        ClassDesc returnDesc = erased == null
                ? toClassDesc(method.returnType())
                : genericCarrier(method.returnType(), erased.returnType());
        return MethodTypeDesc.of(returnDesc, parameters.toArray(new ClassDesc[0]));
    }

    /// The carrier one instantiated position takes. A position the erasure kept as a value
    /// type was declared that way and stays exact; a position the erasure widened to a
    /// reference held a type variable and must therefore be boxed.
    private static ClassDesc genericCarrier(TypeSymbol instantiated, TypeSymbol erased) {
        ClassDesc exact = toClassDesc(instantiated);
        if (instantiated == BuiltinType.VOID || !exact.isPrimitive()) {
            return exact;
        }
        return toClassDesc(erased).isPrimitive() ? exact : boxedClassDesc(instantiated);
    }

    /// Emits `sizeof(T)` as its C# predefined constant. `typeof` over a module-path Java type
    /// is its descriptor-exact JVM `Class` literal, making the complete reflection surface
    /// directly reusable; a V# type remains an interned source-identity token because signed
    /// and unsigned types deliberately share primitive carriers. Binding prevents an
    /// unsupported operand from reaching this method.
    private void emitTypeOperation(IrExpression.TypeOperation operation) {
        switch (operation.operator()) {
            case SIZEOF -> codeBuilder.ldc(predefinedSize(operation.operandType().sourceType()));
            case TYPEOF -> {
                TypeSymbol operand = operation.operandType().sourceType();
                if (JavaInterop.hasClassLiteral(operand)) {
                    codeBuilder.ldc(toClassDesc(operand));
                } else {
                    codeBuilder.ldc(typeIdentity(operand));
                    codeBuilder.invokestatic(CD_VS_TYPE, "of",
                            MethodTypeDesc.of(CD_VS_TYPE, ConstantDescs.CD_String));
                }
            }
        }
    }

    private static int predefinedSize(TypeSymbol type) {
        if (!(type instanceof BuiltinType builtin)) {
            throw new UnsupportedOperationException(
                    "CodeEmitter cannot emit sizeof for: " + type.displayName());
        }
        return switch (builtin) {
            case SBYTE, BYTE, BOOL -> 1;
            case SHORT, USHORT, CHAR -> 2;
            case INT, UINT, FLOAT -> 4;
            case LONG, ULONG, DOUBLE -> 8;
            case DECIMAL -> 16;
            case NINT, NUINT, STRING, OBJECT, DYNAMIC, VOID ->
                    throw new UnsupportedOperationException(
                            "CodeEmitter cannot emit sizeof for: " + type.displayName());
        };
    }

    /// Canonical identity ignores tuple element names (compile-time-only metadata) while
    /// preserving every distinction the JVM carrier erases. Corelib qualified names use the
    /// same `System.*` spellings as their keyword aliases, so `typeof(int)` and
    /// `typeof(System.Int32)` intern to the same token.
    private static String typeIdentity(TypeSymbol type) {
        return switch (type) {
            case BuiltinType builtin -> switch (builtin) {
                case SBYTE -> "System.SByte";
                case BYTE -> "System.Byte";
                case SHORT -> "System.Int16";
                case USHORT -> "System.UInt16";
                case INT -> "System.Int32";
                case UINT -> "System.UInt32";
                case LONG -> "System.Int64";
                case ULONG -> "System.UInt64";
                case NINT -> "System.IntPtr";
                case NUINT -> "System.UIntPtr";
                case FLOAT -> "System.Single";
                case DOUBLE -> "System.Double";
                case DECIMAL -> "System.Decimal";
                case BOOL -> "System.Boolean";
                case CHAR -> "System.Char";
                case STRING -> "System.String";
                case OBJECT -> "System.Object";
                case DYNAMIC -> "System.Object";
                case VOID -> "System.Void";
            };
            case NamedTypeSymbol named -> named.arity() == 0
                    ? named.qualifiedName()
                    : named.qualifiedName() + "<" + ",".repeat(named.arity() - 1) + ">";
            case TypeSymbol.Array array -> {
                StringBuilder name = new StringBuilder(typeIdentity(array.element()));
                for (int rank : array.ranks()) {
                    name.append('[').append(",".repeat(rank - 1)).append(']');
                }
                yield name.toString();
            }
            case TypeSymbol.Tuple tuple -> tuple.elements().stream()
                    .map(element -> typeIdentity(element.type()))
                    .collect(java.util.stream.Collectors.joining(",", "System.ValueTuple<", ">"));
            case TypeSymbol.Nullable nullable ->
                    "System.Nullable<" + typeIdentity(nullable.element()) + ">";
            case TypeSymbol.Constructed constructed -> constructed.arguments().stream()
                    .map(CodeEmitter::typeIdentity)
                    .collect(java.util.stream.Collectors.joining(",",
                            constructed.definition().qualifiedName() + "<", ">"));
            case TypeSymbol.Range ignored -> "System.Range";
            case TypeSymbol.Index ignored -> "System.Index";
            default -> throw new UnsupportedOperationException(
                    "CodeEmitter cannot emit typeof for: " + type.displayName());
        };
    }

    /// Emits Java interop construction after semantic overload resolution has selected one
    /// public constructor and inserted every required argument conversion.
    private void emitObjectCreation(IrExpression.ObjectCreation creation) {
        // `runtimeClassDesc`, not `toClassDesc`: a corelib exception's values are instances of
        // its JVM carrier, while every other named type is its own class.
        ClassDesc owner = runtimeClassDesc(creation.type().sourceType());
        codeBuilder.new_(owner);
        codeBuilder.dup();
        creation.arguments().forEach(this::emitExpression);
        List<ClassDesc> parameters = creation.constructor().parameters().stream()
                .map(parameter -> toClassDesc(parameter.type())).toList();
        codeBuilder.invokespecial(owner, ConstantDescs.INIT_NAME,
                MethodTypeDesc.of(ConstantDescs.CD_void,
                        parameters.toArray(ClassDesc[]::new)));
    }

    /// `receiver with { X = v }`: parks the receiver in a slot, then calls the positional
    /// constructor with one argument per component - the replacement where the source named
    /// one, and a `getfield` off the parked receiver everywhere else. The receiver is
    /// evaluated once, so `Next() with { X = 1 }` advances once like C# requires.
    private void emitRecordWith(IrExpression.RecordWith with) {
        emitExpression(with.receiver());
        int receiverSlot = codeBuilder.allocateLocal(java.lang.classfile.TypeKind.REFERENCE);
        codeBuilder.astore(receiverSlot);
        ClassDesc owner = toClassDesc(with.type().sourceType());
        codeBuilder.new_(owner);
        codeBuilder.dup();
        for (vsharp.compiler.semantics.symbols.FieldSymbol component : with.components()) {
            IrExpression replacement = with.replacements().get(component.name());
            if (replacement != null) {
                emitExpression(replacement);
            } else {
                codeBuilder.aload(receiverSlot);
                codeBuilder.getfield(owner, component.name(), toClassDesc(component.type()));
            }
        }
        List<ClassDesc> parameters = with.constructor().parameters().stream()
                .map(parameter -> toClassDesc(parameter.type())).toList();
        codeBuilder.invokespecial(owner, ConstantDescs.INIT_NAME,
                MethodTypeDesc.of(ConstantDescs.CD_void,
                        parameters.toArray(ClassDesc[]::new)));
    }

    /// Selects `newarray`/`anewarray` for a fresh array of `elemKind` (the array's own
    /// length is expected already on the stack) - factored out of `ArrayCreation` so
    /// `emitCollection` can share the exact same opcode-selection rule.
    private void emitNewArray(java.lang.classfile.TypeKind elemKind, TypeSymbol elementType) {
        if (elemKind == java.lang.classfile.TypeKind.INT) {
            codeBuilder.newarray(java.lang.classfile.TypeKind.INT);
        } else if (elemKind == java.lang.classfile.TypeKind.LONG) {
            codeBuilder.newarray(java.lang.classfile.TypeKind.LONG);
        } else if (elemKind == java.lang.classfile.TypeKind.FLOAT) {
            codeBuilder.newarray(java.lang.classfile.TypeKind.FLOAT);
        } else if (elemKind == java.lang.classfile.TypeKind.DOUBLE) {
            codeBuilder.newarray(java.lang.classfile.TypeKind.DOUBLE);
        } else if (elemKind == java.lang.classfile.TypeKind.BOOLEAN) {
            codeBuilder.newarray(java.lang.classfile.TypeKind.BOOLEAN);
        } else if (elemKind == java.lang.classfile.TypeKind.BYTE) {
            codeBuilder.newarray(java.lang.classfile.TypeKind.BYTE);
        } else if (elemKind == java.lang.classfile.TypeKind.SHORT) {
            codeBuilder.newarray(java.lang.classfile.TypeKind.SHORT);
        } else if (elemKind == java.lang.classfile.TypeKind.CHAR) {
            codeBuilder.newarray(java.lang.classfile.TypeKind.CHAR);
        } else {
            codeBuilder.anewarray(toClassDesc(elementType));
        }
    }

    /// Populates a freshly created array (its reference already on the stack) with
    /// `values` at sequential indices `0..values.size()-1`, leaving that same array
    /// reference on the stack afterward - the standard `dup; ldc index; <value>; Xastore`
    /// shape repeated once per element, shared by `ArrayCreation`'s flat initializer and
    /// `Collection`'s (non-spread) elements.
    private void emitArrayValueElements(List<IrExpression> values, java.lang.classfile.TypeKind elemKind) {
        for (int i = 0; i < values.size(); i++) {
            codeBuilder.dup();
            codeBuilder.ldc(i);
            emitExpression(values.get(i));
            emitArrayStore(elemKind);
        }
    }

    /// `[e1, e2, ...]`, a target-typed collection expression - scoped to an array target
    /// (the only collection runtime representation this backend has chosen so far, per
    ///) and to plain (non-`spread`) elements: a `..` spread element needs
    /// its source's length known to size the array before any element is stored (or a
    /// resizable-then-copy strategy), which is real, separate work from populating a
    /// fixed-arity collection and is deliberately not attempted here (pending priority 13).
    private void emitCollection(IrExpression.Collection collection) {
        if (!(collection.type().sourceType() instanceof TypeSymbol.Array array)) {
            throw new UnsupportedOperationException(
                    "CodeEmitter only emits array-targeted collection expressions currently, not "
                            + collection.type().sourceType());
        }
        TypeSymbol elementType = array.elementType();
        java.lang.classfile.TypeKind elemKind = toTypeKind(elementType);

        boolean hasSpread = false;
        for (IrCollectionElement element : collection.elements()) {
            if (element.spread()) {
                hasSpread = true;
                break;
            }
        }

        if (!hasSpread) {
            List<IrExpression> values = new ArrayList<>(collection.elements().size());
            for (IrCollectionElement element : collection.elements()) {
                values.add(element.expression());
            }
            codeBuilder.ldc(values.size());
            emitNewArray(elemKind, elementType);
            emitArrayValueElements(values, elemKind);
            return;
        }

        // Spread elements exist. Calculate total size dynamically and cache spread arrays.
        int sizeLocal = codeBuilder.allocateLocal(java.lang.classfile.TypeKind.INT);
        codeBuilder.ldc(0);
        codeBuilder.istore(sizeLocal);

        List<Integer> spreadLocals = new ArrayList<>(collection.elements().size());
        for (IrCollectionElement element : collection.elements()) {
            if (element.spread()) {
                emitExpression(element.expression());
                int spreadLocal = codeBuilder.allocateLocal(java.lang.classfile.TypeKind.REFERENCE);
                codeBuilder.astore(spreadLocal);
                spreadLocals.add(spreadLocal);

                // Add spread array length to sizeLocal
                codeBuilder.aload(spreadLocal);
                codeBuilder.arraylength();
                codeBuilder.iload(sizeLocal);
                codeBuilder.iadd();
                codeBuilder.istore(sizeLocal);
            } else {
                spreadLocals.add(-1);
                // Increment sizeLocal by 1
                codeBuilder.iinc(sizeLocal, 1);
            }
        }

        // Allocate the target array
        codeBuilder.iload(sizeLocal);
        emitNewArray(elemKind, elementType);
        int targetArrayLocal = codeBuilder.allocateLocal(java.lang.classfile.TypeKind.REFERENCE);
        codeBuilder.astore(targetArrayLocal);

        // Populate the target array
        int indexLocal = codeBuilder.allocateLocal(java.lang.classfile.TypeKind.INT);
        codeBuilder.ldc(0);
        codeBuilder.istore(indexLocal);

        for (int i = 0; i < collection.elements().size(); i++) {
            IrCollectionElement element = collection.elements().get(i);
            if (element.spread()) {
                int spreadLocal = spreadLocals.get(i);
                
                // System.arraycopy(spreadLocal, 0, targetArrayLocal, indexLocal, spreadLocal.length)
                codeBuilder.aload(spreadLocal);
                codeBuilder.ldc(0);
                codeBuilder.aload(targetArrayLocal);
                codeBuilder.iload(indexLocal);
                codeBuilder.aload(spreadLocal);
                codeBuilder.arraylength();
                codeBuilder.invokestatic(ClassDesc.of("java.lang.System"), "arraycopy",
                        java.lang.constant.MethodTypeDesc.ofDescriptor("(Ljava/lang/Object;ILjava/lang/Object;II)V"));
                
                // indexLocal += spreadLocal.length
                codeBuilder.aload(spreadLocal);
                codeBuilder.arraylength();
                codeBuilder.iload(indexLocal);
                codeBuilder.iadd();
                codeBuilder.istore(indexLocal);
            } else {
                codeBuilder.aload(targetArrayLocal);
                codeBuilder.iload(indexLocal);
                emitExpression(element.expression());
                emitArrayStore(elemKind);
                codeBuilder.iinc(indexLocal, 1);
            }
        }

        // Leave the target array on the stack
        codeBuilder.aload(targetArrayLocal);
    }

    /// Walks every index but the last, leaving the innermost JVM array on the stack.
    ///
    /// A C# rectangular access `m[i, j]` names one element of one array; the JVM reaches it
    /// through one `aaload` per leading dimension. Emitting only the last index - which is
    /// what the backend used to do - silently aliased distinct elements onto the same slot.
    private void emitLeadingIndices(List<IrExpression> indices) {
        for (int index = 0; index < indices.size() - 1; index++) {
            emitExpression(indices.get(index));
            codeBuilder.aaload();
        }
    }

    private void emitArrayLoad(java.lang.classfile.TypeKind typeKind) {
        switch (typeKind) {
            case INT -> codeBuilder.iaload();
            case LONG -> codeBuilder.laload();
            case FLOAT -> codeBuilder.faload();
            case DOUBLE -> codeBuilder.daload();
            case BYTE, BOOLEAN -> codeBuilder.baload();
            case SHORT -> codeBuilder.saload();
            case CHAR -> codeBuilder.caload();
            case REFERENCE -> codeBuilder.aaload();
            default -> throw new UnsupportedOperationException("Unsupported array load type: " + typeKind);
        }
    }
    
    private void emitArrayStore(java.lang.classfile.TypeKind typeKind) {
        switch (typeKind) {
            case INT -> codeBuilder.iastore();
            case LONG -> codeBuilder.lastore();
            case FLOAT -> codeBuilder.fastore();
            case DOUBLE -> codeBuilder.dastore();
            case BYTE, BOOLEAN -> codeBuilder.bastore();
            case SHORT -> codeBuilder.sastore();
            case CHAR -> codeBuilder.castore();
            case REFERENCE -> codeBuilder.aastore();
            default -> throw new UnsupportedOperationException("Unsupported array store type: " + typeKind);
        }
    }

    /// Emits a call while preserving the source evaluation order of named arguments.
    ///
    /// JVM invocation operands must be stacked in parameter order, while C# evaluates
    /// explicit arguments in textual order. Positional calls already satisfy both orders and
    /// take the direct path. A reordered call evaluates the receiver first and every argument
    /// once into fresh locals, then reloads them in parameter order. Optional defaults are
    /// already appended by expression binding and participate in the same ordinal map.
    private void emitCall(IrExpression.Call call) {
        FunctionSymbol function = call.function();
        FunctionSymbol declaration = call.declaration();
        List<ParameterSymbol> parameters = function.parameters();
        if (parameters.stream().anyMatch(CodeEmitter::isRefOrOutParameter)) {
            emitRefOrOutCall(call);
            return;
        }
        boolean staticTarget = isStaticMethod(declaration, captureMap);
        boolean parameterOrder = call.arguments().size() == parameters.size();
        for (int i = 0; parameterOrder && i < call.arguments().size(); i++) {
            parameterOrder = call.argumentParameterOrdinals().get(i) == i;
        }

        if (parameterOrder) {
            if (call.receiver() != null) {
                emitExpression(call.receiver());
            } else if (!staticTarget && declaration.localFunction()) {
                codeBuilder.aload(0);
            }
            for (int index = 0; index < call.arguments().size(); index++) {
                emitExpression(call.arguments().get(index));
                int ordinal = call.argumentParameterOrdinals().get(index);
                adaptGenericArgument(parameters.get(ordinal).type(),
                        declaration.parameters().get(ordinal).type());
            }
        } else {
            Integer receiverSlot = null;
            java.lang.classfile.TypeKind receiverKind = null;
            if (call.receiver() != null) {
                receiverKind = toTypeKind(call.receiver().type().sourceType());
                receiverSlot = codeBuilder.allocateLocal(receiverKind);
                emitExpression(call.receiver());
                storeLocal(receiverKind, receiverSlot);
            } else if (!staticTarget && declaration.localFunction()) {
                receiverKind = java.lang.classfile.TypeKind.REFERENCE;
                receiverSlot = 0;
            }

            int[] parameterSlots = new int[parameters.size()];
            java.util.Arrays.fill(parameterSlots, -1);
            for (int i = 0; i < call.arguments().size(); i++) {
                int ordinal = call.argumentParameterOrdinals().get(i);
                if (ordinal < 0 || ordinal >= parameters.size()
                        || parameterSlots[ordinal] != -1) {
                    throw new UnsupportedOperationException(
                            "CodeEmitter does not yet emit expanded params calls");
                }
                java.lang.classfile.TypeKind kind = toTypeKind(parameters.get(ordinal).type());
                int slot = codeBuilder.allocateLocal(kind);
                emitExpression(call.arguments().get(i));
                storeLocal(kind, slot);
                parameterSlots[ordinal] = slot;
            }

            if (receiverSlot != null) {
                loadLocal(receiverKind, receiverSlot);
            }
            for (int i = 0; i < parameterSlots.length; i++) {
                if (parameterSlots[i] == -1) {
                    throw new IllegalStateException("call argument missing for parameter " + i
                            + " of " + function.qualifiedName());
                }
                loadLocal(toTypeKind(parameters.get(i).type()), parameterSlots[i]);
                adaptGenericArgument(parameters.get(i).type(),
                        declaration.parameters().get(i).type());
            }
        }

        emitCaptureArguments(declaration);

        String qualifiedName = declaration.qualifiedName();
        if (isExtern(function)) {
            emitRuntimeCall(function, call);
        } else if (isStaticMethod(declaration, captureMap)) {
            emitStaticInvoke(declaration, qualifiedName, captureMap);
            adaptGenericReturn(call);
        } else {
            if (call.receiver() == null) {
                if (!declaration.localFunction()) {
                    throw new IllegalStateException(
                            "instance call has no receiver: " + function.qualifiedName());
                }
                codeBuilder.invokevirtual(callableContainer(qualifiedName),
                        emittedMethodName(declaration),
                        buildMethodTypeDesc(declaration, captureMap));
                adaptGenericReturn(call);
                return;
            }
            emitInstanceInvoke(call.receiver().type().sourceType(), declaration, captureMap);
            adaptGenericReturn(call);
        }
    }

    /// Invokes a static method, in the constant-pool form its owner requires: a static method
    /// declared on a JVM interface - `java.util.List.of` and every other JDK factory - must be
    /// referenced through an `InterfaceMethodref`, and a `Methodref` for it is an
    /// `IncompatibleClassChangeError` at the first call.
    private void emitStaticInvoke(FunctionSymbol declaration, String qualifiedName,
            Map<FunctionSymbol, List<IrCapture>> captures) {
        codeBuilder.invokestatic(callableContainer(qualifiedName), emittedMethodName(declaration),
                buildMethodTypeDesc(declaration, captures), declaration.interfaceOwner());
    }

    /// Invokes an instance method on a receiver whose static type decides the opcode: a JVM
    /// interface reference requires `invokeinterface`, and using `invokevirtual` for it is not
    /// a style choice but an `IncompatibleClassChangeError` at the first call.
    private void emitInstanceInvoke(TypeSymbol receiverType, FunctionSymbol declaration,
            Map<FunctionSymbol, List<IrCapture>> captures) {
        ClassDesc owner = toClassDesc(receiverType);
        MethodTypeDesc descriptor = buildMethodTypeDesc(declaration, captures);
        // A wildcard-typed receiver carries its bound, and the bound is what decides the
        // opcode: `WebClient.Get()` is typed `RequestHeadersUriSpec<?>` whose bound is an
        // interface, and `invokevirtual` on one is an `IncompatibleClassChangeError` at the
        // first call, not a style choice.
        TypeSymbol dispatchType = receiverType instanceof TypeSymbol.Wildcard wildcard
                ? wildcard.bound()
                : receiverType;
        NamedTypeSymbol named = dispatchType instanceof NamedTypeSymbol direct ? direct
                : dispatchType instanceof TypeSymbol.Constructed constructed
                        ? constructed.definition() : null;
        // The emitted name, not the written one: a Java member spells both the same, while a
        // synthesized record value member is written `Equals` and emitted `equals`.
        String method = emittedMethodName(declaration);
        if (named != null
                && named.declaredKind() == NamedTypeSymbol.DeclaredKind.INTERFACE) {
            codeBuilder.invokeinterface(owner, method, descriptor);
            return;
        }
        codeBuilder.invokevirtual(owner, method, descriptor);
    }

    /// The call an expression statement discards, when its JVM result is an erased reference
    /// that [adaptGenericReturn] would otherwise narrow, or `null` for every other statement.
    private IrExpression.Call erasedResultCall(IrExpression expression) {
        if (!(expression instanceof IrExpression.Call call)
                || call.type().isVoid()
                || call.declaration() == null
                || isExtern(call.function())) {
            return null; // Runtime intrinsics own their own result shape.
        }
        return toTypeKind(call.declaration().returnType()) == java.lang.classfile.TypeKind.REFERENCE
                ? call : null;
    }

    /// Adapts a source-specialized argument to the declaration's erased reference descriptor.
    /// Reference carriers already satisfy it; a primitive direct-`T` specialization retains
    /// its source identity through the same boxing helpers used by ordinary object conversion.
    private void adaptGenericArgument(TypeSymbol specialized, TypeSymbol declared) {
        if (toTypeKind(declared) == java.lang.classfile.TypeKind.REFERENCE
                && toTypeKind(specialized) != java.lang.classfile.TypeKind.REFERENCE) {
            emitBox(specialized);
        }
    }

    /// Converts an erased result back to the specialized source type. The descriptor-exact
    /// declaration owns the invocation while the specialized symbol owns the expression type;
    /// keeping both on the call prevents a nonexistent specialized JVM method reference.
    private void adaptGenericReturn(IrExpression.Call call) {
        if (call == discardedCall) {
            return; // The statement pops the erased reference instead of narrowing it.
        }
        TypeSymbol declared = call.declaration().returnType();
        if (toTypeKind(declared) != java.lang.classfile.TypeKind.REFERENCE) {
            return;
        }
        // The *expression's* type, not the selected symbol's return type. They are the same for
        // every ordinary generic call, and differ for exactly the shape the design describes: a type
        // parameter only a lambda could supply is still open on the symbol when the call node is
        // built, and is closed afterwards from the bound lambda body. The closed type is what
        // the rest of the program was type-checked against - it is what chose
        // `Console.WriteLine(int)` here - so it is what the narrowing must be emitted for.
        // Reading the open one skipped the narrowing and produced a class the JVM refused to
        // load, from a compilation that reported nothing.
        TypeSymbol specialized = call.type().sourceType();
        if (toTypeKind(specialized) != java.lang.classfile.TypeKind.REFERENCE) {
            emitUnboxObject(specialized);
            return;
        }
        ClassDesc target = toClassDesc(specialized);
        if (!target.equals(ConstantDescs.CD_Object)
                && !target.equals(toClassDesc(declared))) {
            codeBuilder.checkcast(target);
        }
    }

    /// Emits a call whose parameter list contains `ref`/`out` parameters. Each such
    /// argument must currently be a local, parameter, or another `ref`/`out` parameter
    /// (checked earlier by [Compilation.unsupportedRefOutDiagnostics]); the supported shapes
    /// all lower to [IrExpression.Load], so this method can recover the target slot directly
    /// from `localSlots`.
    ///
    /// For a `ref` argument the current value is copied into a fresh one-element array cell,
    /// for `out` the cell is left default-initialised, and for a pass-through `ref`/`out`
    /// parameter the existing cell is forwarded unchanged. After the invocation every cell
    /// is copied back to its target slot in parameter order.
    private void emitRefOrOutCall(IrExpression.Call call) {
        FunctionSymbol function = call.function();
        FunctionSymbol declaration = call.declaration();
        List<ParameterSymbol> parameters = function.parameters();
        boolean staticTarget = isStaticMethod(declaration, captureMap);

        Integer receiverSlot = null;
        java.lang.classfile.TypeKind receiverKind = null;
        if (call.receiver() != null) {
            receiverKind = toTypeKind(call.receiver().type().sourceType());
            receiverSlot = codeBuilder.allocateLocal(receiverKind);
            emitExpression(call.receiver());
            storeLocal(receiverKind, receiverSlot);
        } else if (!staticTarget && declaration.localFunction()) {
            receiverKind = java.lang.classfile.TypeKind.REFERENCE;
            receiverSlot = 0;
        }

        int[] parameterSlots = new int[parameters.size()];
        java.util.Arrays.fill(parameterSlots, -1);
        List<RefOutCopyBack> copyBacks = new ArrayList<>();

        for (int i = 0; i < call.arguments().size(); i++) {
            int ordinal = call.argumentParameterOrdinals().get(i);
            if (ordinal < 0 || ordinal >= parameters.size() || parameterSlots[ordinal] != -1) {
                throw new UnsupportedOperationException(
                        "CodeEmitter cannot emit a ref/out call with a duplicated or missing parameter ordinal");
            }
            ParameterSymbol parameter = parameters.get(ordinal);
            if (isRefOrOutParameter(parameter)) {
                IrExpression argument = call.arguments().get(i);
                // The *place* keeps the specialized type - that is what the caller's variable
                // is - while the cell takes the declaration's, which is what the descriptor
                // demands: a `ref T` is `[Ljava/lang/Object;` however the call specialized it,
                // so a `string` local is carried in an `Object[]` and an `int` local is boxed
                // into one. The two are the same type for every non-generic call, which is why
                // nothing else changes.
                TypeSymbol elementType = parameter.type();
                TypeSymbol cellType = declaration.parameters().get(ordinal).type();
                int cellSlot = codeBuilder.allocateLocal(java.lang.classfile.TypeKind.REFERENCE);
                emitRefOutArgument(argument, parameter, ordinal, elementType, cellType, cellSlot,
                        copyBacks);
                parameterSlots[ordinal] = cellSlot;
            } else {
                // A by-value argument of a generic call still crosses the erasure boundary,
                // so it is adapted and spilled in the *declaration's* carrier: `Bump(ref n, 99)`
                // pushes a boxed `Integer` for a `T` parameter. The ordinary call path has
                // always done this; the ref/out path had no generic calls to do it for until
                // now, and pushing the raw `int` was a verifier error rather than a wrong
                // value.
                TypeSymbol declaredType = declaration.parameters().get(ordinal).type();
                java.lang.classfile.TypeKind kind = toTypeKind(declaredType);
                int slot = codeBuilder.allocateLocal(kind);
                emitExpression(call.arguments().get(i));
                adaptGenericArgument(parameter.type(), declaredType);
                storeLocal(kind, slot);
                parameterSlots[ordinal] = slot;
            }
        }

        if (receiverSlot != null) {
            loadLocal(receiverKind, receiverSlot);
        }
        for (int i = 0; i < parameterSlots.length; i++) {
            if (parameterSlots[i] == -1) {
                throw new IllegalStateException("call argument missing for parameter " + i
                        + " of " + function.qualifiedName());
            }
            java.lang.classfile.TypeKind kind = isRefOrOutParameter(parameters.get(i))
                    ? java.lang.classfile.TypeKind.REFERENCE
                    : toTypeKind(declaration.parameters().get(i).type());
            loadLocal(kind, parameterSlots[i]);
        }

        emitInvocation(function, call);
        // A ref/out call crosses the erasure boundary in its *result* as well, and this path
        // never adapted it: `int head = Pick(ref value)` left the erased `object` on the stack
        // for an `int` context. The copy-backs below are stack-neutral, so the narrowing
        // happens first, exactly where the ordinary call path puts it.
        adaptGenericReturn(call);

        copyBacks.sort(java.util.Comparator.comparingInt(RefOutCopyBack::ordinal));
        for (RefOutCopyBack copyBack : copyBacks) {
            emitRefOutCopyBack(copyBack);
        }
    }

    /// Emits the cell for one `ref`/`out` argument and records how to copy it back. The
    /// supported place shapes are: pass-through of another `ref`/`out` parameter, a local or
    /// plain parameter, a static field, an instance field (receiver spilled once), and a
    /// single-index array element (receiver and index spilled once).
    private void emitRefOutArgument(IrExpression argument, ParameterSymbol parameter,
            int ordinal, TypeSymbol elementType, TypeSymbol cellType, int cellSlot,
            List<RefOutCopyBack> copyBacks) {
        boolean refParameter = parameter.modifiers().contains(vsharp.compiler.syntax.SyntaxKind.REF);
        if (argument instanceof IrExpression.Load load && isCellBacked(load.symbol())) {
            // A ref/out parameter and a closure variable already are cells. Forward the
            // existing cell so an explicit `ref x` argument and an implicit capture of x
            // alias exactly as they do in C#. An `out T x` declaration expression has no
            // earlier statement at which to allocate its slot, so allocate its capture cell
            // here before forwarding it.
            int existingCell = allocateSymbolSlot(load.symbol(), elementType);
            if (forwardableCell(elementType, cellType)) {
                loadLocal(java.lang.classfile.TypeKind.REFERENCE, existingCell);
                storeLocal(java.lang.classfile.TypeKind.REFERENCE, cellSlot);
                return;
            }
            // An `[I` cell cannot be forwarded where `[Ljava/lang/Object;` is required, so the
            // value travels through a cell of the required type and is written back into the
            // original one afterwards. That is the copy-in/copy-out shape every other place
            // here already uses, applied to a cell rather than to a variable.
            emitNewArrayCell(cellType);
            storeLocal(java.lang.classfile.TypeKind.REFERENCE, cellSlot);
            if (refParameter) {
                codeBuilder.aload(cellSlot);
                codeBuilder.ldc(0);
                loadLocal(java.lang.classfile.TypeKind.REFERENCE, existingCell);
                codeBuilder.ldc(0);
                emitArrayLoad(toTypeKind(elementType));
                adaptCellValue(elementType, cellType);
                emitArrayStore(toTypeKind(cellType));
            }
            copyBacks.add(new RefOutCopyBack(ordinal, cellSlot, cellType, elementType,
                    new CellPlace(existingCell)));
            return;
        }
        if (argument instanceof IrExpression.Load load
                && load.symbol() instanceof FieldSymbol field
                && field.modifiers().contains(vsharp.compiler.syntax.SyntaxKind.STATIC)) {
            emitNewArrayCell(cellType);
            storeLocal(java.lang.classfile.TypeKind.REFERENCE, cellSlot);
            if (refParameter) {
                codeBuilder.aload(cellSlot);
                codeBuilder.ldc(0);
                ClassDesc owner = declaringClassOf(field);
                codeBuilder.getstatic(owner, field.name(), toClassDesc(field.type()));
                adaptCellValue(elementType, cellType);
                emitArrayStore(toTypeKind(cellType));
            }
            copyBacks.add(new RefOutCopyBack(ordinal, cellSlot, cellType, elementType,
                    new StaticFieldPlace(field)));
            return;
        }
        if (argument instanceof IrExpression.FieldLoad fieldLoad) {
            FieldSymbol field = (FieldSymbol) fieldLoad.field();
            java.lang.classfile.TypeKind receiverKind =
                    toTypeKind(fieldLoad.receiver().type().sourceType());
            int fieldReceiverSlot = codeBuilder.allocateLocal(receiverKind);
            emitExpression(fieldLoad.receiver());
            storeLocal(receiverKind, fieldReceiverSlot);

            emitNewArrayCell(cellType);
            storeLocal(java.lang.classfile.TypeKind.REFERENCE, cellSlot);
            if (refParameter) {
                codeBuilder.aload(cellSlot);
                codeBuilder.ldc(0);
                loadLocal(receiverKind, fieldReceiverSlot);
                ClassDesc owner = declaringClassOf(field);
                codeBuilder.getfield(owner, field.name(), toClassDesc(field.type()));
                adaptCellValue(elementType, cellType);
                emitArrayStore(toTypeKind(cellType));
            }
            copyBacks.add(new RefOutCopyBack(ordinal, cellSlot, cellType, elementType,
                    new InstanceFieldPlace(field, fieldReceiverSlot, receiverKind)));
            return;
        }
        if (argument instanceof IrExpression.ElementLoad elementLoad) {
            int receiverSlot = codeBuilder.allocateLocal(java.lang.classfile.TypeKind.REFERENCE);
            emitExpression(elementLoad.receiver());
            storeLocal(java.lang.classfile.TypeKind.REFERENCE, receiverSlot);
            int[] indexSlots = new int[elementLoad.indices().size()];
            for (int j = 0; j < indexSlots.length; j++) {
                indexSlots[j] = codeBuilder.allocateLocal(java.lang.classfile.TypeKind.INT);
                emitExpression(elementLoad.indices().get(j));
                storeLocal(java.lang.classfile.TypeKind.INT, indexSlots[j]);
            }

            emitNewArrayCell(cellType);
            storeLocal(java.lang.classfile.TypeKind.REFERENCE, cellSlot);
            if (refParameter) {
                codeBuilder.aload(cellSlot);
                codeBuilder.ldc(0);
                loadLocal(java.lang.classfile.TypeKind.REFERENCE, receiverSlot);
                for (int j = 0; j < indexSlots.length - 1; j++) {
                    codeBuilder.iload(indexSlots[j]);
                    codeBuilder.aaload();
                }
                codeBuilder.iload(indexSlots[indexSlots.length - 1]);
                emitArrayLoad(toTypeKind(elementType));
                adaptCellValue(elementType, cellType);
                emitArrayStore(toTypeKind(cellType));
            }
            copyBacks.add(new RefOutCopyBack(ordinal, cellSlot, cellType, elementType,
                    new ElementPlace(receiverSlot, indexSlots)));
            return;
        }
        if (argument instanceof IrExpression.Load load) {
            // A declaration expression (`M(out int value)`) first appears as this argument;
            // unlike an ordinary local declaration it has no preceding IrStatement.Locals.
            // Ensure copy-back has a destination slot. For an existing local this is a no-op.
            allocateSymbolSlot(load.symbol(), elementType);
            emitNewArrayCell(cellType);
            storeLocal(java.lang.classfile.TypeKind.REFERENCE, cellSlot);
            if (refParameter) {
                codeBuilder.aload(cellSlot);
                codeBuilder.ldc(0);
                emitExpression(argument);
                adaptCellValue(elementType, cellType);
                emitArrayStore(toTypeKind(cellType));
            }
            copyBacks.add(new RefOutCopyBack(ordinal, cellSlot, cellType, elementType,
                    new LocalPlace(load.symbol())));
            return;
        }
        throw new UnsupportedOperationException(
                "CodeEmitter only emits ref/out arguments that are locals, parameters, static/instance fields, single-index elements or ref/out parameter pass-throughs, got: "
                        + argument.getClass().getSimpleName());
    }

    /// Copies a cell back to the place that supplied it, using the place's own store shape.
    private void emitRefOutCopyBack(RefOutCopyBack copyBack) {
        copyBack.place().copyBack(this, copyBack.cellSlot(), copyBack.cellType(),
                copyBack.elementType());
    }

    /// Whether a cell already holding `elementType` can be handed to a parameter whose cell is
    /// declared over `cellType`.
    ///
    /// The same type always can. Otherwise the JVM's own array rule decides: `[Ljava/lang/String;`
    /// is an `[Ljava/lang/Object;` by array covariance, so a `ref string` cell forwards into a
    /// `ref T` parameter unchanged, while `[I` is not and has to be copied through one of the
    /// required type.
    private static boolean forwardableCell(TypeSymbol elementType, TypeSymbol cellType) {
        return cellElementClassDesc(elementType).equals(cellElementClassDesc(cellType))
                || (toTypeKind(elementType) == java.lang.classfile.TypeKind.REFERENCE
                        && toTypeKind(cellType) == java.lang.classfile.TypeKind.REFERENCE);
    }

    /// Adapts a value between a cell's element type and the place's own type, in either
    /// direction, emitting nothing when the two agree.
    ///
    /// This is the same box/unbox/`checkcast` adaptation a generic *call* boundary already
    /// performs; a `ref T` cell is that boundary turned inside out, since the value crosses it
    /// once on the way in and once on the way back.
    private void adaptCellValue(TypeSymbol from, TypeSymbol to) {
        java.lang.classfile.TypeKind fromKind = toTypeKind(from);
        java.lang.classfile.TypeKind toKind = toTypeKind(to);
        if (fromKind == toKind && cellElementClassDesc(from).equals(cellElementClassDesc(to))) {
            return;
        }
        if (toKind == java.lang.classfile.TypeKind.REFERENCE) {
            if (fromKind != java.lang.classfile.TypeKind.REFERENCE) {
                emitBox(from);
                return;
            }
            ClassDesc target = cellElementClassDesc(to);
            if (!target.equals(ConstantDescs.CD_Object)) {
                codeBuilder.checkcast(target);
            }
            return;
        }
        emitUnboxObject(to);
    }

    private void emitNewArrayCell(TypeSymbol elementType) {
        codeBuilder.ldc(1);
        java.lang.classfile.TypeKind kind = toTypeKind(elementType);
        if (kind == java.lang.classfile.TypeKind.REFERENCE) {
            codeBuilder.anewarray(cellElementClassDesc(elementType));
        } else {
            codeBuilder.newarray(kind);
        }
    }

    private void emitInvocation(FunctionSymbol function, IrExpression.Call call) {
        FunctionSymbol declaration = call.declaration();
        emitCaptureArguments(declaration);

        String qualifiedName = declaration.qualifiedName();
        if (isExtern(function)) {
            emitRuntimeCall(function, call);
        } else if (isStaticMethod(declaration, captureMap)) {
            codeBuilder.invokestatic(callableContainer(qualifiedName),
                    emittedMethodName(declaration), buildMethodTypeDesc(declaration, captureMap));
        } else {
            if (call.receiver() == null) {
                if (!declaration.localFunction()) {
                    throw new IllegalStateException(
                            "instance call has no receiver: " + function.qualifiedName());
                }
                codeBuilder.invokevirtual(callableContainer(qualifiedName),
                        emittedMethodName(declaration),
                        buildMethodTypeDesc(declaration, captureMap));
                return;
            }
            emitInstanceInvoke(call.receiver().type().sourceType(), declaration, captureMap);
        }
    }

    private void emitCaptureArguments(FunctionSymbol declaration) {
        List<IrCapture> captures = captureMap.get(declaration);
        if (captures == null) {
            return;
        }
        for (IrCapture capture : captures) {
            Integer slot = localSlots.get(capture.symbol());
            if (slot == null) {
                throw new IllegalStateException("No cell for captured variable "
                        + capture.symbol().qualifiedName() + " while calling "
                        + declaration.qualifiedName());
            }
            codeBuilder.aload(slot);
        }
    }

    /// Copies one cell back to the place that supplied it. `cellType` is what the cell holds -
    /// the declaration's, possibly erased, element type - and `elementType` is what the place
    /// itself stores, so every implementation loads with the first, adapts, and stores with the
    /// second. The two are equal for every non-generic call.
    private sealed interface RefOutPlace {
        void copyBack(CodeEmitter emitter, int cellSlot, TypeSymbol cellType,
                TypeSymbol elementType);
    }

    private record LocalPlace(Symbol symbol) implements RefOutPlace {
        @Override
        public void copyBack(CodeEmitter emitter, int cellSlot, TypeSymbol cellType,
                TypeSymbol elementType) {
            emitter.codeBuilder.aload(cellSlot);
            emitter.codeBuilder.ldc(0);
            emitter.emitArrayLoad(CodeEmitter.toTypeKind(cellType));
            emitter.adaptCellValue(cellType, elementType);
            emitter.storeSymbolValue(symbol, elementType, false);
        }
    }

    private record StaticFieldPlace(FieldSymbol field) implements RefOutPlace {
        @Override
        public void copyBack(CodeEmitter emitter, int cellSlot, TypeSymbol cellType,
                TypeSymbol elementType) {
            emitter.codeBuilder.aload(cellSlot);
            emitter.codeBuilder.ldc(0);
            emitter.emitArrayLoad(CodeEmitter.toTypeKind(cellType));
            emitter.adaptCellValue(cellType, elementType);
            emitter.codeBuilder.putstatic(CodeEmitter.declaringClassOf(field), field.name(),
                    CodeEmitter.toClassDesc(field.type()));
        }
    }

    private record InstanceFieldPlace(FieldSymbol field, int receiverSlot,
            java.lang.classfile.TypeKind receiverKind) implements RefOutPlace {
        @Override
        public void copyBack(CodeEmitter emitter, int cellSlot, TypeSymbol cellType,
                TypeSymbol elementType) {
            emitter.loadLocal(receiverKind, receiverSlot);
            emitter.codeBuilder.aload(cellSlot);
            emitter.codeBuilder.ldc(0);
            emitter.emitArrayLoad(CodeEmitter.toTypeKind(cellType));
            emitter.adaptCellValue(cellType, elementType);
            emitter.codeBuilder.putfield(CodeEmitter.declaringClassOf(field), field.name(),
                    CodeEmitter.toClassDesc(field.type()));
        }
    }

    private record ElementPlace(int receiverSlot, int[] indexSlots) implements RefOutPlace {
        @Override
        public void copyBack(CodeEmitter emitter, int cellSlot, TypeSymbol cellType,
                TypeSymbol elementType) {
            emitter.loadLocal(java.lang.classfile.TypeKind.REFERENCE, receiverSlot);
            for (int i = 0; i < indexSlots.length - 1; i++) {
                emitter.codeBuilder.iload(indexSlots[i]);
                emitter.codeBuilder.aaload();
            }
            emitter.codeBuilder.iload(indexSlots[indexSlots.length - 1]);
            emitter.codeBuilder.aload(cellSlot);
            emitter.codeBuilder.ldc(0);
            emitter.emitArrayLoad(CodeEmitter.toTypeKind(cellType));
            emitter.adaptCellValue(cellType, elementType);
            emitter.emitArrayStore(CodeEmitter.toTypeKind(elementType));
        }
    }

    /// The place for a cell that could not be forwarded directly: the value travelled through a
    /// cell of the declaration's type and is written back into the caller's own.
    private record CellPlace(int existingCellSlot) implements RefOutPlace {
        @Override
        public void copyBack(CodeEmitter emitter, int cellSlot, TypeSymbol cellType,
                TypeSymbol elementType) {
            emitter.loadLocal(java.lang.classfile.TypeKind.REFERENCE, existingCellSlot);
            emitter.codeBuilder.ldc(0);
            emitter.codeBuilder.aload(cellSlot);
            emitter.codeBuilder.ldc(0);
            emitter.emitArrayLoad(CodeEmitter.toTypeKind(cellType));
            emitter.adaptCellValue(cellType, elementType);
            emitter.emitArrayStore(CodeEmitter.toTypeKind(elementType));
        }
    }

    private record RefOutCopyBack(int ordinal, int cellSlot, TypeSymbol cellType,
            TypeSymbol elementType, RefOutPlace place) {}

    /// Emits the call to the runtime method backing an `extern` corelib member.
    ///
    /// The member has no body of its own, so the only thing that can be emitted is the mapping
    /// in [RuntimeLibrary]. A member the table does not know is a compiler defect, not a user
    /// error: corelib is compiler-controlled input, so declaring a member without mapping it
    /// fails loudly here rather than emitting a call to a method nobody wrote.
    private void emitRuntimeCall(FunctionSymbol function, IrExpression.Call call) {
        RuntimeLibrary.Target target = RuntimeLibrary.lookup(function);
        if (target == null) {
            throw new UnsupportedOperationException(
                    "CodeEmitter has no runtime mapping for extern member "
                            + RuntimeLibrary.signature(function));
        }
        if (target.shape() == RuntimeLibrary.ArgumentShape.STRING_START_LENGTH) {
            // C# takes (start, length); java.lang.String takes (start, end).
            codeBuilder.dup2();
            codeBuilder.pop();
            codeBuilder.iadd();
        }
        if (target.invocation().needsReceiver() && call.receiver() == null) {
            throw new IllegalStateException(
                    "instance call has no receiver: " + function.qualifiedName());
        }
        // A `RECEIVER_STATIC` target already has the receiver stacked below its arguments, in
        // the position the static method declares it, so only the opcode differs.
        if (target.invocation() == RuntimeLibrary.Invocation.VIRTUAL) {
            codeBuilder.invokevirtual(target.owner(), target.name(), target.desc());
        } else {
            codeBuilder.invokestatic(target.owner(), target.name(), target.desc());
        }
    }

    /// Whether a callable is declared `extern`, i.e. implemented by the runtime library.
    static boolean isExtern(FunctionSymbol symbol) {
        return symbol.modifiers().contains(vsharp.compiler.syntax.SyntaxKind.EXTERN);
    }

    /// Whether a symbol is a parameter passed by explicit cell at V# boundaries (`ref`/`out`).
    /// `in` stays by-value because it is readonly and its by-value emission has no known
    /// divergence for the subset; revisit when the cell model is generalised.
    static boolean isRefOrOutParameter(Symbol symbol) {
        return symbol instanceof ParameterSymbol parameter
                && (parameter.modifiers().contains(vsharp.compiler.syntax.SyntaxKind.REF)
                        || parameter.modifiers().contains(vsharp.compiler.syntax.SyntaxKind.OUT));
    }

    private void loadLocal(java.lang.classfile.TypeKind typeKind, int slot) {
        switch (typeKind) {
            case INT, BOOLEAN, BYTE, SHORT, CHAR -> codeBuilder.iload(slot);
            case LONG -> codeBuilder.lload(slot);
            case FLOAT -> codeBuilder.fload(slot);
            case DOUBLE -> codeBuilder.dload(slot);
            case REFERENCE -> codeBuilder.aload(slot);
            default -> throw new UnsupportedOperationException("Unsupported load type: " + typeKind);
        }
    }

    private void storeLocal(java.lang.classfile.TypeKind typeKind, int slot) {
        switch (typeKind) {
            case INT, BOOLEAN, BYTE, SHORT, CHAR -> codeBuilder.istore(slot);
            case LONG -> codeBuilder.lstore(slot);
            case FLOAT -> codeBuilder.fstore(slot);
            case DOUBLE -> codeBuilder.dstore(slot);
            case REFERENCE -> codeBuilder.astore(slot);
            default -> throw new UnsupportedOperationException("Unsupported store type: " + typeKind);
        }
    }

    /// Loads `cell[0]`, where `cell` is the one-element array a `ref`/`out` parameter is
    /// passed as at V# boundaries.
    private void emitCellLoad(TypeSymbol elementType, int cellSlot) {
        codeBuilder.aload(cellSlot);
        codeBuilder.ldc(0);
        emitArrayLoad(toTypeKind(elementType));
    }

    /// Emits the JVM's `i2l`/`d2i`/etc. primitive conversion family for `IMPLICIT_NUMERIC`
    /// and `EXPLICIT_NUMERIC` conversions between the four JVM numeric carriers this backend
    /// currently supports (INT/LONG/FLOAT/DOUBLE). Nullable conversion is a structured
    /// box/null-test/unbox operation under that rule and dispatches to its own helper below.
    private void emitNumericConversion(Conversion conversion, TypeSymbol from, TypeSymbol to) {
        if (conversion.isIdentity() || from == to) {
            return; // Already the right JVM carrier; no instruction to emit.
        }
        if (conversion.kind() == ConversionKind.IMPLICIT_NULL
                && toTypeKind(to) == java.lang.classfile.TypeKind.REFERENCE) {
            // `null` is already the representation every reference carrier uses. The
            // conversion is a type-system fact - the value is now typed as the target - and
            // carries no instruction, so `Object o = null` and `java.lang.Integer i = null`
            // differ from `string s = null` only in the descriptor written elsewhere.
            return;
        }
        if (conversion.kind() == ConversionKind.IMPLICIT_NULLABLE
                || conversion.kind() == ConversionKind.EXPLICIT_NULLABLE) {
            emitNullableConversion(from, to);
            return;
        }
        if (conversion.kind() == ConversionKind.IMPLICIT_REFERENCE) {
            return;
        }
        if (conversion.kind() == ConversionKind.EXPLICIT_REFERENCE) {
            codeBuilder.checkcast(toClassDesc(to));
            return;
        }
        if (conversion.kind() == ConversionKind.IMPLICIT_TUPLE
                || conversion.kind() == ConversionKind.EXPLICIT_TUPLE) {
            emitTupleConversion(from, to);
            return;
        }
        if (conversion.kind() != ConversionKind.IMPLICIT_NUMERIC
                && conversion.kind() != ConversionKind.EXPLICIT_NUMERIC
                && conversion.kind() != ConversionKind.IMPLICIT_CONSTANT
                && conversion.kind() != ConversionKind.EXPLICIT_ENUM
                && conversion.kind() != ConversionKind.BOXING
                && conversion.kind() != ConversionKind.UNBOXING) {
            throw new UnsupportedOperationException("CodeEmitter does not yet emit conversion kind: " + conversion.kind());
        }
        if (conversion.kind() == ConversionKind.BOXING) {
            // A nullable value is already null or a boxed T, so boxing T? to object is a
            // representation identity. Ordinary value types still need a wrapper call.
            if (!(from instanceof TypeSymbol.Nullable)) {
                emitBox(from);
            }
            return;
        }
        if (conversion.kind() == ConversionKind.UNBOXING) {
            emitUnboxObject(to);
            return;
        }
        emitNumericOpcode(from, to);
    }

    /// Converts a tuple to another tuple of the same arity, after the operand was pushed.
    ///
    /// Element *names* are compile-time only, in V# as in C#, so a conversion that only
    /// renames elements - `(int, string)` to `(int Count, string Word)`, which is what an
    /// ordinary named-tuple declaration produces - is a representation identity and emits
    /// nothing. When an element type actually changes, the carrier does too: `VsTupleN`
    /// stores erased `Object`s, so `(int, int)` to `(long, long)` holds boxed `Integer`s
    /// that must become boxed `Long`s. That case rebuilds the tuple element by element
    /// rather than reinterpreting it.
    private void emitTupleConversion(TypeSymbol from, TypeSymbol to) {
        if (!(from instanceof TypeSymbol.Tuple source)
                || !(to instanceof TypeSymbol.Tuple target)
                || source.elements().size() != target.elements().size()) {
            throw new UnsupportedOperationException(
                    "CodeEmitter cannot convert " + from + " to " + to + " as a tuple");
        }
        int arity = source.elements().size();
        boolean sameElements = true;
        for (int index = 0; index < arity; index++) {
            if (!Objects.equals(source.elements().get(index).type(),
                    target.elements().get(index).type())) {
                sameElements = false;
                break;
            }
        }
        if (sameElements) {
            return;
        }

        ClassDesc tupleClass = ClassDesc.of("vsharp.runtime.VsTuple" + arity);
        int slot = codeBuilder.allocateLocal(java.lang.classfile.TypeKind.REFERENCE);
        codeBuilder.checkcast(tupleClass);
        codeBuilder.astore(slot);
        codeBuilder.new_(tupleClass);
        codeBuilder.dup();
        List<ClassDesc> parameters = new ArrayList<>(arity);
        for (int index = 0; index < arity; index++) {
            TypeSymbol sourceElement = source.elements().get(index).type();
            TypeSymbol targetElement = target.elements().get(index).type();
            codeBuilder.aload(slot);
            codeBuilder.invokevirtual(tupleClass, "item" + (index + 1),
                    MethodTypeDesc.of(ConstantDescs.CD_Object));
            if (!Objects.equals(sourceElement, targetElement)) {
                if (toTypeKind(sourceElement) != java.lang.classfile.TypeKind.REFERENCE) {
                    emitUnboxNullableElement(sourceElement);
                } else {
                    codeBuilder.checkcast(toClassDesc(sourceElement));
                }
                emitUnderlyingConversion(sourceElement, targetElement);
                if (toTypeKind(targetElement) != java.lang.classfile.TypeKind.REFERENCE) {
                    emitBox(targetElement);
                }
            }
            parameters.add(ConstantDescs.CD_Object);
        }
        codeBuilder.invokespecial(tupleClass, "<init>",
                MethodTypeDesc.of(ConstantDescs.CD_void, parameters));
    }

    /// Emits a nullable conversion after the operand has already been pushed.
    private void emitNullableConversion(TypeSymbol from, TypeSymbol to) {
        if (to instanceof TypeSymbol.Nullable target) {
            if (from instanceof TypeSymbol.Nullable source) {
                Label noValue = codeBuilder.newLabel();
                Label end = codeBuilder.newLabel();
                codeBuilder.dup();
                codeBuilder.ifnull(noValue);
                emitUnboxNullableElement(source.element());
                emitUnderlyingConversion(source.element(), target.element());
                emitBox(target.element());
                codeBuilder.goto_(end);
                codeBuilder.labelBinding(noValue);
                // The original null remains on the stack on this edge.
                codeBuilder.labelBinding(end);
                return;
            }
            if (from == BuiltinType.OBJECT) {
                // `(T?)o` unboxes a boxed T, but a null `object` becomes the null nullable
                // state rather than throwing. The duplicated null stays on the stack along
                // the null edge; the other edge unboxes then re-boxes the value.
                Label noValue = codeBuilder.newLabel();
                Label end = codeBuilder.newLabel();
                codeBuilder.dup();
                codeBuilder.ifnull(noValue);
                emitUnderlyingConversion(BuiltinType.OBJECT, target.element());
                emitBox(target.element());
                codeBuilder.goto_(end);
                codeBuilder.labelBinding(noValue);
                codeBuilder.labelBinding(end);
                return;
            }
            emitUnderlyingConversion(from, target.element());
            emitBox(target.element());
            return;
        }
        if (from instanceof TypeSymbol.Nullable source) {
            emitRequireNullableValue(source.element());
            emitUnderlyingConversion(source.element(), to);
            return;
        }
        throw new UnsupportedOperationException("Invalid nullable conversion from "
                + from.displayName() + " to " + to.displayName());
    }

    private void emitUnderlyingConversion(TypeSymbol from, TypeSymbol to) {
        Conversion conversion = Conversions.classify(from, to);
        if (conversion.isIdentity()) {
            return;
        }
        if (conversion.kind() == ConversionKind.IMPLICIT_NUMERIC
                || conversion.kind() == ConversionKind.EXPLICIT_NUMERIC
                || conversion.kind() == ConversionKind.IMPLICIT_CONSTANT
                || conversion.kind() == ConversionKind.EXPLICIT_ENUM) {
            emitNumericOpcode(from, to);
            return;
        }
        if (conversion.kind() == ConversionKind.UNBOXING) {
            emitUnboxObject(to);
            return;
        }
        if (conversion.kind() == ConversionKind.IMPLICIT_TUPLE
                || conversion.kind() == ConversionKind.EXPLICIT_TUPLE) {
            emitTupleConversion(from, to);
            return;
        }
        // A widening reference conversion is a no-op on the JVM: the value already *is* an
        // instance of the target, so the verifier needs nothing emitted. A narrowing one
        // needs the cast that makes it checked, which is the same `checkcast` an ordinary
        // downcast emits. Reaching here at all was a defect - returning
        // `(ArrayList<T>, int)` where the tuple declares `(List<T>, int)` is an ordinary
        // reference conversion, and the emitter refused it as unsupported.
        if (conversion.kind() == ConversionKind.IMPLICIT_REFERENCE) {
            return;
        }
        if (conversion.kind() == ConversionKind.EXPLICIT_REFERENCE) {
            codeBuilder.checkcast(toClassDesc(to));
            return;
        }
        if (conversion.kind() == ConversionKind.BOXING) {
            // `emitBox` boxes the value type on the stack, which is the *source* here: an
            // `int` becoming `object` boxes the int, not the target.
            emitBox(from);
            return;
        }
        throw new UnsupportedOperationException("CodeEmitter cannot convert nullable element "
                + from.displayName() + " to " + to.displayName());
    }

    /// The shared `i2l`/`d2i`/etc. lookup used after conversion binding has selected a
    /// numeric source and target and once identity has already been ruled out.
    ///
    /// `sbyte`/`byte`/`short`/`ushort`/`char` have no arithmetic carrier of their own on the
    /// JVM: they occupy an int stack slot, so a conversion between them and `int` is a
    /// question of *value*, not of instruction family. Widening therefore emits nothing for
    /// the types the JVM already holds in the C# range (`sbyte`/`short` are sign-extended,
    /// `char` zero-extended, exactly as C# defines them) but must zero-extend `byte` and
    /// `ushort` by hand: V# stores those unsigned types in the *signed* JVM `byte`/`short`
    /// carriers, so `byte` 200 sits in the slot as -56 and reaches its C# value only by
    /// masking off the sign extension here. Narrowing to any small type is the plain
    /// `i2b`/`i2s`/`i2c` truncation C# specifies in an `unchecked` context.
    private void emitNumericOpcode(TypeSymbol from, TypeSymbol to) {
        if (from == BuiltinType.DECIMAL || to == BuiltinType.DECIMAL) {
            emitDecimalConversion(from, to);
            return;
        }
        if (checkedContext && emitCheckedNumericConversion(from, to)) {
            return;
        }
        emitZeroExtension(from);
        java.lang.classfile.TypeKind fromCarrier = numericCarrier(toTypeKind(from));
        java.lang.classfile.TypeKind toCarrier = numericCarrier(toTypeKind(to));
        if (fromCarrier != toCarrier && emitUnsignedCarrierConversion(from, to, toCarrier)) {
            fromCarrier = toCarrier;
        }
        if (fromCarrier != toCarrier) {
            emitCarrierOpcode(fromCarrier, toCarrier);
        }
        switch (toTypeKind(to)) {
            case BYTE -> codeBuilder.i2b();
            case SHORT -> codeBuilder.i2s();
            case CHAR -> codeBuilder.i2c();
            default -> {
                // The target already owns the carrier the value is now held in.
            }
        }
    }

    /// The C# range of an integral type, or `null` for a type that has none (the floating
    /// point types, `decimal`, and every non-numeric type).
    ///
    /// `ulong` and `nuint` cannot state their maximum as a `long`, so they carry a flag
    /// instead of a bound and are compared unsigned wherever they appear.
    private record IntegralRange(long min, long max, boolean unsignedCarrier) {

        /// Whether every value of `source` is a value of this type, which is exactly when a
        /// conversion between them cannot overflow and needs no test in a `checked` context.
        boolean contains(IntegralRange source) {
            if (source.unsignedCarrier) {
                return unsignedCarrier;
            }
            if (unsignedCarrier) {
                return source.min >= 0;
            }
            return min <= source.min && max >= source.max;
        }
    }

    private static IntegralRange integralRange(TypeSymbol type) {
        if (!(type instanceof BuiltinType builtin)) {
            return null;
        }
        return switch (builtin) {
            case SBYTE -> new IntegralRange(Byte.MIN_VALUE, Byte.MAX_VALUE, false);
            case BYTE -> new IntegralRange(0, 255, false);
            case SHORT -> new IntegralRange(Short.MIN_VALUE, Short.MAX_VALUE, false);
            case USHORT -> new IntegralRange(0, 65535, false);
            case CHAR -> new IntegralRange(0, 65535, false);
            case INT -> new IntegralRange(Integer.MIN_VALUE, Integer.MAX_VALUE, false);
            case UINT -> new IntegralRange(0, 4294967295L, false);
            case LONG, NINT -> new IntegralRange(Long.MIN_VALUE, Long.MAX_VALUE, false);
            case ULONG, NUINT -> new IntegralRange(0, -1, true);
            default -> null;
        };
    }

    /// Emits an explicit numeric conversion in its trapping form, and reports whether it did.
    /// A `false` answer leaves the operand untouched for the wrapping path to convert.
    ///
    /// C# §11.7.18 makes `checked` govern conversions as well as arithmetic: a narrowing that
    /// would truncate throws `System.OverflowException` instead. Widenings are silent, so the
    /// range table answers first and most conversions still cost nothing.
    ///
    /// The test itself always runs at 64 bits. An integral source is widened to a `long`
    /// holding its *value* - which for `uint` is the mask, not `i2l`'s sign extension - and
    /// bounded against the target's C# range by [vsharp.runtime.VsChecked]; `ulong`/`nuint`
    /// sources are compared unsigned because their value has no `long` spelling. A floating
    /// source keeps its own comparison, since C# truncates toward zero before testing and
    /// rejects both infinities and NaN. Once the value is known to be in range the narrowing
    /// back to the target's carrier is exact, so no `i2b`/`i2s`/`i2c` mask follows it.
    private boolean emitCheckedNumericConversion(TypeSymbol from, TypeSymbol to) {
        IntegralRange target = integralRange(to);
        if (target == null) {
            return false;
        }
        IntegralRange source = integralRange(from);
        if (source != null && target.contains(source)) {
            return false;
        }
        java.lang.classfile.TypeKind fromCarrier = numericCarrier(toTypeKind(from));
        if (source == null) {
            if (fromCarrier == java.lang.classfile.TypeKind.FLOAT) {
                codeBuilder.f2d();
            } else if (fromCarrier != java.lang.classfile.TypeKind.DOUBLE) {
                return false;
            }
            if (target.unsignedCarrier()) {
                codeBuilder.invokestatic(CD_VS_CHECKED, "toUnsignedFromDouble",
                        MethodTypeDesc.of(ConstantDescs.CD_long, ConstantDescs.CD_double));
            } else {
                codeBuilder.ldc(target.min());
                codeBuilder.ldc(target.max());
                codeBuilder.invokestatic(CD_VS_CHECKED, "fromDouble", MethodTypeDesc.of(
                        ConstantDescs.CD_long, ConstantDescs.CD_double,
                        ConstantDescs.CD_long, ConstantDescs.CD_long));
            }
        } else {
            emitZeroExtension(from);
            if (fromCarrier == java.lang.classfile.TypeKind.INT) {
                codeBuilder.i2l();
                if (from == BuiltinType.UINT) {
                    codeBuilder.ldc(0xFFFF_FFFFL);
                    codeBuilder.land();
                }
            }
            if (source.unsignedCarrier()) {
                codeBuilder.ldc(target.unsignedCarrier() ? -1L : target.max());
                codeBuilder.invokestatic(CD_VS_CHECKED, "unsignedRange", MethodTypeDesc.of(
                        ConstantDescs.CD_long, ConstantDescs.CD_long, ConstantDescs.CD_long));
            } else {
                // An unsigned 64-bit target has no upper bound a signed source can exceed,
                // so only its floor is tested.
                codeBuilder.ldc(target.unsignedCarrier() ? 0L : target.min());
                codeBuilder.ldc(target.unsignedCarrier() ? Long.MAX_VALUE : target.max());
                codeBuilder.invokestatic(CD_VS_CHECKED, "range", MethodTypeDesc.of(
                        ConstantDescs.CD_long, ConstantDescs.CD_long,
                        ConstantDescs.CD_long, ConstantDescs.CD_long));
            }
        }
        if (numericCarrier(toTypeKind(to)) == java.lang.classfile.TypeKind.INT) {
            codeBuilder.l2i();
        }
        return true;
    }

    /// Converts between carriers when either end is one of the unsigned types V# keeps in a
    /// *signed* JVM carrier (`uint` in `int`, `ulong`/`nuint` in `long`), and reports whether
    /// it did.
    ///
    /// The plain `i2l`/`l2d`/`d2l` opcodes all read and write the carrier as signed, so they
    /// answer the wrong question here: `i2l` on `uint` 4294967295 gives `-1`, `l2d` on
    /// `ulong` 2^64-1 gives `-1.0`, and `d2l` saturates at `long`'s maximum instead of
    /// wrapping the way an `unchecked` C# conversion does. Widening `uint` is a mask, which
    /// stays in-line; the four floating-point directions over 64 unsigned bits have no
    /// opcode at all and become calls into [vsharp.runtime.VsUnsigned].
    private boolean emitUnsignedCarrierConversion(
            TypeSymbol from, TypeSymbol to, java.lang.classfile.TypeKind toCarrier) {
        if (from == BuiltinType.UINT) {
            codeBuilder.i2l();
            codeBuilder.ldc(0xFFFF_FFFFL);
            codeBuilder.land();
            switch (toCarrier) {
                case LONG -> {
                    // The masked value is already the target carrier.
                }
                case FLOAT -> codeBuilder.l2f();
                case DOUBLE -> codeBuilder.l2d();
                default -> throw unsupportedNumericConversion(
                        java.lang.classfile.TypeKind.INT, toCarrier);
            }
            return true;
        }
        if (from == BuiltinType.ULONG || from == BuiltinType.NUINT) {
            switch (toCarrier) {
                case FLOAT -> invokeUnsigned("toFloat", ConstantDescs.CD_float, ConstantDescs.CD_long);
                case DOUBLE -> invokeUnsigned("toDouble", ConstantDescs.CD_double, ConstantDescs.CD_long);
                default -> {
                    return false; // Narrowing to `int` is the ordinary truncating `l2i`.
                }
            }
            return true;
        }
        if (to == BuiltinType.ULONG || to == BuiltinType.NUINT) {
            switch (numericCarrier(toTypeKind(from))) {
                case FLOAT -> invokeUnsigned("fromFloat", ConstantDescs.CD_long, ConstantDescs.CD_float);
                case DOUBLE -> invokeUnsigned("fromDouble", ConstantDescs.CD_long, ConstantDescs.CD_double);
                default -> {
                    return false; // `int` widens with the signed `i2l` C# also specifies.
                }
            }
            return true;
        }
        if (to == BuiltinType.UINT) {
            switch (numericCarrier(toTypeKind(from))) {
                // C# truncates modulo 2^32 in an `unchecked` context, so the value has to
                // reach `int` through `long`: `f2i`/`d2i` would saturate at 2^31 - 1 and lose
                // every result in the upper half of the `uint` range.
                case FLOAT -> {
                    codeBuilder.f2l();
                    codeBuilder.l2i();
                }
                case DOUBLE -> {
                    codeBuilder.d2l();
                    codeBuilder.l2i();
                }
                default -> {
                    return false; // `long` narrows with the plain `l2i` truncation.
                }
            }
            return true;
        }
        return false;
    }

    private void invokeUnsigned(String name, ClassDesc returnDesc, ClassDesc parameterDesc) {
        codeBuilder.invokestatic(CD_VS_UNSIGNED, name, MethodTypeDesc.of(returnDesc, parameterDesc));
    }

    /// Masks the sign extension the JVM applied when it loaded an unsigned V# type from its
    /// signed carrier, leaving the C# value on the stack as an int. `i2c` is the 16-bit
    /// form of the same mask, so `ushort` needs no constant.
    private void emitZeroExtension(TypeSymbol from) {
        if (from == BuiltinType.BYTE) {
            codeBuilder.ldc(0xFF);
            codeBuilder.iand();
        } else if (from == BuiltinType.USHORT) {
            codeBuilder.i2c();
        }
    }

    /// Emits a conversion between `decimal` and another numeric type through
    /// [vsharp.runtime.VsDecimal]. Enums are `int`-carried, so they use the `int`
    /// helpers; `nint`/`nuint` use the 64-bit helpers because V# carries them as `long` (R5).
    /// The small unsigned types (`byte`/`ushort`) reach this path as an `int` after
    /// [emitZeroExtension], so their C# value - not their signed carrier bits - is converted.
    private void emitDecimalConversion(TypeSymbol from, TypeSymbol to) {
        TypeSymbol decimalFrom = intIfEnum(from);
        TypeSymbol decimalTo = intIfEnum(to);
        if (decimalFrom == BuiltinType.DECIMAL) {
            ClassDesc result = decimalResultDesc(decimalTo);
            codeBuilder.invokestatic(CD_VS_DECIMAL, decimalToName(decimalTo),
                    MethodTypeDesc.of(result, CD_BIG_DECIMAL));
            return;
        }
        emitZeroExtension(decimalFrom);
        ClassDesc parameter = decimalParameterDesc(decimalFrom);
        codeBuilder.invokestatic(CD_VS_DECIMAL, decimalFromName(decimalFrom),
                MethodTypeDesc.of(CD_BIG_DECIMAL, parameter));
    }

    private static TypeSymbol intIfEnum(TypeSymbol type) {
        return type instanceof NamedTypeSymbol named
                && named.declaredKind() == NamedTypeSymbol.DeclaredKind.ENUM
                ? BuiltinType.INT : type;
    }

    private static String decimalFromName(TypeSymbol from) {
        return switch ((BuiltinType) from) {
            case SBYTE, BYTE, SHORT, USHORT, CHAR, INT -> "fromInt";
            case UINT -> "fromUInt";
            case LONG, NINT -> "fromLong";
            case ULONG, NUINT -> "fromULong";
            case FLOAT -> "fromFloat";
            case DOUBLE -> "fromDouble";
            case DECIMAL -> throw new UnsupportedOperationException(
                    "Identity decimal conversion must be handled before decimal lowering");
            default -> throw new UnsupportedOperationException(
                    "CodeEmitter cannot convert " + from.displayName() + " to decimal");
        };
    }

    private static ClassDesc decimalParameterDesc(TypeSymbol from) {
        return switch ((BuiltinType) from) {
            case SBYTE, BYTE, SHORT, USHORT, CHAR, INT, UINT -> ConstantDescs.CD_int;
            case LONG, NINT, ULONG, NUINT -> ConstantDescs.CD_long;
            case FLOAT -> ConstantDescs.CD_float;
            case DOUBLE -> ConstantDescs.CD_double;
            case DECIMAL -> CD_BIG_DECIMAL;
            default -> throw new UnsupportedOperationException(
                    "CodeEmitter cannot convert " + from.displayName() + " to decimal");
        };
    }

    private static String decimalToName(TypeSymbol to) {
        return switch ((BuiltinType) to) {
            case SBYTE -> "toSByte";
            case BYTE -> "toByte";
            case SHORT -> "toShort";
            case USHORT -> "toUShort";
            case CHAR -> "toChar";
            case INT -> "toInt";
            case UINT -> "toUInt";
            case LONG, NINT -> "toLong";
            case ULONG, NUINT -> "toULong";
            case FLOAT -> "toFloat";
            case DOUBLE -> "toDouble";
            case DECIMAL -> throw new UnsupportedOperationException(
                    "Identity decimal conversion must be handled before decimal lowering");
            default -> throw new UnsupportedOperationException(
                    "CodeEmitter cannot convert decimal to " + to.displayName());
        };
    }

    private static ClassDesc decimalResultDesc(TypeSymbol to) {
        return switch ((BuiltinType) to) {
            case SBYTE, BYTE, SHORT, USHORT, CHAR, INT, UINT -> ConstantDescs.CD_int;
            case LONG, NINT, ULONG, NUINT -> ConstantDescs.CD_long;
            case FLOAT -> ConstantDescs.CD_float;
            case DOUBLE -> ConstantDescs.CD_double;
            case DECIMAL -> CD_BIG_DECIMAL;
            default -> throw new UnsupportedOperationException(
                    "CodeEmitter cannot convert decimal to " + to.displayName());
        };
    }

    private void emitCarrierOpcode(java.lang.classfile.TypeKind from, java.lang.classfile.TypeKind to) {
        switch (from) {
            case INT -> {
                switch (to) {
                    case LONG -> codeBuilder.i2l();
                    case FLOAT -> codeBuilder.i2f();
                    case DOUBLE -> codeBuilder.i2d();
                    default -> throw unsupportedNumericConversion(from, to);
                }
            }
            case LONG -> {
                switch (to) {
                    case INT -> codeBuilder.l2i();
                    case FLOAT -> codeBuilder.l2f();
                    case DOUBLE -> codeBuilder.l2d();
                    default -> throw unsupportedNumericConversion(from, to);
                }
            }
            case FLOAT -> {
                switch (to) {
                    case INT -> codeBuilder.f2i();
                    case LONG -> codeBuilder.f2l();
                    case DOUBLE -> codeBuilder.f2d();
                    default -> throw unsupportedNumericConversion(from, to);
                }
            }
            case DOUBLE -> {
                switch (to) {
                    case INT -> codeBuilder.d2i();
                    case LONG -> codeBuilder.d2l();
                    case FLOAT -> codeBuilder.d2f();
                    default -> throw unsupportedNumericConversion(from, to);
                }
            }
            default -> throw unsupportedNumericConversion(from, to);
        }
    }

    private static UnsupportedOperationException unsupportedNumericConversion(
            java.lang.classfile.TypeKind from, java.lang.classfile.TypeKind to) {
        return new UnsupportedOperationException("CodeEmitter does not yet emit numeric conversion " + from + " -> " + to);
    }

    private void emitUnaryOp(IrUnaryOperator operator, TypeSymbol type) {
        if (type == BuiltinType.DECIMAL) {
            switch (operator) {
                case IDENTITY -> {
                    // Unary `+x`: the operand is already on the stack unchanged.
                }
                case NEGATE -> codeBuilder.invokestatic(CD_VS_DECIMAL, "negate",
                        MethodTypeDesc.of(CD_BIG_DECIMAL, CD_BIG_DECIMAL));
                default -> throw new UnsupportedOperationException(
                        "Unsupported decimal unary operator: " + operator);
            }
            return;
        }
        java.lang.classfile.TypeKind typeKind = toTypeKind(type);
        switch (operator) {
            case IDENTITY -> {
                // Unary `+x`: the operand is already on the stack with no transformation.
            }
            case NEGATE -> {
                switch (numericCarrier(typeKind)) {
                    // `checked(-int.MinValue)` is the one negation that overflows: its result
                    // is one past `int.MaxValue`, and `ineg` would wrap back to itself. C#
                    // has no unsigned unary minus (`-uint` promotes to `long`), so the signed
                    // `Math.negateExact` covers every integral operand that reaches here.
                    case INT -> {
                        if (checkedContext) {
                            codeBuilder.invokestatic(CD_MATH, "negateExact",
                                    MethodTypeDesc.of(ConstantDescs.CD_int, ConstantDescs.CD_int));
                        } else {
                            codeBuilder.ineg();
                        }
                    }
                    case LONG -> {
                        if (checkedContext) {
                            codeBuilder.invokestatic(CD_MATH, "negateExact",
                                    MethodTypeDesc.of(ConstantDescs.CD_long, ConstantDescs.CD_long));
                        } else {
                            codeBuilder.lneg();
                        }
                    }
                    case FLOAT -> codeBuilder.fneg();
                    case DOUBLE -> codeBuilder.dneg();
                    default -> throw new UnsupportedOperationException("Unsupported NEGATE type: " + typeKind);
                }
            }
            case LOGICAL_NOT -> {
                if (typeKind != java.lang.classfile.TypeKind.BOOLEAN && typeKind != java.lang.classfile.TypeKind.INT) {
                    throw new UnsupportedOperationException("Unsupported LOGICAL_NOT type: " + typeKind);
                }
                // Booleans are 0/1 ints on the JVM, so XOR with 1 flips them.
                codeBuilder.ldc(1);
                codeBuilder.ixor();
            }
            case BITWISE_NOT -> {
                java.lang.classfile.TypeKind carrier = integralCarrier(typeKind);
                if (carrier == java.lang.classfile.TypeKind.INT) {
                    // x ^ -1 is ~x for two's-complement ints; the JVM has no dedicated NOT opcode.
                    codeBuilder.ldc(-1);
                    codeBuilder.ixor();
                } else if (carrier == java.lang.classfile.TypeKind.LONG) {
                    codeBuilder.ldc(-1L);
                    codeBuilder.lxor();
                } else {
                    throw new UnsupportedOperationException("Unsupported BITWISE_NOT type: " + typeKind);
                }
            }
            case INDEX_FROM_END -> {
                if (typeKind != java.lang.classfile.TypeKind.INT) {
                    throw new UnsupportedOperationException("INDEX_FROM_END requires an int operand, got " + typeKind);
                }
                ClassDesc vsIndexDesc = ClassDesc.of("vsharp.runtime.VsIndex");
                codeBuilder.invokestatic(vsIndexDesc, "fromEnd",
                        MethodTypeDesc.of(vsIndexDesc, ConstantDescs.CD_int));
            }
        }
    }

    /// Whether a callable is emitted as static, asked by the method flags, parameter slots,
    /// receiver evaluation and invocation opcode. A non-static local function inherits the
    /// receiver mode of its enclosing callable; this is the direct-method equivalent of
    /// capturing `this`, and lets implicit instance-field accesses keep using slot 0.
    private static boolean isStaticMethod(FunctionSymbol symbol,
            Map<FunctionSymbol, List<IrCapture>> captureMap) {
        if (!symbol.localFunction()
                || symbol.modifiers().contains(vsharp.compiler.syntax.SyntaxKind.STATIC)) {
            return symbol.modifiers().contains(vsharp.compiler.syntax.SyntaxKind.STATIC)
                    || symbol.synthesized();
        }
        String qualifiedName = symbol.qualifiedName();
        int separator = qualifiedName.lastIndexOf('$');
        if (separator < 0) {
            throw new IllegalArgumentException(
                    "local function has no enclosing callable: " + qualifiedName);
        }
        String enclosingName = qualifiedName.substring(0, separator);
        for (FunctionSymbol candidate : captureMap.keySet()) {
            if (candidate.qualifiedName().equals(enclosingName)) {
                return isStaticMethod(candidate, captureMap);
            }
        }
        throw new IllegalArgumentException(
                "local function's enclosing callable was not lowered: " + qualifiedName);
    }

    /// The name a callable is emitted under: the last segment of its qualified name.
    ///
    /// For an ordinary method this is its own name. For a local function it is the
    /// `Enclosing$Local` form the declaration binder qualified it with, which is what
    /// keeps two local functions of the same name in different methods apart, and what makes
    /// the definition site and every call site agree without either consulting the other.
    static String emittedMethodName(FunctionSymbol symbol) {
        String qualifiedName = symbol.qualifiedName();
        int lastDot = qualifiedName.lastIndexOf('.');
        String member = lastDot < 0 ? qualifiedName : qualifiedName.substring(lastDot + 1);
        if (member.startsWith("implicit operator ")
                || member.startsWith("explicit operator ")) {
            String spelling = member.substring(member.indexOf("operator ") + 9);
            return member.startsWith("implicit operator ")
                    ? "op_Implicit_" + spelling.replace('.', '_')
                    : "op_Explicit_" + spelling.replace('.', '_');
        }
        // A local function declared among top-level statements carries its enclosing
        // callable's source name, and `<top-level>` is not a legal JVM method name. The
        // enclosing function itself is emitted as `main`, so its locals follow it into
        // `main$Local` rather than reaching a class file with reserved characters in them.
        return member.startsWith(TOP_LEVEL_SOURCE_NAME + "$")
                ? "main" + member.substring(TOP_LEVEL_SOURCE_NAME.length())
                : member;
    }

    /// The class a static call targets, following the single rule the backend places every
    /// callable by: the owner segment of the qualified name, or - when the name has no owner
    /// segment - the synthetic per-file holder, which is the class being emitted. The two
    /// must agree, or the call resolves to a class nobody wrote.
    private ClassDesc callableContainer(String qualifiedName) {
        int lastDot = qualifiedName.lastIndexOf('.');
        return lastDot <= 0 ? ownerClass : ClassDesc.of(qualifiedName.substring(0, lastDot));
    }

    /// `x++`, `++x`, `x--`, `--x`: read the place, step it by one, write it back, and leave
    /// the value the form yields on the stack.
    ///
    /// The only difference between the two forms is *when* the value is duplicated - before
    /// the step for a postfix, after it for a prefix - which is why this cannot be desugared
    /// into the existing compound assignment: recovering the old value arithmetically
    /// (`(x + 1) - 1`) is not an identity for `float` or `double`, where adding one to a
    /// large magnitude changes nothing and subtracting it then changes the value.
    ///
    /// The result is always pushed; a `++` used as a statement is popped by the statement
    /// path, exactly as an assignment expression already is.
    private void emitMutate(IrExpression.Mutate mutate) {
        TypeSymbol type = mutate.type().sourceType();
        if (type instanceof TypeSymbol.Nullable nullable) {
            emitNullableMutate(mutate, nullable);
            return;
        }
        java.lang.classfile.TypeKind kind = toTypeKind(type);
        switch (mutate.place()) {
            case IrExpression.Load load when localSlots.containsKey(load.symbol()) -> {
                loadSymbolValue(load.symbol(), type);
                emitStep(mutate, kind, type);
                // `emitStep` leaves the yielded value below the updated value. Consuming
                // only the update here preserves prefix/postfix expression semantics.
                storeSymbolValue(load.symbol(), type, false);
            }
            case IrExpression.Load load when load.symbol() instanceof FieldSymbol field
                    && field.modifiers().contains(vsharp.compiler.syntax.SyntaxKind.STATIC) -> {
                ClassDesc container = declaringClassOf(field);
                ClassDesc descriptor = toClassDesc(field.type());
                codeBuilder.getstatic(container, field.name(), descriptor);
                emitStep(mutate, kind, type);
                codeBuilder.putstatic(container, field.name(), descriptor);
            }
            case IrExpression.ElementLoad element when element.indices().size() == 1 -> {
                emitExpression(element.receiver());
                emitExpression(element.indices().getFirst());
                // The array and index are needed twice - once to read, once to write - and
                // evaluating them twice would repeat their side effects, so they are
                // duplicated instead. C# evaluates each exactly once.
                codeBuilder.dup2();
                emitArrayLoad(kind);
                if (!mutate.yieldUpdated()) {
                    emitDupUnderArrayTarget(kind);
                }
                emitOne(kind);
                emitBinaryOp(mutate.step(), type);
                if (mutate.yieldUpdated()) {
                    emitDupUnderArrayTarget(kind);
                }
                emitArrayStore(kind);
            }
            default -> throw new UnsupportedOperationException(
                    "CodeEmitter cannot increment or decrement the place: "
                            + mutate.place().getClass().getSimpleName());
        }
    }

    private void emitNullableMutate(IrExpression.Mutate mutate, TypeSymbol.Nullable nullable) {
        int oldSlot = codeBuilder.allocateLocal(java.lang.classfile.TypeKind.REFERENCE);
        int updatedSlot;
        switch (mutate.place()) {
            case IrExpression.Load load when localSlots.containsKey(load.symbol()) -> {
                loadSymbolValue(load.symbol(), nullable);
                codeBuilder.astore(oldSlot);
                updatedSlot = emitNullableStep(oldSlot, nullable, mutate.step());
                codeBuilder.aload(updatedSlot);
                storeSymbolValue(load.symbol(), nullable, false);
            }
            case IrExpression.Load load when load.symbol() instanceof FieldSymbol field
                    && field.modifiers().contains(vsharp.compiler.syntax.SyntaxKind.STATIC) -> {
                ClassDesc container = declaringClassOf(field);
                ClassDesc descriptor = toClassDesc(field.type());
                codeBuilder.getstatic(container, field.name(), descriptor);
                codeBuilder.astore(oldSlot);
                updatedSlot = emitNullableStep(oldSlot, nullable, mutate.step());
                codeBuilder.aload(updatedSlot);
                codeBuilder.putstatic(container, field.name(), descriptor);
            }
            case IrExpression.ElementLoad element when element.indices().size() == 1 -> {
                int arraySlot = codeBuilder.allocateLocal(java.lang.classfile.TypeKind.REFERENCE);
                int indexSlot = codeBuilder.allocateLocal(java.lang.classfile.TypeKind.INT);
                emitExpression(element.receiver());
                codeBuilder.astore(arraySlot);
                emitExpression(element.indices().getFirst());
                codeBuilder.istore(indexSlot);
                codeBuilder.aload(arraySlot);
                codeBuilder.iload(indexSlot);
                emitArrayLoad(java.lang.classfile.TypeKind.REFERENCE);
                codeBuilder.astore(oldSlot);
                updatedSlot = emitNullableStep(oldSlot, nullable, mutate.step());
                codeBuilder.aload(arraySlot);
                codeBuilder.iload(indexSlot);
                codeBuilder.aload(updatedSlot);
                emitArrayStore(java.lang.classfile.TypeKind.REFERENCE);
            }
            default -> throw new UnsupportedOperationException(
                    "CodeEmitter cannot increment or decrement nullable place: "
                            + mutate.place().getClass().getSimpleName());
        }
        codeBuilder.aload(mutate.yieldUpdated() ? updatedSlot : oldSlot);
    }

    private int emitNullableStep(int oldSlot, TypeSymbol.Nullable nullable,
            IrBinaryOperator step) {
        Label noValue = codeBuilder.newLabel();
        Label end = codeBuilder.newLabel();
        codeBuilder.aload(oldSlot);
        codeBuilder.dup();
        codeBuilder.ifnull(noValue);
        emitUnboxNullableElement(nullable.element());
        emitOne(toTypeKind(nullable.element()));
        emitBinaryOp(step, nullable.element());
        emitBox(nullable.element());
        codeBuilder.goto_(end);
        codeBuilder.labelBinding(noValue);
        // The original null remains on the stack.
        codeBuilder.labelBinding(end);
        int updatedSlot = codeBuilder.allocateLocal(java.lang.classfile.TypeKind.REFERENCE);
        codeBuilder.astore(updatedSlot);
        return updatedSlot;
    }

    /// With the current value on top of the stack, leaves the updated value there and a copy
    /// of the yielded value beneath it.
    private void emitStep(IrExpression.Mutate mutate, java.lang.classfile.TypeKind kind,
            TypeSymbol type) {
        if (!mutate.yieldUpdated()) {
            emitDup(kind);
        }
        emitOne(kind);
        emitBinaryOp(mutate.step(), type);
        if (mutate.yieldUpdated()) {
            emitDup(kind);
        }
    }

    /// Pushes the literal one on the operand's own carrier, so the step never mixes widths.
    private void emitOne(java.lang.classfile.TypeKind kind) {
        switch (numericCarrier(kind)) {
            case LONG -> codeBuilder.lconst_1();
            case FLOAT -> codeBuilder.fconst_1();
            case DOUBLE -> codeBuilder.dconst_1();
            default -> codeBuilder.iconst_1();
        }
    }

    private void emitDup(java.lang.classfile.TypeKind kind) {
        if (kind == java.lang.classfile.TypeKind.LONG || kind == java.lang.classfile.TypeKind.DOUBLE) {
            codeBuilder.dup2();
        } else {
            codeBuilder.dup();
        }
    }

    /// Copies the value on top of the stack past the array reference and index beneath it, so
    /// it survives the store that consumes them.
    private void emitDupUnderArrayTarget(java.lang.classfile.TypeKind kind) {
        if (kind == java.lang.classfile.TypeKind.LONG || kind == java.lang.classfile.TypeKind.DOUBLE) {
            codeBuilder.dup2_x2();
        } else {
            codeBuilder.dup_x2();
        }
    }

    /// The class that declares a field, from its qualified name.
    private static ClassDesc declaringClassOf(FieldSymbol field) {
        String qualifiedName = field.qualifiedName();
        return ClassDesc.of(qualifiedName.substring(0, qualifiedName.lastIndexOf('.')));
    }

    /// Emits a binary expression, choosing between the three shapes C# operators actually
    /// have: an opcode over two evaluated operands, a branch that may never evaluate the
    /// right operand, and a call.
    private void emitBinary(IrExpression.Binary binary) {
        switch (binary.operator()) {
            case LOGICAL_AND, LOGICAL_OR -> emitShortCircuit(binary);
            case COALESCE -> emitCoalesce(binary);
            default -> {
                if (isStringConcatenation(binary)) {
                    emitStringConcatenation(binary);
                    return;
                }
                if (hasNullableOperand(binary)) {
                    if ((binary.operator() == IrBinaryOperator.EQUAL
                            || binary.operator() == IrBinaryOperator.NOT_EQUAL)
                            && binary.left().type().sourceType() != TypeSymbol.Null.INSTANCE
                            && binary.right().type().sourceType() != TypeSymbol.Null.INSTANCE) {
                        emitLiftedEquality(binary);
                        return;
                    }
                    if (isRelational(binary.operator())) {
                        emitLiftedRelational(binary);
                        return;
                    }
                    if (isShift(binary.operator())
                            && binary.type().sourceType() instanceof TypeSymbol.Nullable) {
                        emitLiftedShift(binary);
                        return;
                    }
                    if (binary.type().sourceType() instanceof TypeSymbol.Nullable
                            && isLiftedValueOperator(binary.operator())) {
                        emitLiftedValueBinary(binary);
                        return;
                    }
                }
                if (isStringEquality(binary)) {
                    emitStringEquality(binary);
                    return;
                }
                emitExpression(binary.left());
                emitExpression(binary.right());
                emitBinaryOp(binary.operator(), binary.left().type().sourceType());
            }
        }
    }

    /// Whether `==`/`!=` compares two operands whose *static* type is `string`.
    ///
    /// C# defines `==` on `string` as value equality (`String.op_Equality`), and the static
    /// type is what decides: `(object)a == (object)b` is reference equality on the very same
    /// values. The JVM's `if_acmpeq` is reference equality always, so comparing a computed
    /// string against a literal silently answered `false` where C# answers `true` - a wrong
    /// result rather than a diagnostic. A `null` literal operand keeps the reference
    /// form, which is both correct and what C# compiles it to.
    private static boolean isStringEquality(IrExpression.Binary binary) {
        return (binary.operator() == IrBinaryOperator.EQUAL
                        || binary.operator() == IrBinaryOperator.NOT_EQUAL)
                && binary.left().type().sourceType() == BuiltinType.STRING
                && binary.right().type().sourceType() == BuiltinType.STRING;
    }

    /// Emits C#'s string `==` as `java.util.Objects.equals`, which is null-safe in exactly
    /// C#'s way: two nulls are equal and a null never equals a value.
    private void emitStringEquality(IrExpression.Binary binary) {
        emitExpression(binary.left());
        emitExpression(binary.right());
        codeBuilder.invokestatic(ClassDesc.of("java.util.Objects"), "equals",
                MethodTypeDesc.of(ConstantDescs.CD_boolean, ConstantDescs.CD_Object,
                        ConstantDescs.CD_Object));
        if (binary.operator() == IrBinaryOperator.NOT_EQUAL) {
            codeBuilder.iconst_1();
            codeBuilder.ixor();
        }
    }

    /// `a && b` / `a || b`. The defining property is what does *not* happen: `b` is never
    /// evaluated once `a` decided the result, so this cannot be an opcode over two evaluated
    /// operands the way `a & b` is, and the difference is observable whenever `b` has an
    /// effect or would throw.
    private void emitShortCircuit(IrExpression.Binary binary) {
        boolean isAnd = binary.operator() == IrBinaryOperator.LOGICAL_AND;
        Label decidedLabel = codeBuilder.newLabel();
        Label endLabel = codeBuilder.newLabel();

        emitExpression(binary.left());
        if (isAnd) {
            codeBuilder.ifeq(decidedLabel);
        } else {
            codeBuilder.ifne(decidedLabel);
        }
        emitExpression(binary.right());
        codeBuilder.goto_(endLabel);

        codeBuilder.labelBinding(decidedLabel);
        if (isAnd) {
            codeBuilder.iconst_0();
        } else {
            codeBuilder.iconst_1();
        }
        codeBuilder.labelBinding(endLabel);
    }

    /// `a ?? b`: `a` when it is not null, otherwise `b`, and `b` is evaluated only then.
    ///
    /// The left operand is duplicated rather than stored, so the common path costs one `dup`
    /// and no local. A nullable left is unboxed on its present edge exactly when the result is
    /// its underlying value type; both edges then reach the join with the same verifier kind.
    private void emitCoalesce(IrExpression.Binary binary) {
        TypeSymbol leftType = binary.left().type().sourceType();
        if (toTypeKind(leftType) != java.lang.classfile.TypeKind.REFERENCE) {
            throw new UnsupportedOperationException(
                    "CodeEmitter cannot emit '??' over a non-reference operand: " + leftType.displayName());
        }
        Label useRight = codeBuilder.newLabel();
        Label endLabel = codeBuilder.newLabel();
        emitExpression(binary.left());
        codeBuilder.dup();
        codeBuilder.ifnull(useRight);
        if (leftType instanceof TypeSymbol.Nullable nullable
                && binary.type().sourceType().equals(nullable.element())) {
            emitUnboxNullableElement(nullable.element());
        }
        codeBuilder.goto_(endLabel);
        codeBuilder.labelBinding(useRight);
        codeBuilder.pop();
        emitExpression(binary.right());
        codeBuilder.labelBinding(endLabel);
    }

    private static boolean hasNullableOperand(IrExpression.Binary binary) {
        return binary.left().type().sourceType() instanceof TypeSymbol.Nullable
                || binary.right().type().sourceType() instanceof TypeSymbol.Nullable;
    }

    private static boolean isLiftedValueOperator(IrBinaryOperator operator) {
        return operator == IrBinaryOperator.ADD || operator == IrBinaryOperator.SUBTRACT
                || operator == IrBinaryOperator.MULTIPLY || operator == IrBinaryOperator.DIVIDE
                || operator == IrBinaryOperator.REMAINDER
                || operator == IrBinaryOperator.BITWISE_AND
                || operator == IrBinaryOperator.BITWISE_OR
                || operator == IrBinaryOperator.EXCLUSIVE_OR;
    }

    private static boolean isRelational(IrBinaryOperator operator) {
        return operator == IrBinaryOperator.LESS_THAN
                || operator == IrBinaryOperator.LESS_THAN_OR_EQUAL
                || operator == IrBinaryOperator.GREATER_THAN
                || operator == IrBinaryOperator.GREATER_THAN_OR_EQUAL;
    }

    private static boolean isShift(IrBinaryOperator operator) {
        return operator == IrBinaryOperator.SHIFT_LEFT
                || operator == IrBinaryOperator.SHIFT_RIGHT
                || operator == IrBinaryOperator.UNSIGNED_SHIFT_RIGHT;
    }

    /// Lifted value-producing operators evaluate both operands once, in source order, before
    /// observing either nullable state. Present operands are unboxed and promoted to the
    /// result's underlying type; a missing operand produces the single null result carrier.
    private void emitLiftedValueBinary(IrExpression.Binary binary) {
        TypeSymbol leftType = binary.left().type().sourceType();
        TypeSymbol rightType = binary.right().type().sourceType();
        TypeSymbol resultElement = ((TypeSymbol.Nullable) binary.type().sourceType()).element();

        if (resultElement == BuiltinType.BOOL
                && (binary.operator() == IrBinaryOperator.BITWISE_AND
                        || binary.operator() == IrBinaryOperator.BITWISE_OR)) {
            emitLiftedBooleanAndOr(binary);
            return;
        }

        java.lang.classfile.TypeKind leftKind = toTypeKind(leftType);
        java.lang.classfile.TypeKind rightKind = toTypeKind(rightType);
        int leftSlot = codeBuilder.allocateLocal(leftKind);
        int rightSlot = codeBuilder.allocateLocal(rightKind);
        emitExpression(binary.left());
        storeLocal(leftKind, leftSlot);
        emitExpression(binary.right());
        storeLocal(rightKind, rightSlot);

        Label noValue = codeBuilder.newLabel();
        Label end = codeBuilder.newLabel();
        if (leftType instanceof TypeSymbol.Nullable) {
            loadLocal(leftKind, leftSlot);
            codeBuilder.ifnull(noValue);
        }
        if (rightType instanceof TypeSymbol.Nullable) {
            loadLocal(rightKind, rightSlot);
            codeBuilder.ifnull(noValue);
        }

        emitLiftedOperand(leftType, leftSlot, resultElement);
        emitLiftedOperand(rightType, rightSlot, resultElement);
        emitBinaryOp(binary.operator(), resultElement);
        emitBox(resultElement);
        codeBuilder.goto_(end);

        codeBuilder.labelBinding(noValue);
        codeBuilder.aconst_null();
        codeBuilder.labelBinding(end);
    }

    /// Lifted `bool? & bool?` and `bool? | bool?` are the two non-null-propagating lifted
    /// operators: false decides `&` and true decides `|` even when the other side is null.
    private void emitLiftedBooleanAndOr(IrExpression.Binary binary) {
        TypeSymbol leftType = binary.left().type().sourceType();
        TypeSymbol rightType = binary.right().type().sourceType();
        java.lang.classfile.TypeKind leftKind = toTypeKind(leftType);
        java.lang.classfile.TypeKind rightKind = toTypeKind(rightType);
        int leftSlot = codeBuilder.allocateLocal(leftKind);
        int rightSlot = codeBuilder.allocateLocal(rightKind);
        emitExpression(binary.left());
        storeLocal(leftKind, leftSlot);
        emitExpression(binary.right());
        storeLocal(rightKind, rightSlot);

        boolean isAnd = binary.operator() == IrBinaryOperator.BITWISE_AND;
        Label decided = codeBuilder.newLabel();
        Label noValue = codeBuilder.newLabel();
        Label end = codeBuilder.newLabel();
        emitDecisiveNullableBoolean(leftType, leftSlot, isAnd, decided);
        emitDecisiveNullableBoolean(rightType, rightSlot, isAnd, decided);
        if (leftType instanceof TypeSymbol.Nullable) {
            loadLocal(leftKind, leftSlot);
            codeBuilder.ifnull(noValue);
        }
        if (rightType instanceof TypeSymbol.Nullable) {
            loadLocal(rightKind, rightSlot);
            codeBuilder.ifnull(noValue);
        }
        codeBuilder.ldc(isAnd ? 1 : 0);
        emitBox(java.lang.classfile.TypeKind.BOOLEAN);
        codeBuilder.goto_(end);

        codeBuilder.labelBinding(decided);
        codeBuilder.ldc(isAnd ? 0 : 1);
        emitBox(java.lang.classfile.TypeKind.BOOLEAN);
        codeBuilder.goto_(end);

        codeBuilder.labelBinding(noValue);
        codeBuilder.aconst_null();
        codeBuilder.labelBinding(end);
    }

    private void emitDecisiveNullableBoolean(TypeSymbol type, int slot, boolean isAnd,
            Label decided) {
        if (type instanceof TypeSymbol.Nullable nullable) {
            Label absent = codeBuilder.newLabel();
            loadLocal(toTypeKind(type), slot);
            codeBuilder.ifnull(absent);
            emitLiftedOperand(type, slot, nullable.element());
            if (isAnd) {
                codeBuilder.ifeq(decided);
            } else {
                codeBuilder.ifne(decided);
            }
            codeBuilder.labelBinding(absent);
            return;
        }
        loadLocal(toTypeKind(type), slot);
        if (isAnd) {
            codeBuilder.ifeq(decided);
        } else {
            codeBuilder.ifne(decided);
        }
    }

    private void emitLiftedEquality(IrExpression.Binary binary) {
        LiftedOperands operands = spillLiftedOperands(binary);
        TypeSymbol comparisonType = commonLiftedOperandType(operands.leftType(),
                operands.rightType());
        Label falseResult = codeBuilder.newLabel();
        Label trueResult = codeBuilder.newLabel();
        Label end = codeBuilder.newLabel();

        if (operands.leftType() instanceof TypeSymbol.Nullable) {
            Label leftPresent = codeBuilder.newLabel();
            loadLocal(operands.leftKind(), operands.leftSlot());
            codeBuilder.ifnonnull(leftPresent);
            if (operands.rightType() instanceof TypeSymbol.Nullable) {
                loadLocal(operands.rightKind(), operands.rightSlot());
                codeBuilder.ifnull(trueResult);
            }
            codeBuilder.goto_(falseResult);
            codeBuilder.labelBinding(leftPresent);
        }
        if (operands.rightType() instanceof TypeSymbol.Nullable) {
            loadLocal(operands.rightKind(), operands.rightSlot());
            codeBuilder.ifnull(falseResult);
        }

        emitLiftedOperand(operands.leftType(), operands.leftSlot(), comparisonType);
        emitLiftedOperand(operands.rightType(), operands.rightSlot(), comparisonType);
        emitBinaryOp(IrBinaryOperator.EQUAL, comparisonType);
        codeBuilder.goto_(end);

        codeBuilder.labelBinding(trueResult);
        codeBuilder.iconst_1();
        codeBuilder.goto_(end);
        codeBuilder.labelBinding(falseResult);
        codeBuilder.iconst_0();
        codeBuilder.labelBinding(end);
        if (binary.operator() == IrBinaryOperator.NOT_EQUAL) {
            codeBuilder.iconst_1();
            codeBuilder.ixor();
        }
    }

    private void emitLiftedRelational(IrExpression.Binary binary) {
        LiftedOperands operands = spillLiftedOperands(binary);
        TypeSymbol comparisonType = commonLiftedOperandType(operands.leftType(),
                operands.rightType());
        Label falseResult = codeBuilder.newLabel();
        Label end = codeBuilder.newLabel();
        if (operands.leftType() instanceof TypeSymbol.Nullable) {
            loadLocal(operands.leftKind(), operands.leftSlot());
            codeBuilder.ifnull(falseResult);
        }
        if (operands.rightType() instanceof TypeSymbol.Nullable) {
            loadLocal(operands.rightKind(), operands.rightSlot());
            codeBuilder.ifnull(falseResult);
        }
        emitLiftedOperand(operands.leftType(), operands.leftSlot(), comparisonType);
        emitLiftedOperand(operands.rightType(), operands.rightSlot(), comparisonType);
        emitBinaryOp(binary.operator(), comparisonType);
        codeBuilder.goto_(end);
        codeBuilder.labelBinding(falseResult);
        codeBuilder.iconst_0();
        codeBuilder.labelBinding(end);
    }

    private void emitLiftedShift(IrExpression.Binary binary) {
        LiftedOperands operands = spillLiftedOperands(binary);
        TypeSymbol resultElement = ((TypeSymbol.Nullable) binary.type().sourceType()).element();
        Label noValue = codeBuilder.newLabel();
        Label end = codeBuilder.newLabel();
        if (operands.leftType() instanceof TypeSymbol.Nullable) {
            loadLocal(operands.leftKind(), operands.leftSlot());
            codeBuilder.ifnull(noValue);
        }
        if (operands.rightType() instanceof TypeSymbol.Nullable) {
            loadLocal(operands.rightKind(), operands.rightSlot());
            codeBuilder.ifnull(noValue);
        }
        emitLiftedOperand(operands.leftType(), operands.leftSlot(), resultElement);
        emitLiftedOperand(operands.rightType(), operands.rightSlot(), BuiltinType.INT);
        emitBinaryOp(binary.operator(), resultElement);
        emitBox(resultElement);
        codeBuilder.goto_(end);
        codeBuilder.labelBinding(noValue);
        codeBuilder.aconst_null();
        codeBuilder.labelBinding(end);
    }

    private record LiftedOperands(TypeSymbol leftType, TypeSymbol rightType,
            java.lang.classfile.TypeKind leftKind, java.lang.classfile.TypeKind rightKind,
            int leftSlot, int rightSlot) {}

    private LiftedOperands spillLiftedOperands(IrExpression.Binary binary) {
        TypeSymbol leftType = binary.left().type().sourceType();
        TypeSymbol rightType = binary.right().type().sourceType();
        java.lang.classfile.TypeKind leftKind = toTypeKind(leftType);
        java.lang.classfile.TypeKind rightKind = toTypeKind(rightType);
        int leftSlot = codeBuilder.allocateLocal(leftKind);
        int rightSlot = codeBuilder.allocateLocal(rightKind);
        emitExpression(binary.left());
        storeLocal(leftKind, leftSlot);
        emitExpression(binary.right());
        storeLocal(rightKind, rightSlot);
        return new LiftedOperands(leftType, rightType, leftKind, rightKind, leftSlot, rightSlot);
    }

    private static TypeSymbol commonLiftedOperandType(TypeSymbol left, TypeSymbol right) {
        TypeSymbol leftElement = left instanceof TypeSymbol.Nullable nullable
                ? nullable.element() : left;
        TypeSymbol rightElement = right instanceof TypeSymbol.Nullable nullable
                ? nullable.element() : right;
        if (leftElement.equals(rightElement)) {
            return leftElement;
        }
        if (Conversions.classify(leftElement, rightElement).isImplicit()) {
            return rightElement;
        }
        if (Conversions.classify(rightElement, leftElement).isImplicit()) {
            return leftElement;
        }
        throw new UnsupportedOperationException("CodeEmitter cannot choose lifted operand type for "
                + left.displayName() + " and " + right.displayName());
    }

    private void emitLiftedOperand(TypeSymbol operandType, int slot, TypeSymbol resultElement) {
        loadLocal(toTypeKind(operandType), slot);
        TypeSymbol underlying = operandType instanceof TypeSymbol.Nullable nullable
                ? nullable.element() : operandType;
        if (operandType instanceof TypeSymbol.Nullable) {
            emitUnboxNullableElement(underlying);
        }
        emitUnderlyingConversion(underlying, resultElement);
    }

    private void emitLiftedUnary(IrExpression.Unary unary) {
        TypeSymbol operandElement = ((TypeSymbol.Nullable)
                unary.operand().type().sourceType()).element();
        TypeSymbol resultElement = ((TypeSymbol.Nullable) unary.type().sourceType()).element();
        Label noValue = codeBuilder.newLabel();
        Label end = codeBuilder.newLabel();
        emitExpression(unary.operand());
        codeBuilder.dup();
        codeBuilder.ifnull(noValue);
        emitUnboxNullableElement(operandElement);
        if (unary.operator() != IrUnaryOperator.INDEX_FROM_END) {
            emitUnderlyingConversion(operandElement, resultElement);
        }
        TypeSymbol operatorInput = unary.operator() == IrUnaryOperator.INDEX_FROM_END
                ? operandElement : resultElement;
        emitUnaryOp(unary.operator(), operatorInput);
        emitBox(resultElement);
        codeBuilder.goto_(end);
        codeBuilder.labelBinding(noValue);
        // The original null remains on the stack on this edge.
        codeBuilder.labelBinding(end);
    }

    /// `+` is string concatenation exactly when the binder typed the result `string`, which
    /// is the one rule C# applies (either operand being a string makes it a concatenation).
    private static boolean isStringConcatenation(IrExpression.Binary binary) {
        return binary.operator() == IrBinaryOperator.ADD
                && binary.type().sourceType() == BuiltinType.STRING;
    }

    /// `a + b` over strings. Each operand goes through `VsFormat.toDisplayString(Object)`
    /// first - the same conversion interpolation uses, so `$"{x}"` and `"" + x` can never
    /// render the same value differently - which is also what makes the C# null rule fall
    /// out: `toDisplayString(null)` is the empty string, so `null + "x"` is `"x"` and not
    /// `"nullx"` as a plain `String.valueOf` would give. It is also why the receiver of
    /// `String.concat` is always non-null.
    private void emitStringConcatenation(IrExpression.Binary binary) {
        emitDisplayString(binary.left());
        emitDisplayString(binary.right());
        codeBuilder.invokevirtual(ConstantDescs.CD_String, "concat",
                MethodTypeDesc.of(ConstantDescs.CD_String, ConstantDescs.CD_String));
    }

    /// Emits an operand as the string C# would render it as.
    ///
    /// A statically unsigned operand reaches the primitive formatter directly, avoiding an
    /// object allocation. the design also preserves unsigned identity after boxing, but this path
    /// still has the source type and therefore needs no box merely to render it.
    private void emitDisplayString(IrExpression operand) {
        emitExpression(operand);
        TypeSymbol type = operand.type().sourceType();
        if (isUnsigned(type)) {
            ClassDesc carrier = type == BuiltinType.UINT
                    ? ConstantDescs.CD_int : ConstantDescs.CD_long;
            codeBuilder.invokestatic(ClassDesc.of("vsharp.runtime.VsFormat"),
                    "toDisplayStringUnsigned",
                    MethodTypeDesc.of(ConstantDescs.CD_String, carrier));
            return;
        }
        java.lang.classfile.TypeKind kind = toTypeKind(type);
        if (type instanceof NamedTypeSymbol named && named.declaredKind() == NamedTypeSymbol.DeclaredKind.ENUM) {
            String enumHolder = JvmBackend.holderName(named.qualifiedName());
            if (enumHolder == null) {
                enumHolder = JvmBackend.sourceFileClassName(named.location().file().name());
            }
            String methodName = "$" + named.qualifiedName().replace('.', '$') + "_name";
            codeBuilder.invokestatic(ClassDesc.of(enumHolder), methodName, 
                    MethodTypeDesc.of(ConstantDescs.CD_String, ConstantDescs.CD_int));
            return;
        }
        if (kind != java.lang.classfile.TypeKind.REFERENCE) {
            emitBox(type);
        }
        codeBuilder.invokestatic(ClassDesc.of("vsharp.runtime.VsFormat"), "toDisplayString",
                MethodTypeDesc.of(ConstantDescs.CD_String, ConstantDescs.CD_Object));
    }

    /// Emits an operator that consumes both operands from the stack.
    ///
    /// It takes the operand *type*, not only its JVM kind, because two C# operators need the
    /// signedness the kind erases: `>>` is an arithmetic shift over a signed type and a
    /// logical one over `uint`/`ulong`, which share the JVM's signed carriers. The
    /// short-circuiting operators are not here at all - `&&`, `||` and `??` must not evaluate
    /// their right operand unconditionally, so they are emitted as branches by `emitBinary`.
    private void emitBinaryOp(IrBinaryOperator operator, TypeSymbol operandType) {
        if (operandType == BuiltinType.DECIMAL) {
            emitDecimalOp(operator);
            return;
        }
        if (checkedContext && emitCheckedArithmetic(operator, operandType)) {
            return;
        }
        java.lang.classfile.TypeKind typeKind = toTypeKind(operandType);
        switch (operator) {
            case ADD -> emitArithmeticOp(typeKind, NumericOpcode.ADD);
            case SUBTRACT -> emitArithmeticOp(typeKind, NumericOpcode.SUBTRACT);
            case MULTIPLY -> emitArithmeticOp(typeKind, NumericOpcode.MULTIPLY);
            // `idiv`/`irem` read both operands as signed, so the unsigned types - which share
            // those carriers - need the `Integer`/`Long` statics instead: `4294967295u / 2`
            // is `2147483647`, not the `0` a signed division of the same bits produces.
            case DIVIDE -> {
                if (!emitUnsignedDivision(operandType, "divideUnsigned")) {
                    emitArithmeticOp(typeKind, NumericOpcode.DIVIDE);
                }
            }
            case REMAINDER -> {
                if (!emitUnsignedDivision(operandType, "remainderUnsigned")) {
                    emitArithmeticOp(typeKind, NumericOpcode.REMAINDER);
                }
            }
            case BITWISE_AND -> emitIntegralOp(typeKind, IntegralOpcode.AND);
            case BITWISE_OR -> emitIntegralOp(typeKind, IntegralOpcode.OR);
            case EXCLUSIVE_OR -> emitIntegralOp(typeKind, IntegralOpcode.XOR);
            case SHIFT_LEFT -> emitIntegralOp(typeKind, IntegralOpcode.SHIFT_LEFT);
            // C# reads `>>` from the left operand's signedness and `>>>` always logically;
            // the JVM spells that difference as two opcodes over the same carrier.
            case SHIFT_RIGHT -> emitIntegralOp(typeKind, isUnsigned(operandType)
                    ? IntegralOpcode.SHIFT_RIGHT_UNSIGNED : IntegralOpcode.SHIFT_RIGHT);
            case UNSIGNED_SHIFT_RIGHT -> emitIntegralOp(typeKind,
                    IntegralOpcode.SHIFT_RIGHT_UNSIGNED);
            case EQUAL, LESS_THAN, GREATER_THAN, LESS_THAN_OR_EQUAL, GREATER_THAN_OR_EQUAL, NOT_EQUAL -> {
                Label trueLabel = codeBuilder.newLabel();
                Label endLabel = codeBuilder.newLabel();
                if (isUnsigned(operandType) && isRelational(operator)) {
                    // Ordering is the one place where the unsigned types cannot reuse their
                    // signed carrier's branch: `if_icmpgt`/`lcmp` would order 4294967295u
                    // below 1. Equality is unaffected, being a bit comparison either way.
                    emitUnsignedCompare(typeKind);
                    switch (operator) {
                        case LESS_THAN -> codeBuilder.iflt(trueLabel);
                        case GREATER_THAN -> codeBuilder.ifgt(trueLabel);
                        case LESS_THAN_OR_EQUAL -> codeBuilder.ifle(trueLabel);
                        case GREATER_THAN_OR_EQUAL -> codeBuilder.ifge(trueLabel);
                        default -> {}
                    }
                } else if (intSwitchCarrier(typeKind) || typeKind == java.lang.classfile.TypeKind.BOOLEAN) {
                    switch (operator) {
                        case EQUAL -> codeBuilder.if_icmpeq(trueLabel);
                        case NOT_EQUAL -> codeBuilder.if_icmpne(trueLabel);
                        case LESS_THAN -> codeBuilder.if_icmplt(trueLabel);
                        case GREATER_THAN -> codeBuilder.if_icmpgt(trueLabel);
                        case LESS_THAN_OR_EQUAL -> codeBuilder.if_icmple(trueLabel);
                        case GREATER_THAN_OR_EQUAL -> codeBuilder.if_icmpge(trueLabel);
                        default -> {}
                    }
                } else if (typeKind == java.lang.classfile.TypeKind.LONG
                        || typeKind == java.lang.classfile.TypeKind.FLOAT
                        || typeKind == java.lang.classfile.TypeKind.DOUBLE) {
                    // The JVM has no two-operand branch for the wide carriers: a compare opcode
                    // reduces the pair to -1/0/1 on the int stack and the branch tests that.
                    emitCompareOpcode(typeKind, operator);
                    switch (operator) {
                        case EQUAL -> codeBuilder.ifeq(trueLabel);
                        case NOT_EQUAL -> codeBuilder.ifne(trueLabel);
                        case LESS_THAN -> codeBuilder.iflt(trueLabel);
                        case GREATER_THAN -> codeBuilder.ifgt(trueLabel);
                        case LESS_THAN_OR_EQUAL -> codeBuilder.ifle(trueLabel);
                        case GREATER_THAN_OR_EQUAL -> codeBuilder.ifge(trueLabel);
                        default -> {}
                    }
                } else if (typeKind == java.lang.classfile.TypeKind.REFERENCE) {
                    switch (operator) {
                        case EQUAL -> codeBuilder.if_acmpeq(trueLabel);
                        case NOT_EQUAL -> codeBuilder.if_acmpne(trueLabel);
                        default -> throw new UnsupportedOperationException("Unsupported comparison for references");
                    }
                } else {
                    throw new UnsupportedOperationException("Unsupported comparison type");
                }
                // Push false
                codeBuilder.ldc(0);
                codeBuilder.goto_(endLabel);
                // Push true
                codeBuilder.labelBinding(trueLabel);
                codeBuilder.ldc(1);
                // End
                codeBuilder.labelBinding(endLabel);
            }
            default -> throw new UnsupportedOperationException("Unsupported binary operator: " + operator);
        }
    }

    /// Emits a `decimal` operator after both operands are already on the stack as
    /// `BigDecimal` references. Every operation goes through [vsharp.runtime.VsDecimal] -
    /// the same policy constant folding uses - so compile-time and run-time answers
    /// cannot diverge. Decimal arithmetic is always checked in C#, so `checked`/`unchecked`
    /// context is deliberately not consulted here.
    private void emitDecimalOp(IrBinaryOperator operator) {
        MethodTypeDesc binaryDesc = MethodTypeDesc.of(CD_BIG_DECIMAL, CD_BIG_DECIMAL,
                CD_BIG_DECIMAL);
        switch (operator) {
            case ADD -> codeBuilder.invokestatic(CD_VS_DECIMAL, "add", binaryDesc);
            case SUBTRACT -> codeBuilder.invokestatic(CD_VS_DECIMAL, "subtract", binaryDesc);
            case MULTIPLY -> codeBuilder.invokestatic(CD_VS_DECIMAL, "multiply", binaryDesc);
            case DIVIDE -> codeBuilder.invokestatic(CD_VS_DECIMAL, "divide", binaryDesc);
            case REMAINDER -> codeBuilder.invokestatic(CD_VS_DECIMAL, "remainder", binaryDesc);
            case EQUAL, NOT_EQUAL, LESS_THAN, LESS_THAN_OR_EQUAL, GREATER_THAN,
                    GREATER_THAN_OR_EQUAL -> emitDecimalCompare(operator);
            default -> throw new UnsupportedOperationException(
                    "Unsupported decimal binary operator: " + operator);
        }
    }

    /// Reduces two `BigDecimal` operands to the -1/0/1 the `if<cond>` branches test, then
    /// emits the same boolean-construction shape as the primitive comparison paths.
    private void emitDecimalCompare(IrBinaryOperator operator) {
        codeBuilder.invokestatic(CD_VS_DECIMAL, "compare",
                MethodTypeDesc.of(ConstantDescs.CD_int, CD_BIG_DECIMAL, CD_BIG_DECIMAL));
        Label trueLabel = codeBuilder.newLabel();
        Label endLabel = codeBuilder.newLabel();
        switch (operator) {
            case EQUAL -> codeBuilder.ifeq(trueLabel);
            case NOT_EQUAL -> codeBuilder.ifne(trueLabel);
            case LESS_THAN -> codeBuilder.iflt(trueLabel);
            case GREATER_THAN -> codeBuilder.ifgt(trueLabel);
            case LESS_THAN_OR_EQUAL -> codeBuilder.ifle(trueLabel);
            case GREATER_THAN_OR_EQUAL -> codeBuilder.ifge(trueLabel);
            default -> throw new UnsupportedOperationException(
                    "Unsupported decimal comparison: " + operator);
        }
        codeBuilder.ldc(0);
        codeBuilder.goto_(endLabel);
        codeBuilder.labelBinding(trueLabel);
        codeBuilder.ldc(1);
        codeBuilder.labelBinding(endLabel);
    }

    /// Reduces two wide operands to the -1/0/1 the `if<cond>` branches test.
    ///
    /// `lcmp` is total, but the float comparisons come in two flavours that differ only when an
    /// operand is NaN, and C# requires every ordered comparison against NaN to be false while
    /// `!=` is true. Choosing the flavour that pushes the value which *fails* the following
    /// branch gives exactly that: `fcmpg` (NaN -> 1) under `iflt`/`ifle`, `fcmpl` (NaN -> -1)
    /// under `ifgt`/`ifge`/`ifeq`, and `fcmpl` again under `ifne`, where -1 is correctly true.
    private void emitCompareOpcode(java.lang.classfile.TypeKind typeKind, IrBinaryOperator operator) {
        boolean nanMustCompareHigh = operator == IrBinaryOperator.LESS_THAN
                || operator == IrBinaryOperator.LESS_THAN_OR_EQUAL;
        switch (typeKind) {
            case LONG -> codeBuilder.lcmp();
            case FLOAT -> {
                if (nanMustCompareHigh) {
                    codeBuilder.fcmpg();
                } else {
                    codeBuilder.fcmpl();
                }
            }
            case DOUBLE -> {
                if (nanMustCompareHigh) {
                    codeBuilder.dcmpg();
                } else {
                    codeBuilder.dcmpl();
                }
            }
            default -> throw new UnsupportedOperationException("Unsupported comparison type: " + typeKind);
        }
    }

    private enum IntegralOpcode {
        AND,
        OR,
        XOR,
        SHIFT_LEFT,
        SHIFT_RIGHT,
        SHIFT_RIGHT_UNSIGNED
    }

    /// The integral bitwise and shift opcodes, over the two carriers C# defines them for.
    ///
    /// C#'s shift-count masking (5 bits for a 32-bit operand, 6 for 64-bit) needs no code:
    /// the JVM shift instructions mask the count exactly the same way. The count itself is
    /// always an `int` on the stack, which is what the binder already guarantees by keeping
    /// a shift's right operand out of numeric promotion.
    private void emitIntegralOp(java.lang.classfile.TypeKind typeKind, IntegralOpcode opcode) {
        // C# defines `&`, `|` and `^` on `bool` as well as on the integral types, and the two
        // cases need no distinction here: the JVM carries a `bool` as an `int` holding 0 or 1,
        // over which `iand`/`ior`/`ixor` are exactly the logical operators. (`bool` never
        // reaches a shift - C# does not define one.)
        java.lang.classfile.TypeKind carrier = typeKind == java.lang.classfile.TypeKind.BOOLEAN
                ? java.lang.classfile.TypeKind.INT
                : integralCarrier(typeKind);
        switch (carrier) {
            case INT -> {
                switch (opcode) {
                    case AND -> codeBuilder.iand();
                    case OR -> codeBuilder.ior();
                    case XOR -> codeBuilder.ixor();
                    case SHIFT_LEFT -> codeBuilder.ishl();
                    case SHIFT_RIGHT -> codeBuilder.ishr();
                    case SHIFT_RIGHT_UNSIGNED -> codeBuilder.iushr();
                }
            }
            case LONG -> {
                switch (opcode) {
                    case AND -> codeBuilder.land();
                    case OR -> codeBuilder.lor();
                    case XOR -> codeBuilder.lxor();
                    case SHIFT_LEFT -> codeBuilder.lshl();
                    case SHIFT_RIGHT -> codeBuilder.lshr();
                    case SHIFT_RIGHT_UNSIGNED -> codeBuilder.lushr();
                }
            }
            default -> throw new UnsupportedOperationException(
                    "CodeEmitter cannot emit an integral operator over: " + typeKind);
        }
    }

    /// Whether C# treats values of this type as unsigned. `byte`, `ushort` and `char` widen
    /// to `int` before any shift, so only the two types that keep their own carrier can make
    /// `>>` logical.
    /// Emits `Integer.divideUnsigned`/`remainderUnsigned` or their `Long` counterparts for an
    /// unsigned operand type, reporting whether the operator was handled. `byte`/`ushort`
    /// never reach here: binary numeric promotion has already widened them to `int`, where
    /// their values are positive and the signed opcode is exact.
    private boolean emitUnsignedDivision(TypeSymbol operandType, String name) {
        if (!isUnsigned(operandType)) {
            return false;
        }
        ClassDesc carrier = operandType == BuiltinType.UINT
                ? ConstantDescs.CD_int : ConstantDescs.CD_long;
        ClassDesc owner = operandType == BuiltinType.UINT
                ? ConstantDescs.CD_Integer : ConstantDescs.CD_Long;
        codeBuilder.invokestatic(owner, name, MethodTypeDesc.of(carrier, carrier, carrier));
        return true;
    }

    /// Reduces two unsigned operands to the -1/0/1 the `if<cond>` branches test, using the
    /// unsigned comparison the carrier's own opcodes cannot express.
    private void emitUnsignedCompare(java.lang.classfile.TypeKind typeKind) {
        ClassDesc carrier = typeKind == java.lang.classfile.TypeKind.LONG
                ? ConstantDescs.CD_long : ConstantDescs.CD_int;
        ClassDesc owner = typeKind == java.lang.classfile.TypeKind.LONG
                ? ConstantDescs.CD_Long : ConstantDescs.CD_Integer;
        codeBuilder.invokestatic(owner, "compareUnsigned",
                MethodTypeDesc.of(ConstantDescs.CD_int, carrier, carrier));
    }

    private static boolean isUnsigned(TypeSymbol type) {
        return type == BuiltinType.UINT || type == BuiltinType.ULONG
                || type == BuiltinType.NUINT;
    }

    /// Emits `+`, `-` or `*` in their trapping form, and reports whether it did. When it
    /// reports `false` the plain wrapping opcode is still owed and [#emitBinaryOp] emits it.
    ///
    /// Only integral arithmetic can overflow in C# (§11.7.18): floating point saturates to
    /// an infinity, and `/`, `%`, the bitwise and the shift operators cannot leave their
    /// type's range - `int.MinValue / -1` is the single exception, and the JVM's `idiv`
    /// already throws for it. Both operands are on the stack in their common type by the
    /// time this runs, so the call is a straight substitution for the opcode.
    ///
    /// The signed carriers reach `java.lang.Math`, whose `*Exact` methods throw exactly the
    /// `ArithmeticException` that `System.OverflowException` maps to. The unsigned
    /// types share those carriers but not their boundary, so they call [vsharp.runtime.VsChecked]
    /// instead. Enums are `int`-carried, and C# performs enum arithmetic on the
    /// underlying type, so an enum operand is normalised to `int` before the carrier check;
    /// without that, `checked(E + U)`/`checked(E - E)` silently wrapped instead of trapping.
    private boolean emitCheckedArithmetic(IrBinaryOperator operator, TypeSymbol operandType) {
        String signedName = switch (operator) {
            case ADD -> "addExact";
            case SUBTRACT -> "subtractExact";
            case MULTIPLY -> "multiplyExact";
            default -> null;
        };
        if (signedName == null) {
            return false;
        }
        if (operandType instanceof NamedTypeSymbol named
                && named.declaredKind() == NamedTypeSymbol.DeclaredKind.ENUM) {
            operandType = BuiltinType.INT;
        }
        if (!(operandType instanceof BuiltinType builtin)) {
            return false;
        }
        boolean integral = switch (builtin) {
            case SBYTE, BYTE, SHORT, USHORT, INT, UINT, LONG, ULONG, NINT, NUINT, CHAR -> true;
            default -> false;
        };
        if (!integral) {
            return false;
        }
        boolean isLong = builtin.jvmTypeKind() == JvmTypeKind.LONG;
        ClassDesc carrier = isLong ? ConstantDescs.CD_long : ConstantDescs.CD_int;
        MethodTypeDesc desc = MethodTypeDesc.of(carrier, carrier, carrier);
        if (isUnsigned(builtin)) {
            String unsignedName = switch (operator) {
                case ADD -> isLong ? "addULong" : "addUInt";
                case SUBTRACT -> isLong ? "subtractULong" : "subtractUInt";
                default -> isLong ? "multiplyULong" : "multiplyUInt";
            };
            codeBuilder.invokestatic(CD_VS_CHECKED, unsignedName, desc);
        } else {
            codeBuilder.invokestatic(CD_MATH, signedName, desc);
        }
        return true;
    }

    private enum NumericOpcode {
        ADD,
        SUBTRACT,
        MULTIPLY,
        DIVIDE,
        REMAINDER
    }

    private void emitArithmeticOp(java.lang.classfile.TypeKind typeKind, NumericOpcode opcode) {
        switch (numericCarrier(typeKind)) {
            case INT -> {
                switch (opcode) {
                    case ADD -> codeBuilder.iadd();
                    case SUBTRACT -> codeBuilder.isub();
                    case MULTIPLY -> codeBuilder.imul();
                    case DIVIDE -> codeBuilder.idiv();
                    case REMAINDER -> codeBuilder.irem();
                }
            }
            case LONG -> {
                switch (opcode) {
                    case ADD -> codeBuilder.ladd();
                    case SUBTRACT -> codeBuilder.lsub();
                    case MULTIPLY -> codeBuilder.lmul();
                    case DIVIDE -> codeBuilder.ldiv();
                    case REMAINDER -> codeBuilder.lrem();
                }
            }
            case FLOAT -> {
                switch (opcode) {
                    case ADD -> codeBuilder.fadd();
                    case SUBTRACT -> codeBuilder.fsub();
                    case MULTIPLY -> codeBuilder.fmul();
                    case DIVIDE -> codeBuilder.fdiv();
                    case REMAINDER -> codeBuilder.frem();
                }
            }
            case DOUBLE -> {
                switch (opcode) {
                    case ADD -> codeBuilder.dadd();
                    case SUBTRACT -> codeBuilder.dsub();
                    case MULTIPLY -> codeBuilder.dmul();
                    case DIVIDE -> codeBuilder.ddiv();
                    case REMAINDER -> codeBuilder.drem();
                }
            }
            default -> throw new UnsupportedOperationException(
                    "Unsupported arithmetic type: " + typeKind);
        }
    }

    private static java.lang.classfile.TypeKind numericCarrier(java.lang.classfile.TypeKind typeKind) {
        return switch (typeKind) {
            case BYTE, SHORT, CHAR, INT -> java.lang.classfile.TypeKind.INT;
            case LONG -> java.lang.classfile.TypeKind.LONG;
            case FLOAT -> java.lang.classfile.TypeKind.FLOAT;
            case DOUBLE -> java.lang.classfile.TypeKind.DOUBLE;
            default -> throw new UnsupportedOperationException("Unsupported numeric type: " + typeKind);
        };
    }

    private static java.lang.classfile.TypeKind integralCarrier(java.lang.classfile.TypeKind typeKind) {
        return switch (typeKind) {
            case BYTE, SHORT, CHAR, INT -> java.lang.classfile.TypeKind.INT;
            case LONG -> java.lang.classfile.TypeKind.LONG;
            default -> throw new UnsupportedOperationException("Unsupported integral type: " + typeKind);
        };
    }

    private static boolean intSwitchCarrier(java.lang.classfile.TypeKind typeKind) {
        return switch (typeKind) {
            case BYTE, SHORT, CHAR, INT -> true;
            default -> false;
        };
    }

    private static java.lang.classfile.TypeKind toTypeKind(TypeSymbol type) {
        return switch (type.jvmTypeKind()) {
            case BYTE -> java.lang.classfile.TypeKind.BYTE;
            case SHORT -> java.lang.classfile.TypeKind.SHORT;
            case CHAR -> java.lang.classfile.TypeKind.CHAR;
            case INT -> java.lang.classfile.TypeKind.INT;
            case LONG -> java.lang.classfile.TypeKind.LONG;
            case FLOAT -> java.lang.classfile.TypeKind.FLOAT;
            case DOUBLE -> java.lang.classfile.TypeKind.DOUBLE;
            case VOID -> java.lang.classfile.TypeKind.VOID;
            case REFERENCE -> java.lang.classfile.TypeKind.REFERENCE;
            case BOOLEAN -> java.lang.classfile.TypeKind.BOOLEAN;
            default -> throw new UnsupportedOperationException("Unsupported jvmTypeKind: " + type.jvmTypeKind());
        };
    }
}
