(ns wchnt-lang.grammar-test
  (:require [clojure.test :refer :all]
            [wchnt-lang.grammars :as grammars]
            [instaparse.core :as insta]))

(deftest test-grammar-literals
  (testing "Check what the grammar produces for literals"
    (let [result (grammars/parse-construction "42")]
      (println "Parse result for '42':" result)
      (is (not (insta/failure? result)))))
  
  (testing "Check what the grammar produces for object construction"
    (let [result (grammars/parse-construction "[:Rect 0 0 800 600]")]
      (println "Parse result for '[:Rect 0 0 800 600]':" result)
      (is (not (insta/failure? result)))))
  
  (testing "Check what the grammar produces for nested construction"
    (let [result (grammars/parse-construction "[:Game [:PlayArea [:Rect 0 0 800 600]] [:Ball 100 100 1 1 5]]")]
      (println "Parse result for nested construction:" result)
      (is (not (insta/failure? result))))))
