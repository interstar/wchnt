(ns wchnt-lang.bitwise-test
  "Hex literals and bitwise operators (& | ^ ~ << >> >>>).

   TDD contract: these tests define the target behaviour and are expected to
   FAIL until the grammar, reaction IR, Haxe emission, and interpreter support
   are implemented. See doc/type-inference.md for the surrounding numeric rules."
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [instaparse.core :as insta]
            [wchnt-lang.grammars :as grammars]
            [wchnt-lang.parser :as parser]
            [wchnt-lang.ast-to-ir :as ast-to-ir]
            [wchnt-lang.reaction :as reaction]
            [wchnt-lang.targets.haxe-backend :as haxe]
            [wchnt-lang.interpret :as interpret]))

;; ---- grammar helpers ------------------------------------------------------

(defn- parse-construction [s] (grammars/parse-construction s))

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
    (is (= :Expression (first expr)))
    (second expr)))

;; ---- reaction helpers -----------------------------------------------------

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
;; Hex literals
;; ===========================================================================

(deftest hex-literal-parses
  (testing "0x integers are hex literals"
    (is (= [:IntLiteral "0xFF"]
           (inner-expr (assert-parses parse-construction "0xFF"))))
    (is (= [:IntLiteral "0xff"]
           (inner-expr (assert-parses parse-construction "0xff"))))
    (is (= [:IntLiteral "0x10"]
           (inner-expr (assert-parses parse-construction "0x10"))))
    (is (= [:IntLiteral "0xABCDEF"]
           (inner-expr (assert-parses parse-construction "0xABCDEF")))))

  (testing "0x with no digits fails"
    (is (insta/failure? (parse-construction "0x")))
    (doseq [malformed ["0xG" "0x1G" "0x12.3"]]
      (is (insta/failure? (parse-construction malformed))
          (str "malformed hex must not be partially accepted: " malformed))))

  (testing "hex literal is a valid constructor argument"
    (is (not (insta/failure? (parse-construction "[:C 0xFF]"))))))

(deftest hex-literal-ir
  (testing "0xFF lowers to Int 255"
    (let [m (first (methods-ir "Foo = Int/a" "Foo::c = { 0xFF }"))]
      (is (= :int (get-in m [:body :expr])))
      (is (= 255 (get-in m [:body :value])))))

  (testing "lowercase and multi-digit hex lower to the right Int"
    (is (= 255 (get-in (first (methods-ir "Foo = Int/a" "Foo::c = { 0xff }"))
                       [:body :value])))
    (is (= 16 (get-in (first (methods-ir "Foo = Int/a" "Foo::c = { 0x10 }"))
                       [:body :value])))
    (is (= 11259375
           (get-in (first (methods-ir "Foo = Int/a" "Foo::c = { 0xABCDEF }"))
                   [:body :value])))))

(deftest hex-literal-32-bit-range
  (testing "the largest unsigned 32-bit hex literal lowers to signed Int -1"
    (is (= -1
           (get-in (first (methods-ir "Foo = Int/a" "Foo::c = { 0xFFFFFFFF }"))
                   [:body :value]))))
  (testing "hex literals above the unsigned 32-bit range are rejected"
    (is (thrown? Exception
                 (methods-ir "Foo = Int/a" "Foo::c = { 0x100000000 }")))))

;; ===========================================================================
;; Bitwise operators: grammar
;; ===========================================================================

(deftest bitwise-operators-parse
  (testing "binary bitwise operators"
    (is (= [:BitAndOp [:VariableRef "a"] "&" [:VariableRef "b"]]
           (inner-expr (assert-parses parse-construction "a & b"))))
    (is (= [:BitOrOp [:VariableRef "a"] "|" [:VariableRef "b"]]
           (inner-expr (assert-parses parse-construction "a | b"))))
    (is (= [:BitXorOp [:VariableRef "a"] "^" [:VariableRef "b"]]
           (inner-expr (assert-parses parse-construction "a ^ b"))))
    (is (= [:ShiftOp [:VariableRef "a"] "<<" [:IntLiteral "2"]]
           (inner-expr (assert-parses parse-construction "a << 2"))))
    (is (= [:ShiftOp [:VariableRef "a"] ">>" [:IntLiteral "2"]]
           (inner-expr (assert-parses parse-construction "a >> 2"))))
    (is (= [:ShiftOp [:VariableRef "a"] ">>>" [:IntLiteral "2"]]
           (inner-expr (assert-parses parse-construction "a >>> 2"))))
    (is (= [:ShiftOp [:VariableRef "a"] "<<" [:IntLiteral "2"]
            ">>" [:IntLiteral "1"]]
           (inner-expr (assert-parses parse-construction "a << 2 >> 1")))))

  (testing "unary bitwise not"
    (is (= [:BitNotOp [:VariableRef "a"]]
           (inner-expr (assert-parses parse-construction "~a"))))))

