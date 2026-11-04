#!/usr/bin/env bash
# P12, Loom side. Needs the Loom module built (target/classes and target/lib).
HERE=$(cd "$(dirname "$0")" && pwd)
MOD=$HERE/../../../src/loom/ai-agent4j-loom
CP="$MOD/target/classes:$MOD/target/lib/*"
weave() { java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "$CP" io.github.llm4j.loom.cli.WeaveCLI "$@" 2>&1 | grep -v "Picked up\|^INFO\|loadRecursive\|^WARNING\|^[A-Z][a-z][a-z] [0-9][0-9], "; }
export SLACK_WEBHOOK=https://example.com/hook GEMINI_API_KEY=test-key
echo "== weave test edge.loom: scripted models, the question and the send =="; (cd "$HERE" && weave test edge.loom 2>&1 | tail -6)
echo; echo "== weave review mid-loop.loom: the question inside the agent's loop =="; weave review "$HERE/mid-loop.loom" --min-severity low | grep -E "^(HIGH|MEDIUM|LOW|INFO) +L[HA]"
echo; echo "== weave review many-questions.loom: how many questions one run can put to a person =="; weave review "$HERE/many-questions.loom" --min-severity low | grep -E "^(HIGH|MEDIUM|LOW|INFO) +L[HA]"
