(ns wchnt-lang.ir
  "IR constructors and lookup helpers. Shape is checked with Malli in wchnt-lang.schema.")

(defn create-schema-ir
  "Create a schema IR structure"
  [assemblages interfaces enums context-relationships interface-implementers
   observable-classes subscriber-classes debug-methods]
  {:assemblages assemblages
   :interfaces interfaces
   :enums enums
   :context-relationships context-relationships
   :interface-implementers interface-implementers
   :observable-classes observable-classes
   :subscriber-classes subscriber-classes
   :debug-methods debug-methods})

(defn create-construction-ir
  "Create a construction IR structure"
  [root-class factory-name objects wiring collections statements
   dependencies variable-mappings return-object]
  {:root-class root-class
   :factory-name factory-name
   :objects objects
   :wiring wiring
   :collections collections
   :statements statements
   :dependencies dependencies
   :variable-mappings variable-mappings
   :return-object return-object})

(defn get-observable-classes
  "Get list of classes that need observable infrastructure"
  [schema-ir]
  (:observable-classes schema-ir))

(defn get-subscriber-classes
  "Get list of classes that subscribe to observables"
  [schema-ir]
  (:subscriber-classes schema-ir))

(defn get-context-relationships
  "Get context relationship mappings"
  [schema-ir]
  (:context-relationships schema-ir))

(defn get-interface-implementers
  "Get interface implementation mappings"
  [schema-ir]
  (:interface-implementers schema-ir))

(defn find-assemblage
  "Find an assemblage by name"
  [schema-ir assemblage-name]
  (first (filter #(= (:name %) assemblage-name) (:assemblages schema-ir))))

(defn get-assemblage-components
  "Get components for an assemblage"
  [schema-ir assemblage-name]
  (:components (find-assemblage schema-ir assemblage-name)))

(defn is-observable?
  "Check if a class is observable"
  [schema-ir class-name]
  (contains? (set (:observable-classes schema-ir)) class-name))

(defn is-subscriber?
  "Check if a class subscribes to observables"
  [schema-ir class-name]
  (contains? (set (:subscriber-classes schema-ir)) class-name))

(defn context-field-name
  "Haxe field name for a context parent, e.g. Car → theCar"
  [parent-class-name]
  (str "the" parent-class-name))

(defn needs-context?
  "Check if a class needs context"
  [schema-ir class-name]
  (contains? (set (keys (:context-relationships schema-ir))) class-name))

(defn get-context-parent
  "Get the parent class that provides context"
  [schema-ir class-name]
  (get (:context-relationships schema-ir) class-name))

(defn reactive-components
  "Reactive ($) components of a class, or empty if the class is unknown."
  [schema-ir class-name]
  (filterv #(= :reactive (:relationship %))
           (or (get-assemblage-components schema-ir class-name) [])))
