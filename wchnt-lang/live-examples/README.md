# Live interpreter examples (`%canvas`)

These `.wcn` files use the **`%canvas`** host (JavaScript Target + browser harness). They are **not** compiled by the Haxe backend — use the [live wiki](../live/public/index.html) or `lein live-test` / `tests.html`.

| File | Notes |
|------|--------|
| `bounce_canvas.wcn` | Same assemblage as `examples/bounce_openfl.wcn` |
| `square_canvas.wcn` | Arrow keys via `>Keys` / `inject` |
| `pollution_canvas.wcn` | Pair of `examples/pollution_openfl.wcn` |

OpenFL / terminal examples stay in **`examples/`** and run via `./go.sh` or `./run_examples.sh`.

Target drawing uses **`wchntGraphics`** (portable API; see `doc/target.md`).
