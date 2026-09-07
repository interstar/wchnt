(ns wchnt-lang.reaction
  "Reaction section: method AST → methods IR."
  (:require [clojure.string :as str]
            [wchnt-lang.ast-utils :as ast-utils]
            [wchnt-lang.ir :as ir]))

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
  (set (map :name (:interfaces schema-ir))))

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

(defn- find-component
  [schema-ir class-name field-name]
  (first (filter #(= field-name (:component-name %))
                 (or (ir/get-assemblage-components schema-ir class-name) []))))

(defn- context-parent-type
  "If name is the generated context field on this class, return the parent class name."
  [schema-ir class-name name]
  (when (ir/needs-context? schema-ir class-name)
    (let [parent (ir/get-context-parent schema-ir class-name)]
      (when (and parent (= name (ir/context-field-name parent)))
        parent))))

(defn- binding-type
  [schema-ir class-name name]
  (or (:type-name (find-component schema-ir class-name name))
      (context-parent-type schema-ir class-name name)))

(defn- field-names
  [schema-ir class-name]
  (set (map :component-name (ir/get-assemblage-components schema-ir class-name))))

(defn- resolve-name
  [name {:keys [param-names let-names schema-ir class-name]}]
  (cond
    (= name "this")
    {:expr :this}

    (contains? (or let-names #{}) name)
    {:expr :local :name name}

    (contains? param-names name)
    {:expr :param :name name}

    (or (contains? (field-names schema-ir class-name) name)
        (context-parent-type schema-ir class-name name))
    {:expr :field :name name}

    :else
    (throw (ex-info (str "Unknown name '" name "' in " class-name
                         " method (not a field, parameter, or let)")
                    {:name name :class-name class-name}))))

(declare ast->expr process-statements assert-fresh-let-name value-type block-statements convert-call-arg)

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
  (when (or (str/starts-with? class-name "Array<")
            (str/starts-with? class-name "Map<"))
    (throw (ex-info (str "Cannot access fields of collection type " class-name)
                    {:class-name class-name :field-name field-name})))
  (let [component (find-component schema-ir class-name field-name)]
    (when-not component
      (throw (ex-info (str "Unknown field '" field-name "' on " class-name)
                      {:class-name class-name :field-name field-name})))
    (:type-name component)))

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
    (let [final-type (reduce (fn [class-name field-name]
                               (follow-field (:schema-ir ctx) class-name field-name))
                             start-type
                             (rest names))]
      {:expr :path
       :root root
       :fields (vec (rest names))
       :type final-type})))

(defn- method-arg-items
  [arg-list]
  (if-not (ast-utils/node-type? arg-list :MethodArgList)
    []
    (mapv (fn [item]
            (if (ast-utils/node-type? item :MethodArgItem)
              (second item)
              item))
          (rest arg-list))))

(defn- take-call-step
  [items]
  (let [[names more] (split-with string? items)]
    (when (or (empty? names) (empty? more))
      (throw (ex-info "Method call is missing a name or argument list"
                      {:items items})))
    {:fields (vec (butlast names))
     :method (last names)
     :args (first more)
     :more (vec (rest more))}))

(defn- apply-fields
  [root fields ctx]
  (if (empty? fields)
    root
    (let [start (root-type root ctx)]
      (when-not start
        (throw (ex-info "Cannot determine type of receiver for field access"
                        {:root root})))
      (let [final-type (reduce (fn [class-name field-name]
                                 (follow-field (:schema-ir ctx) class-name field-name))
                               start
                               fields)]
        {:expr :path
         :root root
         :fields (vec fields)
         :type final-type}))))

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
                            else-type (branch-or-if-type acc ctx)]
                        (when (not= then-type else-type)
                          (throw (ex-info (str "if branches must have the same type, got "
                                               then-type " and " else-type)
                                          {:then then-type :else else-type})))
                        {:expr :if
                         :condition cond-expr
                         :then then-branch
                         :else acc
                         :type then-type}))
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
        else-type (branch-or-if-type else ctx)]
    (when (not= then-type else-type)
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
     :type then-type}))

(defn- require-lambda
  [node method-name]
  (when-not (ast-utils/node-type? node :BlockOrLambda)
    (throw (ex-info (str method-name " expects a block argument")
                    {:node node})))
  node)

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
      (assert-arg-type elem-type (value-type elem ctx) "Array::cons")
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
    (let [types (map-types class-name)]
      (when-not types
        (throw (ex-info (str "'get' is only defined on maps, not " class-name)
                        {:class-name class-name})))
      (expect-arity "Map" "get" 1 arg-nodes)
      (let [k (convert-call-arg (first arg-nodes) ctx)]
        (assert-arg-type (:key types) (value-type k ctx) "Map::get")
        {:expr :call
         :receiver receiver
         :method "get"
         :args [k]
         :arg-types [(:key types)]
         :type (:val types)}))))

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
    (let [lam (ast->lambda (require-lambda (first arg-nodes) "times")
                           ["Int"] ctx "Int" "times")]
      {:expr :call
       :receiver receiver
       :method "times"
       :args [lam]
       :arg-types [(:type lam)]
       :type (str "Array<" (:type lam) ">")})))

