#!/usr/bin/env bash
# P15, Loom side. Needs the Loom module built (target/classes and target/lib).
HERE=$(cd "$(dirname "$0")" && pwd)
MOD=$HERE/../../../src/loom/ai-agent4j-loom
CP="$MOD/target/classes:$MOD/target/lib/*"
weave() { java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "$CP" io.github.llm4j.loom.cli.WeaveCLI "$@" 2>&1 | grep -v "Picked up\|^INFO\|loadRecursive\|^WARNING\|^[A-Z][a-z][a-z] [0-9][0-9], "; }
export PAY_HOOK=https://example.com/hook GEMINI_API_KEY=test-key
echo "== weave review main.loom, before anything runs =="; weave review "$HERE/main.loom" --min-severity low | grep -A1 -E "LR11"
echo; echo "== weave review fixed.loom =="; weave review "$HERE/fixed.loom" --min-severity low | grep -c "LR11" | sed 's/^/LR11 findings: /'
