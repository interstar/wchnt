(ns wchnt-lang.grammars
  (:require [instaparse.core :as insta]
            [instaparse.failure :as ifail]
            [clojure.string :as str]))

;; =============================================================================
;; Schema Grammar (for parsing schema definitions)
;; =============================================================================

(def schema-grammar
  "
Schema = DefLine (<NL> DefLine)* <NL>?
DefLine = EnumLine / DisjunctionLine / CompositionLine
CompositionLine = Definee Implements? <SPACE> <'='> <SPACE> Element (<SPACE> Element)* <SPACE>?
Implements = <SPACE> <':'> <SPACE> Name
DisjunctionLine = Definee <SPACE> <'='> <SPACE> Element (<SPACE> <'|'> <SPACE> Element)+ <SPACE>?
EnumLine = Definee <SPACE> <'='> <SPACE> <'\"'> EnumValue <'\"'> (<SPACE> <'|'> <SPACE> <'\"'> EnumValue <'\"'>)+ <SPACE>?
Definee = Inlet? Name
Inlet = '>'
<Name> = #'[A-Za-z][A-Za-z0-9_]*'
NL = #'\n+'
Element = (Sigil Type / TypeMarker) ('/' AltName)?
SPACE = #'\\s+'
TypeMarker = ArrayType / MapType / EmptyType / Name
ArrayType = <'['> (Type / MapType) <']'>
Type = Name
MapType =  <'{'> KeyType <SPACE>? <':'> <SPACE>? ValType <'}'>
KeyType = Name 
ValType = ArrayType / Name
AltName = Name
EnumValue =  #'[^\"]+'
Sigil = ':' / '@' / '$'
EmptyType = '_'
")

;; =============================================================================
;; Construction Grammar (for parsing construction and reaction phases)
;; =============================================================================

;; Construction/reaction is a PEG: / is ordered choice. Do not use | for
;; alternatives that share a prefix — JVM and CLJS Instaparse resolve | differently.
;; Brace forms: {Type:Type ...} map, then { args | body } lambda, then { stmts } block.

(def construction-grammar
  "Code = (MethodDefinition / WS)*
MethodDefinition = ClassName <'::'> MethodName <'='> BlockOrLambda ReturnAnn?
ReturnAnn = <'->'> Type
BlockOrLambda = Lambda / Block
Lambda = <'{'> LambdaArgs? <'|'> BlockStatements <'}'>
LambdaArgs = LambdaArg (<','> LambdaArg)*
ExternalLambdaArg = <'@'> Type <'/'> VariableName
TypedLambdaArg = Type <'/'> VariableName
LambdaArg = ExternalLambdaArg / TypedLambdaArg / VariableName
Block = <'{'> BlockStatements <'}'>
<Stmt> = Assignment / Expression
BlockStatements = (Stmt (StmtSep Stmt)*)?
<StmtSep> = <#'\\.\\s+'>
Assignment = VariableName <'='> Expression
TargetCommand = <'%'> TargetMethodName <'('> MethodArgList <')'>
TargetMethodName = Name
Expression = OrExpr
<OrExpr> = OrOp / AndExpr
OrOp = AndExpr (<'or'> AndExpr)+
<AndExpr> = AndOp / NotExpr
AndOp = NotExpr (<'and'> NotExpr)+
<NotExpr> = NotOp / CmpExpr
NotOp = <'not'> NotExpr
<CmpExpr> = CmpOp / ArithExpr
CmpOp = ArithExpr CompOp ArithExpr
<CompOp> = '<=' / '>=' / '==' / '!=' / '<' / '>'
<ArithExpr> = AddOp / Term
AddOp = Term (('+' / '-') Term)+
<Term> = MulOp / Factor
MulOp = Factor (('*' / '/' / '%') Factor)+
<Factor> = IfExpr
         / TargetCommand
         / MethodCall
         / NegOp
         / <'('> OrExpr <')'>
         / WithConstruction
         / ObjectConstruction
         / ArrayConstruction
         / MapConstruction
         / FieldPath
         / Literal
         / VariableRef
         / BlockOrLambda
ElseIfClause = <'else'> <'if'> <'('> OrExpr <')'> Block
ElsePart = ElseIfClause* <'else'> Block
IfExpr = <'if'> <'('> OrExpr <')'> Block ElsePart
NegOp = <'-'> Factor
ObjectConstruction = <'['> <':'> ClassName ArgList <']'> 
InnerObjectConstruction = <'['> (<':'> ClassName)? ArgList <']'>
WithConstruction = <'['> <':'> ClassName WithSource? <'|'> WithAssignList <']'>
WithSource = FieldPath / VariableRef
WithAssignList = WithAssign (<','>? WithAssign)*
WithAssign = WithPath <'='> Expression
<WithPath> = FieldPath / VariableRef
ArrayConstruction = <'['> <':'> <'Array'> <'/'> Type ArgList <']'>
MapConstruction = <'{'> KeyType <':'> ValType KeyValueList? <'}'>
MethodCall = (StringLiteral / IntLiteral / VariableRef) (<#'\\.'> Name)+ <'('> MethodArgList <')'> (<#'\\.'> Name <'('> MethodArgList <')'>)*
FieldPath = #'[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+'
VariableRef = Name
<ArgItem> = MethodCall
          / ArrayConstruction
          / MapConstruction
          / WithConstruction
          / InnerObjectConstruction
          / Literal
          / FieldPath
          / VariableRef
          / BlockOrLambda
          / ParenArg
ArgList = ArgItem*
<ParenArg> = <'('> OrExpr <')'>
MethodArgList = (MethodArgItem (<','> MethodArgItem)*)?
MethodArgItem = Expression
KeyValueList = KeyValuePair (<','>? WS* KeyValuePair)*
KeyValuePair = Expression (<':'>)? Expression
<Literal> = FloatLiteral / IntLiteral / StringLiteral / BoolLiteral
IntLiteral = #'(-)?[0-9]+'
FloatLiteral = #'(-)?[0-9]+\\.[0-9]+'
StringLiteral = <'\"'> #'[^\"]*' <'\"'>
BoolLiteral = 'true' / 'false'
ClassName = Name
MethodName = Name
VariableName = Name
Type = Name
KeyType = Name
ValType = Name
<Name> = #'[A-Za-z_][A-Za-z0-9_]*'
<WS> = <#'\\s+'>")

;; =============================================================================
;; Parser Instances
;; =============================================================================

(def schema-parser
  "Ready-to-use parser for schema definitions"
  (insta/parser schema-grammar))

(def construction-parser
  "Ready-to-use parser for construction and reaction phases"
  (insta/parser construction-grammar :auto-whitespace :standard))

;; =============================================================================
;; Convenience Functions
;; =============================================================================

(defn- expand-field-path
  "Turn a tight dotted token into [:FieldPath \"ball\" \"x\"]."
  [s]
  (into [:FieldPath] (str/split s #"\.")))

(defn- transform-construction-ast
  [ast]
  (if (insta/failure? ast)
    ast
    (insta/transform {:FieldPath expand-field-path} ast)))

(defn parse-schema [schema-text]
  "Parse schema text using the schema parser"
  (insta/parse schema-parser schema-text))

(defn parse-construction [construction-text]
  "Parse construction text using the construction parser"
  (transform-construction-ast
   (insta/parse construction-parser construction-text :start :BlockStatements)))

(defn parse-reaction [reaction-text]
  "Parse reaction methods using the same grammar, starting at Code"
  (transform-construction-ast
   (insta/parse construction-parser reaction-text :start :Code)))

(defn- regexp-pattern
  [r]
  #?(:clj (str r)
     :cljs (.-source r)))

(defn- token-label
  "Turn an instaparse expectation into a short human label."
  [item]
  (case (:tag item)
    :string (:expecting item)
    :regexp (let [p (regexp-pattern (:expecting item))]
              (cond
                (= p "[A-Za-z_][A-Za-z0-9_]*") "name"
                (= p "(-)?[0-9]+") "integer"
                (= p "(-)?[0-9]+\\.[0-9]+") "number"
                (= p "[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+") "field path"
                (= p "\\s+") "whitespace"
                :else (str "token matching /" p "/")))
    (str item)))

(defn- format-expected
  [reason]
  (when (seq reason)
    (let [labels (distinct (map token-label reason))
          n (count labels)]
      (if (<= n 8)
        (str "Expected one of: " (str/join ", " labels))
        (str "Expected an expression (e.g. "
             (str/join ", " (take 6 labels))
             ", …)")))))

(defn- parse-hint
  [text failure]
  (let [i (min (max (or (:index failure) 0) 0) (count text))
        after (subs text i (min (+ i 12) (count text)))
        before (subs text (max 0 (- i 8)) i)]
    (cond
      (and (re-find #"else\s*$" before)
           (re-matches #"if\s*\(.*" (str/triml after)))
      "Hint: else-if chains use 'else if (condition) { … }'."

      (and (>= i 0)
           (= \= (get text (dec i)))
           (re-matches #"^\s*$" after))
      "Hint: add an expression after = (e.g. x = player.x)."

      (and (re-find #"=\s*$" before)
           (re-matches #"^\s*\." after))
      "Hint: use '.' to end a statement, not after = on the same line."

      :else nil)))

(defn failure-in-text->string
  "Multi-line instaparse failure: line/column, snippet, caret, expected tokens."
  [failure text]
  (if (insta/failure? failure)
    (let [snippet (str/trim text)
          {:keys [index reason]} failure
          aug (when (seq snippet) (ifail/augment-failure failure snippet))
          line (:line aug)
          column (:column aug)
          line-text (:text aug)
          caret (when (and line-text column)
                  (ifail/marker line-text column))
          expected (format-expected reason)
          hint (when (seq snippet) (parse-hint snippet failure))
          loc (if (and line column)
                (str "line " line ", column " column)
                (str "index " index))]
      (str/join "\n"
                (remove nil?
                        [(str "at " loc)
                         expected
                         (when line-text (str "  " line-text))
                         (when caret (str "  " caret))
                         hint])))
    (str failure)))

(defn failure->string
  "Human-readable instaparse failure for error messages (JVM and CLJS)."
  ([failure]
   (failure-in-text->string failure ""))
  ([failure text]
   (failure-in-text->string failure text)))

(defn- parse-with-failure-handling [parse-fn text]
  (let [trimmed-text (clojure.string/trim text)]
    (try
      (let [result (parse-fn trimmed-text)]
        (if (insta/failure? result)
          {:success false :error (failure-in-text->string result trimmed-text)}
          {:success true :ast result}))
      (catch #?(:clj Exception :cljs :default) e
        {:success false :error (or (ex-message e) (str e))}))))

(defn parse-construction-with-failure-handling [construction-text]
  "Parse construction text with proper error handling"
  (parse-with-failure-handling parse-construction construction-text))

(defn parse-reaction-with-failure-handling [reaction-text]
  "Parse reaction text with proper error handling"
  (parse-with-failure-handling parse-reaction reaction-text))
