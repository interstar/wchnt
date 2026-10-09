(ns wchnt-lang.targets.smalltalk-test
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [wchnt-lang.compiler :as compiler]
            [wchnt-lang.pipeline :as p]
            [wchnt-lang.schema :as schema]
            [wchnt-lang.targets.smalltalk :as smalltalk]))

(def smalltalk-example
  "## Schema\n\n```\nPoint = Int/x Int/y\n```\n\n## Target\n\n```\n%smalltalk\n```\n")

(deftest smalltalk-target-emits-validated-pharo-source-artifact
  (let [result (compiler/compile smalltalk-example)]
    (is (p/is-cargo? result))
    (is (:success result) (pr-str (:errors result)))
    (let [artifact (:value result)
          source (get-in artifact [:outputs 0 :content])]
      (is (schema/valid-backend-artifact? artifact))
      (is (= :smalltalk (:backend artifact)))
      (is (= "WCHNTGenerated.st" (get-in artifact [:outputs 0 :name])))
      (is (= :pharo (get-in artifact [:payload :dialect])))
      (is (= ["Point"] (get-in artifact [:metadata :class-names])))
      (is (str/starts-with? source "\"VERSION:1.0\"!"))
      (is (str/includes? source "Object subclass: #Point"))
      (is (str/includes? source "instanceVariableNames: 'x y'"))
      (is (str/includes? source "package: 'WCHNT-Generated'!"))
      (is (str/includes? source "Point class\n\tinstanceVariableNames: ''!"))
      (is (str/includes? source "!Point methodsFor: 'WCHNT' stamp: 'WCHNT' prior: 0!"))
      (is (str/includes? source "^ x! !"))
      (is (= 1 (count (:warnings artifact)))))))

(deftest smalltalk-prefix-renames-generated-classes-and-references
  (let [source "## Schema

```
Time = Int/t
Game = Time/time
```

## Construction

```
[:Game [:Time 7]]
```

## Methods

```
Game::label = { \"Time\" }
```

## Target

```
%smalltalk
%prefix WCHNT
%main
WCHNTGameAssemblage new main.
```
"
        result (compiler/compile source)]
    (is (:success result) (pr-str (:errors result)))
    (let [artifact (:value result)
          generated (get-in artifact [:outputs 0 :content])]
      (is (str/includes? generated "Object subclass: #WCHNTTime"))
      (is (str/includes? generated "Object subclass: #WCHNTGame"))
      (is (str/includes? generated "!WCHNTGame methodsFor:"))
      (is (str/includes? generated "WCHNTTime new"))
      (is (str/includes? generated "WCHNTGameAssemblage new main"))
      (is (str/includes? generated "'Time'"))
      (is (= ["WCHNTTime" "WCHNTGame"]
             (get-in artifact [:metadata :class-names]))))))

(deftest smalltalk-float-construction-uses-lowercase-exponent-marker
  (let [source "## Schema

```
Measure = Float/value
```

## Construction

```
[:Measure 0.0001]
```

## Target

```
%smalltalk
```
"
        result (compiler/compile source)]
    (is (:success result) (pr-str (:errors result)))
    (is (str/includes? (get-in result [:value :outputs 0 :content])
                       "value: 1.0e-4"))))

(deftest smalltalk-target-accepts-native-init-step-and-rejects-mixed-lifecycle
  (let [target (smalltalk/parse-target
                "%smalltalk\n\n%init\n| game |\ngame := GameAssemblage new factory.\n%step\ngame := game step.")]
    (is (= "| game |\ngame := GameAssemblage new factory."
           (get-in target [:init :source])))
    (is (= "game := game step." (get-in target [:step :source]))))
  (is (thrown-with-msg? Exception #"either %main or %init/%step"
                        (smalltalk/parse-target
                         "%smalltalk\n%main\nself start.\n%init\nself prepare.\n%step\nself tick.")))
  (is (thrown-with-msg? Exception #"requires both native Smalltalk %init and %step"
                        (smalltalk/parse-target
                         "%smalltalk\n%init\nself prepare."))))

