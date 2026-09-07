We've not discussed this, so far. And it's even vaguer in my mind than the rest of it, is that some implementation details are defered to the last, "Target" phase of the program.

Do you know about what Stewart Brand calls "Shearing Layers"?

One way of philosophically undertanding the idea of WCHNT is that we reorganize a traditional OO program into its various "shearing layers" of degress of "locality" or "ephemerality".

That high-fallutin, but imagine WCHNT as a pre-processor for Haxe. Which is doing something a bit like Aspect Oriented Programming.

In this case, we might have a simple thing in the language that's  a "trace" 

Eg. a method might have a statement like 

trace(var)

Just to note that we want to captures and see that value of the variable at the moment.

HOWEVER, it will only be in the Target phase of that WCHNT program that we will specify exactly how "trace" is to be understood. As a simple print statement? A pretty-print? Logging to another data-structure or file?

These datails will given in Target. And may actually be written in target language. Eg. in Haxe.

This means that when, in future, we have other targets for wchnt. Either a different language. Or a different platform. Or running within a differen framework. The first three phases of the WCHNT will stay the same. And the platform specific stuff will be in the Target section.

Does all this make sense?

## Summary: Target Phase as Shearing Layers

### The Concept

WCHNT reorganizes traditional OO programming into Stewart Brand's "Shearing Layers" - from most stable to most ephemeral:

1. **Schema** - Object definitions and relationships (most stable)
2. **Construction** - Initial object creation (relatively stable)  
3. **Methods** - How objects respond to changes and express behaviour (more dynamic)
4. **Target** - Platform-specific implementation details (most ephemeral)

### The "trace" Example

#### In Reaction Phase (Platform-Independent):
```wchnt
Ball::update() = 
  trace(x)  // Abstract concept - we want to see this value
  [:Ball (x + dx) (y + dy) dx dy radius]
```

#### In Target Phase (Platform-Specific):
```haxe
// Haxe target implementation
trace(var) = Sys.println('Ball.x: ' + var)
```

```javascript
// JavaScript target implementation  
trace(var) = console.log('Ball.x:', var)
```

### Benefits of This Approach

#### 1. **Platform Independence**
- Same WCHNT program can target Haxe, JavaScript, Python, etc.
- Only the Target phase changes
- Core business logic remains identical across platforms

#### 2. **Framework Adaptation**
- Same assemblage can run in different frameworks
- Target phase handles framework-specific APIs
- No changes needed to business logic

#### 3. **Debugging Flexibility**
- `trace()` can be implemented differently for different environments:
  - **Development**: Pretty-print to console
  - **Production**: Structured logging to file
  - **Testing**: Capture to test assertions

#### 4. **Aspect-Oriented Programming**
- Cross-cutting concerns (logging, tracing, profiling) are separated
- Business logic stays clean
- Platform concerns are isolated

### Other Target-Specific Features

This pattern applies to many cross-cutting concerns:

#### Performance Monitoring:
```wchnt
// Reaction phase
Game::update() = 
  profile("game-update")
  // ... update logic
```

#### UI Binding:
```wchnt
// Reaction phase  
Score::update() = 
  bind("score-display")
  [:Score newValue]
```

#### Network Communication:
```wchnt
// Reaction phase
Player::update() = 
  sync("player-state")
  [:Player newData]
```

### Implementation Strategy

The Target phase serves as a **code generation template** that defines how abstract concepts map to concrete platform features. This handles the tension between:

- **Portability** (same logic everywhere)
- **Platform optimization** (use platform-specific features)  
- **Maintainability** (separate concerns clearly)

### Future Extensibility

As WCHNT evolves, new abstract concepts can be added in the first four phases, then implemented appropriately for each target platform in the Target phase. This provides a clean separation between language features and platform capabilities.

