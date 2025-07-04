(ns wchnt-lang.parser
  (:require [instaparse.core :as insta]
            [clojure.core :refer [bean]]
            [clojure.string :as str]
            [wchnt-lang.schema :as schema])
  (:import (java.lang Exception)))

(def grammar
  "Schema = DefLine (<NL> DefLine)* <NL>?
   DefLine = CompositionLine | DisjunctionLine | EnumLine
   CompositionLine = Definee <SPACE> '=' <SPACE> Element (<SPACE> Element)* <SPACE>?
   DisjunctionLine = Definee <SPACE> '=' <SPACE> Element (<SPACE> '|' <SPACE> Element)+ <SPACE>?
   EnumLine = Definee <SPACE> '=' <SPACE> '\"' EnumValue '\"' (<SPACE> '|' <SPACE> '\"' EnumValue '\"')+ <SPACE>?
   Definee = Name
   <Name> = #'[A-Za-z][A-Za-z0-9_]*'
   NL = #'\\n+'
   Element = Sigil? TypeMarker ('/' AltName)?
   SPACE = #'\\s+'
   TypeMarker = Name | ArrayType | DictType
   ArrayType = '[' Type ']'
   Type = Name
   DictType = '{' <SPACE>? KeyType <SPACE>? ':' <SPACE>? DValType <SPACE>? '}'
   KeyType = Name 
   DValType = Name
   AltName = Name
   EnumValue =  #'[^\"]+'
   Sigil = ':'  | '@' | '$'
   ")

(defn get-parser []
  (insta/parser grammar))

(defn find-node [tag tree]
  (cond
    (and (vector? tree) (= (first tree) tag)) tree
    (vector? tree) (or (some #(find-node tag %) (rest tree))
                       (when (= (first tree) :ArrayType)
                         (find-node tag (rest tree)))
                       (when (= (first tree) :DictType)
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
        alt-name-node (find-node :AltName children)
        sigil (when sigil-node (second sigil-node))
        type-name (cond
                   ;; DictType
                   (and type-marker-node (= (first (first (rest type-marker-node))) :DictType))
                   (let [dict-node (first (rest type-marker-node))
                         key-type-node (find-node :KeyType dict-node)
                         val-type-node (find-node :DValType dict-node)
                         key-type (when key-type-node (second key-type-node))
                         val-type (when val-type-node (second val-type-node))]
                     (str "Map<" key-type ", " val-type ">"))
                   ;; ArrayType
                   (and type-marker-node (= (first (first (rest type-marker-node))) :ArrayType))
                   (let [array-node (first (rest type-marker-node))
                         inner-type (second (find-node :Type (rest array-node)))]
                     (str "Array<" inner-type ">"))
                   ;; Simple Type (string or :Name)
                   (and type-marker-node (string? (second type-marker-node)))
                   (second type-marker-node)
                   (and type-marker-node (= (first (first (rest type-marker-node))) :Name))
                   (second (first (rest type-marker-node)))
                   ;; Fallback
                   :else nil)
        alt-name (when alt-name-node (second alt-name-node))
        base-type-name (cond
                        ;; DictType
                        (and type-marker-node (= (first (first (rest type-marker-node))) :DictType))
                        (let [dict-node (first (rest type-marker-node))
                              key-type-node (find-node :KeyType dict-node)
                              val-type-node (find-node :DValType dict-node)
                              key-type (when key-type-node (second key-type-node))
                              val-type (when val-type-node (second val-type-node))]
                          (str (camel-case key-type) "To" (clojure.string/capitalize (camel-case val-type))))
                        ;; ArrayType
                        (and type-marker-node (= (first (first (rest type-marker-node))) :ArrayType))
                        (let [array-node (first (rest type-marker-node))]
                          (second (find-node :Type (rest array-node))))
                        ;; Simple Type (string or :Name)
                        (and type-marker-node (string? (second type-marker-node)))
                        (second type-marker-node)
                        (and type-marker-node (= (first (first (rest type-marker-node))) :Name))
                        (second (first (rest type-marker-node)))
                        :else nil)
        result {:type type-name
               :name (or alt-name (when base-type-name (str (clojure.string/lower-case (subs base-type-name 0 1)) (subs base-type-name 1))))
               :sigil sigil}]
    result))

(defn parse-input [input]
  (let [parse-result ((get-parser) input)]
    (if (insta/failure? parse-result)
      (schema/syntax-error (str (insta/get-failure parse-result)))
      (schema/syntax-success parse-result))))

(defn extract-class-info [ast]
  "Extract class information from parsed schema AST"
  (let [class-list (atom [])
        enum-list (atom [])
        disjunction-list (atom [])]
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
                          type-names (map #(second (find-node :Type %)) element-nodes)]
                      (swap! disjunction-list conj {:name interface-name
                                                   :implementers type-names
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
       :disjunctions @disjunction-list})))

(defn generate-construction-grammar [class-info]
  "Generate Instaparse grammar for construction syntax based on class info"
  (let [classes (:classes class-info)
        enums (:enums class-info)
        disjunctions (:disjunctions class-info)]
    
    ;; Build class constructor rules
    (let [class-rules (for [class classes]
                        (let [class-name (:name class)
                              elements (:elements class)
                              arg-rules (for [element elements]
                                         (let [element-type (:type element)]
                                           (case element-type
                                             "String" "StringLiteral"
                                             "int" "IntLiteral"
                                             "Array<" "ArrayConstruction"
                                             "Map<" "MapConstruction"
                                             "LazyContext<" "LazyConstruction"
                                             "ContextAware" "ContextConstruction"
                                             (str "(" class-name "Construction)"))))]
                          (str class-name "Construction = '[' ':' '" class-name "' <SPACE>? " 
                               (str/join " <SPACE>+ " arg-rules) " <SPACE>? ']'")))
          
          enum-rules (for [enum enums]
                       (let [enum-name (:name enum)
                             enum-values (:values enum)
                             value-alternatives (str/join " | " (map #(str "'" % "'") enum-values))]
                         (str enum-name "Value = " value-alternatives)))
          
          disjunction-rules (for [disjunction disjunctions]
                              (let [interface-name (:name disjunction)
                                    implementers (:implementers disjunction)
                                    implementer-rules (str/join " | " (map #(str "(" % "Construction)") implementers))]
                                (str interface-name "Construction = " implementer-rules)))
          
                     ;; Base grammar template
           base-grammar "Construction = ConstructionLine (<NL> ConstructionLine)* <NL>?
ConstructionLine = MultiStepConstruction | SimpleConstruction
MultiStepConstruction = Name <SPACE>? '=' <SPACE>? SimpleConstruction
SimpleConstruction = ClassConstruction | ArrayConstruction | MapConstruction | ReferenceConstruction
ClassConstruction = '[' ':' Name <SPACE>? (Argument (<SPACE>+ Argument)*)? <SPACE>? ']'
ArrayConstruction = '[' ':' 'Array' '/' Name <SPACE>? '[' (SimpleConstruction (<SPACE>+ SimpleConstruction)*)? ']' <SPACE>? ']'
MapConstruction = '[' ':' 'Map' '/' Name <SPACE>? '{' (MapEntry (<SPACE>? ',' <SPACE>? MapEntry)*)? '}' <SPACE>? ']'
MapEntry = KeyValue ':' Value
KeyValue = StringLiteral | IntLiteral | Name
ReferenceConstruction = Name
Argument = NamedArgument | PositionalArgument
NamedArgument = Value '/' Name
PositionalArgument = Value
Value = StringLiteral | IntLiteral | Name | '_' | ClassConstruction | ArrayConstruction | MapConstruction
StringLiteral = '\"' #'[^\"]*' '\"'
IntLiteral = #'[0-9]+'
Name = #'[A-Za-z][A-Za-z0-9_]*'
SPACE = #'\\s+'
NL = #'\\n+'"
          
          ;; Combine all rules
          all-rules (concat [base-grammar] class-rules enum-rules disjunction-rules)]
      
      (str/join "\n" all-rules))))

(defn schema-to-construction-grammar [schema-input]
  "Convert WCHNT schema to construction grammar"
  (try
    (let [parse-result ((get-parser) schema-input)]
      (if (insta/failure? parse-result)
        (schema/syntax-error (str "Schema parsing failed: " (insta/get-failure parse-result)))
        (let [class-info (extract-class-info parse-result)
              grammar (generate-construction-grammar class-info)]
          (schema/syntax-success {:grammar grammar
                                 :class-info class-info}))))
    (catch Exception e
      (schema/syntax-error (str "Error generating construction grammar: " (.getMessage e))))))

(defn parse-construction [schema-input construction-input]
  "Parse construction syntax using schema-generated grammar"
  (try
    (let [grammar-result (schema-to-construction-grammar schema-input)]
      (if (not (:success grammar-result))
        grammar-result
        (let [grammar (:grammar (:ast grammar-result))
              construction-parser (insta/parser grammar)
              parse-result (construction-parser construction-input)]
          (if (insta/failure? parse-result)
            (schema/syntax-error (str "Construction parsing failed: " (insta/get-failure parse-result)))
            (schema/syntax-success parse-result)))))
    (catch Exception e
      (schema/syntax-error (str "Error parsing construction: " (.getMessage e)))))) 