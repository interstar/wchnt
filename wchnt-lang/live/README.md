# WCHNT live page

Browser interpreter: CodeMirror edits a `.wcn` file, Run constructs the heap and starts the `%canvas` frame loop. Schema / Construction / Methods are the same IR as the Haxe compiler. Target bodies are real JavaScript (`js/Function`). The harness (`public/harness.js`) owns the canvas, `graphics`, and `requestAnimationFrame`.

Default buffer is `examples/bounce_canvas.wcn`.

Runtime is static files only — open `public/index.html` after the CLJS build. No Node server.

## Build

From `wchnt-lang/`:

```bash
lein live          # prepare examples/seed + wiki → live/public/js/main.js
lein live-test     # prepare examples/seed + semantics tests → live/public/js/tests.js
```

Each runs `wchnt-lang.prepare-live` first: it copies `live-examples/*.wcn` →
`live/public/test-examples/` (fetched by `tests.html`) and `seed-pages/*` →
`live/public/seed/` (fetched to seed the wiki on first visit). Then it runs
`lein with-profile +live cljsbuild once` to write `live/public/js/main.js`
(and `tests.js` for `live-test`).

Then open `live/public/index.html` via a **local HTTP server** (recommended so localStorage persists reliably):

```bash
cd live/public && python3 -m http.server 8080
# → http://127.0.0.1:8080/index.html
```

Opening `index.html` as `file://` may work in some browsers but storage is origin-specific and often unreliable. Use a static server for `tests.html` too so `fetch('test-examples/…')` works. Rebuild after changing `src/` or `live/src/`.

`lein cljsbuild auto` (with the `:live` profile) rebuilds on save; still no Node.

Stop cancels the animation frame. The next Run rebuilds the heap from Construction.

Highlighting: after idle (250ms), Schema / Construction / Methods are parsed with the compiler grammars. Marks come from Instaparse spans. A broken section keeps its last good colours and underlines the error. Target is not parsed as WCHNT.

## Resetting the wiki

To wipe localStorage and see the default seed pages (`welcome`, `bounce`, `pollution`):

- press **Ctrl+Shift+Alt+W** and confirm the prompt, or
- run `wchntReset()` in the browser console.

Both clear all saved pages and reload. There is deliberately no button in the UI.
