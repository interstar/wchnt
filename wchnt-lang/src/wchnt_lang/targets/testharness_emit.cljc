(ns wchnt-lang.targets.testharness-emit
  "Expand %testharness suite IR into Haxe Main + fixture helpers."
  (:require [clojure.string :as str]
            [wchnt-lang.parser :as parser]
            [wchnt-lang.pipeline :as p]
            [wchnt-lang.ast-to-ir :as ast-to-ir]
            [wchnt-lang.targets.haxe-backend :as ir-to-haxe]
            [wchnt-lang.targets.testharness-std :as std]))

(defn- rewrite-assert-expr
  "Map WCHNT update! calls onto the Haxe update_mutates entry point."
  [expr]
  (str/replace expr #"update!" "update_mutates"))

(defn- escape-label
  [label]
  (-> label
      (str/replace "\\" "\\\\")
      (str/replace "\"" "\\\"")))

(defn- parse-construction-ir
  [construction-text schema-ir]
  (let [parsed (parser/parse-construction-unified construction-text)]
    (when (p/failed? parsed)
      (throw (ex-info (or (first (:errors parsed))
                          "%with construction parse failed")
                      {:text construction-text})))
    (ast-to-ir/construction-ast-to-ir (:value parsed) schema-ir)))

(defn- emit-fixture-fn
  "Emit a private static factory for one %with construction."
  [index {:keys [construction-text root-class]} schema-ir]
  (let [construction-ir (parse-construction-ir construction-text schema-ir)
        body (ir-to-haxe/generate-factory-body construction-ir schema-ir)
        fn-name (str "__fixture" index)]
    {:fn-name fn-name
     :root-class root-class
     :haxe (str "    static function " fn-name "(): " root-class " {\n"
                body "\n"
                "    }")}))

(defn- emit-with-line
  [{:keys [binding root-class]} fixture declared?]
  (if declared?
    (str "        " binding " = " (:fn-name fixture) "();")
    (str "        var " binding ": " root-class " = " (:fn-name fixture) "();")))

(defn- emit-assert-lines
  [{:keys [cases]}]
  (mapv (fn [{:keys [label expr]}]
          (str "        tests.assertTrue(\""
               (escape-label label)
               "\", "
               (rewrite-assert-expr expr)
               ");"))
        cases))

(defn- emit-suite-lines
  "Walk suite steps in order. Rebinding the same fixture name reassigns."
  [suite fixtures]
  (:lines
   (reduce (fn [{:keys [lines with-i declared]} step]
             (case (:op step)
               :with
               (let [binding (:binding step)
                     declared? (contains? declared binding)
                     line (emit-with-line step (nth fixtures with-i) declared?)]
                 {:lines (conj lines line)
                  :with-i (inc with-i)
                  :declared (conj declared binding)})
               :assert
               {:lines (into lines (emit-assert-lines step))
                :with-i with-i
                :declared declared}))
           {:lines [] :with-i 0 :declared #{}}
           suite)))

(defn- fixture-entries
  [suite schema-ir]
  (->> suite
       (filter #(= :with (:op %)))
       (map-indexed (fn [i step]
                      (-> (emit-fixture-fn i step schema-ir)
                          (assoc :binding (:binding step)))))))

(defn- emit-main-class
  [suite schema-ir]
  (let [fixtures (fixture-entries suite schema-ir)
        fixture-fns (str/join "\n\n" (map :haxe fixtures))
        suite-lines (emit-suite-lines suite fixtures)
        body (str/join "\n" (conj (vec suite-lines) "        tests.report();"))]
    (str "class Main {\n"
         std/wchnt-unit-tests-binding "\n\n"
         fixture-fns "\n\n"
         "    public static function main():Void {\n"
         body "\n"
         "    }\n"
         "}")))

(defn- artifacts-for-cargo
  [cargo include-helpers?]
  (let [schema-ir (get-in cargo [:stash :schema-ir])
        construction-ir (get-in cargo [:stash :construction-ir])
        methods-ir (or (get-in cargo [:stash :methods-ir]) [])]
    (if construction-ir
      (let [schema-ir (assoc schema-ir :root-class (:root-class construction-ir))
            methods-for-codegen (into methods-ir (vals (or (:imported-methods schema-ir) {})))
            classes (ir-to-haxe/schema-ir-to-haxe schema-ir methods-for-codegen include-helpers?)
            factory (ir-to-haxe/generate-construction-factory construction-ir schema-ir)
            wrapper (ir-to-haxe/generate-haxe-assemblage-class schema-ir methods-ir factory)]
        {:classes classes :wrapper wrapper})
      {:classes (ir-to-haxe/schema-ir-to-haxe schema-ir methods-ir include-helpers?)
       :wrapper ""})))

(defn emit-program
  "Emit schema classes plus a Main that runs the %testharness suite."
  [cargo]
  (let [codeblocks (get-in cargo [:stash :codeblocks])
        target-ir (get-in cargo [:stash :target-ir])
        schema-ir (get-in cargo [:stash :schema-ir])
        suite (:suite target-ir)
        page-kind (or (:page-kind codeblocks) :library)
        local-artifacts (artifacts-for-cargo cargo true)
        imported-artifacts (for [imported (vals (get-in cargo [:stash :imported-cargos] {}))]
                             (artifacts-for-cargo imported false))
        classes (str/join "\n"
                          (concat [(:classes local-artifacts)
                                   (:wrapper local-artifacts)]
                                  (map :classes imported-artifacts)
                                  (map :wrapper imported-artifacts)))]
    (when (empty? suite)
      (throw (ex-info "%testharness suite is empty" {})))
    {:classes classes
     :factory ""
     :main ""
     :init ""
     :step ""
     :preamble std/wchnt-unit-tests-class
     :main-class (emit-main-class suite schema-ir)
     :has-construction? true
     :page-kind page-kind
     :host "testharness"
     :codeblocks codeblocks
     :warnings []}))
