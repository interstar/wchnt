# Methods

The official heading in a `.wcn` file is **`## Methods`**.

Methods may take target-provided external values using **`@Type/name`**, e.g. `@WCHNTGraphics/g`. These methods belong in the ordinary **`## Methods`** section. Declare the external class and any methods WCHNT calls in the target's **`%requires`** block. Example: `examples/shapes_openfl.wcn`.

We still say “reaction” informally for this part of the language (immutable-ish methods, constructions, expressions). The file section is **Methods**. Mutation is handled through `update!` constructions and identity slots, not a separate program phase.

`doc/reaction_phase.md` is an archived early sketch (superseded by this file). Language philosophy lives in `intro.md`. Schema rules for `$` and `>` slots live in `schema.md`. Target tick/inject patterns live in `target.md`.

Schema `$` (observable / subscriber) is a different idea from the Methods section heading. That relationship is still called reactive in the compiler.

---

## Decisions (2026-09-01)

1. **Section name.** `## Methods`. Not Reaction, not Reactive.
2. **Calls on self.** `this.move()`. Bare `move()` is not allowed for now. We may add it as shorthand later.
3. **Argument types.** Parameters may be bare names (`px`), schema types (`Rect/bounds`), or external types (`@Graphics/g`). Return types may be annotated after the block (`-> Void`, `-> Shape`). Inference still covers many cases; unknown names fail fast. A full WCHNT type checker is not v1.
4. **Conditionals.** `if (cond) { … } else { … }` is an expression. Both branches are required. It transpiles to a Haxe `if` expression. `ifTrue` / `ifFalse` are not part of the language. Multi-branch conditionals are written as extra bare clauses before the final `else` (see §4).
5. **`$` and `update!`.** `update!` takes no arguments. When an observable finishes `update!`, it calls `update!()` on subscribers (sideways notify, not a tree walk). Naming a `$` field in a construction is a read; it does not tick that object again.
6. **No automatic `update!` of children.** Ordinary and `:context` children tick only if the parent writes `ball.update!()` (or constructs a new child). Want automatic? Give that class its own `$` observable. Do not also call `ball.update!()` from the parent or it ticks twice.
7. **Identity slots mutate in place.** Mailbox (`>`) and observable (`$`) objects keep one instance for the life of the assemblage. In `update!`, naming the slot keeps the reference; constructing the **same** class patches fields on `this.slot`, never `this.slot = new …`. Wrong class → compile error. See §5 and `schema.md`.

---

## What a method is

A method is a named block attached to a class from the schema:

```
ClassName::methodName = { body }
ClassName::methodName = { arg, arg | body }
ClassName::methodName = { Type/arg | body }
ClassName::methodName = { @HostType/arg | body } -> ReturnType
ClassName::methodName = { Type/arg | } -> ReturnType
```

No parentheses on the method name. Braces are required. Arguments may be names, `Type/name`, or `@Type/name`. The value of the block is the value of its last statement (except `Void` methods — see below). The full stop is the statement separator.

Fields of `this` are bare names (`width`, `dx`). Extra arguments are block parameters. Calls on the receiver use `this`.

Construction is the same expression language, narrowed to a single top-level block that builds the initial heap.

---

## What already compiles

Arithmetic, comparisons, bitwise Int operations, `and` / `or` / `not`, returning a construction, and named block args. `%` between values is modulo (`x % width`), same precedence as `*` and `/`. Bitwise operators are `~`, `&`, `^`, `|`, `<<`, `>>`, and `>>>`; they require Int operands and use signed 32-bit two's-complement results. `%trace(...)` is the Target diagnostic command. Examples: `examples/test_reaction_arithmetic.wcn`, `examples/test_reaction_logic.wcn`.

```
Rect::area = { width * height }

Rect::doubleWidth = { [:Rect | width = (width * 2)] }

Ball::move = { [:Ball | x = (x + dx), y = (y + dy)] }

Ball::movingRight = { dx > 0 }

Rect::contains = {px, py | (px >= x) and (px <= (x + width)) and (py >= y) and (py <= (y + height))}
```

Constructor arguments that are expressions must be parenthesized, because construction args are juxtaposed and have no commas: `(x + dx)`, not `x + dx`.

Unknown class, unknown name, or an unused parameter fails fast.

Typed parameters (`Rect/bounds`) enable field access on args. Interface signatures use an empty body after `|`. See `examples/shapes_openfl.wcn`.

---

## Essential remaining pieces

