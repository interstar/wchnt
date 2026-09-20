(ns wchnt-lang.examples-test
  "Compile every examples/*.wcn through the WCHNT pipeline and check generated
   Haxe strings. Does not invoke the Haxe compiler; go_all_examples.sh does that."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer :all]
            [wchnt-lang.compiler :as compiler]
            [wchnt-lang.interpret :as interpret]
            [wchnt-lang.pages :as pages]
            [wchnt-lang.pipeline :as p]))

(defn- example-files
  []
  (->> (file-seq (io/file "examples"))
       (filter #(.isFile %))
       (filter #(str/ends-with? (.getName %) ".wcn"))
       (sort-by #(.getName %))))

(def example-opts
  {:resolve-page (pages/sibling-resolve "examples")})

(defn- compile-example
  [filename]
  (compiler/compile (slurp (str "examples/" filename)) example-opts))

(defn- assert-compiles
  [filename]
  (let [cargo (compile-example filename)]
    (is (p/is-cargo? cargo) (str filename " should return a cargo"))
    (is (:success cargo)
        (str filename " should compile: " (first (:errors cargo))))
    cargo))

(defn- factory
  [cargo]
  (get-in cargo [:value :classes] ""))

(defn- classes
  [cargo]
  (get-in cargo [:value :classes] ""))

(defn- host
  [cargo]
  (get-in cargo [:value :host]))

(defn- main
  [cargo]
  (get-in cargo [:value :main] ""))

(defn- main-class
  [cargo]
  (get-in cargo [:value :main-class] ""))

(deftest every-example-file-compiles
  (testing "each examples/*.wcn compiles to Haxe strings"
    (let [files (example-files)]
      (is (seq files) "examples/ should contain .wcn files")
      (doseq [file files]
        (let [name (.getName file)
              text (slurp file)]
          (if (re-find #"(?m)^%(canvas|cli-live|testharness-live)\s*$" text)
            (let [cargo (compiler/compile-to-ir text example-opts)]
              (is (:success cargo)
                  (str name " live host should compile to IR: " (first (:errors cargo))))
              (is (#{"canvas" "cli-live" "testharness-live"}
                   (get-in cargo [:stash :target-ir :host]))))
            (let [cargo (compiler/compile text example-opts)]
              (is (:success cargo)
                  (str name " should compile: " (first (:errors cargo))))
              (when (get-in cargo [:value :has-construction?])
                (is (str/includes? text "## Target")
                    (str name " with construction should have a Target section"))
                (cond
                  (= "testharness" (host cargo))
                  (do
                    (is (str/includes? (get-in cargo [:value :preamble] "") "class WCHNTUnitTests")
                        (str name " %testharness should emit WCHNTUnitTests"))
                    (is (str/includes? (main-class cargo) "function main")
                        (str name " %testharness should emit Main.main"))
                    (is (str/includes? (main-class cargo) "__fixture")
                        (str name " %testharness should emit fixture helpers")))

                  :else
                  (do
                    (is (str/includes? (factory cargo) "Assemblage")
                        (str name " with construction should emit a factory"))
                    (is (str/includes? (get-in cargo [:value :preamble] "") "class WCHNTConsole")
                        (str name " Haxe host should emit WCHNTConsole"))
                    (is (str/includes? (get-in cargo [:value :preamble] "") "class WCHNTMaths")
                        (str name " Haxe host should emit WCHNTMaths"))
                    (is (str/includes? (main-class cargo) "wchntConsole")
                        (str name " Haxe host should bind wchntConsole"))
                    (is (str/includes? (main-class cargo) "wchntMaths")
                        (str name " Haxe host should bind wchntMaths"))
                    (cond
                      (= "openfl" (host cargo))
                      (do
                        (is (str/includes? (main-class cargo) "extends Sprite")
                            (str name " OpenFL Target should emit Main extends Sprite"))
                        (is (str/includes? (main-class cargo) "function init")
                            (str name " should emit %init"))
                        (is (str/includes? (main-class cargo) "function step")
                            (str name " should emit %step")))

                      (= "cli" (host cargo))
                      (do
                        (is (str/includes? (main-class cargo) "Sys.stdin")
                            (str name " %cli Target should read stdin"))
                        (is (str/includes? (main-class cargo) "function init")
                            (str name " should emit %init"))
                        (is (str/includes? (main-class cargo) "function step")
                            (str name " should emit %step"))
                        (is (str/includes? (main-class cargo) "function main")
                            (str name " should emit a main loop")))

                      :else
                      (is (str/includes? (main cargo) "function main")
                          (str name " should emit %main from Target")))))))))))))

(deftest nested-untagged-components
  (testing "construction_simple nests PlayArea/Rect and Ball under Game"
    (let [cargo (assert-compiles "construction_simple.wcn")
          f (factory cargo)]
      (is (str/includes? f "new PlayArea(new Rect(0, 0, 800, 600))"))
      (is (str/includes? f "new Ball(100, 100, 1, 1, 5)"))
      (is (str/includes? f "new Game(obj1, obj2)")))))

(deftest tagged-object-arrays
  (testing "construction_arrays builds Array/Person then School"
    (let [f (factory (assert-compiles "construction_arrays.wcn"))]
      (is (str/includes? f "[new Person(\"John\"), new Person(\"Jane\")]"))
      (is (str/includes? f "new School(obj1)")))))

(deftest enum-keyed-maps-and-let-bindings
  (testing "construction_maps binds a Direction->String map then App"
    (let [cargo (assert-compiles "construction_maps.wcn")
          f (factory cargo)
          cls (classes cargo)]
      (is (str/includes? cls "enum Direction"))
      (is (str/includes? cls "Map<Direction, String>"))
      (is (str/includes? f "Up => \"jump\""))
      (is (str/includes? f "new Config(obj1)"))
      (is (str/includes? f "new App(\"Hello\", obj2)")))))

(deftest shared-array-let-binding
  (testing "construction_multi_step reuses people in School and Team"
    (let [f (factory (assert-compiles "construction_multi_step.wcn"))]
      (is (str/includes? f "new Person(\"John Smith\", \"1 The Avenue\")"))
      (is (str/includes? f "new School(obj1)"))
      (is (str/includes? f "new Team(obj1)"))
      (is (str/includes? f "new Town(obj2, obj3)")))))

(deftest untagged-primitive-arrays
  (testing "string_array_test accepts a bare string vector as [String]"
    (let [f (factory (assert-compiles "string_array_test.wcn"))]
      (is (str/includes? f "[\"hello\", \"world\", \"test\"]"))
      (is (str/includes? f "new StringList(obj1)")))))

(deftest book-array-construction
  (testing "austen builds a DB of tagged Book literals"
    (let [f (factory (assert-compiles "austen.wcn"))]
      (is (str/includes? f "new Book(\"Pride and Prejudice\", \"Jane Austen\")"))
      (is (str/includes? f "new Book(\"Northanger Abbey\", \"Jane Austen\")"))
      (is (str/includes? f "new DB(obj1)")))))

(deftest enum-constructor-argument
  (testing "test_enum passes a bare enum value to Config"
    (let [cargo (assert-compiles "test_enum.wcn")
          f (factory cargo)
          cls (classes cargo)]
      (is (str/includes? cls "enum BuildType"))
      (is (str/includes? f "new Config(Local)")))))

(deftest context-sigil-generates-setContext
  (testing "test_context emits Engine.setContext and factory wires it on first build"
    (let [cargo (assert-compiles "test_context.wcn")
          f (factory cargo)
          cls (classes cargo)]
      (is (str/includes? cls "public var theCar: Car;"))
      (is (str/includes? cls "public function setContext(c: Car)"))
      (is (str/includes? f "new Engine(4)"))
      (is (str/includes? f "new Car(obj1, \"Toyota\")"))
      (is (str/includes? f ".setContext(")))))

(deftest context-sigil-example-duplicate
  (testing "test_sigil is the same :Engine construction as test_context"
    (let [f (factory (assert-compiles "test_sigil.wcn"))]
      (is (str/includes? f "new Engine(4)"))
      (is (str/includes? f "new Car(obj1, \"Toyota\")")))))

(deftest enum-values-with-spaces
  (testing "test_spaces is schema-only and camel-cases spaced enum labels"
    (let [cargo (assert-compiles "test_spaces.wcn")
          cls (classes cargo)]
      (is (not (str/includes? cls "Assemblage")))
      (is (str/includes? cls "enum GameState"))
      (is (str/includes? cls "NotStarted;"))
      (is (str/includes? cls "InProgress;"))
      (is (str/includes? cls "GameOver;")))))

(deftest schema-only-map-field
  (testing "test_dict_minimal is schema-only Map<String, Int>"
    (let [cargo (assert-compiles "test_dict_minimal.wcn")
          cls (classes cargo)]
      (is (not (str/includes? cls "Assemblage")))
      (is (str/includes? cls "class Config"))
      (is (str/includes? cls "Map<String, Int>")))))

(deftest inline-string-and-enum-maps
  (testing "test_dict constructs nested Map literals including enum keys"
    (let [f (factory (assert-compiles "test_dict.wcn"))]
      (is (str/includes? f "new Config([\"0\" => 0, \"1\" => 1])"))
      (is (str/includes? f "new Config2([Dev => \"dev\", Local => \"local\", Deploy => \"deploy\"])")))))

(deftest nested-reactive-subscribe
  (testing "test_reactive wires Game = $Time with subscribe after construction"
    (let [cargo (assert-compiles "test_reactive.wcn")
          cls (classes cargo)
          f (factory cargo)]
      (is (str/includes? cls "public function subscribe(subscriber: Dynamic): Void"))
      (is (re-find #"obj\d+\.time\.subscribe\(obj\d+\);" f)))))

(deftest shared-reactive-let-binding
  (testing "test_reactive_shared binds Time once then Game t"
    (let [f (factory (assert-compiles "test_reactive_shared.wcn"))]
      (is (str/includes? f "new Time(0)"))
      (is (str/includes? f "new Game(obj1)"))
      (is (str/includes? f "obj2.time.subscribe(obj2)")))))

(deftest reactive-mixed-with-nested-ordinary
  (testing "test_reactive_nested mixes PlayArea/Rect with $Time"
    (let [f (factory (assert-compiles "test_reactive_nested.wcn"))]
      (is (str/includes? f "new PlayArea(new Rect(0, 0, 800, 600))"))
      (is (str/includes? f "new Time(0)"))
      (is (str/includes? f "new Game(obj1, obj2)"))
      (is (str/includes? f "obj3.time.subscribe(obj3)")))))

(deftest two-subscribers-share-one-time
  (testing "test_reactive_two_subscribers builds Time first, both subscribe"
    (let [f (factory (assert-compiles "test_reactive_two_subscribers.wcn"))]
      (is (= 4 (count (re-seq #"\.time\.subscribe\(" f))))
      (is (re-find #"(?s)new Time\(0\).*new Scene\(" f)))))

(deftest complex-game-bindings-and-sum-types
  (testing "test.wcn maps lets, nested Players, and Football as Ball"
    (let [cargo (assert-compiles "test.wcn")
          f (factory cargo)
          cls (classes cargo)]
      (is (not (re-find #"[^a-zA-Z]ps[^a-zA-Z]" f)))
      (is (str/includes? f "new Player(40, 90, \"Bob\")"))
      (is (str/includes? f "new Football(0, 0, 5)"))
      (is (str/includes? cls "interface Ball"))
      (is (str/includes? cls "helper:IWCHNTHelper")))))

(deftest reaction-arithmetic-methods
  (testing "test_reaction_arithmetic.wcn emits area, doubleWidth, and move"
    (let [classes (classes (assert-compiles "test_reaction_arithmetic.wcn"))]
      (is (str/includes? classes "public function area(): Int"))
      (is (str/includes? classes "public function doubleWidth(): Rect"))
      (is (str/includes? classes "public function move(): Ball"))
      (is (str/includes? classes "public function widen(): Game"))
      (is (str/includes? classes "this.x % this.width")))))

(deftest reaction-logic-methods
  (testing "test_reaction_logic.wcn emits movingRight and contains"
    (let [classes (classes (assert-compiles "test_reaction_logic.wcn"))]
      (is (str/includes? classes "public function movingRight(): Bool"))
      (is (str/includes? classes "public function contains(px:Int, py:Int): Bool")))))

(deftest reaction-let-bindings
  (testing "test_reaction_lets.wcn emits immutable locals then return"
    (let [classes (classes (assert-compiles "test_reaction_lets.wcn"))]
      (is (str/includes? classes "var nx = this.x + this.dx;"))
      (is (str/includes? classes "return new Ball(nx, ny, this.dx, this.dy, this.rad);"))
      (is (str/includes? classes "return a;")))))

(deftest reaction-field-paths
  (testing "test_reaction_paths.wcn walks playArea.rect and ball"
    (let [classes (classes (assert-compiles "test_reaction_paths.wcn"))]
      (is (str/includes? classes "this.ball.x"))
      (is (str/includes? classes "this.playArea.rect.width"))))

  (testing "test_reaction_context_path.wcn reads theCar.model"
    (let [classes (classes (assert-compiles "test_reaction_context_path.wcn"))]
      (is (str/includes? classes "return this.theCar.model;")))))

(deftest reaction-method-calls
  (testing "test_reaction_calls.wcn emits this.move and playArea.rect.area"
    (let [classes (classes (assert-compiles "test_reaction_calls.wcn"))]
      (is (str/includes? classes "return this.move();"))
      (is (str/includes? classes "this.playArea.rect.area()"))
      (is (str/includes? classes "this.playArea.rect.contains(this.ball.x, this.ball.y)")))))

(deftest reaction-if-expression
  (testing "test_reaction_if.wcn emits a Haxe if expression"
    (let [classes (classes (assert-compiles "test_reaction_if.wcn"))]
      (is (str/includes? classes "if (this.dx < 0)"))
      (is (str/includes? classes "-this.dx")))))

(deftest reaction-collection-methods
  (testing "test_reaction_collections.wcn emits map filter fold"
    (let [classes (classes (assert-compiles "test_reaction_collections.wcn"))]
      (is (str/includes? classes "this.players.map"))
      (is (str/includes? classes "this.players.filter"))
      (is (str/includes? classes "Lambda.fold(this.players")))))

(deftest bounce-example
  (testing "bounce.wcn uses if, paths, and method calls"
    (let [cargo (assert-compiles "bounce.wcn")
          classes (classes cargo)
          main (main cargo)]
      (is (str/includes? classes "this.ball.x < r.x"))
      (is (str/includes? classes "this.bounceDx()"))
      (is (str/includes? main "assemblage.step()"))
      (is (str/includes? main "assemblage.playAreaSize()"))))

(deftest bounce-openfl-example
  (testing "bounce_openfl.wcn keeps the bounce model and draws from %init/%step"
    (let [cargo (assert-compiles "bounce_openfl.wcn")
          classes (classes cargo)
          main-class (main-class cargo)
          preamble (get-in cargo [:value :preamble] "")]
      (is (= "openfl" (host cargo)))
      (is (str/includes? classes "this.bounceDx()"))
      (is (str/includes? preamble "openfl.display.Sprite"))
      (is (str/includes? preamble "class WCHNTConsole"))
      (is (str/includes? preamble "#if sys"))
      (is (str/includes? main-class "wchntConsole"))
      (is (str/includes? main-class "extends Sprite"))
      (is (str/includes? main-class "function init"))
      (is (str/includes? main-class "function step"))
      (is (str/includes? main-class "assemblage.step()"))
      (is (str/includes? main-class "drawCircle"))
      (is (str/includes? main-class "Event.ENTER_FRAME")))))

(deftest bounce-openfl-time-example
  (testing "bounce_openfl_time.wcn ticks $Time; Game moves via notify, not Target"
    (let [cargo (assert-compiles "bounce_openfl_time.wcn")
          classes (classes cargo)
          factory (factory cargo)
          main-class (main-class cargo)]
      (is (= "openfl" (host cargo)))
      (is (str/includes? classes "this.notifySubscribers();"))
      (is (str/includes? classes "this.t = this.t + 1;"))
      (is (re-find #"\.time\.subscribe\(" factory))
      (is (str/includes? main-class "assemblage.time.update_mutates();"))
      (is (not (str/includes? main-class "assemblage.step()")))
      (is (not (str/includes? main-class "assemblage.update_mutates();")))
      (is (str/includes? main-class "drawCircle"))
      (is (str/includes? main-class "Event.ENTER_FRAME"))))))

(deftest bounce-openfl-compiles-to-ir
  (testing "compile-to-ir yields schema, methods, and construction without Haxe"
    (let [cargo (compiler/compile-to-ir (slurp "examples/bounce_openfl.wcn"))]
      (is (:success cargo) (str (first (:errors cargo))))
      (is (get-in cargo [:stash :schema-ir]))
      (is (get-in cargo [:stash :construction-ir]))
      (is (seq (get-in cargo [:stash :methods-ir])))
      (is (= "openfl" (get-in cargo [:stash :target-ir :host])))
      (is (nil? (get-in cargo [:stash :schema-haxe]))))))

(deftest bounce-canvas-example
  (testing "bounce_canvas.wcn is IR-only; Haxe backend rejects %canvas"
    (let [text (slurp "live-examples/bounce_canvas.wcn")
          ir (compiler/compile-to-ir text)
          haxe (compiler/compile text)]
      (is (:success ir) (str (first (:errors ir))))
      (is (= "canvas" (get-in ir [:stash :target-ir :host])))
      (is (get-in ir [:stash :construction-ir]))
      (is (not (:success haxe)))
      (is (re-find #"%canvas" (or (first (:errors haxe)) ""))))))

(deftest shapes-openfl-example
  (testing "shapes_openfl.wcn moves Array<Shape> via interface step and draws variants"
    (let [cargo (assert-compiles "shapes_openfl.wcn")
          classes (classes cargo)
          main-class (main-class cargo)]
      (is (= "openfl" (host cargo)))
      (is (str/includes? classes "interface Shape"))
      (is (str/includes? classes "public function step(bounds:Rect): Shape;"))
      (is (str/includes? classes "public function draw(g:WCHNTGraphics): WCHNTGraphics;"))
      (is (str/includes? classes "public function draw(g:WCHNTGraphics): WCHNTGraphics {"))
      (is (str/includes? classes "drawCircle(this.x, this.y, this.radius)"))
      (is (str/includes? main-class "s.draw(wchntGraphics)"))
      (is (not (str/includes? main-class "Std.isOfType(s, Circle)")))
      (is (str/includes? main-class "assemblage.time.update_mutates();"))
      (is (not (str/includes? main-class "assemblage.update_mutates();"))))))

(deftest shapes-canvas-registers-graphics-external
  (testing "shapes_canvas Methods register Graphics for the interpreter"
    (let [cargo (compiler/compile-to-ir (slurp "live-examples/shapes_canvas.wcn"))]
      (is (:success cargo))
      (is (contains? (:external-types (get-in cargo [:stash :schema-ir])) "WCHNTGraphics")))))


(deftest square-openfl-example
  (testing "square_openfl.wcn injects >Keys from the OpenFL keyboard"
    (let [cargo (assert-compiles "square_openfl.wcn")
          classes (classes cargo)
          main-class (main-class cargo)
          preamble (get-in cargo [:value :preamble] "")]
      (is (= "openfl" (host cargo)))
      (is (str/includes? classes "public function inject(left:Bool, right:Bool, up:Bool, down:Bool): Keys"))
      (is (str/includes? classes "return this.update_mutates();"))
      (is (str/includes? preamble "openfl.events.KeyboardEvent"))
      (is (str/includes? preamble "openfl.ui.Keyboard"))
      (is (str/includes? main-class "assemblage.keys.inject("))
      (is (str/includes? main-class "Keyboard.LEFT"))
      (is (not (str/includes? main-class "assemblage.update_mutates();"))))))

(deftest square-canvas-example
  (testing "square_canvas.wcn is IR-only; Target injects harness input.keys"
    (let [text (slurp "live-examples/square_canvas.wcn")
          ir (compiler/compile-to-ir text)
          haxe (compiler/compile text)]
      (is (:success ir) (str (first (:errors ir))))
      (is (= "canvas" (get-in ir [:stash :target-ir :host])))
      (is (= ["Keys"] (get-in ir [:stash :schema-ir :mailbox-classes])))
      (is (re-find #"k\[\"ArrowLeft\"\]" (get-in ir [:stash :target-ir :step :haxe])))
      (is (not (:success haxe)))
      (is (re-find #"%canvas" (or (first (:errors haxe)) ""))))))

(deftest bounce-loop-example
  (testing "bounce_loop.wcn ticks $Time and rewrites Game in place"
    (let [cargo (assert-compiles "bounce_loop.wcn")
          classes (classes cargo)
          main (main cargo)]
      (is (str/includes? classes "this.t = this.t + 1;"))
      (is (str/includes? classes "this.notifySubscribers();"))
      (is (str/includes? classes "this.ball = moved;"))
      (is (str/includes? classes "return this;"))
      (is (str/includes? main "assemblage.time.update_mutates();"))
      (is (str/includes? main "for (i in 0...10)"))
      (is (not (str/includes? main "assemblage.update_mutates();")))
      (is (not (str/includes? main "assemblage.bounceDx"))))))

(deftest combinators-cli-example
  (testing "combinators_cli.wcn println's WCHNT values; console owns toConstruction"
    (let [cargo (assert-compiles "combinators_cli.wcn")
          preamble (get-in cargo [:value :preamble] "")
          main-class (main-class cargo)]
      (is (= "cli" (host cargo)))
      (is (str/includes? preamble "public function format(value:Dynamic):String"))
      (is (str/includes? preamble "toConstruction(0, helper)"))
      (is (str/includes? preamble "arrayToConstruction"))
      (is (str/includes? preamble "mapToConstruction"))
      (is (str/includes? main-class "wchntConsole.println(team.names())"))
      (is (str/includes? main-class "wchntConsole.println(team.stats())"))
      (is (not (str/includes? main-class "toConstruction(0, helper)")))
      (is (not (str/includes? main-class "arrayToConstruction"))))))

(deftest maths-example
  (testing "maths.wcn injects wchntMaths; Target does not call randInt itself"
    (let [cargo (assert-compiles "maths.wcn")
          preamble (get-in cargo [:value :preamble] "")
          main-class (main-class cargo)
          factory (factory cargo)]
      (is (= "terminal" (host cargo)))
      (is (str/includes? preamble "class WCHNTMaths"))
      (is (str/includes? factory "factory(maths: WCHNTMaths)"))
      (is (str/includes? main-class "RollAssemblage.factory(wchntMaths)"))
      (is (str/includes? main-class "wchntConsole.println(assemblage.once())"))
      (is (not (str/includes? (main cargo) "Math.random"))))))

(deftest maths-cli-live-example
  (testing "live-examples/maths_cli.wcn is IR-only; Haxe rejects %cli-live"
    (let [text (slurp "live-examples/maths_cli.wcn")
          ir (compiler/compile-to-ir text)
          haxe (compiler/compile text)]
      (is (:success ir) (str (first (:errors ir))))
      (is (= "cli-live" (get-in ir [:stash :target-ir :host])))
      (is (not (:success haxe)))
      (is (re-find #"%cli-live" (or (first (:errors haxe)) ""))))))

(deftest combinators-cli-live-example
  (testing "live-examples/combinators_cli.wcn is IR-only; Haxe rejects %cli-live"
    (let [text (slurp "live-examples/combinators_cli.wcn")
          ir (compiler/compile-to-ir text)
          haxe (compiler/compile text)]
      (is (:success ir) (str (first (:errors ir))))
      (is (= "cli-live" (get-in ir [:stash :target-ir :host])))
      (is (not (:success haxe)))
      (is (re-find #"%cli-live" (or (first (:errors haxe)) ""))))))

(deftest adventure-cli-example
  (testing "adventure.wcn is a %cli host that prints through wchntConsole"
    (let [cargo (assert-compiles "adventure.wcn")
          classes (classes cargo)
          main-class (main-class cargo)
          preamble (get-in cargo [:value :preamble] "")]
      (is (= "cli" (host cargo)))
      (is (str/includes? classes "class WorldMap"))
      (is (str/includes? classes "class Location"))
      (is (str/includes? classes "enum LocationId"))
      (is (str/includes? classes "enum Direction"))
      (is (str/includes? classes "Map<LocationId, Location>"))
      (is (str/includes? classes "Map<Direction, LocationId>"))
      (is (str/includes? classes "this.worldMap"))
      (is (str/includes? classes "WCHNTRuntime.mapExists"))
      (is (str/includes? classes "WCHNTRuntime.mapGetDefault"))
      (is (str/includes? classes "WCHNTRuntime.tpl"))
      (is (str/includes? classes "You are in {place}."))
      (is (str/includes? classes "You can't go that way."))
      (is (not (str/includes? classes "Map<String, Location>")))
      (is (not (str/includes? classes "this.world;")))
      (is (str/includes? preamble "class WCHNTConsole"))
      (is (str/includes? main-class "wchntConsole"))
      (is (str/includes? main-class "wchntConsole.println"))
      (is (not (str/includes? main-class "Sys.println")))
      (is (str/includes? main-class "Sys.stdin"))
      (is (str/includes? main-class "app.step(line)"))
      (is (str/includes? main-class "function init"))
      (is (str/includes? main-class "function step")))))

(deftest adventure-cli-live-example
  (testing "adventure_cli.wcn is IR-only; Haxe backend rejects %cli-live"
    (let [text (slurp "live-examples/adventure_cli.wcn")
          ir (compiler/compile-to-ir text)
          haxe (compiler/compile text)]
      (is (:success ir) (str (first (:errors ir))))
      (is (= "cli-live" (get-in ir [:stash :target-ir :host])))
      (is (get-in ir [:stash :construction-ir]))
      (is (not (:success haxe)))
      (is (re-find #"%cli-live" (or (first (:errors haxe)) ""))))))

(deftest target-trace-example
  (testing "test_target_trace.wcn binds %trace in Target and calls it from Methods"
    (let [cargo (assert-compiles "test_target_trace.wcn")
          classes (classes cargo)
          main (main cargo)
          main-class (get-in cargo [:value :main-class])]
      (is (str/includes? classes "Main.wchnt_trace("))
      (is (str/includes? main-class "function wchnt_trace"))
      (is (str/includes? main "assemblage.area()"))
      (is (str/includes? main "function main")))))

(deftest combinators-example
  (testing "combinators.wcn println's through wchntConsole; Target skips helpers"
    (let [cargo (assert-compiles "combinators.wcn")
          classes (classes cargo)
          main (main cargo)
          main-class (main-class cargo)
          preamble (get-in cargo [:value :preamble] "")]
      (is (= "terminal" (host cargo)))
      (is (str/includes? preamble "class WCHNTConsole"))
      (is (str/includes? preamble "#if sys"))
      (is (str/includes? preamble "haxe.Log.trace"))
      (is (str/includes? main-class "public var wchntConsole"))
      (is (str/includes? main "wchntConsole.println(assemblage.names())"))
      (is (str/includes? main "wchntConsole.println(assemblage.stats())"))
      (is (str/includes? main "wchntConsole.println(assemblage.hot())"))
      (is (not (str/includes? main "arrayToConstruction")))
      (is (not (str/includes? main "mapToConstruction")))
      (is (not (str/includes? main "toConstruction(0, helper)")))
      (is (str/includes? classes "this.players.map"))
      (is (str/includes? classes "this.players.filter"))
      (is (str/includes? classes "Lambda.fold(this.players"))
      (is (str/includes? classes "WCHNTRuntime.mapMap(this.scores"))
      (is (str/includes? classes "WCHNTRuntime.mapFilter(this.scores"))
      (is (str/includes? classes "WCHNTRuntime.mapFold(this.scores"))
      (is (str/includes? classes "new Summary(0, 0)"))
      (is (str/includes? classes "function(p:Player, acc:Summary):Summary"))
      (is (str/includes? classes "acc.total"))
      (is (str/includes? classes "function(p:Player, acc:String):String"))
      (is (str/includes? classes "acc + p.name")))))

(deftest team-stats-example
  (testing "team_stats.wcn uses map, filter, fold, and if"
    (let [cargo (assert-compiles "team_stats.wcn")
          classes (classes cargo)
          main (main cargo)]
      (is (str/includes? classes "this.players.map"))
      (is (str/includes? classes "p.label()"))
      (is (str/includes? classes "this.players.filter"))
      (is (str/includes? classes "Lambda.fold(this.players"))
      (is (str/includes? main "assemblage.total()"))
      (is (str/includes? main "assemblage.labels()")))))

(deftest reaction-strings-example
  (testing "test_reaction_strings.wcn emits length and concat"
    (let [classes (classes (assert-compiles "test_reaction_strings.wcn"))]
      (is (str/includes? classes "this.name.length"))
      (is (str/includes? classes "this.name + \":\""))
      (is (str/includes? classes "WCHNTRuntime.substring(this.name, 0, 1)")))))

(deftest roster-example
  (testing "roster.wcn uses cons, put, and collection literals"
    (let [cargo (assert-compiles "roster.wcn")
          classes (classes cargo)
          main (main cargo)]
      (is (str/includes? classes "[p].concat(this.players)"))
      (is (str/includes? classes "WCHNTRuntime.mapPut(this.scores"))
      (is (str/includes? classes "new Array<Player>()"))
      (is (str/includes? classes "new Map<String, Int>()"))
      (is (str/includes? classes "WCHNTRuntime.arrayHead(this.players)"))
      (is (str/includes? classes "WCHNTRuntime.arrayTail(this.players)"))
      (is (str/includes? classes "WCHNTRuntime.mapGet(this.scores"))
      (is (str/includes? classes "WCHNTRuntime.mapRemove(this.scores"))
      (is (str/includes? classes "WCHNTRuntime.times("))
      (is (str/includes? main "assemblage.withDi()"))
      (is (str/includes? main "assemblage.fresh()"))
      (is (str/includes? main "assemblage.captain()")))))

(deftest import-public-quest-example
  (testing "importB constructs a Quest handle from importA and reads Public data"
    (let [cargo (assert-compiles "importB.wcn")
          f (factory cargo)
          cls (classes cargo)
          loaded (interpret/load-program
                  (slurp "examples/importB.wcn")
                  example-opts)
          purse (interpret/call (:schema-ir loaded) (:methods-ir loaded)
                                (:root loaded) "purse" [])]
      (is (str/includes? f "QuestAssemblage.make("))
      (is (str/includes? cls "public static function make("))
      (is (= 420 purse)))))

(deftest writepaths-deep-example
  (testing "writepaths.wcn emits a six-level reconstruct and keeps the ocean sibling"
    (let [cargo (assert-compiles "writepaths.wcn")
          cls (classes cargo)
          main (main cargo)
          loaded (interpret/load-program (slurp "examples/writepaths.wcn"))
          {:keys [schema-ir methods-ir root]} loaded
          fountain-path ["continent" "country" "capital" "plaza" "fountain"]
          walk (fn [obj fields]
                 (reduce interpret/get-field obj fields))
          more (interpret/call schema-ir methods-ir root "moreJets" [])
          renamed (interpret/call schema-ir methods-ir root "renameFountain" ["Arethusa"])
          deep (interpret/call schema-ir methods-ir root "deeperOcean" [])
          resized (interpret/call schema-ir methods-ir root "resize" [])
          boosted (interpret/call schema-ir methods-ir root "boostFountain"
                                  [(walk root fountain-path)])]
      (is (str/includes? cls "new Continent(new Country(new Capital(new Plaza(new Fountain("))
      (is (str/includes? cls "this.ocean"))
      (is (str/includes? main "assemblage.moreJets()"))
      (is (= 8 (interpret/get-field (walk more fountain-path) "jets")))
      (is (= "Triton" (interpret/get-field (walk more fountain-path) "name")))
      (is (= 4000 (interpret/get-field (interpret/get-field more "ocean") "depth")))
      (is (= "Arethusa" (interpret/get-field (walk renamed fountain-path) "name")))
      (is (= 7 (interpret/get-field (walk renamed fountain-path) "jets")))
      (is (= 4100 (interpret/get-field (interpret/get-field deep "ocean") "depth")))
      (is (= 7 (interpret/get-field (walk deep fountain-path) "jets")))
      (is (= 12 (interpret/get-field (walk resized fountain-path) "jets")))
      (is (= 5000 (interpret/get-field (interpret/get-field resized "ocean") "depth")))
      (is (= 8 (interpret/get-field boosted "jets")))
      (is (= "Triton" (interpret/get-field boosted "name")))
      (is (= "Fountain Triton has 7 jets; ocean 4000 deep."
             (interpret/call schema-ir methods-ir root "label" []))))))

(deftest writepaths-live-example
  (testing "live-examples/writepaths.wcn is IR-only; Haxe backend rejects %cli-live"
    (let [text (slurp "live-examples/writepaths.wcn")
          ir (compiler/compile-to-ir text)
          haxe (compiler/compile text)]
      (is (:success ir) (str (first (:errors ir))))
      (is (= "cli-live" (get-in ir [:stash :target-ir :host])))
      (is (get-in ir [:stash :construction-ir]))
      (is (not (:success haxe)))
      (is (re-find #"%cli-live" (or (first (:errors haxe)) ""))))))

(deftest seed-wiki-flying-and-writepaths
  (testing "wiki seed pages compile: adventure, writepaths, flyingA, flyingB, and factory_args"
    (let [adventure (compiler/compile-to-ir (slurp "live-examples/adventure_cli.wcn"))
          writepaths (compiler/compile-to-ir (slurp "live-examples/writepaths.wcn"))
          flying-a (compiler/compile-to-ir (slurp "live-examples/flyingA.wcn"))
          factory-args (compiler/compile-to-ir (slurp "live-examples/factory_args.wcn"))
          opts {:resolve-page (fn [n]
                                (when (= n "flyingA")
                                  (slurp "live-examples/flyingA.wcn")))}
          flying-b (compiler/compile-to-ir (slurp "live-examples/flyingB.wcn") opts)
          loaded (interpret/load-program (slurp "live-examples/flyingB.wcn") opts)
          factory-loaded (interpret/load-program (slurp "live-examples/factory_args.wcn"))
          factory-pen (interpret/construct-object
                       (:schema-ir factory-loaded) "Pen" ["blue"])
          factory-root (interpret/construct
                        (:schema-ir factory-loaded)
                        (:construction-ir factory-loaded)
                        (:methods-ir factory-loaded)
                        [factory-pen])
          game (interpret/get-field (:root loaded) "game")
          adv (interpret/load-program (slurp "live-examples/adventure_cli.wcn"))
          north (interpret/call (:schema-ir adv) (:methods-ir adv) (:root adv) "move" ["N"])]
      (is (:success adventure) (str (first (:errors adventure))))
      (is (= "cli-live" (get-in adventure [:stash :target-ir :host])))
      (is (= "NorthGate" (interpret/get-field north "here")))
      (is (:success writepaths) (str (first (:errors writepaths))))
      (is (= "cli-live" (get-in writepaths [:stash :target-ir :host])))
      (is (:success flying-a) (str (first (:errors flying-a))))
      (is (= "canvas" (get-in flying-a [:stash :target-ir :host])))
      (is (:success flying-b) (str (first (:errors flying-b))))
      (is (= "canvas" (get-in flying-b [:stash :target-ir :host])))
      (is (= "Sky" (:wchnt/class (:root loaded))))
      (is (= 4 (count (interpret/get-field game "shapes"))))
      (is (:success factory-args) (str (first (:errors factory-args))))
      (is (= "canvas" (get-in factory-args [:stash :target-ir :host])))
      (is (nil? (:root factory-loaded)))
      (is (= "star blue"
             (interpret/call (:schema-ir factory-loaded)
                             (:methods-ir factory-loaded)
                             factory-root "caption" []))))))

(deftest flying-published-shape-example
  (testing "flyingB adds a Pentagon that implements flyingA's public Shape"
    (let [cargo (assert-compiles "flyingB.wcn")
          cls (classes cargo)
          f (factory cargo)
          main-class (main-class cargo)]
      (is (= "openfl" (host cargo)))
      (is (str/includes? cls "interface Shape"))
      (is (str/includes? cls "class Pentagon implements Shape"))
      (is (str/includes? cls "public function addShape("))
      (is (not (str/includes? cls "public static function make(")))
      (is (str/includes? f "GameAssemblage.factory()"))
      (is (str/includes? f "addShape(new Pentagon("))
      (is (str/includes? main-class "assemblage.game.time.update_mutates()"))
      (is (str/includes? main-class "s.draw(wchntGraphics)")))))

(deftest factory-args-live-example
  (testing "live-examples/factory_args.wcn is IR-only; Haxe backend rejects %canvas"
    (let [text (slurp "live-examples/factory_args.wcn")
          ir (compiler/compile-to-ir text)
          haxe (compiler/compile text)
          params (get-in ir [:stash :construction-ir :factory-params])]
      (is (:success ir) (str (first (:errors ir))))
      (is (= "canvas" (get-in ir [:stash :target-ir :host])))
      (is (= [{:name "pen" :type "Pen"}] params))
      (is (not (:success haxe)))
      (is (re-find #"%canvas" (or (first (:errors haxe)) ""))))))

(deftest factory-args-example
  (testing "factory_args.wcn takes the free @Pen name as a factory argument"
    (let [cargo (assert-compiles "factory_args.wcn")
          f (factory cargo)
          main (main cargo)]
      (is (str/includes? f "public static function factory(pen: Pen)"))
      (is (str/includes? f "new Sketch(\"star\", pen)"))
      (is (str/includes? main "SketchAssemblage.factory(pen)"))
      (is (not (str/includes? f "sketchFactory()"))))))
