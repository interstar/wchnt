(ns wchnt-lang.unparse
  "Unparse (pretty-print) Instaparse ASTs back to canonical WCHNT source.

   Inverse of the parsers in `wchnt-lang.grammars`. This re-pretty-prints: it
   does not preserve the author's original whitespace or comments. It is the
   keystone for structured / mobile editors (parse -> edit structure -> print)
   and lives in .cljc so both the JVM compiler side and the CLJS live shells
   share one canonical printer. See doc/mobile-design.md.

   Covers Construction (objects, arrays, maps, literals), Schema (compositions,
   disjunctions, enums, sigils, array/map types), and Methods/Code (blocks,
   assignments, if/else, boolean/arithmetic/comparison operators with
   precedence-aware parenthesization, method calls, lambdas, target commands)."
  (:require [clojure.string :as str]))

(declare unparse-node)

;; Wrap a nested object construction onto its own line once an arg list holds
;; this many object children, or once the single-line form gets this wide.
(def ^:private block-object-threshold 2)
(def ^:private block-width-threshold 72)

(defn- tag [node] (when (vector? node) (first node)))
(defn- children [node] (rest node))
(defn- indent [depth] (apply str (repeat depth "  ")))

(defn- object-node?
  [node]
  (contains? #{:ObjectConstruction :InnerObjectConstruction
               :WithConstruction :ArrayConstruction :MapConstruction}
             (tag node)))

