(ns wchnt-lang.ir-to-haxe-test
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [wchnt-lang.ir-to-haxe :as ir-to-haxe]
            [wchnt-lang.ir :as ir]
            [wchnt-lang.compiler :as compiler]
            [wchnt-lang.pipeline :as p]
            [wchnt-lang.schema :as schema]))

;; =============================================================================
;; Test Data - Sample IR structures
;; =============================================================================

(def sample-schema-ir
  {:assemblages
   [{:name "Rect"
     :components
     [{:component-name "x" :type-name "Int" :relationship :ordinary :optional-name nil}
      {:component-name "y" :type-name "Int" :relationship :ordinary :optional-name nil}
      {:component-name "width" :type-name "Int" :relationship :ordinary :optional-name nil}
      {:component-name "height" :type-name "Int" :relationship :ordinary :optional-name nil}]}
    {:name "Player"
     :components
     [{:component-name "x" :type-name "Int" :relationship :ordinary :optional-name nil}
      {:component-name "y" :type-name "Int" :relationship :ordinary :optional-name nil}
      {:component-name "name" :type-name "String" :relationship :ordinary :optional-name nil}]}
    {:name "Game"
     :components
     [{:component-name "playArea" :type-name "PlayArea" :relationship :context-specific :optional-name nil}
      {:component-name "ball" :type-name "Ball" :relationship :ordinary :optional-name nil}
      {:component-name "teams" :type-name "Array<Team>" :relationship :ordinary :optional-name nil}
      {:component-name "players" :type-name "Array<Player>" :relationship :ordinary :optional-name nil}
      {:component-name "scores" :type-name "Scores" :relationship :ordinary :optional-name nil}]}]
   :interfaces
   [{:name "Shape"
     :implementers ["Circle" "Triangle"]}]
   :enums
   [{:name "Color"
     :values ["Red" "Green" "Blue" "Yellow"]}]
   :context-relationships
   {"PlayArea" "Game"}
   :interface-implementers
   {"Shape" ["Circle" "Triangle"]}
   :observable-classes
   ["Time"]
   :subscriber-classes
   ["Game"]
   :debug-methods
   [{:class "Rect"
     :method "toConstruction"
     :depth-parameter true
     :format :hiccup}]})

(def sample-schema-ir-with-observable
  {:assemblages
   [{:name "Time"
     :components
     [{:component-name "current" :type-name "Int" :relationship :ordinary :optional-name nil}]}
    {:name "Game"
     :components
     [{:component-name "time" :type-name "Time" :relationship :reactive :optional-name nil}]}]
   :interfaces []
   :enums []
   :context-relationships {}
   :interface-implementers {}
   :observable-classes
   ["Time"]
   :subscriber-classes
   ["Game"]
   :debug-methods []})

;; =============================================================================
;; Unit Tests
;; =============================================================================

(deftest test-generate-component-field
  (testing "generates basic field declaration"
    (let [component {:component-name "x" :type-name "Int" :relationship :ordinary}
          result (ir-to-haxe/generate-component-field component)]
      (is (= "    public var x: Int;" result))))
  
  (testing "generates field with complex type"
    (let [component {:component-name "players" :type-name "Array<Player>" :relationship :ordinary}
          result (ir-to-haxe/generate-component-field component)]
      (is (= "    public var players: Array<Player>;" result)))))

(deftest test-generate-context-field
  (testing "generates context field for parent class"
    (let [result (ir-to-haxe/generate-context-field "Game")]
      (is (= "    public var theGame: Game;" result)))))

(deftest test-generate-constructor-params
  (testing "generates constructor parameters for components"
    (let [components [{:component-name "x" :type-name "Int"}
                      {:component-name "y" :type-name "Int"}
                      {:component-name "name" :type-name "String"}]
          result (ir-to-haxe/generate-constructor-params components)]
      (is (= "x:Int, y:Int, name:String" result)))))

(deftest test-generate-constructor-body
  (testing "generates constructor body assignments"
    (let [components [{:component-name "x" :type-name "Int"}
                      {:component-name "y" :type-name "Int"}
                      {:component-name "name" :type-name "String"}]
          result (ir-to-haxe/generate-constructor-body components)]
      (is (= "this.x = x;\n        this.y = y;\n        this.name = name;" result)))))

