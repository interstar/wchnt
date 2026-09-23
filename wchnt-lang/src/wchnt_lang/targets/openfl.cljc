(ns wchnt-lang.targets.openfl
  (:require [wchnt-lang.targets.core :as core]
            [wchnt-lang.targets.haxe :as haxe]))

(def plugin
  {:name "openfl"
   :backend :haxe
   :std :haxe
   :standard {:types #{"WCHNTMaths" "WCHNTConsole" "WCHNTGraphics"}}
   :parse-target core/parse-target
   :emit haxe/emit-program})
