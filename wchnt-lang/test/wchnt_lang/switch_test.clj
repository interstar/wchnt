(ns wchnt-lang.switch-test
  "switch-on-value expressions.

   TDD contract: these tests define the target behaviour and are expected to
   FAIL until the grammar, reaction IR, Haxe emission, and interpreter support
   are implemented. Syntax (no outer braces; each branch is a block):

     switch (scrutinee)
       1 -> { value }
       2 -> { value }
       else -> { value }"
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [instaparse.core :as insta]
            [wchnt-lang.grammars :as grammars]
            [wchnt-lang.parser :as parser]
            [wchnt-lang.ast-to-ir :as ast-to-ir]
            [wchnt-lang.reaction :as reaction]
            [wchnt-lang.targets.haxe-backend :as haxe]
            [wchnt-lang.interpret :as interpret]))

(defn- parse-construction [s] (grammars/parse-construction s))

(defn- inner-expr
  "Unwrap [:BlockStatements [:Expression ...]] to the expression node."
  [block-ast]
  (let [expr (second block-ast)]
    (is (= :Expression (first expr)))
    (second expr)))

(defn- assert-parses
  [parse-fn s]
  (let [result (parse-fn s)]
    (is (not (insta/failure? result))
        (str "should parse: " (pr-str s) "\n" result))
    result))

(defn- schema-ir
  [schema-text]
  (let [cargo (parser/schema-wchnt->schema-ast schema-text)]
    (ast-to-ir/schema-ast-to-ir (:value cargo))))

(defn- methods-ir
  [schema-text reaction-text]
  (reaction/reaction-ast-to-ir (grammars/parse-reaction reaction-text)
                               (schema-ir schema-text)
                               {:bindings {} :main nil}))

;; ===========================================================================
;; Grammar
;; ===========================================================================

(deftest switch-parses
  (testing "literal patterns with a required else, no outer braces"
    (is (= :SwitchExpr
           (first (inner-expr
                   (assert-parses parse-construction
                                  "switch (key) 1 -> { 10 } 2 -> { 20 } else -> { 30 }"))))))

  (testing "string and bool patterns"
    (is (= :SwitchExpr
           (first (inner-expr
                   (assert-parses parse-construction
                                  "switch (s) \"a\" -> { 1 } \"b\" -> { 2 } else -> { 3 }")))))
    (is (= :SwitchExpr
           (first (inner-expr
                   (assert-parses parse-construction
                                  "switch (b) true -> { 1 } false -> { 2 } else -> { 3 }"))))))

  (testing "bare enum names are patterns"
    (is (= :SwitchExpr
           (first (inner-expr
                   (assert-parses parse-construction
                                  "switch (colour) Black -> { 1 } White -> { 2 } else -> { 3 }"))))))

  (testing "switch requires else"
    (is (insta/failure? (parse-construction "switch (key) 1 -> { 10 }"))))

  (testing "else is reserved and cannot be a branch pattern"
    (is (insta/failure? (parse-construction "switch (key) else -> { 10 } 2 -> { 20 } else -> { 30 }")))))

;; ===========================================================================
;; Reaction IR
;; ===========================================================================

(deftest switch-ir
  (testing "switch lowers to a :switch node with a joined result type"
    (let [m (first (methods-ir "Game = Int/key"
                               "Game::pick = { switch (key) 1 -> { 10 } 2 -> { 20 } else -> { 30 } }"))]
      (is (= :switch (get-in m [:body :expr])))
      (is (= :field (get-in m [:body :scrutinee :expr])))
      (is (= "Int" (get-in m [:body :type])))
      (is (= "Int" (:return-type m)))
      (is (= 1 (get-in m [:body :branches 0 :pattern :value])))
      (is (= 2 (get-in m [:body :branches 1 :pattern :value])))
      (is (= 30 (get-in m [:body :else :body :value]))))))

