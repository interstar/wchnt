(ns wchnt-lang.parser
  (:require [instaparse.core :as insta]
            [clojure.string :as str]
            [wchnt-lang.pipeline :as P]
            [wchnt-lang.schema :as schema]
            [wchnt-lang.grammars :as grammars])
  (:import (java.lang Exception)))

;; Use the consolidated grammar from grammars.cljc

(defn get-schema-parser []
  "Get the schema parser for parsing WCHNT schema definitions"
  grammars/schema-parser)


(defn find-node [tag tree]
  (cond
    (and (vector? tree) (= (first tree) tag)) tree
    (vector? tree) (or (some #(find-node tag %) (rest tree))
                       (when (= (first tree) :ArrayType)
                         (find-node tag (rest tree)))
                       (when (= (first tree) :MapType)
                         (find-node tag (rest tree))))
    (seq? tree) (some #(find-node tag %) tree)
    :else nil))

(defn camel-case [s]
  (let [parts (clojure.string/split s #"_")]
    (str (clojure.string/lower-case (first parts))
         (apply str (map clojure.string/capitalize (rest parts))))))

(defn process-element [children]
  (let [sigil-node (find-node :Sigil children)
        type-marker-node (find-node :TypeMarker children)
        type-node (find-node :Type children)
        alt-name-node (find-node :AltName children)
        sigil (when sigil-node (second sigil-node))
        type-name (cond
                   ;; MapType (from TypeMarker) - convert to Map<Key,Value> format
                   (and type-marker-node (= (first (first (rest type-marker-node))) :MapType))
                   (let [map-node (first (rest type-marker-node))
                         key-type-node (find-node :KeyType map-node)
                         val-type-node (find-node :ValType map-node)
                         key-type (when key-type-node (second key-type-node))
                         val-type (cond
                                  ;; If val-type-node is an ArrayType, convert to Array<Type> format
                                  (and val-type-node (vector? (second val-type-node)) 
                                       (= (first (second val-type-node)) :ArrayType))
                                  (let [array-node (second val-type-node)
                                        inner-type-node (find-node :Type (rest array-node))
                                        inner-type (when inner-type-node (second inner-type-node))]
                                    (str "Array<" inner-type ">"))
                                  ;; Otherwise use the value directly
                                  val-type-node (second val-type-node)
                                  :else nil)]
                     (str "Map<" key-type ", " val-type ">"))
                   ;; ArrayType (from TypeMarker) - convert to Array<Type> format
                   (and type-marker-node (= (first (first (rest type-marker-node))) :ArrayType))
                   (let [array-node (first (rest type-marker-node))
                         inner-type-node (find-node :Type (rest array-node))
                         inner-type (when inner-type-node (second inner-type-node))]
                     (str "Array<" inner-type ">"))
                   ;; EmptyType (from TypeMarker)
                   (and type-marker-node (= (first (first (rest type-marker-node))) :EmptyType))
                   "_Empty"
                   ;; Simple Type from TypeMarker (string or :Name)
                   (and type-marker-node (string? (second type-marker-node)))
                   (second type-marker-node)
                   (and type-marker-node (= (first (first (rest type-marker-node))) :Name))
                   (second (first (rest type-marker-node)))
                   ;; Simple Type from (Sigil? Type) structure
                   (and type-node (string? (second type-node)))
                   (second type-node)
                   ;; Fallback
                   :else nil)
        alt-name (when alt-name-node (second alt-name-node))
        base-type-name (cond
                        ;; MapType (from TypeMarker)
                        (and type-marker-node (= (first (first (rest type-marker-node))) :MapType))
                        (let [map-node (first (rest type-marker-node))
                              key-type-node (find-node :KeyType map-node)
                              val-type-node (find-node :ValType map-node)
                              key-type (when key-type-node (second key-type-node))
                              val-type (cond
                                       ;; If val-type-node is an ArrayType, extract the inner type
                                       (and val-type-node (vector? (second val-type-node)) 
                                            (= (first (second val-type-node)) :ArrayType))
                                       (second (find-node :Type (rest (second val-type-node))))
                                       ;; Otherwise use the value directly
                                       val-type-node (second val-type-node)
                                       :else nil)]
                          (str (camel-case key-type) "To" (clojure.string/capitalize (camel-case val-type))))
                        ;; ArrayType (from TypeMarker)
                        (and type-marker-node (= (first (first (rest type-marker-node))) :ArrayType))
                        (let [array-node (first (rest type-marker-node))]
                          (second (find-node :Type (rest array-node))))
                        ;; EmptyType (from TypeMarker)
                        (and type-marker-node (= (first (first (rest type-marker-node))) :EmptyType))
                        "_Empty"
                        ;; Simple Type from TypeMarker (string or :Name)
                        (and type-marker-node (string? (second type-marker-node)))
                        (second type-marker-node)
                        (and type-marker-node (= (first (first (rest type-marker-node))) :Name))
                        (second (first (rest type-marker-node)))
                        ;; Simple Type from (Sigil? Type) structure
                        (and type-node (string? (second type-node)))
                        (second type-node)
                        :else nil)
        ;; Extract key and value types for MapType
        key-type (when (and type-marker-node (= (first (first (rest type-marker-node))) :MapType))
                   (let [map-node (first (rest type-marker-node))
                         key-type-node (find-node :KeyType map-node)]
                     (when key-type-node (second key-type-node))))
        value-type (when (and type-marker-node (= (first (first (rest type-marker-node))) :MapType))
                    (let [map-node (first (rest type-marker-node))
                          val-type-node (find-node :ValType map-node)]
                      (when val-type-node (second val-type-node))))
        result {:type type-name
               :name (or alt-name (when base-type-name (str (clojure.string/lower-case (subs base-type-name 0 1)) (subs base-type-name 1))))
               :sigil sigil
               :key-type key-type
               :value-type value-type}]
    result))

(defn schema-wchnt->schema-ast [input]
  (let [parse-result ((get-schema-parser) input)]
    (if (insta/failure? parse-result)
      (P/fail-cargo (str "Schema parsing failed: " (insta/get-failure parse-result)))
      (P/success-cargo parse-result))))



(defn extract-class-info [ast]
  "Extract class information from parsed schema AST"
  (let [class-list (atom [])
        enum-list (atom [])
        disjunction-list (atom [])
        interface-implementers (atom {})]
    (letfn [(walk-tree [node]
              (cond
                (string? node) nil
                (vector? node)
                (let [[tag & children] node]
                  (case tag
                    :Schema (doseq [child (filter vector? children)]
                              (walk-tree child))
                    :DefLine (walk-tree (first (filter vector? children)))
                    :CompositionLine
                    (let [definee-node (first (filter #(= (first %) :Definee) children))
                          class-name (second definee-node)
                          element-nodes (filter #(= (first %) :Element) children)
                          processed-elements (map process-element element-nodes)]
                      (swap! class-list conj {:name class-name
                                             :elements processed-elements
                                             :type :composition}))
                    :DisjunctionLine
                    (let [definee-node (first (filter #(= (first %) :Definee) children))
                          interface-name (second definee-node)
                          element-nodes (filter #(= (first %) :Element) children)
                          processed-elements (map process-element element-nodes)
                          type-names (map :type processed-elements)
                          has-empty-type (some #(= % "_Empty") type-names)
                          ;; Replace _Empty with the actual interface name for empty types
                          processed-type-names (map #(if (= % "_Empty") 
                                                       (str "_" interface-name) 
                                                       %) type-names)]
                      ;; Track which classes implement this interface
                      (doseq [implementer processed-type-names]
                        (swap! interface-implementers update implementer (fnil conj []) interface-name))
                      ;; Add empty class if needed
                      (when has-empty-type
                        (let [empty-class-name (str "_" interface-name)]
                          (swap! class-list conj {:name empty-class-name
                                                 :elements []
                                                 :type :empty
                                                 :implements [interface-name]})))
                      (swap! disjunction-list conj {:name interface-name
                                                   :implementers processed-type-names
                                                   :has-empty-type has-empty-type
                                                   :type :disjunction}))
                    :EnumLine
                    (let [definee-node (first (filter #(= (first %) :Definee) children))
                          enum-name (second definee-node)
                          enum-value-nodes (filter #(= (first %) :EnumValue) children)
                          enum-values (map second enum-value-nodes)]
                      (swap! enum-list conj {:name enum-name
                                            :values enum-values
                                            :type :enum}))
                    nil))
                :else nil))]
      (walk-tree ast)
      {:classes @class-list
       :enums @enum-list
       :disjunctions @disjunction-list
       :interface-implementers @interface-implementers})))

;; Platform literal type management
(defn primitive-type->literal-rule [type-name]
  "Convert a primitive type name to its corresponding grammar literal rule name"
  (case type-name
    "String" "StringLiteral"
    "Int" "IntLiteral"
    "Float" "FloatLiteral"
    "Bool" "BoolLiteral"
    nil))

(defn element-type-to-rule [element-type enums]
  "Convert an element type to its corresponding grammar rule name"
  (cond
    (primitive-type->literal-rule element-type) (primitive-type->literal-rule element-type)
    (str/starts-with? element-type "Array<") 
    (let [inner-type (str/replace (str/replace element-type "Array<" "") ">" "")
          inner-rule (element-type-to-rule inner-type enums)]
      (str "ArrayConstruction"))
    (str/starts-with? element-type "Map<") "MapConstruction"
    ;; Check if it's an enum type
    (some #(= (:name %) element-type) enums) (str element-type "Value")
    :else (str element-type "Construction")))

(defn generate-strict-arg-rules [elements enums disjunctions classes]
  "Generate strict grammar rules for class elements (with R prefix)"
  (for [element elements]
    (let [element-type (:type element)
          expected-rule (cond
                         (primitive-type->literal-rule element-type) (primitive-type->literal-rule element-type)
                         (str/starts-with? element-type "Array<") 
                         (let [inner-type (str/replace (str/replace element-type "Array<" "") ">" "")
                               element-rule (cond
                                            (primitive-type->literal-rule inner-type) (primitive-type->literal-rule inner-type)
                                            (some #(= (:name %) inner-type) enums) (str inner-type "Value")
                                            (some #(= (:name %) inner-type) (map :name disjunctions))
                                            (let [disjunction (first (filter #(= (:name %) inner-type) disjunctions))
                                                  implementers (:implementers disjunction)]
                                              (str "(" (str/join " | " (map #(str "R" % "Construction") implementers)) ")"))
                                            :else (str "R" inner-type "Construction"))
                               ;; Generate type-specific array construction rule name
                               array-construction-rule (str inner-type "ArrayConstruction")
                               ;; Only include array element rule if the inner type is actually used as an array element
                               array-element-types (->> (for [class classes
                                                              element (:elements class)
                                                              :when (str/starts-with? (:type element) "Array<")]
                                                          (-> (:type element)
                                                              (str/replace "Array<" "")
                                                              (str/replace ">" "")))
                                                        distinct)
                               has-array-element-rule (some #(= % inner-type) array-element-types)
                               ;; For disjunctions, we need to reference the implementer array elements
                               array-element-rule (cond
                                                   (and has-array-element-rule (some #(= (:name %) inner-type) disjunctions))
                                                   (let [disjunction (first (filter #(= (:name %) inner-type) disjunctions))
                                                         implementers (:implementers disjunction)]
                                                     (str "(" (str/join " | " (map #(str % "ArrayElement") implementers)) ")"))
                                                   has-array-element-rule
                                                   (str inner-type "ArrayElement")
                                                   :else nil)]
                           (if array-element-rule
                             (str array-construction-rule " | (<WS>+ (" (str/join " | " [array-element-rule element-rule]) "))*")
                             (str array-construction-rule " | (<WS>+ " element-rule ")*")))
                         (str/starts-with? element-type "Map<") 
                         (let [key-type (:key-type element)
                               value-type (:value-type element)
                               map-rule-name (str key-type "To" (clojure.string/capitalize value-type) "MapConstruction")]
                           (str "(MapConstruction | " map-rule-name ")"))
                         (some #(= (:name %) element-type) enums) (str element-type "Value")
                         (some #(= (:name %) element-type) (map :name disjunctions))
                         (let [disjunction (first (filter #(= (:name %) element-type) disjunctions))
                               implementers (:implementers disjunction)
                               has-empty-type (:has-empty-type disjunction)]
                           (if has-empty-type
                             (str "(" (str/join " | " (map #(str "R" % "Construction") implementers)) " | LocalEmpty)")
                             (str "(" (str/join " | " (map #(str "R" % "Construction") implementers)) ")")))
                         :else (str "R" element-type "Construction"))]
      (str "(" expected-rule " | VariableReference)"))))




(defn find-all-nodes [tag tree]
  "Find all nodes in the tree that start with the given tag. Returns a sequence of matching nodes."
  (cond
    (and (vector? tree) (= (first tree) tag)) 
    [tree]
    
    (vector? tree) 
    (find-all-nodes tag (rest tree))
    
    (seq? tree) 
    (mapcat #(find-all-nodes tag %) tree)
    
    :else []))

(defn parse-construction-unified [construction-text]
  "Parse construction using the consolidated grammar.
  Input: construction text string
  Output: Cargo with parsed AST or error"
  (let [result (grammars/parse-construction-with-failure-handling construction-text)]
    (if (:success result)
      (P/success-cargo (:ast result))
      (P/fail-cargo ["Construction parsing failed: " (:error result)]))))


