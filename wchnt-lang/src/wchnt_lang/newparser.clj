(ns wchnt-lang.newparser
  (:require [instaparse.core :as insta]
            [clojure.java.io :as io]))

(def wchnt-grammar
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
ObjectConstruction = <'['> <':'> ClassName ArgList ']' 
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
ArgList = (ArgItem)*
ArgItem = Literal | VariableRef | InnerObjectConstruction | ArrayConstruction | MapConstruction | BlockOrLambda
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

(defn get-wchnt-parser []
  (insta/parser wchnt-grammar :auto-whitespace :standard)) 