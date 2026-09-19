(ns wchnt-lang.interpret-test
  "JVM interpreter: construction heap + bounce Methods. No browser."
  (:require [clojure.test :refer :all]
            [wchnt-lang.compiler :as compiler]
            [wchnt-lang.interpret :as interpret]))

(defn- bounce-program
  []
  (interpret/load-program (slurp "live-examples/bounce_canvas.wcn")))

(defn- with-paths-program
  []
  (interpret/load-program
   (str "# with paths\n"
        "## Schema\n```\n"
        "Game = PlayArea Ball $Time\n"
        "PlayArea = Rect\n"
        "Rect = Int/x Int/y Int/width Int/height\n"
        "Ball = Int/x Int/y Int/dx Int/dy Int/rad\n"
        "Time = Int/t\n"
        "```\n## Construction\n```\n"
        "[:Game [:PlayArea [0 0 800 600]] [:Ball 100 100 3 4 5] [:Time 0]]\n"
        "```\n## Methods\n```\n"
        "Rect::doubleWidth = { [:Rect | width = (width * 2)] }\n"
        "Ball::nudge = { Int/nx | [:Ball | x = nx] }\n"
        "Game::widen = { [:Game | playArea.rect.width = 400] }\n"
        "Time::update! = { [:Time | t = (t + 1)] }\n"
        "Game::update! = { [:Game | ball.x = (ball.x + ball.dx)] }\n"
        "```\n")))

(defn- array-get-program
  []
  (interpret/load-program
   (str "# array get\n"
        "## Schema\n```\n"
        "Team = [Player]/players\n"
        "Player = String/name\n"
        "```\n## Construction\n```\n"
        "[:Team [:Array/Player [:Player \"Ada\"] [:Player \"Bob\"]]]\n"
        "```\n## Methods\n```\n"
        "Team::first = { p = players.get(0). p.name }\n"
        "Team::second = { p = players.get(1). p.name }\n"
        "Team::bad = { p = players.get(2). p.name }\n"
        "```\n")))

(defn- numeric-conversion-program
  []
  (interpret/load-program
   (str "# numeric conversions\n"
        "## Schema\n```\n"
        "Game = Float/x\n"
        "```\n## Construction\n```\n"
        "[:Game 2.75]\n"
        "```\n## Methods\n```\n"
        "Game::truncated = { x.toInt() }\n"
        "Game::rounded = { x.round() }\n"
        "Game::floored = { x.floor() }\n"
        "Game::ceiled = { x.ceil() }\n"
        "```\n")))

(deftest numeric-conversions-interpret
  (testing "Float conversion methods have explicit, deterministic semantics"
    (let [{:keys [schema-ir methods-ir root]} (numeric-conversion-program)]
      (is (= 2 (interpret/call schema-ir methods-ir root "truncated" [])))
      (is (= 3 (interpret/call schema-ir methods-ir root "rounded" [])))
      (is (= 2 (interpret/call schema-ir methods-ir root "floored" [])))
      (is (= 3 (interpret/call schema-ir methods-ir root "ceiled" []))))))

(defn- mutating-method-program
  []
  (interpret/load-program
   (str "# mutating methods\n"
        "## Schema\n```\n"
        "Counter = $Clock\n"
        "Clock = Int/t\n"
        "```\n## Construction\n```\n"
        "[:Counter [:Clock 10]]\n"
        "```\n## Methods\n```\n"
        "Clock::update! = { [:Clock t] }\n"
        "Clock::advance! = { Int/delta | [:Clock (t + delta)] }\n"
        "Counter::update! = { [:Counter clock] }\n"
        "```\n")))

(deftest arbitrary-mutating-method-interpret
  (testing "the interpreter applies an argument-taking ! method in place"
    (let [{:keys [schema-ir methods-ir root]} (mutating-method-program)
          clock (interpret/get-field root "clock")]
      (is (identical? clock
                      (interpret/call schema-ir methods-ir clock "advance!" [7])))
      (is (= 17 (interpret/get-field clock "t")))
      (is (= clock (interpret/get-field root "clock"))))))

(deftest array-get-interpret
  (testing "Array::get returns the element at an index"
    (let [{:keys [schema-ir methods-ir root]} (array-get-program)]
      (is (= "Ada" (interpret/call schema-ir methods-ir root "first" [])))
      (is (= "Bob" (interpret/call schema-ir methods-ir root "second" [])))))

  (testing "Array::get out of range fails fast"
    (let [{:keys [schema-ir methods-ir root]} (array-get-program)]
      (is (thrown-with-msg? Exception #"out of range"
                            (interpret/call schema-ir methods-ir root "bad" []))))))

