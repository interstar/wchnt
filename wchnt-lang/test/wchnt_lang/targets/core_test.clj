(ns wchnt-lang.targets.core-test
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [wchnt-lang.targets.core :as target]
            [wchnt-lang.targets.plugins :as plugins]
            [wchnt-lang.targets.requires :as requires]
            [wchnt-lang.compiler :as compiler]))

(defn- form-example
  []
  (slurp "live-examples/form_calculator.wcn"))

(deftest target-requires-types-external-method-calls
  (testing "@ target types and method signatures are available while checking Methods"
    (let [source (str "## Schema\n\n```\n"
                      "Paint = Int/x\n"
                      "```\n\n## Construction\n\n```\n"
                      "[:Paint 0]\n"
                      "```\n\n## Methods\n\n```\n"
                      "Paint::colour = {@WCHNTGraphics/g | g.color(255,0,0)}\n"
                      "```\n\n## Target\n\n```\n"
                      "%openfl\n\n%requires\n"
                      "WCHNTGraphics\n"
                      "WCHNTGraphics::color(Int,Int,Int) -> Int\n\n"
                      "%init\nfunction init() {}\n\n"
                      "%step\nfunction step() {}\n"
                      "```\n")
          cargo (compiler/compile-to-ir source)
          method (first (filter #(= "colour" (:method-name %))
                                (get-in cargo [:stash :methods-ir])))]
      (is (:success cargo) (first (:errors cargo)))
      (is (= "Int" (get-in method [:body :type]))))))

