(ns wchnt-lang.pipeline-test
  (:require
   [clojure.test :refer :all]   
   [wchnt-lang.pipeline :as p]))


(deftest test-pipeline
  (testing "basic pipeline"
    (let
      [cargo
       (p/run 3
         (p/processor #(* 4 %))
         (p/stash :x)
         (p/processor #(/ % 2))
         (p/stash :y)
         (p/validator #(even? %) "Even test failed" )
         (p/retrieve :x)
         (p/stash :z)
         (p/retrieve :y)
         )
       cargo2
       (p/continue
        cargo
        (p/processor #(* 4 %))
        )]
      (is (p/is-cargo? cargo))
      (is (not (p/failed? cargo)))
      (is (= (-> cargo :stash :x) 12))
      (is (= (-> cargo :stash :y) 6))
      (is (= (-> cargo :stash :z) 12))
      (is (= (-> cargo (p/cargo-value "banG!")) 6))
      (is (= (-> cargo2 (p/cargo-value "BANG!")) 24))
      )))

(deftest test-subpipes
  (testing "sub pipelines"
    (let [cargo
          (p/run 4
            (p/processor #(* % 2))
            (p/when-do
             #(true)             
             (p/trace "TRACING")             
             (p/processor #(+ % 1)))
            )]
      (is (= (p/cargo-value cargo "Hooo!") 9))
      )))
