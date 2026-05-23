#!/usr/bin/env bash
# ============================================================
# Tantrik IDE Console — Launcher Script
# ============================================================
# Starts the Spring Boot backend and (optionally) the Vite
# dev server for the frontend.
#
# Usage:
#   ./start.sh              # start backend only (serves built UI)
#   ./start.sh --dev        # start backend + Vite dev server
#   ./start.sh --build-ui   # rebuild UI then start backend
#   ./start.sh --help       # show this help
# ============================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER_DIR="$SCRIPT_DIR/tantrik-console-server"
UI_DIR="$SCRIPT_DIR/tantrik-console-ui"

DEV_MODE=false
BUILD_UI=false

# ── argument parsing ──────────────────────────────────────────────────────────
for arg in "$@"; do
  case "$arg" in
    --dev)       DEV_MODE=true ;;
    --build-ui)  BUILD_UI=true ;;
    --help|-h)
      echo "Usage: ./start.sh [--dev] [--build-ui] [--help]"
      echo ""
      echo "  (no flags)    Start the Spring Boot server only."
      echo "                The server serves the pre-built React UI at http://localhost:8090"
      echo ""
      echo "  --dev         Start the Spring Boot server AND the Vite dev server."
      echo "                Backend: http://localhost:8090"
      echo "                Frontend (hot-reload): http://localhost:5173"
      echo ""
      echo "  --build-ui    Rebuild the React UI into the server's static directory,"
      echo "                then start the Spring Boot server."
      echo ""
      echo "  --help        Show this help message."
      exit 0
      ;;
    *)
      echo "Unknown argument: $arg  (use --help for usage)"
      exit 1
      ;;
  esac
done

# ── colour helpers ────────────────────────────────────────────────────────────
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
RED='\033[0;31m'
NC='\033[0m' # No Colour

info()    { echo -e "${CYAN}[tantrik]${NC} $*"; }
success() { echo -e "${GREEN}[tantrik]${NC} $*"; }
warn()    { echo -e "${YELLOW}[tantrik]${NC} $*"; }
error()   { echo -e "${RED}[tantrik]${NC} $*" >&2; }

# ── prerequisite checks ───────────────────────────────────────────────────────
check_command() {
  if ! command -v "$1" &>/dev/null; then
    error "Required command not found: $1"
    error "Please install $1 and try again."
    exit 1
  fi
}

check_command java
check_command mvn

if $DEV_MODE || $BUILD_UI; then
  check_command node
  check_command npm
fi

JAVA_VERSION=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d'.' -f1)
if [[ "$JAVA_VERSION" -lt 17 ]]; then
  error "Java 17+ is required (found Java $JAVA_VERSION)"
  exit 1
fi

# ── kill any process already using port 8090 ─────────────────────────────────
PORT=8090
EXISTING_PID=$(lsof -ti tcp:$PORT 2>/dev/null || true)
if [[ -n "$EXISTING_PID" ]]; then
  warn "Port $PORT is in use by PID $EXISTING_PID — killing it..."
  kill -9 $EXISTING_PID 2>/dev/null || true
  sleep 1
  success "Port $PORT is now free."
fi

# ── create loom-scripts directory if missing ──────────────────────────────────
LOOM_DIR="$SERVER_DIR/loom-scripts"
if [[ ! -d "$LOOM_DIR" ]]; then
  info "Creating loom-scripts directory at $LOOM_DIR"
  mkdir -p "$LOOM_DIR/examples"
fi

# Copy sample scripts if the directory is empty
if [[ -z "$(ls -A "$LOOM_DIR" 2>/dev/null)" ]]; then
  SAMPLES_DIR="$SCRIPT_DIR/loom-scripts"
  if [[ -d "$SAMPLES_DIR" ]]; then
    info "Copying sample Loom scripts into loom-scripts/"
    cp -r "$SAMPLES_DIR"/. "$LOOM_DIR/"
    success "Sample scripts copied."
  fi
fi

# ── optional: rebuild UI ──────────────────────────────────────────────────────
if $BUILD_UI; then
  info "Building React UI..."
  pushd "$UI_DIR" > /dev/null
  if [[ ! -d node_modules ]]; then
    info "Installing npm dependencies..."
    npm install --silent
  fi
  npm run build
  popd > /dev/null
  success "UI built and copied to server static resources."
fi

# ── check that a built UI exists (warn if not) ────────────────────────────────
STATIC_DIR="$SERVER_DIR/src/main/resources/static"
if [[ ! -f "$STATIC_DIR/index.html" ]] && ! $DEV_MODE; then
  warn "No built UI found at $STATIC_DIR/index.html"
  warn "Run './start.sh --build-ui' to build the UI first, or use '--dev' for hot-reload."
fi

# ── build the backend JAR if needed ──────────────────────────────────────────
JAR="$SERVER_DIR/target/tantrik-console-server-0.0.1-SNAPSHOT.jar"
if [[ ! -f "$JAR" ]]; then
  info "Building Spring Boot server (first run)..."
  pushd "$SERVER_DIR" > /dev/null
  mvn package -q -DskipTests
  popd > /dev/null
  success "Server built."
fi

# ── cleanup on exit ───────────────────────────────────────────────────────────
PIDS=()
cleanup() {
  echo ""
  info "Shutting down..."
  for pid in "${PIDS[@]}"; do
    if kill -0 "$pid" 2>/dev/null; then
      kill "$pid" 2>/dev/null || true
    fi
  done
  success "All processes stopped."
}
trap cleanup EXIT INT TERM

# ── start Vite dev server (--dev mode only) ───────────────────────────────────
if $DEV_MODE; then
  VITE_PID=$(lsof -ti tcp:5173 2>/dev/null || true)
  if [[ -n "$VITE_PID" ]]; then
    warn "Port 5173 is in use by PID $VITE_PID — killing it..."
    kill -9 $VITE_PID 2>/dev/null || true
    sleep 1
  fi
  info "Starting Vite dev server on http://localhost:5173 ..."
  pushd "$UI_DIR" > /dev/null
  if [[ ! -d node_modules ]]; then
    info "Installing npm dependencies..."
    npm install --silent
  fi
  npm run dev &
  PIDS+=($!)
  popd > /dev/null
  sleep 1
fi

# ── start Spring Boot server ──────────────────────────────────────────────────
info "Starting Tantrik IDE Console server on http://localhost:8090 ..."
pushd "$SERVER_DIR" > /dev/null
mvn spring-boot:run -q &
PIDS+=($!)
popd > /dev/null

# ── wait and print access info ────────────────────────────────────────────────
sleep 3
echo ""
echo -e "${GREEN}╔══════════════════════════════════════════════════╗${NC}"
echo -e "${GREEN}║         Tantrik IDE Console is running           ║${NC}"
echo -e "${GREEN}╠══════════════════════════════════════════════════╣${NC}"
if $DEV_MODE; then
  echo -e "${GREEN}║  Frontend (dev):  http://localhost:5173          ║${NC}"
  echo -e "${GREEN}║  Backend API:     http://localhost:8090/api      ║${NC}"
else
  echo -e "${GREEN}║  Open in browser: http://localhost:8090          ║${NC}"
  echo -e "${GREEN}║  API base:        http://localhost:8090/api      ║${NC}"
fi
echo -e "${GREEN}║  Health check:    http://localhost:8090/api/health║${NC}"
echo -e "${GREEN}╚══════════════════════════════════════════════════╝${NC}"
echo ""
echo -e "  Press ${YELLOW}Ctrl+C${NC} to stop all services."
echo ""

# ── wait for all background processes ────────────────────────────────────────
wait
