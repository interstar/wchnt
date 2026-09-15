(ns wchnt-java-analysis.core
  (:gen-class)
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [wchnt-java-analysis.parser :as parser]
            [wchnt-java-analysis.report :as report]))

(defn usage [] (println "Usage: lein run -- SOURCE-DIR [-o OUTPUT.md]\n       lein run -- --help"))
(defn parse-args [args]
  (loop [args args result {}]
    (if (empty? args) result
        (let [[arg & more] args]
          (cond
            (= arg "--help") (assoc result :help true)
            (= arg "-o") (recur (rest more) (assoc result :output (first more)))
            (= arg "--output") (recur (rest more) (assoc result :output (first more)))
            (str/starts-with? arg "-") (throw (ex-info (str "Unknown option: " arg) {}))
            (:source result) (throw (ex-info "Only one source directory is allowed" {}))
            :else (recur more (assoc result :source arg)))))))
(defn -main [& args]
  (try
    (let [{:keys [help source output]} (parse-args args)]
      (if help
        (usage)
        (do
          (when-not source (throw (ex-info "Missing Java source directory" {})))
          (when-not (.isDirectory (io/file source)) (throw (ex-info (str "Not a directory: " source) {})))
          (spit (or output "java-assemblage.md") (report/render (parser/parse-tree source)))
          (println (str "Wrote " (or output "java-assemblage.md"))))))
    (catch Exception e
      (binding [*out* *err*] (println (str "Error: " (.getMessage e))))
      (usage)
      (System/exit 1))))
