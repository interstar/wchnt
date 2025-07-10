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
      (println "DEBUG: Recursive parse result:" (pr-str parse-result))
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
          result (haxegen/generate-construction-factory schema construction {})]
      (is (:success result))
      (is (string? (:haxe-code result)))
      (is (str/includes? (:haxe-code result) "var o1 = new Rect(0, 0, 800, 600)"))
      (is (str/includes? (:haxe-code result) "var o2 = new Ball(100, 100, 5)"))
      (is (str/includes? (:haxe-code result) "var o3 = new Game(o1, o2)"))
      (is (str/includes? (:haxe-code result) "return o3")))))

(deftest test-haxe-factory-with-enums
  (testing "Haxe factory generation with enum values"
    (let [schema "Direction = \"Up\" | \"Down\" | \"Left\" | \"Right\"\nGame = Direction/move"
          construction "[:Game Up]"
          result (haxegen/generate-construction-factory schema construction {})]
      (is (:success result))
      (is (str/includes? (:haxe-code result) "var o1 = new Game(Up)"))
      (is (str/includes? (:haxe-code result) "return o1")))))

(deftest test-haxe-factory-with-context-components
  (testing "Haxe factory generation with context-specific components"
    (let [schema "Game = :Rect Ball\nRect = int/x int/y int/width int/height\nBall = int/x int/y int/rad"
          construction "[:Game [:Rect 0 0 800 600] [:Ball 100 100 5]]"
          parse-result (parser/parse-construction schema construction)]
      (println "DEBUG: Parse result:" (pr-str parse-result))
      (when (:success parse-result)
        (println "DEBUG: AST structure:" (pr-str (:ast parse-result))))
      (let [result (haxegen/generate-construction-factory schema construction {})]
        (println "DEBUG: Factory result:" (pr-str result))
        (is (:success result))
        (is (str/includes? (:haxe-code result) "var o1 = new Rect(0, 0, 800, 600)"))
        (is (str/includes? (:haxe-code result) "var o2 = new Ball(100, 100, 5)"))
        (is (str/includes? (:haxe-code result) "var o3 = new Game(o1, o2)"))
        (is (str/includes? (:haxe-code result) "o1.setContext(o3)"))))))