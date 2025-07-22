(ns wchnt-lang.ir
  "Core IR data structures and validation for WCHNT intermediate representation"
  (:require [clojure.spec.alpha :as s]))

;; =============================================================================
;; IR Data Structure Specifications
;; =============================================================================

;; Schema IR Specifications
(s/def ::component-name string?)
(s/def ::type-name string?)
(s/def ::optional-name (s/nilable string?))
(s/def ::relationship #{:ordinary :context-specific :external :reactive})

(s/def ::component
  (s/keys :req [::component-name ::type-name ::relationship]
          :opt [::optional-name]))

(s/def ::assemblage
  (s/keys :req [::name ::components ::context-dependencies ::context-providers]
          :opt [::observable]))

(s/def ::interface
  (s/keys :req [::name ::implementers]))

(s/def ::enum
  (s/keys :req [::name ::values]))

(s/def ::debug-method
  (s/keys :req [::class ::method ::depth-parameter ::format]))

(s/def ::schema-ir
  (s/keys :req [::assemblages ::interfaces ::enums ::context-relationships 
                ::interface-implementers ::observable-classes ::subscriber-classes ::debug-methods]))

;; Construction IR Specifications
(s/def ::object-id string?)
(s/def ::var-name string?)
(s/def ::class-name string?)
(s/def ::args (s/coll-of any?))
(s/def ::parent-id (s/nilable string?))

(s/def ::object
  (s/keys :req [::object-id ::var-name ::class-name ::args ::parent-id]))

(s/def ::wiring
  (s/keys :req [::target ::context ::relationship]))

(s/def ::collection-type #{:array :map})
(s/def ::element-type string?)
(s/def ::key-type string?)
(s/def ::value-type string?)
(s/def ::elements (s/coll-of string?))
(s/def ::entries (s/coll-of (s/coll-of any?)))

(s/def ::collection
  (s/keys :req [::object-id ::var-name ::type ::element-type ::elements]
          :opt [::key-type ::value-type ::entries]))

(s/def ::statement-type #{:assignment :return})
(s/def ::variable string?)
(s/def ::value any?)

(s/def ::statement
  (s/keys :req [::statement-type ::variable ::value]))

(s/def ::dependency
  (s/keys :req [::object-id ::depends-on ::construction-order]))

(s/def ::construction-ir
  (s/keys :req [::root-class ::factory-name ::objects ::wiring ::collections 
                ::statements ::dependencies ::variable-mappings ::return-object]))

;; Method IR Specifications
(s/def ::method-name string?)
(s/def ::parameters (s/coll-of (s/keys :req [::name ::type])))
(s/def ::return-type string?)
(s/def ::body any?)

(s/def ::method
  (s/keys :req [::class ::method-name ::parameters ::return-type ::body]))

(s/def ::methods-ir
  (s/coll-of ::method))

;; Complete IR Specification
(s/def ::ir
  (s/keys :req [::schema ::construction ::methods]))

;; =============================================================================
;; IR Creation Functions
;; =============================================================================

(defn create-schema-ir
  "Create a schema IR structure"
  [assemblages interfaces enums context-relationships interface-implementers 
   observable-classes subscriber-classes debug-methods]
  {::assemblages assemblages
   ::interfaces interfaces
   ::enums enums
   ::context-relationships context-relationships
   ::interface-implementers interface-implementers
   ::observable-classes observable-classes
   ::subscriber-classes subscriber-classes
   ::debug-methods debug-methods})

(defn create-construction-ir
  "Create a construction IR structure"
  [root-class factory-name objects wiring collections statements 
   dependencies variable-mappings return-object]
  {::root-class root-class
   ::factory-name factory-name
   ::objects objects
   ::wiring wiring
   ::collections collections
   ::statements statements
   ::dependencies dependencies
   ::variable-mappings variable-mappings
   ::return-object return-object})

(defn create-ir
  "Create a complete IR structure"
  [schema construction methods]
  {::schema schema
   ::construction construction
   ::methods methods})

;; =============================================================================
;; IR Validation Functions
;; =============================================================================

(defn validate-schema-ir
  "Validate a schema IR structure"
  [schema-ir]
  (if (s/valid? ::schema-ir schema-ir)
    {:valid true :schema-ir schema-ir}
    {:valid false :errors (s/explain-data ::schema-ir schema-ir)}))

(defn validate-construction-ir
  "Validate a construction IR structure"
  [construction-ir]
  (if (s/valid? ::construction-ir construction-ir)
    {:valid true :construction-ir construction-ir}
    {:valid false :errors (s/explain-data ::construction-ir construction-ir)}))

(defn validate-ir
  "Validate a complete IR structure"
  [ir]
  (if (s/valid? ::ir ir)
    {:valid true :ir ir}
    {:valid false :errors (s/explain-data ::ir ir)}))

;; =============================================================================
;; IR Utility Functions
;; =============================================================================

(defn get-observable-classes
  "Get list of classes that need observable infrastructure"
  [schema-ir]
  (::observable-classes schema-ir))

(defn get-subscriber-classes
  "Get list of classes that subscribe to observables"
  [schema-ir]
  (::subscriber-classes schema-ir))

(defn get-context-relationships
  "Get context relationship mappings"
  [schema-ir]
  (::context-relationships schema-ir))

(defn get-interface-implementers
  "Get interface implementation mappings"
  [schema-ir]
  (::interface-implementers schema-ir))

(defn find-assemblage
  "Find an assemblage by name"
  [schema-ir assemblage-name]
  (first (filter #(= (::name %) assemblage-name) (::assemblages schema-ir))))

(defn get-assemblage-components
  "Get components for an assemblage"
  [schema-ir assemblage-name]
  (::components (find-assemblage schema-ir assemblage-name)))

(defn is-observable?
  "Check if a class is observable"
  [schema-ir class-name]
  (contains? (set (::observable-classes schema-ir)) class-name))

(defn is-subscriber?
  "Check if a class subscribes to observables"
  [schema-ir class-name]
  (contains? (set (::subscriber-classes schema-ir)) class-name))

(defn needs-context?
  "Check if a class needs context"
  [schema-ir class-name]
  (contains? (set (keys (::context-relationships schema-ir))) class-name))

(defn get-context-parent
  "Get the parent class that provides context"
  [schema-ir class-name]
  (get (::context-relationships schema-ir) class-name)) 