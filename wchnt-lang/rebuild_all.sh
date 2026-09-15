#!/bin/bash

# Rebuild everything: JVM compiler (JARs), live browser bundles, and the website.
# Usage: ./rebuild_all.sh [--advanced]
#   --advanced   build live/public/js/main.js with :optimizations :advanced
#                (smaller and faster; uses externs.js) instead of :simple.
# Safe to run from anywhere; it cds to the directory containing this script.

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WEBSITE_DIR="$(cd "$SCRIPT_DIR/../website" && pwd)"
cd "$SCRIPT_DIR"

LIVE_ALIAS="live"
if [ "${1:-}" = "--advanced" ] || [ "${1:-}" = "advanced" ]; then
  LIVE_ALIAS="live-advanced"
fi

echo "=========================================="
echo "Rebuilding WCHNT: JVM + live + website"
echo "  live build: $LIVE_ALIAS"
echo "=========================================="
echo

echo "==> JVM compiler (clean, test, jar, uberjar)"
./build.sh
echo

echo "==> Live page bundle (live/public/js/main.js)"
if [ "$LIVE_ALIAS" = "live-advanced" ]; then
  # cljsbuild can skip a build whose output file already exists; force a fresh
  # advanced build so we don't ship a stale :simple bundle.
  rm -f live/public/js/main.js
fi
lein "$LIVE_ALIAS"
echo

echo "==> Live test bundle + examples (live/public/js/tests.js)"
lein live-test
echo

echo "==> Website (_site/ including play/ and seed pages)"
"$WEBSITE_DIR/build.sh"
echo

echo "=========================================="
echo "Rebuild complete."
echo "=========================================="
