(ns wchnt-lang.targets.testharness-std
  "Haxe standard library for the %testharness target.")

(def wchnt-unit-tests-class
  "// Assert harness for %testharness. Counts pass/fail and exits non-zero on failure (sys).
class WCHNTUnitTests {
    var passed:Int;
    var failed:Int;

    public function new() {
        passed = 0;
        failed = 0;
    }

    function say(msg:String):Void {
        #if sys
        Sys.println(msg);
        #else
        haxe.Log.trace(msg, null);
        #end
    }

    public function assertTrue(label:String, cond:Bool):Void {
        if (cond) {
            passed++;
            say(\"PASS: \" + label);
        } else {
            failed++;
            say(\"FAIL: \" + label);
        }
    }

    public function assertEq(label:String, expected:Dynamic, actual:Dynamic):Void {
        assertTrue(label, expected == actual);
    }

    public function report():Void {
        say(\"Tests: \" + (passed + failed) + \"  passed: \" + passed + \"  failed: \" + failed);
        #if sys
        if (failed > 0) Sys.exit(1);
        #end
    }
}")

(def wchnt-unit-tests-binding
  "    static var tests:WCHNTUnitTests = new WCHNTUnitTests();")
