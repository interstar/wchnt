(ns wchnt-lang.api-test
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [wchnt-lang.api :as api]))

(deftest test-compile-to-haxe-success
  (testing "compileToHaxe returns successful compilation"
    (let [api (wchnt_lang.WchntAPI.)
          wchnt-content "## Schema

```
Game = PlayArea Ball
PlayArea = Rect
Rect = Int/x Int/y Int/width Int/height
Ball = Int/x Int/y Int/rad
```

## Construction

```
[:Game [:PlayArea [:Rect 0 0 800 600]] [:Ball 100 100 5]]
```"
          result (.compileToHaxe api wchnt-content)]
      (is (instance? java.util.List result))
      (is (= 1 (.size result)))
      (let [haxe-code (.get result 0)]
        (is (string? haxe-code))
        (is (str/includes? haxe-code "class Game"))
        (is (str/includes? haxe-code "class PlayArea"))
        (is (str/includes? haxe-code "class Rect"))
        (is (str/includes? haxe-code "class Ball"))
        (is (str/includes? haxe-code "public static function gameFactory()"))
        (is (str/includes? haxe-code "var game = gameFactory()"))))))

(deftest test-compile-to-haxe-schema-only
  (testing "compileToHaxe handles schema-only content"
    (let [api (wchnt_lang.WchntAPI.)
          wchnt-content "## Schema

```
Config = String/settings
```

## Construction

```
```"
          result (.compileToHaxe api wchnt-content)]
      (is (instance? java.util.List result))
      (is (= 1 (.size result)))
      (let [haxe-code (.get result 0)]
        (is (string? haxe-code))
        (is (str/includes? haxe-code "class Config"))
        (is (str/includes? haxe-code "public var settings: String"))
        ;; When there's no construction, we get empty factory and main
        (is (str/includes? haxe-code ""))))))

(deftest test-compile-to-haxe-error
  (testing "compileToHaxe handles compilation errors"
    (let [api (wchnt_lang.WchntAPI.)
          wchnt-content "## Schema

```
Game = = PlayArea Ball
```

## Construction

```
[:Game [:PlayArea]]
```"
          result (.compileToHaxe api wchnt-content)]
      (is (instance? java.util.List result))
      (is (= 1 (.size result)))
      (let [error-message (.get result 0)]
        (is (string? error-message))
        (is (str/includes? error-message "error"))))))

(deftest test-api-methods
  (testing "getSchemaGrammarAsString returns schema grammar"
    (let [api (wchnt_lang.WchntAPI.)
          grammar (.getSchemaGrammarAsString api)]
      (is (string? grammar))
      (is (pos? (count grammar)))
      (is (str/includes? grammar "Schema"))))
  
  (testing "getConstructionGrammarAsString returns construction grammar"
    (let [api (wchnt_lang.WchntAPI.)
          schema "Point = Float/x Float/y"
          grammar (.getConstructionGrammarAsString api schema)]
      (is (string? grammar))
      (is (pos? (count grammar)))
      (is (str/includes? grammar "Construction"))))
  
  (testing "getConstructionGrammarAsString returns grammar regardless of schema"
    (let [api (wchnt_lang.WchntAPI.)
          invalid-schema "Invalid = syntax error"
          result (.getConstructionGrammarAsString api invalid-schema)]
      (is (string? result))
      (is (str/includes? result "Code"))
      (is (str/includes? result "MethodDefinition"))))) 