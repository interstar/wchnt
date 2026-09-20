(ns wchnt-lang.targets.interpreter-std-test
  "WCHNTMaths host: query methods return numbers; factory @ injects the handle."
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [wchnt-lang.compiler :as compiler]
            [wchnt-lang.grammars :as grammars]
            [wchnt-lang.parser :as parser]
            [wchnt-lang.ast-to-ir :as ast-to-ir]
            [wchnt-lang.reaction :as reaction]
            [wchnt-lang.interpret :as interpret]
            [wchnt-lang.targets.interpreter-std :as host]
            [wchnt-lang.targets.requires :as requires]
            [wchnt-lang.pipeline :as p]))

(defn- schema-ir
  [schema-text]
  (let [cargo (parser/schema-wchnt->schema-ast schema-text)]
    (ast-to-ir/schema-ast-to-ir (:value cargo))))

(defn- methods-ir
  [schema-text reaction-text]
  (reaction/reaction-ast-to-ir (grammars/parse-reaction reaction-text)
                               (assoc (schema-ir schema-text)
                                      :target-ir
                                      {:requires (requires/parse
                                                  (str "WCHNTMaths::randInt(Int) -> Int\n"
                                                       "WCHNTMaths::sin(Float) -> Float\n"
                                                       "WCHNTGraphics::color(Int, Int, Int) -> Int"))})
                               {:bindings {} :main nil
                                :target-ir {:requires (requires/parse
                                                       (str "WCHNTMaths::randInt(Int) -> Int\n"
                                                            "WCHNTMaths::sin(Float) -> Float\n"
                                                            "WCHNTGraphics::color(Int, Int, Int) -> Int"))}}))

