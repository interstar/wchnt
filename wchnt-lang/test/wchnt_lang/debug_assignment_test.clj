(ns wchnt-lang.debug-assignment-test
  (:require [clojure.test :refer :all]
            [wchnt-lang.parser :as parser]))

(deftest debug-assignment-structure
  (testing "Debug assignment AST structure"
    (let [construction-text "ps = [:Array/Player [10 10 \"John\"] [50 70 \"Sally\"]]. scores = [:Scores 0 0]. [:Game [:PlayArea [0 0 500 400]] [:Football 0 0 5] [:Array/Team [\"West Ham\" ps] [\"Crystal Palace\" [:Array/Player [40 90 \"Bob\"]]]] ps scores]"
          construction-cargo (parser/parse-construction-unified construction-text)
          construction-ast (:value construction-cargo)]
      
      (println "Full AST structure:")
      (println (pr-str construction-ast))
      
      (println "\nStatements:")
      (let [statements (rest construction-ast)]
        (doseq [[idx statement] (map-indexed vector statements)]
          (println (str "Statement " idx ": " (pr-str statement)))))
      
      (println "\nLooking for Assignment nodes:")
      (let [statements (rest construction-ast)
            assignments (filter #(and (vector? %) (= (first %) :Assignment)) statements)]
        (println "Found assignments:" (count assignments))
        (doseq [assignment assignments]
          (println (pr-str assignment))))
      
      (println "\nLooking for Expression nodes:")
      (let [statements (rest construction-ast)
            expressions (filter #(and (vector? %) (= (first %) :Expression)) statements)]
        (println "Found expressions:" (count expressions))
        (doseq [expression expressions]
          (println (pr-str expression))))
      
      ;; Just pass the test for now
      (is true))))
