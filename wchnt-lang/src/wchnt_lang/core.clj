(ns wchnt-lang.core
  (:require [wchnt-lang.parser :as parser]
            [wchnt-lang.haxegen :as haxe-gen]
            [wchnt-lang.eyeball :as eyeball]
            [wchnt-lang.schema :as schema]
            [wchnt-lang.examples :as examples]
            [clojure.string :as str]))

;; Public API for library usage

(defn get-parser
  "Returns the Instaparse parser for WCHNT DSL.
   
   Returns:
     Instaparse parser instance"
  []
  (parser/get-parser))

(defn compile-to-haxe
  "Compile WCHNT DSL input to Haxe classes.
   
   Args:
     input - String containing WCHNT DSL definitions
   
   Returns:
     Map with :success boolean and either :code vector of strings (generated classes)
     or :error string (if compilation failed)"
  [input]
  (haxe-gen/compile-to-haxe input))

(defn eyeball
  "Validate generated Haxe code for common issues.
   
   Args:
     code - String containing generated Haxe code
   
   Returns:
     Map with :status string ('seems ok' or 'issues') and :issues vector of strings"
  [code]
  (eyeball/haxe-eyeball code))

;; Additional utility functions

(defn validate-wchnt-syntax
  "Validate WCHNT DSL syntax.
   
   Args:
     input - String containing WCHNT DSL definitions
   
   Returns:
     Map with :success boolean and either :ast parsed tree (if successful) 
     or :error string (if failed)"
  [input]
  (parser/parse-input input))

(defn compile-and-validate
  "Compile WCHNT to Haxe and validate the output.
   
   Args:
     input - String containing WCHNT DSL definitions
   
   Returns:
     Map with compilation result and validation result"
  [input]
  (let [compilation-result (compile-to-haxe input)]
    (if (:success compilation-result)
      (let [validation-result (eyeball (str/join "\n\n" (:code compilation-result)))]
        (assoc compilation-result :validation validation-result))
      compilation-result)))

;; Command-line interface

(defn -main [& args]
  ;; This ensures the gen-class is loaded
  (require 'wchnt-lang.api)
  (if (empty? args)
    (do
      (println "Usage: lein run <wchnt-source-file>")
      (System/exit 1))
    (let [filename (first args)]
      (if-not (.exists (clojure.java.io/file filename))
        (do
          (println (str "File not found: " filename))
          (System/exit 1))
        (let [input (slurp filename)
              result (compile-to-haxe input)]
          (if (:success result)
            (doseq [class (:code result)] (println class) (println))
            (do
              (println "Compilation failed:")
              (println (:error result))
              (System/exit 1)))))))) 