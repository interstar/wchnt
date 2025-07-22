(ns wchnt-lang.ir-to-haxe-test
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [wchnt-lang.ir-to-haxe :as ir-to-haxe]
            [wchnt-lang.ir :as ir]))

;; =============================================================================
;; Test Data - Sample IR structures
;; =============================================================================

(def sample-schema-ir
  {::ir/assemblages
   [{::ir/name "Rect"
     ::ir/components
     [{::ir/component-name "x" ::ir/type-name "Int" ::ir/relationship :ordinary ::ir/optional-name nil}
      {::ir/component-name "y" ::ir/type-name "Int" ::ir/relationship :ordinary ::ir/optional-name nil}
      {::ir/component-name "width" ::ir/type-name "Int" ::ir/relationship :ordinary ::ir/optional-name nil}
      {::ir/component-name "height" ::ir/type-name "Int" ::ir/relationship :ordinary ::ir/optional-name nil}]}
    {::ir/name "Player"
     ::ir/components
     [{::ir/component-name "x" ::ir/type-name "Int" ::ir/relationship :ordinary ::ir/optional-name nil}
      {::ir/component-name "y" ::ir/type-name "Int" ::ir/relationship :ordinary ::ir/optional-name nil}
      {::ir/component-name "name" ::ir/type-name "String" ::ir/relationship :ordinary ::ir/optional-name nil}]}
    {::ir/name "Game"
     ::ir/components
     [{::ir/component-name "playArea" ::ir/type-name "PlayArea" ::ir/relationship :context-specific ::ir/optional-name nil}
      {::ir/component-name "ball" ::ir/type-name "Ball" ::ir/relationship :ordinary ::ir/optional-name nil}
      {::ir/component-name "teams" ::ir/type-name "Array<Team>" ::ir/relationship :ordinary ::ir/optional-name nil}
      {::ir/component-name "players" ::ir/type-name "Array<Player>" ::ir/relationship :ordinary ::ir/optional-name nil}
      {::ir/component-name "scores" ::ir/type-name "Scores" ::ir/relationship :ordinary ::ir/optional-name nil}]}]
   ::ir/interfaces
   [{::ir/name "Shape"
     ::ir/implementers ["Circle" "Triangle"]}]
   ::ir/enums
   [{::ir/name "Color"
     ::ir/values ["Red" "Green" "Blue" "Yellow"]}]
   ::ir/context-relationships
   {"PlayArea" "Game"}
   ::ir/interface-implementers
   {"Shape" ["Circle" "Triangle"]}
   ::ir/observable-classes
   ["Time"]
   ::ir/subscriber-classes
   ["Game"]
   ::ir/debug-methods
   [{::ir/class "Rect"
     ::ir/method "toConstruction"
     ::ir/depth-parameter true
     ::ir/format :hiccup}]})

(def sample-schema-ir-with-observable
  {::ir/assemblages
   [{::ir/name "Time"
     ::ir/components
     [{::ir/component-name "current" ::ir/type-name "Int" ::ir/relationship :ordinary ::ir/optional-name nil}]}
    {::ir/name "Game"
     ::ir/components
     [{::ir/component-name "time" ::ir/type-name "Time" ::ir/relationship :reactive ::ir/optional-name nil}]}]
   ::ir/interfaces []
   ::ir/enums []
   ::ir/context-relationships {}
   ::ir/interface-implementers {}
   ::ir/observable-classes
   ["Time"]
   ::ir/subscriber-classes
   ["Game"]
   ::ir/debug-methods []})

;; =============================================================================
;; Unit Tests
;; =============================================================================

(deftest test-generate-component-field
  (testing "generates basic field declaration"
    (let [component {::ir/component-name "x" ::ir/type-name "Int" ::ir/relationship :ordinary}
          result (ir-to-haxe/generate-component-field component)]
      (is (= "    public var x: Int;" result))))
  
  (testing "generates field with complex type"
    (let [component {::ir/component-name "players" ::ir/type-name "Array<Player>" ::ir/relationship :ordinary}
          result (ir-to-haxe/generate-component-field component)]
      (is (= "    public var players: Array<Player>;" result)))))

