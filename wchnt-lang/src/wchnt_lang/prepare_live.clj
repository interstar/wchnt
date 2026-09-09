(ns wchnt-lang.prepare-live
  "Copy live example and wiki seed pages into live/public for the browser.

  - live-examples/*.wcn  -> live/public/test-examples/  (loaded by tests.html)
  - seed-pages/*         -> live/public/seed/           (used to seed the wiki)"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- copy-files!
  "Copy regular files from src dir to dest dir (flattened, non-recursive).
  Only files whose name satisfies pred are copied; dest is cleared first."
  [src dest pred]
  (let [src-dir (io/file src)]
    (when (.isDirectory src-dir)
      (let [dest-dir (io/file dest)]
        (when (.exists dest-dir)
          (doseq [f (filter #(.isFile %) (.listFiles dest-dir))]
            (io/delete-file f true)))
        (.mkdirs dest-dir)
        (doseq [f (filter #(and (.isFile %) (pred (.getName %))) (.listFiles src-dir))]
          (io/copy f (io/file dest-dir (.getName f))))))))

(defn -main [& _]
  (copy-files! "live-examples" "live/public/test-examples"
               #(str/ends-with? % ".wcn"))
  (copy-files! "seed-pages" "live/public/seed" (constantly true))
  (println "Copied live-examples -> live/public/test-examples/")
  (println "Copied seed-pages -> live/public/seed/"))
