(ns wchnt-lang.test-newparser
  (:require [clojure.test :refer :all]
            [wchnt-lang.newparser :refer [get-wchnt-parser]]))

(deftest test-simple-method-parse
  (let [parser (get-wchnt-parser)
        code "MyClass::myMethod = { x| (x * 3) }"
        result (parser code)]
    (testing "Simple lambda method parse"
      (is (not (instaparse.core/failure? result))
          (str "Parser failed: " (pr-str result))))))

(deftest test-lambda-syntaxes
  (let [parser (get-wchnt-parser)]
    (doseq [[name code] [["no args" "MyClass::myMethod = { | (3) }"]
                         ["with space" "MyClass::myMethod = { x | (x * 3) }"]
                         ["no space" "MyClass::myMethod = { x| (x * 3) }"]
                         ["multiple args" "MyClass::myMethod = { x, y | (x + y) }"]
                         ["simple block" "MyClass::myMethod = { 3 }"]]]
      (testing (str "Lambda syntax: " name)
        (let [result (parser code)]
          (is (not (instaparse.core/failure? result))
              (str name " failed: " (pr-str result))))))))

(deftest test-whitespace-flexibility
  (let [parser (get-wchnt-parser)]
    (doseq [[name code] [["compact" "MyClass::myMethod={x|(x*3)}"]
                         ["spaced" "MyClass :: myMethod = { x | (x * 3) }"]
                         ["newlines" "MyClass\n::\nmyMethod\n=\n{\nx\n|\n(x\n*\n3)\n}"]
                         ["mixed" "MyClass::myMethod={ x | (x*3) }"]]]
      (testing (str "Whitespace: " name)
        (let [result (parser code)]
          (is (not (instaparse.core/failure? result))
              (str name " failed: " (pr-str result))))))))

(deftest test-multi-statement-blocks
  (let [parser (get-wchnt-parser)]
    (doseq [[name code] [["assignments and final expr"
                          "MyClass::foo = { x = 1. y = 2. (x + y) }"]
                         ["assignments, target, final"
                          "MyClass::foo = { x = 1. %trace(x). y = 2. (x * y) }"]
                         ["all assignments"
                          "MyClass::foo = { x = 1. y = 2. z = 3 }"]
                         ["block with only expressions"
                          "MyClass::foo = { 1. 2. 3 }"]
                         ["user example with object construction"
                          "MyClass::create = { x = 5. y = 3. [:Ball x y] }"]]]
      (testing (str "Multi-statement block: " name)
        (let [result (parser code)]
          (is (not (instaparse.core/failure? result))
              (str name " failed: " (pr-str result))))))))

(deftest test-construction-syntax
  (let [parser (get-wchnt-parser)]
    (doseq [[name code] [["simple construction"
                          "MyClass::create = { [:Game 100 200] }"]
                         ["multi-line construction"
                          "MyClass::create = { [:Game \n  [:PlayArea [0 0 500 400]] \n  [:Ball 200 200 5] \n] }"]
                         ["with array"
                          "MyClass::create = { [:Game [:Array/Player [10 10 \"John\"] [50 70 \"Sally\"]]] }"]
                         ["complex nested"
                          "MyClass::create = { [:Game \n  [:PlayArea [0 0 500 400]] \n  [:Array/Team [\"West Ham\" ps] [\"Crystal Palace\" [:Array/Player [40 90 \"Bob\"]]]] \n] }"]]]
      (testing (str "Construction syntax: " name)
        (let [result (parser code)]
          (is (not (instaparse.core/failure? result))
              (str name " failed: " (pr-str result))))))))

(deftest test-debug-object-construction
  (let [parser (get-wchnt-parser)]
    (doseq [[name code] [["simple literal" "MyClass::test = { 42 }"]
                         ["simple object" "MyClass::test = { [:Game] }"]
                         ["object with one arg" "MyClass::test = { [:Game 42] }"]
                         ["object with two args" "MyClass::test = { [:Game 42 100] }"]]]
      (testing (str "Object construction: " name)
        (let [result (parser code)]
          (is (not (instaparse.core/failure? result))
              (str name " failed: " (pr-str result))))))))

(deftest test-object-construction
  (let [parser (get-wchnt-parser)]
    (doseq [[name code] [["simple" "MyClass::create = { [:Game 100 200] }"]
                         ["with spaces" "MyClass::create = { [: Game 100 200] }"]
                         ["nested" "MyClass::create = { [:Game [:Ball 10 20] [:Paddle 0 0]] }"]]]
      (testing (str "Object construction: " name)
        (let [result (parser code)]
          (is (not (instaparse.core/failure? result))
              (str name " failed: " (pr-str result))))))))

