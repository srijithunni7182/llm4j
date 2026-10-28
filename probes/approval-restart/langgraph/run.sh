#!/usr/bin/env bash
# Probe P5, LangGraph side. (python3 -m venv venv && venv/bin/pip install -r ../../crash-resume/langgraph/requirements.txt)
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
PY=${PROBE_PYTHON:-$HERE/../../crash-resume/langgraph/venv/bin/python}
WORK=$(mktemp -d); : > "$WORK/sends.log"
echo "== process 1: runs until the question, then ends"
"$PY" "$HERE/probe.py" ask "$WORK/sends.log" "$WORK/cp.db" 2>&1 | tail -2
echo "sends: $(wc -l < "$WORK/sends.log")"
echo "== process 2 (a new process, later): the answer resumes the same thread"
"$PY" "$HERE/probe.py" answer "$WORK/sends.log" "$WORK/cp.db" yes 2>&1 | tail -2
echo "sends after the answer: $(wc -l < "$WORK/sends.log")"
echo "== process 3: resume again with the same answer"
"$PY" "$HERE/probe.py" answer "$WORK/sends.log" "$WORK/cp.db" yes 2>&1 | tail -2
echo "sends at the end: $(wc -l < "$WORK/sends.log")"
rm -rf "$WORK"
