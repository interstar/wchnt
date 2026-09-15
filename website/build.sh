#!/bin/bash
# Build website/_site/ from markdown + the live Play bundle + wiki seed pages.
# Usage: ./website/build.sh
# Safe to run from anywhere.

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WCHNT_DIR="$(cd "$SCRIPT_DIR/../wchnt-lang" && pwd)"

echo "==> Refresh live/public/seed from live-examples/seed-map.txt"
(cd "$WCHNT_DIR" && lein run -m wchnt-lang.prepare-live)

if [ ! -f "$WCHNT_DIR/live/public/js/main.js" ]; then
  echo "live/public/js/main.js is missing — run lein live in wchnt-lang/ first." >&2
  exit 1
fi

echo "==> Render website/_site/"
python3 "$SCRIPT_DIR/build.py"
