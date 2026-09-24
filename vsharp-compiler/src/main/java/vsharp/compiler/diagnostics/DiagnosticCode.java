package vsharp.compiler.diagnostics;

import java.util.Locale;

/// The closed set of diagnostics the V# compiler can report.
///
/// Numbers deliberately mirror the Roslyn `CS` code for the same condition wherever an
/// equivalent condition exists, so that a developer moving from C# recognises the failure
/// immediately: `VS1002` is a missing semicolon just as `CS1002` is.
///
/// Roslyn's own numbering now reaches into the `9xxx` range, so codes without a C#
/// counterpart — those describing V#-specific restrictions such as the absence of the
/// object model — start at `20000`, comfortably beyond any allocated `CS` number, and
/// driver/internal failures start at `29900`. A handful of raw-string codes mirror
/// Roslyn's numbers only approximately; they are marked individually.
///
/// Codes are permanent API: once assigned, a number is never reused for a different
/// condition, because build scripts and CI filters match on them.
public enum DiagnosticCode {

    // ---- Lexical (1000-1099) -------------------------------------------------------

    /// `;` expected.
    SEMICOLON_EXPECTED(1002, Severity.ERROR, "; expected"),

    /// A required token is missing.
    TOKEN_EXPECTED(1003, Severity.ERROR, "%s expected"),

    /// String or character literal is not closed before the end of the line.
    NEWLINE_IN_LITERAL(1010, Severity.ERROR, "Newline in constant"),

    /// Empty character literal `''`.
    EMPTY_CHARACTER_LITERAL(1011, Severity.ERROR, "Empty character literal"),

    /// Character literal holding more than one character.
    TOO_MANY_CHARACTERS(1012, Severity.ERROR, "Too many characters in character literal"),

    /// Numeric literal does not fit its type.
    NUMERIC_LITERAL_OVERFLOW(1021, Severity.ERROR,
            "Integral constant is too large to fit in %s"),

    /// Unrecognised escape sequence in a string or character literal.
    UNRECOGNIZED_ESCAPE(1009, Severity.ERROR, "Unrecognized escape sequence"),

    /// Block comment reached end of file.
    UNTERMINATED_COMMENT(1035, Severity.ERROR, "End-of-comment expected"),

    /// A character that cannot begin any token.
    UNEXPECTED_CHARACTER(1056, Severity.ERROR, "Unexpected character '%s'"),

    /// A string literal reached the end of the file without closing.
    UNTERMINATED_STRING(1039, Severity.ERROR, "Unterminated string literal"),

    /// Digit expected after a numeric prefix or separator.
    INVALID_NUMERIC_LITERAL(1013, Severity.ERROR, "Invalid number: %s"),

    /// A numeric literal carries a suffix combination that does not exist.
    INVALID_NUMERIC_SUFFIX(1085, Severity.ERROR, "Invalid numeric literal suffix '%s'"),

    /// An identifier used a Unicode escape that does not denote an identifier character.
    INVALID_IDENTIFIER_ESCAPE(1057, Severity.ERROR,
            "Unicode escape '%s' does not denote an identifier character"),

    // ---- Raw and interpolated strings (8990-8999) ----------------------------------
    // These mirror Roslyn's numbering only approximately; the conditions are exact.

    /// A raw string literal was never closed by a run of at least as many quotes.
    UNTERMINATED_RAW_STRING(8997, Severity.ERROR, "Unterminated raw string literal"),

    /// A closing delimiter has more quotes than the opening one.
    RAW_STRING_EXTRA_QUOTES(8996, Severity.ERROR,
            "Too many closing quotes for raw string literal"),

    /// A content line is indented less than the closing delimiter line.
    RAW_STRING_INDENTATION(8998, Severity.ERROR,
            "Line does not start with the same whitespace as the closing line "
                    + "of the raw string literal"),

    /// The opening or closing delimiter of a multi-line raw string shares its line.
    RAW_STRING_DELIMITER_LINE(8999, Severity.ERROR,
            "%s delimiter of a multi-line raw string literal must be on its own line"),

    /// A hole in an interpolated string is empty or unclosed.
    MALFORMED_INTERPOLATION(8990, Severity.ERROR, "Malformed interpolated string: %s"),

    /// A `{` or `}` inside an interpolated string was neither doubled nor part of a hole.
    UNESCAPED_INTERPOLATION_BRACE(8991, Severity.ERROR,
            "A '%s' character must be escaped by doubling it in an interpolated string"),

    // ---- Syntactic (1500-1599) -----------------------------------------------------

