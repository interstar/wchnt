(ns wchnt-lang.root-mutability-test
  (:require [clojure.test :refer :all]
            [wchnt-lang.compiler :as compiler]
            [wchnt-lang.interpret :as interpret]
            [wchnt-lang.pipeline :as p]))

(def root-mutability-program
  "## Schema

```
Game = Int/x
```

## Construction

```
[:Game 1]
```

## Methods

```
Game::update! = { [:Game (x + 1)] }
Game::step = { [:Game (x + 1)] }
```

## Target

```
%terminal

%main
public static function main():Void {
    var assemblage = GameAssemblage.factory();
    assemblage.update_mutates();
    assemblage = assemblage.step();
}
```
")

(deftest construction-root-is-implicitly-mutable
  (let [cargo (compiler/compile-to-ir root-mutability-program)]
    (is (:success cargo) (pr-str (:errors cargo)))
    (is (contains? (set (get-in cargo [:stash :schema-ir :mutable-classes]))
                   "Game"))
    (let [{:keys [schema-ir methods-ir root]} (interpret/load-program
                                               root-mutability-program)]
      (is (= 1 (:x @(:wchnt/cell root))))
      (is (= root (interpret/call schema-ir methods-ir root "update!" [])))
      (is (= 2 (:x @(:wchnt/cell root))))
      (is (= 3 (:x @(:wchnt/cell
                     (interpret/call schema-ir methods-ir root "step" []))))))))

(deftest haxe-can-use-either-root-update-style
  (let [cargo (compiler/compile root-mutability-program)]
    (is (:success cargo) (pr-str (:errors cargo)))
    (is (clojure.string/includes? (:main-class (:value cargo))
                                  "assemblage.update_mutates();"))
    (is (clojure.string/includes? (:main-class (:value cargo))
                                  "assemblage = assemblage.step();"))))
