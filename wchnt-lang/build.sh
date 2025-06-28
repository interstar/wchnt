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

# Check if JAR was created
if [ -f "target/wchnt-lang.jar" ]; then
    echo "✅ Build successful! JAR file created: target/wchnt-lang.jar"
    echo "📦 JAR size: $(du -h target/wchnt-lang.jar | cut -f1)"
    echo ""
    echo "Usage in other projects:"
    echo "1. Add the JAR to your classpath"
    echo "2. Import the namespace: (require '[wchnt-lang.core :as wchnt])"
    echo "3. Use the API:"
    echo "   - (wchnt/get-parser) - Get the WCHNT parser"
    echo "   - (wchnt/compile-to-haxe input) - Compile to Haxe"
    echo "   - (wchnt/eyeball code) - Validate generated code"
else
    echo "❌ Build failed! JAR file not created."
    exit 1
fi 