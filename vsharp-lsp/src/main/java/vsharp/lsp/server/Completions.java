package vsharp.lsp.server;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.Map;
import java.util.stream.Stream;
import vsharp.compiler.semantics.binding.ExpressionBinding;
import vsharp.compiler.semantics.binding.JavaInterop;
import vsharp.compiler.semantics.binding.SemanticModel;
import vsharp.compiler.semantics.conversions.Conversions;
import vsharp.compiler.semantics.symbols.FieldSymbol;
import vsharp.compiler.semantics.symbols.FunctionSymbol;
import vsharp.compiler.semantics.symbols.LocalSymbol;
import vsharp.compiler.semantics.symbols.NamedTypeSymbol;
import vsharp.compiler.semantics.symbols.NamespaceSymbol;
import vsharp.compiler.semantics.symbols.ParameterSymbol;
import vsharp.compiler.semantics.types.BuiltinType;
import vsharp.compiler.semantics.types.TypeSymbol;
import vsharp.compiler.semantics.symbols.Symbol;
import vsharp.compiler.semantics.symbols.SymbolKind;
import vsharp.compiler.source.SourceFile;
import vsharp.compiler.syntax.SyntaxFacts;
import vsharp.compiler.syntax.SyntaxKind;
import vsharp.lsp.json.Json;

/// Builds `textDocument/completion` items.
///
/// The receiver-resolution helpers here are package-private rather than private because
/// [SignatureHelp] resolves the callee of a call the same way. Sharing them is the point: if
/// signature help resolved receivers by its own rules, the editor could offer a member in the
/// completion list and then fail to describe the very call the user built from it.
///
/// Two sources feed the list and neither is hand-written. Keywords are enumerated from
/// [SyntaxKind] and [SyntaxFacts], so the words the editor offers are by construction the
/// words the lexer accepts. Everything else comes from the [SemanticModel] of the current
/// run, which means completion shows what actually bound - including the compiler-shipped
/// corelib - rather than a curated guess at it.
final class Completions {

    /// LSP `CompletionItemKind` values. Spelled out because the protocol transmits bare
    /// integers and a wrong one silently shows the wrong icon.
    private static final int KIND_FUNCTION = 3;

    private static final int KIND_FIELD = 5;

    private static final int KIND_VARIABLE = 6;

    private static final int KIND_CLASS = 7;

    private static final int KIND_MODULE = 9;

    private static final int KIND_KEYWORD = 14;

    private static final int KIND_ENUM_MEMBER = 20;

    private static final int KIND_STRUCT = 22;

    private static final int KIND_TYPE_PARAMETER = 25;

    /// Sort groups. LSP orders by `sortText` lexically, so a leading digit pins the
    /// groups: locals and members first, then types, then keywords last. Without this the
    /// alphabetically unlucky - `abstract` before `args` - would bury what the user means.
    private static final String SORT_MEMBER = "1";

    private static final String SORT_LOCAL = "2";

    private static final String SORT_TYPE = "3";

    private static final String SORT_KEYWORD = "4";

    private Completions() {
        throw new AssertionError("No instances");
    }

    /// Completion items for `offset` in `file`.
    static List<Json> at(SourceFile file, int offset, Optional<SemanticModel> model,
            Optional<ExpressionBinding> binding, Optional<SourceFile> modelFile) {
        // After a dot the answer is members or nothing. Falling back to the unqualified list
        // when the receiver could not be read was the source of a report of "a lot of random
        // suggestions": `bytes[0].` offered 222 namespaces, types and keywords, none of which
        // can follow a dot. An empty list says "I do not know this receiver", which is true;
        // the global list says something false.
        if (isMemberAccess(file.text(), offset)) {
            return receiverBefore(file.text(), offset)
                    .map(receiver -> members(receiver, model, binding, file,
                            modelFile.orElse(file)))
                    .orElseGet(List::of);
        }
        List<Json> items = new ArrayList<>();
        model.ifPresent(m -> items.addAll(globals(m, file)));
        items.addAll(keywords());
        items.addAll(languageTypes());
        return items;
    }

