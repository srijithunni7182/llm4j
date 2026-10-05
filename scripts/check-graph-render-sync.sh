#!/usr/bin/env bash
# Fails when a copy of the graph renderer differs from the canonical file (run scripts/sync-graph-render.sh to fix).
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source_file="$root/loom/graph-render/graph-render.js"
status=0
for target in \
  "$root/loom/vscode-loom/media/graph-render.js" \
  "$root/eval4j-report/src/main/resources/io/github/llm4j/evalreport/render/graph-render.js"; do
  if ! cmp -s "$source_file" "$target"; then
    echo "OUT OF SYNC: ${target#$root/} differs from loom/graph-render/graph-render.js" >&2
    status=1
  fi
done
[ "$status" -eq 0 ] && echo "graph-render copies are in sync"
exit "$status"
