(ns wchnt-lang.construction-parsing
  (:require [clojure.test :refer :all]
            [wchnt-lang.parser :refer :all]
            [wchnt-lang.schema :refer :all]
            [wchnt-lang.haxegen :as haxe-gen]
            [instaparse.core :as insta]
            [clojure.string :as str]))


(deftest test-parse-final-construction-statement
    (testing "Parse final construction statement using WCHNT pattern"
      (let [schema-input "Person = String String
Group = [Person]"
            construction-input "[:Group [:Person \"John\" \"Smith\"]]"
            schema-parse-result (schema-wchnt->schema-ast schema-input)]
        (is (:success schema-parse-result))
        (let [schema-ast (:value schema-parse-result)
              parse-result (parse-construction-pure {:schema-ast schema-ast :construction construction-input})]
          (is (:success parse-result))
          (let [parsed-ast (:value parse-result)]
            (is (map? parsed-ast))
            (is (= :MultiStepConstruction (:type parsed-ast)))
            (is (empty? (:assignments parsed-ast)))
            (is (vector? (:final-construction parsed-ast)))
            (is (= :GroupConstruction (first (:final-construction parsed-ast)))))))))

(deftest test-classify-and-parse-statements
    (testing "Parse mixed assignment and final construction statements using WCHNT pattern"
      (let [schema-input "Person = String String\nGroup = [Person]\nSchool = Group\nTeam = Group\nTown = School Team"
            construction-input "$people = [:Group [:Person \"John\" \"Smith\"]]. [:Town [:School $people] [:Team $people]]"
            schema-parse-result (schema-wchnt->schema-ast schema-input)]
        (is (:success schema-parse-result))
        (let [schema-ast (:value schema-parse-result)
              parse-result (parse-construction-pure {:schema-ast schema-ast :construction construction-input})]
          (is (:success parse-result))
          (let [parsed-ast (:value parse-result)]
            (is (map? parsed-ast))
            (is (= :MultiStepConstruction (:type parsed-ast)))
            (is (= 1 (count (:assignments parsed-ast))))
            (is (= "people" (:name (first (:assignments parsed-ast)))))
            (is (vector? (:final-construction parsed-ast)))
            (is (= :TownConstruction (first (:final-construction parsed-ast)))))))))



(deftest test-join-multi-line-assignments
    (testing "Parse multi-line assignment with full-stop separator"
      (let [schema "Person = String String\nGroup = [Person]\nSchool = Group\nTeam = Group\nTown = School Team"
            input "$people = [:Group [:Person \"John\" \"Smith\"] [:Person \"Jane\" \"Jones\"]]. [:Town [:School $people] [:Team $people]]"
            schema-parse-result (schema-wchnt->schema-ast schema)]
        (is (:success schema-parse-result))
        (let [schema-ast (:value schema-parse-result)
              result (parse-construction-pure {:schema-ast schema-ast :construction input})]
          (is (:success result))
          (let [parsed-ast (:value result)]
            (is (map? parsed-ast))
            (is (= :MultiStepConstruction (:type parsed-ast)))
            (is (= 1 (count (:assignments parsed-ast))))
            (is (= "people" (:name (first (:assignments parsed-ast)))))))))

    (testing "Handle single-line assignments with full-stop separator"
      (let [schema "Person = String String\nGroup = [Person]\nSchool = Group\nTeam = Group\nTown = School Team"
            input "$people = [:Group [:Person \"John\" \"Smith\"]]. [:Town [:School $people] [:Team $people]]"
            schema-parse-result (schema-wchnt->schema-ast schema)]
        (is (:success schema-parse-result))
        (let [schema-ast (:value schema-parse-result)
              result (parse-construction-pure {:schema-ast schema-ast :construction input})]
          (is (:success result))
          (let [parsed-ast (:value result)]
            (is (map? parsed-ast))
            (is (= :MultiStepConstruction (:type parsed-ast)))
            (is (= 1 (count (:assignments parsed-ast))))
            (is (= "people" (:name (first (:assignments parsed-ast)))))))))

    (testing "Handle full-stops inside strings"
      (let [schema "Person = String"
            input "$msg = \"Hello. World\". [:Person $msg]"
            schema-parse-result (schema-wchnt->schema-ast schema)]
        (is (:success schema-parse-result))
        (let [schema-ast (:value schema-parse-result)
              result (parse-construction-pure {:schema-ast schema-ast :construction input})]
          (is (:success result))
          (let [parsed-ast (:value result)]
            (is (map? parsed-ast))
            (is (= :MultiStepConstruction (:type parsed-ast)))
            (is (= 1 (count (:assignments parsed-ast))))
            (is (= "msg" (:name (first (:assignments parsed-ast))))))))))


(deftest test-valid-multi-step-construction
    (testing "Validate correct multi-step construction AST"
      (let [ast {:type :MultiStepConstruction
                 :assignments [{:name "people" :construction [:Group [:Person "John" "Smith"]]}]
                 :final-construction [:Town [:School "$people"] [:Team "$people"]]}]
        (is (valid-multi-step-construction? ast))))

    (testing "Reject invalid multi-step construction AST"
      (let [ast {:type :MultiStepConstruction
                 :assignments "not a sequence"
                 :final-construction [:Town]}]
        (is (not (valid-multi-step-construction? ast))))))



