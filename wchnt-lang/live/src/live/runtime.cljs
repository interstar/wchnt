(ns live.runtime
  "Compile live Target JS with real js/Function; wrap the heap for JS."
  (:require [clojure.string :as str]
            [wchnt-lang.compiler :as compiler]
            [wchnt-lang.interpret :as interpret]
            [wchnt-lang.js-view :as js-view]
            [wchnt-lang.pipeline :as p]
            [wchnt-lang.targets.testharness-live-run :as testharness]
            [live.storage :as storage]))

(def live-hosts
  #{"canvas" "form" "cli-live" "testharness-live"})

(defn- target-js-body
  [program]
  (let [bindings (get-in program [:target-ir :bindings])
        trace-name (get-in bindings ["trace" :fn-name])]
    (str (str/join "\n" (map :haxe (vals bindings)))
       "\n"
       (get-in program [:target-ir :init :haxe])
       "\n"
       (get-in program [:target-ir :step :haxe])
       "\nreturn {init: init, step: step, trace: "
       (or trace-name "null")
       "};")))

(defn- game-factory
  [program-ref]
  (fn [& args]
    (let [program @program-ref]
      (js-view/as-js
       (js-view/wrap program
                     (interpret/construct (:schema-ir program)
                                          (:construction-ir program)
                                          (or (:methods-ir program) [])
                                          (mapv js-view/from-js args)))))))

(defn- compile-js
  [arg-names body]
  (try
    (apply js/Function. (conj (vec arg-names) body))
    (catch :default e
      (throw (ex-info (str "Target JS compile error: "
                           (or (.-message e) (str e)))
                      {:body body})))))

(def ^:private reserved-js-names
  #{"wchntGraphics" "graphics" "input" "wchntConsole"
    "wchntMaths" "wchntForm" "__wchntClasses" "__wchntAssemblages"
    "init" "step" "assemblage"})

(defn- class-ctors
  [program]
  (let [o (js-obj)]
    (doseq [a (:assemblages (:schema-ir program))
            :let [n (:name a)]
            :when (and (seq (:components a))
                       (not (contains? reserved-js-names n)))]
      (aset o n (fn [& args]
                  (js-view/as-js
                   (js-view/wrap program
                                 (interpret/construct-object
                                  (:schema-ir program)
                                  n
                                  (mapv js-view/from-js args)))))))
    o))

(defn- ctor-preamble
  [program]
  (apply str
         (for [a (:assemblages (:schema-ir program))
               :let [n (:name a)]
               :when (and (seq (:components a))
                          (not (contains? reserved-js-names n)))]
           (str "var " n " = __wchntClasses." n ";\n"))))

(defn- assemblage-preamble
  [program]
  (let [root (:root-class (:construction-ir program))]
    (if root
      (str "var " root "Assemblage = __wchntAssemblages." root "Assemblage;\n")
      "")))

(defn- target-with-ctors
  [program]
  (str (ctor-preamble program)
       (assemblage-preamble program)
       (target-js-body program)))

(defn- assemblage-object
  [program-ref]
  (let [program @program-ref]
    (let [root (:root-class (:construction-ir program))]
      (doto (js-obj)
        (aset (str root "Assemblage")
              (doto (js-obj)
                (aset "factory" (game-factory program-ref))))))))

(defn- bind-live-target
  [program-ref program host-api]
  (let [standard-bindings (get-in program [:target-ir :plugin :standard :bindings])
        arg-names (mapv :name standard-bindings)
        values (mapv #(get host-api (:host-key %)) standard-bindings)
        internal-names ["__wchntClasses" "__wchntAssemblages"]
        ctor (compile-js (into arg-names internal-names)
                         (target-with-ctors program))
        args (into values [(class-ctors program)
                           (assemblage-object program-ref)])]
    (.apply ctor nil (to-array args))))

(defn- bind-target
  [program-ref program host-api]
  (bind-live-target program-ref program host-api))

(defn- install-target-functions
  [program api]
  (let [trace (.-trace api)]
    (if (fn? trace)
      (assoc-in program [:schema-ir :target-fns "trace"] trace)
      program)))

(defn- assert-live-host
  [program]
  (let [host (get-in program [:target-ir :host])]
    (when-not (contains? live-hosts host)
      (throw (ex-info
              (str "Live Run expects %canvas, %form, %cli-live, or %testharness-live"
                   (when host (str ", not %" host)))
              {:host host})))
    program))

(defn- compile-check
  [wchnt-markdown]
  (let [cargo (compiler/compile-to-ir wchnt-markdown
                                     {:resolve-page storage/resolve-page})]
    (if (p/failed? cargo)
      {:kind :error :message (first (:errors cargo))}
      {:kind (:page-kind (:value cargo)) :cargo cargo})))

(defn- program-result
  [wchnt-markdown host-api]
  (let [program (assert-live-host
                 (interpret/load-program
                  wchnt-markdown
                  {:resolve-page storage/resolve-page}))
        program-ref (atom program)
        api (bind-target program-ref program host-api)]
    (reset! program-ref (install-target-functions program api))
    {:kind :program
     :host (get-in program [:target-ir :host])
     :init (.-init api)
     :step (.-step api)}))

(defn- testharness-host?
  [cargo]
  (= "testharness-live" (get-in cargo [:stash :target-ir :host])))

(defn- testharness-result
  [cargo]
  (let [program {:schema-ir (get-in cargo [:stash :schema-ir])
                 :methods-ir (or (get-in cargo [:stash :methods-ir]) [])
                 :target-ir (get-in cargo [:stash :target-ir])
                 :page-kind (get-in cargo [:value :page-kind])}
        report (testharness/run-suite program)]
    {:kind :testharness
     :host "testharness-live"
     :ok? (:ok? report)
     :failed (:failed report)
     :message (str/join "\n" (:lines report))}))

(defn- library-result
  [cargo]
  (if (testharness-host? cargo)
    (testharness-result cargo)
    {:kind :library
     :message "Library page — schema and methods check passed."}))

(defn prepare
  "Parse source. host-api is {:graphics :form :input :console :maths}.
   Returns {:kind ...} with :init/:step/:host for programs, or a
   :testharness report for %testharness-live libraries."
  [wchnt-markdown host-api]
  (let [{:keys [kind message cargo]} (compile-check wchnt-markdown)]
    (case kind
      :documentation {:kind :documentation
                      :message "Documentation page — nothing to run."}
      :library (library-result cargo)
      :error (throw (ex-info message {}))
      :program (program-result wchnt-markdown host-api))))