(deftest bitwise-precedence
  (testing "& binds tighter than == (a & b == c is (a & b) == c)"
    (is (= [:CmpOp [:BitAndOp [:VariableRef "a"] "&" [:VariableRef "b"]] "==" [:VariableRef "c"]]
           (inner-expr (assert-parses parse-construction "a & b == c")))))

  (testing "& binds tighter than |"
    (is (= [:BitOrOp [:VariableRef "a"] "|" [:BitAndOp [:VariableRef "b"] "&" [:VariableRef "c"]]]
           (inner-expr (assert-parses parse-construction "a | b & c")))))

  (testing "+ binds tighter than <<"
    (is (= [:ShiftOp [:AddOp [:VariableRef "a"] "+" [:VariableRef "b"]] "<<" [:IntLiteral "2"]]
           (inner-expr (assert-parses parse-construction "a + b << 2")))))

  (testing "the shift right operand can contain an additive expression"
    (is (= [:ShiftOp [:VariableRef "a"] "<<"
            [:AddOp [:VariableRef "b"] "+" [:VariableRef "c"]]]
           (inner-expr (assert-parses parse-construction "a << b + c")))))

  (testing "unary bit-not binds more tightly than shifts"
    (is (= [:ShiftOp [:BitNotOp [:VariableRef "a"]] "<<" [:IntLiteral "2"]]
           (inner-expr (assert-parses parse-construction "~a << 2")))))

  (testing "bitwise precedence and grouping are explicit"
    (is (= [:BitOrOp [:VariableRef "a"] "|"
            [:BitXorOp [:VariableRef "b"] "^"
             [:BitAndOp [:VariableRef "c"] "&" [:VariableRef "d"]]]]
           (inner-expr (assert-parses parse-construction "a | b ^ c & d"))))
    (is (= [:BitAndOp [:BitOrOp [:VariableRef "a"] "|" [:VariableRef "b"]]
            "&" [:VariableRef "c"]]
           (inner-expr (assert-parses parse-construction "(a | b) & c")))))

  (testing "colour packing expression parses"
    (is (= [:BitOrOp
            [:ShiftOp [:VariableRef "a"] "<<" [:IntLiteral "24"]]
            "|"
            [:ShiftOp [:VariableRef "r"] "<<" [:IntLiteral "16"]]
            "|"
            [:ShiftOp [:VariableRef "g"] "<<" [:IntLiteral "8"]]
            "|"
            [:VariableRef "b"]]
           (inner-expr
            (assert-parses parse-construction
                           "(a << 24) | (r << 16) | (g << 8) | b"))))))

;; ===========================================================================
;; Bitwise operators: reaction IR and Haxe emission
;; ===========================================================================

(deftest bitwise-ir
  (testing "binary bitwise ops are :bitwise Int nodes"
    (let [m (first (methods-ir "Foo = Int/a Int/b" "Foo::c = { a & b }"))]
      (is (= :bitwise (get-in m [:body :expr])))
      (is (= "&" (get-in m [:body :op])))
      (is (= "Int" (get-in m [:body :type])))
      (is (= "Int" (:return-type m)))))

  (testing "each operator records its op"
    (doseq [[src op] [["a | b" "|"] ["a ^ b" "^"] ["a & b" "&"]
                      ["a << 2" "<<"] ["a >> 2" ">>"] ["a >>> 2" ">>>"]]]
      (let [m (first (methods-ir "Foo = Int/a Int/b" (str "Foo::c = { (" src ") }")))]
        (is (= :bitwise (get-in m [:body :expr])))
        (is (= op (get-in m [:body :op])))))))

(deftest bitwise-not-ir
  (testing "~a is a :bitnot Int node"
    (let [m (first (methods-ir "Foo = Int/a" "Foo::c = { ~a }"))]
      (is (= :bitnot (get-in m [:body :expr])))
      (is (= "Int" (get-in m [:body :type]))))))

(deftest bitwise-rejects-float
  (testing "all bitwise operators require Int operands on both sides"
    (doseq [[schema expression]
            [["Foo = Float/a Int/b" "a & b"]
             ["Foo = Int/a Float/b" "a | b"]
             ["Foo = Float/a Int/b" "a ^ b"]
             ["Foo = Float/a Int/b" "a << b"]
             ["Foo = Int/a Float/b" "a >> b"]
             ["Foo = Int/a Float/b" "a >>> b"]
             ["Foo = Float/a" "~a"]]]
      (is (thrown-with-msg? Exception #"Int"
                            (methods-ir schema (str "Foo::c = { (" expression ") }")))
          (str expression " must reject Float operands")))))

