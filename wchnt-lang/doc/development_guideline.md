# Development guidelines

Notes for people working on the WCHNT **compiler, interpreter, and test suite** — not for authors of `.wcn` programs.

Language semantics live in `intro.md`, `schema.md`, `method.md`, `import.md`, and `target.md`. Pipeline architecture: `plan.md`. Live interpreter: `live.md`.

This file collects **implementation gotchas**: places where the two backends (Haxe codegen and the Clojure interpreter) look different under the hood, but should behave the same when WCHNT’s public rules are followed.

---

## Interpreter vs Haxe: identity and `update`

### What the language says

WCHNT distinguishes:

| Role | Example | Contract |
|------|---------|----------|
| **Observable** | `Time` behind `$Time` | After `update`, notifies subscribers |
| **Subscriber** | `Game` (parent of a `$` slot) | Defines `update`; called when an observable ticks |
| **Identity slot** | `$` observables, `>` mailboxes | One instance for the life of the assemblage; fields patch in place |

Ordinary children (`Player`, `Ball`, `Pollutant`, …) are **not** identity objects: `Game::update` may replace them with new constructions each frame.

See `method.md` §5 and `schema.md` for the user-facing rules.

### What the backends do

**Haxe** (`ir_to_haxe.cljc`):

- Subscriber classes such as `Game` are **normal classes** with public fields.
- `Game::update` assigns `this.player = …`, `this.score = …`, etc. on the same `this`.
- **Identity-slot** rules apply only to **child types** that are observables or mailboxes (`identity-slot-type?` = `$` type or `>` class).
- Only **observable** classes get `subscribe` / `notifySubscribers` infrastructure.

**Interpreter** (`interpret.cljc`):

- `identity-object?` is broader: observables **and** subscribers **and** mailboxes.
- Those classes are stored as `{:wchnt/class … :wchnt/cell (atom fields) …}`.
- All `update` calls go through `apply-update`, which mutates the atom and (for observables) notifies subscribers.

So `Game` is an “identity object” in the interpreter’s storage model, but **not** in the language spec or Haxe codegen. That is intentional: the interpreter needs a uniform mutable handle; Haxe already has `this`.

### Safe to ignore for normal WCHNT

If code stays on the intended surface — Methods `update` constructions, Target `inject` / `time.update()`, live JS view property reads — both backends agree. No game author or Target author needs to know about `:wchnt/cell`.

The asymmetry only matters when **tooling or tests bypass the public API**.

### Rules for writing tests

#### Do

- Drive behaviour through **`interpret/call`** and **`interpret/get-field`** (JVM) or the **JS view** (`js-view/as-js`, `js-view/wrap`) in browser tests.
- Tick reactive games the way Target does: **`interpret/call` … `"update"` on the observable** (e.g. `time`), not by calling `Game::update` directly unless you are explicitly testing that entry point.
- For live/canvas tests, treat **`assemblage.pollutants`** as a **JavaScript array** after the view layer (see `js_view.cljc`); use `for…of` or indexed access, not assumptions about Clojure vectors.
- Put shared semantics in **`test/wchnt_lang/semantics_test.cljc`** (`#?(:clj …)` / `#?(:cljs …)`) when both backends should match.
- Reset observable state via **`reset!` on `(:wchnt/cell time)`** (or the relevant slot) when a test needs a specific clock frame — see existing pollution tests.

#### Do not

- Read interpreter fields with **`(:player root)`** or **`get-in` without `get-field`**. On identity objects, fields live inside **`(:wchnt/cell obj)`**, not on the outer map.
- Mutate game state with **`assoc` / `swap!` on the root map** outside `install-update` / `call`. Use `get-field` + `call`, or `swap!` on the **cell** only when simulating host injection (and document why).
- Assume **`Game` is a plain map of fields** in the interpreter because Haxe exposes `game.player` as a public var.
- Assume **subscriber classes** get the same identity-slot codegen as `$Time` or `>Keys` in Haxe — they do not; only their **child** observable/mailbox slots do.
- Write tests that depend on **`:wchnt/cell`**, **`:wchnt/subscribers`**, or other interpreter internals unless the test namespace is explicitly `#?(:clj …)` and the test is **about** the interpreter implementation.

