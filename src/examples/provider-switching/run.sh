#!/usr/bin/env bash
# Asks one question through the routing policy in switch.loom. Usage: ./run.sh "question"
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
weave="${WEAVE_JAR:-$here/../../loom/ai-agent4j-loom/target/ai-agent4j-loom-6.2.0-cli.jar}"
cd "$here"
java -jar "$weave" run switch.loom -i question="${1:-What is the capital of France?}" "${@:2}"
