#!/usr/bin/env bash
# Runs the mechanical completion gates for Loom earned autonomy and writes evidence files.
#   scripts/verify-autonomy.sh            # G1, G2, G4, G6, G8
#   scripts/verify-autonomy.sh G4         # one gate
# G3 (scripts/sabotage_autonomy.py), G5 (scripts/g5/run_autonomy.sh), G7 (docs) and G9 (safety) are separate. Only reports; fixes nothing.
set -uo pipefail
cd "$(dirname "$0")/.."
ROOT=$(pwd)
EVIDENCE="$ROOT/.kiro/specs/loom-earned-autonomy/evidence"
MODULES="src/ai-agent4j,src/ai-agent4j-tools,src/loom/ai-agent4j-loom"
MODULE=src/loom/ai-agent4j-loom
BASELINE=a1ff636   # the rewind-and-fork sign-off, before the earned-autonomy work
SHA=$(git rev-parse HEAD)
mkdir -p "$EVIDENCE"
FAILED=()

totals() {
  python3 - "$@" <<'PY'
import sys, xml.etree.ElementTree as ET
from pathlib import Path
t = f = e = s = 0
for m in sys.argv[1:]:
    for x in Path(m, "target/surefire-reports").glob("TEST-*.xml"):
        r = ET.parse(x).getroot()
        t += int(r.get("tests")); f += int(r.get("failures")); e += int(r.get("errors")); s += int(r.get("skipped"))
print(f"tests={t} failures={f} errors={e} skipped={s}")
PY
}

g1() {
  echo "== G1: clean build and full tests, three runs"
  local out="$EVIDENCE/G1-build.txt"
  { echo "commit $SHA"; date -u; } > "$out"
  local runs=("plain" "plain again" "random order, 100000 random ladder sequences")
  local flags=("" "" "-Dsurefire.runOrder=random -Dloom.autonomy.sequences=100000")
  local counts=()
  for i in 0 1 2; do
    echo "-- run $((i+1)): ${runs[$i]}" | tee -a "$out"
    # shellcheck disable=SC2086
    if mvn -B -o -pl "$MODULES" clean install ${flags[$i]} > "$EVIDENCE/.g1-run$i.log" 2>&1; then
      echo "   BUILD SUCCESS" | tee -a "$out"
    else
      echo "   BUILD FAILURE" | tee -a "$out"; FAILED+=("G1 run $((i+1))"); tail -30 "$EVIDENCE/.g1-run$i.log" >> "$out"
    fi
    counts[$i]="core: $(totals src/ai-agent4j); tools: $(totals src/ai-agent4j-tools); loom: $(totals $MODULE)"; echo "   ${counts[$i]}" | tee -a "$out"
  done
  [ "${counts[0]}" = "${counts[1]}" ] || { echo "   test counts differ between the two plain runs" | tee -a "$out"; FAILED+=("G1 counts"); }
  rm -f "$EVIDENCE"/.g1-run*.log
}

g2() {
  echo "== G2: traceability"
  local out="$EVIDENCE/G2-traceability.txt"
  { echo "commit $SHA"; python3 scripts/verify_spec.py .kiro/specs/loom-earned-autonomy EA; } 2>&1 | tee "$out"
  [ "${PIPESTATUS[0]}" -eq 0 ] || FAILED+=("G2")
}

