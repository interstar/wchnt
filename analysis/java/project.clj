(defproject wchnt-java-analysis "0.1.0-SNAPSHOT"
  :description "Build assemblage-oriented reference material from Java source trees"
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [com.github.javaparser/javaparser-core "3.26.2"]]
  :main wchnt-java-analysis.core
  :aot [wchnt-java-analysis.core]
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
