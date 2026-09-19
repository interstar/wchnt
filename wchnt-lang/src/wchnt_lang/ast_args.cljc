(ns wchnt-lang.ast-args
  "Helpers for extracting structured arguments from construction AST.

  This is intentionally not tied to a specific phase name (construction/reactive/imperative),
  since the same argument extraction logic is expected to be reused across phases."
  (:require [wchnt-lang.ast-utils :as ast-utils]
            [wchnt-lang.reaction :as reaction]))

(declare extract-args-from-arg-list variable-ref->arg inner-object-construction->arg)

(defn- unwrap-expression
  [expr]
  (if (ast-utils/node-type? expr :Expression)
    (second expr)
    expr))

(defn- map-key-expr->arg
  [key-expr index ctx]
  (let [unwrapped (unwrap-expression key-expr)
        enum-values (:enum-values ctx)]
    (cond
      (ast-utils/node-type? unwrapped :StringLiteral)
      {:type :primitive :class-name "String" :value (second unwrapped) :args [] :index (* index 2)}

      (ast-utils/node-type? unwrapped :VariableRef)
      (variable-ref->arg enum-values unwrapped (* index 2))

      :else
      (throw (ex-info "Unsupported key type in map construction" {:key-expr key-expr})))))

(defn- map-val-expr->arg
  [val-expr index ctx]
  (let [unwrapped (unwrap-expression val-expr)
        enum-values (:enum-values ctx)
        val-index (+ (* index 2) 1)]
    (cond
      (ast-utils/node-type? unwrapped :StringLiteral)
      {:type :primitive :class-name "String" :value (second unwrapped) :args [] :index val-index}

      (ast-utils/node-type? unwrapped :IntLiteral)
      {:type :primitive :class-name "Int" :value (second unwrapped) :args [] :index val-index}

      (ast-utils/node-type? unwrapped :VariableRef)
      (variable-ref->arg enum-values unwrapped val-index)

      (or (ast-utils/node-type? unwrapped :ObjectConstruction)
          (ast-utils/node-type? unwrapped :InnerObjectConstruction))
      (inner-object-construction->arg ctx unwrapped val-index)

      :else
      (throw (ex-info "Unsupported value type in map construction" {:val-expr val-expr})))))

(defn process-map-construction-expression
  "Process a MapConstruction expression into structured ConstructionArg IR."
  ([inner-expression]
   (process-map-construction-expression inner-expression nil))
  ([inner-expression schema-ir]
   (let [ctx {:schema-ir schema-ir
              :root-class-name nil
              :enum-values (set (mapcat :values (:enums schema-ir)))}
         key-type-node (second inner-expression)
         key-type (if (ast-utils/node-type? key-type-node :KeyType)
                    (second key-type-node)
                    (throw (ex-info "Map construction missing key type node"
                                    {:inner-expression inner-expression})))
         val-type-node (nth inner-expression 2)
         val-type (if (ast-utils/node-type? val-type-node :ValType)
                    (second val-type-node)
                    (throw (ex-info "Map construction missing value type node"
                                    {:inner-expression inner-expression})))
         ;; KeyValueList is optional in the grammar for an empty map literal.
         key-value-list (nth inner-expression 3 nil)
         ctx (assoc ctx :root-class-name val-type)
         structured-args (if (ast-utils/node-type? key-value-list :KeyValueList)
                           (map-indexed
                            (fn [index key-value-pair]
                              (if (ast-utils/node-type? key-value-pair :KeyValuePair)
                                (let [key-expr (second key-value-pair)
                                      val-expr (nth key-value-pair 2)]
                                  [(map-key-expr->arg key-expr index ctx)
                                   (map-val-expr->arg val-expr index ctx)])
                                (throw (ex-info "Invalid key-value pair in map construction"
                                                {:key-value-pair key-value-pair}))))
                            (rest key-value-list))
                           [])]
     {:type :map
      :class-name (str "Map<" key-type ", " val-type ">")
      :args (flatten structured-args)})))

(defn- build-extract-args-context
  [schema-ir root-class-name]
  {:schema-ir schema-ir
   :root-class-name root-class-name
   :enum-values (set (mapcat :values (:enums schema-ir)))})

