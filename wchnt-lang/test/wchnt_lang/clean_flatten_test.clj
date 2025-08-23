(ns wchnt-lang.clean-flatten-test
  (:require [clojure.test :refer :all]
            [wchnt-lang.ast-to-ir :as ast-to-ir]))

;; Define test data outside functions to avoid paren issues
(def simple-person-node
  [:InnerObjectConstruction 
   [:ClassName "Person"] 
   [:ArgList [:StringLiteral "John"]]])

(def simple-construction-ast
  [:ObjectConstruction 
   [:ClassName "School"] 
   [:ArgList 
    [:InnerObjectConstruction 
     [:ClassName "Person"] 
     [:ArgList [:StringLiteral "John"]]]]])

(def test-schema-ir
  {:assemblages 
   [{:name "School" 
     :components [{:component-name "students" :type-name "Person"}]}
    {:name "Person" 
     :components [{:component-name "name" :type-name "String"}]}]})

(deftest test-get-explicit-class-name
  (testing "get-explicit-class-name extracts class names correctly"
    (let [result (ast-to-ir/get-explicit-class-name simple-person-node)]
      (is (= "Person" result)))))

(deftest test-flatten-nested-constructions
  (testing "refactored flattening works without circular references"
          (let [result (ast-to-ir/flatten-nested-constructions simple-construction-ast test-schema-ir)]
      (is (map? result))
      (is (contains? result :flattened-ast))
      (is (contains? result :nested-objects))
      (is (pos? (count (:nested-objects result))))
      
      ;; Check that no object references itself
      (let [nested-objects (:nested-objects result)]
        (doseq [[obj-id obj-data] nested-objects]
          (when (= :array (:type obj-data))
            (doseq [arg (:args obj-data)]
              (let [arg-val (second arg)]
                (when (= :VariableRef (first arg-val))
                  (is (not= obj-id (second arg-val)) 
                      (str "Object " obj-id " should not reference itself")))))))))))
