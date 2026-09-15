#!/bin/bash

# WCHNT Full Pipeline Examples Runner
# Runs all .wcn files through the complete WCHNT → Haxe → JavaScript → Execution pipeline

set -e  # Exit on any error

echo "=========================================="
echo "WCHNT Full Pipeline Examples Runner"
echo "=========================================="
echo

# Get the directory where this script is located
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"
EXAMPLES_DIR="$SCRIPT_DIR/examples"

# Check if examples directory exists
if [ ! -d "$EXAMPLES_DIR" ]; then
    echo "Error: Examples directory not found at $EXAMPLES_DIR"
    exit 1
fi

# Find all .wcn files
WCN_FILES=$(find "$EXAMPLES_DIR" -name "*.wcn" | sort)

if [ -z "$WCN_FILES" ]; then
    echo "No .wcn files found in $EXAMPLES_DIR"
    exit 1
fi

# Count total files
TOTAL_FILES=$(echo "$WCN_FILES" | wc -l)
CURRENT=0
FAILED_COUNT=0

echo "Found $TOTAL_FILES example files:"
echo "$WCN_FILES" | sed 's|.*/||' | nl
echo

# Run each example through the full pipeline
for file in $WCN_FILES; do
    CURRENT=$((CURRENT + 1))
    filename=$(basename "$file")

    if grep -qE '^%openfl[[:space:]]*$|^%canvas[[:space:]]*$|^%cli[[:space:]]*$|^%cli-live[[:space:]]*$' "$file"; then
        echo "=========================================="
        echo "[$CURRENT/$TOTAL_FILES] Skipping interactive/windowed host: $filename"
        echo "OpenFL: ./go.sh $file   Canvas: live interpreter (doc/live.md)   CLI: ./go.sh $file (interactive)"
        echo "=========================================="
        echo
        continue
    fi
    
    echo "=========================================="
    echo "[$CURRENT/$TOTAL_FILES] Running full pipeline: $filename"
    echo "=========================================="
    echo
    
    # Run the full pipeline
    if ./go.sh "$file"; then
        echo
        echo "✓ SUCCESS: $filename (WCHNT → Haxe → JavaScript → Execution)"
    else
        FAILED_COUNT=$((FAILED_COUNT + 1))
        echo
        echo "✗ FAILED: $filename"
        echo "Continuing with next example..."
    fi
    
    echo
done

# go.sh removes Main.hx and the per-example .js on success. Tidy the shared Main.hx
# here only when nothing failed — failed runs keep their artifacts for debugging.
if [ "$FAILED_COUNT" -eq 0 ]; then
    rm -f Main.hx
else
    echo
    echo "⚠ $FAILED_COUNT example(s) failed — Main.hx and any .js files were left for debugging."
fi

echo "=========================================="
echo "All full pipeline examples completed!"
echo "=========================================="
