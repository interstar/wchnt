(ns wchnt-lang.factory-params-test
  "Factory arguments from free names in @ Construction slots."
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [wchnt-lang.compiler :as compiler]
            [wchnt-lang.interpret :as interpret]
            [wchnt-lang.pipeline :as p]))

(defn- page
  [schema construction methods]
  (str "# factory args\n## Schema\n```\n" schema
       "\n```\n## Construction\n```\n" construction
       "\n```\n## Methods\n```\n" methods
       "\n```\n## Target\n```\n%terminal\n\n%requires\nPen\n\n%main\n"
       "public static function main():Void {}\n```\n"))

(def pen-schema
  "Pen = String/ink
Sketch = String/name @Pen
Board = @Pen Sketch")

(defn- compile-ir
  [schema construction methods]
  (compiler/compile-to-ir (page schema construction methods)))

(defn- factory-params
  [cargo]
  (get-in cargo [:stash :construction-ir :factory-params]))

(deftest factory-params-empty-without-externals
  (let [cargo (compile-ir "Box = String/title"
                          "[:Box \"hi\"]"
                          "Box::ok = { title }")]
    (is (:success cargo) (first (:errors cargo)))
    (is (= [] (factory-params cargo)))))

(deftest free-name-in-at-slot-is-factory-param
  (let [cargo (compile-ir pen-schema
                          "[:Sketch \"star\" pen]"
                          "Sketch::caption = { name }")]
    (is (:success cargo) (first (:errors cargo)))
    (is (= [{:name "pen" :type "Pen"}] (factory-params cargo)))))

(deftest first-appearance-parent-before-child
  (testing "[:Board e [:Sketch \"x\" d]] yields e then d, not flatten order"
    (let [cargo (compile-ir pen-schema
                            "[:Board e [:Sketch \"x\" d]]"
                            "Board::ok = { sketch.name }")]
      (is (:success cargo) (first (:errors cargo)))
      (is (= [{:name "e" :type "Pen"} {:name "d" :type "Pen"}]
             (factory-params cargo))))))

(deftest same-name-is-one-parameter
  (let [cargo (compile-ir pen-schema
                          "[:Board d [:Sketch \"x\" d]]"
                          "Board::ok = { sketch.name }")]
    (is (:success cargo) (first (:errors cargo)))
    (is (= [{:name "d" :type "Pen"}] (factory-params cargo)))))

(deftest let-then-final-keeps-source-order
  (let [cargo (compile-ir pen-schema
                          "s = [:Sketch \"x\" d].\n[:Board e s]"
                          "Board::ok = { sketch.name }")]
    (is (:success cargo) (first (:errors cargo)))
    (is (= [{:name "d" :type "Pen"} {:name "e" :type "Pen"}]
           (factory-params cargo)))))

(deftest type-clash-on-reused-name-fails
  (is (not (:success
            (compile-ir (str pen-schema "\nHost = String/id\nPair = @Pen @Host")
                        "[:Pair x x]"
                        "Pair::ok = { 1 }")))))

(deftest construct-in-external-slot-fails
  (let [cargo (compile-ir pen-schema
                          "[:Sketch \"star\" [:Pen \"blue\"]]"
                          "Sketch::caption = { name }")]
    (is (not (:success cargo)))
    (is (re-find #"external|factory parameter|@" (or (first (:errors cargo)) "")))))

(deftest free-name-in-ordinary-slot-fails
  (let [cargo (compile-ir "Box = String/title"
                          "[:Box mystery]"
                          "Box::ok = { title }")]
    (is (not (:success cargo)))))

(deftest haxe-factory-takes-params
  (let [cargo (compiler/compile
               (page pen-schema
                     "[:Board e [:Sketch \"x\" d]]"
                     "Board::ok = { sketch.name }"))]
    (is (:success cargo) (first (:errors cargo)))
    (let [factory (get-in cargo [:stash :construction-haxe])]
      (is (str/includes? factory "factory(e: Pen, d: Pen)"))
      (is (not (str/includes? factory "factory()"))))))

(deftest interpret-binds-factory-args
  (let [src (page pen-schema
                  "[:Sketch \"star\" pen]"
                  "Sketch::caption = { name.concat(\" \").concat(pen.ink) }")
        cargo (compiler/compile-to-ir src)
        schema-ir (get-in cargo [:stash :schema-ir])
        methods-ir (get-in cargo [:stash :methods-ir])
        construction-ir (get-in cargo [:stash :construction-ir])
        pen {:wchnt/class "Pen" :ink "blue"}
        root (interpret/construct schema-ir construction-ir methods-ir [pen])]
    (is (= "star" (interpret/get-field root "name")))
    (is (= "blue" (interpret/get-field (interpret/get-field root "pen") "ink")))
    (is (= "star blue" (interpret/call schema-ir methods-ir root "caption" [])))))

(deftest construct-object-builds-schema-class
  (let [cargo (compile-ir pen-schema
                          "[:Sketch \"star\" pen]"
                          "Sketch::caption = { name }")
        schema-ir (get-in cargo [:stash :schema-ir])
        pen (interpret/construct-object schema-ir "Pen" ["gold"])]
    (is (= "Pen" (:wchnt/class pen)))
    (is (= "gold" (:ink pen)))))

(deftest load-program-skips-heap-when-factory-args-needed
  (let [src (page pen-schema
                  "[:Sketch \"star\" pen]"
                  "Sketch::caption = { name }")
        loaded (interpret/load-program src)]
    (is (nil? (:root loaded)))
    (is (= [{:name "pen" :type "Pen"}]
           (get-in loaded [:construction-ir :factory-params])))))
