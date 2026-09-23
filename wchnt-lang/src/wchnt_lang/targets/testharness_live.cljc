(ns wchnt-lang.targets.testharness-live
  "Unit-test host (live interpreter): same %with / %assert suite as %testharness."
  (:require [wchnt-lang.targets.testharness-parse :as parse]))

(defn- parse-target
  [text]
  (parse/parse-target text "testharness-live"))

(def plugin
  {:name "testharness-live"
   :backend :live
   :std :live
   :standard {:types #{"WCHNTUnitTests"}}
   :parse-target parse-target})
