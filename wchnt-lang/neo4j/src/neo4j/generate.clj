(ns neo4j.generate
  (:require [clojure.string :as str]
            [instaparse.core :as insta])
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
Sigil = ':'  | '@' | '$'
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

(defn transform-schema [ast]
  (insta/transform
    {:Schema (fn [& defs] (vec defs))
     :DefLine identity
     :CompositionLine (fn [name & elements]
                        {:kind :composition :name name :elements (vec elements)})
     :DisjunctionLine (fn [name & elements]
                        {:kind :disjunction :name name :elements (vec elements)})
     :EnumLine (fn [name & values]
                 {:kind :enum :name name :values (vec values)})
     :Definee identity
     :Element (fn [& parts]
                (let [parts (remove #(= "/" %) parts)
                      [a b c] parts
                      sigil? (and (string? a) (#{":" "@" "$"} a))
                      type (if sigil? b a)
                      alt (if sigil? c b)]
                  {:sigil (when sigil? a)
                   :type type
                   :alt alt}))
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
     :Sigil identity}
    ast))

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

(defn primitive-field [type alt prefix]
  (let [base (field-name type alt)
        name (if prefix (str prefix "_" base) base)]
    {:kind :property :name name :type type}))

(defn flatten-element [schema element class-name prefix visited]
  (validate-typemarker! element class-name)
  (let [type (:type element)
        sigil (:sigil element)]
    (cond
      (primitive-types type)
      [(primitive-field type (:alt element) prefix)]

      (= ":" sigil)
      (flatten-fields schema type (merge-prefix prefix (:alt element)) visited)

      :else
      (throw (ex-info "Cannot flatten non-primitive component"
                      {:class class-name :element element})))))

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

(defn relation-field [type alt]
  (let [base (field-name type alt)]
    {:kind :relation
     :name (str base "_id")
     :target type
     :rel-type (str/upper-case base)
     :base base}))

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
                               [(primitive-field type (:alt el) nil)]

                               (= ":" sigil)
                               (flatten-fields schema type (:alt el) #{class-name})

                               :else
                               [(relation-field type (:alt el))])))
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
                    (assoc rel :var var))
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
                                         (format "MERGE (p)-[:%s]->(%s)" (:rel-type r) (:var r)))
                                       rels)))))]
    (str/join "\n" lines)))

