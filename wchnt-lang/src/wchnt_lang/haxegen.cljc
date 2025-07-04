(ns wchnt-lang.haxegen
  (:require [clojure.string :as str]
            [instaparse.core :as insta]
            [wchnt-lang.parser :as parser]
            [wchnt-lang.schema :as schema]))

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