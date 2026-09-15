(ns wchnt-lang.import-test
  "WCHNT membrane: ## Public, opaque handles, construction calls on import aliases."
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [wchnt-lang.compiler :as compiler]
            [wchnt-lang.interpret :as interpret]
            [wchnt-lang.mainfile :as mainfile]
            [wchnt-lang.pipeline :as p]))

(def shapes-lib
  "## Schema

```
Rect = Int/x Int/y Int/width Int/height
Game = Rect
```

## Construction

```
[:Game [:Rect 0 0 800 600]]
```

## Methods

```
Rect::area = { width * height }
Game::area = { rect.area() }
```

## Public

```
make = { Int/width, Int/height | [:Game [:Rect 0 0 width height]] }
```")

(def shapes-lib-no-public
  "## Schema

```
Rect = Int/x Int/y Int/width Int/height
```")

(def app-using-shapes
  "## Import

```
[[shapes]] as shapes
```

## Schema

```
App = String/title @Game
```

## Construction

```
[:App \"demo\" shapes.make(800, 600)]
```

## Methods

```
App::size = { game.area() }
```

## Target

```
%terminal

%main
var a = AppAssemblage.factory();
```")

(defn- compile-app
  [app]
  (compiler/compile-to-ir
   app
   {:resolve-page (fn [n] (when (= n "shapes") shapes-lib))}))

(defn- err
  [cargo]
  (first (:errors cargo)))

(deftest parse-public-and-import-alias
  (testing "Public entries are unqualified methods or published type names"
    (is (= [{:method "make"} {:type "Shape"}]
           (mainfile/parse-public-names "make = {...}\nShape\n"))))
  (testing "Import specs take as aliases"
    (is (= [{:page "shapes" :alias "shapes"}
            {:page "shapes-lib" :alias "lib"}]
           (mainfile/parse-import-specs "[[shapes]] as shapes\nshapes-lib as lib\n"))))
  (testing "Bare identifier page name is the default alias"
    (is (= [{:page "bounce" :alias "bounce"}]
           (mainfile/parse-import-specs "[[bounce]]\n"))))
  (testing "Hyphenated page names need as"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"as"
                          (mainfile/parse-import-specs "shapes-lib\n")))))

(deftest import-without-public-fails
  (let [cargo (compiler/compile-to-ir
               "## Import\n\n```\nshapes\n```\n\n## Schema\n\n```\nApp = Int/x\n```"
               {:resolve-page (fn [n] (when (= n "shapes") shapes-lib-no-public))})]
    (is (not (:success cargo)))
    (is (re-find #"Public" (err cargo)))))

(deftest import-cannot-use-class-as-local-type
  (let [cargo (compile-app
               "## Import\n\n```\n[[shapes]] as shapes\n```\n\n## Schema\n\n```\nApp = Game\n```")]
    (is (not (:success cargo)))
    (is (re-find #"@Game|handle" (err cargo)))))

(deftest import-happy-path-constructs-and-calls
  (let [cargo (compile-app app-using-shapes)]
    (is (:success cargo) (err cargo))
    (let [loaded (interpret/load-program
                  app-using-shapes
                  {:resolve-page (fn [n] (when (= n "shapes") shapes-lib))})
          size (interpret/call (:schema-ir loaded) (:methods-ir loaded)
                               (:root loaded) "size" [])]
      (is (= 480000 size)))))

(deftest import-field-access-fails
  (let [cargo (compile-app
               (str/replace app-using-shapes
                            "App::size = { game.area() }"
                            "App::size = { game.rect.width }"))]
    (is (not (:success cargo)))
    (is (re-find #"handle|field" (err cargo)))))

(deftest import-construct-of-handle-fails
  (let [cargo (compile-app
               (str/replace app-using-shapes
                            "App::size = { game.area() }"
                            "App::size = { [:Game [:Rect 0 0 1 1]] }"))]
    (is (not (:success cargo)))
    (is (re-find #"construct|handle|Unknown class" (err cargo)))))

(deftest import-private-method-fails
  (let [lib (str/replace shapes-lib "Game::area = { rect.area() }"
                         "Game::area = { rect.area() }\nGame::secret = { 1 }")
        cargo (compiler/compile-to-ir
               (str/replace app-using-shapes
                            "App::size = { game.area() }"
                            "App::size = { game.secret() }")
               {:resolve-page (fn [n] (when (= n "shapes") lib))})]
    (is (:success cargo) (err cargo))))

(deftest public-unknown-method-fails
  (let [lib (str/replace shapes-lib
                         "make = { Int/width, Int/height | [:Game [:Rect 0 0 width height]] }"
                         "missing = { Int/width, Int/height | [:Game [:Rect 0 0 width height]] }")
        cargo (compiler/compile-to-ir
               app-using-shapes
               {:resolve-page (fn [n] (when (= n "shapes") lib))})]
    (is (not (:success cargo)))
    (is (re-find #"Public|missing" (err cargo)))))

(deftest haxe-factory-calls-static-introducer
  (let [cargo (compiler/compile
               app-using-shapes
               {:resolve-page (fn [n] (when (= n "shapes") shapes-lib))})]
    (is (:success cargo) (err cargo))
    (let [classes (:classes (:value cargo))
          factory (:classes (:value cargo))]
      (is (str/includes? classes "public static function make("))
      (is (not (str/includes? classes "\n    public function make(")))
    (is (str/includes? classes "GameAssemblage.make(800, 600)")))))

(def shape-box
  "## Schema

```
Game = [Shape]/shapes
Shape = Circle | Square
Circle = Int/x
Square = Int/x
```

## Construction

```
[:Game [:Array/Shape [:Circle 0]]]
```

## Methods

```
Shape::step = { | } -> Shape
Circle::step = { [:Circle (x + 1)] }
Square::step = { [:Square (x + 1)] }
Game::addShape = { Shape/s | [:Game shapes.cons(s)] }
Game::count = { shapes.length() }
```

## Public

```
make = { [:Game [:Array/Shape [:Circle 0]]] }
Shape
```")

(def sky-with-pentagon
  "## Import

```
[[shapes]] as flying
```

## Schema

```
Sky = @Game
Pentagon : Shape = Int/x
```

## Construction

```
[:Sky flying.make().addShape([:Pentagon 9])]
```

## Methods

```
Sky::count = { game.count() }
Pentagon::step = { [:Pentagon (x + 1)] }
```

## Target

```
%terminal

%main
var sky = SkyAssemblage.factory();
```")

(deftest import-published-interface-can-be-implemented
  (testing "B implements a published Shape and passes it to addShape"
    (let [opts {:resolve-page (fn [n] (when (= n "shapes") shape-box))}
          cargo (compiler/compile-to-ir sky-with-pentagon opts)]
      (is (:success cargo) (err cargo))
      (let [loaded (interpret/load-program sky-with-pentagon opts)
            n (interpret/call (:schema-ir loaded) (:methods-ir loaded)
                              (:root loaded) "count" [])]
        (is (= 2 n)))))
  (testing "Haxe emits Pentagon implements Shape and addShape"
    (let [cargo (compiler/compile
                 sky-with-pentagon
                 {:resolve-page (fn [n] (when (= n "shapes") shape-box))})]
      (is (:success cargo) (err cargo))
      (let [cls (:classes (:value cargo))
            factory (:classes (:value cargo))]
        (is (str/includes? cls "class Pentagon implements Shape"))
        (is (str/includes? cls "public function addShape("))
        (is (str/includes? factory "GameAssemblage.make()"))
        (is (str/includes? factory "addShape(new Pentagon(9))"))))))
