#!/usr/bin/env bash
# Gate G5 for earned autonomy: real `weave` processes against a stand-in model server; the person is the shell (stdin). Transcripts go to evidence/g5/.
#   scripts/g5/run_autonomy.sh      (needs the classes built: mvn -pl src/loom/ai-agent4j-loom compile; dependencies are copied to target/lib)
set -uo pipefail
cd "$(dirname "$0")/../.."
ROOT=$(pwd)
MODULE=$ROOT/src/loom/ai-agent4j-loom
OUT=$ROOT/.kiro/specs/loom-earned-autonomy/evidence/g5
WORK=$(mktemp -d)
PORT=${G5_PORT:-8767}
CP="$MODULE/target/classes:$MODULE/target/lib/*"
mkdir -p "$OUT"
[ -d "$MODULE/target/lib" ] || mvn -B -q -f "$MODULE/pom.xml" dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/lib >&2
NOISE="Picked up\|^INFO\|LoomLoader\|^[A-Z][a-z][a-z] [0-9][0-9], 20\|HarnessExecutor\|^WARNING\|SLF4J"
weave() { java -cp "$CP" io.github.llm4j.loom.cli.WeaveCLI "$@" 2>&1 | grep -v "$NOISE"; }
services_pid=
start_services() {
  python3 "$ROOT/scripts/g5/autonomy_services.py" --port "$PORT" --log "$1" "${@:2}" > "$WORK/services.out" 2>&1 & services_pid=$!
  for _ in $(seq 1 50); do grep -q "fake services" "$WORK/services.out" 2>/dev/null && return; sleep 0.1; done
}
stop_services() { [ -n "$services_pid" ] && kill "$services_pid" 2>/dev/null; wait "$services_pid" 2>/dev/null; services_pid=; }
trap 'stop_services; rm -rf "$WORK"' EXIT
export OLLAMA_BASE_URL="http://127.0.0.1:$PORT" HOME="$WORK/home"; mkdir -p "$HOME"
D=$WORK/run; mkdir -p "$D"; cp "$ROOT/scripts/g5/refund.loom" "$D/"; cd "$D" || exit 1

# case N: odd cases are small refunds, even cases large ones; the person agrees with the model except on every 12th case
amount() { if [ $(($1 % 2)) -eq 1 ]; then echo 50; else echo 500; fi; }
person() { if [ $(($1 % 12)) -eq 0 ]; then echo escalate; elif [ "$(amount "$1")" = 50 ]; then echo approve; else echo reject; fi; }
case_run() { # n [extra weave args]; the shell is the person
  local n=$1; shift
  person "$n" | java -cp "$CP" io.github.llm4j.loom.cli.WeaveCLI run refund.loom -w Triage -i "ticket=T$n" -i tier=gold -i "amount=$(amount "$n")" \
    --journal "runs/c$n" --store store "$@" 2>&1 | grep -E "^\? Workflow completed|rror|HUMAN PROMPT"
}
ledgers() { cat store/autonomy/Refund/*.jsonl store/autonomy/Refund/*/*.jsonl 2>/dev/null; }

echo "== A1: sixty cases, watch to suggest to act, the shell as the person"
start_services "$WORK/a1.log"
{
  echo "\$ weave run refund.loom -w Triage -i ticket=Tn -i tier=gold -i amount=... --journal runs/cn --store store    (n = 1..60; the shell answers the person's question)"
  prev=""
  for n in $(seq 1 60); do
    case_run "$n" > "$WORK/case.txt"
    now=$(weave autonomy status store | grep -o -E "\b(watch|suggest|act)\b" | head -1)
    if [ "$now" != "$prev" ]; then echo "after case $n: the ladder stands at '$now'"; prev=$now; fi
  done
  echo "--- the last case:"; cat "$WORK/case.txt"
  echo "--- weave autonomy status store"; weave autonomy status store
  echo "--- weave autonomy history store"; weave autonomy history store
  echo "--- model calls: $(grep -c . "$WORK/a1.log")"
} > "$OUT/A1-ladder.txt" 2>&1
stop_services

