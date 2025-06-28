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
  - `core.clj` - Entry point and main API
  - `parser.cljc` - Grammar parsing logic
  - `haxegen.cljc` - WCHNT → Haxe code generation
  - `eyeball.cljc` - Validation and sanity checking
  - `schema.cljc` - Data schemas and validation
- `test/wchnt_lang/` - Test suite
- `doc/` - Documentation
  - `intro.md` - Language introduction and philosophy
  - `language.md` - Complete language reference
  - `plan.md` - Future development plans

## Using the JAR Library

After building the JAR, you can use it in other projects:

1. Add the JAR to your classpath
2. Import the namespace: `(require '[wchnt-lang.core :as wchnt])`
3. Use the API:
   - `(wchnt/get-parser)` - Get the WCHNT parser
   - `(wchnt/compile-to-haxe input)` - Compile to Haxe
   - `(wchnt/eyeball code)` - Validate generated code

## Learn More

- Read the [Introduction](doc/intro.md) for language philosophy and concepts
- Check the [Language Guide](doc/language.md) for complete syntax reference
- See [Future Plans](doc/plan.md) for upcoming features

## License

EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0
