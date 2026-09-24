package vsharp.tests.syntax;

import java.util.List;
import java.util.Random;
import java.util.Set;
import vsharp.compiler.diagnostics.Diagnostic;
import vsharp.compiler.diagnostics.DiagnosticBag;
import vsharp.compiler.source.SourceFile;
import vsharp.compiler.syntax.AuxiliarySyntax;
import vsharp.compiler.syntax.DeclarationSyntax;
import vsharp.compiler.syntax.ExpressionSyntax;
import vsharp.compiler.syntax.Parser;
import vsharp.compiler.syntax.PatternSyntax;
import vsharp.compiler.syntax.StatementSyntax;
import vsharp.compiler.syntax.SyntaxKind;
import vsharp.compiler.syntax.TypeSyntax;
import vsharp.testkit.Assert;
import vsharp.testkit.TestRegistry;
import vsharp.testkit.TestSuite;

/// Parser coverage for well-formed C# 13 syntax, subset boundaries and malformed-input
/// recovery. Tests inspect tree shape as well as diagnostics so accepting text without
/// preserving its structure cannot masquerade as parser support.
public final class ParserTests implements TestSuite {

    @Override
    public String suiteName() {
        return "syntax.parser";
    }

    @Override
    public void register(TestRegistry registry) {
        registerCompilationUnits(registry);
        registerTypesAndDeclarations(registry);
        registerExpressions(registry);
        registerPatterns(registry);
        registerStatements(registry);
        registerDirectivesAndRecovery(registry);
    }

    private static Parsed parse(String text) {
        SourceFile file = SourceFile.of("Test.vs", text);
        DiagnosticBag diagnostics = new DiagnosticBag();
        return new Parsed(Parser.parse(file, diagnostics), diagnostics);
    }

    private static AuxiliarySyntax.CompilationUnit clean(String text) {
        Parsed parsed = parse(text);
        Assert.isTrue(parsed.diagnostics().isEmpty(),
                "unexpected diagnostics for <" + text + ">: " + parsed.diagnostics().all());
        return parsed.unit();
    }

    private static ExpressionSyntax expression(String text) {
        SourceFile file = SourceFile.of("Expression.vs", text);
        DiagnosticBag diagnostics = new DiagnosticBag();
        ExpressionSyntax expression = Parser.parseExpression(file, diagnostics);
        Assert.isTrue(diagnostics.isEmpty(),
                "unexpected diagnostics for expression <" + text + ">: " + diagnostics.all());
        return expression;
    }

    private static List<String> errors(String text) {
        return parse(text).diagnostics().all().stream()
                .map(Diagnostic::code).map(Object::toString).toList();
    }

    private static void registerCompilationUnits(TestRegistry registry) {
        registry.test("global and static usings retain their distinctions", () -> {
            AuxiliarySyntax.CompilationUnit unit = clean(
                    "global using System; using static System.Math; using IO = System.IO;");
            Assert.equal(3L, unit.usings().size(), "using count");
            Assert.isTrue(unit.usings().get(0).global(), "global using");
            Assert.isTrue(unit.usings().get(1).isStatic(), "static using");
            Assert.equal("IO", unit.usings().get(2).alias(), "alias");
            Assert.equal("System.IO", unit.usings().get(2).target().text(), "alias target");
        });

        registry.test("file scoped namespace owns following declarations", () -> {
            AuxiliarySyntax.CompilationUnit unit = clean(
                    "namespace A.B; using System; static class Program { static void Main() {} }");
            DeclarationSyntax.Namespace namespace =
                    (DeclarationSyntax.Namespace) unit.declarations().getFirst();
            Assert.isTrue(namespace.fileScoped(), "file scoped");
            Assert.equal("A.B", namespace.name().text(), "qualified namespace");
            Assert.equal(1L, namespace.usings().size(), "namespace using");
            Assert.equal(1L, namespace.declarations().size(), "namespace declaration");
        });

        registry.test("braced namespaces and top level statements are distinct", () -> {
            AuxiliarySyntax.CompilationUnit unit = clean(
                    "namespace N { enum E { A } } int value = 1; value++; ");
            Assert.equal(1L, unit.declarations().size(), "namespace count");
            Assert.equal(2L, unit.statements().size(), "top-level statements");
        });
    }

