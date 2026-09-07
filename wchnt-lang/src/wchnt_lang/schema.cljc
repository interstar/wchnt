(ns wchnt-lang.schema
  (:require [malli.core :as m]))



(def EyeballResult
  [:or
   [:map
    [:status [:= "seems ok"]]
    [:issues [:sequential string?]]]
   [:map
    [:status [:= "issues"]]
    [:issues [:sequential string?]]]])

(def SyntaxValidationResult
  [:or
   [:map
    [:success [:= true]]
    [:ast any?]]
   [:map
    [:success [:= false]]
    [:error string?]]])

;; Mainfile parsing result schema
(def MainfileParseResult
  [:map
   [:page-kind [:enum :documentation :library :program]]
   [:import string?]
   [:schema string?]
   [:construction string?]
   [:methods string?]
   [:imperative string?]
   [:target-methods string?]
   [:target string?]])



;; AST validation functions that check key invariants
(defn valid-schema-ast? [ast]
  "Validate that schema AST has the expected structure"
  (and (vector? ast)
       (= (first ast) :Schema)
       (every? vector? (rest ast))
       (every? #(and (vector? %) 
                     (keyword? (first %))
                     (contains? #{:DefLine} (first %))) 
               (rest ast))))

(defn valid-construction-ast? [ast]
  "Validate that construction AST has the expected structure"
  (and (vector? ast)
       (= (first ast) :BlockStatements)
       (every? vector? (rest ast))
       (every? #(and (vector? %)
                     (keyword? (first %))
                     (contains? #{:Expression :Assignment :Statement :WS} (first %)))
               (rest ast))))

(defn valid-reaction-ast? [ast]
  "Validate that reaction AST is a Code node of method definitions"
  (and (vector? ast)
       (= (first ast) :Code)
       (every? #(and (vector? %)
                     (contains? #{:MethodDefinition :WS} (first %)))
               (rest ast))))

(defn valid-ast-node? [node]
  "Validate that an AST node has the expected structure"
  (cond
    (string? node) true
    (keyword? node) true
    (vector? node) (and (pos? (count node))
                        (keyword? (first node))
                        (every? valid-ast-node? (rest node)))
    :else false))

;; Enhanced syntax validation results with AST validation
(def SchemaSyntaxValidationResult
  [:or
   [:map
    [:success [:= true]]
    [:ast [:fn valid-schema-ast?]]]
   [:map
    [:success [:= false]]
    [:error string?]]])

(def ConstructionSyntaxValidationResult
  [:or
   [:map
    [:success [:= true]]
    [:ast [:fn valid-construction-ast?]]]
   [:map
    [:success [:= false]]
    [:error string?]]])

(def FullProgramStructure
  [:map
   [:classes string?]
   [:factory string?]
   [:main string?]
   [:codeblocks [:map]]
   [:warnings [:sequential string?]]
   ])


(defn valid-full-program? [result]
  (m/validate FullProgramStructure result))

 


(defn valid-eyeball-result? [result]
  (m/validate EyeballResult result))

(defn valid-syntax-result? [result]
  (m/validate SyntaxValidationResult result))

(defn valid-mainfile-parse-result? [result]
  (m/validate MainfileParseResult result))

(defn valid-schema-syntax-result? [result]
  (m/validate SchemaSyntaxValidationResult result))

(defn valid-construction-syntax-result? [result]
  (m/validate ConstructionSyntaxValidationResult result))

(defn eyeball-ok []
  {:status "seems ok"
   :issues []})

(defn eyeball-issues [issues]
  {:status "issues"
   :issues issues})



 

 



;; Schema IR Schemas
(def Component
  [:map
   [:component-name string?]
   [:type-name string?]
   [:relationship [:enum :ordinary :context-specific :external :reactive]]
   [:optional-name [:maybe string?]]])

(def AssemblageSchema
  [:map
   [:name string?]
   [:components [:sequential Component]]
   [:context-dependencies [:sequential string?]]
   [:context-providers [:sequential string?]]
   [:observable [:maybe boolean?]]])

(def Interface
  [:map
   [:name string?]
   [:implementers [:sequential string?]]])

(def EnumSchema
  [:map
   [:name string?]
   [:values [:sequential string?]]])

(def DebugMethod
  [:map
   [:class string?]
   [:method string?]
   [:depth-parameter [:or boolean? string?]]
   [:format [:or keyword? string?]]])

(def SchemaIR
  [:map
   [:assemblages [:sequential AssemblageSchema]]
   [:interfaces [:sequential Interface]]
   [:enums [:sequential EnumSchema]]
   [:context-relationships [:map-of string? [:maybe string?]]]
   [:interface-implementers [:map-of string? [:or string? set?]]]
   [:observable-classes [:sequential string?]]
   [:subscriber-classes [:sequential string?]]
   [:mailbox-classes {:optional true} [:sequential string?]]
   [:debug-methods [:sequential DebugMethod]]
   [:external-types {:optional true} [:set string?]]])

;; Construction IR Schemas (for object instances being constructed)
;; Argument structure for construction objects
;; Registry for recursive schemas
(def construction-registry
  (merge
    (m/default-schemas)                   ;; keep built-ins like :map, :sequential, :maybe, etc.
    {::ConstructionArg
     [:map
      [:type [:enum :object :primitive :variable :enum-value :array :map]]
      [:class-name string?]
      [:args [:sequential [:ref ::ConstructionArg]]]  ;; All nested args should be structured ConstructionArg objects
      [:index int?]
      [:value {:optional true} [:maybe [:or string? int? boolean?]]]]}))  ;; For primitives and variables: strings, integers, booleans. For objects, this is optional.

;; ConstructionArg schema - references the registry
(def ConstructionArg [:ref ::ConstructionArg])

;; Simple validation function - schema handles the structure validation
(defn valid-construction-arg? [arg]
  "Validate that a ConstructionArg has proper structure"
  (m/validate ConstructionArg arg {:registry construction-registry}))

(def ConstructionObjectSchema
  [:map
   [:type [:enum :object :primitive :variable :enum-value :array :map]]
   [:class-name string?]
   [:args [:sequential [:ref ::ConstructionArg]]]  ;; Must be structured ConstructionArg maps, not raw AST nodes
   [:index int?]])

(def Wiring
  [:map
   [:target string?]
   [:context string?]
   [:relationship string?]])

(def Collection
  [:map
   [:object-id string?]
   [:var-name string?]
   [:type [:enum :array :map]]
   [:element-type string?]
   [:elements [:sequential string?]]
   [:key-type [:maybe string?]]
   [:value-type [:maybe string?]]
   [:entries [:maybe [:sequential any?]]]])

(def Statement
  [:map
   [:statement-type [:enum :assignment :return]]
   [:variable string?]
   [:value any?]])

(def Dependency
  [:map
   [:object-id string?]
   [:depends-on string?]
   [:construction-order int?]])

(def ConstructionIR
  [:map
   [:root-class string?]
   [:factory-name string?]
   [:objects [:map-of string? ConstructionObjectSchema]]
   [:wiring [:sequential Wiring]]
   [:collections [:sequential Collection]]
   [:statements [:sequential Statement]]
   [:dependencies [:sequential Dependency]]
   [:variable-mappings [:map-of string? string?]]
   [:return-object string?]])

;; Method IR Schemas
(def Parameter
  [:map
   [:name string?]
   [:type string?]])

(def Method
  [:map
   [:class string?]
   [:method-name string?]
   [:parameters [:sequential Parameter]]
   [:return-type string?]
   [:interface-signature {:optional true} boolean?]
   [:lets [:sequential [:map [:name string?] [:value any?]]]]
   [:body {:optional true} any?]])

(def MethodsIR
  [:sequential Method])

;; Complete IR Schema
(def IR
  [:map
   [:schema SchemaIR]
   [:construction ConstructionIR]
   [:methods MethodsIR]])

;; =============================================================================
;; IR Validation Functions
;; =============================================================================

(defn valid-schema-ir? [schema-ir]
  (m/validate SchemaIR schema-ir))

(defn valid-construction-ir? [construction-ir]
  "Validate construction IR with custom nested arg validation"
  (and (m/validate ConstructionIR construction-ir {:registry construction-registry})
       (every? (fn [[obj-id obj-data]]
                 (every? valid-construction-arg? (:args obj-data)))
               (:objects construction-ir))))

(defn valid-methods-ir? [methods-ir]
  (m/validate MethodsIR methods-ir))

(defn valid-ir? [ir]
  (m/validate IR ir))

(defn explain-schema-ir [schema-ir]
  (when-not (valid-schema-ir? schema-ir)
    (m/explain SchemaIR schema-ir)))

(defn explain-construction-ir [construction-ir]
  (when-not (valid-construction-ir? construction-ir)
    (m/explain ConstructionIR construction-ir {:registry construction-registry})))

(defn explain-ir [ir]
  (when-not (valid-ir? ir)
    (m/explain IR ir)))

;; =============================================================================
;; IR Structure Validation Functions
;; =============================================================================

(defn has-structured-args? [construction-ir]
  "Check if construction IR has properly structured arguments (not raw AST nodes)"
  (let [objects (:objects construction-ir)]
    (every? (fn [[obj-id obj-data]]
              (let [args (:args obj-data)]
                (every? map? args)))  ;; All args should be maps, not vectors
            objects)))

(defn validate-ir-structure [ir]
  "Validate that IR has the proper structured format for Haxe generation"
  (let [construction-ir (:construction ir)]
    (if (has-structured-args? construction-ir)
      {:valid true :message "IR has proper structured arguments"}
      {:valid false :message "IR contains raw AST nodes instead of structured arguments"}))) 
