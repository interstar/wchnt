(ns wchnt-lang.factory-params
  "Collect factory parameters from free names in @ Construction slots.
   Order is first appearance in the Construction AST, left to right."
  (:require [wchnt-lang.ast-utils :as ast-utils]
            [wchnt-lang.ir :as ir]))

(defn- unwrap
  [node]
  (if (ast-utils/node-type? node :Expression)
    (second node)
    node))

(defn- enum-values
  [schema-ir]
  (set (mapcat :values (:enums schema-ir))))

(defn- components-of
  [schema-ir class-name]
  (or (ir/get-assemblage-components schema-ir class-name) []))

(defn- slot-at
  [schema-ir class-name index]
  (let [cs (components-of schema-ir class-name)]
    (when (>= index (count cs))
      (throw (ex-info (str "Construction of " class-name " has extra argument "
                           index)
                      {:class-name class-name :index index})))
    (nth cs index)))

(defn- external?
  [component]
  (= :external (:relationship component)))

(defn- add-param
  [params name type-name]
  (if-let [existing (first (filter #(= name (:name %)) params))]
    (if (= type-name (:type existing))
      params
      (throw (ex-info (str "Factory parameter '" name "' used as both "
                           (:type existing) " and " type-name)
                      {:name name
                       :types [(:type existing) type-name]})))
    (conj params {:name name :type type-name})))

(declare walk-node)

(defn- walk-object
  [node class-name schema-ir enums bound params]
  (let [arg-list (if (ast-utils/node-type? (second node) :ClassName)
                   (nth node 2)
                   (second node))
        items (if (ast-utils/node-type? arg-list :ArgList)
                (rest arg-list)
                [])]
    (reduce (fn [ps [i item]]
              (walk-node item class-name i schema-ir enums bound ps))
            params
            (map-indexed vector items))))

(defn- walk-array
  [node schema-ir enums bound params]
  (let [el-type (second (second node))
        arg-list (nth node 2)
        items (if (ast-utils/node-type? arg-list :ArgList)
                (rest arg-list)
                [])]
    (reduce (fn [ps item]
              (let [inner (unwrap item)
                    class-name (if (and (ast-utils/node-type? inner
                                                              :InnerObjectConstruction)
                                        (ast-utils/node-type? (second inner) :ClassName))
                                 (second (second inner))
                                 el-type)]
                (walk-node item class-name 0 schema-ir enums bound ps)))
            params
            items)))

(defn- walk-variable
  [name class-name index schema-ir enums bound params]
  (cond
    (contains? enums name) params
    (contains? bound name) params
    (nil? class-name)
    (throw (ex-info (str "Unknown construction name '" name "'")
                    {:name name}))
    :else
    (let [slot (slot-at schema-ir class-name index)]
      (if (external? slot)
        (add-param params name (:type-name slot))
        (throw (ex-info (str "Unknown construction name '" name
                             "'; a free name is only a factory parameter "
                             "in an @ slot")
                        {:name name
                         :class-name class-name
                         :slot (:component-name slot)}))))))

(defn- walk-node
  [node class-name index schema-ir enums bound params]
  (let [node (unwrap node)]
    (cond
      (not (vector? node)) params

      (ast-utils/node-type? node :VariableRef)
      (walk-variable (second node) class-name index schema-ir enums bound params)

      (or (ast-utils/node-type? node :ObjectConstruction)
          (ast-utils/node-type? node :InnerObjectConstruction))
      (let [tagged? (ast-utils/node-type? (second node) :ClassName)
            child-class (if tagged?
                          (second (second node))
                          class-name)]
        (when (and class-name (not (nil? index)))
          (let [slot (slot-at schema-ir class-name index)]
            (when (external? slot)
              (throw (ex-info (str "Cannot construct into external slot @"
                                   (:type-name slot)
                                   "; use a factory parameter or a call")
                              {:class-name class-name
                               :type-name (:type-name slot)})))))
        (walk-object node child-class schema-ir enums bound params))

      (ast-utils/node-type? node :ArrayConstruction)
      (do
        (when (and class-name (not (nil? index)))
          (let [slot (slot-at schema-ir class-name index)]
            (when (external? slot)
              (throw (ex-info (str "Cannot construct into external slot @"
                                   (:type-name slot)
                                   "; use a factory parameter or a call")
                              {:class-name class-name})))))
        (walk-array node schema-ir enums bound params))

      (ast-utils/node-type? node :MethodCall)
      params

      :else
      params)))

(defn- walk-statement
  [stmt schema-ir enums bound params]
  (cond
    (ast-utils/node-type? stmt :Assignment)
    (let [var-name (second (second stmt))
          expr (nth stmt 2)
          next-params (walk-node expr nil nil schema-ir enums bound params)]
      {:bound (conj bound var-name)
       :params next-params})

    (or (ast-utils/node-type? stmt :Expression)
        (ast-utils/node-type? stmt :ObjectConstruction))
    {:bound bound
     :params (walk-node stmt nil nil schema-ir enums bound params)}

    :else
    {:bound bound :params params}))

(defn collect
  "Return [{:name n :type t} ...] in Construction AST order."
  [construction-ast schema-ir]
  (let [enums (enum-values schema-ir)
        stmts (if (ast-utils/node-type? construction-ast :BlockStatements)
                (filter vector? (rest construction-ast))
                [construction-ast])]
    (:params
     (reduce (fn [{:keys [bound params]} stmt]
               (walk-statement stmt schema-ir enums bound params))
             {:bound #{} :params []}
             stmts))))
