# WCHNT Language Guide

WCHNT (**We CAN Have Nice Things**) is an object-oriented language organised
around **assemblages**: tightly coupled clusters of classes whose structure and
relationships are declared together in one place. Instead of defining classes
one by one and wiring them in imperative code, you write one declarative map of
the object network, then fill it with data and behaviour.

This document is the **user-facing language bible**. Philosophy lives in
[`intro.md`](intro.md). Compiler internals live in [`plan.md`](plan.md) and
[`development_guideline.md`](development_guideline.md). Historical feature
notes sit in [`attic/`](attic/).

**Authority.** If this guide disagrees with the compiler or with runnable
programs in `examples/` / `live-examples/`, trust the compiler and the
examples.

---

## What WCHNT is

Assemblage programming turns traditional OO inside-out. Within an assemblage,
objects are transparent to each other: relationship kinds are declared in the
Schema (owned components, context back-references, delegates, borrowed
externals, reactive observables, mailboxes). Between assemblages — and between
an assemblage and a host platform — the membrane is deliberate and opaque.

A WCHNT program is a **literate Markdown file** (`.wcn`). Prose, hyperlinks, and
unrelated fenced blocks are ignored. Code lives in fenced blocks under reserved
`##` headings.

The compile sections, in order:

| Section | Required | Purpose |
|---------|----------|---------|
| `## Import` | optional, must be first | bind sibling pages by their Public surface |
| `## Schema` | yes (for code) | declare classes, types, and relationships |
| `## Construction` | for programs | build the initial object graph |
| `## Methods` | optional | expression-based behaviour |
| `## Public` | optional | static methods and interfaces other pages may use |
| `## Target` | for programs | name the host and its entry points |

Each reserved section appears at most once. A page may be:

- **documentation** — no compile sections; compiles to nothing,
- **library** — Schema (+ Methods / Public), no Construction; emits classes but
  no factory,
- **program** — Schema + Construction (+ optional Import / Public / Target).

Wiki `[[PageName]]` links in ordinary prose are navigation only (live wiki).
They do not import classes. Class reuse is `## Import`. A `[[page]]` at the
**end of a section heading** is compile-time **transclusion** (see below).

The class name `Main` is reserved: the compiler generates `class Main` as the
program entry on Haxe hosts.

Programs with Construction get an automatically derived
`<Root>Assemblage.factory(...)`. Target code and importers call that factory.
Do not invent `gameFactory()`-style helpers in WCHNT source.

---

## Reuse: Import vs transclusion

WCHNT has two deliberate reuse mechanisms. They answer different questions and
must not be confused.

### Transclusion (compile time)

A section heading may end with a wiki link naming another page:

````markdown
## Schema [[myapp]]
## Construction [[myapp]]
## Methods [[myapp]]

## Target
```
%openfl
…
```
````

Before parsing, the compiler **textually replaces** that section’s body with
the matching section from `myapp`. The destination keeps its heading; the body
comes from the source. After replacement, the page compiles normally.

Rules:

- One level only — the source section must not itself be transcluded.
- The named page and section must exist; missing or nested transclusion fails.
- No merge, extend, or concatenate of schemas: pure textual include.
- Any Markdown section can be transcluded, including prose sections.
- A `[[page]]` in ordinary prose remains a navigation link only.

**Use transclusion** when the same Schema / Construction / Methods should run
on several Targets (for example OpenFL and canvas twins) and only the Target
block differs.

### Import / Public (runtime)

`## Import` loads a **sibling assemblage as an opaque object**. The importer
never sees the other page’s Schema internals.

**Publisher** (`## Public`) may list:

1. **Static method bodies** written as `name = { … }` (not `Class::name`).
2. **Bare interface names** (`Shape`) so importers can add local implementers.

The implicit `factory()` of a program assemblage is always available and is
**never** listed in Public. Instance methods are not listed either: once the
importer holds a handle, it may call any method on that handle. The membrane is
about structure (no field peeks, no constructing hidden classes), not about
hiding behaviour on a handle you already own.

**Importer:**

