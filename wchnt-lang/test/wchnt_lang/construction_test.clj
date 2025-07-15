(ns wchnt-lang.construction-test
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [wchnt-lang.parser :as parser]
            [wchnt-lang.schema :as schema]
            [wchnt-lang.haxegen :as haxegen]))

(deftest test-schema-to-construction-grammar
  (testing "Schema to construction grammar generation"
    (let [schema "Game = Rect Ball\nRect = Int/x Int/y Int/width Int/height\nBall = Int/x Int/y Int/rad"
          schema-parse-result (parser/schema-wchnt->schema-ast schema)]
      (is (:success schema-parse-result))
      (let [schema-ast (:value schema-parse-result)
            grammar-result (parser/schema-to-construction-grammar schema-ast)]
        (is (:success grammar-result))
        (is (string? (:grammar (:value grammar-result))))
        (is (str/includes? (:grammar (:value grammar-result)) "GameConstruction"))
        (is (str/includes? (:grammar (:value grammar-result)) "RectConstruction"))
        (is (str/includes? (:grammar (:value grammar-result)) "BallConstruction"))))))

(deftest test-construction-grammar-with-enums
  (testing "Construction grammar generation with enums"
    (let [schema "Direction = \"Up\" | \"Down\" | \"Left\" | \"Right\"\nGame = Direction/move"
          schema-parse-result (parser/schema-wchnt->schema-ast schema)]
      (is (:success schema-parse-result))
      (let [schema-ast (:value schema-parse-result)
            grammar-result (parser/schema-to-construction-grammar schema-ast)]
        (is (:success grammar-result))
        (is (str/includes? (:grammar (:value grammar-result)) "DirectionValue"))
        (is (str/includes? (:grammar (:value grammar-result)) "GameConstruction"))))))

(deftest test-construction-grammar-with-arrays
  (testing "Construction grammar generation with arrays"
    (let [schema "School = [Person]/students\nPerson = String/name"
          schema-parse-result (parser/schema-wchnt->schema-ast schema)]
      (is (:success schema-parse-result))
      (let [schema-ast (:value schema-parse-result)
            grammar-result (parser/schema-to-construction-grammar schema-ast)]
        (is (:success grammar-result))
        (is (str/includes? (:grammar (:value grammar-result)) "SchoolConstruction"))
        (is (str/includes? (:grammar (:value grammar-result)) "PersonConstruction"))))))

(deftest test-construction-grammar-with-disjunctions
  (testing "Construction grammar generation with disjunctions"
    (let [schema "Shape = Triangle | Circle\nTriangle = Int/base Int/height\nCircle = Int/radius"
          schema-parse-result (parser/schema-wchnt->schema-ast schema)]
      (is (:success schema-parse-result))
      (let [schema-ast (:value schema-parse-result)
            grammar-result (parser/schema-to-construction-grammar schema-ast)]
        (is (:success grammar-result))
        (is (str/includes? (:grammar (:value grammar-result)) "TriangleConstruction"))
        (is (str/includes? (:grammar (:value grammar-result)) "CircleConstruction"))))))

(deftest test-construction-parsing-simple
  (testing "Simple construction parsing"
    (let [schema "Game = Rect Ball\nRect = Int/x Int/y Int/width Int/height\nBall = Int/x Int/y Int/dx Int/dy Int/rad"
          construction "[:Game [:Rect 0 0 800 600] [:Ball 100 100 1 1 5]]"
          schema-parse-result (parser/schema-wchnt->schema-ast schema)]
      (is (:success schema-parse-result))
      (let [schema-ast (:value schema-parse-result)
            parse-result (parser/parse-construction-pure {:schema-ast schema-ast :construction construction})]
        (is (:success parse-result))
        (let [parsed-ast (:value parse-result)]
          (is (map? parsed-ast))
          (is (= :MultiStepConstruction (:type parsed-ast)))
          (is (empty? (:assignments parsed-ast)))
          (is (vector? (:final-construction parsed-ast)))
          (is (= :GameConstruction (first (:final-construction parsed-ast)))))))))

(deftest test-construction-parsing-with-enums
  (testing "Construction parsing with enum values"
    (let [schema "Direction = \"Up\" | \"Down\" | \"Left\" | \"Right\"\nGame = Direction/move"
          construction "[:Game Up]"
          schema-parse-result (parser/schema-wchnt->schema-ast schema)]
      (is (:success schema-parse-result))
      (let [schema-ast (:value schema-parse-result)
            parse-result (parser/parse-construction-pure {:schema-ast schema-ast :construction construction})]
        (is (:success parse-result))
        (let [parsed-ast (:value parse-result)]
          (is (map? parsed-ast))
          (is (= :MultiStepConstruction (:type parsed-ast)))
          (is (empty? (:assignments parsed-ast)))
          (is (vector? (:final-construction parsed-ast)))
          (is (= :GameConstruction (first (:final-construction parsed-ast)))))))))

(deftest test-construction-parsing-with-disjunctions
  (testing "Construction parsing with disjunction types"
    (let [schema "Shape = Triangle | Circle\nTriangle = Int/base Int/height\nCircle = Int/radius"
          construction "[:Triangle 10 20]"
          schema-parse-result (parser/schema-wchnt->schema-ast schema)]
      (is (:success schema-parse-result))
      (let [schema-ast (:value schema-parse-result)
            parse-result (parser/parse-construction-pure {:schema-ast schema-ast :construction construction})]
        (is (:success parse-result))
        (let [parsed-ast (:value parse-result)]
          (is (map? parsed-ast))
          (is (= :MultiStepConstruction (:type parsed-ast)))
          (is (empty? (:assignments parsed-ast)))
          (is (vector? (:final-construction parsed-ast)))
          (is (= :TriangleConstruction (first (:final-construction parsed-ast)))))))))

