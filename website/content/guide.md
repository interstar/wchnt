# WCHNT programmer's guide

WCHNT (We CAN Have Nice Things) is an object-oriented language for writing
**assemblages**: groups of related objects whose structure, initial data,
behaviour, and connection to a host platform are described together.

A WCHNT program is a Markdown file, normally ending in `.wcn`. Code lives in
fenced blocks under named sections, so a page can contain both explanation and
a runnable program. The live system runs these pages in the browser; the
compiler can also generate Haxe for hosts such as OpenFL.

This is the practical guide to writing current WCHNT programs. The detailed
documents linked at the end contain deeper reference material.

## A WCHNT page

The usual sections are:

```markdown
## Import
## Schema
## Construction
## Methods
## Public
## Target
```

`Import` is optional and must come first when present. `Schema` describes the
object world. `Construction` creates the initial object graph. `Methods`
defines behaviour, including methods that accept host-provided objects declared
in the Target's `%requires` subsection. `Public` publishes interfaces and extra
static entry points. `Target` connects the assemblage to a particular host.

A documentation page may have none of these sections. A reusable page may have
Schema and Methods but no Construction. A runnable program normally has Schema,
Construction, and Target. Each section normally contains a fenced `wchnt`
block; Target sections contain the selected host language. Ordinary prose and
unrelated fences are ignored by the compiler.

A minimal program has this shape:

````markdown
## Schema
```wchnt
Counter = Int/value
```

## Construction
```wchnt
[:Counter 0]
```

## Methods
```wchnt
Counter::increment = { [:Counter | value = (value + 1)] }
```

## Target
```haxe
%terminal
%main {
  var counter = CounterAssemblage.factory();
  wchntConsole.println(counter.value);
}
```
````

The generated `CounterAssemblage.factory()` is explained below. Programmers
normally work with the WCHNT page and do not write the generated class.

## Links, imports, and transclusion

There are three different ways for pages to refer to one another.

### Hyperlinks

In ordinary Markdown prose:

```markdown
See [[physics]] for the physics model.
```

This is a wiki link for readers and editors. It does not make code available to
the compiler.

### Import: runtime reuse

Use `## Import` when one assemblage should use another assemblage as an object:

```markdown
## Import
[[physics]] as physics
```

If the imported page's root class is `World`, the importer can call:

```wchnt
physics.factory()
```

The result is an opaque `World` handle. The importer may call methods on that
handle, such as `world.step()`, but cannot read its fields, construct
`[:World ...]`, or name classes hidden inside the imported page.

An imported page must have a `## Public` section. The `factory` method is
generated automatically for a page with top-level Construction; it is always
available and is not written in Public. Its arguments are the external values
required by Construction.

`Public` can define additional static entry points:

```wchnt
## Public
make = {
  String/name |
  [:Thing name]
}
```

An importer can call `things.make("example")` on the imported assemblage
alias. Public may also list interfaces that another page may implement.
Ordinary instance methods do not need to be listed: once an importer has a
handle, it may call methods on that handle. The handle remains opaque with
respect to fields and construction.

### Transclusion: compile-time sharing

Use transclusion when target-platform pages should share Schema, Construction,
or Methods:

````markdown
## Schema [[myapp]]
## Construction [[myapp]]
## Methods [[myapp]]

## Target

```haxe
%openfl
```
````

Before parsing, each transcluded section is replaced by the matching section
from `myapp`. The destination then supplies a different Target.

Transclusion is not runtime reuse and does not create an imported object. It is a
one-level textual operation. The source page and section must exist, and the
source section must not itself be transcluded. There is no concatenation or
extension operation.

## Schema

Schema declares the shape and relationships of an assemblage:

```wchnt
Game = PlayArea Ball Paddle/leftPaddle Paddle/rightPaddle $Time
PlayArea = Int/x Int/y Int/width Int/height
Ball = Int/x Int/y Int/dx Int/dy Int/radius
Paddle = Int/x Int/y Int/width Int/height
Time = Int/t
```

A component's default field name is its type with a lower-case first letter.
Use `/name` for another name or when a type occurs more than once. Primitive
types include `Int`, `Float`, `String`, and `Bool`. Arrays and maps are
explicit:

```wchnt
Team = String/name [Player]/players
Scores = {String:Int}/scores
```

Interfaces and sum types list alternatives:

```wchnt
Shape = Circle | Rectangle
Circle = Int/radius
Rectangle = Int/width Int/height
Direction = "North" | "South" | "East" | "West"
```

### Relationship sigils

| Syntax | Meaning |
| --- | --- |
| `Child` | ordinary owned component |
| `:Child` | context-dependent component with a back-reference |
| `+Child` | delegated/composed component |
| `@Child` | external or borrowed value |
| `$Child` | reactive identity component |
| `>Keys` | mailbox class, writable by Target |

For example:

```wchnt
Car = :Engine String/model
Student = String/id +Person
Sketch = String/name @Pen
Game = Ball $Time Keys
>Keys = Bool/left Bool/right
```

Ordinary components are built and owned by their parent. A context-dependent
component receives a reference back to its containing object. Delegation
promotes the child's fields and methods but remains composition, not inheritance.
An external is supplied from outside: it may be a platform object, factory
argument, or opaque imported handle. WCHNT code cannot construct it itself.

`$` marks a reactive object with identity which can notify subscribers. A
class beginning with `>` is a mailbox. Target can inject values into it,
after which its `update!` method runs.

## Construction

Construction creates the initial object graph using nested positional values:

```wchnt
[:Game
  [:PlayArea 0 0 800 600]
  [:Ball 200 150 6 5 16]
  [:Paddle 20 250 12 80]
  [:Paddle 768 250 12 80]
  [:Time 0]]
```

The first item names the class and the remaining items follow Schema order.
Use explicit tags for sum types, for example `[:Circle 24]`. Arrays and maps
use their type forms:

```wchnt
[:Array/Player [:Player "Ada"] [:Player "Lin"]]
{String:Int "Ada": 10 "Lin": 12}
```

A Construction block can bind intermediate values. Statements are separated
by a full stop and the final expression is the root:

```wchnt
players = [:Array/Player [:Player "Ada"] [:Player "Lin"]].
[:Team "Aces" players]
```

An external field takes a free name or a call. A free name becomes a factory
argument:

```wchnt
Pen = String/ink
Sketch = String/name @Pen
[:Sketch "star" pen]
```

This produces the conceptual entry point
`SketchAssemblage.factory(pen)`.

## Methods

Methods are named `Class::method` and return their final expression:

```wchnt
Ball::speed = { maths.sqrt((dx * dx) + (dy * dy)) }

Ball::move = {
  [:Ball (x + dx) (y + dy) dx dy radius]
}
```

Arguments come before `|` and may be typed:

```wchnt
Game::addBall = { Ball/b | [:Game | ball = b] }
```

Use `this` for the receiver when calling another method: `this.move()`.
Field access uses names or dotted paths such as `playArea.width`. Methods may
use `if`, arithmetic, strings, arrays, maps, and let-style bindings separated
by full stops:

```wchnt
Ball::nextX = {
  candidate = x + dx.
  if (candidate < 0) { 0 } else { candidate }
}
```

Both branches of an `if` need a compatible type. Numeric types form a small
widening lattice: integers can widen to floats, but narrowing is not automatic.
Negation preserves its operand's numeric type. Use `WCHNTMaths` methods such
as `round`, `floor`, or `ceil` for explicit Float-to-Int conversion.

A write-path returns a copy with selected fields changed:

```wchnt
Ball::move = {
  [:Ball | x = (x + dx), y = (y + dy)]
}
```

Unmentioned fields are preserved. Nested ordinary objects can be updated, for
example `[:Game | playArea.width = newWidth]`. Unknown fields and arbitrary
array or map paths fail fast.

## Mutability and identity

Most WCHNT classes are **immutable values**: a method returns a new object and
the caller decides whether to keep that result. Mutability is not “whatever
happens in `update!`”. The compiler derives a set of **mutable** (identity-bearing)
classes, and only those classes may define methods whose names end in `!`.

A class is mutable when it is any of:

| Kind | How it arises | Role |
| --- | --- | --- |
| **Root** | Outer class of Construction | Stable application object; may use `!` even with no `$` or `>` |
| **Observable** | Type of a `$` slot (e.g. `$Time` → `Time`) | Changes itself via `update!`, then notifies subscribers |
| **Subscriber** | Class that owns a `$` slot (e.g. `Game` with `$Time`) | Receives `update!` when its observables finish updating |
| **Mailbox** | Class marked `>Name` | Target injects a field snapshot; then the mailbox’s `update!` runs |

Everything else — ordinary components, `:` context children, `+` delegates —
stays a replaceable value unless that class is also one of the kinds above.
`@` externals are opaque; WCHNT makes no mutability claim about the host object.

```wchnt
Game = Ball $Time Keys
Ball = Int/x Int/y Int/dx Int/dy Int/rad
Time = Int/t
>Keys = Bool/left Bool/right
```

Here `Time` is mutable (observable), `Game` is mutable (subscriber, and usually
also the Construction root), and `Keys` is mutable (mailbox). `Ball` is an
immutable value: `Game::update!` typically builds a *new* ball and installs it.

A method whose name ends in `!` mutates its receiver in place and must
reconstruct that same class (every schema field). `update!` takes no arguments;
when an observable finishes `update!`, it calls `update!` on each subscriber.
Other `!` methods may take arguments. Pure methods may not call `!` methods;
`!` methods may call both.

```wchnt
Time::update! = {
  [:Time | t = (t + 1)]
}

Game::update! = {
  [:Game | ball.x = (ball.x + ball.dx), ball.y = (ball.y + ball.dy)]
}
```

