package vsharp.compiler.syntax;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import vsharp.compiler.diagnostics.DiagnosticBag;
import vsharp.compiler.diagnostics.DiagnosticCode;
import vsharp.compiler.source.SourceFile;
import vsharp.compiler.source.SourceSpan;

/// Hand-written recursive-descent and precedence parser for the V# subset of C# 13.
///
/// The parser is deliberately lossless about language structure but not punctuation:
/// immutable nodes retain spans and semantic spellings while delimiters are validated and
/// discarded. Every recovery path either consumes a token or inserts one zero-width
/// missing token, and every enclosing list has a progress guard. Malformed input therefore
/// produces a shaped tree and deterministic diagnostics instead of looping or throwing.
public final class Parser {

    private static final Set<SyntaxKind> PREDEFINED_TYPES = EnumSet.of(
            SyntaxKind.BOOL, SyntaxKind.BYTE, SyntaxKind.SBYTE, SyntaxKind.SHORT,
            SyntaxKind.USHORT, SyntaxKind.INT, SyntaxKind.UINT, SyntaxKind.LONG,
            SyntaxKind.ULONG, SyntaxKind.CHAR, SyntaxKind.FLOAT, SyntaxKind.DOUBLE,
            SyntaxKind.DECIMAL, SyntaxKind.STRING, SyntaxKind.OBJECT, SyntaxKind.VOID);

    private static final Set<SyntaxKind> MODIFIERS = EnumSet.of(
            SyntaxKind.PUBLIC, SyntaxKind.PRIVATE, SyntaxKind.PROTECTED,
            SyntaxKind.INTERNAL, SyntaxKind.STATIC, SyntaxKind.READONLY,
            SyntaxKind.CONST, SyntaxKind.VOLATILE, SyntaxKind.EXTERN,
            SyntaxKind.SEALED, SyntaxKind.ABSTRACT, SyntaxKind.VIRTUAL,
            SyntaxKind.OVERRIDE, SyntaxKind.UNSAFE);

    private static final Set<SyntaxKind> ASSIGNMENTS = EnumSet.of(
            SyntaxKind.EQUALS, SyntaxKind.PLUS_EQUALS, SyntaxKind.MINUS_EQUALS,
            SyntaxKind.ASTERISK_EQUALS, SyntaxKind.SLASH_EQUALS,
            SyntaxKind.PERCENT_EQUALS, SyntaxKind.AMPERSAND_EQUALS,
            SyntaxKind.BAR_EQUALS, SyntaxKind.CARET_EQUALS,
            SyntaxKind.QUESTION_QUESTION_EQUALS, SyntaxKind.LESS_THAN_LESS_THAN_EQUALS);

    private final SourceFile file;
    private final DiagnosticBag diagnostics;
    private final List<SyntaxToken> tokens;
    private final List<AuxiliarySyntax.Directive> directives = new ArrayList<>();

    /// Start offsets of the opening braces of the three constructs C# writes as a brace
    /// *inside an expression or pattern* - array initializers, property subpatterns and
    /// `with` initializers. Only the parser knows a brace's grammatical role, and the
    /// layout validator is a token pass that cannot recover it, so the role is published
    /// here rather than re-derived by heuristics. See [SourceStyleValidator].
    private final List<Integer> inlineBraces = new ArrayList<>();

    private int index;


    /// True while parsing a pattern in a position where a following `when` introduces a
    /// case guard rather than a designation (a `switch` label or a switch-expression arm).
    /// Nested subpatterns reset it, because a closing delimiter always separates them from
    /// the guard, so `case (int when):` still binds a variable named `when`.
    private boolean whenIsKeyword;

    private Parser(SourceFile file, DiagnosticBag diagnostics, List<SyntaxToken> tokens) {
        this.file = Objects.requireNonNull(file, "file");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
        this.tokens = List.copyOf(tokens);
        if (this.tokens.isEmpty()
                || !this.tokens.getLast().is(SyntaxKind.END_OF_FILE)) {
            throw new IllegalArgumentException("a parser token stream must end at EOF");
        }
    }