    private static void registerTypesAndDeclarations(TestRegistry registry) {
        registry.test("static containers parse methods fields attributes and generics", () -> {
            AuxiliarySyntax.CompilationUnit unit = clean("""
                    [Marker]
                    public static class MathBox<T> where T : struct {
                        public const int One = 1;
                        [Pure] public static T Pick(T left, T right = default) => left;
                    }
                    """);
            DeclarationSyntax.StaticContainer container =
                    (DeclarationSyntax.StaticContainer) unit.declarations().getFirst();
            Assert.equal("MathBox", container.name(), "container name");
            Assert.equal(1L, container.attributes().size(), "container attributes");
            Assert.equal(1L, container.typeParameters().size(), "generic arity");
            Assert.equal(1L, container.constraints().size(), "constraint count");
            Assert.equal(2L, container.members().size(), "member count");
            DeclarationSyntax.Method method = (DeclarationSyntax.Method) container.members().get(1);
            Assert.notNull(method.expressionBody(), "expression body");
            Assert.notNull(method.parameters().get(1).defaultValue(), "default parameter");
        });

        registry.test("enum struct and record struct stay value declarations", () -> {
            AuxiliarySyntax.CompilationUnit unit = clean("""
                    enum Color : byte { Red = 1, Green, Blue, }
                    readonly record struct Point(int X, int Y);
                    struct Counter { int value; static int operator +(Counter a, Counter b) => 0; }
                    """);
            Assert.equal(3L, unit.declarations().size(), "declaration count");
            DeclarationSyntax.Enum enumeration =
                    (DeclarationSyntax.Enum) unit.declarations().get(0);
            Assert.equal(3L, enumeration.members().size(), "enum members");
            DeclarationSyntax.Struct record =
                    (DeclarationSyntax.Struct) unit.declarations().get(1);
            Assert.isTrue(record.record(), "record struct marker");
            Assert.equal(2L, record.primaryParameters().size(), "primary parameters");
            DeclarationSyntax.Struct structure =
                    (DeclarationSyntax.Struct) unit.declarations().get(2);
            Assert.isTrue(structure.members().get(1) instanceof DeclarationSyntax.Operator,
                    "operator member");
        });

        registry.test("conversion operators are explicit syntax nodes", () -> {
            AuxiliarySyntax.CompilationUnit unit = clean("""
                    struct Number {
                        int value;
                        public static implicit operator int(Number n) => n.value;
                        public static explicit operator Number(int n) => default;
                    }
                    """);
            DeclarationSyntax.Struct structure =
                    (DeclarationSyntax.Struct) unit.declarations().getFirst();
            Assert.isTrue(structure.members().get(1)
                    instanceof DeclarationSyntax.ConversionOperator, "implicit conversion");
            DeclarationSyntax.ConversionOperator explicit =
                    (DeclarationSyntax.ConversionOperator) structure.members().get(2);
            Assert.isFalse(explicit.implicit(), "explicit conversion flag");
        });

        registry.test("qualified generic nullable tuple and array types retain shape", () -> {
            AuxiliarySyntax.CompilationUnit unit = clean(
                    "static class C { static (int x, string y)?[,][] F"
                            + "(System.Map<string, System.List<int[]>> value); }");
            DeclarationSyntax.StaticContainer container =
                    (DeclarationSyntax.StaticContainer) unit.declarations().getFirst();
            DeclarationSyntax.Method method = (DeclarationSyntax.Method) container.members().getFirst();
            TypeSyntax.Array array = (TypeSyntax.Array) method.returnType();
            Assert.equalList(List.of(2, 1), array.ranks(), "array ranks");
            Assert.isTrue(array.element() instanceof TypeSyntax.Nullable, "nullable tuple");
            TypeSyntax.Name parameter = (TypeSyntax.Name) method.parameters().getFirst().type();
            Assert.equal("System.Map", parameter.text(), "qualified generic parameter");
            Assert.equal(2L, parameter.segments().getLast().typeArguments().size(),
                    "generic argument count");
        });

        registry.test("contextual type and modifier spellings remain distinct in the tree", () -> {
            AuxiliarySyntax.CompilationUnit unit = clean("""
                    partial static class Native {
                        static void F(scoped ref nint address, nuint size, dynamic value) {}
                    }
                    var inferred = 1;
                    """);
            DeclarationSyntax.StaticContainer container =
                    (DeclarationSyntax.StaticContainer) unit.declarations().getFirst();
            Assert.isTrue(container.modifiers().contains(SyntaxKind.PARTIAL),
                    "partial modifier kind");
            DeclarationSyntax.Method method =
                    (DeclarationSyntax.Method) container.members().getFirst();
            Assert.equalList(List.of(SyntaxKind.SCOPED, SyntaxKind.REF),
                    method.parameters().getFirst().modifiers(), "scoped ref modifiers");
            Assert.equal(SyntaxKind.NINT,
                    ((TypeSyntax.Predefined) method.parameters().getFirst().type()).keyword(),
                    "nint kind");
            Assert.equal(SyntaxKind.NUINT,
                    ((TypeSyntax.Predefined) method.parameters().get(1).type()).keyword(),
                    "nuint kind");
            Assert.equal(SyntaxKind.DYNAMIC,
                    ((TypeSyntax.Predefined) method.parameters().get(2).type()).keyword(),
                    "dynamic kind");
            StatementSyntax.LocalDeclaration inferred =
                    (StatementSyntax.LocalDeclaration) unit.statements().getFirst();
            Assert.equal(SyntaxKind.VAR,
                    ((TypeSyntax.Predefined) inferred.type()).keyword(), "var kind");
        });

        registry.test("object model declarations are diagnosed and recovery continues", () -> {
            Parsed parsed = parse("class C { int P { get; set; } } enum E { A }");
            Assert.equalList(List.of("VS20001"), parsed.diagnostics().all().stream()
                    .map(Diagnostic::code).map(Object::toString).toList(), "diagnostics");
            Assert.equal(2L, parsed.unit().declarations().size(), "recovered declaration count");
            Assert.isTrue(parsed.unit().declarations().get(1) instanceof DeclarationSyntax.Enum,
                    "enum after unsupported class");
        });
    }

