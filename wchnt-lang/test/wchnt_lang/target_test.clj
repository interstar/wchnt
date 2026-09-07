(ns wchnt-lang.target-test
  (:require [clojure.test :refer :all]
            [wchnt-lang.target :as target]))

(def sample-target
  "%trace
    public static function wchnt_trace<T>(x:T):T {
        haxe.Log.trace(x);
        return x;
    }

%main
    public static function main():Void {
        var assemblage = gameFactory();
        for (i in 0...10) {
            assemblage.time.update();
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
                           "%trace\npublic static function wchnt_trace<T>(x:T):T { return x; }\n")))))

(deftest parse-target-rejects-duplicates
  (testing "duplicate %name fails"
    (is (thrown-with-msg? Exception #"Duplicate"
                          (target/parse-target
                           "%main\nfunction main():Void {}\n%main\nfunction main():Void {}\n")))))

(deftest parse-target-trace-needs-a-function
  (testing "%trace Haxe must declare a function to call from Methods"
    (is (thrown-with-msg? Exception #"function"
                          (target/parse-target
                           "%trace\ntrace(x);\n%main\nfunction main():Void {}\n")))))

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
                   "%init\nvar assemblage:Game;\nfunction init():Void { assemblage = gameFactory(); }\n\n"
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
                   "%init\nvar assemblage;\nfunction init() { assemblage = gameFactory(); }\n\n"
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
