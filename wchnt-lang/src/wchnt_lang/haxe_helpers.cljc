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
        var result = ind + '{';
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
        result += nl + ind + '}';
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

    public static function mapGetDefault<K,V>(m:Map<K,V>, key:K, fallback:V):V {
        if (m.exists(key)) return cast m.get(key);
        return fallback;
    }

    public static function mapExists<K,V>(m:Map<K,V>, key:K):Bool {
        return m.exists(key);
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

    public static function tplHoleName(name:String):Bool {
        if (name.length == 0) return false;
        var c0 = name.charCodeAt(0);
        if (!((c0 >= 65 && c0 <= 90) || (c0 >= 97 && c0 <= 122) || c0 == 95)) return false;
        for (i in 1...name.length) {
            var c = name.charCodeAt(i);
            if (!((c >= 65 && c <= 90) || (c >= 97 && c <= 122) || (c >= 48 && c <= 57) || c == 95)) return false;
        }
        return true;
    }

    public static function tpl(s:String, vars:Map<String, String>):String {
        var out = new StringBuf();
        var i = 0;
        while (i < s.length) {
            var c = s.charAt(i);
            if (c == \"{\") {
                var close = s.indexOf(\"}\", i + 1);
                if (close < 0) throw \"String::tpl: unmatched '{'\";
                var name = s.substring(i + 1, close);
                if (!tplHoleName(name)) throw \"String::tpl: bad hole '{\" + name + \"}'\";
                if (!vars.exists(name)) throw \"String::tpl: missing '\" + name + \"'\";
                out.add(vars.get(name));
                i = close + 1;
            } else if (c == \"}\") {
                throw \"String::tpl: unmatched '}'\";
            } else {
                out.addChar(s.charCodeAt(i));
                i++;
            }
        }
        return out.toString();
    }

    public static function mapMap<K,V,W>(m:Map<K,V>, f:K -> V -> W, out:Map<K,W>):Map<K,W> {
        for (k in m.keys()) {
            out.set(k, f(k, cast m.get(k)));
        }
        return out;
    }

    public static function mapFilter<K,V>(m:Map<K,V>, f:K -> V -> Bool):Map<K,V> {
        var out = m.copy();
        for (k in m.keys()) {
            var v = cast m.get(k);
            if (!f(k, v)) out.remove(k);
        }
        return out;
    }

    public static function mapFold<K,V,A>(m:Map<K,V>, init:A, f:A -> K -> V -> A):A {
        var acc = init;
        for (k in m.keys()) {
            acc = f(acc, k, cast m.get(k));
        }
        return acc;
    }

    public static function times<T>(n:Int, f:Int -> T):Array<T> {
        if (n < 0) throw \"Int::times expected a non-negative count\";
        return [for (i in 0...n) f(i)];
    }
}")

(def openfl-imports
  "import openfl.display.Sprite;
import openfl.display.Graphics;
import openfl.events.Event;
import openfl.events.KeyboardEvent;
import openfl.text.TextField;
import openfl.text.TextFormat;
import openfl.ui.Keyboard;")

(def openfl-graphics-wrapper
  "// Portable draw surface for Target: same API on OpenFL and canvas (see WCHNTHarness).
class WCHNTGraphics {
    var g:Graphics;
    var hud:TextField;
    var sprite:Sprite;
    var bgColor:Int;
    var bgAlpha:Float;
    var fillColor:Int;

    public function new(sprite:Sprite) {
        g = sprite.graphics;
        this.sprite = sprite;
        bgColor = 0x111111;
        bgAlpha = 1;
        fillColor = 0xFFFFFF;
        hud = new TextField();
        hud.selectable = false;
        hud.mouseEnabled = false;
        hud.defaultTextFormat = new TextFormat(\"_sans\", 16, 0xFFFFFF);
        sprite.addChild(hud);
    }

    public function background(color:Int, ?alpha:Float):Void {
        bgColor = color;
        bgAlpha = (alpha == null ? 1 : alpha);
    }

    public function clear():Void {
        if (bgAlpha >= 1) {
            g.clear();
        } else {
            g.lineStyle();
        }
        g.beginFill(bgColor, bgAlpha);
        g.drawRect(0, 0, sprite.stage.stageWidth, sprite.stage.stageHeight);
        g.endFill();
    }

    public function beginFill(color:Int, ?alpha:Float):Void {
        fillColor = color;
        g.beginFill(color, (alpha == null ? 1 : alpha));
    }

    public inline function endFill():Void g.endFill();

    public inline function noStroke():Void g.lineStyle();

    public function lineStyle(?thickness:Float, ?color:Int, ?alpha:Float):Void {
        if (thickness == null) {
            g.lineStyle();
        } else {
            g.lineStyle(thickness, color, (alpha == null ? 1 : alpha));
        }
    }

    public inline function drawRect(x:Float, y:Float, w:Float, h:Float):Void g.drawRect(x, y, w, h);
    public inline function drawCircle(x:Float, y:Float, r:Float):Void g.drawCircle(x, y, r);
    public inline function drawEllipse(x:Float, y:Float, rx:Float, ry:Float):Void g.drawEllipse(x, y, rx, ry);

    public function drawLine(x1:Float, y1:Float, x2:Float, y2:Float):Void {
        g.moveTo(x1, y1);
        g.lineTo(x2, y2);
    }

    public inline function moveTo(x:Float, y:Float):Void g.moveTo(x, y);
    public inline function lineTo(x:Float, y:Float):Void g.lineTo(x, y);

    public function fillText(text:String, x:Float, y:Float):Void {
        hud.text = text;
        hud.x = x;
        hud.y = y;
        hud.textColor = fillColor;
    }
}")

(def openfl-lifecycle
  "public var wchntGraphics:WCHNTGraphics;

    public function new() {
        super();
        addEventListener(Event.ADDED_TO_STAGE, __wchntAdded);
    }

    private function __wchntAdded(_e:Event):Void {
        removeEventListener(Event.ADDED_TO_STAGE, __wchntAdded);
        wchntGraphics = new WCHNTGraphics(this);
        init();
        addEventListener(Event.ENTER_FRAME, __wchntFrame);
    }

    private function __wchntFrame(_e:Event):Void {
        step();
    }")

(def wchnt-console-class
  "// Portable text console for Target: %cli, %terminal, %openfl, live %cli-live.
class WCHNTConsole {
    var helper:WCHNTHelper;

    public function new() {
        helper = new WCHNTHelper();
    }

    function isMap(value:Dynamic):Bool {
        return Std.isOfType(value, haxe.ds.StringMap)
            || Std.isOfType(value, haxe.ds.IntMap)
            || Std.isOfType(value, haxe.ds.EnumValueMap)
            || Std.isOfType(value, haxe.ds.ObjectMap);
    }

    public function format(value:Dynamic):String {
        if (value == null) return \"null\";
        if (Std.isOfType(value, String)) return cast value;
        if (Std.isOfType(value, IWCHNTObject))
            return (cast value : IWCHNTObject).toConstruction(0, helper);
        if (Std.isOfType(value, Array))
            return helper.arrayToConstruction(cast value, 0);
        if (isMap(value))
            return helper.mapToConstruction(cast value, 0);
        return Std.string(value);
    }

    public function print(value:Dynamic):Void {
        #if sys
        Sys.print(format(value));
        #else
        haxe.Log.trace(format(value), null);
        #end
    }

    public function println(value:Dynamic):Void {
        #if sys
        Sys.println(format(value));
        #else
        haxe.Log.trace(format(value), null);
        #end
    }
}")

(def wchnt-maths-class
  "// Portable maths for Target. Queries return numbers; there are no Void methods.
class WCHNTMaths {
    public function new() {}

    public function rand():Float { return Math.random(); }
    public function pi():Float { return Math.PI; }

    public function randInt(n:Int):Int {
        if (n <= 0) throw \"WCHNTMaths.randInt requires n > 0\";
        return Std.int(Math.random() * n);
    }

    public function sin(x:Float):Float { return Math.sin(x); }
    public function cos(x:Float):Float { return Math.cos(x); }
    public function tan(x:Float):Float { return Math.tan(x); }
    public function asin(x:Float):Float { return Math.asin(x); }
    public function acos(x:Float):Float { return Math.acos(x); }
    public function atan(x:Float):Float { return Math.atan(x); }
    public function atan2(y:Float, x:Float):Float { return Math.atan2(y, x); }
    public function abs(x:Float):Float { return Math.abs(x); }
    public function floor(x:Float):Int { return Math.floor(x); }
    public function ceil(x:Float):Int { return Math.ceil(x); }
    public function round(x:Float):Int { return Math.round(x); }
    public function sqrt(x:Float):Float { return Math.sqrt(x); }
    public function log(x:Float):Float { return Math.log(x); }
    public function exp(x:Float):Float { return Math.exp(x); }
    public function pow(x:Float, y:Float):Float { return Math.pow(x, y); }
    public function min(x:Float, y:Float):Float { return Math.min(x, y); }
    public function max(x:Float, y:Float):Float { return Math.max(x, y); }

    public function hsv(h:Float, s:Float, v:Float):Int {
        var hh = ((h % 1) + 1) % 1;
        var i = Math.floor(hh * 6);
        var f = hh * 6 - i;
        var p = v * (1 - s);
        var q = v * (1 - f * s);
        var t = v * (1 - (1 - f) * s);
        var r:Float;
        var g:Float;
        var b:Float;
        switch (((i % 6) + 6) % 6) {
            case 0: r = v; g = t; b = p;
            case 1: r = q; g = v; b = p;
            case 2: r = p; g = v; b = t;
            case 3: r = p; g = q; b = v;
            case 4: r = t; g = p; b = v;
            default: r = v; g = p; b = q;
        }
        function ch(x:Float):Int {
            var n = Math.floor(x * 255);
            if (n < 0) return 0;
            if (n > 255) return 255;
            return n;
        }
        return (ch(r) << 16) | (ch(g) << 8) | ch(b);
    }
}")

(def wchnt-console-binding
  "public static var wchntConsole = new WCHNTConsole();")

(def wchnt-maths-binding
  "public static var wchntMaths = new WCHNTMaths();")

(def cli-lifecycle
  "public static function main():Void {
        var app = new Main();
        app.init();
        while (true) {
            var line:String = null;
            try {
                line = Sys.stdin().readLine();
            } catch (e:haxe.io.Eof) {
                break;
            }
            if (line == null) break;
            app.step(line);
        }
    }")

(def iwchnt-object-interface
  "// Interface that all WCHNT objects implement (updated to use helper)
interface IWCHNTObject {
    public function toConstruction(depth:Int = 0, helper:IWCHNTHelper):String;
}")