    private static void registerExpressions(TestRegistry registry) {
        registry.test("binary precedence and conditional associativity match C sharp", () -> {
            ExpressionSyntax.Binary equality = (ExpressionSyntax.Binary)
                    ((ExpressionSyntax.Binary) expression("a + b * c == d && e")).left();
            Assert.equal(SyntaxKind.EQUALS_EQUALS, equality.operator(), "equality root below &&");
            ExpressionSyntax.Binary addition = (ExpressionSyntax.Binary) equality.left();
            Assert.equal(SyntaxKind.PLUS, addition.operator(), "addition");
            Assert.isTrue(addition.right() instanceof ExpressionSyntax.Binary multiply
                    && multiply.operator() == SyntaxKind.ASTERISK, "multiplication binds tighter");
            ExpressionSyntax.Conditional conditional =
                    (ExpressionSyntax.Conditional) expression("a ? b : c ? d : e");
            Assert.isTrue(conditional.whenFalse() instanceof ExpressionSyntax.Conditional,
                    "conditional is right associative");
        });

        registry.test("assignments and coalescing are right associative", () -> {
            ExpressionSyntax.Assignment assignment =
                    (ExpressionSyntax.Assignment) expression("a = b ?? c ?? d");
            ExpressionSyntax.Binary first = (ExpressionSyntax.Binary) assignment.value();
            Assert.equal(SyntaxKind.QUESTION_QUESTION, first.operator(), "coalescing operator");
            Assert.isTrue(first.right() instanceof ExpressionSyntax.Binary,
                    "coalescing is right associative");
        });

        registry.test("adjacent greater tokens become signed and unsigned shifts", () -> {
            ExpressionSyntax.Binary signed = (ExpressionSyntax.Binary) expression("a >> b");
            Assert.equal(SyntaxKind.GREATER_THAN_GREATER_THAN, signed.operator(), "signed shift");
            ExpressionSyntax.Assignment unsigned =
                    (ExpressionSyntax.Assignment) expression("a >>>= b");
            Assert.equal(SyntaxKind.GREATER_THAN_GREATER_THAN_GREATER_THAN_EQUALS,
                    unsigned.operator(), "unsigned shift assignment");
        });

        registry.test("postfix invocation member and null conditional chains retain order", () -> {
            ExpressionSyntax.Postfix postfix =
                    (ExpressionSyntax.Postfix) expression("source?.Map<int>(value)?[0]++");
            Assert.equal(SyntaxKind.PLUS_PLUS, postfix.operator(), "postfix operator");
            ExpressionSyntax.ElementAccess access =
                    (ExpressionSyntax.ElementAccess) postfix.operand();
            Assert.isTrue(access.nullConditional(), "conditional element access");
            ExpressionSyntax.Invocation invocation =
                    (ExpressionSyntax.Invocation) access.receiver();
            ExpressionSyntax.MemberAccess member =
                    (ExpressionSyntax.MemberAccess) invocation.target();
            Assert.isTrue(member.nullConditional(), "conditional member access");
            Assert.equal(1L, member.typeArguments().size(), "method type arguments");
        });

        registry.test("ranges collections arrays and with expressions have dedicated nodes", () -> {
            ExpressionSyntax.Collection collection =
                    (ExpressionSyntax.Collection) expression("[0, ..items, ^1]");
            Assert.equal(3L, collection.elements().size(), "collection element count");
            Assert.isTrue(collection.elements().get(1).spread(), "spread element");
            Assert.isTrue(collection.elements().get(2).expression()
                    instanceof ExpressionSyntax.Unary index
                    && index.operator() == SyntaxKind.CARET, "index-from-end");
            ExpressionSyntax.ArrayCreation array =
                    (ExpressionSyntax.ArrayCreation) expression("new int[3] { 1, 2, 3 }");
            Assert.equal(1L, array.dimensions().size(), "array dimensions");
            Assert.equal(3L, array.initializer().size(), "array initializer");
            ExpressionSyntax.With with = (ExpressionSyntax.With)
                    expression("point with { X = 1, Y = 2 }");
            Assert.equal(2L, with.initializers().size(), "with assignments");
        });

        registry.test("casts type operators defaults and nameof are preserved", () -> {
            Assert.isTrue(expression("(int)value") instanceof ExpressionSyntax.Cast, "cast");
            Assert.isTrue(expression("(int)char.MaxValue") instanceof ExpressionSyntax.Cast,
                    "cast of predefined type member");
            ExpressionSyntax.MemberAccess nativeLimit =
                    (ExpressionSyntax.MemberAccess) expression("nuint.MaxValue");
            Assert.isTrue(nativeLimit.receiver() instanceof ExpressionSyntax.Identifier,
                    "contextual native type remains an identifier until binding");
            Assert.isTrue(expression("typeof(System.List<>)")
                    instanceof ExpressionSyntax.TypeOperator, "typeof");
            Assert.isTrue(expression("sizeof(long)")
                    instanceof ExpressionSyntax.TypeOperator, "sizeof");
            Assert.isTrue(expression("default(int)") instanceof ExpressionSyntax.Default,
                    "typed default");
            Assert.isTrue(expression("nameof(value)") instanceof ExpressionSyntax.NameOf,
                    "nameof");
            Assert.isTrue(expression("checked(a + b)") instanceof ExpressionSyntax.Checked,
                    "checked expression");
        });

        registry.test("lambdas support inferred typed static and block forms", () -> {
            ExpressionSyntax.Lambda inferred = (ExpressionSyntax.Lambda) expression("x => x + 1");
            Assert.equal(1L, inferred.parameters().size(), "single parameter");
            Assert.equal(null, inferred.parameters().getFirst().type(), "inferred type");
            ExpressionSyntax.Lambda typed =
                    (ExpressionSyntax.Lambda) expression("(int x, int y = 1) => x + y");
            Assert.notNull(typed.parameters().getFirst().type(), "typed parameter");
            ExpressionSyntax.Lambda block =
                    (ExpressionSyntax.Lambda) expression("static (int x) => { return x; }");
            Assert.isTrue(block.isStatic(), "static lambda");
            Assert.isTrue(block.body() instanceof StatementSyntax.Block, "block body");
        });

        registry.test("anonymous methods share the lambda tree without an object delegate", () -> {
            ExpressionSyntax.Lambda anonymous = (ExpressionSyntax.Lambda)
                    expression("delegate (int x) { return x + 1; }");
            Assert.equal(1L, anonymous.parameters().size(), "anonymous parameter");
            Assert.isTrue(anonymous.body() instanceof StatementSyntax.Block, "anonymous body");
            ExpressionSyntax.Lambda staticAnonymous = (ExpressionSyntax.Lambda)
                    expression("static delegate { return; }");
            Assert.isTrue(staticAnonymous.isStatic(), "static anonymous method");
        });

        registry.test("rectangular jagged and nested array initializers retain shape", () -> {
            ExpressionSyntax.ArrayCreation array = (ExpressionSyntax.ArrayCreation)
                    expression("new int[2][] { { 1, 2 }, { 3, 4 } }");
            Assert.isTrue(array.elementType() instanceof TypeSyntax.Array,
                    "jagged element type");
            Assert.equal(2L, array.initializer().size(), "outer initializer size");
            Assert.isTrue(array.initializer().getFirst()
                    instanceof ExpressionSyntax.ArrayInitializer, "nested initializer");
            ExpressionSyntax.ArrayCreation rectangular = (ExpressionSyntax.ArrayCreation)
                    expression("new int[,] { { 1, 2 }, { 3, 4 } }");
            Assert.equal(2L, rectangular.dimensions().size(), "rectangular rank slots");
        });

        registry.test("interpolation holes re-enter the parser at absolute spans", () -> {
            ExpressionSyntax.Interpolated interpolated =
                    (ExpressionSyntax.Interpolated) expression("$\"sum={left + right,8:X}\"");
            Assert.equal(2L, interpolated.elements().size(), "text and hole");
            AuxiliarySyntax.InterpolationElement.Hole hole =
                    (AuxiliarySyntax.InterpolationElement.Hole) interpolated.elements().get(1);
            Assert.isTrue(hole.expression() instanceof ExpressionSyntax.Binary,
                    "parsed hole expression");
            Assert.notNull(hole.alignment(), "parsed alignment");
            Assert.equal("X", hole.format(), "format clause");
            Assert.equal("left + right", SourceFile.of("Expression.vs",
                    "$\"sum={left + right,8:X}\"").textOf(hole.expression().span()),
                    "absolute expression span");
        });

        registry.test("raw interpolation holes enter the same expression parser", () -> {
            String text = "$$\"\"\"{ left: {{left}}, sum: {{left + right}} }\"\"\"";
            ExpressionSyntax.Interpolated interpolated =
                    (ExpressionSyntax.Interpolated) expression(text);
            Assert.equal(5L, interpolated.elements().size(), "raw interpolation parts");
            AuxiliarySyntax.InterpolationElement.Hole second =
                    (AuxiliarySyntax.InterpolationElement.Hole) interpolated.elements().get(3);
            Assert.isTrue(second.expression() instanceof ExpressionSyntax.Binary,
                    "raw hole expression parsed");
            Assert.equal("left + right", SourceFile.of("Expression.vs", text)
                    .textOf(second.expression().span()), "raw hole absolute span");
        });

        registry.test("out declarations are expressions inside argument lists", () -> {
            ExpressionSyntax.Invocation invocation =
                    (ExpressionSyntax.Invocation) expression("Try(text, out var value)");
            Assert.isTrue(invocation.arguments().get(1).expression()
                    instanceof ExpressionSyntax.Declaration, "out declaration");
        });
    }

