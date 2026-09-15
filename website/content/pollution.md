# Pollution Game

A simple arcade game. As you move your yellow ball around with the arrow keys, you throw off red balls of waste. Try to survive for as long as possible without hitting any of it.

Try it here : [Play](play/)


## Schema

This schema defines all the classes used in the game. 

### Notes
* `Keys` is a "mailbox" object, whose values are set by the external harness.
* The `Game` class declares itself to be reactively dependent on `Time`, so the Game's `update` is automatically called whenever the Time object is updated.
* `[Pollutant]/pollutants` uses square bracket notation that declares an array (or list). Arrays (and maps) must always be given an explicit name. 


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

The initial conditions of entire assemblage is declared here using *hiccup* notation.

### Notes
* `[:Player 400 300 5 0 20]` is a simple object construction with the values mapped to fields in the same order as they are declared in the schema.
* `[:PlayArea [0 0 800 600]]` uses a convenient shorthand. PlayArea contains a Rect which actually hold's the PlayArea's dimensions. But because it is unambiguous in this case, we can skip the `:Rect` label at the beginning of the inner list.
* `[:Array/Pollutant]` is an empty list in this case.
* Even though `Keys` is intended to have its values injected by the external harness, it's still a normal component of Game.

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

This is where we write the behaviour of the assemblage

Notes :
* Methods are names bound to code blocks. A code block has the syntax { paramaters | expression}.
* Almost all methods are expressions that return a new value which is either a primitive or an object. To construct a new return object we use the same syntax as the Construction phase of the program.
* `update` is a special method. It's the one method of an object which is considered to mutate it "in place".
* `Time`'s `update` is triggered from the clock in the harness. It looks like it's returning a new Time object, but because it's the update, this will be compiled into a mutation.
* `Keys`, because it is a mailbox (`>`) object, actually has its values set from outside. Its update doesn't actually do anything, but needs to exist because the external harness will call an update after injecting new values into it. Target injects keys every frame, then ticks `$Time` so `Game::update` runs once.
* Methods are either single expessions or a "let binding" ie. a sequence of further definitions, separated by `.` and then a final expression. 
* The `if` in WCHNT is a conditional expression. Not a control structure.
* Arrays have a `map` method which takes an anonymous code block / lambda and maps it across all members of the array. Eg. `movedObs = pollutants.map({ Pollutant/p | p.move(r) }).`
* Arrays also have a `fold` (aka "reduce" in other languages) function. Which lets you reduce the whole collection to a single value. In `hit = movedObs.fold(false, { acc, o | if (acc) { true } else { movedP.collides(o) } }).` we are testing each of the pollutants in `movedObs` to see if the player in `movedP` collided with it. Note that the accumulator is the first argument to the fold, and the collection is the block.


```
Time::update = { [:Time (t + 1)] }

Keys::update = { [:Keys left right up down] }

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

Game::update = {
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

## Target

Almost 

```
%canvas

%init
var assemblage;

function init() {
    assemblage = gameFactory();
}

%step
function step() {
    var k = input.keys;
    assemblage.keys.inject(!!k["ArrowLeft"], !!k["ArrowRight"], !!k["ArrowUp"], !!k["ArrowDown"]);
    assemblage.time.update();
    var r = assemblage.playArea.rect;
    var p = assemblage.player;
    wchntGraphics.clear();
    wchntGraphics.beginFill(0x000000);
    wchntGraphics.drawRect(0, 0, r.width, r.height);
    wchntGraphics.endFill();
    wchntGraphics.lineStyle(2, 0x00ff00);
    wchntGraphics.drawRect(r.x, r.y, r.width, r.height);
    for (var o of assemblage.pollutants) {
        wchntGraphics.beginFill(0xff6464);
        wchntGraphics.drawCircle(o.x, o.y, o.rad);
        wchntGraphics.endFill();
    }
    wchntGraphics.beginFill(0xc8c864);
    wchntGraphics.drawCircle(p.x, p.y, p.rad);
    wchntGraphics.endFill();
    wchntGraphics.fillText("Score: " + assemblage.score, 12, 24);
}

```
