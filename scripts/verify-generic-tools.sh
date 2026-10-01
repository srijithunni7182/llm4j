#!/usr/bin/env bash
# Runs the mechanical completion gates for the Loom generic tools and writes evidence files.
#   scripts/verify-generic-tools.sh               # G1, G2, G4, G6, G8
#   scripts/verify-generic-tools.sh G2            # one gate (G1, G2, G4, G6 or G8)
# G3 (sabotage), G5 (real runs), G7 (docs) and G9 (safety) are separate: see completion-verification.md.
# It only reports; it fixes nothing. Needs the project's Maven dependencies (offline is fine once fetched).
set -uo pipefail
cd "$(dirname "$0")/.."
ROOT=$(pwd)
EVIDENCE="$ROOT/.kiro/specs/loom-generic-tools/evidence"
MODULE=loom/ai-agent4j-loom
BASELINE=71f67cb   # the merged nifty-lovelace head, before the generic tools
SHA=$(git rev-parse HEAD)
mkdir -p "$EVIDENCE"
FAILED=()

mvn_module() { mvn -B -o -pl "$MODULE" "$@"; }

# Totals from the surefire reports of the module in $1.
totals() {
  python3 - "$1" <<'PY'
import sys, xml.etree.ElementTree as ET
from pathlib import Path
t = f = e = s = 0
for x in Path(sys.argv[1], "target/surefire-reports").glob("TEST-*.xml"):
    r = ET.parse(x).getroot()
    t += int(r.get("tests")); f += int(r.get("failures")); e += int(r.get("errors")); s += int(r.get("skipped"))
print(f"tests={t} failures={f} errors={e} skipped={s}")
PY
}

g1() {
  echo "== G1: clean build and full tests, three runs"
  local out="$EVIDENCE/G1-build.txt"
  { echo "commit $SHA"; date -u; } > "$out"
  local runs=("plain" "plain again" "random order and 20000 fuzz iterations")
  local flags=("" "" "-Dsurefire.runOrder=random -Dloom.fuzz.iterations=20000")
  local counts=()
  for i in 0 1 2; do
    echo "-- run $((i+1)): ${runs[$i]}" | tee -a "$out"
    # shellcheck disable=SC2086
    if mvn -B -pl "$MODULE" -am clean install ${flags[$i]} > "$EVIDENCE/.g1-run$i.log" 2>&1; then
      echo "   BUILD SUCCESS" | tee -a "$out"
    else
      echo "   BUILD FAILURE (see the log)" | tee -a "$out"; FAILED+=("G1 run $((i+1))"); tail -30 "$EVIDENCE/.g1-run$i.log" >> "$out"
    fi
    counts[$i]=$(totals "$MODULE"); echo "   ${counts[$i]}" | tee -a "$out"
  done
  [ "${counts[0]}" = "${counts[1]}" ] || { echo "   test counts differ between the two plain runs" | tee -a "$out"; FAILED+=("G1 counts"); }
  rm -f "$EVIDENCE"/.g1-run*.log
}

g2() {
  echo "== G2: traceability"
  local out="$EVIDENCE/G2-traceability.txt"
  { echo "commit $SHA"; python3 scripts/verify_traceability.py; } 2>&1 | tee "$out"
  [ "${PIPESTATUS[0]}" -eq 0 ] || FAILED+=("G2")
}

g4() {
  echo "== G4: nothing that existed has moved (baseline $BASELINE)"
  local out="$EVIDENCE/G4-regression.txt" work
  work=$(mktemp -d)
  git worktree add -q --detach "$work/baseline" "$BASELINE" || { FAILED+=("G4 worktree"); return; }
  ( cd "$work/baseline" && mvn -B -o -pl "$MODULE" test > "$work/baseline.log" 2>&1 )
  mvn_module test > "$work/now.log" 2>&1
  python3 - "$work/baseline/$MODULE" "$MODULE" "$SHA" "$BASELINE" > "$out" <<'PY'
import re, sys, xml.etree.ElementTree as ET
from pathlib import Path
def cases(module):
    out = {}
    for x in Path(module, "target/surefire-reports").glob("TEST-*.xml"):
        for c in ET.parse(x).getroot().iter("testcase"):
            bad = c.find("failure") is not None or c.find("error") is not None
            out[(c.get("classname"), re.sub(r"\(.*$|\[.*$", "", c.get("name")))] = "failed" if bad else ("skipped" if c.find("skipped") is not None else "passed")
    return out
base, now = cases(sys.argv[1]), cases(sys.argv[2])
print(f"commit {sys.argv[3]}; baseline {sys.argv[4]}")
print(f"baseline tests (methods): {len(base)}; now: {len(now)}; added: {len(set(now) - set(base))}")
lost = sorted(k for k in base if k not in now)
broken = sorted(k for k in base if k in now and base[k] == "passed" and now[k] != "passed")
print("tests that existed at the baseline and are gone:", len(lost))
for k in lost: print("   ", k)
print("tests that passed at the baseline and don't now:", len(broken))
for k in broken: print("   ", k)
sys.exit(1 if lost or broken else 0)
PY
  local code=$?
  cat "$out"
  git worktree remove --force "$work/baseline"; rm -rf "$work"
  [ $code -eq 0 ] || FAILED+=("G4")
}

