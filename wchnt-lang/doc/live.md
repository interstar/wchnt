# Live environment (browser interpreter)

Working plan for a Clojure interpreter and a browser page that edits and runs WCHNT. The Haxe/OpenFL compiler stays. This is a second **backend**, not a second language.

Language philosophy: `intro.md`. Compiler architecture: `plan.md`. Methods: **`method.md`**. Target hosts: **`target.md`** (and `plan.md` for pipeline context).

---

## Why

WCHNT already splits the program into shearing layers. Schema, Construction, and Methods should be the assemblage. Target is the only place that should know about OpenFL vs a browser canvas.

`examples/bounce_openfl.wcn` is the proof of that split: bounce lives in Methods; `%step` only ticks `Game::step` and draws. The live page should run **that same Schema / Construction / Methods**, with a rewritten Target.

`shapes_openfl.wcn` (`@Graphics` on Methods) is a **supported** feature, but it is not the ideal. Methods that mention host types are target-coupled. Keep the capacity; do not treat it as the default.

Far term this is the Smalltalk-like live system. v1 is a page: editor, canvas, Run / Stop.

---

## Decisions (2026-09-04)

1. **First demo:** `bounce_openfl.wcn` Methods, new Target for the canvas host. Three shapes / `@Graphics` is later.
2. **Backend:** interpret IR in Clojure/ClojureScript. Do **not** JIT WCHNT → JavaScript in v1. A JS codegen would fork from Haxe the moment Methods grow. Speed: Clojure maps first; `deftype` only if the frame loop is too slow.
3. **Target:** new host **`%canvas`**, same lifecycle as OpenFL (`%init` / `%step`), bodies are **JavaScript**. Custom Target per platform. Same `.wcn` will not run on both hosts. Ideal: only Target differs; sometimes Methods must differ too.
4. **Editor:** CodeMirror 5 as a vendored script tag (CM6 is npm/ESM-only). One buffer. Highlight from the Instaparse AST later (debounced). Token regex only if AST walk is actually too slow.
5. **Layout:** `wchnt-lang/live/` in this repo. CLJS compiles against `src/` with **lein-cljsbuild** (`lein live`). Runtime is static HTML + JS; no Node server.
6. **Out of scope for v1:** debugger/stepper, REPL, Neh-Thalggu wiring, sound.

**Wiki (2026-09):** the live page stores `.wcn` pages in **localStorage**. **New Page** creates a sibling page; **Load example** copies `bounce_canvas` or `square_canvas`. Prose may contain **`[[PageName]]`** links — click to navigate (navigation only; class reuse is **`## Import`**). Run on a documentation page reports “nothing to run”; on a library page it is a schema/methods check pass.

**Target Methods:** methods with `@Type/name` parameters live in **`## Target Methods`**. See **`method.md`**.

---

## How it fits the compiler

```
.wcn markdown
  → mainfile → schema / construction / methods / target IR     ← shared, already live
  → Haxe backend (today)
  → Interpreter backend (this plan)
```

Shared namespaces stay the source of truth: `grammars`, `parser`, `ast-to-ir`, `reaction`, `schema`, `ir`, `target` (parse), `pipeline`. `ir-to-haxe` is Haxe-only. The interpreter consumes **methods IR + construction IR + schema IR**, not Instaparse trees and not generated Haxe.

Rule: if a language change needs a new IR node, **both** backends wait on that node. No live-only syntax.

---

## `%canvas` vs `%openfl`

| | OpenFL | Canvas (live) |
|---|---|---|
| Host | `%openfl` | `%canvas` |
| Lifecycle | `%init` `%step` | `%init` `%step` (same names, same roles) |
| Body language | Haxe | JavaScript |
| Frame loop | Lime / `ENTER_FRAME` | JS harness / `requestAnimationFrame` |
| Factory | generated `gameFactory()` | interpreter `gameFactory()` exposed to JS |
| `graphics` | OpenFL `Graphics` on the Sprite | JS object with the same method names bounce uses |

Bounce Target today (Haxe):