g4() {
  echo "== G4: nothing that existed has moved (baseline $BASELINE)"
  local out="$EVIDENCE/G4-regression.txt" work
  work=$(mktemp -d)
  git worktree add -q --detach "$work/baseline" "$BASELINE" || { FAILED+=("G4 worktree"); return; }
  ( cd "$work/baseline" && mvn -B -o -pl "$MODULES" install > "$work/baseline.log" 2>&1 )
  mvn -B -o -pl "$MODULES" install > "$work/now.log" 2>&1
  python3 - "$work/baseline" "$ROOT" "$SHA" "$BASELINE" > "$out" <<'PY'
import re, sys, xml.etree.ElementTree as ET
from pathlib import Path
MODS = ["src/ai-agent4j", "src/ai-agent4j-tools", "src/loom/ai-agent4j-loom"]
def cases(root):
    out = {}
    for m in MODS:
        for x in Path(root, m, "target/surefire-reports").glob("TEST-*.xml"):
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
  local out="$EVIDENCE/G6-package.txt" dir jar
  { echo "commit $SHA"; mvn -B -o -pl "$MODULE" package -DskipTests 2>&1 | tail -3; } > "$out"
  jar="$ROOT/$(ls "$MODULE"/target/*.jar | grep -v -E 'original|sources|javadoc' | head -1)"
  echo "jar: $jar ($(du -h "$jar" | cut -f1))" | tee -a "$out"
  dir=$(mktemp -d); cd "$dir" || return
  cat > r.loom <<'LOOM'
agent Triager { model: "ollama/x" system: "t" output_schema: { choice: string, reasoning: string, confidence: number } }
decision Refund {
    proposed by:    Triager
    choices:        approve, reject, escalate
    group cases by: tier
    ask:            support-lead
    trust {
        start at watch
        to suggest: after 20 cases, agreeing at least 90%
        moving up is automatic
    }
}
workflow Main(tier) {
    decide Refund -> verdict
}
LOOM
  echo "--- weave check of a script with a decision, from an empty directory with only the jar:" | tee -a "$out"
  java -jar "$jar" check r.loom 2>&1 | grep -E "ready to run|problem|error" | tee -a "$out"
  grep -q "r.loom: ready to run" "$out" || FAILED+=("G6 check")
  java -jar "$jar" --help > help.txt 2>&1
  for cmd in autonomy replay timeline rewind fork; do
    if grep -q "^  $cmd " help.txt; then echo "   weave $cmd is in the jar" | tee -a "$out"; else echo "   MISSING weave $cmd" | tee -a "$out"; FAILED+=("G6 $cmd"); fi
  done
  echo "--- weave autonomy status on an empty store:" | tee -a "$out"
  java -jar "$jar" autonomy status "$dir/store" 2>&1 | grep -v "^Picked up" | tee -a "$out" | head -5
  grep -q "no decision has run" "$out" || FAILED+=("G6 status")
  cd "$ROOT" && rm -rf "$dir"
}

g8() {
  echo "== G8: coverage"
  local out="$EVIDENCE/G8-coverage.txt"
  { echo "commit $SHA"; mvn -B -o -pl "$MODULE" verify 2>&1 | grep -E "jacoco|coverage checks|BUILD|Tests run:.*Skipped: [0-9]+$" | tail -8
    python3 - <<'PY'
import csv
rows = list(csv.DictReader(open("src/loom/ai-agent4j-loom/target/site/jacoco/jacoco.csv")))
for r in rows:
    if r["CLASS"] in ("AgreementStats", "Ladder", "AgentIdentity", "EvidenceTool", "Decider", "Engine", "ReplayEngine", "LedgerCache", "FileLedger", "AutonomyCommands", "ReplayCommand"):
        b, m = int(r["BRANCH_COVERED"]), int(r["BRANCH_MISSED"]); l, lm = int(r["LINE_COVERED"]), int(r["LINE_MISSED"])
        print(f'{r["CLASS"]:16} branches {100*b/max(1,b+m):5.1f}%  lines {100*l/max(1,l+lm):5.1f}%')
PY
  } 2>&1 | tee "$out"
  grep -q "All coverage checks have been met" "$out" || FAILED+=("G8")
}

case "${1:-all}" in
  G1) g1 ;; G2) g2 ;; G4) g4 ;; G6) g6 ;; G8) g8 ;;
  all) g2; g8; g4; g6; g1 ;;
  *) echo "unknown gate $1" >&2; exit 2 ;;
esac
echo
if [ ${#FAILED[@]} -eq 0 ]; then echo "ALL REQUESTED GATES PASSED at $SHA"; else echo "FAILED: ${FAILED[*]}"; exit 1; fi
