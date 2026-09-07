(ns wchnt-lang.examples-test
  "Compile every examples/*.wcn through the WCHNT pipeline and check generated
   Haxe strings. Does not invoke the Haxe compiler; go_all_examples.sh does that."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer :all]
            [wchnt-lang.compiler :as compiler]
            [wchnt-lang.pipeline :as p]))

(defn- example-files
  []
  (->> (file-seq (io/file "examples"))
       (filter #(.isFile %))
       (filter #(str/ends-with? (.getName %) ".wcn"))
       (sort-by #(.getName %))))

(defn- compile-example
  [filename]
  (compiler/compile (slurp (str "examples/" filename))))

(defn- assert-compiles
  [filename]
  (let [cargo (compile-example filename)]
    (is (p/is-cargo? cargo) (str filename " should return a cargo"))
    (is (:success cargo)
        (str filename " should compile: " (first (:errors cargo))))
    cargo))

(defn- factory
  [cargo]
  (get-in cargo [:value :factory] ""))

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
          (if (re-find #"(?m)^%canvas\s*$" text)
            (let [cargo (compiler/compile-to-ir text)]
              (is (:success cargo)
                  (str name " %canvas should compile to IR: " (first (:errors cargo))))
              (is (= "canvas" (get-in cargo [:stash :target-ir :host]))))
            (let [cargo (compiler/compile text)]
              (is (:success cargo)
                  (str name " should compile: " (first (:errors cargo))))
              (when (get-in cargo [:value :has-construction?])
                (is (str/includes? (factory cargo) "Factory")
                    (str name " with construction should emit a factory"))
                (is (str/includes? text "## Target")
                    (str name " with construction should have a Target section"))
                (if (= "openfl" (host cargo))
                  (do
                    (is (str/includes? (main-class cargo) "extends Sprite")
                        (str name " OpenFL Target should emit Main extends Sprite"))
                    (is (str/includes? (main-class cargo) "function init")
                        (str name " should emit %init"))
                    (is (str/includes? (main-class cargo) "function step")
                        (str name " should emit %step")))
                  (is (str/includes? (main cargo) "function main")
                      (str name " should emit %main from Target")))))))))))

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
  (testing "test_context emits Engine.setContext but factory does not call it"
    (let [cargo (assert-compiles "test_context.wcn")
          f (factory cargo)
          cls (classes cargo)]
      (is (str/includes? cls "public var theCar: Car;"))
      (is (str/includes? cls "public function setContext(c: Car)"))
      (is (str/includes? f "new Engine(4)"))
      (is (str/includes? f "new Car(obj1, \"Toyota\")"))
      (is (not (str/includes? f "setContext"))))))

(deftest context-sigil-example-duplicate
  (testing "test_sigil is the same :Engine construction as test_context"
    (let [f (factory (assert-compiles "test_sigil.wcn"))]
      (is (str/includes? f "new Engine(4)"))
      (is (str/includes? f "new Car(obj1, \"Toyota\")")))))

(deftest enum-values-with-spaces
  (testing "test_spaces is schema-only and camel-cases spaced enum labels"
    (let [cargo (assert-compiles "test_spaces.wcn")
          cls (classes cargo)]
      (is (str/blank? (factory cargo)))
      (is (str/includes? cls "enum GameState"))
      (is (str/includes? cls "NotStarted;"))
      (is (str/includes? cls "InProgress;"))
      (is (str/includes? cls "GameOver;")))))

(deftest schema-only-map-field
  (testing "test_dict_minimal is schema-only Map<String, Int>"
    (let [cargo (assert-compiles "test_dict_minimal.wcn")
          cls (classes cargo)]
      (is (str/blank? (factory cargo)))
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
      (is (= 2 (count (re-seq #"\.time\.subscribe\(" f))))
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
      (is (str/includes? main-class "assemblage.time.update();"))
      (is (not (str/includes? main-class "assemblage.step()")))
      (is (not (str/includes? main-class "assemblage.update();")))
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
      (is (str/includes? classes "public function draw(g:Graphics): Void;"))
      (is (str/includes? classes "public function draw(g:Graphics): Void {"))
      (is (str/includes? classes "drawCircle(this.x, this.y, this.radius)"))
      (is (str/includes? main-class "s.draw(graphics)"))
      (is (not (str/includes? main-class "Std.isOfType(s, Circle)")))
      (is (str/includes? main-class "assemblage.time.update();"))
      (is (not (str/includes? main-class "assemblage.update();"))))))


(deftest square-openfl-example
  (testing "square_openfl.wcn injects >Keys from the OpenFL keyboard"
    (let [cargo (assert-compiles "square_openfl.wcn")
          classes (classes cargo)
          main-class (main-class cargo)
          preamble (get-in cargo [:value :preamble] "")]
      (is (= "openfl" (host cargo)))
      (is (str/includes? classes "public function inject(left:Bool, right:Bool, up:Bool, down:Bool): Keys"))
      (is (str/includes? classes "return this.update();"))
      (is (str/includes? preamble "openfl.events.KeyboardEvent"))
      (is (str/includes? preamble "openfl.ui.Keyboard"))
      (is (str/includes? main-class "assemblage.keys.inject("))
      (is (str/includes? main-class "Keyboard.LEFT"))
      (is (not (str/includes? main-class "assemblage.update();"))))))

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
      (is (str/includes? main "assemblage.time.update();"))
      (is (str/includes? main "for (i in 0...10)"))
      (is (not (str/includes? main "assemblage.update();")))
      (is (not (str/includes? main "assemblage.bounceDx"))))))

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