    /// An expression was required but something else appeared.
    EXPRESSION_EXPECTED(1525, Severity.ERROR, "Invalid expression term '%s'"),

    /// A type name was required.
    TYPE_EXPECTED(1031, Severity.ERROR, "Type expected"),

    /// An identifier was required.
    IDENTIFIER_EXPECTED(1001, Severity.ERROR, "Identifier expected"),

    /// A declaration or statement was required at this position.
    DECLARATION_EXPECTED(1519, Severity.ERROR,
            "Invalid token '%s' in a member declaration"),

    /// A `#` line names no preprocessing directive known to C#.
    INVALID_PREPROCESSOR_DIRECTIVE(1024, Severity.ERROR,
            "Preprocessor directive expected"),

    /// End of input was reached with an open `#if` group.
    ENDIF_EXPECTED(1027, Severity.ERROR, "#endif directive expected"),

    /// `#elif`, `#else` or `#endif` has no matching conditional group.
    UNEXPECTED_PREPROCESSOR_DIRECTIVE(1028, Severity.ERROR,
            "Unexpected preprocessor directive"),

    /// An active `#error` directive.
    PREPROCESSOR_ERROR(1029, Severity.ERROR, "#error: %s"),

    /// An active `#warning` directive.
    PREPROCESSOR_WARNING(1030, Severity.WARNING, "#warning: %s"),

    /// A malformed `#if` or `#elif` Boolean expression.
    INVALID_PREPROCESSOR_EXPRESSION(1517, Severity.ERROR,
            "Invalid preprocessor expression"),

    /// Unexpected token after a syntactically complete construct.
    UNEXPECTED_TOKEN(1022, Severity.ERROR, "Type or namespace definition, or end-of-file expected"),

    /// A statement is not valid in this position.
    STATEMENT_EXPECTED(1513, Severity.ERROR, "Statement expected"),

    // ---- Semantic declarations (0100-0899) ----------------------------------------

    /// A binary operator has no predefined meaning for the operand types.
    BINARY_OPERATOR_NOT_APPLICABLE(19, Severity.ERROR,
            "Operator '%s' cannot be applied to operands of type '%s' and '%s'"),

    /// Division by constant zero.
    DIVISION_BY_ZERO(20, Severity.ERROR, "Division by constant zero"),

    /// Cannot apply indexing with [] to an expression of type 'T'.
    INDEXING_NON_ARRAY(21, Severity.ERROR,
            "Cannot apply indexing with [] to an expression of type '%s'"),

    /// Wrong number of indices inside []; expected 'N'.
    WRONG_INDEX_COUNT(22, Severity.ERROR,
            "Wrong number of indices inside []; expected %d"),

    /// A unary operator has no predefined meaning for the operand type.
    UNARY_OPERATOR_NOT_APPLICABLE(23, Severity.ERROR,
            "Operator '%s' cannot be applied to operand of type '%s'"),

    /// The operation overflows at compile time in checked mode.
    COMPILE_TIME_OVERFLOW(220, Severity.ERROR,
            "The operation overflows at compile time in checked mode"),

    /// A decimal literal is outside the range of `decimal` after its scale is applied.
    DECIMAL_LITERAL_OVERFLOW(594, Severity.ERROR,
            "Floating-point constant is outside the range of type 'decimal'"),

    /// Assignment or condition typing requires an implicit conversion which does not exist.
    CANNOT_IMPLICITLY_CONVERT(29, Severity.ERROR,
            "Cannot implicitly convert type '%s' to '%s'"),

    /// Cast syntax requires an explicit or implicit conversion which does not exist.
    CANNOT_EXPLICITLY_CONVERT(30, Severity.ERROR,
            "Cannot convert type '%s' to '%s'"),

    /// A constant-expression conversion exists in principle but its value is out of range.
    CONSTANT_VALUE_CANNOT_CONVERT(31, Severity.ERROR,
            "Constant value '%s' cannot be converted to a '%s'"),

    /// Two parameters in one callable have the same name.
    DUPLICATE_PARAMETER(100, Severity.ERROR,
            "The parameter name '%s' is a duplicate"),

    /// Two namespace members declare the same type name and arity.
    DUPLICATE_NAMESPACE_MEMBER(101, Severity.ERROR,
            "The namespace '%s' already contains a definition for '%s'"),

    /// A type or static container contains two incompatible members with one name.
    DUPLICATE_MEMBER(102, Severity.ERROR,
            "The type '%s' already contains a definition for '%s'"),

    /// A value name cannot be found in the active lexical scopes.
    NAME_NOT_FOUND(103, Severity.ERROR,
            "The name '%s' does not exist in the current context"),

