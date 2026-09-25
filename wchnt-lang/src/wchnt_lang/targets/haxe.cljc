(ns wchnt-lang.targets.haxe
  "Haxe target emission shared by the Haxe target plugins."
  (:require [clojure.string :as str]
            [wchnt-lang.targets.haxe-backend :as ir-to-haxe]
            [wchnt-lang.targets.haxe-std :as haxe-std]))

(defn- class-body
  [& parts]
  (str/join "\n" (remove str/blank? parts)))

(defn- emit-terminal-main
  [helpers main]
  (when (str/blank? (or main ""))
    (throw (ex-info "Construction programs must define %main in Target" {})))
  (let [run (-> main
                (str/replace #"static\s+function\s+main" "function run")
                (str/replace #"function\s+main" "function run"))]
    (str "class Main {\n"
         (class-body haxe-std/wchnt-console-binding
                     haxe-std/wchnt-maths-binding
                     helpers run
                     haxe-std/terminal-lifecycle)
         "\n}")))

(defn- emit-openfl-main
  [helpers init step]
  (when (or (str/blank? (or init "")) (str/blank? (or step "")))
    (throw (ex-info "%openfl requires %init and %step" {})))
  (str "class Main extends Sprite {\n"
       (class-body haxe-std/wchnt-console-binding
                   haxe-std/wchnt-maths-binding
                   helpers init step
                   haxe-std/openfl-lifecycle)
       "\n}"))

(defn- emit-cli-main
  [helpers init step]
  (when (or (str/blank? (or init "")) (str/blank? (or step "")))
    (throw (ex-info "%cli requires %init and %step" {})))
  (str "class Main {\n"
       (class-body haxe-std/wchnt-console-binding
                   haxe-std/wchnt-maths-binding
                   helpers init step
                   haxe-std/cli-lifecycle)
       "\n}"))

(defn- emit-main-class
  [target-ir]
  (if (nil? (:host target-ir))
    ""
    (let [host (:host target-ir)
          helpers (str/join "\n"
                            (map :haxe
                                 (vals (:bindings (or target-ir {:bindings {}})))))
          main (get-in target-ir [:main :haxe])
          init (get-in target-ir [:init :haxe])
          step (get-in target-ir [:step :haxe])]
      (case host
        "terminal" (emit-terminal-main helpers main)
        "openfl" (emit-openfl-main helpers init step)
        "cli" (emit-cli-main helpers init step)
        "canvas" (throw (ex-info "%canvas is for the live interpreter, not the Haxe backend"
                                  {:host host}))
        "cli-live" (throw (ex-info "%cli-live is for the live interpreter, not the Haxe backend"
                                    {:host host}))
        "testharness-live" (throw (ex-info "%testharness-live is for the live interpreter, not the Haxe backend"
                                           {:host host}))
        (throw (ex-info (str "Unknown Target host '" host "'") {:host host}))))))

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
  "Turn a completed Haxe cargo into the compiler's public artifact map."
  [cargo]
  (let [codeblocks (get-in cargo [:stash :codeblocks])
        target-ir (get-in cargo [:stash :target-ir])
        host (:host target-ir)
        page-kind (or (:page-kind codeblocks) :program)
        construction-ir (get-in cargo [:stash :construction-ir])
        has-construction? (boolean construction-ir)
        local-artifacts (artifacts-for-cargo cargo true)
        imported-artifacts (for [imported (vals (get-in cargo [:stash :imported-cargos] {}))]
                             (artifacts-for-cargo imported false))
        classes (str/join "\n"
                          (concat [(:classes local-artifacts)
                                   (:wrapper local-artifacts)]
                                  (map :classes imported-artifacts)
                                  (map :wrapper imported-artifacts)))]
    {:classes classes
     :factory ""
     :main (or (get-in target-ir [:main :haxe]) "")
     :init (or (get-in target-ir [:init :haxe]) "")
     :step (or (get-in target-ir [:step :haxe]) "")
     :preamble (case host
                 "openfl" (str haxe-std/openfl-imports "\n\n"
                               haxe-std/openfl-graphics-wrapper "\n\n"
                               haxe-std/wchnt-input-class "\n\n"
                               haxe-std/wchnt-console-class "\n\n"
                               haxe-std/wchnt-maths-class)
                 "cli" (str haxe-std/wchnt-console-class "\n\n"
                            haxe-std/wchnt-maths-class)
                 "terminal" (str haxe-std/wchnt-console-class "\n\n"
                                 haxe-std/wchnt-maths-class)
                 "")
     :main-class (if has-construction? (emit-main-class target-ir) "")
     :has-construction? has-construction?
     :page-kind page-kind
     :host host
     :codeblocks codeblocks
     :warnings []}))