```wchnt
## Import
[[flyingA]] as flying

## Schema
Sky = @Game

## Construction
[:Sky flying.factory()]
```

- `flying` names the imported assemblage class (`GameAssemblage` when the root
  is `Game`).
- `flying.factory(...)` runs the imported Construction and returns the root.
- `flying.make(...)` is legal only if Public defined `make = { … }`.
- Store imported roots only in `@` Schema slots. Call methods on those handles
  (`quest.headline()`). Do not write `[:Adventurer …]` for a foreign class.
- A published interface may be implemented locally with
  `Pentagon : Shape = …` (not inheritance, not `+`). Pass local implementers
  into Public methods that expect that interface.

Examples: `examples/importA.wcn` / `importB.wcn`,
`examples/flyingA.wcn` / `flyingB.wcn`.

**Use Import** when one assemblage should treat another as a black-box
capability (factories, published ops, published interfaces). **Use
transclusion** when you are sharing source text across platform pages of the
*same* assemblage.

---

## Schema

The Schema is one flat map of the object network. Each line defines a class, an
interface (sum), or an enum.

### Composition

```wchnt
Game = PlayArea Ball Paddle/paddle1 Paddle/paddle2
PlayArea = Rect
Rect = Int/x Int/y Int/width Int/height
```

- Components become instance variables of the left-hand class.
- Default field name: type name with a lower-cased first letter
  (`PlayArea` → `playArea`).
- `/name` overrides the field name and is **required** when two components
  share a type.

### Types: builtins, schema classes, host types

Every type name in Schema or Methods falls into exactly one of these buckets.
An unrecognised bare name is **not** silently treated as a host type.

| Kind | Examples | How you write it |
|------|----------|------------------|
| **Builtin primitives** | `Int`, `Float`, `String`, `Bool` | Bare name; closed set; no Schema definition |
| **Schema types** | `Ball`, `Shape`, `Action` | Defined on a Schema line (class, sum, or enum) |
| **Borrowed externals `@`** | `WCHNTGraphics`, `WCHNTMaths`, OpenFL `Graphics` | Explicit `@Type` (Schema field or Methods param) plus Target `%requires` |
| **Platform-constructible `%`** | `Date`, `Regex`, `UUID`, `Path` | Explicit `%Type` plus `Type::CONSTRUCT(...)` in `%requires` |

**Primitives** are the only outsiders that omit `@` / `%`. They are language
scalars supplied by every host and by the CLJC interpreter. They cannot carry
relationship sigils, and they are not identity objects.

**Schema types** are the assemblage. Ordinary / `:` / `$` / `+` components
must name a Schema class (or, for ordinary fields, a primitive). `$` on a sum
interface is rejected — observables are concrete classes.

**`@` externals** are borrowed host values. Use `@` when the host owns
lifecycle or the value is a capability injected once (graphics, console,
maths, imported assemblage root). Free Construction names in `@` slots become
factory parameters. You cannot write `[:Pen …]` into an `@` slot.

**`%` platform-constructibles** are value-like host data born inside the
assemblage. Schema `%Date/dob` requires `Date::CONSTRUCT(...)` in `%requires`.
Construction and Methods write `[:Date "1815-12-10"]`; the compiler emits host
construction (`new Date(...)` on Haxe, a registry ctor on the interpreter).
Free names are **not** allowed in `%` slots. Both `@` and `%` stay opaque:
only methods declared in `%requires` are callable. Prefer `@` for harness
singletons; use `%` for dates, patterns, ids, paths. See
[`platform_constructable.md`](platform_constructable.md).

Related forms that are also language-built, but not Schema primitives:

- **Collections** — `[Player]` and `{String:Int}` (see below); not written as
  bare `Array` / `Map` field types.

There is no `Void` or `Null` in WCHNT. Every method is an expression whose
value is its last statement. Host Target code may use platform `Void` in
`%main` / `%init` signatures; that is outside WCHNT Methods.

### Collections

```wchnt
Team = String/name [Player]/players
School = {String:Discipline}/disciplines
```