g6() {
  echo "== G6: the packaged app"
  local out="$EVIDENCE/G6-package.txt" dir listing
  { echo "commit $SHA"; mvn_module package -DskipTests 2>&1 | tail -3; } > "$out"
  local jar
  jar="$ROOT/$(ls "$MODULE"/target/*.jar | grep -v -E 'original|sources|javadoc' | head -1)"
  echo "jar: $jar ($(du -h "$jar" | cut -f1))" | tee -a "$out"
  dir=$(mktemp -d); listing="$dir/jar.lst"; unzip -l "$jar" > "$listing"   # a file, so grep -q can't SIGPIPE unzip
  for entry in org/eclipse/angus/mail/smtp/SMTPTransport.class jakarta/mail/Session.class org/postgresql/Driver.class okhttp3/OkHttpClient.class; do
    if grep -q "$entry" "$listing"; then echo "   contains $entry" | tee -a "$out"; else echo "   MISSING $entry" | tee -a "$out"; FAILED+=("G6 $entry"); fi
  done
  for banned in greenmail mockwebserver junit org/h2; do
    if grep -q "$banned" "$listing"; then echo "   UNEXPECTED $banned (a test dependency) is in the jar" | tee -a "$out"; FAILED+=("G6 $banned"); else echo "   no $banned in the jar" | tee -a "$out"; fi
  done
  cp "$ROOT/$MODULE"/samples/digest/*.loom "$dir"/
  cd "$dir" || return
  echo "--- weave check from an empty directory, with only the jar:" | tee -a "$out"
  HN_URL=http://localhost:9 GEMINI_API_KEY=k java -verbose:class -jar "$jar" check digest.loom > "$dir/check.out" 2>&1
  grep -E "ready to run|problem" "$dir/check.out" | tee -a "$out"
  grep -q "digest.loom: ready to run" "$dir/check.out" || FAILED+=("G6 check")
  local mail
  mail=$(grep -c -E "jakarta\.mail\.|org\.eclipse\.angus\.mail" "$dir/check.out")
  echo "--- mail classes loaded while checking a script that declares an email tool (validation only): $mail" | tee -a "$out"
  [ "$mail" -eq 0 ] || FAILED+=("G6 lazy mail")
  cat > db.loom <<'LOOM'
tool Db { use: sql  url: env.DB_URL }
agent A { model: "gemini-2.5-flash"  tools: [Db] }
workflow Main() { delegate "x" to A -> r }
LOOM
  echo "--- sql with a driver that isn't there (Oracle):" | tee -a "$out"
  DB_URL="jdbc:oracle:thin:@secret-host:1521/svc" GEMINI_API_KEY=k java -jar "$jar" check db.loom 2>&1 | grep -E "driver|problem" | tee -a "$out"
  echo "--- sql with PostgreSQL (the packaged driver; no connection is made at load):" | tee -a "$out"
  DB_URL="jdbc:postgresql://db.example.com:5432/app" GEMINI_API_KEY=k java -jar "$jar" check db.loom 2>&1 | grep -E "ready to run|problem|driver" | tee -a "$out"
  echo "--- shell and sql declared together, checked from the jar (shell needs allow: and approval or unattended:):" | tee -a "$out"
  cat > both.loom <<'LOOM'
tool Sh { use: shell  allow: "echo,date"  unattended: true }
tool Db { use: sql  url: env.DB_URL }
agent A { model: "ollama/x"  system: "x"  tools: [Sh, Db] }
workflow Main { delegate "go" to A -> r }
LOOM
  DB_URL="jdbc:postgresql://db.example.com:5432/app" java -jar "$jar" check both.loom 2>&1 | grep -E "ready to run|problem" | tee -a "$out"
  cd "$ROOT" && rm -rf "$dir"
}

g8() {
  echo "== G8: coverage"
  local out="$EVIDENCE/G8-coverage.txt"
  mvn_module test > /dev/null 2>&1
  { echo "commit $SHA"; python3 scripts/coverage_report.py; } 2>&1 | tee "$out"
  [ "${PIPESTATUS[0]}" -eq 0 ] || FAILED+=("G8")
}

case "${1:-all}" in
  G1) g1 ;; G2) g2 ;; G4) g4 ;; G6) g6 ;; G8) g8 ;;
  all) g1; g2; g4; g6; g8 ;;
  *) echo "unknown gate $1" >&2; exit 2 ;;
esac
echo
if [ ${#FAILED[@]} -eq 0 ]; then echo "ALL REQUESTED GATES PASSED at $SHA"; else echo "FAILED: ${FAILED[*]}"; exit 1; fi
