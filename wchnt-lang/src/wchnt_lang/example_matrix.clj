(ns wchnt-lang.example-matrix
  "Compile examples and report which compiler stages are currently working."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [wchnt-lang.compiler :as compiler]
            [wchnt-lang.pages :as pages]))

(defn- wcn-files
  [dir]
  (->> (file-seq (io/file dir))
       (filter #(.isFile %))
       (filter #(str/ends-with? (.getName %) ".wcn"))
       (sort-by #(.getPath %))))

(defn- present?
  [x]
  (and x (not (and (string? x) (str/blank? x)))))

(defn- status
  [ok?]
  (if ok? "ok" "fail"))

(defn- construction-status
  [codeblocks stash]
  (if (str/blank? (:construction codeblocks ""))
    "n/a"
    (status (:construction-ir stash))))

(defn analyze-file
  [file]
  (let [parent (.getParent file)
        opts (if parent {:resolve-page (pages/sibling-resolve parent)} {})
        result (compiler/compile (slurp file) opts)
        stash (:stash result)
        codeblocks (or (:codeblocks stash) {})
        value (:value result)]
    {:file (.getPath file)
     :success (:success result)
     :schema (status (:schema-ir stash))
     :construction (construction-status codeblocks stash)
     :haxe (status (and (:schema-haxe stash)
                        (or (str/blank? (:construction codeblocks ""))
                            (:construction-haxe stash))))
     :factory (if (str/blank? (:construction codeblocks ""))
                "n/a"
                (status (present? (:factory value))))
     :error (first (:errors result))}))

(defn analyze-dir
  ([] (analyze-dir "examples"))
  ([dir]
   (mapv analyze-file (wcn-files dir))))

(defn markdown-table
  [rows]
  (let [header "| example | success | schema | construction | haxe | factory | error |\n|---|---:|---:|---:|---:|---:|---|"
        lines (for [{:keys [file success schema construction haxe factory error]} rows]
                (str "| " file
                     " | " (status success)
                     " | " schema
                     " | " construction
                     " | " haxe
                     " | " factory
                     " | " (or error "")
                     " |"))]
    (str/join "\n" (cons header lines))))

(defn -main
  [& [dir]]
  (println (markdown-table (analyze-dir (or dir "examples")))))
