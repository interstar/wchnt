(ns wchnt-lang.compiler
  "Markdown → IR (shared) then optional Haxe emission.
   compile-to-ir is the live/interpreter entry. compile adds the Haxe backend."
  (:require [wchnt-lang.parser :as parser]
            [wchnt-lang.schema :as schema]
            [wchnt-lang.mainfile :as mainfile]
            [wchnt-lang.pages :as pages]
            [wchnt-lang.pipeline :as p]
            [wchnt-lang.ast-to-ir :as ast-to-ir]
            [wchnt-lang.reaction :as reaction]
            [wchnt-lang.ir-to-haxe :as ir-to-haxe]
            [wchnt-lang.target :as target]
            [wchnt-lang.haxe-helpers :as haxe-helpers]
            [clojure.string :as str]))

(defn- schema-stages
  []
  [(p/processor identity)
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

(defn- parse-methods-text
  [text section schema-ir target-ir]
  (if (str/blank? text)
    {:methods [] :schema-ir schema-ir}
    (let [parsed (parser/parse-reaction-unified text)]
      (when (p/failed? parsed)
        (throw (ex-info (or (first (:errors parsed)) "Methods parse failed")
                        {:section section})))
      (let [reaction-ast (:value parsed)
            schema-with-ext (reaction/merge-external-types
                             schema-ir
                             (reaction/collect-external-types-from-reaction-ast reaction-ast))
            methods (reaction/reaction-ast-to-ir reaction-ast schema-with-ext target-ir
                                                 {:skip-checks? true})]
        (reaction/assert-methods-section-placement! methods schema-with-ext section)
        {:methods methods :schema-ir schema-with-ext}))))

(defn- reaction-stages
  []
  [(p/retrieve :codeblocks)
   (p/cargo-processor
    (fn [cargo]
      (let [codeblocks (:value cargo)
            target-ir (get-in cargo [:stash :target-ir])
            after-methods (parse-methods-text (:methods codeblocks)
                                              :methods
                                              (get-in cargo [:stash :schema-ir])
                                              target-ir)
            after-target-methods (parse-methods-text (:target-methods codeblocks)
                                                     :target-methods
                                                     (:schema-ir after-methods)
                                                     target-ir)
            schema-ir (:schema-ir after-target-methods)
            combined (into (:methods after-methods) (:methods after-target-methods))]
        (reaction/assert-methods-complete! combined schema-ir)
        (-> (p/success-cargo combined)
            (assoc-in [:stash :schema-ir] schema-ir))))
    "methods + target-methods -> IR")
   (p/stash :methods-ir)
   (p/validator schema/valid-methods-ir? "Methods IR matches schema")])

(defn- construction-ir-stages
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
    (p/retrieve :construction-ir)
    (p/validator schema/valid-construction-ir? "Construction IR matches schema"))])

(defn- schema-haxe-stages
  []
  [(p/retrieve :schema-ir)
   (p/cargo-processor
    (fn [cargo]
      (ir-to-haxe/schema-ir-to-haxe (get-in cargo [:stash :schema-ir])
                                    (or (get-in cargo [:stash :methods-ir]) [])))
    "schema-ir -> haxe")
   (p/stash :schema-haxe)])

