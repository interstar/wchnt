(ns wchnt-lang.ast-to-ir
  "Transform WCHNT AST to Intermediate Representation"
  (:require [wchnt-lang.ir :as ir]
            [wchnt-lang.parser :as parser]
            [wchnt-lang.ast-utils :as ast-utils]
            [com.rpl.specter :as s]
            [clojure.string :as str]))

(declare flatten-nested-constructions)

(declare type-from-class-and-position walk visit-node walk-with-context 
         establish-context! establish-object-construction-context 
         establish-inner-object-construction-context establish-array-construction-context
         flatten-with-context flatten-inner-object-construction-with-context 
         flatten-array-construction-with-context)

;; =============================================================================
;; Schema AST to IR Transformation
;; =============================================================================

(defn process-element-to-component
  "Transform a parsed element to an IR component"
  [element]
  (let [sigil (:sigil element)
        type-name (:type element)
        optional-name (:optional-name element)
        name (:name element)  ; Use the name from process-element
        relationship (case sigil
                       ":" :context-specific
                       "@" :external
                       "$" :reactive
                       nil :ordinary
                       :ordinary)
        component-name (if optional-name
                        optional-name
                        (or name  ; Use the name from process-element if available
                            (str (str/lower-case (first type-name)) (subs type-name 1))))]
    {:component-name component-name
     :type-name type-name
     :relationship relationship
     :optional-name optional-name}))

