(ns wchnt-lang.targets.form
  "Live form target: DOM widgets are supplied by WCHNTForm."
  (:require [wchnt-lang.targets.core :as core]
            [wchnt-lang.targets.live-std :as live-std]))

(def plugin
  {:name "form"
   :backend :live
   :std :live
   :standard {:types #{"WCHNTMaths" "WCHNTConsole" "WCHNTGraphics" "WCHNTForm"}
              :bindings [{:name "wchntForm" :host-key :form}
                         {:name "wchntGraphics" :host-key :graphics}
                         {:name "graphics" :host-key :graphics}
                         {:name "input" :host-key :input}
                         {:name "wchntConsole" :host-key :console}
                         {:name "wchntMaths" :host-key :maths}]}
   :parse-target core/parse-target})
