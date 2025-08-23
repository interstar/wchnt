# WCHNT Language Development Plan

## Current State (Updated December 2024)

### Recent Progress: New Architecture Decision - IWCHNTHelper Interface

We have made a **MAJOR ARCHITECTURAL DECISION** to improve the Haxe code generation by introducing a helper interface pattern. This addresses the core issues with map, array, and enum serialization.

#### ✅ New Architecture: IWCHNTHelper Pattern

**Problem Identified:**
- Maps don't have `toConstruction()` methods in Haxe
- Arrays need special serialization logic
- Enums need proper value handling
- Each class was duplicating helper code for complex types

**Solution: Helper Interface Pattern**
1. **`IWCHNTObject`** - Interface that all WCHNT-generated classes implement
   - `toConstruction(depth:Int = 0, helper:IWCHNTHelper):String`

2. **`IWCHNTHelper`** - Helper interface that provides utility methods
   - `arrayToConstruction<T>(arr:Array<T>, depth:Int):String`
   - `mapToConstruction<K,V>(map:Map<K,V>, depth:Int):String`
   - `enumToConstruction(enumValue:Dynamic, depth:Int):String`

**Benefits:**
- **Separation of concerns** - Objects focus on their own serialization, helpers handle complex types
- **Reusability** - The same helper can be used by all objects
- **Extensibility** - Easy to add new helper methods for future types
- **Clean interfaces** - No need to duplicate helper code in every class

**Implementation Plan:**
1. Create the `IWCHNTHelper` interface with helper methods
2. Update `IWCHNTObject` to use the helper parameter
3. Update code generation to use this new pattern
4. Update tests to reflect the new structure

This will solve the map serialization issue and make the code much cleaner!

### Recent Progress: Core Flattening Problem Resolution

We have successfully **RESOLVED** the core flattening problem that was blocking the AST → IR → Haxe pipeline. The two-pass object creation system now works correctly.

#### ✅ Major Fixes Completed

1. **Core Flattening Problem RESOLVED** ✅
   - **Problem**: Second processing pass was overwriting objects created in the first pass
   - **Root Cause**: `process-final-construction` function was not creating the final root object correctly
   - **Solution**: Fixed object ID generation and final object creation logic
   - **Result**: Two-pass flattening now works reliably - first pass extracts nested objects, second pass creates final root object

2. **Test Infrastructure Cleanup** ✅
   - **Removed obsolete test files**: Deleted 4+ test files using old ArgItem structure
   - **Fixed syntax errors**: Resolved bracket matching issues in test files
   - **Updated API module**: Fixed to use new centralized grammar system
   - **Removed debug output**: Cleaned up debug statements corrupting generated Haxe code

3. **Examples Validation** ✅
   - **Success Rate**: 12 out of 13 examples are working perfectly
   - **Working**: All simple examples (enums, maps, arrays, basic constructions)
   - **One Remaining Issue**: Complex nested implicit class inference in `test.wcn`

4. **End-to-End Pipeline** ✅
   - **WCHNT → Haxe**: Basic compilation pipeline is functional
   - **Identified Issues**: Generated Haxe has some structural problems (invalid enum methods, circular references)
   - **Root Cause**: IR still contains AST nodes in arguments instead of normalized values

5. **Comprehensive Unit Test Suite Created** ✅
   - **Added 17 new tests** for Haxe code generation validation
   - **Tests cover**: Basic construction, enum literals, primitive args, object references, array construction
   - **Error detection tests**: AST node validation, circular reference detection
   - **Integration tests**: Full WCHNT to Haxe pipeline validation
   - **Result**: Tests are now catching specific Haxe generation issues early

### Recent Progress: Grammar Consolidation and ArgItem Removal

We have successfully completed a major refactoring to consolidate grammar definitions and remove the redundant `:ArgItem` AST node from the WCHNT language.

#### ✅ Completed Work