- `[Player]` → `Array<Player>`
- `{String:Discipline}` → `Map<String, Discipline>`
- Collection fields need an **explicit** `/name`.

### Sum types (interfaces)

```wchnt
Shape = Circle | Triangle
Circle = Int/radius
Triangle = Int/base Int/height
```

A line of `|`-separated class names defines an interface implemented by those
variants. Do not mix `|` with ordinary composition on the same line. Interface
*method signatures* belong in Methods (empty body after `|`), not in Schema.

On an importing page, attach a new local class to a **published** interface:

```wchnt
Pentagon : Shape = Int/x Int/y Int/side
```

### Enums

```wchnt
Action = "Run" | "Jump" | "Duck" | "Shoot"
```

A line of string literals defines a host enum (Haxe-style).

### Relationship sigils

Sigils attach to a **single class component**, not to `[Array]` or `{Map}`.

| Sigil | Name | Meaning |
|-------|------|---------|
| *(none)* | ordinary | owned by the parent; built alongside it |
| `:` | context-specific | child belongs only to this parent; gets a back-reference |
| `+` | delegate | owned child whose fields and methods are promoted onto the parent |
| `@` | external | borrowed / opaque; lifecycle elsewhere |
| `%` | platform-constructible | host value born via `[:Type …]` + `Type::CONSTRUCT` |
| `$` | reactive | observable; parent subscribes to `update!` |

```wchnt
Car = :Engine String/model
Engine = Int/cylinders
Game = PlayArea Ball $Time
Student = String/id +BasePerson
Chronicle = String/scribe @Quest
Person = String/name %Date/dob
```

#### Ordinary (no sigil)

The parent owns the child; they share a lifecycle. The child’s type stays
generic — `Rect` does not know it lives inside this particular `Game`.

#### Context-specific (`:`)

`Car = :Engine` means an `Engine` exists only inside its `Car`. The compiler
gives `Engine` a back-reference such as `theCar`, so Engine methods can reach
the rest of the assemblage (intra-assemblage transparency). The factory wires
that reference via `setContext` when the assemblage is built.

```wchnt
Engine::carModel = { theCar.model }
```

A context child has **one** parent class. These fail fast:

- `Car = :Engine` and `Truck = :Engine` (two parent classes)
- `Car = :Engine` and `Truck = Engine` (also a component elsewhere)
- `Car = Engine` and `Truck = :Engine` (same conflict the other way)

Same parent twice is fine: `Fleet = :Engine/e1 :Engine/e2`.

#### Delegate (`+`)

`+` is composition with **promotion**, not inheritance. Read
`Student = String/id +BasePerson` as: a Student is an id plus a BasePerson.

```wchnt
[:Student "s17" [:BasePerson "Ada" 36]]
```

Inside Student methods, `name` means `basePerson.name`; `this.greet()` calls
`BasePerson::greet` unless Student defines its own. Write-paths promote too
(`[:Student | name = n]`). Student is **not** a BasePerson for slot typing —
use a sum if a slot should hold either. A method on the delegate that returns
the delegate class must be written again on the wrapper. Schema `+` is
unrelated to expression `+` (addition).

#### External (`@`)

An `@` component is borrowed. Its lifecycle belongs elsewhere.

- As a **Schema field**, Construction fills the slot with a **call** (imported
  handle, `realm.make(...)`) or a **free name** that becomes a factory
  parameter (first appearance, left to right). Never `_` and never an in-place
  `[:Type …]` for that slot. See `examples/factory_args.wcn`.
- As a **Methods parameter** (`@WCHNTGraphics/g`), it is a host type. Declare
  the class and every method WCHNT calls in Target `%requires`.

#### Platform-constructible (`%`)

A `%` component is a host value the assemblage constructs itself.

```wchnt
Person = String/name %Date/dob
```

```wchnt
[:Person "Ada" [:Date "1815-12-10"]]
```

```text
%requires
Date
Date::CONSTRUCT(String) -> Date
Date::year() -> Int
```

