# Live interpreter examples (`%canvas` / `%cli-live`)

These `.wcn` files use a **live** host (JavaScript Target + browser harness). They are **not** compiled by the Haxe backend — use the [live wiki](../live/public/index.html) or `lein live-test` / `tests.html`.

| File | Notes |
|------|--------|
| `welcome.wcn` | Play wiki front page |
| `bounce_canvas.wcn` | Same assemblage as `examples/bounce_openfl.wcn` |
| `shapes_canvas.wcn` | Pair of `examples/shapes_openfl.wcn` |
| `square_canvas.wcn` | Arrow keys via `>Keys` / `inject` |
| `pollution_canvas.wcn` | Pair of `examples/pollution_openfl.wcn` |
| `pong_canvas.wcn` | Pair of `examples/pong_openfl.wcn` |
| `adventure_cli.wcn` | Pair of `examples/adventure.wcn` (`%cli-live` / `wchntConsole`) |
| `writepaths.wcn` | Pair of `examples/writepaths.wcn` (`%cli-live`; type `jets` / `ocean` / `resize`) |
| `flyingA.wcn` | Canvas pair of `examples/flyingA.wcn`; publishes `Shape` |
| `flyingB.wcn` | Imports `[[flyingA]]` and adds a Pentagon |
| `factory_args.wcn` | Pair of `examples/factory_args.wcn`; Target `new Pen` → factory arg |
| `combinators_cli.wcn` | Pair of `examples/combinators_cli.wcn`; `println` formats WCHNT values |
| `maths_cli.wcn` | Pair of `examples/maths.wcn`; factory-inject `wchntMaths` |
| `transclusion_cli.wcn` | Transcludes Schema, Construction, and Methods from `transclusion_shared.wcn` |

The Play wiki seed set is `seed-map.txt`. `seed-from-live.sh` writes `seed/*.wcn`
and `seed/index.txt`; the live page fetches that index.

OpenFL / terminal / `%cli` examples stay in **`examples/`** and run via `./go.sh` or `./go_all_examples.sh`.

Target drawing uses **`wchntGraphics`**. Target text I/O uses **`wchntConsole`** (`print` / `println`). See `doc/target.md`.
