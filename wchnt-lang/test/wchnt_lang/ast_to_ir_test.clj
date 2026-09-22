(ns wchnt-lang.ast-to-ir-test
  (:require [clojure.test :refer :all]
            [wchnt-lang.ast-to-ir :as ast-to-ir]
            [wchnt-lang.ast-utils :as ast-utils]
            [wchnt-lang.parser :as parser]
            [wchnt-lang.schema :as schema]
            [wchnt-lang.ir :as ir]))

;; =============================================================================
;; Schema AST to IR Tests
;; =============================================================================

(deftest test-process-element-to-component
  (testing "process-element-to-component should correctly transform parsed elements"
    (let [element {:sigil nil :type "String" :optional-name nil :name "name"}
          result (ast-to-ir/process-element-to-component element)]
      (is (= "name" (:component-name result)))
      (is (= "String" (:type-name result)))
      (is (= :ordinary (:relationship result)))
      (is (nil? (:optional-name result))))))

(deftest test-process-element-to-component-with-sigil
  (testing "process-element-to-component should handle sigils correctly"
    (let [context-element {:sigil ":" :type "PlayArea" :optional-name nil :name "playArea"}
          result (ast-to-ir/process-element-to-component context-element)]
      (is (= "playArea" (:component-name result)))
      (is (= "PlayArea" (:type-name result)))
      (is (= :context-specific (:relationship result))))))

(deftest test-process-element-to-component-with-reactive-sigil
  (testing "process-element-to-component should mark $ as reactive"
    (let [result (ast-to-ir/process-element-to-component
                  {:sigil "$" :type "Time" :optional-name nil :name "time"})]
      (is (= "time" (:component-name result)))
      (is (= "Time" (:type-name result)))
      (is (= :reactive (:relationship result))))))

(deftest test-process-element-to-component-with-delegate-sigil
  (testing "process-element-to-component should mark + as delegate"
    (let [result (ast-to-ir/process-element-to-component
                  {:sigil "+" :type "BasePerson" :optional-name nil :name "basePerson"})]
      (is (= "basePerson" (:component-name result)))
      (is (= "BasePerson" (:type-name result)))
      (is (= :delegate (:relationship result))))))

