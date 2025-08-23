(ns wchnt-lang.debug-construction
  (:require [clojure.test :refer :all]
            [wchnt-lang.parser :as parser]
            [wchnt-lang.ast-to-ir :as ast-to-ir]
            [wchnt-lang.ast-utils :as ast-utils]
            [wchnt-lang.compiler :as compiler]
            [wchnt-lang.schema :as schema]
            [wchnt-lang.pipeline :as P]))

(deftest debug-construction-ast
  (testing "Debug construction AST structure"
    (let [construction-text "[:Game [:PlayArea [:Rect 0 0 800 600]] [:Ball 100 100 5]]"
          construction-cargo (parser/parse-construction-unified construction-text)
          construction-ast (:value construction-cargo)]
      
      (println "Construction AST:" (pr-str construction-ast))
      
      ;; Test if we can find ObjectConstruction
      (let [obj-construction (ast-utils/find-first-node-by-type construction-ast :ObjectConstruction)]
        (println "Found ObjectConstruction:" (pr-str obj-construction))
        (is (not (nil? obj-construction)) "Should find ObjectConstruction node"))
      
      ;; Test if we can find ClassName
      (let [class-names (ast-utils/find-nodes-by-type construction-ast :ClassName)]
        (println "Found ClassNames:" (pr-str class-names))
        (is (not (empty? class-names)) "Should find ClassName nodes"))
      
      ;; Test the full extraction
      (let [schema-ir {:assemblages [{:name "Game"}
                                     {:name "PlayArea"}
                                     {:name "Rect"}
                                     {:name "Ball"}]}]
        (try
          (let [root-class (ast-to-ir/extract-root-class-from-construction construction-ast schema-ir)]
            (println "Extracted root class:" root-class)
            (is (= "Game" root-class) "Should extract Game as root class"))
          (catch Exception e
            (println "Exception extracting root class:" (.getMessage e))
            (println "Exception data:" (ex-data e))
            (is false "Should not throw exception")))))))

(deftest debug-failing-construction-ast
  (testing "Debug the specific failing construction AST"
    (let [construction-text "[:DB \n  [:Array/Book\n     [:Book \"Pride and Prejudice\"]\n     [:Book \"Northanger Abbey\"]\n  ]\n]"
          construction-cargo (parser/parse-construction-unified construction-text)
          construction-ast (:value construction-cargo)]
      
      (println "Failing Construction AST:" (pr-str construction-ast))
      
      ;; Test if we can find ObjectConstruction
      (let [obj-construction (ast-utils/find-first-node-by-type construction-ast :ObjectConstruction)]
        (println "Found ObjectConstruction in failing case:" (pr-str obj-construction))
        (is (not (nil? obj-construction)) "Should find ObjectConstruction node"))
      
      ;; Test if we can find ClassName
      (let [class-names (ast-utils/find-nodes-by-type construction-ast :ClassName)]
        (println "Found ClassNames in failing case:" (pr-str class-names))
        (is (not (empty? class-names)) "Should find ClassName nodes"))
      
      ;; Test the full extraction
      (let [schema-ir {:assemblages [{:name "DB"}
                                     {:name "Book"}]}]
        (try
          (let [root-class (ast-to-ir/extract-root-class-from-construction construction-ast schema-ir)]
            (println "Extracted root class from failing case:" root-class)
            (is (= "DB" root-class) "Should extract DB as root class"))
          (catch Exception e
            (println "Exception extracting root class from failing case:" (.getMessage e))
            (println "Exception data:" (ex-data e))
            (is false "Should not throw exception"))))))) 