The v1 Methods surface for a playable step is in. Remaining language work is games on top of it (Pong, Gbloink!), factory `setContext`, and Schema `@` fields — not more expression forms.

The subsections below are the implemented slices (kept as the spec).

### 1. Let-bindings in a method

Same rule as Construction. Bind intermediate names with `=`. They are immutable. The last statement is the result.

```
Ball::move = {
  nx = x + dx.
  ny = y + dy.
  [:Ball nx ny dx dy rad]
}
```

A block that ends on an assignment still yields that assigned value:

```
Rect::area = {
  a = width * height.
  a
}
```

**Done.** Grammar already parsed this. IR/codegen now emits `var nx = ...` then `return`. Rebinding a field, parameter, or earlier let fails fast.

### 2. Calling methods

Receiver required. Parenthesized, comma-separated arguments. Chain with `.`.

```
Game::playAreaSize = { playArea.rect.area() }

Ball::step = { this.move() }

playArea.rect.contains(ball.x, ball.y)
```

Not `move()`, not `self.move()`. `PlayArea = Rect` still means `playArea.rect.area()`, not `playArea.area()`.

**Done.** Unknown method or wrong arity fails fast. Blocks as arguments work for `map` / `filter` / `fold` (see §4).

### 3. Field paths (dots without calls)

We can name a field of `this`, and walk into other objects:

```
Game::inBounds = {
  (ball.x > playArea.rect.x) and (ball.x < (playArea.rect.x + playArea.rect.width))
}
```

With a context parent (`Car = :Engine …` generates `theCar` on Engine):

```
Engine::carModel = { theCar.model }
```

Nested paths follow the schema: `PlayArea = Rect` means `playArea.rect.width`, not `playArea.width`.

Dots in a path have no spaces (`ball.x`). A full stop that separates statements needs whitespace after it (`dx. ny = …`), so the two uses of `.` do not clash.

**Done.** Unknown segments fail fast. Collection types cannot be dotted into.

### 4. `if` and collection combinators

`if` is an expression. Both branches are required. Braces are required. It becomes a Haxe `if` expression.

```
Ball::absDx = {
  if (dx < 0) { -dx } else { dx }
}
```

Multi-branch `if` is supported by stacking bare clauses before the final `else`:

```
Ball::pick = {
  if (dx < -1) { 1 } (dx < 0) { 2 } (dx == 0) { 3 } else { 4 }
}
```

Each extra clause is a parenthesised condition followed by a block, in order; the first match wins. It nests into the same Haxe `if` / `else if` chain, and every branch must have the same type.

Arrays and maps have `map`, `filter`, and `fold`. The block is a delayed function. `fold` takes the seed first (`players.fold(0, { acc, p | … })`), like JS / Clojure / Python `reduce`. On an array the block sees one element. On a map it sees the key and the value; `map` keeps the keys and replaces the values. Haxe `Array` has no `fold`, so codegen emits `Lambda.fold` and swaps the lambda parameters (`(elem, acc)`). Haxe `Map` has none of the three, so codegen emits `WCHNTRuntime.mapMap` / `mapFilter` / `mapFold`.

```
players.map({ p | p.name })
players.filter({ p | p.score > 0 })
players.fold(0, { acc, p | acc + p.score })
scores.map({ k, v | v + 1 })
scores.filter({ k, v | v > 0 })
scores.fold(0, { acc, k, v | acc + v })
```

**Done.** Unknown method or wrong block arity fails fast. `map` / `filter` / `fold` are only defined on arrays and maps. See `examples/combinators.wcn`.

Strings have `length()`, `concat`, `str`, `substring`, and `tpl`. `concat` takes a string or number. `str` is on `String`, `Int`, `Float`, and `Bool` (Haxe `Std.string`). `tpl` fills `{name}` holes from a `{String:String}` map; a missing name fails. Haxe `String.length` is a property, so codegen drops the `()`.

```
name.length()
name.concat(":").concat(score)
```

Arrays also have `length()`, `get(index)`, `cons` (prepend), `head`, and `tail`. `head` / `tail` of an empty array, and `get(index)` out of range, fail at runtime. Maps have `put`, `get`, `exists`, and `remove`. One-argument `get` / `remove` of a missing key fail at runtime. `get(key, fallback)` returns `fallback` (same value type) when the key is absent. `exists` is Bool. Map writes copy the map; Target can later name a persistent or mutating store.

