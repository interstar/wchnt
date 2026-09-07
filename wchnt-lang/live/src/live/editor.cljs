(ns live.editor
  "CodeMirror 5 buffer for a full .wcn markdown file."
  (:require [live.highlight :as highlight]
            [live.wiki :as wiki]))

(defn mount!
  [parent text on-wiki-navigate]
  (wiki/ensure-wiki-mode!)
  (let [cm (js/CodeMirror. parent
                           #js {:value text
                                :mode wiki/wiki-mode
                                :theme "material-darker"
                                :lineNumbers true
                                :lineWrapping true})]
    (highlight/attach! cm)
    (when on-wiki-navigate
      (wiki/attach-link-clicks! cm on-wiki-navigate))
    cm))

(defn text
  [view]
  (.getValue view))

(defn set-text!
  [view text]
  (.setValue view text))