(defn- find-child
  [node t]
  (first (filter #(and (vector? %) (= (tag %) t)) (children node))))

(defn- classname-of
  [node]
  (when-let [c (find-child node :ClassName)]
    (second c)))

(defn- arg-nodes
  [node]
  (children (find-child node :ArgList)))

(defn- count-object-args
  [args]
  (count (filter object-node? args)))

(defn- should-block?
  [args inline]
  (or (>= (count-object-args args) block-object-threshold)
      (> (count inline) block-width-threshold)))

(defn- inline-head
  [cname args]
  (str "[" (when cname (str ":" cname))
       (when (and cname (seq args)) " ")))

;; Expression precedence: the grammar drops parens (`<'('>`), so the unparser
;; re-inserts them by comparing a node's precedence to its parent's.
(def ^:private prec
  {:OrOp 1 :AndOp 2 :NotOp 3 :CmpOp 4 :BitOrOp 5 :BitXorOp 6
   :BitAndOp 7 :ShiftOp 8 :AddOp 9 :MulOp 10 :NegOp 11 :BitNotOp 11})

(defn- node-prec [node] (if (vector? node) (get prec (tag node) 100) 100))

(defn- effective
  "See through an :Expression wrapper to the operator node underneath."
  [node]
  (if (and (vector? node) (= (tag node) :Expression)) (first (children node)) node))

(defn- needs-arg-parens?
  "Construction ArgItem is atoms, constructions, calls, and ParenArg.
   Operator expressions and if-forms must be wrapped or they reparse as
   adjacent VariableRefs (if / else)."
  [node]
  (let [n (effective node)]
    (or (< (node-prec n) 100)
        (and (vector? n) (= (tag n) :IfExpr)))))

(defn- arg-str
  "Render a construction argument (object/array/map slot). The grammar only
   admits atoms, constructions, and parenthesized expressions here, so a bare
   operator expression must be wrapped."
  [node depth ctx]
  (let [s (unparse-node node depth ctx)]
    (if (needs-arg-parens? node) (str "(" s ")") s)))

(defn- emit-list
  "Render a bracketed construction from a pre-built `head` (already ending in a
   space when args follow the tag) and child `args`. Inline, or one arg per line
   when in data context and the arg list is large."
  [head args depth ctx]
  (let [inline (str head (str/join " " (map #(arg-str % depth ctx) args)) "]")]
    (if (and (= ctx :data) (should-block? args inline))
      (let [pad (indent (inc depth))
            h (str/replace head #" $" "")]
        (str h "\n"
             (str/join "\n" (map #(str pad (arg-str % (inc depth) ctx)) args))
             "]"))
      inline)))

(defn- unparse-object
  "An [:Class ...] / bare [...] construction."
  [node depth ctx]
  (let [args (arg-nodes node)]
    (emit-list (inline-head (classname-of node) args) args depth ctx)))

(defn- unparse-with-path
  [node]
  (case (tag node)
    :WithPath (unparse-with-path (first (children node)))
    :FieldPath (str/join "." (children node))
    :VariableRef (second node)
    (unparse-node node 0 :expr)))

(defn- unparse-with-assign
  [node depth]
  (str (unparse-with-path (second node)) " = "
       (unparse-node (nth node 2) depth :expr)))

(defn- unparse-with
  "[:Class | field = expr] / [:Class src | path = expr]."
  [node depth]
  (let [src (find-child node :WithSource)
        assigns (children (find-child node :WithAssignList))]
    (str "[:" (classname-of node)
         (when src (str " " (unparse-with-path (first (children src)))))
         " | "
         (str/join ", " (map #(unparse-with-assign % depth) assigns))
         "]")))

(defn- unparse-array
  "An [:Array/Type ...] construction."
  [node depth ctx]
  (let [t (second (find-child node :Type))
        args (children (find-child node :ArgList))]
    (emit-list (str "[:Array/" t (when (seq args) " ")) args depth ctx)))

(defn- kv-pair-str
  [pair depth ctx]
  (let [[k v] (children pair)]
    (str (arg-str k depth ctx) " " (arg-str v depth ctx))))

(defn- unparse-map-construction
  "A {Key:Val k v ...} construction."
  [node depth ctx]
  (let [k (second (find-child node :KeyType))
        v (second (find-child node :ValType))
        pairs (children (find-child node :KeyValueList))
        head (str "{" k ":" v (when (seq pairs) " "))]
    (if (and (= ctx :data) (> (count pairs) 1))
      (let [pad (indent (inc depth))]
        (str "{" k ":" v "\n"
             (str/join "\n" (map #(str pad (kv-pair-str % (inc depth) ctx)) pairs))
             "}"))
      (str head (str/join " " (map #(kv-pair-str % depth ctx) pairs)) "}"))))

;; --- expression operand parenthesization -----------------------------------

(defn- operand
  "Render an operand, wrapping it in parens when its own precedence is at or
   below the parent operator's — enough to reparse to the same tree."
  [node depth p]
  (let [s (unparse-node node depth :expr)]
    (if (<= (node-prec node) p) (str "(" s ")") s)))

(defn- join-operands
  "Implicit-operator infix (Or/And): operands joined by `sep`."
  [nodes depth p sep]
  (str/join sep (map #(operand % depth p) nodes)))

(defn- unparse-infix
  "Explicit-operator infix (Cmp/Add/Mul): children interleave operand nodes and
   operator strings."
  [node depth p]
  (str/join " " (map #(if (string? %) % (operand % depth p)) (children node))))

;; --- methods / code ---------------------------------------------------------

(defn- ends-safe-for-stmt-dot?
  "Statement separator is `.`. A following `name.method(...)` re-parses as a
   field/method chain unless the prior statement ends with ) ] }."
  [s]
  (boolean (re-find #"[)\\]}]$" s)))

(defn- unparse-assignment
  [node depth]
  (let [nm (second (find-child node :VariableName))
        rhs (unparse-node (find-child node :Expression) depth :expr)
        rhs' (if (ends-safe-for-stmt-dot? rhs) rhs (str "(" rhs ")"))]
    (str nm " = " rhs')))

(defn- block-multiline?
  [stmts]
  (or (> (count stmts) 1) (some #(= (tag %) :Assignment) stmts)))

(defn- unparse-block
  "A { ... } block: inline for a single simple expression, else one statement
   per line with `.` separators."
  [node depth]
  (let [stmts (children (find-child node :BlockStatements))
        n (count stmts)]
    (if (block-multiline? stmts)
      (let [pad (indent (inc depth))]
        (str "{\n"
             (str/join "\n"
                       (map-indexed
                        (fn [i s] (str pad (unparse-node s (inc depth) :expr)
                                       (when (< i (dec n)) ".")))
                        stmts))
             "\n" (indent depth) "}"))
      (str "{ " (unparse-node (first stmts) depth :expr) " }"))))

(defn- unparse-condclause
  [node depth]
  (str " (" (unparse-node (first (children node)) depth :expr) ") "
       (unparse-node (find-child node :Block) depth)))

(defn- unparse-elsepart
  [node depth]
  (let [clauses (filter #(and (vector? %) (= (tag %) :CondClause)) (children node))]
    (str (apply str (map #(unparse-condclause % depth) clauses))
         " else " (unparse-node (find-child node :Block) depth))))

(defn- unparse-if
  [node depth]
  (let [[cnd thn elsepart] (children node)]
    (str "if (" (unparse-node cnd depth :expr) ") "
         (unparse-node thn depth)
         (when elsepart (unparse-elsepart elsepart depth)))))

(defn- unparse-switch
  [node depth]
  (let [scrutinee (second node)
        branch-nodes (drop 2 node)
        branches (butlast branch-nodes)
        else-branch (last branch-nodes)
        branch-pad (indent (inc depth))]
    (str "switch (" (unparse-node scrutinee depth :expr) ")"
         (apply str
                (map (fn [branch]
                       (str "\n" branch-pad
                            (unparse-node (second branch) depth :expr)
                            " -> " (unparse-node (nth branch 2) depth)))
                     branches))
         "\n" branch-pad "else -> "
         (unparse-node (second else-branch) depth))))

(defn- unparse-method-call
  [node depth]
  (let [cs (children node)]
    (str (unparse-node (first cs) depth :expr)
         (apply str
                (map (fn [part]
                       (cond
                         (string? part) (str "." part)
                         (= :CallMethodName (first part))
                         (str "." (second part))
                         :else
                         (str "(" (unparse-node part depth :expr) ")")))
                     (rest cs))))))

(defn- unparse-method-def
  [node depth]
  (let [cls (second (find-child node :ClassName))
        m (second (find-child node :MethodName))
        ret (find-child node :ReturnAnn)
        bol (find-child node :BlockOrLambda)
        ret-type (when ret (second (find-child ret :Type)))]
    (str cls "::" m
         " = " (unparse-node (first (children bol)) depth)
         (when ret-type (str " -> " ret-type)))))

(defn- unparse-lambda-arg
  [node]
  (case (tag node)
    :ExternalLambdaArg (str "@" (second (find-child node :Type)) "/"
                            (second (find-child node :VariableName)))
    :TypedLambdaArg (str (second (find-child node :Type)) "/"
                         (second (find-child node :VariableName)))
    :VariableName (second node)
    :LambdaArg (unparse-lambda-arg (first (children node)))
    (throw (ex-info (str "unparse: bad lambda arg " (pr-str (tag node))) {:node node}))))

(defn- unparse-lambda
  [node depth]
  (let [args (find-child node :LambdaArgs)
        stmts (children (find-child node :BlockStatements))]
    (str "{ "
         (when args (str (str/join ", " (map unparse-lambda-arg (children args))) " "))
         "| " (str/join ". " (map #(unparse-node % depth :expr) stmts)) " }")))

(defn- unparse-target-command
  [node depth]
  (str "%" (second (find-child node :TargetMethodName))
       "(" (unparse-node (find-child node :MethodArgList) depth :expr) ")"))

(defn unparse-node
  "Render one AST node to WCHNT source at `depth` indentation. `ctx` is `:data`
   (top-level construction — wraps large object lists) or `:expr` (inside method
   bodies — object constructions stay inline)."
  ([node] (unparse-node node 0 :data))
  ([node depth] (unparse-node node depth :data))
  ([node depth ctx]
   (cond
     (string? node) node
     (not (vector? node)) (str node)
     :else
     (case (tag node)
       :BlockStatements (str/join ".\n" (map #(unparse-node % depth ctx) (children node)))
       :Expression (unparse-node (first (children node)) depth ctx)
       :ObjectConstruction (unparse-object node depth ctx)
       :InnerObjectConstruction (unparse-object node depth ctx)
       :WithConstruction (unparse-with node depth)
       :ArrayConstruction (unparse-array node depth ctx)
       :MapConstruction (unparse-map-construction node depth ctx)
       :Code (str/join "\n\n" (map #(unparse-node % depth :expr) (children node)))
       :MethodDefinition (unparse-method-def node depth)
       :BlockOrLambda (unparse-node (first (children node)) depth)
       :Block (unparse-block node depth)
       :Lambda (unparse-lambda node depth)
       :Assignment (unparse-assignment node depth)
       :IfExpr (unparse-if node depth)
       :SwitchExpr (unparse-switch node depth)
       :MethodCall (unparse-method-call node depth)
       :MethodArgList (str/join ", " (map #(unparse-node % depth :expr) (children node)))
       :MethodArgItem (unparse-node (first (children node)) depth :expr)
       :TargetCommand (unparse-target-command node depth)
       :OrOp (join-operands (children node) depth 1 " or ")
       :AndOp (join-operands (children node) depth 2 " and ")
       :NotOp (str "not " (operand (first (children node)) depth 3))
       :CmpOp (unparse-infix node depth 4)
       :BitOrOp (unparse-infix node depth 5)
       :BitXorOp (unparse-infix node depth 6)
       :BitAndOp (unparse-infix node depth 7)
       :ShiftOp (unparse-infix node depth 8)
       :AddOp (unparse-infix node depth 9)
       :MulOp (unparse-infix node depth 10)
       :NegOp (str "-" (operand (first (children node)) depth 11))
       :BitNotOp (str "~" (operand (first (children node)) depth 11))
       :ClassName (second node)
       :VariableName (second node)
       :IntLiteral (second node)
       :FloatLiteral (second node)
       :StringLiteral (str "\"" (second node) "\"")
       :BoolLiteral (second node)
       :VariableRef (second node)
       :FieldPath (str/join "." (children node))
       (throw (ex-info (str "unparse: unsupported node " (pr-str (tag node)))
                       {:node node}))))))

(defn unparse-construction
  "Canonical WCHNT source for a parsed construction AST (:BlockStatements root)."
  [ast]
  (unparse-node ast 0 :data))

(defn unparse-methods
  "Canonical WCHNT source for a parsed methods AST (:Code root)."
  [ast]
  (unparse-node ast 0 :expr))

;; ---------------------------------------------------------------------------
;; Schema
;; ---------------------------------------------------------------------------

(declare unparse-type-inner)

(defn- unparse-map-type
  [n]
  (let [k (second (find-child n :KeyType))
        vt (find-child n :ValType)
        v (second vt)
        vs (if (string? v) v (unparse-type-inner v))]
    (str "{" k ":" vs "}")))

(defn- unparse-type-inner
  "Type/ArrayType/MapType as it appears inside [] or {}."
  [n]
  (case (tag n)
    :Type (second n)
    :ArrayType (str "[" (unparse-type-inner (second n)) "]")
    :MapType (unparse-map-type n)
    (throw (ex-info (str "unparse-schema: bad inner type " (pr-str (tag n))) {:node n}))))

(defn- unparse-type-marker
  "The X in [:TypeMarker X]: a plain name, array, map, or empty type."
  [tm]
  (let [x (second tm)]
    (cond
      (string? x) x
      (= (tag x) :ArrayType) (str "[" (unparse-type-inner (second x)) "]")
      (= (tag x) :MapType) (unparse-map-type x)
      (= (tag x) :EmptyType) "_"
      :else (throw (ex-info (str "unparse-schema: bad type marker " (pr-str x)) {:node tm})))))

(defn- unparse-definee
  [node]
  (let [cs (children node)]
    (if (and (vector? (first cs)) (= (tag (first cs)) :Inlet))
      (str ">" (second cs))
      (first cs))))

(defn- unparse-element
  "An Element: optional sigil+type or a type marker, with an optional /AltName."
  [node]
  (let [cs (children node)
        alt (find-child node :AltName)
        head (if (= (tag (first cs)) :Sigil)
               (str (second (first cs)) (second (second cs)))
               (unparse-type-marker (first cs)))]
    (str head (when alt (str "/" (second alt))))))

(defn- unparse-composition-line
  [line]
  (let [cs (children line)
        definee (first (filter #(= (tag %) :Definee) cs))
        impl (find-child line :Implements)
        elements (filter #(= (tag %) :Element) cs)]
    (str (unparse-definee definee)
         (when impl (str " : " (second impl)))
         " = "
         (str/join " " (map unparse-element elements)))))

(defn- unparse-def-line
  [line joiner render]
  (str (unparse-definee (first (children line))) " = "
       (str/join joiner (map render (rest (children line))))))

(defn- unparse-schema-node
  [node]
  (case (tag node)
    :Schema (str/join "\n" (map unparse-schema-node (children node)))
    :DefLine (unparse-schema-node (first (children node)))
    :CompositionLine (unparse-composition-line node)
    :DisjunctionLine (unparse-def-line node " | " unparse-element)
    :EnumLine (unparse-def-line node " | " #(str "\"" (second %) "\""))
    (throw (ex-info (str "unparse-schema: unsupported node " (pr-str (tag node)))
                    {:node node}))))

(defn unparse-schema
  "Canonical WCHNT source for a parsed schema AST (:Schema root)."
  [ast]
  (unparse-schema-node ast))