(defn- combinator-call
  [receiver class-name method arg-nodes ctx]
  (let [elem-type (array-elem-type class-name)]
    (when (and (contains? #{"map" "filter" "fold" "cons"} method)
               (not elem-type))
      (throw (ex-info (str "'" method "' is only defined on arrays, not " class-name)
                      {:class-name class-name :method method})))
    (when elem-type
      (case method
        "map"
        (do (expect-arity "Array" "map" 1 arg-nodes)
            (let [lam (ast->lambda (require-lambda (first arg-nodes) "map")
                                   [elem-type] ctx "Array" "map")]
              {:expr :call
               :receiver receiver
               :method "map"
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
                 :args [initial lam]
                 :arg-types [acc-type (:type lam)]
                 :type acc-type})))

        nil))))

(defn- builtin-call
  [receiver class-name method arg-nodes ctx]
  (or (length-call receiver class-name method arg-nodes)
      (concat-call receiver class-name method arg-nodes ctx)
      (cons-call receiver class-name method arg-nodes ctx)
      (head-call receiver class-name method arg-nodes)
      (tail-call receiver class-name method arg-nodes)
      (put-call receiver class-name method arg-nodes ctx)
      (get-call receiver class-name method arg-nodes ctx)
      (remove-call receiver class-name method arg-nodes ctx)
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

(defn- external-call
  "Passthrough call on an @ type — no WCHNT method table; Haxe uses the host type."
  [receiver class-name method arg-nodes ctx]
  (when (ir/external-type? (:schema-ir ctx) class-name)
    (let [args (mapv #(convert-call-arg % ctx) arg-nodes)]
      {:expr :call
       :receiver receiver
       :method method
       :args args
       :arg-types (vec (repeat (count args) nil))
       :type class-name})))

(defn- make-call
  [receiver method arg-list ctx]
  (let [arg-nodes (method-arg-items arg-list)
        class-name (receiver-class receiver ctx)]
    (or (builtin-call receiver class-name method arg-nodes ctx)
        (external-call receiver class-name method arg-nodes ctx)
        (let [args (mapv #(convert-call-arg % ctx) arg-nodes)
              callee (lookup-callee class-name method (count args) ctx)]
          {:expr :call
           :receiver receiver
           :method method
           :args args
           :arg-types (mapv :type (:parameters callee))
           :type (:return-type callee)}))))

(defn- ast->call
  [node ctx]
  (let [root (ast->expr (second node) ctx)
        first-step (take-call-step (drop 2 node))
        first-recv (apply-fields root (:fields first-step) ctx)]
    (loop [call (make-call first-recv (:method first-step) (:args first-step) ctx)
           remaining (:more first-step)]
      (if (empty? remaining)
        call
        (let [step (take-call-step remaining)]
          (when (seq (:fields step))
            (throw (ex-info "Cannot walk fields after a method call"
                            {:fields (:fields step)})))
          (recur (make-call call (:method step) (:args step) ctx)
                 (:more step)))))))

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
        (assert-arg-type elem-type (:class-name expr) "array element")
        expr)
      (let [expr (ast->expr node ctx)]
        (assert-arg-type elem-type (value-type expr ctx) "array element")
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
      {:expr :arith :parts (convert-chain-parts (rest node) ctx)}

      (ast-utils/node-type? node :MulOp)
      {:expr :arith :parts (convert-chain-parts (rest node) ctx)}

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

(defn- infer-param-types-from
  [expr types-atom]
  (case (:expr expr)
    :arith
    (doseq [part (:parts expr)]
      (when (map? part)
        (note-param-type types-atom part "Int")
        (infer-param-types-from part types-atom)))

    :cmp
    (do (note-param-type types-atom (:left expr) "Int")
        (note-param-type types-atom (:right expr) "Int")
        (infer-param-types-from (:left expr) types-atom)
        (infer-param-types-from (:right expr) types-atom))

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
    (do (note-param-type types-atom (:arg expr) "Int")
        (infer-param-types-from (:arg expr) types-atom))

    :array
    (doseq [item (:items expr)]
      (infer-param-types-from item types-atom))

    :map
    (doseq [pair (:pairs expr)]
      (infer-param-types-from (:key pair) types-atom)
      (infer-param-types-from (:value pair) types-atom))

    nil))

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
  (let [types-atom (atom {})]
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
    :arith "Int"
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
    :neg "Int"
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

(defn- method-has-external-param?
  [schema-ir method]
  (some #(ir/external-type? schema-ir (:type %)) (:parameters method)))

(defn assert-methods-section-placement!
  "Methods with @ params belong in ## Target Methods only."
  [methods schema-ir section]
  (doseq [{:keys [class method-name parameters] :as method} methods]
    (let [has-external? (method-has-external-param? schema-ir method)]
      (cond
        (and (= section :methods) has-external?)
        (throw (ex-info (str class "::" method-name
                             " uses @ parameters and must be in ## Target Methods")
                        {:class class :method method-name :section section}))

        (and (= section :target-methods) (not has-external?))
        (throw (ex-info (str class "::" method-name
                             " has no @ parameters and must be in ## Methods")
                        {:class class :method method-name :section section}))))))

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

(defn- value-type
  [expr ctx]
  (case (:expr expr)
    :path (:type expr)
    :this (:class-name ctx)
    :call (:type expr)
    :target-call (:type expr)
    :if (:type expr)
    :neg "Int"
    :lambda (:type expr)
    :param (get (:param-types ctx) (:name expr))
    :field (binding-type (:schema-ir ctx) (:class-name ctx) (:name expr))
    :local (get (:let-types ctx) (:name expr))
    :arith "Int"
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
          (mapv :type (:parameters implementation)))))

(defn- validate-interface-implementations!
  [methods-ir schema-ir]
  (let [by-key (into {} (map (juxt (juxt :class :method-name) identity) methods-ir))]
    (doseq [iface (:interfaces schema-ir)
            :let [iface-name (:name iface)]
            signature (filter #(and (= iface-name (:class %))
                                    (:interface-signature? %))
                              methods-ir)]
      (doseq [impl-name (:implementers iface)]
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
                             :implementation impl}))))))))

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
                           " interface signature requires an explicit return type (e.g. : Float)")
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
       :parameters parameters
       :return-type final-return
       :lets lets
       :body body})))

