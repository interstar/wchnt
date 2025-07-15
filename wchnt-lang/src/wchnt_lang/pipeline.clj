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
   ]
  )


(defn success-cargo [v]
  {:success true
   :value v
   :errors []
   :warnings []
   :stash {}})

(defn fail-cargo [e]
  {:success false
   :value nil
   :errors [e]
   :warnings []
   :stash {}})

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
   :stash     {}}"
  [initial-value]
  {:success  true
   :value    initial-value
   :errors   []
   :warnings []
   :stash     {}})


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


(defn processor
  "Wraps a function f as a processor stage.

  - Applies f to (:value ctx) if :success is true.
  - If f returns a Cargo, merges it with current context (preserving stash).
  - If f returns a plain value, wraps it in a new Cargo.
  - If f throws or fails, sets :success false, :value nil, and adds an error message.

  If :success is already false, the processor is skipped."
  ([f] (processor f ""))
  ([f label]
   (fn [ctx]
     (throw-pass
      ctx "Processor" label
      (fn [ctx label]
        (try
          (let [result (f (:value ctx))]
            (if (is-cargo? result)
              ;; Function returned a Cargo - merge with current context
              (-> ctx
                  (assoc :success (:success result))
                  (assoc :value (:value result))
                  (assoc :errors (:errors result))
                  (assoc :warnings (:warnings result))
                 ;; Preserve current stash, don't overwrite with result stash
                  )
              ;; Function returned a plain value - wrap in new Cargo
              (assoc ctx :value result :success true)))
         (catch Exception e
           (-> ctx
               (assoc :success false :value nil)
               (update :errors conj (.getMessage e))))))))))

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
    ctx))

(defn show
  "Prints the value coming through"
  [label]
  (fn [ctx]
    (throw-pass
     ctx "Show" ""
     (fn [ctx label]
       (println "SHOW : " label)
       (pp/pprint (:value ctx))
       ctx))))

(defn show-all
  "Prints the whole cargo coming through
   Even if :success is false"
  [label]
  (fn [ctx]
    (println "SHOW : " label)
    (pp/pprint ctx)
    ctx))

(declare continue)

(defn when-do
  "IF a condition, then run the subpipe and merge back"
  [p? & stages]
  (fn [ctx]
    (throw-pass
     ctx "When-Do" ""
     (fn [ctx label]
       (println "WHEN DO BEFORE CONDITION TEST")
       (println (p? (:value ctx)))
       (println ctx)
       (if-not
           (p? (:value ctx))
           (do
             (println "CONDITION FAILED")
             ctx)
           (let [args (cons ctx stages)]
             (println "In when-do .. condition passed")
             (pp/pprint args)
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

(defn cargo-processor
  "Like processor, but passes the full cargo (pipeline context) to the function, not just the :value."
  ([f] (cargo-processor f ""))
  ([f label]
   (fn [ctx]
     (throw-pass
      ctx "CargoProcessor" label
      (fn [ctx label]
        (try
          (let [result (f ctx)]
            (if (is-cargo? result)
              ;; Function returned a Cargo - merge with current context
              (-> ctx
                  (assoc :success (:success result))
                  (assoc :value (:value result))
                  (assoc :errors (:errors result))
                  (assoc :warnings (:warnings result)))
              ;; Function returned a plain value - wrap in new Cargo
              (assoc ctx :value result :success true)))
         (catch Exception e
           (-> ctx
               (assoc :success false :value nil)
               (update :errors conj (.getMessage e))))))))))

