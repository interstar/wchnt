(ns wchnt-lang.api
  (:require [wchnt-lang.core :as core]
            [wchnt-lang.parser :as parser]
            [wchnt-lang.grammars :as grammars]
            [wchnt-lang.compiler :as compiler]
            [wchnt-lang.schema :as schema]
            [instaparse.core :as instaparse])
  (:import (java.util ArrayList HashMap)
           (java.util.function Function))
  (:gen-class
    :name wchnt_lang.WchntAPI
    :impl-ns wchnt-lang.api
    :methods [[compileToHaxe [String] java.util.List]
              [eyeball [String] String]
              [getSchemaParser [] java.util.function.Function]
              [getConstructionParser [String] java.util.function.Function]
              [getSchemaGrammarAsString [] String]
              [getConstructionGrammarAsString [String] String]]))

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
  (let [result (compiler/compile input)]
    (if (:success result)
      (let [value (:value result)
            classes (:classes value)
            factory (:factory value)
            main (:main value)
            ;; Combine into a single Haxe program
            haxe-program (str classes "\n\n" factory "\n\n" main)]
        (ArrayList. [haxe-program]))
      (ArrayList. [(pr-str result)]))))

(defn -eyeball [this ^String code]
  (try
    (let [result (core/eyeball code)]
      (when-not (schema/valid-eyeball-result? result)
        (throw (ex-info "eyeball: result does not conform to EyeballResult schema" {:result result})))
      (if (map? result)
        (pr-str result)
        (pr-str {:status "issues"
                 :issues ["Failed to validate code with WCHNT library"]
                 :notes "Error during eyeball validation"})))
    (catch Exception e
      (pr-str {:status "issues"
               :issues [(str "Eyeball validation failed: " (.getMessage e))]
               :notes "Error during WCHNT eyeball validation"}))))

(defn -getSchemaParser [this]
  (try
    (let [parser-fn (parser/get-schema-parser)]
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

(defn create-error-function [error-message]
  "Create a Function that always returns an error"
  (reify Function
    (apply [this input] 
      (let [error-map (HashMap.)]
        (.put error-map "success" false)
        (.put error-map "error" error-message)
        error-map))))

(defn create-parser-function [parser-fn]
  "Create a Function that uses the given parser function"
  (reify Function
    (apply [this input]
      (try
        (let [result (parser-fn input)]
          ;; TODO: If we want to validate the parse result, add a schema for construction ASTs
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

(defn -getConstructionParser [this ^String schema-input]
  (try
    (let [parser-fn parser/parse-construction-unified]
          (create-parser-function parser-fn))
    (catch Exception e
      (create-error-function (.getMessage e)))))

(defn -getSchemaGrammarAsString [this]
  (try
    grammars/schema-grammar
    (catch Exception e
      (str "Error getting schema grammar: " (.getMessage e)))))

(defn -getConstructionGrammarAsString [this ^String schema-input]
  (try
    grammars/construction-grammar
    (catch Exception e
      (str "Error getting construction grammar: " (.getMessage e)))))

 