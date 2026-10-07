# Tutorial: build a bouncing ball

This tutorial walks through a complete, runnable WCHNT program — a ball bouncing inside a box.
You can follow along in the **[Play](https://wchnt.com/play/?page=bounce)** page: paste each piece, press **Run**, and watch it move. **This first draft is written by AI. But will shortly be rewritten by a human**

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
Game = PlayArea Ball
PlayArea = Int/width Int/height
Ball = Int/x Int/y Int/dx Int/dy Int/rad
```
````

Read it like this:

- `Game = PlayArea Ball` — a game owns a play area and a ball.
- `PlayArea = Int/width Int/height` — a play area has two integers, `width` and `height`.
- `Ball` has a position (`x`, `y`), a velocity (`dx`, `dy`), and a radius `rad`.

`Int` is a **primitive** supplied by the host platform. Anything that isn't defined in your
Schema is assumed to come from the platform.

> **Field names.** By default a component's field name is its type name with a lower-cased first
> letter: `PlayArea` → `playArea`, `Ball` → `ball`.
> You can override it with `/`. For example, write `Paddle/player` to call the field `paddle1`.

## 3. Construction — the initial heap

Now tell WCHNT what the world looks like at the start. Construction is just a data literal, like
a nested list with the class name first:

````markdown
## Construction

```
[:Game
  [:PlayArea 800 400]
  [:Ball 200 150 6 5 16]
]
```
````

- The outer `[:Game …]` builds a `Game`.
- Its first argument `[:PlayArea 0 0 800 600]` builds a `PlayArea` — the two numbers are its `width` and `height`.
- The second argument `[:Ball 200 150 6 5 16]` positions the ball at (200, 150), moving right
  and down at (6, 5), with radius 16.

Newlines are just spacing. You can keep or drop class labels wherever the compiler can infer
them from the schema.

## 4. Methods — behaviour as expressions

Methods are attached to classes with `ClassName::methodName`. The body is an expression in curly
braces.

We make the ball bounce off the walls in the `Ball::bounced` method which takes a PlayArea (the dimensions if can move in) as an argument. It calculates the `newDx` and `newDy` and then constructs a new Ball with the new position and momentum. In wchnt mutability is constrained to specific situations. In this simple example we show the immutable way to "move" a ball. By creating a new Ball in the new coordinates.

Similarly, the `Game::step` method which advances the state of the `Game` object is also creating a new instance.

> In our early wchnt experiments we are not *too* concerned with performance. But be assured that we do have mutabile classes when we need them. The thinking is that "pure functions" are a good idea by default.

The rest of the methods are to do with drawing the PlayArea and Ball. These take a Graphics object given to us by the platform we are running on. 


````markdown
## Methods

```
Ball::bounced = { PlayArea/pa |
  newDx = if ((x < 0) or (x > pa.width)) { -dx  } else { dx }.
  newDy = if ((y < 0) or (y > pa.height)) { -dy } else { dy }.
  [:Ball (x+newDx) (y+newDy) newDx newDy rad]
}

Game::step = {
  [:Game playArea ball.bounced(playArea)]
}

Ball::draw = { @WCHNTGraphics/g |
  g.beginFill(0xffffff).drawCircle(x, y, rad).endFill()
}

PlayArea::draw = { @WCHNTGraphics/g |
  g.beginFill(0x003300).drawRect(0, 0, width, height).endFill()
}

Game::draw = { @WCHNTGraphics/g |
  drawnPA = playArea.draw(g).
  drawnBal = ball.draw(g).
  g
}
```
````

Things to unpack:

- **Lets.** `newDx = if ((x < 0) or (x > pa.width)) { -dx  } else { dx }.` binds an intermediate value. The full stop `.` ends a statement; the value of the *last* statement is the method's return value.
- **`if` is an expression**, so it returns a value directly. `or` and `and` combine conditions.
- **Construction arguments are spaced, not comma-separated**, so `(ball.x + ndx)` needs parentheses.
- **Typed arguments.** A method parameter is just a name, but you can annotate it with a type when the compiler needs it for field access. More on this in the Guide.
- **Standard Library.** `WCHNTGraphics` is part of the standard library that will be available for wchnt programs targeting a suitable platform. Note that it presents its drawing functions in a *fluent* style meaning we can chain them together with the `.` operator. All methods return something. There is no `Void` return type in wchnt, nor null values.
- **Colours** are packed RGB integers (`2769450` is `0x2a2a2a`, `15921906` is `0xf2f2f2`).

## 5. Target — where it runs

The last layer names the *outer environment* we call the **Target Platform**.

The Target Platform is an explicit thing that the wchnt compiler or environment has to know about and provide access to. And the Target section of the code is where we write the code that interfaces between it and our assemblage. In most languages, such information is often considered an after-thought to be relegated to obscure configuration files. In wchnt, despite recognising that it is

The target platform is selected with an initial `%` selector. In this case we are choosing `%canvas` which is a web-canvas based environment running in the live environment in the browser. 

That's what changes when you move from a terminal to a window to a browser. Keep it thin: build the assemblage, advance it, hand it the graphics surface.



````markdown
## Target

```
%canvas

%init
var game;

function init() {
    game = GameAssemblage.factory();
}

%step
function step() {
    game = game.step();
    wchntGraphics.clear();
    game.draw(wchntGraphics);
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
