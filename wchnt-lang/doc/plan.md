# WCHNT Language Development Plan

## Intermediate Representation (IR) Implementation Plan

### Overview

This plan outlines the implementation of an intermediate representation layer between the AST and target code generation. The IR will capture WCHNT's semantic concepts independently of any target platform, enabling better separation of concerns, multi-platform support, and improved error handling.

### Current Architecture

```
WCHNT Source → AST → Haxe Code
```

### Proposed Architecture

```
WCHNT Source → AST → IR → Target Code (Haxe, TypeScript, etc.)
```

## IR Structure Design

### 1. Schema Representation

The IR schema captures class definitions, relationships, and type information:

```clojure
{:schema
 {:assemblages
  [{:name "Game"
    :components
    [{:name "playArea"           ; Variable name in parent
      :type "PlayArea"          ; Class name
      :relationship :ordinary   ; No sigil - belongs to parent
      :optional-name nil}       ; No /altName specified
     {:name "ball"
      :type "Ball" 
      :relationship :ordinary
      :optional-name nil}
     {:name "paddle1"
      :type "Paddle"
      :relationship :ordinary
      :optional-name "paddle1"} ; /paddle1 specified
     {:name "paddle2" 
      :type "Paddle"
      :relationship :ordinary
      :optional-name "paddle2"}
     {:name "time"
      :type "Time"
      :relationship :reactive   ; $ sigil - observable
      :optional-name nil}]
    :context-dependencies ["Time"] ; Classes that provide context to this assemblage
    :context-providers []}         ; Classes that receive context from this assemblage
   {:name "PlayArea"
    :components
    [{:name "rect"
      :type "Rect"
      :relationship :ordinary
      :optional-name nil}]
    :context-dependencies []
    :context-providers []}
   {:name "Ball"
    :components
    [{:name "x" :type "Int" :relationship :ordinary :optional-name nil}
     {:name "y" :type "Int" :relationship :ordinary :optional-name nil}
     {:name "dx" :type "Int" :relationship :ordinary :optional-name nil}
     {:name "dy" :type "Int" :relationship :ordinary :optional-name nil}
     {:name "radius" :type "Int" :relationship :ordinary :optional-name nil}]
    :context-dependencies []
    :context-providers []}
   {:name "Paddle"
    :components
    [{:name "rect"
      :type "Rect"
      :relationship :ordinary
      :optional-name nil}]
    :context-dependencies []
    :context-providers []}
   {:name "Rect"
    :components
    [{:name "x" :type "Int" :relationship :ordinary :optional-name nil}
     {:name "y" :type "Int" :relationship :ordinary :optional-name nil}
     {:name "width" :type "Int" :relationship :ordinary :optional-name nil}
     {:name "height" :type "Int" :relationship :ordinary :optional-name nil}]
    :context-dependencies []
    :context-providers []}
   {:name "Time"
    :components
    [{:name "current" :type "Int" :relationship :ordinary :optional-name nil}]
    :context-dependencies []
    :context-providers []
    :observable true}]          ; $ sigil makes this observable
  :interfaces
  [{:name "Shape"
    :implementers ["Circle" "Triangle"]}]
  :enums
  [{:name "Color"
    :values ["Red" "Green" "Blue" "Yellow"]}]
  :context-relationships         ; NEW: Maps context-specific classes to their parent classes
  {"Engine" "Car"               ; Engine needs Car as context
   "Ball" "Game"}               ; Ball needs Game as context
  :interface-implementers       ; NEW: Maps interface names to implementing classes
  {"Shape" ["Circle" "Triangle"]}
  :observable-classes           ; NEW: Classes that need $ infrastructure
  ["Time"]
  :subscriber-classes           ; NEW: Classes that subscribe to observables
  ["Game"]
  :debug-methods                ; NEW: toConstruction method specifications
  [{:class "Rect"
    :method "toConstruction"
    :depth-parameter true
    :format :hiccup}]}
```

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

### 3. Construction Representation

The IR construction captures the dependency graph and wiring:

```clojure
{:construction
 {:root-class "Game"
  :factory-name "gameFactory"
  :objects
  [{:id "obj_1"
    :var-name "o1"
    :class "PlayArea"
    :args ["obj_2"]           ; References to other objects
    :parent nil}              ; Parent in dependency tree
   {:id "obj_2"
    :var-name "o2" 
    :class "Rect"
    :args [0 0 500 400]       ; Literal values
    :parent "obj_1"}
   {:id "obj_3"
    :var-name "o3"
    :class "Ball"
    :args [200 200 1 1 5]
    :parent nil}
   {:id "obj_4"
    :var-name "o4"
    :class "Paddle"
    :args ["obj_5"]
    :parent nil}
   {:id "obj_5"
    :var-name "o5"
    :class "Rect"
    :args [50 50 20 80]
    :parent "obj_4"}
   {:id "obj_6"
    :var-name "o6"
    :class "Paddle"
    :args ["obj_7"]
    :parent nil}
   {:id "obj_7"
    :var-name "o7"
    :class "Rect"
    :args [430 50 20 80]
    :parent "obj_6"}
   {:id "obj_8"
    :var-name "o8"
    :class "Time"
    :args [0]
    :parent nil}]
  :wiring
  [{:target "obj_1"           ; Object that needs context
    :context "obj_9"          ; Object providing context (root Game)
    :relationship :context-specific}
   {:target "obj_3"
    :context "obj_9"
    :relationship :context-specific}
   {:target "obj_4"
    :context "obj_9"
    :relationship :context-specific}
   {:target "obj_6"
    :context "obj_9"
    :relationship :context-specific}]
  :collections
  [{:id "obj_10"
    :var-name "o10"
    :type :array
    :element-type "Player"
    :elements ["obj_11" "obj_12"]}
   {:id "obj_13"
    :var-name "o13"
    :type :map
    :key-type "String"
    :value-type "Discipline"
    :entries [["Math" "obj_14"] ["English" "obj_15"]]}]
  :statements                 ; NEW: Multi-statement constructions with let bindings
  [{:type :assignment
    :variable "players"
    :value {:type :array-construction
            :element-type "Player"
            :elements [...]}}
   {:type :return
    :value {:type :object-construction
            :class "Team"
            :args ["Crystal Palace" "players"]}}]
  :dependencies               ; NEW: Dependency graph for construction order
  [{:object-id "obj_1"
    :depends-on ["obj_2" "obj_3"]
    :construction-order 1}]
  :variable-mappings          ; NEW: Let binding mappings
  {"players" "obj_10"
   "disciplines" "obj_13"}
  :return-object "obj_9"}}
```

### 4. Method Representation

The IR captures method definitions from the reaction phase:

```clojure
{:methods
 [{:class "Rect"
   :name "area"
   :parameters []              ; No parameters
   :return-type "Int"
   :body
   {:type :expression
    :expression
    {:type :binary-operation
     :operator :multiply
     :left {:type :field-access :object :self :field "width"}
     :right {:type :field-access :object :self :field "height"}}}}
  {:class "Rect"
   :name "doubleWidth"
   :parameters []
   :return-type "Rect"
   :body
   {:type :construction
    :class "Rect"
    :args [{:type :field-access :object :self :field "x"}
           {:type :field-access :object :self :field "y"}
           {:type :binary-operation
            :operator :multiply
            :left {:type :field-access :object :self :field "width"}
            :right {:type :literal :value 2}}
           {:type :field-access :object :self :field "height"}]}}
  {:class "Ball"
   :name "update"
   :parameters []
   :return-type "Ball"
   :body
   {:type :multi-statement
    :statements
    [{:type :assignment
      :variable "newdx"
      :value
      {:type :conditional
       :condition
       {:type :binary-operation
        :operator :or
        :left {:type :binary-operation
               :operator :less-than
               :left {:type :field-access :object :self :field "x"}
               :right {:type :literal :value 0}}
        :right {:type :binary-operation
                :operator :greater-than
                :left {:type :field-access :object :self :field "x"}
                :right {:type :field-access
                        :object :context
                        :field "playArea"
                        :subfield "width"}}}
       :true-branch
       {:type :unary-operation
        :operator :negate
        :operand {:type :field-access :object :self :field "dx"}}
       :false-branch
       {:type :field-access :object :self :field "dx"}}}
     {:type :assignment
      :variable "newdy"
      :value
      {:type :conditional
       :condition
       {:type :binary-operation
        :operator :or
        :left {:type :binary-operation
               :operator :less-than
               :left {:type :field-access :object :self :field "y"}
               :right {:type :literal :value 0}}
        :right {:type :binary-operation
                :operator :greater-than
                :left {:type :field-access :object :self :field "y"}
                :right {:type :field-access
                        :object :context
                        :field "playArea"
                        :subfield "height"}}}
       :true-branch
       {:type :unary-operation
        :operator :negate
        :operand {:type :field-access :object :self :field "dy"}}
       :false-branch
       {:type :field-access :object :self :field "dy"}}}]
    :return
    {:type :construction
     :class "Ball"
     :args [{:type :binary-operation
             :operator :add
             :left {:type :field-access :object :self :field "x"}
             :right {:type :variable-reference :name "newdx"}}
            {:type :binary-operation
             :operator :add
             :left {:type :field-access :object :self :field "y"}
             :right {:type :variable-reference :name "newdy"}}
            {:type :variable-reference :name "newdx"}
            {:type :variable-reference :name "newdy"}
            {:type :field-access :object :self :field "radius"}]}}}}
  {:class "Booster"
   :name "boost"
   :parameters [{:name "y" :type "Int"}]
   :return-type "Int"
   :body
   {:type :binary-operation
    :operator :multiply
    :left {:type :field-access :object :self :field "x"}
    :right {:type :parameter-reference :name "y"}}}]}
```