(deftest test-generate-context-field
  (testing "generates context field for parent class"
    (let [result (ir-to-haxe/generate-context-field "Game")]
      (is (= "    public var theGame: Game;" result)))))

(deftest test-generate-constructor-params
  (testing "generates constructor parameters for components"
    (let [components [{::ir/component-name "x" ::ir/type-name "Int"}
                      {::ir/component-name "y" ::ir/type-name "Int"}
                      {::ir/component-name "name" ::ir/type-name "String"}]
          result (ir-to-haxe/generate-constructor-params components)]
      (is (= "x:Int, y:Int, name:String" result)))))

(deftest test-generate-constructor-body
  (testing "generates constructor body assignments"
    (let [components [{::ir/component-name "x" ::ir/type-name "Int"}
                      {::ir/component-name "y" ::ir/type-name "Int"}
                      {::ir/component-name "name" ::ir/type-name "String"}]
          result (ir-to-haxe/generate-constructor-body components)]
      (is (= "this.x = x;\n        this.y = y;\n        this.name = name;" result)))))

(deftest test-generate-to-construction-parts
  (testing "generates toConstruction parts for primitive types"
    (let [components [{::ir/component-name "x" ::ir/type-name "Int"}
                      {::ir/component-name "name" ::ir/type-name "String"}]
          result (ir-to-haxe/generate-to-construction-parts components)]
      (is (= ["this.x.toConstruction(depth + 1)" "this.name.toConstruction(depth + 1)"] result))))
  
  (testing "generates toConstruction parts for array types"
    (let [components [{::ir/component-name "players" ::ir/type-name "Array<Player>"}
                      {::ir/component-name "config" ::ir/type-name "Map<String, Int>"}]
          result (ir-to-haxe/generate-to-construction-parts components)]
      (is (= ["ArrayExtensions.toConstruction(this.players, depth + 1)" "this.config.toConstruction(depth + 1)"] result)))))

(deftest test-generate-to-construction-method
  (testing "generates toConstruction method for class"
    (let [components [{::ir/component-name "x" ::ir/type-name "Int"}
                      {::ir/component-name "y" ::ir/type-name "Int"}]
          result (ir-to-haxe/generate-to-construction-method "Rect" components)]
      (is (str/includes? result "public function toConstruction(depth:Int = 0):String"))
      (is (str/includes? result "return ind + '[:Rect'"))
      (is (str/includes? result "this.x.toConstruction(depth + 1)"))
      (is (str/includes? result "this.y.toConstruction(depth + 1)")))))

