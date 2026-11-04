#!/usr/bin/env bash
# usage: PROBE_PYTHON=venv/bin/python probes/untested-guard/python/run.sh
exec "${PROBE_PYTHON:-python3}" "$(dirname "$0")/probe.py"
