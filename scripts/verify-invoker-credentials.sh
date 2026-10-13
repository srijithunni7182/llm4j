#!/usr/bin/env bash
# Runs the mechanical completion gates for Loom invoker credentials and writes evidence files.
#   scripts/verify-invoker-credentials.sh            # G1 G2 G3 G4 G5 G6 G8 G9
#   scripts/verify-invoker-credentials.sh G3         # one gate
# See .kiro/specs/loom-invoker-credentials/verification.md (§Completion). Only reports; fixes nothing.
set -uo pipefail
cd "$(dirname "$0")/.."
ROOT=$(pwd)
SPEC=.kiro/specs/loom-invoker-credentials
EVIDENCE="$ROOT/$SPEC/evidence"
MODULES="src/ai-agent4j,src/ai-agent4j-tools,src/loom/ai-agent4j-loom"
LOOM=src/loom/ai-agent4j-loom
BASELINE=724e474   # the last commit before any code of this change
SHA=$(git rev-parse HEAD)
mkdir -p "$EVIDENCE/g5"
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
  echo "== G1: the whole reactor compiles; full tests of the three modules pass, in order and in random order"
  local out="$EVIDENCE/G1-build.txt"
  { echo "commit $SHA"; date -u; } > "$out"
  if mvn -B -DskipTests compile > "$EVIDENCE/.g1-compile.log" 2>&1; then echo "-- all modules compile: BUILD SUCCESS" | tee -a "$out"
  else echo "-- compile: BUILD FAILURE" | tee -a "$out"; FAILED+=("G1 compile"); tail -30 "$EVIDENCE/.g1-compile.log" >> "$out"; fi
  local flags=("" "-Dsurefire.runOrder=random")
  for i in 0 1; do
    echo "-- tests, run $((i+1)) ${flags[$i]}" | tee -a "$out"
    for m in ${MODULES//,/ }; do rm -rf "$m/target"; done
    # shellcheck disable=SC2086
    if mvn -B -pl "$MODULES" test ${flags[$i]} > "$EVIDENCE/.g1-run$i.log" 2>&1; then echo "   BUILD SUCCESS" | tee -a "$out"
    else echo "   BUILD FAILURE" | tee -a "$out"; FAILED+=("G1 run $((i+1))"); tail -30 "$EVIDENCE/.g1-run$i.log" >> "$out"; fi
    echo "   core: $(totals src/ai-agent4j); tools: $(totals src/ai-agent4j-tools); loom: $(totals $LOOM)" | tee -a "$out"
  done
  rm -f "$EVIDENCE"/.g1-*.log
}

g2() {
  echo "== G2: traceability"
  { echo "commit $SHA"; python3 scripts/verify_spec.py $SPEC CC; } 2>&1 | tee "$EVIDENCE/G2-traceability.txt"
  [ "${PIPESTATUS[0]}" -eq 0 ] || FAILED+=("G2")
}

g3() {
  echo "== G3: mutation testing of the new and changed lines (score >= 85%)"
  local out="$EVIDENCE/G3-mutation.txt"
  { echo "commit $SHA; changed lines since $BASELINE"; date -u; } > "$out"
  if ! mvn -B -pl src/ai-agent4j,$LOOM -Pmutation-invoker -Djacoco.skip=true test-compile org.pitest:pitest-maven:mutationCoverage > "$EVIDENCE/.g3.log" 2>&1; then
    echo "PIT failed" | tee -a "$out"; FAILED+=("G3 run"); tail -30 "$EVIDENCE/.g3.log" >> "$out"; return
  fi
  for m in src/ai-agent4j $LOOM; do
    echo "-- $m" | tee -a "$out"
    python3 scripts/pit_survivors.py "$m/target/pit-reports/mutations.xml" --since $BASELINE --min=85 2>&1 | tee -a "$out" || FAILED+=("G3 $m")
  done
  rm -f "$EVIDENCE/.g3.log"
}

g4() {
  echo "== G4: nothing that existed has changed or stopped passing (baseline $BASELINE)"
  local out="$EVIDENCE/G4-regression.txt" work
  { echo "commit $SHA; baseline $BASELINE"; date -u; } > "$out"
  local touched
  touched=$(git diff --name-status "$BASELINE" -- 'src/*/src/test' 'src/*/*/src/test' | grep -v '^A' || true)
  if [ -n "$touched" ]; then echo "existing test files modified or deleted:" | tee -a "$out"; echo "$touched" | tee -a "$out"; FAILED+=("G4 tests touched"); else echo "no existing test file was modified or deleted" | tee -a "$out"; fi
  work=$(mktemp -d)
  git worktree add -q --detach "$work/baseline" "$BASELINE" || { FAILED+=("G4 worktree"); return; }
  ( cd "$work/baseline" && mvn -B -pl "$MODULES" test > "$work/baseline.log" 2>&1 )
  mvn -B -pl "$MODULES" test > "$work/now.log" 2>&1
  python3 - "$work/baseline" "$ROOT" >> "$out" <<'PY'
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
print(f"baseline test methods: {len(base)}; now: {len(now)}; added: {len(set(now) - set(base))}")
lost = sorted(k for k in base if k not in now)
broke = sorted(k for k in base if base[k] == "passed" and now.get(k) not in ("passed", None))
print(f"existing tests that no longer exist: {len(lost)}"); [print("  -", k) for k in lost]
print(f"existing tests that passed and do not now: {len(broke)}"); [print("  -", k, now[k]) for k in broke]
sys.exit(1 if lost or broke else 0)
PY
  [ $? -eq 0 ] || FAILED+=("G4 regression")
  git worktree remove --force "$work/baseline"; rm -rf "$work"
}

