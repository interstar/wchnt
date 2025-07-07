(ns wchnt_lang.mainfile_test
  (:require [clojure.test :refer :all]
            [wchnt-lang.mainfile :refer :all]
            [wchnt-lang.schema :as schema]))

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
      (is (= "Person = String String\nGroup = [Person]" (:schema valid-result)))
      (is (= "$people = [:Group [:Person \"John\" \"Smith\"]]\n[:Town [:School $people] [:Team $people]]" (:construction valid-result)))
      (is (= "" (:reactive valid-result)))
      (is (= "" (:imperative valid-result)))
      (is (= "" (:target valid-result)))
      
      ;; Test error result conforms to schema
      (is (not (:success invalid-result)))
      (is (schema/valid-mainfile-parse-result? invalid-result))
      (is (and (:error invalid-result) (re-find #"order|sequence" (:error invalid-result)))))))

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
      (is (= "Person = String String\nGroup = [Person]" (:schema result)))
      (is (= "$people = [:Group [:Person \"John\" \"Smith\"]]\n[:Town [:School $people] [:Team $people]]" (:construction result)))
      (is (= "" (:reactive result)))
      (is (= "" (:imperative result)))
      (is (= "" (:target result))))))

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
      (is (= "Person = String String\nGroup = [Person]" (:schema result)))
      (is (= "" (:construction result)))
      (is (= "" (:reactive result)))
      (is (= "" (:imperative result)))
      (is (= "" (:target result))))))

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
      (is (and (:error result) (re-find #"Schema.*required" (:error result)))))))

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
      (is (and (:error result) (re-find #"multiple.*code.*blocks" (:error result)))))))

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
      (is (and (:error result) (re-find #"order|sequence" (:error result)))))))

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
      (is (= "Person = String String" (:schema result)))
      (is (= "$people = [:Group [:Person \"John\" \"Smith\"]]" (:construction result))))))

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
      (is (= "Person = String String" (:schema result)))
      (is (= "" (:construction result)))
      (is (= "" (:reactive result)))
      (is (= "" (:imperative result)))
      (is (= "" (:target result))))))

(deftest test-parse-mainfile-malformed-markdown
  (testing "Fail fast on malformed markdown"
    (let [content "# WCHNT Program

## Schema
                   
Missing closing backticks
                   
```
Person = String String
"
          result (parse-mainfile content)]
      (is (not (:success result)))
      (is (and (:error result) (re-find #"unclosed.*code.*block" (:error result))))))) 