Identity slots (`$` observables and `>` mailboxes) are never replaced by a
different object: naming the slot keeps the reference; constructing the same
class patches fields on the existing instance. Ordinary slots may be replaced
with new values.

A typical reactive Target does this each frame:

1. Read platform input and inject a complete snapshot into each mailbox.
2. Tick the reactive clock once (`time.update!` / generated `update_mutates`).
3. Draw or present the resulting state.

Generated host code may therefore contain:

```haxe
assemblage.keys.inject(left, right);
assemblage.time.update_mutates();
```

`update_mutates` is the generated host-language name for `update!`. There is no
separate WCHNT method called `update()`.

## Public assemblages and factories

For a page with Construction, WCHNT derives the root class name and generates
an assemblage wrapper. If the root class is `Game`, host code conceptually
calls:

```haxe
var game = GameAssemblage.factory(...);
```

The factory constructs and returns the root object. Its parameters are the
external values required by Construction. Explicit Public methods are
additional static entry points on the wrapper:

```wchnt
## Public
make = {
  String/title |
  [:Quest title]
}
```

An importer calls `realm.make("The beginning")` on the imported alias.
`factory` is automatic and must not be repeated in Public.

## Target and hosts

Target names the outer environment. It owns the event loop, frame callbacks,
platform input, and host objects. Current hosts include:

| Target | Use |
| --- | --- |
| `%canvas` | browser canvas live programs |
| `%openfl` | OpenFL/Haxe programs |
| `%terminal` | terminal programs |
| `%cli` | Haxe command-line programs |
| `%cli-live` | live browser command-line programs |

A frame-driven Target commonly creates the assemblage in `%init` and updates it
from `%step`:

```haxe
%openfl

%init {
  assemblage = GameAssemblage.factory(wchntMaths);
}

%step {
  assemblage.keys.inject(heldLeft, heldRight);
  assemblage.time.update_mutates();
  drawGame(assemblage);
}
```

Keep platform-specific work in Target or methods that use Target-provided types. Schema, Construction,
and ordinary Methods should work independently of Canvas, OpenFL, or another
future host.

## Host objects and standard library

Host objects are supplied through external fields or Target Method parameters.
They have the same programmer-facing API in the live system and Haxe graphics
hosts.

### WCHNTMaths

Typical methods include:

```wchnt
maths.randInt(10)
maths.sin(angle)
maths.cos(angle)
maths.sqrt(value)
maths.round(value)
maths.floor(value)
maths.ceil(value)
maths.hsv(h, s, v)
```

`randInt(n)` produces an integer from `0` through `n - 1` and requires a
positive bound. `hsv` produces a packed colour. Maths is normally declared
as an external and passed to the generated factory.

### WCHNTGraphics

Graphics methods draw on the current host surface:

```wchnt
graphics.background(graphics.color(20, 30, 50)).
graphics.beginFill(graphics.color(255, 80, 40)).
graphics.drawCircle(100, 100, 25).
graphics.endFill()
```

Colour helpers are:

```wchnt
graphics.color(r, g, b)
graphics.color(r, g, b, a)
graphics.color(gray)
graphics.red(colour)
graphics.green(colour)
graphics.blue(colour)
graphics.alpha(colour)
```

Constructed colours are packed ARGB values (`0xAARRGGBB`) with channels from
0 to 255. The one-argument form creates grey. Existing `0xRRGGBB` values
remain valid. Drawing operations use packed alpha when no separate alpha is
supplied. Other graphics methods include `clear`, `lineStyle`, `noStroke`,
`moveTo`, `lineTo`, `drawLine`, `drawRect`, `drawEllipse`, and
`fillText`.

### WCHNTConsole and input

Terminal and CLI Targets receive a console object:

```wchnt
console.println(game.description())
```

Use it rather than platform printing APIs from Methods. Browser and OpenFL
input is read by Target code, which calls a mailbox's generated
`inject(...)` method. Methods read mailbox fields but do not read browser
events or keyboard APIs directly.

## Practical checklist

1. Declare the object graph in Schema.
2. Use Construction to create the initial graph.
3. Put platform-independent behaviour in Methods.
4. Use `@` for values supplied by a host or another assemblage.
5. Use `$` for reactive observables/subscribers and `>` for Target-injected mailboxes.
6. Put in-place mutation only on mutable classes (root, `$` participants, `>`), via `!` methods.
7. Put platform-specific code in Target or methods that use Target-provided types.
8. Use Import for runtime assemblage reuse.
9. Use section transclusion for cross-platform source sharing.
10. Remember that `[[Page]]` by itself is only a navigational hyperlink.

For deeper reference, see [Schema](../doc/schema.md), [Methods](../doc/method.md),
[Target](../doc/target.md), [Import](../doc/import.md),
[Live environment](../doc/live.md), and [Type inference](../doc/type-inference.md).
