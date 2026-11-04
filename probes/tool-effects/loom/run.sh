#!/usr/bin/env bash
# P14, Loom side. Needs the Loom module built (target/classes and target/lib).
HERE=$(cd "$(dirname "$0")" && pwd)
MOD=$HERE/../../../src/loom/ai-agent4j-loom
CP="$MOD/target/classes:$MOD/target/lib/*"
weave() { java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "$CP" io.github.llm4j.loom.cli.WeaveCLI "$@" 2>&1 | grep -v "Picked up\|^INFO\|loadRecursive\|^WARNING\|^[A-Z][a-z][a-z] [0-9][0-9], "; }
export SLACK_WEBHOOK=https://example.com/hook
echo "== weave check retries.loom: retry on a step that changes something =="; weave check "$HERE/retries.loom"
echo; echo "== weave check approved.loom =="; weave check "$HERE/approved.loom"
