#!/usr/bin/env bb

(ns project-lint.standard-library
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.java.shell :as shell]))

(def project-root
  (-> *file* java.io.File. .getParentFile .getParentFile .getCanonicalPath))

(def haxe-source
  (slurp (str project-root "/src/wchnt_lang/targets/haxe_std.cljc")))

(def live-source
  (slurp (str project-root "/live/public/harness.js")))

(def smalltalk-source
  (let [{:keys [exit out err]}
        (shell/sh "lein" "run" "smalltalk-examples/bounce.wcn" :dir project-root)]
    (when-not (zero? exit)
      (throw (ex-info (str "Could not compile Smalltalk stdlib probe: " err)
                      {:exit exit})))
    out))

(def signatures
  (deref (load-file (str project-root
                         "/src/wchnt_lang/targets/stdlib_signatures.cljc"))))

(def cross-target-classes
  (map (fn [[name factory]]
         {:name name :live-factory factory})
       (select-keys {"WCHNTMaths" "makeMaths"
                     "WCHNTConsole" "makeConsole"
                     "WCHNTGraphics" "makeGraphics"
                     "WCHNTInput" "makeInput"}
                    (keys signatures))))

(def live-only-classes
  (map (fn [[name factory]]
         {:name name :live-factory factory})
       (select-keys {"WCHNTForm" "makeForm"}
                    (keys signatures))))

(def smalltalk-classes
  (select-keys signatures ["WCHNTMaths" "WCHNTInput" "WCHNTGraphics"]))

(defn matching-brace
  [source opening]
  (loop [index opening
         depth 1]
    (when (< index (count source))
      (let [character (.charAt source index)
            next-depth (cond
                         (= character \{) (inc depth)
                         (= character \}) (dec depth)
                         :else depth)]
        (if (and (= character \}) (zero? next-depth))
          index
          (recur (inc index) next-depth))))))

(defn haxe-class-body
  [class-name]
  (let [class-marker (str "class " class-name " {")
        class-start (.indexOf haxe-source class-marker)]
    (when (neg? class-start)
      (throw (ex-info (str "Haxe standard class not found: " class-name)
                      {:class-name class-name})))
    (let [opening (+ class-start (count class-marker))
          closing (matching-brace haxe-source opening)]
      (when-not closing
        (throw (ex-info (str "Unclosed Haxe standard class: " class-name)
                        {:class-name class-name})))
      (subs haxe-source opening closing))))

(defn haxe-methods
  [class-name]
  (->> (re-seq #"public(?:\s+(?:static|inline))*\s+function\s+([A-Za-z][A-Za-z0-9_]*)"
               (haxe-class-body class-name))
       (map second)
       (remove #{"new"})
       set))

(defn live-methods
  [factory-name]
  (let [factory-marker (str "function " factory-name "(")
        factory-start (.indexOf live-source factory-marker)]
    (when (neg? factory-start)
      (throw (ex-info (str "Live standard factory not found: " factory-name)
                      {:factory-name factory-name})))
    (let [tail (subs live-source (+ factory-start 1))
          next-factory (re-find #"(?m)^  function [A-Za-z][A-Za-z0-9_]*\("
                                tail)
          next-start (when next-factory
                       (+ factory-start 1 (.indexOf tail next-factory)))]
      (let [body (if next-factory
                   (subs live-source factory-start next-start)
                   (subs live-source factory-start))
            methods (->> (re-seq #"(?m)^\s{6}([A-Za-z_$][A-Za-z0-9_$]*):\s*function\s*\("
                                 body)
                          (map second)
                          set)
            aliases (->> (re-seq #"(?m)^\s{6}([A-Za-z_$][A-Za-z0-9_$]*):\s*format\s*,?"
                                 body)
                         (map second)
                         set)]
        (into methods aliases)))))

(defn smalltalk-method-arities
  [class-name]
  (->> (re-seq #"(?m)!([A-Za-z][A-Za-z0-9]*) methodsFor: 'WCHNT'[^!]*!\n([^\n]+)"
                smalltalk-source)
       (keep (fn [[_ emitted-class selector]]
               (when (= emitted-class class-name)
                 (when-let [[_ method-name]
                            (re-find #"^([A-Za-z][A-Za-z0-9]*)" selector)]
                   [method-name (count (re-seq #":" selector))]))))
       set))

(defn expected-method-arities
  [methods]
  (into #{}
        (mapcat (fn [[method-name {:keys [args overloads]}]]
                  (map (fn [arity] [method-name arity])
                       (if overloads
                         (map (comp count :args) overloads)
                         [(count args)])))
                methods)))

(defn check-smalltalk-class
  [[class-name methods]]
  (let [expected (expected-method-arities methods)
        emitted (smalltalk-method-arities class-name)
        missing (set/difference expected emitted)]
    (when (seq missing)
      {:class-name class-name :missing-from-smalltalk missing})))

(defn sorted-names
  [names]
  (str/join ", " (sort names)))

(defn check-class
  [{:keys [name live-factory]}]
  (let [expected (set (keys (get signatures name)))
        haxe (haxe-methods name)
        live (live-methods live-factory)
        missing-from-haxe (set/difference expected haxe)
        missing-from-live (set/difference expected live)
        unexpected-in-haxe (set/difference haxe expected)
        unexpected-in-live (set/difference live expected)]
    (if (or (seq missing-from-haxe) (seq missing-from-live)
            (seq unexpected-in-haxe) (seq unexpected-in-live))
      {:class-name name
       :missing-from-haxe missing-from-haxe
       :missing-from-live missing-from-live
       :unexpected-in-haxe unexpected-in-haxe
       :unexpected-in-live unexpected-in-live}
      (do
        (println (str "OK standard library: " name " (" (count expected) " methods)"))
        nil))))

(defn check-live-only
  [{:keys [name live-factory]}]
  (let [methods (live-methods live-factory)]
    (if (seq methods)
      (println (str "OK live-only standard library: " name
                    " (" (count methods) " methods)"))
      {:class-name name :error "no live methods found"})))

(defn report-error
  [{:keys [class-name missing-from-haxe missing-from-live missing-from-smalltalk
           unexpected-in-haxe unexpected-in-live error]}]
  (if error
    (println (str "ERROR " class-name ": " error))
    (do
      (when (seq missing-from-haxe)
        (println (str "ERROR " class-name " methods missing from Haxe: "
                      (sorted-names missing-from-haxe))))
      (when (seq missing-from-live)
        (println (str "ERROR " class-name " methods missing from live: "
                      (sorted-names missing-from-live))))
      (when (seq missing-from-smalltalk)
        (println (str "ERROR " class-name " method/arity pairs missing from Smalltalk: "
                      (str/join ", " (sort (map (fn [[method arity]]
                                                   (str method "/" arity))
                                                 missing-from-smalltalk))))))
      (when (seq unexpected-in-haxe)
        (println (str "ERROR " class-name " unexpected Haxe methods: "
                      (sorted-names unexpected-in-haxe))))
      (when (seq unexpected-in-live)
        (println (str "ERROR " class-name " unexpected live methods: "
                      (sorted-names unexpected-in-live)))))))

(let [errors (concat (keep check-class cross-target-classes)
                     (keep check-smalltalk-class smalltalk-classes)
                     (keep check-live-only live-only-classes))]
  (when (seq errors)
    (doseq [error errors]
      (report-error error))
    (System/exit 1)))
