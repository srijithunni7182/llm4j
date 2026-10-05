#!/usr/bin/env bash
# Builds the Loom VS Code extension package (.vsix) with a freshly built weave.jar inside.
# Usage: scripts/build-vsix.sh   -> loom/vscode-loom/vscode-loom-<version>.vsix
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ext="$root/loom/vscode-loom"

echo "== building weave.jar"
mvn -q -f "$root/pom.xml" -pl loom/ai-agent4j-loom -am -DskipTests package
jar="$(ls "$root"/loom/ai-agent4j-loom/target/ai-agent4j-loom-*.jar | grep -v '/original-' | head -n 1)"
mkdir -p "$ext/bin"
cp "$jar" "$ext/bin/weave.jar"

echo "== refreshing generated files"
bash "$root/scripts/sync-graph-render.sh" >/dev/null
bash "$root/scripts/make-logo-assets.sh" >/dev/null

echo "== compiling and packaging the extension"
cd "$ext"
npm ci --no-audit --no-fund >/dev/null
npm run compile >/dev/null
npx --yes @vscode/vsce@3.2.1 package --skip-license --allow-missing-repository 2>&1 | tail -n 5
ls -1 "$ext"/*.vsix
