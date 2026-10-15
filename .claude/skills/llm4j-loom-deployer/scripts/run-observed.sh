#!/usr/bin/env bash
# Runs a Loom workflow with observing on: its own page (embedded) or reporting to a Fabric server. The Fabric client key goes into the process's
# secret store and is never printed or put on a command line.
#   run-observed.sh --script FLOW.loom [--embedded [PORT]] [--linger 5m]
#   run-observed.sh --script FLOW.loom --fabric URL --name NAME [--data FABRIC_DATA_DIR | --key-stdin] [--ca FILE] [--detail truncated|metadata|full]
#                   [--rules FILE] [--secrets STORE] [--] [more 'weave run' options: --max-cost 0.50 --prices p.properties ...]
# With --data (a Fabric on this computer) the client NAME is created if it does not exist. For a remote Fabric, the admin gives you the key:
#   pipe it in with --key-stdin the first time (it is stored, then not needed again).
. "$(dirname "$0")/lib.sh"
SCRIPT=""; MODE=embedded; PORT=8787; URL=""; NAME=""; DATA=""; CA=""; DETAIL=""; RULES=""; STORE=""; KEYSTDIN=0; LINGER=""; REST=()
while [ $# -gt 0 ]; do
  case "$1" in
    --script) SCRIPT="$2"; shift 2 ;;
    --embedded) MODE=embedded; if [ $# -gt 1 ] && [[ "$2" =~ ^[0-9]+$ ]]; then PORT="$2"; shift; fi; shift ;;
    --fabric) MODE=fabric; URL="$2"; shift 2 ;;
    --name) NAME="$2"; shift 2 ;;
    --data) DATA="$2"; shift 2 ;;
    --ca) CA="$2"; shift 2 ;;
    --detail) DETAIL="$2"; shift 2 ;;
    --rules) RULES="$2"; shift 2 ;;
    --secrets) STORE="$2"; shift 2 ;;
    --key-stdin) KEYSTDIN=1; shift ;;
    --linger) LINGER="$2"; shift 2 ;;
    --) shift; REST=("$@"); break ;;
    -h|--help) sed -n '2,10p' "$0"; exit 0 ;;
    *) die "unknown option '$1' (see --help)" 2 ;;
  esac
done
[ -n "$SCRIPT" ] || die "--script FLOW.loom is required" 2
[ -f "$SCRIPT" ] || die "the script '$SCRIPT' does not exist" 2
need_java
WEAVE="$(weave_jar)"; INSPECT="$(inspect_jars)"
RUN=(java -cp "$WEAVE:$INSPECT" io.github.llm4j.loom.cli.WeaveCLI)
SCRIPT_DIR="$(cd "$(dirname "$SCRIPT")" && pwd)"

OBS=()
if [ "$MODE" = fabric ]; then
  [ -n "$URL" ] || die "--fabric needs the server's address" 2
  [ -n "$NAME" ] || die "--fabric needs --name (how this process is shown on the server)" 2
  case "$URL" in http://127.0.0.1*|http://localhost*|https://*) ;; *) die "'$URL': a process reports to a server over https (plain http is accepted only for this computer's own address)." 3 ;; esac
  STORE="${STORE:-$SCRIPT_DIR/.loom/secrets.store}"; KEYFILE="${STORE%.store}.key"
  mkdir -p "$(dirname "$STORE")"; chmod 700 "$(dirname "$STORE")" 2>/dev/null || true
  if [ ! -f "$STORE" ]; then
    umask 077; [ -f "$KEYFILE" ] || { random_secret 40 > "$KEYFILE"; printf '\n' >> "$KEYFILE"; chmod 600 "$KEYFILE"; }
    "${RUN[@]}" secrets create --secrets "$STORE" --secrets-key-file "$KEYFILE" >/dev/null
    say "Created the process's secret store $STORE (master key in $KEYFILE, mode 600; keep both out of git)."
  fi
  if ! "${RUN[@]}" secrets list --secrets "$STORE" --secrets-key-file "$KEYFILE" 2>/dev/null | grep -q 'LOOM_FABRIC_CLIENT_KEY'; then
    if [ "$KEYSTDIN" = 1 ]; then
      "${RUN[@]}" secrets set LOOM_FABRIC_CLIENT_KEY --stdin --secrets "$STORE" --secrets-key-file "$KEYFILE" >/dev/null
      say "Stored the Fabric client key for '$NAME' from standard input."
    elif [ -n "$DATA" ]; then
      FAB="$(fabric_jar)"
      if java -jar "$FAB" clients list --data "$DATA" 2>/dev/null | grep -qx -- "$NAME\|[[:space:]]*$NAME"; then
        die "the client '$NAME' already exists on that Fabric but its key is not in $STORE. The key is shown once, so: revoke it ('java -jar fabric.jar clients revoke $NAME --data $DATA') and run this again, or pipe the key in with --key-stdin." 4
      fi
      # the key is on one line of 'clients add'; it goes straight into the store and is never echoed
      java -jar "$FAB" clients add "$NAME" --data "$DATA" | awk '/^[[:space:]]+lfk_/ {print $1}' \
        | "${RUN[@]}" secrets set LOOM_FABRIC_CLIENT_KEY --stdin --secrets "$STORE" --secrets-key-file "$KEYFILE" >/dev/null
      say "Created the Fabric client '$NAME' and stored its key in $STORE (not printed)."
    else
      die "the process has no Fabric client key in $STORE. Give --data DIR (a Fabric on this computer: the client is created) or pipe the key the admin gave you with --key-stdin." 4
    fi
  fi
  OBS=(--observe-fabric "$URL" --observe-name "$NAME" --secrets "$STORE" --secrets-key-file "$KEYFILE")
  [ -z "$CA" ] || OBS+=(--observe-ca "$CA")
else
  OBS=("--observe=$PORT")
  [ -z "$LINGER" ] || OBS+=(--observe-linger "$LINGER")
  say "The run's own page: http://127.0.0.1:$PORT (it is served while the run lasts${LINGER:+ and $LINGER after})."
fi
[ -z "$DETAIL" ] || OBS+=(--observe-detail "$DETAIL")
[ -z "$RULES" ] || OBS+=(--observe-rules "$RULES")
say "Running $SCRIPT with observing on."
exec "${RUN[@]}" run "$SCRIPT" "${OBS[@]}" ${REST[@]+"${REST[@]}"}
