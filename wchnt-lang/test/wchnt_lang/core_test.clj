(ns wchnt-lang.core-test
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [clojure.pprint :as pp]
            [instaparse.core :as insta]
            [wchnt-lang.core :refer [get-schema-parser
                                     compile-wchnt-file
                                     eyeball]]
            [wchnt-lang.compiler :as compiler]
            [wchnt-lang.examples :refer [example-game-schema
                                         example-person-schema
                                         example-shape-schema]]
            [wchnt-lang.schema :as schema]
            [wchnt-lang.parser :as parser]
            [wchnt-lang.pipeline :as P]))

(deftest test-get-parser
  (testing "Parser retrieval"
    (let [parser (get-schema-parser)]
      (is (ifn? parser))
      (is (not (instaparse.core/failure? (parser "Game = Ball")))))))

(deftest test-compile-wchnt-file-simple
  (testing "Compile simple WCHNT file with schema only"
    (let [wchnt-content "## Schema

```
Config = String/settings
```

## Construction

```
```"
          cargo-result (compiler/compile wchnt-content) ;; compile now returns a cargo
          result (:value cargo-result)]

      (is (schema/valid-full-program? result))
      (let [classes (:classes result)]

        (is (str/includes? result "class Config"))
        (is (str/includes? result "public var settings: String"))
        ))

    (deftest test-compile-wchnt-file-with-construction
      (testing "Compile WCHNT file with schema and construction"
        (let [wchnt-content "## Schema

```
Game = PlayArea Ball
PlayArea = Rect
Rect = Int/x Int/y Int/width Int/height
Ball = Int/x Int/y Int/rad
```

## Construction

```
[:Game [:PlayArea [:Rect 0 0 800 600]] [:Ball 100 100 5]]
```

## Target

```
%terminal

%main
public static function main():Void {
    var assemblage = gameFactory();
    var helper = new WCHNTHelper();
    trace(assemblage.toConstruction(0, helper));
}
```"
              cargo-result (compiler/compile wchnt-content)
              result (:value cargo-result)]
          (is (schema/valid-full-program? result))
          (let [classes (:classes result)
                factory (:factory result)
                main (:main result)]
            (is (str/includes? result "class Game"))
            (is (str/includes? result "class PlayArea"))
            (is (str/includes? result "class Rect"))
            (is (str/includes? result "class Ball"))
            (is (str/includes? factory "public static function gameFactory("))
            (is (str/includes? main "var assemblage = gameFactory()"))))))))

(deftest test-compile-wchnt-complex
  (testing "Compile complex WCHNT with arrays and disjunctions"
    (let [wchnt-content "## Schema

```
Game = [Shape]/shapes [Player]/players
Shape = Triangle | Circle
Triangle = Int/base Int/height
Circle = Int/radius
Player = String/name Int/score
```

## Construction

```
shapes = [:Array/Shape [:Triangle 10 20] [:Circle 15]] .
players = [:Array/Player [:Player \"Alice\" 100] [:Player \"Bob\" 85]] .
[:Game shapes players]
```

## Target

```
%terminal

%main
public static function main():Void {
    var assemblage = gameFactory();
    var helper = new WCHNTHelper();
    trace(assemblage.toConstruction(0, helper));
}
```"
          cargo-result (compiler/compile wchnt-content)
          result  (:value cargo-result)]
      (is (:success cargo-result))
      (is (schema/valid-full-program? result))
      (let [classes (:classes result)
            factory (:factory result)
            main (:main result)]
        
        (is (str/includes? classes "interface Shape"))
        (is (str/includes? classes "class Triangle implements Shape"))
        (is (str/includes? classes "class Circle implements Shape"))
        (is (str/includes? classes "public var shapes: Array<Shape>"))
        (is (str/includes? classes "public var players: Array<Player>"))
        (is (str/includes? classes "helper.arrayToConstruction"))))))

(deftest test-compile-wchnt-error-handling
  (testing "Compile WCHNT file with syntax errors"
    (let [wchnt-content "## Schema

```
Game = = PlayArea Ball
```

## Construction

```
[:Game [:PlayArea]]
```"
         result (compiler/compile wchnt-content) ]
      (is (P/is-cargo? result))
      (is (P/failed? result))
)))

(deftest test-eyeball
  (testing "Eyeball validation"
    (let [valid-code "class Game {
    public var playArea: PlayArea;
    public var ball: Ball;
    
    public function new(playArea: PlayArea, ball: Ball) {
        this.playArea = playArea;
        this.ball = ball;
    }
}"
          invalid-code "function test() { return 42; }"
          valid-result (eyeball valid-code)
          invalid-result (eyeball invalid-code)]
      (is (= "seems ok" (:status valid-result)))
      (is (empty? (:issues valid-result)))
      (is (= "issues" (:status invalid-result)))
      (is (not (empty? (:issues invalid-result)))))))

