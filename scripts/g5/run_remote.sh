#!/usr/bin/env bash
# Gate G5 for remote answers: real `weave` processes against a stand-in Telegram server and a stand-in model server. Transcripts go to evidence/g5/.
#   scripts/g5/run_remote.sh      (needs the classes built: mvn -pl src/loom/ai-agent4j-loom compile; dependencies are copied to target/lib)
set -uo pipefail
cd "$(dirname "$0")/../.."
ROOT=$(pwd)
MODULE=$ROOT/src/loom/ai-agent4j-loom
OUT=$ROOT/.kiro/specs/loom-remote-answers/evidence/g5
WORK=$(mktemp -d)
TG_PORT=${G5_TG_PORT:-8768}
LLM_PORT=${G5_PORT:-8767}
TOKEN="123456:FAKE-G5-TOKEN-do-not-use"
CP="$MODULE/target/classes:$MODULE/target/lib/*"
mkdir -p "$OUT"
[ -d "$MODULE/target/lib" ] || mvn -B -q -f "$MODULE/pom.xml" dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/lib >&2
NOISE="Picked up\|^INFO\|LoomLoader\|^[A-Z][a-z][a-z] [0-9][0-9], 20\|HarnessExecutor\|^WARNING\|SLF4J\|TriggerRunner\|ChannelHumanInterface"
weave() { java -cp "$CP" io.github.llm4j.loom.cli.WeaveCLI "$@" 2>&1 | grep -v "$NOISE"; }
pids=()
cleanup() { for p in "${pids[@]:-}"; do [ -n "$p" ] && kill "$p" 2>/dev/null; done; rm -rf "$WORK"; }
trap cleanup EXIT
export HOME="$WORK/home"; mkdir -p "$HOME"
export TELEGRAM_BOT_TOKEN="$TOKEN" TELEGRAM_CHAT_IDS=5550001 TELEGRAM_API_BASE="http://127.0.0.1:$TG_PORT" OLLAMA_BASE_URL="http://127.0.0.1:$LLM_PORT"
python3 "$ROOT/scripts/g5/telegram_fake.py" --port "$TG_PORT" --log "$WORK/tg.jsonl" --token "$TOKEN" > "$WORK/tg.out" 2>&1 & pids+=($!)
python3 "$ROOT/scripts/g5/autonomy_services.py" --port "$LLM_PORT" --log "$WORK/llm.jsonl" > "$WORK/llm.out" 2>&1 & pids+=($!)
for _ in $(seq 1 50); do grep -q "fake telegram" "$WORK/tg.out" 2>/dev/null && grep -q "fake services" "$WORK/llm.out" 2>/dev/null && break; sleep 0.1; done
reply() { curl -s -X POST "http://127.0.0.1:$TG_PORT/_reply" -d "{\"chat\": ${2:-5550001}, \"from\": ${3:-${2:-5550001}}, \"text\": \"$1\"}" > /dev/null; }
sent() { curl -s "http://127.0.0.1:$TG_PORT/_sent"; echo; }
nsent() { sent | grep -c .; }
code_of() { weave questions "$1" --json | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d[0]["code"] if d else "")'; }
D=$WORK/run; mkdir -p "$D"; cp "$ROOT/scripts/g5/digest.loom" "$ROOT/scripts/g5/refund.loom" "$D/"; cd "$D" || exit 1

echo "== B1: a decision asks on the phone, a reply comes back, one tick resumes it"
{
  echo "\$ weave run refund.loom -w Triage -i ticket=T1 -i tier=gold -i amount=50 --journal runs/c1 --store store --ask-via telegram   (no terminal, no stdin)"
  weave run refund.loom -w Triage -i ticket=T1 -i tier=gold -i amount=50 --journal runs/c1 --store store --ask-via telegram < /dev/null | grep -E "Waiting|Workflow|rror"
  echo "--- the message that arrived on the phone:"; sent | python3 -c 'import json,sys; [print("   | " + l) for m in map(json.loads, sys.stdin) for l in m["text"].splitlines()]'
  echo "--- parse_mode on that message: $(sent | python3 -c 'import json,sys; print([m["parse_mode"] for m in map(json.loads, sys.stdin)])')   (null = plain text)"
  echo "--- does it show the agent's proposal? $(sent | grep -c -i 'proposal\|confidence\|approve.*amount 50') matches (expected 0)"
  echo "\$ weave questions store"; weave questions store
  CODE=$(code_of store)
  echo "--- the person replies in the chat: \"$CODE approve\""
  reply "$CODE approve"
  echo "\$ weave tick store --ask-via telegram"
  weave tick store --ask-via telegram | grep -E "Recorded|Workflow|rror"
  echo "--- the confirmation on the phone: $(sent | tail -1 | python3 -c 'import json,sys; print(json.loads(sys.stdin.read())["text"])')"
  echo "--- messages sent in all: $(nsent) (the question and the confirmation; nothing sent twice)"
  echo "--- ledger cases: $(cat store/autonomy/Refund/ledger.jsonl | grep -c '"kind":"case"'), verdict approve recorded: $(grep -c 'approve' store/autonomy/Refund/ledger.jsonl)"
  echo "\$ weave questions store --all"; weave questions store --all
} > "$OUT/B1-decision-on-phone.txt" 2>&1

