(ns wchnt-lang.ast-processing
  "Unified AST processing for WCHNT construction parsing"
  (:require [wchnt-lang.ast-utils :as ast-utils]
            [clojure.string :as str]))

;; =============================================================================
;; Unified Argument Processing
;; =============================================================================

(defn extract-args-from-arglist
  "Universal argument extraction from ArgList - works for any construction type"
  [arg-list]
  (if (ast-utils/node-type? arg-list :ArgList)
    (rest arg-list)  ; Arguments are direct children, no ArgItem wrapper
    []))

(defn process-construction-arg
  "Process a single construction argument into IR format"
  [arg index schema-ir parent-class-name]

  (cond
        ;; InnerObjectConstruction (implicit class)
    (ast-utils/node-type? arg :InnerObjectConstruction)
    (let [class-name-node (second arg)
          class-name (if (ast-utils/node-type? class-name-node :ClassName)
                      (second class-name-node)
                      ;; Look up expected type from schema based on position
                      (when schema-ir
                        (let [assemblage (first (filter #(= (:name %) parent-class-name) (:assemblages schema-ir)))
                              components (:components assemblage)]
                          (if (and assemblage components (< index (count components)))
                            (:type-name (nth components index))
                            (throw (ex-info "Could not determine expected type for class and position"
                                          {:class-name parent-class-name
                                           :position index
                                           :available-classes (map :name (:assemblages schema-ir))
                                           :schema-ir schema-ir}))))))
          arg-list (when (>= (count arg) 3) (nth arg 2))
          nested-args (if arg-list (extract-args-from-arglist arg-list) [])]
      (when-not class-name
        (throw (ex-info "Could not determine class name for inner construction" 
                       {:arg arg :parent-class parent-class-name :index index})))
      {:type :object
       :class-name class-name
       :args nested-args
       :index index})
    
    ;; ObjectConstruction (explicit class)
    (ast-utils/node-type? arg :ObjectConstruction)
    (let [class-name-node (second arg)
          class-name (if (ast-utils/node-type? class-name-node :ClassName)
                      (second class-name-node)
                      (throw (ex-info "Object construction missing class name" 
                                    {:arg arg :index index})))
          arg-list (when (>= (count arg) 3) (nth arg 2))
          nested-args (if arg-list (extract-args-from-arglist arg-list) [])]
      {:type :object
       :class-name class-name
       :args nested-args
       :index index})
    
    ;; ArrayConstruction
    (ast-utils/node-type? arg :ArrayConstruction)
    (let [type-node (second arg)
          element-type (if (ast-utils/node-type? type-node :Type)
                        (second type-node)
                        (throw (ex-info "Array construction missing type" 
                                      {:arg arg :index index})))
          arg-list (when (>= (count arg) 3) (nth arg 2))
          elements (if arg-list (extract-args-from-arglist arg-list) [])]
      {:type :array
       :class-name element-type
       :args elements
       :index index})
    
    ;; MapConstruction
    (ast-utils/node-type? arg :MapConstruction)
    (let [key-type-node (second arg)
          key-type (if (ast-utils/node-type? key-type-node :KeyType)
                    (second key-type-node)
                    (throw (ex-info "Map construction missing key type" 
                                  {:arg arg :index index})))
          val-type-node (when (>= (count arg) 3) (nth arg 2))
          val-type (if (and val-type-node (ast-utils/node-type? val-type-node :ValType))
                    (second val-type-node)
                    (throw (ex-info "Map construction missing value type" 
                                  {:arg arg :index index})))
          key-value-list (when (>= (count arg) 4) (nth arg 3))
          key-value-pairs (if (and key-value-list (ast-utils/node-type? key-value-list :KeyValueList))
                          (map #(if (ast-utils/node-type? % :KeyValuePair)
                                 (let [key-expr (second %)
                                       val-expr (nth % 2)]
                                   [(second key-expr) (Integer/parseInt (second val-expr))])
                                 %)
                               (rest key-value-list))
                          [])]
      {:type :map
       :class-name (str "Map<" key-type ", " val-type ">")
       :args key-value-pairs
       :index index})
    
    ;; VariableRef
    (ast-utils/node-type? arg :VariableRef)
    {:type :variable
     :class-name "VariableRef"
     :args [(second arg)]
     :index index}
    
    ;; Primitives
    (ast-utils/node-type? arg :IntLiteral)
    {:type :primitive
     :class-name "Int"
     :args [(Integer/parseInt (second arg))]
     :index index}
    
    (ast-utils/node-type? arg :StringLiteral)
    {:type :primitive
     :class-name "String"
     :args [arg]
     :index index}
    
    (ast-utils/node-type? arg :FloatLiteral)
    {:type :primitive
     :class-name "Float"
     :args [(second arg)]
     :index index}
    
    (ast-utils/node-type? arg :BooleanLiteral)
    {:type :primitive
     :class-name "Boolean"
     :args [(second arg)]
     :index index}
    
                    ;; Default case - fail fast with clear error
                :else
                (throw (ex-info "Unrecognized argument type in construction"
                               {:arg arg
                                :arg-type (first arg)
                                :index index
                                :parent-class parent-class-name}))))

(defn process-construction
  "Universal construction processing - handles all construction types"
  [construction-node schema-ir]
  (cond
    ;; ObjectConstruction (explicit class)
    (ast-utils/node-type? construction-node :ObjectConstruction)
    (let [class-name-node (second construction-node)
          class-name (if (ast-utils/node-type? class-name-node :ClassName)
                      (second class-name-node)
                      (throw (ex-info "Object construction missing class name" 
                                    {:construction construction-node})))
          arg-list (nth construction-node 2)
          args (extract-args-from-arglist arg-list)]
      {:type :object
       :class-name class-name
       :args (map-indexed #(process-construction-arg %1 %2 schema-ir class-name) args)})
    
    ;; InnerObjectConstruction (implicit class)
    (ast-utils/node-type? construction-node :InnerObjectConstruction)
    (let [class-name-node (second construction-node)
          class-name (if (ast-utils/node-type? class-name-node :ClassName)
                      (second class-name-node)
                      (throw (ex-info "Inner object construction missing class name" 
                                    {:construction construction-node})))
          arg-list (nth construction-node 2)
          args (extract-args-from-arglist arg-list)]
      {:type :object
       :class-name class-name
       :args (map-indexed #(process-construction-arg %1 %2 schema-ir class-name) args)})
    
    ;; ArrayConstruction
    (ast-utils/node-type? construction-node :ArrayConstruction)
    (let [type-node (second construction-node)
          element-type (if (ast-utils/node-type? type-node :Type)
                        (second type-node)
                        (throw (ex-info "Array construction missing type" 
                                      {:construction construction-node})))
          arg-list (nth construction-node 2)
          args (extract-args-from-arglist arg-list)]
      {:type :array
       :class-name element-type
       :args (map-indexed #(process-construction-arg %1 %2 schema-ir element-type) args)})
    
    ;; MapConstruction
    (ast-utils/node-type? construction-node :MapConstruction)
    (let [key-type-node (second construction-node)
          key-type (if (ast-utils/node-type? key-type-node :KeyType)
                    (second key-type-node)
                    (throw (ex-info "Map construction missing key type" 
                                  {:construction construction-node})))
          val-type-node (nth construction-node 2)
          val-type (if (ast-utils/node-type? val-type-node :ValType)
                    (second val-type-node)
                    (throw (ex-info "Map construction missing value type" 
                                  {:construction construction-node})))
          key-value-list (nth construction-node 3)
          key-value-pairs (if (ast-utils/node-type? key-value-list :KeyValueList)
                          (map #(if (ast-utils/node-type? % :KeyValuePair)
                                 (let [key-expr (second %)
                                       val-expr (nth % 2)]
                                   [(second key-expr) (second val-expr)])
                                 %)
                               (rest key-value-list))
                          [])]
      {:type :map
       :class-name (str "Map<" key-type ", " val-type ">")
       :args key-value-pairs})
    
                    ;; Default case - not a construction
                :else
                (throw (ex-info "Not a construction node" 
                               {:node construction-node
                                :node-type (first construction-node)}))))

;; =============================================================================
;; AST Traversal Utilities
;; =============================================================================

(defn find-constructions
  "Find all construction nodes in an AST"
  [ast]
  (let [construction-types #{:ObjectConstruction :InnerObjectConstruction :ArrayConstruction :MapConstruction}]
    (filter #(and (vector? %) (construction-types (first %))) 
            (tree-seq vector? rest ast))))

(defn extract-root-construction
  "Extract the root construction from a BlockStatements AST"
  [block-statements-ast]
  (let [statements (rest block-statements-ast)  ; Skip :BlockStatements tag
        final-statements (filter #(and (vector? %) (= (first %) :Expression)) statements)]
    (if (seq final-statements)
      (second (last final-statements))  ; Get the expression from [:Expression expr]
      nil)))