    /// A simple name is provided by more than one imported namespace or Java package.
    ///
    /// Enclosing declarations are searched before imports, so this reports only the case C#
    /// reports: the name is absent from every enclosing scope and two `using` directives
    /// answer it equally well. Picking either one would make the meaning of the program depend
    /// on the order of its imports.
    AMBIGUOUS_REFERENCE(104, Severity.ERROR,
            "'%s' is an ambiguous reference between '%s' and '%s'"),

    /// A constant's value depends, directly or through other constants, on itself.
    ///
    /// `const int A = B; const int B = A;` has no value to fold: each side waits for the
    /// other. C# reports CS0110 for exactly this, and V# folds constants on demand, so the
    /// cycle is what the resolver sees when it re-enters a constant it is already folding
    ///. Reporting it is the alternative to recursing until the stack ends.
    CIRCULAR_CONSTANT_DEFINITION(110, Severity.ERROR,
            "The evaluation of the constant value for '%s' involves a circular definition"),

    /// Two callable declarations have the same overload signature.
    DUPLICATE_FUNCTION(111, Severity.ERROR,
            "Type '%s' already defines a member called '%s' with the same parameter types"),

    /// A type or expression does not contain a member with the given name.
    MEMBER_NOT_FOUND(117, Severity.ERROR,
            "'%s' does not contain a definition for '%s'"),

    /// A local name is declared twice in one lexical declaration space.
    DUPLICATE_LOCAL(128, Severity.ERROR,
            "A local variable or function named '%s' is already defined in this scope"),

    /// The left operand of an assignment or mutation does not denote writable storage.
    ASSIGNMENT_TARGET_REQUIRED(131, Severity.ERROR,
            "The left-hand side of an assignment must be a variable, property or indexer"),

    /// A `const` initializer binds to a well-formed expression that is not a *constant*
    /// expression: a call, a read of an ordinary field, a concatenation with a non-string
    /// operand. C# reports CS0133 for this, and the distinction matters because a constant
    /// declaration has no run-time entity to fall back on - unlike an ordinary field, whose
    /// initializer may be any expression at all. Saying "this build cannot fold it" would
    /// blame the compiler for what the language itself refuses.
    CONSTANT_INITIALIZER_REQUIRED(133, Severity.ERROR,
            "The expression being assigned to '%s' must be constant"),

    /// The two result branches of a conditional expression have no common type.
    CONDITIONAL_TYPE_UNDETERMINED(173, Severity.ERROR,
            "Type of conditional expression cannot be determined because there is no implicit conversion between '%s' and '%s'"),

    /// `sizeof(T)` outside an unsafe context is limited to the predefined fixed-size types.
    SIZEOF_REQUIRES_UNSAFE_CONTEXT(233, Severity.ERROR,
            "'%s' does not have a predefined size, therefore sizeof can only be used in an unsafe context"),

    /// A source type name cannot be resolved.
    TYPE_OR_NAMESPACE_NOT_FOUND(246, Severity.ERROR,
            "The type or namespace name '%s' could not be found"),

    /// A qualified name's namespace exists but does not contain the segment that follows it.
    /// Distinct from [#TYPE_OR_NAMESPACE_NOT_FOUND]: the namespace resolved, so the author is
    /// told which segment failed and where it was looked for, rather than that the whole name
    /// is unknown.
    TYPE_NOT_IN_NAMESPACE(234, Severity.ERROR,
            "The type or namespace name '%s' does not exist in the namespace '%s'"),

    /// A generic declared type was used with the wrong number of type arguments.
    WRONG_TYPE_ARGUMENT_COUNT(305, Severity.ERROR,
            "Using the generic type '%s' requires %d type argument(s)"),

    /// A non-method member was used like a method: `s.Length()` where `Length` is a property.
    NON_INVOCABLE_MEMBER(1955, Severity.ERROR,
            "Non-invocable member '%s' cannot be used like a method"),

    /// A call's type argument was supplied by neither an argument nor a target type.
    CANNOT_INFER_TYPE_ARGUMENTS(411, Severity.ERROR,
            "The type arguments for function '%s' cannot be inferred from the usage. "
                    + "Try specifying the type arguments explicitly"),

    /// An unbound generic name (`G<>`) appeared outside the operand of `typeof`.
    UNBOUND_GENERIC_NAME(7003, Severity.ERROR,
            "Unexpected use of an unbound generic name"),

