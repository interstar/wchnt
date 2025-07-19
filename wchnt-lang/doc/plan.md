# WCHNT Language Development Plan

## Current Situation

You have successfully created a new unified grammar in `newparser.clj` that can handle both construction and reaction/imperative sections. This grammar is working well in tests and produces a different AST structure than the old schema-derived construction parser.

## Integration Plan: Replace Old Construction Parser with New Unified Parser

### Phase 1: Understand the AST Structure Differences

**Current Old Parser AST Structure:**
- Produces `{:type :MultiStepConstruction, :assignments [...], :final-construction ...}` 
- Uses schema-derived grammar with specific class construction rules
- Handles assignments and final construction separately

**New Unified Parser AST Structure:**
- Produces method definitions: `[:Code [:MethodDefinition ...]]`
- Can parse construction statements directly: `[:BlockStatements ...]`
- Uses generic grammar that works for both construction and reaction sections
- AST structure is more generic and expression-oriented



**Task 2.2: Create `construction-extractor.clj`**
- Function to extract construction statements from new parser AST
- Parse construction using `:start :BlockStatements` 
- Handle the dual grammar usage demonstrated in tests

### Phase 3: Update Compilation Pipeline

**Task 3.1: Modify `compiler.clj`**
- Replace `parser/parse-construction-pure` call with new unified parser
- Add AST transformation step after parsing
- Update pipeline to use new parser for construction section

**Task 3.2: Create `parse-construction-unified` function**
- Use `newparser/get-wchnt-parser` 
- Parse construction section with `:start :BlockStatements`
- Transform AST to compatible format for existing factory generation

### Phase 4: Update Factory Generation

**Task 4.1: Modify `haxegen.cljc`**
- Update `generate-construction-factory` to handle new AST structure
- Ensure compatibility with both old and new AST formats during transition
- Add validation for new AST structure

**Task 4.2: Create `generate-construction-factory-unified`**
- New factory generation function specifically for unified parser AST
- Handle the new expression-based AST structure
- Maintain same output format (Haxe factory function)

### Phase 5: Testing and Validation

**Task 5.1: Update existing tests**
- Modify `construction_test.clj` to use new parser
- Ensure all existing test cases still pass
- Add new tests for unified parser edge cases

**Task 5.2: Create integration tests**
- Test full compilation pipeline with new parser
- Verify generated Haxe code is identical
- Test error handling and edge cases

### Phase 6: Cleanup and Documentation

**Task 6.1: Remove old construction parsing code**
- Remove `parse-construction-pure` and related functions
- Remove schema-derived construction grammar generation
- Clean up unused code in `parser.cljc`

**Task 6.2: Update documentation**
- Document new unified parser approach
- Update examples to show new grammar usage
- Document AST transformation process

## Questions and Ambiguities

1. **AST Structure Compatibility**: Should we maintain backward compatibility with the old AST structure, or can we update the factory generation to work directly with the new AST?

2. **Error Handling**: How should we handle parsing errors from the new unified parser? Should we maintain the same error message format?

3. **Performance**: The new parser might be slower since it's more generic. Should we optimize it or is the flexibility worth the performance cost?

4. **Grammar Evolution**: Should we extend the unified grammar to handle more construction-specific features, or keep it generic for future reaction/imperative sections?

5. **Testing Strategy**: Should we run both parsers in parallel during transition to ensure identical output, or make a clean switch?

## Implementation Priority

1. **High Priority**: Create AST transformation layer (Phase 2)
2. **High Priority**: Update compilation pipeline (Phase 3) 
3. **Medium Priority**: Update factory generation (Phase 4)
4. **Medium Priority**: Testing and validation (Phase 5)
5. **Low Priority**: Cleanup and documentation (Phase 6)

## Success Criteria

- All existing tests pass with new parser
- Generated Haxe code is identical to current output
- Compilation pipeline is simpler and more maintainable
- New parser can handle both construction and future reaction sections
- Error messages are clear and helpful


