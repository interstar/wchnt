(ns wchnt-lang.ast-to-ir-test
  (:require [clojure.test :refer :all]
            [wchnt-lang.ast-to-ir :as ast-to-ir]
            [wchnt-lang.parser :as parser]
            [wchnt-lang.ir :as ir]))

(deftest test-schema-ast-debug
  (testing "Debug schema AST structure"
    (let [schema-text "Game = PlayArea Ball\nPlayArea = Rect\nRect = Int/x Int/y Int/width Int/height"
          schema-cargo (parser/schema-wchnt->schema-ast schema-text)
          schema-ast (:value schema-cargo)]
      (println "Schema AST:" (pr-str schema-ast))
      (is (vector? schema-ast))
      (is (= :Schema (first schema-ast))))))

(deftest test-schema-ast-debug-with-sigils
  (testing "Debug schema AST structure with sigils"
    (let [schema-text "Game = PlayArea Ball $Time\nTime = Int/current"
          schema-cargo (parser/schema-wchnt->schema-ast schema-text)
          schema-ast (:value schema-cargo)]
      (println "Schema AST with sigils:" (pr-str schema-ast))
      (is (vector? schema-ast))
      (is (= :Schema (first schema-ast))))))

(deftest test-schema-ast-to-ir
  (testing "Transform simple schema AST to IR"
    (let [schema-text "Game = PlayArea Ball\nPlayArea = Rect\nRect = Int/x Int/y Int/width Int/height"
          schema-cargo (parser/schema-wchnt->schema-ast schema-text)
          schema-ast (:value schema-cargo)
          schema-ir (ast-to-ir/schema-ast-to-ir schema-ast)]
      
      ;; Debug output
      (println "Schema IR:" (pr-str schema-ir))
      
      ;; Test that IR is valid
      (is (ir/validate-schema-ir schema-ir))
      
      ;; Test assemblages
      (let [assemblages (::ir/assemblages schema-ir)]
        (is (= 3 (count assemblages)))
        (is (some #(= (::ir/name %) "Game") assemblages))
        (is (some #(= (::ir/name %) "PlayArea") assemblages))
        (is (some #(= (::ir/name %) "Rect") assemblages)))
      
      ;; Test components
      (let [game (first (filter #(= (::ir/name %) "Game") (::ir/assemblages schema-ir)))
            components (::ir/components game)]
        (is (= 2 (count components)))
        (is (some #(= (::ir/component-name %) "playArea") components))
        (is (some #(= (::ir/component-name %) "ball") components))))))

(deftest test-reactive-schema-ast-to-ir
  (testing "Transform schema with reactive dependencies to IR"
    (let [schema-text "Game = PlayArea Ball $Time\nTime = Int/current"
          schema-cargo (parser/schema-wchnt->schema-ast schema-text)
          schema-ast (:value schema-cargo)
          schema-ir (ast-to-ir/schema-ast-to-ir schema-ast)]
      
      ;; Test observable classes
      (is (= ["Time"] (::ir/observable-classes schema-ir)))
      
      ;; Test subscriber classes
      (is (= ["Game"] (::ir/subscriber-classes schema-ir)))
      
      ;; Test that Time is observable
      (is (ir/is-observable? schema-ir "Time"))
      
      ;; Test that Game subscribes
      (is (ir/is-subscriber? schema-ir "Game")))))

(deftest test-context-schema-ast-to-ir
  (testing "Transform schema with context-specific components to IR"
    (let [schema-text "Car = :Engine\nEngine = Int/power"
          schema-cargo (parser/schema-wchnt->schema-ast schema-text)
          schema-ast (:value schema-cargo)
          schema-ir (ast-to-ir/schema-ast-to-ir schema-ast)]
      
      ;; Test context relationships
      (is (= {"Engine" "Car"} (::ir/context-relationships schema-ir)))
      
      ;; Test that Engine needs context
      (is (ir/needs-context? schema-ir "Engine"))
      
      ;; Test that Car provides context
      (is (= "Car" (ir/get-context-parent schema-ir "Engine")))))) 