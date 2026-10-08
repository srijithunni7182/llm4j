#!/usr/bin/env bash
#
# ⚡ GetViral launcher — pick a brain (Gemini / Ollama / demo), enter keys safely, build, launch, open.
#
#   ./launch.sh                 interactive
#   ./launch.sh --demo          no key, scripted demo model
#   ./launch.sh --gemini        prompt for (or reuse) a Gemini API key
#   ./launch.sh --ollama        fully local via Ollama
#   ./launch.sh --cli "idea"    run in the terminal instead of the web studio
#   ./launch.sh --port 8080     use another port
#   ./launch.sh --skip-build    don't rebuild (reuse the last jar)
#   ./launch.sh --forget-keys   delete keys saved by this launcher
#   ./launch.sh --install-desktop   add GetViral to the Ubuntu app grid (Activities)
#
# Keys are passed to Java through the environment (never on the command line, so they don't show in
# `ps`). If you choose to save them, they go to ~/.getviral/credentials with 600 permissions.
#
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
JAR="$HERE/target/getviral-5.0.jar"
CRED_DIR="$HOME/.getviral"
CRED_FILE="$CRED_DIR/credentials"

MODE=""; PORT="${GETVIRAL_PORT:-7070}"; SKIP_BUILD=false; CLI_IDEA=""; USE_CLI=false; INSTALL_DESKTOP=false

# ── style ─────────────────────────────────────────────────────────────────────
if [ -t 1 ]; then
  B=$'\033[1m'; D=$'\033[2m'; R=$'\033[0m'; PINK=$'\033[38;5;205m'; ORANGE=$'\033[38;5;214m'
  GREEN=$'\033[38;5;114m'; RED=$'\033[38;5;203m'; CYAN=$'\033[38;5;87m'
else
  B=""; D=""; R=""; PINK=""; ORANGE=""; GREEN=""; RED=""; CYAN=""
fi
say()  { printf '%s\n' "$*"; }
ok()   { printf '  %s✓%s %s\n' "$GREEN" "$R" "$*"; }
warn() { printf '  %s!%s %s\n' "$ORANGE" "$R" "$*"; }
die()  { printf '\n  %s✗ %s%s\n\n' "$RED" "$*" "$R" >&2; exit 1; }
ask()  { local prompt="$1" default="${2:-}" reply; printf '  %s' "$prompt" >&2; IFS= read -r reply || true; printf '%s' "${reply:-$default}"; }
ask_secret() { local prompt="$1" reply; printf '  %s' "$prompt" >&2; IFS= read -rs reply || true; printf '\n' >&2; printf '%s' "$reply"; }
mask() { local s="$1"; [ ${#s} -le 8 ] && { printf '••••'; return; }; printf '%s••••%s' "${s:0:4}" "${s: -4}"; }
yes_no() { local a; a="$(ask "$1 [y/N] " "n")"; case "$a" in y|Y|yes|YES) return 0 ;; *) return 1 ;; esac; }

# ── args ──────────────────────────────────────────────────────────────────────
while [ $# -gt 0 ]; do
  case "$1" in
    --demo) MODE="demo" ;;
    --gemini) MODE="gemini" ;;
    --ollama) MODE="ollama" ;;
    --cli) USE_CLI=true; shift; CLI_IDEA="${1:-}" ;;
    --port) shift; PORT="${1:?--port needs a number}" ;;
    --skip-build) SKIP_BUILD=true ;;
    --forget-keys) rm -f "$CRED_FILE"; ok "Removed saved keys ($CRED_FILE)"; exit 0 ;;
    --install-desktop) INSTALL_DESKTOP=true ;;
    -h|--help) sed -n '3,17p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "Unknown option: $1 (try --help)" ;;
  esac
  shift
done

# ── Ubuntu desktop entry ──────────────────────────────────────────────────────
if $INSTALL_DESKTOP; then
  APPS="$HOME/.local/share/applications"; ICONS="$HOME/.local/share/icons/hicolor/scalable/apps"
  mkdir -p "$APPS" "$ICONS"
  cat > "$ICONS/getviral.svg" <<'SVG'
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64"><defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#ff2e88"/><stop offset=".5" stop-color="#ff9a3d"/><stop offset="1" stop-color="#8b5cff"/></linearGradient></defs><rect width="64" height="64" rx="18" fill="url(#g)"/><path d="M36 10 18 36h12l-4 18 20-28H34z" fill="#fff"/></svg>
SVG
  cat > "$APPS/getviral.desktop" <<DESKTOP