    /// The operand of `nameof` is an expression shape rather than a named entity.
    EXPRESSION_HAS_NO_NAME(8081, Severity.ERROR,
            "Expression does not have a name"),

    /// A `static` local function attempted to close over an enclosing local or parameter.
    STATIC_LOCAL_FUNCTION_CANNOT_CAPTURE_VARIABLE(8421, Severity.ERROR,
            "A static local function cannot contain a reference to '%s'"),

    /// A `static` local function attempted to use its enclosing instance receiver.
    STATIC_LOCAL_FUNCTION_CANNOT_CAPTURE_RECEIVER(8422, Severity.ERROR,
            "A static local function cannot contain a reference to 'this' or 'base'"),

    /// A generic declaration repeats one of its type parameter names.
    DUPLICATE_TYPE_PARAMETER(692, Severity.ERROR,
            "Duplicate type parameter '%s'"),

    /// `var` appeared outside a local variable declaration.
    INVALID_VAR_CONTEXT(825, Severity.ERROR,
            "The contextual keyword 'var' may only appear within a local variable declaration"),

    /// A method group or similarly target-typed expression cannot infer a `var` local yet.
    CANNOT_INFER_LOCAL_TYPE(815, Severity.ERROR,
            "An implicitly typed local variable cannot be initialized with '%s'"),

    /// A `var` declaration has no initializer from which to infer its type.
    INFERRED_LOCAL_REQUIRES_INITIALIZER(818, Severity.ERROR,
            "Implicitly-typed variables must be initialized"),

    /// A local is referenced before its declaration point.
    VARIABLE_BEFORE_DECLARATION(841, Severity.ERROR,
            "Cannot use local variable '%s' before it is declared"),

    /// A `foreach` collection is neither an array, a `string`, nor a Java `Iterable`.
    FOREACH_NOT_ENUMERABLE(1579, Severity.ERROR,
            "foreach statement cannot operate on variables of type '%s' because it does not "
                    + "contain a public instance definition for 'GetEnumerator'"),

    /// `void` appeared where a value-bearing type is required.
    VOID_NOT_ALLOWED(1547, Severity.ERROR,
            "Keyword 'void' cannot be used in this context"),

    /// A `new` expression supplied an argument count no constructor of the type declares.
    /// C# reports this instead of [#NO_OVERLOAD_TAKES_N_ARGUMENTS] because a constructor is
    /// named by its type, not by a member name the author wrote.
    NO_CONSTRUCTOR_TAKES_N_ARGUMENTS(1729, Severity.ERROR,
            "'%s' does not contain a constructor that takes %d arguments"),

    /// Two overloads that C# tells apart erase to one JVM method, because a V# enum is a
    /// named `int`. Emitting both produced a class file the JVM refused to load, so this is
    /// reported at the declaration instead.
    ERASED_SIGNATURE_CLASH(20011, Severity.ERROR,
            "'%s' declares two overloads of '%s' that erase to the same JVM signature '%s'; "
            + "a V# enum is a named int, so give them different names"),

    /// A type was named by its fully qualified name in source, rather than imported.
    ///
    /// A qualified spelling repeats a package path at every use, hides which dependencies a
    /// file actually has, and cannot be redirected without editing every line that mentions
    /// it. `using` states the dependency once, at the top, where a reader looks for it; an
    /// alias handles the collisions that are the only real argument for qualifying.
    QUALIFIED_TYPE_NAME(20012, Severity.ERROR,
            "Name '%s' by its simple name and import it with 'using %s;', or alias it with "
            + "'using %s = %s;'. A fully qualified type name is not allowed in source"),

    /// An invocation is ambiguous between multiple candidate overloads.
    AMBIGUOUS_CALL(121, Severity.ERROR,
            "The call is ambiguous between the following functions: '%s' and '%s'"),

    /// No function overload matches the number of supplied arguments.
    NO_OVERLOAD_TAKES_N_ARGUMENTS(1501, Severity.ERROR,
            "No overload for function '%s' takes %d arguments"),

    /// An argument type cannot be implicitly converted to the parameter type.
    CANNOT_CONVERT_ARGUMENT(1503, Severity.ERROR,
            "Argument %d: cannot convert from '%s' to '%s'"),

    /// A ref or out argument expression is not a variable.
    REF_OUT_ARGUMENT_MUST_BE_VARIABLE(1510, Severity.ERROR,
            "A ref or out argument must be an assignable variable"),

    /// An argument was passed with a ref/out/in modifier when the parameter does not require it.
    ARGUMENT_SHOULD_NOT_BE_PASSED_WITH_REF_OUT(1615, Severity.ERROR,
            "Argument %d should not be passed with the '%s' keyword"),

