#!/usr/bin/env bash
# P11, Loom side. Needs the Loom module built (target/classes and target/lib).
HERE=$(cd "$(dirname "$0")" && pwd)
MOD=$HERE/../../../src/loom/ai-agent4j-loom
CP="$MOD/target/classes:$MOD/target/lib/*"
weave() { java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "$CP" io.github.llm4j.loom.cli.WeaveCLI "$@" 2>&1 | grep -v "Picked up\|^INFO\|loadRecursive\|^WARNING\|^[A-Z][a-z][a-z] [0-9][0-9], "; }
export MAIL_HOOK=https://example.com/hook GEMINI_API_KEY=test-key
echo "== weave check unchecked.loom (a policy, and an exit with no check before it) =="; weave check "$HERE/unchecked.loom"
echo; echo "== weave check checked.loom =="; weave check "$HERE/checked.loom"
echo; echo "== weave check sending-agent.loom: the agent's own calls to Mail =="; weave check "$HERE/sending-agent.loom"
