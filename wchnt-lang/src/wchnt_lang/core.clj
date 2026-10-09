(ns wchnt-lang.core
  (:require [wchnt-lang.parser :as parser]
            [wchnt-lang.eyeball :as eyeball]
            [wchnt-lang.mainfile :as mainfile]
            [wchnt-lang.schema :as schema]
            [wchnt-lang.compiler :as compiler]
            [wchnt-lang.pages :as pages]
            [clojure.string :as str]
            [clojure.pprint :as pp]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [wchnt-lang.pipeline :as p]))

;; Public API for library usage

(defn get-schema-parser
  "Returns the Instaparse parser for WCHNT Schema Language.
   
   Returns:
     Instaparse parser instance for schema parsing"
  []
  (parser/get-schema-parser))



(defn- compile-opts-for-file
  "Resolve [[page]] imports against sibling .wcn files in the same directory."
  [file]
  (let [parent (.getParent (io/file file))]
    (if parent
      {:resolve-page (pages/sibling-resolve parent)}
      {})))

(defn compile-wchnt-file
  "Compile a WCHNT file.
   
   Args:
     file-path - Path to the WCHNT file
   
   Returns: FullProgramOrFail"
  [file-path]
  (try
    (let [file-content (slurp file-path)
          result (compiler/compile file-content (compile-opts-for-file file-path))]
      result)))

(defn compile-file
  "Compile a WCHNT file and return the result"
  [file-path]
  (let [file (io/file file-path)
        file-content (slurp file)
        result (compiler/compile file-content (compile-opts-for-file file))]
    result))

(defn eyeball
  "Validate generated Haxe code for common issues.
   
   Args:
     code - String containing generated Haxe code
   
   Returns:
     Map with :status string ('seems ok' or 'issues') and :issues vector of strings"
  [code]
  (eyeball/haxe-eyeball code))



;; Command-line interface

(defn compiler-info
  "Return stable, backend-neutral metadata for a successfully compiled artifact."
  [artifact]
  {:format "wchnt-compiler-info"
   :version 1
   :success true
   :target (:target artifact)
   :backend (some-> (:backend artifact) name)
   :pageKind (some-> (:page-kind artifact) name)
   :outputs (mapv (fn [output]
                    {:kind (name (:kind output))
                     :name (:name output)})
                  (:outputs artifact))
   :warnings (vec (:warnings artifact))})

(defn -main [& args]
  (let [args (vec args)]
    (let [info? (= "--info" (first args))
          verbose? (some #{"--verbose"} args)
          filename (first (remove #{"--info" "--verbose"} args))]
      (cond
        (nil? filename)
        (do
          (binding [*out* *err*]
            (println "Usage: lein run [--info|--verbose] <wchnt-source-file>"))
          (System/exit 1))

        (not (.exists (io/file filename)))
        (if info?
          (do
            (println (json/write-str {:format "wchnt-compiler-info"
                                      :version 1
                                      :success false
                                      :errors [(str "File not found: " filename)]}))
            (flush)
            (System/exit 1))
          (do
            (binding [*out* *err*]
              (println (str "File not found: " filename)))
            (System/exit 1)))

        :else
        (let [result (compile-file filename)
              success? (and (p/is-cargo? result) (:success result))]
          (if success?
            (let [artifact (:value result)]
              (if info?
                (println (json/write-str (compiler-info artifact)))
                (do
                  (println (some-> artifact :outputs first :content))
                  (when verbose?
                    (pp/pprint result)))))
            (if info?
              (do
                (println (json/write-str {:format "wchnt-compiler-info"
                                          :version 1
                                          :success false
                                          :errors (vec (:errors result))}))
                (flush)
                (System/exit 1))
              (do
                (binding [*out* *err*]
                  (println (first (:errors result)) "file parsing" filename))
                (println result)
                (System/exit 1)))))))))