```
%openfl
%init
assemblage = gameFactory();
%step
assemblage = assemblage.step();
graphics.clear();
graphics.beginFill(0x2a2a2a);
graphics.drawRect(...);
graphics.drawCircle(...);
```

Canvas Target should be the same shape in JS (`function init` / `function step`, `gameFactory`, `assemblage.step()`, `graphics.*`). That is the isomorphism: **not** one file, **the same Target protocol**.

v1 Graphics subset (what bounce needs): `clear`, `beginFill`, `endFill`, `drawRect`, `drawCircle`, `lineStyle`, `fillText`. `moveTo` / `lineTo` when we port shapes.

### Inject-then-tick

For games with keyboard input plus a frame clock, Target injects `>` mailboxes then ticks `$Time` once per frame. Full pattern: **`doc/target.md`**.

### Semantics tests in the browser

Build the test bundle and copy examples:

```bash
lein live-test
```

Serve `live/public/` with any static server (examples must be reachable at `examples/*.wcn`). Open `tests.html`. The same cases run on the JVM as `lein test wchnt-lang.semantics-test`.

---

## The seam: JS Target talking to Clojure objects

`%step` is real JavaScript. Interpreter values are Clojure maps. JS cannot do `assemblage.ball.x` on a map unless we wrap it.

**v1 approach:** after Construction, wrap the root (and, lazily, field reads) as a **JS view**:

- Property read `obj.ball` → field lookup on the map, wrap the result.
- Method call `obj.step()` → look up Methods IR for that class, interpret the body, wrap the return.
- Primitives (`Int`, `String`, `Bool`) unwrap to JS numbers/strings/booleans so `b.x` in Target is a number.

`graphics` is **not** an interpreter object. It is the harness object, injected into the JS scope of `%init` / `%step` (same role as OpenFL’s Sprite `graphics`).

`input` is the other harness object. `input.keys` is a held snapshot (`ArrowLeft` / `ArrowRight` / `ArrowUp` / `ArrowDown`). Target writes it into a `>` mailbox with `inject` each frame (`examples/square_canvas.wcn`). Click the canvas first so arrows do not go to the editor.

`gameFactory` is a JS function the live page puts in that scope; it runs Construction IR and returns a wrapped root.

If wrapping gets awkward, fall back to an explicit runtime API (`WCHNT.call(obj, "step")`). Prefer the property/method view so Target JS stays visually next to Target Haxe.

---

## Can `live/` compile against `src/`?

Yes. `lein live` (cljsbuild, `:source-paths ["src" "live/src"]`) writes one `live/public/js/main.js`. Open `live/public/index.html`. No JAR, no copy of the compiler, no Node.

**Must become `.cljc` before the page can parse a file:**

- `pipeline.clj` — replace JVM `Exception.` with `ex-info` / reader conditionals
- `mainfile.clj` — string splitting only; should port cleanly
- `compiler.clj` — split “IR pipeline” (shared) from “emit Haxe” (JVM/Haxe only)

Instaparse and Malli already work on CLJS. `core.clj` / `api.clj` stay JVM (lein CLI, Java plugin).

---

## Browser app (`live/`)

```
live/
  src/          CLJS: editor, run/stop, wrap interpreter
  public/       index.html, canvas harness JS, vendored CodeMirror 5
  README.md     lein live, then open index.html
```

Page: CodeMirror (full `.wcn` markdown), error line, canvas (800×600 to match OpenFL window), Run / Stop.

**Harness (plain JS, v1):** own the `<canvas>`, 2D context, `graphics` object, `requestAnimationFrame` loop that calls `init` once and `step` each frame. No WCHNT knowledge.

**CLJS:** parse → IR → interpret Construction; install `gameFactory` + wrapped root into the harness scope; `eval` or `new Function` the Target JS bodies with that scope. Parse errors from the cargo; runtime errors from the interpreter.

**Highlighting:** on idle (250ms), parse schema + construction + methods with the existing grammars (not the whole markdown). Walk Instaparse trees to CodeMirror marks (class names, types, sigils, keywords, strings, numbers, methods). Hidden keywords (`if` / `or` / …) are located inside the node span. Failures: keep last good highlights, mark the error span.

---

## Interpreter (Clojure maps)

