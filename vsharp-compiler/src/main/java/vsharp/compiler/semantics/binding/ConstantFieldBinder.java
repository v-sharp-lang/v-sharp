package vsharp.compiler.semantics.binding;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import vsharp.compiler.diagnostics.DiagnosticBag;
import vsharp.compiler.diagnostics.DiagnosticCode;
import vsharp.compiler.semantics.constants.ConstantEvaluator;
import vsharp.compiler.semantics.constants.ConstantValue;
import vsharp.compiler.semantics.symbols.FieldSymbol;
import vsharp.compiler.semantics.symbols.Scope;
import vsharp.compiler.semantics.types.BuiltinType;
import vsharp.compiler.semantics.types.TypeSymbol;
import vsharp.compiler.source.SourceFile;
import vsharp.compiler.syntax.AuxiliarySyntax;
import vsharp.compiler.syntax.DeclarationSyntax;
import vsharp.compiler.syntax.SyntaxKind;
import vsharp.compiler.syntax.TypeSyntax;

/// Folds every `const` field initializer in a compilation, on demand and exactly once.
///
/// C# §15.4 makes a constant's initializer a *constant expression*, and §12.23 defines a
/// constant expression over bound operands - literals, operators, conversions, and reads of
/// other constants. Declaration binding cannot fold one, because it is the pass that creates
/// the symbols those reads resolve to: `const int Doubled = Base * 2;` has no bound form until
/// `Base` exists. It used to fold initializers anyway, with a private literal-only folder over
/// syntax, and that folder answered "cannot fold" for `int.MaxValue`, for a read of another
/// constant, and for `1 / 0` - which is a *diagnosable* division by constant zero, not an
/// unsupported shape. This pass replaces it: initializers are bound with the real
/// binder and folded with the real [ConstantEvaluator], so a `const` field supports exactly
/// what a `const` local already supported.
///
/// Resolution is on demand rather than in declaration order, because a constant may read a
/// constant declared later, in another type, or in another file; the order that works is the
/// dependency order, and only the bound tree knows it. Re-entering a constant that is already
/// being folded *is* the circular definition C# reports as CS0110, which is what bounds the
/// recursion.
///
/// The pass runs after declaration binding and before expression binding, so every consumer
/// downstream - conversions, flow analysis, lowering, the backend - observes constants that
/// are already folded.
public final class ConstantFieldBinder {

    /// One `const` declarator waiting to be folded, with everything binding its initializer
    /// needs: the file and unit it was written in, the scope its names resolve against, and
    /// the namespace body whose `using` directives apply to it.
    private record Pending(SourceFile file, AuxiliarySyntax.CompilationUnit unit, Scope scope,
            String namespaceName, AuxiliarySyntax.VariableDeclarator variable,
            FieldSymbol symbol) {}

    private final SemanticModel model;
    private final DiagnosticBag diagnostics;
    private final IdentityHashMap<FieldSymbol, Pending> pending = new IdentityHashMap<>();
    private final List<Pending> order = new ArrayList<>();
    /// The constants currently being folded, innermost last. A constant that is already here
    /// when its own fold asks for it again is a circular definition.
    private final Set<FieldSymbol> folding = Collections.newSetFromMap(new IdentityHashMap<>());
    /// The constants a circular definition already accounted for. Every member of a cycle
    /// fails, but only the constant the cycle closed on is reported, so one circular
    /// definition produces one diagnostic rather than one per participant.
    private final Set<FieldSymbol> circular = Collections.newSetFromMap(new IdentityHashMap<>());

    private ConstantFieldBinder(SemanticModel model, DiagnosticBag diagnostics) {
        this.model = model;
        this.diagnostics = diagnostics;
    }

    /// Folds the initializer of every `const` field declared by `units`, reporting the ones
    /// that have no constant value.
    ///
    /// `files` and `units` are the whole compilation, corelib included: a constant declared in
    /// one file is readable from every other, so nothing can be folded from a single file's
    /// declarations alone.
    public static void resolve(List<SourceFile> files,
            List<AuxiliarySyntax.CompilationUnit> units, SemanticModel model,
            DiagnosticBag diagnostics) {
        Objects.requireNonNull(files, "files");
        Objects.requireNonNull(units, "units");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(diagnostics, "diagnostics");
        if (files.size() != units.size()) {
            throw new IllegalArgumentException("files and units must be equal in size");
        }
        ConstantFieldBinder binder = new ConstantFieldBinder(model, diagnostics);
        for (int index = 0; index < files.size(); index++) {
            binder.collect(files.get(index), units.get(index));
        }
        // Declaration order across the compilation, which is what makes the diagnostics of a
        // cyclic or unfoldable constant the same on every run. The bag sorts by position for
        // display; this ordering decides which member of a cycle is the one reported.
        for (Pending entry : binder.order) {
            binder.fold(entry.symbol());
        }
    }

