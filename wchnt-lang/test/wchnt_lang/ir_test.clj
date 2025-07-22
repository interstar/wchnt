(ns wchnt-lang.ir-test
  (:require [clojure.test :refer :all]
            [wchnt-lang.ir :as ir]))

(deftest test-ir-creation
  (testing "Create basic IR structures"
    (let [component {::ir/component-name "playArea"
                     ::ir/type-name "PlayArea"
                     ::ir/relationship :ordinary
                     ::ir/optional-name nil}
          assemblage {::ir/name "Game"
                      ::ir/components [component]
                      ::ir/context-dependencies []
                      ::ir/context-providers []}
          schema-ir (ir/create-schema-ir [assemblage] [] [] {} {} [] [] [])]
      (is (ir/validate-schema-ir schema-ir)))))

(deftest test-ir-utility-functions
  (testing "IR utility functions work correctly"
    (let [schema-ir {::ir/observable-classes ["Time"]
                     ::ir/subscriber-classes ["Game"]
                     ::ir/context-relationships {"Engine" "Car"}}]
      (is (= ["Time"] (ir/get-observable-classes schema-ir)))
      (is (= ["Game"] (ir/get-subscriber-classes schema-ir)))
      (is (= {"Engine" "Car"} (ir/get-context-relationships schema-ir)))
      (is (ir/is-observable? schema-ir "Time"))
      (is (ir/is-subscriber? schema-ir "Game"))
      (is (ir/needs-context? schema-ir "Engine"))
      (is (= "Car" (ir/get-context-parent schema-ir "Engine")))))) 