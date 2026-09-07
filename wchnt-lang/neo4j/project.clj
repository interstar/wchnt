(defproject wchnt-neo4j "0.1.0"
  :description "Generate Neo4j upsert functions from WCHNT schema"
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [instaparse "1.4.12"]
                 [org.clojure/data.json "2.5.0"]]
  :main neo4j.generate
  :aot [neo4j.generate]
  :uberjar-name "wchnt-neo4j.jar"
  :target-path "target/%s")