1. **Grammar Consolidation**
   - **Created `grammars.cljc`** - Single definitive location for all grammar definitions
   - **Removed redundant files**:
     - `resources/wchnt-grammar.txt` (deleted)
     - `src/wchnt_lang/newparser.clj` (deleted)
   - **Updated `parser.cljc`** - Removed dynamic grammar generation, now uses centralized grammars
   - **Simplified grammar structure** - Single source of truth for schema and construction grammars

2. **ArgItem Removal**
   - **Updated grammar** - Changed `ArgList = (<ArgItem>)*` to `ArgList = (Literal | VariableRef | InnerObjectConstruction | ArrayConstruction | MapConstruction | BlockOrLambda)*`
   - **Removed ArgItem from AST** - Arguments are now direct children of ArgList without wrapper nodes
   - **Updated AST processing** - `extract-args-from-arglist` now returns `(rest arg-list)` directly
   - **Updated test data** - Fixed manually constructed AST structures to match grammar output

3. **AST Processing Consolidation**
   - **Created `ast-processing.cljc`** - Unified module for AST processing logic
   - **Consolidated argument processing** - Single `process-construction-arg` function handles all argument types
   - **Added comprehensive tests** - `ast-processing-test.cljc` covers all argument processing scenarios
   - **Implemented fail-fast** - Clear error messages instead of default values

4. **Code Cleanup**
   - **Removed deprecated test files** - Deleted 15+ debug and redundant test files
   - **Updated existing tests** - Fixed test data to match new grammar structure
   - **Removed ArgItem dependencies** - Updated all code that expected ArgItem nodes

#### 🔍 Key Discoveries

1. **Grammar Output Mismatch**
   - **Problem**: Test data used string literals like `[:IntLiteral "42"]` but grammar produces `[:IntLiteral 42]`
   - **Impact**: Caused `ClassCastException: Long cannot be cast to String` errors
   - **Solution**: Updated all test data to match actual grammar output

2. **AST Processing Pipeline Issues**
   - **Problem**: `process-construction-arg` being called on primitive values instead of AST nodes
   - **Root Cause**: Complex nested processing pipeline converting AST nodes to primitives prematurely
   - **Status**: Identified but not yet resolved

3. **Processing Logic Complexity**
   - **Problem**: The `process-construction` function is designed to convert arguments to IR format, but it's being called on arguments that should remain as AST nodes
   - **Impact**: Arguments are being processed too early in the pipeline
   - **Status**: Requires architectural refactoring

#### 🚧 Outstanding Issues

1. **IR Normalization (Phase 1 Priority)** 🔄
   - **Issue**: IR still contains AST nodes in arguments instead of normalized values
   - **Impact**: Generated Haxe code has structural issues (invalid enum methods, circular references)
   - **Example**: `:args ([:IntLiteral 42])` should be `:args [42]`
   - **Priority**: HIGH - Required for clean Haxe code generation

2. **Complex Implicit Class Inference** 🔄
   - **Issue**: Complex nested constructions without explicit class names fail
   - **Example**: `[10 10 "John"]` inside `[:Array/Player ...]` should infer `Player` class
   - **Impact**: One example (`test.wcn`) fails on this
   - **Priority**: MEDIUM - Affects complex constructions

3. **Haxe Code Generation Quality** 🔄
   - **Issue**: Generated Haxe has invalid enum methods and circular object references
   - **Example**: `public static function` in enums, `new Config(obj1)` where obj1 is already Config
   - **Root Cause**: IR normalization issue above
   - **Priority**: HIGH - Required for functional Haxe output

4. **Test Data Consistency** (LOW)
   - **Issue**: Some documentation files still contain `:ArgItem` references
   - **Files**: `problem.md`, `suggestions.md`, `testout.txt`
   - **Priority**: LOW - Documentation cleanup

#### 🔍 Specific Issues Identified by Unit Tests

The new comprehensive unit test suite has identified specific Haxe generation issues. **Major progress made** - 14 out of 17 tests now passing:

