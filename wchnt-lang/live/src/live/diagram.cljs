(ns live.diagram
  "Diagram view: a static class diagram of the current page.

   Drawn once each time the Diagram button is pressed; the page cannot change
   while the diagram is showing. Model, layout and SVG live in the shared
   wchnt-lang.diagram.* namespaces."
  (:require [wchnt-lang.compiler :as compiler]
            [wchnt-lang.diagram.model :as model]
            [wchnt-lang.diagram.layout :as layout]
            [wchnt-lang.diagram.svg :as svg]
            [live.storage :as storage]
            [goog.object :as gobj]))

(defn- el [id]
  (.getElementById js/document id))

(defn- build
  "Markdown → diagram model. Model errors become :error, never a half picture."
  [markdown]
  (try
    (model/from-cargo (compiler/compile-to-ir markdown
                                              {:resolve-page storage/resolve-page}))
    (catch :default e
      {:status :error :error (or (ex-message e) (str e)) :nodes [] :edges []})))

(defn- message
  "[css-class text] for the banner above the diagram, or nil."
  [{:keys [status error]}]
  (case status
    :ok nil
    :partial ["error" (str "Drawn from the Schema only — a later stage failed:\n" error)]
    :error ["error" error]
    :documentation ["info" "Documentation page — no Schema to draw."]))

(defn- show-message!
  [diagram]
  (let [node (el "diagram-message")]
    (if-let [[css text] (message diagram)]
      (do (gobj/set node "className" css)
          (gobj/set node "textContent" text)
          (gobj/set node "hidden" false))
      (gobj/set node "hidden" true))))

(defn- available-width
  "Drawing width of the diagram canvas; the view must be visible to measure."
  []
  (let [canvas (el "diagram-canvas")]
    (when (pos? (.-clientWidth canvas))
      (.-clientWidth canvas))))

(defn render!
  "Compile markdown and draw its diagram into the (visible) diagram view."
  [markdown]
  (let [diagram (build markdown)
        canvas (el "diagram-canvas")]
    (show-message! diagram)
    (set! (.-innerHTML canvas)
          (if (seq (:nodes diagram))
            (svg/render (layout/layout diagram {:max-width (available-width)}))
            ""))))

(defn- fit-label
  [fit?]
  (if fit? "Actual size" "Fit width"))

(defn toggle-fit!
  "Switch between natural size (scroll to pan) and fit-to-width."
  []
  (let [canvas (el "diagram-canvas")
        fit? (.toggle (.-classList canvas) "fit")]
    (gobj/set (el "diagram-fit") "textContent" (fit-label fit?))))