    private static void registerPatterns(TestRegistry registry) {
        registry.test("switch expressions parse logical relational and guarded patterns", () -> {
            ExpressionSyntax.Switch expression = (ExpressionSyntax.Switch) expression("""
                    value switch {
                        > 0 and < 10 => 1,
                        int n when n > 20 => n,
                        _ => 0,
                    }
                    """);
            Assert.equal(3L, expression.arms().size(), "arm count");
            Assert.isTrue(expression.arms().getFirst().pattern()
                    instanceof PatternSyntax.Binary binary
                    && binary.operator() == PatternSyntax.Binary.Kind.AND,
                    "and pattern");
            Assert.notNull(expression.arms().get(1).guard(), "when guard");
            Assert.isTrue(expression.arms().getLast().pattern()
                    instanceof PatternSyntax.Discard, "discard arm");
        });

        registry.test("and or and when never read as pattern designations", () -> {
            // `and`, `or` and `when` are contextual: they only designate a variable when the
            // token after them cannot continue the pattern. `int and > 0` is a combinator,
            // while `int and` (nothing follows) really does name a variable `and`.
            ExpressionSyntax.IsPattern combinator =
                    (ExpressionSyntax.IsPattern) expression("o is int and > 0");
            Assert.isTrue(combinator.pattern() instanceof PatternSyntax.Binary binary
                    && binary.operator() == PatternSyntax.Binary.Kind.AND
                    && binary.left() instanceof PatternSyntax.Type type
                    && type.name() == null,
                    "and reads as a combinator, not a designation");
            ExpressionSyntax.IsPattern designation =
                    (ExpressionSyntax.IsPattern) expression("o is int and");
            Assert.isTrue(designation.pattern() instanceof PatternSyntax.Type type
                    && "and".equals(type.name()),
                    "trailing and reads as a designation");
            // At a switch label or arm a guard may follow, so `when` is always the guard
            // keyword there (Roslyn's `whenIsKeyword`): `int when when > 20` is the type
            // pattern `int`, guarded by the expression `when > 20`.
            ExpressionSyntax.Switch guarded = (ExpressionSyntax.Switch) expression("""
                    value switch {
                        int when when > 20 => 1,
                        _ => 0,
                    }
                    """);
            Assert.isTrue(guarded.arms().getFirst().pattern() instanceof PatternSyntax.Type type
                    && type.name() == null,
                    "when starts the guard at an arm");
            Assert.notNull(guarded.arms().getFirst().guard(), "guard still parses");
            ExpressionSyntax.IsPattern nested =
                    (ExpressionSyntax.IsPattern) expression("o is Point(int when, int x)");
            Assert.isTrue(((PatternSyntax.Recursive) nested.pattern()).positional().getFirst()
                    instanceof PatternSyntax.Type type && "when".equals(type.name()),
                    "when designates inside a subpattern");
        });

        registry.test("recursive property positional and list slice patterns parse", () -> {
            ExpressionSyntax.IsPattern property = (ExpressionSyntax.IsPattern)
                    expression("point is Point(1, var y) { X: > 0 } p");
            PatternSyntax.Recursive recursive = (PatternSyntax.Recursive) property.pattern();
            Assert.equal(2L, recursive.positional().size(), "positional patterns");
            Assert.equal(1L, recursive.properties().size(), "property patterns");
            Assert.equal("p", recursive.designation(), "recursive designation");
            ExpressionSyntax.IsPattern list = (ExpressionSyntax.IsPattern)
                    expression("items is [1, .. var rest, 3]");
            Assert.isTrue(((PatternSyntax.ListPattern) list.pattern()).elements().get(1)
                    instanceof PatternSyntax.Slice, "slice pattern");
        });
    }

