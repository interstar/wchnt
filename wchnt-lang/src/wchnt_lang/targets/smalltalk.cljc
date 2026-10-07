(ns wchnt-lang.targets.smalltalk
  "Pharo 14 source emitter for the first WCHNT Smalltalk vertical slice."
  (:require [clojure.string :as str]
            [wchnt-lang.targets.core :as core]))

(def ^:private package-name "WCHNT-Generated")
(def ^:private keyword-argument-labels
  {"drawRect" ["x" "y" "width" "height"]
   "drawCircle" ["x" "y" "radius"]
   "drawEllipse" ["x" "y" "radiusX" "radiusY"]
   "drawLine" ["x1" "y1" "x2" "y2"]
   "fillText" ["text" "x" "y"]
   "lineStyle" ["thickness" "color" "alpha"]})

(defn parse-target
  [text]
  (let [target-ir (core/parse-target text)]
    (when (or (:init target-ir) (:step target-ir) (seq (:bindings target-ir)))
      (throw (ex-info "%smalltalk v1 accepts a native Smalltalk %main block only"
                      {:host "smalltalk"})))
    target-ir))

(defn- st-string
  [value]
  (str "'" (str/replace (str value) "'" "''") "'"))

(defn- variable-name
  [name]
  (str "a" (str/upper-case (subs name 0 1)) (subs name 1)))

(defn- class-declaration
  [{:keys [name components superclass]}]
  (str (or superclass "Object") " subclass: #" name "\n"
       "    instanceVariableNames: '"
       (str/join " " (map :component-name components)) "'\n"
       "    classVariableNames: ''\n"
       "    package: '" package-name "'!\n"
       name " class\n"
       "\tinstanceVariableNames: ''!"))

(defn- accessor-chunks
  [{:keys [name components]}]
  (mapcat (fn [{:keys [component-name]}]
            [(str component-name "\n    ^ " component-name)
             (str component-name ": " (variable-name component-name)
                  "\n    " component-name " := " (variable-name component-name)
                  ".\n    ^ self")])
          components))

(defn- method-fileout-chunk
  [class-name method-source]
  (str "!" class-name " methodsFor: 'WCHNT' stamp: 'WCHNT' prior: 0!\n"
       (str/replace method-source "!" "!!")
       "! !"))

