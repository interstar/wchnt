(ns neo4j.generate
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [instaparse.core :as insta]
            [neo4j.explorer :as explorer])
  (:gen-class))

(def schema-grammar
  "
Schema = DefLine (<NL> DefLine)* <NL>?
DefLine = CompositionLine | DisjunctionLine | EnumLine
CompositionLine = Definee <SPACE> <'='> <SPACE> Element (<SPACE> Element)* <SPACE>?
DisjunctionLine = Definee <SPACE> <'='> <SPACE> Element (<SPACE> <'|'> <SPACE> Element)+ <SPACE>?
EnumLine = Definee <SPACE> <'='> <SPACE> <'\"'> EnumValue <'\"'> (<SPACE> <'|'> <SPACE> <'\"'> EnumValue <'\"'>)+ <SPACE>?
Definee = Name
<Name> = #'[A-Za-z][A-Za-z0-9_]*'
NL = #'\n+'
Element = ((Sigil Type) | TypeMarker) ('/' AltName)?
SPACE = #'\\s+'
TypeMarker = Name | ArrayType | MapType | EmptyType
ArrayType = <'['> (Type | MapType) <']'>
Type = Name
MapType =  <'{'> KeyType <SPACE>? <':'> <SPACE>? ValType <'}'>
KeyType = Name
ValType = Name | ArrayType
AltName = Name
EnumValue =  #'[^\"]+'
Sigil = ':'  | '@' | '$' | '!'
EmptyType = '_'
")

(def schema-parser (insta/parser schema-grammar))

(def primitive-types
  #{"String" "Int" "Float" "Bool" "Boolean"})

(defn lower-first [s]
  (str (str/lower-case (subs s 0 1)) (subs s 1)))

(defn strip-bom [s]
  (if (and s (str/starts-with? s "\uFEFF"))
    (subs s 1)
    s))

(defn normalize-schema [text]
  (let [clean (strip-bom text)]
    (->> (str/split-lines clean)
         (map str/trim)
         (remove str/blank?)
         (str/join "\n"))))

(defn parse-schema [text]
  (let [normalized (normalize-schema text)
        result (insta/parse schema-parser normalized)]
    (when (insta/failure? result)
      (throw (ex-info "Schema parse failed" {:error (insta/get-failure result)})))
    result))

(defn transform-element [& parts]
  (let [parts (remove #(= "/" %) parts)
        [a b c] parts
        sigil? (and (string? a) (#{":" "@" "$" "!"} a))
        type (if sigil? b a)
        alt (if sigil? c b)]
    {:sigil (when sigil? a)
     :type type
     :alt alt}))

(def schema-transform-rules
  {:Schema (fn [& defs] (vec defs))
   :DefLine identity
   :CompositionLine (fn [name & elements]
                      {:kind :composition :name name :elements (vec elements)})
   :DisjunctionLine (fn [name & elements]
                      {:kind :disjunction :name name :elements (vec elements)})
   :EnumLine (fn [name & values]
               {:kind :enum :name name :values (vec values)})
   :Definee identity
   :Element transform-element
   :TypeMarker identity
   :Type identity
   :ArrayType (fn [inner] {:kind :array :of inner})
   :MapType (fn [k v] {:kind :map :key k :val v})
   :EmptyType (constantly {:kind :empty})
   :KeyType identity
   :ValType identity
   :AltName identity
   :Name identity
   :EnumValue identity
   :Sigil identity})

(defn transform-schema [ast]
  (insta/transform schema-transform-rules ast))

(defn validate-typemarker! [element class-name]
  (when (map? (:type element))
    (throw (ex-info "Arrays, maps, and empty types are not supported yet"
                    {:class class-name :element element}))))

(defn field-name [type alt]
  (or alt (lower-first type)))

(defn merge-prefix [prefix maybe]
  (cond
    (and prefix maybe) (str prefix "_" maybe)
    prefix prefix
    maybe maybe
    :else nil))

(defn ensure-unique! [fields class-name]
  (let [names (map :name fields)
        dupes (->> names
                   frequencies
                   (filter (fn [[_ v]] (> v 1)))
                   (map first)
                   seq)]
    (when dupes
      (throw (ex-info (format "Duplicate field names after flattening in %s: %s"
                              class-name
                              (str/join ", " dupes))
                      {:class class-name
                       :duplicates dupes
                       :fields names})))))

(declare flatten-fields)
(declare relation-field)

(defn primitive-field [type alt prefix required]
  (let [base (field-name type alt)
        name (if prefix (str prefix "_" base) base)]
    {:kind :property :name name :type type :required required}))

(defn flatten-element [schema element class-name prefix visited]
  (validate-typemarker! element class-name)
  (let [type (:type element)
        sigil (:sigil element)]
    (cond
      (primitive-types type)
      [(primitive-field type (:alt element) prefix (= "!" sigil))]

      (= ":" sigil)
      (flatten-fields schema type (merge-prefix prefix (:alt element)) visited)

      :else
      [(relation-field type (:alt element) prefix (= "!" sigil))])))

(defn flatten-fields [schema class-name prefix visited]
  (when (visited class-name)
    (throw (ex-info "Cycle detected while flattening"
                    {:class class-name :visited visited})))
  (let [definition (get schema class-name)]
    (when (or (nil? definition) (not= :composition (:kind definition)))
      (throw (ex-info "Flatten target must be a composition class"
                      {:class class-name})))
    (let [next-visited (conj visited class-name)
          fields (mapcat (fn [el]
                           (flatten-element schema el class-name prefix next-visited))
                         (:elements definition))]
      fields)))

(defn relation-field [type alt prefix required]
  (let [base (field-name type alt)
        name-base (merge-prefix prefix base)
        rel-base (or name-base base)]
    {:kind :relation
     :name (str name-base "_id")
     :hint-name (str name-base "_hint")
     :target type
     :rel-type (str/upper-case rel-base)
     :base rel-base
     :required required}))

(defn class-fields [schema class-name]
  (let [definition (get schema class-name)]
    (if (or (nil? definition) (not= :composition (:kind definition)))
      []
      (let [fields (mapcat (fn [el]
                             (validate-typemarker! el class-name)
                             (let [type (:type el)
                                   sigil (:sigil el)]
                               (cond
                               (primitive-types type)
                               [(primitive-field type (:alt el) nil (= "!" sigil))]

                                 (= ":" sigil)
                                 (flatten-fields schema type (:alt el) #{class-name})

                               :else
                               [(relation-field type (:alt el) nil (= "!" sigil))])))
                           (:elements definition))]
        (ensure-unique! fields class-name)
        fields))))

(defn unique-names [names]
  (reduce (fn [{:keys [seen result]} name]
            (let [count (get seen name 0)
                  next-count (inc count)
                  unique (if (zero? count) name (str name "_" next-count))]
              {:seen (assoc seen name next-count)
               :result (conj result unique)}))
          {:seen {} :result []}
          names))

(defn cypher-query [class-name fields]
  (let [properties (filter #(= :property (:kind %)) fields)
        relations (filter #(= :relation (:kind %)) fields)
        rel-bases (map :base relations)
        rel-vars (:result (unique-names rel-bases))
        rels (map (fn [rel var]
                    (assoc rel :var var :rel-var (str var "_rel")))
                  relations rel-vars)
        lines (-> [(format "MERGE (p:%s {uid: $uid})" class-name)]
                  (cond-> (seq properties)
                    (conj (str "SET "
                               (str/join ",\n    "
                                         (map (fn [p]
                                                (format "p.%s = $%s" (:name p) (:name p)))
                                              properties)))))
                  (cond-> (seq rels)
                    (into (concat ["WITH p"]
                                  (map (fn [r]
                                         (format "MATCH (%s:%s {uid: $%s})"
                                                 (:var r) (:target r) (:name r)))
                                       rels)
                                  (map (fn [r]
                                         (format "MERGE (p)-[%s:%s]->(%s)"
                                                 (:rel-var r) (:rel-type r) (:var r)))
                                       rels)
                                  (map (fn [r]
                                         (format "SET %s.hint = $%s"
                                                 (:rel-var r) (:hint-name r)))
                                       rels)))))]
    (str/join "\n" lines)))

(defn python-function [class-name fields]
  (let [params (vec (concat ["tx" "uid"]
                            (mapcat (fn [f]
                                      (if (= :relation (:kind f))
                                        [(:name f) (:hint-name f)]
                                        [(:name f)]))
                                    fields)))
        query (cypher-query class-name fields)
        args (vec (concat ["uid=uid"]
                          (mapcat (fn [f]
                                    (if (= :relation (:kind f))
                                      [(str (:name f) "=" (:name f))
                                       (str (:hint-name f) "=" (:hint-name f))]
                                      [(str (:name f) "=" (:name f))]))
                                  fields)))
        body (str "    tx.run(\n"
                  "        \"\"\"\n"
                  (str/join "\n" (map (fn [line] (str "        " line))
                                      (str/split-lines query)))
                  "\n        \"\"\",\n"
                  "        " (str/join ",\n        " args) ",\n"
                  "    )")]
    (str "def upsert_" (str/lower-case class-name) "(" (str/join ", " params) "):\n"
         body
         "\n")))

(defn python-upsert-header []
  (str "# Generated from WCHNT schema\n"
       "_uid_counter = 0\n\n"
       "def genUID():\n"
       "    global _uid_counter\n"
       "    _uid_counter += 1\n"
       "    return _uid_counter\n\n"))

(defn sql-type [type]
  (case type
    "String" "TEXT"
    "Int" "INTEGER"
    "Float" "REAL"
    "Bool" "BOOLEAN"
    "Boolean" "BOOLEAN"
    "TEXT"))

(defn sql-column-definition [field]
  (if (= :relation (:kind field))
    (format "%s INTEGER%s"
            (:name field)
            (if (:required field) " NOT NULL" ""))
    (format "%s %s%s"
            (:name field)
            (sql-type (:type field))
            (if (:required field) " NOT NULL" ""))))

(defn sql-hint-column [field]
  (when (= :relation (:kind field))
    (format "%s TEXT" (:hint-name field))))

(defn sql-foreign-key [field]
  (when (= :relation (:kind field))
    (format "FOREIGN KEY(%s) REFERENCES %s(uid)"
            (:name field)
            (:target field))))

(defn sql-table-statement [class-name fields]
  (let [columns (concat
                  [(format "uid INTEGER PRIMARY KEY")]
                  (map sql-column-definition fields)
                  (map sql-hint-column fields)
                  (map sql-foreign-key fields))
        column-lines (->> columns
                          (remove nil?)
                          (str/join ",\n    "))]
    (format "CREATE TABLE IF NOT EXISTS %s (\n    %s\n);"
            class-name
            column-lines)))

(defn sql-create-tables [schema]
  (let [classes (->> (vals schema)
                     (filter #(= :composition (:kind %)))
                     (map :name)
                     sort)
        table-sql (for [class-name classes
                        :let [fields (class-fields schema class-name)]]
                    (sql-table-statement class-name fields))]
    (str "def sql_create_tables():\n"
         "    return \"\"\"\n"
         (str/join "\n\n" table-sql)
         "\n\"\"\"\n\n")))

(defn schema-meta [schema]
  (let [classes (->> (vals schema)
                     (filter #(= :composition (:kind %)))
                     (map :name)
                     sort)
        schema-map (into {}
                         (map (fn [class-name]
                                (let [fields (class-fields schema class-name)
                                      properties (->> fields
                                                      (filter #(= :property (:kind %)))
                                                      (map :name)
                                                      vec)
                                      relations (->> fields
                                                     (filter #(= :relation (:kind %)))
                                                     (map (fn [r]
                                                            (str "{"
                                                                 "\"name\": " (pr-str (:name r)) ", "
                                                                 "\"target\": " (pr-str (:target r)) ", "
                                                                 "\"rel_type\": " (pr-str (:rel-type r))
                                                                 "}")))
                                                     vec)]
                                  [class-name {:properties properties
                                               :relations relations}])))
                         classes)]
    {:class-list classes
     :schema-map schema-map}))

(defn generate-python [schema]
  (let [{:keys [class-list schema-map]} (schema-meta schema)
        functions (map (fn [class-name]
                         (python-function class-name (class-fields schema class-name)))
                       class-list)
        sql-fn (sql-create-tables schema)
        web (explorer/python-webserver class-list schema-map)]
    {:upserts (str (python-upsert-header)
                   (str/join "\n" functions)
                   "\n"
                   sql-fn)
     :explorer web}))

(defn schema-json-data [schema]
  (let [classes (->> (vals schema)
                     (filter #(= :composition (:kind %)))
                     (map :name)
                     sort)]
    (into {}
          (map (fn [class-name]
                 (let [fields (class-fields schema class-name)
                       field-map (into {}
                                       (map (fn [field]
                                              (let [base {:kind (if (= :relation (:kind field))
                                                                  "foreignkey"
                                                                  "primitive")}]
                                               [(:name field)
                                                (cond
                                                   (= :relation (:kind field))
                                                   (merge base {:type (:target field)
                                                                :hint (:hint-name field)
                                                                :required (boolean (:required field))})
                                                   :else
                                                   (merge base {:type (:type field)
                                                                :required (boolean (:required field))}))]))
                                            fields))]
                   [class-name field-map])))
          classes)))

(defn -main [& args]
  (let [[schema-path] args]
    (when (nil? schema-path)
      (binding [*out* *err*]
        (println "Usage: lein run <schema-file>"))
      (System/exit 1))
    (let [text (slurp schema-path)
          ast (parse-schema text)
          defs (transform-schema ast)
          schema (into {} (map (juxt :name identity) defs))
          {:keys [upserts explorer]} (generate-python schema)
          out-file (java.io.File. "schema.py")
          out-dir (.getParentFile out-file)
          base-dir (if out-dir (.getPath out-dir) ".")
          explorer-path (str base-dir "/explorer.py")
          schema-path (str base-dir "/schema.json")]
      (spit (.getPath out-file) upserts)
      (spit explorer-path explorer)
      (spit schema-path (json/write-str (schema-json-data schema) :escape-slash false)))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
