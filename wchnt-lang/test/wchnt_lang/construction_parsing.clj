(ns wchnt-lang.construction-parsing
  (:require [clojure.test :refer :all]
            [wchnt-lang.parser :refer :all]
            [wchnt-lang.schema :refer :all]
            [wchnt-lang.haxegen :as haxe-gen]
            [instaparse.core :as insta]))

(deftest test-parse-assignment-statement
  (testing "Parse simple assignment statement using WCHNT pattern"
    (let [schema-input "Person = String String
Group = [Person]"
          construction-input "$people = [:Group [:Person \"John\" \"Smith\"]]"
          result (haxe-gen/generate-construction-factory schema-input construction-input {})]
      (is (:success result))
      (is (some? (:haxe-code result)))))

  (testing "Parse assignment with multi-line content using WCHNT pattern"
    (let [schema-input "Person = String String
Group = [Person]"
          construction-input "$people = [:Group [:Person \"John\" \"Smith\"] [:Person \"Jane\" \"Jones\"]]"
          result (haxe-gen/generate-construction-factory schema-input construction-input {})]
      (is (:success result))
      (is (some? (:haxe-code result)))))

  (testing "Return nil for non-assignment statement"
    (let [schema-input "Person = String String"
          construction-input "[:Person]"
          result (haxe-gen/generate-construction-factory schema-input construction-input {})]
      (is (not (:success result))))))

(deftest test-parse-final-construction-statement
  (testing "Parse final construction statement using WCHNT pattern"
    (let [schema-input "Person = String String
Group = [Person]"
          construction-input "[:Group [:Person \"John\" \"Smith\"]]"
          result (haxe-gen/generate-construction-factory schema-input construction-input {})]
      (is (:success result))
      (is (some? (:haxe-code result))))))

(deftest test-classify-and-parse-statements
  (testing "Parse mixed assignment and final construction statements using WCHNT pattern"
    (let [schema-input "Person = String String\nGroup = [Person]\nSchool = Group\nTeam = Group\nTown = School Team"
          construction-input "$people = [:Group [:Person \"John\" \"Smith\"]]. [:Town [:School $people] [:Team $people]]"
          result (haxe-gen/generate-construction-factory schema-input construction-input {})]
      (when (not (:success result))
        (println "DEBUG: Error in classify-and-parse-statements:" (:error result)))
      (is (:success result))
      (is (some? (:haxe-code result))))))

(deftest test-join-multi-line-assignments
  (testing "Parse multi-line assignment with full-stop separator"
    (let [schema "Person = String String\nGroup = [Person]\nSchool = Group\nTeam = Group\nTown = School Team"
          input "$people = [:Group [:Person \"John\" \"Smith\"] [:Person \"Jane\" \"Jones\"]]. [:Town [:School $people] [:Team $people]]"
          result (parse-construction schema input)]
      (when (not (:success result))
        (println "DEBUG: Error in multi-line assignment:" (:error result)))
      (is (:success result))
      (is (= :MultiStepConstruction (:type (:ast result))))
      (is (= 1 (count (:assignments (:ast result)))))
      (is (= "people" (:name (first (:assignments (:ast result))))))))

  (testing "Handle single-line assignments with full-stop separator"
    (let [schema "Person = String String\nGroup = [Person]\nSchool = Group\nTeam = Group\nTown = School Team"
          input "$people = [:Group [:Person \"John\" \"Smith\"]]. [:Town [:School $people] [:Team $people]]"
          result (parse-construction schema input)]
      (when (not (:success result))
        (println "DEBUG: Error in single-line assignment:" (:error result)))
      (is (:success result))
      (is (= :MultiStepConstruction (:type (:ast result))))
      (is (= 1 (count (:assignments (:ast result)))))
      (is (= "people" (:name (first (:assignments (:ast result))))))))

  (testing "Handle full-stops inside strings"
    (let [schema "Person = String"
          input "$msg = \"Hello. World\". [:Person $msg]"
          result (parse-construction schema input)]
      (when (not (:success result))
        (println "DEBUG: Error in full-stops inside strings:" (:error result)))
      (is (:success result))
      (is (= :MultiStepConstruction (:type (:ast result))))
      (is (= 1 (count (:assignments (:ast result)))))
      (is (= "msg" (:name (first (:assignments (:ast result)))))))))

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