(ns wchnt-lang.targets.cli-live
  (:require [wchnt-lang.targets.core :as core]
            [wchnt-lang.targets.live-std :as live-std]))

(def plugin
  {:name "cli-live"
   :backend :live
   :std :live
   :provided-types #{"WCHNTMaths" "WCHNTConsole"}
   :parse-target core/parse-target})
