(ns wchnt-lang.targets.testharness-live-run
  "Run a %testharness-live suite against the interpreter."
  (:require [wchnt-lang.parser :as parser]
            [wchnt-lang.pipeline :as p]
            [wchnt-lang.ast-to-ir :as ast-to-ir]
            [wchnt-lang.interpret :as interpret]
            [wchnt-lang.targets.testharness-live-expr :as expr]))

(defn- parse-construction-ir
  [construction-text schema-ir]
  (let [parsed (parser/parse-construction-unified construction-text)]
    (when (p/failed? parsed)
      (throw (ex-info (or (first (:errors parsed))
                          "%with construction parse failed")
                      {:text construction-text})))
    (ast-to-ir/construction-ast-to-ir (:value parsed) schema-ir)))

(defn- construct-fixture
  [{:keys [construction-text]} schema-ir methods-ir]
  (let [construction-ir (parse-construction-ir construction-text schema-ir)]
    (interpret/construct schema-ir construction-ir methods-ir [])))

(defn- empty-report
  []
  {:passed 0 :failed 0 :lines []})

(defn- record-assert
  [report label ok?]
  (let [line (str (if ok? "PASS" "FAIL") ": " label)]
    (-> report
        (update (if ok? :passed :failed) inc)
        (update :lines conj line))))

(defn- run-assert-case
  [report {:keys [label expr]} ctx env]
  (record-assert report label (expr/eval-assert-expr expr ctx env)))

(defn- run-step
  [{:keys [report env]} step ctx]
  (case (:op step)
    :with
    {:report report
     :env (assoc env (:binding step)
                 (construct-fixture step (:schema-ir ctx) (:methods-ir ctx)))}
    :assert
    {:report (reduce (fn [r case]
                       (run-assert-case r case ctx env))
                     report
                     (:cases step))
     :env env}))

(defn- summarize
  [{:keys [passed failed lines]}]
  (let [summary (str "Tests: " (+ passed failed)
                     "  passed: " passed
                     "  failed: " failed)]
    {:passed passed
     :failed failed
     :lines (conj (vec lines) summary)
     :summary summary
     :ok? (zero? failed)}))

(defn run-suite
  "Execute target-ir :suite. program needs :schema-ir :methods-ir :target-ir."
  [program]
  (let [host (get-in program [:target-ir :host])
        suite (get-in program [:target-ir :suite])
        ctx {:schema-ir (:schema-ir program)
             :methods-ir (or (:methods-ir program) [])}]
    (when-not (= "testharness-live" host)
      (throw (ex-info "Expected %testharness-live"
                      {:host host})))
    (when (empty? suite)
      (throw (ex-info "%testharness-live suite is empty" {})))
    (->> suite
         (reduce (fn [state step] (run-step state step ctx))
                 {:report (empty-report) :env {}})
         :report
         summarize)))

(defn run-markdown
  "Compile markdown and run its %testharness-live suite."
  ([wchnt-markdown]
   (run-markdown wchnt-markdown {}))
  ([wchnt-markdown opts]
   (let [program (interpret/load-program wchnt-markdown opts)]
     (run-suite program))))