    /// An argument was passed without a required ref/out/in modifier.
    ARGUMENT_MUST_BE_PASSED_WITH_REF_OUT(1620, Severity.ERROR,
            "Argument %d must be passed with the '%s' keyword"),

    /// An optional parameter default is not a C# compile-time constant/default value.
    DEFAULT_PARAMETER_VALUE_MUST_BE_CONSTANT(1736, Severity.ERROR,
            "Default parameter value for '%s' must be a compile-time constant"),

    /// A required fixed parameter follows an optional one.
    OPTIONAL_PARAMETER_ORDER(1737, Severity.ERROR,
            "Optional parameters must appear after all required parameters"),

    /// A ref or out parameter cannot be supplied implicitly from a declaration default.
    REF_OUT_PARAMETER_CANNOT_HAVE_DEFAULT(1741, Severity.ERROR,
            "A ref or out parameter cannot have a default value"),

    // ---- Extension methods (1100-1113, 1929) ---------------------------------------

    /// `this` appeared on a parameter other than the first one.
    EXTENSION_THIS_NOT_FIRST_PARAMETER(1100, Severity.ERROR,
            "Method '%s' has a parameter modifier 'this' which is not on the first parameter"),

    /// `ref` was combined with `this` on an extension receiver.
    EXTENSION_REF_WITH_THIS(1101, Severity.ERROR,
            "The parameter modifier 'ref' cannot be used with 'this'"),

    /// `out` was combined with `this` on an extension receiver.
    EXTENSION_OUT_WITH_THIS(1102, Severity.ERROR,
            "The parameter modifier 'out' cannot be used with 'this'"),

    /// `params` was combined with `this` on an extension receiver.
    EXTENSION_PARAMS_WITH_THIS(1104, Severity.ERROR,
            "A parameter array cannot be used with 'this' modifier on an extension method"),

    /// An extension method was declared without `static`.
    EXTENSION_METHOD_MUST_BE_STATIC(1105, Severity.ERROR,
            "Extension method '%s' must be static"),

    /// An extension method was declared outside a non-generic `static class`.
    EXTENSION_METHOD_MUST_BE_IN_STATIC_CLASS(1106, Severity.ERROR,
            "Extension method '%s' must be defined in a non-generic static class"),

    /// An extension method was declared inside a nested `static class`.
    ///
    /// C# reserves extension declarations for top level static classes (CS1109). V# keeps the
    /// restriction even though a nested container is only a naming holder here, because a
    /// program that compiles under one compiler and not the other is the larger surprise.
    EXTENSION_METHOD_MUST_BE_TOP_LEVEL(1109, Severity.ERROR,
            "Extension method '%s' must be defined in a top level static class"),

    /// The extension method a call selected cannot take that receiver.
    ///
    /// The receiver is argument one of the rewritten static call, but the author never wrote
    /// it as an argument, so the failure is reported against the expression before the dot and
    /// names the receiver rather than an argument number, as CS1929 does.
    EXTENSION_RECEIVER_MISMATCH(1929, Severity.ERROR,
            "'%s' is not a valid receiver for extension method '%s', which requires a receiver "
                    + "of type '%s'"),

    /// A pattern designation appeared under a `not` or `or` combinator, where it could
    /// not be definitely assigned on every path that reaches the match.
    DESIGNATION_UNDER_NOT_OR_OR(8780, Severity.ERROR,
            "A variable may not be declared within a 'not' or 'or' pattern"),

    /// A constant was required where a computed value appeared.
    CONSTANT_VALUE_EXPECTED(150, Severity.ERROR, "A constant value is expected"),

    /// A list pattern was applied to a value with no length-and-index surface.
    LIST_PATTERN_UNSUPPORTED_TARGET(8985, Severity.ERROR,
            "List patterns may not be used for a value of type '%s'"),

    /// A slice pattern appeared outside a list pattern, or twice inside one.
    SLICE_PATTERN_MISPLACED(8980, Severity.ERROR,
            "Slice patterns may only be used once and directly inside a list pattern"),

    /// A positional pattern was applied to a value that offers no positional components.
    /// V# deconstructs tuples only: every other C# source of positional components is a
    /// `Deconstruct` instance method, which the omitted object model does not provide.
    POSITIONAL_PATTERN_REQUIRES_TUPLE(8129, Severity.ERROR,
            "No deconstruction is available for type '%s', so it cannot be matched by a "
                    + "positional pattern"),

