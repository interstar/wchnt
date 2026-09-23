(ns wchnt-lang.targets.testharness
  "Unit-test host (Haxe): embed many constructions in Target via %with / %assert."
  (:require [wchnt-lang.targets.testharness-parse :as parse]
            [wchnt-lang.targets.testharness-emit :as emit]))

(defn- parse-target
  [text]
  (parse/parse-target text "testharness"))

(def plugin
  {:name "testharness"
   :backend :haxe
   :std :haxe
   :standard {:types #{"WCHNTUnitTests"}}
   :parse-target parse-target
   :emit emit/emit-program})
