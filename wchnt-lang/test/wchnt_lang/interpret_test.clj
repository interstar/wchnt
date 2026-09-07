(ns wchnt-lang.interpret-test
  "JVM interpreter: construction heap + bounce Methods. No browser."
  (:require [clojure.test :refer :all]
            [wchnt-lang.compiler :as compiler]
            [wchnt-lang.interpret :as interpret]))

(defn- bounce-program
  []
  (interpret/load-program (slurp "live-examples/bounce_canvas.wcn")))

(deftest construct-bounce-heap
  (testing "bounce construction is nested maps with schema field names"
    (let [{:keys [root]} (bounce-program)]
      (is (= "Game" (:wchnt/class root)))
      (is (= "PlayArea" (get-in root [:playArea :wchnt/class])))
      (is (= "Rect" (get-in root [:playArea :rect :wchnt/class])))
      (is (= {:wchnt/class "Rect" :x 0 :y 0 :width 800 :height 600}
             (get-in root [:playArea :rect])))
      (is (= {:wchnt/class "Ball" :x 200 :y 150 :dx 6 :dy 5 :rad 16}
             (:ball root))))))

(deftest bounce-dx-away-from-walls
  (testing "Game::bounceDx is +dx while the ball is inside the rect"
    (let [{:keys [schema-ir methods-ir root]} (bounce-program)]
      (is (= 6 (interpret/call schema-ir methods-ir root "bounceDx" [])))
      (is (= 5 (interpret/call schema-ir methods-ir root "bounceDy" []))))))

(deftest bounce-step-moves-the-ball
  (testing "Game::step builds a new Game with the ball translated by dx/dy"
    (let [{:keys [schema-ir methods-ir root]} (bounce-program)
          next (interpret/call schema-ir methods-ir root "step" [])]
      (is (= "Game" (:wchnt/class next)))
      (is (= (get-in root [:playArea :rect]) (get-in next [:playArea :rect])))
      (is (= {:wchnt/class "Ball" :x 206 :y 155 :dx 6 :dy 5 :rad 16}
             (:ball next))))))

(deftest bounce-step-reverses-at-the-right-edge
  (testing "repeated step flips dx after x passes the play-area width"
    (let [{:keys [schema-ir methods-ir root]} (bounce-program)
          after (nth (iterate #(interpret/call schema-ir methods-ir % "step" [])
                              root)
                     102)]
      (is (>= (get-in after [:ball :x]) 800))
      (is (neg? (get-in after [:ball :dx]))))))

(defn- square-program
  []
  (interpret/load-program (slurp "live-examples/square_canvas.wcn")))


(deftest inject-mailbox-moves-the-square
  (testing "inject on >Keys notifies Game and nudges the square horizontally"
    (let [{:keys [schema-ir methods-ir root]} (square-program)
          keys (interpret/get-field root "keys")]
      (interpret/inject schema-ir methods-ir keys [6 0])
      (is (= 386 (get (interpret/get-field root "square") :x)))
      (is (= 280 (get (interpret/get-field root "square") :y)))))
  (testing "inject on >Keys nudges the square vertically"
    (let [{:keys [schema-ir methods-ir root]} (square-program)
          keys (interpret/get-field root "keys")]
      (interpret/inject schema-ir methods-ir keys [0 6])
      (is (= 286 (get (interpret/get-field root "square") :y)))
      (interpret/inject schema-ir methods-ir keys [0 -6])
      (is (= 280 (get (interpret/get-field root "square") :y)))))
  (testing "inject on a non-mailbox class fails"
    (let [{:keys [schema-ir methods-ir root]} (square-program)]
      (is (thrown-with-msg? Exception #"not marked"
                            (interpret/inject schema-ir methods-ir
                                              (interpret/get-field root "square")
                                              [1 2 3]))))))
