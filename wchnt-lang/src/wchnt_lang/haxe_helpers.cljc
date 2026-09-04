(ns wchnt-lang.haxe-helpers
  "Haxe-side helper templates used by the IR -> Haxe generator.")

(def iwchnt-helper-interface
  "// Helper interface for complex type serialization
interface IWCHNTHelper {
    public function arrayToConstruction<T>(arr: Array<T>, depth: Int): String;
    public function mapToConstruction<K,V>(map: Map<K,V>, depth: Int): String;
    public function enumToConstruction(enumValue: Dynamic, depth: Int): String;
}")

(def iwchnt-helper-implementation
  "// Implementation of the helper interface
class WCHNTHelper implements IWCHNTHelper {
    public function new() {}
    
    public function arrayToConstruction<T>(arr: Array<T>, depth: Int): String {
        var ind = \"\";
        for (i in 0...depth) ind += \"  \";
        var nl = '\\n';
        var result = ind + '[:Array';
        for (item in arr) {
            if (Std.isOfType(item, String)) {
                result += nl + ind + '  ' + '\"' + item + '\"';
            } else if (Std.isOfType(item, IWCHNTObject)) {
                result += nl + ind + '  ' + (cast item : IWCHNTObject).toConstruction(depth + 1, this);
            } else {
                result += nl + ind + '  ' + Std.string(item);
            }
        }
        result += nl + ind + ']';
        return result;
    }
    
    public function mapToConstruction<K,V>(map: Map<K,V>, depth: Int): String {
        var ind = \"\";
        for (i in 0...depth) ind += \"  \";
        var nl = '\\n';
        var result = ind + '[:Map';
        for (key in map.keys()) {
            var value = map.get(key);
            result += nl + ind + '  ';
            if (Std.isOfType(key, String)) {
                result += '\"' + key + '\"';
            } else {
                result += Std.string(key);
            }
            result += ': ';
            if (Std.isOfType(value, String)) {
                result += '\"' + value + '\"';
            } else if (Std.isOfType(value, IWCHNTObject)) {
                result += (cast value : IWCHNTObject).toConstruction(depth + 1, this);
            } else {
                result += Std.string(value);
            }
        }
        result += nl + ind + ']';
        return result;
    }
    
    public function enumToConstruction(enumValue: Dynamic, depth: Int): String {
        var ind = \"\";
        for (i in 0...depth) ind += \"  \";
        return ind + Std.string(enumValue);
    }
}")

(def wchnt-runtime
  "// Fail-fast collection and string helpers. Copy-on-write for maps; Target may later pick a backing store.
class WCHNTRuntime {
    public static function arrayHead<T>(arr:Array<T>):T {
        if (arr.length == 0) throw \"Array::head of empty array\";
        return arr[0];
    }

    public static function arrayTail<T>(arr:Array<T>):Array<T> {
        if (arr.length == 0) throw \"Array::tail of empty array\";
        return arr.slice(1);
    }

    public static function mapGet<K,V>(m:Map<K,V>, key:K):V {
        if (!m.exists(key)) throw \"Map::get: key not found\";
        return cast m.get(key);
    }

    public static function mapPut<K,V>(m:Map<K,V>, key:K, value:V):Map<K,V> {
        var copy = m.copy();
        copy.set(key, value);
        return copy;
    }

    public static function mapRemove<K,V>(m:Map<K,V>, key:K):Map<K,V> {
        if (!m.exists(key)) throw \"Map::remove: key not found\";
        var copy = m.copy();
        copy.remove(key);
        return copy;
    }

    public static function substring(s:String, start:Int, end:Int):String {
        if (start < 0 || end < 0 || start > s.length || end > s.length || start > end)
            throw \"String::substring: invalid range\";
        return s.substring(start, end);
    }

    public static function times<T>(n:Int, f:Int -> T):Array<T> {
        if (n < 0) throw \"Int::times expected a non-negative count\";
        return [for (i in 0...n) f(i)];
    }
}")

(def openfl-imports
  "import openfl.display.Sprite;
import openfl.events.Event;")

(def openfl-lifecycle
  "public function new() {
        super();
        addEventListener(Event.ADDED_TO_STAGE, __wchntAdded);
    }

    private function __wchntAdded(_e:Event):Void {
        removeEventListener(Event.ADDED_TO_STAGE, __wchntAdded);
        init();
        addEventListener(Event.ENTER_FRAME, __wchntFrame);
    }

    private function __wchntFrame(_e:Event):Void {
        step();
    }")

(def iwchnt-object-interface
  "// Interface that all WCHNT objects implement (updated to use helper)
interface IWCHNTObject {
    public function toConstruction(depth:Int = 0, helper:IWCHNTHelper):String;
}")

