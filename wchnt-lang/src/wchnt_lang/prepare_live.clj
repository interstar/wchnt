(ns wchnt-lang.prepare-live
  "Copy live example and wiki seed pages into live/public for the browser.

  - live-examples/*.wcn  -> live/public/test-examples/  (loaded by tests.html)
  - live-example-seeds   -> live/public/seed/           (renamed live-examples)

  The live-example-seeds table is the source of truth for which live
  examples also seed the wiki. website/build.sh copies the same pairs."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:const live-example-seeds
  "Pairs of [live-examples-stem seed-page-name]. Content is copied from
  live-examples/<stem>.wcn to the seed dir as <name>.wcn. Keep this
  table in sync with website/build.sh."
  [["welcome" "welcome"]
   ["bounce_canvas" "bounce"]
   ["shapes_canvas" "shapes"]
   ["square_canvas" "square"]
   ["pollution_canvas" "pollution"]
   ["pong_canvas" "pong"]
   ["adventure_cli" "adventure"]
   ["writepaths" "writepaths"]
   ["flyingA" "flyingA"]
   ["flyingB" "flyingB"]])

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

(defn- copy-live-example-seed!
  [dest-dir [src dest-name]]
  (let [from (io/file "live-examples" (str src ".wcn"))
        to (io/file dest-dir (str dest-name ".wcn"))]
    (when-not (.isFile from)
      (throw (ex-info (str "Missing live-example for seed page '" dest-name "': "
                           "live-examples/" src ".wcn")
                      {:src src :dest dest-name})))
    (io/copy from to)))

(defn- clear-files!
  [dir]
  (when (.isDirectory dir)
    (doseq [f (filter #(.isFile %) (.listFiles dir))]
      (io/delete-file f true))))

(defn compose-seed-dir!
  "Write the wiki seed directory from live-example-seeds."
  [dest]
  (let [dest-dir (io/file dest)]
    (clear-files! dest-dir)
    (.mkdirs dest-dir)
    (doseq [pair live-example-seeds]
      (copy-live-example-seed! dest-dir pair))))

(defn -main [& _]
  (copy-files! "live-examples" "live/public/test-examples"
               #(str/ends-with? % ".wcn"))
  (compose-seed-dir! "live/public/seed")
  (println "Copied live-examples -> live/public/test-examples/")
  (println "Composed live-example-seeds -> live/public/seed/"))
