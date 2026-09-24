package vsharp.lsp.server;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import vsharp.compiler.semantics.binding.ExpressionBinding;
import vsharp.compiler.semantics.binding.JavaInterop;
import vsharp.compiler.semantics.binding.SemanticModel;
import vsharp.compiler.semantics.symbols.FunctionSymbol;
import vsharp.compiler.semantics.symbols.NamedTypeSymbol;
import vsharp.compiler.semantics.symbols.ParameterSymbol;
import vsharp.compiler.semantics.symbols.Symbol;
import vsharp.compiler.semantics.symbols.SymbolKind;
import vsharp.compiler.semantics.types.TypeSymbol;
import vsharp.compiler.source.SourceFile;
import vsharp.compiler.source.SourceSpan;
import vsharp.compiler.syntax.SyntaxKind;
import vsharp.lsp.json.Json;

/// Builds `textDocument/signatureHelp`: the parameter list of the call being written.
///
/// The callee is resolved through exactly the helpers [Completions] uses, so the two features
/// cannot disagree about what a receiver is - offering a member in the completion list and then
/// failing to describe the call built from it would be worse than offering neither.
///
/// Overloads are all returned, ordered by parameter count, and the one matching the number of
/// arguments written so far is marked active. That ordering is what makes the list usable
/// without any type-based overload resolution: the user is told which overloads exist and which
/// one their argument count currently selects, which is the question signature help answers.
final class SignatureHelp {

    private SignatureHelp() {
        throw new AssertionError("No instances");
    }

    /// Signature help at `offset`, or JSON `null` when the cursor is not inside a call whose
    /// callee resolves.
    static Json at(SourceFile file, int offset, Optional<SemanticModel> model,
            Optional<ExpressionBinding> binding, SourceFile modelFile) {
        Optional<Calls.CallSite> site = Calls.enclosing(file.text(), offset);
        if (site.isEmpty() || model.isEmpty()) {
            return Json.Null.INSTANCE;
        }
        Resolution resolved =
                resolve(site.get().callee(), model.get(), binding, file, modelFile);
        List<FunctionSymbol> overloads = resolved.overloads();
        if (overloads.isEmpty()) {
            return Json.Null.INSTANCE;
        }
        int active = site.get().activeParameter();
        List<Json> signatures = new ArrayList<>(overloads.size());
        for (int index = 0; index < overloads.size(); index++) {
            signatures.add(signatureOf(overloads.get(index), index, overloads.size(),
                    resolved.reduced()));
        }
        int chosen = activeSignature(overloads, active, resolved.reduced());
        return Json.object()
                .put("signatures", signatures)
                .put("activeSignature", chosen)
                .put("activeParameter", clampActive(overloads.get(chosen), active))
                .build();
    }

    /// The overload the written arguments currently select.
    ///
    /// An exact arity match wins, because that is the overload the user is completing. Failing
    /// that, the narrowest one that can still accept another argument - a `params` tail accepts
    /// any number - and failing that the widest, so someone who has typed too many arguments
    /// still sees the closest candidate rather than the narrowest.
    private static int activeSignature(List<FunctionSymbol> overloads, int active,
            boolean reduced) {
        int written = active + 1;
        int hidden = reduced ? 1 : 0;
        for (int index = 0; index < overloads.size(); index++) {
            if (overloads.get(index).parameters().size() - hidden == written) {
                return index;
            }
        }
        for (int index = 0; index < overloads.size(); index++) {
            FunctionSymbol function = overloads.get(index);
            if (function.parameters().size() - hidden > active || acceptsMore(function)) {
                return index;
            }
        }
        return Math.max(0, overloads.size() - 1);
    }

    /// Whether the last parameter is a `params` array, which absorbs any number of arguments.
    private static boolean acceptsMore(FunctionSymbol function) {
        return !function.parameters().isEmpty()
                && function.parameters().getLast().modifiers().contains(SyntaxKind.PARAMS);
    }