```
players.cons(p)
players.get(0)
players.head()
players.tail()
scores.put(n, s)
scores.get("Ada")
scores.get("Di", 0)
scores.exists("Ada")
scores.remove("Ada")
[:Array/Player]
{String:Int}
name.substring(0, 1)
3.times({ i | i * 2 })
```

`substring(start, end)` is a half-open range. `times` takes a one-argument block; the argument is the index from 0. A statement-ending `.` glued to an `Int` literal is parsed as a method call (`4.n` is wrong); bind first: `n = ((((…))). n.times({ i | … })`. Array `concat` and index sugar are later.

### 5. Mutating methods (`!`) — Done

Methods whose names end in `!` mutate live assemblage state. They look like a construction of **the same class**, listing every field in schema order. Codegen installs those fields onto `this` and returns `this`. Ordinary methods remain pure and return new values.

`update!` is the reactive entry point: it takes no arguments, and an observable calls `update!()` on its subscribers. Every subscriber, every observable, and every `>` mailbox class must define it. Other `!` methods may take arguments, but are still required to reconstruct their receiver and return it. `inject` is reserved: Target fills a mailbox, then `update!` runs. Methods cannot define or call `inject`. See `target.md`.

#### Calling restrictions

An ordinary method is pure: it may read fields, construct replacement values,
and call other ordinary methods, but it may not call a mutating method whose
name ends in `!`. A mutating method may call ordinary methods and other
mutating methods. This gives the compiler a simple effect rule: mutation can
flow outward through a `!` method, but a pure method cannot hide a mutation in
its result.

Every `!` method must belong to a mutable class — the root class of a program,
an observable, a subscriber participating in `$`, or a `>` mailbox — and must
return the receiver's own class. `update!` has no parameters because it is also
the notification target; other `!` methods may accept parameters. In generated
Haxe, `foo!` is emitted as `foo_mutates()` so the effect remains visible at the
host boundary.

There is **no implicit percolation** into children. `ball.update!()` in the picture means: run Ball’s update! in place; the slot value is that same Ball.

#### Ordinary fields vs identity slots

| Slot kind | Schema | In `Parent::update!` | Codegen / interpreter |
|-----------|--------|---------------------|------------------------|
| Ordinary (`Ball`, `Int`, …) | no `$` or `>` | `moved` or `[:Ball …]` | Replace: `this.ball = new Ball(…)` or assign evaluated value |
| Observable (`$Time`) | `$` on parent slot | `time` (name only) | Keep reference: `this.time = this.time` |
| Observable (self) | `$` type’s own `update!` | `[:Time (t + 1)]` | Patch in place: `this.t = this.t + 1` |
| Mailbox (`>Keys`) | `>` class | `keys` or `[:Keys …]` | Keep reference or patch fields: `this.keys.left = …` |

**Identity rule:** Types behind `$` slots and `>` mailbox classes are **identity objects** — one instance from Construction until teardown. They are never replaced by a different object. Subscribers, factory wiring, and Target `inject` all rely on stable references.

In any `update!` construction, for a slot whose type is observable or mailbox:

1. **Name the existing slot** (`time`, `keys`) → pass the reference through; no allocation.
2. **Construct the same class** (`[:Time (t + 1)]`, `[:Keys left right up down]`) → **mutate fields in place** on the existing object. Haxe emits `this.time.t = …` or `this.keys.left = …`; the interpreter merges into the object’s live cell.
3. **Construct a different class** in that slot → **compile error**.

Replacing an identity slot with `new Keys(…)` (even the same class via a fresh construction that codegen would treat as replace) is forbidden when the construction class name does not match — and when it does match, patch-in-place is mandatory.

**Ordinary slots** (`Ball`, `PlayArea`, …) are still **replaced** when a mutating method constructs a new value (`moved = [:Ball …]` → `this.ball = moved`). Listing an unchanged ordinary field by name (`playArea` in `[:Game playArea moved time]`) is a no-op; codegen omits the self-assignment (Haxe rejects `this.playArea = this.playArea`). That omit is not in-place mutation — only `$` and `>` slots patch fields on the existing object.

#### Examples

Target ticks the clock; Game moves on notify (`examples/bounce_loop.wcn`):

```
Time::update! = { [:Time (t + 1)] }

Game::update! = {
  ndx = this.bounceDx().
  ndy = this.bounceDy().
  moved = [:Ball (ball.x + ndx) (ball.y + ndy) ndx ndy ball.rad].
  [:Game playArea moved time]
}
```

