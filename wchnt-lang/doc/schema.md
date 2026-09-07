# Schema phase

The Schema section declares **classes**, **types**, and **relationships** between parts of an assemblage. It is the most stable shearing layer: Construction fills it with data, Methods attach behaviour, Target binds platform details.

This file is the working spec for Schema: design intent, what parses, what codegen does today, and what is specified but not yet implemented.

**Background:** The ideas behind sigils and component types are developed at length in [`wchnt_dsl_relationships_and_reactive.md`](../../wchnt_dsl_relationships_and_reactive.md) (repo root). Philosophy and motivation also live in `intro.md`. Syntax primer: `language.md`. Construction: `construction_phase.md`. Methods (including `update` and interface signatures): **`method.md`**.

---

## Why Schema exists (assemblage programming)

WCHNT is not “generate records in Haxe.” It is **assemblage programming**: one declarative map of an object network — who exists, how they are coupled, and at what strength — before behaviour or platform code.

Four intuitions (from the relationships doc):

1. **Network as source of truth** — relationships belong in one place, not scattered through constructors and imperative wiring.
2. **Multiple membranes** — tight coupling *inside* an assemblage; loose coupling *between* assemblages or between an assemblage and the outside world.
3. **Data-first** — structure and literals are primary; behaviour and target are later phases.
4. **Pipeline-shaped programs** — schema → construction → methods → target; the compiler reassembles into normal OO at the backend.

Schema answers: *what is this cluster of objects, and what kind of relationship does each part have to its neighbours?*

---

## Mainfile format

Schema lives under a `## Schema` heading in a `.wcn` markdown file:

```wchnt
## Schema

```
Game = PlayArea Ball
PlayArea = Rect
Rect = Int/x Int/y Int/width Int/height
```


Rules:

- One **composition**, **disjunction**, or **enum** line per row.
- Lines are `ClassName = …` with spaces between elements (no semicolon).
- A leading **`>`** on the class name (`>Keys = Bool/left …`) marks a **mailbox** class. Target may fill it; Methods may not.
- Class name **`Main`** is reserved (compiler generates `class Main` as program entry).

### Page kinds (literate `.wcn` files)

A `.wcn` file is markdown prose plus optional compile sections. The compiler classifies each page:

| Kind | Compile sections present | Result |
|------|--------------------------|--------|
| **Documentation** | none (prose only, or prose + ignored fences) | success, no IR |
| **Library** | Schema (+ optional Methods / Target Methods), no Construction | Haxe classes, no `Main` / factory |
| **Program** | Schema + Construction (+ Target for runnable hosts) | full compile |

Prose headings like `## Notes` are ignored. Fenced blocks outside reserved sections are ignored.

Optional **`## Import`** (must come first) lists sibling page names (plain or `[[Name]]`). The compiler merges imported Schema + Methods + Target Methods; Construction and Target stay on the current page. See `plan.md`.

Wiki **`[[PageName]]`** links in prose are for navigation only (live browser); they do not import classes.

---

## Composition lines

A composition line defines a class and its **components** (instance variables):

```wchnt
Game = PlayArea Ball Paddle/paddle1 Paddle/paddle2
```

Each element is a **type** plus optional **field name** (`/altName`). Default field name: lower-case first letter of the type (`PlayArea` → `playArea`).

**Generated Haxe (sketch):**

```haxe
class Game {
    public var playArea: PlayArea;
    public var ball: Ball;
    public function new(playArea:PlayArea, ball:Ball) { … }
}
```

---

## Types in schema

### Primitive types

Types not defined in the schema are assumed to come from the **target platform** (Haxe v1): `Int`, `Float`, `String`, `Bool`.

### Array types

```wchnt
Team = String/name [Player]/players
```

Schema: `[Player]` → Haxe `Array<Player>`. Construction: `[:Array/Player …]`.

### Map types

```wchnt
App = {String:Int}/scores
Config = {BuildType:String}/labels
```

