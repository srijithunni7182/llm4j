#!/bin/bash

# Navigate to project root
cd "$(dirname "$0")"

# Cleanup any lingering processes on ports 8080 (Server) and 5173 (UI)
echo "Ensuring ports are clear..."
fuser -k -n tcp 8080 > /dev/null 2>&1
fuser -k -n tcp 5173 > /dev/null 2>&1

# Source Secrets (API Keys)
if [ -f "secrets.sh" ]; then
    echo "🔑 Sourcing secrets from secrets.sh..."
    source secrets.sh
else
    echo "⚠️  secrets.sh not found!"
fi

echo "Starting Nirmaan Yantra... The Autonomous Software Factory."

# Start Server
echo "[1/2] Launching Backend (Spring Boot)..."
cd nirmaan-yantra-server
mvn spring-boot:run > ../server.log 2>&1 &
SERVER_PID=$!
echo "Backend PID: $SERVER_PID"

# Start UI
echo "[2/2] Launching Frontend (React)..."
cd ../nirmaan-yantra-ui
npm run dev > ../ui.log 2>&1 &
UI_PID=$!
echo "Frontend PID: $UI_PID"

echo "------------------------------------------------"
echo "✅ Systems Online."
echo "   UI:     http://localhost:5173"
echo "   Server: http://localhost:8080"
echo "------------------------------------------------"
echo " logs are being written to server.log and ui.log"
echo "Press Ctrl+C to stop all services."

# Cleanup function
cleanup() {
    echo "Shutting down..."
    # Check if process exists before killing to avoid errors
    if ps -p $SERVER_PID > /dev/null; then kill $SERVER_PID; fi
    if ps -p $UI_PID > /dev/null; then kill $UI_PID; fi
    exit
}

trap cleanup SIGINT

# Keep script running
wait
