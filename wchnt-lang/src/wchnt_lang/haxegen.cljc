(ns wchnt-lang.haxegen
  (:require [clojure.string :as str]
            [instaparse.core :as insta]
            [wchnt-lang.parser :as parser]
            [wchnt-lang.pipeline :as P]
            [wchnt-lang.schema :as schema])
  (:import (java.lang Exception)))





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
                                                  :when (contains? implementers class-name)]
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


(defn find-all-nodes [tag tree]
  "Find all nodes in the tree that start with the given tag. Returns a sequence of matching nodes."
  (cond
    (and (vector? tree) (= (first tree) tag)) 
    [tree]
    
    (vector? tree) 
    (find-all-nodes tag (rest tree))
    
    (seq? tree) 
    (mapcat #(find-all-nodes tag %) tree)
    
    :else []))


(defn build-interface-implementers [schema-ast]
  "Build a map of interface names to sets of implementing class names from the schema AST."
  (let [disjunction-nodes (find-all-nodes :DisjunctionLine schema-ast)]
    (reduce (fn [acc disjunction-node]
              (let [[_ & children] disjunction-node
                    definee-node (first (filter #(= (first %) :Definee) children))
                    interface-name (second definee-node)
                    element-nodes (filter #(= (first %) :Element) children)
                    type-names (for [element element-nodes
                                     :let [type-marker (first (filter #(and (vector? %) (= (first %) :TypeMarker)) element))]
                                     :when type-marker]
                                 (second type-marker))]

                ;; Track which classes implement this interface
                (reduce (fn [current-acc implementer]
                          (update current-acc interface-name (fnil conj #{}) implementer))
                        acc type-names)))
            {} disjunction-nodes)))


(defn schema-ast->haxe [schema-ast]
  "Convert schema AST to Haxe classes. Returns a string of Haxe code."
  (let [interface-implementers (build-interface-implementers schema-ast)
        context-relationships (build-context-relationships schema-ast)]

    (let [classes (walk-tree schema-ast interface-implementers context-relationships)
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
      joined-classes)))


(defn int-literal->haxe [children]
  (str (first children)))

(defn float-literal->haxe [children]
  (str (first children)))

(defn bool-literal->haxe [children]
  (str (first children)))


(defn string-literal->haxe [children]
  ;; The children are [quote content quote], so we want the middle element
  (str "\"" (second children) "\""))

(defn string-literal->haxe [children]
  ;; The children are [quote content quote], so we want the middle element
  (str "\"" (second children) "\""))


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



(defn extract-class-name-from-node [class-name-ast]
  "Extract class name from ClassName AST node."
  (second class-name-ast))


(defn extract-type-name [type-ast]
  "Extract type name from type AST."
  (second type-ast))


(defn extract-literal-value [literal-ast]
  "Extract literal value from literal AST."
  (let [literal-node (second literal-ast)]
    (cond
      (and (vector? literal-node) (= (first literal-node) :IntLiteral))
      (second literal-node)
      
      (and (vector? literal-node) (= (first literal-node) :StringLiteral))
      (str "\"" (second literal-node) "\"")  ; Use the middle part (without quotes)
      
      :else "null")))

(defn extract-variable-name [assignment-ast]
  "Extract variable name from assignment AST."
  (second (second assignment-ast)))


(defn extract-root-class-name [ast]
  "Extract the root class name from the unified parser AST"
  (if (not (sequential? ast))
    (throw (ex-info "extract-root-class-name: AST must be a sequential collection" {:ast ast})))
  (if (not= (first ast) :BlockStatements)
    (throw (ex-info "extract-root-class-name: AST must start with :BlockStatements" {:ast (take 3 ast)})))
  
  ;; Look for the first ObjectConstruction in the BlockStatements
  (let [statements (rest ast)]
    (loop [remaining statements]
      (if (empty? remaining)
        (throw (ex-info "extract-root-class-name: No ObjectConstruction found in BlockStatements" {:statements statements}))
        (let [statement (first remaining)]
          (if (and (sequential? statement) (= (first statement) :Expression))
            (let [expression (second statement)]
              (if (and (sequential? expression) (= (first expression) :ObjectConstruction))
                (let [class-name-node (second expression)]
                  (if (and (sequential? class-name-node) (= (first class-name-node) :ClassName))
                    (second class-name-node)  ;; Extract "Game" from [:ClassName "Game"]
                    (throw (ex-info "extract-root-class-name: ObjectConstruction must have ClassName node" {:expression expression}))))
                (recur (rest remaining))))
            (recur (rest remaining))))))))


(defn node-type? [ast kw]
  (and (vector? ast) (= (first ast) kw)))

;; Declare the main function so helper functions can reference it
(declare walk-ast-and-build-table)

(defn handle-string-node [ast counter object-table variable-mapping]
  "Handle string nodes directly"
  [ast counter object-table variable-mapping])

(defn handle-object-construction [ast class-info context-relationships counter parent-id object-table variable-mapping]
  "Handle ObjectConstruction nodes"
  (let [[_ class-name-node arg-list] ast
        class-name (extract-class-name-from-node class-name-node)
        args (rest arg-list)
        abstract-id (str "obj_" counter)
        [processed-args new-counter new-table new-mapping] 
        (reduce (fn [[acc-args acc-counter acc-table acc-mapping] arg]
                  (let [[arg-var arg-counter arg-table arg-mapping] 
                        (walk-ast-and-build-table arg class-info context-relationships acc-counter counter acc-table acc-mapping)]
                    [(conj acc-args arg-var) arg-counter arg-table arg-mapping]))
                [[] (inc counter) object-table variable-mapping] args)
        new-entry {abstract-id {:class class-name
                               :abstract-id abstract-id
                               :ast ast
                               :args processed-args
                               :parent parent-id}}]
    [abstract-id new-counter (merge new-table new-entry) new-mapping]))

(defn handle-inner-object-construction [ast class-info context-relationships counter parent-id object-table variable-mapping]
  "Handle InnerObjectConstruction nodes"
  (let [[_ & children] ast
        has-explicit-class (and (>= (count children) 2) 
                                (vector? (first children))
                                (= (first (first children)) :ClassName))
        class-name-node (if has-explicit-class (first children) nil)
        class-name (if class-name-node
                     (extract-class-name-from-node class-name-node)
                     (let [arg-list (if has-explicit-class (second children) (first children))
                           args (rest arg-list)
                           arg-count (count args)]
                       (cond
                         (= arg-count 4) "Rect"
                         (= arg-count 3) "Player"
                         (= arg-count 2) "Team"
                         :else "InnerObject")))
        arg-list (if has-explicit-class (second children) (first children))
        args (rest arg-list)
        abstract-id (str "obj_" counter)
        [processed-args new-counter new-table new-mapping] 
        (reduce (fn [[acc-args acc-counter acc-table acc-mapping] arg]
                  (let [[arg-var arg-counter arg-table arg-mapping] 
                        (walk-ast-and-build-table arg class-info context-relationships acc-counter counter acc-table acc-mapping)]
                    [(conj acc-args arg-var) arg-counter arg-table arg-mapping]))
                [[] (inc counter) object-table variable-mapping] args)
        new-entry {abstract-id {:class class-name
                               :abstract-id abstract-id
                               :ast ast
                               :args processed-args
                               :parent parent-id}}]
    [abstract-id new-counter (merge new-table new-entry) new-mapping]))

(defn handle-literal-node [ast]
  "Handle literal nodes (IntLiteral, StringLiteral, etc.)"
  (cond
    (node-type? ast :IntLiteral) [(Integer/parseInt (second ast))]
    (node-type? ast :StringLiteral) [(second ast)]
    (node-type? ast :FloatLiteral) [(Double/parseDouble (second ast))]
    (node-type? ast :BoolLiteral) [(Boolean/parseBoolean (second ast))]
    :else [nil]))

(defn handle-variable-ref [ast variable-mapping]
  "Handle VariableRef nodes"
  (let [name-node (second ast)
        var-name (if (and (vector? name-node) (= (first name-node) :SelfName))
                   (second name-node)
                   name-node)
        mapped-var (get variable-mapping var-name)]
    (if mapped-var
      [mapped-var]
      ["null"])))

(defn handle-array-construction [ast class-info context-relationships counter parent-id object-table variable-mapping]
  "Handle ArrayConstruction nodes"
  (let [type-node (second ast)
        element-type (second type-node)
        arg-list (nth ast 2)
        args (rest arg-list)
        abstract-id (str "obj_" counter)
        [processed-args new-counter new-table new-mapping] 
        (reduce (fn [[acc-args acc-counter acc-table acc-mapping] arg]
                  (let [[arg-var arg-counter arg-table arg-mapping] 
                        (walk-ast-and-build-table arg class-info context-relationships acc-counter parent-id acc-table acc-mapping)]
                    (if arg-var
                      [(conj acc-args arg-var) arg-counter (merge acc-table arg-table) (merge acc-mapping arg-mapping)]
                      [acc-args arg-counter (merge acc-table arg-table) (merge acc-mapping arg-mapping)])))
                [[] (inc counter) object-table variable-mapping] args)
        new-entry {abstract-id {:class (str "Array<" element-type ">")
                               :abstract-id abstract-id
                               :ast ast
                               :args processed-args
                               :parent parent-id}}]
    [abstract-id (inc new-counter) (merge new-table new-entry) new-mapping]))

(defn handle-simple-object-construction [ast class-info context-relationships counter parent-id object-table variable-mapping]
  "Handle simple object constructions like [:Game ...]"
  (let [class-name (name (first ast))
        args (rest ast)
        abstract-id (str "obj_" counter)
        [processed-args new-counter new-table new-mapping] 
        (reduce (fn [[acc-args acc-counter acc-table acc-mapping] arg]
                  (let [[arg-var arg-counter arg-table arg-mapping] 
                        (walk-ast-and-build-table arg class-info context-relationships acc-counter counter acc-table acc-mapping)]
                    [(conj acc-args arg-var) arg-counter arg-table arg-mapping]))
                [[] (inc counter) object-table variable-mapping] args)
        new-entry {abstract-id {:class class-name
                               :abstract-id abstract-id
                               :ast ast
                               :args processed-args
                               :parent parent-id}}]
    [abstract-id new-counter (merge new-table new-entry) new-mapping]))

(defn handle-vector-node [ast class-info context-relationships counter parent-id object-table variable-mapping]
  "Handle vector nodes by processing their children"
  (let [[tag & children] ast]
    (if (empty? children)
      [tag counter object-table variable-mapping]
      (if (contains? #{:Expression :Type :VariableName :ClassName :SelfName} tag)
        (let [first-child (first children)]
          (walk-ast-and-build-table first-child class-info context-relationships counter parent-id object-table variable-mapping))
        (if (= tag :ArgList)
          [tag counter object-table variable-mapping]
          (let [first-child (first children)]
            (walk-ast-and-build-table first-child class-info context-relationships counter parent-id object-table variable-mapping)))))))

(defn process-ast-node [ast class-info context-relationships counter parent-id object-table variable-mapping]
  "Process a single AST node and return [var-name counter object-table variable-mapping]"
  (cond
    ;; Handle strings directly
    (string? ast)
    (handle-string-node ast counter object-table variable-mapping)
    
    ;; Handle ObjectConstruction
    (node-type? ast :ObjectConstruction)
    (handle-object-construction ast class-info context-relationships counter parent-id object-table variable-mapping)
    
    ;; Handle InnerObjectConstruction
    (node-type? ast :InnerObjectConstruction)
    (handle-inner-object-construction ast class-info context-relationships counter parent-id object-table variable-mapping)
    
    ;; Handle Type nodes - these should be ignored in array arguments
    (node-type? ast :Type)
    [nil counter object-table variable-mapping]
    
    ;; Handle ArgItem - extract the inner object
    (node-type? ast :ArgItem)
    (let [inner-ast (second ast)]
      (walk-ast-and-build-table inner-ast class-info context-relationships counter parent-id object-table variable-mapping))
    
    ;; Handle literals
    (or (node-type? ast :IntLiteral) (node-type? ast :StringLiteral) 
        (node-type? ast :FloatLiteral) (node-type? ast :BoolLiteral))
    (let [[value] (handle-literal-node ast)]
      [value counter object-table variable-mapping])

    ;; Handle VariableRef
    (node-type? ast :VariableRef)
    (let [[value] (handle-variable-ref ast variable-mapping)]
      [value counter object-table variable-mapping])
    
    ;; Handle ArrayConstruction
    (node-type? ast :ArrayConstruction)
    (handle-array-construction ast class-info context-relationships counter parent-id object-table variable-mapping)
    
    ;; Handle simple object constructions
    (and (vector? ast) (>= (count ast) 2) (keyword? (first ast)) 
         (not= (first ast) :ArgList) (not= (first ast) :Type))
    (handle-simple-object-construction ast class-info context-relationships counter parent-id object-table variable-mapping)
    
    ;; Handle vector nodes
    (vector? ast)
    (handle-vector-node ast class-info context-relationships counter parent-id object-table variable-mapping)
    
    ;; For other types, return as-is
    :else
    [ast counter object-table variable-mapping]))

(defn walk-ast-and-build-table [ast class-info context-relationships counter parent-id object-table variable-mapping]
  "Recursively walk AST and build object table with dependency information.
   Returns [var-name counter object-table variable-mapping]"

  ;; Filter out stray closing bracket characters from parser artifacts
  (let [ast (if (vector? ast)
               (vec (remove #(= % "]") ast))
               ast)]


    
    ;; Process the AST node
    (process-ast-node ast class-info context-relationships counter parent-id object-table variable-mapping)))



(defn process-constructor-arg [arg class-info variable-mapping]
  "Process a constructor argument to generate Haxe code"
                                 (cond
    ;; If it's a variable name (starts with "o"), use it directly
    (and (string? arg) (str/starts-with? arg "o"))
    arg
    ;; If it's a literal, use it as-is
    (or (number? arg) (string? arg))
                                   (str arg)
                                   ;; Otherwise, use ast-to-haxe-factory
                                   :else
                                   (ast-to-haxe-factory arg class-info variable-mapping)))
        
(defn generate-construction-statement [entry class-info variable-mapping]
  "Generate a single construction statement from an object table entry"
                                 (let [var-name (:var-name entry)
                                       class-name (:class entry)
        args (:args entry)]
    (cond
      (= class-name "Array")
      ;; Array construction
      (let [haxe-elements (map #(process-constructor-arg % class-info variable-mapping) args)]
        (str "var " var-name " = [" (str/join ", " haxe-elements) "];"))
      
      (= class-name "InnerObject")
      ;; Inner object construction (for constructor arguments)
      (let [haxe-args (map #(process-constructor-arg % class-info variable-mapping) args)]
        (str "var " var-name " = [" (str/join ", " haxe-args) "];"))
      
      :else
      ;; Regular object construction
      (let [haxe-args (map #(process-constructor-arg % class-info variable-mapping) args)]
        (str "var " var-name " = new " class-name "(" (str/join ", " haxe-args) ");")))))

(defn generate-context-wiring-statement [entry object-table context-relationships]
  "Generate a context wiring statement for an object if needed"
                            (let [var-name (:var-name entry)
                                  class-name (:class entry)
                                  needs-context (get context-relationships class-name)]
                              (when needs-context
                                (let [parent-id (:parent entry)
                                      parent-entry (get object-table parent-id)
                                      parent-var (:var-name parent-entry)]
                                  (str var-name ".setContext(" parent-var ");")))))

(defn build-dependencies [entry]
  "Build dependency graph - find which abstract IDs this entry depends on"
  (let [args (:args entry)]
    (filter #(and (string? %) (str/starts-with? % "obj_")) args)))

(defn assign-variable-numbers [object-table]
  "Assign variable numbers in dependency order (leaf nodes first)"
  (let [entries (vec object-table)
        dependency-map (into {} (map (fn [[id entry]] [id (build-dependencies entry)]) entries))
        visited (atom #{})
        result (atom {})
        var-counter (atom 1)]
    (letfn [(visit [id]
              (when-not (contains? @visited id)
                (swap! visited conj id)
                ;; Visit all dependencies first
                (doseq [dep-id (dependency-map id)]
                  (visit dep-id))
                ;; Then assign variable number to this entry
                (let [entry (get object-table id)]
                  (when entry
                    (let [var-name (str "o" @var-counter)]
                      (swap! result assoc id (assoc entry :var-name var-name))
                      (swap! var-counter inc))))))]
      ;; Visit all entries
      (doseq [[id _] entries]
        (visit id))
      @result)))

(defn generate-statements-from-table [object-table]
  "Generate Haxe statements from the object table in dependency order."
  (let [numbered-table (assign-variable-numbers object-table)
        ;; Create a mapping from abstract IDs to variable names
        id-to-var (into {} (map (fn [[id entry]] [id (:var-name entry)]) numbered-table))]
    (for [[id entry] (sort-by #(Integer/parseInt (subs (:var-name (val %)) 1)) numbered-table)]
      (let [{:keys [class var-name args]} entry]
        (cond
          ;; Handle ArrayConstruction - use square bracket syntax
          (= class "ArrayConstruction")
          (let [haxe-elements (map #(cond
                                     (number? %) (str %)  ;; Number - convert to string
                                     (and (string? %) (re-find #"^o\\d+$" %)) %  ;; Variable reference
                                     (and (string? %) (str/starts-with? % "obj_")) (get id-to-var % var-name)  ;; Abstract ID reference
                                     (string? %) (str "\"" % "\"")  ;; String literal
                                     :else (str %)) (remove nil? args))]  ;; Filter out nil values
            (str "var " var-name " = [" (clojure.string/join ", " haxe-elements) "];"))
          
          ;; Handle arrays with generic types
          (re-find #"^Array<.*>$" class)
          (let [haxe-elements (map #(cond
                                     (number? %) (str %)  ;; Number - convert to string
                                     (and (string? %) (re-find #"^o\\d+$" %)) %  ;; Variable reference
                                     (and (string? %) (str/starts-with? % "obj_")) (get id-to-var % var-name)  ;; Abstract ID reference
                                     (string? %) (str "\"" % "\"")  ;; String literal
                                     :else (str %)) args)]
            (str "var " var-name " = [" (clojure.string/join ", " haxe-elements) "];"))
          
          ;; Handle domain objects
          :else
          (let [haxe-args (map #(cond
                                 (number? %) (str %)  ;; Number - convert to string
                                 (and (string? %) (re-find #"^o\\d+$" %)) %  ;; Variable reference
                                 (and (string? %) (str/starts-with? % "obj_")) (get id-to-var % var-name)  ;; Abstract ID reference
                                 (string? %) (str "\"" % "\"")  ;; String literal
                                 :else (str %)) args)]
            (str "var " var-name " = new " class "(" (clojure.string/join ", " haxe-args) ");")))))))

(defn generate-construction-factory-unified [block-statements-ast class-info context-relationships]
  "Generate Haxe factory function from new unified parser AST.
  Input: [:BlockStatements ...] AST from unified parser
  Output: Cargo with Haxe factory function string"

  
  (P/run block-statements-ast

    
    (P/processor
      (fn [ast]
  
        ;; Extract class name from the first object construction in the AST
        (let [class-name (extract-root-class-name ast)
              factory-name (str (clojure.string/lower-case (first class-name)) (subs class-name 1) "Factory")]

          
          ;; Process all statements to build complete object table
          (let [statements (rest ast)  ;; Skip :BlockStatements tag
                ;; Step 1: Process assignments first to build variable mappings
                assignment-results (loop [assignments (filter #(and (vector? %) (= (first %) :Assignment)) statements)
                 results []
                                         counter 1
                                         object-table {}
                                         variable-mapping {}]
            (if (empty? assignments)
                                      [results counter object-table variable-mapping]
              (let [assignment (first assignments)
                    [assignment-tag var-name-node expression] assignment  ;; Destructure: [tag var-name expression]
                    ;; Extract the actual object from the expression wrapper
                    actual-object (second expression)  ;; Get the object inside [:Expression obj]
                    ;; Walk the actual object
                    [var-name new-counter new-table new-mapping] 
                    (walk-ast-and-build-table actual-object class-info context-relationships counter nil object-table variable-mapping)
                    ;; Add mapping for the assignment variable
                    assignment-var-name (extract-variable-name assignment)
                    new-mapping-with-assignment (assoc new-mapping assignment-var-name var-name)]
                (recur (rest assignments)
                                               (conj results {:name assignment-var-name :var-name var-name})
                                               new-counter
                                               new-table
                                               new-mapping-with-assignment))))
                
                [assignments counter object-table variable-mapping] assignment-results
                
                ;; Step 2: Process the final construction (last expression statement)
                final-statements (filter #(and (vector? %) (= (first %) :Expression)) statements)
                final-construction (if (seq final-statements)
                                    (second (last final-statements))  ;; Get the expression from [:Expression expr]
                                    nil)]
  
            
            ;; Walk the final construction
            (let [[final-var final-counter final-table final-mapping] 
                  (if final-construction
                    (walk-ast-and-build-table final-construction class-info context-relationships counter nil object-table variable-mapping)
                    ["null" counter object-table variable-mapping])]
              
              
              ;; Step 3: Generate Haxe statements from the complete object table
              (let [numbered-table (assign-variable-numbers final-table)
                    id-to-var (into {} (map (fn [[id entry]] [id (:var-name entry)]) numbered-table))
                    construction-stmts (generate-statements-from-table final-table)
                    ;; Step 4: Generate context wiring statements
                    context-wiring-stmts (filter some? 
                                                (for [[id entry] numbered-table]
                                                  (let [var-name (:var-name entry)
                                                        class-name (:class entry)
                                                        needs-context (get context-relationships class-name)]
                                                    (when needs-context
                                                      (let [parent-id (:parent entry)
                                                            parent-abstract-id (str "obj_" parent-id)
                                                            parent-entry (get numbered-table parent-abstract-id)
                                                            parent-var (:var-name parent-entry)]
                                                        (str var-name ".setContext(" parent-var ");"))))))]
                
                
                ;; Step 5: Generate the complete factory function
                (let [final-var-name (get id-to-var final-var final-var)
                      factory-code (str "public static function " factory-name "() {\n"
                                         (clojure.string/join "\n    " construction-stmts) "\n"
                                         (when (seq context-wiring-stmts)
                                           (str "\n    // Context wiring\n"
                                                (clojure.string/join "\n    " context-wiring-stmts) "\n"))
                                         "    return " final-var-name ";\n"
                            "}")]
        
                  (P/success-cargo factory-code)))))))
      "Generate unified factory code")
    ))

(defn generate-construction-factory-unified-cargo [cargo]
  "Pipeline wrapper for unified factory generation."
  (try
    (let [stash (:stash cargo)
          construction-ast (:construction-ast stash)
          schema-ast (:schema-ast stash)
          context-relationships (:context-relationships stash)]

      

      ;; Validate that all required stash values are present
      (if (or (nil? construction-ast) (nil? schema-ast) (nil? context-relationships))
        (P/fail-cargo "Missing required stash values: construction-ast, schema-ast, or context-relationships")

        ;; Extract class-info from schema-ast
        (let [class-info (parser/extract-class-info schema-ast)
              ;; Call the unified factory generation function
              haxe-code (generate-construction-factory-unified construction-ast class-info context-relationships)]
  
          (if (P/failed? haxe-code)
            haxe-code
            (let [result (P/success-cargo (:value haxe-code))]
      
              result)))))
    (catch Exception e
      
      (P/fail-cargo (str "Error generating unified construction factory: " (.getMessage e))))))



