(ns live.editor
  "CodeMirror 5 buffer for a full .wcn markdown file."
  (:require [live.highlight :as highlight]
            [live.wiki :as wiki]))

(defn mount!
  [parent text on-wiki-navigate theme]
  (let [input-style (if (wiki/touch-primary-device?) "contenteditable" "textarea")
        cm (js/CodeMirror. parent
                           #js {:value text
                                :mode "markdown"
                                :theme theme
                                :lineNumbers true
                                :lineWrapping true
                                :inputStyle input-style})]
    (highlight/attach! cm)
    (when on-wiki-navigate
      (wiki/attach-link-clicks! cm on-wiki-navigate))
    cm))

(defn set-theme!
  "Switch the CodeMirror theme (e.g. \"material-darker\" or \"default\")."
  [view theme]
  (.setOption view "theme" theme))

(defn text
  [view]
  (.getValue view))

(defn set-text!
  [view text]
  (.setValue view text))
