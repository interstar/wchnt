(ns wchnt-lang.origin-test
  "Regression tests for construction and collection behavior."
  (:require [clojure.test :refer :all]
            [wchnt-lang.compiler :as compiler]
            [wchnt-lang.interpret :as interpret]))

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
