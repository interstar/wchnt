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
  :aot [wchnt-lang.core wchnt-lang.parser wchnt-lang.haxegen wchnt-lang.eyeball wchnt-lang.schema]
  :jar-name "wchnt-lang.jar"
  :jar-exclusions [#"\.cljx$"]
  :profiles {:dev {:dependencies [[org.clojure/test.check "1.1.1"]]}
             :uberjar {:aot :all}})
