(ns wchnt-lang.construction-test
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [wchnt-lang.parser :as parser]
            [wchnt-lang.schema :as schema]
            [wchnt-lang.haxegen :as haxegen]))

(deftest test-schema-to-construction-grammar
  (testing "Schema to construction grammar generation"
    (let [schema "Game = Rect Ball\nRect = int/x int/y int/width int/height\nBall = int/x int/y int/rad"
          result (parser/schema-to-construction-grammar schema)]
      (is (:success result))
      (is (string? (:grammar (:ast result))))
      (is (str/includes? (:grammar (:ast result)) "GameConstruction"))
      (is (str/includes? (:grammar (:ast result)) "RectConstruction"))
      (is (str/includes? (:grammar (:ast result)) "BallConstruction")))))

(deftest test-construction-grammar-with-enums
  (testing "Construction grammar generation with enums"
    (let [schema "Direction = \"Up\" | \"Down\" | \"Left\" | \"Right\"\nGame = Direction/move"
          result (parser/schema-to-construction-grammar schema)]
      (is (:success result))
      (is (str/includes? (:grammar (:ast result)) "DirectionValue"))
      (is (str/includes? (:grammar (:ast result)) "GameConstruction")))))

(deftest test-construction-grammar-with-dictionaries
  (testing "Construction grammar generation with dictionaries"
    (let [schema "Config = {String : int}/settings"
          result (parser/schema-to-construction-grammar schema)]
      (is (:success result))
      (is (str/includes? (:grammar (:ast result)) "ConfigConstruction")))))

(deftest test-construction-grammar-with-arrays
  (testing "Construction grammar generation with arrays"
    (let [schema "School = [Person]/students\nPerson = String/name"
          result (parser/schema-to-construction-grammar schema)]
      (is (:success result))
      (is (str/includes? (:grammar (:ast result)) "SchoolConstruction"))
      (is (str/includes? (:grammar (:ast result)) "PersonConstruction")))))

(deftest test-construction-grammar-with-sigils
  (testing "Construction grammar generation with sigils"
    (let [schema "Game = :Rect Ball\nRect = int/x int/y int/width int/height\nBall = int/x int/y int/rad"
          result (parser/schema-to-construction-grammar schema)]
      (is (:success result))
      (is (str/includes? (:grammar (:ast result)) "GameConstruction"))
      (is (str/includes? (:grammar (:ast result)) "RectConstruction"))
      (is (str/includes? (:grammar (:ast result)) "BallConstruction")))))

(deftest test-construction-grammar-with-recursive-types
  (testing "Construction grammar generation with recursive types"
    (let [schema "Tree = _ | Node\nNode = int/data Tree/left Tree/right"
          result (parser/schema-to-construction-grammar schema)]
      (is (:success result))
      (is (str/includes? (:grammar (:ast result)) "NodeConstruction"))
      (is (str/includes? (:grammar (:ast result)) "_TreeConstruction")))))

(deftest test-construction-grammar-with-disjunctions
  (testing "Construction grammar generation with disjunctions"
    (let [schema "Shape = Triangle | Circle\nTriangle = int/base int/height\nCircle = int/radius"
          result (parser/schema-to-construction-grammar schema)]
      (is (:success result))
      (is (str/includes? (:grammar (:ast result)) "TriangleConstruction"))
      (is (str/includes? (:grammar (:ast result)) "CircleConstruction")))))

(deftest test-construction-grammar-with-disjunctions-and-empty
  (testing "Construction grammar generation with disjunctions and empty types"
    (let [schema "Shape = _ | Triangle | Circle\nTriangle = int/base int/height\nCircle = int/radius"
          result (parser/schema-to-construction-grammar schema)]
      (is (:success result))
      (is (str/includes? (:grammar (:ast result)) "TriangleConstruction"))
      (is (str/includes? (:grammar (:ast result)) "CircleConstruction"))
      (is (str/includes? (:grammar (:ast result)) "_ShapeConstruction")))))

(deftest test-construction-parsing-simple
  (testing "Simple construction parsing"
    (let [schema "Game = Rect Ball\nRect = int/x int/y int/width int/height\nBall = int/x int/y int/dx int/dy int/rad"
          construction "[:Game [:Rect 0 0 800 600] [:Ball 100 100 1 1 5]]"
          parse-result (parser/parse-construction schema construction)]
      (is (:success parse-result))
      (is (= :MultiStepConstruction (:type (:ast parse-result)))))))

(deftest test-construction-parsing-with-enums
  (testing "Construction parsing with enum values"
    (let [schema "Direction = \"Up\" | \"Down\" | \"Left\" | \"Right\"\nGame = Direction/move"
          construction "[:Game Up]"
          parse-result (parser/parse-construction schema construction)]
      (is (:success parse-result))
      (is (= :MultiStepConstruction (:type (:ast parse-result)))))))

(deftest test-construction-parsing-with-recursive
  (testing "Construction parsing with recursive tree types"
    (let [schema "Tree = _ | Node\nNode = int/data Tree/left Tree/right"
          construction "[:Node 4 _ [:Node 6 [:Node 3 _ _] _]]"
          parse-result (parser/parse-construction schema construction)]
      (is (:success parse-result))
      (is (= :MultiStepConstruction (:type (:ast parse-result)))))))

