(ns wchnt-lang.semantics-test
  "Shared interpreter semantics: JVM (lein test) and browser (live/public/tests.html)."
  #?(:clj (:require [clojure.test :refer [deftest is testing]]
                  [wchnt-lang.interpret :as interpret]
                  [wchnt-lang.canvas :as canvas])
     :cljs (:require [cljs.test :refer-macros [deftest is testing async]]
                     [wchnt-lang.interpret :as interpret]
                     [wchnt-lang.js-view :as js-view])))

(defn- example-path [name]
  ;; JVM tests slurp the repo-root live-examples/; the browser test runner
  ;; fetches the copy served from live/public/test-examples/.
  #?(:clj  (str "live-examples/" name ".wcn")
     :cljs (str "test-examples/" name ".wcn")))

#?(:clj
   (defn- load-example [name]
     (interpret/load-program (slurp (example-path name)))))

#?(:cljs
   (defn- load-example-async [name on-success on-error]
     (-> (js/fetch (example-path name))
         (.then #(.text %))
         (.then (fn [text] (on-success (interpret/load-program text))))
         (.catch on-error))))

#?(:clj
   (deftest bounce-step-moves-the-ball
     (testing "Game::step translates the ball"
       (let [{:keys [schema-ir methods-ir root]} (load-example "bounce_canvas")
             next (interpret/call schema-ir methods-ir root "step" [])]
         (is (= 206 (get-in next [:ball :x])))
         (is (= 155 (get-in next [:ball :y])))))))

#?(:cljs
   (deftest bounce-step-moves-the-ball
     (async done
       (load-example-async
        "bounce_canvas"
        (fn [{:keys [schema-ir methods-ir root]}]
          (let [next (interpret/call schema-ir methods-ir root "step" [])]
            (is (= 206 (get-in next [:ball :x])))
            (is (= 155 (get-in next [:ball :y]))))
          (done))
        (fn [e] (is (nil? e) (str e)) (done))))))

#?(:clj
   (deftest pollution-spawns-and-resets
     (testing "makePollutant drops a trail ball behind the player"
       (let [{:keys [schema-ir methods-ir root]} (load-example "pollution_canvas")
             p (interpret/get-field root "player")
             pol (interpret/call schema-ir methods-ir root "makePollutant" [p])]
         (is (= "Pollutant" (:wchnt/class pol)))
         (is (= 14 (:rad pol)))
         (is (= (- (:dx p)) (:dx pol)))))
     (testing "first spawn tick adds one pollutant"
       (let [{:keys [schema-ir methods-ir root]} (load-example "pollution_canvas")
             time (interpret/get-field root "time")]
         (reset! (:wchnt/cell time) {:t 399})
         (interpret/call schema-ir methods-ir time "update" [])
         (is (= 1 (count (interpret/get-field root "pollutants"))))))
     (testing "collision resets score and clears pollutants"
       (let [{:keys [schema-ir methods-ir root]} (load-example "pollution_canvas")
             time (interpret/get-field root "time")]
         (reset! (:wchnt/cell time) {:t 399})
         (interpret/call schema-ir methods-ir time "update" [])
         (let [pollutant (first (interpret/get-field root "pollutants"))
               player (interpret/get-field root "player")]
           (swap! (:wchnt/cell root)
                  assoc :player (assoc player :x (:x pollutant) :y (:y pollutant)
                                       :dx 0 :dy 0)
                  :score 99)
           (interpret/call schema-ir methods-ir time "update" []))
         (is (empty? (interpret/get-field root "pollutants")))
         (is (= 0 (interpret/get-field root "score")))))))

#?(:cljs
   (deftest js-view-exposes-pollutant-array
     (async done
       (load-example-async
        "pollution_canvas"
        (fn [{:keys [schema-ir methods-ir root]}]
          (let [time (interpret/get-field root "time")
                ctx {:schema-ir schema-ir :methods-ir methods-ir}
                game (js-view/as-js (js-view/wrap ctx root))]
            (reset! (:wchnt/cell time) {:t 399})
            (interpret/call schema-ir methods-ir time "update" [])
            (is (= 1 (count (interpret/get-field root "pollutants"))))
            (is (= 1 (.-length (.-pollutants game))))
            (is (instance? js/Array (.-pollutants game)))
            (is (number? (.-x (aget (.-pollutants game) 0))))
            (let [xs (atom [])]
              (doseq [o (.-pollutants game)]
                (swap! xs conj (.-x o)))
              (is (= 1 (count @xs)))))
          (done))
        (fn [e] (is (nil? e) (str e)) (done))))))

