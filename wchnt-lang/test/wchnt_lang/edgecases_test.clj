(ns wchnt-lang.edgecases-test
  "Schema edge cases for : context exclusivity and $ reactive wiring.
   Positive runnable picture: examples/edgecases.wcn."
  (:require [clojure.test :refer :all]
            [wchnt-lang.ast-to-ir :as ast-to-ir]
            [wchnt-lang.compiler :as compiler]
            [wchnt-lang.interpret :as interpret]
            [wchnt-lang.ir :as ir]
            [wchnt-lang.parser :as parser]
            [wchnt-lang.pipeline :as p]
            [wchnt-lang.schema :as schema]))

(defn- schema-ir
  [schema-text]
  (let [cargo (parser/schema-wchnt->schema-ast schema-text)]
    (is (:success cargo) (str "parse: " (first (:errors cargo))))
    (ast-to-ir/schema-ast-to-ir (:value cargo))))

(defn- schema-fails
  [schema-text re]
  (let [cargo (parser/schema-wchnt->schema-ast schema-text)]
    (is (:success cargo))
    (is (thrown-with-msg? Exception re
                          (ast-to-ir/schema-ast-to-ir (:value cargo))))))

(defn- subscribed?
  [observable subscriber]
  (some #(identical? % subscriber) @(:wchnt/subscribers observable)))

;; ---------------------------------------------------------------------------
;; (1) (6) context exclusivity — must fail
;; ---------------------------------------------------------------------------

(deftest context-two-parents-both-colon-fails
  (testing "Car = :Engine and Truck = :Engine"
    (schema-fails "Car = :Engine\nTruck = :Engine\nEngine = Int/cc\n"
                  #"context-specific.*more than one parent")))

(deftest context-colon-then-ordinary-on-other-parent-fails
  (testing "Car = :Engine and Truck = Engine"
    (schema-fails "Car = :Engine\nTruck = Engine\nEngine = Int/cc\n"
                  #"context-specific to Car.*cannot also be a component of Truck")))

(deftest context-ordinary-then-colon-on-other-parent-fails
  (testing "Car = Engine and Truck = :Engine"
    (schema-fails "Car = Engine\nTruck = :Engine\nEngine = Int/cc\n"
                  #"context-specific to Truck.*cannot also be a component of Car")))

(deftest context-same-parent-two-engines-ok
  (testing "Fleet = :Engine/e1 :Engine/e2 is allowed"
    (let [ir (schema-ir "Fleet = :Engine/e1 :Engine/e2\nEngine = Int/cc\n")]
      (is (schema/valid-schema-ir? ir))
      (is (= "Fleet" (ir/get-context-parent ir "Engine"))))))

;; ---------------------------------------------------------------------------
;; (5) $ on an interface — must fail
;; ---------------------------------------------------------------------------

(deftest reactive-on-interface-fails
  (testing "A = $I with I = B | C"
    (schema-fails "A = $I\nI = B | C\nB = Int/x\nC = Int/y\n"
                  #"Reactive component \$I")))

;; ---------------------------------------------------------------------------
;; (2) (3) (7) schema IR for reactive shapes
;; ---------------------------------------------------------------------------

(deftest multi-observable-on-one-subscriber
  (testing "A = $B $C records both observables and one subscriber"
    (let [ir (schema-ir "A = $B $C\nB = Int/x\nC = Int/y\n")]
      (is (= #{"B" "C"} (set (ir/get-observable-classes ir))))
      (is (= #{"A"} (set (ir/get-subscriber-classes ir)))))))

(deftest independent-reactive-edges
  (testing "A = $B and C = $D are separate observable/subscriber pairs"
    (let [ir (schema-ir "A = $B\nC = $D\nB = Int/x\nD = Int/y\n")]
      (is (= #{"B" "D"} (set (ir/get-observable-classes ir))))
      (is (= #{"A" "C"} (set (ir/get-subscriber-classes ir)))))))

(deftest ordinary-holder-of-observable-is-not-subscriber
  (testing "A = B and C = $B: B is observable; only C subscribes"
    (let [ir (schema-ir "A = B\nC = $B\nB = Int/x\n")]
      (is (= #{"B"} (set (ir/get-observable-classes ir))))
      (is (= #{"C"} (set (ir/get-subscriber-classes ir))))
      (is (not (ir/is-subscriber? ir "A")))
      (is (ir/is-observable? ir "B")))))

;; ---------------------------------------------------------------------------
;; examples/edgecases.wcn + interpreter wiring
;; ---------------------------------------------------------------------------

(deftest edgecases-example-compiles
  (testing "examples/edgecases.wcn compiles"
    (let [cargo (compiler/compile (slurp "examples/edgecases.wcn"))]
      (is (p/is-cargo? cargo))
      (is (:success cargo) (first (:errors cargo)))
      (let [factory (get-in cargo [:value :payload :classes] "")]
        (is (re-find #"\.clockA\.subscribe\(" factory))
        (is (re-find #"\.pulse\.subscribe\(" factory))
        (is (re-find #"\.shared\.subscribe\(" factory))
        (is (re-find #"\.clock\.subscribe\(" factory))))))

(deftest edgecases-interpreter-subscriptions
  (testing "shared Shared fans out; Holder does not subscribe to Clock"
    (let [prog (interpret/load-program (slurp "examples/edgecases.wcn"))
          root (:root prog)
          fan (interpret/get-field root "fanOut")
          s1 (interpret/get-field fan "s1")
          s2 (interpret/get-field fan "s2")
          shared (interpret/get-field s1 "shared")
          hw (interpret/get-field root "holderWatch")
          holder (interpret/get-field hw "holder")
          watcher (interpret/get-field hw "watcher")
          clock (interpret/get-field watcher "clock")]
      (is (identical? shared (interpret/get-field s2 "shared")))
      (is (subscribed? shared s1))
      (is (subscribed? shared s2))
      (is (identical? clock (interpret/get-field holder "clock")))
      (is (subscribed? clock watcher))
      (is (not (subscribed? clock holder))))))

(deftest edgecases-interpreter-multi-and-independent
  (testing "MultiObs listens to both; Alpha tick does not change Beta"
    (let [{:keys [schema-ir methods-ir root]} (interpret/load-program (slurp "examples/edgecases.wcn"))
          multi (interpret/get-field root "multiObs")
          clock-a (interpret/get-field multi "clockA")
          pulse (interpret/get-field multi "pulse")
          indep (interpret/get-field root "independent")
          left (interpret/get-field indep "left")
          right (interpret/get-field indep "right")
          alpha (interpret/get-field left "alpha")
          beta (interpret/get-field right "beta")]
      (is (subscribed? clock-a multi))
      (is (subscribed? pulse multi))
      (is (subscribed? alpha left))
      (is (subscribed? beta right))
      (is (not (subscribed? alpha right)))
      (is (not (subscribed? beta left)))
      (interpret/call schema-ir methods-ir alpha "update!" [])
      (is (= 1 (interpret/get-field alpha "a")))
      (is (= 0 (interpret/get-field beta "b"))))))
