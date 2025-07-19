(ns wchnt-lang.pipeline-test
  (:require
   [clojure.test :refer :all]   
   [wchnt-lang.pipeline :as p]
   [wchnt-lang.parser :as parser]
   [wchnt-lang.haxegen :as haxegen]))


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

(deftest test-when-do-condition-failure
  (testing "when-do condition failure should be handled gracefully"
    (let [cargo
          (p/run "test-string"
            (p/stash :before-when-do)
            (p/log "BEFORE-WHEN-DO")
            (p/when-do
             #(not= % "")  ; This should pass for "test-string"
             (p/stash :inside-when-do)
             (p/log "INSIDE-WHEN-DO"))
            (p/log "AFTER-WHEN-DO"))]
      (is (not (p/failed? cargo)))
      (is (= (p/cargo-value cargo "test") "test-string"))
      
      ;; Check that stash from both before and inside when-do are preserved
      (is (= (-> cargo :stash :before-when-do) "test-string"))
      (is (= (-> cargo :stash :inside-when-do) "test-string"))
      
      ;; Check that all logs are present
      (let [log-entries (:log cargo)]
        (is (some #(re-find #"LOG : BEFORE-WHEN-DO" %) log-entries))
        (is (some #(re-find #"LOG : INSIDE-WHEN-DO" %) log-entries))
        (is (some #(re-find #"LOG : AFTER-WHEN-DO" %) log-entries))
        (is (= (count log-entries) 3))))))

(deftest test-when-do-condition-failure-with-empty-string
  (testing "when-do condition failure with empty string should skip sub-pipeline"
    (let [cargo
          (p/run ""
            (p/stash :before-when-do)
            (p/log "BEFORE-WHEN-DO")
            (p/when-do
             #(not= % "")  ; This should fail for empty string
             (p/stash :inside-when-do)
             (p/log "INSIDE-WHEN-DO"))
            (p/log "AFTER-WHEN-DO"))]
      (is (not (p/failed? cargo)))
      (is (= (p/cargo-value cargo "test") ""))
      
      ;; Check that stash from before when-do is preserved, but inside is not
      (is (= (-> cargo :stash :before-when-do) ""))
      (is (nil? (-> cargo :stash :inside-when-do)))
      
      ;; Check that only logs before and after when-do are present
      (let [log-entries (:log cargo)]
        (is (some #(re-find #"LOG : BEFORE-WHEN-DO" %) log-entries))
        (is (not-any? #(re-find #"INSIDE-WHEN-DO" %) log-entries))  ; Should not be present
        (is (some #(re-find #"LOG : AFTER-WHEN-DO" %) log-entries))
        (is (= (count log-entries) 2))))))

(deftest test-cargo-validation-basic
  (testing "basic cargo validation should work correctly"
    (let [valid-cargo (p/success-cargo "test-value")
          invalid-cargo {:success true :value "test"}  ; Missing required fields
          nil-cargo nil
          string-value "not-a-cargo"]
      (is (p/is-cargo? valid-cargo))
      (is (not (p/is-cargo? invalid-cargo)))
      (is (not (p/is-cargo? nil-cargo)))
      (is (not (p/is-cargo? string-value))))))

(deftest test-cargo-validation-with-log
  (testing "cargo validation should work with cargo that has log entries"
    (let [cargo-with-log (-> (p/success-cargo "test")
                             (update :log conj "test log entry"))]
      (is (p/is-cargo? cargo-with-log))
      (is (= (count (:log cargo-with-log)) 1)))))

(deftest test-cargo-validation-with-stash
  (testing "cargo validation should work with cargo that has stash entries"
    (let [cargo-with-stash (-> (p/success-cargo "test")
                               (assoc-in [:stash :test-key] "test-value"))]
      (is (p/is-cargo? cargo-with-stash))
      (is (= (get-in cargo-with-stash [:stash :test-key]) "test-value")))))

(deftest test-processor-with-cargo-return
  (testing "processor should handle cargo returns correctly"
    (let [cargo
          (p/run 5
            (p/processor (fn [x] 
                           (p/success-cargo (* x 2)))))]
      (is (not (p/failed? cargo)))
      (is (= (p/cargo-value cargo "test") 10)))))

(deftest test-processor-with-failed-cargo-return
  (testing "processor should handle failed cargo returns correctly"
    (let [cargo
          (p/run 5
            (p/processor (fn [x] 
                           (p/fail-cargo "Test failure"))))]
      (is (p/failed? cargo))
      (is (= (count (:errors cargo)) 1))
      (is (= (first (:errors cargo)) "Test failure")))))

(deftest test-cargo-validation-edge-cases
  (testing "cargo validation should handle edge cases correctly"
    (let [cargo-with-empty-arrays (p/success-cargo "test")
          cargo-with-empty-arrays (assoc cargo-with-empty-arrays :errors [] :warnings [] :log [])
          cargo-with-empty-maps (p/success-cargo "test")
          cargo-with-empty-maps (assoc cargo-with-empty-maps :stash {})]
      ;; These should still be valid cargos even with empty arrays/maps
      (is (p/is-cargo? cargo-with-empty-arrays))
      (is (p/is-cargo? cargo-with-empty-maps)))))

(deftest test-parser-cargo-validation
  (testing "cargo objects returned by parser functions should pass validation"
    (let [;; Test the cargo returned by parse-construction-unified
          test-construction-text "shapes = [:Array/Shape [:Triangle 10 20] [:Circle 15]] .\nplayers = [:Array/Player [:Player \"Alice\" 100] [:Player \"Bob\" 85]] .\n[:Game shapes players]"
          parser-cargo (parser/parse-construction-unified test-construction-text)]
      ;; The parser should return a valid cargo
      (is (p/is-cargo? parser-cargo))
      ;; If it's a success cargo, the value should be the parsed AST
      (when (not (p/failed? parser-cargo))
        (is (vector? (p/cargo-value parser-cargo "test")))))))

(deftest test-factory-cargo-validation
  (testing "cargo objects returned by factory generation functions should pass validation"
    (let [;; Test the cargo returned by generate-construction-factory-unified
          test-ast [:BlockStatements [:Assignment "test" [:IntLiteral "5"]]]
          test-class-info {:classes [] :enums [] :disjunctions []}
          test-context-relationships {}
          factory-cargo (haxegen/generate-construction-factory-unified test-ast test-class-info test-context-relationships)]
      ;; The factory generation should return a valid cargo
      (is (p/is-cargo? factory-cargo))
      ;; If it's a success cargo, the value should be a string (Haxe code)
      (when (not (p/failed? factory-cargo))
        (is (string? (p/cargo-value factory-cargo "test")))))))
