(ns wchnt-lang.targets.smalltalk
  "Pharo 14 source emitter for the first WCHNT Smalltalk vertical slice."
  (:require [clojure.string :as str]
            [wchnt-lang.targets.core :as core]))

(def ^:private package-name "WCHNT-Generated")
(def ^:private keyword-argument-labels
  {"drawRect" {4 ["x" "y" "width" "height"]}
   "drawCircle" {3 ["x" "y" "radius"]}
   "drawEllipse" {4 ["x" "y" "radiusX" "radiusY"]}
   "drawLine" {4 ["x1" "y" "x2" "y2"]}
   "fillText" {3 ["text" "x" "y"]}
   "lineStyle" {2 ["thickness" "color"]
                 3 ["thickness" "color" "alpha"]}
   "beginFill" {1 ["color"]
                2 ["color" "alpha"]}
   "background" {1 ["color"]
                 2 ["color" "alpha"]}
   "moveTo" {2 ["x" "y"]}
   "lineTo" {2 ["x" "y"]}})

(defn parse-target
  [text]
  (let [target-ir (core/parse-target text)]
    (let [main? (some? (get-in target-ir [:main :source]))
          init? (some? (get-in target-ir [:init :source]))
          step? (some? (get-in target-ir [:step :source]))]
      (when (seq (:bindings target-ir))
        (throw (ex-info "%smalltalk does not yet support Target helper bindings"
                        {:host "smalltalk"})))
      (when (and main? (or init? step?))
        (throw (ex-info "%smalltalk uses either %main or %init/%step"
                        {:host "smalltalk"})))
      (when (not= init? step?)
        (throw (ex-info "%smalltalk requires both native Smalltalk %init and %step"
                        {:host "smalltalk"}))))
    target-ir))

(defn- st-string
  [value]
  (str "'" (str/replace (str value) "'" "''") "'"))

(defn- variable-name
  [name]
  (str "a" (str/upper-case (subs name 0 1)) (subs name 1)))

