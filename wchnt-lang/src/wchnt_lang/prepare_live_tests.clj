(ns wchnt-lang.prepare-live-tests
  "Copy live-example .wcn files into live/public for browser test runner."
  (:require [clojure.java.io :as io]))

(defn -main [& _]
  (let [dest (io/file "live/public/live-examples")]
    (.mkdirs dest)
    (doseq [name ["bounce_canvas" "pollution_canvas"]]
      (io/copy (io/file "live-examples" (str name ".wcn"))
               (io/file dest (str name ".wcn"))))
    (println "Copied live-examples to live/public/live-examples/")))
