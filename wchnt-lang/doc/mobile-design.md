# Mobile design (wiki environment)

How a wiki-like WCHNT environment should work on a phone/tablet. Phones should be **good for authoring**, not just consumption.

The live wiki is one static app (`live/public/`) served three ways: ordinary website, installable PWA, and Electron desktop. Chrome is already shared; remaining work is input and Run. See `doc/pwa.md`, `doc/live.md`, `android-target.md` (native Android, a different target).

---

## Thesis

The obstacle is **character-by-character typing of punctuation-dense syntax** (`[:Ball …]`, `@Graphics/g`, `::beginFill`, `$Time`). Change the **input method, not the activity**: author by construction, selection, and completion. WCHNT is strongly structured (Instaparse grammars per section), so authoring can mean manipulating structure, with punctuation generated (canonical print via `wchnt-lang.unparse`).

---

## Guiding stars

- **(a) Browser + PWA + Electron** — same `live/public/` files; shells differ in windowing, install, and offline, not in a forked UI.
- **(b) Haxe/OpenFL stays a target.** Electron may later become an IDE that also exports to Haxe.
- **(c) Android target** eventually — Java/Kotlin or via Haxe (`android-target.md`).
- **(d) Smalltalk-like live WCHNT** — minimal CLJS core; more of the UI eventually in interpreted WCHNT.

One core, many shells. Not two products.

---

## Headline: tune Construction with widgets

Schema types drive a **form / inspector**:

| Schema type | Mobile control |
|-------------|----------------|
| `Float` / `Int` | stepper / slider |
| colour | swatch / picker |
| `Bool` | toggle |
| enum | segmented / picker |
| nested object | collapsible group |
| list | add/remove rows |

Change a radius → **Run** → see it. Programming by tweaking, zero punctuation.

---

## Authoring on mobile (all sections)

Construction forms are the easy end of **structured editing + palettes + schema-driven completion**. Nothing is written off.

Ladder (cheap → deep):

1. **Key-cap accessory bar** — hard-to-type tokens (`[:` `]` `@` `$` `::` `/` `.`), smart pair-insertion.
2. **Snippet/template palette** — class / method / construction-row skeleton.
3. **Schema-driven completion** — types, field paths, `$Time` as tappable chips.
4. **Projectional editing** — edit the AST via taps; the printer emits source.

| Section | Technique | Difficulty |
|---------|-----------|------------|
| Construction | typed form/inspector | easy |
| Schema | class list; field = name + type picker + sigil | easy–moderate |
| Methods | statement/expr templates, operator buttons, field-path pickers | hard, high value |
| Target / Methods with `@` handles | host JS/Haxe: text + key-cap bar | text-first |

---

## Running on mobile

Today Run assumes 800×600 landscape and **arrow keys via a hidden textarea**. **The Target section declares the mobile run environment** (manifest-style), consistent with `target.md`:

- **Virtual controller** — keypad / d-pad / swipe / tilt; host injects into `>` mailboxes.
- **Orientation / aspect** — portrait/landscape, aspect intent.
- **Touch-native schemes** — other declarable schemes; part of what you author.

Exact declaration syntax is TBD.

---

## Divergence: shells, not postures

Same activity (author + tune + run). Affordances differ.

| | Wide (desktop browser / Electron) | Phone |
|---|--------------------|-------|
| Chrome | same shell; sheets as centred panels | same shell; bottom sheets |
| Text authoring | keyboard | key-cap bar + completion |
| Structure authoring | projection + mouse | projection + touch |
| Construction | form and/or text | form (primary) + text |
| Run input | keyboard | on-screen / declared scheme |
| Save | autosave | autosave |
| Navigation | search + Pages + links | same + swipe-back |
| Install | optional PWA / Electron | PWA (Add to Home Screen) |

---

## Non-goals

- No second source-of-truth; `.wcn` text stays canonical (regenerated via the printer).
- Not a fork of the live app for PWA vs Electron vs web.
- Native Android rebuild is out of scope here (`android-target.md`).

---

## Roadmap

1. **Construction form editor** — parse → edit values → print.
2. **Authoring ladder** — key-cap bar → snippets → completion → projection (Schema, then Methods).
3. **Mobile Run** — Target-declared controller + orientation.

---

## Open questions

- **Target declaration syntax** for controller scheme + orientation.
- **Projection depth for Methods:** how far before it fights the imperative/reactive nature?
- **Tablet:** closer to phone or to wide/Electron posture?
