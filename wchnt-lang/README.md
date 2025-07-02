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
        String grammar = api.grammarAsString();
        System.out.println("Grammar: " + grammar);
        
        // Get and use the parser as a callable function
        Function<String, Map> parser = api.getParser();
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
- `Function<String, Map> getParser()` — Get a callable parser function that returns structured data
- `String grammarAsString()` — Get the WCHNT grammar as a string

**Parser AST Structure:**
The parser returns a `Map` with:
- `"success"` (boolean) - whether parsing succeeded
- `"ast"` (List) - the parsed abstract syntax tree as a Java tree structure (if successful)
- `"error"` (string) - error message (if failed)

The AST is a tree structure where:
- Each node is a `List` starting with a string (the node type)
- Followed by child nodes (which can be more Lists or primitive values)
- Example: `["Schema", ["DefLine", ["CompositionLine", ["Definee", "Game"], ["Element", ["Type", "Ball"]]]]]`

The `compileToHaxe` method returns a `List<String>` where each string is a complete Haxe class. The `eyeball` method returns EDN (Clojure data) strings for easy parsing in Clojure or Java (with an EDN library). The `getParser` method returns a `Function<String, Map>` that can parse WCHNT input and return the result as a Java Map. The `grammarAsString` method returns the grammar definition that could be used with Instaparse or other parsing libraries.
