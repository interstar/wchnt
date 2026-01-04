# WCHNT Language Development Plan

## Guidelines for our development
- don't use atoms and mutable state. prefer immutability unless absolutely necessary
- keep functions short. Ideally below 30 lines. If a function gets signifantly bigger, break it up into smaller pieces. Use forward reference declarations if this gets circular
- FAIL FAST. We don't use things like (nil? node "undefined") ... if a value isn't what we're expecting, throw an error
- we do use the Cargo structure (defined in @pipeline.cljc) for trapping bad user input. But for everything else there's "throw".

### NEW THREE STAGE PIPELINE

## Overview

The current AST → IR → Haxe pipeline is suffering from complex interdependencies and circular function calls. We're implementing a new three-stage pipeline to separate concerns and eliminate these issues:

**AST → Flattened AST → IR → Haxe**

## Current Status (Updated)

### ✅ Phase 1: Create New Pipeline Structure - COMPLETED
- ✅ Created new namespaces:
  - `wchnt-lang.ast-flattening` (Stage 1)
  - `wchnt-lang.flattened-to-ir` (Stage 2) 
  - `wchnt-lang.ir-to-haxe-multimethods` (Stage 3)
- ✅ Updated schema with new data structures for three-stage pipeline
- ✅ Created comprehensive unit tests for Stage 1
- ✅ Established architectural foundation with clear separation of concerns

### 🔄 Phase 2: Implement Stage 1 (AST → Flattened AST) - IN PROGRESS
- ✅ Basic structure and function framework implemented
- ✅ Unit tests created and syntax errors resolved
- 🔄 Flattening logic needs refinement to properly extract nested objects
- 🔄 Tests currently failing due to incomplete extraction logic

### 🔄 Phase 3: Implement Stage 2 (Flattened AST → IR) - FRAMEWORK READY
- ✅ Basic structure implemented with proper error handling
- ✅ Function ordering issues resolved (removed atoms, implemented fail-fast)
- 🔄 Type inference logic for `InnerObjectConstruction` needs implementation
- 🔄 Variable mapping logic needs completion

### ✅ Phase 4: Implement Stage 3 (IR → Haxe with Multimethods) - COMPLETED
- ✅ Multimethods implemented for each IR node type
- ✅ Simple, focused code generation functions
- ✅ Easy extensibility for new node types
- ✅ Clean separation of concerns

### ⏳ Phase 5: Switch Over - PENDING
- ⏳ Update main pipeline to use new three-stage approach
- ⏳ Remove old code once new pipeline is working
- ⏳ Update tests to use new structure

## Key Achievements

1. **Architecture Established**: Clear three-stage pipeline with well-defined responsibilities
2. **Code Quality**: Adhered to Clojure best practices (immutability, short functions, fail-fast)
3. **Test Infrastructure**: Comprehensive unit tests for Stage 1 with proper error handling
4. **Schema Integration**: Updated Malli schemas for all new data structures
5. **Error Handling**: Implemented fail-fast approach with detailed error messages

## Stage 1: AST → Flattened AST

**Purpose**: Extract nested constructions and assign sequential names to all objects being constructed.

**Input**: Raw AST with nested `InnerObjectConstruction` and `ArrayConstruction` nodes
**Output**: Flat AST with all nested objects extracted as separate `ObjectConstruction` nodes with variable references

**Key Principles**:
- **No type inference** - Preserve `InnerObjectConstruction` nodes where type isn't explicit
- **Sequential naming** - Each object gets a name like `obj1`, `obj2`, etc.
- **Linear structure** - Output is a sequence of object creation instructions
- **Self-contained** - Each object creation includes its variable assignment

**Example**:
```
Input:  [:PlayArea [:Rect 0 0 800 600]]

Output: [:BlockStatements 
          [:Assignment "obj1" [:ObjectConstruction [:ClassName "Rect"] [:ArgList 0 0 800 600]]]
          [:Assignment "obj2" [:ObjectConstruction [:ClassName "PlayArea"] [:ArgList [:VariableRef "obj1"]]]]
        ]
```

**Implementation**:
- Simple tree walker that identifies nested constructions
- Extracts each nested object as a separate top-level assignment
- Replaces nested constructions with variable references
- No schema knowledge required - pure structural transformation

## Stage 2: Flattened AST → IR

**Purpose**: Add type information and create structured IR with proper validation.

**Input**: Flattened AST with all objects at top level
**Output**: Structured IR with resolved types, validated arguments, and object mappings

**Key Responsibilities**:
- **Type inference** - Resolve `InnerObjectConstruction` types using schema
- **Argument normalization** - Convert AST nodes to simple values
- **Validation** - Ensure all types are resolved and arguments are valid
- **Object mapping** - Create variable mappings from WCHNT names to object IDs

**Example**:
```
Input:  [:Assignment "obj1" [:InnerObjectConstruction [:ArgList 0 0 800 600]]]

Output: {:objects {"obj1" {:type :object
                          :class-name "Rect"  ; Inferred from schema
                          :args [0 0 800 600] ; Normalized values
                          :index 0}}
         :variable-mappings {"playArea" "obj1"}}
```

**Implementation**:
- Process each assignment in order
- Use schema to infer types for `InnerObjectConstruction` nodes
- Normalize all arguments to simple values (strings, numbers, object references)
- Create clean, validated IR structure

