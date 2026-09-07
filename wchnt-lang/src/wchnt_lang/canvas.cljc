(ns wchnt-lang.canvas
  "Run a %canvas program: construct the heap, eval Target JS, record draws."
  (:require [wchnt-lang.interpret :as interpret]
            [wchnt-lang.js-view :as js-view]
            [wchnt-lang.target-js :as target-js]))

(defn make-graphics
  "Host graphics object. Methods append to an atom log."
  []
  (let [log (atom [])]
    {:wchnt/host :graphics
     :log log
     :methods {"clear" (fn [] (swap! log conj [:clear]))
               "beginFill" (fn [color] (swap! log conj [:begin-fill color]))
               "endFill" (fn [] (swap! log conj [:end-fill]))
               "drawRect" (fn [x y w h]
                            (swap! log conj [:draw-rect x y w h]))
               "drawCircle" (fn [x y r]
                              (swap! log conj [:draw-circle x y r]))
               "lineStyle" (fn [width color]
                             (swap! log conj [:line-style width color]))
               "fillText" (fn [text x y]
                            (swap! log conj [:fill-text text x y]))}}))

(defn graphics-log
  [graphics]
  @(:log graphics))

(defn- make-env
  [program graphics]
  (atom {"wchntGraphics" graphics
         "gameFactory" (fn []
                         (js-view/wrap program
                                       (interpret/construct
                                        (:schema-ir program)
                                        (:construction-ir program))))}))

(defn assert-canvas-host
  [program]
  (let [host (get-in program [:target-ir :host])]
    (when-not (= "canvas" host)
      (throw (ex-info "Expected a %canvas Target"
                      {:host host})))
    program))

(defn boot
  "Load markdown, bind graphics + gameFactory, eval Target scripts.
   Does not call init/step."
  [wchnt-markdown graphics]
  (let [program (assert-canvas-host (interpret/load-program wchnt-markdown))
        env (make-env program graphics)]
    (target-js/eval-script (get-in program [:target-ir :init :haxe]) env)
    (target-js/eval-script (get-in program [:target-ir :step :haxe]) env)
    env))

(defn run-markdown
  "Eval %init once and %step n times. Returns {:root :draws}."
  [wchnt-markdown n]
  (let [graphics (make-graphics)
        env (boot wchnt-markdown graphics)]
    (target-js/call-js-fn env "init")
    (dotimes [_ n]
      (target-js/call-js-fn env "step"))
    {:root (js-view/unwrap (get @env "assemblage"))
     :draws (graphics-log graphics)}))

#?(:clj
   (defn run-file
     [path n]
     (run-markdown (slurp path) n)))
