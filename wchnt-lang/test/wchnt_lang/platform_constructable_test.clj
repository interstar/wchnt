(ns wchnt-lang.platform-constructable-test
  "Tests for platform-constructible host types via Type::CONSTRUCT in %requires."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [wchnt-lang.compiler :as compiler]
            [wchnt-lang.pipeline :as p]
            [wchnt-lang.ir :as ir]
            [wchnt-lang.interpret :as interpret]
            [wchnt-lang.targets.haxe-backend :as haxe]
            [wchnt-lang.targets.requires :as requires]))

(defn- compile-ir
  [markdown]
  (let [cargo (compiler/compile-to-ir markdown)]
    (when (p/failed? cargo)
      (throw (ex-info (or (first (:errors cargo)) "compile failed")
                      {:errors (:errors cargo)})))
    cargo))

(defn- expect-fail
  [markdown]
  (let [cargo (compiler/compile-to-ir markdown)]
    (is (p/failed? cargo) (str "expected failure, got " (pr-str cargo)))
    (or (first (:errors cargo)) "")))

(def ^:private person-date-md
  "# Person + Date

## Schema

```
Person = String/name Date/dob
```

## Construction

```
[:Person \"Ada\" [:Date \"1815-12-10\"]]
```

## Methods

```
Person::bornYear = { dob.year() }
Person::withDob = { String/s | [:Person name [:Date s]] }
```

## Target

```
%terminal

%requires
Date
Date::CONSTRUCT(String) -> Date
Date::year() -> Int

%main
public static function main():Void {
}
```
")

(deftest date-inferred-from-construct
  (testing "Date with CONSTRUCT is platform-constructible; Schema field is ordinary"
    (let [cargo (compile-ir person-date-md)
          schema (get-in cargo [:stash :schema-ir])
          person (first (filter #(= "Person" (:name %)) (:assemblages schema)))
          dob (first (filter #(= "dob" (:component-name %)) (:components person)))]
      (is (= :ordinary (:relationship dob)))
      (is (= "Date" (:type-name dob)))
      (is (contains? (:platform-constructible-types schema) "Date"))
      (is (ir/platform-constructible-type? schema "Date"))
      (is (ir/external-type? schema "Date"))
      (is (not (ir/borrowed-external-type? schema "Date"))))))

(deftest requires-construct-stored
  (testing "CONSTRUCT is stored under :construct, not callable methods"
    (let [req (requires/parse "Date\nDate::CONSTRUCT(String) -> Date\nDate::year() -> Int")]
      (is (= [{:args ["String"] :arg-types ["String"] :return "Date"}]
             (get-in req [:classes "Date" :construct])))
      (is (nil? (get-in req [:classes "Date" :methods "CONSTRUCT"])))
      (is (some? (requires/method-spec req "Date" "year" 0)))
      (is (= #{"Date"} (requires/constructible-types req))))))

(deftest requires-construct-return-must-match
  (is (thrown-with-msg? Exception #"must return Date"
                        (requires/parse "Date::CONSTRUCT(String) -> Int"))))

(deftest requires-rejects-new-ctor-name
  (is (thrown-with-msg? Exception #"reserved"
                        (requires/parse "Date::NEW(String) -> Date"))))

(deftest construct-without-construct-line-fails
  (let [err (expect-fail
             "# No CONSTRUCT

## Schema
```
Person = String/name Date/dob
```

## Construction
```
[:Person \"Ada\" [:Date \"1815-12-10\"]]
```

## Target
```
%terminal
%requires
Date
Date::year() -> Int
%main
public static function main():Void {}
```
")]
    (is (re-find #"CONSTRUCT|Cannot construct|borrowed|external" err))))

(deftest free-name-in-date-slot-fails
  (let [err (expect-fail
             "# Free name

## Schema
```
Person = String/name Date/dob
```

## Construction
```
[:Person \"Ada\" dob]
```

## Target
```
%terminal
%requires
Date
Date::CONSTRUCT(String) -> Date
%main
public static function main():Void {}
```
")]
    (is (re-find #"not allowed|Free name" err))))

(deftest at-slot-rejects-construction
  (let [err (expect-fail
             "# @ cannot construct

## Schema
```
Sketch = String/name @Pen
```

## Construction
```
[:Sketch \"x\" [:Pen]]
```

## Target
```
%terminal
%requires
Pen
%main
public static function main():Void {}
```
")]
    (is (re-find #"external|Cannot construct" err))))

(deftest wrong-construct-arity-fails
  (let [err (expect-fail
             "# Wrong arity

## Schema
```
Person = String/name Date/dob
```

## Construction
```
[:Person \"Ada\" [:Date \"a\" \"b\"]]
```

## Target
```
%terminal
%requires
Date
Date::CONSTRUCT(String) -> Date
%main
public static function main():Void {}
```
")]
    (is (re-find #"CONSTRUCT|overload" err))))

(deftest construction-tags-host-construct
  (let [cargo (compile-ir person-date-md)
        objects (get-in cargo [:stash :construction-ir :objects])
        date-objs (filter #(and (= "Date" (:class-name (val %)))
                                (:host-construct? (val %)))
                          objects)]
    (is (seq date-objs))))

(deftest methods-call-year-and-host-construct
  (let [cargo (compile-ir person-date-md)
        methods (get-in cargo [:stash :methods-ir])
        born (first (filter #(= "bornYear" (:method-name %)) methods))
        with-dob (first (filter #(= "withDob" (:method-name %)) methods))]
    (is (= :call (:expr (:body born))))
    (is (= "year" (:method (:body born))))
    (is (= :construct (:expr (:body with-dob))))
    (let [date-arg (second (:args (:body with-dob)))]
      (is (= :host-construct (:expr date-arg)))
      (is (= "Date" (:class-name date-arg))))))

(deftest calling-construct-as-method-fails
  (let [err (expect-fail
             "# Call CONSTRUCT

## Schema
```
Person = String/name Date/dob
```

## Construction
```
[:Person \"Ada\" [:Date \"1815-12-10\"]]
```

## Methods
```
Person::bad = { dob.CONSTRUCT(\"x\") }
```

## Target
```
%terminal
%requires
Date
Date::CONSTRUCT(String) -> Date
Date::year() -> Int
%main
public static function main():Void {}
```
")]
    (is (re-find #"CONSTRUCT|metadata|requires" err))))

(deftest haxe-to-construction-instance-tokens
  (let [cargo (compile-ir person-date-md)
        schema (get-in cargo [:stash :schema-ir])
        person (first (filter #(= "Person" (:name %)) (:assemblages schema)))
        parts (haxe/generate-to-construction-parts (:components person) schema)
        joined (str/join " " parts)]
    (is (str/includes? joined "%instanceOfDate"))
    (is (not (str/includes? joined "'@Date'")))
    (let [classes (haxe/schema-ir-to-haxe schema (get-in cargo [:stash :methods-ir]))]
      (is (str/includes? classes "%instanceOfDate"))
      (is (str/includes? classes "new Date(")))))

(deftest haxe-at-instance-token
  (let [cargo (compile-ir
               "# Maths

## Schema
```
Roll = @WCHNTMaths/maths
```

## Construction
```
[:Roll maths]
```

## Target
```
%terminal
%requires
WCHNTMaths
%main
public static function main():Void {}
```
")
        schema (get-in cargo [:stash :schema-ir])
        classes (haxe/schema-ir-to-haxe schema [])]
    (is (str/includes? classes "@instanceOfWCHNTMaths"))))

(defn- stub-date-ctor
  [args]
  (let [s (first args)]
    {:wchnt/host "Date"
     :wchnt/value s
     :methods {"year" (fn []
                        (when-not (string? s)
                          (throw (ex-info "stub Date needs string" {:s s})))
                        (Integer/parseInt (subs s 0 4)))}}))

(deftest interpreter-host-construct-and-print
  (let [cargo (compile-ir person-date-md)
        schema (assoc-in (get-in cargo [:stash :schema-ir])
                         [:target-ir :host-constructors]
                         {"Date" stub-date-ctor})
        root (interpret/construct schema
                                  (get-in cargo [:stash :construction-ir])
                                  (get-in cargo [:stash :methods-ir]))
        year (interpret/call schema
                             (get-in cargo [:stash :methods-ir])
                             root
                             "bornYear"
                             [])]
    (is (= 1815 year))
    (let [dob (interpret/get-field schema root "dob")]
      (is (:wchnt/platform-constructible? dob))
      (is (= "Date" (:wchnt/host dob))))))

(deftest schema-percent-sigil-rejected
  (let [err (expect-fail
             "# Bad %

## Schema
```
Person = String/name %Date/dob
```

## Construction
```
[:Person \"Ada\" [:Date \"1815-12-10\"]]
```

## Target
```
%terminal
%requires
Date
Date::CONSTRUCT(String) -> Date
%main
public static function main():Void {}
```
")]
    (is (re-find #"(?i)parse|schema|unexpected|%" err))))

(deftest date-in-array-and-map
  (testing "bare Date in [Date] and {String:Date} without Stamp wrapper"
    (let [cargo (compile-ir
                 "# Dates in collections

## Schema
```
Bundle = [Date]/dates {String:Date}/byName
```

## Construction
```
[:Bundle
  [:Array/Date [:Date \"a\"] [:Date \"b\"]]
  {String:Date \"x\":[:Date \"c\"]}]
```

## Target
```
%terminal
%requires
Date
Date::CONSTRUCT(String) -> Date
%main
public static function main():Void {}
```
")
          schema (get-in cargo [:stash :schema-ir])
          f (get-in cargo [:stash :construction-ir])]
      (is (ir/platform-constructible-type? schema "Date"))
      (is (some? f))
      (let [classes (haxe/schema-ir-to-haxe schema [])]
        (is (str/includes? classes "Array<Date>"))
        (is (str/includes? classes "Map<String, Date>"))))))
