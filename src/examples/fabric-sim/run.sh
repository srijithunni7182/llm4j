#!/usr/bin/env bash
# Starts a Fabric server and N simulated Loom processes that report to it, so you can exercise Fabric
# (the Processes page, one tab per process and workflow, incidents, pause/suspend/stop) with no model and no key.
#
#   ./run.sh                 # 3 processes, runs of about 4 minutes, until Ctrl-C
#   PROCESSES=5 SPEED=0.5 ./run.sh
#   BUILD=1 ./run.sh         # build the jars first
#
# Environment: PROCESSES (3), SPEED (1.0; 0.2 is five times faster), PORT (9090), DATA (./fabric-sim-data),
#              NO_PRICES=1 (the simulated processes report no cost, so the Fabric price table does the costing),
#              ADMIN_PASSWORD (default: simulate-admin-1, printed), DETAIL (truncated), RUNS (0 = until Ctrl-C)
set -euo pipefail
cd "$(dirname "$0")"
ROOT=../../..
: "${PROCESSES:=3}" "${SPEED:=1.0}" "${PORT:=9090}" "${DATA:=$PWD/fabric-sim-data}" "${DETAIL:=truncated}" "${RUNS:=0}"
: "${ADMIN_PASSWORD:=simulate-admin-1}"
# build the jars when asked, or when they are not there yet
if [ "${BUILD:-}" = "1" ] || ! ls $ROOT/src/loom/loom-fabric/target/loom-fabric-*-all.jar $ROOT/src/loom/loom-inspect/target/loom-inspect-[0-9]*.jar $ROOT/src/loom/ai-agent4j-loom/target/ai-agent4j-loom-*-cli.jar >/dev/null 2>&1; then
  echo "building the jars (once)..."
  (cd "$ROOT" && mvn -q -DskipTests -pl src/loom/ai-agent4j-loom,src/loom/loom-inspect,src/loom/loom-fabric -am package)
fi
WEAVE=$(ls $ROOT/src/loom/ai-agent4j-loom/target/ai-agent4j-loom-*-cli.jar | head -1)
INSPECT=$(ls $ROOT/src/loom/loom-inspect/target/loom-inspect-[0-9]*.jar | grep -v sources | head -1)
CORE=$(ls $ROOT/src/loom/loom-inspect-core/target/loom-inspect-core-[0-9]*.jar | grep -v sources | head -1)
FABRIC=$(ls $ROOT/src/loom/loom-fabric/target/loom-fabric-*-all.jar | head -1)
fabric() { java -jar "$FABRIC" "$@"; }

rm -rf "$DATA"
LOOM_FABRIC_ADMIN_PASSWORD="$ADMIN_PASSWORD" fabric init --data "$DATA" >/dev/null
pids=()
cleanup() { for p in "${pids[@]:-}"; do kill "$p" 2>/dev/null || true; done; }
trap cleanup EXIT INT TERM

fabric serve --data "$DATA" --port "$PORT" >"$DATA/server.log" 2>&1 &
pids+=($!)
for _ in $(seq 1 50); do curl -s "http://127.0.0.1:$PORT/api/health" >/dev/null && break; sleep 0.2; done

for i in $(seq 1 "$PROCESSES"); do
  name="sim-$i"
  key=$(fabric clients add "$name" --data "$DATA" | awk '/^    lfk_/ {print $1}')
  SIM_FABRIC_KEY="$key" java -cp "$WEAVE:$INSPECT:$CORE" io.github.llm4j.loom.inspect.sim.Simulate \
      --fabric "http://127.0.0.1:$PORT" --name "$name" --runs "$RUNS" --speed "$SPEED" --detail "$DETAIL" \
      ${NO_PRICES:+--no-prices} --outbox "$DATA/outbox" --gap 15 >"$DATA/$name.log" 2>&1 &
  pids+=($!)
  sleep 2   # stagger them, so the processes are at different steps
done

echo
echo "Fabric:    http://127.0.0.1:$PORT      (sign in with: $ADMIN_PASSWORD)"
echo "Processes: $PROCESSES simulated, logs in $DATA/sim-N.log, server log in $DATA/server.log"
echo "Try: pause, resume, suspend and stop on a run; stop one simulator (kill it) and watch it go offline;"
echo "     restart the server (Ctrl-C only stops everything; see README.md) and watch the processes catch up."
echo "Ctrl-C stops everything."
wait
