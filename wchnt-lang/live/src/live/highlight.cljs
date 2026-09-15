(ns live.highlight
  "Debounced CodeMirror 5 marks from Instaparse spans."
  (:require [clojure.string :as str]
            [wchnt-lang.highlight :as hl]))

(def ^:private delay-ms 1000)

(def ^:private kind-class
  {:class "cm-wchnt-class"
   :type "cm-wchnt-type"
   :name "cm-wchnt-name"
   :sigil "cm-wchnt-sigil"
   :rel-context "cm-wchnt-rel-context"
   :rel-delegate "cm-wchnt-rel-delegate"
   :rel-reactive "cm-wchnt-rel-reactive"
   :rel-external "cm-wchnt-rel-external"
   :rel-mailbox "cm-wchnt-rel-mailbox"
   :keyword "cm-wchnt-keyword"
   :string "cm-wchnt-string"
   :number "cm-wchnt-number"
   :method "cm-wchnt-method"
   :path "cm-wchnt-path"
   :error "cm-wchnt-error"})

(defn- pos
  [cm i]
  (.posFromIndex cm i))

(defn- clear-marks!
  [marks]
  (doseq [m @marks]
    (.clear m))
  (reset! marks []))

(defn- add-mark!
  [cm marks start end class-name]
  (when (and class-name (< start end))
    (swap! marks conj
           (.markText cm (pos cm start) (pos cm end)
                      #js {:className class-name
                           :inclusiveLeft false
                           :inclusiveRight false}))))

(defn- paint!
  [cm marks {:keys [spans errors]}]
  (clear-marks! marks)
  (doseq [s spans]
    (add-mark! cm marks (:start s) (:end s) (get kind-class (:kind s))))
  (doseq [e errors]
    (add-mark! cm marks (:start e) (:end e) (get kind-class :error))))

(defn- refresh!
  [cm marks prev]
  (try
    (let [text (.getValue cm)]
      (if (str/includes? text "```")
        (let [now (hl/highlight text)
              merged (hl/preserve @prev now)]
          (reset! prev merged)
          (paint! cm marks merged))
        (do (clear-marks! marks)
            (reset! prev {:spans [] :errors []}))))
    (catch :default e
      (.error js/console "WCHNT highlight failed" e))))

(defn attach!
  "Highlight now, then again 250ms after edits. Failed sections keep last good marks."
  [cm]
  (let [marks (atom [])
        prev (atom {:spans [] :errors []})
        timer (atom nil)
        kick (fn [] (refresh! cm marks prev))]
    (kick)
    (.on cm "change"
         (fn [_]
           (when-let [t @timer]
             (js/clearTimeout t))
           (reset! timer (js/setTimeout kick delay-ms))))
    cm))
