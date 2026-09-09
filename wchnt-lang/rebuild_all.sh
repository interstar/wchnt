#!/bin/bash

# Rebuild everything: JVM compiler (JARs), live browser bundles, and the website.
# Safe to run from anywhere; it cds to the directory containing this script.

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WEBSITE_DIR="$(cd "$SCRIPT_DIR/../website" && pwd)"
cd "$SCRIPT_DIR"

echo "=========================================="
echo "Rebuilding WCHNT: JVM + live + website"
echo "=========================================="
echo

echo "==> JVM compiler (clean, test, jar, uberjar)"
./build.sh
echo

echo "==> Live page bundle (live/public/js/main.js)"
lein live
echo

echo "==> Live test bundle + examples (live/public/js/tests.js)"
lein live-test
echo

echo "==> Website (_site/ including play/ from live/public)"
python3 "$WEBSITE_DIR/build.py"
echo

echo "=========================================="
echo "Rebuild complete."
echo "=========================================="
