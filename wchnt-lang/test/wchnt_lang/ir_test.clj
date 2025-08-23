(ns wchnt-lang.ir-test
  (:require [clojure.test :refer :all]
            [wchnt-lang.ir :as ir]))

(deftest test-ir-creation
  (testing "Create basic IR structures"
    (let [component {:component-name "playArea"
                     :type-name "PlayArea"
                     :relationship :ordinary
                     :optional-name nil}
          assemblage {:name "Game"
                      :components [component]
                      :context-dependencies []
                      :context-providers []}
          schema-ir (ir/create-schema-ir [assemblage] [] [] {} {} [] [] [])]
      (is (ir/validate-schema-ir schema-ir)))))

(deftest test-ir-utility-functions
  (testing "IR utility functions work correctly"
    (let [schema-ir {:observable-classes ["Time"]
                     :subscriber-classes ["Game"]
                     :context-relationships {"Engine" "Car"}}]
      (is (= ["Time"] (ir/get-observable-classes schema-ir)))
      (is (= ["Game"] (ir/get-subscriber-classes schema-ir)))
      (is (= {"Engine" "Car"} (ir/get-context-relationships schema-ir)))
      (is (ir/is-observable? schema-ir "Time"))
      (is (ir/is-subscriber? schema-ir "Game"))
      (is (ir/needs-context? schema-ir "Engine"))
      (is (= "Car" (ir/get-context-parent schema-ir "Engine")))))) 