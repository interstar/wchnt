(ns wchnt-lang.interpret
  "Evaluate construction and methods IR as Clojure maps.
   Same IR as the Haxe backend; no host drawing."
  (:require [wchnt-lang.compiler :as compiler]
            [wchnt-lang.ir :as ir]
            [wchnt-lang.pipeline :as p]))

(declare eval-expr instantiate wire-subscriptions wire-context apply-update)

(defn- class-of
  [obj]
  (when (map? obj)
    (:wchnt/class obj)))

(defn- field-names
  [schema-ir class-name]
  (mapv :component-name (ir/get-assemblage-components schema-ir class-name)))

(defn- identity-object?
  [schema-ir class-name]
  (or (ir/is-observable? schema-ir class-name)
      (ir/is-subscriber? schema-ir class-name)
      (ir/mailbox-class? schema-ir class-name)))

(defn- make-instance
  [schema-ir class-name values]
  (let [fields (field-names schema-ir class-name)]
    (when (empty? fields)
      (throw (ex-info (str "Unknown class '" class-name "'")
                      {:class-name class-name})))
    (when (not= (count fields) (count values))
      (throw (ex-info (str "Construction of " class-name " expected "
                           (count fields) " arguments, got " (count values))
                      {:class-name class-name
                       :expected (count fields)
                       :got (count values)})))
    (let [fields-map (into {} (map vector (map keyword fields) values))]
      (if (identity-object? schema-ir class-name)
        {:wchnt/class class-name
         :wchnt/cell (atom fields-map)
         :wchnt/subscribers (atom [])}
        (into {:wchnt/class class-name} fields-map)))))

(defn- eval-construction-arg
  [arg env schema-ir]
  (case (:type arg)
    :primitive (:value arg)
    :enum-value (or (:value arg) (first (:args arg)))
    :variable (if-let [found (get env (:value arg))]
                found
                (throw (ex-info (str "Unknown construction variable '"
                                     (:value arg) "'")
                                {:name (:value arg)})))
    :object (instantiate arg env schema-ir)
    :array (mapv #(eval-construction-arg % env schema-ir) (:args arg))
    (throw (ex-info "Unsupported construction argument"
                    {:arg arg}))))

(defn- instantiate
  [obj-data env schema-ir]
  (if (= :array (:type obj-data))
    (mapv #(eval-construction-arg % env schema-ir) (:args obj-data))
    (make-instance schema-ir
                   (:class-name obj-data)
                   (mapv #(eval-construction-arg % env schema-ir)
                         (:args obj-data)))))

(defn construct
  "Build the root object graph from construction IR."
  [schema-ir construction-ir]
  (let [ordered (sort-by (fn [[_ data]] (or (:index data) 0))
                         (:objects construction-ir))
        env (reduce (fn [env [id data]]
                      (assoc env id (instantiate data env schema-ir)))
                    {}
                    ordered)
        root-id (:return-object construction-ir)
        root (or (get env root-id)
                 (throw (ex-info "Construction has no return object"
                                 {:return-object root-id})))]
    (wire-subscriptions schema-ir root)
    (wire-context schema-ir root)))

(defn load-program
  "Parse markdown to IR and construct the initial heap."
  ([wchnt-markdown]
   (load-program wchnt-markdown {}))
  ([wchnt-markdown opts]
   (let [cargo (if (seq opts)
                 (compiler/compile-to-ir wchnt-markdown opts)
                 (compiler/compile-to-ir wchnt-markdown))]
     (when (p/failed? cargo)
       (throw (ex-info (or (first (:errors cargo)) "compile-to-ir failed")
                       {:errors (:errors cargo)})))
     (when (= :documentation (get-in cargo [:value :page-kind]))
       (throw (ex-info "Documentation page has nothing to construct"
                       {:page-kind :documentation})))
     {:schema-ir (get-in cargo [:stash :schema-ir])
      :methods-ir (or (get-in cargo [:stash :methods-ir]) [])
      :construction-ir (get-in cargo [:stash :construction-ir])
      :target-ir (get-in cargo [:stash :target-ir])
      :page-kind (get-in cargo [:value :page-kind])
      :root (when (get-in cargo [:stash :construction-ir])
              (construct (get-in cargo [:stash :schema-ir])
                         (get-in cargo [:stash :construction-ir])))})))

(defn- lookup-method
  [methods-ir class-name method-name]
  (or (first (filter #(and (= class-name (:class %))
                           (= method-name (:method-name %))
                           (not (:interface-signature? %)))
                     methods-ir))
      (throw (ex-info (str "Unknown method " class-name "::" method-name)
                      {:class-name class-name :method-name method-name}))))

(defn get-field
  "Read a schema field. Identity objects store live values in :wchnt/cell."
  [obj field-name]
  (when-not (map? obj)
    (throw (ex-info (str "Cannot read field '" field-name "' of a non-object")
                    {:field field-name :value obj})))
  (let [k (keyword field-name)
        store (if-let [cell (:wchnt/cell obj)] @cell obj)]
    (when-not (contains? store k)
      (throw (ex-info (str "Unknown field '" field-name "'")
                      {:field field-name :class (class-of obj)})))
    (get store k)))

(defn- walk-fields
  [obj fields]
  (reduce get-field obj fields))

(defn- eval-root
  [root {:keys [this locals params]}]
  (case (:expr root)
    :this this
    :field (get-field this (:name root))
    :local (get locals (:name root))
    :param (get params (:name root))
    (throw (ex-info "Unknown path root" {:root root}))))

(defn- eval-path
  [expr ctx]
  (walk-fields (eval-root (:root expr) ctx) (:fields expr)))

(defn- int-like?
  [x]
  (or (integer? x)
      #?(:clj (instance? Long x)
         :cljs false)))