(defn- class-ref
  [class-name schema-ir]
  (or (some #(when (= class-name (:name %)) %) (:assemblages schema-ir))
      (throw (ex-info (str "Smalltalk backend cannot find schema class '"
                           class-name "'")
                      {:class-name class-name}))))

(declare expression)

(defn- st-selector
  [method args]
  (let [labels (get keyword-argument-labels method)]
    (cond
      (and labels (= (count labels) (count args)))
      (str method ": " (expression (first args))
           (apply str (map (fn [label arg]
                             (str " " label ": " (expression arg)))
                           (rest labels) (rest args))))
      (> (count args) 1)
      (throw (ex-info "Smalltalk backend has no keyword mapping for this multi-argument call"
                      {:method method :argument-count (count args)}))
      (seq args) (str method ": " (expression (first args)))
      :else method)))

(defn- call-expression
  [{:keys [receiver method args on]}]
  (let [receiver-code (expression receiver)
        args-code (map expression args)]
    (cond
      (= method "length") (str "(" receiver-code " size)")
      (and (= on "Array") (= method "get"))
      (str "(" receiver-code " at: (" (first args-code) " + 1))")
      (= method "concat") (str "(" receiver-code " , " (first args-code) ")")
      (= method "str") (str "(" receiver-code " asString)")
      (= method "tpl") (str "((WCHNTRuntime new) template: " receiver-code
                            " using: " (first args-code) ")")
      :else (str "(" receiver-code " " (st-selector method args) ")"))))

(defn- map-expression
  [{:keys [pairs]}]
  (if (empty? pairs)
    "Dictionary new"
    (str "(Dictionary newFrom: {"
         (str/join ". " (map (fn [{:keys [key value]}]
                               (str (expression key) " -> " (expression value)))
                             pairs))
         "})")))

(defn- construct-expression
  [{:keys [class-name args]} schema-ir]
  (let [components (:components (class-ref class-name schema-ir))]
    (str "(" class-name " new"
         (apply str (map (fn [{:keys [component-name]} arg]
                           (str " " component-name ": " (expression arg) ";"))
                         components args))
         " yourself)")))

(defn- branch-expression
  [{:keys [lets body]} schema-ir]
  (let [bindings (map (fn [{:keys [name value]}]
                        (str name " := " (expression value) ".")) lets)]
    (str "[" (when (seq lets)
                (str " | " (str/join " " (map :name lets)) " | "))
         (when (seq bindings) (str (str/join " " bindings) " "))
         (expression body schema-ir) "]")))

(defn expression
  ([expr] (expression expr nil))
  ([expr schema-ir]
   (case (:expr expr)
     :int (str (:value expr))
     :float (str (:value expr))
     :bool (if (:value expr) "true" "false")
     :string (st-string (:value expr))
     :field (str "self " (:name expr))
     :param (:name expr)
     :local (:name expr)
     :this "self"
     :path (reduce (fn [root field] (str "(" root " " field ")"))
                   (expression (:root expr) schema-ir)
                   (:fields expr))
     :call (call-expression expr)
     :construct (construct-expression expr schema-ir)
     :map (map-expression expr)
     :arith (str "(" (str/join " " (map #(if (string? %) %
                                                (expression % schema-ir))
                                          (:parts expr))) ")")
     :neg (str "(" (expression (:arg expr) schema-ir) " negated)")
     :cmp (let [operator (case (:op expr) "==" "=" "!=" "~=" (:op expr))]
            (str "(" (expression (:left expr) schema-ir) " " operator " "
                 (expression (:right expr) schema-ir) ")"))
     :if (str "((" (expression (:condition expr) schema-ir)
              ") ifTrue: " (branch-expression (:then expr) schema-ir)
              " ifFalse: " (branch-expression (:else expr) schema-ir) ")")
     :and (str "(" (str/join " and: [" (map #(expression % schema-ir) (:args expr)))
              (apply str (repeat (dec (count (:args expr))) "]")) ")")
     :or (str "(" (str/join " or: [" (map #(expression % schema-ir) (:args expr)))
             (apply str (repeat (dec (count (:args expr))) "]")) ")")
     (throw (ex-info "Unsupported expression in Smalltalk backend"
                     {:expression expr})))))

(defn- method-selector
  [{:keys [method-name parameters]}]
  (cond
    (> (count parameters) 1)
    (throw (ex-info "Smalltalk backend currently supports methods with at most one parameter"
                    {:method method-name :parameter-count (count parameters)}))
    (seq parameters) (str method-name ": " (:name (first parameters)))
    :else method-name))

(defn- method-chunk
  [method schema-ir]
  (let [{:keys [parameters lets body]} method
        temporaries (map :name lets)
        let-lines (map (fn [{:keys [name value]}]
                         (str name " := " (expression value schema-ir) "."))
                       lets)]
    (str (method-selector method)
         (when (seq temporaries)
           (str "\n    | " (str/join " " temporaries) " |"))
         (when (seq let-lines)
           (str "\n    " (str/join "\n    " let-lines)))
         "\n    ^ " (expression body schema-ir))))

(defn- construction-value
  [arg construction-ir parameters]
  (case (:type arg)
    :primitive (if (= "String" (:class-name arg))
                 (st-string (:value arg))
                 (str (:value arg)))
    :variable (or (get (:variable-mappings construction-ir) (:value arg))
                  (when (contains? (:objects construction-ir) (:value arg))
                    (:value arg))
                  (get parameters (:value arg))
                  (throw (ex-info "Unknown variable in Smalltalk construction"
                                  {:argument arg})))
    (throw (ex-info "Unsupported construction value in Smalltalk backend"
                    {:argument arg}))))

(defn- object-allocation
  [[object-id object]]
  (case (:type object)
    :object (str object-id " := " (:class-name object) " new.")
    :array (str object-id " := Array new: " (count (:args object)) ".")
    (throw (ex-info "Unsupported construction object in Smalltalk backend"
                    {:object-id object-id :object object}))))

(defn- object-wiring
  [[object-id {:keys [type class-name args]}] schema-ir construction-ir parameters]
  (case type
    :object (map (fn [{:keys [component-name]} arg]
                   (str object-id " " component-name ": "
                        (construction-value arg construction-ir parameters) "."))
                 (:components (class-ref class-name schema-ir)) args)
    :array (map-indexed (fn [index arg]
                          (str object-id " at: " (inc index) " put: "
                               (construction-value arg construction-ir parameters) "."))
                        args)
    []))

(declare factory-selector factory-declaration)

(defn- construction-factory
  [construction-ir schema-ir]
  (let [parameter-map (into {} (map (juxt :name :name)
                                    (:factory-params construction-ir)))
        objects (sort-by (comp :index val) (:objects construction-ir))
        object-names (map first objects)
        allocations (map object-allocation objects)
        wiring (mapcat #(object-wiring % schema-ir construction-ir parameter-map)
                       objects)]
    (str (factory-declaration construction-ir)
         "\n    | " (str/join " " object-names) " |\n    "
         (str/join "\n    " (concat allocations wiring
                              [(str "^ " (:return-object construction-ir))])))))

(defn- graphics-state-methods
  []
  [(str "initialize\n"
        "    super initialize.\n"
        "    game := nil.\n"
        "    fillColor := Color black.\n"
        "    shapes := OrderedCollection new.\n"
        "    self extent: 800@400.\n"
        "    self color: Color black")
   (str "game: aGame\n"
        "    game := aGame.\n"
        "    self clear.\n"
        "    game draw: self.\n"
        "    self startStepping.\n"
        "    ^ self")
   "stepTime\n    ^ 16"
   (str "step\n"
        "    game := game step.\n"
        "    self clear.\n"
        "    game draw: self")])

(defn- graphics-drawing-methods
  []
  [(str "clear\n"
        "    shapes removeAll.\n"
        "    self changed.\n"
        "    ^ self")
   (str "beginFill: aColor\n"
        "    fillColor := self colorFromInteger: aColor.\n"
        "    ^ self")
   "endFill\n    ^ self"
   (str "drawRect: x y: y width: width height: height\n"
        "    shapes add: {#rectangle. (x@y extent: width@height). fillColor}.\n"
        "    self changed.\n"
        "    ^ self")
   (str "drawCircle: x y: y radius: radius\n"
        "    | origin extent |\n"
        "    origin := (x - radius) @ (y - radius).\n"
        "    extent := (radius * 2) @ (radius * 2).\n"
        "    shapes add: {#oval. (origin extent: extent). fillColor}.\n"
        "    self changed.\n"
        "    ^ self")
   (str "drawOn: aCanvas\n"
        "    super drawOn: aCanvas.\n"
        "    shapes do: [ :shape |\n"
        "        (shape first = #rectangle)\n"
        "            ifTrue: [ aCanvas fillRectangle: (shape at: 2) color: (shape at: 3) ]\n"
        "            ifFalse: [ aCanvas fillOval: (shape at: 2) color: (shape at: 3) ] ]")])

(defn- graphics-color-methods
  []
  [(str "colorFromInteger: anInteger\n"
        "    ^ Color\n"
        "        r: (((anInteger bitShift: -16) bitAnd: 255) / 255.0)\n"
        "        g: (((anInteger bitShift: -8) bitAnd: 255) / 255.0)\n"
        "        b: ((anInteger bitAnd: 255) / 255.0)")])

(defn- runtime-methods
  []
  {"WCHNTConsole"
   ["println: anObject\n    Transcript show: anObject asString; cr.\n    ^ self"
    "nextLine\n    ^ UIManager default request: 'I-Spy guess' initialAnswer: ''"]
   "WCHNTMaths"
   [(str "randInt: anInteger\n    anInteger > 0 ifFalse: [ self error: 'randInt requires a positive bound' ].\n"
         "    ^ anInteger atRandom - 1")]
   "WCHNTRuntime"
   [(str "template: aString using: aDictionary\n"
         "    | result |\n    result := aString.\n"
         "    aDictionary keysAndValuesDo: [ :key :value |\n"
         "        result := result copyReplaceAll: ('{' , key , '}') with: value asString ].\n"
         "    ^ result")]
   "WCHNTGraphics"
   (concat (graphics-state-methods)
           (graphics-drawing-methods)
           (graphics-color-methods))})

(defn- runtime-classes
  []
  [{:name "WCHNTConsole" :components []}
   {:name "WCHNTMaths" :components []}
   {:name "WCHNTRuntime" :components []}
   {:name "WCHNTGraphics"
    :superclass "Morph"
    :components (mapv (fn [name] {:component-name name})
                      ["game" "fillColor" "shapes"])}])

(defn- factory-selector
  [construction-ir]
  (str "factory"
       (apply str (map (fn [{:keys [name]}]
                         (str "With" (str/capitalize name) ":"))
                       (:factory-params construction-ir)))))

(defn- factory-declaration
  [construction-ir]
  (str (factory-selector construction-ir)
       (apply str (map (fn [{:keys [name]}] (str " " name))
                       (:factory-params construction-ir)))))

(defn- main-chunk
  [target-ir]
  (when-let [main (get-in target-ir [:main :source])]
    (str "main\n    " (str/replace main #"\n" "\n    ") "\n    ^ self")))

(defn- pharo-source
  [cargo]
  (let [schema-ir (get-in cargo [:stash :schema-ir])
        methods (get-in cargo [:stash :methods-ir] [])
        construction-ir (get-in cargo [:stash :construction-ir])
        target-ir (get-in cargo [:stash :target-ir])
        assemblages (:assemblages schema-ir)
        assemblage-class (when construction-ir
                           {:name (str (:root-class construction-ir) "Assemblage")
                            :components []})
        declared-classes (concat assemblages (when assemblage-class [assemblage-class])
                                 (runtime-classes))
        class-definitions (map class-declaration declared-classes)
        model-methods (mapcat (fn [class]
                                (map (fn [source] [(:name class) source])
                                     (concat (accessor-chunks class)
                                             (map #(method-chunk % schema-ir)
                                                  (filter (fn [m] (= (:name class) (:class m)))
                                                          methods)))))
                              assemblages)
        assemblage-methods (when assemblage-class
                             (concat
                              [(construction-factory construction-ir schema-ir)]
                              (when-let [main (main-chunk target-ir)] [main])))
        stdlib-methods (mapcat (fn [[class-name methods]]
                                 (map #(vector class-name %) methods))
                               (runtime-methods))
        all-methods (concat
                     model-methods
                     (when assemblage-class
                       (map #(vector (:name assemblage-class) %) assemblage-methods))
                     stdlib-methods)
        method-chunks (map (fn [[class-name source]]
                             (method-fileout-chunk class-name source))
                           all-methods)
        source (str "\"VERSION:1.0\"!\n"
                    (str/join "\n" class-definitions)
                    "\n\n"
                    (str/join "\n\n" method-chunks))]
    source))

(defn emit-program
  "Emit Pharo source for schema classes, Methods, Construction, and native
   Target main code. Unimplemented IR fails fast during emission."
  [cargo]
  (let [schema-ir (get-in cargo [:stash :schema-ir])
        codeblocks (get-in cargo [:stash :codeblocks])
        target-ir (get-in cargo [:stash :target-ir])
        construction-ir (get-in cargo [:stash :construction-ir])
        _ (when (and (get-in target-ir [:main :source]) (nil? construction-ir))
            (throw (ex-info "%smalltalk %main requires Construction"
                            {:host "smalltalk"})))
        source (pharo-source cargo)
        unsupported (nil? construction-ir)]
    {:backend :smalltalk
     :target "smalltalk"
     :page-kind (or (:page-kind codeblocks) :program)
     :outputs [{:kind :source :name "WCHNTGenerated.st" :content source}]
     :payload {:dialect :pharo
               :dialect-version "14.0.0"
               :package package-name
               :source source}
     :metadata {:class-names (mapv :name (:assemblages schema-ir))}
     :warnings (if unsupported
                 ["Smalltalk backend currently needs Construction to emit a runnable program."]
                 [])}))

(def plugin
  {:name "smalltalk"
   :backend :smalltalk
   :std :smalltalk
   :standard {:types #{"WCHNTMaths" "WCHNTGraphics"}}
   :parse-target parse-target
   :emit emit-program})
