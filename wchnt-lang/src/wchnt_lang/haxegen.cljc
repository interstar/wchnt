(ns wchnt-lang.haxegen
  (:require [clojure.string :as str]
            [instaparse.core :as insta]
            [wchnt-lang.parser :as parser]
            [wchnt-lang.pipeline :as P]
            [wchnt-lang.schema :as schema]))

;; Import find-node from parser
(def find-node wchnt-lang.parser/find-node)


(defn primitive-type->to-construction-expr [field-name field-type]
  "Generate the toConstruction expression for a primitive type field"
  (case field-type
    "String" (str "\"\" + this." field-name " + \"\"")
    "Int" (str "this." field-name)
    "Float" (str "this." field-name)
    "Bool" (str "this." field-name)
    nil))

;; Helper to convert parsed type ASTs to Haxe type strings
(defn type-ast->haxe-type [t]
  (cond
    (string? t) t
    (and (vector? t) (= (first t) :ArrayType))
    (let [type-node (find-node :Type (rest t))
          inner-type (when type-node (second type-node))]
      (str "Array<" (type-ast->haxe-type inner-type) ">"))
    (and (vector? t) (= (first t) :Type))
    (second t)
    (and (vector? t) (= (first t) :MapType))
    (let [key-type-node (find-node :KeyType (rest t))
          val-type-node (find-node :ValType (rest t))
          key-type (when key-type-node (second key-type-node))
          val-type (when val-type-node (second val-type-node))]
      (str "Map<" (type-ast->haxe-type key-type) ", " (type-ast->haxe-type val-type) ">"))
    :else (str t)))

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

(defn indent [depth]
  (apply str (repeat depth "  ")))

(defn generate-to-construction-parts [elements]
  "Generate the parts for a toConstruction method with depth argument"
  (for [element elements]
    (let [field-name (:name element)
          field-type (type-ast->haxe-type (:type element))]
      (or (primitive-type->to-construction-expr field-name field-type)
      (cond
        (str/starts-with? field-type "Array<") (str "this." field-name ".toConstruction(depth + 1)")
        (str/starts-with? field-type "Map<") (str "this." field-name ".toConstruction(depth + 1)")
            :else (str "this." field-name ".toConstruction(depth + 1)"))))))

