# Target

Target is the shearing layer that names the **outer environment**: terminal loop, OpenFL frame, browser canvas, keyboard snapshot, drawing surface. Schema, Construction, and Methods describe the assemblage; Target wires that assemblage to a host.

See also: `plan.md` (compiler pipeline), `live.md` (canvas host + live interpreter), `schema.md` (`>` mailboxes and `$` observables).

## Hosts

The first line of `## Target` must name a host (required — there is no default):

| Host | Target bodies | Entry | Examples |
|------|---------------|-------|----------|
| `%terminal` | Haxe | `%main` | `bounce_loop.wcn` |
| `%cli` | Haxe | `%init` + `%step(line)` | `adventure.wcn` |
| `%cli-live` | JavaScript | `%init` + `%step(line)` | `adventure_cli.wcn` |
| `%openfl` | Haxe | `%init` + `%step` | `bounce_openfl.wcn`, `pollution_openfl.wcn` |
| `%canvas` | JavaScript | `%init` + `%step` | `bounce_canvas.wcn`, `pollution_canvas.wcn` |

- **`%name` helpers** — Haxe fragments callable from Methods as `%name(...)` (e.g. `%trace`).
- **`## Target Methods`** — methods with `@Type/name` parameters (platform handles passed from Target). See **`method.md`**.

Target owns loops, imports, frame callbacks, and harness objects (`wchntGraphics`, `wchntConsole`, `wchntMaths`, `input.keys` on canvas). Methods must not embed platform APIs except via `@` parameters supplied by Target.

## Maths (`wchntMaths`)

Every Haxe host binds `Main.wchntMaths` (`WCHNTMaths`). Live hosts bind the same
name on the harness. Schema stores the handle in an `@` slot; Construction
uses a free name; Target passes `wchntMaths` into the factory
(`RollAssemblage.factory(wchntMaths)`).

Query methods return numbers (`randInt`, `sin`, `cos`, …). Void host methods
(drawing) stay fluent. One method can pull as many randoms as the step needs:

```
r1 = maths.randInt(10).
r2 = maths.randInt(10).
[:Trio r1 r2 r3]
```

`randInt(n)` is `0 .. n-1` and fails if `n <= 0`. `hsv(h, s, v)` returns a packed
`0xRRGGBB` int for fills. See `examples/maths.wcn` and `live-examples/origin_canvas.wcn`.

## Console (`wchntConsole`)

Every Haxe host (`%terminal`, `%cli`, `%openfl`) emits `WCHNTConsole` and binds
`Main.wchntConsole`. `%cli-live` uses the same API on the live harness. Target
writes `wchntConsole.println(team.names())` — it does not call `toConstruction`,
`arrayToConstruction`, or `mapToConstruction`.

- **format** — objects via `toConstruction`; arrays and maps via the helper; a
  bare string is unchanged (adventure prose).
- **sink** — `Sys.print` / `Sys.println` on sys targets (neko `%cli`, native
  OpenFL); `haxe.Log.trace` on JS (`%terminal` → Node, HTML5 OpenFL).
- `%cli` / `%cli-live` add a read loop; the console is still just print.

## CLI (`%cli` / `%cli-live`)

`%cli` and `%cli-live` are the interactive text hosts: the same `%init` /
`%step(line)` lifecycle and a compiler-owned read loop.

- **`%init`** — `function init():Void` (Haxe) / `function init()` (JS) runs once
  and builds the assemblage.
- **`%step`** — `function step(line:String):Void` / `function step(line)` runs
  once per input line. `line` is the line just read.
- Output goes through **`wchntConsole`** (see above). The host adds no story
  text (no “type N/E/S/W”). TTY echo of the typed line is host I/O, not
  program output.
- **`%cli`** compiles with `haxe -neko` (`Sys.stdin` is not on the JS target).
- **`%cli-live`** is the live interpreter twin: a transcript + line box. Same
  Schema / Construction / Methods as `%cli`; only Target is rewritten.

The generated `%cli` `main()` constructs a `WCHNTConsole`, runs `init()` once,
then reads lines until EOF:

```haxe
public static function main():Void {
    var app = new Main();
    app.init();
    while (true) {
        var line:String = null;
        try { line = Sys.stdin().readLine(); }
        catch (e:haxe.io.Eof) { break; }
        if (line == null) break;
        app.step(line);
    }
}
```