- `CONSTRUCT` is **requires metadata only** — never call `dob.CONSTRUCT(...)`.
  WCHNT writes `[:Date …]`; the compiler looks up `CONSTRUCT` and emits host
  construction.
- Free names are forbidden in `%` slots (unlike `@`).
- Example: `examples/platform_date.wcn`.

#### Reactive (`$`)

`Game = PlayArea Ball $Time` means `Time` is **observable** and `Game`
**subscribes**. When `Time.update!()` finishes, it notifies `Game`, which runs
its own `update!()` (no arguments). Reading `time` in `Game::update!` is a
read, not another tick. The `$` slot type must be a schema class.

There is **no automatic** `update!` of ordinary children. Tick a child only by
writing `ball.update!()` or constructing a new value. Want automatic? Give that
class its own `$` observable. Do not also call `ball.update!()` from the parent
or it ticks twice.

### Mailbox (`>`)

A leading `>` on the **class name** marks a mailbox:

```wchnt
>Keys = Bool/left Bool/right Bool/up Bool/down
Game = PlayArea Square $Keys
```

A mailbox is in the assemblage (Schema defines it; Construction births it), but
**Target** may fill it. Target calls `keys.inject(...)` (schema field order),
which writes the fields then runs `Keys::update!`. Methods cannot define or
call `inject`. A mailbox class must define `update!`.

Mailbox (`>`) and observable (`$`) objects are **identity objects**: one
instance for the life of the assemblage, mutated in place, never replaced.

---

## Construction

Construction is the initial data, written as a nested literal inspired by
Clojure’s hiccup. Arguments are **positional** in Schema component order.

```wchnt
[:Game
  [:PlayArea [0 0 800 600]]
  [:Ball 200 150 6 5 16]]
```

Rules:

- Class labels may be omitted where the expected class is known from Schema.
  Bracket structure is never collapsed.
- Arrays: `[:Array/Player [:Player "Ada"] [:Player "Bob"]]`.
- Maps: `{String:Int "Ada": 42}` (empty: `{String:Int}`).
- Sum types must always be tagged: `[:Circle 5]` vs `[:Triangle 4 8]`.
- Intermediate bindings with `=` are allowed; statements end with `.`:

```wchnt
players = [:Array/Player [:Player "Ada"] [:Player "Bob"]].
[:Team "Aces" players]
```

Top-level Construction is fully positional. Write-paths
(`[:Ball | x = nx]`) are Methods-only.

Externals: an `@` slot takes a free name (factory argument) or a call:

```wchnt
Pen = String/ink
Sketch = String/name @Pen
[:Sketch "star" pen]
```

Generated factory: `SketchAssemblage.factory(pen: Pen)`. Target passes
`SketchAssemblage.factory(pen)`.

Platform `%` slots take a host construction instead:

```wchnt
Person = String/name %Date/dob
[:Person "Ada" [:Date "1815-12-10"]]
```

---

## Methods

A method is `ClassName::methodName = { body }`. The body’s value is the return
value. Methods are pure by default. A name ending in `!` is mutating: it
updates the receiver in place and returns that same receiver.

```wchnt
Rect::area = { width * height }

Ball::move = { Rect/bounds |
  [:Ball (x + dx) (y + dy) dx dy rad]
}

Clock::advance! = { Int/delta | [:Clock (t + delta)] }
```

- Arguments go before `|` in the block.
- Parameters may be bare (`px`), schema-typed (`Rect/bounds`), or external
  (`@WCHNTGraphics/g`). A type is required for field access on an argument.
- Return types may be annotated after the block: `-> Shape`. Every method
  returns its last expression; there is no `Void` return type.
- Interface methods use an empty body:
  `Shape::step = { Int/width | } -> Shape`.
- Fields of `this` are bare names; calls on the receiver use `this.move()`.
  Bare `move()` is not allowed.
- Constructor arguments that are expressions need parentheses: `(x + dx)`.

### Statements and lets

A block is statements separated by `.` (full stop). Earlier statements are
immutable `let`-style bindings; the last expression is the result. Rebinding a
field, parameter, or earlier let fails fast.

