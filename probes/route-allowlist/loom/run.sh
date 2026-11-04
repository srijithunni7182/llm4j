#!/usr/bin/env bash
# P17, Loom side. Needs the Loom module built (target/classes and target/lib).
HERE=$(cd "$(dirname "$0")" && pwd)
MOD=$HERE/../../../src/loom/ai-agent4j-loom
CP="$MOD/target/classes:$MOD/target/lib/*"
weave() { java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "$CP" io.github.llm4j.loom.cli.WeaveCLI "$@" 2>&1 | grep -v "Picked up\|^INFO\|loadRecursive\|^WARNING\|^[A-Z][a-z][a-z] [0-9][0-9], "; }
export MAIL_HOOK=https://example.com/hook GEMINI_API_KEY=test-key
echo "== weave check unbounded.loom: before anything runs =="; weave check "$HERE/unbounded.loom"
echo; echo "== weave check main.loom =="; weave check "$HERE/main.loom"
echo; echo "== weave test main.loom: a route outside the allow-list at run time =="; (cd "$HERE" && weave test main.loom | tail -4)
