#!/usr/bin/env bash
# Draws each starter's workflow into its README, between the <!-- graph:start --> and <!-- graph:end --> markers, with
# `weave graph --format mermaid`. StartersTest fails when a picture is out of date. Run after changing a starter's main.loom.
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
MOD=$ROOT/src/loom/ai-agent4j-loom
CP=${WEAVE_CP:-$MOD/target/classes:$MOD/target/lib/*}
for dir in "$ROOT"/src/loom/starters/[0-9]*/; do
    name=$(basename "$dir")
    workflow=$(awk '/^workflow:/ {print $2; exit}' "$dir/tests/main.test.yaml" 2>/dev/null || true)
    workflow=${workflow:-Main}
    graph=$(cd "$dir" && java -Dfile.encoding=UTF-8 -cp "$CP" io.github.llm4j.loom.cli.WeaveCLI graph main.loom --workflow "$workflow" --format mermaid 2>/dev/null | grep -v '^%%')
    tmp=$(mktemp)
    awk -v graph="$graph" '
        /<!-- graph:start -->/ { print; print "```mermaid"; print graph; print "```"; skip = 1; next }
        /<!-- graph:end -->/   { skip = 0 }
        !skip                  { print }
    ' "$dir/README.md" > "$tmp"
    mv "$tmp" "$dir/README.md"
    echo "drew $name ($workflow)"
done