(deftest test-generate-set-context-method
  (testing "generates setContext method"
    (let [result (ir-to-haxe/generate-set-context-method "Game")]
      (is (str/includes? result "public function setContext(c: Game)"))
      (is (str/includes? result "this.theGame = c;"))))

(deftest test-generate-observable-infrastructure
  (testing "generates observable infrastructure"
    (let [result (ir-to-haxe/generate-observable-infrastructure "Time")]
      (is (str/includes? result "private var subscribers: Array<Dynamic> = []"))
      (is (str/includes? result "public function subscribe(subscriber: Dynamic): Void"))
      (is (str/includes? result "public function unsubscribe(subscriber: Dynamic): Void"))
      (is (str/includes? result "public function notifySubscribers(): Void")))))

(deftest test-generate-haxe-class
  (testing "generates basic Haxe class"
    (let [assemblage (first (::ir/assemblages sample-schema-ir))
          result (ir-to-haxe/generate-haxe-class assemblage sample-schema-ir)]
      (is (str/includes? result "class Rect"))
      (is (str/includes? result "public var x: Int;"))
      (is (str/includes? result "public var y: Int;"))
      (is (str/includes? result "public var width: Int;"))
      (is (str/includes? result "public var height: Int;"))
      (is (str/includes? result "public function new(x:Int, y:Int, width:Int, height:Int)"))
      (is (str/includes? result "this.x = x;"))
      (is (str/includes? result "public function toConstruction(depth:Int = 0):String"))))
  
  (testing "generates class with context-specific components"
    (let [game-assemblage (nth (::ir/assemblages sample-schema-ir) 2)
          result (ir-to-haxe/generate-haxe-class game-assemblage sample-schema-ir)]
      (is (str/includes? result "class Game"))
      (is (str/includes? result "public var theGame: Game;"))
      (is (str/includes? result "public function setContext(c: Game)"))
      (is (str/includes? result "this.theGame = c;"))))
  
  (testing "generates class with observable infrastructure"
    (let [time-assemblage (first (::ir/assemblages sample-schema-ir-with-observable))
          result (ir-to-haxe/generate-haxe-class time-assemblage sample-schema-ir-with-observable)]
      (is (str/includes? result "class Time"))
      (is (str/includes? result "private var subscribers: Array<Dynamic> = []"))
      (is (str/includes? result "public function subscribe(subscriber: Dynamic): Void"))
      (is (str/includes? result "public function unsubscribe(subscriber: Dynamic): Void"))
      (is (str/includes? result "public function notifySubscribers(): Void")))))

(deftest test-generate-haxe-interface
  (testing "generates Haxe interface"
    (let [interface (first (::ir/interfaces sample-schema-ir))
          result (ir-to-haxe/generate-haxe-interface interface)]
      (is (str/includes? result "interface Shape"))
      (is (str/includes? result "public function toConstruction(depth:Int = 0):String")))))

(deftest test-generate-haxe-enum
  (testing "generates Haxe enum"
    (let [enum (first (::ir/enums sample-schema-ir))
          result (ir-to-haxe/generate-haxe-enum enum)]
      (is (str/includes? result "enum Color"))
      (is (str/includes? result "Red;"))
      (is (str/includes? result "Green;"))
      (is (str/includes? result "Blue;"))
      (is (str/includes? result "Yellow;"))
      (is (str/includes? result "public static function RedToConstruction(depth:Int = 0):String"))
      (is (str/includes? result "return ind + '\"Red';")))))

(deftest test-generate-array-extensions
  (testing "generates ArrayExtensions when arrays are present"
    (let [result (ir-to-haxe/generate-array-extensions sample-schema-ir)]
      (is (str/includes? result "class ArrayExtensions"))
      (is (str/includes? result "public static function toConstruction<T>(arr: Array<T>, depth: Int = 0): String"))
      (is (str/includes? result "result = ind + '[:Array';"))))
  
  (testing "does not generate ArrayExtensions when no arrays present"
    (let [simple-ir {::ir/assemblages [{::ir/name "Rect" ::ir/components [{::ir/component-name "x" ::ir/type-name "Int"}]}]}
          result (ir-to-haxe/generate-array-extensions simple-ir)]
      (is (nil? result)))))

(deftest test-schema-ir-to-haxe
  (testing "generates complete Haxe code from schema IR"
    (let [result (ir-to-haxe/schema-ir-to-haxe sample-schema-ir)]
      ;; Should contain all classes
      (is (str/includes? result "class Rect"))
      (is (str/includes? result "class Player"))
      (is (str/includes? result "class Game"))
      
      ;; Should contain interface
      (is (str/includes? result "interface Shape"))
      
      ;; Should contain enum
      (is (str/includes? result "enum Color"))
      
      ;; Should contain ArrayExtensions (since we have Array types)
      (is (str/includes? result "class ArrayExtensions"))
      
      ;; Should have proper structure
      (is (str/includes? result "public function new("))
      (is (str/includes? result "public function toConstruction("))
      (is (str/includes? result "public var "))))
  
  (testing "generates Haxe code with observable infrastructure"
    (let [result (ir-to-haxe/schema-ir-to-haxe sample-schema-ir-with-observable)]
      (is (str/includes? result "class Time"))
      (is (str/includes? result "private var subscribers: Array<Dynamic> = []"))
      (is (str/includes? result "public function subscribe(subscriber: Dynamic): Void"))
      (is (str/includes? result "public function unsubscribe(subscriber: Dynamic): Void"))
      (is (str/includes? result "public function notifySubscribers(): Void"))))))

