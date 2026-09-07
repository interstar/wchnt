# Target

Target is the shearing layer that names the **outer environment**: terminal loop, OpenFL frame, browser canvas, keyboard snapshot, drawing surface. Schema, Construction, and Methods describe the assemblage; Target wires that assemblage to a host.

See also: `plan.md` (compiler pipeline), `live.md` (canvas host + live interpreter), `schema.md` (`>` mailboxes and `$` observables).

## Hosts

The first line of `## Target` must name a host (required — there is no default):

| Host | Target bodies | Entry | Examples |
|------|---------------|-------|----------|
| `%terminal` | Haxe | `%main` | `bounce_loop.wcn` |
| `%openfl` | Haxe | `%init` + `%step` | `bounce_openfl.wcn`, `pollution_openfl.wcn` |
| `%canvas` | JavaScript | `%init` + `%step` | `bounce_canvas.wcn`, `pollution_canvas.wcn` |

- **`%name` helpers** — Haxe fragments callable from Methods as `%name(...)` (e.g. `%trace`).
- **`## Target Methods`** — methods with `@Type/name` parameters (platform handles passed from Target). See **`method.md`**.

Target owns loops, imports, frame callbacks, and harness objects (`graphics`, `input.keys` on canvas). Methods must not embed platform APIs except via `@` parameters supplied by Target.

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

Canvas harness v1 on **`wchntGraphics`**: `clear`, `beginFill`, `endFill`, `drawRect`, `drawCircle`, `lineStyle`, `fillText`. OpenFL `%step` receives `wchntGraphics:WCHNTGraphics` on `Main` (does not shadow `Sprite.graphics`). Canvas `%step` receives the same name from `WCHNTHarness`. See `live.md`.

## Identity in update (Target perspective)

Target only calls `inject` and `time.update()`. It never replaces mailbox or `$` objects. Inside `Game::update`, Methods name existing slots (`time`, `keys`) or construct the same class in place (`[:Keys …]` patches fields on `this.keys`). See **`method.md` §5** and **`schema.md`** (`$` / `>` identity).
