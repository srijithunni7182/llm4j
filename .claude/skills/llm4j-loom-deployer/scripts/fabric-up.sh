#!/usr/bin/env bash
# Brings up a Fabric server in the background: creates its data directory and admin password the first time, starts it, waits until it answers.
#   fabric-up.sh [--data DIR] [--port N] [--bind ADDR] [--generate-password] [-- extra 'fabric serve' options]
# The admin password is never taken from a command line and never printed: set LOOM_FABRIC_ADMIN_PASSWORD (12+ characters) for the first start, or pass
# --generate-password to have one written to DIR/admin-password.txt (mode 600) for the developer to read. A second run changes nothing.
# Listening beyond this computer needs TLS or a proxy: pass --tls-keystore/--tls-cert or --trust-proxy after the double dash, and ask the user first.
. "$(dirname "$0")/lib.sh"
DATA="./fabric-data"; PORT=9090; BIND=127.0.0.1; GENERATE=0; EXTRA=()
while [ $# -gt 0 ]; do
  case "$1" in
    --data) DATA="$2"; shift 2 ;;
    --port) PORT="$2"; shift 2 ;;
    --bind) BIND="$2"; shift 2 ;;
    --generate-password) GENERATE=1; shift ;;
    --) shift; EXTRA=("$@"); break ;;
    -h|--help) sed -n '2,8p' "$0"; exit 0 ;;
    *) die "unknown option '$1' (see --help)" 2 ;;
  esac
done
need_java
JAR="$(fabric_jar)"
mkdir -p "$DATA"; DATA="$(cd "$DATA" && pwd)"

# nothing listens beyond this computer without TLS or a proxy in front (the server refuses too; this says why first)
if ! is_loopback "$BIND"; then
  safe=0; for a in "${EXTRA[@]:-}"; do case "$a" in --trust-proxy|--tls-cert|--tls-cert=*|--tls-keystore|--tls-keystore=*) safe=1 ;; esac; done
  [ "$safe" = 1 ] || die "'$BIND' is not this computer. Fabric listens beyond it only with TLS (--tls-keystore ... or --tls-cert ... --tls-key ...) or behind a proxy that terminates it (--trust-proxy). Ask the user which, then pass it after '--'." 3
fi

URL="http://127.0.0.1:$PORT"
for a in "${EXTRA[@]:-}"; do case "$a" in --tls-*) URL="https://127.0.0.1:$PORT" ;; esac; done

PIDFILE="$DATA/fabric.pid"
if [ -f "$PIDFILE" ] && pid_alive "$(cat "$PIDFILE")" && wait_healthy "$URL" 2; then
  say "Fabric is already running: $URL (pid $(cat "$PIDFILE"), data $DATA). Nothing changed."
  exit 0
fi

if [ ! -f "$DATA/vault.json" ]; then
  if [ -z "${LOOM_FABRIC_ADMIN_PASSWORD:-}" ]; then
    [ "$GENERATE" = 1 ] || die "the first start needs an admin password: set LOOM_FABRIC_ADMIN_PASSWORD (12+ characters) in the environment, or pass --generate-password. It is never taken from a command line." 2
    umask 077
    random_secret 24 > "$DATA/admin-password.txt"; printf '\n' >> "$DATA/admin-password.txt"
    chmod 600 "$DATA/admin-password.txt"
    export LOOM_FABRIC_ADMIN_PASSWORD="$(head -n 1 "$DATA/admin-password.txt")"
    say "A new admin password was written to $DATA/admin-password.txt (mode 600). It was not printed."
  fi
  java -jar "$JAR" init --data "$DATA" >/dev/null || die "fabric init failed (is the password 12 or more characters?)" 4
  say "Initialised $DATA: vault.json and master.key. master.key sits next to the vault for development; in production move it elsewhere (see the deployment chapter)."
fi

# the server does not need the admin password: it holds only the hash, in the vault
env -u LOOM_FABRIC_ADMIN_PASSWORD nohup java -jar "$JAR" serve --data "$DATA" --port "$PORT" --bind "$BIND" ${EXTRA[@]+"${EXTRA[@]}"} >"$DATA/fabric.log" 2>&1 &
echo $! > "$PIDFILE"
if wait_healthy "$URL" 40; then
  say "Fabric is up: $URL (pid $(cat "$PIDFILE"), data $DATA, log $DATA/fabric.log)."
  say "Sign in on that page with the admin password$( [ -f "$DATA/admin-password.txt" ] && printf ' in %s' "$DATA/admin-password.txt" )."
else
  tail -n 15 "$DATA/fabric.log" >&2 || true
  kill "$(cat "$PIDFILE")" 2>/dev/null || true; rm -f "$PIDFILE"
  die "Fabric did not answer within 40 seconds (the log is above)." 5
fi
