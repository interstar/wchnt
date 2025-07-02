(ns wchnt-lang.core-test
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [wchnt-lang.core :refer [get-parser
                                    compile-to-haxe
                                    eyeball
                                    validate-wchnt-syntax
                                    compile-and-validate]]
            [wchnt-lang.examples :refer [example-game-schema
                                        example-person-schema
                                        example-shape-schema]]
            [wchnt-lang.schema :as schema]))

(deftest test-get-parser
  (testing "Parser retrieval"
    (let [parser (get-parser)]
      (is (ifn? parser))
      (is (not (instaparse.core/failure? (parser "Game = Ball")))))))

(deftest test-basic-compilation
  (testing "Basic compilation"
    (let [result (compile-to-haxe "Game = PlayArea Ball")]
      (is (map? result))
      (is (contains? result :success))
      (is (or (contains? result :code) (contains? result :error))))))

(deftest test-pong-example-compilation
  (testing "Pong game compilation"
    (let [input (example-game-schema)
          result (compile-to-haxe input)]
      (is (:success result))
      (is (vector? (:code result)))
      (is (= 5 (count (:code result)))) ; Should generate 5 classes: Game, PlayArea, Ball, Paddle, Rect
      (is (some #(str/includes? % "class Game") (:code result)))
      (is (some #(str/includes? % "public var playArea: PlayArea") (:code result)))
      (is (some #(str/includes? % "public var paddle1: Paddle") (:code result)))
      (is (some #(str/includes? % "public var paddle2: Paddle") (:code result))))))

(deftest test-default-naming
  (testing "Default naming (lowercase first letter)"
    (let [input "Person = String/name int/age"
          result (compile-to-haxe input)]
      (is (:success result))
      (is (some #(str/includes? % "public var name: String") (:code result)))
      (is (some #(str/includes? % "public var age: int") (:code result))))))

(deftest test-constructor-generation
  (testing "Constructor generation"
    (let [input "Game = PlayArea Ball"
          result (compile-to-haxe input)
          game-class (first (:code result))]
      (is (:success result))
      (is (str/includes? game-class "public function new("))
      (is (str/includes? game-class "playArea: PlayArea"))
      (is (str/includes? game-class "ball: Ball"))
      (is (str/includes? game-class "this.playArea = playArea"))
      (is (str/includes? game-class "this.ball = ball")))))

(deftest test-array-compilation
  (testing "Array type compilation"
    (let [input (example-person-schema)
          result (compile-to-haxe input)]
      (is (:success result))
      (is (some #(str/includes? % "public var name: String") (:code result)))
      (is (some #(str/includes? % "public var addresses: Array<Address>") (:code result))))))

(deftest test-disjunction-compilation
  (testing "Interface disjunction compilation"
    (let [input (example-shape-schema)
          result (compile-to-haxe input)]
      (is (:success result))
      (is (some #(str/includes? % "interface Shape") (:code result)))
      (is (some #(str/includes? % "class Triangle implements Shape") (:code result)))
      (is (some #(str/includes? % "class Circle implements Shape") (:code result))))))

(deftest test-mixed-features
  (testing "Mixed arrays and disjunctions"
    (let [input "Game = [Shape]/shapes [Player]/players
Shape = Triangle | Circle
Triangle = int/base int/height
Circle = int/radius
Player = String/name int/score"
          result (compile-to-haxe input)]
      (is (:success result))
      (is (some #(str/includes? % "public var shapes: Array<Shape>") (:code result)))
      (is (some #(str/includes? % "public var players: Array<Player>") (:code result)))
      (is (some #(str/includes? % "interface Shape") (:code result)))
      (is (some #(str/includes? % "class Triangle implements Shape") (:code result)))
      (is (some #(str/includes? % "class Circle implements Shape") (:code result))))))

(deftest test-syntax-validation
  (testing "Syntax validation"
    (let [valid-input "Game = PlayArea Ball"
          invalid-input "Game = = PlayArea"
          valid-result (validate-wchnt-syntax valid-input)
          invalid-result (validate-wchnt-syntax invalid-input)]
      (is (:success valid-result))
      (is (not (:success invalid-result)))
      (is (contains? invalid-result :error)))))

(deftest test-eyeball
  (testing "Eyeball validation"
    (let [valid-code "class Game {
    public var playArea: PlayArea;
    public var ball: Ball;
    
    public function new(playArea: PlayArea, ball: Ball) {
        this.playArea = playArea;
        this.ball = ball;
    }
}"
          invalid-code "function test() { return 42; }"
          valid-result (eyeball valid-code)
          invalid-result (eyeball invalid-code)]
      (is (= "seems ok" (:status valid-result)))
      (is (empty? (:issues valid-result)))
      (is (= "issues" (:status invalid-result)))
      (is (not (empty? (:issues invalid-result)))))))

(deftest test-compile-and-validate
  (testing "Compile and validate workflow"
    (let [input (example-game-schema)
          result (compile-and-validate input)]
      (is (:success result))
      (is (contains? result :validation))
      (is (= "seems ok" (:status (:validation result)))))))

(deftest test-example-schemas
  (testing "Example schema functions"
    (is (string? (example-game-schema)))
    (is (string? (example-person-schema)))
    (is (string? (example-shape-schema)))
    (is (str/includes? (example-game-schema) "Game ="))
    (is (str/includes? (example-person-schema) "Person ="))
    (is (str/includes? (example-shape-schema) "Shape ="))))

(deftest test-schema-validation
  (testing "Schema validation functions"
    (let [success-result (schema/success-result ["class Test {}"])
          error-result (schema/error-result "Test error")
          validation-ok (schema/validation-ok)
          validation-issues (schema/validation-issues ["Issue 1"])]
      (is (schema/valid-compilation-result? success-result))
      (is (schema/valid-compilation-result? error-result))
      (is (schema/valid-validation-result? validation-ok))
      (is (schema/valid-validation-result? validation-issues)))))

(deftest test-enum-compilation
  (testing "Enum compilation"
    (let [input "BuildType = \"Dev\" | \"Local\" | \"Deploy\"
Config = BuildType/environment"
          result (compile-to-haxe input)]
      (is (:success result))
      (is (some #(str/includes? % "enum BuildType") (:code result)))
      (is (some #(str/includes? % "Dev") (:code result)))
      (is (some #(str/includes? % "Local") (:code result)))
      (is (some #(str/includes? % "Deploy") (:code result)))
      (is (some #(str/includes? % "public var environment: BuildType") (:code result))))))

(deftest test-enum-with-spaces
  (testing "Enum with spaces in values"
    (let [input "GameState = \"Not Started\" | \"In Progress\" | \"Game Over\""
          result (compile-to-haxe input)]
      (is (:success result))
      (is (some #(str/includes? % "enum GameState") (:code result)))
      (is (some #(str/includes? % "NotStarted") (:code result)))
      (is (some #(str/includes? % "InProgress") (:code result)))
      (is (some #(str/includes? % "GameOver") (:code result))))))

(deftest test-enum-syntax-validation
  (testing "Enum syntax validation"
    (let [valid-enum-input "BuildType = \"Dev\" | \"Local\" | \"Deploy\""
          valid-disjunction-input "BuildType = Dev | Local | Deploy"
          invalid-input "BuildType = = Dev | Local | Deploy"
          valid-enum-result (validate-wchnt-syntax valid-enum-input)
          valid-disjunction-result (validate-wchnt-syntax valid-disjunction-input)
          invalid-result (validate-wchnt-syntax invalid-input)]
      (is (:success valid-enum-result))
      (is (:success valid-disjunction-result))
      (is (not (:success invalid-result)))
      (is (contains? invalid-result :error)))))

(deftest test-dict-compilation
  (testing "Dictionary type compilation"
    (let [input "Config = {String : int}/settings"
          result (compile-to-haxe input)]
      (is (:success result))
      (is (some #(str/includes? % "public var settings: Map<String, int>") (:code result))))))

(deftest test-dict-with-enum-keys
  (testing "Dictionary with enum keys"
    (let [input "BuildType = \"Dev\" | \"Local\" | \"Deploy\"
Config = {BuildType : String}/environments"
          result (compile-to-haxe input)]
      (is (:success result))
      (is (some #(str/includes? % "enum BuildType") (:code result)))
      (is (some #(str/includes? % "public var environments: Map<BuildType, String>") (:code result))))))

(deftest test-dict-with-custom-types
  (testing "Dictionary with custom types as keys and values"
    (let [input "User = String/name int/id
UserProfile = {User : String}/profiles"
          result (compile-to-haxe input)]
      (is (:success result))
      (is (some #(str/includes? % "public var profiles: Map<User, String>") (:code result))))))

(deftest test-dict-syntax-validation
  (testing "Dictionary syntax validation"
    (let [valid-input "Config = {String : int}/settings"
          invalid-input "Config = {String int}/settings"
          valid-result (validate-wchnt-syntax valid-input)
          invalid-result (validate-wchnt-syntax invalid-input)]
      (is (:success valid-result))
      (is (not (:success invalid-result)))
      (is (contains? invalid-result :error)))))

(deftest test-mixed-dict-and-array
  (testing "Mixed dictionary and array types"
    (let [input "Game = {String : [Player]}/teams [Player]/players
Player = String/name int/score"
          result (compile-to-haxe input)]
      (is (:success result))
      (is (some #(str/includes? % "public var teams: Map<String, Array<Player>>") (:code result)))
      (is (some #(str/includes? % "public var players: Array<Player>") (:code result))))))

(deftest test-dict-default-naming
  (testing "Dictionary default naming (lowercase first letter)"
    (let [input "Config = {String : int}"
          result (compile-to-haxe input)]
      (is (:success result))
      (is (some #(str/includes? % "public var stringToInt: Map<String, int>") (:code result))))))

(run-tests)