- `[:Time (t + 1)]` patches `this.t` on the existing Time instance, then Time notifies subscribers.
- `moved` is a **new** Ball (ordinary slot) — replacement is correct.
- `time` alone is a **read** of the existing Time reference. Time has already ticked; Game does not tick it again.

Mailbox pass-through after Target inject (`examples/square_openfl.wcn`):

```
Keys::update! = { [:Keys left right up down] }
```

Target `inject`s new bools; `Keys::update!` copies the current fields back onto the same `Keys` instance (in place). Game reads `keys.left` in its own `update!`.

Pollution game (`examples/pollution_openfl.wcn`): Game lists `time` and `keys` by name at the end of `Game::update!` — both identity slots, no replacement.

#### Notify contract

When an **observable** finishes its own `update!`, it calls `update!()` on each subscriber with no arguments. That is sideways notify along `$` edges, not a recursive tree walk. Target drives the first tick (see `target.md`); Methods never loop.

### 6. Arrays and maps inside methods

Construction already builds arrays and maps. Methods can too, including empty collections. Arrays have `get(index)`, `cons`, `head`, and `tail`. Maps have `put`, `get`, `exists`, and `remove` (copy-on-write for now). One-argument map `get` fails if the key is missing; `get(key, fallback)` returns `fallback` of the value type. `exists` is Bool.

```
Team::count = { players.length() }

Team::withP = {p | players.cons(p) }

Team::captain = { players.head() }

Team::first = { p = players.get(0). p.name }

Team::withScore = {n, s | scores.put(n, s) }

Team::adaScore = { scores.get("Ada") }

Team::hasAda = { scores.exists("Ada") }

Team::fresh = { [:Team name [:Array/Player] {String:Int}] }
```

Plus `map` / `filter` / `fold` in §4. Array indexing is `players.get(index)`; on a map, `get` is key lookup.

### Write paths (`[:Class | field = expr]`) — Done

Positional `[:Ball x y dx dy rad]` still builds a new value from every field. A **with-construction** copies an existing object and writes only the named paths:

```
[:Rect | width = (width * 2)]
[:Ball ball | x = nx, y = ny]
[:Game | playArea.rect.width = 800]
```

- No source (`[:Rect | …]`) means `this`. The method’s class must be that class; otherwise name the source (`[:Ball ball | x = nx]`).
- Dotted LHS paths rebuild ordinary objects along the path and leave sibling fields alone.
- The form lowers to a full same-class construction, so `update!` identity rules are unchanged: `$` / `>` slots that are not on the path stay the same reference; constructing the same identity class on a path still patches in place.
- Methods only. Construction must still write the whole positional picture.

```
Time::update! = { [:Time | t = (t + 1)] }

Game::update! = { [:Game | ball.x = (ball.x + ball.dx)] }

Game::widen = { [:Game | playArea.rect.width = (playArea.rect.width * 2)] }
```

Unknown fields, a field plus a path under it (`playArea = …, playArea.rect.width = …`), or a path into an array or map fail fast. Do not auto-create missing structure. Deep nest: **`examples/writepaths.wcn`**. Live pair: **`live-examples/writepaths.wcn`** (`%cli-live`).

### 7. Target commands in a method — Done as expressions

`%trace(...)` is the one Target-defined diagnostic call available in Methods. It
is an **expression**, not a third kind of line. Blocks stay lets plus one
result. The Target implementation may print, log, alert, or otherwise signal,
but should return its argument so `%trace(x)` yields `x`. It must be explicitly
defined in the Target section.

```
Rect::area = { %trace(width * height) }

ndx = %trace(this.bounceDx()).
[:Game playArea moved time]
```

`%trace` fails until Target binds it. `%main` is Haxe and is the program entry; Methods are not auto-run. See `examples/bounce_loop.wcn` and `examples/test_target_trace.wcn`.

### 8. Typed parameters, interface signatures, and `@` externs — Done

Schema field names are often derived from types. Method arguments cannot use that trick, so they are always explicit. They may be:

- a name (`px`) — type inferred from use, or fail
- a schema type (`Rect/bounds`, `Shape/s`) — required for field access and for `map` element types
- an **external** type (`@Graphics/g`) — a host type not defined in Schema; Haxe assumes it exists in the namespace (OpenFL preamble imports `openfl.display.Graphics`)

Return types may be annotated with `-> Type` after the block. Interface methods on a sum type are signatures with an empty body:

```
Shape::step = { Rect/bounds | } -> Shape
Shape::draw = { @Graphics/g | } -> Void

Circle::draw = { @Graphics/g |
  g.beginFill(15316448).drawCircle(x, y, radius).endFill()
} -> Void
```

