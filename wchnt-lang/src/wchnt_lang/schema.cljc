(ns wchnt-lang.schema
  (:require [malli.core :as m]
            [malli.generator :as mg]
            [malli.error :as me]))

;; Core result schemas
(def CompilationResult
  [:or
   [:map
    [:success [:= true]]
    [:code [:sequential string?]]]
   [:map
    [:success [:= false]]
    [:error string?]]])

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
   [:schema string?]
   [:construction string?]
   [:reactive string?]
   [:imperative string?]
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
                     (contains? #{:Expression :Statement :WS} (first %)))
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
  (let [test (m/validate FullProgramStructure result) ]
    (println "In valid-full-program?")
    (println test)
    (if-not test
      (let [exp (m/explain FullProgramStructure result)]
        (println "FULL-PROGRAM-STRUCTURE FAILED")
        (println exp)
        (println (me/humanize exp))
        (println "====================== .... end of FAIL =====")
        result)
      result)))

 
;; Validation functions
(defn valid-compilation-result? [result]
  (m/validate CompilationResult result))

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



;; Multi-step construction AST schema (legacy - keeping for compatibility)
(def MultiStepConstructionAST
  [:map
   [:type [:= :MultiStepConstruction]]
   [:assignments [:sequential 
                  [:map
                   [:name string?]
                   [:construction any?]]]]
   [:final-construction vector?]])

(defn valid-multi-step-construction? [ast]
  (m/validate MultiStepConstructionAST ast)) 

;; Object Construction Table - the rich data structure built from AST walking
(def ObjectConstructionEntry
  [:map
   [:parent [:maybe string?]]  ; Parent object ID (for context relationships)
   [:class string?]            ; Class name to instantiate
   [:var-name [:maybe string?]] ; Variable name (e.g., "o1", "o2")
   [:ast any?]])               ; Original AST node for this object

(def ObjectConstructionTable
  [:map-of string? ObjectConstructionEntry])  ; ID -> Entry mapping

(defn valid-object-construction-table? [table]
  (m/validate ObjectConstructionTable table))

;; Object Construction Result - what walk-ast-and-build-table returns
(def ObjectConstructionResult
  [:tuple
   string?                    ; Root object ID
   int?                       ; Final counter value
   ObjectConstructionTable    ; The object table
   [:map-of string? string?]]) ; Variable mapping

(defn valid-object-construction-result? [result]
  (m/validate ObjectConstructionResult result)) 

;; =============================================================================
;; IR Data Structure Schemas (Malli)
;; =============================================================================

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
   [:debug-methods [:sequential DebugMethod]]])

;; Construction IR Schemas (for object instances being constructed)
;; Argument structure for construction objects
;; ConstructionArg schema with runtime validation for nested args
(def ConstructionArg
  [:map
   [:type [:enum :object :primitive :variable :enum-value :array :map]]
   [:class-name string?]
   [:value [:maybe [:or string? int? boolean?]]]  ;; For primitives and variables: strings, integers, booleans. For objects, this is optional.
   [:args [:sequential map?]]  ;; For objects, this contains nested args. For primitives and variables, this is empty.
   [:index int?]])

;; Custom validation function for ConstructionArg that checks nested structure
(defn valid-construction-arg? [arg]
  "Validate that a ConstructionArg has proper structure, including nested args"
  (and (m/validate ConstructionArg arg)
       (every? (fn [nested-arg]
                 (and (map? nested-arg)
                      (contains? nested-arg :type)
                      (contains? nested-arg :class-name)
                      (contains? nested-arg :index)))
               (:args arg))))

(def ConstructionObjectSchema
  [:map
   [:type [:enum :object :primitive :variable :enum-value :array :map]]
   [:class-name string?]
   [:args [:sequential ConstructionArg]]  ;; Must be structured ConstructionArg maps, not raw AST nodes
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
   [:body any?]])

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
  (and (m/validate ConstructionIR construction-ir)
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
    (m/explain ConstructionIR construction-ir)))

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

;; ============================================================================= 