    /// Renders one overload, with the offsets of each parameter inside the rendered label.
    ///
    /// Offsets rather than parameter *names*: the protocol permits either, and a name would be
    /// matched by substring search, so a parameter called `n` would highlight the first `n`
    /// anywhere in the label - including inside the return type.
    ///
    /// The label is written the way the declaration is written, because that is what tells a
    /// user what they may type: passing modifiers (`ref`, `out`, `in`, `params`) appear because
    /// omitting one is a compile error, an optional parameter shows its actual default lifted
    /// from the declaring source, the owning type qualifies the name so overloads from
    /// different types are distinguishable, and type parameters are shown because a generic
    /// call may need them written.
    private static Json signatureOf(FunctionSymbol function, int index, int total,
            boolean reduced) {
        StringBuilder label = new StringBuilder();
        label.append(function.returnType().displayName()).append(' ');
        String owner = ownerOf(function);
        if (!owner.isEmpty()) {
            label.append(owner).append('.');
        }
        label.append(Completions.spellingOf(function));
        if (!function.typeParameters().isEmpty()) {
            label.append(function.typeParameters().stream()
                    .map(Symbol::name)
                    .collect(java.util.stream.Collectors.joining(", ", "<", ">")));
        }
        label.append('(');
        // A receiver-form call has already supplied the `this` parameter, so the signature
        // shown is the reduced one C# describes; otherwise every argument index would be one
        // past what the author is typing.
        int first = reduced ? 1 : 0;
        List<Json> parameters = new ArrayList<>(function.parameters().size());
        for (int position = first; position < function.parameters().size(); position++) {
            if (position > first) {
                label.append(", ");
            }
            int start = label.length();
            label.append(renderParameter(function.parameters().get(position)));
            parameters.add(Json.object()
                    .put("label", Json.array(List.of(
                            Json.Num.of(start), Json.Num.of(label.length()))))
                    .build());
        }
        label.append(')');
        Json.Obj.Builder signature = Json.object()
                .put("label", label.toString())
                .put("parameters", parameters);
        if (total > 1) {
            // Orientation: an eleven-overload list is otherwise an undifferentiated stack.
            signature.put("documentation", "Overload " + (index + 1) + " of " + total);
        }
        return signature.build();
    }

    /// One parameter, with its passing mode and default exactly as declared.
    private static String renderParameter(ParameterSymbol parameter) {
        StringBuilder text = new StringBuilder();
        for (SyntaxKind modifier : List.of(SyntaxKind.THIS, SyntaxKind.REF, SyntaxKind.OUT,
                SyntaxKind.IN, SyntaxKind.PARAMS)) {
            if (parameter.modifiers().contains(modifier)) {
                text.append(modifier.text().orElse("")).append(' ');
            }
        }
        text.append(parameter.type().displayName()).append(' ').append(parameter.name());
        defaultTextOf(parameter).ifPresent(value -> text.append(" = ").append(value));
        return text.toString();
    }

    /// The source text of an optional parameter's default.
    ///
    /// Read from the declaring file rather than reconstructed: the default is an expression,
    /// and re-rendering one would drift from what the author actually wrote. A Java method has
    /// no default and no V# source to read, so this is empty for those.
    private static Optional<String> defaultTextOf(ParameterSymbol parameter) {
        if (parameter.defaultValue() == null) {
            return Optional.empty();
        }
        SourceFile declaring = parameter.location().file();
        SourceSpan span = parameter.defaultValue().span();
        if (span.end() > declaring.length()) {
            return Optional.empty();
        }
        String text = declaring.textOf(span).trim();
        return text.isEmpty() ? Optional.empty() : Optional.of(text);
    }

    /// The simple name of the type or container declaring `function`.
    private static String ownerOf(FunctionSymbol function) {
        String qualified = function.qualifiedName();
        int lastDot = qualified.lastIndexOf('.');
        if (lastDot < 0) {
            return "";
        }
        String owner = qualified.substring(0, lastDot);
        int ownerDot = owner.lastIndexOf('.');
        return ownerDot < 0 ? owner : owner.substring(ownerDot + 1);
    }

    /// Keeps the highlighted parameter inside the chosen overload.
    ///
    /// Past the last parameter the editor highlights nothing, which reads as "this argument is
    /// wrong" rather than "there is no such parameter". A `params` tail genuinely does accept
    /// the extra arguments, so the highlight stays on it.
    private static int clampActive(FunctionSymbol function, int active) {
        int count = function.parameters().size();
        if (count == 0) {
            return 0;
        }
        return active < count ? active : (acceptsMore(function) ? count - 1 : active);
    }

    /// Every function the callee text could name, ordered by parameter count.
    /// The overloads a call site resolves to, and whether they were reached through a receiver
    /// that does not declare them - an extension call, whose first parameter the author has
    /// already supplied and must not be asked for again.
    private record Resolution(List<FunctionSymbol> overloads, boolean reduced) {
    }

    private static Resolution resolve(String callee, SemanticModel model,
            Optional<ExpressionBinding> binding, SourceFile file, SourceFile modelFile) {
        int lastDot = callee.lastIndexOf('.');
        String member = lastDot < 0 ? callee : callee.substring(lastDot + 1);
        String receiver = lastDot < 0 ? "" : callee.substring(0, lastDot);
        boolean reduced = false;
        List<FunctionSymbol> found = receiver.isEmpty()
                ? unqualified(member, model)
                : qualified(receiver, member, model, binding, file, modelFile);
        if (found.isEmpty() && !receiver.isEmpty()) {
            // The member is not on the receiver, which is exactly where the binder looks for an
            // extension method. Completion already offers them here; describing the call the
            // user then writes is the other half of that promise.
            Optional<TypeSymbol> value = Completions.valueNamed(receiver, model, binding, file);
            if (value.isPresent()) {
                found = extensions(value.get(), member, model);
                reduced = !found.isEmpty();
            }
        }
        // A function declared in this file wins over one of the same name elsewhere. An
        // unqualified call resolves in lexical scope, so a program that declares its own
        // `TryParse` means that one - corelib's four overloads of the same name are not
        // candidates the user was choosing between.
        String here = file.name();
        return new Resolution(found.stream()
                .distinct()
                .sorted(Comparator
                        .comparing((FunctionSymbol f) ->
                                f.location().file().name().equals(here) ? 0 : 1)
                        .thenComparingInt(f -> f.parameters().size()))
                .toList(), reduced);
    }