#### Example (good)

```clojure
(let [{:keys [schema-ir methods-ir root]} (load-example "pollution_canvas")
      time (interpret/get-field root "time")]
  (reset! (:wchnt/cell time) {:t 399})
  (interpret/call schema-ir methods-ir time "update" [])
  (is (= 1 (count (interpret/get-field root "pollutants")))))
```

#### Example (bad)

```clojure
;; Fields are not on the top-level map — this reads nil or stale data.
(is (= 1 (count (:pollutants root))))

;; Bypasses reactive notify chain unless you know exactly why.
(interpret/call schema-ir methods-ir root "update" [])
```

### When to fix the backend instead of the test

If a test uses `get-field` / `call` / the JS view and **Haxe and the interpreter still disagree**, that is a **bug**, not a documented gotcha. File it against the backend that diverges from `method.md` and `schema.md`.

---

## Adding more guidelines

When you hit a recurring foot-gun (pipeline cargo, grammar edge cases, Target JS subset, live wiki storage, …), add a short section here: **what users expect**, **what the code actually does**, **what test authors must do**.

Keep user-facing semantics in the main doc set; keep this file for **maintainer** concerns only.

---

## IR flattening (construction)

### What it is

Construction source is nested hiccup-like literals:

```
[:Game [:PlayArea [0 0 800 600]] [:Ball 200 150 6 5 16]]
```

The **factory** cannot emit `new Game(new PlayArea(...), …)` from that tree directly. **Flattening** walks the construction AST and produces an ordered list of object creations with stable ids (`obj1`, `obj2`, …) and a variable map — **construction IR**.

That IR is what both Haxe codegen (`generate-factory-body`) and the interpreter (`construct`) consume.

### The issue

Flattening still lives **inside** `construction-ast-to-ir` in `ast_to_ir.cljc`, interleaved with AST walking. `plan.md` calls this unfinished architecture: either finish flatten as a clear phase with a stable IR shape, or admit the IR is still “decorated AST” and name it honestly.

**Symptoms for maintainers:**

- Some codegen still pattern-matches Instaparse node names instead of reading only IR maps.
- Harder to test “flatten in isolation” without running the full construction pipeline.
- Duplicate logic risk between interpreter instantiation and Haxe factory emission (they should stay aligned via the same IR).

**Not a user-facing gap** — games compile and run. It is **refactor debt** when touching construction or the factory.

---

## Required `%terminal` host

Every non-empty `## Target` must name a **host** before lifecycle blocks:

```
%terminal

%main
public static function main():Void { … }
```

Valid hosts: `%terminal`, `%cli`, `%cli-live`, `%openfl`, `%canvas`. There is **no default** — omitting the host line fails fast with a clear error.

Init/step hosts (`%openfl`, `%canvas`, `%cli`, `%cli-live`) use `%init` / `%step` instead of `%main`.

---

## `do { … }` in Methods (open question)

The open design question is whether Methods needs **`do { … }`** for sequential statements.

**Pros of `do`:**

- Natural order for side-effectful host calls (`g.beginFill(); g.drawRect(); g.endFill()`) without chained-call workarounds.
- Target Methods with `@Graphics` read more like the host API.
- Local debugging / logging sequences without nested `let` chains.

**Cons:**

- Second evaluation model beside “one expression per method / branch”.
- Complicates the interpreter and Haxe backends (statement vs expression).
- Invites imperative style in Methods, which the language has deliberately kept functional + `update` for mutation.

**Current stance:** keep Methods as **expression + `let` bindings + final value**; use chained calls (Haxe Void unroll) or separate methods for host drawing. Revisit `do` only if Void unrolling and `let` chains prove insufficient in real examples (shapes, Pong, Gbloink!).

