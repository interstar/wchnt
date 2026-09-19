# WCHNT Language Guide

WCHNT (We CAN Have Nice Things) is an object-oriented language organised around
**assemblages**: a group of tightly-coupled classes whose structure and
relationships are declared together in one place. Instead of defining classes
one by one and wiring them in imperative code, you write one declarative map of
the object network.

This guide is the up-to-date summary of the language. The precise working specs
are `doc/schema.md`, `doc/method.md`, `doc/target.md`, and `doc/import.md`; the
runnable examples are in `examples/`. If they disagree, the compiler and the
examples win.

---

## The file format

A WCHNT program is a **markdown file** (`.wcn`). Code lives in fenced code
blocks under reserved `##` headings. Prose, hyperlinks, and unrelated fences
are ignored. This is literate programming: the document is the program.

The sections, in order:

| Section | Required | Purpose |
|---------|----------|---------|
| `## Import` | optional, must be first | bind sibling pages by their Public surface |
| `## Schema` | yes (for code) | declare classes, types, and relationships |
| `## Construction` | for programs | build the initial object graph |
| `## Methods` | optional | pure-ish behaviour as expressions |
| `## Public` | optional | list of methods and interfaces other pages may use |
| `## Target` | for programs | name the host and its entry points |

Each section appears at most once. A page may be:

- **documentation** — no compile sections; compiles to nothing,
- **library** — Schema (+ Methods / Public), no Construction; emits Haxe classes
  but no factory,
- **program** — Schema + Construction (+ optional Import / Public / Target).

Wiki `[[PageName]]` links in prose are navigation only — they do not import
classes. Class reuse is `## Import`. The class name `Main` is reserved (the
compiler generates `class Main` as the program entry).

---

## Schema

The Schema is the heart of assemblage programming: one flat map of the object
network. Each line defines a class, an interface, or an enum.

### Composition

```wchnt
Game = PlayArea Ball Paddle/paddle1 Paddle/paddle2
PlayArea = Rect
Rect = Int/x Int/y Int/width Int/height
```

- `PlayArea Ball` are **components** (instance variables) of `Game`.
- The default field name is the type name with a lower-cased first letter:
  `PlayArea` → `playArea`.
- `/name` overrides the field name, and is **required** when a class has two
  components of the same type (the two `Paddle`s).
- `Int`, `Float`, `String`, `Bool` are **primitives** supplied by the host
  platform. Any type not defined in the Schema is assumed to come from the
  platform.

### Collections

```wchnt
Team = String/name [Player]/players
School = {String:Discipline}/disciplines
```

- `[Player]` is an array of players → Haxe `Array<Player>`.
- `{String:Discipline}` is a map keyed by string → Haxe `Map<String, Discipline>`.
- Arrays and maps need an **explicit** field name — there is no sensible
  default derived from `Array<Player>`.

### Sum types (interfaces)

```wchnt
Shape = Circle | Triangle
Circle = Int/radius
Triangle = Int/base Int/height
```

A line that is all `|` defines an **interface** (`Shape`) implemented by the
classes on the right. Do not mix `|` with ordinary composition on the same
line. Interface *methods* are declared in Methods (empty body after `|`), not
in Schema.

### Enums

```wchnt
Action = "Run" | "Jump" | "Duck" | "Shoot"
```

A line of string literals defines a Haxe-style **enum**.

### Relationship sigils

A sigil on a component changes the *kind* of relationship, not the field's
type. Sigils attach to a single class type (`:Engine`, `$Time`, `@Db`,
`+BasePerson`), not to `[Array]` or `{Map}`.

| Sigil | Name | Meaning |
|-------|------|---------|
| *(none)* | ordinary | owned by the parent; built alongside it |
| `:` | context-specific | child belongs to this parent; gets a back-reference |
| `+` | delegate | owned child whose fields and methods are promoted onto the parent |
| `@` | external | borrowed from outside; across pages, an opaque handle |
| `$` | reactive | observable / subscriber (see update!) |

```wchnt
Car = :Engine String/model
Engine = Int/cylinders
Game = PlayArea Ball $Time
Student = String/id +BasePerson
Chronicle = String/scribe @Quest
```

#### Ordinary (no sigil)

The default: the parent owns the child, they are built together, and share a
lifecycle. The child's type stays generic — `Rect` does not know it lives
inside this particular `Game`.

#### Context-specific (`:`)

`Car = :Engine` means an `Engine` exists only inside its `Car`. The compiler
gives `Engine` a back-reference field — `theCar` — so Engine methods can reach
the rest of the assemblage (intra-assemblage transparency):

```wchnt
Engine::carModel = { theCar.model }
```

