(ns wchnt-lang.wiki-search-test
  (:require [clojure.test :refer :all]
            [wchnt-lang.wiki-search :as wiki-search]))

(def pages
  {"bounce" "A square in a box"
   "pollution" "yellow player and red waste"
   "adventure" "You are in a ballroom"
   "Ball" "schema only"})

(deftest blank-query-lists-names
  (is (= {:names ["Ball" "adventure" "bounce" "pollution"]
          :bodies []}
         (wiki-search/split "" pages))))

(deftest name-hits-rank-exact-then-prefix
  (let [{:keys [names bodies]} (wiki-search/split "ball" pages)]
    (is (= ["Ball"] names))
    (is (= ["adventure"] bodies))))

(deftest prefix-name-sorts-before-substring
  (is (= ["Bob" "bounce" "about"]
         (:names (wiki-search/split "bo" {"bounce" ""
                                         "Bob" ""
                                         "about" ""})))))

(deftest body-hits-exclude-name-hits
  (let [{:keys [names bodies]} (wiki-search/split "waste" pages)]
    (is (empty? names))
    (is (= ["pollution"] bodies))))

(deftest search-is-case-insensitive
  (is (= ["Ball"] (:names (wiki-search/split "BALL" pages))))
  (is (= ["pollution"] (:bodies (wiki-search/split "YELLOW" pages)))))
