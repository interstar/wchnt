# Introduction to WCHNT Language

WCHNT (We CAN Have Nice Things) is a new object-oriented language for making coding easy and fun. It's designed to express "assemblages" of different object classes in a more intuitive way than traditional OO languages.

## Philosophy

WCHNT is based on the idea that we can express an entire assemblage of multiple interconnected classes at once in a single data-schema, using a Backus-Naur Format inspired grammar. Instead of defining classes one by one, we define the entire structure of our system in a declarative way.

## Simple Example

Here's a basic example that defines a simple game structure:

```wchnt
Game = PlayArea Ball Paddle/paddle1 Paddle/paddle2
PlayArea = Rect
Ball = int/x int/y int/dx int/dy int/rad
Paddle = int/x int/y
Rect = int/x int/y int/width int/height
```

This generates immutable Haxe classes with public fields and constructors for a game with a play area, a ball, and two paddles.

## Quick Start

1. Save the example above to a file named `game.wcn`
2. Compile it using the WCHNT compiler:

```bash
lein run game.wcn
```

This will generate Haxe code that you can use in your projects.

## What's Next?

- Read the [Language Guide](language.md) for a complete tutorial and reference
- Check out the [Future Plans](plan.md) to see what's coming next
- Try the examples in the test files to see more complex usage patterns
