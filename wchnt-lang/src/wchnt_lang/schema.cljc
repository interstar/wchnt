(ns wchnt-lang.schema
  (:require [malli.core :as m]
            [malli.generator :as mg]))

;; Core result schemas
(def CompilationResult
  [:or
   [:map
    [:success [:= true]]
    [:code [:sequential string?]]]
   [:map
    [:success [:= false]]
    [:error string?]]])

(def ValidationResult
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
  [:or
   [:map
    [:success [:= true]]
    [:schema string?]
    [:construction string?]
    [:reactive string?]
    [:imperative string?]
    [:target string?]]
   [:map
    [:success [:= false]]
    [:error string?]]])

;; Validation functions
(defn valid-compilation-result? [result]
  (m/validate CompilationResult result))

(defn valid-validation-result? [result]
  (m/validate ValidationResult result))

(defn valid-syntax-result? [result]
  (m/validate SyntaxValidationResult result))

(defn valid-mainfile-parse-result? [result]
  (m/validate MainfileParseResult result))

;; Helper functions to create results
(defn success-result [code]
  {:success true
   :code code})

(defn error-result [error]
  {:success false
   :error error})

(defn validation-ok []
  {:status "seems ok"
   :issues []})

(defn validation-issues [issues]
  {:status "issues"
   :issues issues})

(defn syntax-success [ast & [additional-data]]
  (merge {:success true
          :ast ast}
         additional-data))

(defn syntax-error [error]
  {:success false
   :error error})

;; Multi-step construction AST schema
(def MultiStepConstructionAST
  [:map
   [:type [:= :MultiStepConstruction]]
   [:assignments [:sequential 
                  [:map
                   [:name string?]
                   [:construction any?]]]]
   [:final-construction any?]])

(defn valid-multi-step-construction? [ast]
  (m/validate MultiStepConstructionAST ast)) 