    /// The receiver of a member access: a dotted name, and how many times it is indexed.
    ///
    /// `bytes` is `("bytes", 0)` and `bytes[0]` is `("bytes", 1)`, which is what lets the
    /// element type be taken for each indexing step.
    record Receiver(String name, int indexDepth) {}

    /// Whether the cursor sits immediately after a `.`, ignoring the word being typed.
    static boolean isMemberAccess(String text, int offset) {
        int index = Math.min(offset, text.length());
        while (index > 0 && isIdentifierPart(text.charAt(index - 1))) {
            index--;
        }
        return index > 0 && text.charAt(index - 1) == '.';
    }

    /// Reads the receiver expression to the left of the dot.
    ///
    /// Handles a dotted name with any number of trailing index operations, which is what a
    /// user writes constantly and what the previous version could not read at all. A receiver
    /// ending in a *call* - `Foo().` - yields empty rather than a guess: describing the
    /// members of a return type needs the call bound, and inventing an answer would be worse
    /// than admitting there is none.
    static Optional<Receiver> receiverBefore(String text, int offset) {
        int index = Math.min(offset, text.length());
        while (index > 0 && isIdentifierPart(text.charAt(index - 1))) {
            index--;
        }
        if (index == 0 || text.charAt(index - 1) != '.') {
            return Optional.empty();
        }
        int end = index - 1;
        int indexDepth = 0;
        while (true) {
            end = skipWhitespaceBack(text, end);
            if (end <= 0 || text.charAt(end - 1) != ']') {
                break;
            }
            int opening = matchingOpen(text, end - 1, '[', ']');
            if (opening < 0) {
                return Optional.empty();
            }
            end = opening;
            indexDepth++;
        }
        end = skipWhitespaceBack(text, end);
        if (end > 0 && text.charAt(end - 1) == ')') {
            // A call result; see above.
            return Optional.empty();
        }
        int start = end;
        while (start > 0) {
            char c = text.charAt(start - 1);
            if (isIdentifierPart(c) || c == '.') {
                start--;
                continue;
            }
            break;
        }
        String name = text.substring(start, end).trim();
        if (name.isEmpty() || name.endsWith(".") || name.startsWith(".")) {
            return Optional.empty();
        }
        return Optional.of(new Receiver(name, indexDepth));
    }

    private static int skipWhitespaceBack(String text, int index) {
        int at = index;
        while (at > 0 && Character.isWhitespace(text.charAt(at - 1))) {
            at--;
        }
        return at;
    }

