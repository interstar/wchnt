(ns live.runtime
  "Compile %canvas Target JS with real js/Function; wrap the heap for JS."
  (:require [wchnt-lang.canvas :as canvas]
            [wchnt-lang.compiler :as compiler]
            [wchnt-lang.interpret :as interpret]
            [wchnt-lang.js-view :as js-view]
            [wchnt-lang.pipeline :as p]
            [live.storage :as storage]))

(defn- target-js-body
  [program]
  (str (get-in program [:target-ir :init :haxe])
       "\n"
       (get-in program [:target-ir :step :haxe])
       "\nreturn {init: init, step: step};"))

(defn- game-factory
  [program]
  (fn []
    (js-view/as-js
     (js-view/wrap program
                   (interpret/construct (:schema-ir program)
                                        (:construction-ir program))))))

(defn- compile-target
  [program wchnt-graphics input]
  (let [body (target-js-body program)
        ctor (try
               (js/Function. "wchntGraphics" "graphics" "gameFactory" "input" body)
               (catch :default e
                 (throw (ex-info (str "Target JS compile error: "
                                      (or (.-message e) (str e)))
                                 {:body body}))))]
    (.call ctor nil wchnt-graphics wchnt-graphics (game-factory program) input)))

(defn- compile-check
  [wchnt-markdown]
  (let [cargo (compiler/compile-to-ir wchnt-markdown
                                     {:resolve-page storage/resolve-page})]
    (if (p/failed? cargo)
      {:kind :error :message (first (:errors cargo))}
      {:kind (:page-kind (:value cargo)) :cargo cargo})))

(defn prepare
  "Parse source. Returns {:kind ...} with :init/:step for programs."
  [wchnt-markdown wchnt-graphics input]
  (let [{:keys [kind message]} (compile-check wchnt-markdown)]
    (case kind
      :documentation {:kind :documentation
                      :message "Documentation page — nothing to run."}
      :library {:kind :library
                :message "Library page — schema and methods check passed."}
      :error (throw (ex-info message {}))
      :program (let [program (canvas/assert-canvas-host
                              (interpret/load-program
                               wchnt-markdown
                               {:resolve-page storage/resolve-page}))
                      api (compile-target program wchnt-graphics input)]
                 {:kind :program
                  :init (.-init api)
                  :step (.-step api)}))))
