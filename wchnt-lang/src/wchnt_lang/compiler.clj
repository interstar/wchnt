(ns wchnt-lang.compiler
  (:require [wchnt-lang.parser :as parser]
            [wchnt-lang.haxegen :as haxe-gen]
            [wchnt-lang.schema :as schema]
            [wchnt-lang.mainfile :as mainfile]
            [wchnt-lang.pipeline :as p]
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
          (p/show-all "STARTING COMPILATION")
          (p/processor mainfile/parse-mainfile) ;; parse the mainfile into code blocks
          (p/show-all "AFTER PARSE-MAINFILE")

          ;; Validate that it's a mainfile-parse-result
          (p/validator schema/valid-mainfile-parse-result? "Is a valid mainfile parse result")               

          (p/stash :codeblocks) ;; if it was OK, stash result as codeblocks
          (p/show-all "AFTER STASH CODEBLOCKS")

          (p/processor #(:schema %) "Get Schema") ;; get the schema 
          (p/show "SCHEMA EXTRACTED")

          (p/stash :schema-wchnt)    ;; stash it under :schema-wchnt name

          (p/processor parser/schema-wchnt->schema-ast "Parse schema to ast") ;; insta/parse it to the ast for the schema
          (p/show "SCHEMA AST")

          (p/stash :schema-ast) ;; stash the ast of the schema

          ;; create grammar for parsing the construction
          (p/processor parser/schema-to-construction-grammar "Schema ast -> construction grammar") 
          (p/show "CONSTRUCTION GRAMMAR")
          (p/stash :construction-grammar) ;; stash that as :construction-grammar
          
          (p/retrieve :schema-ast) ;; pull out the schema-ast again

          (p/processor haxe-gen/schema-ast->haxe "schema-ast -> haxe") ;; convert it to haxe
          (p/show "SCHEMA HAXE")
          (p/stash :schema-haxe)                 ;; stash the schema haxe

          (p/retrieve :codeblocks) ;; retrieve the codeblocks
         
          (p/processor #(:construction %) "Extract construction from codeblocks") ;; get the construction
          (p/show "CONSTRUCTION EXTRACTED")
          (p/when-do               ;; if condition do the sub-pipeline
           #(not= % "") ;; check if the construction wchnt is not empty      
           (p/stash :construction-wchnt) ;; stash it as :construction-wchnt
           (p/show-all "BEFORE PARSE-CONSTRUCTION-PURE")
           ;; Parse construction using the schema-generated grammar
           (p/cargo-processor #(parser/parse-construction-pure 
                              {:schema-ast (-> % :stash :schema-ast) :construction (-> % :stash :construction-wchnt)}) "parse-construction-pure")
           (p/show-all "AFTER PARSE-CONSTRUCTION-PURE")
           (p/stash :construction-ast)
           
           ;; Generate construction factory
           (p/show-all "BEFORE GENERATE-CONSTRUCTION-FACTORY")
           (p/processor #(haxe-gen/generate-construction-factory-pure 
                           % 
                           (parser/extract-class-info (-> % :stash :schema-ast))
                           {}))
           (p/show-all "AFTER GENERATE-CONSTRUCTION-FACTORY")
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


 
