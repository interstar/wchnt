# Methods

The official heading in a `.wcn` file is **`## Methods`**.

We still say “reaction” informally for this part of the language (immutable-ish methods, constructions, expressions). The file section is Methods. We are not committed to a second **Imperative** section; splitting mutating and non-mutating methods may not be how mutation gets managed. Imperative remains in the mainfile order but is unused.

`doc/reaction_phase.md` is an old sketch. Language philosophy lives in `intro.md`. This file is the working description of Methods: what compiles, what we will implement, and what we have decided.

Schema `$` (observable / subscriber) is a different idea. That relationship is still called reactive in the compiler. It is not this section.

---

## Decisions (2026-09-01)

1. **Section name.** `## Methods`. Not Reaction, not Reactive.
2. **Calls on self.** `this.move()`. Bare `move()` is not allowed for now. We may add it as shorthand later.
3. **Argument types.** None in the source. Schema names are often derived from types (`Game = :PlayArea Ball` → `playArea`, `ball`); method arguments cannot use that trick, so they are always explicit names (`px`, `py`). A WCHNT type checker is not v1. The Haxe backend may still infer types for generated parameters; that is not part of the language.
4. **Conditionals.** `if (cond) { … } else { … }` is an expression. Both branches are required. It transpiles to a Haxe `if` expression. `ifTrue` / `ifFalse` are not part of the language.
5. **`$` and `update`.** `update` takes no arguments. When an observable finishes `update`, it calls `update()` on subscribers (sideways notify, not a tree walk). Naming a `$` field in a construction is a read; it does not tick that object again.
6. **No automatic `update` of children.** Ordinary and `:context` children tick only if the parent writes `ball.update()` (or constructs a new child). Want automatic? Give that class its own `$` observable. Do not also call `ball.update()` from the parent or it ticks twice.

---

## What a method is

A method is a named block attached to a class from the schema:

```
ClassName::methodName = { body }
ClassName::methodName = { arg, arg | body }
```

No parentheses on the method name. Braces are required. Arguments, when present, are names only. The value of the block is the value of its last statement. The full stop is the statement separator.

Fields of `this` are bare names (`width`, `dx`). Extra arguments are block parameters (`px`, `py`). Calls on the receiver use `this`.

Construction is the same expression language, narrowed to a single top-level block that builds the initial heap.

---

## What already compiles

Arithmetic, comparisons, `and` / `or` / `not`, returning a construction, and named block args. `%` between values is modulo (`x % width`), same precedence as `*` and `/`. `%name(...)` is still a Target command. Examples: `examples/test_reaction_arithmetic.wcn`, `examples/test_reaction_logic.wcn`.

```
Rect::area = { width * height }

Rect::doubleWidth = { [:Rect x y (width * 2) height] }

Ball::move = { [:Ball (x + dx) (y + dy) dx dy rad] }

Ball::movingRight = { dx > 0 }

Rect::contains = {px, py | (px >= x) and (px <= (x + width)) and (py >= y) and (py <= (y + height))}
```

Constructor arguments that are expressions must be parenthesized, because construction args are juxtaposed and have no commas: `(x + dx)`, not `x + dx`.

Unknown class, unknown name, or an unused parameter fails fast.

---

## Essential remaining pieces

These are what we need before Methods can express a playable step (Pong bounce, then Gbloink!).

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

**Done.** Unknown method or wrong arity fails fast. Blocks as arguments are the next slice.

### 3. Field paths (dots without calls)

We can already name a field of `this`. We cannot yet walk into another object.

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

`else if` is not in yet.

Arrays have `map`, `filter`, and `fold`. The block is a delayed function, evaluated per element. `fold` takes the seed first and the function last (`players.fold(0, { acc, p | … })`), like JS / Clojure / Python `reduce`. Haxe `Array` has no `fold`, so codegen emits `Lambda.fold` and swaps the lambda parameters (`(elem, acc)`).

```
players.map({ p | p.name })
players.filter({ p | p.score > 0 })
players.fold(0, { acc, p | acc + p.score })
```

**Done.** Unknown method or wrong block arity fails fast. `map` / `filter` / `fold` are only defined on arrays.

Strings have `length()` and `concat`. `concat` takes a string or number. Haxe `String.length` is a property, so codegen drops the `()`.

```
name.length()
name.concat(":").concat(score)
```

Arrays also have `length()`, `cons` (prepend), `head`, and `tail`. `head` / `tail` of an empty array fail at runtime. Maps have `put`, `get`, and `remove`. `get` / `remove` of a missing key fail at runtime. Map writes copy the map; Target can later name a persistent or mutating store.

```
players.cons(p)
players.head()
players.tail()
scores.put(n, s)
scores.get("Ada")
scores.remove("Ada")
[:Array/Player]
[:Map/{String:Int}]
name.substring(0, 1)
3.times({ i | i * 2 })
```

