#!/usr/bin/env bash
# Copies the canonical graph renderer into the places that ship it.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source_file="$root/loom/graph-render/graph-render.js"
targets=(
  "$root/loom/vscode-loom/media/graph-render.js"
  "$root/eval4j-report/src/main/resources/io/github/llm4j/evalreport/render/graph-render.js"
)
for target in "${targets[@]}"; do
  mkdir -p "$(dirname "$target")"
  cp "$source_file" "$target"
  echo "synced ${target#$root/}"
done
