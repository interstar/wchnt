(ns wchnt-lang.core
  (:require [wchnt-lang.parser :as parser]
            [wchnt-lang.haxegen :as haxe-gen]
            [wchnt-lang.eyeball :as eyeball]
            [wchnt-lang.mainfile :as mainfile]
            [wchnt-lang.schema :as schema]
            [wchnt-lang.compiler :as compiler]
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

;; Printing functions for different output modes

(defn print-complete-haxe-file
  "Print the complete Haxe file body (classes + factory + main function)"
  [haxe-file-content]
  (println haxe-file-content))

(defn print-verbose-compilation
  "Print verbose compilation details showing each phase"
  [schema-content construction-content schema-result factory-result]
  (println "## Schema")
  (println "```")
  (println schema-content)
  (println "```")
  (println)
  (println "## Generated Haxe Classes")
  (doseq [class (:code schema-result)] (println class))
  (when (not-empty construction-content)
    (do
      (println)
      (println "## Construction")
      (println "```")
      (println construction-content)
      (println "```")
      (println)
      (println "## Factory Function")
      (println (:code factory-result))))
  (println)
  (println "## Main Function")
  (println "public static function main() {")
  (println "    return factory();")
  (println "}"))

(defn print-compilation-error
  "Print detailed error information when compilation fails"
  [error-message phase input-content]
  (println "Compilation failed during" phase "phase:")
  (println error-message)
  (when input-content
    (println)
    (println "Input that caused the error:")
    (println "```")
    (println input-content)
    (println "```")))

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
          (let [mainfile-result (process-mainfile filename)]
            (if (:success mainfile-result)
              (let [schema-content (:schema mainfile-result)
                    construction-content (:construction mainfile-result)]
                (if verbose?
                  ;; Verbose mode: show each phase
                  (let [schema-result (compiler/compile-schema-phase schema-content)]
                    (if (:success schema-result)
                      (if (not-empty construction-content)
                        (let [factory-result (compiler/compile-construction-phase schema-content construction-content)]
                          (if (:success factory-result)
                            (print-verbose-compilation schema-content construction-content schema-result factory-result)
                            (do
                              (print-compilation-error (:error factory-result) "construction" construction-content)
                              (System/exit 1))))
                        (print-verbose-compilation schema-content "" schema-result {:code "// No construction phase" :success true}))
                      (do
                        (print-compilation-error (:error schema-result) "schema" schema-content)
                        (System/exit 1))))
                  ;; Default mode: show only complete Haxe file
                  (let [complete-result (compiler/compile-complete-program schema-content construction-content)]
                    (if (:success complete-result)
                      (print-complete-haxe-file (:code complete-result))
                      (do
                        (print-compilation-error (:error complete-result) "compilation" (str "Schema:\n" schema-content "\n\nConstruction:\n" construction-content))
                        (System/exit 1))))))
              (do
                (print-compilation-error (:error mainfile-result) "file parsing" filename)
                (System/exit 1)))))))))
