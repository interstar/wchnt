#!/bin/bash

# WCHNT Language Examples Runner
# Runs all .wcn files in the examples directory

set -e  # Exit on any error

echo "=========================================="
echo "WCHNT Language Examples Runner"
echo "=========================================="
echo

# Get the directory where this script is located
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
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

echo "Found $TOTAL_FILES example files:"
echo "$WCN_FILES" | sed 's|.*/||' | nl
echo

# Run each example
for file in $WCN_FILES; do
    CURRENT=$((CURRENT + 1))
    filename=$(basename "$file")
    
    echo "=========================================="
    echo "[$CURRENT/$TOTAL_FILES] Running: $filename"
    echo "=========================================="
    echo
    
    # Run the example
    if lein run "$file"; then
        echo
        echo "✓ SUCCESS: $filename"
    else
        echo
        echo "✗ FAILED: $filename"
        echo "Continuing with next example..."
    fi
    
    echo
done

echo "=========================================="
echo "All examples completed!"
echo "==========================================" 