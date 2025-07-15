# Introduction to WCHNT Language

WCHNT (We CAN Have Nice Things) is a new object-oriented language for making coding easy and fun. It's designed to express "assemblages" of different object classes in a more intuitive way than traditional OO languages.

## Philosophy

WCHNT is based on the idea that we can express an entire assemblage of multiple interconnected classes at once in a single data-schema, using a Backus-Naur Format inspired grammar. Instead of defining classes one by one, we define the entire structure of our system in a declarative way. This gives us declarative rather than imperative data descriptions and construction. A single point where we can read and edit the shape of the assemblage of objects. 

In fact "assemblage programming" turns the whole shape and ordering of an OO program upside down. A WCHNT program is a literate markdown file with 5 sections. Each of which defines one codeblock demarcated with backtick fences.

The 5 sections are :
- Schema
- Construction
- Reactive
- Imperative
- Target

So far, the Schema and Construction phases are close to finished and working. They describe the schema of the assemblage, and the data for initializing it.

The next two sections are intended for describing the behaviour of the assemblage. And the final section for target specific code. We are not working on these last three yet.

## Simple Example

Here's a basic example that defines a simple schema for a game assemblage:

### Schema
```wchnt
Game = PlayArea Ball Paddle/paddle1 Paddle/paddle2
PlayArea = Rect
Ball = int/x int/y int/dx int/dy int/rad
Paddle = int/x int/y
Rect = int/x int/y int/width int/height
```
### Construction
```
[:Game 
  [:PlayArea [0 0 500 400]]
  [:Ball 250 200 1 1 5]
  [:Paddle 50 20]
  [:Paddle 450 20]  
]
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
