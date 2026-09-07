# WCHNT Language Development Plan

This replaces the previous plan (the abandoned AST → Flattened AST → IR → Haxe rewrite, extra IR layers, and clojure.spec). That work left fossils in the compiler. This document is the current source of truth for *what we are building* and *what we will delete*.

Language philosophy lives in `intro.md` and `podcast_ramble.md`. This file is about the compiler and the next slices of work.

## Goals

Near term: a preprocessor. One assemblage file compiles to a Haxe package. Schema + Construction already do this. The program must eventually be complete enough to write small games and music (Pong, Gbloink!, string processing) without hand-editing generated classes. Libraries are allowed. Stub generation that needs a round-trip into Haxe is a failure.

V1 Target: the evolving world is written in WCHNT; the outer environment (terminal loop, OpenFL frame, browser canvas) is named in Target. That is enough to play. It is not the success criterion.

Far term: a live Smalltalk-like system. Same language, other end of the spectrum. Near-term live work is `doc/live.md` (Clojure interpreter + `live/` browser page).

Success for the *idea* is other OO languages adopting assemblage programming. WCHNT-the-compiler is the proof.

## Current state (honest)

**Works.** Markdown mainfile → schema grammar → schema IR → Haxe classes. Construction → unified grammar → construction IR → factory. Methods (lets, paths, calls, `if`, collections, typed params, interface signatures, `update` + `$` notify). Target is a real section: `%name` Haxe helpers callable from Methods, `%main` or OpenFL `%init`/`%step`. Construction programs without a host entry fail. Schema class `Main` is reserved. Examples are the spec; `go.sh` compiles terminal examples to JS/Node and OpenFL examples via lime.

**OpenFL drawing.** Bounce still draws in Target Haxe (`bounce_openfl.wcn`). Shapes draw from Methods: `@Graphics/g` on `Shape::draw`, Target only supplies the host `Graphics` (`shapes_openfl.wcn`). See **`doc/method.md`**.

**Does not work as a language yet.** Schema `@` fields are stored as `:external` but still codegen like ordinary components.

**The compiler is still messy.** Live code and abandoned attempts share namespaces, especially `parser.cljc`, `ast_to_ir.cljc`, `ir_to_haxe.cljc`, `schema.cljc`, `ir.cljc`. Do not extend dead helpers. The live compile path in `compiler.cljc` is the source of truth (`compile-to-ir` then optional Haxe emission).

## Architecture we keep

- **Cargo pipeline** (`pipeline.cljc`). Compiler bugs throw. Bad user input fails the cargo. Stash intermediate results.
- **Two grammars** (`grammars.cljc`): one schema grammar, one unified construction/expression grammar. Construction is *not* a grammar generated from the schema.
- **Markdown mainfile** with ordered sections. Prose around the fences is optional. Reserved sections: `Import`, `Schema`, `Construction`, `Methods`, `Target Methods`, `Target`. Pages without compile sections are **documentation** (ignored by the compiler). Schema-only pages are **libraries** (Haxe classes, no `Main`). **`## Import`** merges sibling libraries; **`[[links]]`** in prose are wiki navigation (live only).
- **One IR.** Schema IR is maps of assemblages, components, and relationship sigils. Construction IR is objects to allocate, assignments, and wiring. Haxe is a backend. We are not inserting extra IR layers between flatten and codegen.
- **Flattening as a construction problem**, not a second architecture: nested literals become an ordered list of object creations. Finish that so codegen sees values and variable names, not leftover AST — or stop pretending and call it a decorated AST. Prefer finishing flatten.
- **Examples in `examples/`** are the language spec. Unit tests of abandoned APIs are not.
- **Haxe v1 target**, including `setContext` for `:`.
- **`toConstruction` + helper** stay. They are debug output so we can see the heap as construction syntax. They are not a user-facing language feature. Programs dump them from `%main` when they want to.
- **`$` observables** are live: subscribe in the factory, `update` rewrites `this` and notifies subscribers.
- **Fail-fast.** No defaults-for-nil, no compatibility cushion. Experimental language, no legacy corpus.
- **Malli** for Clojure data schemas. Not clojure.spec.

## What we burn (no mourning)

Do this as we touch the files, or in a dedicated cleanup pass. Do not revive any of it.

Most of the list below is **already removed** (unified construction grammar, no `haxegen`, Malli-only validation). Remaining debt: Instaparse node matches in codegen if any resurface; see `doc/development_guideline.md` § IR flattening.

- Per-schema construction grammars: `extract-class-info`, `generate-strict-arg-rules`, atom walks in `parser.cljc`. **Removed.**
- Forward declarations of functions that do not exist (`walk-with-context`, `flatten-with-context`, `establish-context!`, …). **Removed.**
- Empty placeholders: `method-ast-to-ir`, `debug-print-construction-state`. **Removed.**
- clojure.spec IR in `ir.cljc`. **Removed** — Malli in `schema.cljc` is the checker.
- Tests whose only job is the abandoned flatten API (`clean_flatten_test` and similar). **Removed.**
- The old `plan.md` three-stage namespace plan. Already gone; do not follow leftover comments that still mention it.
- Hidden debug `Main` in `compiler.clj`. Gone. `%main` is the entry. Do not put a default loop back into the compiler.

Ignore the `neo4j/` tree. It is a side experiment, not part of this compiler.

## Compilation pipeline (live)

```
.wcn markdown
  → mainfile (section map)
  → schema text → Instaparse schema AST → schema IR → Haxe classes
  → Target text → host + % bindings + %main or %init/%step Haxe
  → methods text (if present) → unified parse → methods IR → Haxe on classes
  → construction text (if present) → unified parse → construction IR → factory Haxe
  → wrap class Main from factory, Target helpers, and %main
```

