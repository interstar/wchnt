(ns wchnt-lang.targets.haxe-backend
  "Transform WCHNT IR to Haxe code"
  (:require [wchnt-lang.ir :as ir]
            [clojure.string :as str]
            [wchnt-lang.targets.haxe-std :as haxe-helpers]))

;; =============================================================================
;; IR to Haxe Transformation
;; =============================================================================

(defn- haxe-enum-ctor
  "Haxe enum constructors are type identifiers: first letter upper-case."
  [raw]
  (-> (str/trim (str raw))
      (str/replace #"[^A-Za-z0-9]" "")
      (str/replace #"^[a-z]" str/upper-case)))

(defn generate-component-field
  "Generate a Haxe field declaration for a component"
  [component]
  (let [component-name (:component-name component)
        type-name (:type-name component)
        relationship (:relationship component)]
    (str "    public var " component-name ": " type-name ";")))

(defn generate-context-field
  "Generate a Haxe field for context-specific components"
  [parent-class-name]
  (str "    public var " (ir/context-field-name parent-class-name) ": " parent-class-name ";"))

(defn generate-constructor-params
  "Generate constructor parameters for an assemblage"
  [components]
  (str/join ", " 
    (for [component components]
      (let [component-name (:component-name component)
            type-name (:type-name component)]
        (str component-name ":" type-name)))))

(defn- constructor-context-lines
  "Wire :context children to this parent. Same rule as factory setContext."
  [components schema-ir class-name]
  (keep (fn [component]
          (let [type-name (:type-name component)]
            (when (and schema-ir class-name type-name
                       (ir/needs-context? schema-ir type-name)
                       (= class-name (ir/get-context-parent schema-ir type-name)))
              (str "this." (:component-name component) ".setContext(this);"))))
        components))

(defn- constructor-subscribe-lines
  "Subscribe this parent to each $ reactive child. Same rule as factory subscribe."
  [components]
  (keep (fn [component]
          (when (= :reactive (:relationship component))
            (str "this." (:component-name component) ".subscribe(this);")))
        components))

(defn generate-constructor-body
  "Generate constructor body: field assigns, then construction magic
   (context back-refs and reactive subscriptions). Magic runs for every
   `new Class(...)`, including objects built inside methods."
  ([components]
   (generate-constructor-body components nil nil))
  ([components schema-ir class-name]
   (str/join "\n        "
             (concat
              (for [component components]
                (str "this." (:component-name component) " = "
                     (:component-name component) ";"))
              (constructor-context-lines components schema-ir class-name)
              (constructor-subscribe-lines components)))))

(defn generate-to-construction-parts
  "Generate the parts for a toConstruction method using the new helper pattern"
  [components schema-ir]
  (for [component components]
    (let [component-name (:component-name component)
          type-name (:type-name component)]
      (cond
        (str/starts-with? type-name "Array<")
        (str "helper.arrayToConstruction(this." component-name ", depth + 1)")
        (str/starts-with? type-name "Map<")
        (str "helper.mapToConstruction(this." component-name ", depth + 1)")
        ;; Handle primitive types (fix the string quoting issue)
        (= type-name "String")
        (str "'\"' + this." component-name " + '\"'")
        (contains? #{"Int" "Float" "Bool"} type-name)
        (str "this." component-name)
        ;; Check if it's an enum type
        (some #(= (:name %) type-name) (:enums schema-ir))
        (str "helper.enumToConstruction(this." component-name ", depth + 1)")
        (ir/external-type? schema-ir type-name)
        (str "'@" type-name "'")
        ;; For other types (custom classes), call toConstruction with helper
        :else
        (str "this." component-name ".toConstruction(depth + 1, helper)")))))

(defn generate-to-construction-method
  "Generate the toConstruction method for a class using the new helper pattern"
  [class-name components schema-ir]
  (let [to-construction-parts (generate-to-construction-parts components schema-ir)
        indent-line (str "ind + '  ' + ")]
    (str "\n    public function toConstruction(depth:Int = 0, helper:IWCHNTHelper):String {\n"
         "        var ind = \"\";\n"
         "        for (i in 0...depth) ind += \"  \";\n"
         "        var nl = '\\n';\n"
         "        return ind + '[:" class-name "' + nl + "
         (str/join " + nl + " (map #(str indent-line %) to-construction-parts)) 
         " + nl + ind + ']' ;\n"
         "    }")))

(defn generate-set-context-method
  "Generate setContext method for context-specific components"
  [parent-class-name]
  (str "\n    public function setContext(c: " parent-class-name ") {\n"
       "        this." (ir/context-field-name parent-class-name) " = c;\n"
       "    }"))

(defn generate-observable-infrastructure
  "Generate observable infrastructure for classes that need it"
  [class-name]
  (str "\n    // Observable infrastructure for " class-name "\n"
       "    private var subscribers: Array<Dynamic> = [];\n"
       "\n"
       "    public function subscribe(subscriber: Dynamic): Void {\n"
       "        if (subscribers.indexOf(subscriber) < 0) {\n"
       "            subscribers.push(subscriber);\n"
       "        }\n"
       "    }\n"
       "\n"
       "    public function unsubscribe(subscriber: Dynamic): Void {\n"
       "        subscribers.remove(subscriber);\n"
       "    }\n"
       "\n"
       "    public function notifySubscribers(): Void {\n"
       "        for (subscriber in subscribers) {\n"
       "            Reflect.callMethod(subscriber, Reflect.field(subscriber, 'update_mutates'), []);\n"
       "        }\n"
       "    }"))

(declare expr-ir-to-haxe)
(declare let-binding-lines)

(defn- haxe-infix-part
  [part]
  (if (string? part)
    part
    (let [haxe (expr-ir-to-haxe part)]
      (if (= :arith (:expr part))
        (str "(" haxe ")")
        haxe))))

(defn- haxe-infix
  "Render infix arith. Int-only / uses Std.int; Float chains keep Haxe /."
  [parts type]
  (let [float-chain? (or (= "Float" type)
                         (some (fn [p]
                                 (and (map? p)
                                      (or (= :float (:expr p))
                                          (= "Float" (:type p)))))
                               parts))]
    (if (and (some #(= "/" %) parts) (not float-chain?))
      (loop [acc (haxe-infix-part (first parts))
             rest (rest parts)]
        (if (empty? rest)
          acc
          (let [op (first rest)
                rhs (haxe-infix-part (second rest))
                combined (if (= op "/")
                           (str "Std.int((" acc ") / (" rhs "))")
                           (str acc " " op " " rhs))]
            (recur combined (drop 2 rest)))))
      (str/join " " (map haxe-infix-part parts)))))

(defn- haxe-join-op
  [op exprs]
  (->> exprs
       (map expr-ir-to-haxe)
       (str/join (str " " op " "))))

(defn- bitwise-precedence
  [expr]
  (when (= :bitwise (:expr expr))
    (case (:op expr)
      "<<" 4
      ">>" 4
      ">>>" 4
      "&" 3
      "^" 2
      "|" 1
      nil)))

(defn- haxe-bitwise-operand
  [expr parent-op side]
  (let [child-prec (or (bitwise-precedence expr) 0)
        parent-prec (case parent-op
                      ("<<" ">>" ">>>") 4
                      "&" 3
                      "^" 2
                      "|" 1
                      0)
        needs-parens? (and (pos? child-prec)
                           (if (= side :right)
                             true
                             (< child-prec parent-prec)))
        rendered (expr-ir-to-haxe expr)]
    (if needs-parens? (str "(" rendered ")") rendered)))

(defn- haxe-cmp-operand
  [expr]
  (let [rendered (expr-ir-to-haxe expr)]
    (if (bitwise-precedence expr)
      (str "(" rendered ")")
      rendered)))

(defn- haxe-lets-then-value
  [{:keys [lets body]} return?]
  (let [let-lines (mapcat let-binding-lines (or lets []))
        body-lines [(if return?
                      (str "        return " (expr-ir-to-haxe body) ";")
                      (str "        " (expr-ir-to-haxe body) ";"))]]
    (str/join "\n" (concat let-lines body-lines))))

(declare haxe-if haxe-switch)

(defn- branch-lets
  [branch]
  (cond
    (= (:expr branch) :if)
    (concat (or (:lets branch) [])
            (branch-lets (:then branch))
            (branch-lets (:else branch)))

    (= (:expr branch) :switch)
    (concat (or (:lets branch) [])
            (mapcat branch-lets (:branches branch))
            (branch-lets (:else branch)))

    :else
    (or (:lets branch) [])))

(defn- strip-branch-lets
  [branch]
  (cond
    (= (:expr branch) :if)
    (assoc branch
           :lets []
           :then (strip-branch-lets (:then branch))
           :else (strip-branch-lets (:else branch)))

    (= (:expr branch) :switch)
    (assoc branch
           :lets []
           :branches (mapv strip-branch-lets (:branches branch))
           :else (strip-branch-lets (:else branch)))

    :else
    (assoc branch :lets [])))

(defn- collect-branch-lets
  [expr]
  (cond
    (= (:expr expr) :if)
    (concat (branch-lets (:then expr))
            (branch-lets (:else expr)))

    (= (:expr expr) :switch)
    (concat (mapcat branch-lets (:branches expr))
            (branch-lets (:else expr)))

    :else nil))

(defn- strip-branch-lets-expr
  [expr]
  (cond
    (= (:expr expr) :if)
    (assoc expr
           :then (strip-branch-lets (:then expr))
           :else (strip-branch-lets (:else expr)))

    (= (:expr expr) :switch)
    (assoc expr
           :branches (mapv strip-branch-lets (:branches expr))
           :else (strip-branch-lets (:else expr)))

    :else expr))

(defn- haxe-if-branch-value
  "Render one if branch as a Haxe expression (nested if or body)."
  [branch]
  (when (seq (:lets branch []))
    (throw (ex-info "if branch lets must be hoisted before Haxe emission"
                    {:lets (:lets branch)})))
  (if (= (:expr branch) :if)
    (haxe-if branch)
    (expr-ir-to-haxe (:body branch))))

(defn- haxe-if-else-tail
  [else-branch]
  (if (= (:expr else-branch) :if)
    (str "else if (" (expr-ir-to-haxe (:condition else-branch)) ") "
         (haxe-if-branch-value (:then else-branch)) " "
         (haxe-if-else-tail (:else else-branch)))
    (str "else " (haxe-if-branch-value else-branch))))

(defn- haxe-if
  "Haxe if expression, parenthesised for use in arith and assignments."
  [expr]
  (str "(if (" (expr-ir-to-haxe (:condition expr)) ") "
       (haxe-if-branch-value (:then expr)) " "
       (haxe-if-else-tail (:else expr)) ")"))

(defn- haxe-switch-branch
  [branch]
  (when (seq (:lets branch []))
    (throw (ex-info "switch branch lets must be hoisted before Haxe emission"
                    {:lets (:lets branch)})))
  (str "case " (expr-ir-to-haxe (:pattern branch)) ": "
       (expr-ir-to-haxe (:body branch)) ";"))

(defn- haxe-switch
  "Haxe switch expression, parenthesised for use in arith and assignments."
  [expr]
  (when (seq (:lets (:else expr) []))
    (throw (ex-info "switch else lets must be hoisted before Haxe emission"
                    {:lets (:lets (:else expr))})))
  (str "(switch (" (expr-ir-to-haxe (:scrutinee expr)) ") { "
       (str/join " " (map haxe-switch-branch (:branches expr)))
       "default: " (expr-ir-to-haxe (:body (:else expr))) "; "
       "})"))

(defn- haxe-lambda
  ([expr]
   (haxe-lambda expr (:params expr)))
  ([expr params]
   (let [params-str (str/join ", " (map #(str (:name %) ":" (:type %)) params))]
     (str "function(" params-str "):" (:type expr) " {\n"
          (haxe-lets-then-value expr true) "\n"
          "        }"))))

(defn- haxe-fold
  [expr]
  (let [[initial lam] (:args expr)
        params (vec (:params lam))]
    (when (not= 2 (count params))
      (throw (ex-info "fold lambda must have two parameters"
                      {:params params})))
    (str "Lambda.fold(" (expr-ir-to-haxe (:receiver expr)) ", "
         (haxe-lambda lam [(second params) (first params)]) ", "
         (expr-ir-to-haxe initial) ")")))

(defn- haxe-runtime-call
  [fn-name expr]
  (str "WCHNTRuntime." fn-name "("
       (str/join ", " (map expr-ir-to-haxe (cons (:receiver expr) (:args expr))))
       ")"))

(defn- haxe-dot-call
  [expr]
  (str (expr-ir-to-haxe (:receiver expr)) "." (ir/haxe-method-name (:method expr)) "("
       (str/join ", " (map expr-ir-to-haxe (:args expr)))
       ")"))

(defn- haxe-map-map
  [expr]
  (let [lam (first (:args expr))
        k-type (:type (first (:params lam)))
        w-type (:type lam)]
    (when (or (nil? k-type) (nil? w-type))
      (throw (ex-info "Map::map is missing key or result types"
                      {:expr expr})))
    (str "WCHNTRuntime.mapMap("
         (expr-ir-to-haxe (:receiver expr)) ", "
         (expr-ir-to-haxe lam) ", "
         "new Map<" k-type ", " w-type ">())")))

(defn- haxe-call
  [expr]
  (if (= :import-alias (:expr (:receiver expr)))
    (str (or (:import-assemblage expr) (:import-class expr)) "."
         (ir/haxe-method-name (:method expr)) "("
         (str/join ", " (map expr-ir-to-haxe (:args expr)))
         ")")
    (case (:method expr)
    "fold" (if (= "Map" (:on expr))
             (haxe-runtime-call "mapFold" expr)
             (haxe-fold expr))
    "map" (if (= "Map" (:on expr))
            (haxe-map-map expr)
            (haxe-dot-call expr))
    "filter" (if (= "Map" (:on expr))
               (haxe-runtime-call "mapFilter" expr)
               (haxe-dot-call expr))
    "length" (str (expr-ir-to-haxe (:receiver expr)) ".length")
    "concat" (str (expr-ir-to-haxe (:receiver expr)) " + "
                  (expr-ir-to-haxe (first (:args expr))))
    "cons" (str "[" (expr-ir-to-haxe (first (:args expr))) "].concat("
                (expr-ir-to-haxe (:receiver expr)) ")")
    "head" (haxe-runtime-call "arrayHead" expr)
    "tail" (haxe-runtime-call "arrayTail" expr)
    "get" (if (= "Array" (:on expr))
            (str "(" (expr-ir-to-haxe (:receiver expr)) ")["
                 (expr-ir-to-haxe (first (:args expr))) "]")
            (haxe-runtime-call (if (= 2 (count (:args expr)))
                                 "mapGetDefault"
                                 "mapGet")
                               expr))
    "exists" (haxe-runtime-call "mapExists" expr)
    "put" (haxe-runtime-call "mapPut" expr)
    "remove" (haxe-runtime-call "mapRemove" expr)
    "substring" (haxe-runtime-call "substring" expr)
    "str" (str "Std.string(" (expr-ir-to-haxe (:receiver expr)) ")")
    "toInt" (str "Std.int(" (expr-ir-to-haxe (:receiver expr)) ")")
    "floor" (if (:external-type expr)
              (haxe-dot-call expr)
              (str "Math.floor(" (expr-ir-to-haxe (:receiver expr)) ")"))
    "ceil" (if (:external-type expr)
             (haxe-dot-call expr)
             (str "Math.ceil(" (expr-ir-to-haxe (:receiver expr)) ")"))
    "round" (if (:external-type expr)
              (haxe-dot-call expr)
              (str "Math.round(" (expr-ir-to-haxe (:receiver expr)) ")"))
    "tpl" (haxe-runtime-call "tpl" expr)
    "times" (haxe-runtime-call "times" expr)
    (haxe-dot-call expr))))

(defn expr-ir-to-haxe
  "Render a reaction expression IR node as a Haxe expression string."
  [expr]
  (case (:expr expr)
    :int (str (:value expr))
    :float (str (:value expr))
    :bool (if (:value expr) "true" "false")
    :string (str "\"" (:value expr) "\"")
    :field (str "this." (:name expr))
    :param (:name expr)
    :local (:name expr)
    :this "this"
    :path (str (expr-ir-to-haxe (:root expr)) "."
               (str/join "." (:fields expr)))
    :call (haxe-call expr)
    :target-call (str "Main." (:haxe-name expr) "("
                      (str/join ", " (map expr-ir-to-haxe (:args expr)))
                      ")")
    :lambda (haxe-lambda expr)
    :if (haxe-if expr)
    :switch (haxe-switch expr)
    :neg (let [inner (expr-ir-to-haxe (:arg expr))]
           (if (re-matches #"[A-Za-z0-9_.]+" inner)
             (str "-" inner)
             (str "-(" inner ")")))
    :arith (haxe-infix (:parts expr) (:type expr))
    :and (haxe-join-op "&&" (:args expr))
    :or (haxe-join-op "||" (:args expr))
    :not (str "!(" (expr-ir-to-haxe (:arg expr)) ")")
    :cmp (str (haxe-cmp-operand (:left expr)) " " (:op expr) " "
              (haxe-cmp-operand (:right expr)))
    :bitwise (str (haxe-bitwise-operand (:left expr) (:op expr) :left)
                  " " (:op expr) " "
                  (haxe-bitwise-operand (:right expr) (:op expr) :right))
    :bitnot (str "~" (expr-ir-to-haxe (:arg expr)))
    :construct (str "new " (:class-name expr) "("
                    (str/join ", " (map expr-ir-to-haxe (:args expr)))
                    ")")
    :array (if (empty? (:items expr))
             (str "new Array<" (:elem-type expr) ">()")
             (str "[" (str/join ", " (map expr-ir-to-haxe (:items expr))) "]"))
    :map (if (empty? (:pairs expr))
           (str "new Map<" (:key-type expr) ", " (:val-type expr) ">()")
           (str "[" (str/join ", " (map (fn [pair]
                                          (str (expr-ir-to-haxe (:key pair)) " => "
                                               (expr-ir-to-haxe (:value pair))))
                                        (:pairs expr)))
                "]"))
    :enum (haxe-enum-ctor (:name expr))
    (throw (ex-info "Unknown expression IR in method body" {:expr expr}))))

(defn- method-let-lines
  [lets]
  (mapcat let-binding-lines (or lets [])))

(defn- identity-slot-type?
  "Mailbox and observable ($) objects keep their identity across update()."
  [schema-ir type-name]
  (or (ir/is-observable? schema-ir type-name)
      (ir/mailbox-class? schema-ir type-name)))

(defn- self-field-ref?
  "Construction arg names the same field on this (listing every schema field)."
  [field-name arg-expr]
  (and (= (:expr arg-expr) :field)
       (= (:name arg-expr) field-name)))

(defn- no-op-passthrough-field?
  "Skip assignment when update names an unchanged field (avoids Haxe self-assign).
   In-place mutation semantics apply only to $ and > slots; see identity branches."
  [schema-ir type-name field arg-expr]
  (or (self-field-ref? field arg-expr)
      (and (identity-slot-type? schema-ir type-name)
           (= (:expr arg-expr) :field)
           (= (:name arg-expr) field))))

(defn- update-field-lines
  "Install update construction fields. Identity slots mutate in place; values replace."
  [schema-ir components args]
  (mapcat (fn [component arg-expr]
            (let [field (:component-name component)
                  type-name (:type-name component)]
              (cond
                (and (identity-slot-type? schema-ir type-name)
                     (= (:expr arg-expr) :construct)
                     (not= (:class-name arg-expr) type-name))
                (throw (ex-info
                        (str "update must not replace identity slot '" field
                             "' with a " (:class-name arg-expr)
                             "; name the existing " type-name " slot or construct "
                             type-name " fields in place")
                        {:field field :expected type-name :got (:class-name arg-expr)}))

                (and (identity-slot-type? schema-ir type-name)
                     (= (:expr arg-expr) :construct)
                     (= (:class-name arg-expr) type-name))
                (keep (fn [[sub-comp sub-arg]]
                        (when-not (self-field-ref? (:component-name sub-comp) sub-arg)
                          (str "        this." field "." (:component-name sub-comp)
                               " = " (expr-ir-to-haxe sub-arg) ";")))
                      (map vector
                           (ir/get-assemblage-components schema-ir type-name)
                           (:args arg-expr)))

                (no-op-passthrough-field? schema-ir type-name field arg-expr)
                []

                :else
                [(str "        this." field " = " (expr-ir-to-haxe arg-expr) ";")])))
          components args))

(defn- update-context-lines
  [components schema-ir class-name]
  (keep (fn [component]
          (let [type-name (:type-name component)]
            (when (and schema-ir type-name
                       (ir/needs-context? schema-ir type-name)
                       (= class-name (ir/get-context-parent schema-ir type-name)))
              (str "        this." (:component-name component)
                   ".setContext(this);"))))
        components))

(defn- let-binding-lines
  "Emit one let binding as a Haxe var. WCHNT lets are expressions, not statements."
  [{:keys [name value]}]
  [(str "        var " name " = " (expr-ir-to-haxe value) ";")])

(defn- generate-ordinary-method
  [{:keys [method-name parameters return-type body lets]} static?]
  (let [hoisted (collect-branch-lets body)
        body (if (seq hoisted) (strip-branch-lets-expr body) body)
        params (str/join ", " (map #(str (:name %) ":" (:type %)) parameters))
        body-lines [(str "        return " (expr-ir-to-haxe body) ";")]
        kind (if static? "public static function" "public function")]
        (str "\n    " kind " " (ir/haxe-method-name method-name) "(" params "): " return-type " {\n"
         (str/join "\n" (concat (method-let-lines lets)
                                (method-let-lines hoisted)
                                body-lines))
         "\n    }")))

(defn- generate-mutating-method
  "Install the construction onto this, then return this. update! also notifies."
  [{:keys [class method-name parameters body lets]}
   {:keys [components observable? schema-ir class-name]}]
  (let [class-name (or class-name class)
        parameters (or parameters [])
        params (str/join ", " (map #(str (:name %) ":" (:type %)) parameters))
        args (:args body)]
    (when-not (= :construct (:expr body))
      (throw (ex-info "mutating method body must be a construction"
                      {:class-name class-name})))
    (when (not= (count components) (count args))
      (throw (ex-info (str class-name "::" method-name
                           " construction does not match class fields")
                      {:class-name class-name
                       :expected (count components)
                       :got (count args)})))
    (str "\n    public function " (ir/haxe-method-name method-name) "(" params "): " class-name " {\n"
         (str/join "\n" (concat
                         (method-let-lines lets)
                         (update-field-lines schema-ir components args)
                         (update-context-lines components schema-ir class-name)
                         (when (and observable? (= "update!" method-name))
                           ["        this.notifySubscribers();"])
                         ["        return this;"]))
         "\n    }")))

(defn- generate-inject-method
  "Host-only: write schema fields, then update!(). Not callable from Methods."
  [class-name components]
  (let [params (str/join ", " (map #(str (:component-name %) ":" (:type-name %))
                                  components))
        assigns (map #(str "        this." (:component-name %) " = "
                           (:component-name %) ";")
                     components)]
    (str "\n    public function inject(" params "): " class-name " {\n"
         (str/join "\n" assigns)
         "\n        return this.update_mutates();\n"
         "    }")))

(defn generate-method
  "Generate a Haxe method from methods IR. ! methods rewrite this in place."
  ([method]
   (generate-method method {:components []
                            :observable? false
                            :schema-ir nil
                            :class-name (:class method)}))
  ([method ctx]
   (when (:interface-signature? method)
     (throw (ex-info "Interface signatures are not emitted on concrete classes"
                     {:class (:class method) :method-name (:method-name method)})))
   (if (:mutating? method)
     (generate-mutating-method method ctx)
     (generate-ordinary-method method false))))

(defn- methods-for-class
  [methods-ir class-name]
  (filterv #(= class-name (:class %)) methods-ir))

(defn- generate-promoted-field
  [{:keys [name type fields]}]
  (str "    public var " name "(get, never): " type ";\n"
       "    function get_" name "(): " type " {\n"
       "        return this." (str/join "." fields) ";\n"
       "    }"))

(defn- generate-promoted-method
  [{:keys [method-name parameters return-type]} via-fields]
  (let [params (str/join ", " (map #(str (:name %) ":" (:type %)) parameters))
        args (str/join ", " (map :name parameters))
        recv (str "this." (str/join "." via-fields))]
    (str "\n    public function " (ir/haxe-method-name method-name) "(" params "): " return-type " {\n"
         "        return " recv "." (ir/haxe-method-name method-name) "(" args ");\n"
         "    }")))

(defn- collect-promoted-methods
  [schema-ir methods-ir class-name defined]
  (mapcat (fn [slot]
            (let [inner (:type-name slot)
                  here (for [m (methods-for-class methods-ir inner)
                             :when (and (not (:interface-signature? m))
                                        (not (contains? defined (:method-name m))))]
                         {:method m :via [(:component-name slot)]})
                  nested (collect-promoted-methods schema-ir methods-ir inner defined)]
              (concat here
                      (map (fn [{:keys [method via]}]
                             {:method method
                              :via (into [(:component-name slot)] via)})
                           nested))))
          (ir/delegate-components schema-ir class-name)))

(defn- generate-delegate-forwards
  [schema-ir methods-ir class-name defined]
  (let [fields (str/join "\n" (map generate-promoted-field
                                  (ir/promoted-field-hits schema-ir class-name)))
        methods (str/join ""
                          (map (fn [{:keys [method via]}]
                                 (generate-promoted-method method via))
                               (collect-promoted-methods schema-ir methods-ir
                                                         class-name defined)))]
    {:fields fields :methods methods}))

(defn generate-haxe-class
  "Generate Haxe class from IR assemblage"
  ([assemblage schema-ir]
   (generate-haxe-class assemblage schema-ir []))
  ([assemblage schema-ir class-methods]
   (generate-haxe-class assemblage schema-ir class-methods []))
  ([assemblage schema-ir class-methods methods-ir]
   (let [class-name (:name assemblage)
         components (:components assemblage)
         needs-context (ir/needs-context? schema-ir class-name)
         context-parent (ir/get-context-parent schema-ir class-name)
         is-observable (ir/is-observable? schema-ir class-name)
         interface-implementers (:interface-implementers schema-ir)
         implemented-interfaces (filter #(contains? (set (second %)) class-name) interface-implementers)
         interface-names (map first implemented-interfaces)
         all-interfaces (conj (vec interface-names) "IWCHNTObject")
         implements-clause (if (empty? all-interfaces)
                             ""
                             (str " implements " (str/join " implements " all-interfaces)))
         component-fields (map generate-component-field components)
         context-field (when (and needs-context context-parent) (generate-context-field context-parent))
         all-fields (if context-field
                      (conj component-fields context-field)
                      component-fields)
         constructor-params (generate-constructor-params components)
         constructor-body (generate-constructor-body components schema-ir class-name)
         to-construction-method (generate-to-construction-method class-name components schema-ir)
         set-context-method (when (and needs-context context-parent) (generate-set-context-method context-parent))
         observable-infrastructure (when is-observable (generate-observable-infrastructure class-name))
         inject-method (when (ir/mailbox-class? schema-ir class-name)
                         (generate-inject-method class-name components))
         method-ctx {:components components
                     :observable? is-observable
                     :schema-ir schema-ir
                     :class-name class-name}
         static-public (or (:static-public-methods schema-ir) #{})
         instance-methods (remove #(or (:static? %)
                                       (contains? static-public
                                                  [class-name (:method-name %)]))
                                  class-methods)
         user-methods (str/join "" (map #(generate-method % method-ctx)
                                        instance-methods))
         static-methods ""
         defined (set (map :method-name class-methods))
         forwards (generate-delegate-forwards schema-ir methods-ir class-name defined)]
     (str "class " class-name implements-clause " {\n"
          (str/join "\n" all-fields)
          (when (seq (:fields forwards))
            (str "\n" (:fields forwards)))
          "\n\n"
          "    public function new(" constructor-params ") {\n"
          "        " constructor-body "\n"
          "    }"
          user-methods
          (:methods forwards)
          static-methods
          (or inject-method "")
          (or set-context-method "")
          to-construction-method
          (or observable-infrastructure "")
          "\n}"))))

(defn- generate-interface-method-signature
  [{:keys [method-name parameters return-type]}]
  (let [params (str/join ", " (map #(str (:name %) ":" (:type %)) parameters))]
    (str "    public function " (ir/haxe-method-name method-name) "(" params "): " return-type ";")))

(defn generate-haxe-interface
  "Generate Haxe interface from IR interface and optional interface method signatures."
  ([interface]
   (generate-haxe-interface interface []))
  ([interface methods-ir]
   (let [interface-name (:name interface)
         iface-methods (filterv :interface-signature?
                                (methods-for-class methods-ir interface-name))
         method-lines (map generate-interface-method-signature iface-methods)]
     (str "interface " interface-name " {\n"
          (when (seq method-lines)
            (str (str/join "\n" method-lines) "\n"))
          "    public function toConstruction(depth:Int = 0, helper:IWCHNTHelper):String;\n"
          "}"))))

(defn generate-haxe-enum
  "Generate Haxe enum from IR enum"
  [enum]
  (let [enum-name (:name enum)
        enum-values (:values enum)
        processed-values (map haxe-enum-ctor enum-values)]
    (str "enum " enum-name " {\n"
         (str/join "\n" (map #(str "    " % ";") processed-values))
         "\n}")))

;; No longer needed - arrays are handled inline in each class

(defn generate-haxe-assemblage-class
  "Emit the host-facing wrapper for a program assemblage."
  [schema-ir methods-ir factory-haxe]
  (let [root (:root-class schema-ir)
        class-name (str root "Assemblage")
        static-methods (->> methods-ir
                            (filter #(and (= root (:class %)) (:static? %)))
                            (map #(generate-ordinary-method % true))
                            (apply str))]
    (str "class " class-name " {\n"
         factory-haxe
         static-methods
         "\n}")))

(defn schema-ir-to-haxe
  "Transform schema IR to Haxe code, including any reaction methods."
  ([schema-ir]
   (schema-ir-to-haxe schema-ir [] true))
  ([schema-ir methods-ir]
   (schema-ir-to-haxe schema-ir methods-ir true))
  ([schema-ir methods-ir include-helpers?]
   (let [assemblages (:assemblages schema-ir)
         interfaces (:interfaces schema-ir)
         enums (:enums schema-ir)
         interface-classes (map #(generate-haxe-interface % methods-ir) interfaces)
         iwchnt-helper (str haxe-helpers/iwchnt-object-interface
                            "\n\n"
                            haxe-helpers/iwchnt-helper-interface
                            "\n\n"
                            haxe-helpers/iwchnt-helper-implementation
                            "\n\n"
                            haxe-helpers/wchnt-runtime)
         classes (map #(generate-haxe-class % schema-ir
                                            (remove :interface-signature?
                                                    (methods-for-class methods-ir (:name %)))
                                            methods-ir)
                      assemblages)
         enum-classes (map generate-haxe-enum enums)
         all-classes (concat interface-classes
                             (when include-helpers? [iwchnt-helper])
                             classes enum-classes)]
     (str/join "\n" all-classes)))) 

;; =============================================================================
;; Construction IR to Haxe Transformation
;; =============================================================================

(declare generate-array-element)
(declare render-variable-ref)

(defn- render-array-object-element
  [element schema-ir expected-type variable-mappings]
  (let [arg-class-name (:class-name element)
        arg-args (:args element)]
    (str "new " arg-class-name "("
         (str/join ", " (map #(generate-array-element % schema-ir expected-type variable-mappings) arg-args))
         ")")))

(defn- render-array-primitive-element
  [element]
  (let [primitive-value (:value element)
        class-name (:class-name element)]
    (if (or (= class-name "String") (= class-name 'String))
      (str "\"" primitive-value "\"")
      (str primitive-value))))

(defn- render-array-nested-array-element
  [element schema-ir variable-mappings]
  (let [array-type (:class-name element)
        array-elements (:args element)]
    (str "["
         (str/join ", " (map #(generate-array-element % schema-ir array-type variable-mappings) array-elements))
         "]")))

(defn generate-array-element
  "Generate Haxe code for a single array element"
  [element schema-ir expected-type variable-mappings]
  (cond
    (and (map? element) (= (:type element) :object))
    (render-array-object-element element schema-ir expected-type variable-mappings)

    (and (map? element) (= (:type element) :variable))
    (render-variable-ref element variable-mappings)

    (and (map? element) (= (:type element) :primitive))
    (render-array-primitive-element element)

    (and (map? element) (= (:type element) :enum-value))
    (:value element)

    (and (map? element) (= (:type element) :array))
    (render-array-nested-array-element element schema-ir variable-mappings)

    :else
    (throw (ex-info "Unexpected array element in construction IR"
                    {:element element}))))

(defn generate-array-assignment
  "Generate Haxe code for an array assignment"
  [obj-id args schema-ir class-name variable-mappings]
  (str "  var " obj-id " = ["
       (str/join ", " (map #(generate-array-element % schema-ir class-name variable-mappings) args))
       "];"))

(declare generate-object-assignment-arg)

(defn render-variable-ref
  [arg variable-mappings]
  (let [var-name (or (:value arg) (first (:args arg)))]
    (get variable-mappings var-name var-name)))

(defn- ir-stored-value
  "Read :value even when it is false. `or` drops Bool false and emits empty Haxe."
  [arg]
  (if (contains? arg :value)
    (:value arg)
    (first (:args arg))))

(defn render-primitive-value
  [arg]
  (let [primitive-value (ir-stored-value arg)
        class-name (:class-name arg)]
    (when (nil? primitive-value)
      (throw (ex-info "Primitive constructor argument has no value"
                      {:arg arg})))
    (if (or (= class-name "String") (= class-name 'String))
      (if (and (vector? primitive-value) (= (first primitive-value) :StringLiteral))
        (str "\"" (second primitive-value) "\"")
        (str "\"" primitive-value "\""))
      (str primitive-value))))

(defn render-object-arg
  [arg schema-ir variable-mappings]
  (str "new " (:class-name arg) "("
       (str/join ", " (map #(generate-object-assignment-arg % schema-ir variable-mappings)
                           (:args arg)))
       ")"))

(defn render-array-arg
  [arg schema-ir variable-mappings]
  (str "["
       (str/join ", " (map #(generate-object-assignment-arg % schema-ir variable-mappings)
                           (:args arg)))
       "]"))

(declare render-map-arg)

(defn render-map-entry
  [arg schema-ir variable-mappings]
  (if (and (map? arg) (contains? arg :type))
    (case (:type arg)
      :primitive (render-primitive-value arg)
      :variable (render-variable-ref arg variable-mappings)
      :enum-value (haxe-enum-ctor (:value arg))
      :object (render-object-arg arg schema-ir variable-mappings)
      :array (render-array-arg arg schema-ir variable-mappings)
      :map (render-map-arg arg schema-ir variable-mappings)
      (str (:value arg)))
    (str arg)))

(defn render-map-literal
  [args schema-ir variable-mappings]
  (let [pairs (partition 2 (map #(render-map-entry % schema-ir variable-mappings) args))]
    (str "[" (str/join ", " (map #(str (first %) " => " (second %)) pairs)) "]")))

(defn render-map-arg
  [arg schema-ir variable-mappings]
  (render-map-literal (:args arg) schema-ir variable-mappings))

(defn render-plain-string-arg
  [arg component-types arg-index]
  (if (and (< arg-index (count component-types))
           (= (nth component-types arg-index) "String"))
    (str "\"" arg "\"")
    arg))

(defn generate-object-assignment-arg
  "Generate Haxe code for a single argument in object assignment"
  [arg schema-ir variable-mappings]
  (cond
    (and (map? arg) (= (:type arg) :object))
    (render-object-arg arg schema-ir variable-mappings)

    (and (map? arg) (= (:type arg) :call))
    (expr-ir-to-haxe (:expr arg))

    (and (map? arg) (= (:type arg) :variable))
    (render-variable-ref arg variable-mappings)

    (and (map? arg) (= (:type arg) :primitive))
    (render-primitive-value arg)

    (and (map? arg) (= (:type arg) :enum-value))
    (haxe-enum-ctor (:value arg))

    (and (map? arg) (= (:type arg) :array))
    (render-array-arg arg schema-ir variable-mappings)

    (and (map? arg) (= (:type arg) :map))
    (render-map-arg arg schema-ir variable-mappings)

    :else
    (throw (ex-info "Unexpected object constructor argument in construction IR"
                    {:arg arg}))))

(defn generate-object-assignment
  "Generate Haxe code for an object assignment using structured IR data"
  [obj-id class-name args schema-ir variable-mappings]
  (let [assemblage (first (filter #(= (:name %) class-name) (:assemblages schema-ir)))
        component-types (map :type-name (:components assemblage))
        processed-args (for [[arg-index arg] (map-indexed vector args)]
                         (if (string? arg)
                           (render-plain-string-arg arg component-types arg-index)
                           (generate-object-assignment-arg arg schema-ir variable-mappings)))
        assignment-code (if (str/starts-with? class-name "Array<")
                         (str "  var " obj-id " = ["
                              (str/join ", " processed-args) "];")
                         (str "  var " obj-id " = new " class-name "("
                              (str/join ", " processed-args) ");"))]
    assignment-code))

(defn generate-variable-assignment
  "Generate Haxe code for a variable assignment"
  [obj-id args]
  (if (seq args)
    (str "  var " obj-id " = " (first args) ";")
    (throw (ex-info "Variable assignment missing arguments" {:obj-id obj-id :args args}))))

(defn generate-map-assignment
  "Generate Haxe code for a map assignment"
  [obj-id class-name args schema-ir variable-mappings]
  (str "  var " obj-id " = " (render-map-literal args schema-ir variable-mappings) ";"))

(defn generate-primitive-assignment
  "Generate Haxe code for a primitive assignment"
  [obj-id obj-data]
  (let [value (if (contains? obj-data :value)
                (:value obj-data)
                (if (and (seq (:args obj-data)) (map? (first (:args obj-data))))
                  (:value (first (:args obj-data)))
                  (if (seq (:args obj-data))
                    (first (:args obj-data))
                    (throw (ex-info "Primitive assignment missing arguments" {:obj-id obj-id :obj-data obj-data})))))]
    (str "  var " obj-id " = " value ";")))

(defn generate-assignment-statement
  "Generate Haxe code for a single assignment statement"
  [obj-id obj-data schema-ir variable-mappings]
  (let [obj-type (:type obj-data)
        class-name (:class-name obj-data)
        args (:args obj-data)]
    (case obj-type
      :array (generate-array-assignment obj-id args schema-ir class-name variable-mappings)
      :object (generate-object-assignment obj-id class-name args schema-ir variable-mappings)
      :variable (generate-variable-assignment obj-id (if (contains? obj-data :value) [(:value obj-data)] args))
      :enum-value (if (or (contains? obj-data :value) (seq args))
                    (str "  var " obj-id " = "
                         (haxe-enum-ctor (or (:value obj-data) (first args))) ";")
                    (throw (ex-info "Enum value assignment missing arguments" {:obj-id obj-id :args args})))
      :map (generate-map-assignment obj-id class-name args schema-ir variable-mappings)
      :primitive (generate-primitive-assignment obj-id obj-data)
      (throw (ex-info "Unknown object type in factory generation" 
                    {:obj-type obj-type
                     :obj-data obj-data})))))



(defn generate-subscribe-statements
  "After objects exist, subscribe each parent to its $ components."
  [objects schema-ir]
  (for [[obj-id obj-data] objects
        :when (= :object (:type obj-data))
        component (ir/reactive-components schema-ir (:class-name obj-data))]
    (str "  " obj-id "." (:component-name component) ".subscribe(" obj-id ");")))

(defn generate-context-statements
  "After objects exist, wire :context children to their parent."
  [objects schema-ir]
  (for [[obj-id obj-data] objects
        :when (= :object (:type obj-data))
        :let [parent-class (:class-name obj-data)]
        component (ir/get-assemblage-components schema-ir parent-class)
        :when (= :context-specific (:relationship component))
        :let [child-type (:type-name component)]
        :when (and (ir/needs-context? schema-ir child-type)
                   (= parent-class (ir/get-context-parent schema-ir child-type)))]
    (str "  " obj-id "." (:component-name component) ".setContext(" obj-id ");")))

(defn generate-factory-body
  "Generate the body of a factory function from construction IR"
  [construction-ir schema-ir]
  (let [objects (ir/objects-in-construction-order (:objects construction-ir)
                                                  (:variable-mappings construction-ir))
        variable-mappings (:variable-mappings construction-ir)
        return-object (:return-object construction-ir)
        assignment-statements
        (for [[obj-id obj-data] objects]
          (generate-assignment-statement obj-id obj-data schema-ir variable-mappings))
        subscribe-statements (generate-subscribe-statements objects schema-ir)
        context-statements (generate-context-statements objects schema-ir)
        final-statement (str "  return " return-object ";")]
    (str/join "\n" (concat assignment-statements
                           context-statements
                           subscribe-statements
                           [final-statement]))))

(defn- factory-signature-params
  [construction-ir]
  (->> (or (:factory-params construction-ir) [])
       (map #(str (:name %) ": " (:type %)))
       (str/join ", ")))

(defn generate-construction-factory
  "Generate Haxe factory function from construction IR"
  [construction-ir schema-ir]
  (let [root-class (:root-class construction-ir)
        factory-name "factory"
        params (factory-signature-params construction-ir)
        body (generate-factory-body construction-ir schema-ir)]
    (str "public static function " factory-name "(" params "): " root-class " {\n"
         body "\n"
         "}"))) 
