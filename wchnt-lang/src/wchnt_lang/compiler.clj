(ns wchnt-lang.compiler
  (:require [wchnt-lang.parser :as parser]
            [wchnt-lang.haxegen :as haxe-gen]
            [wchnt-lang.schema :as schema]
            [wchnt-lang.mainfile :as mainfile]
            [wchnt-lang.pipeline :as p]
            [wchnt-lang.pipeline :as P]
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
          (p/log-all "STARTING COMPILATION")
          (p/processor mainfile/parse-mainfile) ;; parse the mainfile into code blocks
          (p/log-all "AFTER PARSE-MAINFILE")

          ;; Validate that it's a mainfile-parse-result
          (p/validator schema/valid-mainfile-parse-result? "Is a valid mainfile parse result")               

          (p/stash :codeblocks) ;; if it was OK, stash result as codeblocks
          (p/log-all "AFTER STASH CODEBLOCKS")

          (p/processor #(:schema %) "Get Schema") ;; get the schema 
          (p/log "SCHEMA EXTRACTED")

          (p/stash :schema-wchnt)    ;; stash it under :schema-wchnt name

          (p/processor parser/schema-wchnt->schema-ast "Parse schema to ast") ;; insta/parse it to the ast for the schema
          (p/log "SCHEMA AST")

          (p/stash :schema-ast) ;; stash the ast of the schema

          ;; create grammar for parsing the construction
          (p/processor parser/schema-to-construction-grammar "Schema ast -> construction grammar") 
          (p/log "CONSTRUCTION GRAMMAR")
          (p/stash :construction-grammar) ;; stash that as :construction-grammar
          
          (p/retrieve :schema-ast) ;; pull out the schema-ast again

          (p/processor haxe-gen/schema-ast->haxe "schema-ast -> haxe") ;; convert it to haxe
          (p/log "SCHEMA HAXE")
          (p/stash :schema-haxe)                 ;; stash the schema haxe

          (p/retrieve :codeblocks) ;; retrieve the codeblocks
         
          (p/processor #(:construction %) "Extract construction from codeblocks") ;; get the construction
          (p/log "CONSTRUCTION EXTRACTED")
          (p/when-do               ;; if condition do the sub-pipeline
           #(not= % "") ;; check if the construction wchnt is not empty      
           (p/stash :construction-wchnt) ;; stash it as :construction-wchnt
           (p/log-all "BEFORE PARSE-CONSTRUCTION-PURE")
           (p/trace "ABOUT-TO-CALL-CARGO-PROCESSOR")
           ;; Parse construction using the schema-generated grammar
                      (p/cargo-processor #(do
                                (println "DEBUG: About to call parse-construction-pure")
                                (println "DEBUG: schema-ast:" (-> % :stash :schema-ast))
                                (println "DEBUG: construction:" (-> % :stash :construction-wchnt))
                                (let [result (parser/parse-construction-pure 
                                             {:schema-ast (-> % :stash :schema-ast) 
                                              :construction (-> % :stash :construction-wchnt)})]
                                  (if (P/failed? result)
                                    result
                                    (P/success-cargo (:value result))))) "parse-construction-pure")
           (p/log-all "AFTER PARSE-CONSTRUCTION-PURE")
           (p/log-all "AFTER PARSE-CONSTRUCTION-CARGO-PROCESSOR")
           (p/stash :construction-ast)
           (p/log-all "AFTER STASH CONSTRUCTION-AST")
           (p/log "CURRENT VALUE BEFORE FACTORY GENERATION")
           (p/log-all "ABOUT TO START FACTORY GENERATION")
           
           ;; Generate construction factory
           (p/log-all "BEFORE GENERATE-CONSTRUCTION-FACTORY")
           (p/cargo-processor #(do
                                (println "DEBUG: About to call generate-construction-factory-pure-cargo")
                                (println "DEBUG: construction-ast:" %)
                                (println "DEBUG: class-info:" (parser/extract-class-info (-> % :stash :schema-ast)))
                                (haxe-gen/generate-construction-factory-pure-cargo 
                                 % 
                                 (parser/extract-class-info (-> % :stash :schema-ast))
                                 {})) "generate-construction-factory-pure-cargo")
           (p/log-all "AFTER GENERATE-CONSTRUCTION-FACTORY")
           (p/stash :construction-haxe)
           )
          )]
    
    
    ;; If the pipeline failed, return the failed cargo
    (if (p/failed? final-cargo)
      final-cargo
      ;; Otherwise, construct the success result
      (let [factory (or (-> final-cargo :stash :construction-haxe) "")
            main (if (str/blank? factory)
                   ""
                   (str "public static function main() {\n    return factory();\n}"))
            full-program {:classes (-> final-cargo :stash :schema-haxe) 
                         :factory factory
                         :main main
                         :codeblocks (-> final-cargo :stash :codeblocks)
                         :warnings []}]
        (p/success-cargo full-program)))))

(defn compile-file
  "Compile a WCHNT file by path, slurping the file and compiling its contents.
   Always returns a FullProgramStructure or a map with error details."
  [file-path]
  (compile (slurp file-path)))


 