(def roll-schema
  "Trio = Int/a Int/b Int/c
Roll = @WCHNTMaths/maths")

(defn- page
  [schema construction methods]
  (str "# maths\n## Schema\n```\n" schema
       "\n```\n## Construction\n```\n" construction
       "\n```\n## Methods\n```\n" methods
       "\n```\n## Target\n```\n%terminal\n\n%main\n"
       "public static function main():Void {\n"
       "    var assemblage = RollAssemblage.factory(wchntMaths);\n"
       "    wchntConsole.println(assemblage.once());\n"
       "}\n```\n"))

(def roll-methods
  (str "Roll::once = {\n"
       "  r1 = maths.randInt(10).\n"
       "  r2 = maths.randInt(10).\n"
       "  r3 = maths.randInt(10).\n"
       "  [:Trio r1 r2 r3]\n"
       "}\n\n"
       "Roll::wave = { maths.sin(0) }\n"))

(deftest randint-call-types-as-int
  (let [once (first (filter #(= "once" (:method-name %))
                            (methods-ir roll-schema roll-methods)))]
    (is (= "Trio" (:return-type once)))
    (is (= "Int" (get-in once [:lets 0 :value :type])))
    (is (= "WCHNTMaths" (get-in once [:lets 0 :value :external-type])))
    (is (= "randInt" (get-in once [:lets 0 :value :method])))))

(deftest sin-call-types-as-float
  (let [wave (first (filter #(= "wave" (:method-name %))
                            (methods-ir roll-schema roll-methods)))]
    (is (= "Float" (:return-type wave)))
    (is (= "sin" (get-in wave [:body :method])))
    (is (= "WCHNTMaths" (:external-type (:body wave))))))

(deftest unknown-external-method-remains-opaque
  (let [bad (first (methods-ir roll-schema "Roll::bad = { maths.foo() }"))]
    (is (= "WCHNTMaths" (:return-type bad)))
    (is (= "WCHNTMaths" (get-in bad [:body :type])))))

(deftest undeclared-external-result-cannot-be-used-as-a-number
  (try
    (reaction/reaction-ast-to-ir
     (grammars/parse-reaction
      "Roll::bad = { maths.sin(0) * 2.0 }")
     (assoc (schema-ir roll-schema)
            :target-ir
            {:requires (requires/parse "WCHNTMaths")})
     {:bindings {} :main nil})
    (is false "Undeclared external numeric result should fail")
    (catch Exception e
      (is (re-find #"Arithmetic expects Int or Float, got WCHNTMaths in Roll::bad"
                   (.getMessage e)))
      (is (re-find #"external call WCHNTMaths::sin"
                   (.getMessage e)))
      (is (re-find #"Target %requires" (.getMessage e))))))

(deftest randint-wrong-arity-fails
  (is (thrown-with-msg? Exception #"randInt expected \(1\)"
                        (methods-ir roll-schema "Roll::bad = { maths.randInt() }"))))

(deftest graphics-passthrough-stays-fluent
  (let [draw (first (methods-ir
                     "Dot = Int/x Int/y"
                     "Dot::draw = { @WCHNTGraphics/g | g.beginFill(1).endFill() }"))]
    (is (= "WCHNTGraphics" (get-in draw [:body :type])))
    (is (= "WCHNTGraphics" (get-in draw [:body :external-type])))))

(deftest graphics-colour-queries-return-int
  (let [draw (first (methods-ir
                     "Dot = Int/x"
                     "Dot::colour = { @WCHNTGraphics/g | g.color(255, 0, 0) }"))]
    (is (= "Int" (get-in draw [:body :type])))
    (is (= "WCHNTGraphics" (get-in draw [:body :external-type])))))

(deftest interpret-pulls-three-distinct-rands
  (let [src (page roll-schema "[:Roll maths]" roll-methods)
        cargo (compiler/compile-to-ir src)
        schema-ir (get-in cargo [:stash :schema-ir])
        methods (get-in cargo [:stash :methods-ir])
        construction (get-in cargo [:stash :construction-ir])
        xs (atom [3 7 1])
        maths (host/make-maths {"randInt" (fn [n]
                                            (is (= 10 n))
                                            (let [v (first @xs)]
                                              (swap! xs rest)
                                              v))})
        root (interpret/construct schema-ir construction methods [maths])
        trio (interpret/call schema-ir methods root "once" [])]
    (is (= 3 (interpret/get-field trio "a")))
    (is (= 7 (interpret/get-field trio "b")))
    (is (= 1 (interpret/get-field trio "c")))
    (is (= 0.0 (interpret/call schema-ir methods root "wave" [])))))

(deftest randint-rejects-non-positive
  (is (thrown-with-msg? Exception #"n > 0"
                        (host/invoke (host/make-maths) "randInt" [0]))))

(deftest hsv-packs-rgb
  (let [white (host/invoke (host/make-maths) "hsv" [0.0 0.0 1.0])
        red (host/invoke (host/make-maths) "hsv" [0.0 1.0 1.0])]
    (is (= 0xffffff white))
    (is (= 0xff0000 red))))

(deftest haxe-emits-maths-host-and-factory
  (let [cargo (compiler/compile (page roll-schema "[:Roll maths]" roll-methods))]
    (is (:success cargo) (first (:errors cargo)))
    (let [preamble (get-in cargo [:value :preamble] "")
          factory (get-in cargo [:value :classes] "")
          main-class (get-in cargo [:value :main-class] "")
          classes (get-in cargo [:value :classes] "")]
      (is (str/includes? preamble "class WCHNTMaths"))
      (is (str/includes? preamble "function randInt(n:Int):Int"))
      (is (str/includes? preamble "function sin(x:Float):Float"))
      (is (str/includes? main-class "public var wchntMaths"))
      (is (str/includes? factory "factory(maths: WCHNTMaths)"))
      (is (str/includes? main-class "RollAssemblage.factory(wchntMaths)"))
      (is (str/includes? classes "'@WCHNTMaths'"))
      (is (not (str/includes? classes "this.maths.toConstruction"))))))
