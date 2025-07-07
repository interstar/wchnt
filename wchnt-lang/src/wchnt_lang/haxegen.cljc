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
  (println "DEBUG: [string-literal->haxe] children:" (pr-str children))
  (let [result (str "\"" (second children) "\"")]
    (println "DEBUG: [string-literal->haxe] result:" (pr-str result))
    result))

(defn enum-value->haxe [tag children enums]
  (if (and (str/ends-with? (name tag) "Value")
           (some #(= (:name %) (str/replace (name tag) "Value" "")) enums))
    (str (first children))
    nil))

(defn local-empty->haxe [ast]
  (let [empty-class (second ast)]
    (str "new " empty-class "()")))

(defn variable-reference->haxe [ast]
  "Convert variable reference AST to Haxe variable name"
  (println "DEBUG: [variable-reference->haxe] ast:" (pr-str ast))
  (let [var-name (nth ast 2)]  ;; Extract variable name from [:VariableReference "$" "people"]
    (println "DEBUG: [variable-reference->haxe] extracted var-name:" var-name)
    var-name))

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
    
    ;; Check if this is an array construction (ends with "ArrayConstruction")
    (if (str/ends-with? (name tag) "ArrayConstruction")
      ;; Handle as array construction
      (let [array-elements (filter vector? children)
            ;; Filter out TypeName nodes and only keep actual array elements
            actual-elements (filter #(not= (first %) :TypeName) array-elements)
            haxe-elements (map #(ast-to-haxe-factory % class-info) actual-elements)]
        (str "[" (str/join ", " haxe-elements) "]"))
              ;; Check if this is a map construction (ends with "MapConstruction")
      (if (str/ends-with? (name tag) "MapConstruction")
        ;; Handle as map construction
        (let [map-elements (filter vector? children)
              ;; Filter out TypeName nodes and only keep actual map elements
              actual-elements (filter #(and (vector? %) 
                                           (re-matches #".*To.*MapElement$" (name (first %)))) map-elements)
              ;; Extract key and value types from the type information
              type-info (filter #(and (vector? %) (= (first %) :TypeName)) children)
              key-type (second (first type-info))
              value-type (second (second type-info))
              ;; Generate map entries as .set() calls
              set-calls (map #(let [element-haxe (ast-to-haxe-factory % class-info)]
                               (str ".set(" element-haxe ")")) actual-elements)
              haxe-code (str "new Map<" key-type ", " value-type ">()" (str/join "" set-calls))]
          (println "DEBUG: [MapConstruction] generated haxe-code:" haxe-code)
          haxe-code)
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
  "Convert construction AST to Haxe factory function code (handles XxxConstruction, XxxArrayConstruction, etc.)"
  (let [classes (:classes class-info)
        enums (:enums class-info)]
    (cond
      (string? ast)
      ast

      (keyword? ast)
      (name ast)

      (vector? ast)
      (let [[tag & children] ast]
        (cond
          ;; Handle :Construction wrapper (unwrap, no new)
          (= tag :Construction)
          (ast-to-haxe-factory (first children) class-info)

          ;; Handle ArrayConstruction specifically
          (= tag :ArrayConstruction)
          (let [array-elements (filter vector? children)
                ;; Filter out TypeName nodes and only keep actual array elements
                actual-elements (filter #(not= (first %) :TypeName) array-elements)
                haxe-elements (map #(ast-to-haxe-factory % class-info) actual-elements)]
            (str "[" (str/join ", " haxe-elements) "]"))

          ;; Handle MapConstruction specifically
          (= tag :MapConstruction)
          (let [map-elements (filter vector? children)
                ;; Filter out TypeName nodes and only keep actual map elements
                actual-elements (filter #(and (vector? %) 
                                             (re-matches #".*To.*MapElement$" (name (first %)))) map-elements)
                ;; Extract key and value types from the type information
                type-info (filter #(and (vector? %) (= (first %) :TypeName)) children)
                key-type (second (first type-info))
                value-type (second (second type-info))
                ;; Generate map entries as .set() calls
                set-calls (map #(let [element-haxe (ast-to-haxe-factory % class-info)]
                                 (str ".set(" element-haxe ")")) actual-elements)
                haxe-code (str "new Map<" key-type ", " value-type ">()" (str/join "" set-calls))]
            (println "DEBUG: [MapConstruction] generated haxe-code:" haxe-code)
            haxe-code)

          ;; Handle XxxToYyyMapElement nodes (e.g., :DirectionToStringMapElement)
          (and (keyword? tag) (re-matches #".*To.*MapElement$" (name tag)))
          (let [key-value-pair (filter vector? children)
                key (first key-value-pair)
                value (second key-value-pair)
                key-haxe (ast-to-haxe-factory key class-info)
                value-haxe (ast-to-haxe-factory value class-info)
                result (str "[" key-haxe ", " value-haxe "]")]
            (println "DEBUG: [XxxToYyyMapElement] key:" key-haxe "value:" value-haxe "result:" result)
            result)

          ;; Handle XxxArrayElement nodes (e.g., :PlayerArrayElement, :TeamArrayElement)
          (and (keyword? tag) (re-matches #".*ArrayElement$" (name tag)))
          (let [class-name (-> (name tag)
                               (str/replace #"ArrayElement$" ""))
                haxe-args (map #(ast-to-haxe-factory % class-info) (remove string? children))]
            (str "new " class-name "(" (str/join ", " haxe-args) ")"))

          ;; Handle XxxArrayConstruction (e.g., :StudentsArrayConstruction)
          (and (keyword? tag) (re-matches #".*ArrayConstruction$" (name tag)))
          (let [array-elems (map #(ast-to-haxe-factory % class-info) (remove string? children))]
            (str "[" (str/join ", " array-elems) "]"))

          ;; Handle XxxConstruction (e.g., :SchoolConstruction), but not ArrayConstruction
          (and (keyword? tag)
               (re-matches #".*Construction$" (name tag))
               (not (re-matches #".*ArrayConstruction$" (name tag))))
          (let [class-name (-> (name tag)
                               (str/replace #"Construction$" ""))
                haxe-args (map #(ast-to-haxe-factory % class-info) (remove string? children))]
            (str "new " class-name "(" (str/join ", " haxe-args) ")"))

          ;; Handle StringLiteral
          (= tag :StringLiteral)
          (string-literal->haxe children)

          ;; Handle IntLiteral
          (= tag :IntLiteral)
          (int-literal->haxe children)

          ;; Handle VariableReference
          (= tag :VariableReference)
          (variable-reference->haxe ast)

          ;; Handle LocalEmpty
          (= tag :LocalEmpty)
          (local-empty->haxe ast)

          ;; Fallback: generic node
          :else
          (generic-node->haxe children ast-to-haxe-factory class-info)))

      (seq? ast)
      (when (and (not (vector? ast)) (not (keyword? ast)))
        (str/join " " (map #(ast-to-haxe-factory % class-info) ast)))

      :else
      (str ast))))

(defn generate-construction-factory-impl [schema-input construction-input]
  "Implementation of generate-construction-factory without exception handling"
  (let [parse-result (parser/parse-construction schema-input construction-input)]
    (if (:success parse-result)
      (let [ast (:ast parse-result)]
        (println "DEBUG: [generate-construction-factory-impl] AST:" (pr-str ast))
        ;; Handle multi-step construction AST
        (if (= (:type ast) :MultiStepConstruction)
          (let [class-info (parser/extract-class-info ((parser/get-parser) schema-input))
                ;; Generate variable declarations for assignments
                variable-declarations (for [assignment (:assignments ast)]
                                      (let [var-name (:name assignment)
                                            construction (:construction assignment)
                                            haxe-value (ast-to-haxe-factory construction class-info)]
                                        (do (println "DEBUG: [factory] var decl:" var-name "=" haxe-value)
                                            (str "var " var-name " = " haxe-value ";"))))
                ;; Generate final construction
                final-haxe (ast-to-haxe-factory (:final-construction ast) class-info)]
            (println "DEBUG: [factory] final construction:" final-haxe)
            (let [factory-code (str "public static function factory() {\n"
                                   "    " (str/join "\n    " variable-declarations) "\n"
                                   "    return " final-haxe ";\n"
                                   "}")]
                              (println "DEBUG: [factory] generated factory-code:\n" factory-code)
                (schema/syntax-success ast {:haxe-code factory-code})))
          ;; Handle single construction AST (legacy)
          (let [class-info (parser/extract-class-info ((parser/get-parser) schema-input))
                haxe-code (ast-to-haxe-factory ast class-info)]
                          (println "DEBUG: [factory] single construction haxe-code:" haxe-code)
              (schema/syntax-success ast {:haxe-code haxe-code}))))
      parse-result)))

(defn generate-construction-factory [schema-input construction-input]
  "Generate Haxe factory function from schema and construction input"
  #?(:clj
     (try
       (generate-construction-factory-impl schema-input construction-input)
       (catch Exception e
         (println "DEBUG: caught exception:" e)
         (schema/syntax-error (str "Error generating factory: " (.getMessage e)))))
     :cljs
     (try
       (generate-construction-factory-impl schema-input construction-input)
       (catch :default e
         (println "DEBUG: caught exception:" e)
         (schema/syntax-error (str "Error generating factory: " (.-message e))))))) 