`substring(start, end)` is a half-open range. `times` takes a one-argument block; the argument is the index from 0. Array `concat` and index sugar are later.

### 5. `update` (the one mutation) — Done

`update` is the one mutation of object identity. In source it looks like a construction of **the same class**, listing every field in schema order. Codegen installs those fields onto `this`, then notifies if this object is observable, then `return this`. Ordinary methods still `return new Class(...)`.

`update` takes no arguments (`notifySubscribers` calls `update()`). Every subscriber and every observable must define it.

There is **no implicit percolation** into children. `ball.update()` in the picture means: run Ball’s update in place; the slot value is that same Ball.

The first Target is a dumb N-times loop that ticks the root’s `$` component(s). Methods have no loop. See `examples/bounce_loop.wcn`.

```
Time::update = { [:Time (t + 1)] }

Game::update = {
  ndx = this.bounceDx().
  ndy = this.bounceDy().
  moved = [:Ball (ball.x + ndx) (ball.y + ndy) ndx ndy ball.rad].
  [:Game playArea moved time]
}
```

`time` in that list is a read. Time has already ticked; Game does not tick it again.

### 6. Arrays and maps inside methods

Construction already builds arrays and maps. Methods can too, including empty collections. Arrays have `cons`, `head`, and `tail`. Maps have `put`, `get`, and `remove` (copy-on-write for now).

```
Team::count = { players.length() }

Team::withP = {p | players.cons(p) }

Team::captain = { players.head() }

Team::withScore = {n, s | scores.put(n, s) }

Team::adaScore = { scores.get("Ada") }

Team::fresh = { [:Team name [:Array/Player] [:Map/{String:Int}]] }
```

Plus `map` / `filter` / `fold` in §4. Indexing spelling is unset (`players.get(0)` vs `players.at(0)` vs something shorter).

### 7. Target commands in a method — Done as expressions

`%name(...)` is a call to a function defined in Target. It is an **expression**, not a third kind of line. Blocks stay lets plus one result. `%trace(x)` yields `x`.

```
Rect::area = { %trace(width * height) }

ndx = %trace(this.bounceDx()).
[:Game playArea moved time]
```

Unknown `%name` fails until Target binds it. `%main` is Haxe and is the program entry; Methods are not auto-run. See `examples/bounce_loop.wcn` and `examples/test_target_trace.wcn`.

---

## A small picture of the intended language

`examples/bounce_loop.wcn` is the compiling version (PlayArea is Rect, bounce lives on Game, Target ticks `$Time`). A variant with `:PlayArea` and `ball.update()`:

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

Ball::update = {
  hitX = (x < thePlayArea.x) or (x > (thePlayArea.x + thePlayArea.width)).
  ndx = if (hitX) { -dx } else { dx }.
  [:Ball (x + ndx) (y + dy) ndx dy rad]
}

Game::update = {
  [:Game playArea this.ball.update() time]
}
```

(`this.ball.update()` vs `ball.update()` for a field receiver is the same rule as field paths: `ball` is a field of Game, then `.update()`. `this` is for calling a method on the current object when there is no other receiver.)

That is enough, in principle, for a bouncing ball. Paddles, scores, and a Target `step` come after.

---

## Suggested implementation order

Each slice: an `examples/*.wcn` file, tests on Haxe strings, no Haxe compiler in unit tests.

1. **Lets in method bodies.** Done. Last statement is the return. Fail if a name is rebound.
2. **Field paths.** Done. `ball.x`, `playArea.rect.width`, `theCar.model`. Fail if a segment is not a component.
3. **Method calls.** Done. Receiver required (`this.move()`, `ball.move()`, `playArea.rect.area()`). Arguments comma-separated. Fail if the method is not defined on that class or the arity is wrong.
4. **`if` / `else` as an expression, and `map` / `filter` / `fold` on arrays.** Done.
5. **`update` rules.** Done. In-place rewrite of `this`, `$` notify with no args, no child percolation.
6. **Array `concat` and index sugar.** `times`, `get`/`remove`, `head`/`tail`, and `substring` are done.
7. **Target `%` expansion.** Done as expressions plus `%main` Haxe. Unknown `%name` fails. Methods are not auto-run.

Do not add a second grammar. Do not add `for` unless combinators on collections are clearly the wrong shape. Do not add argument types in the source.

---

## Explicitly later

- Whether we ever need a separate Imperative heading.
- Bare `move()` as shorthand for `this.move()`.
- Argument types in WCHNT (and a type checker).
- Automatic `update` of `:context` children (explicitly not this).
- `@external` objects.
- Index syntax sugar.
- `Class::method()` with parentheses in the definition (old `reaction_phase.md`).
