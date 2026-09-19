(ns wchnt-lang.targets.requires
  "Shared grammar and IR for platform-provided external classes.

   A class-only declaration makes a host type available:
     WCHNTGraphics

   A method declaration additionally describes a WCHNT-visible signature:
     WCHNTGraphics::color(Int, Int, Int) -> Int"
  (:require [clojure.set]
            [clojure.string]
            [instaparse.core :as insta]
            [wchnt-lang.grammars :as grammars]))

(def ^:private requires-grammar
  "Requires = (MethodDeclaration / ClassDeclaration)*
   MethodDeclaration = Type <'::'> MethodName <'('> ArgTypes? <')'> <'->'> Type
   ArgTypes = Type (<','> Type)*
   ClassDeclaration = Type
   Type = Name
   MethodName = Name
   Name = #'[A-Za-z][A-Za-z0-9_]*'
   WS = #'\\s+'")

(def ^:private requires-parser
  (insta/parser requires-grammar :auto-whitespace :standard))

(defn- node?
  [tag node]
  (and (vector? node) (= tag (first node))))

(defn- children
  [tag tree]
  (cond
    (node? tag tree) [tree]
    (vector? tree) (mapcat #(children tag %) (rest tree))
    (seq? tree) (mapcat #(children tag %) tree)
    :else []))

(defn- child-value
  [tag node]
  (some (fn [child]
          (when (node? tag child)
            (let [value (second child)]
              (if (vector? value) (second value) value))))
        (rest node)))

(defn- method-entry
  [node]
  (let [method-node (first (children :MethodDeclaration node))
        types (mapv (fn [type-node]
                      (let [value (second type-node)]
                        (if (vector? value) (second value) value)))
                    (children :Type method-node))]
    {:class-name (first types)
     :method-name (child-value :MethodName method-node)
     :args (vec (butlast (rest types)))
     :return (last types)}))

(defn- class-entry
  [node]
  {:class-name (child-value :Type node)
   :method-name nil})

(defn- entries
  [ast]
  (mapv (fn [node]
          (if (node? :MethodDeclaration node)
            (method-entry node)
            (class-entry node)))
        (rest ast)))

(defn- add-entry
  [result {:keys [class-name method-name args return]}]
  (if method-name
    (update-in result [:classes class-name :methods method-name]
               (fnil conj [])
               {:args args :arg-types args :return return})
    (update-in result [:classes class-name]
               #(merge {:methods {}} %))))

(defn parse
  "Parse %requires text into target external declarations."
  [text]
  (let [ast (insta/parse requires-parser (or text ""))]
    (if (insta/failure? ast)
      (throw (ex-info (str "Invalid %requires section: "
                           (grammars/failure-in-text->string ast (or text "")))
                      {:text text}))
      (reduce add-entry {:classes {}} (entries ast)))))

(defn provided-types
  [requires]
  (set (keys (:classes requires))))

(defn method-spec
  [requires class-name method-name arity]
  (let [specs (get-in requires [:classes class-name :methods method-name])]
    (when (seq specs)
      (or (some (fn [spec]
                  (when (= arity (count (:args spec)))
                    spec))
                specs)
          (throw (ex-info (str class-name "::" method-name
                               " expected "
                               (sort (set (map #(count (:args %)) specs)))
                               " argument(s), got " arity)
                          {:class-name class-name
                           :method-name method-name
                           :expected (sort (set (map #(count (:args %)) specs)))
                           :got arity}))))))

(defn assert-no-duplicate-method-signatures!
  [requires]
  (doseq [[class-name {:keys [methods]}] (:classes requires)
          [method-name specs] methods]
    (when (not= (count specs)
                (count (set (map (juxt :args :return) specs))))
      (throw (ex-info (str "Duplicate %requires declaration for "
                           class-name "::" method-name)
                      {:class-name class-name :method-name method-name})))))

(defn assert-external-types!
  "Ensure every @ type is either an imported WCHNT handle or provided by Target."
  [schema-ir target-ir]
  (when (:host target-ir)
    (let [declared (or (:external-types schema-ir) #{})
        imported (or (:imported-handles schema-ir) #{})
        provided (or (:external-types target-ir) #{})
        collisions (clojure.set/intersection imported provided)
        missing (clojure.set/difference declared imported provided)]
      (when (seq collisions)
        (throw (ex-info (str "External type is both imported and Target-provided: "
                             (first (sort collisions)))
                        {:types collisions})))
      (when (seq missing)
        (throw (ex-info (str "Target does not provide external type(s): "
                             (clojure.string/join ", " (sort missing)))
                        {:types missing}))))))
