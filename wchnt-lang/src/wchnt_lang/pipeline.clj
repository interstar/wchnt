(ns wchnt-lang.pipeline
  "A lightweight library for building pipelines of processors, validators, and taps.

  Each stage takes and returns a map (called a Cargo) of the form:

  {:success  true-or-false
   :value    any
   :errors   [error messages]
   :warnings [warning messages]
   :stash     {name intermediate-value}}

The pipeline short-circuits on failure: if :success is false, later stages are skipped."
  (:require [malli.core :as m]
            [clojure.pprint :as pp])
  )

(def Cargo
  [:map
   [:success :boolean]
   [:value any?]
   [:errors [:sequential string?]]
   [:warnings [:sequential string?]]
   [:stash [:map]]
   [:log [:sequential string?]]
   ]
  )


(defn success-cargo [v]
  {:success true
   :value v
   :errors []
   :warnings []
   :stash {}
   :log []})

(defn fail-cargo [e]
  {:success false
   :value nil
   :errors [e]
   :warnings []
   :stash {}
   :log []})

(defn is-cargo? [c] (m/validate Cargo c))
(defn failed? [c] (not (:success c)))
(defn cargo-value [c msg]
  (if-not (is-cargo? c)
    (throw (Exception. (str "Not Cargo " msg "\n" (with-out-str (pp/pprint c))) ))
    (if (:success c)
      (:value c)
      (throw (Exception. (str "Cargo failure " msg " for " c )))
      )))

(defn init
  "Initialize the pipeline context with an initial value.
  Returns the standard map structure used by pipeline stages:

  {:success  true
   :value    initial-value
   :errors   []
   :warnings []
   :stash     {}
   :log       []}"
  [initial-value]
  {:success  true
   :value    initial-value
   :errors   []
   :warnings []
   :stash     {}
   :log       []})


(defn throw-pass [ctx type label f]
  "Throw if not Cargo. Pass through if error. 
   Otherwise run f on the ctx"
  (if-not (is-cargo? ctx)
    (throw (Exception.
               (str type " Error. Not Cargo " label "\n"
                    (with-out-str (pp/pprint ctx))))))
  (if-not (:success ctx) ctx
          (f ctx label)
          ))


(defn- merge-cargo-into-context [ctx result]
  (-> ctx
      (assoc :success (:success result))
      (assoc :value (:value result))
      (assoc :errors (:errors result))
      (assoc :warnings (:warnings result))
      (update :log concat (:log result))
      (update :stash merge (:stash result))))

(defn- make-processor [arg-extractor]
  (fn
    ([f] ((make-processor arg-extractor) f ""))
    ([f label]
     (fn [ctx]
       (throw-pass
        ctx "Processor" label
        (fn [ctx label]
          (try
            (let [result (f (arg-extractor ctx))]
              (if (is-cargo? result)
                (merge-cargo-into-context ctx result)
                (assoc ctx :value result :success true)))
           (catch Exception e
             (-> ctx
                 (assoc :success false :value nil)
                 (update :errors conj (.getMessage e)))))))))))

(def processor (make-processor :value))
(def cargo-processor (make-processor identity))

(defn validator
  "Wraps a predicate function pred as a validator stage.

  - Applies pred to (:value ctx).
  - If pred returns true, leaves ctx unchanged.
  - If pred returns false, sets :success false, :value nil,
    and adds the supplied error message to :errors.

  If :success is already false, the validator is skipped."
  [pred err-msg]
  
  (fn [ctx]
    (throw-pass
     ctx "Validator" err-msg
     (fn [ctx label]
       (if (pred (:value ctx))
         ctx
         (-> ctx
             (assoc :success false :value nil)
             (update :errors conj err-msg)))
       ))))

(defn stash
  "Captures the current value under a given name in :stash.

  - Adds {:stash {name value}} to ctx.
  - Leaves :success and other fields unchanged.
  - If :success is already false, the stash is skipped."
  [name]  
  (fn [ctx]
    (throw-pass
     ctx "Stash" ""
     (fn [ctx label]
       (assoc-in ctx [:stash name] (:value ctx))))))

(defn retrieve
  "Pulls back one of the stashped previous values to make 
  the current value"
  [name]
  (fn [ctx]
    (throw-pass
     ctx "Retrieve" ""
     (fn [ctx label]
       (assoc ctx :value (-> ctx :stash (get name)))))))

(defn trace [label]
  (fn [ctx]
    (println "TRACE: " label)
    (update ctx :log conj (str "TRACE: " label))))

(defn log
  "Logs the value with label and adds to log"
  [label]
  (fn [ctx]
    (throw-pass
     ctx "Log" ""
     (fn [ctx _]
       (update ctx :log conj (str "LOG : " label "\n" (pr-str (:value ctx))))))))

(defn log-all
  "Logs the whole cargo with label and adds to log
   Even if :success is false"
  [label]
  (fn [ctx]
    (update ctx :log conj (str "LOG ALL : " label "\n" (with-out-str (pp/pprint ctx))))))

(declare continue)

(defn when-do
  "IF a condition, then run the subpipe and merge back"
  [p? & stages]
  (fn [ctx]
    (throw-pass
     ctx "When-Do" ""
     (fn [ctx label]
       (if-not
           (p? (:value ctx))
           (do
     
             ctx)
           (let [args (cons ctx stages)]

             (apply continue args))
           )))))

(defn run
  "Run a sequence of pipeline stages on an initial value.
  
  Returns the final context map with :success, :value, :errors, :warnings, and :stash."
  [initial-value & stages]
  (reduce (fn [ctx stage] (stage ctx)) (init initial-value) stages)) 

(defn continue
  "Continue running a sequence of pipeline stages based 
  on existing cargo"
  [cargo & stages]
  (reduce (fn [ctx stage] (stage ctx)) cargo stages))

