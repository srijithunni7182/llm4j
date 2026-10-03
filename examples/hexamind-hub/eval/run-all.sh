#!/usr/bin/env bash
# One command for the whole Hexamind evaluation: all layers, one report. See eval/RUN-PLAN.md.
#
#   eval/run-all.sh --fake            free: scripted models stand in for Gemini and Claude (checks the pipeline)
#   eval/run-all.sh                   real run, hard cap $10 (override: EVAL_CAP_USD); asks for the safety checks first
#   GEMINI_FREE_TIER=1 EVAL_CAP_USD=5 eval/run-all.sh   free-tier Gemini key, cap on Claude spend only
#   eval/run-all.sh --stage smoke     only the one-scenario smoke test (about one cent)
#
# Keys are read from the environment only: GEMINI_API_KEY, ANTHROPIC_API_KEY. They are never printed or stored.
set -euo pipefail

cd "$(dirname "$0")/.."
FAKE=0; STAGE=all
while [ $# -gt 0 ]; do
  case "$1" in
    --fake) FAKE=1 ;;
    --stage) STAGE="$2"; shift ;;
    -h|--help) sed -n '2,10p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
  shift
done

CAP="${EVAL_CAP_USD:-10}"
# GEMINI_FREE_TIER=1: the Gemini key costs nothing, so the spend guard counts only the Claude judge against the cap
GUARD_PRICES=eval/prices.properties
if [ "${GEMINI_FREE_TIER:-0}" = 1 ]; then
  GUARD_PRICES="$(mktemp /tmp/eval-prices.XXXXXX)"
  sed 's/^gemini-3.5-flash *=.*/gemini-3.5-flash = 0, 0/' eval/prices.properties > "$GUARD_PRICES"
fi
if [ "$FAKE" = 1 ]; then OUT=target/eval4j-fake; else OUT=target/eval4j; fi
MVN=(mvn -B -q -Deval4j.export.dir="$OUT" -Deval4j.pricing=eval/prices.properties -Deval.capUsd="$CAP" -Deval.prices="$GUARD_PRICES"
     -Dmaven.test.failure.ignore=true)
[ "$FAKE" = 1 ] && MVN+=(-Deval.fake=true)

# run maven with its (very chatty) output in a log file; print only the test totals
mvnq() {
  local log="target/eval/mvn-$1.log"; shift
  mkdir -p target/eval
  "${MVN[@]}" "$@" > "$log" 2>&1 || true
  grep -E "Tests run:.*Skipped: [0-9]+$" "$log" | tail -1 || true
  if grep -q "COMPILATION ERROR\|BUILD FAILURE" "$log"; then grep -m5 "ERROR.*\.java\|ERROR.*symbol" "$log"; die "build failed, see $log"; fi
}

say() { printf '\n== %s\n' "$*"; }
die() { printf 'STOP: %s\n' "$*" >&2; exit 1; }
spend() { [ -f target/eval/spend.json ] && cat target/eval/spend.json || echo '(no spend recorded)'; }

# ---- stage 0: preflight ------------------------------------------------------------------------------
say "stage 0: preflight"
command -v mvn >/dev/null || die "mvn not found"
[ -f eval/prices.properties ] || die "eval/prices.properties missing"
if [ "$FAKE" = 0 ]; then
  [ -n "${GEMINI_API_KEY:-}" ]    || die "GEMINI_API_KEY is not set (export it in your shell; do not put it in a file)"
  [ -n "${ANTHROPIC_API_KEY:-}" ] || die "ANTHROPIC_API_KEY is not set"
  [ "${EVAL_LIMITS_CONFIRMED:-}" = 1 ] || die "set EVAL_LIMITS_CONFIRMED=1 once you have set spending limits at both providers (RUN-PLAN.md, 'Before anything runs')"
  echo "keys present (not shown); cap \$$CAP; limits confirmed by you"
else
  echo "fake mode: no keys, no spend"
fi
rm -rf target/eval "$OUT" ; mkdir -p target/eval

# ---- stage 4 first, because it is free: workflow logic on a scripted model ---------------------------
say "stage 4: workflow logic, golden dataset and spend guard (free)"
mvnq free -Dtest='GoldenDatasetTest,TrajectoryPathTest,SpendGuardAndReplayTest' -Dsurefire.failIfNoSpecifiedTests=false test
for f in target/surefire-reports/*GoldenDatasetTest.txt target/surefire-reports/*TrajectoryPathTest.txt target/surefire-reports/*SpendGuardAndReplayTest.txt; do
  if grep -q "FAILURE" "$f"; then die "free tests failed: see $f. These test the workflow, not the model; fix them before spending."; fi
done
echo "ok"
[ "$STAGE" = path ] && exit 0

# ---- stage 1: smoke ----------------------------------------------------------------------------------
say "stage 1: smoke test (alex-02 end to end)"
mvnq smoke -Peval -Dtest=SmokeEvalTest test
if grep -q "FAILURE\|ERROR" target/surefire-reports/*SmokeEvalTest.txt 2>/dev/null; then
  spend; die "smoke test failed: see target/surefire-reports/*SmokeEvalTest.txt. Nothing else was run."
fi
grep -h "^SMOKE" target/eval/mvn-smoke.log || true
spend
[ "$STAGE" = smoke ] && exit 0
# the smoke run is its own bundle; clear it so the main report is one clean run
rm -rf "$OUT" target/eval/replay target/eval/judge-cache target/eval/judge-cache-fake

# ---- stages 2, 3, 5, 6 in one JVM, so the report is one run ------------------------------------------
say "stages 2, 3, 5, 6: reasoning, calibration, prompts, debates"
mvnq main -Peval -Dtest='AgentReasoningEvalTest,CalibrationEvalTest,PromptEvalTest,TrajectoryEvalTest' test
echo "(failed checks are findings, not errors; they are listed in the report. Full log: target/eval/mvn-main.log)"
spend

# ---- stage 7: report ---------------------------------------------------------------------------------
say "stage 7: report"
REPORT="$OUT/report/index.html"
[ -f "$REPORT" ] || die "no report was produced at $REPORT"
if grep -q '"stopped":null' target/eval/spend.json; then
  echo "run completed within the spend guard"
else
  echo "WARNING: the spend guard stopped the run; the report is partial. Reason is in the line above."
fi
echo "report: $(pwd)/$REPORT"
echo "summary: $(pwd)/$OUT/report/summary.md"
