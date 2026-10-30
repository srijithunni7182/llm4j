#!/usr/bin/env bash
# Stops the Fabric server of a data directory (delegates to `weave fabric down` when the weave in use has it) (what it holds stays on disk). Safe to run when it is not running.
#   fabric-down.sh [--data DIR]
. "$(dirname "$0")/lib.sh"
DATA="./fabric-data"
while [ $# -gt 0 ]; do
  case "$1" in
    --data) DATA="$2"; shift 2 ;;
    -h|--help) sed -n '2,3p' "$0"; exit 0 ;;
    *) die "unknown option '$1' (see --help)" 2 ;;
  esac
done
rc=0; weave_fabric down --data "$DATA" || rc=$?
[ "$rc" = 127 ] || exit "$rc"
PIDFILE="$DATA/fabric.pid"
if [ ! -f "$PIDFILE" ]; then say "Fabric is not running (no pid file in $DATA). Nothing to do."; exit 0; fi
PID="$(cat "$PIDFILE")"
if pid_alive "$PID"; then
  kill "$PID" 2>/dev/null || true
  for _ in $(seq 1 20); do pid_alive "$PID" || break; sleep 0.5; done
  pid_alive "$PID" && { kill -9 "$PID" 2>/dev/null || true; say "Fabric (pid $PID) did not stop in 10 seconds and was killed."; } || say "Fabric (pid $PID) stopped."
else
  say "Fabric was not running (a stale pid file was removed)."
fi
rm -f "$PIDFILE"
