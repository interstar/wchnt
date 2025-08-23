(ns wchnt-lang.ast-utils
  "Utility functions for working with AST nodes and tree walking")

(defn node-type?
  "Check if an AST node is of a specific type"
  [ast kw]
  (and (vector? ast) (= (first ast) kw)))

(defn find-nodes-by-type
  "Find all nodes of a specific type in an AST"
  [ast node-type]
  (letfn [(find-nodes [node]
            (cond
              (node-type? node node-type)
              [node]
              (vector? node)
              (mapcat find-nodes (rest node))
              :else []))]
    (find-nodes ast)))

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

(defn walk-ast
  "Walk an AST and apply a function to each node"
  [ast visitor-fn]
  (letfn [(walk [node]
            (let [result (visitor-fn node)]
              (if (vector? node)
                (map walk (rest node))
                result)))]
    (walk ast))) 