(ns wchnt-lang.live-export-test
  (:require [clojure.test :refer [deftest is testing]]
            [live.export :as export]))

(deftest encode-decode-roundtrip
  (testing "pages survive export and import"
    (let [pages {"welcome" "# Welcome\n\nHello.\n"
                 "bounce_canvas" "## Schema\n\n```\nGame = Ball\n```\n"}
          encoded (export/encode-pages pages)
          decoded (export/decode-pages encoded)]
      (is (re-find #"^%%%%%%%%%%%%%%%%%%%\nbounce_canvas" encoded))
      (is (= 2 (count decoded)))
      (is (= "bounce_canvas" (:name (first decoded))))
      (is (= (get pages "bounce_canvas") (:content (first decoded))))
      (is (= "welcome" (:name (second decoded))))
      (is (= (get pages "welcome") (:content (second decoded)))))))

(deftest decode-skips-leading-blanks
  (testing "file may start with a separator"
    (let [text (str export/page-separator "\nfoo\n# Foo")]
      (is (= [{:name "foo" :content "# Foo"}]
             (export/decode-pages text))))))

(deftest decode-rejects-invalid-names
  (testing "bad page names fail fast"
    (let [text (str export/page-separator "\nnot valid!\nbody\n")]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid page name"
                            (export/decode-pages text))))))
