(ns wchnt-lang.parser-test
  (:require [clojure.test :refer :all]
            [instaparse.core :as insta]
            [wchnt-lang.parser :as parser]
            [wchnt-lang.pipeline :as p]
            [wchnt-lang.schema :as schema]
            [wchnt-lang.mainfile :as mainfile]))

(defn ast-contains? [pred ast]
  (cond
    (pred ast) true
    (vector? ast) (some #(ast-contains? pred %) ast)
    (seq? ast) (some #(ast-contains? pred %) ast)
    :else false))


(deftest split-wchnt-phases-test
  (testing "split-wchnt-phases handles schema-only files"
    (let [input "## Schema\n```\nPerson = String/name String/address\nSchool = [Person]/students\n```"
          result (mainfile/parse-mainfile input)]
      (is (:success result))
      (is (= "Person = String/name String/address\nSchool = [Person]/students" (:schema (:value result))))
      (is (= "" (:construction (:value result))))))
  
  (testing "split-wchnt-phases handles schema+construction files"
    (let [input "## Schema\n```\nPerson = String/name\n```\n## Construction\n```\n[:Person \"John\"]\n```"
          result (mainfile/parse-mainfile input)]
      (is (:success result))
      (is (= "Person = String/name" (:schema (:value result))))
      (is (= "[:Person \"John\"]" (:construction (:value result))))))
  
  (testing "split-wchnt-phases handles files with extra whitespace"
    (let [input "## Schema\n```\n  Person = String/name  \n```\n## Construction\n```\n  [:Person \"John\"]  \n```"
          result (mainfile/parse-mainfile input)]
      (is (:success result))
      (is (= "  Person = String/name  " (:schema (:value result))))
      (is (= "  [:Person \"John\"]  " (:construction (:value result))))))
  
  (testing "split-wchnt-phases handles empty construction phase"
    (let [input "## Schema\n```\nPerson = String/name\n```\n## Construction\n```\n```"
          result (mainfile/parse-mainfile input)]
      (is (:success result))
      (is (= "Person = String/name" (:schema (:value result))))
      (is (= "" (:construction (:value result))))))
  
  (testing "split-wchnt-phases handles complex multi-line input"
    (let [input "## Schema\n```\nPerson = String/name String/address\nSchool = [Person]/students\n```\n## Construction\n```\n$people = [:Array [:Person \"John\"]]\n[:School $people]\n```"
          result (mainfile/parse-mainfile input)]
      (is (:success result))
      (is (= "Person = String/name String/address\nSchool = [Person]/students" (:schema (:value result))))
      (is (= "$people = [:Array [:Person \"John\"]]\n[:School $people]" (:construction (:value result)))))))




(deftest test-float-and-bool-literals
  (testing "parses Float literals with decimal points"
    (let [schema "Point = Float/x Float/y"
          schema-result (parser/schema-wchnt->schema-ast schema)
          input "[:Point 3.14 2.718]"
          result (parser/parse-construction-pure {:schema-ast (:value schema-result) :construction input})]
      (is (:success result))
      (is (schema/valid-multi-step-construction? (:value result)))
      (is (ast-contains? #(and (vector? %) (= (first %) :FloatLiteral)) (:final-construction (:value result))))))

  (testing "parses Bool literals"
    (let [schema "Config = Bool/active Bool/enabled"
          schema-result (parser/schema-wchnt->schema-ast schema)
          input "[:Config true false]"
          result (parser/parse-construction-pure {:schema-ast (:value schema-result) :construction input})]
      (is (:success result))
      (is (schema/valid-multi-step-construction? (:value result)))
      (is (ast-contains? #(and (vector? %) (= (first %) :BoolLiteral)) (:final-construction (:value result))))))

  (testing "parses false Bool literal"
    (let [schema "Config = Bool/active"
          schema-result (parser/schema-wchnt->schema-ast schema)
          input "[:Config false]"
          result (parser/parse-construction-pure {:schema-ast (:value schema-result) :construction input})]
      (is (:success result))
      (is (schema/valid-multi-step-construction? (:value result)))
      (is (ast-contains? #(and (vector? %) (= (first %) :BoolLiteral)) (:final-construction (:value result))))))
  )


(deftest test-float-literals-in-construction
  (testing "parses Float literals in class construction"
    (let [schema "Point = Float/x Float/y"
          schema-result (parser/schema-wchnt->schema-ast schema)
          input "[:Point 3.14 2.718]"
          result (parser/parse-construction-pure {:schema-ast (:value schema-result) :construction input})]
      (is (:success result))
      (is (schema/valid-multi-step-construction? (:value result)))
      (is (ast-contains? #(and (vector? %) (= (first %) :FloatLiteral)) (:final-construction (:value result))))))
  
  (testing "parses Bool literals in class construction"
    (let [schema "Config = Bool/active Bool/enabled"
          schema-result (parser/schema-wchnt->schema-ast schema)
          input "[:Config true false]"
          result (parser/parse-construction-pure {:schema-ast (:value schema-result) :construction input})]
      (is (:success result))
      (is (schema/valid-multi-step-construction? (:value result)))
      (is (ast-contains? #(and (vector? %) (= (first %) :BoolLiteral)) (:final-construction (:value result)))))))



(deftest test-float-literals-dont-break-statement-separation
  (testing "decimal points in floats don't break multi-statement construction"
    (let [schema "Point = Float/x Float/y"
          schema-result (parser/schema-wchnt->schema-ast schema)
          input "$x = 3.14. $y = 2.718. [:Point $x $y]"
          result (parser/parse-construction-pure {:schema-ast (:value schema-result) :construction input})]
      (is (:success result))
      (is (schema/valid-multi-step-construction? (:value result)))
      (println "XXXX ")
      (println result)
      (is (some #(and (vector? %) (= (first %) :FloatLiteral)) (map :construction (:assignments (:value result)))))
      (is (ast-contains? #(and (vector? %)
                               (= (first %) :PointConstruction)) (:final-construction (:value result))))))

  (testing "floats in strings don't break statement separation"
    (let [schema "Config = String/message"
          schema-result (parser/schema-wchnt->schema-ast schema)
          input "$msg = \"The value is 3.14\". [:Config $msg]"
          result (parser/parse-construction-pure {:schema-ast (:value schema-result) :construction input})]
      (is (:success result))
      (is (schema/valid-multi-step-construction? (:value result)))
      (is (some #(and (vector? %) (= (first %) :StringLiteral)) (map :construction (:assignments (:value result)))))
      (is (ast-contains? #(and (vector? %) (= (first %) :ConfigConstruction)) (:final-construction (:value result))))))


  (testing "floats in class construction don't break statement separation"
    (let [schema "Point = Float/x Float/y\nLine = Float/x Float/y"
          schema-result (parser/schema-wchnt->schema-ast schema)
          input "[:Point 3.14 2.718]. [:Line 1.5 2.5]"
          result (parser/parse-construction-pure {:schema-ast (:value schema-result) :construction input})]
      (is (:success result))
      (is (schema/valid-multi-step-construction? (:value result)))
      ;; Only the last statement becomes the final construction
      (is (ast-contains? #(and (vector? %) (= (first %) :LineConstruction)) (:final-construction (:value result)))))))


(deftest test-split-statements-debug
  (testing "debug what split-statements returns"
    (let [input "$x = 3.14. $y = 2.718. [:Point $x $y]"
          result (parser/split-statements input)]
      (println "Input:" input)
      (println "Result:" result)
      (println "Result count:" (count result))
      (is true))))


(deftest test-split-statements-with-floats
  (testing "splits statements correctly when floats contain decimal points"
    (let [input "$x = 3.14. $y = 2.718. [:Point $x $y]"
          result (parser/split-statements input)]
      (is (= 3 (count result)))
      (is (= "$x = 3.14" (first result)))
      (is (= "$y = 2.718" (second result)))
      (is (= "[:Point $x $y]" (nth result 2)))))
  
  (testing "doesn't split on decimal points in floats"
    (let [input "3.14 2.718"
          result (parser/split-statements input)]
      (is (= 1 (count result)))
      (is (= "3.14 2.718" (first result)))))
  
  (testing "handles floats in strings correctly"
    (let [input "$msg = \"The value is 3.14\". [:Config $msg]"
          result (parser/split-statements input)]
      (is (= 2 (count result)))
      (is (= "$msg = \"The value is 3.14\"" (first result)))
      (is (= "[:Config $msg]" (second result))))))



(deftest test-mixed-primitive-literals
  (testing "parses mixed primitive literals in construction"
    (let [schema "Mixed = Int/num Float/pi Bool/active String/name Bool/enabled"
          schema-result (parser/schema-wchnt->schema-ast schema)
          input "[:Mixed 42 3.14 true \"hello\" false]"
          result (parser/parse-construction-pure {:schema-ast (:value schema-result) :construction input})]
      (is (:success result))
      (is (schema/valid-multi-step-construction? (:value result)))
      (is (ast-contains? #(and (vector? %) (= (first %) :IntLiteral)) (:final-construction (:value result))))
      (is (ast-contains? #(and (vector? %) (= (first %) :FloatLiteral)) (:final-construction (:value result))))
      (is (ast-contains? #(and (vector? %) (= (first %) :BoolLiteral)) (:final-construction (:value result))))
      (is (ast-contains? #(and (vector? %) (= (first %) :StringLiteral)) (:final-construction (:value result)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
