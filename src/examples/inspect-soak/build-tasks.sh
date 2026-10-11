#!/usr/bin/env bash
# Builds soak-tasks.jar (the Pace task) against the weave jar.
# Usage: WEAVE_JAR=../../loom/ai-agent4j-loom/target/ai-agent4j-loom-5.0-cli.jar ./build-tasks.sh
set -euo pipefail
cd "$(dirname "$0")"
: "${WEAVE_JAR:=../../loom/ai-agent4j-loom/target/ai-agent4j-loom-5.0-cli.jar}"
rm -rf out && mkdir -p out
javac -cp "$WEAVE_JAR" -d out tasks/soak/Pace.java
jar cf soak-tasks.jar -C out soak -C tasks META-INF
echo "built soak-tasks.jar"
