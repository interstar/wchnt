(ns wchnt-lang.targets.live-canvas
  "Run a %canvas program: construct the heap, eval Target JS, record draws."
  (:require [wchnt-lang.interpret :as interpret]
            [wchnt-lang.js-view :as js-view]
            [wchnt-lang.targets.interpreter-std :as host]
            [wchnt-lang.targets.live-js :as target-js]))

(defn make-graphics
  "Host graphics object. Methods append to an atom log and return the graphics
   object so calls chain fluently (same API as WCHNTGraphics / WCHNTHarness)."
  []
  (let [log (atom [])
        self (volatile! nil)
        clamp (fn [value] (max 0 (min 255 (int value))))
        record (fn [entry] (swap! log conj entry) @self)]
    (let [gfx {:wchnt/host "WCHNTGraphics"
               :log log
               :methods {"color" (fn [& [r g b a]]
                                    (let [gray? (nil? g)
                                          g (if gray? r g)
                                          b (if gray? r b)
                                          a (if (nil? a) 255 a)]
                                      (bit-or (bit-shift-left (clamp a) 24)
                                              (bit-shift-left (clamp r) 16)
                                              (bit-shift-left (clamp g) 8)
                                              (clamp b))))
                         "red" (fn [color] (bit-and (unsigned-bit-shift-right color 16) 0xff))
                         "green" (fn [color] (bit-and (unsigned-bit-shift-right color 8) 0xff))
                         "blue" (fn [color] (bit-and color 0xff))
                         "alpha" (fn [color] (bit-and (unsigned-bit-shift-right color 24) 0xff))
                         "background" (fn [& [color alpha]]
                                        (record (if (nil? alpha)
                                                  [:background color]
                                                  [:background color alpha])))
                         "clear" (fn [] (record [:clear]))
                         "beginFill" (fn [& [color alpha]]
                                       (record (if (nil? alpha)
                                                 [:begin-fill color]
                                                 [:begin-fill color alpha])))
                         "endFill" (fn [] (record [:end-fill]))
                         "lineStyle" (fn [& [width color alpha]]
                                       (if (nil? width)
                                         (record [:no-stroke])
                                         (record (if (nil? alpha)
                                                   [:line-style width color]
                                                   [:line-style width color alpha]))))
                         "noStroke" (fn [] (record [:no-stroke]))
                         "drawRect" (fn [x y w h] (record [:draw-rect x y w h]))
                         "drawCircle" (fn [x y r] (record [:draw-circle x y r]))
                         "drawEllipse" (fn [x y rx ry] (record [:draw-ellipse x y rx ry]))
                         "drawLine" (fn [x1 y1 x2 y2] (record [:draw-line x1 y1 x2 y2]))
                         "fillText" (fn [text x y] (record [:fill-text text x y]))
                         "moveTo" (fn [x y] (record [:move-to x y]))
                         "lineTo" (fn [x y] (record [:line-to x y]))}}]
      (vreset! self gfx)
      gfx)))

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
