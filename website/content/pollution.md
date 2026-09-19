# Pollution Game

A simple arcade game. As you move your yellow ball around with the arrow keys, you throw off red balls of waste. Try to survive for as long as possible without hitting any of it.

Try it here: [Play](play/?page=pollution)

## Schema

This schema defines all the classes used in the game.

### Notes

* `>Keys` is a **mailbox**: Target injects the held-arrow snapshot each frame.
* `$Time` makes `Time` an **observable** and `Game` a **subscriber**. When Time finishes
  `update!`, Game’s `update!` runs automatically.
* `[Pollutant]/pollutants` declares an array. Array and map fields need an explicit name.
* Ordinary classes (`Player`, `Pollutant`, `Rect`, …) stay **immutable values**. Only the root,
  `$` participants, and `>` mailboxes may use `!` methods.

```
>Keys = Bool/left Bool/right Bool/up Bool/down
Game = PlayArea Player [Pollutant]/pollutants Int/score Int/spawnTier Int/spawnEvery $Time Keys
PlayArea = Rect
Rect = Int/x Int/y Int/width Int/height
Player = Int/x Int/y Int/dx Int/dy Int/rad
Pollutant = Int/x Int/y Int/dx Int/dy Int/rad
Time = Int/t
```

## Construction

The initial conditions of the entire assemblage are declared here using *hiccup* notation.

### Notes

* `[:Player 400 300 5 0 20]` maps values to fields in schema order.
* `[:PlayArea [0 0 800 600]]` drops the unambiguous `:Rect` label on the inner object.
* `[:Array/Pollutant]` is an empty list.
* `Keys` is still constructed here even though Target will overwrite its fields via `inject`.

```
[:Game
  [:PlayArea [0 0 800 600]]
  [:Player 400 300 5 0 20]
  [:Array/Pollutant]
  0
  0
  400
  [:Time 0]
  [:Keys false false false false]
]
```

## Methods

Platform-independent behaviour lives here.

### Notes

* Methods are names bound to code blocks: `{ parameters | expression }`.
* Ordinary methods return new values. Mutating methods end in `!` and rewrite `this` in place.
* `Time::update!` is ticked from Target. It looks like a construction, but installs fields on
  the existing `Time` and then notifies `Game`.
* `Keys::update!` is a pass-through after inject: the mailbox keeps identity; fields were just
  written by Target.
* Target injects keys every frame, then ticks `$Time`, so `Game::update!` runs once per frame.
* Statements in a block are separated by `.`; the last expression is the result.
* `if` is an expression. Arrays have `map`, `fold`, and `cons`.

