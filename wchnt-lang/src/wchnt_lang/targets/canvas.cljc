(ns wchnt-lang.targets.canvas
  (:require [wchnt-lang.targets.core :as core]
            [wchnt-lang.targets.live-std :as live-std]))

(def plugin
  {:name "canvas"
   :backend :live
   :std :live
   :standard {:types #{"WCHNTMaths" "WCHNTConsole" "WCHNTGraphics" "WCHNTInput"}
              :bindings [{:name "wchntGraphics" :host-key :graphics}
                         {:name "wchntInput" :host-key :input}
                         {:name "wchntConsole" :host-key :console}
                         {:name "wchntMaths" :host-key :maths}]}
   :parse-target core/parse-target})
