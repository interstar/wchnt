# Language guide

This is a tour of the whole WCHNT language, from the file format down to the Target
layer. The **[Tutorial](tutorial.html)** shows these pieces working together; this
page is the reference.
**This first draft is written by AI. But will shortly be rewritten by a human**

## The file format

A WCHNT program is markdown. Code lives in fenced blocks under reserved `##`
headings, in this order:

| Section | Purpose |
|---------|---------|
| `## Import` | (optional, first) bind sibling pages by their Public surface |
| `## Schema` | declare classes, types, and relationships |
| `## Construction` | build the initial object graph |
| `## Methods` | pure-ish behaviour as expressions |
| `## Public` | (optional) list of methods and interfaces other pages may use |
| `## Target Methods` | methods that take platform types (`@Type/name`) |
| `## Target` | name the host and its entry points |

A page may be **documentation** (no compile sections), a **library** (Schema,
no Construction), or a **program** (Schema + Construction). Prose and unrelated
fences are ignored. Wiki `[[PageName]]` links in prose are navigation only —
they do not import classes. Class reuse is `## Import`.

---

## Schema

The schema is a list of lines of the form `ClassName = components`. It is the
single place where the *shape* of the whole assemblage is declared.

### Composition

```wchnt
Game = PlayArea Ball Paddle/paddle1 Paddle/paddle2
PlayArea = Int/x Int/y Int/width Int/height
...
```

- `PlayArea Ball` are **components** (fields) of `Game`.
- Default field name = type name with a lower-cased first letter (`PlayArea` →
  `playArea`).
- `/name` overrides the field name, and is required when a class has two fields
  of the same type.
- `Int`, `Float`, `String`, `Bool` are primitives from the host platform.
- Class name `Main` is reserved.

### Relationship sigils

A sigil on a component changes the *kind* of relationship, not the field's type.
Sigils attach to a single class type (`:Engine`, `$Time`, `@Db`, `+BasePerson`),
not to `[Array]` or `{Map}`.