[Desktop Entry]
Type=Application
Name=GetViral Studio
Comment=One idea in, a ready-to-post pack for X, Instagram Reels and YouTube out
Exec=bash -c 'cd "$HERE" && ./launch.sh; echo; read -rp "Press Enter to close…"'
Icon=getviral
Terminal=true
Categories=Development;AudioVideo;
Keywords=AI;agents;content;reels;youtube;
DESKTOP
  chmod +x "$APPS/getviral.desktop"
  command -v update-desktop-database >/dev/null 2>&1 && update-desktop-database "$APPS" >/dev/null 2>&1 || true
  ok "Installed — search for \"GetViral\" in Activities (it opens a terminal that runs this launcher)."
  exit 0
fi

cat <<EOF

  ${PINK}${B}⚡ GetViral${R}  ${D}one idea · every feed${R}
  ${D}12 agents · Loom · Engram · eval4j · ai-agent4j · addons${R}

EOF

# ── prerequisites ─────────────────────────────────────────────────────────────
command -v java >/dev/null 2>&1 || die "Java 17+ is required:  sudo apt install openjdk-17-jdk"
JAVA_MAJOR="$(java -version 2>&1 | awk -F'"' '/version/ {split($2, v, "."); print (v[1] == "1" ? v[2] : v[1]); exit}')"
[ "${JAVA_MAJOR:-0}" -ge 17 ] 2>/dev/null || die "Java 17+ is required (found ${JAVA_MAJOR:-unknown}):  sudo apt install openjdk-17-jdk"
ok "Java $JAVA_MAJOR"
if ! $SKIP_BUILD || [ ! -f "$JAR" ]; then
  command -v mvn >/dev/null 2>&1 || die "Maven is required to build:  sudo apt install maven   (or pass --skip-build with an existing jar)"
fi

# ── saved credentials ─────────────────────────────────────────────────────────
SAVED_GEMINI=""; SAVED_IG_USER=""; SAVED_IG_TOKEN=""
if [ -f "$CRED_FILE" ]; then
  # shellcheck disable=SC1090
  SAVED_GEMINI="$(sed -n 's/^GEMINI_API_KEY=//p' "$CRED_FILE" | head -1)"
  SAVED_IG_USER="$(sed -n 's/^IG_USER_ID=//p' "$CRED_FILE" | head -1)"
  SAVED_IG_TOKEN="$(sed -n 's/^IG_ACCESS_TOKEN=//p' "$CRED_FILE" | head -1)"
fi
save_cred() {
  local key="$1" value="$2"
  mkdir -p "$CRED_DIR"; chmod 700 "$CRED_DIR"
  touch "$CRED_FILE"; chmod 600 "$CRED_FILE"
  grep -v "^${key}=" "$CRED_FILE" > "$CRED_FILE.tmp" 2>/dev/null || true
  printf '%s=%s\n' "$key" "$value" >> "$CRED_FILE.tmp"
  mv "$CRED_FILE.tmp" "$CRED_FILE"; chmod 600 "$CRED_FILE"
  ok "Saved to $CRED_FILE (readable only by you — remove with --forget-keys)"
}

# ── choose the brain ──────────────────────────────────────────────────────────
if [ -z "$MODE" ]; then
  say "  ${B}Which brain should the agents use?${R}"
  say "    ${B}1${R}) Google Gemini   ${D}— real writing, AI images, real LLM judges (needs an API key)${R}"
  say "    ${B}2${R}) Ollama (local)  ${D}— free and private, runs on your machine${R}"
  say "    ${B}3${R}) Demo            ${D}— no key; scripted model, still runs every tool for real${R}"
  case "$(ask "Choose 1-3 [1]: " "1")" in
    2) MODE="ollama" ;;
    3) MODE="demo" ;;
    *) MODE="gemini" ;;
  esac
  say ""