```wchnt
Ball::move = {
  nx = x + dx.
  ny = y + dy.
  [:Ball nx ny dx dy rad]
}
```

A statement-ending `.` glued to an `Int` literal is parsed as a method call;
bind first when chaining (`n = 4. n.times({ i | … })`).

### Expressions

- Arithmetic: `+ - * / %` (`%` is modulo)
- Bitwise Int: `~`, `&`, `^`, `|`, `<<`, `>>`, `>>>`; hex literals `0x…`
- Comparison: `== != < <= > >=`
- Logic: `and`, `or`, `not`
- Field paths: `ball.x`, `playArea.rect.width` (no spaces around dots)
- Method calls: `this.move()`, `ball.step(playArea.rect)`
- Constructions: `[:Ball 1 2 3 4 5]`, arrays and maps as in Construction
- Lambdas: `{ x | x * 2 }`
- Target diagnostic: `%trace(x)` (must be bound in Target; returns its argument)

Bitwise operators require `Int` operands and use signed 32-bit two’s-complement
results. Hex literals range `0x0` … `0xFFFFFFFF`. Shift counts use their low
five bits. Precedence, tighter to looser: arithmetic, shifts, `&`, `^`, `|`,
comparisons, `not`, `and`, `or`. Parenthesize bitwise-OR in a method block when
it could look like an untyped lambda: `Bits::combine = { (a | b) }`.

### Multi-branch `if`

`if` is an expression. Braces are required. The final `else` is required.
Extra branches are bare `(cond) { … }` clauses before `else`. Clauses are
checked in order; the first match wins. Every branch must join to one result
type.

```wchnt
Ball::absDx = {
  if (dx < 0) { -dx } else { dx }
}

Ball::pick = {
  if (dx < -1) { 1 } (dx < 0) { 2 } (dx == 0) { 3 } else { 4 }
}

scoreBand = if (score >= 100) { "gold" } (score >= 50) { "silver" } else { "bronze" }
```

### `switch`

`switch` compares its scrutinee to each case from top to bottom. Arms use `->`
and a block. `else ->` is required. Arms must join to one result type.

```wchnt
colourName = switch (key)
  1 -> { "red" }
  2 -> { "green" }
  else -> { "white" }
```

Both `if` and `switch` are expressions: they may be a method’s final value or
bound in a let.

### Numbers

```
Int  <:  Float
```

`Int` widens automatically where `Float` is expected. Mixed numeric branches
join to `Float`. Narrowing is never implicit:

```wchnt
x.toInt()   // truncate toward zero; returns Int
x.floor()   // toward negative infinity; returns Int
x.ceil()    // toward positive infinity; returns Int
x.round()   // nearest integer; returns Int
```

These are methods on `Float`, not on `WCHNTMaths`. Use `WCHNTMaths` for host
maths (`rand`, `sin`, `hsv`, …).

### Collections and strings

| Receiver | Methods |
|----------|---------|
| `Array<T>` | `length()`, `get(i)`, `cons`, `head`, `tail`, `map`, `filter`, `fold` |
| `Map<K,V>` | `put`, `get`, `get(k, fallback)`, `exists`, `remove`, `map`, `filter`, `fold` |
| `Int` | `times({ i \| … })`, `str()` |
| `Float` | `toInt`, `floor`, `ceil`, `round`, `str()` |
| `Bool` | `str()` |
| `String` | `length()`, `concat`, `str`, `substring(start, end)`, `tpl` |

```wchnt
players.map({ p | p.name })
players.filter({ p | p.score > 0 })
players.fold(0, { acc, p | acc + p.score })
scores.map({ k, v | v + 1 })
scores.get("Ada")
scores.get("Di", 0)
3.times({ i | i * 2 })
```

Array `get` fails out of range. Map `get(key)` fails on a missing key;
`get(key, fallback)` returns the fallback. Collection transforms copy.
`+` does not concatenate strings — use `concat` or `tpl`.

### Template strings (`tpl`)

