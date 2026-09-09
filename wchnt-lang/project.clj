(defproject wchnt-lang "0.1.0-SNAPSHOT"
  :description "WCHNT (We CAN Have Nice Things) - A new object-oriented language for making coding easy and fun"
  :url "https://github.com/your-username/wchnt-lang"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.11.1"]
                 [instaparse "1.4.12"]
                 [metosin/malli "0.15.0"]]
  :repl-options {:init-ns wchnt-lang.core}
  :main wchnt-lang.core
               :aot [wchnt-lang.core wchnt-lang.api wchnt-lang.parser wchnt-lang.eyeball wchnt-lang.schema]
  :prep-tasks ["javac" "compile"]
  :jar-name "wchnt-lang.jar"
  :uberjar-name "wchnt-lang-standalone.jar"
  :jar-exclusions [#"\.cljx$"]
  :omit-source true
  :profiles {             :dev {:dependencies [[org.clojure/test.check "1.1.1"]]
                   :source-paths ["live/src"]
                   :plugins [[lein-cloverage "1.2.4"]]}
             :live {:dependencies [[org.clojure/clojurescript "1.11.132"]]
                    :plugins [[lein-cljsbuild "1.1.8"]]
                    :cljsbuild {:builds [{:id "live"
                                          :source-paths ["src" "live/src"]
                                          :compiler {:output-to "live/public/js/main.js"
                                                     :main live.core
                                                     :optimizations :simple
                                                     :pretty-print true}}
                                         {:id "live-tests"
                                          :source-paths ["src" "live/src" "test"]
                                          :compiler {:output-to "live/public/js/tests.js"
                                                     :main live.test-runner
                                                     :optimizations :simple
                                                     :pretty-print true}}]}}
             :uberjar {:aot :all}}
  :aliases {"live" ["do" ["run" "-m" "wchnt-lang.prepare-live"]
                    ["with-profile" "+live" "cljsbuild" "once" "live"]]
             "live-test" ["do" ["run" "-m" "wchnt-lang.prepare-live"]
                          ["with-profile" "+live" "cljsbuild" "once" "live-tests"]]})