(deftest with-path-copies-untouched-fields
  (testing "[:Rect | width = ...] keeps x, y, height"
    (let [{:keys [schema-ir methods-ir root]} (with-paths-program)
          rect (interpret/get-field (interpret/get-field root "playArea") "rect")
          doubled (interpret/call schema-ir methods-ir rect "doubleWidth" [])]
      (is (= {:wchnt/class "Rect" :x 0 :y 0 :width 1600 :height 600} doubled))))

  (testing "[:Ball | x = nx] with a source-less this keeps the other Ball fields"
    (let [{:keys [schema-ir methods-ir root]} (with-paths-program)
          ball (interpret/get-field root "ball")
          moved (interpret/call schema-ir methods-ir ball "nudge" [42])]
      (is (= {:wchnt/class "Ball" :x 42 :y 100 :dx 3 :dy 4 :rad 5} moved))))

  (testing "nested write-path rebuilds PlayArea.rect.width only"
    (let [{:keys [schema-ir methods-ir root]} (with-paths-program)
          next (interpret/call schema-ir methods-ir root "widen" [])
          next-rect (interpret/get-field (interpret/get-field next "playArea") "rect")]
      (is (= 400 (interpret/get-field next-rect "width")))
      (is (= 600 (interpret/get-field next-rect "height")))
      (is (= (interpret/get-field root "ball") (interpret/get-field next "ball"))))))

(deftest with-path-update-patches-identity
  (testing "Game::update! write-path replaces ball and keeps the Time cell"
    (let [{:keys [schema-ir methods-ir root]} (with-paths-program)
          time (interpret/get-field root "time")]
      (interpret/call schema-ir methods-ir time "update!" [])
      (is (= 1 (interpret/get-field time "t")))
      (is (= 103 (interpret/get-field (interpret/get-field root "ball") "x")))
      (is (identical? time (interpret/get-field root "time"))))))

(deftest construct-bounce-heap
  (testing "bounce construction is nested maps with schema field names"
    (let [{:keys [root]} (bounce-program)
          snapshot (interpret/materialize root)]
      (is (= "Game" (:wchnt/class snapshot)))
      (is (= "PlayArea" (get-in snapshot [:playArea :wchnt/class])))
      (is (= "Rect" (get-in snapshot [:playArea :rect :wchnt/class])))
      (is (= {:wchnt/class "Rect" :x 0 :y 0 :width 800 :height 600}
             (get-in snapshot [:playArea :rect])))
      (is (= {:wchnt/class "Ball" :x 200 :y 150 :dx 6 :dy 5 :rad 16}
             (:ball snapshot))))))

(deftest bounce-dx-away-from-walls
  (testing "Game::bounceDx is +dx while the ball is inside the rect"
    (let [{:keys [schema-ir methods-ir root]} (bounce-program)]
      (is (= 6 (interpret/call schema-ir methods-ir root "bounceDx" [])))
      (is (= 5 (interpret/call schema-ir methods-ir root "bounceDy" []))))))

(deftest bounce-step-moves-the-ball
  (testing "Game::step builds a new Game with the ball translated by dx/dy"
    (let [{:keys [schema-ir methods-ir root]} (bounce-program)
          next (interpret/call schema-ir methods-ir root "step" [])
          root-snapshot (interpret/materialize root)
          next-snapshot (interpret/materialize next)]
      (is (= "Game" (:wchnt/class next-snapshot)))
      (is (= (get-in root-snapshot [:playArea :rect])
             (get-in next-snapshot [:playArea :rect])))
      (is (= {:wchnt/class "Ball" :x 206 :y 155 :dx 6 :dy 5 :rad 16}
             (:ball next-snapshot))))))

(deftest bounce-step-reverses-at-the-right-edge
  (testing "repeated step flips dx after x passes the play-area width"
    (let [{:keys [schema-ir methods-ir root]} (bounce-program)
          after (nth (iterate #(interpret/call schema-ir methods-ir % "step" [])
                              root)
                     102)
          snapshot (interpret/materialize after)]
      (is (>= (get-in snapshot [:ball :x]) 800))
      (is (neg? (get-in snapshot [:ball :dx]))))))

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

;; --- context-specific components across a step -----------------------------
;; A `:Ball` reaches its context parent via `theGame`. When Game::step rebuilds
;; the Game (and a fresh Ball) each tick, the new Ball must be re-wired with its
;; context, or the *next* tick can't resolve `theGame`.

