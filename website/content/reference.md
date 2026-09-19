# Reference — the ideas behind WCHNT

The **[Guide](guide.html)** covers *how* to write WCHNT. This page covers *why* the language is
shaped the way it is, and where to find the deeper detail. **This first draft is written by AI. But will shortly be rewritten by a human**

## Assemblage programming

Traditional object-oriented code defines classes one at a time and then wires them together with
constructors, setters, and glue. The most important information — *what is connected to what, and
how strongly* — ends up scattered across the program.

WCHNT inverts that. A program is first and foremost a **map of an object network**: a single
declarative place that says which classes exist, which objects they own, which ones they borrow,
and which changes should propagate. We call that network an **assemblage**, and the style
**assemblage programming**.

The program is then split into layers that change at different speeds:

| Layer | Question it answers |
|-------|---------------------|
| Import / Public | what may *another* assemblage see? (optional membrane) |
| Schema | what is this cluster of objects, and how are they related? |
| Construction | what data does it start with? |
| Methods | how does it behave, including calls on host objects provided by Target? |
| Target | which platform runs it, and how does the host drive each frame? |

That layering is borrowed from the idea of **shearing layers**: things that evolve at different
rates should live in different places.

## Relationships, not just fields

A field says "this object has a value of that type". WCHNT adds a second dimension: the *kind* of
relationship between the two objects. Five sigils capture the useful cases.

| Sigil | Name | What it says |
|-------|------|--------------|
| *(none)* | **ordinary component** | the parent owns the child; they're built together and live together |
| `:` | **context-dependent component** | the child exists only inside this parent, and can see the whole assemblage through a back-reference |
| `@` | **external** | the parent *uses* an object whose true home is elsewhere — borrowed, not owned; across pages, an opaque handle. Construction: a call, or a free name that becomes a factory argument (first appearance, left to right). Not `_`, not in-place construction |
| `$` | **reactive** | the parent subscribes to the child; when the child `update!`s, the parent `update!`s too |
| `+` | **delegate** | the parent owns the child *and* promotes its fields and methods. Read `Student = String/id +BasePerson` as: a Student is an id plus a BasePerson. Not a subtype — write a sum if you need a shared type |

A further mark, `>` on the class name itself, declares a **mailbox**: an object the Target (the
outside world) is allowed to fill — for example, a snapshot of held keyboard keys.

There is no class inheritance. `+` is composition with promotion. `Pentagon : Shape = …` on an
importing page means “this local class implements a *published* interface,” not `extends`.

The guiding image is **multiple membranes**. Inside an assemblage, coupling is tight and visible.
Between an assemblage and the outside world, coupling is loose. WCHNT makes that distinction
explicit instead of leaving it implicit in the code.

## Values by default; identity where it matters

Most methods are pure: they return new values rather than changing state. Mutability is not a
special privilege of one method name. The compiler marks certain classes as **mutable**
(identity-bearing):

- the **root** class of Construction
- **`$` observables** (e.g. `Time` behind `$Time`)
- **`$` subscribers** (e.g. `Game` when it owns `$Time`)
- **`>` mailbox** classes

Only those classes may define methods whose names end in `!`. Ordinary classes (`Ball`,
`Pollutant`, …) stay replaceable values.

`update!` is the reactive entry point: it takes no arguments, rewrites `this` in place, and — if
the receiver is an observable — notifies subscribers so their `update!` runs next. Other `!`
methods may take arguments. Pure methods may not call `!` methods.

`$` is a sideways notify edge, not a tree walk. Children do not `update!` unless their parent
says so (or they have their own `$`). Mailboxes are filled by Target `inject`, then run their
own `update!`.

Methods stay expressions: `let` bindings, `if` / `else`, collection combinators, and
**write-paths** (`[:Ball | x = nx]`) that copy unspecified fields. Strings do not use `+` for
concatenation; they use `.concat` or **`tpl`**, which fills `{name}` holes from a
`{String:String}` map.

## Target: keep the host thin

Schema, Construction, and Methods describe *your* assemblage. Target describes the *outer
environment* — and it's the layer you swap when you move from a terminal to a window to a browser.

Best practice for a frame-driven game:

1. Put simulation in Methods (`Time::update!`, `Game::update!`, …).
2. Put drawing in Methods (`Game::draw = { @WCHNTGraphics/g | … }`); declare required host APIs in Target.
3. Leave Target as a short harness: create with `GameAssemblage.factory()`, inject mailboxes,
   tick `$Time`, call `assemblage.draw(wchntGraphics)`.

That way OpenFL and canvas Targets differ only in how they read keys and which `%` host they name.
See [Pollution](pollution.html) and the lander / shapes examples.

Current hosts:

- **`%terminal`** — a Haxe program with a `main()`; `go.sh` compiles and runs it.
- **`%cli`** / **`%cli-live`** — a line-oriented text host (`init` / `step(line)`), with
  `wchntConsole`. `%cli-live` is the browser twin.
- **`%openfl`** — a windowed Haxe program with `init()` / `step()`.
- **`%canvas`** — the browser interpreter, with JavaScript `init()` / `step()`. This is what the
  **[Play](play/)** page runs.

The point: your Schema, Construction, and Methods stay identical across graphics
hosts. Only Target changes. That's the shearing-layer idea applied to deployment.

## The live system

The **[Play](play/)** page is a full browser interpreter: CodeMirror for editing, a canvas
harness, and the same compiler pipeline (markdown → IR) as the Haxe backend — no separate
"live-only" language.

It stores your pages in the browser's localStorage, lets you link pages with `[[PageName]]`, and
can share *published* surfaces across pages with `## Import` / `## Public`. Import is a
membrane: the other assemblage is a black box unless it lists methods (and maybe an interface)
under Public. Handles are stored as `@Quest`; Construction may call `alias.make(...)`. Wiki
links in prose do not import. It's a first step toward a Smalltalk-like live system, where you
edit an assemblage and watch it run.

## Working specifications

The source repository keeps the precise, up-to-date specs in `doc/`. If this site and the code
disagree, the code and its examples win. Key files:

- `doc/schema.md` — Schema: five sigils, types, identity / mutability, Import / Public
- `doc/import.md` — the inter-assemblage membrane
- `doc/method.md` — Methods, `update!`, write-paths, `tpl`, interfaces, `@` externs
- `doc/target.md` — Target hosts and the inject-then-tick pattern
- `doc/live.md` — the live interpreter and browser page
- `doc/plan.md` — the compiler architecture and what's next