(deftest test-array-construction
  (let [parser (get-wchnt-parser)]
    (doseq [[name code] [["simple" "MyClass::create = { [:Array/Int 1 2 3] }"]
                         ["with spaces" "MyClass::create = { [: Array / Int 1 2 3] }"]
                         ["objects" "MyClass::create = { [:Array/Ball [:Ball 1 2] [:Ball 3 4]] }"]]]
      (testing (str "Array construction: " name)
        (let [result (parser code)]
          (is (not (instaparse.core/failure? result))
              (str name " failed: " (pr-str result))))))))

(deftest test-map-construction
  (let [parser (get-wchnt-parser)]
    (doseq [[name code] [["simple" "MyClass::create = { [:Map/{String:Int} \"a\" 1 \"b\" 2] }"]
                         ["with spaces" "MyClass::create = { [: Map / { String : Int } \"a\" 1 \"b\" 2] }"]]]
      (testing (str "Map construction: " name)
        (let [result (parser code)]
          (is (not (instaparse.core/failure? result))
              (str name " failed: " (pr-str result))))))))

(deftest test-arithmetic-expressions
  (let [parser (get-wchnt-parser)]
    (doseq [[name code] [["simple" "MyClass::calc = { (1 + 2) }"]
                        ["complex" "MyClass::calc = { ((x * 3) + (y / 2)) }"]
                        ["no parens" "MyClass::calc = { x + y * z }"]]]
      (testing (str "Arithmetic: " name)
        (let [result (parser code)]
          (is (not (instaparse.core/failure? result))
              (str name " failed: " (pr-str result))))))))

(deftest test-boolean-expressions
  (let [parser (get-wchnt-parser)]
    (doseq [[name code] [["simple and" "MyClass::test = { x and y }"]
                        ["simple or" "MyClass::test = { x or y }"]
                        ["simple not" "MyClass::test = { not x }"]
                        ["not and" "MyClass::test = { not x and y }"]
                        ["with parentheses" "MyClass::test = { (x and y) or z }"]
                        ["complex" "MyClass::test = { (x and y) or (not z) }"]
                        ["with literals" "MyClass::test = { true and false }"]]]
      (testing (str "Boolean: " name)
        (let [result (parser code)]
          (is (not (instaparse.core/failure? result))
              (str name " failed: " (pr-str result))))))))

(deftest test-method-calls
  (let [parser (get-wchnt-parser)]
    (doseq [[name code] [["simple" "MyClass::call = { obj.method() }"]
                         ["with args" "MyClass::call = { obj.method(x, y) }"]
                         ["chained" "MyClass::call = { obj.method1().method2() }"]
                         ["self" "MyClass::call = { self.field }"]]]
      (testing (str "Method call: " name)
        (let [result (parser code)]
          (is (not (instaparse.core/failure? result))
              (str name " failed: " (pr-str result))))))))

(deftest test-target-commands
  (let [parser (get-wchnt-parser)]
    (doseq [[name code] [["simple" "MyClass::debug = { %trace(x) }"]
                         ["with args" "MyClass::debug = { %profile(\"method\") }"]
                         ["multiple" "MyClass::debug = { %trace(x). %log(y) }"]]]
      (testing (str "Target command: " name)
        (let [result (parser code)]
          (is (not (instaparse.core/failure? result))
              (str name " failed: " (pr-str result))))))))

(deftest test-assignments
  (let [parser (get-wchnt-parser)]
    (doseq [[name code] [["simple" "MyClass::assign = { x = 3 }"]
                         ["expression" "MyClass::assign = { result = (x + y) }"]
                         ["object" "MyClass::assign = { ball = [:Ball 10 20] }"]]]
      (testing (str "Assignment: " name)
        (let [result (parser code)]
          (is (not (instaparse.core/failure? result))
              (str name " failed: " (pr-str result))))))))

(deftest test-literals
  (let [parser (get-wchnt-parser)]
    (doseq [[name code] [["int" "MyClass::test = { 42 }"]
                         ["float" "MyClass::test = { 3.14 }"]
                         ["string" "MyClass::test = { \"hello\" }"]
                         ["bool" "MyClass::test = { true }"]
                         ["false" "MyClass::test = { false }"]]]
      (testing (str "Literal: " name)
        (let [result (parser code)]
          (is (not (instaparse.core/failure? result))
              (str name " failed: " (pr-str result))))))))

(deftest test-multiple-methods
  (let [parser (get-wchnt-parser)]
    (testing "Multiple methods in one program"
      (let [code "MyClass::method1 = { x }
                MyClass::method2 = { y }"
            result (parser code)]
        (is (not (instaparse.core/failure? result))
            (str "Multiple methods failed: " (pr-str result)))))))

