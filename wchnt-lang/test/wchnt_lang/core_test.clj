(ns wchnt-lang.core-test
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [clojure.pprint :as pp]
            [wchnt-lang.core :refer [get-schema-parser
                                     compile-wchnt-file
                                     eyeball]]
            [wchnt-lang.compiler :as compiler]
            [wchnt-lang.examples :refer [example-game-schema
                                         example-person-schema
                                         example-shape-schema]]
            [wchnt-lang.schema :as schema]
            [wchnt-lang.parser :as parser]
            [wchnt-lang.pipeline :as P]
            
            [wchnt-lang.haxegen :as haxegen]))

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
            (is (str/includes? main "return gameFactory()"))))))))

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
        (is (str/includes? classes "class ArrayExtensions"))))))

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
          context-map (wchnt-lang.haxegen/build-context-relationships schema-ast)]
      (is (= {"Car" nil, "Engine" "Car"} context-map))))

  (testing "Context relationships for nested schema"
    (let [schema-str "A = :B\nB = :C\nC = Int/value"
          schema-ast ((wchnt-lang.parser/get-schema-parser) schema-str)
          context-map (wchnt-lang.haxegen/build-context-relationships schema-ast)]
      (is (= {"A" nil, "B" "A", "C" "B"} context-map)))))

(run-tests)
