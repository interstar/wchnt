(ns wchnt-lang.compiler
  (:require [wchnt-lang.parser :as parser]
            [wchnt-lang.schema :as schema]
            [wchnt-lang.mainfile :as mainfile]
            [wchnt-lang.pipeline :as p]
            [wchnt-lang.pipeline :as P]
            [wchnt-lang.ast-to-ir :as ast-to-ir]
            [wchnt-lang.reaction :as reaction]
            [wchnt-lang.ir-to-haxe :as ir-to-haxe]
            [wchnt-lang.target :as target]
            [wchnt-lang.haxe-helpers :as haxe-helpers]
            [instaparse.core :as insta]
            [clojure.string :as str]))




;; =============================================================================
;; Main Compilation Function
;; =============================================================================

(defn- schema-stages
  []
  [(p/processor mainfile/parse-mainfile) ;; parse the mainfile into code blocks
   (p/validator schema/valid-mainfile-parse-result? "Is a valid mainfile parse result")
   (p/stash :codeblocks)
   (p/processor #(:schema %) "Get Schema")
   (p/stash :schema-wchnt)
   (p/processor parser/schema-wchnt->schema-ast "Parse schema to ast")
   (p/stash :schema-ast)
   (p/retrieve :schema-ast)
   (p/processor ast-to-ir/schema-ast-to-ir "schema-ast -> IR")
   (p/stash :schema-ir)
   (p/retrieve :schema-ast)
   (p/processor ast-to-ir/build-context-relationships "Build context relationships")
   (p/stash :context-relationships)
   (p/retrieve :codeblocks)
   (p/processor #(:target %) "Extract target")
   (p/stash :target-wchnt)
   (p/processor target/parse-target "parse Target % blocks")
   (p/stash :target-ir)])

(defn- reaction-stages
  []
  [(p/retrieve :codeblocks)
   (p/processor #(:methods %) "Extract methods from codeblocks")
   (p/when-do
    #(not= % "")
    (p/stash :methods-wchnt)
    (p/processor parser/parse-reaction-unified "parse-reaction-unified")
    (p/stash :reaction-ast)
    (p/validator schema/valid-reaction-ast? "Reaction AST matches schema")
    (p/retrieve :reaction-ast)
    (p/cargo-processor
     (fn [cargo]
       (let [reaction-ast (:value cargo)
             schema-ir (get-in cargo [:stash :schema-ir])
             target-ir (get-in cargo [:stash :target-ir])]
         (p/success-cargo (reaction/reaction-ast-to-ir reaction-ast schema-ir target-ir))))
     "reaction-ast -> IR")
    (p/stash :methods-ir)
    (p/validator schema/valid-methods-ir? "Methods IR matches schema"))
   (p/cargo-processor
    (fn [cargo]
      (reaction/assert-update-wiring!
       (or (get-in cargo [:stash :methods-ir]) [])
       (get-in cargo [:stash :schema-ir]))
      cargo)
    "subscribers and observables define update")])

(defn- schema-haxe-stages
  []
  [(p/retrieve :schema-ir)
   (p/cargo-processor
    (fn [cargo]
      (ir-to-haxe/schema-ir-to-haxe (get-in cargo [:stash :schema-ir])
                                    (or (get-in cargo [:stash :methods-ir]) [])))
    "schema-ir -> haxe")
   (p/stash :schema-haxe)])

(defn- construction-stages
  []
  [(p/retrieve :codeblocks)
   (p/processor #(:construction %) "Extract construction from codeblocks")
   (p/when-do
    #(not= % "")
    (p/stash :construction-wchnt)
    (p/processor
     (fn [construction-text]
       (parser/parse-construction-unified construction-text))
     "parse-construction-unified")
    (p/stash :construction-ast)
    (p/validator schema/valid-construction-ast? "Construction AST matches schema")
    (p/retrieve :construction-ast)
    (p/cargo-processor
     (fn [cargo]
       (let [construction-ast (:value cargo)
             schema-ir (get-in cargo [:stash :schema-ir])]
         (p/success-cargo (ast-to-ir/construction-ast-to-ir construction-ast schema-ir))))
     "construction-ast -> IR")
    (p/stash :construction-ir)
    (p/log-all "After stashing construction-ir")
    (p/retrieve :construction-ir)
    (p/validator schema/valid-construction-ir? "Construction IR matches schema")
    (p/retrieve :construction-ir)
    (p/cargo-processor
     (fn [cargo]
       (let [construction-ir (:value cargo)
             schema-ir (get-in cargo [:stash :schema-ir])]
         (ir-to-haxe/generate-construction-factory construction-ir schema-ir)))
     "construction-ir -> haxe")
    (p/stash :construction-haxe))])

(defn- run-compilation-pipeline
  [wchnt-markdown]
  (apply p/run wchnt-markdown (concat (schema-stages)
                                      (reaction-stages)
                                      (schema-haxe-stages)
                                      (construction-stages))))

(defn- class-body
  [& parts]
  (str/join "\n" (remove str/blank? parts)))

(defn- emit-terminal-main
  [factory helpers main]
  (when (str/blank? (or main ""))
    (throw (ex-info "Construction programs must define %main in Target" {})))
  (str "class Main {\n" (class-body factory helpers main) "\n}"))

(defn- emit-openfl-main
  [factory helpers init step]
  (when (or (str/blank? (or init "")) (str/blank? (or step "")))
    (throw (ex-info "%openfl requires %init and %step" {})))
  (str "class Main extends Sprite {\n"
       (class-body factory helpers init step haxe-helpers/openfl-lifecycle)
       "\n}"))

(defn- emit-main-class
  [factory target-ir]
  (if (str/blank? factory)
    ""
    (let [host (or (:host target-ir) "terminal")
          helpers (str/join "\n" (map :haxe (vals (:bindings (or target-ir {:bindings {}})))))
          main (get-in target-ir [:main :haxe])
          init (get-in target-ir [:init :haxe])
          step (get-in target-ir [:step :haxe])]
      (case host
        "terminal" (emit-terminal-main factory helpers main)
        "openfl" (emit-openfl-main factory helpers init step)
        (throw (ex-info (str "Unknown Target host '" host "'") {:host host}))))))

(defn- cargo->full-program
  [cargo]
  (let [factory (or (-> cargo :stash :construction-haxe) "")
        target-ir (get-in cargo [:stash :target-ir])
        host (:host target-ir)
        has-construction? (not (str/blank? factory))
        main (or (get-in target-ir [:main :haxe]) "")
        main-class (if has-construction?
                     (emit-main-class factory target-ir)
                     "")]
    {:classes (-> cargo :stash :schema-haxe)
     :factory factory
     :main main
     :init (or (get-in target-ir [:init :haxe]) "")
     :step (or (get-in target-ir [:step :haxe]) "")
     :preamble (if (= host "openfl") haxe-helpers/openfl-imports "")
     :main-class main-class
     :has-construction? has-construction?
     :host host
     :codeblocks (-> cargo :stash :codeblocks)
     :warnings []}))

(defn compile [wchnt-markdown]
  "High-level compilation function that runs both schema and construction pipelines. 
   Should now return a FullProgramStructure"
  (let [final-cargo (run-compilation-pipeline wchnt-markdown)]
    (if (p/failed? final-cargo)
      final-cargo
      (try
        (assoc final-cargo :value (cargo->full-program final-cargo))
        (catch Exception e
          (-> final-cargo
              (assoc :success false :value nil)
              (update :errors conj (.getMessage e))))))))



 
