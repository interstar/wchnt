# Project lint

Project lint checks things that unit tests don't: that the WCHNT backends stay
consistent with each other and with the contracts they implement. This README
is written for anyone, human or AI agent, who needs to run the linters, act on
their output, or extend them.

## Contents

- [Quick start](#quick-start)
- [Why this exists](#why-this-exists)
- [The linters](#the-linters)
- [standard_library.clj](#standard_libraryclj): the standard-library API on every host
- [backend_coverage.clj](#backend_coverageclj): the method-body IR in every backend
- [Guidance for agents](#guidance-for-agents)
- [Writing a new linter](#writing-a-new-linter)
- [TODO](#todo)

## Quick start

From `wchnt-lang/`:

    ./projectlint.sh                          # every linter
    ./project_lint/backend_coverage.clj       # one linter
    ./project_lint/standard_library.clj

Requirements:

- [babashka](https://babashka.org) (`bb`) on the PATH. Every linter is a `bb`
  script.
- `lein` and a JVM for `standard_library.clj`. It compiles a probe program
  to get the Smalltalk output, so it's the slow one (it starts a JVM).

`projectlint.sh` runs every `*.clj` / `*.bb` file in this directory in
alphabetical order. It runs all of them even when one fails, then prints
`Project lint FAILED: <scripts>` and exits 1. If they all pass it prints
`Project lint passed.` and exits 0. Other files here (`*.edn`, this README)
are data and docs and are not run.

Every linter prints one line per check: `OK …` when it passes, `ERROR …` for
each problem. It exits 1 if there was any `ERROR`.

## Why this exists

WCHNT has one front end and several backends:

    markdown ─► parse ─► IR (schema, construction, methods, target)
                          │
                          ├─► Haxe emitter        %terminal %cli %openfl
                          ├─► Smalltalk emitter   %smalltalk (Pharo file-out)
                          └─► interpreter         live browser: %canvas %form
                                                  %cli-live %testharness-live

The live environment doesn't generate code. It runs the IR directly with
`src/wchnt_lang/interpret.cljc`, so for linting purposes "the live backend"
means the interpreter, plus the browser's JavaScript standard library
`live/public/harness.js`.

A WCHNT program can rely on two things that every backend must provide:

1. **The standard library.** Classes such as `WCHNTMaths` and `WCHNTGraphics`,
   which Methods and Target code call. Their API is declared once in
   `src/wchnt_lang/targets/stdlib_signatures.cljc` (the type checker uses it).
   Each host language implements it separately. **Checked by
   `standard_library.clj`.**
2. **The method-body IR.** Every expression kind and built-in method
   (`xs.head()`, `m.put(k, v)`, `a & b` …) the front end can produce in
   Methods and Reactions. Each backend has its own dispatch over this IR.
   **Checked by `backend_coverage.clj`.**

Both checks exist because a feature added to one backend is easily forgotten
in the others. Usually nothing fails until a user program happens to use the
feature on the backend that lacks it. This happened when the Smalltalk backend
was ported from the Haxe one.

## The linters

| Script | Contract | Implementations checked | Needs |
| --- | --- | --- | --- |
| `standard_library.clj` | `src/wchnt_lang/targets/stdlib_signatures.cljc` | Haxe `haxe_std.cljc`, live `harness.js`, Smalltalk emitted file-out | bb, lein |
| `backend_coverage.clj` | `project_lint/ir_vocabulary.edn` (checked against the front end) | Haxe `haxe_backend.cljc`, Smalltalk `smalltalk.cljc`, interpreter `interpret.cljc` | bb |

---

## standard_library.clj

Checks that every standard-library class implements its declared methods on
every host that offers it.

### The contract

`src/wchnt_lang/targets/stdlib_signatures.cljc` defines `signatures`:
class name → method name → `{:args [types] :return type}`. A method can
instead have `{:overloads [{:args …} …]}`, as `WCHNTGraphics::color` does
with 1, 3 or 4 arguments. The file is declarations only. The type checker
(`reaction.cljc`), the target plugin registry (`targets/plugins.cljc`) and the
interpreter's host API (`targets/interpreter_std.cljc`) all read it, so it is
the single source of truth for the standard library's API.

Which classes a target exposes is set by the target plugin's
`:standard {:types #{…}}` (see the table in `src/wchnt_lang/targets/targets.md`).

### What is checked

| Host | Implementation | How methods are found | Direction | Classes |
| --- | --- | --- | --- | --- |
| Haxe | `src/wchnt_lang/targets/haxe_std.cljc`, which holds the Haxe standard classes as strings | The text of `class <Name> {` up to its matching `}`, then every `public [static\|inline] function <name>` except `new` | Both ways: missing **and** unexpected. Names only. | `WCHNTMaths`, `WCHNTConsole`, `WCHNTGraphics`, `WCHNTInput` |
| Live JS | `live/public/harness.js` | The factory function for the class (`function makeMaths(`, `makeConsole`, `makeGraphics`, `makeInput`) up to the next factory at the same indent, then object members at 6-space indent written `name: function(` or `name: format` (an alias) | Both ways. Names only. | Same four |
| Live JS (live-only) | `harness.js` `makeForm` | As above | Only checks that the factory has at least one method | `WCHNTForm` |
| Smalltalk | The Pharo file-out produced by `lein run smalltalk-examples/bounce.wcn` | Chunks starting `!<Class> methodsFor: 'WCHNT'…!`. The method is the first identifier on the next line, and its arity is the number of `:` in that selector line (`drawRect: x y: y width: w height: h` → `drawRect`/4) | Missing only. **Name and arity**, including each overload. Extra Smalltalk methods are allowed: the runtime has helpers. | `WCHNTMaths`, `WCHNTInput`, `WCHNTGraphics` (`%smalltalk` has no `WCHNTConsole`) |

The Smalltalk backend emits all of its runtime classes into every file-out,
so any program that compiles works as a probe. `bounce.wcn` is used because
it's small.

The mapping from class to live factory name (`"WCHNTMaths" → "makeMaths"` …)
and the list of classes per host are written at the top of the script. When
a host gains or loses a class, update them there.

### Reading the output

    OK standard library: WCHNTMaths (21 methods)
    OK standard library: WCHNTGraphics (18 methods)
    OK live-only standard library: WCHNTForm (6 methods)

| Message | Meaning | Fix |
| --- | --- | --- |
| `ERROR <Class> methods missing from Haxe: a, b` | Declared in `stdlib_signatures.cljc` but no `public function` in that Haxe class | Implement it in `haxe_std.cljc`, or remove the declaration if the method shouldn't exist |
| `ERROR <Class> methods missing from live: a, b` | No member of that name in the `harness.js` factory | Implement it in `harness.js` |
| `ERROR <Class> method/arity pairs missing from Smalltalk: drawRect/4` | No Smalltalk method with that selector name and number of arguments | Implement it in the Smalltalk runtime in `targets/smalltalk.cljc` (`graphics-*-methods`, `maths-methods` …). For multi-argument methods, also check `keyword-argument-labels` in the same file: the call site must build the same selector the runtime defines. |
| `ERROR <Class> unexpected Haxe methods: x` / `unexpected live methods: x` | A public method that isn't in the contract. Programs can't call it, because the type checker only knows declared methods. | Declare it in `stdlib_signatures.cljc` (then implement it on the other hosts), or make it private / remove it |
| `ERROR <Class>: no live methods found` | The `WCHNTForm` factory exists but no methods were recognised | Usually a formatting change in `harness.js` (see Limits) |
| Exception `Haxe standard class not found: X` / `Live standard factory not found: makeX` | The script couldn't find the anchor it scrapes from | The class or factory was renamed or moved. Update the script. |
| Exception `Could not compile Smalltalk stdlib probe` | `lein run smalltalk-examples/bounce.wcn` failed | The Smalltalk backend or `bounce.wcn` is broken. The lein error is in the message. |

### Adding a standard-library method

1. Declare it in `stdlib_signatures.cljc`.
2. Implement it in `haxe_std.cljc` (Haxe), `harness.js` (live), and the
   Smalltalk runtime in `smalltalk.cljc`, for every host whose targets expose
   the class.
3. Run `./project_lint/standard_library.clj`.

### Limits

- Scraping is text-based, so it depends on formatting. Haxe methods must be
  `public … function name`. Live members must be at exactly 6 spaces and the
  factories at 2. Reformatting `harness.js` can hide methods, which shows up
  as "missing" errors.
- Haxe and live are checked by name only, so an arity mismatch there isn't
  caught. Smalltalk is checked by name and arity.
- None of it checks behaviour, for example that `hsv` returns the same colour
  on every host.

---

## backend_coverage.clj

Checks that every backend handles every expression kind and built-in method
that the front end can put in a method body.

### The vocabulary

`project_lint/ir_vocabulary.edn` lists:

- `:expressions`: every `:expr` tag a method-body node can carry (`:int`,
  `:call`, `:if`, `:bitwise`, `:import-alias` …), each with a one-line
  description;
- `:builtins`: every built-in method, as
  `{:receiver "Array" :method "get" :arity 1 :on "Array"}`. `:on` is the tag
  the IR call node carries. If the key is absent, the node has no `:on`, and a
  backend can only identify the receiver by the method name or the receiver's
  `:type`.

This file is a lint-side stand-in for a definitive vocabulary that should
live in the compiler (see [TODO](#todo)). Until then the lint checks the
file against the front end, so it can't silently drift.

### What is checked

**1. The vocabulary matches the front end.** The script compares it with
`src/wchnt_lang/reaction.cljc`, which builds method-body IR, in both
directions:

- every map literal `{:expr <kind> …}` in the file;
- every built-in method name reachable from `builtin-call`;
- every `[method on]` from call-node literals with `:method "m" :on "X"`;
- every literal `(expect-arity "Receiver" "method" n …)` reachable from
  `builtin-call` (one-way: each must appear in the vocabulary).

**2. Every backend covers the vocabulary.**

| Backend | Dispatcher (in the `consumers` table at the top of the script) | Used by |
| --- | --- | --- |
| Haxe | `expr-ir-to-haxe` in `src/wchnt_lang/targets/haxe_backend.cljc` | `%terminal`, `%cli`, `%openfl` |
| Smalltalk | `expression` in `src/wchnt_lang/targets/smalltalk.cljc` | `%smalltalk` |
| Interpreter (live) | `eval-expr` in `src/wchnt_lang/interpret.cljc` | the browser: `%canvas`, `%form`, `%cli-live`, `%testharness-live` |

For each backend the script reports:

- expression kinds with no case;
- built-in methods with no case. These fall through to the backend's generic
  method call;
- dead branches: a branch guarded by `:on`, such as
  `(and (= on "Array") (= method "head"))`, where the vocabulary says the IR
  never tags that method with that `:on`. The branch can never match.

Out of scope, because they don't consume method IR:
`targets/live_js.cljc` (the small JS subset in a Target's `%init`/`%step`),
`targets/testharness_live_expr.cljc` (`%assert` expressions), and the
standard library (that's `standard_library.clj`).

### How the scraper works

Read this before changing the script, or when its results look wrong.

- Source files are parsed as Clojure data with `edamame` (bundled with bb),
  reading `.cljc` with the `:clj` reader-conditional branch. Nothing is
  evaluated, and the compiler isn't loaded.
- **Call-graph walk.** Starting from a root function (a backend's dispatcher,
  or the producer's `builtin-call`), the script follows calls to other
  top-level `defn`s **in the same file**. It doesn't enter another
  *dispatcher*: a function containing `(case (:expr x) …)`, or one using
  `ast-utils/node-type?` (the front end's AST converter). That stops the
  walk recursing back over the whole compiler and picking up unrelated
  method names.
- **What counts as handling** something, inside the walked functions:
  - expression kind `:k`: a test in `(case (:expr x) …)`, or `(= :k (:expr …))`
    / `(not= :k (:expr …))`;
  - built-in method `"m"`: a test in `(case method …)` /
    `(case (:method expr) …)`, `(= method "m")`,
    `(contains? #{"m" …} method)`, where `method` is the symbol `method`
    or `(:method x)`;
  - `:on` guard: an `(and …)` that directly contains both `(= on "X")` and
    `(= method "m")`. The method inside it counts only if `[m X]` is in the
    vocabulary. Otherwise the branch is reported as dead.
- The checks look at method **names** only. Receiver types and arities in
  the vocabulary are used to check the vocabulary against the front end,
  not the backends.

### Reading the output

    OK IR vocabulary matches front end (28 expression kinds, 20 built-in methods)
    OK backend coverage: Haxe
    ERROR Smalltalk does not handle expression kinds: :bitwise, :target-call
    ERROR Smalltalk has no case for built-in methods (generic call instead): put, remove
    ERROR Smalltalk branches test an :on tag the IR never sets, so never match (method/on): head/Array

The first line is about the vocabulary file. The others are one per backend.
Pairs are written `method/on`, and triples `receiver/method/arity`.

#### Vocabulary errors: the front end and `ir_vocabulary.edn` disagree

Fix these first. Backend results are only as good as the vocabulary.

| Message | Meaning | Fix |
| --- | --- | --- |
| `expression kinds produced in … but not in ir_vocabulary.edn` | The front end gained a new `{:expr :x}` node | Add `:x` to `:expressions`. The backend checks then show which backends still need it. |
| `vocabulary expression kinds never produced in …` | A kind was removed or renamed in the front end | Update the vocabulary, then remove the dead backend cases |
| `built-in methods in … but not in ir_vocabulary.edn` | A new built-in such as `xs.last()` | Add a `:builtins` entry with receiver, method, arity and `:on` (if the node carries one) |
| `vocabulary built-in methods never produced in …` | A built-in was removed or renamed | Update the vocabulary |
| `built-in :on tags differ from ir_vocabulary.edn` | The front end now tags, or no longer tags, a built-in call node with `:on` | Make `:on` in the vocabulary match, then check the backends' `:on` guards |
| `expect-arity receiver/method/arity missing from ir_vocabulary.edn` | A built-in's receiver or arity changed | Add or correct the entry |

A stray `{:expr :x …}` map literal in `reaction.cljc` that isn't really IR
(for example in an error payload) would also be picked up. If that happens,
fix the lint, not the vocabulary.

#### Backend errors: a backend doesn't cover the vocabulary

| Message | Meaning | What happens to a program that uses it |
| --- | --- | --- |
| `does not handle expression kinds` | The dispatcher has no case for that `:expr` | The emitter, or the live run, throws "Unsupported expression" / "Unknown expression IR". At least it's loud. |
| `has no case for built-in methods (generic call instead)` | No branch tests that method name, so the call goes through the backend's default user/external-method path | Usually **silent and wrong**. Smalltalk emits a plain message send (`(m put: k with: v)`) that loads into Pharo and then fails with `doesNotUnderstand` at run time. The interpreter throws "Unknown array method". |
| `branches test an :on tag the IR never sets, so never match` | For example `(and (= on "Array") (= method "head"))`, when the front end never puts `:on "Array"` on `head` calls | Same as the row above. The backend *looks* as if it supports the method, but it doesn't. |

To fix a dead branch, either test something the IR does carry (the method
name alone, or `(:type receiver)`), or change the front end to set `:on`. If
you change the front end, update `:on` in the vocabulary too.

An error means the backend has *no branch*. `OK` only means every branch
*exists*, not that the emitted code is right.

#### When a backend legitimately lacks a feature

There is no allowlist. A backend that doesn't support something yet keeps
failing this lint until it does. That's deliberate while the gaps are being
closed. If a backend is never going to support a feature, add both of these:

- a fail-fast check in its target plugin, with a clear message;
- an allowlist entry here.

### Adding an IR feature

1. Add it in the front end (`reaction.cljc`).
2. Add it to `ir_vocabulary.edn`. The lint fails until you do.
3. Handle it in **every** backend: `haxe_backend.cljc`, `smalltalk.cljc`,
   `interpret.cljc`.
4. Run `./project_lint/backend_coverage.clj`.

### Limits

- This is static scraping. It sees whether a dispatch branch exists, not
  whether the branch is correct. Wrong semantics, such as 0- vs 1-based
  indexing or `fold` argument order, need a conformance corpus: small
  programs compiled on every backend, with their output compared against
  the interpreter (see TODO).
- Method names only. Receiver type and arity aren't checked per backend.
- Arities are only checked against the front end where `expect-arity` is
  called with literals. `Map.get` (1 or 2 arguments) and the methods whose
  receiver is a variable (`length`, `str`) have no such call.
- If a dispatcher is renamed, update `consumers` (or `producer`) at the top
  of the script. The script fails loudly if it can't find one.

---

## Guidance for agents

- **The lint describes reality.** Don't make an error go away by editing the
  contract (`stdlib_signatures.cljc`, `ir_vocabulary.edn`) to match a broken
  backend. Edit the contract only when the *front end or the language* has
  genuinely changed.
- **Don't add allowlists or skip lists** to get a green run. A gap is a
  real gap until a backend fails fast on it with a clear message, and the
  user has agreed to that.
- **Check before assuming a false positive.** Read the backend function the
  error names. Both "dead branch" findings and missing interpreter
  built-ins have turned out to be real bugs.
- **If the scraper is wrong, fix the scraper, and say so.** For example, a
  backend now dispatches through a map instead of `case`. Keep the
  fail-loudly behaviour when anchors are missing.
- A failing lint doesn't block unit tests (`lein test`). It's a separate
  signal. Report its result alongside test results.

## Writing a new linter

- Put a `bb` script (`*.clj` or `*.bb`, executable, with `#!/usr/bin/env bb`)
  in this directory. `projectlint.sh` picks it up automatically.
- Print `OK <what passed>` or `ERROR <what is wrong>: <sorted items>` lines,
  and exit 1 if there was any error.
- Read source as data with `edamame` rather than regexes where you can.
  Find anchors (a function, a class) by name, and throw a clear exception
  if one is missing rather than silently checking nothing.
- Derive what you check from the codebase's own declarations where possible,
  as `stdlib_signatures.cljc` is used, rather than writing lists into the
  linter.
- Add a section to this README: what it checks, how, every message and its
  fix, and its limits.

## TODO

### Move the IR vocabulary into the codebase

`ir_vocabulary.edn` is a lint-side stand-in. The definitive list of expression
kinds and built-in methods should live in the compiler, as the standard-library
signatures already do in `src/wchnt_lang/targets/stdlib_signatures.cljc`. For
example, a `src/wchnt_lang/ir_vocabulary.cljc` that is:

- the Malli schema for method bodies, replacing `[:body any?]` in
  `schema.cljc`, so the front end's output is validated on every compile,
  not only when lint runs;
- the table the type checker uses for built-in receivers and arities,
  instead of each per-method `*-call` function in `reaction.cljc`
  hardcoding them;
- what each target plugin declares its coverage against, so an unsupported
  feature fails fast at compile time with a clear message ("Smalltalk
  backend does not yet support bitwise operators") instead of deep inside
  an emitter.

Once that exists, `backend_coverage.clj` should read the vocabulary from it,
as `standard_library.clj` reads `stdlib_signatures.cljc`. The front-end half
of the check then becomes a unit test.

### Other candidates

- **A conformance corpus:** tiny `.wcn` programs, one feature each, compiled
  for every backend and, where possible, run, with output compared against
  the interpreter. Smalltalk can run headless in Pharo. This is the only way
  to catch wrong semantics rather than missing branches.
  `src/wchnt_lang/example_matrix.clj` already compiles a directory of
  examples and tabulates the stages. It would need a backend dimension.
- **More vocabularies:** schema features (relationship kinds, mailbox and
  observable classes, interfaces, enums), Construction argument types, and
  Target features (`%trace`, `%main` vs `%init`/`%step`).
