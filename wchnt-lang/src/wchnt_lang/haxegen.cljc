(ns wchnt-lang.haxegen
  (:require [clojure.string :as str]
            [instaparse.core :as insta]
            [wchnt-lang.parser :as parser]
            [wchnt-lang.schema :as schema]))


(defn build-context-relationships [schema-ast]
  "Build a map of context-specific classes to their parent classes"
  (let [context-map (atom {})]
    (letfn [(walk-for-contexts [node parent-class]
              (when (vector? node)
                (let [[tag & children] node]
                  (case tag
                    :Schema 
                    (doseq [child (filter vector? children)]
                      (walk-for-contexts child parent-class))
                    :CompositionLine
                    (let [definee-node (first (filter #(= (first %) :Definee) children))
                          class-name (second definee-node)
                          element-nodes (filter #(= (first %) :Element) children)
                          processed-elements (map wchnt-lang.parser/process-element element-nodes)
                          context-elements (filter #(= (:sigil %) ":") processed-elements)]
                      ;; Record context relationships: context-specific components get this class as their context
                      (doseq [element context-elements]
                        (swap! context-map assoc (:type element) class-name))
                      ;; Initialize this class to nil only if it doesn't already have a context
                      (when (nil? (get @context-map class-name))
                        (swap! context-map assoc class-name nil))
                      ;; Recursively check nested contexts
                      (doseq [child (filter vector? children)]
                        (walk-for-contexts child class-name)))
                    :DisjunctionLine
                    (let [definee-node (first (filter #(= (first %) :Definee) children))
                          interface-name (second definee-node)]
                      ;; For disjunctions, we don't need to track contexts
                      (doseq [child (filter vector? children)]
                        (walk-for-contexts child parent-class)))
                    :EnumLine
                    ;; Enums don't have contexts
                    nil
                    ;; Recursively process other nodes
                    (doseq [child (filter vector? children)]
                      (walk-for-contexts child parent-class))))
                ))]
      (walk-for-contexts schema-ast nil)
      @context-map)))

(defn generate-haxe-class [class-name elements context-relationships]
  (let [context-parent (get context-relationships class-name)
        context-variable (when context-parent (str "the" context-parent))
        fields (for [element elements]
                 (let [field-name (:name element)
                       field-type (:type element)
                       sigil (:sigil element)]
                   (case sigil
                     ":" (str "    public var " field-name ": " field-type ";")
                     (str "    public var " field-name ": " field-type ";"))))
        ;; Add context field if this class has a context
        all-fields (if context-variable
                     (conj fields (str "    public var " context-variable ": " context-parent ";"))
                     fields)
        constructor-params (for [element elements]
                            (let [field-name (:name element)
                                  field-type (:type element)
                                  sigil (:sigil element)]
                              (str field-type " " field-name)))
        constructor-body (for [element elements]
                          (let [field-name (:name element)
                                sigil (:sigil element)]
                            (str "        this." field-name " = " field-name ";")))
        ;; Add setContext method if this class has a context
        setter-method (when context-variable
                       (str "\n    public function setContext(c: " context-parent ") {\n"
                            "        this." context-variable " = c;\n"
                            "    }"))
        class-code (str "class " class-name " {\n"
                       (str/join "\n" all-fields)
                       "\n\n"
                       "    public function new(" (str/join ", " constructor-params) ") {\n"
                       (str/join "\n" constructor-body)
                       "\n    }"
                       (or setter-method "")
                       "\n}")]
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

(defn walk-tree [node interface-implementers context-relationships]
  (cond
    (string? node) []
    (vector? node)
    (let [[tag & children] node]
      (case tag
        :Schema (vec (mapcat #(walk-tree % interface-implementers context-relationships) (filter vector? children)))
        :DefLine (walk-tree (first (filter vector? children)) interface-implementers context-relationships)
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
                          (generate-haxe-class class-name processed-elements context-relationships))]
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
        (vec (mapcat #(walk-tree % interface-implementers context-relationships) (filter vector? children)))))
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
                                             [interface-name (set implementers)])))
          ;; Build context relationships
          context-relationships (build-context-relationships parse-result)]
      (if (insta/failure? parse-result)
        (schema/syntax-error (str input " is not a valid string in wchnt: " (insta/get-failure parse-result)))
        (let [classes (walk-tree parse-result interface-implementers context-relationships)]
          (schema/success-result classes))))
    (catch #?(:clj Exception :cljs :default) e
      (schema/syntax-error (str "Error during compilation: " e)))))

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
          haxe-code)
        ;; Handle as regular class construction
        (let [haxe-args (map-indexed (fn [idx arg]
                         (let [element (nth elements idx)
                               element-type (:type element)
                               element-name (:name element)
                               sigil (:sigil element)
                               arg-expr (ast-to-haxe-factory arg class-info)]
                                         arg-expr))
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
            haxe-code)

          ;; Handle XxxToYyyMapElement nodes (e.g., :DirectionToStringMapElement)
          (and (keyword? tag) (re-matches #".*To.*MapElement$" (name tag)))
          (let [key-value-pair (filter vector? children)
                key (first key-value-pair)
                value (second key-value-pair)
                key-haxe (ast-to-haxe-factory key class-info)
                value-haxe (ast-to-haxe-factory value class-info)
                result (str "[" key-haxe ", " value-haxe "]")]
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
          (let [var-name (nth ast 2)]  ;; Extract variable name from [:VariableReference "$" "people"]
            var-name)

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





(defn walk-ast-and-build-table [ast class-info context-relationships id-counter parent-id object-table]
  "Walk through AST once, building object table with abstract IDs on way down, details on way up"
  (cond
    (string? ast)
    [ast id-counter object-table]
    
    (keyword? ast)
    [ast id-counter object-table]
    
    (vector? ast)
    (let [[tag & children] ast]
      (cond
        ;; Handle :Construction wrapper (unwrap, no new)
        (= tag :Construction)
        (walk-ast-and-build-table (first children) class-info context-relationships id-counter parent-id object-table)
        
        ;; Handle XxxConstruction nodes (e.g., :GameConstruction, :RectConstruction)
        (and (keyword? tag) 
             (re-matches #".*Construction$" (name tag))
             (not (re-matches #".*ArrayConstruction$" (name tag))))
        (let [class-name (str/replace (name tag) "Construction" "")
              ;; On way down: create abstract ID and record in table
              current-id (str "id-" id-counter)
              updated-table (assoc object-table current-id {:parent parent-id :class class-name :var-name nil :ast ast})
              
              ;; Process all children recursively
              [processed-children final-counter child-table] 
              (reduce (fn [[acc-children acc-counter acc-table] child]
                        (let [[processed-child new-counter new-table] 
                              (walk-ast-and-build-table child class-info context-relationships acc-counter current-id acc-table)]
                          [(conj acc-children processed-child) new-counter new-table]))
                      [[] (inc id-counter) updated-table]
                      (remove string? children))
              
              ;; On way back up: assign variable name (count existing vars to get next number)
              existing-vars (filter #(not (nil? (:var-name (val %)))) child-table)
              current-var (str "o" (inc (count existing-vars)))
              final-table (assoc child-table current-id {:parent parent-id :class class-name :var-name current-var :ast ast})]
          [current-var final-counter final-table])
        
        ;; Handle other vector nodes recursively
        :else
        (let [[processed-children final-counter child-table] 
              (reduce (fn [[acc-children acc-counter acc-table] child]
                        (let [[processed-child new-counter new-table] 
                              (walk-ast-and-build-table child class-info context-relationships acc-counter parent-id acc-table)]
                          [(conj acc-children processed-child) new-counter new-table]))
                      [[] id-counter object-table]
                      children)]
          [ast final-counter child-table])))
    
    :else
    [ast id-counter object-table]))

(defn generate-statements-from-table [object-table context-relationships class-info]
  "Generate construction statements and context wiring from the object table"
  (let [;; Sort objects by variable name to ensure correct order
        sorted-entries (sort-by #(Integer/parseInt (subs (:var-name (val %)) 1)) object-table)
        
        ;; Helper function to process constructor arguments
        process-constructor-arg (fn [arg]
                                 (cond
                                   ;; If it's a vector (object node), find its variable name in the table
                                   (vector? arg)
                                   (let [matching-entry (first (filter #(= (:ast (val %)) arg) object-table))]
                                     (if matching-entry
                                       (:var-name (val matching-entry))
                                       (ast-to-haxe-factory arg class-info)))
                                   ;; If it's a raw number, just convert to string
                                   (number? arg)
                                   (str arg)
                                   ;; Otherwise, use ast-to-haxe-factory
                                   :else
                                   (ast-to-haxe-factory arg class-info)))
        
        ;; Generate construction statements
        construction-stmts (map (fn [[id entry]]
                                 (let [var-name (:var-name entry)
                                       class-name (:class entry)
                                       ast (:ast entry)
                                       ;; Extract constructor arguments (children after the tag)
                                       constructor-args (rest ast)
                                       ;; Process arguments: use variable names for objects, values for primitives
                                       haxe-args (map process-constructor-arg constructor-args)]
                                   (str "var " var-name " = new " class-name "(" (str/join ", " haxe-args) ");")))
                               sorted-entries)
        
        ;; Generate context wiring statements
        context-stmts (map (fn [[id entry]]
                            (let [var-name (:var-name entry)
                                  class-name (:class entry)
                                  needs-context (get context-relationships class-name)]
                              (when needs-context
                                (let [parent-id (:parent entry)
                                      parent-entry (get object-table parent-id)
                                      parent-var (:var-name parent-entry)]
                                  (str var-name ".setContext(" parent-var ");")))))
                          sorted-entries)
        context-stmts (remove nil? context-stmts)]
    {:construction-stmts construction-stmts :context-stmts context-stmts}))

(defn generate-construction-factory-impl [schema-input construction-input context-relationships]
  "Implementation of generate-construction-factory without exception handling"
  (let [parse-result (parser/parse-construction schema-input construction-input)]
    (if (:success parse-result)
      (let [ast (:ast parse-result)
            class-info (parser/extract-class-info ((parser/get-parser) schema-input))
            ;; Build context relationships from schema if not provided
            actual-context-relationships (if (empty? context-relationships)
                                          (build-context-relationships ((parser/get-parser) schema-input))
                                          context-relationships)]
        
                ;; Handle multi-step construction AST
        (if (= (:type ast) :MultiStepConstruction)
          (let [;; Process assignments first
                assignment-results (map #(walk-ast-and-build-table (:construction %) class-info actual-context-relationships 1 nil {})
                                       (:assignments ast))
                assignment-tables (map last assignment-results)
                combined-assignment-table (apply merge assignment-tables)
                
                ;; Process final construction
                [final-var final-counter final-table] 
                (walk-ast-and-build-table (:final-construction ast) class-info actual-context-relationships 
                                         (inc (count combined-assignment-table)) nil combined-assignment-table)
                
                ;; Generate statements from the complete table
                {:keys [construction-stmts context-stmts]} (generate-statements-from-table final-table actual-context-relationships class-info)
                
                ;; Get the root object (highest variable number)
                root-var (apply max-key #(Integer/parseInt (subs % 1)) 
                               (map #(:var-name (val %)) final-table))
                
                factory-code (str "public static function factory() {\n"
                                 "    " (str/join "\n    " construction-stmts) "\n"
                                 (when (seq context-stmts) (str "    " (str/join "\n    " context-stmts) "\n"))
                                 "    return " root-var ";\n"
                                 "}")]
            (schema/syntax-success ast {:haxe-code factory-code}))
          
          ;; Handle single construction AST (legacy)
          (let [;; Build object table
                [final-var final-counter object-table] 
                (walk-ast-and-build-table ast class-info actual-context-relationships 1 nil {})
                
                ;; Generate statements from the table
                {:keys [construction-stmts context-stmts]} (generate-statements-from-table object-table actual-context-relationships class-info)
                
                ;; Get the root object
                root-var (apply max-key #(Integer/parseInt (subs % 1)) 
                               (map #(:var-name (val %)) object-table))
                
                factory-code (str "public static function factory() {\n"
                                 "    " (str/join "\n    " construction-stmts) "\n"
                                 (when (seq context-stmts) (str "    " (str/join "\n    " context-stmts) "\n"))
                                 "    return " root-var ";\n"
                                 "}")]
            (schema/syntax-success ast {:haxe-code factory-code}))))
      parse-result)))

(defn generate-construction-factory [schema-input construction-input context-relationships]
  "Generate Haxe factory function from schema and construction input"
  #?(:clj
     (try
       (generate-construction-factory-impl schema-input construction-input context-relationships)
       (catch Exception e
         (schema/syntax-error (str "Error generating factory: " (.getMessage e)))))
     :cljs
     (try
       (generate-construction-factory-impl schema-input construction-input context-relationships)
       (catch :default e
         (schema/syntax-error (str "Error generating factory: " (.-message e))))))) 

 
