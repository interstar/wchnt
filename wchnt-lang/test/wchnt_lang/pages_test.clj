(ns wchnt-lang.pages-test
  (:require [clojure.test :refer :all]
            [wchnt-lang.compiler :as compiler]
            [wchnt-lang.mainfile :as mainfile]
            [wchnt-lang.pages :as pages]
            [wchnt-lang.pipeline :as p]))

(def shapes-lib
  "## Schema

```
Rect = Int/x Int/y Int/width Int/height
```")

(def game-using-lib
  "## Import

```
shapes-lib
```

## Schema

```
Game = Rect
```

## Construction

```
[:Game [0 0 800 600]]
```

## Target

```
%main
var g = gameFactory();
```")

(deftest resolve-import-order-finds-siblings
  (is (= ["shapes-lib"]
         (pages/resolve-import-order
          ["shapes-lib"]
          (fn [n] (when (= n "shapes-lib") shapes-lib))))))

(deftest compile-with-import-merges-schema
  (let [cargo (compiler/compile-to-ir
               game-using-lib
               {:resolve-page (fn [n]
                                (when (= n "shapes-lib") shapes-lib))})]
    (is (:success cargo))
    (let [schema-ir (get-in cargo [:stash :schema-ir])]
      (is (some #(= "Rect" (:name %)) (:assemblages schema-ir)))
      (is (some #(= "Game" (:name %)) (:assemblages schema-ir))))))

(deftest target-methods-at-in-methods-fails
  (let [content "## Schema

```
Game = Int/x
```

## Methods

```
Game::draw = { @Graphics/g |
  1
}
```"]
    (let [cargo (compiler/compile-to-ir content)]
      (is (not (:success cargo)))
      (is (re-find #"Target Methods" (first (:errors cargo)))))))

(deftest documentation-compile-is-success
  (let [cargo (compiler/compile "# Notes\n\nProse only.")]
    (is (:success cargo))
    (is (= :documentation (:page-kind (:value cargo))))))

(deftest library-compiles-without-main
  (let [cargo (compiler/compile shapes-lib)]
    (is (:success cargo))
    (is (false? (:has-construction? (:value cargo))))
    (is (= "" (:main-class (:value cargo))))
    (is (not (empty? (:classes (:value cargo)))))))
