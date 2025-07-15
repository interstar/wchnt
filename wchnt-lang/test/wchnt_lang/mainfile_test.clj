(ns wchnt_lang.mainfile_test
  (:require [clojure.test :refer :all]
            [wchnt-lang.mainfile :refer :all]
            [wchnt-lang.schema :as schema]
            [wchnt-lang.pipeline :as p]))



(deftest test-parse-mainfile-schema-validation
  (testing "parse-mainfile result conforms to MainfileParseResult schema"
    (let [valid-content "# WCHNT Program

## Schema

```
Person = String String
Group = [Person]
```

## Construction

```
$people = [:Group [:Person \"John\" \"Smith\"]]
[:Town [:School $people] [:Team $people]]
```"
          valid-result (parse-mainfile valid-content)
          invalid-content "# WCHNT Program

## Construction

```
$people = [:Group [:Person \"John\" \"Smith\"]]
```

## Schema

```
Person = String String
```"
          invalid-result (parse-mainfile invalid-content)]
      
      ;; Test successful result conforms to schema
      (is (:success valid-result))
      (is (schema/valid-mainfile-parse-result? valid-result))
      (let [value (:value valid-result)]
        (is (= "Person = String String\nGroup = [Person]" (:schema value)))
        (is (= "$people = [:Group [:Person \"John\" \"Smith\"]]\n[:Town [:School $people] [:Team $people]]" (:construction value)))
        (is (= "" (:reactive value)))
        (is (= "" (:imperative value)))
        (is (= "" (:target value))))
      
      ;; Test error result conforms to schema
      (is (not (:success invalid-result)))
      (is (schema/valid-mainfile-parse-result? invalid-result))
      (is (and (seq (:errors invalid-result)) (re-find #"order|sequence" (first (:errors invalid-result))))))))


(deftest test-parse-mainfile-basic
  (testing "Parse a basic mainfile with Schema and Construction sections"
    (let [content "# WCHNT Program

This is a simple example.

## Schema

Here's the schema definition:

```
Person = String String
Group = [Person]
```

## Construction

And here's the construction:

```
$people = [:Group [:Person \"John\" \"Smith\"]]
[:Town [:School $people] [:Team $people]]
```

## Reactive

Reactive behavior will go here.

## Imperative

Imperative code will go here.

## Target

Target configuration will go here."
          result (parse-mainfile content)]
      (is (:success result))
      (let [value (:value result)]
        (is (= "Person = String String\nGroup = [Person]" (:schema value)))
        (is (= "$people = [:Group [:Person \"John\" \"Smith\"]]\n[:Town [:School $people] [:Team $people]]" (:construction value)))
        (is (= "" (:reactive value)))
        (is (= "" (:imperative value)))
        (is (= "" (:target value)))))))


(deftest test-parse-mainfile-schema-only
  (testing "Parse a mainfile with only Schema section (required)"
    (let [content "# WCHNT Program

## Schema

```
Person = String String
Group = [Person]
```

Some additional text here."
          result (parse-mainfile content)]
      (is (:success result))
      (let [value (:value result)]
        (is (= "Person = String String\nGroup = [Person]" (:schema value)))
        (is (= "" (:construction value)))
        (is (= "" (:reactive value)))
        (is (= "" (:imperative value)))
        (is (= "" (:target value)))))))



(deftest test-parse-mainfile-missing-schema
  (testing "Fail when Schema section is missing"
    (let [content "# WCHNT Program

## Construction

```
$people = [:Group [:Person \"John\" \"Smith\"]]
```

## Reactive

```
// reactive code here
```"
          result (parse-mainfile content)]
      (is (not (:success result)))
      (is (and (seq (:errors result)) (re-find #"Schema.*required" (first (:errors result))))))))





(deftest test-parse-mainfile-multiple-code-blocks
  (testing "Fail when section has multiple code blocks"
    (let [content "# WCHNT Program

## Schema

Main schema:
```
Person = String String
```

Additional interfaces:
```
Entity = Person | Group
```"
          result (parse-mainfile content)]
      (is (not (:success result)))
      (is (and (seq (:errors result)) (re-find #"multiple.*code.*blocks" (first (:errors result)))))))

  )


(deftest test-parse-mainfile-ignore-language-hints
  (testing "Ignore language hints in code blocks"
    (let [content "# WCHNT Program

## Schema

```wchnt
Person = String String
```

## Construction

```clojure
$people = [:Group [:Person \"John\" \"Smith\"]]
```"
          result (parse-mainfile content)]
      (is (:success result))
      (let [value (:value result)]
        (is (= "Person = String String" (:schema value)))
        (is (= "$people = [:Group [:Person \"John\" \"Smith\"]]" (:construction value)))))))


(deftest test-parse-mainfile-wrong-order
  (testing "Fail when sections are in wrong order"
    (let [content "# WCHNT Program

## Construction

```
$people = [:Group [:Person \"John\" \"Smith\"]]
```

## Schema

```
Person = String String
Group = [Person]
```"
          result (parse-mainfile content)]
      (is (not (:success result)))
      (is (and (seq (:errors result)) (re-find #"order|sequence" (first (:errors result))))))))


(deftest test-parse-mainfile-empty-sections
  (testing "Handle empty sections gracefully"
    (let [content "# WCHNT Program

## Schema

```
Person = String String
```

## Construction

## Reactive

## Imperative

## Target"
          result (parse-mainfile content)]
      (is (:success result))
      (let [value (:value result)]
        (is (= "Person = String String" (:schema value)))
        (is (= "" (:construction value)))
        (is (= "" (:reactive value)))
        (is (= "" (:imperative value)))
        (is (= "" (:target value)))))))





(deftest test-parse-mainfile-malformed-markdown
  (testing "Fail fast on malformed markdown"
    (let [content "# WCHNT Program

## Schema
                   
Missing closing backticks
                   
```
Person = String String
```
"
          result (parse-mainfile content)]
      (is (not (:success result)))
      (is (and (seq (:errors result)) (re-find #"unclosed.*code.*block" (first (:errors result))))))))