(defn- apply-op
  [op left right]
  (case op
    "+" (+ left right)
    "-" (- left right)
    "*" (* left right)
    "/" (if (and (int-like? left) (int-like? right))
          (quot left right)
          (/ left right))
    "%" (rem left right)
    (throw (ex-info (str "Unknown arithmetic operator '" op "'") {:op op}))))

(defn- eval-arith
  [parts ctx]
  (let [evaled (mapv (fn [part]
                       (if (string? part)
                         part
                         (eval-expr part ctx)))
                     parts)]
    (when (even? (count evaled))
      (throw (ex-info "Arithmetic chain must alternate values and operators"
                      {:parts parts})))
    (reduce (fn [acc [op rhs]]
              (apply-op op acc rhs))
            (first evaled)
            (partition 2 (rest evaled)))))

(defn- eval-cmp
  [expr ctx]
  (let [left (eval-expr (:left expr) ctx)
        right (eval-expr (:right expr) ctx)]
    (case (:op expr)
      "<" (< left right)
      ">" (> left right)
      "<=" (<= left right)
      ">=" (>= left right)
      "==" (= left right)
      "!=" (not= left right)
      (throw (ex-info (str "Unknown comparison '" (:op expr) "'")
                      {:op (:op expr)})))))

(defn- eval-lets
  [lets ctx]
  (reduce (fn [ctx {:keys [name value]}]
            (when (nil? value)
              (throw (ex-info (str "Let '" name "' has nil value") {:name name})))
            (let [evaled (eval-expr value ctx)]
              (when (nil? evaled)
                (throw (ex-info (str "Let '" name "' evaluated to nil")
                                {:name name})))
              (assoc-in ctx [:locals name] evaled)))
          ctx
          lets))

(defn- eval-if
  [expr ctx]
  (let [branch (if (eval-expr (:condition expr) ctx)
                 (:then expr)
                 (:else expr))]
    (when (nil? branch)
      (throw (ex-info "If expression missing branch"
                      {:condition (:condition expr)})))
    (if (= (:expr branch) :if)
      (eval-if branch ctx)
      (do
        (when (nil? (:body branch))
          (throw (ex-info "If branch missing :body"
                          {:branch branch})))
        (let [inner (eval-lets (:lets branch) ctx)]
          (eval-expr (:body branch) inner))))))

