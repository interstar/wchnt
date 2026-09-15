(ns wchnt-lang.origin-test
  "Origin pattern (Patternflow port) compiles and builds lit cells."
  (:require [clojure.test :refer :all]
            [wchnt-lang.compiler :as compiler]
            [wchnt-lang.interpret :as interpret]
            [wchnt-lang.host :as host]
            [wchnt-lang.pipeline :as p]))

(deftest origin-canvas-compiles-to-ir
  (let [cargo (compiler/compile-to-ir (slurp "live-examples/origin_canvas.wcn"))]
    (is (:success cargo) (str (first (:errors cargo))))
    (is (= "canvas" (get-in cargo [:stash :target-ir :host])))
    (is (= [{:name "maths" :type "WCHNTMaths"}]
           (get-in cargo [:stash :construction-ir :factory-params])))))

(deftest origin-canvas-haxe-rejects-canvas-host
  (let [haxe (compiler/compile (slurp "live-examples/origin_canvas.wcn"))]
    (is (not (:success haxe)))
    (is (re-find #"%canvas" (or (first (:errors haxe)) "")))))

(deftest origin-builds-colored-cells
  (let [text (slurp "live-examples/origin_canvas.wcn")
        p (interpret/load-program text)
        root (interpret/construct (:schema-ir p) (:construction-ir p)
                                  (:methods-ir p) [(host/make-maths)])
        schema (:schema-ir p)
        methods (:methods-ir p)]
    (interpret/inject schema methods (interpret/get-field root "pointer")
                      [0.3 0.5 false])
    (let [cells (interpret/call schema methods root "cells" [])]
      (is (pos? (count cells)))
      (is (every? #(and (contains? % :x) (contains? % :rgb) (pos? (:rgb %)))
                  cells)))))

(deftest float-literal-in-construction
  (let [src (str "# f\n## Schema\n```\nP = Float/x\n```\n## Construction\n```\n"
                 "[:P 1.5]\n```\n## Methods\n```\nP::id = { x }\n```\n"
                 "## Target\n```\n%terminal\n\n%main\n"
                 "public static function main():Void {}\n```\n")
        cargo (compiler/compile-to-ir src)]
    (is (:success cargo) (str (first (:errors cargo))))
    (let [p (interpret/load-program src)
          root (:root p)]
      (is (= 1.5 (interpret/get-field root "x"))))))

(deftest times-on-let-bound-int
  (let [src (str "# t\n## Schema\n```\nBox = Int/x\n```\n## Construction\n```\n"
                 "[:Box 1]\n```\n## Methods\n```\n"
                 "Box::zeros = { n = (3). n.times({ i | 0 }) }\n```\n"
                 "## Target\n```\n%terminal\n\n%main\n"
                 "public static function main():Void {}\n```\n")
        cargo (compiler/compile-to-ir src)]
    (is (:success cargo) (str (first (:errors cargo))))
    (let [p (interpret/load-program src)
          zs (interpret/call (:schema-ir p) (:methods-ir p) (:root p) "zeros" [])]
      (is (= [0 0 0] zs)))))
