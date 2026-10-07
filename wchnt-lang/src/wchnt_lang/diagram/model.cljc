(ns wchnt-lang.diagram.model
  "Schema IR + Methods IR → class-diagram model.

   The model is plain data:
     {:nodes [{:id :kind :stereotypes ...}] :edges [{:kind :from :to ...}]}

   Node kinds: :class, :interface (sum type), :enum, :external (borrowed @
   type not defined in this Schema), :imported-interface.

   Edge kinds:
     :is-a   implementer → interface
     :has-a  owner → part, with the Schema :relationship (sigil) and the
             field names as :labels. Collections point at their element class.

   Read-only over compiler output; layout and drawing live in diagram.layout
   and diagram.svg."
  (:require [clojure.string :as str]
            [wchnt-lang.ir :as ir]))

(def relationships
  #{:ordinary :context-specific :external :reactive :delegate})

;; --- types -------------------------------------------------------------------

(defn- array-element
  [type-name]
  (second (re-matches #"Array<(.+)>" type-name)))

(defn- map-parts
  [type-name]
  (when-let [[_ k v] (re-matches #"Map<([^,<>]+),\s*(.+)>" type-name)]
    [k v]))

(defn wchnt-type
  "IR type name in WCHNT spelling: Array<T> → [T], Map<K, V> → {K:V}."
  [type-name]
  (if-let [e (array-element type-name)]
    (str "[" (wchnt-type e) "]")
    (if-let [[k v] (map-parts type-name)]
      (str "{" k ":" (wchnt-type v) "}")
      type-name)))

(defn- collection-target
  "[innermost-type shape] where shape is the collection spelling with the
   element replaced by *: Array<Ball> → [\"Ball\" \"[*]\"]."
  [type-name]
  (if-let [e (array-element type-name)]
    (let [[inner shape] (collection-target e)]
      [inner (str "[" shape "]")])
    (if-let [[k v] (map-parts type-name)]
      (let [[inner shape] (collection-target v)]
        [inner (str "{" k ":" shape "}")])
      [type-name "*"])))

(defn- schema-types
  [schema-ir]
  (set (concat (map :name (:assemblages schema-ir))
               (map :name (:interfaces schema-ir))
               (map :name (:enums schema-ir)))))

;; --- components --------------------------------------------------------------

(defn- assert-relationship!
  [owner {:keys [relationship component-name]}]
  (when-not (contains? relationships relationship)
    (throw (ex-info (str "Diagram: unknown relationship " (pr-str relationship)
                         " on " owner "." component-name)
                    {:class owner :component component-name
                     :relationship relationship}))))

(defn- edge-target
  "{:to class :shape collection-shape-or-nil} when the component is drawn as
   an edge: it points at a Schema type, or it is a borrowed @ slot."
  [types {:keys [type-name relationship]}]
  (let [[inner shape] (collection-target type-name)]
    (when (or (contains? types inner) (= :external relationship))
      {:to inner
       :shape (when (not= inner type-name) shape)})))

(defn- component-edge
  [owner component {:keys [to shape]}]
  {:kind :has-a
   :from owner
   :to to
   :relationship (:relationship component)
   :labels [(if shape
              (str (:component-name component) " " shape)
              (:component-name component))]})

(defn- component-field
  [{:keys [component-name type-name]}]
  {:name component-name :type (wchnt-type type-name)})

(defn- context-field
  [schema-ir class-name]
  (when-let [parent (ir/get-context-parent schema-ir class-name)]
    {:name (ir/context-field-name parent) :type parent}))

;; --- nodes -------------------------------------------------------------------

(defn- class-stereotypes
  [schema-ir class-name]
  (cond-> []
    (= class-name (:root-class schema-ir)) (conj "root")
    (some #{class-name} (:observable-classes schema-ir)) (conj "observable")
    (ir/mailbox-class? schema-ir class-name) (conj "mailbox")))

(defn- class-node
  [schema-ir types {:keys [name components]}]
  (run! #(assert-relationship! name %) components)
  {:id name
   :kind :class
   :stereotypes (class-stereotypes schema-ir name)
   :mailbox? (ir/mailbox-class? schema-ir name)
   :fields (vec (concat (->> components
                             (remove #(edge-target types %))
                             (map component-field))
                        (keep identity [(context-field schema-ir name)])))})

(defn- interface-node
  [{:keys [name]}]
  {:id name :kind :interface :stereotypes ["interface"]})

(defn- enum-node
  [{:keys [name values]}]
  {:id name :kind :enum :stereotypes ["enum"] :values (vec values)})

(defn- outside-node
  [kind stereotype id]
  {:id id :kind kind :stereotypes [stereotype]})

;; --- edges -------------------------------------------------------------------

(defn- class-edges
  [types {:keys [name components]}]
  (keep (fn [c]
          (some->> (edge-target types c) (component-edge name c)))
        components))

(defn- merge-parallel-edges
  "One has-a edge per owner/part/relationship, labels in Schema order."
  [edges]
  (let [edge-key (juxt :from :to :relationship)
        grouped (group-by edge-key edges)]
    (mapv (fn [k]
            (let [es (grouped k)]
              (assoc (first es) :labels (vec (mapcat :labels es)))))
          (distinct (map edge-key edges)))))

(defn- is-a-edges
  [schema-ir]
  (distinct
   (concat (for [{:keys [name implementers]} (:interfaces schema-ir)
                 impl implementers]
             {:kind :is-a :from impl :to name})
           (for [{:keys [name implements]} (:assemblages schema-ir)
                 :when implements]
             {:kind :is-a :from name :to implements}))))

;; --- methods -----------------------------------------------------------------

(defn- method-entry
  [{:keys [method-name parameters return-type mutating? static?]}]
  {:name method-name
   :params (mapv (fn [{:keys [name type]}] {:name name :type (wchnt-type type)})
                 parameters)
   :return (wchnt-type return-type)
   :mutating? (boolean mutating?)
   :static? (boolean static?)})

(defn- assert-method-classes!
  [nodes by-class]
  (let [ids (set (map :id nodes))]
    (when-let [unknown (seq (remove ids (keys by-class)))]
      (throw (ex-info (str "Diagram: methods on unknown class "
                           (str/join ", " unknown))
                      {:classes (vec unknown)})))))

(defn- attach-methods
  "methods is a Methods IR seq, or :failed when Methods did not compile."
  [nodes methods]
  (let [by-class (if (= :failed methods) {} (group-by :class methods))]
    (assert-method-classes! nodes by-class)
    (mapv (fn [{:keys [id kind] :as n}]
            (if (#{:class :interface} kind)
              (assoc n :methods (if (= :failed methods)
                                  :failed
                                  (mapv method-entry (get by-class id []))))
              n))
          nodes)))

;; --- assembly ----------------------------------------------------------------

(defn- outside-nodes
  "Boxes for edge targets this Schema does not define."
  [defined edges]
  (let [missing (fn [kind] (->> edges
                                (filter #(= kind (:kind %)))
                                (map :to)
                                (remove defined)
                                distinct))]
    (concat (map #(outside-node :external "external" %) (missing :has-a))
            (map #(outside-node :imported-interface "interface" %) (missing :is-a)))))

(defn schema->diagram
  "Build the diagram model from Schema IR and Methods IR (or :failed).
   Throws on IR it does not understand rather than guessing."
  [schema-ir methods]
  (let [types (schema-types schema-ir)
        local (concat (map #(class-node schema-ir types %) (:assemblages schema-ir))
                      (map interface-node (:interfaces schema-ir))
                      (map enum-node (:enums schema-ir)))
        edges (into (merge-parallel-edges
                     (mapcat #(class-edges types %) (:assemblages schema-ir)))
                    (is-a-edges schema-ir))
        nodes (into (vec local) (outside-nodes (set (map :id local)) edges))]
    {:nodes (attach-methods nodes methods)
     :edges edges}))

(defn- documentation-cargo?
  [cargo]
  (and (:success cargo)
       (= :documentation (get-in cargo [:value :page-kind]))))

(defn from-cargo
  "Diagram from a compiler/compile-to-ir cargo.

   :status is :ok, :partial (later stage failed; Schema still drawn, methods
   marked :failed if Methods did not compile), :error (no Schema IR), or
   :documentation. :error carries the first compiler error."
  [cargo]
  (let [schema-ir (get-in cargo [:stash :schema-ir])
        methods-ir (get-in cargo [:stash :methods-ir])
        error (first (:errors cargo))
        empty-diagram {:nodes [] :edges []}]
    (cond
      (documentation-cargo? cargo)
      (assoc empty-diagram :status :documentation)

      (nil? schema-ir)
      (assoc empty-diagram :status :error
             :error (or error "Compilation produced no Schema"))

      (:success cargo)
      (assoc (schema->diagram schema-ir methods-ir) :status :ok)

      :else
      (assoc (schema->diagram schema-ir (or methods-ir :failed))
             :status :partial :error error))))