```wchnt
"Score {name}: {n}".tpl({String:String "name": name, "n": n.str()})
```

Holes are `{name}`. The map must supply every name; a missing key fails.
Values are strings — convert numbers with `.str()` first.

### Write-paths

Positional construction still builds every field. A **with-construction**
copies an existing object and writes only named paths:

```wchnt
[:Rect | width = (width * 2)]
[:Ball ball | x = nx, y = ny]
[:Game | playArea.rect.width = 800]
```

- No source (`[:Rect | …]`) means `this`.
- Dotted LHS paths rebuild ordinary objects along the path and leave siblings
  alone.
- Arrays and maps cannot be traversed by write-path.
- Methods only. Unknown fields or conflicting paths fail fast.

Examples: `examples/writepaths.wcn`, `live-examples/writepaths.wcn`.

### Immutability and mutating methods (`!`)

Ordinary methods are pure: they return new values. A method whose name ends in
**`!`** rewrites `this` in place and returns it. `update!` is the conventional
reactive method (no arguments); when an observable finishes `update!`, it
notifies subscribers.

```wchnt
Time::update! = { [:Time (t + 1)] }

Game::update! = {
  moved = [:Ball (ball.x + ball.dx) (ball.y + ball.dy) ball.dx ball.dy ball.rad].
  [:Game playArea moved time]
}
```

#### Effect rules

- A pure method may not call a `!` method.
- A mutating method may call pure and mutating methods.
- Every `!` method must reconstruct its own class (every field, schema order)
  and return `this`. `update!` takes no parameters; other `!` methods may.
- A class is mutable when it is the **root** of a program, an **observable**, a
  **subscriber** on a `$` edge, or a **`>` mailbox**. A library with no
  Construction has no root. A `!` method on any other class is rejected.
- Haxe emits `foo!` as `foo_mutates()` so the effect stays visible at the host
  boundary.
- `inject` is Target-only. Methods cannot define or call it.

#### Ordinary fields vs identity slots

| Slot kind | In `Parent::update!` | Behaviour |
|-----------|----------------------|-----------|
| Ordinary (`Ball`, …) | `moved` or `[:Ball …]` | **Replace** the field |
| Observable (`$Time`) | `time` (name) | **Keep** the reference |
| Observable (self) | `[:Time (t + 1)]` | **Patch** fields in place |
| Mailbox (`>Keys`) | `keys` or `[:Keys …]` | Keep reference or patch fields |

Identity rule: types behind `$` and `>` are never replaced by a different
object. Naming the slot passes the reference through; constructing the **same**
class patches fields on the existing instance; constructing a **different**
class in that slot is a compile error.

Ordinary slots are still replaced when you construct a new value. Listing an
unchanged ordinary field by name is a no-op (codegen omits the self-assignment).

There is no implicit percolation into children. Notify is sideways along `$`
edges, not a recursive tree walk.

---

## Standard library

Host objects are supplied by Target under stable harness names. Schema typically
stores them in `@` slots; Construction uses free names; Target passes them into
`factory(...)`.

| Harness name | Class | Typical hosts |
|--------------|-------|---------------|
| `wchntMaths` | `WCHNTMaths` | all |
| `wchntConsole` | `WCHNTConsole` | text / all printing hosts |
| `wchntGraphics` | `WCHNTGraphics` | `%openfl`, `%canvas`, `%form` |
| `wchntInput` | `WCHNTInput` | `%openfl`, `%canvas`, `%form` |
| `wchntForm` | `WCHNTForm` | `%form` |

Signatures are defined in
`src/wchnt_lang/targets/stdlib_signatures.cljc`. Methods that call these APIs
belong in ordinary `## Methods` with `@Type/name` parameters. There is no
separate Target Methods section.

### `%requires`

Every host class and every host method signature that WCHNT (including
imported assemblages) calls must be declared. For `%` types, also declare
construction:

```text
%requires
WCHNTGraphics
WCHNTGraphics::drawCircle(Float,Float,Float) -> WCHNTGraphics
Date
Date::CONSTRUCT(String) -> Date
Date::year() -> Int
```

