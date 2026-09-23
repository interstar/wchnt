(ns wchnt-lang.targets.plugins
  "Dispatch Target sections to platform plugins."
  (:require [clojure.string :as str]
            [wchnt-lang.targets.terminal :as terminal]
            [wchnt-lang.targets.cli :as cli]
            [wchnt-lang.targets.openfl :as openfl]
            [wchnt-lang.targets.canvas :as canvas]
            [wchnt-lang.targets.form :as form]
            [wchnt-lang.targets.cli-live :as cli-live]
            [wchnt-lang.targets.testharness :as testharness]
            [wchnt-lang.targets.testharness-live :as testharness-live]
            [wchnt-lang.targets.core :as core]
            [wchnt-lang.targets.haxe :as haxe]
            [wchnt-lang.targets.stdlib-signatures :as stdlib]))

(def plugins
  {"terminal" terminal/plugin
   "cli" cli/plugin
   "openfl" openfl/plugin
   "canvas" canvas/plugin
   "form" form/plugin
   "cli-live" cli-live/plugin
   "testharness" testharness/plugin
   "testharness-live" testharness-live/plugin})

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
          parsed ((:parse-target plugin) text)
          standard-types (get-in plugin [:standard :types])
          standard-classes (select-keys stdlib/signatures standard-types)
          standard-requires
          {:classes
           (into {}
                 (map (fn [[class-name methods]]
                        [class-name
                         {:methods
                          (into {}
                                (map (fn [[method-name spec]]
                                       [method-name
                                        (mapv (fn [overload]
                                                {:args (:args overload)
                                                 :arg-types (:args overload)
                                                 :return (or (:return overload)
                                                              (:return spec))})
                                              (if-let [overloads (:overloads spec)]
                                                overloads
                                                [spec]))])
                                     methods))}])
                      standard-classes))}
          requires-ir (:requires parsed)
          merged-requires
          (update requires-ir :classes
                  (fn [classes]
                    (reduce-kv
                     (fn [result class-name standard-class]
                       (update result class-name
                               #(merge-with merge (or %) standard-class)))
                     classes
                     (:classes standard-requires))))]
      (assoc parsed
             :requires merged-requires
             :plugin plugin
             :platform (:name plugin)
             :external-types (into (or (:external-types parsed) #{})
                                   standard-types)))))

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