(defn- st-integer-literal
  [value]
  (let [text (str value)]
    (if-let [[_ sign digits] (re-matches #"(-?)0[xX]([0-9A-Fa-f]+)" text)]
      (str sign "16r" digits)
      text)))

(defn- st-float-literal
  [value]
  (str/replace (str value) "E" "e"))

(defn- context-variable
  [schema-ir class-name]
  (when-let [parent (get-in schema-ir [:context-relationships class-name])]
    (str "the" parent)))

(defn- class-declaration
  [{:keys [name components superclass]} schema-ir]
  (let [context (context-variable schema-ir name)
        variables (cond-> (mapv :component-name components) context (conj context))]
    (str (or superclass "Object") " subclass: #" name "\n"
       "    instanceVariableNames: '"
       (str/join " " variables) "'\n"
       "    classVariableNames: ''\n"
       "    package: '" package-name "'!\n"
       name " class\n"
       "\tinstanceVariableNames: ''!")))

(defn- accessor-chunks
  [{:keys [name components]} schema-ir]
  (concat
   (mapcat (fn [{:keys [component-name]}]
            [(str component-name "\n    ^ " component-name)
             (str component-name ": " (variable-name component-name)
                  "\n    " component-name " := " (variable-name component-name)
                  ".\n    ^ self")])
          components)
   (when-let [context (context-variable schema-ir name)]
     [(str context "\n    ^ " context)
      (str context ": aParent\n    " context " := aParent.\n    ^ self")])))

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

(declare expression lambda-expression)

(defn- smalltalk-method-name
  [name]
  (if (str/ends-with? name "!")
    (str (subs name 0 (dec (count name))) "Mutates")
    name))

(defn- smalltalk-args
  [method args schema-ir]
  (when (seq args)
    (str (smalltalk-method-name method) ": " (expression (first args) schema-ir)
         (apply str (map #(str " with: " (expression % schema-ir)) (rest args))))))

(defn- st-selector
  [method args schema-ir]
  (let [labels (get-in keyword-argument-labels [method (count args)])]
    (cond
      (and labels (= (count labels) (count args)))
      (str method ": " (expression (first args) schema-ir)
           (apply str (map (fn [label arg]
                             (str " " label ": " (expression arg schema-ir)))
                           (rest labels) (rest args))))
      (> (count args) 1) (smalltalk-args method args schema-ir)
      (seq args) (smalltalk-args method args schema-ir)
      :else (smalltalk-method-name method))))

(defn- call-expression
  [{:keys [receiver method args on]} schema-ir]
  (let [receiver-code (expression receiver schema-ir)
        args-code (map #(expression % schema-ir) args)]
    (cond
      (= method "length") (str "(" receiver-code " size)")
      (and (= on "Array") (= method "get"))
      (str "(" receiver-code " at: (" (first args-code) " + 1))")
      (= method "cons")
      (str "((Array with: " (first args-code) ") , " receiver-code ")")
      (and (= on "Array") (= method "head"))
      (str "(" receiver-code " first)")
      (and (= on "Array") (= method "tail"))
      (str "(" receiver-code " allButFirst)")
      (and (= method "get")
           (or (= on "Map") (str/starts-with? (or (:type receiver) "") "Map<")))
      (if (= 2 (count args-code))
        (str "(" receiver-code " at: " (first args-code)
             " ifAbsent: [ " (second args-code) " ])")
        (str "(" receiver-code " at: " (first args-code) ")"))
      (= method "fold")
      (let [[initial lambda] args]
        (str "(" receiver-code " inject: " (expression initial schema-ir)
             " into: " (lambda-expression lambda schema-ir) ")"))
      (= method "times")
      (str "((WCHNTRuntime new) times: " receiver-code
           " applying: " (lambda-expression (first args) schema-ir) ")")
      (= method "concat") (str "(" receiver-code " , " (first args-code) ")")
      (= method "str") (str "(" receiver-code " asString)")
      (and (= on "Float") (= method "toInt"))
      (str "(" receiver-code " truncated)")
      (and (= on "Float") (= method "floor"))
      (str "(" receiver-code " floor)")
      (and (= on "Float") (= method "ceil"))
      (str "(" receiver-code " ceiling)")
      (and (= on "Float") (= method "round"))
      (str "(" receiver-code " rounded)")
      (= method "substring")
      (str "(" receiver-code " copyFrom: (" (first args-code)
           " + 1) to: " (second args-code) ")")
      (= method "tpl") (str "((WCHNTRuntime new) template: " receiver-code
                            " using: " (first args-code) ")")
      :else (str "(" receiver-code " " (st-selector method args schema-ir) ")"))))

(defn- map-expression
  [{:keys [pairs]} schema-ir]
  (if (empty? pairs)
    "Dictionary new"
    (str "(Dictionary newFrom: {"
         (str/join ". " (map (fn [{:keys [key value]}]
                               (str (expression key schema-ir) " -> "
                                    (expression value schema-ir)))
                             pairs))
         "})")))

(defn- construct-expression
  [{:keys [class-name args]} schema-ir]
  (let [components (:components (class-ref class-name schema-ir))
        context-components (for [[{:keys [component-name relationship type-name]} arg]
                                 (map vector components args)
                                 :when (and (= relationship :context-specific)
                                            (= class-name (get-in schema-ir
                                                                  [:context-relationships type-name])))]
                             component-name)
        constructor (str "(" class-name " new"
                        (apply str (map (fn [{:keys [component-name]} arg]
                                          (str " " component-name ": "
                                               (expression arg schema-ir) ";"))
                                        components args))
                        " yourself)")]
    (if (seq context-components)
      (str "([ | wchntConstructed | wchntConstructed := " constructor ". "
           (str/join " " (map (fn [component-name]
                                (str "(wchntConstructed " component-name ") the"
                                     class-name ": wchntConstructed."))
                              context-components))
           " wchntConstructed ] value)")
      constructor)))

(defn- branch-expression
  [branch schema-ir]
  (if (= :if (:expr branch))
    (expression branch schema-ir)
    (let [{:keys [lets body]} branch
          bindings (map (fn [{:keys [name value]}]
                          (str name " := " (expression value schema-ir) ".")) lets)]
      (str "[" (when (seq lets)
                  (str " | " (str/join " " (map :name lets)) " | "))
           (when (seq bindings) (str (str/join " " bindings) " "))
           (expression body schema-ir) "]"))))

(defn lambda-expression
  ([lambda] (lambda-expression lambda nil))
  ([{:keys [params lets body]} schema-ir]
   (str "[ " (str/join " " (map #(str ":" (:name %)) params)) " | "
        (when (seq lets)
          (str "| " (str/join " " (map :name lets)) " | "))
        (str/join " " (map (fn [{:keys [name value]}]
                              (str name " := " (expression value schema-ir) "."))
                            lets))
        (when (seq lets) " ")
        (expression body schema-ir) " ]")))

(defn- switch-expression
  [{:keys [scrutinee branches else]} schema-ir]
  (reduce (fn [otherwise {:keys [pattern body]}]
            (str "((" (expression scrutinee schema-ir) " = "
                 (expression pattern schema-ir) ") ifTrue: "
                 (branch-expression {:body body} schema-ir)
                 " ifFalse: " otherwise ")"))
          (branch-expression else schema-ir)
          (reverse branches)))

(defn expression
  ([expr] (expression expr nil))
  ([expr schema-ir]
   (case (:expr expr)
     :int (st-integer-literal (:value expr))
     :float (st-float-literal (:value expr))
     :bool (if (:value expr) "true" "false")
     :string (st-string (:value expr))
     :enum (str "#" (:name expr))
     :field (str "self " (:name expr))
     :param (:name expr)
     :local (:name expr)
     :this "self"
     :path (reduce (fn [root field] (str "(" root " " field ")"))
                   (expression (:root expr) schema-ir)
                   (:fields expr))
     :call (call-expression expr schema-ir)
     :construct (construct-expression expr schema-ir)
     :array (if (empty? (:items expr))
              "Array new"
              (str "(Array with: "
                   (str/join " with: " (map #(expression % schema-ir) (:items expr)))
                   ")"))
     :map (map-expression expr schema-ir)
     :lambda (lambda-expression expr schema-ir)
     :arith (str "(" (str/join " " (map #(if (string? %) %
                                                (expression % schema-ir))
                                          (:parts expr))) ")")
     :neg (str "(" (expression (:arg expr) schema-ir) " negated)")
     :not (str "(" (expression (:arg expr) schema-ir) " not)")
     :cmp (let [operator (case (:op expr) "==" "=" "!=" "~=" (:op expr))]
            (str "(" (expression (:left expr) schema-ir) " " operator " "
                 (expression (:right expr) schema-ir) ")"))
     :if (str "((" (expression (:condition expr) schema-ir)
              ") ifTrue: " (branch-expression (:then expr) schema-ir)
              " ifFalse: " (branch-expression (:else expr) schema-ir) ")")
     :switch (switch-expression expr schema-ir)
     :and (str "(" (str/join " and: [" (map #(expression % schema-ir) (:args expr)))
              (apply str (repeat (dec (count (:args expr))) "]")) ")")
     :or (str "(" (str/join " or: [" (map #(expression % schema-ir) (:args expr)))
             (apply str (repeat (dec (count (:args expr))) "]")) ")")
     (throw (ex-info (str "Unsupported expression in Smalltalk backend: "
                          (pr-str expr))
                     {:expression expr})))))

(defn- method-selector
  [{:keys [method-name parameters]}]
  (if (seq parameters)
    (str (smalltalk-method-name method-name) ": " (:name (first parameters))
         (apply str (map #(str " with: " (:name %)) (rest parameters))))
    (smalltalk-method-name method-name)))

(defn- method-chunk
  [method schema-ir]
  (let [{:keys [parameters lets body]} method
        temporaries (map :name lets)
        let-lines (map (fn [{:keys [name value]}]
                         (str name " := " (expression value schema-ir) "."))
                       lets)
        mutating-lines (when (:mutating? method)
                         (let [components (:components (class-ref (:class method) schema-ir))]
                           (mapcat (fn [{:keys [component-name relationship type-name]} arg]
                                     (cond-> [(str component-name " := "
                                                   (expression arg schema-ir) ".")]
                                       (and (= relationship :context-specific)
                                            (= (:class method)
                                               (get-in schema-ir
                                                       [:context-relationships type-name])))
                                       (conj (str "(" component-name ") the"
                                                  (:class method) ": self."))))
                                   components (:args body))))]
    (str (method-selector method)
         (when (seq temporaries)
           (str "\n    | " (str/join " " temporaries) " |"))
         (when (or (seq let-lines) (seq mutating-lines))
           (str "\n    " (str/join "\n    " (concat let-lines mutating-lines))))
         "\n    ^ " (if (:mutating? method) "self" (expression body schema-ir)))))

(defn- mailbox-inject-chunk
  [{:keys [name components]}]
  (let [params (mapv #(variable-name (:component-name %)) components)]
    (str "inject: " (first params)
         (apply str (map #(str " with: " %) (rest params)))
         "\n    "
         (str/join "\n    " (map (fn [{:keys [component-name]} param]
                               (str component-name " := " param "."))
                             components params))
         "\n    self updateMutates.\n    ^ self")))

(declare construction-value)

(defn- construction-object-value
  [arg construction-ir parameters schema-ir]
  (let [class-name (:class-name arg)
        components (:components (class-ref class-name schema-ir))
        constructor (str "(" class-name " new"
                        (apply str (map (fn [{:keys [component-name]} value]
                                          (str " " component-name ": "
                                               (construction-value value construction-ir
                                                                   parameters schema-ir) ";"))
                                        components (:args arg)))
                        " yourself)")
        context-links (for [[{:keys [component-name relationship type-name]} value]
                            (map vector components (:args arg))
                            :when (and (= relationship :context-specific)
                                       (= class-name (get-in schema-ir
                                                             [:context-relationships type-name])))]
                        (str "(wchntConstructed " component-name ") the"
                             class-name ": wchntConstructed."))]
    (if (seq context-links)
      (str "([ | wchntConstructed | wchntConstructed := " constructor ". "
           (str/join " " context-links)
           " wchntConstructed ] value)")
      constructor)))

(defn- construction-value
  [arg construction-ir parameters schema-ir]
  (case (:type arg)
    :primitive (if (= "String" (:class-name arg))
                 (st-string (:value arg))
                 (case (:class-name arg)
                   "Int" (st-integer-literal (:value arg))
                   "Float" (st-float-literal (:value arg))
                   (str (:value arg))))
    :enum-value (str "#" (or (:value arg) (first (:args arg))))
    :map (let [pairs (partition 2 (:args arg))]
           (if (empty? pairs)
             "Dictionary new"
             (str "(Dictionary newFrom: {"
                  (str/join ". " (map (fn [[key value]]
                                        (str (construction-value key construction-ir
                                                                 parameters schema-ir)
                                             " -> "
                                             (construction-value value construction-ir
                                                                 parameters schema-ir)))
                                      pairs))
                  "})")))
    :array (str "{" (str/join ". " (map #(construction-value % construction-ir
                                                                parameters schema-ir)
                                          (:args arg))) "}")
    :object (construction-object-value arg construction-ir parameters schema-ir)
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
    :object (let [components (:components (class-ref class-name schema-ir))]
              (concat
               (map (fn [{:keys [component-name]} arg]
                      (str object-id " " component-name ": "
                           (construction-value arg construction-ir parameters schema-ir) "."))
                    components args)
               (for [[{:keys [type-name relationship]} arg] (map vector components args)
                     :when (and (= relationship :context-specific)
                                (= class-name (get-in schema-ir
                                                      [:context-relationships type-name])))]
                 (str (construction-value arg construction-ir parameters schema-ir)
                      " the" class-name ": " object-id "."))))
    :array (map-indexed (fn [index arg]
                          (str object-id " at: " (inc index) " put: "
                               (construction-value arg construction-ir parameters schema-ir) "."))
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
        "    input := WCHNTInput new. stepBlock := nil.\n"
        "    fillColor := Color white. fillAlpha := 1.0.\n"
        "    backgroundColor := Color black. backgroundAlpha := 1.0.\n"
        "    lineColor := Color black. lineAlpha := 1.0. lineWidth := 0.\n"
        "    currentPoint := 0@0.\n"
        "    shapes := OrderedCollection new. pathPoints := OrderedCollection new.\n"
        "    fillActive := false.\n"
        "    self extent: 800@400.\n"
        "    input graphics: self. input attach")
   (str "game: aGame\n"
        "    game := aGame.\n"
        "    self clear.\n"
        "    game draw: self.\n"
        "    self startStepping.\n"
        "    ^ self")
   "stepTime\n    ^ 16"
   (str "step\n"
        "    stepBlock ifNotNil: [ stepBlock value ].\n"
        "    self changed")
   (str "stepBlock: aBlock\n"
        "    stepBlock := aBlock.\n"
        "    self startStepping.\n"
        "    ^ self")
   (str "background: aColor\n"
        "    backgroundColor := self colorFromInteger: aColor.\n"
        "    backgroundAlpha := self alphaFromInteger: aColor.\n"
        "    ^ self clear")
   (str "background: aColor alpha: anAlpha\n"
        "    backgroundColor := self colorFromInteger: aColor.\n"
        "    backgroundAlpha := anAlpha.\n"
        "    ^ self clear")
   (str "input: anInput\n"
        "    input := anInput.\n"
        "    input graphics: self.\n"
        "    input extent: self extent.\n"
        "    input attach.\n"
        "    ^ self")
   (str "extent: anExtent\n"
        "    super extent: anExtent.\n"
        "    input ifNotNil: [ input extent: anExtent ].\n"
        "    ^ self")
   (str "handlesMouseDown: anEvent\n    ^ true")
   (str "handlesMouseUp: anEvent\n    ^ true")
   (str "handlesMouseOver: anEvent\n    ^ true")
   (str "handlesKeyboard: anEvent\n    ^ true")
   (str "mouseMove: anEvent\n"
        "    input mouseAt: (anEvent position - self bounds origin) extent: self extent")
   (str "mouseDown: anEvent\n"
        "    input mouseAt: (anEvent position - self bounds origin) extent: self extent.\n"
        "    self takeKeyboardFocus.\n"
        "    input pointerDown")
   (str "mouseUp: anEvent\n"
        "    input mouseAt: (anEvent position - self bounds origin) extent: self extent.\n"
        "    input pointerUp")
   (str "keyDown: anEvent\n"
        "    input pressKey: (self keyNameFor: anEvent)")
   (str "keyUp: anEvent\n"
        "    input keyUp: (self keyNameFor: anEvent)")
   (str "keyNameFor: anEvent\n"
        "    | value |\n"
        "    value := anEvent keyValue.\n"
        "    value = 28 ifTrue: [ ^ 'ArrowLeft' ].\n"
        "    value = 29 ifTrue: [ ^ 'ArrowRight' ].\n"
        "    value = 30 ifTrue: [ ^ 'ArrowUp' ].\n"
        "    value = 31 ifTrue: [ ^ 'ArrowDown' ].\n"
        "    ^ value asCharacter asString")])

(defn- graphics-drawing-methods
  []
  [(str "clear\n"
        "    shapes removeAll.\n"
        "    shapes add: {#rectangle. (0@0 extent: self extent). backgroundColor. backgroundAlpha}.\n"
        "    pathPoints removeAll. fillActive := false. lineWidth := 0. currentPoint := 0@0.\n"
        "    self changed.\n"
        "    ^ self")
   (str "beginFill: aColor\n"
        "    fillColor := self colorFromInteger: aColor.\n"
        "    fillAlpha := self alphaFromInteger: aColor.\n"
        "    fillActive := true. pathPoints := OrderedCollection new.\n"
        "    ^ self")
   (str "beginFill: aColor alpha: anAlpha\n"
        "    fillColor := self colorFromInteger: aColor.\n"
        "    fillAlpha := anAlpha.\n"
        "    fillActive := true. pathPoints := OrderedCollection new.\n"
        "    ^ self")
   (str "lineStyle\n"
        "    lineWidth := 0.\n"
        "    ^ self")
   (str "lineStyle: aThickness color: aColor\n"
        "    lineWidth := aThickness.\n"
        "    lineColor := self colorFromInteger: aColor.\n"
        "    lineAlpha := self alphaFromInteger: aColor.\n"
        "    ^ self")
   (str "lineStyle: aThickness color: aColor alpha: anAlpha\n"
        "    lineWidth := aThickness.\n"
        "    lineColor := self colorFromInteger: aColor.\n"
        "    lineAlpha := anAlpha.\n"
        "    ^ self")
   (str "noStroke\n    ^ self lineStyle")
   (str "endFill\n"
        "    (fillActive and: [ pathPoints size >= 3 ]) ifTrue: [\n"
        "        shapes add: {#polygon. pathPoints asArray. fillColor. fillAlpha}.\n"
        "        2 to: pathPoints size do: [ :index |\n"
        "            shapes add: {#line. (pathPoints at: index - 1). (pathPoints at: index). lineColor. lineWidth. lineAlpha} ].\n"
        "        self changed ].\n"
        "    fillActive := false. pathPoints := OrderedCollection new.\n"
        "    ^ self")
   (str "drawRect: x y: y width: width height: height\n"
        "    shapes add: {#rectangle. (x@y extent: width@height). fillColor. fillAlpha. lineColor. lineWidth. lineAlpha}.\n"
        "    self changed.\n"
        "    ^ self")
   (str "drawCircle: x y: y radius: radius\n"
        "    | origin extent |\n"
        "    origin := (x - radius) @ (y - radius).\n"
        "    extent := (radius * 2) @ (radius * 2).\n"
        "    shapes add: {#oval. (origin extent: extent). fillColor. fillAlpha. lineColor. lineWidth. lineAlpha}.\n"
        "    self changed.\n"
        "    ^ self")
   (str "drawEllipse: x y: y radiusX: radiusX radiusY: radiusY\n"
        "    shapes add: {#oval. ((x - radiusX)@(y - radiusY) extent: (radiusX * 2)@(radiusY * 2)). fillColor. fillAlpha. lineColor. lineWidth. lineAlpha}.\n"
        "    self changed.\n"
        "    ^ self")
   (str "drawLine: x1 y: y1 x2: x2 y2: y2\n"
        "    shapes add: {#line. (x1@y1). (x2@y2). lineColor. lineWidth. lineAlpha}.\n"
        "    self changed.\n"
        "    ^ self")
   (str "moveTo: x y: y\n"
        "    currentPoint := x@y. pathPoints := OrderedCollection with: currentPoint.\n"
        "    ^ self")
   (str "lineTo: x y: y\n"
        "    fillActive ifFalse: [ self drawLine: currentPoint x y: currentPoint y x2: x y2: y ].\n"
        "    currentPoint := x@y. pathPoints add: currentPoint.\n"
        "    ^ self")
   (str "fillText: aString x: x y: y\n"
        "    shapes add: {#text. aString asString. x@y. fillColor. fillAlpha}.\n"
        "    self changed.\n"
        "    ^ self")
   (str "drawOn: aCanvas\n"
        "    super drawOn: aCanvas.\n"
        "    aCanvas translateBy: self bounds origin\n"
        "        clippingTo: self bounds\n"
        "        during: [ :canvas |\n"
        "            shapes do: [ :shape |\n"
        "                | drawingCanvas strokeCanvas alpha |\n"
        "                alpha := (#(rectangle oval) includes: shape first)\n"
        "                    ifTrue: [ shape at: 4 ] ifFalse: [ shape last ].\n"
        "                drawingCanvas := (alpha = 1.0)\n"
        "                    ifTrue: [ canvas ]\n"
        "                    ifFalse: [ canvas asAlphaBlendingCanvas: alpha ].\n"
        "                shape first = #rectangle ifTrue: [ drawingCanvas fillRectangle: (shape at: 2) color: (shape at: 3) ].\n"
        "                shape first = #oval ifTrue: [ drawingCanvas fillOval: (shape at: 2) color: (shape at: 3) ].\n"
        "                shape first = #line ifTrue: [ drawingCanvas line: (shape at: 2) to: (shape at: 3) width: (shape at: 5) color: (shape at: 4) ].\n"
        "                shape first = #polygon ifTrue: [ drawingCanvas drawPolygon: (shape at: 2) color: (shape at: 3) borderWidth: 0 borderColor: (shape at: 3) ].\n"
        "                shape first = #text ifTrue: [ drawingCanvas drawString: (shape at: 2) at: (shape at: 3) font: TextStyle defaultFont color: (shape at: 4) ].\n"
        "                shape first = #rectangle ifTrue: [\n"
        "                    (shape size >= 7 and: [ (shape at: 6) > 0 ]) ifTrue: [\n"
        "                        strokeCanvas := ((shape at: 7) = 1.0) ifTrue: [ canvas ] ifFalse: [ canvas asAlphaBlendingCanvas: (shape at: 7) ].\n"
        "                        strokeCanvas frameRectangle: (shape at: 2) width: (shape at: 6) color: (shape at: 5) ] ].\n"
        "                shape first = #oval ifTrue: [\n"
        "                    (shape size >= 7 and: [ (shape at: 6) > 0 ]) ifTrue: [\n"
        "                        strokeCanvas := ((shape at: 7) = 1.0) ifTrue: [ canvas ] ifFalse: [ canvas asAlphaBlendingCanvas: (shape at: 7) ].\n"
        "                        strokeCanvas frameOval: (shape at: 2) width: (shape at: 6) color: (shape at: 5) ] ] ] ]")])

(defn- graphics-color-methods
  []
  [(str "colorFromInteger: anInteger\n"
        "    ^ Color\n"
        "        r: ((((anInteger bitAnd: 16rFFFFFF) bitShift: -16) bitAnd: 255) / 255.0)\n"
        "        g: ((((anInteger bitAnd: 16rFFFFFF) bitShift: -8) bitAnd: 255) / 255.0)\n"
        "        b: ((anInteger bitAnd: 255) / 255.0)")
   (str "alphaFromInteger: anInteger\n"
        "    ^ (anInteger > 16rFFFFFF or: [ anInteger < 0 ])\n"
        "        ifTrue: [ (((anInteger bitShift: -24) bitAnd: 255) / 255.0) ]\n"
        "        ifFalse: [ 1.0 ]")
   "color: gray
    ^ self color: gray with: gray with: gray"
   "color: red with: green with: blue
    ^ self color: red with: green with: blue with: 255"
   (str "color: red with: green with: blue with: alpha\n"
        "    | r g b a |\n"
        "    r := self clampByte: red. g := self clampByte: green.\n"
        "    b := self clampByte: blue. a := self clampByte: alpha.\n"
        "    ^ (((a bitShift: 24) bitOr: (r bitShift: 16)) bitOr: (g bitShift: 8)) bitOr: b")
   "clampByte: aValue
    ^ (aValue max: 0) min: 255"
   "red: aColor
    ^ (aColor bitShift: -16) bitAnd: 255"
   "green: aColor
    ^ (aColor bitShift: -8) bitAnd: 255"
   "blue: aColor
    ^ aColor bitAnd: 255"
   "alpha: aColor
    ^ (aColor bitShift: -24) bitAnd: 255"])

(defn- maths-methods
  []
  [(str "initialize\n    super initialize.\n    randomGenerator := Random new")
   "rand
    ^ randomGenerator next"
   "pi
    ^ Float pi"
   (str "randInt: n\n"
        "    n > 0 ifFalse: [ self error: 'randInt requires n > 0' ].\n"
        "    ^ n atRandom - 1")
   "sin: aNumber
    ^ aNumber sin"
   "cos: aNumber
    ^ aNumber cos"
   "tan: aNumber
    ^ aNumber tan"
   "asin: aNumber
    ^ aNumber arcSin"
   "acos: aNumber
    ^ aNumber arcCos"
   "atan: aNumber
    ^ aNumber arcTan"
   (str "atan2: aY with: aX\n"
        "    | angle |\n"
        "    aX = 0 ifTrue: [\n"
        "        aY > 0 ifTrue: [ ^ Float pi / 2 ].\n"
        "        aY < 0 ifTrue: [ ^ 0 - (Float pi / 2) ].\n"
        "        ^ 0.0 ].\n"
        "    angle := (aY / aX) arcTan.\n"
        "    aX > 0 ifTrue: [ ^ angle ].\n"
        "    aY >= 0 ifTrue: [ ^ angle + Float pi ].\n"
        "    ^ angle - Float pi")
   "abs: aNumber
    ^ aNumber abs"
   "floor: aNumber
    ^ aNumber floor"
   "ceil: aNumber
    ^ aNumber ceiling"
   "round: aNumber
    ^ (aNumber + 0.5) floor"
   "sqrt: aNumber
    ^ aNumber sqrt"
   "log: aNumber
    ^ aNumber ln"
   "exp: aNumber
    ^ aNumber exp"
   "pow: aBase with: anExponent
    ^ aBase raisedTo: anExponent"
   "min: aLeft with: aRight
    ^ aLeft min: aRight"
   "max: aLeft with: aRight
    ^ aLeft max: aRight"
   (str "hsv: hue with: saturation with: value\n"
        "    | h sector index fraction p q t channels red green blue |\n"
        "    h := hue - hue floor.\n"
        "    sector := h * 6. index := sector floor. fraction := sector - index.\n"
        "    p := value * (1 - saturation).\n"
        "    q := value * (1 - (fraction * saturation)).\n"
        "    t := value * (1 - ((1 - fraction) * saturation)).\n"
        "    channels := {\n"
        "        {value. t. p}. {q. value. p}. {p. value. t}.\n"
        "        {p. q. value}. {t. p. value}. {value. p. q}\n"
        "    } at: (index + 1).\n"
        "    red := self clampChannel: channels first.\n"
        "    green := self clampChannel: channels second.\n"
        "    blue := self clampChannel: channels third.\n"
        "    ^ ((red bitShift: 16) bitOr: (green bitShift: 8)) bitOr: blue")
   (str "clampChannel: aChannel\n"
        "    ^ (((aChannel max: 0.0) min: 1.0) * 255) floor")])

(defn- runtime-methods
  []
  {"WCHNTConsole"
   ["println: anObject\n    Transcript show: anObject asString; cr.\n    ^ self"
    "nextLine\n    ^ UIManager default request: 'I-Spy guess' initialAnswer: ''"]
   "WCHNTMaths"
   (maths-methods)
   "WCHNTRuntime"
   [(str "template: aString using: aDictionary\n"
         "    | result |\n    result := aString.\n"
         "    aDictionary keysAndValuesDo: [ :key :value |\n"
         "        result := result copyReplaceAll: ('{' , key , '}') with: value asString ].\n"
         "    ^ result")
    (str "times: aCount applying: aBlock\n"
         "    | result |\n"
         "    aCount < 0 ifTrue: [ self error: 'Int::times expected a non-negative count' ].\n"
         "    result := Array new: aCount.\n"
         "    1 to: aCount do: [ :index | result at: index put: (aBlock value: index - 1) ].\n"
         "    ^ result")]
   "WCHNTGraphics"
   (concat (graphics-state-methods)
           (graphics-drawing-methods)
           (graphics-color-methods))
   "WCHNTInput"
   [(str "initialize\n"
         "    mouseX := 0. mouseY := 0. mouseNX := 0.0. mouseNY := 0.0.\n"
         "    width := 1.0. height := 1.0. mouseIsDown := false. attached := false.\n"
         "    graphics := nil. keys := Set new. pressedKeys := OrderedCollection new")
    "mouseX\n    ^ mouseX"
    "mouseY\n    ^ mouseY"
    "mouseNX\n    ^ mouseNX"
    "mouseNY\n    ^ mouseNY"
    "mouseDown\n    ^ mouseIsDown"
    (str "mouseAt: aPoint extent: anExtent\n"
         "    mouseX := ((aPoint x max: 0.0) min: (anExtent x max: 1.0)) rounded.\n"
         "    mouseY := ((aPoint y max: 0.0) min: (anExtent y max: 1.0)) rounded.\n"
         "    width := anExtent x max: 1.0. height := anExtent y max: 1.0.\n"
         "    mouseNX := ((aPoint x max: 0.0) min: width) / width.\n"
         "    mouseNY := ((aPoint y max: 0.0) min: height) / height")
    "extent: anExtent
    width := anExtent x max: 1.0. height := anExtent y max: 1.0"
    "graphics: aGraphics
    graphics := aGraphics"
    "attach
    attached := true. ^ self"
    (str "detach\n"
         "    attached := false. mouseIsDown := false.\n"
         "    keys removeAll. pressedKeys removeAll. ^ self")
    (str "focus\n"
         "    graphics ifNotNil: [ graphics takeKeyboardFocus ].\n"
         "    ^ self")
    "pointerDown\n    attached ifTrue: [ mouseIsDown := true ]"
    "pointerUp\n    mouseIsDown := false"
    (str "pressKey: aKey\n"
         "    (attached and: [ aKey notNil ]) ifTrue: [ keys add: aKey. pressedKeys add: aKey ]")
    "keyUp: aKey\n    keys remove: aKey ifAbsent: [ ]"
    "keyDown: aKey\n    ^ keys includes: aKey"
    (str "keyPresses\n"
         "    | result |\n"
         "    result := pressedKeys asArray.\n"
         "    pressedKeys removeAll.\n"
         "    ^ result") ]})

(defn- runtime-classes
  []
  [{:name "WCHNTConsole" :components []}
   {:name "WCHNTMaths" :components [{:component-name "randomGenerator"}]}
   {:name "WCHNTRuntime" :components []}
   {:name "WCHNTGraphics"
    :superclass "Morph"
    :components (mapv (fn [name] {:component-name name})
                      ["game" "fillColor" "fillAlpha" "backgroundColor"
                       "backgroundAlpha" "lineColor" "lineAlpha" "lineWidth"
                       "currentPoint" "pathPoints" "fillActive" "shapes"
                       "input" "stepBlock"])}
   {:name "WCHNTInput"
    :components (mapv (fn [name] {:component-name name})
                      ["mouseX" "mouseY" "mouseNX" "mouseNY" "width" "height"
                       "mouseIsDown" "attached" "keys" "pressedKeys" "graphics"])}])

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

(defn- init-declaration
  [source]
  (if-let [[_ declarations body] (re-matches #"(?s)\s*\|([^|]*)\|(.*)" source)]
    {:temporaries (str/split (str/trim declarations) #"\s+")
     :body (str/trim body)}
    {:temporaries [] :body (str/trim source)}))

(defn- frame-main-chunk
  [target-ir]
  (let [{:keys [temporaries body]} (init-declaration
                                    (get-in target-ir [:init :source]))
        step (str/trim (get-in target-ir [:step :source]))
        locals (concat ["wchntGraphics" "wchntInput" "wchntMaths"] temporaries)]
    (str "main\n    | " (str/join " " locals) " |\n"
         "    wchntGraphics := WCHNTGraphics new.\n"
         "    wchntInput := WCHNTInput new.\n"
         "    wchntMaths := WCHNTMaths new.\n"
         "    wchntGraphics input: wchntInput.\n"
         (when-not (str/blank? body) (str "    " (str/replace body #"\n" "\n    ") "\n"))
         "    wchntGraphics stepBlock: [\n"
         "        " (str/replace step #"\n" "\n        ") "\n"
         "    ].\n"
         "    wchntGraphics openInWindow.\n"
         "    wchntInput focus.\n"
         "    ^ wchntGraphics")))

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
        class-definitions (map #(class-declaration % schema-ir) declared-classes)
        model-methods (mapcat (fn [class]
                                (map (fn [source] [(:name class) source])
                                     (concat (accessor-chunks class schema-ir)
                                             (map #(method-chunk % schema-ir)
                                                  (filter (fn [m] (= (:name class) (:class m)))
                                                          methods))
                                             (when (contains? (set (:mailbox-classes schema-ir))
                                                              (:name class))
                                               [(mailbox-inject-chunk class)]))))
                              assemblages)
        assemblage-methods (when assemblage-class
                             (concat
                              [(construction-factory construction-ir schema-ir)]
                              (when-let [main (or (main-chunk target-ir)
                                                  (when (get-in target-ir [:init :source])
                                                    (frame-main-chunk target-ir)))]
                                [main])))
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
   :standard {:types #{"WCHNTMaths" "WCHNTGraphics" "WCHNTInput"}}
   :parse-target parse-target
   :emit emit-program})