fi

export GETVIRAL_PORT="$PORT"
# Local launches sign in with the dev login unless Google sign-in is configured. Never set this on a public server.
if [ -z "${GOOGLE_CLIENT_ID:-}" ]; then export GETVIRAL_DEV_LOGIN=true; fi
export GETVIRAL_PUBLIC_URL="${GETVIRAL_PUBLIC_URL:-http://localhost:$PORT}"
case "$MODE" in
  gemini)
    KEY="${GEMINI_API_KEY:-${GOOGLE_API_KEY:-}}"
    if [ -n "$KEY" ]; then
      ok "Using GEMINI_API_KEY from your environment ($(mask "$KEY"))"
    elif [ -n "$SAVED_GEMINI" ] && ! yes_no "Found a saved Gemini key ($(mask "$SAVED_GEMINI")). Enter a different one?"; then
      KEY="$SAVED_GEMINI"; ok "Using saved Gemini key"
    else
      say "  ${D}Get a free key at https://aistudio.google.com/apikey (input is hidden)${R}"
      KEY="$(ask_secret "Gemini API key: ")"
      KEY="$(printf '%s' "$KEY" | tr -d '[:space:]')"
      [ -n "$KEY" ] || die "No key entered. Re-run with --demo to try GetViral without one."
      NEW_KEY=true
    fi
    # Validate the key (header, not URL, so it doesn't land in proxy logs).
    if command -v curl >/dev/null 2>&1; then
      STATUS="$(curl -s -o /dev/null -w '%{http_code}' -m 10 -H "x-goog-api-key: $KEY" \
        https://generativelanguage.googleapis.com/v1beta/models 2>/dev/null || true)"
      case "$STATUS" in
        200) ok "Gemini key works" ;;
        400|401|403) die "Google rejected that key (HTTP $STATUS). Check it at https://aistudio.google.com/apikey" ;;
        *) warn "Couldn't verify the key (HTTP ${STATUS:-no response}) — continuing anyway." ;;
      esac
    fi
    if [ "${NEW_KEY:-false}" = true ] && yes_no "Save this key for next time?"; then save_cred GEMINI_API_KEY "$KEY"; fi
    export GEMINI_API_KEY="$KEY" GETVIRAL_MODE="gemini"
    MODEL="$(ask "Model [gemini-3.5-flash]: " "gemini-3.5-flash")"
    export GETVIRAL_MODEL="$MODEL"
    if yes_no "Enable AI video clips with Google Veo? ${D}(paid per clip, slow)${R}"; then
      export GETVIRAL_VEO=true; ok "Veo clips on"
    fi
    ;;
  ollama)
    OLLAMA_URL="${OLLAMA_BASE_URL:-http://localhost:11434}"
    command -v curl >/dev/null 2>&1 || die "curl is required to talk to Ollama:  sudo apt install curl"
    TAGS="$(curl -s -m 5 "$OLLAMA_URL/api/tags" || true)"
    [ -n "$TAGS" ] || die "Ollama isn't answering at $OLLAMA_URL. Start it with 'ollama serve' (https://ollama.com)."
    MODELS="$(printf '%s' "$TAGS" | grep -o '"name":"[^"]*"' | cut -d'"' -f4 | tr '\n' ' ')"
    [ -n "$MODELS" ] || die "Ollama has no models. Pull one first, e.g. 'ollama pull gemma3'."
    ok "Ollama at $OLLAMA_URL — models: $MODELS"
    FIRST="$(printf '%s' "$MODELS" | awk '{print $1}')"
    PICK="$(ask "Model [$FIRST]: " "$FIRST")"
    export GETVIRAL_MODE="ollama" GETVIRAL_MODEL="ollama/$PICK" OLLAMA_BASE_URL="$OLLAMA_URL"
    warn "Images use the free Pollinations.ai API (or local renders offline)."
    ;;
  demo)
    export GETVIRAL_MODE="demo"
    ok "Demo mode — no key needed"
    ;;
esac

