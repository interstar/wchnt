(ns wchnt-lang.interpret
  "Evaluate construction and methods IR as Clojure maps.
   Same IR as the Haxe backend; no host drawing."
  (:require [wchnt-lang.compiler :as compiler]
            [wchnt-lang.ir :as ir]
            [wchnt-lang.pipeline :as p]
            [wchnt-lang.template :as template]
            [wchnt-lang.targets.interpreter-std :as host]))

(declare eval-expr instantiate wire-subscriptions wire-context wire-new
         apply-mutating-method eval-map-construction eval-construction-arg)

(defn- class-of
  [obj]
  (when (map? obj)
    (:wchnt/class obj)))

(defn- field-names
  [schema-ir class-name]
  (mapv :component-name (ir/get-assemblage-components schema-ir class-name)))

(defn- identity-object?
  [schema-ir class-name]
  (ir/mutable-class? schema-ir class-name))

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

(defn construct-object
  "Build one assemblage instance from schema field values in schema order.
   Target uses this when it constructs a borrowed @ object (new Pen(...))."
  [schema-ir class-name values]
  (make-instance schema-ir class-name values))

(defn- primitive-value
  [arg]
  (let [v (:value arg)]
    (if (and (= "Int" (:class-name arg)) (string? v))
      #?(:clj (Long/parseLong v)
         :cljs (js/parseInt v 10))
      v)))

(defn- construction-var-name
  [arg]
  (or (:value arg) (first (:args arg))))

(defn- resolve-construction-var
  [arg env mappings]
  (let [name (construction-var-name arg)
        id (get mappings name name)]
    (or (get env id)
        (throw (ex-info (str "Unknown construction variable '" name "'")
                        {:name name})))))

(defn- eval-map-construction
  "Flattened key/value construction args → a Clojure map. Missing class on
   purpose: Map<K,V> is not a schema assemblage."
  [args env schema-ir methods-ir mappings]
  (when (odd? (count args))
    (throw (ex-info "Map construction expected key/value pairs"
                    {:got (count args)})))
  (into {}
        (map (fn [[k v]]
               [(eval-construction-arg k env schema-ir methods-ir mappings)
                (eval-construction-arg v env schema-ir methods-ir mappings)])
             (partition 2 args))))

(defn- eval-construction-arg
  [arg env schema-ir methods-ir mappings]
  (case (:type arg)
    :primitive (primitive-value arg)
    :enum-value (or (:value arg) (first (:args arg)))
    :variable (resolve-construction-var arg env mappings)
    :call (eval-expr (:expr arg) {:schema-ir schema-ir
                                  :methods-ir methods-ir
                                  :this nil
                                  :params {}
                                  :locals {}})
    :object (instantiate arg env schema-ir methods-ir mappings)
    :array (mapv #(eval-construction-arg % env schema-ir methods-ir mappings)
                 (:args arg))
    :map (eval-map-construction (:args arg) env schema-ir methods-ir mappings)
    (throw (ex-info "Unsupported construction argument"
                    {:arg arg}))))

(defn- instantiate
  [obj-data env schema-ir methods-ir mappings]
  (case (:type obj-data)
    :array (mapv #(eval-construction-arg % env schema-ir methods-ir mappings)
                 (:args obj-data))
    :map (eval-map-construction (:args obj-data) env schema-ir methods-ir mappings)
    (make-instance schema-ir
                   (:class-name obj-data)
                   (mapv #(eval-construction-arg % env schema-ir methods-ir mappings)
                         (:args obj-data)))))

(defn- seed-factory-env
  [construction-ir factory-args]
  (let [params (or (:factory-params construction-ir) [])]
    (when (not= (count params) (count factory-args))
      (throw (ex-info (str "Factory expected " (count params)
                           " argument(s), got " (count factory-args))
                      {:expected (mapv :name params)
                       :got (count factory-args)})))
    (into {} (map (fn [p v] [(:name p) v]) params factory-args))))

