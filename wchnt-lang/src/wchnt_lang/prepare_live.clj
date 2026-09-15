(ns wchnt-lang.prepare-live
  "Copy live-examples into live/public for the browser.

  - *.wcn              -> live/public/test-examples/
  - seed-from-live.sh  -> live/public/seed/  (reads seed-map.txt)"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- copy-wcn!
  [src dest]
  (let [src-dir (io/file src)
        dest-dir (io/file dest)]
    (when (.exists dest-dir)
      (doseq [f (filter #(.isFile %) (.listFiles dest-dir))]
        (io/delete-file f true)))
    (.mkdirs dest-dir)
    (when (.isDirectory src-dir)
      (doseq [f (filter #(and (.isFile %) (str/ends-with? (.getName %) ".wcn"))
                        (.listFiles src-dir))]
        (io/copy f (io/file dest-dir (.getName f)))))))

(defn- run-seed-from-live!
  [dest]
  (let [script (.getCanonicalPath (io/file "live-examples/seed-from-live.sh"))
        dest-path (.getCanonicalPath (doto (io/file dest) .mkdirs))
        code (.waitFor (.start (doto (ProcessBuilder. ["bash" script dest-path])
                                 (.inheritIO))))]
    (when-not (zero? code)
      (throw (ex-info (str "seed-from-live.sh exited " code)
                      {:dest dest-path :exit code})))))

(defn -main [& _]
  (copy-wcn! "live-examples" "live/public/test-examples")
  (run-seed-from-live! "live/public/seed")
  (println "Copied live-examples -> live/public/test-examples/")
  (println "Ran seed-from-live.sh -> live/public/seed/"))