    /// The index of the bracket opening the group that closes at `closing`.
    private static int matchingOpen(String text, int closing, char open, char close) {
        int depth = 0;
        for (int index = closing; index >= 0; index--) {
            char c = text.charAt(index);
            if (c == close) {
                depth++;
            } else if (c == open) {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
        }
        return -1;
    }

    private static boolean isIdentifierPart(char c) {
        return SyntaxFacts.isIdentifierPart(c);
    }

    /// Members offered after `qualifier` and a dot.
    ///
    /// Two different things can precede a dot and both must work. A *type* or namespace -
    /// `Console.`, `Math.` - offers its static members, and is resolved by name against the
    /// bound symbol table. A *value* - `obj.` where `var obj = "abc";` - offers the instance
    /// members of its type, and this is the case that matters most: it is what an editor user
    /// reaches for constantly, and answering nothing made the extension look dead even though
    /// every other feature worked.
    ///
    /// Resolving a value is a name lookup, not a reference resolution, for the same reason
    /// hover is: the compiler records no reference-to-symbol edges. A local declared once -
    /// overwhelmingly the common case - resolves exactly.
    private static List<Json> members(Receiver receiver, Optional<SemanticModel> model,
            Optional<ExpressionBinding> binding, SourceFile file, SourceFile modelFile) {
        if (model.isEmpty()) {
            return List.of();
        }
        SemanticModel bound = model.get();
        String qualifier = receiver.name();
        if (receiver.indexDepth() > 0) {
            // An indexed receiver is only ever a value, and each step peels one rank.
            return valueNamed(qualifier, bound, binding, file)
                    .map(type -> indexed(type, receiver.indexDepth()))
                    .map(type -> instanceMembersOf(type, bound))
                    .orElseGet(List::of);
        }
        // Each strategy is tried until one produces members. Returning the first strategy's
        // *empty* answer is what hid `Integer.`: the name also resolves as a declared symbol
        // whose scope lists nothing, so the module-path lookup below was never reached.
        Optional<Symbol> owner = typeOrNamespaceNamed(qualifier, bound);
        if (owner.isPresent()) {
            List<Json> statics = staticMembersOf(owner.get(), bound);
            if (!statics.isEmpty()) {
                return statics;
            }
        }
        Optional<List<Json>> value = valueNamed(qualifier, bound, binding, file)
                .map(type -> instanceMembersOf(type, bound));
        if (value.isPresent() && !value.get().isEmpty()) {
            return value.get();
        }
        // A Java type the program never mentioned is absent from the symbol table, so
        // `Integer.` found nothing even though `Integer.ParseInt("42")` compiles. Resolve it
        // the way the binder does - by simple name through the file's imports - and list its
        // statics.
        return javaTypeNamed(qualifier, bound, modelFile)
                .map(type -> javaMembers(type, type, bound, true))
                .orElseGet(List::of);
    }

    /// The type of `value[i]` applied `depth` times, or `null` when it is not indexable.
    ///
    /// An array peels one rank per step - `int[][]` indexes to `int[]`, not `int` - and a
    /// string indexes to `char`, matching what the binder does. Anything else has no indexer
    /// V# can resolve here, so the caller offers nothing rather than the wrong members.
    private static TypeSymbol indexed(TypeSymbol type, int depth) {
        TypeSymbol current = type;
        for (int step = 0; step < depth; step++) {
            if (current instanceof TypeSymbol.Array array) {
                current = array.elementType();
            } else if (current == BuiltinType.STRING) {
                current = BuiltinType.CHAR;
            } else {
                return null;
            }
        }
        return current;
    }

    /// A type, static container or namespace spelled `qualifier`.
    static Optional<Symbol> typeOrNamespaceNamed(String qualifier, SemanticModel model) {
        List<Symbol> symbols = model.symbols();
        return symbols.stream()
                .filter(s -> s.qualifiedName().equals(qualifier))
                .filter(Completions::isTypeLike)
                .findFirst()
                .or(() -> symbols.stream()
                        .filter(s -> s.name().equals(qualifier))
                        .filter(Completions::isTypeLike)
                        .findFirst());
    }

    private static boolean isTypeLike(Symbol symbol) {
        return symbol.kind() == SymbolKind.NAMED_TYPE
                || symbol.kind() == SymbolKind.STATIC_CONTAINER
                || symbol.kind() == SymbolKind.NAMESPACE;
    }

    /// A module-path type named `qualifier`, resolved fully qualified or through an import.
    static Optional<NamedTypeSymbol> javaTypeNamed(String qualifier, SemanticModel model,
            SourceFile file) {
        JavaInterop interop = model.javaInterop();
        NamedTypeSymbol direct = interop.resolveType(qualifier);
        if (direct != null) {
            return Optional.of(direct);
        }
        String alias = model.aliasTarget(file, qualifier);
        if (alias != null) {
            NamedTypeSymbol aliased = interop.resolveType(alias);
            if (aliased != null) {
                return Optional.of(aliased);
            }
        }
        for (String namespace : model.importedNamespaces(file)) {
            NamedTypeSymbol imported = interop.resolveType(namespace + "." + qualifier);
            if (imported != null) {
                return Optional.of(imported);
            }
        }
        return Optional.empty();
    }

    /// The type of a local, parameter or field spelled `qualifier`, preferring this file's.
    static Optional<TypeSymbol> valueNamed(String qualifier, SemanticModel model,
            Optional<ExpressionBinding> binding, SourceFile file) {
        List<Symbol> candidates = model.symbols().stream()
                .filter(s -> s.name().equals(qualifier))
                .filter(s -> s.kind() == SymbolKind.LOCAL || s.kind() == SymbolKind.PARAMETER
                        || s.kind() == SymbolKind.FIELD)
                .toList();
        Optional<Symbol> found = candidates.stream()
                .filter(s -> s.location().file().name().equals(file.name()))
                .findFirst()
                .or(() -> candidates.stream().findFirst());
        // `effectiveType` is the inferred type of a `var` local; the declared type is the
        // fallback for everything binding did not reach.
        return found.flatMap(symbol -> binding
                .flatMap(b -> b.effectiveType(symbol))
                .or(() -> Optional.ofNullable(typeOf(symbol))));
    }

    private static TypeSymbol typeOf(Symbol symbol) {
        return switch (symbol) {
            case LocalSymbol local -> local.type();
            case ParameterSymbol parameter -> parameter.type();
            case FieldSymbol field -> field.type();
            default -> null;
        };
    }

    /// The declared members one segment below `owner`, which is how a static surface reads.
    private static List<Json> staticMembersOf(Symbol owner, SemanticModel model) {
        String prefix = owner.qualifiedName() + ".";
        Set<String> seen = new LinkedHashSet<>();
        List<Json> items = new ArrayList<>();
        for (Symbol symbol : model.symbols()) {
            String qualified = symbol.qualifiedName();
            if (!qualified.startsWith(prefix)) {
                continue;
            }
            String remainder = qualified.substring(prefix.length());
            if (remainder.indexOf('.') >= 0) {
                continue;
            }
            if (seen.add(remainder + '#' + symbol.kind())) {
                items.add(item(remainder, kindOf(symbol.kind()), SORT_MEMBER, detailOf(symbol)));
            }
        }
        if (items.isEmpty() && owner instanceof NamedTypeSymbol named) {
            // A Java type declares nothing in the V# symbol table; its members come from the
            // class file on demand.
            items.addAll(javaMembers(named, named, model, true));
        }
        return items;
    }

    /// The instance members of `type`, wherever they are declared.
    ///
    /// A builtin resolves through its corelib declaration - `string` is `System.String`, whose
    /// curated members are the ones V# actually offers, not `java.lang.String`'s. A Java type
    /// resolves through the class file. A V# type resolves through its own scope.
    static List<Json> instanceMembersOf(TypeSymbol type, SemanticModel model) {
        List<Json> structural = structuralMembersOf(type);
        if (!structural.isEmpty()) {
            List<Json> withExtensions = new ArrayList<>(structural);
            withExtensions.addAll(extensionMembersOf(type, model));
            return withExtensions;
        }
        NamedTypeSymbol surface = memberSurfaceOf(type, model);
        List<Json> items = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (surface != null) {
            model.scopeOf(surface).ifPresent(scope -> {
                for (Symbol symbol : scope.symbols()) {
                    if (isStatic(symbol) || symbol.name().isEmpty()
                            || symbol.name().startsWith("<")) {
                        continue;
                    }
                    String spelling = spellingOf(symbol);
                    if (seen.add(spelling + '#' + symbol.kind())) {
                        items.add(item(spelling, kindOf(symbol.kind()), SORT_MEMBER,
                                detailOf(symbol)));
                    }
                }
            });
            if (items.isEmpty()) {
                items.addAll(javaMembers(surface, type, model, false));
            }
        }
        items.addAll(extensionMembersOf(type, model));
        return items;
    }

    /// The extension methods a receiver of `type` can reach, offered after the
    /// receiver's own members because that is the order the binder searches in.
    ///
    /// Completion lists every extension the compilation declares for this receiver, not only
    /// those the file has imported: an editor list is a suggestion, and a name the program
    /// has not imported yet is exactly what a developer is looking for when they type the
    /// dot. The binder still refuses an unimported one, so nothing here can make a wrong
    /// program compile.
    private static List<Json> extensionMembersOf(TypeSymbol type, SemanticModel model) {
        List<Json> items = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Symbol symbol : model.symbols()) {
            if (!(symbol instanceof FunctionSymbol function) || !function.isExtension()) {
                continue;
            }
            TypeSymbol extended = function.extendedType().orElseThrow();
            boolean accepts = !function.typeParameters().isEmpty()
                    || switch (Conversions.classify(type, extended).kind()) {
                        case IDENTITY, IMPLICIT_REFERENCE, BOXING -> true;
                        default -> false;
                    };
            if (accepts && seen.add(function.name() + '#' + function.signature())) {
                items.add(item(function.name(), kindOf(SymbolKind.FUNCTION), SORT_MEMBER,
                        detailOf(function) + " (extension)"));
            }
        }
        return items;
    }

