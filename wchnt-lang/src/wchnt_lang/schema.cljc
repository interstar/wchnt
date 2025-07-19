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
       (= (first ast) :MultiStepConstruction)
       (every? vector? (rest ast))
       (every? #(or (and (vector? %) 
                         (keyword? (first %))
                         (contains? #{:Statement :WS} (first %)))
                    (string? %))
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
