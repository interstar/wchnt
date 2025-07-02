(ns wchnt-lang.parser
  (:require [instaparse.core :as insta]
            [clojure.core :refer [bean]]
            [clojure.string :as str]
            [wchnt-lang.schema :as schema]))

(def grammar
  "Schema = DefLine (<NL> DefLine)* <NL>?
   DefLine = CompositionLine | DisjunctionLine | EnumLine
   CompositionLine = Definee <SPACE> '=' <SPACE> Element (<SPACE> Element)* <SPACE>?
   DisjunctionLine = Definee <SPACE> '=' <SPACE> Element (<SPACE> '|' <SPACE> Element)+ <SPACE>?
   EnumLine = Definee <SPACE> '=' <SPACE> '\"' EnumValue '\"' (<SPACE> '|' <SPACE> '\"' EnumValue '\"')+ <SPACE>?
   Definee = Name
   <Name> = #'[A-Za-z][A-Za-z0-9_]*'
   NL = #'\\n+'
   Element = TypeMarker ('/' AltName)?
   SPACE = #'\\s+'
   TypeMarker = Name | ArrayType | DictType
   ArrayType = '[' Type ']'
   Type = Name
   DictType = '{' <SPACE>? KeyType <SPACE>? ':' <SPACE>? DValType <SPACE>? '}'
   KeyType = Name 
   DValType = Name
   AltName = Name
   EnumValue =  #'[^\"]+'
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
  (let [type-marker-node (find-node :TypeMarker children)
        alt-name-node (find-node :AltName children)
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
               :name (or alt-name (when base-type-name (str (clojure.string/lower-case (subs base-type-name 0 1)) (subs base-type-name 1))))}]
    result))

(defn parse-input [input]
  (let [parse-result ((get-parser) input)]
    (if (insta/failure? parse-result)
      (schema/syntax-error (str (insta/get-failure parse-result)))
      (schema/syntax-success parse-result)))) 