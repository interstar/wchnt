(ns wchnt-lang.unparse-test
  "Round-trip and formatting tests for the construction/schema/methods unparser."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [wchnt-lang.grammars :as g]
            [wchnt-lang.mainfile :as mf]
            [wchnt-lang.unparse :as u]))

(defn- reparse
  "Parse -> unparse -> parse. Structural round-trip guarantees correctness
   without pinning exact formatting."
  [text]
  (g/parse-construction (u/unparse-construction (g/parse-construction text))))

(deftest roundtrip-preserves-structure
  (testing "unparsed source reparses to the same AST"
    (doseq [text ["[:Ball 1 2 3]"
                  "[:P 1.5 2.0 3]"
                  "[:S \"hello world\"]"
                  "[:B true false]"
                  "[:PlayArea [0 0 800 600]]"
                  "[:Game [:PlayArea [0 0 800 600]] [:Ball 200 150 6 5 16]]"]]
      (is (= (g/parse-construction text) (reparse text))
          (str "round-trip: " text)))))

(deftest canonical-formatting-bounce
  (testing "nested constructions wrap; leaf lists stay inline"
    (let [ast (g/parse-construction
               "[:Game [:PlayArea [0 0 800 600]] [:Ball 200 150 6 5 16]]")]
      (is (= (str "[:Game\n"
                  "  [:PlayArea [0 0 800 600]]\n"
                  "  [:Ball 200 150 6 5 16]]")
             (u/unparse-construction ast))))))

(deftest leaf-list-inline
  (testing "a single simple nested list stays on one line"
    (let [ast (g/parse-construction "[:PlayArea [0 0 800 600]]")]
      (is (= "[:PlayArea [0 0 800 600]]" (u/unparse-construction ast))))))

;; --- Array / Map constructions ---------------------------------------------

(deftest array-map-roundtrip
  (testing "array and map constructions survive parse -> unparse -> parse"
    (doseq [text ["[:Array/Shape [:Circle 1 2 3 4] [:Square 5 6 7 8]]"
                  "[:Array/Int 1 2 3]"
                  "{Int:Shape 1 [:Circle 1 2 3 4]}"
                  "{Int:Shape 1 [:Circle 1 2 3 4] 2 [:Square 5 6 7 8]}"
                  "{String:Int}"
                  "[:Game [:PlayArea [0 0 800 600]] [:Array/Shape [:Circle 1 2 3 4]]]"]]
      (is (= (g/parse-construction text) (reparse text))
          (str "array/map round-trip: " text)))))

;; --- Schema ----------------------------------------------------------------

(defn- reparse-schema
  [text]
  (g/parse-schema (u/unparse-schema (g/parse-schema text))))

(deftest schema-roundtrip
  (testing "every schema feature survives parse -> unparse -> parse"
    (doseq [text ["Ball = Int/x Int/y Int/rad"
                  "Game = PlayArea [Shape]/shapes $Time @WCHNTGraphics/g"
                  ">Input = Bool/left Bool/right"
                  "Shape = Circle | Square | Triangle"
                  "Dir = \"North\" | \"South\""
                  "Grid = {Int:Shape}/cells"
                  "Cache = [{Int:Shape}]/rows"
                  "Ball = :Team/team"
                  "Student = String/id +BasePerson"
                  "Nothing = _"
                  "Pentagon : Shape = Int/x Int/y Int/side Int/dx"]]
      (is (= (g/parse-schema text) (reparse-schema text))
          (str "schema round-trip: " text)))))

(deftest schema-multiline-roundtrip
  (testing "a full multi-line schema round-trips"
    (let [text (str "Game = PlayArea Ball\n"
                    "PlayArea = Rect\n"
                    "Rect = Int/x Int/y Int/width Int/height\n"
                    "Ball = Int/x Int/y Int/dx Int/dy Int/rad")]
      (is (= (g/parse-schema text) (reparse-schema text)))
      (is (= text (u/unparse-schema (g/parse-schema text)))))))

