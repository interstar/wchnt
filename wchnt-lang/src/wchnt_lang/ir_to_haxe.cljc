(ns wchnt-lang.ir-to-haxe
  "Transform WCHNT IR to Haxe code"
  (:require [wchnt-lang.ir :as ir]
            [clojure.string :as str]))

;; =============================================================================
;; IR to Haxe Transformation
;; =============================================================================

(defn generate-component-field
  "Generate a Haxe field declaration for a component"
  [component]
  (let [component-name (::ir/component-name component)
        type-name (::ir/type-name component)
        relationship (::ir/relationship component)]
    (str "    public var " component-name ": " type-name ";")))

(defn generate-context-field
  "Generate a Haxe field for context-specific components"
  [parent-class-name]
  (str "    public var the" parent-class-name ": " parent-class-name ";"))

(defn generate-constructor-params
  "Generate constructor parameters for an assemblage"
  [components]
  (str/join ", " 
    (for [component components]
      (let [component-name (::ir/component-name component)
            type-name (::ir/type-name component)]
        (str component-name ":" type-name)))))

(defn generate-constructor-body
  "Generate constructor body for an assemblage"
  [components]
  (str/join "\n        " 
    (for [component components]
      (let [component-name (::ir/component-name component)]
        (str "this." component-name " = " component-name ";")))))

(defn generate-to-construction-parts
  "Generate the parts for a toConstruction method"
  [components]
  (for [component components]
    (let [component-name (::ir/component-name component)
          type-name (::ir/type-name component)]
      (cond
        (str/starts-with? type-name "Array<") 
        (str "ArrayExtensions.toConstruction(this." component-name ", depth + 1)")
        (str/starts-with? type-name "Map<") 
        (str "this." component-name ".toConstruction(depth + 1)")
        :else 
        (str "this." component-name ".toConstruction(depth + 1)")))))

(defn generate-to-construction-method
  "Generate the toConstruction method for a class"
  [class-name components]
  (let [to-construction-parts (generate-to-construction-parts components)
        indent-line (str "ind + '  ' + ")]
    (str "\n    public function toConstruction(depth:Int = 0):String {\n"
         "        var ind = \"\";\n"
         "        for (i in 0...depth) ind += \"  \";\n"
         "        var nl = '\\n';\n"
         "        return ind + '[:" class-name "' + nl + "
         (str/join " + nl + " (map #(str indent-line %) to-construction-parts)) 
         " + nl + ind + ']' ;\n"
         "    }")))

(defn generate-set-context-method
  "Generate setContext method for context-specific components"
  [parent-class-name]
  (str "\n    public function setContext(c: " parent-class-name ") {\n"
       "        this.the" parent-class-name " = c;\n"
       "    }"))

(defn generate-observable-infrastructure
  "Generate observable infrastructure for classes that need it"
  [class-name]
  (str "\n    // Observable infrastructure for " class-name "\n"
       "    private var subscribers: Array<Dynamic> = [];\n"
       "\n"
       "    public function subscribe(subscriber: Dynamic): Void {\n"
       "        subscribers.push(subscriber);\n"
       "    }\n"
       "\n"
       "    public function unsubscribe(subscriber: Dynamic): Void {\n"
       "        subscribers.remove(subscriber);\n"
       "    }\n"
       "\n"
       "    public function notifySubscribers(): Void {\n"
       "        for (subscriber in subscribers) {\n"
       "            if (Reflect.hasField(subscriber, 'update')) {\n"
       "                Reflect.callMethod(subscriber, Reflect.field(subscriber, 'update'), []);\n"
       "            }\n"
       "        }\n"
       "    }"))

(defn generate-haxe-class
  "Generate Haxe class from IR assemblage"
  [assemblage schema-ir]
  (let [class-name (::ir/name assemblage)
        components (::ir/components assemblage)
        needs-context (ir/needs-context? schema-ir class-name)
        context-parent (ir/get-context-parent schema-ir class-name)
        is-observable (ir/is-observable? schema-ir class-name)
        
        ;; Find interfaces this class implements
        interface-implementers (::ir/interface-implementers schema-ir)
        implemented-interfaces (filter #(contains? (set (second %)) class-name) interface-implementers)
        interface-names (map first implemented-interfaces)
        implements-clause (if (empty? interface-names)
                           ""
                           (str " implements " (str/join ", " interface-names)))
        
        ;; Generate fields
        component-fields (map generate-component-field components)
        context-field (when needs-context (generate-context-field context-parent))
        all-fields (if context-field
                     (conj component-fields context-field)
                     component-fields)
        
        ;; Generate constructor
        constructor-params (generate-constructor-params components)
        constructor-body (generate-constructor-body components)
        
        ;; Generate methods
        to-construction-method (generate-to-construction-method class-name components)
        set-context-method (when needs-context (generate-set-context-method context-parent))
        observable-infrastructure (when is-observable (generate-observable-infrastructure class-name))
        
        ;; Combine all parts
        class-code (str "class " class-name implements-clause " {\n"
                       (str/join "\n" all-fields)
                       "\n\n"
                       "    public function new(" constructor-params ") {\n"
                       "        " constructor-body "\n"
                       "    }"
                       (or set-context-method "")
                       to-construction-method
                       (or observable-infrastructure "")
                       "\n}")]
    class-code))

(defn generate-haxe-interface
  "Generate Haxe interface from IR interface"
  [interface]
  (let [interface-name (::ir/name interface)]
    (str "interface " interface-name " {\n"
         "    public function toConstruction(depth:Int = 0):String;\n"
         "}")))

(defn generate-haxe-enum
  "Generate Haxe enum from IR enum"
  [enum]
  (let [enum-name (::ir/name enum)
        enum-values (::ir/values enum)
        processed-values (for [value enum-values]
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

(defn generate-array-extensions
  "Generate ArrayExtensions class if needed"
  [schema-ir]
  (let [assemblages (::ir/assemblages schema-ir)
        has-arrays (some #(some (fn [comp] 
                                  (str/starts-with? (::ir/type-name comp) "Array<")) 
                                (::ir/components %)) 
                        assemblages)]
    (when has-arrays
      "// Extension methods for Array toConstruction
class ArrayExtensions {
    public static function toConstruction<T>(arr: Array<T>, depth: Int = 0): String {
        var ind = \"\";
        for (i in 0...depth) ind += \"  \";
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
}")))

(defn schema-ir-to-haxe
  "Transform schema IR to Haxe code"
  [schema-ir]
  (let [assemblages (::ir/assemblages schema-ir)
        interfaces (::ir/interfaces schema-ir)
        enums (::ir/enums schema-ir)
        
        ;; Generate classes
        classes (map #(generate-haxe-class % schema-ir) assemblages)
        
        ;; Generate interfaces
        interface-classes (map generate-haxe-interface interfaces)
        
        ;; Generate enums
        enum-classes (map generate-haxe-enum enums)
        
        ;; Generate array extensions if needed
        array-extensions (generate-array-extensions schema-ir)
        
        ;; Combine all parts
        all-classes (cond-> (concat classes interface-classes enum-classes)
                            array-extensions (conj array-extensions))
        joined-classes (str/join "\n\n" all-classes)]
    joined-classes)) 