(defn generate-to-construction-method [class-name to-construction-parts]
  "Generate the toConstruction method string with proper string concatenation"
  (let [indent-line (str "ind + '  ' + ")]
    (str "\n    public function toConstruction(depth:Int = 0):String {\n"
         "        var ind = \"\" + '  '.repeat(depth);\n"
         "        var nl = '\\n';\n"
         "        return ind + '[:" class-name "' + nl + "
         (str/join " + nl + " (map #(str indent-line %) to-construction-parts)) 
         " + nl + ind + ']' ;\n"
         "    }")))

(defn generate-haxe-class [class-name elements context-relationships]
  (let [context-parent (get context-relationships class-name)
        context-variable (when context-parent (str "the" context-parent))
        fields (for [element elements]
                 (let [field-name (:name element)
                       field-type (type-ast->haxe-type (:type element))
                       sigil (:sigil element)]
                   (case sigil
                     ":" (str "    public var " field-name ": " field-type ";")
                     (str "    public var " field-name ": " field-type ";"))))
        all-fields (if context-variable
                     (conj fields (str "    public var " context-variable ": " context-parent ";"))
                     fields)
        constructor-params (for [element elements]
                            (let [field-name (:name element)
                                  field-type (type-ast->haxe-type (:type element))
                                  sigil (:sigil element)]
                              (str field-type " " field-name)))
        constructor-body (for [element elements]
                          (let [field-name (:name element)
                                sigil (:sigil element)]
                            (str "        this." field-name " = " field-name ";")))
        ;; Generate toConstruction method with depth argument
        to-construction-parts (generate-to-construction-parts elements)
        to-construction-method (generate-to-construction-method class-name to-construction-parts)
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
                       to-construction-method
                       "\n}")]
    class-code))

(defn generate-haxe-interface [interface-name]
  (str "interface " interface-name " {\n"
       "    public var x: Int;\n"
       "    public var y: Int;\n"
       "}"))

(defn generate-haxe-enum [enum-name enum-values]
  (let [processed-values (for [value enum-values]
                          (let [raw-value (str/trim value)
                                enum-value-name (-> raw-value
                                                   (str/replace #"[^A-Za-z0-9]" "")
                                                   (str/replace #"^[a-z]" str/upper-case))]
                            enum-value-name))
        ;; Generate toConstruction method for each enum value
        to-construction-methods (for [value processed-values]
                                 (str "    public static function " value "ToConstruction(depth:Int = 0):String {\n"
                                      "        var ind = \"\" + '  '.repeat(depth);\n"
                                      "        return ind + '\"" value "';\n"
                                      "    }"))
        enum-code (str "enum " enum-name " {\n"
                      (str/join "\n" (map #(str "    " % ";") processed-values))
                      "\n\n"
                      (str/join "\n" to-construction-methods)
                      "\n}")]
    enum-code))

(defn generate-haxe-class-implementing [class-name interface-name elements]
  (let [fields (for [element elements]
                 (let [field-name (:name element)
                       field-type (type-ast->haxe-type (:type element))]
                   (str "    public var " field-name ": " field-type ";")))
        constructor-params (for [element elements]
                            (let [field-name (:name element)
                                  field-type (type-ast->haxe-type (:type element))]
                              (str field-type " " field-name)))
        constructor-body (for [element elements]
                          (let [field-name (:name element)]
                            (str "        this." field-name " = " field-name ";")))
        ;; Generate toConstruction method with depth argument
        to-construction-parts (generate-to-construction-parts elements)
        to-construction-method (generate-to-construction-method class-name to-construction-parts)
        class-code (str "class " class-name " implements " interface-name " {\n"
                       (str/join "\n" fields)
                       "\n\n"
                       "    public function new(" (str/join ", " constructor-params) ") {\n"
                       (str/join "\n" constructor-body)
                       "\n    }"
                       to-construction-method
                       "\n}")]
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
              interface-name (second definee-node)]
          [(generate-haxe-interface interface-name)])
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

(defn array-extensions-class []
  "Return the Haxe code for ArrayExtensions class for toConstruction."
  "// Extension methods for Array toConstruction
class ArrayExtensions {
    public static function toConstruction<T>(arr: Array<T>, depth: Int = 0): String {
        var ind = \"\" + '  '.repeat(depth);
        var nl = '\\n';
        var result = ind + '[:Array';
        for (item in arr) {
            if (Std.isOfType(item, String)) {
                result += nl + ind + '  ' + '\"' + item + '\"';
            } else if (Reflect.hasField(item, 'toConstruction')) {
                result += nl + ind + '  ' + Reflect.callMethod(item, Reflect.field(item, 'toConstruction'), [depth + 1]);
            } else {
                result += nl + ind + '  ' + Std.string(item);
            }
        }
        result += nl + ind + ']';
        return result;
    }
}")

(defn schema-ast->haxe [schema-ast]
  "Convert schema AST to Haxe classes. Returns a string of Haxe code."
  (let [interface-implementers {}
        context-relationships (build-context-relationships schema-ast)
        classes (walk-tree schema-ast interface-implementers context-relationships)
        has-arrays (letfn [(has-array-type? [node]
                              (cond
                                (vector? node)
                                (let [[tag & children] node]
                                  (or (= tag :ArrayType)
                                      (some has-array-type? children)))
                                :else false))]
                     (has-array-type? schema-ast))
        array-extension (array-extensions-class)
        all-classes (if has-arrays (conj classes array-extension) classes)
                joined-classes (str/join "\n\n" all-classes)]
    joined-classes))

(defn int-literal->haxe [children]
  (str (first children)))

(defn float-literal->haxe [children]
  (str (first children)))

(defn bool-literal->haxe [children]
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
  (str/join " " (map ast-to-haxe-factory children)))

(defn ast-to-haxe-factory [ast class-info variable-mapping]
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
          (ast-to-haxe-factory (first children) class-info variable-mapping)

          ;; Handle ArrayConstruction specifically
          (= tag :ArrayConstruction)
          (let [array-elements (filter vector? children)
                ;; Filter out TypeName nodes and only keep actual array elements
                actual-elements (filter #(not= (first %) :TypeName) array-elements)
                haxe-elements (map #(ast-to-haxe-factory % class-info variable-mapping) actual-elements)]
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
                set-calls (map #(let [element-haxe (ast-to-haxe-factory % class-info variable-mapping)]
                                 (str ".set(" element-haxe ")")) actual-elements)
                haxe-code (str "new Map<" key-type ", " value-type ">()" (str/join "" set-calls))]
            haxe-code)

          ;; Handle XxxToYyyMapElement nodes (e.g., :DirectionToStringMapElement)
          (and (keyword? tag) (re-matches #".*To.*MapElement$" (name tag)))
          (let [key-value-pair (filter vector? children)
                key (first key-value-pair)
                value (second key-value-pair)
                key-haxe (ast-to-haxe-factory key class-info variable-mapping)
                value-haxe (ast-to-haxe-factory value class-info variable-mapping)
                result (str "[" key-haxe ", " value-haxe "]")]
            result)

          ;; Handle XxxArrayElement nodes (e.g., :PlayerArrayElement, :TeamArrayElement)
          (and (keyword? tag) (re-matches #".*ArrayElement$" (name tag)))
          (let [class-name (-> (name tag)
                               (str/replace #"ArrayElement$" ""))
                haxe-args (map #(ast-to-haxe-factory % class-info variable-mapping) (remove string? children))]
            (str "new " class-name "(" (str/join ", " haxe-args) ")"))

          ;; Handle XxxArrayConstruction (e.g., :StudentsArrayConstruction)
          (and (keyword? tag) (re-matches #".*ArrayConstruction$" (name tag)))
          (let [array-elems (map #(ast-to-haxe-factory % class-info variable-mapping) (remove string? children))]
            (str "[" (str/join ", " array-elems) "]"))

          ;; Handle XxxConstruction (e.g., :SchoolConstruction), but not ArrayConstruction
          (and (keyword? tag)
               (re-matches #".*Construction$" (name tag))
               (not (re-matches #".*ArrayConstruction$" (name tag))))
          (let [class-name (-> (name tag)
                               (str/replace #"Construction$" ""))
                haxe-args (map #(ast-to-haxe-factory % class-info variable-mapping) (remove string? children))]
            (str "new " class-name "(" (str/join ", " haxe-args) ")"))

          ;; Handle StringLiteral
          (= tag :StringLiteral)
          (string-literal->haxe children)

          ;; Handle IntLiteral
          (= tag :IntLiteral)
          (int-literal->haxe children)

          ;; Handle FloatLiteral
          (= tag :FloatLiteral)
          (float-literal->haxe children)

          ;; Handle BoolLiteral
          (= tag :BoolLiteral)
          (bool-literal->haxe children)

          ;; Handle VariableReference
          (= tag :VariableReference)
          (let [var-name-node (nth ast 1)  ;; Extract variable name from [:VariableReference [:VariableName "people"]]
                var-name (if (and (vector? var-name-node) (= (first var-name-node) :VariableName))
                          (second var-name-node)  ;; Extract from [:VariableName "people"]
                          (str var-name-node))]   ;; Fallback if structure is different
            (get variable-mapping (str "$" var-name) (str "$" var-name)))

          ;; Handle LocalEmpty
          (= tag :LocalEmpty)
          (local-empty->haxe ast)

          ;; Fallback: generic node
          :else
          (generic-node->haxe children #(ast-to-haxe-factory % class-info variable-mapping) class-info)))

      (seq? ast)
      (when (and (not (vector? ast)) (not (keyword? ast)))
        (str/join " " (map #(ast-to-haxe-factory % class-info variable-mapping) ast)))

      :else
      (str ast))))





(defn collect-ast-nodes [ast class-info context-relationships id-counter parent-id object-table]
  "First pass: collect all AST nodes without assigning variable names"
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
        (collect-ast-nodes (first children) class-info context-relationships id-counter parent-id object-table)
        
        ;; Handle XxxConstruction nodes (e.g., :GameConstruction, :RectConstruction)
        (and (keyword? tag) 
             (re-matches #".*Construction$" (name tag))
             (not (re-matches #".*ArrayConstruction$" (name tag))))
        (let [class-name (str/replace (name tag) "Construction" "")
              current-id (str "id-" id-counter)
              updated-table (assoc object-table current-id {:parent parent-id :class class-name :var-name nil :ast ast})
              [processed-children final-counter child-table] 
              (reduce (fn [[acc-children acc-counter acc-table] child]
                        (let [[processed-child new-counter new-table] 
                              (collect-ast-nodes child class-info context-relationships acc-counter current-id acc-table)]
                          [(conj acc-children processed-child) new-counter new-table]))
                      [[] (inc id-counter) updated-table]
                      (remove string? children))]
          [current-id final-counter child-table])

        ;; Handle ArrayElement nodes (e.g., :PlayerArrayElement, :TriangleArrayElement)
        (and (keyword? tag)
             (re-matches #".*ArrayElement$" (name tag)))
        (let [class-name (str/replace (name tag) "ArrayElement" "")
              current-id (str "id-" id-counter)
              updated-table (assoc object-table current-id {:parent parent-id :class class-name :var-name nil :ast ast})
              [processed-children final-counter child-table]
              (reduce (fn [[acc-children acc-counter acc-table] child]
                        (let [[processed-child new-counter new-table]
                              (collect-ast-nodes child class-info context-relationships acc-counter current-id acc-table)]
                          [(conj acc-children processed-child) new-counter new-table]))
                      [[] (inc id-counter) updated-table]
                      (remove string? children))]
          [current-id final-counter child-table])

        ;; Handle ArrayConstruction nodes (including type-specific ones like PlayerArrayConstruction)
        (and (keyword? tag) 
             (re-matches #".*ArrayConstruction$" (name tag)))
        (let [current-id (str "id-" id-counter)
              updated-table (assoc object-table current-id {:parent parent-id :class "Array" :var-name nil :ast ast})
              [processed-children final-counter child-table] 
              (reduce (fn [[acc-children acc-counter acc-table] child]
                        (let [[processed-child new-counter new-table] 
                              (collect-ast-nodes child class-info context-relationships acc-counter current-id acc-table)]
                          [(conj acc-children processed-child) new-counter new-table]))
                      [[] (inc id-counter) updated-table]
                      (remove string? children))]
          [current-id final-counter child-table])
        
        ;; Handle other vector nodes recursively
        :else
        (let [[processed-children final-counter child-table] 
              (reduce (fn [[acc-children acc-counter acc-table] child]
                        (let [[processed-child new-counter new-table] 
                              (collect-ast-nodes child class-info context-relationships acc-counter parent-id acc-table)]
                          [(conj acc-children processed-child) new-counter new-table]))
                      [[] id-counter object-table]
                      children)]
          [ast final-counter child-table])))
    
    :else
    [ast id-counter object-table]))

(defn assign-variable-names [object-table start-counter]
  "Second pass: assign variable names in dependency order (children before parents)"
  (let [;; Build dependency graph
        build-dependencies (fn [entry]
                            (let [ast (:ast entry)
                                  constructor-args (rest ast)
                                  dependencies (filter vector? constructor-args)
                                  dependency-ids (map #(let [matching-entry (first (filter (fn [[k v]] (= (:ast v) %)) object-table))]
                                                       (when matching-entry (key matching-entry)))
                                                     dependencies)]
                              (remove nil? dependency-ids)))
        
        ;; Sort entries by dependency order (leaf nodes first)
        sorted-entries (let [entries (vec object-table)
                            dependency-map (into {} (map (fn [[id entry]] [id (build-dependencies entry)]) entries))
                            visited (atom #{})
                            result (atom [])]
                        (letfn [(visit [id]
                                  (when-not (contains? @visited id)
                                    (swap! visited conj id)
                                    (doseq [dep-id (dependency-map id)]
                                      (visit dep-id))
                                    (swap! result conj (first (filter #(= (key %) id) entries)))))]
                          (doseq [[id _] entries]
                            (visit id))
                          @result))
        
        ;; Assign variable names in dependency order
        final-table (reduce (fn [table [idx [id entry]]]
                             (assoc table id (assoc entry :var-name (str "o" (+ start-counter idx)))))
                           object-table
                           (map-indexed vector sorted-entries))]
    final-table))

(defn walk-ast-and-build-table [ast class-info context-relationships id-counter parent-id object-table variable-mapping]
  "Walk through AST once, building object table with abstract IDs on way down, details on way up"
  (let [[root-id final-counter collected-table] (collect-ast-nodes ast class-info context-relationships id-counter parent-id object-table)
        final-table (assign-variable-names collected-table id-counter)]
    [root-id final-counter final-table variable-mapping]))

(defn- get-haxe-type [class-name class-info]
  "Get the Haxe type for a given class name"
  (cond
    ;; Check if it's a primitive type
    (contains? #{"String" "Int" "Float" "Bool"} class-name)
    class-name
    
    ;; Check if it's an array type
    (= class-name "Array")
    "Array<Dynamic>"  ;; Generic array type
    
    ;; Check if it's a class in our schema
    (some #(= (:name %) class-name) (:classes class-info))
    class-name
    
    ;; Check if it's an interface (disjunction)
    (some #(= (:name %) class-name) (:disjunctions class-info))
    class-name
    
    ;; Default to Dynamic for unknown types
    :else
    "Dynamic"))

(defn generate-statements-from-table [object-table context-relationships class-info variable-mapping]
  "Generate construction statements and context wiring from the object table"
  (let [;; Helper function to process constructor arguments
        process-constructor-arg (fn [arg]
                                 (cond
                                   ;; If it's a vector (object node), find its variable name in the table
                                   (vector? arg)
                                   (let [matching-entry (first (filter #(= (:ast (val %)) arg) object-table))]
                                     (if matching-entry
                                       (:var-name (val matching-entry))
                                       (ast-to-haxe-factory arg class-info variable-mapping)))
                                   ;; If it's a raw number, just convert to string
                                   (number? arg)
                                   (str arg)
                                   ;; If it's a string that looks like a variable reference, check the mapping
                                   (and (string? arg) (str/starts-with? arg "$"))
                                   (get variable-mapping arg arg)
                                   ;; Otherwise, use ast-to-haxe-factory
                                   :else
                                   (ast-to-haxe-factory arg class-info variable-mapping)))
        
        ;; Build dependency graph
        build-dependencies (fn [entry]
                            (let [ast (:ast entry)
                                  constructor-args (rest ast)
                                  dependencies (filter vector? constructor-args)
                                  dependency-vars (map #(let [matching-entry (first (filter (fn [[k v]] (= (:ast v) %)) object-table))]
                                                        (when matching-entry (:var-name (val matching-entry))))
                                                      dependencies)]
                              (remove nil? dependency-vars)))
        
        ;; Sort entries by dependency order (leaf nodes first)
        sorted-entries (let [entries (vec object-table)
                            dependency-map (into {} (map (fn [[id entry]] [id (build-dependencies entry)]) entries))
                            visited (atom #{})
                            result (atom [])]
                        (letfn [(visit [id]
                                  (when-not (contains? @visited id)
                                    (swap! visited conj id)
                                    (doseq [dep-id (dependency-map id)]
                                      (when-let [dep-entry (first (filter #(= (key %) dep-id) entries))]
                                        (visit (key dep-entry))))
                                    (swap! result conj (first (filter #(= (key %) id) entries)))))]
                          (doseq [[id _] entries]
                            (visit id))
                          @result))
        
        ;; Generate construction statements in dependency order
        construction-stmts (map (fn [[id entry]]
                                 (let [var-name (:var-name entry)
                                       class-name (:class entry)
                                       ast (:ast entry)
                                       haxe-type (get-haxe-type class-name class-info)]
                                   (if (= class-name "Array")
                                     ;; Handle arrays by processing their elements through the object table
                                     (let [;; Extract array elements (children after the tag)
                                           array-elements (filter vector? (rest ast))
                                           ;; Process elements: use variable names for objects, values for primitives
                                           haxe-elements (map process-constructor-arg array-elements)]
                                       (str haxe-type " " var-name " = [" (str/join ", " haxe-elements) "];"))
                                     ;; Handle regular classes
                                     (let [;; Extract constructor arguments (children after the tag)
                                           constructor-args (rest ast)
                                           ;; Process arguments: use variable names for objects, values for primitives
                                           haxe-args (map process-constructor-arg constructor-args)]
                                       (str haxe-type " " var-name " = new " class-name "(" (str/join ", " haxe-args) ");")))))
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

;; Helper for multi-step construction factory code
(defn multi-step-factory-code [ast class-info actual-context-relationships]
  (let [assignment-results
        (loop [assignments (:assignments ast)
               results []
               global-counter 1]
          (if (empty? assignments)
            results
            (let [assignment (first assignments)
                  result (walk-ast-and-build-table
                          (:construction assignment)
                          class-info actual-context-relationships
                          global-counter nil {} {})
                  next-counter (nth result 1)]
              (recur (rest assignments)
                     (conj results result)
                     next-counter))))
        assignment-tables (map #(nth % 2) assignment-results)
        assignment-mappings (map #(nth % 3) assignment-results)
        combined-assignment-table (apply merge assignment-tables)
        combined-variable-mapping (apply merge assignment-mappings)
        [final-var final-counter final-table final-mapping]
        (walk-ast-and-build-table
         (:final-construction ast) class-info actual-context-relationships
         (inc (count combined-assignment-table)) nil combined-assignment-table combined-variable-mapping)
        variable-name-mapping (into {}
                                   (for [assignment (:assignments ast)]
                                     (let [assignment-ast (:construction assignment)
                                           array-var (some (fn [[_ entry]] (when (= (:ast entry) assignment-ast) (:var-name entry))) final-table)]
                                       [(str "$" (:name assignment)) array-var])))
        {:keys [construction-stmts context-stmts]} (generate-statements-from-table final-table actual-context-relationships class-info variable-name-mapping)
        root-var (let [final-construction (:final-construction ast)
                        root-entry (first (filter #(= (:ast (val %)) final-construction) final-table))]
                   (if root-entry
                     (:var-name (val root-entry))
                     (apply max-key #(Integer/parseInt (subs % 1))
                            (map #(:var-name (val %)) final-table))))
        factory-code (str "public static function factory() {\n"
                          "  " (str/join "\n    " construction-stmts) "\n"
                          (when (seq context-stmts) (str "    " (str/join "\n    " context-stmts) "\n"))
                          "    return " root-var ";\n"
                          "}")]
    (P/success-cargo factory-code)))

;; Helper for single construction factory code
(defn single-factory-code [ast class-info actual-context-relationships]
  (let [[final-var final-counter object-table final-mapping]
        (walk-ast-and-build-table ast class-info actual-context-relationships 1 nil {} {})
        {:keys [construction-stmts context-stmts]} (generate-statements-from-table object-table actual-context-relationships class-info {})
        root-var (let [root-entry (first (filter #(= (:ast (val %)) ast) object-table))]
                   (if root-entry
                     (:var-name (val root-entry))
                     (apply max-key #(Integer/parseInt (subs % 1))
                            (map #(:var-name (val %)) object-table))))
        factory-code (str "public static function factory() {\n"
                          "    " (str/join "\n    " construction-stmts) "\n"
                          (when (seq context-stmts) (str "    " (str/join "\n    " context-stmts) "\n"))
                          "    return " root-var ";\n"
                          "}")]
    (P/success-cargo factory-code)))

(defn generate-construction-factory-impl [schema-input construction-input context-relationships]
  "Implementation of generate-construction-factory without exception handling"
  (let [parse-result (parser/parse-construction-pure {:schema-ast schema-input :construction construction-input})]
    (if (:success parse-result)
      (let [ast (:value parse-result)
            class-info (parser/extract-class-info ((parser/get-schema-parser) schema-input))
            actual-context-relationships
            (if (empty? context-relationships)
              (build-context-relationships ((parser/get-schema-parser) schema-input))
              context-relationships)]
        (case (:type ast)
          :MultiStepConstruction (multi-step-factory-code ast class-info actual-context-relationships)
          :SingleConstruction (single-factory-code ast class-info actual-context-relationships)
          (P/fail-cargo (str "Unknown construction AST type: " (:type ast)))))
      parse-result)))

(defn generate-construction-factory [schema-input construction-input context-relationships]
  "Generate Haxe factory function from schema and construction input"
  #?(:clj
     (try
       (generate-construction-factory-impl schema-input construction-input context-relationships)
       (catch Exception e
         (P/fail-cargo (str "Error generating factory: " (.getMessage e)))))
     :cljs
     (try
       (generate-construction-factory-impl schema-input construction-input context-relationships)
       (catch :default e
         (P/fail-cargo (str "Error generating factory: " (.-message e)))))))

(defn generate-construction-factory-pure [parsed-ast class-info context-relationships]
  "Pure transformation: generate Haxe factory function from parsed construction AST.
  Input: parsed construction AST, class-info, context-relationships
  Output: Haxe factory function string"
  (let [ast-type (:type parsed-ast)
        ;; We need the schema AST to build context relationships, but we don't have it here
        ;; For now, we'll require context-relationships to be passed in
        actual-context-relationships (if (empty? context-relationships)
                                      {}  ;; Empty context relationships if none provided
                                      context-relationships)]
    (cond
      (= ast-type :MultiStepConstruction)
      (multi-step-factory-code parsed-ast class-info actual-context-relationships)
      (= ast-type :SingleConstruction)
      (single-factory-code parsed-ast class-info actual-context-relationships)
      :else (throw (ex-info "Unknown construction AST type" {:ast-type ast-type :parsed-ast parsed-ast}))))
)