```
Time::update! = { [:Time (t + 1)] }

Keys::update! = { [:Keys left right up down] }

Player::speed = {
  ax = if (dx < 0) { -dx } else { dx }.
  ay = if (dy < 0) { -dy } else { dy }.
  if (ay > ax) { ay } else { ax }
}

Player::steer = { Int/udx, Int/udy |
  if ((udx == 0) and (udy == 0)) { [:Player x y dx dy rad] } else {
    sp = this.speed().
    [:Player x y (udx * sp) (udy * sp) rad]
  }
}

Player::move = { Rect/bounds |
  nx = (x + dx).
  ny = (y + dy).
  ndx = if ((nx < 5) or (nx > (bounds.width - 5))) { -dx } else { dx }.
  ndy = if ((ny < 5) or (ny > (bounds.height - 5))) { -dy } else { dy }.
  fx = if ((nx < 5) or (nx > (bounds.width - 5))) { x } else { nx }.
  fy = if ((ny < 5) or (ny > (bounds.height - 5))) { y } else { ny }.
  [:Player fx fy ndx ndy rad]
}

Player::collides = { Pollutant/o |
  vx = (x - o.x).
  vy = (y - o.y).
  d2 = ((vx * vx) + (vy * vy)).
  sumR = (rad + o.rad).
  d2 < (sumR * sumR)
}

Pollutant::move = { Rect/bounds |
  w = bounds.width.
  h = bounds.height.
  nx = (x + dx).
  ny = (y + dy).
  wx = if (nx > w) { 0 } else { if (nx < 0) { w } else { nx } }.
  wy = if (ny > h) { 0 } else { if (ny < 0) { h } else { ny } }.
  [:Pollutant wx wy dx dy rad]
}

Game::makePollutant = { Player/p |
  shift = ((p.rad * 12) / 10).
  prad = ((p.rad * 7) / 10).
  sx = (p.x - (p.dx * shift)).
  sy = (p.y - (p.dy * shift)).
  [:Pollutant sx sy (-p.dx) (-p.dy) prad]
}

Game::update! = {
  r = playArea.rect.
  udx = (if (keys.right) { 1 } else { 0 }) + (if (keys.left) { -1 } else { 0 }).
  udy = (if (keys.down) { 1 } else { 0 }) + (if (keys.up) { -1 } else { 0 }).
  steered = if ((udx == 0) and (udy == 0)) { player } else { player.steer(udx, udy) }.
  movedP = steered.move(r).
  movedObs = pollutants.map({ Pollutant/p | p.move(r) }).
  hit = movedObs.fold(false, { acc, o | if (acc) { true } else { movedP.collides(o) } }).
  spawning = ((time.t % spawnEvery) == 0).
  nextP = if (hit) {
    [:Player (r.width / 2) (r.height / 2) ((r.width / 40) / 4) 0 (r.width / 40)]
  } else {
    movedP
  }.
  nextObs = if (hit) {
    [:Array/Pollutant]
  } else if (spawning) {
    movedObs.cons(this.makePollutant(movedP))
  } else {
    movedObs
  }.
  nextScore = if (hit) { 0 } else if (spawning) { (score + spawnTier) } else { score }.
  nextTier = if (hit) { 0 } else if (spawning) { (spawnTier + 1) } else { spawnTier }.
  [:Game playArea nextP nextObs nextScore nextTier spawnEvery time keys]
}
```

## Drawing methods

Drawing needs a host graphics surface, so draw methods accept `@WCHNTGraphics/g` in the ordinary
Methods section. Declare the graphics calls used in Target's `%requires`. These methods stay shareable
between `%canvas` and `%openfl`. Draw helpers return the graphics handle so collections can
`fold` them, matching the lander / shapes style.

```
Player::draw = { @WCHNTGraphics/g |
  g.beginFill(13154404).drawCircle(x, y, rad).endFill()
}

Pollutant::draw = { @WCHNTGraphics/g |
  g.beginFill(16737764).drawCircle(x, y, rad).endFill()
}

Game::draw = { @WCHNTGraphics/g |
  r = playArea.rect.
  bg = g.beginFill(0).drawRect(0, 0, r.width, r.height).endFill().
  border = g.lineStyle(2, 65280).drawRect(r.x, r.y, r.width, r.height).noStroke().
  afterObs = pollutants.fold(border, { acc, Pollutant/o | o.draw(acc) }).
  player.draw(afterObs).beginFill(13154404).fillText("Score: {score}".tpl({String:String "score": score.str()}), 12, 24)
} -> Void
```

Colours are packed RGB integers (`13154404` is `0xc8c864`, `16737764` is `0xff6464`,
`65280` is `0x00ff00`).

## Target

The host only wires input, ticks the clock, and asks the assemblage to draw.

```
%canvas

%init
var assemblage;

function init() {
    assemblage = GameAssemblage.factory();
}

%step
function step() {
    var k = input.keys;
    assemblage.keys.inject(!!k["ArrowLeft"], !!k["ArrowRight"], !!k["ArrowUp"], !!k["ArrowDown"]);
    assemblage.time["update!"]();
    wchntGraphics.clear();
    assemblage.draw(wchntGraphics);
}
```

Click the play area after Run so arrow keys reach the canvas.
