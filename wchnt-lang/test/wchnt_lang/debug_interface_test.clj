(ns wchnt-lang.debug-interface-test
  (:require [clojure.test :refer :all]
            [wchnt-lang.haxegen :as haxegen]))

(deftest test-build-interface-implementers
  (testing "Debug interface implementers function"
    (let [schema-ast [:Schema 
                      [:DefLine [:CompositionLine [:Definee "Config"] [:Element [:TypeMarker "String"] "/" [:AltName "settings"]]]]
                      [:DefLine [:DisjunctionLine [:Definee "Shape"] [:Element [:TypeMarker "Triangle"]] [:Element [:TypeMarker "Circle"]]]]]
          result (haxegen/build-interface-implementers schema-ast)]
      (println "DEBUG: Input schema-ast:" (pr-str schema-ast))
      (println "DEBUG: Result:" (pr-str result))
      (println "DEBUG: Result type:" (type result))
      (is (map? result) "Should return a map")
      (is (contains? result "Shape") "Should contain Shape interface")
      (is (= #{"Triangle" "Circle"} (get result "Shape")) "Should have Triangle and Circle as implementers")))) 