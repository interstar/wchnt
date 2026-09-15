(ns wchnt-lang.grammar-test
  (:require [clojure.test :refer :all]
            [wchnt-lang.grammars :as grammars]
            [instaparse.core :as insta]))

(defn- parse-construction
  [s]
  (grammars/parse-construction s))

(defn- parse-reaction
  [s]
  (grammars/parse-reaction s))

(defn- assert-parses
  [parse-fn s]
  (let [result (parse-fn s)]
    (is (not (insta/failure? result))
        (str "should parse: " (pr-str s) "\n" result))
    result))

(defn- inner-expr
  "Unwrap [:BlockStatements [:Expression ...]] to the expression node."
  [block-ast]
  (let [expr (second block-ast)]
    (is (= :Expression (first expr)) (str "expected Expression, got " (pr-str expr)))
    (second expr)))

(deftest test-grammar-literals
  (testing "literals and object construction still parse"
    (is (not (insta/failure? (parse-construction "42"))))
    (is (not (insta/failure? (parse-construction "[:Rect 0 0 800 600]"))))
    (is (not (insta/failure? (parse-construction "[:Game [:PlayArea [:Rect 0 0 800 600]] [:Ball 100 100 1 1 5]]"))))))

(deftest arithmetic-expressions
  (testing "addition and multiplication"
    (is (= [:AddOp [:IntLiteral "1"] "+" [:IntLiteral "2"]]
           (inner-expr (assert-parses parse-construction "1 + 2"))))
    (is (= [:MulOp [:VariableRef "width"] "*" [:VariableRef "height"]]
           (inner-expr (assert-parses parse-construction "width * height")))))

  (testing "* binds tighter than +"
    (is (= [:AddOp [:IntLiteral "1"] "+" [:MulOp [:IntLiteral "2"] "*" [:IntLiteral "3"]]]
           (inner-expr (assert-parses parse-construction "1 + 2 * 3")))))

  (testing "parentheses regroup"
    (is (= [:MulOp [:AddOp [:IntLiteral "1"] "+" [:IntLiteral "2"]] "*" [:IntLiteral "3"]]
           (inner-expr (assert-parses parse-construction "(1 + 2) * 3")))))

  (testing "% is modulo, same precedence as * and /"
    (is (= [:MulOp [:VariableRef "x"] "%" [:VariableRef "width"]]
           (inner-expr (assert-parses parse-construction "x % width"))))
    (is (= [:AddOp [:IntLiteral "1"] "+" [:MulOp [:IntLiteral "2"] "%" [:IntLiteral "3"]]]
           (inner-expr (assert-parses parse-construction "1 + 2 % 3")))))

  (testing "field arithmetic used in reaction bodies"
    (is (= [:AddOp [:VariableRef "x"] "+" [:VariableRef "dx"]]
           (inner-expr (assert-parses parse-construction "x + dx"))))))

(deftest boolean-and-comparison-expressions
  (testing "not, and, or"
    (is (= [:NotOp [:BoolLiteral "true"]]
           (inner-expr (assert-parses parse-construction "not true"))))
    (is (= [:AndOp [:BoolLiteral "true"] [:BoolLiteral "false"]]
           (inner-expr (assert-parses parse-construction "true and false"))))
    (is (= [:OrOp [:VariableRef "a"] [:VariableRef "b"]]
           (inner-expr (assert-parses parse-construction "a or b")))))

  (testing "and binds tighter than or"
    (is (= [:OrOp [:VariableRef "a"] [:AndOp [:VariableRef "b"] [:VariableRef "c"]]]
           (inner-expr (assert-parses parse-construction "a or b and c")))))

  (testing "comparisons"
    (is (= [:CmpOp [:VariableRef "x"] ">" [:IntLiteral "0"]]
           (inner-expr (assert-parses parse-construction "x > 0"))))
    (is (= [:CmpOp [:VariableRef "x"] "<=" [:VariableRef "width"]]
           (inner-expr (assert-parses parse-construction "x <= width"))))
    (is (= [:CmpOp [:VariableRef "a"] "==" [:VariableRef "b"]]
           (inner-expr (assert-parses parse-construction "a == b")))))

  (testing "arithmetic inside comparisons, comparisons inside and"
    (is (= [:CmpOp [:AddOp [:VariableRef "x"] "+" [:VariableRef "dx"]] ">" [:IntLiteral "0"]]
           (inner-expr (assert-parses parse-construction "x + dx > 0"))))
    (is (= [:AndOp [:CmpOp [:VariableRef "x"] ">" [:IntLiteral "0"]]
                  [:CmpOp [:VariableRef "y"] "<" [:VariableRef "height"]]]
           (inner-expr (assert-parses parse-construction "x > 0 and y < height"))))))

