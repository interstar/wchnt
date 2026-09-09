# WCHNT Language Compiler

WCHNT (We CAN Have Nice Things) is a new object-oriented language for making coding easy and fun. This repository contains the standalone compiler for the WCHNT language.

## Quick Start

### 1. Try the Compiler

Create a file called `game.wcn` with this content:

```wchnt
Game = PlayArea Ball Paddle/paddle1 Paddle/paddle2
PlayArea = Rect
Ball = int/x int/y int/dx int/dy int/rad
Paddle = int/x int/y
Rect = int/x int/y int/width int/height
```

Then compile it:

```bash
lein run game.wcn
```

This will generate Haxe code for a simple game structure.

## Development Guide

### Prerequisites

- Clojure 1.11.1+
- Leiningen

### 1. Run Tests

```bash
lein test
```

This runs the complete test suite to verify everything works correctly.

### 2. Compile Files

```bash
lein run <filename.wcn>
```

Compiles a WCHNT file to Haxe code. The generated code is printed to stdout.

### 3. Build JAR Library

```bash
./build.sh
```

Creates a distributable JAR file at `target/wchnt-lang.jar` that can be used as a library in other projects.

## Project Structure

- `src/wchnt_lang/` - Main compiler source code
  - `core.clj` - CLI entry point and public Clojure API
  - `compiler.cljc` - Markdown → IR pipeline, plus Haxe emission
  - `mainfile.cljc` - Markdown section extraction and page classification
  - `grammars.cljc` / `parser.cljc` - Instaparse grammars and parse entry points
  - `ast_to_ir.cljc` - Schema AST → IR and Construction AST → IR
  - `reaction.cljc` - Methods AST → methods IR (typing, update/interface checks)
  - `ir.cljc` / `schema.cljc` - IR constructors / Malli schemas
  - `ir_to_haxe.cljc` - IR → Haxe code generation
  - `target.cljc` - Target section parsing
  - `interpret.cljc` / `js_view.cljc` / `target_js.cljc` / `canvas.cljc` - interpreter and live/canvas backend
  - `pipeline.cljc` - cargo/stage pipeline helpers
  - `eyeball.cljc` - Cheap checks on generated Haxe
  - `api.clj` - Java API (`wchnt_lang.WchntAPI`)
- `test/wchnt_lang/` - Test suite
- `live/` - Browser interpreter page (CodeMirror + canvas)
- `doc/` - Documentation
  - `intro.md` - Language introduction and philosophy
  - `language.md` - Complete language reference
  - `plan.md` - Future development plans
  - `live.md` - Interpreter and live-page plan

## Using the JAR Library

After building the JAR, you can use it in other projects:

1. Add the JAR to your classpath
2. Import the namespace: `(require '[wchnt-lang.core :as wchnt])`
3. Use the API:
   - `(wchnt/get-schema-parser)` - Get the WCHNT schema parser
   - `(wchnt/compile-file "game.wcn")` - Compile a `.wcn` file to IR + Haxe (returns a cargo)
   - `(wchnt/eyeball haxe-code)` - Cheap checks on generated Haxe
4. Or compile a markdown string directly via `(require '[wchnt-lang.compiler :as compiler])` and `(compiler/compile markdown)`

The Java plugin API (`wchnt_lang.WchntAPI`) is documented below.

## Learn More

- Read the [Introduction](doc/intro.md) for language philosophy and concepts
- Check the [Language Guide](doc/language.md) for complete syntax reference
- [Schema](doc/schema.md), [Methods](doc/method.md), and [Plan](doc/plan.md) are the working specs
- Short slice list: [TODO.md](TODO.md)

## Scripts

Run these from this directory (`wchnt-lang/`). Prerequisites: `lein`, `haxe`, `node` (plus `lime` + OpenFL for the windowed examples).

| Script / command | What it does |
|---|---|
| `./build.sh` | Clean, run the unit tests, and build the JVM compiler JARs (`target/wchnt-lang.jar`, `target/wchnt-lang-standalone.jar`). |
| `./rebuild_all.sh` | Rebuild everything: JVM compiler (via `build.sh`), live bundles, and `website/_site/`. |
| `lein test` | Run the Clojure unit test suite. |
| `./run_examples.sh` | Compile **every** `examples/*.wcn` to Haxe and print the output. WCHNT → Haxe only; no JS compile or execution. Prints ✓/✗ per file. |
| `./go.sh <file.wcn>` | Full pipeline for **one** file: WCHNT → Haxe → JS → `node`. `%openfl` files instead write `project.xml` and launch `lime`. |
| `./go_all_examples.sh` | Full pipeline for every **terminal** example (skips `%openfl` and `%canvas`). |

### Running examples

```bash
lein test                            # unit tests
./run_examples.sh                    # compile-only sweep of every example
./go.sh examples/bounce.wcn          # one terminal example end-to-end
./go_all_examples.sh                 # all terminal examples end-to-end
./go.sh examples/shapes_openfl.wcn   # one OpenFL window (needs lime + openfl)
```