Schema: `{KeyType:ValType}` → Haxe `Map<KeyType, ValType>`. String **enums** (below) are often used as map keys.

### Sum types (disjunctions)

```wchnt
Shape = Circle | Square | Triangle
Circle = Int/x Int/y Int/radius Int/dx
```

- One line, all disjunction — do not mix `|` with ordinary composition on the same line.
- Codegen: Haxe `interface Shape`, concrete classes implement it.
- **Interface methods** (callable through a `Shape` reference) are declared in **Methods**, not Schema. See **`method.md`**, `examples/shapes_openfl.wcn`.

### String enums

```wchnt
BuildType = "Dev" | "Local" | "Deploy"
```

Compiles to Haxe `enum`. Distinct from class sum types (string literals, not separate classes).

### Placeholder `_` (experimental)

Grammar allows `_` (internal `_Empty`) for future slot/tree shapes. Unused in v1 examples.

---

## Component relationships: three dimensions, four sigils

Relationships between classes were originally analysed on **three axes** (see relationships doc):

| Dimension | Question |
|-----------|----------|
| **Component vs associate** | Owned by this assemblage, or merely referenced? |
| **Context-specific vs generic** | Must the child know *this* parent assemblage? |
| **Reactive vs non-reactive** | Should changes propagate automatically to dependents? |

Not all 2³ combinations are exposed in the language. They **collapse** to four **sigils** on composition lines:

| Sigil | Name | IR tag | Collapsed idea |
|-------|------|--------|----------------|
| *(none)* | **ordinary** | `:ordinary` | Generic **component**, owned, non-reactive |
| `:` | **context-specific** | `:context-specific` | **Component** tied to parent; child sees assemblage |
| `@` | **external** | `:external` | Generic **associate** — reference from outside the assemblage |
| `$` | **reactive** | `:reactive` | Observable/subscriber slot on the parent |

Sigils apply to **single schema class types** (`:Engine`, `$Time`, `@Db`). They are not combined with `[Array]` or `{Map}` in the current grammar.

**Intentionally omitted:** *context-specific association* — if something is not owned by the parent, it should not need a parent back-reference (`theCar`-style). That case is `@`, not `:`.

Sigils do **not** appear in Construction syntax; they are enforced by **codegen and wiring** (see `construction_phase.md`).

