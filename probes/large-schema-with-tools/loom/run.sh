#!/usr/bin/env bash
# P10, Loom side. Needs the Loom module built (target/classes and target/lib).
HERE=$(cd "$(dirname "$0")" && pwd)
MOD=$HERE/../../../src/loom/ai-agent4j-loom
CP="$MOD/target/classes:$MOD/target/lib/*"
weave() { java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "$CP" io.github.llm4j.loom.cli.WeaveCLI "$@" 2>&1 | grep -v "Picked up\|^INFO\|loadRecursive"; }
echo "== weave review main.loom, before anything runs =="
weave review "$HERE/main.loom" --min-severity low | grep -A2 "LP10"
