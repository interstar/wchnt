(ns wchnt-lang.parser
  (:require [instaparse.core :as insta]
            [clojure.core :refer [bean]]
            [clojure.string :as str]
            [wchnt-lang.schema :as schema])
  (:import (java.lang Exception)))

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
     :construction (str/trim construction-block)}))

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

(defn generate-class-grammar [class enums disjunctions]
  "Generate a grammar string for a single class"
  (let [class-name (:name class)
        elements (:elements class)
        disjunction-names (set (map :name disjunctions))
        disjunction-map (into {} (map (juxt :name identity) disjunctions))
        arg-rules (for [element elements]
                   (let [element-type (:type element)]
                     (cond
                       (= element-type "String") "StringLiteral"
                       (= element-type "int") "IntLiteral"
                       (str/starts-with? element-type "Array<") 
                       (str (clojure.string/capitalize (:name element)) "ArrayConstruction")
                       (str/starts-with? element-type "Map<") 
                       (str (clojure.string/capitalize (:name element)) "MapConstruction")
                       (some #(= (:name %) element-type) enums) (str element-type "Value")
                       (disjunction-names element-type)
                       (let [disjunction (get disjunction-map element-type)
                             implementers (:implementers disjunction)
                             has-empty-type (:has-empty-type disjunction)]
                         (if has-empty-type
                           (str "(" (str/join " | " (map #(str % "Construction") implementers)) " | LocalEmpty)")
                           (str "(" (str/join " | " (map #(str % "Construction") implementers)) ")")))
                       :else (str element-type "Construction"))))]
    (if (empty? elements)
      (str class-name "Construction = '[' ':' '" class-name "' <WS>? ']'")
      (str class-name "Construction = '[' ':' '" class-name "' <WS>? " 
           (str/join " <WS>+ " arg-rules) " <WS>? ']'"))))

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
    (str rule-name " = '[' <WS>? ':' 'Array' '/' '" field-name "' (<WS>+ " element-rule ")* <WS>? ']'")))

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
    (str rule-name " = '{' (<WS>? " key-rule " <WS>? ':' <WS>? " value-rule ")* <WS>? '}'")))

(defn generate-construction-grammar [class-info]
  "Generate a single composed grammar string for all classes and enums"
  (let [classes (:classes class-info)
        enums (:enums class-info)
        disjunctions (:disjunctions class-info)
        _ (println "DEBUG: classes=" classes)
        _ (println "DEBUG: enums=" enums)
        _ (println "DEBUG: disjunctions=" disjunctions)
        
        ;; Find the first non-empty class as the root (prioritize composition classes over empty classes)
        root-class (when (seq classes) 
                     (let [non-empty-classes (filter #(not= (:type %) :empty) classes)]
                       (if (seq non-empty-classes)
                         (str (:name (first non-empty-classes)) "Construction")
                         (str (:name (first classes)) "Construction"))))
        
        ;; Start with multi-step construction as the root
        grammar-parts ["MultiStepConstruction = (<NL>? Statement <NL>)*"
                      "Statement = VariableAssignment | Construction"
                      "VariableAssignment = VariableName <WS>? '=' <WS>? Construction"
                      "VariableReference = VariableName"
                      "VariableName = #'[a-zA-Z_][a-zA-Z0-9_]*'"
                      "Construction = ArrayConstruction | MapConstruction | ClassConstruction | VariableReference"]
        
        ;; Add class grammars (prioritize non-empty classes first)
        sorted-classes (sort-by #(if (= (:type %) :empty) 1 0) classes)
        class-grammars (map #(do (let [g (generate-class-grammar % enums disjunctions)] (println "DEBUG: class grammar for" (:name %) "=" g) g)) sorted-classes)
        grammar-parts (concat grammar-parts class-grammars)
        
        ;; Add disjunction/interface rules (sum types)
        disjunction-rules (for [disjunction disjunctions]
                            (let [interface-name (:name disjunction)
                                  implementers (:implementers disjunction)
                                  rule (str interface-name "Construction = "
                                            (str/join " | " (map #(str % "Construction") implementers)))]
                              (println "DEBUG: disjunction/interface rule for" interface-name "=" rule)
                              rule))
        grammar-parts (concat grammar-parts disjunction-rules)
        
        ;; Add array construction rules for each array field
        array-rules (for [class classes
                         element (:elements class)
                         :when (str/starts-with? (:type element) "Array<")]
                     (let [g (generate-array-grammar element classes enums disjunctions)]
                       (println "DEBUG: array grammar for" (:name element) "=" g)
                       g))
        grammar-parts (concat grammar-parts array-rules)
        
        ;; Add map construction rules for each map field
        map-rules (for [class classes
                       element (:elements class)
                       :when (str/starts-with? (:type element) "Map<")]
                   (let [g (generate-map-grammar element classes enums disjunctions)]
                     (println "DEBUG: map grammar for" (:name element) "=" g)
                     g))
        grammar-parts (concat grammar-parts map-rules)
        
        ;; Add generic construction rules
        generic-rules ["ArrayConstruction = '[' <WS>? ':' 'Array' <WS>? Construction* <WS>? ']'"
                      "MapConstruction = '{' (<WS>? Construction <WS>? ':' <WS>? Construction)* <WS>? '}'"
                      "ClassConstruction = '[' <WS>? ':' VariableName <WS>? Construction* <WS>? ']'"]
        grammar-parts (concat grammar-parts generic-rules)
        
        ;; Add enum value rules
        enum-rules (map #(do (let [g (generate-enum-grammar %)] (println "DEBUG: enum grammar for" (:name %) "=" g) g)) enums)
        grammar-parts (concat grammar-parts enum-rules)
        
        ;; Add disjunction grammars for empty classes
        disjunction-grammars (for [disjunction disjunctions
                                  :when (:has-empty-type disjunction)]
                              (let [interface-name (:name disjunction)
                                    empty-class-name (str "_" interface-name)
                                    g (str empty-class-name "Construction = '[' ':' '" empty-class-name "' <WS>? ']'")]
                                (println "DEBUG: disjunction grammar for" interface-name "(empty) =" g)
                                g))
        grammar-parts (concat grammar-parts disjunction-grammars)
        
        ;; Add common rules
        common-rules ["WS = #'[\\s,]+'"
                     "NL = #'\\n+'"
                     "IntLiteral = #'\\d+'"
                     "StringLiteral = '\"' #'[^\"]*' '\"'"
                     "LocalEmpty = '_'"]
        grammar-parts (concat grammar-parts common-rules)
        
        ;; Join all parts - MultiStepConstruction will be the start symbol
        composed-grammar (str/join "\n" grammar-parts)
        _ (println "DEBUG: FINAL GRAMMAR\n" composed-grammar)]
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
                  (println "DEBUG: Resolving variable reference:" node)
                  (get variable-assignments node))
                node)
              (vector? node)
              (let [[tag & children] node]
                (vec (cons tag (map walk-and-resolve children))))
              (seq? node) 
              (map walk-and-resolve node)
              :else node))]
    (walk-and-resolve ast)))

(defn join-multi-line-assignments [lines]
  "Join multi-line variable assignments into single lines"
  (loop [remaining lines
         result []
         current-assignment nil]
    (if (empty? remaining)
      (if current-assignment
        (conj result (:text current-assignment))
        result)
      (let [line (first remaining)
            trimmed (str/trim line)]
        (if (and (nil? current-assignment) (re-find #"^[a-zA-Z_][a-zA-Z0-9_]*\s*=\s*$" trimmed))
          ;; Start of assignment with no content on same line
          (let [[var-name _] (str/split trimmed #"\s*=\s*" 2)
                var-name (str/trim var-name)]
            (recur (rest remaining) result 
                   {:var-name var-name 
                    :text ""
                    :brackets 0
                    :braces 0}))
          (if (and (nil? current-assignment) (re-find #"^[a-zA-Z_][a-zA-Z0-9_]*\s*=" trimmed))
            ;; Start of assignment with content on same line
            (let [[var-name assignment-start] (str/split trimmed #"\s*=\s*" 2)
                  var-name (str/trim var-name)
                  assignment-start (str/trim (or assignment-start ""))]
              (recur (rest remaining) result 
                     {:var-name var-name 
                      :text (if (str/blank? assignment-start) "" assignment-start)
                      :brackets (count (re-seq #"\[" assignment-start))
                      :braces (count (re-seq #"\{" assignment-start))}))
            (if current-assignment
              ;; Continue assignment
              (let [new-text (str (:text current-assignment) " " trimmed)
                    new-brackets (+ (:brackets current-assignment)
                                   (count (re-seq #"\[" trimmed))
                                   (- (count (re-seq #"\]" trimmed))))
                    new-braces (+ (:braces current-assignment)
                                 (count (re-seq #"\{" trimmed))
                                 (- (count (re-seq #"\}" trimmed))))]
                (if (and (zero? new-brackets) (zero? new-braces))
                  ;; Assignment complete
                  (recur (rest remaining) (conj result new-text) nil)
                  ;; Continue building
                  (recur (rest remaining) result 
                         (assoc current-assignment 
                                :text new-text
                                :brackets new-brackets
                                :braces new-braces))))
              ;; Regular line
              (recur (rest remaining) (conj result trimmed) nil))))))))

(defn parse-variable-assignments [lines construction-parser]
  "Parse variable assignments from lines, returning a map of variable assignments and final construction"
  (let [variable-assignments (atom {})
        final-construction (atom nil)]
    (println "DEBUG: parse-variable-assignments processing lines:")
    (doseq [line lines]
      (println "DEBUG: Processing line:" (pr-str line))
      (let [trimmed-line (str/trim line)]
        (if (re-find #"^[a-zA-Z_][a-zA-Z0-9_]*\s*=" trimmed-line)
          ;; Variable assignment
          (let [[var-name assignment] (str/split trimmed-line #"\s*=\s*" 2)
                var-name (str/trim var-name)
                assignment (str/trim assignment)]
            (println "DEBUG: Found variable assignment:" var-name "=" assignment)
            (let [assignment-parse (construction-parser assignment)]
              (if (insta/failure? assignment-parse)
                (throw (ex-info (str "Failed to parse variable assignment for '" var-name "': " (insta/get-failure assignment-parse))
                               {:variable var-name :assignment assignment}))
                (swap! variable-assignments assoc var-name assignment-parse))))
          ;; Construction
          (if (nil? @final-construction)
            (do
              (println "DEBUG: Found final construction:" trimmed-line)
              (reset! final-construction trimmed-line))
            (throw (ex-info "Multiple constructions found - only one final construction allowed"
                           {:constructions [@final-construction trimmed-line]}))))))
    {:variable-assignments @variable-assignments
     :final-construction @final-construction}))

(defn parse-multi-step-construction [construction-input class-info]
  "Parse multi-step construction with variable assignments and references"
  (let [grammar-string (generate-construction-grammar class-info)
        construction-parser (insta/parser grammar-string)]
    (println "DEBUG: Generated grammar:")
    (println grammar-string)
    (println "DEBUG: Trying to parse:" construction-input)
    (println "DEBUG: Construction input type:" (type construction-input))
    (println "DEBUG: Construction input as string:" (pr-str construction-input))
    
    ;; Parse the entire construction input as a single string
    ;; The grammar already handles whitespace (including newlines) properly
    (let [parse-result (construction-parser construction-input)]
      (if (insta/failure? parse-result)
        (do
          (println "DEBUG: Parse failure details:" (insta/get-failure parse-result))
          (throw (ex-info (str "Failed to parse construction: " (insta/get-failure parse-result))
                         {:construction construction-input :failure parse-result})))
        (do
          (println "DEBUG: Parse result:" parse-result)
          (schema/syntax-success parse-result))))))

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