`Type::CONSTRUCT(Args…) -> Type` is metadata for `[:Type …]` in Construction
or Methods. The return type must equal the class name. `NEW` / `new` are
rejected as ctor names. The compiler does not guess external methods.

### `WCHNTMaths`

```text
rand():Float                 pi():Float
randInt(Int):Int             sin/cos/tan/asin/acos/atan(Float):Float
atan2(Float,Float):Float     abs(Float):Float
floor/ceil/round(Float):Int  sqrt/log/exp(Float):Float
pow/min/max(Float,Float):Float
hsv(Float,Float,Float):Int
```

`randInt(n)` is `0 .. n-1` and fails if `n <= 0`. `hsv` returns packed
`0xRRGGBB`. Language Float conversion (`toInt`, …) does not need a maths
handle. Example: `examples/maths.wcn`.

### `WCHNTConsole`

```text
format(Any):String
print(Any):WCHNTConsole      (fluent)
println(Any):WCHNTConsole    (fluent)
clear():WCHNTConsole         (fluent)
```

`format` pretty-prints objects via construction syntax; bare strings pass
through. Host values print as opaque instance tokens — `@instanceOfPen` for
borrowed `@` slots and `%instanceOfDate` for platform `%` slots — never a
fake `[:Date …]` round-trip (ctor args are not recoverable). Prefer
`wchntConsole.println(...)` from Target — do not call `toConstruction` by hand.

### `WCHNTGraphics`

Fluent drawing surface shared by OpenFL and canvas:

```text
color(Int):Int                         color(Int,Int,Int):Int
color(Int,Int,Int,Int):Int             red/green/blue/alpha(Int):Int
background(Int|Int,Float):WCHNTGraphics
clear():WCHNTGraphics
beginFill(Int|Int,Float):WCHNTGraphics
endFill():WCHNTGraphics
lineStyle() | lineStyle(Float,Int) | lineStyle(Float,Int,Float)
noStroke():WCHNTGraphics
moveTo/lineTo(Float,Float):WCHNTGraphics
drawLine(Float,Float,Float,Float):WCHNTGraphics
drawRect(Float,Float,Float,Float):WCHNTGraphics
drawCircle(Float,Float,Float):WCHNTGraphics
drawEllipse(Float,Float,Float,Float):WCHNTGraphics
fillText(String,Float,Float):WCHNTGraphics
```

`color(x)` is grayscale; three- and four-argument forms pack RGB(A). Host APIs
that look imperative are written as a **single chained call** in Methods;
codegen may unroll host-side void chains onto the fluent receiver. Example:
`examples/shapes_openfl.wcn`, `examples/graphics_openfl.wcn`.

### `WCHNTInput`

```text
mouseX():Int   mouseY():Int
mouseNX():Float mouseNY():Float
mouseDown():Bool
keyDown(String):Bool
keyPresses():Array<String>
attach()/detach()/focus():WCHNTInput   (fluent)
```

`keyDown` is for held controls. `keyPresses` returns and clears the queued
discrete key names since the previous call — inject that array into a `>`
mailbox and fold it in Methods. Methods do not call platform input directly.

### `WCHNTForm`

```text
mount(Any):WCHNTForm
value(String):String
number(String):Float
pollEvents():Array<FormEvent>
graphics(String):WCHNTGraphics
clear():WCHNTForm
```

`%form` mounts a Schema-shaped widget tree (`Form`, `Panel`, `Label`,
`TextInput`, …). See form examples under `examples/` / `live-examples/`.

---

## Target

Target names the outer environment. Schema, Construction, and Methods stay the
same across hosts; only Target changes. The first line of a non-empty Target
must name a host — there is no default.

### Hosts

