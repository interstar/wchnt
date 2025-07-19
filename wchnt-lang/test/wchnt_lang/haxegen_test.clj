(ns wchnt-lang.haxegen-test
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [wchnt-lang.haxegen :as haxegen]
            [wchnt-lang.schema :as schema]
            [wchnt-lang.parser :as parser]))

(deftest test-type-ast->haxe-type

  
  (testing "leaves String unchanged"
    (is (= "String" (haxegen/type-ast->haxe-type "String"))))
  
  (testing "handles Array types"
    (is (= "Array<Int>" (haxegen/type-ast->haxe-type [:ArrayType [:Type "Int"]]))))
  
  (testing "handles Map types"
    (is (= "Map<String, Int>" (haxegen/type-ast->haxe-type [:MapType [:KeyType "String"] [:ValType "Int"]])))))

(deftest test-generate-to-construction-parts
  (testing "generates correct parts for primitive types"
    (let [elements [{:name "x" :type "Int"}
                    {:name "name" :type "String"}
                    {:name "active" :type "Bool"}]]
      (is (= ["this.x" "\"\" + this.name + \"\"" "this.active"]
             (haxegen/generate-to-construction-parts elements)))))
  
  (testing "generates correct parts for complex types"
    (let [elements [{:name "items" :type [:ArrayType [:Type "String"]]}
                    {:name "config" :type [:MapType [:KeyType "String"] [:ValType "Int"]]}]]
      (is (= ["ArrayExtensions.toConstruction(this.items, depth + 1)" "this.config.toConstruction(depth + 1)"]
             (haxegen/generate-to-construction-parts elements))))))

(deftest test-compile-to-haxe-with-primitives
  (testing "compiles schema with Int fields correctly"
    (let [input "Rect = Int/x Int/y Int/width Int/height"
          ast-result (wchnt-lang.parser/schema-wchnt->schema-ast input)
          ast (:value ast-result)]
      (let [result (haxegen/schema-ast->haxe ast)]
        (let [haxe-code result]
        (is (str/includes? haxe-code "public var x: Int;"))
        (is (str/includes? haxe-code "public var y: Int;"))
        (is (str/includes? haxe-code "public var width: Int;"))
        (is (str/includes? haxe-code "public var height: Int;"))
        ;; Check that toConstruction doesn't call .toConstruction() on Int fields
        (is (str/includes? haxe-code "this.x"))
        (is (str/includes? haxe-code "this.y"))
          (is (not (str/includes? haxe-code "this.x.toConstruction")))))))
  
  (testing "compiles schema with String fields correctly"
    (let [input "Person = String/name String/email"
          ast-result (wchnt-lang.parser/schema-wchnt->schema-ast input)
          ast (:value ast-result)
          result (haxegen/schema-ast->haxe ast)]
      (let [haxe-code result]
        (is (str/includes? haxe-code "public var name: String;"))
        (is (str/includes? haxe-code "public var email: String;"))
        ;; Check that toConstruction wraps String fields in quotes
        (is (str/includes? haxe-code "\"\" + this.name + \"\""))))))

(deftest test-compile-to-haxe-with-arrays
  (testing "compiles schema with arrays and includes ArrayExtensions"
    (let [input "School = [Person]/students"
          ast-result (wchnt-lang.parser/schema-wchnt->schema-ast input)
          ast (:value ast-result)
          result (haxegen/schema-ast->haxe ast)]
      (let [haxe-code result]
        ;; Should have both the class and the ArrayExtensions
        (is (str/includes? haxe-code "class ArrayExtensions"))))))

(deftest test-find-all-nodes
  (testing "find-all-nodes finds nodes at different depths"
    (let [tree [:Root
                [:Level1 [:Target "value1"]]
                [:Level1 [:Level2 [:Target "value2"]]]
                [:Level1 [:Level2 [:Level3 [:Target "value3"]]]]]
          results (haxegen/find-all-nodes :Target tree)]
      (is (= 3 (count results)) "Should find all 3 Target nodes")
      (is (= "value1" (second (first results))) "Should find first value")
      (is (= "value2" (second (second results))) "Should find second value")
      (is (= "value3" (second (nth results 2))) "Should find third value")))

  (testing "find-all-nodes handles empty trees"
    (is (= [] (haxegen/find-all-nodes :Target [])) "Empty vector should return empty")
    (is (= [] (haxegen/find-all-nodes :Target nil)) "Nil should return empty")
    (is (= [] (haxegen/find-all-nodes :Target "string")) "String should return empty"))

  (testing "find-all-nodes finds nodes in sequences"
    (let [tree [:Root
                [:List [:Target "a"] [:Target "b"]]
                [:Nested [:List [:Target "c"]]]]
          results (haxegen/find-all-nodes :Target tree)]
      (is (= 3 (count results)) "Should find all 3 Target nodes in sequences")
      (is (= ["a" "b" "c"] (map second results)) "Should find correct values")))

  (testing "find-all-nodes handles ArrayType and MapType special cases"
    (let [tree [:Root
                [:ArrayType [:Target "array-value"]]
                [:MapType [:Target "map-value"]]]
          results (haxegen/find-all-nodes :Target tree)]
      (is (= 2 (count results)) "Should find both Target nodes in special types")
      (is (= ["array-value" "map-value"] (map second results)) "Should find correct values")))

  (testing "find-all-nodes returns empty when no matches"
    (let [tree [:Root [:Level1 [:Level2 "value"]]]]
      (is (= [] (haxegen/find-all-nodes :Target tree)) "Should return empty when no matches"))))
  
  
  
