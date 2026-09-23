(ns wchnt-lang.targets.cli-live
  (:require [wchnt-lang.targets.core :as core]
            [wchnt-lang.targets.live-std :as live-std]))

(def plugin
  {:name "cli-live"
   :backend :live
   :std :live
   :standard {:types #{"WCHNTMaths" "WCHNTConsole"}
              :bindings [{:name "wchntConsole" :host-key :console}
                         {:name "wchntMaths" :host-key :maths}]}
   :parse-target core/parse-target})
