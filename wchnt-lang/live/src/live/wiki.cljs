(ns live.wiki
  "Wiki navigation: [[PageName]] links in prose."
  (:require [clojure.string :as string]))

(def wiki-mode "markdown-wiki")

(defonce mode-defined? (atom false))

(defn find-links
  "Return [{:name :start :end}] for [[PageName]] in markdown."
  [text]
  (let [re (js/RegExp. "\\[\\[([^\\]]+)\\]\\]" "g")]
    (loop [acc []]
      (if-let [m (.exec re text)]
        (recur (conj acc {:name (string/trim (aget m 1))
                           :start (.-index m)
                           :end (+ (.-index m) (.-length m))}))
        acc))))

(defn- link-at-index
  [text index]
  (some #(when (and (<= (:start %) index) (< index (:end %)))
           %)
        (find-links text)))

(defn- name-from-link-text
  [text]
  (when text
    (or (second (re-find #"\[\[([^\]]+)\]\]" text))
        (when (and (seq (string/trim text))
                   (not (re-find #"[\[\]]" text)))
          (string/trim text)))))

(defn- link-from-dom
  "Read the page name from a .cm-wiki-link span under the click target."
  [evt]
  (loop [el (.-target evt)]
    (cond
      (nil? el) nil
      (and (.-classList el) (.contains (.-classList el) "cm-wiki-link"))
      (when-let [name (name-from-link-text (.-textContent el))]
        {:name name})

      :else (recur (.-parentElement el)))))

(defn- link-at-bbox
  "Hit-test link bounding boxes — works when nested markdown spans confuse coordsChar."
  [cm evt]
  (let [x (.-clientX evt)
        y (.-clientY evt)
        pad 3]
    (some (fn [{:keys [name start end]}]
            (let [from (.posFromIndex cm start)
                  to (.posFromIndex cm (max start (dec end)))]
              (try
                (let [a (.charCoords cm from "window")
                      b (.charCoords cm to "window")
                      left (min (.-left a) (.-left b))
                      right (max (.-right a) (.-right b))
                      top (min (.-top a) (.-top b))
                      bottom (max (.-bottom a) (.-bottom b))]
                  (when (and (<= (- left pad) x (+ right pad))
                             (<= (- top pad) y (+ bottom pad)))
                    {:name name}))
                (catch :default _ nil))))
          (find-links (.getValue cm)))))

(defn- link-at-index-neighbors
  [cm evt]
  (let [pos (.coordsChar cm #js {:left (.-clientX evt)
                                  :top (.-clientY evt)})
        index (.indexFromPos cm pos)
        text (.getValue cm)]
    (some #(link-at-index text %)
          (map #(+ index %) (range -2 3)))))

(defn- link-at-event
  [cm evt]
  (or (link-from-dom evt)
      (link-at-bbox cm evt)
      (link-at-index-neighbors cm evt)))

(defn ensure-wiki-mode!
  "Markdown plus an overlay that styles full [[PageName]] tokens."
  []
  (when-not @mode-defined?
    (reset! mode-defined? true)
    (.defineMode js/CodeMirror wiki-mode
                 (fn [config]
                   (let [md (.getMode js/CodeMirror config "markdown")
                         overlay #js {:token
                                      (fn [stream]
                                        (if (.match stream (js/RegExp. "\\[\\[[^\\]]+\\]\\]"))
                                          "wiki-link"
                                          (do (.next stream) nil)))}]
                     (.overlayMode js/CodeMirror md overlay false))))))

(defn attach-link-clicks!
  "Navigate when the user clicks a [[link]].
   CM instance .on does not receive DOM events — use CodeMirror.on on the scroller."
  [cm on-navigate]
  (let [scroller (.getScrollerElement cm)
        down-link (atom nil)
        on-down (fn [evt]
                  (when (== 0 (.-button evt))
                    (let [link (link-at-event cm evt)]
                      (reset! down-link link)
                      (when link
                        (.preventDefault evt)
                        (.stopPropagation evt)))))
        on-up (fn [evt]
                (when (== 0 (.-button evt))
                  (let [link (or (link-at-event cm evt) @down-link)]
                    (reset! down-link nil)
                    (when link
                      (.preventDefault evt)
                      (.stopPropagation evt)
                      (on-navigate (:name link))))))]
    (.on js/CodeMirror scroller "mousedown" on-down)
    (.on js/CodeMirror scroller "mouseup" on-up)))

(defn attach-link-marks!
  "Back-compat name: define mode styling + click handler."
  [cm on-navigate]
  (ensure-wiki-mode!)
  (attach-link-clicks! cm on-navigate)
  {:refresh (fn [])})
