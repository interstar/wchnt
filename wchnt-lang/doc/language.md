# WCHNT Language Guide

This guide covers the complete syntax and features of the WCHNT language. WCHNT uses a simple syntax inspired by Backus-Naur Format and Haskell types to define object assemblages.

## Basic Syntax

To start with, a WCHNT source file is actually embedded in a Markdown file. This is a "literate programming" 

Each line in WCHNT defines a class and its components:

```
ClassName = Component1 Component2/altName Component3
```

### Syntax Rules

- **ClassName**: The name of the class to generate (must start with uppercase letter)
- **Component1**: A component of type `Component1` (default field name: `component1`)
- **Component2/altName**: A component of type `Component2` with custom field name `altName`
- Multiple components are separated by spaces
- Each line must end without a semicolon

## Type System

### Basic Types

WCHNT supports standard primitive types:

```wchnt
Person = String/name int/age Bool/isActive Float/height
```

**Available primitive types:**
- `String` - Text strings
- `int` - Integer numbers
- `Float` - Decimal numbers
- `Bool` - Boolean values (true/false)

### Array Types

Use square brackets `[Type]` to define arrays:

```wchnt
Person = String/name [Address]/addresses
Address = String/street String/city String/zipCode
```

This creates a `Person` class with a `name` field and an `addresses` field that is an array of `Address` objects.

### Interface Disjunctions (Unions)

Use the pipe operator `|` to define interface relationships. **Important**: A line must be either all composition OR all disjunction - you cannot mix them.

```wchnt
Shape = Triangle | Circle
Triangle = int/base int/height
Circle = int/radius
```

This creates a `Shape` interface that can be either a `Triangle` or a `Circle`.

## Examples

### Basic Game Structure

```wchnt
Game = PlayArea Ball Paddle/paddle1 Paddle/paddle2
PlayArea = Rect
Ball = int/x int/y int/dx int/dy int/rad
Paddle = int/x int/y
Rect = int/x int/y int/width int/height
```

**Generated classes:**
- `Game` with fields: `playArea`, `ball`, `paddle1`, `paddle2`
- `PlayArea` with field: `rect`
- `Ball` with fields: `x`, `y`, `dx`, `dy`, `rad`
- `Paddle` with fields: `x`, `y`
- `Rect` with fields: `x`, `y`, `width`, `height`

### Person with Addresses

```wchnt
Person = String/name [Address]/addresses
Address = String/street String/city String/zipCode
```

**Generated classes:**
- `Person` with fields: `name` (String), `addresses` (Array<Address>)
- `Address` with fields: `street`, `city`, `zipCode`

### Shape Hierarchy

```wchnt
Shape = Triangle | Circle
Triangle = int/base int/height
Circle = int/radius
```

**Generated classes:**
- `Shape` interface
- `Triangle` implementing `Shape` with fields: `base`, `height`
- `Circle` implementing `Shape` with field: `radius`

## Naming Conventions

### Class Names
- Must start with an uppercase letter
- Use PascalCase (e.g., `GameObject`, `PlayerCharacter`)

### Field Names
- Default field names are lowercase versions of the type name
- Custom field names can be specified after the `/`
- Use camelCase for custom names (e.g., `playerName`, `gameScore`)

### Type Names
- Primitive types: `String`, `int`, `Float`, `Bool`
- Array types: `[TypeName]`
- Union types: `Type1 | Type2`

## Best Practices

### 1. Order of Definitions
Define classes in dependency order - define components before the classes that use them:

```wchnt
# Good: Define Rect before PlayArea
Rect = int/x int/y int/width int/height
PlayArea = Rect
Game = PlayArea Ball

# Bad: PlayArea references Rect before it's defined
PlayArea = Rect
Rect = int/x int/y int/width int/height
```

### 2. Meaningful Names
Use descriptive names for classes and fields:

```wchnt
# Good
Player = String/playerName int/playerScore

# Less clear
Player = String/n int/s
```

### 3. Consistent Structure
Group related classes together and use consistent naming patterns:

```wchnt
# Game entities
Player = String/name int/score
Enemy = String/type int/health
GameObject = int/x int/y

# UI components
Button = String/text Bool/enabled
Label = String/content
```

## Common Patterns

### 1. Configuration Objects
```wchnt
GameConfig = int/maxPlayers int/roundTime String/gameMode
PlayerConfig = String/name String/color Bool/isAI
```

### 2. Data Transfer Objects
```wchnt
UserData = String/username String/email [String]/roles
ProfileData = String/bio String/avatarUrl int/joinDate
```

### 3. State Management
```wchnt
AppState = UserData/currentUser [String]/messages Bool/isLoading
```

## Error Handling

The WCHNT compiler will report errors for:

- **Undefined types**: Referencing a class that isn't defined
- **Circular dependencies**: Classes that reference each other in a loop
- **Invalid syntax**: Malformed lines or invalid characters
- **Mixed composition/disjunction**: Using both `|` and regular composition on the same line

## Output

WCHNT generates immutable Haxe classes with:
- Public fields for all components
- Constructor that takes all fields as parameters
- Proper type annotations
- Interface implementations for union types

The generated code is ready to use in Haxe projects and can be easily integrated with existing codebases. 