(deftest bitwise-haxe
  (testing "bitwise operators emit as Haxe operators on this-fields"
    (doseq [[src out] [["a & b" "this.a & this.b"]
                       ["a | b" "this.a | this.b"]
                       ["a ^ b" "this.a ^ this.b"]
                       ["a << 2" "this.a << 2"]
                       ["a >> 2" "this.a >> 2"]
                       ["a >>> 2" "this.a >>> 2"]
                       ["~a" "~this.a"]]]
      (let [m (first (methods-ir "Foo = Int/a Int/b" (str "Foo::c = { (" src ") }")))
            haxe (haxe/generate-method m)]
        (is (str/includes? haxe out) (str src " should emit " out)))))

  (testing "precedence that differs from Haxe/C is parenthesised"
    (let [m (first (methods-ir "Foo = Int/a Int/b Int/c" "Foo::c = { (a & b) == c }"))
          haxe (haxe/generate-method m)]
      (is (str/includes? haxe "(this.a & this.b) == this.c"))))

  (testing "nested bitwise operations preserve WCHNT grouping in generated Haxe"
    (let [m (first (methods-ir "Foo = Int/a Int/b Int/c Int/d"
                               "Foo::e = { (a | b ^ c & d) }"))
          generated (haxe/generate-method m)]
      (is (str/includes? generated
                         "this.a | (this.b ^ (this.c & this.d))")))))

;; ===========================================================================
;; Bitwise operators: interpreter evaluation
;; ===========================================================================

(defn- bitwise-program
  []
  (interpret/load-program
   (str "# bitwise\n"
        "## Schema\n```\n"
        "B = Int/x\n"
        "```\n## Construction\n```\n"
        "[:B 0]\n"
        "```\n## Methods\n```\n"
        "B::band = { Int/a, Int/b | a & b }\n"
        "B::bor = { Int/a, Int/b | a | b }\n"
        "B::bxor = { Int/a, Int/b | a ^ b }\n"
        "B::bnot = { Int/a | ~a }\n"
        "B::bshl = { Int/a, Int/b | a << b }\n"
        "B::bshlChain = { Int/a | a << 2 << 1 }\n"
        "B::bshr = { Int/a, Int/b | a >> b }\n"
        "B::bushr = { Int/a, Int/b | a >>> b }\n"
        "B::pack = { Int/r, Int/g, Int/b | (0xFF << 24) | (r << 16) | (g << 8) | b }\n"
        "B::red = { Int/c | (c >>> 16) & 0xFF }\n"
        "B::green = { Int/c | (c >>> 8) & 0xFF }\n"
        "B::blue = { Int/c | c & 0xFF }\n"
        "```\n")))

(deftest bitwise-interpret
  (testing "binary and unary bitwise operators evaluate"
    (let [{:keys [schema-ir methods-ir root]} (bitwise-program)]
      (is (= 1 (interpret/call schema-ir methods-ir root "band" [5 3])))
      (is (= 7 (interpret/call schema-ir methods-ir root "bor" [5 3])))
      (is (= 6 (interpret/call schema-ir methods-ir root "bxor" [5 3])))
      (is (= -6 (interpret/call schema-ir methods-ir root "bnot" [5])))
      (is (= 10 (interpret/call schema-ir methods-ir root "bshl" [5 1])))
      (is (= 24 (interpret/call schema-ir methods-ir root "bshlChain" [3])))
      (is (= 2 (interpret/call schema-ir methods-ir root "bshr" [5 1])))
      (is (= 4 (interpret/call schema-ir methods-ir root "bushr" [8 1])))))

  (testing "bitwise results use signed 32-bit two's-complement semantics"
    (let [{:keys [schema-ir methods-ir root]} (bitwise-program)]
      (is (= -2147483648 (interpret/call schema-ir methods-ir root "bshl" [1 31])))
      (is (= -1 (interpret/call schema-ir methods-ir root "bnot" [0])))
      (is (= 2147483647 (interpret/call schema-ir methods-ir root "bushr" [-1 1])))
      (is (= -1 (interpret/call schema-ir methods-ir root "bushr" [-1 0])))))

  (testing "shift counts use only their low five bits"
    (let [{:keys [schema-ir methods-ir root]} (bitwise-program)]
      (is (= 1 (interpret/call schema-ir methods-ir root "bshl" [1 32])))
      (is (= 2 (interpret/call schema-ir methods-ir root "bshl" [1 33])))
      (is (= -2147483648 (interpret/call schema-ir methods-ir root "bshl" [1 -1])))))

  (testing "colour packing and byte extraction round-trip"
    (let [{:keys [schema-ir methods-ir root]} (bitwise-program)
          packed (interpret/call schema-ir methods-ir root "pack" [255 128 60])]
      (is (= 255 (interpret/call schema-ir methods-ir root "red" [packed])))
      (is (= 128 (interpret/call schema-ir methods-ir root "green" [packed])))
      (is (= 60 (interpret/call schema-ir methods-ir root "blue" [packed]))))))
