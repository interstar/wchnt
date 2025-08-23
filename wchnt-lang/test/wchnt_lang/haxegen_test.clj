(ns wchnt-lang.haxegen-test
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [wchnt-lang.haxegen :as haxegen]
            [wchnt-lang.ir-to-haxe :as ir-to-haxe]
            [wchnt-lang.schema :as schema]
            [wchnt-lang.parser :as parser]
                         [wchnt-lang.compiler :as compiler]
             [wchnt-lang.pipeline :as p]))

(deftest test-type-ast->haxe-type

  
  (testing "leaves String unchanged"
    (is (= "String" (haxegen/type-ast->haxe-type "String"))))
  
  (testing "handles Array types"
    (is (= "Array<Int>" (haxegen/type-ast->haxe-type [:ArrayType [:Type "Int"]]))))
  
  (testing "handles Map types"
    (is (= "Map<String, Int>" (haxegen/type-ast->haxe-type [:MapType [:KeyType "String"] [:ValType "Int"]])))))

(deftest test-generate-to-construction-parts
  (testing "generates correct parts for primitive types"
    (let [elements [{:name "x" :type "Int"}
                    {:name "name" :type "String"}
                    {:name "active" :type "Bool"}]]
      (is (= ["this.x" "\"\" + this.name + \"\"" "this.active"]
             (haxegen/generate-to-construction-parts elements)))))
  
  (testing "generates correct parts for complex types"
    (let [elements [{:name "items" :type [:ArrayType [:Type "String"]]}
                    {:name "config" :type [:MapType [:KeyType "String"] [:ValType "Int"]]}]]
      (is (= ["ArrayExtensions.toConstruction(this.items, depth + 1)" "this.config.toConstruction(depth + 1)"]
             (haxegen/generate-to-construction-parts elements))))))

(deftest test-compile-to-haxe-with-primitives
  (testing "compiles schema with Int fields correctly"
    (let [input "Rect = Int/x Int/y Int/width Int/height"
          ast-result (wchnt-lang.parser/schema-wchnt->schema-ast input)
          ast (:value ast-result)]
      (let [result (haxegen/schema-ast->haxe ast)]
        (let [haxe-code result]
        (is (str/includes? haxe-code "public var x: Int;"))
        (is (str/includes? haxe-code "public var y: Int;"))
        (is (str/includes? haxe-code "public var width: Int;"))
        (is (str/includes? haxe-code "public var height: Int;"))
        ;; Check that toConstruction doesn't call .toConstruction() on Int fields
        (is (str/includes? haxe-code "this.x"))
        (is (str/includes? haxe-code "this.y"))
          (is (not (str/includes? haxe-code "this.x.toConstruction")))))))
  
  (testing "compiles schema with String fields correctly"
    (let [input "Person = String/name String/email"
          ast-result (wchnt-lang.parser/schema-wchnt->schema-ast input)
          ast (:value ast-result)
          result (haxegen/schema-ast->haxe ast)]
      (let [haxe-code result]
        (is (str/includes? haxe-code "public var name: String;"))
        (is (str/includes? haxe-code "public var email: String;"))
        ;; Check that toConstruction wraps String fields in quotes
        (is (str/includes? haxe-code "\"\" + this.name + \"\""))))))

(deftest test-compile-to-haxe-with-arrays
  (testing "compiles schema with arrays and includes ArrayExtensions"
    (let [input "School = [Person]/students"
          ast-result (wchnt-lang.parser/schema-wchnt->schema-ast input)
          ast (:value ast-result)
          result (haxegen/schema-ast->haxe ast)]
      (let [haxe-code result]
        ;; Should have both the class and the ArrayExtensions
        (is (str/includes? haxe-code "class ArrayExtensions"))))))