(deftest test-construction-parsing-with-disjunctions
  (testing "Construction parsing with disjunction types"
    (let [schema "Shape = Triangle | Circle\nTriangle = int/base int/height\nCircle = int/radius"
          construction "[:Triangle 10 20]"
          parse-result (parser/parse-construction schema construction)]
      (is (:success parse-result))
      (is (= :MultiStepConstruction (:type (:ast parse-result)))))))

(deftest test-construction-parsing-invalid-syntax
  (testing "Construction parsing with invalid syntax"
    (let [schema "Game = PlayArea\nPlayArea = int/x"
          construction "[:Game [:PlayArea]]" ; Missing required argument
          parse-result (parser/parse-construction schema construction)]
      (is (not (:success parse-result)))
      (is (string? (:error parse-result))))))

(deftest test-construction-parsing-unknown-class
  (testing "Construction parsing with unknown class"
    (let [schema "Game = PlayArea\nPlayArea = int/x"
          construction "[:Unknown 42]"
          parse-result (parser/parse-construction schema construction)]
      (is (not (:success parse-result)))
      (is (string? (:error parse-result))))))

(deftest test-haxe-factory-generation
  (testing "Haxe factory generation from construction AST"
    (let [schema "Game = Rect Ball\nRect = int/x int/y int/width int/height\nBall = int/x int/y int/rad"
          construction "[:Game [:Rect 0 0 800 600] [:Ball 100 100 5]]"
          result (haxegen/generate-construction-factory schema construction)]
      (is (:success result))
      (is (string? (:haxe-code result)))
      (is (str/includes? (:haxe-code result) "new Game("))
      (is (str/includes? (:haxe-code result) "new Rect("))
      (is (str/includes? (:haxe-code result) "new Ball("))
      (is (str/includes? (:haxe-code result) "0, 0, 800, 600"))
      (is (str/includes? (:haxe-code result) "100, 100, 5")))))

(deftest test-haxe-factory-with-enums
  (testing "Haxe factory generation with enum values"
    (let [schema "Direction = \"Up\" | \"Down\" | \"Left\" | \"Right\"\nGame = Direction/move"
          construction "[:Game Up]"
          result (haxegen/generate-construction-factory schema construction)]
      (is (:success result))
      (is (str/includes? (:haxe-code result) "new Game("))
      (is (str/includes? (:haxe-code result) "Up")))))

(deftest test-haxe-factory-with-context-components
  (testing "Haxe factory generation with context-specific components"
    (let [schema "Game = :Rect Ball\nRect = int/x int/y int/width int/height\nBall = int/x int/y int/rad"
          construction "[:Game [:Rect 0 0 800 600] [:Ball 100 100 5]]"
          result (haxegen/generate-construction-factory schema construction)]
      (is (:success result))
      (is (str/includes? (:haxe-code result) "new LazyContext<Rect>"))
      (is (str/includes? (:haxe-code result) "new Ball(")))))

(deftest test-assignment-complete?
  (testing "assignment-complete? returns true for balanced brackets and braces"
    (is (true? (parser/assignment-complete? {:brackets 0 :braces 0})))
    (is (false? (parser/assignment-complete? {:brackets 1 :braces 0})))
    (is (false? (parser/assignment-complete? {:brackets 0 :braces 1})))
    (is (false? (parser/assignment-complete? {:brackets 1 :braces 1})))))

(deftest test-count-brackets
  (testing "count-brackets correctly counts bracket balance"
    (is (= {:brackets 0 :braces 0} (parser/count-brackets "hello")))
    (is (= {:brackets 1 :braces 0} (parser/count-brackets "[hello")))
    (is (= {:brackets 0 :braces 0} (parser/count-brackets "[hello]")))
    (is (= {:brackets 1 :braces 1} (parser/count-brackets "[hello{world")))
    (is (= {:brackets 0 :braces -1} (parser/count-brackets "[hello{world}]}")))))

(deftest test-parse-assignment-start
  (testing "parse-assignment-start handles different assignment formats"
    ;; Assignment with no content
    (is (= {:var-name "ps" :text "" :brackets 0 :braces 0}
           (parser/parse-assignment-start "$ps = ")))
    
    ;; Assignment with content
    (is (= {:var-name "ps" :text "[:Array [10 10 \"John\"]]" :brackets 0 :braces 0}
           (parser/parse-assignment-start "$ps = [:Array [10 10 \"John\"]]")))
    
    ;; Not an assignment
    (is (nil? (parser/parse-assignment-start "[:Game")))
    (is (nil? (parser/parse-assignment-start "")))))

(deftest test-continue-assignment
  (testing "continue-assignment properly updates assignment state"
    (let [initial {:var-name "ps" :text "[:Array" :brackets 1 :braces 0}
          result (parser/continue-assignment initial "[10 10 \"John\"]")]
      (is (= "[:Array [10 10 \"John\"]" (:text result)))
      (is (= 1 (:brackets result)))
      (is (= 0 (:braces result))))))

(deftest test-format-assignment
  (testing "format-assignment creates correct string format"
    (is (= "$ps = [:Array [10 10 \"John\"]]"
           (parser/format-assignment {:var-name "ps" :text "[:Array [10 10 \"John\"]]"})))))

(deftest test-is-construction-line?
  (testing "is-construction-line? correctly identifies construction lines"
    (is (true? (parser/is-construction-line? "[Game")))
    (is (true? (parser/is-construction-line? "  [:PlayArea")))
    (is (false? (parser/is-construction-line? "")))
    (is (false? (parser/is-construction-line? "  ")))
    (is (false? (parser/is-construction-line? "$ps = "))))) 