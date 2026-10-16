#!/usr/bin/env bash
# Gate G7: a real process. A host run suspends at a question; the process is killed with kill -9; a new process answers; the run finishes without
# repeating the paid-for step (the stand-in model saw 2 calls, not 3); weave timeline reads the run. Usage: spring-starter-g7.sh EVIDENCE_FILE
set -uo pipefail
cd "$(dirname "$0")/.."
OUT=${1:-/dev/stdout}
LOOM=src/loom/ai-agent4j-loom
JAR=$(ls $LOOM/target/*-cli.jar 2>/dev/null | head -1)
[ -n "$JAR" ] || { mvn -B -q -pl $LOOM package -DskipTests -Djacoco.skip=true >/dev/null 2>&1; JAR=$(ls $LOOM/target/*-cli.jar | head -1); }
W=$(mktemp -d); PORT=${G7_PORT:-18791}; FAIL=0
say() { echo "$*" | tee -a "$OUT"; }
{ echo "commit $(git rev-parse HEAD)"; date -u; } > "$OUT"
java -cp "$JAR" scripts/g7/Host7.java serve $PORT "$W/count" > "$W/serve.log" 2>&1 & SERVER=$!
for _ in $(seq 1 50); do grep -q SERVING "$W/serve.log" 2>/dev/null && break; sleep 0.2; done
java -cp "$JAR" scripts/g7/Host7.java run "$W/store" scripts/g7/flow.loom "http://127.0.0.1:$PORT" > "$W/run.log" 2>&1 & RUNNER=$!
for _ in $(seq 1 100); do grep -q SUSPENDED "$W/run.log" 2>/dev/null && break; sleep 0.2; done
say "run process says: $(grep -h 'SUSPENDED\|COMPLETED' "$W/run.log" | head -1)"
grep -q SUSPENDED "$W/run.log" || { say "FAIL: the run did not suspend"; FAIL=1; }
kill -9 $RUNNER 2>/dev/null; wait $RUNNER 2>/dev/null; say "run process killed with kill -9; model calls so far: $(cat "$W/count" 2>/dev/null)"
STEP=$(grep -o 'SUSPENDED g7-run .*' "$W/run.log" | awk '{print $3}')
java -cp "$JAR" scripts/g7/Host7.java answer "$W/store" scripts/g7/flow.loom "http://127.0.0.1:$PORT" g7-run "$STEP" yes > "$W/answer.log" 2>&1
say "answer process says: $(tr '\n' ' ' < "$W/answer.log")"
grep -q "COMPLETED" "$W/answer.log" || { say "FAIL: the run did not complete after the answer"; FAIL=1; }
CALLS=$(cat "$W/count" 2>/dev/null)
say "model calls in total: $CALLS (draft once before the kill, finish once after; the draft was not repeated)"
[ "$CALLS" = "2" ] || { say "FAIL: expected 2 model calls"; FAIL=1; }
say "-- weave timeline"
java -jar "$JAR" timeline "$W/store/runs/g7-run" 2>&1 | grep -v "^Picked up" | tee -a "$OUT" > "$W/timeline.log"
grep -q "Main" "$W/timeline.log" || { say "FAIL: weave timeline did not show the run"; FAIL=1; }
say "-- the real loom-inspect page lists a running run (V12.7)"
INSPECT=$(ls src/loom/loom-inspect/target/loom-inspect-*.jar 2>/dev/null | grep -v "sources\|javadoc" | head -1)
if [ -z "$INSPECT" ]; then mvn -B -q -pl src/loom/loom-inspect-core,src/loom/loom-inspect package -DskipTests -Djacoco.skip=true >/dev/null 2>&1; INSPECT=$(ls src/loom/loom-inspect/target/loom-inspect-*.jar | grep -v "sources\|javadoc" | head -1); fi
kill $SERVER 2>/dev/null
java -cp "$JAR" scripts/g7/Host7.java serve $((PORT+1)) "$W/count2" 3000 > "$W/serve2.log" 2>&1 & SERVER=$!
for _ in $(seq 1 50); do grep -q SERVING "$W/serve2.log" 2>/dev/null && break; sleep 0.2; done
java -cp "$JAR:$INSPECT:$(ls src/loom/loom-inspect-core/target/loom-inspect-core-*.jar 2>/dev/null | grep -v "sources\|javadoc" | head -1)" scripts/g7/Host7.java observe "$W/store2" scripts/g7/flow.loom "http://127.0.0.1:$((PORT+1))" $((PORT+2)) > "$W/observe.log" 2>&1
OBS=$?
say "page says: $(grep -h '^RUNS' "$W/observe.log" | cut -c1-300)"
[ $OBS -eq 0 ] || { say "FAIL: the page did not list the run"; tail -5 "$W/observe.log" | tee -a "$OUT"; FAIL=1; }
kill $SERVER 2>/dev/null; rm -rf "$W"
[ $FAIL -eq 0 ] && say "G7 PASS" || say "G7 FAIL"
exit $FAIL