(defn- construction-haxe-stages
  []
  [(p/retrieve :codeblocks)
   (p/processor #(:construction %) "Extract construction from codeblocks")
   (p/when-do
    #(not= % "")
    (p/retrieve :construction-ir)
    (p/cargo-processor
     (fn [cargo]
       (let [construction-ir (:value cargo)
             schema-ir (get-in cargo [:stash :schema-ir])]
         (ir-to-haxe/generate-construction-factory construction-ir schema-ir)))
     "construction-ir -> haxe")
    (p/stash :construction-haxe))])

(defn- ir-stages-from-codeblocks
  []
  (concat (schema-stages) (reaction-stages) (construction-ir-stages)))

(defn- ir-stages
  []
  (concat [(p/processor mainfile/parse-mainfile)
           (p/validator schema/valid-mainfile-parse-result? "Is a valid mainfile parse result")]
          (ir-stages-from-codeblocks)))

(defn- documentation-cargo
  [codeblocks]
  (-> (p/success-cargo codeblocks)
      (assoc-in [:stash :codeblocks] codeblocks)
      (assoc :value {:page-kind :documentation :codeblocks codeblocks})))

(defn- compile-codeblocks-to-ir
  [codeblocks]
  (apply p/run codeblocks (ir-stages-from-codeblocks)))

(defn- import-codeblocks
  "Imported pages contribute schema and methods only."
  [codeblocks]
  (assoc codeblocks
         :construction ""
         :target ""
         :page-kind :library))

(defn- compile-import-cargos
  [import-order resolve-page]
  (when (seq import-order)
    (reduce
     (fn [acc page-name]
       (let [markdown (resolve-page page-name)
             parsed (mainfile/parse-mainfile markdown)]
         (when (p/failed? parsed)
           (throw (ex-info (str "Import page '" page-name "' parse error: "
                                (first (:errors parsed)))
                           {:page page-name})))
         (let [cargo (compile-codeblocks-to-ir (import-codeblocks (:value parsed)))]
           (if (p/failed? cargo)
             (throw (ex-info (str "Import page '" page-name "' compile error: "
                                  (first (:errors cargo)))
                             {:page page-name}))
             (if acc
               (pages/merge-cargo-stashes acc cargo)
               cargo)))))
     nil
     import-order)))

(defn- merge-imports-into-cargo
  [page-cargo import-order resolve-page]
  (if (empty? import-order)
    page-cargo
    (let [import-cargo (compile-import-cargos import-order resolve-page)]
      (pages/merge-cargo-stashes import-cargo page-cargo))))

(defn- finalize-ir-cargo
  [cargo codeblocks]
  (-> cargo
      (assoc :value {:page-kind (:page-kind codeblocks) :codeblocks codeblocks})
      (assoc-in [:stash :codeblocks] codeblocks)))

(defn compile-to-ir
  "Parse a .wcn markdown file to schema, methods, construction, and target IR.
   Does not emit Haxe. Used by the interpreter / live page.

   Optional :resolve-page (fn [name] markdown-or-nil) merges ## Import siblings."
  ([wchnt-markdown]
   (compile-to-ir wchnt-markdown {}))
  ([wchnt-markdown {:keys [resolve-page]}]
   (try
     (let [parsed (mainfile/parse-mainfile wchnt-markdown)]
       (if (p/failed? parsed)
         parsed
         (let [codeblocks (:value parsed)]
           (if (= :documentation (:page-kind codeblocks))
             (documentation-cargo codeblocks)
             (let [import-order (when resolve-page
                                  (pages/resolve-import-order
                                   (mainfile/parse-import-names (:import codeblocks))
                                   resolve-page))
                   page-cargo (compile-codeblocks-to-ir codeblocks)]
               (if (p/failed? page-cargo)
                 page-cargo
                 (finalize-ir-cargo
                  (merge-imports-into-cargo page-cargo import-order resolve-page)
                  codeblocks)))))))
     (catch #?(:clj Exception :cljs :default) e
       (p/fail-cargo (or (ex-message e) (str e)))))))

(defn- haxe-stages
  []
  (concat (schema-haxe-stages) (construction-haxe-stages)))

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
    (let [host (:host target-ir)]
      (when-not host
        (throw (ex-info "Target must name a host (%terminal, %openfl, or %canvas)"
                        {:target-ir target-ir})))
      (let [helpers (str/join "\n" (map :haxe (vals (:bindings (or target-ir {:bindings {}})))))
            main (get-in target-ir [:main :haxe])
            init (get-in target-ir [:init :haxe])
            step (get-in target-ir [:step :haxe])]
        (case host
          "terminal" (emit-terminal-main factory helpers main)
          "openfl" (emit-openfl-main factory helpers init step)
          "canvas" (throw (ex-info "%canvas is for the live interpreter, not the Haxe backend"
                                   {:host host}))
          (throw (ex-info (str "Unknown Target host '" host "'") {:host host})))))))

(defn- cargo->full-program
  [cargo]
  (let [codeblocks (get-in cargo [:stash :codeblocks])
        factory (or (-> cargo :stash :construction-haxe) "")
        target-ir (get-in cargo [:stash :target-ir])
        host (:host target-ir)
        page-kind (or (:page-kind codeblocks) :program)
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
     :preamble (if (= host "openfl")
                 (str haxe-helpers/openfl-imports "\n\n"
                      haxe-helpers/openfl-graphics-wrapper)
                 "")
     :main-class main-class
     :has-construction? has-construction?
     :page-kind page-kind
     :host host
     :codeblocks codeblocks
     :warnings []}))

(defn compile
  "Haxe backend: IR pipeline plus class/factory/Main emission."
  ([wchnt-markdown]
   (compile wchnt-markdown {}))
  ([wchnt-markdown opts]
   (let [ir-cargo (compile-to-ir wchnt-markdown opts)]
     (cond
       (p/failed? ir-cargo)
       ir-cargo

       (= :documentation (:page-kind (:value ir-cargo)))
       (assoc ir-cargo
              :value {:page-kind :documentation
                      :classes ""
                      :factory ""
                      :main ""
                      :init ""
                      :step ""
                      :preamble ""
                      :main-class ""
                      :has-construction? false
                      :host nil
                      :codeblocks (:codeblocks (:value ir-cargo))
                      :warnings []})

       :else
       (try
         (let [haxe-cargo (apply p/continue ir-cargo (haxe-stages))]
           (if (p/failed? haxe-cargo)
             haxe-cargo
             (assoc haxe-cargo :value (cargo->full-program haxe-cargo))))
         (catch #?(:clj Exception :cljs :default) e
           (-> ir-cargo
               (assoc :success false :value nil)
               (update :errors conj (or (ex-message e) (str e))))))))))