    /// A constant pattern whose constant can never equal a value of the tested type, so the
    /// arm could only ever be dead code. C# reports the same shape as CS8121.
    PATTERN_CANNOT_HANDLE_TYPE(8121, Severity.ERROR,
            "An expression of type '%s' cannot be handled by a pattern of type '%s'"),

    /// A relational pattern (`> 5`) applied to a type C# defines no relational operators for.
    RELATIONAL_PATTERN_UNSUPPORTED_TYPE(8781, Severity.ERROR,
            "Relational patterns may not be used for a value of type '%s'"),

    /// A positional pattern listed a different number of subpatterns than the tested tuple
    /// has elements.
    POSITIONAL_PATTERN_ARITY(8502, Severity.ERROR,
            "Matching a value of type '%s' requires '%s' subpatterns, but '%s' were given"),

    // ---- Flow analysis (0126-0269) -------------------------------------------------

    /// A `return;` left a function whose return type demands a value.
    RETURN_VALUE_REQUIRED(126, Severity.ERROR,
            "An object of a type convertible to '%s' is required"),

    /// A `return expression;` appeared inside a `void` function.
    RETURN_VALUE_IN_VOID_FUNCTION(127, Severity.ERROR,
            "Since '%s' returns void, a return keyword must not be followed by an "
                    + "object expression"),

    /// `break` or `continue` appeared with no enclosing loop or switch.
    NO_ENCLOSING_LOOP(139, Severity.ERROR,
            "No enclosing loop out of which to break or continue"),

    /// `goto` named a label that no enclosing statement declares.
    LABEL_NOT_FOUND(159, Severity.ERROR,
            "No such label '%s' within the scope of the goto statement"),

    /// A value-returning function has a reachable end point.
    NOT_ALL_PATHS_RETURN(161, Severity.ERROR,
            "'%s': not all code paths return a value"),

    /// A throw operand or catch type must be a JVM Throwable-compatible exception.
    TYPE_CAUGHT_OR_THROWN_MUST_DERIVE_FROM_EXCEPTION(155, Severity.ERROR,
            "The type caught or thrown must be derived from System.Exception"),

    /// A bare `throw;` has no exception to rethrow outside a `catch` clause body.
    RETHROW_OUTSIDE_CATCH(156, Severity.ERROR,
            "A throw statement with no arguments is not allowed outside of a catch clause"),

    /// A statement can never execute.
    UNREACHABLE_CODE(162, Severity.WARNING, "Unreachable code detected"),

    /// The end of a switch section is reachable, which C# forbids.
    SWITCH_FALL_THROUGH(163, Severity.ERROR,
            "Control cannot fall through from one case label to another"),

    /// A declared label is never the target of a `goto`.
    LABEL_NEVER_REFERENCED(164, Severity.WARNING, "This label has not been referenced"),

    /// A local variable was read before any assignment reached it.
    UNASSIGNED_LOCAL_USE(165, Severity.ERROR, "Use of unassigned local variable '%s'"),

    /// An `out` parameter is not definitely assigned where control leaves the function.
    OUT_PARAMETER_NOT_ASSIGNED(177, Severity.ERROR,
            "The out parameter '%s' must be assigned to before control leaves the "
                    + "current function"),

    /// An `out` parameter was read before the function assigned it.
    UNASSIGNED_OUT_PARAMETER_USE(269, Severity.ERROR,
            "Use of unassigned out parameter '%s'"),

    /// A lock statement requires an expression of a reference type.
    NON_REFERENCE_TYPE_IN_LOCK(185, Severity.ERROR,
            "'%s' is not a reference type as required by the lock statement"),

    /// Type used in a using statement must be implicitly convertible to 'java.lang.AutoCloseable'.
    TYPE_USED_IN_USING_STATEMENT_MUST_BE_IMPLICITLY_CONVERTIBLE_TO_AUTOCLOSEABLE(1674, Severity.ERROR,
            "type used in a using statement must be implicitly convertible to 'java.lang.AutoCloseable'"),

    // ---- V#-specific restrictions (20000-20999) ------------------------------------

    /// A C# construct that depends on the object model V# omits.
    OBJECT_MODEL_UNSUPPORTED(20001, Severity.ERROR,
            "'%s' requires the C# object model, which V# does not implement"),

    /// A construct is recognised but not yet implemented by this compiler build.
    NOT_YET_IMPLEMENTED(20002, Severity.ERROR, "%s is not supported by this V# build"),

    /// A C# unsafe/stack-only feature which cannot be represented by verifiable JVM code.
    UNVERIFIABLE_FEATURE_UNSUPPORTED(20003, Severity.ERROR,
            "'%s' has no verifiable JVM equivalent and is outside V#"),