Target talks to the console, not `Sys.println`:

```haxe
function init():Void {
    assemblage = GameAssemblage.factory();
    wchntConsole.println(assemblage.look());
}
function step(line:String):Void {
    assemblage = assemblage.move(line);
    wchntConsole.println(assemblage.msg);
}
```

## Tick-only games (`$Time`)

When simulation is driven only by the clock, Target ticks the root’s observable once per frame:

```haxe
// OpenFL %step
assemblage.time.update();
```

`Time::update` increments `t`, notifies subscribers, and `Game::update` runs with no arguments. Target does **not** call `Game::update` directly.

Examples: `bounce_openfl_time.wcn`, `bounce_loop.wcn`, `bounce_canvas.wcn` (Methods use `step`; canvas Target calls `assemblage.step()` instead).

## Inject-then-tick (keyboard + clock)

When a game needs **continuous motion** and **held keyboard input**, Target follows a fixed two-step pattern each frame:

1. **Inject** the full snapshot into every `>` mailbox (schema field order), including all-false when nothing is held.
2. **Tick** `$Time` once with `time.update()`.

Target must **not** call `Game::update` directly. Injection runs the mailbox’s `update`; ticking Time notifies Game via `$`.

### Why this shape

- **`>Keys`** (mailbox) — the keyboard snapshot cannot be computed inside Methods. Target reads held keys and `inject`s them.
- **`$Time`** (observable) — the frame clock. One tick per frame drives `Game::update`.
- **No `$Keys` on Game** when `$Time` already drives the frame — otherwise Game would be notified twice per frame (once from inject→mailbox notify, once from Time). Game reads `keys.left` etc. inside `Game::update` instead.

Schema: `Game = … $Time Keys` (Time reactive, Keys a plain slot). Not `Game = … $Time $Keys`.

### Canvas (`%step` JavaScript)

From `pollution_canvas.wcn`:

```javascript
function step() {
    var k = input.keys;
    assemblage.keys.inject(
        !!k["ArrowLeft"], !!k["ArrowRight"],
        !!k["ArrowUp"], !!k["ArrowDown"]);
    assemblage.time.update();
    // … draw from assemblage fields …
}
```

`square_canvas.wcn` injects derived dx/dy into a two-field `>Keys` mailbox the same way.

### OpenFL (`%step` Haxe)

From `pollution_openfl.wcn`:

```haxe
function step():Void {
    assemblage.keys.inject(
        held(Keyboard.LEFT), held(Keyboard.RIGHT),
        held(Keyboard.UP), held(Keyboard.DOWN));
    assemblage.time.update();
    // … draw …
}
```

Use arrow keys (same as `square_openfl.wcn`). Longer term, Android swipe or other hosts would replace the inject body only — Schema / Construction / Methods stay the same.

### Order matters

Always **inject, then tick**. If you tick Time before injecting keys, Game sees stale input for that frame.

## Drawing

Target (or Target Methods with `@Graphics/g`) performs drawing after the tick:

- **bounce** — draw calls live in Target Haxe/JS (`bounce_openfl.wcn`, `bounce_canvas.wcn`).
- **shapes** — `Shape::draw(@Graphics/g)` in Methods; Target passes `graphics` (`shapes_openfl.wcn`).

The **`wchntGraphics`** surface is shared by both hosts (same API, same visual result): `background`, `clear`, `beginFill`, `endFill`, `lineStyle`, `noStroke`, `moveTo`, `lineTo`, `drawLine`, `drawRect`, `drawCircle`, `drawEllipse`, `fillText`. Shapes fill and/or stroke from the current state; `moveTo`/`lineTo`…`endFill` draws a filled/stroked path. OpenFL `%step` receives `wchntGraphics:WCHNTGraphics` on `Main` (does not shadow `Sprite.graphics`); Canvas `%step` receives the same name from `WCHNTHarness`. Parity example: `graphics_openfl.wcn` / `graphics_canvas.wcn`. See `live.md` and `website/content/wchntgraphics.md`.

## Identity in update (Target perspective)

Target only calls `inject` and `time.update()`. It never replaces mailbox or `$` objects. Inside `Game::update`, Methods name existing slots (`time`, `keys`) or construct the same class in place (`[:Keys …]` patches fields on `this.keys`). See **`method.md` §5** and **`schema.md`** (`$` / `>` identity).
