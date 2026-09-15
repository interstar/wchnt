#!/bin/bash
# Copy live-examples listed in seed-map.txt into a wiki seed directory
# and write seed/index.txt (the live page fetches that list — no recompile).
# Usage: seed-from-live.sh DEST_DIR
# Safe to run from anywhere.

set -euo pipefail

if [ "${1:-}" = "" ]; then
  echo "usage: seed-from-live.sh DEST_DIR" >&2
  exit 1
fi

DEST="$(mkdir -p "$1" && cd "$1" && pwd)"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MAP="$HERE/seed-map.txt"

if [ ! -f "$MAP" ]; then
  echo "missing $MAP" >&2
  exit 1
fi

# Drop previous seed pages (keep the dest dir). Do not delete index until
# the new copies succeed.
find "$DEST" -maxdepth 1 -type f -name '*.wcn' -delete

names=()
while read -r src destname; do
  case "$src" in
    ""|\#*) continue ;;
  esac
  from="$HERE/${src}.wcn"
  if [ ! -f "$from" ]; then
    echo "missing live-example for seed page '$destname': $from" >&2
    exit 1
  fi
  cp "$from" "$DEST/${destname}.wcn"
  names+=("$destname")
done < <(awk '
  NF && $1 !~ /^#/ {
    if (NF < 2) {
      print "seed-map.txt: missing wiki page name for " $1 > "/dev/stderr"
      exit 1
    }
    print $1, $2
  }' "$MAP")

{
  printf '%s\n' "${names[@]}"
} > "$DEST/index.txt"