    /// A type pattern names something the JVM cannot be asked about at run time.
    TYPE_PATTERN_UNSUPPORTED_TARGET(20004, Severity.ERROR,
            "A pattern cannot test '%s': %s"),

    /// A C# construct whose observable behaviour depends on a CLR runtime rule the JVM does
    /// not provide. Distinct from [#NOT_YET_IMPLEMENTED]: this is a permanent exclusion, and
    /// the second argument states the exact dependency.
    RUNTIME_MODEL_UNSUPPORTED(20005, Severity.ERROR,
            "'%s' cannot be expressed on the JVM: %s"),

    /// A borrowed JDK member written in the JVM's own camelCase. V# member spelling is
    /// PascalCase everywhere, including across the Java boundary, and the compiler performs
    /// the translation, so the camelCase spelling is an error rather than a second accepted
    /// name.
    JAVA_MEMBER_CASING(20006, Severity.ERROR,
            "Java member '%s' must be written as '%s': V# spells members in PascalCase"),

    /// An opening brace which violates the mandatory global Allman placement rule.
    OPENING_BRACE_PLACEMENT(20007, Severity.ERROR,
            "Opening brace must appear on its own new line (Allman style)"),

    /// A closing brace must begin its line so a scope boundary cannot be hidden after code.
    CLOSING_BRACE_PLACEMENT(20008, Severity.ERROR,
            "Closing brace must begin its line"),

    /// Every brace uses four literal spaces for each lexically enclosing brace pair.
    BRACE_INDENTATION(20009, Severity.ERROR,
            "Brace must be indented with exactly %d spaces (four per enclosing scope)"),

    /// A package the installed JDK publishes, from a module this compiler process did not
    /// resolve. The name is real, so CS0246 would send the user looking for a typo that
    /// does not exist; the fix belongs to the launch, and the message says so.
    SYSTEM_MODULE_NOT_RESOLVED(20010, Severity.ERROR,
            "Package '%s' belongs to JDK module '%s', which this compiler process did not "
                    + "resolve; launch it with --add-modules naming that module"),

    /// An `async` callable whose return type is not `Task` or `Task<T>`. V# starts the
    /// body on a task and hands back the task, so the declared type is what the caller
    /// receives and there is nothing else it can be.
    ASYNC_RETURN_TYPE(20014, Severity.ERROR,
            "An async callable must return Task, Task<T> or void, not '%s'"),

    /// `await` applied to something that is not a task.
    AWAIT_REQUIRES_TASK(20015, Severity.ERROR,
            "'await' requires Task or Task<T>, but the operand is '%s'"),

    /// `async` on a lambda or a local function. Both are emitted through machinery that
    /// passes captured values as shared cells, and an async callable is split into a body plus
    /// a wrapper that must forward exactly those cells - a second capture convention this
    /// build does not implement. A declared method has no capture convention and is the
    /// supported form.
    ASYNC_LAMBDA_UNSUPPORTED(20016, Severity.ERROR,
            "'async' is supported on a declared method, not on a lambda or local function"),

    /// A `ref`, `out` or `in` parameter on an `async` callable. C# refuses these outright
    /// (CS1988) and V# must too, for a reason its own lowering makes sharper: the body runs on
    /// another thread, so the caller would read the cell before the body ever wrote it.
    ASYNC_BY_REFERENCE_PARAMETER(20017, Severity.ERROR,
            "An async callable cannot have a '%s' parameter: its body runs on another thread, so "
                    + "the caller would read the cell before the body wrote it; return a tuple "
                    + "such as Task<(bool, int)> instead"),

    /// A task joined with the JDK's own blocking read instead of `await`.
    ///
    /// `Future.Get()` waits without limit, and the timed overload waits for whatever the caller
    /// chose, so either one opts the call site out of the execution limit that makes a blocking
    /// `await` safe to compile into every program. It is an error by default. A build that has
    /// a genuine reason may pass `--allow-unbounded-joins`, which downgrades every occurrence to
    /// a warning rather than silencing it: the point is that the decision is visible in the
    /// build command and in the output, not that it is forbidden.
    UNBOUNDED_TASK_JOIN(20018, Severity.ERROR,
            "'%s' on a task waits outside the %d second execution limit; use 'await' instead, or "
                    + "pass --allow-unbounded-joins to accept unbounded waits in this build"),

