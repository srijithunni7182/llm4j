#!/usr/bin/env bash
# Makes the Loom images the VS Code extension ships from the project logo (src/loom/ai-agent4j-loom/loom_logo.png):
#   loom-mark-128.png  the emblem alone, 128x128: toolbar, tab icon and extension icon
#   loom-logo-320.png  emblem and wordmark, 320x320: loading and empty states
# Usage: scripts/make-logo-assets.sh [output-dir]   (default: src/loom/vscode-loom/media). Needs ImageMagick (convert).
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source_logo="$root/src/loom/ai-agent4j-loom/loom_logo.png"
out="${1:-$root/src/loom/vscode-loom/media}"
command -v convert >/dev/null || { echo "ImageMagick (convert) is needed" >&2; exit 1; }
mkdir -p "$out"
# fixed settings and no metadata, so the same logo always gives the same bytes
flags=(-strip -define png:exclude-chunks=date,time,tEXt,zTXt,iTXt -define png:compression-level=9)
# the emblem sits in the upper part of the 1024 px logo; the wordmark is below it
convert "$source_logo" -crop 620x620+202+120 +repage -resize 128x128 "${flags[@]}" "$out/loom-mark-128.png"
convert "$source_logo" -resize 320x320 "${flags[@]}" "$out/loom-logo-320.png"
echo "wrote $out/loom-mark-128.png and $out/loom-logo-320.png"