    /// Parses a complete source file, adding lexical and syntactic diagnostics to `bag`.
    public static AuxiliarySyntax.CompilationUnit parse(SourceFile file, DiagnosticBag bag) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(bag, "bag");
        return parse(file, Set.of(), bag);
    }

    /// Parses a complete file with command-line preprocessing symbols.
    public static AuxiliarySyntax.CompilationUnit parse(SourceFile file, Set<String> symbols,
            DiagnosticBag bag) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(symbols, "symbols");
        Objects.requireNonNull(bag, "bag");
        Preprocessor.Result preprocessed = Preprocessor.process(file, symbols, bag);
        Parser parser = new Parser(file, bag,
                Lexer.tokenizeMasked(file, preprocessed.maskedText(), bag));
        parser.directives.addAll(preprocessed.directives());
        return parser.parseCompilationUnit();
    }

    /// Parses one expression followed by end-of-file. Primarily useful to embedders and
    /// focused syntax tests; a trailing token is diagnosed.
    public static ExpressionSyntax parseExpression(SourceFile file, DiagnosticBag bag) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(bag, "bag");
        Parser parser = new Parser(file, bag, Lexer.tokenize(file, bag));
        ExpressionSyntax result = parser.parseExpression();
        if (!parser.at(SyntaxKind.END_OF_FILE)) {
            parser.report(DiagnosticCode.UNEXPECTED_TOKEN, parser.current().span());
        }
        return result;
    }

    // ---- Token cursor and diagnostics -------------------------------------------------

    private SyntaxToken current() {
        return peek(0);
    }

    private SyntaxToken peek(int offset) {
        return tokens.get(Math.min(index + offset, tokens.size() - 1));
    }

    private boolean at(SyntaxKind kind) {
        return current().is(kind);
    }

    private boolean atContextual(String text) {
        return at(SyntaxKind.IDENTIFIER) && current().text().equals(text);
    }

    private boolean peekContextual(int offset, String text) {
        SyntaxToken token = peek(offset);
        return token.is(SyntaxKind.IDENTIFIER) && token.text().equals(text);
    }

    private SyntaxToken take() {
        SyntaxToken result = current();
        if (!result.is(SyntaxKind.END_OF_FILE)) {
            index++;
        }
        return result;
    }

    private SyntaxToken expect(SyntaxKind kind) {
        if (at(kind)) {
            return take();
        }
        DiagnosticCode code = switch (kind) {
            case IDENTIFIER -> DiagnosticCode.IDENTIFIER_EXPECTED;
            case SEMICOLON -> DiagnosticCode.SEMICOLON_EXPECTED;
            default -> DiagnosticCode.TOKEN_EXPECTED;
        };
        SourceSpan point = SourceSpan.at(current().span().start());
        if (code == DiagnosticCode.TOKEN_EXPECTED) {
            diagnostics.report(code, file, point, "'" + kind.display() + "'");
        } else {
            diagnostics.report(code, file, point);
        }
        return SyntaxToken.missing(kind, point.start());
    }

    private void report(DiagnosticCode code, SourceSpan span, Object... arguments) {
        diagnostics.report(code, file, span, arguments);
    }

    private int mark() {
        return index;
    }

    private SourceSpan finish(int start) {
        int end = index == 0 ? start : tokens.get(Math.min(index - 1, tokens.size() - 1)).span().end();
        return SourceSpan.between(start, Math.max(start, end));
    }

    private static SourceSpan covering(int start, SyntaxNode node) {
        return SourceSpan.between(start, Math.max(start, node.span().end()));
    }

    /// Consumes an opening brace that the grammar writes inside an expression or a pattern,
    /// recording its role so the layout validator can exempt the pair from the statement-level
    /// Allman rule when it is written on one line.
    private void takeInlineBrace() {
        if (at(SyntaxKind.OPEN_BRACE)) {
            inlineBraces.add(current().span().start());
        }
        expect(SyntaxKind.OPEN_BRACE);
    }

    private void ensureProgress(int before) {
        if (index == before && !at(SyntaxKind.END_OF_FILE)) {
            take();
        }
    }

    // ---- Compilation units and directives --------------------------------------------

    private AuxiliarySyntax.CompilationUnit parseCompilationUnit() {
        List<AuxiliarySyntax.UsingDirective> usings = new ArrayList<>();
        List<DeclarationSyntax> declarations = new ArrayList<>();
        List<StatementSyntax> statements = new ArrayList<>();
        while (!at(SyntaxKind.END_OF_FILE)) {
            int before = mark();
            if (at(SyntaxKind.HASH)) {
                directives.add(parseDirective());
            } else if (startsUsingDirective()) {
                usings.add(parseUsingDirective());
            } else if (at(SyntaxKind.NAMESPACE)) {
                declarations.add(parseNamespace());
            } else if (startsTypeDeclaration()) {
                declarations.add(parseTypeDeclaration());
            } else {
                statements.add(parseStatement());
            }
            ensureProgress(before);
        }
        return new AuxiliarySyntax.CompilationUnit(
                new SourceSpan(0, file.length()), directives, usings, declarations, statements,
                inlineBraces);
    }

    private AuxiliarySyntax.Directive parseDirective() {
        int start = expect(SyntaxKind.HASH).span().start();
        if (!current().atStartOfLine() && index > 1
                && tokens.get(index - 1).span().end() != current().span().start()) {
            // A directive keyword follows its hash on the same line, so atStartOfLine is
            // normally false. Placement of the hash itself was retained on the token.
        }
        SyntaxToken keyword = current();
        if (keyword.is(SyntaxKind.END_OF_FILE) || keyword.atStartOfLine()) {
            report(DiagnosticCode.INVALID_PREPROCESSOR_DIRECTIVE, SourceSpan.at(keyword.span().start()));
            return new AuxiliarySyntax.Directive(SourceSpan.at(start),
                    AuxiliarySyntax.Directive.Kind.UNKNOWN, "");
        }
        take();
        String spelling = keyword.text().toLowerCase(Locale.ROOT);
        AuxiliarySyntax.Directive.Kind kind = switch (spelling) {
            case "define" -> AuxiliarySyntax.Directive.Kind.DEFINE;
            case "undef" -> AuxiliarySyntax.Directive.Kind.UNDEF;
            case "if" -> AuxiliarySyntax.Directive.Kind.IF;
            case "elif" -> AuxiliarySyntax.Directive.Kind.ELIF;
            case "else" -> AuxiliarySyntax.Directive.Kind.ELSE;
            case "endif" -> AuxiliarySyntax.Directive.Kind.ENDIF;
            case "region" -> AuxiliarySyntax.Directive.Kind.REGION;
            case "endregion" -> AuxiliarySyntax.Directive.Kind.ENDREGION;
            case "error" -> AuxiliarySyntax.Directive.Kind.ERROR;
            case "warning" -> AuxiliarySyntax.Directive.Kind.WARNING;
            case "line" -> AuxiliarySyntax.Directive.Kind.LINE;
            case "nullable" -> AuxiliarySyntax.Directive.Kind.NULLABLE;
            case "pragma" -> AuxiliarySyntax.Directive.Kind.PRAGMA;
            default -> AuxiliarySyntax.Directive.Kind.UNKNOWN;
        };
        if (kind == AuxiliarySyntax.Directive.Kind.UNKNOWN) {
            report(DiagnosticCode.INVALID_PREPROCESSOR_DIRECTIVE, keyword.span());
        }
        int argumentStart = keyword.span().end();
        int end = argumentStart;
        while (!at(SyntaxKind.END_OF_FILE) && !current().atStartOfLine()) {
            end = take().span().end();
        }
        String argument = file.text().substring(argumentStart, end).strip();
        return new AuxiliarySyntax.Directive(SourceSpan.between(start, end), kind, argument);
    }

    private boolean startsUsingDirective() {
        if (atContextual("global") && peek(1).is(SyntaxKind.USING)) {
            return true;
        }
        if (!at(SyntaxKind.USING) || peek(1).is(SyntaxKind.OPEN_PAREN)) {
            return false;
        }
        if (peek(1).is(SyntaxKind.STATIC)) {
            return true;
        }
        int typeEnd = scanType(index + 1);
        if (typeEnd < 0) {
            return false;
        }
        // `using T value = ...` is a declaration; aliases use `=` immediately after
        // their first identifier and ordinary imports end after one qualified name.
        return tokenAt(typeEnd).is(SyntaxKind.SEMICOLON)
                || (peek(1).is(SyntaxKind.IDENTIFIER) && peek(2).is(SyntaxKind.EQUALS));
    }

    private AuxiliarySyntax.UsingDirective parseUsingDirective() {
        int start = current().span().start();
        boolean global = false;
        if (atContextual("global")) {
            global = true;
            take();
        }
        expect(SyntaxKind.USING);
        boolean isStatic = false;
        if (at(SyntaxKind.STATIC)) {
            isStatic = true;
            take();
        }
        String alias = null;
        if (at(SyntaxKind.IDENTIFIER) && peek(1).is(SyntaxKind.EQUALS)) {
            alias = take().text();
            take();
        }
        TypeSyntax target = parseType();
        TypeSyntax.Name name = switch (target) {
            case TypeSyntax.Name value -> value;
            default -> {
                report(DiagnosticCode.TYPE_EXPECTED, target.span());
                yield TypeSyntax.Name.of(target.span(), "<missing>");
            }
        };
        expect(SyntaxKind.SEMICOLON);
        return new AuxiliarySyntax.UsingDirective(finish(start), global, isStatic, alias, name);
    }

    // ---- Declarations -----------------------------------------------------------------

    private boolean startsTypeDeclaration() {
        int cursor = index;
        while (tokenAt(cursor).is(SyntaxKind.OPEN_BRACKET)) {
            cursor = skipBalanced(cursor, SyntaxKind.OPEN_BRACKET, SyntaxKind.CLOSE_BRACKET);
        }
        while (MODIFIERS.contains(tokenAt(cursor).kind())
                || isContextualAt(cursor, "partial") || isContextualAt(cursor, "file")) {
            cursor++;
        }
        SyntaxToken token = tokenAt(cursor);
        return token.is(SyntaxKind.ENUM) || token.is(SyntaxKind.STRUCT)
                || token.is(SyntaxKind.CLASS) || token.is(SyntaxKind.INTERFACE)
                || token.is(SyntaxKind.DELEGATE)
                || (isContextualAt(cursor, "record")
                        && tokenAt(cursor + 1).is(SyntaxKind.STRUCT));
    }

    private DeclarationSyntax parseTypeDeclaration() {
        int start = current().span().start();
        List<AuxiliarySyntax.AttributeList> attributes = parseAttributeLists();
        List<SyntaxKind> modifiers = parseModifiers();
        if (atContextual("record") && peek(1).is(SyntaxKind.STRUCT)) {
            take();
            take();
            return parseStructTail(start, attributes, modifiers, true);
        }
        if (at(SyntaxKind.CLASS) && modifiers.contains(SyntaxKind.STATIC)) {
            take();
            return parseStaticContainerTail(start, attributes, modifiers);
        }
        return switch (current().kind()) {
            case ENUM -> {
                take();
                yield parseEnumTail(start, attributes, modifiers);
            }
            case STRUCT -> {
                take();
                yield parseStructTail(start, attributes, modifiers, false);
            }
            case CLASS, INTERFACE, DELEGATE -> parseUnsupportedDeclaration(start);
            default -> {
                report(DiagnosticCode.DECLARATION_EXPECTED, current().span(), current().text());
                SyntaxToken bad = take();
                yield new DeclarationSyntax.Unsupported(finish(start), bad.kind(), null);
            }
        };
    }

    private DeclarationSyntax parseUnsupportedDeclaration(int start) {
        SyntaxToken introducer = take();
        report(DiagnosticCode.OBJECT_MODEL_UNSUPPORTED, introducer.span(), introducer.text());
        String name = at(SyntaxKind.IDENTIFIER) ? take().text() : null;
        skipDeclarationRemainder();
        return new DeclarationSyntax.Unsupported(finish(start), introducer.kind(), name);
    }

    private void skipDeclarationRemainder() {
        while (!at(SyntaxKind.END_OF_FILE) && !at(SyntaxKind.SEMICOLON)
                && !at(SyntaxKind.OPEN_BRACE) && !at(SyntaxKind.CLOSE_BRACE)) {
            take();
        }
        if (at(SyntaxKind.OPEN_BRACE)) {
            int depth = 0;
            do {
                if (at(SyntaxKind.OPEN_BRACE)) {
                    depth++;
                } else if (at(SyntaxKind.CLOSE_BRACE)) {
                    depth--;
                }
                take();
            } while (depth > 0 && !at(SyntaxKind.END_OF_FILE));
        } else if (at(SyntaxKind.SEMICOLON)) {
            take();
        }
    }

    private DeclarationSyntax.Namespace parseNamespace() {
        int start = expect(SyntaxKind.NAMESPACE).span().start();
        TypeSyntax.Name name = parseQualifiedName();
        if (at(SyntaxKind.SEMICOLON)) {
            take();
            NamespaceContents contents = parseNamespaceContents(SyntaxKind.END_OF_FILE);
            return new DeclarationSyntax.Namespace(finish(start), name, true,
                    contents.usings(), contents.declarations(), contents.statements());
        }
        expect(SyntaxKind.OPEN_BRACE);
        NamespaceContents contents = parseNamespaceContents(SyntaxKind.CLOSE_BRACE);
        expect(SyntaxKind.CLOSE_BRACE);
        if (at(SyntaxKind.SEMICOLON)) {
            take();
        }
        return new DeclarationSyntax.Namespace(finish(start), name, false,
                contents.usings(), contents.declarations(), contents.statements());
    }

    private NamespaceContents parseNamespaceContents(SyntaxKind terminator) {
        List<AuxiliarySyntax.UsingDirective> usings = new ArrayList<>();
        List<DeclarationSyntax> declarations = new ArrayList<>();
        List<StatementSyntax> statements = new ArrayList<>();
        while (!at(terminator) && !at(SyntaxKind.END_OF_FILE)) {
            int before = mark();
            if (at(SyntaxKind.HASH)) {
                directives.add(parseDirective());
            } else if (startsUsingDirective()) {
                usings.add(parseUsingDirective());
            } else if (at(SyntaxKind.NAMESPACE)) {
                declarations.add(parseNamespace());
            } else if (startsTypeDeclaration()) {
                declarations.add(parseTypeDeclaration());
            } else {
                report(DiagnosticCode.DECLARATION_EXPECTED, current().span(), current().text());
                statements.add(parseStatement());
            }
            ensureProgress(before);
        }
        return new NamespaceContents(usings, declarations, statements);
    }

    private record NamespaceContents(List<AuxiliarySyntax.UsingDirective> usings,
            List<DeclarationSyntax> declarations, List<StatementSyntax> statements) { }

    private DeclarationSyntax.StaticContainer parseStaticContainerTail(int start,
            List<AuxiliarySyntax.AttributeList> attributes, List<SyntaxKind> modifiers) {
        String name = expect(SyntaxKind.IDENTIFIER).text();
        List<AuxiliarySyntax.TypeParameter> typeParameters = parseTypeParameters();
        List<AuxiliarySyntax.ConstraintClause> constraints = parseConstraints();
        expect(SyntaxKind.OPEN_BRACE);
        List<DeclarationSyntax> members = parseTypeMembers(name);
        expect(SyntaxKind.CLOSE_BRACE);
        if (at(SyntaxKind.SEMICOLON)) {
            take();
        }
        return new DeclarationSyntax.StaticContainer(finish(start), attributes, modifiers, name,
                typeParameters, constraints, members);
    }

    private DeclarationSyntax.Struct parseStructTail(int start,
            List<AuxiliarySyntax.AttributeList> attributes, List<SyntaxKind> modifiers,
            boolean record) {
        String name = expect(SyntaxKind.IDENTIFIER).text();
        List<AuxiliarySyntax.TypeParameter> typeParameters = parseTypeParameters();
        List<AuxiliarySyntax.Parameter> primary = at(SyntaxKind.OPEN_PAREN)
                ? parseParameterList(false) : List.of();
        if (at(SyntaxKind.COLON)) {
            SyntaxToken colon = take();
            report(DiagnosticCode.OBJECT_MODEL_UNSUPPORTED, colon.span(),
                    "struct interface implementation");
            do {
                parseType();
            } while (at(SyntaxKind.COMMA) && take() != null);
        }
        List<AuxiliarySyntax.ConstraintClause> constraints = parseConstraints();
        List<DeclarationSyntax> members = List.of();
        if (at(SyntaxKind.SEMICOLON)) {
            take();
        } else {
            expect(SyntaxKind.OPEN_BRACE);
            members = parseTypeMembers(name);
            expect(SyntaxKind.CLOSE_BRACE);
            if (at(SyntaxKind.SEMICOLON)) {
                take();
            }
        }
        return new DeclarationSyntax.Struct(finish(start), attributes, modifiers, record, name,
                typeParameters, primary, constraints, members);
    }

    private DeclarationSyntax.Enum parseEnumTail(int start,
            List<AuxiliarySyntax.AttributeList> attributes, List<SyntaxKind> modifiers) {
        String name = expect(SyntaxKind.IDENTIFIER).text();
        TypeSyntax underlying = null;
        if (at(SyntaxKind.COLON)) {
            take();
            underlying = parseType();
        }
        expect(SyntaxKind.OPEN_BRACE);
        List<AuxiliarySyntax.EnumMember> members = new ArrayList<>();
        while (!at(SyntaxKind.CLOSE_BRACE) && !at(SyntaxKind.END_OF_FILE)) {
            int memberStart = current().span().start();
            List<AuxiliarySyntax.AttributeList> memberAttributes = parseAttributeLists();
            String memberName = expect(SyntaxKind.IDENTIFIER).text();
            ExpressionSyntax value = null;
            if (at(SyntaxKind.EQUALS)) {
                take();
                value = parseExpression();
            }
            members.add(new AuxiliarySyntax.EnumMember(finish(memberStart), memberAttributes,
                    memberName, value));
            if (!at(SyntaxKind.COMMA)) {
                break;
            }
            take();
        }
        expect(SyntaxKind.CLOSE_BRACE);
        if (at(SyntaxKind.SEMICOLON)) {
            take();
        }
        return new DeclarationSyntax.Enum(finish(start), attributes, modifiers, name,
                underlying, members);
    }

    private List<DeclarationSyntax> parseTypeMembers(String containerName) {
        List<DeclarationSyntax> members = new ArrayList<>();
        while (!at(SyntaxKind.CLOSE_BRACE) && !at(SyntaxKind.END_OF_FILE)) {
            int before = mark();
            if (at(SyntaxKind.HASH)) {
                directives.add(parseDirective());
            } else if (startsTypeDeclaration()) {
                members.add(parseTypeDeclaration());
            } else {
                members.add(parseMember(containerName));
            }
            ensureProgress(before);
        }
        return List.copyOf(members);
    }

    private DeclarationSyntax parseMember(String containerName) {
        int start = current().span().start();
        List<AuxiliarySyntax.AttributeList> attributes = parseAttributeLists();
        List<SyntaxKind> modifiers = parseModifiers();
        if (at(SyntaxKind.IDENTIFIER) && current().text().equals(containerName)
                && peek(1).is(SyntaxKind.OPEN_PAREN)) {
            SyntaxToken constructor = take();
            report(DiagnosticCode.OBJECT_MODEL_UNSUPPORTED, constructor.span(), "constructor");
            skipDeclarationRemainder();
            return new DeclarationSyntax.Unsupported(finish(start), SyntaxKind.NEW,
                    constructor.text());
        }
        if (at(SyntaxKind.IMPLICIT) || at(SyntaxKind.EXPLICIT)) {
            return parseConversionOperator(start, attributes, modifiers);
        }
        TypeSyntax type = parseType();
        if (at(SyntaxKind.OPERATOR)) {
            return parseOperator(start, attributes, modifiers, type);
        }
        String name = expect(SyntaxKind.IDENTIFIER).text();
        List<AuxiliarySyntax.TypeParameter> typeParameters = parseTypeParameters();
        if (at(SyntaxKind.OPEN_PAREN)) {
            List<AuxiliarySyntax.Parameter> parameters = parseParameterList(false);
            List<AuxiliarySyntax.ConstraintClause> constraints = parseConstraints();
            CallableBody body = parseCallableBody();
            return new DeclarationSyntax.Method(finish(start), attributes, modifiers, type, name,
                    typeParameters, parameters, constraints, body.block(), body.expression());
        }
        if (at(SyntaxKind.OPEN_BRACE)) {
            SyntaxToken brace = current();
            report(DiagnosticCode.OBJECT_MODEL_UNSUPPORTED, brace.span(), "property or indexer");
            skipDeclarationRemainder();
            return new DeclarationSyntax.Unsupported(finish(start), SyntaxKind.OPEN_BRACE, name);
        }
        List<AuxiliarySyntax.VariableDeclarator> variables = new ArrayList<>();
        variables.add(parseNamedVariableTail(name, start, type));
        while (at(SyntaxKind.COMMA)) {
            take();
            int variableStart = current().span().start();
            String variableName = expect(SyntaxKind.IDENTIFIER).text();
            variables.add(parseNamedVariableTail(variableName, variableStart, type));
        }
        expect(SyntaxKind.SEMICOLON);
        return new DeclarationSyntax.Field(finish(start), attributes, modifiers, type, variables);
    }

    private AuxiliarySyntax.VariableDeclarator parseNamedVariableTail(String name, int start,
            TypeSyntax declaredType) {
        ExpressionSyntax initializer = null;
        if (at(SyntaxKind.EQUALS)) {
            take();
            initializer = parseVariableInitializer(declaredType);
        }
        int end = initializer == null ? Math.max(start, previousEnd()) : initializer.span().end();
        return AuxiliarySyntax.VariableDeclarator.named(SourceSpan.between(start, end), name,
                initializer);
    }

    private DeclarationSyntax parseOperator(int start,
            List<AuxiliarySyntax.AttributeList> attributes, List<SyntaxKind> modifiers,
            TypeSyntax returnType) {
        expect(SyntaxKind.OPERATOR);
        OperatorToken operator = takeOverloadableOperator();
        List<AuxiliarySyntax.Parameter> parameters = parseParameterList(false);
        CallableBody body = parseCallableBody();
        return new DeclarationSyntax.Operator(finish(start), attributes, modifiers, returnType,
                operator.kind(), parameters, body.block(), body.expression());
    }

    private DeclarationSyntax parseConversionOperator(int start,
            List<AuxiliarySyntax.AttributeList> attributes, List<SyntaxKind> modifiers) {
        boolean implicit = take().is(SyntaxKind.IMPLICIT);
        expect(SyntaxKind.OPERATOR);
        TypeSyntax target = parseType();
        List<AuxiliarySyntax.Parameter> parameters = parseParameterList(false);
        AuxiliarySyntax.Parameter parameter;
        if (parameters.isEmpty()) {
            SourceSpan point = SourceSpan.at(current().span().start());
            parameter = new AuxiliarySyntax.Parameter(point, List.of(), List.of(),
                    new TypeSyntax.Missing(point), "<missing>", null);
        } else {
            parameter = parameters.getFirst();
        }
        CallableBody body = parseCallableBody();
        return new DeclarationSyntax.ConversionOperator(finish(start), attributes, modifiers,
                implicit, target, parameter, body.block(), body.expression());
    }

    private OperatorToken takeOverloadableOperator() {
        if (at(SyntaxKind.GREATER_THAN) && adjacentGreaterCount(index) >= 2) {
            int count = Math.min(3, adjacentGreaterCount(index));
            index += count;
            return new OperatorToken(count == 3
                    ? SyntaxKind.GREATER_THAN_GREATER_THAN_GREATER_THAN
                    : SyntaxKind.GREATER_THAN_GREATER_THAN, count);
        }
        SyntaxToken token = take();
        if (token.kind().category() != SyntaxKind.Category.PUNCTUATOR
                && !token.is(SyntaxKind.TRUE) && !token.is(SyntaxKind.FALSE)) {
            report(DiagnosticCode.TOKEN_EXPECTED, token.span(), "operator");
        }
        return new OperatorToken(token.kind(), 1);
    }

    private record OperatorToken(SyntaxKind kind, int tokenCount) { }

    private CallableBody parseCallableBody() {
        if (at(SyntaxKind.OPEN_BRACE)) {
            return new CallableBody(parseBlock(), null);
        }
        if (at(SyntaxKind.EQUALS_GREATER_THAN)) {
            take();
            ExpressionSyntax expression = parseExpression();
            expect(SyntaxKind.SEMICOLON);
            return new CallableBody(null, expression);
        }
        expect(SyntaxKind.SEMICOLON);
        return new CallableBody(null, null);
    }

    private record CallableBody(StatementSyntax.Block block, ExpressionSyntax expression) { }

    private List<AuxiliarySyntax.AttributeList> parseAttributeLists() {
        List<AuxiliarySyntax.AttributeList> result = new ArrayList<>();
        while (at(SyntaxKind.OPEN_BRACKET) && looksLikeAttributeList()) {
            int start = take().span().start();
            String target = null;
            if (at(SyntaxKind.IDENTIFIER) && peek(1).is(SyntaxKind.COLON)) {
                target = take().text();
                take();
            } else if (at(SyntaxKind.RETURN) && peek(1).is(SyntaxKind.COLON)) {
                target = take().text();
                take();
            }
            List<AuxiliarySyntax.Attribute> attributes = new ArrayList<>();
            do {
                int attributeStart = current().span().start();
                TypeSyntax.Name name = parseQualifiedName();
                List<AuxiliarySyntax.Argument> arguments = at(SyntaxKind.OPEN_PAREN)
                        ? parseArgumentList(SyntaxKind.OPEN_PAREN, SyntaxKind.CLOSE_PAREN)
                        : List.of();
                attributes.add(new AuxiliarySyntax.Attribute(finish(attributeStart), name,
                        arguments));
                if (!at(SyntaxKind.COMMA)) {
                    break;
                }
                take();
            } while (!at(SyntaxKind.CLOSE_BRACKET) && !at(SyntaxKind.END_OF_FILE));
            expect(SyntaxKind.CLOSE_BRACKET);
            result.add(new AuxiliarySyntax.AttributeList(finish(start), target, attributes));
        }
        return List.copyOf(result);
    }

    private boolean looksLikeAttributeList() {
        int close = skipBalanced(index, SyntaxKind.OPEN_BRACKET, SyntaxKind.CLOSE_BRACKET);
        if (close <= index + 1) {
            return false;
        }
        SyntaxToken after = tokenAt(close);
        return MODIFIERS.contains(after.kind()) || after.is(SyntaxKind.ENUM)
                || after.is(SyntaxKind.STRUCT) || after.is(SyntaxKind.CLASS)
                || after.is(SyntaxKind.INTERFACE) || after.is(SyntaxKind.DELEGATE)
                || isContextualAt(close, "record") || isTypeStart(after);
    }

    private List<SyntaxKind> parseModifiers() {
        List<SyntaxKind> result = new ArrayList<>();
        while (MODIFIERS.contains(current().kind()) || atContextual("partial")
                || atContextual("file") || atContextual("async")) {
            if (atContextual("partial")) {
                take();
                result.add(SyntaxKind.PARTIAL);
            } else if (atContextual("file")) {
                take();
                result.add(SyntaxKind.FILE);
            } else if (atContextual("async")) {
                take();
                result.add(SyntaxKind.ASYNC);
            } else {
                result.add(take().kind());
            }
        }
        return List.copyOf(result);
    }

    private List<AuxiliarySyntax.TypeParameter> parseTypeParameters() {
        if (!at(SyntaxKind.LESS_THAN)) {
            return List.of();
        }
        take();
        List<AuxiliarySyntax.TypeParameter> result = new ArrayList<>();
        while (!at(SyntaxKind.GREATER_THAN) && !at(SyntaxKind.END_OF_FILE)) {
            int start = current().span().start();
            List<AuxiliarySyntax.AttributeList> attributes = parseAttributeLists();
            if (at(SyntaxKind.IN) || at(SyntaxKind.OUT)) {
                SyntaxToken variance = take();
                report(DiagnosticCode.OBJECT_MODEL_UNSUPPORTED, variance.span(),
                        "generic variance");
            }
            String name = expect(SyntaxKind.IDENTIFIER).text();
            result.add(new AuxiliarySyntax.TypeParameter(finish(start), name, attributes));
            if (!at(SyntaxKind.COMMA)) {
                break;
            }
            take();
        }
        expect(SyntaxKind.GREATER_THAN);
        return List.copyOf(result);
    }

    private List<AuxiliarySyntax.ConstraintClause> parseConstraints() {
        List<AuxiliarySyntax.ConstraintClause> result = new ArrayList<>();
        while (atContextual("where")) {
            int start = take().span().start();
            String parameter = expect(SyntaxKind.IDENTIFIER).text();
            expect(SyntaxKind.COLON);
            List<TypeSyntax> constraints = new ArrayList<>();
            boolean constructor = false;
            boolean allowsRefStruct = false;
            while (!at(SyntaxKind.END_OF_FILE)) {
                if (at(SyntaxKind.NEW) && peek(1).is(SyntaxKind.OPEN_PAREN)) {
                    take();
                    take();
                    expect(SyntaxKind.CLOSE_PAREN);
                    constructor = true;
                } else if (atContextual("allows")) {
                    take();
                    expect(SyntaxKind.REF);
                    expect(SyntaxKind.STRUCT);
                    allowsRefStruct = true;
                } else if (at(SyntaxKind.CLASS) || at(SyntaxKind.STRUCT)
                        || atContextual("notnull") || atContextual("unmanaged")) {
                    SyntaxToken keyword = take();
                    constraints.add(TypeSyntax.Name.of(keyword.span(), keyword.text()));
                    if (at(SyntaxKind.QUESTION)) {
                        SyntaxToken question = take();
                        TypeSyntax last = constraints.removeLast();
                        constraints.add(new TypeSyntax.Nullable(
                                SourceSpan.between(last.span().start(), question.span().end()), last));
                    }
                } else {
                    constraints.add(parseType());
                }
                if (!at(SyntaxKind.COMMA)) {
                    break;
                }
                take();
            }
            result.add(new AuxiliarySyntax.ConstraintClause(finish(start), parameter,
                    constraints, constructor, allowsRefStruct));
        }
        return List.copyOf(result);
    }

    // ---- Statements -------------------------------------------------------------------

    private StatementSyntax parseStatement() {
        if (at(SyntaxKind.HASH)) {
            AuxiliarySyntax.Directive directive = parseDirective();
            directives.add(directive);
            return new StatementSyntax.Empty(directive.span());
        }
        if (at(SyntaxKind.OPEN_BRACE)) {
            return parseBlock();
        }
        if (at(SyntaxKind.SEMICOLON)) {
            return new StatementSyntax.Empty(take().span());
        }
        if (at(SyntaxKind.IDENTIFIER) && peek(1).is(SyntaxKind.COLON)) {
            return parseLabeledStatement();
        }
        if (atContextual("yield")) {
            return parseYieldStatement();
        }
        if (looksLikeLocalFunction()) {
            return parseLocalFunction();
        }
        if ((at(SyntaxKind.CHECKED) || at(SyntaxKind.UNCHECKED))
                && peek(1).is(SyntaxKind.OPEN_BRACE)) {
            return parseCheckedStatement();
        }
        return switch (current().kind()) {
            case IF -> parseIfStatement();
            case WHILE -> parseWhileStatement();
            case DO -> parseDoStatement();
            case FOR -> parseForStatement();
            case FOREACH -> parseForeachStatement();
            case SWITCH -> parseSwitchStatement();
            case BREAK -> parseBreakStatement();
            case CONTINUE -> parseContinueStatement();
            case RETURN -> parseReturnStatement();
            case THROW -> parseThrowStatement();
            case GOTO -> parseGotoStatement();
            case TRY -> parseTryStatement();
            case USING -> parseUsingStatement();
            case LOCK -> parseLockStatement();
            case CONST -> parseLocalDeclaration(true, false, true);
            case UNSAFE, FIXED -> parseUnsupportedStatement();
            default -> looksLikeLocalDeclaration()
                    ? parseLocalDeclaration(false, false, true)
                    : parseExpressionStatement();
        };
    }

    private StatementSyntax.Block parseBlock() {
        int start = expect(SyntaxKind.OPEN_BRACE).span().start();
        List<StatementSyntax> statements = new ArrayList<>();
        while (!at(SyntaxKind.CLOSE_BRACE) && !at(SyntaxKind.END_OF_FILE)) {
            int before = mark();
            if (at(SyntaxKind.HASH)) {
                directives.add(parseDirective());
            } else {
                statements.add(parseStatement());
            }
            ensureProgress(before);
        }
        expect(SyntaxKind.CLOSE_BRACE);
        return new StatementSyntax.Block(finish(start), statements);
    }

    private StatementSyntax parseIfStatement() {
        int start = take().span().start();
        ExpressionSyntax condition = parseParenthesizedExpression();
        StatementSyntax whenTrue = parseStatement();
        StatementSyntax whenFalse = null;
        if (at(SyntaxKind.ELSE)) {
            take();
            whenFalse = parseStatement();
        }
        return new StatementSyntax.If(finish(start), condition, whenTrue, whenFalse);
    }

    private StatementSyntax parseWhileStatement() {
        int start = take().span().start();
        ExpressionSyntax condition = parseParenthesizedExpression();
        StatementSyntax body = parseStatement();
        return new StatementSyntax.While(finish(start), condition, body);
    }

    private StatementSyntax parseDoStatement() {
        int start = take().span().start();
        StatementSyntax body = parseStatement();
        expect(SyntaxKind.WHILE);
        ExpressionSyntax condition = parseParenthesizedExpression();
        expect(SyntaxKind.SEMICOLON);
        return new StatementSyntax.Do(finish(start), body, condition);
    }

    private StatementSyntax parseForStatement() {
        int start = take().span().start();
        expect(SyntaxKind.OPEN_PAREN);
        List<StatementSyntax> initializers = new ArrayList<>();
        if (!at(SyntaxKind.SEMICOLON)) {
            if (looksLikeLocalDeclaration()) {
                initializers.add(parseLocalDeclaration(false, false, false));
            } else {
                do {
                    int expressionStart = current().span().start();
                    ExpressionSyntax expression = parseExpression();
                    initializers.add(new StatementSyntax.Expression(
                            covering(expressionStart, expression), expression));
                    if (!at(SyntaxKind.COMMA)) {
                        break;
                    }
                    take();
                } while (!at(SyntaxKind.SEMICOLON));
            }
        }
        expect(SyntaxKind.SEMICOLON);
        ExpressionSyntax condition = at(SyntaxKind.SEMICOLON) ? null : parseExpression();
        expect(SyntaxKind.SEMICOLON);
        List<ExpressionSyntax> iterators = new ArrayList<>();
        if (!at(SyntaxKind.CLOSE_PAREN)) {
            do {
                iterators.add(parseExpression());
                if (!at(SyntaxKind.COMMA)) {
                    break;
                }
                take();
            } while (!at(SyntaxKind.CLOSE_PAREN));
        }
        expect(SyntaxKind.CLOSE_PAREN);
        StatementSyntax body = parseStatement();
        return new StatementSyntax.For(finish(start), initializers, condition, iterators, body);
    }

    private StatementSyntax parseForeachStatement() {
        int start = take().span().start();
        expect(SyntaxKind.OPEN_PAREN);
        TypeSyntax type = parseType();
        PatternSyntax variable;

        if (at(SyntaxKind.IN) && type instanceof TypeSyntax.Tuple tupleType) {
            List<PatternSyntax> elements = new ArrayList<>();
            for (TypeSyntax.TupleElement element : tupleType.elements()) {
                if (element.name() == null) {
                    report(DiagnosticCode.IDENTIFIER_EXPECTED, element.span());
                }
                elements.add(new PatternSyntax.Type(element.span(), element.type(), element.name()));
            }
            variable = new PatternSyntax.Recursive(tupleType.span(), null, elements, List.of(), null);
            type = new TypeSyntax.Predefined(tupleType.span(), SyntaxKind.VAR);
        } else {
            variable = parseVariableDesignation();
        }

        expect(SyntaxKind.IN);
        ExpressionSyntax collection = parseExpression();
        expect(SyntaxKind.CLOSE_PAREN);
        StatementSyntax body = parseStatement();
        return new StatementSyntax.Foreach(finish(start), type, variable, collection, body);
    }

    private StatementSyntax parseSwitchStatement() {
        int start = take().span().start();
        ExpressionSyntax expression = parseParenthesizedExpression();
        expect(SyntaxKind.OPEN_BRACE);
        List<AuxiliarySyntax.SwitchSection> sections = new ArrayList<>();
        while (!at(SyntaxKind.CLOSE_BRACE) && !at(SyntaxKind.END_OF_FILE)) {
            int sectionStart = current().span().start();
            List<AuxiliarySyntax.SwitchLabel> labels = new ArrayList<>();
            while (at(SyntaxKind.CASE) || at(SyntaxKind.DEFAULT)) {
                int labelStart = current().span().start();
                PatternSyntax pattern = null;
                if (at(SyntaxKind.CASE)) {
                    take();
                    pattern = parseGuardedPattern();
                } else {
                    take();
                }
                ExpressionSyntax guard = null;
                if (atContextual("when")) {
                    take();
                    guard = parseExpression();
                }
                expect(SyntaxKind.COLON);
                labels.add(new AuxiliarySyntax.SwitchLabel(finish(labelStart), pattern, guard));
            }
            if (labels.isEmpty()) {
                report(DiagnosticCode.TOKEN_EXPECTED, current().span(), "'case' or 'default'");
                take();
                continue;
            }
            List<StatementSyntax> body = new ArrayList<>();
            while (!at(SyntaxKind.CASE) && !at(SyntaxKind.DEFAULT)
                    && !at(SyntaxKind.CLOSE_BRACE) && !at(SyntaxKind.END_OF_FILE)) {
                int before = mark();
                body.add(parseStatement());
                ensureProgress(before);
            }
            sections.add(new AuxiliarySyntax.SwitchSection(finish(sectionStart), labels, body));
        }
        expect(SyntaxKind.CLOSE_BRACE);
        return new StatementSyntax.Switch(finish(start), expression, sections);
    }

    private StatementSyntax parseBreakStatement() {
        int start = take().span().start();
        expect(SyntaxKind.SEMICOLON);
        return new StatementSyntax.Break(finish(start));
    }

    private StatementSyntax parseContinueStatement() {
        int start = take().span().start();
        expect(SyntaxKind.SEMICOLON);
        return new StatementSyntax.Continue(finish(start));
    }

    private StatementSyntax parseReturnStatement() {
        int start = take().span().start();
        ExpressionSyntax expression = at(SyntaxKind.SEMICOLON) ? null : parseExpression();
        expect(SyntaxKind.SEMICOLON);
        return new StatementSyntax.Return(finish(start), expression);
    }

    private StatementSyntax parseThrowStatement() {
        int start = take().span().start();
        ExpressionSyntax expression = at(SyntaxKind.SEMICOLON) ? null : parseExpression();
        expect(SyntaxKind.SEMICOLON);
        return new StatementSyntax.Throw(finish(start), expression);
    }

    private StatementSyntax parseGotoStatement() {
        int start = take().span().start();
        StatementSyntax.Goto.Kind kind;
        String label = null;
        ExpressionSyntax value = null;
        if (at(SyntaxKind.CASE)) {
            take();
            kind = StatementSyntax.Goto.Kind.CASE;
            value = parseExpression();
        } else if (at(SyntaxKind.DEFAULT)) {
            take();
            kind = StatementSyntax.Goto.Kind.DEFAULT;
        } else {
            kind = StatementSyntax.Goto.Kind.LABEL;
            label = expect(SyntaxKind.IDENTIFIER).text();
        }
        expect(SyntaxKind.SEMICOLON);
        return new StatementSyntax.Goto(finish(start), kind, label, value);
    }

    private StatementSyntax parseLabeledStatement() {
        int start = current().span().start();
        String label = take().text();
        take();
        StatementSyntax statement = parseStatement();
        return new StatementSyntax.Labeled(finish(start), label, statement);
    }

    private StatementSyntax parseTryStatement() {
        int start = take().span().start();
        StatementSyntax.Block body = parseBlock();
        List<AuxiliarySyntax.CatchClause> catches = new ArrayList<>();
        while (at(SyntaxKind.CATCH)) {
            int catchStart = take().span().start();
            TypeSyntax type = null;
            String name = null;
            SourceSpan nameSpan = null;
            if (at(SyntaxKind.OPEN_PAREN)) {
                take();
                type = parseType();
                if (at(SyntaxKind.IDENTIFIER)) {
                    SyntaxToken identifier = take();
                    name = identifier.text();
                    nameSpan = identifier.span();
                }
                expect(SyntaxKind.CLOSE_PAREN);
            }
            ExpressionSyntax filter = null;
            if (atContextual("when")) {
                take();
                filter = parseParenthesizedExpression();
            }
            StatementSyntax.Block catchBody = parseBlock();
            catches.add(new AuxiliarySyntax.CatchClause(finish(catchStart), type, name, nameSpan,
                    filter, catchBody));
        }
        StatementSyntax.Block finallyBody = null;
        if (at(SyntaxKind.FINALLY)) {
            take();
            finallyBody = parseBlock();
        }
        if (catches.isEmpty() && finallyBody == null) {
            report(DiagnosticCode.TOKEN_EXPECTED, current().span(), "'catch' or 'finally'");
        }
        return new StatementSyntax.Try(finish(start), body, catches, finallyBody);
    }

    private StatementSyntax parseUsingStatement() {
        int start = take().span().start();
        if (!at(SyntaxKind.OPEN_PAREN)) {
            index--;
            return parseLocalDeclaration(false, true, true);
        }
        take();
        SyntaxNode resource;
        if (looksLikeLocalDeclaration()) {
            resource = parseLocalDeclaration(false, false, false);
        } else {
            resource = parseExpression();
        }
        expect(SyntaxKind.CLOSE_PAREN);
        StatementSyntax body = parseStatement();
        return new StatementSyntax.Using(finish(start), resource, body);
    }

    private StatementSyntax parseLockStatement() {
        int start = take().span().start();
        ExpressionSyntax expression = parseParenthesizedExpression();
        StatementSyntax body = parseStatement();
        return new StatementSyntax.Lock(finish(start), expression, body);
    }

    private StatementSyntax parseCheckedStatement() {
        int start = current().span().start();
        boolean checked = take().is(SyntaxKind.CHECKED);
        StatementSyntax.Block body = parseBlock();
        return new StatementSyntax.Checked(finish(start), checked, body);
    }

    private StatementSyntax parseYieldStatement() {
        int start = take().span().start();
        boolean isBreak = at(SyntaxKind.BREAK);
        ExpressionSyntax expression = null;
        if (isBreak) {
            take();
        } else {
            expect(SyntaxKind.RETURN);
            expression = parseExpression();
        }
        expect(SyntaxKind.SEMICOLON);
        return new StatementSyntax.Yield(finish(start), isBreak, expression);
    }

    private StatementSyntax parseUnsupportedStatement() {
        int start = current().span().start();
        SyntaxToken introducer = take();
        report(DiagnosticCode.UNVERIFIABLE_FEATURE_UNSUPPORTED, introducer.span(),
                introducer.text());
        if (at(SyntaxKind.OPEN_PAREN)) {
            index = skipBalanced(index, SyntaxKind.OPEN_PAREN, SyntaxKind.CLOSE_PAREN);
        }
        StatementSyntax body = at(SyntaxKind.OPEN_BRACE) ? parseBlock() : parseStatement();
        return new StatementSyntax.Checked(finish(start), false,
                body instanceof StatementSyntax.Block block
                        ? block : new StatementSyntax.Block(body.span(), List.of(body)));
    }

    private StatementSyntax parseLocalDeclaration(boolean constant, boolean using,
            boolean withSemicolon) {
        int start = current().span().start();
        if (using) {
            expect(SyntaxKind.USING);
        }
        if (constant) {
            expect(SyntaxKind.CONST);
        }
        TypeSyntax type = parseType();
        List<AuxiliarySyntax.VariableDeclarator> variables = new ArrayList<>();
        do {
            int variableStart = current().span().start();
            String name = null;
            PatternSyntax designation = null;
            if (at(SyntaxKind.OPEN_PAREN)) {
                designation = parseVariableDesignation();
            } else {
                name = expect(SyntaxKind.IDENTIFIER).text();
            }
            ExpressionSyntax initializer = null;
            if (at(SyntaxKind.EQUALS)) {
                take();
                initializer = parseVariableInitializer(type);
            }
            int end = initializer == null ? previousEnd() : initializer.span().end();
            variables.add(new AuxiliarySyntax.VariableDeclarator(
                    SourceSpan.between(variableStart, Math.max(variableStart, end)), name,
                    designation, initializer));
            if (!at(SyntaxKind.COMMA)) {
                break;
            }
            take();
        } while (!at(SyntaxKind.SEMICOLON) && !at(SyntaxKind.END_OF_FILE));
        if (withSemicolon) {
            expect(SyntaxKind.SEMICOLON);
        }
        return new StatementSyntax.LocalDeclaration(finish(start), constant, using, type,
                variables);
    }

    private StatementSyntax parseExpressionStatement() {
        int start = current().span().start();
        ExpressionSyntax expression = parseExpression();
        expect(SyntaxKind.SEMICOLON);
        return new StatementSyntax.Expression(finish(start), expression);
    }

    private ExpressionSyntax parseParenthesizedExpression() {
        expect(SyntaxKind.OPEN_PAREN);
        ExpressionSyntax result = parseExpression();
        expect(SyntaxKind.CLOSE_PAREN);
        return result;
    }

    private boolean looksLikeLocalDeclaration() {
        // `await Work.Run();` is a statement whose expression starts here, not a local
        // declaration of a variable named `Run` with type `await`. The word scans as a type
        // name because it is contextual, so the operator has to be recognised first.
        if (atContextual("await")) {
            return false;
        }
        int end = scanType(index);
        if (end < 0) {
            return false;
        }
        // `p with { X = 1 }` scans exactly like a declaration - a type name followed by an
        // identifier - because `with` is contextual. Only the brace tells them apart, and a
        // declarator is never followed by one, so this shape is a `with` expression.
        if (isContextualAt(end, "with") && tokenAt(end + 1).is(SyntaxKind.OPEN_BRACE)) {
            return false;
        }
        return tokenAt(end).is(SyntaxKind.IDENTIFIER)
                || (atContextual("var") && tokenAt(end).is(SyntaxKind.OPEN_PAREN));
    }

    private boolean looksLikeLocalFunction() {
        int cursor = index;
        if (tokenAt(cursor).is(SyntaxKind.STATIC)) {
            cursor++;
        }
        if (isContextualAt(cursor, "async")) {
            cursor++;
        }
        int typeEnd = scanType(cursor);
        if (typeEnd < 0 || !tokenAt(typeEnd).is(SyntaxKind.IDENTIFIER)) {
            return false;
        }
        int afterName = typeEnd + 1;
        if (tokenAt(afterName).is(SyntaxKind.LESS_THAN)) {
            afterName = scanTypeParameterList(afterName);
        }
        return tokenAt(afterName).is(SyntaxKind.OPEN_PAREN);
    }

    private StatementSyntax parseLocalFunction() {
        int start = current().span().start();
        List<SyntaxKind> modifiers = new ArrayList<>();
        if (at(SyntaxKind.STATIC)) {
            modifiers.add(take().kind());
        }
        if (atContextual("async")) {
            SyntaxToken async = take();
            report(DiagnosticCode.ASYNC_LAMBDA_UNSUPPORTED, async.span());
        }
        TypeSyntax returnType = parseType();
        String name = expect(SyntaxKind.IDENTIFIER).text();
        List<AuxiliarySyntax.TypeParameter> typeParameters = parseTypeParameters();
        List<AuxiliarySyntax.Parameter> parameters = parseParameterList(false);
        List<AuxiliarySyntax.ConstraintClause> constraints = parseConstraints();
        CallableBody body = parseCallableBody();
        DeclarationSyntax.Method method = new DeclarationSyntax.Method(finish(start), List.of(),
                modifiers, returnType, name, typeParameters, parameters, constraints,
                body.block(), body.expression());
        return new StatementSyntax.LocalFunction(method.span(), method);
    }

    // ---- Expressions ------------------------------------------------------------------

    private ExpressionSyntax parseExpression() {
        return parseAssignmentExpression();
    }

    private ExpressionSyntax parseAssignmentExpression() {
        if (looksLikeLambda()) {
            return parseLambda();
        }
        ExpressionSyntax left = parseConditionalExpression();
        AssignmentOperator assignment = assignmentOperator();
        if (assignment == null) {
            return left;
        }
        consume(assignment.tokenCount());
        ExpressionSyntax right = parseAssignmentExpression();
        return new ExpressionSyntax.Assignment(
                SourceSpan.between(left.span().start(), right.span().end()), left,
                assignment.kind(), right);
    }

    private ExpressionSyntax parseConditionalExpression() {
        ExpressionSyntax condition = parseBinaryExpression(1);
        if (!at(SyntaxKind.QUESTION)) {
            return condition;
        }
        take();
        ExpressionSyntax whenTrue = parseExpression();
        expect(SyntaxKind.COLON);
        ExpressionSyntax whenFalse = parseAssignmentExpression();
        return new ExpressionSyntax.Conditional(
                SourceSpan.between(condition.span().start(), whenFalse.span().end()),
                condition, whenTrue, whenFalse);
    }

    private ExpressionSyntax parseBinaryExpression(int minimumPrecedence) {
        ExpressionSyntax left = parseUnaryExpression();
        while (true) {
            if (at(SyntaxKind.IS)) {
                int precedence = 9;
                if (precedence < minimumPrecedence) {
                    break;
                }
                take();
                PatternSyntax pattern = parsePattern();
                left = new ExpressionSyntax.IsPattern(
                        SourceSpan.between(left.span().start(), pattern.span().end()), left,
                        pattern);
                continue;
            }
            if (at(SyntaxKind.AS)) {
                int precedence = 9;
                if (precedence < minimumPrecedence) {
                    break;
                }
                take();
                TypeSyntax type = parseType();
                left = new ExpressionSyntax.As(
                        SourceSpan.between(left.span().start(), type.span().end()), left, type);
                continue;
            }
            BinaryOperator operator = binaryOperator();
            if (operator == null || operator.precedence() < minimumPrecedence) {
                break;
            }
            consume(operator.tokenCount());
            if (operator.kind() == SyntaxKind.DOT_DOT) {
                ExpressionSyntax end = canStartExpression(current())
                        ? parseBinaryExpression(operator.precedence() + 1) : null;
                int rangeEnd = end == null ? previousEnd() : end.span().end();
                left = new ExpressionSyntax.Range(
                        SourceSpan.between(left.span().start(), rangeEnd), left, end);
                continue;
            }
            int nextMinimum = operator.rightAssociative()
                    ? operator.precedence() : operator.precedence() + 1;
            ExpressionSyntax right = parseBinaryExpression(nextMinimum);
            left = new ExpressionSyntax.Binary(
                    SourceSpan.between(left.span().start(), right.span().end()), left,
                    operator.kind(), right);
        }
        return left;
    }

    /// Folds `-2147483648` and `-9223372036854775808` into single `int` and `long` literals,
    /// per C# §6.4.5.3.
    ///
    /// The literal ladder is sign-blind, as it must be: `2147483648` on its own is a `uint`
    /// and `9223372036854775808` a `ulong`, and they are right in every other position. C#
    /// makes exactly one exception, for a *decimal* literal of that magnitude with no suffix
    /// standing immediately after a unary minus, and it is the exception that lets a program
    /// write `int.MinValue`'s value. Without it `int a = -2147483648;` needs a narrowing
    /// conversion C# never asks for, and the operand's own type leaks into the diagnostic.
    ///
    /// The fold is a type change and nothing else: the lexer already stores a `uint` in the
    /// raw 32 bits of an `Integer`, so `2147483648` is already the bit pattern of
    /// `Integer.MIN_VALUE`, and the same holds for `ulong` in a `Long`. Hexadecimal and binary
    /// spellings are excluded because the rule names a decimal-integer-literal; `-0x80000000`
    /// stays a negated `uint`, as in C#.
    ///
    /// @param operator the operator token already consumed
    /// @return the folded literal, or `null` when this is not that one shape
    private ExpressionSyntax foldNegatedMinimum(SyntaxToken operator) {
        if (!operator.is(SyntaxKind.MINUS) || !current().is(SyntaxKind.INTEGER_LITERAL)) {
            return null;
        }
        SyntaxToken literal = current();
        String text = literal.text();
        if (!isDecimalInteger(text) || hasNumericSuffix(text)) {
            return null;
        }
        LiteralType folded = switch (literal.literalType()) {
            case UINT -> literal.value() instanceof Integer i && i.intValue() == Integer.MIN_VALUE
                    ? LiteralType.INT : null;
            case ULONG -> literal.value() instanceof Long l && l.longValue() == Long.MIN_VALUE
                    ? LiteralType.LONG : null;
            default -> null;
        };
        if (folded == null) {
            return null;
        }
        take();
        SourceSpan span = SourceSpan.between(operator.span().start(), previousEnd());
        SyntaxToken token = SyntaxToken.literal(SyntaxKind.INTEGER_LITERAL, span,
                operator.text() + text, folded, literal.value(), operator.atStartOfLine());
        return new ExpressionSyntax.Literal(span, token);
    }

    /// Whether a literal's text is a decimal-integer-literal rather than a hexadecimal or
    /// binary one. Digit separators are allowed anywhere C# allows them.
    private static boolean isDecimalInteger(String text) {
        return !(text.length() >= 2 && text.charAt(0) == '0'
                && switch (text.charAt(1)) {
                    case 'x', 'X', 'b', 'B' -> true;
                    default -> false;
                });
    }

    /// Whether a literal's text carries a `U` or `L` integer-type-suffix in any spelling.
    private static boolean hasNumericSuffix(String text) {
        char last = text.charAt(text.length() - 1);
        return last == 'u' || last == 'U' || last == 'l' || last == 'L';
    }

    private ExpressionSyntax parseUnaryExpression() {
        if (atContextual("await")) {
            int start = take().span().start();
            ExpressionSyntax operand = parseUnaryExpression();
            return new ExpressionSyntax.Await(
                    SourceSpan.between(start, operand.span().end()), operand);
        }
        if (at(SyntaxKind.DOT_DOT)) {
            int start = take().span().start();
            ExpressionSyntax end = canStartExpression(current()) ? parseUnaryExpression() : null;
            int rangeEnd = end == null ? previousEnd() : end.span().end();
            return new ExpressionSyntax.Range(SourceSpan.between(start, rangeEnd), null, end);
        }
        if (isPrefixOperator(current().kind())) {
            SyntaxToken operator = take();
            if (operator.is(SyntaxKind.ASTERISK) || operator.is(SyntaxKind.AMPERSAND)) {
                report(DiagnosticCode.UNVERIFIABLE_FEATURE_UNSUPPORTED, operator.span(),
                        operator.text());
            }
            ExpressionSyntax negatedMinimum = foldNegatedMinimum(operator);
            if (negatedMinimum != null) {
                return negatedMinimum;
            }
            ExpressionSyntax operand = parseUnaryExpression();
            return new ExpressionSyntax.Unary(
                    SourceSpan.between(operator.span().start(), operand.span().end()),
                    operator.kind(), operand);
        }
        if (at(SyntaxKind.OPEN_PAREN) && looksLikeCast()) {
            int start = take().span().start();
            TypeSyntax type = parseType();
            expect(SyntaxKind.CLOSE_PAREN);
            ExpressionSyntax expression = parseUnaryExpression();
            return new ExpressionSyntax.Cast(
                    SourceSpan.between(start, expression.span().end()), type, expression);
        }
        return parsePostfixExpression();
    }

    private ExpressionSyntax parsePostfixExpression() {
        ExpressionSyntax result = parsePrimaryExpression();
        while (true) {
            if (at(SyntaxKind.OPEN_PAREN)) {
                List<AuxiliarySyntax.Argument> arguments =
                        parseArgumentList(SyntaxKind.OPEN_PAREN, SyntaxKind.CLOSE_PAREN);
                result = new ExpressionSyntax.Invocation(
                        SourceSpan.between(result.span().start(), previousEnd()), result,
                        arguments);
            } else if (at(SyntaxKind.OPEN_BRACKET)
                    || (at(SyntaxKind.QUESTION) && peek(1).is(SyntaxKind.OPEN_BRACKET))) {
                boolean conditional = at(SyntaxKind.QUESTION);
                if (conditional) {
                    take();
                }
                List<AuxiliarySyntax.Argument> arguments =
                        parseArgumentList(SyntaxKind.OPEN_BRACKET, SyntaxKind.CLOSE_BRACKET);
                result = new ExpressionSyntax.ElementAccess(
                        SourceSpan.between(result.span().start(), previousEnd()), result,
                        arguments, conditional);
            } else if (at(SyntaxKind.DOT) || at(SyntaxKind.QUESTION_DOT)) {
                boolean conditional = take().is(SyntaxKind.QUESTION_DOT);
                String name = expect(SyntaxKind.IDENTIFIER).text();
                List<TypeSyntax> typeArguments = looksLikeExpressionTypeArguments()
                        ? parseTypeArgumentList(false) : List.of();
                result = new ExpressionSyntax.MemberAccess(
                        SourceSpan.between(result.span().start(), previousEnd()), result, name,
                        typeArguments, conditional);
            } else if (at(SyntaxKind.PLUS_PLUS) || at(SyntaxKind.MINUS_MINUS)
                    || at(SyntaxKind.EXCLAMATION)) {
                SyntaxToken operator = take();
                result = new ExpressionSyntax.Postfix(
                        SourceSpan.between(result.span().start(), operator.span().end()), result,
                        operator.kind());
            } else if (at(SyntaxKind.SWITCH)) {
                result = parseSwitchExpression(result);
            } else if (atContextual("with")) {
                result = parseWithExpression(result);
            } else {
                return result;
            }
        }
    }

    private ExpressionSyntax parsePrimaryExpression() {
        SyntaxToken token = current();
        if (token.is(SyntaxKind.INTERPOLATED_STRING)) {
            take();
            return parseInterpolated(token);
        }
        if (token.kind().category() == SyntaxKind.Category.LITERAL
                || token.is(SyntaxKind.TRUE) || token.is(SyntaxKind.FALSE)
                || token.is(SyntaxKind.NULL)) {
            take();
            return new ExpressionSyntax.Literal(token.span(), token);
        }
        if (token.is(SyntaxKind.IDENTIFIER)) {
            if (token.text().equals("nameof") && peek(1).is(SyntaxKind.OPEN_PAREN)) {
                return parseNameOfExpression();
            }
            take();
            List<TypeSyntax> typeArguments = looksLikeExpressionTypeArguments()
                    ? parseTypeArgumentList(false) : List.of();
            return new ExpressionSyntax.Identifier(
                    SourceSpan.between(token.span().start(), previousEnd()), token.text(),
                    typeArguments);
        }
        // `int.MaxValue`: a keyword type is an expression only as a member-access receiver.
        // Without the trailing `.` the token is still an invalid expression term, so the
        // ordinary diagnostic below stays the outcome for `int x` written where a value goes.
        if (PREDEFINED_TYPES.contains(token.kind()) && peek(1).is(SyntaxKind.DOT)) {
            take();
            return new ExpressionSyntax.PredefinedType(token.span(), token.kind());
        }
        return switch (token.kind()) {
            case OPEN_PAREN -> parseParenthesizedOrTupleExpression();
            case OPEN_BRACKET -> parseCollectionExpression();
            case NEW -> parseCreationExpression();
            case DELEGATE -> parseAnonymousMethod();
            case DEFAULT -> parseDefaultExpression();
            case TYPEOF, SIZEOF -> parseTypeOperatorExpression();
            case CHECKED, UNCHECKED -> parseCheckedExpression();
            case THROW -> {
                int start = take().span().start();
                ExpressionSyntax expression = parseExpression();
                yield new ExpressionSyntax.Throw(covering(start, expression), expression);
            }
            case THIS, BASE -> {
                take();
                report(DiagnosticCode.OBJECT_MODEL_UNSUPPORTED, token.span(), token.text());
                yield new ExpressionSyntax.Unsupported(token.span(), token.kind(), token.text());
            }
            case STACKALLOC -> {
                take();
                report(DiagnosticCode.UNVERIFIABLE_FEATURE_UNSUPPORTED, token.span(),
                        token.text());
                while (!at(SyntaxKind.SEMICOLON) && !at(SyntaxKind.COMMA)
                        && !at(SyntaxKind.CLOSE_PAREN) && !at(SyntaxKind.END_OF_FILE)) {
                    take();
                }
                yield new ExpressionSyntax.Unsupported(finish(token.span().start()), token.kind(),
                        token.text());
            }
            default -> {
                report(DiagnosticCode.EXPRESSION_EXPECTED, token.span(), token.text());
                if (!token.is(SyntaxKind.END_OF_FILE)) {
                    take();
                }
                yield new ExpressionSyntax.Missing(SourceSpan.at(token.span().start()));
            }
        };
    }

    private ExpressionSyntax parseParenthesizedOrTupleExpression() {
        int start = take().span().start();
        ExpressionSyntax first = parseTupleElementExpression();
        if (!at(SyntaxKind.COMMA)) {
            expect(SyntaxKind.CLOSE_PAREN);
            return new ExpressionSyntax.Parenthesized(finish(start), first);
        }
        List<ExpressionSyntax> elements = new ArrayList<>();
        elements.add(first);
        while (at(SyntaxKind.COMMA)) {
            take();
            elements.add(parseTupleElementExpression());
        }
        expect(SyntaxKind.CLOSE_PAREN);
        return new ExpressionSyntax.Tuple(finish(start), elements);
    }

    private ExpressionSyntax parseTupleElementExpression() {
        if (looksLikeLocalDeclaration()) {
            return parseDeclarationExpression();
        }
        return parseExpression();
    }

    private ExpressionSyntax.Declaration parseDeclarationExpression() {
        int start = current().span().start();
        TypeSyntax type = parseType();
        PatternSyntax designation;
        if (at(SyntaxKind.IDENTIFIER)) {
            SyntaxToken id = take();
            if (id.text().equals("_")) {
                designation = new PatternSyntax.Discard(id.span());
            } else {
                designation = new PatternSyntax.Var(id.span(), id.text());
            }
        } else {
            report(DiagnosticCode.IDENTIFIER_EXPECTED, current().span(), current().text());
            SyntaxToken dummy = current();
            if (!at(SyntaxKind.END_OF_FILE)) {
                take();
            }
            designation = new PatternSyntax.Var(dummy.span(), dummy.text());
        }
        return new ExpressionSyntax.Declaration(finish(start), type, designation);
    }

    private ExpressionSyntax parseCollectionExpression() {
        int start = take().span().start();
        List<AuxiliarySyntax.CollectionElement> elements = new ArrayList<>();
        while (!at(SyntaxKind.CLOSE_BRACKET) && !at(SyntaxKind.END_OF_FILE)) {
            int elementStart = current().span().start();
            boolean spread = false;
            if (at(SyntaxKind.DOT_DOT)) {
                spread = true;
                take();
            }
            ExpressionSyntax expression = parseExpression();
            elements.add(new AuxiliarySyntax.CollectionElement(
                    covering(elementStart, expression), spread, expression));
            if (!at(SyntaxKind.COMMA)) {
                break;
            }
            take();
        }
        expect(SyntaxKind.CLOSE_BRACKET);
        return new ExpressionSyntax.Collection(finish(start), elements);
    }

    private ExpressionSyntax parseCreationExpression() {
        int start = take().span().start();
        // `new (` opens either a target-typed creation - which needs the omitted object
        // model - or a tuple element type in an array creation (`new (string, int)[2]`).
        // Only the token after the balanced pair tells them apart.
        if (at(SyntaxKind.OPEN_PAREN)
                && !tokenAt(skipBalanced(index, SyntaxKind.OPEN_PAREN, SyntaxKind.CLOSE_PAREN))
                        .is(SyntaxKind.OPEN_BRACKET)) {
            report(DiagnosticCode.OBJECT_MODEL_UNSUPPORTED, SourceSpan.at(start),
                    "object creation");
            index = skipBalanced(index, SyntaxKind.OPEN_PAREN, SyntaxKind.CLOSE_PAREN);
            return new ExpressionSyntax.Unsupported(finish(start), SyntaxKind.NEW,
                    "object creation");
        }
        TypeSyntax element;
        if (at(SyntaxKind.OPEN_BRACKET)) {
            SourceSpan point = SourceSpan.at(current().span().start());
            element = new TypeSyntax.Missing(point);
        } else {
            element = parseType(false);
        }
        if (at(SyntaxKind.OPEN_PAREN)) {
            List<AuxiliarySyntax.Argument> arguments = parseArgumentList(
                    SyntaxKind.OPEN_PAREN, SyntaxKind.CLOSE_PAREN);
            if (at(SyntaxKind.OPEN_BRACE)) {
                report(DiagnosticCode.OBJECT_MODEL_UNSUPPORTED, current().span(),
                        "object initializer");
                index = skipBalanced(index, SyntaxKind.OPEN_BRACE, SyntaxKind.CLOSE_BRACE);
            }
            return new ExpressionSyntax.ObjectCreation(finish(start), element, arguments);
        }
        if (!at(SyntaxKind.OPEN_BRACKET)) {
            report(DiagnosticCode.OBJECT_MODEL_UNSUPPORTED, element.span(), "object creation");
            if (at(SyntaxKind.OPEN_BRACE)) {
                index = skipBalanced(index, SyntaxKind.OPEN_BRACE, SyntaxKind.CLOSE_BRACE);
            }
            return new ExpressionSyntax.Unsupported(finish(start), SyntaxKind.NEW,
                    "object creation");
        }
        expect(SyntaxKind.OPEN_BRACKET);
        List<ExpressionSyntax> dimensions = new ArrayList<>();
        if (!at(SyntaxKind.CLOSE_BRACKET)) {
            while (true) {
                dimensions.add(at(SyntaxKind.COMMA) || at(SyntaxKind.CLOSE_BRACKET)
                        ? new ExpressionSyntax.Missing(SourceSpan.at(current().span().start()))
                        : parseExpression());
                if (!at(SyntaxKind.COMMA)) {
                    break;
                }
                take();
            }
        }
        expect(SyntaxKind.CLOSE_BRACKET);
        List<Integer> trailingRanks = new ArrayList<>();
        while (at(SyntaxKind.OPEN_BRACKET)) {
            take();
            int rank = 1;
            while (at(SyntaxKind.COMMA)) {
                take();
                rank++;
            }
            expect(SyntaxKind.CLOSE_BRACKET);
            trailingRanks.add(rank);
        }
        if (!trailingRanks.isEmpty()) {
            element = new TypeSyntax.Array(
                    SourceSpan.between(element.span().start(), previousEnd()), element,
                    trailingRanks);
        }
        List<ExpressionSyntax> initializer = at(SyntaxKind.OPEN_BRACE)
                ? parseExpressionInitializer() : List.of();
        return new ExpressionSyntax.ArrayCreation(finish(start), element, dimensions,
                initializer);
    }

    /// The initializer of a variable declarator.
    ///
    /// C# lets an array variable be initialised by a bare `{ ... }` (§12.8.16.5), which means
    /// exactly `new T[] { ... }`. Desugaring it here keeps one array-creation path through
    /// binding and lowering instead of a second shape every later stage would have to know.
    /// Only a rank-1 outermost specifier is desugared: a rectangular `int[,] m = {{1,2}}`
    /// would need the multi-dimensional initializer the backend deliberately does not emit,
    /// so it keeps its existing diagnostic rather than compiling into the wrong array.
    private ExpressionSyntax parseVariableInitializer(TypeSyntax declaredType) {
        if (!at(SyntaxKind.OPEN_BRACE) || !(declaredType instanceof TypeSyntax.Array array)
                || array.ranks().getFirst() != 1) {
            return parseExpression();
        }
        int start = current().span().start();
        List<ExpressionSyntax> elements = parseExpressionInitializer();
        List<Integer> remaining = array.ranks().subList(1, array.ranks().size());
        TypeSyntax elementType = remaining.isEmpty()
                ? array.element()
                : new TypeSyntax.Array(array.element().span(), array.element(), remaining);
        return new ExpressionSyntax.ArrayCreation(SourceSpan.between(start, previousEnd()),
                elementType, List.of(), elements);
    }

    private List<ExpressionSyntax> parseExpressionInitializer() {
        takeInlineBrace();
        List<ExpressionSyntax> result = new ArrayList<>();
        while (!at(SyntaxKind.CLOSE_BRACE) && !at(SyntaxKind.END_OF_FILE)) {
            if (at(SyntaxKind.OPEN_BRACE)) {
                int nestedStart = current().span().start();
                List<ExpressionSyntax> nested = parseExpressionInitializer();
                result.add(new ExpressionSyntax.ArrayInitializer(
                        SourceSpan.between(nestedStart, previousEnd()), nested));
            } else {
                result.add(parseExpression());
            }
            if (!at(SyntaxKind.COMMA)) {
                break;
            }
            take();
        }
        expect(SyntaxKind.CLOSE_BRACE);
        return List.copyOf(result);
    }

    private ExpressionSyntax parseAnonymousMethod() {
        int start = take().span().start();
        List<AuxiliarySyntax.Parameter> parameters = at(SyntaxKind.OPEN_PAREN)
                ? parseParameterList(false) : List.of();
        StatementSyntax.Block body = parseBlock();
        return new ExpressionSyntax.Lambda(finish(start), false, null, parameters, body);
    }

    private ExpressionSyntax parseDefaultExpression() {
        int start = take().span().start();
        TypeSyntax type = null;
        if (at(SyntaxKind.OPEN_PAREN)) {
            take();
            type = parseType();
            expect(SyntaxKind.CLOSE_PAREN);
        }
        return new ExpressionSyntax.Default(finish(start), type);
    }

    private ExpressionSyntax parseTypeOperatorExpression() {
        SyntaxToken operator = take();
        expect(SyntaxKind.OPEN_PAREN);
        TypeSyntax type = parseType();
        expect(SyntaxKind.CLOSE_PAREN);
        return new ExpressionSyntax.TypeOperator(finish(operator.span().start()),
                operator.kind(), type);
    }

    private ExpressionSyntax parseNameOfExpression() {
        int start = take().span().start();
        expect(SyntaxKind.OPEN_PAREN);
        ExpressionSyntax expression = parseExpression();
        expect(SyntaxKind.CLOSE_PAREN);
        return new ExpressionSyntax.NameOf(finish(start), expression);
    }

    private ExpressionSyntax parseCheckedExpression() {
        int start = current().span().start();
        boolean checked = take().is(SyntaxKind.CHECKED);
        expect(SyntaxKind.OPEN_PAREN);
        ExpressionSyntax expression = parseExpression();
        expect(SyntaxKind.CLOSE_PAREN);
        return new ExpressionSyntax.Checked(finish(start), checked, expression);
    }

    private ExpressionSyntax parseSwitchExpression(ExpressionSyntax governing) {
        take();
        expect(SyntaxKind.OPEN_BRACE);
        List<AuxiliarySyntax.SwitchExpressionArm> arms = new ArrayList<>();
        while (!at(SyntaxKind.CLOSE_BRACE) && !at(SyntaxKind.END_OF_FILE)) {
            int start = current().span().start();
            PatternSyntax pattern = parseGuardedPattern();
            ExpressionSyntax guard = null;
            if (atContextual("when")) {
                take();
                guard = parseExpression();
            }
            expect(SyntaxKind.EQUALS_GREATER_THAN);
            ExpressionSyntax expression = parseExpression();
            arms.add(new AuxiliarySyntax.SwitchExpressionArm(finish(start), pattern, guard,
                    expression));
            if (!at(SyntaxKind.COMMA)) {
                break;
            }
            take();
        }
        expect(SyntaxKind.CLOSE_BRACE);
        return new ExpressionSyntax.Switch(
                SourceSpan.between(governing.span().start(), previousEnd()), governing, arms);
    }

    private ExpressionSyntax parseWithExpression(ExpressionSyntax receiver) {
        take();
        takeInlineBrace();
        List<AuxiliarySyntax.VariableDeclarator> initializers = new ArrayList<>();
        while (!at(SyntaxKind.CLOSE_BRACE) && !at(SyntaxKind.END_OF_FILE)) {
            int start = current().span().start();
            String name = expect(SyntaxKind.IDENTIFIER).text();
            expect(SyntaxKind.EQUALS);
            ExpressionSyntax value = parseExpression();
            initializers.add(AuxiliarySyntax.VariableDeclarator.named(
                    covering(start, value), name, value));
            if (!at(SyntaxKind.COMMA)) {
                break;
            }
            take();
        }
        expect(SyntaxKind.CLOSE_BRACE);
        return new ExpressionSyntax.With(
                SourceSpan.between(receiver.span().start(), previousEnd()), receiver,
                initializers);
    }

    @SuppressWarnings("unchecked")
    private ExpressionSyntax parseInterpolated(SyntaxToken token) {
        List<InterpolationPart> parts = (List<InterpolationPart>) token.value();
        List<AuxiliarySyntax.InterpolationElement> elements = new ArrayList<>();
        for (InterpolationPart part : parts) {
            switch (part) {
                case InterpolationPart.Text text -> elements.add(
                        new AuxiliarySyntax.InterpolationElement.Text(token.span(), text.value()));
                case InterpolationPart.Hole(SourceSpan expressionSpan, SourceSpan alignmentSpan,
                        String format) -> {
                    ExpressionSyntax expression = parseFragment(expressionSpan);
                    ExpressionSyntax alignment = alignmentSpan == null
                            ? null : parseFragment(alignmentSpan);
                    SourceSpan span = alignmentSpan == null
                            ? expressionSpan : expressionSpan.union(alignmentSpan);
                    elements.add(new AuxiliarySyntax.InterpolationElement.Hole(span, expression,
                            alignment, format));
                }
            }
        }
        return new ExpressionSyntax.Interpolated(token.span(), elements);
    }

    private ExpressionSyntax parseFragment(SourceSpan span) {
        if (span.isEmpty()) {
            return new ExpressionSyntax.Missing(span);
        }
        Parser fragment = new Parser(file, diagnostics, Lexer.tokenizeFragment(file, span,
                diagnostics));
        ExpressionSyntax expression = fragment.parseExpression();
        if (!fragment.at(SyntaxKind.END_OF_FILE)) {
            fragment.report(DiagnosticCode.UNEXPECTED_TOKEN, fragment.current().span());
        }
        return expression;
    }

    private List<AuxiliarySyntax.Argument> parseArgumentList(SyntaxKind open,
            SyntaxKind close) {
        expect(open);
        List<AuxiliarySyntax.Argument> result = new ArrayList<>();
        while (!at(close) && !at(SyntaxKind.END_OF_FILE)) {
            int start = current().span().start();
            String name = null;
            if (at(SyntaxKind.IDENTIFIER) && peek(1).is(SyntaxKind.COLON)) {
                name = take().text();
                take();
            }
            SyntaxKind modifier = null;
            if (at(SyntaxKind.REF) || at(SyntaxKind.OUT) || at(SyntaxKind.IN)) {
                modifier = take().kind();
            }
            ExpressionSyntax expression;
            if (modifier == SyntaxKind.OUT && scanType(index) >= 0
                    && tokenAt(scanType(index)).is(SyntaxKind.IDENTIFIER)) {
                TypeSyntax type = parseType();
                PatternSyntax designation = parseVariableDesignation();
                expression = new ExpressionSyntax.Declaration(
                        SourceSpan.between(type.span().start(), designation.span().end()), type,
                        designation);
            } else {
                expression = parseExpression();
            }
            result.add(new AuxiliarySyntax.Argument(finish(start), name, modifier, expression));
            if (!at(SyntaxKind.COMMA)) {
                break;
            }
            take();
        }
        expect(close);
        return List.copyOf(result);
    }

    private boolean looksLikeLambda() {
        int cursor = index;
        if (isContextualAt(cursor, "static") || isContextualAt(cursor, "async")
                || tokenAt(cursor).is(SyntaxKind.STATIC)) {
            cursor++;
        }
        if (tokenAt(cursor).is(SyntaxKind.DELEGATE)) {
            return true;
        }
        if (tokenAt(cursor).is(SyntaxKind.IDENTIFIER)
                && tokenAt(cursor + 1).is(SyntaxKind.EQUALS_GREATER_THAN)) {
            return true;
        }
        if (!tokenAt(cursor).is(SyntaxKind.OPEN_PAREN)) {
            // An explicit return type, as in `int (int x) => x`, precedes the parameter list.
            int afterType = scanType(cursor);
            if (afterType < 0 || afterType == cursor
                    || !tokenAt(afterType).is(SyntaxKind.OPEN_PAREN)) {
                return false;
            }
            cursor = afterType;
        }
        int after = skipBalanced(cursor, SyntaxKind.OPEN_PAREN, SyntaxKind.CLOSE_PAREN);
        return after > cursor && tokenAt(after).is(SyntaxKind.EQUALS_GREATER_THAN);
    }

    private ExpressionSyntax parseLambda() {
        int start = current().span().start();
        boolean isStatic = false;
        if (at(SyntaxKind.STATIC) || atContextual("static")) {
            take();
            isStatic = true;
        } else if (atContextual("async")) {
            SyntaxToken async = take();
            report(DiagnosticCode.ASYNC_LAMBDA_UNSUPPORTED, async.span());
        }
        if (at(SyntaxKind.DELEGATE)) {
            take();
            List<AuxiliarySyntax.Parameter> anonymousParameters = at(SyntaxKind.OPEN_PAREN)
                    ? parseParameterList(false) : List.of();
            StatementSyntax.Block body = parseBlock();
            return new ExpressionSyntax.Lambda(finish(start), isStatic, null, anonymousParameters,
                    body);
        }
        TypeSyntax returnType = null;
        if (!at(SyntaxKind.OPEN_PAREN)
                && !(at(SyntaxKind.IDENTIFIER) && peek(1).is(SyntaxKind.EQUALS_GREATER_THAN))) {
            returnType = parseType();
        }
        List<AuxiliarySyntax.Parameter> parameters;
        if (at(SyntaxKind.IDENTIFIER) && peek(1).is(SyntaxKind.EQUALS_GREATER_THAN)) {
            SyntaxToken name = take();
            parameters = List.of(new AuxiliarySyntax.Parameter(name.span(), List.of(), List.of(),
                    null, name.text(), null));
        } else {
            parameters = parseParameterList(true);
        }
        expect(SyntaxKind.EQUALS_GREATER_THAN);
        SyntaxNode body = at(SyntaxKind.OPEN_BRACE) ? parseBlock() : parseAssignmentExpression();
        return new ExpressionSyntax.Lambda(SourceSpan.between(start, body.span().end()), isStatic,
                returnType, parameters, body);
    }

    private List<AuxiliarySyntax.Parameter> parseParameterList(boolean allowImplicitTypes) {
        expect(SyntaxKind.OPEN_PAREN);
        List<AuxiliarySyntax.Parameter> result = new ArrayList<>();
        while (!at(SyntaxKind.CLOSE_PAREN) && !at(SyntaxKind.END_OF_FILE)) {
            int start = current().span().start();
            List<AuxiliarySyntax.AttributeList> attributes = parseAttributeLists();
            List<SyntaxKind> modifiers = new ArrayList<>();
            while (atContextual("scoped") || at(SyntaxKind.REF) || at(SyntaxKind.OUT)
                    || at(SyntaxKind.IN) || at(SyntaxKind.PARAMS) || at(SyntaxKind.THIS)) {
                if (atContextual("scoped")) {
                    take();
                    modifiers.add(SyntaxKind.SCOPED);
                } else {
                    modifiers.add(take().kind());
                }
            }
            TypeSyntax type = null;
            String name;
            if (allowImplicitTypes && at(SyntaxKind.IDENTIFIER)
                    && (peek(1).is(SyntaxKind.COMMA) || peek(1).is(SyntaxKind.CLOSE_PAREN)
                            || peek(1).is(SyntaxKind.EQUALS))) {
                name = take().text();
            } else {
                type = parseType();
                name = expect(SyntaxKind.IDENTIFIER).text();
            }
            ExpressionSyntax defaultValue = null;
            if (at(SyntaxKind.EQUALS)) {
                take();
                defaultValue = parseExpression();
            }
            result.add(new AuxiliarySyntax.Parameter(finish(start), attributes, modifiers, type,
                    name, defaultValue));
            if (!at(SyntaxKind.COMMA)) {
                break;
            }
            take();
        }
        expect(SyntaxKind.CLOSE_PAREN);
        return List.copyOf(result);
    }

    // ---- Patterns ---------------------------------------------------------------------

    private PatternSyntax parsePattern() {
        return parseOrPattern();
    }

    /// Parses a pattern in a position where `when` starts a guard instead of naming a
    /// variable: a `switch` case label or a switch-expression arm.
    private PatternSyntax parseGuardedPattern() {
        boolean saved = whenIsKeyword;
        whenIsKeyword = true;
        try {
            return parseOrPattern();
        } finally {
            whenIsKeyword = saved;
        }
    }

    /// Parses a subpattern nested inside a delimiter pair, where a guard can never follow
    /// directly, so `when` is an ordinary identifier again.
    private PatternSyntax parseNestedPattern() {
        boolean saved = whenIsKeyword;
        whenIsKeyword = false;
        try {
            return parseOrPattern();
        } finally {
            whenIsKeyword = saved;
        }
    }

    /// Decides whether the current identifier binds a pattern variable or continues the
    /// pattern grammar. `and`, `or` and `when` are contextual keywords: they are only
    /// designations when the token after them cannot continue a pattern, so `o is int and`
    /// binds `and` while `o is int and > 0` combines two patterns. This mirrors Roslyn's
    /// `IsValidPatternDesignation` and is what keeps `o is string or int n` from being read
    /// as a type pattern designating `or` followed by the stray tokens `int n`.
    private boolean atPatternDesignation() {
        if (!at(SyntaxKind.IDENTIFIER)) {
            return false;
        }
        return switch (current().text()) {
            case "when" -> !whenIsKeyword;
            case "and", "or" -> switch (peek(1).kind()) {
                case CLOSE_BRACE, CLOSE_BRACKET, CLOSE_PAREN, COMMA, SEMICOLON, COLON,
                        QUESTION, EQUALS_GREATER_THAN, END_OF_FILE -> true;
                default -> false;
            };
            default -> true;
        };
    }

    /// True when the pattern under the cursor starts with a keyword type (`int`, `string`,
    /// `nint`, ...). Those keywords can never begin a constant expression on their own, so a
    /// bare `o is double` is unambiguously a type pattern, while a bare identifier
    /// (`o is Red`) stays a constant pattern because only binding can tell the two apart.
    private boolean isPredefinedTypeStart() {
        return PREDEFINED_TYPES.contains(current().kind()) || isContextualType(current());
    }

    /// Tokens that can only follow a complete pattern. `and`, `or` and `when` are ordinary
    /// identifiers here and are handled by the designation path instead.
    private static boolean endsPattern(SyntaxToken token) {
        return switch (token.kind()) {
            case CLOSE_BRACE, CLOSE_BRACKET, CLOSE_PAREN, COMMA, SEMICOLON, COLON,
                    EQUALS_GREATER_THAN, END_OF_FILE -> true;
            default -> false;
        };
    }

    private PatternSyntax parseOrPattern() {
        PatternSyntax left = parseAndPattern();
        while (atContextual("or")) {
            take();
            PatternSyntax right = parseAndPattern();
            left = new PatternSyntax.Binary(
                    SourceSpan.between(left.span().start(), right.span().end()), left,
                    PatternSyntax.Binary.Kind.OR, right);
        }
        return left;
    }

    private PatternSyntax parseAndPattern() {
        PatternSyntax left = parseNotPattern();
        while (atContextual("and")) {
            take();
            PatternSyntax right = parseNotPattern();
            left = new PatternSyntax.Binary(
                    SourceSpan.between(left.span().start(), right.span().end()), left,
                    PatternSyntax.Binary.Kind.AND, right);
        }
        return left;
    }

    private PatternSyntax parseNotPattern() {
        if (atContextual("not")) {
            int start = take().span().start();
            PatternSyntax pattern = parseNotPattern();
            return new PatternSyntax.Not(covering(start, pattern), pattern);
        }
        return parsePrimaryPattern();
    }

    private PatternSyntax parsePrimaryPattern() {
        int start = current().span().start();
        if (atContextual("var")) {
            take();
            String name = expect(SyntaxKind.IDENTIFIER).text();
            return new PatternSyntax.Var(finish(start), name);
        }
        if (at(SyntaxKind.IDENTIFIER) && current().text().equals("_")) {
            return new PatternSyntax.Discard(take().span());
        }
        if (isRelationalOperator(current().kind())) {
            SyntaxToken operator = take();
            ExpressionSyntax value = parseUnaryExpression();
            return new PatternSyntax.Relational(covering(start, value), operator.kind(), value);
        }
        if (at(SyntaxKind.OPEN_BRACKET)) {
            return parseListPattern();
        }
        if (at(SyntaxKind.OPEN_BRACE)) {
            return parseRecursivePattern(null, start);
        }
        if (at(SyntaxKind.OPEN_PAREN)) {
            take();
            PatternSyntax first = parseNestedPattern();
            if (at(SyntaxKind.COMMA)) {
                List<PatternSyntax> positional = new ArrayList<>();
                positional.add(first);
                while (at(SyntaxKind.COMMA)) {
                    take();
                    positional.add(parseNestedPattern());
                }
                expect(SyntaxKind.CLOSE_PAREN);
                return new PatternSyntax.Recursive(finish(start), null, positional, List.of(),
                        parseOptionalDesignation());
            }
            expect(SyntaxKind.CLOSE_PAREN);
            return new PatternSyntax.Parenthesized(finish(start), first);
        }
        int typeEnd = scanType(index);
        if (typeEnd >= 0 && (tokenAt(typeEnd).is(SyntaxKind.IDENTIFIER)
                || tokenAt(typeEnd).is(SyntaxKind.OPEN_PAREN)
                || tokenAt(typeEnd).is(SyntaxKind.OPEN_BRACE)
                || (isPredefinedTypeStart() && endsPattern(tokenAt(typeEnd))))) {
            TypeSyntax type = parseType();
            if (at(SyntaxKind.OPEN_PAREN) || at(SyntaxKind.OPEN_BRACE)) {
                return parseRecursivePattern(type, start);
            }
            String name = atPatternDesignation() ? take().text() : null;
            return new PatternSyntax.Type(finish(start), type, name);
        }
        ExpressionSyntax constant = parseUnaryExpression();
        return new PatternSyntax.Constant(constant.span(), constant);
    }

    private PatternSyntax parseRecursivePattern(TypeSyntax type, int start) {
        List<PatternSyntax> positional = new ArrayList<>();
        if (at(SyntaxKind.OPEN_PAREN)) {
            take();
            while (!at(SyntaxKind.CLOSE_PAREN) && !at(SyntaxKind.END_OF_FILE)) {
                positional.add(parseNestedPattern());
                if (!at(SyntaxKind.COMMA)) {
                    break;
                }
                take();
            }
            expect(SyntaxKind.CLOSE_PAREN);
        }
        List<AuxiliarySyntax.PropertySubpattern> properties = new ArrayList<>();
        if (at(SyntaxKind.OPEN_BRACE)) {
            takeInlineBrace();
            while (!at(SyntaxKind.CLOSE_BRACE) && !at(SyntaxKind.END_OF_FILE)) {
                int propertyStart = current().span().start();
                StringBuilder name = new StringBuilder(expect(SyntaxKind.IDENTIFIER).text());
                while (at(SyntaxKind.DOT)) {
                    take();
                    name.append('.').append(expect(SyntaxKind.IDENTIFIER).text());
                }
                expect(SyntaxKind.COLON);
                PatternSyntax pattern = parseNestedPattern();
                properties.add(new AuxiliarySyntax.PropertySubpattern(
                        finish(propertyStart), name.toString(), pattern));
                if (!at(SyntaxKind.COMMA)) {
                    break;
                }
                take();
            }
            expect(SyntaxKind.CLOSE_BRACE);
        }
        return new PatternSyntax.Recursive(finish(start), type, positional, properties,
                parseOptionalDesignation());
    }

    private PatternSyntax parseListPattern() {
        int start = take().span().start();
        List<PatternSyntax> elements = new ArrayList<>();
        while (!at(SyntaxKind.CLOSE_BRACKET) && !at(SyntaxKind.END_OF_FILE)) {
            if (at(SyntaxKind.DOT_DOT)) {
                int sliceStart = take().span().start();
                PatternSyntax nested = at(SyntaxKind.COMMA) || at(SyntaxKind.CLOSE_BRACKET)
                        ? null : parseNestedPattern();
                elements.add(new PatternSyntax.Slice(
                        nested == null ? finish(sliceStart) : covering(sliceStart, nested), nested));
            } else {
                elements.add(parseNestedPattern());
            }
            if (!at(SyntaxKind.COMMA)) {
                break;
            }
            take();
        }
        expect(SyntaxKind.CLOSE_BRACKET);
        return new PatternSyntax.ListPattern(finish(start), elements,
                parseOptionalDesignation());
    }

    private PatternSyntax parseVariableDesignation() {
        int start = current().span().start();
        if (at(SyntaxKind.OPEN_PAREN)) {
            take();
            List<PatternSyntax> elements = new ArrayList<>();
            do {
                elements.add(parseVariableDesignation());
                if (!at(SyntaxKind.COMMA)) {
                    break;
                }
                take();
            } while (!at(SyntaxKind.CLOSE_PAREN) && !at(SyntaxKind.END_OF_FILE));
            expect(SyntaxKind.CLOSE_PAREN);
            return new PatternSyntax.Recursive(finish(start), null, elements, List.of(), null);
        }
        SyntaxToken name = expect(SyntaxKind.IDENTIFIER);
        return name.text().equals("_")
                ? new PatternSyntax.Discard(name.span())
                : new PatternSyntax.Var(name.span(), name.text());
    }

    private String parseOptionalDesignation() {
        return atPatternDesignation() ? take().text() : null;
    }

    // ---- Types ------------------------------------------------------------------------

    private TypeSyntax parseType() {
        return parseType(true);
    }

    private TypeSyntax parseType(boolean allowArraySuffix) {
        int start = current().span().start();
        if (at(SyntaxKind.REF)) {
            take();
            boolean readOnly = false;
            if (at(SyntaxKind.READONLY)) {
                take();
                readOnly = true;
            }
            TypeSyntax element = parseType(allowArraySuffix);
            return new TypeSyntax.Ref(covering(start, element), element, readOnly);
        }
        TypeSyntax result;
        if (PREDEFINED_TYPES.contains(current().kind())) {
            SyntaxToken keyword = take();
            result = new TypeSyntax.Predefined(keyword.span(), keyword.kind());
        } else if (isContextualType(current())) {
            SyntaxToken keyword = take();
            result = new TypeSyntax.Predefined(keyword.span(), contextualTypeKind(keyword.text()));
        } else if (at(SyntaxKind.OPEN_PAREN)) {
            result = parseTupleType();
        } else if (at(SyntaxKind.IDENTIFIER)) {
            result = parseQualifiedName();
        } else {
            report(DiagnosticCode.TYPE_EXPECTED, current().span());
            result = new TypeSyntax.Missing(SourceSpan.at(current().span().start()));
            if (!at(SyntaxKind.END_OF_FILE)) {
                take();
            }
        }
        if (at(SyntaxKind.QUESTION)) {
            SyntaxToken question = take();
            result = new TypeSyntax.Nullable(
                    SourceSpan.between(result.span().start(), question.span().end()), result);
        }
        if (allowArraySuffix && at(SyntaxKind.OPEN_BRACKET)) {
            List<Integer> ranks = new ArrayList<>();
            int arrayStart = result.span().start();
            while (at(SyntaxKind.OPEN_BRACKET)) {
                take();
                int rank = 1;
                while (at(SyntaxKind.COMMA)) {
                    take();
                    rank++;
                }
                expect(SyntaxKind.CLOSE_BRACKET);
                ranks.add(rank);
            }
            result = new TypeSyntax.Array(SourceSpan.between(arrayStart, previousEnd()), result,
                    ranks);
        }
        return result;
    }

    private TypeSyntax.Tuple parseTupleType() {
        int start = take().span().start();
        List<TypeSyntax.TupleElement> elements = new ArrayList<>();
        do {
            int elementStart = current().span().start();
            TypeSyntax type = parseType();
            String name = at(SyntaxKind.IDENTIFIER) ? take().text() : null;
            elements.add(new TypeSyntax.TupleElement(finish(elementStart), type, name));
            if (!at(SyntaxKind.COMMA)) {
                break;
            }
            take();
        } while (!at(SyntaxKind.CLOSE_PAREN) && !at(SyntaxKind.END_OF_FILE));
        expect(SyntaxKind.CLOSE_PAREN);
        if (elements.size() < 2) {
            SourceSpan point = SourceSpan.at(previousEnd());
            report(DiagnosticCode.TOKEN_EXPECTED, point, "','");
            elements.add(new TypeSyntax.TupleElement(point, new TypeSyntax.Missing(point), null));
        }
        return new TypeSyntax.Tuple(finish(start), elements);
    }

    private TypeSyntax.Name parseQualifiedName() {
        int start = current().span().start();
        boolean global = atContextual("global") && peek(1).is(SyntaxKind.COLON_COLON);
        if (global) {
            take();
            take();
        }
        List<TypeSyntax.Segment> segments = new ArrayList<>();
        segments.add(parseNameSegment());
        while (at(SyntaxKind.DOT)) {
            take();
            segments.add(parseNameSegment());
        }
        return new TypeSyntax.Name(finish(start), segments, global);
    }

    private TypeSyntax.Segment parseNameSegment() {
        int start = current().span().start();
        String name = expect(SyntaxKind.IDENTIFIER).text();
        List<TypeSyntax> arguments = at(SyntaxKind.LESS_THAN)
                ? parseTypeArgumentList(true) : List.of();
        return new TypeSyntax.Segment(finish(start), name, arguments);
    }

    private List<TypeSyntax> parseTypeArgumentList(boolean allowOmitted) {
        expect(SyntaxKind.LESS_THAN);
        List<TypeSyntax> result = new ArrayList<>();
        // The empty-looking list in `G<>` has one omitted argument. Commas then delimit
        // additional omissions (`G<,>` has two). Retaining those slots is what lets semantic
        // binding distinguish an unbound generic name from the non-generic spelling `G`.
        if (allowOmitted && at(SyntaxKind.GREATER_THAN)) {
            result.add(new TypeSyntax.Omitted(current().span()));
        }
        while (!at(SyntaxKind.GREATER_THAN) && !at(SyntaxKind.END_OF_FILE)) {
            if (allowOmitted && (at(SyntaxKind.COMMA) || at(SyntaxKind.GREATER_THAN))) {
                result.add(new TypeSyntax.Omitted(SourceSpan.at(current().span().start())));
            } else {
                result.add(parseType());
            }
            if (!at(SyntaxKind.COMMA)) {
                break;
            }
            take();
            if (allowOmitted && at(SyntaxKind.GREATER_THAN)) {
                result.add(new TypeSyntax.Omitted(SourceSpan.at(current().span().start())));
            }
        }
        expect(SyntaxKind.GREATER_THAN);
        return List.copyOf(result);
    }

    private boolean looksLikeExpressionTypeArguments() {
        if (!at(SyntaxKind.LESS_THAN)) {
            return false;
        }
        int after = scanTypeArgumentList(index);
        if (after < 0) {
            return false;
        }
        SyntaxKind kind = tokenAt(after).kind();
        return kind == SyntaxKind.OPEN_PAREN || kind == SyntaxKind.DOT
                || kind == SyntaxKind.QUESTION_DOT || kind == SyntaxKind.COLON_COLON;
    }

    // ---- Token-only lookahead ---------------------------------------------------------

    private int scanType(int from) {
        int cursor = from;
        if (tokenAt(cursor).is(SyntaxKind.REF)) {
            cursor++;
            if (tokenAt(cursor).is(SyntaxKind.READONLY)) {
                cursor++;
            }
        }
        SyntaxToken first = tokenAt(cursor);
        if (PREDEFINED_TYPES.contains(first.kind()) || isContextualType(first)) {
            cursor++;
        } else if (first.is(SyntaxKind.IDENTIFIER)) {
            if (isContextualAt(cursor, "global")
                    && tokenAt(cursor + 1).is(SyntaxKind.COLON_COLON)) {
                cursor += 2;
            }
            cursor = scanNameSegment(cursor);
            if (cursor < 0) {
                return -1;
            }
            while (tokenAt(cursor).is(SyntaxKind.DOT)) {
                cursor = scanNameSegment(cursor + 1);
                if (cursor < 0) {
                    return -1;
                }
            }
        } else if (first.is(SyntaxKind.OPEN_PAREN)) {
            cursor++;
            int elements = 0;
            while (true) {
                cursor = scanType(cursor);
                if (cursor < 0) {
                    return -1;
                }
                if (tokenAt(cursor).is(SyntaxKind.IDENTIFIER)) {
                    cursor++;
                }
                elements++;
                if (!tokenAt(cursor).is(SyntaxKind.COMMA)) {
                    break;
                }
                cursor++;
            }
            if (elements < 2 || !tokenAt(cursor).is(SyntaxKind.CLOSE_PAREN)) {
                return -1;
            }
            cursor++;
        } else {
            return -1;
        }
        if (tokenAt(cursor).is(SyntaxKind.QUESTION)) {
            cursor++;
        }
        while (tokenAt(cursor).is(SyntaxKind.OPEN_BRACKET)) {
            cursor++;
            while (tokenAt(cursor).is(SyntaxKind.COMMA)) {
                cursor++;
            }
            if (!tokenAt(cursor).is(SyntaxKind.CLOSE_BRACKET)) {
                return -1;
            }
            cursor++;
        }
        return cursor;
    }

    private int scanNameSegment(int from) {
        if (!tokenAt(from).is(SyntaxKind.IDENTIFIER)) {
            return -1;
        }
        int cursor = from + 1;
        return tokenAt(cursor).is(SyntaxKind.LESS_THAN)
                ? scanTypeArgumentList(cursor) : cursor;
    }

    private int scanTypeArgumentList(int from) {
        if (!tokenAt(from).is(SyntaxKind.LESS_THAN)) {
            return -1;
        }
        int cursor = from + 1;
        while (true) {
            int afterType = scanType(cursor);
            if (afterType < 0) {
                return -1;
            }
            cursor = afterType;
            if (!tokenAt(cursor).is(SyntaxKind.COMMA)) {
                break;
            }
            cursor++;
        }
        return tokenAt(cursor).is(SyntaxKind.GREATER_THAN) ? cursor + 1 : -1;
    }

    private int scanTypeParameterList(int from) {
        int cursor = from;
        if (!tokenAt(cursor).is(SyntaxKind.LESS_THAN)) {
            return cursor;
        }
        cursor++;
        while (tokenAt(cursor).is(SyntaxKind.IDENTIFIER)) {
            cursor++;
            if (!tokenAt(cursor).is(SyntaxKind.COMMA)) {
                break;
            }
            cursor++;
        }
        return tokenAt(cursor).is(SyntaxKind.GREATER_THAN) ? cursor + 1 : from;
    }

    private boolean looksLikeCast() {
        int afterType = scanType(index + 1);
        if (afterType < 0 || !tokenAt(afterType).is(SyntaxKind.CLOSE_PAREN)) {
            return false;
        }
        SyntaxToken after = tokenAt(afterType + 1);
        if (!canStartExpression(after)
                && !(PREDEFINED_TYPES.contains(after.kind())
                        && tokenAt(afterType + 2).is(SyntaxKind.DOT))) {
            return false;
        }
        boolean singleName = afterType == index + 2 && peek(1).is(SyntaxKind.IDENTIFIER);
        return !singleName || (!after.is(SyntaxKind.PLUS) && !after.is(SyntaxKind.MINUS));
    }

    private int skipBalanced(int from, SyntaxKind open, SyntaxKind close) {
        if (!tokenAt(from).is(open)) {
            return from;
        }
        int depth = 0;
        int cursor = from;
        while (!tokenAt(cursor).is(SyntaxKind.END_OF_FILE)) {
            if (tokenAt(cursor).is(open)) {
                depth++;
            } else if (tokenAt(cursor).is(close) && --depth == 0) {
                return cursor + 1;
            }
            cursor++;
        }
        return cursor;
    }

    private SyntaxToken tokenAt(int absoluteIndex) {
        return tokens.get(Math.min(Math.max(absoluteIndex, 0), tokens.size() - 1));
    }

    private boolean isContextualAt(int absoluteIndex, String text) {
        SyntaxToken token = tokenAt(absoluteIndex);
        return token.is(SyntaxKind.IDENTIFIER) && token.text().equals(text);
    }

    private static boolean isContextualType(SyntaxToken token) {
        return token.is(SyntaxKind.IDENTIFIER)
                && switch (token.text()) {
                    case "var", "dynamic", "nint", "nuint" -> true;
                    default -> false;
                };
    }

    private static SyntaxKind contextualTypeKind(String text) {
        return switch (text) {
            case "var" -> SyntaxKind.VAR;
            case "dynamic" -> SyntaxKind.DYNAMIC;
            case "nint" -> SyntaxKind.NINT;
            case "nuint" -> SyntaxKind.NUINT;
            default -> throw new IllegalArgumentException("not a contextual type: " + text);
        };
    }

    private static boolean isTypeStart(SyntaxToken token) {
        return PREDEFINED_TYPES.contains(token.kind()) || isContextualType(token)
                || token.is(SyntaxKind.IDENTIFIER) || token.is(SyntaxKind.REF)
                || token.is(SyntaxKind.OPEN_PAREN);
    }

    private static boolean isPrefixOperator(SyntaxKind kind) {
        return switch (kind) {
            case PLUS, MINUS, EXCLAMATION, TILDE, PLUS_PLUS, MINUS_MINUS,
                    CARET, AMPERSAND, ASTERISK, REF -> true;
            default -> false;
        };
    }

    private static boolean isRelationalOperator(SyntaxKind kind) {
        return switch (kind) {
            case LESS_THAN, LESS_THAN_EQUALS, GREATER_THAN, GREATER_THAN_EQUALS -> true;
            default -> false;
        };
    }

    private static boolean canStartExpression(SyntaxToken token) {
        if (token.kind().category() == SyntaxKind.Category.LITERAL
                || token.is(SyntaxKind.IDENTIFIER)) {
            return true;
        }
        return switch (token.kind()) {
            case TRUE, FALSE, NULL, OPEN_PAREN, OPEN_BRACKET, NEW, DEFAULT,
                    TYPEOF, SIZEOF, CHECKED, UNCHECKED, THROW, PLUS, MINUS,
                    EXCLAMATION, TILDE, PLUS_PLUS, MINUS_MINUS, CARET,
                    AMPERSAND, ASTERISK, REF, DOT_DOT, DELEGATE -> true;
            default -> false;
        };
    }

    private BinaryOperator binaryOperator() {
        SyntaxKind kind = current().kind();
        if (kind == SyntaxKind.GREATER_THAN) {
            if (greaterAssignmentOperator() != null) {
                return null;
            }
            int greater = adjacentGreaterCount(index);
            if (greater >= 2 && !adjacentEquals(index + Math.min(greater, 3))) {
                int count = Math.min(greater, 3);
                return new BinaryOperator(count == 3
                        ? SyntaxKind.GREATER_THAN_GREATER_THAN_GREATER_THAN
                        : SyntaxKind.GREATER_THAN_GREATER_THAN, 11, false, count);
            }
        }
        return switch (kind) {
            case ASTERISK, SLASH, PERCENT -> new BinaryOperator(kind, 13, false, 1);
            case PLUS, MINUS -> new BinaryOperator(kind, 12, false, 1);
            case LESS_THAN_LESS_THAN -> new BinaryOperator(kind, 11, false, 1);
            case LESS_THAN, LESS_THAN_EQUALS, GREATER_THAN, GREATER_THAN_EQUALS ->
                    new BinaryOperator(kind, 9, false, 1);
            case EQUALS_EQUALS, EXCLAMATION_EQUALS ->
                    new BinaryOperator(kind, 8, false, 1);
            case AMPERSAND -> new BinaryOperator(kind, 7, false, 1);
            case CARET -> new BinaryOperator(kind, 6, false, 1);
            case BAR -> new BinaryOperator(kind, 5, false, 1);
            case AMPERSAND_AMPERSAND -> new BinaryOperator(kind, 4, false, 1);
            case BAR_BAR -> new BinaryOperator(kind, 3, false, 1);
            case DOT_DOT -> new BinaryOperator(kind, 2, false, 1);
            case QUESTION_QUESTION -> new BinaryOperator(kind, 1, true, 1);
            default -> null;
        };
    }

    private record BinaryOperator(SyntaxKind kind, int precedence, boolean rightAssociative,
            int tokenCount) { }

    private AssignmentOperator assignmentOperator() {
        if (ASSIGNMENTS.contains(current().kind())) {
            return new AssignmentOperator(current().kind(), 1);
        }
        return greaterAssignmentOperator();
    }

    private AssignmentOperator greaterAssignmentOperator() {
        if (!at(SyntaxKind.GREATER_THAN)) {
            return null;
        }
        int plainGreater = adjacentGreaterCount(index);
        if (plainGreater >= 2 && adjacentEquals(index + Math.min(plainGreater, 3))) {
            int totalGreater = Math.min(plainGreater, 3);
            return new AssignmentOperator(totalGreater == 3
                    ? SyntaxKind.GREATER_THAN_GREATER_THAN_GREATER_THAN_EQUALS
                    : SyntaxKind.GREATER_THAN_GREATER_THAN_EQUALS, totalGreater + 1);
        }
        // The lexer correctly longest-matches the final `>=`, so `>>=` is emitted as
        // GREATER_THAN, GREATER_THAN_EQUALS and `>>>=` as two GREATER_THAN tokens plus
        // GREATER_THAN_EQUALS. Recombine that representation here as well.
        SyntaxToken terminal = tokenAt(index + plainGreater);
        SyntaxToken before = tokenAt(index + plainGreater - 1);
        if ((plainGreater == 1 || plainGreater == 2)
                && terminal.is(SyntaxKind.GREATER_THAN_EQUALS)
                && before.span().end() == terminal.span().start()) {
            int totalGreater = plainGreater + 1;
            return new AssignmentOperator(totalGreater == 3
                    ? SyntaxKind.GREATER_THAN_GREATER_THAN_GREATER_THAN_EQUALS
                    : SyntaxKind.GREATER_THAN_GREATER_THAN_EQUALS, plainGreater + 1);
        }
        return null;
    }

    private record AssignmentOperator(SyntaxKind kind, int tokenCount) { }

    private int adjacentGreaterCount(int from) {
        int count = 0;
        int cursor = from;
        int end = tokenAt(cursor).span().start();
        while (count < 3 && tokenAt(cursor).is(SyntaxKind.GREATER_THAN)
                && tokenAt(cursor).span().start() == end) {
            end = tokenAt(cursor).span().end();
            count++;
            cursor++;
        }
        return count;
    }

    private boolean adjacentEquals(int atIndex) {
        SyntaxToken equals = tokenAt(atIndex);
        SyntaxToken previous = tokenAt(atIndex - 1);
        return equals.is(SyntaxKind.EQUALS)
                && previous.span().end() == equals.span().start();
    }

    private void consume(int count) {
        for (int i = 0; i < count; i++) {
            take();
        }
    }

    private int previousEnd() {
        return index == 0 ? current().span().start() : tokenAt(index - 1).span().end();
    }
}
