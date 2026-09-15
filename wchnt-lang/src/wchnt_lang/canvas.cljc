(ns wchnt-lang.canvas
  "Run a %canvas program: construct the heap, eval Target JS, record draws."
  (:require [wchnt-lang.interpret :as interpret]
            [wchnt-lang.js-view :as js-view]
            [wchnt-lang.host :as host]
            [wchnt-lang.target-js :as target-js]))

(defn make-graphics
  "Host graphics object. Methods append to an atom log.
   API and semantics match WCHNTGraphics (OpenFL) and WCHNTHarness (live canvas)."
  []
  (let [log (atom [])]
    {:wchnt/host :graphics
     :log log
     :methods {"background" (fn [& [color alpha]]
                              (swap! log conj (if (nil? alpha)
                                                [:background color]
                                                [:background color alpha])))
               "clear" (fn [] (swap! log conj [:clear]))
               "beginFill" (fn [& [color alpha]]
                             (swap! log conj (if (nil? alpha)
                                               [:begin-fill color]
                                               [:begin-fill color alpha])))
               "endFill" (fn [] (swap! log conj [:end-fill]))
               "lineStyle" (fn [& [width color alpha]]
                             (if (nil? width)
                               (swap! log conj [:no-stroke])
                               (swap! log conj (if (nil? alpha)
                                                 [:line-style width color]
                                                 [:line-style width color alpha]))))
               "noStroke" (fn [] (swap! log conj [:no-stroke]))
               "drawRect" (fn [x y w h]
                            (swap! log conj [:draw-rect x y w h]))
               "drawCircle" (fn [x y r]
                              (swap! log conj [:draw-circle x y r]))
               "drawEllipse" (fn [x y rx ry]
                               (swap! log conj [:draw-ellipse x y rx ry]))
               "drawLine" (fn [x1 y1 x2 y2]
                            (swap! log conj [:draw-line x1 y1 x2 y2]))
               "fillText" (fn [text x y]
                            (swap! log conj [:fill-text text x y]))
               "moveTo" (fn [x y] (swap! log conj [:move-to x y]))
               "lineTo" (fn [x y] (swap! log conj [:line-to x y]))}}))

(defn graphics-log
  [graphics]
  @(:log graphics))

(defn- make-env
  [program graphics]
  (let [factory (fn [& args]
                  (js-view/wrap program
                                (interpret/construct
                                 (:schema-ir program)
                                 (:construction-ir program)
                                 (or (:methods-ir program) [])
                                 (mapv js-view/from-js args))))
        assemblage-name (str (:root-class (:construction-ir program))
                             "Assemblage")]
    (atom {"wchntGraphics" graphics
           "graphics" graphics
           "wchntMaths" (host/make-maths)
           assemblage-name {"factory" factory}})))

(defn assert-canvas-host
  [program]
  (let [host (get-in program [:target-ir :host])]
    (when-not (= "canvas" host)
      (throw (ex-info "Expected a %canvas Target"
                      {:host host})))
    program))

(defn boot
  "Load markdown, bind graphics + the generated Assemblage wrapper, eval Target scripts.
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