(defn- expected-class-name-for-arg
  [{:keys [schema-ir root-class-name]} arg-index]
  (let [assemblage (first (filter #(= (:name %) root-class-name) (:assemblages schema-ir)))
        components (:components assemblage)]
    (if (and assemblage components (< arg-index (count components)))
      (:type-name (nth components arg-index))
      (throw (ex-info "Could not determine expected class name for argument"
                      {:arg-index arg-index
                       :root-class-name root-class-name
                       :schema-ir schema-ir})))))

(defn- variable-ref->arg
  [enum-values arg-item arg-index]
  (let [var-name (second arg-item)
        is-enum-value (contains? enum-values var-name)]
    (if is-enum-value
      {:type :enum-value
       :class-name "Enum"
       :value var-name
       :args []
       :index arg-index}
      {:type :variable
       :class-name "VariableRef"
       :value var-name
       :args []
       :index arg-index})))

(defn- inner-object-construction->arg
  [ctx arg-item arg-index]
  (let [second-element (second arg-item)]
    (if (ast-utils/node-type? second-element :ClassName)
      (let [class-name (second second-element)
            arg-list (nth arg-item 2)
            child-ctx (-> ctx
                          (assoc :root-class-name class-name)
                          (dissoc :array-element-type))]
        {:type :object
         :class-name class-name
         :args (extract-args-from-arg-list child-ctx arg-list)
         :index arg-index})
      (let [expected-class-name (or (:array-element-type ctx)
                                    (expected-class-name-for-arg ctx arg-index))
            arg-list (second arg-item)
            child-ctx (-> ctx
                          (assoc :root-class-name expected-class-name)
                          (dissoc :array-element-type))]
        {:type :object
         :class-name expected-class-name
         :args (extract-args-from-arg-list child-ctx arg-list)
         :index arg-index}))))

(defn- array-construction->arg
  [ctx arg-item arg-index]
  (let [element-type (second (second arg-item))
        arg-list (nth arg-item 2)
        element-ctx (assoc ctx
                           :root-class-name element-type
                           :array-element-type element-type)]
    {:type :array
     :class-name element-type
     :args (extract-args-from-arg-list element-ctx arg-list)
     :index arg-index}))

(defn- map-construction->arg
  [arg-item arg-index schema-ir]
  (assoc (process-map-construction-expression arg-item schema-ir)
         :index arg-index))

(defn- int-literal->arg
  [arg-item arg-index]
  {:type :primitive
   :class-name "Int"
   :value (ast-utils/parse-int (second arg-item))
   :args []
   :index arg-index})

(defn- string-literal->arg
  [arg-item arg-index]
  {:type :primitive
   :class-name "String"
   :value (second arg-item)
   :args []
   :index arg-index})

(defn- bool-literal->arg
  [arg-item arg-index]
  {:type :primitive
   :class-name "Bool"
   :value (= "true" (second arg-item))
   :args []
   :index arg-index})

(defn- float-literal->arg
  [arg-item arg-index]
  {:type :primitive
   :class-name "Float"
   :value (ast-utils/parse-float (second arg-item))
   :args []
   :index arg-index})

(defn- extract-arg-item
  [{:keys [enum-values] :as ctx} arg-item arg-index]
  (cond
    (ast-utils/node-type? arg-item :VariableRef)
    (variable-ref->arg enum-values arg-item arg-index)

    (ast-utils/node-type? arg-item :InnerObjectConstruction)
    (inner-object-construction->arg ctx arg-item arg-index)

    (ast-utils/node-type? arg-item :ArrayConstruction)
    (array-construction->arg ctx arg-item arg-index)

    (ast-utils/node-type? arg-item :MapConstruction)
    (map-construction->arg arg-item arg-index (:schema-ir ctx))

    (ast-utils/node-type? arg-item :IntLiteral)
    (int-literal->arg arg-item arg-index)

    (ast-utils/node-type? arg-item :FloatLiteral)
    (float-literal->arg arg-item arg-index)

    (ast-utils/node-type? arg-item :StringLiteral)
    (string-literal->arg arg-item arg-index)

    (ast-utils/node-type? arg-item :BoolLiteral)
    (bool-literal->arg arg-item arg-index)

    (ast-utils/node-type? arg-item :MethodCall)
    (reaction/construction-call->ir arg-item (:schema-ir ctx) arg-index)

    :else
    {:type :primitive
     :class-name "Unknown"
     :value arg-item
     :args []
     :index arg-index}))

(defn- extract-args-from-arg-list
  [ctx arg-list]
  (if (ast-utils/node-type? arg-list :ArgList)
    (map-indexed (fn [arg-index arg-item]
                   (extract-arg-item ctx arg-item arg-index))
                 (rest arg-list))
    []))

(defn extract-args-from-object-construction
  "Extract structured arguments from a single ObjectConstruction node."
  [object-construction schema-ir root-class-name]
  (if (ast-utils/node-type? object-construction :ObjectConstruction)
    (let [arg-list (nth object-construction 2)
          ctx (build-extract-args-context schema-ir root-class-name)]
      (extract-args-from-arg-list ctx arg-list))
    []))
