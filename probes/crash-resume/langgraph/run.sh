#!/usr/bin/env bash
# Probe P1, LangGraph side. usage: run.sh [plain|keyed] [early|late]    (needs: python3 -m venv venv && venv/bin/pip install -r requirements.txt)
set -u
KIND=${1:-plain}
WHEN=${2:-early}
HERE=$(cd "$(dirname "$0")" && pwd)
PY=${PROBE_PYTHON:-$HERE/venv/bin/python}
WORK=$(mktemp -d)
FLAG=$([ "$KIND" = keyed ] && echo --key || echo "")
echo "== run 1: the process dies ($WHEN) =="
[ "$WHEN" = early ] && export CRASH_AFTER_SEND=1 || export CRASH_LATE=1
"$PY" "$HERE/probe.py" run1 "$WORK/sends.log" "$WORK/cp.db" $FLAG >/dev/null 2>&1
RC=$?; unset CRASH_AFTER_SEND CRASH_LATE
echo "exit code: $RC   sends so far: $(wc -l < "$WORK/sends.log")"
echo "== run 2: resume the same thread from the checkpoint =="
"$PY" "$HERE/probe.py" run2 "$WORK/sends.log" "$WORK/cp.db" $FLAG 2>&1 | tail -3
echo "sends after resume: $(wc -l < "$WORK/sends.log")"
echo "keys sent: $(tr '\n' ' ' < "$WORK/sends.log")"
rm -rf "$WORK"