Each implementer must match the interface (name, params, return). Target then calls through the interface (`s.draw(graphics)`) instead of `Std.isOfType` tests. A class on another page implements a **published** interface with `Pentagon : Shape = …` in Schema and matching methods (`examples/flyingB.wcn`).

Methods are still **one expression** (optional `let`s before it). There is no statement list. Host APIs that look imperative (OpenFL `Graphics`) are written as a **single chained call**. OpenFL types those methods as `Void`, so codegen unrolls the chain onto the root receiver:

```haxe
g.beginFill(15316448);
g.drawCircle(this.x, this.y, this.radius);
g.endFill();
```

That is a backend accommodation, not WCHNT supporting imperative Methods. `Graphics/g` without `@` is not registered as external; calls on it fail.

See `examples/shapes_openfl.wcn`. Schema `@` fields (`Circle = … @Graphics`) are still ordinary codegen — use a method parameter, not a stored handle.

---

## A small picture of the intended language

`examples/bounce_loop.wcn` is the compiling version (PlayArea is Rect, bounce lives on Game, Target ticks `$Time`). A variant with `:PlayArea` and `ball.update!()`:

## Schema

```
Game = :PlayArea Ball $Time
PlayArea = Rect
Rect = Int/x Int/y Int/width Int/height
Ball = Int/x Int/y Int/dx Int/dy Int/rad
Time = Int/t
```

## Construction

```
[:Game
  [:PlayArea [0 0 800 600]]
  [:Ball 100 100 1 1 5]
  [:Time 0]
]
```

## Methods

```
Rect::area = { width * height }

Ball::move = {
  [:Ball (x + dx) (y + dy) dx dy rad]
}

Ball::update! = {
  hitX = (x < thePlayArea.x) or (x > (thePlayArea.x + thePlayArea.width)).
  ndx = if (hitX) { -dx } else { dx }.
  [:Ball (x + ndx) (y + dy) ndx dy rad]
}

Game::update! = {
  [:Game playArea this.ball.update!() time]
}
```

(`this.ball.update!()` vs `ball.update!()` for a field receiver is the same rule as field paths: `ball` is a field of Game, then `.update!()`. `this` is for calling a method on the current object when there is no other receiver.)

That is enough, in principle, for a bouncing ball. Paddles, scores, and a Target `step` come after.

---

## Suggested implementation order

Each slice: an `examples/*.wcn` file, tests on Haxe strings, no Haxe compiler in unit tests.

1. **Lets in method bodies.** Done. Last statement is the return. Fail if a name is rebound.
2. **Field paths.** Done. `ball.x`, `playArea.rect.width`, `theCar.model`. Fail if a segment is not a component.
3. **Method calls.** Done. Receiver required (`this.move()`, `ball.move()`, `playArea.rect.area()`). Arguments comma-separated. Fail if the method is not defined on that class or the arity is wrong.
4. **`if` / `else` as an expression, and `map` / `filter` / `fold` on arrays.** Done.
5. **`update!` rules.** Done. In-place rewrite of `this`, identity slots patch not replace, `$` notify with no args, no child percolation.
6. **Array `concat` and index.** `times`, array `get(index)`, map `get`/`remove`, `head`/`tail`, and `substring` are done.
6b. **Write paths.** Done. `[:Class | path = expr]` copies unspecified fields from `this` or a named source.
7. **Target `%trace` expansion.** Done as an expression plus `%main` Haxe. Methods are not auto-run.
8. **Typed params, interface signatures, `@Type/name`.** Done. `shapes_openfl.wcn` is the example. Void host chains unroll in codegen.

Do not add a second grammar. Do not add `for` unless combinators on collections are clearly the wrong shape. Do not add a statement language for Methods (host `Void` chains are unrolled, not a new syntax).

---

## Explicitly later

- Whether Methods ever needs **`do { … }`** for sequential statements (see `doc/development_guideline.md`).
- Bare `move()` as shorthand for `this.move()`.
- A full WCHNT type checker.
- Automatic `update!` of `:context` children (explicitly not this).
- Schema `@` field construction/codegen (distinct from Methods `@Type/name`).
- Index syntax sugar.
- `Class::method()` with parentheses in the definition (old `reaction_phase.md` spelling).

---

## Document history

- **`method.md`** — canonical Methods spec (this file).
- **`reaction.md`** — redirect stub for old links.
- **`reaction_phase.md`** — archived 2026 sketch; do not extend.