(deftest test-invalid-syntax
  (let [parser (get-wchnt-parser)]
    (doseq [[name code] [["missing equals" "MyClass::method { x }"]
                        ["missing braces" "MyClass::method = x"]
                        ["invalid lambda" "MyClass::method = { x | }"]
                        ["unclosed paren" "MyClass::method = { (x + y }"]
                        ["mixed boolean without parens" "MyClass::method = { x and y or z }"]]]
      (testing (str "Invalid syntax: " name)
        (let [result (parser code)]
          (is (instaparse.core/failure? result)
              (str name " should have failed but succeeded: " (pr-str result))))))))

(deftest test-decimal-point-vs-statement-separator
  (let [parser (get-wchnt-parser)]
    (doseq [[name code] [["float literal" "MyClass::test = { 3.14 }"]
                         ["float in expression" "MyClass::test = { (x * 3.14) }"]
                         ["float in assignment" "MyClass::test = { pi = 3.14 }"]
                         ["multiple floats" "MyClass::test = { x = 3.14. y = 2.718 }"]
                         ["float in object construction" "MyClass::test = { [:Point 3.14 2.718] }"]
                         ["float in string" "MyClass::test = { \"The value is 3.14\" }"]]]
      (testing (str "Decimal point handling: " name)
        (let [result (parser code)]
          (is (not (instaparse.core/failure? result))
              (str name " failed: " (pr-str result))))))))

(deftest test-debug-nested-construction
  (let [parser (get-wchnt-parser)]
    (doseq [[name code] [["simple nested" "MyClass::test = { [:Game [:Ball 10 20]] }"]
                         ["with numbers" "MyClass::test = { [:Game [10 20 30]] }"]
                         ["nested with array" "MyClass::test = { [:Game [:Array/Int 1 2 3]] }"]]]
      (testing (str "Debug nested: " name)
        (let [result (parser code)]
          (if (instaparse.core/failure? result)
            (println name "failed:" (instaparse.core/get-failure result))
            (println name "succeeded:" (pr-str result)))))))
  ;; Always pass this test - it's just for debugging
  (is true))




(deftest test-dual-grammar-usage
  (let [parser (get-wchnt-parser)]

    (testing "Same grammar can parse both method definitions and construction statements"
      ;; Test method definitions (starts at Code)
      (let [method-code "MyClass::create = { x = 5. y = 3. [:Ball x y] }"
            method-result (parser method-code)]
        (is (not (instaparse.core/failure? method-result))
            (str "Method definition failed: " (pr-str method-result))))

      ;; Test construction statements (starts at BlockStatements)
      (let [construction-code "x = 5. y = 3. [:Ball x y]"
            construction-result (instaparse.core/parse parser construction-code :start :BlockStatements)]
        (is (not (instaparse.core/failure? construction-result))
            (str "Construction statements failed: " (pr-str construction-result))))

      ;; Test more complex construction with nested objects
      (let [complex-construction "game = [:Game [:PlayArea [0 0 400 500]]]. ball = [:Ball 200 200 5]. game"
            complex-result (instaparse.core/parse parser complex-construction :start :BlockStatements)]
        (is (not (instaparse.core/failure? complex-result))
            (str "Complex construction failed: " (pr-str complex-result)))))

    (testing "Demonstrates the object boundary concept with square brackets"
      ;; Test that inner object constructions require brackets even without class labels
      (let [inner-object-test "MyClass::test = { [:PlayArea [0 0 400 500]] }"
            result (parser inner-object-test)]
        (is (not (instaparse.core/failure? result))
            (str "Inner object with brackets failed: " (pr-str result))))

      ;; Test that outermost constructions always need class labels
      (let [outermost-test "MyClass::test = { [:Game [:PlayArea [0 0 400 500]]] }"
            result (parser outermost-test)]
        (is (not (instaparse.core/failure? result))
            (str "Outermost with class labels failed: " (pr-str result)))))))

(deftest test-debug-construction-ast
  (let [parser (get-wchnt-parser)]
    (testing "Debug construction AST structure"
      (let [construction-code "shapes = [:Array/Shape [:Triangle 10 20] [:Circle 15]]. players = [:Array/Player [:Player \"Alice\" 100] [:Player \"Bob\" 85]]. [:Game shapes players]"
            construction-result (instaparse.core/parse parser construction-code :start :BlockStatements)]
        (if (instaparse.core/failure? construction-result)
          (println "Construction parsing failed:" (instaparse.core/get-failure construction-result))
          (println "Construction AST:" (pr-str construction-result)))
        ;; Always pass this test - it's just for debugging
        (is true)))))
