# V# syntax and recovery notes

This document fixes the parser contract for the non-object-oriented C# 13 layer. The
feature classification remains authoritative in [FEATURE-MATRIX.md](FEATURE-MATRIX.md);
this file describes how the implemented parser represents that classification.

The notation is compact EBNF: `[]` is optional, `{}` is repetition, `|` is choice, and
quoted text is a token spelling. Contextual words are emitted by the lexer as identifiers
and recognised only in their grammar positions.

## Pipeline boundary

Parsing is intentionally syntax-only. It decides grouping, precedence and the shape of
declarations, but not whether a name denotes a type, whether an overload exists, whether a
pattern is exhaustive, or whether a variable is definitely assigned. Those are binder and
flow-analysis decisions. Every syntax node is immutable and owns an absolute half-open
source span.

Before lexing a compilation unit, `Preprocessor` evaluates `#if` groups. It replaces every
directive line and inactive source character with spaces while retaining line terminators.
The lexer therefore cannot diagnose inactive text, while offsets, lines and diagnostic
excerpts still refer to the original `SourceFile`. The public parser accepts an initial set
of command-line symbols.

After a file parses without syntax errors, the compilation facade applies V#'s mandatory
layout gate to the same preprocessor-masked token stream. Every syntax opening brace must be
alone on a new line; opening and closing braces are indented with four literal spaces per
enclosing brace pair; and a closing brace begins its line. This is a compiler error
(`VS20007`-`VS20009`), not a formatter preference. Parser-only APIs remain useful for syntax
tooling, but every full `Compilation` (including CLI and Gradle use) enforces the rule before
declaration binding.

Three constructs write a brace inside an expression or a pattern rather than around a block:
array initializers (`new int[] { 1, 2 }`), property subpatterns (`Order { Quantity: > 100 }`)
and `with` initializers (`o with { Quantity = 5 }`). Allman placement would make these
unwritable in their canonical C# spelling, so such a pair may instead be written entirely on
one line. The parser publishes the offsets of those braces on `CompilationUnit.inlineBraces`
- a brace's grammatical role is a parse fact the token pass cannot recover - and the gate
exempts a pair only when it both appears in that list and opens and closes on one line. Spread
over several lines the pair is a block again and obeys the ordinary rule, so each of the three
constructs has exactly two admissible spellings: fully inline, or fully Allman. Skipping both
members leaves lexical depth unchanged, so blocks nested inside an exempt pair are still
measured against their real enclosing block.

## Compilation units and declarations

```ebnf
compilation-unit = { directive | using-directive | namespace-declaration
                   | type-declaration | statement }, EOF ;

using-directive  = [ "global" ], "using", [ "static" ],
                   [ identifier, "=" ], qualified-name, ";" ;

namespace-declaration
                 = "namespace", qualified-name,
                   ( ";", { namespace-member }
                   | "{", { namespace-member }, "}", [ ";" ] ) ;

type-declaration = { attribute-list }, { modifier },
                   ( "static", "class", static-container-tail
                   | "enum", enum-tail
                   | "struct", struct-tail
                   | "record", "struct", struct-tail ) ;

static-container-tail
                 = identifier, [ type-parameters ], { constraint-clause },
                   "{", { member }, "}", [ ";" ] ;

struct-tail      = identifier, [ type-parameters ], [ parameter-list ],
                   { constraint-clause },
                   ( ";" | "{", { member }, "}", [ ";" ] ) ;

enum-tail        = identifier, [ ":", type ], "{",
                   [ enum-member, { ",", enum-member }, [ "," ] ], "}", [ ";" ] ;

member           = field | method | operator | conversion-operator | type-declaration ;
field            = { attribute-list }, { modifier }, type,
                   variable, { ",", variable }, ";" ;
method           = { attribute-list }, { modifier }, type, identifier,
                   [ type-parameters ], parameter-list, { constraint-clause }, callable-body ;
callable-body    = block | "=>", expression, ";" | ";" ;
parameter-list   = "(", [ parameter, { ",", parameter } ], ")" ;
parameter        = { attribute-list }, { parameter-modifier }, [ type ], identifier,
                   [ "=", expression ] ;
parameter-modifier
                 = "ref" | "out" | "in" | "params" | "scoped" | "this" ;
```

`class` without `static`, interfaces, user delegates, constructors, properties and indexers
are recognised as bounded unsupported declarations. The parser reports `VS20001`, skips
their balanced extent, and resumes with the next declaration. A static class is represented
as `DeclarationSyntax.StaticContainer`; it is a namespace-like carrier, not an instance
type.

The grammar accepts every parameter modifier in every position; which combinations are legal
is a declaration rule, not a syntax one, so the diagnostics stay precise. `this` marks an
extension receiver and is accepted only as the first parameter of a `static` method of a top
level, non-generic `static class`, never together with `ref`, `out` or `params`
(`VS1100`-`VS1109`). A parameter type is optional only inside a lambda parameter list.

## Types

