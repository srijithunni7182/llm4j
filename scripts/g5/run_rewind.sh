#!/usr/bin/env bash
# Gate G5 for rewind and fork: real `weave` processes against stand-in services. Transcripts go to evidence/g5/.
#   scripts/g5/run_rewind.sh          (needs the classes built: mvn -pl src/loom/ai-agent4j-loom compile; dependencies are copied to target/lib)
set -uo pipefail
cd "$(dirname "$0")/../.."
ROOT=$(pwd)
MODULE=$ROOT/src/loom/ai-agent4j-loom
OUT=$ROOT/.kiro/specs/loom-rewind-and-fork/evidence/g5
WORK=$(mktemp -d)
PORT=${G5_PORT:-8766}
CP="$MODULE/target/classes:$MODULE/target/lib/*"
mkdir -p "$OUT"
[ -d "$MODULE/target/lib" ] || mvn -B -q -f "$MODULE/pom.xml" dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/lib >&2
weave() { java -cp "$CP" io.github.llm4j.loom.cli.WeaveCLI "$@" 2>&1 | grep -v "Picked up\|^INFO\|LoomLoader\|^[A-Z][a-z][a-z] [0-9][0-9], 20\|HarnessExecutor\|^WARNING"; }
services_pid=
start_services() {
  python3 "$ROOT/scripts/g5/rewind_services.py" --port "$PORT" --log "$1" "${@:2}" > "$WORK/services.out" 2>&1 & services_pid=$!
  for _ in $(seq 1 50); do grep -q "fake services" "$WORK/services.out" 2>/dev/null && return; sleep 0.1; done
}
stop_services() { [ -n "$services_pid" ] && kill "$services_pid" 2>/dev/null; wait "$services_pid" 2>/dev/null; services_pid=; }
trap 'stop_services; rm -rf "$WORK"' EXIT
export OLLAMA_BASE_URL="http://127.0.0.1:$PORT" SLACK_WEBHOOK="http://127.0.0.1:$PORT/hook/T000/B000/SECRETSECRET1234" HOME="$WORK/home"
mkdir -p "$HOME"
posts() { grep -c '"path": "/hook' "$1"; }
chats() { grep -c 'agent' "$1"; }
D=$WORK/run; mkdir -p "$D"; cp "$ROOT/scripts/g5/report.loom" "$D/"; cd "$D" || exit 1
RUN="weave run report.loom --workflow Report --input topic=bees --trace"

echo "== R1: the report goes back twice and publishes"
start_services "$WORK/r1.log"
{
  echo "\$ $RUN --journal runs/a"; $RUN --journal runs/a | grep -E "rewind|Workflow|Slack|Reviewer .*score|\[Report/s6"
  echo "--- weave timeline runs/a:"; weave timeline runs/a
  echo "--- Slack posts: $(posts "$WORK/r1.log") (expected 1); model calls: $(chats "$WORK/r1.log") (expected 3 attempts x 4 + the publisher's 2 = 14)"
} > "$OUT/R1-report.txt" 2>&1
stop_services

echo "== R2: kill -9 while the second attempt is under way, then resume"
start_services "$WORK/r2a.log" --hang Collector:fix1
{
  echo "\$ $RUN --journal runs/k   (the collector's call in generation 2 hangs)"
  java -cp "$CP" io.github.llm4j.loom.cli.WeaveCLI run report.loom --workflow Report --input topic=bees --journal runs/k --trace > "$WORK/r2-first.out" 2>&1 &
  WPID=$!
  for _ in $(seq 1 150); do grep -q 'fix1' "$WORK/r2a.log" && break; sleep 0.2; done
  sleep 1; kill -9 "$WPID" 2>/dev/null; wait "$WPID" 2>/dev/null
  echo "weave killed with SIGKILL while generation 2 was starting"
  echo "--- timeline after the kill:"; weave timeline runs/k | sed -n '/Rewinds:/,$p'
  stop_services
  start_services "$WORK/r2b.log"
  echo; echo "\$ the same command again (resume)"; $RUN --journal runs/k | grep -E "rewind|Workflow|Slack"
  echo "--- timeline after the resume:"; weave timeline runs/k | sed -n '/Checkpoints/,$p'
  echo "--- rewinds recorded: $(weave timeline runs/k | grep -c '^  generation') (expected 2: one before the kill, one after, none repeated)"
  echo "--- Slack posts: $(posts "$WORK/r2a.log") before + $(posts "$WORK/r2b.log") after (expected 0 + 1)"
} > "$OUT/R2-kill-resume.txt" 2>&1
stop_services

