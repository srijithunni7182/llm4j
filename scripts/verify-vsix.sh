#!/usr/bin/env bash
# Checks a built .vsix has what the workflow graph needs, then unpacks it and runs the bundled weave.jar the way the
# extension does (java -jar bin/weave.jar graph <file> --format json).
# Usage: scripts/verify-vsix.sh [path-to.vsix]
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
vsix="${1:-$(ls "$root"/src/loom/vscode-loom/*.vsix | head -n 1)}"
listing="$(unzip -Z1 "$vsix")"
status=0
for required in \
  extension/package.json extension/out/extension.js extension/out/views/GraphPanel.js extension/bin/weave.jar \
  extension/media/graph-render.js extension/media/graph-render.css extension/media/panel.js extension/media/panel.css \
  extension/media/loom-mark-128.png extension/media/loom-logo-320.png; do
  if ! grep -qx "$required" <<<"$listing"; then echo "MISSING from the package: $required" >&2; status=1; fi
done
for forbidden in 'out-test/' 'test/'; do
  if grep -E "^extension/.*$forbidden" <<<"$listing" | grep -v 'node_modules' | grep -q .; then echo "SHOULD NOT be in the package: $forbidden" >&2; status=1; fi
done
[ "$status" -eq 0 ] || exit "$status"
work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
unzip -q "$vsix" -d "$work"
out="$(java -jar "$work/extension/bin/weave.jar" graph "$root/src/loom/ai-agent4j-loom/samples/content_factory/main.loom" --format json)"
grep -q '"version": 1' <<<"$out" && grep -q '"GenerateContent"' <<<"$out" || { echo "the bundled weave.jar did not draw the sample" >&2; exit 1; }
echo "vsix ok: $(wc -l <<<"$listing") files, bundled weave.jar draws the sample"