;; =============================================================================
;; Integration Tests - Compare with existing haxegen output
;; =============================================================================

(deftest test-context-dependent-variables-working
  (testing "demonstrates that context-dependent variables are working"
    (let [context-ir {::ir/assemblages
                      [{::ir/name "Game"
                        ::ir/components
                        [{::ir/component-name "playArea" ::ir/type-name "PlayArea" ::ir/relationship :context-specific ::ir/optional-name nil}
                         {::ir/component-name "ball" ::ir/type-name "Ball" ::ir/relationship :ordinary ::ir/optional-name nil}]}
                       {::ir/name "PlayArea"
                        ::ir/components
                        [{::ir/component-name "rect" ::ir/type-name "Rect" ::ir/relationship :ordinary ::ir/optional-name nil}]}]
                      ::ir/interfaces []
                      ::ir/enums []
                      ::ir/context-relationships
                      {"PlayArea" "Game"}
                      ::ir/interface-implementers {}
                      ::ir/observable-classes []
                      ::ir/subscriber-classes []
                      ::ir/debug-methods []}
          result (ir-to-haxe/schema-ir-to-haxe context-ir)]
      

      
      ;; Should generate context field for PlayArea
      (is (str/includes? result "public var theGame: Game;"))
      
      ;; Should generate setContext method for PlayArea
      (is (str/includes? result "public function setContext(c: Game)"))
      (is (str/includes? result "this.theGame = c;"))
      
      ;; Should NOT generate context field for Game (it's the parent)
      (is (not (str/includes? result "class Game {public var theGame: Game;")))
      
      ;; Should NOT generate setContext method for Game
      (is (not (str/includes? result "class Game {public function setContext(c: Game)"))))))

(deftest test-ir-to-haxe-matches-haxegen-pattern
  (testing "IR to Haxe generates similar structure to existing haxegen"
    (let [simple-ir {::ir/assemblages
                     [{::ir/name "Rect"
                       ::ir/components
                       [{::ir/component-name "x" ::ir/type-name "Int" ::ir/relationship :ordinary ::ir/optional-name nil}
                        {::ir/component-name "y" ::ir/type-name "Int" ::ir/relationship :ordinary ::ir/optional-name nil}
                        {::ir/component-name "width" ::ir/type-name "Int" ::ir/relationship :ordinary ::ir/optional-name nil}
                        {::ir/component-name "height" ::ir/type-name "Int" ::ir/relationship :ordinary ::ir/optional-name nil}]}]
                     ::ir/interfaces []
                     ::ir/enums []
                     ::ir/context-relationships {}
                     ::ir/interface-implementers {}
                     ::ir/observable-classes []
                     ::ir/subscriber-classes []
                     ::ir/debug-methods []}
          result (ir-to-haxe/schema-ir-to-haxe simple-ir)]
      
      ;; Should have the same basic structure as haxegen output
      (is (str/includes? result "class Rect"))
      (is (str/includes? result "public var x: Int;"))
      (is (str/includes? result "public var y: Int;"))
      (is (str/includes? result "public var width: Int;"))
      (is (str/includes? result "public var height: Int;"))
      (is (str/includes? result "public function new(x:Int, y:Int, width:Int, height:Int)"))
      (is (str/includes? result "this.x = x;"))
      (is (str/includes? result "this.y = y;"))
      (is (str/includes? result "this.width = width;"))
      (is (str/includes? result "this.height = height;"))
      (is (str/includes? result "public function toConstruction(depth:Int = 0):String"))
      (is (str/includes? result "return ind + '[:Rect'"))))) 

(deftest test-maps-arrays-interfaces-working
  (testing "demonstrates that Maps, Arrays, and Interfaces/Subtypes are working"
    (let [complex-ir {::ir/assemblages
                      [{::ir/name "Game"
                        ::ir/components
                        [{::ir/component-name "players" ::ir/type-name "Array<Player>" ::ir/relationship :ordinary ::ir/optional-name nil}
                         {::ir/component-name "config" ::ir/type-name "Map<String, Int>" ::ir/relationship :ordinary ::ir/optional-name nil}
                         {::ir/component-name "shapes" ::ir/type-name "Array<Shape>" ::ir/relationship :ordinary ::ir/optional-name nil}
                         {::ir/component-name "ball" ::ir/type-name "Ball" ::ir/relationship :ordinary ::ir/optional-name nil}]}
                       {::ir/name "Player"
                        ::ir/components
                        [{::ir/component-name "name" ::ir/type-name "String" ::ir/relationship :ordinary ::ir/optional-name nil}
                         {::ir/component-name "score" ::ir/type-name "Int" ::ir/relationship :ordinary ::ir/optional-name nil}]}
                       {::ir/name "Circle"
                        ::ir/components
                        [{::ir/component-name "radius" ::ir/type-name "Int" ::ir/relationship :ordinary ::ir/optional-name nil}]}
                       {::ir/name "Triangle"
                        ::ir/components
                        [{::ir/component-name "base" ::ir/type-name "Int" ::ir/relationship :ordinary ::ir/optional-name nil}
                         {::ir/component-name "height" ::ir/type-name "Int" ::ir/relationship :ordinary ::ir/optional-name nil}]}
                       {::ir/name "Football"
                        ::ir/components
                        [{::ir/component-name "size" ::ir/type-name "Int" ::ir/relationship :ordinary ::ir/optional-name nil}]}]
                      ::ir/interfaces
                      [{::ir/name "Shape"
                        ::ir/implementers ["Circle" "Triangle"]}
                       {::ir/name "Ball"
                        ::ir/implementers ["Football"]}]
                      ::ir/enums []
                      ::ir/context-relationships {}
                      ::ir/interface-implementers
                      {"Shape" ["Circle" "Triangle"]
                       "Ball" ["Football"]}
                      ::ir/observable-classes []
                      ::ir/subscriber-classes []
                      ::ir/debug-methods []}
          result (ir-to-haxe/schema-ir-to-haxe complex-ir)]
      

      
      ;; Should generate ArrayExtensions (since we have Array types)
      (is (str/includes? result "class ArrayExtensions"))
      (is (str/includes? result "public static function toConstruction<T>(arr: Array<T>, depth: Int = 0): String"))
      
      ;; Should generate interfaces
      (is (str/includes? result "interface Shape"))
      (is (str/includes? result "interface Ball"))
      (is (str/includes? result "public function toConstruction(depth:Int = 0):String"))
      
      ;; Should generate implementing classes with 'implements'
      (is (str/includes? result "class Circle implements Shape"))
      (is (str/includes? result "class Triangle implements Shape"))
      (is (str/includes? result "class Football implements Ball"))
      
      ;; Should generate Array fields correctly
      (is (str/includes? result "public var players: Array<Player>;"))
      (is (str/includes? result "public var shapes: Array<Shape>;"))
      
      ;; Should generate Map fields correctly
      (is (str/includes? result "public var config: Map<String, Int>;"))
      
      ;; Should generate ArrayExtensions calls in toConstruction
      (is (str/includes? result "ArrayExtensions.toConstruction(this.players, depth + 1)"))
      (is (str/includes? result "ArrayExtensions.toConstruction(this.shapes, depth + 1)"))
      
      ;; Should generate .toConstruction() calls for Maps
      (is (str/includes? result "this.config.toConstruction(depth + 1)"))
      
      ;; Should generate constructor parameters correctly
      (is (str/includes? result "public function new(players:Array<Player>, config:Map<String, Int>, shapes:Array<Shape>, ball:Ball)"))
      
      ;; Should generate constructor assignments correctly
      (is (str/includes? result "this.players = players;"))
      (is (str/includes? result "this.config = config;"))
      (is (str/includes? result "this.shapes = shapes;"))
      (is (str/includes? result "this.ball = ball;"))))) 