1. **Function Return Type Issue** ✅ **RESOLVED**
   - **Problem**: `generate-construction-factory` returns cargo object `{:success true, :value "..."}` instead of string
   - **Solution**: Refactored function to return string directly, removed unnecessary try/catch and cargo pattern
   - **Result**: All tests expecting string output now pass

2. **Circular Reference Generation** ✅ **RESOLVED**
   - **Problem**: Generated Haxe has `return new Config(obj1)` instead of `return obj1`
   - **Solution**: Simplified `generate-final-statement` function to return existing object variable directly
   - **Result**: Factory functions now return existing objects correctly

3. **Missing String Quotes** ✅ **RESOLVED**
   - **Problem**: String literals generated as `West Ham` instead of `"West Ham"`
   - **Root Cause**: Incorrect `map-indexed` destructuring in `generate-object-assignment`
   - **Solution**: Fixed destructuring from `[arg arg-index]` to `[arg-index arg]`
   - **Result**: String literals now properly quoted in Haxe output

4. **Missing Enum Definitions** ✅ **RESOLVED**
   - **Problem**: Generated code doesn't include enum definitions
   - **Root Cause**: Enum line syntax required quoted values and Haxe enum generation had invalid methods
   - **Solution**: Fixed enum line syntax and corrected Haxe enum generation (removed invalid methods)
   - **Result**: Enums now generated correctly with proper Haxe syntax

5. **AST Node Detection Not Working** 🔄 **PENDING**
   - **Problem**: Error detection tests for AST nodes in IR arguments are not throwing exceptions
   - **Test Failures**: `test-construction-ir-to-haxe-invalid-ast-nodes` and `test-haxe-generation-error-detection`
   - **Impact**: IR normalization validation is not working
   - **Fix Required**: Implement AST node detection in IR validation

6. **Circular Reference Detection Not Working** 🔄 **PENDING**
   - **Problem**: Error detection tests for circular references are not throwing exceptions
   - **Test Failure**: `test-construction-ir-to-haxe-circular-reference-detection`
   - **Impact**: Circular reference validation is not working
   - **Fix Required**: Implement circular reference detection in IR validation

#### 📋 Next Steps

1. **Immediate (High Priority)** - Complete Unit Test Suite
   - **Implement AST node detection**: Add validation to detect AST nodes in IR arguments
   - **Implement circular reference detection**: Add validation to detect circular object references
   - **Status**: 14/17 tests passing, 3 validation tests remaining

2. **Short Term (Medium Priority)** - IR Normalization
   - **Implement Phase 1 from plan**: Convert AST nodes in IR arguments to simple values
   - **Fix complex implicit class inference**: Handle nested constructions without explicit class names
   - **Improve error messages**: Better diagnostics for construction failures
   - **Test end-to-end pipeline**: Ensure WCHNT → Haxe → JavaScript works completely

3. **Medium Term (Medium Priority)** - Quality Improvements
   - **Documentation cleanup**: Update remaining files to remove ArgItem references
   - **Performance optimization**: Optimize the flattening and IR generation
   - **Grammar validation**: Add comprehensive grammar validation tests

4. **Long Term (Low Priority)** - Optimization
   - **Multi-platform support**: Extend beyond Haxe to other targets
   - **Advanced optimizations**: IR optimization passes, dead code elimination

### Architecture Status

#### Current Pipeline
```
WCHNT Source → AST (no ArgItem) → IR → Haxe Code
```

#### Grammar Structure
- **Schema Grammar**: Defined in `grammars.cljc`
- **Construction Grammar**: Defined in `grammars.cljc`
- **Parser**: Uses centralized grammar definitions
- **AST Structure**: Arguments are direct children of ArgList

#### Processing Modules
- **`grammars.cljc`**: Centralized grammar definitions
- **`parser.cljc`**: Simplified parser using centralized grammars
- **`ast-processing.cljc`**: Unified AST processing logic
- **`ast-to-ir.cljc`**: AST to IR conversion (needs refactoring)
- **`ir-to-haxe.cljc`**: IR to Haxe code generation

