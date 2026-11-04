#!/usr/bin/env bash
# usage: PROBE_PYTHON=venv/bin/python probes/extractor-isolation/python/run.sh
exec "${PROBE_PYTHON:-python3}" "$(dirname "$0")/probe.py"
