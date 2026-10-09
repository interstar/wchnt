(ns wchnt-lang.diagram-test
  "Class-diagram model, layout, and SVG for the live Diagram view."
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [wchnt-lang.compiler :as compiler]
            [wchnt-lang.diagram.model :as model]
            [wchnt-lang.diagram.layout :as layout]
            [wchnt-lang.diagram.svg :as svg]
            [wchnt-lang.diagram.text :as text])
  (:import [javax.xml.parsers DocumentBuilderFactory]
           [java.io ByteArrayInputStream]))

;; --- helpers -----------------------------------------------------------------

(defn- fence
  [heading body]
  (when body
    (str "## " heading "\n\n```\n" body "\n```\n\n")))

(defn- page
  ([schema] (page schema nil nil nil))
  ([schema construction methods] (page schema construction methods nil))
  ([schema construction methods target]
   (str "# diagram\n\n"
        (fence "Schema" schema)
        (fence "Construction" construction)
        (fence "Methods" methods)
        (fence "Target" target))))

(defn- diagram
  [& args]
  (model/from-cargo (compiler/compile-to-ir (apply page args))))

(defn- node
  [d id]
  (first (filter #(= id (:id %)) (:nodes d))))

(defn- has-a
  [d from to]
  (first (filter #(and (= :has-a (:kind %)) (= from (:from %)) (= to (:to %)))
                 (:edges d))))

(defn- is-a?
  [d from to]
  (boolean (some #(and (= :is-a (:kind %)) (= from (:from %)) (= to (:to %)))
                 (:edges d))))

(def game-schema
  "Game = PlayArea Ball Paddle/p1 Paddle/p2 $Time :Engine @Pen [Ball]/balls {String:Shape}/shapes
>Keys = Bool/left
PlayArea = Int/width Int/height
Ball = Int/x Int/y
Paddle = Int/y
Time = Int/t
Engine = Int/cc
Pen = String/ink
Shape = Circle | Square
Circle = Int/r
Square = Int/s
Action = \"Run\" | \"Jump\"
Student = String/id +Paddle")

(def game-construction
  "[:Game [:PlayArea 1 2] [:Ball 1 2] [:Paddle 1] [:Paddle 2] [:Time 0] [:Engine 3] pen [[:Ball 1 1]] {\"a\": [:Circle 1]}]")

(def game-methods
  "Time::update! = { [:Time (t + 1)] }
Keys::update! = { [:Keys left] }
Game::update! = { [:Game | ball = ball] }
Ball::move = { Int/d | [:Ball (x + d) y] }
Shape::area = { } -> Int
Circle::area = { r * r }
Square::area = { s * s }")

(defn- game
  []
  (diagram game-schema game-construction game-methods))

;; --- model: relationships ----------------------------------------------------

(deftest compiled-page-is-ok
  (is (= :ok (:status (game)))))

(deftest sigils-become-coloured-has-a-edges
  (let [d (game)]
    (is (= :ordinary (:relationship (has-a d "Game" "PlayArea"))))
    (is (= :reactive (:relationship (has-a d "Game" "Time"))))
    (is (= :context-specific (:relationship (has-a d "Game" "Engine"))))
    (is (= :external (:relationship (has-a d "Game" "Pen"))))
    (is (= :delegate (:relationship (has-a d "Student" "Paddle"))))))

(deftest same-type-twice-is-one-edge-with-both-labels
  (let [d (game)
        paddles (filter #(and (= "Game" (:from %)) (= "Paddle" (:to %))) (:edges d))]
    (is (= 1 (count paddles)))
    (is (= ["p1" "p2"] (:labels (first paddles))))))

(deftest collections-point-at-their-element-class
  (let [d (game)]
    (is (some #{"balls [*]"} (:labels (has-a d "Game" "Ball"))))
    (is (= ["shapes {String:*}"] (:labels (has-a d "Game" "Shape"))))))

(deftest ordinary-and-collection-edges-to-same-class-merge
  (is (= ["ball" "balls [*]"] (:labels (has-a (game) "Game" "Ball")))))

(deftest class-components-are-not-listed-as-fields
  (is (= [] (:fields (node (game) "Game")))))

(deftest primitive-components-are-fields
  (is (= [{:name "width" :type "Int"} {:name "height" :type "Int"}]
         (:fields (node (game) "PlayArea")))))

(deftest context-child-shows-back-reference-field
  (is (= [{:name "cc" :type "Int"} {:name "theGame" :type "Game"}]
         (:fields (node (game) "Engine")))))

(deftest primitive-collection-is-a-field-in-wchnt-spelling
  (let [d (diagram "Team = [String]/names {String:Int}/scores")]
    (is (= [{:name "names" :type "[String]"} {:name "scores" :type "{String:Int}"}]
           (:fields (node d "Team"))))
    (is (empty? (:edges d)))))

;; --- model: sums, enums, stereotypes -----------------------------------------

(deftest sum-type-gives-is-a-edges-to-interface
  (let [d (game)]
    (is (= :interface (:kind (node d "Shape"))))
    (is (is-a? d "Circle" "Shape"))
    (is (is-a? d "Square" "Shape"))))

(deftest interface-carries-its-signature
  (is (= [{:name "area" :params [] :return "Int" :mutating? false :static? false}]
         (:methods (node (game) "Shape")))))

(deftest enum-node-lists-values
  (let [n (node (game) "Action")]
    (is (= :enum (:kind n)))
    (is (= ["Run" "Jump"] (:values n)))))

(deftest enum-typed-component-is-an-edge
  (let [d (diagram "Player = String/name Move/next\nMove = \"Left\" | \"Right\"")]
    (is (= :ordinary (:relationship (has-a d "Player" "Move"))))
    (is (= [{:name "name" :type "String"}] (:fields (node d "Player"))))))

(deftest stereotypes-mark-root-observable-mailbox
  (let [d (game)]
    (is (= ["root"] (:stereotypes (node d "Game"))))
    (is (= ["observable"] (:stereotypes (node d "Time"))))
    (is (= ["mailbox"] (:stereotypes (node d "Keys"))))
    (is (:mailbox? (node d "Keys")))
    (is (= [] (:stereotypes (node d "Ball"))))))

;; --- model: methods ----------------------------------------------------------

(deftest methods-carry-typed-signatures
  (let [d (game)]
    (is (= [{:name "move" :params [{:name "d" :type "Int"}] :return "Ball"
             :mutating? false :static? false}]
           (:methods (node d "Ball"))))
    (is (= [{:name "update!" :params [] :return "Time" :mutating? true :static? false}]
           (:methods (node d "Time"))))
    (is (= [] (:methods (node d "Paddle"))))))

(deftest enums-and-externals-have-no-methods-compartment
  (let [d (game)]
    (is (not (contains? (node d "Action") :methods)))))

;; --- model: host types -------------------------------------------------------

(def host-target
  "%canvas
%init
function init() {}
%step
function step() {}
%requires
Pen
Date
Date::CONSTRUCT(String) -> Date")

(deftest undefined-external-becomes-external-node
  (let [d (diagram "Sketch = String/name @Pen Date/dob" nil
                   "Sketch::draw = { @WCHNTGraphics/g | g.clear() }" host-target)]
    (is (= :ok (:status d)) (:error d))
    (is (= :external (:kind (node d "Pen"))))
    (is (= ["external"] (:stereotypes (node d "Pen"))))
    (is (= :external (:relationship (has-a d "Sketch" "Pen"))))))

(deftest platform-constructible-field-is-text
  (let [d (diagram "Sketch = String/name @Pen Date/dob" nil nil host-target)]
    (is (some #{{:name "dob" :type "Date"}} (:fields (node d "Sketch"))))
    (is (nil? (node d "Date")))))

(deftest stdlib-types-used-only-as-params-get-no-box
  (let [d (diagram "Sketch = String/name @Pen" nil
                   "Sketch::draw = { @WCHNTGraphics/g | g.clear() }" host-target)]
    (is (nil? (node d "WCHNTGraphics")))
    (is (= [{:name "g" :type "WCHNTGraphics"}]
           (:params (first (:methods (node d "Sketch"))))))))

;; --- model: failures ---------------------------------------------------------

(deftest broken-methods-still-draw-classes
  (let [d (diagram game-schema game-construction
                   (str/replace game-methods "{ r * r }" "{ r * zz }"))]
    (is (= :partial (:status d)))
    (is (str/includes? (:error d) "zz"))
    (is (= :failed (:methods (node d "Ball"))))
    (is (= :failed (:methods (node d "Shape"))))
    (is (= :reactive (:relationship (has-a d "Game" "Time"))))))

(deftest schema-error-gives-no-diagram
  (let [d (diagram "Game = = Ball")]
    (is (= :error (:status d)))
    (is (string? (:error d)))
    (is (empty? (:nodes d)))))

(deftest documentation-page-has-nothing-to-draw
  (let [d (model/from-cargo (compiler/compile-to-ir "# Notes\n\nJust prose.\n"))]
    (is (= :documentation (:status d)))
    (is (empty? (:nodes d)))))

(deftest unknown-relationship-fails-fast
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"relationship"
       (model/schema->diagram
        {:assemblages [{:name "A" :components [{:component-name "b" :type-name "B"
                                                :relationship :mystery}]}
                       {:name "B" :components []}]
         :interfaces [] :enums []}
        []))))

(deftest methods-on-unknown-class-fail-fast
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"Ghost"
       (model/schema->diagram
        {:assemblages [{:name "A" :components []}] :interfaces [] :enums []}
        [{:class "Ghost" :method-name "boo" :parameters [] :return-type "Int"}]))))

;; --- text --------------------------------------------------------------------

(deftest method-line-reads-like-wchnt
  (let [n {:id "Ball" :kind :class :stereotypes [] :fields []
           :methods [{:name "move" :params [{:name "d" :type "Int"}] :return "Ball"}]}]
    (is (= "move(d: Int) -> Ball"
           (text/line-text (first (second (text/body-sections n))))))))

(deftest failed-methods-say-so-in-the-box
  (let [n {:id "Ball" :kind :class :stereotypes [] :fields [] :methods :failed}]
    (is (= text/methods-failed-text
           (text/line-text (first (second (text/body-sections n))))))))

;; --- layout ------------------------------------------------------------------

(defn- boxes
  [laid]
  (into {} (map (juxt :id identity) (:nodes laid))))

(defn- overlap?
  [a b]
  (and (< (:x a) (+ (:x b) (:w b))) (< (:x b) (+ (:x a) (:w a)))
       (< (:y a) (+ (:y b) (:h b))) (< (:y b) (+ (:y a) (:h a)))))

(deftest laid-out-boxes-do-not-overlap
  (let [ns (:nodes (layout/layout (game)))]
    (doseq [a ns b ns :when (not= (:id a) (:id b))]
      (is (not (overlap? a b)) (str (:id a) " overlaps " (:id b))))))

(deftest owner-sits-above-its-parts
  (let [bs (boxes (layout/layout (game)))]
    (is (< (:y (bs "Game")) (:y (bs "Ball"))))
    (is (< (:y (bs "Game")) (:y (bs "Shape"))))))

(deftest interface-sits-above-implementers
  (let [bs (boxes (layout/layout (game)))]
    (is (< (:y (bs "Shape")) (:y (bs "Circle"))))))

(deftest boxes-fit-their-text
  (let [n (first (filter #(= "Ball" (:id %)) (:nodes (layout/layout (game)))))
        widest (reduce max (map text/line-length (mapcat :lines (:blocks n))))]
    (is (>= (:w n) (* widest (:char-w layout/metrics))))))

(deftest every-node-and-edge-is-placed
  (let [laid (layout/layout (game))]
    (is (every? #(and (number? (:x %)) (number? (:y %))) (:nodes laid)))
    (is (every? #(>= (count (:points %)) 2) (:edges laid)))))

(defn- on-boundary?
  [{:keys [x y w h]} [px py]]
  (let [eps 0.01
        within-x (<= (- x eps) px (+ x w eps))
        within-y (<= (- y eps) py (+ y h eps))
        near (fn [a b] (< (Math/abs (double (- a b))) eps))]
    (and within-x within-y
         (or (near px x) (near px (+ x w)) (near py y) (near py (+ y h))))))

(deftest edges-start-and-end-on-box-borders
  (let [laid (layout/layout (game))
        bs (boxes laid)]
    (doseq [{:keys [from to points]} (:edges laid)]
      (is (on-boundary? (bs from) (first points)) (str from "->" to " start"))
      (is (on-boundary? (bs to) (last points)) (str from "->" to " end")))))

(defn- segment-crosses-box?
  "True when segment a→b passes through the inside of box (2px inset)."
  [[ax ay] [bx by] {:keys [x y w h]}]
  (let [x0 (+ x 2) x1 (- (+ x w) 2) y0 (+ y 2) y1 (- (+ y h) 2)
        dx (- bx ax) dy (- by ay)
        clip (fn [[t0 t1] p q]
               (cond
                 (and (zero? p) (neg? q)) nil
                 (zero? p) [t0 t1]
                 :else (let [r (/ q p)]
                         (if (neg? p) [(max t0 r) t1] [t0 (min t1 r)]))))
        span (reduce (fn [acc [p q]] (when acc (clip acc p q)))
                     [0.0 1.0]
                     [[(- dx) (- ax x0)] [dx (- x1 ax)] [(- dy) (- ay y0)] [dy (- y1 ay)]])]
    (boolean (and span (< (first span) (second span))))))

(defn- edges-crossing-boxes
  [laid]
  (let [bs (boxes laid)]
    (for [{:keys [from to points]} (:edges laid)
          [a b] (partition 2 1 points)
          box (vals bs)
          :when (and (not= (:id box) from) (not= (:id box) to)
                     (segment-crosses-box? a b box))]
      [from to (:id box)])))

(deftest edges-never-pass-through-other-boxes
  (is (empty? (edges-crossing-boxes (layout/layout (game))))))

(deftest edges-avoid-boxes-on-narrow-screens
  (let [laid (layout/layout (game) {:max-width 360})]
    (is (empty? (edges-crossing-boxes laid)))
    (doseq [a (:nodes laid) b (:nodes laid) :when (not= (:id a) (:id b))]
      (is (not (overlap? a b))))))

(deftest narrow-rows-centre-on-the-screen-not-the-widest-box
  ;; Detour columns may push everything right; measure from the leftmost box.
  (let [laid (layout/layout (game) {:max-width 360})
        detour-shift (- (reduce min (map :x (:nodes laid))) (:margin layout/metrics))
        small (filter #(<= (:w %) 296) (:nodes laid))]
    (is (every? #(<= (- (+ (:x %) (:w %)) detour-shift) 360) small))
    (is (every? #(>= (:x %) 0) (:nodes laid)))))

(def cyclic-diagram
  {:nodes [{:id "A" :kind :class :stereotypes [] :fields [] :methods []}
           {:id "B" :kind :class :stereotypes [] :fields [] :methods []}
           {:id "Tree" :kind :class :stereotypes [] :fields [] :methods []}]
   :edges [{:kind :has-a :from "A" :to "B" :relationship :ordinary :labels ["b"]}
           {:kind :has-a :from "B" :to "A" :relationship :ordinary :labels ["a"]}
           {:kind :has-a :from "Tree" :to "Tree" :relationship :ordinary
            :labels ["kids [*]"]}]})

(deftest cycles-and-self-references-lay-out
  (let [laid (layout/layout cyclic-diagram)
        loop-edge (first (filter #(= "Tree" (:from %)) (:edges laid)))]
    (is (= 3 (count (:nodes laid))))
    (is (= 4 (count (:points loop-edge))))))

(def wide-diagram
  {:nodes (into [{:id "Root" :kind :class :stereotypes [] :fields [] :methods []}]
                (for [i (range 12)]
                  {:id (str "Part" i) :kind :class :stereotypes [] :fields [] :methods []}))
   :edges (vec (for [i (range 12)]
                 {:kind :has-a :from "Root" :to (str "Part" i) :relationship :ordinary
                  :labels [(str "p" i)]}))})

(deftest narrow-screens-wrap-wide-layers
  (let [wide (layout/layout wide-diagram)
        narrow (layout/layout wide-diagram {:max-width 360})]
    (is (> (:width wide) 360))
    (is (<= (:width narrow) 360))
    (is (> (:height narrow) (:height wide)))))

(deftest empty-diagram-lays-out
  (let [laid (layout/layout {:nodes [] :edges []})]
    (is (pos? (:width laid)))
    (is (empty? (:nodes laid)))))

;; --- svg ---------------------------------------------------------------------

(defn- well-formed?
  [s]
  (try
    (-> (DocumentBuilderFactory/newInstance)
        (.newDocumentBuilder)
        (.parse (ByteArrayInputStream. (.getBytes ^String s "UTF-8"))))
    true
    (catch Exception _ false)))

(defn- game-svg
  []
  (svg/render (layout/layout (game))))

(deftest svg-is-well-formed-xml
  (is (well-formed? (game-svg))))

(deftest svg-escapes-method-arrows
  (let [s (game-svg)]
    (is (str/includes? s ") -&gt; </tspan>"))
    (is (not (str/includes? s "->")))))

(deftest svg-edges-use-syntax-colour-classes
  (let [s (game-svg)]
    (is (str/includes? s "cm-wchnt-rel-context"))
    (is (str/includes? s "cm-wchnt-rel-reactive"))
    (is (str/includes? s "cm-wchnt-rel-external"))
    (is (str/includes? s "cm-wchnt-rel-delegate"))
    (is (str/includes? s "cm-wchnt-type"))
    (is (str/includes? s "wd-is-a"))))

(deftest svg-mailbox-name-uses-mailbox-colour
  (is (re-find #"cm-wchnt-rel-mailbox[^>]*>Keys<" (game-svg))))

(deftest svg-draws-one-head-per-edge
  (let [laid (layout/layout (game))
        s (svg/render laid)]
    (is (= (count (:edges laid))
           (count (re-seq #"class=\"wd-head" s))))))

(deftest svg-hollow-diamonds-for-borrowed-and-reactive
  (let [s (game-svg)]
    (is (re-find #"data-to=\"Pen\"[^>]*>\s*<polygon class=\"wd-head wd-hollow\"" s))
    (is (re-find #"data-to=\"Engine\"[^>]*>\s*<polygon class=\"wd-head wd-filled\"" s))))

(deftest svg-of-failed-methods-says-so
  (let [d (diagram game-schema game-construction
                   (str/replace game-methods "{ r * r }" "{ r * zz }"))
        s (svg/render (layout/layout d))]
    (is (well-formed? s))
    (is (str/includes? s text/methods-failed-text))))
