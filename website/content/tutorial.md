# Tutorial: build a bouncing ball

This tutorial walks through a complete, runnable WCHNT program — a ball bouncing inside a box.
You can follow along in the **[Play](play/)** page: paste each piece, press **Run**, and watch it
move. **This first draft is written by AI. But will shortly be rewritten by a human**

By the end you'll have seen the layers that make up every WCHNT program: **Schema**,
**Construction**, **Methods**, and **Target**. Optional **Import** /
**Public** sections let one page reuse another; the [Guide](guide.html) covers those.

## 1. The file is markdown

A WCHNT program is an ordinary markdown file. Prose is for humans; the code goes inside fenced
code blocks under a few reserved headings:

```markdown
## Schema
… classes and relationships …

## Construction
… the initial data …

## Methods
… behaviour, including calls to host APIs declared by Target …

## Target
… how the host drives each frame …
```

Order matters, and each section appears at most once. `## Import` (if present) comes first.
Everything else in the file is ignored by the compiler. Wiki `[[PageName]]` links in prose
are navigation only.

## 2. Schema — what exists, and who owns whom

The Schema is the heart of assemblage programming: one flat map of the object network. Add a
`## Schema` section with these three classes:

````markdown
## Schema

```
Game = PlayArea :Ball
PlayArea = Int/x Int/y Int/width Int/height
Ball = Int/x Int/y Int/dx Int/dy Int/rad
```
````

Read it like this:

- `PlayArea = Int/x Int/y Int/width Int/height` — a play area is four integers, named `x`, `y`,
  `width`, `height`.
- `Game = PlayArea :Ball` — a game owns a play area and a ball.
- `Ball` has a position (`x`, `y`), a velocity (`dx`, `dy`), and a radius `rad`.

The `:` in front of `Ball` is a **relationship sigil**. It makes the ball a
*context-dependent component*: the ball belongs to this particular game, and it gets an automatic
back-reference called `theGame` so its methods can reach the rest of the assemblage. We use that
below when the ball bounces off the play area.

`Int` is a **primitive** supplied by the host platform. Anything that isn't defined in your
Schema is assumed to come from the platform.

> **Field names.** By default a component's field name is its type name with a lower-cased first
> letter: `PlayArea` → `playArea`, `Ball` → `ball`. You can override it with `/`: write
> `Paddle/paddle1` to call the field `paddle1`.

## 3. Construction — the initial heap

Now tell WCHNT what the world looks like at the start. Construction is just a data literal, like
a nested list with the class name first:

````markdown
## Construction

```
[:Game
  [:PlayArea 0 0 800 600]
  [:Ball 200 150 6 5 16]]
```
````

- The outer `[:Game …]` builds a `Game`.
- Its first argument `[:PlayArea 0 0 800 600]` builds a `PlayArea` — the four numbers are its
  `x`, `y`, `width`, `height`.
- The second argument `[:Ball 200 150 6 5 16]` positions the ball at (200, 150), moving right
  and down at (6, 5), with radius 16.

Newlines are just spacing. You can keep or drop class labels wherever the compiler can infer
them from the schema.

## 4. Methods — behaviour as expressions

Methods are attached to classes with `ClassName::methodName`. The body is an expression in curly
braces. Let's make the ball bounce off the walls:

````markdown
## Methods

```
Ball::bounceDX = {
  r = theGame.playArea.
  if ((x < r.x) or (x > (r.x + r.width))) { -dx } else { dx }
}

Ball::bounceDY = {
  r = theGame.playArea.
  if ((y < r.y) or (y > (r.y + r.height))) { -dy } else { dy }
}

Game::step = {
  ndx = ball.bounceDX().
  ndy = ball.bounceDY().
  [:Game playArea [:Ball (ball.x + ndx) (ball.y + ndy) ndx ndy ball.rad]]
}
```
````

Things to unpack:

- **Lets.** `r = theGame.playArea.` binds an intermediate value. The full stop `.` ends a
  statement; the value of the *last* statement is the method's return value.
- **Context.** `theGame` is the back-reference `:Ball` gave us — the ball can reach its own game.
- **Paths.** `theGame.playArea.x` walks into other objects. No spaces around the dots.
- **Field methods.** `ball.bounceDX()` calls a method on a field; inside `Ball::bounceDX` the
  ball's own fields are bare names (`x`, `dx`).
- **`if` is an expression**, so it returns a value directly. `or` and `and` combine conditions.
- **Constructor arguments are spaced, not comma-separated**, so `(ball.x + ndx)` needs parentheses.

