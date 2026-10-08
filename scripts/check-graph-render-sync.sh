#!/usr/bin/env bash
# Fails when a copy of the graph renderer differs from the canonical file (run scripts/sync-graph-render.sh to fix).
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source_file="$root/src/loom/graph-render/graph-render.js"
status=0
for target in \
  "$root/src/loom/vscode-loom/media/graph-render.js" \
  "$root/src/eval4j-report/src/main/resources/io/github/llm4j/evalreport/render/graph-render.js"; do
  if ! cmp -s "$source_file" "$target"; then
    echo "OUT OF SYNC: ${target#$root/} differs from src/loom/graph-render/graph-render.js" >&2
    status=1
  fi
done
css_target="$root/src/loom/vscode-loom/media/graph-render.css"
if ! node -e "process.stdout.write(require(process.argv[1]).css + '\n')" "$source_file" | cmp -s - "$css_target"; then
  echo "OUT OF SYNC: ${css_target#$root/} differs from the rules in src/loom/graph-render/graph-render.js" >&2
  status=1
fi
[ "$status" -eq 0 ] && echo "graph-render copies are in sync"
exit "$status"
