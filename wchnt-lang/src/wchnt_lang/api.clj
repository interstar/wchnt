(ns wchnt-lang.api
  (:require [wchnt-lang.core :as core]
            [wchnt-lang.parser :as parser]
            [wchnt-lang.compiler :as compiler]
            [instaparse.core :as instaparse])
  (:import (java.util ArrayList HashMap)
           (java.util.function Function))
  (:gen-class
    :name wchnt_lang.WchntAPI
    :impl-ns wchnt-lang.api
    :methods [[compileToHaxe [String] java.util.List]
              [eyeball [String] String]
              [getParser [] java.util.function.Function]
              [grammarAsString [] String]]))

(defn clojure-to-java-tree [clojure-data]
  (cond
    (vector? clojure-data)
    (let [java-list (ArrayList.)]
      (doseq [item clojure-data]
        (.add java-list (clojure-to-java-tree item)))
      java-list)
    (keyword? clojure-data)
    (name clojure-data)
    (seq? clojure-data)
    (let [java-list (ArrayList.)]
      (doseq [item clojure-data]
        (.add java-list (clojure-to-java-tree item)))
      java-list)
    :else
    clojure-data))

(defn -compileToHaxe [this ^String input]
  (try
    (let [result (core/compile-to-haxe input)]
      (if (and (map? result) (:success result))
        (ArrayList. (:code result))
        (ArrayList. [""])))
    (catch Exception e
      (ArrayList. [""]))))

(defn -eyeball [this ^String code]
  (try
    (let [result (core/eyeball code)]
      (if (map? result)
        (pr-str result)
        (pr-str {:status "issues"
                 :issues ["Failed to validate code with WCHNT library"]
                 :notes "Error during eyeball validation"})))
    (catch Exception e
      (pr-str {:status "issues"
               :issues [(str "Eyeball validation failed: " (.getMessage e))]
               :notes "Error during WCHNT eyeball validation"}))))

(defn -getParser [this]
  (try
    (let [parser-fn (core/get-parser)]
      (reify Function
        (apply [this input]
          (try
            (let [result (parser-fn input)]
              (if (instaparse.core/failure? result)
                (let [error-map (HashMap.)]
                  (.put error-map "success" false)
                  (.put error-map "error" (str (instaparse.core/get-failure result)))
                  error-map)
                (let [success-map (HashMap.)]
                  (.put success-map "success" true)
                  (.put success-map "ast" (clojure-to-java-tree result))
                  success-map)))
            (catch Exception e
              (let [error-map (HashMap.)]
                (.put error-map "success" false)
                (.put error-map "error" (.getMessage e))
                error-map))))))
    (catch Exception e
      (reify Function
        (apply [this input] 
          (let [error-map (HashMap.)]
            (.put error-map "success" false)
            (.put error-map "error" (.getMessage e))
            error-map))))))

(defn -grammarAsString [this]
  (try
    parser/schema-grammar
    (catch Exception e
      (str "Error getting grammar: " (.getMessage e)))))

 