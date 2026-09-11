(ns wchnt-lang.ir
  "IR constructors and lookup helpers. Shape is checked with Malli in wchnt-lang.schema.")

(defn create-schema-ir
  "Create a schema IR structure"
  [assemblages interfaces enums context-relationships interface-implementers
   observable-classes subscriber-classes debug-methods external-types]
  {:assemblages assemblages
   :interfaces interfaces
   :enums enums
   :context-relationships context-relationships
   :interface-implementers interface-implementers
   :observable-classes observable-classes
   :subscriber-classes subscriber-classes
   :debug-methods debug-methods
   :external-types (or external-types #{})})

(defn get-external-types
  "Type names referenced with @ (schema field or Methods param) but not defined here."
  [schema-ir]
  (or (:external-types schema-ir) #{}))

(defn external-type?
  [schema-ir type-name]
  (contains? (get-external-types schema-ir) type-name))

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

(defn get-mailbox-classes
  "Classes marked `>Name` in Schema. Target may inject field values, then update."
  [schema-ir]
  (or (:mailbox-classes schema-ir) []))

(defn mailbox-class?
  [schema-ir class-name]
  (contains? (set (get-mailbox-classes schema-ir)) class-name))

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

(defn- variable-arg-name
  [arg]
  (when (and (map? arg) (= (:type arg) :variable))
    (or (:value arg) (first (:args arg)))))

(defn- arg-ref-names
  [arg]
  (cond
    (nil? arg) []
    (variable-arg-name arg) [(variable-arg-name arg)]
    (and (map? arg) (:args arg)) (mapcat arg-ref-names (:args arg))
    (sequential? arg) (mapcat arg-ref-names arg)
    :else []))

(defn- resolve-object-id
  [name variable-mappings object-ids]
  (let [resolved (get variable-mappings name name)]
    (when (contains? object-ids resolved)
      resolved)))

(defn- object-dependencies
  [obj-data variable-mappings object-ids]
  (->> (arg-ref-names obj-data)
       (keep #(resolve-object-id % variable-mappings object-ids))
       set))

(defn- next-ready-object-id
  [remaining deps objects]
  (->> remaining
       (filter #(empty? (get deps %)))
       (sort-by #(or (:index (get objects %)) 0))
       first))

(defn topo-sort-object-ids
  "Object ids in an order where each object's variable refs already exist.
   Nested maps are often extracted before the assignments they name."
  [objects variable-mappings]
  (let [object-ids (set (keys objects))
        initial-deps (into {} (map (fn [[id data]]
                                     [id (object-dependencies data variable-mappings object-ids)])
                                   objects))]
    (loop [remaining object-ids
           deps initial-deps
           ordered []]
      (if (empty? remaining)
        ordered
        (if-let [id (next-ready-object-id remaining deps objects)]
          (recur (disj remaining id)
                 (into {} (map (fn [[k v]] [k (disj v id)]) deps))
                 (conj ordered id))
          (throw (ex-info "Circular object references in construction"
                          {:remaining remaining :deps deps})))))))

(defn objects-in-construction-order
  "Construction objects as [id data] pairs, dependencies before dependents."
  [objects variable-mappings]
  (map (fn [id] [id (get objects id)])
       (topo-sort-object-ids objects variable-mappings)))
