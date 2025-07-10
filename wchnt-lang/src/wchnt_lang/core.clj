(ns wchnt-lang.core
  (:require [wchnt-lang.parser :as parser]
            [wchnt-lang.haxegen :as haxe-gen]
            [wchnt-lang.eyeball :as eyeball]
            [wchnt-lang.mainfile :as mainfile]
            [wchnt-lang.schema :as schema]
            [clojure.string :as str]
            [clojure.java.io :as io]))

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

;; Mainfile processing functions

(defn process-mainfile
  "Process a WCHNT mainfile (markdown with embedded code blocks).
   
   Args:
     file-path - Path to the WCHNT mainfile
   
   Returns:
     Map with :success boolean and either parsed sections or :error string"
  [file-path]
  (let [parse-result (mainfile/read-mainfile file-path)]
    (if (:success parse-result)
      (do
        ;; Validate the parse result against our schema
        (when-not (schema/valid-mainfile-parse-result? parse-result)
          (throw (ex-info "Invalid mainfile parse result" {:result parse-result})))
        parse-result)
      parse-result)))

;; Command-line interface

(defn -main [& args]
  ;; This ensures the gen-class is loaded
  (require 'wchnt-lang.api)
  (if (empty? args)
    (do
      (println "Usage: lein run <wchnt-source-file>")
      (System/exit 1))
    (let [filename (first args)]
      (if-not (.exists (io/file filename))
        (do
          (println (str "File not found: " filename))
          (System/exit 1))
        (let [mainfile-result (process-mainfile filename)]
          (if (:success mainfile-result)
            (let [schema-content (:schema mainfile-result)
                  construction-content (:construction mainfile-result)
                  parse-result (parser/parse-input schema-content)]
              (if (:success parse-result)
                (let [result (compile-to-haxe schema-content)
                      context-relationships (haxe-gen/build-context-relationships (:ast parse-result))]
          (if (:success result)
                    (if (not-empty construction-content)
                      (let [factory-result (haxe-gen/generate-construction-factory schema-content construction-content context-relationships)]
                  (if (:success factory-result)
                    (do
                            (doseq [class (:ast result)] (println class))
                            (println (:haxe-code factory-result)))
                          (do
                            (println "Construction parsing failed:")
                            (println (:error factory-result))
                            (System/exit 1))))
                      ;; Only schema phase present, print schema Haxe code
                      (doseq [class (:ast result)] (println class)))
                    (do
                      (println "Schema compilation failed:")
                      (println (:error result))
                      (System/exit 1))))
                    (do
                  (println "Schema parsing failed:")
                  (println "Input:" schema-content)
                  (println "Error:" (:error parse-result))
                  (System/exit 1))))
            (do
              (println "Mainfile parsing failed:")
              (println (:error mainfile-result))
              (System/exit 1)))))))) 