| Sigil | Name | Meaning |
|-------|------|---------|
| *(none)* | ordinary | owned by the parent; built alongside it |
| `:` | context-specific | child belongs to this parent; it gets a back-reference (`theCar`) |
| `+` | delegate | owned child whose fields and methods are **promoted** onto the parent |
| `@` | external | borrowed from outside — or, across pages, an opaque **handle**. Construction fills it with a call or a free name (a factory argument), never `_` or `[:Type …]` |
| `$` | reactive | observable / subscriber (see [update](#update)) |

```wchnt
Car = :Engine String/model
Engine = Int/cylinders
Game = PlayArea Ball $Time
Student = String/id +BasePerson
Chronicle = String/scribe @Quest
```

`Car = :Engine` gives `Engine` a field pointing back to its `Car` (`theCar`), so
engine methods can read sibling data. `Game = … $Time` means `Time` is
observable and `Game` subscribes to it.

`+` is read as addition: a Student is an **id plus a BasePerson**. Construction
nests the inner object, in schema order:

```wchnt
Student = String/id +BasePerson
[:Student "s17" [:BasePerson "Ada" 36]]
```

In Student methods, `name` means `basePerson.name`. `this.greet()` is
`BasePerson::greet` unless Student defines `greet`. Write-paths promote too:
`[:Student | name = n]`. Student is **not** a BasePerson for slot typing — write
an explicit sum (`Person = BasePerson | Student`) if a slot should hold either.
A method on BasePerson that returns a BasePerson must be written again on
Student. Schema `+` is not Methods `+` (integer addition).

A leading `>` on the **class name** is not a component sigil. `>Keys = Bool/left
…` marks a **mailbox**: Target may `inject` the next field picture, then
`update` runs. See [update](#update).

### Collections

```wchnt
Team = String/name [Player]/players
School = {String:Discipline}/disciplines
```

`[Player]` is an array of players; `{String:Discipline}` is a map keyed by
string.

### Sum types, implementers, and enums

```wchnt
Shape = Circle | Triangle
Circle = Int/radius
Triangle = Int/base Int/height
```

A line that is all `|` defines an **interface** (`Shape`) implemented by the
classes on the right. Interface *methods* are declared in Methods (empty body
after `|`), not in Schema.

On another page that **imports** a published interface, a new local class can
implement it:

```wchnt
Pentagon : Shape = Int/x Int/y Int/side Int/dx
```

That is not inheritance and not `+`. Local sums still list variants with `|`.

```wchnt
BuildType = "Dev" | "Local" | "Deploy"
```

A line of string literals defines a Haxe-style **enum**.

---

## Import and Public

Assemblages are **opaque** unless they have `## Public`. Importing a page
without Public fails. The importer never sees the other page's Schema
internals.

**Publisher** (`importA`, `flyingA`) lists references, not bodies:

```
Quest::make
Quest::headline
Quest::roster
Quest::gold
```

or, to let another page add a new `Shape`:

```
Game::make
Game::addShape
Game::update
Shape
```

The class itself is not published: the importer cannot write `[:Adventurer …]`
or `quest.party.hero`.

**Importer** (`importB`, `flyingB`):

```
## Import
[[importA]] as realm

## Schema
Chronicle = String/scribe @Quest

## Construction
[:Chronicle "Greyhold" realm.make("The Lost Chalice", "Andy", "Dave")]
```

`realm` is a module alias. `realm.make(...)` is a Construction **call** — it
returns an already-wired handle. Store that handle as `@Quest`. Call only
Public methods (`quest.headline()`). Target may still peek at fields; the
membrane is WCHNT source, not generated Haxe.

---

## Construction

Construction is the initial data, written as a nested literal. Argument order
matches Schema component order.

```wchnt
[:Game
  [:PlayArea [0 0 800 600]]
  [:Ball 200 150 6 5 16]]
```

- The first element of a bracket is the class name; the rest are arguments by
  position.
- Labels may be omitted where the compiler can infer them from the schema.
- Arrays: `[:Array/Player [:Player "Ada"] [:Player "Bob"]]`
- Maps: `{String:Int "Ada": 42}` (empty: `{String:Int}`)
- Sum types must be tagged: `[:Circle 5]` vs `[:Triangle 4 8]`.

A construction block may bind intermediate values with `=` before a final
expression:

```wchnt
players = [:Array/Player [:Player "Ada"] [:Player "Bob"]].
[:Team "Aces" players]
```

The top-level Construction section is positional. **Write-paths**
(`[:Ball | x = nx]`) are for Methods.

---

## Methods

A method is `ClassName::methodName = { body }`. The body is an expression; its
value is the return value.

```wchnt
Rect::area = { width * height }

Ball::move = { Rect/bounds |
  [:Ball (x + dx) (y + dy) dx dy rad]
}

Rect::doubleWidth = { [:Rect | width = (width * 2)] }
```

- Arguments go before a `|` in a block.
- Parameters may be bare names (`px`) or typed (`Rect/bounds`) — a type is
  needed for field access.
- Return types may be annotated after the block: `-> Void`, `-> Shape`.
- Interface methods use an empty body: `Shape::step = { Int/width | } -> Shape`.
- External host types are written `@Type/name` and must live in
  `## Target Methods`.
- Calls on self are `this.move()`. Bare `move()` is not allowed.
- Constructor arguments that are expressions need parentheses:
  `(x + dx)`, not `x + dx`.

### Write-paths

`[:Class | field = expr]` copies unspecified fields from `this` (or from a
named source). Dotted paths rebuild ordinary objects along the path:

```wchnt
[:Rect | width = (width * 2)]
[:Ball ball | x = nx, y = ny]
[:Game | playArea.rect.width = 800]
[:Student | name = n]
```

Unknown fields, a field plus a path under it, or a path into an array or map
fail fast.

### Statements and lets

A block is a sequence of statements separated by `.` (a full stop). Only the
last statement is the result; earlier ones are `let`-style bindings and may
not be reassigned.

```wchnt
Rect::doubleWidth = {
  w = width * 2.
  [:Rect x y w height]
}
```

### Expressions

- Arithmetic: `+ - * / %` (`+` is integer addition; `%` is modulo)
- Comparison: `== != < <= > >=`
- Logic: `and`, `or`, `not`
- `if` / `else` is an expression; both branches required (`else if` chains work)
- Field paths: `ball.x`, `playArea.rect.width` (no spaces around dots)
- Method calls: `this.move()`, `ball.step(playArea.rect)`, `a.b.c()`
- Constructing: `[:Ball 1 2 3 4 5]`, arrays and maps as in Construction
- Lambdas: `{ x | x * 2 }`
- Target commands: `%trace(x)` — an expression, bound in Target

### Collections and strings

Arrays have `length()`, `cons`, `head`, `tail`, plus `map`, `filter`, `fold`.
Maps have the same three combinators; the block sees key and value. `map` on a
map keeps the keys.

```wchnt
players.map({ p | p.name })
players.filter({ p | p.score > 0 })
players.fold(0, { acc, p | acc + p.score })
scores.map({ k, v | v + 1 })
scores.filter({ k, v | v > 0 })
scores.fold(0, { acc, k, v | acc + v })
```

Maps also have `put`, `get`, `exists`, `remove` (writes copy the map).
`get(key, fallback)` uses a fallback of the value type when the key is missing.

Ints have `times`: `3.times({ i | i * 2 })`.

Strings have `length()`, `concat`, `str`, `substring(start, end)`, and **`tpl`**.
`+` does not concatenate strings — use `.concat` or a template.

### Template strings (`tpl`)

`tpl` fills `{name}` holes from a `{String:String}` map. A missing name fails.
Extra keys are ignored. Non-string values need `.str()` first.

```wchnt
"You are in {place}.".tpl({String:String "place": room.description})

"Fountain {name} has {jets} jets.".tpl({String:String
  "name": f.name,
  "jets": jets.str()})
```

Hole names are identifiers (`place`, not `2`). Unmatched `{` or `}` fails. If
the template is a string literal, the compiler checks that every hole appears
in the map.

### `update` and reactive dependencies

Ordinary methods are pure and return new objects. **`update`** is the one piece
of mutation: it rewrites `this` in place and — if the object is observable —
notifies its subscribers.

```wchnt
Time::update = { [:Time (t + 1)] }

Game::update = {
  [:Game playArea [:Ball (ball.x + ball.dx) (ball.y + ball.dy) ball.dx ball.dy ball.rad] time]
}
```

`$Time` on `Game` means: when `Time.update()` finishes, `Game.update()` runs
automatically. Identity objects (`$` observables and `>` mailboxes) are mutated
in place, never replaced. Name the slot (`time`, `keys`) to keep the reference;
construct the same class to patch fields. A mailbox class must define `update`.
Methods cannot define or call `inject` — that is Target-only.

---

## Target

Target names the outer environment — the layer that changes when you move
platform.

| Host | Entry points | Notes |
|------|--------------|-------|
| `%terminal` | `%main` | Haxe; `go.sh` compiles and runs it |
| `%cli` | `%init` + `%step(line)` | Haxe / Neko text loop; `wchntConsole` |
| `%cli-live` | `%init` + `%step(line)` | browser transcript; same Methods as `%cli` |
| `%openfl` | `%init` + `%step` | Haxe, windowed (Lime/OpenFL) |
| `%canvas` | `%init` + `%step` | JavaScript; what **[Play](play/)** runs |

Every non-empty Target must name a host first. There is no default.

`%name(...)` helpers bind a function callable from Methods. Drawing uses the
portable [`wchntGraphics`](wchntgraphics.html) object. Text hosts use
`wchntConsole`. The browser harness also exposes `gameFactory()` and `input`.

For games with both a clock and held keys: **inject** the mailbox every frame,
then tick `$Time`. Do not put `$Keys` on Game if you also tick Time, or Game
updates twice. See the [Pollution](pollution.html) page.

---

## Further reading

The working specs live in the source repo (`doc/`). If this site and the code
disagree, the code and its examples win.

- Schema, sigils, Import / Public: `doc/schema.md`, `doc/import.md`
- Methods, `update`, write-paths, `tpl`: `doc/method.md`
- Target hosts: `doc/target.md`
- The live interpreter: `doc/live.md`