`go_all_examples.sh` skips `%openfl` (use `./go.sh` for those) and `%canvas` (browser-only). Windowed: `bounce_openfl.wcn`, `shapes_openfl.wcn`, `square_openfl.wcn`, `pollution_openfl.wcn`, `pong_openfl.wcn`. Live canvas pairs in `live-examples/`: bounce, square, pollution, pong, shapes.

**Generated artifacts:** `go.sh` and `go_all_examples.sh` write `Main.hx` and `<name>.js` into the repo root. On success these are deleted automatically; on failure they are left in place for debugging (the WCHNT compile error ends up in `Main.hx`).

### Live page (browser interpreter)

```bash
lein live        # copy examples/seed, rebuild live/public/js/main.js
lein live-test   # copy examples/seed, rebuild live/public/js/tests.js
```

Both aliases first run `wchnt-lang.prepare-live`, which copies `live-examples/*.wcn` →
`live/public/test-examples/` (loaded by `tests.html`) and `seed-pages/*` →
`live/public/seed/` (fetched to seed the wiki on first visit; never overwrites user data).

Then serve `live/public/` (e.g. `cd live/public && python3 -m http.server 8080`) and open `index.html` (or `tests.html` for the browser test runner). CodeMirror + 800×600 canvas; default buffer is `examples/bounce_canvas.wcn`. Static HTML + JS — no Node. Watch mode: `lein with-profile +live cljsbuild auto`. Details: [live/README.md](live/README.md), [doc/live.md](doc/live.md).

### Rebuild everything

```bash
./rebuild_all.sh
```

Runs `./build.sh` (JVM: clean → test → jar → uberjar), then `lein live`, `lein live-test`, and `python3 ../website/build.py`.

## License

EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0

## Java API Usage

The WCHNT compiler JAR can be used directly from Java as a standard class. This is useful for plugin systems or Java-based projects that want to invoke the WCHNT compiler without Clojure interop.

**Class:** `wchnt_lang.WchntAPI`

**Usage Example:**

```java
import wchnt_lang.WchntAPI;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class Example {
    public static void main(String[] args) {
        WchntAPI api = new WchntAPI();
        
        // Compile WCHNT to Haxe
        String wchntSource = "Game = PlayArea Ball\nPlayArea = Rect\nRect = int/x int/y int/width int/height";
        List<String> haxeClasses = api.compileToHaxe(wchntSource);
        
        for (String haxeClass : haxeClasses) {
            System.out.println("Generated Haxe class:");
            System.out.println(haxeClass);
            System.out.println();
        }
        
        // Get the grammar as a string
        String grammar = api.getSchemaGrammarAsString();
        System.out.println("Grammar: " + grammar);
        
        // Get and use the parser as a callable function
        Function<String, Map> parser = api.getSchemaParser();
        Map parseResult = parser.apply("Game = Ball");
        System.out.println("Parse success: " + parseResult.get("success"));
        if ((Boolean) parseResult.get("success")) {
            List ast = (List) parseResult.get("ast");
            System.out.println("AST: " + ast);
            // AST is a tree structure: ["Schema", ["DefLine", ["CompositionLine", ...]]]
        } else {
            System.out.println("Error: " + parseResult.get("error"));
        }
    }
}
```

**Available Methods:**
- `List<String> compileToHaxe(String input)` — Compile WCHNT DSL to Haxe code (returns list of class strings)
- `String eyeball(String code)` — Validate generated Haxe code (returns EDN string)
- `Function<String, Map> getSchemaParser()` — Get a callable schema parser function that returns structured data
- `Function<String, Map> getConstructionParser(String schema)` — Get the unified construction parser (the schema argument is unused)
- `String getSchemaGrammarAsString()` — Get the WCHNT schema grammar as a string
- `String getConstructionGrammarAsString(String schema)` — Get the construction grammar as a string

**Parser AST Structure:**
The parser returns a `Map` with:
- `"success"` (boolean) - whether parsing succeeded
- `"ast"` (List) - the parsed abstract syntax tree as a Java tree structure (if successful)
- `"error"` (string) - error message (if failed)

The AST is a tree structure where:
- Each node is a `List` starting with a string (the node type)
- Followed by child nodes (which can be more Lists or primitive values)
- Example: `["Schema", ["DefLine", ["CompositionLine", ["Definee", "Game"], ["Element", ["Type", "Ball"]]]]]`

The `compileToHaxe` method returns a `List<String>` where each string is a complete Haxe class. The `eyeball` method returns EDN (Clojure data) strings for easy parsing in Clojure or Java (with an EDN library). The `getSchemaParser` method returns a `Function<String, Map>` that can parse WCHNT input and return the result as a Java Map. The `getSchemaGrammarAsString` method returns the grammar definition that could be used with Instaparse or other parsing libraries.
