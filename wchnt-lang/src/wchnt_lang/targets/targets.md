# Target-platform architecture

The `Target` section is the boundary between the portable WCHNT compiler and
the environment in which an assemblage runs. The core compiler parses the
portable sections and hands the target text to a target-platform plugin. The
plugin owns target-specific parsing, standard-library availability, runtime
setup, and code emission.

This keeps the language sections portable while allowing targets to have
different lifecycle callbacks and different host-language conventions. A
target is selected by the first target directive, such as `%canvas`, `%openfl`,
or `%cli`.

## External classes and `%requires`

Classes supplied by a target platform are declared in a `%requires` subsection
of `Target`, one declaration per line. A declaration may name a class, or a
method whose signature the compiler can check:

    %requires
    Camera
    Camera::capture() -> Image
    WCHNTGraphics::color(Int, Int, Int) -> Int

Imported WCHNT classes are resolved through `Import` and carry their full
interface information. A name not supplied by an import is accepted as an
external class only when it is declared by `%requires` or provided by the
selected target's standard library. The target backend emits the declared
name directly in its host language; it does not try to inspect the platform
class. A class-only declaration deliberately leaves its methods opaque. To
use a platform method in a typed WCHNT expression, declare its signature in
`%requires`; otherwise its result remains the external receiver type and
numeric, field, or other WCHNT operations will reject it.

The compiler fails early for missing external classes, duplicate method
signatures, and a collision between an imported WCHNT type and a platform
external type.

## Standard libraries

Standard classes are implemented per host language. `haxe_std.cljc` contains
the Haxe-side standard implementations and `live_std.cljc` contains the live
JavaScript-side implementations. A target chooses which of these classes it
exposes. For example, the graphics wrapper is available to `%canvas` and
`%openfl`, but not to a console target.

The current availability is:

| Target | Standard classes |
| --- | --- |
| `%terminal` | `WCHNTMaths`, `WCHNTConsole` |
| `%cli` | `WCHNTMaths`, `WCHNTConsole` |
| `%openfl` | `WCHNTMaths`, `WCHNTConsole`, `WCHNTGraphics` |
| `%canvas` | `WCHNTMaths`, `WCHNTConsole`, `WCHNTGraphics` |
| `%cli-live` | `WCHNTMaths`, `WCHNTConsole` |
| `%testharness` | `WCHNTUnitTests` |
| `%testharness-live` | `WCHNTUnitTests` |

These names are conventions supplied by the target. They are not WCHNT
assemblages and do not participate in `Import`.

## Plugin API

Each target plugin provides a descriptor containing its name, host backend,
standard external types, target parser, and emitter. The registry dispatches
the target text to that descriptor and attaches the structured Target IR to
the compilation. Shared parsing, `%requires` handling, Haxe standard code,
and live standard code are reusable libraries in this directory; lifecycle
and framework-specific behavior remains in the individual target plugin.

The initial implementation uses a built-in registry. A later version may load
plugins dynamically, but that is deliberately outside the language contract.

## Source layout

* `core.cljc` contains shared target-block extraction and common IR shaping.
* `requires.cljc` parses and validates `%requires` declarations.
* `plugins.cljc` selects the plugin for a target directive.
* `terminal.cljc`, `cli.cljc`, `openfl.cljc`, `canvas.cljc`,
  `cli_live.cljc`, `testharness.cljc`, and `testharness_live.cljc`
  describe the supported target platforms.
* `%testharness` / `%testharness-live` share a custom parser
  (`testharness_parse.cljc`) so Target may repeat `%with` / `%assert`.
  Each `%with` embeds a WCHNT construction. The Haxe host expands those
  into fixture helpers (`testharness_emit.cljc` + `testharness_std.cljc`).
  The live host runs the same suite IR on the interpreter
  (`testharness_live_run.cljc` + `testharness_live_expr.cljc`). No
  `## Construction` section is required.
* `haxe_backend.cljc` and `haxe_std.cljc` implement shared Haxe support.
* `live_js.cljc`, `live_canvas.cljc`, `live_std.cljc`, and
  `interpreter_std.cljc` implement the live runtime support. The latter is
  shared host behavior; `live_std.cljc` is the live target-facing facade.