(deftest test-find-all-nodes
  (testing "find-all-nodes finds nodes at different depths"
    (let [tree [:Root
                [:Level1 [:Target "value1"]]
                [:Level1 [:Level2 [:Target "value2"]]]
                [:Level1 [:Level2 [:Level3 [:Target "value3"]]]]]
          results (haxegen/find-all-nodes :Target tree)]
      (is (= 3 (count results)) "Should find all 3 Target nodes")
      (is (= "value1" (second (first results))) "Should find first value")
      (is (= "value2" (second (second results))) "Should find second value")
      (is (= "value3" (second (nth results 2))) "Should find third value")))

  (testing "find-all-nodes handles empty trees"
    (is (= [] (haxegen/find-all-nodes :Target [])) "Empty vector should return empty")
    (is (= [] (haxegen/find-all-nodes :Target nil)) "Nil should return empty")
    (is (= [] (haxegen/find-all-nodes :Target "string")) "String should return empty"))

  (testing "find-all-nodes finds nodes in sequences"
    (let [tree [:Root
                [:List [:Target "a"] [:Target "b"]]
                [:Nested [:List [:Target "c"]]]]
          results (haxegen/find-all-nodes :Target tree)]
      (is (= 3 (count results)) "Should find all 3 Target nodes in sequences")
      (is (= ["a" "b" "c"] (map second results)) "Should find correct values")))

  (testing "find-all-nodes handles ArrayType and MapType special cases"
    (let [tree [:Root
                [:ArrayType [:Target "array-value"]]
                [:MapType [:Target "map-value"]]]
          results (haxegen/find-all-nodes :Target tree)]
      (is (= 2 (count results)) "Should find both Target nodes in special types")
      (is (= ["array-value" "map-value"] (map second results)) "Should find correct values")))

  (testing "find-all-nodes returns empty when no matches"
    (let [tree [:Root [:Level1 [:Level2 "value"]]]]
      (is (= [] (haxegen/find-all-nodes :Target tree)) "Should return empty when no matches"))))
;; =============================================================================
;; Construction IR to Haxe Generation Tests
;; =============================================================================

(deftest test-construction-ir-to-haxe-basic
  (testing "generates valid Haxe for simple construction"
    (let [construction-ir {:root-class "Config"
                          :factory-name "configFactory"
                          :objects {"obj1" {:type :object
                                           :class-name "Config"
                                           :args ["Local"]
                                           :index 0}}
                          :return-object "obj1"
                          :variable-mappings {}}
          schema-ir {:assemblages [{:name "Config"
                                   :components [{:component-name "environment" :type-name "BuildType"}]}]
                     :enums [{:name "BuildType" :values ["Dev" "Local" "Deploy"]}]}
          result (ir-to-haxe/generate-construction-factory construction-ir schema-ir)]
      
      ;; Should generate valid Haxe code
      (is (string? result))
      (is (str/includes? result "public static function configFactory()"))
      (is (str/includes? result "return obj1"))
      
      ;; Should NOT have invalid enum methods
      (is (not (str/includes? result "public static function DevToConstruction")))
      (is (not (str/includes? result "public static function LocalToConstruction")))
      (is (not (str/includes? result "public static function DeployToConstruction")))
      
      ;; Should NOT have circular references
      (is (not (str/includes? result "new Config(obj1)")))
      (is (not (str/includes? result "return new Config(obj1)"))))))

(deftest test-construction-ir-to-haxe-enum-literals
  (testing "generates valid Haxe for enum literals"
    (let [construction-ir {:root-class "Config"
                          :factory-name "configFactory"
                          :objects {"obj1" {:type :object
                                           :class-name "Config"
                                           :args ["Local"]
                                           :index 0}}
                          :return-object "obj1"
                          :variable-mappings {}}
          schema-ir {:assemblages [{:name "Config"
                                   :components [{:component-name "environment" :type-name "BuildType"}]}]
                     :enums [{:name "BuildType" :values ["Dev" "Local" "Deploy"]}]}
          result (ir-to-haxe/generate-construction-factory construction-ir schema-ir)]
      
      ;; Should use enum values directly, not call methods
      (is (str/includes? result "Local"))
      (is (not (str/includes? result "LocalToConstruction")))
      (is (not (str/includes? result "Local()"))))))

