(ns wchnt-lang.targets.live-canvas-test
  "JS view + %canvas Target eval for bounce. Graphics is a recording stub."
  (:require [clojure.test :refer :all]
            [wchnt-lang.targets.live-canvas :as canvas]
            [wchnt-lang.interpret :as interpret]
            [wchnt-lang.js-view :as js-view]))

(defn- bounce-ctx
  []
  (interpret/load-program (slurp "live-examples/bounce_canvas.wcn")))

(deftest js-view-reads-fields
  (testing "wrapped Game exposes playArea.rect and ball.x as JS properties"
    (let [ctx (bounce-ctx)
          game (js-view/wrap ctx (:root ctx))]
      (is (= 200 (js-view/js-get (js-view/js-get game "ball") "x")))
      (is (= 800 (js-view/js-get (js-view/js-get (js-view/js-get game "playArea")
                                                "rect")
                                "width"))))))

(deftest js-view-calls-step
  (testing "assemblage.step() returns a wrapped Game with a moved ball"
    (let [ctx (bounce-ctx)
          game (js-view/wrap ctx (:root ctx))
          next (js-view/js-call game "step" [])]
      (is (= 206 (js-view/js-get (js-view/js-get next "ball") "x")))
      (is (= 155 (js-view/js-get (js-view/js-get next "ball") "y")))
      (is (= 16 (js-view/js-get (js-view/js-get next "ball") "rad"))))))

(deftest js-get-method-is-invokable
  (testing "assemblage.step is a function, like JS property access then call"
    (let [ctx (bounce-ctx)
          game (js-view/wrap ctx (:root ctx))
          step-fn (js-view/js-get game "step")
          next (step-fn)]
      (is (= 206 (js-view/js-get (js-view/js-get next "ball") "x"))))))

(deftest describe-prints-objects-arrays-and-maps
  (testing "println form for a class, an array of objects, and a dict"
    (let [{:keys [schema-ir methods-ir root]}
          (interpret/load-program
           (str "# t\n## Schema\n```\n"
                "Team = [Player]/players {String : Int}/scores\n"
                "Player = String/name Int/score\n"
                "```\n## Construction\n```\n"
                "[:Team [:Array/Player [\"Ada\" 3] [\"Cy\" 5]] {String:Int \"Ada\":3}]\n"
                "```\n## Methods\n```\n"
                "Team::scorers = { players.filter({ p | p.score > 0 }) }\n"
                "```\n"))
          ctx {:schema-ir schema-ir :methods-ir methods-ir}
          team (js-view/wrap ctx root)
          scorers (js-view/js-call team "scorers" [])]
      (is (= "[:Team [[:Player \"Ada\" 3] [:Player \"Cy\" 5]] {\"Ada\":3}]"
             (js-view/describe team)))
      (is (= "[[:Player \"Ada\" 3] [:Player \"Cy\" 5]]"
             (js-view/describe scorers))))))

(deftest canvas-run-bounce-target-js
  (testing "bounce_canvas %init/%step JS draws after one assemblage.step"
    (let [{:keys [root draws]} (canvas/run-file "live-examples/bounce_canvas.wcn" 1)]
      (let [snapshot (interpret/materialize root)]
        (is (= 206 (get-in snapshot [:ball :x])))
        (is (= 155 (get-in snapshot [:ball :y]))))
      (is (= [[:clear]
              [:begin-fill 0x2a422a]
              [:draw-rect 0 0 800 600]
              [:end-fill]
              [:begin-fill 0xf2f2f2]
              [:draw-circle 206 155 16]
              [:end-fill]]
             draws)))))

(deftest graphics-records-new-primitives
  (testing "background, drawLine, drawEllipse, and noStroke are recorded"
    (let [g (canvas/make-graphics)
          methods (:methods g)
          call! (fn [m & args] (apply (get methods m) args))]
      (call! "background" 0x1a1a2e)
      (call! "clear")
      (call! "beginFill" 0x2a2a2a)
      (call! "drawRect" 20 20 160 100)
      (call! "endFill")
      (call! "lineStyle" 2 0x00ff00)
      (call! "drawRect" 200 20 160 100)
      (call! "noStroke")
      (call! "drawEllipse" 420 200 60 30)
      (call! "drawLine" 20 300 760 300)
      (is (= [[:background 0x1a1a2e]
              [:clear]
              [:begin-fill 0x2a2a2a]
              [:draw-rect 20 20 160 100]
              [:end-fill]
              [:line-style 2 0x00ff00]
              [:draw-rect 200 20 160 100]
              [:no-stroke]
              [:draw-ellipse 420 200 60 30]
              [:draw-line 20 300 760 300]]
             (canvas/graphics-log g))))))

(deftest graphics-colour-helpers
  (testing "colour helpers use packed ARGB and round-trip components"
    (let [methods (:methods (canvas/make-graphics))
          colour (fn [& args] (apply (get methods "color") args))
          red (get methods "red")
          green (get methods "green")
          blue (get methods "blue")
          alpha (get methods "alpha")
          orange (colour 255 128 0 64)]
      (is (= 1090486272 orange))
      (is (= 4286611584 (colour 128 128 128)))
      (is (= 255 (red orange)))
      (is (= 128 (green orange)))
      (is (= 0 (blue orange)))
      (is (= 64 (alpha orange)))
      (is (= 17 (red (colour 17))))
      (is (= 17 (green (colour 17))))
      (is (= 17 (blue (colour 17))))
      (is (= 255 (alpha (colour 17)))))))

(deftest canvas-run-graphics-parity
  (testing "graphics_canvas %step draws filled, stroked, ellipse, line, and polygon"
    (let [{:keys [draws]} (canvas/run-file "live-examples/graphics_canvas.wcn" 1)]
      (is (= [[:background 0x1a1a2e]
              [:clear]
              [:begin-fill 0x2a2a2a]
              [:draw-rect 20 20 160 100]
              [:end-fill]
              [:line-style 2 0x00ff00]
              [:draw-rect 200 20 160 100]
              [:no-stroke]
              [:begin-fill 0xf2f2f2]
              [:draw-circle 100 200 40]
              [:end-fill]
              [:line-style 3 0xff6464]
              [:draw-circle 260 200 40]
              [:no-stroke]
              [:begin-fill 0x6464ff]
              [:line-style 2 0xffffff]
              [:draw-ellipse 420 200 60 30]
              [:end-fill]
              [:no-stroke]
              [:line-style 4 0xffffff]
              [:draw-line 20 300 760 300]
              [:no-stroke]
              [:begin-fill 0xc8c864]
              [:move-to 100 400]
              [:line-to 200 340]
              [:line-to 300 400]
              [:line-to 100 400]
              [:end-fill]]
             draws)))))