;; --- Methods ---------------------------------------------------------------

(defn- reparse-methods
  [text]
  (g/parse-reaction (u/unparse-methods (g/parse-reaction text))))

(deftest methods-roundtrip
  (testing "method bodies survive parse -> unparse -> parse"
    (doseq [text ["Game::a = { ball.dx }"
                  "Game::b = { -ball.dx }"
                  "Game::c = { ball.x + 1 } -> Int"
                  "Game::d = { if (ball.x < r.x) { -ball.dx } else { ball.dx } }"
                  "Game::pick = { if (a < 0) { 1 } (a == 0) { 2 } (a > 0) { 3 } else { 4 } }"
                  "Game::e = { r = playArea.rect. r.x + r.width }"
                  "Game::f = { this.bounceDx() }"
                  "Game::g = { (a and b) or (not c) }"
                  "Rect::doubleWidth = { [:Rect | width = width * 2] }"
                  "Game::widen = { [:Game | playArea.rect.width = 800] }"
                  "Game::nudge = { [:Ball ball | x = nx] }"
                  "Square::nudge = { [:Square (if (nx < minX) { x } else { nx }) size] }"
                  ;; Statement `.` after a field/mul must not glue into `n.times`
                  "Game::cells = { n = rows * cols. n.times({ i | i }) }"]]
      (is (= (g/parse-reaction text) (reparse-methods text))
          (str "methods round-trip: " text)))))

(deftest methods-real-seed-roundtrip
  (testing "the full bounce.wcn methods block round-trips"
    (let [text (str "Game::bounceDx = {\n"
                    "  r = playArea.rect.\n"
                    "  if ((ball.x < r.x) or (ball.x > (r.x + r.width))) { -ball.dx } else { ball.dx }\n"
                    "}\n\n"
                    "Game::step = {\n"
                    "  ndx = this.bounceDx().\n"
                    "  [:Game playArea [:Ball (ball.x + ndx) ndx ball.rad]]\n"
                    "}")]
      (is (= (g/parse-reaction text) (reparse-methods text))))))

(deftest precedence-parens-preserved
  (testing "parens needed for precedence are re-inserted"
    (let [ast (g/parse-reaction "Game::f = { (ball.x + r.width) * 2 }")
          out (u/unparse-methods ast)]
      (is (str/includes? out "(ball.x + r.width) * 2"))
      (is (= ast (g/parse-reaction out))))))

(deftest precedence-redundant-parens-dropped
  (testing "parens the grammar doesn't need are dropped but reparse is stable"
    (let [ast (g/parse-reaction "Game::f = { ball.x > (r.x + r.width) }")
          out (u/unparse-methods ast)]
      (is (str/includes? out "ball.x > r.x + r.width"))
      (is (not (str/includes? out "(r.x")))
      (is (= ast (g/parse-reaction out))))))

;; --- Real seed files: the strongest round-trip evidence --------------------

(def ^:private section->unparse
  {"schema" [g/parse-schema u/unparse-schema]
   "construction" [g/parse-construction u/unparse-construction]
   "methods" [g/parse-reaction u/unparse-methods]})

(defn- seed-files []
  (->> (file-seq (io/file "live-examples"))
       (filter #(str/ends-with? (.getName %) ".wcn"))
       (sort-by #(.getName %))))

(deftest seed-files-roundtrip
  (testing "every schema/construction/methods block in live-examples round-trips"
    (is (seq (seed-files)) "live-examples/ should contain .wcn files")
    (doseq [f (seed-files)
            [section code] (mf/extract-code-blocks (slurp f))
            :let [[parse unparse] (section->unparse section)]
            :when (and parse (not (str/blank? code)))]
      (let [ast (parse code)]
        (is (= ast (parse (unparse ast)))
            (str (.getName f) " / " section " round-trips"))))))
