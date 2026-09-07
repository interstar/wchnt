(ns wchnt-lang.ast-utils
  "Utility functions for working with AST nodes and tree walking")

(defn node-type?
  "Check if an AST node is of a specific type"
  [ast kw]
  (and (vector? ast) (= (first ast) kw)))

(defn- find-nodes-by-type*
  [node node-type]
  (cond
    (node-type? node node-type)
    [node]

    (vector? node)
    (mapcat #(find-nodes-by-type* % node-type) (rest node))

    :else
    []))

(defn find-nodes-by-type
  "Find all nodes of a specific type in an AST"
  [ast node-type]
  (find-nodes-by-type* ast node-type))

(defn find-first-node-by-type
  "Find the first node of a specific type in an AST"
  [ast node-type]
  (first (find-nodes-by-type ast node-type)))

(defn get-node-children
  "Get the children of an AST node (everything after the first element)"
  [ast]
  (when (vector? ast)
    (rest ast)))

(defn get-node-type
  "Get the type of an AST node (first element)"
  [ast]
  (when (vector? ast)
    (first ast)))

(defn- walk-ast*
  [node visitor-fn]
  (let [result (visitor-fn node)]
    (if (vector? node)
      (map #(walk-ast* % visitor-fn) (rest node))
      result)))

(defn walk-ast
  "Walk an AST and apply a function to each node"
  [ast visitor-fn]
  (walk-ast* ast visitor-fn))

(defn definee-name
  "Class name from a :Definee node. Optional leading :Inlet (`>`)."
  [node]
  (let [xs (rest node)]
    (if (node-type? (first xs) :Inlet)
      (second xs)
      (first xs))))

(defn definee-inlet?
  "True when the class is marked `>Name` (harness mailbox)."
  [node]
  (node-type? (second node) :Inlet))

(defn parse-int
  "Parse a decimal integer. Fail fast on junk (no silent NaN)."
  [s]
  #?(:clj (Integer/parseInt s)
     :cljs (let [n (js/parseInt s 10)]
             (if (js/isNaN n)
               (throw (ex-info (str "Not an integer: " s) {:s s}))
               n))))

(defn parse-float
  "Parse a floating-point number. Fail fast on junk (no silent NaN)."
  [s]
  #?(:clj (Double/parseDouble s)
     :cljs (let [n (js/parseFloat s)]
             (if (js/isNaN n)
               (throw (ex-info (str "Not a float: " s) {:s s}))
               n))))
