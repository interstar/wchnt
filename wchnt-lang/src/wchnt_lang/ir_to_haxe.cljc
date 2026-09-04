(ns wchnt-lang.ir-to-haxe
  "Transform WCHNT IR to Haxe code"
  (:require [wchnt-lang.ir :as ir]
            [clojure.string :as str]
            [wchnt-lang.ast-utils :as ast-utils]
            [wchnt-lang.pipeline :as p]
            [wchnt-lang.ast-to-ir :as ast-to-ir]
            [wchnt-lang.haxe-helpers :as haxe-helpers]))

;; =============================================================================
;; IR to Haxe Transformation
;; =============================================================================

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

(defn generate-constructor-body
  "Generate constructor body for an assemblage"
  [components]
  (str/join "\n        " 
    (for [component components]
      (let [component-name (:component-name component)]
        (str "this." component-name " = " component-name ";")))))

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
       "        subscribers.push(subscriber);\n"
       "    }\n"
       "\n"
       "    public function unsubscribe(subscriber: Dynamic): Void {\n"
       "        subscribers.remove(subscriber);\n"
       "    }\n"
       "\n"
       "    public function notifySubscribers(): Void {\n"
       "        for (subscriber in subscribers) {\n"
       "            Reflect.callMethod(subscriber, Reflect.field(subscriber, 'update'), []);\n"
       "        }\n"
       "    }"))

(declare expr-ir-to-haxe)

(defn- haxe-infix-part
  [part]
  (if (string? part)
    part
    (let [haxe (expr-ir-to-haxe part)]
      (if (= :arith (:expr part))
        (str "(" haxe ")")
        haxe))))

(defn- haxe-infix
  [parts]
  (str/join " " (map haxe-infix-part parts)))

(defn- haxe-join-op
  [op exprs]
  (->> exprs
       (map expr-ir-to-haxe)
       (str/join (str " " op " "))))

(defn- haxe-lets-then-value
  [{:keys [lets body]} return?]
  (let [let-lines (map (fn [{:keys [name value]}]
                         (str "        var " name " = " (expr-ir-to-haxe value) ";"))
                       (or lets []))
        value-line (if return?
                     (str "        return " (expr-ir-to-haxe body) ";")
                     (str "        " (expr-ir-to-haxe body) ";"))]
    (str/join "\n" (concat let-lines [value-line]))))

(defn- haxe-if
  [expr]
  (str "if (" (expr-ir-to-haxe (:condition expr)) ") {\n"
       (haxe-lets-then-value (:then expr) false) "\n"
       "    } else {\n"
       (haxe-lets-then-value (:else expr) false) "\n"
       "    }"))

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

(defn- haxe-call
  [expr]
  (case (:method expr)
    "fold" (haxe-fold expr)
    "length" (str (expr-ir-to-haxe (:receiver expr)) ".length")
    "concat" (str (expr-ir-to-haxe (:receiver expr)) " + "
                  (expr-ir-to-haxe (first (:args expr))))
    "cons" (str "[" (expr-ir-to-haxe (first (:args expr))) "].concat("
                (expr-ir-to-haxe (:receiver expr)) ")")
    "head" (haxe-runtime-call "arrayHead" expr)
    "tail" (haxe-runtime-call "arrayTail" expr)
    "get" (haxe-runtime-call "mapGet" expr)
    "put" (haxe-runtime-call "mapPut" expr)
    "remove" (haxe-runtime-call "mapRemove" expr)
    "substring" (haxe-runtime-call "substring" expr)
    "times" (haxe-runtime-call "times" expr)
    (str (expr-ir-to-haxe (:receiver expr)) "." (:method expr) "("
         (str/join ", " (map expr-ir-to-haxe (:args expr)))
         ")")))

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
    :neg (let [inner (expr-ir-to-haxe (:arg expr))]
           (if (re-matches #"[A-Za-z0-9_.]+" inner)
             (str "-" inner)
             (str "-(" inner ")")))
    :arith (haxe-infix (:parts expr))
    :and (haxe-join-op "&&" (:args expr))
    :or (haxe-join-op "||" (:args expr))
    :not (str "!(" (expr-ir-to-haxe (:arg expr)) ")")
    :cmp (str (expr-ir-to-haxe (:left expr)) " " (:op expr) " "
              (expr-ir-to-haxe (:right expr)))
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
    (throw (ex-info "Unknown expression IR in method body" {:expr expr}))))

