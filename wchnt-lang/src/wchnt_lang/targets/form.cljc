(ns wchnt-lang.targets.form
  "Live form target: DOM widgets are supplied by WCHNTForm."
  (:require [clojure.string :as str]
            [wchnt-lang.targets.core :as core]
            [wchnt-lang.targets.live-std :as live-std]))

(def widget-protocol
  "The WCHNT data protocol consumed by WCHNTForm.mount."
  {"Form" {"root" "Panel"}
   "Panel" {"id" "String" "children" "Array<Node>"}
   "Label" {"id" "String" "text" "String"}
   "TextInput" {"id" "String" "value" "String"}
   "TextArea" {"id" "String" "value" "String" "rows" "Int" "cols" "Int"}
   "Slider" {"id" "String" "min" "Float" "max" "Float"
             "step" "Float" "value" "Float"}
   "Button" {"id" "String" "text" "String"}
   "Option" {"value" "String" "label" "String"}
   "Options" {"id" "String" "options" "Array<Option>" "selected" "String"}
   "Canvas" {"id" "String" "width" "Int" "height" "Int"}})

(defn- schema-class
  [schema-ir class-name]
  (some #(when (= class-name (:name %)) %) (:assemblages schema-ir)))

(defn- schema-fields
  [schema-ir class-name]
  (into {}
        (map (juxt :component-name :type-name))
        (:components (schema-class schema-ir class-name))))

(defn- validate-widget-schema!
  [schema-ir class-name expected]
  (when-let [assemblage (schema-class schema-ir class-name)]
    (let [actual (schema-fields schema-ir class-name)
          missing (seq (remove (set (keys actual)) (keys expected)))
          extra (seq (remove (set (keys expected)) (keys actual)))
          wrong (seq (for [[field expected-type] expected
                           :let [actual-type (get actual field)]
                           :when (and actual-type (not= expected-type actual-type))]
                       [field expected-type actual-type]))]
      (when missing
        (throw (ex-info
                (str "%form protocol: " class-name
                     " is missing field(s): " (str/join ", " missing))
                {:class-name class-name :missing missing})))
      (when extra
        (throw (ex-info
                (str "%form protocol: " class-name
                     " has unexpected field(s): " (str/join ", " extra))
                {:class-name class-name :extra extra})))
      (when wrong
        (let [[field expected-type actual-type] (first wrong)]
          (throw (ex-info
                  (str "%form protocol: " class-name " field '" field
                       "' must be " expected-type ", got " actual-type)
                  {:class-name class-name
                   :field field
                   :expected expected-type
                   :actual actual-type})))))))

(defn- construction-type-ok?
  [expected arg]
  (let [actual (:class-name arg)]
    (or (= expected actual)
        (and (= expected "Float") (= actual "Int"))
        (and (= expected "Node") (contains? (set (keys widget-protocol)) actual))
        (and (re-matches #"^Array<(.+)>$" expected)
             (= :array (:type arg))
             (= (second (re-matches #"^Array<(.+)>$" expected)) actual)))))

(declare validate-widget-object!)

(defn- validate-widget-arg!
  [object-id index arg]
  (when (= :object (:type arg))
    (validate-widget-object! (str object-id "." index) arg))
  (when (= :array (:type arg))
    (doseq [[child-index child] (map-indexed vector (:args arg))]
      (when (= :object (:type child))
        (validate-widget-object!
         (str object-id "." index "." child-index)
         child)))))

(defn- validate-widget-object!
  [object-id object]
  (when-let [expected (get widget-protocol (:class-name object))]
    (let [class-name (:class-name object)
          args (:args object)
          expected-fields (vec (keys expected))]
      (when-not (= (count expected-fields) (count args))
        (throw (ex-info
                (str "%form construction: " class-name " (" object-id
                     ") expects " (count expected-fields)
                     " argument(s), got " (count args))
                {:class-name class-name
                 :object-id object-id
                 :expected-arity (count expected-fields)
                 :actual-arity (count args)})))
      (doseq [[field arg] (map vector expected-fields args)
              :let [expected-type (get expected field)]
              :when (and (not= "VariableRef" (:class-name arg))
                         (not (construction-type-ok? expected-type arg)))]
        (throw (ex-info
                (str "%form construction: " class-name " field '" field
                     "' expects " expected-type ", got " (:class-name arg))
                {:class-name class-name
                 :object-id object-id
                 :field field
                 :expected expected-type
                 :actual (:class-name arg)})))
      (doseq [[index arg] (map-indexed vector args)]
        (validate-widget-arg! object-id index arg)))))

(defn- validate-form-schema
  [cargo]
  (let [schema-ir (get-in cargo [:stash :schema-ir])
        form-class (schema-class schema-ir "Form")]
    (when-not form-class
      (throw (ex-info "%form schema must define a Form class" {})))
    (doseq [[class-name expected] widget-protocol]
      (validate-widget-schema! schema-ir class-name expected)))
  cargo)

(defn- validate-form-construction
  [cargo]
  (let [schema-ir (get-in cargo [:stash :schema-ir])
        construction-ir (get-in cargo [:stash :construction-ir])
        form-objects (filter #(= "Form" (:class-name (second %)))
                             (:objects construction-ir))]
    (when-not construction-ir
      (throw (ex-info "%form requires a Construction section" {})))
    (when-not (= 1 (count form-objects))
      (throw (ex-info
              (str "%form construction must contain exactly one Form object, got "
                   (count form-objects))
              {:form-count (count form-objects)})))
    (doseq [[object-id object] (:objects construction-ir)]
      (validate-widget-object! object-id object)))
  cargo)

(def plugin
  {:name "form"
   :backend :live
   :std :live
   :standard {:types #{"WCHNTMaths" "WCHNTConsole" "WCHNTGraphics" "WCHNTInput" "WCHNTForm"}
              :bindings [{:name "wchntForm" :host-key :form}
                         {:name "wchntGraphics" :host-key :graphics}
                         {:name "wchntInput" :host-key :input}
                         {:name "wchntConsole" :host-key :console}
                         {:name "wchntMaths" :host-key :maths}]}
   :validate-schema validate-form-schema
   :validate-construction validate-form-construction
   :parse-target core/parse-target})
