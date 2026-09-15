(ns wchnt-lang.prepare-live-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(deftest seed-from-live-script-writes-index
  (testing "seed-from-live.sh copies mapped examples and writes index.txt"
    (let [dest (io/file "target/prepare-live-test-seed")
          script (.getCanonicalPath (io/file "live-examples/seed-from-live.sh"))
          dest-path (.getCanonicalPath (doto dest .mkdirs))
          code (.waitFor (.start (ProcessBuilder. ["bash" script dest-path])))]
      (is (zero? code) "seed-from-live.sh should succeed")
      (is (= (slurp "live-examples/bounce_canvas.wcn")
             (slurp (io/file dest "bounce.wcn"))))
      (is (= (slurp "live-examples/welcome.wcn")
             (slurp (io/file dest "welcome.wcn"))))
      (is (not (.exists (io/file dest "square_canvas.wcn"))))
      (let [names (str/split-lines (str/trim (slurp (io/file dest "index.txt"))))]
        (is (seq names))
        (doseq [name names]
          (is (.isFile (io/file dest (str name ".wcn")))
              (str "index lists " name " but seed/" name ".wcn is missing")))))))
