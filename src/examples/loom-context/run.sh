#!/usr/bin/env bash
# Runs the Loom context demo: a script whose agent outgrows its window, kept inside it by its context block. No model, no key.
#   ./run.sh              (builds the jar the first time)
#   BUILD=1 ./run.sh      (rebuild it first)
set -euo pipefail
cd "$(dirname "$0")"
ROOT=../../..
if [ "${BUILD:-}" = "1" ] || ! ls $ROOT/src/loom/ai-agent4j-loom/target/ai-agent4j-loom-*-cli.jar >/dev/null 2>&1; then
  echo "building the jar (once)..." >&2
  (cd "$ROOT" && mvn -q -DskipTests -pl src/loom/ai-agent4j-loom -am package)
fi
JAR=$(ls $ROOT/src/loom/ai-agent4j-loom/target/ai-agent4j-loom-*-cli.jar | head -1)
java -Dorg.slf4j.simpleLogger.defaultLogLevel=warn -cp "$JAR" LoomContextDemo.java
echo
echo "--- weave audit shows each agent's strategy and where its window comes from ---"
java -cp "$JAR" io.github.llm4j.loom.cli.WeaveCLI audit research.loom --context-windows research.windows --fail-on none 2>/dev/null | sed -n '/## Context and compaction/,/## Findings/p' | head -20