### Success Metrics

#### ✅ Achieved
- **Grammar consolidation** - Single source of truth for all grammars
- **ArgItem removal** - Cleaner AST structure without redundant wrapper nodes
- **Code cleanup** - Removed 15+ redundant files and deprecated functionality
- **Test infrastructure** - Comprehensive test coverage for AST processing
- **Fail-fast implementation** - Clear error messages instead of default values

#### 🎯 Target
- **All tests passing** - Complete test suite execution (17 new Haxe generation tests)
- **Full pipeline working** - WCHNT source to Haxe code generation
- **Clean architecture** - Clear separation between AST processing and IR conversion
- **Documentation updated** - All documentation reflects current grammar structure
- **IR normalization complete** - All AST nodes converted to normalized values in IR

### Lessons Learned

1. **Grammar Consolidation Benefits**
   - Single source of truth eliminates inconsistencies
   - Easier maintenance and updates
   - Clearer separation of concerns

2. **AST Node Simplification**
   - Removing redundant nodes improves processing efficiency
   - Cleaner data structures are easier to work with
   - Better error messages with simpler structures

3. **Test Data Alignment**
   - Test data must match actual grammar output
   - String vs numeric literal mismatches cause runtime errors
   - Comprehensive test coverage catches these issues early

4. **Processing Pipeline Complexity**
   - Early processing of arguments can cause downstream issues
   - Clear separation between AST processing and IR conversion is essential
   - Fail-fast approach provides better debugging information

5. **Unit Test Validation**
   - Comprehensive unit tests catch issues early in the development process
   - Tests should validate both success cases and error conditions
   - Integration tests ensure end-to-end pipeline functionality
   - Test failures provide clear guidance on what needs to be fixed

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

## Current IR Structure Problems and Fixes

### Analysis of Current IR Issues

The current AST→IR conversion is producing an IR structure that's **too close to the AST** rather than being a **normalized intermediate representation** suitable for code generation. This is causing problems in the IR→Haxe conversion.

#### Current IR Problems:

1. **Arguments are AST nodes, not normalized values**
   ```clojure
   ;; Current (problematic):
   :args [[:ArgItem [:StringLiteral "Bob"]] [:ArgItem [:IntLiteral "100"]]]
   
   ;; Should be:
   :args ["Bob" 100]
   ```

2. **AST nodes still present in IR**
   ```clojure
   ;; Current (unnecessary):
   :ast [:InnerObjectConstruction [:ClassName "Player"] [:ArgList ...]]
   
   ;; Should be: (no AST field needed)
   ```

3. **Variable mappings are redundant**
   ```clojure
   ;; Current (redundant):
   :variable-mappings {"obj1" "obj1", "obj2" "obj2"}
   
   ;; Should be (meaningful):
   :variable-mappings {"playArea" "obj1", "ball" "obj2"}
   ```

4. **Missing final construction objects**
   - IR only has nested objects (`obj1`, `obj2`) 
   - Missing the final Game object that should be returned
   - No clear indication of which object is the return value

5. **Complex nested object processing**
   - The flattening logic is complex and error-prone
   - Object creation order is not deterministic
   - Variable references are not properly resolved

#### Root Cause:
The current `construction-ast-to-ir` function is trying to preserve too much AST information instead of creating a clean, normalized IR suitable for code generation.

### IR Structure Fixes Plan

#### Phase 1: Normalize IR Object Structure

**Goal:** Create a clean, normalized IR structure where all arguments are simple values, not AST nodes.

**Changes:**
1. **Normalize all arguments** - Convert AST nodes to simple values
2. **Remove AST fields** - Eliminate unnecessary AST data from IR
3. **Simplify object structure** - Each object should have: `:type`, `:class-name`, `:args`, `:index`
4. **Ensure all objects are created** - Both nested and final objects should be in the IR