echo "== A2: kill -9 in the middle of a case, then resume"
start_services "$WORK/a2a.log" --hang T901
{
  echo "\$ weave run ... ticket=T901 (the model's call hangs)"
  person 5 | java -cp "$CP" io.github.llm4j.loom.cli.WeaveCLI run refund.loom -w Triage -i ticket=T901 -i tier=gold -i amount=50 --journal runs/kill --store store > "$WORK/a2-first.out" 2>&1 &
  WPID=$!
  for _ in $(seq 1 100); do grep -q 'T901' "$WORK/a2a.log" && break; sleep 0.2; done
  sleep 1; kill -9 "$WPID" 2>/dev/null; wait "$WPID" 2>/dev/null
  echo "weave killed with SIGKILL while the model was thinking"
  stop_services; start_services "$WORK/a2b.log"
  echo "\$ the same command again (resume)"
  person 5 | java -cp "$CP" io.github.llm4j.loom.cli.WeaveCLI run refund.loom -w Triage -i ticket=T901 -i tier=gold -i amount=50 --journal runs/kill --store store 2>&1 | grep -E "^\? Workflow|rror"
  echo "--- cases the ledger holds: $(ledgers | grep -c '"kind":"case"')  (60 from A1 + 1)"
} > "$OUT/A2-kill-resume.txt" 2>&1
stop_services

echo "== A3: replay with a changed prompt"
start_services "$WORK/a3.log"
{
  sed 's/Propose whether to approve this refund./Propose whether to approve this refund. APPROVE-EVERYTHING/' refund.loom > changed.loom
  before=$(ledgers | md5sum)
  echo "\$ weave replay --decision Refund --store store --candidate changed.loom --limit 15 --seed 1 refund.loom"
  weave replay --decision Refund --store store --candidate changed.loom --limit 15 --seed 1 refund.loom | head -40
  after=$(ledgers | md5sum)
  [ "$before" = "$after" ] && echo "--- the ledger is the same before and after the replay: unchanged" || echo "--- the ledger CHANGED"
  echo "--- the same by hand: weave fork of one of the sampled runs under the changed script, simulated"
  weave fork runs/c10 --to runs/forked10 --script changed.loom --at Triage/s0 --effects simulate --reason "what if" --allow-drift | head -10
} > "$OUT/A3-replay.txt" 2>&1
stop_services

echo "== A4: freeze, then a forced promotion"
start_services "$WORK/a4.log"
{
  asked() { local k=0; for n in "$@"; do case_run "$n" --trace | grep -q "HUMAN PROMPT" && k=$((k+1)); done; echo $k; }
  echo "ladder before: $(weave autonomy status store | sed -n 2p)"
  echo "at act, 6 more cases: the person was asked in $(asked 62 63 64 65 66 67) of them (only the 50% audit sample)"
  echo "\$ weave autonomy freeze store Refund --reason incident"
  weave autonomy freeze store Refund --reason incident
  echo "frozen, 6 more cases: the person was asked in $(asked 68 69 70 71 72 73) of them (act is stopped: every case is asked)"
  weave autonomy status store | head -3
  weave autonomy unfreeze store Refund --reason "all clear"
  echo "\$ demote to watch, then promote to act with --force"
  weave autonomy demote store Refund --scope gold --to watch --reason "test"
  for n in 81 83; do person "$n" | java -cp "$CP" io.github.llm4j.loom.cli.WeaveCLI run refund.loom -w Triage -i "ticket=T$n" -i tier=silver -i amount=50 --journal "runs/c$n" --store store > /dev/null 2>&1; done
  weave autonomy promote store Refund --scope silver --to act --force --reason "operator decision (no cases yet in this scope)"
  weave autonomy status store | sed -n 1,12p
  weave autonomy history store | tail -6
} > "$OUT/A4-freeze-forced.txt" 2>&1
stop_services
echo "transcripts in $OUT"