    /// Members a type carries structurally rather than through a declaration.
    ///
    /// An array, a nullable and a tuple have no declaring class whose scope could be listed:
    /// the binder answers `Length`, `HasValue`/`Value` and the tuple's components as special
    /// cases, so completion has to mirror those rules or offer nothing. It offered nothing,
    /// which is why `nums.` on an `int[]` produced an empty list while `nums.Length` compiled
    /// perfectly well.
    private static List<Json> structuralMembersOf(TypeSymbol type) {
        if (type instanceof TypeSymbol.Array) {
            return List.of(item("Length", KIND_FIELD, SORT_MEMBER, "int - array length"));
        }
        if (type instanceof TypeSymbol.Tuple tuple) {
            List<Json> items = new ArrayList<>();
            for (int index = 0; index < tuple.elements().size(); index++) {
                TypeSymbol.TupleElement element = tuple.elements().get(index);
                // Both spellings bind: the positional `ItemN` always, and the declared name
                // when the tuple gave one.
                items.add(item("Item" + (index + 1), KIND_FIELD, SORT_MEMBER,
                        element.type().displayName()));
                if (element.name() != null && !element.name().isEmpty()) {
                    items.add(item(element.name(), KIND_FIELD, SORT_MEMBER,
                            element.type().displayName()));
                }
            }
            return items;
        }
        if (type instanceof TypeSymbol.Nullable nullable) {
            List<Json> items = new ArrayList<>();
            items.add(item("HasValue", KIND_FIELD, SORT_MEMBER, "bool"));
            items.add(item("Value", KIND_FIELD, SORT_MEMBER,
                    nullable.element().displayName()));
            return items;
        }
        return List.of();
    }