`Game::step` returns a **new** `Game` — same `playArea`, new `ball`. Ordinary methods are pure:
they return new data rather than mutating. In-place mutation is reserved for **mutable**
classes (the Construction root, `$` observables and their subscribers, and `>` mailboxes)
and is written with a method name ending in `!` — most often `update!`. See the Guide.

> **Typed arguments.** A method parameter is just a name (`px`), but you can annotate it with a
> type when the compiler needs it for field access: `Rect/bounds`. More on this in the Guide.

## 5. Methods — drawing with the host surface

Drawing needs a host graphics object, so the draw methods accept an
`@WCHNTGraphics/g` parameter in the ordinary **Methods** section. Declare the
graphics calls used in the Target's `%requires` subsection. This lets `%canvas`
and `%openfl` share the same draw code.

````markdown
```
Ball::draw = { @WCHNTGraphics/g |
  g.beginFill(15921906).drawCircle(x, y, rad).endFill()
}

Game::draw = { @WCHNTGraphics/g |
  r = playArea.
  bg = g.beginFill(2769450).drawRect(r.x, r.y, r.width, r.height).endFill().
  ball.draw(g)
} -> Void
```
````

Colours are packed RGB integers (`2769450` is `0x2a2a2a`, `15921906` is `0xf2f2f2`).
`Ball::draw` returns the graphics handle so callers can chain; `Game::draw` is
annotated `-> Void` because it is only called for its side effect.

## 6. Target — where it runs

The last layer names the *outer environment*. That's what changes when you move from a terminal
to a window to a browser. Keep it thin: build the assemblage, advance it, hand it the
graphics surface. The **[Play](play/)** page uses the `%canvas` host:

````markdown
## Target

```
%canvas

%init
var assemblage;

function init() {
    assemblage = GameAssemblage.factory();
}

%step
function step() {
    assemblage = assemblage.step();
    wchntGraphics.clear();
    assemblage.draw(wchntGraphics);
}
```
````

The browser harness runs `init` once and `step` every frame.
`GameAssemblage.factory()` builds your assemblage (the name comes from the
Construction root class); `assemblage.step()` advances it by returning a new
`Game` value; `assemblage.draw(wchntGraphics)` paints via Methods.
[`wchntGraphics`](wchntgraphics.html) is the portable drawing surface. Target
code is real JavaScript here — Schema, Construction, and Methods
stay the same across graphics hosts.

This tutorial uses a pure `step` that returns a new root. The root class is
also allowed to define mutating `!` methods (see the Guide). For a reactive
clock, Schema would add `$Time`, both `Time` and `Game` would define
`update!`, and Target would tick `assemblage.time["update!"]()` instead of
assigning `assemblage = assemblage.step()`.

## 7. Run it

Open **[Play](play/)**, then click **New** and give the page a name (e.g. `bounce`) so you get a
fresh page to work in — the editor opens a blank page ready to edit. Paste the full program
above into that page and press **Run**. A grey ball should bounce inside the box. Try changing
the ball's radius or velocity in Construction, or the colours in its draw method.

## 8. More of the language

The bounce program uses ordinary fields and one context-dependent `:` ball. The rest of
the language is in the **[Guide](guide.html)**. A short map:

**Delegation (`+`).** A class can be its extras *plus* an inner object. Fields and
methods of the inner object are promoted. Student is not a BasePerson unless you write
a sum.

```
Student = String/id +BasePerson
[:Student "s17" [:BasePerson "Ada" 36]]
```

**Write-paths.** In Methods, `[:Ball | x = (x + dx)]` copies the other fields from
`this`. Construction stays fully positional.

**Templates.** Strings have `tpl`, which fills `{name}` holes from a map. `+` does not
concatenate — use `.concat` or `tpl`.

```
"You are in {place}.".tpl({String:String "place": room.description})
```

**Import.** A page with `## Public` is a box. Another page writes `## Import` /
`[[thatPage]] as alias`, stores a handle as `@Quest`, and calls only the published
methods (`realm.make(...)`). A published interface can be implemented locally:
`Pentagon : Shape = Int/x …`.

**Reactive `update!` and mailboxes.** `$Time` makes `Time` an observable: its
`update!` notifies subscribers such as `Game::update!`. A class marked `>Keys`
is a mailbox — Target may `inject` a snapshot, then the mailbox’s `update!`
runs. The [Pollution](pollution.html) game uses both. Ordinary classes like
`Ball` stay immutable values unless they are also the root or participate in `$`
/ `>`.

## Where next

The **[Guide](guide.html)** is the full language tour — all five relationship sigils,
Import / Public, write-paths, `tpl`, collections, mutability / `update!`, and the Target hosts.
