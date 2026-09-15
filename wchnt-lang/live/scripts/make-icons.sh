#!/bin/bash
# Rasterise website/assets/witch.svg into live/public/icons/.
# Requires ImageMagick `convert`.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SRC="$ROOT/../website/assets/witch.svg"
DEST="$ROOT/live/public/icons"
if [[ ! -f "$SRC" ]]; then
  echo "missing $SRC" >&2
  exit 1
fi
mkdir -p "$DEST"
# SVG fill is near-black; invert onto #111 so the mark reads on the dark shell.
convert "$SRC" -resize 512x512 -background white -alpha remove -negate \
  -fill '#111111' -opaque black -colorspace sRGB png32:"$DEST/_base.png"
convert "$DEST/_base.png" -resize 192x192 png32:"$DEST/icon-192.png"
convert "$DEST/_base.png" png32:"$DEST/icon-512.png"
convert "$DEST/_base.png" -resize 154x154 -background '#111111' -gravity center \
  -extent 192x192 png32:"$DEST/icon-192-maskable.png"
convert "$DEST/_base.png" -resize 410x410 -background '#111111' -gravity center \
  -extent 512x512 png32:"$DEST/icon-512-maskable.png"
rm -f "$DEST/_base.png"
echo "Wrote icons in $DEST"
