#!/usr/bin/env bash
# P13, Loom side. Needs the Loom module built (target/classes and target/lib).
HERE=$(cd "$(dirname "$0")" && pwd)
MOD=$HERE/../../../src/loom/ai-agent4j-loom
CP="$MOD/target/classes:$MOD/target/lib/*"
weave() { java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "$CP" io.github.llm4j.loom.cli.WeaveCLI "$@" 2>&1 | grep -v "Picked up\|^INFO\|loadRecursive\|^WARNING\|^[A-Z][a-z][a-z] [0-9][0-9], "; }
export GEMINI_API_KEY=test-key
echo "== weave check untested/main.loom: the evaluator has no cases =="; weave check "$HERE/untested/main.loom"
echo; echo "== weave check tested/main.loom: a case that breaks the rule and a clean one =="; weave check "$HERE/tested/main.loom"
