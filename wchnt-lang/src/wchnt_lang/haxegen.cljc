(ns wchnt-lang.haxegen
  (:require [clojure.string :as str]
            [instaparse.core :as insta]
            [wchnt-lang.parser :as parser]
            [wchnt-lang.schema :as schema]
            [clojure.pprint :refer [pprint]]))

(defn generate-haxe-class [class-name elements]
  (let [has-context-components (some #(= (:sigil %) ":") elements)
        import-statement (when has-context-components "import wchnt.*;")
        fields (for [element elements]
                 (let [field-name (:name element)
                       field-type (:type element)
                       sigil (:sigil element)]
                   (case sigil
                     ":" (str "    public var " field-name ": LazyContext<" field-type ">;")
                     (str "    public var " field-name ": " field-type ";"))))
        constructor-params (for [element elements]
                            (let [field-name (:name element)
                                  field-type (:type element)
                                  sigil (:sigil element)]
                              (case sigil
                                ":" (str "LazyContext<" field-type "> " field-name)
                                (str field-type " " field-name))))
        constructor-body (for [element elements]
                          (let [field-name (:name element)
                                sigil (:sigil element)]
                            (case sigil
                              ":" (str "        this." field-name " = " field-name ";")
                              (str "        this." field-name " = " field-name ";"))))
        class-code (str "class " class-name " {\n"
                       (when import-statement (str import-statement "\n"))
                       (str/join "\n" fields)
                       "\n\n"
                       "    public function new(" (str/join ", " constructor-params) ") {\n"
                       (str/join "\n" constructor-body)
                       "\n    }\n"
                       "}")]
    class-code))

(defn generate-haxe-interface [interface-name]
  (str "interface " interface-name " {\n"
       "    // Interface for " interface-name "\n"
       "}"))

(defn generate-haxe-enum [enum-name enum-values]
  (let [processed-values (for [value enum-values]
                          (let [raw-value (str/trim value)
                                enum-value-name (-> raw-value
                                                   (str/replace #"[^A-Za-z0-9]" "")
                                                   (str/replace #"^[a-z]" str/upper-case))]
                            enum-value-name))
        enum-code (str "enum " enum-name " {\n"
                      (str/join "\n" (map #(str "    " % ";") processed-values))
                      "\n}")]
    enum-code))

(defn generate-haxe-class-implementing [class-name interface-name elements]
  (let [fields (for [element elements]
                 (let [field-name (:name element)
                       field-type (:type element)]
                   (str "    public var " field-name ": " field-type ";")))
        constructor-params (for [element elements]
                            (let [field-name (:name element)
                                  field-type (:type element)]
                              (str field-type " " field-name)))
        constructor-body (for [element elements]
                          (let [field-name (:name element)]
                            (str "        this." field-name " = " field-name ";")))
        class-code (str "class " class-name " implements " interface-name " {\n"
                       (str/join "\n" fields)
                       "\n\n"
                       "    public function new(" (str/join ", " constructor-params) ") {\n"
                       (str/join "\n" constructor-body)
                       "\n    }\n"
                       "}")]
    class-code))

(defn walk-tree [node interface-implementers]
  (cond
    (string? node) []
    (vector? node)
    (let [[tag & children] node]
      (case tag
        :Schema (vec (mapcat #(walk-tree % interface-implementers) (filter vector? children)))
        :DefLine (walk-tree (first (filter vector? children)) interface-implementers)
        :CompositionLine 
        (let [definee-node (first (filter #(= (first %) :Definee) children))
              class-name (second definee-node)
              element-nodes (filter #(= (first %) :Element) children)
              processed-elements (map wchnt-lang.parser/process-element element-nodes)
              ;; Find which interface this class should implement
              interface-to-implement (first (for [[interface implementers] interface-implementers
                                                  :when (implementers class-name)]
                                             interface))
              haxe-class (if interface-to-implement
                          (generate-haxe-class-implementing class-name interface-to-implement processed-elements)
                          (generate-haxe-class class-name processed-elements))]
          [haxe-class])
        :DisjunctionLine
        (let [definee-node (first (filter #(= (first %) :Definee) children))
              interface-name (second definee-node)
              element-nodes (filter #(= (first %) :Element) children)
              type-names (map #(second (wchnt-lang.parser/find-node :Type %)) element-nodes)
              interface-code (generate-haxe-interface interface-name)]
          [interface-code])
        :EnumLine
        (let [definee-node (first (filter #(= (first %) :Definee) children))
              enum-name (second definee-node)
              enum-value-nodes (filter #(= (first %) :EnumValue) children)
              enum-values (map second enum-value-nodes)]
          (let [enum-code (generate-haxe-enum enum-name enum-values)]
            [enum-code]))
        :Definee []
        :Element []
        :Type []
        :ArrayType []
        :AltName []
        (vec (mapcat #(walk-tree % interface-implementers) (filter vector? children)))))
    :else []))

(defn compile-to-haxe [input]
  (try
    (let [parse-result ((parser/get-parser) input)
          ;; First pass: collect interface-to-implementers mapping from disjunction lines
          interface-implementers (into {} (for [line (str/split-lines input)
                                               :when (str/includes? line "|")]
                                           (let [parts (str/split (str/trim line) #"\s*=\s*")
                                                 interface-name (first parts)
                                                 implementers (str/split (second parts) #"\s*\|\s*")]
                                             [interface-name (set implementers)])))]
      (if (insta/failure? parse-result)
        (schema/error-result (str input " is not a valid string in wchnt: " (insta/get-failure parse-result)))
        (let [classes (walk-tree parse-result interface-implementers)]
          (schema/success-result classes))))
    (catch #?(:clj Exception :cljs :default) e
      (schema/error-result (str "Error during compilation: " e)))))

(defn int-literal->haxe [children]
  (str (first children)))

(defn string-literal->haxe [children]
  ;; The children are [quote content quote], so we want the middle element
  (str "\"" (second children) "\""))

(defn enum-value->haxe [tag children enums]
  (if (and (str/ends-with? (name tag) "Value")
           (some #(= (:name %) (str/replace (name tag) "Value" "")) enums))
    (str (first children))
    nil))

(defn local-empty->haxe [ast]
  (let [empty-class (second ast)]
    (str "new " empty-class "()")))

(defn unwrap-args [args]
  (if (and (= 1 (count args))
           (vector? (first args))
           (= (first (first args)) (keyword ":")))
    (drop 2 (first args))
    args))

(defn construction-node->haxe [tag children class-info ast-to-haxe-factory]
  (let [classes (:classes class-info)
        class-name (str/replace (name tag) "Construction" "")
        class-info-item (first (filter #(= (:name %) class-name) classes))
        elements (:elements class-info-item)
        ;; Filter out string children (like brackets)
        non-string-children (filter vector? children)
        first-vector (first non-string-children)
        args (if (and (vector? first-vector)
                      (= (first first-vector) (keyword ":")))
               (drop 2 first-vector)
               non-string-children)]
    (println "DEBUG: [construction-node->haxe] class-name=" class-name)
    (print   "DEBUG: [construction-node->haxe] classes= ") (pprint classes)
    (print   "DEBUG: [construction-node->haxe] elements= ") (pprint elements)
    (print   "DEBUG: [construction-node->haxe] args= ") (pprint args)
    (println "DEBUG: [construction-node->haxe] children=" children)
    (println "DEBUG: [construction-node->haxe] non-string-children=" non-string-children)
    (println "DEBUG: [construction-node->haxe] first-vector=" first-vector)
    
    ;; Check if this is an array construction (ends with "ArrayConstruction")
    (if (str/ends-with? (name tag) "ArrayConstruction")
      ;; Handle as array construction
      (let [array-elements (filter vector? children)
            haxe-elements (map #(ast-to-haxe-factory % class-info) array-elements)]
        (str "[" (str/join ", " haxe-elements) "]"))
      ;; Check if this is a map construction (ends with "MapConstruction")
      (if (str/ends-with? (name tag) "MapConstruction")
        ;; Handle as map construction
        (let [map-entries (filter vector? children)
              ;; For map constructions, we need to get the field info from the parent class
              ;; The map field name is the class-name without "Map" suffix, converted to lowercase
              field-name (str/lower-case (str/replace class-name "Map" ""))
              ;; Find the parent class that contains this map field
              parent-class (first (filter #(some (fn [element] (= (:name element) field-name)) (:elements %)) classes))
              _ (when (nil? parent-class)
                  (throw (ex-info (str "Could not find parent class containing map field '" field-name "'") 
                                 {:field-name field-name :available-classes (map :name classes)})))
              map-field (first (filter #(= (:name %) field-name) (:elements parent-class)))
              _ (when (nil? map-field)
                  (throw (ex-info (str "Could not find map field '" field-name "' in parent class '" (:name parent-class) "'")
                                 {:field-name field-name :parent-class (:name parent-class) :available-elements (map :name (:elements parent-class))})))
              key-type (:key-type map-field)
              _ (when (nil? key-type)
                  (throw (ex-info (str "Map field '" field-name "' has no key-type information")
                                 {:field-name field-name :map-field map-field})))
              value-type (:value-type map-field)
              _ (when (nil? value-type)
                  (throw (ex-info (str "Map field '" field-name "' has no value-type information")
                                 {:field-name field-name :map-field map-field})))
              haxe-entries (for [i (range 0 (count map-entries) 2)]
                            (let [key (ast-to-haxe-factory (nth map-entries i) class-info)
                                  value (ast-to-haxe-factory (nth map-entries (inc i)) class-info)]
                              (str "[" key ", " value "]")))]
          (println "DEBUG: [map-construction] key-type =" (pr-str key-type))
          (println "DEBUG: [map-construction] value-type =" (pr-str value-type))
          (str "new Map<" key-type ", " value-type ">([" (str/join ", " haxe-entries) "])"))
        ;; Handle as regular class construction
        (let [haxe-args (map-indexed 
                       (fn [idx arg]
                         (let [element (nth elements idx)
                               element-type (:type element)
                               element-name (:name element)
                               sigil (:sigil element)
                               arg-expr (ast-to-haxe-factory arg class-info)]
                           (if sigil
                             (str "new LazyContext<" element-type ">(" arg-expr ")")
                             arg-expr)))
                         args)]
          (str "new " class-name "(" (str/join ", " haxe-args) ")"))))))

(defn generic-node->haxe [children ast-to-haxe-factory class-info]
  (str/join " " (map #(ast-to-haxe-factory % class-info) children)))

(defn ast-to-haxe-factory [ast class-info]
  "Convert construction AST to Haxe factory function code"
  (let [classes (:classes class-info)
        enums (:enums class-info)
        disjunctions (:disjunctions class-info)]
    (cond
      (string? ast) ast
      (vector? ast)
      (let [[tag & children] ast]
        (case tag
          :IntLiteral (int-literal->haxe children)
          :StringLiteral (string-literal->haxe children)
          :LocalEmpty (local-empty->haxe ast)
          ;; Enum value
          (or (enum-value->haxe tag children enums)
              ;; Construction node
              (if (str/ends-with? (name tag) "Construction")
                (construction-node->haxe tag children class-info ast-to-haxe-factory)
                ;; Generic node
                (generic-node->haxe children ast-to-haxe-factory class-info)))))
      (seq? ast) (str/join " " (map #(ast-to-haxe-factory % class-info) ast))
      :else (str ast))))

(defn generate-construction-factory [schema-input construction-input]
  "Generate Haxe factory function from schema and construction input"
  (try
    (let [parse-result (parser/parse-construction schema-input construction-input)]
      (println "DEBUG: parse-result =" parse-result)
      (println "DEBUG: parse-result success =" (:success parse-result))
      (if (:success parse-result)
        (let [ast (:ast parse-result)
              class-info (parser/extract-class-info ((parser/get-parser) schema-input))
              haxe-code (ast-to-haxe-factory ast class-info)]
          (println "DEBUG: generated haxe-code =" haxe-code)
          (schema/syntax-success {:haxe-code haxe-code
                                 :ast ast}))
        (do
          (println "DEBUG: parse failed, returning parse-result")
          parse-result)))
    (catch #?(:clj Exception :cljs :default) e
      (println "DEBUG: caught exception:" e)
      (schema/syntax-error (str "Error generating factory: " (.getMessage e)))))) 