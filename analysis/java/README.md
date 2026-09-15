# Java assemblage analysis

This is a reference-material generator for migrating Java projects toward WCHNT.
It does not translate Java behaviour. It scans a source tree and writes a Markdown
report containing:

- the discovered classes and interfaces;
- inheritance and implementation relationships;
- instance fields, including their Java declarations and source locations;
- methods and constructors with signatures and locations;
- a conservative WCHNT Schema sketch;
- review notes for things Java does not map directly to WCHNT.

## Usage

From this directory:

```sh
lein run -- /path/to/java/source -o assemblage.md
```

The output path defaults to `java-assemblage.md`. Use `--help` for options.

The analyzer uses JavaParser for syntax, so comments, formatting, and method bodies
are not interpreted as a substitute for semantic analysis. Fields declared `static`
are excluded from the suggested instance schema but remain visible in the class
inventory.