(def context-bounce
  (str "## Schema\n\n```\n"
       "Game = PlayArea :Ball\n"
       "PlayArea = Rect\n"
       "Rect = Int/x Int/y Int/width Int/height\n"
       "Ball = Int/x Int/y Int/dx Int/dy Int/rad\n```\n\n"
       "## Construction\n\n```\n"
       "[:Game [:PlayArea [0 0 800 600]] [:Ball 200 150 6 5 16]]\n```\n\n"
       "## Methods\n\n```\n"
       "Ball::bounceDX = {\n  r = theGame.playArea.rect.\n"
       "  if ((x < r.x) or (x > (r.x + r.width))) { -dx } else { dx }\n}\n\n"
       "Ball::bounceDY = {\n  r = theGame.playArea.rect.\n"
       "  if ((y < r.y) or (y > (r.y + r.height))) { -dy } else { dy }\n}\n\n"
       "Game::step = {\n  ndx = ball.bounceDX().\n  ndy = ball.bounceDY().\n"
       "  [:Game playArea [:Ball (ball.x + ndx) (ball.y + ndy) ndx ndy ball.rad]]\n}\n```\n\n"
       "## Target\n\n```\n%canvas\n\n"
       "%init\nfunction init(){ assemblage = GameAssemblage.factory(); }\n\n"
       "%step\nfunction step(){ assemblage = assemblage.step(); }\n```\n"))

(deftest context-wired-on-initial-construction
  (testing "the constructed ball carries a theGame back-reference to its parent"
    (let [{:keys [root]} (interpret/load-program context-bounce)]
      (is (= "Game" (get-in (interpret/materialize root)
                            [:ball :theGame :wchnt/class]))))))

(deftest context-rewired-after-step
  (testing "Game::step's freshly built ball is re-wired with theGame"
    (let [{:keys [schema-ir methods-ir root]} (interpret/load-program context-bounce)
          next (interpret/call schema-ir methods-ir root "step" [])]
      (is (= "Game" (get-in (interpret/materialize next)
                            [:ball :theGame :wchnt/class]))))))

(deftest context-bounce-survives-repeated-steps
  (testing "stepping many times keeps resolving theGame and eventually bounces"
    (let [{:keys [schema-ir methods-ir root]} (interpret/load-program context-bounce)
          after (nth (iterate #(interpret/call schema-ir methods-ir % "step" [])
                              root)
                     102)
          snapshot (interpret/materialize after)]
      (is (>= (get-in snapshot [:ball :x]) 800))
      (is (neg? (get-in snapshot [:ball :dx]))))))

(def reactive-step-program
  (str "## Schema\n\n```\n"
       "Game = $Time\n"
       "Time = Int/t\n```\n\n"
       "## Construction\n\n```\n[:Game [:Time 0]]\n```\n\n"
       "## Methods\n\n```\n"
       "Time::update! = { [:Time t] }\n"
       "Game::update! = { [:Game time] }\n"
       "Game::step = {\n  [:Game [:Time (time.t + 1)]]\n}\n```\n\n"
       "## Target\n\n```\n%canvas\n\n"
       "%init\nfunction init(){ assemblage = GameAssemblage.factory(); }\n\n"
       "%step\nfunction step(){ assemblage = assemblage.step(); }\n```\n"))