(deftest debug-construction-ast-to-ir
  (testing "Debug the full construction AST-to-IR transformation"
    (let [construction-text "[:DB \n  [:Array/Book\n     [:Book \"Pride and Prejudice\"]\n     [:Book \"Northanger Abbey\"]\n  ]\n]"
          construction-cargo (parser/parse-construction-unified construction-text)
          construction-ast (:value construction-cargo)
          schema-ir {:assemblages [{:name "DB"}
                                   {:name "Book"}]}]
      
      (println "Testing construction AST-to-IR transformation")
      
      (try
        (let [construction-ir (ast-to-ir/construction-ast-to-ir construction-ast schema-ir)]
          (println "Construction IR:" (pr-str construction-ir))
          (is (not (nil? construction-ir)) "Should create construction IR")
          (is (= "DB" (:root-class construction-ir)) "Should have DB as root class")
          (is (= "dBFactory" (:factory-name construction-ir)) "Should have correct factory name"))
        (catch Exception e
          (println "Exception in construction AST-to-IR:" (.getMessage e))
          (println "Exception data:" (ex-data e))
          (is false "Should not throw exception")))))) 

(deftest debug-ast-navigation
  (testing "Debug AST navigation in detail"
    (let [construction-text "[:DB \n  [:Array/Book\n     [:Book \"Pride and Prejudice\"]\n     [:Book \"Northanger Abbey\"]\n  ]\n]"
          construction-cargo (parser/parse-construction-unified construction-text)
          construction-ast (:value construction-cargo)]
      
      (println "=== Detailed AST Navigation Debug ===")
      (println "Full AST:" (pr-str construction-ast))
      (println "AST type:" (type construction-ast))
      (println "AST first element:" (first construction-ast))
      
      ;; Test find-nodes-by-type
      (let [all-object-constructions (ast-utils/find-nodes-by-type construction-ast :ObjectConstruction)]
        (println "All ObjectConstruction nodes:" (pr-str all-object-constructions))
        (is (not (empty? all-object-constructions)) "Should find ObjectConstruction nodes"))
      
      ;; Test find-first-node-by-type
      (let [first-obj-construction (ast-utils/find-first-node-by-type construction-ast :ObjectConstruction)]
        (println "First ObjectConstruction:" (pr-str first-obj-construction))
        (is (not (nil? first-obj-construction)) "Should find first ObjectConstruction"))
      
      ;; Test find-nodes-by-type for ClassName
      (let [all-class-names (ast-utils/find-nodes-by-type construction-ast :ClassName)]
        (println "All ClassName nodes:" (pr-str all-class-names))
        (is (not (empty? all-class-names)) "Should find ClassName nodes"))
      
      ;; Test the specific extraction logic
      (let [obj-construction (ast-utils/find-first-node-by-type construction-ast :ObjectConstruction)]
        (when obj-construction
          (println "ObjectConstruction found, checking structure:")
          (println "  First element:" (first obj-construction))
          (println "  Second element:" (second obj-construction))
          (println "  Second element type:" (type (second obj-construction)))
          (println "  Second element first:" (first (second obj-construction)))
          
          (let [class-name-node (second obj-construction)]
            (if (ast-utils/node-type? class-name-node :ClassName)
              (let [class-name (second class-name-node)]
                (println "  Extracted class name:" class-name)
                (is (= "DB" class-name) "Should extract DB as class name"))
              (println "  ClassName node not found in expected position")))))))) 

(deftest debug-extract-root-class-issue
  (testing "Debug the exact issue with extract-root-class-from-construction"
    (let [construction-text "[:DB \n  [:Array/Book\n     [:Book \"Pride and Prejudice\"]\n     [:Book \"Northanger Abbey\"]\n  ]\n]"
          construction-cargo (parser/parse-construction-unified construction-text)
          construction-ast (:value construction-cargo)
          schema-ir {:assemblages [{:name "DB"}
                                   {:name "Book"}]}]
      
      (println "=== Debugging extract-root-class-from-construction ===")
      (println "Construction AST:" (pr-str construction-ast))
      (println "Schema IR:" (pr-str schema-ir))
      
      ;; Test ast-utils functions directly
      (let [obj-construction (ast-utils/find-first-node-by-type construction-ast :ObjectConstruction)]
        (println "Found ObjectConstruction:" (pr-str obj-construction))
        (is (not (nil? obj-construction)) "Should find ObjectConstruction node"))
      
      ;; Test the extraction logic step by step
      (let [obj-construction (ast-utils/find-first-node-by-type construction-ast :ObjectConstruction)
            class-name-node (second obj-construction)]
        (println "Class name node:" (pr-str class-name-node))
        (println "Is ClassName node?" (ast-utils/node-type? class-name-node :ClassName))
        (is (ast-utils/node-type? class-name-node :ClassName) "Should be a ClassName node"))
      
      ;; Test the full extraction
      (try
        (let [root-class (ast-to-ir/extract-root-class-from-construction construction-ast schema-ir)]
          (println "Successfully extracted root class:" root-class)
          (is (= "DB" root-class) "Should extract DB as root class"))
        (catch Exception e
          (println "Exception in extract-root-class-from-construction:" (.getMessage e))
          (println "Exception data:" (ex-data e))
          (is false "Should not throw exception")))))) 