(defn build-context-relationships
  "Build context relationship mappings from schema AST"
  [schema-ast]
  (let [context-map (atom {})]
    (letfn [(walk-for-contexts [node parent-class]
              (when (vector? node)
                (let [[tag & children] node]
                  (case tag
                    :Schema 
                    (doseq [child (filter vector? children)]
                      (walk-for-contexts child parent-class))
                    :CompositionLine
                    (let [definee-node (first (filter #(= (first %) :Definee) children))
                          class-name (second definee-node)
                          element-nodes (filter #(= (first %) :Element) children)
                          processed-elements (map parser/process-element element-nodes)
                          context-elements (filter #(= (:sigil %) ":") processed-elements)]
                      ;; Record context relationships: context-specific components get this class as their context
                      (doseq [element context-elements]
                        (swap! context-map assoc (:type element) class-name))
                      ;; Initialize this class to nil only if it doesn't already have a context
                      (when (nil? (get @context-map class-name))
                        (swap! context-map assoc class-name nil))
                      ;; Recursively check nested contexts
                      (doseq [child (filter vector? children)]
                        (walk-for-contexts child class-name)))
                    :DisjunctionLine
                    (let [definee-node (first (filter #(= (first %) :Definee) children))
                          interface-name (second definee-node)]
                      (doseq [child (filter vector? children)]
                        (walk-for-contexts child parent-class)))
                    :EnumLine
                    ;; Enums don't have contexts
                    nil
                    ;; Recursively process other nodes
                    (doseq [child (filter vector? children)]
                      (walk-for-contexts child parent-class))))))]
      (walk-for-contexts schema-ast nil)
      @context-map)))

(defn build-interface-implementers
  "Build interface implementation mappings from schema AST"
  [schema-ast]
  (let [disjunction-nodes (parser/find-all-nodes :DisjunctionLine schema-ast)]
    (reduce (fn [acc disjunction-node]
              (let [[_ & children] disjunction-node
                    definee-node (first (filter #(= (first %) :Definee) children))
                    interface-name (second definee-node)
                    element-nodes (filter #(= (first %) :Element) children)
                    type-names (for [element element-nodes
                                     :let [type-marker (first (filter #(and (vector? %) (= (first %) :TypeMarker)) element))]
                                     :when type-marker]
                                 (second type-marker))]
                ;; Track which classes implement this interface
                (reduce (fn [current-acc implementer]
                          (update current-acc interface-name (fnil conj #{}) implementer))
                        acc type-names)))
            {} disjunction-nodes)))

(defn build-observable-and-subscriber-classes
  "Build observable and subscriber class lists from schema AST"
  [schema-ast]
  (let [observable-classes (atom #{})
        subscriber-classes (atom #{})]
    (letfn [(walk-for-reactive [node parent-class]
              (when (vector? node)
                (let [[tag & children] node]
                  (case tag
                    :Schema 
                    (doseq [child (filter vector? children)]
                      (walk-for-reactive child parent-class))
                    :CompositionLine
                    (let [definee-node (first (filter #(= (first %) :Definee) children))
                          class-name (second definee-node)
                          element-nodes (filter #(= (first %) :Element) children)
                          processed-elements (map parser/process-element element-nodes)
                          reactive-elements (filter #(= (:sigil %) "$") processed-elements)]
                      ;; Record reactive relationships
                      (doseq [element reactive-elements]
                        (swap! observable-classes conj (:type element))
                        (swap! subscriber-classes conj class-name))
                      ;; Recursively check nested contexts
                      (doseq [child (filter vector? children)]
                        (walk-for-reactive child class-name)))
                    :DisjunctionLine
                    (doseq [child (filter vector? children)]
                      (walk-for-reactive child parent-class))
                    :EnumLine
                    nil
                    ;; Recursively process other nodes
                    (doseq [child (filter vector? children)]
                      (walk-for-reactive child parent-class))))))]
      (walk-for-reactive schema-ast nil)
      {:observable-classes (vec @observable-classes)
       :subscriber-classes (vec @subscriber-classes)})))

(defn transform-composition-line
  "Transform a composition line to an assemblage"
  [composition-line]
  (let [[_ & children] composition-line
        definee-node (first (filter #(= (first %) :Definee) children))
        class-name (second definee-node)
        element-nodes (filter #(= (first %) :Element) children)
        processed-elements (map parser/process-element element-nodes)
        components (map process-element-to-component processed-elements)]
    {:name class-name
     :components components
     :context-dependencies []
     :context-providers []
     :observable nil}))

(defn transform-disjunction-line
  "Transform a disjunction line to an interface"
  [disjunction-line]
  (let [[_ & children] disjunction-line
        definee-node (first (filter #(= (first %) :Definee) children))
        interface-name (second definee-node)
        element-nodes (filter #(= (first %) :Element) children)
        type-names (for [element element-nodes
                         :let [type-marker (first (filter #(and (vector? %) (= (first %) :TypeMarker)) element))]
                         :when type-marker]
                     (second type-marker))]
    {:name interface-name
     :implementers (vec type-names)}))

(defn transform-enum-line
  "Transform an enum line to an enum"
  [enum-line]
  (let [[_ & children] enum-line
        definee-node (first (filter #(= (first %) :Definee) children))
        enum-name (second definee-node)
        enum-value-nodes (filter #(= (first %) :EnumValue) children)
        enum-values (map second enum-value-nodes)]
    {:name enum-name
     :values (vec enum-values)}))

(defn create-debug-methods
  "Create debug method specifications for all assemblages"
  [assemblages]
  (for [assemblage assemblages]
    {:class (:name assemblage)
     :method "toConstruction"
     :depth-parameter true
     :format :hiccup}))

(defn schema-ast-to-ir
  "Transform schema AST to IR schema"
  [schema-ast]
  (let [composition-lines (parser/find-all-nodes :CompositionLine schema-ast)
        disjunction-lines (parser/find-all-nodes :DisjunctionLine schema-ast)
        enum-lines (parser/find-all-nodes :EnumLine schema-ast)
        
        assemblages (map transform-composition-line composition-lines)
        interfaces (map transform-disjunction-line disjunction-lines)
        enums (map transform-enum-line enum-lines)
        
        context-relationships (build-context-relationships schema-ast)
        interface-implementers (build-interface-implementers schema-ast)
        {:keys [observable-classes subscriber-classes]} (build-observable-and-subscriber-classes schema-ast)
        debug-methods (create-debug-methods assemblages)]
    
    (ir/create-schema-ir assemblages interfaces enums context-relationships 
                         interface-implementers observable-classes subscriber-classes debug-methods)))

;; =============================================================================
;; Construction AST to IR Transformation (Placeholder)
;; =============================================================================



(defn extract-args-from-object-construction
  "Extract arguments from a single ObjectConstruction node"
  [object-construction schema-ir root-class-name]
  (letfn [(lookup-expected-class-name [arg-index]
            "Look up the expected class name for an argument at the given index"
            (let [assemblage (first (filter #(= (:name %) root-class-name) (:assemblages schema-ir)))
                  components (:components assemblage)]
              (if (and assemblage components (< arg-index (count components)))
                (:type-name (nth components arg-index))
                (throw (ex-info "Could not determine expected class name for argument"
                              {:arg-index arg-index
                               :root-class-name root-class-name
                               :schema-ir schema-ir})))))
          
          (extract-arg-item [arg-item arg-index]
            (cond
              ;; VariableRef - check if it's actually an enum value reference
              (ast-utils/node-type? arg-item :VariableRef)
              (let [var-name (second arg-item)
                    ;; Check if this variable name matches any enum value in the schema
                    enum-values (mapcat :values (:enums schema-ir))
                    is-enum-value (some #(= var-name %) enum-values)]
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
                   :index arg-index}))
              
              ;; InnerObjectConstruction
              (ast-utils/node-type? arg-item :InnerObjectConstruction)
              (let [second-element (second arg-item)]
                (if (ast-utils/node-type? second-element :ClassName)
                  ;; Class name is explicit
                  {:type :object
                   :class-name (second second-element)
                   :args [arg-item]
                   :index arg-index}
                  ;; Class name is omitted, need to look it up
                  (let [expected-class-name (lookup-expected-class-name arg-index)]
                    {:type :object
                     :class-name expected-class-name
                     :args [(assoc arg-item 1 [:ClassName expected-class-name])]
                     :index arg-index})))
              
              ;; ArrayConstruction - treat as object (will be flattened later)
              (ast-utils/node-type? arg-item :ArrayConstruction)
              {:type :array
               :class-name (second (second arg-item))
               :args [arg-item]
               :index arg-index}
              
              ;; IntLiteral
              (ast-utils/node-type? arg-item :IntLiteral)
              {:type :primitive
               :class-name "Int"
               :value (Integer/parseInt (second arg-item))
               :args []
               :index arg-index}
              
              ;; StringLiteral
              (ast-utils/node-type? arg-item :StringLiteral)
              {:type :primitive
               :class-name "String"
               :value (second arg-item)
               :args []
               :index arg-index}
              
              ;; Default to primitive
              :else {:type :primitive
                     :class-name "Unknown"
                     :value arg-item
                     :args []
                     :index arg-index}))
          
          (extract-arg-list [arg-list]
            (if (ast-utils/node-type? arg-list :ArgList)
              (map-indexed (fn [arg-index arg-item]
                            (extract-arg-item arg-item arg-index))
                          (rest arg-list))
              []))]
    
    (if (ast-utils/node-type? object-construction :ObjectConstruction)
      (let [arg-list (nth object-construction 2)]
        (extract-arg-list arg-list))
      [])))













(defn extract-args-from-ast
  "Extract arguments from parsed construction AST, processing assignments and final construction"
  [construction-ast]
  (letfn [(extract-arg-item [arg-item]
            (cond
              ;; InnerObjectConstruction
              (ast-utils/node-type? arg-item :InnerObjectConstruction)
              (let [class-name-node (second arg-item)
                    class-name (if (ast-utils/node-type? class-name-node :ClassName)
                                (second class-name-node)
                                (if (string? class-name-node)
                                  class-name-node
                                  (str class-name-node)))
                    arg-list (nth arg-item 2)
                    nested-args (extract-arg-list arg-list)]
                [:object class-name nested-args])
              
              ;; VariableRef
              (ast-utils/node-type? arg-item :VariableRef)
              [:variable (second arg-item)]
              
              ;; IntLiteral
              (ast-utils/node-type? arg-item :IntLiteral)
              [:primitive (Integer/parseInt (second arg-item))]
              
              ;; StringLiteral (if it exists)
              (ast-utils/node-type? arg-item :StringLiteral)
              [:primitive arg-item]
              
              ;; Default to primitive
              :else [:primitive arg-item]))
          
          (extract-arg-list [arg-list]
            (if (ast-utils/node-type? arg-list :ArgList)
              (map (fn [arg-item]
                     (extract-arg-item arg-item))
                   (rest arg-list))
              []))
          
          (process-assignment [assignment]
            (let [[_ var-name-node expression] assignment
                  var-name (second var-name-node)
                  actual-object (second expression)  ;; Get the object inside [:Expression obj]
                  ;; For now, just return the variable name and a placeholder
                  ;; We'll need to process the actual object construction later
                  ]
              {:var-name var-name :object actual-object}))
          
          (process-final-construction [statements]
            (let [final-statements (filter #(and (vector? %) (= (first %) :Expression)) statements)
                  final-construction (if (seq final-statements)
                                      (second (last final-statements))  ;; Get the expression from [:Expression expr]
                                      nil)]
              (if final-construction
                (let [obj-construction (ast-utils/find-first-node-by-type final-construction :ObjectConstruction)
          arg-list (nth obj-construction 2)]
      (if arg-list
        (extract-arg-list arg-list)
                    []))
                [])))]
    
    ;; Process the entire construction block
    (let [statements (rest construction-ast)  ;; Skip :BlockStatements tag
          ;; Step 1: Process assignments
          assignments (filter #(and (vector? %) (= (first %) :Assignment)) statements)
          assignment-results (map process-assignment assignments)
          ;; Step 2: Process final construction
          final-args (process-final-construction statements)]
      
      ;; For now, return the final construction args
      ;; TODO: We need to integrate the assignment processing with the final construction
      final-args)))

(defn process-variable-ref-expression
  "Process a VariableRef expression - resolve to the actual nested object if available"
  [inner-expression nested-objects]
  (let [var-name (second inner-expression)
        nested-obj (get nested-objects var-name)]
    (if nested-obj
      ;; Return the actual nested object (without the :ast field which is just for debugging)
      (dissoc nested-obj :ast)
      ;; Fallback to variable reference (this maintains backward compatibility)
      {:type :variable
       :class-name "VariableRef"
       :args [var-name]})))

(defn process-array-construction-expression
  "Process an ArrayConstruction expression"
  [inner-expression]
  (let [type-node (second inner-expression)
        array-type (if (ast-utils/node-type? type-node :Type)
                    (second type-node)
                    (throw (ex-info "Array construction missing type node" 
                                  {:inner-expression inner-expression})))
        arg-list (nth inner-expression 2)
        array-elements (if (ast-utils/node-type? arg-list :ArgList)
                       (rest arg-list)
                       [])]
    {:type :array
     :class-name array-type
     :args array-elements}))

(defn process-object-construction-expression
  "Process an ObjectConstruction expression"
  [inner-expression]
  (let [class-name-node (second inner-expression)
        class-name (if (ast-utils/node-type? class-name-node :ClassName)
                    (second class-name-node)
                    (throw (ex-info "Object construction missing class name node" 
                                  {:inner-expression inner-expression})))
        arg-list (nth inner-expression 2)
        args (if (ast-utils/node-type? arg-list :ArgList)
              (rest arg-list)
              [])]
    {:type :object
     :class-name class-name
     :args args}))

(defn process-map-construction-expression
  "Process a MapConstruction expression"
  [inner-expression]
  (let [key-type-node (second inner-expression)
        key-type (if (ast-utils/node-type? key-type-node :KeyType)
                  (second key-type-node)
                  (throw (ex-info "Map construction missing key type node" 
                                {:inner-expression inner-expression})))
        val-type-node (nth inner-expression 2)
        val-type (if (ast-utils/node-type? val-type-node :ValType)
                  (second val-type-node)
                  (throw (ex-info "Map construction missing value type node" 
                                {:inner-expression inner-expression})))
        key-value-list (nth inner-expression 3)
        structured-args (if (ast-utils/node-type? key-value-list :KeyValueList)
                        (map-indexed (fn [index key-value-pair]
                                      (if (ast-utils/node-type? key-value-pair :KeyValuePair)
                                        (let [key-expr (second key-value-pair)
                                              val-expr (nth key-value-pair 2)]
                                          ;; Convert key and value to structured ConstructionArg objects
                                          (let [key-arg (cond
                                                         (ast-utils/node-type? key-expr :StringLiteral)
                                                         {:type :primitive :class-name "String" :value (second key-expr) :args [] :index (* index 2)}
                                                         (ast-utils/node-type? key-expr :VariableRef)
                                                         {:type :variable :class-name "VariableRef" :value (second key-expr) :args [] :index (* index 2)}
                                                         (and (ast-utils/node-type? key-expr :Expression) (ast-utils/node-type? (second key-expr) :StringLiteral))
                                                         {:type :primitive :class-name "String" :value (second (second key-expr)) :args [] :index (* index 2)}
                                                         (and (ast-utils/node-type? key-expr :Expression) (ast-utils/node-type? (second key-expr) :VariableRef))
                                                         {:type :variable :class-name "VariableRef" :value (second (second key-expr)) :args [] :index (* index 2)}
                                                         :else
                                                         (throw (ex-info "Unsupported key type in map construction" {:key-expr key-expr})))
                                                val-arg (cond
                                                         (ast-utils/node-type? val-expr :StringLiteral)
                                                         {:type :primitive :class-name "String" :value (second val-expr) :args [] :index (+ (* index 2) 1)}
                                                         (ast-utils/node-type? val-expr :IntLiteral)
                                                         {:type :primitive :class-name "Int" :value (second val-expr) :args [] :index (+ (* index 2) 1)}
                                                         (ast-utils/node-type? val-expr :VariableRef)
                                                         {:type :variable :class-name "VariableRef" :value (second val-expr) :args [] :index (+ (* index 2) 1)}
                                                         (and (ast-utils/node-type? val-expr :Expression) (ast-utils/node-type? (second val-expr) :StringLiteral))
                                                         {:type :primitive :class-name "String" :value (second (second val-expr)) :args [] :index (+ (* index 2) 1)}
                                                         (and (ast-utils/node-type? val-expr :Expression) (ast-utils/node-type? (second val-expr) :IntLiteral))
                                                         {:type :primitive :class-name "Int" :value (second (second val-expr)) :args [] :index (+ (* index 2) 1)}
                                                         (and (ast-utils/node-type? val-expr :Expression) (ast-utils/node-type? (second val-expr) :VariableRef))
                                                         {:type :variable :class-name "VariableRef" :value (second (second val-expr)) :args [] :index (+ (* index 2) 1)}
                                                         :else
                                                         (throw (ex-info "Unsupported value type in map construction" {:val-expr val-expr})))]
                                            [key-arg val-arg]))
                                        (throw (ex-info "Invalid key-value pair in map construction" {:key-value-pair key-value-pair}))))
                                    (rest key-value-list))
                        [])]
    {:type :map
     :class-name (str "Map<" key-type ", " val-type ">")
     :args (flatten structured-args)}))

(defn process-assignment-expression
  "Process an assignment expression to extract object information"
  [expression nested-objects]
  (let [inner-expression (second expression)]  ;; Get the expression inside [:Expression ...]
    (cond
      (ast-utils/node-type? inner-expression :VariableRef)
      (process-variable-ref-expression inner-expression nested-objects)
      
      (ast-utils/node-type? inner-expression :ArrayConstruction)
      (process-array-construction-expression inner-expression)
      
      (ast-utils/node-type? inner-expression :ObjectConstruction)
      (process-object-construction-expression inner-expression)
      
      (ast-utils/node-type? inner-expression :MapConstruction)
      (process-map-construction-expression inner-expression)
      
      :else
      (throw (ex-info "Unknown expression type in assignment" 
                     {:inner-expression inner-expression})))))

(defn extract-root-class-from-construction
  "Extract the root class name from construction AST using schema IR"
  [construction-ast schema-ir]
  (let [class-names (set (map :name (:assemblages schema-ir)))
        ;; Find all ObjectConstruction nodes in the AST (including nested ones)
        obj-constructions (ast-utils/find-nodes-by-type construction-ast :ObjectConstruction)]
    (if (seq obj-constructions)
      ;; Take the last ObjectConstruction (the final construction in multi-statement blocks)
      (let [obj-construction (last obj-constructions)
            class-name-node (second obj-construction)]
        (if (ast-utils/node-type? class-name-node :ClassName)
          (let [class-name (second class-name-node)]
            (if (contains? class-names class-name)
              class-name
              (throw (ex-info (str "Class name '" class-name "' not found in schema") 
                             {:class-name class-name 
                              :available-classes class-names}))))
          (throw (ex-info "ObjectConstruction node does not contain ClassName" 
                         {:obj-construction obj-construction}))))
      (throw (ex-info "Could not find root class in construction AST" 
                     {:construction-ast construction-ast})))))

(defn process-nested-arg
  "Process a single nested argument into IR format"
  [nested-index nested-arg]
  (if (vector? nested-arg)
    (let [[nested-type nested-value] nested-arg]
      {:type nested-type
       :class-name (name nested-type)
       :args [nested-value]
       :index nested-index})
    (throw (ex-info "Cannot process non-vector nested argument" 
                   {:nested-arg nested-arg
                    :index nested-index}))))

(defn process-object-arg
  "Process an object argument into IR format"
  [arg index]
  (let [[_ class-name nested-args] arg]
    {:type :object
     :class-name class-name
     :args (map-indexed process-nested-arg nested-args)
     :index index}))

(defn process-primitive-arg
  "Process a primitive argument into IR format"
  [arg index]
  (let [primitive-node (second arg)]
    (cond
      (ast-utils/node-type? primitive-node :IntLiteral)
      {:type :primitive
       :class-name "Int"
       :args [(second primitive-node)]
       :index index}
      (ast-utils/node-type? primitive-node :StringLiteral)
      {:type :primitive
       :class-name "String"
       :args [(second primitive-node)]
       :index index}
      (ast-utils/node-type? primitive-node :FloatLiteral)
      {:type :primitive
       :class-name "Float"
       :args [(second primitive-node)]
       :index index}
      (ast-utils/node-type? primitive-node :BooleanLiteral)
  {:type :primitive
       :class-name "Boolean"
       :args [(second primitive-node)]
       :index index}
      :else (throw (ex-info "Unknown primitive type in AST" 
                          {:primitive-node primitive-node
                           :arg arg
                           :index index})))))

(defn process-variable-arg
  "Process a variable argument into IR format"
  [arg index]
  (throw (ex-info "Variable arguments should be resolved to actual objects" 
                 {:arg arg
                  :index index})))

(defn process-enum-value-arg
  "Process an enum value argument into IR format"
  [arg index]
  {:type :enum-value
   :class-name "Enum"
   :args [(second arg)]
   :index index})

(defn process-array-arg
  "Process an array argument into IR format"
  [arg index]
  (let [type-name (second arg)
        array-elements (nth arg 2)]
    {:type :array
     :class-name "Array"
     :args array-elements
     :index index}))

(defn process-construction-arg
  "Process a single construction argument into IR format"
  [index arg]
  (if (vector? arg)
    (let [type (first arg)]
      (case type
        :object (process-object-arg arg index)
        :variable (process-variable-arg arg index)
        :primitive (process-primitive-arg arg index)
        :array (process-array-arg arg index)
        :enum-value (process-enum-value-arg arg index)))
    ;; Direct primitive value (like a number)
    (throw (ex-info "Direct primitive values should be handled by specific type handlers" 
                   {:arg arg
                    :index index}))))

(defn process-assignments
  "Process assignment statements to build object table and variable mappings"
  [statements nested-objects]
  (let [assignments (filter #(and (vector? %) (= (first %) :Assignment)) statements)]
    (reduce (fn [[objects mappings counter] assignment]
              (let [[_ var-name-node expression] assignment
                    var-name (if (ast-utils/node-type? var-name-node :VariableName)
                              (second var-name-node)
                              (str var-name-node))
                    obj-id (str "obj" (inc counter))
                    processed-expression (process-assignment-expression expression nested-objects)]
                [(assoc objects obj-id (assoc processed-expression :index counter))
                 (assoc mappings var-name obj-id)
                 (inc counter)]))
            [{} {} 0]
            assignments)))

(defn find-final-construction
  "Find the final ObjectConstruction node in the statements"
  [statements]
  (let [direct-construction (first (filter #(and (vector? %) (= (first %) :ObjectConstruction)) statements))]
    (if direct-construction
      direct-construction
      ;; Look for ObjectConstruction inside Expression nodes
      (let [expression-statements (filter #(and (vector? %) (= (first %) :Expression)) statements)
            object-constructions (mapcat #(ast-utils/find-nodes-by-type % :ObjectConstruction) expression-statements)]
        (first object-constructions)))))

(defn type-from-class-and-position
  "Get the expected type for a specific position in a class's constructor arguments"
  [class-name position schema-ir]
  (let [assemblage (first (filter #(= (:name %) class-name) (:assemblages schema-ir)))
        components (:components assemblage)]
    (if (and assemblage components (< position (count components)))
      (:type-name (nth components position))
      (throw (ex-info "Could not determine expected type for class and position"
                    {:class-name class-name
                     :position position
                     :available-classes (map :name (:assemblages schema-ir))
                     :schema-ir schema-ir})))))

(defn process-variable-final-arg
  "Process a variable argument in final construction - resolve to the actual object being referenced"
  [var-name assignment-objects variable-mappings index]
  (let [mapped-obj-id (get variable-mappings var-name)
        assignment-obj (get assignment-objects mapped-obj-id)]


    (if assignment-obj
      ;; Copy the referenced object but with a new index
      (assoc assignment-obj :index index)
      (throw (ex-info "Variable not found in assignments" 
                    {:var-name var-name
                     :variable-mappings variable-mappings
                     :assignment-objects (keys assignment-objects)
                     :index index})))))

(defn process-object-construction-final-arg
  "Process an ObjectConstruction in final arguments"
  [object-construction index]
  (let [class-name-node (second object-construction)
        class-name (if (ast-utils/node-type? class-name-node :ClassName)
                    (second class-name-node)
                    (throw (ex-info "Object construction missing class name" 
                                  {:object-construction object-construction
                                   :index index})))]
    {:type :object
     :class-name class-name
     :args []  ;; Will be processed elsewhere
     :index index}))

(defn process-array-construction-final-arg
  "Process an ArrayConstruction in final arguments"
  [object-construction index]
  (let [type-node (second object-construction)
        type-name (if (ast-utils/node-type? type-node :Type)
                   (second type-node)
                   (throw (ex-info "Array construction missing type" 
                                 {:array-construction object-construction
                                  :index index})))]
    {:type :array
     :class-name "Array"
     :args [object-construction]
     :index index}))

(defn process-inner-object-construction-final-arg
  "Process an InnerObjectConstruction in final arguments"
  [object-construction schema-ir root-class-name index]
  (let [class-name-node (second object-construction)
        class-name (if (ast-utils/node-type? class-name-node :ClassName)
                    (second class-name-node)
                    ;; Look up expected type from schema
                    (type-from-class-and-position root-class-name index schema-ir))]
    {:type :object
     :class-name class-name
     :args [object-construction]
     :index index}))

(defn process-primitive-final-arg
  "Process a primitive argument in final construction"
  [primitive-node arg index]
  (cond
    (ast-utils/node-type? primitive-node :IntLiteral)
    {:type :primitive
     :class-name "Int"
     :args [(second primitive-node)]
     :index index}
    (ast-utils/node-type? primitive-node :StringLiteral)
    {:type :primitive
     :class-name "String"
     :args [(second primitive-node)]
     :index index}
    (ast-utils/node-type? primitive-node :FloatLiteral)
    {:type :primitive
     :class-name "Float"
     :args [(second primitive-node)]
     :index index}
    (ast-utils/node-type? primitive-node :BooleanLiteral)
    {:type :primitive
     :class-name "Boolean"
     :args [(second primitive-node)]
     :index index}
    :else
    (throw (ex-info "Unknown primitive type in AST" 
                  {:primitive-node primitive-node
                   :arg arg
                   :index index}))))

(defn process-object-final-arg
  "Process an object argument in final construction"
  [object-construction schema-ir root-class-name index]
  (cond
    (ast-utils/node-type? object-construction :ObjectConstruction)
    (process-object-construction-final-arg object-construction index)
    
    (ast-utils/node-type? object-construction :ArrayConstruction)
    (process-array-construction-final-arg object-construction index)
    
    (ast-utils/node-type? object-construction :InnerObjectConstruction)
    (process-inner-object-construction-final-arg object-construction schema-ir root-class-name index)
    
    :else
    (throw (ex-info "Invalid object construction in final arguments" 
                  {:object-construction object-construction
                   :index index}))))

(defn create-variable-final-object
  "Create a final object for a variable reference"
  [var-name assignment-objects variable-mappings index]
  (let [existing-obj-id (get variable-mappings var-name)]
    [existing-obj-id
     (process-variable-final-arg var-name assignment-objects variable-mappings index)]))

(defn create-array-final-object
  "Create a final object for an array argument"
  [arg index assignment-objects]
  (let [obj-id (str "obj" (+ (count assignment-objects) index 1))]
    [obj-id (process-array-arg arg index)]))

(defn create-object-final-object
  "Create a final object for an object argument"
  [arg index assignment-objects schema-ir root-class-name]
  (let [obj-id (str "obj" (+ (count assignment-objects) index 1))]
    [obj-id (process-object-final-arg (second arg) schema-ir root-class-name index)]))

(defn create-enum-final-object
  "Create a final object for an enum value argument"
  [arg index assignment-objects]
  (let [obj-id (str "obj" (+ (count assignment-objects) index 1))]
    [obj-id {:type :enum-value
             :class-name "EnumValue"
             :args [(second arg)]
             :index index}]))

(defn create-primitive-final-object
  "Create a final object for a primitive argument"
  [arg index assignment-objects]
  (let [obj-id (str "obj" (+ (count assignment-objects) index 1))]
    [obj-id (process-primitive-final-arg (second arg) arg index)]))

(defn create-final-object
  "Create a final object from an argument, using assignment objects and variable mappings for class names"
  [index arg assignment-objects variable-mappings schema-ir root-class-name]
  (if (vector? arg)
    (let [type (first arg)]
      (case type
        :variable
        (create-variable-final-object (second arg) assignment-objects variable-mappings index)
        
        :array
        (create-array-final-object arg index assignment-objects)
        
        :object
        (create-object-final-object arg index assignment-objects schema-ir root-class-name)
        
        :enum-value
        (create-enum-final-object arg index assignment-objects)
        
        :primitive
        (create-primitive-final-object arg index assignment-objects)
        
        (throw (ex-info "Unknown argument type in final construction" 
                       {:arg arg
                        :type type
                        :index index}))))
    (throw (ex-info "Non-vector argument in final construction" 
                   {:arg arg
                    :index index}))))

(defn process-final-construction
  "Process the final construction to create final objects"
  [final-construction assignment-objects variable-mappings schema-ir root-class-name]
  (if final-construction
    (let [final-args (extract-args-from-object-construction final-construction schema-ir root-class-name)
          ;; Create the final root object with structured arguments
          root-obj-id (str "obj" (+ (count assignment-objects) 1))
          root-obj {:type :object
                   :class-name root-class-name
                   :args final-args  ;; Use the structured arguments directly
                   :index (count assignment-objects)}]
      ;; Return the root object with structured arguments
      {root-obj-id root-obj})
    {}))

(defn merge-variable-mappings
  "Create and merge variable mappings for nested objects"
  [variable-mappings nested-objects]
  (let [nested-variable-mappings (into {} 
                                      (for [[obj-id obj-data] nested-objects]
                                        [obj-id obj-id]))] ; Map variable ref to object id
    (merge variable-mappings nested-variable-mappings)))

(defn debug-print-construction-state
  "Print debug information about construction processing"
  [root-class statements assignment-objects nested-objects all-assignment-objects 
   final-construction final-objects all-objects]
  )

(defn construction-ast-to-ir
  "Transform construction AST to IR construction"
  [construction-ast schema-ir]
  (let [root-class (extract-root-class-from-construction construction-ast schema-ir)
        factory-name (str (str/lower-case (first root-class)) (subs root-class 1) "Factory")
        statements (rest construction-ast)  ;; Skip :BlockStatements tag
        
        ;; Flatten nested object constructions
        {:keys [flattened-ast nested-objects]} (flatten-nested-constructions construction-ast schema-ir)
        ;; Debug output for troubleshooting

        
        ;; Process assignments from flattened AST
        [assignment-objects variable-mappings] (process-assignments (rest flattened-ast) nested-objects)
        
        ;; Combine all variable mappings and objects
        all-variable-mappings (merge-variable-mappings variable-mappings nested-objects)
        all-assignment-objects (merge assignment-objects nested-objects)
        
        ;; Process final construction from flattened AST
        final-construction (find-final-construction (rest flattened-ast))
        final-objects (process-final-construction final-construction all-assignment-objects all-variable-mappings schema-ir root-class)
        
        ;; Combine all objects
        all-objects (merge all-assignment-objects final-objects)
        
        ;; Determine the return object ID - it should be the final root object
        return-obj-id (if (empty? final-objects)
                       "obj1"  ;; Fallback if no final objects created
                       (first (keys final-objects)))]
    
    ;; Debug output for troubleshooting
    (debug-print-construction-state root-class statements assignment-objects nested-objects 
                                   all-assignment-objects final-construction final-objects all-objects)
    
    (ir/create-construction-ir root-class factory-name all-objects [] [] [] [] all-variable-mappings return-obj-id)))



;; =============================================================================
;; Method AST to IR Transformation (Placeholder)
;; =============================================================================

(defn method-ast-to-ir
  "Transform method AST to IR methods (placeholder for Phase 3)"
  [method-ast schema-ir]
  ;; This will be implemented in Phase 3
  [])

;; =============================================================================
;; Main AST to IR Transformation
;; =============================================================================

(defn ast-to-ir
  "Transform complete WCHNT AST to IR"
  [schema-ast construction-ast method-ast]
  (let [schema-ir (schema-ast-to-ir schema-ast)
        construction-ir (construction-ast-to-ir construction-ast schema-ir)
        methods-ir (method-ast-to-ir method-ast schema-ir)]
    (ir/create-ir schema-ir construction-ir methods-ir))) 

;; =============================================================================
;; Refactored Flattening Functions (New Architecture)
;; =============================================================================

(defn get-explicit-class-name
  "Extract explicit class name from AST node"
  [node]
  (when (and (vector? node) 
             (>= (count node) 2)
             (vector? (second node))
             (= :ClassName (first (second node))))
    (second (second node))))


(defn record-nested-object!
  "Record a nested object and return a VariableRef to it"
  [nested-objects-atom object-counter-atom entry]
  (let [obj-id (str "obj" (swap! object-counter-atom inc))
        entry-with-index (assoc entry :index (count @nested-objects-atom))]
    (swap! nested-objects-atom assoc obj-id entry-with-index)
    [:VariableRef obj-id]))

(defn visit-node
  "Process a single AST node with context and transformed children"
  [node ctx nested-objects-atom object-counter-atom schema-ir]
  (let [[tag & children] node]
    (cond
      ;; --- Case 1: Nodes that should be flattened ---
      (= tag :InnerObjectConstruction)
      (let [class-name (or (get-explicit-class-name node)
                           ;; If no explicit class name, try to get it from context
                           (if (:array-element-type ctx)
                             (:array-element-type ctx)  ; Use array element type if we're inside an array
                             (:parent-class ctx)))  ; Use parent class directly instead of going deeper
            arg-list (if (get-explicit-class-name node)
                       (nth node 2)  ; [:ClassName "X"] [:ArgList ...]
                       (second node))] ; [:ArgList ...]

        (when-not class-name
          (throw (ex-info "Could not determine class name for inner construction" {:ast node :ctx ctx})))
        ;; Process arguments into structured IR format
        (let [structured-args (extract-args-from-object-construction 
                               [:ObjectConstruction [:ClassName class-name] arg-list] 
                               schema-ir 
                               class-name)]
          (record-nested-object! nested-objects-atom object-counter-atom 
                                {:type :object, :class-name class-name, :args structured-args, :ast node})))

      (= tag :ArrayConstruction)
      (let [element-type (second (second node))  ; from [:Type "Person"]
            arg-list (nth node 2)]               ; from [:ArgList ...]

        ;; Process array elements as object constructions of the element type
        (let [structured-args (extract-args-from-object-construction 
                               [:ObjectConstruction [:ClassName element-type] arg-list] 
                               schema-ir 
                               element-type)]
          (record-nested-object! nested-objects-atom object-counter-atom
                                {:type :array, :class-name element-type, :args structured-args, :ast node})))

      (= tag :MapConstruction)
      (let [map-ir (process-map-construction-expression node)]
        (record-nested-object! nested-objects-atom object-counter-atom map-ir))

      ;; --- Case 2: Structural nodes that are just rebuilt ---
      (#{:ObjectConstruction :ArgList :ClassName :Type :BlockStatements :Expression} tag)
      (into [] (cons tag children))

      ;; --- Case 3: Leaf nodes ---
      :else node)))

(defn walk-ast
  "Walk AST tree with context, processing children before parents"
  [node ctx nested-objects-atom object-counter-atom schema-ir]
  (if (vector? node)
    (let [[tag & children] node
          new-ctx (case tag
                    :ObjectConstruction
                    (assoc ctx :parent-class (get-explicit-class-name node))
                    :ArrayConstruction
                    (assoc ctx :array-element-type (second (second node)))  ; Set array element type
                    :InnerObjectConstruction
                    (assoc ctx :parent-class (or (get-explicit-class-name node)
                                                (:array-element-type ctx)
                                                (type-from-class-and-position (:parent-class ctx) (:arg-index ctx) schema-ir)))
                    ctx)
          transformed-children (map-indexed
                                (fn [i child] 
                                  (walk-ast child (assoc new-ctx :arg-index i) 
                                           nested-objects-atom object-counter-atom schema-ir))
                                children)]
      (visit-node (into [] (cons tag transformed-children)) new-ctx 
                 nested-objects-atom object-counter-atom schema-ir))
    ;; Leaf node, return as-is
    node))

(defn flatten-nested-constructions
  "Flatten nested object and array constructions by extracting them into separate variables and replacing with variable references.
   Fail fast if a class name for an InnerObjectConstruction cannot be determined from explicit ClassName or schema context."
  [construction-ast schema-ir]

  (let [nested-objects (atom {})
        object-counter (atom 0)]
    (let [flattened-ast (walk-ast construction-ast {} nested-objects object-counter schema-ir)]

      {:flattened-ast flattened-ast
       :nested-objects @nested-objects})))



 