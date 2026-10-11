#!/usr/bin/env bash
# Runs the soak workflow. It needs GEMINI_API_KEY (or put it in the secret store and add --secrets).
#
#   ./run-soak.sh                      # about 30 minutes, trace on stderr
#   PACE=30 ./run-soak.sh              # a shorter run for a quick check (about 12 minutes)
#   ASK_HUMAN=yes ./run-soak.sh        # the publisher waits for you to approve the file write
#
# Extra weave options (for example --observe-server once loom-inspect exists) go on the command line:
#   ./run-soak.sh --observe-server https://inspect.example.com:9090 --observe-name soak-1
set -euo pipefail
cd "$(dirname "$0")"
: "${WEAVE_JAR:=../../loom/ai-agent4j-loom/target/ai-agent4j-loom-5.0-cli.jar}"
: "${TOPIC:=cold plunges and recovery}"
: "${PACE:=90}"
: "${ASK_HUMAN:=no}"
[ -f soak-tasks.jar ] || ./build-tasks.sh
exec java -cp "$WEAVE_JAR:soak-tasks.jar" io.github.llm4j.loom.cli.WeaveCLI run soak.loom \
     -i topic="$TOPIC" -i pace="$PACE" -i ask_human="$ASK_HUMAN" \
     --journal runs/soak --wait --trace "$@"