Construction flattening lives inside `construction-ast-to-ir` today. That is fine. Do not split it into new namespaces unless the file becomes unreadable after cleanup.

Codegen must not grow new knowledge of Instaparse node shapes. If it still does (`InnerObjectConstruction` in `ir_to_haxe.cljc`), that is debt to pay down, not a pattern to copy.

## Language phases

| Section | Status | Notes |
|---|---|---|
| Schema | Working | See **`doc/schema.md`**. Ordinary, `:`, `$` codegen. Schema `@` fields parsed (`:external`) but not distinct yet. Class name `Main` is reserved. |
| Construction | Working | Same expression grammar as Methods. |
| Methods | Working for v1 | Official heading `## Methods`. Arithmetic, logic, lets, paths, calls, `if`, collections, strings, typed params, interface signatures, `@Type/name` extern params, in-place `update`. Informal name: reaction. |
| Target | Working | Terminal: `%terminal` + `%main`. OpenFL/canvas: `%init` / `%step`. `%name` Haxe is callable from Methods. |

Schema, Construction, Methods, and Target are the program phases.

## How Target names the environment

Full Target reference (hosts, inject-then-tick, drawing): **`doc/target.md`**.

The host is **not** a CLI flag and **not** a markdown heading. Schema, Construction, and Methods stay the same file. Target names the outer environment, because that is the shearing layer that changes when you move from a Node dump to a windowed frame.

First useful form: a `%` with no Haxe body.

```
## Target

%terminal

%main
public static function main():Void {
    var assemblage = gameFactory();
    ...
}
```

```
## Target

%openfl

%init
var assemblage:Game;

function init():Void {
    assemblage = gameFactory();
}

%step
function step():Void {
    assemblage.time.update();
    // bounce_openfl: draw in this Haxe
    // shapes_openfl: assemblage.shapes[i].draw(graphics)
}
```

Known hosts today: `terminal`, `openfl`, `canvas`. Host names are not callable from Methods. **A host line is required** — there is no default. Two hosts, a host with a body, or an unknown empty `%` used as a helper without a function, fail.

What the host is for:

- **terminal** — compiler emits `class Main` with static `main()`. `go.sh` runs `haxe -js … -main Main` then Node. `%main` is required. `%init` / `%step` are not allowed.
- **openfl** — compiler emits `class Main extends Sprite`. `%init` once, `%step` every frame. `go.sh` / lime. Target bodies are Haxe.
- **canvas** — planned. Live interpreter only (`doc/live.md`). Same `%init` / `%step` roles; bodies are JavaScript. JS harness owns the canvas and a `graphics` object. The Haxe backend does not emit this host.

Do not invent a second grammar for this. Windowed examples: `examples/bounce_openfl.wcn`, `bounce_openfl_time.wcn`, `shapes_openfl.wcn`, `square_openfl.wcn` (`>` mailbox / arrow keys). Canvas ports: `examples/bounce_canvas.wcn`, `square_canvas.wcn`.

`class Main` in the schema is reserved because the Haxe hosts still generate a Haxe `Main` as the entry.

## Neh-Thalggu

WCHNT is a DSL plugin for Neh-Thalggu (sibling project) — MCP / CLI / web for compiling DSL snippets so coding agents call a real compiler instead of guessing.

That is why these exist and must keep working:

- **Java API** (`wchnt-lang.api` → `wchnt_lang.WchntAPI`): `compileToHaxe`, `eyeball`, grammar/parser accessors. JAR is the legacy java-jar plugin shape. Construction parser/grammar must **not** take a schema string as if we still generated per-schema grammars (the Java methods still have that parameter; ignore it or clean the signature when we next touch the plugin).
- **Eyeball** (`eyeball.cljc`): cheap checks that generated Haxe was incorporated. Keep the entrypoint. The current checks (immutable, public fields, has constructor) can evolve; the contract is `{:status :issues :notes}`.
- **Examples** (`examples.clj` and/or `examples/*.wcn`): Neh-Thalggu `examples` tool. Keep a stable list of snippets with descriptions. Prefer pointing at real `.wcn` files over a second copy of schemas.

Compile / eyeball / examples / docs / header are the plugin surface. Do not break them for internal refactors.

## Immediate work (ordered)

1. **Cleanup pass on the live path.** Done.
2. **`$` stubs actually wired.** Done. `update` exists; notify is live.
3. **Target as a real section.** Done. `%main` (terminal) or `%init`/`%step` (OpenFL). Host `%terminal` / `%openfl`.
4. **Methods in the expression grammar.** Done for the v1 surface in **`doc/method.md`**.
5. **OpenFL Main.** Done. `bounce_openfl.wcn`, `shapes_openfl.wcn`, `pong_openfl.wcn`. Next on the Haxe path: Gbloink!.
6. **Live interpreter + browser.** Done for v1 (`doc/live.md`). Canvas: bounce, square, pollution, pong, shapes.

Do not start a second IR layer. Do not generate construction grammars from schemas. Do not edit `neo4j/` as part of this plan. Do not put the game loop back into `compiler.clj`.

## Working rules (compiler)

- Short functions; kebab-case; immutable Clojure unless an atom is clearly better (and then say so).
- No hardcoded class names (`Player`, `Rect`, …). Schema is the only source of class identity.
- Class labels optional in nested construction; bracket structure is not. Sum types must be tagged.
- When flattening or inferring a missing class tag, derive it from schema position. If you cannot, fail fast.
