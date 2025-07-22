(ns wchnt-lang.ast-to-ir
  "Transform WCHNT AST to Intermediate Representation"
  (:require [wchnt-lang.ir :as ir]
            [wchnt-lang.parser :as parser]
            [clojure.string :as str]))

;; =============================================================================
;; Schema AST to IR Transformation
;; =============================================================================

(defn process-element-to-component
  "Transform a parsed element to an IR component"
  [element]
  (let [sigil (:sigil element)
        type-name (:type element)
        optional-name (:optional-name element)
        relationship (case sigil
                       ":" :context-specific
                       "@" :external
                       "$" :reactive
                       nil :ordinary
                       :ordinary)
        component-name (if optional-name
                        optional-name
                        (str (str/lower-case (first type-name)) (subs type-name 1)))]
    {::ir/component-name component-name
     ::ir/type-name type-name
     ::ir/relationship relationship
     ::ir/optional-name optional-name}))

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
                      ;; Don't initialize classes to nil - only set context relationships for context-specific components
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
    {::ir/name class-name
     ::ir/components components
     ::ir/context-dependencies []
     ::ir/context-providers []}))

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
    {::ir/name interface-name
     ::ir/implementers (vec type-names)}))

(defn transform-enum-line
  "Transform an enum line to an enum"
  [enum-line]
  (let [[_ & children] enum-line
        definee-node (first (filter #(= (first %) :Definee) children))
        enum-name (second definee-node)
        enum-value-nodes (filter #(= (first %) :EnumValue) children)
        enum-values (map second enum-value-nodes)]
    {::ir/name enum-name
     ::ir/values (vec enum-values)}))

(defn create-debug-methods
  "Create debug method specifications for all assemblages"
  [assemblages]
  (for [assemblage assemblages]
    {::ir/class (::ir/name assemblage)
     ::ir/method "toConstruction"
     ::ir/depth-parameter true
     ::ir/format :hiccup}))

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

(defn construction-ast-to-ir
  "Transform construction AST to IR construction (placeholder for Phase 2)"
  [construction-ast schema-ir]
  ;; This will be implemented in Phase 2
  (ir/create-construction-ir "Game" "gameFactory" [] [] [] [] [] {} "obj_1"))

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