(deftest parse-requires-declarations
  (testing "class-only and typed method declarations produce external IR"
    (let [ir (requires/parse
               "WCHNTGraphics\nWCHNTGraphics::color(Int, Int, Int) -> Int\n")]
      (is (= #{"WCHNTGraphics"} (requires/provided-types ir)))
      (is (= {:args ["Int" "Int" "Int"]
              :arg-types ["Int" "Int" "Int"]
              :return "Int"}
             (requires/method-spec ir "WCHNTGraphics" "color" 3)))))
  (testing "Target includes structured requires data"
    (let [ir (target/parse-target
              "%openfl\n\n%requires\nWCHNTGraphics\n\n%init\nfunction init() {}\n\n%step\nfunction step() {}")]
      (is (= #{"WCHNTGraphics"} (:external-types ir))))))

(def sample-target
  "%terminal

%trace
    public static function wchnt_trace<T>(x:T):T {
        haxe.Log.trace(x);
        return x;
    }

%main
    public static function main():Void {
        var assemblage = GameAssemblage.factory();
        for (i in 0...10) {
            assemblage.time.update_mutates();
        }
    }
")

(deftest parse-empty-target
  (testing "blank Target is no bindings, no main, and no host"
    (is (= {:bindings {} :main nil :host nil :init nil :step nil}
           (target/parse-target "")))
    (is (= {:bindings {} :main nil :host nil :init nil :step nil}
           (target/parse-target "   \n")))))

(deftest parse-main-and-trace
  (testing "%trace and %main blocks extract Haxe and the callable function name"
    (let [ir (target/parse-target sample-target)]
      (is (= "terminal" (:host ir)))
      (is (nil? (:init ir)))
      (is (nil? (:step ir)))
      (is (= "wchnt_trace" (get-in ir [:bindings "trace" :fn-name])))
      (is (re-find #"function wchnt_trace" (get-in ir [:bindings "trace" :haxe])))
      (is (re-find #"function main" (get-in ir [:main :haxe])))
      (is (re-find #"time\.update" (get-in ir [:main :haxe]))))))

(deftest parse-target-requires-percent
  (testing "non-empty Target without %name fails"
    (is (thrown-with-msg? Exception #"Target"
                          (target/parse-target "function main():Void {}")))))

(deftest parse-target-requires-main
  (testing "a non-empty Target must define %main"
    (is (thrown-with-msg? Exception #"%main"
                          (target/parse-target
                           "%terminal\n\n%trace\npublic static function wchnt_trace<T>(x:T):T { return x; }\n")))))

(deftest parse-target-requires-host
  (testing "non-empty Target must name a host"
    (is (thrown-with-msg? Exception #"host"
                          (target/parse-target
                           "%main\npublic static function main():Void {}\n")))))

(deftest parse-target-rejects-duplicates
  (testing "duplicate %name fails"
    (is (thrown-with-msg? Exception #"Duplicate"
                          (target/parse-target
                           "%terminal\n\n%main\nfunction main():Void {}\n%main\nfunction main():Void {}\n")))))

(deftest parse-target-trace-needs-a-function
  (testing "%trace Haxe must declare a function to call from Methods"
    (is (thrown-with-msg? Exception #"function"
                          (target/parse-target
                          "%terminal\n\n%trace\ntrace(x);\n%main\nfunction main():Void {}\n")))))

(deftest parse-target-rejects-arbitrary-method-hooks
  (testing "Target exposes only the dedicated %trace diagnostic hook to Methods"
    (is (thrown-with-msg? Exception #"Only %trace"
                          (target/parse-target
                           "%terminal\n\n%log\nfunction log(x) { return x; }\n%main\nfunction main():Void {}\n")))))

(deftest parse-target-host-terminal
  (testing "%terminal names the host and is not a Haxe binding"
    (let [ir (target/parse-target
              "%terminal\n\n%main\npublic static function main():Void {}\n")]
      (is (= "terminal" (:host ir)))
      (is (nil? (get-in ir [:bindings "terminal"])))
      (is (re-find #"function main" (get-in ir [:main :haxe]))))))

(deftest parse-target-host-openfl
  (testing "%openfl names the OpenFL host and takes %init and %step"
    (let [ir (target/parse-target
              (str "%openfl\n\n"
                   "%init\nvar assemblage:Game;\nfunction init():Void { assemblage = GameAssemblage.factory(); }\n\n"
                   "%step\nfunction step():Void { assemblage = assemblage.step(); }\n"))]
      (is (= "openfl" (:host ir)))
      (is (nil? (:main ir)))
      (is (nil? (get-in ir [:bindings "openfl"])))
      (is (re-find #"function init" (get-in ir [:init :haxe])))
      (is (re-find #"function step" (get-in ir [:step :haxe]))))))

(deftest parse-target-openfl-requires-init-and-step
  (testing "%openfl without %init/%step fails"
    (is (thrown-with-msg? Exception #"%init"
                          (target/parse-target
                           "%openfl\n\n%step\nfunction step():Void {}\n")))
    (is (thrown-with-msg? Exception #"%step"
                          (target/parse-target
                           "%openfl\n\n%init\nfunction init():Void {}\n")))))

(deftest parse-target-openfl-rejects-main
  (testing "%openfl does not take %main"
    (is (thrown-with-msg? Exception #"%main"
                          (target/parse-target
                           "%openfl\n\n%main\nfunction main():Void {}\n%init\nfunction init():Void {}\n%step\nfunction step():Void {}\n")))))

(deftest parse-target-terminal-rejects-init-step
  (testing "%init/%step are not for %terminal"
    (is (thrown-with-msg? Exception #"%openfl"
                          (target/parse-target
                           "%terminal\n\n%init\nfunction init():Void {}\n%step\nfunction step():Void {}\n")))))

(deftest parse-target-init-must-be-named-init
  (testing "%init Haxe must declare function init"
    (is (thrown-with-msg? Exception #"init"
                          (target/parse-target
                           "%openfl\n\n%init\nfunction setup():Void {}\n%step\nfunction step():Void {}\n")))))

(deftest parse-target-init-may-have-helpers
  (testing "%init may define helpers; the block is still valid if function init exists"
    (let [ir (target/parse-target
              (str "%openfl\n\n"
                   "%init\nfunction held():Bool { return false; }\n"
                   "function init():Void {}\n\n"
                   "%step\nfunction step():Void {}\n"))]
      (is (re-find #"function init" (get-in ir [:init :haxe])))
      (is (re-find #"function held" (get-in ir [:init :haxe]))))))

(deftest parse-target-host-must-be-empty
  (testing "host % names must not contain Haxe"
    (is (thrown-with-msg? Exception #"empty"
                          (target/parse-target
                           "%terminal\nfunction oops() {}\n%main\nfunction main():Void {}\n")))))

(deftest parse-target-rejects-two-hosts
  (testing "only one host may be named"
    (is (thrown-with-msg? Exception #"host"
                          (target/parse-target
                           "%terminal\n%openfl\n%main\nfunction main():Void {}\n")))))

(deftest parse-target-host-canvas
  (testing "%canvas uses the same %init/%step lifecycle as OpenFL"
    (let [ir (target/parse-target
              (str "%canvas\n\n"
                   "%init\nvar assemblage;\nfunction init() { assemblage = GameAssemblage.factory(); }\n\n"
                   "%step\nfunction step() { assemblage = assemblage.step(); }\n"))]
      (is (= "canvas" (:host ir)))
      (is (nil? (:main ir)))
      (is (re-find #"function init" (get-in ir [:init :haxe])))
      (is (re-find #"function step" (get-in ir [:step :haxe]))))))

(deftest parse-target-canvas-requires-init-and-step
  (testing "%canvas without %init/%step fails"
    (is (thrown-with-msg? Exception #"%init"
                          (target/parse-target
                           "%canvas\n\n%step\nfunction step() {}\n")))
    (is (thrown-with-msg? Exception #"%step"
                          (target/parse-target
                           "%canvas\n\n%init\nfunction init() {}\n")))))

(deftest parse-target-host-cli
  (testing "%cli uses the same %init/%step lifecycle as OpenFL"
    (let [ir (target/parse-target
              (str "%cli\n\n"
                   "%init\nvar assemblage:Game;\nfunction init():Void { assemblage = GameAssemblage.factory(); }\n\n"
                   "%step\nfunction step(line:String):Void { assemblage = assemblage.move(line); }\n"))]
      (is (= "cli" (:host ir)))
      (is (nil? (:main ir)))
      (is (re-find #"function init" (get-in ir [:init :haxe])))
      (is (re-find #"function step" (get-in ir [:step :haxe]))))))

(deftest parse-target-cli-requires-init-and-step
  (testing "%cli without %init/%step fails"
    (is (thrown-with-msg? Exception #"%init"
                          (target/parse-target
                           "%cli\n\n%step\nfunction step(line:String):Void {}\n")))
    (is (thrown-with-msg? Exception #"%step"
                          (target/parse-target
                           "%cli\n\n%init\nfunction init():Void {}\n")))))

(deftest parse-target-cli-rejects-main
  (testing "%cli does not take %main"
    (is (thrown-with-msg? Exception #"%main"
                          (target/parse-target
                           "%cli\n\n%main\nfunction main():Void {}\n%init\nfunction init():Void {}\n%step\nfunction step(line:String):Void {}\n")))))

(deftest parse-target-host-cli-live
  (testing "%cli-live is the browser twin of %cli"
    (let [ir (target/parse-target
              (str "%cli-live\n\n"
                   "%init\nvar assemblage;\nfunction init() { assemblage = GameAssemblage.factory(); }\n\n"
                   "%step\nfunction step(line) { assemblage = assemblage.move(line); }\n"))]
      (is (= "cli-live" (:host ir)))
      (is (nil? (:main ir)))
      (is (re-find #"function init" (get-in ir [:init :haxe])))
      (is (re-find #"function step" (get-in ir [:step :haxe]))))))

(deftest parse-target-cli-live-requires-init-and-step
  (testing "%cli-live without %init/%step fails"
    (is (thrown-with-msg? Exception #"%init"
                          (target/parse-target
                           "%cli-live\n\n%step\nfunction step(line) {}\n")))
    (is (thrown-with-msg? Exception #"%step"
                          (target/parse-target
                           "%cli-live\n\n%init\nfunction init() {}\n")))))

(deftest parse-target-host-form
  (testing "%form is a live frame target with WCHNTForm available"
    (let [ir (plugins/parse-target
              (str "%form\n\n"
                   "%init\nfunction init() {}\n\n"
                   "%step\nfunction step() {}\n"))]
      (is (= "form" (:host ir)))
      (is (nil? (:main ir)))
      (is (re-find #"function init" (get-in ir [:init :haxe])))
      (is (re-find #"function step" (get-in ir [:step :haxe])))
      (is (contains? (:external-types ir) "WCHNTConsole"))
      (is (some #(= {:name "wchntConsole" :host-key :console} %)
                (get-in ir [:plugin :standard :bindings]))))))

(deftest form-target-validates-widget-schema
  (testing "missing and unexpected standard widget fields fail during schema validation"
    (let [missing (str/replace (form-example)
                               "Canvas = String/id Int/width Int/height"
                               "Canvas = String/id Int/width")
          extra (str/replace (form-example)
                             "Canvas = String/id Int/width Int/height"
                             "Canvas = String/id Int/width Int/height String/debug")
          missing-cargo (compiler/compile-to-ir missing)
          extra-cargo (compiler/compile-to-ir extra)]
      (is (re-find #"Canvas is missing field\(s\): height"
                   (first (:errors missing-cargo))))
      (is (re-find #"Canvas has unexpected field\(s\): debug"
                   (first (:errors extra-cargo)))))))

(deftest form-target-validates-construction
  (testing "widget construction arity and types are checked by the target hook"
    (let [wrong-arity (str/replace (form-example)
                                   "[:Canvas \"graph\" 800 400]"
                                   "[:Canvas \"graph\" 800]")
          wrong-type (str/replace (form-example)
                                  "[:Canvas \"graph\" 800 400]"
                                  "[:Canvas \"graph\" \"wide\" 400]")
          arity-cargo (compiler/compile-to-ir wrong-arity)
          type-cargo (compiler/compile-to-ir wrong-type)]
      (is (re-find #"%form construction: Canvas .* expects 3 argument\(s\), got 2"
                   (str (first (:errors arity-cargo)))))
      (is (re-find #"%form construction: Canvas field 'width' expects Int, got String"
                   (str (first (:errors type-cargo))))))))
