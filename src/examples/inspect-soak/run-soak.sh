#!/usr/bin/env bash
# Runs the soak workflow. It needs GEMINI_API_KEY (or put it in the secret store and add --secrets).
#
#   ./run-soak.sh                      # about 30 minutes, trace on stderr
#   ASK_HUMAN=yes ./run-soak.sh        # the publisher waits for you to approve the file write
#
# The workflow sleeps between steps with `sleep`. With --journal the run pauses for each sleep and holds no
# thread; --wait keeps this process alive so it wakes the run itself. For a shorter run, lower the sleep
# durations in soak.loom.
#
# Extra weave options (for example --observe-fabric, with the client key in the secret store) go on the command line:
#   ./run-soak.sh --observe-fabric https://fabric.example.com:9090 --observe-name soak-1
set -euo pipefail
cd "$(dirname "$0")"
: "${WEAVE_JAR:=../../loom/ai-agent4j-loom/target/ai-agent4j-loom-6.0.0-cli.jar}"
: "${TOPIC:=cold plunges and recovery}"
: "${ASK_HUMAN:=no}"
exec java -cp "$WEAVE_JAR${INSPECT_JARS:+:$INSPECT_JARS}" io.github.llm4j.loom.cli.WeaveCLI run soak.loom \
     -i topic="$TOPIC" -i ask_human="$ASK_HUMAN" \
     --journal runs/soak --wait --trace "$@"