    private static void registerStatements(TestRegistry registry) {
        registry.test("conditionals and all loop forms parse as top level statements", () -> {
            AuxiliarySyntax.CompilationUnit unit = clean("""
                    if (ready) Run(); else Stop();
                    while (Next()) { continue; }
                    do { value++; } while (value < 10);
                    for (int i = 0; i < 10; i++) { if (i == 5) break; }
                    foreach (var item in items) Use(item);
                    """);
            Assert.equal(5L, unit.statements().size(), "statement count");
            Assert.isTrue(unit.statements().get(3) instanceof StatementSyntax.For,
                    "for statement");
            Assert.isTrue(unit.statements().get(4) instanceof StatementSyntax.Foreach,
                    "foreach statement");
        });

        registry.test("deconstruction locals foreach and labels retain designations", () -> {
            AuxiliarySyntax.CompilationUnit unit = clean("""
                    var (left, right) = pair;
                    foreach (var (key, value) in pairs) { use: Use(key, value); }
                    """);
            StatementSyntax.LocalDeclaration local =
                    (StatementSyntax.LocalDeclaration) unit.statements().getFirst();
            Assert.notNull(local.variables().getFirst().designation(), "local deconstruction");
            StatementSyntax.Foreach loop =
                    (StatementSyntax.Foreach) unit.statements().get(1);
            Assert.isTrue(loop.variable() instanceof PatternSyntax.Recursive,
                    "foreach deconstruction");
        });

        registry.test("switch statements preserve labels guards goto case and default", () -> {
            StatementSyntax.Switch statement = (StatementSyntax.Switch) clean("""
                    switch (value) {
                        case int n when n > 0: goto case 0;
                        case 0: break;
                        default: goto done;
                    }
                    done: return;
                    """).statements().getFirst();
            Assert.equal(3L, statement.sections().size(), "switch sections");
            Assert.notNull(statement.sections().getFirst().labels().getFirst().guard(),
                    "case guard");
            Assert.isTrue(statement.sections().getFirst().statements().getFirst()
                    instanceof StatementSyntax.Goto, "goto case");
        });

        registry.test("try filters using lock and checked blocks parse", () -> {
            AuxiliarySyntax.CompilationUnit unit = clean("""
                    using System;
                    try { Work(); }
                    catch (Exception error) when (CanHandle(error)) { Recover(); }
                    finally { Clean(); }
                    using (Resource resource = Open()) { Use(resource); }
                    using Resource second = Open();
                    lock (gate) { checked { total += value; } }
                    """);
            Assert.isTrue(unit.statements().get(0) instanceof StatementSyntax.Try,
                    "try statement");
            StatementSyntax.Try attempt = (StatementSyntax.Try) unit.statements().get(0);
            Assert.equal(1L, attempt.catches().size(), "catch count");
            Assert.notNull(attempt.catches().getFirst().filter(), "exception filter");
            Assert.isTrue(unit.statements().get(1) instanceof StatementSyntax.Using,
                    "using statement");
            Assert.isTrue(unit.statements().get(2) instanceof StatementSyntax.LocalDeclaration,
                    "using declaration");
            Assert.isTrue(unit.statements().get(3) instanceof StatementSyntax.Lock,
                    "lock statement");
        });

        registry.test("local functions and iterator statements have dedicated nodes", () -> {
            AuxiliarySyntax.CompilationUnit unit = clean("""
                    int Add(int x, int y) => x + y;
                    IEnumerable<int> Values() { yield return 1; yield break; }
                    """);
            Assert.isTrue(unit.statements().getFirst() instanceof StatementSyntax.LocalFunction,
                    "expression-bodied local function");
            StatementSyntax.LocalFunction iterator =
                    (StatementSyntax.LocalFunction) unit.statements().get(1);
            Assert.isTrue(iterator.declaration().body().statements().getFirst()
                    instanceof StatementSyntax.Yield, "yield return");
        });
    }