The factory wires `theCar` (via `setContext`) when the assemblage is built.

#### Delegate (`+`)

`+` is composition with promotion. Read `Student = String/id +BasePerson` as:
a Student is an **id plus a BasePerson**. Construction nests the inner object,
in schema order:

```wchnt
Student = String/id +BasePerson
[:Student "s17" [:BasePerson "Ada" 36]]
```

Inside Student methods, `name` and `age` mean `basePerson.name` and
`basePerson.age`; `this.greet()` calls `BasePerson::greet` unless Student
defines its own `greet`. Write-paths promote too (`[:Student | name = n]`).

Student is **not** a BasePerson for slot typing — write a sum
(`Person = BasePerson | Student`) if a slot should hold either. A method on the
delegate that returns the delegate class must be written again on the wrapper.
`+` here is a Schema sigil; in Methods `+` is integer addition.

#### External (`@`)

An `@` component is **borrowed**: its lifecycle belongs elsewhere, and it is
passed in at construction rather than built by this assemblage.

- As a **Schema field** (`Chronicle = String/scribe @Quest`), the Construction
  slot is filled either by a **call** (an imported handle, `realm.make(...)`)
  or by a **free name**, which becomes a parameter of the generated factory
  (first appearance, left to right). It is never `_` and never an in-place
  `[:Type …]` construction. See `examples/factory_args.wcn`.
- As a **Methods parameter** (`@Graphics/g`), it is a host type not defined in
  Schema. Put these methods in `## Methods`; the target's `%requires`
  declarations provide their external signatures. See
  `examples/shapes_openfl.wcn`.

#### Reactive (`$`)

`Game = PlayArea Ball $Time` declares that `Time` is **observable** and `Game`
**subscribes** to it. When `Time.update!()` finishes, it notifies `Game`, which
runs its own `update!()` (no arguments). Reading `time` in `Game::update!` is a
read, not another tick. The `$` slot type must be a schema class.

### Mailbox (`>`)

A leading `>` on the **class name** (not a component sigil) marks a **mailbox**:

```wchnt
>Keys = Bool/left Bool/right Bool/up Bool/down
Game = PlayArea Square $Keys
```

A mailbox is in the assemblage (Schema defines it, Construction births it), but
the **Target** is allowed to fill it. Target calls `keys.inject(...)` (schema
field order), which writes the fields then runs `Keys::update!`. Methods cannot
define or call `inject`. A mailbox class must define `update!`.

Mailbox (`>`) and observable (`$`) objects are **identity objects**: they are
mutated in place and never replaced.

---

## Construction

Construction is the initial data, written as a nested literal inspired by
Clojure's hiccup. The first element of a bracket is the class name; the rest
are arguments **by position** (schema component order).

```wchnt
[:Game
  [:PlayArea [0 0 800 600]]
  [:Ball 200 150 6 5 16]]
```

- Class labels may be omitted where the compiler can infer them from the
  schema (`[:PlayArea [0 0 800 600]]` needs no `:Rect` on the inner list).
  Bracket structure is never collapsed.
- Arrays: `[:Array/Player [:Player "Ada"] [:Player "Bob"]]`.
- Maps: `{String:Int "Ada": 42}` (empty: `{String:Int}`).
- Sum types must always be tagged: `[:Circle 5]` vs `[:Triangle 4 8]`.
- A construction block may bind intermediate values with `=` before the final
  expression; statements are separated by a full stop `.`:

```wchnt
players = [:Array/Player [:Player "Ada"] [:Player "Bob"]].
[:Team "Aces" players]
```

The top-level Construction section is fully **positional**; write-paths
(`[:Ball | x = nx]`) are Methods-only.

Externals are the exception to "build everything here": an `@` slot takes a
free name (a factory argument) or a call. Example — `Pen` comes from the host:

```wchnt
Pen = String/ink
Sketch = String/name @Pen
[:Sketch "star" pen]
```

`pen` is a free name, so the generated factory is `SketchAssemblage.factory(pen: Pen)`.
Target passes it: `SketchAssemblage.factory(pen)`.

---

## Methods

A method is `ClassName::methodName = { body }`. The body is an expression; its
value is the return value. Methods are pure by default. A method whose name
ends in `!` is a mutating method: it updates its receiver in place and returns
that same receiver.

```wchnt
Rect::area = { width * height }

Ball::move = { Rect/bounds |
  [:Ball (x + dx) (y + dy) dx dy rad]
}

Clock::advance! = { Int/delta | [:Clock (t + delta)] }
```

