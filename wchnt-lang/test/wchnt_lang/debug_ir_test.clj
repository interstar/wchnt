(ns wchnt-lang.debug-ir-test
  (:require [clojure.test :refer :all]
            [wchnt-lang.parser :as parser]
            [wchnt-lang.ast-to-ir :as ast-to-ir]))

(deftest debug-ir-structure
  (testing "Debug IR structure for string array test"
    (let [construction-text "[:StringList [\"hello\" \"world\" \"test\"]]"
          construction-cargo (parser/parse-construction-unified construction-text)
          construction-ast (:value construction-cargo)
          schema-ir {:assemblages [{:name "StringList" :components [{:component-name "xs" :type-name "Array<String>"}]}]}
          result (ast-to-ir/construction-ast-to-ir construction-ast schema-ir)]
      
      (println "Construction AST:")
      (println (pr-str construction-ast))
      
      (println "\nIR Result:")
      (println (pr-str result))
      
      (println "\nObjects in IR:")
      (doseq [[obj-id obj-data] (:objects result)]
        (println (str obj-id ": " (pr-str obj-data))))
      
      ;; Just pass the test for now
      (is true))))
