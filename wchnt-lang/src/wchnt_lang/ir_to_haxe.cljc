(ns wchnt-lang.ir-to-haxe
  "Transform WCHNT IR to Haxe code"
  (:require [wchnt-lang.ir :as ir]
            [clojure.string :as str]
            [wchnt-lang.ast-utils :as ast-utils]
            [wchnt-lang.pipeline :as p]
            [wchnt-lang.ast-to-ir :as ast-to-ir]))

;; =============================================================================
;; Haxe Helper Interface and Implementation
;; =============================================================================

(def iwchnt-helper-interface
  "// Helper interface for complex type serialization
interface IWCHNTHelper {
    public function arrayToConstruction<T>(arr: Array<T>, depth: Int): String;
    public function mapToConstruction<K,V>(map: Map<K,V>, depth: Int): String;
    public function enumToConstruction(enumValue: Dynamic, depth: Int): String;
}")

(def iwchnt-helper-implementation
  "// Implementation of the helper interface
class WCHNTHelper implements IWCHNTHelper {
    public function new() {}
    
    public function arrayToConstruction<T>(arr: Array<T>, depth: Int): String {
        var ind = \"\";
        for (i in 0...depth) ind += \"  \";
        var nl = '\\n';
        var result = ind + '[:Array';
        for (item in arr) {
            if (Std.isOfType(item, String)) {
                result += nl + ind + '  ' + '\"' + item + '\"';
            } else if (Reflect.hasField(item, 'toConstruction')) {
                result += nl + ind + '  ' + Reflect.callMethod(item, Reflect.field(item, 'toConstruction'), [depth + 1, this]);
            } else {
                result += nl + ind + '  ' + Std.string(item);
            }
        }
        result += nl + ind + ']';
        return result;
    }
    
    public function mapToConstruction<K,V>(map: Map<K,V>, depth: Int): String {
        var ind = \"\";
        for (i in 0...depth) ind += \"  \";
        var nl = '\\n';
        var result = ind + '[:Map';
        for (key in map.keys()) {
            var value = map.get(key);
            result += nl + ind + '  ';
            if (Std.isOfType(key, String)) {
                result += '\"' + key + '\"';
            } else {
                result += Std.string(key);
            }
            result += ': ';
            if (Std.isOfType(value, String)) {
                result += '\"' + value + '\"';
            } else if (Reflect.hasField(value, 'toConstruction')) {
                result += Reflect.callMethod(value, Reflect.field(value, 'toConstruction'), [depth + 1, this]);
            } else {
                result += Std.string(value);
            }
        }
        result += nl + ind + ']';
        return result;
    }
    
    public function enumToConstruction(enumValue: Dynamic, depth: Int): String {
        var ind = \"\";
        for (i in 0...depth) ind += \"  \";
        return ind + Std.string(enumValue);
    }
}")

(def iwchnt-object-interface
  "// Interface that all WCHNT objects implement (updated to use helper)
interface IWCHNTObject {
    public function toConstruction(depth:Int = 0, helper:IWCHNTHelper):String;
}")

;; =============================================================================
;; IR to Haxe Transformation
;; =============================================================================

(defn generate-component-field
  "Generate a Haxe field declaration for a component"
  [component]
  (let [component-name (:component-name component)
        type-name (:type-name component)
        relationship (:relationship component)]
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
      (let [component-name (:component-name component)
            type-name (:type-name component)]
        (str component-name ":" type-name)))))

(defn generate-constructor-body
  "Generate constructor body for an assemblage"
  [components]
  (str/join "\n        " 
    (for [component components]
      (let [component-name (:component-name component)]
        (str "this." component-name " = " component-name ";")))))

(defn generate-to-construction-parts
  "Generate the parts for a toConstruction method using the new helper pattern"
  [components schema-ir]
  (for [component components]
    (let [component-name (:component-name component)
          type-name (:type-name component)]
      (cond
        (str/starts-with? type-name "Array<")
        (str "helper.arrayToConstruction(this." component-name ", depth + 1)")
        (str/starts-with? type-name "Map<")
        (str "helper.mapToConstruction(this." component-name ", depth + 1)")
        ;; Handle primitive types (fix the string quoting issue)
        (= type-name "String")
        (str "'\"' + this." component-name " + '\"'")
        (contains? #{"Int" "Float" "Bool"} type-name)
        (str "this." component-name)
        ;; Check if it's an enum type
        (some #(= (:name %) type-name) (:enums schema-ir))
        (str "helper.enumToConstruction(this." component-name ", depth + 1)")
        ;; For other types (custom classes), call toConstruction with helper
        :else
        (str "this." component-name ".toConstruction(depth + 1, helper)")))))

(defn generate-to-construction-method
  "Generate the toConstruction method for a class using the new helper pattern"
  [class-name components schema-ir]
  (let [to-construction-parts (generate-to-construction-parts components schema-ir)
        indent-line (str "ind + '  ' + ")]
    (str "\n    public function toConstruction(depth:Int = 0, helper:IWCHNTHelper):String {\n"
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
  (let [class-name (:name assemblage)
        components (:components assemblage)
        needs-context (ir/needs-context? schema-ir class-name)
        context-parent (ir/get-context-parent schema-ir class-name)
        is-observable (ir/is-observable? schema-ir class-name)

        ;; Find interfaces this class implements
        interface-implementers (:interface-implementers schema-ir)
        implemented-interfaces (filter #(contains? (set (second %)) class-name) interface-implementers)
        interface-names (map first implemented-interfaces)
        ;; Add IWCHNTObject to all classes
        all-interfaces (conj (vec interface-names) "IWCHNTObject")
        implements-clause (if (empty? all-interfaces)
                            ""
                            (str " implements " (str/join " implements " all-interfaces)))
        
        ;; Generate fields
        component-fields (map generate-component-field components)
        context-field (when (and needs-context context-parent) (generate-context-field context-parent))
        all-fields (if context-field
                     (conj component-fields context-field)
                     component-fields)
        
        ;; Generate constructor
        constructor-params (generate-constructor-params components)
        constructor-body (generate-constructor-body components)
        
        ;; Generate methods
        to-construction-method (generate-to-construction-method class-name components schema-ir)
        set-context-method (when (and needs-context context-parent) (generate-set-context-method context-parent))
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
  (let [interface-name (:name interface)]
    (str "interface " interface-name " {\n"
         "    public function toConstruction(depth:Int = 0):String;\n"
         "}")))

(defn generate-haxe-enum
  "Generate Haxe enum from IR enum"
  [enum]
  (let [enum-name (:name enum)
        enum-values (:values enum)
        processed-values (for [value enum-values]
                          (let [raw-value (str/trim value)
                                enum-value-name (-> raw-value
                                                   (str/replace #"[^A-Za-z0-9]" "")
                                                   (str/replace #"^[a-z]" str/upper-case))]
                            enum-value-name))]
    (str "enum " enum-name " {\n"
         (str/join "\n" (map #(str "    " % ";") processed-values))
         "\n}")))

;; No longer needed - arrays are handled inline in each class

(defn schema-ir-to-haxe
  "Transform schema IR to Haxe code"
  [schema-ir]
  (let [assemblages (:assemblages schema-ir)
        interfaces (:interfaces schema-ir)
        enums (:enums schema-ir)
        
        ;; Generate interfaces first (so they can be implemented by classes)
        interface-classes (map generate-haxe-interface interfaces)
        
        ;; Use the top-level helper definitions
        iwchnt-helper (str iwchnt-helper-interface "\n\n" iwchnt-helper-implementation "\n\n" iwchnt-object-interface)
        
        ;; Generate classes
        classes (map #(generate-haxe-class % schema-ir) assemblages)
        
        ;; Generate enums
        enum-classes (map generate-haxe-enum enums)
        
        ;; Combine all parts (interfaces first, then classes, then enums)
        all-classes (concat interface-classes [iwchnt-helper] classes enum-classes)
        joined-classes (str/join "\n" all-classes)]
    joined-classes)) 

;; =============================================================================
;; Construction IR to Haxe Transformation
;; =============================================================================

(defn process-inner-object-construction
  "Process an InnerObjectConstruction node and return Haxe constructor code"
  [element schema-ir expected-type]
  (cond
    ;; InnerObjectConstruction: [:InnerObjectConstruction [:ClassName "Name"] [:ArgList ...]] or [:InnerObjectConstruction [:ArgList ...]]
    (ast-utils/node-type? element :InnerObjectConstruction)
    (let [second-element (second element)
          [class-name arg-list]
          (if (ast-utils/node-type? second-element :ClassName)
            ;; Has explicit class name: [:InnerObjectConstruction [:ClassName "Name"] [:ArgList ...]]
            [(second second-element) (nth element 2)]
            ;; No explicit class name: [:InnerObjectConstruction [:ArgList ...]]
            (if expected-type
              ;; Use the expected type from the array
              [expected-type second-element]
              ;; Cannot determine class name - fail fast
              (throw (ex-info "Cannot determine class name for InnerObjectConstruction - no explicit class name and no expected type" 
                            {:element element
                             :schema-ir schema-ir
                             :expected-type expected-type}))))
          constructor-args
          (if (ast-utils/node-type? arg-list :ArgList)
            (rest arg-list)
            [])]
      (str "new " class-name "("
           (str/join
            ", "
            (for [arg constructor-args]
              (cond
                ;; Handle nested InnerObjectConstruction nodes recursively
                (and (vector? arg) (ast-utils/node-type? arg :InnerObjectConstruction))
                (if schema-ir
                  ;; Use schema to determine expected type for this position
                  (let [expected-arg-type (ast-to-ir/type-from-class-and-position class-name 0 schema-ir)]
                    (process-inner-object-construction arg schema-ir expected-arg-type))
                  ;; Schema is required for nested InnerObjectConstruction processing
                  (throw (ex-info "Schema IR is required to process nested InnerObjectConstruction nodes" 
                                {:element arg
                                 :class-name class-name
                                 :schema-ir schema-ir})))
                ;; Handle ArrayConstruction nodes
                (and (vector? arg) (ast-utils/node-type? arg :ArrayConstruction))
                (str arg)
                ;; Handle primitive AST nodes
                (and (vector? arg) (ast-utils/node-type? arg :IntLiteral))
                (second arg)
                (and (vector? arg) (ast-utils/node-type? arg :StringLiteral))
                (str "\"" (second arg) "\"")
                (and (vector? arg) (ast-utils/node-type? arg :FloatLiteral))
                (second arg)
                (and (vector? arg) (ast-utils/node-type? arg :BooleanLiteral))
                (second arg)
                ;; Handle VariableRef nodes
                (and (vector? arg) (ast-utils/node-type? arg :VariableRef))
                (second arg)
                ;; Handle IR objects
                (and (map? arg) (= (:type arg) :primitive))
                (:value arg)
                ;; Default case
                :else
                (str arg))))
           ")"))
    :else
    (str element)))

(defn generate-array-element
  "Generate Haxe code for a single array element"
  [element schema-ir expected-type]
  (cond
    ;; Handle structured IR objects
    (and (map? element) (= (:type element) :object))
    (let [arg-class-name (:class-name element)
          arg-args (:args element)]
      (str "new " arg-class-name "("
           (str/join ", " (map #(generate-array-element % schema-ir expected-type) arg-args))
           ")"))
    
    ;; Handle variable references
    (and (map? element) (= (:type element) :variable))
    (first (:args element))  ;; Variable name
    
    ;; Handle primitive values
    (and (map? element) (= (:type element) :primitive))
    (let [primitive-value (first (:args element))
          class-name (:class-name element)]
      (if (or (= class-name "String") (= class-name 'String))
        (str "\"" primitive-value "\"")  ;; String type - add quotes
        (str primitive-value)))  ;; Other primitives - no quotes
    
    ;; Handle enum values
    (and (map? element) (= (:type element) :enum-value))
    (first (:args element))  ;; Enum value (not quoted)
    
    ;; Handle nested arrays
    (and (map? element) (= (:type element) :array))
    (let [array-type (:class-name element)
          array-elements (:args element)]
      (str "["
           (str/join ", " (map #(generate-array-element % schema-ir array-type) array-elements))
           "]"))
    
    ;; Handle raw AST nodes (fallback for backward compatibility)
    (ast-utils/node-type? element :InnerObjectConstruction)
    (process-inner-object-construction element schema-ir expected-type)
    
    ;; Primitive types (raw AST)
    (ast-utils/node-type? element :IntLiteral) (second element)
    (ast-utils/node-type? element :StringLiteral) (str "\"" (second element) "\"")
    (ast-utils/node-type? element :FloatLiteral) (second element)
    (ast-utils/node-type? element :BooleanLiteral) (second element)
    
    ;; Variable reference (raw AST)
    (ast-utils/node-type? element :VariableRef) (second element)
    
    ;; Default to string conversion
    :else (str element)))

(defn generate-array-assignment
  "Generate Haxe code for an array assignment"
  [obj-id args schema-ir class-name]
  (str "  var " obj-id " = ["
       (str/join ", " (map #(generate-array-element % schema-ir class-name) args))
       "];"))

(defn generate-ast-node-arg
  "Generate Haxe code for a raw AST node (fallback for backward compatibility)"
  [ast-node schema-ir parent-class-name arg-index]
  (cond
    ;; InnerObjectConstruction
    (ast-utils/node-type? ast-node :InnerObjectConstruction)
    (process-inner-object-construction ast-node schema-ir parent-class-name)
    
    ;; ArrayConstruction
    (ast-utils/node-type? ast-node :ArrayConstruction)
    (let [type-node (second ast-node)
          array-type (if (ast-utils/node-type? type-node :Type)
                      (second type-node)
                      "Unknown")
          arg-list (nth ast-node 2)
          elements (if (ast-utils/node-type? arg-list :ArgList)
                    (rest arg-list)
                    [])]
      (str "["
           (str/join ", " (map #(generate-ast-node-arg % schema-ir array-type 0) elements))
           "]"))
    
    ;; Primitive literals
    (ast-utils/node-type? ast-node :IntLiteral) (second ast-node)
    (ast-utils/node-type? ast-node :StringLiteral) (str "\"" (second ast-node) "\"")
    (ast-utils/node-type? ast-node :FloatLiteral) (second ast-node)
    (ast-utils/node-type? ast-node :BooleanLiteral) (second ast-node)
    
    ;; Variable references
    (ast-utils/node-type? ast-node :VariableRef) (second ast-node)
    
    ;; Default case
    :else (str ast-node)))

(defn generate-object-assignment-arg
  "Generate Haxe code for a single argument in object assignment"
  [arg schema-ir]
  (cond
    ;; Handle structured IR objects
    (and (map? arg) (= (:type arg) :object))
    (let [arg-class-name (:class-name arg)
          arg-args (:args arg)]
      (str "new " arg-class-name "("
           (str/join ", " (map #(generate-object-assignment-arg % schema-ir) arg-args))
           ")"))
    
    ;; Handle variable references
    (and (map? arg) (= (:type arg) :variable))
    (first (:args arg))  ;; Variable name
    
    ;; Handle primitive values
    (and (map? arg) (= (:type arg) :primitive))
    (let [primitive-value (first (:args arg))
          class-name (:class-name arg)]
      (if (or (= class-name "String") (= class-name 'String))
        ;; Check if the primitive value is a raw AST node that needs processing
        (if (and (vector? primitive-value) (= (first primitive-value) :StringLiteral))
          (str "\"" (second primitive-value) "\"")  ;; Extract string from AST node
          (str "\"" primitive-value "\""))  ;; String type - add quotes
        (str primitive-value)))  ;; Other primitives - no quotes
    
    ;; Handle enum values
    (and (map? arg) (= (:type arg) :enum-value))
    (first (:args arg))  ;; Enum value (not quoted)
    
    ;; Handle array types
    (and (map? arg) (= (:type arg) :array))
    (let [array-type (:class-name arg)
          array-elements (:args arg)]
      (str "["
           (str/join ", " (map #(generate-object-assignment-arg % schema-ir) array-elements))
           "]"))
    
    ;; Handle raw AST nodes (fallback)
    (vector? arg)
    (generate-ast-node-arg arg schema-ir nil 0)
    
    ;; Default case
    :else
    (str arg)))

(defn generate-object-assignment
  "Generate Haxe code for an object assignment using structured IR data"
  [obj-id class-name args schema-ir]
  (let [;; Get component types from schema for proper type handling
        assemblage (first (filter #(= (:name %) class-name) (:assemblages schema-ir)))
        component-types (map :type-name (:components assemblage))
        
        ;; Process arguments using structured IR data
        processed-args (for [[arg-index arg] (map-indexed vector args)]
                        (cond
                          ;; Handle structured IR objects
                          (and (map? arg) (= (:type arg) :object))
                          (let [arg-class-name (:class-name arg)
                                arg-args (:args arg)]
                            (str "new " arg-class-name "("
                                 (str/join ", " (map #(generate-object-assignment-arg % schema-ir) arg-args))
                                 ")"))
                          
                          ;; Handle variable references
                          (and (map? arg) (= (:type arg) :variable))
                          (first (:args arg))  ;; Variable name
                          
                          ;; Handle primitive values
                          (and (map? arg) (= (:type arg) :primitive))
                          (let [primitive-value (first (:args arg))
                                class-name (:class-name arg)]
                            (if (or (= class-name "String") (= class-name 'String))
                              ;; Check if the primitive value is a raw AST node that needs processing
                              (if (and (vector? primitive-value) (= (first primitive-value) :StringLiteral))
                                (str "\"" (second primitive-value) "\"")  ;; Extract string from AST node
                                (str "\"" primitive-value "\""))  ;; String type - add quotes
                              (str primitive-value)))  ;; Other primitives - no quotes
                          
                          ;; Handle enum values
                          (and (map? arg) (= (:type arg) :enum-value))
                          (first (:args arg))  ;; Enum value (not quoted)
                          
                          ;; Handle array types
                          (and (map? arg) (= (:type arg) :array))
                          (let [array-type (:class-name arg)
                                array-elements (:args arg)]
                            (str "["
                                 (str/join ", " (map #(generate-object-assignment-arg % schema-ir) array-elements))
                                 "]"))
                          
                          ;; Handle raw AST nodes (fallback for backward compatibility)
                          (vector? arg)
                          (generate-ast-node-arg arg schema-ir nil 0)
                          
                          ;; Handle plain strings (fallback)
                          (string? arg)
                          (if (and (< arg-index (count component-types))
                                   (= (nth component-types arg-index) "String"))
                            (str "\"" arg "\"")  ;; String component
                            arg)  ;; Non-string component
                          
                          ;; Default case
                          :else
                          (str arg)))
        
        ;; Generate the assignment statement
        assignment-code (if (str/starts-with? class-name "Array<")
                         ;; Array type - use array literal syntax
                         (str "  var " obj-id " = ["
                              (str/join ", " processed-args) "];")
                         ;; Object type - use constructor syntax
                         (str "  var " obj-id " = new " class-name "("
                              (str/join ", " processed-args) ");"))]
    assignment-code))



(defn generate-ast-node-arg
  "Generate Haxe code for a raw AST node (fallback for backward compatibility)"
  [ast-node schema-ir parent-class-name arg-index]
  (cond
    ;; InnerObjectConstruction
    (ast-utils/node-type? ast-node :InnerObjectConstruction)
    (process-inner-object-construction ast-node schema-ir parent-class-name)
    
    ;; ArrayConstruction
    (ast-utils/node-type? ast-node :ArrayConstruction)
    (let [type-node (second ast-node)
          array-type (if (ast-utils/node-type? type-node :Type)
                      (second type-node)
                      "Unknown")
          arg-list (nth ast-node 2)
          elements (if (ast-utils/node-type? arg-list :ArgList)
                    (rest arg-list)
                    [])]
      (str "["
           (str/join ", " (map #(generate-ast-node-arg % schema-ir array-type 0) elements))
           "]"))
    
    ;; Primitive literals
    (ast-utils/node-type? ast-node :IntLiteral) (second ast-node)
    (ast-utils/node-type? ast-node :StringLiteral) (str "\"" (second ast-node) "\"")
    (ast-utils/node-type? ast-node :FloatLiteral) (second ast-node)
    (ast-utils/node-type? ast-node :BooleanLiteral) (second ast-node)
    
    ;; Variable references
    (ast-utils/node-type? ast-node :VariableRef) (second ast-node)
    
    ;; Default case
    :else (str ast-node)))

(defn generate-variable-assignment
  "Generate Haxe code for a variable assignment"
  [obj-id args]
  (if (seq args)
    (str "  var " obj-id " = " (first args) ";")
    (throw (ex-info "Variable assignment missing arguments" {:obj-id obj-id :args args}))))

(defn generate-map-assignment
  "Generate Haxe code for a map assignment"
  [obj-id class-name args]
  (let [key-value-pairs (map (fn [[key val]]
                              (str key " => " val))
                            args)]
    (str "  var " obj-id " = new " class-name "([" (str/join ", " key-value-pairs) "]);")))

(defn generate-primitive-assignment
  "Generate Haxe code for a primitive assignment"
  [obj-id obj-data]
  (let [value (if (contains? obj-data :value)
                (:value obj-data)
                (if (and (seq (:args obj-data)) (map? (first (:args obj-data))))
                  (:value (first (:args obj-data)))
                  (if (seq (:args obj-data))
                    (first (:args obj-data))
                    (throw (ex-info "Primitive assignment missing arguments" {:obj-id obj-id :obj-data obj-data})))))]
    (str "  var " obj-id " = " value ";")))

(defn generate-assignment-statement
  "Generate Haxe code for a single assignment statement"
  [obj-id obj-data schema-ir]
  (let [obj-type (:type obj-data)
        class-name (:class-name obj-data)
        args (:args obj-data)]
    (case obj-type
      :array (generate-array-assignment obj-id args schema-ir class-name)
      :object (generate-object-assignment obj-id class-name args schema-ir)
      :variable (generate-variable-assignment obj-id args)
      :enum-value (if (seq args)
                    (str "  var " obj-id " = " (first args) ";")
                    (throw (ex-info "Enum value assignment missing arguments" {:obj-id obj-id :args args})))
      :map (generate-map-assignment obj-id class-name args)
      :primitive (generate-primitive-assignment obj-id obj-data)
      (throw (ex-info "Unknown object type in factory generation" 
                    {:obj-type obj-type
                     :obj-data obj-data})))))



(defn generate-factory-body
  "Generate the body of a factory function from construction IR"
  [construction-ir schema-ir]
  (let [root-class (:root-class construction-ir)
        objects (:objects construction-ir)
        variable-mappings (:variable-mappings construction-ir)
        return-object (:return-object construction-ir)]
    
    ;; All objects now use simple objN naming
    (let [all-objects objects
          
          ;; Generate assignment statements for all objects
          assignment-statements
          (for [[obj-id obj-data] all-objects]
            (generate-assignment-statement obj-id obj-data schema-ir))

          ;; Generate final return statement
          final-statement (str "  return " return-object ";")]
      
      (str/join "\n" (concat assignment-statements [final-statement])))))

(defn generate-construction-factory
  "Generate Haxe factory function from construction IR"
  [construction-ir schema-ir]
  (let [root-class (:root-class construction-ir)
        factory-name (:factory-name construction-ir)
        body (generate-factory-body construction-ir schema-ir)]
    (str "public static function " factory-name "(): " root-class " {\n"
         body "\n"
         "}"))) 