    /// A type argument does not convert to the type its `where` clause names.
    ///
    /// C#'s CS0311. Only a *reference* argument is checked: V# models no boxed-interface
    /// surface for a keyword value type, so `int` against `IComparable` cannot be decided here
    /// and is admitted rather than wrongly refused.
    CONSTRAINT_REQUIRES_CONVERSION(311, Severity.ERROR,
            "The type '%s' cannot be used as type parameter '%s' in the generic type or method "
                    + "'%s'. There is no implicit reference conversion from '%s' to '%s'"),

    /// A `where` clause names a type that cannot constrain a type parameter.
    ///
    /// C# admits an interface, a non-sealed class or another type parameter. A `struct` or
    /// `record struct` is none of those - it is sealed and has no derived types, so the clause
    /// could only ever be satisfied by itself - and C# reports CS0701 for exactly this.
    INVALID_CONSTRAINT_TYPE(701, Severity.ERROR,
            "'%s' is not a valid constraint. A type used as a constraint must be an interface, "
                    + "a non-sealed class or a type parameter"),

    /// A type argument is not the non-nullable value type its `where` clause demands.
    CONSTRAINT_REQUIRES_VALUE_TYPE(453, Severity.ERROR,
            "The type '%s' must be a non-nullable value type in order to use it as parameter "
                    + "'%s' in the generic type or method '%s'"),

    /// A type argument is not the reference type its `where` clause demands.
    CONSTRAINT_REQUIRES_REFERENCE_TYPE(452, Severity.ERROR,
            "The type '%s' must be a reference type in order to use it as parameter '%s' in the "
                    + "generic type or method '%s'"),

    /// A primitive array was passed where a generic declaration's erased array stands.
    ///
    /// C# reifies a generic instantiation and V# erases it, so this is a genuine difference
    /// rather than an unfinished feature: `T[]` is `object[]` in the descriptor, any reference
    /// array passes through it by JVM array covariance, and `int[]` cannot because the JVM has
    /// no widening from a primitive array to an object array. Boxing into a copy would compile
    /// and then lose the caller's aliasing, so the call is refused by name instead.
    GENERIC_PRIMITIVE_ARRAY_CARRIER(20019, Severity.ERROR,
            "'%s' cannot be passed for '%s': a type parameter array is erased to '%s' at run "
                    + "time, and a primitive array is not one. Use an array of a reference "
                    + "type, or declare the parameter with the element type spelled out"),

    /// An array whose element type is a type parameter was created.
    ///
    /// The JVM allocates the erasure - an `object[]` - which is the wrong array: the caller
    /// narrows the result back to its own element type and that cast fails on a value the
    /// program built correctly. Refusing the creation keeps the erased model sound; passing an
    /// array in and reading or writing its elements stays fully supported.
    GENERIC_ARRAY_CREATION(20020, Severity.ERROR,
            "An array with element type '%s' cannot be created: a type parameter has no run-time "
                    + "element type, so the array would be created as 'object[]'"),

    // ---- Driver and internal (29900-29999) -----------------------------------------

    /// The command line was rejected.
    INVALID_COMMAND_LINE(29900, Severity.ERROR, "%s"),

    /// A source file could not be read.
    SOURCE_UNREADABLE(29901, Severity.ERROR, "Cannot read source file: %s"),

    /// An output artifact could not be written.
    OUTPUT_UNWRITABLE(29902, Severity.ERROR, "Cannot write output: %s"),

    /// The compiler itself failed; distinct from any user error.
    INTERNAL_ERROR(29999, Severity.ERROR, "Internal compiler error: %s");

    private final int number;

    private final Severity defaultSeverity;

    private final String template;

    private final String id;

    DiagnosticCode(int number, Severity defaultSeverity, String template) {
        this.number = number;
        this.defaultSeverity = defaultSeverity;
        this.template = template;
        this.id = "VS%04d".formatted(number);
    }

    /// Numeric part of the code.
    public int number() {
        return number;
    }

    /// Stable textual identifier such as `VS1002`.
    public String id() {
        return id;
    }

    /// Severity used unless the caller overrides it.
    public Severity defaultSeverity() {
        return defaultSeverity;
    }

    /// Renders this code's message with the given arguments.
    ///
    /// Formatting is locale-independent: diagnostics must be byte-identical regardless of
    /// the machine's locale so that CI comparisons stay meaningful.
    public String format(Object... arguments) {
        // String.formatted would apply the default locale; ROOT keeps output stable.
        return arguments.length == 0 ? template : String.format(Locale.ROOT, template, arguments);
    }

    @Override
    public String toString() {
        // Upper case, exactly as Roslyn prints `CS1002`; tooling matches on this form.
        return id;
    }
}
