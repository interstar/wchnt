(ns wchnt-lang.targets.canvas
  (:require [wchnt-lang.targets.core :as core]
            [wchnt-lang.targets.live-std :as live-std]))

(def plugin
  {:name "canvas"
   :backend :live
   :std :live
   :provided-types #{"WCHNTMaths" "WCHNTConsole" "WCHNTGraphics"}
   :parse-target core/parse-target})