(defn- construction-arg-list
  [block-ast]
  (let [obj (inner-expr block-ast)]
    (is (= :ObjectConstruction (first obj)))
    (last obj)))

(deftest parenthesized-exprs-in-construction-args
  (testing "constructor args can be parenthesized arithmetic"
    (let [arg-list (construction-arg-list
                    (assert-parses parse-construction "[:Ball (x + dx) (y + dy) dx dy rad]"))]
      (is (= :ArgList (first arg-list)))
      (is (= [:AddOp [:VariableRef "x"] "+" [:VariableRef "dx"]] (nth arg-list 1)))
      (is (= [:AddOp [:VariableRef "y"] "+" [:VariableRef "dy"]] (nth arg-list 2)))
      (is (= [:VariableRef "dx"] (nth arg-list 3)))))

  (testing "nested construction with a multiplied field"
    (let [arg-list (construction-arg-list
                    (assert-parses parse-construction "[:Rect x y (width * 2) height]"))]
      (is (= [:MulOp [:VariableRef "width"] "*" [:IntLiteral "2"]] (nth arg-list 3))))))

(defn- method-body-expr
  [method-ast]
  (let [block-or-lambda (nth method-ast 3)
        block (second block-or-lambda)
        statements (second block)]
    (inner-expr statements)))

(deftest reaction-method-arithmetic
  (testing "Rect::area body is width * height"
    (let [ast (assert-parses parse-reaction "Rect::area = { width * height }")
          method (second ast)]
      (is (= :Code (first ast)))
      (is (= :MethodDefinition (first method)))
      (is (= [:ClassName "Rect"] (second method)))
      (is (= [:MethodName "area"] (nth method 2)))
      (is (= [:MulOp [:VariableRef "width"] "*" [:VariableRef "height"]]
             (method-body-expr method)))))

  (testing "method body can construct with arithmetic args"
    (is (not (insta/failure?
              (parse-reaction "Rect::doubleWidth = { [:Rect x y (width * 2) height] }")))))

  (testing "lambda args for a method"
    (is (not (insta/failure?
              (parse-reaction "Booster::boost = {y | (x * y)}"))))))

(deftest return-annotation-is-postfix
  (testing "return type sits after the block as -> Type, not before ="
    (let [ast (assert-parses parse-reaction "Shape::step = { Rect/bounds | } -> Shape")
          method (second ast)]
      (is (= [:ClassName "Shape"] (second method)))
      (is (= [:MethodName "step"] (nth method 2)))
      (is (= :BlockOrLambda (first (nth method 3))))
      (is (= [:ReturnAnn [:Type "Shape"]] (nth method 4))))
    (is (insta/failure? (parse-reaction "Shape::step : Shape = { Rect/bounds | }")))
    (is (not (insta/failure?
              (parse-reaction "Circle::draw = { @Graphics/g | g.endFill() } -> Void"))))))

(deftest field-paths
  (testing "dotted field access parses as FieldPath, not a method call"
    (is (= [:FieldPath "ball" "x"]
           (inner-expr (assert-parses parse-construction "ball.x"))))
    (is (= [:FieldPath "playArea" "rect" "width"]
           (inner-expr (assert-parses parse-construction "playArea.rect.width")))))

  (testing "method calls still require parentheses"
    (is (= :MethodCall
           (first (inner-expr (assert-parses parse-construction "ball.move()")))))
    (is (= :MethodCall
           (first (inner-expr (assert-parses parse-construction "this.move()")))))
    (is (= :MethodCall
           (first (inner-expr (assert-parses parse-construction "playArea.rect.area()"))))))

  (testing "dot plus whitespace is a statement separator, not a path"
    (let [ast (assert-parses parse-construction "nx = x + dx. ny = y + dy")]
      (is (= :Assignment (first (second ast))))
      (is (= :Assignment (first (nth ast 2)))))
    (let [ast (assert-parses parse-construction "ball.x. playArea.rect.width")]
      (is (= [:FieldPath "ball" "x"] (second (second ast))))
      (is (= [:FieldPath "playArea" "rect" "width"] (second (nth ast 2)))))))