(defn- eval-construct
  [expr ctx]
  (make-instance (:schema-ir ctx)
                 (:class-name expr)
                 (mapv #(eval-expr % ctx) (:args expr))))

(defn- bind-params
  [method args]
  (let [names (mapv :name (:parameters method))]
    (when (not= (count names) (count args))
      (throw (ex-info (str (:class method) "::" (:method-name method)
                           " expected " (count names) " arguments, got "
                           (count args))
                      {:method-name (:method-name method)
                       :expected (count names)
                       :got (count args)})))
    (zipmap names args)))

(defn- eval-method
  [method ctx]
  (let [inner (eval-lets (:lets method) (assoc ctx :locals {}))]
    (eval-expr (:body method) inner)))

(defn- invoke-lambda
  [lambda-expr arg-vals ctx]
  (let [param-names (mapv :name (:params lambda-expr))
        bindings (zipmap param-names arg-vals)
        inner (eval-lets (:lets lambda-expr)
                         (-> ctx
                             (assoc :params (merge (:params ctx {}) bindings))
                             (update :locals merge bindings)))]
    (eval-expr (:body lambda-expr) inner)))

(defn- eval-array-call
  [method recv arg-exprs ctx]
  (case method
    "map"
    (mapv #(invoke-lambda (first arg-exprs) [%] ctx) recv)

    "filter"
    (vec (filter #(invoke-lambda (first arg-exprs) [%] ctx) recv))

    "fold"
    (reduce (fn [acc elem]
              (invoke-lambda (second arg-exprs) [acc elem] ctx))
            (eval-expr (first arg-exprs) ctx)
            recv)

    "cons"
    (into [(eval-expr (first arg-exprs) ctx)] recv)

    (throw (ex-info (str "Unknown array method '" method "'")
                    {:method method}))))

(defn- eval-external-call
  [recv method args]
  #?(:cljs
     (cond
       (and (map? recv) (= :graphics (:wchnt/host recv)))
       (do (apply (get (:methods recv) method) args) recv)

       (some? recv)
       (let [f (aget recv method)]
         (when f (.apply f recv (clj->js args)))
         recv)

       :else recv)
     :clj
     (if (and (map? recv) (= :graphics (:wchnt/host recv)))
       (do (apply (get (:methods recv) method) args) recv)
       (throw (ex-info (str "External call on unsupported receiver for '" method "'")
                       {:method method :receiver recv})))))

(defn- eval-call
  [expr ctx]
  (let [recv (eval-expr (:receiver expr) ctx)
        method (:method expr)
        ext-type (:type expr)]
    (cond
      (and ext-type (ir/external-type? (:schema-ir ctx) ext-type))
      (eval-external-call recv method (mapv #(eval-expr % ctx) (:args expr)))

      (vector? recv)
      (eval-array-call method recv (:args expr) ctx)

      :else
      (let [class-name (class-of recv)
            m (lookup-method (:methods-ir ctx) class-name method)
            args (mapv #(eval-expr % ctx) (:args expr))]
        (eval-method m
                     (-> ctx
                         (assoc :this recv)
                         (update :params merge (bind-params m args))))))))

(defn- lookup-binding
  "Resolve a name from method params or let locals. false is a valid value."
  [ctx name]
  (let [locals (:locals ctx)
        params (:params ctx)]
    (cond
      (contains? locals name) (get locals name)
      (contains? params name) (get params name)
      :else (throw (ex-info (str "Unknown binding '" name "'")
                            {:name name
                             :locals (keys locals)
                             :params (keys params)})))))

(defn- eval-expr
  [expr ctx]
  (when (nil? expr)
    (throw (ex-info "Nil method expression" {})))
  (when-not (:expr expr)
    (throw (ex-info "Method expression missing :expr tag"
                    {:keys (keys expr) :expr expr})))
  (case (:expr expr)
    :int (:value expr)
    :float (:value expr)
    :bool (:value expr)
    :string (:value expr)
    :this (:this ctx)
    :field (get-field (:this ctx) (:name expr))
    :local (lookup-binding ctx (:name expr))
    :param (lookup-binding ctx (:name expr))
    :path (eval-path expr ctx)
    :arith (eval-arith (:parts expr) ctx)
    :cmp (eval-cmp expr ctx)
    :and (every? #(eval-expr % ctx) (:args expr))
    :or (some #(eval-expr % ctx) (:args expr))
    :not (not (eval-expr (:arg expr) ctx))
    :neg (- (eval-expr (:arg expr) ctx))
    :if (eval-if expr ctx)
    :array (mapv #(eval-expr % ctx) (:items expr))
    :construct (eval-construct expr ctx)
    :call (eval-call expr ctx)
    (throw (ex-info (str "Unsupported method expression: " (:expr expr))
                    {:expr expr}))))

(defn- subscribe!
  [observable subscriber]
  (when-not (:wchnt/subscribers observable)
    (throw (ex-info "Cannot subscribe to a non-identity object"
                    {:class (class-of observable)})))
  (swap! (:wchnt/subscribers observable) conj subscriber))

(defn- replace-field!
  "Install value on parent. Identity parents mutate :wchnt/cell; ordinary maps return updated."
  [obj field-name value]
  (let [k (keyword field-name)]
    (if-let [cell (:wchnt/cell obj)]
      (do (swap! cell assoc k value) obj)
      (assoc obj k value))))

(defn- wire-context-on-object!
  [schema-ir parent-obj]
  (if-not (and (map? parent-obj) (:wchnt/class parent-obj))
    parent-obj
    (let [parent-class (class-of parent-obj)
          parent-obj
          (reduce
           (fn [parent component]
             (if (not= :context-specific (:relationship component))
               parent
               (let [field (:component-name component)
                     child-type (:type-name component)]
                 (if (and (ir/needs-context? schema-ir child-type)
                          (= parent-class (ir/get-context-parent schema-ir child-type)))
                   (let [child (get-field parent field)
                         ctx-key (keyword (ir/context-field-name parent-class))
                         wired (assoc child ctx-key parent)]
                     (replace-field! parent field wired))
                   parent))))
           parent-obj
           (ir/get-assemblage-components schema-ir parent-class))]
      (reduce
       (fn [parent fname]
         (let [child (get-field parent fname)
               wired (wire-context-on-object! schema-ir child)]
           (if (identical? child wired)
             parent
             (replace-field! parent fname wired))))
       parent-obj
       (field-names schema-ir parent-class)))))

(defn- wire-context
  [schema-ir obj]
  (wire-context-on-object! schema-ir obj))

(defn- wire-subscriptions
  [schema-ir obj]
  (when (and (map? obj) (:wchnt/class obj))
    (doseq [comp (ir/reactive-components schema-ir (class-of obj))]
      (subscribe! (get-field obj (:component-name comp)) obj))
    (doseq [fname (field-names schema-ir (class-of obj))]
      (wire-subscriptions schema-ir (get-field obj fname)))))

(defn- field-snapshot
  [schema-ir obj]
  (let [fields (field-names schema-ir (class-of obj))]
    (into {} (map (fn [f] [(keyword f) (get-field obj f)]) fields))))

(defn- install-update-field!
  "Install one field from an update construction. Identity slots mutate in place."
  [schema-ir this-obj component arg-expr ctx]
  (let [field (:component-name component)
        type-name (:type-name component)
        k (keyword field)]
    (if (identity-object? schema-ir type-name)
      (let [existing (get-field this-obj field)]
        (case (:expr arg-expr)
          :construct
          (do
            (when-not (= (:class-name arg-expr) type-name)
              (throw (ex-info
                      (str "update must not replace identity slot '" field
                           "' with a " (:class-name arg-expr))
                      {:field field :expected type-name :got (:class-name arg-expr)})))
            (let [sub-comps (ir/get-assemblage-components schema-ir type-name)
                  sub-vals (mapv #(eval-expr % ctx) (:args arg-expr))
                  patch (zipmap (map (comp keyword :component-name) sub-comps)
                                sub-vals)]
              (reset! (:wchnt/cell existing) (merge @(:wchnt/cell existing) patch))))

          (swap! (:wchnt/cell this-obj) assoc k (eval-expr arg-expr ctx))))
      (swap! (:wchnt/cell this-obj) assoc k (eval-expr arg-expr ctx)))))

(defn- install-update!
  [schema-ir this-obj body-expr ctx]
  (when-not (= :construct (:expr body-expr))
    (throw (ex-info "update method body must be a construction"
                    {:class (class-of this-obj) :body body-expr})))
  (let [components (ir/get-assemblage-components schema-ir (class-of this-obj))]
    (when (not= (count components) (count (:args body-expr)))
      (throw (ex-info "update construction field count mismatch"
                      {:expected (count components)
                       :got (count (:args body-expr))})))
    (doseq [[component arg-expr] (map vector components (:args body-expr))]
      (install-update-field! schema-ir this-obj component arg-expr ctx))))

(defn- apply-update
  [schema-ir methods-ir obj]
  (when-not (:wchnt/cell obj)
    (throw (ex-info (str (class-of obj) "::update needs an identity object")
                    {:class-name (class-of obj)})))
  (let [method (lookup-method methods-ir (class-of obj) "update")
        ctx {:schema-ir schema-ir
             :methods-ir methods-ir
             :this obj
             :params {}
             :locals {}}
        inner (eval-lets (:lets method) (assoc ctx :locals {}))]
    (install-update! schema-ir obj (:body method) inner)
    (wire-context-on-object! schema-ir obj)
    (when (ir/is-observable? schema-ir (class-of obj))
      (doseq [sub @(:wchnt/subscribers obj)]
        (apply-update schema-ir methods-ir sub)))
    obj))

(defn inject
  "Host-only: write mailbox fields (schema order), then update()."
  [schema-ir methods-ir obj args]
  (let [class-name (class-of obj)
        fields (field-names schema-ir class-name)]
    (when-not (ir/mailbox-class? schema-ir class-name)
      (throw (ex-info (str "Class '" class-name "' is not marked >"
                           class-name "; Target cannot inject")
                      {:class-name class-name})))
    (when (not= (count fields) (count args))
      (throw (ex-info (str class-name " inject expected " (count fields)
                           " arguments, got " (count args))
                      {:class-name class-name
                       :expected (count fields)
                       :got (count args)})))
    (reset! (:wchnt/cell obj) (zipmap (map keyword fields) args))
    (apply-update schema-ir methods-ir obj)))

(defn call
  "Invoke a method on an interpreter object. args is a vector of already-evaled values."
  [schema-ir methods-ir obj method-name args]
  (if (= "update" method-name)
    (apply-update schema-ir methods-ir obj)
    (let [method (lookup-method methods-ir (class-of obj) method-name)]
      (eval-method method
                   {:schema-ir schema-ir
                    :methods-ir methods-ir
                    :this obj
                    :params (bind-params method args)
                    :locals {}}))))
