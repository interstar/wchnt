(ns wchnt-lang.diagram.layout
  "Place a diagram model on a plane.

   Layered layout: owners sit above their parts and interfaces above their
   implementers. Edges that would close a cycle are ignored for layering.
   Within a layer, nodes are ordered by the mean position of their neighbours
   to cut crossings. A layer wider than :max-width wraps onto extra rows, so a
   phone gets a tall, narrow diagram.

   Edges are straight unless that would cross another box; then they take an
   orthogonal detour through the empty gaps between rows.

   Box sizes come from character counts (monospace) rather than DOM
   measurement, which keeps this pure and testable on the JVM."
  (:require [wchnt-lang.diagram.text :as text]))

(def metrics
  {:char-w 7.4      ; monospace advance at font-size 12, rounded up
   :line-h 16
   :pad 8
   :min-w 90
   :gap-x 48
   :gap-y 64
   :margin 32
   :loop-size 24
   :parallel-gap 10
   :min-x 8})       ; nothing is drawn left of this

;; --- boxes -------------------------------------------------------------------

(defn- block-height
  [n-lines]
  (+ (:pad metrics) (* n-lines (:line-h metrics))))

(defn- blocks
  "Header then body compartments, each {:lines :top :height} relative to the box."
  [node]
  (let [parts (cons (text/header-lines node) (text/body-sections node))
        heights (map (comp block-height count) parts)
        tops (reductions + 0 heights)]
    (mapv (fn [lines top h] {:lines lines :top top :height h})
          parts tops heights)))

(defn- box-width
  [bs]
  (let [widest (reduce max 0 (map text/line-length (mapcat :lines bs)))]
    (max (:min-w metrics)
         (+ (* 2 (:pad metrics)) (* widest (:char-w metrics))))))

(defn- size-node
  [node]
  (let [bs (blocks node)
        last-block (peek bs)]
    (assoc node
           :blocks bs
           :w (box-width bs)
           :h (+ (:top last-block) (:height last-block)))))

;; --- layering ----------------------------------------------------------------

(defn- down-edge
  "Direction used for layering: owner → part, interface → implementer."
  [{:keys [kind from to]}]
  (if (= :is-a kind) [to from] [from to]))

(defn- successors
  [edges]
  (reduce (fn [m [a b]] (update m a (fnil conj []) b)) {} edges))

(defn- dfs
  "Depth-first walk keeping tree, forward and cross edges in :keep and
   dropping back edges (those that point at a node on the current path)."
  [succ state id]
  (if (contains? (:done state) id)
    state
    (let [state (reduce (fn [st child]
                          (if (contains? (:path st) child)
                            st
                            (dfs succ (update st :keep conj [id child]) child)))
                        (update state :path conj id)
                        (get succ id []))]
      (-> state
          (update :path disj id)
          (update :done conj id)))))

