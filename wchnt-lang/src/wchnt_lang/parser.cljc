(ns wchnt-lang.parser
  (:require [instaparse.core :as insta]
            [clojure.core :refer [bean]]
            [clojure.string :as str]
            [wchnt-lang.schema :as schema]))

(def grammar
  "Schema = DefLine (<NL> DefLine)*
   DefLine = CompositionLine | DisjunctionLine | EnumLine
   CompositionLine = Definee <SPACE> '=' <SPACE> Element (<SPACE> Element)* <SPACE>?
   DisjunctionLine = Definee <SPACE> '=' <SPACE> Element (<SPACE> '|' <SPACE> Element)+ <SPACE>?
   EnumLine = Definee <SPACE> '=' <SPACE> '\"' EnumValue '\"' (<SPACE> '|' <SPACE> '\"' EnumValue '\"')+ <SPACE>?
   Definee = Name
   <Name> = #'[A-Za-z][A-Za-z0-9_]*'
   NL = #'\\n+'
   Element = Type ('/' AltName)?
   SPACE = #'\\s+'
   Type = ArrayType | Name
   ArrayType = '[' Type ']'
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
                         (find-node tag (rest tree))))
    (seq? tree) (some #(find-node tag %) tree)
    :else nil))

(defn process-element [children]
  (let [type-node (or (find-node :Type children)
                      (find-node :ArrayType children))
        alt-name-node (find-node :AltName children)
        type-name (cond
                   ;; Check if this is an ArrayType node directly
                   (= (first type-node) :ArrayType)
                   (let [inner-type (second (find-node :Type (rest type-node)))]
                     (str "Array<" inner-type ">"))
                   ;; Check if this is a Type node that contains an ArrayType
                   (and (= (first type-node) :Type)
                        (find-node :ArrayType type-node))
                   (let [array-type-node (find-node :ArrayType type-node)
                         inner-type (second (find-node :Type (rest array-type-node)))]
                     (str "Array<" inner-type ">"))
                   ;; Regular type
                   :else
                   (second type-node))
        alt-name (when alt-name-node (second alt-name-node))
        base-type-name (cond
                        ;; Check if this is an ArrayType node directly
                        (= (first type-node) :ArrayType)
                        (second (find-node :Type (rest type-node)))
                        ;; Check if this is a Type node that contains an ArrayType
                        (and (= (first type-node) :Type)
                             (find-node :ArrayType type-node))
                        (let [array-type-node (find-node :ArrayType type-node)]
                          (second (find-node :Type (rest array-type-node))))
                        ;; Regular type
                        :else
                        (second type-node))
        result {:type type-name
               :name (or alt-name (str (str/lower-case (subs base-type-name 0 1)) (subs base-type-name 1)))}]
    result))

(defn parse-input [input]
  (let [parse-result ((get-parser) input)]
    (if (insta/failure? parse-result)
      (schema/syntax-error (str (insta/get-failure parse-result)))
      (schema/syntax-success parse-result)))) 