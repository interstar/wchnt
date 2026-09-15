(ns wchnt-java-analysis.analyze
  (:require [clojure.string :as str]))

(def primitive-type-map
  {"byte" "Byte" "Byte" "Byte"
   "short" "Short" "Short" "Short"
   "int" "Int" "Integer" "Int"
   "long" "Long" "Long" "Long"
   "float" "Float" "Float" "Float"
   "double" "Double" "Double" "Double"
   "boolean" "Bool" "Boolean" "Bool"
   "char" "Char" "Character" "Char"
   "String" "String"})

(def primitive-types (set (vals primitive-type-map)))

(defn simple-type [type-name]
  (let [t (-> type-name str/trim (str/replace #"\s+" ""))]
    (cond
      (contains? primitive-type-map t) (get primitive-type-map t)
      (str/ends-with? t "[]") (str "[" (simple-type (subs t 0 (- (count t) 2))) "]")
      (re-matches #"(?:List|Collection|Set|Iterable)<(.+)>" t) (str "[" (simple-type (second (re-matches #"(?:List|Collection|Set|Iterable)<(.+)>" t))) "]")
      (re-matches #"Map<([^,]+),(.+)>" t) (let [[_ k v] (re-matches #"Map<([^,]+),(.+)>" t)]
                                             (str "{" (simple-type k) ":" (simple-type v) "}"))
      (str/includes? t "<") (first (str/split t #"<"))
      :else (last (str/split t #"\.")))))

(defn relationship [field-type known-types]
  (let [t (simple-type field-type)]
    (cond
      (or (str/starts-with? t "[") (str/starts-with? t "{")) :ordinary
      (contains? known-types t) :ordinary
      (contains? primitive-types t) :ordinary
      :else :external)))

(defn schema-field [field known-types]
  (let [{:keys [name type static final visibility location]} field]
    {:name name
     :type type
     :wchnt-type (simple-type type)
     :relationship (relationship type known-types)
     :static static
     :final final
     :visibility visibility
     :location location}))

(defn- wchnt-descendants [parent-of name]
  (vec (mapcat (fn [child]
                 (cons child (wchnt-descendants parent-of child)))
               (keep (fn [[child parent]] (when (= parent name) child)) parent-of))))

(defn inheritance-view
  "Derive the WCHNT composition/sum-type view of Java class inheritance.
   The parsed Java model is left untouched; this returns report-oriented data."
  [classes]
  (let [by-name (into {} (map (juxt :name identity) classes))
        parent-of (into {} (keep (fn [c]
                                   (when-let [parent (first (:extends c))]
                                     [(:name c) parent])) classes))
        base-names (->> (vals parent-of) distinct (filter by-name))
        generated (mapv (fn [base]
                          {:name (str "I" base)
                           :generated? true
                           :generated-from base
                           :values (vec (cons base (wchnt-descendants parent-of base)))})
                        base-names)
        schema-classes (mapv (fn [class]
                               (let [parent (get parent-of (:name class))
                                     inferred (when parent
                                                {:name (str "inner" parent)
                                                 :type parent
                                                 :wchnt-type parent
                                                 :relationship :ordinary
                                                 :inferred? true
                                                 :location (:location class)})]
                                 (assoc class :schema-fields
                                        (vec (concat (map #(schema-field % (set (keys by-name)))
                                                          (remove :static (:fields class)))
                                                     (when inferred [inferred]))))))
                        classes)]
    {:classes schema-classes
     :generated-sum-types generated}))

(defn schema-line [class known-types]
  (let [fields (or (:schema-fields class)
                   (->> (:fields class)
                        (remove :static)
                        (map #(schema-field % known-types))))]
    (when (seq fields)
      (str (when (:mailbox? class) ">") (:name class) " = "
           (str/join " " (map (fn [{:keys [wchnt-type name relationship]}]
                                (let [prefix (case relationship :external "@" "")
                                      default-name (when (re-matches #"[A-Za-z][A-Za-z0-9_]*" wchnt-type)
                                                     (str (str/lower-case (subs wchnt-type 0 1))
                                                          (subs wchnt-type 1)))
                                      rendered-type (str prefix wchnt-type)]
                                  (if (= name default-name)
                                    rendered-type
                                    (str rendered-type "/" name)))) fields))))))

(defn interface-line [interface]
  (let [parents (seq (:extends interface))]
    (str "interface " (:name interface)
         (when parents (str " extends " (str/join ", " parents))))))

(defn review-notes [analysis]
  (vec (concat
        (when (some #(str/ends-with? (str/lower-case %) ".pde") (:files analysis))
          ["Processing .pde tabs were parsed as synthetic Java classes. Processing merges tabs into one sketch, so review cross-tab relationships and sketch lifecycle functions manually."])
        (when (seq (:errors analysis)) ["Some Java files could not be parsed; see the file inventory."])
        (for [c (:classes analysis) :when (seq (:static-fields c))]
          (str (:name c) " has static fields; they are listed but omitted from the instance schema."))
        (for [c (:classes analysis) :when (seq (:constructors c))]
          (str (:name c) " has constructors; Construction values must be designed manually."))
        (for [c (:classes analysis) :when (seq (:implements c))]
          (str (:name c) " implements " (str/join ", " (:implements c)) "; consider a WCHNT sum type/interface."))
        (for [s (:generated-sum-types analysis)]
          (str (:generated-from s) " inheritance became " (:name s)
               "; inherited behavior will need delegation through the inferred composition field.")))))