(deftest if-expression-parses
  (testing "C-like if/else is an expression"
    (is (= :IfExpr
           (first (inner-expr (assert-parses parse-construction
                                             "if (dx < 0) { 0 - dx } else { dx }"))))))

  (testing "if without else does not parse"
    (is (insta/failure?
         (parse-construction "if (dx < 0) { dx }")))))

(deftest unary-minus-parses
  (testing "-dx is a prefix negation"
    (is (= [:NegOp [:VariableRef "dx"]]
           (inner-expr (assert-parses parse-construction "-dx"))))))

(deftest collection-method-blocks-parse
  (testing "map takes a lambda argument"
    (is (= :MethodCall
           (first (inner-expr (assert-parses parse-construction
                                             "players.map({ p | p.name })")))))))

(deftest string-method-on-literal-parses
  (testing "a string literal can be a call receiver"
    (is (= :MethodCall
           (first (inner-expr (assert-parses parse-construction
                                             "\"Ada\".concat(\":\")")))))))

(deftest tpl-map-arg-parses
  (testing "tpl takes a brace map, not a block, including field-path values"
    (let [call (inner-expr (assert-parses parse-construction
                                          "\"You are in {place}.\".tpl({String:String \"place\": room.description})"))]
      (is (= :MethodCall (first call)))
      (is (some #{:MapConstruction}
                (map first (tree-seq vector? rest call)))))))

(deftest cons-and-put-parse
  (testing "cons and put are ordinary method calls"
    (is (= :MethodCall
           (first (inner-expr (assert-parses parse-construction
                                             "players.cons(p)")))))
    (is (= :MethodCall
           (first (inner-expr (assert-parses parse-construction
                                             "scores.put(n, s)")))))))

(deftest empty-collection-literals-parse
  (testing "empty array and map constructions parse"
    (is (= :ArrayConstruction
           (first (inner-expr (assert-parses parse-construction
                                             "[:Array/Player]")))))
    (is (= :MapConstruction
           (first (inner-expr (assert-parses parse-construction
                                             "{String:Int}")))))))

(deftest brace-map-literals-parse
  (testing "{Key:Val ...} is the map literal"
    (is (= :MapConstruction
           (first (inner-expr (assert-parses parse-construction
                                             "{String:Int \"Ada\":3 \"Cy\":5}")))))
    (is (= :MapConstruction
           (first (inner-expr (assert-parses parse-construction
                                             "{String:Int \"Ada\" 3}")))))
    (let [arg-list (construction-arg-list
                    (assert-parses parse-construction
                                   "[:Team name [:Array/Player] {String:Int}]"))]
      (is (= :MapConstruction (first (nth arg-list 3))))))

  (testing "the old [:Map/{Key:Val} ...] tag does not parse"
    (is (insta/failure? (parse-construction "[:Map/{String:Int}]"))))

  (testing "braces that are blocks or lambdas are not stolen as maps"
    (is (= :Lambda
           (first (second (inner-expr (assert-parses parse-construction
                                                     "{ i | i }"))))))
    (is (not (insta/failure?
              (parse-reaction
               "Game::pick = { if (n == 0) { 10 } else { 20 } }"))))))

(deftest method-call-in-construction-args-parses
  (testing "cons and put can be construction arguments"
    (let [arg-list (construction-arg-list
                    (assert-parses parse-construction
                                   "[:Team name players.cons(p) scores.put(n, s)]"))]
      (is (= :MethodCall (first (nth arg-list 2))))
      (is (= :MethodCall (first (nth arg-list 3)))))))

