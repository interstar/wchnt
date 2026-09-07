(ns wchnt-lang.ast-to-ir
  "Transform WCHNT AST to Intermediate Representation"
  (:require [wchnt-lang.ir :as ir]
            [wchnt-lang.parser :as parser]
            [wchnt-lang.ast-utils :as ast-utils]
            [wchnt-lang.ast-args :as ast-args]
            [clojure.string :as str]))

(declare flatten-nested-constructions)

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

(defn- walk-for-contexts!
  [node parent-class context-map]
  (when (vector? node)
    (let [[tag & children] node]
      (case tag
        :Schema
        (doseq [child (filter vector? children)]
          (walk-for-contexts! child parent-class context-map))

        :CompositionLine
        (let [definee-node (first (filter #(= (first %) :Definee) children))
              class-name (ast-utils/definee-name definee-node)
              element-nodes (filter #(= (first %) :Element) children)
              processed-elements (map parser/process-element element-nodes)
              context-elements (filter #(= (:sigil %) ":") processed-elements)]
          (doseq [element context-elements]
            (swap! context-map assoc (:type element) class-name))
          (when (nil? (get @context-map class-name))
            (swap! context-map assoc class-name nil))
          (doseq [child (filter vector? children)]
            (walk-for-contexts! child class-name context-map)))

        :DisjunctionLine
        (doseq [child (filter vector? children)]
          (walk-for-contexts! child parent-class context-map))

        :EnumLine
        nil

        (doseq [child (filter vector? children)]
          (walk-for-contexts! child parent-class context-map))))))

(defn- collect-external-types-from-assemblages
  [assemblages]
  (into #{}
        (comp (mapcat :components)
              (filter #(= :external (:relationship %)))
              (map :type-name))
        assemblages))

(defn build-context-relationships
  "Build context relationship mappings from schema AST"
  [schema-ast]
  (let [context-map (atom {})]
    (walk-for-contexts! schema-ast nil context-map)
    @context-map))

(defn build-interface-implementers
  "Build interface implementation mappings from schema AST"
  [schema-ast]
  (let [disjunction-nodes (parser/find-all-nodes :DisjunctionLine schema-ast)]
    (reduce (fn [acc disjunction-node]
              (let [[_ & children] disjunction-node
                    definee-node (first (filter #(= (first %) :Definee) children))
                    interface-name (ast-utils/definee-name definee-node)
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

(def primitive-type-names #{"Int" "String" "Float" "Bool"})

(defn- collection-type-name?
  [type-name]
  (or (str/starts-with? type-name "Array<")
      (str/starts-with? type-name "Map<")))

(defn- reactive-pairs-from-composition-line
  [composition-line]
  (let [[_ & children] composition-line
        definee-node (first (filter #(= (first %) :Definee) children))
        class-name (ast-utils/definee-name definee-node)
        elements (map parser/process-element
                      (filter #(= (first %) :Element) children))]
    (for [element elements
          :when (= "$" (:sigil element))]
      {:subscriber class-name
       :observable (:type element)})))

(defn build-observable-and-subscriber-classes
  "Build observable and subscriber class lists from schema AST"
  [schema-ast]
  (let [pairs (mapcat reactive-pairs-from-composition-line
                      (parser/find-all-nodes :CompositionLine schema-ast))]
    {:observable-classes (vec (distinct (map :observable pairs)))
     :subscriber-classes (vec (distinct (map :subscriber pairs)))}))

(defn- validate-reactive-component!
  [subscriber-class {:keys [type-name component-name]} assemblage-names]
  (cond
    (contains? primitive-type-names type-name)
    (throw (ex-info (str "Reactive component $" type-name " on " subscriber-class
                         " must be a schema class, not a primitive")
                    {:class subscriber-class :type-name type-name}))

    (collection-type-name? type-name)
    (throw (ex-info (str "Reactive component $" type-name " on " subscriber-class
                         " must be a single object, not a collection")
                    {:class subscriber-class :type-name type-name}))

    (not (contains? assemblage-names type-name))
    (throw (ex-info (str "Reactive component $" type-name
                         (when component-name (str "/" component-name))
                         " on " subscriber-class
                         " is not a class in this schema")
                    {:class subscriber-class :type-name type-name}))))

(defn- validate-reactive-components!
  [assemblages]
  (let [assemblage-names (set (map :name assemblages))]
    (doseq [assemblage assemblages
            component (:components assemblage)
            :when (= :reactive (:relationship component))]
      (validate-reactive-component! (:name assemblage) component assemblage-names)))
  assemblages)


(defn transform-composition-line
  "Transform a composition line to an assemblage"
  [composition-line]
  (let [[_ & children] composition-line
        definee-node (first (filter #(= (first %) :Definee) children))
        class-name (ast-utils/definee-name definee-node)
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
        interface-name (ast-utils/definee-name definee-node)
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
        enum-name (ast-utils/definee-name definee-node)
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

(defn- composition-inlet-names
  [schema-ast]
  (for [line (parser/find-all-nodes :CompositionLine schema-ast)
        :let [definee (first (filter #(= (first %) :Definee) (rest line)))]
        :when (ast-utils/definee-inlet? definee)]
    (ast-utils/definee-name definee)))

(defn- assert-inlet-only-on-classes!
  [schema-ast]
  (doseq [line (concat (parser/find-all-nodes :DisjunctionLine schema-ast)
                       (parser/find-all-nodes :EnumLine schema-ast))
          :let [definee (first (filter #(= (first %) :Definee) (rest line)))]
          :when (and definee (ast-utils/definee-inlet? definee))]
    (throw (ex-info (str ">" (ast-utils/definee-name definee)
                         " is only allowed on a composition class, not a sum type or enum")
                    {:name (ast-utils/definee-name definee)}))))

(defn- assert-no-reserved-class-names!
  [assemblages]
  (when (some #(= "Main" (:name %)) assemblages)
    (throw (ex-info "Schema class 'Main' is reserved. The compiler generates class Main as the program entry. Rename the class."
                    {:reserved "Main"}))))

(defn schema-ast-to-ir
  "Transform schema AST to IR schema"
  [schema-ast]
  (let [composition-lines (parser/find-all-nodes :CompositionLine schema-ast)
        disjunction-lines (parser/find-all-nodes :DisjunctionLine schema-ast)
        enum-lines (parser/find-all-nodes :EnumLine schema-ast)
        
        assemblages (validate-reactive-components!
                     (map transform-composition-line composition-lines))]
    (assert-no-reserved-class-names! assemblages)
    (assert-inlet-only-on-classes! schema-ast)
    (let [interfaces (map transform-disjunction-line disjunction-lines)
          enums (map transform-enum-line enum-lines)
          context-relationships (build-context-relationships schema-ast)
          interface-implementers (build-interface-implementers schema-ast)
          {:keys [observable-classes subscriber-classes]} (build-observable-and-subscriber-classes schema-ast)
          mailbox-classes (vec (distinct (composition-inlet-names schema-ast)))
          debug-methods (create-debug-methods assemblages)]
      (assoc (ir/create-schema-ir assemblages interfaces enums context-relationships
                                  interface-implementers observable-classes subscriber-classes
                                  debug-methods
                                  (collect-external-types-from-assemblages assemblages))
             :mailbox-classes mailbox-classes))))

;; =============================================================================
;; Construction AST to IR
;; =============================================================================

(defn extract-args-from-object-construction
  "Extract structured arguments from a single ObjectConstruction node.

  Implementation lives in wchnt-lang.ast-args so it can be reused across phases."
  [object-construction schema-ir root-class-name]
  (ast-args/extract-args-from-object-construction object-construction schema-ir root-class-name))













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

(defn process-array-construction-element
  "Process one item inside an ArrayConstruction."
  [array-type element index schema-ir]
  (if (and schema-ir
           (ast-utils/node-type? element :InnerObjectConstruction)
           (not (ast-utils/node-type? (second element) :ClassName)))
    (let [arg-list (second element)]
      {:type :object
       :class-name array-type
       :args (extract-args-from-object-construction
              [:ObjectConstruction [:ClassName array-type] arg-list]
              schema-ir
              array-type)
       :index index})
    (first (extract-args-from-object-construction
            [:ObjectConstruction [:ClassName array-type] [:ArgList element]]
            schema-ir
            array-type))))

(defn process-array-construction-expression
  "Process an ArrayConstruction expression"
  ([inner-expression]
   (process-array-construction-expression inner-expression nil))
  ([inner-expression schema-ir]
  (let [type-node (second inner-expression)
        array-type (if (ast-utils/node-type? type-node :Type)
                    (second type-node)
                    (throw (ex-info "Array construction missing type node" 
                                  {:inner-expression inner-expression})))
        arg-list (nth inner-expression 2)
        array-elements (if schema-ir
                         (map-indexed
                          #(process-array-construction-element array-type %2 %1 schema-ir)
                          (rest arg-list))
                         (if (ast-utils/node-type? arg-list :ArgList)
                           (rest arg-list)
                           []))]
    {:type :array
     :class-name array-type
     :args array-elements})))

(defn process-object-construction-expression
  "Process an ObjectConstruction expression"
  ([inner-expression]
   (process-object-construction-expression inner-expression nil))
  ([inner-expression schema-ir]
  (let [class-name-node (second inner-expression)
        class-name (if (ast-utils/node-type? class-name-node :ClassName)
                    (second class-name-node)
                    (throw (ex-info "Object construction missing class name node" 
                                  {:inner-expression inner-expression})))
        arg-list (nth inner-expression 2)
        args (if schema-ir
               (extract-args-from-object-construction inner-expression schema-ir class-name)
               (if (ast-utils/node-type? arg-list :ArgList)
                 (rest arg-list)
                 []))]
    {:type :object
     :class-name class-name
     :args args})))

(defn process-map-construction-expression
  "Process a MapConstruction expression"
  [inner-expression]
  (ast-args/process-map-construction-expression inner-expression))

(defn process-assignment-expression
  "Process an assignment expression to extract object information"
  ([expression nested-objects]
   (process-assignment-expression expression nested-objects nil))
  ([expression nested-objects schema-ir]
  (let [inner-expression (second expression)]  ;; Get the expression inside [:Expression ...]
    (cond
      (ast-utils/node-type? inner-expression :VariableRef)
      (process-variable-ref-expression inner-expression nested-objects)
      
      (ast-utils/node-type? inner-expression :ArrayConstruction)
      ;; Check if this array construction was already processed during flattening
      (let [existing-obj (first (filter #(= (:ast (second %)) inner-expression) nested-objects))]
        (if existing-obj
          (second existing-obj)  ;; Return the existing object
          (process-array-construction-expression inner-expression schema-ir)))
      
      (ast-utils/node-type? inner-expression :ObjectConstruction)
      (process-object-construction-expression inner-expression schema-ir)
      
      (ast-utils/node-type? inner-expression :MapConstruction)
      (process-map-construction-expression inner-expression)
      
      :else
      (throw (ex-info "Unknown expression type in assignment" 
                     {:inner-expression inner-expression}))))))

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

(defn process-assignments
  "Process assignment statements to build object table and variable mappings"
  ([statements nested-objects starting-counter]
   (process-assignments statements nested-objects starting-counter nil))
  ([statements nested-objects starting-counter schema-ir]
  (let [assignments (filter #(and (vector? %) (= (first %) :Assignment)) statements)]
    (reduce (fn [[objects mappings counter] assignment]
                              (let [[_ var-name-node expression] assignment
                      var-name (if (ast-utils/node-type? var-name-node :VariableName)
                                (second var-name-node)
                                (str var-name-node))
                                          ;; Check if the assignment expression is a simple variable reference to an existing nested object
                    existing-obj-id (when (and (= (first expression) :Expression)
                                              (= (first (second expression)) :VariableRef))
                                     (second (second expression)))]
                  (if (and existing-obj-id (contains? nested-objects existing-obj-id))
                    ;; Use existing nested object
                    [objects
                     (assoc mappings var-name existing-obj-id)
                     counter]
                    ;; Create new object
                    (let [obj-id (str "obj" (inc counter))
                          processed-expression (process-assignment-expression expression nested-objects schema-ir)]
                      [(assoc objects obj-id (assoc processed-expression :index counter))
                       (assoc mappings var-name obj-id)
                       (inc counter)]))))
            [{} {} starting-counter]
            assignments))))

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
  ;; Nested objects don't have variable names, so just return the original mappings
  variable-mappings)

(defn construction-ast-to-ir
  "Transform construction AST to IR construction"
  [construction-ast schema-ir]
  (let [root-class (extract-root-class-from-construction construction-ast schema-ir)
        factory-name (str (str/lower-case (first root-class)) (subs root-class 1) "Factory")
        {:keys [flattened-ast nested-objects]} (flatten-nested-constructions construction-ast schema-ir)
        [assignment-objects variable-mappings] (process-assignments (rest flattened-ast) nested-objects (count nested-objects) schema-ir)
        all-variable-mappings (merge-variable-mappings variable-mappings nested-objects)
        all-assignment-objects (merge assignment-objects nested-objects)
        final-construction (find-final-construction (rest flattened-ast))
        final-objects (process-final-construction final-construction all-assignment-objects all-variable-mappings schema-ir root-class)
        all-objects (merge all-assignment-objects final-objects)
        return-obj-id (if (empty? final-objects)
                        "obj1"
                        (first (keys final-objects)))]
    (ir/create-construction-ir root-class factory-name all-objects [] [] [] [] all-variable-mappings return-obj-id)))

;; =============================================================================
;; Construction flattening
;; =============================================================================

;; process-children refers to walk-ast-for-flattening before it is defined below.
(declare walk-ast-for-flattening)

(defn get-explicit-class-name
  "Extract explicit class name from AST node"
  [node]
  (when (and (vector? node) 
             (>= (count node) 2)
             (vector? (second node))
             (= :ClassName (first (second node))))
    (second (second node))))

(defn determine-class-name-for-inner-construction
  "Determine the class name for an InnerObjectConstruction node"
  [node ctx schema-ir]
  (or (get-explicit-class-name node)
      ;; If no explicit class name, try to get it from context
      (if (:array-element-type ctx)
        (:array-element-type ctx)  ; Use array element type if we're inside an array
        (let [parent-class (:parent-class ctx)
              arg-index (:arg-index ctx)]
          (if (and parent-class arg-index)
            (let [assemblage (first (filter #(= (:name %) parent-class) (:assemblages schema-ir)))
                  components (:components assemblage)]
              (if (and assemblage components (< arg-index (count components)))
                (:type-name (nth components arg-index))
                (throw (ex-info "Could not determine expected class name for argument"
                              {:arg-index arg-index
                               :parent-class parent-class
                               :schema-ir schema-ir}))))
            (throw (ex-info "Could not determine class name for inner construction" {:ast node :ctx ctx})))))))

(defn extract-arg-list-from-node
  "Extract the argument list from an InnerObjectConstruction node"
  [node]
  (if (get-explicit-class-name node)
    (nth node 2)  ; [:ClassName "X"] [:ArgList ...]
    (second node))) ; [:ArgList ...]

(defn process-structured-arguments
  "Process arguments into structured IR format"
  [class-name arg-list schema-ir]
  (extract-args-from-object-construction 
    [:ObjectConstruction [:ClassName class-name] arg-list] 
    schema-ir 
    class-name))

(defn record-nested-object!
  "Record a nested object and return [obj-id updated-nested-objects]"
  [nested-objects object-counter entry]
  (let [obj-id (str "obj" (inc object-counter))
        entry-with-index (assoc entry :index (count nested-objects))
        updated-nested-objects (assoc nested-objects obj-id entry-with-index)]
    [obj-id updated-nested-objects]))

(defn create-and-record-object
  "Create an object and record it in the nested objects map"
  [class-name structured-args nested-objects object-counter object-type]
  (let [obj-entry {:type object-type, :class-name class-name, :args structured-args}
        [obj-id updated-nested-objects] (record-nested-object! nested-objects object-counter obj-entry)]
    {:obj-id obj-id, :nested-objects updated-nested-objects, :object-counter (inc object-counter)}))

(defn visit-node
  "Process a single AST node with context and transformed children"
  [node ctx nested-objects object-counter schema-ir]
  (let [[tag & children] node]
    (cond
      ;; --- Case 1: Nodes that should be flattened ---
      (= tag :InnerObjectConstruction)
      (let [class-name (determine-class-name-for-inner-construction node ctx schema-ir)]
        (when-not class-name
          (throw (ex-info "Could not determine class name for inner construction" {:ast node :ctx ctx})))
        (let [arg-list (extract-arg-list-from-node node)
              structured-args (process-structured-arguments class-name arg-list schema-ir)
              result (create-and-record-object class-name structured-args nested-objects object-counter :object)]
          {:result [:VariableRef (:obj-id result)], :nested-objects (:nested-objects result), :object-counter (:object-counter result)}))

      (= tag :ArrayConstruction)
      (let [element-type (second (second node))  ; from [:Type "Person"]
            arg-list (nth node 2)]               ; from [:ArgList ...]
        (let [structured-args (map-indexed
                               #(process-array-construction-element element-type %2 %1 schema-ir)
                               (rest arg-list))
              result (create-and-record-object element-type structured-args nested-objects object-counter :array)]
          {:result [:VariableRef (:obj-id result)], :nested-objects (:nested-objects result), :object-counter (:object-counter result)}))

      (= tag :MapConstruction)
      (let [map-ir (process-map-construction-expression node)
            [obj-id updated-nested-objects] (record-nested-object! nested-objects object-counter map-ir)]
        {:result [:VariableRef obj-id], :nested-objects updated-nested-objects, :object-counter (inc object-counter)})

      ;; --- Case 2: Structural nodes that are just rebuilt ---
      (#{:ObjectConstruction :ArgList :ClassName :Type :BlockStatements :Expression} tag)
      {:result (into [] (cons tag children)), :nested-objects nested-objects, :object-counter object-counter}

      ;; --- Case 3: Leaf nodes ---
      :else
      {:result node, :nested-objects nested-objects, :object-counter object-counter})))

(defn process-children
  "Process children of a node and return updated context and results"
  [children ctx nested-objects object-counter schema-ir]
  (reduce
   (fn [acc [i child]]
     (let [child-result (walk-ast-for-flattening child (assoc ctx :arg-index i) 
                                  (:nested-objects acc) (:object-counter acc) schema-ir)]
       {:children (conj (:children acc) (:result child-result))
        :nested-objects (:nested-objects child-result)
        :object-counter (:object-counter child-result)}))
   {:children [] :nested-objects nested-objects :object-counter object-counter}
   (map-indexed vector children)))

(defn build-context-for-node
  "Build appropriate context for a node type"
  [node ctx schema-ir]
  (case (first node)
    :ObjectConstruction
    (assoc ctx :parent-class (get-explicit-class-name node))
    :ArrayConstruction
    (assoc ctx :array-element-type (second (second node)))  ; Set array element type
    :InnerObjectConstruction
    (assoc ctx :parent-class
           (or (get-explicit-class-name node)
               (:array-element-type ctx)
               (type-from-class-and-position (:parent-class ctx) (:arg-index ctx) schema-ir)))
    ctx))

(defn walk-ast-for-flattening
  "Walk AST tree with context, processing children before parents"
  [node ctx nested-objects object-counter schema-ir]
  (if (vector? node)
    (let [[tag & children] node]
      (case tag
        ;; Handle array constructions by flattening them
        :ArrayConstruction
        (visit-node node ctx nested-objects object-counter schema-ir)
        
        ;; Handle inner object constructions by flattening them
        :InnerObjectConstruction
        (visit-node node ctx nested-objects object-counter schema-ir)
        
        :MapConstruction
        (visit-node node ctx nested-objects object-counter schema-ir)
        
        ;; Handle other nodes by processing children
        (let [new-ctx (build-context-for-node node ctx schema-ir)
              transformed-children-result (process-children children new-ctx nested-objects object-counter schema-ir)]
          (visit-node (into [] (cons tag (:children transformed-children-result))) new-ctx 
                     (:nested-objects transformed-children-result) (:object-counter transformed-children-result) schema-ir))))
    ;; Leaf node, return as-is
    {:result node, :nested-objects nested-objects, :object-counter object-counter}))

(defn flatten-nested-constructions
  "Flatten nested object and array constructions by extracting them into separate variables and replacing with variable references.
   Fail fast if a class name for an InnerObjectConstruction cannot be determined from explicit ClassName or schema context."
  [construction-ast schema-ir]

  (let [flattening-result (walk-ast-for-flattening construction-ast {} {} 0 schema-ir)]
    {:flattened-ast (:result flattening-result)
     :nested-objects (:nested-objects flattening-result)}))



 