(defn- method-let-lines
  [lets]
  (map (fn [{:keys [name value]}]
         (str "        var " name " = " (expr-ir-to-haxe value) ";"))
       (or lets [])))

(defn- update-temp-lines
  [args]
  (map-indexed (fn [i arg]
                 (str "        var __u" i " = " (expr-ir-to-haxe arg) ";"))
               args))

(defn- update-assign-lines
  [components]
  (map-indexed (fn [i component]
                 (str "        this." (:component-name component) " = __u" i ";"))
               components))

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

(defn- generate-ordinary-method
  [{:keys [method-name parameters return-type body lets]}]
  (let [params (str/join ", " (map #(str (:name %) ":" (:type %)) parameters))]
    (str "\n    public function " method-name "(" params "): " return-type " {\n"
         (str/join "\n" (concat (method-let-lines lets)
                                [(str "        return " (expr-ir-to-haxe body) ";")]))
         "\n    }")))

(defn- generate-update-method
  "Install the construction onto this, then notify if this class is observable."
  [{:keys [class body lets]} {:keys [components observable? schema-ir class-name]}]
  (let [class-name (or class-name class)
        args (:args body)]
    (when-not (= :construct (:expr body))
      (throw (ex-info "update method body must be a construction"
                      {:class-name class-name})))
    (when (not= (count components) (count args))
      (throw (ex-info (str class-name "::update construction does not match class fields")
                      {:class-name class-name
                       :expected (count components)
                       :got (count args)})))
    (str "\n    public function update(): " class-name " {\n"
         (str/join "\n" (concat
                         (method-let-lines lets)
                         (update-temp-lines args)
                         (update-assign-lines components)
                         (update-context-lines components schema-ir class-name)
                         (when observable? ["        this.notifySubscribers();"])
                         ["        return this;"]))
         "\n    }")))

(defn generate-method
  "Generate a Haxe method from methods IR. update rewrites this in place."
  ([method]
   (generate-method method {:components []
                            :observable? false
                            :schema-ir nil
                            :class-name (:class method)}))
  ([method ctx]
   (when (:interface-signature? method)
     (throw (ex-info "Interface signatures are not emitted on concrete classes"
                     {:class (:class method) :method-name (:method-name method)})))
   (if (= "update" (:method-name method))
     (generate-update-method method ctx)
     (generate-ordinary-method method))))

(defn- methods-for-class
  [methods-ir class-name]
  (filterv #(= class-name (:class %)) methods-ir))

(defn generate-haxe-class
  "Generate Haxe class from IR assemblage"
  ([assemblage schema-ir]
   (generate-haxe-class assemblage schema-ir []))
  ([assemblage schema-ir class-methods]
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
         constructor-body (generate-constructor-body components)
         to-construction-method (generate-to-construction-method class-name components schema-ir)
         set-context-method (when (and needs-context context-parent) (generate-set-context-method context-parent))
         observable-infrastructure (when is-observable (generate-observable-infrastructure class-name))
         method-ctx {:components components
                     :observable? is-observable
                     :schema-ir schema-ir
                     :class-name class-name}
         user-methods (str/join "" (map #(generate-method % method-ctx) class-methods))]
     (str "class " class-name implements-clause " {\n"
          (str/join "\n" all-fields)
          "\n\n"
          "    public function new(" constructor-params ") {\n"
          "        " constructor-body "\n"
          "    }"
          user-methods
          (or set-context-method "")
          to-construction-method
          (or observable-infrastructure "")
          "\n}"))))

(defn- generate-interface-method-signature
  [{:keys [method-name parameters return-type]}]
  (let [params (str/join ", " (map #(str (:name %) ":" (:type %)) parameters))]
    (str "    public function " method-name "(" params "): " return-type ";")))

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
        processed-values (for [value enum-values]
                          (let [raw-value (str/trim value)
                                enum-value-name (-> raw-value
                                                   (str/replace #"[^A-Za-z0-9]" "")
                                                   (str/replace #"^[a-z]" str/upper-case))]
                            enum-value-name))]
    (str "enum " enum-name " {\n"
         (str/join "\n" (map #(str "    " % ";") processed-values))
         "\n}")))

;; No longer needed - arrays are handled inline in each class

(defn schema-ir-to-haxe
  "Transform schema IR to Haxe code, including any reaction methods."
  ([schema-ir]
   (schema-ir-to-haxe schema-ir []))
  ([schema-ir methods-ir]
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
                                                    (methods-for-class methods-ir (:name %))))
                      assemblages)
         enum-classes (map generate-haxe-enum enums)
         all-classes (concat interface-classes [iwchnt-helper] classes enum-classes)]
     (str/join "\n" all-classes)))) 

;; =============================================================================
;; Construction IR to Haxe Transformation
;; =============================================================================

(declare process-inner-object-construction)
(declare generate-array-element)
(declare render-variable-ref)

(defn- inner-object-class-and-arg-list
  [element schema-ir expected-type]
  (let [second-element (second element)]
    (if (ast-utils/node-type? second-element :ClassName)
      ;; Has explicit class name: [:InnerObjectConstruction [:ClassName "Name"] [:ArgList ...]]
      [(second second-element) (nth element 2)]
      ;; No explicit class name: [:InnerObjectConstruction [:ArgList ...]]
      (if expected-type
        [expected-type second-element]
        (throw (ex-info "Cannot determine class name for InnerObjectConstruction - no explicit class name and no expected type"
                        {:element element
                         :schema-ir schema-ir
                         :expected-type expected-type}))))))

(defn- arg-list->constructor-args
  [arg-list]
  (if (ast-utils/node-type? arg-list :ArgList)
    (rest arg-list)
    []))

(defn- render-inner-object-constructor-arg
  [arg schema-ir class-name]
  (cond
    ;; Handle nested InnerObjectConstruction nodes recursively
    (and (vector? arg) (ast-utils/node-type? arg :InnerObjectConstruction))
    (if schema-ir
      ;; Use schema to determine expected type for this position
      (let [expected-arg-type (ast-to-ir/type-from-class-and-position class-name 0 schema-ir)]
        (process-inner-object-construction arg schema-ir expected-arg-type))
      (throw (ex-info "Schema IR is required to process nested InnerObjectConstruction nodes"
                      {:element arg
                       :class-name class-name
                       :schema-ir schema-ir})))

    ;; Handle ArrayConstruction nodes
    (and (vector? arg) (ast-utils/node-type? arg :ArrayConstruction))
    (str arg)

    ;; Handle primitive AST nodes
    (and (vector? arg) (ast-utils/node-type? arg :IntLiteral))
    (second arg)
    (and (vector? arg) (ast-utils/node-type? arg :StringLiteral))
    (str "\"" (second arg) "\"")
    (and (vector? arg) (ast-utils/node-type? arg :FloatLiteral))
    (second arg)
    (and (vector? arg) (ast-utils/node-type? arg :BooleanLiteral))
    (second arg)

    ;; Handle VariableRef nodes
    (and (vector? arg) (ast-utils/node-type? arg :VariableRef))
    (second arg)

    ;; Handle IR objects
    (and (map? arg) (= (:type arg) :primitive))
    (:value arg)

    ;; Default case
    :else
    (str arg)))

(defn process-inner-object-construction
  "Process an InnerObjectConstruction node and return Haxe constructor code"
  [element schema-ir expected-type]
  (cond
    ;; InnerObjectConstruction: [:InnerObjectConstruction [:ClassName "Name"] [:ArgList ...]] or [:InnerObjectConstruction [:ArgList ...]]
    (ast-utils/node-type? element :InnerObjectConstruction)
    (let [[class-name arg-list] (inner-object-class-and-arg-list element schema-ir expected-type)
          constructor-args (arg-list->constructor-args arg-list)]
      (str "new " class-name "("
           (str/join
            ", "
            (map #(render-inner-object-constructor-arg % schema-ir class-name)
                 constructor-args))
           ")"))
    :else
    (str element)))

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

    (ast-utils/node-type? element :InnerObjectConstruction)
    (process-inner-object-construction element schema-ir expected-type)

    (ast-utils/node-type? element :IntLiteral) (second element)
    (ast-utils/node-type? element :StringLiteral) (str "\"" (second element) "\"")
    (ast-utils/node-type? element :FloatLiteral) (second element)
    (ast-utils/node-type? element :BooleanLiteral) (second element)

    (ast-utils/node-type? element :VariableRef)
    (get variable-mappings (second element) (second element))

    :else (str element)))

(defn generate-array-assignment
  "Generate Haxe code for an array assignment"
  [obj-id args schema-ir class-name variable-mappings]
  (str "  var " obj-id " = ["
       (str/join ", " (map #(generate-array-element % schema-ir class-name variable-mappings) args))
       "];"))

(declare generate-object-assignment-arg)
(declare generate-ast-node-arg)

(defn render-variable-ref
  [arg variable-mappings]
  (let [var-name (or (:value arg) (first (:args arg)))]
    (get variable-mappings var-name var-name)))

(defn render-primitive-value
  [arg]
  (let [primitive-value (or (:value arg) (first (:args arg)))
        class-name (:class-name arg)]
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

(defn render-map-entry
  [arg]
  (if (and (map? arg) (contains? arg :type))
    (case (:type arg)
      :primitive (render-primitive-value arg)
      :variable (or (:value arg) (first (:args arg)))
      :enum-value (:value arg)
      (str (:value arg)))
    (str arg)))

(defn render-map-literal
  [args]
  (let [pairs (partition 2 (map render-map-entry args))]
    (str "[" (str/join ", " (map #(str (first %) " => " (second %)) pairs)) "]")))

(defn render-map-arg
  [arg]
  (render-map-literal (:args arg)))

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

    (and (map? arg) (= (:type arg) :variable))
    (render-variable-ref arg variable-mappings)

    (and (map? arg) (= (:type arg) :primitive))
    (render-primitive-value arg)

    (and (map? arg) (= (:type arg) :enum-value))
    (:value arg)

    (and (map? arg) (= (:type arg) :array))
    (render-array-arg arg schema-ir variable-mappings)

    (and (map? arg) (= (:type arg) :map))
    (render-map-arg arg)

    (vector? arg)
    (generate-ast-node-arg arg schema-ir nil 0)

    :else
    (str arg)))

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



(defn generate-ast-node-arg
  "Generate Haxe code for a raw AST node (fallback for backward compatibility)"
  [ast-node schema-ir parent-class-name arg-index]
  (cond
    ;; InnerObjectConstruction
    (ast-utils/node-type? ast-node :InnerObjectConstruction)
    (process-inner-object-construction ast-node schema-ir parent-class-name)
    
    ;; ArrayConstruction
    (ast-utils/node-type? ast-node :ArrayConstruction)
    (let [type-node (second ast-node)
          array-type (if (ast-utils/node-type? type-node :Type)
                      (second type-node)
                      "Unknown")
          arg-list (nth ast-node 2)
          elements (if (ast-utils/node-type? arg-list :ArgList)
                    (rest arg-list)
                    [])]
      (str "["
           (str/join ", " (map #(generate-ast-node-arg % schema-ir array-type 0) elements))
           "]"))
    
    ;; Primitive literals
    (ast-utils/node-type? ast-node :IntLiteral) (second ast-node)
    (ast-utils/node-type? ast-node :StringLiteral) (str "\"" (second ast-node) "\"")
    (ast-utils/node-type? ast-node :FloatLiteral) (second ast-node)
    (ast-utils/node-type? ast-node :BooleanLiteral) (second ast-node)
    
    ;; Variable references
    (ast-utils/node-type? ast-node :VariableRef) (second ast-node)
    
    ;; Default case
    :else (str ast-node)))

(defn generate-variable-assignment
  "Generate Haxe code for a variable assignment"
  [obj-id args]
  (if (seq args)
    (str "  var " obj-id " = " (first args) ";")
    (throw (ex-info "Variable assignment missing arguments" {:obj-id obj-id :args args}))))

(defn generate-map-assignment
  "Generate Haxe code for a map assignment"
  [obj-id class-name args schema-ir]
  (str "  var " obj-id " = " (render-map-literal args) ";"))

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
                    (str "  var " obj-id " = " (or (:value obj-data) (first args)) ";")
                    (throw (ex-info "Enum value assignment missing arguments" {:obj-id obj-id :args args})))
      :map (generate-map-assignment obj-id class-name args schema-ir)
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

(defn- variable-arg-name
  [arg]
  (when (and (map? arg) (= (:type arg) :variable))
    (or (:value arg) (first (:args arg)))))

(defn- arg-ref-names
  [arg]
  (cond
    (nil? arg) []
    (variable-arg-name arg) [(variable-arg-name arg)]
    (and (map? arg) (:args arg)) (mapcat arg-ref-names (:args arg))
    (sequential? arg) (mapcat arg-ref-names arg)
    :else []))

(defn- resolve-object-id
  [name variable-mappings object-ids]
  (let [resolved (get variable-mappings name name)]
    (when (contains? object-ids resolved)
      resolved)))

(defn- object-dependencies
  [obj-data variable-mappings object-ids]
  (->> (arg-ref-names obj-data)
       (keep #(resolve-object-id % variable-mappings object-ids))
       set))

(defn- next-ready-object-id
  [remaining deps objects]
  (->> remaining
       (filter #(empty? (get deps %)))
       (sort-by #(or (:index (get objects %)) 0))
       first))

(defn- topo-sort-object-ids
  [objects variable-mappings]
  (let [object-ids (set (keys objects))
        initial-deps (into {} (map (fn [[id data]]
                                     [id (object-dependencies data variable-mappings object-ids)])
                                   objects))]
    (loop [remaining object-ids
           deps initial-deps
           ordered []]
      (if (empty? remaining)
        ordered
        (if-let [id (next-ready-object-id remaining deps objects)]
          (recur (disj remaining id)
                 (into {} (map (fn [[k v]] [k (disj v id)]) deps))
                 (conj ordered id))
          (throw (ex-info "Circular object references in construction"
                          {:remaining remaining :deps deps})))))))

(defn- objects-in-construction-order
  [objects variable-mappings]
  (map (fn [id] [id (get objects id)])
       (topo-sort-object-ids objects variable-mappings)))

(defn generate-factory-body
  "Generate the body of a factory function from construction IR"
  [construction-ir schema-ir]
  (let [objects (objects-in-construction-order (:objects construction-ir)
                                               (:variable-mappings construction-ir))
        variable-mappings (:variable-mappings construction-ir)
        return-object (:return-object construction-ir)
        assignment-statements
        (for [[obj-id obj-data] objects]
          (generate-assignment-statement obj-id obj-data schema-ir variable-mappings))
        subscribe-statements (generate-subscribe-statements objects schema-ir)
        final-statement (str "  return " return-object ";")]
    (str/join "\n" (concat assignment-statements subscribe-statements [final-statement]))))

(defn generate-construction-factory
  "Generate Haxe factory function from construction IR"
  [construction-ir schema-ir]
  (let [root-class (:root-class construction-ir)
        factory-name (:factory-name construction-ir)
        body (generate-factory-body construction-ir schema-ir)]
    (str "public static function " factory-name "(): " root-class " {\n"
         body "\n"
         "}"))) 