echo "== B2: a stranger's reply is ignored; the daemon is killed with -9 and a second one carries on"
{
  weave run digest.loom -w Digest -i topic=bees --journal runs/d2 --store store2 --ask-via telegram < /dev/null | grep -E "Waiting|rror"
  CODE=$(code_of store2)
  M0=$(nsent)
  echo "--- a stranger (chat 999) replies \"$CODE yes\""
  reply "$CODE yes" 999
  java -cp "$CP" io.github.llm4j.loom.cli.WeaveCLI daemon store2 --ask-via telegram --poll 1s > "$WORK/d1.out" 2>&1 & DPID=$!
  sleep 4
  echo "--- after 4s of the daemon: question still open? $(weave questions store2 | grep -c "$CODE") (expected 1); the stranger was not answered: $(( $(nsent) - M0 )) notes sent (expected 0)"
  kill -9 "$DPID" 2>/dev/null; wait "$DPID" 2>/dev/null
  echo "--- daemon killed with SIGKILL; the person now replies \"$CODE yes\""
  reply "$CODE yes"
  java -cp "$CP" io.github.llm4j.loom.cli.WeaveCLI daemon store2 --ask-via telegram --poll 1s > "$WORK/d2.out" 2>&1 & DPID=$!
  for _ in $(seq 1 40); do grep -q "Workflow completed" "$WORK/d2.out" && break; sleep 0.5; done
  kill "$DPID" 2>/dev/null; wait "$DPID" 2>/dev/null
  echo "--- second daemon: $(grep -E "Workflow completed|Recorded" "$WORK/d2.out" | head -2 | tr '\n' ' ')"
  echo "--- the answer was applied once: $(grep -c 'answered' store2/channel/audit.jsonl) audit line(s) for answers"
  grep -E '"event"' store2/channel/audit.jsonl | cut -c1-200
} > "$OUT/B2-stranger-and-kill.txt" 2>&1

echo "== B3: weave answer from a terminal on the host, a second answer is refused"
{
  weave run digest.loom -w Digest -i topic=ants --journal runs/d3 --store store3 --ask-via telegram < /dev/null | grep -E "Waiting|rror"
  CODE=$(code_of store3)
  echo "\$ weave answer store3 $CODE yes"; weave answer store3 "$CODE" yes
  echo "\$ weave answer store3 $CODE no"; weave answer store3 "$CODE" no
  echo "\$ weave tick store3     (no channel configured: the terminal answer is enough)"; weave tick store3 | grep -E "Workflow|rror"
} > "$OUT/B3-terminal-answer.txt" 2>&1

echo "== B4: a question nobody answers expires and the run carries on"
{
  mkdir -p store4; echo '{"channel":"telegram","expire":"2s"}' > store4/channel.json
  weave run digest.loom -w Digest -i topic=wasps --journal runs/d4 --store store4 < /dev/null | grep -E "Waiting|rror"
  sleep 3
  echo "\$ weave tick store4   (after the question's 2 seconds are up)"; weave tick store4 | grep -E "Workflow|rror"
  echo "--- the question: $(weave questions store4 --all | head -1 | cut -c1-80)"
  echo "--- the phone was told: $(sent | tail -1 | python3 -c 'import json,sys; print(json.loads(sys.stdin.read())["text"])')"
} > "$OUT/B4-expiry.txt" 2>&1

echo "== B5: the token appears nowhere"
{
  hits=$(grep -rl "FAKE-G5-TOKEN" . 2>/dev/null | wc -l)
  echo "files under the run directory that contain the token: $hits (expected 0)"
  echo "audit lines: $(cat store*/channel/audit.jsonl | wc -l); token in them: $(cat store*/channel/audit.jsonl | grep -c FAKE-G5-TOKEN)"
} > "$OUT/B5-secrets.txt" 2>&1
echo "transcripts in $OUT"