The **`>`** mark is **not** a component sigil. It goes on the **class definition** (`>Keys = …`), not on a parent slot. See [Mailbox classes](#mailbox-classes-inward-from-the-host).

---

### Ordinary components (no sigil)

**Purpose:** Default case. The child is **part of** this assemblage instance: built in the same construction story, same lifecycle as siblings under the same parent. The type (`Rect`, `Ball`) remains **generic** — reusable in other assemblages elsewhere.

```wchnt
Game = PlayArea Ball
```

**Pseudocode:**

```
// Game owns playArea and ball.
// Construction creates them together as part of [:Game …].
// Rect as a type does not know it lives inside this Game.
```

**Codegen today:** Public field + constructor parameter. **Working.**

**Examples:** `construction_simple.wcn`, `bounce_openfl.wcn`, `shapes_openfl.wcn`.

---

### Context-specific components (`:`)

**Purpose:** The child is **necessarily part of this parent** — almost `Parent:Child`. Not a reusable “global” object; meaningful only inside this assemblage. The child gets a **context back-reference** (`theCar` on `Engine`) so Methods can reach the rest of the assemblage (**intra-assemblage transparency**).

```wchnt
Car = :Engine String/model
Engine = Int/cylinders
```

**Pseudocode:**

```
class Engine {
    cylinders: Int
    theCar: Car
    setContext(c: Car) { theCar = c }
}
// Engine::torque = { theCar.model … }  — can read sibling fields via theCar
```

**Design intent (future):** Nested `:context` (`Car = :Engine`, `Engine = :FuelPump`) should percolate context to the **root** assemblage so deep children can still see `theCar`. Collections of context children (`[:Wheel]/wheels`) should give each element a parent reference. **Lifecycle:** knowing strict ownership hierarchy should eventually support clearer create/destroy rules within the assemblage.

**Codegen today:**

- Child: `theParent` field + `setContext`.
- **Construction wires the owned graph:** the factory builds parent and child and passes the child into the parent’s constructor (e.g. `new Car(engine, …)`). That is ordinary parent→child ownership.
- **The reverse link is wired on first build:** the factory (and interpreter) call `child.setContext(parent)` after construction. `update()` also re-wires context children.
- **Working** for field generation, update-time wiring, and Methods paths once context is set (`test_reaction_context_path.wcn`).

**Examples:** `test_sigil.wcn`, `test_context.wcn`.

---

### External components (`@`)

**Purpose:** The parent **holds a reference** to something whose **authoritative birth is not inside this assemblage’s construction graph**. Associate, not owner. Lifecycle independent; may pre-exist, be shared, or outlive the parent.

Original sketch (MVC-style):

```wchnt
View = :WindowManager @Model
```

`WindowManager` is context-specific to the view; `@Model` is **borrowed** — built elsewhere, passed in at construction.

**Pseudocode:**

```
existingModel = buildModelElsewhere()
view = new View(windowManager, existingModel)
// Model may be shared; destroying View does not destroy Model.
```

#### What `@` might unify (design space — not all implemented)

Several use cases feel different but share one membrane: **extrinsic reference — supplied from outside the assemblage `let`**.

| Use case | Idea | Typical source |
|----------|------|----------------|
| **Dependency injection** | Db connection, config service — runtime knows the environment | Target / factory preamble / host |
| **Foreign object** | Instance from another package or library, not constructed in our Construction | Construction passes ref, or Target injects |
| **Platform handle** | OpenFL `Graphics`, audio device — host object | Target `%init` / `%step` |
| **Extern type** | Type name used but **not defined** in this Schema | Methods `@Type/name`; Haxe import |

Common thread: **the assemblage uses it; it does not construct it as an ordinary owned child.**

This is **not** the same as:

- **ordinary** — we birth it in `[:Game …]`;
- **`:`** — child is owned *and* tied to parent identity;
- **`$`** — reactive notify contract, not merely “came from outside.”

#### `@Graphics` on `Circle`? (sketch vs good design)

A tempting sketch for OpenFL:

```wchnt
Circle = Int/x Int/y Int/radius Int/dx @Graphics
```

…so Methods could call `graphics.drawCircle(…)` on each shape. **Problems:**

- `Graphics` is **one per Sprite/step**, not one per shape — wrong granularity for MVC.
- Every `Circle` carrying a platform handle couples **domain shape** to **OpenFL**, which Schema/Methods ideally keep separate from Target.

**Better directions (implemented for draw):**

- **Method parameter** `Shape::draw = { @Graphics/g | … }` — explicit per-call injection from Target. Domain objects do not store a platform handle.
- **Keep drawing in Target** is still valid (`bounce_openfl.wcn`). `shapes_openfl.wcn` moved draw onto the `Shape` interface instead.

Putting `@Graphics` on `Game` as a schema field is **not** how the working example does it.

**Status today:**

- **Schema `@` fields** (`View = @Model`): parsed, stored as `:external` in IR, **no distinct construction/codegen** — they still behave like ordinary fields.
- **Methods `@Type/name`**: working. Registers the type as external, emits Haxe of that name, passes host method calls through. OpenFL `Void` chains unroll to statements. See **`method.md`** and `examples/shapes_openfl.wcn`.

**Examples:** Schema `@` story in `intro.md`; compiling Methods `@` in `examples/shapes_openfl.wcn`.

---

### Reactive components (`$`)

**Purpose:** Declares **observable / subscriber** coupling. The sigil marks a **slot on the parent class**, not a global property of the child type.

```wchnt
Game = PlayArea Ball $Time
Time = Int/t
```

**Meaning:**

- `$Time` on `Game` → field `time: Time`.
- **`Time`** = **observable** (subscribers, `notifySubscribers()` after `update()`).
- **`Game`** = **subscriber** (must define `update()`; called when `Time` updates).
- Reading `time` in `Game::update` is a **read**, not another tick.

**Object identity (in-place mutation):** The `$` marks a slot whose **type** is an identity object — one instance from Construction until teardown, never swapped for a different object. Factory subscribe wiring and any code holding a reference to `time` depend on this.

In **`update`** (see **`method.md` §5**):

- **Name the slot** (`time` in `[:Game … time]`) → keep the same reference.
- **Construct the same class on self** (`Time::update = { [:Time (t + 1)] }`) → patch fields on `this` in place (`this.t = …` in Haxe; interpreter merges into the live cell).
- **Construct a different class** in a `$` slot → compile error.

Ordinary children (`Ball`, `PlayArea`) are **not** identity objects: `[:Ball …]` in `Game::update` correctly replaces the slot with a new instance.

**Restrictions (enforced):** `$` type must be a schema class; not a primitive or collection.

**Codegen today:** Observable infrastructure on the `$` type; factory emits `parent.time.subscribe(parent)`. **Working** — see `test_reactive.wcn`, `bounce_openfl_time.wcn`, `shapes_openfl.wcn`.

**Methods contract:** Both observable and subscriber define `update` (see **`method.md`**). No automatic child propagation — parent ticks children only by writing `ball.update()` or constructing new values.

---

### Mailbox classes (`>` — inward from the host)

**Purpose:** Some objects cannot compute their next value from themselves. `$Time` can (`t + 1`). Held keys cannot: the snapshot comes from the keyboard. **`>`** marks a class that Target is allowed to **fill**, then `update`.

This is **not** `@`. `@` means the object is **declared elsewhere** (no `[:Keys …]` in this assemblage). A mailbox **is** in the assemblage: Schema defines it, Construction births it, `$` on a parent slot still means notify. Target only writes the next field picture.

```wchnt
>Keys = Bool/left Bool/right Bool/up Bool/down
Game = PlayArea Square $Keys
```

**Meaning:**

- `>Keys` → Target may call `keys.inject(left, right, up, down)` (schema field order).
- `inject` is **host-only**. It is generated on the class (Haxe and the live JS view). Methods cannot define or call `inject`.
- `inject` writes the fields, then runs `Keys::update`. If Game has `$Keys`, that notify runs `Game::update`.
- `Time` with only `$Time` (no `>`) cannot be injected. Target may only call `time.update()` with no payload.

**Object identity (in-place mutation):** A `>` mailbox class is an **identity object** — same rule as `$` observables. Construction creates one `Keys` instance; Target `inject` and Methods `update` **patch its fields**, never allocate a replacement. Any reference to `keys` (from Game, subscribers, or the JS view) stays valid.

In **`update`** (see **`method.md` §5**):

- **Name the slot** (`keys` in `[:Game … keys]`) → keep the same mailbox reference.
- **Construct the same mailbox class** (`[:Keys left right up down]` in `Keys::update` or after reading injected values in `Game::update`) → patch `this.keys.left`, `this.keys.right`, … in place.
- **Construct a different class** in a `>` slot → compile error.

**Restrictions:** `>` is allowed on **composition** classes only, not sum types or enums. A mailbox class must define `update` (usually `[:Keys left right up down]` — pass the injected fields through).

**Examples:** `examples/square_openfl.wcn`, `examples/square_canvas.wcn`.

#### Inject-then-tick (continuous games)

When a game needs **both** held input and a clock (e.g. `examples/pollution_openfl.wcn`), use **two mechanisms**:

1. **`>Keys` (or another mailbox)** — Target injects the held-arrow snapshot **every frame** (including “all false” when nothing is held).
2. **`$Time`** — Target calls `time.update()` once per frame. That notifies `Game::update`.

Do **not** put `$Keys` on Game if you also tick `$Time` — you would notify Game twice per frame. Instead: inject into the mailbox (no `$` on that slot), then tick Time; Game reads the mailbox fields inside `Game::update`. See **`doc/target.md`** (inject-then-tick).

**Examples:** `examples/pollution_openfl.wcn`, `examples/pollution_canvas.wcn`, `examples/bounce_openfl_time.wcn` (clock only).

See also **Identity slots in `update`** below (summary of `$` + `>` together).

---

#### Identity slots in `update` (summary)

Mailbox (`>`) and observable (`$`) objects share one rule: **mutate in place, never replace**. In any class’s `update` construction:

| You write | Effect on identity slot |
|-----------|-------------------------|
| `time` or `keys` (field name) | Same object reference |
| `[:Time (t + 1)]` or `[:Keys …]` (same class as slot type) | Patch fields on existing instance |
| `[:OtherClass …]` | Compile error |

Haxe codegen emits field assignments on `this.slot.field`; the live interpreter merges into the object’s cell. Full Methods detail: **`method.md` §5**. Target inject/tick order: **`target.md`**.

---

## What Schema does *not* include

- **Method bodies and interface signatures** — `## Methods` (**`method.md`**).
- **Initial values and wiring order** — `## Construction`.
- **Platform loops, imports, `%init`** — `## Target` (`target.md`).

Schema may **name** extern types (future `@` / extern story); **implementing** platform APIs remains Target’s job.

---

## Schema → IR → Haxe

```
Schema text → schema grammar → schema IR → Haxe classes / interfaces / enums
```

Key IR (`ast_to_ir.cljc`, `schema.cljc`):

- `:relationship` per component — `:ordinary`, `:context-specific`, `:external`, `:reactive`
- `:interfaces`, `:interface-implementers` — sum types
- `:observable-classes`, `:subscriber-classes` — from `$` slots
- `:mailbox-classes` — from `>Name` definees
- `:context-relationships` — e.g. `Engine` is context child of `Car`

---

## Canonical examples

| Topic | Example |
|-------|---------|
| Ordinary | `construction_simple.wcn`, `shapes_openfl.wcn` |
| `:context` | `test_sigil.wcn`, `test_context.wcn` |
| `$` reactive | `test_reactive.wcn`, `bounce_openfl_time.wcn`, `shapes_openfl.wcn` |
| `>` mailbox | `square_openfl.wcn`, `square_canvas.wcn` |
| Sum types + interface methods | `shapes_openfl.wcn`, `test.wcn` |
| Arrays / maps / enums | `construction_arrays.wcn`, `test_dict.wcn` |
| `@` external (schema field) | *(specified; IR only — no distinct codegen)* |
| `@` external (method param) | `shapes_openfl.wcn` |

---

## Implemented vs planned

| Feature | Status |
|---------|--------|
| Composition, primitives, arrays, maps, enums, sum types | **Working** |
| Ordinary components | **Working** |
| `:context` — `theParent`, `setContext` | **Working** (factory calls `setContext` on first build) |
| `$` — subscribe / notify / `update` contract | **Working** |
| `$` / `>` — identity slots patch in place in `update` | **Working** (Haxe + interpreter) |
| `>` — mailbox class; Target `inject` then `update` | **Working** (`square_openfl.wcn`, `square_canvas.wcn`) |
| `@` — schema field extrinsic semantics | **Specified; IR only** |
| `@Type/name` on Methods params | **Working** (`shapes_openfl.wcn`) |
| Extern types without local class definition | **Working** as Methods `@` params; not as Schema-only names |
| Platform injection via `@` | **Working** as a method argument from Target, not a stored field |
| `_` empty type | **Grammar only** |

When in doubt, **`examples/` and the compiler** override older prose in `intro.md` or `construction_phase.md`.
