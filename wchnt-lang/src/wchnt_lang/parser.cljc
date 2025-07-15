(ns wchnt-lang.parser
  (:require [instaparse.core :as insta]
            [clojure.string :as str]
            [wchnt-lang.pipeline :as P]
            [wchnt-lang.schema :as schema]))

;; Grammar for splitting construction statements


(def schema-grammar
  "
Schema = DefLine (<NL> DefLine)* <NL>?
DefLine = CompositionLine | DisjunctionLine | EnumLine
CompositionLine = Definee <SPACE> '=' <SPACE> Element (<SPACE> Element)* <SPACE>?
DisjunctionLine = Definee <SPACE> '=' <SPACE> Element (<SPACE> '|' <SPACE> Element)+ <SPACE>?
EnumLine = Definee <SPACE> '=' <SPACE> '\"' EnumValue '\"' (<SPACE> '|' <SPACE> '\"' EnumValue '\"')+ <SPACE>?
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

(defn get-schema-parser []
  "Get the schema parser for parsing WCHNT schema definitions"
  (insta/parser schema-grammar))


(defn find-node [tag tree]
  (cond
    (and (vector? tree) (= (first tree) tag)) tree
    (vector? tree) (or (some #(find-node tag %) (rest tree))
                       (when (= (first tree) :ArrayType)
                         (find-node tag (rest tree)))
                       (when (= (first tree) :MapType)
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
        type-node (find-node :Type children)
        alt-name-node (find-node :AltName children)
        sigil (when sigil-node (second sigil-node))
        type-name (cond
                   ;; MapType (from TypeMarker) - convert to Map<Key,Value> format
                   (and type-marker-node (= (first (first (rest type-marker-node))) :MapType))
                   (let [map-node (first (rest type-marker-node))
                         key-type-node (find-node :KeyType map-node)
                         val-type-node (find-node :ValType map-node)
                         key-type (when key-type-node (second key-type-node))
                         val-type (cond
                                  ;; If val-type-node is an ArrayType, convert to Array<Type> format
                                  (and val-type-node (vector? (second val-type-node)) 
                                       (= (first (second val-type-node)) :ArrayType))
                                  (let [array-node (second val-type-node)
                                        inner-type-node (find-node :Type (rest array-node))
                                        inner-type (when inner-type-node (second inner-type-node))]
                                    (str "Array<" inner-type ">"))
                                  ;; Otherwise use the value directly
                                  val-type-node (second val-type-node)
                                  :else nil)]
                     (str "Map<" key-type ", " val-type ">"))
                   ;; ArrayType (from TypeMarker) - convert to Array<Type> format
                   (and type-marker-node (= (first (first (rest type-marker-node))) :ArrayType))
                   (let [array-node (first (rest type-marker-node))
                         inner-type-node (find-node :Type (rest array-node))
                         inner-type (when inner-type-node (second inner-type-node))]
                     (str "Array<" inner-type ">"))
                   ;; EmptyType (from TypeMarker)
                   (and type-marker-node (= (first (first (rest type-marker-node))) :EmptyType))
                   "_Empty"
                   ;; Simple Type from TypeMarker (string or :Name)
                   (and type-marker-node (string? (second type-marker-node)))
                   (second type-marker-node)
                   (and type-marker-node (= (first (first (rest type-marker-node))) :Name))
                   (second (first (rest type-marker-node)))
                   ;; Simple Type from (Sigil? Type) structure
                   (and type-node (string? (second type-node)))
                   (second type-node)
                   ;; Fallback
                   :else nil)
        alt-name (when alt-name-node (second alt-name-node))
        base-type-name (cond
                        ;; MapType (from TypeMarker)
                        (and type-marker-node (= (first (first (rest type-marker-node))) :MapType))
                        (let [map-node (first (rest type-marker-node))
                              key-type-node (find-node :KeyType map-node)
                              val-type-node (find-node :ValType map-node)
                              key-type (when key-type-node (second key-type-node))
                              val-type (cond
                                       ;; If val-type-node is an ArrayType, extract the inner type
                                       (and val-type-node (vector? (second val-type-node)) 
                                            (= (first (second val-type-node)) :ArrayType))
                                       (second (find-node :Type (rest (second val-type-node))))
                                       ;; Otherwise use the value directly
                                       val-type-node (second val-type-node)
                                       :else nil)]
                          (str (camel-case key-type) "To" (clojure.string/capitalize (camel-case val-type))))
                        ;; ArrayType (from TypeMarker)
                        (and type-marker-node (= (first (first (rest type-marker-node))) :ArrayType))
                        (let [array-node (first (rest type-marker-node))]
                          (second (find-node :Type (rest array-node))))
                        ;; EmptyType (from TypeMarker)
                        (and type-marker-node (= (first (first (rest type-marker-node))) :EmptyType))
                        "_Empty"
                        ;; Simple Type from TypeMarker (string or :Name)
                        (and type-marker-node (string? (second type-marker-node)))
                        (second type-marker-node)
                        (and type-marker-node (= (first (first (rest type-marker-node))) :Name))
                        (second (first (rest type-marker-node)))
                        ;; Simple Type from (Sigil? Type) structure
                        (and type-node (string? (second type-node)))
                        (second type-node)
                        :else nil)
        ;; Extract key and value types for MapType
        key-type (when (and type-marker-node (= (first (first (rest type-marker-node))) :MapType))
                   (let [map-node (first (rest type-marker-node))
                         key-type-node (find-node :KeyType map-node)]
                     (when key-type-node (second key-type-node))))
        value-type (when (and type-marker-node (= (first (first (rest type-marker-node))) :MapType))
                    (let [map-node (first (rest type-marker-node))
                          val-type-node (find-node :ValType map-node)]
                      (when val-type-node (second val-type-node))))
        result {:type type-name
               :name (or alt-name (when base-type-name (str (clojure.string/lower-case (subs base-type-name 0 1)) (subs base-type-name 1))))
               :sigil sigil
               :key-type key-type
               :value-type value-type}]
    result))

(defn schema-wchnt->schema-ast [input]
  (let [parse-result ((get-schema-parser) input)]
    (if (insta/failure? parse-result)
      (P/fail-cargo (str "Schema parsing failed: " (insta/get-failure parse-result)))
      (P/success-cargo parse-result))))



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

;; Platform literal type management
(defn primitive-type->literal-rule [type-name]
  "Convert a primitive type name to its corresponding grammar literal rule name"
  (case type-name
    "String" "StringLiteral"
    "Int" "IntLiteral"
    "Float" "FloatLiteral"
    "Bool" "BoolLiteral"
    nil))

(defn element-type-to-rule [element-type enums]
  "Convert an element type to its corresponding grammar rule name"
  (cond
    (primitive-type->literal-rule element-type) (primitive-type->literal-rule element-type)
    (str/starts-with? element-type "Array<") 
    (let [inner-type (str/replace (str/replace element-type "Array<" "") ">" "")
          inner-rule (element-type-to-rule inner-type enums)]
      (str "ArrayConstruction"))
    (str/starts-with? element-type "Map<") "MapConstruction"
    ;; Check if it's an enum type
    (some #(= (:name %) element-type) enums) (str element-type "Value")
    :else (str element-type "Construction")))

(defn generate-strict-arg-rules [elements enums disjunctions classes]
  "Generate strict grammar rules for class elements (with R prefix)"
  (for [element elements]
    (let [element-type (:type element)
          expected-rule (cond
                         (primitive-type->literal-rule element-type) (primitive-type->literal-rule element-type)
                         (str/starts-with? element-type "Array<") 
                         (let [inner-type (str/replace (str/replace element-type "Array<" "") ">" "")
                               element-rule (cond
                                            (primitive-type->literal-rule inner-type) (primitive-type->literal-rule inner-type)
                                            (some #(= (:name %) inner-type) enums) (str inner-type "Value")
                                            (some #(= (:name %) inner-type) (map :name disjunctions))
                                            (let [disjunction (first (filter #(= (:name %) inner-type) disjunctions))
                                                  implementers (:implementers disjunction)]
                                              (str "(" (str/join " | " (map #(str "R" % "Construction") implementers)) ")"))
                                            :else (str "R" inner-type "Construction"))
                               ;; Generate type-specific array construction rule name
                               array-construction-rule (str inner-type "ArrayConstruction")
                               ;; Only include array element rule if the inner type is actually used as an array element
                               array-element-types (->> (for [class classes
                                                              element (:elements class)
                                                              :when (str/starts-with? (:type element) "Array<")]
                                                          (-> (:type element)
                                                              (str/replace "Array<" "")
                                                              (str/replace ">" "")))
                                                        distinct)
                               has-array-element-rule (some #(= % inner-type) array-element-types)
                               ;; For disjunctions, we need to reference the implementer array elements
                               array-element-rule (cond
                                                   (and has-array-element-rule (some #(= (:name %) inner-type) disjunctions))
                                                   (let [disjunction (first (filter #(= (:name %) inner-type) disjunctions))
                                                         implementers (:implementers disjunction)]
                                                     (str "(" (str/join " | " (map #(str % "ArrayElement") implementers)) ")"))
                                                   has-array-element-rule
                                                   (str inner-type "ArrayElement")
                                                   :else nil)]
                           (if array-element-rule
                             (str array-construction-rule " | (<WS>+ (" (str/join " | " [array-element-rule element-rule]) "))*")
                             (str array-construction-rule " | (<WS>+ " element-rule ")*")))
                         (str/starts-with? element-type "Map<") 
                         (let [key-type (:key-type element)
                               value-type (:value-type element)
                               map-rule-name (str key-type "To" (clojure.string/capitalize value-type) "MapConstruction")]
                           (str "(MapConstruction | " map-rule-name ")"))
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

(defn generate-relaxed-arg-rules [elements enums disjunctions classes]
  "Generate relaxed grammar rules for class elements (without R prefix)"
  (for [element elements]
    (let [element-type (:type element)
          expected-rule (cond
                         (primitive-type->literal-rule element-type) (primitive-type->literal-rule element-type)
                         (str/starts-with? element-type "Array<") 
                         (let [inner-type (str/replace (str/replace element-type "Array<" "") ">" "")
                               element-rule (cond
                                            (primitive-type->literal-rule inner-type) (primitive-type->literal-rule inner-type)
                                            (some #(= (:name %) inner-type) enums) (str inner-type "Value")
                                            (some #(= (:name %) inner-type) (map :name disjunctions))
                                            (let [disjunction (first (filter #(= (:name %) inner-type) disjunctions))
                                                  implementers (:implementers disjunction)]
                                              (str "(" (str/join " | " (map #(str % "Construction") implementers)) ")"))
                                            :else (str inner-type "Construction"))
                               ;; Generate type-specific array construction rule name
                               array-construction-rule (str inner-type "ArrayConstruction")
                               ;; Only include array element rule if the inner type is actually used as an array element
                               array-element-types (->> (for [class classes
                                                              element (:elements class)
                                                              :when (str/starts-with? (:type element) "Array<")]
                                                          (-> (:type element)
                                                              (str/replace "Array<" "")
                                                              (str/replace ">" "")))
                                                        distinct)
                               has-array-element-rule (some #(= % inner-type) array-element-types)
                               ;; For disjunctions, we need to reference the implementer array elements
                               array-element-rule (cond
                                                   (and has-array-element-rule (some #(= (:name %) inner-type) disjunctions))
                                                   (let [disjunction (first (filter #(= (:name %) inner-type) disjunctions))
                                                         implementers (:implementers disjunction)]
                                                     (str "(" (str/join " | " (map #(str % "ArrayElement") implementers)) ")"))
                                                   has-array-element-rule
                                                   (str inner-type "ArrayElement")
                                                   :else nil)]
                           (if array-element-rule
                             (str array-construction-rule " | (<WS>+ (" (str/join " | " [array-element-rule element-rule]) "))*")
                             (str array-construction-rule " | (<WS>+ " element-rule ")*")))
                         (str/starts-with? element-type "Map<") 
                         (let [key-type (:key-type element)
                               value-type (:value-type element)
                               map-rule-name (str key-type "To" (clojure.string/capitalize value-type) "MapConstruction")]
                           (str "(MapConstruction | " map-rule-name ")"))
                         (some #(= (:name %) element-type) enums) (str element-type "Value")
                         (some #(= (:name %) element-type) (map :name disjunctions))
                         (let [disjunction (first (filter #(= (:name %) element-type) disjunctions))
                               implementers (:implementers disjunction)
                               has-empty-type (:has-empty-type disjunction)]
                           (if has-empty-type
                             (str "(" (str/join " | " (map #(str % "Construction") implementers)) " | LocalEmpty)")
                             (str "(" (str/join " | " (map #(str % "Construction") implementers)) ")")))
                         :else (str element-type "Construction"))]
      (str "(" expected-rule " | LocalEmpty | VariableReference)"))))

(defn generate-class-grammar [class enums disjunctions classes]
  "Generate strict (RTypeConstruction) and relaxed (TypeConstruction) grammar strings for a single class"
  (let [class-name (:name class)
        elements (:elements class)
        arg-rules (generate-strict-arg-rules elements enums disjunctions classes)
        relaxed-args (generate-relaxed-arg-rules elements enums disjunctions classes)
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
                      (primitive-type->literal-rule inner-type) (primitive-type->literal-rule inner-type)
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
                  (primitive-type->literal-rule key-type) (primitive-type->literal-rule key-type)
                  (some #(= (:name %) key-type) enums) (str key-type "Value")
                  :else (str key-type "Construction"))
        value-rule (cond
                    (primitive-type->literal-rule value-type) (primitive-type->literal-rule value-type)
                    (str/starts-with? value-type "Array<") 
                    (let [inner-type (str/replace (str/replace value-type "Array<" "") ">" "")
                          inner-rule (element-type-to-rule inner-type enums)]
                      (str inner-rule))
                    (str/starts-with? value-type "Map<") "MapConstruction"
                    (some #(= (:name %) value-type) enums) (str value-type "Value")
                    :else (str value-type "Construction"))
        rule-name (str (clojure.string/capitalize field-name) "MapConstruction")]
    (str rule-name " = <'{'> (<WS>? " key-rule " <WS>? <':'> <WS>? " value-rule ")* <WS>? <'}'>")))

(defn generate-array-element-grammar [class enums disjunctions classes]
  "Generate array-element grammar strings for a single class (with optional whitespace between arguments)"
  (let [class-name (:name class)
        elements (:elements class)
        arg-rules (generate-relaxed-arg-rules elements enums disjunctions classes)
        array-element-rule (if (empty? elements)
                             (str class-name "ArrayElement = <'['> (<':'> <'" class-name "'>)? <WS>? <']'>" )
                             (str class-name "ArrayElement = <'['> (<':'> <'" class-name "'>)? <WS>? "
                                  (str/join " <WS>? " arg-rules) " <WS>? <']'>"))]
    array-element-rule))

(defn generate-class-grammars [classes enums disjunctions]
  "Generate grammar rules for all classes"
  (let [sorted-classes (sort-by #(if (= (:type %) :empty) 1 0) classes)
        class-grammars (mapcat #(let [[strict-rule relaxed-rule] (generate-class-grammar % enums disjunctions classes)]
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

(defn generate-type-based-map-rules [classes enums disjunctions]
  "Generate type-based map construction rules (e.g., StringToIntMapConstruction)"
  (let [map-types (->> (for [class classes
                             element (:elements class)
                             :when (str/starts-with? (:type element) "Map<")]
                         (:type element))
                       distinct)
        type-based-rules (for [map-type map-types]
                          (let [type-parts (str/split (str/replace (str/replace map-type "Map<" "") ">" "") #",")
                                key-type (str/trim (first type-parts))
                                value-type (str/trim (second type-parts))
                                rule-name (str key-type "To" (clojure.string/capitalize value-type) "MapConstruction")
                                key-rule (cond
                                         (primitive-type->literal-rule key-type) (primitive-type->literal-rule key-type)
                                         (some #(= (:name %) key-type) enums) (str key-type "Value")
                                         :else (str key-type "Construction"))
                                value-rule (cond
                                           (primitive-type->literal-rule value-type) (primitive-type->literal-rule value-type)
                                           (str/starts-with? value-type "Array<") "ArrayConstruction"
                                           (str/starts-with? value-type "Map<") "MapConstruction"
                                           (some #(= (:name %) value-type) enums) (str value-type "Value")
                                           :else (str value-type "Construction"))]
                            (str rule-name " = <'{'> (<WS>? " key-rule " <WS>? <':'> <WS>? " value-rule ")* <WS>? <'}'>")))]
    type-based-rules))

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
        ;; Check both classes and disjunctions for array element types
        array-element-rules (concat
                             ;; Generate for classes that are used as array elements
                             (for [class classes
                                   :when (some #(= % (str (:name class))) array-element-types)]
                               (let [rule (generate-array-element-grammar class enums disjunctions classes)]
                                 rule))
                             ;; For disjunctions used as array elements, generate array element rules for all implementers
                             (apply concat
                                    (for [disjunction disjunctions
                                          :when (some #(= % (str (:name disjunction))) array-element-types)]
                                      (for [implementer (:implementers disjunction)]
                                        (let [implementer-class (first (filter #(= (:name %) implementer) classes))]
                                          (when implementer-class
                                            (generate-array-element-grammar implementer-class enums disjunctions classes)))))))
                ;; Generate type-specific array construction rules for each array type
        array-construction-rules (for [array-type array-element-types]
                                   (let [;; For disjunctions, we need to include all implementer array elements
                                         array-element-types-for-this-type (cond
                                                                             ;; Check if it's a disjunction
                                                                             (some #(= (:name %) array-type) disjunctions)
                                                                             (let [disjunction (first (filter #(= (:name %) array-type) disjunctions))
                                                                                   implementers (:implementers disjunction)]
                                                                                 (map #(str % "ArrayElement") implementers))
                                                                             ;; For concrete types, just include that type's array element
                                                                             :else [(str array-type "ArrayElement")])
                                         rule-name (str array-type "ArrayConstruction")
                                         rule-body (str rule-name " = <'['> <WS>? <':'> <'Array'> <'/'> <'" array-type "'> (<WS>? ("
                                                        (str/join " | " array-element-types-for-this-type)
                                                        "))* <WS>? <']'>")]
                                     [rule-name rule-body]))
        ;; Convert to map for easy lookup
        array-construction-rule-map (into {} array-construction-rules)
        ;; For backward compatibility, also include a generic ArrayConstruction rule that's not used
        generic-array-construction-rule "ArrayConstruction = <'['> <WS>? <':'> <'Array'> <'/'> TypeName <WS>? <']'>"
        ]
    {:array-element-rules array-element-rules
     :array-construction-rules (vals array-construction-rule-map)
     :array-construction-rule-map array-construction-rule-map
     :generic-array-construction-rule generic-array-construction-rule}))

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
                                            (primitive-type->literal-rule key-type) (primitive-type->literal-rule key-type)
                                            (some #(= (:name %) key-type) enums) (str key-type "Value")
                                            :else (str key-type "Construction"))
                                  value-rule (cond
                                              (primitive-type->literal-rule value-type) (primitive-type->literal-rule value-type)
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

(defn generate-single-statement-rules [classes]
  "Generate grammar rules for single statements (assignment OR construction)"
  (let [all-construction-types (concat 
                                (map #(str (:name %) "Construction") classes)
                                ;; Include type-specific array construction rules
                                (for [class classes
                                      element (:elements class)
                                      :when (str/starts-with? (:type element) "Array<")]
                                  (let [inner-type (str/replace (str/replace (:type element) "Array<" "") ">" "")]
                                    (str inner-type "ArrayConstruction")))
                                ["MapConstruction"]  ;; Include the generic map construction
                                (map #(str (clojure.string/capitalize (:name %)) "MapConstruction")
                                     (for [class classes
                                           element (:elements class)
                                           :when (str/starts-with? (:type element) "Map<")]
                                       element)))
        statement-rule (str "Statement = VariableAssignment | (" (str/join " | " all-construction-types) ")")
        variable-assignment-rule (str "VariableAssignment = <'$'> VariableName <WS>? <'='> <WS>? (StringLiteral | IntLiteral | FloatLiteral | BoolLiteral | VariableReference | (" (str/join " | " all-construction-types) "))")
        single-statement-rules [statement-rule
                               variable-assignment-rule
                               "VariableReference = <'$'> #'[a-zA-Z_][a-zA-Z0-9_]*'"
                               "VariableName = #'[a-zA-Z_][a-zA-Z0-9_]*'"]]
    single-statement-rules))

(defn generate-common-rules []
  "Generate common grammar rules"
  ["WS = #'[\\s\\n,]+'"
   "IntLiteral = #'\\d+'"
   "FloatLiteral = #'\\d+\\.\\d+'"
   "BoolLiteral = 'true' | 'false'"
   "StringLiteral = '\"' #'[^\"]*' '\"'"
   "LocalEmpty = '_'"
   "TypeName = #'[a-zA-Z][a-zA-Z0-9]*'"
  ])

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
        grammar-parts (concat grammar-parts (:array-element-rules array-grammars) (:array-construction-rules array-grammars))
        
        ;; Add map grammars (generic first, then specific)
        map-grammars (generate-map-grammars classes enums disjunctions)
        grammar-parts (concat grammar-parts (:map-element-rules map-grammars) [(:map-construction-rule map-grammars)])
        
        ;; Add map construction rules for each map field (specific after generic)
        map-field-grammars (generate-map-field-grammars classes enums disjunctions)
        grammar-parts (concat grammar-parts map-field-grammars)
        
        ;; Add type-based map construction rules
        type-based-map-rules (generate-type-based-map-rules classes enums disjunctions)
        grammar-parts (concat grammar-parts type-based-map-rules)
        
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
        
        ;; Add single statement rules
        single-statement-rules (generate-single-statement-rules classes)
        grammar-parts (concat grammar-parts single-statement-rules)
        
        ;; Add common rules
        common-rules (generate-common-rules)
        grammar-parts (concat grammar-parts common-rules)
        
        ;; Join all parts - Statement will be the start symbol
        composed-grammar (str/join "\n" grammar-parts)]
    composed-grammar))



(defn schema-to-construction-grammar [schema-ast]
  "Implementation of schema-to-construction-grammar without exception handling"
  (let [class-info (extract-class-info schema-ast)
        grammar-string (generate-construction-grammar class-info)]
    (P/success-cargo
     {:grammar grammar-string
      :class-info class-info})))





(defn split-statements [input-string]
  "Split input string into statements. A statement ends at any full stop not in a string and not between two digits.
   If there are no full stops, treat the entire string as a single statement."
  (let [trimmed-input (str/trim input-string)]
    (if (str/blank? trimmed-input)
      []
      (let [chars (vec trimmed-input)
            len (count chars)]
        (loop [i 0
               current-statement []
               statements []
               in-string false
               escape-next false
               found-full-stop false]
          (if (>= i len)
            ;; End of input - add final statement if not empty
            (let [final-statements (if (seq current-statement)
                                    (conj statements (str/trim (apply str current-statement)))
                                    statements)]
              (filter seq (map str/trim final-statements)))
            (let [char (nth chars i)]
              (cond
                ;; Handle escape sequences
                escape-next
                (recur (inc i) (conj current-statement char) statements in-string false found-full-stop)
                
                ;; Handle string boundaries
                (= char \")
                (recur (inc i) (conj current-statement char) statements (not in-string) false found-full-stop)
                
                ;; Handle full-stop (statement separator) - only if not in a string and not between two digits
                (and (= char \.) (not in-string))
                (let [prev-char (when (> i 0) (nth chars (dec i)))
                      next-char (when (< (inc i) len) (nth chars (inc i)))
                      is-between-digits (and prev-char next-char (Character/isDigit prev-char) (Character/isDigit next-char))]
                  (if is-between-digits
                    ;; This is a decimal point in a float, not a statement separator
                    (recur (inc i) (conj current-statement char) statements in-string false found-full-stop)
                    ;; This is a statement separator
                    (let [statement (str/trim (apply str current-statement))
                          ;; skip whitespace after the dot
                          next-i (loop [j (inc i)]
                                   (if (and (< j len) (Character/isWhitespace (nth chars j)))
                                     (recur (inc j))
                                     j))]
                      (recur next-i [] (if (seq statement) (conj statements statement) statements) false false true))))
                
                ;; All other characters
                :else
                (recur (inc i) (conj current-statement char) statements in-string (= char \\) found-full-stop)))))))))





(defn parse-construction-pure [{:keys [schema-ast construction]}]
  "Pure transformation: parse construction using split-statements approach.
  Input: {:schema-ast schema-ast :construction construction-string}
  Output: parsed construction AST with assignments and final construction"
  (let [class-info (extract-class-info schema-ast)
        grammar-string (generate-construction-grammar class-info)
        statement-parser (insta/parser grammar-string :start :Statement)
        statements (split-statements construction)]
    (P/run statements
      ;; Validate we have at least one statement
      (P/validator #(not (empty? %)) "No statements found in construction")
      
      ;; Parse each statement individually
      (P/processor 
        (fn [statements]
          (map-indexed 
            (fn [idx statement]
              (let [parse-result (statement-parser statement)]
                (if (insta/failure? parse-result)
                  (P/fail-cargo (str "Failed to parse statement " (inc idx) ": " (insta/get-failure parse-result)))
                  parse-result)))
            statements))
        "Parse each statement")
      
      ;; Extract assignments and final construction
      (P/processor 
        (fn [successful-parses]
          (let [;; Separate assignments from final construction based on content, not position
                assignment-statements (filter (fn [ast]
                                               (and (vector? ast) 
                                                    (= (first ast) :Statement)
                                                    (vector? (second ast))
                                                    (= (first (second ast)) :VariableAssignment)))
                                             successful-parses)
                final-statements (filter (fn [ast]
                                          (and (vector? ast) 
                                               (= (first ast) :Statement)
                                               (vector? (second ast))
                                               (not= (first (second ast)) :VariableAssignment)))
                                        successful-parses)
                ;; Extract the actual construction from the final statement
                final-construction (if (seq final-statements)
                                    (let [final-statement (last final-statements)]
                                      (if (and (vector? final-statement) (= (first final-statement) :Statement))
                                        (second final-statement) ; Get the construction node from [:Statement construction]
                                        final-statement))
                                    nil)
                ;; Extract assignments from assignment statements
                assignments (map (fn [ast]
                                  (if (and (vector? ast) (= (first ast) :Statement))
                                    (let [statement-content (second ast)]
                                      (if (and (vector? statement-content) (= (first statement-content) :VariableAssignment))
                                        (let [[_ & children] statement-content
                                              var-name-node (first (filter #(= (first %) :VariableName) children))
                                              value-node (first (filter #(not= (first %) :VariableName) children))]
                                          (when (and var-name-node value-node)
                                            {:name (second var-name-node)
                                             :construction value-node}))
                                        nil))
                                    nil))
                                assignment-statements)
                valid-assignments (filter some? assignments)]
            (if (nil? final-construction)
              (P/fail-cargo "Construction must include a final construction statement.")
              {:type :MultiStepConstruction
               :assignments valid-assignments
               :final-construction final-construction})))
        "Extract assignments and final construction")
        (P/show "The final construction")
        )    
        ))


