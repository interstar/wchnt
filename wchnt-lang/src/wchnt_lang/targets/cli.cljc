(ns wchnt-lang.targets.cli
  (:require [wchnt-lang.targets.core :as core]
            [wchnt-lang.targets.haxe :as haxe]))

(def plugin
  {:name "cli"
   :backend :haxe
   :std :haxe
   :provided-types #{"WCHNTMaths" "WCHNTConsole"}
   :parse-target core/parse-target
   :emit haxe/emit-program})