(deftest test-generate-to-construction-parts
  (testing "generates toConstruction parts for primitive types"
    (let [components [{:component-name "x" :type-name "Int"}
                      {:component-name "name" :type-name "String"}]
          result (ir-to-haxe/generate-to-construction-parts components sample-schema-ir)]
      (is (= ["this.x" "'\"' + this.name + '\"'"] result))))
  
  (testing "generates toConstruction parts for array types"
    (let [components [{:component-name "players" :type-name "Array<Player>"}
                      {:component-name "config" :type-name "Map<String, Int>"}]
          result (ir-to-haxe/generate-to-construction-parts components sample-schema-ir)]
      (is (= ["helper.arrayToConstruction(this.players, depth + 1)" "helper.mapToConstruction(this.config, depth + 1)"] result)))))

(deftest test-generate-to-construction-method
  (testing "generates toConstruction method for class"
    (let [components [{:component-name "x" :type-name "Int"}
                      {:component-name "y" :type-name "Int"}]
          result (ir-to-haxe/generate-to-construction-method "Rect" components sample-schema-ir)]
      (is (str/includes? result "public function toConstruction(depth:Int = 0, helper:IWCHNTHelper):String"))
      (is (str/includes? result "return ind + '[:Rect'"))
      (is (str/includes? result "this.x"))
      (is (str/includes? result "this.y")))))

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
    (let [assemblage (first (:assemblages sample-schema-ir))
          result (ir-to-haxe/generate-haxe-class assemblage sample-schema-ir)]
      (is (str/includes? result "class Rect"))
      (is (str/includes? result "public var x: Int;"))
      (is (str/includes? result "public var y: Int;"))
      (is (str/includes? result "public var width: Int;"))
      (is (str/includes? result "public var height: Int;"))
      (is (str/includes? result "public function new(x:Int, y:Int, width:Int, height:Int)"))
      (is (str/includes? result "this.x = x;"))
      (is (str/includes? result "public function toConstruction(depth:Int = 0, helper:IWCHNTHelper):String"))))
  
  (testing "generates class with context-specific components"
    (let [game-assemblage (nth (:assemblages sample-schema-ir) 2)
          result (ir-to-haxe/generate-haxe-class game-assemblage sample-schema-ir)]
      (is (str/includes? result "class Game"))
      (is (str/includes? result "public var theGame: Game;"))
      (is (str/includes? result "public function setContext(c: Game)"))
      (is (str/includes? result "this.theGame = c;"))))
  
  (testing "generates class with observable infrastructure"
    (let [time-assemblage (first (:assemblages sample-schema-ir-with-observable))
          result (ir-to-haxe/generate-haxe-class time-assemblage sample-schema-ir-with-observable)]
      (is (str/includes? result "class Time"))
      (is (str/includes? result "private var subscribers: Array<Dynamic> = []"))
      (is (str/includes? result "public function subscribe(subscriber: Dynamic): Void"))
      (is (str/includes? result "public function unsubscribe(subscriber: Dynamic): Void"))
      (is (str/includes? result "public function notifySubscribers(): Void")))))

(deftest test-generate-haxe-interface
  (testing "generates Haxe interface"
    (let [interface (first (:interfaces sample-schema-ir))
          result (ir-to-haxe/generate-haxe-interface interface)]
      (is (str/includes? result "interface Shape"))
      (is (str/includes? result "public function toConstruction(depth:Int = 0, helper:IWCHNTHelper):String")))))

