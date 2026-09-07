(ns wchnt-lang.assignment-mapping-test
  (:require [clojure.test :refer [deftest testing is]]
            [wchnt-lang.ast-to-ir :as ast-to-ir]))

(deftest test-assignment-mapping-simple-array
  (testing "Assignment should map variable to the outermost object created"
    (let [schema-ir {:assemblages [{:name "Person" :components [{:type-name "String"} {:type-name "String"}]}
                                  {:name "Root" :components [{:type-name "Person"}]}]}
          construction-ast [:BlockStatements
                           [:Assignment [:VariableName "people"] 
                            [:Expression [:ArrayConstruction [:Type "Person"] 
                                         [:ArgList "John" "Smith" "Jane" "Doe"]]]]
                           [:ObjectConstruction [:ClassName "Root"] 
                            [:ArgList [:VariableRef "people"]]]]
          result (ast-to-ir/construction-ast-to-ir construction-ast schema-ir)
          variable-mappings (:variable-mappings result)
          objects (:objects result)]
      
      ;; The variable should be mapped to the array object (the outermost object)
      (is (contains? variable-mappings "people"))
      (let [array-obj-id (get variable-mappings "people")
            array-obj (get objects array-obj-id)]
        (is (= :array (:type array-obj)))
        (is (= "Person" (:class-name array-obj)))))))

(deftest test-assignment-mapping-nested-objects
  (testing "Assignment should map variable to the outermost object, not inner objects"
    (let [schema-ir {:assemblages [{:name "Person" :components [{:type-name "String"} {:type-name "String"}]}
                                  {:name "School" :components [{:type-name "Person"}]}
                                  {:name "Root" :components [{:type-name "School"}]}]}
          construction-ast [:BlockStatements
                           [:Assignment [:VariableName "school"] 
                            [:Expression [:ObjectConstruction [:ClassName "School"] 
                                         [:ArgList [:Person "John" "Smith"]]]]]
                           [:ObjectConstruction [:ClassName "Root"] 
                            [:ArgList [:VariableRef "school"]]]]
          result (ast-to-ir/construction-ast-to-ir construction-ast schema-ir)
          variable-mappings (:variable-mappings result)
          objects (:objects result)]
      
      ;; The variable should be mapped to the School object (the outermost object)
      (is (contains? variable-mappings "school"))
      (let [school-obj-id (get variable-mappings "school")
            school-obj (get objects school-obj-id)]
        (is (= :object (:type school-obj)))
        (is (= "School" (:class-name school-obj)))))))

(deftest test-assignment-mapping-multiple-assignments
  (testing "Multiple assignments should create separate mappings"
    (let [schema-ir {:assemblages [{:name "Person" :components [{:type-name "String"} {:type-name "String"}]}
                                  {:name "Root" :components [{:type-name "Person"} {:type-name "Person"}]}]}
          construction-ast [:BlockStatements
                           [:Assignment [:VariableName "person1"] 
                            [:Expression [:ObjectConstruction [:ClassName "Person"] 
                                         [:ArgList "John" "Smith"]]]]
                           [:Assignment [:VariableName "person2"] 
                            [:Expression [:ObjectConstruction [:ClassName "Person"] 
                                         [:ArgList "Jane" "Doe"]]]]
                           [:ObjectConstruction [:ClassName "Root"] 
                            [:ArgList [:VariableRef "person1"] [:VariableRef "person2"]]]]
          result (ast-to-ir/construction-ast-to-ir construction-ast schema-ir)
          variable-mappings (:variable-mappings result)]
      
      ;; Each variable should be mapped to its own object
      (is (contains? variable-mappings "person1"))
      (is (contains? variable-mappings "person2"))
      (is (not= (get variable-mappings "person1") 
                (get variable-mappings "person2"))))))