    // ---- Collection ----------------------------------------------------------------

    /// Walks one file for `const` declarators, deriving each one's scope exactly as
    /// [ExpressionBinder] does: a member binds in its declaring type's scope, a top-level
    /// declaration in the file's scope, which is the one carrying its `using` directives.
    private void collect(SourceFile file, AuxiliarySyntax.CompilationUnit unit) {
        Scope global = model.scopeFor(unit).orElse(model.globalScope());
        for (DeclarationSyntax declaration : unit.declarations()) {
            collectDeclaration(file, unit, declaration, global, "");
        }
    }

    private void collectDeclaration(SourceFile file, AuxiliarySyntax.CompilationUnit unit,
            DeclarationSyntax declaration, Scope enclosing, String namespaceName) {
        switch (declaration) {
            case DeclarationSyntax.Namespace namespace -> {
                Scope scope = model.scopeFor(namespace).orElse(enclosing);
                String inner = joinNamespace(namespaceName, namespace.name());
                for (DeclarationSyntax member : namespace.declarations()) {
                    collectDeclaration(file, unit, member, scope, inner);
                }
            }
            case DeclarationSyntax.StaticContainer container -> {
                Scope scope = model.scopeFor(container).orElse(enclosing);
                for (DeclarationSyntax member : container.members()) {
                    collectDeclaration(file, unit, member, scope, namespaceName);
                }
            }
            case DeclarationSyntax.Struct structure -> {
                Scope scope = model.scopeFor(structure).orElse(enclosing);
                for (DeclarationSyntax member : structure.members()) {
                    collectDeclaration(file, unit, member, scope, namespaceName);
                }
            }
            case DeclarationSyntax.Field field ->
                collectField(file, unit, field, enclosing, namespaceName);
            // Nothing else declares a field: an enum declares members, whose values are
            // assigned by the enum's own succession rule, and a callable declares locals,
            // whose `const` form is folded where it is bound.
            case DeclarationSyntax.Enum ignored -> { }
            case DeclarationSyntax.Method ignored -> { }
            case DeclarationSyntax.Operator ignored -> { }
            case DeclarationSyntax.ConversionOperator ignored -> { }
            case DeclarationSyntax.Unsupported ignored -> { }
        }
    }

    private void collectField(SourceFile file, AuxiliarySyntax.CompilationUnit unit,
            DeclarationSyntax.Field field, Scope scope, String namespaceName) {
        if (!field.modifiers().contains(SyntaxKind.CONST)) {
            return;
        }
        for (AuxiliarySyntax.VariableDeclarator variable : field.variables()) {
            if (variable.name() == null || variable.initializer() == null) {
                continue;
            }
            if (!(model.declaredSymbol(variable).orElse(null) instanceof FieldSymbol symbol)
                    || symbol.constant().isResolved()) {
                continue;
            }
            Pending entry = new Pending(file, unit, scope, namespaceName, variable, symbol);
            pending.put(symbol, entry);
            order.add(entry);
        }
    }

    private static String joinNamespace(String enclosing, TypeSyntax.Name name) {
        String written = name.segments().stream()
                .map(TypeSyntax.Segment::identifier)
                .reduce((left, right) -> left + "." + right)
                .orElse("");
        if (written.isEmpty()) {
            return enclosing;
        }
        return enclosing.isEmpty() ? written : enclosing + "." + written;
    }

    // ---- Folding -------------------------------------------------------------------

