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
             (constantly true)             
             (p/trace "TRACING")             
             (p/processor #(+ % 1)))
            )]
      (is (= (p/cargo-value cargo "Hooo!") 9))
      )))

(deftest test-logging-basic
  (testing "basic logging functionality"
    (let [cargo
          (p/run 5
            (p/trace "START")
            (p/log "VALUE")
            (p/processor #(* % 2))
            (p/log "AFTER MULTIPLY")
            (p/log-all "FULL CARGO")
            (p/processor #(+ % 3)))]
      (is (not (p/failed? cargo)))
      (is (= (p/cargo-value cargo "test") 13))
      (is (some #(re-find #"TRACE: START" %) (:log cargo)))
      (is (some #(re-find #"LOG : VALUE" %) (:log cargo)))
      (is (some #(re-find #"LOG : AFTER MULTIPLY" %) (:log cargo)))
      (is (some #(re-find #"LOG ALL : FULL CARGO" %) (:log cargo)))
      (is (= (count (:log cargo)) 4)))))

(deftest test-logging-when-do-preservation
  (testing "logging is preserved when using when-do with sub-pipelines"
    (let [cargo
          (p/run 10
            (p/trace "MAIN-START")
            (p/log "MAIN-BEFORE-SUB")
            (p/when-do
             (constantly true)
             (p/trace "SUB-START")
             (p/log "SUB-VALUE")
             (p/processor #(* % 2))
             (p/log "SUB-AFTER-MULTIPLY")
             (p/trace "SUB-END"))
            (p/log "MAIN-AFTER-SUB")
            (p/trace "MAIN-END"))]
      (is (not (p/failed? cargo)))
      (is (= (p/cargo-value cargo "test") 20))
      
      ;; Check that all logs are present in order
      (let [log-entries (:log cargo)]
        (is (some #(re-find #"TRACE: MAIN-START" %) log-entries))
        (is (some #(re-find #"LOG : MAIN-BEFORE-SUB" %) log-entries))
        (is (some #(re-find #"TRACE: SUB-START" %) log-entries))
        (is (some #(re-find #"LOG : SUB-VALUE" %) log-entries))
        (is (some #(re-find #"LOG : SUB-AFTER-MULTIPLY" %) log-entries))
        (is (some #(re-find #"TRACE: SUB-END" %) log-entries))
        (is (some #(re-find #"LOG : MAIN-AFTER-SUB" %) log-entries))
        (is (some #(re-find #"TRACE: MAIN-END" %) log-entries))
        
        ;; Check total count (4 traces + 4 logs = 8 entries)
        (is (= (count log-entries) 8))))))

(deftest test-logging-when-do-skipped
  (testing "logging is preserved when when-do condition is false"
    (let [cargo
          (p/run 5
            (p/trace "MAIN-START")
            (p/log "MAIN-BEFORE-SUB")
            (p/when-do
             (constantly false)  ; condition is false, sub-pipeline should be skipped
             (p/trace "SUB-START")
             (p/log "SUB-VALUE")
             (p/processor #(* % 2)))
            (p/log "MAIN-AFTER-SUB")
            (p/trace "MAIN-END"))]
      (is (not (p/failed? cargo)))
      (is (= (p/cargo-value cargo "test") 5))  ; value unchanged since sub-pipeline was skipped
      
      ;; Check that only main pipeline logs are present
      (let [log-entries (:log cargo)]
        (is (some #(re-find #"TRACE: MAIN-START" %) log-entries))
        (is (some #(re-find #"LOG : MAIN-BEFORE-SUB" %) log-entries))
        (is (not-any? #(re-find #"SUB-START" %) log-entries))  ; sub-pipeline logs should not be present
        (is (not-any? #(re-find #"SUB-VALUE" %) log-entries))
        (is (some #(re-find #"LOG : MAIN-AFTER-SUB" %) log-entries))
        (is (some #(re-find #"TRACE: MAIN-END" %) log-entries))
        
        ;; Check total count (2 traces + 2 logs = 4 entries)
        (is (= (count log-entries) 4))))))

(deftest test-logging-processor-merge
  (testing "logging is merged when processor returns a cargo"
    (let [cargo
          (p/run 3
            (p/trace "START")
            (p/processor (fn [x] 
                           (p/success-cargo (* x 2))))  ; processor returns a cargo
            (p/log "AFTER-PROCESSOR")
            (p/trace "END"))]
      (is (not (p/failed? cargo)))
      (is (= (p/cargo-value cargo "test") 6))
      
      ;; Check that logs from both the returned cargo and the main pipeline are present
      (let [log-entries (:log cargo)]
        (is (some #(re-find #"TRACE: START" %) log-entries))
        (is (some #(re-find #"LOG : AFTER-PROCESSOR" %) log-entries))
        (is (some #(re-find #"TRACE: END" %) log-entries))
        
        ;; Check total count (2 traces + 1 log = 3 entries)
        (is (= (count log-entries) 3))))))

(deftest test-logging-cargo-processor-merge
  (testing "logging is merged when cargo-processor returns a cargo"
    (let [cargo
          (p/run 4
            (p/trace "START")
            (p/cargo-processor (fn [ctx] 
                                 (p/success-cargo (* (:value ctx) 3))))  ; cargo-processor returns a cargo
            (p/log "AFTER-CARGO-PROCESSOR")
            (p/trace "END"))]
      (is (not (p/failed? cargo)))
      (is (= (p/cargo-value cargo "test") 12))
      
      ;; Check that logs from both the returned cargo and the main pipeline are present
      (let [log-entries (:log cargo)]
        (is (some #(re-find #"TRACE: START" %) log-entries))
        (is (some #(re-find #"LOG : AFTER-CARGO-PROCESSOR" %) log-entries))
        (is (some #(re-find #"TRACE: END" %) log-entries))
        
        ;; Check total count (2 traces + 1 log = 3 entries)
        (is (= (count log-entries) 3))))))

(deftest test-logging-nested-when-do
  (testing "logging is preserved in nested when-do sub-pipelines"
    (let [cargo
          (p/run 2
            (p/trace "LEVEL1-START")
            (p/when-do
             (constantly true)
             (p/trace "LEVEL1-SUB-START")
             (p/when-do
              (constantly true)
              (p/trace "LEVEL2-SUB-START")
              (p/log "LEVEL2-VALUE")
              (p/processor #(+ % 5))
              (p/trace "LEVEL2-SUB-END"))
             (p/log "LEVEL1-SUB-AFTER-LEVEL2")
             (p/trace "LEVEL1-SUB-END"))
            (p/trace "LEVEL1-END"))]
      (is (not (p/failed? cargo)))
      (is (= (p/cargo-value cargo "test") 7))
      
      ;; Check that all logs from all levels are present
      (let [log-entries (:log cargo)]
        (is (some #(re-find #"TRACE: LEVEL1-START" %) log-entries))
        (is (some #(re-find #"TRACE: LEVEL1-SUB-START" %) log-entries))
        (is (some #(re-find #"TRACE: LEVEL2-SUB-START" %) log-entries))
        (is (some #(re-find #"LOG : LEVEL2-VALUE" %) log-entries))
        (is (some #(re-find #"TRACE: LEVEL2-SUB-END" %) log-entries))
        (is (some #(re-find #"LOG : LEVEL1-SUB-AFTER-LEVEL2" %) log-entries))
        (is (some #(re-find #"TRACE: LEVEL1-SUB-END" %) log-entries))
        (is (some #(re-find #"TRACE: LEVEL1-END" %) log-entries))
        
        ;; Check total count (6 traces + 2 logs = 8 entries)
        (is (= (count log-entries) 8))))))

(deftest test-logging-failure-preservation
  (testing "logging is preserved even when pipeline fails"
    (let [cargo
          (p/run 5
            (p/trace "START")
            (p/log "BEFORE-FAILURE")
            (p/validator #(> % 10) "Value too small")  ; This will fail
            (p/log "AFTER-FAILURE")  ; This should not execute
            (p/trace "END"))]  ; This should not execute
      (is (p/failed? cargo))
      (is (= (count (:errors cargo)) 1))
      
      ;; Check that logs before failure are preserved
      (let [log-entries (:log cargo)]
        (is (some #(re-find #"TRACE: START" %) log-entries))
        (is (some #(re-find #"LOG : BEFORE-FAILURE" %) log-entries))
        (is (not-any? #(re-find #"AFTER-FAILURE" %) log-entries))  ; Should not be present
        (is (some #(re-find #"TRACE: END" %) log-entries))  ; TRACE runs even after failure
        
        ;; Check total count (2 traces + 1 log = 3 entries)
        (is (= (count log-entries) 3))))))

(deftest test-cargo-processor-stash-preservation
  (testing "cargo-processor preserves stash from current context"
    (let [cargo
          (p/run 3
            (p/stash :original-stash)
            (p/log "BEFORE-CARGO-PROCESSOR")
            (p/cargo-processor (fn [ctx] 
                                 (p/success-cargo 
                                   {:new-value (* (:value ctx) 2)
                                    :stash {:sub-stash "sub-value"}})))  ; This stash should NOT overwrite original
            (p/log "AFTER-CARGO-PROCESSOR"))]
      (is (not (p/failed? cargo)))
      (is (= (p/cargo-value cargo "test") {:new-value 6 :stash {:sub-stash "sub-value"}}))
      
      ;; Check that original stash is preserved
      (is (= (-> cargo :stash :original-stash) 3))
      (is (not (contains? (:stash cargo) :sub-stash)))  ; Sub-stash should not overwrite original stash
      
      ;; Check that logs are preserved
      (let [log-entries (:log cargo)]
        (is (some #(re-find #"LOG : BEFORE-CARGO-PROCESSOR" %) log-entries))
        (is (some #(re-find #"LOG : AFTER-CARGO-PROCESSOR" %) log-entries))
        (is (= (count log-entries) 2))))))

(deftest test-cargo-processor-log-preservation
  (testing "cargo-processor preserves log from current context"
    (let [cargo
          (p/run 4
            (p/trace "MAIN-START")
            (p/log "MAIN-BEFORE-CARGO")
            (p/cargo-processor (fn [ctx] 
                                 (p/run (* (:value ctx) 3)
                                   (p/log "SUB-LOG-1")
                                   (p/log "SUB-LOG-2"))))  ; This should add to log, not replace
            (p/log "MAIN-AFTER-CARGO")
            (p/trace "MAIN-END"))]
      (is (not (p/failed? cargo)))
      (is (= (p/cargo-value cargo "test") 12))
      
      ;; Check that all logs are preserved in order
      (let [log-entries (:log cargo)]
        (is (some #(re-find #"TRACE: MAIN-START" %) log-entries))
        (is (some #(re-find #"LOG : MAIN-BEFORE-CARGO" %) log-entries))
        (is (some #(re-find #"LOG : SUB-LOG-1" %) log-entries))
        (is (some #(re-find #"LOG : SUB-LOG-2" %) log-entries))
        (is (some #(re-find #"LOG : MAIN-AFTER-CARGO" %) log-entries))
        (is (some #(re-find #"TRACE: MAIN-END" %) log-entries))
        
        ;; Check total count (2 traces + 4 logs = 6 entries)
        (is (= (count log-entries) 6))))))

(deftest test-cargo-processor-failure-stash-preservation
  (testing "cargo-processor preserves stash even when it returns a failed cargo"
    (let [cargo
          (p/run 5
            (p/stash :important-data)
            (p/log "BEFORE-FAILURE")
            (p/cargo-processor (fn [ctx] 
                                 (p/fail-cargo "Something went wrong")))  ; This should fail but preserve stash
            (p/log "AFTER-FAILURE"))]
      (is (p/failed? cargo))
      (is (= (count (:errors cargo)) 1))
      (is (= (first (:errors cargo)) "Something went wrong"))
      
      ;; Check that stash is preserved even after failure
      (is (= (-> cargo :stash :important-data) 5))
      
      ;; Check that logs before failure are preserved
      (let [log-entries (:log cargo)]
        (is (some #(re-find #"LOG : BEFORE-FAILURE" %) log-entries))
        (is (not-any? #(re-find #"AFTER-FAILURE" %) log-entries))  ; Should not be present
        
        ;; Check total count (1 log = 1 entry)
        (is (= (count log-entries) 1))))))

(deftest test-cargo-processor-failure-log-preservation
  (testing "cargo-processor preserves log even when it returns a failed cargo"
    (let [cargo
          (p/run 6
            (p/trace "MAIN-START")
            (p/log "MAIN-BEFORE-FAILURE")
            (p/cargo-processor (fn [ctx] 
                                 (p/run (:value ctx)
                                   (p/log "SUB-LOG-BEFORE-FAILURE")
                                   (p/validator #(> % 10) "Value too small"))))  ; This should fail
            (p/log "MAIN-AFTER-FAILURE"))]
      (is (p/failed? cargo))
      (is (= (count (:errors cargo)) 1))
      
      ;; Check that logs are preserved even after failure
      (let [log-entries (:log cargo)]
        (is (some #(re-find #"TRACE: MAIN-START" %) log-entries))
        (is (some #(re-find #"LOG : MAIN-BEFORE-FAILURE" %) log-entries))
        (is (some #(re-find #"LOG : SUB-LOG-BEFORE-FAILURE" %) log-entries))
        (is (not-any? #(re-find #"MAIN-AFTER-FAILURE" %) log-entries))  ; Should not be present
        
        ;; Check total count (1 trace + 2 logs = 3 entries)
        (is (= (count log-entries) 3))))))

(deftest test-cargo-processor-plain-value
  (testing "cargo-processor handles plain value returns correctly"
    (let [cargo
          (p/run 7
            (p/stash :original-data)
            (p/log "BEFORE-PLAIN-VALUE")
            (p/cargo-processor (fn [ctx] 
                                 (* (:value ctx) 2)))  ; Returns plain value, not cargo
            (p/log "AFTER-PLAIN-VALUE"))]
      (is (not (p/failed? cargo)))
      (is (= (p/cargo-value cargo "test") 14))
      
      ;; Check that stash is preserved
      (is (= (-> cargo :stash :original-data) 7))
      
      ;; Check that logs are preserved
      (let [log-entries (:log cargo)]
        (is (some #(re-find #"LOG : BEFORE-PLAIN-VALUE" %) log-entries))
        (is (some #(re-find #"LOG : AFTER-PLAIN-VALUE" %) log-entries))
        (is (= (count log-entries) 2))))))