    private static void registerDirectivesAndRecovery(TestRegistry registry) {
        registry.test("preprocessing directives retain kind argument order and spans", () -> {
            AuxiliarySyntax.CompilationUnit unit = clean("""
                    #define DEBUG
                    #if DEBUG
                    int value = 1;
                    #else
                    int value = 2;
                    #endif
                    #pragma warning disable 1234
                    """);
            Assert.equal(5L, unit.directives().size(), "directive count");
            Assert.equal(AuxiliarySyntax.Directive.Kind.DEFINE,
                    unit.directives().getFirst().kind(), "define kind");
            Assert.equal("DEBUG", unit.directives().getFirst().argument(), "define argument");
            Assert.equal("warning disable 1234", unit.directives().getLast().argument(),
                    "pragma argument");
        });

        registry.test("inactive conditional branches never reach the lexer", () -> {
            AuxiliarySyntax.CompilationUnit unit = clean("""
                    #if false
                    "unterminated
                    @@@ invalid !!!
                    #else
                    int active = 1;
                    #endif
                    """);
            Assert.equal(1L, unit.statements().size(), "only active branch is parsed");
            StatementSyntax.LocalDeclaration declaration =
                    (StatementSyntax.LocalDeclaration) unit.statements().getFirst();
            Assert.equal("active", declaration.variables().getFirst().name(),
                    "active declaration");
        });

        registry.test("initial symbols and nested Boolean directive expressions select code", () -> {
            String text = """
                    #if FEATURE && !DISABLED
                    int selected = 1;
                    #else
                    int rejected = 2;
                    #endif
                    """;
            SourceFile file = SourceFile.of("Symbols.vs", text);
            DiagnosticBag diagnostics = new DiagnosticBag();
            AuxiliarySyntax.CompilationUnit unit =
                    Parser.parse(file, Set.of("FEATURE"), diagnostics);
            Assert.isTrue(diagnostics.isEmpty(), "symbol preprocessing diagnostics");
            StatementSyntax.LocalDeclaration declaration =
                    (StatementSyntax.LocalDeclaration) unit.statements().getFirst();
            Assert.equal("selected", declaration.variables().getFirst().name(),
                    "selected branch");
        });

        registry.test("unbalanced and malformed conditional directives are deterministic", () -> {
            Assert.equalList(List.of("VS1517", "VS1027"),
                    errors("#if (true &&)\nint x = 1;"), "conditional diagnostics");
            Assert.equalList(List.of("VS1028"), errors("#else\nint x = 1;"),
                    "orphan else");
        });

        registry.test("missing semicolons use the stable Roslyn diagnostic", () -> {
            Assert.equalList(List.of("VS1002"), errors("int value = 1"), "missing semicolon");
        });

        registry.test("missing delimiters recover into following statements", () -> {
            Parsed parsed = parse("if (ready { Run(); } Stop();");
            Assert.isTrue(parsed.diagnostics().hasErrors(), "delimiter diagnostic");
            Assert.isTrue(parsed.unit().statements().size() >= 1,
                    "tree returned despite malformed input");
        });

        registry.test("unknown directives are reported without swallowing the next line", () -> {
            Parsed parsed = parse("#mystery value\nint x = 1;");
            Assert.equalList(List.of("VS1024"), parsed.diagnostics().all().stream()
                    .map(Diagnostic::code).map(Object::toString).toList(), "directive error");
            Assert.equal(1L, parsed.unit().statements().size(), "following statement");
        });

        registry.test("diagnostic order is deterministic after multi-error recovery", () -> {
            List<String> first = errors("if ( { return } @ #unknown\nint x");
            List<String> second = errors("if ( { return } @ #unknown\nint x");
            Assert.equalList(first, second, "repeat parse diagnostic order");
            Assert.isTrue(first.size() >= 3, "multiple errors reported");
        });

        registry.test("every punctuation token makes progress under recovery", () -> {
            Parsed parsed = parse("} ] ) , : :: => ??= ### ;");
            Assert.notNull(parsed.unit(), "recovery tree");
            Assert.isTrue(parsed.diagnostics().hasErrors(), "malformed punctuation diagnosed");
        });

        registry.test("deterministic generated malformed inputs always terminate", () -> {
            Random random = new Random(0x565348415250L);
            String alphabet = "abcXYZ019_+-*/%&|^!?=<>.,:;()[]{}@#'\" \n";
            for (int sample = 0; sample < 500; sample++) {
                int length = random.nextInt(80);
                StringBuilder text = new StringBuilder(length);
                for (int i = 0; i < length; i++) {
                    text.append(alphabet.charAt(random.nextInt(alphabet.length())));
                }
                SourceFile file = SourceFile.of("Generated" + sample + ".vs", text.toString());
                DiagnosticBag diagnostics = new DiagnosticBag();
                Assert.notNull(Parser.parse(file, diagnostics),
                        "generated input " + sample + " returns a tree");
            }
        });

        registry.test("root and child spans cover their exact source extent", () -> {
            String text = "int value = (1 + 2);";
            AuxiliarySyntax.CompilationUnit unit = clean(text);
            Assert.equal(text.length(), unit.span().length(), "root span");
            StatementSyntax.LocalDeclaration local =
                    (StatementSyntax.LocalDeclaration) unit.statements().getFirst();
            Assert.equal(text, SourceFile.of("Test.vs", text).textOf(local.span()),
                    "statement span");
        });
    }

    private record Parsed(AuxiliarySyntax.CompilationUnit unit, DiagnosticBag diagnostics) { }
}