| Host | Entry | Body language | Notes |
|------|-------|---------------|-------|
| `%haxe` | `%haxe-main` | Haxe | Sys / Node-style one-shot programs. **Intended name.** Today’s examples still say `%terminal` + `%main` until the compiler migration lands (see [`liveterminal.md`](liveterminal.md)). |
| `%cli` | `%init` + `%step(line)` | Haxe (neko) | Interactive text loop + `wchntConsole` |
| `%cli-live` | `%init` + `%step(line)` | JavaScript | Browser/live twin of `%cli` |
| `%openfl` | `%init` + `%step` | Haxe | Windowed Lime/OpenFL |
| `%canvas` | `%init` + `%step` | JavaScript | Live Play page / browser canvas |
| `%form` | `%init` + `%step` | JavaScript | DOM form host + `wchntForm` |
| `%terminal` | thin lifecycle | *(host-owned)* | **Planned:** JVM live interpreter / OS host. Target stays thin; do not embed Clojure in the `.wcn` Target. See [`liveterminal.md`](liveterminal.md). |

`%trace` is the one diagnostic function callable from Methods. Its Target
implementation may use `trace`, `console.log`, `alert`, etc., and must return
its argument.

Target owns loops, frame callbacks, and harness construction. Methods must not
embed platform APIs except via `@` parameters (with `%requires`) or `%trace`.

### Inject-then-tick

For a game with held input and a clock, each frame:

1. **Inject** the full snapshot into every `>` mailbox (schema field order),
   including all-false when nothing is held.
2. **Tick** `$Time` once (`time.update_mutates()` / interpreter equivalent).

Do **not** also put `$Keys` on Game if you tick `$Time`, or Game updates twice
per frame — inject into the mailbox (no `$` on that slot), tick Time, and read
`keys.left` inside `Game::update!`. See `examples/pollution_openfl.wcn`,
`examples/square_openfl.wcn`.

An assemblage may instead use an ordinary root `update!` and assign the
returned root in Target; reactive ticking is conventional, not mandatory.

### CLI hosts

`%cli` / `%cli-live` share `%init` once and `%step(line)` per input line.
Output goes through `wchntConsole`. The host adds no story text. `%cli`
compiles with neko (`Sys.stdin`); `%cli-live` is the live interpreter twin.

---

## A small worked picture

Schema + Construction + Methods for a bouncing ball; Target ticks `$Time`
(`examples/bounce_loop.wcn` is the compiling version):

```wchnt
## Schema

Game = PlayArea Ball $Time
PlayArea = Rect
Rect = Int/x Int/y Int/width Int/height
Ball = Int/x Int/y Int/dx Int/dy Int/rad
Time = Int/t

## Construction

[:Game
  [:PlayArea [0 0 800 600]]
  [:Ball 200 150 6 5 16]
  [:Time 0]]

## Methods

Time::update! = { [:Time (t + 1)] }

Game::update! = {
  moved = [:Ball (ball.x + ball.dx) (ball.y + ball.dy) ball.dx ball.dy ball.rad].
  [:Game playArea moved time]
}
```

Browse `examples/` for Haxe-backed programs and `live-examples/` for
browser/live twins. Philosophy and assemblage motivation: [`intro.md`](intro.md).

---

## Common mistakes

- Confusing **transclusion** (`## Schema [[page]]`) with **Import** (runtime
  opaque assemblage).
- Using `Game::make` in Public — write `make = { … }`.
- Inventing `gameFactory()` — the compiler derives `<Root>Assemblage.factory`.
- Merging imported classes into the local Schema, or constructing / reading
  imported internals.
- Using `this` or calling other methods from Public static methods.
- Calling `inject` from Methods.
- Replacing `$` / `>` identity objects in a mutating method.
- Confusing Schema `+BasePerson` with expression `+`.
- Putting host / Clojure / Haxe glue into Methods instead of Target `@`
  parameters and `%requires`.
- Omitting the final `else` on `if` / `switch`, or mixing branch result types.
- Annotating a method `-> Void` — every method returns its last expression;
  there is no Void or Null in WCHNT.

---

## Style

- when writing an assemblage, put as much of the logic in the wchnt code as possible. The target language code should be as "thin" as possible. A place only for what is genuinely platform specific either because it uses platform specific classes or inbuilt functionality, or because it manipulates and prepares data that only make sense on this target.
