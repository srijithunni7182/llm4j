#!/usr/bin/env bash
# Says whether the Fabric server of a data directory is running, where, and which processes may report to it (names only, never keys).
#   fabric-status.sh [--data DIR] [--port N]
. "$(dirname "$0")/lib.sh"
DATA="./fabric-data"; PORT=9090
while [ $# -gt 0 ]; do
  case "$1" in
    --data) DATA="$2"; shift 2 ;;
    --port) PORT="$2"; shift 2 ;;
    -h|--help) sed -n '2,3p' "$0"; exit 0 ;;
    *) die "unknown option '$1' (see --help)" 2 ;;
  esac
done
[ -d "$DATA" ] || { say "No Fabric data directory at $DATA: it has not been set up here. Run fabric-up.sh."; exit 1; }
DATA="$(cd "$DATA" && pwd)"
PIDFILE="$DATA/fabric.pid"
if [ -f "$PIDFILE" ] && pid_alive "$(cat "$PIDFILE")"; then
  if wait_healthy "http://127.0.0.1:$PORT" 3 || wait_healthy "https://127.0.0.1:$PORT" 1; then
    say "running: pid $(cat "$PIDFILE"), port $PORT, data $DATA"
  else
    say "the process is alive (pid $(cat "$PIDFILE")) but does not answer on port $PORT: look at $DATA/fabric.log"
  fi
else
  say "not running (data $DATA exists). Start it with fabric-up.sh."
fi
if [ -f "$DATA/vault.json" ]; then
  need_java
  say "clients that may report:"
  java -jar "$(fabric_jar)" clients list --data "$DATA" 2>/dev/null | sed 's/^/  /' || true
fi