- Arguments go before a `|` in a block.
- Parameters may be bare names (`px`) or typed (`Rect/bounds`) — a type is
  needed for field access. Target-provided external types are `@Type/name`;
  their class and method signatures are declared in Target `%requires`.
- Return types may be annotated after the block: `-> Void`, `-> Shape`.
- Interface methods use an empty body: `Shape::step = { Int/width | } -> Shape`.
- Fields of `this` are bare names (`width`, `dx`); calls on the receiver use
  `this.move()`. Bare `move()` is not allowed.
- Constructor arguments that are expressions need parentheses:
  `(x + dx)`, not `x + dx`.

### Statements and lets

A block is a sequence of statements separated by `.` (a full stop). Earlier
statements are `let`-style bindings (immutable); the last is the result.

```wchnt
Ball::move = {
  nx = x + dx.
  ny = y + dy.
  [:Ball nx ny dx dy rad]
}
```

### Expressions

- Arithmetic: `+ - * / %` (`%` is modulo)
- Comparison: `== != < <= > >=`
- Logic: `and`, `or`, `not`
- `if` / `else` is an expression; both branches are required (`else if` chains work)
- Field paths: `ball.x`, `playArea.rect.width` (no spaces around dots)
- Method calls: `this.move()`, `ball.step(playArea.rect)`, `a.b.c()`
- Constructing: `[:Ball 1 2 3 4 5]`, arrays and maps as in Construction
- Lambdas: `{ x | x * 2 }`
- Target commands: `%trace(x)` — an expression, bound in Target

### Collections and strings

Arrays have `length()`, `get(index)`, `cons` (prepend), `head`, `tail`, plus the
combinators `map`, `filter`, `fold`. Maps have the same three combinators (the
block sees key and value; `map` keeps the keys) plus `put`, `get`, `exists`,
`remove`.

```wchnt
players.map({ p | p.name })
players.filter({ p | p.score > 0 })
players.fold(0, { acc, p | acc + p.score })
players.get(0)
scores.map({ k, v | v + 1 })
scores.get("Ada")
scores.get("Di", 0)
```

On an array, `get(index)` returns the element and fails on an out-of-range
index. On a map, `get(key)` fails on a missing key, and `get(key, fallback)`
returns the fallback instead.

Ints have `times`: `3.times({ i | i * 2 })`.

### Numbers and explicit conversion

WCHNT's numeric lattice is:

```
Int  <:  Float
```

An `Int` widens automatically where a `Float` is expected. The join of mixed
numeric expressions is `Float`, so an `if` with one `Int` branch and one
`Float` branch returns `Float`. Narrowing never happens implicitly.

Float values provide explicit conversion and rounding methods:

```wchnt
x.toInt()   // truncate toward zero; returns Int
x.floor()   // toward negative infinity; returns Int
x.ceil()    // toward positive infinity; returns Int
x.round()   // nearest integer; returns Int
```

These are built-in methods on `Float`, not methods on `WCHNTMaths`. Use
`WCHNTMaths` for host maths such as `rand`, `randInt`, trigonometry, `sqrt`,
`pow`, and `hsv`.

Strings have `length()`, `concat`, `str`, `substring(start, end)`, and **`tpl`**.
`+` does **not** concatenate strings — use `.concat` or a template.

### Template strings (`tpl`)

`tpl` fills `{name}` holes from a `{String:String}` map. A missing name fails;
non-string values need `.str()` first.

```wchnt
"You are in {place}.".tpl({String:String "place": room.description})
```

### Write-paths

`[:Class | field = expr]` copies an existing object and writes only the named
paths. No source means `this`; otherwise name the source (`[:Ball ball | …]`).
Dotted paths rebuild ordinary objects along the path and leave siblings alone.

```wchnt
[:Rect | width = (width * 2)]
[:Ball ball | x = nx, y = ny]
[:Game | playArea.rect.width = 800]
[:Student | name = n]
```

Unknown fields, a field plus a path under it, or a path into an array or map
fail fast.

### Mutating methods and reactive dependencies

Ordinary methods are pure and return new objects. A method whose name ends in
**`!`** rewrites `this` in place and returns it. `update!` is the conventional
reactive method: if the object is observable, it also notifies its subscribers.

```wchnt
Time::update! = { [:Time (t + 1)] }

Game::update! = {
  moved = [:Ball (ball.x + ball.dx) (ball.y + ball.dy) ball.dx ball.dy ball.rad].
  [:Game playArea moved time]
}
```

`$Time` on `Game` means: when `Time.update!()` finishes, `Game.update!()` runs
automatically (sideways notify, not a tree walk). Ordinary children do **not**
update! automatically — a parent ticks a child by writing `ball.update!()` or
constructing a new value.