(deftest test-austen-construction-ir
  (testing "Test construction IR generation with Austen example"
    (let [construction-text "[:DB \n  [:Array/Book\n     [:Book \"Pride and Prejudice\"]\n     [:Book \"Northanger Abbey\"]\n  ]\n]"
          construction-cargo (parser/parse-construction-unified construction-text)
          construction-ast (:value construction-cargo)
          schema-ir {:assemblages [{:name "DB"}
                                   {:name "Book"}]}
          construction-ir (ast-to-ir/construction-ast-to-ir construction-ast schema-ir)]
      
      (println "=== Austen Construction IR Test ===")
      (println "Construction AST:" (pr-str construction-ast))
      (println "Schema IR:" (pr-str schema-ir))
      (println "Construction IR:" (pr-str construction-ir))
      
      (is (:success construction-cargo))
      (is (not (nil? construction-ir)))
      (is (= "DB" (:root-class construction-ir)))
      (is (= "dBFactory" (:factory-name construction-ir)))
      
      ;; Check that final objects are created
      (let [objects (:objects construction-ir)
            return-object (:return-object construction-ir)]
        (println "Objects:" (keys objects))
        (println "Return object:" return-object)
        (is (not (empty? objects)) "Should have objects in IR")
        (is (not (nil? return-object)) "Should have a return object")
        (is (contains? objects return-object) "Return object should exist in objects")
        (is (= "DB" (:class-name (get objects return-object))) "Return object should be a DB"))))) 

(deftest test-complex-example-parsing
  (testing "Test that the complex example is being parsed correctly"
    (let [complex-wchnt "## Schema

```
Game = [Shape]/shapes [Player]/players
Shape = Triangle | Circle
Triangle = Int/base Int/height
Circle = Int/radius
Player = String/name Int/score
```

## Construction

```
shapes = [:Array/Shape [:Triangle 10 20] [:Circle 15]] .
players = [:Array/Player [:Player \"Alice\" 100] [:Player \"Bob\" 85]] .
[:Game shapes players]
```"
          cargo-result (compiler/compile complex-wchnt)]
      
      (println "=== Complex Example Test ===")
      (println "Input WCHNT:" complex-wchnt)
      (println "Cargo result:" (pr-str cargo-result))
      
      ;; Debug the AST structure
      (let [construction-ast (get-in cargo-result [:stash :construction-ast])]
        (println "Construction AST:" (pr-str construction-ast))
        
        ;; Test ast-utils functions directly
        (let [obj-constructions (ast-utils/find-nodes-by-type construction-ast :ObjectConstruction)]
          (println "Found ObjectConstruction nodes:" (count obj-constructions))
          (doseq [node obj-constructions]
            (println "  ObjectConstruction node:" (pr-str node))))
        
        (let [expression-nodes (ast-utils/find-nodes-by-type construction-ast :Expression)]
          (println "Found Expression nodes:" (count expression-nodes))
          (doseq [node expression-nodes]
            (println "  Expression node:" (pr-str node)))))
      
      ;; Check if the cargo has the expected structure
      (is (P/is-cargo? cargo-result))
      (if (:success cargo-result)
        (let [result (:value cargo-result)]
          (println "Success! Result:" (pr-str result))
          (is (schema/valid-full-program? result)))
        (do
          (println "Failed! Errors:" (:errors cargo-result))
          (println "Stash:" (pr-str (:stash cargo-result)))))))) 