**Target IR Structure:**
```clojure
{:return-object "obj3"
 :objects {"obj1" {:type :object
                   :class-name "Player"
                   :args ["Bob" 100]
                   :index 0}
          "obj2" {:type :object
                  :class-name "Rect"
                  :args [10 20 100 200]
                  :index 1}
          "obj3" {:type :object
                  :class-name "Game"
                  :args ["obj1" "obj2"]
                  :index 2}}
 :variable-mappings {"playArea" "obj1", "ball" "obj2"}}
```

#### Phase 2: Fix Object Creation Logic

**Goal:** Ensure all objects (nested and final) are properly created and stored in the IR.

**Changes:**
1. **Fix nested object processing** - Ensure InnerObjectConstruction nodes are properly flattened
2. **Fix final object creation** - Create the root object that will be returned
3. **Fix object ordering** - Ensure objects are created in dependency order
4. **Fix variable resolution** - Properly resolve variable references to object IDs

**Implementation Strategy:**
1. **Two-pass approach** - First pass creates all objects, second pass resolves references
2. **Consistent naming** - Use `objN` for all objects (no distinction between nested/final)
3. **Clear dependency tracking** - Track which objects depend on which others

#### Phase 3: Improve Argument Processing

**Goal:** Convert all AST argument nodes to simple, normalized values.

**Changes:**
1. **Normalize primitive values** - `[:StringLiteral "Bob"]` → `"Bob"`
2. **Normalize object references** - `[:VariableRef "obj1"]` → `"obj1"`
3. **Handle complex arguments** - Arrays, maps, nested objects
4. **Validate argument types** - Ensure arguments match expected types from schema

**Implementation:**
```clojure
(defn normalize-arg [arg]
  (cond
    (ast-utils/node-type? arg :StringLiteral) (second arg)
    (ast-utils/node-type? arg :IntLiteral) (Integer/parseInt (second arg))
    (ast-utils/node-type? arg :VariableRef) (second arg)
    (ast-utils/node-type? arg :ArrayConstruction) (process-array-construction arg)
    (ast-utils/node-type? arg :MapConstruction) (process-map-construction arg)
    :else (throw (ex-info "Unknown argument type" {:arg arg}))))
```

#### Phase 4: Fix Variable Mappings

**Goal:** Create meaningful variable mappings that map WCHNT variable names to object IDs.

**Changes:**
1. **Track assignment variables** - When `var = expression` is processed, map `var` to the created object ID
2. **Handle nested object variables** - Map flattened nested objects to their variable references
3. **Remove redundant mappings** - Don't map object IDs to themselves
4. **Support complex assignments** - Handle array assignments, map assignments, etc.

**Example:**
```clojure
;; WCHNT: players = [:Array/Player [10 10 "John"] [50 70 "Sally"]]
;; IR: {:variable-mappings {"players" "obj1"}}
;; Haxe: var obj1 = [obj2, obj3]; // where obj2, obj3 are the Player objects
```

#### Phase 5: Update IR Schema Validation

**Goal:** Ensure the IR schema properly validates the normalized structure.

**Changes:**
1. **Update ConstructionObjectSchema** - Ensure it validates normalized arguments
2. **Add argument validation** - Validate that arguments are simple values, not AST nodes
3. **Add object completeness validation** - Ensure all referenced objects exist
4. **Add dependency validation** - Ensure no circular dependencies

**Schema Updates:**
```clojure
(def ConstructionObjectSchema
  [:map
   [:type [:enum :object :primitive :array :map :variable]]
   [:class-name string?]
   [:args [:sequential [:or string? number? boolean?]]] ; Simple values only
   [:index int?]])
```

#### Phase 6: Update Tests

**Goal:** Update unit tests to expect the new normalized IR structure.

**Changes:**
1. **Update test expectations** - Change tests to expect normalized arguments
2. **Add new test cases** - Test complex nested constructions
3. **Test error cases** - Test invalid argument types, missing objects, etc.
4. **Test variable mappings** - Test that variable mappings are correct

#### Phase 7: Update IR→Haxe Conversion

