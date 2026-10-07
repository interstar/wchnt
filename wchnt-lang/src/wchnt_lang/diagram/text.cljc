(ns wchnt-lang.diagram.text
  "Box text for class-diagram nodes.

   A line is a vector of segments {:class css-class :text string}. The CSS
   classes are the editor's syntax-colour classes (cm-wchnt-*), so a box reads
   like highlighted WCHNT. Layout uses these lines for sizing and the SVG
   renderer draws them, so the two always agree."
  (:require [clojure.string :as str]))

(def methods-failed-text "methods failed to compile")

(defn- seg
  [css-class text]
  {:class css-class :text text})

(defn- punct
  [text]
  (seg "wd-punct" text))

(defn- stereotype-line
  [stereotypes]
  (when (seq stereotypes)
    [(seg "wd-stereotype" (str "«" (str/join ", " stereotypes) "»"))]))

(defn- name-class
  [{:keys [mailbox?]}]
  (if mailbox? "cm-wchnt-rel-mailbox" "cm-wchnt-class"))

(defn header-lines
  "Stereotype line (when any) followed by the class name."
  [node]
  (->> [(stereotype-line (:stereotypes node))
        [(seg (name-class node) (:id node))]]
       (remove nil?)
       vec))

(defn- typed-name
  [{:keys [name type]}]
  [(seg "cm-wchnt-name" name) (punct ": ") (seg "cm-wchnt-type" type)])

(defn- param-segments
  [params]
  (->> params
       (map typed-name)
       (interpose [(punct ", ")])
       (apply concat)))

(defn- method-line
  [{:keys [name params return static?]}]
  (vec (concat (when static? [(seg "cm-wchnt-keyword" "static ")])
               [(seg "cm-wchnt-method" name) (punct "(")]
               (param-segments params)
               [(punct ") -> ") (seg "cm-wchnt-type" return)])))

(defn- method-lines
  [methods]
  (if (= :failed methods)
    [[(seg "wd-failed" methods-failed-text)]]
    (mapv method-line methods)))

(defn- enum-line
  [value]
  [(seg "cm-wchnt-string" (str "\"" value "\""))])

(defn body-sections
  "Compartments below the header, each a vector of lines.
   Classes: fields then methods. Interfaces: methods. Enums: values."
  [node]
  (case (:kind node)
    :class [(mapv typed-name (:fields node)) (method-lines (:methods node))]
    :interface [(method-lines (:methods node))]
    :enum [(mapv enum-line (:values node))]
    :external []
    :imported-interface []))

(defn line-text
  [line]
  (apply str (map :text line)))

(defn line-length
  [line]
  (count (line-text line)))
