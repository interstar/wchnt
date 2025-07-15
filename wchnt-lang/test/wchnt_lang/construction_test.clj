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
              result-cargo (haxegen/generate-construction-factory-pure parsed-ast class-info {})]
          (is (:success result-cargo))
          (let [result (:value result-cargo)]
            (is (string? result))
            (is (str/includes? result "Rect o1 = new Rect(0, 0, 800, 600)"))
            (is (str/includes? result "Ball o2 = new Ball(100, 100, 5)"))
            (is (str/includes? result "Game o3 = new Game(o1, o2)"))
            (is (str/includes? result "return o3"))))))))

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
              result-cargo (haxegen/generate-construction-factory-pure parsed-ast class-info {})]
          (is (:success result-cargo))
          (let [result (:value result-cargo)]
            (is (string? result))
            (is (str/includes? result "Game o1 = new Game(Up)"))
            (is (str/includes? result "return o1"))))))))

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
              context-relationships (haxegen/build-context-relationships schema-ast)
              result-cargo (haxegen/generate-construction-factory-pure parsed-ast class-info context-relationships)]
          (is (:success result-cargo))
          (let [result (:value result-cargo)]
            (is (string? result))
            (is (str/includes? result "Rect o1 = new Rect(0, 0, 800, 600)"))
            (is (str/includes? result "Ball o2 = new Ball(100, 100, 5)"))
            (is (str/includes? result "Game o3 = new Game(o1, o2)"))
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

(deftest test-construction-grammar-with-simple-arrays
  (testing "Construction grammar generation with simple arrays"
    (let [schema "Game = [Player]/players\nPlayer = String/name Int/score"
          schema-parse-result (parser/schema-wchnt->schema-ast schema)]
      (is (:success schema-parse-result))
      (let [schema-ast (:value schema-parse-result)
            grammar-result (parser/schema-to-construction-grammar schema-ast)]
        (is (:success grammar-result))
        (let [grammar (:grammar (:value grammar-result))]
          (is (str/includes? grammar "GameConstruction"))
          (is (str/includes? grammar "PlayerConstruction"))
          (is (str/includes? grammar "PlayerArrayElement"))
          (is (str/includes? grammar "PlayerArrayConstruction"))
          ;; Should only allow PlayerArrayElement for Player arrays
          (is (str/includes? grammar "PlayerArrayConstruction = <'['> <WS>? <':'> <'Array'> <'/'> <'Player'> (<WS>? (PlayerArrayElement))* <WS>? <']'>")))))))

(deftest test-construction-grammar-with-disjunction-arrays
  (testing "Construction grammar generation with disjunction arrays"
    (let [schema "Game = [Shape]/shapes\nShape = Triangle | Circle\nTriangle = Int/base Int/height\nCircle = Int/radius"
          schema-parse-result (parser/schema-wchnt->schema-ast schema)]
      (is (:success schema-parse-result))
      (let [schema-ast (:value schema-parse-result)
            grammar-result (parser/schema-to-construction-grammar schema-ast)]
        (is (:success grammar-result))
        (let [grammar (:grammar (:value grammar-result))]
          (is (str/includes? grammar "GameConstruction"))
          (is (str/includes? grammar "TriangleConstruction"))
          (is (str/includes? grammar "CircleConstruction"))
          (is (str/includes? grammar "TriangleArrayElement"))
          (is (str/includes? grammar "CircleArrayElement"))
          (is (str/includes? grammar "ShapeArrayConstruction"))
          ;; Should only allow TriangleArrayElement and CircleArrayElement for Shape arrays
          (is (str/includes? grammar "ShapeArrayConstruction = <'['> <WS>? <':'> <'Array'> <'/'> <'Shape'> (<WS>? (TriangleArrayElement | CircleArrayElement))* <WS>? <']'>")))))))

(deftest test-construction-parsing-simple-array
  (testing "Simple array construction parsing"
    (let [schema "Game = [Player]/players\nPlayer = String/name Int/score"
          construction "[:Game [:Array/Player [:Player \"Alice\" 100] [:Player \"Bob\" 85]]]"
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

(deftest test-construction-parsing-disjunction-array
  (testing "Disjunction array construction parsing"
    (let [schema "Game = [Shape]/shapes\nShape = Triangle | Circle\nTriangle = Int/base Int/height\nCircle = Int/radius"
          construction "[:Game [:Array/Shape [:Triangle 10 20] [:Circle 15]]]"
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

(deftest test-multi-step-construction-with-arrays
  (testing "Multi-step construction parsing with array assignments"
    (let [schema "Game = [Shape]/shapes [Player]/players\nShape = Triangle | Circle\nTriangle = Int/base Int/height\nCircle = Int/radius\nPlayer = String/name Int/score"
          construction "$shapes = [:Array/Shape [:Triangle 10 20] [:Circle 15]]. $players = [:Array/Player [:Player \"Alice\" 100] [:Player \"Bob\" 85]]. [:Game $shapes $players]"
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

(deftest test-haxe-factory-generation-simple-array
  (testing "Haxe factory generation with simple arrays"
    (let [schema "Game = [Player]/players\nPlayer = String/name Int/score"
          construction "[:Game [:Array/Player [:Player \"Alice\" 100] [:Player \"Bob\" 85]]]"
          schema-parse-result (parser/schema-wchnt->schema-ast schema)]
      (is (:success schema-parse-result))
      (let [schema-ast (:value schema-parse-result)
            parse-result (parser/parse-construction-pure {:schema-ast schema-ast :construction construction})]
        (is (:success parse-result))
        (let [parsed-ast (:value parse-result)
              class-info (parser/extract-class-info schema-ast)
              result-cargo (haxegen/generate-construction-factory-pure parsed-ast class-info {})]
          (is (:success result-cargo))
          (let [result (:value result-cargo)]
            (is (string? result))
            (is (str/includes? result "Player o1 = new Player(\"Alice\", 100)"))
            (is (str/includes? result "Player o2 = new Player(\"Bob\", 85)"))
            (is (str/includes? result "Array<Dynamic> o3 = [o1, o2]"))
            (is (str/includes? result "Game o4 = new Game(o3)"))
            (is (str/includes? result "return o4"))))))))

(deftest test-haxe-factory-generation-disjunction-array
  (testing "Haxe factory generation with disjunction arrays"
    (let [schema "Game = [Shape]/shapes\nShape = Triangle | Circle\nTriangle = Int/base Int/height\nCircle = Int/radius"
          construction "[:Game [:Array/Shape [:Triangle 10 20] [:Circle 15]]]"
          schema-parse-result (parser/schema-wchnt->schema-ast schema)]
      (is (:success schema-parse-result))
      (let [schema-ast (:value schema-parse-result)
            parse-result (parser/parse-construction-pure {:schema-ast schema-ast :construction construction})]
        (is (:success parse-result))
        (let [parsed-ast (:value parse-result)
              class-info (parser/extract-class-info schema-ast)
              result-cargo (haxegen/generate-construction-factory-pure parsed-ast class-info {})]
          (is (:success result-cargo))
          (let [result (:value result-cargo)]
            (is (string? result))
            (is (str/includes? result "Triangle o1 = new Triangle(10, 20)"))
            (is (str/includes? result "Circle o2 = new Circle(15)"))
            (is (str/includes? result "Array<Dynamic> o3 = [o1, o2]"))
            (is (str/includes? result "Game o4 = new Game(o3)"))
            (is (str/includes? result "return o4"))))))))

(deftest test-haxe-factory-generation-multi-step-arrays
  (testing "Haxe factory generation with multi-step array assignments"
    (let [schema "Game = [Shape]/shapes [Player]/players\nShape = Triangle | Circle\nTriangle = Int/base Int/height\nCircle = Int/radius\nPlayer = String/name Int/score"
          construction "$shapes = [:Array/Shape [:Triangle 10 20] [:Circle 15]]. $players = [:Array/Player [:Player \"Alice\" 100] [:Player \"Bob\" 85]]. [:Game $shapes $players]"
          schema-parse-result (parser/schema-wchnt->schema-ast schema)]
      (is (:success schema-parse-result))
      (let [schema-ast (:value schema-parse-result)
            parse-result (parser/parse-construction-pure {:schema-ast schema-ast :construction construction})]
        (is (:success parse-result))
        (let [parsed-ast (:value parse-result)
              class-info (parser/extract-class-info schema-ast)
              result-cargo (haxegen/generate-construction-factory-pure parsed-ast class-info {})]
          (is (:success result-cargo))
          (let [result (:value result-cargo)]
            (is (string? result))
            ;; Check that array assignments are handled correctly
            (is (str/includes? result "Triangle o7 = new Triangle(10, 20)"))
            (is (str/includes? result "Circle o8 = new Circle(15)"))
            (is (str/includes? result "Array<Dynamic> o9 = [o7, o8]"))
            (is (str/includes? result "Player o10 = new Player(\"Alice\", 100)"))
            (is (str/includes? result "Player o11 = new Player(\"Bob\", 85)"))
            (is (str/includes? result "Array<Dynamic> o12 = [o10, o11]"))
            (is (str/includes? result "Game o13 = new Game(o9, o12)"))
            (is (str/includes? result "return o13"))))))))

(deftest test-array-grammar-type-safety
  (testing "Array grammar should be type-safe - reject invalid combinations"
    (let [schema "Game = [Shape]/shapes [Player]/players\nShape = Triangle | Circle\nTriangle = Int/base Int/height\nCircle = Int/radius\nPlayer = String/name Int/score"
          ;; This should fail because we're trying to put a Player in a Shape array
          invalid-construction "[:Game [:Array/Shape [:Player \"invalid\" 100]]]"
          schema-parse-result (parser/schema-wchnt->schema-ast schema)]
      (is (:success schema-parse-result))
      (let [schema-ast (:value schema-parse-result)
            parse-result (parser/parse-construction-pure {:schema-ast schema-ast :construction invalid-construction})]
        ;; This should fail because the grammar should reject PlayerArrayElement in ShapeArrayConstruction
        (is (not (:success parse-result)))))))

(deftest test-array-element-grammar-generation
  (testing "Array element grammar rules should be generated correctly"
    (let [schema "Game = [Player]/players\nPlayer = String/name Int/score"
          schema-parse-result (parser/schema-wchnt->schema-ast schema)]
      (is (:success schema-parse-result))
      (let [schema-ast (:value schema-parse-result)
            grammar-result (parser/schema-to-construction-grammar schema-ast)]
        (is (:success grammar-result))
        (let [grammar (:grammar (:value grammar-result))]
          ;; Should have PlayerArrayElement rule
          (is (str/includes? grammar "PlayerArrayElement = <'['> (<':'> <'Player'>)? <WS>? (StringLiteral | LocalEmpty | VariableReference) <WS>? (IntLiteral | LocalEmpty | VariableReference) <WS>? <']'>")))))))

(deftest test-disjunction-array-element-grammar-generation
  (testing "Disjunction array element grammar rules should be generated correctly"
    (let [schema "Game = [Shape]/shapes\nShape = Triangle | Circle\nTriangle = Int/base Int/height\nCircle = Int/radius"
          schema-parse-result (parser/schema-wchnt->schema-ast schema)]
      (is (:success schema-parse-result))
      (let [schema-ast (:value schema-parse-result)
            grammar-result (parser/schema-to-construction-grammar schema-ast)]
        (is (:success grammar-result))
        (let [grammar (:grammar (:value grammar-result))]
          ;; Should have TriangleArrayElement and CircleArrayElement rules
          (is (str/includes? grammar "TriangleArrayElement = <'['> (<':'> <'Triangle'>)? <WS>? (IntLiteral | LocalEmpty | VariableReference) <WS>? (IntLiteral | LocalEmpty | VariableReference) <WS>? <']'>"))
          (is (str/includes? grammar "CircleArrayElement = <'['> (<':'> <'Circle'>)? <WS>? (IntLiteral | LocalEmpty | VariableReference) <WS>? <']'>"))))))))