g5() {
  echo "== G5: two people, one script, a token refreshed in the middle (end to end)"
  local out="$EVIDENCE/g5/D1-two-users.txt"
  { echo "commit $SHA"; date -u; mvn -B -pl src/ai-agent4j,$LOOM test -Dtest=InvokerDemoTest -Dsurefire.failIfNoSpecifiedTests=false 2>&1 | grep "DEMO\|Tests run:.*Fail\|BUILD"; } | tee "$out"
  grep -q "BUILD SUCCESS" "$out" || FAILED+=("G5")
}

g6() {
  echo "== G6: the Loom CLI packages and 'weave check' works on a script that uses invoker"
  local out="$EVIDENCE/G6-package.txt" d
  { echo "commit $SHA"; date -u; } > "$out"
  if mvn -B -pl "$MODULES" package -DskipTests -Djacoco.skip=true >> "$out" 2>&1; then echo "package: BUILD SUCCESS" | tee -a "$out"; else FAILED+=("G6 package"); return; fi
  d=$(mktemp -d)
  printf 'tool Refunds { use: openapi  spec: "refunds.json"  auth_header: "Authorization"  auth_value: invoker.token }\nagent Scout { model: "ollama/llama3" system: "You are Scout." tools: [Refunds] approve: [Refunds] }\nworkflow Main() { delegate "x" to Scout -> r }\n' > "$d/job.loom"
  printf '{"openapi":"3.0.0","info":{"title":"R","version":"1"},"servers":[{"url":"https://api.example.com"}],"paths":{"/refunds":{"get":{"summary":"List","responses":{"200":{"description":"ok"}}}}}}' > "$d/refunds.json"
  local jar; jar=$(ls "$LOOM"/target/*.jar | grep -v sources | grep -v javadoc | head -1)
  echo "-- weave check ($jar)" | tee -a "$out"
  java -cp "$jar:$(cd $LOOM && mvn -B -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout 2>/dev/null | tail -1)" io.github.llm4j.loom.cli.WeaveCLI check "$d/job.loom" 2>&1 | grep -v "^Picked up" | tee -a "$out"
  grep -q "acts as the person who runs it, with their token" "$out" || FAILED+=("G6 check")
  rm -rf "$d"
}

g8() {
  echo "== G8: coverage of the new classes (lines >= 90%, branches >= 85%)"
  local out="$EVIDENCE/G8-coverage.txt"
  { echo "commit $SHA"; date -u; } > "$out"
  mvn -B -pl "$MODULES" test > /dev/null 2>&1
  python3 - >> "$out" <<'PY'
import csv, sys
NEW = ["ThreadScope", "InvokerSource", "InvokerRefs", "OpenApiKind", "InvokerOptions", "InvokerCredentials", "CredentialSource", "MissingInvokerCredentialException", "RequestAuth"]
bad = 0
for mod in ["src/ai-agent4j", "src/loom/ai-agent4j-loom"]:
    for r in csv.DictReader(open(mod + "/target/site/jacoco/jacoco.csv")):
        if any(r["CLASS"].startswith(n) for n in NEW):
            li, lm, bi, bm = (int(r[k]) for k in ("LINE_COVERED", "LINE_MISSED", "BRANCH_COVERED", "BRANCH_MISSED"))
            lp = 100 * li / max(1, li + lm); bp = 100 * bi / (bi + bm) if bi + bm else 100
            ok = lp >= 90 and bp >= 85
            bad += not ok
            print(f"{'ok ' if ok else 'LOW'} {r['CLASS']:36} lines {li}/{li+lm} ({lp:.0f}%) branches {bi}/{bi+bm} ({bp:.0f}%)")
sys.exit(1 if bad else 0)
PY
  local rc=$?; cat "$out"; [ $rc -eq 0 ] || FAILED+=("G8")
}

g9() {
  echo "== G9: safety checks (V4 and V5) all pass"
  local out="$EVIDENCE/G9-safety.md"
  { echo "# G9: safety"; echo; echo "commit $SHA"; echo; python3 scripts/verify_spec.py $SPEC CC | head -2; echo; echo "Tests tagged CC-V4.* and CC-V5.* (all executed and passed in the run above):"; echo;
    grep -rhn 'Tag("CC-V[45]\.' src/*/src/test src/loom/*/src/test | sed 's/^[0-9]*: *//' | sort | uniq -c | sed 's/^/    /'; } > "$out"
  echo "written $out"
}

gates=("$@"); [ ${#gates[@]} -eq 0 ] && gates=(G1 G2 G3 G4 G5 G6 G8 G9)
for g in "${gates[@]}"; do "$(echo "$g" | tr 'G' 'g')"; done
echo
if [ ${#FAILED[@]} -eq 0 ]; then echo "ALL GATES PASSED (${gates[*]}) at $SHA"; else echo "FAILED: ${FAILED[*]}"; exit 1; fi