(deftest test-multi-step-construction-parsing
  (testing "Multi-step construction parsing with assignments"
    (let [schema "Game = Rect Ball\nRect = Int/x Int/y Int/width Int/height\nBall = Int/x Int/y Int/rad"
          construction "$rect = [:Rect 0 0 800 600]. $ball = [:Ball 100 100 5]. [:Game $rect $ball]"
          schema-parse-result (parser/schema-wchnt->schema-ast schema)]
      (is (:success schema-parse-result))
      (let [schema-ast (:value schema-parse-result)
            parse-result (parser/parse-construction-pure {:schema-ast schema-ast :construction construction})]
        (is (:success parse-result))
        (let [parsed-ast (:value parse-result)]
          (is (map? parsed-ast))
          (is (= :MultiStepConstruction (:type parsed-ast)))
          (is (= 2 (count (:assignments parsed-ast))))
          (is (vector? (:final-construction parsed-ast)))
          (is (= :GameConstruction (first (:final-construction parsed-ast)))))))))

(deftest test-haxe-factory-generation
  (testing "Haxe factory generation from construction AST"
    (let [schema "Game = Rect Ball\nRect = Int/x Int/y Int/width Int/height\nBall = Int/x Int/y Int/rad"
          construction "[:Game [:Rect 0 0 800 600] [:Ball 100 100 5]]"
          schema-parse-result (parser/schema-wchnt->schema-ast schema)]
      (is (:success schema-parse-result))
      (let [schema-ast (:value schema-parse-result)
            parse-result (parser/parse-construction-pure {:schema-ast schema-ast :construction construction})]
        (is (:success parse-result))
        (let [parsed-ast (:value parse-result)
              class-info (parser/extract-class-info schema-ast)
              result (haxegen/generate-construction-factory-pure parsed-ast class-info {})]
          (is (string? result))
          (is (str/includes? result "var o1 = new Rect(0, 0, 800, 600)"))
          (is (str/includes? result "var o2 = new Ball(100, 100, 5)"))
          (is (str/includes? result "var o3 = new Game(o1, o2)"))
          (is (str/includes? result "return o3")))))))

(deftest test-haxe-factory-with-enums
  (testing "Haxe factory generation with enum values"
    (let [schema "Direction = \"Up\" | \"Down\" | \"Left\" | \"Right\"\nGame = Direction/move"
          construction "[:Game Up]"
          schema-parse-result (parser/schema-wchnt->schema-ast schema)]
      (is (:success schema-parse-result))
      (let [schema-ast (:value schema-parse-result)
            parse-result (parser/parse-construction-pure {:schema-ast schema-ast :construction construction})]
        (is (:success parse-result))
        (let [parsed-ast (:value parse-result)
              class-info (parser/extract-class-info schema-ast)
              result (haxegen/generate-construction-factory-pure parsed-ast class-info {})]
          (is (string? result))
          (is (str/includes? result "var o1 = new Game(Up)"))
          (is (str/includes? result "return o1")))))))

(deftest test-haxe-factory-with-context-components
  (testing "Haxe factory generation with context-specific components"
    (let [schema "Game = :Rect Ball\nRect = Int/x Int/y Int/width Int/height\nBall = Int/x Int/y Int/rad"
          construction "[:Game [:Rect 0 0 800 600] [:Ball 100 100 5]]"
          schema-parse-result (parser/schema-wchnt->schema-ast schema)]
      (is (:success schema-parse-result))
      (let [schema-ast (:value schema-parse-result)
            parse-result (parser/parse-construction-pure {:schema-ast schema-ast :construction construction})]
        (is (:success parse-result))
        (let [parsed-ast (:value parse-result)
              class-info (parser/extract-class-info schema-ast)
              result (haxegen/generate-construction-factory-pure parsed-ast class-info {})]
          (is (string? result))
          (is (str/includes? result "var o1 = new Rect(0, 0, 800, 600)"))
          (is (str/includes? result "var o2 = new Ball(100, 100, 5)"))
          (is (str/includes? result "var o3 = new Game(o1, o2)"))
          (is (str/includes? result "o1.setContext(o3)")))))))

(deftest test-single-statement-construction
  (testing "Single statement construction parsing (no full stops)"
    (let [schema "Game = PlayArea Ball\nPlayArea = Rect\nRect = Int/x Int/y Int/width Int/height\nBall = Int/x Int/y Int/rad"
          construction "[:Game [:PlayArea [:Rect 0 0 800 600]] [:Ball 100 100 5]]"
          schema-parse-result (parser/schema-wchnt->schema-ast schema)]
      (is (:success schema-parse-result))
      (let [schema-ast (:value schema-parse-result)
            parse-result (parser/parse-construction-pure {:schema-ast schema-ast :construction construction})]
        (is (:success parse-result))
        (let [parsed-ast (:value parse-result)]
          (is (map? parsed-ast))
          (is (= :MultiStepConstruction (:type parsed-ast)))
          (is (empty? (:assignments parsed-ast)))
          (is (vector? (:final-construction parsed-ast)))
          (is (= :GameConstruction (first (:final-construction parsed-ast)))))))))
