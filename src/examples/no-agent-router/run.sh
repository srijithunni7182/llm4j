#!/usr/bin/env bash
# Compiles the three tasks and runs the workflow with weave. Usage: ./run.sh "message" [order] [amount]
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
weave="${WEAVE_JAR:-$here/../../loom/ai-agent4j-loom/target/ai-agent4j-loom-6.2.0-cli.jar}"
out="$here/tasks/out"; rm -rf "$out"; mkdir -p "$out"
javac -cp "$weave" -d "$out" "$here"/tasks/src/router/*.java
cp -r "$here/tasks/META-INF" "$out/"
cd "$here"
java -cp "$weave:$out" io.github.llm4j.loom.cli.WeaveCLI run router.loom -i message="$1" -i order="${2:-A-1042}" -i amount="${3:-40}" "${@:4}"