Identity objects (`$` observables and `>` mailboxes) mutate in place, never
replaced. In any `update!` construction, naming the slot (`time`, `keys`) keeps
the reference; constructing the same class (`[:Time (t + 1)]`, `[:Keys …]`)
patches its fields on the existing instance; constructing a different class in
that slot is a compile error.

#### Method effect rules

- A pure method may read fields, construct values, call pure methods, and use
  permitted host operations. It may not call a mutating `!` method.
- A mutating method may call pure methods and other mutating methods.
- Every mutating method must reconstruct its own class, listing every field in
  schema order, and returns `this`. `update!` is the special zero-argument
  reactive method; other `!` methods may take arguments.
- A class is mutable when it is the root class of a program, an observable, a
  subscriber participating in a `$` relationship, or a `>` mailbox class. A
  library with no Construction section has no root class. A `!` method on any
  other class is rejected.
- Haxe exposes the effect visibly by translating `foo!` to `foo_mutates()`.

### Target commands

`%name(...)` is a call to a function defined in Target, usable as an
expression. `%trace(x)` yields `x`. Unknown `%name` fails until Target binds it.

---

## Target

Target names the outer environment — the layer that changes when you move
platform. Schema, Construction, and Methods stay identical across hosts; only
Target changes.

| Host | Entry points | Notes |
|------|--------------|-------|
| `%terminal` | `%main` | Haxe; `go.sh` compiles and runs it |
| `%cli` | `%init` + `%step(line)` | Haxe / Neko text loop; `wchntConsole` |
| `%cli-live` | `%init` + `%step(line)` | browser transcript; same Methods as `%cli` |
| `%openfl` | `%init` + `%step` | Haxe, windowed (Lime/OpenFL) |
| `%canvas` | `%init` + `%step` | JavaScript; what the live Play page runs |

The first line of a non-empty `## Target` must name a host. There is no
default.

- **`%name` helpers** bind functions callable from Methods (e.g. `%trace`).
- **`wchntGraphics`** is the portable drawing surface (`clear`, `beginFill`,
  `drawRect`, `drawCircle`, `endFill`, `lineStyle`, `moveTo`, `lineTo`, …).
- **`wchntConsole`** is the text output object (`print` / `println`).
- **`wchntMaths`** is the maths handle (`randInt`, `sin`, `cos`, `hsv`, …),
  stored in an `@` slot and passed into the factory.

### Inject-then-tick

For a game needing both held input and a clock, Target follows a fixed order
each frame:

1. **Inject** the full snapshot into every `>` mailbox (schema field order),
   including all-false when nothing is held.
2. **Tick** `$Time` once with `time.update_mutates()`.

Do **not** put `$Keys` on Game if you also tick `$Time`, or Game updates twice
per frame — inject into the mailbox (no `$` on that slot), then tick Time, and
read `keys.left` etc. inside `Game::update!`. See `examples/pollution_openfl.wcn`.

---

## Import and Public

Assemblages are **opaque** unless they have `## Public`. Importing a page
without Public fails. The importer never sees the other page's Schema
internals.

**Publisher** writes static method bodies and bare interface names directly in
Public:

```
make = { ... }
addShape = { ... }
describe = { ... }
Shape
```

The implicit `factory()` is always available on a program assemblage and is not
listed in Public. The class itself is not published: the importer cannot write
`[:Adventurer …]` or read `quest.party.hero`. A bare interface name (`Shape`)
lets another page add a new implementer.

**Importer**:

```
## Import
[[importA]] as realm

## Schema
Chronicle = String/scribe @Quest

## Construction
[:Chronicle "Greyhold" realm.make("The Lost Chalice", "Andy", "Dave")]
```

`realm` names the imported assemblage class. `realm.factory(...)` is the
implicit Construction **call** for the root object; `realm.make(...)` is an
explicit static method only when the publisher wrote `make = { ... }` in
Public. Both return already-wired values. Store imported root handles only in
`@` slots, and call methods on those handles (`quest.headline()`). A published
interface may be implemented locally with `Pentagon : Shape = …` (not
inheritance, not `+`), and a local implementer may be passed into a Public
method. See `examples/importA.wcn` / `importB.wcn` and
`examples/flyingA.wcn` / `flyingB.wcn`.

---

## Where the detail lives

- Schema, sigils, Import / Public: `doc/schema.md`, `doc/import.md`
- Methods, `update!`, write-paths, `tpl`: `doc/method.md`
- Target hosts, inject-then-tick: `doc/target.md`
- The live interpreter: `doc/live.md`
- Runnable programs: `examples/` (and `live-examples/` for the browser)
