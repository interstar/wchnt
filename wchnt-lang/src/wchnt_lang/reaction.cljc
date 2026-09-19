(ns wchnt-lang.reaction
  "Reaction section: method AST → methods IR."
  (:require [clojure.string :as str]
            [wchnt-lang.ast-utils :as ast-utils]
            [wchnt-lang.ir :as ir]
            [wchnt-lang.targets.requires :as target-requires]
            [wchnt-lang.template :as template]))

(defn- unwrap-expr
  [node]
  (cond
    (ast-utils/node-type? node :Expression)
    (second node)

    (ast-utils/node-type? node :MethodArgItem)
    (unwrap-expr (second node))

    :else
    node))

(defn- assemblage-names
  [schema-ir]
  (set (map :name (:assemblages schema-ir))))

(defn- interface-names
  [schema-ir]
  (into (set (map :name (:interfaces schema-ir)))
        (or (:imported-interfaces schema-ir) #{})))

(defn- enum-names
  [schema-ir]
  (set (map :name (:enums schema-ir))))

(defn- interface?
  [schema-ir class-name]
  (contains? (interface-names schema-ir) class-name))

(defn- valid-type-name?
  "True when type-name is a primitive, schema class, interface, enum, external, or collection type."
  [schema-ir type-name]
  (or (contains? #{"Int" "Float" "String" "Bool" "Void"} type-name)
      (contains? (assemblage-names schema-ir) type-name)
      (contains? (interface-names schema-ir) type-name)
      (contains? (enum-names schema-ir) type-name)
      (ir/external-type? schema-ir type-name)
      (contains? (or (:imported-handles schema-ir) #{}) type-name)
      (and (string? type-name)
           (or (str/starts-with? type-name "Array<")
               (str/starts-with? type-name "Map<"))
           (str/ends-with? type-name ">"))))

(defn- assert-valid-type!
  [schema-ir type-name context]
  (when-not (valid-type-name? schema-ir type-name)
    (throw (ex-info (str "Unknown type '" type-name "'")
                    (assoc context :type-name type-name)))))

(defn- interface-implementers
  [schema-ir interface-name]
  (vec (get-in schema-ir [:interface-implementers interface-name] #{})))

(defn- context-parent-type
  "If name is the generated context field on this class, return the parent class name."
  [schema-ir class-name name]
  (when (ir/needs-context? schema-ir class-name)
    (let [parent (ir/get-context-parent schema-ir class-name)]
      (when (and parent (= name (ir/context-field-name parent)))
        parent))))

(defn- binding-type
  [schema-ir class-name name]
  (or (:type (ir/resolve-field schema-ir class-name name))
      (context-parent-type schema-ir class-name name)))

(defn- field-names
  [schema-ir class-name]
  (into (ir/own-field-names schema-ir class-name)
        (ir/promoted-field-names schema-ir class-name)))

(defn- this-field-expr
  "IR for a field of this, expanding + promotion into a path."
  [schema-ir class-name name]
  (let [hit (ir/resolve-field schema-ir class-name name)]
    (cond
      (nil? hit) nil
      (= 1 (count (:fields hit))) {:expr :field :name (first (:fields hit))}
      :else {:expr :path
             :root {:expr :this}
             :fields (:fields hit)
             :type (:type hit)})))

(defn- enums-with-value
  [schema-ir name]
  (filterv #(some #{name} (:values %)) (or (:enums schema-ir) [])))

(defn- resolve-enum-ctor
  [name schema-ir class-name]
  (let [hits (enums-with-value schema-ir name)]
    (when (> (count hits) 1)
      (throw (ex-info (str "Enum constructor '" name "' belongs to more than one enum")
                      {:name name :class-name class-name
                       :enums (mapv :name hits)})))
    (when-let [e (first hits)]
      {:expr :enum :name name :type (:name e)})))

(defn- resolve-name
  [name {:keys [param-names let-names schema-ir class-name]}]
  (cond
    (= name "this")
    {:expr :this}

    (contains? (or let-names #{}) name)
    {:expr :local :name name}

    (contains? (or (:import-aliases schema-ir) {}) name)
    {:expr :import-alias :name name}

    (contains? param-names name)
    {:expr :param :name name}

    (this-field-expr schema-ir class-name name)
    (this-field-expr schema-ir class-name name)

    (context-parent-type schema-ir class-name name)
    {:expr :field :name name}

    :else
    (or (resolve-enum-ctor name schema-ir class-name)
        (throw (ex-info (str "Unknown name '" name
                             "' (not a field, parameter, or let)")
                        {:name name :class-name class-name})))))

(declare ast->expr process-statements assert-fresh-let-name value-type make-arith
         block-statements convert-call-arg
         assert-methods-complete!)

(defn- convert-chain-parts
  [items ctx]
  (mapv (fn [item]
          (if (string? item)
            item
            (ast->expr item ctx)))
        items))

(defn- arg-list-exprs
  [arg-list ctx]
  (if (ast-utils/node-type? arg-list :ArgList)
    (mapv #(ast->expr % ctx) (rest arg-list))
    []))

(defn- validate-construct!
  [class-name args schema-ir]
  (when (contains? (or (:imported-handles schema-ir) #{}) class-name)
    (throw (ex-info (str "Cannot construct imported handle '" class-name
                         "'; call a public method that returns one")
                    {:class-name class-name})))
  (when-not (contains? (assemblage-names schema-ir) class-name)
    (throw (ex-info (str "Unknown class '" class-name "' in method construction")
                    {:class-name class-name})))
  (let [expected (count (ir/get-assemblage-components schema-ir class-name))]
    (when (not= expected (count args))
      (throw (ex-info (str "Construction of " class-name " expected "
                           expected " arguments, got " (count args))
                      {:class-name class-name
                       :expected expected
                       :got (count args)})))))

(defn- root-type
  [root {:keys [schema-ir class-name let-types param-types]}]
  (case (:expr root)
    :this class-name
    :field (binding-type schema-ir class-name (:name root))
    :local (get let-types (:name root))
    :param (get param-types (:name root))
    :call (:type root)
    :import-alias nil
    :target-call (:type root)
    :path (:type root)
    :if (:type root)
    :string "String"
    :int "Int"
    :float "Float"
    :bool "Bool"
    :array (:type root)
    :map (:type root)
    :arith (:type root)
    nil))

(defn- follow-field
  [schema-ir class-name field-name]
  (when (contains? (or (:imported-handles schema-ir) #{}) class-name)
    (throw (ex-info (str "Cannot read field '" field-name "' on imported handle "
                         class-name)
                    {:class-name class-name :field-name field-name})))
  (when (or (str/starts-with? class-name "Array<")
            (str/starts-with? class-name "Map<"))
    (throw (ex-info (str "Cannot access fields of collection type " class-name)
                    {:class-name class-name :field-name field-name})))
  (when (or (str/starts-with? class-name "Array<")
            (str/starts-with? class-name "Map<"))
    (throw (ex-info (str "Cannot access fields of collection type " class-name)
                    {:class-name class-name :field-name field-name})))
  (let [hit (ir/resolve-field schema-ir class-name field-name)]
    (when-not hit
      (throw (ex-info (str "Unknown field '" field-name "' on " class-name)
                      {:class-name class-name :field-name field-name})))
    (:type hit)))

(declare imported-handle?)

(defn- ast->path
  [node ctx]
  (let [names (vec (rest node))
        root (resolve-name (first names) ctx)
        start-type (root-type root ctx)]
    (when-not start-type
      (throw (ex-info
              (if (and (= :param (:expr root))
                       (not (get (:param-types ctx) (:name root))))
                (str "Parameter '" (first names)
                     "' needs a type annotation for field access (e.g. Rect/"
                     (first names) ")")
                (str "Cannot determine type of '" (first names) "' for field access"))
              {:name (first names)})))
    (when (imported-handle? (:schema-ir ctx) start-type)
      (throw (ex-info (str "Cannot read fields of imported handle " start-type)
                      {:class-name start-type :fields (rest names)})))
    (let [expanded (ir/expand-field-path (:schema-ir ctx) start-type (rest names))]
      {:expr :path
       :root root
       :fields (:fields expanded)
       :type (:type expanded)})))

(defn- method-arg-items
  [arg-list]
  (if-not (ast-utils/node-type? arg-list :MethodArgList)
    []
    (mapv (fn [item]
            (if (ast-utils/node-type? item :MethodArgItem)
              (second item)
              item))
          (rest arg-list))))

(defn- call-method-name
  [item]
  (cond
    (string? item) item
    (and (vector? item) (= :CallMethodName (first item))) (second item)
    :else nil))

(defn- take-call-step
  [items]
  (let [[name-nodes more] (split-with #(some? (call-method-name %)) items)
        names (mapv call-method-name name-nodes)]
    (when (or (empty? names) (empty? more))
      (throw (ex-info "Method call is missing a name or argument list"
                      {:items items})))
    {:fields (vec (butlast names))
     :method (last names)
     :args (first more)
     :more (vec (rest more))}))

(declare imported-handle?)

(defn- apply-fields
  [root fields ctx]
  (if (empty? fields)
    root
    (let [start (root-type root ctx)]
      (when-not start
        (throw (ex-info "Cannot determine type of receiver for field access"
                        {:root root})))
      (when (imported-handle? (:schema-ir ctx) start)
        (throw (ex-info (str "Cannot read fields of imported handle " start)
                        {:class-name start :fields fields})))
      (let [expanded (ir/expand-field-path (:schema-ir ctx) start fields)]
        {:expr :path
         :root root
         :fields (:fields expanded)
         :type (:type expanded)}))))

(defn- receiver-class
  [receiver ctx]
  (or (root-type receiver ctx)
      (throw (ex-info "Cannot determine type of call receiver"
                      {:receiver receiver}))))

(defn- array-elem-type
  [type-name]
  (when (and (string? type-name)
             (str/starts-with? type-name "Array<")
             (str/ends-with? type-name ">"))
    (subs type-name 6 (dec (count type-name)))))

(defn- process-block
  [block-node ctx class-name method-name]
  (when-not (ast-utils/node-type? block-node :Block)
    (throw (ex-info "Expected a block" {:node block-node})))
  (process-statements (rest (second block-node)) ctx class-name method-name))

(defn- with-lambda-params
  [ctx param-names param-types class-name method-name]
  (doseq [name param-names]
    (assert-fresh-let-name name ctx class-name method-name))
  (-> ctx
      (update :param-names into param-names)
      (update :param-types merge (zipmap param-names param-types))))

(defn- ast->lambda
  [node param-types ctx class-name method-name]
  (let [{:keys [param-names statements]} (block-statements node)]
    (when (not= (count param-names) (count param-types))
      (throw (ex-info (str class-name "::" method-name " block expected "
                           (count param-types) " arguments, got "
                           (count param-names))
                      {:expected (count param-types)
                       :got (count param-names)})))
    (let [inner-ctx (with-lambda-params ctx param-names param-types
                      class-name method-name)
          {:keys [lets body]} (process-statements statements
                                                  inner-ctx
                                                  class-name
                                                  method-name)
          ret (value-type body inner-ctx)]
      (when-not ret
        (throw (ex-info (str "Cannot determine return type of "
                             class-name "::" method-name " block")
                        {:body body})))
      {:expr :lambda
       :params (mapv (fn [n t] {:name n :type t}) param-names param-types)
       :lets lets
       :body body
       :type ret})))

(declare type-join)

(defn- branch-type
  [branch ctx]
  (value-type (:body branch) ctx))

(defn- branch-or-if-type
  [node ctx]
  (if (= (:expr node) :if)
    (:type node)
    (branch-type node ctx)))

(defn- process-else-part
  [else-part-node ctx class-name method-name]
  (let [items (rest else-part-node)
        final-block (last items)
        else-if-nodes (butlast items)
        final-else (process-block final-block ctx class-name "if-else")
        chain         (reduce (fn [acc else-if-node]
                      (let [cond-expr (ast->expr (nth else-if-node 1) ctx)
                            then-branch (process-block (nth else-if-node 2)
                                                       ctx class-name "if-then")
                            then-type (branch-type then-branch ctx)
                            else-type (branch-or-if-type acc ctx)
                            result-type (type-join then-type else-type)]
                        (when-not result-type
                          (throw (ex-info (str "if branches must have the same type, got "
                                               then-type " and " else-type)
                                          {:then then-type :else else-type})))
                        {:expr :if
                         :condition cond-expr
                         :then then-branch
                         :else acc
                         :type result-type}))
                    final-else
                    (reverse else-if-nodes))]
    chain))

(defn- ast->if
  [node ctx]
  (let [class-name (:class-name ctx)
        method-name "if"
        condition (ast->expr (second node) ctx)
        then (process-block (nth node 2) ctx class-name "if-then")
        else (process-else-part (nth node 3) ctx class-name method-name)
        then-type (branch-type then ctx)
        else-type (branch-or-if-type else ctx)
        result-type (type-join then-type else-type)]
    (when-not result-type
      (throw (ex-info (str "if branches must have the same type, got "
                           then-type " and " else-type)
                      {:then then-type :else else-type})))
    (when-not then-type
      (throw (ex-info "Cannot determine type of if expression"
                      {:then then :else else})))
    {:expr :if
     :condition condition
     :then then
     :else else
     :type result-type}))

(defn- require-lambda
  [node method-name]
  (let [node (unwrap-expr node)]
    (when-not (ast-utils/node-type? node :BlockOrLambda)
      (throw (ex-info (str method-name " expects a block argument")
                      {:node node})))
    node))

(defn- map-types
  [type-name]
  (when (and (string? type-name)
             (str/starts-with? type-name "Map<")
             (str/ends-with? type-name ">"))
    (let [inner (subs type-name 4 (dec (count type-name)))
          idx (str/index-of inner ", ")]
      (when idx
        {:key (subs inner 0 idx)
         :val (subs inner (+ idx 2))}))))

(defn- stringifyable?
  [type-name]
  (contains? #{"String" "Int" "Float" "Bool"} type-name))

(defn- expect-arity
  [owner method n arg-nodes]
  (when (not= n (count arg-nodes))
    (throw (ex-info (str owner "::" method " expected " n " arguments, got "
                         (count arg-nodes))
                    {:expected n :got (count arg-nodes)}))))

(defn- assert-arg-type
  [expected actual what]
  (when (and actual (not= expected actual))
    (throw (ex-info (str what " expected " expected ", got " actual)
                    {:expected expected :actual actual}))))

(def ^:private numeric-types #{"Int" "Float"})

(defn- numeric-type?
  [type-name]
  (contains? numeric-types type-name))

(defn- type-join
  "The least common type for values used in one expression.

  WCHNT currently has a two-level numeric lattice: Int is below Float,
  so mixing the two promotes the result to Float. Other types still need
  to match exactly here; interface assignability is handled separately.
  "
  [left right]
  (cond
    (= left right) left
    (and (numeric-type? left) (numeric-type? right)) "Float"
    :else nil))

(defn- type-assignable?
  [schema-ir expected actual]
  (or (nil? actual)
      (= expected actual)
      (and (numeric-type? expected)
           (numeric-type? actual)
           (= expected (type-join expected actual)))
      (contains? (set (interface-implementers schema-ir expected)) actual)))

(defn- assert-assignable!
  [schema-ir expected actual what]
  (when-not (type-assignable? schema-ir expected actual)
    (throw (ex-info (str what " expected " expected ", got " actual)
                    {:expected expected :actual actual}))))

(defn- length-call
  [receiver class-name method arg-nodes]
  (when (= method "length")
    (when-not (or (= class-name "String") (array-elem-type class-name))
      (throw (ex-info (str "'length' is only defined on strings and arrays, not "
                           class-name)
                      {:class-name class-name})))
    (expect-arity class-name "length" 0 arg-nodes)
    {:expr :call
     :receiver receiver
     :method "length"
     :args []
     :arg-types []
     :type "Int"}))

(defn- concat-call
  [receiver class-name method arg-nodes ctx]
  (when (and (= method "concat") (= class-name "String"))
    (expect-arity "String" "concat" 1 arg-nodes)
    (let [arg (convert-call-arg (first arg-nodes) ctx)
          arg-type (value-type arg ctx)]
      (when-not (or (nil? arg-type) (stringifyable? arg-type))
        (throw (ex-info (str "String::concat expected a string or number, got "
                             arg-type)
                        {:type arg-type})))
      {:expr :call
       :receiver receiver
       :method "concat"
       :args [arg]
       :arg-types [arg-type]
       :type "String"})))

(defn- cons-call
  [receiver class-name method arg-nodes ctx]
  (when (and (= method "cons") (array-elem-type class-name))
    (expect-arity "Array" "cons" 1 arg-nodes)
    (let [elem-type (array-elem-type class-name)
          elem (convert-call-arg (first arg-nodes) ctx)]
      (assert-assignable! (:schema-ir ctx) elem-type (value-type elem ctx) "Array::cons")
      {:expr :call
       :receiver receiver
       :method "cons"
       :args [elem]
       :arg-types [elem-type]
       :type class-name})))

(defn- put-call
  [receiver class-name method arg-nodes ctx]
  (when (= method "put")
    (let [types (map-types class-name)]
      (when-not types
        (throw (ex-info (str "'put' is only defined on maps, not " class-name)
                        {:class-name class-name})))
      (expect-arity "Map" "put" 2 arg-nodes)
      (let [k (convert-call-arg (first arg-nodes) ctx)
            v (convert-call-arg (second arg-nodes) ctx)]
        (assert-arg-type (:key types) (value-type k ctx) "Map::put key")
        (assert-arg-type (:val types) (value-type v ctx) "Map::put value")
        {:expr :call
         :receiver receiver
         :method "put"
         :args [k v]
         :arg-types [(:key types) (:val types)]
         :type class-name}))))

(defn- head-call
  [receiver class-name method arg-nodes]
  (when (= method "head")
    (let [elem-type (array-elem-type class-name)]
      (when-not elem-type
        (throw (ex-info (str "'head' is only defined on arrays, not " class-name)
                        {:class-name class-name})))
      (expect-arity "Array" "head" 0 arg-nodes)
      {:expr :call
       :receiver receiver
       :method "head"
       :args []
       :arg-types []
       :type elem-type})))

(defn- tail-call
  [receiver class-name method arg-nodes]
  (when (= method "tail")
    (let [elem-type (array-elem-type class-name)]
      (when-not elem-type
        (throw (ex-info (str "'tail' is only defined on arrays, not " class-name)
                        {:class-name class-name})))
      (expect-arity "Array" "tail" 0 arg-nodes)
      {:expr :call
       :receiver receiver
       :method "tail"
       :args []
       :arg-types []
       :type class-name})))

(defn- get-call
  [receiver class-name method arg-nodes ctx]
  (when (= method "get")
    (let [elem-type (array-elem-type class-name)
          types (map-types class-name)]
      (cond
        elem-type
        (do (expect-arity "Array" "get" 1 arg-nodes)
            (let [idx (convert-call-arg (first arg-nodes) ctx)]
              (assert-arg-type "Int" (value-type idx ctx) "Array::get")
              {:expr :call
               :receiver receiver
               :method "get"
               :on "Array"
               :args [idx]
               :arg-types ["Int"]
               :type elem-type}))

        types
        (let [n (count arg-nodes)]
          (when-not (or (= n 1) (= n 2))
            (throw (ex-info (str "Map::get expected 1 or 2 arguments, got " n)
                            {:expected "1 or 2" :got n})))
          (let [k (convert-call-arg (first arg-nodes) ctx)
                fallback (when (= n 2) (convert-call-arg (second arg-nodes) ctx))]
            (assert-arg-type (:key types) (value-type k ctx) "Map::get")
            (when fallback
              (assert-arg-type (:val types) (value-type fallback ctx) "Map::get"))
            {:expr :call
             :receiver receiver
             :method "get"
             :args (if fallback [k fallback] [k])
             :arg-types (if fallback [(:key types) (:val types)] [(:key types)])
             :type (:val types)}))

        :else
        (throw (ex-info (str "'get' is only defined on arrays and maps, not "
                             class-name)
                        {:class-name class-name}))))))

(defn- exists-call
  [receiver class-name method arg-nodes ctx]
  (when (= method "exists")
    (let [types (map-types class-name)]
      (when-not types
        (throw (ex-info (str "'exists' is only defined on maps, not " class-name)
                        {:class-name class-name})))
      (expect-arity "Map" "exists" 1 arg-nodes)
      (let [k (convert-call-arg (first arg-nodes) ctx)]
        (assert-arg-type (:key types) (value-type k ctx) "Map::exists")
        {:expr :call
         :receiver receiver
         :method "exists"
         :args [k]
         :arg-types [(:key types)]
         :type "Bool"}))))

(defn- remove-call
  [receiver class-name method arg-nodes ctx]
  (when (= method "remove")
    (let [types (map-types class-name)]
      (when-not types
        (throw (ex-info (str "'remove' is only defined on maps, not " class-name)
                        {:class-name class-name})))
      (expect-arity "Map" "remove" 1 arg-nodes)
      (let [k (convert-call-arg (first arg-nodes) ctx)]
        (assert-arg-type (:key types) (value-type k ctx) "Map::remove")
        {:expr :call
         :receiver receiver
         :method "remove"
         :args [k]
         :arg-types [(:key types)]
         :type class-name}))))

(defn- str-call
  [receiver class-name method arg-nodes]
  (when (= method "str")
    (when-not (stringifyable? class-name)
      (throw (ex-info (str "'str' is only defined on String, Int, Float, and Bool, not "
                           class-name)
                      {:class-name class-name})))
    (expect-arity class-name "str" 0 arg-nodes)
    {:expr :call
     :receiver receiver
     :method "str"
     :args []
     :arg-types []
     :type "String"}))

(defn- literal-string-keys
  [map-expr]
  (when (and (= :map (:expr map-expr))
             (every? #(= :string (get-in % [:key :expr])) (:pairs map-expr)))
    (set (map #(get-in % [:key :value]) (:pairs map-expr)))))

(defn- assert-literal-tpl-holes!
  [receiver arg]
  (when (= :string (:expr receiver))
    (when-let [keys (literal-string-keys arg)]
      (doseq [hole (template/holes (:value receiver))]
        (when-not (contains? keys hole)
          (throw (ex-info (str "String::tpl: missing '" hole "'")
                          {:hole hole})))))))

(defn- tpl-call
  [receiver class-name method arg-nodes ctx]
  (when (= method "tpl")
    (when (not= class-name "String")
      (throw (ex-info (str "'tpl' is only defined on strings, not " class-name)
                      {:class-name class-name})))
    (expect-arity "String" "tpl" 1 arg-nodes)
    (let [arg (convert-call-arg (first arg-nodes) ctx)]
      (assert-arg-type "Map<String, String>" (value-type arg ctx) "String::tpl")
      (assert-literal-tpl-holes! receiver arg)
      {:expr :call
       :receiver receiver
       :method "tpl"
       :args [arg]
       :arg-types ["Map<String, String>"]
       :type "String"})))

(defn- substring-call
  [receiver class-name method arg-nodes ctx]
  (when (= method "substring")
    (when (not= class-name "String")
      (throw (ex-info (str "'substring' is only defined on strings, not " class-name)
                      {:class-name class-name})))
    (expect-arity "String" "substring" 2 arg-nodes)
    (let [start (convert-call-arg (first arg-nodes) ctx)
          end (convert-call-arg (second arg-nodes) ctx)]
      (assert-arg-type "Int" (value-type start ctx) "String::substring start")
      (assert-arg-type "Int" (value-type end ctx) "String::substring end")
      {:expr :call
       :receiver receiver
       :method "substring"
       :args [start end]
       :arg-types ["Int" "Int"]
       :type "String"})))

(defn- times-call
  [receiver class-name method arg-nodes ctx]
  (when (= method "times")
    (when (not= class-name "Int")
      (throw (ex-info (str "'times' is only defined on Int, not " class-name)
                      {:class-name class-name})))
    (expect-arity "Int" "times" 1 arg-nodes)
    (let [owner (:class-name ctx)
          lam (ast->lambda (require-lambda (first arg-nodes) "times")
                           ["Int"] ctx owner "times")]
      {:expr :call
       :receiver receiver
       :method "times"
       :args [lam]
       :arg-types [(:type lam)]
       :type (str "Array<" (:type lam) ">")})))

(defn- array-combinator-call
  [receiver class-name method arg-nodes ctx elem-type]
  (case method
    "map"
    (do (expect-arity "Array" "map" 1 arg-nodes)
        (let [lam (ast->lambda (require-lambda (first arg-nodes) "map")
                               [elem-type] ctx "Array" "map")]
          {:expr :call
           :receiver receiver
           :method "map"
           :on "Array"
           :args [lam]
           :arg-types [(:type lam)]
           :type (str "Array<" (:type lam) ">")}))

    "filter"
    (do (expect-arity "Array" "filter" 1 arg-nodes)
        (let [lam (ast->lambda (require-lambda (first arg-nodes) "filter")
                               [elem-type] ctx "Array" "filter")]
          (when (not= "Bool" (:type lam))
            (throw (ex-info (str "Array::filter block must return Bool, got "
                                 (:type lam))
                            {:type (:type lam)})))
          {:expr :call
           :receiver receiver
           :method "filter"
           :on "Array"
           :args [lam]
           :arg-types ["Bool"]
           :type class-name}))

    "fold"
    (do (expect-arity "Array" "fold" 2 arg-nodes)
        (let [initial (ast->expr (first arg-nodes) ctx)
              acc-type (value-type initial ctx)]
          (when-not acc-type
            (throw (ex-info "Cannot determine type of fold initial value"
                            {:initial initial})))
          (let [lam (ast->lambda (require-lambda (second arg-nodes) "fold")
                                 [acc-type elem-type] ctx "Array" "fold")]
            (when (not= acc-type (:type lam))
              (throw (ex-info (str "Array::fold block must return " acc-type
                                   ", got " (:type lam))
                              {:expected acc-type :got (:type lam)})))
            {:expr :call
             :receiver receiver
             :method "fold"
             :on "Array"
             :args [initial lam]
             :arg-types [acc-type (:type lam)]
             :type acc-type})))

    nil))

(defn- map-type-name
  [key-type val-type]
  (str "Map<" key-type ", " val-type ">"))

(defn- map-combinator-call
  [receiver class-name method arg-nodes ctx {:keys [key val]}]
  (case method
    "map"
    (do (expect-arity "Map" "map" 1 arg-nodes)
        (let [lam (ast->lambda (require-lambda (first arg-nodes) "map")
                               [key val] ctx "Map" "map")]
          {:expr :call
           :receiver receiver
           :method "map"
           :on "Map"
           :args [lam]
           :arg-types [(:type lam)]
           :type (map-type-name key (:type lam))}))

    "filter"
    (do (expect-arity "Map" "filter" 1 arg-nodes)
        (let [lam (ast->lambda (require-lambda (first arg-nodes) "filter")
                               [key val] ctx "Map" "filter")]
          (when (not= "Bool" (:type lam))
            (throw (ex-info (str "Map::filter block must return Bool, got "
                                 (:type lam))
                            {:type (:type lam)})))
          {:expr :call
           :receiver receiver
           :method "filter"
           :on "Map"
           :args [lam]
           :arg-types ["Bool"]
           :type class-name}))

    "fold"
    (do (expect-arity "Map" "fold" 2 arg-nodes)
        (let [initial (ast->expr (first arg-nodes) ctx)
              acc-type (value-type initial ctx)]
          (when-not acc-type
            (throw (ex-info "Cannot determine type of fold initial value"
                            {:initial initial})))
          (let [lam (ast->lambda (require-lambda (second arg-nodes) "fold")
                                 [acc-type key val] ctx "Map" "fold")]
            (when (not= acc-type (:type lam))
              (throw (ex-info (str "Map::fold block must return " acc-type
                                   ", got " (:type lam))
                              {:expected acc-type :got (:type lam)})))
            {:expr :call
             :receiver receiver
             :method "fold"
             :on "Map"
             :args [initial lam]
             :arg-types [acc-type (:type lam)]
             :type acc-type})))

    nil))

(defn- combinator-call
  [receiver class-name method arg-nodes ctx]
  (when (contains? #{"map" "filter" "fold" "cons"} method)
    (let [elem-type (array-elem-type class-name)
          types (map-types class-name)]
      (cond
        elem-type
        (array-combinator-call receiver class-name method arg-nodes ctx elem-type)

        (and types (not= method "cons"))
        (map-combinator-call receiver class-name method arg-nodes ctx types)

        :else
        (throw (ex-info (str "'" method "' is only defined on "
                             (if (= method "cons")
                               "arrays"
                               "arrays and maps")
                             ", not " class-name)
                        {:class-name class-name :method method}))))))

(defn- numeric-call
  "Explicit conversions and rounding operations on Float values."
  [receiver class-name method arg-nodes]
  (when (and (= class-name "Float")
             (contains? #{"toInt" "floor" "ceil" "round"} method))
    (expect-arity "Float" method 0 arg-nodes)
    {:expr :call
     :receiver receiver
     :method method
     :numeric-op method
     :args []
     :arg-types []
     :type "Int"}))

(defn- builtin-call
  [receiver class-name method arg-nodes ctx]
  (or (numeric-call receiver class-name method arg-nodes)
      (length-call receiver class-name method arg-nodes)
      (concat-call receiver class-name method arg-nodes ctx)
      (cons-call receiver class-name method arg-nodes ctx)
      (head-call receiver class-name method arg-nodes)
      (tail-call receiver class-name method arg-nodes)
      (put-call receiver class-name method arg-nodes ctx)
      (get-call receiver class-name method arg-nodes ctx)
      (exists-call receiver class-name method arg-nodes ctx)
      (remove-call receiver class-name method arg-nodes ctx)
      (str-call receiver class-name method arg-nodes)
      (tpl-call receiver class-name method arg-nodes ctx)
      (substring-call receiver class-name method arg-nodes ctx)
      (times-call receiver class-name method arg-nodes ctx)
      (combinator-call receiver class-name method arg-nodes ctx)))

(defn- convert-call-arg
  [node ctx]
  (when (ast-utils/node-type? node :BlockOrLambda)
    (throw (ex-info "Blocks as call arguments are only valid for map, filter, fold, and times"
                    {:node node})))
  (ast->expr node ctx))

(defn- lookup-callee
  [class-name method-name arg-count ctx]
  (let [callee ((:lookup-method ctx) class-name method-name)
        expected (count (:parameters callee))]
    (when (not= expected arg-count)
      (throw (ex-info (str class-name "::" method-name " expected "
                           expected " arguments, got " arg-count)
                      {:class-name class-name
                       :method-name method-name
                       :expected expected
                       :got arg-count})))
    callee))

(defn- via-receiver
  [receiver via-fields result-type]
  (cond
    (empty? via-fields) receiver
    (= :path (:expr receiver))
    {:expr :path
     :root (:root receiver)
     :fields (into (vec (:fields receiver)) via-fields)
     :type result-type}
    :else
    {:expr :path
     :root receiver
     :fields (vec via-fields)
     :type result-type}))

(defn- host-arg-ok?
  [expected actual]
  (or (nil? actual)
      (= expected actual)
      (and (= expected "Float") (= actual "Int"))))

(defn- typed-external-call
  [receiver class-name method args spec ctx]
  (doseq [[arg expected] (map vector args (:arg-types spec))]
    (let [actual (value-type arg ctx)]
      (when-not (host-arg-ok? expected actual)
        (throw (ex-info (str class-name "::" method " expected " expected
                             ", got " actual)
                        {:class-name class-name :method method})))))
  {:expr :call
   :receiver receiver
   :method method
   :args args
   :arg-types (or (:arg-types spec) (vec (repeat (count args) nil)))
   :external-type class-name
   :type (:return spec)})

(defn- external-method-spec
  [ctx class-name method arity]
  (try
    (target-requires/method-spec
     (get-in (:schema-ir ctx) [:target-ir :requires])
     class-name method arity)
    (catch #?(:clj Exception :cljs :default) e
      (throw (ex-info
              (str (:class-name ctx) "::" (:method-name ctx)
                   ": " (ex-message e))
              (merge (ex-data e)
                     {:class-name (:class-name ctx)
                      :method-name (:method-name ctx)
                      :external-class class-name
                      :external-method method}))))))

(defn- external-call
  "Call an @ type using only the method declarations supplied by Target.
   Undeclared external methods remain opaque/fluent: the compiler does not
   invent a return type or inspect the platform class."
  [receiver class-name method arg-nodes ctx]
  (when (ir/external-type? (:schema-ir ctx) class-name)
    (let [args (mapv #(convert-call-arg % ctx) arg-nodes)]
      (if-let [spec (external-method-spec ctx class-name method (count args))]
        (typed-external-call receiver class-name method args spec ctx)
        {:expr :call
         :receiver receiver
         :method method
         :args args
         :arg-types (vec (repeat (count args) nil))
         :external-type class-name
         :type class-name}))))

(defn- imported-handle?
  [schema-ir class-name]
  (contains? (or (:imported-handles schema-ir) #{}) class-name))

(defn- assert-public-method!
  [schema-ir class-name method]
  (when (imported-handle? schema-ir class-name)
    (when-not (contains? (or (:public-methods schema-ir) #{}) [class-name method])
      (throw (ex-info (str class-name "::" method
                           " is not on the Public list of the imported assemblage")
                      {:class-name class-name :method-name method})))))

(defn- resolve-import-method
  "Resolve alias.factory or alias.staticPublicMethod."
  [alias fields method schema-ir]
  (let [info (get (:import-aliases schema-ir) alias)
        static (or (:static-public-methods info) #{})
        root (:root-class info)]
    (when-not info
      (throw (ex-info (str "Unknown import alias '" alias "'") {:alias alias})))
    (when (seq fields)
      (throw (ex-info (str "Imported assemblage calls cannot qualify a class: "
                           alias "." (str/join "." fields) "." method)
                      {:alias alias :fields fields :method method})))
    (when-not (or (= method "factory")
                  (contains? static [root method]))
      (throw (ex-info (str "'" method "' is not a static Public method on imported assemblage '"
                           (:page info) "'")
                      {:alias alias :method method})))
    info))

(defn expr-uses-call?
  "True when an expression IR contains a method/host call."
  [node]
  (cond
    (nil? node) false
    (map? node) (or (= :call (:expr node))
                    (some expr-uses-call? (vals node)))
    (sequential? node) (boolean (some expr-uses-call? node))
    :else false))

(defn expr-uses-instance?
  "True when a method IR (or expression) reads this or a field of this."
  [node]
  (cond
    (nil? node) false
    (map? node)
    (or (contains? #{:this :field} (:expr node))
        (and (= :path (:expr node))
             (expr-uses-instance? (:root node)))
        (some expr-uses-instance? (vals node)))
    (sequential? node) (boolean (some expr-uses-instance? node))
    :else false))

(defn- method-uses-instance?
  [method]
  (or (expr-uses-instance? (:body method))
      (expr-uses-instance? (:lets method))))

(defn- import-call
  [receiver fields method arg-list ctx]
  (let [alias (:name receiver)
        info (resolve-import-method alias fields method (:schema-ir ctx))
        class-name (:root-class info)
        arg-nodes (method-arg-items arg-list)
        args (mapv #(convert-call-arg % ctx) arg-nodes)
        callee (if (= method "factory")
                  {:parameters (mapv (fn [{:keys [name type]}]
                                       {:name name :type type})
                                     (or (:factory-params info) []))
                   :return-type class-name}
                  (lookup-callee class-name method (count args) ctx))]
    (when (= method "factory")
      (when (not= (count (:parameters callee)) (count args))
        (throw (ex-info (str "factory expected " (count (:parameters callee))
                             " arguments, got " (count args))
                        {:alias alias}))))
    (when (and (not= method "factory")
               (not (:static? callee)))
      (throw (ex-info (str class-name "::" method
                           " is an instance method; call it on the handle returned by factory")
                       {:class-name class-name :method-name method})))
    (when (and (ir/mutating-method-name? method)
               (not (:mutating? ctx)))
      (throw (ex-info (str "Pure method cannot call mutating method "
                           class-name "::" method)
                      {:class-name class-name :method-name method})))
    {:expr :call
     :receiver receiver
     :import-class class-name
     :import-assemblage (:assemblage-class info)
     :method method
     :args args
     :arg-types (mapv :type (:parameters callee))
     :type (:return-type callee)}))

(defn- make-call
  [receiver method arg-list ctx]
  (if (= :import-alias (:expr receiver))
    (import-call receiver [] method arg-list ctx)
    (let [arg-nodes (method-arg-items arg-list)
          class-name (receiver-class receiver ctx)]
      (or (builtin-call receiver class-name method arg-nodes ctx)
          (and (not (imported-handle? (:schema-ir ctx) class-name))
               (external-call receiver class-name method arg-nodes ctx))
            (let [args (mapv #(convert-call-arg % ctx) arg-nodes)
                callee (lookup-callee class-name method (count args) ctx)
                via (or (ir/delegate-path (:schema-ir ctx) class-name (:class callee)) [])]
            (when (and (ir/mutating-method-name? (:method-name callee))
                       (not (:mutating? ctx)))
              (throw (ex-info (str "Pure method cannot call mutating method "
                                   class-name "::" method)
                              {:class-name class-name :method-name method})))
            (doseq [[arg param] (map vector args (:parameters callee))]
              (assert-assignable! (:schema-ir ctx) (:type param) (value-type arg ctx)
                                  (str class-name "::" method)))
            {:expr :call
             :receiver (via-receiver receiver via (:class callee))
             :method method
             :args args
             :arg-types (mapv :type (:parameters callee))
             :type (:return-type callee)})))))

(defn- ast->call
  [node ctx]
  (let [root (ast->expr (second node) ctx)
        first-step (take-call-step (drop 2 node))]
    (if (= :import-alias (:expr root))
      (let [call (import-call root (:fields first-step) (:method first-step)
                              (:args first-step) ctx)]
        (if (empty? (:more first-step))
          call
          (loop [call call
                 remaining (:more first-step)]
            (if (empty? remaining)
              call
              (let [step (take-call-step remaining)]
                (when (seq (:fields step))
                  (throw (ex-info "Cannot walk fields after a method call"
                                  {:fields (:fields step)})))
                (recur (make-call call (:method step) (:args step) ctx)
                       (:more step)))))))
      (let [first-recv (apply-fields root (:fields first-step) ctx)]
        (loop [call (make-call first-recv (:method first-step) (:args first-step) ctx)
               remaining (:more first-step)]
          (if (empty? remaining)
            call
            (let [step (take-call-step remaining)]
              (when (seq (:fields step))
                (throw (ex-info "Cannot walk fields after a method call"
                                {:fields (:fields step)})))
              (recur (make-call call (:method step) (:args step) ctx)
                     (:more step)))))))))

(defn construction-call->ir
  "Lower a construction-slot MethodCall AST using import aliases on schema-ir."
  [node schema-ir index]
  (let [ctx {:schema-ir schema-ir
             :class-name "_Construction"
             :param-names #{}
             :let-names #{}
             :let-types {}
             :param-types {}
             :lookup-method (fn [class-name method-name]
                              (or (get (:imported-methods schema-ir)
                                       [class-name method-name])
                                  (throw (ex-info (str "Unknown method "
                                                       class-name "::" method-name
                                                       " in construction")
                                                  {:class-name class-name
                                                   :method-name method-name}))))
             :target-fns {}}
        expr (ast->expr node ctx)]
    {:type :call
     :class-name (or (:type expr) "Unknown")
     :expr expr
     :args []
     :index index}))

(defn- with-path-segments
  [node]
  (cond
    (ast-utils/node-type? node :WithPath) (with-path-segments (second node))
    (ast-utils/node-type? node :FieldPath) (vec (rest node))
    (ast-utils/node-type? node :VariableRef) [(second node)]
    :else (throw (ex-info "Write-path must be a field name or dotted path"
                          {:node node}))))

(defn- with-assign-entries
  [assign-list]
  (when-not (ast-utils/node-type? assign-list :WithAssignList)
    (throw (ex-info "Write-path construction is missing assignments"
                    {:node assign-list})))
  (mapv (fn [assign]
          (when-not (ast-utils/node-type? assign :WithAssign)
            (throw (ex-info "Expected field = expr in write-path"
                            {:node assign})))
          {:path (with-path-segments (second assign))
           :value (nth assign 2)})
        (rest assign-list)))

(defn- add-with-slot
  [slot rest-path value class-name field-name]
  (if (empty? rest-path)
    (do
      (when (:exact slot)
        (throw (ex-info (str "Duplicate write-path '" field-name "' on " class-name)
                        {:class-name class-name :field field-name})))
      (when (seq (:nested slot))
        (throw (ex-info (str "Cannot assign '" field-name "' and also a path under it on "
                             class-name)
                        {:class-name class-name :field field-name})))
      (assoc slot :exact value))
    (do
      (when (:exact slot)
        (throw (ex-info (str "Cannot assign '" field-name "' and also a path under it on "
                             class-name)
                        {:class-name class-name :field field-name})))
      (update slot :nested conj {:path rest-path :value value}))))

(defn- group-with-assigns
  [assigns class-name]
  (reduce (fn [acc {:keys [path value]}]
            (when (empty? path)
              (throw (ex-info (str "Empty write-path on " class-name)
                              {:class-name class-name})))
            (let [field (first path)]
              (update acc field
                      (fn [slot]
                        (add-with-slot (or slot {:nested []})
                                       (vec (rest path))
                                       value class-name field)))))
          {}
          assigns))

(defn- assert-known-with-fields!
  [grouped components class-name]
  (let [known (set (map :component-name components))
        extra (remove known (keys grouped))]
    (when (seq extra)
      (throw (ex-info (str "Unknown field '" (first extra) "' on " class-name
                           " in write-path")
                      {:class-name class-name :field (first extra)})))))

(defn- read-component
  "IR that reads one component from a with-construction source."
  [source-ir class-name field-name schema-ir]
  (let [typ (follow-field schema-ir class-name field-name)]
    (if (= :this (:expr source-ir))
      {:expr :field :name field-name}
      (let [root (if (= :path (:expr source-ir)) (:root source-ir) source-ir)
            fields (if (= :path (:expr source-ir))
                     (conj (vec (:fields source-ir)) field-name)
                     [field-name])]
        {:expr :path :root root :fields fields :type typ}))))

(defn- field-type-at-path
  [schema-ir class-name path]
  (reduce (fn [typ field]
            (follow-field schema-ir typ field))
          class-name
          path))

(declare lower-with)

(defn- lower-with-field
  [class-name source-ir component grouped ctx]
  (let [field (:component-name component)
        typ (:type-name component)
        hits (get grouped field)]
    (cond
      (nil? hits)
      (read-component source-ir class-name field (:schema-ir ctx))

      (:exact hits)
      (let [value (ast->expr (:exact hits) ctx)]
        (assert-assignable! (:schema-ir ctx) typ (value-type value ctx)
                            (str class-name "." field))
        value)

      :else
      (lower-with typ
                  (read-component source-ir class-name field (:schema-ir ctx))
                  (:nested hits)
                  ctx))))

(defn- expand-with-assigns
  [schema-ir class-name assigns]
  (mapv (fn [{:keys [path value]}]
          {:path (:fields (ir/expand-field-path schema-ir class-name path))
           :value value})
        assigns))

(defn- lower-with
  "Expand [:Class | path = expr] into a full :construct of every schema field."
  [class-name source-ir assigns ctx]
  (let [schema-ir (:schema-ir ctx)
        components (or (ir/get-assemblage-components schema-ir class-name) [])]
    (when (empty? components)
      (throw (ex-info (str "Cannot use write-path construction on '" class-name
                           "' (not a composition class)")
                      {:class-name class-name})))
    (let [assigns (expand-with-assigns schema-ir class-name assigns)
          grouped (group-with-assigns assigns class-name)]
      (assert-known-with-fields! grouped components class-name)
      (doseq [{:keys [path]} assigns]
        (field-type-at-path schema-ir class-name path))
      {:expr :construct
       :class-name class-name
       :args (mapv #(lower-with-field class-name source-ir % grouped ctx)
                   components)})))

(defn- with-source-ir
  [source-node class-name ctx]
  (if (nil? source-node)
    (do
      (when (not= (:class-name ctx) class-name)
        (throw (ex-info (str "[:" class-name " | ...] needs a source because this is "
                             (:class-name ctx)
                             " (e.g. [:" class-name " ball | x = nx])")
                        {:class-name class-name :this (:class-name ctx)})))
      {:expr :this})
    (let [src (ast->expr source-node ctx)
          typ (value-type src ctx)]
      (when-not typ
        (throw (ex-info (str "[:" class-name " | ...] cannot determine source type")
                        {:class-name class-name})))
      (when (not= typ class-name)
        (throw (ex-info (str "[:" class-name " | ...] source must be " class-name
                             ", got " typ)
                        {:expected class-name :actual typ})))
      src)))

(defn- ast->with
  "[:Class | path = expr] or [:Class src | path = expr]. Lowers to :construct."
  [node ctx]
  (let [class-name (second (second node))
        mid (nth node 2)
        source-node (when (ast-utils/node-type? mid :WithSource) mid)
        assign-list (if source-node (nth node 3) mid)]
    (when (contains? (or (:imported-handles (:schema-ir ctx)) #{}) class-name)
      (throw (ex-info (str "Cannot construct imported handle '" class-name
                           "'; call a public method that returns one")
                      {:class-name class-name})))
    (when-not (contains? (assemblage-names (:schema-ir ctx)) class-name)
      (throw (ex-info (str "Unknown class '" class-name "' in write-path construction")
                      {:class-name class-name})))
    (let [source-inner (when source-node (second source-node))
          source (with-source-ir source-inner class-name ctx)
          assigns (with-assign-entries assign-list)]
      (lower-with class-name source assigns ctx))))

(defn- ast->construct
  "ObjectConstruction or InnerObjectConstruction with an explicit class name."
  [node ctx]
  (let [class-node (second node)]
    (when-not (ast-utils/node-type? class-node :ClassName)
      (throw (ex-info "Nested construction in a method must name the class, e.g. [:Ball ...]"
                      {:node node})))
    (let [class-name (second class-node)
          args (arg-list-exprs (nth node 2) ctx)]
      (validate-construct! class-name args (:schema-ir ctx))
      {:expr :construct :class-name class-name :args args})))

(defn- named-inner-construct
  [node class-name]
  (if (ast-utils/node-type? (second node) :ClassName)
    node
    (into [:InnerObjectConstruction [:ClassName class-name]] (rest node))))

(defn- ast->array-item
  [node elem-type ctx]
  (let [node (unwrap-expr node)]
    (if (ast-utils/node-type? node :InnerObjectConstruction)
      (let [expr (ast->construct (named-inner-construct node elem-type) ctx)]
        (assert-assignable! (:schema-ir ctx) elem-type (:class-name expr) "array element")
        expr)
      (let [expr (ast->expr node ctx)]
        (assert-assignable! (:schema-ir ctx) elem-type (value-type expr ctx) "array element")
        expr))))

(defn- ast->array
  [node ctx]
  (let [type-node (second node)
        elem-type (if (ast-utils/node-type? type-node :Type)
                    (second type-node)
                    (throw (ex-info "Array construction missing type"
                                    {:node node})))
        arg-list (nth node 2)
        items (if (ast-utils/node-type? arg-list :ArgList)
                (mapv #(ast->array-item % elem-type ctx) (rest arg-list))
                [])]
    {:expr :array
     :elem-type elem-type
     :items items
     :type (str "Array<" elem-type ">")}))

(defn- ast->map-pair
  [pair key-type val-type ctx]
  (when-not (ast-utils/node-type? pair :KeyValuePair)
    (throw (ex-info "Expected a key-value pair in map construction"
                    {:node pair})))
  (let [k (ast->expr (second pair) ctx)
        v (ast->expr (nth pair 2) ctx)]
    (assert-arg-type key-type (value-type k ctx) "map key")
    (assert-arg-type val-type (value-type v ctx) "map value")
    {:key k :value v}))

(defn- ast->map
  [node ctx]
  (let [key-type (second (second node))
        val-type (second (nth node 2))
        kv-list (get node 3)
        pairs (if (ast-utils/node-type? kv-list :KeyValueList)
                (mapv #(ast->map-pair % key-type val-type ctx) (rest kv-list))
                [])]
    {:expr :map
     :key-type key-type
     :val-type val-type
     :pairs pairs
     :type (str "Map<" key-type ", " val-type ">")}))

(defn- ast->target-call
  [node ctx]
  (let [name (second (second node))
        args (mapv #(ast->expr % ctx) (method-arg-items (nth node 2 nil)))
        fn-name (get (:target-fns ctx) name)]
    (when-not fn-name
      (throw (ex-info (str "Unknown target function '%" name "'")
                      {:name name})))
    (when (empty? args)
      (throw (ex-info (str "%" name " needs an argument so the value can pass through")
                      {:name name})))
    {:expr :target-call
     :name name
     :haxe-name fn-name
     :args args
     :type (value-type (first args) ctx)}))

(defn- ast->expr
  [node ctx]
  (let [node (unwrap-expr node)]
    (cond
      (string? node)
      node

      (ast-utils/node-type? node :IntLiteral)
      {:expr :int :value (ast-utils/parse-int (second node))}

      (ast-utils/node-type? node :FloatLiteral)
      {:expr :float :value (ast-utils/parse-float (second node))}

      (ast-utils/node-type? node :BoolLiteral)
      {:expr :bool :value (= "true" (second node))}

      (ast-utils/node-type? node :StringLiteral)
      {:expr :string :value (second node)}

      (ast-utils/node-type? node :FieldPath)
      (ast->path node ctx)

      (ast-utils/node-type? node :VariableRef)
      (resolve-name (second node) ctx)

      (ast-utils/node-type? node :AddOp)
      (make-arith (convert-chain-parts (rest node) ctx) ctx)

      (ast-utils/node-type? node :MulOp)
      (make-arith (convert-chain-parts (rest node) ctx) ctx)

      (ast-utils/node-type? node :AndOp)
      {:expr :and :args (mapv #(ast->expr % ctx) (rest node))}

      (ast-utils/node-type? node :OrOp)
      {:expr :or :args (mapv #(ast->expr % ctx) (rest node))}

      (ast-utils/node-type? node :NotOp)
      {:expr :not :arg (ast->expr (second node) ctx)}

      (ast-utils/node-type? node :CmpOp)
      {:expr :cmp
       :op (nth node 2)
       :left (ast->expr (second node) ctx)
       :right (ast->expr (nth node 3) ctx)}

      (ast-utils/node-type? node :ArrayConstruction)
      (ast->array node ctx)

      (ast-utils/node-type? node :MapConstruction)
      (ast->map node ctx)

      (ast-utils/node-type? node :WithConstruction)
      (ast->with node ctx)

      (or (ast-utils/node-type? node :ObjectConstruction)
          (ast-utils/node-type? node :InnerObjectConstruction))
      (ast->construct node ctx)

      (ast-utils/node-type? node :MethodCall)
      (ast->call node ctx)

      (ast-utils/node-type? node :TargetCommand)
      (ast->target-call node ctx)

      (ast-utils/node-type? node :IfExpr)
      (ast->if node ctx)

      (ast-utils/node-type? node :NegOp)
      {:expr :neg :arg (ast->expr (second node) ctx)}

      :else
      (throw (ex-info "Unsupported expression in reaction method"
                      {:node node})))))

(defn- note-param-type
  [types-atom expr type-name]
  (when (= :param (:expr expr))
    (swap! types-atom update (:name expr) (fnil conj #{}) type-name)))

(defn- known-param-type
  [expr types-atom]
  (when (= :param (:expr expr))
    (let [types (get @types-atom (:name expr))]
      (when (= 1 (count types))
        (first types)))))

(defn- contains-float?
  [expr types-atom]
  (case (:expr expr)
    :float true
    :param (= "Float" (known-param-type expr types-atom))
    :neg (contains-float? (:arg expr) types-atom)
    :arith (some #(contains-float? % types-atom) (:parts expr))
    :call (= "Float" (:type expr))
    :field (= "Float" (:type expr))
    false))

(defn- infer-param-types-from
  ([expr types-atom]
   (infer-param-types-from expr types-atom nil))
  ([expr types-atom numeric-context]
   (case (:expr expr)
    :arith
    (let [target (if (contains-float? expr types-atom) "Float"
                     (or numeric-context "Int"))]
      (doseq [part (:parts expr)]
        (when (map? part)
          (note-param-type types-atom part target)
          (infer-param-types-from part types-atom target))))

    :cmp
    (let [target (if (or (contains-float? (:left expr) types-atom)
                        (contains-float? (:right expr) types-atom))
                   "Float"
                   (or numeric-context "Int"))]
      (note-param-type types-atom (:left expr) target)
      (note-param-type types-atom (:right expr) target)
      (infer-param-types-from (:left expr) types-atom target)
      (infer-param-types-from (:right expr) types-atom target))

    :and
    (doseq [arg (:args expr)]
      (note-param-type types-atom arg "Bool")
      (infer-param-types-from arg types-atom))

    :or
    (doseq [arg (:args expr)]
      (note-param-type types-atom arg "Bool")
      (infer-param-types-from arg types-atom))

    :not
    (do (note-param-type types-atom (:arg expr) "Bool")
        (infer-param-types-from (:arg expr) types-atom))

    :construct
    (doseq [arg (:args expr)]
      (infer-param-types-from arg types-atom))

    :path
    (infer-param-types-from (:root expr) types-atom)

    :call
    (do (infer-param-types-from (:receiver expr) types-atom)
        (doseq [[arg type-name] (map vector (:args expr) (:arg-types expr))]
          (note-param-type types-atom arg type-name)
          (infer-param-types-from arg types-atom)))

    :target-call
    (doseq [arg (:args expr)]
      (infer-param-types-from arg types-atom))

    :if
    (do (note-param-type types-atom (:condition expr) "Bool")
        (infer-param-types-from (:condition expr) types-atom)
        (doseq [branch [(:then expr) (:else expr)]]
          (doseq [l (:lets branch)]
            (infer-param-types-from (:value l) types-atom))
          (infer-param-types-from (:body branch) types-atom)))

    :lambda
    (do (doseq [l (:lets expr)]
          (infer-param-types-from (:value l) types-atom))
        (infer-param-types-from (:body expr) types-atom))

    :neg
    (let [target (or numeric-context
                    (known-param-type (:arg expr) types-atom)
                    "Int")]
      (note-param-type types-atom (:arg expr) target)
      (infer-param-types-from (:arg expr) types-atom target))

    :array
    (doseq [item (:items expr)]
      (infer-param-types-from item types-atom))

    :map
    (doseq [pair (:pairs expr)]
      (infer-param-types-from (:key pair) types-atom)
      (infer-param-types-from (:value pair) types-atom))

    nil)))

(defn- inferred-param-type
  [param-name types-map]
  (let [found (get types-map param-name)]
    (cond
      (nil? found)
      (throw (ex-info (str "Cannot infer type for parameter '" param-name
                           "'. Add an explicit annotation (e.g. Type/"
                           param-name ")")
                      {:param-name param-name}))

      (> (count found) 1)
      (throw (ex-info (str "Parameter '" param-name "' used as both "
                           (str/join " and " found))
                      {:param-name param-name :types found}))

      :else
      (first found))))

(defn- infer-parameters
  [param-names exprs explicit-types schema-ir]
  (let [types-atom (atom (into {}
                              (map (fn [[name type-name]]
                                     [name #{type-name}])
                                   explicit-types)))]
    (doseq [expr exprs]
      (infer-param-types-from expr types-atom))
    (mapv (fn [name]
            (let [explicit (get explicit-types name)
                  inferred (when-not explicit
                             (inferred-param-type name @types-atom))]
              (cond
                (and explicit inferred (not= explicit inferred))
                (throw (ex-info (str "Parameter '" name "' annotated as " explicit
                                     " but used as " inferred)
                                {:param-name name :explicit explicit :inferred inferred}))

                explicit
                (do (assert-valid-type! schema-ir explicit {:param-name name})
                    {:name name :type explicit})

                inferred
                {:name name :type inferred}

                :else
                (throw (ex-info (str "Cannot infer type for parameter '" name
                                     "'. Add an explicit annotation (e.g. Type/" name ")")
                                {:param-name name})))))
          param-names)))

(defn- expr-return-type
  [expr schema-ir class-name param-type-by-name let-types]
  (case (:expr expr)
    :int "Int"
    :float "Float"
    :bool "Bool"
    :string "String"
    :arith (:type expr)
    :cmp "Bool"
    :and "Bool"
    :or "Bool"
    :not "Bool"
    :construct (:class-name expr)
    :array (:type expr)
    :map (:type expr)
    :field (binding-type schema-ir class-name (:name expr))
    :param (get param-type-by-name (:name expr))
    :local (get let-types (:name expr))
    :path (:type expr)
    :this class-name
    :call (:type expr)
    :target-call (:type expr)
    :if (:type expr)
    :neg (expr-return-type (:arg expr) schema-ir class-name param-type-by-name let-types)
    :lambda (:type expr)
    (throw (ex-info "Cannot determine return type of method body"
                    {:expr expr}))))

(defn- let-type-map
  [lets schema-ir class-name param-type-by-name]
  (reduce (fn [types {:keys [name value]}]
            (assoc types name
                   (expr-return-type value schema-ir class-name param-type-by-name types)))
          {}
          lets))

(defn- lambda-arg-info
  "Parse one :LambdaArg node into {:name string :type string-or-nil}."
  [node]
  (let [inner (second node)]
    (cond
      (= (first inner) :ExternalLambdaArg)
      {:name (second (nth inner 2))
       :type (second (nth inner 1))}

      (= (first inner) :TypedLambdaArg)
      {:name (second (nth inner 2))
       :type (second (nth inner 1))}

      (= (first inner) :VariableName)
      {:name (second inner) :type nil}

      :else
      (throw (ex-info "Expected ExternalLambdaArg, TypedLambdaArg, or VariableName"
                      {:node node})))))

(defn- external-types-from-lambda-args
  [lambda-args-node]
  (when (ast-utils/node-type? lambda-args-node :LambdaArgs)
    (into #{}
          (keep (fn [arg-node]
                  (let [inner (second arg-node)]
                    (when (= (first inner) :ExternalLambdaArg)
                      (second (nth inner 1)))))
                (rest lambda-args-node)))))

(defn- external-types-from-method-def
  [method-node]
  (let [body-node (first (filter #(= (first %) :BlockOrLambda) (rest method-node)))
        inner (second body-node)]
    (when (ast-utils/node-type? inner :Lambda)
      (external-types-from-lambda-args (second inner)))))

(defn collect-external-types-from-reaction-ast
  "Collect @Type names from Methods lambda parameters."
  [reaction-ast]
  (when (ast-utils/node-type? reaction-ast :Code)
    (into #{} (mapcat external-types-from-method-def)
          (filter #(ast-utils/node-type? % :MethodDefinition) (rest reaction-ast)))))

(defn merge-external-types
  "Merge extra @ type names into schema IR (schema @ fields + Methods @ params)."
  [schema-ir extra-types]
  (update schema-ir :external-types #(into (or % #{}) extra-types)))

(defn- lambda-args-info
  [lambda-args-node]
  (when-not (ast-utils/node-type? lambda-args-node :LambdaArgs)
    (throw (ex-info "Expected LambdaArgs node" {:node lambda-args-node})))
  (mapv lambda-arg-info (rest lambda-args-node)))

(defn- lambda-param-names
  [lambda-node]
  (let [maybe-args (second lambda-node)]
    (if (ast-utils/node-type? maybe-args :LambdaArgs)
      (mapv :name (lambda-args-info maybe-args))
      [])))

(defn- lambda-explicit-param-types
  [lambda-node]
  (let [maybe-args (second lambda-node)]
    (if (ast-utils/node-type? maybe-args :LambdaArgs)
      (into {} (keep (fn [{:keys [name type]}]
                       (when type [name type]))
                     (lambda-args-info maybe-args)))
      {})))

(defn- lambda-statements
  [lambda-node]
  (let [maybe-args (second lambda-node)]
    (if (ast-utils/node-type? maybe-args :LambdaArgs)
      (nth lambda-node 2)
      (second lambda-node))))

(defn- block-statement-nodes
  [block-statements-node]
  (if (and block-statements-node
           (ast-utils/node-type? block-statements-node :BlockStatements))
    (vec (rest block-statements-node))
    []))

(defn- block-statements
  [block-or-lambda]
  (let [inner (second block-or-lambda)]
    (cond
      (ast-utils/node-type? inner :Block)
      {:param-names []
       :explicit-param-types {}
       :statements (block-statement-nodes (second inner))}

      (ast-utils/node-type? inner :Lambda)
      {:param-names (lambda-param-names inner)
       :explicit-param-types (lambda-explicit-param-types inner)
       :statements (block-statement-nodes (lambda-statements inner))}

      :else
      (throw (ex-info "Method body must be a block or lambda"
                      {:node block-or-lambda})))))

(defn- throw-rebind
  [name class-name method-name]
  (throw (ex-info (str "Cannot rebind '" name "' in " class-name "::" method-name)
                  {:name name :class-name class-name :method-name method-name})))

(defn- assert-fresh-let-name
  [name ctx class-name method-name]
  (when (or (= name "this")
            (contains? (:let-names ctx) name)
            (contains? (:param-names ctx) name)
            (contains? (field-names (:schema-ir ctx) (:class-name ctx)) name)
            (context-parent-type (:schema-ir ctx) (:class-name ctx) name))
    (throw-rebind name class-name method-name)))

(defn- arith-result-type
  "Join numeric operand types, rejecting opaque/non-numeric operands."
  [parts ctx]
  (let [operand-types (keep (fn [part]
                              (when (map? part)
                                (value-type part ctx)))
                            parts)
        string-expression? (and (seq operand-types)
                                (every? #(= "String" %) operand-types))
        invalid-part (some (fn [part]
                             (when (and (map? part)
                                        (let [type (value-type part ctx)]
                                          (and type
                                               (not (or (numeric-type? type)
                                                        (= "String" type))))))
                               part))
                           parts)
        invalid (some #(when-not (or (numeric-type? %) (= "String" %)) %)
                      operand-types)
        owner (str (:class-name ctx) "::" (:method-name ctx))
        external-class (:external-type invalid-part)
        external-method (:method invalid-part)
        hint (when (and external-class external-method)
               (str " Add " external-class "::" external-method
                    "(...) -> <return-type> to Target %requires."))]
    (cond
      string-expression? "String"
      invalid (throw (ex-info (str "Arithmetic expects Int or Float, got " invalid
                                   " in " owner
                                   (when (and external-class external-method)
                                     (str "; this came from external call "
                                          external-class "::" external-method))
                                   "."
                                   hint)
                              {:type invalid
                               :class-name (:class-name ctx)
                               :method-name (:method-name ctx)
                               :external-class external-class
                               :external-method external-method
                               :hint hint}))
      (some #(= "Float" %) operand-types) "Float"
      :else "Int")))

(declare value-type)

(defn- make-arith
  [parts ctx]
  {:expr :arith
   :parts parts
   :type (arith-result-type parts ctx)})

(defn- value-type
  [expr ctx]
  (case (:expr expr)
    :path (:type expr)
    :this (:class-name ctx)
    :call (:type expr)
    :target-call (:type expr)
    :if (:type expr)
    :neg (value-type (:arg expr) ctx)
    :lambda (:type expr)
    :param (get (:param-types ctx) (:name expr))
    :field (binding-type (:schema-ir ctx) (:class-name ctx) (:name expr))
    :local (get (:let-types ctx) (:name expr))
    :arith (or (:type expr) (arith-result-type (:parts expr) ctx))
    :int "Int"
    :float "Float"
    :bool "Bool"
    :string "String"
    :cmp "Bool"
    :and "Bool"
    :or "Bool"
    :not "Bool"
    :construct (:class-name expr)
    :array (:type expr)
    :map (:type expr)
    :enum (:type expr)
    nil))

(defn- bind-let
  [stmt ctx class-name method-name]
  (let [name (second (second stmt))]
    (assert-fresh-let-name name ctx class-name method-name)
    (let [value (ast->expr (nth stmt 2) ctx)]
      {:let {:name name :value value}
       :ctx (-> ctx
                (update :let-names (fnil conj #{}) name)
                (assoc-in [:let-types name] (value-type value ctx)))})))

(defn- process-nonfinal-statement
  [stmt ctx class-name method-name]
  (when-not (ast-utils/node-type? stmt :Assignment)
    (throw (ex-info (str class-name "::" method-name
                         " can only use let-bindings before the final expression")
                    {:statement stmt})))
  (bind-let stmt ctx class-name method-name))

(defn- process-final-statement
  [stmt ctx lets class-name method-name]
  (cond
    (ast-utils/node-type? stmt :Assignment)
    (let [{:keys [let]} (bind-let stmt ctx class-name method-name)]
      {:lets (conj lets let)
       :body {:expr :local :name (:name let)}})

    (ast-utils/node-type? stmt :Expression)
    {:lets lets
     :body (ast->expr stmt ctx)}

    :else
    (throw (ex-info (str class-name "::" method-name " body must end in an expression")
                    {:statement stmt}))))

(defn- process-statements
  [stmt-nodes ctx class-name method-name]
  (let [stmts (vec stmt-nodes)]
    (when (empty? stmts)
      (throw (ex-info (str class-name "::" method-name " has an empty body")
                      {:class-name class-name :method-name method-name})))
    (let [nonfinal (pop stmts)
          final (peek stmts)
          acc (reduce (fn [{:keys [ctx lets]} stmt]
                        (let [bound (process-nonfinal-statement stmt ctx class-name method-name)]
                          {:ctx (:ctx bound)
                           :lets (conj lets (:let bound))}))
                      {:ctx ctx
                       :lets []}
                      nonfinal)]
      (process-final-statement final (:ctx acc) (:lets acc) class-name method-name))))

(defn- method-def-parts
  "Extract class, method name, optional return annotation, and body from a MethodDefinition node."
  [method-node]
  (let [children (rest method-node)
        class-name (second (first (filter #(= (first %) :ClassName) children)))
        method-name (second (first (filter #(= (first %) :MethodName) children)))
        return-ann (first (filter #(= (first %) :ReturnAnn) children))
        return-type (when return-ann (second (second return-ann)))
        body-node (first (filter #(= (first %) :BlockOrLambda) children))]
    {:class-name class-name
     :method-name method-name
     :return-type return-type
     :body-node body-node}))

(defn- assert-not-reserved-method!
  [class-name method-name]
  (when (= "inject" method-name)
    (throw (ex-info (str class-name "::inject is reserved for the Target harness")
                    {:class-name class-name :method-name method-name}))))

(defn- validate-method-owner!
  [schema-ir class-name method-name]
  (when-not (or (contains? (assemblage-names schema-ir) class-name)
                (contains? (interface-names schema-ir) class-name))
    (throw (ex-info (str "Unknown class or interface '" class-name "' in method "
                         class-name "::" method-name)
                    {:class-name class-name :method-name method-name}))))

(defn- method-header
  [method-node schema-ir]
  (let [{:keys [class-name method-name]} (method-def-parts method-node)]
    (validate-method-owner! schema-ir class-name method-name)
    [class-name method-name]))

(defn- return-types-match?
  "Implementation may return a concrete class when the signature returns the interface."
  [schema-ir interface-name signature-return impl-return impl-class]
  (or (= signature-return impl-return)
      (and (= signature-return interface-name)
           (contains? (set (interface-implementers schema-ir interface-name)) impl-class))))

(defn- signatures-match?
  [schema-ir signature implementation]
  (and (return-types-match? schema-ir (:class signature)
                            (:return-type signature)
                            (:return-type implementation)
                            (:class implementation))
       (= (mapv :name (:parameters signature))
          (mapv :name (:parameters implementation)))
       (= (mapv :type (:parameters signature))
          (mapv :type (:parameters implementation)))
       (= (boolean (:mutating? signature))
          (boolean (:mutating? implementation)))))

(defn- signatures-for
  [methods-ir schema-ir iface-name]
  (let [from-page (filterv #(and (= iface-name (:class %))
                                 (:interface-signature? %))
                           methods-ir)
        from-import (filterv #(and (= iface-name (:class %))
                                   (:interface-signature? %))
                             (vals (or (:imported-methods schema-ir) {})))]
    (if (seq from-page) from-page from-import)))

(defn- check-implementer-method!
  [schema-ir by-key iface-name signature impl-name]
  (let [impl (get by-key [impl-name (:method-name signature)])]
    (cond
      (nil? impl)
      (throw (ex-info (str impl-name " must implement " iface-name
                           "::" (:method-name signature))
                      {:interface iface-name
                       :implementer impl-name
                       :method-name (:method-name signature)}))

      (:interface-signature? impl)
      (throw (ex-info (str impl-name "::" (:method-name signature)
                           " must provide an implementation, not a signature")
                      {:class-name impl-name
                       :method-name (:method-name signature)}))

      (not (signatures-match? schema-ir signature impl))
      (throw (ex-info (str impl-name "::" (:method-name signature)
                           " does not match " iface-name " signature")
                      {:interface-signature signature
                       :implementation impl})))))

(defn- validate-interface-implementations!
  [methods-ir schema-ir]
  (let [by-key (into {} (map (juxt (juxt :class :method-name) identity) methods-ir))
        local (assemblage-names schema-ir)]
    (doseq [iface-name (interface-names schema-ir)
            signature (signatures-for methods-ir schema-ir iface-name)
            impl-name (interface-implementers schema-ir iface-name)
            :when (contains? local impl-name)]
      (check-implementer-method! schema-ir by-key iface-name signature impl-name))))

(defn- interface-method-def->ir
  [method-node schema-ir]
  (let [{:keys [class-name method-name return-type body-node]}
        (method-def-parts method-node)
        {:keys [param-names explicit-param-types statements]}
        (block-statements body-node)]
    (when (seq statements)
      (throw (ex-info (str class-name "::" method-name
                           " interface signature must have an empty body")
                      {:class-name class-name :method-name method-name})))
    (when-not return-type
      (throw (ex-info (str class-name "::" method-name
                           " interface signature requires an explicit return type (e.g. -> Float)")
                      {:class-name class-name :method-name method-name})))
    (assert-valid-type! schema-ir return-type
                        {:class-name class-name :method-name method-name})
    (when (not= (count param-names) (count explicit-param-types))
      (throw (ex-info (str class-name "::" method-name
                           " interface parameters must all be typed (Type/name)")
                      {:class-name class-name
                       :method-name method-name
                       :param-names param-names
                       :explicit explicit-param-types})))
    (doseq [[_ type-name] explicit-param-types]
      (assert-valid-type! schema-ir type-name
                        {:class-name class-name :method-name method-name}))
    {:class class-name
     :method-name method-name
     :mutating? (ir/mutating-method-name? method-name)
     :parameters (mapv (fn [n] {:name n :type (get explicit-param-types n)})
                       param-names)
     :return-type return-type
     :interface-signature? true
     :lets []
     :body nil}))

(defn- return-types-compatible?
  [annotated inferred schema-ir]
  (or (= annotated inferred)
      (and (= annotated "Void")
           (or (nil? inferred)
               (ir/external-type? schema-ir inferred)))))

(defn- concrete-method-def->ir
  [method-node schema-ir lookup-method target-fns]
  (let [{:keys [class-name method-name return-type body-node]}
        (method-def-parts method-node)
        {:keys [param-names explicit-param-types statements]}
        (block-statements body-node)
        ctx {:schema-ir schema-ir
             :class-name class-name
             :method-name method-name
             :mutating? (ir/mutating-method-name? method-name)
             :param-names (set param-names)
             :let-names #{}
             :let-types {}
             :param-types explicit-param-types
             :lookup-method lookup-method
             :target-fns target-fns}
        {:keys [lets body]} (process-statements statements ctx class-name method-name)
        parameters (infer-parameters param-names
                                     (conj (mapv :value lets) body)
                                     explicit-param-types
                                     schema-ir)
        param-type-by-name (into {} (map (juxt :name :type) parameters))
        let-types (let-type-map lets schema-ir class-name param-type-by-name)
        inferred-return (expr-return-type body schema-ir class-name param-type-by-name let-types)]
    (when (and return-type inferred-return
               (not (return-types-compatible? return-type inferred-return schema-ir)))
      (throw (ex-info (str class-name "::" method-name " return type annotation "
                           return-type " does not match inferred " inferred-return)
                      {:class-name class-name
                       :method-name method-name
                       :annotated return-type
                       :inferred inferred-return})))
    (when return-type
      (assert-valid-type! schema-ir return-type
                          {:class-name class-name :method-name method-name}))
    (let [final-return (or return-type inferred-return)]
      (when-not final-return
        (throw (ex-info (str "Cannot determine return type of " class-name
                             "::" method-name)
                        {:class-name class-name :method-name method-name})))
      {:class class-name
       :method-name method-name
       :mutating? (ir/mutating-method-name? method-name)
       :parameters parameters
       :return-type final-return
       :lets lets
       :body body})))

(defn- validate-mutating-method!
  "Mutating methods construct this class (every schema field) and return it."
  [{:keys [class method-name parameters body]} schema-ir]
  (when (ir/mutating-method-name? method-name)
    (when-not (ir/mutable-class? schema-ir class)
      (throw (ex-info (str class "::" method-name
                           " requires an identity-capable class")
                      {:class-name class :method-name method-name})))
    (when (seq parameters)
      (when (= "update!" method-name)
        (throw (ex-info (str class "::update! takes no arguments")
                        {:class-name class :parameters parameters}))))
    (when-not (and (= :construct (:expr body))
                   (= class (:class-name body)))
      (throw (ex-info (str class "::" method-name " must construct " class)
                      {:class-name class :body body})))
    (let [expected (count (ir/get-assemblage-components schema-ir class))
          got (count (:args body))]
      (when (not= expected got)
        (throw (ex-info (str class "::" method-name " must list every field ("
                             expected " arguments, got " got ")")
                        {:class-name class :expected expected :got got}))))))

(defn assert-update-wiring!
  "Every $ subscriber, observable, and mailbox must define update!."
  [methods-ir schema-ir]
  (let [defined (set (map (juxt :class :method-name) (or methods-ir [])))
        missing-update (fn [class-names]
                         (filterv #(not (contains? defined [% "update!"]))
                                  (or class-names [])))]
    (doseq [class-name (missing-update (ir/get-subscriber-classes schema-ir))]
      (throw (ex-info (str "Subscriber " class-name " must define update!")
                      {:class-name class-name})))
    (doseq [class-name (missing-update (ir/get-observable-classes schema-ir))]
      (throw (ex-info (str "Observable " class-name " must define update!")
                      {:class-name class-name})))
    (doseq [class-name (missing-update (ir/get-mailbox-classes schema-ir))]
      (throw (ex-info (str "Mailbox " class-name " must define update!")
                      {:class-name class-name})))))

(defn- method-def->ir
  [method-node schema-ir lookup-method target-fns]
  (let [{:keys [class-name method-name]} (method-def-parts method-node)]
    (validate-method-owner! schema-ir class-name method-name)
    (assert-not-reserved-method! class-name method-name)
    (let [ir (if (interface? schema-ir class-name)
               (interface-method-def->ir method-node schema-ir)
               (concrete-method-def->ir method-node schema-ir lookup-method target-fns))]
      (validate-mutating-method! ir schema-ir)
      ir)))

(defn- method-node-key
  [method-node]
  [(second (second method-node))
   (second (nth method-node 2))])

(defn- ensure-method!
  "Convert one method to IR, memoized. The atom is a conversion cache for the
  call graph so a callee's types are available without threading memo maps
  through every expression helper."
  [state defs-by-key schema-ir target-fns key]
  (or (get-in @state [:memo key])
      (do
        (when-not (contains? defs-by-key key)
          (throw (ex-info (str "Unknown method '" (second key) "' on " (first key))
                          {:class-name (first key) :method-name (second key)})))
        (when (contains? (:visiting @state) key)
          (throw (ex-info (str "Cyclic method call involving "
                               (first key) "::" (second key))
                          {:key key})))
        (swap! state update :visiting conj key)
        (let [imported (or (:imported-methods schema-ir) {})
              defined? (fn [c m]
                         (or (contains? imported [c m])
                             (contains? defs-by-key [c m])))
              lookup-defined (fn [class-name method-name]
                               (or (get imported [class-name method-name])
                                   (when (contains? defs-by-key [class-name method-name])
                                     (ensure-method! state defs-by-key schema-ir target-fns
                                                     [class-name method-name]))))
              lookup (fn [class-name method-name]
                       (or (lookup-defined class-name method-name)
                           (when-let [inner (ir/find-delegated-method-class
                                             schema-ir class-name method-name defined?)]
                             (lookup-defined inner method-name))
                           (throw (ex-info (str "Unknown method '" method-name
                                                "' on " class-name)
                                           {:class-name class-name
                                            :method-name method-name}))))
              ir (try
                   (method-def->ir (get defs-by-key key) schema-ir lookup target-fns)
                   (catch #?(:clj Exception :cljs :default) e
                     (let [owner (str (first key) "::" (second key))
                           message (ex-message e)]
                       (if (and message
                                (or (= message owner)
                                    (str/starts-with? message (str owner " "))
                                    (str/starts-with? message (str owner ":"))))
                         (throw e)
                         (throw (ex-info (str "Error in " owner ": "
                                              (or message (str e)))
                                         (merge (ex-data e)
                                                {:class-name (first key)
                                                 :method-name (second key)
                                                 :cause-message message}
                                                {:cause e})))))))]
          (swap! state (fn [s]
                         (-> s
                             (assoc-in [:memo key] ir)
                             (update :visiting disj key))))
          ir))))

(defn- target-fn-map
  [target-ir]
  (into {} (map (fn [[name binding]]
                  [name (:fn-name binding)])
                (:bindings (or target-ir {:bindings {}})))))

(defn reaction-ast-to-ir
  "Turn a parsed :Code reaction AST into MethodsIR."
  ([reaction-ast schema-ir]
   (reaction-ast-to-ir reaction-ast schema-ir {:bindings {} :main nil}))
  ([reaction-ast schema-ir target-ir]
   (reaction-ast-to-ir reaction-ast schema-ir target-ir {}))
  ([reaction-ast schema-ir target-ir {:keys [skip-checks?]}]
   (when-not (ast-utils/node-type? reaction-ast :Code)
     (throw (ex-info "Reaction AST must start with :Code" {:ast reaction-ast})))
   (let [schema-ir (merge-external-types schema-ir
                                         (collect-external-types-from-reaction-ast reaction-ast))
         defs (filterv #(ast-utils/node-type? % :MethodDefinition) (rest reaction-ast))
         keys (mapv method-node-key defs)]
     (when (not= (count keys) (count (set keys)))
       (throw (ex-info "Duplicate method definition in Methods section"
                       {:methods keys})))
     (let [defs-by-key (zipmap keys defs)
           target-fns (target-fn-map target-ir)
           state (atom {:memo {} :visiting #{}})
           methods (mapv #(ensure-method! state defs-by-key schema-ir target-fns %)
                         keys)]
       (when-not skip-checks?
         (assert-methods-complete! methods schema-ir))
       methods))))

(defn- concrete-methods-by-class
  [methods-ir]
  (reduce (fn [acc method]
            (if (:interface-signature? method)
              acc
              (update acc (:class method) (fnil conj []) method)))
          {}
          methods-ir))

(defn- method-names-on
  [by-class schema-ir class-name]
  (into (set (map :method-name (get by-class class-name)))
        (mapcat #(method-names-on by-class schema-ir (:type-name %))
                (ir/delegate-components schema-ir class-name))))

(defn- assert-no-method-field-shadow!
  [schema-ir class-name method-name]
  (when (contains? (ir/promoted-field-names schema-ir class-name) method-name)
    (throw (ex-info (str class-name "::" method-name
                         " shadows field '" method-name "' from a delegate")
                    {:class-name class-name :method-name method-name}))))

(defn- assert-no-delegate-method-clash!
  [schema-ir by-class class-name]
  (let [slots (ir/delegate-components schema-ir class-name)
        surfaces (mapv #(method-names-on by-class schema-ir (:type-name %)) slots)]
    (doseq [i (range (count slots))
            j (range (inc i) (count slots))
            :let [shared (first (filter (nth surfaces i) (nth surfaces j)))]
            :when shared]
      (throw (ex-info (str class-name " delegates share the same method '" shared
                           "'")
                      {:class-name class-name :method-name shared})))))

(defn- assert-delegate-overrides!
  [schema-ir by-class class-name]
  (let [defined (set (map :method-name (get by-class class-name)))]
    (doseq [slot (ir/delegate-components schema-ir class-name)
            method (get by-class (:type-name slot))
            :when (= (:return-type method) (:type-name slot))]
      (when-not (contains? defined (:method-name method))
        (throw (ex-info (str class-name " must define '" (:method-name method)
                             "' because " (:type-name slot) "::"
                             (:method-name method) " returns "
                             (:type-name slot))
                        {:class-name class-name
                         :method-name (:method-name method)
                         :delegate (:type-name slot)}))))))

(defn- validate-delegate-methods!
  [methods-ir schema-ir]
  (let [by-class (concrete-methods-by-class methods-ir)]
    (doseq [assemblage (:assemblages schema-ir)
            :let [class-name (:name assemblage)]]
      (doseq [method (get by-class class-name)]
        (assert-no-method-field-shadow! schema-ir class-name (:method-name method)))
      (assert-no-delegate-method-clash! schema-ir by-class class-name)
      (assert-delegate-overrides! schema-ir by-class class-name))))

(defn assert-methods-complete!
  "Run subscriber wiring, interface, and delegate checks on the full Methods IR."
  [methods schema-ir]
  (assert-update-wiring! methods schema-ir)
  (validate-interface-implementations! methods schema-ir)
  (validate-delegate-methods! methods schema-ir))
