#!/usr/bin/env bash
# Starts a Fabric server and simulated Loom processes (no model, no key). See src/examples/fabric-sim/README.md.
#   ./fabric-sim.sh                     3 processes
#   PROCESSES=5 SPEED=0.3 ./fabric-sim.sh
exec "$(dirname "$0")/src/examples/fabric-sim/run.sh" "$@"
