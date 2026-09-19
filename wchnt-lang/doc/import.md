# Import (assemblage reuse)

Design for reuse **from one assemblage to another**. This file is the agreed model
(2026-09-14) and supersedes the earlier "introducer" design, which inferred static
methods from whether a body used `this`. The new model makes the assemblage itself
an object and kills that inference.

Philosophy: `intro.md`, `podcast_ramble.md`. Schema mechanics: `schema.md`.
Construction: `construction_phase.md`. Methods: `method.md`.

---

## Cross-platform section transclusion

Transclusion is a separate, compile-time mechanism for sharing the common
parts of assemblages between target-platform pages. It is not runtime reuse;
use `## Import` when one assemblage needs to use another assemblage as an
object.

A section heading may name the page from which its contents should be copied:

```markdown
## Schema [[myapp]]
## Construction [[myapp]]
## Methods [[myapp]]

## Target
```haxe
// platform-specific target
```
```

The compiler performs this textual section replacement before parsing the
WCHNT sections. The section title and heading level come from the destination;
the section body comes from the matching heading on the named page. The
mechanism is generic and can be used on any Markdown section, including
documentation sections. `[[myapp]]` remains a normal wiki link for editors;
the compiler uses it as the transclusion directive when it occurs at the end of
a section heading.

Transclusion is intentionally one level deep. The named page and named section
must exist, and the source section must not itself be transcluded (including a
transclusion nested inside that section). Missing pages, missing sections, and
nested transclusions are compilation errors. There is no concatenation or
extension operation here.

The source page is read independently for each transcluded section. It does
not merge schemas, constructions, methods, or targets into the destination in
any other way; after replacement, the resulting page is compiled normally.

---

## The model

### An assemblage is an object

The thing you import is now a real class, not a synthetic "module alias".

- A **program** page (one with a `## Construction`) whose root class is `R`
  compiles to an **`RAssemblage`** class with one static entry:

  ```
  public static function factory(<externals>): R
  ```

- `factory()` is exactly what `<r>Factory(...)` is today — it runs the
  `## Construction` and returns the root object — just relocated into
  `RAssemblage` and given a fixed name.

- **`factory()` is mandatory.** For any program page, Target and importers call
  `RAssemblage.factory(...)`. There is no `<r>Factory` alias. Fail fast.

### Externals are factory parameters (unchanged)

Free names in `@` Construction slots still become `factory()` parameters, in
Construction-AST order, typed by the slot. This is collected by
`factory_params.cljc` and is untouched by this design.

```wchnt
Roll = @WCHNTMaths/maths

[:Roll maths]
; Target: RollAssemblage.factory(wchntMaths)
```

When an importer's `factory()` needs an external, the importer supplies it the
same way — the parameter list is just the imported page's externals:

```wchnt
[:Sky flying.factory(wchntMaths)]
```

### Libraries have no factory

A **library** page (Schema + Methods, no Construction) has no root object, so no
`factory()`. It exposes itself through the static methods it declares in
`## Public`.

*Open question:* what the assemblage class is named when there is no root class.
Deferred until libraries are actually imported; the program case is the one that
matters first.

---

## `## Public`

`## Public` now contains only two kinds of entry:

1. **Extra static method definitions** on the assemblage class — methods an
   importer calls directly (`alias.make(...)`).
2. **Published interface names** (`Shape`) — so an importer can implement the sum
   with `Pentagon : Shape = …`.

Instance methods are **not** listed. Once an importer holds a handle, it can call
**any** method on it; the membrane is about structure, not behaviour.

- `factory()` itself is implicit and never listed.
- **v1:** extra static methods **cannot call `factory`** (one factory per page;
  other statics build literals or customise a handle they are given).

---

## `## Import`

```
## Import
[[flyingA]] as flying
```

`flying` names the `GameAssemblage` class. `flying.factory()` (and
`flying.otherStatic(...)`) are ordinary static calls. The result is the root
handle:

```wchnt
## Schema
Sky = @Game
Pentagon : Shape = Int/x Int/y Int/side Int/dx

## Construction
[:Sky flying.factory().addShape([:Pentagon 640 90 36 4])]
```

- `flying` has type `GameAssemblage`; `factory()` returns `Game`, which is why it
  fits the `@Game` slot.
- On the returned `Game`, `addShape` is callable without being listed anywhere.

---

## Handles: opaque values, public behaviour

A handle type (the root `R`, or anything a method returns) follows one rule:

- **Opaque**: no field reads, no construction (`[:Game …]`), no naming internal
  classes (`Rect`, `Ball`).
- **Public behaviour**: any method is callable.

The reachable surface is "everything a handle's methods return or accept" — a
method returning an internal type makes that type reachable too, and its methods
callable. That is consistent with the rule, and accepted.

---

## v1 limits

- Extra static methods cannot call `factory`.
- The membrane is **WCHNT source only**. Target / native / generated Haxe /
  `js-view` may still peek at fields (unchanged from the earlier cut).

---

## What this deletes

Compared to the earlier "introducer" design, this removes:

- `expr-uses-instance?` / introducer inference in `reaction.cljc` + `importing.cljc`
- the `:import-alias` special expression in `reaction.cljc` / `interpret.cljc`
- `assert-public-method!` (the per-method allowlist)
- `:static-public-methods` as an *inferred* set
- the `factory-name` derivation in `ast_to_ir.cljc` — `factory` is
  now a constant name on the assemblage class

---

## Examples: before → after

### flyingA / flyingB

flyingA's duplicate no-argument Public method disappears;
`factory()` replaces it. Public shrinks to the published interface:

```
## Public
Shape
```

flyingB:

```
[:Sky flying.factory().addShape([:Pentagon 640 90 36 4])]
```

### importA / importB

importA's parameterised `make` becomes an **extra static**; the instance
methods leave Public:

```
## Public
make = { String/title, String/heroName, String/companionName |
  [:Quest …] }
```

importB is mostly unchanged: `realm.make(...)` now calls that explicit static,
and `quest.headline()` / `quest.roster()` / `quest.gold()` are callable on the
handle without being listed.

### factory_args / maths / bikes

Construction is unchanged; only the Target spelling changes
(`SketchAssemblage.factory(pen)`,
`RollAssemblage.factory(wchntMaths)`, etc.).

---

## Open questions

- Library assemblage-class naming (no root class).
- Whether extra statics eventually need `factory` (v1 forbids; revisit if the
  "customise a handle" workaround hurts).
- Re-export, nested Import, transparent struct libraries, and binding Target /
  native to the membrane — all unchanged and deferred.

---

## Who decides (archive)

If there are two kinds of reuse, someone has to say which kind *this* import is.

### If A decides (chosen)

A publishes the surface. B can only use what A offered.

**For.** The author of A knows which names are API. Opacity is real. A can change
private classes. Matches `public` / `private`. The list lives on A's wiki page.

**Against.** A must predict reuse. Easy to over-publish. A page that wanted to be
both a struct library and a box needs more than this cut (deferred).

### If B decides (rejected)

B writes `import physics as box` vs `as data`.

**For.** B knows B's needs. One page, two clients.

**Against.** If B can choose transparent, A has no membrane — that is today's
merge. "Who sees my insides?" is not the caller's question.

**B still chooses** whether to import A and the local alias.

### Deferred: two kinds of reuse

Transparent data types (`Rect` as a schema-level import, literals, field reads)
are a different membrane. Not in this cut. An assemblage with `## Public` is a
box. An assemblage without it is closed.