(deftest test-build-observable-and-subscriber-classes
  (testing "Game = $Time records Time as observable and Game as subscriber"
    (let [cargo (parser/schema-wchnt->schema-ast "Game = $Time\nTime = Int/t\n")
          result (ast-to-ir/build-observable-and-subscriber-classes (:value cargo))]
      (is (:success cargo))
      (is (= #{"Time"} (set (:observable-classes result))))
      (is (= #{"Game"} (set (:subscriber-classes result)))))))

(deftest test-schema-ast-to-ir-reactive-component
  (testing "schema IR keeps the $ field on the subscriber class"
    (let [cargo (parser/schema-wchnt->schema-ast "Game = $Time\nTime = Int/t\n")
          schema-ir (ast-to-ir/schema-ast-to-ir (:value cargo))
          game (first (filter #(= "Game" (:name %)) (:assemblages schema-ir)))
          time-component (first (:components game))]
      (is (schema/valid-schema-ir? schema-ir))
      (is (= :reactive (:relationship time-component)))
      (is (= "time" (:component-name time-component)))
      (is (= "Time" (:type-name time-component))))))

(deftest test-schema-ast-to-ir-rejects-derived-component-name-collisions
  (testing "repeated primitive types cannot derive the same field name"
    (let [cargo (parser/schema-wchnt->schema-ast "X = Int Int\n")]
      (is (:success cargo))
      (is (thrown-with-msg? Exception
                            #"Two fields named 'int' in X definition in the Schema"
                            (ast-to-ir/schema-ast-to-ir (:value cargo))))))
  (testing "repeated unnamed maps cannot derive the same field name"
    (let [cargo (parser/schema-wchnt->schema-ast
                 "Palette = {Colour:Int} {Colour:Int}\nColour = \"Black\" | \"White\"\n")]
      (is (:success cargo))
      (is (thrown-with-msg? Exception
                            #"Two fields named 'colourToInt' in Palette definition in the Schema"
                            (ast-to-ir/schema-ast-to-ir (:value cargo)))))))

(deftest test-schema-ast-to-ir-mailbox-class
  (testing ">Keys is recorded as a mailbox class"
    (let [cargo (parser/schema-wchnt->schema-ast
                 ">Keys = Bool/left\nGame = $Keys\n")
          schema-ir (ast-to-ir/schema-ast-to-ir (:value cargo))]
      (is (schema/valid-schema-ir? schema-ir))
      (is (= ["Keys"] (:mailbox-classes schema-ir)))
      (is (ir/mailbox-class? schema-ir "Keys"))
      (is (not (ir/mailbox-class? schema-ir "Game"))))))

(deftest test-schema-ast-to-ir-rejects-inlet-enum
  (testing "> on an enum fails fast"
    (let [cargo (parser/schema-wchnt->schema-ast ">Dir = \"A\" | \"B\"\n")]
      (is (:success cargo))
      (is (thrown-with-msg? Exception #"composition class"
                            (ast-to-ir/schema-ast-to-ir (:value cargo)))))))

(deftest test-schema-ast-to-ir-rejects-primitive-reactive
  (testing "$ on a primitive type fails fast"
    (let [cargo (parser/schema-wchnt->schema-ast "Game = $Int/t\n")]
      (is (:success cargo))
      (is (thrown-with-msg? Exception #"primitive"
                            (ast-to-ir/schema-ast-to-ir (:value cargo)))))))

(deftest test-build-context-relationships
  (testing "build-context-relationships should extract context mappings"
    (let [schema-ast [:Schema 
                      [:CompositionLine 
                       [:Definee "Game"]
                       [:Element [:Sigil ":"] [:Type "PlayArea"]]
                       [:Element [:TypeMarker "Shape"]]]]
          result (ast-to-ir/build-context-relationships schema-ast)]
      (is (= "Game" (get result "PlayArea")))
      (is (nil? (get result "Game"))))))

(deftest test-build-interface-implementers
  (testing "build-interface-implementers should extract interface mappings"
    (let [schema-ast [:Schema 
                      [:DisjunctionLine 
                       [:Definee "Shape"]
                       [:Element [:TypeMarker "Triangle"]]
                       [:Element [:TypeMarker "Circle"]]]]
          result (ast-to-ir/build-interface-implementers schema-ast)]
      (is (= #{"Triangle" "Circle"} (get result "Shape"))))))

(deftest test-transform-composition-line
  (testing "transform-composition-line should create assemblage IR"
    (let [composition-line [:CompositionLine 
                           [:Definee "Player"]
                           [:Element [:TypeMarker "String"] "name"]
                           [:Element [:TypeMarker "Int"] "score"]]
          result (ast-to-ir/transform-composition-line composition-line)]
      (is (= "Player" (:name result)))
      (is (= 2 (count (:components result))))
      (is (= "string" (:component-name (first (:components result)))))
      (is (= "String" (:type-name (first (:components result))))))))

(deftest test-transform-disjunction-line
  (testing "transform-disjunction-line should create interface IR"
    (let [disjunction-line [:DisjunctionLine 
                           [:Definee "Shape"]
                           [:Element [:TypeMarker "Triangle"]]
                           [:Element [:TypeMarker "Circle"]]]
          result (ast-to-ir/transform-disjunction-line disjunction-line)]
      (is (= "Shape" (:name result)))
      (is (= ["Triangle" "Circle"] (:implementers result))))))

(deftest test-transform-enum-line
  (testing "transform-enum-line should create enum IR"
    (let [enum-line [:EnumLine 
                     [:Definee "Direction"]
                     [:EnumValue "North"]
                     [:EnumValue "South"]
                     [:EnumValue "East"]
                     [:EnumValue "West"]]
          result (ast-to-ir/transform-enum-line enum-line)]
      (is (= "Direction" (:name result)))
      (is (= ["North" "South" "East" "West"] (:values result))))))

(deftest test-schema-ast-to-ir
  (testing "schema-ast-to-ir should create complete schema IR"
    (let [schema-ast [:Schema 
                      [:CompositionLine 
                       [:Definee "Game"]
                       [:Element [:TypeMarker "String"] "name"]]
                      [:DisjunctionLine 
                       [:Definee "Shape"]
                       [:Element [:TypeMarker "Triangle"]]
                       [:Element [:TypeMarker "Circle"]]]
                      [:EnumLine 
                       [:Definee "Direction"]
                       [:EnumValue "North"]
                       [:EnumValue "South"]]]
          result (ast-to-ir/schema-ast-to-ir schema-ast)]
      ;; First, validate that the result conforms to the schema
      (when-not (schema/valid-schema-ir? result)
        (println "Schema validation failed for result:")
        (clojure.pprint/pprint result)
        (println "Schema explanation:")
        (clojure.pprint/pprint (schema/explain-schema-ir result)))
      (is (schema/valid-schema-ir? result) "Generated schema IR should conform to schema")
      (is (= 1 (count (:assemblages result))))
      (is (= 1 (count (:interfaces result))))
      (is (= 1 (count (:enums result))))
      (is (= "Game" (:name (first (:assemblages result)))))
      (is (= "Shape" (:name (first (:interfaces result)))))
      (is (= "Direction" (:name (first (:enums result))))))))

(deftest reserved-class-name-main
  (testing "schema class Main is reserved for the generated entry class"
    (let [cargo (parser/schema-wchnt->schema-ast "Main = Int/x\n")]
      (is (:success cargo))
      (is (thrown-with-msg? Exception #"Main"
                            (ast-to-ir/schema-ast-to-ir (:value cargo)))))))

;; =============================================================================
;; Construction AST to IR Tests
;; =============================================================================

(deftest test-extract-args-from-object-construction
  (testing "extract-args-from-object-construction should handle different argument types"
    (let [object-construction [:ObjectConstruction 
                              [:ClassName "Team"]
                              [:ArgList 
                               [:StringLiteral "West Ham"]
                               [:VariableRef "obj3"]]]
          schema-ir {:assemblages [{:name "Team"
                                   :components [{:component-name "name" :type-name "String"}
                                              {:component-name "players" :type-name "Array<Player>"}]}]}
          result (ast-to-ir/extract-args-from-object-construction object-construction schema-ir "Team")]
      (is (= 2 (count result)))
      (is (= {:type :primitive, :class-name "String", :value "West Ham", :args [], :index 0} (first result)))
      (is (= {:type :variable, :class-name "VariableRef", :value "obj3", :args [], :index 1} (second result))))))

(deftest test-extract-args-from-object-construction-with-enum
  (testing "extract-args-from-object-construction should identify enum values"
    (let [object-construction [:ObjectConstruction 
                              [:ClassName "Player"]
                              [:ArgList 
                               [:VariableRef "North"]]]
          schema-ir {:assemblages [{:name "Player"
                                   :components [{:component-name "direction" :type-name "Direction"}]}]
                     :enums [{:name "Direction" :values ["North" "South" "East" "West"]}]}
          result (ast-to-ir/extract-args-from-object-construction object-construction schema-ir "Player")]
      (is (= 1 (count result)))
      (is (= {:type :enum-value, :class-name "Enum", :value "North", :args [], :index 0} (first result))))))

(deftest test-extract-args-from-object-construction-with-inner-object
  (testing "extract-args-from-object-construction should handle inner object constructions"
    (let [object-construction [:ObjectConstruction 
                              [:ClassName "Game"]
                              [:ArgList 
                               [:InnerObjectConstruction 
                                [:ClassName "Player"]
                                [:ArgList [:StringLiteral "Alice"]]]]]
          schema-ir {:assemblages [{:name "Game"
                                   :components [{:component-name "player" :type-name "Player"}]}
                                  {:name "Player"
                                   :components [{:component-name "name" :type-name "String"}]}]}
          result (ast-to-ir/extract-args-from-object-construction object-construction schema-ir "Game")]
      (is (= 1 (count result)))
      (is (= {:type :object
              :class-name "Player"
              :args [{:type :primitive, :class-name "String", :value "Alice", :args [], :index 0}]
              :index 0}
             (update (first result) :args vec))))))

(deftest test-extract-args-from-object-construction-with-array
  (testing "extract-args-from-object-construction should handle array constructions"
    (let [object-construction [:ObjectConstruction 
                              [:ClassName "Team"]
                              [:ArgList 
                               [:ArrayConstruction 
                                [:Type "Player"]
                                [:ArgList [:StringLiteral "Alice"] [:StringLiteral "Bob"]]]]]
          schema-ir {:assemblages [{:name "Team"
                                   :components [{:component-name "players" :type-name "Array<Player>"}]}]}
          result (ast-to-ir/extract-args-from-object-construction object-construction schema-ir "Team")]
      (is (= 1 (count result)))
      (is (= {:type :array
              :class-name "Player"
              :args [{:type :primitive, :class-name "String", :value "Alice", :args [], :index 0}
                     {:type :primitive, :class-name "String", :value "Bob", :args [], :index 1}]
              :index 0}
             (update (first result) :args vec))))))

(deftest test-process-variable-ref-expression
  (testing "process-variable-ref-expression should resolve nested objects"
    (let [inner-expression [:VariableRef "obj1"]
          nested-objects {"obj1" {:type :object :class-name "Player" :args ["Alice"] :index 0 :ast [:ObjectConstruction [:ClassName "Player"] [:ArgList [:StringLiteral "Alice"]]]}}
          result (ast-to-ir/process-variable-ref-expression inner-expression nested-objects)]
      (is (= :object (:type result)))
      (is (= "Player" (:class-name result)))
      (is (= ["Alice"] (:args result)))
      (is (nil? (:ast result))))))

(deftest test-process-variable-ref-expression-fallback
  (testing "process-variable-ref-expression should fallback to variable reference"
    (let [inner-expression [:VariableRef "unknown"]
          nested-objects {}
          result (ast-to-ir/process-variable-ref-expression inner-expression nested-objects)]
      (is (= :variable (:type result)))
      (is (= "VariableRef" (:class-name result)))
      (is (= ["unknown"] (:args result))))))

(deftest test-process-array-construction-expression
  (testing "process-array-construction-expression should create array IR"
    (let [inner-expression [:ArrayConstruction 
                           [:Type "String"]
                           [:ArgList [:StringLiteral "hello"] [:StringLiteral "world"]]]
          result (ast-to-ir/process-array-construction-expression inner-expression)]
      (is (= :array (:type result)))
      (is (= "String" (:class-name result)))
      (is (= [:StringLiteral "hello"] (first (:args result))))
      (is (= [:StringLiteral "world"] (second (:args result)))))))

(deftest test-process-object-construction-expression
  (testing "process-object-construction-expression should create object IR"
    (let [inner-expression [:ObjectConstruction 
                           [:ClassName "Player"]
                           [:ArgList [:StringLiteral "Alice"] [:IntLiteral "100"]]]
          result (ast-to-ir/process-object-construction-expression inner-expression)]
      (is (= :object (:type result)))
      (is (= "Player" (:class-name result)))
      (is (= [:StringLiteral "Alice"] (first (:args result))))
      (is (= [:IntLiteral "100"] (second (:args result)))))))

(deftest test-process-map-construction-expression
  (testing "process-map-construction-expression should create map IR"
    (let [inner-expression [:MapConstruction 
                           [:KeyType "String"]
                           [:ValType "Int"]
                           [:KeyValueList 
                            [:KeyValuePair [:StringLiteral "a"] [:IntLiteral "1"]]
                            [:KeyValuePair [:StringLiteral "b"] [:IntLiteral "2"]]]]
          result (ast-to-ir/process-map-construction-expression inner-expression)]
      (is (= :map (:type result)))
      (is (= "Map<String, Int>" (:class-name result)))
      (is (= [{:type :primitive, :class-name "String", :value "a", :args [], :index 0}
              {:type :primitive, :class-name "Int", :value "1", :args [], :index 1}
              {:type :primitive, :class-name "String", :value "b", :args [], :index 2}
              {:type :primitive, :class-name "Int", :value "2", :args [], :index 3}]
             (vec (:args result)))))))

(deftest test-process-empty-map-construction-expression
  (testing "an empty map literal produces an empty map IR argument list"
    (let [result (ast-to-ir/process-map-construction-expression
                  [:MapConstruction [:KeyType "String"] [:ValType "Int"]])]
      (is (= :map (:type result)))
      (is (= "Map<String, Int>" (:class-name result)))
      (is (empty? (:args result))))))

(deftest test-process-assignment-expression
  (testing "process-assignment-expression should handle different expression types"
    (let [expression [:Expression [:VariableRef "obj1"]]
          nested-objects {"obj1" {:type :object :class-name "Player" :args ["Alice"]}}
          result (ast-to-ir/process-assignment-expression expression nested-objects)]
      (is (= :object (:type result)))
      (is (= "Player" (:class-name result))))))

(deftest test-extract-root-class-from-construction
  (testing "extract-root-class-from-construction should find root class"
    (let [construction-ast [:BlockStatements
                           [:Assignment [:VariableName "shapes"] [:Expression [:ArrayConstruction [:Type "Shape"] [:ArgList]]]]
                           [:Expression [:ObjectConstruction [:ClassName "Game"] [:ArgList [:VariableRef "shapes"]]]]]
          schema-ir {:assemblages [{:name "Game"} {:name "Shape"}]}
          result (ast-to-ir/extract-root-class-from-construction construction-ast schema-ir)]
      (is (= "Game" result)))))

(deftest test-construction-ast-to-ir-simple
  (testing "construction-ast-to-ir should create simple construction IR"
    (let [construction-ast [:BlockStatements
                           [:Expression [:ObjectConstruction [:ClassName "Game"] [:ArgList [:StringLiteral "Test"]]]]]
          schema-ir {:assemblages [{:name "Game" :components [{:component-name "name" :type-name "String"}]}]}
          result (ast-to-ir/construction-ast-to-ir construction-ast schema-ir)]
      ;; First, validate that the result conforms to the schema
      (is (schema/valid-construction-ir? result) "Generated construction IR should conform to schema")
      (is (= "Game" (:root-class result)))
      (is (= "factory" (:factory-name result)))
      (is (contains? result :objects))
      (is (contains? result :return-object))
      (is (contains? result :statements))
      ;; Validate that IR has structured arguments (not raw AST nodes)
      (let [structure-validation (schema/validate-ir-structure {:construction result})]
        (when-not (:valid structure-validation)
          (println "Structure validation failed:")
          (println "Objects in IR:")
          (clojure.pprint/pprint (:objects result)))
        (is (:valid structure-validation) (:message structure-validation))))))

(deftest test-construction-ast-to-ir-with-assignments
  (testing "construction-ast-to-ir should handle assignments"
    (let [construction-ast [:BlockStatements
                           [:Assignment [:VariableName "player"] [:Expression [:ObjectConstruction [:ClassName "Player"] [:ArgList [:StringLiteral "Alice"]]]]]
                           [:Expression [:ObjectConstruction [:ClassName "Game"] [:ArgList [:VariableRef "player"]]]]]
          schema-ir {:assemblages [{:name "Game" :components [{:component-name "player" :type-name "Player"}]}
                                  {:name "Player" :components [{:component-name "name" :type-name "String"}]}]}
          result (ast-to-ir/construction-ast-to-ir construction-ast schema-ir)]
      ;; First, validate that the result conforms to the schema
      (is (schema/valid-construction-ir? result) "Generated construction IR should conform to schema")
      (is (= "Game" (:root-class result)))
      ;; The current implementation doesn't populate statements with assignment data
      ;; Instead, assignments are tracked in variable-mappings and objects
      (is (= 0 (count (:statements result))))
      (is (contains? (:variable-mappings result) "player")))))

(deftest test-construction-ast-to-ir-with-arrays
  (testing "construction-ast-to-ir should handle array constructions"
    (let [construction-ast [:BlockStatements
                           [:Expression [:ObjectConstruction 
                                        [:ClassName "Team"] 
                                        [:ArgList [:ArrayConstruction 
                                                  [:Type "Player"] 
                                                  [:ArgList [:StringLiteral "Alice"] 
                                                           [:StringLiteral "Bob"]]]]]]]
          schema-ir {:assemblages [{:name "Team" :components [{:component-name "players" :type-name "Array<Player>"}]}
                                  {:name "Player" :components [{:component-name "name" :type-name "String"}]}]}
          result (ast-to-ir/construction-ast-to-ir construction-ast schema-ir)]
      ;; First, validate that the result conforms to the schema
      (is (schema/valid-construction-ir? result) "Generated construction IR should conform to schema")
      (is (= "Team" (:root-class result)))
      (is (contains? result :objects))
      (is (contains? result :return-object)))))

(deftest test-construction-ast-to-ir-with-maps
  (testing "construction-ast-to-ir should handle map constructions"
    (let [construction-ast [:BlockStatements
                           [:Expression [:ObjectConstruction 
                                        [:ClassName "Config"] 
                                        [:ArgList [:MapConstruction 
                                                  [:KeyType "String"] 
                                                  [:ValType "Int"] 
                                                  [:KeyValueList [:KeyValuePair 
                                                                 [:StringLiteral "a"] 
                                                                 [:IntLiteral "1"]]]]]]]]
          schema-ir {:assemblages [{:name "Config" :components [{:component-name "settings" :type-name "Map<String, Int>"}]}]}
          result (ast-to-ir/construction-ast-to-ir construction-ast schema-ir)]
      ;; First, validate that the result conforms to the schema
      (is (schema/valid-construction-ir? result) "Generated construction IR should conform to schema")
      (is (= "Config" (:root-class result)))
      (let [config-obj (get-in result [:objects (:return-object result)])
            settings-ref (first (:args config-obj))
            settings-arg (get-in result [:objects (:value settings-ref)])]
        (is (= :variable (:type settings-ref)))
        (is (= :map (:type settings-arg)))
        (is (= "Map<String, Int>" (:class-name settings-arg)))
        (is (= [{:type :primitive, :class-name "String", :value "a", :args [], :index 0}
                {:type :primitive, :class-name "Int", :value "1", :args [], :index 1}]
               (vec (:args settings-arg)))))
      (is (contains? result :objects))
      (is (contains? result :return-object)))))

;; =============================================================================
;; Integration Tests
;; =============================================================================

(deftest test-full-construction-pipeline
  (testing "Full construction AST to IR pipeline should work end-to-end"
    (let [construction-text "[:Game [:Player \"Alice\" 100] [:Player \"Bob\" 85]]"
          construction-cargo (parser/parse-construction-unified construction-text)
          construction-ast (:value construction-cargo)
          schema-ir {:assemblages [{:name "Game" :components [{:component-name "player1" :type-name "Player"}
                                                             {:component-name "player2" :type-name "Player"}]}
                                  {:name "Player" :components [{:component-name "name" :type-name "String"}
                                                             {:component-name "score" :type-name "Int"}]}]}
          result (ast-to-ir/construction-ast-to-ir construction-ast schema-ir)]
      (is (:success construction-cargo))
      ;; First, validate that the result conforms to the schema
      (is (schema/valid-construction-ir? result) "Generated construction IR should conform to schema")
      (is (= "Game" (:root-class result)))
      (is (contains? result :objects))
      (is (contains? result :return-object))
      (is (contains? result :statements)))))

;; =============================================================================
;; Type Inference Tests
;; =============================================================================

(deftest test-inner-object-type-inference-from-parent
  (testing "InnerObjectConstruction should infer type from parent context"
    (let [construction-ast [:BlockStatements
                           [:Expression [:ObjectConstruction 
                                        [:ClassName "Game"] 
                                        [:ArgList [:InnerObjectConstruction 
                                                  [:ArgList [:IntLiteral "10"] 
                                                           [:IntLiteral "20"]]]]]]]
          schema-ir {:assemblages [{:name "Game" :components [{:component-name "player" :type-name "Player"}]}
                                  {:name "Player" :components [{:component-name "x" :type-name "Int"}
                                                             {:component-name "y" :type-name "Int"}]}]}
          result (ast-to-ir/construction-ast-to-ir construction-ast schema-ir)]
      ;; First, validate that the result conforms to the schema
      (is (schema/valid-construction-ir? result) "Generated construction IR should conform to schema")
      (is (= "Game" (:root-class result)))
      ;; Check that the inner object was correctly inferred as Player
      (let [objects (:objects result)
            inner-object (first (vals objects))]
        (is (= "Player" (:class-name inner-object)) "Inner object should be inferred as Player")))))

(deftest test-inner-object-type-inference-from-array
  (testing "InnerObjectConstruction inside ArrayConstruction should infer type from array element type"
    (let [construction-ast [:BlockStatements
                           [:Expression [:ObjectConstruction 
                                        [:ClassName "Game"] 
                                        [:ArgList [:ArrayConstruction 
                                                  [:Type "Player"] 
                                                  [:ArgList [:InnerObjectConstruction 
                                                            [:ArgList [:IntLiteral "10"] 
                                                                     [:IntLiteral "20"]]]
                                                           [:InnerObjectConstruction 
                                                            [:ArgList [:IntLiteral "30"] 
                                                                     [:IntLiteral "40"]]]]]]]]]
          schema-ir {:assemblages [{:name "Game" :components [{:component-name "players" :type-name "Array<Player>"}]}
                                  {:name "Player" :components [{:component-name "x" :type-name "Int"}
                                                             {:component-name "y" :type-name "Int"}]}]}
          result (ast-to-ir/construction-ast-to-ir construction-ast schema-ir)]
      ;; First, validate that the result conforms to the schema
      (is (schema/valid-construction-ir? result) "Generated construction IR should conform to schema")
      ;; ArrayConstruction is represented as one array object containing Player args.
      (let [objects (:objects result)
            array-object (first (filter #(= :array (:type (val %))) objects))
            player-objects (:args (val array-object))]
        (is (= 2 (count player-objects)) "Should have exactly 2 Player entries")
        (doseq [obj player-objects]
          (is (= "Player" (:class-name obj)) 
              "Array element should be inferred as Player"))))))

(deftest test-inner-object-type-inference-multi-layer
  (testing "InnerObjectConstruction should infer type through multiple layers of parent context"
    (let [construction-ast [:BlockStatements
                           [:Expression [:ObjectConstruction 
                                        [:ClassName "A"] 
                                        [:ArgList [:InnerObjectConstruction 
                                                  [:ArgList [:IntLiteral "2"]]]]]]]
          schema-ir {:assemblages [{:name "A" :components [{:component-name "b" :type-name "B"}]}
                                  {:name "B" :components [{:component-name "c" :type-name "C"}]}
                                  {:name "C" :components [{:component-name "d" :type-name "Int"}]}]}
          result (ast-to-ir/construction-ast-to-ir construction-ast schema-ir)]
      ;; First, validate that the result conforms to the schema
      (is (schema/valid-construction-ir? result) "Generated construction IR should conform to schema")
      (is (= "A" (:root-class result)))
      ;; Check that the inner object was correctly inferred as B (not C or Int)
      (let [objects (:objects result)
            inner-object (first (vals objects))]
        (is (= "B" (:class-name inner-object)) "Inner object should be inferred as B from A's component")))))

(deftest test-inner-object-type-inference-nested-arrays
  (testing "InnerObjectConstruction inside nested arrays should infer correct type"
    (let [construction-ast [:BlockStatements
                           [:Expression [:ObjectConstruction 
                                        [:ClassName "League"] 
                                        [:ArgList [:ArrayConstruction 
                                                  [:Type "Team"] 
                                                  [:ArgList [:InnerObjectConstruction 
                                                            [:ArgList [:StringLiteral "Team1"]
                                                                     [:ArrayConstruction 
                                                                      [:Type "Player"] 
                                                                      [:ArgList [:InnerObjectConstruction 
                                                                                [:ArgList [:IntLiteral "10"] 
                                                                                         [:IntLiteral "20"]]]]]]]]]]]]]
          schema-ir {:assemblages [{:name "League" :components [{:component-name "teams" :type-name "Array<Team>"}]}
                                  {:name "Team" :components [{:component-name "name" :type-name "String"}
                                                           {:component-name "players" :type-name "Array<Player>"}]}
                                  {:name "Player" :components [{:component-name "x" :type-name "Int"}
                                                             {:component-name "y" :type-name "Int"}]}]}
          result (ast-to-ir/construction-ast-to-ir construction-ast schema-ir)]
      ;; First, validate that the result conforms to the schema
      (is (schema/valid-construction-ir? result) "Generated construction IR should conform to schema")
      ;; Check that objects were correctly inferred
      (let [objects (:objects result)]
        (doseq [[obj-id obj] objects]
          (when (= :object (:type obj))
                (cond
              (= "Team" (:class-name obj))
              (is true "Team object correctly identified")
              (= "Player" (:class-name obj))
              (is true "Player object correctly identified")
              (= "League" (:class-name obj))
              (is true "League object correctly identified")
                  :else
              (is false (str "Unexpected object type: " (:class-name obj))))))))))

(deftest test-nested-array-inner-object-is-element-type
  (testing "untagged [40 90 Bob] inside Array/Player is a Player, not a field of Team"
    (let [construction-ast [:BlockStatements
                            [:Expression
                             [:ObjectConstruction
                              [:ClassName "Team"]
                              [:ArgList
                               [:StringLiteral "Palace"]
                               [:ArrayConstruction
                                [:Type "Player"]
                                [:ArgList
                                 [:InnerObjectConstruction
                                  [:ArgList
                                   [:IntLiteral "40"]
                                   [:IntLiteral "90"]
                                   [:StringLiteral "Bob"]]]]]]]]]
          schema-ir {:assemblages [{:name "Team"
                                    :components [{:component-name "name" :type-name "String" :relationship :ordinary :optional-name nil}
                                                 {:component-name "players" :type-name "Array<Player>" :relationship :ordinary :optional-name nil}]}
                                   {:name "Player"
                                    :components [{:component-name "x" :type-name "Int" :relationship :ordinary :optional-name nil}
                                                 {:component-name "y" :type-name "Int" :relationship :ordinary :optional-name nil}
                                                 {:component-name "name" :type-name "String" :relationship :ordinary :optional-name nil}]}]
                     :enums [] :interfaces [] :context-relationships {}
                     :interface-implementers {} :observable-classes [] :subscriber-classes [] :debug-methods []}
          result (ast-to-ir/construction-ast-to-ir construction-ast schema-ir)
          nodes (tree-seq coll? seq result)
          player-nodes (filter #(and (map? %)
                                     (= :object (:type %))
                                     (= "Player" (:class-name %)))
                               nodes)]
      (is (seq player-nodes) "IR should contain a Player object for Bob"))))

(deftest test-inner-object-type-inference-with-explicit-class
  (testing "InnerObjectConstruction with explicit class name should use that instead of inferring"
    (let [construction-ast [:BlockStatements
                           [:Expression [:ObjectConstruction 
                                        [:ClassName "Game"] 
                                        [:ArgList [:InnerObjectConstruction 
                                                  [:ClassName "Player"]
                                                  [:ArgList [:IntLiteral "10"] 
                                                           [:IntLiteral "20"]]]]]]]
          schema-ir {:assemblages [{:name "Game" :components [{:component-name "shape" :type-name "Shape"}]}
                                  {:name "Player" :components [{:component-name "x" :type-name "Int"}
                                                             {:component-name "y" :type-name "Int"}]}]}
          result (ast-to-ir/construction-ast-to-ir construction-ast schema-ir)]
      ;; First, validate that the result conforms to the schema
      (is (schema/valid-construction-ir? result) "Generated construction IR should conform to schema")
      (is (= "Game" (:root-class result)))
      ;; Check that the inner object used explicit class name instead of inferring from parent
      (let [objects (:objects result)
            inner-object (first (vals objects))]
        (is (= "Player" (:class-name inner-object)) "Inner object should use explicit Player class name")))))
