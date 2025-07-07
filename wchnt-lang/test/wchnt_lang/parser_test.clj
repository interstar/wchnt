(ns wchnt-lang.parser-test
  (:require [clojure.test :refer :all]
            [wchnt-lang.parser :as parser]))

(deftest split-wchnt-phases-test
  (testing "split-wchnt-phases handles schema-only files"
    (let [input "## Schema\nPerson = String/name String/address\nSchool = [Person]/students"
          result (parser/split-wchnt-phases input)]
      (is (= "Person = String/name String/address\nSchool = [Person]/students" (:schema result)))
      (is (= "" (:construction result)))))
  
  (testing "split-wchnt-phases handles schema+construction files"
    (let [input "## Schema\nPerson = String/name\n## Construction\n[:Person \"John\"]"
          result (parser/split-wchnt-phases input)]
      (is (= "Person = String/name" (:schema result)))
      (is (= "[:Person \"John\"]" (:construction result)))))
  
  (testing "split-wchnt-phases handles files with extra whitespace"
    (let [input "## Schema\n  Person = String/name  \n## Construction\n  [:Person \"John\"]  "
          result (parser/split-wchnt-phases input)]
      (is (= "Person = String/name" (:schema result)))
      (is (= "[:Person \"John\"]" (:construction result)))))
  
  (testing "split-wchnt-phases handles empty construction phase"
    (let [input "## Schema\nPerson = String/name\n## Construction\n"
          result (parser/split-wchnt-phases input)]
      (is (= "Person = String/name" (:schema result)))
      (is (= "" (:construction result)))))
  
  (testing "split-wchnt-phases handles complex multi-line input"
    (let [input "## Schema\nPerson = String/name String/address\nSchool = [Person]/students\n## Construction\n$people = [:Array [:Person \"John\"]]\n[:School $people]"
          result (parser/split-wchnt-phases input)]
      (is (= "Person = String/name String/address\nSchool = [Person]/students" (:schema result)))
      (is (= "$people = [:Array [:Person \"John\"]]\n[:School $people]" (:construction result)))))) 