(ns wchnt-lang.core-cli-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer :all]
            [wchnt-lang.compiler :as compiler]
            [wchnt-lang.core :as core]))

(deftest compiler-info-is-json-safe-and-describes-the-artifact
  (let [source "## Schema

```
Game = Int/score
```

## Construction

```
[:Game 0]
```

## Target

```
%openfl
%init
function init() {}
%step
function step() {}
```
"
        result (compiler/compile source)
        manifest (core/compiler-info (:value result))
        decoded (json/read-str (json/write-str manifest))]
    (is (:success result))
    (is (= "wchnt-compiler-info" (get decoded "format")))
    (is (= 1 (get decoded "version")))
    (is (= "openfl" (get decoded "target")))
    (is (= "haxe" (get decoded "backend")))
    (is (= "program" (get decoded "pageKind")))
    (is (= [{"kind" "source" "name" "Main.hx"}]
           (get decoded "outputs")))))