(deftest smalltalk-ispy-example-emits-model-methods-factory-and-console-loop
  (let [result (compiler/compile (slurp "smalltalk-examples/ispy.wcn"))]
    (is (:success result) (pr-str (:errors result)))
    (let [artifact (:value result)
          source (get-in artifact [:outputs 0 :content])]
      (is (schema/valid-backend-artifact? artifact))
      (is (str/includes? source "!Game methodsFor: 'WCHNT' stamp: 'WCHNT' prior: 0!"))
      (is (not (str/includes? source "compile: 'guess: line")))
      (is (str/includes? source "Yes!! You found the {name}!!"))
      (is (str/includes? source "guess: line"))
      (is (str/includes? source "factoryWithMaths: maths"))
      (is (str/includes? source "WCHNTConsole new"))
      (is (str/includes? source "UIManager default request:"))
      (is (not (str/includes? source "GameAssemblage new main!")))
      (is (empty? (:warnings artifact))))))

(deftest smalltalk-bounce-emits-morphic-graphics-host
  (let [result (compiler/compile (slurp "smalltalk-examples/bounce.wcn"))]
    (is (:success result) (pr-str (:errors result)))
    (let [artifact (:value result)
          source (get-in artifact [:outputs 0 :content])
          graphics-class (str/index-of source "Morph subclass: #WCHNTGraphics")
          first-method (str/index-of source "!Game methodsFor:")]
      (is (schema/valid-backend-artifact? artifact))
      (is (str/starts-with? source "\"VERSION:1.0\"!"))
      (is (< graphics-class first-method))
      (is (str/includes? source "drawRect: x y: y width: width height: height"))
      (is (str/includes? source "drawCircle: x y: y radius: radius"))
      (is (str/includes? source "ifTrue: [(self dx negated)]"))
      (is (not (str/includes? source "ifTrue: [(-self dx)]")))
      (is (str/includes? source "aCanvas translateBy: self bounds origin"))
      (is (str/includes? source "clippingTo: self bounds"))
      (is (str/includes? source "Morph subclass: #WCHNTGraphics"))
      (is (str/includes? source "Object subclass: #WCHNTInput"))
      (is (str/includes? source "keyDown: aKey\n    ^ keys includes: aKey"))
      (is (str/includes? source "stepBlock: aBlock"))
      (is (str/includes? source "wchntGraphics openInWindow."))
      (is (str/includes? source "wchntGraphics openInWindow.\n    wchntInput focus."))
      (is (str/includes? source "wchntGraphics game: game."))
      (is (str/includes? source "^ ("))
      (is (empty? (:warnings artifact))))))

(deftest smalltalk-butterfly-emits-input-and-context-support
  (let [result (compiler/compile (slurp "smalltalk-examples/butterfly.wcn"))]
    (is (:success result) (pr-str (:errors result)))
    (let [artifact (:value result)
          source (get-in artifact [:outputs 0 :content])]
      (is (= ["Inbox"] (get-in result [:stash :schema-ir :mailbox-classes])))
      (is (schema/valid-backend-artifact? artifact))
      (is (str/includes? source "thePaint"))
      (is (str/includes? source "inject: aMouseX with: aMouseY"))
      (is (str/includes? source "inject: wchntInput mouseX with: wchntInput mouseY"))
      (is (str/includes? source "Dictionary newFrom:"))
      (is (str/includes? source "16rFF000000"))
      (is (not (str/includes? source "0xFF000000")))
      (is (str/includes? source "colourToInt) at: self colour"))
      (is (str/includes? source "!Inbox methodsFor: 'WCHNT' stamp: 'WCHNT' prior: 0!\ninject: aMouseX with: aMouseY"))
      (is (str/includes? source "alphaFromInteger: anInteger"))
      (is (str/includes? source "asAlphaBlendingCanvas: alpha"))
      (is (str/includes? source "beginFill: aColor alpha: anAlpha"))
      (is (str/includes? source "handlesMouseDown: anEvent\n    ^ true"))
      (is (str/includes? source "handlesMouseOver: anEvent\n    ^ true"))
      (is (str/includes? source "handlesKeyboard: anEvent\n    ^ true"))
      (is (not (str/includes? source "handlesMouseDown\n")))
      (is (str/includes? source "main\n    | wchntGraphics wchntInput wchntMaths paint |"))
      (is (str/includes? source "paint := PaintAssemblage new factory."))
      (is (str/includes? source "paint canvas paint: wchntGraphics."))
      (is (not (str/includes? source "wchntGraphics clear.")))
      (is (str/includes? source
                         "wchntConstructed := (Paint new canvas: self canvas; brush: nextBrush; palette: self palette; inbox: self inbox; yourself)."))
      (is (str/includes? source
                         "(wchntConstructed brush) thePaint: wchntConstructed."))
      (is (str/includes? source "keyPresses"))
      (is (empty? (:warnings artifact))))))

