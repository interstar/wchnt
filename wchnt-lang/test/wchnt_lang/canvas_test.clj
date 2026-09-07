(ns wchnt-lang.canvas-test
  "JS view + %canvas Target eval for bounce. Graphics is a recording stub."
  (:require [clojure.test :refer :all]
            [wchnt-lang.canvas :as canvas]
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

(deftest canvas-run-bounce-target-js
  (testing "bounce_canvas %init/%step JS draws after one assemblage.step"
    (let [{:keys [root draws]} (canvas/run-file "live-examples/bounce_canvas.wcn" 1)]
      (is (= 206 (get-in root [:ball :x])))
      (is (= 155 (get-in root [:ball :y])))
      (is (= [[:clear]
              [:begin-fill 0x2a2a2a]
              [:draw-rect 0 0 800 600]
              [:end-fill]
              [:begin-fill 0xf2f2f2]
              [:draw-circle 206 155 16]
              [:end-fill]]
             draws)))))
