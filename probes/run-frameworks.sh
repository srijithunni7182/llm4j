#!/usr/bin/env bash
# Reruns probes P9 to P17 and stores what they print in each probe's results/ folder.
# usage: PROBE_PYTHON=venv/bin/python probes/run-frameworks.sh [probe-folder ...]
#   python3 -m venv venv && venv/bin/pip install -r probes/requirements.txt     (once)
# The Loom side needs the Loom module built (target/classes and target/lib). No keys, no network beyond the pip install.
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
PY=${PROBE_PYTHON:-python3}
PROBES=("$@")
[ ${#PROBES[@]} -eq 0 ] && PROBES=(extractor-isolation large-schema-with-tools exit-check approval-placement untested-guard tool-effects handoff-check long-loop-progress route-allowlist)
for p in "${PROBES[@]}"; do
  mkdir -p "$HERE/$p/results"
  echo "== $p: python side"
  PROBE_PYTHON=$PY "$HERE/$p/python/run.sh" 2>/dev/null | grep -v "^Skipping" > "$HERE/$p/results/python.md"
  echo "== $p: Loom side"
  "$HERE/$p/loom/run.sh" > "$HERE/$p/results/loom.txt" 2>&1
done