(defn- acyclic-edges
  [ids edges]
  (let [targets (set (map second edges))
        starts (concat (remove targets ids) (filter targets ids))
        succ (successors edges)]
    (:keep (reduce (partial dfs succ)
                   {:done #{} :path #{} :keep #{}}
                   starts))))

(defn- layer-of
  [preds acc id]
  (if (contains? acc id)
    acc
    (let [ps (map first (get preds id))
          acc (reduce (partial layer-of preds) acc ps)]
      (assoc acc id (if (seq ps)
                      (inc (reduce max (map acc ps)))
                      0)))))

(defn- assign-layers
  "Longest-path layering over an acyclic edge set: vector of id vectors."
  [ids edges]
  (let [preds (group-by second edges)
        layer (reduce (partial layer-of preds) {} ids)
        n (inc (reduce max -1 (vals layer)))]
    (reduce (fn [ls id] (update ls (layer id) conj id))
            (vec (repeat n []))
            ids)))

;; --- ordering ----------------------------------------------------------------

(defn- neighbour-map
  [edges]
  (reduce (fn [m [a b]]
            (-> m
                (update a (fnil conj []) b)
                (update b (fnil conj []) a)))
          {} edges))

(defn- barycentre-sort
  [row neighbours fixed-pos]
  (let [own (zipmap row (range))
        score (fn [id]
                (let [ps (keep fixed-pos (neighbours id))]
                  (if (seq ps)
                    (/ (reduce + ps) (count ps))
                    (own id))))]
    (vec (sort-by score row))))

(defn- sweep
  [layers neighbours indices step]
  (reduce (fn [ls i]
            (let [fixed (ls (+ i step))
                  fixed-pos (zipmap fixed (range))]
              (assoc ls i (barycentre-sort (ls i) neighbours fixed-pos))))
          layers
          indices))

(defn- order-layers
  [layers edges]
  (let [neighbours (neighbour-map edges)
        n (count layers)
        down #(sweep % neighbours (range 1 n) -1)
        up #(sweep % neighbours (range (- n 2) -1 -1) 1)]
    (-> layers down up down up)))

;; --- placement ---------------------------------------------------------------

(defn- row-width
  [row sizes]
  (+ (reduce + (map #(:w (sizes %)) row))
     (* (:gap-x metrics) (max 0 (dec (count row))))))

(defn- wrap-layer
  [layer sizes max-row-w]
  (reduce (fn [rows id]
            (let [current (peek rows)
                  wider (+ (row-width current sizes) (:gap-x metrics) (:w (sizes id)))]
              (if (and (seq current) (> wider max-row-w))
                (conj rows [id])
                (conj (pop rows) (conj current id)))))
          [[]]
          layer))

(defn- place-row
  "Centre the row in frame-w; a row wider than the frame starts at the margin."
  [row sizes frame-w y]
  (let [x0 (+ (:margin metrics) (max 0 (/ (- frame-w (row-width row sizes)) 2)))
        xs (reductions + x0 (map #(+ (:gap-x metrics) (:w (sizes %))) row))]
    (map (fn [id x] [id (assoc (sizes id) :x x :y y)]) row xs)))

(defn- place-rows
  "{:boxes {id node} :height}. Rows centre on the screen (max-row-w), not on
   the widest row, so a phone keeps narrow boxes in view."
  [rows sizes max-row-w]
  (let [frame-w (min max-row-w (reduce max 0 (map #(row-width % sizes) rows)))
        placed (reduce (fn [{:keys [y boxes]} row]
                         (let [h (reduce max 0 (map #(:h (sizes %)) row))]
                           {:y (+ y h (:gap-y metrics))
                            :boxes (into boxes (place-row row sizes frame-w y))}))
                       {:y (:margin metrics) :boxes {}}
                       rows)]
    {:boxes (:boxes placed)
     :height (if (seq rows)
               (+ (- (:y placed) (:gap-y metrics)) (:margin metrics))
               (* 2 (:margin metrics)))}))

;; --- edge geometry -----------------------------------------------------------

(defn- centre
  [{:keys [x y w h]}]
  [(+ x (/ w 2)) (+ y (/ h 2))])

(defn- exit-point
  "Where a ray from [px py] (inside box) along [dx dy] leaves the box."
  [{:keys [x y w h]} [px py] [dx dy]]
  (let [axis-t (fn [p d lo hi]
                 (cond (pos? d) (/ (- hi p) d)
                       (neg? d) (/ (- lo p) d)
                       :else ##Inf))
        t (min (axis-t px dx x (+ x w)) (axis-t py dy y (+ y h)))]
    [(+ px (* t dx)) (+ py (* t dy))]))

(defn- unit-normal
  [[ax ay] [bx by]]
  (let [dx (- bx ax) dy (- by ay)
        len (Math/sqrt (+ (* dx dx) (* dy dy)))]
    (if (zero? len) [0 1] [(/ (- dy) len) (/ dx len)])))

(defn- straight-points
  "Endpoints on both borders, shifted sideways by offset for parallel edges."
  [from-box to-box offset]
  (let [[a b] (sort [(:id from-box) (:id to-box)])
        canonical (if (= a (:id from-box)) [from-box to-box] [to-box from-box])
        [nx ny] (apply unit-normal (map centre canonical))
        shift (fn [[x y]] [(+ x (* nx offset)) (+ y (* ny offset))])
        p (shift (centre from-box))
        q (shift (centre to-box))
        d [(- (first q) (first p)) (- (second q) (second p))]]
    [(exit-point from-box p d)
     (exit-point to-box q (mapv - d))]))

(defn- loop-points
  [{:keys [x y w h]}]
  (let [right (+ x w)
        out (+ right (:loop-size metrics))
        top (+ y (* 0.3 h))
        bottom (+ y (* 0.7 h))]
    [[right top] [out top] [out bottom] [right bottom]]))

(defn- parallel-offsets
  "Sideways offset per edge index so edges between the same pair don't overlap."
  [edges]
  (let [pair-of (fn [i] (set [(:from (edges i)) (:to (edges i))]))
        groups (vals (group-by pair-of (range (count edges))))]
    (into {}
          (mapcat (fn [idxs]
                    (let [mid (/ (dec (count idxs)) 2)]
                      (map-indexed (fn [n i] [i (* (:parallel-gap metrics) (- n mid))])
                                   idxs)))
                  groups))))

(defn- crosses-box?
  "Does segment p→q pass through the inside of box (2px inset)? Liang–Barsky."
  [[ax ay] [bx by] {:keys [x y w h]}]
  (let [dx (- bx ax) dy (- by ay)
        clip (fn [[t0 t1] p q]
               (cond
                 (zero? p) (when-not (neg? q) [t0 t1])
                 (neg? p) [(max t0 (/ q p)) t1]
                 :else [t0 (min t1 (/ q p))]))
        span (reduce (fn [span [p q]] (when span (clip span p q)))
                     [0 1]
                     [[(- dx) (- ax (+ x 2))] [dx (- (+ x w -2) ax)]
                      [(- dy) (- ay (+ y 2))] [dy (- (+ y h -2) ay)]])]
    (boolean (and span (< (first span) (second span))))))

(defn- blocked?
  [boxes {:keys [from to]} points]
  (some (fn [[p q]]
          (some #(and (not= (:id %) from) (not= (:id %) to) (crosses-box? p q %))
                (vals boxes)))
        (partition 2 1 points)))

(defn- jitter
  "Small spread so detours that share a gap or an exit don't sit on each other."
  [k]
  (* 5 (- (mod k 5) 2)))

(defn- port-x
  "x of the nth detour attached to a box: alternately right and left of the
   centre, so edges sharing a box arrive at distinct points. The centre itself
   is left to straight edges, which usually land there."
  [{:keys [x w]} n]
  (let [m (inc n)
        step (if (even? m) (- (/ m 2)) (/ (inc m) 2))]
    (-> (+ x (/ w 2) (* step 0.18 w))
        (max (+ x 8))
        (min (+ x w -8)))))

(defn- row-bottoms
  "Rows align on top, so a row is every box with the same :y."
  [boxes]
  (reduce (fn [m {:keys [y h]}] (update m y (fnil max 0) (+ y h))) {} (vals boxes)))

(defn- gap-below [bottoms box k] (+ (bottoms (:y box)) (/ (:gap-y metrics) 2) (jitter k)))
(defn- gap-above [box k] (- (:y box) (/ (:gap-y metrics) 2) (jitter k)))

(defn- obstacles
  "Boxes overlapping the vertical band lo..hi."
  [boxes [lo hi]]
  (filter #(and (< (:y %) hi) (> (+ (:y %) (:h %)) lo)) (vals boxes)))

(defn- channel-x
  "x for the vertical run of a detour: the free column (beside an obstacle, or
   straight down from either end) that keeps the path shortest."
  [obs sx tx k]
  (let [step (+ 10 (* 5 (mod k 4)))
        free? (fn [x] (not-any? #(< (- (:x %) 4) x (+ (:x %) (:w %) 4)) obs))
        candidates (concat [sx tx]
                           (mapcat (fn [{:keys [x w]}] [(- x step) (+ x w step)]) obs))]
    (apply min-key
           #(+ (Math/abs (double (- % sx))) (Math/abs (double (- % tx))))
           (filter free? candidates))))

(defn- detour-points
  "Orthogonal path around the boxes: out through the empty gap beside the
   source row, down (or up) a free column, in through the gap beside the
   target row. Adjacent rows share one gap, giving a simple Z."
  [{:keys [boxes bottoms]} from-box to-box {:keys [k from-port to-port]}]
  (let [sx (port-x from-box from-port)
        tx (port-x to-box to-port)
        bottom (fn [b] (+ (:y b) (:h b)))
        same-row? (= (:y to-box) (:y from-box))
        down? (> (:y to-box) (:y from-box))
        [s-y g1] (if (or down? same-row?)
                   [(bottom from-box) (gap-below bottoms from-box k)]
                   [(:y from-box) (gap-above from-box k)])
        [t-y g2] (cond same-row? [(bottom to-box) g1]
                       down? [(:y to-box) (gap-above to-box k)]
                       :else [(bottom to-box) (gap-below bottoms to-box k)])
        obs (obstacles boxes [(min g1 g2) (max g1 g2)])
        g2 (if (empty? obs) g1 g2)
        cx (channel-x obs sx tx k)]
    {:points (vec (dedupe [[sx s-y] [sx g1] [cx g1] [cx g2] [tx g2] [tx t-y]]))
     :label-at (if (< g2 t-y)
                 [(+ tx 4) (- t-y 6 (* 13 to-port))]
                 [(+ tx 4) (+ t-y 14 (* 13 to-port))])}))

(defn- detour
  "Detour e, claiming the next port on each end box. Detoured edges carry
   :label-at so labels stack beside the arrival instead of piling up."
  [ctx acc {:keys [from to] :as e}]
  (let [{:keys [ports detours]} acc
        slots {:k detours
               :from-port (get ports from 0)
               :to-port (get ports to 0)}
        route (detour-points ctx ((:boxes ctx) from) ((:boxes ctx) to) slots)]
    (-> acc
        (update :edges conj (merge e route))
        (update :detours inc)
        (update-in [:ports from] (fnil inc 0))
        (update-in [:ports to] (fnil inc 0)))))

(defn- route-edge
  "Accumulate routed edges: straight if clear, else an orthogonal detour."
  [{:keys [boxes offsets] :as ctx} acc i {:keys [from to] :as e}]
  (let [straight (when-not (= from to)
                   (straight-points (boxes from) (boxes to) (offsets i)))]
    (cond
      (= from to)
      (update acc :edges conj (assoc e :points (loop-points (boxes from))))

      (blocked? boxes e straight)
      (detour ctx acc e)

      :else
      (update acc :edges conj (assoc e :points straight)))))

(defn- route-edges
  [edges boxes]
  (let [ctx {:boxes boxes
             :offsets (parallel-offsets edges)
             :bottoms (row-bottoms boxes)}]
    (:edges (reduce-kv (fn [acc i e] (route-edge ctx acc i e))
                       {:edges [] :detours 0 :ports {}}
                       edges))))

(defn- shift-x
  [dx {:keys [nodes edges] :as laid}]
  (assoc laid
         :nodes (mapv #(update % :x + dx) nodes)
         :edges (mapv (fn [e]
                        (cond-> (update e :points (partial mapv (fn [[x y]] [(+ x dx) y])))
                          (:label-at e) (update-in [:label-at 0] + dx)))
                      edges)))

(defn- fit-extent
  "Shift right if detour columns ran left of :min-x, then size the drawing."
  [laid]
  (let [xs-of (fn [l] (concat (map :x (:nodes l)) (mapcat #(map first (:points %)) (:edges l))))
        dx (max 0 (- (:min-x metrics) (reduce min ##Inf (xs-of laid))))
        shifted (shift-x dx laid)
        right (reduce max 0 (concat (map #(+ (:x %) (:w %)) (:nodes shifted))
                                    (mapcat #(map first (:points %)) (:edges shifted))))]
    (assoc shifted :width (+ right (:margin metrics)))))

;; --- entry -------------------------------------------------------------------

(defn- ordered-rows
  [{:keys [nodes edges]} sizes max-row-w]
  (let [ids (mapv :id nodes)
        dag (vec (acyclic-edges ids (remove (fn [[a b]] (= a b))
                                            (distinct (map down-edge edges)))))
        layers (order-layers (assign-layers ids dag) dag)]
    (vec (mapcat #(wrap-layer % sizes max-row-w) layers))))

(defn layout
  "Diagram model → the same model with :x :y :w :h :blocks on each node,
   :points on each edge, and overall :width / :height.
   opts: {:max-width px} wraps wide layers to fit that width."
  ([diagram] (layout diagram {}))
  ([diagram {:keys [max-width]}]
   (let [sized (mapv size-node (:nodes diagram))
         sizes (zipmap (map :id sized) sized)
         max-row-w (if max-width (- max-width (* 2 (:margin metrics))) ##Inf)
         rows (ordered-rows diagram sizes max-row-w)
         {:keys [boxes height]} (place-rows rows sizes max-row-w)]
     (fit-extent
      (assoc diagram
             :nodes (mapv #(boxes (:id %)) sized)
             :edges (route-edges (vec (:edges diagram)) boxes)
             :height height)))))
