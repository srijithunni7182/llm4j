#!/usr/bin/env bash
# P9, Loom side. Needs the Loom module built (target/classes and target/lib).
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
MOD=$HERE/../../../src/loom/ai-agent4j-loom
CP="$MOD/target/classes:$MOD/target/lib/*"
weave() { java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "$CP" io.github.llm4j.loom.cli.WeaveCLI "$@" 2>&1 | grep -v "Picked up\|^INFO\|loadRecursive"; }
WORK=$(mktemp -d)
javac -cp "$CP" -d "$WORK" "$HERE/src/Probe.java" 2>&1 | grep -v "Picked up"
quiet() { grep -v "Picked up\|^INFO\|^WARNING\|loadRecursive\|^[A-Z][a-z][a-z] [0-9][0-9], "; }
echo "== main.loom (an extractor), run with a recording model =="
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "$CP:$WORK" Probe "$HERE/main.loom" 2>&1 | quiet
echo "== agent-as-extractor.loom (the same job as an agent), run the same way =="
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "$CP:$WORK" Probe "$HERE/agent-as-extractor.loom" 2>&1 | quiet
echo
echo "== weave review agent-as-extractor.loom: the same job written as an agent =="
weave review "$HERE/agent-as-extractor.loom" --min-severity low | grep -E "LP08|LP09|LA21"