(deftest test-construction-ir-to-haxe-primitive-args
  (testing "generates valid Haxe for primitive arguments"
    (let [construction-ir {:root-class "Rect"
                          :factory-name "rectFactory"
                          :objects {"obj1" {:type :object
                                           :class-name "Rect"
                                           :args [0 0 100 200]  ; Simple integers
                                           :index 0}}
                          :return-object "obj1"
                          :variable-mappings {}}
          schema-ir {:assemblages [{:name "Rect"
                                   :components [{:component-name "x" :type-name "Int"}
                                              {:component-name "y" :type-name "Int"}
                                              {:component-name "width" :type-name "Int"}
                                              {:component-name "height" :type-name "Int"}]}]}
          result (ir-to-haxe/generate-construction-factory construction-ir schema-ir)]
      
      ;; Should use primitive values directly
      (is (str/includes? result "new Rect(0, 0, 100, 200)"))
      (is (not (str/includes? result "[:IntLiteral")))
      (is (not (str/includes? result "[:StringLiteral"))))))

(deftest test-construction-ir-to-haxe-object-references
  (testing "generates valid Haxe for object references"
    (let [construction-ir {:root-class "Game"
                          :factory-name "gameFactory"
                          :objects {"obj1" {:type :object
                                           :class-name "Ball"
                                           :args [100 100 5]
                                           :index 0}
                                   "obj2" {:type :object
                                           :class-name "Game"
                                           :args ["obj1"]  ; Reference to obj1
                                           :index 1}}
                          :return-object "obj2"
                          :variable-mappings {}}
          schema-ir {:assemblages [{:name "Game"
                                   :components [{:component-name "ball" :type-name "Ball"}
                                              {:component-name "paddle" :type-name "Paddle"}]}
                                  {:name "Ball"
                                   :components [{:component-name "x" :type-name "Int"}
                                              {:component-name "y" :type-name "Int"}
                                              {:component-name "radius" :type-name "Int"}]}
                                  {:name "Paddle"
                                   :components [{:component-name "x" :type-name "Int"}
                                              {:component-name "y" :type-name "Int"}
                                              {:component-name "width" :type-name "Int"}
                                              {:component-name "height" :type-name "Int"}]}]}
          result (ir-to-haxe/generate-construction-factory construction-ir schema-ir)]
      
      ;; Should reference objects by variable name
      (is (str/includes? result "var obj1 = new Ball(100, 100, 5)"))
      (is (str/includes? result "var obj2 = new Game(obj1)"))
      (is (str/includes? result "return obj2"))
      
      ;; Should NOT have circular references
      (is (not (str/includes? result "new Game(obj2)")))
      (is (not (str/includes? result "return new Game(obj2)"))))))

(deftest test-construction-ir-to-haxe-array-construction
  (testing "generates valid Haxe for array construction"
    (let [construction-ir {:root-class "Team"
                          :factory-name "teamFactory"
                          :objects {"obj1" {:type :object
                                           :class-name "Player"
                                           :args [10 10 "John"]
                                           :index 0}
                                   "obj2" {:type :object
                                           :class-name "Player"
                                           :args [50 70 "Sally"]
                                           :index 1}
                                   "obj3" {:type :array
                                           :class-name "Player"
                                           :args ["obj1" "obj2"]  ; Array of object references
                                           :index 2}
                                   "obj4" {:type :object
                                           :class-name "Team"
                                           :args ["West Ham" "obj3"]
                                           :index 3}}
                          :return-object "obj4"
                          :variable-mappings {}}
          schema-ir {:assemblages [{:name "Team"
                                   :components [{:component-name "name" :type-name "String"}
                                              {:component-name "players" :type-name "Array<Player>"}]}
                                  {:name "Player"
                                   :components [{:component-name "x" :type-name "Int"}
                                              {:component-name "y" :type-name "Int"}
                                              {:component-name "name" :type-name "String"}]}]}
          result (ir-to-haxe/generate-construction-factory construction-ir schema-ir)]
      
      ;; Should create array with object references
      (is (str/includes? result "var obj3 = [obj1, obj2]"))
      (is (str/includes? result "var obj4 = new Team(\"West Ham\", obj3)"))
      
      ;; Should NOT have AST nodes in array
      (is (not (str/includes? result "[:VariableRef")))
      (is (not (str/includes? result "[:StringLiteral"))))))