## Stage 3: IR → Haxe (Multimethods)

**Purpose**: Generate Haxe code using multimethods for clean, extensible code generation.

**Input**: Well-formed, validated IR
**Output**: Haxe source code

**Key Principles**:
- **Multimethods** - One method per IR node type
- **Simple generation** - Each method handles its specific case
- **No complex logic** - Just straightforward code generation
- **Easy extension** - Add new node types by adding new methods

**Example**:
```clojure
(defmulti generate-haxe (fn [ir-node] (:type ir-node)))

(defmethod generate-haxe :object [node]
  (str "new " (:class-name node) "(" 
       (clojure.string/join ", " (map generate-arg (:args node)))
       ")"))

(defmethod generate-haxe :array [node]
  (str "[" (clojure.string/join ", " (map generate-haxe (:elements node))) "]"))
```

## Implementation Strategy

### Phase 1: Create New Pipeline Structure ✅ COMPLETED
1. ✅ Created new namespaces:
   - `wchnt-lang.ast-flattening` (Stage 1)
   - `wchnt-lang.flattened-to-ir` (Stage 2)
   - `wchnt-lang.ir-to-haxe-multimethods` (Stage 3)
2. ✅ Updated schema with new data structures
3. ✅ Created comprehensive unit tests
4. ✅ Established architectural foundation

### Phase 2: Implement Stage 1 (AST → Flattened AST) 🔄 IN PROGRESS
- ✅ Basic structure and function framework implemented
- ✅ Unit tests created and syntax errors resolved
- 🔄 Flattening logic needs refinement to properly extract nested objects
- 🔄 Tests currently failing due to incomplete extraction logic

### Phase 3: Implement Stage 2 (Flattened AST → IR) 🔄 FRAMEWORK READY
- ✅ Basic structure implemented with proper error handling
- ✅ Function ordering issues resolved (removed atoms, implemented fail-fast)
- 🔄 Type inference logic for `InnerObjectConstruction` needs implementation
- 🔄 Variable mapping logic needs completion

### Phase 4: Implement Stage 3 (IR → Haxe with Multimethods) ✅ COMPLETED
- ✅ Multimethods implemented for each IR node type
- ✅ Simple, focused code generation
- ✅ Easy to extend with new node types
- ✅ Clean separation of concerns

### Phase 5: Switch Over ⏳ PENDING
- ⏳ Update main pipeline to use new three-stage approach
- ⏳ Remove old code once new pipeline is working
- ⏳ Update tests to use new structure

## Next Steps

### Immediate Priorities
1. **Fix Stage 1 Flattening Logic**: Complete the nested object extraction in `ast_flattening.cljc`
2. **Implement Type Inference**: Add logic to resolve `InnerObjectConstruction` types in Stage 2
3. **Complete Variable Mapping**: Finish the object mapping logic in Stage 2
4. **Integration Testing**: Connect all three stages and test with real examples

### Technical Debt
- Remove validation check from Stage 1 main function (already done)
- Fix function ordering in Stage 2 (already done)
- Ensure all functions follow the "short functions" rule (mostly done)

## Benefits

1. **Separation of Concerns**: Each stage has one clear job
2. **Eliminates Circular Dependencies**: No complex function interdependencies
3. **Easier Debugging**: Can inspect intermediate Flattened AST
4. **Simpler Logic**: Each transformation is focused and independent
5. **Better Testing**: Each stage can be tested independently
6. **Extensible**: Easy to add new node types or target languages
7. **Maintainable**: Clear data flow and simple function responsibilities

## Migration Approach

- **Incremental**: Build and test each stage independently
- **Safe**: Old code keeps working while building new code
- **Clear**: Each stage has one job and is easy to understand
- **Testable**: Each stage can be unit tested in isolation

This three-stage approach will eliminate the current complexity and provide a solid foundation for future WCHNT development.






## FUTURE WORK
### 2. Relationship Types

The IR explicitly represents the four relationship types:

#### Ordinary Components (no sigil)
```clojure
{:relationship :ordinary}
```
- Object belongs to parent
- Lifecycle connected to parent
- Default variable name: lowercase first letter of class name
- Can specify alternative name with `/altName`

#### Context-Specific Components (`:` sigil)
```clojure
{:relationship :context-specific
 :context-parent "Game"}  ; Parent class that provides context
```
- Must belong to parent
- Gets implicit parent reference (e.g., `theGame`)
- Cannot be created outside parent context
- Methods can safely access parent properties

#### External References (`@` sigil)
```clojure
{:relationship :external
 :external-type "Person"}  ; Type of external object
```
- Lent objects, independent lifecycle
- No assumption about lifecycle connection
- Reference passed in during construction

#### Reactive Dependencies (`$` sigil)
```clojure
{:relationship :reactive
 :observable true
 :subscribers ["Game"]}  ; 
```
- Observable/subscriber pattern
- Automatic update propagation
- Changes trigger `update()` method calls in subscribers

NB: THIS IS COMPLICATED AND NEEDS CAREFUL THINKING.

Game = $Time 

means that the Game class subscribes to Time. Which means the Time class now needs the extra infrastructure that allows it to be an observable. Although this line occurs in the declaration of the Game class, it has implications for the shape of the Time class.
