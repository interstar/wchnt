#!/bin/bash

# WCHNT Language Compiler Build Script
# This script builds the WCHNT compiler into a distributable JAR file

set -e  # Exit on any error

echo "Building WCHNT Language Compiler..."

# Clean previous builds
echo "Cleaning previous builds..."
lein clean

# Run tests to ensure everything works
echo "Running tests..."
lein test

# Build the JAR file
echo "Building JAR file..."
lein jar

# Build the uberjar (standalone with dependencies)
echo "Building uberjar (standalone)..."
lein uberjar

# Check if JARs were created
if [ -f "target/wchnt-lang.jar" ]; then
    echo "✅ Build successful! JAR file created: target/wchnt-lang.jar"
    echo "📦 JAR size: $(du -h target/wchnt-lang.jar | cut -f1)"
fi

if [ -f "target/wchnt-lang-standalone.jar" ]; then
    echo "✅ Uberjar successful! Standalone JAR created: target/wchnt-lang-standalone.jar"
    echo "📦 Uberjar size: $(du -h target/wchnt-lang-standalone.jar | cut -f1)"
    echo ""
    echo "Usage in other projects:"
    echo "1. Add the standalone JAR to your classpath"
    echo "2. Use the Java API: wchnt_lang.WchntAPI"
    echo "3. Available methods:"
    echo "   - WchntAPI.compileToHaxe(String input) - Compile to Haxe"
    echo "   - WchntAPI.eyeball(String code) - Validate generated code"
    echo "   - WchntAPI.getParser() - Get the WCHNT parser"
else
    echo "❌ Uberjar build failed! Standalone JAR file not created."
    exit 1
fi 