(defn construct
  "Build the root object graph from construction IR.
   factory-args fill free @-slot names, in :factory-params order."
  ([schema-ir construction-ir]
   (construct schema-ir construction-ir [] []))
  ([schema-ir construction-ir methods-ir]
   (construct schema-ir construction-ir methods-ir []))
  ([schema-ir construction-ir methods-ir factory-args]
   (let [mappings (or (:variable-mappings construction-ir) {})
         ordered (ir/objects-in-construction-order (:objects construction-ir)
                                                   mappings)
         env0 (seed-factory-env construction-ir (or factory-args []))
         env (reduce (fn [env [id data]]
                       (assoc env id (instantiate data env schema-ir methods-ir mappings)))
                     env0
                     ordered)
         root-id (:return-object construction-ir)
         root (or (get env root-id)
                  (throw (ex-info "Construction has no return object"
                                  {:return-object root-id})))]
     (wire-new schema-ir root))))

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
      :root (when-let [construction-ir (get-in cargo [:stash :construction-ir])]
              (when (empty? (or (:factory-params construction-ir) []))
                (construct (get-in cargo [:stash :schema-ir])
                           construction-ir
                           (or (get-in cargo [:stash :methods-ir]) []))))})))

(defn- find-method
  [methods-ir class-name method-name]
  (first (filter #(and (= class-name (:class %))
                       (= method-name (:method-name %))
                       (not (:interface-signature? %)))
                 methods-ir)))

(defn- lookup-method
  [methods-ir class-name method-name]
  (or (find-method methods-ir class-name method-name)
      (throw (ex-info (str "Unknown method " class-name "::" method-name)
                      {:class-name class-name :method-name method-name}))))

(defn- lookup-method-or-delegate
  [schema-ir methods-ir class-name method-name]
  (or (find-method methods-ir class-name method-name)
      (when-let [inner (ir/find-delegated-method-class
                        schema-ir class-name method-name
                        (fn [c m] (some? (find-method methods-ir c m))))]
        (find-method methods-ir inner method-name))
      (throw (ex-info (str "Unknown method " class-name "::" method-name)
                      {:class-name class-name :method-name method-name}))))

(defn- lookup-method-ctx
  [ctx class-name method-name]
  (or (first (filter #(and (= class-name (:class %))
                           (= method-name (:method-name %))
                           (not (:interface-signature? %)))
                     (or (:methods-ir ctx) [])))
      (get (:imported-methods (:schema-ir ctx)) [class-name method-name])
      (throw (ex-info (str "Unknown method " class-name "::" method-name)
                      {:class-name class-name :method-name method-name}))))

(defn- field-store
  [obj]
  (if-let [cell (:wchnt/cell obj)] @cell obj))