(deftest head-tail-get-remove-parse
  (testing "head tail get remove substring times parse as calls"
    (is (= :MethodCall (first (inner-expr (assert-parses parse-construction "players.head()")))))
    (is (= :MethodCall (first (inner-expr (assert-parses parse-construction "players.tail()")))))
    (is (= :MethodCall (first (inner-expr (assert-parses parse-construction "scores.get(n)")))))
    (is (= :MethodCall (first (inner-expr (assert-parses parse-construction "scores.get(n, 0)")))))
    (is (= :MethodCall (first (inner-expr (assert-parses parse-construction "scores.exists(n)")))))
    (is (= :MethodCall (first (inner-expr (assert-parses parse-construction "scores.remove(n)")))))
    (is (= :MethodCall (first (inner-expr (assert-parses parse-construction "name.substring(0, 1)")))))
    (is (= :MethodCall (first (inner-expr (assert-parses parse-construction "3.times({ i | i })")))))))

(deftest target-call-is-an-expression
  (testing "%trace(x) parses as a TargetCommand expression"
    (is (= :TargetCommand
           (first (inner-expr (assert-parses parse-construction "%trace(width)")))))
    (let [ast (assert-parses parse-reaction "Rect::area = { %trace(width * height) }")
          method (second ast)]
      (is (= :TargetCommand (first (method-body-expr method))))))

  (testing "%trace argument can itself use modulo"
    (is (= :TargetCommand
           (first (inner-expr (assert-parses parse-construction "%trace(x % width)")))))))

(deftest schema-inlet-definee
  (testing ">Keys parses as an Inlet on the definee"
    (let [ast (grammars/parse-schema ">Keys = Bool/left Bool/right\n")]
      (is (not (insta/failure? ast)))
      (is (some #{:Inlet}
                (map first (tree-seq vector? rest ast)))))))

(deftest schema-implements-clause
  (testing "Pentagon : Shape = ... records an Implements node"
    (let [ast (grammars/parse-schema "Pentagon : Shape = Int/x Int/y Int/side Int/dx\n")]
      (is (not (insta/failure? ast)) (pr-str ast))
      (is (some #(and (vector? %) (= :Implements (first %)) (= "Shape" (second %)))
                (tree-seq vector? rest ast))))))

(deftest schema-delegate-sigil
  (testing "+BasePerson is a Sigil on a composition element"
    (let [ast (grammars/parse-schema "Student = String/id +BasePerson\n")]
      (is (not (insta/failure? ast)) (pr-str ast))
      (is (some #(and (vector? %) (= :Sigil (first %)) (= "+" (second %)))
                (tree-seq vector? rest ast))))))

(deftest with-construction-parses
  (testing "[:Class | field = expr] is a WithConstruction, not positional args"
    (let [node (inner-expr (assert-parses parse-construction "[:Ball | x = 1]"))]
      (is (= :WithConstruction (first node)))
      (is (= [:ClassName "Ball"] (second node)))
      (is (= :WithAssignList (first (nth node 2))))))

  (testing "optional source before | and dotted write-paths"
    (let [node (inner-expr (assert-parses parse-construction
                                         "[:Ball ball | x = nx, y = ny]"))]
      (is (= :WithConstruction (first node)))
      (is (some #(and (vector? %) (= :VariableRef (first %)) (= "ball" (second %)))
                (tree-seq vector? rest node))))
    (let [node (inner-expr (assert-parses parse-construction
                                         "[:Game | playArea.rect.width = 800]"))]
      (is (= :WithConstruction (first node)))
      (is (some #(and (vector? %) (= :FieldPath (first %)) (= ["playArea" "rect" "width"] (rest %)))
                (tree-seq vector? rest node)))))

  (testing "positional constructions still parse"
    (is (= :ObjectConstruction
           (first (inner-expr (assert-parses parse-construction "[:Ball 1 2 3 4 5]")))))
    (is (not (insta/failure?
              (parse-reaction "Rect::doubleWidth = { [:Rect | width = (width * 2)] }"))))))

(deftest parse-failure-is-readable
  (testing "failure-in-text->string reports line, snippet, and expected tokens"
    (let [text "Game::f = { foo = }"
          result (grammars/parse-reaction-with-failure-handling text)]
      (is (false? (:success result)))
      (is (string? (:error result)))
      (is (re-find #"at line 1, column" (:error result)))
      (is (re-find #"Expected" (:error result)))
      (is (re-find #"Game::f = \{ foo = \}" (:error result)))
      (is (re-find #"\^" (:error result))))))
