(ns wchnt-lang.targets.plugins
  "Dispatch Target sections to platform plugins."
  (:require [clojure.string :as str]
            [wchnt-lang.targets.terminal :as terminal]
            [wchnt-lang.targets.cli :as cli]
            [wchnt-lang.targets.openfl :as openfl]
            [wchnt-lang.targets.canvas :as canvas]
            [wchnt-lang.targets.cli-live :as cli-live]
            [wchnt-lang.targets.core :as core]
            [wchnt-lang.targets.haxe :as haxe]))

(def plugins
  {"terminal" terminal/plugin
   "cli" cli/plugin
   "openfl" openfl/plugin
   "canvas" canvas/plugin
   "cli-live" cli-live/plugin})

(defn- first-target-name
  [text]
  (some (fn [line]
          (second (re-matches #"^[ \\t]*%([A-Za-z_][A-Za-z0-9_-]*)[ \\t]*$" line)))
        (str/split-lines (or text ""))))

(defn plugin-for
  [text]
  (let [name (first-target-name text)]
    (or (get plugins name)
        (throw (ex-info (str "Unknown Target platform '" (or name "") "'")
                        {:target name
                         :known (sort (keys plugins))})))))

(defn parse-target
  [text]
  (if (str/blank? (or text ""))
    (core/parse-target text)
    (let [plugin (plugin-for text)
          parsed ((:parse-target plugin) text)]
      (assoc parsed
             :plugin plugin
             :platform (:name plugin)
             :external-types (into (or (:external-types parsed) #{})
                                   (:provided-types plugin))))))

(defn emit
  "Delegate final target emission to the selected platform plugin."
  [cargo]
  (let [target-ir (get-in cargo [:stash :target-ir])
        plugin (:plugin target-ir)]
    (if-let [emit-fn (:emit plugin)]
      (emit-fn cargo)
      (if (nil? plugin)
        (haxe/emit-program cargo)
        (throw (ex-info (str "Target '" (:host target-ir)
                             "' has no Haxe emitter (Haxe cannot emit %"
                             (:host target-ir) ")")
                        {:host (:host target-ir)
                         :backend (:backend plugin)}))))))