(deftest test-variable-reference-parsing
    (testing "Parse variable reference with $ sigil"
      (let [grammar-string "Construction = VariableReference | PersonConstruction\n                         VariableReference = '$' VariableName\n                         VariableName = #'[a-zA-Z_][a-zA-Z0-9_]*'\n                         PersonConstruction = '[' ':' 'Person' <WS>? StringLiteral <WS>+ StringLiteral <WS>? ']'\n                         StringLiteral = '\"' #'[^\"]*' '\"'\n                         WS = #'[\\s,]+'"
            construction-parser (insta/parser grammar-string)
            result (construction-parser "$people")]
        (is (not (insta/failure? result)))
        (is (= [:Construction [:VariableReference "$" [:VariableName "people"]]] result))))

    (testing "Parse class construction with variable reference"
      (let [grammar-string "Construction = VariableReference | PersonConstruction | SchoolConstruction\n                         VariableReference = '$' VariableName\n                         VariableName = #'[a-zA-Z_][a-zA-Z0-9_]*'\n                         PersonConstruction = '[' ':' 'Person' <WS>? StringLiteral <WS>+ StringLiteral <WS>? ']'\n                         SchoolConstruction = '[' ':' 'School' <WS>? VariableReference <WS>? ']'\n                         StringLiteral = '\"' #'[^\"]*' '\"'\n                         WS = #'[\\s,]+'"
            construction-parser (insta/parser grammar-string)
            result (construction-parser "[:School $people]")]
        (is (not (insta/failure? result)))
        (is (= [:Construction [:SchoolConstruction "[" ":" "School" [:VariableReference "$" [:VariableName "people"]] "]"]] result))))

    (testing "Reject variable reference without $ sigil"
      (let [grammar-string "Construction = VariableReference | PersonConstruction
                         VariableReference = '$' VariableName
                         VariableName = #'[a-zA-Z_][a-zA-Z0-9_]*'
                         PersonConstruction = '[' ':' 'Person' <WS>? StringLiteral <WS>+ StringLiteral <WS>? ']'
                         StringLiteral = '\"' #'[^\"]*' '\"'
                         WS = #'[\\s,]+'"
            construction-parser (insta/parser grammar-string)
            result (construction-parser "people")]
        (is (insta/failure? result)))))


(deftest test-parse-assignment-statement
  (testing "Parse simple assignment statement using WCHNT pattern"
    (let [schema-input "Person = String String
Group = [Person]"
          construction-input "$people = [:Group [:Person \"John\" \"Smith\"]]. [:Group $people]"
          schema-parse-result (schema-wchnt->schema-ast schema-input)]
      (is (:success schema-parse-result))
      (let [schema-ast (:value schema-parse-result)
            parse-result (parse-construction-pure {:schema-ast schema-ast :construction construction-input})]
        (is (:success parse-result))
        (let [parsed-ast (:value parse-result)]
          (is (map? parsed-ast))
          (is (= :MultiStepConstruction (:type parsed-ast)))
          (is (= 1 (count (:assignments parsed-ast))))
          (is (= "people" (:name (first (:assignments parsed-ast)))))))))
  (testing "Parse assignment with multi-line content using WCHNT pattern"
    (let [schema-input "Person = String String
Group = [Person]"
          construction-input "$people = [:Group [:Person \"John\" \"Smith\"] [:Person \"Jane\" \"Jones\"]]. [:Group $people]"
          schema-parse-result (schema-wchnt->schema-ast schema-input)]
      (is (:success schema-parse-result))
      (let [schema-ast (:value schema-parse-result)
            parse-result (parse-construction-pure {:schema-ast schema-ast :construction construction-input})]
        (is (:success parse-result))
        (let [parsed-ast (:value parse-result)]
          (is (map? parsed-ast))
          (is (= :MultiStepConstruction (:type parsed-ast)))
          (is (= 1 (count (:assignments parsed-ast))))
          (is (= "people" (:name (first (:assignments parsed-ast))))))))))

(deftest test-assignments-without-final-construction
  (testing "Construction with only assignments and no final construction should fail"
    (let [schema-input "Person = String String\nGroup = [Person]"
          construction-input "$people = [:Group [:Person \"John\" \"Smith\"]]"
          schema-parse-result (schema-wchnt->schema-ast schema-input)]
      (is (:success schema-parse-result))
      (let [schema-ast (:value schema-parse-result)
            parse-result (parse-construction-pure {:schema-ast schema-ast :construction construction-input})]
        (is (not (:success parse-result)))
        (is (some #(str/includes? % "final construction") (:errors parse-result)))))))

(deftest test-assignments-with-final-construction
  (testing "Construction with assignments and a final construction should succeed"
    (let [schema-input "Person = String String\nGroup = [Person]"
          construction-input "$people = [:Group [:Person \"John\" \"Smith\"]]. [:Group $people]"
          schema-parse-result (schema-wchnt->schema-ast schema-input)]
      (is (:success schema-parse-result))
      (let [schema-ast (:value schema-parse-result)
            parse-result (parse-construction-pure {:schema-ast schema-ast :construction construction-input})]
        (is (:success parse-result))
        (let [parsed-ast (:value parse-result)]
          (is (map? parsed-ast))
          (is (= :MultiStepConstruction (:type parsed-ast)))
          (is (= 1 (count (:assignments parsed-ast))))
          (is (= "people" (:name (first (:assignments parsed-ast)))))
          (is (vector? (:final-construction parsed-ast)))
          (is (= :GroupConstruction (first (:final-construction parsed-ast)))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