(defn- subscribed?
  [observable subscriber]
  (some #(identical? % subscriber) @(:wchnt/subscribers observable)))

(deftest reactive-subscribed-on-initial-construction
  (testing "Game constructed with $Time is subscribed for notify"
    (let [{:keys [root]} (interpret/load-program reactive-step-program)
          time (interpret/get-field root "time")]
      (is (subscribed? time root)))))

(deftest reactive-resubscribed-after-step
  (testing "Game::step's freshly built Game is subscribed to the new Time"
    (let [{:keys [schema-ir methods-ir root]} (interpret/load-program reactive-step-program)
          next (interpret/call schema-ir methods-ir root "step" [])
          next-time (interpret/get-field next "time")]
      (is (= 1 (interpret/get-field next-time "t")))
      (is (subscribed? next-time next))
      (is (= 1 (count @(:wchnt/subscribers next-time)))))))

(defn- adventure-program
  []
  (interpret/load-program (slurp "live-examples/adventure_cli.wcn")))

(deftest construct-adventure-maps
  (testing "WorldMap places and Location exits are sparse enum maps"
    (let [{:keys [root]} (adventure-program)
          snapshot (interpret/materialize root)
          places (:places (:worldMap snapshot))
          here (get places "Village")]
      (is (= "Game" (:wchnt/class snapshot)))
      (is (= "WorldMap" (:wchnt/class (:worldMap snapshot))))
      (is (= "Village" (:here snapshot)))
      (is (= "Location" (:wchnt/class here)))
      (is (= "Village Square" (:description here)))
      (is (= "NorthGate" (get (:exits here) "N")))
      (is (= "Market" (get (:exits here) "E")))
      (is (not (contains? (:exits (get places "NorthGate")) "E"))))))

(deftest adventure-look-and-move
  (testing "look and move read sparse exits and concat descriptions"
    (let [{:keys [schema-ir methods-ir root]} (adventure-program)
          north (interpret/call schema-ir methods-ir root "move" ["N"])
          blocked (interpret/call schema-ir methods-ir north "move" ["E"])]
      (is (= "You are in Village Square."
             (interpret/call schema-ir methods-ir root "look" [])))
      (let [north (interpret/materialize north)
            blocked (interpret/materialize blocked)]
        (is (= "NorthGate" (:here north)))
        (is (= "You are in North Gate." (:msg north)))
        (is (= "NorthGate" (:here blocked)))
        (is (= "You can't go that way." (:msg blocked)))))))

(deftest array-map-filter-fold
  (testing "filter keeps matching elements; map and fold still work"
    (let [{:keys [schema-ir methods-ir root]}
          (interpret/load-program (slurp "examples/team_stats.wcn"))
          scorers (interpret/call schema-ir methods-ir root "scorers" [])
          names (interpret/call schema-ir methods-ir root "names" [])]
      (is (= ["Ada" "Bob" "Cy"] names))
      (is (= 2 (count scorers)))
      (is (= ["Ada" "Cy"] (mapv :name scorers)))
      (is (= [3 5] (mapv :score scorers)))
      (is (= 8 (interpret/call schema-ir methods-ir root "total" []))))))

(deftest map-map-filter-fold
  (testing "map combinators walk key and value; filter keeps entries; fold seeds first"
    (let [{:keys [schema-ir methods-ir root]}
          (interpret/load-program
           (str "# t\n## Schema\n```\n"
                "Team = {String : Int}/scores\n"
                "```\n## Construction\n```\n"
                "[:Team {String:Int \"Ada\":3 \"Bob\":0 \"Cy\":5}]\n"
                "```\n## Methods\n```\n"
                "Team::boosted = { scores.map({ k, v | v + 1 }) }\n"
                "Team::hot = { scores.filter({ k, v | v > 0 }) }\n"
                "Team::sum = { scores.fold(0, { acc, k, v | acc + v }) }\n"
                "Team::ada = { scores.filter({ k, v | k == \"Ada\" }) }\n"
                "```\n"))
          boosted (interpret/call schema-ir methods-ir root "boosted" [])
          hot (interpret/call schema-ir methods-ir root "hot" [])
          ada (interpret/call schema-ir methods-ir root "ada" [])]
      (is (= {"Ada" 4 "Bob" 1 "Cy" 6} boosted))
      (is (= {"Ada" 3 "Cy" 5} hot))
      (is (= {"Ada" 3} ada))
      (is (= 8 (interpret/call schema-ir methods-ir root "sum" []))))))

(deftest fold-class-and-string-seed
  (testing "fold acc is the seed's class or String, not only Int"
    (let [{:keys [schema-ir methods-ir root]}
          (interpret/load-program
           (str "# t\n## Schema\n```\n"
                "Team = [Player]/players\n"
                "Player = String/name Int/score\n"
                "Summary = Int/total Int/count\n"
                "```\n## Construction\n```\n"
                "[:Team [:Array/Player [\"Ada\" 3] [\"Bob\" 0] [\"Cy\" 5]]]\n"
                "```\n## Methods\n```\n"
                "Team::stats = { players.fold([:Summary 0 0], { acc, p | [:Summary (acc.total + p.score) (acc.count + 1)] }) }\n"
                "Team::joined = { players.fold(\"\", { acc, p | acc.concat(p.name) }) }\n"
                "```\n"))
          stats (interpret/call schema-ir methods-ir root "stats" [])]
      (is (= "Summary" (:wchnt/class stats)))
      (is (= 8 (:total stats)))
      (is (= 3 (:count stats)))
      (is (= "AdaBobCy" (interpret/call schema-ir methods-ir root "joined" []))))))

(deftest roster-map-get
  (testing "examples/roster.wcn constructs a String->Int map and get works"
    (let [{:keys [schema-ir methods-ir root]}
          (interpret/load-program (slurp "examples/roster.wcn"))]
      (is (= 3 (interpret/call schema-ir methods-ir root "adaScore" []))))))

(deftest map-exists-and-get-default
  (testing "exists is membership; get(key, fallback) stays the value type"
    (let [{:keys [schema-ir methods-ir root]}
          (interpret/load-program
           (str "# t\n## Schema\n```\n"
                "Team = String/name [Player]/players {String : Int}/scores\n"
                "Player = String/name Int/score\n"
                "```\n## Construction\n```\n"
                "[:Team \"Rockets\" [:Array/Player] {String:Int \"Ada\":3}]\n"
                "```\n## Methods\n```\n"
                "Team::hasAda = { scores.exists(\"Ada\") }\n"
                "Team::hasDi = { scores.exists(\"Di\") }\n"
                "Team::diOrZero = { scores.get(\"Di\", 0) }\n"
                "Team::adaOrZero = { scores.get(\"Ada\", 0) }\n"
                "```\n"))]
      (is (true? (interpret/call schema-ir methods-ir root "hasAda" [])))
      (is (false? (interpret/call schema-ir methods-ir root "hasDi" [])))
      (is (= 0 (interpret/call schema-ir methods-ir root "diOrZero" [])))
      (is (= 3 (interpret/call schema-ir methods-ir root "adaOrZero" []))))))

(deftest primitive-str-and-tpl
  (testing "str prints primitives; tpl fills named holes"
    (let [{:keys [schema-ir methods-ir root]}
          (interpret/load-program
           (str "# t\n## Schema\n```\n"
                "Player = String/name Int/score\n"
                "```\n## Construction\n```\n"
                "[:Player \"Ada\" 3]\n"
                "```\n## Methods\n```\n"
                "Player::n = { score.str() }\n"
                "Player::s = { name.str() }\n"
                "Player::hi = { \"hi {who} ({n}).\".tpl({String:String \"who\": name \"n\": score.str()}) }\n"
                "Player::fill = { String/t | t.tpl({String:String \"who\": name}) }\n"
                "```\n"))]
      (is (= "3" (interpret/call schema-ir methods-ir root "n" [])))
      (is (= "Ada" (interpret/call schema-ir methods-ir root "s" [])))
      (is (= "hi Ada (3)." (interpret/call schema-ir methods-ir root "hi" [])))
      (is (= "hey Ada"
             (interpret/call schema-ir methods-ir root "fill" ["hey {who}"])))
      (is (thrown-with-msg? Exception #"missing 'nope'"
                            (interpret/call schema-ir methods-ir root "fill"
                                            ["hey {nope}"]))))))

(deftest enum-ctor-map-get
  (testing "methods can use bare enum constructors as map keys"
    (let [{:keys [schema-ir methods-ir root]}
          (interpret/load-program
           (str "# t\n## Schema\n```\n"
                "Config = {Move : Int}/moves\n"
                "Move = \"Up\" | \"Down\"\n"
                "```\n## Construction\n```\n"
                "[:Config {Move:Int Up:3 Down:1}]\n"
                "```\n## Methods\n```\n"
                "Config::jump = { moves.get(Up) }\n"
                "Config::hasUp = { moves.exists(Up) }\n"
                "```\n"))]
      (is (= 3 (interpret/call schema-ir methods-ir root "jump" [])))
      (is (true? (interpret/call schema-ir methods-ir root "hasUp" []))))))

(deftest nested-objects-in-enum-maps
  (testing "map values can be nested object literals keyed by enums"
    (let [{:keys [root]}
          (interpret/load-program
           (str "# t\n## Schema\n```\n"
                "WorldMap = {LocationId : Location}/places\n"
                "Location = String/description {Direction : LocationId}/exits\n"
                "LocationId = \"Village\" | \"Market\"\n"
                "Direction = \"N\" | \"E\" | \"S\" | \"W\"\n"
                "Game = WorldMap LocationId/here\n"
                "```\n## Construction\n```\n"
                "[:Game\n"
                "  [:WorldMap\n"
                "    {LocationId:Location\n"
                "      Village [:Location \"square\" {Direction:LocationId N:Market}]\n"
                "      Market [:Location \"stalls\" {Direction:LocationId W:Village}]\n"
                "    }]\n"
                "  Village]\n"
                "```\n"))
          snapshot (interpret/materialize root)
          places (:places (:worldMap snapshot))
          village (get places "Village")]
      (is (= "Game" (:wchnt/class snapshot)))
      (is (= "Village" (:here snapshot)))
      (is (= "Location" (:wchnt/class village)))
      (is (= "square" (:description village)))
      (is (= "Market" (get (:exits village) "N"))))))