(deftest test-construction-ir-to-haxe-enum-method-generation
  (testing "does not generate invalid enum methods"
    (let [construction-ir {:root-class "Config"
                          :factory-name "configFactory"
                          :objects {"obj1" {:type :object
                                           :class-name "Config"
                                           :args ["Local"]
                                           :index 0}}
                          :return-object "obj1"
                          :variable-mappings {}}
          schema-ir {:assemblages [{:name "Config"
                                   :components [{:component-name "environment" :type-name "BuildType"}]}]
                     :enums [{:name "BuildType" :values ["Dev" "Local" "Deploy"]}]}
          result (ir-to-haxe/generate-construction-factory construction-ir schema-ir)]
      
      ;; Should NOT generate static methods in enums
      (is (not (str/includes? result "public static function DevToConstruction")))
      (is (not (str/includes? result "public static function LocalToConstruction")))
      (is (not (str/includes? result "public static function DeployToConstruction")))
      
      ;; Should use enum values directly
      (is (str/includes? result "Local")))))

(deftest test-construction-ir-to-haxe-factory-return
  (testing "generates correct factory return statements"
    (let [construction-ir {:root-class "Game"
                          :factory-name "gameFactory"
                          :objects {"obj1" {:type :object
                                           :class-name "Game"
                                           :args ["obj2" "obj3"]
                                           :index 0}
                                   "obj2" {:type :object
                                           :class-name "Ball"
                                           :args [100 100 5]
                                           :index 1}
                                   "obj3" {:type :object
                                           :class-name "Paddle"
                                           :args [50 50 20 80]
                                           :index 2}}
                          :return-object "obj1"
                          :variable-mappings {}}
          schema-ir {:assemblages [{:name "Game"
                                   :components [{:component-name "ball" :type-name "Ball"}
                                              {:component-name "paddle" :type-name "Paddle"}]}
                                  {:name "Ball"
                                   :components [{:component-name "x" :type-name "Int"}
                                              {:component-name "y" :type-name "Int"}
                                              {:component-name "radius" :type-name "Int"}]}
                                  {:name "Paddle"
                                   :components [{:component-name "x" :type-name "Int"}
                                              {:component-name "y" :type-name "Int"}
                                              {:component-name "width" :type-name "Int"}
                                              {:component-name "height" :type-name "Int"}]}]}
          result (ir-to-haxe/generate-construction-factory construction-ir schema-ir)]
      
      ;; Should return the correct object
      (is (str/includes? result "return obj1"))
      
      ;; Should NOT have circular return
      (is (not (str/includes? result "return new Game(obj1)")))
      (is (not (str/includes? result "return new Game(obj2)"))))))

;; =============================================================================
;; Integration Tests: Full WCHNT to Haxe Pipeline
;; =============================================================================

