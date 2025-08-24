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
MethodDefinition = ClassName <'::'> MethodName <'='> BlockOrLambda
BlockOrLambda = Lambda | Block
Lambda = <'{'> LambdaArgs? <'|'> BlockStatements <'}'>
LambdaArgs = VariableName (<','> VariableName)*
Block = <'{'> BlockStatements <'}'>
BlockStatements = (Assignment | TargetCommand | Expression) (<'.'> WS* (Assignment | TargetCommand | Expression))*
Assignment = VariableName <'='> Expression
TargetCommand = <'%'> TargetMethodName <'('> MethodArgList <')'>
TargetMethodName = Name
Expression = BooleanExpr
           | ObjectConstruction
           | ArrayConstruction
           | MapConstruction
           | ArithmeticExpr
           | MethodCall
           | VariableRef
           | Literal
           | BlockOrLambda
ObjectConstruction = <'['> <':'> ClassName ArgList <']'> 
InnerObjectConstruction = <'['> (<':'> ClassName)? ArgList <']'>
ArrayConstruction = <'['> <':'> <'Array'> <'/'> Type ArgList <']'>
MapConstruction = <'['> <':'> <'Map'> <'/'> <'{'> KeyType <':'> ValType <'}'> KeyValueList <']'>
MethodCall = VariableRef <'.'> MethodName <'('> MethodArgList <')'> (<'.'> MethodName <'('> MethodArgList <')'>)*
VariableRef = Name | SelfName
SelfName = <'self.'> Name
BooleanExpr = 'not' BooleanExpr | BooleanFactor 'and' BooleanFactor | BooleanFactor 'or' BooleanFactor | BooleanFactor
BooleanFactor = <'('> Expression <')'> | VariableRef | BoolLiteral | BlockOrLambda
ArithmeticExpr = Term (('+' | '-') Term)*
Term = Factor (('*' | '/') Factor)*
Factor = <'('> Expression <')'> | VariableRef | Literal | BlockOrLambda
ArgList = (Literal | VariableRef | InnerObjectConstruction | ArrayConstruction | MapConstruction | BlockOrLambda)*
MethodArgList = (MethodArgItem (<','> MethodArgItem)*)?
MethodArgItem = Expression | BlockOrLambda
KeyValueList = KeyValuePair (<','>? WS* KeyValuePair)*
KeyValuePair = Expression (<':'>)? Expression
<Literal> = IntLiteral | FloatLiteral | StringLiteral | BoolLiteral
IntLiteral = #'[0-9]+'
FloatLiteral = #'[0-9]+\\.[0-9]+'
StringLiteral = <'\"'> #'[^\"]*' <'\"'>
BoolLiteral = 'true' | 'false'
ClassName = Name
MethodName = Name
VariableName = Name
Type = Name
KeyType = Name
ValType = Name
<Name> = #'[A-Za-z_][A-Za-z0-9_]*'
WS = <#'\\s+'>")

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

(defn parse-schema [schema-text]
  "Parse schema text using the schema parser"
  (insta/parse schema-parser schema-text))

(defn parse-construction [construction-text]
  "Parse construction text using the construction parser"
  (insta/parse construction-parser construction-text :start :BlockStatements))

(defn parse-construction-with-failure-handling [construction-text]
  "Parse construction text with proper error handling"
  (let [trimmed-text (clojure.string/trim construction-text)]
    (try
      (let [result (parse-construction trimmed-text)]
        (if (insta/failure? result)
          {:success false :error (insta/get-failure result)}
          {:success true :ast result}))
      (catch Exception e
        {:success false :error (.getMessage e)}))))
