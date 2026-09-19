(ns wchnt-lang.transclusion-test
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [wchnt-lang.mainfile :as mainfile]
            [wchnt-lang.pipeline :as p]))

(def source-page
  "# Shared program

## Schema

```
Game = Int/x
```

## Construction

```
[:Game 1]
```

## Notes

This text belongs to the shared page.")

(deftest transcludes-sections-before-parsing
  (let [page "## Schema [[shared]]

## Construction [[shared]]

## Target

```
%terminal
```
"
        resolver (fn [name] (when (= name "shared") source-page))
        result (mainfile/parse-mainfile page {:resolve-page resolver})]
    (is (:success result) (pr-str (:errors result)))
    (is (= "Game = Int/x" (:schema (:value result))))
    (is (= "[:Game 1]" (:construction (:value result))))))

(deftest transclusion-is-generic-over-markdown-sections
  (let [page "## Notes [[shared]]

## Target

```
%terminal
```
"
        expanded (mainfile/expand-transclusions
                  page
                  (fn [name] (when (= name "shared") source-page)))]
    (is (str/includes? expanded "## Notes\n\nThis text belongs to the shared page."))
    (is (not (str/includes? expanded "[[shared]]")))))

(deftest transclusion-fails-fast-for-missing-references
  (testing "missing page"
    (let [result (mainfile/parse-mainfile "## Schema [[missing]]"
                                          {:resolve-page (constantly nil)})]
      (is (p/failed? result))
      (is (str/includes? (first (:errors result))
                         "Transclusion page not found: 'missing'"))))
  (testing "missing section"
    (let [result (mainfile/parse-mainfile "## Schema [[shared]]"
                                          {:resolve-page (constantly "## Other\n\ntext")})]
      (is (p/failed? result))
      (is (str/includes? (first (:errors result))
                         "Transclusion section 'schema' not found")))))

(deftest nested-transclusion-is-rejected
  (let [result (mainfile/parse-mainfile
                "## Schema [[outer]]"
                {:resolve-page (constantly "## Schema [[inner]]\n\n```\nGame = Int/x\n```")})]
    (is (p/failed? result))
    (is (str/includes? (first (:errors result))
                       "Nested transclusion"))))

