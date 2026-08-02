#!/usr/bin/env bash
# Re-render the PNG icon artwork from the vector master (design/icon-source.svg).
#
# The launcher icon itself is fully vector (res/drawable/ic_launcher_foreground.xml
# + monochrome layer + @color background), so Android needs no density PNGs.
# This script only regenerates the two derived PNGs:
#   design/icon-preview.png        1024x1024 rounded tile (vector-trace preview)
#   art/ic_launcher-playstore.png  512x512 flat square (Play Store listing)
#
# design/icon-source.png is the ORIGINAL artwork — never overwritten here.
#
# Requires a Chromium/Chrome binary; set CHROME to override.
set -euo pipefail
cd "$(dirname "$0")/.."

CHROME="${CHROME:-$(command -v chromium || command -v chromium-browser || command -v google-chrome)}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

cp design/icon-source.svg "$WORK/wombat.svg"

cat > "$WORK/source-tile.html" <<'HTML'
<meta charset="utf-8">
<style>
  body { margin: 0; }
  .tile { width: 1024px; height: 1024px; background: #16161D; border-radius: 224px; }
  .tile img { width: 100%; height: 100%; }
</style>
<div class="tile"><img src="wombat.svg"></div>
HTML

cat > "$WORK/playstore.html" <<'HTML'
<meta charset="utf-8">
<style>
  body { margin: 0; }
  .tile { width: 512px; height: 512px; background: #16161D; }
  .tile img { width: 100%; height: 100%; }
</style>
<div class="tile"><img src="wombat.svg"></div>
HTML

"$CHROME" --headless --no-sandbox --disable-gpu --default-background-color=00000000 \
  --screenshot="$PWD/design/icon-preview.png" --window-size=1024,1024 "file://$WORK/source-tile.html"
"$CHROME" --headless --no-sandbox --disable-gpu \
  --screenshot="$PWD/art/ic_launcher-playstore.png" --window-size=512,512 "file://$WORK/playstore.html"

echo "Rendered design/icon-preview.png and art/ic_launcher-playstore.png"
