(ns live.test-runner
  (:require [cljs.test :refer-macros [run-all-tests]]
            [wchnt-lang.semantics-test]))

(defn ^:export run-tests
  "Run shared semantics tests; returns true when all pass."
  [output-el]
  (set! (.-textContent output-el) "")
  (let [append! (fn [s]
                  (set! (.-textContent output-el)
                        (str (.-textContent output-el) s "\n")))]
    (binding [*print-fn* append!]
      (let [results (run-all-tests #"wchnt-lang\.semantics-test")]
        (append! (str "\nRan " (:test results) " tests, "
                      (+ (:error results) (:fail results)) " failures, "
                      (:error results) " errors."))
        (zero? (+ (:error results) (:fail results)))))))

(defn ^:export -main []
  (js/console.log "Load live/public/tests.html in a browser to run tests."))