echo "== R3: kill -9 after Slack was told, then resume (no second post)"
start_services "$WORK/r3a.log" --hang Publisher
{
  echo "\$ $RUN --journal runs/p   (the publisher's model hangs after the Slack call)"
  java -cp "$CP" io.github.llm4j.loom.cli.WeaveCLI run report.loom --workflow Report --input topic=bees --journal runs/p --trace > "$WORK/r3-first.out" 2>&1 &
  WPID=$!
  for _ in $(seq 1 150); do grep -q '/hook' "$WORK/r3a.log" && break; sleep 0.2; done
  sleep 1.5; kill -9 "$WPID" 2>/dev/null; wait "$WPID" 2>/dev/null
  echo "Slack posts before the kill: $(posts "$WORK/r3a.log"); weave killed with SIGKILL"
  stop_services; start_services "$WORK/r3b.log"
  echo "\$ resume"; $RUN --journal runs/p | grep -E "Workflow|Slack|rewind"
  echo "--- Slack posts: $(posts "$WORK/r3a.log") + $(posts "$WORK/r3b.log") (expected 1 + 0)"
} > "$OUT/R3-kill-after-slack.txt" 2>&1
stop_services

echo "== R4: operator commands on the finished run"
start_services "$WORK/r4.log"
{
  cp -r runs/a runs/o
  echo "\$ weave rewind runs/o --to collected --reason redo      (the report was already posted to Slack: held)"
  weave rewind runs/o --to collected --reason "redo"
  echo; echo "\$ weave rewind runs/o --to collected --effects keep --set feedback=fix2 --reason redo --resume"
  weave rewind runs/o --to collected --effects keep --set feedback=fix2 --reason "redo" --resume | grep -E "rewound|Workflow|Slack|rewind"
  echo "--- timeline (rewinds):"; weave timeline runs/o | sed -n '/Rewinds:/,$p'
  echo "--- Slack posts so far: $(posts "$WORK/r4.log") (expected 0: the identical post was found in the journal, not repeated)"
  echo; echo "\$ weave fork runs/a --to runs/f --at collected --effects simulate --reason \"what if\" --resume"
  weave fork runs/a --to runs/f --at collected --effects simulate --reason "what if" --resume | grep -E "fork|Workflow|simulat|Slack"
  echo "--- Slack posts after the simulated fork: $(posts "$WORK/r4.log") (expected 0)"
  echo "--- the parent's timeline is unchanged (still two rewinds, not three):"; weave timeline runs/a | grep -c '^  generation'
} > "$OUT/R4-operator.txt" 2>&1
stop_services

echo "== R5: fork under another script, with and without drift"
start_services "$WORK/r5.log"
{
  sed 's/Publish {draft}/Publish the final {draft}/' report.loom > later.loom
  sed 's/Collect sources on/Gather sources on/' report.loom > earlier.loom
  echo "\$ weave fork runs/a --to runs/g1 --script later.loom --at collected --effects simulate --reason t   (changed after the point: accepted)"
  weave fork runs/a --to runs/g1 --script later.loom --at collected --effects simulate --reason t
  echo; echo "\$ weave fork runs/a --to runs/g2 --script earlier.loom --at Report/s2 --effects simulate --reason t   (changed before the point: refused)"
  weave fork runs/a --to runs/g2 --script earlier.loom --at Report/s2 --effects simulate --reason t
  echo; echo "\$ ... --allow-drift"
  weave fork runs/a --to runs/g3 --script earlier.loom --at Report/s2 --effects simulate --reason t --allow-drift
} > "$OUT/R5-fork-script.txt" 2>&1
stop_services

echo "== R6: reset --failed after a model outage"
start_services "$WORK/r6a.log" --fail Analyst
{
  echo "\$ $RUN --journal runs/x   (the analyst's model answers HTTP 500)"
  $RUN --journal runs/x | grep -E "ail|Workflow|rror" | head -5
  stop_services; start_services "$WORK/r6b.log"
  echo; echo "\$ weave reset runs/x --failed --reason \"model back\" --resume"
  weave reset runs/x --failed --reason "model back" --resume | grep -E "retry|ail|Workflow|reset|Slack"
  echo "--- timeline:"; weave timeline runs/x | head -12
} > "$OUT/R6-reset-failed.txt" 2>&1
stop_services
echo "transcripts in $OUT"
