(ns wchnt-lang.core
  (:require [wchnt-lang.parser :as parser]
            [wchnt-lang.haxegen :as haxe-gen]
            [wchnt-lang.eyeball :as eyeball]
            [wchnt-lang.mainfile :as mainfile]
            [wchnt-lang.schema :as schema]
            [wchnt-lang.compiler :as compiler]
            [clojure.string :as str]
            [clojure.pprint :as pp]
            [clojure.java.io :as io]
            [wchnt-lang.pipeline :as p]))

;; Public API for library usage

(defn get-schema-parser
  "Returns the Instaparse parser for WCHNT Schema Language.
   
   Returns:
     Instaparse parser instance for schema parsing"
  []
  (parser/get-schema-parser))



(defn compile-wchnt-file
  "Compile a WCHNT file using the new pipeline structure.
   
   Args:
     file-path - Path to the WCHNT file
   
   Returns: FullProgramOrFail"
  [file-path]
  (try
    (let [file-content (slurp file-path)
          result (compiler/compile file-content)]
      (schema/valid-full-program-or-fail? result)
      )))

(defn eyeball
  "Validate generated Haxe code for common issues.
   
   Args:
     code - String containing generated Haxe code
   
   Returns:
     Map with :status string ('seems ok' or 'issues') and :issues vector of strings"
  [code]
  (eyeball/haxe-eyeball code))



;; Command-line interface

(defn -main [& args]
  (let [args (vec args)]
    (if (empty? args)
      (do
        (println "Usage: lein run [--verbose] <wchnt-source-file>")
        (System/exit 1))
      (let [verbose? (some #{"--verbose"} args)
            filename (if verbose? (second args) (first args))]
        (if-not (.exists (io/file filename))
          (do
            (println (str "File not found: " filename))
            (System/exit 1))
          (let [result (compiler/compile-file filename)]
            (if (and
                 (schema/valid-full-program-or-fail? result)
                 (:success result))
              (do
                (println (:classes result))
                (println (:factory result))
                (println (:main result))                
                (if verbose?                
                  (pp/pprint result))) 
              (do
                (println
                 (:error result) "file parsing" filename)
                (System/exit 1)))))))))