    /// Folds one constant, and everything it reads, exactly once.
    private void fold(FieldSymbol symbol) {
        Pending entry = pending.get(symbol);
        if (entry == null || symbol.constant().isResolved()) {
            return;
        }
        if (!folding.add(symbol)) {
            // The fold of this constant reached itself. Every constant on the stack is part
            // of the cycle and none of them has a value, but the diagnostic belongs to the
            // one the cycle closed on - the name a reader would have to change.
            diagnostics.report(DiagnosticCode.CIRCULAR_CONSTANT_DEFINITION, entry.file(),
                    entry.variable().span(), symbol.name());
            circular.addAll(folding);
            return;
        }
        try {
            // The initializer is bound again by the ordinary pass, which is the pass that
            // reports what is wrong with it - a division by a constant zero, an overflow, an
            // unresolved name. Binding it here into a scratch bag both keeps those from being
            // said twice and tells this pass whether a failed fold is a defect of the
            // evaluator or just the consequence of an error already reported.
            DiagnosticBag scratch = new DiagnosticBag();
            BoundExpression bound = ExpressionBinder.bindConstantInitializer(entry.file(),
                    entry.unit(), model, entry.scope(), entry.namespaceName(),
                    entry.variable().initializer(), scratch);
            foldReadConstants(bound);
            if (circular.contains(symbol)) {
                symbol.constant().resolve(null);
                return;
            }
            // A constant expression is always evaluated in a checked context (C# §12.8.20),
            // whichever context it is written in; `unchecked(...)` inside it still overrides
            // that for its own operand.
            ConstantEvaluator.Result result = ConstantEvaluator.evaluate(bound, true);
            boolean isConstant = result instanceof ConstantEvaluator.Result.Value;
            symbol.constant().resolve(isConstant
                    ? convert(((ConstantEvaluator.Result.Value) result).value(), bound.type(),
                            entry.symbol().type())
                    : null);
            if (!isConstant && !scratch.hasErrors()) {
                // A constant with no value has no run-time entity to read, so accepting it
                // would emit reads of a field nothing ever assigns. What reaches here
                // bound cleanly and still did not fold, which is precisely C#'s definition of
                // a non-constant expression - a call, a read of an ordinary field, a
                // concatenation with an enum operand - so CS0133 is the answer, not a
                // complaint about this build's reach. Anything actually invalid was already
                // reported by the binding above, and will be reported again, in place, by the
                // ordinary expression pass.
                //
                // A value the *declared type* cannot hold is not one of them. `const byte B =
                // 300;` is a constant expression whose conversion fails, which is CS0031 from
                // the ordinary pass against the same initializer; the slot simply stays
                // valueless, and saying anything here would say it twice.
                diagnostics.report(DiagnosticCode.CONSTANT_INITIALIZER_REQUIRED, entry.file(),
                        entry.variable().initializer().span(),
                        entry.symbol().qualifiedName());
            }
        } finally {
            folding.remove(symbol);
        }
    }

    /// Folds every constant `expression` reads, so that the evaluator finds their values.
    ///
    /// Only the shapes [ConstantEvaluator] descends into are walked: any other shape is not a
    /// constant expression, so the constants under it can never be needed to fold this one,
    /// and resolving them here would only move their diagnostics to the wrong declaration.
    private void foldReadConstants(BoundExpression expression) {
        switch (expression) {
            case BoundExpression.Value value -> {
                if (value.symbol() instanceof FieldSymbol field) {
                    fold(field);
                }
            }
            case BoundExpression.Parenthesized parenthesized ->
                foldReadConstants(parenthesized.expression());
            case BoundExpression.Unary unary -> foldReadConstants(unary.operand());
            case BoundExpression.Binary binary -> {
                foldReadConstants(binary.left());
                foldReadConstants(binary.right());
            }
            case BoundExpression.Conditional conditional -> {
                foldReadConstants(conditional.condition());
                foldReadConstants(conditional.whenTrue());
                foldReadConstants(conditional.whenFalse());
            }
            case BoundExpression.Conversion conversion ->
                foldReadConstants(conversion.operand());
            case BoundExpression.Deferred deferred ->
                deferred.children().forEach(this::foldReadConstants);
            default -> {
                // Not a constant-expression shape; the fold will say so.
            }
        }
    }

    // ---- Conversion to the declared type -------------------------------------------

    /// The folded value as a constant *of the declared type*, or `null` when the initializer
    /// does not denote one.
    ///
    /// C# §15.4 gives a constant the type it was declared with, not the type its initializer
    /// happened to have: `const long Ticks = 5;` is a `long` whose value is `5L`, and the
    /// backend emits the field's own carrier, so a value left in the `int` shape would be
    /// loaded with the wrong instruction. The conversion itself is [ConstantEvaluator#convert],
    /// the same table a cast written *inside* an initializer folds through - one answer to
    /// what a constant is worth in a type, rather than a second one that could disagree with
    /// the first.
    ///
    /// A value the target cannot hold answers `null`, and the caller stays silent about it:
    /// the ordinary expression pass checks this initializer against this type and reports
    /// CS0031, with the span C# puts it on.
    private static ConstantValue convert(ConstantValue value, TypeSymbol source,
            TypeSymbol declared) {
        TypeSymbol target = declared instanceof TypeSymbol.Nullable nullable
                ? nullable.element()
                : declared;
        if (value instanceof ConstantValue.Null) {
            // Only a reference-shaped constant can be null, and C# allows exactly `string`
            // and the reference types whose only constant is `null` (§15.4).
            return target == BuiltinType.STRING || !(target instanceof BuiltinType)
                    ? value
                    : null;
        }
        return source == target ? value : ConstantEvaluator.convert(value, source, target);
    }
}
