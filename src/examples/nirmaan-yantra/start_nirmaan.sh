#!/usr/bin/env bash
# Builds what is out of date (the UI, the factory) and starts Nirmaan Yantra's web host.
#   ./start_nirmaan.sh            then open http://127.0.0.1:8080
# Settings (all optional): NIRMAAN_PORT, NIRMAAN_FABRIC_URL (report every run to a Fabric server), NIRMAAN_WORKSPACE, NODE_BIN.
# The model key is read from the encrypted store nirmaan-yantra-server/.loom/secrets.store (see nirmaan-yantra-server/README.md).
set -euo pipefail
cd "$(dirname "$0")"

# The UI is built with Vite 7, which needs Node 20.19 or newer. Use NODE_BIN's folder, or the newest nvm Node, when the default is older.
node_ok() { command -v node >/dev/null 2>&1 && node -e 'const [a,b]=process.versions.node.split(".").map(Number); process.exit(a>20||(a===20&&b>=19)?0:1)' 2>/dev/null; }
if ! node_ok; then
  if [ -n "${NODE_BIN:-}" ]; then export PATH="$NODE_BIN:$PATH"
  else newest="$(ls -d "$HOME"/.nvm/versions/node/v* 2>/dev/null | sort -V | tail -n 1 || true)"; [ -n "$newest" ] && export PATH="$newest/bin:$PATH"; fi
  node_ok || { echo "error: the UI needs Node 20.19 or newer (found '$(node -v 2>/dev/null || echo none)'). Install one, or set NODE_BIN=/path/to/node/bin." >&2; exit 2; }
fi

UI=nirmaan-yantra-ui
if [ ! -f "$UI/dist/index.html" ] || [ -n "$(find "$UI/src" "$UI/index.html" -newer "$UI/dist/index.html" -print -quit 2>/dev/null)" ]; then
  echo "[1/3] Building the UI..."
  (cd "$UI" && { [ -d node_modules ] || npm ci; } && npm run build >/dev/null)
else
  echo "[1/3] The UI is up to date."
fi

echo "[2/3] Building the factory..."
(cd nirmaan-yantra-server && mvn -q -DskipTests package)

echo "[3/3] Starting the web host..."
cd nirmaan-yantra-server
exec java -cp "target/classes:target/lib/*" io.github.llm4j.nirmaan.host.NirmaanHost
