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

# The VS Code webview has a strict content-security policy, so it loads the renderer's rules from a file
# instead of an embedded style element. The file is generated from the same source.
css_target="$root/loom/vscode-loom/media/graph-render.css"
node -e "process.stdout.write(require(process.argv[1]).css + '\n')" "$source_file" > "$css_target"
echo "synced ${css_target#$root/}"