Object: `{:wchnt/class "Ball" :x 200 :y 150 :dx 6 :dy 5 :rad 16}` (or namespaced keys — pick one and keep it).

- Construction IR → nested maps (same order/rules as the factory).
- Methods IR → eval expressions against `this` + params + lets. Reuse the IR nodes `reaction` already produces (`:arith`, `:call`, `:if`, `:construct`, …).
- `update` mutates the map in place **or** returns a new map and swaps into the parent slot — match Haxe semantics (`update` rewrites `this`). Maps make copy-on-write easier; in-place `atom` inside the object is allowed if `update` + `$` notify need identity. Prefer immutable maps + parent slot replace first; if `$` identity is wrong, say so and use an atom.
- `$`: subscriber lists on the observable map; `update` then calls `update` on subscribers (same contract as Haxe).
- No `@Graphics` in the bounce demo. When we need it, `@` params are host objects passed in (the JS `graphics`), not Schema fields.

**JVM check:** `lein test` for interpret(IR) on bounce Methods without a browser. Optional: `lein run --interpret examples/bounce_canvas.wcn` that prints a few `step` heaps (no canvas).

---

## Example files

- Keep `examples/bounce_openfl.wcn` as the Lime program.
- Add `examples/bounce_canvas.wcn`: **copy Schema, Construction, Methods**; Target is `%canvas` + JS `%init`/`%step`.
- Live page can ship that file as the default buffer.

Do not try to run `%openfl` Haxe in the browser.

---

## Ordered slices

Each slice: tests on the JVM interpreter first; browser only when that slice needs a canvas.

1. **`.cljc` the IR pipeline.** Done. `pipeline`, `mainfile`, `compiler`, `examples` are `.cljc`. `compile-to-ir` stops before Haxe. `core.clj` / `api.clj` / `example_matrix.clj` stay JVM.
2. **`%canvas` host in `target.cljc`.** Done. Same lifecycle as OpenFL. Haxe `compile` fails with a clear error. `examples/bounce_canvas.wcn` is the pair file.
3. **Interpreter: Construction + read-only field paths.** Done. Maps with `:wchnt/class`. `examples/bounce_canvas.wcn` heap matches schema fields.
4. **Interpreter: Methods.** Done for bounce. Lets, `if`, arith, paths, `this.method()`, construct. `Game::step` moves the ball and reverses `dx` at the right wall. See `wchnt-lang.interpret` / `interpret_test`.
5. **JS view + `%canvas` eval.** Done on the JVM. `js-view` wraps objects (`assemblage.step()`, `ball.x`). `target-js` evaluates the bounce Target subset (not a full JS engine). `canvas/run-file` records `graphics` draws. The live page uses real `js/Function`.
6. **`live/` shell.** Done. `lein live` (cljsbuild), CodeMirror 5, canvas harness (`clear` / `beginFill` / `drawRect` / `drawCircle` / `endFill`), Run / Stop. Default buffer: bounce canvas. Browser Target uses real `js/Function`; objects are Proxies over `js-view`. Static `index.html` + JS.
7. **Instaparse highlighting.** Done. Debounced 250ms. Schema / Construction / Methods from the existing grammars; Target is left alone. Failed section keeps last good marks and underlines the error span.
8. **Stop / restart.** Done with the page. Tear down rAF; next Run rebuilds the heap from Construction (no hot patch of methods mid-frame in v1).

Then, not v1: `$Time` canvas port (`bounce_openfl_time`), `@Graphics` / `shapes_openfl` on canvas, target-specific Methods section, maps → deftype if profiling says so.

---

## What we will not do

- A WCHNT → JavaScript compiler in this pass.
- Interpreting Haxe.
- Putting `openfl` or `canvas` in Schema.
- A second grammar for the live page.
- Copying `src/` into `live/`.
- Making one `.wcn` file run on Lime and in the browser unchanged.

---

## Success

You paste (or load) bounce’s assemblage, hit Run, and a ball bounces on the canvas. Schema / Construction / Methods match `bounce_openfl.wcn`. Only Target is JS. The Haxe path still compiles the OpenFL file.