### 5. Block/Lambda Representation

Blocks are represented as first-class lambda expressions:

```clojure
{:type :lambda
 :parameters [{:name "x" :type "Int"}]
 :body
 {:type :binary-operation
  :operator :multiply
  :left {:type :parameter-reference :name "x"}
  :right {:type :literal :value 2}}}
```

### 6. Control Structure Representation

Control structures use blocks as combinators:

```clojure
{:type :method-call
 :object {:type :field-access :object :self :field "bool"}
 :method "ifo"
 :arguments
 [{:type :lambda
   :parameters []
   :body {:type :binary-operation
          :operator :add
          :left {:type :literal :value 3}
          :right {:type :literal :value 4}}}
  {:type :lambda
   :parameters []
   :body {:type :binary-operation
          :operator :multiply
          :left {:type :literal :value 5}
          :right {:type :literal :value 2}}}]}
```

### 7. Target Commands Representation

Target-specific commands are captured in the IR:

```clojure
{:target-commands
 [{:command "trace"
   :arguments [{:type :field-access :object :self :field "ball"}]
   :target-expansion "console.log"}]}
```

## Implementation Phases

### Phase 1: IR Structure and Schema Translation

1. **Create `ir.cljc`** - Core IR data structures and validation
2. **Create `ast-to-ir.cljc`** - Transform schema AST to IR schema  
3. **Update pipeline** to include IR generation step
4. **Add tests** for IR generation

**Key Decisions:**
- **Observable Infrastructure**: Use code generation instead of base classes to avoid external dependencies and allow any class to be observable
- **Reactive Complexity**: Explicitly track observable classes and subscribers in IR to handle cross-cutting concerns
- **Context Resolution**: Move complex context relationship logic from `haxegen.cljc` into IR
- **Interface Tracking**: Capture interface implementations in IR for proper code generation

### Phase 2: Construction Translation

1. **Extend `ast-to-ir.cljc`** - Transform construction AST to IR construction
2. **Add dependency resolution** logic to IR
3. **Add context wiring** logic to IR
4. **Add collection handling** (arrays, maps) to IR

### Phase 3: Method Translation

1. **Extend `ast-to-ir.cljc`** - Transform reaction phase AST to IR methods
2. **Add expression tree** representation for method bodies
3. **Add block/lambda** representation
4. **Add control structure** representation

### Phase 4: Target Code Generation

1. **Create `ir-to-haxe.cljc`** - Transform IR to Haxe code
2. **Simplify existing `haxegen.cljc`** - Remove semantic analysis, focus on code generation
3. **Add IR validation** - Ensure semantic correctness before code generation

### Phase 5: Enhanced Error Handling

1. **Add semantic validation** at IR level
2. **Improve error messages** with WCHNT-specific context
3. **Add IR debugging** tools

### Phase 6: Multi-Platform Support

1. **Create `ir-to-typescript.cljc`** - Example of additional target
2. **Add target-specific** optimizations
3. **Add platform-specific** feature detection

## Benefits of This Approach

### 1. **Separation of Concerns**
- Semantic analysis separated from code generation
- Platform-specific details isolated in target generators
- Easier to test and validate each phase

### 2. **Better Error Handling**
- Semantic errors caught at IR level
- WCHNT-specific error messages
- Clear separation between syntax and semantic errors

### 3. **Multi-Platform Support**
- Single IR can generate multiple target languages
- Platform-specific optimizations possible
- Easier to add new targets

### 4. **Improved Maintainability**
- Clear data flow through the pipeline
- Easier to debug and modify
- Better testability at each stage

### 5. **Future Extensibility**
- Easy to add new semantic features
- Support for different target platforms
- Potential for IR optimization passes

## File Structure

```
src/wchnt_lang/
├── ir.cljc              # IR data structures and validation
├── ast-to-ir.cljc       # AST to IR transformation
├── ir-to-haxe.cljc      # IR to Haxe code generation
├── ir-to-typescript.cljc # IR to TypeScript code generation (future)
└── haxegen.cljc         # Simplified (existing file)

test/wchnt_lang/
├── ir_test.clj          # IR structure tests
├── ast_to_ir_test.clj   # AST to IR transformation tests
└── ir_to_haxe_test.clj  # IR to Haxe generation tests
```

## Migration Strategy

1. **Parallel Development** - Build IR alongside existing system
2. **Gradual Migration** - Move one phase at a time
3. **Backward Compatibility** - Maintain existing API during transition
4. **Comprehensive Testing** - Ensure IR produces identical output to current system

This IR-based architecture will provide a solid foundation for WCHNT's future development while maintaining the current functionality and improving the overall codebase structure.


