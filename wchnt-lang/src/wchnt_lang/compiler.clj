(ns wchnt-lang.compiler
  (:require [wchnt-lang.parser :as parser]
            [wchnt-lang.haxegen :as haxe-gen]
            [wchnt-lang.schema :as schema]
            [wchnt-lang.mainfile :as mainfile]
            [wchnt-lang.pipeline :as p]
            [wchnt-lang.pipeline :as P]
            [wchnt-lang.ast-to-ir :as ast-to-ir]
            [wchnt-lang.ir-to-haxe :as ir-to-haxe]
            [instaparse.core :as insta]
            [clojure.string :as str]))




;; =============================================================================
;; Main Compilation Function
;; =============================================================================

(defn compile [wchnt-markdown]
  "High-level compilation function that runs both schema and construction pipelines. 
   Should now return a FullProgramStructure"
  (let [final-cargo 
        (p/run wchnt-markdown
          (p/processor mainfile/parse-mainfile) ;; parse the mainfile into code blocks

          ;; Validate that it's a mainfile-parse-result
          (p/validator schema/valid-mainfile-parse-result? "Is a valid mainfile parse result")               

          (p/stash :codeblocks) ;; if it was OK, stash result as codeblocks

          (p/processor #(:schema %) "Get Schema") ;; get the schema 
          (p/stash :schema-wchnt)    ;; stash it under :schema-wchnt name

          (p/processor parser/schema-wchnt->schema-ast "Parse schema to ast") ;; insta/parse it to the ast for the schema
          (p/stash :schema-ast) ;; stash the ast of the schema
          
          ;; NEW: Generate IR from schema AST
          (p/retrieve :schema-ast) ;; pull out the schema-ast again
          (p/processor ast-to-ir/schema-ast-to-ir "schema-ast -> IR") ;; convert schema AST to IR
          (p/stash :schema-ir) ;; stash the IR
          
          (p/retrieve :schema-ast) ;; pull out the schema-ast again for Haxe generation
          (p/processor haxe-gen/schema-ast->haxe "schema-ast -> haxe") ;; convert it to haxe
          (p/stash :schema-haxe)                 ;; stash the schema haxe

          ;; Calculate context relationships from schema-ast
          (p/retrieve :schema-ast)
          (p/processor haxe-gen/build-context-relationships "Build context relationships")
          (p/stash :context-relationships)

          (p/retrieve :codeblocks) ;; retrieve the codeblocks
         
          (p/processor #(:construction %) "Extract construction from codeblocks") ;; get the construction
          (p/when-do               ;; if condition do the sub-pipeline
           #(not= % "") ;; check if the construction wchnt is not empty      
           (p/stash :construction-wchnt) ;; stash it as :construction-wchnt
           ;; Parse construction using the new unified grammar
           (p/processor
             (fn [construction-text]
               (parser/parse-construction-unified construction-text))
             "parse-construction-unified")
           (p/stash :construction-ast)
           
           ;; Generate construction factory using the new unified factory generation
           (p/cargo-processor haxe-gen/generate-construction-factory-unified-cargo "generate-construction-factory-unified-cargo")
           (p/stash :construction-haxe)
           )
          )]
    
    
    ;; If the pipeline failed, return the failed cargo
    (if (p/failed? final-cargo)
      final-cargo
      ;; Otherwise, construct the success result
              (let [factory (or (-> final-cargo :stash :construction-haxe) "")
            ;; Extract factory function name from the factory code
            factory-fn-name (if (str/blank? factory)
                             "factory"
                             (let [match (re-find #"public static function (\w+)\(" factory)]
                               (or (second match) "factory")))
            main (if (str/blank? factory)
                   ""
                   (str "public static function main():Void {\n    var game = " factory-fn-name "();\n    trace(game.toConstruction());\n}"))
            ;; Generate a complete Haxe program with a main class
            main-class (if (str/blank? factory)
                        ""
                        (str "class Main {\n" factory "\n" main "\n}"))
            full-program {:classes (-> final-cargo :stash :schema-haxe) 
                         :factory factory
                         :main main
                         :main-class main-class
                         :codeblocks (-> final-cargo :stash :codeblocks)
                         :warnings []}]
        (p/success-cargo full-program)))))



 
