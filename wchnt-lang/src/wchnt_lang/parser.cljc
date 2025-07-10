(ns wchnt-lang.parser
  (:require [instaparse.core :as insta]
            [clojure.string :as str]
            [wchnt-lang.schema :as schema]))

(def schema-grammar
  "Schema = DefLine (<NL> DefLine)* <NL>?
   DefLine = CompositionLine | DisjunctionLine | EnumLine
   CompositionLine = Definee <SPACE> '=' <SPACE> Element (<SPACE> Element)* <SPACE>?
   DisjunctionLine = Definee <SPACE> '=' <SPACE> Element (<SPACE> '|' <SPACE> Element)+ <SPACE>?
   EnumLine = Definee <SPACE> '=' <SPACE> '\"' EnumValue '\"' (<SPACE> '|' <SPACE> '\"' EnumValue '\"')+ <SPACE>?
   Definee = Name
   <Name> = #'[A-Za-z][A-Za-z0-9_]*'
   NL = #'\\n+'
   Element = Sigil? TypeMarker ('/' AltName)?
   SPACE = #'\\s+'
   TypeMarker = Name | ArrayType | DictType | EmptyType
   ArrayType = '[' Type ']'
   Type = Name
   DictType = '{' <SPACE>? KeyType <SPACE>? ':' <SPACE>? DValType <SPACE>? '}'
   KeyType = Name 
   DValType = Name
   AltName = Name
   EnumValue =  #'[^\"]+'
   Sigil = ':'  | '@' | '$'
   EmptyType = '_'
   ")



(defn get-parser []
  (insta/parser schema-grammar))


(defn find-node [tag tree]
  (cond
    (and (vector? tree) (= (first tree) tag)) tree
    (vector? tree) (or (some #(find-node tag %) (rest tree))
                       (when (= (first tree) :ArrayType)
                         (find-node tag (rest tree)))
                       (when (= (first tree) :DictType)
                         (find-node tag (rest tree))))
    (seq? tree) (some #(find-node tag %) tree)
    :else nil))

(defn camel-case [s]
  (let [parts (clojure.string/split s #"_")]
    (str (clojure.string/lower-case (first parts))
         (apply str (map clojure.string/capitalize (rest parts))))))

(defn process-element [children]
  (let [sigil-node (find-node :Sigil children)
        type-marker-node (find-node :TypeMarker children)
        alt-name-node (find-node :AltName children)
        sigil (when sigil-node (second sigil-node))
        type-name (cond
                   ;; DictType
                   (and type-marker-node (= (first (first (rest type-marker-node))) :DictType))
                   (let [dict-node (first (rest type-marker-node))
                         key-type-node (find-node :KeyType dict-node)
                         val-type-node (find-node :DValType dict-node)
                         key-type (when key-type-node (second key-type-node))
                         val-type (when val-type-node (second val-type-node))]
                     (str "Map<" key-type ", " val-type ">"))
                   ;; ArrayType
                   (and type-marker-node (= (first (first (rest type-marker-node))) :ArrayType))
                   (let [array-node (first (rest type-marker-node))
                         inner-type (second (find-node :Type (rest array-node)))]
                     (str "Array<" inner-type ">"))
                   ;; EmptyType
                   (and type-marker-node (= (first (first (rest type-marker-node))) :EmptyType))
                   "_Empty"  ;; This will be replaced with the actual interface name during disjunction processing
                   ;; Simple Type (string or :Name)
                   (and type-marker-node (string? (second type-marker-node)))
                   (second type-marker-node)
                   (and type-marker-node (= (first (first (rest type-marker-node))) :Name))
                   (second (first (rest type-marker-node)))
                   ;; Fallback
                   :else nil)
        alt-name (when alt-name-node (second alt-name-node))
        base-type-name (cond
                        ;; DictType
                        (and type-marker-node (= (first (first (rest type-marker-node))) :DictType))
                        (let [dict-node (first (rest type-marker-node))
                              key-type-node (find-node :KeyType dict-node)
                              val-type-node (find-node :DValType dict-node)
                              key-type (when key-type-node (second key-type-node))
                              val-type (when val-type-node (second val-type-node))]
                          (str (camel-case key-type) "To" (clojure.string/capitalize (camel-case val-type))))
                        ;; ArrayType
                        (and type-marker-node (= (first (first (rest type-marker-node))) :ArrayType))
                        (let [array-node (first (rest type-marker-node))]
                          (second (find-node :Type (rest array-node))))
                        ;; EmptyType
                        (and type-marker-node (= (first (first (rest type-marker-node))) :EmptyType))
                        "_Empty"  ;; This will be replaced with the actual interface name during disjunction processing
                        ;; Simple Type (string or :Name)
                        (and type-marker-node (string? (second type-marker-node)))
                        (second type-marker-node)
                        (and type-marker-node (= (first (first (rest type-marker-node))) :Name))
                        (second (first (rest type-marker-node)))
                        :else nil)
        ;; Extract key and value types for DictType
        key-type (when (and type-marker-node (= (first (first (rest type-marker-node))) :DictType))
                   (let [dict-node (first (rest type-marker-node))
                         key-type-node (find-node :KeyType dict-node)]
                     (when key-type-node (second key-type-node))))
        value-type (when (and type-marker-node (= (first (first (rest type-marker-node))) :DictType))
                     (let [dict-node (first (rest type-marker-node))
                           val-type-node (find-node :DValType dict-node)]
                       (when val-type-node (second val-type-node))))
        result {:type type-name
               :name (or alt-name (when base-type-name (str (clojure.string/lower-case (subs base-type-name 0 1)) (subs base-type-name 1))))
               :sigil sigil
               :key-type key-type
               :value-type value-type}]
    result))

(defn parse-input [input]
  (let [parse-result ((get-parser) input)]
    (if (insta/failure? parse-result)
      (schema/syntax-error (str (insta/get-failure parse-result)))
      (schema/syntax-success parse-result))))

(defn split-wchnt-phases [input]
  "Split a WCHNT program into schema and construction phases based on explicit markers."
  (let [[schema construction-block] (str/split input #"## Construction")]
    {:schema (-> schema (str/replace #"## Schema" "") str/trim)
     :construction (if construction-block (str/trim construction-block) "")}))

(defn extract-class-info [ast]
  "Extract class information from parsed schema AST"
  (let [class-list (atom [])
        enum-list (atom [])
        disjunction-list (atom [])
        interface-implementers (atom {})]
    (letfn [(walk-tree [node]
              (cond
                (string? node) nil
                (vector? node)
                (let [[tag & children] node]
                  (case tag
                    :Schema (doseq [child (filter vector? children)]
                              (walk-tree child))
                    :DefLine (walk-tree (first (filter vector? children)))
                    :CompositionLine
                    (let [definee-node (first (filter #(= (first %) :Definee) children))
                          class-name (second definee-node)
                          element-nodes (filter #(= (first %) :Element) children)
                          processed-elements (map process-element element-nodes)]
                      (swap! class-list conj {:name class-name
                                             :elements processed-elements
                                             :type :composition}))
                    :DisjunctionLine
                    (let [definee-node (first (filter #(= (first %) :Definee) children))
                          interface-name (second definee-node)
                          element-nodes (filter #(= (first %) :Element) children)
                          processed-elements (map process-element element-nodes)
                          type-names (map :type processed-elements)
                          has-empty-type (some #(= % "_Empty") type-names)
                          ;; Replace _Empty with the actual interface name for empty types
                          processed-type-names (map #(if (= % "_Empty") 
                                                       (str "_" interface-name) 
                                                       %) type-names)]
                      ;; Track which classes implement this interface
                      (doseq [implementer processed-type-names]
                        (swap! interface-implementers update implementer (fnil conj []) interface-name))
                      ;; Add empty class if needed
                      (when has-empty-type
                        (let [empty-class-name (str "_" interface-name)]
                          (swap! class-list conj {:name empty-class-name
                                                 :elements []
                                                 :type :empty
                                                 :implements [interface-name]})))
                      (swap! disjunction-list conj {:name interface-name
                                                   :implementers processed-type-names
                                                   :has-empty-type has-empty-type
                                                   :type :disjunction}))
                    :EnumLine
                    (let [definee-node (first (filter #(= (first %) :Definee) children))
                          enum-name (second definee-node)
                          enum-value-nodes (filter #(= (first %) :EnumValue) children)
                          enum-values (map second enum-value-nodes)]
                      (swap! enum-list conj {:name enum-name
                                            :values enum-values
                                            :type :enum}))
                    nil))
                :else nil))]
      (walk-tree ast)
      {:classes @class-list
       :enums @enum-list
       :disjunctions @disjunction-list
       :interface-implementers @interface-implementers})))

(defn element-type-to-rule [element-type enums]
  "Convert an element type to its corresponding grammar rule name"
  (cond
    (= element-type "String") "StringLiteral"
    (= element-type "int") "IntLiteral"
    (str/starts-with? element-type "Array<") 
    (let [inner-type (str/replace (str/replace element-type "Array<" "") ">" "")
          inner-rule (element-type-to-rule inner-type enums)]
      (str "ArrayConstruction"))
    (str/starts-with? element-type "Map<") "MapConstruction"
    ;; Check if it's an enum type
    (some #(= (:name %) element-type) enums) (str element-type "Value")
    :else (str element-type "Construction")))

(defn generate-strict-arg-rules [elements enums disjunctions]
  "Generate strict grammar rules for class elements (with R prefix)"
  (for [element elements]
    (let [element-type (:type element)
          expected-rule (cond
                         (= element-type "String") "StringLiteral"
                         (= element-type "int") "IntLiteral"
                         (str/starts-with? element-type "Array<") 
                         (let [inner-type (str/replace (str/replace element-type "Array<" "") ">" "")
                               element-rule (cond
                                            (= inner-type "String") "StringLiteral"
                                            (= inner-type "int") "IntLiteral"
                                            (some #(= (:name %) inner-type) enums) (str inner-type "Value")
                                            (some #(= (:name %) inner-type) (map :name disjunctions))
                                            (let [disjunction (first (filter #(= (:name %) inner-type) disjunctions))
                                                  implementers (:implementers disjunction)]
                                              (str "(" (str/join " | " (map #(str "R" % "Construction") implementers)) ")"))
                                            :else (str "R" inner-type "Construction"))
                               array-element-rule (str inner-type "ArrayElement")]
                           (str "ArrayConstruction | (<WS>+ (" (str/join " | " [array-element-rule element-rule]) "))*"))
                         (str/starts-with? element-type "Map<") (str (clojure.string/capitalize (:name element)) "MapConstruction")
                         (some #(= (:name %) element-type) enums) (str element-type "Value")
                         (some #(= (:name %) element-type) (map :name disjunctions))
                         (let [disjunction (first (filter #(= (:name %) element-type) disjunctions))
                               implementers (:implementers disjunction)
                               has-empty-type (:has-empty-type disjunction)]
                           (if has-empty-type
                             (str "(" (str/join " | " (map #(str "R" % "Construction") implementers)) " | LocalEmpty)")
                             (str "(" (str/join " | " (map #(str "R" % "Construction") implementers)) ")")))
                         :else (str "R" element-type "Construction"))]
      (str "(" expected-rule " | VariableReference)"))))

(defn generate-relaxed-arg-rules [elements enums disjunctions]
  "Generate relaxed grammar rules for class elements (without R prefix)"
  (for [element elements]
    (let [element-type (:type element)
          expected-rule (cond
                         (= element-type "String") "StringLiteral"
                         (= element-type "int") "IntLiteral"
                         (str/starts-with? element-type "Array<") 
                         (let [inner-type (str/replace (str/replace element-type "Array<" "") ">" "")
                               element-rule (cond
                                            (= inner-type "String") "StringLiteral"
                                            (= inner-type "int") "IntLiteral"
                                            (some #(= (:name %) inner-type) enums) (str inner-type "Value")
                                            (some #(= (:name %) inner-type) (map :name disjunctions))
                                            (let [disjunction (first (filter #(= (:name %) inner-type) disjunctions))
                                                  implementers (:implementers disjunction)]
                                              (str "(" (str/join " | " (map #(str % "Construction") implementers)) ")"))
                                            :else (str inner-type "Construction"))
                               array-element-rule (str inner-type "ArrayElement")]
                           (str "ArrayConstruction | (<WS>+ (" (str/join " | " [array-element-rule element-rule]) "))*"))
                         (str/starts-with? element-type "Map<") (str (clojure.string/capitalize (:name element)) "MapConstruction")
                         (some #(= (:name %) element-type) enums) (str element-type "Value")
                         (some #(= (:name %) element-type) (map :name disjunctions))
                         (let [disjunction (first (filter #(= (:name %) element-type) disjunctions))
                               implementers (:implementers disjunction)
                               has-empty-type (:has-empty-type disjunction)]
                           (if has-empty-type
                             (str "(" (str/join " | " (map #(str % "Construction") implementers)) " | LocalEmpty)")
                             (str "(" (str/join " | " (map #(str % "Construction") implementers)) ")")))
                         :else (str element-type "Construction"))]
      (str "(" expected-rule " | VariableReference)"))))

(defn generate-class-grammar [class enums disjunctions]
  "Generate strict (RTypeConstruction) and relaxed (TypeConstruction) grammar strings for a single class"
  (let [class-name (:name class)
        elements (:elements class)
        arg-rules (generate-strict-arg-rules elements enums disjunctions)
        relaxed-args (generate-relaxed-arg-rules elements enums disjunctions)
        strict-rule (if (empty? elements)
                      (str "R" class-name "Construction = <'['> <':'> <'" class-name "'> <WS>? <']'>" )
                      (str "R" class-name "Construction = <'['> <':'> <'" class-name "'> <WS>? "
                           (str/join " <WS>+ " arg-rules) " <WS>? <']'>"))
        relaxed-rule (if (empty? elements)
                        (str class-name "Construction = <'['> (<':'> <'" class-name "'>)? <WS>? <']'>" )
                        (str class-name "Construction = <'['> (<':'> <'" class-name "'>)? <WS>? "
                             (str/join " <WS>+ " relaxed-args) " <WS>? <']'>"))]
    [strict-rule relaxed-rule]))

(defn generate-enum-grammar [enum]
  "Generate a grammar string for a single enum"
  (let [enum-name (:name enum)
        enum-values (:values enum)
        value-alternatives (str/join " | " (map #(str "'" % "'") enum-values))]
    (str enum-name "Value = " value-alternatives)))

(defn generate-array-grammar [array-field classes enums disjunctions]
  "Generate a specific grammar rule for an array field"
  (let [field-name (:name array-field)
        element-type (:type array-field)
        ;; Extract the inner type from Array<Type>
        inner-type (str/replace (str/replace element-type "Array<" "") ">" "")
        ;; Determine what construction rule to use for the inner type
        element-rule (cond
                      (= inner-type "String") "StringLiteral"
                      (= inner-type "int") "IntLiteral"
                      (str/starts-with? inner-type "Array<") 
                      (let [nested-inner-type (str/replace (str/replace inner-type "Array<" "") ">" "")
                            nested-rule (element-type-to-rule nested-inner-type enums)]
                        (str nested-rule))
                      (str/starts-with? inner-type "Map<") "MapConstruction"
                      ;; Check if it's an enum type
                      (some #(= (:name %) inner-type) enums) (str inner-type "Value")
                      ;; Check if it's a disjunction type
                      (some #(= (:name %) inner-type) (map :name disjunctions))
                      (let [disjunction (first (filter #(= (:name %) inner-type) disjunctions))
                            implementers (:implementers disjunction)]
                        (str "(" (str/join " | " (map #(str % "Construction") implementers)) ")"))
                      ;; Default to class construction
                      :else (str inner-type "Construction"))
        rule-name (str (clojure.string/capitalize field-name) "ArrayConstruction")]
    (str rule-name " = <'['> <WS>? <':'> <'Array'> <'/'> <'" field-name "'> (<WS>+ " element-rule ")* <WS>? <']'>")))

(defn generate-map-grammar [map-field classes enums disjunctions]
  "Generate a specific grammar rule for a map field"
  (let [field-name (:name map-field)
        element-type (:type map-field)
        ;; Extract key and value types from Map<Key,Value>
        type-parts (str/split (str/replace (str/replace element-type "Map<" "") ">" "") #",")
        key-type (str/trim (first type-parts))
        value-type (str/trim (second type-parts))
        ;; Determine construction rules for key and value types
        key-rule (cond
                  (= key-type "String") "StringLiteral"
                  (= key-type "int") "IntLiteral"
                  (some #(= (:name %) key-type) enums) (str key-type "Value")
                  :else (str key-type "Construction"))
        value-rule (cond
                    (= value-type "String") "StringLiteral"
                    (= value-type "int") "IntLiteral"
                    (str/starts-with? value-type "Array<") 
                    (let [inner-type (str/replace (str/replace value-type "Array<" "") ">" "")
                          inner-rule (element-type-to-rule inner-type enums)]
                      (str inner-rule))
                    (str/starts-with? value-type "Map<") "MapConstruction"
                    (some #(= (:name %) value-type) enums) (str value-type "Value")
                    :else (str value-type "Construction"))
        rule-name (str (clojure.string/capitalize field-name) "MapConstruction")]
    (str rule-name " = <'{'> (<WS>? " key-rule " <WS>? <':'> <WS>? " value-rule ")* <WS>? <'}'>")))

(defn generate-array-element-grammar [class enums disjunctions]
  "Generate array-element grammar strings for a single class (with optional whitespace between arguments)"
  (let [class-name (:name class)
        elements (:elements class)
        arg-rules (generate-relaxed-arg-rules elements enums disjunctions)
        array-element-rule (if (empty? elements)
                             (str class-name "ArrayElement = <'['> (<':'> <'" class-name "'>)? <WS>? <']'>" )
                             (str class-name "ArrayElement = <'['> (<':'> <'" class-name "'>)? <WS>? "
                                  (str/join " <WS>? " arg-rules) " <WS>? <']'>"))]
    array-element-rule))

(defn generate-class-grammars [classes enums disjunctions]
  "Generate grammar rules for all classes"
  (let [sorted-classes (sort-by #(if (= (:type %) :empty) 1 0) classes)
        class-grammars (mapcat #(let [[strict-rule relaxed-rule] (generate-class-grammar % enums disjunctions)]
                                  [strict-rule relaxed-rule])
                                sorted-classes)]
    class-grammars))

(defn generate-disjunction-grammars [disjunctions]
  "Generate grammar rules for disjunctions/interfaces"
  (let [disjunction-rules (for [disjunction disjunctions]
                            (let [interface-name (:name disjunction)
                                  implementers (:implementers disjunction)
                                  strict-rule (str "R" interface-name "Construction = "
                                                   (str/join " | " (map #(str "R" % "Construction") implementers)))
                                  relaxed-rule (str interface-name "Construction = "
                                                    (str/join " | " (map #(str % "Construction") implementers)))]
                              [strict-rule relaxed-rule]))
        disjunction-grammars (mapcat identity disjunction-rules)]
    disjunction-grammars))

(defn generate-map-field-grammars [classes enums disjunctions]
  "Generate grammar rules for map fields"
  (let [map-rules (for [class classes
                       element (:elements class)
                       :when (str/starts-with? (:type element) "Map<")]
                   (let [g (generate-map-grammar element classes enums disjunctions)]
                     g))]
    map-rules))

(defn generate-array-grammars [classes enums disjunctions]
  "Generate grammar rules for arrays"
  (let [;; Get all types that can be used in arrays
        array-element-types (->> (for [class classes
                                       element (:elements class)
                                       :when (str/starts-with? (:type element) "Array<")]
                                   (-> (:type element)
                                       (str/replace "Array<" "")
                                       (str/replace ">" "")))
                                 distinct)
        ;; Generate array-element versions of construction rules (with optional whitespace)
        array-element-rules (for [class classes
                                  :when (some #(= % (str (:name class))) array-element-types)]
                              (let [rule (generate-array-element-grammar class enums disjunctions)]
                                rule))
        ;; Use array-element versions for array elements
        array-construction-rule (if (seq array-element-types)
                                  (str "ArrayConstruction = <'['> <WS>? <':'> <'Array'> <'/'> TypeName (<WS>? ("
                                       (str/join " | " (map #(str % "ArrayElement") array-element-types))
                                       "))* <WS>? <']'>")
                                  "ArrayConstruction = <'['> <WS>? <':'> <'Array'> <'/'> TypeName <WS>? <']'>")
        ]
    {:array-element-rules array-element-rules
     :array-construction-rule array-construction-rule}))

(defn generate-map-grammars [classes enums disjunctions]
  "Generate grammar rules for generic maps"
  (let [;; Get all map types that can be used in generic map constructions
        map-types (->> (for [class classes
                             element (:elements class)
                             :when (str/starts-with? (:type element) "Map<")]
                         (:type element))
                       distinct)
        ;; Generate map element rules for different key-value combinations
        map-element-rules (for [map-type map-types]
                            (let [type-parts (str/split (str/replace (str/replace map-type "Map<" "") ">" "") #",")
                                  key-type (str/trim (first type-parts))
                                  value-type (str/trim (second type-parts))
                                  key-rule (cond
                                            (= key-type "String") "StringLiteral"
                                            (= key-type "int") "IntLiteral"
                                            (some #(= (:name %) key-type) enums) (str key-type "Value")
                                            :else (str key-type "Construction"))
                                  value-rule (cond
                                              (= value-type "String") "StringLiteral"
                                              (= value-type "int") "IntLiteral"
                                              (str/starts-with? value-type "Array<") "ArrayConstruction"
                                              (str/starts-with? value-type "Map<") "MapConstruction"
                                              (some #(= (:name %) value-type) enums) (str value-type "Value")
                                              :else (str value-type "Construction"))
                                  rule-name (str key-type "To" value-type "MapElement")]
                              (str rule-name " = " key-rule " <WS>? <':'> <WS>? " value-rule)))
        ;; Use map-element versions for map elements
        map-construction-rule (if (seq map-types)
                                (str "MapConstruction = <'['> <WS>? <':'> <'Map'> <'/'> <'{'> TypeName <':'> TypeName <'}'> (<WS>? ("
                                     (str/join " | " (map #(let [type-parts (str/split (str/replace (str/replace % "Map<" "") ">" "") #",")
                                                              key-type (str/trim (first type-parts))
                                                              value-type (str/trim (second type-parts))]
                                                          (str key-type "To" value-type "MapElement")) map-types))
                                     "))* <WS>? <']'>")
                                "MapConstruction = <'['> <WS>? <':'> <'Map'> <'/'> <'{'> TypeName <':'> TypeName <'}'> <WS>? <']'>")
        ]
    {:map-element-rules map-element-rules
     :map-construction-rule map-construction-rule}))

(defn generate-multi-step-rules [classes]
  "Generate grammar rules for multi-step constructions"
  (let [all-construction-types (concat 
                                (map #(str (:name %) "Construction") classes)
                                ["ArrayConstruction"]  ;; Include the generic array construction
                                ["MapConstruction"]  ;; Include the generic map construction
                                (map #(str (clojure.string/capitalize (:name %)) "MapConstruction")
                                     (for [class classes
                                           element (:elements class)
                                           :when (str/starts-with? (:type element) "Map<")]
                                       element)))
        statement-rule (str "Statement = VariableAssignment | (" (str/join " | " all-construction-types) ")")
        variable-assignment-rule (str "VariableAssignment = '$' VariableName <WS>? '=' <WS>? (" (str/join " | " all-construction-types) ")")
        multi-step-rules ["MultiStepConstruction = (WS? Statement WS?)*"
                         statement-rule
                         variable-assignment-rule
                         "VariableReference = '$' #'[a-zA-Z_][a-zA-Z0-9_]*'"
                         "VariableName = #'[a-zA-Z_][a-zA-Z0-9_]*'"]]
    multi-step-rules))

(defn generate-common-rules []
  "Generate common grammar rules"
  ["WS = #'[\\s\\n,]+'"
   "IntLiteral = #'\\d+'"
   "StringLiteral = '\"' #'[^\"]*' '\"'"
   "LocalEmpty = '_'"
   "TypeName = #'[A-Z][a-zA-Z0-9]*'"])

(defn generate-construction-grammar [class-info]
  "Generate a single composed grammar string for all classes and enums"
  (let [classes (:classes class-info)
        enums (:enums class-info)
        disjunctions (:disjunctions class-info)

        
        ;; Start with the assemblage root as the main rule
        grammar-parts []
        
        ;; Add class grammars
        class-grammars (generate-class-grammars classes enums disjunctions)
        grammar-parts (concat grammar-parts class-grammars)
        
        ;; Add disjunction/interface rules
        disjunction-grammars (generate-disjunction-grammars disjunctions)
        grammar-parts (concat grammar-parts disjunction-grammars)
        
        ;; Add array grammars
        array-grammars (generate-array-grammars classes enums disjunctions)
        grammar-parts (concat grammar-parts (:array-element-rules array-grammars) [(:array-construction-rule array-grammars)])
        
        ;; Add map grammars (generic first, then specific)
        map-grammars (generate-map-grammars classes enums disjunctions)
        grammar-parts (concat grammar-parts (:map-element-rules map-grammars) [(:map-construction-rule map-grammars)])
        
        ;; Add map construction rules for each map field (specific after generic)
        map-field-grammars (generate-map-field-grammars classes enums disjunctions)
        grammar-parts (concat grammar-parts map-field-grammars)
        
        ;; Add enum value rules
        enum-rules (map #(generate-enum-grammar %) enums)
        grammar-parts (concat grammar-parts enum-rules)
        
        ;; Add disjunction grammars for empty classes
        disjunction-grammars (for [disjunction disjunctions
                                  :when (:has-empty-type disjunction)]
                              (let [interface-name (:name disjunction)
                                    empty-class-name (str "_" interface-name)
                                    g (str empty-class-name "Construction = <'['> <':'> <'" empty-class-name "'> <WS>? <']'>")]
                                g))
        grammar-parts (concat grammar-parts disjunction-grammars)
        
        ;; Add multi-step construction rules
        multi-step-rules (generate-multi-step-rules classes)
        grammar-parts (concat grammar-parts multi-step-rules)
        
        ;; Add common rules
        common-rules (generate-common-rules)
        grammar-parts (concat grammar-parts common-rules)
        
        ;; Join all parts - MultiStepConstruction will be the start symbol
        composed-grammar (str/join "\n" grammar-parts)]
    composed-grammar))

(defn schema-to-construction-grammar-impl [schema-input]
  "Implementation of schema-to-construction-grammar without exception handling"
  (let [parse-result ((get-parser) schema-input)]
    (if (insta/failure? parse-result)
      (schema/syntax-error (str "Schema parsing failed: " (insta/get-failure parse-result)))
      (let [class-info (extract-class-info parse-result)
            grammar-string (generate-construction-grammar class-info)]
        (schema/syntax-success {:grammar grammar-string
                                :class-info class-info})))))

(defn schema-to-construction-grammar [schema-input]
  "Convert WCHNT schema to construction grammar"
  #?(:clj
     (try
       (schema-to-construction-grammar-impl schema-input)
       (catch Exception e
         (schema/syntax-error (str "Error generating construction grammar: " (.getMessage e)))))
     :cljs
     (try
       (schema-to-construction-grammar-impl schema-input)
       (catch :default e
         (schema/syntax-error (str "Error generating construction grammar: " (.-message e)))))))

(defn walk-and-resolve [node context classes interface-to-empty]
  "Helper function to walk AST and resolve LocalEmpty nodes"
  (cond
    (string? node) node
    (vector? node)
    (let [[tag & children] node]
      (case tag
        :LocalEmpty
        (let [expected-type context
              empty-class (get interface-to-empty expected-type)]
          (if empty-class
            [:LocalEmpty empty-class] ; Keep LocalEmpty tag but add resolved class
            [:LocalEmpty "Unknown"])) ; Fallback if no empty class found
        ;; For construction nodes, pass context to children
        (if (str/ends-with? (name tag) "Construction")
          (let [class-name (str/replace (name tag) "Construction" "")
                class-info (first (filter #(= (:name %) class-name) classes))
                element-types (map :type (:elements class-info))
                resolved-children (map-indexed 
                                   (fn [idx child] 
                                     (walk-and-resolve child (nth element-types idx nil) classes interface-to-empty))
                                   children)]
            (vec (cons tag resolved-children)))
          ;; For other nodes, just walk children without context
          (vec (cons tag (map #(walk-and-resolve % nil classes interface-to-empty) children)))))
    (seq? node) (map #(walk-and-resolve % context classes interface-to-empty) node)
    :else node)))

(defn resolve-local-empty [ast class-info]
  "Post-process AST to replace LocalEmpty nodes with appropriate empty classes"
  (let [classes (:classes class-info)
        interface-implementers (:interface-implementers class-info)
        ;; Build lookup: interface-name -> empty-class-name
        interface-to-empty (into {} 
                                (for [class classes
                                      :when (= (:type class) :empty)
                                      :when (seq (:implements class))]
                                  [(:implements class) (:name class)]))]
    (walk-and-resolve ast nil classes interface-to-empty)))

(defn resolve-variable-references [ast variable-assignments class-info]
  "Walk through AST and replace variable references with their actual values"
  (letfn [(walk-and-resolve [node]
            (cond
              (string? node) 
              ;; Check if this string is a variable name
              (if (contains? variable-assignments node)
                (do
              
                  (get variable-assignments node))
                node)
              (vector? node)
              (let [[tag & children] node]
                (vec (cons tag (map walk-and-resolve children))))
              (seq? node) 
              (map walk-and-resolve node)
              :else node))]
    (walk-and-resolve ast)))



(defn split-statements [input-string]
  "Split input string into statements using full-stop as separator, treating newlines as whitespace.
   Ignores full-stops inside quoted strings."
  (let [chars (vec input-string)
        len (count chars)]
    (loop [i 0
           current-statement []
           statements []
           in-string false
           escape-next false]
      (if (>= i len)
        ;; End of input - add final statement if not empty
        (let [final-statements (if (seq current-statement)
                                (conj statements (str/trim (str/join current-statement)))
                                statements)]
          (filter seq final-statements))
        (let [char (nth chars i)]
          (cond
            ;; Handle escape sequences
            escape-next
            (recur (inc i) (conj current-statement char) statements in-string false)
            
            ;; Handle string boundaries
            (= char \")
            (recur (inc i) (conj current-statement char) statements (not in-string) false)
            
            ;; Handle full-stop (statement separator) - only if not in a string
            (and (= char \.) (not in-string))
            (let [statement (str/trim (str/join current-statement))]
              (if (seq statement)
                (recur (inc i) [] (conj statements statement) false false)
                (recur (inc i) [] statements false false)))
            
            ;; All other characters
            :else
            (recur (inc i) (conj current-statement char) statements in-string (= char \\))))))))



(defn parse-multi-step-construction [construction-input class-info]
  "Parse multi-step construction with variable assignments and references"
  (let [grammar-string (generate-construction-grammar class-info)
        construction-parser (insta/parser grammar-string :start :MultiStepConstruction)]

    ;; Parse the entire input as a MultiStepConstruction
    (let [parse-result (construction-parser construction-input)]
      (if (insta/failure? parse-result)
        (schema/syntax-error (str "Failed to parse construction: " (insta/get-failure parse-result)))
        (do
          ;; Extract statements from the parsed result
          (let [statements (filter #(and (vector? %) (= (first %) :Statement)) parse-result)
                assignments (atom [])
                final-construction (atom nil)]
            (doseq [statement statements]
              (let [statement-content (second statement)]
                (if (= (first statement-content) :VariableAssignment)
                  ;; Handle variable assignment
                  (let [[_ _ [_ var-name] _ construction-ast] statement-content]
                    (swap! assignments conj {:name var-name :construction construction-ast}))
                  ;; Handle final construction
                  (reset! final-construction statement-content))))
            (let [structured-ast {:type :MultiStepConstruction
                                 :assignments @assignments
                                 :final-construction @final-construction}]
              (schema/syntax-success structured-ast))))))))

(defn parse-construction-impl [schema-input construction-input]
  "Implementation of parse-construction without exception handling"
  (let [parse-result ((get-parser) schema-input)]
    (if (insta/failure? parse-result)
      (schema/syntax-error (str "Schema parsing failed: " (insta/get-failure parse-result)))
      (let [class-info (extract-class-info parse-result)]
        (parse-multi-step-construction construction-input class-info)))))

(defn parse-construction [schema-input construction-input]
  "Parse construction syntax using schema-generated grammar"
  #?(:clj
     (try
       (parse-construction-impl schema-input construction-input)
       (catch Exception e
         (schema/syntax-error (str "Error parsing construction: " (.getMessage e)))))
     :cljs
     (try
       (parse-construction-impl schema-input construction-input)
       (catch :default e
         (schema/syntax-error (str "Error parsing construction: " (.-message e)))))))