(defn- validate-update-method!
  "update takes no arguments and constructs this class (every schema field)."
  [{:keys [class method-name parameters body]} schema-ir]
  (when (= "update" method-name)
    (when (seq parameters)
      (throw (ex-info (str class "::update takes no arguments")
                      {:class-name class :parameters parameters})))
    (when-not (and (= :construct (:expr body))
                   (= class (:class-name body)))
      (throw (ex-info (str class "::update must construct " class)
                      {:class-name class :body body})))
    (let [expected (count (ir/get-assemblage-components schema-ir class))
          got (count (:args body))]
      (when (not= expected got)
        (throw (ex-info (str class "::update must list every field ("
                             expected " arguments, got " got ")")
                        {:class-name class :expected expected :got got}))))))

(defn assert-update-wiring!
  "Every $ subscriber and every observable class must define update."
  [methods-ir schema-ir]
  (let [defined (set (map (juxt :class :method-name) (or methods-ir [])))
        missing-update (fn [class-names]
                         (filterv #(not (contains? defined [% "update"]))
                                  (or class-names [])))]
    (doseq [class-name (missing-update (ir/get-subscriber-classes schema-ir))]
      (throw (ex-info (str "Subscriber " class-name " must define update")
                      {:class-name class-name})))
    (doseq [class-name (missing-update (ir/get-observable-classes schema-ir))]
      (throw (ex-info (str "Observable " class-name " must define update")
                      {:class-name class-name})))
    (doseq [class-name (missing-update (ir/get-mailbox-classes schema-ir))]
      (throw (ex-info (str "Mailbox " class-name " must define update")
                      {:class-name class-name})))))

(defn- method-def->ir
  [method-node schema-ir lookup-method target-fns]
  (let [{:keys [class-name method-name]} (method-def-parts method-node)]
    (validate-method-owner! schema-ir class-name method-name)
    (assert-not-reserved-method! class-name method-name)
    (let [ir (if (interface? schema-ir class-name)
               (interface-method-def->ir method-node schema-ir)
               (concrete-method-def->ir method-node schema-ir lookup-method target-fns))]
      (validate-update-method! ir schema-ir)
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
        (let [lookup (fn [class-name method-name]
                       (ensure-method! state defs-by-key schema-ir target-fns
                                       [class-name method-name]))
              ir (method-def->ir (get defs-by-key key) schema-ir lookup target-fns)]
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
         (assert-update-wiring! methods schema-ir)
         (validate-interface-implementations! methods schema-ir))
       methods))))

(defn assert-methods-complete!
  "Run subscriber wiring and interface checks on the full Methods IR."
  [methods schema-ir]
  (assert-update-wiring! methods schema-ir)
  (validate-interface-implementations! methods schema-ir))