(deftest smalltalk-lander-emits-nested-conditional-expressions
  (let [result (compiler/compile (slurp "smalltalk-examples/lander.wcn"))]
    (is (:success result) (pr-str (:errors result)))
    (let [artifact (:value result)
          source (get-in artifact [:outputs 0 :content])]
      (is (schema/valid-backend-artifact? artifact))
      (is (str/includes? source "!WCHNTGame methodsFor: 'WCHNT' stamp: 'WCHNT' prior: 0!"))
      (is (str/includes? source "ifTrue:"))
      (is (str/includes? source "ifFalse:"))
      (is (str/includes? source "Array with:"))
      (is (str/includes? source "into: [ :b :i | | nx ny |"))
      (is (str/includes? source "(WCHNTRuntime new) times: 12 applying:"))
      (is (not (str/includes? source "12 times:")))
      (is (str/includes? source "Object subclass: #WCHNTTime"))
      (is (str/includes? source "!WCHNTGameAssemblage methodsFor:"))
      (is (str/includes? source "WCHNTGameAssemblage new factoryWithMaths:"))
      (is (str/includes? source "lineStyle: 2 color: 11184810"))
      (is (str/includes? source "lineStyle: 4 color: 5232783"))
      (is (not (str/includes? source "lineStyle: 2 with:")))
      (is (str/includes? source "drawLine: (s x1) y: (s y1) x2: (s x2) y2: (s y2)"))
      (is (not (str/includes? source "drawLine: (s x1) y1:")))
      (is (str/includes? source "drawingCanvas line: (shape at: 2) to: (shape at: 3) width: (shape at: 5) color: (shape at: 4)"))
      (is (not (str/includes? source "drawingCanvas line: (shape at: 2) To:")))
      (is (str/includes? source "drawingCanvas drawPolygon: (shape at: 2) color: (shape at: 3) borderWidth: 0 borderColor: (shape at: 3)"))
      (is (str/includes? source "1.0e-4"))
      (is (not (str/includes? source "1.0E-4")))
      (is (str/includes? source "rfuel := (self maths round: self fuel)"))
      (is (not (str/includes? source "self maths rounded")))
      (is (str/includes? source "lineTo: rightX y: rightY) lineTo: noseX y: noseY) endFill"))
      (is (str/includes? source "lineColor := Color black"))
      (is (not (str/includes? source "self color: Color black")))
      (is (empty? (:warnings artifact))))))

(deftest smalltalk-mutating-method-rewires-replaced-context-child
  (let [source "## Schema

```
Game = :Ship Int/ticks
Ship = Int/x
```

## Construction

```
[:Game [:Ship 0] 0]
```

## Methods

```
Game::update! = { [:Game [:Ship (ship.x + 1)] (ticks + 1)] }
```

## Target

```
%smalltalk
%main
self start.
```
"
        result (compiler/compile source)]
    (is (:success result) (pr-str (:errors result)))
    (let [generated (get-in result [:value :outputs 0 :content])]
      (is (str/includes? generated "updateMutates"))
      (is (str/includes? generated "(ship) theGame: self.")))))
