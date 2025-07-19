(ns wchnt-lang.parser-test
  (:require [clojure.test :refer :all]
            [instaparse.core :as insta]
            [wchnt-lang.parser :as parser]
            [wchnt-lang.pipeline :as p]
            [wchnt-lang.schema :as schema]
            [wchnt-lang.mainfile :as mainfile]))

(defn ast-contains? [pred ast]
  (cond
    (pred ast) true
    (vector? ast) (some #(ast-contains? pred %) ast)
    (seq? ast) (some #(ast-contains? pred %) ast)
    :else false))

(deftest split-wchnt-phases-test
  (testing "split-wchnt-phases handles schema-only files"
    (let [input "## Schema\n```\nPerson = String/name String/address\nSchool = [Person]/students\n```"
          result (mainfile/parse-mainfile input)]
      (is (:success result))
      (is (= "Person = String/name String/address\nSchool = [Person]/students" (:schema (:value result))))
      (is (= "" (:construction (:value result))))))
  
  (testing "split-wchnt-phases handles schema+construction files"
    (let [input "## Schema\n```\nPerson = String/name\n```\n## Construction\n```\n[:Person \"John\"]\n```"
          result (mainfile/parse-mainfile input)]
      (is (:success result))
      (is (= "Person = String/name" (:schema (:value result))))
      (is (= "[:Person \"John\"]" (:construction (:value result))))))
  
  (testing "split-wchnt-phases handles files with extra whitespace"
    (let [input "## Schema\n```\n  Person = String/name  \n```\n## Construction\n```\n  [:Person \"John\"]  \n```"
          result (mainfile/parse-mainfile input)]
      (is (:success result))
      (is (= "  Person = String/name  " (:schema (:value result))))
      (is (= "  [:Person \"John\"]  " (:construction (:value result))))))
  
  (testing "split-wchnt-phases handles empty construction phase"
    (let [input "## Schema\n```\nPerson = String/name\n```\n## Construction\n```\n```"
          result (mainfile/parse-mainfile input)]
      (is (:success result))
      (is (= "Person = String/name" (:schema (:value result))))
      (is (= "" (:construction (:value result))))))
  
  (testing "split-wchnt-phases handles complex multi-line input"
    (let [input "## Schema\n```\nPerson = String/name String/address\nSchool = [Person]/students\n```\n## Construction\n```\npeople = [:Array [:Person \"John\"]]\n[:School people]\n```"
          result (mainfile/parse-mainfile input)]
      (is (:success result))
      (is (= "Person = String/name String/address\nSchool = [Person]/students" (:schema (:value result))))
      (is (= "people = [:Array [:Person \"John\"]]\n[:School people]" (:construction (:value result)))))))

(run-tests)
