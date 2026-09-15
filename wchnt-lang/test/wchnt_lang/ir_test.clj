(ns wchnt-lang.ir-test
  (:require [clojure.test :refer :all]
            [wchnt-lang.ir :as ir]
            [wchnt-lang.schema :as schema]))

(deftest test-ir-creation
  (testing "Create basic IR structures"
    (let [component {:component-name "playArea"
                     :type-name "PlayArea"
                     :relationship :ordinary
                     :optional-name nil}
          assemblage {:name "Game"
                      :components [component]
                      :context-dependencies []
                      :context-providers []
                      :observable nil}
          schema-ir (ir/create-schema-ir [assemblage] [] [] {} {} [] [] [] #{})]
      (is (schema/valid-schema-ir? schema-ir)))))

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

(deftest construction-order-waits-for-named-assignments
  (testing "a map extracted before the objects it names is built after them"
    (let [objects {"obj1" {:type :map
                           :class-name "Map<String, Location>"
                           :index 0
                           :args [{:type :primitive :value "Village Square"}
                                  {:type :variable :value "village"}]}
                   "obj2" {:type :object
                           :class-name "Location"
                           :index 1
                           :args [{:type :primitive :value "Village Square"}]}
                   "obj3" {:type :object
                           :class-name "Game"
                           :index 2
                           :args [{:type :variable :value "obj1"}]}}
          ordered (mapv first (ir/objects-in-construction-order
                               objects
                               {"village" "obj2"}))]
      (is (= ["obj2" "obj1" "obj3"] ordered)))))

(deftest test-reactive-components
  (testing "reactive-components returns $ fields for a subscriber class"
    (let [schema-ir {:assemblages [{:name "Game"
                                    :components [{:component-name "time"
                                                  :type-name "Time"
                                                  :relationship :reactive
                                                  :optional-name nil}]}]}]
      (is (= ["time"] (mapv :component-name (ir/reactive-components schema-ir "Game"))))
      (is (= [] (ir/reactive-components schema-ir "Time")))))) 