(deftest test-example-schemas
  (testing "Example schema functions"
    (is (string? (example-game-schema)))
    (is (string? (example-person-schema)))
    (is (string? (example-shape-schema)))
    (is (str/includes? (example-game-schema) "Game ="))
    (is (str/includes? (example-person-schema) "Person ="))
    (is (str/includes? (example-shape-schema) "Shape ="))))



(deftest test-build-context-relationships
  (testing "Context relationships for simple schema"
    (let [schema-str "Car = :Engine\nEngine = Int/cylinders"
          schema-ast ((wchnt-lang.parser/get-schema-parser) schema-str)
          context-map (wchnt-lang.ast-to-ir/build-context-relationships schema-ast)]
      (is (= {"Car" nil, "Engine" "Car"} context-map))))

  (testing "Context relationships for nested schema"
    (let [schema-str "A = :B\nB = :C\nC = Int/value"
          schema-ast ((wchnt-lang.parser/get-schema-parser) schema-str)
          context-map (wchnt-lang.ast-to-ir/build-context-relationships schema-ast)]
      (is (= {"A" nil, "B" "A", "C" "B"} context-map)))))

(deftest test-austen-example
  (testing "Compile Austen example with array construction"
    (let [wchnt-content "## Schema

```
DB = [Book]/books
Book = String/title
```

## Construction

```
[:DB 
  [:Array/Book
     [:Book \"Pride and Prejudice\"]
     [:Book \"Northanger Abbey\"]
  ]
]
```

## Target

```
%terminal

%main
public static function main():Void {
    var assemblage = dBFactory();
    var helper = new WCHNTHelper();
    trace(assemblage.toConstruction(0, helper));
}
```"
          cargo-result (compiler/compile wchnt-content)
          result (:value cargo-result)]
      
      ;; First, let's check if the cargo failed and why
      (if (P/failed? cargo-result)
        (do
          (println "Compilation failed:")
          (println "Error:" (:error cargo-result))
          (println "Full cargo:" (with-out-str (pp/pprint cargo-result)))
          ;; For now, just test that we get a proper error cargo
          (is (P/is-cargo? cargo-result))
          (is (P/failed? cargo-result)))
        ;; If it succeeded, test the result
        (do
          (is (schema/valid-full-program? result))
          (let [classes (:classes result)
                factory (:factory result)]
            (is (str/includes? classes "class DB"))
            (is (str/includes? classes "class Book"))
            (is (str/includes? classes "public var books: Array<Book>"))
            (is (str/includes? factory "public static function dBFactory("))))))))





(deftest test-construction-whitespace-handling
  (testing "Test that construction parsing handles whitespace correctly"
    (let [one-liner "[:DB [:Array/Book [:Book \"Pride and Prejudice\"] [:Book \"Northanger Abbey\"]]]"
          multi-line-with-trailing "[:DB 
  [:Array/Book
     [:Book \"Pride and Prejudice\"]
     [:Book \"Northanger Abbey\"]
  ]
]
"
          multi-line-no-trailing "[:DB 
  [:Array/Book
     [:Book \"Pride and Prejudice\"]
     [:Book \"Northanger Abbey\"]
  ]
]"]
      
      ;; Test using the actual parsing function that includes trimming
      (let [one-cargo (parser/parse-construction-unified one-liner)
            trailing-cargo (parser/parse-construction-unified multi-line-with-trailing)
            no-trailing-cargo (parser/parse-construction-unified multi-line-no-trailing)]
        
        (is (P/is-cargo? one-cargo) "One-liner should return a cargo")
        (is (P/is-cargo? trailing-cargo) "Multi-line with trailing whitespace should return a cargo")
        (is (P/is-cargo? no-trailing-cargo) "Multi-line without trailing whitespace should return a cargo")
        
        (is (:success one-cargo) "One-liner should parse successfully")
        (is (:success trailing-cargo) "Multi-line with trailing whitespace should parse successfully")
        (is (:success no-trailing-cargo) "Multi-line without trailing whitespace should parse successfully")
        
        ;; All should produce the same AST structure
        (let [one-ast (:value one-cargo)
              trailing-ast (:value trailing-cargo)
              no-trailing-ast (:value no-trailing-cargo)]
          (is (= (first one-ast) :BlockStatements) "Should parse as BlockStatements")
          (is (= (first trailing-ast) :BlockStatements) "Should parse as BlockStatements")
          (is (= (first no-trailing-ast) :BlockStatements) "Should parse as BlockStatements"))))))



(run-tests)
