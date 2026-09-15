# WCHNT Language Development Rules

## Project Context
- WCHNT is a declarative object-oriented language that generates Haxe code
- It's an experiment in defining "assemblage programming" - an OO variant which focuses on defining the networks of interactive objects declaratively
- Assemblage programming reorders traditional OO. Instead of objects being self-contained black boxes, the assemblage is for tightly coupled clusters or network of objects which are transparent to each other.
- A WCHNT program has these phases
  - Schema (the classes and their relations,  written in a B-N Format or ADT type style)
  - Construction (the data literals paramaters that are the initial starting point of the code. Written as a single large data-structure (or "let"-like form, but syntax is close to EDN) which is as isomorphic to the schema as possible.
  - Behaviour - (Reactions and imperative) for methods.
  - Target: Immutable, reactive programming patterns

## Clojure Style Preferences
- keep functions short. Ideally if a function goes over 30 lines in length, refactor it into smaller pieces
- prefer immutable solutions over creating local mutable atoms. If an atom is necessary or the best solution, report this to the programmer with an explanation as to why.
- the reason for this is that when you use atoms to store temporary state in a function, it tends to lead to longer functions as everything needs to be within the scope where the atom was declared. if we consciously choose pure functions we can find it easier to break long functions into smaller ones.
- Use kebab-case for function and variable names
- Prefer destructuring in function parameters
- Use threading macros (`->`, `->>`) for data transformation pipelines
- Prefer `let` over nested function calls for clarity
- Prefer `map`, `filter`, `reduce` over loops when possible

## Code Organization
- Keep functions small and focused
- Use meaningful names that reflect the domain (assemblage, schema, construction)
- Add docstrings to public functions explaining their role in the compilation pipeline
- Group related functions together in namespaces

## WCHNT-Specific Patterns
- We are writing the WCHNT compile-chain. We aim to fail fast and definitively on any error in user input. If the WCHNT programmer uses the wrong name, makes a syntax error etc. we simply quit and print as useful an error message as we can.
- We particularly DON'T try to give helpful default values or interpretations of nil etc.
- We use the cargo structure and the pipeline functions defined in pipeline.clj as our way to organize the compilation pipeline. This pipeline passes cargos through from one stage to another and down into sub-pipelines
- when there's a failure in the pipeline, the cargo is short-circuited through the rest of the pipeline, but preserves its state at the point of failure so we can inspect it to find out what went wrong.
- pipelines also allow us to stash intermediate processing results and keep a log. They are a bit like a combination of Error Monad and Writer Monad
- Schema definitions should be clear and readable
- Construction syntax should follow hiccup-like patterns
- Error messages should be helpful for schema validation
- Generated code should be clean and well-structured

## CRITICAL: NEVER Hardcode Class Names or Make Assumptions
- NEVER hardcode specific class names (like "Player", "Rect", "Team") in the source code
- NEVER guess class names based on argument counts, data structure shapes, or other heuristics
- NEVER use magic numbers or patterns to determine class types
- ALWAYS derive class names from the actual schema/IR data
- If you can't determine the class name from the schema, FAIL FAST with a clear error message
- The schema is the single source of truth for all class information
- Any code that tries to guess or hardcode class names is WRONG and must be rewritten

## Testing

- Write unit tests first for each new task or piece of functionality 
- Test edge cases for complex wchnt schemas and constructions.
- Ensure generated Haxe code is valid
- Run tests with correct namespace: `lein test wchnt-lang.construction-test` or `lein test :only wchnt-lang.construction-test/test-name`
- Test namespaces use kebab-case: `wchnt-lang.construction-test`, `wchnt-lang.parser-test`, etc.
- Debug files should be created in the test/wchnt_lang/  directory, not in the root directory 