---

## Legacy Java API (Neh-Thalggu)

`wchnt_lang.WchntAPI` exposes `getConstructionParser(String schema)` and `getConstructionGrammarAsString(String schema)` with a **schema parameter that is ignored**. Construction uses one unified grammar (`grammars/parse-construction`), not a per-schema generated parser.

That signature is **legacy compatibility** for the Neh-Thalggu plugin wrapper, which expected schema-driven construction grammars from an older WCHNT design. New callers should treat the schema argument as documentation-only and rely on the unified grammar. Removing the parameter would break the published Java interface without a coordinated Neh-Thalggu release.

---

## Schema `+` vs Methods `+`

Schema and Methods are **two grammars**. The same character is a different token in each.

| Place | `+` means |
|-------|-----------|
| Schema | Delegate sigil: `Student = String/id +BasePerson` |
| Methods / Construction expressions | Integer addition: `score + 1` |

Do not “unify” them. `$` already works the same way (reactive slot in Schema; not an expression operator).

**Instaparse:** the Schema rule must keep the sigil **quoted** — `Sigil = ':' / '@' / '$' / '+'`. An unquoted `+` is “one or more.” Tests that parse `Student = String/id +BasePerson` will fail if that quote is dropped.

Write `+BasePerson` with no space after the sigil, same as `:Engine` and `$Time`.

---

## Delegation is composition, never `extends`

`+` is **has-a plus promotion**, not inheritance.

- IR tag is `:delegate` on a component. There is no parent-class field on the assemblage.
- Haxe emits an owned field (`basePerson`) plus getters and forwarding methods. **Never** `class Student extends BasePerson`.
- Methods IR rewrites promoted names to paths through the slot (`name` → `basePerson.name`). Write-paths expand the same way (`[:Student | name = n]`).
- Student is **not** a BasePerson for slot typing. Shared type is an explicit sum (`Person = BasePerson | Student`).
- Must-override: if the immediate delegate `D` has a stored `:return-type` of exactly `D`, the wrapper must define that method. Collections and interface/sum returns do not force it. Check is immediate (GradStudent looks at Student, not through to BasePerson).

Never hardcode `Student` / `BasePerson` (or any class name) in compiler logic. Derive slots from schema IR. If the class cannot be determined, fail fast.

Example and tests: `examples/test_delegate.wcn`, `test/wchnt_lang/delegate_test.clj`.

---

## Import membrane (WCHNT source only)

`## Import` + `## Public` is a **compile-time** membrane (`pages.cljc`, `reaction.cljc`). Interpreter maps, Haxe `public var`, and `js-view` still expose fields. Target of the importer may cheat (`assemblage.game.playArea`). That is v1.

When touching import:

- Do **not** flatten-merge A’s Schema into B. Keep origin on classes and methods.
- Importer Schema stores a handle only as `@Quest` / `@Game`.
- Construction may contain a call leaf (`realm.make(...)`). Flatten / factory / `eval-construction-arg` must treat that as an already-wired object, not a nested `:object`.
- `Class : Interface =` on the importer implements a **published** sum. It is not `+` and not `extends`.

See `doc/import.md`, `examples/importA.wcn` / `importB.wcn`, `examples/flyingA.wcn` / `flyingB.wcn`.

---

## `String::tpl`

`tpl` fills `{name}` holes from a `{String:String}` map. Shared expander: `src/wchnt_lang/template.cljc` (interpreter + compile-time hole check). Haxe uses `WCHNTRuntime.tpl`.

- Hole names are `[A-Za-z_][A-Za-z0-9_]*`. Unmatched `{` / `}` and illegal names fail.
- A missing key fails. Extra keys are ignored.
- If the receiver is a **string literal**, compile-time checks that every hole appears in the map literal.
- Values that are not strings must be converted first (`score.str()`).
- `+` does **not** concatenate strings. Use `.concat` or `tpl`.

```
"Hello {name}".tpl({String:String "name": name})
```
