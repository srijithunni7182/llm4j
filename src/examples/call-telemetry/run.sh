#!/usr/bin/env bash
# Runs the call-telemetry demo: what each tool and model call did. No model, no key.
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
exec java -Dorg.slf4j.simpleLogger.defaultLogLevel=warn -cp "$JAR" CallTelemetryDemo.java