    /// Members read from a Java class file, specialised to the receiver's type arguments.
    private static List<Json> javaMembers(NamedTypeSymbol declaration, TypeSymbol receiver,
            SemanticModel model, boolean wantStatic) {
        JavaInterop interop = model.javaInterop();
        if (!interop.isJavaType(declaration)) {
            return List.of();
        }
        List<Json> items = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Symbol symbol : interop.specializeMembers(receiver, interop.getMembers(declaration))) {
            if (isStatic(symbol) != wantStatic || symbol.name().isEmpty()) {
                continue;
            }
            // Offered in the spelling V# source must use, not the JVM's. A Java class
            // publishes `size` and `isEmpty`; a V# program writes `Size()` and `IsEmpty()`
            //, so completing the Java spelling would insert code that does not
            // compile - worse than offering nothing.
            String spelling = spellingOf(symbol);
            if (seen.add(spelling + '#' + symbol.kind())) {
                items.add(item(spelling, kindOf(symbol.kind()), SORT_MEMBER, detailOf(symbol)));
            }
        }
        return items;
    }

    /// The declaration whose members answer a lookup on `type`.
    static NamedTypeSymbol memberSurfaceOf(TypeSymbol type, SemanticModel model) {
        if (type instanceof NamedTypeSymbol named) {
            return named;
        }
        if (type instanceof TypeSymbol.Nullable nullable) {
            return memberSurfaceOf(nullable.element(), model);
        }
        if (!(type instanceof BuiltinType builtin)) {
            return null;
        }
        String name = CORELIB_TYPE_NAMES.get(builtin);
        if (name == null) {
            return null;
        }
        return model.globalScope().lookup("System").stream()
                .filter(NamespaceSymbol.class::isInstance)
                .map(NamespaceSymbol.class::cast)
                .findFirst()
                .flatMap(model::scopeOf)
                .map(scope -> scope.lookupLocal(name))
                .stream()
                .flatMap(List::stream)
                .filter(NamedTypeSymbol.class::isInstance)
                .map(NamedTypeSymbol.class::cast)
                .findFirst()
                .orElse(null);
    }

    /// The spelling a V# program must write for `symbol`.
    ///
    /// A member discovered on the module path publishes its JVM name - `size`, `isEmpty`,
    /// `trimToSize` - while V# source must write `Size`, `IsEmpty`, `TrimToSize`.
    /// Completing the JVM spelling inserts code that does not compile, which is worse than
    /// offering nothing at all. A name with no case to convert, such as `MAX_VALUE`, is
    /// matched by its own spelling in both directions and is returned unchanged.
    static String spellingOf(Symbol symbol) {
        if (!JavaInterop.isModulePathSymbol(symbol)) {
            return symbol.name();
        }
        String converted = JavaInterop.vsharpMemberName(symbol.name());
        return converted == null ? symbol.name() : converted;
    }

    static boolean isStatic(Symbol symbol) {
        return switch (symbol) {
            case FunctionSymbol function -> function.modifiers().contains(SyntaxKind.STATIC);
            case FieldSymbol field -> field.modifiers().contains(SyntaxKind.STATIC);
            default -> false;
        };
    }

    /// The corelib declaration each builtin's members live on, mirroring the binder's own table
    /// so that `"abc".` offers what `"abc".Length` actually binds to.
    private static final Map<BuiltinType, String> CORELIB_TYPE_NAMES = corelibTypeNames();

    private static Map<BuiltinType, String> corelibTypeNames() {
        Map<BuiltinType, String> names = new java.util.EnumMap<>(BuiltinType.class);
        names.put(BuiltinType.SBYTE, "SByte");
        names.put(BuiltinType.BYTE, "Byte");
        names.put(BuiltinType.SHORT, "Int16");
        names.put(BuiltinType.USHORT, "UInt16");
        names.put(BuiltinType.INT, "Int32");
        names.put(BuiltinType.UINT, "UInt32");
        names.put(BuiltinType.LONG, "Int64");
        names.put(BuiltinType.ULONG, "UInt64");
        names.put(BuiltinType.NINT, "IntPtr");
        names.put(BuiltinType.NUINT, "UIntPtr");
        names.put(BuiltinType.DECIMAL, "Decimal");
        names.put(BuiltinType.FLOAT, "Single");
        names.put(BuiltinType.DOUBLE, "Double");
        names.put(BuiltinType.BOOL, "Boolean");
        names.put(BuiltinType.CHAR, "Char");
        names.put(BuiltinType.STRING, "String");
        names.put(BuiltinType.OBJECT, "Object");
        return Map.copyOf(names);
    }

    /// Everything nameable without a qualifier.
    ///
    /// Namespaces, types and functions come from the whole compilation, because a V#
    /// program may reference a type declared in a sibling file. Locals and parameters are
    /// restricted to the file being edited: offering another file's loop variable would be
    /// noise, and the compiler would reject every such completion anyway.
    private static List<Json> globals(SemanticModel model, SourceFile file) {
        List<Json> items = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Symbol symbol : model.symbols()) {
            if (symbol.name().isEmpty() || symbol.name().startsWith("<")) {
                continue;
            }
            // A *member* discovered on the module path is never nameable on its own: V# has no
            // unqualified `toString` or `serialVersionUID`, they are reached through a
            // receiver. Offering them put 907 unwritable names into a 1851-item list, most of
            // them in JVM spelling, which is both noise and an invitation to write code the
            // compiler refuses. Java *types* stay - `String`, `Integer` - because an
            // import does make those nameable, and they are already PascalCase.
            if (JavaInterop.isModulePathSymbol(symbol)
                    && (symbol.kind() == SymbolKind.FUNCTION
                        || symbol.kind() == SymbolKind.FIELD
                        || symbol.kind() == SymbolKind.ENUM_MEMBER)) {
                continue;
            }
            boolean fileLocal = symbol.kind() == SymbolKind.LOCAL
                    || symbol.kind() == SymbolKind.PARAMETER;
            if (fileLocal && !symbol.location().file().name().equals(file.name())) {
                continue;
            }
            String sort = switch (symbol.kind()) {
                case LOCAL, PARAMETER, FIELD, ENUM_MEMBER -> SORT_LOCAL;
                default -> SORT_TYPE;
            };
            // Defensive: anything that did come from the module path is offered in the
            // spelling V# source must use, never the JVM's.
            String spelling = spellingOf(symbol);
            if (seen.add(spelling + '#' + symbol.kind())) {
                items.add(item(spelling, kindOf(symbol.kind()), sort, detailOf(symbol)));
            }
        }
        return items;
    }

    /// Type names the language always provides, which no symbol in the model carries.
    ///
    /// `Task` is the V# spelling of `java.util.concurrent.Future`, resolved by the binder as
    /// an alias rather than declared anywhere, so it never appears in `model.symbols()`. The
    /// symbol table holds `Future` instead, and only once some file already mentions it -
    /// which means an author writing their first `async Task<int>` was offered the one
    /// spelling V# does not use and nothing for the one it does.
    static List<Json> languageTypes() {
        return List.of(item("Task", KIND_STRUCT, SORT_TYPE,
                "V# task type (java.util.concurrent.Future)"));
    }

    /// Every keyword the lexer knows, reserved and contextual.
    static List<Json> keywords() {
        return Stream.concat(
                        Stream.of(SyntaxKind.values())
                                .filter(SyntaxKind::isKeyword)
                                .map(kind -> kind.text().orElse(""))
                                .filter(text -> !text.isEmpty()),
                        SyntaxFacts.contextualKeywords().stream())
                .distinct()
                .sorted()
                .map(text -> item(text, KIND_KEYWORD, SORT_KEYWORD, "V# keyword"))
                .toList();
    }

    private static int kindOf(SymbolKind kind) {
        return switch (kind) {
            case NAMESPACE -> KIND_MODULE;
            case STATIC_CONTAINER -> KIND_CLASS;
            case NAMED_TYPE -> KIND_STRUCT;
            case FUNCTION -> KIND_FUNCTION;
            case TYPE_PARAMETER -> KIND_TYPE_PARAMETER;
            case PARAMETER, LOCAL -> KIND_VARIABLE;
            case FIELD -> KIND_FIELD;
            case ENUM_MEMBER -> KIND_ENUM_MEMBER;
        };
    }

    /// The right-hand hint shown beside an item: what the symbol is and where it lives.
    private static String detailOf(Symbol symbol) {
        String owner = symbol.qualifiedName();
        int lastDot = owner.lastIndexOf('.');
        String container = lastDot < 0 ? "" : owner.substring(0, lastDot);
        String label = switch (symbol.kind()) {
            case NAMESPACE -> "namespace";
            case STATIC_CONTAINER -> "static class";
            case NAMED_TYPE -> "type";
            case FUNCTION -> "function";
            case TYPE_PARAMETER -> "type parameter";
            case PARAMETER -> "parameter";
            case FIELD -> "field";
            case LOCAL -> "local";
            case ENUM_MEMBER -> "enum member";
        };
        return container.isEmpty() ? label : label + " · " + container;
    }

    private static Json item(String label, int kind, String sort, String detail) {
        return Json.object()
                .put("label", label)
                .put("kind", kind)
                .put("sortText", sort + label)
                .put("detail", detail)
                .build();
    }
}
