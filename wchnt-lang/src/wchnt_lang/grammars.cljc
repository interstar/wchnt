(ns wchnt-lang.grammars
  (:require [instaparse.core :as insta]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.lang Exception)))

;; =============================================================================
;; Schema Grammar (for parsing schema definitions)
;; =============================================================================

(def schema-grammar
  "
Schema = DefLine (<NL> DefLine)* <NL>?
DefLine = CompositionLine | DisjunctionLine | EnumLine
CompositionLine = Definee <SPACE> <'='> <SPACE> Element (<SPACE> Element)* <SPACE>?
DisjunctionLine = Definee <SPACE> <'='> <SPACE> Element (<SPACE> <'|'> <SPACE> Element)+ <SPACE>?
EnumLine = Definee <SPACE> <'='> <SPACE> <'\"'> EnumValue <'\"'> (<SPACE> <'|'> <SPACE> <'\"'> EnumValue <'\"'>)+ <SPACE>?
Definee = Name
<Name> = #'[A-Za-z][A-Za-z0-9_]*'
NL = #'\n+'
Element = ((Sigil Type) | TypeMarker) ('/' AltName)?
SPACE = #'\\s+'
TypeMarker = Name | ArrayType | MapType | EmptyType
ArrayType = <'['> (Type | MapType) <']'>
Type = Name
MapType =  <'{'> KeyType <SPACE>? <':'> <SPACE>? ValType <'}'>
KeyType = Name 
ValType = Name | ArrayType 
AltName = Name
EnumValue =  #'[^\"]+'
Sigil = ':'  | '@' | '$'
EmptyType = '_'
")

;; =============================================================================
;; Construction Grammar (for parsing construction and reaction phases)
;; =============================================================================

(def construction-grammar
  "Code = (MethodDefinition | WS)*
MethodDefinition = ClassName <'::'> MethodName ReturnAnn? <'='> BlockOrLambda
ReturnAnn = <':'> Type
BlockOrLambda = Lambda | Block
Lambda = <'{'> LambdaArgs? <'|'> BlockStatements <'}'>
LambdaArgs = LambdaArg (<','> LambdaArg)*
LambdaArg = Type <'/'> VariableName | VariableName
Block = <'{'> BlockStatements <'}'>
<Stmt> = Assignment / Expression
BlockStatements = (Stmt (StmtSep Stmt)*)?
<StmtSep> = <#'\\.\\s+'>
Assignment = VariableName <'='> Expression
TargetCommand = <'%'> TargetMethodName <'('> MethodArgList <')'>
TargetMethodName = Name
Expression = OrExpr
<OrExpr> = OrOp | AndExpr
OrOp = AndExpr (<'or'> AndExpr)+
<AndExpr> = AndOp | NotExpr
AndOp = NotExpr (<'and'> NotExpr)+
<NotExpr> = NotOp | CmpExpr
NotOp = <'not'> NotExpr
<CmpExpr> = CmpOp | ArithExpr
CmpOp = ArithExpr CompOp ArithExpr
<CompOp> = '==' | '!=' | '<=' | '>=' | '<' | '>'
<ArithExpr> = AddOp | Term
AddOp = Term (('+' | '-') Term)+
<Term> = MulOp | Factor
MulOp = Factor (('*' | '/' | '%') Factor)+
<Factor> = IfExpr
         / TargetCommand
         / MethodCall
         / NegOp
         / <'('> OrExpr <')'>
         / ObjectConstruction
         / ArrayConstruction
         / MapConstruction
         / FieldPath
         / Literal
         / VariableRef
         / BlockOrLambda
IfExpr = <'if'> <'('> OrExpr <')'> Block <'else'> Block
NegOp = <'-'> Factor
ObjectConstruction = <'['> <':'> ClassName ArgList <']'> 
InnerObjectConstruction = <'['> (<':'> ClassName)? ArgList <']'>
ArrayConstruction = <'['> <':'> <'Array'> <'/'> Type ArgList <']'>
MapConstruction = <'['> <':'> <'Map'> <'/'> <'{'> KeyType <':'> ValType <'}'> KeyValueList? <']'>
MethodCall = (StringLiteral | IntLiteral | VariableRef) (<#'\\.'> Name)+ <'('> MethodArgList <')'> (<#'\\.'> Name <'('> MethodArgList <')'>)*
FieldPath = #'[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+'
VariableRef = Name | SelfName
SelfName = <'self.'> Name
<ArgItem> = MethodCall
          / ArrayConstruction
          / MapConstruction
          / InnerObjectConstruction
          / Literal
          / FieldPath
          / VariableRef
          / BlockOrLambda
          / ParenArg
ArgList = ArgItem*
<ParenArg> = <'('> OrExpr <')'>
MethodArgList = (MethodArgItem (<','> MethodArgItem)*)?
MethodArgItem = Expression | BlockOrLambda
KeyValueList = KeyValuePair (<','>? WS* KeyValuePair)*
KeyValuePair = Expression (<':'>)? Expression
<Literal> = IntLiteral | FloatLiteral | StringLiteral | BoolLiteral
IntLiteral = #'(-)?[0-9]+'
FloatLiteral = #'(-)?[0-9]+\\.[0-9]+'
StringLiteral = <'\"'> #'[^\"]*' <'\"'>
BoolLiteral = 'true' | 'false'
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

(defn- parse-with-failure-handling [parse-fn text]
  (let [trimmed-text (clojure.string/trim text)]
    (try
      (let [result (parse-fn trimmed-text)]
        (if (insta/failure? result)
          {:success false :error (insta/get-failure result)}
          {:success true :ast result}))
      (catch Exception e
        {:success false :error (.getMessage e)}))))

(defn parse-construction-with-failure-handling [construction-text]
  "Parse construction text with proper error handling"
  (parse-with-failure-handling parse-construction construction-text))

(defn parse-reaction-with-failure-handling [reaction-text]
  "Parse reaction text with proper error handling"
  (parse-with-failure-handling parse-reaction reaction-text))