(defn python-function [class-name fields]
  (let [params (vec (concat ["tx" "uid"] (map :name fields)))
        query (cypher-query class-name fields)
        args (vec (concat ["uid=uid"]
                          (map (fn [f]
                                 (str (:name f) "=" (:name f)))
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

(defn python-explorer-header []
  (str "# Generated from WCHNT schema\n"
       "import atexit\n"
       "import html\n"
       "from pathlib import Path\n"
       "from urllib.parse import quote\n\n"
       "from bottle import Bottle, response, run, request, static_file\n"
       "from neo4j import GraphDatabase\n\n"
       "# Neo4j credentials\n"
       "URI = \"bolt://localhost:7687\"\n"
       "AUTH = (\"neo4j\", \"testpass\")\n\n"
       "driver = GraphDatabase.driver(URI, auth=AUTH)\n"
       "app = Bottle()\n"
       "atexit.register(driver.close)\n"
       "STATIC_ROOT = Path(__file__).resolve().parent / \"static\"\n"
       "NODE_ROOT = STATIC_ROOT / \"vendor\" / \"esm\" / \"node\"\n\n"
       "def link_to_node(label, uid, text=None):\n"
       "    if uid is None or label is None:\n"
       "        return \"\"\n"
       "    title = text if text is not None else f\"{label} {uid}\"\n"
       "    return f\"<a href='/node/{quote(label)}/{quote(str(uid))}'>\" + html.escape(str(title)) + \"</a>\"\n\n"
       "def render_page(title, body):\n"
       "    return (\"<!doctype html><html><head><meta charset='utf-8'>\"\n"
       "            f\"<title>{html.escape(title)}</title>\"\n"
       "            \"<style>body{font-family:system-ui, sans-serif;margin:24px;}\"\n"
       "            \"table{border-collapse:collapse;width:100%;}\"\n"
       "            \"th,td{border:1px solid #ccc;padding:6px 8px;text-align:left;}\"\n"
       "            \"a{text-decoration:none;color:#1a5;}\"\n"
       "            \"</style></head><body>\"\n"
       "            f\"<h1>{html.escape(title)}</h1>\" + body + \"</body></html>\")\n\n"
       "def parse_uid(value):\n"
       "    try:\n"
       "        return int(value)\n"
       "    except (TypeError, ValueError):\n"
       "        return value\n\n"))

(defn python-schema [schema]
  (let [classes (->> (vals schema)
                     (filter #(= :composition (:kind %)))
                     (map :name)
                     sort)]
    (str "SCHEMA = {\n"
         (str/join ""
                   (for [class-name classes
                         :let [fields (class-fields schema class-name)
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
                                              vec)]]
                     (str "    " (pr-str class-name) ": {\n"
                          "        \"properties\": [" (str/join ", " (map pr-str properties)) "],\n"
                          "        \"relations\": [" (str/join ", " relations) "],\n"
                          "    },\n")))
         "}\n\n"
         "CLASS_LIST = [" (str/join ", " (map pr-str classes)) "]\n\n")))

(defn python-webserver [schema]
  (str (python-explorer-header)
       (python-schema schema)
       "@app.route('/')\n"
       "def index():\n"
       "    items = ''.join([f\"<li><a href='/class/{quote(name)}'>\" + html.escape(name) + \"</a></li>\" for name in CLASS_LIST])\n"
       "    return render_page('Classes', f\"<ul>{items}</ul>\")\n\n"
       "@app.route('/class/<label>')\n"
       "def class_list(label):\n"
       "    meta = SCHEMA.get(label)\n"
       "    if meta is None:\n"
       "        response.status = 404\n"
       "        return render_page('Not Found', 'Unknown class')\n"
       "    props = meta['properties']\n"
       "    cols = ['uid'] + props\n"
       "    select_cols = ', '.join([f\"n.{c} AS {c}\" for c in cols])\n"
       "    query = f\"MATCH (n:{label}) RETURN {select_cols} ORDER BY n.uid LIMIT 200\"\n"
       "    with driver.session() as session:\n"
       "        rows = [dict(record) for record in session.run(query)]\n"
       "    header = ''.join([f\"<th>{html.escape(c)}</th>\" for c in cols])\n"
       "    body_rows = []\n"
       "    for row in rows:\n"
       "        cells = []\n"
       "        uid = row.get('uid')\n"
       "        cells.append(f\"<td>{link_to_node(label, uid, uid)}</td>\")\n"
       "        for c in props:\n"
       "            cells.append(f\"<td>{html.escape(str(row.get(c)))}</td>\")\n"
       "        body_rows.append('<tr>' + ''.join(cells) + '</tr>')\n"
       "    table = f\"<table><thead><tr>{header}</tr></thead><tbody>{''.join(body_rows)}</tbody></table>\"\n"
       "    return render_page(f\"{label} List\", table)\n\n"
       "@app.route('/node/<label>/<uid>')\n"
       "def node_detail(label, uid):\n"
       "    uid_value = parse_uid(uid)\n"
       "    query = (\"MATCH (n:\" + label + \" {uid: $uid}) \"\n"
       "             \"OPTIONAL MATCH (n)-[r]->(o) \"\n"
       "             \"WITH n, collect({dir:'out', type:type(r), label:head(labels(o)), uid:o.uid}) AS outs \"\n"
       "             \"OPTIONAL MATCH (i)-[r2]->(n) \"\n"
       "             \"WITH n, outs, collect({dir:'in', type:type(r2), label:head(labels(i)), uid:i.uid}) AS ins \"\n"
       "             \"RETURN n, outs, ins\")\n"
       "    with driver.session() as session:\n"
       "        record = session.run(query, uid=uid_value).single()\n"
       "    if record is None or record.get('n') is None:\n"
       "        response.status = 404\n"
       "        return render_page('Not Found', 'Node not found')\n"
       "    node = record.get('n')\n"
       "    props = dict(node)\n"
       "    prop_rows = ''.join([f\"<tr><th>{html.escape(str(k))}</th><td>{html.escape(str(v))}</td></tr>\" for k, v in props.items()])\n"
       "    prop_table = f\"<table><tbody>{prop_rows}</tbody></table>\"\n"
       "    links = []\n"
       "    for item in record.get('outs') or []:\n"
       "        if item.get('uid') is not None and item.get('label') is not None:\n"
       "            links.append(f\"<li>out {html.escape(item.get('type'))}: \" + link_to_node(item.get('label'), item.get('uid')) + \"</li>\")\n"
       "    for item in record.get('ins') or []:\n"
       "        if item.get('uid') is not None and item.get('label') is not None:\n"
       "            links.append(f\"<li>in {html.escape(item.get('type'))}: \" + link_to_node(item.get('label'), item.get('uid')) + \"</li>\")\n"
       "    rels = f\"<ul>{''.join(links)}</ul>\" if links else \"<p>No relations.</p>\"\n"
       "    body = prop_table + \"<h2>Relations</h2>\" + rels\n"
       "    return render_page(f\"{label} {uid}\", body)\n\n"
       "@app.route('/static/<filepath:path>')\n"
       "def server_static(filepath):\n"
       "    return static_file(filepath, root=STATIC_ROOT)\n\n"
       "if __name__ == '__main__':\n"
       "    run(app, host='127.0.0.1', port=8080, debug=True)\n"))

(defn generate-python [schema]
  (let [classes (->> (vals schema)
                     (filter #(= :composition (:kind %)))
                     (map :name)
                     sort)
        functions (map (fn [class-name]
                         (python-function class-name (class-fields schema class-name)))
                       classes)
        web (python-webserver schema)]
    {:upserts (str (python-upsert-header)
                   (str/join "\n" functions))
     :explorer web}))

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
          {:keys [upserts explorer]} (generate-python schema)]
      (let [out-file (java.io.File. "schema.py")
            out-dir (.getParentFile out-file)
            base-dir (if out-dir (.getPath out-dir) ".")
            explorer-path (str base-dir "/explorer.py")]
        (spit (.getPath out-file) upserts)
        (spit explorer-path explorer)))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