(defn materialize
  "Return a snapshot of an interpreter object graph for inspection/debugging.
   Identity objects keep their live fields in :wchnt/cell; this removes that
   implementation detail without changing the live object itself."
  [value]
  (letfn [(walk [value path]
            (cond
              (vector? value)
              (mapv #(walk % path) value)

              (and (map? value) (:wchnt/cell value))
              (if (some #(identical? value %) path)
                {:wchnt/class (:wchnt/class value)}
                (into {:wchnt/class (:wchnt/class value)}
                      (map (fn [[k v]] [k (walk v (conj path value))])
                           @(:wchnt/cell value))))

              (map? value)
              (into {} (map (fn [[k v]] [k (walk v path)]) value))

              :else
              value))]
    (walk value [])))

(defn get-field
  "Read a schema field. Identity objects store live values in :wchnt/cell.
   With schema-ir, also walks + delegate slots for promoted fields."
  ([obj field-name]
   (get-field nil obj field-name))
  ([schema-ir obj field-name]
   (when-not (map? obj)
     (throw (ex-info (str "Cannot read field '" field-name "' of a non-object")
                     {:field field-name :value obj})))
   (let [k (keyword field-name)
         store (field-store obj)]
     (cond
       (contains? store k)
       (get store k)

       schema-ir
       (let [hit (ir/resolve-field schema-ir (class-of obj) field-name)]
         (when (or (nil? hit) (= 1 (count (:fields hit))))
           (throw (ex-info (str "Unknown field '" field-name "'")
                           {:field field-name :class (class-of obj)})))
         (reduce (fn [o f] (get-field schema-ir o f)) obj (:fields hit)))

       :else
       (throw (ex-info (str "Unknown field '" field-name "'")
                       {:field field-name :class (class-of obj)}))))))

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

(defn- int32
  [n]
  #?(:clj (unchecked-int (long n))
     :cljs (bit-or n 0)))

(defn- eval-bitwise
  [expr ctx]
  (let [op (:op expr)
        left (int32 (eval-expr (:left expr) ctx))
        right (int32 (eval-expr (:right expr) ctx))
        shift (bit-and right 31)]
    (case op
      "&" (int32 (bit-and left right))
      "|" (int32 (bit-or left right))
      "^" (int32 (bit-xor left right))
      "<<" (int32 (bit-shift-left left shift))
      ">>" (int32 (bit-shift-right left shift))
      ">>>" #?(:clj (int32 (bit-and (unsigned-bit-shift-right
                                      (bit-and (long left) 4294967295)
                                      shift)
                                      4294967295))
               :cljs (bit-or (unsigned-bit-shift-right left shift) 0))
      (throw (ex-info (str "Unknown bitwise operator '" op "'")
                      {:operator op})))))

