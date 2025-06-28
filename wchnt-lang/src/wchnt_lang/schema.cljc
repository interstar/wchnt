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

;; Validation functions
(defn valid-compilation-result? [result]
  (m/validate CompilationResult result))

(defn valid-validation-result? [result]
  (m/validate ValidationResult result))

(defn valid-syntax-result? [result]
  (m/validate SyntaxValidationResult result))

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

(defn syntax-success [ast]
  {:success true
   :ast ast})

(defn syntax-error [error]
  {:success false
   :error error}) 