**Goal:** Simplify the IR→Haxe conversion to work with the normalized IR.

**Changes:**
1. **Simplify argument processing** - No need to parse AST nodes in arguments
2. **Simplify object creation** - Direct mapping from IR objects to Haxe variables
3. **Simplify variable resolution** - Use the normalized variable mappings
4. **Remove complex logic** - Eliminate the complex argument parsing logic

**Benefits:**
- **Cleaner code generation** - No AST parsing in code generation
- **Better error messages** - Errors at IR level, not code generation level
- **Easier debugging** - IR structure is human-readable
- **Better testability** - Can test IR structure independently

### Implementation Order

1. **Start with Phase 1** - Normalize IR structure (most critical)
2. **Phase 2** - Fix object creation logic
3. **Phase 3** - Improve argument processing
4. **Phase 4** - Fix variable mappings
5. **Phase 5** - Update schema validation
6. **Phase 6** - Update tests
7. **Phase 7** - Update IR→Haxe conversion

### Success Criteria

- **All tests pass** with the new IR structure
- **go.sh script works** - Full pipeline from WCHNT to JavaScript execution
- **IR structure is clean** - No AST nodes in arguments, clear object structure
- **Code generation is simpler** - IR→Haxe conversion is straightforward
- **Error messages are clear** - Errors point to WCHNT source, not internal structures

This systematic approach will transform the IR from an AST-like structure to a proper intermediate representation suitable for code generation.

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


## Comments from Another AI (Gemini)

The plan outlined in "Current IR Structure Problems and Fixes" is excellent and addresses the core issues of moving from an AST-like structure to a true, normalized IR for code generation. Here are a few thoughts that reinforce and build upon this direction, particularly concerning the construction phase.

### On Flattening the Construction Graph

The central challenge is transforming the nested, tree-like AST of a construction into a flat, ordered list of instantiation commands for the Haxe factory function. The AST represents *what* to build, while the target IR should represent *how* to build it, step-by-step.

The proposed two-pass approach is the ideal pattern for this:

1.  **Pass 1: Declaration & Identification.** Walk the entire construction AST (including multi-step assignments). The goal is to identify *every single object* that needs to be created, whether it's a deeply nested component or assigned to a variable. Each object is given a unique internal ID (e.g., `obj1`, `obj2`) and added to a flat map or table. At this stage, its constructor arguments might still be unresolved references (e.g., the AST node for a variable name like `players`, or the ID of another object like `obj5`). The output of this pass is a complete "catalogue" of all parts needed for the final assemblage.

2.  **Pass 2: Resolution & Dependency-Graphing.** Re-visit each object in the flat catalogue. Now, resolve its arguments. An argument that was a variable name (`players`) is replaced with the unique ID of the object it refers to (looked up in the `variable-mappings` table). An argument that was another nested object construction is replaced with that nested object's unique ID. During this pass, you build the dependency graph: if `Game` (`obj3`) has an argument that resolves to `Player` (`obj1`), then `obj3` depends on `obj1`.

### The Importance of the `variable-mappings`

The `variable-mappings` table is the symbol table for the construction scope. It's the critical link between the user-defined names in the WCHNT source (e.g., `people = [...]`) and the compiler-generated internal IDs (`obj10`). Getting this right, as outlined in Phase 4 of the plan, is what enables the powerful "let-binding" feature of multi-step constructions.

### The Final IR as a "Build Script"

After the two passes, the construction IR should be a simple, flat list of "instructions" that can be executed in order by the Haxe code generator. Sorting this list based on the dependency graph created in Pass 2 gives you the exact order of `var o1 = new...`, `var o2 = new...` statements.

The proposed target IR structure correctly captures this: a map of all objects and a clear return value. The code generator's job becomes a straightforward iteration over the sorted objects, a'nd emitting the corresponding Haxe `new` expression for each one. This completely separates the complex analysis (AST traversal, symbol resolution, dependency sorting) from the simple task of writing strings of code.


