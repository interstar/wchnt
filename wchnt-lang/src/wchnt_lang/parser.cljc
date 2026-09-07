(ns wchnt-lang.parser
  (:require [instaparse.core :as insta]
            [wchnt-lang.pipeline :as P]
            [wchnt-lang.grammars :as grammars]))

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

(defn- type-marker-tag
  [type-marker-node]
  (let [payload (first (rest type-marker-node))]
    (when (vector? payload)
      (first payload))))

(defn- map-val-type->type-name
  [val-type-node]
  (let [val (second val-type-node)]
    (cond
      (and (vector? val) (= (first val) :ArrayType))
      (let [array-node val
            inner-type-node (find-node :Type (rest array-node))
            inner-type (when inner-type-node (second inner-type-node))]
        (str "Array<" inner-type ">"))

      val-type-node
      val

      :else
      nil)))

(defn- map-val-type->base-type-name
  [val-type-node]
  (let [val (second val-type-node)]
    (if (and (vector? val) (= (first val) :ArrayType))
      (second (find-node :Type (rest val)))
      val)))

(defn- map-type->type-name
  [type-marker-node]
  (let [map-node (first (rest type-marker-node))
        key-type-node (find-node :KeyType map-node)
        val-type-node (find-node :ValType map-node)
        key-type (when key-type-node (second key-type-node))
        val-type (when val-type-node (map-val-type->type-name val-type-node))]
    (str "Map<" key-type ", " val-type ">")))

(defn- map-type->base-type-name
  [type-marker-node]
  (let [map-node (first (rest type-marker-node))
        key-type-node (find-node :KeyType map-node)
        val-type-node (find-node :ValType map-node)
        key-type (when key-type-node (second key-type-node))
        val-type (when val-type-node (map-val-type->base-type-name val-type-node))]
    (str (camel-case key-type) "To" (clojure.string/capitalize (camel-case val-type)))))

(defn- array-type->type-name
  [type-marker-node]
  (let [array-node (first (rest type-marker-node))
        inner-type-node (find-node :Type (rest array-node))
        inner-type (when inner-type-node (second inner-type-node))]
    (str "Array<" inner-type ">")))

(defn- array-type->base-type-name
  [type-marker-node]
  (let [array-node (first (rest type-marker-node))]
    (second (find-node :Type (rest array-node)))))

(defn- type-marker->type-name
  [type-marker-node]
  (cond
    (= (type-marker-tag type-marker-node) :MapType)
    (map-type->type-name type-marker-node)

    (= (type-marker-tag type-marker-node) :ArrayType)
    (array-type->type-name type-marker-node)

    (= (type-marker-tag type-marker-node) :EmptyType)
    "_Empty"

    (string? (second type-marker-node))
    (second type-marker-node)

    (= (type-marker-tag type-marker-node) :Name)
    (second (first (rest type-marker-node)))

    :else
    nil))

(defn- type-marker->base-type-name
  [type-marker-node]
  (cond
    (= (type-marker-tag type-marker-node) :MapType)
    (map-type->base-type-name type-marker-node)

    (= (type-marker-tag type-marker-node) :ArrayType)
    (array-type->base-type-name type-marker-node)

    (= (type-marker-tag type-marker-node) :EmptyType)
    "_Empty"

    (string? (second type-marker-node))
    (second type-marker-node)

    (= (type-marker-tag type-marker-node) :Name)
    (second (first (rest type-marker-node)))

    :else
    nil))

(defn- type-marker->key-type
  [type-marker-node]
  (when (= (type-marker-tag type-marker-node) :MapType)
    (let [map-node (first (rest type-marker-node))
          key-type-node (find-node :KeyType map-node)]
      (when key-type-node (second key-type-node)))))

(defn- type-marker->value-type
  [type-marker-node]
  (when (= (type-marker-tag type-marker-node) :MapType)
    (let [map-node (first (rest type-marker-node))
          val-type-node (find-node :ValType map-node)]
      (when val-type-node (second val-type-node)))))

(defn- lower-camel-from-base-type
  [base-type-name]
  (when base-type-name
    (str (clojure.string/lower-case (subs base-type-name 0 1)) (subs base-type-name 1))))

(defn process-element [children]
  (let [sigil-node (find-node :Sigil children)
        type-marker-node (find-node :TypeMarker children)
        type-node (find-node :Type children)
        alt-name-node (find-node :AltName children)
        sigil (when sigil-node (second sigil-node))
        type-name (or (when type-marker-node (type-marker->type-name type-marker-node))
                      (when (and type-node (string? (second type-node))) (second type-node)))
        alt-name (when alt-name-node (second alt-name-node))
        base-type-name (or (when type-marker-node (type-marker->base-type-name type-marker-node))
                           (when (and type-node (string? (second type-node))) (second type-node)))
        key-type (when type-marker-node (type-marker->key-type type-marker-node))
        value-type (when type-marker-node (type-marker->value-type type-marker-node))
        result {:type type-name
               :name (or alt-name (lower-camel-from-base-type base-type-name))
               :sigil sigil
               :key-type key-type
               :value-type value-type}]
    result))

(defn schema-wchnt->schema-ast [input]
  (let [parse-result ((get-schema-parser) input)]
    (if (insta/failure? parse-result)
      (P/fail-cargo (str "In Schema, "
                         (grammars/failure-in-text->string parse-result input)))
      (P/success-cargo parse-result))))

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
      (P/fail-cargo (str "In Construction, " (:error result))))))

(defn parse-reaction-unified [reaction-text]
  "Parse the Reactive section using the construction/reaction grammar.
  Input: reaction text string
  Output: Cargo with parsed AST or error"
  (let [result (grammars/parse-reaction-with-failure-handling reaction-text)]
    (if (:success result)
      (P/success-cargo (:ast result))
      (P/fail-cargo (str "In Methods, " (:error result))))))