#?(:cljs
   (deftest pollution-spawns-and-resets
     (async done
       (load-example-async
        "pollution_canvas"
        (fn [{:keys [schema-ir methods-ir root]}]
          (testing "makePollutant drops a trail ball behind the player"
            (let [p (interpret/get-field root "player")
                  pol (interpret/call schema-ir methods-ir root "makePollutant" [p])]
              (is (= "Pollutant" (:wchnt/class pol)))
              (is (= 14 (:rad pol)))
              (is (= (- (:dx p)) (:dx pol)))))
          (testing "first spawn tick adds one pollutant"
            (let [time (interpret/get-field root "time")]
              (reset! (:wchnt/cell time) {:t 399})
              (interpret/call schema-ir methods-ir time "update" [])
              (is (= 1 (count (interpret/get-field root "pollutants"))))))
          (testing "collision resets score and clears pollutants"
            (let [time (interpret/get-field root "time")]
              (reset! (:wchnt/cell time) {:t 399})
              (interpret/call schema-ir methods-ir time "update" [])
              (let [pollutant (first (interpret/get-field root "pollutants"))
                    player (interpret/get-field root "player")]
                (swap! (:wchnt/cell root)
                       assoc :player (assoc player :x (:x pollutant) :y (:y pollutant)
                                            :dx 0 :dy 0)
                       :score 99)
                (interpret/call schema-ir methods-ir time "update" []))
              (is (empty? (interpret/get-field root "pollutants")))
              (is (= 0 (interpret/get-field root "score")))))
          (done))
        (fn [e] (is (nil? e) (str e)) (done))))))

#?(:clj
   (deftest factory-wires-context
     (testing ":context children get theParent on construction"
       (let [{:keys [schema-ir methods-ir root]}
             (interpret/load-program (slurp "examples/test_reaction_context_path.wcn"))
             engine (interpret/get-field root "engine")]
         (is (= "Toyota" (interpret/call schema-ir methods-ir engine "carModel" [])))))))

#?(:clj
   (deftest pong-ball-reads-play-area-via-context
     (testing ":Ball uses theGame.playArea without bounds parameters"
       (let [{:keys [schema-ir methods-ir root]} (load-example "pong_canvas")
             time (interpret/get-field root "time")]
         (is (= "Game" (:wchnt/class (:theGame (interpret/get-field root "ball")))))
         (interpret/call schema-ir methods-ir time "update" [])
         (is (number? (:y (interpret/get-field root "ball"))))))))

#?(:clj
   (deftest shapes-draw-chains-graphics-calls
     (testing "Target Methods @Graphics/g chains (beginFill.drawCircle.endFill) on canvas"
       (let [{:keys [schema-ir methods-ir root]} (load-example "shapes_canvas")
             circle (first (interpret/get-field root "shapes"))
             g (canvas/make-graphics)]
         (interpret/call schema-ir methods-ir circle "draw" [g])
         (is (some #{[:begin-fill 15316448]} (canvas/graphics-log g)))
         (is (some #{[:draw-circle 80 120 24]} (canvas/graphics-log g)))
         (is (some #{[:end-fill]} (canvas/graphics-log g)))))))

(deftest else-if-expression
  (testing "else if chains evaluate the matching branch"
    (let [schema "Rect = Int/x Int/y Int/width Int/height
Game = Rect Int/score"
          methods "Game::pick = {
  n = score.
  if (n == 0) { 10 } else if (n == 1) { 20 } else { 30 }
}"
          prog (interpret/load-program (str "## Schema\n```\n" schema
                                           "\n```\n## Construction\n```\n[:Game [0 0 1 1] 1]\n```\n## Methods\n```\n"
                                           methods "\n```\n## Target\n```\n%canvas\n\n%init\nfunction init() {}\n\n%step\nfunction step() {}\n```"))
          {:keys [schema-ir methods-ir root]} prog]
      (is (= 20 (interpret/call schema-ir methods-ir root "pick" []))))))

(deftest update-preserves-mailbox-identity
  (testing "constructing a mailbox slot in update mutates fields in place"
    (let [schema ">Keys = Bool/left Bool/right Bool/up Bool/down
Game = Int/score $Time Keys
Time = Int/t"
          methods "Time::update = { [:Time (t + 1)] }
Keys::update = { [:Keys left right up down] }
Game::update = { [:Game score time [:Keys true false false false]] }"
          prog (interpret/load-program (str "## Schema\n```\n" schema
                                           "\n```\n## Construction\n```\n[:Game 0 [:Time 0] [:Keys false false false false]]\n```\n## Methods\n```\n"
                                           methods "\n```\n## Target\n```\n%canvas\n\n%init\nfunction init() {}\n\n%step\nfunction step() {}\n```"))
          {:keys [schema-ir methods-ir root]} prog
          keys-before (interpret/get-field root "keys")]
      (interpret/call schema-ir methods-ir root "update" [])
      (is (identical? keys-before (interpret/get-field root "keys")))
      (is (true? (interpret/get-field (interpret/get-field root "keys") "left"))))))
