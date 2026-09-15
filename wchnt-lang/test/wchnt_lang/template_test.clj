(ns wchnt-lang.template-test
  (:require [clojure.test :refer :all]
            [wchnt-lang.template :as template]))

(deftest holes-and-expand
  (testing "named holes substitute; extras ignored"
    (is (= ["place"] (template/holes "You are in {place}.")))
    (is (= "You are in Village Square."
           (template/expand "You are in {place}." {"place" "Village Square"})))
    (is (= "ab" (template/expand "a{x}b" {"x" "" "y" "nope"})))))

(deftest missing-and-malformed
  (testing "missing names and bad braces fail fast"
    (is (thrown-with-msg? Exception #"missing 'who'"
                          (template/expand "hi {who}." {})))
    (is (thrown-with-msg? Exception #"unmatched '\{'"
                          (template/holes "hi {who")))
    (is (thrown-with-msg? Exception #"unmatched '\}'"
                          (template/holes "hi }")))
    (is (thrown-with-msg? Exception #"bad hole"
                          (template/holes "hi {2}")))))
