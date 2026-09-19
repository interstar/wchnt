(ns wchnt-lang.reaction-test
  "Reaction methods: AST → IR → Haxe strings. Does not invoke the Haxe compiler."
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [wchnt-lang.grammars :as grammars]
            [wchnt-lang.parser :as parser]
            [wchnt-lang.ast-to-ir :as ast-to-ir]
            [wchnt-lang.reaction :as reaction]
            [wchnt-lang.targets.haxe-backend :as ir-to-haxe]
            [wchnt-lang.compiler :as compiler]))

(defn- schema-ir
  [schema-text]
  (let [cargo (parser/schema-wchnt->schema-ast schema-text)]
    (ast-to-ir/schema-ast-to-ir (:value cargo))))

(defn- methods-ir
  ([schema-text reaction-text]
   (methods-ir schema-text reaction-text {:bindings {} :main nil}))
  ([schema-text reaction-text target-ir]
   (reaction/reaction-ast-to-ir (grammars/parse-reaction reaction-text)
                                (schema-ir schema-text)
                                target-ir)))

(def rect-ball-schema
  "Rect = Int/x Int/y Int/width Int/height
Ball = Int/x Int/y Int/dx Int/dy Int/rad")

(deftest area-method-ir
  (testing "Rect::area is a no-arg method returning Int multiplication of fields"
    (let [methods (methods-ir rect-ball-schema "Rect::area = { width * height }")
          area (first methods)]
      (is (= 1 (count methods)))
      (is (= "Rect" (:class area)))
      (is (= "area" (:method-name area)))
      (is (= [] (:parameters area)))
      (is (= "Int" (:return-type area)))
      (is (= :arith (get-in area [:body :expr])))
      (is (= :field (get-in area [:body :parts 0 :expr])))
      (is (= "width" (get-in area [:body :parts 0 :name]))))))

(deftest move-method-ir
  (testing "Ball::move constructs a new Ball with arithmetic args"
    (let [move (first (methods-ir rect-ball-schema
                                  "Ball::move = { [:Ball (x + dx) (y + dy) dx dy rad] }"))]
      (is (= "Ball" (:class move)))
      (is (= "Ball" (:return-type move)))
      (is (= :construct (get-in move [:body :expr])))
      (is (= "Ball" (get-in move [:body :class-name])))
      (is (= 5 (count (get-in move [:body :args])))))))

(deftest contains-method-ir
  (testing "Rect::contains takes two params and returns Bool"
    (let [contains (first (methods-ir rect-ball-schema
                                      "Rect::contains = {px, py | (px >= x) and (px <= (x + width)) and (py >= y) and (py <= (y + height))}"))]
      (is (= [{:name "px" :type "Int"} {:name "py" :type "Int"}]
             (:parameters contains)))
      (is (= "Bool" (:return-type contains))))))

(deftest unknown-class-fails
  (testing "method on a class missing from the schema fails fast"
    (is (thrown-with-msg? Exception #"Unknown class or interface"
                          (methods-ir rect-ball-schema "Foo::bar = { 1 }")))))

(deftest unknown-name-fails
  (testing "unknown names identify their owning method"
    (is (thrown-with-msg? Exception
                          #"Error in Rect::area: Unknown name 'banana' \(not a field, parameter, or let\)"
                          (methods-ir rect-ball-schema
                                      "Rect::area = { banana * height }")))))

(def enum-map-schema
  "Config = {Move : Int}/moves
Move = \"Up\" | \"Down\"")

(deftest enum-ctor-in-method-ir
  (testing "bare enum constructors are map keys, not unknown names"
    (let [m (first (methods-ir enum-map-schema "Config::jump = { moves.get(Up) }"))]
      (is (= "Int" (:return-type m)))
      (is (= :enum (get-in m [:body :args 0 :expr])))
      (is (= "Up" (get-in m [:body :args 0 :name])))
      (is (= "Move" (get-in m [:body :args 0 :type]))))))

(deftest ambiguous-enum-ctor-fails
  (testing "a constructor that belongs to two enums fails fast"
    (is (thrown-with-msg? Exception #"more than one enum"
                          (methods-ir "A = \"X\" | \"Y\"\nB = \"X\" | \"Z\"\nC = String/s"
                                      "C::m = { X }")))))

(deftest let-bindings-in-method-ir
  (testing "Ball::move binds nx and ny then constructs"
    (let [move (first (methods-ir rect-ball-schema
                                  "Ball::move = { nx = x + dx. ny = y + dy. [:Ball nx ny dx dy rad] }"))]
      (is (= ["nx" "ny"] (mapv :name (:lets move))))
      (is (= :construct (get-in move [:body :expr])))
      (is (= :local (get-in move [:body :args 0 :expr])))
      (is (= "nx" (get-in move [:body :args 0 :name])))
      (is (= "Ball" (:return-type move))))))

(deftest last-assignment-is-the-result
  (testing "a block that ends on an assignment yields that name"
    (let [area (first (methods-ir rect-ball-schema
                                  "Rect::area = { a = width * height }"))]
      (is (= ["a"] (mapv :name (:lets area))))
      (is (= {:expr :local :name "a"} (:body area)))
      (is (= "Int" (:return-type area))))))

(deftest rebind-let-fails
  (testing "a name cannot be assigned twice"
    (is (thrown-with-msg? Exception #"rebind"
                          (methods-ir rect-ball-schema
                                      "Rect::area = { a = width. a = height. a }")))))

(deftest rebind-field-fails
  (testing "a field name cannot be reused as a let"
    (is (thrown-with-msg? Exception #"rebind"
                          (methods-ir rect-ball-schema
                                      "Ball::move = { x = x + dx. [:Ball x y dx dy rad] }")))))

(deftest emit-lets-haxe
  (testing "lets become Haxe vars, then return"
    (let [methods (methods-ir rect-ball-schema
                              "Ball::move = { nx = x + dx. ny = y + dy. [:Ball nx ny dx dy rad] }")
          haxe (ir-to-haxe/generate-method (first methods))]
      (is (str/includes? haxe "var nx = this.x + this.dx;"))
      (is (str/includes? haxe "var ny = this.y + this.dy;"))
      (is (str/includes? haxe "return new Ball(nx, ny, this.dx, this.dy, this.rad);")))))

(deftest lets-example-emits-methods
  (testing "test_reaction_lets.wcn compiles lets into Haxe"
    (let [cargo (compiler/compile (slurp "examples/test_reaction_lets.wcn"))]
      (is (:success cargo) (str (first (:errors cargo))))
      (let [classes (get-in cargo [:value :classes])]
        (is (str/includes? classes "var nx = this.x + this.dx;"))
        (is (str/includes? classes "var ny = this.y + this.dy;"))
        (is (str/includes? classes "return new Ball(nx, ny, this.dx, this.dy, this.rad);"))
        (is (str/includes? classes "var a = this.width * this.height;"))
        (is (str/includes? classes "return a;"))))))

(deftest emit-area-haxe
  (testing "area compiles to this.width * this.height"
    (let [methods (methods-ir rect-ball-schema "Rect::area = { width * height }")
          haxe (ir-to-haxe/generate-method (first methods))]
      (is (str/includes? haxe "public function area(): Int"))
      (is (str/includes? haxe "return this.width * this.height;")))))

(deftest emit-modulo-haxe
  (testing "x % width compiles to Haxe %"
    (let [methods (methods-ir rect-ball-schema "Rect::wrapX = { x % width }")
          wrap (first methods)
          haxe (ir-to-haxe/generate-method wrap)]
      (is (= :arith (get-in wrap [:body :expr])))
      (is (= "%" (get-in wrap [:body :parts 1])))
      (is (str/includes? haxe "return this.x % this.width;")))))

(deftest emit-grouped-modulo-haxe
  (testing "parenthesized add before modulo keeps grouping in Haxe"
    (let [methods (methods-ir rect-ball-schema "Rect::wrapX = { (x + 1) % width }")
          haxe (ir-to-haxe/generate-method (first methods))]
      (is (str/includes? haxe "(this.x + 1) % this.width"))
      (is (not (str/includes? haxe "this.x + 1 % this.width"))))))

(deftest emit-move-haxe
  (testing "move compiles to new Ball with this. field arithmetic"
    (let [methods (methods-ir rect-ball-schema
                              "Ball::move = { [:Ball (x + dx) (y + dy) dx dy rad] }")
          haxe (ir-to-haxe/generate-method (first methods))]
      (is (str/includes? haxe "public function move(): Ball"))
      (is (str/includes? haxe "new Ball(this.x + this.dx, this.y + this.dy, this.dx, this.dy, this.rad)")))))

(deftest emit-contains-haxe
  (testing "contains uses && and parameter names, not this.px"
    (let [methods (methods-ir rect-ball-schema
                              "Rect::contains = {px, py | (px >= x) and (px <= (x + width)) and (py >= y) and (py <= (y + height))}")
          haxe (ir-to-haxe/generate-method (first methods))]
      (is (str/includes? haxe "public function contains(px:Int, py:Int): Bool"))
      (is (str/includes? haxe "px >= this.x"))
      (is (str/includes? haxe "&&"))
      (is (not (str/includes? haxe "this.px"))))))

(deftest arithmetic-example-emits-methods
  (testing "test_reaction_arithmetic.wcn puts area, doubleWidth, move on generated classes"
    (let [cargo (compiler/compile (slurp "examples/test_reaction_arithmetic.wcn"))]
      (is (:success cargo) (str (first (:errors cargo))))
      (let [classes (get-in cargo [:value :classes])]
        (is (str/includes? classes "public function area(): Int"))
        (is (str/includes? classes "return this.width * this.height;"))
        (is (str/includes? classes "public function doubleWidth(): Rect"))
        (is (str/includes? classes "new Rect(this.x, this.y, this.width * 2, this.height)"))
        (is (str/includes? classes "public function move(): Ball"))
        (is (str/includes? classes "new Ball(this.x + this.dx, this.y + this.dy, this.dx, this.dy, this.rad)"))
        (is (str/includes? classes "public function widen(): Game"))
        (is (str/includes? classes "new PlayArea(new Rect(this.playArea.rect.x, this.playArea.rect.y, this.playArea.rect.width * 2, this.playArea.rect.height))"))))))

(deftest logic-example-emits-methods
  (testing "test_reaction_logic.wcn puts movingRight and contains on generated classes"
    (let [cargo (compiler/compile (slurp "examples/test_reaction_logic.wcn"))]
      (is (:success cargo) (str (first (:errors cargo))))
      (let [classes (get-in cargo [:value :classes])]
        (is (str/includes? classes "public function movingRight(): Bool"))
        (is (str/includes? classes "return this.dx > 0;"))
        (is (str/includes? classes "public function contains(px:Int, py:Int): Bool"))
        (is (str/includes? classes "px >= this.x"))))))

(def game-path-schema
  "Game = PlayArea Ball
PlayArea = Rect
Rect = Int/x Int/y Int/width Int/height
Ball = Int/x Int/y Int/dx Int/dy Int/rad")

(deftest field-path-ir
  (testing "Game::inBounds walks playArea.rect and ball fields"
    (let [m (first (methods-ir game-path-schema
                               "Game::inBounds = { (ball.x > playArea.rect.x) and (ball.x < (playArea.rect.x + playArea.rect.width)) }"))]
      (is (= "Bool" (:return-type m)))
      (is (= :path (get-in m [:body :args 0 :left :expr])))
      (is (= "ball" (get-in m [:body :args 0 :left :root :name])))
      (is (= ["x"] (get-in m [:body :args 0 :left :fields]))))))

(deftest nested-field-path-unknown-fails
  (testing "a segment that is not a component fails fast"
    (is (thrown-with-msg? Exception #"Unknown field"
                          (methods-ir game-path-schema
                                      "Game::bad = { ball.nope }")))))

(deftest nested-construct-ir
  (testing "method construction may nest an explicitly named object"
    (let [m (first (methods-ir game-path-schema
                               "Game::rebuild = { [:Game playArea [:Ball (ball.x + ball.dx) ball.y ball.dx ball.dy ball.rad]] }"))]
      (is (= "Game" (:return-type m)))
      (is (= :construct (get-in m [:body :expr])))
      (is (= :construct (get-in m [:body :args 1 :expr])))
      (is (= "Ball" (get-in m [:body :args 1 :class-name]))))))

(deftest nested-construct-must-name-class
  (testing "unnamed nested construction in a method fails fast"
    (is (thrown-with-msg? Exception #"must name the class"
                          (methods-ir game-path-schema
                                      "Game::bad = { [:Game playArea [0 0 1 1 1]] }")))))

(deftest emit-nested-construct-haxe
  (testing "nested construction becomes nested new"
    (let [haxe (ir-to-haxe/generate-method
                (first (methods-ir game-path-schema
                                   "Game::rebuild = { [:Game playArea [:Ball (ball.x + ball.dx) ball.y ball.dx ball.dy ball.rad]] }")))]
      (is (str/includes? haxe "new Game(this.playArea, new Ball("))
      (is (str/includes? haxe "this.ball.x + this.ball.dx")))))

(deftest collection-field-path-fails
  (testing "cannot walk into an array component with a dot"
    (is (thrown-with-msg? Exception #"collection"
                          (methods-ir "Team = [Player]/players\nPlayer = Int/n"
                                      "Team::bad = { players.n }")))))

(deftest path-after-let
  (testing "a let can be the root of a later field path"
    (let [m (first (methods-ir game-path-schema
                               "Game::w = { r = playArea.rect. r.width }"))]
      (is (= "Int" (:return-type m)))
      (is (= :path (get-in m [:body :expr])))
      (is (= :local (get-in m [:body :root :expr])))
      (is (= "r" (get-in m [:body :root :name])))
      (is (= ["width"] (get-in m [:body :fields]))))))

(deftest context-field-path
  (testing "Engine can read theCar.model"
    (let [m (first (methods-ir "Car = :Engine String/model\nEngine = Int/cylinders"
                               "Engine::carModel = { theCar.model }"))]
      (is (= "String" (:return-type m)))
      (is (= :path (get-in m [:body :expr])))
      (is (= "theCar" (get-in m [:body :root :name])))
      (is (= ["model"] (get-in m [:body :fields]))))))

(deftest emit-field-path-haxe
  (testing "paths compile to this.ball.x"
    (let [haxe (ir-to-haxe/generate-method
                (first (methods-ir game-path-schema "Game::bx = { ball.x }")))]
      (is (str/includes? haxe "return this.ball.x;")))))

(deftest paths-example-emits-methods
  (testing "test_reaction_paths.wcn compiles field paths"
    (let [cargo (compiler/compile (slurp "examples/test_reaction_paths.wcn"))]
      (is (:success cargo) (str (first (:errors cargo))))
      (let [classes (get-in cargo [:value :classes])]
        (is (str/includes? classes "this.ball.x"))
        (is (str/includes? classes "this.playArea.rect.width")))))

  (testing "test_reaction_context_path.wcn compiles theCar.model"
    (let [cargo (compiler/compile (slurp "examples/test_reaction_context_path.wcn"))]
      (is (:success cargo) (str (first (:errors cargo))))
      (is (str/includes? (get-in cargo [:value :classes])
                        "return this.theCar.model;")))))

(def call-schema
  "Game = PlayArea Ball
PlayArea = Rect
Rect = Int/x Int/y Int/width Int/height
Ball = Int/x Int/y Int/dx Int/dy Int/rad")

(def call-methods
  "Rect::area = { width * height }
Rect::contains = {px, py | (px >= x) and (px <= (x + width)) and (py >= y) and (py <= (y + height))}
Ball::move = { [:Ball (x + dx) (y + dy) dx dy rad] }
Ball::step = { this.move() }
Game::playAreaSize = { playArea.rect.area() }
Game::ballInside = { playArea.rect.contains(ball.x, ball.y) }")

(deftest this-call-ir
  (testing "Ball::step calls this.move and returns Ball"
    (let [step (first (filter #(= "step" (:method-name %))
                              (methods-ir call-schema call-methods)))]
      (is (= "Ball" (:return-type step)))
      (is (= :call (get-in step [:body :expr])))
      (is (= :this (get-in step [:body :receiver :expr])))
      (is (= "move" (get-in step [:body :method])))
      (is (= [] (get-in step [:body :args]))))))

(deftest path-receiver-call-ir
  (testing "Game::playAreaSize calls area on playArea.rect"
    (let [m (first (filter #(= "playAreaSize" (:method-name %))
                           (methods-ir call-schema call-methods)))]
      (is (= "Int" (:return-type m)))
      (is (= :call (get-in m [:body :expr])))
      (is (= "area" (get-in m [:body :method])))
      (is (= :path (get-in m [:body :receiver :expr])))
      (is (= ["rect"] (get-in m [:body :receiver :fields]))))))

(deftest call-with-path-args-ir
  (testing "contains gets ball.x and ball.y"
    (let [m (first (filter #(= "ballInside" (:method-name %))
                           (methods-ir call-schema call-methods)))]
      (is (= "Bool" (:return-type m)))
      (is (= "contains" (get-in m [:body :method])))
      (is (= 2 (count (get-in m [:body :args]))))
      (is (= :path (get-in m [:body :args 0 :expr]))))))

(deftest unknown-method-call-fails
  (testing "a method missing on the receiver class fails fast"
    (is (thrown-with-msg? Exception #"Unknown method"
                          (methods-ir call-schema
                                      "Ball::move = { [:Ball x y dx dy rad] }
Game::bad = { ball.explode() }")))))

(deftest unexpected-method-conversion-errors-include-method-context
  (testing "unexpected failures while converting a method to IR name its owner"
    (is (thrown-with-msg? Exception
                          #"Error in Game::bad: Unknown method 'explode' on Ball"
                          (methods-ir call-schema
                                      "Ball::move = { [:Ball x y dx dy rad] }
Game::bad = { ball.explode() }")))))

(deftest wrong-arity-call-fails
  (testing "wrong number of arguments fails fast"
    (is (thrown-with-msg? Exception #"expected 2 arguments"
                          (methods-ir call-schema
                                      "Rect::contains = {px, py | (px >= x) and (py >= y)}
Game::bad = { playArea.rect.contains(ball.x) }")))))

(deftest playArea-area-without-rect-fails
  (testing "PlayArea is not Rect, so playArea.area is unknown"
    (is (thrown-with-msg? Exception #"Unknown method"
                          (methods-ir call-schema
                                      "Rect::area = { width * height }
Game::bad = { playArea.area() }")))))

(deftest emit-method-call-haxe
  (testing "calls compile to receiver.method(args)"
    (let [methods (methods-ir call-schema call-methods)
          by-name (into {} (map (juxt :method-name identity) methods))
          step-haxe (ir-to-haxe/generate-method (by-name "step"))
          size-haxe (ir-to-haxe/generate-method (by-name "playAreaSize"))
          inside-haxe (ir-to-haxe/generate-method (by-name "ballInside"))]
      (is (str/includes? step-haxe "return this.move();"))
      (is (str/includes? size-haxe "return this.playArea.rect.area();"))
      (is (str/includes? inside-haxe
                         "this.playArea.rect.contains(this.ball.x, this.ball.y)")))))

(deftest calls-example-emits-methods
  (testing "test_reaction_calls.wcn compiles method calls"
    (let [cargo (compiler/compile (slurp "examples/test_reaction_calls.wcn"))]
      (is (:success cargo) (str (first (:errors cargo))))
      (let [classes (get-in cargo [:value :classes])]
        (is (str/includes? classes "return this.move();"))
        (is (str/includes? classes "this.playArea.rect.area()"))
        (is (str/includes? classes
                           "this.playArea.rect.contains(this.ball.x, this.ball.y)"))))))

(deftest if-expression-ir
  (testing "if/else is an expression whose type is the branch type"
    (let [m (first (methods-ir rect-ball-schema
                               "Ball::absDx = { if (dx < 0) { -dx } else { dx } }"))]
      (is (= "Int" (:return-type m)))
      (is (= :if (get-in m [:body :expr])))
      (is (= :cmp (get-in m [:body :condition :expr])))
      (is (= :neg (get-in m [:body :then :body :expr])))
      (is (= :field (get-in m [:body :else :body :expr]))))))

(deftest else-if-expression-ir
  (testing "else if chains nest in the else branch"
    (let [m (first (methods-ir rect-ball-schema
                               "Ball::pick = { if (dx < 0) { 1 } else if (dx == 0) { 2 } else { 3 } }"))]
      (is (= :if (get-in m [:body :expr])))
      (is (= :if (get-in m [:body :else :expr])))
      (is (= :cmp (get-in m [:body :else :condition :expr]))))))

(deftest emit-else-if-haxe
  (testing "else if transpiles to nested Haxe if"
    (let [haxe (ir-to-haxe/generate-method
                (first (methods-ir rect-ball-schema
                                   "Ball::pick = { if (dx < 0) { 1 } else if (dx == 0) { 2 } else { 3 } }")))]
      (is (str/includes? haxe "else"))
      (is (str/includes? haxe "if (this.dx == 0)")))))

(deftest if-branch-type-mismatch-fails
  (testing "then and else must have the same type"
    (is (thrown-with-msg? Exception #"same type"
                          (methods-ir rect-ball-schema
                                      "Ball::bad = { if (dx < 0) { dx } else { true } }")))))

(deftest numeric-branches-use-float-join
  (testing "an Int branch and a Float branch have the common type Float"
    (let [m (first (methods-ir "Game = Int/x"
                               "Game::value = { if (x < 0) { x } else { -0.5 } }"))]
      (is (= "Float" (:return-type m)))
      (is (= "Float" (get-in m [:body :type]))))))

(deftest float-negation-preserves-explicit-type
  (testing "negating an explicitly typed Float does not infer it as Int"
    (let [m (first (methods-ir "Game = Int/x"
                               "Game::abs = { Float/x | if (x < 0.0) { x } else { -x } }"))]
      (is (= [{:name "x" :type "Float"}] (:parameters m)))
      (is (= "Float" (:return-type m))))))

(deftest float-conversions-are-language-builtins
  (testing "Float conversion methods need no WCHNTMaths receiver"
    (let [methods (methods-ir "Game = Float/x"
                              "Game::rounded = { x.round() }
                               Game::truncated = { x.toInt() }")
          rounded (first methods)
          truncated (second methods)]
      (is (= "Int" (:return-type rounded)))
      (is (= "round" (get-in rounded [:body :method])))
      (is (= "Int" (:return-type truncated)))
      (is (= "toInt" (get-in truncated [:body :method])))
      (is (str/includes? (ir-to-haxe/generate-method rounded)
                         "return Math.round(this.x);"))
      (is (str/includes? (ir-to-haxe/generate-method truncated)
                         "return Std.int(this.x);")))))

(deftest emit-if-haxe
  (testing "if transpiles to a Haxe if expression"
    (let [haxe (ir-to-haxe/generate-method
                (first (methods-ir rect-ball-schema
                                   "Ball::absDx = { if (dx < 0) { -dx } else { dx } }")))]
      (is (str/includes? haxe "if (this.dx < 0)"))
      (is (str/includes? haxe "-this.dx"))
      (is (str/includes? haxe "else")))))

(deftest if-example-emits-methods
  (testing "test_reaction_if.wcn compiles if/else"
    (let [cargo (compiler/compile (slurp "examples/test_reaction_if.wcn"))]
      (is (:success cargo) (str (first (:errors cargo))))
      (let [classes (get-in cargo [:value :classes])]
        (is (str/includes? classes "if (this.dx < 0)"))
        (is (str/includes? classes "-this.dx"))))))

(def team-schema
  "Team = [Player]/players
Player = String/name Int/score")

(def roster-schema
  "Team = String/name [Player]/players {String : Int}/scores
Player = String/name Int/score")

(deftest map-ir
  (testing "Array map takes a one-arg block and returns Array of the body type"
    (let [m (first (methods-ir team-schema
                               "Team::names = { players.map({ p | p.name }) }"))]
      (is (= "Array<String>" (:return-type m)))
      (is (= :call (get-in m [:body :expr])))
      (is (= "map" (get-in m [:body :method])))
      (is (= :lambda (get-in m [:body :args 0 :expr])))
      (is (= ["p"] (mapv :name (get-in m [:body :args 0 :params])))))))

(deftest filter-ir
  (testing "filter keeps the array type"
    (let [m (first (methods-ir team-schema
                               "Team::scorers = { players.filter({ p | p.score > 0 }) }"))]
      (is (= "Array<Player>" (:return-type m)))
      (is (= "filter" (get-in m [:body :method]))))))

(deftest fold-ir
  (testing "fold takes an initial value and a two-arg block"
    (let [m (first (methods-ir team-schema
                               "Team::total = { players.fold(0, { acc, p | acc + p.score }) }"))]
      (is (= "Int" (:return-type m)))
      (is (= "fold" (get-in m [:body :method])))
      (is (= :int (get-in m [:body :args 0 :expr])))
      (is (= :lambda (get-in m [:body :args 1 :expr]))))))

(def fold-seed-schema
  "Team = [Player]/players
Player = String/name Int/score
Summary = Int/total Int/count")

(deftest fold-infers-class-and-string-seed
  (testing "fold types acc from a constructed class seed and from a string seed"
    (let [stats (first (methods-ir fold-seed-schema
                                   "Team::stats = { players.fold([:Summary 0 0], { acc, p | [:Summary (acc.total + p.score) (acc.count + 1)] }) }"))
          joined (first (methods-ir fold-seed-schema
                                    "Team::joined = { players.fold(\"\", { acc, p | acc.concat(p.name) }) }"))]
      (is (= "Summary" (:return-type stats)))
      (is (= :construct (get-in stats [:body :args 0 :expr])))
      (is (= "Summary" (get-in stats [:body :args 0 :class-name])))
      (is (= [{:name "acc" :type "Summary"} {:name "p" :type "Player"}]
             (get-in stats [:body :args 1 :params])))
      (is (= "String" (:return-type joined)))
      (is (= :string (get-in joined [:body :args 0 :expr])))
      (is (= [{:name "acc" :type "String"} {:name "p" :type "Player"}]
             (get-in joined [:body :args 1 :params]))))))

(deftest map-on-non-array-fails
  (testing "map is only for arrays and maps"
    (is (thrown-with-msg? Exception #"array"
                          (methods-ir rect-ball-schema
                                      "Ball::bad = { dx.map({ n | n }) }")))))

(deftest map-map-ir
  (testing "Map map takes key and value and returns Map of the body type"
    (let [m (first (methods-ir roster-schema
                               "Team::boosted = { scores.map({ k, v | v + 1 }) }"))]
      (is (= "Map<String, Int>" (:return-type m)))
      (is (= "map" (get-in m [:body :method])))
      (is (= "Map" (get-in m [:body :on])))
      (is (= ["k" "v"] (mapv :name (get-in m [:body :args 0 :params])))))))

(deftest map-filter-ir
  (testing "Map filter keeps the map type"
    (let [m (first (methods-ir roster-schema
                               "Team::hot = { scores.filter({ k, v | v > 0 }) }"))]
      (is (= "Map<String, Int>" (:return-type m)))
      (is (= "filter" (get-in m [:body :method])))
      (is (= "Map" (get-in m [:body :on]))))))

(deftest map-fold-ir
  (testing "Map fold takes a seed and a three-arg block"
    (let [m (first (methods-ir roster-schema
                               "Team::sum = { scores.fold(0, { acc, k, v | acc + v }) }"))]
      (is (= "Int" (:return-type m)))
      (is (= "fold" (get-in m [:body :method])))
      (is (= "Map" (get-in m [:body :on])))
      (is (= ["acc" "k" "v"] (mapv :name (get-in m [:body :args 1 :params])))))))

(deftest map-map-one-arg-fails
  (testing "Map::map rejects an array-style one-arg block"
    (is (thrown-with-msg? Exception #"2 arguments"
                          (methods-ir roster-schema
                                      "Team::bad = { scores.map({ v | v }) }")))))

(deftest emit-collection-haxe
  (testing "map filter fold emit Haxe Array methods and Lambda.fold"
    (let [methods (methods-ir team-schema
                              "Team::names = { players.map({ p | p.name }) }
Team::scorers = { players.filter({ p | p.score > 0 }) }
Team::total = { players.fold(0, { acc, p | acc + p.score }) }")
          by-name (into {} (map (juxt :method-name identity) methods))
          names-haxe (ir-to-haxe/generate-method (by-name "names"))
          scorers-haxe (ir-to-haxe/generate-method (by-name "scorers"))
          total-haxe (ir-to-haxe/generate-method (by-name "total"))]
      (is (str/includes? names-haxe "this.players.map"))
      (is (str/includes? names-haxe "function(p:Player):String"))
      (is (str/includes? scorers-haxe "this.players.filter"))
      (is (str/includes? total-haxe "Lambda.fold(this.players"))
      (is (str/includes? total-haxe "function(p:Player, acc:Int):Int")))))

(deftest array-get-ir
  (testing "Array::get(index) returns the element type"
    (let [m (first (methods-ir team-schema
                               "Team::first = { players.get(0) }"))]
      (is (= "Player" (:return-type m)))
      (is (= :call (get-in m [:body :expr])))
      (is (= "get" (get-in m [:body :method])))
      (is (= "Array" (get-in m [:body :on])))
      (is (= ["Int"] (get-in m [:body :arg-types]))))))

(deftest array-get-on-non-array-fails
  (testing "get on a non-array, non-map receiver fails fast"
    (is (thrown-with-msg? Exception #"arrays and maps"
                          (methods-ir rect-ball-schema
                                      "Ball::bad = { dx.get(0) }")))))

(deftest array-get-haxe
  (testing "Array::get emits Haxe index access"
    (let [m (first (methods-ir team-schema
                               "Team::first = { players.get(0) }"))
          haxe (ir-to-haxe/generate-method m)]
      (is (str/includes? haxe "(this.players)[0]")))))

(deftest emit-map-collection-haxe
  (testing "map filter fold on maps emit WCHNTRuntime helpers"
    (let [methods (methods-ir roster-schema
                              "Team::boosted = { scores.map({ k, v | v + 1 }) }
Team::hot = { scores.filter({ k, v | v > 0 }) }
Team::sum = { scores.fold(0, { acc, k, v | acc + v }) }")
          by-name (into {} (map (juxt :method-name identity) methods))
          map-haxe (ir-to-haxe/generate-method (by-name "boosted"))
          filter-haxe (ir-to-haxe/generate-method (by-name "hot"))
          fold-haxe (ir-to-haxe/generate-method (by-name "sum"))]
      (is (str/includes? map-haxe "WCHNTRuntime.mapMap(this.scores"))
      (is (str/includes? map-haxe "new Map<String, Int>()"))
      (is (str/includes? map-haxe "function(k:String, v:Int):Int"))
      (is (str/includes? filter-haxe "WCHNTRuntime.mapFilter(this.scores"))
      (is (str/includes? fold-haxe "WCHNTRuntime.mapFold(this.scores"))
      (is (str/includes? fold-haxe "function(acc:Int, k:String, v:Int):Int")))))

(deftest collections-example-emits-methods
  (testing "test_reaction_collections.wcn compiles map filter fold"
    (let [cargo (compiler/compile (slurp "examples/test_reaction_collections.wcn"))]
      (is (:success cargo) (str (first (:errors cargo))))
      (let [classes (get-in cargo [:value :classes])]
        (is (str/includes? classes "this.players.map"))
        (is (str/includes? classes "this.players.filter"))
        (is (str/includes? classes "Lambda.fold(this.players"))))))

(def player-schema
  "Player = String/name Int/score")

(deftest string-length-ir
  (testing "String::length is a no-arg method returning Int"
    (let [m (first (methods-ir player-schema "Player::nameLen = { name.length() }"))]
      (is (= "Int" (:return-type m)))
      (is (= "length" (get-in m [:body :method]))))))

(deftest string-concat-ir
  (testing "concat joins a string with a string or number"
    (let [m (first (methods-ir player-schema
                               "Player::tag = { name.concat(\":\").concat(score) }"))]
      (is (= "String" (:return-type m)))
      (is (= "concat" (get-in m [:body :method]))))))

(deftest primitive-str-ir
  (testing "str is String on Int, String, and int literals"
    (let [methods (methods-ir player-schema
                              "Player::n = { score.str() }
Player::s = { name.str() }
Player::four = { 4.str() }")
          by-name (into {} (map (juxt :method-name identity) methods))]
      (is (= "String" (:return-type (by-name "n"))))
      (is (= "str" (get-in (by-name "n") [:body :method])))
      (is (= "String" (:return-type (by-name "s"))))
      (is (= "String" (:return-type (by-name "four")))))))

(deftest str-on-array-fails
  (testing "str is only for primitives"
    (is (thrown-with-msg? Exception #"str"
                          (methods-ir roster-schema
                                      "Team::bad = { players.str() }")))))

(deftest string-tpl-ir
  (testing "tpl takes Map<String, String> and returns String"
    (let [m (first (methods-ir player-schema
                               "Player::hi = { \"hi {who}.\".tpl({String:String \"who\": name}) }"))]
      (is (= "String" (:return-type m)))
      (is (= "tpl" (get-in m [:body :method])))
      (is (= 1 (count (get-in m [:body :args])))))))

(deftest tpl-missing-literal-hole-fails
  (testing "a string literal with a hole missing from the map fails at compile"
    (is (thrown-with-msg? Exception #"missing 'who'"
                          (methods-ir player-schema
                                      "Player::bad = { \"hi {who}.\".tpl({String:String}) }")))))

(deftest tpl-wrong-map-fails
  (testing "tpl rejects a map that is not String to String"
    (is (thrown-with-msg? Exception #"String::tpl"
                          (methods-ir roster-schema
                                      "Team::bad = { \"x {n}.\".tpl(scores) }")))))

(deftest cons-ir
  (testing "cons prepends an element and keeps the array type"
    (let [m (first (methods-ir roster-schema
                               "Team::withP = {p | players.cons(p) }"))]
      (is (= "Array<Player>" (:return-type m)))
      (is (= "cons" (get-in m [:body :method]))))))

(deftest put-ir
  (testing "put returns a new map of the same type"
    (let [m (first (methods-ir roster-schema
                               "Team::withScore = {n, s | scores.put(n, s) }"))]
      (is (= "Map<String, Int>" (:return-type m)))
      (is (= "put" (get-in m [:body :method]))))))

(deftest array-literal-ir
  (testing "[:Array/Player] is an empty typed array"
    (let [m (first (methods-ir roster-schema
                               "Team::none = { [:Array/Player] }"))]
      (is (= "Array<Player>" (:return-type m)))
      (is (= :array (get-in m [:body :expr])))
      (is (= [] (get-in m [:body :items]))))))

(deftest map-literal-ir
  (testing "{String:Int} is an empty typed map"
    (let [m (first (methods-ir roster-schema
                               "Team::blank = { {String:Int} }"))]
      (is (= "Map<String, Int>" (:return-type m)))
      (is (= :map (get-in m [:body :expr])))
      (is (= [] (get-in m [:body :pairs]))))))

(deftest brace-map-literal-pairs-ir
  (testing "{String:Int k v} pairs type-check"
    (let [m (first (methods-ir roster-schema
                               "Team::scoresOf = { {String:Int \"Ada\":3} }"))]
      (is (= "Map<String, Int>" (:return-type m)))
      (is (= 1 (count (get-in m [:body :pairs])))))))

(deftest cons-wrong-elem-fails
  (testing "cons rejects an element of the wrong type"
    (is (thrown-with-msg? Exception #"cons"
                          (methods-ir roster-schema
                                      "Team::bad = { players.cons(name) }")))))

(deftest put-on-array-fails
  (testing "put is only for maps"
    (is (thrown-with-msg? Exception #"put"
                          (methods-ir roster-schema
                                      "Team::bad = { players.put(\"Ada\", 1) }")))))

(deftest head-tail-ir
  (testing "head is the element type, tail is the array type"
    (let [methods (methods-ir roster-schema
                              "Team::captain = { players.head() }
Team::bench = { players.tail() }")
          by-name (into {} (map (juxt :method-name identity) methods))]
      (is (= "Player" (:return-type (by-name "captain"))))
      (is (= "head" (get-in (by-name "captain") [:body :method])))
      (is (= "Array<Player>" (:return-type (by-name "bench"))))
      (is (= "tail" (get-in (by-name "bench") [:body :method]))))))

(deftest map-get-remove-ir
  (testing "get returns the value type, remove returns the map"
    (let [methods (methods-ir roster-schema
                              "Team::adaScore = { scores.get(\"Ada\") }
Team::noAda = { scores.remove(\"Ada\") }")
          by-name (into {} (map (juxt :method-name identity) methods))]
      (is (= "Int" (:return-type (by-name "adaScore"))))
      (is (= "get" (get-in (by-name "adaScore") [:body :method])))
      (is (= "Map<String, Int>" (:return-type (by-name "noAda"))))
      (is (= "remove" (get-in (by-name "noAda") [:body :method]))))))

(deftest map-exists-and-get-default-ir
  (testing "exists is Bool; get(key, fallback) is the value type"
    (let [methods (methods-ir roster-schema
                              "Team::hasAda = { scores.exists(\"Ada\") }
Team::adaOrZero = { scores.get(\"Ada\", 0) }")
          by-name (into {} (map (juxt :method-name identity) methods))]
      (is (= "Bool" (:return-type (by-name "hasAda"))))
      (is (= "exists" (get-in (by-name "hasAda") [:body :method])))
      (is (= "Int" (:return-type (by-name "adaOrZero"))))
      (is (= 2 (count (get-in (by-name "adaOrZero") [:body :args])))))))

(deftest get-default-wrong-type-fails
  (testing "get fallback must be the map value type"
    (is (thrown-with-msg? Exception #"Map::get"
                          (methods-ir roster-schema
                                      "Team::bad = { scores.get(\"Ada\", name) }")))))

(deftest exists-on-array-fails
  (testing "exists is only for maps"
    (is (thrown-with-msg? Exception #"exists"
                          (methods-ir roster-schema
                                      "Team::bad = { players.exists(\"Ada\") }")))))

(deftest substring-ir
  (testing "substring takes two Ints and returns String"
    (let [m (first (methods-ir player-schema
                               "Player::initial = { name.substring(0, 1) }"))]
      (is (= "String" (:return-type m)))
      (is (= "substring" (get-in m [:body :method]))))))

(deftest times-ir
  (testing "n.times maps the index through a block and returns an array"
    (let [m (first (methods-ir player-schema
                               "Player::zeros = { 3.times({ i | 0 }) }"))]
      (is (= "Array<Int>" (:return-type m)))
      (is (= "times" (get-in m [:body :method]))))))

(deftest head-on-string-fails
  (testing "head is only for arrays"
    (is (thrown-with-msg? Exception #"array"
                          (methods-ir player-schema
                                      "Player::bad = { name.head() }")))))

(deftest get-on-array-fails
  (testing "get is only for maps"
    (is (thrown-with-msg? Exception #"get"
                          (methods-ir roster-schema
                                      "Team::bad = { players.get(\"Ada\") }")))))

(deftest times-wrong-arity-fails
  (testing "times block takes one argument, the index"
    (is (thrown-with-msg? Exception #"times"
                          (methods-ir player-schema
                                      "Player::bad = { 3.times({ 0 }) }")))))

(deftest emit-string-cons-put-haxe
  (testing "length concat cons put emit Haxe"
    (let [methods (methods-ir roster-schema
                              "Player::nameLen = { name.length() }
Player::tag = { name.concat(\":\").concat(score) }
Team::withP = {p | players.cons(p) }
Team::withScore = {n, s | scores.put(n, s) }
Team::none = { [:Array/Player] }
Team::blank = { {String:Int} }")
          by-name (into {} (map (juxt :method-name identity) methods))
          len-haxe (ir-to-haxe/generate-method (by-name "nameLen"))
          tag-haxe (ir-to-haxe/generate-method (by-name "tag"))
          cons-haxe (ir-to-haxe/generate-method (by-name "withP"))
          put-haxe (ir-to-haxe/generate-method (by-name "withScore"))
          none-haxe (ir-to-haxe/generate-method (by-name "none"))
          blank-haxe (ir-to-haxe/generate-method (by-name "blank"))]
      (is (str/includes? len-haxe "this.name.length"))
      (is (not (str/includes? len-haxe "this.name.length()")))
      (is (str/includes? tag-haxe "this.name + \":\""))
      (is (str/includes? cons-haxe "[p].concat(this.players)"))
      (is (str/includes? put-haxe "WCHNTRuntime.mapPut(this.scores, n, s)"))
      (is (str/includes? none-haxe "new Array<Player>()"))
      (is (str/includes? blank-haxe "new Map<String, Int>()")))))

(deftest emit-head-get-substring-times-haxe
  (testing "head tail get remove substring times emit runtime helpers"
    (let [methods (methods-ir roster-schema
                              "Player::initial = { name.substring(0, 1) }
Team::captain = { players.head() }
Team::bench = { players.tail() }
Team::adaScore = { scores.get(\"Ada\") }
Team::hasAda = { scores.exists(\"Ada\") }
Team::adaOrZero = { scores.get(\"Ada\", 0) }
Team::noAda = { scores.remove(\"Ada\") }
Team::zeros = { 3.times({ i | 0 }) }")
          by-name (into {} (map (juxt :method-name identity) methods))]
      (is (str/includes? (ir-to-haxe/generate-method (by-name "captain"))
                         "WCHNTRuntime.arrayHead(this.players)"))
      (is (str/includes? (ir-to-haxe/generate-method (by-name "bench"))
                         "WCHNTRuntime.arrayTail(this.players)"))
      (is (str/includes? (ir-to-haxe/generate-method (by-name "adaScore"))
                         "WCHNTRuntime.mapGet(this.scores, \"Ada\")"))
      (is (str/includes? (ir-to-haxe/generate-method (by-name "hasAda"))
                         "WCHNTRuntime.mapExists(this.scores, \"Ada\")"))
      (is (str/includes? (ir-to-haxe/generate-method (by-name "adaOrZero"))
                         "WCHNTRuntime.mapGetDefault(this.scores, \"Ada\", 0)"))
      (is (str/includes? (ir-to-haxe/generate-method (by-name "noAda"))
                         "WCHNTRuntime.mapRemove(this.scores, \"Ada\")"))
      (is (str/includes? (ir-to-haxe/generate-method (by-name "initial"))
                         "WCHNTRuntime.substring(this.name, 0, 1)"))
      (is (str/includes? (ir-to-haxe/generate-method (by-name "zeros"))
                         "WCHNTRuntime.times(3")))))

(deftest emit-str-tpl-haxe
  (testing "str is Std.string; tpl is WCHNTRuntime.tpl"
    (let [methods (methods-ir player-schema
                              "Player::n = { score.str() }
Player::hi = { \"hi {who}.\".tpl({String:String \"who\": name}) }")
          by-name (into {} (map (juxt :method-name identity) methods))]
      (is (str/includes? (ir-to-haxe/generate-method (by-name "n"))
                         "Std.string(this.score)"))
      (is (str/includes? (ir-to-haxe/generate-method (by-name "hi"))
                         "WCHNTRuntime.tpl")))))

(def tick-schema
  "Game = PlayArea Ball $Time
PlayArea = Rect
Rect = Int/x Int/y Int/width Int/height
Ball = Int/x Int/y Int/dx Int/dy Int/rad
Time = Int/t")

(def tick-methods
  "Time::update! = { [:Time (t + 1)] }
Game::update! = { [:Game playArea ball time] }")

(deftest update-rewrites-self
  (testing "update IR is a same-class construction of every field"
    (let [methods (methods-ir tick-schema tick-methods)
          by-name (into {} (map (juxt (juxt :class :method-name) identity) methods))
          time-up (by-name ["Time" "update!"])
          game-up (by-name ["Game" "update!"])]
      (is (= "Time" (:return-type time-up)))
      (is (= :construct (get-in time-up [:body :expr])))
      (is (= "Time" (get-in time-up [:body :class-name])))
      (is (= 1 (count (get-in time-up [:body :args]))))
      (is (= "Game" (get-in game-up [:body :class-name])))
      (is (= 3 (count (get-in game-up [:body :args])))))))

(def mutating-method-schema
  "Counter = $Clock
Clock = Int/t")

(def mutating-methods
  "Clock::update! = { [:Clock t] }
Clock::advance! = { Int/delta | [:Clock (t + delta)] }
Counter::update! = { [:Counter clock] }")

(deftest arbitrary-mutating-method-is-marked-in-ir
  (testing "! is an effect marker and mutating methods may take arguments"
    (let [methods (methods-ir mutating-method-schema mutating-methods)
          advance (first (filter #(= "advance!" (:method-name %)) methods))]
      (is (:mutating? advance))
      (is (= [{:name "delta" :type "Int"}] (:parameters advance)))
      (is (= "Clock" (:return-type advance)))
      (is (= "Clock" (get-in advance [:body :class-name]))))))

(deftest pure-method-cannot-call-mutating-method
  (testing "a pure method cannot call a ! method"
    (is (thrown-with-msg? Exception #"Pure method cannot call mutating method"
                          (methods-ir mutating-method-schema
                                       (str mutating-methods
                                            "\nClock::bad = { this.advance!(1) }"))))))

(deftest mutating-method-requires-mutable-class
  (testing "! methods are rejected on ordinary value classes"
    (is (thrown-with-msg? Exception #"identity-capable class"
                          (methods-ir "Counter = Int/t"
                                       "Counter::reset! = { [:Counter t] }")))))

(deftest mutating-method-cannot-replace-identity-slot
  (testing "a mutating construction cannot put another class in a $ slot"
    (let [cargo (compiler/compile
                 (str "## Schema\n\n```\n"
                      "Game = $Time\nTime = Int/t\nOther = Int/x\n"
                      "```\n\n## Construction\n\n```\n"
                      "[:Game [:Time 0]]\n"
                      "```\n\n## Methods\n\n```\n"
                      "Time::update! = { [:Time t] }\n"
                      "Game::update! = { [:Game [:Other 1]] }\n"
                      "```\n"))]
      (is (not (:success cargo)))
      (is (re-find #"replace identity slot|assignable|type"
                   (or (first (:errors cargo)) ""))))))

(deftest mutating-method-cannot-replace-mailbox-slot
  (testing "a mutating construction cannot put another class in a > slot"
    (let [cargo (compiler/compile
                 (str "## Schema\n\n```\n"
                      "Game = $Keys\n>Keys = Int/x\nOther = Int/x\n"
                      "```\n\n## Construction\n\n```\n"
                      "[:Game [:Keys 0]]\n"
                      "```\n\n## Methods\n\n```\n"
                      "Keys::update! = { [:Keys x] }\n"
                      "Game::update! = { [:Game [:Other 1]] }\n"
                      "```\n"))]
      (is (not (:success cargo)))
      (is (re-find #"replace identity slot|assignable|type"
                   (or (first (:errors cargo)) ""))))))

(deftest emit-update-haxe
  (testing "update assigns fields on this, notifies if observable, returns this"
    (let [methods (methods-ir tick-schema tick-methods)
          time-up (first (filter #(= "Time" (:class %)) methods))
          game-up (first (filter #(= "Game" (:class %)) methods))
          time-haxe (ir-to-haxe/generate-method
                     time-up
                     {:components [{:component-name "t"}]
                      :observable? true})
          game-haxe (ir-to-haxe/generate-method
                     game-up
                     {:components [{:component-name "playArea"}
                                   {:component-name "ball"}
                                   {:component-name "time"}]
                      :observable? false})]
      (is (str/includes? time-haxe "this.t = this.t + 1;"))
      (is (str/includes? time-haxe "this.notifySubscribers();"))
      (is (str/includes? time-haxe "return this;"))
      (is (not (str/includes? game-haxe "this.playArea = this.playArea;")))
      (is (not (str/includes? game-haxe "this.ball = this.ball;")))
      (is (not (str/includes? game-haxe "this.time = this.time;")))
      (is (not (str/includes? game-haxe "notifySubscribers"))))))

(def game-rect-schema
  "Game = PlayArea Ball
PlayArea = Rect
Rect = Int/x Int/y Int/width Int/height
Ball = Int/x Int/y Int/dx Int/dy Int/rad")

(deftest with-construction-lowers-to-construct
  (testing "[:Rect | width = (width * 2)] copies the other Rect fields from this"
    (let [m (first (methods-ir rect-ball-schema
                               "Rect::doubleWidth = { [:Rect | width = (width * 2)] }"))
          args (get-in m [:body :args])]
      (is (= :construct (get-in m [:body :expr])))
      (is (= "Rect" (get-in m [:body :class-name])))
      (is (= 4 (count args)))
      (is (= :field (:expr (nth args 0))))
      (is (= "x" (:name (nth args 0))))
      (is (= :arith (:expr (nth args 2))))
      (is (= :field (:expr (nth args 3))))
      (is (= "height" (:name (nth args 3)))))))

(deftest with-construction-nested-path
  (testing "[:Game | playArea.rect.width = 800] rebuilds PlayArea and Rect"
    (let [m (first (methods-ir game-rect-schema
                               "Game::widen = { [:Game | playArea.rect.width = 800] }"))
          args (get-in m [:body :args])
          play-area (first args)
          rect (first (:args play-area))]
      (is (= :construct (:expr play-area)))
      (is (= "PlayArea" (:class-name play-area)))
      (is (= :construct (:expr rect)))
      (is (= "Rect" (:class-name rect)))
      (is (= :int (:expr (nth (:args rect) 2))))
      (is (= 800 (:value (nth (:args rect) 2))))
      (is (= :field (:expr (second args))))
      (is (= "ball" (:name (second args)))))))

(deftest with-construction-named-source
  (testing "[:Ball ball | x = nx] reads remaining fields from the source"
    (let [m (first (methods-ir game-rect-schema
                               "Game::nudge = { Ball/ball, Int/nx | [:Ball ball | x = nx] }"))
          args (get-in m [:body :args])]
      (is (= :param (:expr (first args))))
      (is (= "nx" (:name (first args))))
      (is (= :path (:expr (second args))))
      (is (= ["y"] (:fields (second args)))))))

(deftest with-construction-emits-haxe
  (testing "lowered write-paths emit the same new Class(...) as a full construction"
    (let [methods (methods-ir game-rect-schema
                              (str "Rect::doubleWidth = { [:Rect | width = (width * 2)] }\n"
                                   "Game::widen = { [:Game | playArea.rect.width = 800] }"))
          by-name (into {} (map (juxt :method-name identity) methods))
          dw (ir-to-haxe/generate-method (by-name "doubleWidth"))
          widen (ir-to-haxe/generate-method (by-name "widen"))]
      (is (str/includes? dw "new Rect(this.x, this.y, this.width * 2, this.height)"))
      (is (str/includes? widen "new PlayArea(new Rect(this.playArea.rect.x, this.playArea.rect.y, 800, this.playArea.rect.height))"))
      (is (str/includes? widen "this.ball")))))

(deftest with-update-ticks-time
  (testing "Time::update! may use [:Time | t = (t + 1)]"
    (let [methods (methods-ir tick-schema
                              (str "Time::update! = { [:Time | t = (t + 1)] }\n"
                                   "Game::update! = { [:Game | ball.x = (ball.x + ball.dx)] }"))
          time-up (first (filter #(= "Time" (:class %)) methods))
          game-up (first (filter #(= "Game" (:class %)) methods))
          sir (schema-ir tick-schema)
          time-haxe (ir-to-haxe/generate-method
                     time-up
                     {:components (mapv #(select-keys % [:component-name :type-name])
                                        [{:component-name "t" :type-name "Int"}])
                      :observable? true
                      :schema-ir sir
                      :class-name "Time"})
          game-haxe (ir-to-haxe/generate-method
                     game-up
                     {:components [{:component-name "playArea" :type-name "PlayArea"}
                                   {:component-name "ball" :type-name "Ball"}
                                   {:component-name "time" :type-name "Time"}]
                      :observable? false
                      :schema-ir sir
                      :class-name "Game"})]
      (is (str/includes? time-haxe "this.t = this.t + 1;"))
      (is (str/includes? game-haxe "this.ball = new Ball("))
      (is (not (str/includes? game-haxe "this.time = "))))))

(deftest with-construction-needs-source
  (testing "implicit this must be the constructed class"
    (is (thrown-with-msg? Exception #"needs a source"
                          (methods-ir game-rect-schema
                                      "Game::nudge = { [:Ball | x = 1] }")))))

(deftest with-construction-unknown-field-fails
  (testing "write-path field must exist on the class"
    (is (thrown-with-msg? Exception #"Unknown field"
                          (methods-ir rect-ball-schema
                                      "Rect::bad = { [:Rect | banana = 1] }")))))

(deftest with-construction-conflict-fails
  (testing "cannot assign a field and also a path under it"
    (is (thrown-with-msg? Exception #"path under"
                          (methods-ir game-rect-schema
                                      "Game::bad = { [:Game | playArea = playArea, playArea.rect.width = 800] }")))))

(deftest with-construction-rejects-collection-path
  (testing "cannot walk into an array with a write-path"
    (is (thrown-with-msg? Exception #"collection"
                          (methods-ir
                           "Team = String/name [Player]/players\nPlayer = String/name Int/score"
                           "Team::bad = { [:Team | players.name = \"Ada\"] }")))))

(deftest construction-rejects-with-form
  (testing "[:Class | ...] is Methods-only"
    (let [cargo (compiler/compile
                 (str "# x\n\n## Schema\n\n```\nBall = Int/x Int/y\n```\n\n"
                      "## Construction\n\n```\n[:Ball | x = 1]\n```\n\n"
                      "## Target\n\n```\n%terminal\n\n%main\n"
                      "public static function main():Void {}\n```\n"))]
      (is (not (:success cargo)))
      (is (re-find #"Methods" (or (first (:errors cargo)) ""))))))

(deftest update-must-construct-self
  (testing "update that does not construct its class fails"
    (is (thrown-with-msg? Exception #"update!"
                          (methods-ir tick-schema
                                      "Time::update! = { t }
Game::update! = { [:Game playArea ball time] }")))))

(deftest update-must-list-every-field
  (testing "update construction arity must match the schema"
    (is (thrown-with-msg? Exception #"arguments"
                          (methods-ir tick-schema
                                      "Time::update! = { [:Time (t + 1)] }
Game::update! = { [:Game playArea ball] }")))))

(deftest subscriber-without-update-fails
  (testing "a $ subscriber must define update"
    (is (thrown-with-msg? Exception #"update!"
                          (methods-ir tick-schema
                                      "Time::update! = { [:Time (t + 1)] }")))))

(deftest observable-without-update-fails
  (testing "an observable class must define update"
    (is (thrown-with-msg? Exception #"update!"
                          (methods-ir tick-schema
                                      "Game::update! = { [:Game playArea ball time] }")))))

(deftest update-rejects-parameters
  (testing "update takes no arguments"
    (is (thrown-with-msg? Exception #"update!"
                          (methods-ir tick-schema
                                      "Time::update! = {n | [:Time (t + n)] }
Game::update! = { [:Game playArea ball time] }")))))

(deftest construction-without-target-emits-assemblage
  (testing "a construction program without Target emits the assemblage but no Main"
    (let [cargo (compiler/compile
                 (str "# x\n\n## Schema\n\n```\nRect = Int/x Int/y Int/width Int/height\n```\n\n"
                      "## Construction\n\n```\n[:Rect 0 0 1 1]\n```\n"))]
      (is (:success cargo) (first (:errors cargo)))
      (is (str/includes? (get-in cargo [:value :classes]) "RectAssemblage"))
      (is (str/blank? (get-in cargo [:value :main-class] ""))))))

(deftest schema-class-named-main-fails
  (testing "a schema class called Main fails rather than colliding with the generated entry"
    (let [cargo (compiler/compile
                 (str "# x\n\n## Schema\n\n```\nMain = Int/x\n```\n\n"
                      "## Construction\n\n```\n[:Main 1]\n```\n\n"
                      "## Target\n\n```\n%terminal\n\n%main\npublic static function main():Void {}\n```\n"))]
      (is (not (:success cargo)))
      (is (re-find #"Main" (or (first (:errors cargo)) ""))))))

(deftest openfl-emits-sprite-init-and-step
  (testing "%openfl splices init/step onto Main extends Sprite"
    (let [cargo (compiler/compile
                 (str "# x\n\n## Schema\n\n```\nRect = Int/x Int/y Int/width Int/height\n```\n\n"
                      "## Construction\n\n```\n[:Rect 0 0 1 1]\n```\n\n"
                      "## Target\n\n```\n%openfl\n\n"
                      "%init\nvar assemblage:Rect;\nfunction init():Void { assemblage = RectAssemblage.factory(); }\n\n"
                      "%step\nfunction step():Void { graphics.clear(); }\n```\n"))]
      (is (:success cargo) (first (:errors cargo)))
      (let [main-class (get-in cargo [:value :main-class])
            preamble (get-in cargo [:value :preamble])]
        (is (= "openfl" (get-in cargo [:value :host])))
        (is (str/includes? preamble "openfl.display.Sprite"))
        (is (str/includes? main-class "class Main extends Sprite"))
        (is (str/includes? main-class "function init"))
        (is (str/includes? main-class "function step"))
        (is (str/includes? main-class "Event.ENTER_FRAME"))
        (is (str/includes? main-class "RectAssemblage.factory"))))))

(deftest dollar-without-methods-fails
  (testing "schema $ with no Methods section still requires update"
    (let [cargo (compiler/compile
                 (str "# x\n\n## Schema\n\n```\nGame = $Time\nTime = Int/t\n```\n\n"
                      "## Construction\n\n```\n[:Game [:Time 0]]\n```\n"))]
      (is (not (:success cargo)))
      (is (re-find #"update!" (or (first (:errors cargo)) ""))))))

(def trace-target
  {:bindings {"trace" {:fn-name "wchnt_trace" :haxe ""}}
   :main {:haxe ""}})

(deftest target-trace-passes-the-value-through
  (testing "%trace(expr) keeps expr's type and compiles to Main.wchnt_trace"
    (let [methods (methods-ir rect-ball-schema
                              "Rect::area = { %trace(width * height) }"
                              trace-target)
          area (first methods)
          haxe (ir-to-haxe/generate-method area)]
      (is (= :target-call (get-in area [:body :expr])))
      (is (= "trace" (get-in area [:body :name])))
      (is (= "Int" (:return-type area)))
      (is (str/includes? haxe "Main.wchnt_trace("))
      (is (str/includes? haxe "this.width * this.height")))))

(deftest target-trace-unknown-name-fails
  (testing "%trace without a Target binding fails"
    (is (thrown-with-msg? Exception #"%trace"
                          (methods-ir rect-ball-schema
                                      "Rect::area = { %trace(width * height) }")))))

(deftest target-trace-is-not-a-statement-line
  (testing "a block cannot have %trace as a line before the result"
    (is (thrown-with-msg? Exception #"let-bindings"
                          (methods-ir rect-ball-schema
                                      "Rect::area = { %trace(width). width * height }"
                                      trace-target)))))

(def shape-schema
  "Shape = Triangle | Circle
Triangle = Int/base Int/height
Circle = Int/radius")

(deftest typed-param-field-access
  (testing "Rect/playAreaRect enables field access on a parameter"
    (let [m (first (methods-ir rect-ball-schema
                              "Rect::rightEdge = { Rect/r |
  r.x + r.width
}"))]
      (is (= "Int" (:return-type m)))
      (is (= [{:name "r" :type "Rect"}] (:parameters m))))))

(deftest untyped-param-field-access-fails
  (testing "field access on an untyped parameter fails with annotation hint"
    (is (thrown-with-msg? Exception #"needs a type annotation"
                          (methods-ir rect-ball-schema
                                      "Rect::rightEdge = { r | r.x + r.width }")))))

(deftest identity-without-annotation-fails
  (testing "Ball::itself = { x | x } cannot infer parameter type"
    (is (thrown-with-msg? Exception #"Cannot infer type for parameter 'x'"
                          (methods-ir rect-ball-schema
                                      "Ball::itself = { x | x }")))))

(deftest typed-identity-works
  (testing "explicit type annotation allows identity passthrough"
    (let [m (first (methods-ir rect-ball-schema
                              "Ball::itself = { Ball/b | b }"))]
      (is (= "Ball" (:return-type m)))
      (is (= [{:name "b" :type "Ball"}] (:parameters m))))))

(deftest interface-signature-ir
  (testing "Shape::area = { | } -> Int declares an interface signature"
    (let [methods (methods-ir shape-schema
                              "Shape::area = { | } -> Int
Triangle::area = { base * height }
Circle::area = { radius * radius }")
          m (first (filter #(= "Shape" (:class %)) methods))]
      (is (= "area" (:method-name m)))
      (is (= "Int" (:return-type m)))
      (is (:interface-signature? m))
      (is (nil? (:body m)))
      (is (= [] (:parameters m))))))

(deftest interface-signature-with-params
  (testing "interface parameters must be typed"
    (let [methods (methods-ir shape-schema
                              "Shape::scale = { Int/factor | } -> Int
Triangle::scale = { Int/factor | base * factor }
Circle::scale = { Int/factor | radius * factor }")
          m (first (filter #(= "Shape" (:class %)) methods))]
      (is (= [{:name "factor" :type "Int"}] (:parameters m)))
      (is (:interface-signature? m)))))

(deftest interface-implementation-validation
  (testing "each implementer must match the interface signature"
    (is (thrown-with-msg? Exception #"must implement Shape::area"
                          (methods-ir shape-schema
                                      "Shape::area = { | } -> Int")))
    (let [methods (methods-ir shape-schema
                              "Shape::area = { | } -> Int
Triangle::area = { base * height }
Circle::area = { radius * radius }")
          by-name (into {} (map (juxt :method-name identity)
                                (filter #(= "Circle" (:class %)) methods)))]
      (is (= "Int" (:return-type (get by-name "area")))))))

(deftest interface-signature-emits-haxe
  (testing "interface methods appear on generated Haxe interface"
    (let [schema (schema-ir shape-schema)
          methods (methods-ir shape-schema
                            "Shape::area = { | } -> Int
Triangle::area = { base * height }
Circle::area = { radius * radius }")
          haxe (ir-to-haxe/schema-ir-to-haxe schema methods)]
      (is (str/includes? haxe "interface Shape"))
      (is (str/includes? haxe "public function area(): Int;"))
      (is (str/includes? haxe "class Circle implements Shape"))
      (is (str/includes? haxe "public function area(): Int {")))))

(deftest external-type-at-lambda-param
  (testing "@Graphics/g registers Graphics as external and types the parameter"
    (let [schema-text "Circle = Int/x Int/y Int/radius"
          reaction-text "Circle::draw = { @Graphics/g |
  g.beginFill(16711680).drawCircle(x, y, radius).endFill()
} -> Void"
          methods (methods-ir schema-text reaction-text)
          draw (first methods)]
      (is (= [{:name "g" :type "Graphics"}] (:parameters draw)))
      (is (= "Void" (:return-type draw)))
      (let [haxe (ir-to-haxe/generate-method draw)]
        (is (str/includes? haxe "draw(g:Graphics): Void"))
        (is (str/includes? haxe "g.beginFill("))
        (is (str/includes? haxe "g.drawCircle("))
        (is (str/includes? haxe "g.endFill();"))
        (is (not (str/includes? haxe "return g.beginFill")))))))

(deftest external-type-without-at-fails
  (testing "Graphics/g without @ is not registered as external; calls on g fail"
    (is (thrown-with-msg? Exception #"Unknown method 'drawCircle' on Graphics"
                          (methods-ir "Circle = Int/x Int/y Int/radius"
                                      "Circle::draw = { Graphics/g | g.drawCircle(x, y, radius) } -> Void")))))

(deftest inject-method-is-reserved
  (testing "Methods cannot define inject; that name is for Target"
    (is (thrown-with-msg? Exception #"reserved"
                          (methods-ir ">Keys = Bool/left"
                                      "Keys::inject = { left }")))))
