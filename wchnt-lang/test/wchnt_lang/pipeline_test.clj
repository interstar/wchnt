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