(defn- eval-bitnot
  [expr ctx]
  (int32 (bit-not (int32 (eval-expr (:arg expr) ctx)))))

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
  ;; Objects built inside a method get the *same* construction magic as the
  ;; initial heap — reactive subscriptions and context back-references — so a
  ;; :Ball rebuilt each tick still resolves `theGame`, and any reactive wiring
  ;; is re-established. Without this the next tick breaks. See `wire-new`.
  (wire-new (:schema-ir ctx)
            (make-instance (:schema-ir ctx)
                           (:class-name expr)
                           (mapv #(eval-expr % ctx) (:args expr)))))

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

    "get"
    (let [idx (eval-expr (first arg-exprs) ctx)]
      (when-not (and (integer? idx) (<= 0 idx) (< idx (count recv)))
        (throw (ex-info (str "Array::get: index " idx " out of range")
                        {:index idx :length (count recv)})))
      (nth recv idx))

    "length"
    (count recv)

    (throw (ex-info (str "Unknown array method '" method "'")
                    {:method method}))))

(defn- wchnt-dict?
  "A WCHNT Map value is a Clojure map with no assemblage class tag."
  [recv]
  (and (map? recv) (not (class-of recv))))

(defn- eval-map-call
  [method recv arg-exprs ctx]
  (case method
    "map"
    (into {}
          (map (fn [[k v]]
                 [k (invoke-lambda (first arg-exprs) [k v] ctx)])
               recv))

    "filter"
    (into {}
          (filter (fn [[k v]]
                    (invoke-lambda (first arg-exprs) [k v] ctx))
                  recv))

    "fold"
    (reduce (fn [acc [k v]]
              (invoke-lambda (second arg-exprs) [acc k v] ctx))
            (eval-expr (first arg-exprs) ctx)
            recv)

    (let [args (mapv #(eval-expr % ctx) arg-exprs)]
      (case method
        "get"
        (if (= 2 (count args))
          (get recv (first args) (second args))
          (let [k (first args)]
            (when-not (contains? recv k)
              (throw (ex-info "Map::get: key not found" {:key k})))
            (get recv k)))

        "exists"
        (contains? recv (first args))

        "put"
        (assoc recv (first args) (second args))

        "remove"
        (let [k (first args)]
          (when-not (contains? recv k)
            (throw (ex-info "Map::remove: key not found" {:key k})))
          (dissoc recv k))

        (throw (ex-info (str "Unknown map method '" method "'")
                        {:method method}))))))

(defn- eval-string-call
  [method recv arg-exprs ctx]
  (case method
    "concat" (str recv (eval-expr (first arg-exprs) ctx))
    "str" (str recv)
    "tpl" (template/expand recv (eval-expr (first arg-exprs) ctx))
    "length" (count recv)
    "substring"
    (let [start (eval-expr (first arg-exprs) ctx)
          end (eval-expr (second arg-exprs) ctx)]
      (when (or (neg? start) (neg? end) (> start (count recv))
                (> end (count recv)) (> start end))
        (throw (ex-info "String::substring: invalid range"
                        {:start start :end end})))
      (subs recv start end))
    (throw (ex-info (str "Unknown string method '" method "'")
                    {:method method}))))

(defn- eval-object-call
  [recv method arg-exprs ctx]
  (let [class-name (class-of recv)
        m (lookup-method-ctx ctx class-name method)
        args (mapv #(eval-expr % ctx) arg-exprs)]
    (eval-method m
                 (-> ctx
                     (assoc :this recv)
                     (update :params merge (bind-params m args))))))

(defn- js-host-apply
  [recv method args]
  #?(:cljs
     (let [f (aget recv method)]
       (when-not f
         (throw (ex-info (str "Unknown host method '" method "'")
                         {:method method})))
       (.apply f recv (clj->js args)))
     :clj
     (throw (ex-info (str "External call on unsupported receiver for '" method "'")
                     {:method method :receiver recv}))))

(defn- eval-external-call
  [recv method args ext-type]
  (cond
    (and (map? recv) (= :graphics (:wchnt/host recv)))
    (do (apply (get (:methods recv) method) args) recv)

    (and (map? recv) (= :maths (:wchnt/host recv)))
    (host/invoke recv method args)

    (and ext-type (host/query? ext-type method))
    (js-host-apply recv method args)

    :else
    (do (js-host-apply recv method args) recv)))

(defn- eval-call
  [expr ctx]
  (if (= :import-alias (:expr (:receiver expr)))
    (let [class-name (:import-class expr)
          method (:method expr)
          args (mapv #(eval-expr % ctx) (:args expr))
          info (get (:import-aliases (:schema-ir ctx))
                    (:name (:receiver expr)))]
      (if (= method "factory")
        (construct (:schema-ir info)
                   (:construction-ir info)
                   (:methods-ir info)
                   args)
        (let [m (lookup-method-ctx ctx class-name method)]
          (eval-method m
                       (-> ctx
                           (assoc :this nil)
                           (update :params merge (bind-params m args)))))))
    (let [recv (eval-expr (:receiver expr) ctx)
          method (:method expr)
          ext-type (:external-type expr)]
      (cond
        (and ext-type (ir/external-type? (:schema-ir ctx) ext-type))
        (eval-external-call recv method
                            (mapv #(eval-expr % ctx) (:args expr))
                            ext-type)

        (vector? recv)
        (eval-array-call method recv (:args expr) ctx)

        (number? recv)
        (case method
          "str" (str recv)
          "toInt" (int recv)
          "floor" #?(:clj (long (Math/floor (double recv)))
                      :cljs (js/Math.floor recv))
          "ceil" #?(:clj (long (Math/ceil (double recv)))
                     :cljs (js/Math.ceil recv))
          "round" #?(:clj (long (Math/round (double recv)))
                      :cljs (js/Math.round recv))
          "times"
          (let [n (long recv)
                lam (first (:args expr))]
            (when (neg? n)
              (throw (ex-info "Int::times expected a non-negative count"
                              {:n n})))
            (when (not= :lambda (:expr lam))
              (throw (ex-info "Int::times expects a block"
                              {:got lam})))
            (mapv #(invoke-lambda lam [%] ctx) (range n)))
          (throw (ex-info (str "Unknown numeric method '" method "'")
                          {:method method})))

        (boolean? recv)
        (if (= method "str")
          (str recv)
          (throw (ex-info (str "Unknown boolean method '" method "'")
                          {:method method})))

        (string? recv)
        (eval-string-call method recv (:args expr) ctx)

        (wchnt-dict? recv)
        (eval-map-call method recv (:args expr) ctx)

        :else
        (eval-object-call recv method (:args expr) ctx)))))

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
    :bitwise (eval-bitwise expr ctx)
    :bitnot (eval-bitnot expr ctx)
    :cmp (eval-cmp expr ctx)
    :and (every? #(eval-expr % ctx) (:args expr))
    :or (some #(eval-expr % ctx) (:args expr))
    :not (not (eval-expr (:arg expr) ctx))
    :neg (- (eval-expr (:arg expr) ctx))
    :if (eval-if expr ctx)
    :array (mapv #(eval-expr % ctx) (:items expr))
    :map (into {} (map (fn [pair]
                         [(eval-expr (:key pair) ctx)
                          (eval-expr (:value pair) ctx)])
                       (:pairs expr)))
    :construct (eval-construct expr ctx)
    :call (eval-call expr ctx)
    :enum (:name expr)
    (throw (ex-info (str "Unsupported method expression: " (:expr expr))
                    {:expr expr}))))

(defn- subscribe!
  "Register subscriber on observable once. Re-wiring a reconstructed graph
   must not stack duplicate notifications."
  [observable subscriber]
  (when-not (:wchnt/subscribers observable)
    (throw (ex-info "Cannot subscribe to a non-identity object"
                    {:class (class-of observable)})))
  (swap! (:wchnt/subscribers observable)
         (fn [subs]
           (if (some #(identical? % subscriber) subs)
             subs
             (conj subs subscriber)))))

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

(defn- wire-new
  "Construction magic shared by the initial heap and method-level `[:Class …]`:
   reactive $ subscriptions and :context parent back-references. Always applied
   to a freshly built object graph so a reconstructed Game still has `theGame`
   on a :Ball and still receives Time.notify."
  [schema-ir obj]
  (wire-subscriptions schema-ir obj)
  (wire-context schema-ir obj))

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

(defn- apply-mutating-method
  [schema-ir methods-ir obj method-name args]
  (when-not (:wchnt/cell obj)
    (throw (ex-info (str (class-of obj) "::" method-name
                         " needs an identity object")
                    {:class-name (class-of obj)})))
  (let [method (lookup-method methods-ir (class-of obj) method-name)
        ctx {:schema-ir schema-ir
             :methods-ir methods-ir
             :this obj
             :params (bind-params method args)
             :locals {}}
        inner (eval-lets (:lets method) (assoc ctx :locals {}))]
    (install-update! schema-ir obj (:body method) inner)
    (wire-new schema-ir obj)
    (when (= "update!" method-name)
      (doseq [sub @(:wchnt/subscribers obj)]
        (apply-mutating-method schema-ir methods-ir sub "update!" [])))
    obj))

(defn inject
  "Host-only: write mailbox fields (schema order), then update!()."
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
    (apply-mutating-method schema-ir methods-ir obj "update!" [])))

(defn call
  "Invoke a method on an interpreter object. args is a vector of already-evaled values."
  [schema-ir methods-ir obj method-name args]
  (if (ir/mutating-method-name? method-name)
    (apply-mutating-method schema-ir methods-ir obj method-name args)
    (let [class-name (class-of obj)
          method (lookup-method-or-delegate schema-ir methods-ir class-name method-name)
          via (or (ir/delegate-path schema-ir class-name (:class method)) [])
          this (reduce (fn [o f] (get-field schema-ir o f)) obj via)]
      (eval-method method
                   {:schema-ir schema-ir
                    :methods-ir methods-ir
                    :this this
                    :params (bind-params method args)
                    :locals {}}))))
