# V# → JVM bytecode mapping

This document describes what the backend emits **today**, not what it intends to emit.
Every mapping below is exercised by a test in `vsharp.tests.backend.BackendTests` unless the
row says otherwise, and every form that is *not* implemented is listed in
[Forms that refuse to emit](#forms-that-refuse-to-emit) rather than left implicit.

Two classes produce all bytecode, both on `java.lang.classfile` only (no ASM, no internal
JDK packages):

| Class | Responsibility |
| --- | --- |
| `vsharp.compiler.backend.JvmBackend` | Class-level layout: which classes exist, their fields, their constructor, which callables land in which class. |
| `vsharp.compiler.backend.CodeEmitter` | Method bodies: one `CodeEmitter` per emitted method, plus the single type→carrier mapper `toClassDesc`. |

`Compilation.emit()` runs the front end through `Phase.CODE_GENERATION` and surfaces results
as `UnitAnalysis.Emitted`, whose `classes()` is an ordered `Map<String, byte[]>` from JVM
binary name to class bytes. The CLI writes exactly that map (see `docs/CLI.md`).

## Class layout

V# has no user-declared classes in this subset, so the backend synthesises **holders**.

A holder is created for every distinct owner segment among the unit's callables and fields:

* A member declared inside `namespace Demo { static class Program { ... } }` has qualified
  name `Demo.Program.Main`; its holder is `Demo.Program`, emitted as `Demo/Program.class`.
* A top-level statement or member with no owner segment belongs to the **file holder**,
  named after the source file's base name if that is a valid Java identifier, otherwise
  `Program`. `Point.vs` yields `Point.class`.
* One source file may produce several classes. `MultiClass.vs` declaring
  `namespace A { struct B ... struct C ... }` emits both `A/B.class` and `A/C.class`.

Every holder gets a public no-argument constructor that chains to `Object.<init>` — instance
members are reachable from Java, and V# code that constructs a holder has a real `<init>` to
call. Local functions and synthesised functions carry no owner segment and are therefore
emitted into their file's holder.

## Type mapping

An array type is canonical before it reaches the backend: `TypeSymbol.Array` flattens a nested
element into one rank list, outermost first, so `int[][]` has a single spelling and
`jvmDepth()` (the sum of the ranks) is the number of JVM array levels - `int[]` is `[I`,
`int[][]` and `int[,]` are both `[[I`.

`CodeEmitter.toClassDesc(TypeSymbol)` is the **single carrier authority**.
Field declarations, field access sites, method descriptors and constructor descriptors all
read it, because the JVM resolves a field by name *and* descriptor: two mappers that disagree
produce a `NoSuchFieldError` that passes the verifier and fails at first execution.

Builtin descriptors are derived from the same `JvmTypeKind` that drives the load/store/return
instructions, never from the V# spelling, so a descriptor and its instructions cannot
disagree by construction.

| V# type | JVM carrier | Descriptor | Note |
| --- | --- | --- | --- |
| `sbyte`, `byte` | `byte` | `B` | JVM `byte` is signed, so it carries C# `sbyte`; `byte` shares the carrier. |
| `short`, `ushort` | `short` | `S` | |
| `int`, `uint` | `int` | `I` | |
| `long`, `ulong` | `long` | `J` | |
| `nint`, `nuint` | `long` | `J` | R5: 64-bit only; C#'s 32-bit native size is not modelled. |
| `char` | `char` | `C` | Unsigned 16-bit code unit, matching C#. |
| `float` | `float` | `F` | |
| `double` | `double` | `D` | |
| `bool` | `boolean` | `Z` | |
| `void` | `void` | `V` | |
| `string` | reference | `Ljava/lang/String;` | |
| `decimal` | reference | `Ljava/math/BigDecimal;` | `BigDecimal` constrained to C#'s 96-bit coefficient/scale-28 envelope by `VsDecimal`. |
| `object`, `dynamic` | reference | `Ljava/lang/Object;` | |
| named type (struct/enum) | reference | `L<qualified name>;` | Full qualified name, e.g. `LDemo/Point;`. |
| array | reference | JVM array descriptor, e.g. `[I` or `[Ljava/lang/Object;` | Each source rank contributes one array level; nullable elements use reference slots. |
| nullable `T?` | reference | `Ljava/lang/Object;` | `null` means no value; a present value is boxed `T`. The first executed vertical is `int?`. |
| tuple, range | reference | `Ljava/lang/Object;` | Runtime instances use `VsTupleN`/`VsRange`; the static carrier remains widened. |

Small integral types are promoted to the `int` carrier for arithmetic and normalised back on
store, matching both C# numeric promotion and the JVM's own operand stack rules.

Signedness is **semantic, not representational**: `uint` and `int` share carrier `I`, and the
difference lives in the operators and conversions the semantic layer selects. A `uint` field
is declared `I` and read with `iload`; declaring it `Ljava/lang/Object;` while loading it with
`iload_0` was a real `VerifyError` fixed.

Because the carrier is shared, every place where the *interpretation* of the bit pattern is
observable must be selected by the V# type rather than by the opcode:

| Operation on `uint`/`ulong`/`nuint` | Emission |
| --- | --- |
| `/`, `%` | `Integer.divideUnsigned`/`remainderUnsigned` (or the `Long` pair) — `idiv` reads both operands as signed |
| `<`, `<=`, `>`, `>=` | `Integer.compareUnsigned`/`Long.compareUnsigned` then the `if<cond>` against `0`; `if_icmplt`/`lcmp` would order `4294967295u` below `1` |
| `==`, `!=` | unchanged: bit comparison is signedness-free |
| `>>` | `iushr`/`lushr` — C# reads `>>` from the left operand's signedness |
| `→ float`/`double` | `VsUnsigned.toFloat`/`toDouble`: `i2d` on a `uint` gives `-1.0`, and `l2d` reads a `ulong` as signed |
| `float`/`double` → | `VsUnsigned.fromFloat`/`fromDouble`: `d2l` saturates at `Long.MAX_VALUE` instead of wrapping into the unsigned range |
| `uint` → `long`/`float`/`double` | mask with `0xFFFF_FFFFL` after `i2l` (zero extension, not `i2l`'s sign extension) |
| `ulong`/`uint` → narrower or same-width | the ordinary truncating opcode; `unchecked` truncation is signedness-free |
| rendering | `VsFormat.toDisplayStringUnsigned`, reached through the `WriteLineUnsigned`/`WriteUnsigned` runtime members |
| boxing / nullable value state | `VsUnsigned` private immutable boxes retain unsigned identity; primitive descriptors stay `byte`/`short`/`int`/`long` |

The console pair is the one place where the JVM method name has to differ from the C# member
name: `Console.WriteLine(uint)` and `Console.WriteLine(int)` have the same descriptor `(I)V`,
so they cannot both be called `WriteLine`. `RuntimeLibrary` holds the whole `extern` mapping
table keyed by V# signature (qualified name plus parameter type spelling), which is what lets
`uint` and `int` select different JVM targets while sharing a carrier.

## `System.Math.Abs`

The curated overload family is `sbyte`, `short`, `int`, `long`, `nint`, `float`, `double`
and `decimal`, all declared as `extern` in `corelib.vs` and mapped by `RuntimeLibrary` to
`vsharp.runtime.VsMath`. The signed integral forms cannot use the JDK `Math.abs`: C# throws
`System.OverflowException` for the signed minimum (`sbyte.MinValue`, `short.MinValue`,
`int.MinValue`, `long.MinValue`, `nint.MinValue`) while Java silently wraps. `int`/`long`/
`nint` delegate to `Math.absExact` (whose `ArithmeticException` is exactly what
`System.OverflowException` maps to); `sbyte`/`short` test their minima before negating.
`float`/`double` delegate to `Math.abs`, which already matches C# for finite values and NaN,
and `decimal` negates through `VsMath.absDecimal` over the validated `BigDecimal` carrier.

`System.Math.Max`/`Min` follow the same curated pattern over the complete C# overload set
(`sbyte`, `byte`, `short`, `ushort`, `int`, `uint`, `long`, `ulong`, `nint`, `nuint`,
`float`, `double`, `decimal`). The signed and floating forms reuse the JDK `Math.max`/`Math.min`,
whose NaN and signed-zero behaviour match the .NET 10 oracle; unsigned forms compare with
`Integer.compareUnsigned`/`Long.compareUnsigned`/`*toUnsignedInt` because their carriers are
signed; decimal forms use `VsDecimal.compare` and reproduce .NET's operand choice for equal
values (`Max` returns the first, `Min` the second). The result type is therefore C#'s own
overload result, never a widened carrier.

`System.Math.Clamp` has the same complete 13-type overload set. Each helper first checks
`min > max` and throws the `IllegalArgumentException` carrier for `System.ArgumentException`,
then returns `min`, `max`, or the original `value` operand. Unsigned carriers use unsigned
comparison and decimal uses `VsDecimal.compare`; returning the selected operand unchanged
preserves decimal scale, floating NaN payloads and signed zero. A 77-record .NET 10/JDK 25
oracle covers every overload and these identity/exception edges byte-for-byte.

`System.Math.Sign` covers `sbyte`, `short`, `int`, `long`, `nint`, `float`, `double` and
`decimal`; every overload returns `int`, exactly as C# declares. The integral/nint forms use
`Integer.signum`/`Long.signum`. The float/double forms cannot use Java's `Math.signum` as-is:
C# `Math.Sign(float.NaN)`/`Math.Sign(double.NaN)` throws `ArithmeticException`, while Java
returns NaN, so `VsMath.signFloat`/`signDouble` test `isNaN` first and then delegate. The
decimal form is `BigDecimal.signum()`.

`System.Math.Floor`/`Ceiling`/`Truncate` are double-to-double and map to `VsMath`.
`Floor`/`Ceiling` delegate to `java.lang.Math.floor`/`ceil`, which already match C# for
fractional values, signed zero, NaN and infinities. `Truncate` selects `Math.ceil` for
negative values and `Math.floor` otherwise, reproducing C#'s toward-zero rule and the
signed-zero/NaN behaviour measured against the .NET 10 oracle.

`System.Math.Sqrt(double)`, `Pow(double,double)` and natural `Log(double)` also map to
`VsMath`. `Sqrt` and `Log` delegate directly. `Pow` cannot: Java returns NaN where .NET
returns one for `1` raised to NaN or either infinity and for `-1` raised to either infinity.
It also retains a negative NaN sign at exponents `-1` and `1` where .NET clears it. Two
value guards and a sign-bit repair limited to those NaN exponent cells make the full
12,687-record .NET 10/JDK 25 oracle corpus raw-bit identical.

`System.Math.Sin(double)`, `Cos(double)` and `Tan(double)` are the next bounded family and
delegate through `VsMath` to `java.lang.Math`. They were selected by a five-call-site
scientific workload tie because this direct trio is a smaller coherent API than the mixed
`Exp`/`Log10`/two-argument-`Log` alternative. The 32,794-record .NET 10/JDK 25 comparison
covers fixed IEEE edges, 16,384 arbitrary raw inputs and 16,384 finite values in
`[-1000,1000)`. Result categories and all fixed raw bits agree. Finite values differ in 58
`Sin`, 62 `Cos` and 115 `Tan` records, always by exactly one ULP; `StrictMath` is measurably
worse at 827/789/881 differences. the design therefore makes one ULP the explicit compatibility
boundary for this approximate family rather than claiming bit identity. Inverse
trigonometry, `Atan2`, `Exp`, `Log10` and two-argument `Log` remain outside the surface.

`System.Math.Round` is the eight-overload family over `double` and `decimal`, plus the
`System.MidpointRounding` enum that four of them take. The enum is a corelib declaration
carried as `int` like every other V# enum, so the `RuntimeLibrary` key spells
`System.MidpointRounding` while the emitted descriptor is `I`; the runtime helpers take the
mode as a plain `int` and validate it, which is what keeps `(MidpointRounding)7` a checked
`System.ArgumentException` rather than an unmatched switch arm.

The `double` forms map to `VsMath.round*`. `ToEven` is the JVM's own `Math.rint`, and
`AwayFromZero` is the one mode with no JDK counterpart: it splits the integral part off and
steps away from zero only when the discarded fraction reaches a half, working from the
fraction rather than adding `0.5` so signed zeros, infinities and NaN pass through intact.
The digit forms scale by a table of exact `double` powers of ten rather than computing
`Math.pow(10, digits)`, which would introduce representation error the .NET operation does
not have. Validation order is measured, not assumed: `digits` is checked first and
unconditionally, while `mode` is not checked when a value at or above `1e16` (or NaN) does
not reach the rounding step at all - so `Math.Round(double.NaN, -1)` throws for the digits
and `Math.Round(2.5, 99, (MidpointRounding)99)` throws for the digits before the mode.

The `decimal` forms map to `VsDecimal.roundToScale` through the same `VsMath` entry points.
A decimal keeps its stored scale, so rounding is a scale *reduction*, never a normalisation:
a value already at or below the requested scale is returned untouched (`1.00m` rounded to
three decimals stays `1.00m`), and the result re-enters the coefficient/scale envelope check
because rounding away from zero can grow the coefficient. Unlike the `double` forms the mode
is validated for every value, because a decimal always has a scale to reduce. `decimals` is
bounded by `VsDecimal.MAX_SCALE` (28) rather than the `double` limit of 15.

C# raises `ArgumentOutOfRangeException` for an invalid digit count and `ArgumentException`
for an invalid mode. V#'s corelib exception set has no `ArgumentOutOfRangeException` carrier
, so both arrive as `System.ArgumentException` - the base C# itself uses - carrying
.NET's message text verbatim. That text was measured from the runtime, not paraphrased: the
two digit-count messages do not share a wording (`Rounding digits must be between 0 and 15,
inclusive. (Parameter 'digits')` for `double`, `Decimal can only round to between 0 and 28
digits of precision. (Parameter 'decimals')` for `decimal`), and the mode message renders the
rejected `int` (`The value '5' is not valid for this usage of the type MidpointRounding.
(Parameter 'mode')`). A 4,025-record .NET 10 oracle over both carriers, every mode, every
digit count edge and the invalid-argument cells was replayed through the shipped runtime and
matched byte-for-byte - 3,982 value records and all 43 throwing records, message included -
once NaN sign bits and decimal negative-zero sign flags are normalised the way the rendering
path already normalises them.

## Floating-point constants

`float`/`double` `NaN`, `PositiveInfinity`, `NegativeInfinity` and `Epsilon` are C# `const`
fields (§7.3), and V# binds them as literals for the same reason as `MinValue`/`MaxValue`
: a field read would need a corelib class the backend never emits, and would lose
constant folding. `Epsilon` is the smallest positive denormal - the value Java spells
`MIN_VALUE` - which is why `double.MinValue` above cannot be taken from the JDK constant of
the same name.

## `System.Int32` parsing

`Int32.Parse(string)` and `Int32.TryParse(string, out int)` map to
`vsharp.runtime.VsInt32`. The runtime scanner implements the invariant form of the default
integer grammar directly: ASCII digits, one optional ASCII `+`/`-`, and leading/trailing
U+0009..U+000D or U+0020. It also retains .NET's measured trailing-NUL compatibility rule.
Provider, `NumberStyles`, Span and UTF-8 overloads stay absent; admitting them would require
the culture and stack-only surfaces excluded elsewhere in the feature matrix.

This invariant qualifier is a real policy boundary: .NET's one-string members normally use
the current culture. The oracle found the same sign spellings under invariant, `en-US` and
`tr-TR`, but `ar-SA` prefixes its sign with U+061C. V# has no culture model, so the design fixes
these curated calls to invariant signs and documents that difference rather than inheriting
the host JVM locale.

The scanner carries `SUCCESS`, `FORMAT` or `OVERFLOW` as data, so `TryParse` does not use
exceptions for ordinary failure and writes zero to its `int[]` out cell on every false
return. `Parse` translates those states to the curated exception carriers: null input is
`System.ArgumentException` (the existing `ArgumentNullException` base-type approximation),
malformed text is the distinct `System.FormatException`, and a valid out-of-range magnitude
is `System.OverflowException`. Syntax is validated before range is reported, matching the
measured distinction between `2147483648x` (format) and `2147483648 ` (overflow).

`System.FormatException` deliberately maps to the small runtime class `VsFormatException`,
not Java's tempting `NumberFormatException`: the Java type derives from
`IllegalArgumentException`, but .NET `FormatException` and `ArgumentException` are siblings.
The custom unchecked carrier therefore preserves both `catch (FormatException)` and the fact
that `catch (ArgumentException)` must not intercept malformed numeric text.

## `System.Double` parsing

`Double.Parse(string)` and `Double.TryParse(string, out double)` map to
`vsharp.runtime.VsDouble` under the same deterministic invariant-culture policy. The scanner
implements the invariant `NumberStyles.Float | AllowThousands` grammar: ASCII decimal digits,
optional sign/point/exponent, the the design whitespace set, and permissive comma separators after
at least one integer digit but only before the decimal point or exponent. It validates the
grammar before delegating decimal-to-binary conversion to `Double.parseDouble`, thereby
rejecting Java-only hexadecimal forms and `f`/`d` suffixes. A 25,000-record generated corpus
including grouping and exponents matches .NET 10 raw result bits byte-for-byte.

The compatibility edges are explicit. Decimal overflow succeeds with signed infinity;
underflow succeeds with signed zero; invariant `NaN` and `Infinity` are ASCII-case-insensitive
and accept an outer sign/number whitespace; every NaN spelling becomes .NET's canonical raw
bits `FFF8000000000000`; and the numeric path accepts the measured trailing-NUL rule while the
special-symbol path does not. `TryParse` writes positive zero for every false result, while a
successful negative underflow retains negative zero. Malformed input reuses the rule's distinct
`VsFormatException`; null `Parse` retains the documented `ArgumentNullException` base-carrier
approximation. Culture/provider/style, Span, UTF-8 and every other numeric parsing family stay
outside this scope.

## `System.Boolean` parsing

`Boolean.Parse(string)` and `Boolean.TryParse(string, out bool)` map to
`vsharp.runtime.VsBoolean`. Boolean parsing is culture-independent: after trimming NUL plus
the exact 25-character the design `Char.IsWhiteSpace` BMP set from both ends, only ASCII-case
variants of `True` and `False` are accepted. An exhaustive one-position mutation over every
UTF-16 code unit at every token position proves that no Unicode fold is admitted. Embedded
trim characters remain invalid, and an all-trim input is not a token.

The helper uses an allocation-free three-state result so valid `false` remains distinct from
failure. `TryParse` assigns false for every failure. `Parse(null)` uses the existing
ArgumentNullException base-carrier approximation with Boolean's measured parameter name
`value`; malformed input raises the rule's `VsFormatException` with Boolean's distinct measured
message.

## `System.Char` invariant operations

The culture-independent `IsDigit(char)`, `IsLetter(char)`, `IsLetterOrDigit(char)` and
`IsWhiteSpace(char)` members map to `vsharp.runtime.VsChar`. A complete comparison of all
65,536 UTF-16 code units between .NET 10 and JDK 25 produced identical classification
membership (370 digits, 48,973 letters and 49,343 letters-or-digits), so those helpers use
the matching `Character` predicates. `IsWhiteSpace`
reuses the explicit 25-code-unit C# set already used by `String.IsNullOrWhiteSpace` and the
trim family; Java's predicate differs at nine code units.

`ToUpperInvariant(char)` and `ToLowerInvariant(char)` are also included. A four-column BMP
oracle (`code|letter-or-digit|upper|lower`) replays byte-for-byte with SHA-256
`6dae506e9f67855e376551a9b18b382479450e901115c87e69f739474f132cd4`. Java's simple
`Character` casing differs at exactly two inputs: .NET invariant char casing leaves U+0131
unchanged when uppercasing and U+0130 unchanged when lowercasing. `VsChar` guards those two
values and delegates every other code unit. Locale-root *string* casing is not used because
it expands single code units and has 103 uppercase mismatches against the char-returning API.

`Char.ToUpper(char)` and `Char.ToLower(char)` remain absent. Those C# overloads use the
current culture: under `tr-TR`, `ToUpper('i')` is U+0130 and `ToLower('I')` is U+0131, while
their invariant results are U+0049 and U+0069. V# has no culture model, so mapping either
overload to Java's locale-independent character conversion would violate that rule.

## `System.Convert` radix pair

The bounded culture-independent Convert surface is `ToString(int, int toBase)` and
`ToInt32(string, int fromBase)`, both mapped to `vsharp.runtime.VsConvert`. Only bases
2, 8, 10 and 16 are valid. Decimal conversion is signed; every other base renders or parses
the full UInt32 bit pattern, so `Convert.ToString(-1, 16)` is `ffffffff` and parsing that text
returns `-1`. Hex input alone permits an optional `0x` prefix, after an optional plus sign.
A minus sign is valid only for base 10, no form accepts whitespace or Unicode digits, and a
null input converts to zero after the base has been validated.

The helper keeps .NET's distinct argument, format and overflow outcomes, including its two
format messages (no recognizable digits versus trailing non-parsable characters), signed
`Int32` versus unsigned `UInt32` overflow messages, and the runtime's unusual empty-string
argument failure. A 4,968-record .NET 10 corpus covers the targeted grammar/failure matrix
plus 512 deterministic values round-tripped in every valid base; the runtime replay matches
byte-for-byte at SHA-256
`5efe86691c0524ce8ab075fd1bea536421d855bc2fa8fafa89899ca7635e0247`.

The wider `System.Convert` facade remains absent. Its numeric/object/provider overloads add
no capability beyond V# conversions and the curated parsers, and were not selected by the
ordinary-program probe that admitted this pair.

## `System.String` invariant casing

`String.ToUpperInvariant()` and `ToLowerInvariant()` map to receiver-static
`vsharp.runtime.VsString` calls. .NET invariant string casing is simple,
context-independent code-point casing: it does not expand `ß` or the presentation ligatures,
and lowercasing a word-final capital sigma produces ordinary sigma rather than final sigma.
Java's `String.toUpperCase/toLowerCase(Locale.ROOT)` therefore cannot carry the operation; it
differs in 113 of the 65,556 BMP-plus-context oracle records through expansions, final-sigma
context and Turkish-I handling.

`VsString` instead walks Unicode code points and applies the simple `Character` mapping via
the same two guarded helpers `VsChar` uses. This is exhaustive rather than heuristic: the
candidate matches .NET 10 byte-for-byte for all 1,112,064 Unicode scalar values, including
supplementary mappings, at SHA-256
`facf78c8b5332c8266645b9182a5cae085668f63635c2c860af23747fd1888c5`. Named context cases
cover combining marks, Greek sigma, sharp-s/ligatures, Deseret pairs, Turkish I and unpaired
surrogates. A null receiver raises the existing `NullReferenceException` carrier.

The current-culture `ToUpper()` and `ToLower()` overloads remain excluded under that rule.

## Fields

`unit.fields()` is the complete field population: every `FieldSymbol` originates from a
declarator bound in `DeclarationBinder.bindField` and recorded by `UnitLowerer`. `JvmBackend`
emits each into its holder with `ClassFile.ACC_PUBLIC`, adding `ACC_STATIC` when the
declaration carries `static`, and always with `CodeEmitter.toClassDesc(field.type())`.

Fields are emitted **even when no method references them** — a declared field is part of the
type's contract and visible to Java reflection, not an artefact of use.

Access sites: `getstatic`/`putstatic` for static fields, `getfield`/`putfield` for instance
fields (an unqualified instance field reference loads `this` via `aload_0`). Compound and
increment forms duplicate the receiver/value with `dup`/`dup2`/`dup_x1`/`dup2_x1` chosen by
carrier width.

Static field initializers are collected per holder, in declaration order, into a synthetic
callable named `<static-init>` that the emitter renders as `<clinit>` (`ACC_STATIC`, no
`ACC_PUBLIC`, descriptor `()V`). The JVM runs it on first use of the class, which is when C#
runs an implicit static constructor. Instance field initializers have no such hook - V#
declares no constructors - and are refused at binding instead of being emitted and never run.

## Positional `record struct`

A `record struct Point(int X, long Y)` is an ordinary JVM class, not a `java.lang.Record`:
the JVM record shape would make the components private and final, while C# gives a
non-`readonly` record struct settable members.

* Each primary-constructor parameter becomes a public instance field of the holder, at the
  component's own descriptor (`X:I`, `Y:J`), emitted through the same `unit.fields()` path
  as a declared field.
* `UnitLowerer` records the declaration as an `IrRecordStruct`, and `JvmBackend` emits its
  constructor structurally — `aload_0; invokespecial Object.<init>()V`, then one
  `aload_0; <load parameter>; putfield` per component in declaration order, then `return`.
  There is no body to lower because no source declares one. The parameterless constructor
  every holder receives stays alongside it: a positional record struct always has at least
  one component, so the descriptors cannot collide.
* `new Point(1, 2L)` is the ordinary `IrExpression.ObjectCreation` shape
  (`new`/`dup`/arguments/`invokespecial <init>(IJ)V`) against that constructor.
* `p with { X = 9 }` (`IrExpression.RecordWith`) evaluates the receiver once into a slot,
  then calls the same constructor with one argument per component: the replacement where the
  source named one, `aload slot; getfield` everywhere else.
* Deconstruction reuses `TupleDeconstructionStore`. Its `components` list is empty for a
  tuple (elements read with `checkcast VsTupleN; invokevirtual itemN()`) and holds the
  component fields for a record struct, which are read with `checkcast Point; getfield` at
  the declared descriptor — no boxing on that path.
* A positional pattern over a record struct lowers to the same per-component `getfield`
  tests as a property pattern over a struct field.

`Equals`/`GetHashCode`/`ToString` are deliberately not generated *for a declared type*: in C#
each is an override of an `object` virtual member, which is the object model V# omits. The
universal members below are a different mechanism — a static dispatch over closed carriers,
not a virtual member any source can override.

## Universal members

`ToString()`, `Equals(object)`, `GetHashCode()` and `GetType()` are declared in `corelib.vs`
on the nine carriers whose boxed JVM class is exactly the C# type (`object`, `string`, `bool`,
`char`, `int`, `long`, `float`, `double`, `decimal`). `sbyte`/`short` box as `Integer` and the
unsigned family shares its carrier with a signed type, so a JVM type test there would answer
`true` where C# answers `false`; those carriers keep the members absent instead of answering
wrongly. `RuntimeLibrary` maps each declaration to a static `vsharp.runtime.VsObject` call
taking the receiver, so the emitted shape is `invokestatic VsObject.equals(...)Z`,
`VsObject.hash(...)I` and `VsObject.type(Ljava/lang/Object;)Ljava/lang/Class;` — never
`invokevirtual`, because four carriers answer differently from the JVM: `Double`/`Float`
compare raw bits (`0.0 != -0.0`, `NaN == NaN`), `Boolean` hashes 1/0 rather than 1231/1237,
`Character` hashes `value | value << 16`, and `BigDecimal` normalises scale so `1.00m` equals
and hashes with `1m`. Overloads on the primitive carriers avoid boxing where the receiver is
already unboxed. A null receiver reaches an ordinary `NullPointerException`, which is the JVM
class carrying `System.NullReferenceException`.

`GetType()` is the one member whose C# and JVM names differ by more than case, so it is also
the only one with a second binding path: on a **Java** receiver `ExpressionBinder` resolves
`GetType` to the inherited `getClass` and emits a plain `invokevirtual`/`invokeinterface`,
after the PascalCase→camelCase translation has failed to find a declared `getType`. A
Java class that declares its own `getType()` keeps it, because the written name is tried
first. `System.Type` **is** `java.lang.Class` (like `object`/`string` being
`java.lang.Object`/`java.lang.String`, the design), so the result carries the whole reflective
surface and compares by identity, and a `Class` crossing back from a JDK method is the same
V# type rather than a second one. The divergence is the *name* a `Type` renders: the JVM
reports `java.lang.Integer` where .NET reports `System.Int32`, because the boxed carrier is
what every other universal member already answers about.

## Callables

`buildMethodTypeDesc` builds every descriptor from the same `toClassDesc`. A callable is
emitted `ACC_PUBLIC`, plus `ACC_STATIC` when declared `static`. `CodeEmitter.allocateParameters`
assigns slots in declaration order; instance methods reserve slot 0 for `this`.

Call sites:

| Shape | Instruction |
| --- | --- |
| `System.Console.WriteLine` | `invokestatic vsharp/runtime/stdlib/Console.WriteLine` (corelib special case, the design) |
| `string.Substring(start)` | `invokevirtual java/lang/String.substring(I)Ljava/lang/String;` |
| `string.Substring(start, length)` | computes `start + length`, then invokes `java/lang/String.substring(II)Ljava/lang/String;` |
| `value.ToString()` on a builtin | receiver stacked, then `invokestatic vsharp/runtime/VsFormat.toDisplayString(<carrier>)Ljava/lang/String;` - `toDisplayStringUnsigned` for `uint`/`ulong` |
| `int.MaxValue` and the other limits | no call at all: bound as a folded literal, so the constant is `ldc`-ed |
| static callable | `invokestatic` on the holder derived from the qualified name, or on the file holder when the name has no owner segment (a local function of top-level statements) |
| capturing local function | its ordinary arguments followed by one shared one-element array cell per ordered transitive capture; a non-static local inside an instance callable is an instance helper and also receives the enclosing receiver as JVM slot 0 |
| entry point bridge | `main(String[])` forwards to `Main`, then `invokestatic java/lang/System.exit(I)V` when `Main` returns `int` |
| instance callable | `invokevirtual` on the receiver's carrier |
| extension method in receiver form | `invokestatic` on the declaring holder with the receiver as argument 0 - byte for byte the static-form call, because binding rewrites `receiver.M(a)` to `Holder.M(receiver, a)` before lowering |

Optional parameter defaults are retained on `ParameterSymbol` and substituted into the bound
call after overload resolution, so the caller's bytecode contains the default value. Bound and
IR calls also retain the destination parameter ordinal of every argument. A positional call can
therefore push operands directly; a named call whose textual order differs from parameter order
evaluates the receiver and explicit arguments once into fresh locals, then reloads them in JVM
descriptor order. This preserves C#'s left-to-right source evaluation rule while still satisfying
the operand-stack order required by `invoke*`.

A captured local or parameter is lifted into the same one-element-array cell representation used
by `ref`/`out`. The declaring callable creates the cell, every load and store addresses element 0,
and local-function calls pass the cell itself after their source parameters. Capture closure is
computed across the local call graph, so an intermediate or recursive helper forwards the same
cell even when it does not read the value directly. This preserves mutation, exceptional exits,
and aliasing between an explicit `ref x` argument and an implicit capture of `x`. A captured source
parameter keeps the callable's public descriptor and is lifted on entry; the added cell parameters
exist only on private local-function helpers. Non-static helpers inherit the enclosing callable's
receiver mode, while explicit static-local captures are rejected with `VS8421`/`VS8422`.

A generic V# declaration has one erased JVM descriptor: a direct type parameter `T` is
`Object` in both parameters and results, and so is a tuple of any shape - a V# tuple reaches
every descriptor position as `Ljava/lang/Object;` and is narrowed by the `checkcast` to
`VsTupleN` that each element access already emits, which is why `(T, T)` needs no rule of its
own beyond being admitted. A nullable is opaque in exactly the same way: `int?` is a
boxed value or `null` in an `Object` position, so `T?` is admitted on the same terms,
guarded by the `where T : struct` that the design made enforceable. The result is narrowed back from the *expression's*
type rather than the selected symbol's return type: the two agree for every ordinary
call and differ when a type parameter only a lambda or the call's target could supply is
closed after the call node is built, and reading the open one emitted no narrowing at all -
a class the JVM refuses to link, from a compilation that reported nothing. The ref/out call
path narrows its result in the same place, before its copy-backs, which are stack-neutral.
The selected call retains both that declaration and the inference-specialised symbol. The declaration supplies the actual `invoke*` descriptor;
the specialised symbol supplies call-site adaptation, boxing a primitive argument and
unboxing or checking the returned `Object`. This prevents a specialised `(I)I` call from
targeting a declaration that exists only as `(Object)Object`. Generic calls with a type
parameter nested in another carrier report `VS20002` from `Compilation.emit()` before
lowering; their representation is not approximated.

A type-parameter-dependent array is carried the same way. `T[]` is
`[Ljava/lang/Object;` in the descriptor and `T[][]` is `[[Ljava/lang/Object;`; the body loads
and stores elements with `aaload`/`aastore` and reads `arraylength`, all of which are
element-type-agnostic. Nothing is copied at the boundary, so a callee that writes into the
array is observed by the caller exactly as in C#. Admission is JVM array assignability and
not a rank comparison: an argument deeper than the declaration always passes, because the
component it presents at that depth is itself an array and therefore an `object`
(`int[][]` *is* an `object[]`), while an argument of equal depth passes only when its leaf is
a reference. The two shapes erasure cannot represent are refused before lowering, each with
its own code because neither is an unfinished feature:

| Shape | Code | Why |
| --- | --- | --- |
| `First(new int[] { 1 })` against `T[]` | `VS20019` | `[I` is not assignable to `[Ljava/lang/Object;` and the JVM defines no widening to it. Boxing into an `Integer[]` copy would compile and lose the caller's aliasing. |
| `new T[n]` in a generic body | `VS20020` | The JVM allocates the erasure, an `object[]`; the caller's narrowing `checkcast` to `[Ljava/lang/String;` would then fail on a correctly built value. |

A result typed `T[]` returns the erased `[Ljava/lang/Object;` and the call site narrows it
with one `checkcast` to the inferred array type, which is the same adaptation a direct `T`
result already used.

`params T[]` is that same carrier and not a storage form of its own. Expanded binding
collapses the loose arguments into the array that is actually passed before the call is bound,
so both forms reach the emitter as one array argument and the rule above decides them
identically. The only stage that has to know the difference is overload resolution, which
considers every candidate in both forms and infers from the *element* type in the expanded
one.

A generic `ref T`/`out T` is that same cell over the *erasure*: `[Ljava/lang/Object;`,
whatever the call specialized `T` to. The cell is decided by the declaration and the
value is adapted at both crossings - boxed on the way in, unboxed or `checkcast` on copy-back -
which is the boundary a by-value `T` already crossed, turned inside out. A caller's own cell is
forwarded when the JVM accepts it there (`[Ljava/lang/String;` is an `[Ljava/lang/Object;` by
array covariance) and otherwise copied through a cell of the required type and written back,
which is the copy-in/copy-out shape every other place already uses. The by-value arguments of
such a call are adapted in the declaration's carrier too; pushing a raw `int` for a `T`
parameter is a `VerifyError`, not a wrong value, which is why `GenericRefOutTests` loads every
class it emits.

A `ref`/`out` parameter is declared with the one-element array descriptor of its type (`ref int`
is `[I`) and every read/write of the parameter inside the callee goes through `cell[0]`. At the
call site a supported place (local, plain parameter, pass-through of another `ref`/`out`
parameter, static field, instance field, or array element of any rank) is wrapped: `ref`
copies the current value into a fresh cell, `out` leaves the cell default-initialised, and a
pass-through forwards the existing cell. After the call every cell is copied back to its target
in parameter order - a local/parameter slot via `istore`/etc., a static field via `putstatic`,
an instance field via a spilled receiver and `putfield`, and an array element via spilled
receiver+indices, descending the JVM rank chain with `aaload` for leading indices and the leaf
`Xaload`/`Xastore` for the last.

An extension method has no backend representation of its own. The `this` modifier is a
binding rule: it decides which call *syntax* may reach a method, and binding then produces the
same `BoundExpression.Call` a static-form call produces, with the receiver occupying argument
zero and the call's receiver slot empty. Nothing in the IR, the emitter or the runtime
distinguishes the two, which is also what makes the result plain Java: `Text.TextExt.Shout("x")`
compiles and runs from a `.java` source against the emitted class file, with no annotation,
marker or helper involved.

A method body ends with an explicit `return_` when the lowered form did not already end in a
return (`appendImplicitReturn`).

## Statements

All implemented and covered by execution tests:

`Block`, `Expression` (popping a non-void result), `Locals`, `Return`, `If`, `For`, `Foreach`,
`While`, `DoWhile`, `Break`, `Continue`, `Throw` (expression form), `Switch`, `SwitchGoto`
(`goto case`/`goto default`), `Labeled`, `Goto`, `Try`, `Using`, `Lock`, `Checked`, `Empty`.

`Checked`/`unchecked` are pass-throughs: the JVM has no arithmetic-mode distinction, so the
checked semantics live in the operators the semantic layer already selected.

`switch` (statement) has two dispatch shapes, chosen before any instruction is emitted:

* **Value dispatch** when the governing expression is int-carried or a `string` and every
  label is an unguarded constant, `default:` or `case _:`. Dispatch is a linear `if_icmpeq`
  (or `String.equals`) chain, not `tableswitch`/`lookupswitch`: simplicity and auditability
  were chosen over dispatch performance, consistent with the rest of the emitter. This is the
  shape `goto case` jumps into, so its constant-to-label table is what `SwitchDispatch` holds.
* **Pattern dispatch** for everything else — any other carrier (`long`, `double`, `object`,
  an enum reference, a `record struct`), any label that is a pattern rather than a constant,
  and any `when` guard. Each label is tested in source order by the same pattern compiler
  `is` and switch expressions use, jumping to its section on a match; constant labels are
  still recorded so `goto case` keeps working, and `goto default`, fall-through and
  multi-label sections are unchanged.

A switch *expression* always uses the pattern compiler: its governing value is parked in a
slot of its own carrier and each arm is a test-and-branch with its optional guard.

In both forms the governing expression is evaluated **once** into a local, so repeated
comparisons cannot re-run side effects.

## `foreach`: three loop shapes

`IrStatement.Foreach` carries an `elementType` decided in binding — the type one iteration
step yields *before* any conversion to the loop variable — because the variable's own type
does not say which carrier the value arrives in (`foreach (long x in List<int>)`). The
emitter selects on the collection's source type:

* **Array** — an index loop. The collection is stored in a slot, `arraylength` bounds an
  `int` counter, and `Xaload` loads the leaf. A rectangular rank (`int[,]`) is nested JVM
  arrays, so one loop per level is emitted (`emitRectangularForeachLevel`); a jagged rank
  (`int[][]`) stays on the vector path, since each step legitimately yields an array.
* **`string`** — an index loop over `String.length`/`String.charAt`, yielding `char`. C#
  defines `foreach (char c in s)` as exactly that indexer, so a `CharSequence` iterator is
  *not* used: it would iterate code *points* and box every element.
* **`java.lang.Iterable`** — `invokeinterface Iterable.iterator()`, then the ordinary
  `hasNext`/`next` shape javac emits, with `next` returning the erased `Object`. The
  iterator lives in a local rather than on the stack so `break`, `continue` and `return`
  can unwind through cleanups that would not preserve it.

`emitErasedElement` narrows the erased `Object` to the element type the receiver's generic
signature published: nothing for `object` itself, a `checkcast` for a reference element, an
unboxing call when the element's JVM carrier is a wrapper. `emitIterationElement` then
applies the ordinary conversion from the element type to the variable type, which is how an
explicitly typed loop variable (CS0030) is emitted.

### A `foreach` that deconstructs its element

`foreach (var (a, b) in pairs)` and `foreach ((string a, int b) in pairs)` take the element
*apart* instead of testing it. Lowering keeps the loop protocol identical to every shape
above: the statement still declares exactly **one** iteration variable, a synthetic carrier
named `<element>N` that no source identifier can spell, and the deconstruction becomes the
first statement of the body — an ordinary `TupleDeconstructionStore` of the carrier into the
locals the designation introduces. `break`, `continue`, cleanups and the three carrier loops
therefore need no deconstruction-specific machinery, and the element is read once per step.

Component values come from the same two sources a positional pattern uses: tuple items
(`item1`, `item2`, …) for a tuple element, and the component *fields* of a positional
`record struct`, read with `getfield`. A nested designation re-enters the same walk one
level down through a nested tuple target, and a `_` component still occupies a slot, named by
position, so the walk stays total. Synthetic locals are exempt from capture (`CaptureCollector`
skips names starting with `<`): they belong to lowering, not to any user scope.

## Protected regions: `try`/`catch`/`finally`, `lock`, `using`

The JVM has no `jsr`/`ret` (removed) and no "run this once regardless of exit" primitive, so
`emitProtectedRegion` guarantees a cleanup by **duplicating its bytecode at every exit**:

1. **Normal completion** — all normal paths converge on one label, cleanup runs once, control
   falls through.
2. **Exception** — a catch-all handler spans the *entire* region, `body` plus every catch body
   (a throwing catch body must still trigger cleanup): it saves the exception, runs the
   cleanup, and rethrows.
3. **`return`** — runs every active cleanup innermost-first before the real `return_`. A
   non-void value is stashed in a fresh temp local first, so the duplicated cleanup bytecode
   cannot disturb it.
4. **`break`/`continue`/`goto`/`goto case`** — runs exactly the cleanups the jump *escapes*
. Break/continue targets record `activeCleanups.size()` when established
   (`JumpTarget`); `goto` labels get their depth from the `findGotoLabelDepths` pre-pass,
   because a label may be jumped to before it is emitted. A jump that stays inside the region
   duplicates nothing.

`lock (expr) { body }` is exactly C#'s semantics: `monitorenter` with `monitorexit` as the
cleanup, reusing the same machinery. It is scoped to `REFERENCE`-kind expressions —
those are the only operands `monitorenter` accepts, and C# requires a reference type anyway.

`using` lowers to try-finally calling `close()` on the resource, validated at the semantic
layer as implicitly convertible to `java.lang.AutoCloseable` (`VS1674`,).

A `goto endLabel` is emitted unconditionally after a `try`, whether or not the body already
returned. When the whole `try` is the last thing in a non-void method with no trailing
statement, `endLabel` would otherwise bind one byte past the end of the code array, which the
verifier rejects as a branch target with no instruction; a `nop()` guarantees it is
reachable.

## Expressions

Implemented and execution-tested: `Constant` (int/long/float/double/bool/char/string/null),
`Load`, `Store`, `Binary`, `Unary`, `Convert` (including explicit cast syntax and `object` unboxing),
`Conditional`, `DefaultValue`,
`NameOf`, `TypeOperation`, `Call`, `ObjectCreation` (module-path Java classes), `FieldLoad`, `FieldStore`,
`ElementLoad`, `ElementStore`, `ArrayCreation` (flat initialiser), `ArrayLength`,
`StringLength`, `StringElementLoad`, `NullableHasValue`, `NullableValue`, `Tuple`, `TupleElementLoad`,
`Checked`, `Interpolated`, `IsPattern`, `As`, `Range`, `Collection`, `Switch` (expression),
`NullConditional` + `ConditionalReceiver`.

Runtime support classes the emitter targets directly (all in `vsharp-runtime`, JDK-only):

| Construct | Emission |
| --- | --- |
| `nameof(entity)` | The validated final identifier is carried directly in IR and emitted with `ldc`; the semantic operand is never lowered, evaluated or captured. A nameless operand reports `VS8081`. |
| `sizeof(T)` | `ldc` of C#'s predefined safe-context size: 1/2/4/8 bytes by primitive family, 16 for `decimal`. Operands that require `unsafe`, including native integers and user structs, report `VS0233` before lowering. |
| `typeof(T)` | `VsType.of(canonical source identity)`. Interning makes reference equality type equality while keeping source types that share a JVM carrier (`int`/`uint`) distinct; keyword/corelib aliases share `System.*` identity, tuple names erase, and unbound generic definitions retain their arity. |
| `Int32.Parse` / `TryParse` | `VsInt32.parse(String)` / `tryParse(String,int[])`; the `out int` uses the same one-element cell and copy-back ABI as user callables. |
| `Double.Parse` / `TryParse` | `VsDouble.parse(String)` / `tryParse(String,double[])`; the result/out cell preserves raw IEEE-754 bits, including signed zero and canonical NaN. |
| `Boolean.Parse` / `TryParse` | `VsBoolean.parse(String)` / `tryParse(String,boolean[])`; the out value uses the existing one-element boolean cell and is false on failure. |
| Java object creation | `new` + `dup` + converted arguments + `invokespecial <init>` using the selected public constructor descriptor. |
| tuple, arity 2–8 | `new vsharp/runtime/VsTupleN` + canonical constructor; a generic record erased to `Object` parameters, so each element is boxed first. |
| range `a..b`, `a..`, `..b`, `..` | `vsharp/runtime/VsRange` constructor matching the form; `int` endpoints wrap via `VsIndex.fromStart(int)`, `^n` endpoints via `VsIndex.fromEnd`. |
| interpolated string | `StringBuilder` chain; a `String` hole is appended directly, anything else is boxed and passed through `VsFormat.toDisplayString(Object)` for C#-faithful formatting. |
| string `Length` / `s[i]` | `java.lang.String.length()` / `charAt(int)`, preserving C#'s UTF-16 code-unit semantics. |
| null-conditional `r?.m(a)`, `r?[i]` | The receiver is evaluated once into a fresh local of its reference kind; `ifnonnull` jumps to the access, the fall-through arm pushes the null result (`aconst_null`, or nothing when the access is `void`) and `goto`s the join. The access subtree reads the spilled slot through `IrExpression.ConditionalReceiver`, so arguments and every further link of the chain execute only on the non-null path. A nullable value receiver (`int? x`) is also spilled as its `Object` carrier, and the `ConditionalReceiver` unboxes it to the underlying `T` before the access. Emission keeps a stack of receiver slots, so a nested chain (`a?.B()?.C()`) always addresses its nearest enclosing receiver. |
| nullable `T?` | `null` or boxed `T` in an `Object` descriptor. `HasValue` is a null branch; `Value` calls `VsNullable.requireValue` then unboxes; `??` unboxes only on a present underlying-result edge; lifted operators spill operands once, null-test, unbox, operate, and re-box. Signed-carrier unsigned elements use the the design `VsUnsigned` boxes. Nullable arrays are `Object[]`. |

Boxing uses the standard `valueOf` wrappers for primitives except C#
`byte`/`ushort`/`uint`/`ulong`/`nuint`: their signed JVM carriers cannot retain unsigned
identity through `object`, so the design uses private immutable boxes exposed only through
`VsUnsigned.box*`/`unbox*`. Structs, `Range` and `Index` are already reference carriers
mapping to `java.lang.Object` descendants, so boxing them is a JVM no-op.

Explicit unboxing `(T)o` is the inverse of those boxes: `checkcast` to the
standard wrapper followed by its `*Value()` call for the unambiguous built-ins and `nint`;
the private `VsUnsigned.unbox*` helpers for `byte`/`ushort`/`uint`/`ulong`/`nuint`;
`VsDecimal.unbox` for `decimal`; `checkcast Integer` + `intValue` for an `int`-carried enum;
and a plain `checkcast` for a declared struct's reference carrier. A nullable target
`(T?)o` null-tests first, so a null `object` becomes the no-value state instead of throwing.

Checkpoint 72 fixes nullable boxing as a representation identity: boxing `T?` to `object`
does not allocate or unbox because the carrier is already either `null` or boxed `T`.
For signed-carrier unsigned `T`, the design changes only which immutable underlying box represents
the present state; nullable descriptors and the null state remain unchanged. The unbox
helpers also accept the former `Byte`/`Short`/`Integer`/`Long` spelling from Java callers.
Accessing `.Value` with no value throws `IllegalStateException` through `VsNullable`; this is
catchable as `System.Exception`, but its Java exception class differs from .NET's
`InvalidOperationException` and is a documented JVM boundary choice.

The built-in `string` surface is deliberately curated instead of exposing arbitrary
`java.lang.String` members. the design currently includes `Length`, integer indexing, both
`Substring` overloads, the instance `Trim`/`TrimStart`/`TrimEnd`, the ordinal
`Contains`/`IndexOf`/`LastIndexOf`/`StartsWith`/`EndsWith`/`Replace` family, and the static
`IsNullOrEmpty`/`IsNullOrWhiteSpace` checks, because their UTF-16/value semantics map
directly through `VsString`. Neither `IsNullOrWhiteSpace` nor the trim family uses Java's
`String.isBlank()`/`String.trim()`: a full BMP sweep against the .NET 10 oracle showed C#
and Java whitespace classification differ at `\u001C`..`\u001F`, `\u0085`, `\u00A0`,
`\u2007` and `\u202F`, so `VsString` encodes the exact C# `char.IsWhiteSpace` BMP set and
trims with it. Java's `String.trim()` strips only ASCII `<= ' '`, which would leave NBSP and
NEL intact. Culture-sensitive defaults and superficially similar methods with different edge
cases remain absent until a C#-compatible runtime adapter exists.

The search/replace family is admitted only in its ordinal spellings, which are exactly
the ones whose .NET results Java's own `indexOf`/`lastIndexOf`/`replace` reproduce: the
`char` overloads of `Contains`, `IndexOf`, `LastIndexOf`, `StartsWith` and `EndsWith`,
`Contains(string)`, and both `Replace` overloads. `IndexOf(string)`, `StartsWith(string)`,
`EndsWith(string)`, `ToUpper` and `ToLower` compare or map with the current culture in C# and
stay out. The `VsString` helpers exist for the argument contract rather than the search: the
receiver is null-checked before any argument (C# observes `NullReferenceException` first), a
null argument raises `ArgumentException`, an empty `oldValue` is rejected instead of being
inserted between every character the way Java's `replace` would, and a null `newValue`
deletes. `Replace` scans left to right without overlap in both languages, so `"aaa"` with
`"aa"` replaced by `"b"` yields `"ba"` on each.

The culture-independent composition subset is `String.Concat(params string[])` and
`String.Join(string, params string[])`, both mapped to `VsString`. Null elements render as
empty strings; a null Join separator is empty; empty arrays return the empty string; and a
null values array raises `ArgumentException` (the same documented `ArgumentNullException`
carrier limitation as the design). Expanded params elements are evaluated left to right before the
runtime call. .NET 10 also exposes object/string `params ReadOnlySpan` overloads, which make
`String.Concat()` and `String.Join(",")` ambiguous. V# represents the admitted string-only
subset with arrays, and the binder preserves that zero-value ambiguity explicitly rather
than accepting source C# 13 rejects.

`String.Split(params char[])` is the separator-array member and is deliberately not
delegated to Java's `String.split`: that method is regular-expression based and discards
trailing empty entries, while C#'s form is a plain UTF-16 code-unit scan that keeps every
empty entry, so `n` separator occurrences always yield `n + 1` parts and an empty receiver
yields one empty part. A null or empty separator array is C#'s documented fallback to the
whitespace set, which is `VsChar.isWhiteSpace` (the rule's exact C# set) rather than Java's wider
one. The scan matches by code unit and never by code point, so a lone high surrogate splits
inside a surrogate pair; a 465-record .NET 10 oracle over the receiver/separator cross-product
pins that behaviour, including NUL as a non-whitespace separator, and is replayed
byte-for-byte by a SHA-256 digest in `runtime.string`. The `StringSplitOptions`,
`string`-separator and count-limited overloads stay absent: they need an options enum and a
distinct multi-character search, neither of which this subset admits.

The ordinal shaping family is `PadLeft`/`PadRight` in both overloads, `ToCharArray()`,
`Insert(int, string)` and both `Remove` overloads, each mapped to one receiver-static
`VsString` call. All are pure UTF-16 code-unit operations with no culture, format or
comparison model. Padding never truncates: a width at or below the current length returns the
receiver unchanged, and the padding character is copied as a bare code unit, so NUL and lone
surrogates pad like any other unit. `ToCharArray` returns a fresh copy per call and splits
surrogate pairs into their two units. Three argument contracts are reproduced exactly, because
.NET does not use one shape for all of them: `PadLeft`/`PadRight` and the two-argument `Remove`
use the modern range message with a trailing `Actual value was N.` line; `Insert` validates its
argument *before* its index and compares that index unsigned, so a negative start index is
reported as `4294967295`; and the one-argument `Remove` keeps two legacy messages that carry no
actual value at all. The two-argument `Remove` never tests the start index against the length
directly - it bounds `count` by `Length - startIndex`, so an oversized start index fails on
`count` against a negative bound. V#'s corelib has no `ArgumentOutOfRangeException` or
`ArgumentNullException` carrier, so all of these arrive as `System.ArgumentException`
carrying the shipped text verbatim. A 976-record .NET 10 oracle over the receiver/width/pad/
index/count cross-product is replayed byte-for-byte by digest in `runtime.string`.

## Numeric format specifiers

`ToString(string)` on the numeric types admits exactly the standard `D`, `X` and `F`
specifiers, mapped to `vsharp.runtime.VsNumberFormat`. the design had excluded format
specifiers as culture-dependent, but that premise was superseded by V# has no
current-culture model, so a curated member gets a *deterministic invariant contract* with the
boundary documented rather than hidden. The decimal point is `.`, the negative sign is `-`,
and no group separator, currency symbol or percent sign is ever produced.

Four measured behaviours drive the implementation, and none of them is guessable:

- **The precision is a minimum, never a width.** `(-42).ToString("X4")` is `FFFFFFD6` -
  eight digits, because the carrier's two's-complement value already needs them. `X` renders
  the carrier's width, so `-1` is `FF` as `sbyte`, `FFFF` as `short` and sixteen digits as
  `long`.
- **`double`/`float` round half to even, but `decimal` rounds half away from zero.**
  `2.5.ToString("F0")` is `2` while `2.5m.ToString("F0")` is `3`, and `0.125.ToString("F2")`
  is `0.12` against `0.125m.ToString("F2")` as `0.13`. Binary values round on their *exact*
  stored value, not on the literal that produced them, so `2.345` prints `2.35` while `2.355`
  also prints `2.35`: the two doubles straddle their literals in opposite directions.
- **Sign survives a zero magnitude for binary types but not for `decimal`.** Both `-0.0` and
  `-0.001` print `-0.00`, while `-0.004m` prints `0.00`.
- **NaN and the infinities short-circuit the entire format string.** .NET returns `NaN` or
  `Infinity` even for `D`, `X`, an unknown letter such as `Q`, and a custom format string, so
  the specifier is never parsed for those values. Only finite values validate.

Everything else is refused rather than approximated. `G`, `N`, `C`, `E`, `P` and `R` are valid
C# but select group separators, currency or exponential layout that a culture-free build
cannot render faithfully, so they raise a `System.FormatException` naming the reason. An
unrecognised single letter reproduces .NET's own `Format specifier was invalid.` text. Custom
format strings are refused as a distinct case, because .NET *renders* them rather than
rejecting them - `(-42).ToString("D2X")` yields `-D2X` - so they cannot be folded into
"invalid" without misrepresenting the language. An 825-record oracle over every admitted type
crossed with the admitted specifiers is replayed by digest in `runtime.numberformat`.

Interpolation format clauses (`{value:F2}`) reuse this engine unchanged. The clause is
literal text, so the binder validates it and reports VS20002 at compile time for anything the
engine can never honour, rather than letting it fail at run time; `VsNumberFormat` exposes the
grammar so the compile-time check and the runtime engine cannot disagree. What the binder does
*not* refuse is `D` or `X` on a floating value: C# compiles that and throws at run time, so
refusing it early would reject programs C# accepts.

Three hole shapes are distinguished, all measured:

- A **numeric** hole emits `VsNumberFormat.formatX(value, "spec")`, selected by carrier *and*
  signedness. The format runs first and the alignment clause then pads the rendered text, so
  `$"{d,10:F2}"` yields `[      2.35]`.
- A **`string`, `bool` or `char`** hole *ignores* the clause, because none of those types is
  `IFormattable` in .NET and C# ignores it too. `$"{s:F2}"` yields `abc`.
- An **enum** hole is refused. Its specifier grammar is a different one: `X` pads to the
  underlying width (`00000007`, not the minimal `7` an `int` gives) and `F` means *flags*, so
  `$"{status:F2}"` throws in C#. Mapping enums onto the numeric engine would be wrong rather
  than incomplete. Every other type, including `object`, is refused because C# resolves
  `IFormattable` at run time and V# has no such dispatch.

The alignment clause remains separate, being width arithmetic rather than a formatting policy,
which is the half of that rule that was always correct.

## System.Array

The curated `System.Array` statics are `Sort`, `Reverse` and `IndexOf` over the
primitive element types, mapped to `vsharp.runtime.VsArray`. None of them is a forward to
`java.util.Arrays`, because three behaviours were measured to differ.

*Unsigned element types share a signed carrier.* `byte`, `ushort`, `uint` and `ulong` are
carried as JVM `byte`, `short`, `int` and `long`, so the signed comparison would order `200`
before `5`. Each unsigned sort flips the sign bit, sorts, and flips it back, which is an exact
order-isomorphism between the unsigned and signed orderings. `Sort` therefore needs one helper
per V# element type, while `Reverse` and `IndexOf` - which move or compare bits - share one
helper per carrier.

*NaN sorts first in C# and last in the JDK.* .NET orders `NaN, -Infinity, ..., -0, +0, ...,
+Infinity`; the JDK's total order puts NaN last. The floating sorts rotate the trailing NaN
block to the front. Signed zero already agrees (`-0 < +0`).

*`IndexOf` uses `Equals`, not `==`.* NaN is found by NaN and `-0.0` is found by `0.0`. That is
neither Java's `==` (which fails for NaN) nor `Double.equals`/`doubleToLongBits` (which would
wrongly separate `-0.0` from `0.0`), so the predicate is written out explicitly.

`Sort(string[])` is absent because .NET orders strings with the current culture: the
measured order `_a, a, A, ab, b, B` differs from the ordinal `A, B, _a, a, ab, b`.
`IndexOf(string[], string)` *is* present because it is ordinal, proved by a culture-equal but
ordinally different pair (`U+00C5` against `U+0041 U+030A`) that .NET reports as absent.
`decimal[]` is absent because .NET's unstable sort and the JDK's stable one can order
equal-comparing decimals of different scale differently. A 212-record oracle over every
admitted element type, rendering elements as carrier bits, is replayed by digest in
`runtime.array`.

One measured difference is deliberately *not* reproduced yet: .NET's `double.NaN`/`float.NaN`
constants are the negative quiet NaN (`0xFFF8000000000000`), while V# folds them to the JDK's
positive constants. No admitted member can observe a NaN payload - formatting renders `NaN`,
comparison is false, and both `Sort` and `IndexOf` are payload-agnostic - so this is currently
unobservable. It becomes a real defect the moment any bit-inspecting member such as
`BitConverter` is admitted, and it is recorded as pending for that reason.

Resolving a call like `Split(',')` exposed a real overload-resolution defect.
A candidate is applicable in its *normal* form or, failing that, in its *expanded* form
(C# 12.6.4.2), and `OverloadResolver` only ever considered expansion when the argument count
exceeded the parameter count. At equal arity the argument count alone decides nothing — an
array selects the normal form and a single element selects the expanded one — so every
one-element `params` call (`Concat("a")`, `Join("-", "a")`, `Split(',')`) was rejected with
VS1503 naming the array type. Both forms are now attempted per candidate, normal first so the
existing "non-expanded beats expanded" ranking is unchanged, and the expanded diagnostic is
the one reported when neither applies, which is the element type C# names.

## Java interop

`JavaInterop` resolves types from the running JVM's module path with
`java.lang.classfile`, so V# can name `java.lang.AutoCloseable` without corelib declaring it.
It reads class files rather than using reflection, so a type is described exactly as the JVM
records it and no user class is ever initialised by the compiler. One instance is owned by
`SemanticModel` per binding run — no global mutable state, and caches are memoisation of a
pure function of the module path.

Descriptors decode through `MethodTypeDesc`/`ClassDesc`/`AccessFlag`. Signedness drives the
mapping (JVM `byte` → `sbyte`, JVM `char` → `char`), a Java `T[][]` becomes a jagged
`TypeSymbol.Array` of rank-1 dimensions rather than one multi-dimensional array, and
synthetic/bridge methods are filtered out so an erased bridge cannot masquerade as a real
overload. Discovery is deliberately partial: **public, non-synthetic fields; public,
non-synthetic, non-bridge methods; and public, non-synthetic constructors only**. A Java
`final` field is a V# `readonly` field, while a mutable field remains an assignment target.
Fully qualified static field access uses the field's declaring Java type directly; instance
fields use the receiver's discovered Java type. Both reuse the normal `getstatic`/`putstatic`
and `getfield`/`putfield` paths. Constructor overload resolution uses the same engine as
method calls, but only module-path-discovered Java classes may be constructed; target-typed
`new()`, V# user types and object initialisers remain excluded.

Class and method `Signature` attributes are mapped for exact invariant types. A Java
class carries its real arity and type-parameter identities; a constructed receiver substitutes
those parameters through direct and inherited members; and generic methods reuse V#'s existing
inference. Generic superclass/interface signatures carry substitutions across the hierarchy,
so `List<E>` inherits `Collection<E>.stream` as `Stream<E>` and
`String implements Comparable<String>` collapses the inherited substituted duplicate of its
direct `compareTo(String)`. The specialized function is used only for source typing and
overload selection. Each call separately retains a descriptor-exact declaration, from which the
backend builds the erased JVM descriptor; a `TypeSymbol.Constructed` likewise maps to its
definition's `ClassDesc`. This separation is what makes `ArrayList<string>.Get` return `string`
without inventing a nonexistent JVM `get(int): String` method.

A direct primitive specialization such as `ArrayList<int>.Add`/`Get` uses the existing generic
call adaptation to box into, and unbox from, the erased `Object` position. A **generic** Java
varargs method infers its element from the loose arguments: `Arrays.AsList(1, 2)` matches
each argument against the trailing array's element type, so the call is typed `List<int>`. The
declaration keeps its `([Ljava/lang/Object;)Ljava/util/List;` descriptor and the inferred
element is never approximated as an `int[]`: `anewarray java/lang/Object` is filled by boxing
each scalar as it is packed, and `Get` unboxes back through `checkcast`. An actual array argument
still binds in normal form and is passed directly, without being wrapped a second time.

A signature containing `?`, `? extends` or `? super` falls back as a whole to the erased
descriptor. The same applies when an unprojected type-parameter bound changes a direct erased
carrier (for example, `T extends Enum<T>` erases to `Enum`, not `Object`). V# has no wildcard
projection or Java-bound model, and approximating either would produce false semantics or a
wrong JVM descriptor. Generic public fields and constructor signatures also stay
descriptor-erased until they can carry their own unspecialized declaration. Raw Java type use
remains callable: failed method inference may retry erasure only for module-path declarations,
and a constructed value widens to the raw spelling of the same class. The equivalent fallbacks
do not apply to V# declarations, whose existing generic-emission boundary is unchanged.
Overloads with distinct descriptors remain distinct (for example
`ArrayList.remove(int): Object` versus `remove(Object): boolean`). Members inherited through
the superclass and interface closure are mapped.

Throw operands and explicit catch types must be `System.Exception`, `null` where C# permits
it, or a discovered Java type whose class-file superclass chain reaches
`java.lang.Throwable`; invalid types report `VS0155` before lowering. A Java exception catch
maps directly to its discovered JVM descriptor, while `System.Exception` remains the
corelib alias for `java.lang.Exception`.

A bare `catch` is emitted as the JVM's typeless catch-all handler entry, which is exactly
C#'s "catches every exception" on this platform. The one divergence — a bare `catch`
also sees `java.lang.Error`, which has no CLR counterpart — is recorded in the feature matrix
rather than papered over with an `instanceof Exception` test that would swallow-and-rethrow
with different unwinding.

Every handler stores its caught reference into a local, even when the clause names no
variable: the JVM begins each handler with exactly that one value on the stack, so the store
also leaves the stack empty for the handler body. Those slots are pushed per *handler body*
(`handledExceptionSlots`), so a bare `throw;` reloads the innermost enclosing handler's
exception and `athrow`s the same object, preserving exception identity across intervening
`finally` blocks and nesting. A `finally` body and the guarded `try` body are outside any
handler and never push, which is exactly where C# forbids a bare `throw;` (CS0156) and where
the binder reports `VS0156`. A local function declared inside a `catch` body resets the depth
while binding, because it does not handle the enclosing exception.

## Recursive patterns

`expr is T (p1, p2) { Name: p3 } name` is emitted as an optional `instanceof` narrow
followed by one subtree per component. A typeless `is { X: 1 }` still tests `ifnull`
first, because C# reads no component out of a null value, and `instanceof` already excludes
null for the typed form.

Binding decides how each component is reached, so the backend performs no name lookup: a
tuple element becomes `checkcast VsTupleN` + `itemN()` + the same unboxing tuple
deconstruction uses, a struct field becomes `checkcast` + `getfield`, and the two `Length`
forms become `arraylength` (or `VsArrays.length` for a rectangular array) and
`String.length()`. Each access is preceded by a `checkcast` to the receiver class because
the V# type of a slot is not the JVM type the verifier tracks for it — a tuple is carried as
`Object` in parameters and fields, which is exactly the shape that failed verification
before the cast was added.

Each component's value is stored into a slot of its own, primed with a default like every
other slot the pattern tree writes, and handed to the same `emitPatternTest` every node
uses, so components nest to any depth. The chain short-circuits on the first component that
does not match, which is what makes a later component's extraction unreachable rather than
merely unused: C# never reads `Y` once `X` failed.

## List patterns

`xs is [1, .. var rest, var last]` emits a null test, then a length test, then one subtree
per element. Without a slice the length must be `==` the element count; with one it
must be `>=` the count of the non-slice elements, which is what makes `[first, .., last]`
match every collection of two or more.

Prefix elements load at constant indices. Suffix elements load at `length - k`, computed
rather than folded, because their position depends on the value being matched. The element
load is an array load opcode chosen by element kind, or `String.charAt` for a `string`.

A slice with a subpattern is matched against the real slice, produced by the same
`VsSlices.slice` call a written `xs[prefix..^suffix]` emits, over a `VsRange` built from
`VsIndex.fromStart(prefix)` and `VsIndex.fromEnd(suffix)`. Sharing that call is what keeps
the pattern and the expression from disagreeing about bounds or about the empty slice. A
bare `..` produces nothing and binds nothing.

## Verification and determinism

Every emitted class in the backend suite is loaded through a real `ClassLoader` and executed,
so the JVM verifier runs on all of it: an emitted class that failed verification would fail
its test. Output is deterministic — holders iterate in `LinkedHashSet` order, fields and
callables in declaration order, and the emitted class map is a `LinkedHashMap`.

`emitStatement` and `emitExpression` are **total switches**: any lowered form they do not
implement throws `UnsupportedOperationException` naming it. There is no silent
`default -> {}`. A silent no-op would drop a statement and still produce a perfectly valid
`.class` file — a wrong program that verifies, which is strictly worse than a compiler that
refuses.

## Forms that refuse to emit

These are defensive backend refusal branches. Normal user source is rejected earlier where
a semantic gate exists; if an unsupported form nevertheless reaches emission, it throws
`UnsupportedOperationException` instead of silently producing wrong bytecode.

**Statements**

* `catch` filters (`when`)

**Expressions**

* `as` over non-reference operands or against non-reference targets
* nested (multi-dimensional) array initialisers
* interpolation hole format specifiers (`{x:F2}`); an alignment clause (`{x,10}`) is
  emitted as a `VsFormat.align` call on the rendered hole
* collection expressions targeting anything other than an array
* tuples outside arity 2–8
* range endpoints that are neither `int` nor `System.Index`
* type patterns over a value-typed operand other than the identity shape (the boxing shape
  is refused at binding with VS20002, not here)

## Corelib exceptions

`corelib.vs` declares the C# exception names the subset uses; each is carried by a real JVM
class, and `CorelibCarriers` is the single table the binder and the emitter both read:

| V# name | JVM carrier |
| --- | --- |
| `System.Exception` | `java.lang.Exception` |
| `System.OverflowException` | `java.lang.ArithmeticException` |
| `System.DivideByZeroException` | `java.lang.ArithmeticException` |
| `System.IndexOutOfRangeException` | `java.lang.ArrayIndexOutOfBoundsException` |
| `System.NullReferenceException` | `java.lang.NullPointerException` |
| `System.InvalidCastException` | `java.lang.ClassCastException` |
| `System.InvalidOperationException` | `java.lang.IllegalStateException` |
| `System.ArgumentException` | `java.lang.IllegalArgumentException` |
| `System.FormatException` | `vsharp.runtime.VsFormatException` |

`new Exception("boom")` resolves against the *carrier's* public constructors and emits
`new`/`dup`/`invokespecial` against the carrier's `ClassDesc`, while the expression keeps its
corelib type so diagnostics and `catch` clauses name what the source wrote. `catch` and
`throw` use the same table, so a JVM-raised `ArrayIndexOutOfBoundsException` is caught by
`catch (IndexOutOfRangeException)`, and `catch (Exception)` catches all of them because the
carriers really are subclasses of `java.lang.Exception`.

`FormatException` is the sole carrier implemented by `vsharp-runtime` rather than the JDK.
This is necessary to keep it outside the `ArgumentException` hierarchy while still making
it an unchecked JVM exception and a descendant of the root `Exception` carrier.

The JVM raises `ArithmeticException` for integer division by zero and for the `Math.*Exact`
operations used by checked arithmetic. Consequently `DivideByZeroException` and
`OverflowException` share a carrier: either catch can observe either failure. This is a
documented JVM representation divergence; distinguishing them would require translating
every division and every checked operation into separate runtime-owned exception classes.

## Known semantic divergences

* **The curated one-string numeric parsers use invariant punctuation/signs.** The matching
  .NET `Int32`/`Double` overloads consult the current culture; V# has no culture model, so
  the design/the design give them a deterministic invariant contract. Provider/style overloads remain
  absent.

* **`nint`/`nuint` are 64-bit** on every target, because the production JVM target is
  uniformly 64-bit. C# permits a 32-bit native size; V# does not model it (R5).
* **A generic instantiation is erased, not reified**. C# builds a distinct
  `First<int>` whose parameter really is an `int[]`; the JVM has one method whose parameter
  is `[Ljava/lang/Object;`. Every *reference* array is carried by it unchanged, so the
  difference is invisible for those, but two programs C# accepts are refused rather than
  approximated: a primitive array argument (`VS20019`) and `new T[n]` (`VS20020`). Both are
  compile-time refusals naming the exact expression; neither silently changes behaviour.
  Pinned by `GenericArrayTests`.
* **`in` parameters are passed by value**. Readonly-ness is enforced, but a variable
  passed both `in` and `ref`/`out` in one call names two copies in V#, so the `in` parameter
  does not observe the `ref`/`out` write. C# passes `in` by readonly reference, so aliasing
  is observable there. Pinned by `BackendTests.emitInParametersByValue`.
* **`decimal` is carried as `BigDecimal` and every decimal operation is emitted through
  `VsDecimal`**, the same policy constant folding uses. The JVM itself is not constrained
  to System.Decimal, so a raw `BigDecimal` crossing from Java is only revalidated when it next
  enters a `VsDecimal` operation; decimal type tests remain excluded until that boundary is
  given a public predicate.
* **Value formatting matches the local .NET 10 SDK**, reconciled in exactly one place
  (`VsFormat`) and covered by tests. The float/double thresholds were originally derived from
  the documented "G" rule and were wrong (R2, the design): the default `ToString()` of .NET Core 3.0+
  is shortest-round-trip, so the layout switches to exponential at the round-trip precision
  (17 for `double`, 9 for `float`), not at the `G` default precision (15/7). Java's shortest
  literal also carries a minimum of two significant digits, so `Double.MIN_VALUE` needed
  shortening from `4.9E-324` to .NET's `5E-324`. A 1661-value corpus - powers of ten around
  every boundary, subnormals, and random bit patterns - now renders identically in V# and in
  `dotnet` 10.0.400.
