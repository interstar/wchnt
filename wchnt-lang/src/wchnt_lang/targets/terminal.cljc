(ns wchnt-lang.targets.terminal
  (:require [wchnt-lang.targets.core :as core]
            [wchnt-lang.targets.haxe :as haxe]))

(def plugin
  {:name "terminal"
   :backend :haxe
   :std :haxe
   :standard {:types #{"WCHNTMaths" "WCHNTConsole"}}
   :parse-target core/parse-target
   :emit haxe/emit-program})
