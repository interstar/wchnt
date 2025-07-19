(ns wchnt-lang.eyeball
  (:require [clojure.string :as str]
            [wchnt-lang.schema :as schema]))

(defn haxe-eyeball [code]
  (let [issues (cond-> []
                 (not (str/includes? code "class"))
                 (conj "Missing class declarations")
                 (not (str/includes? code "public var"))
                 (conj "Missing public field declarations")
                 (not (str/includes? code "public function new"))
                 (conj "Missing constructor")
                 (not (str/includes? code "this."))
                 (conj "Missing field assignments in constructor")
                 (str/includes? code "set")
                 (conj "Classes should be immutable - no setters allowed")
                 (str/includes? code "private")
                 (conj "All fields should be public"))]
    (if (empty? issues)
      (schema/eyeball-ok)
      (schema/eyeball-issues issues)))) 