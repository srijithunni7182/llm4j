#!/usr/bin/env bash
# What loom-inspect knows about a run's tools, models and context: no model, no key, no server.
#   ./run.sh              (builds the jars the first time)
#   BUILD=1 ./run.sh      (rebuild them first)
set -euo pipefail
cd "$(dirname "$0")"
ROOT=../../..
if [ "${BUILD:-}" = "1" ] || ! ls $ROOT/src/loom/loom-inspect/target/loom-inspect-[0-9]*.jar $ROOT/src/loom/loom-inspect-core/target/loom-inspect-core-[0-9]*.jar $ROOT/src/loom/ai-agent4j-loom/target/ai-agent4j-loom-*-cli.jar >/dev/null 2>&1; then
  echo "building the jars (once)..." >&2
  (cd "$ROOT" && mvn -q -DskipTests -pl src/loom/ai-agent4j-loom,src/loom/loom-inspect -am package)
fi
WEAVE=$(ls $ROOT/src/loom/ai-agent4j-loom/target/ai-agent4j-loom-*-cli.jar | head -1)
INSPECT=$(ls $ROOT/src/loom/loom-inspect/target/loom-inspect-[0-9]*.jar | grep -v sources | head -1)
CORE=$(ls $ROOT/src/loom/loom-inspect-core/target/loom-inspect-core-[0-9]*.jar | grep -v sources | head -1)
exec java -Dorg.slf4j.simpleLogger.defaultLogLevel=warn -cp "$WEAVE:$INSPECT:$CORE" InspectToolsDemo.java 2>&1 | grep -v "^Picked up\|INFO:\|^[A-Z][a-z][a-z] [0-9]*, 20\|WARNING: line"
