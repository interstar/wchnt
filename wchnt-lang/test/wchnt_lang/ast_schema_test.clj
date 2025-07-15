(ns wchnt-lang.ast-schema-test
  (:require [clojure.test :refer :all]
            [wchnt-lang.parser :as parser]
            [wchnt-lang.schema :as schema]))

(deftest test-schema-ast-validation
  (testing "Schema AST validation works with real parser output"
    (let [schema-input "Game = Ball\nBall = Int/x Int/y"
          result (parser/schema-wchnt->schema-ast schema-input)]
      (is (:success result))
      (is (schema/valid-schema-ast? (:ast result)))
      (is (schema/valid-schema-syntax-result? result)))))

(deftest test-construction-ast-validation
  (testing "Construction AST validation works with real parser output"
    (let [schema "Point = Float/x Float/y"
          schema-result (parser/schema-wchnt->schema-ast schema)
          construction "[:Point 3.14 2.718]"
          result (parser/parse-construction-pure {:schema-ast (:value schema-result) :construction construction})]
      (is (:success result))
      (is (schema/valid-construction-ast? (:ast result)))
      (is (schema/valid-construction-syntax-result? result)))))

(deftest test-multi-step-construction-ast-validation
  (testing "Multi-step construction AST validation works with real parser output"
    (let [schema "Point = Float/x Float/y"
          schema-result (parser/schema-wchnt->schema-ast schema)
          construction "$x = 3.14. $y = 2.718. [:Point $x $y]"
          result (parser/parse-construction-pure {:schema-ast (:value schema-result) :construction construction})]
      (is (:success result))
      (is (schema/valid-construction-ast? (:ast result)))
      (is (schema/valid-construction-syntax-result? result)))))

(deftest test-variable-assignment-ast-validation
  (testing "Variable assignment AST validation works with real parser output"
    (let [schema "Config = String/message"
          schema-result (parser/schema-wchnt->schema-ast schema)
          construction "$msg = \"Hello\". [:Config $msg]"
          result (parser/parse-construction-pure {:schema-ast (:value schema-result) :construction construction})]
      (is (:success result))
      (is (schema/valid-construction-ast? (:ast result)))
      (is (schema/valid-construction-syntax-result? result)))))

(deftest test-array-construction-ast-validation
  (testing "Array construction AST validation works with real parser output"
    (let [schema "Group = [Person]\nPerson = String/name"
          schema-result (parser/schema-wchnt->schema-ast schema)
          construction "[:Group [:Person \"John\"] [:Person \"Jane\"]]"
          result (parser/parse-construction-pure {:schema-ast (:value schema-result) :construction construction})]
      (is (:success result))
      (is (schema/valid-construction-ast? (:ast result)))
      (is (schema/valid-construction-syntax-result? result)))))

(deftest test-map-construction-ast-validation
  (testing "Map construction AST validation works with real parser output"
    (let [schema "Config = {String : Int}/settings"
          schema-result (parser/schema-wchnt->schema-ast schema)
          construction "[:Config [:Map/{String:Int} \"key1\":42 \"key2\":100]]"
          result (parser/parse-construction-pure {:schema-ast (:value schema-result) :construction construction})]
      (is (:success result))
      (is (schema/valid-construction-ast? (:ast result)))
      (is (schema/valid-construction-syntax-result? result)))))

(deftest test-ast-schema-invariants
  (testing "AST schemas enforce structural invariants"
    ;; Test that schema ASTs always start with :Schema
    (let [schema-input "Game = Ball"
          result (parser/schema-wchnt->schema-ast schema-input)]
      (when (:success result)
        (let [ast (:ast result)]
          (is (= :Schema (first ast)))
          (is (vector? ast))
          (is (every? vector? (rest ast))))))
    
    ;; Test that construction ASTs always start with :MultiStepConstruction
    (let [schema "Point = Float/x Float/y"
          schema-result (parser/schema-wchnt->schema-ast schema)
          construction "[:Point 3.14 2.718]"
          result (parser/parse-construction-pure {:schema-ast (:value schema-result) :construction construction})]
      (when (:success result)
        (let [ast (:ast result)]
          (is (= :MultiStepConstruction (first ast)))
          (is (vector? ast))
          (is (every? vector? (rest ast)))))))) 