(deftest switch-enum-pattern
  (testing "a bare name pattern resolves to the enum constructor"
    (let [m (first (methods-ir "Colour = \"Black\" | \"White\"
Game = Colour/colour"
                               "Game::pick = { switch (colour) Black -> { 1 } White -> { 2 } else -> { 3 } }"))]
      (is (= :enum (get-in m [:body :branches 0 :pattern :expr])))
      (is (= "Black" (get-in m [:body :branches 0 :pattern :name])))
      (is (= "White" (get-in m [:body :branches 1 :pattern :name])))
      (is (= "Int" (get-in m [:body :type]))))))

(deftest switch-pattern-type-mismatch-fails
  (testing "a pattern whose type differs from the scrutinee is rejected"
    (is (thrown-with-msg? Exception #"switch"
                          (methods-ir "Game = Int/key"
                                      "Game::pick = { switch (key) \"a\" -> { 1 } else -> { 2 } }")))))

(deftest switch-branch-type-mismatch-fails
  (testing "branch results must share a type, like if"
    (is (thrown-with-msg? Exception #"same type"
                          (methods-ir "Game = Int/key"
                                      "Game::pick = { switch (key) 1 -> { 1 } else -> { true } }")))))

(deftest switch-param-in-later-branch-infers
  (testing "an untyped parameter used only in a later branch still infers"
    (let [m (first (methods-ir "Game = Int/x"
                               "Game::pick = { Int/n, p | switch (n) 1 -> { 10 } else -> { (p + 1) } }"))]
      (is (= [{:name "n" :type "Int"} {:name "p" :type "Int"}] (:parameters m))))))

;; ===========================================================================
;; Haxe emission
;; ===========================================================================

(deftest switch-haxe
  (testing "switch emits a native Haxe switch with a default"
    (let [m (first (methods-ir "Game = Int/key"
                               "Game::pick = { switch (key) 1 -> { 10 } 2 -> { 20 } else -> { 30 } }"))
          hx (haxe/generate-method m)]
      (is (str/includes? hx "switch (this.key)"))
      (is (str/includes? hx "case 1:"))
      (is (str/includes? hx "case 2:"))
      (is (str/includes? hx "default:")))))

;; ===========================================================================
;; Interpreter
;; ===========================================================================

(defn- switch-program
  []
  (interpret/load-program
   (str "# switch\n"
        "## Schema\n```\n"
        "Game = Int/score\n"
        "Colour = \"Black\" | \"White\" | \"Red\"\n"
        "```\n"
        "## Construction\n```\n"
        "[:Game 1]\n"
        "```\n"
        "## Methods\n```\n"
        "Game::pick = { switch (score) 1 -> { 10 } 2 -> { 20 } else -> { 30 } }\n"
        "Game::pickN = { Int/n | switch (n) 1 -> { 10 } 2 -> { 20 } else -> { 30 } }\n"
        "Game::paint = { Colour/colour | switch (colour) Black -> { 111 } White -> { 222 } else -> { 333 } }\n"
        "```\n"
        "## Target\n```\n%canvas\n\n%init\nfunction init() {}\n\n%step\nfunction step() {}\n```\n")))

(deftest switch-interpret
  (testing "switch selects the matching branch; else is the fallback"
    (let [{:keys [schema-ir methods-ir root]} (switch-program)]
      (is (= 10 (interpret/call schema-ir methods-ir root "pick" [])))
      (is (= 10 (interpret/call schema-ir methods-ir root "pickN" [1])))
      (is (= 20 (interpret/call schema-ir methods-ir root "pickN" [2])))
      (is (= 30 (interpret/call schema-ir methods-ir root "pickN" [0])))
      (is (= 30 (interpret/call schema-ir methods-ir root "pickN" [99])))))
  (testing "enum scrutinee matches enum patterns"
    (let [{:keys [schema-ir methods-ir root]} (switch-program)]
      (is (= 111 (interpret/call schema-ir methods-ir root "paint" ["Black"])))
      (is (= 222 (interpret/call schema-ir methods-ir root "paint" ["White"])))
      (is (= 333 (interpret/call schema-ir methods-ir root "paint" ["Red"]))))))