# ── optional: Instagram publishing ────────────────────────────────────────────
if ! $USE_CLI; then
  if [ -n "${IG_USER_ID:-}" ] && [ -n "${IG_ACCESS_TOKEN:-}" ]; then
    ok "Instagram publishing: using IG_USER_ID/IG_ACCESS_TOKEN from your environment"
  elif [ -n "$SAVED_IG_USER" ] && [ -n "$SAVED_IG_TOKEN" ]; then
    export IG_USER_ID="$SAVED_IG_USER" IG_ACCESS_TOKEN="$SAVED_IG_TOKEN"
    ok "Instagram publishing: using saved account $SAVED_IG_USER"
  elif yes_no "Connect Instagram for real publishing? ${D}(otherwise publishing is a safe dry run)${R}"; then
    IGU="$(ask "Instagram professional account ID (IG_USER_ID): ")"
    IGT="$(ask_secret "Instagram access token (hidden): ")"
    if [ -n "$IGU" ] && [ -n "$IGT" ]; then
      export IG_USER_ID="$IGU" IG_ACCESS_TOKEN="$IGT"
      ok "Instagram connected — every post still needs your approval in the studio"
      if yes_no "Save the Instagram credentials?"; then save_cred IG_USER_ID "$IGU"; save_cred IG_ACCESS_TOKEN "$IGT"; fi
    else
      warn "Incomplete — publishing stays a dry run."
    fi
  fi
fi

# ── build ─────────────────────────────────────────────────────────────────────
if $SKIP_BUILD && [ -f "$JAR" ]; then
  ok "Skipping build (using $(basename "$JAR"))"
else
  say ""
  say "  ${CYAN}Building the llm4j stack + GetViral from source…${R} ${D}(first run downloads dependencies)${R}"
  LOG="$(mktemp -t getviral-build.XXXXXX)"
  if (cd "$ROOT" && mvn -q -B -DskipTests -Dgpg.skip -Dmaven.javadoc.skip=true install -pl src/examples/getviral -am) >"$LOG" 2>&1; then
    ok "Build complete"
    rm -f "$LOG"
  else
    tail -n 30 "$LOG" >&2
    die "Build failed — full log: $LOG"
  fi
fi
[ -f "$JAR" ] || die "Missing $JAR"

# ── run ───────────────────────────────────────────────────────────────────────
JAVA_OPTS=(-Djava.awt.headless=true -Dfile.encoding=UTF-8)
if $USE_CLI; then
  say ""
  exec java "${JAVA_OPTS[@]}" -jar "$JAR" --cli "${CLI_IDEA:-a 2-minute morning routine for busy students}"
fi

port_in_use() { (echo >/dev/tcp/127.0.0.1/"$PORT") >/dev/null 2>&1; }
if port_in_use; then
  if curl -s -m 2 "http://localhost:$PORT/api/info" 2>/dev/null | grep -q '"mode"'; then
    warn "A GetViral studio is already running on port $PORT."
    if yes_no "Stop it and start fresh?"; then
      PIDS="$(ss -ltnpH "sport = :$PORT" 2>/dev/null | grep -o 'pid=[0-9]*' | cut -d= -f2 | sort -u | tr '\n' ' ')"
      [ -n "$PIDS" ] || PIDS="$(lsof -t -i:"$PORT" 2>/dev/null || true)"
      [ -n "$PIDS" ] && kill $PIDS 2>/dev/null || true
      sleep 2
    else
      say "  Open ${B}http://localhost:$PORT${R}"; exit 0
    fi
  fi
  port_in_use && die "Port $PORT is busy. Try: ./launch.sh --port 7171"
fi

URL="http://localhost:$PORT"
open_browser() {
  for _ in $(seq 1 60); do
    if curl -s -m 1 "$URL/api/info" >/dev/null 2>&1; then
      if command -v xdg-open >/dev/null 2>&1; then xdg-open "$URL" >/dev/null 2>&1
      elif command -v open >/dev/null 2>&1; then open "$URL"
      fi
      return
    fi
    sleep 1
  done
}
say ""
say "  ${PINK}${B}Launching the studio → $URL${R}  ${D}(Ctrl+C to stop)${R}"
open_browser &
exec java "${JAVA_OPTS[@]}" -jar "$JAR"
