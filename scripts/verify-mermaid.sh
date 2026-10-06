#!/usr/bin/env bash
# V3.2: the Mermaid that `weave graph --format mermaid` prints is accepted by the real Mermaid parser.
# Installs mermaid and jsdom in a temporary folder (needs network access to the npm registry), then parses the golden
# files in loom/ai-agent4j-loom/src/test/resources/graph/golden. A deliberately broken diagram must be refused.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
cp "$root/scripts/mermaid-parse.mjs" "$work/parse.mjs"
(cd "$work" && npm init -y >/dev/null && npm install mermaid@10.9.1 jsdom@24 --no-audit --no-fund >/dev/null 2>&1)
node "$work/parse.mjs" "$root/loom/ai-agent4j-loom/src/test/resources/graph/golden"
mkdir "$work/broken" && printf 'flowchart TD\n  a --> -->| b\n' > "$work/broken/bad.mmd"
if node "$work/parse.mjs" "$work/broken" >/dev/null 2>&1; then echo "the parser accepted a broken diagram: the check proves nothing" >&2; exit 1; fi
echo "mermaid ok: golden diagrams parse, a broken one is refused"