(deftest test-generate-haxe-enum
  (testing "generates Haxe enum"
    (let [enum (first (:enums sample-schema-ir))
          result (ir-to-haxe/generate-haxe-enum enum)]
      (is (str/includes? result "enum Color"))
      (is (str/includes? result "Red;"))
      (is (str/includes? result "Green;"))
      (is (str/includes? result "Blue;"))
      (is (str/includes? result "Yellow;"))
      ;; Should NOT have methods in enum (Haxe enums can't have methods)
      (is (not (str/includes? result "public static function RedToConstruction"))))))

;; Removed - ArrayExtensions class is no longer used

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
      
      ;; Should contain helper interface and implementation (since we have Array types)
      (is (str/includes? result "interface IWCHNTHelper"))
      (is (str/includes? result "class WCHNTHelper implements IWCHNTHelper"))
      
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
    (let [context-ir {:assemblages
                      [{:name "Game"
                        :components
                        [{:component-name "playArea" :type-name "PlayArea" :relationship :context-specific :optional-name nil}
                         {:component-name "ball" :type-name "Ball" :relationship :ordinary :optional-name nil}]}
                       {:name "PlayArea"
                        :components
                        [{:component-name "rect" :type-name "Rect" :relationship :ordinary :optional-name nil}]}]
                      :interfaces []
                      :enums []
                      :context-relationships
                      {"PlayArea" "Game"}
                      :interface-implementers {}
                      :observable-classes []
                      :subscriber-classes []
                      :debug-methods []}
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
    (let [simple-ir {:assemblages
                     [{:name "Rect"
                       :components
                       [{:component-name "x" :type-name "Int" :relationship :ordinary :optional-name nil}
                        {:component-name "y" :type-name "Int" :relationship :ordinary :optional-name nil}
                        {:component-name "width" :type-name "Int" :relationship :ordinary :optional-name nil}
                        {:component-name "height" :type-name "Int" :relationship :ordinary :optional-name nil}]}]
                     :interfaces []
                     :enums []
                     :context-relationships {}
                     :interface-implementers {}
                     :observable-classes []
                     :subscriber-classes []
                     :debug-methods []}
          result (ir-to-haxe/schema-ir-to-haxe simple-ir)]
      
      ;; Should generate the same structure as haxegen
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
      (is (str/includes? result "return ind + '[:Rect'"))))) 

(deftest test-maps-arrays-interfaces-working
  (testing "demonstrates that Maps, Arrays, and Interfaces/Subtypes are working"
    (let [complex-ir {:assemblages
                      [{:name "Game"
                        :components
                        [{:component-name "players" :type-name "Array<Player>" :relationship :ordinary :optional-name nil}
                         {:component-name "config" :type-name "Map<String, Int>" :relationship :ordinary :optional-name nil}
                         {:component-name "shapes" :type-name "Array<Shape>" :relationship :ordinary :optional-name nil}
                         {:component-name "ball" :type-name "Ball" :relationship :ordinary :optional-name nil}]}
                       {:name "Player"
                        :components
                        [{:component-name "name" :type-name "String" :relationship :ordinary :optional-name nil}
                         {:component-name "score" :type-name "Int" :relationship :ordinary :optional-name nil}]}
                       {:name "Circle"
                        :components
                        [{:component-name "radius" :type-name "Int" :relationship :ordinary :optional-name nil}]}
                       {:name "Triangle"
                        :components
                        [{:component-name "base" :type-name "Int" :relationship :ordinary :optional-name nil}
                         {:component-name "height" :type-name "Int" :relationship :ordinary :optional-name nil}]}
                       {:name "Football"
                        :components
                        [{:component-name "size" :type-name "Int" :relationship :ordinary :optional-name nil}]}]
                      :interfaces
                      [{:name "Shape"
                        :implementers ["Circle" "Triangle"]}
                       {:name "Ball"
                        :implementers ["Football"]}]
                      :enums []
                      :context-relationships {}
                      :interface-implementers
                      {"Shape" ["Circle" "Triangle"]
                       "Ball" ["Football"]}
                      :observable-classes []
                      :subscriber-classes []
                      :debug-methods []}
          result (ir-to-haxe/schema-ir-to-haxe complex-ir)]
      
      ;; Should generate helper interface and implementation
      (is (str/includes? result "interface IWCHNTHelper"))
      (is (str/includes? result "class WCHNTHelper implements IWCHNTHelper"))
      
      ;; Should generate interfaces
      (is (str/includes? result "interface Shape"))
      (is (str/includes? result "interface Ball"))
      
      ;; Should generate classes that implement interfaces
      (is (str/includes? result "class Circle implements Shape"))
      (is (str/includes? result "class Triangle implements Shape"))
      (is (str/includes? result "class Football implements Ball"))
      
      ;; Should generate array fields
      (is (str/includes? result "public var players: Array<Player>;"))
      (is (str/includes? result "public var shapes: Array<Shape>;"))
      
      ;; Should generate map fields
      (is (str/includes? result "public var config: Map<String, Int>;"))
      
      ;; Should generate array construction calls using helper
      (is (str/includes? result "helper.arrayToConstruction(this.players, depth + 1)"))
      (is (str/includes? result "helper.arrayToConstruction(this.shapes, depth + 1)"))
      
      ;; Should generate map construction calls using helper
      (is (str/includes? result "helper.mapToConstruction(this.config, depth + 1)"))
      
      ;; Should generate constructor with all parameters
      (is (str/includes? result "public function new(players:Array<Player>, config:Map<String, Int>, shapes:Array<Shape>, ball:Ball)"))
      
      ;; Should generate constructor body assignments
      (is (str/includes? result "this.players = players;"))
      (is (str/includes? result "this.config = config;"))
      (is (str/includes? result "this.shapes = shapes;"))
      (is (str/includes? result "this.ball = ball;")))))

(deftest test-array-of-strings
  (testing "handles arrays of strings correctly"
    (let [string-list-ir {:assemblages
                          [{:name "StringList"
                            :components
                            [{:component-name "xs" :type-name "Array<String>" :relationship :ordinary :optional-name nil}]}]
                          :interfaces []
                          :enums []
                          :context-relationships {}
                          :interface-implementers {}
                          :observable-classes []
                          :subscriber-classes []
                          :debug-methods []}
          result (ir-to-haxe/schema-ir-to-haxe string-list-ir)]
      
      ;; Should generate the class
      (is (str/includes? result "class StringList implements IWCHNTObject"))
      
      ;; Should generate the array field
      (is (str/includes? result "public var xs: Array<String>"))
      
      ;; Should generate array construction call using helper
      (is (str/includes? result "helper.arrayToConstruction(this.xs, depth + 1)"))
      
      ;; Should generate helper interface
      (is (str/includes? result "interface IWCHNTHelper"))
      
      ;; Should generate helper implementation
      (is (str/includes? result "class WCHNTHelper implements IWCHNTHelper")))))

(deftest test-generate-construction-factory
  (testing "generates basic factory function from construction IR"
    (let [construction-ir {:root-class "Game"
                           :factory-name "gameFactory"
                           :return-object "obj1"
                           :objects {"obj1" {:type :object
                                            :class-name "Game"
                                            :args []
                                            :index 0}}}
          schema-ir {:assemblages [] :interfaces [] :enums []}
          result (ir-to-haxe/generate-construction-factory construction-ir schema-ir)]
      (is (str/includes? result "public static function gameFactory(): Game"))
      (is (str/includes? result "return obj1;"))))
  
  (testing "generates factory with object arguments"
    (let [construction-ir {:root-class "Game"
                           :factory-name "gameFactory"
                           :return-object "obj3"
                           :objects {"obj1" {:type :object
                                            :class-name "Player"
                                            :args [{:type :primitive
                                                    :class-name "String"
                                                    :args ["Bob"]
                                                    :index 0}
                                                   {:type :primitive
                                                    :class-name "Int"
                                                    :args [100]
                                                    :index 1}]}
                                     "obj2" {:type :primitive
                                            :class-name "Int"
                                            :args [200]
                                            :index 0}
                                     "obj3" {:type :object
                                            :class-name "Game"
                                            :args ["obj1" "obj2"]
                                            :index 0}}}
          schema-ir {:assemblages [] :interfaces [] :enums []}
          result (ir-to-haxe/generate-construction-factory construction-ir schema-ir)]
      (is (str/includes? result "var obj1 = new Player(\"Bob\", 100);"))
      (is (str/includes? result "var obj2 = 200;"))
      (is (str/includes? result "var obj3 = new Game(obj1, obj2);"))
      (is (str/includes? result "return obj3;")))))

;; =============================================================================
;; Integration Tests - Map Construction
;; =============================================================================

(deftest test-map-construction-integration
  "Test that map construction syntax is supported in IR-to-Haxe generation"
  (testing "Simple map construction with enum keys"
    (let [wchnt-content "## Schema

```
Main = String/hello Config
Direction = \"Up\" | \"Down\" | \"Left\" | \"Right\"
Config = {Direction : String}/moves
```

## Construction

```
controls = [:Map/{Direction:String} Up:\"jump\", Down:\"crouch\", Left:\"left\" Right:\"right\"].
[:Main \"Hello\" [:Config controls]]
```"
          cargo-result (compiler/compile wchnt-content)]
      
      (is (p/is-cargo? cargo-result))
      (if (:success cargo-result)
        (let [result (:value cargo-result)]
          (is (schema/valid-full-program? result))
          (is (str/includes? (:classes result) "class Main"))
          (is (str/includes? (:classes result) "class Config"))
          (is (str/includes? (:classes result) "enum Direction"))
          (is (str/includes? (:factory result) "mainFactory")))
        (do
          (println "Map construction failed:")
          (println "Error:" (:error cargo-result))
          (println "Full cargo:" (pr-str cargo-result))
          (is false "Map construction should work")))))) 