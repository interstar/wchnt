(ns wchnt-lang.targets.haxe-backend-test
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [wchnt-lang.targets.haxe-backend :as ir-to-haxe]
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
      (is (str/includes? result "public function notifySubscribers(): Void"))
      (is (str/includes? result "Reflect.callMethod(subscriber"))
      (is (not (str/includes? result "hasField"))))))

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
;; Integration Tests - Generated Haxe structure
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

      ;; Every `new Game(...)` wires the context child — including method constructions
      (is (str/includes? result "this.playArea.setContext(this);"))
      
      ;; Should NOT generate context field for Game (it's the parent)
      (is (not (str/includes? result "class Game {public var theGame: Game;")))
      
      ;; Should NOT generate setContext method for Game
      (is (not (str/includes? result "class Game {public function setContext(c: Game)"))))))

(deftest test-ir-to-haxe-generates-basic-class-pattern
  (testing "IR to Haxe generates basic class structure"
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
      
      ;; Should generate a complete class from schema IR
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
                           :factory-name "factory"
                           :return-object "obj1"
                           :objects {"obj1" {:type :object
                                            :class-name "Game"
                                            :args []
                                            :index 0}}}
          schema-ir {:assemblages [] :interfaces [] :enums []}
          result (ir-to-haxe/generate-construction-factory construction-ir schema-ir)]
      (is (str/includes? result "public static function factory(): Game"))
      (is (str/includes? result "return obj1;"))))
  
  (testing "generates factory with object arguments"
    (let [construction-ir {:root-class "Game"
                           :factory-name "factory"
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

(deftest test-generate-construction-factory-subscribes-reactive-fields
  (testing "factory calls subscribe on each $ component after objects exist"
    (let [construction-ir {:root-class "Game"
                           :factory-name "factory"
                           :return-object "obj2"
                           :objects {"obj1" {:type :object
                                            :class-name "Time"
                                            :args [{:type :primitive :class-name "Int" :args [0] :index 0}]
                                            :index 0}
                                     "obj2" {:type :object
                                            :class-name "Game"
                                            :args [{:type :variable :class-name "Time" :args ["obj1"] :index 0}]
                                            :index 1}}}
          result (ir-to-haxe/generate-construction-factory construction-ir sample-schema-ir-with-observable)]
      (is (str/includes? result "var obj1 = new Time(0);"))
      (is (str/includes? result "var obj2 = new Game(obj1);"))
      (is (str/includes? result "obj2.time.subscribe(obj2);"))
      (is (re-find #"(?s)var obj2 = new Game\(obj1\);.*obj2\.time\.subscribe\(obj2\);.*return obj2;"
                   result)))))

;; =============================================================================
;; Integration Tests - Map Construction
;; =============================================================================

(deftest test-map-construction-integration
  "Test that map construction syntax is supported in IR-to-Haxe generation"
  (testing "Simple map construction with enum keys"
    (let [wchnt-content "## Schema

```
App = String/hello Config
Direction = \"Up\" | \"Down\" | \"Left\" | \"Right\"
Config = {Direction : String}/moves
```

## Construction

```
controls = {Direction:String Up:\"jump\", Down:\"crouch\", Left:\"left\" Right:\"right\"}.
[:App \"Hello\" [:Config controls]]
```

## Target

```
%terminal

%main
public static function main():Void {
    var assemblage = AppAssemblage.factory();
    var helper = new WCHNTHelper();
    trace(assemblage.toConstruction(0, helper));
}
```"
          cargo-result (compiler/compile wchnt-content)]
      
      (is (p/is-cargo? cargo-result))
      (if (:success cargo-result)
        (let [result (:value cargo-result)]
          (is (schema/valid-full-program? result))
          (is (str/includes? (:classes result) "class App"))
          (is (str/includes? (:classes result) "class Config"))
          (is (str/includes? (:classes result) "enum Direction"))
          (is (str/includes? (:classes result) "class AppAssemblage"))
          (is (str/includes? (:classes result) "public static function factory")))
        (do
          (println "Map construction failed:")
          (println "Error:" (:error cargo-result))
          (println "Full cargo:" (pr-str cargo-result))
          (is false "Map construction should work")))))) 

(deftest test-dict-example-compiles
  (testing "dictionary examples preserve nested map construction arguments"
    (let [cargo-result (compiler/compile (slurp "examples/test_dict.wcn"))]
      (is (p/is-cargo? cargo-result))
      (if (:success cargo-result)
        (let [result (:value cargo-result)
              factory (:classes result)]
          (is (schema/valid-full-program? result))
          (is (str/includes? factory "new Config([\"0\" => 0, \"1\" => 1])"))
          (is (str/includes? factory "new Config2([Dev => \"dev\", Local => \"local\", Deploy => \"deploy\"])")))
        (do
          (println "test_dict.wcn failed:")
          (println "Error:" (:error cargo-result))
          (println "Full cargo:" (pr-str cargo-result))
          (is false "test_dict.wcn should compile"))))))

(deftest test-reactive-example-subscribes
  (testing "Game = $Time construction wires time.subscribe(game)"
    (let [cargo-result (compiler/compile (slurp "examples/test_reactive.wcn"))]
      (is (p/is-cargo? cargo-result))
      (is (:success cargo-result))
      (let [result (:value cargo-result)
            classes (:classes result)
            factory (:classes result)]
        (is (str/includes? classes "class Time"))
        (is (str/includes? classes "public function subscribe(subscriber: Dynamic): Void"))
        (is (str/includes? factory ".time.subscribe("))
        (is (re-find #"obj\d+\.time\.subscribe\(obj\d+\);" factory))))))

(deftest test-complex-game-example-resolves-bindings
  (testing "test.wcn maps ps to an object id and infers nested Player, not String"
    (let [cargo-result (compiler/compile (slurp "examples/test.wcn"))]
      (is (:success cargo-result))
      (let [factory (get-in cargo-result [:value :classes])
            classes (get-in cargo-result [:value :classes])]
        (is (not (re-find #"[^a-zA-Z]ps[^a-zA-Z]" factory)))
        (is (str/includes? factory "new Player(40, 90, \"Bob\")"))
        (is (not (str/includes? factory "new String(40, 90")))
        (is (str/includes? classes "helper:IWCHNTHelper"))))))

(deftest test-two-reactive-subscribers-share-time
  (testing "shared Time is subscribed by both World and Scene, Time built first"
    (let [cargo-result (compiler/compile (slurp "examples/test_reactive_two_subscribers.wcn"))]
      (is (:success cargo-result))
      (let [factory (get-in cargo-result [:value :classes])]
        (is (re-find #"\.time\.subscribe\(" factory))
        (is (= 4 (count (re-seq #"\.time\.subscribe\(" factory))))
        (is (re-find #"(?s)new Time\(0\).*new Scene\(" factory))))))

(deftest mailbox-class-emits-inject
  (testing "square_openfl >Keys gets a generated inject that calls update"
    (let [cargo (compiler/compile (slurp "examples/square_openfl.wcn"))]
      (is (:success cargo) (first (:errors cargo)))
      (let [classes (get-in cargo [:value :classes])]
        (is (str/includes? classes "class Keys"))
        (is (str/includes? classes "public function inject(left:Bool, right:Bool, up:Bool, down:Bool): Keys"))
        (is (str/includes? classes "return this.update_mutates();"))
        (is (str/includes? (get-in cargo [:value :main-class]) "assemblage.keys.inject("))))))

(deftest arbitrary-mutating-method-emits-visible-haxe-name
  (testing "a WCHNT ! method becomes an argument-taking _mutates Haxe method"
    (let [source (str "## Schema\n\n```\n"
                      "Counter = $Clock\nClock = Int/t\n"
                      "```\n\n## Construction\n\n```\n"
                      "[:Counter [:Clock 10]]\n"
                      "```\n\n## Methods\n\n```\n"
                      "Clock::update! = { [:Clock t] }\n"
                      "Clock::advance! = { Int/delta | [:Clock (t + delta)] }\n"
                      "Counter::update! = { [:Counter clock] }\n"
                      "```\n")
          cargo (compiler/compile source)]
      (is (:success cargo) (first (:errors cargo)))
      (let [classes (get-in cargo [:value :classes])]
        (is (str/includes? classes "public function advance_mutates(delta:Int): Clock"))
        (is (str/includes? classes "return this;"))))))

(deftest factory-emits-false-bool-literals
  (testing "construction false is Haxe false, not an empty constructor argument"
    (let [cargo (compiler/compile (slurp "examples/square_openfl.wcn"))]
      (is (:success cargo) (first (:errors cargo)))
      (let [factory (get-in cargo [:value :classes])]
        (is (str/includes? factory "new Keys(false, false, false, false)"))
        (is (not (str/includes? factory "new Keys(,")))))))

(deftest fluent-chain-method-returns-graphics-root
  (testing "an unannotated fluent @WCHNTGraphics chain is one expression returning the handle"
    (let [source (str "## Schema\n\n```\n"
                      "Dot = Int/x Int/y\n"
                      "```\n\n## Construction\n\n```\n"
                      "[:Dot 1 2]\n"
                      "```\n\n## Methods\n\n```\n"
                      "Dot::draw = { @WCHNTGraphics/g | g.beginFill(1).drawCircle(5, 6, 7).endFill() }\n"
                      "```\n\n## Target\n\n```\n%openfl\n\n%init\nfunction init() {}\n\n%step\nfunction step() {}\n```\n")
          cargo (compiler/compile source)]
      (is (:success cargo) (first (:errors cargo)))
      (let [classes (get-in cargo [:value :classes])]
        (is (str/includes? classes "public function draw(g:WCHNTGraphics): WCHNTGraphics"))
        (is (str/includes? classes "return g.beginFill(1).drawCircle(5, 6, 7).endFill();"))))))

(deftest constructor-applies-construction-magic
  (testing "Haxe constructors wire context children and $ subscriptions"
    (let [context-src (str "## Schema\n\n```\n"
                           "Game = PlayArea :Ball $Time\n"
                           "PlayArea = Rect\n"
                           "Rect = Int/x Int/y Int/width Int/height\n"
                           "Ball = Int/x Int/y Int/dx Int/dy Int/rad\n"
                           "Time = Int/t\n```\n\n"
                           "## Construction\n\n```\n"
                           "[:Game [:PlayArea [0 0 800 600]] [:Ball 1 2 3 4 5] [:Time 0]]\n```\n\n"
                           "## Methods\n\n```\n"
                           "Time::update! = { [:Time t] }\n"
                           "Game::update! = { [:Game playArea ball time] }\n"
                           "Game::step = {\n"
                           "  [:Game playArea [:Ball (ball.x + 1) ball.y ball.dx ball.dy ball.rad] [:Time (time.t + 1)]]\n"
                           "}\n```\n\n"
                           "## Target\n\n```\n%terminal\n\n%main\nfunction main() {}\n```\n")
          cargo (compiler/compile context-src)]
      (is (:success cargo) (first (:errors cargo)))
      (let [classes (get-in cargo [:value :classes])]
        (is (str/includes? classes "this.ball.setContext(this);")
            "every new Game(...) must setContext, not just the factory")
        (is (str/includes? classes "this.time.subscribe(this);")
            "every new Game(...) must subscribe to $Time")))))

(deftest delegate-forwards-fields-and-methods
  (testing "Student Haxe exposes promoted name and greet"
    (let [cargo (compiler/compile (slurp "examples/test_delegate.wcn"))]
      (is (:success cargo) (first (:errors cargo)))
      (let [classes (get-in cargo [:value :classes])]
        (is (str/includes? classes "public var name(get, never): String;"))
        (is (str/includes? classes "return this.basePerson.name;"))
        (is (str/includes? classes "return this.basePerson.greet();"))))))