(deftest test-full-wchnt-to-haxe-pipeline
  (testing "complete WCHNT source to Haxe compilation"
    (let [wchnt-source "## Schema

```
BuildType = \"Local\" | \"Remote\" | \"Production\"
Config = BuildType/environment
```

## Construction

```
[:Config Local]
```"
          result (compiler/compile wchnt-source)]
      
      ;; Should compile successfully
      (is (:success result))
      
      ;; Print error if compilation failed
      (when-not (:success result)
        (println "Compilation failed with error:" (:error result)))
      
      ;; Should generate valid Haxe code
      (let [classes-code (:classes (:value result))
            factory-code (:factory (:value result))
            main-class-code (:main-class (:value result))]
        (is (string? classes-code))
        (is (str/includes? classes-code "class Config"))
        (is (str/includes? classes-code "enum BuildType"))
        
        ;; Should NOT have methods in enum (Haxe enums can't have methods)
        (is (not (str/includes? classes-code "public static function LocalToConstruction")))
        
        ;; Should have valid factory
        (is (str/includes? factory-code "public static function configFactory()"))
        (is (str/includes? factory-code "return obj1"))))))

(deftest test-full-wchnt-to-haxe-pipeline-with-primitives
  (testing "WCHNT with primitive arguments to Haxe"
    (let [wchnt-source "## Schema

```
Rect = Int/x Int/y Int/width Int/height
```

## Construction

```
[:Rect 0 0 100 200]
```"
          result (compiler/compile wchnt-source)]
      
      ;; Should compile successfully
      (is (:success result))
      
      ;; Should generate valid Haxe code
      (let [classes-code (:classes (:value result))
            factory-code (:factory (:value result))]
        (is (string? classes-code))
        (is (str/includes? classes-code "class Rect"))
        (is (str/includes? classes-code "public var x: Int"))
        
        ;; Should use primitive values directly
        (is (str/includes? factory-code "new Rect(0, 0, 100, 200)"))
        (is (not (str/includes? factory-code "[:IntLiteral")))))))

;; =============================================================================
;; Haxe Syntax Validation Tests
;; =============================================================================

(deftest test-haxe-map-syntax
  "Test that Map types are generated with correct Haxe syntax"
  (testing "Map with enum keys should use proper Haxe syntax"
    (let [wchnt-content "## Schema

```
Direction = \"Up\" | \"Down\" | \"Left\" | \"Right\"
Config = {Direction : String}/moves
```

## Construction

```
controls = [:Map/{Direction:String} Up:\"jump\", Down:\"crouch\", Left:\"left\" Right:\"right\"].
[:Config controls]
```"
          cargo-result (compiler/compile wchnt-content)]
      
      (is (p/is-cargo? cargo-result))
      (if (:success cargo-result)
        (let [result (:value cargo-result)
              classes-code (:classes result)
              main-class-code (:main-class result)]
          ;; Should generate proper Map syntax
          (is (str/includes? classes-code "public var moves: Map<Direction, String>"))
          ;; Should NOT have Main class in classes (user didn't define one)
          (is (= 0 (count (re-seq #"class Main" classes-code))))
          ;; Should have Main class in main-class (we generate one)
          (is (str/includes? main-class-code "class Main"))
          ;; Should have proper enum definition
          (is (str/includes? classes-code "enum Direction")))
        (do
          (println "Map syntax test failed:")
          (println "Error:" (:error cargo-result))
          (is false "Map syntax should work"))))))

(deftest test-haxe-enum-value-handling
  "Test that enum values are handled correctly in factory functions"
  (testing "Enum values should be passed as enum instances, not strings"
    (let [wchnt-content "## Schema

```
BuildType = \"Dev\" | \"Local\" | \"Deploy\"
Config = BuildType/environment
```

## Construction

```
[:Config Local]
```"
          cargo-result (compiler/compile wchnt-content)]
      
      (is (p/is-cargo? cargo-result))
      (if (:success cargo-result)
        (let [result (:value cargo-result)
              factory-code (:factory result)]
          ;; Should pass enum value directly, not as string
          (is (str/includes? factory-code "new Config(Local)"))
          ;; Should NOT pass enum value as string
          (is (not (str/includes? factory-code "new Config(\"Local\""))))
        (do
          (println "Enum value handling test failed:")
          (println "Error:" (:error cargo-result))
          (is false "Enum value handling should work"))))))

(deftest test-haxe-array-constructor-syntax
  "Test that Array constructors use correct Haxe syntax"
  (testing "Array constructor should use proper Haxe syntax"
    (let [wchnt-content "## Schema

```
StringList = [String]/xs
```

## Construction

```
[:StringList [\"hello\" \"world\" \"test\"]]
```"
          cargo-result (compiler/compile wchnt-content)]
      
      (is (p/is-cargo? cargo-result))
      (if (:success cargo-result)
        (let [result (:value cargo-result)
              factory-code (:factory result)]
          ;; Should use proper Haxe array syntax
          (is (str/includes? factory-code "[\"hello\", \"world\", \"test\"]"))
          ;; Should NOT use new Array<String> constructor with too many args
          (is (not (str/includes? factory-code "new Array<String>(\"hello\", \"world\", \"test\""))))
        (do
          (println "Array constructor syntax test failed:")
          (println "Error:" (:error cargo-result))
          (is false "Array constructor syntax should work"))))))

(deftest test-haxe-enum-to-construction-removal
  "Test that enums do NOT have toConstruction methods"
  (testing "Enums should not have toConstruction methods in Haxe"
    (let [wchnt-content "## Schema

```
BuildType = \"Dev\" | \"Local\" | \"Deploy\"
Config = BuildType/environment
```

## Construction

```
[:Config Local]
```"
          cargo-result (compiler/compile wchnt-content)]
      
      (is (p/is-cargo? cargo-result))
      (if (:success cargo-result)
        (let [result (:value cargo-result)
              classes-code (:classes result)]
          ;; Should NOT call toConstruction on enum
          (is (not (str/includes? classes-code "this.environment.toConstruction")))
          ;; Should handle enum values differently in toConstruction
          (is (str/includes? classes-code "this.environment")))
        (do
          (println "Enum toConstruction removal test failed:")
          (println "Error:" (:error cargo-result))
          (is false "Enum toConstruction removal should work"))))))

(deftest test-haxe-no-duplicate-class-definitions
  "Test that no duplicate class definitions are generated"
  (testing "Should have exactly one Main class when user defines Main"
    (let [wchnt-content "## Schema

```
Main = String/hello Config
Direction = \"Up\" | \"Down\" | \"Left\" | \"Right\"
Config = {Direction : String}/moves
```

## Construction

```
controls = [:Map/{Direction:String} Up:\"jump\", Down:\"crouch\", Left:\"left\" Right:\"right\"].
[:Main \"Hello\" [:Config controls]]
```"
          cargo-result (compiler/compile wchnt-content)]
      
      (is (p/is-cargo? cargo-result))
      (if (:success cargo-result)
        (let [result (:value cargo-result)
              classes-code (:classes result)
              main-class-code (:main-class result)]
          ;; Should have exactly one Main class definition in classes
          (is (= 1 (count (re-seq #"class Main" classes-code))))
          ;; Should NOT have a separate Main class in main-class (user controls their own Main)
          (is (str/blank? main-class-code)))
        (do
          (println "User-defined Main class test failed:")
          (println "Error:" (:error cargo-result))
          (is false "Should handle user-defined Main class correctly"))))))
  
  (testing "Should have exactly one Main class when user doesn't define Main"
    (let [wchnt-content "## Schema

```
Direction = \"Up\" | \"Down\" | \"Left\" | \"Right\"
Config = {Direction : String}/moves
```

## Construction

```
controls = [:Map/{Direction:String} Up:\"jump\", Down:\"crouch\", Left:\"left\" Right:\"right\"].
[:Config controls]
```"
          cargo-result (compiler/compile wchnt-content)]
      
      (is (p/is-cargo? cargo-result))
      (if (:success cargo-result)
        (let [result (:value cargo-result)
              classes-code (:classes result)
              main-class-code (:main-class result)]
          ;; Should have no Main class in classes (user didn't define one)
          (is (= 0 (count (re-seq #"class Main" classes-code))))
          ;; Should have a Main class in main-class (we generate one)
          (is (str/includes? main-class-code "class Main")))
        (do
          (println "Generated Main class test failed:")
          (println "Error:" (:error cargo-result))
          (is false "Should generate Main class when user doesn't define one")))))

(deftest test-haxe-proper-main-class-generation
  "Test that Main class is properly generated with factory and main methods"
  (testing "Main class should have both factory and main methods"
    (let [wchnt-content "## Schema

```
Config = String/environment
```

## Construction

```
[:Config \"Local\"]
```"
          cargo-result (compiler/compile wchnt-content)]
      
      (is (p/is-cargo? cargo-result))
      (if (:success cargo-result)
        (let [result (:value cargo-result)
              main-class-code (:main-class result)]
          ;; Should have factory method
          (is (str/includes? main-class-code "public static function configFactory"))
          ;; Should have main method
          (is (str/includes? main-class-code "public static function main"))
          ;; Should call factory in main
          (is (str/includes? main-class-code "var assemblage = configFactory()")))
        (do
          (println "Main class generation test failed:")
          (println "Error:" (:error cargo-result))
          (is false "Main class should be properly generated"))))))

(deftest test-haxe-array-with-variable-references
  "Test that arrays with variable references generate correct Haxe syntax"
  (testing "Array with variable references should use proper Haxe syntax"
    (let [wchnt-content "## Schema

```
Book = String/title String/author
DB = [Book]/books
```

## Construction

```
[:DB 
  [:Array/Book
     [:Book \"Pride and Prejudice\" \"Jane Austen\"]
     [:Book \"Northanger Abbey\" \"Jane Austen\"]
  ]
]
```"
          cargo-result (compiler/compile wchnt-content)]
      
      (is (p/is-cargo? cargo-result))
      (if (:success cargo-result)
        (let [result (:value cargo-result)
              factory-code (:factory result)]
          ;; Should use proper Haxe array syntax with variable references
          (is (str/includes? factory-code "[obj1, obj2]"))
          ;; Should have proper object construction
          (is (str/includes? factory-code "new Book(\"Pride and Prejudice\", \"Jane Austen\")"))
          (is (str/includes? factory-code "new Book(\"Northanger Abbey\", \"Jane Austen\")"))
          ;; Should have proper array assignment
          (is (str/includes? factory-code "var obj3 = [obj1, obj2];")))
        (do
          (println "Array with variable references test failed:")
          (println "Error:" (:error cargo-result))
          (is false "Array with variable references should work"))))))

(deftest test-haxe-array-with-external-variable-references
  "Test that arrays with external variable references generate correct Haxe syntax"
  (testing "Array with external variable references should use proper Haxe syntax"
    (let [wchnt-content "## Schema

```
Player = String/name
Team = [Player]/players
Game = [Team]/teams
```

## Construction

```
players = [:Array/Player [\"John\"] [\"Sally\"]].

[:Game 
  [:Array/Team [\"Team A\" players] [\"Team B\" players]]
]
```"
          cargo-result (compiler/compile wchnt-content)]
      
      (is (p/is-cargo? cargo-result))
      (if (:success cargo-result)
        (let [result (:value cargo-result)
              factory-code (:factory result)]
          ;; Should use proper Haxe array syntax with variable references
          (is (str/includes? factory-code "[obj1, obj2]"))
          ;; Should have proper Team object construction
          (is (str/includes? factory-code "new Team(\"Team A\", players)"))
          (is (str/includes? factory-code "new Team(\"Team B\", players)"))
          ;; Should NOT have raw IR structure in array
          (is (not (str/includes? factory-code "{:type :variable"))))
        (do
          (println "Array with external variable references test failed:")
          (println "Error:" (:error cargo-result))
          (is false "Array with external variable references should work"))))))