    /// The extension methods named `member` that a receiver of `type` can reach.
    ///
    /// Candidacy uses the same conversion test the binder applies - identity, reference or
    /// boxing - with a generic extension offered unconditionally, because inference and not
    /// signature help decides whether it fits.
    private static List<FunctionSymbol> extensions(TypeSymbol type, String member,
            SemanticModel model) {
        List<FunctionSymbol> found = new ArrayList<>();
        for (Symbol symbol : model.symbols()) {
            if (!(symbol instanceof FunctionSymbol function) || !function.isExtension()
                    || !function.name().equals(member)) {
                continue;
            }
            TypeSymbol extended = function.extendedType().orElseThrow();
            boolean accepts = !function.typeParameters().isEmpty()
                    || switch (vsharp.compiler.semantics.conversions.Conversions
                            .classify(type, extended).kind()) {
                        case IDENTITY, IMPLICIT_REFERENCE, BOXING -> true;
                        default -> false;
                    };
            if (accepts) {
                found.add(function);
            }
        }
        return found;
    }

    /// A call with no receiver: a function declared in this program.
    private static List<FunctionSymbol> unqualified(String member, SemanticModel model) {
        return model.symbols().stream()
                .filter(s -> s.kind() == SymbolKind.FUNCTION)
                .filter(s -> s.name().equals(member))
                .map(FunctionSymbol.class::cast)
                .toList();
    }

    /// A call through a receiver, which is either a type - static members - or a value.
    private static List<FunctionSymbol> qualified(String receiver, String member,
            SemanticModel model, Optional<ExpressionBinding> binding, SourceFile file,
            SourceFile modelFile) {
        Optional<Symbol> owner = Completions.typeOrNamespaceNamed(receiver, model);
        if (owner.isPresent()) {
            List<FunctionSymbol> declared = declaredUnder(owner.get(), member, model);
            if (!declared.isEmpty()) {
                return declared;
            }
            if (owner.get() instanceof NamedTypeSymbol named) {
                List<FunctionSymbol> java = javaMembers(named, named, member, model, true);
                if (!java.isEmpty()) {
                    return java;
                }
            }
        }
        Optional<TypeSymbol> value = Completions.valueNamed(receiver, model, binding, file);
        if (value.isPresent()) {
            List<FunctionSymbol> instance = instanceMembers(value.get(), member, model);
            if (!instance.isEmpty()) {
                return instance;
            }
        }
        // A Java type named by a simple name is absent from the symbol table until something
        // mentions it, exactly as in completion.
        return Completions.javaTypeNamed(receiver, model, modelFile)
                .map(type -> javaMembers(type, type, member, model, true))
                .orElseGet(List::of);
    }

    /// Functions declared one segment below `owner`.
    private static List<FunctionSymbol> declaredUnder(Symbol owner, String member,
            SemanticModel model) {
        String qualified = owner.qualifiedName() + "." + member;
        return model.symbols().stream()
                .filter(s -> s.kind() == SymbolKind.FUNCTION)
                .filter(s -> s.qualifiedName().equals(qualified))
                .map(FunctionSymbol.class::cast)
                .toList();
    }

    /// Instance functions of a value's type, from its declaration or its class file.
    private static List<FunctionSymbol> instanceMembers(TypeSymbol type, String member,
            SemanticModel model) {
        NamedTypeSymbol surface = Completions.memberSurfaceOf(type, model);
        if (surface == null) {
            return List.of();
        }
        List<FunctionSymbol> declared = model.scopeOf(surface)
                .map(scope -> scope.lookupLocal(member).stream()
                        .filter(FunctionSymbol.class::isInstance)
                        .map(FunctionSymbol.class::cast)
                        .filter(function -> !Completions.isStatic(function))
                        .toList())
                .orElseGet(List::of);
        return declared.isEmpty() ? javaMembers(surface, type, member, model, false) : declared;
    }

    /// Functions read from a class file, matched on the V# spelling the source must write.
    private static List<FunctionSymbol> javaMembers(NamedTypeSymbol declaration,
            TypeSymbol receiver, String member, SemanticModel model, boolean wantStatic) {
        JavaInterop interop = model.javaInterop();
        if (!interop.isJavaType(declaration)) {
            return List.of();
        }
        List<FunctionSymbol> found = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Symbol symbol : interop.specializeMembers(receiver,
                interop.getMembers(declaration))) {
            if (!(symbol instanceof FunctionSymbol function)
                    || Completions.isStatic(function) != wantStatic) {
                continue;
            }
            if (!Completions.spellingOf(function).equals(member)) {
                continue;
            }
            if (seen.add(function.signature())) {
                found.add(function);
            }
        }
        return found;
    }
}
