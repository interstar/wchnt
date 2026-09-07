#!/bin/bash

# Rebuild everything: the JVM compiler (JARs) and the live browser bundles.
# Safe to run from anywhere; it cds to the directory containing this script.

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "=========================================="
echo "Rebuilding WCHNT: JVM compiler + live page"
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

echo "=========================================="
echo "Rebuild complete."
echo "=========================================="