```ebnf
type             = [ "ref", [ "readonly" ] ], type-primary, [ "?" ],
                   { "[", { "," }, "]" } ;
type-primary     = predefined-type | qualified-name | tuple-type ;
qualified-name   = [ "global", "::" ], name-segment, { ".", name-segment } ;
name-segment     = identifier, [ "<", type-argument-list, ">" ] ;
tuple-type       = "(", tuple-element, ",", tuple-element,
                   { ",", tuple-element }, ")" ;
tuple-element    = type, [ identifier ] ;
```

`var`, `dynamic`, `nint` and `nuint` remain identifier tokens but become distinct
parser-only `SyntaxKind` values inside `TypeSyntax.Predefined`. Similarly, `partial`,
`file` and `scoped` retain their distinct contextual meaning in declaration nodes.

## Expressions and precedence

Assignment, conditional and null-coalescing operators associate right-to-left. All other
binary operators associate left-to-right. Higher rows bind more tightly:

| Level | Forms |
| ---: | --- |
| 15 | primary, member/index access, invocation, postfix `++`/`--`/`!`, `switch`, `with` |
| 14 | prefix `+ - ! ~ ++ -- ^ ref`; unsafe `& *` are diagnosed |
| 13 | `* / %` |
| 12 | `+ -` |
| 11 | `<< >> >>>` |
| 9 | `< <= > >= is as` |
| 8 | `== !=` |
| 7 | `&` |
| 6 | `^` |
| 5 | `|` |
| 4 | `&&` |
| 3 | `||` |
| 2 | `..` |
| 1 | `??` |
| — | `?:`, then assignment and lambda |

The lexer deliberately emits consecutive `>` tokens. The expression parser recombines
only physically adjacent tokens into `>>`, `>>>`, `>>=` or `>>>=`; type parsing consumes
the same tokens separately to close nested generic argument lists.

Primary forms include literals, names, tuples, interpolated strings, collection
expressions, arrays and array initialisers, `new type(arguments)`, `default`,
`nameof`, `typeof`, `sizeof`, `checked`/`unchecked`, throw expressions, lambdas and anonymous
methods. Object creation binds only when the named type was discovered from the Java module
path and a public constructor is applicable. Target-typed `new()`, V# user-type construction
and object initialisers remain object-model exclusions. A predefined type keyword is a primary expression only when a
`.` follows it (`int.MaxValue`), which is the one position C# allows it in; anywhere else it
stays the invalid expression term `VS1525` names. A fully qualified module-path Java
type may also be the receiver of a public static field access; instance fields use ordinary
member access on a Java-typed value. Interpolation holes are lexed again over their absolute
source spans and parsed as ordinary expressions.

## Statements

```ebnf
statement        = block | ";" | local-declaration | expression, ";"
                 | if | while | do | for | foreach | switch
                 | break | continue | return | throw | goto | labelled
                 | try | using | lock | checked | yield | local-function ;

block            = "{", { statement }, "}" ;
if               = "if", "(", expression, ")", statement,
                   [ "else", statement ] ;
while            = "while", "(", expression, ")", statement ;
do               = "do", statement, "while", "(", expression, ")", ";" ;
for              = "for", "(", [ declaration | expression-list ], ";",
                   [ expression ], ";", [ expression-list ], ")", statement ;
foreach           = "foreach", "(", type, designation, "in", expression, ")", statement ;
try              = "try", block, ( catch, { catch }, [ "finally", block ]
                   | "finally", block ) ;
using            = "using", "(", ( declaration | expression ), ")", statement
                 | "using", local-declaration-tail ;
```

Top-level local functions use the same method node as container methods, wrapped in
`StatementSyntax.LocalFunction`. `yield return` and `yield break` remain explicit nodes so
semantic binding can report the object-model exclusion at their exact source spans.
Deconstruction designations are pattern nodes shared by local and `foreach` declarations.

## Patterns

Pattern precedence is `not`, then `and`, then `or`. The parser represents constant,
relational, type/declaration, `var`, discard, positional/property recursive, list and slice
patterns. A switch-expression arm owns its optional `when` guard and result expression;
switch-statement labels and their statements are grouped into sections.

## Recovery contract

Required punctuation is inserted as a zero-width `MISSING_TOKEN` at the current source
position. Missing semicolons use stable `VS1002`; missing identifiers and types use
`VS1001` and `VS1031`; other required tokens use `VS1003`. Invalid primary expressions
consume one token unless already at EOF. Every declaration, statement, delimited list and
switch-section loop verifies forward progress.

Unsupported object-model syntax reports `VS20001`. Unsafe constructs without a verifiable
JVM representation report `VS20003`. Neither category is silently accepted, and both
recover to a balanced delimiter or statement boundary so later diagnostics remain useful.

The deterministic generated-input parser test fixes a seed and parses 500 malformed token
mixtures on every build. Together with targeted missing-delimiter and multi-error cases,
this guards the termination and diagnostic-order contracts.
