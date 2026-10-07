(ns wchnt-lang.diagram.svg
  "Laid-out diagram → SVG string.

   Colours come from the editor's syntax classes (cm-wchnt-*): each edge
   group and text span carries one, and shapes paint with currentColor. Put
   the SVG inside an element with the CodeMirror theme class and the diagram
   follows light/dark like the code does. Presentation attributes give a
   readable fallback when no stylesheet is present.

   Heads are drawn as polygons inside the edge group, not SVG markers, so
   they inherit the edge colour."
  (:require [clojure.string :as str]
            [wchnt-lang.diagram.layout :as layout]))

(def ^:private diamond-len 16)
(def ^:private diamond-w 9)
(def ^:private triangle-len 14)
(def ^:private triangle-w 14)
(def ^:private font-size 12)

(def ^:private relationship-class
  {:ordinary "cm-wchnt-type"
   :context-specific "cm-wchnt-rel-context"
   :delegate "cm-wchnt-rel-delegate"
   :reactive "cm-wchnt-rel-reactive"
   :external "cm-wchnt-rel-external"})

(def ^:private owned?
  "Owned parts get a filled (composition) diamond; borrowed and observed
   parts a hollow (aggregation) one."
  #{:ordinary :context-specific :delegate})

;; --- strings -----------------------------------------------------------------

(defn- esc
  [s]
  (-> (str s)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- num-str
  [n]
  (let [r (/ (Math/round (* 10.0 (double n))) 10.0)]
    (if (== r (Math/floor r))
      (str (long r))
      (str r))))

(defn- points-attr
  [pts]
  (str/join " " (map (fn [[x y]] (str (num-str x) "," (num-str y))) pts)))

;; --- vectors -----------------------------------------------------------------

(defn- v+ [[ax ay] [bx by]] [(+ ax bx) (+ ay by)])
(defn- v- [[ax ay] [bx by]] [(- ax bx) (- ay by)])
(defn- v* [[x y] k] [(* x k) (* y k)])

(defn- unit
  [[x y]]
  (let [len (Math/sqrt (+ (* x x) (* y y)))]
    (if (zero? len) [1 0] [(/ x len) (/ y len)])))

(defn- normal [[x y]] [(- y) x])

;; --- edges -------------------------------------------------------------------

(defn- polygon
  [classes pts]
  (str "<polygon class=\"" classes "\" points=\"" (points-attr pts)
       "\" stroke=\"currentColor\" fill=\""
       (if (str/includes? classes "wd-filled") "currentColor" "none") "\"/>"))

(defn- polyline
  [pts]
  (str "<polyline class=\"wd-line\" points=\"" (points-attr pts)
       "\" fill=\"none\" stroke=\"currentColor\"/>"))

(defn- label
  [[x y] anchor labels]
  (str "<text class=\"wd-label\" x=\"" (num-str x) "\" y=\"" (num-str y)
       "\" text-anchor=\"" anchor "\" fill=\"currentColor\">"
       (esc (str/join ", " labels)) "</text>"))

(defn- diamond
  "Diamond with its tip at p pointing back along u; returns [shape tail]."
  [p u fill-class]
  (let [n (normal u)
        mid (v+ p (v* u (/ diamond-len 2)))
        tail (v+ p (v* u diamond-len))]
    [(polygon (str "wd-head " fill-class)
              [p (v+ mid (v* n (/ diamond-w 2))) tail (v- mid (v* n (/ diamond-w 2)))])
     tail]))

(defn- part-end-label-point
  "Field names sit near the part end, UML role-name style: edges leaving one
   owner fan out, so labels there collide far less than at the diamonds."
  [points]
  (let [q (last points)
        u (unit (v- q (last (butlast points))))]
    (v+ (v- q (v* u 18)) (v* (normal u) 10))))

(defn- has-a-edge
  [{:keys [from to relationship labels points label-at]}]
  (let [[p q] points
        u (unit (v- q p))
        [head tail] (diamond p u (if (owned? relationship) "wd-filled" "wd-hollow"))]
    (str "<g class=\"wd-edge wd-has-a " (relationship-class relationship)
         "\" data-from=\"" (esc from) "\" data-to=\"" (esc to) "\">"
         head
         (polyline (cons tail (rest points)))
         (if label-at
           (label label-at "start" labels)
           (label (part-end-label-point points) "middle" labels))
         "</g>")))

(defn- is-a-edge
  [{:keys [from to points]}]
  (let [tip (last points)
        before (last (butlast points))
        u (unit (v- tip before))
        base (v- tip (v* u triangle-len))
        n (normal u)]
    (str "<g class=\"wd-edge wd-is-a\" data-from=\"" (esc from)
         "\" data-to=\"" (esc to) "\">"
         (polygon "wd-head wd-hollow"
                  [tip (v+ base (v* n (/ triangle-w 2))) (v- base (v* n (/ triangle-w 2)))])
         (polyline (concat (butlast points) [base]))
         "</g>")))

(defn- edge
  [e]
  (case (:kind e)
    :has-a (has-a-edge e)
    :is-a (is-a-edge e)))

;; --- nodes -------------------------------------------------------------------

(defn- tspan
  [{:keys [class text]}]
  (str "<tspan class=\"" class "\" fill=\"currentColor\">" (esc text) "</tspan>"))

(defn- text-line
  [x y anchor line]
  (str "<text x=\"" (num-str x) "\" y=\"" (num-str y) "\" text-anchor=\"" anchor
       "\" xml:space=\"preserve\">"
       (apply str (map tspan line)) "</text>"))

(defn- baseline
  "Text baseline of line i in a block: half the padding above, 4px descent."
  [top i]
  (let [{:keys [pad line-h]} layout/metrics]
    (+ top (/ pad 2) (* line-h (inc i)) -4)))

(defn- block-svg
  [w {:keys [lines top]} header?]
  (let [[x anchor] (if header? [(/ w 2) "middle"] [(:pad layout/metrics) "start"])]
    (str (when-not header?
           (str "<line class=\"wd-rule\" x1=\"0\" y1=\"" (num-str top) "\" x2=\"" (num-str w)
                "\" y2=\"" (num-str top) "\" stroke=\"currentColor\"/>"))
         (apply str (map-indexed (fn [i line] (text-line x (baseline top i) anchor line))
                                 lines)))))

(defn- node
  [{:keys [id kind x y w h blocks]}]
  (str "<g class=\"wd-node wd-" (name kind) "\" data-class=\"" (esc id)
       "\" transform=\"translate(" (num-str x) "," (num-str y) ")\">"
       "<rect class=\"wd-box\" width=\"" (num-str w) "\" height=\"" (num-str h)
       "\" rx=\"4\" fill=\"none\" stroke=\"currentColor\""
       (when (#{:external :imported-interface} kind) " stroke-dasharray=\"5 3\"")
       "/>"
       (apply str (map-indexed (fn [i b] (block-svg w b (zero? i))) blocks))
       "</g>"))

;; --- entry -------------------------------------------------------------------

(defn render
  "SVG markup for a diagram produced by diagram.layout/layout.
   Edges are drawn first so boxes sit on top of any crossing lines."
  [{:keys [nodes edges width height]}]
  (str "<svg xmlns=\"http://www.w3.org/2000/svg\" class=\"wchnt-diagram\""
       " width=\"" (num-str width) "\" height=\"" (num-str height) "\""
       " viewBox=\"0 0 " (num-str width) " " (num-str height) "\""
       " font-family=\"ui-monospace, monospace\" font-size=\"" font-size "\">"
       (apply str (map edge edges))
       (apply str (map node nodes))
       "</svg>"))
