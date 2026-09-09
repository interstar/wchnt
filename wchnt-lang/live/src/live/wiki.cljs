(ns live.wiki
  "Wiki navigation: [[PageName]] links in prose."
  (:require [clojure.string :as string]))

(def link-attr "data-wiki-page")

(defn touch-primary-device?
  "True on phones/tablets; false on mouse-driven laptops (incl. touch-screen laptops)."
  []
  (let [mq (when (.-matchMedia js/window) (.-matchMedia js/window))]
    (boolean
     (or (and mq (.-matches (.call mq js/window "(pointer: coarse)")))
         (and mq (.-matches (.call mq js/window "(max-width: 768px)"))
              (.-ontouchstart js/window))))))

(defn find-links
  "Return [{:name :start :end}] for [[PageName]] in markdown."
  [text]
  (let [re (js/RegExp. "\\[\\[([^\\]]+)\\]\\]" "g")]
    (loop [acc []]
      (if-let [m (.exec re text)]
        (recur (conj acc {:name (string/trim (aget m 1))
                           :start (.-index m)
                           :end (+ (.-index m)
                                   (.-length (aget m 0)))}))
        acc))))

(defn- link-at-index
  [text index]
  (some #(when (and (<= (:start %) index) (< index (:end %))) %)
          (find-links text)))

(defn- clear-link-marks!
  [marks]
  (doseq [m @marks]
    (.clear m))
  (reset! marks []))

(defn- refresh-link-marks!
  [cm marks]
  (clear-link-marks! marks)
  (let [text (.getValue cm)]
    (doseq [{:keys [name start end]} (find-links text)]
      (try
        (let [mark (.markText cm
                              (.posFromIndex cm start)
                              (.posFromIndex cm end)
                              #js {:className "cm-wiki-link"
                                   :attributes (js-obj link-attr name)
                                   :handleMouseEvents true
                                   :inclusiveLeft false
                                   :inclusiveRight false})]
          (swap! marks conj mark))
        (catch :default _ nil)))))

(defn- first-touch
  [evt]
  (let [touches (.-touches evt)
        changed-touches (.-changedTouches evt)]
    (cond
      (and touches (> (.-length touches) 0)) (aget touches 0)
      (and changed-touches (> (.-length changed-touches) 0))
      (aget changed-touches 0)
      :else nil)))

(defn- client-xy
  [evt]
  (let [touch (first-touch evt)]
    [(if touch (.-clientX touch) (.-clientX evt))
     (if touch (.-clientY touch) (.-clientY evt))]))

(defn- link-page-at-event
  [cm evt]
  (when-let [target (.-target evt)]
    (or (when-let [el (.closest target (str "[" link-attr "]"))]
          (.getAttribute el link-attr))
        (let [[x y] (client-xy evt)
              pos (.coordsChar cm #js {:left x :top y} "window")
              index (.indexFromPos cm pos)]
          (:name (link-at-index (.getValue cm) index))))))

(defn attach-link-clicks!
  "Navigate when the user clicks/taps marked [[link]] text."
  [cm on-navigate]
  (let [marks (atom [])
        last-nav-ms (atom 0)
        navigate-once! (fn [evt page-name]
                         (.preventDefault evt)
                         (.stopPropagation evt)
                         (let [now (.now js/Date)]
                           (when (> (- now @last-nav-ms) 300)
                             (reset! last-nav-ms now)
                             (when-let [input (.getInputField cm)]
                               (.blur input))
                             (on-navigate page-name))))
        refresh (fn [] (refresh-link-marks! cm marks))
        schedule (fn [] (js/setTimeout refresh 0))
        handle-link-event (fn [evt]
                            (when-let [page (link-page-at-event cm evt)]
                              (navigate-once! evt page)))]
    (doseq [ms [0 50 200 500]]
      (js/setTimeout refresh ms))
    (.on cm "change" (fn [_ _] (schedule)))
    (.on cm "refresh" refresh)
    (.on cm "viewportChange" refresh)
    (.addEventListener js/window "resize" schedule)
    (let [wrapper (.getWrapperElement cm)]
      (doseq [event ["click" "mousedown"]]
        (.addEventListener wrapper event handle-link-event
                           #js {:capture true}))
      (.addEventListener wrapper "touchstart" handle-link-event
                         #js {:capture true :passive false}))
    {:refresh refresh}))

(defn attach-link-marks!
  "Back-compat name: click handler + link marks."
  [cm on-navigate]
  (attach-link-clicks! cm on-navigate))
